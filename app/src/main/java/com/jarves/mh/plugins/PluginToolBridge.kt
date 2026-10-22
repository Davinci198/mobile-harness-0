package com.jarves.mh.plugins

import org.json.JSONObject
import java.io.Closeable

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
 * The host-side adapter for the plugin's entry-point [android.app.Service] (the
 * one named by its `com.jarves.mh.plugin.entry_point` meta-data) implements this
 * interface; calls cross the APK boundary through [PluginIpc].
 */
interface PluginToolHost : Closeable {
    fun listTools(): List<PluginTool>
    suspend fun executeTool(name: String, arguments: JSONObject): PluginToolResult

    override fun close() = Unit
}

/** Adapts the real cross-APK Messenger contract to the host registry contract. */
class IpcPluginToolHost(
    context: android.content.Context,
    packageName: String,
    serviceName: String?,
) : PluginToolHost {
    private val client = PluginIpcClient(context, packageName, serviceName)

    override fun listTools(): List<PluginTool> = client.listTools().map { declaration ->
        PluginTool(
            name = declaration.name,
            description = declaration.description,
            inputSchema = declaration.inputSchema(),
        )
    }

    override suspend fun executeTool(name: String, arguments: JSONObject): PluginToolResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { client.execute(name, arguments).toString() }
                .fold(
                    onSuccess = PluginToolResult::Success,
                    onFailure = { error ->
                        PluginToolResult.Error(
                            "${error.javaClass.simpleName}: ${error.message ?: "plugin IPC failed"}",
                        )
                    },
                )
        }

    override fun close() = client.close()
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
