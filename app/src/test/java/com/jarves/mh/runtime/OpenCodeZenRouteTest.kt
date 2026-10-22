package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenCode Zen serves its model list from the provider itself, and the guest
 * runs with the models.dev catalog fetch disabled (it hangs when the guest
 * DNS dies). Without a pinned route the turn failed with
 * "Model unavailable: opencode/<model>" — no catalog, no route.
 */
class OpenCodeZenRouteTest {

    private val zen = ProviderProfile(
        kind = ProviderKind.OPENCODE_ZEN,
        model = "beam-free",
    )

    @Test
    fun zenPinsTheProviderAndTheSelectedModel() {
        val environment = openCodeEnvironmentFor(zen, secret = null, gatewayUrl = null)

        val config = environment["OPENCODE_CONFIG_CONTENT"]
        assertTrue(config != null)
        assertTrue(config!!.contains(""""provider":{"opencode":{"""))
        assertTrue(config.contains(""""baseURL":"https://opencode.ai/zen/v1""""))
        assertTrue(config.contains(""""models":{"beam-free":{}}"""))
    }

    @Test
    fun zenStaysKeylessAndSendsNoAuthorizationHeader() {
        val environment = openCodeEnvironmentFor(zen, secret = null, gatewayUrl = null)

        // Zen's free tier is keyless: a placeholder key would be sent as an
        // Authorization header the gateway does not want.
        assertFalse(environment.containsKey("OPENCODE_API_KEY"))
        assertFalse(environment["OPENCODE_CONFIG_CONTENT"].orEmpty().contains("apiKey"))
    }

    @Test
    fun zenWithASavedKeyPublishesIt() {
        val environment = openCodeEnvironmentFor(zen, secret = "zen-key", gatewayUrl = null)

        assertEquals("zen-key", environment["OPENCODE_API_KEY"])
        assertTrue(
            environment["OPENCODE_CONFIG_CONTENT"].orEmpty()
                .contains(""""apiKey":"{env:OPENCODE_API_KEY}""""),
        )
    }

    @Test
    fun theModelFlagMatchesThePinnedModelKey() {
        assertEquals("opencode/beam-free", opencodeModelArg(zen, gatewayUrl = null, model = "beam-free"))
    }
}
