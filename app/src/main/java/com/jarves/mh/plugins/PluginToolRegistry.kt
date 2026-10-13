package com.jarves.mh.plugins

import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision
import org.json.JSONObject

/**
 * Collects the tools every admitted plugin contributes and routes executions
 * back to the plugin that owns the tool name.
 *
 * Registration order is significance: the first binding that declares a tool
 * name keeps it. A later plugin declaring the same name is ignored and the
 * conflict is logged — two plugins must never silently swap the meaning of a
 * tool name the agent already knows.
 *
 * Every [execute] call goes through [ToolPermissionGate] like a built-in tool.
 * Plugin tools are not in [com.jarves.mh.tools.ToolPermissionStore.knownTools];
 * they are dynamically discovered, so they resolve to the global default (or
 * an explicit per-tool override) and carry the [com.jarves.mh.tools.ToolRiskCatalog]
 * fallback risk, which a blanket global ALLOW alone can never auto-run.
 */
class PluginToolRegistry(
    private val gate: ToolPermissionGate,
    private val log: (String) -> Unit = {},
) {
    private class Entry(
        val binding: PluginToolBinding,
        val tool: PluginTool,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** Declare [bindings] in order; duplicate tool names keep the first host. */
    fun registerAll(bindings: List<PluginToolBinding>) {
        synchronized(lock) {
            for (binding in bindings) {
                val tools = runCatching { binding.host.listTools() }.getOrElse { failure ->
                    log("Plugin '${binding.plugin.packageName}' failed to list tools: $failure")
                    continue
                }
                for (tool in tools) {
                    val existing = entries[tool.name]
                    if (existing != null) {
                        log(
                            "Plugin tool '${tool.name}' from '${binding.plugin.packageName}' " +
                                "conflicts with '${existing.binding.plugin.packageName}'; keeping the first",
                        )
                        continue
                    }
                    entries[tool.name] = Entry(binding, tool)
                }
            }
        }
    }

    fun register(binding: PluginToolBinding) = registerAll(listOf(binding))

    /** Every admitted tool, in registration order. */
    fun tools(): List<PluginTool> = synchronized(lock) { entries.values.map { it.tool } }

    /** Flattened view for the settings UI. */
    fun summaries(): List<PluginToolSummary> = synchronized(lock) {
        entries.values.map { entry ->
            PluginToolSummary(
                name = entry.tool.name,
                description = entry.tool.description,
                sourcePackageName = entry.binding.plugin.packageName,
                sourceLabel = entry.binding.plugin.label,
            )
        }
    }

    /** The plugin that owns [toolName], or null when no plugin declared it. */
    fun sourceFor(toolName: String): PluginCandidate? =
        synchronized(lock) { entries[toolName]?.binding?.plugin }

    /**
     * Run [name] on the plugin that owns it.
     *
     * The permission gate is evaluated first: a forbidden tool never reaches
     * plugin code, an ASK-level tool surfaces as [PluginToolResult.Error]
     * until the caller arms the gate's allow-once grant, and only an
     * Allowed decision crosses into the plugin process.
     */
    suspend fun execute(
        name: String,
        arguments: JSONObject,
        explanation: String = "Plugin tool '$name'",
    ): PluginToolResult {
        val entry = synchronized(lock) { entries[name] }
            ?: return PluginToolResult.Error("Unknown plugin tool '$name'")
        return when (val decision = gate.evaluate(name, explanation)) {
            is ToolPermissionGateDecision.Allowed ->
                runCatching { entry.binding.host.executeTool(name, arguments) }
                    .getOrElse { failure ->
                        PluginToolResult.Error("Plugin tool '$name' failed: $failure")
                    }
            is ToolPermissionGateDecision.Blocked -> PluginToolResult.Error(decision.reason)
            is ToolPermissionGateDecision.NeedsApproval ->
                PluginToolResult.Error("Plugin tool '$name' requires approval")
        }
    }
}
