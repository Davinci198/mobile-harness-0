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
        // No tiles at all: a granted folder's root listing IS its content. The old single
        // path="" tile sent every tap back to the root, so its contents were unreachable.
        val definitions = FsCategories.definitionsFor(DeviceRoot.SafTree("t", "content://x"))
        assertTrue(definitions.isEmpty())
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

    @Test
    fun theMainStorageTileIsAListingNotTheTilesHomeAgain() {
        // Both views are the empty path: opening the root shows the tiles, and the
        // "Main storage" tile opens the root itself. Reading the empty path as the tiles
        // home unconditionally sent that tap straight back to the tiles, so the main
        // storage could never be browsed.
        val allFiles = DeviceRoot.AllFiles("t")
        assertTrue(FsCategories.tilesBelongAt(allFiles, "", tilesRequested = true))
        assertTrue(!FsCategories.tilesBelongAt(allFiles, "", tilesRequested = false))
        // A folder is a listing either way.
        assertTrue(!FsCategories.tilesBelongAt(allFiles, "Download", tilesRequested = true))
    }

    @Test
    fun aRootWithoutTilesNeverGetsThem() {
        // A granted folder's root is a listing, and asking for its tiles home must not
        // invent one: the back gesture falls through to leaving instead of reloading.
        val granted = DeviceRoot.SafTree("t", "content://x")
        assertTrue(!FsCategories.tilesBelongAt(granted, "", tilesRequested = true))
        assertTrue(FsCategories.tilesBelongAt(DeviceRoot.Local("t"), "", tilesRequested = true))
    }
}
