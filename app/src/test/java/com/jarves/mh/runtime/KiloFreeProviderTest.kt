package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
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
}
