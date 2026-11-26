package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the work sessions add up to. The borders under test: a counted week needs five of its seven
 * days known, a long day is at the setting, evening work is past 20:00 and not at it, a usual day
 * off is worked from fifteen minutes, a short gap is under eleven hours and not at them, a long
 * stretch is six hours or more; a run bounded by an unknown day is "at least"; the days before the
 * timer was first used are unknown; hours are split at midnight; a session the timer capped is
 * counted apart and leaves its days unknown; the timer's own rows keep no day.
 */
class WorkStatsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun d(month: Int, day: Int): LocalDate = LocalDate.of(2026, month, day)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** A session of [segments], each a pair of moments: its start and its end. */
    private fun session(vararg segments: Pair<Long, Long>, typed: Boolean = false): JournalEntry =
        workEntry(segments.map { WorkSegment(it.first, it.second) }, "Work", zone, typed = typed)!!

    private fun work(day: LocalDate, fromHour: Int, toHour: Int, toMinute: Int = 0): JournalEntry =
        session(at(day, fromHour) to at(day, toHour, toMinute))

    private fun kept(day: LocalDate) =
        JournalEntry(day = day.toString(), occurredAt = at(day, 12), kind = "WATER", title = "Water", calories = 0.0)

    private fun mood(day: LocalDate, hour: Int, score: Int) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, hour),
        kind = "MOOD",
        title = "Mood",
        calories = 0.0,
        moodScore = score,
    )

    private fun night(wakeDay: LocalDate, minutes: Int, endHour: Int = 7) = JournalEntry(
        day = wakeDay.toString(),
        occurredAt = at(wakeDay, endHour),
        kind = "SLEEP",
        title = "Sleep",
        calories = 0.0,
        sleepStartAt = at(wakeDay, endHour) - minutes * 60_000L,
        sleepEndAt = at(wakeDay, endHour),
    )

    private fun drink(day: LocalDate, grams: Double) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 21),
        kind = KIND_ALCOHOL,
        title = "Beer",
        calories = 0.0,
        servingG = grams,
    )

    private fun days(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()

    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    private fun summary(rows: List<JournalEntry>, window: ClosedRange<LocalDate>, longDay: Int = 600) =
        workSummary(rows, window, zone, weekend, longDay)

    /**
     * Four whole weeks inside the window, from Monday the 2nd of March. The first: five eight-hour
     * days and an unknown weekend, 40 h. The second: four twelve-hour days and a Friday kept without
     * work, 48 h. The third: three days of work and one kept, four known — not counted. The fourth:
     * seven days of work, 55 h. The partial weeks at either end are never counted.
     */
    @Test
    fun countedWeeksAreWholeWeeksWithFiveDaysKnown() {
        val rows = days(d(2, 25), d(3, 1)).map(::kept) +
            days(d(3, 2), d(3, 6)).map { work(it, 9, 17) } +
            days(d(3, 9), d(3, 12)).map { work(it, 8, 20) } + kept(d(3, 13)) +
            days(d(3, 16), d(3, 18)).map { work(it, 9, 17) } + kept(d(3, 21)) +
            days(d(3, 23), d(3, 28)).map { work(it, 9, 17) } + work(d(3, 29), 9, 16) +
            work(d(3, 30), 9, 17)
        val result = summary(rows, d(2, 25)..d(4, 1))
        assertEquals(listOf(d(3, 2), d(3, 9), d(3, 23)), result.weeks.map { it.monday })
        assertEquals(listOf(40.0, 48.0, 55.0), result.weeks.map { it.hours })
        assertEquals(48.0, result.medianWeekHours!!, 0.0)
        assertEquals(40.0..55.0, result.weekHoursRange)
        assertEquals(2, result.weeksAtLeast48)
        assertEquals(1, result.weeksAtLeast55)
        assertEquals(1, result.weeksWithoutADayOff)
        assertEquals(listOf(5, 4, 7), result.weeks.map { it.workDays })
    }

    /** Days kept before the timer was ever used are not known for work: a week of them is not counted. */
    @Test
    fun weeksBeforeTheTimerAreNotCounted() {
        val rows = days(d(3, 2), d(3, 8)).map(::kept) + days(d(3, 9), d(3, 13)).map { work(it, 9, 17) }
        assertEquals(listOf(d(3, 9)), summary(rows, d(3, 2)..d(3, 15)).weeks.map { it.monday })
    }

    /**
     * A shift from Sunday 22:00 to Monday 06:00: two hours in one week, six in the next. The timer
     * was first used the week before the window, so the days kept around the shift are known.
     */
    @Test
    fun weeklyHoursAreSplitAtMidnight() {
        val rows = listOf(work(d(2, 23), 9, 17)) + days(d(3, 2), d(3, 7)).map(::kept) +
            session(at(d(3, 8), 22) to at(d(3, 9), 6)) + days(d(3, 10), d(3, 13)).map(::kept)
        val weeks = summary(rows, d(3, 2)..d(3, 15)).weeks
        assertEquals(listOf(2.0, 6.0), weeks.map { it.hours })
        assertEquals(mapOf(d(3, 8) to 120.0, d(3, 9) to 360.0), summary(rows, d(3, 2)..d(3, 15)).dailyMinutes)
    }

    @Test
    fun aLongDayIsAtTheSettingAndEveningWorkIsPastEight() {
        val rows = listOf(
            work(d(3, 2), 8, 18),
            work(d(3, 3), 8, 17, 59),
            work(d(3, 4), 12, 20),
            work(d(3, 5), 12, 20, 1),
            session(at(d(3, 6), 22) to at(d(3, 7), 6)),
        )
        val result = summary(rows, d(3, 1)..d(3, 31))
        assertEquals(1, result.longDays)
        assertEquals(600.0, result.longestDayMinutes!!, 0.0)
        // The 5th ran a minute past eight; the 6th's shift began at ten; its morning on the 7th did not.
        assertEquals(2, result.eveningDays)
        assertEquals(6, result.workDays)
    }

    /**
     * On the day the clocks go back the day has 25 hours, and twenty hours after midnight is 19:00 on
     * the clock: an hour worked from 19:00 to 20:00 is still not evening work.
     */
    @Test
    fun eveningWorkIsReadOnTheClockOnTheDayTheClocksChange() {
        val sunday = d(10, 25)
        val rows = listOf(work(sunday, 19, 20), work(sunday.plusDays(1), 14, 20, 30))
        val result = summary(rows, d(10, 1)..d(10, 31))
        assertEquals(1, result.eveningDays)
        assertEquals(60.0, result.dailyMinutes.getValue(sunday), 0.0)
    }

    /**
     * Saturday the 7th worked for fifteen minutes, Sunday the 8th for fourteen, Saturday the 14th
     * kept without work, Sunday the 15th unknown, and Saturday the 28th of February kept before the
     * timer was first used: one of three known.
     */
    @Test
    fun aUsualDayOffCountsAsWorkedFromFifteenMinutes() {
        val rows = listOf(
            kept(d(2, 28)),
            work(d(3, 2), 9, 17),
            session(at(d(3, 7), 10) to at(d(3, 7), 10, 15)),
            session(at(d(3, 8), 10) to at(d(3, 8), 10, 14)),
            kept(d(3, 14)),
        )
        assertEquals(DayShare(1, 3), summary(rows, d(2, 23)..d(3, 15)).usualDaysOff)
    }

    /**
     * A five-day run between two days kept without work, a six-day run after a kept day and before
     * an unknown one, and a four-day run cut by the window's end.
     */
    @Test
    fun theLongestRunIsAtLeastWhenAnUnknownDayOrTheWindowBoundsIt() {
        val rows = listOf(work(d(3, 2), 9, 17), kept(d(3, 3))) +
            days(d(3, 4), d(3, 8)).map { work(it, 9, 17) } + kept(d(3, 9)) +
            kept(d(3, 11)) + days(d(3, 12), d(3, 17)).map { work(it, 9, 17) } +
            days(d(3, 28), d(3, 31)).map { work(it, 9, 17) }
        assertEquals(WorkRun(6, d(3, 12), d(3, 17), atLeast = true), summary(rows, d(3, 1)..d(3, 31)).longestRun)
        // Without the six-day run, the five-day one is exact.
        val exact = rows.filterNot { it.day >= "2026-03-12" && it.day <= "2026-03-17" }
        assertEquals(WorkRun(5, d(3, 4), d(3, 8), atLeast = false), summary(exact, d(3, 1)..d(3, 26)).longestRun)
        // Cut by the window: at least.
        assertEquals(WorkRun(2, d(3, 30), d(3, 31), atLeast = true), summary(rows, d(3, 30)..d(3, 31)).longestRun)
        assertNull(summary(listOf(kept(d(3, 1))), d(3, 1)..d(3, 31)).longestRun)
    }

    /**
     * Of two runs as long, the one that may be longer is printed, even when it is the earlier: the
     * 2nd to the 4th follows the unknown 1st, the 10th to the 12th sits between two kept days.
     */
    @Test
    fun anAtLeastRunOutranksAnExactOneOfTheSameLength() {
        val rows = days(d(3, 2), d(3, 4)).map { work(it, 9, 17) } + kept(d(3, 5)) + kept(d(3, 9)) +
            days(d(3, 10), d(3, 12)).map { work(it, 9, 17) } + kept(d(3, 13))
        assertEquals(WorkRun(3, d(3, 2), d(3, 4), atLeast = true), summary(rows, d(3, 1)..d(3, 31)).longestRun)
    }

    /**
     * Eleven hours exactly is not short; ten fifty-nine is; the last end of two sessions counts; days
     * with a day between are no pair; a night shift's end is where its rest begins.
     */
    @Test
    fun aShortGapIsUnderElevenHoursBetweenLastEndAndFirstStart() {
        val rows = listOf(
            work(d(3, 2), 8, 20),
            work(d(3, 3), 7, 21),
            session(at(d(3, 4), 7, 59) to at(d(3, 4), 12)),
            work(d(3, 4), 13, 23),
            work(d(3, 5), 9, 17),
            work(d(3, 7), 9, 17),
            session(at(d(3, 10), 22) to at(d(3, 11), 6)),
            work(d(3, 11), 20, 23),
        )
        val result = summary(rows, d(3, 1)..d(3, 31))
        assertEquals(4, result.gapPairs)
        assertEquals(2, result.shortGaps)
    }

    @Test
    fun aLongStretchIsSixHoursWithoutAPressedPauseAndTypedSessionsAreApart() {
        val rows = listOf(
            work(d(3, 2), 8, 14),
            work(d(3, 3), 8, 13, 59),
            session(at(d(3, 4), 8) to at(d(3, 4), 11), at(d(3, 4), 11, 30) to at(d(3, 4), 18)),
            session(at(d(3, 5), 8) to at(d(3, 5), 12), at(d(3, 5), 12, 30) to at(d(3, 5), 17)),
            session(at(d(3, 6), 8) to at(d(3, 6), 17), typed = true),
        )
        val result = summary(rows, d(3, 1)..d(3, 31))
        assertEquals(4, result.timedSessions)
        assertEquals(1, result.typedSessions)
        assertEquals(2, result.longStretchSessions)
    }

    /**
     * Monday the 2nd is long, Tuesday the 3rd short, Wednesday the 4th and Saturday the 7th are days
     * off. Mood written during the work is not the evening's; the next day is the whole of it.
     */
    @Test
    fun comparisonsReadTheEveningTheNextDayAndTheNextNight() {
        val rows = listOf(
            work(d(3, 2), 8, 18),
            mood(d(3, 2), 12, 1),
            mood(d(3, 2), 19, 5),
            drink(d(3, 2), 20.0),
            night(d(3, 2), 480),
            work(d(3, 3), 9, 13),
            mood(d(3, 3), 9, 2),
            mood(d(3, 3), 14, 4),
            night(d(3, 3), 420),
            // An afternoon nap filed under the same day is no part of the night that ended that morning.
            night(d(3, 3), 60, endHour = 15),
            mood(d(3, 4), 10, 3),
            night(d(3, 4), 360),
            kept(d(3, 7)),
            mood(d(3, 7), 10, 4),
            drink(d(3, 7), 10.0),
        )
        val result = workDayComparisons(rows, d(3, 1)..d(3, 31), zone, longDayMinutes = 600)
        assertEquals(1, result.longDays)
        assertEquals(1, result.otherDays)
        assertEquals(2, result.daysOffKnown)
        assertEquals(DayMean(1, 5.0), result.moodSameEvening.exposed)
        assertEquals(DayMean(1, 4.0), result.moodSameEvening.other)
        assertEquals(DayMean(1, 3.0), result.moodNextDay.forward.exposed)
        assertEquals(DayMean(1, 3.0), result.moodNextDay.forward.other)
        assertEquals(DayMean(0, null), result.moodNextDay.reverse.exposed)
        assertEquals(DayMean(1, 3.0), result.moodNextDay.reverse.other)
        assertEquals(DayMean(1, 420.0), result.sleepNextNight.forward.exposed)
        assertEquals(DayMean(1, 360.0), result.sleepNextNight.forward.other)
        assertEquals(DayMean(1, 480.0), result.sleepNextNight.reverse.exposed)
        assertEquals(DayMean(1, 420.0), result.sleepNextNight.reverse.other)
        assertEquals(DayShare(1, 1), result.drinkEvenings.exposed)
        assertEquals(DayShare(0, 1), result.drinkEvenings.other)
        assertEquals(DayMean(1, 20.0), result.drinkGrams.exposed)
        assertEquals(DayMean(1, 0.0), result.drinkGrams.other)
        assertEquals(DayMean(2, 3.5), result.daysOff.mood)
        assertEquals(DayMean(0, null), result.daysOff.sleepNextNight)
        assertEquals(DayShare(1, 2), result.daysOff.drinkEvenings)
        assertEquals(DayMean(2, 5.0), result.daysOff.drinkGrams)
        assertFalse(result.moodSameEvening.notable)
        assertFalse(result.drinksNotable)
    }

    /**
     * A day nobody kept is no evening without a drink: it is left out, not counted as none. The work
     * timer's own row keeps no day — it is the exposure, and if it kept one every work day would be
     * an evening without a drink while a day off needed an entry of its own.
     */
    @Test
    fun drinksAreReadOnlyOnDaysKeptByHand() {
        val rows = listOf(session(at(d(3, 2), 22) to at(d(3, 3), 6)))
        val result = workDayComparisons(rows, d(3, 1)..d(3, 31), zone, longDayMinutes = 600)
        assertEquals(2, result.otherDays)
        assertEquals(DayShare(0, 0), result.drinkEvenings.other)
        val kept = workDayComparisons(rows + kept(d(3, 2)), d(3, 1)..d(3, 31), zone, longDayMinutes = 600)
        assertEquals(DayShare(0, 1), kept.drinkEvenings.other)
    }

    /**
     * The timer stopped itself on the 3rd, sixteen hours after a Start at nine that nobody stopped —
     * fifteen hours on the 3rd and one on the 4th. It is counted apart, and the 3rd and the 4th are
     * unknown: no long day, no long stretch, no rest gap beside the 4th's own session, not days off.
     */
    @Test
    fun aCappedSessionIsCountedApartAndLeavesItsDaysUnknown() {
        val capped = workEntry(listOf(WorkSegment(at(d(3, 3), 9), at(d(3, 4), 1))), "Work", zone, capped = true)!!
        val rows = listOf(
            work(d(3, 2), 9, 13),
            capped,
            kept(d(3, 3)),
            work(d(3, 4), 9, 13),
            work(d(3, 5), 9, 13),
            kept(d(3, 6)),
        )
        val result = summary(rows, d(3, 1)..d(3, 31))
        assertEquals(1, result.cappedSessions)
        assertEquals(3, result.timedSessions)
        assertEquals(0, result.longStretchSessions)
        assertEquals(0, result.longDays)
        assertEquals(setOf(d(3, 2), d(3, 5)), result.dailyMinutes.keys)
        assertEquals(0, result.gapPairs)
        assertEquals(WorkRun(1, d(3, 5), d(3, 5), atLeast = true), result.longestRun)
        val comparisons = workDayComparisons(rows, d(3, 1)..d(3, 31), zone, longDayMinutes = 600)
        assertEquals(0, comparisons.longDays)
        assertEquals(2, comparisons.otherDays)
        assertEquals(1, comparisons.daysOffKnown)
    }

    /** Ten long days and ten short ones: enough days for a verdict rather than the floor's message. */
    @Test
    fun tenDaysEachReachTheFloor() {
        val long = days(d(3, 2), d(3, 11)).flatMap { listOf(work(it, 8, 18), mood(it, 20, 2)) }
        val short = days(d(3, 12), d(3, 21)).flatMap { listOf(work(it, 9, 13), mood(it, 20, 4)) }
        val result = workDayComparisons(long + short, d(3, 1)..d(3, 31), zone, longDayMinutes = 600)
        assertEquals(DayMean(10, 2.0), result.moodSameEvening.exposed)
        assertTrue(result.moodSameEvening.verdict != ContrastVerdict.NOT_ENOUGH_DAYS)
        // All the long days are in the first half, so the halves cannot agree.
        assertEquals(ContrastVerdict.NO_CLEAR_DIFFERENCE, result.moodSameEvening.verdict)
    }
}
