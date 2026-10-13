package com.jarves.mh.sampleplugin

import org.json.JSONObject

/**
 * Reference tool: hands its input straight back, so a host can verify the
 * full IPC round-trip (bind -> listTools -> execute -> reply) end to end.
 */
object EchoTool {

    const val NAME = "Echo"

    fun run(args: JSONObject): JSONObject = JSONObject().put("echo", args)
}
