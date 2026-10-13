package com.jarves.mh.plugins

import org.json.JSONArray
import org.json.JSONObject

/**
 * The manifest contract between Mobile Harness (the host) and plugin APKs.
 *
 * A plugin declares itself in its own manifest:
 *
 * ```xml
 * <application>
 *   <meta-data android:name="com.jarves.mh.plugin.capabilities"
 *              android:value="tools,commands" />
 *   <service android:name=".ToolHostService"
 *            android:exported="true">
 *     <intent-filter>
 *       <action android:name="com.jarves.mh.plugin.ACTION_TOOL_HOST" />
 *     </intent-filter>
 *   </service>
 * </application>
 * ```
 *
 *  - [META_CAPABILITIES] is a comma-separated list of [PluginCapability]
 *    names the plugin claims (case-insensitive, unknown names ignored);
 *  - [ACTION_TOOL_HOST] marks the Service the host binds to. It must be
 *    exported: the host lives in another process;
 *  - the service serves the tool declarations from the plugin's
 *    `assets/tools.json` ([TOOLS_ASSET]):
 *
 * ```json
 * {
 *   "tools": [
 *     {
 *       "name": "MyTool",
 *       "description": "What it does",
 *       "inputSchema": { "type": "object", "properties": { "path": {"type": "string"} } }
 *     }
 *   ]
 * }
 * ```
 *
 * IPC is JSON over Messenger, defined in [PluginIpc]: the host sends
 * `{"id":1,"action":"listTools"}` or
 * `{"id":2,"action":"execute","name":"MyTool","args":{...}}` and receives
 * `{"id":1,"ok":true,"result":...}` or
 * `{"id":2,"ok":false,"error":{"code":"...","message":"..."}}`.
 */
object PluginContract {

    /** `android:name` of the capability meta-data in the plugin manifest. */
    const val META_CAPABILITIES = "com.jarves.mh.plugin.capabilities"

    /** Intent action of the plugin's tool-host service. */
    const val ACTION_TOOL_HOST = "com.jarves.mh.plugin.ACTION_TOOL_HOST"

    /** Asset file declaring the plugin's tools. */
    const val TOOLS_ASSET = "tools.json"

    /** IPC action: list the declared tools. */
    const val ACTION_LIST_TOOLS = "listTools"

    /** IPC action: run one declared tool. */
    const val ACTION_EXECUTE = "execute"

    /** One declared tool, parsed from [TOOLS_ASSET]. */
    data class PluginToolDeclaration(
        val name: String,
        val description: String = "",
        /** The `inputSchema` object as JSON text; stored as text so declarations compare by value. */
        val inputSchemaJson: String? = null,
    ) {
        fun inputSchema(): JSONObject? = inputSchemaJson?.let(::JSONObject)
    }

    /** Map one capability token to the closed set MH knows how to gate. */
    fun parseCapability(raw: String): PluginCapability? =
        PluginCapability.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    /** Parse the comma-separated [META_CAPABILITIES] value; unknown and blank tokens are dropped. */
    fun parseCapabilities(raw: String?): Set<PluginCapability> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',').mapNotNull(::parseCapability).toSet()
    }

    /** Parse the same list when the caller already split it into tokens. */
    fun parseCapabilities(tokens: Collection<String>): Set<PluginCapability> =
        tokens.mapNotNull(::parseCapability).toSet()

    /**
     * Parse a `tools.json` document. Entries without a usable `name` are
     * skipped (an unnamed tool can never be called), a missing `tools` array
     * yields no declarations, and malformed JSON throws [org.json.JSONException].
     */
    fun parseTools(document: String): List<PluginToolDeclaration> {
        val root = JSONObject(document)
        val array = root.optJSONArray("tools") ?: return emptyList()
        val tools = ArrayList<PluginToolDeclaration>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val name = entry.optString("name").trim()
            if (name.isEmpty()) continue
            tools += PluginToolDeclaration(
                name = name,
                description = entry.optString("description").trim(),
                inputSchemaJson = entry.optJSONObject("inputSchema")?.toString(),
            )
        }
        return tools
    }

    /** Render declarations back into the `tools.json` shape (the `listTools` result document). */
    fun toolsDocument(tools: List<PluginToolDeclaration>): JSONObject {
        val array = JSONArray()
        for (tool in tools) {
            val entry = JSONObject().put("name", tool.name).put("description", tool.description)
            tool.inputSchemaJson?.let { entry.put("inputSchema", JSONObject(it)) }
            array.put(entry)
        }
        return JSONObject().put("tools", array)
    }
}
