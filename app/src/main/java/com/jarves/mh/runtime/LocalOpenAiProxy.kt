package com.jarves.mh.runtime

import android.util.Log
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** True when this profile's OpenAI-compatible traffic can ride the loopback proxy. */
internal fun ProviderProfile.routesThroughOpenAiProxy(): Boolean = when (kind) {
    ProviderKind.NVIDIA_NIM -> true
    ProviderKind.CUSTOM -> dshApi.ifBlank { "anthropic-messages" } == "openai-completions"
    else -> false
}

/**
 * Loopback pass-through reverse proxy for OpenAI-compatible providers.
 *
 * Guest CLIs (OpenCode, Hermes) point their OpenAI base URL at this server;
 * every request is forwarded to the real provider with the API key injected
 * from the app process. The guest only ever talks plain HTTP to 127.0.0.1,
 * so guest networking dying when the app loses focus can no longer break the
 * provider call — the outbound TLS connection lives in the app process, the
 * same way [LocalFormatGateway] protects Claude Code.
 *
 * The proxy is path-transparent: the guest base URL mirrors the real
 * provider's path (e.g. `http://127.0.0.1:PORT/v1`), and requests are
 * re-targeted at the provider origin, preserving path and query.
 */
internal class LocalOpenAiProxy(
    private val profile: ProviderProfile,
    private val apiKey: String,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val target = URI(profile.resolvedBaseUrl)
    private val targetPath: String = target.path.orEmpty().trimEnd('/')

    /** Base URL the guest must use: loopback origin + the provider's own path. */
    val url: String = "http://127.0.0.1:${server.localPort}$targetPath"

    fun start(): LocalOpenAiProxy = apply {
        Thread({ acceptLoop() }, "mh-openai-proxy").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                Thread({ socket.use(::handle) }, "mh-openai-proxy-request").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        if (headers["expect"].equals("100-continue", ignoreCase = true)) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            output.flush()
        }
        val body = readBody(input, headers)
        runCatching {
            val (connection, code) = forward(method, rawTarget, headers, body)
            try {
                writeUpstreamResponse(output, connection, code)
            } finally {
                connection.disconnect()
            }
        }.onFailure { error ->
            Log.w("OpenAiProxy", "Upstream call failed for ${profile.kind}: ${error.message}")
            writeJson(output, 502, openAiError(error.message ?: "Provider request failed"))
        }
    }

    private fun forward(
        method: String,
        rawTarget: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): Pair<HttpURLConnection, Int> {
        val origin = "${target.scheme}://${target.authority}"
        val connection = URL(origin + rawTarget).openConnection() as HttpURLConnection
        connection.requestMethod = if (method in SUPPORTED_METHODS) method else "GET"
        connection.connectTimeout = 20_000
        connection.readTimeout = 180_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        for ((name, value) in headers) {
            when (name) {
                "host", "content-length", "connection", "expect", "accept-encoding",
                "authorization", "transfer-encoding", "proxy-authorization", "proxy-connection",
                -> Unit
                else -> connection.setRequestProperty(name, value)
            }
        }
        if (connection.requestMethod == "POST" || connection.requestMethod == "PUT") {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        return connection to code
    }

    private fun writeUpstreamResponse(
        output: BufferedOutputStream,
        connection: HttpURLConnection,
        code: Int,
    ) {
        val headers = StringBuilder()
        headers.append("HTTP/1.1 $code ${reasonPhrase(code)}\r\n")
        for ((name, values) in connection.headerFields) {
            if (name == null) continue
            val lower = name.lowercase()
            if (lower in HOP_BY_HOP_HEADERS) continue
            for (value in values) headers.append("$name: $value\r\n")
        }
        headers.append("Connection: close\r\n\r\n")
        output.write(headers.toString().toByteArray())
        output.flush()
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        stream?.use { source ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count <= 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        }
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): ByteArray {
        val length = headers["content-length"]?.toIntOrNull()
        if (length != null) {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bytes, offset, length - offset)
                if (count < 0) break
                offset += count
            }
            return bytes
        }
        val chunked = headers["transfer-encoding"].equals("chunked", ignoreCase = true)
        if (!chunked) return ByteArray(0)
        val body = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            var remaining = size
            while (remaining > 0) {
                val count = input.read(chunk, 0, minOf(remaining, chunk.size))
                if (count < 0) break
                body.write(chunk, 0, count)
                remaining -= count
            }
            readLine(input)
        }
        return body.toByteArray()
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

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        output.write("HTTP/1.1 $code ${reasonPhrase(code)}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun openAiError(message: String): String =
        """{"error":{"message":"${message.replace("\"", "'").take(500)}","type":"api_error"}}"""

    private fun reasonPhrase(code: Int): String = when (code) {
        in 200..299 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        in 500..599 -> "Server Error"
        else -> "Error"
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }

    companion object {
        private val SUPPORTED_METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")
        private val HOP_BY_HOP_HEADERS = setOf(
            "transfer-encoding", "connection", "keep-alive", "proxy-connection",
            "trailer", "upgrade",
        )
    }
}
