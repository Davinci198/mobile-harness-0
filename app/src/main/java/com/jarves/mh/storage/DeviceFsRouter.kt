package com.jarves.mh.storage

/**
 * Decides which backend serves a path, and explains itself when none can.
 *
 * The app has four ways to reach a file and they overlap only partially:
 *
 * | backend    | sandbox | shared storage | /data, /system |
 * |------------|---------|-----------------|----------------|
 * | Local      | yes     | no              | no             |
 * | SAF tree   | no      | yes             | no             |
 * | All files  | no      | yes             | no             |
 * | Shizuku    | no      | yes             | yes            |
 *
 * The order matters: the cheapest backend that can actually see the path wins, so
 * browsing shared storage does not go through a command pipe when it does not have to,
 * while `/data` falls through to Shizuku instead of failing.
 */
object DeviceFsRouter {
    enum class Reach {
        /** The app's own files. Always present, no permission involved. */
        SANDBOX,

        /** A folder the user handed over through the system picker. */
        SAF,

        /** Real paths, because all-files access is granted. */
        ALL_FILES,

        /** Only reachable through the Shizuku shell. */
        SHELL,
    }

    /**
     * Picks the backend for a path. [hasSaf] and [allFiles] come from live permission
     * checks, so the answer changes as the user grants things without a restart.
     */
    fun reachFor(
        path: String,
        hasSaf: Boolean,
        allFiles: Boolean,
        shizuku: Boolean,
    ): Reach? {
        val trimmed = path.trim('/')
        return when {
            // Anything under the app's own files is served directly; the sandbox is bind
            // mounted into the guest, so it is the one place worth keeping fast.
            trimmed.startsWith(SANDBOX_PREFIX) -> Reach.SANDBOX

            hasSaf || allFiles -> if (isSharedStorage(trimmed)) Reach.ALL_FILES else Reach.SHELL.takeIf { shizuku }

            shizuku -> Reach.SHELL

            else -> null
        }
    }

    /** The reason a path cannot be shown, phrased with the way out. */
    fun whyUnavailable(path: String, shizuku: Boolean): FsError {
        val trimmed = path.trim('/')
        val where = if (trimmed.startsWith("data/") || trimmed == "data") "This location needs Shizuku" else "No access"
        return when {
            !shizuku && (trimmed.startsWith("data/") || trimmed.startsWith("system/") || trimmed.startsWith("vendor/")) ->
                FsError(FsErrorKind.NO_ACCESS, "$where, which this app does not have yet", FsRemedy.REQUEST_SHIZUKU)

            shizuku -> FsError(FsErrorKind.NO_ACCESS, "Not available", FsRemedy.REQUEST_SHIZUKU)

            else -> FsError(
                FsErrorKind.NO_ACCESS,
                "Grant access to a folder to browse this",
                FsRemedy.PICK_FOLDER,
            )
        }
    }

    /** True for the shared storage an ordinary app can ever be given access to. */
    fun isSharedStorage(path: String): Boolean {
        val trimmed = path.trim('/')
        return trimmed == "sdcard" || trimmed.startsWith("sdcard/") ||
            trimmed == "storage" || trimmed.startsWith("storage/")
    }

    const val SANDBOX_PREFIX = "sandbox"
}
