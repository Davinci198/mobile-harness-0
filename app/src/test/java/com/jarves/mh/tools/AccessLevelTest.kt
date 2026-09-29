package com.jarves.mh.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessLevelTest {
    @Test
    fun fromStringReadsEveryTier() {
        assertEquals(AccessLevel.STANDARD, AccessLevel.fromString("STANDARD"))
        assertEquals(AccessLevel.ACCESSIBILITY, AccessLevel.fromString("ACCESSIBILITY"))
        assertEquals(AccessLevel.DEBUGGER, AccessLevel.fromString("DEBUGGER"))
        assertEquals(AccessLevel.ADMIN, AccessLevel.fromString("ADMIN"))
    }

    @Test
    fun theRetiredRootValueBecomesDebugger() {
        // Root was dropped as a tier: there is no superuser on the device, and Shizuku
        // is what took its place. Anything already saved must not fall back to nothing.
        assertEquals(AccessLevel.DEBUGGER, AccessLevel.fromString("ROOT"))
        assertEquals(AccessLevel.DEBUGGER, AccessLevel.fromString("SHIZUKU"))
    }

    @Test
    fun thereIsNoRootTier() {
        assertFalse(AccessLevel.entries.any { it.name == "ROOT" })
    }

    @Test
    fun fromStringIsForgivingAboutCaseAndSpacing() {
        assertEquals(AccessLevel.ADMIN, AccessLevel.fromString("admin"))
        assertEquals(AccessLevel.DEBUGGER, AccessLevel.fromString("  Debugger "))
        assertEquals(AccessLevel.ACCESSIBILITY, AccessLevel.fromString("accessibility\n"))
    }

    @Test
    fun fromStringFallsBackToStandard() {
        assertEquals(AccessLevel.STANDARD, AccessLevel.fromString(null))
        assertEquals(AccessLevel.STANDARD, AccessLevel.fromString(""))
        assertEquals(AccessLevel.STANDARD, AccessLevel.fromString("CAUTION"))
        assertEquals(AccessLevel.STANDARD, AccessLevel.fromString("superuser"))
    }

    @Test
    fun everyTierIsCoveredByTheStatusMap() {
        val statuses = accessLevelStatuses(shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true)
        assertEquals(AccessLevel.entries.toSet(), statuses.keys)
    }

    @Test
    fun standardIsAlwaysReadyAndNothingElseIsWithoutShizuku() {
        val none = accessLevelStatuses(shizukuInstalled = false, shizukuRunning = false, shizukuGranted = false)
        assertTrue(none.getValue(AccessLevel.STANDARD).available)
        for (level in AccessLevel.entries.filter { it != AccessLevel.STANDARD }) {
            assertFalse("$level must not be available without Shizuku", none.getValue(level).available)
        }
    }

    @Test
    fun debuggerFollowsTheShizukuState() {
        val notInstalled = accessLevelStatuses(false, false, false).getValue(AccessLevel.DEBUGGER)
        assertEquals(AccessLevelDetail.UNSUPPORTED, notInstalled.detail)
        assertFalse(notInstalled.available)

        val installedNotRunning = accessLevelStatuses(true, false, false).getValue(AccessLevel.DEBUGGER)
        assertEquals(AccessLevelDetail.NOT_RUNNING, installedNotRunning.detail)

        val runningNotGranted = accessLevelStatuses(true, true, false).getValue(AccessLevel.DEBUGGER)
        assertEquals(AccessLevelDetail.NEEDS_PERMISSION, runningNotGranted.detail)
        assertFalse(runningNotGranted.available)

        val granted = accessLevelStatuses(true, true, true).getValue(AccessLevel.DEBUGGER)
        assertTrue(granted.available)
        assertEquals(AccessLevelDetail.READY, granted.detail)
    }

    @Test
    fun accessibilityAndAdminHaveNoBackendYet() {
        val statuses = accessLevelStatuses(shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true)
        for (level in listOf(AccessLevel.ACCESSIBILITY, AccessLevel.ADMIN)) {
            assertFalse("$level should be unavailable", statuses.getValue(level).available)
            assertEquals(AccessLevelDetail.UNSUPPORTED, statuses.getValue(level).detail)
        }
    }
}
