package com.jarves.mh.storage

import com.jarves.mh.storage.DeviceFsRouter.Reach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFsRouterTest {
    @Test
    fun theSandboxIsAlwaysServedDirectly() {
        assertEquals(Reach.SANDBOX, DeviceFsRouter.reachFor("sandbox/workspaces", hasSaf = false, allFiles = false, shizuku = false))
        assertEquals(Reach.SANDBOX, DeviceFsRouter.reachFor("sandbox", hasSaf = true, allFiles = true, shizuku = true))
    }

    @Test
    fun sharedStoragePrefersTheCheapBackendOverTheShell() {
        // Even with Shizuku available, going through a command pipe for /sdcard would be
        // slower for no gain.
        assertEquals(Reach.ALL_FILES, DeviceFsRouter.reachFor("sdcard/Download", hasSaf = true, allFiles = false, shizuku = true))
        assertEquals(Reach.ALL_FILES, DeviceFsRouter.reachFor("storage/emulated/0/DCIM", hasSaf = false, allFiles = true, shizuku = true))
    }

    @Test
    fun systemPathsFallThroughToTheShell() {
        assertEquals(Reach.SHELL, DeviceFsRouter.reachFor("data/local/tmp", hasSaf = true, allFiles = true, shizuku = true))
        assertEquals(Reach.SHELL, DeviceFsRouter.reachFor("system/build.prop", hasSaf = false, allFiles = true, shizuku = true))
    }

    @Test
    fun withoutAnyPermissionNothingIsReachable() {
        assertNull(DeviceFsRouter.reachFor("sdcard/Download", hasSaf = false, allFiles = false, shizuku = false))
        assertNull(DeviceFsRouter.reachFor("data/local/tmp", hasSaf = false, allFiles = false, shizuku = false))
    }

    @Test
    fun shizukuAloneCoversEverythingIncludingTheSandboxPath() {
        assertEquals(Reach.SHELL, DeviceFsRouter.reachFor("data/local/tmp", hasSaf = false, allFiles = false, shizuku = true))
    }

    @Test
    fun leadingAndTrailingSlashesDoNotChangeTheAnswer() {
        assertEquals(Reach.SHELL, DeviceFsRouter.reachFor("/data/local/tmp/", hasSaf = false, allFiles = false, shizuku = true))
        assertTrue(DeviceFsRouter.isSharedStorage("/sdcard/"))
    }

    @Test
    fun sharedStorageDetectionDoesNotMatchLookalikes() {
        assertFalse(DeviceFsRouter.isSharedStorage("data/media/0"))
        assertFalse(DeviceFsRouter.isSharedStorage(""))
    }

    @Test
    fun theErrorSaysHowToGetAccess() {
        val needsShizuku = DeviceFsRouter.whyUnavailable("data/local/tmp", shizuku = false)
        assertEquals(FsErrorKind.NO_ACCESS, needsShizuku.kind)
        assertEquals(FsRemedy.REQUEST_SHIZUKU, needsShizuku.remedy)

        val needsAFolder = DeviceFsRouter.whyUnavailable("sdcard/Download", shizuku = false)
        assertEquals(FsRemedy.PICK_FOLDER, needsAFolder.remedy)
    }
}
