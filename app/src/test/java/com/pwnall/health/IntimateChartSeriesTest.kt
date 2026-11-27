package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The week-by-week series of the sex-life charts page: twelve weeks of seven days ending on today; a
 * week of mornings is a point from three answers and a gap below them, never a zero; the comparison's
 * line is each week's distance from the pooled average; the acts' marks land on the line of the act
 * they were ticked on; and every chart stays off the page until it reaches its floor.
 */
class IntimateChartSeriesTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    /** A Friday, so the newest week runs Saturday the 28th of March to Friday the 3rd of April. */
    private val today = LocalDate.of(2026, 4, 3)

    private val weeks = chartWeeks(today)

    private fun at(day: LocalDate, hour: Int): Long = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun check(day: LocalDate, answer: MorningErection) =
        morningCheckEntry("Morning", day, MorningCheck(answer, at(day, 7), WakeSource.TYPED))

    private fun night(day: LocalDate, minutes: Int) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 7),
        kind = "SLEEP",
        title = "Sleep",
        calories = 0.0,
        sleepStartAt = at(day, 7) - minutes * 60_000L,
        sleepEndAt = at(day, 7),
    )

    private fun drink(day: LocalDate, grams: Double) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 21),
        kind = KIND_ALCOHOL,
        title = "Wine",
        calories = 0.0,
        servingG = grams,
    )

    private fun kept(day: LocalDate) =
        JournalEntry(day = day.toString(), occurredAt = at(day, 12), kind = "WATER", title = "Water", calories = 0.0)

    private fun partnered(day: LocalDate, vararg notes: SexNote) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 22),
        kind = KIND_CYCLE_LOG,
        title = "Sex",
        calories = 0.0,
        measurementPayloadJson = listOfNotNull(
            """"$CYCLE_PAYLOAD_KEY":"SEX_PROTECTED"""",
            notes.takeIf { it.isNotEmpty() }
                ?.joinToString(",", """"$CYCLE_NOTES_KEY":[""", "]") { "\"${it.name}\"" },
        ).joinToString(",", "{", "}"),
    )

    private fun solo(day: LocalDate, vararg notes: SexNote) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 22),
        kind = KIND_MASTURBATION,
        title = "Masturbation",
        calories = 0.0,
        measurementPayloadJson = notes.takeIf { it.isNotEmpty() }
            ?.joinToString(",", """{"$CYCLE_NOTES_KEY":[""", "]}") { "\"${it.name}\"" },
    )

    /** The [day]th day (0 to 6) of week [index], oldest week 0. */
    private fun dayOf(index: Int, day: Int): LocalDate = weeks[index].start.plusDays(day.toLong())

    /** [firm] firm and [none] none-answered mornings in week [index]. */
    private fun mornings(index: Int, firm: Int, none: Int = 0): List<JournalEntry> =
        (0 until firm).map { check(dayOf(index, it), MorningErection.FIRM) } +
            (firm until firm + none).map { check(dayOf(index, it), MorningErection.NONE) }

    @Test
    fun theWeeksAreTwelveSevenDayStretchesEndingToday() {
        assertEquals(INTIMATE_CHART_WEEKS, weeks.size)
        assertEquals(LocalDate.of(2026, 3, 28)..today, weeks.last())
        assertEquals(LocalDate.of(2026, 1, 10)..LocalDate.of(2026, 1, 16), weeks.first())
        weeks.zipWithNext { older, newer -> assertEquals(older.endInclusive.plusDays(1), newer.start) }
        assertEquals(today.minusDays(INTIMATE_CHART_DAYS - 1), weeks.first().start)
    }

    /**
     * Two answers make no point, three do; "Didn't notice" is no answer. The average pools the weeks
     * with a point, and each point stands above or below it.
     */
    @Test
    fun aWeekOfMorningsIsAPointFromThreeAnswersAndAGapBelowThem() {
        val rows = mornings(9, firm = 3, none = 1) +
            mornings(10, firm = 2) +
            check(dayOf(10, 5), MorningErection.DIDNT_NOTICE) +
            mornings(11, firm = 1, none = 3)
        val found = morningWeeks(rows, weeks)
        assertNull(found.points[8])
        assertEquals(0.75, found.points[9]!!, 1e-9)
        assertNull("two answers and a didn't-notice are not a point", found.points[10])
        assertEquals(0.25, found.points[11]!!, 1e-9)
        assertEquals(2, found.weeksWithPoint)
        // 4 of 8, pooled — the third week's two answers are not in it.
        assertEquals(0.5, found.average!!, 1e-9)
        assertEquals(0.25, found.fromAverage[9]!!, 1e-9)
        assertEquals(-0.25, found.fromAverage[11]!!, 1e-9)
        assertNull(found.fromAverage[10])
        assertNull(MorningWeeks(List(INTIMATE_CHART_WEEKS) { DayShare(0, 0) }).average)
    }

    @Test
    fun aComparisonNeedsFourWeeksWithABarAndAPointAndSomethingAboveZero() {
        val rows = (4..11).flatMap { mornings(it, firm = 2, none = 1) }
        val found = morningWeeks(rows, weeks)
        val threeWeeks = List(INTIMATE_CHART_WEEKS) { if (it >= 9) 7.5 else null }
        assertNull(morningPairChart(MorningExposure.NIGHT, threeWeeks, found))
        val fourWeeks = List(INTIMATE_CHART_WEEKS) { if (it >= 8) 7.5 else null }
        val chart = morningPairChart(MorningExposure.NIGHT, fourWeeks, found)
        assertNotNull(chart)
        assertEquals(found.fromAverage, chart!!.mornings)
        // Bars without a morning point beside them do not count towards the four.
        val early = List(INTIMATE_CHART_WEEKS) { if (it < 6) 7.5 else null }
        assertNull(morningPairChart(MorningExposure.NIGHT, early, found))
        // Twelve weeks without a drink say nothing about drinking.
        val dry = List(INTIMATE_CHART_WEEKS) { 0.0 }
        assertNull(morningPairChart(MorningExposure.ALCOHOL, dry, found))
    }

    /** A week's mean night is in hours, from four nights; three leave the week without a bar. */
    @Test
    fun aWeeksNightIsTheMeanOfFourNightsOrMore() {
        val rows = (0..3).map { night(dayOf(11, it), 7 * 60 + it * 20) } + (0..2).map { night(dayOf(10, it), 8 * 60) }
        val hours = weeklyNightHours(rows, weeks, zone)
        assertEquals(7.5, hours[11]!!, 1e-9)
        assertNull(hours[10])
        assertNull(hours[0])
    }

    /** Days at zero steps are left out of the mean, as days the phone was not carried. */
    @Test
    fun aWeeksStepsAreTheMeanOfTheDaysThatCountedAny() {
        val days = (0..4).map { DaySteps(dayOf(11, it).toString(), if (it == 4) 0L else 8_000L + it * 1_000L) } +
            (0..2).map { DaySteps(dayOf(10, it).toString(), 5_000L) }
        val steps = weeklySteps(days, weeks)
        assertEquals(9_500.0, steps[11]!!, 1e-9)
        assertNull("three days and no more", steps[10])
    }

    /**
     * Standard drinks a week, read off the days kept by hand and scaled to seven: four kept days make a
     * bar, a week of four kept days without a drink is a zero, and three kept days — or one glass of
     * water — are a gap, never a dry week.
     */
    @Test
    fun aWeeksDrinksAreReadOnlyOffItsDaysKeptByHand() {
        val rows = listOf(drink(dayOf(11, 1), 20.0), drink(dayOf(11, 4), 15.0)) +
            listOf(0, 2).map { kept(dayOf(11, it)) } +
            (0..3).map { kept(dayOf(10, it)) } +
            listOf(drink(dayOf(9, 0), 10.0), kept(dayOf(9, 1)), kept(dayOf(9, 2))) +
            kept(dayOf(8, 0))
        val drinks = weeklyDrinks(rows, weeks)
        // 35 g over four kept days, scaled to seven: 61.25 g, 6.125 standard drinks.
        assertEquals(35.0 / 4 * 7 / STANDARD_DRINK_G, drinks[11]!!, 1e-9)
        assertEquals(0.0, drinks[10]!!, 1e-9)
        assertNull("three kept days and no more", drinks[9])
        assertNull("one glass of water is not a dry week", drinks[8])
        assertNull(drinks[7])
    }

    /**
     * The ticks land on the act they were ticked on; a week with nothing recorded — before the diary's
     * first row or in the middle of it — is no week at all, and a week with anything and no act a zero.
     */
    @Test
    fun theActsMarkDesireAndDifficultyOnTheirOwnLine() {
        val rows = listOf(
            kept(dayOf(8, 0)),
            partnered(dayOf(9, 1), SexNote.LOW_DESIRE),
            partnered(dayOf(9, 3)),
            solo(dayOf(9, 4), SexNote.ERECTION),
            solo(dayOf(11, 0), SexNote.LOW_DESIRE, SexNote.ERECTION),
            partnered(dayOf(11, 2), SexNote.ERECTION),
        )
        val chart = actsChart(rows, weeks)!!
        assertTrue(chart.weeks.take(8).all { it == null })
        assertEquals(ActsWeek(partnered = 0, solo = 0), chart.weeks[8])
        assertEquals(ActsWeek(partnered = 2, solo = 1, partneredLowDesire = 1, soloErection = 1), chart.weeks[9])
        assertNull("a week with nothing recorded is not a week without acts", chart.weeks[10])
        assertEquals(
            ActsWeek(partnered = 1, solo = 1, soloLowDesire = 1, partneredErection = 1, soloErection = 1),
            chart.weeks[11],
        )
    }

    /**
     * A week of nothing but rows nobody typed — the nights the sleep estimate writes on every opening
     * of the app — is no week at all, never a week without acts; a morning answered in it is a week
     * kept, and a zero.
     */
    @Test
    fun aWeekOfEstimatedNightsIsNoWeekWithoutActs() {
        val estimated = (0..6).map { night(dayOf(10, it), 7 * 60).copy(measurementSource = "PHONE_USAGE_ESTIMATE") }
        val rows = listOf(partnered(dayOf(9, 1)), partnered(dayOf(11, 2))) + estimated

        assertNull(actsChart(rows, weeks)!!.weeks[10])
        val answered = actsChart(rows + check(dayOf(10, 3), MorningErection.FIRM), weeks)!!
        assertEquals(ActsWeek(partnered = 0, solo = 0), answered.weeks[10])
    }

    /**
     * Today is no reading yet: its steps are still climbing, and a breakfast written down makes it a
     * kept day before its evening has happened.
     */
    @Test
    fun todayIsNoReadingForStepsOrDrinks() {
        val days = (0..5).map { DaySteps(dayOf(11, it).toString(), 9_000L) } + DaySteps(today.toString(), 600L)
        assertEquals(9_000.0, weeklySteps(days, weeks)[11]!!, 1e-9)

        // Three finished kept days and this morning's breakfast: no bar yet, rather than a dry day.
        val rows = (1..3).map { drink(dayOf(11, it), 20.0) } + kept(today)
        assertNull(weeklyDrinks(rows, weeks)[11])
    }

    /**
     * A nap on a day with no night recorded stands in for no night: an afternoon's or a morning's, it
     * is no reading. A short night is a night, though — two and a half hours is a bad night, and it
     * stays in the mean — and so is the long day's sleep after a night shift, and a night a file
     * import filed under the day it began.
     */
    @Test
    fun aNapIsNoNightButAShortNightIs() {
        fun sleep(day: LocalDate, fromHour: Int, minutes: Int): JournalEntry {
            val start = at(day, fromHour)
            return night(day, minutes).copy(sleepStartAt = start, sleepEndAt = start + minutes * 60_000L)
        }
        val rows = (0..3).map { night(dayOf(11, it), 7 * 60 + 30) } + sleep(dayOf(11, 4), 13, 45)
        assertEquals(7.5, weeklyNightHours(rows, weeks, zone)[11]!!, 1e-9)

        val withNap = (0..2).map { night(dayOf(10, it), 8 * 60) } + sleep(dayOf(10, 3), 10, 60)
        assertNull(weeklyNightHours(withNap, weeks, zone)[10])

        val badNight = (0..3).map { night(dayOf(9, it), 7 * 60) } + night(dayOf(9, 4), 150)
        assertEquals((4 * 7 * 60 + 150) / 5.0 / 60.0, weeklyNightHours(badNight, weeks, zone)[9]!!, 1e-9)

        val shifts = (0..3).map { sleep(dayOf(8, it), 13, 7 * 60 + 30) }
        assertEquals(7.5, weeklyNightHours(shifts, weeks, zone)[8]!!, 1e-9)

        // 23:00 to 07:00, filed under the evening it began, as a file import files it.
        val imported = (0..3).map { night(dayOf(7, it + 1), 8 * 60).copy(day = dayOf(7, it).toString()) }
        assertEquals(8.0, weeklyNightHours(imported, weeks, zone)[7]!!, 1e-9)

        // A short night filed under its evening counts under the morning it ended: 23:30 to 02:00.
        val shortImport = (0..3).map { night(dayOf(6, it), 7 * 60) } +
            sleep(dayOf(6, 3), 23, 150).let { it.copy(sleepStartAt = it.sleepStartAt!! + 30 * 60_000L) }
        assertEquals((4 * 7 * 60 + 120) / 5.0 / 60.0, weeklyNightHours(shortImport, weeks, zone)[6]!!, 1e-9)
    }

    /**
     * A doze at dawn that ends hours before the day's own sleep — the sleep after a night shift — is
     * left out rather than read as the morning's night, which would lose the sleep itself.
     */
    @Test
    fun aDozeBeforeTheDaysSleepIsNotItsNight() {
        val day = dayOf(5, 3)
        val doze = night(day, 45).copy(sleepStartAt = at(day, 6) + 15 * 60_000L, sleepEndAt = at(day, 7))
        val sleep = night(day, 7 * 60).copy(sleepStartAt = at(day, 9), sleepEndAt = at(day, 16))
        val rows = (0..2).map { night(dayOf(5, it), 7 * 60) } + doze + sleep

        assertEquals(7.0, weeklyNightHours(rows, weeks, zone)[5]!!, 1e-9)
    }

    /** A row that is no sleep at all — no length, or days long — does not make the nap beside it a night. */
    @Test
    fun aBrokenRowDoesNotMakeANapANight() {
        val day = dayOf(4, 3)
        val empty = night(day, 1).copy(sleepStartAt = at(day, 5), sleepEndAt = at(day, 5))
        val nap = night(day, 60).copy(sleepStartAt = at(day, 13), sleepEndAt = at(day, 14))
        val rows = (0..2).map { night(dayOf(4, it), 7 * 60) } + empty + nap

        assertNull("three nights and a nap are not four readings", weeklyNightHours(rows, weeks, zone)[4])
    }

    /** Dozing off again after a waking is the same night, even when the second stretch is short and late. */
    @Test
    fun aDozeAfterAWakingStaysWithTheNight() {
        val day = dayOf(11, 4)
        val first = night(day, 6 * 60 + 45).copy(
            sleepEndAt = at(day, 5) + 45 * 60_000L,
            sleepStartAt =
            at(day, 0) - 60 * 60_000L,
        )
        val doze = night(day, 105).copy(sleepStartAt = at(day, 6) + 15 * 60_000L, sleepEndAt = at(day, 8))
        val rows = (0..3).map { night(dayOf(11, it), 7 * 60) } + first + doze

        assertEquals((4 * 7 * 60 + 405 + 105) / 5.0 / 60.0, weeklyNightHours(rows, weeks, zone)[11]!!, 1e-9)
    }

    @Test
    fun theActsChartNeedsActsInTwoDifferentWeeks() {
        val oneWeek = listOf(partnered(dayOf(11, 0)), solo(dayOf(11, 1)), partnered(dayOf(11, 2)))
        assertNull(actsChart(oneWeek, weeks))
        assertNotNull(actsChart(oneWeek + solo(dayOf(5, 3)), weeks))
        assertNull(actsChart(emptyList(), weeks))
    }

    /**
     * The mornings and their comparisons only for a profile asked about them, alcohol only for one
     * asked about drinks, and a page with nothing over its floor is empty.
     */
    @Test
    fun thePageShowsOnlyWhatReachesItsFloorAndOnlyToWhomItApplies() {
        val rows = (6..11).flatMap { mornings(it, firm = 2, none = 2) } +
            (6..11).flatMap { week -> (0..3).map { night(dayOf(week, it), 7 * 60) } } +
            (6..11).map { drink(dayOf(it, 5), 20.0) }
        val man = intimateChartsData(rows, emptyList(), today, mornings = true, alcohol = true)
        assertNotNull(man.mornings)
        assertEquals(listOf(MorningExposure.NIGHT, MorningExposure.ALCOHOL), man.pairs.map { it.exposure })
        assertNull("no acts at all", man.acts)
        assertFalse(man.empty)

        val noDrinks = intimateChartsData(rows, emptyList(), today, mornings = true, alcohol = false)
        assertEquals(listOf(MorningExposure.NIGHT), noDrinks.pairs.map { it.exposure })

        val notAsked = intimateChartsData(rows, emptyList(), today, mornings = false, alcohol = true)
        assertNull(notAsked.mornings)
        assertTrue(notAsked.pairs.isEmpty())
        assertTrue(notAsked.empty)

        // Three weeks of mornings are not a chart, and nothing is set beside them either.
        val short =
            (9..11).flatMap { mornings(it, firm = 3) } +
                (9..11).flatMap { week -> (0..3).map { night(dayOf(week, it), 400) } }
        val few = intimateChartsData(short, emptyList(), today, mornings = true, alcohol = true)
        assertNull(few.mornings)
        assertTrue(few.pairs.isEmpty())
        assertTrue(few.empty)
    }
}
