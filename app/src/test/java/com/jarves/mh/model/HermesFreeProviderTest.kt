package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesFreeProviderTest {
    @Test
    fun freeProviderIsNamedHermes() {
        assertEquals("Hermes", ProviderKind.FREE.title)
    }

    @Test
    fun freeTierBelongsToTheHermesAgentOnly() {
        assertTrue(ProviderKind.FREE in providersForAgent(AgentKind.HERMES))
        assertFalse(ProviderKind.FREE in providersForAgent(AgentKind.OPENCODE))
        assertFalse(ProviderKind.FREE in providersForAgent(AgentKind.CLAUDE_CODE))
        assertFalse(ProviderKind.FREE in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
        assertFalse(ProviderKind.FREE in providersForAgent(AgentKind.ANTIGRAVITY))
    }

    @Test
    fun freeTierShipsTheNousEndpointAndFreeDefaultModel() {
        assertEquals("https://inference-api.nousresearch.com/v1", ProviderKind.FREE.defaultBaseUrl)
        assertEquals("stepfun/step-3.7-flash:free", ProviderKind.FREE.defaultModel)
        assertTrue(ProviderKind.FREE.fixedBaseUrl)
        assertTrue(ProviderKind.FREE.fixedProtocol)
    }
}
