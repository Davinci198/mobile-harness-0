package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for the reply-to-speech formatter used by the on-device TTS speaker:
 * code must never be read aloud, whitespace collapses, and very long replies
 * are capped so a monologue cannot run unbounded.
 */
class ReplySpeechTest {
    @Test
    fun stripsCodeFencesAndInlineCode() {
        val reply = "Run `npm test` first.\n```kotlin\nval x = 1\n```\nThen commit."
        val prepared = replyForSpeech(reply)
        assertFalse(prepared.contains("kotlin"))
        assertFalse(prepared.contains("val x"))
        assertFalse(prepared.contains("`"))
        assertFalse(prepared.contains("npm test"))
        assertEquals("Run first. Then commit.", prepared)
    }

    @Test
    fun collapsesWhitespace() {
        val prepared = replyForSpeech("line one\n\n\n   line two\tend")
        assertEquals("line one line two end", prepared)
    }

    @Test
    fun capsLongReplies() {
        val prepared = replyForSpeech("a".repeat(5000))
        assertEquals(1201, prepared.length)
        assertTrue(prepared.endsWith("…"))
    }

    @Test
    fun keepsShortRepliesIntact() {
        assertEquals("All checks passed.", replyForSpeech("  All checks passed. "))
    }
}
