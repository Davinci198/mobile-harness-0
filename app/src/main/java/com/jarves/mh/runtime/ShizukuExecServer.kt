package com.jarves.mh.runtime

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Loopback endpoint that lets agents running in the guest reach the Shizuku shell.
 *
 * The guest already has its own Linux userland through proot, but it cannot touch Android
 * APIs. This listener bridges that gap: a small JSON request over loopback runs one command
 * as the `shell` user and returns its output.
 *
 * It is deliberately narrow, because a shell on the host is a real escalation path:
 * - it only binds 127.0.0.1, so only the guest can reach it;
 * - the owner decides whether it even runs (see [enabled]);
 * - the body, the command length and the output are all capped;
 * - the command runs under a timeout and is killed if it overruns.
 */
internal class ShizukuExecServer(
    private val enabled: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

    /** Port the guest script posts to; ephemeral, so it is handed over at startup. */
    val port: Int get() = server.localPort

    fun start(): ShizukuExecServer = apply {
        Thread({ acceptLoop() }, "mh-shizuku-exec").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            Thread(
                {
                    runCatching { handle(socket) }
                    runCatching { socket.close() }
                },
                "mh-shizuku-exec-request",
            ).apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        if (!requestLine.startsWith("POST")) {
            respond(socket, JSONObject().put("ok", false).put("error", "POST required").toString())
            return
        }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
        }
        val length = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_BODY)
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) break
            offset += count
        }
        if (offset == 0) {
            respond(socket, JSONObject().put("ok", false).put("error", "empty body").toString())
            return
        }

        val request = runCatching { JSONObject(String(body, 0, offset, Charsets.UTF_8)) }.getOrNull()
        if (request == null) {
            respond(socket, JSONObject().put("ok", false).put("error", "invalid JSON").toString())
            return
        }

        // The listener is only useful while Shizuku access is granted and the debug tier
        // is the one in use; anything else gets a refusal rather than a shell.
        if (!enabled()) {
            respond(
                socket,
                JSONObject().put("ok", false)
                    .put("error", "Shizuku access is not granted, or the debug access level is not active")
                    .toString(),
            )
            return
        }

        val command = request.optString("command").trim()
        if (command.isEmpty()) {
            respond(socket, JSONObject().put("ok", false).put("error", "missing command").toString())
            return
        }
        if (command.length > MAX_COMMAND) {
            respond(socket, JSONObject().put("ok", false).put("error", "command too long").toString())
            return
        }
        val timeout = (request.optLong("timeoutMs", DEFAULT_TIMEOUT_MS))
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

        scope.launch {
            val result = ShizukuBridge.execute(command, timeout)
            val payload = JSONObject()
                .put("ok", result.ok)
                .put("exitCode", result.exitCode)
                .put("stdout", result.stdout.take(MAX_OUTPUT))
                .put("stderr", result.stderr.take(MAX_OUTPUT))
                .put("timedOut", result.timedOut)
            respond(socket, payload.toString())
        }
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
        private const val MAX_BODY = 128 * 1024
        private const val MAX_COMMAND = 8 * 1024
        private const val MAX_OUTPUT = 64 * 1024
        private const val MIN_TIMEOUT_MS = 1_000L
        private const val MAX_TIMEOUT_MS = 300_000L
        private const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
