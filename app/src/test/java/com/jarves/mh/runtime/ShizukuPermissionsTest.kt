package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPermissionsTest {
    /** Real `dumpsys package` output, including the per-user overrides. */
    private val realDumpsys = """
        Packages:
          Package [moe.shizuku.privileged.api] (a1b2c3):
            userId=10210
            requested permissions:
              android.permission.FOREGROUND_SERVICE: granted=true
              android.permission.WRITE_SECURE_SETTINGS: granted=true
              android.permission.POST_NOTIFICATIONS: granted=false
            install permissions:
              android.permission.WRITE_SECURE_SETTINGS: granted=false, userId=10
              android.permission.WRITE_SECURE_SETTINGS: granted=false, userId=11
              android.permission.RECEIVE_BOOT_COMPLETED: granted=true
    """.trimIndent()

    @Test
    fun readsGrantedAndDeniedPermissions() {
        val parsed = ShizukuPermissions.parseDumpsys(realDumpsys).associate { it.permission to it.granted }
        assertEquals(true, parsed["android.permission.FOREGROUND_SERVICE"])
        assertEquals(false, parsed["android.permission.POST_NOTIFICATIONS"])
    }

    @Test
    fun perUserOverridesDoNotOverrideThePrimaryState() {
        val parsed = ShizukuPermissions.parseDumpsys(realDumpsys).associate { it.permission to it.granted }
        assertEquals(
            "the granted=true for the primary user must survive the userId= overrides",
            true,
            parsed["android.permission.WRITE_SECURE_SETTINGS"],
        )
    }

    @Test
    fun eachPermissionAppearsOnce() {
        val parsed = ShizukuPermissions.parseDumpsys(realDumpsys)
        assertEquals(parsed.size, parsed.map { it.permission }.distinct().size)
    }

    @Test
    fun unrelatedLinesAreIgnored() {
        assertTrue(ShizukuPermissions.parseDumpsys("some noise\nno permissions here\n").isEmpty())
    }

    @Test
    fun parseSessionIdReadsTheNumericLine() {
        assertEquals(42, ShizukuPermissions.parseSessionId("Success: created install session [42]\n"))
        assertEquals(7, ShizukuPermissions.parseSessionId("\n  7  \n"))
        assertNull(ShizukuPermissions.parseSessionId("Failure [not a number]"))
        assertNull(ShizukuPermissions.parseSessionId(""))
    }

    @Test
    fun successNeedsACleanRun() {
        assertTrue(ShizukuPermissions.isSuccess(ShizukuCommandResult(0, "", "")))
        assertFalse(ShizukuPermissions.isSuccess(ShizukuCommandResult(0, "", "Operation not allowed")))
        assertFalse(ShizukuPermissions.isSuccess(ShizukuCommandResult(1, "", "")))
        assertFalse(ShizukuPermissions.isSuccess(ShizukuCommandResult(0, "", "", timedOut = true)))
    }

    @Test
    fun failureReasonPrefersTheMostSpecificMessage() {
        val timeout = ShizukuPermissions.failureReason(ShizukuCommandResult(-1, "", "", timedOut = true))
        assertTrue(timeout.contains("timed out"))

        val denied = ShizukuPermissions.failureReason(ShizukuCommandResult(1, "", "Operation not allowed: x\nmore"))
        assertEquals("Operation not allowed: x", denied)

        val unknown = ShizukuPermissions.failureReason(ShizukuCommandResult(9, "", ""))
        assertTrue(unknown.contains("9"))
    }
}
