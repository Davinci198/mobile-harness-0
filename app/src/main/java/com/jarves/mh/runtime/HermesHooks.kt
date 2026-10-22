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

    /**
     * The last error Hermes rendered on its own, without a hook event.
     *
     * The interactive stdout is a rendered TUI, so the reply text only reaches
     * the app through the webhook stream. A failure that happens before the
     * stream starts (a missing credential, a rejected model) is rendered as a
     * plain "⚠️  message" line and never fires a hook, which used to surface
     * as a generic "no reply for this turn" while the real cause sat in the
     * TUI. Keeping the line lets the failure report the actual reason.
     */
    var lastErrorLine: String? = null
        private set

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
        captureError(line)
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

    private fun captureError(line: String) {
        // The Nous free tier answers a token exchange with a browser check
        // ("Nous needs to run a quick check before starting your free
        // session."). The guest has no browser, so the session waits on it
        // until it is killed; name it instead of timing out silently.
        if (line.contains(NOUS_CHALLENGE_MARK)) {
            lastErrorLine = NOUS_CHALLENGE_MESSAGE
            return
        }
        // The status bar renders a bare "⚠ " bullet inside a bordered box
        // ("⚠ update available", "⚠ space-bunny-alpha │ ctx 12%"), while a real
        // failure is a standalone emoji line: "⚠️  No usable credentials ...".
        // The variation selector is what tells them apart.
        if (!line.startsWith(WARNING_MARK)) return
        val text = line.removePrefix(WARNING_MARK).trim().ifEmpty { return }
        lastErrorLine = text
    }

    companion object {
        private const val PROMPT_MARK = "❯"
        private const val ECHO_PREFIX_CHARS = 60
        private const val WARNING_MARK = "\u26A0\uFE0F"
        private val RUNNING_MARKERS = listOf("msg=interrupt", "Ctrl+C cancel")
    }
}

/** Marker of the Nous free tier's browser check (the guest cannot open a browser). */
internal const val NOUS_CHALLENGE_MARK = "needs to run a quick check"

/** What the user is told when the Nous free tier asks for that browser check. */
internal const val NOUS_CHALLENGE_MESSAGE =
    "The Nous free tier wants a browser check that the guest cannot complete. " +
        "Pick a keyed provider for Hermes (for example NVIDIA NIM)."
