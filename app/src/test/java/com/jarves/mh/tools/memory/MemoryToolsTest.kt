package com.jarves.mh.tools.memory

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStorage
import com.jarves.mh.tools.ToolPermissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class MemoryMemoryStore : MemoryStore {
    var data = MemoryData()
    var failLoad = false
    override fun load(): MemoryData {
        if (failLoad) throw MemoryCorruptStoreException("corrupt")
        return data
    }
    override fun save(data: MemoryData) {
        this.data = data
    }
}

class MemoryToolsTest {
    private lateinit var memoryStore: MemoryMemoryStore
    private lateinit var permissionStore: ToolPermissionStore
    private lateinit var ids: ArrayDeque<String>
    private lateinit var tools: MemoryTools
    private var now = 100L

    @Before
    fun setUp() {
        memoryStore = MemoryMemoryStore()
        permissionStore = ToolPermissionStore(object : ToolPermissionStorage {
            private val values = mutableMapOf<String, String>()
            override fun read(key: String): String? = values[key]
            override fun write(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        })
        permissionStore.globalDefault = ToolPermissionLevel.ALLOW
        ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000004",
                "00000000-0000-0000-0000-000000000005",
                "00000000-0000-0000-0000-000000000006",
            ),
        )
        tools = newTools()
    }

    private fun newTools(hooks: List<AIToolHook> = emptyList()) = MemoryTools(
        store = memoryStore,
        gate = ToolPermissionGate(permissionStore),
        hooks = hooks,
        clock = { now++ },
        idFactory = { ids.removeFirst() },
    )

    private fun created(title: String, content: String, path: String = "", tags: List<String> = emptyList()): MemoryRecord {
        val result = tools.create(title, content, path, tags)
        assertTrue(result.toString(), result is MemoryToolResult.Created)
        return (result as MemoryToolResult.Created).memory
    }

    @Test
    fun createReadUpdateDelete() {
        val created = created(" Title ", "Body", "work/project", listOf("Tag", "tag", "Other"))
        assertEquals("Title", created.title)
        assertEquals(listOf("Tag", "Other"), created.tags)
        assertTrue(tools.read(created.id) is MemoryToolResult.Found)

        val updated = tools.update(created.id, content = "Changed", tags = emptyList())
        assertEquals("Changed", (updated as MemoryToolResult.Updated).memory.content)
        assertTrue(updated.memory.tags.isEmpty())
        assertEquals("Title", updated.memory.title)

        val deleted = tools.delete(created.id)
        assertEquals(0, (deleted as MemoryToolResult.Deleted).removedLinks)
        assertTrue(tools.read(created.id) is MemoryToolResult.Error)
    }

    @Test
    fun duplicateTitlesHaveDistinctIds() {
        val first = created("Same", "one")
        val second = created("Same", "two")
        assertTrue(first.id != second.id)
        assertEquals(2, memoryStore.data.memories.size)
    }

    @Test
    fun createValidatesFieldsAndPaths() {
        assertError(tools.create("", "body"), MemoryErrorCode.INVALID_INPUT)
        assertError(tools.create("Title", ""), MemoryErrorCode.INVALID_INPUT)
        assertError(tools.create("Title", "body", "../escape"), MemoryErrorCode.INVALID_INPUT)
        assertError(tools.create("Title", "body", tags = listOf("")), MemoryErrorCode.INVALID_INPUT)
        assertError(tools.create("T".repeat(MemoryToolLimits.MAX_TITLE_CHARS + 1), "body"), MemoryErrorCode.INVALID_INPUT)
        assertTrue(memoryStore.data.memories.isEmpty())
    }

    @Test
    fun idsAreValidated() {
        assertError(tools.read("bad-id"), MemoryErrorCode.INVALID_INPUT)
        assertError(tools.update("bad-id", title = "x"), MemoryErrorCode.INVALID_INPUT)
        assertError(tools.delete("bad-id"), MemoryErrorCode.INVALID_INPUT)
    }

    @Test
    fun updateWithoutChangesDoesNotAdvanceTimestamp() {
        val memory = created("Title", "body")
        val result = tools.update(memory.id, title = "Title") as MemoryToolResult.Updated
        assertEquals(memory.updatedAtMillis, result.memory.updatedAtMillis)
    }

    @Test
    fun searchIsCaseInsensitiveAndRanksTitleFirst() {
        val body = created("Other", "alpha and beta")
        val title = created("Alpha", "unrelated")
        val result = tools.search("ALPHA") as MemoryToolResult.SearchResults
        assertEquals(2, result.total)
        assertEquals(title.id, result.items.first().id)
        assertEquals(body.id, result.items.last().id)
    }

    @Test
    fun searchAndListValidatePagination() {
        assertError(tools.search(""), MemoryErrorCode.INVALID_INPUT)
        assertError(tools.search("x", 0), MemoryErrorCode.INVALID_INPUT)
        assertError(tools.search("x", MemoryToolLimits.MAX_RESULTS + 1), MemoryErrorCode.INVALID_INPUT)
        assertError(tools.list(limit = 10, offset = -1), MemoryErrorCode.INVALID_INPUT)
    }

    @Test
    fun listFiltersNestedPathsAndPaginates() {
        val root = created("Root", "body")
        val nested = created("Nested", "body", "work/project")
        val deeper = created("Deeper", "body", "work/project/deep")
        created("Other", "body", "archive")
        val result = tools.list("work/project", limit = 1, offset = 1) as MemoryToolResult.Memories
        assertEquals(2, result.total)
        assertEquals(1, result.items.size)
        assertEquals(nested.id, result.items.single().id)
    }

    @Test
    fun linksAreValidatedAndIdempotent() {
        val source = created("Source", "body")
        val target = created("Target", "body")
        val first = tools.link(source.id, target.id, "related", "first") as MemoryToolResult.Linked
        assertTrue(first.created)
        val duplicate = tools.link(source.id, target.id, "related", "changed") as MemoryToolResult.Linked
        assertTrue(!duplicate.created)
        assertEquals("first", duplicate.link.description)
        assertEquals(1, memoryStore.data.links.size)
        assertError(tools.link(source.id, source.id), MemoryErrorCode.INVALID_INPUT)
        assertError(tools.link(source.id, target.id, "bad type"), MemoryErrorCode.INVALID_INPUT)
    }

    @Test
    fun samePairCanHaveDifferentLinkTypes() {
        val source = created("Source", "body")
        val target = created("Target", "body")
        tools.link(source.id, target.id, "related")
        tools.link(source.id, target.id, "depends-on")
        assertEquals(2, memoryStore.data.links.size)
    }

    @Test
    fun listLinksRejectsMissingMemory() {
        assertError(
            tools.listLinks("00000000-0000-0000-0000-000000000099"),
            MemoryErrorCode.NOT_FOUND,
        )
    }

    @Test
    fun listLinksIncludesIncomingAndOutgoing() {
        val source = created("Source", "body")
        val target = created("Target", "body")
        val link = (tools.link(source.id, target.id) as MemoryToolResult.Linked).link
        assertEquals(1, (tools.listLinks(target.id) as MemoryToolResult.Links).total)
        val unlinked = tools.unlink(link.id)
        assertEquals(link.id, (unlinked as MemoryToolResult.Unlinked).linkId)
        assertTrue(memoryStore.data.links.isEmpty())
    }

    @Test
    fun deletingMemoryCascadesBothDirections() {
        val source = created("Source", "body")
        val target = created("Target", "body")
        tools.link(source.id, target.id)
        val deleted = tools.delete(source.id) as MemoryToolResult.Deleted
        assertEquals(1, deleted.removedLinks)
        assertTrue(memoryStore.data.links.isEmpty())
    }

    @Test
    fun askAndForbidDoNotMutateStore() {
        permissionStore.globalDefault = ToolPermissionLevel.ASK
        assertError(tools.create("Title", "body"), MemoryErrorCode.PERMISSION_REQUIRED)
        permissionStore.globalDefault = ToolPermissionLevel.FORBID
        assertError(tools.create("Title", "body"), MemoryErrorCode.PERMISSION_DENIED)
        assertTrue(memoryStore.data.memories.isEmpty())
    }

    @Test
    fun perToolOverrideWinsOverGlobalAsk() {
        permissionStore.globalDefault = ToolPermissionLevel.ASK
        permissionStore.setOverride("MemoryCreate", ToolPermissionLevel.ALLOW)
        assertTrue(tools.create("Title", "body") is MemoryToolResult.Created)
    }

    @Test
    fun hooksFireLifecycle() {
        val events = mutableListOf<String>()
        val hooks = listOf(object : AIToolHook {
            override fun onToolExecutionStarted(toolName: String) { events += "start:$toolName" }
            override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) {
                events += "result:$toolName:$success"
            }
            override fun onToolExecutionFinished(toolName: String) { events += "end:$toolName" }
        })
        newTools(hooks).create("Title", "body")
        assertEquals(listOf("start:MemoryCreate", "result:MemoryCreate:true", "end:MemoryCreate"), events)
    }

    @Test
    fun throwingHooksDoNotChangeCommittedResult() {
        val hooks = listOf(object : AIToolHook {
            override fun onToolExecutionStarted(toolName: String) = throw IllegalStateException("hook")
            override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) = throw IllegalStateException("hook")
            override fun onToolExecutionFinished(toolName: String) = throw IllegalStateException("hook")
        })
        assertTrue(newTools(hooks).create("Title", "body") is MemoryToolResult.Created)
    }

    @Test
    fun corruptStoreReturnsStructuredError() {
        memoryStore.failLoad = true
        assertError(tools.list(), MemoryErrorCode.CORRUPT_STORE)
    }

    private fun assertError(result: MemoryToolResult, code: MemoryErrorCode) {
        assertTrue(result.toString(), result is MemoryToolResult.Error)
        assertEquals(code, (result as MemoryToolResult.Error).code)
    }
}
