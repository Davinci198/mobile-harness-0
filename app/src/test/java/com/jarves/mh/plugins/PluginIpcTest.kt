package com.jarves.mh.plugins

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class PluginIpcTest {

    private class FakeToolServer(
        private val document: JSONObject = PluginContract.toolsDocument(
            listOf(PluginContract.PluginToolDeclaration("Echo", "Returns its input")),
        ),
        private val tool: (String, JSONObject) -> JSONObject = { _, args -> args },
    ) : PluginIpcServer() {
        override fun onListTools(): JSONObject = document

        override fun onExecute(name: String, args: JSONObject): JSONObject = when (name) {
            "Missing" -> throw PluginIpcException("unknown_tool", "tool '$name' is not declared")
            "Boom" -> error("kaboom")
            else -> tool(name, args)
        }
    }

    private fun dispatch(server: PluginIpcServer, id: Long, request: PluginIpcRequest): PluginIpcResponse =
        PluginIpc.decodeResponse(server.dispatch(PluginIpc.encodeRequest(id, request)))

    // --- request serialization ---

    @Test
    fun encodesListToolsRequest() {
        val json = JSONObject(PluginIpc.encodeRequest(9L, PluginIpcRequest.ListTools))
        assertEquals(9L, json.optLong("id"))
        assertEquals("listTools", json.optString("action"))
    }

    @Test
    fun encodesExecuteRequest() {
        val args = JSONObject().put("text", "hi \"you\"").put("times", 3)
        val json = JSONObject(PluginIpc.encodeRequest(4L, PluginIpcRequest.Execute("Echo", args)))
        assertEquals(4L, json.optLong("id"))
        assertEquals("execute", json.optString("action"))
        assertEquals("Echo", json.optString("name"))
        assertEquals(3, json.getJSONObject("args").optInt("times"))
        assertEquals("hi \"you\"", json.getJSONObject("args").optString("text"))
    }

    @Test
    fun listToolsRequestRoundTrips() {
        val call = PluginIpc.decodeRequest(PluginIpc.encodeRequest(1L, PluginIpcRequest.ListTools))
        assertEquals(1L, call.id)
        assertTrue(call.request is PluginIpcRequest.ListTools)
    }

    @Test
    fun executeRequestRoundTripsNameAndArgs() {
        val args = JSONObject().put("nested", JSONObject().put("k", "v"))
        val call = PluginIpc.decodeRequest(PluginIpc.encodeRequest(5L, PluginIpcRequest.Execute("Echo", args)))
        assertEquals(5L, call.id)
        val request = call.request as PluginIpcRequest.Execute
        assertEquals("Echo", request.name)
        assertEquals("v", request.args.optJSONObject("nested")?.optString("k"))
    }

    @Test
    fun decodeRequestRejectsUnknownAction() {
        val error = assertThrows(PluginIpcException::class.java) {
            PluginIpc.decodeRequest("""{"id":1,"action":"teleport"}""")
        }
        assertEquals("bad_request", error.code)
    }

    @Test
    fun decodeRequestRejectsExecuteWithoutName() {
        val error = assertThrows(PluginIpcException::class.java) {
            PluginIpc.decodeRequest("""{"id":1,"action":"execute","args":{}}""")
        }
        assertEquals("bad_request", error.code)
    }

    @Test
    fun decodeRequestRejectsMalformedJson() {
        assertThrows(JSONException::class.java) { PluginIpc.decodeRequest("not json") }
    }

    // --- response serialization ---

    @Test
    fun successResponseRoundTrips() {
        val result = JSONObject().put("echo", "hi")
        val decoded = PluginIpc.decodeResponse(PluginIpc.encodeResponse(PluginIpc.success(11L, result)))
        assertTrue(decoded.ok)
        assertEquals(11L, decoded.id)
        assertEquals("hi", (decoded.result as JSONObject).optString("echo"))
    }

    @Test
    fun failureResponseRoundTrips() {
        val decoded = PluginIpc.decodeResponse(
            PluginIpc.encodeResponse(PluginIpc.failure(12L, "unknown_tool", "no such tool")),
        )
        assertFalse(decoded.ok)
        assertEquals(12L, decoded.id)
        assertEquals("unknown_tool", decoded.errorCode)
        assertEquals("no such tool", decoded.errorMessage)
    }

    @Test
    fun peekIdRecoversIdOrReportsMissing() {
        assertEquals(8L, PluginIpc.peekId("""{"id":8,"ok":true}"""))
        assertEquals(-1L, PluginIpc.peekId("not json"))
    }

    @Test
    fun defaultTimeoutsMatchTheContract() {
        assertEquals(5_000L, PluginIpcClient.DEFAULT_BIND_TIMEOUT_MS)
        assertEquals(5_000L, PluginIpcClient.DEFAULT_LIST_TOOLS_TIMEOUT_MS)
        assertEquals(30_000L, PluginIpcClient.DEFAULT_EXECUTE_TIMEOUT_MS)
    }

    // --- server dispatch ---

    @Test
    fun serverDispatchesListTools() {
        val response = dispatch(FakeToolServer(), 1L, PluginIpcRequest.ListTools)
        assertTrue(response.ok)
        assertEquals(1L, response.id)
        val document = response.result as JSONObject
        assertEquals("Echo", document.optJSONArray("tools")!!.getJSONObject(0).optString("name"))
    }

    @Test
    fun serverDispatchesExecuteToTheTool() {
        val response = dispatch(
            FakeToolServer(),
            2L,
            PluginIpcRequest.Execute("Echo", JSONObject().put("text", "hi")),
        )
        assertTrue(response.ok)
        assertEquals("hi", (response.result as JSONObject).optString("text"))
    }

    @Test
    fun serverReportsUnknownToolAsTypedFailure() {
        val response = dispatch(FakeToolServer(), 3L, PluginIpcRequest.Execute("Missing", JSONObject()))
        assertFalse(response.ok)
        assertEquals("unknown_tool", response.errorCode)
        assertEquals(3L, response.id)
    }

    @Test
    fun serverWrapsToolCrashAsToolError() {
        val response = dispatch(FakeToolServer(), 4L, PluginIpcRequest.Execute("Boom", JSONObject()))
        assertFalse(response.ok)
        assertEquals("tool_error", response.errorCode)
    }

    @Test
    fun serverAnswersMalformedRequestWithoutThrowing() {
        val response = PluginIpc.decodeResponse(FakeToolServer().dispatch("not json"))
        assertFalse(response.ok)
        assertEquals("bad_request", response.errorCode)
        assertEquals(-1L, response.id)
    }

    @Test
    fun serverReportsUnknownActionAndKeepsTheId() {
        val response = PluginIpc.decodeResponse(
            FakeToolServer().dispatch("""{"id":6,"action":"teleport"}"""),
        )
        assertFalse(response.ok)
        assertEquals("bad_request", response.errorCode)
        assertEquals(6L, response.id)
    }

    // --- timeout handling ---

    @Test
    fun awaitReturnsReplyThatArrivedBeforeTheDeadline() {
        val calls = PluginPendingCalls()
        calls.register(7L)
        assertTrue(calls.complete(7L, """{"id":7,"ok":true}"""))
        assertEquals("""{"id":7,"ok":true}""", calls.await(7L, 1_000L))
        assertEquals(0, calls.pendingCount)
    }

    @Test
    fun awaitTimesOutWhenNoReplyArrives() {
        val calls = PluginPendingCalls()
        calls.register(1L)
        val started = System.nanoTime()
        val result = calls.await(1L, 100L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertNull(result)
        assertTrue("await returned after ${elapsedMs}ms", elapsedMs in 90L..5_000L)
        assertEquals(0, calls.pendingCount)
    }

    @Test
    fun lateReplyAfterTimeoutIsIgnored() {
        val calls = PluginPendingCalls()
        calls.register(2L)
        assertNull(calls.await(2L, 50L))
        assertFalse(calls.complete(2L, "late"))
        assertEquals(0, calls.pendingCount)
    }

    @Test
    fun failAllWakesABlockedAwait() {
        val calls = PluginPendingCalls()
        calls.register(3L)
        var failure: PluginIpcException? = null
        val waiter = thread {
            failure = runCatching { calls.await(3L, 5_000L) }.exceptionOrNull() as? PluginIpcException
        }
        Thread.sleep(100)
        calls.failAll(PluginIpcException("closed", "connection closed"))
        waiter.join(4_000)
        assertFalse(waiter.isAlive)
        assertEquals("closed", failure?.code)
        assertEquals(0, calls.pendingCount)
    }

    @Test
    fun duplicateReplyCompletesOnlyOnce() {
        val calls = PluginPendingCalls()
        calls.register(4L)
        assertTrue(calls.complete(4L, "first"))
        assertFalse(calls.complete(4L, "second"))
        assertEquals("first", calls.await(4L, 1_000L))
    }

    @Test
    fun failAllSurfacesATypedErrorThroughAwaitExactlyOnce() {
        val calls = PluginPendingCalls()
        calls.register(5L)
        calls.failAll(PluginIpcException("closed", "connection closed"))
        val error = assertThrows(PluginIpcException::class.java) { calls.await(5L, 1_000L) }
        assertEquals("closed", error.code)
        assertEquals(0, calls.pendingCount)
        assertNull(calls.await(5L, 50L))
    }

    @Test
    fun abandonedCallIsNeverAwaitable() {
        val calls = PluginPendingCalls()
        calls.register(6L)
        calls.abandon(6L)
        assertEquals(0, calls.pendingCount)
        assertNull(calls.await(6L, 50L))
    }
}
