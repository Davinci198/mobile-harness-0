package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEndpointJsonTest {
    @Test
    fun roundTripKeepsEveryField() {
        val endpoints = listOf(
            CustomEndpoint(
                id = "id-1",
                label = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-5.2",
                dshApi = "openai-responses",
                keyName = "cline",
            ),
            CustomEndpoint(
                id = "id-2",
                label = "NIM primary",
                baseUrl = "https://integrate.api.nvidia.com/v1",
                model = "nvidia/nemotron-3-super-120b-a12b",
                dshApi = "openai-completions",
            ),
        )
        assertEquals(endpoints, decodeCustomEndpoints(encodeCustomEndpoints(endpoints)))
    }

    @Test
    fun blankAndMalformedPayloadsDecodeToEmptyList() {
        assertTrue(decodeCustomEndpoints(null).isEmpty())
        assertTrue(decodeCustomEndpoints("").isEmpty())
        assertTrue(decodeCustomEndpoints("not json").isEmpty())
        assertTrue(decodeCustomEndpoints("{}").isEmpty())
    }

    @Test
    fun entriesWithoutLabelAreSkipped() {
        val raw = """[{"label":"","baseUrl":"https://x"},{"label":"kept","baseUrl":"https://y"}]"""
        val decoded = decodeCustomEndpoints(raw)
        assertEquals(1, decoded.size)
        assertEquals("kept", decoded.single().label)
    }

    @Test
    fun missingIdIsRegenerated() {
        val decoded = decodeCustomEndpoints("""[{"label":"e","baseUrl":"https://y"}]""")
        assertTrue(decoded.single().id.isNotBlank())
    }
}
