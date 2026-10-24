package com.jarves.mh.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelResponseParserTest {
    @Test
    fun parsesOpenAiStyleDataList() {
        val models = ModelResponseParser.parse(
            """{"data":[{"id":"model-b"},{"id":"model-a","display_name":"Model A"}]}""",
        )

        assertEquals(listOf("model-a", "model-b"), models.map { it.id })
        assertEquals("Model A", models.first().displayName)
    }

    @Test
    fun parsesModelsAndStringArrays() {
        assertEquals(
            listOf("alpha"),
            ModelResponseParser.parse("""{"models":[{"name":"alpha"}]}""").map { it.id },
        )
        assertEquals(
            listOf("alpha", "beta"),
            ModelResponseParser.parse("""["beta","alpha"]""").map { it.id },
        )
    }

    @Test
    fun malformedResponseReturnsEmptyList() {
        assertEquals(emptyList<DiscoveredModel>(), ModelResponseParser.parse("not json"))
    }

    @Test
    fun explicitIsFreeFlagWinsOverThePricingHeuristic() {
        // Kilo's paid music rows carry all-zero pricing on chat fields; only
        // the explicit isFree flag keeps them out of a keyless pool listing.
        val models = ModelResponseParser.parse(
            """{"data":[
                {"id":"kilo-auto/free","isFree":true,
                 "pricing":{"prompt":"0","completion":"0","request":"0"}},
                {"id":"google/lyria-3-pro-preview","isFree":false,
                 "pricing":{"prompt":"0","completion":"0","request":"0"}},
                {"id":"legacy/style","pricing":{"prompt":"0","completion":"0","request":"0"}},
                {"id":"paid/vendor","isFree":false,
                 "pricing":{"prompt":"0.000004","completion":"0.00002","request":"0"}}]}""",
        )

        assertTrue(models.first { it.id == "kilo-auto/free" }.isFree)
        assertFalse(models.first { it.id == "google/lyria-3-pro-preview" }.isFree)
        assertTrue(models.first { it.id == "legacy/style" }.isFree)
        assertFalse(models.first { it.id == "paid/vendor" }.isFree)
    }
}
