package com.jarves.mh.workflows

import org.json.JSONArray
import org.json.JSONObject

/** One recorded tool call inside a workflow. All values stay strings (file-format stability). */
data class WorkflowStep(
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
)

/**
 * A recorded sequence of tool calls replayed without the LLM —
 * workflow format: user phrase → matched workflow → steps run through the
 * same [com.jarves.mh.tools.ToolPermissionGate] as any other tool call.
 */
data class AgentWorkflow(
    val id: String,
    val name: String,
    val description: String,
    val matchPhrases: List<String>,
    val steps: List<WorkflowStep>,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
)

object AgentWorkflowJson {

    /** Parse one workflow file; returns null when the document is not a usable workflow. */
    fun parse(text: String): AgentWorkflow? {
        val json = try {
            JSONObject(text)
        } catch (_: Throwable) {
            return null
        }
        val id = json.optString("id").trim()
        val name = json.optString("name").trim()
        if (id.isEmpty() || name.isEmpty()) return null
        val phrases = json.optJSONArray("matchPhrases")?.toStringList().orEmpty().filter { it.isNotBlank() }
        if (phrases.isEmpty()) return null
        val stepsJson = json.optJSONArray("steps") ?: return null
        val steps = (0 until stepsJson.length()).mapNotNull { index ->
            val item = stepsJson.optJSONObject(index) ?: return@mapNotNull null
            val toolName = item.optString("toolName").trim()
            if (toolName.isEmpty()) return@mapNotNull null
            val arguments = mutableMapOf<String, String>()
            val args = item.optJSONObject("arguments")
            if (args != null) {
                for (key in args.keys()) {
                    arguments[key] = args.opt(key)?.toString() ?: ""
                }
            }
            WorkflowStep(toolName, arguments)
        }
        if (steps.isEmpty()) return null
        return AgentWorkflow(
            id = id,
            name = name,
            description = json.optString("description").trim(),
            matchPhrases = phrases,
            steps = steps,
            createdAtMillis = json.optLong("createdAtMillis", 0L),
            updatedAtMillis = json.optLong("updatedAtMillis", 0L),
        )
    }

    fun serialize(workflow: AgentWorkflow): String {
        val json = JSONObject()
        json.put("id", workflow.id)
        json.put("name", workflow.name)
        json.put("description", workflow.description)
        json.put("matchPhrases", JSONArray(workflow.matchPhrases))
        val steps = JSONArray()
        workflow.steps.forEach { step ->
            val item = JSONObject()
            item.put("toolName", step.toolName)
            item.put("arguments", JSONObject(step.arguments as Map<*, *>))
            steps.put(item)
        }
        json.put("steps", steps)
        json.put("createdAtMillis", workflow.createdAtMillis)
        json.put("updatedAtMillis", workflow.updatedAtMillis)
        return json.toString(2)
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotBlank) }
}
