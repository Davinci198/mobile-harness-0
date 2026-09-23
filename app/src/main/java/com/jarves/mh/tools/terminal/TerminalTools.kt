package com.jarves.mh.tools.terminal

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision

class TerminalTools(
    private val runner: TerminalCommandRunner,
    private val gate: ToolPermissionGate,
    private val hooks: List<AIToolHook> = emptyList(),
) {
    fun execute(
        projectSlug: String,
        command: String,
        timeoutMs: Long = TerminalToolLimits.DEFAULT_TIMEOUT_MS,
        rows: Int = TerminalToolLimits.DEFAULT_ROWS,
        columns: Int = TerminalToolLimits.DEFAULT_COLUMNS,
    ): TerminalToolResult {
        val tool = "Bash"
        if (projectSlug.isBlank()) return TerminalToolResult.Error(tool, "Project slug is required")
        if (command.isBlank()) return TerminalToolResult.Error(tool, "Command is empty")
        if (command.length > TerminalToolLimits.MAX_COMMAND_CHARS) {
            return TerminalToolResult.Error(tool, "Command is too long")
        }
        if (timeoutMs !in 1..TerminalToolLimits.MAX_TIMEOUT_MS) {
            return TerminalToolResult.Error(tool, "Invalid timeout")
        }
        if (rows !in 1..500 || columns !in 1..1000) {
            return TerminalToolResult.Error(tool, "Invalid terminal size")
        }

        val explanation = command.take(240)
        when (val decision = gate.evaluate(tool, explanation)) {
            is ToolPermissionGateDecision.Allowed -> Unit
            is ToolPermissionGateDecision.NeedsApproval -> {
                return TerminalToolResult.Error(tool, "Approval required (ASK)")
            }
            is ToolPermissionGateDecision.Blocked -> {
                return TerminalToolResult.Error(tool, decision.reason)
            }
        }
        hooks.forEach { it.onToolExecutionStarted(tool) }
        return try {
            val run = runner.run(projectSlug, command, timeoutMs, rows, columns)
            val result = if (run.timedOut) {
                TerminalToolResult.TimedOut(command, run.output, timeoutMs)
            } else {
                TerminalToolResult.Completed(command, run.output, run.exitCode)
            }
            hooks.forEach { it.onToolExecutionResult(tool, true, "exit ${run.exitCode}") }
            result
        } catch (error: Throwable) {
            hooks.forEach { it.onToolExecutionError(tool, error) }
            TerminalToolResult.Error(tool, error.message ?: "Terminal execution failed")
        } finally {
            hooks.forEach { it.onToolExecutionFinished(tool) }
        }
    }
}
