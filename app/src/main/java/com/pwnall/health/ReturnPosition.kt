package com.pwnall.health

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Why a page came back from a panel at its top, and how it is put back where it was left.
 *
 * Every page below a panel leaves composition while the panel is open — see [BackdropCamera] — and
 * comes back from its saved state when the panel falls away. The scroll position comes back with
 * it, but nothing else does: the page's database flows start again from their empty first value,
 * so the first layout or two of the returning page is a page with no rows in it, a few hundred
 * pixels tall. A [ScrollState] cannot stand below the end of its content, and on that first short
 * layout it clamps itself to the foot of it — the top, near enough — for good. Then the rows
 * arrive and the page grows back under a position that is already lost. Left at that, the panel
 * would fall away over a picture of the page where it was left, and the live page would appear at
 * its top a frame later.
 *
 * [keepReturnPosition] remembers where the scroll stood before that first layout, and when the
 * first layout pins it to the foot of a page shorter than that, it waits for the page to grow and
 * puts it back — see [followReturn]. It gives up the moment anybody else moves the scroll — a
 * finger, or a page resetting itself on purpose — settles for the foot of a page that has stopped
 * growing ([RETURN_QUIET_MS]), and stops after [RETURN_PATIENCE_MS] whatever happens.
 *
 * The add tab has a jump of its own on top of this one: see the chooser's scroll in
 * [EntryEditorScreen].
 */
internal enum class ReturnScroll {
    /** The scroll stands where it was left, or further down: nothing was lost. */
    STANDING,

    /** Pinned to the foot of a page laid out shorter than the position it was left at. */
    WAITING,

    /** Pinned still, and the page has grown tall enough to stand where it was left again. */
    RESTORE,

    /** Somebody else moved it. Their position is the one that stands. */
    MOVED,
}

/**
 * Where a returning scroll stands, from where it was [left], the [pinned] value a short layout last
 * held it at (null before the first layout has been judged), its [value] and [max] now, and whether
 * anything is [moving] it now.
 *
 * A clamp leaves the value exactly at the foot of the content, so a value anywhere else that is
 * not the pin it already had is a move somebody made. A pin that drops further — the page shrank
 * again before it grew — is still a pin. The value alone cannot tell a clamp from a finger that
 * carried the page to its foot, though: a fling to the end of a half-loaded page lands exactly where
 * a clamp would. So a scroll that is being moved is a move, wherever it ends up — the clamp itself
 * runs inside layout and never moves the scroll that way.
 */
internal fun returnScroll(left: Int, pinned: Int?, value: Int, max: Int, moving: Boolean = false): ReturnScroll = when {
    moving -> ReturnScroll.MOVED
    value >= left -> ReturnScroll.STANDING
    pinned != null && value == pinned && max >= left -> ReturnScroll.RESTORE
    value == max || value == pinned -> ReturnScroll.WAITING
    else -> ReturnScroll.MOVED
}

/**
 * How long a returning page is given to load enough of itself to stand where it was left, and how
 * long the picture of it is held over it in the meantime. Room answers a warm query in a frame or
 * two; this is the ceiling for a cold one, short enough that a page that never grows back is
 * simply shown as it is.
 */
internal const val RETURN_PATIENCE_MS = 700L

/**
 * The scrolls on the page being returned to that are still waiting to stand where they were left.
 * The app holds its picture of that page over it until this is back at nought, so what is seen is
 * the page where it was left, then the same page live — never the short page in between.
 */
internal class ReturnHold {
    var waiting by mutableIntStateOf(0)
        private set

    fun take() {
        waiting++
    }

    fun release() {
        waiting--
    }
}

/**
 * Returns once every scroll on the page just returned to stands where it was left, or once
 * [RETURN_PATIENCE_MS] has run out. A frame goes by first, so that page has been composed and its
 * scrolls have taken their holds before the count is read.
 */
internal suspend fun ReturnHold.awaitSettled() {
    withFrameNanos { }
    awaitNoneWaiting(snapshotFlow { waiting })
}

/**
 * Returns once the count of [waiting] scrolls is back at nought, or after [patienceMs]: the veil
 * comes off when the last hold is let go, and never later than the patience, whoever forgot to.
 */
internal suspend fun awaitNoneWaiting(waiting: Flow<Int>, patienceMs: Long = RETURN_PATIENCE_MS) {
    withTimeoutOrNull(patienceMs) { waiting.first { it == 0 } }
}

/**
 * The picture of the page being returned to, over it: it takes the touches meant for the live page
 * underneath, which may not yet be where the picture shows it, and says nothing to a screen reader,
 * which reads the live page.
 */
@Composable
internal fun ReturnVeil(photo: ImageBitmap) {
    Box(
        Modifier.fillMaxSize()
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) { BackdropPhoto(photo) }
}

/** The app's [ReturnHold], where there is one; null in a preview or a test. */
internal val LocalReturnHold = staticCompositionLocalOf<ReturnHold?> { null }

/**
 * Puts [state] back where it stood when this page was composed, if the page's first layouts are too
 * short to hold it — see [ReturnScroll]. Applied by [panelVerticalScroll] to every page scroll.
 *
 * While the scroll is pinned short of where it was left the page is not drawn, so that a page coming
 * back from a page of its own — a history, a calendar, a summary — shows nothing for the frame or two
 * its rows take rather than its top and then a jump. The app's own panels cover the same frames with
 * a picture instead ([ReturnVeil]), and hold it for as long as this is waiting.
 */
@Composable
internal fun Modifier.keepReturnPosition(state: ScrollState): Modifier {
    // Read in composition, before any layout: this is the saved or the hoisted position, and the
    // first measure is what may clamp it.
    val left = remember(state) { state.value }
    var placed by remember(state) { mutableStateOf(false) }
    var settled by remember(state) { mutableStateOf(left == 0) }
    val hold = LocalReturnHold.current
    LaunchedEffect(state, left) {
        if (left == 0) return@LaunchedEffect
        hold?.take()
        try {
            // Nothing until the first placement: before it, the value is the saved one, unclamped.
            val frames = snapshotFlow {
                if (placed) ReturnFrame(state.value, state.maxValue, state.isScrollInProgress) else null
            }.filterNotNull()
            if (followReturn(left, frames)) state.scrollTo(left)
        } finally {
            settled = true
            hold?.release()
        }
    }
    // Placement comes after measurement, so by the time this box is placed the scroll inside it has
    // been told how tall its content is, and has clamped itself if it was going to. The draw reads
    // the clamp itself rather than waiting for the effect above to judge it, which comes a frame late.
    // A page being scrolled is always drawn: a finger that has carried it to its foot is looking at it.
    return onPlaced { placed = true }.drawWithContent {
        val pinnedShort = state.value < left && state.value == state.maxValue
        if (settled || state.isScrollInProgress || !pinnedShort) drawContent()
    }
}

/** One look at a returning scroll: where it stands, how far it could go, and whether it is being moved. */
internal data class ReturnFrame(val value: Int, val max: Int, val moving: Boolean = false)

/**
 * Follows a returning scroll through the [frames] it is laid out at, from the first placed one, and
 * says whether it is to be put back where it was [left].
 *
 * True once a pinned scroll's page has grown back under where it was left, and also once a pinned
 * page has gone [quietMs] without changing at all: it is as tall as it is going to be — rows were
 * deleted while it was away — and the nearest it can stand to where it was left is the foot it is
 * already on. False the moment anything else moves it, when nothing was lost, and when [patienceMs]
 * runs out with the page still changing short of the place.
 */
internal suspend fun followReturn(
    left: Int,
    frames: Flow<ReturnFrame>,
    quietMs: Long = RETURN_QUIET_MS,
    patienceMs: Long = RETURN_PATIENCE_MS,
): Boolean = withTimeoutOrNull(patienceMs) {
    val pin = ReturnPin(left)
    var seen = frames.first()
    var verdict = pin.see(seen)
    while (verdict == ReturnScroll.WAITING) {
        val next = withTimeoutOrNull(quietMs) { frames.first { it != seen } }
        verdict = if (next == null) ReturnScroll.RESTORE else pin.see(next)
        if (next != null) seen = next
    }
    verdict == ReturnScroll.RESTORE
} ?: false

/**
 * One returning scroll's [ReturnScroll], fed each value and content height it is seen at, keeping
 * the pin a short layout last left it on.
 */
internal class ReturnPin(private val left: Int) {
    private var pinned: Int? = null

    fun see(value: Int, max: Int, moving: Boolean = false): ReturnScroll =
        returnScroll(left, pinned, value, max, moving).also { if (it == ReturnScroll.WAITING) pinned = value }

    fun see(frame: ReturnFrame): ReturnScroll = see(frame.value, frame.max, frame.moving)
}

/**
 * How long a pinned page may go without growing before it is taken to be as tall as it will get:
 * rows deleted while the page was away leave it shorter than where it was left, and a page that
 * waited out the whole of [RETURN_PATIENCE_MS] for rows that are gone would sit undrawn for it.
 */
internal const val RETURN_QUIET_MS = 200L
