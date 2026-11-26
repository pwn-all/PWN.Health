package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The readings of her record the line under the cycle screen's week is chosen from, and — more to
 * the point — the ones it refuses to give.
 *
 * Every card is a sentence about a woman's body with figures under it. A card that appears without
 * the record to support it is the failure this class exists to catch; the day it says "you tend to
 * feel better in the follicular phase" off four mood taps is the day the screen stops being worth
 * reading.
 */
class CycleInsightsTest {

    private fun entry(kind: String, day: String) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = kind,
        calories = 0.0,
    )

    private fun mood(day: String, score: Int) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = KIND_MOOD,
        title = "mood",
        calories = 0.0,
        moodScore = score,
    )

    /** A night is a measurement row: that is the kind every writer of one gives it. */
    private fun sleep(day: String, minutes: Long) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = "MEASUREMENT",
        title = "sleep",
        calories = 0.0,
        sleepStartAt = 0L,
        sleepEndAt = minutes * 60_000L,
    )

    private fun day(text: String) = LocalDate.parse(text)

    /** Twelve 28-day cycles from 5 January, each a five-day period. */
    private fun regular(periodDays: Long = 4) = (0 until 12).flatMap { index ->
        val start = day("2026-01-05").plusDays(28L * index)
        listOf(
            entry(KIND_CYCLE, start.toString()),
            entry(KIND_CYCLE_END, start.plusDays(periodDays).toString()),
        )
    }

    private inline fun <reified T : CycleInsight> List<CycleInsight>.of(): T? =
        firstOrNull { it is T } as? T

    /** A record with nothing in it says nothing at all rather than saying something generic. */
    @Test
    fun anEmptyRecordProducesNoInsights() {
        val today = day("2026-09-01")
        assertEquals(emptyList<CycleInsight>(), cycleInsights(emptyList(), cycleSummary(emptyList(), today), today))
    }

    /** Regular and irregular are the same card with the word changed, and both print the span. */
    @Test
    fun theLengthCardCarriesTheSpanAndWhetherItCountsAsRegular() {
        val today = day("2026-11-20")
        val steady = cycleInsights(regular(), cycleSummary(regular(), today), today).of<CycleInsight.Length>()
        assertEquals(true, steady?.regular)
        assertEquals(28, steady?.shortest)
        assertEquals(28, steady?.longest)
        assertEquals(11, steady?.cycles)

        // Four cycles running 24 to 36 days: a twelve-day spread, well past FIGO's seven.
        val jumpy = listOf("2026-04-01", "2026-04-25", "2026-05-30", "2026-06-23", "2026-07-29")
            .map { entry(KIND_CYCLE, it) }
        val card = cycleInsights(jumpy, cycleSummary(jumpy, day("2026-08-10")), day("2026-08-10"))
            .of<CycleInsight.Length>()
        assertEquals(false, card?.regular)
        assertEquals(24, card?.shortest)
        assertEquals(36, card?.longest)
    }

    /**
     * One or two cycles measure neither word. [CycleSummary.regular] answers false for them because
     * nothing has been measured yet, and a card built on that would tell a woman two months into the
     * app, with two cycles of exactly twenty-eight days, that her cycle length varies. Withheld,
     * the line under the week falls through to the date the next period is expected on.
     */
    @Test
    fun theLengthCardWaitsForThreeCycles() {
        fun periods(count: Int) = (0 until count).flatMap { index ->
            val start = day("2026-09-01").plusDays(28L * index)
            listOf(entry(KIND_CYCLE, start.toString()), entry(KIND_CYCLE_END, start.plusDays(4).toString()))
        }

        val early = day("2026-11-10")
        val two = cycleInsights(periods(3), cycleSummary(periods(3), early), early)
        assertEquals(2, cycleSummary(periods(3), early).recordedCycles)
        assertNull("two cycles are not a measurement", two.of<CycleInsight.Length>())
        assertEquals(day("2026-11-24"), cycleSummary(periods(3), early).nextExpected)

        val later = day("2026-12-05")
        val three = cycleInsights(periods(4), cycleSummary(periods(4), later), later)
        assertEquals(
            CycleInsight.Length(cycles = 3, shortest = 28, longest = 28, regular = true),
            three.of<CycleInsight.Length>(),
        )
    }

    /**
     * The screen asks the journal for [CYCLE_SCREEN_KINDS] and nothing else, and that must cost it
     * nothing it says: from those rows every reading comes out as it does from a journal with the
     * meals and the glasses of water left in — the two patterns included, which are read by column
     * and not by kind.
     */
    @Test
    fun theKindsTheScreenReadsCarryEverythingItSays() {
        val today = day("2026-11-20")
        val bleeding = recordedBleedingDays(regular())
        val nights = (0 until 120).map { day("2026-07-20").plusDays(it.toLong()) }
            .map { sleep(it.toString(), if (it in bleeding) 360L else 480L) }
        val moods = (9..11).flatMap { cycle ->
            val start = day("2026-01-05").plusDays(28L * cycle)
            (0 until 28).map { offset ->
                mood(start.plusDays(offset.toLong()).toString(), if (offset < 14) 5 else 2)
            }
        }
        val others = listOf(entry("EAT", "2026-11-19"), entry(KIND_WATER, "2026-11-19"))
        val journal = regular() + nights + moods + entry(KIND_MASTURBATION, "2026-11-12") + others

        val read = journal.filter { it.kind in CYCLE_SCREEN_KINDS }
        assertEquals("only the meal and the glass are left out", journal.dropLast(others.size), read)

        val said = cycleInsights(read, cycleSummary(read, today), today)
        assertEquals(cycleInsights(journal, cycleSummary(journal, today), today), said)
        assertNotNull(said.of<CycleInsight.MoodPattern>())
        assertNotNull(said.of<CycleInsight.SleepPattern>())
    }

    /**
     * Mood is split at the estimated ovulation rather than at the midpoint, so the halves mean what
     * a clinician means by them — and it is withheld from an irregular record, which has no
     * reliable place to split.
     */
    @Test
    fun theMoodCardNeedsBothHalvesFilledAndARegularRecord() {
        val today = day("2026-11-20")
        // Follicular days of the last three cycles read 5; luteal days read 2.
        val moods = (9..11).flatMap { cycle ->
            val start = day("2026-01-05").plusDays(28L * cycle)
            (0 until 28).map { offset ->
                mood(start.plusDays(offset.toLong()).toString(), if (offset < 14) 5 else 2)
            }
        }
        val journal = regular() + moods
        val card = cycleInsights(journal, cycleSummary(journal, today), today).of<CycleInsight.MoodPattern>()

        assertTrue("the first half read better", (card?.difference ?: 0.0) > 0)
        assertTrue("every compared day is counted", (card?.days ?: 0) >= 2 * CYCLE_PATTERN_MIN_DAYS)
        assertEquals(
            "and it is the reading the line under the week prints, ahead of \"your cycle is regular\"",
            card,
            patternInsight(cycleInsights(journal, cycleSummary(journal, today), today)),
        )

        // The same taps against an irregular record say nothing: there is no midpoint to split on.
        val jumpy = listOf("2026-08-01", "2026-08-25", "2026-09-30", "2026-10-24", "2026-11-29")
            .map { entry(KIND_CYCLE, it) }
        assertNull(moodPattern(jumpy + moods, cycleSummary(jumpy + moods, today), today))
    }

    /**
     * The line under the week has room for one reading. The two patterns lead, and the length of her
     * cycles is what is said where there is neither: there is a length whenever the mood pattern can
     * exist at all, so with the length first that pattern would be worked out on every change to the
     * journal and never printed.
     */
    @Test
    fun theLineUnderTheWeekLeadsWithThePatternsAndFallsBackOnTheLength() {
        val period = CycleInsight.PeriodLength(median = 5, periods = 11, longest = 6)
        val length = CycleInsight.Length(cycles = 11, shortest = 28, longest = 28, regular = true)
        val mood = CycleInsight.MoodPattern(difference = 0.8, days = 40)
        val sleep = CycleInsight.SleepPattern(periodMinutes = 400, otherMinutes = 440, nights = 60)

        assertEquals(mood, patternInsight(listOf(period, length, mood, sleep)))
        assertEquals(sleep, patternInsight(listOf(period, length, sleep)))
        assertEquals(length, patternInsight(listOf(period, length)))
        assertNull("the period's length is said during a period, not here", patternInsight(listOf(period)))
    }

    /**
     * The ovulation estimate holds that line until its day has gone by and no longer. Printed on
     * every day of a regular cycle it would leave her record's own readings no day to be printed on.
     */
    @Test
    fun theOvulationEstimateLeadsOnlyUntilItsDayHasGoneBy() {
        fun upcoming(today: String) = upcomingOvulation(cycleSummary(regular(), day(today)), day(today))

        // 23 November: fourteen days before the start the average predicts for 7 December.
        assertEquals(day("2026-11-23"), upcoming("2026-11-10"))
        assertEquals(day("2026-11-23"), upcoming("2026-11-23"))
        assertNull("the day after, the line is her record's", upcoming("2026-11-24"))
        assertNull("and a prediction overtaken by events estimates nothing", upcoming("2026-12-15"))
    }

    /** Too few days in either half and nothing is said, however lopsided the few are. */
    @Test
    fun aHandfulOfMoodTapsSaysNothing() {
        val today = day("2026-11-20")
        val journal = regular() + listOf(
            mood("2026-11-10", 5),
            mood("2026-11-11", 5),
            mood("2026-11-12", 5),
            mood("2026-11-25", 1),
            mood("2026-11-26", 1),
        )

        assertNull(moodPattern(journal, cycleSummary(journal, today), today))
    }

    /**
     * Sleep is compared between two sets of real nights — the days she recorded bleeding against
     * the rest — so a night assigned by arithmetic can never be the whole of the finding.
     */
    @Test
    fun theSleepCardComparesRecordedBleedingNightsWithTheRest() {
        val today = day("2026-11-20")
        val journal = regular().toMutableList()
        // Every bleeding day of the last three periods: six hours. Every other night: eight.
        val bleeding = recordedBleedingDays(regular())
        var other = 0
        (0 until 120).forEach { offset ->
            val date = day("2026-07-20").plusDays(offset.toLong())
            if (date.isAfter(today)) return@forEach
            if (date in bleeding) {
                journal += sleep(date.toString(), 360)
            } else if (other++ < 40) {
                journal += sleep(date.toString(), 480)
            }
        }

        val card = cycleInsights(journal, cycleSummary(journal, today), today).of<CycleInsight.SleepPattern>()
        assertEquals(360, card?.periodMinutes)
        assertEquals(480, card?.otherMinutes)
    }

    /** Twenty minutes apart is noise, and noise gets no card. */
    @Test
    fun aSmallSleepDifferenceSaysNothing() {
        val today = day("2026-11-20")
        val journal = regular().toMutableList()
        val bleeding = recordedBleedingDays(regular())
        (0 until 120).forEach { offset ->
            val date = day("2026-07-20").plusDays(offset.toLong())
            if (date.isAfter(today)) return@forEach
            journal += sleep(date.toString(), if (date in bleeding) 470L else 480L)
        }

        assertNull(sleepPattern(journal, today))
    }
}
