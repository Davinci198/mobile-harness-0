package com.jarves.mh.tools.terminal

sealed interface TerminalToolResult {
    data class Completed(
        val command: String,
        val output: String,
        val exitCode: Int,
    ) : TerminalToolResult

    data class TimedOut(
        val command: String,
        val output: String,
        val timeoutMs: Long,
    ) : TerminalToolResult

    data class Error(
        val tool: String,
        val message: String,
    ) : TerminalToolResult
}

data class TerminalCommandRun(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean,
)

interface TerminalCommandRunner {
    fun run(
        projectSlug: String,
        command: String,
        timeoutMs: Long,
        rows: Int,
        columns: Int,
    ): TerminalCommandRun
}
