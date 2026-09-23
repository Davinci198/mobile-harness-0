package com.jarves.mh.mcp

import org.json.JSONObject

internal object McpJsonRpc {
    fun request(id: Long, method: String, params: JSONObject?): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", method)
        .apply { if (params != null) put("params", params) }
        .toString()

    fun notification(method: String, params: JSONObject?): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", method)
        .apply { if (params != null) put("params", params) }
        .toString()

    fun response(frame: String): Pair<Long, JSONObject> {
        val json = try {
            JSONObject(frame)
        } catch (error: Throwable) {
            throw McpException.Protocol("Invalid MCP JSON frame: ${error.message ?: "malformed JSON"}")
        }
        if (json.optString("jsonrpc") != "2.0") throw McpException.Protocol("MCP response has invalid jsonrpc version")
        val id = id(json.opt("id")) ?: throw McpException.Protocol("MCP response has invalid id")
        if (json.has("error")) {
            val error = json.getJSONObject("error")
            throw McpException.JsonRpc(error.optInt("code", -1), error.optString("message", "MCP JSON-RPC error"))
        }
        if (!json.has("result")) throw McpException.Protocol("MCP response has no result")
        return id to json.getJSONObject("result")
    }

    fun id(value: Any?): Long? = when (value) {
        is Number -> value.toString().toLongOrNull()
        is String -> value.toLongOrNull()
        else -> null
    }
}
