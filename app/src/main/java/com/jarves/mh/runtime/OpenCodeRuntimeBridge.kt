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
            add(if (prefix != null && !model.startsWith("$prefix/")) "$prefix/$model" else model)
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
        )
        val kind = provider.kind
        if (kind == ProviderKind.FREE || secret.isNullOrBlank()) return environment
        if (gatewayUrl != null && provider.routesThroughOpenAiProxy()) {
            when (kind) {
                ProviderKind.NVIDIA_NIM -> {
                    environment["NVIDIA_API_KEY"] = secret
                    environment["NIM_API_KEY"] = secret
                    environment["OPENCODE_CONFIG_CONTENT"] =
                        """{"provider":{"nvidia":{"options":{"baseURL":"$gatewayUrl"}}}}"""
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