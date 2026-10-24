package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kilo Free rides [LocalOpenAiProxy] with a blank key. The proxy must forward
 * its request WITHOUT an Authorization header — Kilo's free pool answers 401
 * to any credential, so a placeholder smuggled through the proxy would break
 * every turn. A loopback echo upstream records exactly what arrived.
 */
class LocalOpenAiProxyKeylessTest {

    /** Records the first request then answers a fixed JSON body. */
    private class EchoUpstream : AutoCloseable {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val lastRequest = AtomicReference<String?>(null)

        fun start(): EchoUpstream = apply {
            Thread({
                runCatching {
                    server.accept().use { socket ->
                        lastRequest.set(readCompleteRequest(socket))
                        val body = """{"ok":true,"up":1}"""
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                        )
                        socket.getOutputStream().flush()
                    }
                }.onFailure { lastRequest.set("upstream-error: ${it.message}") }
            }, "echo-upstream").apply { isDaemon = true; start() }
        }

        private fun readCompleteRequest(socket: Socket): String {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = input.readLineBytes() ?: return "(no request line)"
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLineBytes() ?: break
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
            }
            val body = headers["content-length"]?.toIntOrNull()?.let { length ->
                val buffer = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(buffer, offset, length - offset)
                    if (count < 0) break
                    offset += count
                }
                String(buffer)
            }.orEmpty()
            return buildString {
                append("LINE $requestLine\n")
                headers.forEach { (name, value) -> append("H $name: $value\n") }
                append("BODY $body")
            }
        }

        private fun BufferedInputStream.readLineBytes(): String? {
            val bytes = ArrayList<Byte>()
            while (true) {
                val value = read()
                if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
                if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
                bytes += value.toByte()
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    private val upstreams = mutableListOf<EchoUpstream>()
    private val proxies = mutableListOf<LocalOpenAiProxy>()

    private fun openProxy(key: String): LocalOpenAiProxy {
        val upstream = EchoUpstream().start().also(upstreams::add)
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "http://127.0.0.1:${upstream.port}/v1",
            model = "some-model",
            dshApi = "openai-completions",
        )
        assertTrue(profile.routesThroughOpenAiProxy())
        return LocalOpenAiProxy(profile, key).start().also(proxies::add)
    }

    @After
    fun tearDown() {
        proxies.forEach { runCatching { it.close() } }
        upstreams.forEach { runCatching { it.close() } }
    }

    @Test
    fun blankKeyForwardsWithoutAnAuthorizationHeader() {
        val proxy = openProxy("")

        val code = post(proxy.url + "/chat/completions", """{"model":"some-model"}""")

        assertEquals(200, code)
        val (requestLine, headers, body) = parseEchoRequest(upstreams.last().lastRequest.get())
        assertEquals("POST /v1/chat/completions HTTP/1.1", requestLine)
        assertFalse(
            "Authorization reached the upstream for a keyless profile " + headers,
            headers.containsKey("authorization"),
        )
        assertEquals("""{"model":"some-model"}""", body)
    }

    @Test
    fun keyedProfileStillSendsBearer() {
        val proxy = openProxy("sk-test")

        val code = post(proxy.url + "/chat/completions", """{"model":"some-model"}""")

        assertEquals(200, code)
        val (_, headers, _) = parseEchoRequest(upstreams.last().lastRequest.get())
        assertEquals("Bearer sk-test", headers["authorization"])
    }

    private fun parseEchoRequest(raw: String?): Triple<String, Map<String, String>, String> {
        requireNotNull(raw) { "upstream saw no request" }
        val lines = raw.split('\n')
        val requestLine = lines.first { it.startsWith("LINE ") }.removePrefix("LINE ")
        val headers = lines.filter { it.startsWith("H ") }.associate {
            val value = it.removePrefix("H ")
            val split = value.indexOf(": ")
            value.substring(0, split).lowercase() to value.substring(split + 2)
        }
        val body = lines.first { it.startsWith("BODY ") }.removePrefix("BODY ")
        return Triple(requestLine, headers, body)
    }

    /** Posts a fixed body; HttpURLConnection exposes the status only via getResponseCode(). */
    private fun post(endpoint: String, body: String): Int {
        val connection = URL(endpoint).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        val bytes = body.toByteArray()
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        return try {
            val code = connection.responseCode
            connection.inputStream.use { it.readAllBytes() }
            code
        } catch (error: java.io.IOException) {
            connection.errorStream?.use { it.readAllBytes() }
            connection.responseCode
        }
    }
}
