package com.jarves.mh.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legacy overlay bundles were built against Ubuntu 20.04 and carry a full
 * glibc 2.31 multilib plus Python 3.8. Extracted over the 26.04 Core they
 * silently regress libc 2.43 back to 2.31 (loader included) and re-point
 * `/usr/bin/python3` at 3.8, which is what made later tool installs break.
 * An overlay must be additive only; these tests pin the paths it may never
 * replace and the ones it may still add freely.
 */
class OverlayCorePathGuardTest {

    @Test
    fun `glibc loader and libc may never be replaced by an overlay`() {
        assertTrue(isCoreSystemPathOverlayGuarded("usr/lib/aarch64-linux-gnu/libc.so.6"))
        assertTrue(isCoreSystemPathOverlayGuarded("usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"))
        assertTrue(isCoreSystemPathOverlayGuarded("usr/lib/aarch64-linux-gnu/libm.so.6"))
    }

    @Test
    fun `the whole base multilib directory is guarded`() {
        assertTrue(isCoreSystemPathOverlayGuarded("usr/lib/aarch64-linux-gnu"))
        assertTrue(isCoreSystemPathOverlayGuarded("usr/lib/aarch64-linux-gnu/libstdc++.so.6"))
        assertTrue(isCoreSystemPathOverlayGuarded("usr/lib/gcc/aarch64-linux-gnu/15/libgcc.a"))
    }

    @Test
    fun `the default python interpreter is guarded`() {
        assertTrue(isCoreSystemPathOverlayGuarded("usr/bin/python3"))
        assertTrue(isCoreSystemPathOverlayGuarded("usr/bin/python3.8"))
        assertTrue(isCoreSystemPathOverlayGuarded("usr/bin/python3-config"))
        assertTrue(isCoreSystemPathOverlayGuarded("usr/bin/pip3"))
    }

    @Test
    fun `overlay-owned add-on paths stay writable`() {
        assertFalse(isCoreSystemPathOverlayGuarded("usr/local/bin/claude"))
        assertFalse(isCoreSystemPathOverlayGuarded("usr/local/lib/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js"))
        assertFalse(isCoreSystemPathOverlayGuarded("root/.local/bin/agy"))
        assertFalse(isCoreSystemPathOverlayGuarded("opt/gradle/gradle-8.14.3/bin/gradle"))
        assertFalse(isCoreSystemPathOverlayGuarded("root/android-sdk/platforms/android-36/android.jar"))
        assertFalse(isCoreSystemPathOverlayGuarded("usr/lib/python3.8/zipfile.py"))
    }
}