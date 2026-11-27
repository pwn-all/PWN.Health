package com.pwnall.health

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/*
 * The cycle screen's own palette.
 *
 * Named values rather than scheme roles, on purpose: the hero is a mid blue with white on it in both
 * themes, and the chart tints are all derived from that white. The scheme's night primary is a pale
 * blue carrying near-black, so mapping the hero onto it would turn every one of those tints to
 * nothing. Each pair these values are printed in is measured instead, by CycleSkinContrastTest, the
 * way DesignContrastTest measures the scheme.
 */

internal val CycleVioletLightRaw = Color(0xFF3D65AD)
internal val CycleVioletDarkRaw = Color(0xFF335C9C)
internal val CycleCardLightRaw = Color(0xFFF0F4FB)
internal val CycleCardDarkRaw = Color(0xFF1E2C43)
internal val CycleCellLightRaw = Color(0xFFE8EFFB)
internal val CycleCellDarkRaw = Color(0xFF283B59)
internal val CycleCellInkLightRaw = Color(0xFF2F5799)
internal val CycleCellInkDarkRaw = Color(0xFFC8D9FA)
internal val CycleAccentLightRaw = Color(0xFF355FAA)
internal val CycleAccentDarkRaw = Color(0xFF91B0F1)

/** Cycle controls use the same blue family as the rest of the app, with white text on the hero. */
internal val CycleViolet: Color
    @Composable get() = darkOrCycle(CycleVioletDarkRaw, CycleVioletLightRaw)

/** What the blue carries: white, at full strength. Greys on it fall short of the contrast text needs. */
internal val CycleOnViolet = Color.White

/** The same white, dimmed as far as 4.5:1 allows, for the lines that sit under the headline. */
internal val CycleOnVioletMuted = Color(0xFFF0F5FE)

/**
 * The window strip's track — the blue darkened a little rather than lightened. Lightened, it would
 * pull the white labels on it under the 4.5:1 text owes; darkened, it still reads as a track, and the
 * white on it and the white pill beside it both stand further off it than off the bare blue.
 */
internal val CycleVioletTrack = Color.Black.copy(alpha = 0.12f)

/** The disc behind today's date under the plot: the same darkening, a step deeper. */
internal val CycleTodayDisc = Color.Black.copy(alpha = 0.18f)

/** The day sticks behind the chart, and the dashed level line: the same white, fainter. */
internal val CycleGrid = Color.White.copy(alpha = 0.28f)

/** The days that bleed, recorded and forecast. A lighter blue keeps them distinct. */
internal val CyclePeach = Color(0xFFBBD0FB)

/** And the third curve — pain, or desire. */
internal val CycleMint = Color(0xFFDCE8FF)

/**
 * The fertility estimate's line: a pale lavender, so it is not the bleeding days' blue, and light
 * enough to stand 3:1 clear of the hero in both themes. It is dashed as well, so it is told from the
 * recorded lines by shape before colour.
 */
internal val CycleEstimate = Color(0xFFC9C0FA)

/** The sheet pulled up over the blue, and the radius of the two corners it turns. */
internal val CycleSheet: Color
    @Composable get() = MaterialTheme.colorScheme.surface

internal val CYCLE_SHEET_RADIUS = PwnCorners.extraLarge

/** The card inside the sheet, and the day cells inside that. */
internal val CycleCard: Color
    @Composable get() = darkOrCycle(CycleCardDarkRaw, CycleCardLightRaw)

internal val CycleCell: Color
    @Composable get() = darkOrCycle(CycleCellDarkRaw, CycleCellLightRaw)

/** The blue the pale blue carries, used for text on the cell. */
internal val CycleCellInk: Color
    @Composable get() = darkOrCycle(CycleCellInkDarkRaw, CycleCellInkLightRaw)

/**
 * The deep blue of the chosen day, the slider and the word above it — a clear step darker than the
 * hero so it reads as the thing being pointed at rather than as more background.
 */
internal val CycleAccent: Color
    @Composable get() = darkOrCycle(CycleAccentDarkRaw, CycleAccentLightRaw)

/** This file's own copy of the palette's light/dark switch, which is private to [PwnDesign]. */
@Composable
private fun darkOrCycle(dark: Color, light: Color): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) dark else light
