package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeCustomEndpointConfigTest {
    private val baseUrl = "https://inference.dahl.global/v1/"
    private val model = "MiniMaxAI/MiniMax-M2.7"

    private fun custom(dshApi: String) = ProviderProfile(
        kind = ProviderKind.CUSTOM,
        baseUrl = baseUrl,
        model = model,
        dshApi = dshApi,
    )

    @Test
    fun registersTheCustomModelUnderTheOpenAiNamespace() {
        val expected = "{\"\$schema\":\"https://opencode.ai/config.json\",\"snapshot\":false," +
            "\"provider\":{\"openai\":{\"npm\":\"@ai-sdk/openai-compatible\"," +
            "\"options\":{\"baseURL\":\"$baseUrl\",\"apiKey\":\"{env:OPENAI_API_KEY}\"}," +
            "\"models\":{\"$model\":{}}}}}"
        assertEquals(
            expected,
            openAiCompatibleConfigContent("openai", baseUrl, "OPENAI_API_KEY", model),
        )
    }

    @Test
    fun configJsonCarriesBaseUrlAndModelKey() {
        val config = JSONObject(
            openAiCompatibleConfigContent("openai", baseUrl, "OPENAI_API_KEY", model),
        )
        val provider = config.getJSONObject("provider").getJSONObject("openai")
        assertEquals("@ai-sdk/openai-compatible", provider.getString("npm"))
        assertEquals(baseUrl, provider.getJSONObject("options").getString("baseURL"))
        assertEquals("{env:OPENAI_API_KEY}", provider.getJSONObject("options").getString("apiKey"))
        assertTrue(provider.getJSONObject("models").has(model))
    }

    @Test
    fun nvidiaConfigContentKeepsTheCatalogModelKey() {
        val expected = "{\"\$schema\":\"https://opencode.ai/config.json\",\"snapshot\":false," +
            "\"provider\":{\"nvidia\":{\"npm\":\"@ai-sdk/openai-compatible\"," +
            "\"options\":{\"baseURL\":\"https://integrate.api.nvidia.com/v1\"," +
            "\"apiKey\":\"{env:NVIDIA_API_KEY}\"}," +
            "\"models\":{\"nvidia/nemotron-3-nano-omni-30b-a3b-reasoning\":{}}}}}"
        assertEquals(
            expected,
            openAiCompatibleConfigContent(
                "nvidia",
                "https://integrate.api.nvidia.com/v1",
                "NVIDIA_API_KEY",
                "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
            ),
        )
    }

    @Test
    fun cliModelFlagPointsAtTheRegisteredNamespace() {
        for (dshApi in listOf("openai-completions", "openai-responses")) {
            val profile = custom(dshApi)
            val arg = opencodeModelArg(profile, gatewayUrl = null, model = model)
            assertEquals("openai/$model", arg)
            assertEquals(model, arg.removePrefix("openai/"))
            assertTrue(profile.isOpenAiCompatibleCustom())
        }
    }

    @Test
    fun cliModelFlagIsPrefixedEvenWhenTheModelIdStartsWithOpenAi() {
        val profile = custom("openai-completions").copy(model = "openai/gpt-4o-mini")
        val arg = opencodeModelArg(profile, gatewayUrl = null, model = "openai/gpt-4o-mini")
        assertEquals("openai/openai/gpt-4o-mini", arg)
        assertEquals("openai/gpt-4o-mini", arg.removePrefix("openai/"))
    }

    @Test
    fun loopbackProxyTurnKeepsTheOpenAiNamespace() {
        val profile = custom("openai-completions")
        assertEquals(
            "openai/$model",
            opencodeModelArg(profile, gatewayUrl = "http://127.0.0.1:37821/v1", model = model),
        )
    }

    @Test
    fun anthropicCustomEndpointsKeepTheBareModelId() {
        val profile = custom("anthropic-messages")
        assertFalse(profile.isOpenAiCompatibleCustom())
        assertFalse(isOpenAiCompatibleApi("anthropic-messages"))
        assertTrue(isOpenAiCompatibleApi("openai-completions"))
        assertEquals(model, opencodeModelArg(profile, gatewayUrl = null, model = model))
    }

    @Test
    fun otherProvidersKeepTheirNamespace() {
        assertEquals(
            "nvidia/nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
            opencodeModelArg(
                ProviderProfile(ProviderKind.NVIDIA_NIM, model = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"),
                gatewayUrl = null,
                model = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
            ),
        )
        assertEquals(
            "deepseek/deepseek-chat",
            opencodeModelArg(
                ProviderProfile(ProviderKind.DEEPSEEK, model = "deepseek-chat"),
                gatewayUrl = null,
                model = "deepseek-chat",
            ),
        )
        assertEquals(
            "anthropic/claude-sonnet-4-6",
            opencodeModelArg(
                ProviderProfile(ProviderKind.ANTHROPIC, model = "claude-sonnet-4-6"),
                gatewayUrl = null,
                model = "claude-sonnet-4-6",
            ),
        )
    }
}
