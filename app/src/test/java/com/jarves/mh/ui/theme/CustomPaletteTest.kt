package com.jarves.mh.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPaletteTest {
    private fun assertContrast(min: Float, a: Color, b: Color, message: String) {
        val ratio = contrastRatio(a, b)
        assertTrue("$message: contrast was $ratio", ratio >= min)
    }

    @Test
    fun hsvRoundTripKeepsColor() {
        val source = Color(0xFF43A047)
        val hsv = source.toHsv()
        val back = hsvToColor(hsv.h, hsv.s, hsv.v)
        assertEquals(source.red, back.red, 0.01f)
        assertEquals(source.green, back.green, 0.01f)
        assertEquals(source.blue, back.blue, 0.01f)
    }

    @Test
    fun hsvToColorCoversEveryHueSector() {
        for (hue in 0 until 360 step 15) {
            val color = hsvToColor(hue.toFloat(), 0.8f, 0.9f)
            assertTrue("hue $hue out of range", color.red in 0f..1f && color.green in 0f..1f && color.blue in 0f..1f)
        }
    }

    @Test
    fun rotateHueWrapsAroundTheCircle() {
        val source = Color(0xFF7C5CFC)
        val back = source.rotateHue(360f)
        assertEquals(source.red, back.red, 0.01f)
        assertEquals(source.blue, back.blue, 0.01f)
    }

    @Test
    fun parseHexColorAcceptsAndRejects() {
        assertEquals(0xFF43A047.toInt(), parseHexColor("43A047"))
        assertEquals(0xFF43A047.toInt(), parseHexColor("#43a047"))
        assertEquals(null, parseHexColor("43A04"))
        assertEquals(null, parseHexColor("ZZZZZZ"))
    }

    @Test
    fun darkSchemeKeepsTextReadableForAnyAccent() {
        val accents = listOf(
            Color(0xFFFFE066), Color(0xFF00E5FF), Color(0xFFFF1744),
            Color(0xFF7C5CFC), Color(0xFF2E7D32), Color(0xFFFAFAFA),
        )
        for (accent in accents) {
            val palette = customPalette(accent, Color(0xFF0F0F10))
            val label = "#${accent.toHexString()}"
            assertContrast(4.5f, palette.darkColors.primary, palette.darkColors.onPrimary, "$label onPrimary")
            assertContrast(4.5f, palette.darkColors.background, palette.darkColors.onBackground, "$label onBackground")
            assertContrast(4.5f, palette.darkColors.surface, palette.darkColors.onSurface, "$label onSurface")
            assertContrast(3.0f, palette.darkColors.surfaceVariant, palette.darkColors.onSurfaceVariant, "$label onSurfaceVariant")
        }
    }

    @Test
    fun lightSchemeKeepsTextReadableForAnyAccent() {
        val accents = listOf(
            Color(0xFFFFE066), Color(0xFF00E5FF), Color(0xFFFF1744),
            Color(0xFF7C5CFC), Color(0xFF2E7D32), Color(0xFFFAFAFA),
        )
        for (accent in accents) {
            val palette = customPalette(accent, Color(0xFF0F0F10))
            val label = "#${accent.toHexString()}"
            assertContrast(4.5f, palette.lightColors.primary, palette.lightColors.onPrimary, "$label light onPrimary")
            assertContrast(4.5f, palette.lightColors.background, palette.lightColors.onBackground, "$label light onBackground")
            assertContrast(3.0f, palette.lightColors.surfaceVariant, palette.lightColors.onSurfaceVariant, "$label light onSurfaceVariant")
        }
    }

    @Test
    fun surfacesGetLighterAsTheyMoveAwayFromBackground() {
        val palette = customPalette(Color(0xFF43A047), Color(0xFF0A140E))
        assertTrue(palette.background.relativeLuminance() < palette.surface.relativeLuminance())
        assertTrue(palette.surface.relativeLuminance() < palette.surfaceVariant.relativeLuminance())
        assertTrue(palette.surfaceVariant.relativeLuminance() < palette.muted.relativeLuminance())
    }

    @Test
    fun semanticGreenSurvivesACustomTheme() {
        assertEquals(Color(0xFF69D69E), customPalette(Color(0xFFFF1744), Color(0xFF0F0F10)).green)
    }
}
