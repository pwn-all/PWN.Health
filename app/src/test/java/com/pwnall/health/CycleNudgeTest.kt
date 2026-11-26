package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * What the home screen says about the cycle, and — mostly — what it does not.
 *
 * The nudge sits under the day's leading number, which is the most expensive place on the screen
 * to put a sentence. It has to earn it: two states are worth interrupting for and every other day
 * is silent.
 */
class CycleNudgeTest {
    private val today = LocalDate.parse("2026-08-30")

    private fun entry(kind: String, day: String) = JournalEntry(
        day = day,
        occurredAt = LocalDate.parse(day).toEpochDay() * 86_400_000,
        kind = kind,
        title = kind,
        calories = 0.0,
    )

    @Test
    fun anOrdinaryDaySaysNothing() {
        val entries = listOf(
            entry(KIND_CYCLE, "2026-08-26"),
            entry(KIND_CYCLE_END, "2026-08-30"),
        )
        assertNull(cycleNudge(entries, today))
    }

    @Test
    fun aPeriodStillRunningAfterAWeekAsksToBeClosed() {
        val nudge = cycleNudge(listOf(entry(KIND_CYCLE, "2026-08-20")), today)
        assertEquals(CycleNudgeKind.CLOSE_PERIOD, nudge?.kind)
        assertEquals(LocalDate.parse("2026-08-20"), nudge?.since)
    }

    /**
     * A period started yesterday is not overdue — but it is running, and the card says so and
     * offers the two things there are to do about it rather than going quiet for a week.
     */
    @Test
    fun aPeriodStartedYesterdayIsRunningRatherThanOverdue() {
        val nudge = cycleNudge(listOf(entry(KIND_CYCLE, "2026-08-29")), today)
        assertEquals(CycleNudgeKind.PERIOD_RUNNING, nudge?.kind)
        assertEquals(LocalDate.parse("2026-08-29"), nudge?.since)
    }

    @Test
    fun aCycleLateByAWeekAsksForTheNextStart() {
        // Two starts 28 days apart set the average; the last one is then 42 days ago.
        val entries = listOf(
            entry(KIND_CYCLE, "2026-06-21"),
            entry(KIND_CYCLE_END, "2026-06-26"),
            entry(KIND_CYCLE, "2026-07-19"),
            entry(KIND_CYCLE_END, "2026-07-24"),
        )
        val nudge = cycleNudge(entries, today)
        assertEquals(CycleNudgeKind.LOG_START, nudge?.kind)
        assertEquals(LocalDate.parse("2026-07-19"), nudge?.since)
    }

    @Test
    fun aCycleMerelyDueSaysNothingYet() {
        val entries = listOf(
            entry(KIND_CYCLE, "2026-07-05"),
            entry(KIND_CYCLE_END, "2026-07-10"),
            entry(KIND_CYCLE, "2026-08-02"),
            entry(KIND_CYCLE_END, "2026-08-07"),
        )
        // 28 days on, plus a few: due, not late.
        assertNull(cycleNudge(entries, LocalDate.parse("2026-08-31")))
    }

    /**
     * The one nudge that does not wait. Until a first start is on record the cycle screen has
     * nothing to draw, nothing to predict and no day to name, and the day the app is installed is
     * the day the last start is still remembered rather than reconstructed.
     */
    @Test
    fun aBrandNewJournalIsAskedForItsFirstCycleStraightAway() {
        val nudge = cycleNudge(listOf(entry(KIND_WATER, "2026-08-28")), today)
        assertEquals(CycleNudgeKind.FIRST_CYCLE, nudge?.kind)
        assertNull("there is no day to count from yet", nudge?.since)
    }

    /** And an empty journal is asked too: there is no first day to wait a month from. */
    @Test
    fun anEmptyJournalIsAskedForItsFirstCycleToo() {
        assertEquals(CycleNudgeKind.FIRST_CYCLE, cycleNudge(emptyList(), today)?.kind)
    }

    /** One start on file and the ask is over: from there the nudges are about gaps, not about a start. */
    @Test
    fun aRecordedCycleEndsTheFirstAsk() {
        val entries = listOf(entry(KIND_CYCLE, "2026-08-26"), entry(KIND_CYCLE_END, "2026-08-30"))
        assertNull(cycleNudge(entries, today))
    }
}
