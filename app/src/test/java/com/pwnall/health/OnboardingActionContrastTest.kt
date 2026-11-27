package com.pwnall.health

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The onboarding action prints white lettering on its fill, whatever blue the theme brings. */
class OnboardingActionContrastTest {
    @Test
    fun whiteLetteringClearsNormalTextContrastOnBothStops() {
        val accents = listOf(
            PwnLightColors.primary,
            PwnDarkColors.primary,
            // Accents a custom theme could pick, from the palest to the loudest.
            Color.White,
            Color(0xFFFFEB3B),
            Color(0xFFFF80AB),
            Color(0xFF00E676),
            Color.Black,
        )
        for (accent in accents) {
            val stops = actionFill(accent)
            assertEquals(2, stops.size)
            for (stop in stops) {
                val ratio = contrastRatio(Color.White, stop)
                assertTrue("white on $stop from $accent: $ratio", ratio >= 4.5f)
            }
        }
    }

    @Test
    fun theFillIsTheDeeperOfTheTwoStopsInBothPalettes() {
        // Both stops are deepened together, so the first, the one the button is filled with, stays
        // the deeper: the paler one left over from the gradient only sets how far that goes.
        for (accent in listOf(PwnLightColors.primary, PwnDarkColors.primary)) {
            val stops = actionFill(accent)
            assertTrue(contrastRatio(Color.White, stops[0]) > contrastRatio(Color.White, stops[1]))
        }
    }
}
