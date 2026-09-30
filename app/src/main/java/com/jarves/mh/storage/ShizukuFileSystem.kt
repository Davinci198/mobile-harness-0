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

    /** [FsCategories.HIDDEN_DEVICE_PATHS] as absolute paths, for matching entries. */
    private val hiddenRootPaths = FsCategories.HIDDEN_DEVICE_PATHS.map { "/$it" }.toSet()

    /** The real path behind a relative one, for handing to a content provider. */
    fun absolutePathOf(relative: String): String = absolute(relative)

    /**
     * Copies the file behind [path] to a place the app can read on its own, and returns
     * that path.
     *
     * Only needed when the backend is the shell: the shell sees everything, an ordinary
     * app sees nothing, so there is no way to hand a Shizuku file to a video player
     * without a copy. The copy is made by the shell, on disk, so a video is never loaded
     * into memory — the command pipe only ever carries the outcome.
     */
    suspend fun copyForSharing(path: String, name: String): FsResult<String> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        // A name ends up on a command line and in a URI, so keep it to characters that
        // survive both. The original is what the other app sees in its title bar.
        val safeName = name.filter { it.isLetterOrDigit() || it in " ._-()" }.trim().ifEmpty { "file" }
        val dir = "${ShizukuFs.SHARE_DIR}/${System.currentTimeMillis()}"
        val dest = "$dir/$safeName"
        val result = run(
            ShizukuFs.sweepShareCommand() +
                "; " + ShizukuFs.shareCopyCommand(absolute(path), dir, dest),
            WRITE_TIMEOUT_MS,
        )
        if (!result.ok) {
            return fsError(FsErrorKind.NO_ACCESS, result.stderr.ifBlank { "The file could not be prepared" })
        }
        return FsResult.Ok(dest)
    }

    override suspend fun list(path: String): FsResult<List<FsEntry>> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        // A single directory still goes through the batched command, so there is one way
        // to list a directory and one way to be wrong about it.
        return listMany(listOf(path)).first()
    }

    /**
     * One command for the whole set. The device root is a dozen categories, and a
     * command per category is a dozen process spawns where one is enough.
     */
    override suspend fun listMany(paths: List<String>): List<FsResult<List<FsEntry>>> {
        if (paths.isEmpty()) return emptyList()
        val commands = paths.map { absolute(it) }
        val result = run(ShizukuFs.listManyCommand(commands), LIST_TIMEOUT_MS * commands.size.coerceAtMost(6))
        if (!result.ok) {
            return paths.map {
                fsError(FsErrorKind.NO_ACCESS, result.stderr.ifBlank { "Shizuku could not list this" }, FsRemedy.REQUEST_SHIZUKU)
            }
        }
        return ShizukuFs.parseBatched(result.stdout, commands.size).mapIndexed { index, block ->
            when {
                block.missing -> fsError(FsErrorKind.NOT_FOUND, "No such directory")
                else -> FsResult.Ok(visibleIn(commands[index], block.entries))
            }
        }
    }

    /**
     * Partitions the browser never shows. Writing to /system, /vendor or /product does
     * not come back, so they are dropped from every listing — the grid, the folder view
     * and search all read through this — rather than being one tap from a mistake.
     *
     * Matching the whole path and not the name keeps a folder that merely happens to be
     * called "data" or "system" (sdcard/Android/data) where it belongs.
     */
    private fun visibleIn(parentAbsolute: String, entries: List<FsEntry>): List<FsEntry> =
        entries.filterNot { ShizukuFs.joinPath(parentAbsolute, it.name) in hiddenRootPaths }

    override suspend fun stat(path: String): FsResult<FsEntry> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        val result = run(ShizukuFs.statCommand(absolute(path)), SHORT_TIMEOUT_MS)
        val entry = ShizukuFs.parseStatLine(result.stdout.lineSequence().firstOrNull().orEmpty())
            ?: return fsError(FsErrorKind.NOT_FOUND, "No such file or directory")
        return FsResult.Ok(entry)
    }

    override suspend fun readBytes(path: String, maxBytes: Long): FsResult<ByteArray> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        val result = run(ShizukuFs.readBytesCommand(absolute(path), maxBytes), READ_TIMEOUT_MS)
        if (result.exitCode == 0 && result.stdout.isBlank()) {
            return fsError(FsErrorKind.NOT_FOUND, "No such file")
        }
        if (!result.ok) {
            return fsError(FsErrorKind.NO_ACCESS, result.stderr.ifBlank { "Shizuku could not read this" }, FsRemedy.REQUEST_SHIZUKU)
        }
        return runCatching { FsResult.Ok(java.util.Base64.getDecoder().decode(result.stdout.trim())) }
            .getOrElse { fsError(FsErrorKind.FAILED, "The file could not be decoded") }
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray): FsResult<Unit> {
        if (!FsPaths.isSafeRelative(path)) return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (bytes.size > FsLimits.MAX_ARCHIVE_BYTES) {
            return fsError(FsErrorKind.FAILED, "The file is too large to write")
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        val result = run(ShizukuFs.writeBytesCommand(absolute(path), encoded), WRITE_TIMEOUT_MS)
        return if (result.ok) {
            FsResult.Ok(Unit)
        } else {
            fsError(FsErrorKind.FAILED, result.stderr.ifBlank { "Shizuku could not write this" }, FsRemedy.REQUEST_SHIZUKU)
        }
    }

    override suspend fun copy(fromPath: String, toPath: String, recursive: Boolean): FsResult<Unit> {
        if (!FsPaths.isSafeRelative(fromPath) || !FsPaths.isSafeRelative(toPath)) {
            return fsError(FsErrorKind.FAILED, "Path escapes the root")
        }
        val result = run(ShizukuFs.copyCommand(absolute(fromPath), absolute(toPath)), WRITE_TIMEOUT_MS)
        return if (result.ok) {
            FsResult.Ok(Unit)
        } else {
            fsError(FsErrorKind.FAILED, result.stderr.ifBlank { "Shizuku could not copy this" }, FsRemedy.REQUEST_SHIZUKU)
        }
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
            val check = run("ls -A -- ${ShizukuFs.quote(absolute(path))} 2>/dev/null | head -1", SHORT_TIMEOUT_MS)
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
