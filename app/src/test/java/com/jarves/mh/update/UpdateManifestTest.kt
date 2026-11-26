package com.jarves.mh.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * Locks the in-app update channel contract. The shipped build pointed at a
 * manifest URL that returns 404 and the parser answered "up to date" for every
 * failure, so these cases are exactly the ones that were silently broken.
 */
class UpdateManifestTest {

    private fun manifest(
        versionCode: Int = 13,
        versionName: String = "1.0.13",
        onlineSha: String = "a".repeat(64),
        offlineSha: String = "b".repeat(64),
    ) = JSONObject().apply {
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("notes", "Fixes the updater")
        put("artifacts", JSONObject().apply {
            put("online", JSONObject().apply {
                put("url", "https://github.com/o/r/releases/download/update-channel/mobile-harness-online-v1.0.13.apk")
                put("sha256", onlineSha)
                put("sizeBytes", 2_048L)
            })
            put("offline", JSONObject().apply {
                put("url", "https://github.com/o/r/releases/download/update-channel/mobile-harness-offline-v1.0.13.apk")
                put("sha256", offlineSha)
                put("sizeBytes", 4_096L)
            })
        })
    }.toString()

    @Test
    fun `newer manifest offers the matching variant`() {
        val result = UpdateManifest.parse(manifest(), variant = "offline", currentVersionCode = 12)
        assertTrue(result is AppUpdateCheck.Available)
        val info = (result as AppUpdateCheck.Available).info
        assertEquals(13L, info.versionCode)
        assertEquals("1.0.13", info.versionName)
        assertEquals("b".repeat(64), info.sha256)
        assertEquals(4_096L, info.sizeBytes)
        assertTrue(info.apkUrl.contains("-offline-"))
    }

    @Test
    fun `same version is up to date, not an error`() {
        val result = UpdateManifest.parse(manifest(versionCode = 12), "online", currentVersionCode = 12)
        assertEquals(AppUpdateCheck.UpToDate, result)
    }

    @Test
    fun `an older manifest is up to date`() {
        val result = UpdateManifest.parse(manifest(versionCode = 9), "online", currentVersionCode = 12)
        assertEquals(AppUpdateCheck.UpToDate, result)
    }

    @Test
    fun `a manifest without a versionCode fails instead of looking current`() {
        val body = JSONObject().put("versionName", "1.0.13").toString()
        val result = UpdateManifest.parse(body, "online", currentVersionCode = 12)
        assertTrue(result is AppUpdateCheck.Failed)
    }

    @Test
    fun `invalid json is reported as a failure`() {
        val result = UpdateManifest.parse("Not Found", "online", currentVersionCode = 12)
        assertTrue(result is AppUpdateCheck.Failed)
    }

    @Test
    fun `an artifact without a checksum is rejected`() {
        val body = manifest(onlineSha = "").let { json ->
            JSONObject(json).apply {
                getJSONObject("artifacts").getJSONObject("online").remove("sha256")
            }.toString()
        }
        val result = UpdateManifest.parse(body, "online", currentVersionCode = 12)
        assertTrue(result is AppUpdateCheck.Failed)
    }

    @Test
    fun `a plaintext apk url is rejected`() {
        val body = JSONObject(manifest()).apply {
            getJSONObject("artifacts").getJSONObject("online")
                .put("url", "http://example.com/mobile-harness.apk")
        }.toString()
        val result = UpdateManifest.parse(body, "online", currentVersionCode = 12)
        assertTrue(result is AppUpdateCheck.Failed)
    }

    @Test
    fun `a variant missing from a multi-variant manifest does not fall back to the other apk`() {
        // "online" exists, "offline" does not: the offline build must not be
        // offered the online APK (mismatched runtime bundles).
        val body = JSONObject(manifest()).apply {
            getJSONObject("artifacts").remove("offline")
        }.toString()
        val result = UpdateManifest.parse(body, "offline", currentVersionCode = 12)
        assertTrue(result is AppUpdateCheck.Failed)
    }
}
