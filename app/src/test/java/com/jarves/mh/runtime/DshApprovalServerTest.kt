package com.jarves.mh.runtime

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
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
        DshApprovalServer { callId, toolName, reason ->
            seen += Triple(callId, toolName, reason)
            "allowed-once"
        }.use { server ->
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
        DshApprovalServer { _, _, reason ->
            capturedReason.set(reason)
            "rejected"
        }.use { server ->
            server.start()

            val (_, payload) = post(
                server,
                body = JSONObject().put("callId", "call-2").put("reason", JSONObject.NULL).toString(),
            )

            assertEquals("rejected", payload.getString("outcome"))
            assertNull(capturedReason.get())
        }
    }
    }

    @Test
    fun aThrowingDecisionFailsClosedAsUnavailable() {
        DshApprovalServer { _, _, _ -> error("ui gone") }.use { server ->
            server.start()

            val (_, payload) = post(server, body = JSONObject().put("callId", "call-3").toString())

            assertEquals("unavailable", payload.getString("outcome"))
        }
    }

    @Test
    fun aNonPostProbeStillGetsAValidOutcome() {
        DshApprovalServer { _, _, _ -> "allowed-once" }.use { server ->
            server.start()

            val (status, payload) = post(server)

            assertEquals(200, status)
            assertEquals("unavailable", payload.getString("outcome"))
        }
    }
}
