package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile

/**
 * Headless Hermes bridge (Nous Research): `hermes chat --quiet
 * --format stream-json [--provider X] [--model Y] -q "<prompt>"` prints
 * newline-delimited events. Model routing uses Hermes' own provider names and
 * the matching API-key environment variables; the Free provider omits them.
 */
class HermesRuntimeBridge(
    context: Context,
    secretFor: (ProviderProfile) -> String?,
) : HeadlessCliBridge(context, secretFor) {
    override val kind: AgentKind get() = AgentKind.HERMES
    override val guestExecutable: String get() = RuntimeInstaller.HERMES_GUEST_PATH

    override fun commandFor(
        prompt: String,
        provider: ProviderProfile,
        secret: String?,
        guestWorkspacePath: String,
    ): List<String> = buildList {
        add(guestExecutable)
        add("chat")
        add("--quiet")
        add("--format")
        add("stream-json")
        val providerName = hermesProviderName(provider)
        if (providerName.isNotBlank()) {
            add("--provider")
            add(providerName)
        }
        val model = provider.model.ifBlank { provider.kind.defaultModel }
        if (model.isNotBlank()) {
            add("--model")
            add(model)
        }
        add("-q")
        add(prompt)
    }

    override fun environmentFor(provider: ProviderProfile, secret: String?): Map<String, String> {
        val environment = linkedMapOf<String, String>("HOME" to "/root")
        val kind = provider.kind
        if (kind == ProviderKind.FREE || secret.isNullOrBlank()) return environment
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
            ProviderKind.OPENCODE_ZEN -> {
                environment["OPENAI_API_KEY"] = secret
                environment["OPENAI_BASE_URL"] = provider.resolvedBaseUrl
            }
            ProviderKind.NVIDIA_NIM -> {
                environment["OPENAI_API_KEY"] = secret
                environment["OPENAI_BASE_URL"] = provider.resolvedBaseUrl
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
        HermesJsonlParser.parseLine(line, sessionId)

    private fun hermesProviderName(provider: ProviderProfile): String {
        if (provider.kind == ProviderKind.FREE) return ""
        return when (provider.kind) {
            ProviderKind.DEEPSEEK -> "deepseek"
            ProviderKind.ANTHROPIC, ProviderKind.KIMI -> "anthropic"
            ProviderKind.LLM_ROUTER -> "openrouter"
            ProviderKind.OPENCODE_ZEN, ProviderKind.NVIDIA_NIM -> "openai"
            ProviderKind.CUSTOM -> {
                val api = provider.dshApi.ifBlank { "anthropic-messages" }
                if (api == "openai-completions" || api == "openai-responses") "openai" else "anthropic"
            }
            ProviderKind.CLAUDE -> ""
            ProviderKind.FREE -> ""
        }
    }
}