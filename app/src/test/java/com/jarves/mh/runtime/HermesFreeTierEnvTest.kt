package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesFreeTierEnvTest {
    @Test
    fun freeProviderOpensTheAnonymousFreeTierGate() {
        val env = hermesEnvironmentFor(ProviderProfile(kind = ProviderKind.FREE), null, null)

        assertEquals("1", env["HERMES_GUEST_ONBOARDING"])
        assertEquals("/root", env["HOME"])
    }

    @Test
    fun keyedProviderKeepsItsKeyAndNoGate() {
        val env = hermesEnvironmentFor(
            ProviderProfile(kind = ProviderKind.NVIDIA_NIM),
            secret = "nvkey",
            gatewayUrl = null,
        )

        assertEquals("nvkey", env["OPENAI_API_KEY"])
        assertFalse(env.containsKey("HERMES_GUEST_ONBOARDING"))
    }

    @Test
    fun loopbackWithoutKeyStaysGateless() {
        val env = hermesEnvironmentFor(
            ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = "http://127.0.0.1:20128/v1",
                dshApi = "openai-completions",
            ),
            secret = null,
            gatewayUrl = null,
        )

        assertEquals("loopback", env["OPENAI_API_KEY"])
        assertFalse(env.containsKey("HERMES_GUEST_ONBOARDING"))
    }

    @Test
    fun freeProviderNeverSendsAKeyVariable() {
        val env = hermesEnvironmentFor(
            ProviderProfile(kind = ProviderKind.FREE),
            secret = "stale-key",
            gatewayUrl = "http://127.0.0.1:8080/v1",
        )

        assertEquals(setOf("HOME", "HERMES_GUEST_ONBOARDING"), env.keys)
        assertTrue(env.values.none { it == "stale-key" })
    }
}
