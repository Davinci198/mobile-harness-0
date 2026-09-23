package com.jarves.mh.tools.calc

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision

/**
 * Safe arithmetic calculator gated by [ToolPermissionGate] (tool name `Calculate`).
 * Expressions are evaluated by [ExpressionEvaluator] — no shell, no JS, no file access.
 */
class CalculateTool(
    private val gate: ToolPermissionGate,
    private val hooks: List<AIToolHook> = emptyList(),
) {
    fun calculate(expression: String): CalcToolResult {
        val tool = "Calculate"
        if (expression.isBlank()) {
            return CalcToolResult.Error(tool, "Expression is empty")
        }
        if (expression.length > CalcToolLimits.MAX_EXPRESSION_CHARS) {
            return CalcToolResult.Error(tool, "Expression is too long")
        }

        val explanation = expression.take(240)
        when (val decision = gate.evaluate(tool, explanation)) {
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
            val value = ExpressionEvaluator(expression).evaluate()
            hooks.forEach { it.onToolExecutionResult(tool, true, value.toString()) }
            CalcToolResult.Calculated(expression, value)
        } catch (e: CalcParseException) {
            hooks.forEach { it.onToolExecutionResult(tool, false, e.message ?: "parse error") }
            CalcToolResult.Error(tool, e.message ?: "Invalid expression")
        } catch (t: Throwable) {
            hooks.forEach { it.onToolExecutionError(tool, t) }
            CalcToolResult.Error(tool, t.message ?: "Calculation failed")
        } finally {
            hooks.forEach { it.onToolExecutionFinished(tool) }
        }
    }
}
