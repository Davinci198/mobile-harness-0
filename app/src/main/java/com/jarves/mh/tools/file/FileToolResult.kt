package com.jarves.mh.tools.file

/** Result of a file tool call. */
sealed interface FileToolResult {
    data class FileContent(
        val path: String,
        val content: String,
        val totalLines: Int,
        val truncated: Boolean,
    ) : FileToolResult

    data class FileWritten(
        val path: String,
        val bytes: Long,
        val appended: Boolean,
    ) : FileToolResult

    data class FileApplied(
        val path: String,
        val replacements: Int,
    ) : FileToolResult

    data class GrepMatches(
        val root: String,
        val pattern: String,
        val matches: List<GrepMatch>,
        val truncated: Boolean,
    ) : FileToolResult

    data class GrepMatch(
        val path: String,
        val lineNumber: Int,
        val line: String,
    ) : FileToolResult

    data class FoundFiles(
        val root: String,
        val glob: String,
        val files: List<String>,
        val truncated: Boolean,
    ) : FileToolResult

    data class FileToolError(
        val tool: String,
        val message: String,
    ) : FileToolResult
}
