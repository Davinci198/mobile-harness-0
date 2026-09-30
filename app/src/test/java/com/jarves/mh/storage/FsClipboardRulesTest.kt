package com.jarves.mh.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FsClipboardRulesTest {

    // Everything is root-relative, which is how the view model keeps it: the clipboard
    // outlives the folder it was filled from, so it never holds an absolute path.
    private val root = ""

    @Test
    fun puttingStripsDuplicatesAndAnythingEscapingTheRoot() {
        val board = FsClipboardRules.put(listOf("a.txt", "a.txt", "../etc/passwd", "/etc/shadow"), FsClipboardOperation.COPY)

        assertEquals(listOf("a.txt"), board.items)
    }

    @Test
    fun aPasteWritesTheNamesIntoTheTargetFolder() {
        val board = FsClipboardRules.put(listOf("Download/a.txt", "Download/notes.md"), FsClipboardOperation.COPY)

        val pasted = FsClipboardRules.pasteInto(board, "DCIM", root)

        assertEquals(listOf("DCIM/a.txt", "DCIM/notes.md"), pasted)
    }

    @Test
    fun aFolderCannotBePastedInsideItself() {
        val board = FsClipboardRules.put(listOf("Download"), FsClipboardOperation.COPY)

        assertNull("Download into Download is a no-op at best", FsClipboardRules.pasteInto(board, "Download", root))
        assertNull("and worse one level down", FsClipboardRules.pasteInto(board, "Download/fotos", root))
    }

    @Test
    fun aSiblingWhoseNameSharesAPrefixIsStillAllowed() {
        // "Downloads" is not inside "Download"; only a real path segment counts.
        val board = FsClipboardRules.put(listOf("Download"), FsClipboardOperation.COPY)

        assertEquals(listOf("Downloads/Download"), FsClipboardRules.pasteInto(board, "Downloads", root))
    }

    @Test
    fun anEmptyClipboardPastesNothing() {
        assertNull(FsClipboardRules.pasteInto(FsClipboard(), "DCIM", root))
    }

    @Test
    fun theStatusSaysHowManyAndWhichOperation() {
        val copy = FsClipboardRules.put(listOf("a", "b"), FsClipboardOperation.COPY)
        val cut = FsClipboardRules.put(listOf("a"), FsClipboardOperation.CUT)

        assertTrue(FsClipboardRules.status(copy, "", root).contains("Copiere"))
        assertTrue(FsClipboardRules.status(cut, "", root).contains("Mutare"))
        assertEquals("", FsClipboardRules.status(FsClipboard(), "", root))
    }
}

class FsArchiveTest {

    private val day = 1_767_225_600_000L

    @Test
    fun theArchiveIsNamedAfterTheDayItWasMade() {
        val name = FsArchive.archiveName(day, Locale.ROOT)

        assertTrue(name, name.startsWith("Arhivă "))
        assertTrue(name, name.endsWith(".zip"))
        assertTrue(name, name.contains("-"))
    }

    @Test
    fun aSecondArchiveTheSameDayGetsACounter() {
        val first = FsArchive.archiveName(day, Locale.ROOT)
        val second = FsArchive.uniqueName(setOf(first), day, Locale.ROOT)

        assertTrue(second, second.endsWith(".zip"))
        assertTrue(second, second != first)
    }

    @Test
    fun aFreeNameIsUsedAsIs() {
        val name = FsArchive.archiveName(day, Locale.ROOT)
        assertEquals(name, FsArchive.uniqueName(emptySet(), day, Locale.ROOT))
    }

    @Test
    fun aNameThatIsNotUsableIsRefused() {
        assertNull(FsArchive.validate(""))
        assertNull(FsArchive.validate("   "))
        assertNull(FsArchive.validate("a/b.zip"))
        assertNull(FsArchive.validate(".."))
        assertEquals("  buna.zip  ".trim(), FsArchive.validate("  buna.zip  "))
    }

    @Test
    fun theManifestListsEveryArchivedPath() {
        val text = FsArchive.manifest(listOf("a.txt", "b/c.txt"))

        assertTrue(text, text.contains("files: 2"))
        assertTrue(text, text.contains("a.txt"))
        assertTrue(text, text.contains("b/c.txt"))
    }
}
