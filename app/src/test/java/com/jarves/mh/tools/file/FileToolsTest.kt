package com.jarves.mh.tools.file

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** In-memory storage for FileTools gate tests (no Android). */
private class MemStore : com.jarves.mh.tools.ToolPermissionStorage {
    val map = mutableMapOf<String, String>()
    override fun read(key: String): String? = map[key]
    override fun write(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class FileToolsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: ToolPermissionStore
    private lateinit var gate: ToolPermissionGate
    private lateinit var tools: FileTools

    @Before
    fun setUp() {
        store = ToolPermissionStore(MemStore())
        store.globalDefault = ToolPermissionLevel.ALLOW
        gate = ToolPermissionGate(store)
        tools = FileTools(tmp.root, gate)
    }

    // --- read ---

    @Test
    fun readReturnsNumberedLines() {
        tmp.newFile("a.txt").writeText("one\ntwo\nthree")
        val r = tools.read("a.txt", 1, 10)
        assertTrue(r is FileToolResult.FileContent)
        r as FileToolResult.FileContent
        assertEquals(3, r.totalLines)
        assertTrue(r.content.contains("1| one"))
        assertTrue(r.content.contains("2| two"))
        assertTrue(r.content.contains("3| three"))
        assertFalseTruncated(r)
    }

    @Test
    fun readMissingFileIsError() {
        val r = tools.read("nope.txt")
        assertTrue(r is FileToolResult.FileToolError)
    }

    @Test
    fun readOutsideSandboxIsError() {
        val r = tools.read("../secret.txt")
        assertTrue(r is FileToolResult.FileToolError)
        assertTrue((r as FileToolResult.FileToolError).message.contains("escapes"))
    }

    // --- write ---

    @Test
    fun writeCreatesFile() {
        val r = tools.write("dir/new.txt", "hello")
        assertTrue(r is FileToolResult.FileWritten)
        assertEquals("hello", tmp.root.resolve("dir/new.txt").readText())
    }

    @Test
    fun writeAppendAddsContent() {
        tmp.newFile("app.txt").writeText("base")
        tools.write("app.txt", "+more", append = true)
        assertEquals("base+more", tmp.root.resolve("app.txt").readText())
    }

    @Test
    fun writeOutsideSandboxIsError() {
        val r = tools.write("../evil.txt", "x")
        assertTrue(r is FileToolResult.FileToolError)
    }

    // --- apply ---

    @Test
    fun applyReplacesText() {
        tmp.newFile("code.kt").writeText("val x = 1\nval y = 2")
        val r = tools.apply("code.kt", "val x = 1", "val x = 99")
        assertTrue(r is FileToolResult.FileApplied)
        assertEquals(1, (r as FileToolResult.FileApplied).replacements)
        assertEquals("val x = 99\nval y = 2", tmp.root.resolve("code.kt").readText())
    }

    @Test
    fun applyMultipleOccurrencesReplacesAll() {
        tmp.newFile("m.txt").writeText("aa bb aa")
        val r = tools.apply("m.txt", "aa", "cc")
        assertTrue(r is FileToolResult.FileApplied)
        assertEquals(2, (r as FileToolResult.FileApplied).replacements)
        assertEquals("cc bb cc", tmp.root.resolve("m.txt").readText())
    }

    @Test
    fun applyMissingOldIsError() {
        tmp.newFile("n.txt").writeText("content")
        val r = tools.apply("n.txt", "not-there", "x")
        assertTrue(r is FileToolResult.FileToolError)
    }

    // --- grep ---

    @Test
    fun grepFindsMatches() {
        tmp.newFile("s.kt").writeText("fun main() {}\n// TODO fix\nfun other() {}")
        val r = tools.grep(".", "fun ")
        assertTrue(r is FileToolResult.GrepMatches)
        r as FileToolResult.GrepMatches
        assertEquals(2, r.matches.size)
        assertEquals(1, r.matches[0].lineNumber)
        assertEquals(3, r.matches[1].lineNumber)
    }

    @Test
    fun grepCaseInsensitive() {
        tmp.newFile("c.txt").writeText("Hello World")
        val r = tools.grep(".", "hello", caseInsensitive = true)
        assertEquals(1, (r as FileToolResult.GrepMatches).matches.size)
    }

    @Test
    fun grepInvalidRegexIsError() {
        val r = tools.grep(".", "[unclosed")
        assertTrue(r is FileToolResult.FileToolError)
    }

    // --- find ---

    @Test
    fun findMatchesGlob() {
        tmp.newFolder("src")
        tmp.newFile("src/Main.kt").writeText("x")
        tmp.newFile("src/Util.kt").writeText("y")
        tmp.newFile("src/readme.md").writeText("z")
        val r = tools.find(".", "*.kt")
        assertTrue(r is FileToolResult.FoundFiles)
        r as FileToolResult.FoundFiles
        assertEquals(2, r.files.size)
        assertTrue(r.files.all { it.endsWith(".kt") })
    }

    @Test
    fun findRespectsMaxDepth() {
        tmp.newFile("root.kt").writeText("0")
        tmp.newFolder("a")
        tmp.newFolder("a", "b")
        tmp.newFile("a/top.kt").writeText("1")
        tmp.newFile("a/b/deep.kt").writeText("2")
        // depths from ".": root.kt=1, a/top.kt=2, a/b/deep.kt=3
        val shallow = tools.find(".", "*.kt", maxDepth = 1)
        assertEquals(listOf("root.kt"), (shallow as FileToolResult.FoundFiles).files)
        val mid = tools.find(".", "*.kt", maxDepth = 2)
        assertEquals(2, mid.let { (it as FileToolResult.FoundFiles).files.size })
        val deep = tools.find(".", "*.kt", maxDepth = 3)
        assertEquals(3, (deep as FileToolResult.FoundFiles).files.size)
    }

    // --- gate integration ---

    @Test
    fun forbidBlocksWrite() {
        store.globalDefault = ToolPermissionLevel.FORBID
        val r = tools.write("x.txt", "data")
        assertTrue(r is FileToolResult.FileToolError)
        assertTrue((r as FileToolResult.FileToolError).message.contains("forbidden"))
        assertTrue(!tmp.root.resolve("x.txt").exists())
    }

    @Test
    fun askWriteNeedsApproval() {
        store.globalDefault = ToolPermissionLevel.ASK
        val r = tools.write("x.txt", "data")
        assertTrue(r is FileToolResult.FileToolError)
        assertTrue((r as FileToolResult.FileToolError).message.contains("Approval"))
    }

    @Test
    fun allowReadWorks() {
        store.globalDefault = ToolPermissionLevel.ALLOW
        tmp.newFile("ok.txt").writeText("line")
        val r = tools.read("ok.txt")
        assertTrue(r is FileToolResult.FileContent)
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
        val gatedTools = FileTools(tmp.root, ToolPermissionGate(store), listOf(hook))
        tmp.newFile("h.txt").writeText("data")
        gatedTools.read("h.txt")
        assertTrue(events.contains("start:Read"))
        assertTrue(events.contains("result:Read:true"))
        assertTrue(events.contains("end:Read"))
    }

    @Test
    fun globToRegexBasics() {
        val re = FileTools.globToRegex("*.kt")
        assertTrue(re.matches("Main.kt"))
        assertTrue(!re.matches("src/Main.kt"))
        val deep = FileTools.globToRegex("**/*.kt")
        assertTrue(deep.matches("src/Main.kt"))
        val alt = FileTools.globToRegex("*.{kt,java}")
        assertTrue(alt.matches("A.kt"))
        assertTrue(alt.matches("B.java"))
        assertTrue(!alt.matches("C.md"))
    }

    private fun assertFalseTruncated(r: FileToolResult.FileContent) {
        assertTrue(!r.truncated)
    }
}
