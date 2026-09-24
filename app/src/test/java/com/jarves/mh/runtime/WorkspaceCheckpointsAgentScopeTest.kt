package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceCheckpointsAgentScopeTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var workspace: File

    @Before
    fun setUp() {
        filesDir = temporaryFolder.newFolder("files")
        workspace = File(filesDir, "workspaces/project").apply { mkdirs() }
    }

    @Test
    fun agentsHaveIndependentChangeHistories() {
        File(workspace, "Main.kt").writeText("original")
        val store = WorkspaceCheckpoints(filesDir)
        val claude = store.forAgent(AgentKind.CLAUDE_CODE)
        val deepSeek = store.forAgent(AgentKind.DEEPSEEK_HARNESS)

        claude.createCheckpoint("project", workspace)
        File(workspace, "Main.kt").writeText("changed by claude")
        claude.saveChangedPaths("project", listOf("Main.kt"), workspace)

        assertEquals(listOf("Main.kt"), claude.readChangedPaths("project"))
        assertTrue(deepSeek.readChangedPaths("project").isEmpty())
    }

    @Test
    fun historiesDoNotOverwriteEachOther() {
        File(workspace, "Main.kt").writeText("original")
        val store = WorkspaceCheckpoints(filesDir)
        val claude = store.forAgent(AgentKind.CLAUDE_CODE)
        val deepSeek = store.forAgent(AgentKind.DEEPSEEK_HARNESS)

        claude.createCheckpoint("project", workspace)
        File(workspace, "Main.kt").writeText("claude")
        claude.saveChangedPaths("project", listOf("Main.kt"), workspace)

        deepSeek.createCheckpoint("project", workspace)
        File(workspace, "Main.kt").writeText("deepseek")
        deepSeek.saveChangedPaths("project", listOf("Main.kt"), workspace)

        assertEquals("deepseek", File(workspace, "Main.kt").readText())
        assertEquals(listOf("Main.kt"), claude.readChangedPaths("project"))
        assertEquals(listOf("Main.kt"), deepSeek.readChangedPaths("project"))
    }

    @Test
    fun legacyHistoryMigratesOnceToActiveAgent() {
        val legacy = File(filesDir, "checkpoints/project/latest")
        File(legacy, "project").mkdirs()
        File(legacy, "project/Main.kt").writeText("original")
        File(legacy, "changes.json").writeText("[\"Main.kt\"]")
        val store = WorkspaceCheckpoints(filesDir)

        assertTrue(store.migrateLegacy("project", AgentKind.CLAUDE_CODE, workspace))
        assertFalse(store.migrateLegacy("project", AgentKind.CLAUDE_CODE, workspace))
        assertFalse(store.migrateLegacy("project", AgentKind.DEEPSEEK_HARNESS, workspace))
        assertEquals(listOf("Main.kt"), store.forAgent(AgentKind.CLAUDE_CODE).readChangedPaths("project"))
        assertTrue(store.forAgent(AgentKind.DEEPSEEK_HARNESS).readChangedPaths("project").isEmpty())
        assertFalse(legacy.exists())
    }

    @Test
    fun incompleteMigrationDestinationIsRecovered() {
        val legacy = File(filesDir, "checkpoints/project/latest")
        File(legacy, "project").mkdirs()
        File(legacy, "project/Main.kt").writeText("original")
        File(legacy, "changes.json").writeText("[\"Main.kt\"]")
        File(filesDir, "change-history/project/claude-code").mkdirs()
        val store = WorkspaceCheckpoints(filesDir)

        assertTrue(store.migrateLegacy("project", AgentKind.CLAUDE_CODE, workspace))
        assertFalse(legacy.exists())
        assertEquals(listOf("Main.kt"), store.forAgent(AgentKind.CLAUDE_CODE).readChangedPaths("project"))
    }

    @Test
    fun undoRejectsContentChangedAfterObservation() {
        File(workspace, "Main.kt").writeText("original")
        val history = WorkspaceCheckpoints(filesDir).forAgent(AgentKind.CLAUDE_CODE)
        history.createCheckpoint("project", workspace)
        File(workspace, "Main.kt").writeText("claude")
        history.saveChangedPaths("project", listOf("Main.kt"), workspace)
        File(workspace, "Main.kt").writeText("newer change")

        val result = history.undoFile("project", workspace, "Main.kt")

        assertEquals(WorkspaceChangeMutationStatus.CONFLICT, result.status)
        assertEquals(listOf("Main.kt"), result.conflicts)
        assertEquals("newer change", File(workspace, "Main.kt").readText())
    }

    @Test
    fun acceptClosesHistoryAfterContentChanged() {
        File(workspace, "Main.kt").writeText("original")
        val history = WorkspaceCheckpoints(filesDir).forAgent(AgentKind.CLAUDE_CODE)
        history.createCheckpoint("project", workspace)
        File(workspace, "Main.kt").writeText("claude")
        history.saveChangedPaths("project", listOf("Main.kt"), workspace)
        File(workspace, "Main.kt").writeText("newer change")

        val result = history.acceptFile("project", workspace, "Main.kt")

        assertEquals(WorkspaceChangeMutationStatus.APPLIED, result.status)
        assertTrue(result.conflicts.isEmpty())
        assertEquals("newer change", File(workspace, "Main.kt").readText())
        assertTrue(history.readChangedPaths("project").isEmpty())
    }

    @Test
    fun undoAllMakesNoPartialWritesWhenOnePathConflicts() {
        File(workspace, "Main.kt").writeText("original main")
        File(workspace, "Settings.kt").writeText("original settings")
        val history = WorkspaceCheckpoints(filesDir).forAgent(AgentKind.CLAUDE_CODE)
        history.createCheckpoint("project", workspace)
        File(workspace, "Main.kt").writeText("claude main")
        File(workspace, "Settings.kt").writeText("claude settings")
        history.saveChangedPaths("project", listOf("Main.kt", "Settings.kt"), workspace)
        File(workspace, "Settings.kt").writeText("newer settings")

        val result = history.undoAll("project", workspace)

        assertEquals(WorkspaceChangeMutationStatus.CONFLICT, result.status)
        assertEquals(listOf("Settings.kt"), result.conflicts)
        assertEquals("claude main", File(workspace, "Main.kt").readText())
        assertEquals("newer settings", File(workspace, "Settings.kt").readText())
    }

    @Test
    fun acceptAllClosesEveryObservedChange() {
        File(workspace, "Main.kt").writeText("original main")
        File(workspace, "Settings.kt").writeText("original settings")
        val history = WorkspaceCheckpoints(filesDir).forAgent(AgentKind.CLAUDE_CODE)
        history.createCheckpoint("project", workspace)
        File(workspace, "Main.kt").writeText("claude main")
        File(workspace, "Settings.kt").writeText("newer settings")
        history.saveChangedPaths("project", listOf("Main.kt", "Settings.kt"), workspace)

        val result = history.acceptAll("project", workspace)

        assertEquals(WorkspaceChangeMutationStatus.APPLIED, result.status)
        assertTrue(result.conflicts.isEmpty())
        assertEquals("claude main", File(workspace, "Main.kt").readText())
        assertEquals("newer settings", File(workspace, "Settings.kt").readText())
        assertTrue(history.readChangedPaths("project").isEmpty())
    }

    @Test
    fun projectRootChangeIsBlockedWhileHistoryExists() {
        File(workspace, "Main.kt").writeText("original")
        val history = WorkspaceCheckpoints(filesDir).forAgent(AgentKind.CLAUDE_CODE)
        history.createCheckpoint("project", workspace)
        File(workspace, "Main.kt").writeText("claude")
        history.saveChangedPaths("project", listOf("Main.kt"), workspace)

        history.configureProjectRoot("project", "nested")

        assertEquals(listOf("Main.kt"), history.readChangedPaths("project"))
        assertEquals(workspace.canonicalFile, history.ensureWorkspace("project").canonicalFile)
    }

    @Test
    fun configuredProjectRootPersistsAcrossStoreInstances() {
        val store = WorkspaceCheckpoints(filesDir).forAgent(AgentKind.CLAUDE_CODE)
        store.configureProjectRoot("project", "nested")

        val restartedStore = WorkspaceCheckpoints(filesDir).forAgent(AgentKind.CLAUDE_CODE)
        assertEquals(File(workspace, "nested").canonicalFile, restartedStore.ensureWorkspace("project").canonicalFile)
    }

    @Test
    fun firstRootMetadataInitializesAlongsideLegacyHistory() {
        val legacy = File(filesDir, "checkpoints/project/latest")
        File(legacy, "project").mkdirs()
        File(legacy, "changes.json").writeText("[]")
        val store = WorkspaceCheckpoints(filesDir).forAgent(AgentKind.CLAUDE_CODE)

        assertTrue(store.configureProjectRoot("project", "nested"))
        assertEquals(File(workspace, "nested").canonicalFile, store.ensureWorkspace("project").canonicalFile)
    }

    @Test
    fun laterRunDoesNotAdoptExternalChangeToOlderPendingPath() {
        File(workspace, "Main.kt").writeText("original")
        File(workspace, "Settings.kt").writeText("original settings")
        val history = WorkspaceCheckpoints(filesDir).forAgent(AgentKind.CLAUDE_CODE)
        history.createCheckpoint("project", workspace)
        File(workspace, "Main.kt").writeText("claude")
        history.saveChangedPaths("project", listOf("Main.kt"), workspace)
        File(workspace, "Main.kt").writeText("external")
        File(workspace, "Settings.kt").writeText("claude settings")

        history.saveChangedPaths("project", listOf("Settings.kt"), workspace)
        val result = history.undoFile("project", workspace, "Main.kt")

        assertEquals(WorkspaceChangeMutationStatus.CONFLICT, result.status)
        assertEquals("external", File(workspace, "Main.kt").readText())
    }
}
