package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.providersForAgent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniRushContractTest {
    /**
     * Android's `org.json` silently ignores trailing content after the first
     * value, so a document with one extra `}` parses "fine" in a unit test and
     * only blows up in the guest (opencode rejects the whole provider). Walk the
     * document and require the brace depth to land exactly on zero at the end.
     */
    private fun assertStrictJson(document: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in document) {
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> depth--
            }
            assertTrue("unbalanced JSON near: $document", depth >= 0)
        }
        assertFalse("unterminated string in: $document", inString)
        assertEquals("JSON braces do not balance in: $document", 0, depth)
    }

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
    fun `omnirush is offered to deepseek harness and opencode but not hermes`() {
        assertTrue(ProviderKind.OMNIRUSH in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
        assertTrue(ProviderKind.OMNIRUSH in providersForAgent(AgentKind.OPENCODE))
        assertFalse(ProviderKind.OMNIRUSH in providersForAgent(AgentKind.HERMES))
        assertFalse(ProviderKind.OMNIRUSH in providersForAgent(AgentKind.CLAUDE_CODE))
    }

    @Test
    fun `dsh route speaks openai responses at the loopback proxy`() {
        val route = DshRouteMapper.forProfile(
            ProviderProfile(ProviderKind.OMNIRUSH),
            omniRushBaseUrl = "http://127.0.0.1:7/omnirush/v1",
        )
        assertEquals("mh-omnirush", route.name)
        assertEquals("openai-responses", route.custom?.api)
        assertEquals("http://127.0.0.1:7/omnirush/v1", route.custom?.baseUrl)
        assertEquals(DshRuntimeBridge.FALLBACK_KEY_ENV, route.keyEnv)
    }

    @Test(expected = IllegalStateException::class)
    fun `dsh route refuses omnirush without the proxy`() {
        DshRouteMapper.forProfile(ProviderProfile(ProviderKind.OMNIRUSH))
    }

    @Test
    fun `dsh settings pin the omnirush provider at the proxy`() {
        val route = DshRouteMapper.forProfile(
            ProviderProfile(ProviderKind.OMNIRUSH),
            omniRushBaseUrl = "http://127.0.0.1:7/omnirush/v1",
        )
        val patch = dshHomePatch(route, "gpt-6-astra")
        assertTrue(patch.contains("api: openai-responses"))
        assertTrue(patch.contains("baseURL: 'http://127.0.0.1:7/omnirush/v1'"))
        assertTrue(patch.contains("apiKeyEnv: ${DshRuntimeBridge.FALLBACK_KEY_ENV}"))
    }

    @Test
    fun `guest config points the openai provider at the proxy with all models`() {
        val content = OmniRush.configContent("http://127.0.0.1:9/omnirush/v1")
        assertStrictJson(content)
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
    fun `keys with quotes survive embedding without breaking the document`() {
        // Device tokens may carry '"' or '\': opencode's own textual {env:}
        // fill-in would corrupt the JSON and drop the whole provider
        // (provider.no-route on device). We escape before embedding.
        val hostile = "ab\"cd\\ef"
        val content = OmniRush.configContent("http://127.0.0.1:9/omnirush/v1", hostile)
        assertStrictJson(content)
        val apiKey = JSONObject(content)
            .getJSONObject("provider")
            .getJSONObject("omnirush")
            .getJSONObject("options")
            .getString("apiKey")
        assertEquals(hostile, apiKey)
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

    @Test
    fun `authorize response parses the pending approval`() {
        val auth = OmniRush.parseAuthorizeResponse(
            """{"device_code":"d1","user_code":"ABCD-1234","verification_uri":"https://omnirush.ai/x","verification_uri_complete":"https://omnirush.ai/x?c=1","interval":5,"expires_in":600}""",
            nowMillis = 1_000L,
        )
        assertEquals("d1", auth?.deviceCode)
        assertEquals("ABCD-1234", auth?.userCode)
        assertEquals("https://omnirush.ai/x?c=1", auth?.approveUrl)
        assertEquals(5L, auth?.intervalSec)
        assertEquals(1_000L + 600_000L, auth?.expiresAtMillis)
    }

    @Test
    fun `authorize response rejects incomplete payloads and fills defaults`() {
        assertNull(OmniRush.parseAuthorizeResponse("""{"user_code":"X"}"""))
        val auth = OmniRush.parseAuthorizeResponse(
            """{"device_code":"d","user_code":"u","verification_uri_complete":"https://x"}""",
            nowMillis = 0L,
        )
        assertEquals(5L, auth?.intervalSec)
        assertEquals(600_000L, auth?.expiresAtMillis)
    }
}
