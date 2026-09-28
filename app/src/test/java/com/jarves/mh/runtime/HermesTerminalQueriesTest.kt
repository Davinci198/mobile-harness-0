package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HermesTerminalQueriesTest {
    @Test
    fun backgroundColourQueryIsAnswered() {
        val match = terminalReplyFor("\u001B]11;?\u001B\\")
        assertNotNull(match)
        assertEquals("\u001B]11;rgb:0000/0000/0000\u001B\\", match!!.second)
    }

    @Test
    fun primaryDeviceAttributesQueryIsAnswered() {
        val match = terminalReplyFor("\u001B[c")
        assertNotNull(match)
        assertEquals("\u001B[?1;2c", match!!.second)
    }

    @Test
    fun plainOutputNeedsNoReply() {
        assertNull(terminalReplyFor("\u276F Ask anything, or type / for commands..."))
    }
}
