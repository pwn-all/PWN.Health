package com.pwnall.health

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.util.Locale
import kotlin.math.roundToInt

internal enum class ThemeColorRole(@param:StringRes val label: Int) {
    MAIN_ACCENT(R.string.theme_main_accent),
    ACCENT_CONTAINER(R.string.theme_accent_container),
    SECONDARY(R.string.theme_secondary),
    SECONDARY_CONTAINER(R.string.theme_secondary_container),
    TERTIARY(R.string.theme_tertiary),
    TERTIARY_CONTAINER(R.string.theme_tertiary_container),
    BACKGROUND(R.string.theme_background),
    SURFACE(R.string.theme_surface),
    TEXT(R.string.theme_text),
    MUTED_TEXT(R.string.theme_muted_text),
    BORDER(R.string.theme_border),
    ICON(R.string.theme_icon),
    ICON_MID(R.string.theme_icon_mid),
    ICON_LIGHT(R.string.theme_icon_light),
}

/** Only overrides are stored, so an untouched theme keeps the complete built-in colour scheme. */
internal data class ThemePalette(val overrides: Map<ThemeColorRole, Int> = emptyMap()) {
    fun color(role: ThemeColorRole, dark: Boolean): Int = overrides[role] ?: defaultThemeColor(role, dark)

    fun edit(role: ThemeColorRole, color: Int, dark: Boolean, updateAll: Boolean = false): ThemePalette {
        val related = if (role == ThemeColorRole.MAIN_ACCENT && updateAll) {
            generatedThemeColors(color, dark)
        } else {
            emptyMap()
        }
        return ThemePalette(overrides + related + (role to color))
    }
}

internal fun parseThemeHex(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
    return (0xFF000000L or hex.toLong(16)).toInt()
}

internal fun themeHex(color: Int): String = "#%06X".format(Locale.ROOT, color and 0xFFFFFF)

internal fun defaultThemeColor(role: ThemeColorRole, dark: Boolean): Int {
    val scheme = if (dark) PwnDarkColors else PwnLightColors
    return mapOf(
        ThemeColorRole.MAIN_ACCENT to scheme.primary,
        ThemeColorRole.ACCENT_CONTAINER to scheme.primaryContainer,
        ThemeColorRole.SECONDARY to scheme.secondary,
        ThemeColorRole.SECONDARY_CONTAINER to scheme.secondaryContainer,
        ThemeColorRole.TERTIARY to scheme.tertiary,
        ThemeColorRole.TERTIARY_CONTAINER to scheme.tertiaryContainer,
        ThemeColorRole.BACKGROUND to scheme.background,
        ThemeColorRole.SURFACE to scheme.surface,
        ThemeColorRole.TEXT to scheme.onSurface,
        ThemeColorRole.MUTED_TEXT to scheme.onSurfaceVariant,
        ThemeColorRole.BORDER to scheme.outline,
        // The quiet set the navigation bar's resting stops wear, and so every drawing in the app;
        // the accent set is kept for what is chosen (accentIconColors).
        ThemeColorRole.ICON to scheme.onSurfaceVariant,
        ThemeColorRole.ICON_MID to scheme.outline,
        ThemeColorRole.ICON_LIGHT to if (dark) Color(0xFF24304A) else Color(0xFFEDF2FD),
    ).getValue(role).toArgb()
}

/** Opaque sRGB mixing, channel by channel: the white-tint recipe the duotone icon palette is drawn with. */
internal fun mixThemeColors(from: Int, to: Int, amount: Float): Int {
    val fraction = amount.coerceIn(0f, 1f)
    var result = 0xFF000000.toInt()
    for (shift in listOf(16, 8, 0)) {
        val a = (from ushr shift) and 255
        val b = (to ushr shift) and 255
        result = result or ((a + (b - a) * fraction).roundToInt() shl shift)
    }
    return result
}

internal fun generatedThemeColors(accent: Int, dark: Boolean): Map<ThemeColorRole, Int> {
    val ground = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    val ink = if (dark) 0xFFF4F5FA.toInt() else 0xFF131313.toInt()
    val hsv = themeHsv(accent)
    val secondary = mixThemeColors(accent, ground, 0.14f)
    val tertiary = Color.hsv(hsv[0], hsv[1] * 0.65f, hsv[2]).toArgb()
    val surface = mixThemeColors(accent, ground, if (dark) 0.82f else 0.99f)
    val containerMix = if (dark) 0.72f else 0.88f
    return mapOf(
        ThemeColorRole.MAIN_ACCENT to accent,
        ThemeColorRole.ACCENT_CONTAINER to mixThemeColors(accent, ground, containerMix),
        ThemeColorRole.SECONDARY to secondary,
        ThemeColorRole.SECONDARY_CONTAINER to mixThemeColors(secondary, ground, containerMix),
        ThemeColorRole.TERTIARY to tertiary,
        ThemeColorRole.TERTIARY_CONTAINER to mixThemeColors(tertiary, ground, containerMix),
        ThemeColorRole.BACKGROUND to mixThemeColors(accent, ground, if (dark) 0.9f else 0.97f),
        ThemeColorRole.SURFACE to surface,
        ThemeColorRole.TEXT to ink,
        ThemeColorRole.MUTED_TEXT to mixThemeColors(ink, surface, 0.38f),
        // Pale on purpose: the border also becomes the variant outline, the colour of dividers and
        // quiet card edges, which are meant to recede. Where it is the only edge a field has, the
        // outline drawn from it is held to 3:1 at draw time — see legible() below.
        ThemeColorRole.BORDER to mixThemeColors(accent, ground, if (dark) 0.6f else 0.82f),
        ThemeColorRole.ICON to accent,
        ThemeColorRole.ICON_MID to mixThemeColors(accent, ground, if (dark) 0.45f else 0.55f),
        ThemeColorRole.ICON_LIGHT to mixThemeColors(accent, ground, if (dark) 0.75f else 0.88f),
    )
}

/** Kept independent of android.graphics.Color so the picker and generation share testable math. */
internal fun themeHsv(color: Int): List<Float> {
    val r = ((color ushr 16) and 255) / 255f
    val g = ((color ushr 8) and 255) / 255f
    val b = (color and 255) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta + 6f) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return listOf(hue, if (max == 0f) 0f else delta / max, max)
}

internal fun themeContentColor(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White

/** WCAG contrast ratio of two opaque colours. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val lighter = maxOf(a.luminance(), b.luminance())
    val darker = minOf(a.luminance(), b.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * [foreground] as close to itself as it can stay while clearing [minimum] against every ground:
 * mixed toward black on light grounds or white on dark ones, never further than it has to go.
 *
 * Grounds of both kinds at once are the limit. A dark page under light cards can leave no colour
 * that clears both, and going all the way to black or white — chosen by the grounds' average
 * luminance, say — would leave the text at 1.1:1 on the ground of the other kind: black on a
 * near-black page, whatever text colour was picked. What is drawn then is the mix, toward either pole, whose
 * worst ground is the best any mix manages: a mid tone of the picked colour, between 3:1 and 4:1 on
 * the page and on the built-in cards alike. That is short of [minimum], and it reads everywhere.
 *
 * Only what is drawn is changed; the override the person saved stays exactly as they picked it, so
 * a text colour chosen equal to the surface still reads, and choosing a legible one later is not
 * fighting a colour the app rewrote behind their back.
 */
internal fun legibleOn(foreground: Color, grounds: List<Color>, minimum: Float): Color {
    fun worst(color: Color) = grounds.minOf { contrastRatio(color, it) }
    val target = if (grounds.map { it.luminance() }.average() > THEME_CONTENT_SPLIT) Color.Black else Color.White
    return when {
        worst(foreground) >= minimum -> foreground

        worst(target) >= minimum -> nearestMix(foreground, target) { worst(it) >= minimum }

        // The picked colour stands first, so it is what is drawn when no mix does better than it.
        else -> (listOf(foreground) + listOf(Color.Black, Color.White).flatMap { mixes(foreground, it) })
            .maxBy { worst(it) }
    }
}

/** The mix nearest [foreground] on the way to [target] that [clears], which [target] itself does. */
private fun nearestMix(foreground: Color, target: Color, clears: (Color) -> Boolean): Color {
    var low = 0f
    var high = 1f
    repeat(LEGIBLE_STEPS) {
        val middle = (low + high) / 2f
        if (clears(lerp(foreground, target, middle))) high = middle else low = middle
    }
    return lerp(foreground, target, high)
}

/** Every step from [foreground] to [pole]: what the tone for grounds of both kinds is chosen from. */
private fun mixes(foreground: Color, pole: Color): List<Color> =
    (1..COMPROMISE_STEPS).map { lerp(foreground, pole, it / COMPROMISE_STEPS.toFloat()) }

private const val THEME_CONTENT_SPLIT = 0.179
private const val LEGIBLE_STEPS = 12

/** Neighbouring mid tones stand a third of a contrast point apart at this many steps to a pole. */
private const val COMPROMISE_STEPS = 48

internal fun ThemePalette.colorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) PwnDarkColors else PwnLightColors
    if (overrides.isEmpty()) return base
    return rawColorScheme(base, dark).legible()
}

/**
 * Independent overrides can collide — text picked the colour of the surface, a border the colour of
 * the page. Text roles are held to 4.5:1 and the outline to 3:1 against every ground they are drawn
 * on, which is what keeps a custom theme readable while its grounds are all light or all dark. A
 * dark page picked under light cards cannot be given that much on both, and gets the one tone that
 * reads on both instead — the limit [legibleOn] describes.
 *
 * The accent is held to 4.5:1 as well, because the app prints it as text — a chosen option's label,
 * a text button — as well as filling selections with it. A pale accent such as amber is drawn
 * deepened until it reads, and what is printed on it is chosen again against the colour drawn.
 */
private fun ColorScheme.legible(): ColorScheme {
    val grounds = textGrounds
    val drawnPrimary = legibleOn(primary, grounds, TEXT_CONTRAST)
    return copy(
        primary = drawnPrimary,
        onPrimary = if (drawnPrimary == primary) onPrimary else themeContentColor(drawnPrimary),
        onBackground = legibleOn(onBackground, grounds, TEXT_CONTRAST),
        onSurface = legibleOn(onSurface, grounds, TEXT_CONTRAST),
        onSurfaceVariant = legibleOn(onSurfaceVariant, grounds, TEXT_CONTRAST),
        outline = legibleOn(outline, grounds, GRAPHIC_CONTRAST),
    )
}

/** The page and every surface tier: what text in this scheme is printed on. */
internal val ColorScheme.textGrounds: List<Color>
    get() = listOf(
        background,
        surface,
        surfaceContainerLow,
        surfaceContainer,
        surfaceContainerHigh,
        surfaceContainerHighest,
    )

/**
 * A status tone — [dark] for a dark page, [light] for a light one — as a custom [scheme] draws it.
 * The pairs are measured against the built-in palettes only, and on a page of middling brightness a
 * picked palette can give neither of them a ground it reads on: the tone is deepened or lightened
 * as far as text needs, the way the scheme's own text roles are.
 */
internal fun statusTone(dark: Color, light: Color, scheme: ColorScheme): Color =
    legibleOn(if (scheme.background.luminance() < 0.5f) dark else light, scheme.textGrounds, TEXT_CONTRAST)

internal const val TEXT_CONTRAST = 4.5f
internal const val GRAPHIC_CONTRAST = 3f

private fun ThemePalette.rawColorScheme(base: ColorScheme, dark: Boolean): ColorScheme {
    fun value(role: ThemeColorRole) = Color(color(role, dark))
    fun changed(role: ThemeColorRole, original: Color) = overrides[role]?.let(::Color) ?: original
    fun content(role: ThemeColorRole, original: Color) =
        if (role in overrides) themeContentColor(value(role)) else original
    return base.copy(
        primary = value(ThemeColorRole.MAIN_ACCENT),
        onPrimary = content(ThemeColorRole.MAIN_ACCENT, base.onPrimary),
        primaryContainer = value(ThemeColorRole.ACCENT_CONTAINER),
        onPrimaryContainer = content(ThemeColorRole.ACCENT_CONTAINER, base.onPrimaryContainer),
        secondary = value(ThemeColorRole.SECONDARY),
        onSecondary = content(ThemeColorRole.SECONDARY, base.onSecondary),
        secondaryContainer = value(ThemeColorRole.SECONDARY_CONTAINER),
        onSecondaryContainer = content(ThemeColorRole.SECONDARY_CONTAINER, base.onSecondaryContainer),
        tertiary = value(ThemeColorRole.TERTIARY),
        onTertiary = content(ThemeColorRole.TERTIARY, base.onTertiary),
        tertiaryContainer = value(ThemeColorRole.TERTIARY_CONTAINER),
        onTertiaryContainer = content(ThemeColorRole.TERTIARY_CONTAINER, base.onTertiaryContainer),
        background = value(ThemeColorRole.BACKGROUND),
        onBackground = value(ThemeColorRole.TEXT),
        surface = value(ThemeColorRole.SURFACE),
        onSurface = value(ThemeColorRole.TEXT),
        surfaceVariant = changed(ThemeColorRole.ACCENT_CONTAINER, base.surfaceVariant),
        onSurfaceVariant = value(ThemeColorRole.MUTED_TEXT),
        outline = value(ThemeColorRole.BORDER),
        outlineVariant = changed(ThemeColorRole.BORDER, base.outlineVariant),
        surfaceDim = changed(ThemeColorRole.BACKGROUND, base.surfaceDim),
        surfaceBright = changed(ThemeColorRole.SURFACE, base.surfaceBright),
        surfaceContainerLowest = changed(ThemeColorRole.BACKGROUND, base.surfaceContainerLowest),
        surfaceContainerLow = changed(ThemeColorRole.SURFACE, base.surfaceContainerLow),
        surfaceContainer = changed(ThemeColorRole.ACCENT_CONTAINER, base.surfaceContainer),
        surfaceContainerHigh = changed(ThemeColorRole.SURFACE, base.surfaceContainerHigh),
        surfaceContainerHighest = changed(ThemeColorRole.ACCENT_CONTAINER, base.surfaceContainerHighest),
        surfaceTint = changed(ThemeColorRole.MAIN_ACCENT, base.surfaceTint),
    )
}
