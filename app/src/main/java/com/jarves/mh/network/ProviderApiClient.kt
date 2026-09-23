package com.jarves.mh.network

import com.jarves.mh.model.ProviderProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class DiscoveredModel(val id: String, val displayName: String = id, val isFree: Boolean = false)

enum class ModelHealthStatus { OK, FAIL, TIMEOUT, ERROR }

data class ModelHealth(
    val modelId: String,
    val status: ModelHealthStatus,
    val latencyMs: Long,
    val httpCode: Int = 0,
    val detail: String? = null,
) {
    val isBroken: Boolean get() = status != ModelHealthStatus.OK
}

sealed interface ModelDiscoveryResult {
    data class Success(val models: List<DiscoveredModel>, val endpoint: String) : ModelDiscoveryResult
    data class Failure(val message: String, val providerMessage: String? = null) : ModelDiscoveryResult
}

sealed interface ConnectionValidation {
    data class Success(val message: String) : ConnectionValidation
    data class Failure(
        val message: String,
        val providerMessage: String? = null,
        val label: String = "Failed",
    ) : ConnectionValidation
}

class ProviderApiClient {
    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val cleanBaseUrl = normalizeBaseUrl(baseUrl)
        val cleanKey = sanitizeApiKey(apiKey)
        if (cleanBaseUrl.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure("Enter a base URL first.")
        }

        var authError = false
        var lastMessage = "This provider did not expose a model list. You can enter a custom model name."
        var lastProviderMessage: String? = null
        for (endpoint in modelEndpoints(cleanBaseUrl, protocol)) {
            // OpenRouter's complete catalog is public. Fetch it anonymously even when
            // OpenRouter is configured through Custom API so an account-scoped key does
            // not reduce discovery to the models allowed by that key's preferences.
            // The saved key is still used for validation and all inference requests.
            val discoveryKey = if (isOpenRouterCatalogEndpoint(endpoint)) "" else cleanKey
            val response = request(endpoint, "GET", discoveryKey, protocol = protocol)
            when {
                response.code == 401 || response.code == 403 -> {
                    authError = true
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.code in 200..299 -> {
                    val models = ModelResponseParser.parse(response.body)
                    if (models.isNotEmpty()) return@withContext ModelDiscoveryResult.Success(models, endpoint)
                    lastMessage = "The provider replied, but its model list was empty or unsupported."
                }
                response.code > 0 && response.code != 404 -> {
                    lastMessage = friendlyHttpError(response.code)
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.error != null -> lastMessage = response.error
            }
        }
        ModelDiscoveryResult.Failure(
            if (authError) "Check the saved API key, then try refreshing again." else lastMessage,
            lastProviderMessage,
        )
    }

    /**
     * Probe each model with a tiny completion (same idea as the external
     * functionez scanner). Streams per-model health so the UI can print a
     * terminal-style log while the batch runs.
     */
    suspend fun validateModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
        models: List<DiscoveredModel>,
        concurrency: Int = 5,
        onProgress: suspend (ModelHealth) -> Unit = {},
    ): List<ModelHealth> = withContext(Dispatchers.IO) {
        val cleanBaseUrl = normalizeBaseUrl(baseUrl)
        val cleanKey = sanitizeApiKey(apiKey)
        if (cleanBaseUrl.isBlank() || models.isEmpty()) return@withContext emptyList()
        val endpoints = messagesEndpointCandidates(cleanBaseUrl, protocol)
        val results = Collections.synchronizedList(mutableListOf<ModelHealth>())
        val semaphore = Semaphore(concurrency.coerceIn(1, 10))
        coroutineScope {
            models.map { model ->
                async {
                    semaphore.withPermit {
                        val health = probeModel(endpoints, model.id, cleanKey, protocol)
                        results.add(health)
                        onProgress(health)
                    }
                }
            }.awaitAll()
        }
        results.toList()
    }

    private fun probeModel(
        endpoints: List<String>,
        modelId: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelHealth {
        val body = validationBody(modelId, protocol)
        var last: ModelHealth = ModelHealth(modelId, ModelHealthStatus.ERROR, 0L, 0, "No endpoint")
        for (endpoint in endpoints) {
            val started = System.currentTimeMillis()
            val response = request(endpoint, "POST", apiKey, body, protocol, connectTimeoutMs = 12_000, readTimeoutMs = 30_000)
            val elapsed = System.currentTimeMillis() - started
            last = healthFromResponse(modelId, response.code, response.body, response.error, elapsed)
            if (response.code != 404 && response.code != 0) return last
        }
        return last
    }

    internal fun healthFromResponse(
        modelId: String,
        code: Int,
        body: String,
        error: String?,
        elapsedMs: Long,
    ): ModelHealth = when {
        code in 200..299 -> ModelHealth(modelId, ModelHealthStatus.OK, elapsedMs, code)
        error != null && (error.contains("timeout", ignoreCase = true) || error.contains("timed out", ignoreCase = true)) ->
            ModelHealth(modelId, ModelHealthStatus.TIMEOUT, elapsedMs, 0, error.take(160))
        code in 400..599 ->
            ModelHealth(modelId, ModelHealthStatus.FAIL, elapsedMs, code, providerErrorMessage(body) ?: error ?: "HTTP $code")
        error != null ->
            ModelHealth(modelId, ModelHealthStatus.ERROR, elapsedMs, 0, error.take(160))
        else -> ModelHealth(modelId, ModelHealthStatus.ERROR, elapsedMs, code, "HTTP $code")
    }

    suspend fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        discoveredModels: List<DiscoveredModel>,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        val cleanBaseUrl = normalizeBaseUrl(baseUrl)
        val cleanModel = model.trim()
        val cleanKey = sanitizeApiKey(apiKey)
        if (cleanBaseUrl.isBlank() || cleanModel.isBlank() || cleanKey.isBlank()) {
            return@withContext ConnectionValidation.Failure("Base URL, model, and API key are required.")
        }
        val body = validationBody(cleanModel, protocol)
        // Probe candidate paths so a base URL that already ends in /v1 or
        // /chat/completions never becomes /v1/v1/messages or /chat/chat/…
        // Gateways may need to cold-start a model before returning the first token.
        var authRejected = false
        var modelRejected = false
        var lastCode = 0
        var lastBody = ""
        var lastError: String? = null
        for (endpoint in messagesEndpointCandidates(cleanBaseUrl, protocol)) {
            val response = request(endpoint, "POST", cleanKey, body, protocol, connectTimeoutMs = 12_000, readTimeoutMs = 45_000)
            when {
                response.code in 200..299 -> return@withContext ConnectionValidation.Success(
                    if (protocol == ProviderProtocol.ANTHROPIC || protocol == ProviderProtocol.ANTHROPIC_GATEWAY || protocol == ProviderProtocol.OPENROUTER) {
                        "Anthropic Messages endpoint verified. Claude Code settings are ready."
                    } else {
                        "Connection successful. Claude Code settings are ready."
                    },
                )
                response.code == 401 || response.code == 403 -> {
                    authRejected = true
                    lastCode = response.code
                    lastBody = response.body
                }
                response.code == 400 && response.body.contains("model", ignoreCase = true) -> {
                    modelRejected = true
                    lastCode = response.code
                    lastBody = response.body
                }
                response.code > 0 -> {
                    lastCode = response.code
                    lastBody = response.body
                }
                response.error != null -> lastError = response.error
            }
        }
        when {
            authRejected -> ConnectionValidation.Failure(
                "Check this API key or select another saved key.",
                providerErrorMessage(lastBody),
                "Rejected",
            )
            modelRejected -> ConnectionValidation.Failure(
                "Refresh the model list or select a different model.",
                providerErrorMessage(lastBody),
                "Model error",
            )
            lastCode == 404 -> ConnectionValidation.Failure(
                "Check the Base URL and selected gateway protocol.",
                providerErrorMessage(lastBody),
                "Endpoint error",
            )
            lastCode == 429 -> ConnectionValidation.Failure(
                "Wait a moment, then retry or use another API key.",
                providerErrorMessage(lastBody),
                "Rate limited",
            )
            lastCode in 500..599 -> ConnectionValidation.Failure(
                "The provider is temporarily unavailable. Try again shortly.",
                providerErrorMessage(lastBody),
                "Provider error",
            )
            lastCode > 0 -> ConnectionValidation.Failure(
                "Review the model, protocol, and endpoint settings.",
                providerErrorMessage(lastBody),
                "Request failed",
            )
            lastError?.contains("timeout", ignoreCase = true) == true ||
                lastError?.contains("timed out", ignoreCase = true) == true ->
                ConnectionValidation.Failure("Check your connection and try again.", lastError, "Timed out")
            else -> ConnectionValidation.Failure(
                "Check your internet connection and provider settings.",
                lastError,
                "Network error",
            )
        }
    }

    private fun request(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String? = null,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResult {
        return runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                if (endpoint.startsWith("https://opencode.ai/zen/")) {
                    // OpenCode Zen expects requests to identify the OpenCode client and session.
                    setRequestProperty("User-Agent", "opencode/1.18.20")
                    setRequestProperty("x-session-id", "session-${UUID.randomUUID()}")
                } else {
                    setRequestProperty("User-Agent", "MobileHarness/1.0 (Android)")
                }
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                if (apiKey.isNotBlank() && protocol != ProviderProtocol.OPENROUTER && protocol != ProviderProtocol.OPENAI_CHAT && protocol != ProviderProtocol.OPENAI_RESPONSES) {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                if (body != null) doOutput = true
            }
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            HttpResult(code, responseBody)
        }.getOrElse { HttpResult(0, "", it.message ?: "Network connection failed",) }
    }

    /**
     * Strip path suffixes users paste into the Base URL field so we never probe
     * doubled endpoints like /v1/v1/messages or /chat/chat/completions.
     */
    internal fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        val suffixes = listOf(
            "/chat/completions",
            "/chat",
            "/completions",
            "/messages",
            "/responses",
        )
        for (suffix in suffixes) {
            if (base.endsWith(suffix, ignoreCase = true)) {
                base = base.substring(0, base.length - suffix.length).trimEnd('/')
                break
            }
        }
        return base
    }

    private fun sanitizeApiKey(raw: String): String =
        raw.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

    internal fun modelEndpoints(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = normalizeBaseUrl(baseUrl)
        val withoutAnthropic = base.removeSuffix("/anthropic")
        val candidates = when (protocol) {
            ProviderProtocol.OPENROUTER -> listOf("$base/v1/models")
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> buildList {
                add("$base/models")
                if (!base.endsWith("/v1")) add("$base/v1/models")
            }
            else -> listOf("$base/v1/models", "$base/models", "$withoutAnthropic/models", "$withoutAnthropic/v1/models")
        }
        return candidates.distinct()
    }

    /**
     * Candidate chat endpoints ordered most→least likely. A base URL that
     * already ends in /v1 gets the path appended directly (never /v1/v1/…).
     */
    internal fun messagesEndpointCandidates(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = normalizeBaseUrl(baseUrl)
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> buildList {
                if (base.endsWith("/v1")) {
                    add("$base/messages")
                } else {
                    add("$base/v1/messages")
                    add("$base/messages")
                }
            }.distinct()
            ProviderProtocol.OPENAI_CHAT -> buildList {
                if (base.endsWith("/v1")) {
                    add("$base/chat/completions")
                } else {
                    add("$base/v1/chat/completions")
                    add("$base/chat/completions")
                }
            }.distinct()
            ProviderProtocol.OPENAI_RESPONSES -> buildList {
                if (base.endsWith("/v1")) {
                    add("$base/responses")
                } else {
                    add("$base/v1/responses")
                    add("$base/responses")
                }
            }.distinct()
            else -> buildList {
                if (base.endsWith("/v1")) {
                    add("$base/messages")
                } else {
                    add("$base/v1/messages")
                    add("$base/messages")
                }
            }.distinct()
        }
    }

    private fun isOpenRouterCatalogEndpoint(endpoint: String): Boolean = runCatching {
        val url = URL(endpoint)
        url.host.equals("openrouter.ai", ignoreCase = true) &&
            url.path.trimEnd('/').endsWith("/models")
    }.getOrDefault(false)

    internal fun validationBody(model: String, protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", "Hello, reply with 1 word."),
                            ),
                        ),
                ),
            )
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            // Some gateways reject max_tokens <= 2; 16 is enough for a ping.
            .put("max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
    }

    private fun friendlyHttpError(code: Int): String = when (code) {
        429 -> "The provider rate limit was reached. Wait a moment and try again."
        in 500..599 -> "The provider is temporarily unavailable (HTTP $code)."
        else -> "The provider returned HTTP $code. Check the URL and account access."
    }

    private fun providerErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val extracted = runCatching {
            val root = JSONObject(body)
            when (val error = root.opt("error")) {
                is JSONObject -> error.optString("message").ifBlank { error.optString("detail") }
                is String -> error
                else -> root.optString("message").ifBlank { root.optString("detail") }
            }
        }.getOrNull().orEmpty()
        if (extracted.isBlank()) return null
        return extracted
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)
}

object ModelResponseParser {
    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val trimmed = json.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> add(DiscoveredModel(item))
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isNotBlank()) {
                            val label = item.optString("display_name").ifBlank { item.optString("displayName") }.ifBlank { id }
                            val pricing = item.optJSONObject("pricing")
                            val free = id.endsWith(":free", ignoreCase = true) || pricing?.let {
                                listOf("prompt", "completion", "request").all { field ->
                                    it.optString(field, "0").toDoubleOrNull() == 0.0
                                }
                            } == true
                            add(DiscoveredModel(id, label, free))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.sortedWith(compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() })
    }.getOrDefault(emptyList())
}
