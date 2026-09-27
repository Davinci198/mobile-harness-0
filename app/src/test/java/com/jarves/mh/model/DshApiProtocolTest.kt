package com.jarves.mh.model

import com.jarves.mh.network.ProviderApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

class DshApiProtocolTest {
    @Test
    fun mapsEveryWireProtocolToItsDshApiString() {
        assertEquals("openai-completions", dshApiForProtocol(ProviderProtocol.OPENAI_CHAT))
        assertEquals("openai-responses", dshApiForProtocol(ProviderProtocol.OPENAI_RESPONSES))
        assertEquals("anthropic-messages", dshApiForProtocol(ProviderProtocol.ANTHROPIC_GATEWAY))
        assertEquals("anthropic-messages", dshApiForProtocol(ProviderProtocol.ANTHROPIC))
        assertEquals("anthropic-messages", dshApiForProtocol(ProviderProtocol.OPENROUTER))
        assertEquals("anthropic-messages", dshApiForProtocol(ProviderProtocol.CLAUDE_LOGIN))
    }

    @Test
    fun roundTripsThroughProviderProtocolForAgent() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, "https://example.com/v1", "m", dshApi = "openai-completions")
        assertEquals(
            "openai-completions",
            dshApiForProtocol(providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS)),
        )
    }

    @Test
    fun resolvesThePickedProtocolForEveryAgent() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, "https://inference.dahl.global/v1", "m", dshApi = "openai-completions")
        for (agent in AgentKind.entries) {
            assertEquals(agent.name, ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, agent))
        }
        assertEquals(
            ProviderProtocol.ANTHROPIC_GATEWAY,
            providerProtocolForAgent(profile.copy(dshApi = "anthropic-messages"), AgentKind.HERMES),
        )
        assertEquals(
            ProviderProtocol.OPENAI_RESPONSES,
            providerProtocolForAgent(profile.copy(dshApi = "openai-responses"), AgentKind.OPENCODE),
        )
    }

    @Test
    fun providersWithoutAPickerKeepTheirBuiltInProtocol() {
        val profile = ProviderProfile(ProviderKind.ANTHROPIC, "https://api.anthropic.com", "m", dshApi = "openai-completions")
        assertEquals(
            ProviderProtocol.ANTHROPIC,
            providerProtocolForAgent(profile, AgentKind.HERMES),
        )
        // Fixed-protocol providers ignore whatever the profile stores.
        val nim = ProviderProfile(ProviderKind.NVIDIA_NIM, dshApi = "anthropic-messages")
        assertEquals(
            ProviderProtocol.OPENAI_CHAT,
            providerProtocolForAgent(nim, AgentKind.CLAUDE_CODE),
        )
    }

    @Test
    fun blankWireProtocolFallsBackToTheProviderDefault() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, "https://example.com/v1", "m", dshApi = "")
        assertEquals(
            ProviderProtocol.ANTHROPIC_GATEWAY,
            providerProtocolForAgent(profile, AgentKind.HERMES),
        )
    }

    @Test
    fun previewsTheEndpointTheSelectedProtocolWillCall() {
        val openai = ProviderProfile(ProviderKind.CUSTOM, "https://inference.dahl.global/v1", "m", dshApi = "openai-completions")
        val anthropic = openai.copy(dshApi = "anthropic-messages")
        val client = ProviderApiClient()
        assertEquals(
            "https://inference.dahl.global/v1/chat/completions",
            client.requestPreviewUrl(openai.baseUrl, providerProtocolForAgent(openai, AgentKind.HERMES)),
        )
        assertEquals(
            "https://inference.dahl.global/v1/messages",
            client.requestPreviewUrl(anthropic.baseUrl, providerProtocolForAgent(anthropic, AgentKind.HERMES)),
        )
    }
}
