package com.jarves.mh.runtime

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Loopback listener carrying dsh's Cordis `approval/request` seam to the UI.
 *
 * The guest answerer plugin (`$DSH_HOME/plugins/mh-approval-answerer/index.js`,
 * inserted by [dshHomePatch]) POSTs `{callId, toolName, reason}` and keeps the
 * socket open until [decide] resolves: [DshRuntimeBridge.requestApprovalDecision]
 * shows the ApprovalCard and suspends until the user answers (or the decision
 * window expires). The HTTP response closes the waterfall with one of dsh's
 * outcome strings; anything unexpected fails closed as `unavailable`.
 */
internal class DshApprovalServer(
    private val decide: suspend (callId: String, toolName: String, reason: String?) -> String,
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
        val outcome = runCatching {
            val payload = JSONObject(String(body, 0, offset, Charsets.UTF_8))
            val callId = payload.stringOrNull("callId") ?: UUID.randomUUID().toString()
            val toolName = payload.stringOrNull("toolName") ?: "tool"
            runBlocking { decide(callId, toolName, payload.stringOrNull("reason")) }
        }.getOrElse { "unavailable" }
        respond(socket, JSONObject().put("outcome", outcome).toString())
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }

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
