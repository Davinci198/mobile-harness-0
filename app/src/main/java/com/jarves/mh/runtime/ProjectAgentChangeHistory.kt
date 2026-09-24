package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import java.io.File

data class ProjectAgent(
    val projectId: String,
    val agent: AgentKind,
)

sealed interface ChangeSelection {
    data object All : ChangeSelection
    data class Paths(val paths: Set<String>) : ChangeSelection
}

enum class ChangeHistoryMutationStatus {
    APPLIED,
    UNCHANGED,
    CONFLICT,
}

data class ChangeHistoryReview(
    val projectAgent: ProjectAgent,
    val changes: List<ChangeItem>,
)

data class ChangeHistoryMutationResult(
    val status: ChangeHistoryMutationStatus,
    val review: ChangeHistoryReview? = null,
    val conflicts: List<String> = emptyList(),
)

class ProjectAgentChangeHistory(filesDir: File) {
    private val checkpoints = WorkspaceCheckpoints(filesDir)

    fun configureProjectRoot(projectId: String, rootPath: String): Boolean =
        checkpoints.configureProjectRoot(projectId, rootPath)

    fun review(projectAgent: ProjectAgent): ChangeHistoryReview {
        val store = store(projectAgent)
        val workspace = store.ensureWorkspace(projectAgent.projectId)
        store.prepare(projectAgent.projectId, workspace)
        val changes = store.readChangedPaths(projectAgent.projectId)
            .filterNot(store::isInternalRuntimePath)
            .let { store.buildChangeDetails(projectAgent.projectId, workspace, it) }
        return ChangeHistoryReview(projectAgent, changes)
    }

    fun pendingCounts(projectId: String, activeAgent: AgentKind): Map<AgentKind, Int> {
        review(ProjectAgent(projectId, activeAgent))
        return AgentKind.entries.associateWith { review(ProjectAgent(projectId, it)).changes.size }
    }

    fun undo(projectAgent: ProjectAgent, selection: ChangeSelection): ChangeHistoryMutationResult {
        val store = store(projectAgent)
        val workspace = store.ensureWorkspace(projectAgent.projectId)
        val result = when (selection) {
            ChangeSelection.All -> store.undoAll(projectAgent.projectId, workspace)
            is ChangeSelection.Paths -> store.undoPaths(projectAgent.projectId, workspace, selection.paths)
        }
        return result.toHistoryResult(projectAgent)
    }

    fun accept(projectAgent: ProjectAgent, selection: ChangeSelection): ChangeHistoryMutationResult {
        val store = store(projectAgent)
        val workspace = store.ensureWorkspace(projectAgent.projectId)
        val result = when (selection) {
            ChangeSelection.All -> store.acceptAll(projectAgent.projectId, workspace)
            is ChangeSelection.Paths -> store.acceptPaths(projectAgent.projectId, workspace, selection.paths)
        }
        return result.toHistoryResult(projectAgent)
    }

    private fun store(projectAgent: ProjectAgent): WorkspaceCheckpoints =
        checkpoints.forAgent(projectAgent.agent)

    private fun WorkspaceChangeMutationResult.toHistoryResult(projectAgent: ProjectAgent): ChangeHistoryMutationResult =
        ChangeHistoryMutationResult(
            status = when (status) {
                WorkspaceChangeMutationStatus.APPLIED -> ChangeHistoryMutationStatus.APPLIED
                WorkspaceChangeMutationStatus.UNCHANGED -> ChangeHistoryMutationStatus.UNCHANGED
                WorkspaceChangeMutationStatus.CONFLICT -> ChangeHistoryMutationStatus.CONFLICT
            },
            review = if (status == WorkspaceChangeMutationStatus.APPLIED) review(projectAgent) else null,
            conflicts = conflicts,
        )
}
