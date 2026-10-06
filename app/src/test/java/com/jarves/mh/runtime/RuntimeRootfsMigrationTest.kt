package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Guards the Ubuntu 26.04.1 base migration.
 *
 * Pins the Core bundle identity and rootfs markers the installer compares against, and
 * checks that the Python overlay removal stays version-agnostic, because the interpreter
 * minor version changed from 3.8 to 3.14 together with the base.
 */
class RuntimeRootfsMigrationTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun coreBundleMatchesTheShipped2026101Artifact() {
        assertEquals(
            "pocketdev-core-arm64-2026.10.1.tar.zst",
            RuntimeInstaller.CORE_BUNDLE_FILE_NAME_FOR_TEST,
        )
        assertEquals(
            "62fcc178df8846448a648aff2842d48a21b299261ecc79eb6a8149f632c3cb49",
            RuntimeInstaller.CORE_BUNDLE_SHA256_FOR_TEST,
        )
        assertEquals(84_766_979L, RuntimeInstaller.CORE_BUNDLE_COMPRESSED_BYTES_FOR_TEST)
    }

    @Test
    fun rootfsAndMaintenanceMarkersArePinned() {
        assertEquals("ubuntu-26.04.1-arm64", RuntimeInstaller.ROOTFS_VERSION_FOR_TEST)
        assertEquals("ubuntu-base-26.04-base-arm64.tar.gz", RuntimeInstaller.ROOTFS_FILE_FOR_TEST)
        assertEquals(
            "b2b46a37324ea1954e93f293fe6d7c2241daf2fc298c4022e6e4caceeed74cab",
            RuntimeInstaller.ROOTFS_SHA256_FOR_TEST,
        )
        assertEquals("ubuntu-maintenance-v2", RuntimeInstaller.SYSTEM_UPGRADE_VERSION_FOR_TEST)
        assertEquals("core-bundle-2026.10.1", RuntimeInstaller.CORE_TOOLS_VERSION_FOR_TEST)
        // The previous marker is retained so an existing installation is recognised
        // as outdated instead of being treated as unknown.
        assertEquals("core-bundle-2026.09.5", RuntimeInstaller.LEGACY_CORE_TOOLS_VERSION_FOR_TEST)
    }

    @Test
    fun removesPythonStackForBothOldAndNewInterpreterVersions() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val versioned = listOf(
            "usr/bin/python3.8",
            "usr/bin/python3.14",
            "usr/local/bin/python3.14",
            "usr/lib/python3.8",
            "usr/lib/python3.14",
            "usr/local/lib/python3.14",
            "usr/lib/aarch64-linux-gnu/libpython3.14.so.1.0",
        )
        val untouched = listOf(
            "usr/bin/bash",
            "usr/bin/git",
            "usr/lib/aarch64-linux-gnu/libssl.so.3",
            "usr/local/lib/nodejs/bin/node",
        )
        (versioned + untouched).forEach { path ->
            File(rootfs, path).apply { parentFile?.mkdirs(); writeText("x") }
        }
        File(rootfs, ".pocket-python-tools-version").writeText("python-3.8")
        // The pip cache is a directory in a real rootfs, so removal has to recurse.
        File(rootfs, "root/.cache/pip/23/http-v2").apply { parentFile?.mkdirs(); writeText("x") }

        removePythonStackFrom(rootfs)

        versioned.forEach { assertFalse("$it should be removed", File(rootfs, it).exists()) }
        assertFalse(File(rootfs, ".pocket-python-tools-version").exists())
        assertFalse(File(rootfs, "root/.cache/pip").exists())
        untouched.forEach { assertTrue("$it must survive", File(rootfs, it).exists()) }
    }

    @Test
    fun removesPythonStackToleratesAMissingInterpreter() {
        val rootfs = temporaryFolder.newFolder("empty-rootfs")
        File(rootfs, "usr/bin/bash").apply { parentFile?.mkdirs(); writeText("x") }

        removePythonStackFrom(rootfs)

        assertTrue(File(rootfs, "usr/bin/bash").exists())
    }

    @Test
    fun rewriteGuestHostsAddsLoopbackAliasesWhenLocalhostMissing() {
        val hosts = temporaryFolder.newFile("hosts-empty")
        hosts.writeText("104.20.32.17 models.opencode.ai\n192.168.1.2 mybox.internal\n")

        rewriteGuestHosts(hosts, "104.20.33.18")

        val text = hosts.readText()
        assertTrue(text.contains("127.0.0.1 localhost"))
        assertTrue(text.contains("127.0.1.1 guest"))
        assertTrue(text.contains("::1 localhost ip6-localhost ip6-loopback"))
        assertTrue(text.contains("104.20.33.18 models.opencode.ai"))
        assertTrue(text.contains("192.168.1.2 mybox.internal"))
        assertFalse(text.contains("104.20.32.17 models.opencode.ai"))
    }

    @Test
    fun rewriteGuestHostsIsIdempotentWithSamePin() {
        val hosts = temporaryFolder.newFile("hosts-idempotent")
        hosts.writeText("127.0.0.1 localhost\n127.0.1.1 guest\n::1 localhost ip6-localhost ip6-loopback\n1.2.3.4 models.opencode.ai\n")

        rewriteGuestHosts(hosts, "1.2.3.4")
        val once = hosts.readText()
        rewriteGuestHosts(hosts, "1.2.3.4")
        val twice = hosts.readText()

        assertEquals(once, twice)
        assertTrue(twice.lines().filter { it.contains("localhost") }.size == 3)
    }

    @Test
    fun rewriteGuestHostsWithoutPinKeepsExistingLocalhostOnly() {
        val hosts = temporaryFolder.newFile("hosts-keep")
        hosts.writeText("127.0.0.1 localhost\n127.0.1.1 guest\nsome.host extra\n")

        rewriteGuestHosts(hosts, null)

        val text = hosts.readText()
        assertTrue(text.contains("127.0.0.1 localhost"))
        assertTrue(text.contains("127.0.1.1 guest"))
        assertFalse(text.contains("1.2.3.4 models.opencode.ai"))
    }
}
