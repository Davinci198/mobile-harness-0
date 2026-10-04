package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.isLoopbackBaseUrl

/**
 * Headless Hermes bridge (Nous Research): `hermes chat --quiet
 * --format stream-json [--provider X] [--model Y] -q "<prompt>"` prints
 * newline-delimited events. Model routing uses Hermes' own provider names and
 * the matching API-key environment variables; the Free provider routes to the
 * keyless `nous` provider and omits every key variable.
 */
internal class HermesRuntimeBridge(
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
        gatewayUrl: String?,
    ): List<String> = buildList {
        add(guestExecutable)
        add("chat")
        add("--quiet")
        add("--format")
        add("stream-json")
        addAll(hermesProviderOptions(provider))
        add("-q")
        add(prompt)
    }

    override fun environmentFor(
        provider: ProviderProfile,
        secret: String?,
        gatewayUrl: String?,
    ): Map<String, String> = hermesEnvironmentFor(provider, secret, gatewayUrl)

    override fun parseJsonlLine(line: String, sessionId: String): CliParsed =
        HermesJsonlParser.parseLine(line, sessionId)

    // Hermes keeps one interactive `hermes chat` alive between turns, which
    // turns a 22-26s boot per turn into one boot per project/endpoint. The
    // session is started *without* `-q`: on a TTY `-q` is the legacy
    // single-query mode, and Hermes finalises the session and exits right after
    // the first answer ("Shutting down... (finalizing session)").
    override fun supportsWarmSession(): Boolean = true

    override fun warmCommandFor(
        prompt: String,
        provider: ProviderProfile,
        secret: String?,
        guestWorkspacePath: String,
        gatewayUrl: String?,
    ): List<String> = buildList {
        add(guestExecutable)
        add("chat")
        addAll(hermesProviderOptions(provider))
    }
}

/**
 * Hermes' own provider name for a profile. The Free provider (displayed as
 * "Hermes") routes to the keyless Nous inference endpoint; anonymous callers
 * carry no API-key environment variable — only the free-tier gate below.
 */
internal fun hermesProviderName(provider: ProviderProfile): String = when (provider.kind) {
    ProviderKind.FREE -> "nous"
    ProviderKind.DEEPSEEK -> "deepseek"
    ProviderKind.ANTHROPIC, ProviderKind.KIMI -> "anthropic"
    ProviderKind.LLM_ROUTER -> "openrouter"
    ProviderKind.OPENCODE_ZEN, ProviderKind.NVIDIA_NIM -> "openai"
    ProviderKind.CUSTOM -> {
        val api = provider.dshApi.ifBlank { "anthropic-messages" }
        if (api == "openai-completions" || api == "openai-responses") "openai" else "anthropic"
    }
    ProviderKind.CLAUDE -> ""
}

/** `--provider`/`--model` flag pair for `hermes chat`, shared by cold and warm starts. */
internal fun hermesProviderOptions(provider: ProviderProfile): List<String> = buildList {
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
}

/**
 * Process environment for a Hermes Agent turn. The Free provider carries no
 * key: it opens the free-tier gate so the guest mints its anonymous Nous
 * identity (`hermes_cli.guest_enabled()` reads `HERMES_GUEST_ONBOARDING`)
 * instead of falling back to provider config already on disk.
 */
internal fun hermesEnvironmentFor(
    provider: ProviderProfile,
    secret: String?,
    gatewayUrl: String?,
): Map<String, String> {
    val environment = linkedMapOf<String, String>("HOME" to "/root")
    val kind = provider.kind
    if (kind == ProviderKind.FREE) {
        environment["HERMES_GUEST_ONBOARDING"] = "1"
        return environment
    }
    val rawKey = secret.orEmpty()
    // A loopback gateway on this device runs keyless: keep the guest pointed
    // at it instead of falling back to the provider's public endpoint.
    if (rawKey.isBlank() && !isLoopbackBaseUrl(provider.resolvedBaseUrl)) return environment
    // Guest CLIs refuse to boot with an empty key variable, while a loopback
    // gateway ignores the header — send a placeholder instead of nothing.
    val key = rawKey.ifBlank { "loopback" }
    if (gatewayUrl != null && provider.routesThroughOpenAiProxy()) {
        environment["OPENAI_API_KEY"] = key
        environment["OPENAI_BASE_URL"] = gatewayUrl
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
        ProviderKind.OPENCODE_ZEN -> {
            environment["OPENAI_API_KEY"] = key
            environment["OPENAI_BASE_URL"] = provider.resolvedBaseUrl
        }
        ProviderKind.NVIDIA_NIM -> {
            environment["OPENAI_API_KEY"] = key
            environment["OPENAI_BASE_URL"] = provider.resolvedBaseUrl
        }
        ProviderKind.CUSTOM -> {
            val api = provider.dshApi.ifBlank { "anthropic-messages" }
            if (api == "openai-completions" || api == "openai-responses") {
                environment["OPENAI_API_KEY"] = key
                environment["OPENAI_BASE_URL"] = provider.resolvedBaseUrl
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
