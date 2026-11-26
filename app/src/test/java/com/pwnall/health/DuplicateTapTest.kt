package com.pwnall.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateTapTest {
    private val tapped = 1_800_000_000_000L

    @Test
    fun theFirstTapOfTheDayIsNeverADuplicate() {
        assertFalse(isDuplicateTap(null, tapped))
    }

    @Test
    fun aBouncedTouchIsSwallowed() {
        assertTrue(isDuplicateTap(tapped, tapped))
        assertTrue(isDuplicateTap(tapped, tapped + 150))
        assertTrue(isDuplicateTap(tapped, tapped + DUPLICATE_TAP_WINDOW_MS - 1))
    }

    @Test
    fun aSecondGlassAfterTheWindowCounts() {
        assertFalse(isDuplicateTap(tapped, tapped + DUPLICATE_TAP_WINDOW_MS))
        assertFalse(isDuplicateTap(tapped, tapped + 60_000))
    }

    /** A clock correction must not turn the next tap into a new entry. */
    @Test
    fun aClockThatWentBackwardsStillSwallowsTheRepeat() {
        assertTrue(isDuplicateTap(tapped, tapped - 500))
    }

    /** …but a big backwards jump must not make the app unloggable until the clock catches up. */
    @Test
    fun aLargeBackwardsJumpDoesNotSwallowEverything() {
        assertFalse(isDuplicateTap(tapped, tapped - 60 * 60_000))
    }

    @Test
    fun theWindowCanBeTightenedForATestOrATuning() {
        assertFalse(isDuplicateTap(tapped, tapped + 200, windowMs = 100))
        assertTrue(isDuplicateTap(tapped, tapped + 50, windowMs = 100))
    }
}
