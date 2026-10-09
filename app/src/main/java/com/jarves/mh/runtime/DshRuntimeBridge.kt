package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.classifyRisk
import com.jarves.mh.model.isLoopbackBaseUrl
import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RuntimeBridge] driving the official DeepSeek Harness (`dsh`) in headless
 * one-shot mode: `dsh --profile headless "<task>"`.
 *
 * Verified contract (dsh 0.1.2-rc.1): the final answer goes to stdout, provider
 * reasoning deltas stream under a `dsh: reasoning:` heading, failures print
 * `dsh: <CODE>: <message>`, exit 0 means the turn completed. Because the
 * native launcher merges stdout+stderr into one capture file, this bridge
 * separates the streams by the `dsh:` diagnostic prefix.
 *
 * Auth and model selection are fully non-interactive: keys travel in the
 * process environment (`DEEPSEEK_API_KEY` for the native route, one shared
 * `MH_DSH_API_KEY` for hand-declared routes) and `$DSH_HOME/settings.yaml`
 * carries the default model plus any custom provider route.
 */
class DshRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir).forAgent(AgentKind.DEEPSEEK_HARNESS)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    /** Guest asks waiting for the ApprovalCard, keyed by dsh's approval id. */
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var approvalServer: DshApprovalServer? = null
    @Volatile private var activeProcess: Process? = null
    /**
     * The SDK process kept alive between Agent Executions. Reusing it skips the
     * 8-12s proot + node + dsh boot that dominated short turns.
     */
    @Volatile private var warmChannel: DshSdkChannel? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    private val warmLock = Mutex()
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    @Volatile private var lastThinkingUpdateAt: Long = 0L

    override suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        lastThinkingUpdateAt = 0L
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting DeepSeek Harness…")
        val secret = secretFor(provider).orEmpty()
        // A loopback gateway on this device runs keyless; only remote providers need a key.
        if (secret.isBlank() && !isLoopbackBaseUrl(provider.resolvedBaseUrl)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "No API key is saved for ${provider.kind.title}."))
            return@withContext sessionId
        }
        if (provider.kind == ProviderKind.CLAUDE) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "Claude subscription login is not supported by DeepSeek Harness. Pick a key-based provider in Settings.",
                ),
            )
            return@withContext sessionId
        }

        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread {
                        running.destroy()
                        Thread.sleep(500)
                        if (running.isAlive) running.destroyForcibly()
                    }.start()
                }
            }
            startForegroundRuntime(projectSlug, sessionId)
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
                "DeepSeek Harness is not installed. Open Settings → Coding agent to install it."
            }
            installer.ensureDshAndroidCompatibility()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val route = DshRouteMapper.forProfile(provider)
            writeDshSettings(installed.rootfs, route, provider)
            val environment = buildEnvironment(route, secret)
            val model = provider.model.ifBlank { route.defaultModel }

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)
            val command = listOf("/usr/local/bin/dsh", "--profile", "sdk")
            Log.d("DshBridge", "Route: ${route.name}, Model: ${provider.model}")
            // Route, model, workspace and credentials are all baked into the guest
            // process when it spawns, so any change to them forces a fresh boot.
            val signature = dshWarmSignature(route, model, guestWorkspacePath, workspace, environment)
            var lastFailure = ""
            for (attempt in 1..RETRY_MAX_ATTEMPTS) {
                if (userStopRequested) throw DshSessionException("Stopped by user")
                val channel = adoptChannel(signature) {
                    installer.process(
                        installed.proot,
                        installed.rootfs,
                        workspace,
                        environment,
                        command,
                        guestWorkspacePath = guestWorkspacePath,
                        // dsh's editor saves through an atomic temp-file rename. PRoot's
                        // hard-link emulation turns that rename into a dangling `.l2s`
                        // symlink after the temp file is removed, losing the real file.
                        emulateHardLinks = false,
                    )
                }
                activeProcess = channel.process
                if (userStopRequested) channel.process.destroy()
                val sdkResult = runSdkSession(
                    channel = channel,
                    sessionId = sessionId,
                    route = route,
                    model = model,
                    guestWorkspacePath = guestWorkspacePath,
                    prompt = contextPrompt,
                )
                if (sdkResult.completed && !userStopRequested) {
                    val changed = checkpoints.changedFiles(workspace, before)
                    if (changed.isNotEmpty()) {
                        Log.d("DshBridge", "Changed files: $changed")
                        checkpoints.saveChangedPaths(projectId, changed, workspace)
                        val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                        eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                    }
                    emitCompletedOnce(sessionId)
                    finishForegroundRuntime(
                        completed = true,
                        projectName = projectSlug,
                        detail = "DeepSeek Harness finished the task in $projectSlug.",
                    )
                    return@runCatching
                }
                // A failed turn leaves the reused process in an unknown state: drop it
                // so the retry (and every later Agent Execution) boots from scratch.
                val deadExit = if (channel.process.isAlive) {
                    null
                } else {
                    runCatching { channel.process.exitValue() }.getOrNull()
                }
                closeWarmChannel(channel)
                activeProcess = null
                if (userStopRequested) throw DshSessionException("Stopped by user")
                lastFailure = sdkResult.failure.ifBlank {
                    if (deadExit != null) "DeepSeek Harness stopped with exit code $deadExit"
                    else "DeepSeek Harness stopped unexpectedly"
                }
                if (!isTransientProviderOverload(lastFailure) || attempt >= RETRY_MAX_ATTEMPTS) {
                    error(lastFailure)
                }
                pushForegroundProgress("DeepSeek Harness provider is overloaded — retrying ($attempt/${RETRY_MAX_ATTEMPTS})…")
                eventBus.emit(
                    RuntimeEvent.AssistantDelta(
                        sessionId,
                        "\n\n[Provider temporarily overloaded — retrying ($attempt/${RETRY_MAX_ATTEMPTS})…]\n",
                    ),
                )
                delay(RETRY_BACKOFF_MS * attempt)
            }
            error(lastFailure)
        }.onFailure { error ->
            Log.e("DshBridge", "Session failed", error)
            val message = friendlyError(error)
            emitFailureOnce(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = projectSlug,
                    detail = message,
                )
            }
        }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    /**
     * Runs one Agent Execution on [channel].
     *
     * The `initialize` handshake only runs for a freshly spawned process; a
     * reused one goes straight to `session/prompt`, which is where the 8-12s
     * proot + node + dsh boot is skipped. The process is deliberately left
     * running afterwards: the next Agent Execution resumes reading the same
     * capture file exactly where this turn stopped.
     */
    private suspend fun runSdkSession(
        channel: DshSdkChannel,
        sessionId: String,
        route: DshRoute,
        model: String,
        guestWorkspacePath: String,
        prompt: String,
    ): DshSdkRunResult {
        val process = channel.process
        val nativeProcess = process as? NativeSpawnProcess
            ?: error("Unsupported Android runtime process")
        val parser = DshSdkProtocolParser(sessionId)
        var sawRunning = false
        var completed = false
        var sawActivity = false
        var turnEndedAt = 0L
        var lastByteAt = android.os.SystemClock.elapsedRealtime()
        var failure = ""
        // Weak models loop a failing tool call (invented file paths, malformed
        // args) with no text; the harness itself has no step cap, so guard here
        // and stop the session instead of letting it spin until the user stops.
        val stuckGuard = DshStuckGuard()

        if (!channel.initialized) {
            val bootFailure = awaitSdkInitialized(channel, route, model, guestWorkspacePath)
            if (bootFailure != null) return DshSdkRunResult(completed = false, failure = bootFailure)
        }
        channel.send(
            method = "session/prompt",
            id = SDK_PROMPT_ID,
            params = JSONObject()
                .put("sessionId", sessionId)
                .put(
                    "contentBlocks",
                    JSONArray().put(JSONObject().put("type", "text").put("text", prompt)),
                ),
        )

        fun markTurnEnded() {
            turnEndedAt = android.os.SystemClock.elapsedRealtime()
            lastByteAt = turnEndedAt
        }

        suspend fun handle(protocolEvent: DshSdkProtocolEvent) {
            when (protocolEvent) {
                DshSdkProtocolEvent.Initialized,
                DshSdkProtocolEvent.PromptAccepted,
                DshSdkProtocolEvent.ShutdownAcknowledged,
                DshSdkProtocolEvent.Ignored,
                -> Unit
                is DshSdkProtocolEvent.Status -> {
                    if (protocolEvent.running) {
                        sawRunning = true
                        pushForegroundProgress("DeepSeek Harness is working…")
                    } else if (sawRunning && turnEndedAt == 0L) {
                        completed = sawActivity && failure.isBlank()
                        if (!completed && failure.isBlank()) {
                            failure = "DeepSeek Harness stopped before processing the prompt"
                        }
                        markTurnEnded()
                    }
                }
                is DshSdkProtocolEvent.Reasoning -> {
                    sawActivity = true
                    stuckGuard.reset()
                    emitReasoningSummary(
                        sessionId = sessionId,
                        text = protocolEvent.text,
                        blockId = protocolEvent.blockId,
                        startsNewBlock = protocolEvent.startsNewBlock,
                        isFinal = protocolEvent.isFinal,
                        force = protocolEvent.startsNewBlock || protocolEvent.isFinal,
                    )
                }
                is DshSdkProtocolEvent.ToolStarted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolStarted(sessionId, protocolEvent.name, protocolEvent.detail),
                    )
                    if (stuckGuard.noteToolStarted(protocolEvent.name, protocolEvent.detail)) {
                        failure = "The agent repeated the same tool call ${DshStuckGuard.IDENTICAL_LIMIT} times " +
                            "in a row without producing any response. Try a stronger model or rephrase the request."
                        channel.closeInput()
                        process.destroy()
                    }
                }
                is DshSdkProtocolEvent.ToolCompleted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolCompleted(sessionId, protocolEvent.name, protocolEvent.summary),
                    )
                    if (stuckGuard.noteToolCompleted(protocolEvent.summary)) {
                        failure = "The agent repeated a failing tool call ${DshStuckGuard.LIMIT} times in a row " +
                            "without producing any response. Try a stronger model or rephrase the request."
                        channel.closeInput()
                        process.destroy()
                    }
                }
                is DshSdkProtocolEvent.AssistantText -> {
                    if (protocolEvent.text.isNotEmpty()) {
                        sawActivity = true
                        stuckGuard.reset()
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, protocolEvent.text))
                    }
                }
                // A failure ends the turn too: the SDK server keeps running, so
                // waiting for the process to exit would hang the Agent Execution.
                is DshSdkProtocolEvent.Failed -> {
                    failure = protocolEvent.message
                    markTurnEnded()
                }
                DshSdkProtocolEvent.TurnCompleted -> sawActivity = true
            }
        }

        while (true) {
            val outputFile = nativeProcess.outputFile
            if (!process.isAlive && outputFile.length() <= channel.outputOffset) break
            if (turnEndedAt > 0L) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastByteAt >= WARM_TURN_QUIET_MS || now - turnEndedAt >= WARM_TURN_DRAIN_TIMEOUT_MS) {
                    break
                }
            }
            val available = outputFile.length() - channel.outputOffset
            if (available <= 0) {
                delay(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(outputFile, "r").use { file ->
                file.seek(channel.outputOffset)
                file.read(bytes)
            }
            if (count <= 0) continue
            channel.outputOffset += count
            lastByteAt = android.os.SystemClock.elapsedRealtime()
            channel.pendingOutput.append(bytes.decodeToString(0, count))
            var newline = channel.pendingOutput.indexOf("\n")
            while (newline >= 0) {
                val line = channel.pendingOutput.substring(0, newline).trimEnd('\r')
                channel.pendingOutput.delete(0, newline + 1)
                if (line.isNotBlank()) handle(parser.parseLine(line))
                newline = channel.pendingOutput.indexOf("\n")
            }
        }
        channel.pendingOutput.toString().trim().takeIf(String::isNotBlank)?.let {
            handle(parser.parseLine(it))
        }
        channel.turns++
        Log.d("DshBridge", "SDK turn done: completed=$completed, failure=${failure.take(120)}")
        return DshSdkRunResult(completed = completed, failure = failure)
    }

    /**
     * Sends `initialize` and reads until the SDK answers it, consuming everything
     * the handshake prints so a later turn never re-reads it.
     *
     * Runs under [warmLock] with a single initializer/reader per channel and
     * gives up after [SDK_INIT_TIMEOUT_MS] instead of waiting on a live process
     * that never answers.
     *
     * Returns null once the channel is ready, otherwise the failure to report -
     * possibly blank, so the caller can substitute the process exit code.
     */
    private suspend fun awaitSdkInitialized(
        channel: DshSdkChannel,
        route: DshRoute,
        model: String,
        guestWorkspacePath: String,
    ): String? = warmLock.withLock {
        // Single initializer and single reader: the handshake shares
        // channel.outputOffset/pendingOutput with the turn reader, so a second
        // concurrent caller would send `initialize` again and race for the same
        // response bytes — one reader would consume both answers and the other
        // would wait forever while the process stays alive.
        if (channel.initialized) return@withLock null
        val process = channel.process
        val nativeProcess = process as? NativeSpawnProcess
            ?: error("Unsupported Android runtime process")
        channel.send(
            method = "initialize",
            id = SDK_INITIALIZE_ID,
            params = JSONObject()
                .put("cwd", guestWorkspacePath)
                .put("provider", route.name)
                .put("model", model),
        )
        val parser = DshSdkProtocolParser("")
        var failure: String? = null
        var ready = false
        val deadline = android.os.SystemClock.elapsedRealtime() + SDK_INIT_TIMEOUT_MS
        while (!ready && (process.isAlive || nativeProcess.outputFile.length() > channel.outputOffset)) {
            if (android.os.SystemClock.elapsedRealtime() > deadline) {
                failure = "SDK initialize timed out after ${SDK_INIT_TIMEOUT_MS / 1000}s"
                break
            }
            val available = nativeProcess.outputFile.length() - channel.outputOffset
            if (available <= 0) {
                delay(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                file.seek(channel.outputOffset)
                file.read(bytes)
            }
            if (count <= 0) continue
            channel.outputOffset += count
            channel.pendingOutput.append(bytes.decodeToString(0, count))
            var newline = channel.pendingOutput.indexOf("\n")
            while (newline >= 0) {
                val line = channel.pendingOutput.substring(0, newline).trimEnd('\r')
                channel.pendingOutput.delete(0, newline + 1)
                if (line.isNotBlank()) {
                    when (val event = parser.parseLine(line)) {
                        DshSdkProtocolEvent.Initialized -> ready = true
                        is DshSdkProtocolEvent.Failed -> {
                            failure = event.message
                            ready = true
                        }
                        else -> Unit
                    }
                }
                newline = channel.pendingOutput.indexOf("\n")
            }
        }
        if (!ready) return@withLock failure.orEmpty()
        channel.initialized = true
        Log.d("DshBridge", "SDK handshake ready (reused=${channel.turns > 0})")
        null
    }

    /**
     * The process backing the current Agent Execution: freshly spawned, or the
     * one the previous turn left running. Anything else is a cold start.
     *
     * Acquisition runs under [warmLock]: a prewarm racing a turn would
     * otherwise both see no channel, spawn two processes and leak the one
     * that loses the `warmChannel` assignment.
     */
    private suspend fun adoptChannel(signature: String, spawn: () -> Process): DshSdkChannel =
        warmLock.withLock { acquireChannel(signature, spawn) }

    /**
     * Prewarm-only variant: the active-execution guard is re-checked inside
     * the lock, because a turn can set `activeSessionId` after the caller's
     * own check but before the spawn. Null when a turn is (or just became)
     * active — the caller silently backs off.
     */
    private suspend fun adoptIdleChannel(signature: String, spawn: () -> Process): DshSdkChannel? =
        warmLock.withLock {
            if (activeSessionId != null) return@withLock null
            acquireChannel(signature, spawn)
        }

    private fun acquireChannel(signature: String, spawn: () -> Process): DshSdkChannel {
        warmChannel?.takeIf { it.signature == signature && it.process.isAlive && it.turns < MAX_WARM_TURNS }
            ?.let { return it }
        closeWarmChannelBlocking()
        return DshSdkChannel(signature, spawn()).also { warmChannel = it }
    }

    /**
     * Shuts the reused SDK process down. A turn that failed leaves the server in
     * an unknown state, so every failure path drops the channel rather than
     * risking a half-disposed agent on the next turn.
     *
     * [expected] closes only that instance: a failure handler whose turn ended
     * long ago must not kill the channel a concurrent caller already adopted.
     */
    private suspend fun closeWarmChannel(expected: DshSdkChannel? = null) {
        val channel = warmLock.withLock {
            val current = warmChannel ?: return@withLock null
            if (expected != null && current !== expected) return@withLock null
            warmChannel = null
            current
        } ?: return
        destroyChannel(channel)
    }

    /** Non-suspend variant for the spawn path, where there is nothing to await. */
    private fun closeWarmChannelBlocking() {
        val channel = warmChannel ?: return
        warmChannel = null
        runCatching { channel.send("shutdown", SDK_SHUTDOWN_ID) }
        runCatching { channel.closeInput() }
        Thread {
            runCatching { channel.process.destroy() }
            runCatching { channel.process.destroyForcibly() }
        }.apply { isDaemon = true; start() }
    }

    private suspend fun destroyChannel(channel: DshSdkChannel) {
        runCatching { channel.send("shutdown", SDK_SHUTDOWN_ID) }
        // The SDK disposes its agents while answering shutdown; give it a beat
        // before the pipe is cut, then fall back to the proot kill.
        delay(SDK_SHUTDOWN_GRACE_MS)
        channel.closeInput()
        if (channel.process.isAlive) {
            channel.process.destroy()
            delay(300)
            if (channel.process.isAlive) channel.process.destroyForcibly()
        }
        Log.i("DshBridge", "SDK channel closed (turns=${channel.turns}, alive=${channel.process.isAlive})")
    }

    /** Guest environment for [route]; identical across every warm reuse. */
    private fun buildEnvironment(route: DshRoute, secret: String): Map<String, String> {
        val environment = linkedMapOf(
            // PRoot's --link2symlink turns the loader's native-binding cache
            // hard-link into a dangling .l2s symlink; with hard links disabled
            // (always on for dsh) the cache can still contain a poisoned copy,
            // so load the binding straight from its installed source.
            "NARB_DISABLE_NATIVE_CACHE" to "1",
            "DSH_HOME" to DSH_HOME_GUEST_PATH,
            // The guest answerer posts approval asks to this port; the value is
            // stable for the process, so warm reuse keeps the same signature.
            "MH_APPROVAL_PORT" to ensureApprovalServer().port.toString(),
            // PocketDev already confines the whole Linux guest with PRoot. Let dsh
            // use every tool inside that boundary without an unavailable approval UI.
            "DSH_PERMISSION_MODE" to "danger-full-access",
            // Guest CLIs refuse to boot with an empty key variable; a loopback
            // gateway ignores the header, so send a placeholder instead.
            route.keyEnv to secret.ifBlank { "loopback" },
        )
        if (route.keyEnv != FALLBACK_KEY_ENV) environment.remove(FALLBACK_KEY_ENV)
        return environment
    }

    /**
     * Boots the SDK process for [provider] right after a provider save so the
     * next Agent Execution pays model latency instead of the 8-12s PRoot boot.
     * Best effort: a cold-only state, an execution already in flight, or an
     * uninstallable runtime makes this a silent no-op.
     */
    override suspend fun prewarmSession(
        provider: ProviderProfile,
        projectId: String,
        projectSlug: String,
    ) {
        withContext(Dispatchers.IO) {
            Log.d("Prewarm", "enter: activeSessionId=$activeSessionId kind=${provider.kind} secretLen=${secretFor(provider)?.length ?: 0}")
            if (activeSessionId != null) return@withContext
            val secret = secretFor(provider).orEmpty()
            if (secret.isBlank() && !isLoopbackBaseUrl(provider.resolvedBaseUrl)) return@withContext
            if (provider.kind == ProviderKind.CLAUDE) return@withContext
            runCatching {
                if (!installer.isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
                    Log.d("Prewarm", "skip: runtime not installed")
                    return@runCatching
                }
                val installed = installer.installedRuntime()
                installer.ensureDshAndroidCompatibility()
                val route = DshRouteMapper.forProfile(provider)
                writeDshSettings(installed.rootfs, route, provider)
                Log.d("Prewarm", "settings.yaml written for ${provider.kind}")
                val model = provider.model.ifBlank { route.defaultModel }
                val environment = buildEnvironment(route, secret)
                val workspace = checkpoints.ensureWorkspace(projectId)
                val guestWorkspacePath = "/workspace/$projectSlug"
                val signature = dshWarmSignature(route, model, guestWorkspacePath, workspace, environment)
                val channel = adoptIdleChannel(signature) {
                    installer.process(
                        installed.proot,
                        installed.rootfs,
                        workspace,
                        environment,
                        listOf("/usr/local/bin/dsh", "--profile", "sdk"),
                        guestWorkspacePath = guestWorkspacePath,
                        emulateHardLinks = false,
                    )
                } ?: return@runCatching
                if (channel.initialized) return@runCatching
                val failure = awaitSdkInitialized(channel, route, model, guestWorkspacePath)
                if (failure == null) {
                    Log.i("DshBridge", "SDK session prewarmed for $projectSlug")
                } else {
                    Log.w("DshBridge", "Prewarm failed (${failure.take(120)}); the turn boots cold")
                    closeWarmChannel(channel)
                }
            }.onFailure { Log.w("DshBridge", "Prewarm skipped", it) }
        }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        val decision = pendingApprovals.remove(request.approvalId) ?: return
        decision.complete(if (approved) "allowed-once" else "rejected")
        eventBus.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
    }

    /**
     * One guest ask: show the ApprovalCard and wait for [APPROVAL_TIMEOUT_MS]
     * for the user's decision. A sessionless or timed-out ask fails closed as
     * `unavailable`, and a timeout also clears the card it raised.
     */
    private suspend fun requestApprovalDecision(callId: String, toolName: String, reason: String?): String {
        val sessionId = activeSessionId ?: return "unavailable"
        val request = ToolRequest(
            approvalId = callId,
            sessionId = sessionId,
            toolName = toolName,
            explanation = reason?.takeIf(String::isNotBlank) ?: "$toolName needs approval",
            risk = classifyRisk(toolName, null),
        )
        val decision = CompletableDeferred<String>()
        pendingApprovals[callId] = decision
        eventBus.emit(RuntimeEvent.ToolRequested(sessionId, request))
        val outcome = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { decision.await() }
        pendingApprovals.remove(callId, decision)
        if (outcome == null) {
            eventBus.emit(RuntimeEvent.ToolRejected(sessionId, callId))
            return "unavailable"
        }
        return outcome
    }

    private fun ensureApprovalServer(): DshApprovalServer =
        approvalServer ?: synchronized(this) {
            approvalServer ?: DshApprovalServer { callId, toolName, reason ->
                requestApprovalDecision(callId, toolName, reason)
            }.start().also { approvalServer = it }
        }

    /** Complete every unanswered ask so a dead card cannot outlive its session. */
    private suspend fun cancelPendingApprovals(sessionId: String) {
        pendingApprovals.keys.forEach { callId ->
            if (pendingApprovals.remove(callId)?.complete("cancelled") == true) {
                eventBus.emit(RuntimeEvent.ToolRejected(sessionId, callId))
            }
        }
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    private fun writeDshSettings(rootfs: File, route: DshRoute, provider: ProviderProfile) {
        val home = File(rootfs, DSH_HOME_GUEST_PATH.removePrefix("/")).apply { mkdirs() }
        val body = buildString {
            appendLine("agent-default-model:")
            appendLine("  provider: ${route.name}")
            appendLine("  model: ${yamlQuote(provider.model.ifBlank { route.defaultModel })}")
            if (route.custom != null) {
                appendLine("llm-pi-ai:")
                appendLine("  providers:")
                appendLine("    ${route.name}:")
                appendLine("      apiKeyEnv: ${route.keyEnv}")
                appendLine("      api: ${route.custom.api}")
                appendLine("      baseURL: ${yamlQuote(route.custom.baseUrl)}")
                appendLine("      models:")
                appendLine("        - id: ${yamlQuote(provider.model.ifBlank { route.defaultModel })}")
            }
        }
        File(home, "settings.yaml").writeText(body)
        // dsh 0.2.0 reads the Cordis home patch (`$DSH_HOME/cordis.patch.yml`),
        // applied over every profile's own layer, instead of settings.yaml (which
        // it renames to settings.yaml.imported). Patch the llm-pi-ai row with the
        // custom route so the SDK resolves its provider at boot; deepseek-official
        // needs no entry because the DSH_HOME credentials service picks its key out
        // of the DEEPSEEK_API_KEY environment we export.
        val patch = dshHomePatch(route, provider.model.ifBlank { route.defaultModel })
        File(home, "cordis.patch.yml").writeText(patch)
        deployApprovalAnswerer(home)
    }

    /** Drop the guest-side `approval/request` answerer at its patch-referenced path. */
    private fun deployApprovalAnswerer(home: File) {
        runCatching {
            val directory = File(home, "plugins/mh-approval-answerer").apply { mkdirs() }
            File(directory, "index.js").writeText(APPROVAL_ANSWERER_JS)
        }.onFailure { Log.w("DshBridge", "Approval answerer deploy failed", it) }
    }

private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        blockId: Long,
        startsNewBlock: Boolean,
        isFinal: Boolean,
        force: Boolean = false,
    ) {
        val summary = text.replace(Regex("\\s+"), " ").trim().take(2_000)
        if (summary.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastThinkingUpdateAt >= 400) {
            lastThinkingUpdateAt = now
            eventBus.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = summary,
                    blockId = blockId,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                ),
            )
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            cancelPendingApprovals(sessionId)
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "your project",
                detail = "DeepSeek Harness finished the task.",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            cancelPendingApprovals(sessionId)
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "your project",
                    detail = reason,
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is DshSessionException -> message
            message.contains("authentication", true) ||
                message.contains("invalid api key", true) ||
                message.contains("autherror", true) ||
                message.contains("expired", true) ||
                message.contains("quota", true) ||
                message.contains("rate limit", true) ||
                listOf("401", "403", "429").any { code ->
                    message.contains(code) && (message.contains("auth", true) || message.contains("HTTP", true))
                } ->
                "The provider rejected the saved API key."
            message.contains("missing_credential", true) ->
                "No API key reached DeepSeek Harness. Re-save the provider key in Settings."
            message.contains("not installed", true) -> message.take(300)
            message.isBlank() -> "DeepSeek Harness could not start."
            else -> message.take(500)
        }
    }

    private fun buildContextPrompt(currentPrompt: String, history: List<ChatMessage>, guestWorkspacePath: String, projectKind: ProjectKind): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                !msg.text.startsWith("Failed to") &&
                !msg.text.startsWith("Error:") &&
                !msg.text.contains("API Error")
            }
            .dropLast(1)

        val sb = StringBuilder()
        sb.appendLine("The <project-context> and <conversation_history> sections below are plain text provided in this message. They are NOT files on disk — do not try to read, edit, glob, or otherwise locate any file mentioned by their names (for example response.md, project-context.txt, or conversation_history.txt does not exist). Answer the user's latest message directly at the end of this conversation.")
        sb.appendLine("<project-context>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("If the user is just chatting or asking a question, reply directly without calling any tool.")
            sb.appendLine("Use terminal or file tools only when the request actually involves files or commands.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (installer.isStackInstalled(DevStack.ANDROID)) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
            sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
            sb.appendLine("PocketDev globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this PocketDev runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in PocketDev Settings before building.")
        }
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project-context>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(projectName: String, sessionId: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, sessionId),
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(
                        if (completed) RuntimeExecutionService.ACTION_COMPLETE
                        else RuntimeExecutionService.ACTION_FAILED,
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, activeSessionId)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w("DshBridge", "Could not post task result notification", error)
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED)
                    .putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, activeSessionId),
            )
        }.onFailure {
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    /**
     * Provider-side transient failures (for example NVIDIA NIM "Service temporarily
     * overloaded", PI_AI_ERROR) can be retried by re-running the SDK process. Anything
     * else (bad key, quota, blocking) must surface immediately.
     */
    private fun isTransientProviderOverload(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("temporarily overloaded") ||
            m.contains("overloaded") ||
            m.contains("temporarily unavailable") ||
            m.contains("service temporarily") ||
            m.contains("pi_ai_error") ||
            m.contains("try again later") ||
            m.contains("backoff")
    }

    private class DshSessionException(message: String) : IllegalStateException(message)

    companion object {
        const val DSH_HOME_GUEST_PATH = "/root/.dsh"
        const val FALLBACK_KEY_ENV = "MH_DSH_API_KEY"
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val SDK_INITIALIZE_ID = 1
        private const val SDK_PROMPT_ID = 2
        private const val SDK_SHUTDOWN_ID = 3
        private const val SDK_SHUTDOWN_GRACE_MS = 400L
        private const val SDK_INIT_TIMEOUT_MS = 60_000L
        private const val RETRY_MAX_ATTEMPTS = 4
        private const val RETRY_BACKOFF_MS = 4_000L

        /** How long the ApprovalCard may keep one guest ask open. */
        private const val APPROVAL_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * Guest-side answerer for dsh's `approval/request` waterfall: forwards
         * the ask to [DshApprovalServer] and closes it with the user's
         * decision. Every failure (no port, unreachable host, bad payload)
         * fails closed as `unavailable`, which is also dsh's own default for a
         * missing answerer.
         */
        private const val APPROVAL_ANSWERER_JS = """
export default function mhApprovalAnswerer(ctx) {
  ctx.on("approval/request", async (request) => {
    const port = process.env.MH_APPROVAL_PORT;
    if (!port) return "unavailable";
    try {
      const response = await fetch("http://127.0.0.1:" + port + "/approval", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          callId: request.callId ?? null,
          toolName: request.toolName ?? "tool",
          reason: request.reason ?? null,
        }),
        signal: AbortSignal.timeout(630000),
      });
      if (!response.ok) return "unavailable";
      const outcome = (await response.json()).outcome;
      return ["allowed-once", "rejected", "cancelled", "unavailable"].includes(outcome)
        ? outcome
        : "unavailable";
    } catch {
      return "unavailable";
    }
  });
}
"""

        /**
         * Silence the reused process must keep after a turn ends before the
         * turn is considered over. The SDK stops emitting as soon as the session
         * goes idle, so a few quiet hundred milliseconds only catch the trailing
         * records that race the `session.status` notification.
         */
        private const val WARM_TURN_QUIET_MS = 400L

        /** Hard cap on the drain, so a chatty process cannot pin the turn open. */
        private const val WARM_TURN_DRAIN_TIMEOUT_MS = 3_000L

        /**
         * The SDK only disposes server-owned agents on `shutdown`, and each turn
         * creates one under its own session id, so recycle the process before the
         * map grows without bound.
         */
        private const val MAX_WARM_TURNS = 25
    }
}

internal fun yamlQuote(value: String): String = "'${value.replace("'", "''")}'"

/**
 * Cordis home patch for the dsh 0.2.0 settings surface
 * (`$DSH_HOME/cordis.patch.yml`), applied over every profile's own layer.
 *
 * Three concerns share the file:
 * - Custom routes patch the llm-pi-ai row so the SDK resolves the provider at
 *   boot; deepseek-official needs no entry because the credentials service
 *   picks its key out of the DEEPSEEK_API_KEY environment we export. Omitting
 *   the row keeps dsh-base's default providers, so a later custom-route
 *   session's providers never leak into an official session.
 * - The insert entry loads the guest answerer that forwards `approval/request`
 *   asks to [DshApprovalServer] over loopback.
 * - The permission presets ask only while `MH_APPROVAL_PORT` is exported (the
 *   SDK bridge always exports it): with no listener the danger preset keeps
 *   dsh's original `never`, so one-shot runs without an approval channel stay
 *   unchanged, while a connected session raises the ApprovalCard.
 */
internal fun dshHomePatch(route: DshRoute, model: String): String = buildString {
    if (route.custom != null) {
        appendLine("- id: llm-pi-ai")
        appendLine("  config:")
        appendLine("    providers:")
        appendLine("      ${route.name}:")
        appendLine("        apiKeyEnv: ${route.keyEnv}")
        appendLine("        api: ${route.custom.api}")
        appendLine("        baseURL: ${yamlQuote(route.custom.baseUrl)}")
        appendLine("        models:")
        appendLine("          - id: ${yamlQuote(model)}")
    }
    appendLine("- insert:")
    appendLine("    - id: mh-approval")
    appendLine("      name: ${DshRuntimeBridge.DSH_HOME_GUEST_PATH}/plugins/mh-approval-answerer/index.js")
    appendLine("- id: permission")
    appendLine("  config:")
    appendLine("    defaultPreset: !!js \"process.env.MH_APPROVAL_PORT ? 'danger-full-access' : undefined\"")
    appendLine("    presets:")
    appendLine("      read-only:")
    appendLine("        sandbox: read-only")
    appendLine("        approval: ask")
    appendLine("      workspace-write:")
    appendLine("        sandbox: workspace-write")
    appendLine("        approval: ask")
    appendLine("      danger-full-access:")
    appendLine("        sandbox: danger-full-access")
    appendLine("        approval: !!js \"process.env.MH_APPROVAL_PORT ? 'ask' : 'never'\"")
}

private data class DshSdkRunResult(val completed: Boolean, val failure: String)

/**
 * One `dsh --profile sdk` process plus the read state its turns share.
 *
 * PRoot merges stdout and stderr into a single append-only capture file, so the
 * next turn has to resume reading exactly where the previous one stopped: the
 * offset and the partial line live here rather than in a single turn's stack.
 * The writer stays open for the life of the process because stdin carries every
 * later `session/prompt`.
 */
internal class DshSdkChannel(val signature: String, val process: Process) {
    val writer: BufferedWriter = process.outputStream.bufferedWriter()
    val pendingOutput = StringBuilder()
    var outputOffset = 0L
    var initialized = false
    var turns = 0
    private var inputClosed = false

    fun send(method: String, id: Int, params: JSONObject? = null) {
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) frame.put("params", params)
        writer.write(frame.toString())
        writer.newLine()
        writer.flush()
    }

    fun closeInput() {
        if (inputClosed) return
        inputClosed = true
        runCatching { writer.close() }
    }
}

/**
 * Warm-session identity: every input baked into the guest process at spawn.
 * `writeDshSettings` derives its file from the route and model, so those two
 * cover the on-disk config; the workspace and environment carry the project
 * and the provider key.
 */
internal fun dshWarmSignature(
    route: DshRoute,
    model: String,
    guestWorkspacePath: String,
    workspace: File,
    environment: Map<String, String>,
): String = listOf(
    route.name,
    route.custom?.api ?: "",
    route.custom?.baseUrl ?: "",
    model,
    guestWorkspacePath,
    workspace.absolutePath,
    environment.toString(),
).joinToString("|")

/** dsh provider route resolved from our saved provider profile. */
internal data class DshRoute(
    val name: String,
    val keyEnv: String,
    val defaultModel: String,
    val custom: DshCustomRoute? = null,
)

internal data class DshCustomRoute(val api: String, val baseUrl: String)

internal object DshRouteMapper {
    fun forProfile(profile: ProviderProfile): DshRoute {
        val model = profile.model.ifBlank { profile.kind.defaultModel }
        return when (profile.kind) {
            ProviderKind.DEEPSEEK -> DshRoute(
                name = "deepseek-official",
                keyEnv = "DEEPSEEK_API_KEY",
                defaultModel = model,
            )
            ProviderKind.ANTHROPIC -> DshRoute(
                name = "mh-anthropic",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", profile.resolvedBaseUrl),
            )
            ProviderKind.LLM_ROUTER -> DshRoute(
                name = "mh-openrouter",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", profile.resolvedBaseUrl),
            )
            ProviderKind.KIMI -> DshRoute(
                name = "mh-kimi",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
            ProviderKind.OPENCODE_ZEN -> DshRoute(
                name = "opencode-zen",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-responses", profile.resolvedBaseUrl),
            )
            ProviderKind.NVIDIA_NIM -> DshRoute(
                name = "nvidia-nim",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-completions", profile.resolvedBaseUrl),
            )
            ProviderKind.CUSTOM -> DshRoute(
                name = "mh-custom",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
            ProviderKind.CLAUDE -> throw IllegalArgumentException("Claude subscription login is not supported by DeepSeek Harness")
            ProviderKind.FREE -> throw IllegalArgumentException("The Free provider is only available for Hermes")
        }
    }
}

/** One classified line of merged headless output (stdout+stderr share a capture file). */
internal sealed interface DshLine {
    data object ReasoningHeading : DshLine
    data class Reasoning(val text: String) : DshLine
    data class Diagnostic(val text: String) : DshLine
    data class Answer(val text: String) : DshLine
}

internal object DshHeadlessParser {
    fun parseLine(rawLine: String): DshLine {
        val line = rawLine.trim()
        if (line.startsWith("dsh:")) {
            val body = line.removePrefix("dsh:").trim()
            if (body.startsWith("reasoning:")) {
                val rest = body.removePrefix("reasoning:").trim()
                return if (rest.isBlank()) DshLine.ReasoningHeading else DshLine.Reasoning(rest)
            }
            return DshLine.Diagnostic(body.ifBlank { line })
        }
        return DshLine.Answer(line)
    }
}

internal sealed interface DshSdkProtocolEvent {
    data object Initialized : DshSdkProtocolEvent
    data object PromptAccepted : DshSdkProtocolEvent
    data class Status(val running: Boolean) : DshSdkProtocolEvent
    data class Reasoning(
        val blockId: Long,
        val text: String,
        val startsNewBlock: Boolean,
        val isFinal: Boolean,
    ) : DshSdkProtocolEvent
    data class ToolStarted(val callId: String, val name: String, val detail: String) : DshSdkProtocolEvent
    data class ToolCompleted(val callId: String, val name: String, val summary: String) : DshSdkProtocolEvent
    data class AssistantText(val text: String) : DshSdkProtocolEvent
    data class Failed(val message: String) : DshSdkProtocolEvent
    data object TurnCompleted : DshSdkProtocolEvent
    data object ShutdownAcknowledged : DshSdkProtocolEvent
    data object Ignored : DshSdkProtocolEvent
}

/**
 * Watches for the harness (which has no step cap) spinning on the same failing
 * tool result — the signature of a weak model stuck in a loop: repeated error
 * summaries with no assistant text or reasoning in between. Once LIMIT failing
 * results arrive back-to-back, the session is aborted by its caller.
 */
internal class DshStuckGuard {
    private var streak = 0
    private var lastSignature = ""
    private var identicalStreak = 0

    /**
     * Returns true when the loop limit was just reached (and resets).
     *
     * Failing summaries count as one class of loop signal. Identical tool calls
     * that succeed (for example `update_goal` completing the same goal over and
     * over) are tracked separately and never clear the failure streak.
     */
    fun noteToolCompleted(summary: String): Boolean {
        val failed = summary.startsWith("error", ignoreCase = true) ||
            summary.contains("not found", ignoreCase = true) ||
            summary.contains("invalid", ignoreCase = true)
        if (!failed) {
            streak = 0
            return false
        }
        streak++
        if (streak < LIMIT) return false
        streak = 0
        return true
    }

    /** Returns true when the same tool+detail was invoked [IDENTICAL_LIMIT] times consecutively. */
    fun noteToolStarted(name: String, detail: String): Boolean {
        val signature = name + "\u0000" + detail
        identicalStreak = if (signature == lastSignature) identicalStreak + 1 else 1
        lastSignature = signature
        if (identicalStreak < IDENTICAL_LIMIT) return false
        identicalStreak = 0
        return true
    }

    fun reset() {
        streak = 0
        identicalStreak = 0
        lastSignature = ""
    }

    companion object {
        const val LIMIT = 5
        const val IDENTICAL_LIMIT = 6
    }
}

/** Stateful parser for the pinned dsh SDK's newline-delimited JSON-RPC stream. */
internal class DshSdkProtocolParser(private val expectedSessionId: String) {
    private val reasoningByBlock = mutableMapOf<Long, StringBuilder>()
    private val textByBlock = mutableMapOf<Long, StringBuilder>()
    private val streamedTextSinceMessage = StringBuilder()
    private val toolNames = mutableMapOf<String, String>()

    fun parseLine(line: String): DshSdkProtocolEvent {
        val frame = runCatching { JSONObject(line) }.getOrNull()
            ?: return if (line.startsWith("dsh:", ignoreCase = true)) {
                val body = line.removePrefix("dsh:").trim()
                // Boot warnings are informational; dsh continues past them.
                if (body.startsWith("warning:", ignoreCase = true)) {
                    DshSdkProtocolEvent.Ignored
                } else {
                    DshSdkProtocolEvent.Failed(body)
                }
            } else {
                DshSdkProtocolEvent.Ignored
            }

        if (frame.has("id")) {
            val id = frame.optInt("id", -1)
            frame.optJSONObject("error")?.let { error ->
                return DshSdkProtocolEvent.Failed(
                    error.optString("message").ifBlank { "DeepSeek Harness SDK request $id failed" },
                )
            }
            return when (id) {
                1 -> DshSdkProtocolEvent.Initialized
                2 -> DshSdkProtocolEvent.PromptAccepted
                3 -> DshSdkProtocolEvent.ShutdownAcknowledged
                else -> DshSdkProtocolEvent.Ignored
            }
        }

        val params = frame.optJSONObject("params") ?: return DshSdkProtocolEvent.Ignored
        return when (frame.optString("method")) {
            "session.status" -> {
                if (params.optString("sessionId") != expectedSessionId) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Status(params.optString("status") == "running")
            }
            "session.event" -> parseSessionEvent(params)
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseSessionEvent(params: JSONObject): DshSdkProtocolEvent {
        if (params.optString("sessionId") != expectedSessionId) return DshSdkProtocolEvent.Ignored
        val event = params.optJSONObject("event") ?: return DshSdkProtocolEvent.Ignored
        val data = event.optJSONObject("data") ?: return DshSdkProtocolEvent.Ignored
        return when (event.optString("type")) {
            "assistant/chunk" -> parseAssistantChunk(data)
            "assistant/message" -> {
                // dsh 0.2.0 compacts each step's model stream into this event
                // (`data.stream`) instead of delivering live `assistant/chunk`
                // notifications. Replay the compacted deltas so the UI still gets the
                // text, then dedup against the completed message content below.
                val streamEvent = parseAssistantStream(data)
                val content = data.optJSONObject("message")?.optJSONArray("content")
                val text = contentText(content)
                if (streamEvent != null) {
                    // Replay already delivered the text; drop the content copy.
                    if (text.isNotBlank()) streamedTextSinceMessage.clear()
                    return@parseSessionEvent streamEvent
                }
                if (text.isBlank()) {
                    return@parseSessionEvent DshSdkProtocolEvent.Ignored
                }
                if (text == streamedTextSinceMessage.toString()) {
                    // The completed message repeats deltas chunked earlier.
                    streamedTextSinceMessage.clear()
                    return@parseSessionEvent DshSdkProtocolEvent.Ignored
                }
                DshSdkProtocolEvent.AssistantText(text)
            }
            "tool/call" -> {
                val callId = data.optString("callId")
                val rawName = data.optString("name").ifBlank { "Tool" }
                val arguments = data.optString("arguments")
                val displayName = displayToolName(rawName, arguments)
                toolNames[callId] = displayName
                DshSdkProtocolEvent.ToolStarted(callId, displayName, toolDetail(arguments))
            }
            "tool/result" -> {
                val message = data.optJSONObject("message")
                val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
                // dsh 0.2.0 carries the result id on the message itself; 0.1.2 nested
                // it under the first content block.
                val callId = message?.optString("toolCallId").orEmpty()
                    .ifBlank { resultBlock?.optString("toolCallId").orEmpty() }
                val name = toolNames.remove(callId) ?: "Tool"
                val error = data.optJSONObject("error")
                val text = contentText(resultBlock?.optJSONArray("content"))
                val summary = error?.optString("reason").orEmpty()
                    .ifBlank { error?.optString("message").orEmpty() }
                    .ifBlank { text }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(180)
                    .ifBlank { "$name completed" }
                DshSdkProtocolEvent.ToolCompleted(callId, name, summary)
            }
            "turn/end" -> {
                val reason = data.optJSONObject("reason")
                when (reason?.optString("kind")) {
                    "error" -> DshSdkProtocolEvent.Failed(
                        reason.optJSONObject("error")?.optString("message").orEmpty()
                            .ifBlank { "DeepSeek Harness turn failed" },
                    )
                    "blocked" -> DshSdkProtocolEvent.Failed("DeepSeek Harness was blocked from completing the task")
                    "aborted" -> DshSdkProtocolEvent.Failed("DeepSeek Harness cancelled the turn")
                    else -> DshSdkProtocolEvent.TurnCompleted
                }
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseAssistantChunk(data: JSONObject): DshSdkProtocolEvent {
        val chunk = data.optJSONObject("chunk") ?: return DshSdkProtocolEvent.Ignored
        val index = chunk.optInt("index", 0)
        val blockId = data.optInt("turn", 0) * 1_000_000L + data.optInt("step", 0) * 1_000L + index
        return parseStreamChunk(chunk, blockId) ?: DshSdkProtocolEvent.Ignored
    }

    /**
     * Replays the compacted records dsh 0.2.0 embeds in `assistant/message`
     * (`data.stream`): packed text/reasoning runs plus raw `chunk` records.
     * The run is lossless, so the replayed deltas cover the assembled message.
     */
    private fun parseAssistantStream(data: JSONObject): DshSdkProtocolEvent? {
        val stream = data.optJSONArray("stream") ?: return null
        val turn = data.optInt("turn", 0) * 1_000_000L
        val step = data.optInt("step", 0) * 1_000L
        val text = StringBuilder()
        var reasoning: DshSdkProtocolEvent? = null
        for (index in 0 until stream.length()) {
            val record = stream.optJSONObject(index) ?: continue
            val blockId = turn + step + record.optInt("index", 0)
            when (record.optString("type")) {
                "text-chunks", "reasoning-chunks" -> {
                    val texts = record.optJSONArray("texts") ?: continue
                    if (record.optString("type") == "text-chunks") {
                        for (part in 0 until texts.length()) {
                            val delta = texts.optString(part)
                            if (delta.isEmpty()) continue
                            text.append(delta)
                            textByBlock.getOrPut(blockId) { StringBuilder() }.append(delta)
                            streamedTextSinceMessage.append(delta)
                        }
                    } else {
                        val buffer = reasoningByBlock.getOrPut(blockId) { StringBuilder() }
                        val starts = buffer.isEmpty()
                        for (part in 0 until texts.length()) {
                            buffer.append(texts.optString(part))
                        }
                        reasoning = DshSdkProtocolEvent.Reasoning(
                            blockId,
                            buffer.toString(),
                            startsNewBlock = starts,
                            isFinal = true,
                        )
                    }
                }
                "chunk" -> {
                    // Chunk records carry their index inside the chunk itself, not on
                    // the record wrapper. `block-end` keyed by the wrapper index is
                    // turn+step (missing the packed block's index), so it would miss
                    // the accumulated text and re-append the completed message twice.
                    val chunkRecord = record.optJSONObject("chunk") ?: continue
                    parseStreamChunk(chunkRecord, turn + step + chunkRecord.optInt("index", 0))?.let {
                        when (it) {
                            is DshSdkProtocolEvent.AssistantText -> text.append(it.text)
                            is DshSdkProtocolEvent.Reasoning -> reasoning = it
                            else -> Unit
                        }
                    }
                }
            }
        }
        val textEvent = text.toString().takeIf(String::isNotEmpty)?.let { DshSdkProtocolEvent.AssistantText(it) }
        return textEvent ?: reasoning
    }

    private fun parseStreamChunk(chunk: JSONObject?, blockId: Long): DshSdkProtocolEvent? {
        if (chunk == null) return null
        return when (chunk.optString("type")) {
            "text-delta" -> {
                val delta = chunk.optString("text")
                if (delta.isEmpty()) return null
                textByBlock.getOrPut(blockId) { StringBuilder() }.append(delta)
                streamedTextSinceMessage.append(delta)
                DshSdkProtocolEvent.AssistantText(delta)
            }
            "reasoning-delta" -> {
                val buffer = reasoningByBlock.getOrPut(blockId) { StringBuilder() }
                val starts = buffer.isEmpty()
                buffer.append(chunk.optString("text"))
                DshSdkProtocolEvent.Reasoning(blockId, buffer.toString(), starts, isFinal = false)
            }
            "block-end" -> {
                val block = chunk.optJSONObject("block")
                if (block?.optString("type") == "text") {
                    val streamed = textByBlock.remove(blockId)?.toString().orEmpty()
                    val complete = block.optString("text")
                    val missingSuffix = complete.takeIf { it.startsWith(streamed) }?.removePrefix(streamed).orEmpty()
                    if (missingSuffix.isBlank()) return null
                    streamedTextSinceMessage.append(missingSuffix)
                    return DshSdkProtocolEvent.AssistantText(missingSuffix)
                }
                if (block?.optString("type") != "reasoning") return null
                val text = block.optString("text").ifBlank { reasoningByBlock[blockId]?.toString().orEmpty() }
                val starts = blockId !in reasoningByBlock
                reasoningByBlock.remove(blockId)
                if (text.isBlank()) null else DshSdkProtocolEvent.Reasoning(blockId, text, starts, isFinal = true)
            }
            else -> null
        }
    }

    private fun displayToolName(rawName: String, arguments: String): String {
        val operation = runCatching { JSONObject(arguments).optString("command") }.getOrDefault("")
        return when (rawName.lowercase()) {
            "bash", "shell" -> "Bash"
            "read", "view" -> "Read"
            "glob" -> "Glob"
            "grep", "search" -> "Grep"
            "write", "create" -> "Write"
            "edit", "str_replace_editor" -> when (operation.lowercase()) {
                "view" -> "Read"
                "create" -> "Write"
                else -> "Edit"
            }
            else -> rawName.replaceFirstChar { it.uppercase() }
        }
    }

    private fun toolDetail(arguments: String): String {
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
        val detail = parsed?.let { json ->
            listOf("path", "file_path", "command", "pattern", "query")
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf(String::isNotBlank) }
        }.orEmpty()
        return detail.ifBlank { arguments }.replace(Regex("\\s+"), " ").trim().take(240)
            .ifBlank { "Working in the project" }
    }

    private fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        return buildList {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                when (block.optString("type")) {
                    "text" -> block.optString("text").takeIf(String::isNotBlank)?.let(::add)
                    "tool-result" -> contentText(block.optJSONArray("content")).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.joinToString("\n")
    }
}
