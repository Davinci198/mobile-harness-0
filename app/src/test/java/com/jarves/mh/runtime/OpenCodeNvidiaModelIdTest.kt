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
    fun `preserves other vendor prefixes for full body id`() {
        assertEquals(
            "nvidia/meta/muse-glimmer-30b",
            nvidiaApiModelId("meta/muse-glimmer-30b"),
        )
    }

    @Test
    fun `cli model flag is provider namespace plus full body id`() {
        // --model = provider/modelKey; models map key = full body id (nvidia/…).
        val modelId = nvidiaApiModelId("nemotron-3-super-120b-a12b")
        assertEquals("nvidia/nvidia/nemotron-3-super-120b-a12b", "nvidia/$modelId")
    }
}
