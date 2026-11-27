package com.pwnall.health

import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hour bars on the home cards and on the Steps page are the only drawing of when the day's
 * activity happened, so even the quiet ones are meaningful graphics and owe 3:1 against the card.
 */
class HomeChartContrastTest {
    @Test
    fun quietHourBarsStillClearGraphicsContrastOnTheCard() {
        for (scheme in listOf(PwnLightColors, PwnDarkColors)) {
            val card = scheme.surfaceContainerHigh
            // The card tints: steps in primary, food in tertiary, the burn in secondary. The Steps
            // page draws its bars in primary too.
            for (tint in listOf(scheme.primary, scheme.secondary, scheme.tertiary)) {
                val bar = tint.copy(alpha = QUIET_BAR_ALPHA).compositeOver(card)
                val ratio = contrastRatio(bar, card)
                assertTrue("$tint at $QUIET_BAR_ALPHA on $card: $ratio < 3", ratio >= 3f)
            }
        }
    }
}
