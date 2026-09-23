package com.jarves.mh.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class McpStdioClient(
    private val config: McpStdioConfig,
    private val launcher: McpProcessLauncher,
) {
    private enum class State { NEW, CONNECTING, READY, FAILED, CLOSED }

    private val state = AtomicReference(State.NEW)
    private val lifecycle = Mutex()
    private val operationMutex = Mutex()
    private val writeMutex = Mutex()
    private val callSlots = Semaphore(McpLimits.MAX_CONCURRENT_CALLS)
    private val nextRequestId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: McpProcessTransport? = null
    private var readerJob: Job? = null

    @Volatile
    private var serverInfo: McpServerInfo? = null

    suspend fun connect(): McpServerInfo = lifecycle.withLock {
        config.validate()
        when (state.get()) {
            State.READY -> return@withLock serverInfo ?: throw McpException.Startup("MCP server info is unavailable")
            State.CONNECTING -> throw McpException.Startup("MCP client is already connecting")
            State.CLOSED, State.FAILED -> throw McpException.Startup("MCP client is not reusable")
            State.NEW -> Unit
        }
        state.set(State.CONNECTING)
        val launched = try {
            launcher.launch(config)
        } catch (error: Throwable) {
            state.set(State.FAILED)
            throw McpException.Startup("MCP process launch failed: ${error.message ?: "unknown error"}")
        }
        transport = launched
        readerJob = scope.launch { readLoop(launched) }
        try {
            val result = request(
                "initialize",
                JSONObject()
                    .put("protocolVersion", config.protocolVersion)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "mobile-harness").put("version", "1")),
                config.connectTimeoutMillis,
            )
            val protocol = result.opt("protocolVersion")
            if (protocol !is String || protocol != config.protocolVersion) {
                throw McpException.Protocol("MCP protocol version mismatch: ${result.opt("protocolVersion")}")
            }
            val server = result.optJSONObject("serverInfo")
                ?: throw McpException.Protocol("MCP initialize has no serverInfo")
            if (server.opt("name") !is String || server.optString("name").isBlank()) {
                throw McpException.Protocol("MCP server name is invalid")
            }
            if (server.opt("version") !is String || server.optString("version").isBlank()) {
                throw McpException.Protocol("MCP server version is invalid")
            }
            notify("notifications/initialized", null)
            val info = McpServerInfo(
                name = result.optJSONObject("serverInfo")?.optString("name").orEmpty(),
                version = result.optJSONObject("serverInfo")?.optString("version").orEmpty(),
                protocolVersion = result.optString("protocolVersion", config.protocolVersion),
                capabilities = result.optJSONObject("capabilities") ?: JSONObject(),
            )
            serverInfo = info
            if (!state.compareAndSet(State.CONNECTING, State.READY)) {
                throw McpException.ProcessExited("MCP process exited during initialization")
            }
            info
        } catch (error: CancellationException) {
            state.set(State.FAILED)
            closeTransport(launched)
            readerJob?.cancel()
            readerJob = null
            failPending(error)
            throw error
        } catch (error: Throwable) {
            state.set(State.FAILED)
            closeTransport(launched)
            readerJob?.cancel()
            readerJob = null
            failPending(error)
            if (error is McpException) throw error
            throw McpException.Startup("MCP initialization failed: ${error.message ?: "unknown error"}")
        }
    }

    suspend fun listTools(): List<McpTool> {
        requireReady()
        val tools = linkedMapOf<String, McpTool>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(McpLimits.MAX_LIST_PAGES) {
            val params = JSONObject().apply { if (cursor != null) put("cursor", cursor) }
            val result = request("tools/list", params, config.requestTimeoutMillis)
            val array = result.optJSONArray("tools") ?: throw McpException.Protocol("MCP tools/list has no tools array")
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: throw McpException.Protocol("MCP tool entry is invalid")
                val name = item.optString("name")
                if (name.isBlank()) throw McpException.Protocol("MCP tool name is empty")
                if (tools.size >= McpLimits.MAX_TOOLS) throw McpException.Protocol("MCP server exposes too many tools")
                if (tools.containsKey(name)) throw McpException.Protocol("MCP server returned duplicate tool: $name")
                val schema = item.optJSONObject("inputSchema")
                    ?: throw McpException.Protocol("MCP tool inputSchema is missing: $name")
                tools[name] = McpTool(
                    name = name,
                    description = item.optString("description"),
                    inputSchema = schema,
                )
            }
            if (result.has("nextCursor")) {
                if (result.opt("nextCursor") !is String) throw McpException.Protocol("MCP nextCursor is invalid")
                val next = result.getString("nextCursor")
                if (next.isBlank() || !seenCursors.add(next)) {
                    throw McpException.Protocol("MCP tools/list returned a repeated cursor")
                }
                cursor = next
                continue
            }
            return tools.values.toList()
        }
        throw McpException.Protocol("MCP tools/list exceeded page limit")
    }

    suspend fun callTool(name: String, arguments: JSONObject): McpToolResult {
        requireReady()
        if (name.isBlank()) throw McpException.Protocol("MCP tool name is empty")
        callSlots.acquire()
        return try {
            val result = request(
                "tools/call",
                JSONObject().put("name", name).put("arguments", arguments),
                config.requestTimeoutMillis,
            )
            val content = result.optJSONArray("content")
                ?: throw McpException.Protocol("MCP tools/call has no content array")
            McpToolResult(
                content = content.toObjects(),
                structuredContent = result.optJSONObject("structuredContent"),
                isError = result.optBoolean("isError", false),
            )
        } finally {
            callSlots.release()
        }
    }

    suspend fun cancel(reason: String) {
        lifecycle.withLock {
            if (state.get() == State.CLOSED) return
            operationMutex.withLock {
                pending.keys.toList().forEach { requestId ->
                    pending.remove(requestId)?.completeExceptionally(McpException.Cancelled(requestId))
                    runCatching {
                        withContext(NonCancellable) {
                            notify("notifications/cancelled", JSONObject().put("requestId", requestId).put("reason", reason.take(240)))
                        }
                    }
                }
            }
        }
    }

    suspend fun close() {
        lifecycle.withLock {
            if (state.get() == State.CLOSED) return
            operationMutex.withLock {
                state.set(State.CLOSED)
                val error = McpException.ProcessExited("MCP client closed")
                pending.keys.toList().forEach { pending.remove(it)?.completeExceptionally(error) }
                closeTransport(transport)
            }
            readerJob?.cancel()
            readerJob = null
            scope.cancel()
        }
    }

    private suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long): JSONObject {
        val id = nextRequestId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        try {
            operationMutex.withLock {
                val currentState = state.get()
                if (currentState != State.CONNECTING && currentState != State.READY) {
                    throw McpException.Transport("MCP client is not connected")
                }
                val active = transport ?: throw McpException.Transport("MCP transport is unavailable")
                if (!active.isAlive()) throw McpException.ProcessExited("MCP process is not running: ${boundedStderr(active)}")
                if (pending.putIfAbsent(id, deferred) != null) throw McpException.Protocol("Duplicate MCP request ID: $id")
                withContext(Dispatchers.IO) { writeFrame(active, McpJsonRpc.request(id, method, params)) }
            }
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            pending.remove(id)
            runCatching {
                withContext(NonCancellable) {
                    notify("notifications/cancelled", JSONObject().put("requestId", id).put("reason", "MCP request timed out"))
                }
            }
            throw McpException.Timeout(id)
        } catch (error: CancellationException) {
            pending.remove(id)
            runCatching {
                withContext(NonCancellable) {
                    notify("notifications/cancelled", JSONObject().put("requestId", id).put("reason", "MCP request cancelled"))
                }
            }
            throw error
        } catch (error: Throwable) {
            pending.remove(id)
            throw error
        }
    }

    private suspend fun notify(method: String, params: JSONObject?) {
        val active = transport ?: return
        withContext(Dispatchers.IO) { writeFrame(active, McpJsonRpc.notification(method, params)) }
    }

    private suspend fun writeFrame(active: McpProcessTransport, frame: String) {
        if (frame.toByteArray(Charsets.UTF_8).size > McpLimits.MAX_FRAME_BYTES) {
            throw McpException.Protocol("MCP outbound frame is too large")
        }
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                active.output.write(frame.toByteArray(Charsets.UTF_8))
                active.output.write('\n'.code)
                active.output.flush()
            }
        }
    }

    private suspend fun readLoop(active: McpProcessTransport) {
        try {
            while (true) {
                val line = readBoundedLine(active.input) ?: break
                if (line.isBlank()) throw McpException.Protocol("MCP stdout contains a blank frame")
                val parsed = try {
                    val tokenizer = JSONTokener(line)
                    val value = tokenizer.nextValue()
                    if (tokenizer.nextClean().code != 0) throw McpException.Protocol("MCP frame has trailing JSON")
                    value
                } catch (error: McpException) {
                    throw error
                } catch (error: Throwable) {
                    throw McpException.Protocol("Invalid MCP JSON frame: ${error.message ?: "malformed JSON"}")
                }
                val envelopes = when (parsed) {
                    is JSONObject -> listOf(parsed)
                    is org.json.JSONArray -> (0 until parsed.length()).map { index ->
                        parsed.optJSONObject(index) ?: throw McpException.Protocol("MCP batch entry is invalid")
                    }
                    else -> throw McpException.Protocol("MCP frame is not an object or batch")
                }
                if (envelopes.isEmpty()) throw McpException.Protocol("MCP batch is empty")
                envelopes.forEach { processEnvelope(active, it) }
            }
            throw McpException.ProcessExited("MCP process stdout closed: ${boundedStderr(active)}")
        } catch (error: Throwable) {
            state.compareAndSet(State.CONNECTING, State.FAILED)
            state.compareAndSet(State.READY, State.FAILED)
            failPending(error)
            if (state.get() != State.CLOSED) closeTransport(active)
        }
    }

    private suspend fun processEnvelope(active: McpProcessTransport, envelope: JSONObject) {
        if (!envelope.has("id")) return
        val method = envelope.optString("method")
        if (method.isNotBlank()) {
            val serverId = envelope.opt("id")
            val validServerId = (serverId is Number && McpJsonRpc.id(serverId) != null) ||
                (serverId is String && serverId.isNotBlank())
            if (!validServerId) throw McpException.Protocol("MCP server request has invalid id")
            if (method == "ping") {
                writeFrame(active, JSONObject().put("jsonrpc", "2.0").put("id", serverId).put("result", JSONObject()).toString())
            } else {
                writeFrame(active, JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", serverId)
                    .put("error", JSONObject().put("code", -32601).put("message", "Method not found"))
                    .toString())
            }
            return
        }
        val response = try {
            McpJsonRpc.response(envelope.toString())
        } catch (error: McpException.JsonRpc) {
            pending.remove(McpJsonRpc.id(envelope.opt("id")))?.completeExceptionally(error)
            return
        }
        pending.remove(response.first)?.complete(response.second)
    }

    private fun readBoundedLine(input: java.io.InputStream): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (output.size() > 0) throw McpException.Protocol("MCP frame is not newline terminated")
                return null
            }
            if (value == '\n'.code) {
                if (output.size() == 0) throw McpException.Protocol("MCP stdout contains a blank frame")
                return output.toString(Charsets.UTF_8.name()).removeSuffix("\r")
            }
            if (output.size() >= McpLimits.MAX_FRAME_BYTES) throw McpException.Protocol("MCP inbound frame is too large")
            output.write(value)
        }
    }

    private fun failPending(error: Throwable) {
        pending.keys.toList().forEach { pending.remove(it)?.completeExceptionally(error) }
    }

    private fun closeTransport(active: McpProcessTransport?) {
        if (active == null) return
        runCatching { active.output.close() }
        runCatching { if (active.isAlive()) active.terminate() }
        runCatching { if (active.isAlive()) active.kill() }
        runCatching { active.close() }
    }

    private fun boundedStderr(active: McpProcessTransport): String = runCatching { active.stderr() }.getOrDefault("").takeLast(2_048)

    private fun requireReady() {
        if (state.get() != State.READY) throw McpException.Transport("MCP client is not connected")
    }

    private fun JSONArray.toObjects(): List<JSONObject> = (0 until length()).map { index ->
        optJSONObject(index) ?: throw McpException.Protocol("MCP tool content entry is invalid")
    }
}
