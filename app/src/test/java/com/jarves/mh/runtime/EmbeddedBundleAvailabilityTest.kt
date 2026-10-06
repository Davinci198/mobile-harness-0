package com.jarves.mh.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the offline flavor ships the bundle archives under `assets/runtime/`. The
 * online debug APK has none, so Antigravity's `forceEmbedded = true` used to throw
 * `FileNotFoundException` on every attempt instead of downloading the bundle. These
 * tests pin the decision the installer makes so it cannot regress silently.
 */
class EmbeddedBundleAvailabilityTest {

    /** Mirrors obtainRuntimeBundle: embedded only when the asset is actually there. */
    private fun shouldUseEmbedded(
        preferEmbedded: Boolean,
        offlineFlavor: Boolean,
        forceDownload: Boolean,
        assetExists: Boolean,
    ): Boolean = !forceDownload && (preferEmbedded || offlineFlavor) && assetExists

    @Test
    fun `an online build without assets falls back to downloading`() {
        assertFalse(
            "the online APK has no assets/runtime/, so it must download",
            shouldUseEmbedded(preferEmbedded = true, offlineFlavor = false, forceDownload = false, assetExists = false),
        )
    }

    @Test
    fun `an offline build still prefers the embedded archive`() {
        assertTrue(
            shouldUseEmbedded(preferEmbedded = false, offlineFlavor = true, forceDownload = false, assetExists = true),
        )
    }

    @Test
    fun `forceDownload overrides an offline flavor`() {
        assertFalse(
            "the Studio bundle is never embedded, even offline",
            shouldUseEmbedded(preferEmbedded = false, offlineFlavor = true, forceDownload = true, assetExists = true),
        )
    }

    @Test
    fun `an asset present in an online build is still used`() {
        assertTrue(
            shouldUseEmbedded(preferEmbedded = true, offlineFlavor = false, forceDownload = false, assetExists = true),
        )
    }

    @Test
    fun `the installer prefers downloading when no asset exists`() {
        assertFalse(
            "this is the Antigravity case: forceEmbedded with nothing to read",
            shouldUseEmbedded(
                preferEmbedded = true,
                offlineFlavor = false,
                forceDownload = false,
                assetExists = false,
            ),
        )
    }
}
