package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The week-by-week series of the Work charts page: the weeks are the work cards' own whole
 * Monday-to-Sunday weeks to yesterday; a week that was not counted is a gap, never a zero; a line's
 * week is a point from four days with a reading; the line is each week's distance from the mean of
 * the weeks with a point; a comparison stays off the page until four weeks carry a bar and a point
 * both, and a line that never leaves zero stays off it too; the drinks are read on the last week's
 * Sunday as on any other day.
 */
class WorkChartSeriesTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    /** A Wednesday: the cards read the 7th of January to the 7th of April, twelve whole weeks. */
    private val today = LocalDate.of(2026, 4, 8)

    private val weeks = wholeWorkWeeks(workInsightWindow(today))

    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun work(day: LocalDate, toMinute: Int = 0): JournalEntry =
        workEntry(listOf(WorkSegment(at(day, 9), at(day, 17, toMinute))), "Work", zone)!!

    private fun mood(day: LocalDate, score: Int, hour: Int = 20) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, hour),
        kind = "MOOD",
        title = "Mood",
        calories = 0.0,
        moodScore = score,
    )

    private fun energy(day: LocalDate, answer: Energy, hour: Int = 20) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, hour),
        kind = "MOOD",
        title = "Energy",
        calories = 0.0,
        energyScore = answer.score,
    )

    private fun drink(day: LocalDate, grams: Double) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 21),
        kind = KIND_ALCOHOL,
        title = "Beer",
        calories = 0.0,
        servingG = grams,
    )

    private fun kept(day: LocalDate) =
        JournalEntry(day = day.toString(), occurredAt = at(day, 12), kind = "WATER", title = "Water", calories = 0.0)

    private fun days(week: ClosedRange<LocalDate>): List<LocalDate> =
        generateSequence(week.start) { it.plusDays(1) }.takeWhile { it <= week.endInclusive }.toList()

    /** Monday to Friday, eight hours a day, in each of [weekIndices]: 40 h a counted week. */
    private fun workWeeks(weekIndices: Iterable<Int>, toMinute: Int = 0): List<JournalEntry> =
        weekIndices.flatMap { index -> days(weeks[index]).take(5).map { work(it, toMinute) } }

    private fun data(journal: List<JournalEntry>, alcohol: Boolean = true) =
        workChartsData(journal, today, zone, weekend, 600, alcohol)

    @Test
    fun theWeeksAreTheWholeMondayToSundayWeeksToYesterday() {
        assertEquals(12, weeks.size)
        assertEquals(LocalDate.of(2026, 1, 12), weeks.first().start)
        assertEquals(LocalDate.of(2026, 4, 5), weeks.last().endInclusive)
        assertTrue(weeks.all { it.start.dayOfWeek == DayOfWeek.MONDAY && it.endInclusive == it.start.plusDays(6) })
        // On a Monday yesterday was a Sunday, and the week it ended is whole: thirteen of them.
        val monday = wholeWorkWeeks(workInsightWindow(LocalDate.of(2026, 4, 6)))
        assertEquals(13, monday.size)
        assertEquals(LocalDate.of(2026, 4, 5), monday.last().endInclusive)
    }

    @Test
    fun aWeekThatWasNotCountedIsAGapNeverAZero() {
        val shown = data(workWeeks((6..11) - 8))

        assertEquals(
            List(6) { null } + listOf(40, 40, null, 40, 40, 40),
            shown.hours.map { it?.roundToInt() },
        )
        assertTrue(shown.hoursShown)
        assertFalse(shown.empty)
        assertEquals(40.0, shown.hoursTop, 1e-9)
        // The bars are the cards' own counted weeks, and the dashed line their median.
        assertEquals(5, shown.insights.summary.weeks.size)
        assertEquals(40.0, shown.insights.summary.medianWeekHours!!, 1e-9)
    }

    @Test
    fun theHoursAxisTopsOutAtTheLongestWeekRoundedUp() {
        // Eight hours and six minutes a day: 40.5 h a week, printed at the top as 41 h.
        assertEquals(41.0, data(workWeeks(6..11, toMinute = 6)).hoursTop, 1e-9)
    }

    @Test
    fun fewerThanFourCountedWeeksDrawNoChartButTheCardsStay() {
        val few = data(workWeeks(9..11))

        assertFalse(few.hoursShown)
        assertTrue(few.pairs.isEmpty())
        assertFalse(few.empty)
    }

    @Test
    fun aPageWithNoWorkInItsWeeksIsEmpty() {
        val none = data(weeks.flatMap(::days).map(::kept))

        assertFalse(none.hoursShown)
        assertTrue(none.empty)
    }

    @Test
    fun aWeeksMoodIsAPointFromFourDaysEachDayOnce() {
        val first = days(weeks[0])
        val second = days(weeks[1])
        val journal = listOf(
            // Two taps on one day are one day's mood, 3.
            mood(first[0], 1, hour = 9),
            mood(first[0], 5, hour = 21),
            mood(first[1], 4),
            mood(first[2], 4),
            mood(first[3], 4),
            // Three days are not enough.
            mood(second[0], 2),
            mood(second[1], 2),
            mood(second[2], 2),
        )
        val points = weeklyMood(journal, weeks)

        assertEquals(3.75, points[0]!!, 1e-9)
        assertNull(points[1])
        assertNull(points[2])
    }

    @Test
    fun aWeekWoundUpIsTheShareOfItsAnsweredDays() {
        val first = days(weeks[0])
        val second = days(weeks[1])
        val journal = listOf(
            energy(first[0], Energy.WOUND_UP),
            // A day with a restless answer among calm ones is a day answered wound up or restless.
            energy(first[1], Energy.CALM, hour = 9),
            energy(first[1], Energy.RESTLESS, hour = 21),
            energy(first[2], Energy.STEADY),
            energy(first[3], Energy.SETTLED),
            energy(second[0], Energy.WOUND_UP),
            energy(second[1], Energy.WOUND_UP),
            energy(second[2], Energy.WOUND_UP),
        )
        val points = weeklyWoundUp(journal, weeks)

        assertEquals(0.5, points[0]!!, 1e-9)
        // Three answered days, and an unanswered day is not a calm one.
        assertNull(points[1])
    }

    @Test
    fun theLineIsEachWeeksDistanceFromTheMeanOfTheWeeksWithAPoint() {
        val line = WeekLine(listOf(1.0, null, 3.0, 4.0))

        assertEquals(8.0 / 3.0, line.average!!, 1e-9)
        assertEquals(listOf(1.0 - 8.0 / 3.0, null, 3.0 - 8.0 / 3.0, 4.0 - 8.0 / 3.0), line.fromAverage)
        assertNull(WeekLine(listOf(null, null)).average)
        assertEquals(listOf<Double?>(null, null), WeekLine(listOf(null, null)).fromAverage)
    }

    @Test
    fun aComparisonNeedsFourWeeksWithABarAndAPointAndALineAboveZero() {
        val hours = listOf(40.0, 40.0, 40.0, 40.0, null)

        assertNull(workPairChart(WorkOutcome.MOOD, hours, WeekLine(listOf(3.0, 3.0, 3.0, null, 3.0))))
        assertNotNull(workPairChart(WorkOutcome.MOOD, hours, WeekLine(listOf(3.0, 3.0, 3.0, 3.0, null))))
        // Four weeks without a drink beside the hours say nothing about drinking.
        assertNull(workPairChart(WorkOutcome.ALCOHOL, hours, WeekLine(listOf(0.0, 0.0, 0.0, 0.0, null))))
    }

    @Test
    fun theComparisonsAreTheSheetsPairsAndTheDrinksOnlyForAProfileAskedAboutThem() {
        val worked = (6..11).flatMap { days(weeks[it]) }
        val journal = workWeeks(6..11) +
            worked.map { mood(it, if (it.dayOfWeek == DayOfWeek.MONDAY) 2 else 4) } +
            worked.filter { it.dayOfWeek == DayOfWeek.SUNDAY }.map { drink(it, 20.0) }

        val asked = data(journal, alcohol = true)
        val notAsked = data(journal, alcohol = false)

        assertEquals(listOf(WorkOutcome.MOOD, WorkOutcome.ALCOHOL), asked.pairs.map { it.outcome })
        assertEquals(listOf(WorkOutcome.MOOD), notAsked.pairs.map { it.outcome })
        // Twenty grams on each Sunday over seven kept days: two standard drinks a week, the last
        // week's Sunday among them although it is the last day the weeks hold.
        val drinks = asked.pairs.single { it.outcome == WorkOutcome.ALCOHOL }.line.points
        assertEquals(2.0, drinks.last()!!, 1e-9)
        assertNull(drinks.first())
    }

    @Test
    fun eachOutcomeCarriesTheSheetsResearchAndItsOwnSmallestSwing() {
        assertEquals(
            R.string.report_changes_background_work_mood to R.string.report_changes_source_work_mood,
            WorkOutcome.MOOD.research(),
        )
        assertEquals(
            R.string.report_changes_background_work_wound_up to R.string.report_changes_source_work_wound_up,
            WorkOutcome.WOUND_UP.research(),
        )
        assertEquals(
            R.string.report_changes_background_work_sleep to R.string.report_changes_source_work_sleep,
            WorkOutcome.SLEEP.research(),
        )
        assertEquals(
            R.string.report_changes_background_work_alcohol to R.string.report_changes_source_work_alcohol,
            WorkOutcome.ALCOHOL.research(),
        )
        assertEquals(0.5, WorkOutcome.MOOD.minSwing, 1e-9)
        assertEquals(0.5, WorkOutcome.SLEEP.minSwing, 1e-9)
        assertEquals(2.0, WorkOutcome.ALCOHOL.minSwing, 1e-9)
        assertEquals(0.1, WorkOutcome.WOUND_UP.minSwing, 1e-9)
    }
}
