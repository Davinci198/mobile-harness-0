package com.jarves.mh.tools.file

import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class GateMemStore : com.jarves.mh.tools.ToolPermissionStorage {
    val map = mutableMapOf<String, String>()
    override fun read(key: String): String? = map[key]
    override fun write(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class FileToolGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: ToolPermissionStore
    private lateinit var gate: ToolPermissionGate
    private lateinit var tools: FileTools

    @Before
    fun setUp() {
        store = ToolPermissionStore(GateMemStore())
        gate = ToolPermissionGate(store)
        tools = FileTools(tmp.root, gate)
    }

    @Test
    fun defaultAskBlocksMutatingTools() {
        // default global is ASK
        val w = tools.write("f.txt", "x")
        assertTrue(w is FileToolResult.FileToolError)
        assertTrue((w as FileToolResult.FileToolError).message.contains("Approval"))
        assertTrue(!tmp.root.resolve("f.txt").exists())
    }

    @Test
    fun overrideWriteAllowWhileGlobalAsk() {
        store.globalDefault = ToolPermissionLevel.ASK
        store.setOverride("Write", ToolPermissionLevel.ALLOW)
        val w = tools.write("ok.txt", "data")
        assertTrue(w is FileToolResult.FileWritten)
        assertEquals("data", tmp.root.resolve("ok.txt").readText())
    }

    @Test
    fun forbidReadBlocksRead() {
        store.globalDefault = ToolPermissionLevel.ALLOW
        store.setOverride("Read", ToolPermissionLevel.FORBID)
        tmp.newFile("r.txt").writeText("secret")
        val r = tools.read("r.txt")
        assertTrue(r is FileToolResult.FileToolError)
        assertTrue((r as FileToolResult.FileToolError).message.contains("forbidden"))
    }

    @Test
    fun forbidGrepBlocksGrep() {
        store.globalDefault = ToolPermissionLevel.ALLOW
        store.setOverride("Grep", ToolPermissionLevel.FORBID)
        tmp.newFile("g.txt").writeText("needle")
        val r = tools.grep(".", "needle")
        assertTrue(r is FileToolResult.FileToolError)
    }

    @Test
    fun forbidGlobBlocksFind() {
        store.globalDefault = ToolPermissionLevel.ALLOW
        store.setOverride("Glob", ToolPermissionLevel.FORBID)
        tmp.newFile("x.kt").writeText("x")
        val r = tools.find(".", "*.kt")
        assertTrue(r is FileToolResult.FileToolError)
    }

    @Test
    fun editUsesEditPermission() {
        store.globalDefault = ToolPermissionLevel.ALLOW
        store.setOverride("Edit", ToolPermissionLevel.FORBID)
        tmp.newFile("e.txt").writeText("old text here")
        val r = tools.apply("e.txt", "old", "new")
        assertTrue(r is FileToolResult.FileToolError)
        assertTrue((r as FileToolResult.FileToolError).message.contains("forbidden"))
        // file unchanged
        assertEquals("old text here", tmp.root.resolve("e.txt").readText())
    }

    @Test
    fun allowPolicyLetsAllToolsThrough() {
        store.globalDefault = ToolPermissionLevel.ALLOW
        tmp.newFile("a.txt").writeText("hello world")
        assertTrue(tools.read("a.txt") is FileToolResult.FileContent)
        assertTrue(tools.grep(".", "hello") is FileToolResult.GrepMatches)
        assertTrue(tools.find(".", "*.kt") is FileToolResult.FoundFiles)
        assertTrue(tools.write("b.txt", "x") is FileToolResult.FileWritten)
        assertTrue(tools.apply("a.txt", "hello", "bye") is FileToolResult.FileApplied)
    }
}
