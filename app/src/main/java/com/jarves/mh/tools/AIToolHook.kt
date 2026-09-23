package com.jarves.mh.tools

/** Decision returned by a tool hook before execution begins. */
sealed class AIToolHookDecision {
    data object Allow : AIToolHookDecision()
    data class Block(val reason: String) : AIToolHookDecision()
}

/**
 * Lifecycle hooks around tool calls. A hook may block a call by returning
 * [AIToolHookDecision.Block] from [onToolCallIntercept].
 *
 * MH tools are driven by guest CLIs; these hooks fire when the app-side
 * gate processes a permission request so rules/logging can observe every stage.
 */
interface AIToolHook {
    /** Called when a tool call request is received (before permission resolution). */
    fun onToolCallRequested(toolName: String, explanation: String) {}

    /** Called before permission checks. Return [AIToolHookDecision.Block] to veto. */
    fun onToolCallIntercept(toolName: String, explanation: String): AIToolHookDecision =
        AIToolHookDecision.Allow

    /** Called after the permission level for the tool is resolved. */
    fun onToolPermissionChecked(toolName: String, granted: Boolean, reason: String? = null) {}

    /** Called when the tool is about to run (permission already granted). */
    fun onToolExecutionStarted(toolName: String) {}

    /** Called when a tool execution result is produced. */
    fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) {}

    /** Called when execution fails. */
    fun onToolExecutionError(toolName: String, error: Throwable) {}

    /** Called when the tool request lifecycle is finished (success or failure). */
    fun onToolExecutionFinished(toolName: String) {}
}
