package com.jarves.mh.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app downloads its Ubuntu/agent bundles from one release host and verifies
 * every archive against the SHA-256 pinned in [RuntimeInstaller]. The published
 * manifest (dist/runtime-bundles/manifest.json) is the only description of that
 * channel, so a mismatch between the two is an install that fails on a phone
 * long after CI went green. These tests keep them identical.
 */
class RuntimeBundleManifestTest {

    private fun repoFile(relative: String): File {
        val direct = File(relative)
        if (direct.isFile) return direct
        val found = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull(File::isFile)
        return found ?: error("Could not locate $relative from ${File(".").absolutePath}")
    }

    private fun manifest(): JSONObject = JSONObject(repoFile("dist/runtime-bundles/manifest.json").readText())

    @Test
    fun `every installer bundle is published with the same name and checksum`() {
        val published = manifest().getJSONObject("bundles")
        val known = published.keys().asSequence().mapNotNull { published.optJSONObject(it) }.toList()
        val problems = RuntimeInstaller.BUNDLES_FOR_TEST.mapNotNull { bundle ->
            val entry = known.firstOrNull { it.optString("file") == bundle.fileName }
            when {
                entry == null -> "${bundle.label}: ${bundle.fileName} is missing from the published manifest"
                !entry.optString("sha256").equals(bundle.sha256, ignoreCase = true) ->
                    "${bundle.label}: checksum drift (installer ${bundle.sha256.take(12)}…, manifest ${entry.optString("sha256").take(12)}…)"
                entry.optLong("compressedBytes", bundle.compressedBytes) != bundle.compressedBytes ->
                    "${bundle.label}: compressed size drift"
                else -> null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the manifest publishes at least one source URL and a mirror list`() {
        val root = manifest()
        assertTrue(root.optString("source").startsWith("https://"))
        val mirrors = root.optJSONArray("mirrors") ?: JSONArray()
        assertTrue("mirrors must list at least the primary host", mirrors.length() >= 1)
        val primary = mirrors.getJSONObject(0).optString("url")
        // If the primary host disappears the app has no other place to look,
        // which is why the primary URL is mirrored in the manifest itself.
        assertEquals(root.optString("source"), primary)
    }

    @Test
    fun `manifest version and architecture match the installer contract`() {
        val root = manifest()
        assertEquals(1, root.optInt("schemaVersion"))
        assertEquals("arm64", root.optString("architecture"))
        assertTrue(root.optInt("minimumAndroidApi") <= 28)
    }
}
