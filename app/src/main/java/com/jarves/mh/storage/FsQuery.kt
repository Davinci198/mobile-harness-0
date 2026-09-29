package com.jarves.mh.storage

/** How a directory is laid out. */
enum class FsViewMode { LIST, GRID }

enum class FsSort { NAME, DATE, SIZE }

/**
 * The rules behind the header controls, kept pure so they can be unit-tested without a
 * device: ordering, the search match, and the bounds that keep a recursive search from
 * turning into a walk of the whole device.
 */
object FsQuery {

    /** A recursive search stops here rather than crawling everything on the device. */
    const val SEARCH_MAX_DEPTH = 3
    const val SEARCH_MAX_RESULTS = 300
    const val SEARCH_MAX_DIRECTORIES = 400

    fun sort(entries: List<FsEntry>, by: FsSort, ascending: Boolean = true): List<FsEntry> {
        val key: (FsEntry) -> Comparable<*> = when (by) {
            // Name ignores case so a phone's mixed-case names do not sort A, a, B, b.
            FsSort.NAME -> { entry -> entry.name.lowercase() }
            FsSort.DATE -> { entry -> entry.lastModifiedMillis }
            FsSort.SIZE -> { entry -> entry.sizeBytes }
        }
        // Each group is sorted on its own. Sorting the combined list by name would put a
        // file called "alpha.txt" between two directories and break the grouping.
        fun order(group: List<FsEntry>): List<FsEntry> {
            val sorted = group.sortedBy(key)
            return if (ascending) sorted else sorted.reversed()
        }

        return order(entries.filter { it.isDirectory }) + order(entries.filterNot { it.isDirectory })
    }

    fun matches(name: String, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return name.lowercase().contains(needle)
    }

    /** Keeps the matches, ordered by the same rules the listing uses. */
    fun apply(entries: List<FsEntry>, query: String, by: FsSort, ascending: Boolean): List<FsEntry> =
        sort(entries.filter { matches(it.name, query) }, by, ascending)
}
