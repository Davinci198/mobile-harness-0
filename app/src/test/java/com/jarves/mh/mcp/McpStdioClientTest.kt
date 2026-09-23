package com.jarves.mh.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import kotlin.concurrent.thread

private class FakeMcpTransport : McpProcessTransport {
    private val serverInput = PipedInputStream()
    private val serverOutput = PipedOutputStream(serverInput)
    private val clientInput = PipedInputStream()
    private val clientOutput = PipedOutputStream(clientInput)
    @Volatile var alive = true
    @Volatile var stderrText = ""

    override val input: java.io.InputStream = clientInput
    override val output: java.io.OutputStream = clientOutput
    override fun stderr(): String = stderrText
    override fun isAlive(): Boolean = alive
    override fun terminate() { alive = false }
    override fun kill() { alive = false }
    override fun close() {
        alive = false
        runCatching { serverOutput.close() }
        runCatching { clientOutput.close() }
        runCatching { serverInput.close() }
        runCatching { clientInput.close() }
    }

    fun serve(
        notifications: MutableList<JSONObject>,
        handler: suspend (JSONObject) -> JSONObject? = { defaultResponse(it) },
    ): Thread = thread(isDaemon = true) {
        runBlocking {
            val reader = BufferedReader(InputStreamReader(serverInput))
            while (alive) {
                val line = reader.readLine() ?: break
                val envelope = JSONObject(line)
                if (!envelope.has("id") || (!envelope.has("method") && (envelope.has("result") || envelope.has("error")))) {
                    if (!envelope.has("id")) notifications += envelope
                    continue
                }
                val result = handler(envelope)
                if (result != null) {
                    serverOutput.write(
                        JSONObject()
                            .put("jsonrpc", "2.0")
                            .put("id", envelope.get("id"))
                            .put("result", result)
                            .toString().plus("\n").toByteArray(),
                    )
                    serverOutput.flush()
                }
            }
        }
    }

    private fun defaultResponse(request: JSONObject): JSONObject? = when (request.optString("method")) {
        "initialize" -> JSONObject()
            .put("protocolVersion", "2025-03-26")
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject().put("name", "fake").put("version", "1"))
        "tools/list" -> JSONObject().put("tools", JSONArrayTools("one", "two"))
        "tools/call" -> JSONObject()
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "done")))
            .put("isError", false)
        else -> JSONObject()
    }
}

private fun JSONArrayTools(vararg names: String) = org.json.JSONArray().apply {
    names.forEach { name ->
        put(JSONObject().put("name", name).put("description", name).put("inputSchema", JSONObject()))
    }
}

class McpStdioClientTest {
    @Test
    fun connectsListsAndCallsTools() = runBlocking {
        val transport = FakeMcpTransport()
        val notifications: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        transport.serve(notifications) { request ->
            when (request.optString("method")) {
                "initialize" -> {
                    transport.serverOutput.write("{\"jsonrpc\":\"2.0\",\"id\":\"health-1\",\"method\":\"ping\"}\n".toByteArray())
                    transport.serverOutput.flush()
                    initializeResult()
                }
                "tools/list" -> if (request.optJSONObject("params")?.has("cursor") == true) {
                    JSONObject().put("tools", JSONArrayTools("three"))
                } else {
                    JSONObject()
                        .put("tools", JSONArrayTools("one", "two"))
                        .put("nextCursor", "page-2")
                }
                "tools/call" -> JSONObject()
                    .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "called")))
                    .put("isError", false)
                else -> JSONObject()
            }
        }
        val client = McpStdioClient(config(), McpProcessLauncher { transport })

        val info = client.connect()
        assertEquals("fake", info.name)
        assertEquals(listOf("one", "two", "three"), client.listTools().map { it.name })
        val result = client.callTool("one", JSONObject().put("value", 1))
        assertEquals("called", result.text())
        assertFalse(result.isError)
        assertTrue(notifications.any { it.optString("method") == "notifications/initialized" })
        client.close()
    }

    @Test
    fun serverNotificationsDoNotBreakPendingRequests() = runBlocking {
        val transport = FakeMcpTransport()
        val notifications: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        transport.serve(notifications) { request ->
            if (request.optString("method") == "initialize") {
                transport.serverOutput.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\n".toByteArray())
                transport.serverOutput.flush()
                initializeResult()
            } else {
                JSONObject().put("tools", JSONArrayTools())
            }
        }
        val client = McpStdioClient(config(), McpProcessLauncher { transport })
        client.connect()
        assertTrue(client.listTools().isEmpty())
        client.close()
    }

    @Test
    fun timeoutRemovesPendingAndSendsCancellation() = runBlocking {
        val transport = FakeMcpTransport()
        val notifications: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        transport.serve(notifications) { request ->
            when (request.optString("method")) {
                "initialize" -> initializeResult()
                "tools/list" -> null
                else -> JSONObject()
            }
        }
        val client = McpStdioClient(
            config().copy(requestTimeoutMillis = 150),
            McpProcessLauncher { transport },
        )
        client.connect()
        val error = runCatching { client.listTools() }.exceptionOrNull()
        assertTrue(error is McpException.Timeout)
        waitUntil { notifications.any { it.optString("method") == "notifications/cancelled" } }
        client.close()
    }

    @Test
    fun repeatedListCursorIsProtocolError() = runBlocking {
        val transport = FakeMcpTransport()
        val notifications: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        transport.serve(notifications) { request ->
            when (request.optString("method")) {
                "initialize" -> initializeResult()
                "tools/list" -> when (request.optJSONObject("params")?.optString("cursor")) {
                    null -> JSONObject().put("tools", JSONArrayTools()).put("nextCursor", "cursor-a")
                    "cursor-a" -> JSONObject().put("tools", JSONArrayTools()).put("nextCursor", "cursor-b")
                    else -> JSONObject().put("tools", JSONArrayTools()).put("nextCursor", "cursor-a")
                }
                else -> JSONObject()
            }
        }
        val client = McpStdioClient(config(), McpProcessLauncher { transport })
        client.connect()
        val error = runCatching { client.listTools() }.exceptionOrNull()
        assertTrue(error is McpException.Protocol)
        client.close()
    }

    @Test
    fun closeIsIdempotentAndRejectsFurtherCalls() = runBlocking {
        val transport = FakeMcpTransport()
        val notifications: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        transport.serve(notifications)
        val client = McpStdioClient(config(), McpProcessLauncher { transport })
        client.connect()
        client.close()
        client.close()
        assertFalse(transport.isAlive())
        assertTrue(runCatching { client.listTools() }.exceptionOrNull() is McpException.Transport)
    }

    private fun initializeResult() = JSONObject()
        .put("protocolVersion", "2025-03-26")
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", "fake").put("version", "1"))

    private fun config() = McpStdioConfig(
        id = "fake",
        displayName = "Fake",
        command = "/usr/bin/fake-mcp",
        connectTimeoutMillis = 1_000,
        requestTimeoutMillis = 1_000,
    )

    private fun waitUntil(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Condition was not reached")
    }
}
