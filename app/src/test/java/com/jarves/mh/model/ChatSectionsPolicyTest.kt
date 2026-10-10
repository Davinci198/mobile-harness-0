package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSectionsPolicyTest {

    @Test
    fun autoOpensWhileRunningAndCollapsesWhenFinished() {
        assertEquals(listOf(0, 1, 2), ChatSectionsPolicy.initialExpanded(ChatSectionsMode.AUTO, isRunning = true, itemCount = 3))
        assertEquals(emptyList<Int>(), ChatSectionsPolicy.initialExpanded(ChatSectionsMode.AUTO, isRunning = false, itemCount = 3))
        assertEquals(
            emptyList<Int>(),
            ChatSectionsPolicy.onRunFinished(ChatSectionsMode.AUTO, listOf(0, 1)),
        )
    }

    @Test
    fun alwaysAndNeverIgnoreRunningState() {
        assertEquals(listOf(0, 1), ChatSectionsPolicy.initialExpanded(ChatSectionsMode.ALWAYS, isRunning = false, itemCount = 2))
        assertEquals(emptyList<Int>(), ChatSectionsPolicy.initialExpanded(ChatSectionsMode.NEVER, isRunning = true, itemCount = 2))
        assertEquals(listOf(0, 1), ChatSectionsPolicy.onRunFinished(ChatSectionsMode.ALWAYS, listOf(0, 1)))
        assertEquals(listOf(0), ChatSectionsPolicy.onRunFinished(ChatSectionsMode.NEVER, listOf(0)))
    }

    @Test
    fun emptySectionStillExpandsItsSummaryRow() {
        assertEquals(listOf(0), ChatSectionsPolicy.initialExpanded(ChatSectionsMode.ALWAYS, isRunning = false, itemCount = 0))
        assertEquals(listOf(0), ChatSectionsPolicy.initialExpanded(ChatSectionsMode.AUTO, isRunning = true, itemCount = 0))
        assertEquals(emptyList<Int>(), ChatSectionsPolicy.initialExpanded(ChatSectionsMode.AUTO, isRunning = false, itemCount = 0))
    }

    @Test
    fun grownListOpensNewRowsButKeepsManualToggles() {
        // AUTO while running: add the new row, keep whatever the user closed.
        assertEquals(
            listOf(2, 0, 1),
            ChatSectionsPolicy.onItemsGrown(ChatSectionsMode.AUTO, isRunning = true, itemCount = 3, current = listOf(2)),
        )
        // NEVER never forces rows open.
        assertEquals(listOf(1), ChatSectionsPolicy.onItemsGrown(ChatSectionsMode.NEVER, isRunning = true, itemCount = 4, current = listOf(1)))
        // AUTO after finish: do not re-open anything.
        assertEquals(listOf(1), ChatSectionsPolicy.onItemsGrown(ChatSectionsMode.AUTO, isRunning = false, itemCount = 4, current = listOf(1)))
        // ALWAYS opens the new row regardless of running state.
        assertEquals(
            listOf(0, 1),
            ChatSectionsPolicy.onItemsGrown(ChatSectionsMode.ALWAYS, isRunning = false, itemCount = 2, current = listOf(0)),
        )
    }

    @Test
    fun modeAcceptsOpenMatchesTheTriState() {
        assertTrue(ChatSectionsPolicy.modeAcceptsOpen(ChatSectionsMode.ALWAYS, isRunning = false))
        assertTrue(ChatSectionsPolicy.modeAcceptsOpen(ChatSectionsMode.AUTO, isRunning = true))
        assertFalse(ChatSectionsPolicy.modeAcceptsOpen(ChatSectionsMode.AUTO, isRunning = false))
        assertFalse(ChatSectionsPolicy.modeAcceptsOpen(ChatSectionsMode.NEVER, isRunning = true))
    }
}
