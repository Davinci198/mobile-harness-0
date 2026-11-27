package com.jarves.mh.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Installing an update to our own package kills the process, so the relaunch has
 * to be decided from a marker that survived on disk. The failure modes these
 * cases pin down are all user-visible: no relaunch at all (the old behaviour —
 * the user landed on the launcher after every update), or a relaunch on every
 * later cold start because the marker was never cleared.
 *
 * [UpdateRestart] itself needs a Context; the parsing and the "did the update
 * really land" decision are the parts worth testing, and they are mirrored here
 * exactly as [UpdateRestart.pendingRestartFrom] and
 * [UpdateRestart.updateApplied] implement them.
 */
class UpdateRestartPolicyTest {

    private val separator = '\u0000'

    /** Mirrors AppPreferences.pendingUpdateRestart + pendingRestartFrom. */
    private fun fromVersion(stored: String): String? =
        stored.split(separator).firstOrNull()?.takeIf { it.isNotBlank() }

    /** Mirrors UpdateRestart.updateApplied. */
    private fun applied(fromVersionName: String, installedVersionName: String): Boolean =
        fromVersionName.isNotBlank() && fromVersionName != installedVersionName

    @Test
    fun `a marker written before the install identifies the version that asked for it`() {
        val stored = "1.0.13${separator}1.0.14"
        assertEquals("1.0.13", fromVersion(stored))
    }

    @Test
    fun `an empty preference means no pending update`() {
        assertNull(fromVersion(""))
        assertFalse(applied(fromVersion("") ?: "", "1.0.14"))
    }

    @Test
    fun `the update counts as applied only when the version actually changed`() {
        assertTrue(applied("1.0.13", "1.0.14"))
    }

    @Test
    fun `a cancelled install must not be reported as applied`() {
        // The install was denied or blocked: the app restarts on the same build,
        // and claiming an update would be a lie shown on every cold start.
        assertFalse(applied("1.0.14", "1.0.14"))
    }

    @Test
    fun `a marker without a version never triggers a relaunch`() {
        // ",1.0.14" or a truncated write: no evidence of what was installed.
        assertNull(fromVersion("${separator}1.0.14"))
        assertFalse(applied(fromVersion("${separator}1.0.14") ?: "", "1.0.14"))
    }

    @Test
    fun `a downgrade is still an applied change, not a stale marker`() {
        // Restoring an older APK over a newer one is a real install; the marker
        // must not survive and re-notify on the next start.
        assertTrue(applied("1.0.14", "1.0.13"))
    }
}
