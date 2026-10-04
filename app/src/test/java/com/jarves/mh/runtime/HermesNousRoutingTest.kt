package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesNousRoutingTest {
    @Test
    fun freeRoutesToTheNousProviderWithTheFreeDefaultModel() {
        assertEquals("nous", hermesProviderName(ProviderProfile(kind = ProviderKind.FREE)))
        assertEquals(
            listOf("--provider", "nous", "--model", "stepfun/step-3.7-flash:free"),
            hermesProviderOptions(ProviderProfile(kind = ProviderKind.FREE)),
        )
    }

    @Test
    fun freeModelSelectionOverridesTheKeylessDefault() {
        assertEquals(
            listOf("--provider", "nous", "--model", "poolside/laguna-s-2.1:free"),
            hermesProviderOptions(
                ProviderProfile(kind = ProviderKind.FREE, model = "poolside/laguna-s-2.1:free"),
            ),
        )
    }

    @Test
    fun keyedProvidersKeepTheirOwnRouting() {
        assertEquals("deepseek", hermesProviderName(ProviderProfile(kind = ProviderKind.DEEPSEEK)))
        assertEquals(
            listOf("--provider", "deepseek", "--model", "deepseek-v4-flash"),
            hermesProviderOptions(ProviderProfile(kind = ProviderKind.DEEPSEEK)),
        )
        assertEquals(
            listOf("--provider", "openrouter", "--model", "~anthropic/claude-sonnet-latest"),
            hermesProviderOptions(ProviderProfile(kind = ProviderKind.LLM_ROUTER)),
        )
        assertEquals(
            listOf("--provider", "anthropic", "--model", "claude-sonnet-4-6"),
            hermesProviderOptions(ProviderProfile(kind = ProviderKind.ANTHROPIC)),
        )
    }

    @Test
    fun claudeProfileSendsNoProviderFlag() {
        assertEquals("", hermesProviderName(ProviderProfile(kind = ProviderKind.CLAUDE)))
        assertTrue("--provider" !in hermesProviderOptions(ProviderProfile(kind = ProviderKind.CLAUDE)))
    }
}
