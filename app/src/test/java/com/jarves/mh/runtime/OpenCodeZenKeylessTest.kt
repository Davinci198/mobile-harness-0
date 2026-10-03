package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.network.ProviderApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeZenKeylessTest {
    private val zen = ProviderProfile(ProviderKind.OPENCODE_ZEN)

    @Test
    fun zenModelFlagUsesTheOpencodeCatalogNamespace() {
        assertEquals("opencode", opencodeModelPrefix(ProviderKind.OPENCODE_ZEN))
        assertEquals(
            "opencode/deepseek-v4-flash",
            opencodeModelArg(zen, gatewayUrl = null, model = "deepseek-v4-flash"),
        )
        // Already prefixed ids are never doubled.
        assertEquals(
            "opencode/big-pickle",
            opencodeModelArg(zen, gatewayUrl = null, model = "opencode/big-pickle"),
        )
    }

    @Test
    fun zenAndFreeRunWithoutASavedKey() {
        assertFalse(requiresSavedSecret(zen))
        assertFalse(requiresSavedSecret(ProviderProfile(ProviderKind.FREE)))
        assertTrue(requiresSavedSecret(ProviderProfile(ProviderKind.ANTHROPIC)))
        assertTrue(
            requiresSavedSecret(
                ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example/v1"),
            ),
        )
        assertFalse(
            requiresSavedSecret(
                ProviderProfile(ProviderKind.CUSTOM, baseUrl = "http://127.0.0.1:37821/v1"),
            ),
        )
    }

    @Test
    fun zenBaseUrlIsRecognizedWithOrWithoutTrailingSlash() {
        val client = ProviderApiClient()
        assertTrue(client.isOpenCodeZen("https://opencode.ai/zen/v1"))
        assertTrue(client.isOpenCodeZen("https://opencode.ai/zen/v1/"))
        assertTrue(client.isOpenCodeZen("https://opencode.ai/zen/v1/responses"))
        assertFalse(client.isOpenCodeZen("https://api.apmix.ai/v1"))
        assertFalse(client.isOpenCodeZen("https://opencode.example/zen/v1"))
    }
}
