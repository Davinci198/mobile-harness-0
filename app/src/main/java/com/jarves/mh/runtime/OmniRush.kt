package com.jarves.mh.runtime

/**
 * OmniRush gateway contract (reverse-engineered from the omnirush CLI 2.4.16
 * and verified live with `scripts/omnirush-probe.sh`, 3/3 models OK).
 *
 * The gateway serves its models ONLY through the Responses API with extra
 * constraints; plain `chat/completions` answers 404 `unsupported_model_endpoint`:
 * - `POST {base}/responses` with `store:false`, `stream:true` and `input` as a
 *   list (a string input is rejected with 400 "Input must be a list").
 * - every request carries `User-Agent: omnirush` (bare product name — a
 *   versioned `omnirush/x.y` UA is refused as a lookalike) plus
 *   `X-OmniRush-Client: cli/<version>`.
 * - the version gate is a floor, not an exact match: any `cli/<v>` with
 *   `v >= minimum` passes; below it the gateway answers 426
 *   `update_required` naming the new `"minimum"`. The floor moves with the
 *   CLI's near-daily releases, so Mobile-Harness never hardcodes the label:
 *   it sends the last known-good version from prefs and auto-bumps it from a
 *   426 response, retrying once.
 *
 * Auth is a rotating device-token pair (`POST {manager}/device/refresh`
 * with `{refresh_token}` rotates both). The access token lives in the vault
 * as the provider's active key; the refresh token as a second named key.
 */
internal object OmniRush {
    /** Manager origin; the model plane is [BASE_URL], the auth plane is [MANAGER_URL]. */
    const val MANAGER_URL = "https://omnirush.ai/omnirush"
    const val BASE_URL = "https://omnirush.ai/omnirush/v1"
    const val DEFAULT_MODEL = "gpt-6-astra"

    /** Catalog snapshot (id to display name), used for the guest opencode config. */
    val MODELS: Map<String, String> = linkedMapOf(
        "gpt-6-astra" to "GPT 6 Astra",
        "gpt-6-sol" to "GPT 6 Sol",
        "gpt-6.1-sol" to "GPT 6.1 Sol",
    )

    const val CLIENT_USER_AGENT = "omnirush"
    const val CLIENT_HEADER = "X-OmniRush-Client"

    /** Last version verified against the gateway; the stored pref starts here. */
    const val FALLBACK_CLIENT_VERSION = "2.4.16"

    /** Name of the vault key holding the refresh token (the active key holds the access token). */
    const val REFRESH_KEY_NAME = "refresh"

    fun clientHeaderValue(version: String): String = "cli/$version"

    /**
     * Parses the `"minimum"` version out of a 426 `update_required` body, or
     * null when the body carries no floor (not a version-gate answer).
     */
    fun parseUpdateFloor(body: String): String? {
        val match = Regex(""""minimum"\s*:\s*"([^"]+)"""").find(body) ?: return null
        return match.groupValues[1].takeIf { it.isNotBlank() }
    }

    /** True when [candidate] is a strictly newer dotted version than [current]. */
    fun isNewerVersion(candidate: String, current: String): Boolean {
        val left = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val right = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrElse(index) { 0 }) - (right.getOrElse(index) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }

    /** Refresh request body for `{manager}/device/refresh`. */
    fun refreshRequestBody(refreshToken: String): String =
        """{"refresh_token":"${refreshToken.replace("\"", "")}"}"""

    /**
     * Blocking device-token rotation against `{manager}/device/refresh`.
     * Returns the new (access, refresh) pair, or null when the refresh token
     * is dead (401/409/signed out) or the network fails. Call off the main
     * thread — the loopback proxy invokes this from its request threads.
     */
    fun refreshNow(refreshToken: String, clientVersion: String): Pair<String, String>? = runCatching {
        val url = java.net.URL("$MANAGER_URL/device/refresh")
        val connection = url.openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", CLIENT_USER_AGENT)
            connection.setRequestProperty(CLIENT_HEADER, clientHeaderValue(clientVersion))
            val body = refreshRequestBody(refreshToken).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.use { it.readBytes().decodeToString() }
            parseRefreshResponse(text)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * Parses a 200 rotation answer `{access_token, refresh_token}` into the
     * new pair, or null when the body is not a rotation answer.
     */
    fun parseRefreshResponse(body: String): Pair<String, String>? {
        val access = Regex(""""access_token"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        val refresh = Regex(""""refresh_token"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) return null
        return access to refresh
    }

    /**
     * Guest opencode provider config for OmniRush (mirrors the CLI's own
     * `buildOpencodeConfig`): the `@ai-sdk/openai` provider speaks /responses,
     * pointed at our loopback proxy which injects the client gate headers.
     *
     * Models carry id + display name ONLY: opencode v2.0.28 drops the whole
     * provider (no-route) when a model entry carries a `limit` map
     * (verified live in the guest: identical config without `limit` resolves).
     */
    fun configContent(gatewayUrl: String): String = buildString {
        append("""{"${'$'}schema":"https://opencode.ai/config.json","snapshot":false,""")
        append(""""provider":{"omnirush":{""")
        append(""""npm":"@ai-sdk/openai",""")
        append(""""name":"omnirush.ai",""")
        append(""""options":{"baseURL":"$gatewayUrl","apiKey":"{env:OMNIRUSH_ACCESS_TOKEN}"},""")
        append(""""models":{""")
        MODELS.entries.forEachIndexed { index, (id, display) ->
            if (index > 0) append(",")
            append(""""$id":{"name":"$display"}""")
        }
        append("}}}}}")
    }
}

/**
 * App-side OmniRush session state handed to [LocalOpenAiProxy]: the client
 * label is read live (never hardcoded at the call site) and the floor bump
 * plus token refresh persist back to prefs/vault through these callbacks.
 */
internal data class OmniRushProxyAuth(
    /** Current `cli/<v>` label version, e.g. from prefs. */
    val clientVersion: () -> String,
    /** Persist a newer floor observed from a 426 answer. */
    val onFloor: (String) -> Unit,
    /**
     * Rotate the device-token pair after a 401 and persist both sides;
     * returns the fresh access token, or null when signed out.
     */
    val refreshAccessToken: () -> String?,
)
