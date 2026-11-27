package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The cycle half of the boundaries: the exact day a prediction rolls over, the exact gap the
 * plausible range takes, and the pairing of starts with ends when somebody taps twice.
 */
class CycleAndMoodEdgeCasesTest {

    private fun entry(kind: String, day: String) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = kind,
        calories = 0.0,
    )

    private fun starts(vararg days: String) = days.map { entry(KIND_CYCLE, it) }

    private fun day(text: String) = LocalDate.parse(text)

    /**
     * Someone logs two periods, stops for eighteen months and opens the app again. The prediction
     * is the last recorded start plus the measured average and stays there: nothing rolls it
     * forward or blanks it once the day it names has gone by, because blanking it would erase the
     * date from the card the moment a period is one day late. What marks the record as stale is
     * [CycleSummary.dayOfCycle], capped at twice the average and so null here.
     */
    @Test
    fun aStalePredictionStaysAnchoredToTheLastStart() {
        val today = day("2026-09-06")

        val summary = cycleSummary(starts("2025-01-01", "2025-02-01"), today)

        assertEquals(31, summary.averageLengthDays)
        assertNull("nothing beside the date disputes it", summary.dayOfCycle)
        assertEquals(day("2025-03-04"), summary.nextExpected)
    }

    /**
     * Thirteen starts through 2024, nothing for two years — a pregnancy, a hormonal method, or the
     * app simply left alone — and then one new start. The cycles before the break are history: counted,
     * the new start would bring back a prediction, "regular" and a fertile window open that very
     * week, all drawn from cycles two years old. Nothing is said until a cycle is measured again.
     */
    @Test
    fun cyclesBeforeABreakInTheRecordPredictNothing() {
        val old = (0 until 13).map { day("2024-01-01").plusDays(28L * it).toString() }
        val journal = starts(*old.toTypedArray(), "2026-11-01")
        val today = day("2026-11-10")

        val summary = cycleSummary(journal, today)
        assertEquals(day("2026-11-01"), summary.lastStart)
        assertEquals(10, summary.dayOfCycle)
        assertEquals("the twelve cycles of 2024 are not counted", 0, summary.recordedCycles)
        assertNull(summary.nextExpected)
        assertNull(fertileWindow(summary))
        assertNull(CycleFacts(journal, summary, today).fertility())

        // The start after it closes one measured cycle, and the prediction is drawn from that alone.
        val next = cycleSummary(journal + starts("2026-12-01"), day("2026-12-05"))
        assertEquals(1, next.recordedCycles)
        assertEquals(30, next.averageLengthDays)
        assertEquals(day("2026-12-31"), next.nextExpected)
    }

    /**
     * [CYCLE_BREAK_DAYS] to the day. Eighty-nine days is still an entry or two that went missing:
     * the gap is dropped and the cycles before it go on being counted. Ninety is the break.
     */
    @Test
    fun aBreakInTheRecordBeginsAtNinetyDays() {
        val today = day("2026-07-01")

        val missed = cycleSummary(starts("2026-01-01", "2026-01-29", "2026-02-26", "2026-05-26"), today)
        assertEquals(2, missed.recordedCycles)
        assertEquals(day("2026-06-23"), missed.nextExpected)

        val broken = cycleSummary(starts("2026-01-01", "2026-01-29", "2026-02-26", "2026-05-27"), today)
        assertEquals(0, broken.recordedCycles)
        assertNull(broken.nextExpected)
    }

    /**
     * February is the only month where a wrong leap rule shows. A prediction built from months or
     * weeks rather than days lands on 1 March in 2028, a day the person has already bled through.
     */
    @Test
    fun theCalendarIsWalkedDayByDayAcrossALeapYear() {
        val leap = cycleSummary(starts("2028-01-04", "2028-02-01"), day("2028-02-10"))
        assertEquals(day("2028-02-29"), leap.nextExpected)
        assertEquals(10, leap.dayOfCycle)

        val ordinary = cycleSummary(starts("2027-01-04", "2027-02-01"), day("2027-02-10"))
        assertEquals(day("2027-03-01"), ordinary.nextExpected)
        assertEquals(10, ordinary.dayOfCycle)

        // Counted inclusively over the 27th, the 28th, the 29th and the 1st — four days, not three.
        assertEquals(4, cycleSummary(starts("2028-02-27"), day("2028-03-01")).dayOfCycle)
        assertEquals(3, cycleSummary(starts("2027-02-27"), day("2027-03-01")).dayOfCycle)
    }

    /** Both ends of [PLAUSIBLE_CYCLE_DAYS], and the first day outside each of them. */
    @Test
    fun theExactEdgesOfThePlausibleGapRange() {
        val today = day("2026-05-10")

        val shortest = cycleSummary(starts("2026-01-01", "2026-01-16"), today)
        assertEquals(1, shortest.recordedCycles)
        assertEquals(15, shortest.averageLengthDays)
        assertEquals(day("2026-01-31"), shortest.nextExpected)

        val longest = cycleSummary(starts("2026-01-01", "2026-03-02"), today)
        assertEquals(1, longest.recordedCycles)
        assertEquals(60, longest.averageLengthDays)
        assertEquals(day("2026-05-01"), longest.nextExpected)

        // 14 days is a double entry and 61 a missed one; a dropped gap leaves nothing to predict from.
        listOf("2026-01-15", "2026-03-03").forEach { outside ->
            val dropped = cycleSummary(starts("2026-01-01", outside), today)
            assertEquals("gap to $outside", 0, dropped.recordedCycles)
            assertEquals("gap to $outside", DEFAULT_CYCLE_DAYS, dropped.averageLengthDays)
            assertNull("gap to $outside", dropped.nextExpected)
        }
    }

    /** The only guard that keeps the home card from printing "day 400" of a cycle. */
    @Test
    fun theDayCounterStopsAtTwiceTheAverage() {
        val single = starts("2026-07-01")

        assertEquals(DEFAULT_CYCLE_DAYS, cycleSummary(single, day("2026-08-25")).averageLengthDays)
        assertEquals(56, cycleSummary(single, day("2026-08-25")).dayOfCycle)
        assertNull(cycleSummary(single, day("2026-08-26")).dayOfCycle)
        // The day the period started is day one, not day zero.
        assertEquals(1, cycleSummary(single, day("2026-07-01")).dayOfCycle)
    }

    /**
     * A period start tapped again two days later, then closed once. An end closes the most recent
     * start at or before it, so the period runs from the second tap; the earlier onset keeps
     * waiting for an end of its own and, having none, is never published to Health Connect.
     */
    @Test
    fun twoStartsBeforeOneEndCloseTheMostRecentOnset() {
        val periods = cyclePeriods(
            listOf(
                entry(KIND_CYCLE, "2026-06-01"),
                entry(KIND_CYCLE, "2026-06-03"),
                entry(KIND_CYCLE_END, "2026-06-08"),
            ),
        )

        assertEquals("periods $periods", 1, periods.size)
        assertEquals(CyclePeriod(day("2026-06-03"), day("2026-06-08")), periods.first())
    }

    /**
     * "Period ended" and "period started" tapped on the same day. An end never reaches back past a
     * later start, and a start on the end's own day counts as later, so the end closes the new
     * onset as a one-day period and the older start stays open. Letting the end reach back instead
     * would let an onset nobody ever closed swallow a much later end and publish a month-long
     * bleed to Health Connect.
     */
    @Test
    fun anEndOnTheDayOfTheNextStartClosesThatStart() {
        val periods = cyclePeriods(
            listOf(
                entry(KIND_CYCLE, "2026-06-01"),
                entry(KIND_CYCLE_END, "2026-06-06"),
                entry(KIND_CYCLE, "2026-06-06"),
            ),
        )

        assertEquals("periods $periods", 1, periods.size)
        assertEquals(CyclePeriod(day("2026-06-06"), day("2026-06-06")), periods.first())
    }

    /**
     * Backups and Health Connect imports both write the day column, and a re-import writes the same
     * day three times. An unparseable day must be skipped rather than take the home screen down,
     * and the repeats must not become a zero-day cycle in the average.
     */
    @Test
    fun unreadableAndRepeatedDayStringsLeaveOneUsablePeriod() {
        val rows = listOf("2026-02-30", "", "2026-1-5", "2026-06-01", "2026-06-01", "2026-06-01")
            .map { entry(KIND_CYCLE, it) } +
            listOf(entry(KIND_CYCLE_END, "2026-05-28"), entry(KIND_CYCLE_END, "2026-06-05"))

        val periods = cyclePeriods(rows)
        assertEquals("periods $periods", 1, periods.size)
        assertEquals(CyclePeriod(day("2026-06-01"), day("2026-06-05")), periods.first())

        val summary = cycleSummary(rows, day("2026-06-10"))
        assertEquals(day("2026-06-01"), summary.lastStart)
        assertEquals("the three repeats are one start", 0, summary.recordedCycles)
        assertNull(summary.nextExpected)
    }

    /**
     * [PERIOD_OPEN_DAYS] is eight, so the eighth day is the first one the app asks to close on.
     * Before that the period is simply running, which the card says without asking for anything.
     */
    @Test
    fun aPeriodIsOnlyOverdueOnTheEighthDay() {
        val today = day("2026-08-30")

        assertEquals(
            "seven days is still a period",
            CycleNudge(CycleNudgeKind.PERIOD_RUNNING, day("2026-08-23")),
            cycleNudge(starts("2026-08-23"), today),
        )
        assertEquals(
            CycleNudge(CycleNudgeKind.CLOSE_PERIOD, day("2026-08-22")),
            cycleNudge(starts("2026-08-22"), today),
        )
    }

    /** A cycle merely due is not late: the threshold is the average plus a whole week. */
    @Test
    fun aCycleIsOnlyLateOnceItIsAWholeWeekPast() {
        val today = day("2026-08-30")
        val late = listOf(entry(KIND_CYCLE, "2026-07-26"), entry(KIND_CYCLE_END, "2026-07-30"))
        val due = listOf(entry(KIND_CYCLE, "2026-07-27"), entry(KIND_CYCLE_END, "2026-07-31"))

        // One start only, so the average is the textbook 28 and the line is at 35 days.
        assertEquals(CycleNudge(CycleNudgeKind.LOG_START, day("2026-07-26")), cycleNudge(late, today))
        assertNull("34 days is due, not late", cycleNudge(due, today))
    }

    /**
     * A cycle that was never logged is asked about on the first open, however old or new the rest
     * of the journal is. Waiting for a month of use would make the date of the last period a guess,
     * and leave the screen empty the whole time.
     */
    @Test
    fun aCycleNeverLoggedIsAskedAboutWhateverTheJournalsAge() {
        val today = day("2026-08-30")

        listOf("2026-07-31", "2026-08-01", today.toString()).forEach { day ->
            assertEquals(
                "journal starting $day",
                CycleNudge(CycleNudgeKind.FIRST_CYCLE, null),
                cycleNudge(listOf(entry(KIND_WATER, day)), today),
            )
        }
    }

    /**
     * A mistyped year, or an import from a phone whose clock is ahead. The day count goes negative,
     * and the app must neither ask to close a period that has not begun nor publish a negative day.
     */
    @Test
    fun aStartDatedInTheFutureIsNeitherANudgeNorADayOfCycle() {
        val today = day("2026-08-30")
        val typo = starts("2026-09-20")

        val nudge = cycleNudge(typo, today)
        assertNull("nudge $nudge", nudge)
        assertNull(cycleSummary(typo, today).dayOfCycle)
    }

    /**
     * Sixteen days and fifty-nine days are both inside the plausible range — the spread somebody
     * coming off contraception actually records — so the mean lands exactly on a half day.
     */
    @Test
    fun aWideSpreadAveragesOnAHalfDayAndRoundsUp() {
        val summary = cycleSummary(starts("2026-01-01", "2026-01-17", "2026-03-17"), day("2026-03-20"))

        assertEquals(2, summary.recordedCycles)
        assertEquals("the mean of 16 and 59 is 37.5", 38, summary.averageLengthDays)
        assertEquals(day("2026-04-24"), summary.nextExpected)
        assertEquals(4, summary.dayOfCycle)
    }

    /**
     * Three cycles of thirty days with one start nobody logged: gaps of 30, 60 and 30, all inside the
     * plausible range. Their mean is forty, and as a mean the screen would predict ten days late,
     * the profile's card would call the cycle forty days, and the advice would send a woman whose
     * cycles are thirty to a clinician about long ones. The length is the middle one, in all three places.
     */
    @Test
    fun aStartNobodyLoggedDoesNotMoveTheLengthOfHerCycle() {
        val journal = starts("2026-03-01", "2026-03-31", "2026-05-30", "2026-06-29")
        val today = day("2026-07-05")

        val summary = cycleSummary(journal, today)
        assertEquals(3, summary.recordedCycles)
        assertEquals(30, summary.averageLengthDays)
        assertEquals(day("2026-07-29"), summary.nextExpected)
        assertEquals(30, personalBaseline(emptyList(), journal, today).usualCycleDays)
        assertNull(cycleAdvice(journal, ageYears = 30, today = today))
    }

    /**
     * The screen, the report and the advice card average over one window — FIGO's reference period
     * of the last twelve cycles — and so arrive at one number.
     *
     * Here twelve forty-day cycles follow one sixteen-day gap from over a year ago. Averaging every
     * plausible gap on file instead would put the report at 38 while the advice card printed 40 for
     * the same woman on the same day, and put the prediction on a date her last year of cycles does
     * not support. Both halves also agree on which gaps count as a cycle at all:
     * [PLAUSIBLE_CYCLE_DAYS].
     */
    @Test
    fun theSummaryAndTheAdviceAverageOverOneWindow() {
        val second = day("2026-01-17")
        val journal = (listOf(day("2026-01-01"), second) + (1..12).map { second.plusDays(40L * it) })
            .map { entry(KIND_CYCLE, it.toString()) }
        val today = day("2027-05-22")

        val advised = cycleAdvice(journal, ageYears = 30, today = today)
            ?.args?.filterIsInstance<Counted>()?.get(1)?.quantity ?: 0
        assertEquals("the advice card's own mean", 40, advised)

        val summary = cycleSummary(journal, today)
        assertEquals("and the screen's, over the same twelve", 40, summary.averageLengthDays)
        assertEquals(12, summary.recordedCycles)
    }

    /**
     * A log added to the enum and forgotten in [CycleSection] is invisible on the screen while
     * still being exported and imported — the silent-failure shape a new journal kind can take. Sex
     * is the one pair deliberately off this screen.
     */
    @Test
    fun everyCycleLogIsReachableFromTheScreenAndRoundTrips() {
        val sectioned = CycleSection.entries.flatMap { it.logs }.toSet()
        val offScreen = CycleLog.entries.filterNot { it in sectioned }.toSet()
        assertEquals(setOf(CycleLog.SEX_PROTECTED, CycleLog.SEX_UNPROTECTED), offScreen)

        CycleLog.entries.forEach { log ->
            assertEquals("$log does not round-trip", log, cycleLogOf(log.name))
            assertTrue("$log has no title", log.titleRes != 0)
        }
        val titles = CycleLog.entries.map { it.titleRes }
        assertEquals("two logs share one title", titles.size, titles.toSet().size)
    }
}
