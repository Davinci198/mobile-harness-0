package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectAgentChangeHistoryTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun reviewIsScopedToOneProjectAndAgent() {
        val filesDir = temporaryFolder.newFolder("files")
        val workspace = File(filesDir, "workspaces/project").apply { mkdirs() }
        File(workspace, "Main.kt").writeText("original")
        val store = WorkspaceCheckpoints(filesDir)
        val claude = store.forAgent(AgentKind.CLAUDE_CODE)
        val deepSeek = store.forAgent(AgentKind.DEEPSEEK_HARNESS)
        claude.createCheckpoint("project", workspace)
        File(workspace, "Main.kt").writeText("claude")
        claude.saveChangedPaths("project", listOf("Main.kt"), workspace)
        val history = ProjectAgentChangeHistory(filesDir)

        val claudeReview = history.review(ProjectAgent("project", AgentKind.CLAUDE_CODE))
        val deepSeekReview = history.review(ProjectAgent("project", AgentKind.DEEPSEEK_HARNESS))

        assertEquals(listOf("Main.kt"), claudeReview.changes.map { it.path })
        assertTrue(deepSeekReview.changes.isEmpty())
        assertEquals(
            AgentKind.entries.associateWith { if (it == AgentKind.CLAUDE_CODE) 1 else 0 },
            history.pendingCounts("project", AgentKind.CLAUDE_CODE),
        )
    }

    @Test
    fun undoConflictIsVisibleAndAcceptRemainsAvailable() {
        val filesDir = temporaryFolder.newFolder("files")
        val workspace = File(filesDir, "workspaces/project").apply { mkdirs() }
        File(workspace, "Main.kt").writeText("original")
        WorkspaceCheckpoints(filesDir).forAgent(AgentKind.CLAUDE_CODE).apply {
            createCheckpoint("project", workspace)
            File(workspace, "Main.kt").writeText("claude")
            saveChangedPaths("project", listOf("Main.kt"), workspace)
            File(workspace, "Main.kt").writeText("newer")
        }
        val history = ProjectAgentChangeHistory(filesDir)
        val projectAgent = ProjectAgent("project", AgentKind.CLAUDE_CODE)

        val undo = history.undo(projectAgent, ChangeSelection.Paths(setOf("Main.kt")))
        val accept = history.accept(projectAgent, ChangeSelection.Paths(setOf("Main.kt")))

        assertEquals(ChangeHistoryMutationStatus.CONFLICT, undo.status)
        assertEquals(listOf("Main.kt"), undo.conflicts)
        assertEquals("newer", File(workspace, "Main.kt").readText())
        assertEquals(ChangeHistoryMutationStatus.APPLIED, accept.status)
        assertTrue(history.review(projectAgent).changes.isEmpty())
    }
}
