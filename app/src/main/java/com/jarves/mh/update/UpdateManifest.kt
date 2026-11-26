package com.jarves.mh.update

import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

/**
 * Outcome of a manifest check. The updater used to collapse "no newer version"
 * and "the manifest is unreachable" into the same `null`, so a broken update
 * channel looked exactly like being up to date.
 */
sealed interface AppUpdateCheck {
    data class Available(val info: AppUpdateInfo) : AppUpdateCheck
    data object UpToDate : AppUpdateCheck
    data class Failed(val reason: String) : AppUpdateCheck
}

/**
 * Pure parser for mobile-harness-update.json. Kept free of Android types so the
 * manifest contract (variant selection, mandatory checksum, version ordering)
 * is unit-tested without a device.
 */
object UpdateManifest {
    /**
     * @param body raw manifest JSON
     * @param variant BuildConfig.APP_VARIANT ("online" or "offline")
     * @param currentVersionCode BuildConfig.VERSION_CODE of the running app
     */
    fun parse(body: String, variant: String, currentVersionCode: Long): AppUpdateCheck {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return AppUpdateCheck.Failed("Update manifest is not valid JSON")
        val versionCode = root.optLong("versionCode")
        if (versionCode <= 0L) return AppUpdateCheck.Failed("Update manifest has no versionCode")
        if (versionCode <= currentVersionCode) return AppUpdateCheck.UpToDate
        val artifact = root.optJSONObject("artifacts")?.optJSONObject(variant)
            ?: root.optJSONObject(variant)
            ?: root
        val url = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }
        if (!url.startsWith("https://")) {
            return AppUpdateCheck.Failed("Update manifest has no APK for the $variant build")
        }
        val sha256 = artifact.optString("sha256").lowercase()
        if (sha256.isBlank()) return AppUpdateCheck.Failed("Update manifest has no checksum")
        return AppUpdateCheck.Available(
            AppUpdateInfo(
                versionCode = versionCode,
                versionName = root.optString("versionName", versionCode.toString()),
                apkUrl = url,
                sha256 = sha256,
                sizeBytes = artifact.optLong("sizeBytes", -1L),
                notes = root.optString("notes"),
            ),
        )
    }
}
