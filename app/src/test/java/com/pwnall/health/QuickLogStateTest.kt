package com.pwnall.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The card between tapping "one beer" and believing it was recorded, shared by the alcohol, tobacco
 * and bathroom pages: the button that was pressed is shut for the window and no other, a write that
 * throws says so, and a tap the data layer swallowed as a repeat shows nothing. Time is the test
 * scheduler's: a `delay` in the body moves it, and the writes run on it.
 */
class QuickLogStateTest {
    private val notSaved = "Not saved"

    @Test
    fun theButtonThatWasPressedIsShutForTheWindowAndNoOther() = runTest {
        val quickLog = QuickLogState(notSaved)
        var writes = 0

        quickLog.log(this, "beer", "Beer · added") { writes++ }
        assertTrue(quickLog.locked("beer"))
        assertFalse(quickLog.locked("wine"))
        delay(1)
        assertEquals("Beer · added", quickLog.message)

        // A second tap on the shut button is not queued: nothing is written for it, then or later.
        quickLog.log(this, "beer", "Beer · added") { writes++ }
        delay(DUPLICATE_TAP_WINDOW_MS - 2)
        assertTrue(quickLog.locked("beer"))
        delay(2)
        assertFalse(quickLog.locked("beer"))
        assertNull(quickLog.message)
        assertEquals(1, writes)
    }

    /** A disk that is full says so where the eye is, and the button opens at once to be tried again. */
    @Test
    fun aWriteThatThrowsPutsUpTheFailureAndOpensTheButton() = runTest {
        val quickLog = QuickLogState(notSaved)

        quickLog.log(this, "pee", "Added") { throw IOException("disk full") }
        delay(1)

        assertEquals(notSaved, quickLog.failure)
        assertNull(quickLog.message)
        assertFalse(quickLog.locked("pee"))

        // The next tap takes the failure card down as it starts.
        quickLog.log(this, "pee", "Added") { true }
        assertNull(quickLog.failure)
    }

    /** A tab tapped straight after the beer takes the page away, and not the beer with it. */
    @Test
    fun aWriteBegunIsSeenThroughWhenThePageGoesUnderIt() = runTest {
        val quickLog = QuickLogState(notSaved)
        val page = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        var written = false

        quickLog.log(page, "beer", "Beer · added") {
            delay(10)
            written = true
        }
        delay(1)
        page.cancel()
        delay(20)

        assertTrue(written)
    }

    /** The wine tapped a second after the beer: the beer's window closing takes down only its own tick. */
    @Test
    fun theFirstWindowClosingLeavesTheSecondTapsTickStanding() = runTest {
        val quickLog = QuickLogState(notSaved)

        quickLog.log(this, "beer", "Beer · added") { true }
        delay(1_000)
        quickLog.log(this, "wine", "Wine · added") { true }
        delay(1)
        assertEquals("Wine · added", quickLog.message)

        delay(DUPLICATE_TAP_WINDOW_MS - 1_000)
        assertFalse(quickLog.locked("beer"))
        assertTrue(quickLog.locked("wine"))
        assertEquals("Wine · added", quickLog.message)

        delay(1_000)
        assertFalse(quickLog.locked("wine"))
        assertNull(quickLog.message)
    }

    /**
     * A cancellation the write throws travels on rather than being reported as a disk that failed,
     * and it does not leave the button shut for as long as the page stands.
     */
    @Test
    fun aCancellationFromTheWriteIsNoFailureAndOpensTheButton() = runTest {
        val quickLog = QuickLogState(notSaved)

        quickLog.log(this, "pee", "Added") { throw CancellationException("timed out") }
        delay(1)

        assertNull(quickLog.failure)
        assertNull(quickLog.message)
        assertFalse(quickLog.locked("pee"))
    }

    /**
     * The wine tapped a second after the beer, and swallowed by the data layer as a repeat: the beer's
     * tick stays up for its whole window. Counted at the tap, the swallowed repeat would take it down at once.
     */
    @Test
    fun anotherButtonsSwallowedRepeatLeavesTheTickStanding() = runTest {
        val quickLog = QuickLogState(notSaved)

        quickLog.log(this, "beer", "Beer · added") { true }
        delay(1_000)
        quickLog.log(this, "wine", "Wine · added") { false }
        delay(1)
        assertEquals("Beer · added", quickLog.message)
        assertFalse(quickLog.locked("wine"))

        delay(DUPLICATE_TAP_WINDOW_MS - 1_002)
        assertEquals("Beer · added", quickLog.message)
        delay(2)
        assertNull(quickLog.message)
    }

    /** A repeat the data layer swallowed was already confirmed by the first tap: no second tick. */
    @Test
    fun aTapTheDataLayerSwallowedShowsNothingAndHoldsNothingShut() = runTest {
        val quickLog = QuickLogState(notSaved)

        quickLog.log(this, "cigarette", "Cigarette · added") { false }
        delay(1)

        assertNull(quickLog.message)
        assertNull(quickLog.failure)
        assertFalse(quickLog.locked("cigarette"))
    }
}
