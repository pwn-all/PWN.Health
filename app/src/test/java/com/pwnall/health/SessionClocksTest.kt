package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The moves of a session's clocks — see [SessionClocks]: a set stopped hands over to its partner or
 * rests, the rest counts from "Complete set" and not from the answer to it, and a second Stop of the
 * same set changes nothing. Times are elapsedRealtime milliseconds, handed in.
 */
class SessionClocksTest {
    private val bench = ACTIVITY_CATALOG.first { it.code == "BENCH_PRESS" }
    private val curl = ACTIVITY_CATALOG.first { it.code == "BICEPS_CURL" }
    private val plank = ACTIVITY_CATALOG.first { it.code == "PLANK" }

    /** Started at one second, "Complete set" at 31 s — thirty seconds of bench, as the Stop button reckons them. */
    private val running = SessionClocks().started(0, from = 0, at = 1_000L, now = 1_000L).copy(runElapsed = 30)

    @Test
    fun aStartCountsFromWhatAHoldWasTypedAs() {
        val started = SessionClocks().started(2, from = 20, at = 5_000L, now = 8_000L)

        assertEquals(2, started.activeIndex)
        assertFalse(started.resting)
        assertEquals(5_000L, started.runStartedAt)
        assertEquals(23, started.runElapsed)
    }

    /** The rest begins at "Complete set": the five seconds spent answering are rest, not lost. */
    @Test
    fun theRestCountsFromCompleteSetNotFromTheAnswer() {
        val plan = listOf(PlannedSet(reps = "10", load = "60"), PlannedSet(reps = "10", load = "60"))

        val stop = running.stopped(plan, 0, confirmedAt = 31_000L, now = 36_000L, spec = bench, partner = null)!!

        assertTrue(stop.plan[0].completed)
        assertEquals(30, stop.plan[0].actualSeconds)
        assertFalse(stop.plan[1].completed)
        assertTrue(stop.clocks.resting)
        assertEquals(0, stop.clocks.activeIndex)
        assertEquals(31_000L, stop.clocks.restStartedAt)
        assertEquals(5, stop.clocks.restElapsed)
    }

    /** A hold is stopped without a question, so it ends at the Stop and its clock ran on from what was typed. */
    @Test
    fun aHoldStoppedAtOnceEndsAtTheStop() {
        val plan = listOf(PlannedSet(actualSeconds = 10))
        val holding = SessionClocks().started(0, from = 10, at = 1_000L, now = 1_000L)

        val stop = holding.stopped(plan, 0, confirmedAt = null, now = 21_000L, spec = plank, partner = null)!!

        assertEquals(30, stop.plan[0].actualSeconds)
        assertEquals(21_000L, stop.clocks.restStartedAt)
        assertEquals(0, stop.clocks.restElapsed)
    }

    /** No rest card between the halves: the curls run from the bench's Stop, the answer's seconds in them. */
    @Test
    fun theFirstHalfOfARoundHandsStraightOverToItsPartner() {
        val plan = listOf(PlannedSet(reps = "10", load = "60")).paired()

        val stop = running.stopped(plan, 0, confirmedAt = 31_000L, now = 36_000L, spec = bench, partner = curl)!!

        assertEquals(30, stop.plan[0].actualSeconds)
        assertFalse(stop.clocks.resting)
        assertEquals(1, stop.clocks.activeIndex)
        assertEquals(31_000L, stop.clocks.runStartedAt)
        assertEquals(5, stop.clocks.runElapsed)
    }

    /** A hold as the partner is its own clock: it starts at the answer, from the seconds typed into it. */
    @Test
    fun aHoldAsThePartnerStartsAtTheAnswer() {
        val plan = listOf(PlannedSet(reps = "10", load = "60"), PlannedSet(actualSeconds = 20, second = true))

        val stop = running.stopped(plan, 0, confirmedAt = 31_000L, now = 36_000L, spec = bench, partner = plank)!!

        assertEquals(1, stop.clocks.activeIndex)
        assertEquals(36_000L, stop.clocks.runStartedAt)
        assertEquals(20, stop.clocks.runElapsed)
    }

    /** The second of two quick touches finds the set already stopped and leaves the rest's clocks alone. */
    @Test
    fun aSecondStopOfTheSameSetChangesNothing() {
        val plan = listOf(PlannedSet(reps = "10", load = "60"))
        val stop = running.stopped(plan, 0, confirmedAt = 31_000L, now = 36_000L, spec = bench, partner = null)!!

        assertNull(stop.clocks.stopped(stop.plan, 0, confirmedAt = null, now = 37_000L, spec = bench, partner = null))
    }

    /** A rest card left all night is the counted ceiling when the answer finally comes. */
    @Test
    fun aRestAnsweredHoursLaterCountsNoMoreThanTheCeiling() {
        val plan = listOf(PlannedSet(reps = "10", load = "60"))
        val morning = 31_000L + 8 * 3_600_000L

        val stop = running.stopped(plan, 0, confirmedAt = 31_000L, now = morning, spec = bench, partner = null)!!

        assertEquals(MAX_COUNTED_REST_SECONDS, stop.clocks.restElapsed)
        assertEquals(31_000L, stop.clocks.restStartedAt)
    }
}
