package com.pwnall.health

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Semantic accents are also used as labels and selected text, not only filled backgrounds. */
class DesignContrastTest {
    @Test
    fun defaultTextRolesRemainReadableAcrossEverySurfaceTier() {
        for (scheme in listOf(PwnLightColors, PwnDarkColors)) {
            val surfaces = listOf(
                scheme.background,
                scheme.surface,
                scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            )
            val status = if (scheme == PwnDarkColors) {
                listOf(PwnWarnDarkRaw, PwnBleedDarkRaw)
            } else {
                listOf(PwnWarnLightRaw, PwnBleedLightRaw)
            }
            for (surface in surfaces) {
                for (text in listOf(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary) + status) {
                    assertContrast(text, surface, 4.5f)
                }
                assertContrast(scheme.outline, surface, 3f)
            }
        }
    }

    @Test
    fun meaningfulDefaultIconTonesRemainVisibleOnNavigationAndCards() {
        for (dark in listOf(false, true)) {
            val scheme = if (dark) PwnDarkColors else PwnLightColors
            val icons = ThemePalette().iconColors(dark)
            // The add glyph is drawn on the mid-tone disc, not directly on the surface.
            assertContrast(icons.light, icons.mid, 3f)
            for (surface in listOf(scheme.surface, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)) {
                for (ink in listOf(icons.ink, icons.mid, icons.line)) {
                    assertContrast(ink, surface, 3f)
                }
            }
        }
    }

    @Test
    fun navigationBarStatesStayVisibleOnTheBar() {
        for (dark in listOf(false, true)) {
            val scheme = if (dark) PwnDarkColors else PwnLightColors
            val resting = ThemePalette().iconColors(dark)
            val accent = ThemePalette().accentIconColors(dark)
            val bar = scheme.surfaceContainer
            // Unselected stops draw in the neutral set on the bar; the chosen one in the accent set
            // on its indicator; the add stop in the accent set on either.
            for (ink in listOf(resting.ink, resting.mid, accent.ink, accent.mid)) {
                assertContrast(ink, bar, 3f)
            }
            for (ink in listOf(accent.ink, accent.mid)) assertContrast(ink, scheme.secondaryContainer, 3f)
            assertContrast(scheme.onSurfaceVariant, bar, 4.5f)
            assertContrast(scheme.primary, bar, 4.5f)
        }
    }

    @Test
    fun pairedContentRolesMeetNormalTextContrast() {
        for (scheme in listOf(PwnLightColors, PwnDarkColors)) {
            for ((background, foreground) in listOf(
                scheme.primary to scheme.onPrimary,
                scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.secondary to scheme.onSecondary,
                scheme.secondaryContainer to scheme.onSecondaryContainer,
                scheme.tertiary to scheme.onTertiary,
                scheme.tertiaryContainer to scheme.onTertiaryContainer,
                scheme.error to scheme.onError,
                scheme.errorContainer to scheme.onErrorContainer,
                scheme.inverseSurface to scheme.inverseOnSurface,
                scheme.inverseSurface to scheme.inversePrimary,
            )) {
                assertContrast(foreground, background, 4.5f)
            }
        }
    }

    @Test
    fun selectionFillCarriesTheStateAndTheWordOnIt() {
        for (dark in listOf(false, true)) {
            val scheme = if (dark) PwnDarkColors else PwnLightColors
            val fill = if (dark) PwnBlueInkDarkRaw else PwnBlueInkLightRaw
            // The switch's on track and the segmented control's pill identify a control's state,
            // so they owe 3:1 to the page, the track and the card they stand on.
            for (ground in listOf(scheme.background, scheme.surface, scheme.surfaceContainerHigh)) {
                assertContrast(fill, ground, 3f)
            }
            // The switch's knob and the chosen segment's word are the page colour on that fill.
            assertContrast(scheme.background, fill, 4.5f)
            // A lit tile's tick and edge are the accent on its primaryContainer fill.
            assertContrast(scheme.primary, scheme.primaryContainer, 3f)
        }
    }

    /**
     * The sex-life and work charts, on their card: a bar is a graphical object and owes the card 3:1
     * (WCAG 1.4.11), and the line or the median drawn across it, in the card's ink, owes the same to
     * the bar it crosses; the two acts lines are held to text weight on the card, as their comment says.
     * The work chart's switched lines (ink, warn, blue ink, bleed) run on a rim in the card's colour, so
     * each owes 3:1 to the card alone; the long-days card's three bars (secondary, muted, green) too.
     */
    @Test
    fun weekChartBarsAndLinesStandOutOnTheirCard() {
        for (dark in listOf(false, true)) {
            val scheme = if (dark) PwnDarkColors else PwnLightColors
            val card = scheme.surfaceContainerHigh
            val alpha = if (dark) WEEK_BAR_ALPHA_DARK else WEEK_BAR_ALPHA_LIGHT
            val bar = scheme.secondary.copy(alpha = alpha).compositeOver(card)
            assertContrast(bar, card, 3f)
            assertContrast(scheme.onSurface, bar, 3f)
            assertContrast(if (dark) PwnBlueInkDarkRaw else PwnBlueInkLightRaw, card, 4.5f)
            assertContrast(if (dark) PwnWarnDarkRaw else PwnWarnLightRaw, card, 4.5f)
            assertContrast(if (dark) PwnBleedDarkRaw else PwnBleedLightRaw, card, 3f)
            assertContrast(if (dark) PwnGreenDarkRaw else PwnGreenRaw, card, 3f)
            assertContrast(scheme.secondary, card, 3f)
            assertContrast(scheme.onSurfaceVariant, card, 3f)
        }
    }

    @Test
    fun separatorsStayVisibleOnEverySurfaceTier() {
        for (scheme in listOf(PwnLightColors, PwnDarkColors)) {
            for (surface in listOf(
                scheme.background,
                scheme.surface,
                scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            )) {
                // Not a WCAG figure — a divider is not a control — but a hairline under this
                // disappears into the card it is meant to divide.
                assertContrast(scheme.outlineVariant, surface, 1.3f)
            }
        }
    }

    @Test
    fun compactCardDescriptionStaysReadableWhenSoftened() {
        for (scheme in listOf(PwnLightColors, PwnDarkColors)) {
            val ink = scheme.onPrimaryContainer.copy(alpha = COMPACT_SUBTITLE_ALPHA)
                .compositeOver(scheme.primaryContainer)
            assertContrast(ink, scheme.primaryContainer, 4.5f)
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val light = maxOf(foreground.luminance(), background.luminance())
        val dark = minOf(foreground.luminance(), background.luminance())
        val ratio = (light + 0.05f) / (dark + 0.05f)
        assertTrue("$foreground on $background: $ratio < $minimum", ratio >= minimum)
    }
}
