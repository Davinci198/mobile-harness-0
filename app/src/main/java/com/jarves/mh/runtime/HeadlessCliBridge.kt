package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [RuntimeBridge] driving a plain headless CLI coding agent inside PRoot.
 * The agent runs once per turn with the whole conversation packed into the
 * prompt (the same contract as [DshRuntimeBridge]): stdout is captured into a
 * log file that PRoot merges from both descendants, and a single JSONL command
 * flag (`--format json`, `--format stream-json`) produces newline-delimited
 * event objects that we map onto [RuntimeEvent].
 *
 * Subclasses only supply the executable path, the exact CLI command, the
 * provider environment, and the JSONL parser — the session lifecycle, file
 * change checkpoints and foreground service are shared.
 */
internal abstract class HeadlessCliBridge(
    protected val context: Context,
    protected val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    protected val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    @Volatile private var lastThinkingUpdateAt: Long = 0L

    /** Agent kind whose installed binary we launch in the guest. */
    protected abstract val kind: AgentKind

    /** Guest path of the headless CLI binary, e.g. /root/.opencode/bin/opencode. */
    protected abstract val guestExecutable: String

    /** Full guest command for one turn. [prompt] is the context-packed prompt string. */
    protected abstract fun commandFor(
        prompt: String,
        provider: ProviderProfile,
        secret: String?,
        guestWorkspacePath: String,
    ): List<String>

    /** Process environment carrying the provider credentials for the guest. */
    protected abstract fun environmentFor(provider: ProviderProfile, secret: String?): Map<String, String>

    /**
     * Maps one JSONL line to bridge events. Returns [CliParsed.IGNORED] for
     * non-event lines, otherwise the events plus optional completion/failure.
     */
    protected abstract fun parseJsonlLine(line: String, sessionId: String): CliParsed

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
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
        pushForegroundProgress("Starting ${kind.title}…")

        val secret = secretFor(provider).orEmpty()
        if (provider.kind != ProviderKind.FREE && secret.isBlank()) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(sessionId, "No API key is saved for ${provider.kind.title}."),
            )
            return@withContext sessionId
        }
        if (provider.kind == ProviderKind.CLAUDE) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "Claude subscription login is not supported by ${kind.title}. Pick a key-based provider in Settings.",
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
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(kind)) {
                "${kind.title} is not installed. Open Settings → Coding agent to install it."
            }
            // Leftovers from a previous killed session (orphaned guest children
            // like `opencode serve`) can block the new run at startup.
            runCatching { installer.killGuestOrphans() }
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)
            val command = commandFor(contextPrompt, provider, secret, guestWorkspacePath)
            Log.d("HeadlessBridge", "${kind.title} command: $command")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                environmentFor(provider, secret),
                command,
                guestWorkspacePath = guestWorkspacePath,
                emulateHardLinks = false,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()
            val result = runCliSession(process, sessionId)
            val exit = process.waitFor()
            Log.d("HeadlessBridge", "${kind.title} process exited with code $exit")
            if (exit != 0) runCatching { installer.killGuestOrphans() }
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                acceptLastChanges(projectId)
            }
            if (!userStopRequested && result.failed == null) {
                emitCompletedOnce(sessionId)
                finishForegroundRuntime(
                    completed = true,
                    projectName = projectSlug,
                    detail = "${kind.title} finished the task in $projectSlug.",
                )
            } else {
                if (userStopRequested) throw CliSessionException("Stopped by user")
                error(
                    result.failed ?: if (exit != 0) {
                        "${kind.title} was terminated early (exit $exit). The phone suspends guest processes when the app leaves the screen; keep mobile-harness in the foreground and retry."
                    } else {
                        "${kind.title} stopped with exit code $exit"
                    },
                )
            }
        }.onFailure { error ->
            Log.e("HeadlessBridge", "Session failed", error)
            val message = friendlyError(error)
            emitFailureOnce(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(completed = false, projectName = projectSlug, detail = message)
            }
        }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    private suspend fun runCliSession(process: Process, sessionId: String): CliRunResult {
        val nativeProcess = process as? NativeSpawnProcess
            ?: error("Unsupported Android runtime process")
        var outputOffset = 0L
        val pendingOutput = StringBuilder()
        var lastBlockId = 0L
        var failed: String? = null
        while (process.isAlive || nativeProcess.outputFile.length() > outputOffset) {
            val available = nativeProcess.outputFile.length() - outputOffset
            if (available <= 0) {
                delay(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                file.seek(outputOffset)
                file.read(bytes)
            }
            if (count <= 0) continue
            outputOffset += count
            pendingOutput.append(bytes.decodeToString(0, count))
            var newline = pendingOutput.indexOf("\n")
            while (newline >= 0) {
                val line = pendingOutput.substring(0, newline).trimEnd('\r')
                pendingOutput.delete(0, newline + 1)
                if (line.isNotBlank()) {
                    var terminalSeen = false
                    when (val parsed = parseJsonlLine(line, sessionId)) {
                        is CliParsed.Events -> {
                            failed = parsed.failed ?: failed
                            terminalSeen = parsed.terminal
                            parsed.events.forEach { event ->
                                when (event) {
                                    is RuntimeEvent.ReasoningSummary -> emitReasoningSummary(
                                        sessionId = sessionId,
                                        text = event.summary,
                                        blockId = if (event.startsNewBlock) ++lastBlockId else lastBlockId.takeIf { it > 0L } ?: 1L,
                                        startsNewBlock = event.startsNewBlock,
                                        isFinal = false,
                                    )
                                    else -> eventBus.emit(event)
                                }
                            }
                        }
                        CliParsed.IGNORED -> Unit
                    }
                    if (failed != null || terminalSeen) {
                        // Either a reported failure or a final result envelope:
                        // the agent already answered, so a still-live process only
                        // means a stuck shutdown path (e.g. hermes crashing in its
                        // cleanup). Tear it down; the drain loop above keeps
                        // reading whatever is left in the capture file.
                        process.destroy()
                    }
                }
                newline = pendingOutput.indexOf("\n")
            }
        }
        return CliRunResult(failed = failed)
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // Headless one-shot runs expose no approval channel; nothing is ever requested.
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            // A dead PRoot wrapper leaves guest children (bun/node/python) alive
            // and reparented to init; a stale `opencode serve` then blocks every
            // later headless run at startup. Sweep them with the next session.
            runCatching { installer.killGuestOrphans() }
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory || !File(checkpoint, "changes.json").isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) return@withContext false
        paths.forEach { path ->
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                target.delete()
            }
        }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else if (target.isFile) {
            target.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "your project",
                detail = "${kind.title} finished the task.",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(completed = false, projectName = activeProjectSlug ?: "your project", detail = reason)
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is CliSessionException -> message
            message.contains("connection error", true) ||
                message.contains("connection reset", true) ||
                message.contains("getaddrinfo", true) ||
                message.contains("etimeout", true) ||
                message.contains("temporary failure in name resolution", true) ||
                message.contains("network is unreachable", true) ->
                "Network inside the runtime stopped responding (the phone suspends guest sockets when the app leaves the screen). Keep mobile-harness in the foreground and try again."
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
                "No API key reached ${kind.title}. Re-save the provider key in Settings."
            message.contains("not installed", true) -> message.take(300)
            message.isBlank() -> "${kind.title} could not start."
            else -> message.take(500)
        }
    }

    protected fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
    ): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                    !msg.text.startsWith("Failed to") &&
                    !msg.text.startsWith("Error:") &&
                    !msg.text.contains("API Error")
            }
            .dropLast(1)

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
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
        sb.appendLine("</project_workspace>")
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

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        blockId: Long,
        startsNewBlock: Boolean,
        isFinal: Boolean,
    ) {
        val summary = text.replace(Regex("\\s+"), " ").trim().take(2_000)
        if (summary.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastThinkingUpdateAt >= 400 || startsNewBlock || isFinal) {
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

    private fun startForegroundRuntime(projectName: String) {
        androidx.core.content.ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
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
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w("HeadlessBridge", "Could not post task result notification", error)
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private class CliSessionException(message: String) : IllegalStateException(message)

    companion object {
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
    }
}

private data class CliRunResult(val failed: String?)

/** Result of classifying a single JSONL line; [IGNORED] skips the line. */
internal sealed interface CliParsed {
    data object IGNORED : CliParsed
    data class Events(
        val events: List<RuntimeEvent> = emptyList(),
        val failed: String? = null,
        /** The CLI emitted its final envelope; the process can be torn down. */
        val terminal: Boolean = false,
    ) : CliParsed
}

/**
 * OpenCode headless contract (opencode v2): `opencode run --format json`
 * prints newline-delimited JSON. Text/reasoning/tool parts carry
 * `part.type` + `part.text`, an `error` object ends the run, and completion is
 * signalled by the `message.complete` / `session.idle` event types.
 */
internal object OpenCodeJsonlParser {
    fun parseLine(line: String, sessionId: String): CliParsed {
        val event = runCatching { JSONObject(line) }.getOrNull() ?: return CliParsed.IGNORED
        val type = event.optString("type")
        when (type) {
            // Final envelopes of a one-shot run. The process can outlive them
            // (Bun shutdown hangs), so they terminate the session immediately.
            "message.complete", "session.end" -> return CliParsed.Events(terminal = true)
            // Can fire between turns; not treated as the end of the run.
            "session.idle" -> return CliParsed.Events(emptyList())
        }
        if (type == "error") {
            val error = event.optJSONObject("error")
            val message = error?.optString("message") ?: event.optString("message")
            return CliParsed.Events(
                failed = listOf(
                    message,
                    error?.optString("type"),
                    event.optString("type"),
                ).firstOrNull { !it.isNullOrBlank() } ?: "OpenCode reported an error",
            )
        }
        val part = event.optJSONObject("part") ?: return CliParsed.IGNORED
        val partType = part.optString("type")
        return when (partType) {
            "text" -> CliParsed.Events(
                listOf(RuntimeEvent.AssistantDelta(sessionId, part.optString("text"))),
            )
            "reasoning" -> CliParsed.Events(
                listOf(
                    RuntimeEvent.ReasoningSummary(
                        sessionId = sessionId,
                        summary = part.optString("text"),
                        blockId = 0L,
                        startsNewBlock = true,
                    ),
                ),
            )
            "tool" -> {
                val tool = part.optJSONObject("tool")
                val toolState = part.optString("state")
                val name = tool?.optString("name")
                    ?: part.optString("title")
                    ?: part.optString("name")
                if (name.isBlank()) CliParsed.IGNORED
                else if (toolState == "completed") CliParsed.Events(
                    listOf(
                        RuntimeEvent.ToolCompleted(
                            sessionId,
                            name,
                            tool?.optString("title") ?: name,
                        ),
                    ),
                )
                else CliParsed.Events(
                    listOf(RuntimeEvent.ToolStarted(sessionId, name, tool?.optString("title").orEmpty())),
                )
            }
            else -> CliParsed.IGNORED
        }
    }
}

/**
 * Hermes headless contract: `hermes chat --format stream-json -q "<prompt>"`
 * prints newline-delimited events shaped like `{type, part, error, done}` with
 * `part.text` deltas, optional reasoning text, `type:tool_call` markers and a
 * final done/completed marker.
 */
internal object HermesJsonlParser {
    fun parseLine(line: String, sessionId: String): CliParsed {
        val event = runCatching { JSONObject(line) }.getOrNull() ?: return CliParsed.IGNORED
        val type = event.optString("type")
        if (type.contains("error", true)) {
            val message = event.optString("message").ifBlank {
                event.optJSONObject("error")?.optString("message").orEmpty()
            }
            return CliParsed.Events(failed = message.ifBlank { "Hermes reported an error" })
        }
        if (type.contains("completed", true) || type.contains("done", true) || event.optBoolean("done")) {
            return CliParsed.Events(emptyList())
        }
        if (type == "result") {
            // The CLI keeps running after the result envelope (its cleanup can
            // crash on optional deps like nemo_relay), so the envelope itself is
            // terminal: a non-zero exit surfaces as a failure and a zero exit
            // completes the run — runCliSession then tears the process down
            // instead of waiting forever for an exit that never comes.
            val exitCode = event.optInt("exit_code", 0)
            if (exitCode != 0) {
                val reason = event.optString("error").ifBlank {
                    "Hermes was interrupted before the provider answered (exit $exitCode). " +
                        "This usually means the phone suspended guest networking — keep the app in the foreground and retry."
                }
                return CliParsed.Events(failed = reason, terminal = true)
            }
            return CliParsed.Events(terminal = true)
        }
        val part = event.optJSONObject("part")
        val partType = part?.optString("type").orEmpty()
        val text = when {
            partType == "text" -> part.optString("text")
            partType == "reasoning" -> part.optString("text").takeIf { it.isNotBlank() }
            else -> null
        }
        if (partType == "reasoning" && !text.isNullOrBlank()) {
            return CliParsed.Events(
                listOf(
                    RuntimeEvent.ReasoningSummary(
                        sessionId = sessionId,
                        summary = text,
                        blockId = 0L,
                        startsNewBlock = true,
                    ),
                ),
            )
        }
        if (partType == "text") {
            val delta = text ?: part.optString("content").ifBlank { part.optString("one_of").ifBlank { "" } }
            if (delta.isBlank()) return CliParsed.IGNORED
            return CliParsed.Events(listOf(RuntimeEvent.AssistantDelta(sessionId, delta)))
        }
        if (type == "tool_call" || partType == "tool_call" || partType == "tool") {
            val name = part?.optString("title")
                ?: part?.optString("name")
                ?: event.optString("tool_name")
            if (!name.isNullOrBlank()) {
                return CliParsed.Events(
                    listOf(RuntimeEvent.ToolStarted(sessionId, name, part?.optString("title").orEmpty())),
                )
            }
        }
        return CliParsed.IGNORED
    }
}