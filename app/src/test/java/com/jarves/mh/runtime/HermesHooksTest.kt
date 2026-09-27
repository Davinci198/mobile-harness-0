package com.jarves.mh.runtime

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesHooksTest {
    private fun payload(event: String, extra: JSONObject = JSONObject(), toolName: String = ""): JSONObject =
        JSONObject()
            .put("hook_event_name", event)
            .put("tool_name", toolName)
            .put("session_id", "s-1")
            .put("extra", extra)

    @Test
    fun textDeltaBecomesAssistantText() {
        val action = mapHermesHook(payload("on_stream_delta", JSONObject().put("kind", "text").put("delta", "hi")))
        assertEquals(HermesHookAction.Text("hi"), action)
    }

    @Test
    fun reasoningDeltaIsIgnored() {
        val action = mapHermesHook(payload("on_stream_delta", JSONObject().put("kind", "reasoning").put("delta", "hmm")))
        assertEquals(HermesHookAction.Ignored, action)
    }

    @Test
    fun streamEndCarriesFinalTextAndError() {
        val action = mapHermesHook(
            payload(
                "on_stream_end",
                JSONObject().put("final_text", "done").put("finished", false).put("error", "boom"),
            ),
        )
        assertTrue(action is HermesHookAction.StreamEnd)
        action as HermesHookAction.StreamEnd
        assertEquals("done", action.finalText)
        assertFalse(action.finished)
        assertEquals("boom", action.error)
    }

    @Test
    fun toolCallsMapOntoToolEvents() {
        // The guest sends the tool arguments top-level as `tool_input`.
        val started = mapHermesHook(
            payload("pre_tool_call", toolName = "write_file")
                .put("tool_input", JSONObject().put("path", "/tmp/x")),
        )
        assertTrue(started is HermesHookAction.ToolStart)
        assertEquals("write_file", (started as HermesHookAction.ToolStart).name)
        assertTrue(started.detail.contains("/tmp/x"))

        val ended = mapHermesHook(
            payload("post_tool_call", JSONObject().put("status", "ok"), toolName = "write_file"),
        )
        assertEquals(HermesHookAction.ToolEnd("write_file", "ok"), ended)
    }

    @Test
    fun apiRequestErrorCarriesMessageAndRetryability() {
        val error = JSONObject().put("type", "auth").put("message", "401 invalid token")
        val action = mapHermesHook(
            payload("api_request_error", JSONObject().put("error", error).put("retryable", false)),
        )
        assertTrue(action is HermesHookAction.RequestError)
        action as HermesHookAction.RequestError
        assertEquals("401 invalid token", action.message)
        assertFalse(action.retryable)
    }

    @Test
    fun unknownEventsAreIgnored() {
        assertEquals(HermesHookAction.Ignored, mapHermesHook(payload("on_session_start")))
        assertEquals(HermesHookAction.Ignored, mapHermesHook(payload("on_stream_start")))
    }

    @Test
    fun boundaryWaitsForRunningThenEndsAtTheNextPromptLine() {
        val boundary = WarmTurnBoundary("Write a test")
        // Idle suggestions before the turn started are not the boundary.
        assertFalse(boundary.onOutputLine("❯ Ask anything, or type / for commands..."))
        assertFalse(boundary.turnStarted)
        // The echo of our own submission.
        assertFalse(boundary.onOutputLine("❯ Write a test please"))
        // The running hint arms the boundary.
        assertFalse(boundary.onOutputLine("  ⚠ ❯ msg=interrupt · /queue · /bg · Ctrl+C cancel"))
        assertTrue(boundary.turnStarted)
        // The echo line arriving after the hint must not end the turn.
        assertFalse(boundary.onOutputLine("❯ Write a test please"))
        assertFalse(boundary.onOutputLine("  ⚠ space-bunny-alpha │ ctx 12% │ 3s"))
        // Back to the idle prompt: the turn is over.
        assertTrue(boundary.onOutputLine("❯ Plan a feature, then build it step by step"))
        assertTrue(boundary.onOutputLine("anything after completion stays complete"))
    }

    @Test
    fun boundaryCanBeArmedByAStreamHook() {
        val boundary = WarmTurnBoundary("hello")
        boundary.markRunning()
        assertTrue(boundary.onOutputLine("❯ Ask anything, or type / for commands..."))
    }

    @Test
    fun hookServerDeliversThePayloadAndAnswersOk() {
        val received = AtomicReference<JSONObject>()
        val latch = CountDownLatch(1)
        HermesHookServer { payload -> received.set(payload); latch.countDown() }.use { server ->
            server.start()
            val connection = URL(server.url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.doOutput = true
            val body = """{"hook_event_name":"on_stream_delta","extra":{"kind":"text","delta":"yo"}}"""
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            assertEquals(200, connection.responseCode)
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals("on_stream_delta", received.get().optString("hook_event_name"))
            assertEquals("yo", received.get().optJSONObject("extra").optString("delta"))
            connection.disconnect()
        }
    }
}
