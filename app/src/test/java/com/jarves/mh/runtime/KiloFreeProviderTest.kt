package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kilo Free is keyless, but its free pool is stricter than a loopback gateway:
 * any Authorization header — even the placeholder every guest agent sends —
 * answers 401. These tests pin the routes that keep the header off the wire.
 */
class KiloFreeProviderTest {

    @Test
    fun kiloFreeNeedsNoSavedSecret() {
        assertFalse(requiresSavedSecret(ProviderProfile(ProviderKind.KILO_FREE)))
    }

    @Test
    fun kiloFreeRidesTheLoopbackProxy() {
        assertTrue(ProviderProfile(ProviderKind.KILO_FREE).routesThroughOpenAiProxy())
    }

    @Test
    fun hermesProxyBranchKeepsTheHeaderlessGatewayUrl() {
        val environment = hermesEnvironmentFor(
            provider = ProviderProfile(ProviderKind.KILO_FREE),
            secret = "loopback",
            gatewayUrl = "http://127.0.0.1:43125/api/gateway",
        )

        assertEquals("loopback", environment["OPENAI_API_KEY"])
        // The base URL must point at the strip-proxy, not at the real gateway:
        // Kilo rejects every Authorization header, and the proxy is what
        // removes it.
        assertEquals("http://127.0.0.1:43125/api/gateway", environment["OPENAI_BASE_URL"])
        assertNull(environment["NVIDIA_BASE_URL"])
    }

    @Test
    fun hermesDirectBranchDialsKiloAtItsFixedOrigin() {
        val environment = hermesEnvironmentFor(
            provider = ProviderProfile(ProviderKind.KILO_FREE),
            secret = "loopback",
            gatewayUrl = null,
        )

        assertEquals("loopback", environment["OPENAI_API_KEY"])
        assertEquals("https://api.kilo.ai/api/gateway", environment["OPENAI_BASE_URL"])
    }

    @Test
    fun claudeFormatGatewayOmitsAuthorizationForKeylessOpenAiProfile() {
        val upstream = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val authorization = AtomicReference<String?>(null)
        val upstreamThread = Thread {
            upstream.use { server ->
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val headers = mutableMapOf<String, String>()
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        val split = line.indexOf(':')
                        if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                        line = reader.readLine()
                    }
                    authorization.set(headers["authorization"])
                    val body = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().use { it.write(response.toByteArray()); it.flush() }
                }
            }
        }.apply { isDaemon = true; start() }

        val gateway = LocalFormatGateway(
            ProviderProfile(
                // The gateway behavior is provider-agnostic; use a custom
                // local origin here because Kilo's fixed origin is remote.
                kind = ProviderKind.CUSTOM,
                baseUrl = "http://127.0.0.1:${upstream.localPort}/api/gateway",
                model = ProviderKind.KILO_FREE.defaultModel,
                dshApi = "openai-completions",
            ),
            apiKey = "",
        ).start()

        try {
            val connection = URL(gateway.url + "/v1/messages").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            val request = """{"model":"claude-sonnet-4-6","max_tokens":16,"messages":[{"role":"user","content":"hi"}]}"""
            connection.setFixedLengthStreamingMode(request.toByteArray().size)
            connection.outputStream.use { it.write(request.toByteArray()) }
            assertEquals(200, connection.responseCode)
            connection.inputStream.use { it.readAllBytes() }
        } finally {
            gateway.close()
            upstreamThread.join(2_000)
        }

        assertNull(authorization.get())
    }
}
