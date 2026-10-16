package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * OpenCode rides the same OmniRush loopback proxy DeepSeek Harness does, so it
 * needs the same two guarantees: the rotating access token must neither retire
 * the warm proxy nor change the warm session's identity. Without both, every
 * rotation makes the next turn pay the 22-26s cold TUI boot instead of model
 * latency.
 */
class OpenCodeWarmSignatureTest {
    private val omnirush = ProviderProfile(ProviderKind.OMNIRUSH)
    private val gateway = "http://127.0.0.1:55999/omnirush/v1"

    /**
     * The environment OpenCode bakes into the guest for OmniRush: the key the
     * bridge resolves once feeds both entries, so testing it here pins the only
     * two places a rotating token could still leak into the signature.
     */
    private fun environment(secret: String): Map<String, String> {
        val key = openCodeGuestKey(omnirush, secret)
        return linkedMapOf(
            "OMNIRUSH_ACCESS_TOKEN" to key,
            "OPENCODE_CONFIG_CONTENT" to OmniRush.configContent(gateway, key),
        )
    }

    private fun signature(
        secret: String,
        guestWorkspacePath: String = "/workspace/demo",
        environment: Map<String, String> = environment(secret),
    ): String = headlessWarmSignature(omnirush, gateway, guestWorkspacePath, environment)

    @Test
    fun omnirushGuestKeyIsConstantSoARotationKeepsTheSessionWarm() {
        assertEquals(
            openCodeGuestKey(omnirush, "access-v1"),
            openCodeGuestKey(omnirush, "access-v2"),
        )
        assertEquals(LOOPBACK_GUEST_KEY, openCodeGuestKey(omnirush, ""))
        // Baking the raw token in (the old behaviour) boots fresh every rotation.
        assertNotEquals(
            signature("access-v1", environment = mapOf("OMNIRUSH_ACCESS_TOKEN" to "access-v1")),
            signature("access-v2", environment = mapOf("OMNIRUSH_ACCESS_TOKEN" to "access-v2")),
        )
    }

    @Test
    fun aRotatedOmniRushTokenDoesNotChangeTheWarmSignature() {
        assertEquals(signature("access-v1"), signature("access-v2"))
    }

    @Test
    fun aDifferentProjectOrGatewayStillBootsFresh() {
        assertNotEquals(
            signature("access-v1"),
            signature("access-v1", guestWorkspacePath = "/workspace/other"),
        )
        assertNotEquals(
            signature("access-v1"),
            headlessWarmSignature(
                omnirush,
                "http://127.0.0.1:55998/omnirush/v1",
                "/workspace/demo",
                environment("access-v1"),
            ),
        )
    }

    @Test
    fun otherProvidersStillReceiveTheirOwnKey() {
        val nvidia = ProviderProfile(ProviderKind.NVIDIA_NIM)
        assertEquals("nv-key", openCodeGuestKey(nvidia, "nv-key"))
        // Only the loopback-per-route provider is allowed to drop its credential.
        assertNotEquals(openCodeGuestKey(nvidia, "nv-key"), openCodeGuestKey(nvidia, "nv-key-rotated"))
    }

    @Test
    fun theWarmProxyIdentityIgnoresTheRotatingOmniRushPair() {
        assertEquals(
            headlessProxyIdentity(omnirush, "access-v1", "cli/1.2.3"),
            headlessProxyIdentity(omnirush, "access-v2", "cli/1.2.4"),
        )
        // A different endpoint or model still means a different proxy socket…
        assertNotEquals(
            headlessProxyIdentity(omnirush, "access-v1", "cli/1.2.3"),
            headlessProxyIdentity(omnirush.copy(model = "other"), "access-v1", "cli/1.2.3"),
        )
        // …and a provider whose key is captured at construction keeps carrying it.
        val nvidia = ProviderProfile(ProviderKind.NVIDIA_NIM)
        assertNotEquals(
            headlessProxyIdentity(nvidia, "nv-key", ""),
            headlessProxyIdentity(nvidia, "nv-key-rotated", ""),
        )
    }
}
