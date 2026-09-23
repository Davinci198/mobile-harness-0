package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile

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
        // NVIDIA's catalog id already embeds `openai/…`; force the `nvidia`
        // namespace so gateway sessions resolve `nvidia/openai/gpt-oss-20b`
        // instead of the non-existent `openai/gpt-oss-20b` route.
        val prefix = when {
            provider.kind == ProviderKind.NVIDIA_NIM -> "nvidia"
            gatewayUrl != null -> "openai"
            else -> opencodeModelPrefix(provider.kind)
        }
        val model = provider.model.ifBlank { provider.kind.defaultModel }
        if (model.isNotBlank()) {
            add("--model")
            if (provider.kind == ProviderKind.NVIDIA_NIM) {
                // `--model` is `provider/modelKey` where modelKey must match the
                // OPENCODE_CONFIG_CONTENT models map. NVIDIA's chat API rejects
                // bare ids (HTTP 404) and requires the full `nvidia/…` body id,
                // so the models map key keeps that prefix and the CLI gets
                // `nvidia/<key>` (e.g. nvidia/nvidia/nemotron-…).
                add("nvidia/${nvidiaApiModelId(model)}")
            } else {
                add(if (prefix != null && !model.startsWith("$prefix/")) "$prefix/$model" else model)
            }
        }
        add(prompt)
    }

    override fun environmentFor(
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
        if (kind == ProviderKind.FREE || secret.isNullOrBlank()) return environment
        if (gatewayUrl != null && provider.routesThroughOpenAiProxy()) {
            when (kind) {
                ProviderKind.NVIDIA_NIM -> {
                    environment["NVIDIA_API_KEY"] = secret
                    environment["NIM_API_KEY"] = secret
                    // Full offline config (HarnessRouter pattern): pin npm package,
                    // baseURL, env-resolved key, the one model for this turn, and
                    // disable workspace snapshots. Catalog fetch is already off above,
                    // so the explicit `models` map is what `--model nvidia/…` resolves.
                    // The key must be the full NVIDIA body id (`nvidia/…`); stripping
                    // the prefix makes chat/completions answer HTTP 404.
                    val modelId = nvidiaApiModelId(provider.model.ifBlank { provider.kind.defaultModel })
                    environment["OPENCODE_CONFIG_CONTENT"] = buildString {
                        append("""{"${'$'}schema":"https://opencode.ai/config.json","snapshot":false,""")
                        append(""""provider":{"nvidia":{""")
                        append(""""npm":"@ai-sdk/openai-compatible",""")
                        append(""""options":{"baseURL":"$gatewayUrl","apiKey":"{env:NVIDIA_API_KEY}"},""")
                        append(""""models":{"$modelId":{}}}}}""")
                    }
                }
                else -> {
                    environment["OPENAI_API_KEY"] = secret
                    environment["OPENAI_BASE_URL"] = gatewayUrl
                }
            }
            return environment
        }
        when (kind) {
            ProviderKind.DEEPSEEK -> environment["DEEPSEEK_API_KEY"] = secret
            ProviderKind.ANTHROPIC -> {
                environment["ANTHROPIC_API_KEY"] = secret
                if (provider.resolvedBaseUrl.isNotBlank() && "api.anthropic.com" !in provider.resolvedBaseUrl) {
                    environment["ANTHROPIC_BASE_URL"] = provider.resolvedBaseUrl
                }
            }
            ProviderKind.LLM_ROUTER -> environment["OPENROUTER_API_KEY"] = secret
            ProviderKind.KIMI -> {
                environment["ANTHROPIC_API_KEY"] = secret
                environment["ANTHROPIC_BASE_URL"] = provider.resolvedBaseUrl
            }
            ProviderKind.OPENCODE_ZEN -> environment["OPENCODE_ZEN_API_KEY"] = secret
            ProviderKind.NVIDIA_NIM -> {
                environment["NIM_API_KEY"] = secret
                environment["NVIDIA_API_KEY"] = secret
                // Direct (no proxy): still pin the model + baseURL offline so a
                // disabled catalog fetch cannot leave `--model nvidia/…` unresolved.
                // Keep the full `nvidia/…` body id — bare ids 404 on NVIDIA's API.
                val modelId = nvidiaApiModelId(provider.model.ifBlank { provider.kind.defaultModel })
                val base = provider.resolvedBaseUrl.ifBlank { "https://integrate.api.nvidia.com/v1" }
                environment["OPENCODE_CONFIG_CONTENT"] = buildString {
                    append("""{"${'$'}schema":"https://opencode.ai/config.json","snapshot":false,""")
                    append(""""provider":{"nvidia":{""")
                    append(""""npm":"@ai-sdk/openai-compatible",""")
                    append(""""options":{"baseURL":"$base","apiKey":"{env:NVIDIA_API_KEY}"},""")
                    append(""""models":{"$modelId":{}}}}}""")
                }
            }
            ProviderKind.CUSTOM -> {
                val api = provider.dshApi.ifBlank { "anthropic-messages" }
                if (api == "openai-completions" || api == "openai-responses") {
                    environment["OPENAI_API_KEY"] = secret
                    environment["OPENAI_BASE_URL"] = provider.resolvedBaseUrl
                } else {
                    environment["ANTHROPIC_API_KEY"] = secret
                    environment["ANTHROPIC_BASE_URL"] = provider.resolvedBaseUrl
                }
            }
            ProviderKind.CLAUDE -> Unit
            ProviderKind.FREE -> Unit
        }
        return environment
    }

    override fun parseJsonlLine(line: String, sessionId: String): CliParsed =
        OpenCodeJsonlParser.parseLine(line, sessionId)

    private fun opencodeModelPrefix(kind: ProviderKind): String? = when (kind) {
        ProviderKind.DEEPSEEK -> "deepseek"
        ProviderKind.ANTHROPIC, ProviderKind.KIMI -> "anthropic"
        ProviderKind.LLM_ROUTER -> "openrouter"
        ProviderKind.OPENCODE_ZEN -> "opencode-zen"
        ProviderKind.NVIDIA_NIM -> "nvidia"
        ProviderKind.CUSTOM -> null
        ProviderKind.FREE -> null
        ProviderKind.CLAUDE -> null
    }
}

/** Full id NVIDIA's `/v1/chat/completions` accepts (prefix required). */
internal fun nvidiaApiModelId(model: String): String =
    if (model.startsWith("nvidia/")) model else "nvidia/$model"
