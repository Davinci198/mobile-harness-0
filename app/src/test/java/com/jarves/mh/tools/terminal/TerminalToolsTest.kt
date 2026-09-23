package com.jarves.mh.tools.terminal

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStorage
import com.jarves.mh.tools.ToolPermissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class TerminalMemoryStorage : ToolPermissionStorage {
    private val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class FakeRunner(
    var result: TerminalCommandRun = TerminalCommandRun("ok", 0, false),
) : TerminalCommandRunner {
    var calls = 0
    var lastCommand: String? = null
    var lastProject: String? = null

    override fun run(
        projectSlug: String,
        command: String,
        timeoutMs: Long,
        rows: Int,
        columns: Int,
    ): TerminalCommandRun {
        calls++
        lastCommand = command
        lastProject = projectSlug
        return result
    }
}

class TerminalToolsTest {
    private fun tools(
        store: ToolPermissionStore,
        runner: FakeRunner = FakeRunner(),
        hooks: List<AIToolHook> = emptyList(),
    ): TerminalTools {
        store.globalDefault = ToolPermissionLevel.ALLOW
        return TerminalTools(runner, ToolPermissionGate(store), hooks)
    }

    @Test
    fun executesAllowedCommand() {
        val store = ToolPermissionStore(TerminalMemoryStorage())
        val runner = FakeRunner(TerminalCommandRun("hello", 0, false))
        val result = tools(store, runner).execute("project", "printf hello")
        assertTrue(result is TerminalToolResult.Completed)
        assertEquals("hello", (result as TerminalToolResult.Completed).output)
        assertEquals("project", runner.lastProject)
        assertEquals("printf hello", runner.lastCommand)
    }

    @Test
    fun returnsNonZeroExitAsCompleted() {
        val store = ToolPermissionStore(TerminalMemoryStorage())
        val runner = FakeRunner(TerminalCommandRun("error", 7, false))
        val result = tools(store, runner).execute("project", "false")
        assertTrue(result is TerminalToolResult.Completed)
        assertEquals(7, (result as TerminalToolResult.Completed).exitCode)
    }

    @Test
    fun returnsTimeoutResult() {
        val store = ToolPermissionStore(TerminalMemoryStorage())
        val runner = FakeRunner(TerminalCommandRun("partial", 124, true))
        val result = tools(store, runner).execute("project", "sleep 10", 1_000)
        assertTrue(result is TerminalToolResult.TimedOut)
        assertEquals("partial", (result as TerminalToolResult.TimedOut).output)
    }

    @Test
    fun askDoesNotExecute() {
        val store = ToolPermissionStore(TerminalMemoryStorage())
        store.globalDefault = ToolPermissionLevel.ASK
        val runner = FakeRunner()
        val result = TerminalTools(runner, ToolPermissionGate(store)).execute("project", "rm -rf /")
        assertTrue(result is TerminalToolResult.Error)
        assertTrue((result as TerminalToolResult.Error).message.contains("Approval"))
        assertEquals(0, runner.calls)
    }

    @Test
    fun forbidDoesNotExecute() {
        val store = ToolPermissionStore(TerminalMemoryStorage())
        store.globalDefault = ToolPermissionLevel.FORBID
        val runner = FakeRunner()
        val result = TerminalTools(runner, ToolPermissionGate(store)).execute("project", "pwd")
        assertTrue(result is TerminalToolResult.Error)
        assertEquals(0, runner.calls)
    }

    @Test
    fun invalidInputsDoNotExecute() {
        val store = ToolPermissionStore(TerminalMemoryStorage())
        val runner = FakeRunner()
        val tool = tools(store, runner)
        assertTrue(tool.execute("", "pwd") is TerminalToolResult.Error)
        assertTrue(tool.execute("project", " ") is TerminalToolResult.Error)
        assertTrue(tool.execute("project", "pwd", 0) is TerminalToolResult.Error)
        assertTrue(tool.execute("project", "pwd", rows = 0) is TerminalToolResult.Error)
        assertEquals(0, runner.calls)
    }

    @Test
    fun hooksFireForExecution() {
        val store = ToolPermissionStore(TerminalMemoryStorage())
        val events = mutableListOf<String>()
        val hook = object : AIToolHook {
            override fun onToolExecutionStarted(toolName: String) { events += "start:$toolName" }
            override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) {
                events += "result:$toolName:$success"
            }
            override fun onToolExecutionFinished(toolName: String) { events += "end:$toolName" }
        }
        tools(store, FakeRunner(), listOf(hook)).execute("project", "pwd")
        assertEquals(listOf("start:Bash", "result:Bash:true", "end:Bash"), events)
    }

    @Test
    fun runnerFailureIsReturnedAsError() {
        val store = ToolPermissionStore(TerminalMemoryStorage())
        val runner = object : TerminalCommandRunner {
            override fun run(
                projectSlug: String,
                command: String,
                timeoutMs: Long,
                rows: Int,
                columns: Int,
            ): TerminalCommandRun = throw IllegalStateException("spawn failed")
        }
        store.globalDefault = ToolPermissionLevel.ALLOW
        val result = TerminalTools(runner, ToolPermissionGate(store)).execute("project", "pwd")
        assertTrue(result is TerminalToolResult.Error)
        assertTrue((result as TerminalToolResult.Error).message.contains("spawn failed"))
    }
}
