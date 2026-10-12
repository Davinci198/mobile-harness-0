package com.jarves.mh.workflows

import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.classifyRisk
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Result of one replayed step. */
data class StepOutcome(val ok: Boolean, val summary: String)

/** Final state of a workflow replay. */
sealed interface ReplayOutcome {
    data class Completed(val stepSummaries: List<String>) : ReplayOutcome
    data class Rejected(val stepIndex: Int, val toolName: String) : ReplayOutcome
    data class Failed(val stepIndex: Int, val toolName: String, val reason: String) : ReplayOutcome
}

/**
 * Replays a matched workflow step by step without the LLM.
 *
 * Every step passes through the shared [ToolPermissionGate] first:
 * - ALLOW → runs immediately (LOW-risk tools under an auto policy).
 * - ASK → [requestApproval] raises the approval card; on "Allow once" the
 *   gate receives [ToolPermissionGate.allowOnce] so the tool's own internal
 *   gate check passes without a second prompt.
 * - FORBID / hook veto → the workflow stops; recorded flows never bypass policy.
 */
class WorkflowReplayExecutor(
    private val gate: ToolPermissionGate,
    private val sessionId: String,
    private val emit: (RuntimeEvent) -> Unit,
    private val requestApproval: suspend (ToolRequest) -> Boolean,
    private val runStep: suspend (WorkflowStep) -> StepOutcome,
) {

    suspend fun run(workflow: AgentWorkflow): ReplayOutcome {
        val summaries = mutableListOf<String>()
        workflow.steps.forEachIndexed { index, step ->
            currentCoroutineContext().ensureActive()
            val explanation = explanation(workflow, index, step)
            val request = ToolRequest(
                sessionId = sessionId,
                toolName = step.toolName,
                explanation = explanation,
                affectedPaths = step.arguments["path"]?.let { listOf(it) }.orEmpty(),
                commandPreview = step.arguments["command"],
                risk = classifyRisk(step.toolName, step.arguments["command"]),
            )
            when (val decision = gate.evaluate(step.toolName, explanation)) {
                is ToolPermissionGateDecision.Blocked -> {
                    return ReplayOutcome.Failed(index, step.toolName, decision.reason)
                }
                is ToolPermissionGateDecision.NeedsApproval -> {
                    val approved = requestApproval(request)
                    emit(
                        if (approved) RuntimeEvent.ToolApproved(sessionId, request.approvalId)
                        else RuntimeEvent.ToolRejected(sessionId, request.approvalId),
                    )
                    if (!approved) return ReplayOutcome.Rejected(index, step.toolName)
                    gate.allowOnce(step.toolName)
                }
                is ToolPermissionGateDecision.Allowed -> Unit
            }
            emit(RuntimeEvent.ToolStarted(sessionId, step.toolName, brief(step)))
            val outcome = try {
                runStep(step)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                StepOutcome(false, error.message ?: "step failed")
            } finally {
                // Never leave a stale one-shot grant behind.
                gate.clearAllowOnce(step.toolName)
            }
            emit(RuntimeEvent.ToolCompleted(sessionId, step.toolName, outcome.summary))
            summaries += outcome.summary
            if (!outcome.ok) {
                return ReplayOutcome.Failed(index, step.toolName, outcome.summary)
            }
        }
        return ReplayOutcome.Completed(summaries)
    }

    private fun explanation(workflow: AgentWorkflow, index: Int, step: WorkflowStep): String {
        val args = step.arguments.entries.joinToString(" ") { (key, value) ->
            val shown = if (value.length > 80) value.take(80) + "…" else value
            "$key=$shown"
        }
        val prefix = "Workflow '${workflow.name}' step ${index + 1}: ${step.toolName}"
        return (if (args.isBlank()) prefix else "$prefix $args").take(240)
    }

    private fun brief(step: WorkflowStep): String =
        step.arguments.entries.take(3).joinToString(" ") { (key, value) ->
            val shown = if (value.length > 60) value.take(60) + "…" else value
            "$key=$shown"
        }.ifBlank { step.toolName }
}
