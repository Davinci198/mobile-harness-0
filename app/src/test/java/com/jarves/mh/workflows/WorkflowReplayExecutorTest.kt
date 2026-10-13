package com.jarves.mh.workflows

import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStore
import com.jarves.mh.tools.ToolPermissionStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowReplayExecutorTest {

    private class MemoryStorage : ToolPermissionStorage {
        val map = mutableMapOf<String, String>()
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private fun gateWith(global: ToolPermissionLevel): ToolPermissionGate {
        val store = ToolPermissionStore(MemoryStorage())
        store.globalDefault = global
        return ToolPermissionGate(store)
    }

    private fun workflow(vararg steps: WorkflowStep) =
        AgentWorkflow("wf", "My Workflow", "", listOf("my workflow"), steps.toList())

    @Test
    fun lowRiskToolsRunWithoutApprovalInOrder() = runBlocking {
        val gate = gateWith(ToolPermissionLevel.ALLOW)
        val ran = mutableListOf<String>()
        val approvals = mutableListOf<ToolRequest>()
        val events = mutableListOf<RuntimeEvent>()
        val executor = WorkflowReplayExecutor(
            gate = gate,
            sessionId = "wf-test",
            emit = { events += it },
            requestApproval = { approvals += it; true },
            runStep = { step -> ran += step.toolName; StepOutcome(true, "${step.toolName} ok") },
        )
        val outcome = executor.run(
            workflow(
                WorkflowStep("Calculate", mapOf("expression" to "1+1")),
                WorkflowStep("Sleep", mapOf("durationMs" to "1")),
                WorkflowStep("Read", mapOf("path" to "notes.txt")),
            ),
        )
        assertTrue(outcome is ReplayOutcome.Completed)
        assertEquals(listOf("Calculate", "Sleep", "Read"), ran)
        assertTrue(approvals.isEmpty())
        assertEquals(3, events.filterIsInstance<RuntimeEvent.ToolStarted>().size)
        assertEquals(3, events.filterIsInstance<RuntimeEvent.ToolCompleted>().size)
    }

    @Test
    fun nonLowToolUnderGlobalAllowStillRequestsApproval() = runBlocking {
        val gate = gateWith(ToolPermissionLevel.ALLOW)
        val approvals = mutableListOf<ToolRequest>()
        var gatePassedInsideTool = false
        val executor = WorkflowReplayExecutor(
            gate = gate,
            sessionId = "wf-test",
            emit = {},
            requestApproval = { approvals += it; true },
            runStep = {
                // Simulates the tool's own internal gate check.
                gatePassedInsideTool =
                    gate.evaluate("Bash", "run") is ToolPermissionGateDecision.Allowed
                StepOutcome(true, "ran")
            },
        )
        val outcome = executor.run(workflow(WorkflowStep("Bash", mapOf("command" to "ls"))))
        assertTrue(outcome is ReplayOutcome.Completed)
        assertEquals(1, approvals.size)
        assertEquals("Bash", approvals.first().toolName)
        assertEquals("wf-test", approvals.first().sessionId)
        assertTrue("tool-internal gate should pass after approval", gatePassedInsideTool)
        // Grant is one-shot: next evaluation asks again.
        assertTrue(gate.evaluate("Bash", "again") is ToolPermissionGateDecision.NeedsApproval)
    }

    @Test
    fun rejectedApprovalStopsBeforeAnyStepRuns() = runBlocking {
        val gate = gateWith(ToolPermissionLevel.ALLOW)
        val ran = mutableListOf<String>()
        val events = mutableListOf<RuntimeEvent>()
        val executor = WorkflowReplayExecutor(
            gate = gate,
            sessionId = "wf-test",
            emit = { events += it },
            requestApproval = { false },
            runStep = { ran += it.toolName; StepOutcome(true, "ok") },
        )
        val outcome = executor.run(
            workflow(
                WorkflowStep("ClipboardCopy", mapOf("text" to "hi")),
                WorkflowStep("Calculate", mapOf("expression" to "2+2")),
            ),
        )
        assertTrue(outcome is ReplayOutcome.Rejected)
        assertEquals(0, (outcome as ReplayOutcome.Rejected).stepIndex)
        assertTrue(ran.isEmpty())
        assertEquals(1, events.filterIsInstance<RuntimeEvent.ToolRejected>().size)
    }

    @Test
    fun forbiddenToolStopsWithoutPrompt() = runBlocking {
        val gate = gateWith(ToolPermissionLevel.FORBID)
        val approvals = mutableListOf<ToolRequest>()
        val executor = WorkflowReplayExecutor(
            gate = gate,
            sessionId = "wf-test",
            emit = {},
            requestApproval = { approvals += it; true },
            runStep = { StepOutcome(true, "should not run") },
        )
        val outcome = executor.run(workflow(WorkflowStep("Read", mapOf("path" to "x"))))
        assertTrue(outcome is ReplayOutcome.Failed)
        outcome as ReplayOutcome.Failed
        assertEquals(0, outcome.stepIndex)
        assertTrue(outcome.reason.contains("forbidden"))
        assertTrue(approvals.isEmpty())
    }

    @Test
    fun failingStepStopsAndLaterStepsDoNotRun() = runBlocking {
        val gate = gateWith(ToolPermissionLevel.ALLOW)
        val ran = mutableListOf<String>()
        val executor = WorkflowReplayExecutor(
            gate = gate,
            sessionId = "wf-test",
            emit = {},
            requestApproval = { true },
            runStep = { step ->
                ran += step.toolName
                if (step.toolName == "Write") StepOutcome(false, "disk full") else StepOutcome(true, "ok")
            },
        )
        val outcome = executor.run(
            workflow(
                WorkflowStep("Calculate", mapOf("expression" to "1")),
                WorkflowStep("Write", mapOf("path" to "a", "content" to "b")),
                WorkflowStep("Sleep", mapOf("durationMs" to "1")),
            ),
        )
        assertTrue(outcome is ReplayOutcome.Failed)
        outcome as ReplayOutcome.Failed
        assertEquals(1, outcome.stepIndex)
        assertEquals("disk full", outcome.reason)
        assertEquals(listOf("Calculate", "Write"), ran)
    }

    @Test
    fun staleAllowOnceIsClearedAfterDispatch() = runBlocking {
        val gate = gateWith(ToolPermissionLevel.ALLOW)
        val executor = WorkflowReplayExecutor(
            gate = gate,
            sessionId = "wf-test",
            emit = {},
            requestApproval = { true },
            // Deliberately does NOT consume the grant (dispatch failure case).
            runStep = { StepOutcome(false, "crashed before gate") },
        )
        executor.run(workflow(WorkflowStep("Bash", mapOf("command" to "ls"))))
        assertTrue(gate.evaluate("Bash", "next call") is ToolPermissionGateDecision.NeedsApproval)
    }

    @Test
    fun interveningEvaluateDoesNotStealAllowOnceGrant() = runBlocking {
        val gate = gateWith(ToolPermissionLevel.ALLOW)
        var toolPassed = false
        val executor = WorkflowReplayExecutor(
            gate = gate,
            sessionId = "wf-test",
            emit = {},
            requestApproval = { true },
            runStep = {
                // An intermediate check of the same tool must not consume
                // the step's one-shot grant before the tool's own gate check.
                gate.evaluate("Bash", "mid-step unrelated check")
                toolPassed = gate.evaluate("Bash", "tool internal check") is ToolPermissionGateDecision.Allowed
                StepOutcome(true, "ran")
            },
        )
        val outcome = executor.run(workflow(WorkflowStep("Bash", mapOf("command" to "ls"))))
        assertTrue(outcome is ReplayOutcome.Completed)
        assertTrue("tool-internal gate should still pass after an intervening evaluate", toolPassed)
        assertTrue(gate.evaluate("Bash", "after step") is ToolPermissionGateDecision.NeedsApproval)
    }

    @Test
    fun approvalRequestCarriesToolContext() = runBlocking {
        val gate = gateWith(ToolPermissionLevel.ALLOW)
        val approvals = mutableListOf<ToolRequest>()
        WorkflowReplayExecutor(
            gate = gate,
            sessionId = "wf-ctx",
            emit = {},
            requestApproval = { approvals += it; true },
            runStep = { StepOutcome(true, "ok") },
        ).run(
            workflow(
                WorkflowStep("Write", mapOf("path" to "notes/a.txt", "content" to "hello")),
            ),
        )
        val request = approvals.single()
        assertEquals("wf-ctx", request.sessionId)
        assertEquals("Write", request.toolName)
        assertEquals(listOf("notes/a.txt"), request.affectedPaths)
        assertTrue(request.explanation.contains("My Workflow"))
    }
}
