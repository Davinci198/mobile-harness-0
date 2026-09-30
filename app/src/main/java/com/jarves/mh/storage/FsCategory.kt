package com.jarves.mh.storage

/**
 * A category shown on the root of a root, the way a file manager presents storage:
 * a tile per well-known location, with how much lives in it.
 *
 * The count is deliberately just a count. Summing sizes would mean walking every file in
 * each category, which on the Shizuku backend is a shell round trip per category and can
 * take seconds on a busy /data.
 */
enum class FsCategoryKind {
    STORAGE,
    DOWNLOADS,
    IMAGES,
    AUDIO,
    VIDEO,
    DOCUMENTS,
    APPS,
    SYSTEM,
    DATA,
    VENDOR,
    PRODUCT,
    WORKSPACES,
    CHATS,
    RUNTIME,
    SETUP,
    TERMINAL,
    OTHER,
}

data class FsCategory(
    val kind: FsCategoryKind,
    val label: String,
    /** Path relative to the root, "" for the root itself. */
    val path: String,
    /** How many entries live directly inside, shown under the name. */
    val count: Int,
    /**
     * Bytes of the files counted here, or -1 while unknown. A file manager shows "size
     * (count)" under each tile, and the bytes come from the listing already fetched for
     * the count, so they cost nothing extra. Only files sitting directly in the category
     * are counted: a recursive total would be a walk per category.
     */
    val bytes: Long = -1L,
)

/**
 * The tiles a root offers. Shared-storage categories only make sense where that storage
 * exists, and the device layout only on a root that can see the device, so each root gets
 * its own set rather than a fixed list with dead entries.
 */
object FsCategories {

    /** Storage areas shared storage is divided into, by the paths Android defines. */
    private val SHARED_STORAGE = listOf(
        FsCategoryKind.STORAGE to "",
        FsCategoryKind.DOWNLOADS to "Download",
        FsCategoryKind.IMAGES to "DCIM",
        FsCategoryKind.AUDIO to "Music",
        FsCategoryKind.VIDEO to "Movies",
        FsCategoryKind.DOCUMENTS to "Documents",
        FsCategoryKind.APPS to "Android",
    )

    /** The app's own layout, which is the proot sandbox seen from the host. */
    private val SANDBOX = listOf(
        FsCategoryKind.WORKSPACES to "workspaces",
        FsCategoryKind.CHATS to "chats",
        FsCategoryKind.RUNTIME to "runtime",
        FsCategoryKind.DOWNLOADS to "updates",
        FsCategoryKind.SETUP to "setup",
        FsCategoryKind.TERMINAL to "terminal-history",
    )

    /** The top level of the device filesystem, only reachable through a shell. */
    private val DEVICE = listOf(
        FsCategoryKind.SYSTEM to "system",
        FsCategoryKind.DATA to "data",
        FsCategoryKind.DATA to "sdcard",
        FsCategoryKind.VENDOR to "vendor",
        FsCategoryKind.PRODUCT to "product",
        FsCategoryKind.OTHER to "storage",
    )

    fun kindsFor(root: DeviceRoot): List<FsCategoryKind> = when (root) {
        is DeviceRoot.Local -> SANDBOX.map { it.first }
        is DeviceRoot.AllFiles -> SHARED_STORAGE.map { it.first }
        is DeviceRoot.SafTree -> SHARED_STORAGE.map { it.first }
        is DeviceRoot.Shizuku -> (DEVICE + SHARED_STORAGE.drop(1)).map { it.first }
    }

    /** The definition list for a root, in the order the tiles should appear. */
    fun definitionsFor(root: DeviceRoot): List<Pair<FsCategoryKind, String>> {
        val base = when (root) {
            is DeviceRoot.Local -> SANDBOX
            is DeviceRoot.AllFiles -> SHARED_STORAGE
            // A granted folder is one place, not a storage layout: list what is inside it.
            is DeviceRoot.SafTree -> listOf(FsCategoryKind.OTHER to "")
            // The device root starts at /, where DCIM, Music and the rest do not exist:
            // they live under the shared storage mount. Without the prefix every one of
            // them is read as missing and its tile is dropped.
            is DeviceRoot.Shizuku -> DEVICE + SHARED_STORAGE.drop(1).map { (kind, path) ->
                kind to "sdcard/$path"
            }
        }
        // A kind may repeat with a different path (/sdcard and /storage are both real), so
        // the pairs are what identify a tile.
        return base
    }
}
