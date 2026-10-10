package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortTest {

    @Test
    fun antigravityExposesTheRealLowMediumHighLadder() {
        assertEquals(listOf("low", "medium", "high"), ReasoningEffort.levelsFor(AgentKind.ANTIGRAVITY))
    }

    @Test
    fun agentsWithoutTheCapabilityGetNoEffortControl() {
        assertEquals(emptyList<String>(), ReasoningEffort.levelsFor(AgentKind.CLAUDE_CODE))
        assertEquals(emptyList<String>(), ReasoningEffort.levelsFor(AgentKind.DEEPSEEK_HARNESS))
        assertEquals(emptyList<String>(), ReasoningEffort.levelsFor(AgentKind.OPENCODE))
        assertEquals(emptyList<String>(), ReasoningEffort.levelsFor(AgentKind.HERMES))
    }

    @Test
    fun everySupportedLevelIsPartOfTheDocumentedLadder() {
        AgentKind.entries.forEach { kind ->
            assertTrue(
                "levels for $kind must stay inside the ladder",
                ReasoningEffort.levelsFor(kind).all { it in ReasoningEffort.ALL_LEVELS },
            )
        }
    }
}
