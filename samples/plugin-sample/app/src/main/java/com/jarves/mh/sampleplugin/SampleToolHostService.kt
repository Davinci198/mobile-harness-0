package com.jarves.mh.sampleplugin

import com.jarves.mh.plugins.PluginIpcException
import com.jarves.mh.plugins.PluginToolHostService
import org.json.JSONObject

/**
 * The plugin's tool host — the Service Mobile Harness binds to through the
 * [com.jarves.mh.plugins.PluginContract.ACTION_TOOL_HOST] intent action.
 *
 * `tools.json` in this APK's assets declares what the host may call; this
 * class only routes declared names to their implementations. Tool execution
 * happens in this plugin's own process, never in the host.
 */
class SampleToolHostService : PluginToolHostService() {

    override fun executeTool(name: String, args: JSONObject): JSONObject = when (name) {
        EchoTool.NAME -> EchoTool.run(args)
        else -> throw PluginIpcException("unknown_tool", "tool '$name' is not implemented")
    }
}
