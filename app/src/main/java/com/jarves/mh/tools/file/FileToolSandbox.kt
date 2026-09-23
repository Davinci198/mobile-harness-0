package com.jarves.mh.tools.file

import java.io.File

/**
 * Path sandbox: every path must resolve inside [root].
 * Blocks `..` escapes and absolute paths outside the root.
 */
class FileToolSandbox(private val root: File) {

    val rootPath: String = root.canonicalFile.absolutePath

    /** Resolve [path] against the sandbox root. Returns null if outside. */
    fun resolve(path: String): File? {
        if (path.isBlank()) return null
        val candidate = when {
            path.startsWith("/") -> File(path)
            path.startsWith("~/") -> File(root, path.removePrefix("~/"))
            else -> File(root, path)
        }
        return try {
            val canonical = candidate.canonicalFile
            if (canonical.path == rootPath || canonical.path.startsWith(rootPath + File.separator)) {
                canonical
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isValid(path: String): Boolean = resolve(path) != null
}
