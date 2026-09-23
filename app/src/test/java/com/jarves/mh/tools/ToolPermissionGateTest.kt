package com.jarves.mh.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [ToolPermissionStorage] for unit tests (no Android). */
private class MemoryStorage : ToolPermissionStorage {
    val map = mutableMapOf<String, String>()
    override fun read(key: String): String? = map[key]
    override fun write(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class ToolPermissionGateTest {
    private fun store(storage: ToolPermissionStorage = MemoryStorage()): ToolPermissionStore =
        ToolPermissionStore(storage)

    @Test
    fun defaultGlobalIsAsk() {
        assertEquals(ToolPermissionLevel.ASK, store().globalDefault)
    }

    @Test
    fun overrideWinsOverGlobal() {
        val store = store()
        store.globalDefault = ToolPermissionLevel.ALLOW
        store.setOverride("Bash", ToolPermissionLevel.FORBID)
        assertEquals(ToolPermissionLevel.FORBID, store.resolve("Bash"))
        assertEquals(ToolPermissionLevel.ALLOW, store.resolve("Edit"))
    }

    @Test
    fun resolveFallsBackToGlobalWhenNoOverride() {
        val store = store()
        store.globalDefault = ToolPermissionLevel.FORBID
        assertNull(store.overrideFor("Write"))
        assertEquals(ToolPermissionLevel.FORBID, store.resolve("Write"))
    }

    @Test
    fun clearOverrideRestoresGlobal() {
        val store = store()
        store.globalDefault = ToolPermissionLevel.ALLOW
        store.setOverride("Bash", ToolPermissionLevel.ASK)
        store.setOverride("Bash", null)
        assertEquals(ToolPermissionLevel.ALLOW, store.resolve("Bash"))
    }

    @Test
    fun allOverridesListsOnlyExplicitTools() {
        val store = store()
        store.setOverride("Bash", ToolPermissionLevel.FORBID)
        store.setOverride("Edit", ToolPermissionLevel.ALLOW)
        store.setOverride("Read", null)
        val overrides = store.allOverrides()
        assertEquals(2, overrides.size)
        assertEquals(ToolPermissionLevel.FORBID, overrides["Bash"])
        assertEquals(ToolPermissionLevel.ALLOW, overrides["Edit"])
        assertNull(overrides["Read"])
    }

    @Test
    fun knownToolsIncludeAppTools() {
        val expected = listOf(
            "Calculate",
            "Sleep",
            "UseSkill",
            "MemoryCreate",
            "MemoryRead",
            "MemoryUpdate",
            "MemoryDelete",
            "MemorySearch",
            "MemoryList",
            "MemoryLink",
            "MemoryUnlink",
            "MemoryLinkList",
        )
        assertTrue(ToolPermissionStore.knownTools.containsAll(expected))
    }

    @Test
    fun evaluateAllowRunsHooksAndAllows() {
        val store = store()
        store.globalDefault = ToolPermissionLevel.ALLOW
        val events = mutableListOf<String>()
        val hook = object : AIToolHook {
            override fun onToolCallRequested(toolName: String, explanation: String) {
                events += "requested:$toolName"
            }
            override fun onToolPermissionChecked(toolName: String, granted: Boolean, reason: String?) {
                events += "checked:$granted"
            }
        }
        val gate = ToolPermissionGate(store, listOf(hook))
        val decision = gate.evaluate("Read", "read file")
        assertTrue(decision is ToolPermissionGateDecision.Allowed)
        assertEquals(listOf("requested:Read", "checked:true"), events)
    }

    @Test
    fun evaluateAskNeedsApproval() {
        val store = store()
        store.globalDefault = ToolPermissionLevel.ASK
        val gate = ToolPermissionGate(store)
        val decision = gate.evaluate("Bash", "run ls")
        assertTrue(decision is ToolPermissionGateDecision.NeedsApproval)
        assertEquals(
            ToolPermissionLevel.ASK,
            (decision as ToolPermissionGateDecision.NeedsApproval).level,
        )
    }

    @Test
    fun evaluateForbidBlocks() {
        val store = store()
        store.globalDefault = ToolPermissionLevel.FORBID
        val gate = ToolPermissionGate(store)
        val decision = gate.evaluate("Write", "write file")
        assertTrue(decision is ToolPermissionGateDecision.Blocked)
    }

    @Test
    fun interceptHookBlocksEvenWhenGlobalAllows() {
        val store = store()
        store.globalDefault = ToolPermissionLevel.ALLOW
        val blocker = object : AIToolHook {
            override fun onToolCallIntercept(toolName: String, explanation: String): AIToolHookDecision =
                if (toolName == "Bash") AIToolHookDecision.Block("no shell") else AIToolHookDecision.Allow
        }
        val gate = ToolPermissionGate(store, listOf(blocker))
        val blocked = gate.evaluate("Bash", "rm -rf /")
        assertTrue(blocked is ToolPermissionGateDecision.Blocked)
        assertEquals("no shell", (blocked as ToolPermissionGateDecision.Blocked).reason)
        assertTrue(gate.evaluate("Read", "ok") is ToolPermissionGateDecision.Allowed)
    }

    @Test
    fun fromStringDefaultsToAskOnUnknown() {
        assertEquals(ToolPermissionLevel.ASK, ToolPermissionLevel.fromString(null))
        assertEquals(ToolPermissionLevel.ASK, ToolPermissionLevel.fromString("nope"))
        assertEquals(ToolPermissionLevel.ALLOW, ToolPermissionLevel.fromString("ALLOW"))
        assertEquals(ToolPermissionLevel.FORBID, ToolPermissionLevel.fromString("FORBID"))
    }
}
