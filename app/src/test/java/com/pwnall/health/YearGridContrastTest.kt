package com.pwnall.health

import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

/** The year grid's faintest walked day has to stand apart from the card and from a day not walked. */
class YearGridContrastTest {
    @Test
    fun theFaintestWalkedDayClearsGraphicContrastInBothPalettes() {
        for (scheme in listOf(PwnLightColors, PwnDarkColors)) {
            val card = scheme.surfaceContainerHigh
            val empty = scheme.surfaceVariant
            // The smallest share a walked day can have is just above nothing.
            val faintest = scheme.primary.copy(alpha = yearCellAlpha(0.001f)).compositeOver(card)
            for (ground in listOf(card, empty)) {
                val ratio = contrastRatio(faintest, ground)
                assertTrue("$faintest on $ground: $ratio", ratio >= 3f)
            }
        }
    }

    @Test
    fun aDayCloserToTheGoalIsShadedMore() {
        assertTrue(yearCellAlpha(0.5f) > yearCellAlpha(0.1f))
        assertTrue(yearCellAlpha(1f) == 1f)
    }
}
