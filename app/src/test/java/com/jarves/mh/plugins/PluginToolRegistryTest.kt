package com.jarves.mh.plugins

import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStorage
import com.jarves.mh.tools.ToolPermissionStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryStorage : ToolPermissionStorage {
    private val map = mutableMapOf<String, String>()
    override fun read(key: String): String? = map[key]
    override fun write(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

private class FakeHost(private val tools: List<PluginTool>) : PluginToolHost {
    val executions = mutableListOf<String>()
    var failWith: String? = null
    var listFailure: String? = null

    override fun listTools(): List<PluginTool> {
        listFailure?.let { error(it) }
        return tools
    }

    override suspend fun executeTool(name: String, arguments: JSONObject): PluginToolResult {
        executions += name
        failWith?.let { return PluginToolResult.Error(it) }
        return PluginToolResult.Success("ok:$name")
    }
}

private fun candidate(packageName: String, label: String = packageName) = PluginCandidate(
    packageName = packageName,
    label = label,
    versionName = "1.0",
    versionCode = 1L,
    declaredCapabilities = setOf(PluginCapability.TOOLS),
    entryPointService = null,
)

private fun tool(name: String) = PluginTool(name, "description of $name")

class PluginToolRegistryTest {

    private val logs = mutableListOf<String>()
    private val storage = MemoryStorage()
    private val gate = ToolPermissionGate(ToolPermissionStore(storage))
    private val registry = PluginToolRegistry(gate) { logs += it }

    @Test
    fun toolsListEveryContributedToolInOrder() {
        val hostA = FakeHost(listOf(tool("alpha")))
        val hostB = FakeHost(listOf(tool("beta"), tool("gamma")))
        registry.registerAll(
            listOf(
                PluginToolBinding(candidate("com.example.a"), hostA),
                PluginToolBinding(candidate("com.example.b"), hostB),
            ),
        )
        assertEquals(listOf("alpha", "beta", "gamma"), registry.tools().map { it.name })
    }

    @Test
    fun duplicateToolNameKeepsFirstPluginAndLogsConflict() {
        val hostA = FakeHost(listOf(tool("alpha")))
        val hostB = FakeHost(listOf(tool("alpha"), tool("beta")))
        registry.registerAll(
            listOf(
                PluginToolBinding(candidate("com.example.a", "Alpha Plugin"), hostA),
                PluginToolBinding(candidate("com.example.b", "Beta Plugin"), hostB),
            ),
        )
        assertEquals(listOf("alpha", "beta"), registry.tools().map { it.name })
        assertEquals("com.example.a", registry.sourceFor("alpha")?.packageName)
        assertEquals("Alpha Plugin", registry.summaries().first { it.name == "alpha" }.sourceLabel)
        assertTrue(
            "expected a conflict log, got $logs",
            logs.any { it.contains("alpha") && it.contains("conflict") },
        )
    }

    @Test
    fun duplicateToolNameRoutesExecutionToFirstPlugin() {
        val hostA = FakeHost(listOf(tool("alpha")))
        val hostB = FakeHost(listOf(tool("alpha")))
        registry.registerAll(
            listOf(
                PluginToolBinding(candidate("com.example.a"), hostA),
                PluginToolBinding(candidate("com.example.b"), hostB),
            ),
        )
        gate.setOverride("alpha", ToolPermissionLevel.ALLOW)
        val result = runBlocking { registry.execute("alpha", JSONObject()) }
        assertEquals(PluginToolResult.Success("ok:alpha"), result)
        assertEquals(listOf("alpha"), hostA.executions)
        assertTrue(hostB.executions.isEmpty())
    }

    @Test
    fun unknownToolIsRejectedWithoutTouchingAnyHost() {
        val host = FakeHost(listOf(tool("alpha")))
        registry.register(PluginToolBinding(candidate("com.example.a"), host))
        val result = runBlocking { registry.execute("ghost", JSONObject()) }
        assertTrue(result is PluginToolResult.Error)
        assertTrue((result as PluginToolResult.Error).reason.contains("Unknown"))
        assertTrue(host.executions.isEmpty())
    }

    @Test
    fun globalAllowAloneDoesNotAutoRunPluginTool() {
        val host = FakeHost(listOf(tool("alpha")))
        registry.register(PluginToolBinding(candidate("com.example.a"), host))
        gate.setGlobalDefault(ToolPermissionLevel.ALLOW)
        val blocked = runBlocking { registry.execute("alpha", JSONObject()) }
        assertTrue((blocked as PluginToolResult.Error).reason.contains("approval"))
        assertTrue(host.executions.isEmpty())
    }

    @Test
    fun allowOnceGrantAfterAskRunsTheTool() {
        val host = FakeHost(listOf(tool("alpha")))
        registry.register(PluginToolBinding(candidate("com.example.a"), host))
        gate.setGlobalDefault(ToolPermissionLevel.ASK)
        gate.allowOnce("alpha")
        val result = runBlocking { registry.execute("alpha", JSONObject()) }
        assertEquals(PluginToolResult.Success("ok:alpha"), result)
        assertEquals(listOf("alpha"), host.executions)
    }

    @Test
    fun explicitAllowOverrideRoutesExecution() {
        val host = FakeHost(listOf(tool("alpha")))
        registry.register(PluginToolBinding(candidate("com.example.a"), host))
        gate.setOverride("alpha", ToolPermissionLevel.ALLOW)
        val result = runBlocking { registry.execute("alpha", JSONObject().put("x", 1)) }
        assertEquals(PluginToolResult.Success("ok:alpha"), result)
        assertEquals(listOf("alpha"), host.executions)
    }

    @Test
    fun forbidBlocksBeforePluginCodeRuns() {
        val host = FakeHost(listOf(tool("alpha")))
        registry.register(PluginToolBinding(candidate("com.example.a"), host))
        gate.setOverride("alpha", ToolPermissionLevel.FORBID)
        val result = runBlocking { registry.execute("alpha", JSONObject()) }
        assertTrue((result as PluginToolResult.Error).reason.contains("forbidden"))
        assertTrue(host.executions.isEmpty())
    }

    @Test
    fun hostFailureIsReportedAsError() {
        val host = FakeHost(listOf(tool("alpha"))).apply { failWith = "plugin exploded" }
        registry.register(PluginToolBinding(candidate("com.example.a"), host))
        gate.setOverride("alpha", ToolPermissionLevel.ALLOW)
        val result = runBlocking { registry.execute("alpha", JSONObject()) }
        assertTrue((result as PluginToolResult.Error).reason.contains("plugin exploded"))
    }

    @Test
    fun hostThatCannotListToolsIsSkippedAndLogged() {
        val broken = FakeHost(emptyList()).apply { listFailure = "no manifest" }
        val healthy = FakeHost(listOf(tool("alpha")))
        registry.registerAll(
            listOf(
                PluginToolBinding(candidate("com.example.broken"), broken),
                PluginToolBinding(candidate("com.example.a"), healthy),
            ),
        )
        assertEquals(listOf("alpha"), registry.tools().map { it.name })
        assertTrue(logs.any { it.contains("com.example.broken") && it.contains("list tools") })
    }

    @Test
    fun summariesCarrySourcePackageAndLabel() {
        registry.register(
            PluginToolBinding(
                candidate("com.example.a", "Alpha Plugin"),
                FakeHost(listOf(tool("alpha"))),
            ),
        )
        val summary = registry.summaries().single()
        assertEquals("alpha", summary.name)
        assertEquals("description of alpha", summary.description)
        assertEquals("com.example.a", summary.sourcePackageName)
        assertEquals("Alpha Plugin", summary.sourceLabel)
    }

    @Test
    fun registerIsIncrementalAcrossCalls() {
        registry.register(PluginToolBinding(candidate("com.example.a"), FakeHost(listOf(tool("alpha")))))
        registry.register(PluginToolBinding(candidate("com.example.b"), FakeHost(listOf(tool("beta")))))
        assertEquals(listOf("alpha", "beta"), registry.tools().map { it.name })
    }
}
