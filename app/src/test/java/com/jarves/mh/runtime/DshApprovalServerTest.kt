package com.jarves.mh.runtime

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshApprovalServerTest {
    private fun post(server: DshApprovalServer, path: String = "/approval", body: String? = null): Pair<Int, JSONObject> {
        val connection = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 20_000
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
        return connection.responseCode to JSONObject(payload)
    }

    @Test
    fun forwardsTheGuestAskAndReturnsTheDecision() {
        val seen = mutableListOf<Triple<String, String, String?>>()
        DshApprovalServer(
            decide = { callId, toolName, reason ->
                seen += Triple(callId, toolName, reason)
                "allowed-once"
            },
            answer = { _, _ -> """{"answers":[]}""" },
        ).use { server ->
            server.start()
            assertTrue(server.port > 0)

            val (status, payload) = post(
                server,
                body = JSONObject().put("callId", "call-1").put("toolName", "bash").put("reason", "rm -rf /tmp/x").toString(),
            )

            assertEquals(200, status)
            assertEquals("allowed-once", payload.getString("outcome"))
            assertEquals(listOf(Triple("call-1", "bash", "rm -rf /tmp/x")), seen)
        }
    }

    @Test
    fun aDeniedDecisionReachesTheGuest() {
        val capturedReason = AtomicReference<String?>("sentinel")
        DshApprovalServer(
            decide = { _, _, reason ->
                capturedReason.set(reason)
                "rejected"
            },
            answer = { _, _ -> """{"answers":[]}""" },
        ).use { server ->
            server.start()

            val (_, payload) = post(
                server,
                body = JSONObject().put("callId", "call-2").put("reason", JSONObject.NULL).toString(),
            )

            assertEquals("rejected", payload.getString("outcome"))
            assertNull(capturedReason.get())
        }
    }

    @Test
    fun aThrowingDecisionFailsClosedAsUnavailable() {
        DshApprovalServer(
            decide = { _, _, _ -> error("ui gone") },
            answer = { _, _ -> """{"answers":[]}""" },
        ).use { server ->
            server.start()

            val (_, payload) = post(server, body = JSONObject().put("callId", "call-3").toString())

            assertEquals("unavailable", payload.getString("outcome"))
        }
    }

    @Test
    fun aNonPostProbeStillGetsAValidOutcome() {
        DshApprovalServer(
            decide = { _, _, _ -> "allowed-once" },
            answer = { _, _ -> """{"answers":[]}""" },
        ).use { server ->
            server.start()

            val (status, payload) = post(server)

            assertEquals(200, status)
            assertEquals("unavailable", payload.getString("outcome"))
        }
    }

    @Test
    fun forwardsTheQuestionAndReturnsTheAnswerBatch() {
        val seen = AtomicReference<String?>(null)
        DshApprovalServer(
            decide = { _, _, _ -> "unavailable" },
            answer = { callId, questions ->
                seen.set(callId)
                """{"answers":[{"id":"${questions.getJSONObject(0).getString("id")}","selected":["B"]}]}"""
            },
        ).use { server ->
            server.start()

            val (status, payload) = post(
                server,
                path = "/question",
                body = JSONObject()
                    .put("callId", "qcall-1")
                    .put("questions", JSONArray().put(JSONObject().put("id", "choice").put("question", "Which one?")))
                    .toString(),
            )

            assertEquals(200, status)
            val answer = payload.getJSONArray("answers").getJSONObject(0)
            assertEquals("choice", answer.getString("id"))
            assertEquals("B", answer.getJSONArray("selected").getString(0))
            assertEquals("qcall-1", seen.get())
        }
    }

    @Test
    fun aQuestionWithoutQuestionsFailsClosed() {
        DshApprovalServer(
            decide = { _, _, _ -> "allowed-once" },
            answer = { _, _ -> """{"answers":[]}""" },
        ).use { server ->
            server.start()

            val (_, payload) = post(
                server,
                path = "/question",
                body = JSONObject().put("callId", "qcall-2").toString(),
            )

            assertEquals("unavailable", payload.getString("error"))
        }
    }

    @Test
    fun aThrowingAnswerFailsClosedAsUnavailable() {
        DshApprovalServer(
            decide = { _, _, _ -> "allowed-once" },
            answer = { _, _ -> error("ui gone") },
        ).use { server ->
            server.start()

            val (_, payload) = post(
                server,
                path = "/question",
                body = JSONObject()
                    .put("questions", JSONArray().put(JSONObject().put("id", "q").put("question", "?")))
                    .toString(),
            )

            assertEquals("unavailable", payload.getString("error"))
        }
    }
}
