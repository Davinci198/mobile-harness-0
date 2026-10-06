package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `--link2symlink` turns every link(2) into a symlink to a sibling `.l2s.*` file, so
 * dpkg's `status-old` backup and the coreutils/perl shims in `usr/bin` become links
 * that break the moment the temporary is gone. The next apt run then fails with
 * `error creating new backup file '/var/lib/dpkg/status-old': Permission denied`,
 * leaving the package database half-installed so PHP, Java and Hermes cannot install.
 */
class Link2SymlinkRepairTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val rootfs: File by lazy { temporaryFolder.newFolder("ubuntu") }

    private fun file(path: String, content: String): File =
        File(rootfs, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    @Test
    fun `a shim symlink to an l2s temporary becomes a real copy`() {
        val target = file("usr/bin/.l2s.ls.4242", "real coreutils bytes")
        val shim = File(rootfs, "usr/bin/ls")
        java.nio.file.Files.createSymbolicLink(shim.toPath(), target.toPath())

        repairLink2symlinkArtifacts(rootfs)

        assertFalse("the shim must not stay a symlink", java.nio.file.Files.isSymbolicLink(shim.toPath()))
        assertEquals("real coreutils bytes", shim.readText())
        assertTrue("the copy has to stay executable", shim.canExecute())
    }

    @Test
    fun `l2s temporaries are removed once the shims are materialised`() {
        val target = file("usr/bin/.l2s.ls.4242", "bytes")
        val shim = File(rootfs, "usr/bin/ls")
        java.nio.file.Files.createSymbolicLink(shim.toPath(), target.toPath())

        repairLink2symlinkArtifacts(rootfs)

        assertTrue("the materialised shim keeps the bytes", shim.isFile)
        assertTrue(shim.readText() == "bytes")
        assertFalse("the temporary itself must not ship", target.exists())
    }

    @Test
    fun `dpkg status-old and status-new are removed`() {
        val statusOld = file("var/lib/dpkg/status-old", "stale backup")
        val statusNew = file("var/lib/dpkg/status-new", "partial write")

        repairLink2symlinkArtifacts(rootfs)

        assertFalse(statusOld.exists())
        assertFalse(statusNew.exists())
    }

    @Test
    fun `a real dpkg status file survives`() {
        val status = file("var/lib/dpkg/status", "Package: bash\nStatus: install ok installed\n")

        repairLink2symlinkArtifacts(rootfs)

        assertTrue("the package database must not be touched", status.isFile)
        assertTrue(status.readText().contains("Status: install ok installed"))
    }

    @Test
    fun `an in-rootfs symlink is left alone`() {
        val target = file("usr/bin/busybox", "busybox bytes")
        val link = File(rootfs, "usr/bin/vi")
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())

        repairLink2symlinkArtifacts(rootfs)

        assertTrue("in-rootfs symlinks are valid and must be kept", java.nio.file.Files.isSymbolicLink(link.toPath()))
        assertTrue("and the target must stay", target.isFile)
    }

    @Test
    fun `a symlink to a missing l2s temporary does not throw`() {
        val shim = File(rootfs, "usr/bin/ls")
        shim.parentFile?.mkdirs()
        java.nio.file.Files.createSymbolicLink(shim.toPath(), File(rootfs, "usr/bin/.l2s.gone.1").toPath())

        repairLink2symlinkArtifacts(rootfs)

        assertFalse("nothing to copy from, so nothing to materialise", shim.exists())
    }

    @Test
    fun `a missing rootfs is a no-op`() {
        repairLink2symlinkArtifacts(File(temporaryFolder.root, "absent"))
    }
}
