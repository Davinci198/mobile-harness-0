package com.jarves.mh.mcp

import org.json.JSONObject

data class McpStdioConfig(
    val id: String,
    val displayName: String,
    val command: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String = "",
    val environment: Map<String, String> = emptyMap(),
    val protocolVersion: String = "2025-03-26",
    val connectTimeoutMillis: Long = 30_000,
    val requestTimeoutMillis: Long = 60_000,
) {
    fun validate() {
        require(id.isNotBlank()) { "MCP server ID is empty" }
        require(displayName.isNotBlank()) { "MCP display name is empty" }
        require(command.isNotBlank()) { "MCP command is empty" }
        require(connectTimeoutMillis in 100..300_000) { "MCP connect timeout is invalid" }
        require(requestTimeoutMillis in 100..600_000) { "MCP request timeout is invalid" }
        require(environment.keys.none { it.isBlank() || it.contains('=') || it.contains('\u0000') }) {
            "MCP environment name is invalid"
        }
    }
}

data class McpServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
    val capabilities: JSONObject,
)

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
)

data class McpToolResult(
    val content: List<JSONObject>,
    val structuredContent: JSONObject?,
    val isError: Boolean,
) {
    fun text(): String = content
        .filter { it.optString("type") == "text" }
        .joinToString("\n") { it.optString("text") }
        .take(McpLimits.MAX_RESULT_TEXT_CHARS)
}

sealed class McpException(message: String) : Exception(message) {
    class Transport(message: String) : McpException(message)
    class Startup(message: String) : McpException(message)
    class Protocol(message: String) : McpException(message)
    class JsonRpc(val code: Int, message: String) : McpException(message)
    class Timeout(val requestId: Long) : McpException("MCP request timed out: $requestId")
    class Cancelled(val requestId: Long) : McpException("MCP request cancelled: $requestId")
    class ProcessExited(message: String) : McpException(message)
}

object McpLimits {
    const val MAX_FRAME_BYTES = 1_048_576
    const val MAX_TOOLS = 100
    const val MAX_LIST_PAGES = 20
    const val MAX_CONCURRENT_CALLS = 8
    const val MAX_RESULT_TEXT_CHARS = 262_144
}
