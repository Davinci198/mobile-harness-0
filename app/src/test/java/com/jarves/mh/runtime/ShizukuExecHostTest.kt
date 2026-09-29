package com.jarves.mh.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuExecHostTest {
    private val script = ShizukuExecHost.guestScript()

    @Test
    fun theScriptTalksToTheEndpoint() {
        assertTrue(script.startsWith("#!/bin/sh"))
        assertTrue(script.contains("127.0.0.1"))
        assertTrue(script.contains("/exec"))
        assertTrue(script.contains("Content-Type: application/json"))
    }

    @Test
    fun thePortComesFromTheFileNotFromTheScript() {
        // The listener binds an ephemeral port, so a hardcoded one would break on the
        // very next app start.
        assertTrue(script.contains("/root/.mh-shizuku-port"))
        assertTrue(script.contains("PORT=\$(cat"))
    }

    @Test
    fun aMissingBridgeExplainsItself() {
        // Without a port file the script has to say what to do, not just fail to connect.
        assertTrue(script.contains("not enabled"))
        assertTrue(script.contains("Access level"))
    }

    @Test
    fun theCommandIsJsonEscapedNotConcatenated() {
        // A naive "command":"$*" would break on quotes and let the payload be forged.
        assertTrue(script.contains("json.dumps"))
    }
}
