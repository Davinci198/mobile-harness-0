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
    fun statCommandQuotesThePath() {
        assertEquals("stat -c '%f|%s|%Y|%n' '/data/plain dir' 2>/dev/null || true", ShizukuFs.statCommand("/data/plain dir"))
    }

    @Test
    fun mkdirCommandQuotesThePath() {
        assertEquals("mkdir -p -- '/data/plain dir' 2>/dev/null", ShizukuFs.createDirectoryCommand("/data/plain dir"))
    }

    @Test
    fun readCommandQuotesThePath() {
        assertTrue(ShizukuFs.readTextCommand("/data/plain dir", 10).contains("'/data/plain dir'"))
    }

    @Test
    fun deleteCommandQuotesThePath() {
        assertTrue(ShizukuFs.deleteCommand("/data/plain dir", recursive = true).contains("'/data/plain dir'"))
    }

    @Test
    fun quotedPathWithAnEmbeddedQuoteStaysOneArgument() {
        val command = ShizukuFs.createDirectoryCommand("/data/a b'c")
        assertTrue(command, command.contains("'/data/a b'\\''c'"))
    }

    @Test
    fun renameQuotesTheSourceAndTheTarget() {
        val command = ShizukuFs.renameCommand("/data/local/tmp/old name", "new name")
        assertTrue(command, command.startsWith("mv -- '/data/local/tmp/old name' "))
        assertTrue(command, command.endsWith("'/data/local/tmp/new name' 2>/dev/null"))
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
