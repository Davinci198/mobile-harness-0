package com.jarves.mh.tools

/**
 * Central gate that resolves a tool's effective permission level and runs
 * registered [AIToolHook]s around the decision.
 *
 * Resolution: per-tool override in [store] wins; otherwise [ToolPermissionStore.globalDefault].
 * Intercept hooks may still block an ALLOW decision (e.g. hard denylist).
 */
class ToolPermissionGate(
    private val store: ToolPermissionStore,
    private val hooks: List<AIToolHook> = emptyList(),
) {
    fun resolve(toolName: String): ToolPermissionLevel = store.resolve(toolName)

    fun setGlobalDefault(level: ToolPermissionLevel) {
        store.globalDefault = level
    }

    fun setOverride(toolName: String, level: ToolPermissionLevel?) {
        store.setOverride(toolName, level)
    }

    fun overrideFor(toolName: String): ToolPermissionLevel? = store.overrideFor(toolName)

    /**
     * Full pre-execution check for a tool call.
     *
     * Runs [AIToolHook.onToolCallRequested] and [AIToolHook.onToolCallIntercept],
     * then resolves the permission level. A block from any hook forces [ToolPermissionLevel.FORBID]
     * regardless of policy (hooks are a hard veto, not a fallback).
     */
    fun evaluate(toolName: String, explanation: String): ToolPermissionGateDecision {
        hooks.forEach { it.onToolCallRequested(toolName, explanation) }
        for (hook in hooks) {
            val decision = hook.onToolCallIntercept(toolName, explanation)
            if (decision is AIToolHookDecision.Block) {
                hooks.forEach { it.onToolPermissionChecked(toolName, false, decision.reason) }
                return ToolPermissionGateDecision.Blocked(decision.reason)
            }
        }
        val level = resolve(toolName)
        return when (level) {
            ToolPermissionLevel.ALLOW -> {
                hooks.forEach { it.onToolPermissionChecked(toolName, true) }
                ToolPermissionGateDecision.Allowed
            }
            ToolPermissionLevel.FORBID -> {
                val reason = "Tool '$toolName' is forbidden by permission policy"
                hooks.forEach { it.onToolPermissionChecked(toolName, false, reason) }
                ToolPermissionGateDecision.Blocked(reason)
            }
            ToolPermissionLevel.ASK -> ToolPermissionGateDecision.NeedsApproval(level)
        }
    }
}

/** Result of evaluating a tool call against policy and hooks. */
sealed interface ToolPermissionGateDecision {
    data object Allowed : ToolPermissionGateDecision
    data class NeedsApproval(val level: ToolPermissionLevel) : ToolPermissionGateDecision
    data class Blocked(val reason: String) : ToolPermissionGateDecision
}
