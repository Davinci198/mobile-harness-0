package com.jarves.mh.tools.calc

/** Result of a calculate or sleep tool call. */
sealed interface CalcToolResult {
    data class Calculated(
        val expression: String,
        val value: Double,
    ) : CalcToolResult

    data class Slept(
        val durationMs: Long,
    ) : CalcToolResult

    data class Error(
        val tool: String,
        val message: String,
    ) : CalcToolResult
}
