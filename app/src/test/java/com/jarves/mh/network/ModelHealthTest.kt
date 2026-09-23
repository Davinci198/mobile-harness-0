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
    fun insufficientCreditsBecomes402() {
        val health = client.healthFromResponse("m", 402, """{"error":{"message":"insufficient_credits"}}""", null, 100)
        assertEquals(ModelHealthStatus.INSUFFICIENT_CREDITS, health.status)
        assertTrue(health.isBroken)
        assertEquals(402, health.httpCode)
        assertEquals("insufficient credits", health.detail)
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

    @Test
    fun mergeCatalogKeepsScanHealthForSurvivingModels() {
        val previous = listOf(
            DiscoveredModel("keep", "Keep", latencyMs = 800, httpCode = 200, health = "OK"),
            DiscoveredModel("fail", "Fail", latencyMs = 12_000, httpCode = 404, health = "FAIL"),
        )
        val fresh = listOf(
            DiscoveredModel("keep", "Keep Renamed"),
            DiscoveredModel("new", "Brand New"),
        )
        val merged = mergeCatalogModels(fresh, previous)
        assertEquals(2, merged.size)
        val keep = merged.first { it.id == "keep" }
        assertEquals(800L, keep.latencyMs)
        assertEquals(200, keep.httpCode)
        assertEquals("OK", keep.health)
        assertFalse(merged.first { it.id == "new" }.isBroken)
    }

    @Test
    fun mergeCatalogWithoutPreviousKeepsFreshModels() {
        val fresh = listOf(DiscoveredModel("a"), DiscoveredModel("b"))
        assertEquals(fresh, mergeCatalogModels(fresh, emptyList()))
    }

    @Test
    fun endpointCatalogKeyNormalizesTrailingSlash() {
        val catalog = EndpointModelCatalog(
            kindName = "CUSTOM",
            baseUrl = "https://api.example.com/v1/",
            models = emptyList(),
        )
        assertEquals("CUSTOM|https://api.example.com/v1", catalog.key)
        assertTrue(catalog.matches("CUSTOM", "https://api.example.com/v1"))
        assertFalse(catalog.matches("OPENAI", "https://api.example.com/v1"))
    }

    @Test
    fun discoveredModelIsBrokenOnlyWhenScannedAndNotOk() {
        assertFalse(DiscoveredModel("a").isBroken)
        assertFalse(DiscoveredModel("a", health = "OK").isBroken)
        assertTrue(DiscoveredModel("a", health = "FAIL").isBroken)
        assertEquals("0.8s", DiscoveredModel("a", latencyMs = 800).latencyLabel)
    }
}
