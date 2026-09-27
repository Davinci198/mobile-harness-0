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
 * A session that is ready to run a turn: [seeded] is true when this call just
 * spawned it, which means the turn's prompt already went out through `-q` and
 * must not be typed into the prompt again.
 */
internal data class WarmStart(val process: Process, val seeded: Boolean)

/**
 * One long-lived interactive `hermes chat` process reused across Agent
 * Executions, so a turn pays the model latency instead of the 22-26s boot.
 *
 * The session is keyed by [signature] (provider, model, endpoint, workspace):
 * any change, a dead process or a boot failure closes it and the caller falls
 * back to the one-shot cold path. The first turn is seeded through `-q` (the
 * multi-line context prompt stays in argv, never in TUI input); later turns
 * are typed onto the PTY, the turn boundary comes from [WarmTurnBoundary] plus
 * the webhook stream events.
 */
internal class HermesWarmSession(
    private val ensureHookConfig: (String) -> Unit,
) : AutoCloseable {
    private var server: HermesHookServer? = null
    private var process: Process? = null
    private var signature: String? = null
    private var outputOffset = 0L
    private val lineBuffer = StringBuilder()
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
    ): WarmStart? {
        val current = process
        if (current != null && current.isAlive && this.signature == signature) {
            Log.d("HermesWarmSession", "reusing warm session pid=${current.spawnPid()}")
            if (!awaitSessionReady(current, seeded = false)) {
                close()
            } else {
                return WarmStart(current, seeded = false)
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
        pendingHooks.clear()
        if (!awaitSessionReady(spawned, seeded = true)) {
            close()
            return null
        }
        return WarmStart(spawned, seeded = true)
    }

    /** Runs one turn: submits [prompt] unless the session was seeded with it. */
    suspend fun runTurn(
        prompt: String,
        sessionId: String,
        submitPrompt: Boolean,
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
        if (submitPrompt) {
            val submission = (prompt.replace('\r', ' ').replace('\n', ' ').trim() + "\r")
                .toByteArray(Charsets.UTF_8)
            runCatching {
                target.outputStream.write(submission)
                target.outputStream.flush()
            }.onFailure {
                return WarmTurnResult(failed = "Could not send the prompt to the Hermes session.", sawAnyOutput = false)
            }
        }

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

        val drain: suspend () -> Unit = {
            while (true) {
                val payload = pendingHooks.poll() ?: break
                sawAnyOutput = true
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

    /**
     * Waits until the session can carry a turn.
     *
     * A freshly seeded session is already running the turn its `-q` prompt
     * started, so its idle prompt only renders once the answer is done: waiting
     * for it would always time out. Any output (or the first hook delivery)
     * proves the session is up, and nothing is consumed, so the turn still sees
     * every line. A reused session is idle, so there the prompt line is the
     * readiness signal.
     */
    private suspend fun awaitSessionReady(target: Process, seeded: Boolean): Boolean {
        if (!seeded) return awaitInteractivePrompt(target)
        val native = target as? NativeSpawnProcess ?: return false
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!target.isAlive) return false
            if (pendingHooks.isNotEmpty() || native.outputFile.length() > outputOffset) return true
            delay(50)
        }
        return false
    }

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
        return bytes.decodeToString(0, count)
    }

    override fun close() {
        val target = process
        process = null
        signature = null
        lineBuffer.setLength(0)
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
    }
}

/** `Process.pid()` does not exist on Android; the native spawn does expose it. */
internal fun Process.spawnPid(): Int = (this as? NativeSpawnProcess)?.pid ?: -1
