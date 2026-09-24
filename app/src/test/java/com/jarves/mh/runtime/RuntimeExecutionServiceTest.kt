package com.jarves.mh.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for the background-survival session bookkeeping: the refcounted
 * [RuntimeTaskController] session registry and the durable
 * [RuntimeRecoveryState] ledger consumed after a process death.
 */
class RuntimeExecutionServiceTest {

    private lateinit var ledgerDir: File

    @Before
    fun setUp() {
        ledgerDir = File(
            System.getProperty("java.io.tmpdir"),
            "mh-recovery-test-${System.nanoTime()}",
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        ledgerDir.deleteRecursively()
        RuntimeTaskController.reset()
    }

    private fun begin(sessionId: String, description: String) {
        RuntimeTaskController.begin(sessionId, description)
        RuntimeRecoveryState.beginIn(ledgerDir, sessionId, description)
    }

    private fun end(sessionId: String) {
        RuntimeTaskController.end(sessionId)
        RuntimeRecoveryState.endIn(ledgerDir, sessionId)
    }

    @Test
    fun `sessions are refcounted - stale results never kill newer sessions`() {
        begin("a", "Task A")
        begin("b", "Task B")
        assertEquals(2, RuntimeTaskController.activeCount())

        // Stale terminal event for session a: b must stay active.
        val noneLeft = RuntimeTaskController.end("a")
        assertFalse(noneLeft)
        assertEquals(1, RuntimeTaskController.activeCount())
        assertEquals("Task B", RuntimeTaskController.describe())

        val last = RuntimeTaskController.end("b")
        assertTrue(last)
        assertEquals(0, RuntimeTaskController.activeCount())
        assertNull(RuntimeTaskController.describe())
    }

    @Test
    fun `ending an unknown session reports none left`() {
        assertTrue(RuntimeTaskController.end("does-not-exist"))
    }

    @Test
    fun `recovery ledger round trips and consume clears it`() {
        begin("s1", "Working: feature")
        begin("s2", "Working: fix")
        end("s1")

        val recovered = RuntimeRecoveryState.consumeIn(ledgerDir)
        assertEquals(listOf("s2" to "Working: fix"), recovered)
        assertTrue(RuntimeRecoveryState.consumeIn(ledgerDir).isEmpty())
    }

    @Test
    fun `recovery ledger tolerates malformed lines`() {
        val file = File(ledgerDir, "runtime-recovery-tasks.txt")
        file.writeText("garbage\ns1|desc one\n\ns2|desc two\n")
        val recovered = RuntimeRecoveryState.consumeIn(ledgerDir)
        assertEquals(listOf("s1" to "desc one", "s2" to "desc two"), recovered)
    }

    @Test
    fun `consume on missing ledger is empty`() {
        assertTrue(RuntimeRecoveryState.consumeIn(File(ledgerDir, "never-created")).isEmpty())
    }

    @Test
    fun `re-registering a session replaces its description`() {
        begin("s1", "Old description")
        begin("s1", "New description")
        assertEquals("New description", RuntimeTaskController.describe())
        assertEquals(1, RuntimeTaskController.activeCount())
        end("s1")
        assertTrue(RuntimeTaskController.end("s1"))
    }

    @Test
    fun `orphan marker list matches known guest binaries`() {
        val markers = GuestOrphanScan.orphanMarkers()
        assertTrue(markers.contains("libproot.so"))
        assertTrue(markers.contains("opencode serve"))
        assertTrue(markers.any { it.startsWith("/usr/local/bin/") })
    }
}
