package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.RiskLevel
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentWorkTest {
    @Test
    fun executionHandleRoutesEventsApprovalAndStopToOneAdapter() = runBlocking {
        val bridge = FakeRuntimeBridge()
        val driver = object : AgentDriver {
            override val kind = AgentKind.CLAUDE_CODE
            override val runtime: RuntimeBridge = bridge
            override val capabilities = setOf(AgentCapability.INTERACTIVE_APPROVALS)
        }
        val work = AgentWork(listOf(driver), ProjectAgentChangeHistory(File("unused")))
        val event = async { work.events.first() }
        yield()

        val start = async {
            work.start(
                projectAgent = ProjectAgent("project", AgentKind.CLAUDE_CODE),
                projectSlug = "project",
                projectKind = ProjectKind.PROJECT,
                prompt = "hello",
                conversationHistory = emptyList<ChatMessage>(),
                provider = ProviderProfile(ProviderKind.ANTHROPIC),
            )
        }
        val firstEvent = event.await() as AgentWorkEvent.Execution
        val handle = requireNotNull(work.handleFor("session-1"))
        val request = ToolRequest(sessionId = handle.sessionId, toolName = "Write", risk = RiskLevel.REVIEW)
        handle.respondToApproval(request, true)
        handle.stop()
        start.await()

        assertEquals(AgentKind.CLAUDE_CODE, firstEvent.agent)
        assertEquals(RuntimeEvent.SessionStarted("session-1"), firstEvent.event)
        assertEquals(listOf("session-1"), bridge.approvals)
        assertEquals(listOf("session-1"), bridge.stopped)
    }
}

private class FakeRuntimeBridge : RuntimeBridge {
    private val release = CompletableDeferred<Unit>()
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 4)
    override val events: Flow<RuntimeEvent> = eventBus
    val approvals = mutableListOf<String>()
    val stopped = mutableListOf<String>()

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String {
        eventBus.emit(RuntimeEvent.SessionStarted("session-1"))
        release.await()
        return "session-1"
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        approvals += request.sessionId
    }

    override suspend fun stopSession(sessionId: String) {
        stopped += sessionId
        eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "Stopped by test"))
        release.complete(Unit)
    }

    override suspend fun stopActiveSession() = Unit
}
