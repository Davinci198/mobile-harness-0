package com.jarves.mh.workflows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkflowStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sample(id: String = "check_status") = AgentWorkflow(
        id = id,
        name = "Check Status",
        description = "Runs a status check",
        matchPhrases = listOf("check status", "status check"),
        steps = listOf(
            WorkflowStep("Calculate", mapOf("expression" to "2+2")),
            WorkflowStep("Sleep", mapOf("durationMs" to "10")),
        ),
        createdAtMillis = 123L,
        updatedAtMillis = 456L,
    )

    @Test
    fun saveThenLoadRoundTrips() {
        val store = WorkflowStore(temp.newFolder("workflows"))
        assertTrue(store.save(sample()))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(sample(), loaded.first())
    }

    @Test
    fun loadSkipsCorruptFiles() {
        val dir = temp.newFolder("workflows")
        File(dir, "good.json").writeText(AgentWorkflowJson.serialize(sample("good")))
        File(dir, "bad.json").writeText("{not json at all")
        File(dir, "empty.json").writeText("{}")
        File(dir, "notes.txt").writeText("ignored")
        val loaded = WorkflowStore(dir).load()
        assertEquals(listOf("good"), loaded.map { it.id })
    }

    @Test
    fun loadOnMissingDirectoryIsEmpty() {
        val store = WorkflowStore(File(temp.root, "does-not-exist"))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun deleteRemovesWorkflow() {
        val store = WorkflowStore(temp.newFolder("workflows"))
        store.save(sample())
        assertTrue(store.delete("check_status"))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun sanitizeFileNameBlocksPathTraversal() {
        val store = WorkflowStore(temp.newFolder("workflows"))
        store.save(sample(id = "../evil"))
        assertTrue(store.load().isEmpty() || store.load().first().id == "../evil")
        val onDisk = File(temp.root, "workflows").listFiles()!!.map { it.name }
        assertTrue(onDisk.none { it.contains("..") || it.contains('/') })
    }
}
