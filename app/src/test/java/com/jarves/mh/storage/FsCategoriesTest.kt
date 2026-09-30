package com.jarves.mh.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsCategoriesTest {
    @Test
    fun theSandboxShowsTheAppsOwnLayout() {
        val paths = FsCategories.definitionsFor(DeviceRoot.Local("t")).map { it.second }
        assertTrue(paths.contains("workspaces"))
        assertTrue(paths.contains("runtime"))
        // Storage categories would be dead entries in the app's own files.
        assertTrue(!paths.contains("DCIM"))
    }

    @Test
    fun aGrantedFolderIsOnePlaceNotAStorageLayout() {
        val definitions = FsCategories.definitionsFor(DeviceRoot.SafTree("t", "content://x"))
        assertEquals(1, definitions.size)
        assertEquals("", definitions.single().second)
    }

    @Test
    fun sharedStorageCoversTheWellKnownAreas() {
        val paths = FsCategories.definitionsFor(DeviceRoot.AllFiles("t")).map { it.second }
        assertEquals(listOf("", "Download", "DCIM", "Music", "Movies", "Documents", "Android"), paths)
    }

    @Test
    fun theDeviceRootLeadsWithTheSystemLayout() {
        val paths = FsCategories.definitionsFor(DeviceRoot.Shizuku("t", "/")).map { it.second }
        assertEquals("system", paths.first())
        assertTrue(paths.contains("data"))
        assertTrue(paths.contains("sdcard/Download"))
    }

    @Test
    fun sharedStorageIsReachedThroughTheMountNotTheFilesystemRoot() {
        // The device root starts at /, where there is no DCIM: shared storage hangs off
        // /sdcard. Written as bare "DCIM" every one of these tiles is read as missing and
        // dropped from the grid, which is how six of the twelve tiles vanished.
        val paths = FsCategories.definitionsFor(DeviceRoot.Shizuku("t", "/")).map { it.second }
        val shared = paths.filter { it.startsWith("sdcard/") }
        assertEquals(
            listOf("sdcard/Download", "sdcard/DCIM", "sdcard/Music", "sdcard/Movies", "sdcard/Documents", "sdcard/Android"),
            shared,
        )
        // The system tiles stay where they are: /system really is /system.
        assertTrue(paths.containsAll(listOf("system", "data", "vendor", "product", "storage")))
    }

    @Test
    fun twoPathsCanShareAKind() {
        // /data and /sdcard are both DATA to a person looking at the device root.
        val kinds = FsCategories.definitionsFor(DeviceRoot.Shizuku("t", "/")).map { it.first }
        assertEquals(2, kinds.count { it == FsCategoryKind.DATA })
    }
}
