package com.jarves.mh.network

import com.jarves.mh.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
