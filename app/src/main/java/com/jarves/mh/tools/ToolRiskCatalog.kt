package com.jarves.mh.tools

/**
 * Default [ToolRiskLevel] for every tool MH knows about.
 *
 * Static policy like FoneClaw's compile-time tool index: unknown or
 * agent-defined tools fall back to [ToolRiskLevel.DEVICE_CONTROL] so a
 * blanket global ALLOW can never silently auto-run something unclassified.
 */
object ToolRiskCatalog {

    private val defaults: Map<String, ToolRiskLevel> = buildMap {
        // Read-only / no side effects
        listOf(
            "Read", "Glob", "Grep", "Calculate", "Sleep", "UseSkill",
            "MemorySearch", "MemoryList", "MemoryLinkList",
        ).forEach { put(it, ToolRiskLevel.LOW) }

        // Reads private/user data
        listOf(
            "ClipboardRead", "MemoryRead",
        ).forEach { put(it, ToolRiskLevel.SENSITIVE_READ) }

        // Changes device/app state
        listOf(
            "Write", "Edit", "NotebookEdit",
            "MemoryCreate", "MemoryUpdate", "MemoryDelete", "MemoryLink", "MemoryUnlink",
            "ClipboardCopy", "DeviceToast", "DeviceNotification", "DeviceOpenHttpsLink",
            "VisitWeb",
        ).forEach { put(it, ToolRiskLevel.DEVICE_CONTROL) }

        // Affects the outside world
        listOf(
            "Bash", "HttpRequest", "DownloadFile", "ShizukuExec",
        ).forEach { put(it, ToolRiskLevel.EXTERNAL_EFFECT) }
    }

    fun riskFor(toolName: String): ToolRiskLevel =
        defaults[toolName]
            ?: defaults.entries.firstOrNull { it.key.equals(toolName, ignoreCase = true) }?.value
            ?: ToolRiskLevel.DEVICE_CONTROL

    /** Full snapshot for settings/debug surfaces. */
    fun snapshot(): Map<String, ToolRiskLevel> = defaults.toMap()
}
