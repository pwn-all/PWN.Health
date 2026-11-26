package com.pwnall.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The judgement a page's scroll makes when the page comes back from behind a panel: whether the
 * first short layouts lost its position, whether the page has grown back under it, and whether
 * somebody else has moved it since — see [ReturnScroll].
 */
// The virtual clock is read to say when each wait ended, which is what the timeouts are about.
@OptIn(ExperimentalCoroutinesApi::class)
class ReturnPositionTest {
    /** Laid out at least as tall as before, the scroll is where it was left and nothing is done. */
    @Test
    fun aPageTallEnoughOnItsFirstLayoutKeepsItsPlace() {
        assertEquals(ReturnScroll.STANDING, returnScroll(left = 900, pinned = null, value = 900, max = 2_400))
    }

    /** The rows still loading: the scroll is clamped to the foot of a short page, so it waits. */
    @Test
    fun aClampToTheFootOfAShortPageWaits() {
        assertEquals(ReturnScroll.WAITING, returnScroll(left = 900, pinned = null, value = 120, max = 120))
        // A page that fits the window entirely clamps to nought, which is still a clamp.
        assertEquals(ReturnScroll.WAITING, returnScroll(left = 900, pinned = null, value = 0, max = 0))
    }

    /** A position short of where it was left but not at the foot is somebody's choice, not a clamp. */
    @Test
    fun aResetOnTheFirstLayoutIsLeftAlone() {
        assertEquals(ReturnScroll.MOVED, returnScroll(left = 900, pinned = null, value = 0, max = 2_400))
    }

    /** Once the rows arrive the page is tall enough again, and the pinned scroll is put back. */
    @Test
    fun aPinnedScrollIsPutBackWhenThePageGrows() {
        assertEquals(ReturnScroll.RESTORE, returnScroll(left = 900, pinned = 120, value = 120, max = 2_400))
        // Grown, but not yet far enough: one flow of several has answered.
        assertEquals(ReturnScroll.WAITING, returnScroll(left = 900, pinned = 120, value = 120, max = 600))
    }

    /** A finger on the page while it waits: its position stands, even when the page grows later. */
    @Test
    fun aScrollMovedWhileWaitingIsNotTakenBack() {
        assertEquals(ReturnScroll.MOVED, returnScroll(left = 900, pinned = 120, value = 40, max = 600))
    }

    /** The page shrank again before it grew — one card gone — and the new foot is still a pin. */
    @Test
    fun aPinThatDropsFurtherIsStillAPin() {
        assertEquals(ReturnScroll.WAITING, returnScroll(left = 900, pinned = 300, value = 120, max = 120))
    }

    /** The whole of a return, frame by frame: empty, half loaded, loaded. */
    @Test
    fun aReturnIsFollowedFromTheClampToTheRestore() {
        val pin = ReturnPin(left = 900)
        assertEquals(ReturnScroll.WAITING, pin.see(value = 80, max = 80))
        assertEquals(ReturnScroll.WAITING, pin.see(value = 80, max = 500))
        assertEquals(ReturnScroll.RESTORE, pin.see(value = 80, max = 2_400))
    }

    /** The pin a pin moved to is the one that is held: a smaller foot, then a page that grows. */
    @Test
    fun theLatestPinIsTheOneHeld() {
        val pin = ReturnPin(left = 900)
        assertEquals(ReturnScroll.WAITING, pin.see(value = 300, max = 300))
        assertEquals(ReturnScroll.WAITING, pin.see(value = 100, max = 100))
        assertEquals(ReturnScroll.RESTORE, pin.see(value = 100, max = 1_200))
    }

    /**
     * A fling that lands on the foot of a half-loaded page stands exactly where a clamp would. It is
     * still a move: the page is drawn under the finger, and the rows arriving later do not throw it
     * back to where it was left.
     */
    @Test
    fun aFingerThatCarriesThePageToItsFootIsAMove() {
        assertEquals(
            ReturnScroll.MOVED,
            returnScroll(left = 2_000, pinned = 600, value = 600, max = 600, moving = true),
        )
    }

    /** The same, followed in time: the move is taken the frame it is seen, not when the rows come. */
    @Test
    fun aScrollMovedToTheFootWhileWaitingIsNotPutBack() = runTest {
        val frames = MutableStateFlow(ReturnFrame(value = 600, max = 600))
        launch {
            delay(50)
            frames.value = ReturnFrame(value = 600, max = 600, moving = true)
            delay(50)
            frames.value = ReturnFrame(value = 600, max = 2_400)
        }
        assertFalse(followReturn(left = 2_000, frames = frames))
        assertEquals(50L, currentTime)
    }

    /** The rows arrive within the patience: the scroll is put back as soon as the page is tall enough. */
    @Test
    fun aPageThatGrowsBackIsReturnedToAtOnce() = runTest {
        val frames = MutableStateFlow(ReturnFrame(value = 80, max = 80))
        launch {
            delay(30)
            frames.value = ReturnFrame(value = 80, max = 500)
            delay(30)
            frames.value = ReturnFrame(value = 80, max = 2_400)
        }
        assertTrue(followReturn(left = 900, frames = frames))
        assertEquals(60L, currentTime)
    }

    /**
     * Rows deleted while the page was away: it never grows back, and after the quiet period it is
     * settled at its foot rather than left undrawn for the whole of the patience.
     */
    @Test
    fun aPageThatStopsGrowingSettlesAtItsFootAfterTheQuiet() = runTest {
        val frames = MutableStateFlow(ReturnFrame(value = 600, max = 600))
        assertTrue(followReturn(left = 2_000, frames = frames))
        assertEquals(RETURN_QUIET_MS, currentTime)
    }

    /** A page that keeps changing but never reaches the place is given up on at the patience limit. */
    @Test
    fun aPageThatNeverGrowsFarEnoughIsGivenUpOn() = runTest {
        val frames = MutableStateFlow(ReturnFrame(value = 100, max = 100))
        val ticker = launch {
            var height = 100
            while (true) {
                delay(RETURN_QUIET_MS / 2)
                height += 50
                frames.value = ReturnFrame(value = height, max = height)
            }
        }
        assertFalse(followReturn(left = 5_000, frames = frames))
        assertEquals(RETURN_PATIENCE_MS, currentTime)
        ticker.cancel()
    }

    /** The veil waits for every hold to be let go, and comes off the moment the last one is. */
    @Test
    fun theVeilLiftsWhenTheLastHoldIsReleased() = runTest {
        val waiting = MutableStateFlow(2)
        launch {
            delay(100)
            waiting.value = 1
            delay(100)
            waiting.value = 0
        }
        awaitNoneWaiting(waiting)
        assertEquals(200L, currentTime)
    }

    /** A hold nobody releases keeps the veil up no longer than the patience. */
    @Test
    fun theVeilLiftsAtThePatienceWhateverIsStillHeld() = runTest {
        awaitNoneWaiting(MutableStateFlow(1))
        assertEquals(RETURN_PATIENCE_MS, currentTime)
    }
}
