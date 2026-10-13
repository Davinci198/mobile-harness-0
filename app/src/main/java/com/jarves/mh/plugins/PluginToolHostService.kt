package com.jarves.mh.plugins

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject

/**
 * Base [Service] a plugin APK extends to serve its tools to the host.
 *
 * The plugin's manifest has to declare (see [PluginContract]):
 *
 *  - `<meta-data android:name="com.jarves.mh.plugin.capabilities" android:value="tools"/>`;
 *  - this service with `android:exported="true"` and an intent filter for
 *    [PluginContract.ACTION_TOOL_HOST] — the host binds to it from its own
 *    process, so every tool call runs sandboxed inside the plugin's process;
 *  - `assets/tools.json` describing the tools.
 *
 * [onBind] reads the declarations once, serves `listTools`, rejects `execute`
 * for names missing from `tools.json` and hands accepted calls to
 * [executeTool]. Subclasses implement [executeTool] only.
 */
abstract class PluginToolHostService : Service() {

    private var declaredTools: List<PluginContract.PluginToolDeclaration>? = null
    private var server: PluginIpcServer? = null

    /** Run one declared tool. Throw [PluginIpcException] for typed failures. */
    protected abstract fun executeTool(name: String, args: JSONObject): JSONObject

    /** The asset holding the declarations; override to rename the file. */
    protected open fun toolsAsset(): String = PluginContract.TOOLS_ASSET

    override fun onBind(intent: Intent?): IBinder {
        val tools = declaredTools ?: loadDeclaredTools().also { declaredTools = it }
        return serverFor(tools).asBinder()
    }

    private fun serverFor(tools: List<PluginContract.PluginToolDeclaration>): PluginIpcServer =
        server ?: (object : PluginIpcServer() {
            override fun onListTools(): JSONObject = PluginContract.toolsDocument(tools)

            override fun onExecute(name: String, args: JSONObject): JSONObject {
                if (tools.none { it.name == name }) {
                    throw PluginIpcException("unknown_tool", "tool '$name' is not declared in ${toolsAsset()}")
                }
                return executeTool(name, args)
            }
        }).also { server = it }

    /**
     * Read [toolsAsset] from this plugin's own assets. A missing or malformed
     * asset yields no declarations instead of crashing the binding: the plugin
     * then lists no tools and every execute fails with `unknown_tool`.
     */
    private fun loadDeclaredTools(): List<PluginContract.PluginToolDeclaration> = runCatching {
        assets.open(toolsAsset()).bufferedReader().use { it.readText() }
    }.map { PluginContract.parseTools(it) }.getOrElse { emptyList() }
}
