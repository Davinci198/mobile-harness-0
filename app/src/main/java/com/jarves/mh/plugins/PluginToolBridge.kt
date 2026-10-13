package com.jarves.mh.plugins

import org.json.JSONObject

/** One tool a plugin APK contributes to the agent's tool surface. */
data class PluginTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject? = null,
)

/** Outcome of asking a plugin to run one of its tools. */
sealed class PluginToolResult {
    data class Success(val output: String) : PluginToolResult()
    data class Error(val reason: String) : PluginToolResult()
}

/**
 * What MH calls inside a plugin APK.
 *
 * The plugin's entry-point [android.app.Service] (the one named by its
 * `com.jarves.mh.plugin.entry_point` meta-data) implements this interface;
 * MH instantiates it through the plugin's own class loader and talks to it
 * across the APK boundary through this interface only.
 */
interface PluginToolHost {
    fun listTools(): List<PluginTool>
    suspend fun executeTool(name: String, arguments: JSONObject): PluginToolResult
}

/**
 * One admitted plugin together with the live host that serves its tools.
 * The binding is the unit the registry consumes: discovery produces
 * [PluginCandidate]s, the Android layer attaches a [PluginToolHost], and the
 * registry never touches PackageManager again.
 */
data class PluginToolBinding(
    val plugin: PluginCandidate,
    val host: PluginToolHost,
)

/** A discovered plugin tool, flattened for the settings UI. */
data class PluginToolSummary(
    val name: String,
    val description: String,
    val sourcePackageName: String,
    val sourceLabel: String,
)
