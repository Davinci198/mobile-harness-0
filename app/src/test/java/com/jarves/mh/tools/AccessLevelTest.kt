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
        assertEquals(AccessLevel.ROOT, AccessLevel.fromString("ROOT"))
    }

    @Test
    fun fromStringIsForgivingAboutCaseAndSpacing() {
        assertEquals(AccessLevel.ROOT, AccessLevel.fromString("root"))
        assertEquals(AccessLevel.DEBUGGER, AccessLevel.fromString("  Debugger "))
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
    fun standardAndSandboxAreAlwaysReady() {
        val none = accessLevelStatuses(shizukuInstalled = false, shizukuRunning = false, shizukuGranted = false)
        for (level in listOf(AccessLevel.STANDARD, AccessLevel.ROOT)) {
            assertTrue("$level should be ready", none.getValue(level).available)
            assertEquals(AccessLevelDetail.READY, none.getValue(level).detail)
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
