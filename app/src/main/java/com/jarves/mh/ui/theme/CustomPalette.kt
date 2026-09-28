package com.jarves.mh.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Pure color math behind the custom color theme: plain Kotlin over [Color]'s float
 * components, so every rule here is covered by plain JVM unit tests.
 */
data class Hsv(val h: Float, val s: Float, val v: Float)

fun Float.clamp01(): Float = coerceIn(0f, 1f)

fun Color.toHsv(): Hsv {
    val maxC = maxOf(red, green, blue)
    val minC = minOf(red, green, blue)
    val d = maxC - minC
    val h = when {
        d == 0f -> 0f
        maxC == red -> 60f * (((green - blue) / d) % 6f)
        maxC == green -> 60f * (((blue - red) / d) + 2f)
        else -> 60f * (((red - green) / d) + 4f)
    }
    val s = if (maxC == 0f) 0f else d / maxC
    return Hsv((h + 360f) % 360f, s, maxC)
}

fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val hue = h.mod(360f)
    val sat = s.clamp01()
    val val0 = v.clamp01()
    val c = val0 * sat
    val x = c * (1f - abs(hue / 60f % 2f - 1f))
    val m = val0 - c
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(red = r + m, green = g + m, blue = b + m, alpha = 1f)
}

fun Color.rotateHue(degrees: Float): Color {
    val hsv = toHsv()
    if (hsv.s <= 0.001f) return this
    return hsvToColor((hsv.h + degrees + 360f) % 360f, hsv.s, hsv.v)
}

fun Color.mix(other: Color, t: Float): Color {
    val u = t.clamp01()
    return Color(
        red = red + (other.red - red) * u,
        green = green + (other.green - green) * u,
        blue = blue + (other.blue - blue) * u,
        alpha = alpha + (other.alpha - alpha) * u,
    )
}

fun Color.relativeLuminance(): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

fun contrastRatio(a: Color, b: Color): Float {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    return (max(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** White or black, whichever reads better on this color. */
fun Color.bestForeground(): Color =
    if (contrastRatio(this, Color.White) >= contrastRatio(this, Color.Black)) Color.White else Color.Black

/** Darkens this color until it clears [min] contrast against [against]. */
fun Color.darkenedFor(against: Color, min: Float = 4.5f): Color {
    var candidate = this
    var guard = 0
    while (contrastRatio(candidate, against) < min && guard < 40) {
        candidate = candidate.mix(Color.Black, 0.06f)
        guard++
    }
    return candidate
}

private val SemanticGreen = Color(0xFF69D69E)

/**
 * Builds a full [PocketPalette] from the two colors the user picked, so the theme stays
 * coherent instead of turning into 22 hand-tuned hexes. The dark scheme walks the
 * background toward white (or toward black when the user picked a light one), the light
 * scheme walks it the other way, and the accent drives primary/secondary with WCAG AA
 * contrast guaranteed on the matching `on*` color.
 */
fun customPalette(accent: Color, background: Color): PocketPalette {
    val userPickedLight = background.relativeLuminance() > 0.32f
    val onDark = if (userPickedLight) Color(0xFF12100F) else Color(0xFFF2F4F3)
    val liftDirection = if (userPickedLight) Color.Black else Color.White

    fun lift(amount: Float): Color = background.mix(liftDirection, amount)
    fun surfaceOf() = lift(if (userPickedLight) 0.06f else 0.04f)
    fun variantOf() = lift(if (userPickedLight) 0.10f else 0.08f)
    fun mutedOf() = lift(if (userPickedLight) 0.30f else 0.55f)

    val darkAccent = accent.darkenedFor(onDark)
    val darkSecondary = darkAccent.rotateHue(30f).darkenedFor(onDark)
    val lightAccent = accent.darkenedFor(Color.White)
    val lightSecondary = lightAccent.rotateHue(30f).darkenedFor(Color.White)

    return PocketPalette(
        accent = darkAccent,
        accentTeal = darkAccent.rotateHue(-30f),
        blue = darkAccent.rotateHue(60f),
        green = SemanticGreen,
        background = background,
        surface = surfaceOf(),
        surfaceVariant = variantOf(),
        outline = lift(0.16f),
        cardBorder = lift(0.12f),
        muted = mutedOf(),
        darkColors = darkColorScheme(
            primary = darkAccent,
            onPrimary = darkAccent.bestForeground(),
            primaryContainer = darkAccent.mix(background, 0.78f),
            onPrimaryContainer = darkAccent.mix(onDark, 0.55f),
            secondary = darkSecondary,
            onSecondary = darkSecondary.bestForeground(),
            secondaryContainer = darkSecondary.mix(background, 0.80f),
            onSecondaryContainer = darkSecondary.mix(onDark, 0.55f),
            tertiary = SemanticGreen,
            onTertiary = Color(0xFF00391E),
            background = background,
            onBackground = onDark,
            surface = surfaceOf(),
            onSurface = onDark,
            surfaceVariant = variantOf(),
            onSurfaceVariant = mutedOf(),
            outline = lift(0.16f),
            outlineVariant = lift(0.12f),
        ),
        lightColors = lightColorScheme(
            primary = lightAccent,
            onPrimary = Color.White,
            primaryContainer = lightAccent.mix(Color.White, 0.84f),
            onPrimaryContainer = lightAccent.mix(Color.Black, 0.55f),
            secondary = lightSecondary,
            onSecondary = Color.White,
            secondaryContainer = lightSecondary.mix(Color.White, 0.86f),
            onSecondaryContainer = lightSecondary.mix(Color.Black, 0.55f),
            tertiary = Color(0xFF1B8A5A),
            onTertiary = Color.White,
            background = background.mix(Color.White, 0.92f),
            onBackground = background.mix(Color.Black, 0.80f),
            surface = Color.White,
            onSurface = background.mix(Color.Black, 0.80f),
            surfaceVariant = background.mix(Color.White, 0.85f),
            onSurfaceVariant = background.mix(Color.Black, 0.45f),
            outline = background.mix(Color.White, 0.55f),
            outlineVariant = background.mix(Color.White, 0.75f),
        ),
    )
}
