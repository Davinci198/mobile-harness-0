package com.jarves.mh.workflows

/**
 * Static descriptor of which tools workflow replay can execute and which
 * arguments each one requires. A workflow referencing an unknown tool or a
 * missing required argument is not executable and never matches — the input
 * falls through to the normal Agent Execution instead of failing mid-run.
 */
object WorkflowCatalog {

    /** toolName → required argument keys. Optional keys are not listed. */
    val requiredArguments: Map<String, List<String>> = mapOf(
        // file
        "Read" to listOf("path"),
        "Write" to listOf("path", "content"),
        "Edit" to listOf("path", "old", "new"),
        "Grep" to listOf("path", "pattern"),
        "Glob" to listOf("glob"),
        // terminal
        "Bash" to listOf("command"),
        // calc
        "Calculate" to listOf("expression"),
        "Sleep" to emptyList(),
        // network
        "VisitWeb" to listOf("url"),
        "HttpRequest" to listOf("url"),
        "DownloadFile" to listOf("url", "filename"),
        // device
        "ClipboardCopy" to listOf("text"),
        "ClipboardRead" to emptyList(),
        "DeviceToast" to listOf("text"),
        "DeviceNotification" to listOf("title", "text"),
        "DeviceOpenHttpsLink" to listOf("url"),
        "UiReadTree" to emptyList(),
        "UiStatus" to emptyList(),
        "UiClick" to listOf("nodeId"),
        // memory
        "MemoryCreate" to listOf("title", "content"),
        "MemoryRead" to listOf("id"),
        "MemorySearch" to listOf("query"),
        "MemoryList" to emptyList(),
    )

    val supportedTools: Set<String> = requiredArguments.keys

    fun supports(toolName: String): Boolean = toolName in requiredArguments

    /** True when every step names a supported tool and carries its required arguments. */
    fun isExecutable(workflow: AgentWorkflow): Boolean = isExecutable(workflow, emptySet())

    /**
     * Validate a workflow against built-ins plus dynamically discovered tools.
     * Plugin schemas are validated by the plugin at execution time; admission
     * only needs to establish that the host currently knows the tool name.
     */
    fun isExecutable(workflow: AgentWorkflow, additionalTools: Set<String>): Boolean =
        workflow.steps.isNotEmpty() && workflow.steps.all { step -> isStepExecutable(step, additionalTools) }

    fun isStepExecutable(step: WorkflowStep, additionalTools: Set<String> = emptySet()): Boolean {
        val required = requiredArguments[step.toolName]
            ?: return step.toolName in additionalTools
        return required.all { key -> !step.arguments[key].isNullOrBlank() }
    }

    /** Human-readable reason a workflow cannot run, or null when it can. */
    fun rejectionReason(workflow: AgentWorkflow, additionalTools: Set<String> = emptySet()): String? {
        if (workflow.steps.isEmpty()) return "Workflow has no steps"
        workflow.steps.forEachIndexed { index, step ->
            if (!supports(step.toolName) && step.toolName !in additionalTools) {
                return "Step ${index + 1}: unsupported tool '${step.toolName}'"
            }
            val missing = requiredArguments[step.toolName].orEmpty()
                .filter { step.arguments[it].isNullOrBlank() }
            if (missing.isNotEmpty()) return "Step ${index + 1}: missing ${missing.joinToString(", ")}"
        }
        return null
    }
}
