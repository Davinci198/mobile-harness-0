package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniRushContractTest {
    @Test
    fun `provider entry points at the gateway with the catalog default`() {
        assertEquals("https://omnirush.ai/omnirush/v1", ProviderKind.OMNIRUSH.defaultBaseUrl)
        assertEquals("gpt-6-astra", ProviderKind.OMNIRUSH.defaultModel)
        assertTrue(ProviderKind.OMNIRUSH.fixedBaseUrl)
        assertTrue(ProviderKind.OMNIRUSH.fixedProtocol)
    }

    @Test
    fun `omnirush rides the loopback proxy and nothing else changes lanes`() {
        assertTrue(ProviderProfile(ProviderKind.OMNIRUSH).routesThroughOpenAiProxy())
        assertTrue(ProviderProfile(ProviderKind.NVIDIA_NIM).routesThroughOpenAiProxy())
        assertFalse(ProviderProfile(ProviderKind.ANTHROPIC).routesThroughOpenAiProxy())
    }

    @Test
    fun `opencode model arg uses the omnirush provider namespace`() {
        assertEquals(
            "omnirush/gpt-6-astra",
            opencodeModelArg(ProviderProfile(ProviderKind.OMNIRUSH), "http://127.0.0.1:1/omnirush/v1", "gpt-6-astra"),
        )
    }

    @Test
    fun `guest config points the openai provider at the proxy with all models`() {
        val content = OmniRush.configContent("http://127.0.0.1:9/omnirush/v1")
        val root = JSONObject(content)
        val provider = root.getJSONObject("provider").getJSONObject("omnirush")
        assertEquals("@ai-sdk/openai", provider.getString("npm"))
        assertEquals("http://127.0.0.1:9/omnirush/v1", provider.getJSONObject("options").getString("baseURL"))
        val models = provider.getJSONObject("models")
        assertEquals(
            setOf("gpt-6-astra", "gpt-6-sol", "gpt-6.1-sol"),
            models.keys().asSequence().toSet(),
        )
        assertEquals("GPT 6 Astra", models.getJSONObject("gpt-6-astra").getString("name"))
    }

    @Test
    fun `update floor parses out of a 426 body`() {
        assertEquals(
            "2.4.11",
            OmniRush.parseUpdateFloor("""{"error":{"type":"update_required","minimum":"2.4.11"}}"""),
        )
        assertNull(OmniRush.parseUpdateFloor("""{"detail":"client_not_allowed"}"""))
        assertNull(OmniRush.parseUpdateFloor("not json"))
    }

    @Test
    fun `version comparison spots a newer floor`() {
        assertTrue(OmniRush.isNewerVersion("2.4.11", "2.4.10"))
        assertTrue(OmniRush.isNewerVersion("2.5.0", "2.4.16"))
        assertFalse(OmniRush.isNewerVersion("2.4.16", "2.4.16"))
        assertFalse(OmniRush.isNewerVersion("2.4.9", "2.4.10"))
    }

    @Test
    fun `rotation answer parses into the fresh pair`() {
        val parsed = OmniRush.parseRefreshResponse("""{"access_token":"a1","refresh_token":"r1"}""")
        assertEquals("a1" to "r1", parsed)
        assertNull(OmniRush.parseRefreshResponse("""{"detail":"refresh_token_invalid_or_expired"}"""))
    }

    @Test
    fun `refresh body carries the token as JSON`() {
        val body = OmniRush.refreshRequestBody("omr_secret")
        assertEquals("omr_secret", JSONObject(body).getString("refresh_token"))
    }
}
