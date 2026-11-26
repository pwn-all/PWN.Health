package com.pwnall.health

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The downward pull that shuts a panel, for a page whose scrolling is its own business — a list, a
 * lazy grid, a form three composables deep.
 *
 * Deliberately not the pull a scroll gives you for free. A page that has been scrolled spends the
 * first swipe getting back to its top and stops there; only a second swipe, started from a page
 * already resting at its top — for half a second, not the instant a fling lands there — throws it
 * away. Neither the offset nor the resting is taken on
 * trust — [SwipeCloseGesture] has the rule and what proves it — and a page that never scrolls is a
 * page at its top, so the handle over a short page still works on the first pull.
 */
@Composable
internal fun Modifier.swipeDownToClose(
    onClose: () -> Unit,
    pull: PullProgress? = null,
    enabled: () -> Boolean = { true },
    canStartClose: () -> Boolean,
): Modifier {
    val close by rememberUpdatedState(onClose)
    val active by rememberUpdatedState(enabled)
    val atTop by rememberUpdatedState(canStartClose)
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val scope = rememberCoroutineScope()
    val gesture = remember { SwipeCloseGesture() }
    // The caller's holder where it handed one over, a private one where it did not: the modifier
    // works the same either way, and nothing downstream has to know which it got.
    val state = pull ?: remember { PullProgress() }
    var distance by state.distance
    var height by state.height
    var settling by remember { mutableStateOf(false) }
    // A flick back up is how a pull is taken back, and taking it back has to beat the distance
    // already travelled: a finger that went too far and threw the panel back at the page it came
    // from was not asking for it to close, however far down it got first.
    val cancelFlick = with(LocalDensity.current) { 300.dp.toPx() }
    suspend fun release(velocity: Float = 0f, cancelled: Boolean = false) {
        if (settling || distance <= 0f) return
        settling = true
        // A pull taken back: the finger left the page, or it threw the page back where it came
        // from. Either way the panel goes home however far down the gesture got first.
        val takenBack = cancelled || !active() || velocity <= -cancelFlick
        // Both ways out carry on at the speed the finger let go at, so a flick does not stall into
        // a curve that starts from rest. Each is held at the edge it is heading for: the panel's own
        // height on the way out, where the spatial spring's overshoot would only be spent off the
        // bottom of the window while the page behind waited, and the top on the way home, which a
        // spring thrown upwards would otherwise pass and lift the panel off its foot. Home is the
        // same resting edge the panel rose onto, so it settles there on the same unbouncing spring.
        val slide = Animatable(distance)
        try {
            if (!takenBack && gesture.canClose && distance >= threshold) {
                val gone = maxOf(distance, height / PULL_RESISTANCE)
                slide.updateBounds(upperBound = gone)
                slide.animateTo(gone, PwnMotion.spatial(), initialVelocity = velocity.coerceAtLeast(0f)) {
                    distance = value
                }
                if (active()) close() else distance = 0f
            } else {
                slide.updateBounds(lowerBound = 0f)
                slide.animateTo(0f, settleSpring(), initialVelocity = velocity.coerceAtMost(0f)) { distance = value }
            }
        } finally {
            settling = false
        }
    }
    val connection = remember(threshold) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!active()) return Offset.Zero
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (!gesture.canClose || settling || distance <= 0f) return Offset.Zero
                val before = distance
                distance = (distance + available.y).coerceAtLeast(0f)
                return Offset(0f, distance - before)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!active()) return Offset.Zero
                // Told before anything is taken, and told about the fling as well as the finger:
                // this is the page saying where it is standing, and a swipe that throws a list
                // back to its top does most of that travel after the finger has gone.
                gesture.contentMoved(consumed.y, SystemClock.uptimeMillis())
                val taken = source == NestedScrollSource.UserInput &&
                    gesture.canClose && !settling && available.y > 0f
                if (taken) distance += available.y
                return if (available.y > 0f) Offset(0f, available.y) else Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!active()) return Velocity.Zero
                if (distance <= 0f) return Velocity.Zero
                release(available.y)
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (active() && available.y > 0f) Velocity(0f, available.y) else Velocity.Zero
        }
    }
    return this.onSizeChanged { height = it.height.toFloat() }
        .nestedScroll(connection)
        .pointerInput(Unit) {
            // Observe before the child scrolls, without consuming its touch events. It marks where
            // one gesture ends and the next begins, and whether it began on the handle — which is
            // all the rule needs from the finger. Everything else it knows, the content tells it.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                gesture.begin(active() && atTop(), down.uptimeMillis)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(threshold) {
            // A handle or fixed header follows the same permission sampled at touch-down.
            // It cannot bypass a scrolled body, or pass a blocked pull to a parent panel.
            val flick = VelocityTracker()
            detectVerticalDragGestures(
                onDragStart = { flick.resetTracking() },
                onDragEnd = { scope.launch { release(flick.calculateVelocity().y) } },
                onDragCancel = { scope.launch { release(cancelled = true) } },
            ) { change, amount ->
                if (active() && !settling) {
                    flick.addPosition(change.uptimeMillis, change.position)
                    if (gesture.canClose) distance = (distance + amount).coerceAtLeast(0f)
                    change.consume()
                }
            }
        }
        .graphicsLayer { translationY = distance * PULL_RESISTANCE }
}
