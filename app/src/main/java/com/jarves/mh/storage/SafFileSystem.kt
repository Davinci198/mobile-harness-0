package com.jarves.mh.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * A Storage Access Framework tree the user granted through the system picker.
 *
 * This is the only way an ordinary app reaches arbitrary storage without asking for a
 * special permission, and it survives reboots once the permission is persisted. It does
 * not reach /data or /system — nothing in a normal app does — so the router sends those
 * to Shizuku instead.
 */
class SafFileSystem(
    override val root: DeviceRoot.SafTree,
    private val resolver: ContentResolver,
) : DeviceFs {
    private val treeUri: Uri = Uri.parse(root.treeUri)

    private fun documentId(relative: String): String? {
        if (!FsPaths.isSafeRelative(relative)) return null
        val base = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val child = relative.trim('/')
        return if (child.isEmpty()) base else "$base/$child"
    }

    /**
     * The tree-backed document URI for a path, which is what another app opens directly —
     * no copy, no file provider, and the provider's own permissions carry the read.
     * Null when the path escapes the granted folder.
     */
    fun documentUri(relative: String): Uri? {
        val id = documentId(relative) ?: return null
        return runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, id) }.getOrNull()
    }

    private fun childUri(parentId: String, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, "$parentId/$documentId")

    private fun column(uri: Uri, name: String): String? =
        runCatching { resolver.query(uri, arrayOf(name), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } }.getOrNull()

    private fun toEntry(parentId: String, cursor: android.database.Cursor): FsEntry? {
        val documentId = runCatching { cursor.getString(0) }.getOrNull() ?: return null
        val name = runCatching { cursor.getString(1) }.getOrNull() ?: return null
        val mime = runCatching { cursor.getString(2) }.getOrNull() ?: ""
        val size = runCatching { if (cursor.isNull(3)) 0L else cursor.getLong(3) }.getOrDefault(0L)
        val modified = runCatching { if (cursor.isNull(4)) 0L else cursor.getLong(4) }.getOrDefault(0L)
        val leaf = documentId.substringAfterLast('/')
        return FsEntry(
            name = leaf.ifEmpty { name },
            relativePath = leaf,
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            sizeBytes = if (mime == DocumentsContract.Document.MIME_TYPE_DIR) 0L else size,
            lastModifiedMillis = modified,
            readable = true,
        )
    }

    override suspend fun list(path: String): FsResult<List<FsEntry>> {
        val parentId = documentId(path)
            ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        }.getOrNull() ?: return fsError(FsErrorKind.FAILED, "This folder cannot be listed")
        return runCatching {
            resolver.query(children, CHILD_COLUMNS, null, null, null)?.use { cursor ->
                val entries = mutableListOf<FsEntry>()
                while (cursor.moveToNext()) {
                    toEntry(parentId, cursor)?.let(entries::add)
                }
                entries
                    .sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                    .take(FsLimits.MAX_LISTED_ENTRIES)
            }
        }.getOrNull()?.let { FsResult.Ok(it) }
            ?: fsError(FsErrorKind.FAILED, "This folder cannot be listed")
    }

    override suspend fun stat(path: String): FsResult<FsEntry> {
        val uri = documentUri(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        val name = column(uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            ?: return fsError(FsErrorKind.NOT_FOUND, "No such file")
        val mime = column(uri, DocumentsContract.Document.COLUMN_MIME_TYPE).orEmpty()
        val size = column(uri, DocumentsContract.Document.COLUMN_SIZE)?.toLongOrNull() ?: 0L
        val modified = column(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.toLongOrNull() ?: 0L
        return FsResult.Ok(
            FsEntry(
                name = name,
                relativePath = FsPaths.nameOf(path),
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                sizeBytes = size,
                lastModifiedMillis = modified,
            ),
        )
    }

    override suspend fun readText(path: String): FsResult<String> {
        val uri = documentUri(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(FsLimits.MAX_TEXT_READ_BYTES.toInt() + 1)
                if (bytes.size > FsLimits.MAX_TEXT_READ_BYTES) null else String(bytes)
            }
        }.fold(
            onSuccess = { text ->
                if (text == null) fsError(FsErrorKind.FAILED, "File is too large to open")
                else FsResult.Ok(text)
            },
            onFailure = { fsError(FsErrorKind.FAILED, it.message ?: "File could not be read") },
        )
    }

    override suspend fun readBytes(path: String, maxBytes: Long): FsResult<ByteArray> {
        val uri = documentUri(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        return runCatching {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("The provider refused to open this file")
            if (bytes.size > maxBytes) error("File is too large")
            FsResult.Ok(bytes)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "File could not be read") }
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray): FsResult<Unit> {
        val uri = documentUri(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        return runCatching {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("The provider refused to open this file")
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "File could not be written") }
    }

    override suspend fun copy(fromPath: String, toPath: String, recursive: Boolean): FsResult<Unit> {
        // SAF has no copy of its own, so a folder is walked and each file is moved across
        // whole. That is slower than a filesystem rename but it is what the contract allows.
        val source = documentUri(fromPath) ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        val name = FsPaths.nameOf(toPath)
        val mime = column(source, DocumentsContract.Document.COLUMN_MIME_TYPE)
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (!recursive) return fsError(FsErrorKind.FAILED, "It is a directory")
            val children = list(fromPath).valueOrNull() ?: emptyList()
            when (val made = createDirectory(toPath)) {
                is FsResult.Ok -> Unit
                is FsResult.Err -> return made
            }
            children.forEach { child ->
                copy(
                    FsPaths.join(fromPath, child.name),
                    FsPaths.join(toPath, child.name),
                    recursive,
                )
            }
            return FsResult.Ok(Unit)
        }
        if (name.isEmpty()) return fsError(FsErrorKind.FAILED, "It needs a name")
        return when (val bytes = readBytes(fromPath, FsLimits.MAX_ARCHIVE_BYTES)) {
            is FsResult.Err -> bytes
            is FsResult.Ok -> when (val written = writeBytes(toPath, bytes.value)) {
                is FsResult.Err -> written
                is FsResult.Ok -> FsResult.Ok(Unit)
            }
        }
    }

    override suspend fun writeText(path: String, content: String, append: Boolean): FsResult<Unit> {
        // Appending through SAF is provider-specific and rarely supported, so say so
        // instead of silently truncating the file.
        if (append) return fsError(FsErrorKind.FAILED, "Appending is not supported for granted folders")
        val uri = documentUri(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        if (content.toByteArray().size > FsLimits.MAX_TEXT_READ_BYTES) {
            return fsError(FsErrorKind.FAILED, "File is too large to write")
        }
        return runCatching {
            resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
                ?: error("The provider refused to open this file")
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "File could not be written") }
    }

    override suspend fun delete(path: String, recursive: Boolean): FsResult<Unit> {
        val uri = documentUri(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        if (path.isBlank()) return fsError(FsErrorKind.FAILED, "The granted folder itself cannot be deleted")
        return runCatching {
            DocumentsContract.deleteDocument(resolver, uri)
                ?: return fsError(FsErrorKind.FAILED, "The provider refused to delete this")
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "Could not delete") }
    }

    override suspend fun rename(path: String, newName: String): FsResult<Unit> {
        val uri = documentUri(path) ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        if (newName.isBlank() || newName.contains('/')) return fsError(FsErrorKind.FAILED, "Invalid name")
        return runCatching {
            DocumentsContract.renameDocument(resolver, uri, newName)
                ?: return fsError(FsErrorKind.FAILED, "The provider refused to rename this")
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "Rename failed") }
    }

    override suspend fun createDirectory(path: String): FsResult<Unit> {
        val parent = FsPaths.parentOf(path)
            ?: return fsError(FsErrorKind.FAILED, "Cannot create the granted folder itself")
        val parentId = documentId(parent) ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        val parentUri = runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId) }.getOrNull()
            ?: return fsError(FsErrorKind.FAILED, "Path escapes the folder")
        val name = FsPaths.nameOf(path)
        if (name.isBlank()) return fsError(FsErrorKind.FAILED, "Invalid folder name")
        return runCatching {
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: return fsError(FsErrorKind.FAILED, "The provider refused to create a folder")
            FsResult.Ok(Unit)
        }.getOrElse { fsError(FsErrorKind.FAILED, it.message ?: "Could not create the folder") }
    }

    private companion object {
        val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
