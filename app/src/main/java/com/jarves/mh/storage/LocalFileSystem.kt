package com.jarves.mh.storage

import java.io.File

/**
 * What every backend can do. Implemented four times: the app sandbox, a SAF tree, real
 * paths under all-files access, and the device filesystem through the Shizuku shell.
 *
 * Paths are always POSIX-like and relative to the root the backend was opened with, with
 * "" meaning the root itself. That keeps the UI free of per-backend path handling.
 */
interface DeviceFs {
    val root: DeviceRoot

    suspend fun list(path: String): FsResult<List<FsEntry>>

    suspend fun stat(path: String): FsResult<FsEntry>

    suspend fun readText(path: String): FsResult<String>

    suspend fun writeText(path: String, content: String, append: Boolean = false): FsResult<Unit>

    suspend fun delete(path: String, recursive: Boolean = false): FsResult<Unit>

    suspend fun rename(path: String, newName: String): FsResult<Unit>

    suspend fun createDirectory(path: String): FsResult<Unit>

    /**
     * Bytes in and out, which text alone cannot carry: an archive is binary, and so is an
     * image. Backends that can only move text refuse rather than pretending.
     */
    suspend fun readBytes(path: String, maxBytes: Long): FsResult<ByteArray> =
        fsError(FsErrorKind.FAILED, "This location cannot read binary files")

    suspend fun writeBytes(path: String, bytes: ByteArray): FsResult<Unit> =
        fsError(FsErrorKind.FAILED, "This location cannot write binary files")

    /**
     * Copies a file or a whole folder, recursively. A copy is what a paste is made of, so
     * it has to work for folders too, not just single files.
     */
    suspend fun copy(fromPath: String, toPath: String, recursive: Boolean = true): FsResult<Unit> =
        fsError(FsErrorKind.FAILED, "This location cannot copy")

    /**
     * Lists several directories at once, returning one result per input path, in order.
     *
     * Backends that answer in-process just loop; the shell backend overrides this,
     * because a root with a dozen categories would otherwise cost a dozen command
     * spawns where one would do.
     */
    suspend fun listMany(paths: List<String>): List<FsResult<List<FsEntry>>> =
        paths.map { list(it) }
}

/** Shared helpers so the four backends stay thin. */
object FsPaths {
    /** Joins a root-relative path, tolerating a leading or trailing slash on either side. */
    fun join(relative: String, child: String): String {
        val left = relative.trim('/')
        val right = child.trim('/')
        if (right.isEmpty()) return left
        if (left.isEmpty()) return right
        return "$left/$right"
    }

    fun parentOf(relative: String): String? {
        val trimmed = relative.trim('/')
        if (trimmed.isEmpty()) return null
        val cut = trimmed.lastIndexOf('/')
        return if (cut < 0) "" else trimmed.substring(0, cut)
    }

    fun nameOf(relative: String): String {
        val trimmed = relative.trim('/')
        val cut = trimmed.lastIndexOf('/')
        return if (cut < 0) trimmed else trimmed.substring(cut + 1)
    }

    /** Rejects traversal and absolute paths before a relative path is used for anything. */
    fun isSafeRelative(relative: String): Boolean {
        if (relative.startsWith("/")) return false
        return relative.split('/').none { it == ".." }
    }
}

/** Turns a [File] into an [FsEntry], relative to [root]. */
internal fun File.toFsEntry(root: File): FsEntry {
    val relative = runCatching { relativeTo(root).invariantSeparatorsPath }.getOrElse { name }
    return FsEntry(
        name = name,
        relativePath = relative,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        lastModifiedMillis = lastModified(),
        readable = canRead(),
    )
}

/** Direct filesystem access, used for the app's own storage and, when allowed, shared storage. */
class LocalFileSystem(
    override val root: DeviceRoot,
    private val base: File,
    private val writeAllowed: Boolean = true,
    private val readAllowed: () -> Boolean = { true },
    private val noAccessError: FsError = FsError(FsErrorKind.NO_ACCESS, "Storage access is not granted"),
) : DeviceFs {
    /**
     * The real file a path points at, for handing to another app through the file
     * provider. Null when the path escapes the root, which is the same guard `resolve`
     * applies everywhere else.
     */
    fun fileFor(path: String): File? = resolve(path)

    private fun resolve(relative: String): File? {
        if (!FsPaths.isSafeRelative(relative)) return null
        val candidate = if (relative.isBlank()) base else File(base, relative)
        val canonicalBase = base.canonicalFile
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        // A symlink must not be able to walk the user out of the root.
        return canonical.takeIf { it.toPath().startsWith(canonicalBase.toPath()) }
    }

    override suspend fun list(path: String): FsResult<List<FsEntry>> {
        if (!readAllowed()) return FsResult.Err(noAccessError)
        val dir = resolve(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (!dir.exists()) return fsError(FsErrorKind.NOT_FOUND, "No such directory")
        if (!dir.isDirectory) return fsError(FsErrorKind.FAILED, "Not a directory")
        val entries = runCatching { dir.listFiles() }.getOrNull()
            ?: return fsError(FsErrorKind.FAILED, "Directory could not be listed")
        return FsResult.Ok(
            entries
                .orEmpty()
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .take(FsLimits.MAX_LISTED_ENTRIES)
                .map { it.toFsEntry(base) },
        )
    }

    override suspend fun stat(path: String): FsResult<FsEntry> {
        val file = resolve(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (!file.exists()) return fsError(FsErrorKind.NOT_FOUND, "No such file")
        return FsResult.Ok(file.toFsEntry(base))
    }

    override suspend fun readText(path: String): FsResult<String> {
        val file = resolve(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (!file.exists()) return fsError(FsErrorKind.NOT_FOUND, "No such file")
        if (file.isDirectory) return fsError(FsErrorKind.FAILED, "It is a directory")
        if (file.length() > FsLimits.MAX_TEXT_READ_BYTES) {
            return fsError(FsErrorKind.FAILED, "File is too large to open")
        }
        return runCatching { FsResult.Ok(file.readText()) }
            .getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "File could not be read") }
    }

    override suspend fun readBytes(path: String, maxBytes: Long): FsResult<ByteArray> {
        val file = resolve(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (!file.exists()) return fsError(FsErrorKind.NOT_FOUND, "No such file")
        if (file.isDirectory) return fsError(FsErrorKind.FAILED, "It is a directory")
        if (file.length() > maxBytes) return fsError(FsErrorKind.FAILED, "File is too large")
        return runCatching { FsResult.Ok(file.readBytes()) }
            .getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "File could not be read") }
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray): FsResult<Unit> {
        if (!writeAllowed) return fsError(FsErrorKind.READ_ONLY, "This location is read only")
        val file = resolve(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (file.isDirectory) return fsError(FsErrorKind.FAILED, "It is a directory")
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "File could not be written") }
    }

    override suspend fun writeText(path: String, content: String, append: Boolean): FsResult<Unit> {
        if (!writeAllowed) return fsError(FsErrorKind.READ_ONLY, "This location is read only")
        val file = resolve(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (file.isDirectory) return fsError(FsErrorKind.FAILED, "It is a directory")
        return runCatching {
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "File could not be written") }
    }

    override suspend fun copy(fromPath: String, toPath: String, recursive: Boolean): FsResult<Unit> {
        if (!writeAllowed) return fsError(FsErrorKind.READ_ONLY, "This location is read only")
        val from = resolve(fromPath) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        val to = resolve(toPath) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (!from.exists()) return fsError(FsErrorKind.NOT_FOUND, "No such file")
        if (from.isDirectory && !recursive) return fsError(FsErrorKind.FAILED, "It is a directory")
        // Copying a folder into itself would walk forever.
        if (from.isDirectory && to.path.startsWith(from.path + "/")) {
            return fsError(FsErrorKind.FAILED, "A folder cannot be copied into itself")
        }
        return runCatching {
            if (from.isDirectory) {
                to.mkdirs()
                from.listFiles()?.forEach { child ->
                    val name = child.name
                    copy(FsPaths.join(fromPath, name), FsPaths.join(toPath, name), recursive)
                }
            } else {
                // Prevent overwriting the source with itself (same canonical path)
                try {
                    if (from.canonicalPath == to.canonicalPath) {
                        return@runCatching fsError<Unit>(FsErrorKind.FAILED, "Source and destination are the same")
                    }
                } catch (_: Exception) { /* ignore */ }
                to.parentFile?.mkdirs()
                from.copyTo(to, overwrite = true)
            }
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "Could not copy") }
    }

    override suspend fun delete(path: String, recursive: Boolean): FsResult<Unit> {
        if (!writeAllowed) return fsError(FsErrorKind.READ_ONLY, "This location is read only")
        val file = resolve(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (path.isBlank()) return fsError(FsErrorKind.FAILED, "The root itself cannot be deleted")
        if (file.isDirectory && !recursive) return fsError(FsErrorKind.FAILED, "Directory is not empty")
        return runCatching {
            if (!file.deleteRecursively() && file.exists()) {
                return@runCatching fsError<Unit>(FsErrorKind.FAILED, "Could not delete")
            }
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "Could not delete") }
    }

    override suspend fun rename(path: String, newName: String): FsResult<Unit> {
        if (!writeAllowed) return fsError(FsErrorKind.READ_ONLY, "This location is read only")
        val file = resolve(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        if (path.isBlank()) return fsError(FsErrorKind.FAILED, "The root itself cannot be renamed")
        if (newName.isBlank() || newName.contains('/')) return fsError(FsErrorKind.FAILED, "Invalid name")
        if (!file.exists()) return fsError(FsErrorKind.NOT_FOUND, "No such file")
        if (File(file.parentFile ?: return fsError(FsErrorKind.FAILED, "No parent"), newName).exists()) {
            return fsError(FsErrorKind.FAILED, "A file with that name already exists")
        }
        return runCatching {
            if (!file.renameTo(File(file.parentFile, newName))) {
                return@runCatching fsError<Unit>(FsErrorKind.FAILED, "Rename refused by the filesystem")
            }
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "Rename failed") }
    }

    override suspend fun createDirectory(path: String): FsResult<Unit> {
        if (!writeAllowed) return fsError(FsErrorKind.READ_ONLY, "This location is read only")
        val dir = resolve(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the root")
        return runCatching {
            if (!dir.mkdirs() && !dir.isDirectory) {
                return@runCatching fsError<Unit>(FsErrorKind.FAILED, "Could not create the folder")
            }
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "Could not create the folder") }
    }
}
