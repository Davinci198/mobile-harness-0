package com.jarves.mh.storage

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileSystemTest {
    @get:Rule
    val temp = TemporaryFolder()

    // mkdirs instead of newFolder: several tests lay files down before asking for the
    // filesystem, and TemporaryFolder.newFolder throws when the name already exists.
    private fun system(write: Boolean = true): LocalFileSystem {
        val root = File(temp.root, "root").apply { mkdirs() }
        return LocalFileSystem(DeviceRoot.Local("test"), root, writeAllowed = write)
    }

    @Test
    fun listsDirectoriesBeforeFilesThenAlphabetically() = runBlocking {
        val fs = system()
        File(temp.root, "root/zeta.txt").writeText("z")
        File(temp.root, "root/alpha.txt").writeText("a")
        File(temp.root, "root/Beta").mkdirs()
        val names = fs.list("").valueOrNull()!!.map { it.name }
        assertEquals(listOf("Beta", "alpha.txt", "zeta.txt"), names)
    }

    @Test
    fun relativePathsAreRootedAtTheBase() = runBlocking {
        File(temp.root, "root/docs/notes.md").apply { parentFile.mkdirs(); writeText("# hi") }
        val entry = system().stat("docs/notes.md").valueOrNull()!!
        assertEquals("notes.md", entry.name)
        assertEquals("docs/notes.md", entry.relativePath)
        assertEquals(4L, entry.sizeBytes)
    }

    @Test
    fun traversalIsRefused() = runBlocking {
        val outside = temp.newFile("secret.txt").apply { writeText("nope") }
        val fs = system()
        assertTrue(fs.readText("../secret.txt").errorOrNull()!!.message.contains("escapes"))
        // Sanity: the file really is outside the root, so the guard is what refused it.
        assertTrue(!File(temp.root, "root").canonicalFile.toPath().startsWith(outside.canonicalFile.toPath()))
    }

    @Test
    fun absolutePathsAreRefused() = runBlocking {
        assertTrue(system().readText("/etc/passwd").errorOrNull()!!.message.contains("escapes"))
    }

    @Test
    fun missingPathsSaySo() = runBlocking {
        assertEquals(FsErrorKind.NOT_FOUND, system().readText("nope.txt").errorOrNull()!!.kind)
    }

    @Test
    fun writeCreatesMissingParents() = runBlocking {
        val fs = system()
        assertTrue(fs.writeText("a/b/c.txt", "hello").isOk)
        assertEquals("hello", File(temp.root, "root/a/b/c.txt").readText())
        fs.writeText("a/b/c.txt", "!", append = true)
        assertEquals("hello!", File(temp.root, "root/a/b/c.txt").readText())
    }

    @Test
    fun aReadOnlyLocationRefusesWrites() = runBlocking {
        val fs = system(write = false)
        assertEquals(FsErrorKind.READ_ONLY, fs.writeText("x.txt", "a").errorOrNull()!!.kind)
        assertEquals(FsErrorKind.READ_ONLY, fs.delete("x.txt").errorOrNull()!!.kind)
    }

    @Test
    fun renameRefusesCollisionsAndSlashesInTheNewName() = runBlocking {
        val fs = system()
        File(temp.root, "root/one.txt").writeText("1")
        File(temp.root, "root/two.txt").writeText("2")
        assertTrue(fs.rename("one.txt", "three.txt").isOk)
        assertEquals("1", File(temp.root, "root/three.txt").readText())
        assertTrue(fs.rename("three.txt", "two.txt").errorOrNull()!!.message.contains("already exists"))
        assertTrue(fs.rename("three.txt", "a/b").errorOrNull()!!.message.contains("Invalid name"))
    }

    @Test
    fun deleteRefusesNonEmptyDirectoriesWithoutRecursion() = runBlocking {
        File(temp.root, "root/dir/inner.txt").apply { parentFile.mkdirs(); writeText("x") }
        val fs = system()
        assertEquals(FsErrorKind.FAILED, fs.delete("dir").errorOrNull()!!.kind)
        assertTrue(fs.delete("dir", recursive = true).isOk)
        assertTrue(!File(temp.root, "root/dir").exists())
    }

    @Test
    fun theRootItselfCannotBeDeletedOrRenamed() = runBlocking {
        val fs = system()
        assertTrue(fs.delete("").errorOrNull()!!.message.contains("root"))
        assertTrue(fs.rename("", "elsewhere").errorOrNull()!!.message.contains("root"))
    }

    @Test
    fun tooLargeFilesAreRefusedInsteadOfLoaded() = runBlocking {
        val fs = system()
        val big = File(temp.root, "root/big.txt")
        big.writeBytes(ByteArray((FsLimits.MAX_TEXT_READ_BYTES + 1).toInt()))
        assertTrue(fs.readText("big.txt").errorOrNull()!!.message.contains("too large"))
    }

    @Test
    fun pathHelpersBehave() {
        assertEquals("a/b", FsPaths.join("a", "b"))
        assertEquals("b", FsPaths.join("", "b"))
        assertEquals("a", FsPaths.join("a/", "/b/").substringBefore('/'))
        assertEquals("", FsPaths.parentOf("a"))
        assertEquals("a/b", FsPaths.parentOf("a/b/c"))
        assertEquals(null, FsPaths.parentOf(""))
        assertEquals("c", FsPaths.nameOf("a/b/c"))
        assertTrue(FsPaths.isSafeRelative("a/b"))
        assertFalse(FsPaths.isSafeRelative("../a"))
        assertFalse(FsPaths.isSafeRelative("/a"))
    }
}
