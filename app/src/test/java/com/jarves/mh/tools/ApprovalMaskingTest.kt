package com.jarves.mh.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalMaskingTest {

    @Test
    fun masksApiKeyAssignment() {
        val out = ApprovalMasking.mask("call with api_key=nvapi-abc123def456ghi")
        assertFalse(out.contains("nvapi-abc123def456ghi"))
        assertTrue(out.contains("api_key=•••"))
    }

    @Test
    fun masksKnownKeyPrefixesAnywhere() {
        val out = ApprovalMasking.mask("curl -H 'Authorization: Bearer nvapi-XyZ1234567890AbCd' https://x")
        assertFalse(out.contains("nvapi-XyZ1234567890AbCd"))
    }

    @Test
    fun masksTokenAssignmentsCaseInsensitive() {
        val out = ApprovalMasking.mask("TOKEN=abcdefghijklmnopqrstuvwx and password: hunter22")
        assertFalse(out.contains("abcdefghijklmnopqrstuvwx"))
        assertTrue(out.contains("•••"))
    }

    @Test
    fun keepsOrdinaryTextIntact() {
        val text = "Read the file src/main/AndroidManifest.xml then run ./gradlew compile"
        assertEquals(text, ApprovalMasking.mask(text))
    }

    @Test
    fun masksLongOpaqueBlobs() {
        val blob = "dGhpcyBpcyBhIGxvbmcgYmFzZTY0IGJsb2IgdGhhdCBzaG91bGQgYmUgbWFza2Vk"
        val out = ApprovalMasking.mask("payload $blob done")
        assertFalse(out.contains(blob))
        assertTrue(out.contains("done"))
    }

    @Test
    fun maskAllMapsEveryItem() {
        val masked = ApprovalMasking.maskAll(
            listOf("clean path", "api_key=secretvalue12345"),
        )
        assertEquals("clean path", masked[0])
        assertFalse(masked[1].contains("secretvalue12345"))
    }
}
