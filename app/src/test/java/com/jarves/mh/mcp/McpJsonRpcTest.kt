package com.jarves.mh.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpJsonRpcTest {
    @Test
    fun encodesRequestsAndNotifications() {
        val request = JSONObject(McpJsonRpc.request(7, "tools/call", JSONObject().put("name", "x")))
        assertEquals("2.0", request.getString("jsonrpc"))
        assertEquals(7L, request.getLong("id"))
        assertEquals("tools/call", request.getString("method"))

        val notification = JSONObject(McpJsonRpc.notification("notifications/initialized", null))
        assertTrue(!notification.has("id"))
    }

    @Test
    fun decodesResponsesAndRejectsJsonRpcErrors() {
        val response = McpJsonRpc.response("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}")
        assertEquals(7L, response.first)
        assertTrue(response.second.getBoolean("ok"))

        val error = runCatching {
            McpJsonRpc.response("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}")
        }.exceptionOrNull()
        assertTrue(error is McpException.JsonRpc)
        assertEquals(-32601, (error as McpException.JsonRpc).code)
    }

    @Test
    fun configValidationRejectsInvalidValues() {
        assertTrue(runCatching {
            McpStdioConfig("id", "name", "").validate()
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            McpStdioConfig("id", "name", "cmd", connectTimeoutMillis = 1).validate()
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            McpStdioConfig("id", "name", "cmd", environment = mapOf("BAD=KEY" to "x")).validate()
        }.exceptionOrNull() is IllegalArgumentException)
    }
}
