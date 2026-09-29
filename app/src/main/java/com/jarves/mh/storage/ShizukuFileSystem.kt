package com.jarves.mh.storage

import com.jarves.mh.runtime.ShizukuBridge
import java.util.Base64

/**
 * The device filesystem through the Shizuku shell.
 *
 * This is the only backend in the app that can see /data and /system, because it runs
 * as the `shell` user rather than as the app. It is also the slowest and the most
 * indirect: every operation is a command in a pipe, so the UI has to expect latency and
 * the commands are capped.
 */
class ShizukuFileSystem(
    override val root: DeviceRoot.Shizuku,
    private val run: suspend (String, Long) -> com.jarves.mh.runtime.ShizukuCommandResult = { command, timeout ->
        ShizukuBridge.execute(command, timeout)
    },
) : DeviceFs {

    private fun absolute(relative: String): String {
        val base = root.startPath.trimEnd('/').ifEmpty { "" }
        val child = relative.trim('/')
        return if (child.isEmpty()) base.ifEmpty { "/" } else ShizukuFs.joinPath(base, child)
    }

    override suspend fun list(path: String): FsResult<List<FsEntry>> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        val result = run(ShizukuFs.listCommand(absolute(path)), LIST_TIMEOUT_MS)
        // Exit code 4 is the command's own "cannot enter directory".
        if (result.exitCode == 4) return fsError(FsErrorKind.NOT_FOUND, "No such directory")
        if (!result.ok) {
            return fsError(FsErrorKind.NO_ACCESS, result.stderr.ifBlank { "Shizuku could not list this" }, FsRemedy.REQUEST_SHIZUKU)
        }
        return FsResult.Ok(ShizukuFs.parseListing(result.stdout))
    }

    override suspend fun stat(path: String): FsResult<FsEntry> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        val result = run(ShizukuFs.statCommand(absolute(path)), SHORT_TIMEOUT_MS)
        val entry = ShizukuFs.parseStatLine(result.stdout.lineSequence().firstOrNull().orEmpty())
            ?: return fsError(FsErrorKind.NOT_FOUND, "No such file or directory")
        return FsResult.Ok(entry)
    }

    override suspend fun readText(path: String): FsResult<String> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        val result = run(
            ShizukuFs.readTextCommand(absolute(path), FsLimits.MAX_TEXT_READ_BYTES),
            READ_TIMEOUT_MS,
        )
        if (result.exitCode == 0 && result.stdout.isEmpty()) {
            return fsError(FsErrorKind.NOT_FOUND, "No such file")
        }
        if (!result.ok) {
            return fsError(FsErrorKind.NO_ACCESS, result.stderr.ifBlank { "Shizuku could not read this" }, FsRemedy.REQUEST_SHIZUKU)
        }
        return FsResult.Ok(result.stdout.take(FsLimits.MAX_SHELL_OUTPUT_CHARS.toInt()))
    }

    override suspend fun writeText(path: String, content: String, append: Boolean): FsResult<Unit> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        // Appending is implemented with >> below, but it is not offered: the caller has to
        // ask for it explicitly rather than get a silently truncated file.
        if (append) return fsError(FsErrorKind.FAILED, "Appending is not supported through this backend")
        if (content.toByteArray().size > FsLimits.MAX_TEXT_READ_BYTES) {
            return fsError(FsErrorKind.FAILED, "File is too large to write")
        }
        // Base64 because the content is arbitrary text that must not reach the shell line.
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
        val command = if (append) {
            ShizukuFs.appendTextCommand(absolute(path), encoded)
        } else {
            ShizukuFs.writeTextCommand(absolute(path), encoded)
        }
        val result = run(command, WRITE_TIMEOUT_MS)
        if (!result.ok) {
            return fsError(FsErrorKind.FAILED, result.stderr.ifBlank { "Shizuku could not write this" })
        }
        return FsResult.Ok(Unit)
    }

    override suspend fun delete(path: String, recursive: Boolean): FsResult<Unit> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (path.isBlank()) return fsError(FsErrorKind.FAILED, "The root itself cannot be deleted")
        if (!recursive) {
            // Refuse a non-empty directory rather than silently recursing.
            val check = run("ls -A -- $(ShizukuFs.quote(absolute(path))) 2>/dev/null | head -1", SHORT_TIMEOUT_MS)
            if (check.ok && check.stdout.isNotBlank()) {
                return fsError(FsErrorKind.FAILED, "Directory is not empty")
            }
        }
        val result = run(ShizukuFs.deleteCommand(absolute(path), recursive), WRITE_TIMEOUT_MS)
        if (!result.ok) return fsError(FsErrorKind.FAILED, "Could not delete")
        return FsResult.Ok(Unit)
    }

    override suspend fun rename(path: String, newName: String): FsResult<Unit> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (path.isBlank()) return fsError(FsErrorKind.FAILED, "The root itself cannot be renamed")
        if (newName.isBlank() || newName.contains('/') || newName == "." || newName == "..") {
            return fsError(FsErrorKind.FAILED, "Invalid name")
        }
        val result = run(ShizukuFs.renameCommand(absolute(path), newName), SHORT_TIMEOUT_MS)
        if (!result.ok) return fsError(FsErrorKind.FAILED, "Rename refused by the filesystem")
        return FsResult.Ok(Unit)
    }

    override suspend fun createDirectory(path: String): FsResult<Unit> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        val result = run(ShizukuFs.createDirectoryCommand(absolute(path)), SHORT_TIMEOUT_MS)
        if (!result.ok) return fsError(FsErrorKind.FAILED, "Could not create the folder")
        return FsResult.Ok(Unit)
    }

    private companion object {
        const val SHORT_TIMEOUT_MS = 15_000L
        const val LIST_TIMEOUT_MS = 30_000L
        const val READ_TIMEOUT_MS = 30_000L
        const val WRITE_TIMEOUT_MS = 60_000L
    }
}
