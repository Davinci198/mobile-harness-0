package com.jarves.mh.runtime

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Loopback listener for Hermes `hooks.outbound` deliveries.
 *
 * A warm interactive `hermes chat` session reports its progress through the
 * webhook pipeline (`_prepare_agent_startup` registers `hooks.outbound` and
 * every `invoke_hook` site POSTs fire-and-forget JSON): interactive stdout is a
 * rendered TUI with no parseable events, while `--format stream-json` forces
 * single-query mode that exits after one turn. The listener binds an ephemeral
 * port, answers 200 immediately and hands the raw payload to [onPayload].
 */
internal class HermesHookServer(
    private val onPayload: (JSONObject) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** Endpoint the guest config must point at: app loopback, fresh port per listener. */
    val url: String = "http://127.0.0.1:${server.localPort}/hook"

    fun start(): HermesHookServer = apply {
        Thread({ acceptLoop() }, "mh-hermes-hooks").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            Thread(
                {
                    runCatching { handle(socket) }
                    runCatching { socket.close() }
                },
                "mh-hermes-hook-request",
            ).apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(minOf(length, MAX_HOOK_BODY))
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) break
            offset += count
        }
        respond(socket, """{"ok":true}""")
        if (!requestLine.startsWith("POST") || offset == 0) return
        runCatching { JSONObject(String(body, 0, offset, Charsets.UTF_8)) }
            .onSuccess { payload -> runCatching { onPayload(payload) } }
    }

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
        private const val MAX_HOOK_BODY = 1 shl 20
    }
}
