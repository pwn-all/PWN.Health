package com.pwnall.health

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

// What a panel tells the chrome around it: that it is there at all, and how far it has been
// pulled. Both are read by composables standing ABOVE the panel in the tree — the bottom bar,
// the page behind it — which is why neither can travel as a CompositionLocal from inside it.

/**
 * How many panels are held open over the app.
 *
 * The bottom bar is composed above every panel in the tree, so a panel cannot tell it to go away
 * through a CompositionLocal — a local flows down, and the bar is up. It reads this counter
 * instead, which every [PwnSheetPage] raises for as long as it is composed. That covers the panels
 * the app screen knows about and, more to the point, the ones it does not: a profile section, a
 * medicine page, a meal step. They are all opened by a host inside a tab, so the tab is still the
 * screen the app thinks it is standing on, and without this counter the bar would stay up over all
 * of them.
 *
 * Null where nobody is counting — a preview, a test, a screenshot of one composable.
 */
internal class PanelCount {
    var open by mutableIntStateOf(0)
}

internal val LocalPanels = compositionLocalOf<PanelCount?> { null }

/**
 * How far a panel has been pulled down, for whatever is drawn behind it.
 *
 * Owned by the caller rather than kept inside the modifier, because the thing that wants to read it
 * — the backdrop under the panel — is a sibling, not a child. [fraction] is deliberately a computed
 * property over two float states: read inside a `graphicsLayer` or `drawWithContent` block it
 * re-applies on the next frame without recomposing anything, which is what keeps a drag at sixty
 * frames. Read it in composition and every frame of the gesture recomposes the page.
 */
internal class PullProgress {
    /** Pixels of finger travel, before resistance. The gesture delegates its own var to this. */
    val distance = mutableFloatStateOf(0f)

    /** The panel's own height, as laid out. */
    val height = mutableFloatStateOf(0f)

    /** 0 while the panel sits where it opened, 1 once it has travelled its own height. */
    val fraction: Float
        get() = height.floatValue.let {
            if (it > 0f) (distance.floatValue * PULL_RESISTANCE / it).coerceIn(0f, 1f) else 0f
        }
}

/** How much of the finger's travel the panel actually makes, so the pull has weight to it. */
internal const val PULL_RESISTANCE = 0.65f
