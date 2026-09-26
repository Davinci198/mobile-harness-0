package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeNvidiaModelIdTest {
    @Test
    fun `keeps already prefixed nvidia body id`() {
        assertEquals(
            "nvidia/nemotron-3-super-120b-a12b",
            nvidiaApiModelId("nvidia/nemotron-3-super-120b-a12b"),
        )
    }

    @Test
    fun `adds nvidia prefix to bare catalog key`() {
        assertEquals(
            "nvidia/nemotron-3-super-120b-a12b",
            nvidiaApiModelId("nemotron-3-super-120b-a12b"),
        )
    }

    @Test
    fun `keeps namespaced catalog ids verbatim`() {
        // Live-verified 2026-09-26: chat/completions answers 200 for the exact
        // /v1/models id and 404 for a synthetic nvidia/ prefix.
        assertEquals(
            "meta/muse-glimmer-30b",
            nvidiaApiModelId("meta/muse-glimmer-30b"),
        )
        assertEquals(
            "google/gemma-4-31b-it",
            nvidiaApiModelId("google/gemma-4-31b-it"),
        )
        assertEquals(
            "openai/gpt-oss-20b",
            nvidiaApiModelId("openai/gpt-oss-20b"),
        )
    }

    @Test
    fun `cli model flag is provider namespace plus full body id`() {
        // --model = provider/modelKey; models map key = full body id (nvidia/…).
        val modelId = nvidiaApiModelId("nemotron-3-super-120b-a12b")
        assertEquals("nvidia/nvidia/nemotron-3-super-120b-a12b", "nvidia/$modelId")
    }
}
