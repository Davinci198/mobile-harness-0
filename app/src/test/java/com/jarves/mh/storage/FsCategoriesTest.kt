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
        assertEquals("data", paths.first())
        assertTrue(paths.contains("storage"))
        assertTrue(paths.contains("sdcard/Download"))
    }

    @Test
    fun sharedStorageIsReachedThroughTheMountNotTheFilesystemRoot() {
        // The device root starts at /, where there is no DCIM: shared storage hangs off
        // /sdcard. Written as bare "DCIM" every one of these tiles is read as missing and
        // dropped from the grid, which is how six of the twelve tiles vanished.
        val paths = FsCategories.definitionsFor(DeviceRoot.Shizuku("t", "/")).map { it.second }
        assertEquals(
            listOf("sdcard/Download", "sdcard/DCIM", "sdcard/Music", "sdcard/Movies", "sdcard/Documents", "sdcard/Android"),
            paths.filter { it.startsWith("sdcard/") },
        )
    }

    @Test
    fun theWriteOncePartitionsAreNotOffered() {
        // A write to /system, /vendor or /product does not come back, so those tiles are
        // not shown at all. /data and /storage stay: they hold the app data and the mount.
        val paths = FsCategories.definitionsFor(DeviceRoot.Shizuku("t", "/")).map { it.second }
        assertEquals(setOf("system", "vendor", "product"), FsCategories.HIDDEN_DEVICE_PATHS)
        assertTrue(paths.none { it in FsCategories.HIDDEN_DEVICE_PATHS })
        assertTrue(paths.containsAll(listOf("data", "storage", "sdcard")))
    }

    @Test
    fun twoPathsCanShareAKind() {
        // /data and /sdcard are both DATA to a person looking at the device root.
        val kinds = FsCategories.definitionsFor(DeviceRoot.Shizuku("t", "/")).map { it.first }
        assertEquals(2, kinds.count { it == FsCategoryKind.DATA })
    }
}
