package com.jarves.mh.tools.file

/** Shared limits for MH file tools (matching ro-operit ToolExecutionLimits). */
object FileToolLimits {
    const val MAX_FILE_READ_BYTES: Int = 32_000
    const val DEFAULT_READ_PART_LINES: Int = 200
    const val MAX_GREP_RESULTS: Int = 100
    const val MAX_FIND_RESULTS: Int = 500
    const val TRUNCATION_MARKER: String = "\n\n... (file content truncated) ..."
}
