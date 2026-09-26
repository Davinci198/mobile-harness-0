package com.jarves.mh.model

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
}
