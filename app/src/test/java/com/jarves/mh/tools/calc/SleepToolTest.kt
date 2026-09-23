package com.jarves.mh.tools.calc

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStorage
import com.jarves.mh.tools.ToolPermissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class SleepMemStorage : ToolPermissionStorage {
    private val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class SleepToolTest {
    private lateinit var store: ToolPermissionStore
    private val slept = mutableListOf<Long>()

    @Before
    fun setUp() {
        store = ToolPermissionStore(SleepMemStorage())
        store.globalDefault = ToolPermissionLevel.ALLOW
        slept.clear()
    }

    private fun tool(hooks: List<AIToolHook> = emptyList()): SleepTool =
        SleepTool(ToolPermissionGate(store), hooks) { ms -> slept += ms }

    @Test
    fun sleepsRequestedDurationWhenAllowed() {
        val result = tool().sleep(250)
        assertTrue(result is CalcToolResult.Slept)
        assertEquals(250L, (result as CalcToolResult.Slept).durationMs)
        assertEquals(listOf(250L), slept)
    }

    @Test
    fun defaultDurationIsApplied() {
        val result = tool().sleep()
        assertTrue(result is CalcToolResult.Slept)
        assertEquals(CalcToolLimits.DEFAULT_SLEEP_MS, (result as CalcToolResult.Slept).durationMs)
        assertEquals(listOf(CalcToolLimits.DEFAULT_SLEEP_MS), slept)
    }

    @Test
    fun zeroDurationIsAllowed() {
        val result = tool().sleep(0)
        assertTrue(result is CalcToolResult.Slept)
        assertEquals(listOf(0L), slept)
    }

    @Test
    fun negativeDurationIsErrorWithoutSleeping() {
        val result = tool().sleep(-1)
        assertTrue(result is CalcToolResult.Error)
        assertTrue((result as CalcToolResult.Error).message.contains("non-negative"))
        assertTrue(slept.isEmpty())
    }

    @Test
    fun durationAboveMaxIsErrorWithoutSleeping() {
        val result = tool().sleep(CalcToolLimits.MAX_SLEEP_MS + 1)
        assertTrue(result is CalcToolResult.Error)
        assertTrue((result as CalcToolResult.Error).message.contains("maximum"))
        assertTrue(slept.isEmpty())
    }

    @Test
    fun askDoesNotSleep() {
        store.globalDefault = ToolPermissionLevel.ASK
        val result = tool().sleep(1_000)
        assertTrue(result is CalcToolResult.Error)
        assertTrue((result as CalcToolResult.Error).message.contains("Approval"))
        assertTrue(slept.isEmpty())
    }

    @Test
    fun forbidDoesNotSleep() {
        store.globalDefault = ToolPermissionLevel.FORBID
        val result = tool().sleep(1_000)
        assertTrue(result is CalcToolResult.Error)
        assertTrue((result as CalcToolResult.Error).message.contains("forbidden"))
        assertTrue(slept.isEmpty())
    }

    @Test
    fun overrideAllowWhileGlobalAsk() {
        store.globalDefault = ToolPermissionLevel.ASK
        store.setOverride("Sleep", ToolPermissionLevel.ALLOW)
        val result = tool().sleep(10)
        assertTrue(result is CalcToolResult.Slept)
        assertEquals(listOf(10L), slept)
    }

    @Test
    fun hooksFireLifecycle() {
        val events = mutableListOf<String>()
        val hook = object : AIToolHook {
            override fun onToolExecutionStarted(toolName: String) { events += "start:$toolName" }
            override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) {
                events += "result:$toolName:$success"
            }
            override fun onToolExecutionFinished(toolName: String) { events += "end:$toolName" }
        }
        tool(listOf(hook)).sleep(5)
        assertEquals(listOf("start:Sleep", "result:Sleep:true", "end:Sleep"), events)
    }

    @Test
    fun hooksReportSleepFailure() {
        val events = mutableListOf<String>()
        val hook = object : AIToolHook {
            override fun onToolExecutionError(toolName: String, error: Throwable) {
                events += "error:$toolName"
            }
            override fun onToolExecutionFinished(toolName: String) { events += "end:$toolName" }
        }
        val failing = SleepTool(ToolPermissionGate(store), listOf(hook)) { error("interrupted") }
        val result = failing.sleep(1)
        assertTrue(result is CalcToolResult.Error)
        assertTrue((result as CalcToolResult.Error).message.contains("interrupted"))
        assertEquals(listOf("error:Sleep", "end:Sleep"), events)
    }
}
