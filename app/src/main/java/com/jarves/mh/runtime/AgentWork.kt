package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

sealed interface AgentWorkEvent {
    data class Execution(
        val agent: AgentKind,
        val event: RuntimeEvent,
    ) : AgentWorkEvent
}

class AgentExecutionHandle internal constructor(
    val projectAgent: ProjectAgent,
    val sessionId: String,
    private val runtime: RuntimeBridge,
) {
    suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        runtime.respondToApproval(request, approved)
    }

    suspend fun stop() {
        runtime.stopSession(sessionId)
    }
}

class AgentWork(
    drivers: List<AgentDriver>,
    private val changeHistory: ProjectAgentChangeHistory,
) {
    private val driversByAgent = drivers.associateBy { it.kind }
    private val handles = ConcurrentHashMap<String, AgentExecutionHandle>()
    private val startingAgents = ConcurrentHashMap<AgentKind, ProjectAgent>()
    private val terminalSessions = ConcurrentHashMap.newKeySet<String>()

    val events: Flow<AgentWorkEvent> = channelFlow {
        driversByAgent.values.forEach { driver ->
            launch {
                try {
                    driver.runtime.events.collect { event ->
                        if (event is RuntimeEvent.SessionStarted) {
                            startingAgents[driver.kind]?.let { projectAgent ->
                                handles[event.sessionId] = AgentExecutionHandle(projectAgent, event.sessionId, driver.runtime)
                            }
                        }
                        if (event is RuntimeEvent.SessionCompleted || event is RuntimeEvent.SessionFailed) {
                            terminalSessions.add(event.sessionId)
                            handles.remove(event.sessionId)
                        }
                        send(AgentWorkEvent.Execution(driver.kind, event))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return@launch
                }
            }
        }
    }

    suspend fun start(
        projectAgent: ProjectAgent,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): AgentExecutionHandle {
        val runtime = driversByAgent.getValue(projectAgent.agent).runtime
        startingAgents[projectAgent.agent] = projectAgent
        val sessionId = try {
            runtime.startSession(
                projectAgent.projectId,
                projectSlug,
                projectKind,
                prompt,
                conversationHistory,
                provider,
            )
        } finally {
            startingAgents.remove(projectAgent.agent, projectAgent)
        }
        val handle = AgentExecutionHandle(projectAgent, sessionId, runtime)
        handles[sessionId] = handle
        if (terminalSessions.remove(sessionId)) handles.remove(sessionId, handle)
        return handle
    }

    fun handleFor(sessionId: String): AgentExecutionHandle? = handles[sessionId]

    fun configureProjectRoot(projectId: String, rootPath: String): Boolean =
        changeHistory.configureProjectRoot(projectId, rootPath)

    fun review(projectAgent: ProjectAgent): ChangeHistoryReview = changeHistory.review(projectAgent)

    fun pendingCounts(projectId: String, activeAgent: AgentKind): Map<AgentKind, Int> =
        changeHistory.pendingCounts(projectId, activeAgent)

    fun undo(projectAgent: ProjectAgent, selection: ChangeSelection): ChangeHistoryMutationResult =
        changeHistory.undo(projectAgent, selection)

    fun accept(projectAgent: ProjectAgent, selection: ChangeSelection): ChangeHistoryMutationResult =
        changeHistory.accept(projectAgent, selection)
}
