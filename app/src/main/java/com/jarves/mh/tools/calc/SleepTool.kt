package com.jarves.mh.tools.calc

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Bounded delay gated by [ToolPermissionGate] (tool name `Sleep`).
 * Duration is clamped to [CalcToolLimits.MAX_SLEEP_MS]; the wait runs on
 * [Dispatchers.IO] so a long sleep never parks the main thread's work queue.
 */
class SleepTool(
    private val gate: ToolPermissionGate,
    private val hooks: List<AIToolHook> = emptyList(),
    private val delayFn: (Long) -> Unit = { ms ->
        runBlocking(Dispatchers.IO) { delay(ms) }
    },
) {
    fun sleep(durationMs: Long = CalcToolLimits.DEFAULT_SLEEP_MS): CalcToolResult {
        val tool = "Sleep"
        if (durationMs < CalcToolLimits.MIN_SLEEP_MS) {
            return CalcToolResult.Error(tool, "Duration must be non-negative")
        }
        if (durationMs > CalcToolLimits.MAX_SLEEP_MS) {
            return CalcToolResult.Error(
                tool,
                "Duration exceeds maximum of ${CalcToolLimits.MAX_SLEEP_MS} ms",
            )
        }

        when (val decision = gate.evaluate(tool, "sleep $durationMs ms")) {
            is ToolPermissionGateDecision.Allowed -> Unit
            is ToolPermissionGateDecision.NeedsApproval -> {
                return CalcToolResult.Error(tool, "Approval required (ASK)")
            }
            is ToolPermissionGateDecision.Blocked -> {
                return CalcToolResult.Error(tool, decision.reason)
            }
        }

        hooks.forEach { it.onToolExecutionStarted(tool) }
        return try {
            delayFn(durationMs)
            hooks.forEach { it.onToolExecutionResult(tool, true, "slept $durationMs ms") }
            CalcToolResult.Slept(durationMs)
        } catch (t: Throwable) {
            hooks.forEach { it.onToolExecutionError(tool, t) }
            CalcToolResult.Error(tool, t.message ?: "Sleep failed")
        } finally {
            hooks.forEach { it.onToolExecutionFinished(tool) }
        }
    }
}
