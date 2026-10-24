package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.isLoopbackBaseUrl

/**
 * Headless OpenCode v2 bridge: `opencode run --standalone --format json --auto`.
 * Model routing uses OpenCode's `provider/model` namespace plus the matching
 * provider API-key environment variable. Secretless providers (Free) simply
 * omit the key so OpenCode falls back to its already-configured credentials.
 *
 * Verified against opencode v2.0.12 in the guest runtime: `--format json`
 * prints newline-delimited events carrying text/reasoning/tool parts and a
 * terminal `error` object.
 */
internal class OpenCodeRuntimeBridge(
    context: Context,
    secretFor: (ProviderProfile) -> String?,
) : HeadlessCliBridge(context, secretFor) {
    override val kind: AgentKind get() = AgentKind.OPENCODE
    override val guestExecutable: String get() = RuntimeInstaller.OPENCODE_GUEST_PATH

    override fun commandFor(
        prompt: String,
        provider: ProviderProfile,
        secret: String?,
        guestWorkspacePath: String,
        gatewayUrl: String?,
    ): List<String> = buildList {
        add(guestExecutable)
        add("run")
        add("--standalone")
        add("--format")
        add("json")
        add("--auto")
        // Emit reasoning parts; without this the JSONL stream has no thinking blocks.
        add("--thinking")
        val model = provider.model.ifBlank { provider.kind.defaultModel }
        if (model.isNotBlank()) {
            add("--model")
            add(opencodeModelArg(provider, gatewayUrl, model))
        }
        add(prompt)
    }

    override fun environmentFor(
        provider: ProviderProfile,
        secret: String?,
        gatewayUrl: String?,
    ): Map<String, String> = openCodeEnvironmentFor(provider, secret, gatewayUrl)

    override fun parseJsonlLine(line: String, sessionId: String): CliParsed =
        OpenCodeJsonlParser.parseLine(line, sessionId)
}

/**
 * Process environment for an OpenCode Agent turn.
 *
 * Kept out of the bridge so the routing rules stay unit-testable without an
 * Android [Context].
 */
internal fun openCodeEnvironmentFor(
    provider: ProviderProfile,
    secret: String?,
    gatewayUrl: String?,
): Map<String, String> {
    val environment = linkedMapOf<String, String>(
        "HOME" to "/root",
        "OPENCODE_DISABLE_AUTOUPDATE" to "1",
        // models.dev / models.opencode.ai fetches hang for minutes when guest
        // DNS is dead (app backgrounded) and can stall `opencode run` until
        // the process is killed with zero stdout. The bundled catalog already
        // resolves nvidia/* models offline.
        "OPENCODE_DISABLE_MODELS_FETCH" to "true",
    )
    val kind = provider.kind
    if (kind == ProviderKind.FREE) return environment
    val rawKey = secret.orEmpty()
    // A loopback gateway on this device runs keyless: keep the guest pointed
    // at it instead of falling back to the provider's public endpoint.
    // OpenCode Zen's free tier is keyless too (its catalog answers
    // anonymously), so it must not be blocked by the missing-key guard.
    if (rawKey.isBlank() && !isLoopbackBaseUrl(provider.resolvedBaseUrl) &&
        kind != ProviderKind.OPENCODE_ZEN
    ) {
        return environment
    }
    // Guest CLIs refuse to boot with an empty key variable, while a loopback
    // gateway ignores the header — send a placeholder instead of nothing.
    val key = rawKey.ifBlank { "loopback" }
    if (gatewayUrl != null && provider.routesThroughOpenAiProxy()) {
        when (kind) {
            ProviderKind.NVIDIA_NIM -> {
                environment["NVIDIA_API_KEY"] = key
                environment["NIM_API_KEY"] = key
                // Full offline config (HarnessRouter pattern): pin npm package,
                // baseURL, env-resolved key, the one model for this turn, and
                // disable workspace snapshots. Catalog fetch is already off above,
                // so the explicit `models` map is what `--model nvidia/…` resolves.
                // The key must be the exact /v1/models catalog id — bare ids and
                // synthetic `nvidia/<vendor>/…` prefixes answer HTTP 404.
                val modelId = nvidiaApiModelId(provider.model.ifBlank { provider.kind.defaultModel })
                environment["OPENCODE_CONFIG_CONTENT"] =
                    openAiCompatibleConfigContent("nvidia", gatewayUrl, "NVIDIA_API_KEY", modelId)
            }
            else -> {
                environment["OPENAI_API_KEY"] = key
                environment["OPENAI_BASE_URL"] = gatewayUrl
                val model = provider.model.ifBlank { provider.kind.defaultModel }
                if (provider.isOpenAiCompatibleCustom() && model.isNotBlank()) {
                    environment["OPENCODE_CONFIG_CONTENT"] =
                        openAiCompatibleConfigContent("openai", gatewayUrl, "OPENAI_API_KEY", model)
                }
            }
        }
        return environment
    }
    when (kind) {
        ProviderKind.DEEPSEEK -> environment["DEEPSEEK_API_KEY"] = key
        ProviderKind.ANTHROPIC -> {
            environment["ANTHROPIC_API_KEY"] = key
            if (provider.resolvedBaseUrl.isNotBlank() && "api.anthropic.com" !in provider.resolvedBaseUrl) {
                environment["ANTHROPIC_BASE_URL"] = provider.resolvedBaseUrl
            }
        }
        ProviderKind.LLM_ROUTER -> environment["OPENROUTER_API_KEY"] = key
        ProviderKind.KIMI -> {
            environment["ANTHROPIC_API_KEY"] = key
            environment["ANTHROPIC_BASE_URL"] = provider.resolvedBaseUrl
        }
        // Zen's provider id in the OpenCode catalog is `opencode`, which reads
        // OPENCODE_API_KEY — there is no `opencode-zen` provider or env var.
        ProviderKind.OPENCODE_ZEN -> {
            if (rawKey.isNotBlank()) environment["OPENCODE_API_KEY"] = rawKey
            // The zen model list is served by the provider itself, not by
            // models.dev, and the guest's catalog fetch is off (it hangs
            // when the guest DNS dies). Without a pinned route the turn
            // failed with "Model unavailable: opencode/<model>" — the
            // provider had no route for a model no catalog mentioned.
            // Pinning the provider with the one model the turn needs
            // resolves it offline, exactly like the nvidia/custom paths.
            val model = provider.model.ifBlank { provider.kind.defaultModel }
            if (model.isNotBlank()) {
                environment["OPENCODE_CONFIG_CONTENT"] = openAiCompatibleConfigContent(
                    providerId = "opencode",
                    baseUrl = provider.resolvedBaseUrl,
                    // Zen's free tier is keyless: a placeholder key would
                    // be sent as an Authorization header it does not want.
                    apiKeyEnv = if (rawKey.isNotBlank()) "OPENCODE_API_KEY" else null,
                    modelId = model,
                )
            }
        }
        ProviderKind.NVIDIA_NIM -> {
            environment["NIM_API_KEY"] = key
            environment["NVIDIA_API_KEY"] = key
            // Direct (no proxy): still pin the model + baseURL offline so a
            // disabled catalog fetch cannot leave `--model nvidia/…` unresolved.
            // Key = exact /v1/models catalog id — bare ids and synthetic
            // `nvidia/<vendor>/…` prefixes answer HTTP 404.
            val modelId = nvidiaApiModelId(provider.model.ifBlank { provider.kind.defaultModel })
            val base = provider.resolvedBaseUrl.ifBlank { "https://integrate.api.nvidia.com/v1" }
            environment["OPENCODE_CONFIG_CONTENT"] =
                openAiCompatibleConfigContent("nvidia", base, "NVIDIA_API_KEY", modelId)
        }
        // Kilo Free never reaches here through the picker; if a stored profile
        // does, pin the keyless route (a placeholder key would be sent as an
        // Authorization header Kilo's free pool rejects).
        ProviderKind.KILO_FREE -> {
            val model = provider.model.ifBlank { provider.kind.defaultModel }
            if (model.isNotBlank()) {
                environment["OPENCODE_CONFIG_CONTENT"] = openAiCompatibleConfigContent(
                    providerId = "kilo",
                    baseUrl = provider.resolvedBaseUrl,
                    apiKeyEnv = null,
                    modelId = model,
                )
            }
        }
        ProviderKind.CUSTOM -> {
            val api = provider.dshApi.ifBlank { "anthropic-messages" }
            if (isOpenAiCompatibleApi(api)) {
                environment["OPENAI_API_KEY"] = key
                environment["OPENAI_BASE_URL"] = provider.resolvedBaseUrl
                val model = provider.model.ifBlank { provider.kind.defaultModel }
                if (provider.resolvedBaseUrl.isNotBlank() && model.isNotBlank()) {
                    environment["OPENCODE_CONFIG_CONTENT"] =
                        openAiCompatibleConfigContent("openai", provider.resolvedBaseUrl, "OPENAI_API_KEY", model)
                }
            } else {
                environment["ANTHROPIC_API_KEY"] = key
                environment["ANTHROPIC_BASE_URL"] = provider.resolvedBaseUrl
            }
        }
        ProviderKind.CLAUDE -> Unit
        ProviderKind.FREE -> Unit
    }
    return environment
}

internal fun isOpenAiCompatibleApi(api: String): Boolean =
    api == "openai-completions" || api == "openai-responses"

internal fun ProviderProfile.isOpenAiCompatibleCustom(): Boolean =
    kind == ProviderKind.CUSTOM && isOpenAiCompatibleApi(dshApi.ifBlank { "anthropic-messages" })

internal fun openAiCompatibleConfigContent(
    providerId: String,
    baseUrl: String,
    apiKeyEnv: String?,
    modelId: String,
): String = buildString {
    append("""{"${'$'}schema":"https://opencode.ai/config.json","snapshot":false,""")
    append(""""provider":{"$providerId":{""")
    append(""""npm":"@ai-sdk/openai-compatible",""")
    if (apiKeyEnv.isNullOrBlank()) {
        // A keyless gateway (OpenCode Zen's free tier) must not see an
        // Authorization header: it serves the OpenCode client only, and a
        // placeholder key is worse than no key at all.
        append(""""options":{"baseURL":"$baseUrl"},""")
    } else {
        append(""""options":{"baseURL":"$baseUrl","apiKey":"{env:$apiKeyEnv}"},""")
    }
    append(""""models":{"$modelId":{}}}}}""")
}

internal fun opencodeModelPrefix(kind: ProviderKind): String? = when (kind) {
    ProviderKind.DEEPSEEK -> "deepseek"
    ProviderKind.ANTHROPIC, ProviderKind.KIMI -> "anthropic"
    ProviderKind.LLM_ROUTER -> "openrouter"
    // The catalog provider for Zen is `opencode`, so the CLI flag must be
    // `opencode/<model>`; `opencode-zen/…` resolves to nothing ("Unexpected server error").
    ProviderKind.OPENCODE_ZEN -> "opencode"
    ProviderKind.NVIDIA_NIM -> "nvidia"
    // Kilo Free only rides DeepSeek Harness; a stored profile picked here
    // would prefix with its own provider id from OPENCODE_CONFIG_CONTENT.
    ProviderKind.KILO_FREE -> "kilo"
    ProviderKind.CUSTOM -> null
    ProviderKind.FREE -> null
    ProviderKind.CLAUDE -> null
}

internal fun opencodeModelArg(provider: ProviderProfile, gatewayUrl: String?, model: String): String = when {
    provider.kind == ProviderKind.NVIDIA_NIM -> {
        // `--model` is `provider/modelKey` where modelKey must match the
        // OPENCODE_CONFIG_CONTENT models map; modelKey doubles as the chat
        // body id, so it must be the exact /v1/models catalog id (see
        // nvidiaApiModelId). The CLI gets `nvidia/<key>` — e.g.
        // nvidia/nvidia/nemotron-… for an nvidia/… key, or
        // nvidia/google/gemma-4-31b-it for a google/… key.
        "nvidia/${nvidiaApiModelId(model)}"
    }
    provider.isOpenAiCompatibleCustom() -> "openai/$model"
    else -> {
        val prefix = gatewayUrl?.let { "openai" } ?: opencodeModelPrefix(provider.kind)
        if (prefix != null && !model.startsWith("$prefix/")) "$prefix/$model" else model
    }
}

/** Id accepted by NVIDIA's `/v1/chat/completions`: the exact `/v1/models` catalog id. */
internal fun nvidiaApiModelId(model: String): String =
    // chat/completions must receive the exact /v1/models catalog id:
    // `nvidia/nemotron-3-super-120b-a12b` and `google/gemma-4-31b-it` are both
    // valid as-is, while bare keys (`nemotron-3-super-120b-a12b`) 404 without
    // the nvidia/ namespace and synthetic prefixes (`nvidia/google/…`) 404 too.
    if ("/" in model) model else "nvidia/$model"
