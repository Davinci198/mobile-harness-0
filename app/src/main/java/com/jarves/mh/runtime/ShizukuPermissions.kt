package com.jarves.mh.runtime

/**
 * Granting and revoking another app's runtime permissions needs a real shell, which the
 * Shizuku service provides. The parsing here is pure so it can be unit-tested without a
 * device attached.
 */
object ShizukuPermissions {

    /** One permission line as reported by `dumpsys package`. */
    data class PermissionState(
        val permission: String,
        val granted: Boolean,
    )

    /**
     * Pulls the runtime permissions and their grant state out of `dumpsys package <pkg>`.
     *
     * Each permission shows up once for the primary user and then again per secondary
     * user (`granted=false, userId=10`). Those per-user overrides are skipped and the
     * first state seen wins, which is the one the package manager enforces for the user
     * actually driving the app.
     */
    fun parseDumpsys(output: String): List<PermissionState> {
        val state = LinkedHashMap<String, Boolean>()
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith(PERMISSION_PREFIX) || '=' !in line) return@forEach
            if (line.contains(USER_ID_MARKER)) return@forEach
            val permission = line.substringBefore(':').trim()
            if (permission.isEmpty()) return@forEach
            state.putIfAbsent(permission, line.contains(GRANTED_MARKER))
        }
        return state.map { (permission, granted) -> PermissionState(permission, granted) }
    }

    /**
     * `pm install-create` reports the new session either bare or wrapped, depending on the
     * Android version: `1196182964` or `Success: created install session [1196182964]`.
     */
    fun parseSessionId(output: String): Int? {
        val bracketed = SESSION_IN_BRACKETS.find(output)
        if (bracketed != null) return bracketed.groupValues[1].toIntOrNull()
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.toIntOrNull() != null }
            ?.toIntOrNull()
    }

    /** True when `pm` reported a success rather than an error. */
    fun isSuccess(result: ShizukuCommandResult): Boolean = result.ok && result.stderr.isBlank()

    /** Human-readable reason out of a failed `pm grant`/`pm revoke`. */
    fun failureReason(result: ShizukuCommandResult): String = when {
        result.timedOut -> "The command timed out"
        result.stderr.isNotBlank() -> result.stderr.lineSequence().first().trim()
        result.stdout.isNotBlank() -> result.stdout.lineSequence().first().trim()
        else -> "The command failed with code ${result.exitCode}"
    }

    suspend fun list(packageName: String): List<PermissionState> {
        val result = ShizukuBridge.execute("dumpsys package $packageName")
        return if (result.ok) parseDumpsys(result.stdout) else emptyList()
    }

    suspend fun set(
        packageName: String,
        permission: String,
        granted: Boolean,
    ): ShizukuCommandResult {
        val verb = if (granted) "grant" else "revoke"
        return ShizukuBridge.execute("pm $verb $packageName $permission")
    }
}

private const val PERMISSION_PREFIX = "android.permission."
private const val USER_ID_MARKER = "userId="
private const val GRANTED_MARKER = "granted=true"
private val SESSION_IN_BRACKETS = Regex("""\[(\d+)]""")
