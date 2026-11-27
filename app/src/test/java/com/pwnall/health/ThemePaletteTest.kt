package com.pwnall.health

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {
    private val purple = 0xFF6D6DCC.toInt()
    private val green = 0xFF228855.toInt()

    /** Material amber: a pale accent, 1.6:1 against a white page as picked. */
    private val amber = 0xFFFFC107.toInt()

    /** Material green: a mid accent that still falls short of text contrast on white. */
    private val midGreen = 0xFF4CAF50.toInt()

    @Test
    fun hexAcceptsOptionalHashAndCaseButRejectsIncompleteAndAlphaValues() {
        assertEquals(purple, parseThemeHex(" #6d6dcc "))
        assertEquals(purple, parseThemeHex("6D6DCC"))
        assertEquals("#6D6DCC", themeHex(purple))
        for (invalid in listOf("", "#", "abc", "12345", "#FF6D6DCC", "#GG0011", "0x123456", "##123456")) {
            assertNull(invalid, parseThemeHex(invalid))
        }
    }

    @Test
    fun mainAccentWithoutUpdateAllPreservesEveryOtherColorIncludingManualEdits() {
        for (dark in listOf(false, true)) {
            val manual = ThemePalette().edit(ThemeColorRole.ICON_MID, green, dark)
                .edit(ThemeColorRole.BACKGROUND, 0xFF334455.toInt(), dark)
            val changed = manual.edit(ThemeColorRole.MAIN_ACCENT, purple, dark)
            assertEquals(purple, changed.color(ThemeColorRole.MAIN_ACCENT, dark))
            ThemeColorRole.entries.filter { it != ThemeColorRole.MAIN_ACCENT }.forEach {
                assertEquals(it.name, manual.color(it, dark), changed.color(it, dark))
            }
        }
    }

    @Test
    fun updateAllExplicitlyReplacesManualColorsButDoesNotCarryIntoTheNextEdit() {
        val manual = ThemePalette().edit(ThemeColorRole.ICON_MID, green, false)
        val generated = manual.edit(ThemeColorRole.MAIN_ACCENT, purple, false, updateAll = true)
        assertEquals(ThemeColorRole.entries.toSet(), generated.overrides.keys)
        assertEquals(0xFFBDBDE8.toInt(), generated.color(ThemeColorRole.ICON_MID, false))
        assertEquals(0xFFEDEDF9.toInt(), generated.color(ThemeColorRole.ICON_LIGHT, false))
        val next = generated.edit(ThemeColorRole.MAIN_ACCENT, green, false)
        assertEquals(generated.overrides - ThemeColorRole.MAIN_ACCENT, next.overrides - ThemeColorRole.MAIN_ACCENT)
    }

    @Test
    fun secondaryEditsCannotTriggerPaletteGeneration() {
        val original = ThemePalette().edit(ThemeColorRole.MAIN_ACCENT, purple, true, updateAll = true)
        val changed = original.edit(ThemeColorRole.ICON, green, true, updateAll = true)
        assertEquals(original.overrides - ThemeColorRole.ICON, changed.overrides - ThemeColorRole.ICON)
        assertEquals(green, changed.color(ThemeColorRole.ICON, true))
    }

    @Test
    fun lightAndDarkPreferencesAreIndependentAndInvalidSavedValuesFallBack() {
        val stored = mapOf(
            themePreferenceKey(false, ThemeColorRole.ICON) to "#112233",
            themePreferenceKey(true, ThemeColorRole.ICON) to "#AABBCC",
            themePreferenceKey(false, ThemeColorRole.MAIN_ACCENT) to "bad value",
            themePreferenceKey(true, ThemeColorRole.BORDER) to 42,
            "light.FUTURE_COLOR" to "#FFFFFF",
        )
        val light = themePaletteFromPreferences(stored, false)
        val dark = themePaletteFromPreferences(stored, true)
        assertEquals(mapOf(ThemeColorRole.ICON to 0xFF112233.toInt()), light.overrides)
        assertEquals(mapOf(ThemeColorRole.ICON to 0xFFAABBCC.toInt()), dark.overrides)
        assertEquals(PwnLightColors.primary.toArgb(), light.color(ThemeColorRole.MAIN_ACCENT, false))
        val restored = themePaletteFromPreferences(
            light.overrides.mapKeys { themePreferenceKey(false, it.key) }.mapValues { themeHex(it.value) },
            false,
        )
        assertEquals(light, restored)
    }

    @Test
    fun emptyOverridesRestoreTheExactDefaultSchemes() {
        assertEquals(PwnLightColors, ThemePalette().colorScheme(false))
        assertEquals(PwnDarkColors, ThemePalette().colorScheme(true))
        // The default drawings wear the navigation bar's resting ink; the accent is for what is chosen.
        assertEquals(PwnLightColors.onSurfaceVariant.toArgb(), ThemePalette().color(ThemeColorRole.ICON, false))
        assertEquals(PwnDarkColors.onSurfaceVariant.toArgb(), ThemePalette().color(ThemeColorRole.ICON, true))
        assertEquals(PwnLightColors.primary, ThemePalette().accentIconColors(false).ink)
        assertEquals(PwnDarkColors.primary, ThemePalette().accentIconColors(true).ink)
    }

    @Test
    fun generatedThemesHaveOrderedIconTonesAndReadableText() {
        for (seed in listOf(purple, green, Color.White.toArgb(), Color.Black.toArgb(), Color.Red.toArgb())) {
            for (dark in listOf(false, true)) {
                val palette = ThemePalette(generatedThemeColors(seed, dark))
                val ink = Color(palette.color(ThemeColorRole.ICON, dark)).luminance()
                val mid = Color(palette.color(ThemeColorRole.ICON_MID, dark)).luminance()
                val light = Color(palette.color(ThemeColorRole.ICON_LIGHT, dark)).luminance()
                assertTrue(if (dark) ink >= mid && mid >= light else ink <= mid && mid <= light)
                val scheme = palette.colorScheme(dark)
                assertTrue(contrast(scheme.background, scheme.onBackground) >= 4.5f)
                assertTrue(contrast(scheme.surface, scheme.onSurfaceVariant) >= 4.5f)
                assertTrue(contrast(scheme.primary, scheme.onPrimary) >= 4.5f)
            }
        }
        assertNotEquals(generatedThemeColors(purple, false), generatedThemeColors(purple, true))
    }

    @Test
    fun pickerHsvRoundTripsIncludingGreyBlackWhiteAndHueWrap() {
        for (color in listOf(purple, green, Color.Black.toArgb(), Color.White.toArgb(), 0xFF808080.toInt())) {
            val hsv = themeHsv(color)
            assertEquals(color, Color.hsv(hsv[0], hsv[1], hsv[2]).toArgb())
        }
    }

    @Test
    fun collidingOverridesStillDrawReadableTextWithoutRewritingTheSavedChoice() {
        for (dark in listOf(false, true)) {
            val ground = if (dark) 0xFF101010.toInt() else 0xFFFFFFFF.toInt()
            val palette = ThemePalette()
                .edit(ThemeColorRole.SURFACE, ground, dark)
                .edit(ThemeColorRole.BACKGROUND, ground, dark)
                .edit(ThemeColorRole.TEXT, ground, dark)
                .edit(ThemeColorRole.MUTED_TEXT, ground, dark)
                .edit(ThemeColorRole.BORDER, ground, dark)
                .edit(ThemeColorRole.ICON, ground, dark)
                .edit(ThemeColorRole.ICON_MID, ground, dark)
            val scheme = palette.colorScheme(dark)
            for (text in listOf(scheme.onSurface, scheme.onBackground, scheme.onSurfaceVariant)) {
                assertTrue(contrast(text, scheme.surface) >= 4.5f)
                assertTrue(contrast(text, scheme.background) >= 4.5f)
            }
            assertTrue(contrast(scheme.outline, scheme.surface) >= 3f)
            val icons = palette.iconColors(dark)
            assertTrue(contrast(icons.ink, scheme.surface) >= 3f)
            assertTrue(contrast(icons.mid, scheme.surface) >= 3f)
            assertEquals(ground, palette.color(ThemeColorRole.TEXT, dark))
        }
    }

    /**
     * A near-black page picked under the light palette's own cards. No colour clears 4.5:1 on both,
     * and pure black text would read 1.1:1 on the page, whatever was picked for it, white included.
     * It is drawn in the one tone that reads on both.
     */
    @Test
    fun aDarkPageUnderLightCardsStillGetsTextThatReadsOnBoth() {
        val page = ThemePalette().edit(ThemeColorRole.BACKGROUND, 0xFF101010.toInt(), false)
        for (palette in listOf(page, page.edit(ThemeColorRole.TEXT, 0xFFFFFFFF.toInt(), false))) {
            val scheme = palette.colorScheme(false)
            for (text in listOf(scheme.onBackground, scheme.onSurface, scheme.onSurfaceVariant)) {
                assertTrue("page ${contrast(text, scheme.background)}", contrast(text, scheme.background) >= 3f)
                assertTrue("card ${contrast(text, scheme.surface)}", contrast(text, scheme.surface) >= 3f)
            }
        }
    }

    @Test
    fun groundsOfBothKindsGetTheToneThatReadsBestOnTheWorstOfThem() {
        val grounds = listOf(Color.Black, Color.White)
        fun worst(color: Color) = grounds.minOf { contrast(color, it) }
        for (picked in listOf(Color.Black, Color.White, Color(purple), Color(amber))) {
            val drawn = legibleOn(picked, grounds, TEXT_CONTRAST)
            assertTrue("${themeHex(picked.toArgb())}: ${worst(drawn)}", worst(drawn) >= 4f)
            assertTrue(worst(drawn) >= worst(picked))
        }
    }

    @Test
    fun generatedOutlinesClearEveryGroundWhileDividersStayPale() {
        for (seed in listOf(amber, midGreen, PwnLightColors.primary.toArgb(), PwnDarkColors.primary.toArgb())) {
            for (dark in listOf(false, true)) {
                val palette = ThemePalette().edit(ThemeColorRole.MAIN_ACCENT, seed, dark, updateAll = true)
                val scheme = palette.colorScheme(dark)
                val grounds = listOf(scheme.surface, scheme.background, scheme.surfaceContainer)
                for (ground in grounds) {
                    val ratio = contrast(scheme.outline, ground)
                    assertTrue("${themeHex(seed)} dark=$dark: $ratio", ratio >= 3f)
                }
                assertEquals(Color(palette.color(ThemeColorRole.BORDER, dark)), scheme.outlineVariant)
            }
        }
    }

    @Test
    fun paleAccentsAreDrawnLegibleButKeptAsPicked() {
        for (seed in listOf(amber, midGreen)) {
            for (dark in listOf(false, true)) {
                val palette = ThemePalette().edit(ThemeColorRole.MAIN_ACCENT, seed, dark, updateAll = true)
                val scheme = palette.colorScheme(dark)
                for (ground in listOf(scheme.surface, scheme.background, scheme.surfaceContainer)) {
                    assertTrue(contrast(scheme.primary, ground) >= 4.5f)
                }
                assertTrue(contrast(scheme.primary, scheme.onPrimary) >= 4.5f)
                assertEquals(seed, palette.color(ThemeColorRole.MAIN_ACCENT, dark))
            }
        }
    }

    /**
     * The status tones — the warning, the bleed, the green, the link blue — come in a pair measured
     * against the built-in palettes. A mid-grey page counts as dark and would take the pale one of
     * each, near 2:1 on the page; on a custom palette they are drawn as its text is, legible on every
     * ground.
     */
    @Test
    fun statusTonesReadOnACustomPageOfMiddlingBrightness() {
        val scheme = ThemePalette().edit(ThemeColorRole.BACKGROUND, 0xFF808080.toInt(), false).colorScheme(false)
        val pairs = listOf(
            PwnWarnDarkRaw to PwnWarnLightRaw,
            PwnBleedDarkRaw to PwnBleedLightRaw,
            PwnGreenDarkRaw to PwnGreenRaw,
            PwnBlueInkDarkRaw to PwnBlueInkLightRaw,
        )
        assertTrue(contrast(PwnWarnDarkRaw, scheme.background) < 3f)
        for ((dark, light) in pairs) {
            val tone = statusTone(dark, light, scheme)
            for (ground in scheme.textGrounds) {
                assertTrue("${themeHex(tone.toArgb())} on ${themeHex(ground.toArgb())}", contrast(tone, ground) >= 4.5f)
            }
        }
        // The built-in palettes' tones already read, and are drawn exactly as they are.
        assertEquals(PwnWarnLightRaw, statusTone(PwnWarnDarkRaw, PwnWarnLightRaw, PwnLightColors))
        assertEquals(PwnWarnDarkRaw, statusTone(PwnWarnDarkRaw, PwnWarnLightRaw, PwnDarkColors))
    }

    @Test
    fun legibleOverridesAreDrawnExactlyAsPicked() {
        val palette = ThemePalette().edit(ThemeColorRole.TEXT, 0xFF202020.toInt(), false)
        assertEquals(Color(0xFF202020), palette.colorScheme(false).onSurface)
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
}
