package com.jarves.mh.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision table for arming the Termux-style keep-alive foreground service. */
class SessionKeepAliveTest {
    @Test
    fun `runs while the always-on setting is enabled`() {
        assertTrue(shouldKeepAlive(enabled = true, hasHolds = false))
        assertTrue(shouldKeepAlive(enabled = true, hasHolds = true))
    }

    @Test
    fun `runs with the setting off only while a hold exists`() {
        assertTrue(shouldKeepAlive(enabled = false, hasHolds = true))
        assertFalse(shouldKeepAlive(enabled = false, hasHolds = false))
    }
}
