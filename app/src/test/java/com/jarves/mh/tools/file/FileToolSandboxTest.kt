package com.jarves.mh.tools.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileToolSandboxTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sandbox(): FileToolSandbox = FileToolSandbox(tmp.root)

    @Test
    fun acceptsRelativePathInsideRoot() {
        val s = sandbox()
        tmp.newFile("a.txt")
        assertTrue(s.isValid("a.txt"))
        assertTrue(s.resolve("a.txt") != null)
    }

    @Test
    fun acceptsNestedPathInsideRoot() {
        val s = sandbox()
        tmp.newFolder("sub")
        assertTrue(s.isValid("sub/c.txt"))
    }

    @Test
    fun rejectsDotDotEscape() {
        val s = sandbox()
        assertFalse(s.isValid("../outside.txt"))
        assertFalse(s.isValid("sub/../../outside.txt"))
        assertFalse(s.isValid(".."))
    }

    @Test
    fun rejectsAbsolutePathOutsideRoot() {
        val s = sandbox()
        assertFalse(s.isValid("/etc/passwd"))
        assertFalse(s.isValid("/"))
    }

    @Test
    fun rejectsBlankPath() {
        val s = sandbox()
        assertFalse(s.isValid(""))
        assertFalse(s.isValid("   "))
    }

    @Test
    fun resolveReturnsCanonicalFileInsideRoot() {
        val s = sandbox()
        tmp.newFolder("inner")
        val f = s.resolve("inner/file.txt")
        assertTrue(f != null)
        assertEquals(
            java.io.File(tmp.root.canonicalFile, "inner"),
            f!!.parentFile,
        )
        assertTrue(f.path.startsWith(s.rootPath))
    }
}
