package com.pwnall.health

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Each panel owns its live scroll positions, including restored and programmatically moved ones. */
internal class PanelScrollState {
    private val scrolls = mutableSetOf<ScrollableState>()
    private val children = mutableSetOf<PanelScrollState>()

    // Keys of the pages standing in this panel that asked it not to close by a downward pull: a long
    // chat, which a pull on its list or its handle could otherwise close by accident.
    // Counted in state too, so the handle that says the panel can be pulled shut goes while one holds.
    private val holds = mutableSetOf<Any>()
    private var held by mutableIntStateOf(0)

    val hasOpenChild: Boolean get() = children.isNotEmpty()

    /** Whether some page in this panel holds it open: only its back arrow and the system back close it then. */
    val swipeCloseHeld: Boolean get() = held > 0

    /** Whether the downward pull may close this panel: no panel open over it, and no page holding it open. */
    val closesBySwipe: Boolean get() = !hasOpenChild && !swipeCloseHeld

    val canStartClose: Boolean
        get() = scrolls.none { it.canScrollBackward }

    fun register(state: ScrollableState) {
        scrolls.add(state)
    }

    fun unregister(state: ScrollableState) {
        scrolls.remove(state)
    }

    fun registerChild(panel: PanelScrollState) {
        children.add(panel)
    }

    fun unregisterChild(panel: PanelScrollState) {
        children.remove(panel)
    }

    /** [key] holds the panel open against the downward pull until it is [release]d. */
    fun hold(key: Any) {
        holds.add(key)
        held = holds.size
    }

    /** [key]'s hold let go; a key that holds nothing is let go of harmlessly. */
    fun release(key: Any) {
        holds.remove(key)
        held = holds.size
    }
}

internal val LocalPanelScrollState = staticCompositionLocalOf<PanelScrollState?> { null }

/** Keep the underlying panel inert until its child has finished leaving composition. */
@Composable
internal fun RegisterChildPanel(panel: PanelScrollState) {
    val parent = LocalPanelScrollState.current
    DisposableEffect(parent, panel) {
        parent?.registerChild(panel)
        onDispose { parent?.unregisterChild(panel) }
    }
}

/**
 * Holds the panel this page stands in open against the downward pull for as long as the page is
 * composed: the back arrow and the system back, predictive back included, are then the only ways out.
 * One hold per call; nothing where the page stands in no panel.
 */
@Composable
internal fun HoldPanelOpen() {
    val panel = LocalPanelScrollState.current
    val key = remember { Any() }
    DisposableEffect(panel, key) {
        panel?.hold(key)
        onDispose { panel?.release(key) }
    }
}

@Composable
internal fun RegisterPanelScroll(state: ScrollableState) {
    val panel = LocalPanelScrollState.current
    DisposableEffect(panel, state) {
        panel?.register(state)
        onDispose { panel?.unregister(state) }
    }
}

/**
 * The normal vertical scroll, also exposing its position to the containing panel's close gesture,
 * and coming back where it was left when its page returns from behind a panel before its rows have
 * — see [keepReturnPosition].
 */
@Composable
internal fun Modifier.panelVerticalScroll(state: ScrollState): Modifier {
    RegisterPanelScroll(state)
    return keepReturnPosition(state).verticalScroll(state)
}
