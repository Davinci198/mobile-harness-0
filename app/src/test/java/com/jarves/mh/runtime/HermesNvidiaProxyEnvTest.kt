package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Hermes agent's provider is an NVIDIA NIM profile that rides the local
 * OpenAI-compatible proxy. Hermes resolves a provider's credential from that
 * provider's own env var, so the proxy branch has to publish it as well.
 */
class HermesNvidiaProxyEnvTest {

    private fun profile() = ProviderProfile(
        kind = ProviderKind.NVIDIA_NIM,
        model = "meta/muse-glimmer-30b",
        baseUrl = "https://integrate.api.nvidia.com/v1",
    )

    @Test
    fun proxyBranchPublishesTheNvidiaCredentialAndBaseUrl() {
        val environment = hermesEnvironmentFor(
            provider = profile(),
            secret = "nvapi-test-key",
            gatewayUrl = "http://127.0.0.1:43123/v1",
        )

        assertEquals("nvapi-test-key", environment["NVIDIA_API_KEY"])
        // The traffic must still go through the loopback proxy: the outbound
        // TLS lives in the app process, not in the guest.
        assertEquals("http://127.0.0.1:43123/v1", environment["NVIDIA_BASE_URL"])
        assertEquals("nvapi-test-key", environment["OPENAI_API_KEY"])
        assertEquals("http://127.0.0.1:43123/v1", environment["OPENAI_BASE_URL"])
    }

    @Test
    fun directBranchKeepsTheProviderEndpoint() {
        val environment = hermesEnvironmentFor(
            provider = profile(),
            secret = "nvapi-test-key",
            gatewayUrl = null,
        )

        assertEquals("nvapi-test-key", environment["NVIDIA_API_KEY"])
        assertEquals("https://integrate.api.nvidia.com/v1", environment["NVIDIA_BASE_URL"])
    }

    @Test
    fun customOpenAiProfileDoesNotGrowNvidiaVariables() {
        val custom = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            model = "some-model",
            baseUrl = "https://example.invalid/v1",
            dshApi = "openai-completions",
        )

        val environment = hermesEnvironmentFor(
            provider = custom,
            secret = "sk-test",
            gatewayUrl = "http://127.0.0.1:43124/v1",
        )

        assertEquals("sk-test", environment["OPENAI_API_KEY"])
        assertTrue(environment["NVIDIA_API_KEY"] == null)
        assertTrue(environment["NVIDIA_BASE_URL"] == null)
    }
}
