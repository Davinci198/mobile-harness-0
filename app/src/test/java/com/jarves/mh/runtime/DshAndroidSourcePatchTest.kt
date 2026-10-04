package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fixture lines below are copied verbatim from the published
 * @deepseek-ai/dsh 0.1.2-rc.1 (bundled) and 0.2.0-rc.2 (npm update) sources.
 */
class DshAndroidSourcePatchTest {
    private val incompatible = { "DeepSeek Harness 0.2.0-rc.2 is not compatible with this PocketDev build" }

    @Test
    fun patchesBundledPersistenceSource() {
        val source = fixture(
            "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            "\t\t\tawait link(tmp, finalPath);",
        )

        val patched = patchDshHardLinkSource(
            source,
            DSH_PERSISTENCE_IMPORT_VARIANTS,
            DSH_PERSISTENCE_CALL_VARIANTS,
            incompatible,
        )

        assertTrue("import { copyFile, link, mkdir, mkdtemp," in patched)
        assertTrue("await copyFile(tmp, finalPath, 1);" in patched)
        assertNoHardLinkCalls(patched)
    }

    @Test
    fun patchesUpdatedPersistenceSource() {
        val source = fixture(
            "import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            "\t\tawait internals.fs.link(staged, currentPath);",
            "\t\t\tawait link(tmp, finalPath);",
        )

        val patched = patchDshHardLinkSource(
            source,
            DSH_PERSISTENCE_IMPORT_VARIANTS,
            DSH_PERSISTENCE_CALL_VARIANTS,
            incompatible,
        )

        assertTrue("import { copyFile, link, lstat, mkdir, mkdtemp," in patched)
        assertTrue("await copyFile(staged, currentPath, 1);" in patched)
        assertTrue("await copyFile(tmp, finalPath, 1);" in patched)
        assertNoHardLinkCalls(patched)
    }

    @Test
    fun patchesBundledFsLocalSource() {
        val source = fixture(
            "import { createReadStream } from \"node:fs\";",
            "import { chmod, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            "\t\t\tawait linkFile(tempPath, absolutePath);",
        )

        val patched = patchDshHardLinkSource(
            source,
            DSH_FS_LOCAL_IMPORT_VARIANTS,
            DSH_FS_LOCAL_CALL_VARIANTS,
            incompatible,
        )

        assertTrue("import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";" in patched)
        assertTrue("await copyFile(tempPath, absolutePath, 1);" in patched)
        assertNoHardLinkCalls(patched)
    }

    @Test
    fun patchesUpdatedFsLocalSourceWithoutDuplicateRealpath() {
        val source = fixture(
            "import { createReadStream, realpath } from \"node:fs\";",
            "import { chmod, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from \"node:fs/promises\";",
            "\t\t\tawait linkFile(tempPath, absolutePath);",
        )

        val patched = patchDshHardLinkSource(
            source,
            DSH_FS_LOCAL_IMPORT_VARIANTS,
            DSH_FS_LOCAL_CALL_VARIANTS,
            incompatible,
        )

        val promisesImport = patched.lineSequence().first { "node:fs/promises" in it }
        assertFalse("realpath" in promisesImport)
        assertTrue("import { createReadStream, realpath } from \"node:fs\";" in patched)
        assertTrue("import { chmod, copyFile, link, lstat," in patched)
        assertNoHardLinkCalls(patched)
    }

    @Test
    fun patchesBundledAttachmentSource() {
        val source = fixture(
            "import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";",
            "\t\t\tawait link(temporary, target);",
        )

        val patched = patchDshHardLinkSource(
            source,
            DSH_ATTACHMENT_IMPORT_VARIANTS,
            DSH_ATTACHMENT_CALL_VARIANTS,
            incompatible,
        )

        assertTrue("await copyFile(temporary, target, 1);" in patched)
        assertNoHardLinkCalls(patched)
    }

    @Test
    fun patchesUpdatedAttachmentSource() {
        val source = fixture(
            "import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";",
            "\t\t\tawait link(source, target);",
            "\t\t\tawait link(staged.path, target);",
        )

        val patched = patchDshHardLinkSource(
            source,
            DSH_ATTACHMENT_IMPORT_VARIANTS,
            DSH_ATTACHMENT_CALL_VARIANTS,
            incompatible,
        )

        assertTrue("await copyFile(source, target, 1);" in patched)
        assertTrue("await copyFile(staged.path, target, 1);" in patched)
        assertNoHardLinkCalls(patched)
    }

    @Test
    fun alreadyPatchedSourceIsReturnedUnchanged() {
        val source = fixture(
            "import { createReadStream, realpath } from \"node:fs\";",
            "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from \"node:fs/promises\";",
            "\t\t\tawait copyFile(tempPath, absolutePath, 1);",
        )

        val patched = patchDshHardLinkSource(
            source,
            DSH_FS_LOCAL_IMPORT_VARIANTS,
            DSH_FS_LOCAL_CALL_VARIANTS,
            incompatible,
        )

        assertEquals(source, patched)
    }

    @Test
    fun failsOnUnknownImportLayout() {
        val source = fixture(
            "import { readlink } from \"node:fs/promises\";",
            "\t\t\tawait link(tmp, finalPath);",
        )

        assertIncompatible {
            patchDshHardLinkSource(source, DSH_PERSISTENCE_IMPORT_VARIANTS, DSH_PERSISTENCE_CALL_VARIANTS, incompatible)
        }
    }

    @Test
    fun failsOnUnknownCallSite() {
        val source = fixture(
            "import { copyFile, link } from \"node:fs/promises\";",
            "\t\t\tawait link(absolute, temp);",
        )

        assertIncompatible {
            patchDshHardLinkSource(source, DSH_FS_LOCAL_IMPORT_VARIANTS, DSH_FS_LOCAL_CALL_VARIANTS, incompatible)
        }
    }

    private fun fixture(vararg lines: String): String = lines.joinToString("\n")

    private fun assertNoHardLinkCalls(source: String) {
        listOf("await link(", ".link(", "linkFile(").forEach { marker ->
            assertFalse("leftover hard-link marker: $marker", marker in source)
        }
    }

    private fun assertIncompatible(block: () -> String) {
        try {
            block()
            fail("expected an incompatible-layout failure")
        } catch (error: IllegalStateException) {
            assertEquals(incompatible(), error.message)
        }
    }
}
