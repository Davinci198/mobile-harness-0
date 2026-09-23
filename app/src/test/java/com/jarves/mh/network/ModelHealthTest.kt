package com.jarves.mh.network

import com.jarves.mh.model.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelHealthTest {
    private val client = ProviderApiClient()

    @Test
    fun twoXxBecomesOk() {
        val health = client.healthFromResponse("m", 200, "{}", null, 120)
        assertEquals(ModelHealthStatus.OK, health.status)
        assertFalse(health.isBroken)
        assertEquals(200, health.httpCode)
    }

    @Test
    fun fourTenGoneBecomesFail() {
        val body = """{"error":{"message":"model removed"}}"""
        val health = client.healthFromResponse("m", 410, body, null, 50)
        assertEquals(ModelHealthStatus.FAIL, health.status)
        assertTrue(health.isBroken)
        assertEquals(410, health.httpCode)
        assertEquals("model removed", health.detail)
    }

    @Test
    fun timeoutErrorBecomesTimeout() {
        val health = client.healthFromResponse("m", 0, "", "connect timed out", 30_000)
        assertEquals(ModelHealthStatus.TIMEOUT, health.status)
        assertTrue(health.isBroken)
    }

    @Test
    fun networkErrorBecomesError() {
        val health = client.healthFromResponse("m", 0, "", "Connection refused", 5)
        assertEquals(ModelHealthStatus.ERROR, health.status)
        assertTrue(health.isBroken)
    }

    @Test
    fun validationBodyIsSmallProbeForScan() {
        val body = org.json.JSONObject(
            client.validationBody("nvidia/nemotron-3-super-120b-a12b", ProviderProtocol.OPENAI_CHAT),
        )
        assertEquals("nvidia/nemotron-3-super-120b-a12b", body.getString("model"))
        assertTrue(body.getInt("max_tokens") in 1..32)
    }
}
