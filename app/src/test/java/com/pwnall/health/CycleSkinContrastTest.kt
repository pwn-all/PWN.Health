package com.pwnall.health

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cycle screen keeps its own palette rather than scheme roles (see CycleSkin), so the pairs it
 * prints are measured here, in both themes, the way DesignContrastTest measures the scheme.
 */
class CycleSkinContrastTest {
    private data class Theme(
        val scheme: androidx.compose.material3.ColorScheme,
        val violet: Color,
        val card: Color,
        val cell: Color,
        val cellInk: Color,
        val accent: Color,
        val bleed: Color,
    )

    private val themes = listOf(
        Theme(
            PwnLightColors,
            CycleVioletLightRaw,
            CycleCardLightRaw,
            CycleCellLightRaw,
            CycleCellInkLightRaw,
            CycleAccentLightRaw,
            PwnBleedLightRaw,
        ),
        Theme(
            PwnDarkColors,
            CycleVioletDarkRaw,
            CycleCardDarkRaw,
            CycleCellDarkRaw,
            CycleCellInkDarkRaw,
            CycleAccentDarkRaw,
            PwnBleedDarkRaw,
        ),
    )

    @Test
    fun textOnTheHeroIsReadable() {
        for (theme in themes) {
            val violet = theme.violet
            assertContrast(CycleOnViolet, violet, TEXT)
            assertContrast(CycleOnVioletMuted, violet, TEXT)
            // The window strip: white labels on the track, the violet printed on the white pill.
            assertContrast(CycleOnViolet, CycleVioletTrack.compositeOver(violet), TEXT)
            assertContrast(violet, CycleOnViolet, TEXT)
            // Today's date under the plot, inside its disc.
            assertContrast(CycleOnViolet, CycleTodayDisc.compositeOver(violet), TEXT)
        }
    }

    @Test
    fun chartMarksStandClearOfTheHero() {
        for (theme in themes) {
            for (mark in listOf(CycleOnViolet, CyclePeach, CycleMint, CycleEstimate)) {
                assertContrast(mark, theme.violet, GRAPHIC)
            }
        }
    }

    @Test
    fun textOnTheSheetCardsAndCellsIsReadable() {
        for (theme in themes) {
            val scheme = theme.scheme
            assertContrast(theme.cellInk, theme.cell, TEXT)
            assertContrast(theme.bleed, theme.cell, TEXT)
            for (ground in listOf(theme.card, theme.cell)) {
                assertContrast(scheme.onSurfaceVariant, ground, TEXT)
            }
            // The chosen day: the sheet's colour printed on the accent.
            assertContrast(scheme.surface, theme.accent, TEXT)
            // The accent is the slider's answer word, printed on the sheet.
            assertContrast(theme.accent, scheme.surface, TEXT)
        }
    }

    @Test
    fun calendarMarksStandClearOfThePage() {
        for (theme in themes) {
            val page = theme.scheme.background
            // The fertile day's dotted ring and the ovulation ring, on the page and on the fill.
            assertContrast(theme.accent, page, GRAPHIC)
            assertContrast(theme.accent, theme.cell, GRAPHIC)
            // A neighbouring month's day number, at full strength.
            assertContrast(theme.scheme.onSurfaceVariant, page, TEXT)
            assertContrast(theme.bleed, page, TEXT)
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$foreground on $background is $ratio:1, under $minimum:1", ratio >= minimum)
    }

    private companion object {
        const val TEXT = 4.5f
        const val GRAPHIC = 3f
    }
}
