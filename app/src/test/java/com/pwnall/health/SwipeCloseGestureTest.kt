package com.pwnall.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeCloseGestureTest {
    @Test
    fun returningToTopCannotEarnPermissionUntilTheNextTouch() {
        val gesture = SwipeCloseGesture()
        gesture.begin(atTop = false, now = 0)
        gesture.contentMoved(400f, now = 100)
        gesture.contentMoved(0f, now = 200)
        assertFalse(gesture.canClose)
        gesture.begin(atTop = true, now = 100 + PANEL_SETTLE_MS)
        assertTrue(gesture.canClose)
    }

    @Test
    fun aFastFlingThatReachesTopStillCannotDismiss() {
        val gesture = SwipeCloseGesture()
        gesture.begin(atTop = false, now = 0)
        gesture.contentMoved(20f, now = 10)
        gesture.contentMoved(1500f, now = 20)
        gesture.contentMoved(0f, now = 30)
        assertFalse(gesture.canClose)
    }

    /**
     * Flicking a long page back up: the throw that lands it at the top ends a moment before the next
     * throw touches down. That touch is at the top, and it is still the scrolling — it closes nothing
     * until the page has rested there.
     */
    @Test
    fun theNextThrowAfterAFlingLandsAtTheTopCannotDismiss() {
        val gesture = SwipeCloseGesture()
        gesture.begin(atTop = false, now = 0)
        gesture.contentMoved(900f, now = 300)
        gesture.begin(atTop = true, now = 300 + PANEL_SETTLE_MS - 1)
        assertFalse(gesture.canClose)
        // That touch was one more throw: the rest counts from it.
        gesture.begin(atTop = true, now = 300 + 2 * PANEL_SETTLE_MS - 1)
        assertTrue(gesture.canClose)
    }

    /** Throws still landing at a page already at its top keep it: only a pause lets a pull close it. */
    @Test
    fun aRunOfThrowsAtTheTopClosesNothingUntilItPauses() {
        val gesture = SwipeCloseGesture()
        gesture.begin(atTop = false, now = 0)
        gesture.contentMoved(900f, now = 300)
        generateSequence(380L) { it + 90 }.takeWhile { it < 2_000 }.forEach { at ->
            gesture.begin(atTop = true, now = at)
            assertFalse(gesture.canClose)
        }
        gesture.begin(atTop = true, now = 1_970 + PANEL_SETTLE_MS)
        assertTrue(gesture.canClose)
    }

    @Test
    fun aStationaryPageAtTopCanCloseOnTheFirstPull() {
        val gesture = SwipeCloseGesture()
        gesture.begin(atTop = true, now = 0)
        gesture.contentMoved(0f, now = 10)
        assertTrue(gesture.canClose)
    }

    @Test
    fun aHandleCannotBypassThePositionSampledAtTouchDown() {
        val gesture = SwipeCloseGesture()
        gesture.begin(atTop = false, now = 0)
        assertFalse(gesture.canClose)
        gesture.contentMoved(0f, now = 10)
        assertFalse(gesture.canClose)
    }

    @Test
    fun reversingDirectionOrAnOldFlingMovingContentSpendsTheGesture() {
        val gesture = SwipeCloseGesture()
        gesture.begin(atTop = true, now = 0)
        gesture.contentMoved(-1f, now = 10)
        gesture.contentMoved(1f, now = 20)
        assertFalse(gesture.canClose)
        gesture.begin(atTop = true, now = 20 + PANEL_SETTLE_MS)
        gesture.contentMoved(1f, now = 30 + PANEL_SETTLE_MS)
        assertFalse(gesture.canClose)
    }

    @Test
    fun restoredOrProgrammaticallyScrolledContentIsSampledAfresh() {
        val gesture = SwipeCloseGesture()
        gesture.begin(atTop = true, now = 0)
        assertTrue(gesture.canClose)
        gesture.begin(atTop = false, now = 10)
        assertFalse(gesture.canClose)
        gesture.begin(atTop = true, now = 20)
        assertTrue(gesture.canClose)
    }
}
