package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The figures the cycle ring is drawn from, and the two of them somebody might act on.
 *
 * The ring itself is thirty circles on a canvas and is not worth a test; which day is which colour
 * is the whole of the logic, and every branch of it is a claim about this woman's body.
 */
class CycleVisualisationTest {

    private fun entry(kind: String, day: String) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = kind,
        calories = 0.0,
    )

    private fun day(text: String) = LocalDate.parse(text)

    private fun log(day: String, log: CycleLog) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = KIND_CYCLE_LOG,
        title = log.name,
        calories = 0.0,
        measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"${log.name}"}""",
    )

    /** A regular record: twelve 28-day cycles, each a five-day period. */
    private fun regularJournal(first: String = "2026-01-05", cycle: Long = 28, length: Long = 4) =
        (0 until 12).flatMap { index ->
            val start = day(first).plusDays(cycle * index)
            listOf(
                entry(KIND_CYCLE, start.toString()),
                entry(KIND_CYCLE_END, start.plusDays(length).toString()),
            )
        }

    /** Five marked days between two marks are five bleeding days, not two. */
    @Test
    fun aClosedPeriodFillsInTheDaysBetweenItsEnds() {
        val days = recordedBleedingDays(
            listOf(entry(KIND_CYCLE, "2026-04-21"), entry(KIND_CYCLE_END, "2026-04-25")),
        )

        assertEquals((21..25).map { day("2026-04-$it") }.toSet(), days)
    }

    /**
     * A period still running contributes the days actually marked and nothing else. Filling to
     * today would draw bleeding on days she may simply have forgotten to close the period on.
     */
    @Test
    fun anOpenPeriodOnlyContributesWhatWasMarked() {
        val days = recordedBleedingDays(
            listOf(
                entry(KIND_CYCLE, "2026-04-21"),
                JournalEntry(
                    day = "2026-04-23",
                    occurredAt = 0L,
                    kind = KIND_CYCLE_LOG,
                    title = "flow",
                    calories = 0.0,
                    measurementPayloadJson = """{"cycle":"FLOW_MEDIUM"}""",
                ),
            ),
        )

        assertEquals(setOf(day("2026-04-21"), day("2026-04-23")), days)
    }

    /** The period length the screen prints is inclusive of both ends: the 1st to the 5th is five. */
    @Test
    fun theMedianPeriodLengthCountsBothEnds() {
        val summary = cycleSummary(regularJournal(), day("2026-11-20"))

        assertEquals(5, summary.periodLengthDays)
    }

    /**
     * One period left open for a fortnight and closed in a hurry must not move the figure. A median
     * is what makes that true, and it is the only reason the figure is not a mean.
     */
    @Test
    fun oneAbsurdlyLongPeriodDoesNotMoveTheMedian() {
        val journal = regularJournal().toMutableList()
        journal[journal.indexOfFirst { it.kind == KIND_CYCLE_END }] =
            entry(KIND_CYCLE_END, "2026-01-24")

        assertEquals(5, cycleSummary(journal, day("2026-11-20")).periodLengthDays)
    }

    /**
     * The fertile window, counted back from the predicted start: ovulation fourteen days before it,
     * and the five days before that. Wilcox 1995, and the shape every calendar method uses.
     */
    @Test
    fun theFertileWindowIsSixDaysEndingAtTheEstimatedOvulation() {
        val summary = cycleSummary(regularJournal(), day("2026-11-20"))
        val window = fertileWindow(summary)

        // Eleven 28-day gaps from 5 January put the last start on 9 November and the next on
        // 7 December, so ovulation is the 23rd and the window opens on the 18th.
        assertEquals(day("2026-12-07"), summary.nextExpected)
        assertEquals(day("2026-11-18"), window?.start)
        assertEquals(day("2026-11-23"), window?.endInclusive)
    }

    /**
     * And it is withheld from a record that cannot support it. An irregular record gets no window
     * rather than a wide one: this is the figure on the screen somebody might act on, and a window
     * nobody can trust is worse than a blank.
     */
    @Test
    fun anIrregularOrThinRecordGetsNoFertileWindow() {
        val today = day("2026-09-01")

        val twoCycles = listOf("2026-06-01", "2026-06-29", "2026-07-27").map { entry(KIND_CYCLE, it) }
        assertEquals(2, cycleSummary(twoCycles, today).recordedCycles)
        assertNull("two cycles is not a measurement", fertileWindow(cycleSummary(twoCycles, today)))

        // Four cycles running 24 to 36 days: a twelve-day spread, well past FIGO's seven.
        val irregular = listOf("2026-04-01", "2026-04-25", "2026-05-30", "2026-06-23", "2026-07-29")
            .map { entry(KIND_CYCLE, it) }
        val summary = cycleSummary(irregular, today)
        assertFalse(summary.regular)
        assertNull(fertileWindow(summary))

        assertNull("and nothing at all before a first start", fertileWindow(cycleSummary(emptyList(), today)))
    }

    /**
     * Cycles under twenty days put the calendar's window on the day the cycle began or before it,
     * inside the period — the rounding error [observedFertileWindow] refuses a finished cycle. No
     * window then, and no ovulation either; twenty days open it on the second day.
     */
    @Test
    fun aRecordOfShortCyclesGetsNoWindowOverItsPeriod() {
        fun summary(vararg starts: String) = cycleSummary(starts.map { entry(KIND_CYCLE, it) }, day("2026-08-05"))

        val eighteen = summary("2026-06-01", "2026-06-19", "2026-07-07", "2026-07-25")
        assertTrue("three cycles of eighteen days are regular", eighteen.regular)
        assertNull(fertileWindow(eighteen))
        assertNull(estimatedOvulation(eighteen))

        val twenty = summary("2026-06-01", "2026-06-21", "2026-07-11", "2026-07-31")
        assertEquals(day("2026-08-01"), fertileWindow(twenty)?.start)
        assertEquals(day("2026-08-06"), estimatedOvulation(twenty))
    }

    /**
     * The three kinds of day the chart shades: bleeding she wrote down, the days the average puts
     * the next period on, and the estimated fertile window. They make the same claim about the same
     * dates wherever they are drawn.
     */
    @Test
    fun theChartShadesRecordFertileWindowAndForecastApart() {
        val today = day("2026-11-20")
        val journal = regularJournal()
        val facts = CycleFacts(journal, cycleSummary(journal, today), today)
        val chart = facts.chart(facts.phase(), CycleWindow.MONTH, acts = true)
        fun shaded(runs: List<IntRange>) = runs.flatMap { it.toList() }
            .map { chart.buckets[it].start }
            .toSet()

        // The period that ended on 13 November is behind her, and the window begins today.
        assertTrue("nothing already lived through is shaded", shaded(chart.bleeding).isEmpty())
        // The window counted back from the predicted start still reaches into the days ahead.
        assertTrue(day("2026-11-23") in shaded(chart.fertile))
        assertFalse(day("2026-11-24") in shaded(chart.fertile))
        // And the month reaches the next period, which is drawn as forecast rather than record.
        val forecast = shaded(chart.predicted)
        assertTrue(day("2026-12-07") in forecast)
        assertTrue("a forecast is never drawn over a day that has happened", forecast.none { it.isBefore(today) })

        // Bleeding she wrote down still shades the part of it the window reaches: day two of the
        // period running now, and the three days of it left after today.
        val inside = day("2026-11-10")
        val running = CycleFacts(journal, cycleSummary(journal, inside), inside)
            .let { it.chart(it.phase(), CycleWindow.MONTH, acts = true) }
        val bleeding = running.bleeding.flatMap { it.toList() }.map { running.buckets[it].start }
        assertTrue(inside in bleeding)
        assertTrue(day("2026-11-13") in bleeding)
        assertFalse(day("2026-11-14") in bleeding)
    }

    /**
     * The window starts today and runs forward, and it is the same width whatever the record holds.
     *
     * The plot is about what is coming: a window reaching back, or one sliding back by whatever the
     * forward half could not support, would fill a screen about this cycle with the cycles before it.
     */
    @Test
    fun theWindowStartsTodayAndRunsForwardWhateverTheRecordHolds() {
        val today = day("2026-09-18")
        assertEquals(6 to 1, cycleWindowSpan(CycleWindow.WEEK, today))
        assertEquals(27 to 1, cycleWindowSpan(CycleWindow.MONTH, today))
        // 18 September through 30 November: the rest of this month and the two after it, which is
        // three month marks. Ninety-one days from here would end on 17 December and draw a fourth.
        assertEquals(73 to 1, cycleWindowSpan(CycleWindow.QUARTER, today))
        assertEquals(
            "three spans and no more: at six months there is nothing ahead to draw",
            listOf(CycleWindow.WEEK, CycleWindow.MONTH, CycleWindow.QUARTER),
            CycleWindow.entries,
        )
    }

    /**
     * The quarter is three named months, whatever day of the month it is opened on, and never so
     * long that a column has to hold two days — the axis names months, and a month mark over a
     * column that straddles two of them names neither.
     */
    @Test
    fun theQuarterIsThreeNamedMonthsWhateverDayItOpensOn() {
        var opened = day("2026-01-01")
        repeat(366) {
            val end = cycleWindowEnd(CycleWindow.QUARTER, opened)
            assertEquals("$opened: ends on a month's last day", end.lengthOfMonth(), end.dayOfMonth)
            val months = generateSequence(opened) { it.plusDays(1) }
                .takeWhile { !it.isAfter(end) }
                .map { it.year to it.monthValue }
                .distinct()
                .count()
            assertEquals("$opened: three months", 3, months)
            val (forward, bucket) = cycleWindowSpan(CycleWindow.QUARTER, opened)
            assertEquals("$opened: one day to a column", 1, bucket)
            assertTrue("$opened: fits the plot", forward + 1 <= CHART_MAX_POINTS)
            opened = opened.plusDays(1)
        }
    }

    /**
     * Every column the axis has something to say about keeps its stick, whether or not its words
     * fitted beside its neighbour's.
     *
     * Bound to the labels, the sticks would vanish wherever a label was dropped for room, and a phone
     * with large text would draw a twenty-eight day plot with two lines on it. They are the ruler, and
     * the ruler does not depend on whether there was space to write the numbers.
     */
    @Test
    fun everyColumnTheAxisMarksKeepsItsStick() {
        val today = day("2026-11-20")
        val journal = regularJournal()
        val facts = CycleFacts(journal, cycleSummary(journal, today), today)
        CycleWindow.entries.forEach { window ->
            val chart = facts.chart(facts.phase(), window, acts = true)
            val sticks = chart.stickColumns()
            assertTrue("${window.name}: every tick", sticks.containsAll(chart.ticks))
            assertEquals("${window.name}: in order, once each", sticks.distinct().sorted(), sticks)
            assertTrue("${window.name}: on the plot", sticks.all { it in chart.buckets.indices })
            // Over three months the lines are the month divisions and nothing else, so the plot
            // reads as three bands; anywhere shorter the forecast gets a line of its own too.
            if (window == CycleWindow.QUARTER) {
                assertEquals("${window.name}: the month divisions alone", chart.ticks, sticks)
            } else {
                chart.predictedStart?.let {
                    assertTrue("${window.name}: and the forecast", it in sticks)
                }
            }
        }
    }

    /**
     * Holding a finger on the plot lights the stretch of time under it — its month over a quarter,
     * its week over a month — and says what kind of day it is in the three words the plot is
     * shaded in. The band has to cover every column, or a finger would find a gap.
     */
    @Test
    fun everyColumnBelongsToABandAndHasAKindOfDay() {
        val today = day("2026-11-20")
        val journal = regularJournal()
        val facts = CycleFacts(journal, cycleSummary(journal, today), today)
        val kinds = setOf(
            R.string.cycle_legend_period,
            R.string.cycle_legend_fertile,
            R.string.cycle_phase_ordinary,
        )
        CycleWindow.entries.forEach { window ->
            val chart = facts.chart(facts.phase(), window, acts = true)
            chart.buckets.indices.forEach { index ->
                val band = chart.bandOf(index)
                assertTrue("${window.name} $index: inside its own band", index in band)
                assertTrue("${window.name} $index: on the plot", band.first in chart.buckets.indices)
                assertTrue("${window.name} $index: on the plot", band.last in chart.buckets.indices)
                assertTrue("${window.name} $index: one of the three", chart.dayKind(index) in kinds)
            }
        }
    }

    /**
     * A month name may stand anywhere over its own month, so the run it owns has to end where the
     * next mark begins. Written as "the next tick", not "the next month", so the same rule serves
     * a week and a month without a second one.
     */
    @Test
    fun aMarksRunEndsWhereTheNextOneBegins() {
        val today = day("2026-09-18")
        val journal = regularJournal()
        val facts = CycleFacts(journal, cycleSummary(journal, today), today)
        val chart = facts.chart(facts.phase(), CycleWindow.QUARTER, acts = true)

        assertEquals("three months, three marks", 3, chart.ticks.size)
        chart.ticks.forEachIndexed { at, index ->
            val end = chart.runEnd(index)
            assertTrue("a run reaches at least its own column", end >= index)
            val next = chart.ticks.getOrNull(at + 1)
            if (next != null) {
                assertEquals("it stops one short of the next mark", next - 1, end)
            } else {
                assertEquals("and the last one runs to the edge", chart.buckets.lastIndex, end)
            }
        }
        // September's mark owns the rest of September, so its name has that much room to move in.
        assertEquals(day("2026-09-30"), chart.buckets[chart.runEnd(chart.ticks.first())].start)
    }

    /** Today is never in a column with a day either side of it, or record and forecast would mix. */
    @Test
    fun todayStandsAloneInItsOwnColumn() {
        val today = day("2026-11-20")
        val journal = regularJournal()
        val facts = CycleFacts(journal, cycleSummary(journal, today), today)
        CycleWindow.entries.forEach { window ->
            val chart = facts.chart(facts.phase(), window, acts = true)
            val bucket = chart.buckets[chart.todayIndex]
            assertEquals("${window.name}: today is alone", today, bucket.start)
            assertEquals("${window.name}: today is alone", today, bucket.endInclusive)
            assertTrue(
                "${window.name}: nothing in the window has already happened",
                chart.buckets.none { it.start.isBefore(today) },
            )
            assertTrue(
                "${window.name}: nothing after today sits left of it",
                chart.buckets.drop(chart.todayIndex + 1).all { it.start.isAfter(today) },
            )
        }
    }

    /** The span, not the average, is what the screen prints once the cycles differ. */
    @Test
    fun theSummaryCarriesTheSpanTheAverageWasDrawnFrom() {
        val starts = listOf("2026-04-01", "2026-04-27", "2026-05-29", "2026-06-24")
            .map { entry(KIND_CYCLE, it) }

        val summary = cycleSummary(starts, day("2026-07-01"))

        assertEquals(26, summary.shortestDays)
        assertEquals(32, summary.longestDays)
        assertTrue("six days apart is regular", summary.regular)
    }

    /**
     * A regular record still differs by up to a week, and the cycle running now may be her shortest
     * or her longest: the window opens where the shortest would open it and shuts where the longest
     * would shut it. Counted from the average alone it would read "Low" on four of the six fertile days
     * of her own 32-day cycle — the one reading on the screen that could be taken for a clearance. The
     * ovulation estimate stays one day, counted from the average, and is not the window's end.
     */
    @Test
    fun theFertileWindowIsAsWideAsHerCyclesDiffer() {
        // Gaps of 25, 28, 32 and 28 days, each start a five-day period; the last began on 24 April.
        val journal = listOf("2026-01-01", "2026-01-26", "2026-02-23", "2026-03-27", "2026-04-24").flatMap {
            listOf(entry(KIND_CYCLE, it), entry(KIND_CYCLE_END, day(it).plusDays(4).toString()))
        }
        val last = day("2026-04-24")
        val summary = cycleSummary(journal, last.plusDays(17))
        assertTrue("seven days apart is still regular", summary.regular)
        assertEquals(28, summary.averageLengthDays)

        assertEquals(last.plusDays(25 - 19L)..last.plusDays(32 - 14L), fertileWindow(summary))
        assertEquals(last.plusDays(28 - 14L), estimatedOvulation(summary))

        fun word(offset: Long) = last.plusDays(offset)
            .let { CycleFacts(journal, cycleSummary(journal, it), it).fertility()?.level }
        assertEquals("day 18, fertile in her long cycle", FertilityLevel.HIGH, word(17))
        assertEquals("day 7, fertile in her short one", FertilityLevel.HIGH, word(6))
        assertEquals(FertilityLevel.LOW, word(5))
        assertEquals(FertilityLevel.LOW, word(19))

        // The calendar shades the whole span and rings the one estimated day inside it.
        val today = last.plusDays(17)
        val may = CycleFacts(journal, summary, today).calendarMonth(YearMonth.of(2026, 5), DayOfWeek.MONDAY, false)
        assertEquals(listOf(day("2026-05-08")), may.filter { it.ovulation }.map { it.date })
        assertTrue(may.single { it.date == day("2026-05-12") }.fertile)
        assertFalse(may.single { it.date == day("2026-05-13") }.fertile)
    }

    /**
     * A forecast is drawn only while the prediction it is chained from still stands, and no further
     * than the comparison line is chained. Walking from the predicted start to the end of whatever
     * month was asked for, a record silent since March would draw "Predicted period" in December,
     * nine cycles out, in the same dashes as next week's.
     */
    @Test
    fun theForecastStopsWithThePredictionAndTwoCyclesPastIt() {
        val journal = regularJournal()
        fun forecast(today: LocalDate, days: Long) =
            predictedPeriodDays(cycleSummary(journal, today), today, today.plusDays(days))

        // 7 December, 4 January and 1 February, five days each, and nothing after them.
        val ahead = forecast(day("2026-11-20"), 365)
        assertEquals(15, ahead.size)
        assertEquals(day("2026-12-07"), ahead.min())
        assertEquals(day("2027-02-05"), ahead.max())

        assertTrue("six days late it still stands", forecast(day("2026-12-13"), 90).isNotEmpty())
        assertTrue("a week late it does not", forecast(day("2026-12-14"), 90).isEmpty())
        assertTrue("nor on a record gone quiet", forecast(day("2026-11-09").plusDays(250), 90).isEmpty())
    }

    /**
     * The month is six whole weeks laid from the locale's first day: the 1st stands in its own
     * column, a month that opens on that day gets no blank week ahead of it, and the days of the
     * next month the grid shows carry the forecast, apart from the period she recorded.
     */
    @Test
    fun theMonthIsSixWeeksFromTheLocalesFirstDay() {
        val today = day("2026-11-20")
        val journal = regularJournal()
        val facts = CycleFacts(journal, cycleSummary(journal, today), today)
        fun grid(first: DayOfWeek) = facts.calendarMonth(YearMonth.of(2026, 11), first, acts = false)

        // 1 November 2026 is a Sunday.
        mapOf(
            DayOfWeek.MONDAY to day("2026-10-26"),
            DayOfWeek.SUNDAY to day("2026-11-01"),
            DayOfWeek.SATURDAY to day("2026-10-31"),
        ).forEach { (first, opens) ->
            assertEquals(first.name, (0L until 42L).map { opens.plusDays(it) }, grid(first).map { it.date })
        }

        val sunday = grid(DayOfWeek.SUNDAY)
        val recorded = (0L..4L).map { day("2026-11-09").plusDays(it) }
        assertEquals(recorded, sunday.filter { it.bleeding }.map { it.date })
        assertEquals((0L..4L).map { day("2026-12-07").plusDays(it) }, sunday.filter { it.predicted }.map { it.date })
    }

    /**
     * Spotting between periods is bleeding and is not a day of a period. Were every recorded bleeding
     * day called "Period" on the calendar, the week strip and the plot, the one thing a clinician
     * asks about apart from periods would be drawn, and read out, as one of them.
     */
    @Test
    fun spottingBetweenPeriodsIsNotCalledAPeriod() {
        val today = day("2026-11-20")
        // One on a day between periods, and one on a day of the period that ran 9 to 13 November.
        val journal = regularJournal() + log("2026-11-20", CycleLog.SPOTTING) + log("2026-11-10", CycleLog.SPOTTING)
        val facts = CycleFacts(journal, cycleSummary(journal, today), today)
        val month = facts.calendarMonth(YearMonth.of(2026, 11), DayOfWeek.MONDAY, acts = false)
            .associateBy { it.date }

        val between = month.getValue(today)
        assertTrue("it is bleeding she recorded", between.bleeding)
        assertTrue(between.spotting)
        assertEquals(R.string.cycle_spotting, bleedingWord(between.bleeding, between.spotting))

        val inside = month.getValue(day("2026-11-10"))
        assertFalse("a day of a period is a day of the period", inside.spotting)
        assertEquals(R.string.cycle_legend_period, bleedingWord(inside.bleeding, inside.spotting))
        assertNull(bleedingWord(bleeding = false, spotting = false))

        // And the plot, whose ribbon is named "Period", does not shade today as one.
        val chart = facts.chart(facts.phase(), CycleWindow.WEEK, acts = true)
        assertTrue(chart.bleeding.isEmpty())
        assertFalse(chart.dayKind(chart.todayIndex) == R.string.cycle_legend_period)
    }

    /** What is said aloud about a curve is read back off its height, so every rung must come back as itself. */
    @Test
    fun aRungComesBackFromItsOwnHeight() {
        CycleScale.entries.forEach { scale ->
            scale.logs.indices.forEach { index ->
                assertEquals("${scale.name} rung ${index + 1}", index + 1, scale.rungAt(scale.heightOf(index + 1)))
            }
        }
        assertNull("no flow is no rung of flow", CycleScale.FLOW.rungAt(0.0))
        assertNull(CycleScale.MUCUS.rungAt(0.0))
        assertEquals("the floor of pain is a rung of its own", 1, CycleScale.PAIN.rungAt(0.0))
    }

    /**
     * The plot's description names each rung as itself — never "Mild" as "No pain", a nought of flow
     * as "Light", or the peak of the fertility estimate as "Egg white", a mucus nobody recorded — and
     * never counts a day she never answered as a day recorded.
     */
    @Test
    fun aCurveIsSpokenByTheRungThatWasDrawn() {
        val mild = CycleSeries(CycleScale.PAIN, CycleSeriesRole.RECORDED, listOf(CycleScale.PAIN.heightOf(2), null))
        assertEquals(SeriesReading(answered = 1, peak = CycleLog.PAIN_MILD, peakIndex = 0), mild.spokenReading())

        // A day of a cycle with no flow written on it is a measured nought, and nothing she answered.
        val dry = CycleSeries(CycleScale.FLOW, CycleSeriesRole.RECORDED, listOf(0.0, 0.0), listOf(true, false))
        assertNull(dry.spokenReading())

        // The same day ahead of a forecast period: its peak is named, and none of it is hers.
        val heights = listOf(0.0, 1.0, 2.0 / 3)
        val ahead = CycleSeries(CycleScale.FLOW, CycleSeriesRole.RECORDED, heights, listOf(true, false, false))
        assertEquals(SeriesReading(answered = 0, peak = CycleLog.FLOW_HEAVY, peakIndex = 1), ahead.spokenReading())

        val estimate = CycleSeries(CycleScale.MUCUS, CycleSeriesRole.ESTIMATE, listOf(0.2, 1.0), listOf(false, false))
        assertNull("nobody recorded the estimate, and its peak is no mucus", estimate.spokenReading())
    }
}
