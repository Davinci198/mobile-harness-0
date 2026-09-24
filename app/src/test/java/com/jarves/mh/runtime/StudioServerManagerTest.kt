package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract checks for the Ekko Studio guest integration. The critical
 * invariant: nothing in the Studio process path may contain a
 * killGuestOrphans() marker substring ("hermes", "hermes-agent", "libproot.so",
 * "/root/.opencode/bin/opencode", "/usr/local/bin/hermes", "opencode serve"),
 * otherwise headless agent sessions would kill the long-running Studio server.
 */
class StudioServerManagerTest {

    @Test
    fun `guest entry and home avoid orphan sweep markers`() {
        val markers = listOf(
            "libproot.so",
            RuntimeInstaller.OPENCODE_GUEST_PATH,
            RuntimeInstaller.HERMES_GUEST_PATH,
            "hermes-agent",
            "opencode serve",
        )
        val processPath = listOf(
            RuntimeInstaller.STUDIO_GUEST_ENTRY,
            "/usr/local/lib/studio/dist/server/index.js",
        )
        for (marker in markers) {
            for (path in processPath) {
                assertFalse(
                    "Studio process path '$path' must not contain orphan marker '$marker'",
                    marker in path,
                )
            }
        }
    }

    @Test
    fun `studio guest home is intentionally the hermes-web-ui state dir`() {
        // The state dir lives on disk (never in a cmdline), so the sweep cannot
        // see it; keeping the upstream default preserves login sessions.
        assertEquals("/root/.hermes-web-ui", RuntimeInstaller.STUDIO_GUEST_HOME)
    }

    @Test
    fun `studio port does not collide with known guest services`() {
        val usedPorts = setOf(20128, 3082, 8642, 8700, 9119)
        assertFalse(RuntimeInstaller.STUDIO_DEFAULT_PORT in usedPorts)
        assertEquals(8648, RuntimeInstaller.STUDIO_DEFAULT_PORT)
    }

    @Test
    fun `portOpen reports false without a server`() {
        // Nothing listens on 8648 inside the unit-test JVM.
        assertFalse(StudioServerManager.portOpen())
    }
}
