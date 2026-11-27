package com.pwnall.health

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class WellnessPaletteTest {
    @Test
    fun pastelFilledActionsKeepReadableNavyLabelsInBothThemes() {
        listOf(PwnLightColors, PwnDarkColors).forEach { scheme ->
            listOf(
                scheme.primary to scheme.onPrimary,
                scheme.secondary to scheme.onSecondary,
                scheme.tertiary to scheme.onTertiary,
            ).forEach { (fill, ink) ->
                val contrast = (max(fill.luminance(), ink.luminance()) + 0.05f) /
                    (min(fill.luminance(), ink.luminance()) + 0.05f)
                assertTrue("Filled action contrast: $contrast", contrast >= 4.5f)
            }
        }
    }
}
