package com.pwnall.health

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Local M3 baseline, independent of library default changes. */
internal object PwnDimensions {
    val minimumTarget = 48.dp
    val mediumButtonHeight = 56.dp
    val mediumButtonIcon = 24.dp
    val mediumButtonPadding = 24.dp
    val buttonGap = 8.dp
    val cardPadding = 16.dp

    /** Material's small top app bar: the bar is this tall however short its title is. */
    val topBarHeight = 64.dp

    /**
     * Widest a page's own wide button is drawn. On a phone the button takes most of the column; in
     * a wider pane a pill running the whole of it reads as a banner rather than something to press.
     */
    val maxButtonWidth = 480.dp

    /** A thing's picture beside its name: a drawing of the set, or a line icon in its tinted disc. */
    val glyphSize = 54.dp

    /** The line icon inside a [glyphSize] disc. */
    val glyphIconSize = 29.dp

    /**
     * Widest a page or a panel is drawn. Material's bottom-sheet maximum, used as the single-pane
     * width too: in a medium or expanded window the page stands centred at this width rather than
     * stretching rows and cards across the whole window.
     */
    val maxPaneWidth = 640.dp
}

/**
 * The gaps between things and the insets inside them. A new gap takes one of these; a card's own
 * inset is [PwnDimensions.cardPadding].
 */
internal object PwnSpacing {
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val extraLarge = 24.dp
}

internal object PwnCorners {
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val largeIncreased = 20.dp
    val extraLarge = 28.dp
    val extraLargeIncreased = 32.dp
    val extraExtraLarge = 48.dp
}

internal object PwnElevation {
    val level0 = 0.dp
    val level1 = 1.dp
    val level2 = 3.dp
    val level3 = 6.dp
    val level4 = 8.dp
    val level5 = 12.dp
}

/** Spatial springs can overshoot; opacity/effects settle without overshoot. */
internal object PwnMotion {
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    /** Small, quick movements the finger is waiting on, such as a pressed button's corners. */
    fun <T> fastSpatial(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
}

internal val PwnShapes = Shapes(
    extraSmall = RoundedCornerShape(PwnCorners.extraSmall),
    small = RoundedCornerShape(PwnCorners.small),
    medium = RoundedCornerShape(PwnCorners.medium),
    large = RoundedCornerShape(PwnCorners.large),
    extraLarge = RoundedCornerShape(PwnCorners.extraLarge),
)

private fun typeRole(size: Int, line: Int, tracking: Float = 0f, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp, fontWeight = weight)

/**
 * Large, short text — titles, headlines, the labels of segments and buttons — is where a single
 * word outgrows its line at a large font scale, and without hyphenation Android then cuts it at
 * whatever letter reaches the edge ("Расписан" / "ие"). Hyphenated, it breaks where the language
 * allows and says so. Body text keeps the platform default.
 */
private fun TextStyle.hyphenated() = copy(hyphens = Hyphens.Auto)

internal val PwnTypography = Typography(
    displayLarge = typeRole(57, 64, -0.2f).hyphenated(),
    displayMedium = typeRole(45, 52).hyphenated(),
    displaySmall = typeRole(36, 44).hyphenated(),
    headlineLarge = typeRole(32, 40).hyphenated(),
    headlineMedium = typeRole(28, 36).hyphenated(),
    headlineSmall = typeRole(24, 32).hyphenated(),
    titleLarge = typeRole(22, 28).hyphenated(),
    titleMedium = typeRole(16, 24, 0.2f, FontWeight.Medium).hyphenated(),
    titleSmall = typeRole(14, 20, 0.1f, FontWeight.Medium).hyphenated(),
    bodyLarge = typeRole(16, 24, 0.5f),
    bodyMedium = typeRole(14, 20, 0.2f),
    bodySmall = typeRole(12, 16, 0.4f),
    labelLarge = typeRole(14, 20, 0.1f, FontWeight.Medium).hyphenated(),
    labelMedium = typeRole(12, 16, 0.5f, FontWeight.Medium),
    labelSmall = typeRole(11, 16, 0.5f, FontWeight.Medium),
)
