package com.jarves.mh.tools.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryJsonStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingStoreLoadsEmpty() {
        val store = MemoryJsonStore(temporaryFolder.root.resolve("missing/memory.json"))
        assertEquals(MemoryData(), store.load())
    }

    @Test
    fun roundTripPreservesMemoriesAndLinks() {
        val file = temporaryFolder.root.resolve("memory.json")
        val store = MemoryJsonStore(file)
        val data = MemoryData(
            memories = listOf(
                memory(),
                memory("00000000-0000-0000-0000-000000000002", "Target"),
            ),
            links = listOf(link()),
        )
        store.save(data)
        assertEquals(data, MemoryJsonStore(file).load())
    }

    @Test
    fun invalidJsonIsRejected() {
        val file = temporaryFolder.newFile("bad.json")
        file.writeText("not-json")
        val error = runCatching { MemoryJsonStore(file).load() }.exceptionOrNull()
        assertTrue(error is MemoryStoreException)
    }

    @Test
    fun unsupportedSchemaIsRejected() {
        val file = temporaryFolder.newFile("schema.json")
        file.writeText(JSONObject().put("schemaVersion", 99).toString())
        val error = runCatching { MemoryJsonStore(file).load() }.exceptionOrNull()
        assertTrue(error?.message?.contains("Unsupported") == true)
    }

    @Test
    fun danglingLinkIsRejected() {
        val file = temporaryFolder.newFile("dangling.json")
        file.writeText(JSONObject()
            .put("schemaVersion", 1)
            .put("memories", org.json.JSONArray().put(memory().toJson()))
            .put("links", org.json.JSONArray().put(link().toJson()))
            .toString())
        val error = runCatching { MemoryJsonStore(file).load() }.exceptionOrNull()
        assertTrue(error?.message?.contains("Dangling") == true)
    }

    private fun memory(
        id: String = "00000000-0000-0000-0000-000000000001",
        title: String = "Title",
    ) = MemoryRecord(
        id = id,
        title = title,
        content = "Content",
        path = "work/project",
        tags = listOf("one", "two"),
        createdAtMillis = 1,
        updatedAtMillis = 2,
    )

    private fun MemoryRecord.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("content", content)
        .put("path", path)
        .put("tags", org.json.JSONArray(tags))
        .put("createdAtMillis", createdAtMillis)
        .put("updatedAtMillis", updatedAtMillis)

    private fun link() = MemoryLink(
        id = "00000000-0000-0000-0000-000000000003",
        fromId = memory().id,
        toId = "00000000-0000-0000-0000-000000000002",
        type = "related",
        description = "description",
        createdAtMillis = 3,
    )

    private fun MemoryLink.toJson() = JSONObject()
        .put("id", id)
        .put("fromId", fromId)
        .put("toId", toId)
        .put("type", type)
        .put("description", description)
        .put("createdAtMillis", createdAtMillis)
}
