package com.jarves.mh.runtime

import android.util.Log
import com.jarves.mh.model.RuntimeEvent
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.delay

internal data class WarmTurnResult(
    val failed: String?,
    val sawAnyOutput: Boolean,
    val processDied: Boolean = false,
    val timedOut: Boolean = false,
)

/**
 * One long-lived interactive `hermes chat` process reused across Agent
 * Executions, so a turn pays the model latency instead of the 22-26s boot.
 *
 * The session is keyed by [signature] (provider, model, endpoint, workspace):
 * any change, a dead process or a boot failure closes it and the caller falls
 * back to the one-shot cold path. The session is started without `-q` (which
 * would make Hermes answer once and exit), so every turn is pasted into the
 * TUI as a bracketed paste; the turn boundary comes from [WarmTurnBoundary]
 * plus the webhook stream events.
 */
internal class HermesWarmSession(
    private val ensureHookConfig: (String) -> Unit,
) : AutoCloseable {
    private var server: HermesHookServer? = null
    private var process: Process? = null
    private var signature: String? = null
    private var outputOffset = 0L
    private val lineBuffer = StringBuilder()
    private val queryTail = StringBuilder()
    private var queryAnswers = 0
    private var tuiReady = false
    private val pendingHooks = LinkedBlockingQueue<org.json.JSONObject>()

    /** True while a spawned session is still running. */
    val isActive: Boolean get() = process?.isAlive == true

    /**
     * Returns a running session matching [signature], spawning one (and waiting
     * for it to come up) when needed; null means "fall back to cold".
     */
    suspend fun ensureStarted(
        signature: String,
        spawn: (hookUrl: String) -> Process,
    ): Process? {
        val current = process
        if (current != null && current.isAlive && this.signature == signature) {
            Log.d("HermesWarmSession", "reusing warm session pid=${current.spawnPid()}")
            if (!awaitInteractivePrompt(current)) {
                close()
            } else {
                return current
            }
        } else if (current != null) {
            Log.i("HermesWarmSession", "warm session unusable, respawning")
            close()
        }
        val listener = HermesHookServer { payload -> pendingHooks.offer(payload) }.start()
        val spawned = runCatching {
            ensureHookConfig(listener.url)
            spawn(listener.url) as? NativeSpawnProcess
        }.getOrNull()
        if (spawned == null) {
            listener.close()
            return null
        }
        server = listener
        process = spawned
        this.signature = signature
        outputOffset = 0L
        lineBuffer.setLength(0)
        queryTail.setLength(0)
        queryAnswers = 0
        tuiReady = false
        pendingHooks.clear()
        // Without `-q` the session boots idle, so its prompt line is the
        // readiness signal for a fresh spawn as well as a reused one.
        if (!awaitInteractivePrompt(spawned)) {
            close()
            return null
        }
        return spawned
    }

    /** Runs one turn: pastes [prompt] into the session and waits for the reply. */
    suspend fun runTurn(
        prompt: String,
        sessionId: String,
        emit: suspend (RuntimeEvent) -> Unit,
    ): WarmTurnResult {
        val target = process
            ?: return WarmTurnResult(failed = WARM_NOT_RUNNING, sawAnyOutput = false)
        if (!target.isAlive) {
            return WarmTurnResult(failed = WARM_NOT_RUNNING, sawAnyOutput = false, processDied = true)
        }
        val boundary = WarmTurnBoundary(prompt.lineSequence().first().trim())
        pendingHooks.clear()
        var bootLogged = false
        // Bracketed paste, the way a terminal delivers a multi-line paste: the
        // TUI inserts the newlines into its buffer instead of treating them as
        // "submit", so the whole context prompt arrives in one piece.
        // Ctrl+U discards whatever the line already holds (a dropped paste, a
        // stale query answer) so this turn starts from an empty prompt.
        val submission = (KILL_LINE + PASTE_START + prompt.replace("\r\n", "\n").trim() + PASTE_END + "\r")
            .toByteArray(Charsets.UTF_8)
        runCatching {
            target.outputStream.write(submission)
            target.outputStream.flush()
        }.onFailure {
            return WarmTurnResult(failed = "Could not send the prompt to the Hermes session.", sawAnyOutput = false)
        }
        Log.i("HermesWarmSession", "prompt submitted (${prompt.length} chars)")

        var sawAnyOutput = false
        var sawText = false
        var emittedChars = 0
        var lastError: String? = null
        var finished = false
        var lastOutputAt = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + TURN_TIMEOUT_MS
        // The idle prompt line often lands without a trailing newline, so the
        // partial tail counts once the TUI stops repainting for a moment.
        val shortEcho = prompt.lineSequence().first().trim().take(SHORT_ECHO_CHARS)

        var loggedHooks = 0
        val drain: suspend () -> Unit = {
            while (true) {
                val payload = pendingHooks.poll() ?: break
                sawAnyOutput = true
                if (loggedHooks < MAX_LOGGED_HOOKS) {
                    loggedHooks++
                    Log.i(
                        "HermesWarmSession",
                        "hook ${payload.optString("hook_event_name")} " +
                            "kind=${payload.optJSONObject("extra")?.optString("kind").orEmpty()}",
                    )
                }
                when (val action = mapHermesHook(payload)) {
                    is HermesHookAction.Text -> {
                        boundary.markRunning()
                        if (action.text.isNotBlank()) {
                            sawText = true
                            emittedChars += action.text.length
                            emit(RuntimeEvent.AssistantDelta(sessionId, action.text))
                        }
                    }
                    is HermesHookAction.ToolStart -> {
                        boundary.markRunning()
                        emit(RuntimeEvent.ToolStarted(sessionId, action.name, action.detail))
                    }
                    is HermesHookAction.ToolEnd -> {
                        boundary.markRunning()
                        emit(RuntimeEvent.ToolCompleted(sessionId, action.name, action.summary))
                    }
                    is HermesHookAction.StreamEnd -> {
                        boundary.markRunning()
                        if (action.error.isNotBlank() && !action.finished) lastError = action.error
                        // The webhook queue is bounded: deltas can drop under
                        // load, and final_text completes whatever is missing.
                        val finalText = action.finalText
                        if (finalText.length > emittedChars) {
                            val tail = finalText.substring(emittedChars)
                            emittedChars = finalText.length
                            if (tail.isNotBlank()) {
                                sawText = true
                                emit(RuntimeEvent.AssistantDelta(sessionId, tail))
                            }
                        }
                    }
                    is HermesHookAction.RequestError -> {
                        boundary.markRunning()
                        lastError = action.message
                    }
                    HermesHookAction.Ignored -> Unit
                }
            }
        }

        while (!finished) {
            if (!target.isAlive) {
                drain()
                return WarmTurnResult(
                    failed = lastError ?: DIED_MESSAGE,
                    sawAnyOutput = sawAnyOutput,
                    processDied = true,
                )
            }
            drain()
            sawNewOutput(target)?.let { chunk ->
                if (!bootLogged) {
                    bootLogged = true
                    Log.i("HermesWarmSession", "tui output: " + chunk.replace('\n', ' ').take(400))
                }
                sawAnyOutput = true
                lastOutputAt = System.currentTimeMillis()
                lineBuffer.append(chunk)
                var newline = lineBuffer.indexOf("\n")
                while (newline >= 0) {
                    val line = lineBuffer.substring(0, newline).trimEnd('\r')
                    lineBuffer.delete(0, newline + 1)
                    if (line.isNotBlank() && boundary.onOutputLine(line)) finished = true
                    newline = lineBuffer.indexOf("\n")
                }
            }
            if (!finished && boundary.turnStarted) {
                val partial = lineBuffer.substring(lineBuffer.lastIndexOf("\n") + 1).trim()
                val isIdlePrompt = partial.startsWith(PROMPT_MARK) &&
                    (shortEcho.isEmpty() || !partial.contains(shortEcho))
                val quiet = System.currentTimeMillis() - lastOutputAt >= PROMPT_QUIET_MS
                if (isIdlePrompt && quiet) finished = true
            }
            if (System.currentTimeMillis() > deadline) {
                return WarmTurnResult(
                    failed = "Hermes did not finish the turn in time.",
                    sawAnyOutput = sawAnyOutput,
                    timedOut = true,
                )
            }
            if (!finished) delay(50)
        }

        // Catch the trailing deliveries (stream end, last post_tool_call) that
        // race the prompt repaint.
        var quietSince = System.currentTimeMillis()
        val graceEnd = System.currentTimeMillis() + GRACE_MS
        while (System.currentTimeMillis() < graceEnd) {
            val before = pendingHooks.size
            drain()
            if (before > 0) quietSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - quietSince >= QUIET_MS) break
            delay(50)
        }
        return WarmTurnResult(
            failed = if (sawText) null else lastError ?: NO_REPLY_MESSAGE,
            sawAnyOutput = sawAnyOutput,
        )
    }

    /** Waits until the session is idle at its prompt and able to accept input. */
    private suspend fun awaitInteractivePrompt(target: Process): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!target.isAlive) return false
            val chunk = sawNewOutput(target)
            if (chunk == null) {
                delay(50)
                continue
            }
            lineBuffer.append(chunk)
            var newline = lineBuffer.indexOf("\n")
            while (newline >= 0) {
                val line = lineBuffer.substring(0, newline).trimEnd('\r')
                lineBuffer.delete(0, newline + 1)
                if (line.trim().startsWith(PROMPT_MARK)) return true
                newline = lineBuffer.indexOf("\n")
            }
            // The idle prompt often has no trailing newline yet: accept the tail.
            val tail = lineBuffer.substring(lineBuffer.lastIndexOf("\n") + 1)
            if (tail.trim().startsWith(PROMPT_MARK)) return true
        }
        return false
    }

    private fun sawNewOutput(target: Process): String? {
        val native = target as? NativeSpawnProcess ?: return null
        val file: File = native.outputFile
        val available = file.length() - outputOffset
        if (available <= 0) return null
        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
        val count = RandomAccessFile(file, "r").use { random ->
            random.seek(outputOffset)
            random.read(bytes)
        }
        if (count <= 0) return null
        outputOffset += count
        val text = bytes.decodeToString(0, count)
        answerTerminalQueries(target, text)
        return text
    }

    /**
     * Answers the terminal capability queries the interactive TUI sends on a
     * raw PTY. It asks for the background colour and the terminal identity and
     * then waits: nothing is rendered and no turn runs until the answers come
     * back, which a headless reader never sends.
     *
     * Only the startup window is answered: once the prompt line is on screen
     * the TUI reads the same bytes as user input, and a late answer lands in
     * the input buffer as literal text.
     */
    private fun answerTerminalQueries(target: Process, chunk: String) {
        if (tuiReady) return
        queryTail.append(chunk)
        val seen = queryTail.toString()
        var unanswered = seen
        var answered = 0
        while (answered < MAX_QUERIES_PER_CHUNK) {
            val (query, reply) = terminalReplyFor(unanswered) ?: break
            runCatching {
                target.outputStream.write(reply.toByteArray(Charsets.UTF_8))
                target.outputStream.flush()
            }
            if (queryAnswers < MAX_LOGGED_QUERIES) {
                queryAnswers++
                Log.i("HermesWarmSession", "terminal query answered: ${query.toDebugText()}")
            }
            unanswered = unanswered.replaceFirst(query, "")
            answered++
        }
        if (seen.contains(PROMPT_MARK)) tuiReady = true
        if (seen.length > QUERY_TAIL_CHARS) queryTail.delete(0, seen.length - QUERY_TAIL_CHARS)
    }

    private fun String.toDebugText(): String = replace("\u001B", "ESC")

    override fun close() {
        val target = process
        process = null
        signature = null
        lineBuffer.setLength(0)
        queryTail.setLength(0)
        queryAnswers = 0
        tuiReady = false
        pendingHooks.clear()
        if (target != null) {
            // proot ignores the polite signal often enough that the wrapper has
            // to be killed outright; the guest children it leaves behind are
            // swept by the caller.
            Thread {
                runCatching { target.destroy() }
                runCatching { target.destroyForcibly() }
                Thread.sleep(500)
                if (target.isAlive) runCatching { target.destroyForcibly() }
                Log.i("HermesWarmSession", "close: pid=${target.spawnPid()} stillAlive=${target.isAlive}")
            }.apply { isDaemon = true; start() }
        }
        runCatching { server?.close() }
        server = null
    }

    companion object {
        private const val PROMPT_MARK = "❯"
        private const val READY_TIMEOUT_MS = 90_000L
        private const val TURN_TIMEOUT_MS = 10 * 60_000L
        private const val GRACE_MS = 2_000L
        private const val QUIET_MS = 300L
        private const val PROMPT_QUIET_MS = 400L
        private const val SHORT_ECHO_CHARS = 20
        private const val WARM_NOT_RUNNING = "The warm Hermes session is not running."
        private const val NO_REPLY_MESSAGE =
            "Hermes returned no reply for this turn. The warm session was dropped; the next turn starts fresh."
        private const val DIED_MESSAGE =
            "The Hermes session exited unexpectedly; the phone may have suspended it. Retry with the app in the foreground."
        private const val KILL_LINE = "\u0015"
        private const val PASTE_START = "\u001B[200~"
        private const val PASTE_END = "\u001B[201~"
        private const val QUERY_TAIL_CHARS = 32
        private const val MAX_QUERIES_PER_CHUNK = 3
        private const val MAX_LOGGED_QUERIES = 6
        private const val MAX_LOGGED_HOOKS = 8

        /** Terminal query the TUI sends -> the answer a real terminal gives. */
        internal val TERMINAL_REPLIES: List<Pair<String, String>> = listOf(
            // OSC 10/11: foreground/background colour query.
            "\u001B]11;?" to "\u001B]11;rgb:0000/0000/0000\u001B\\",
            "\u001B]10;?" to "\u001B]10;rgb:ffff/ffff/ffff\u001B\\",
            // Primary/secondary device attributes: "VT100 with advanced video".
            "\u001B[c" to "\u001B[?1;2c",
            "\u001B[>c" to "\u001B[>0;10;1c",
            // XTVERSION and kitty keyboard protocol: no extensions.
            "\u001B[>0q" to "\u001BP>|mobile-harness",
            "\u001B[?u" to "\u001B[?0u",
        )
    }
}

/** `Process.pid()` does not exist on Android; the native spawn does expose it. */
internal fun Process.spawnPid(): Int = (this as? NativeSpawnProcess)?.pid ?: -1

/** The first unanswered terminal query in [text], with the reply for it. */
internal fun terminalReplyFor(text: String): Pair<String, String>? =
    HermesWarmSession.TERMINAL_REPLIES.firstOrNull { text.contains(it.first) }
