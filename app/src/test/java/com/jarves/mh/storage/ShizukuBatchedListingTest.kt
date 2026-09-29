package com.jarves.mh.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The batched listing is what keeps a root with a dozen categories down to one command,
 * so the parser has to survive the awkward cases: a folder that is not there, a name that
 * looks like a marker, and blocks that never arrived.
 */
class ShizukuBatchedListingTest {

    /** One `find -printf` line: type, octal permissions, size, mtime with fraction, name. */
    private fun dirLine(name: String) = "d|755|4096|1700000000.250000000|$name"
    private fun fileLine(name: String, size: Long = 10L) = "f|644|$size|1700000000.250000000|$name"

    @Test
    fun everyRequestedPathGetsABlock() {
        val output = """
            #MH0
            .
            ${dirLine("Download")}
            #MH1
            .
            ${fileLine("notes.md")}
            #MH2
            .
        """.trimIndent()

        val blocks = ShizukuFs.parseBatched(output, expected = 3)

        assertEquals(3, blocks.size)
        assertEquals("Download", blocks[0].entries.single().name)
        assertEquals("notes.md", blocks[1].entries.single().name)
        assertTrue("an empty directory is not missing", !blocks[2].missing)
        assertTrue(blocks[2].entries.isEmpty())
    }

    @Test
    fun aDirectoryThatIsNotThereIsMarkedMissing() {
        val output = """
            #MH0
            .
            ${dirLine("data")}
            #MH1
            #MHX
        """.trimIndent()

        val blocks = ShizukuFs.parseBatched(output, expected = 2)

        assertTrue("the tile should be dropped, not shown as empty", blocks[1].missing)
        assertTrue(!blocks[0].missing)
    }

    @Test
    fun aBlockThatNeverArrivedIsMissingRatherThanEmpty() {
        // A later command overran the output cap, so the tail of the batch is absent.
        val blocks = ShizukuFs.parseBatched("#MH0\n.\n${dirLine("system")}", expected = 5)

        assertEquals(5, blocks.size)
        assertTrue(!blocks[0].missing)
        assertTrue(blocks[1].missing)
        assertTrue(blocks[4].missing)
    }

    @Test
    fun blocksStayInTheOrderThePathsWereAskedFor() {
        val output = "#MH1\n.\n${fileLine("second")}\n#MH0\n.\n${fileLine("first")}"
        val blocks = ShizukuFs.parseBatched(output, expected = 2)

        assertEquals("first", blocks[0].entries.single().name)
        assertEquals("second", blocks[1].entries.single().name)
    }

    @Test
    fun aFileNamedLikeAMarkerIsStillAnEntry() {
        val output = "#MH0\n.\n${fileLine("#MH1")}"
        val blocks = ShizukuFs.parseBatched(output, expected = 1)

        // A stat line starts with the file mode in hex, so a file called "#MH1" cannot
        // be mistaken for the marker of a second block.
        assertEquals(1, blocks.size)
        assertEquals("#MH1", blocks[0].entries.single().name)
    }

    @Test
    fun theCommandQuotesEveryPathAndKeepsSpacesIntact() {
        val command = ShizukuFs.listManyCommand(listOf("/storage/emulated/0/My Files", "/data/data"))

        assertTrue("a space in a path would split into two arguments", command.contains("'/storage/emulated/0/My Files'"))
        assertTrue(command.contains("'/data/data'"))
        assertTrue("paths must not go through a variable", !command.contains("for d in \$"))
    }

    @Test
    fun theStartPointIsNotAnEntry() {
        // find reports the directory it was pointed at; that is the directory we already
        // know about, so listing it as a child of itself would show it twice.
        val blocks = ShizukuFs.parseBatched("#MH0\n.\n${dirLine("DCIM")}", expected = 1)

        assertEquals(listOf("DCIM"), blocks[0].entries.map { it.name })
    }

    @Test
    fun aFractionalTimestampBecomesWholeSeconds() {
        val entry = ShizukuFs.parseFindLine("f|644|12|1790713248.413296802|notes.md")

        assertEquals(1790713248L, entry?.lastModifiedMillis)
    }

    @Test
    fun permissionsDecideWhetherAnEntryReadsAsLocked() {
        assertEquals(false, ShizukuFs.parseFindLine("f|000|12|1.0|secret.txt")?.readable)
        assertEquals(true, ShizukuFs.parseFindLine("f|644|12|1.0|open.txt")?.readable)
        assertEquals(true, ShizukuFs.parseFindLine("f|640|12|1.0|group.txt")?.readable)
    }

    @Test
    fun aDirectoryCarriesNoSizeOfItsOwn() {
        // "4096" is the block size of the directory itself, not what it holds.
        assertEquals(0L, ShizukuFs.parseFindLine("d|755|4096|1.0|Pictures")?.sizeBytes)
    }

    @Test
    fun theCommandAsksFindRatherThanForkingAStatPerEntry() {
        // A stat per entry is what made a root take twelve seconds; find does the same
        // work inside one process.
        val command = ShizukuFs.listManyCommand(listOf("/data", "/system"))

        assertTrue(command.contains("find . -maxdepth 1 -printf"))
        assertTrue("no per-entry stat", !command.contains("stat -c"))
    }

    @Test
    fun noPathsMeansNothingToRun() {
        assertEquals("true", ShizukuFs.listManyCommand(emptyList()))
    }
}
