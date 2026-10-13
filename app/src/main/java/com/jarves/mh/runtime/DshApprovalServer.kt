package com.jarves.mh.runtime

import com.jarves.mh.tools.device.DeviceToolResult
import com.jarves.mh.tools.device.DeviceTools
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loopback listener carrying dsh's interactive seams to the UI.
 *
 * Three routes share the socket:
 * - `POST /approval` — the guest approval answerer
 *   (`$DSH_HOME/plugins/mh-approval-answerer/index.js`, inserted by
 *   [dshHomePatch]) posts `{callId, toolName, reason}` and keeps the socket
 *   open until [decide] resolves: [DshRuntimeBridge.requestApprovalDecision]
 *   shows the ApprovalCard and suspends until the user answers (or the decision
 *   window expires). The HTTP response closes the waterfall with one of dsh's
 *   outcome strings; anything unexpected fails closed as `unavailable`.
 * - `POST /question` — the guest question answerer posts
 *   `{callId, questions}` and receives the user's answer batch
 *   (`{"answers":[…]}`) once [answer] resolves; failures return
 *   `{"error":…}` so the plugin rejects the waterfall and the tool reports a
 *   clean error to the model.
 * - `POST /device` — the guest UI-tools bridge
 *   (`$DSH_HOME/plugins/mh-ui-tools/index.js`) posts `{tool, nodeId}` and
 *   receives a `{success, …}` result once the tool runs through
 *   [DeviceTools]'s permission gate. `UiReadTree`/`UiStatus` are
 *   SENSITIVE_READ, `UiClick` is DEVICE_CONTROL, so the same risk-graded
 *   approval cards the workflow path uses also guard the chat agent.
 */
internal class DshApprovalServer(
    private val decide: suspend (callId: String, toolName: String, reason: String?) -> String,
    private val answer: suspend (callId: String?, questions: JSONArray) -> String,
    private val deviceTools: DeviceTools? = null,
    /**
     * Raises the ApprovalCard for a UI tool the gate held at ASK and returns
     * the user's decision (`allowed-once` / `rejected`). Lets the chat agent's
     * UiReadTree/UiClick/UiStatus share the same risk-graded approval cards
     * the workflow path uses, then re-dispatches once the user allows. Fails
     * closed as `unavailable` when the host did not wire one.
     */
    private val decideDeviceAsk: suspend (tool: String, nodeId: String?) -> String =
        { _, _ -> "unavailable" },
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** Loopback port handed to the guest as `MH_APPROVAL_PORT`. */
    val port: Int
        get() = server.localPort

    fun start(): DshApprovalServer = apply {
        Thread({ acceptLoop() }, "mh-dsh-approvals").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            Thread(
                {
                    runCatching { handle(socket) }
                    runCatching { socket.close() }
                },
                "mh-dsh-approval-request",
            ).apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = REQUEST_READ_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return respond(socket, unavailable())
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return respond(socket, unavailable())
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(minOf(length, MAX_APPROVAL_BODY))
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) break
            offset += count
        }
        if (!requestLine.startsWith("POST") || offset == 0) return respond(socket, unavailable())
        val payload = runCatching { JSONObject(String(body, 0, offset, Charsets.UTF_8)) }
            .getOrElse { return respond(socket, unavailable()) }
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        if (path == "/question") {
            val questions = payload.optJSONArray("questions")
            if (questions == null || questions.length() == 0) return respond(socket, questionError())
            val body2 = runCatching {
                runBlocking { answer(payload.stringOrNull("callId"), questions) }
            }.getOrElse { questionError() }
            return respond(socket, body2)
        }
        if (path == "/device") {
            return respond(socket, runDevice(payload))
        }
        val outcome = runCatching {
            val callId = payload.stringOrNull("callId") ?: UUID.randomUUID().toString()
            val toolName = payload.stringOrNull("toolName") ?: "tool"
            runBlocking { decide(callId, toolName, payload.stringOrNull("reason")) }
        }.getOrElse { "unavailable" }
        respond(socket, JSONObject().put("outcome", outcome).toString())
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }

    /**
     * Runs one UI tool through [DeviceTools]'s gate and maps the result onto a
     * `{success, …}` body the guest bridge can hand back to the model. When the
     * gate holds a call at ASK (e.g. UiClick under an ASK policy), the route
     * raises the same ApprovalCard the workflow path uses via [decideDeviceAsk]
     * and re-dispatches once the user allows.
     */
    private fun runDevice(payload: JSONObject): String {
        val tools = deviceTools
            ?: return deviceError("Device tools are unavailable in this runtime")
        val tool = payload.stringOrNull("tool") ?: return deviceError("Missing tool name")
        val nodeId = payload.stringOrNull("nodeId")
        val first = dispatchDevice(tools, tool, nodeId)
        val needsApproval = runCatching {
            JSONObject(first).optBoolean("needsApproval", false)
        }.getOrDefault(false)
        if (!needsApproval) return first
        val decision = runCatching {
            runBlocking { decideDeviceAsk(tool, nodeId) }
        }.getOrDefault("unavailable")
        if (decision != "allowed-once") return deviceError("Approval $decision for $tool")
        // Arm the gate's one-shot grant before re-dispatching, mirroring
        // WorkflowReplayExecutor: the gate would otherwise resolve to ASK again
        // and loop straight back into another ApprovalCard.
        tools.permissionGate.allowOnce(tool)
        return try {
            dispatchDevice(tools, tool, nodeId)
        } finally {
            tools.permissionGate.clearAllowOnce(tool)
        }
    }

    private fun dispatchDevice(tools: DeviceTools, tool: String, nodeId: String?): String {
        val result = when (tool) {
            "UiReadTree" -> tools.readUiTree()
            "UiClick" -> tools.clickUiNode(nodeId.orEmpty())
            "UiStatus" -> tools.accessibilityStatus()
            else -> return deviceError("Unknown device tool: $tool")
        }
        return when (result) {
            is DeviceToolResult.UiTreeRead ->
                deviceOk(JSONObject().put("nodes", result.nodes))
            is DeviceToolResult.UiNodeClicked ->
                deviceOk(JSONObject().put("nodeId", result.nodeId))
            is DeviceToolResult.UiStatus ->
                deviceOk(JSONObject().put("enabled", result.enabled))
            is DeviceToolResult.Error -> when (result.code) {
                com.jarves.mh.tools.device.DeviceErrorCode.APPROVAL_REQUIRED ->
                    JSONObject()
                        .put("success", false)
                        .put("error", "Approval required for $tool")
                        .put("needsApproval", true)
                        .toString()
                com.jarves.mh.tools.device.DeviceErrorCode.PERMISSION_REQUIRED ->
                    deviceError(result.message)
                com.jarves.mh.tools.device.DeviceErrorCode.PERMISSION_DENIED ->
                    deviceError(result.message)
                else -> deviceError(result.message)
            }
            else -> deviceError("Unexpected device tool result")
        }
    }

    private fun deviceOk(data: JSONObject): String =
        JSONObject().put("success", true).put("data", data).toString()

    private fun deviceError(message: String): String =
        JSONObject().put("success", false).put("error", message).toString()

    private fun respond(socket: Socket, body: String) {
        runCatching {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val output = socket.getOutputStream()
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        }
    }

    private fun unavailable(): String = JSONObject().put("outcome", "unavailable").toString()

    private fun questionError(): String = JSONObject().put("error", "unavailable").toString()

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
            bytes += value.toByte()
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }

    companion object {
        private const val MAX_APPROVAL_BODY = 1 shl 16
        private const val REQUEST_READ_TIMEOUT_MS = 15_000
    }
}
