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
}
