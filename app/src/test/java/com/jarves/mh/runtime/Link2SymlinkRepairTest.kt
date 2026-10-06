package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `--link2symlink` turns every link(2) into a symlink pointing at an absolute
 * `/data/.../.l2s.*` file. Left in place, dpkg's next run cannot create its
 * `status-old` backup and the package database stays half-installed, which is
 * what broke PHP, Java and Hermes after a manual `apt install` in the guest.
 */
class Link2SymlinkRepairTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun rootfs(): File = temporaryFolder.newFolder("ubuntu")

    private fun writeExecutable(path: File) {
        path.parentFile.mkdirs()
        path.writeText("#!/bin/sh\nexit 0\n")
        path.setExecutable(true, false)
    }

    @Test
    fun `a shim symlink pointing into data becomes a real copy`() {
        val rootfs = rootfs()
        val dataTarget = File(temporaryFolder.root, ".l2s.ls.4242").apply { writeText("real coreutils bytes") }
        val shim = File(rootfs, "usr/bin/ls")
        shim.parentFile.mkdirs()
        java.nio.file.Files.createSymbolicLink(shim.toPath(), dataTarget.toPath())

        repairLink2symlinkArtifacts(rootfs)

        assertFalse("shim must not stay a symlink", java.nio.file.Files.isSymbolicLink(shim.toPath()))
        assertEquals("real coreutils bytes", shim.readText())
        assertTrue("the copy has to stay executable", shim.canExecute())
    }

    @Test
    fun `l2s temporaries are removed`() {
        val rootfs = rootfs()
        val temporary = File(rootfs, "var/lib/dpkg/.l2s.status0001.0001").apply { writeText("scratch") }

        repairLink2symlinkArtifacts(rootfs)

        assertFalse(temporary.exists())
    }

    @Test
    fun `dpkg status-old and status-new are removed`() {
        val rootfs = rootfs()
        val statusOld = File(rootfs, "var/lib/dpkg/status-old").apply { writeText("stale backup") }
        val statusNew = File(rootfs, "var/lib/dpkg/status-new").apply { writeText("partial write") }

        repairLink2symlinkArtifacts(rootfs)

        assertFalse(statusOld.exists())
        assertFalse(statusNew.exists())
    }

    @Test
    fun `a real dpkg status file survives`() {
        val rootfs = rootfs()
        val status = File(rootfs, "var/lib/dpkg/status").apply { writeText("Package: bash\nStatus: install ok installed\n") }

        repairLink2symlinkArtifacts(rootfs)

        assertTrue("the package database must not be touched", status.isFile)
        assertTrue(status.readText().contains("Status: install ok installed"))
    }

    @Test
    fun `an ordinary symlink inside the rootfs is left alone`() {
        val rootfs = rootfs()
        val target = File(rootfs, "usr/bin/busybox").apply { writeExecutable(this) }
        val link = File(rootfs, "usr/bin/ls")
        link.parentFile.mkdirs()
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())

        repairLink2symlinkArtifacts(rootfs)

        assertTrue("in-rootfs symlinks are valid and must be kept", java.nio.file.Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun `a dangling shim does not throw`() {
        val rootfs = rootfs()
        val shim = File(rootfs, "usr/bin/ls")
        shim.parentFile.mkdirs()
        java.nio.file.Files.createSymbolicLink(shim.toPath(), File("/data/nonexistent/.l2s.ls.1").toPath())

        repairLink2symlinkArtifacts(rootfs)

        assertFalse("an unresolvable target leaves nothing to copy", shim.exists())
    }
}
