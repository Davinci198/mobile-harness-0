package com.jarves.mh.runtime

import org.json.JSONObject

/** What one `hooks.outbound` delivery means for the running Agent Execution. */
internal sealed interface HermesHookAction {
    data class Text(val text: String) : HermesHookAction
    data class ToolStart(val name: String, val detail: String) : HermesHookAction
    data class ToolEnd(val name: String, val summary: String) : HermesHookAction
    data class StreamEnd(val finalText: String, val finished: Boolean, val error: String) : HermesHookAction
    data class RequestError(val message: String, val retryable: Boolean) : HermesHookAction
    object Ignored : HermesHookAction
}

/**
 * Maps one outbound webhook payload onto [HermesHookAction].
 *
 * Wire shape (see `agent/shell_hooks._payload_fields`): top-level `tool_name`,
 * `tool_input`, `session_id`, `cwd`, `profile`, plus everything else folded
 * into `extra` - including `delta`, `kind`, `final_text`, `finished`, `error`,
 * `status`, `error_message` and `retryable`.
 */
internal fun mapHermesHook(payload: JSONObject): HermesHookAction {
    val extra = payload.optJSONObject("extra")
    return when (payload.optString("hook_event_name")) {
        "on_stream_delta" ->
            if (extra?.optString("kind") == "text") HermesHookAction.Text(extra.optString("delta"))
            else HermesHookAction.Ignored
        "on_stream_start" -> HermesHookAction.Ignored
        "on_stream_end" -> HermesHookAction.StreamEnd(
            finalText = extra?.optString("final_text").orEmpty(),
            finished = extra?.optBoolean("finished") ?: true,
            error = extra?.optString("error").orEmpty(),
        )
        "pre_tool_call" -> HermesHookAction.ToolStart(
            name = payload.optString("tool_name").ifBlank { "tool" },
            detail = hookToolDetail(payload.optJSONObject("tool_input")),
        )
        "post_tool_call" -> {
            val name = payload.optString("tool_name")
                .ifBlank { extra?.optString("function_name").orEmpty() }
                .ifBlank { "tool" }
            val error = extra?.optString("error_message").orEmpty()
            val status = extra?.optString("status").orEmpty()
            HermesHookAction.ToolEnd(name, error.ifBlank { status.ifBlank { "done" } })
        }
        "api_request_error" -> {
            val error = extra?.optJSONObject("error")
            val message = error?.optString("message").orEmpty()
                .ifBlank { extra?.optString("reason").orEmpty() }
                .ifBlank { "The provider rejected the request." }
            HermesHookAction.RequestError(message, extra?.optBoolean("retryable") ?: true)
        }
        else -> HermesHookAction.Ignored
    }
}

private fun hookToolDetail(input: JSONObject?): String {
    if (input == null) return ""
    val text = input.toString()
    return if (text.length > 160) text.take(157) + "..." else text
}

/**
 * Detects the end of one turn by watching interactive stdout.
 *
 * Idle renders a suggestion line such as "❯ Ask anything, or type / for
 * commands..."; a running turn renders the interrupt hint
 * "msg=interrupt · /queue · ..." plus a status line that repaints
 * continuously (so a silence heuristic cannot work). The turn is over at the
 * first "❯" line that is not the echo of our own submission.
 */
/** Strips CSI/OSC escape sequences so rendered lines can be matched as text. */
internal fun stripAnsi(text: String): String = text.replace(ANSI_SEQUENCE, "")

private val ANSI_SEQUENCE = Regex(
    "\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][A-Za-z0-9]",
)

internal class WarmTurnBoundary(echo: String) {
    private val echoPrefix: String = echo.take(ECHO_PREFIX_CHARS)
    private var running = false
    private var done = false

    /** True once the turn is observably running (hint line seen). */
    val turnStarted: Boolean get() = running

    /** A stream hook proves the turn started before the hint line renders. */
    fun markRunning() {
        running = true
    }

    /** Feeds one stdout line; returns true once the prompt is idle again. */
    fun onOutputLine(rawLine: String): Boolean {
        if (done) return true
        // The TUI colours the prompt mark itself, so the raw line starts with
        // escape sequences; match on the text it renders.
        val line = stripAnsi(rawLine).trim()
        if (!running) {
            if (RUNNING_MARKERS.any { line.contains(it) }) running = true
            return false
        }
        // A blank echo (theoretically impossible: prompts are never empty) must
        // not match every suggestion line, so it never matches at all.
        val isOwnEcho = echoPrefix.isNotEmpty() && line.contains(echoPrefix)
        if (line.startsWith(PROMPT_MARK) && !isOwnEcho) {
            done = true
        }
        return done
    }

    companion object {
        private const val PROMPT_MARK = "❯"
        private const val ECHO_PREFIX_CHARS = 60
        private val RUNNING_MARKERS = listOf("msg=interrupt", "Ctrl+C cancel")
    }
}
