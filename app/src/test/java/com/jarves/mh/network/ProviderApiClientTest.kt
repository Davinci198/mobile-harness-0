package com.jarves.mh.network

import com.jarves.mh.model.ProviderProtocol
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiClientTest {
    @Test
    fun openAiResponsesProbeUsesStructuredInputWithoutOutputCap() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "muse-spark-1.3-contributor-free",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
            ),
        )

        assertEquals("muse-spark-1.3-contributor-free", body.getString("model"))
        val message = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val content = message.getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", content.getString("type"))
        assertEquals("Hello, reply with 1 word.", content.getString("text"))
        assertEquals(false, body.has("max_output_tokens"))
    }

    @Test
    fun openAiChatProbeUsesMaxTokens16() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "gpt-4o-mini",
                protocol = ProviderProtocol.OPENAI_CHAT,
            ),
        )

        assertEquals(16, body.getInt("max_tokens"))
    }

    @Test
    fun anthropicProbeUsesMaxTokens16() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "claude-sonnet-4-6",
                protocol = ProviderProtocol.ANTHROPIC,
            ),
        )

        assertEquals(16, body.getInt("max_tokens"))
    }

    @Test
    fun probeIndicatesProtocolAcceptsAnyLivePath() {
        val client = ProviderApiClient()

        assertTrue(client.probeIndicatesProtocol(200))
        assertTrue(client.probeIndicatesProtocol(400))
        assertTrue(client.probeIndicatesProtocol(401))
        assertTrue(client.probeIndicatesProtocol(429))
        assertTrue(client.probeIndicatesProtocol(500))
        assertFalse(client.probeIndicatesProtocol(404))
        assertFalse(client.probeIndicatesProtocol(405))
        assertFalse(client.probeIndicatesProtocol(0))
    }

    @Test
    fun normalizeBaseUrlStripsChatAndMessagesSuffixes() {
        val client = ProviderApiClient()

        assertEquals("https://api.example.com", client.normalizeBaseUrl("https://api.example.com/"))
        assertEquals("https://api.example.com/v1", client.normalizeBaseUrl("https://api.example.com/v1/"))
        assertEquals("https://api.example.com/v1", client.normalizeBaseUrl("https://api.example.com/v1/messages"))
        assertEquals("https://api.example.com", client.normalizeBaseUrl("https://api.example.com/chat/completions"))
        assertEquals("https://api.example.com", client.normalizeBaseUrl("https://api.example.com/chat"))
        assertEquals("https://api.example.com/v1", client.normalizeBaseUrl("  https://api.example.com/v1/responses  "))
    }

    @Test
    fun messagesEndpointCandidatesNeverDoubleV1() {
        val client = ProviderApiClient()

        assertEquals(
            listOf("https://api.example.com/v1/messages"),
            client.messagesEndpointCandidates("https://api.example.com/v1", ProviderProtocol.ANTHROPIC),
        )
        assertEquals(
            listOf("https://api.example.com/v1/messages", "https://api.example.com/messages"),
            client.messagesEndpointCandidates("https://api.example.com", ProviderProtocol.ANTHROPIC),
        )
        assertEquals(
            listOf("https://api.example.com/v1/chat/completions", "https://api.example.com/chat/completions"),
            client.messagesEndpointCandidates("https://api.example.com", ProviderProtocol.OPENAI_CHAT),
        )
        assertEquals(
            listOf("https://api.example.com/v1/chat/completions"),
            client.messagesEndpointCandidates("https://api.example.com/v1/chat/completions", ProviderProtocol.OPENAI_CHAT),
        )
        assertEquals(
            listOf("https://openrouter.ai/api/v1/messages", "https://openrouter.ai/api/messages"),
            client.messagesEndpointCandidates("https://openrouter.ai/api", ProviderProtocol.OPENROUTER),
        )
        assertEquals(
            listOf("https://api.anthropic.com/anthropic/v1/messages", "https://api.anthropic.com/anthropic/messages"),
            client.messagesEndpointCandidates("https://api.anthropic.com/anthropic", ProviderProtocol.ANTHROPIC),
        )
    }

    @Test
    fun modelEndpointsProbeBothV1LayoutsForOpenAiChat() {
        val client = ProviderApiClient()

        assertEquals(
            listOf("https://api.example.com/models", "https://api.example.com/v1/models"),
            client.modelEndpoints("https://api.example.com", ProviderProtocol.OPENAI_CHAT),
        )
        assertEquals(
            listOf("https://api.example.com/v1/models"),
            client.modelEndpoints("https://api.example.com/v1", ProviderProtocol.OPENAI_CHAT),
        )
    }

    @Test
    fun requestPreviewUrlShowsTheFirstProbedEndpoint() {
        val client = ProviderApiClient()

        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            client.requestPreviewUrl("http://localhost:11434", ProviderProtocol.OPENAI_CHAT),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            client.requestPreviewUrl("https://api.anthropic.com", ProviderProtocol.ANTHROPIC_GATEWAY),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/messages",
            client.requestPreviewUrl("https://openrouter.ai/api", ProviderProtocol.OPENROUTER),
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            client.requestPreviewUrl("https://api.example.com/chat/completions", ProviderProtocol.OPENAI_CHAT),
        )
    }

    @Test
    fun remoteEndpointWithoutKeyIsRejectedBeforeAnyRequest() = runBlocking {
        val result = ProviderApiClient().validate(
            baseUrl = "https://api.anthropic.com",
            model = "claude-sonnet-4-6",
            apiKey = "",
            protocol = ProviderProtocol.ANTHROPIC_GATEWAY,
            discoveredModels = emptyList(),
        )

        assertTrue(result is ConnectionValidation.Failure)
    }

    @Test
    fun nousFreeTierEndpointIsRecognizedByHostOnly() {
        val client = ProviderApiClient()

        assertTrue(client.isNousFreeTier("https://inference-api.nousresearch.com/v1"))
        assertTrue(client.isNousFreeTier("https://inference-api.nousresearch.com/v1/"))
        assertTrue(client.isNousFreeTier("https://inference-api.nousresearch.com/v1/models"))
        assertTrue(client.isNousFreeTier("http://inference-api.nousresearch.com"))
        assertFalse(client.isNousFreeTier("https://inference-api.nousresearch.com.evil.example/v1"))
        assertFalse(client.isNousFreeTier("https://api.nousresearch.com/v1"))
        assertFalse(client.isNousFreeTier("https://opencode.ai/zen/v1"))
        assertFalse(client.isNousFreeTier(""))
    }

    @Test
    fun nousFreeTierCatalogProbesV1ModelsAndIgnoresTheDoubledPath() {
        assertEquals(
            listOf(
                "https://inference-api.nousresearch.com/v1/v1/models",
                "https://inference-api.nousresearch.com/v1/models",
            ),
            ProviderApiClient().modelEndpoints(
                "https://inference-api.nousresearch.com/v1",
                ProviderProtocol.ANTHROPIC_GATEWAY,
            ),
        )
    }

    @Test
    fun nousAccessTokenParsesTheGuestAuthFile() {
        val now = 1_000_000_000L

        assertEquals("guest-token", parseNousAccessToken(nousAuthJson(now + 3_600), now))
        // No expiry recorded: trust the token and let the server reject it if stale.
        assertEquals("guest-token", parseNousAccessToken(nousAuthJson(expiresAtEpoch = null), now))
    }

    @Test
    fun nousAccessTokenRejectsExpiredOrBrokenAuthFiles() {
        val now = 1_000_000_000L

        // Already expired, or about to expire inside the safety margin.
        assertNull(parseNousAccessToken(nousAuthJson(now - 60), now))
        assertNull(parseNousAccessToken(nousAuthJson(now + 10), now))
        // Blank token, missing identity, malformed file.
        assertNull(parseNousAccessToken(nousAuthJson(now + 3_600, token = ""), now))
        assertNull(parseNousAccessToken("""{"providers":{}}""", now))
        assertNull(parseNousAccessToken("not json at all", now))
    }

    private fun nousAuthJson(expiresAtEpoch: Long?, token: String = "guest-token"): String {
        val expiresAt = expiresAtEpoch?.let {
            OffsetDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneOffset.UTC).toString()
        }
        val nous = JSONObject().put("access_token", token)
        if (expiresAt != null) nous.put("expires_at", expiresAt)
        return JSONObject().put("providers", JSONObject().put("nous", nous)).toString()
    }
}
