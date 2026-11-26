package com.jarves.mh.runtime

import com.jarves.mh.model.RiskLevel
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript writer batches live deltas and writes immediately on a turn
 * boundary. Getting the boundary set wrong either rewrites the whole chat JSON
 * on every token (the old behaviour) or leaves a finished turn unsaved until
 * the process dies.
 *
 * Mirrors the predicate used by MainViewModel.onRuntimeEvent; keep in sync.
 */
class TranscriptWritePolicyTest {

    /** True when the event must be persisted without waiting for the debounce. */
    private fun isBoundary(event: RuntimeEvent): Boolean =
        event !is RuntimeEvent.AssistantDelta && event !is RuntimeEvent.ReasoningProgress

    private val session = "session-1"

    @Test
    fun `streaming deltas stay on the debounce window`() {
        assertFalse(isBoundary(RuntimeEvent.AssistantDelta(session, "hel")))
        assertFalse(isBoundary(RuntimeEvent.AssistantDelta(session, "lo")))
        assertFalse(isBoundary(RuntimeEvent.ReasoningProgress(session, estimatedTokens = 120)))
    }

    @Test
    fun `every other event flushes immediately`() {
        val flushing = listOf(
            RuntimeEvent.SessionStarted(session),
            RuntimeEvent.ToolRequested(session, toolRequest()),
            RuntimeEvent.ToolCompleted(session, "Bash", "ok"),
            RuntimeEvent.QuestionsAnswered(session, "call-1"),
            RuntimeEvent.ReasoningSummary(session, "plan", blockId = 1L),
            RuntimeEvent.FilesChanged(session, emptyList()),
            RuntimeEvent.PreviewStarted(session, "http://127.0.0.1:5173"),
            RuntimeEvent.SessionCompleted(session),
            RuntimeEvent.SessionFailed(session, "network"),
        )
        val missed = flushing.filter { !isBoundary(it) }
        assertTrue("these events must not wait for the debounce: $missed", missed.isEmpty())
    }

    private fun toolRequest(): ToolRequest = ToolRequest(
        approvalId = "req-1",
        sessionId = session,
        toolName = "Bash",
        explanation = "List files",
        commandPreview = "ls",
        risk = RiskLevel.REVIEW,
    )
}
