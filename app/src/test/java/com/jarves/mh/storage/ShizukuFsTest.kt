package com.jarves.mh.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuFsTest {
    @Test
    fun quotingSurvivesAnEmbeddedQuote() {
        // The classic breakout: a file name that closes the quote and appends a command.
        assertEquals("'a'\\''b'", ShizukuFs.quote("a'b"))
        assertEquals("'/data/local/tmp'", ShizukuFs.quote("/data/local/tmp"))
        assertEquals("'/x/; rm -rf /'", ShizukuFs.quote("/x/; rm -rf /"))
        assertEquals("'/x/`id`'", ShizukuFs.quote("/x/`id`"))
        assertEquals("'/x/\$(id)'", ShizukuFs.quote("/x/\$(id)"))
    }

    @Test
    fun everyCommandWrapsThePathInQuotes() {
        // Asserted on the argument rather than the whole line: a full-string comparison
        // over a doubly escaped quote is easy to get wrong in the test itself, and a
        // mismatch there would say nothing about the code.
        val path = "/data/plain dir"
        assertTrue(ShizukuFs.statCommand(path).contains(" '$path' 2>/dev/null"))
        assertTrue(ShizukuFs.createDirectoryCommand(path).contains(" '$path' 2>/dev/null"))
        assertTrue(ShizukuFs.readTextCommand(path, 10).contains(" '$path' 2>/dev/null"))
        assertTrue(ShizukuFs.deleteCommand(path, recursive = true).contains(" '$path' "))
    }

    @Test
    fun aQuotedPathSurvivesAsASingleArgument() {
        val command = ShizukuFs.createDirectoryCommand("/data/a b'c")
        // The embedded quote is closed, escaped and reopened, so the whole name stays one
        // shell word and nothing after it is treated as syntax.
        assertTrue(command, command.contains("'/data/a b'\\''c'"))
    }

    @Test
    fun renameQuotesTheTargetExactlyOnce() {
        // The target must be built by splicing the name onto the unquoted parent. Quoting
        // the parent first would hand the shell three arguments where mv expects two.
        val command = ShizukuFs.renameCommand("/data/local/tmp/old name", "new name")
        assertTrue(command, command.startsWith("mv -- '/data/local/tmp/old name' "))
        assertTrue(command, command.endsWith("'/data/local/tmp/new name' 2>/dev/null"))

        val topLevel = ShizukuFs.renameCommand("old.txt", "new.txt")
        assertEquals("mv -- 'old.txt' 'new.txt' 2>/dev/null", topLevel)
    }

    @Test
    fun theListingIncludesDotfiles() {
        val command = ShizukuFs.listCommand("/data/local/tmp")
        // Without the .[!.]* pass, dotfiles are invisible and the tree looks broken.
        assertTrue(command.contains("*"))
        assertTrue(command.contains(".[!.]*"))
    }

    @Test
    fun parseStatLineReadsAFile() {
        val entry = ShizukuFs.parseStatLine("81a4|1234|1790684993|notes.txt")
        assertEquals("notes.txt", entry!!.name)
        assertFalse(entry.isDirectory)
        assertEquals(1234L, entry.sizeBytes)
        assertEquals(1790684993000L, entry.lastModifiedMillis)
        assertTrue(entry.readable)
    }

    @Test
    fun parseStatLineReadsADirectory() {
        val entry = ShizukuFs.parseStatLine("41f9|3452|1790684993|bin.mt.plus")!!
        assertTrue(entry.isDirectory)
        assertEquals(0L, entry.sizeBytes)
    }

    @Test
    fun aPipeInTheFileNameDoesNotBreakParsing() {
        // The name is the last field, so only the first three delimiters split.
        val entry = ShizukuFs.parseStatLine("81a4|10|100|weird|name.txt")!!
        assertEquals("weird|name.txt", entry.name)
    }

    @Test
    fun unreadableEntriesAreMarkedButStillListed() {
        // %f is a hex st_mode: (type << 12) | permissions. 0x81a4 is -rw-r--r--,
        // 0x4000 is d---------.
        assertTrue(ShizukuFs.parseStatLine("81a4|3|100|open.txt")!!.readable)
        val locked = ShizukuFs.parseStatLine("100000|0|100|secret")!!
        assertFalse(locked.readable)
        assertFalse(locked.isDirectory)
        // An unreadable directory is still a directory: the UI has to let you back out.
        val lockedDir = ShizukuFs.parseStatLine("4000|0|100|private")!!
        assertTrue(lockedDir.isDirectory)
        assertFalse(lockedDir.readable)
    }

    @Test
    fun junkLinesAreIgnored() {
        assertNull(ShizukuFs.parseStatLine(""))
        assertNull(ShizukuFs.parseStatLine("not a stat line"))
        assertNull(ShizukuFs.parseStatLine("81a4|abc|100|name"))
        assertNull(ShizukuFs.parseStatLine("81a4|10|100|"))
    }

    @Test
    fun listingSortsDirectoriesFirstThenByName() {
        val output = """
            81a4|3|100|zeta.txt
            41f9|0|100|Beta
            81a4|1|100|alpha.txt
            41f9|0|100|alpha
        """.trimIndent()
        val names = ShizukuFs.parseListing(output).map { it.name }
        // Directories first, then case-insensitive: "alpha" sorts before "Beta".
        assertEquals(listOf("alpha", "Beta", "alpha.txt", "zeta.txt"), names)
    }

    @Test
    fun pathJoiningKeepsOneLeadingSlash() {
        assertEquals("/data/local/tmp", ShizukuFs.joinPath("/data", "local/tmp"))
        assertEquals("/data/local", ShizukuFs.joinPath("/data/", "/local"))
        assertEquals("/etc", ShizukuFs.joinPath("", "etc"))
    }
}
