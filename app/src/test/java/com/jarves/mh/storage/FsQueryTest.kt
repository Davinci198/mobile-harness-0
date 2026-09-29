package com.jarves.mh.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FsQueryTest {
    private fun entry(
        name: String,
        dir: Boolean = false,
        size: Long = 0,
        modified: Long = 0,
    ) = FsEntry(name = name, relativePath = name, isDirectory = dir, sizeBytes = size, lastModifiedMillis = modified)

    private val listing = listOf(
        entry("beta.txt", size = 300, modified = 20),
        entry("Alpha", dir = true, modified = 10),
        entry("alpha.txt", size = 100, modified = 30),
        entry("gamma", dir = true, modified = 40),
    )

    @Test
    fun directoriesAlwaysComeFirst() {
        for (by in FsSort.entries) {
            val sorted = FsQuery.sort(listing, by)
            assertEquals("sort by $by", true, sorted.first().isDirectory)
            assertTrue(sorted.take(2).all { it.isDirectory })
        }
    }

    @Test
    fun nameSortIgnoresCase() {
        val names = FsQuery.sort(listing, FsSort.NAME).map { it.name }
        assertEquals(listOf("Alpha", "gamma", "alpha.txt", "beta.txt"), names)
    }

    @Test
    fun descendingReversesTheOrder() {
        val names = FsQuery.sort(listing, FsSort.NAME, ascending = false).map { it.name }
        assertEquals(listOf("gamma", "Alpha", "beta.txt", "alpha.txt"), names)
    }

    @Test
    fun sizeAndDateSortByTheirOwnValue() {
        assertEquals("beta.txt", FsQuery.sort(listing, FsSort.SIZE).last().name)
        assertEquals("alpha.txt", FsQuery.sort(listing, FsSort.DATE).last().name)
    }

    @Test
    fun searchIsCaseInsensitiveAndMatchesAnywhere() {
        assertTrue(FsQuery.matches("Download", "down"))
        assertTrue(FsQuery.matches("pr9-full.txt", "FULL"))
        assertFalse(FsQuery.matches("notes.md", "zip"))
    }

    @Test
    fun anEmptyQueryMatchesEverything() {
        assertTrue(FsQuery.matches("anything", "   "))
    }

    @Test
    fun applyFiltersThenSorts() {
        val result = FsQuery.apply(listing, "alpha", FsSort.NAME, ascending = true)
        assertEquals(listOf("Alpha", "alpha.txt"), result.map { it.name })
    }

    @Test
    fun applyKeepsDirectoriesAheadOfMatches() {
        val result = FsQuery.apply(listing, "a", FsSort.NAME, ascending = true)
        assertTrue("a directory matching the query belongs before a file", result.first().isDirectory)
    }

    @Test
    fun theSearchBoundsAreBounded() {
        // A recursive search on a device root would otherwise walk /data forever.
        assertTrue(FsQuery.SEARCH_MAX_DEPTH <= 4)
        assertTrue(FsQuery.SEARCH_MAX_RESULTS <= 1_000)
        assertTrue(FsQuery.SEARCH_MAX_DIRECTORIES <= 1_000)
    }
}
