package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The arithmetic behind a report chart, read without a device: the ticks a doctor reads the
 * figures against, where a day and a figure land, where a line breaks, and where the labels go.
 */
class ReportChartGeometryTest {
    private val day: LocalDate = LocalDate.of(2026, 9, 24)

    // --- the value axis ----------------------------------------------------------------------------

    private fun assertLadder(scale: ValueScale, what: String) {
        assertTrue("$what: ${scale.ticks.size} ticks", scale.ticks.size in 3..5)
        val step = scale.ticks[1] - scale.ticks[0]
        scale.ticks.zipWithNext { a, b -> assertEquals("$what: uneven", step, b - a, step * 1e-6) }
        val mantissa = step / 10.0.pow(floor(log10(step) + 1e-9))
        assertTrue("$what: a step of $step", listOf(1.0, 2.0, 2.5, 5.0).any { abs(it - mantissa) < 1e-6 })
        scale.ticks.forEach { assertTrue("$what: $it off the axis", it >= scale.min - 1e-9 && it <= scale.max + 1e-9) }
    }

    /** Over spreads from hundredths to hundreds of thousands, three to five ticks on the 1-2-2.5-5 ladder. */
    @Test
    fun theTicksClimbInStepsAReaderAddsUp() {
        listOf(0.03 to 0.06, 4.87 to 5.53, 5.3 to 6.0, 1.0 to 9.0, 60.0 to 190.0, 6_041.0 to 10_400.0, 0.0 to 285_000.0)
            .forEach { (low, high) ->
                val scale = valueScale(listOf(low, high))
                assertLadder(scale, "$low..$high")
                assertTrue(scale.min <= low && scale.max >= high)
            }
        (1..400).forEach { seed ->
            val low = (seed * 37 % 1000) / 7.0
            val high = low + (seed * 53 % 997 + 1) / 3.0.pow(seed % 5)
            assertLadder(valueScale(listOf(low, high)), "$low..$high")
        }
    }

    /** A scale with ends of its own keeps them: mood from 1 to 5, a share from 0 to 100. */
    @Test
    fun fixedEndsAreKept() {
        val mood = valueScale(listOf(2.0, 4.0), yFloor = 1.0, yCeiling = 5.0)
        assertEquals(1.0, mood.min, 0.0)
        assertEquals(5.0, mood.max, 0.0)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0), mood.ticks)

        val share = valueScale(listOf(95.0), yFloor = 0.0, yCeiling = 100.0)
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0), share.ticks)
    }

    /** A figure past a fixed end moves that end out to it, the other end holding; a band alone moves neither. */
    @Test
    fun aFigurePastAFixedEndMovesIt() {
        val over = valueScale(listOf(95.0, 120.0), yFloor = 0.0, yCeiling = 100.0)
        assertEquals(0.0, over.min, 0.0)
        assertTrue("a top of ${over.max}", over.max >= 120.0)
        assertLadder(over, "past the ceiling")

        val banded = valueScale(listOf(2.0, 4.0), band = ChartBand(0.0, 6.0), yFloor = 1.0, yCeiling = 5.0)
        assertEquals(1.0, banded.min, 0.0)
        assertEquals(5.0, banded.max, 0.0)
    }

    /** 80.0 to 80.6 kg is not stretched over the whole plot; a spread wider than the floor is fitted to itself. */
    @Test
    fun aSmallChangeOnALargeFigureIsNotDrawnAsAClimb() {
        val weight = valueScale(listOf(80.0, 80.6))
        assertTrue("${weight.min}..${weight.max}", 0.6 / (weight.max - weight.min) < 0.1)
        assertTrue(weight.min <= 80.0 && weight.max >= 80.6)
        assertLadder(weight, "weight")
        val steps = valueScale(listOf(6_041.0, 10_400.0))
        assertTrue("${steps.min}..${steps.max}", (10_400.0 - 6_041.0) / (steps.max - steps.min) > 0.8)
    }

    /** A year of steps is drawn over its own spread; bars are given zero to stand on. */
    @Test
    fun onlyBarsAreDrawnFromZero() {
        val line = valueScale(listOf(6_041.0, 10_400.0))
        assertTrue("a line of steps drawn from ${line.min}", line.min > 5_000.0)
        val bars = valueScale(listOf(6_041.0, 10_400.0), fromZero = true)
        assertEquals(0.0, bars.min, 0.0)
        assertLadder(bars, "bars")
    }

    /** Counts never get a negative axis, however much air they are given. */
    @Test
    fun figuresOfZeroOrMoreNeverGetANegativeAxis() {
        assertEquals(0.0, valueScale(listOf(100.0, 10_000.0)).min, 0.0)
        assertEquals(0.0, valueScale(listOf(0.0)).min, 0.0)
        assertTrue(valueScale(listOf(-2.0, 3.0)).min < -2.0)
    }

    /** The band is on the chart even where every figure sits inside it. */
    @Test
    fun theBandFitsOnTheAxis() {
        val scale = valueScale(listOf(4.8, 5.2), band = ChartBand(3.9, 5.5))
        assertTrue(scale.min <= 3.9 && scale.max >= 5.5)
        val oneSided = valueScale(listOf(2.1, 2.4), band = ChartBand(null, 3.0))
        assertTrue(oneSided.max >= 3.0)
    }

    /** One reading still gets an axis with room on both sides of it. */
    @Test
    fun aSingleFigureStillGetsAnAxis() {
        val scale = valueScale(listOf(5.3))
        assertTrue(scale.min < 5.3 && scale.max > 5.3)
        assertLadder(scale, "one figure")
    }

    @Test
    fun ticksArePrintedTheWayTheSheetPrintsFigures() {
        assertEquals(
            listOf("0", "2,500", "5,000", "7,500", "10,000"),
            valueLabels(listOf(0.0, 2_500.0, 5_000.0, 7_500.0, 10_000.0), Locale.ENGLISH),
        )
        // Decimals with a point whatever the language, as the sheet prints a laboratory figure.
        assertEquals(listOf("5", "5.5", "6"), valueLabels(listOf(5.0, 5.5, 6.0), Locale.GERMAN))
        assertEquals(listOf("0.1", "0.2", "0.3"), valueLabels(listOf(0.1, 0.2, 0.1 * 3), Locale.ENGLISH))
    }

    // --- the time axis -----------------------------------------------------------------------------

    @Test
    fun aQuarterIsMarkedInDaysAndWeeks() {
        val from = day.minusMonths(3)
        val choices = dateTickChoices(from, day)
        assertTrue(choices.all { it.unit == DateUnit.DAY })
        choices.forEach { ticks -> assertTrue(ticks.days.all { it in from..day }) }
        val weekly = choices.filter { ticks -> ticks.days.zipWithNext { a, b -> a.plusDays(7) <= b }.all { it } }
        assertTrue(weekly.isNotEmpty())
        weekly.forEach { ticks -> assertTrue(ticks.days.all { it.dayOfWeek == DayOfWeek.MONDAY }) }
    }

    @Test
    fun upToTwoYearsIsMarkedInMonths() {
        listOf(day.minusMonths(6), day.minusYears(1), day.minusYears(2)).forEach { from ->
            val choices = dateTickChoices(from, day)
            assertTrue("$from", choices.all { it.unit == DateUnit.MONTH })
            choices.forEach { ticks -> assertTrue(ticks.days.all { it.dayOfMonth == 1 && it in from..day }) }
        }
        // Every third month falls on the quarters a calendar has, whichever month the period starts in.
        val quarters = dateTickChoices(day.minusYears(1), day)[2]
        assertEquals(setOf(1, 4, 7, 10), quarters.days.map { it.monthValue }.toSet())
    }

    @Test
    fun beyondTwoYearsIsMarkedInYears() {
        val from = day.minusYears(5)
        val choices = dateTickChoices(from, day)
        assertTrue(choices.all { it.unit == DateUnit.YEAR })
        assertEquals((2022..2026).map { LocalDate.of(it, 1, 1) }, choices.first().days)
    }

    @Test
    fun aMonthCarriesItsYearOnTheFirstTickAndInJanuary() {
        val ticks = DateTicks(DateUnit.MONTH, (0L..3L).map { LocalDate.of(2025, 11, 1).plusMonths(it) })
        assertEquals(
            listOf("Nov 2025", "Dec", "Jan 2026", "Feb"),
            dateTickLabels(ticks, Locale.ENGLISH, InternationalProfile.datePattern),
        )
        assertEquals(
            listOf("2025"),
            dateTickLabels(DateTicks(DateUnit.YEAR, listOf(LocalDate.of(2025, 1, 1))), Locale.ENGLISH, ""),
        )
    }

    /** A month on its own is named in its own form: "май", not the "мая" of "7 мая". */
    @Test
    fun aMonthOnTheAxisIsNamedOnItsOwn() {
        val spring = DateTicks(DateUnit.MONTH, (0L..4L).map { LocalDate.of(2026, 3, 1).plusMonths(it) })
        assertEquals(
            listOf("март 2026", "апр.", "май", "июнь", "июль"),
            dateTickLabels(spring, Locale.forLanguageTag("ru"), InternationalProfile.datePattern),
        )
        assertEquals(
            listOf("май 2026"),
            dateTickLabels(
                DateTicks(DateUnit.MONTH, listOf(LocalDate.of(2026, 5, 1))),
                Locale.forLanguageTag("ru"),
                InternationalProfile.datePattern,
            ),
        )
    }

    /** A day on the axis is the sheet's own date without its year, in the sheet's own order. */
    @Test
    fun aDayOnTheAxisIsTheSheetsDateWithoutItsYear() {
        val seventh = LocalDate.of(2026, 9, 7)
        val monday = DateTicks(DateUnit.DAY, listOf(seventh))
        assertEquals(listOf("7 Sep"), dateTickLabels(monday, Locale.ENGLISH, InternationalProfile.datePattern))
        assertEquals(listOf("Sep 7"), dateTickLabels(monday, Locale.ENGLISH, UnitedStatesProfile.datePattern))
        assertEquals(listOf("07.09"), dateTickLabels(monday, Locale.GERMAN, GermanyProfile.datePattern))
        CountryProfileRegistry.all.forEach { profile ->
            val label = seventh.format(DateTimeFormatter.ofPattern(shortDayPattern(profile.datePattern)))
            assertFalse("${profile.id}: $label", "2026" in label)
            assertTrue("${profile.id}: $label", '7' in label)
        }
    }

    // --- from figures to the page ------------------------------------------------------------------

    @Test
    fun eachDayIsASlotAndTimeRunsLeftToRight() {
        val from = day.minusDays(9)
        assertEquals(5f, dayX(from, from, day, 0f, 100f), 1e-4f)
        assertEquals(95f, dayX(day, from, day, 0f, 100f), 1e-4f)
        assertEquals(50f, dayX(day, day, day, 0f, 100f), 1e-4f)
        val scale = ValueScale(0.0, 10.0, listOf(0.0, 5.0, 10.0))
        assertEquals(100f, valueY(0.0, scale, 0f, 100f), 1e-4f)
        assertEquals(0f, valueY(10.0, scale, 0f, 100f), 1e-4f)
    }

    /** A quarter nobody measured is a hole in the line, not a stroke across it. */
    @Test
    fun aMissingStretchBreaksTheLine() {
        val monthly = listOf(1, 2, 3, 7, 8, 9).map { LocalDate.of(2026, it, 1) }
        assertEquals(listOf(0..2, 3..5), lineRuns(monthly))
        val daily = (0L..30L).map { day.minusDays(it) }.reversed()
        assertEquals(listOf(0..30), lineRuns(daily))
        assertEquals(listOf(0..1), lineRuns(listOf(day.minusYears(2), day)))
        assertEquals(emptyList<IntRange>(), lineRuns(emptyList()))
        // Weights on two days in a row and five months later: the long gap cannot lift its own
        // threshold past itself, however few or evenly split the gaps are.
        assertEquals(listOf(0..1, 2..2), lineRuns(listOf(day, day.plusDays(1), day.plusDays(151))))
        assertEquals(listOf(0..2, 3..3, 4..4), lineRuns(listOf(0L, 1L, 2L, 92L, 182L).map(day::plusDays)))
    }

    /** Two readings on one morning are no gap; counting them would break the line at every day. */
    @Test
    fun readingsOnOneDayDoNotBreakTheLine() {
        val days = listOf(day, day, day.plusDays(1), day.plusDays(1), day.plusDays(2))
        assertEquals(listOf(0..4), lineRuns(days))
    }

    @Test
    fun barsTakeMostOfTheRoomBetweenThemAndShareIt() {
        val daily = (0L..364L).map { day.minusDays(it) }.reversed()
        assertEquals(1.4f * 0.7f, barWidth(daily, 1.4f, 1), 1e-4f)
        assertEquals(0.8f, barWidth(daily, 1.4f, 2), 1e-4f)
        val monthly = (0L..11L).map { day.minusMonths(it) }.reversed()
        assertEquals(18f, barWidth(monthly, 1.4f, 1), 1e-4f)
    }

    // --- labels ------------------------------------------------------------------------------------

    private val bounds = ChartBox(0f, 0f, 200f, 100f)

    private fun box(left: Float, top: Float) = ChartBox(left, top, left + 20f, top + 9f)

    /** A second label on the same spot goes below, a third a line higher, a fourth nowhere. */
    @Test
    fun aLabelThatWouldCoverAnotherMovesOrIsLeftOff() {
        val places = listOf(box(50f, 40f), box(50f, 53f), box(50f, 31f))
        val placed = placeLabels(List(4) { places }, bounds)
        assertEquals(places, placed.take(3))
        assertNull(placed[3])
    }

    @Test
    fun aLabelIsNeverPlacedOutsideItsChart() {
        val placed = placeLabels(listOf(listOf(box(-5f, 40f), box(10f, 40f))), bounds)
        assertEquals(box(10f, 40f), placed.single())
        assertNull(placeLabels(listOf(listOf(box(0f, -3f))), bounds).single())
    }

    @Test
    fun aLabelAtTheEdgeMovesInRatherThanHangingOut() {
        assertEquals(0f, centredBox(4f, 20f, 0f, 9f, 0f, 200f).left, 0f)
        assertEquals(200f, centredBox(198f, 20f, 0f, 9f, 0f, 200f).right, 0f)
        assertEquals(90f, centredBox(100f, 20f, 0f, 9f, 0f, 200f).left, 0f)
    }

    @Test
    fun theLegendWrapsWhereALineIsFull() {
        assertEquals(listOf(0 to 0f, 0 to 41f, 1 to 0f), flowLegend(listOf(20f, 20f, 20f), 100f))
        assertEquals(listOf(0 to 0f), flowLegend(listOf(500f), 100f))
    }

    /** A label wrapped over two lines takes a line of its own, and the next entry starts below it. */
    @Test
    fun aWrappedLegendLabelTakesItsOwnLines() {
        assertEquals(
            listOf(0 to 0f, 1 to 0f, 3 to 0f, 3 to 41f),
            flowLegend(listOf(20f, 80f, 20f, 20f), 100f, listOf(1, 2, 1, 1)),
        )
    }

    // --- which charts are drawn, and where ----------------------------------------------------------

    private fun chart(
        vararg series: ChartSeries,
        from: LocalDate = day.minusMonths(6),
        width: ChartWidth = ChartWidth.FULL,
        band: ChartBand? = null,
        title: ReportText? = null,
        marks: List<ChartMark> = emptyList(),
    ) = ReportChart(
        series = series.toList(),
        unit = ReportText.Plain("mmol/L"),
        from = from,
        to = day,
        title = title,
        band = band,
        marks = marks,
        width = width,
    )

    private fun line(
        vararg values: Pair<LocalDate, Double>,
        style: ChartStyle = ChartStyle.LINE,
        label: String? = null,
    ) = ChartSeries(values.map { ChartPoint(it.first, it.second) }, style, label?.let(ReportText::Plain))

    @Test
    fun halfChartsPairUpAndAnOddOneStandsAlone() {
        val half = chart(line(day to 1.0), width = ChartWidth.HALF)
        val full = chart(line(day to 1.0))
        assertEquals(
            listOf(listOf(half, half), listOf(half), listOf(full), listOf(half)),
            chartRows(listOf(half, half, half, full, half)),
        )
    }

    @Test
    fun aChartWithNothingInItsWindowHasNothingToDraw() {
        assertFalse(chart(line(day.minusYears(1) to 5.0)).drawable())
        assertFalse(chart(line(day to Double.NaN)).drawable())
        assertFalse(chart(ChartSeries(emptyList(), ChartStyle.LINE)).drawable())
        assertTrue(chart(line(day.minusYears(1) to 5.0, day to 5.1)).drawable())
    }

    // --- a whole chart ------------------------------------------------------------------------------

    private val measure: (String, SheetType) -> Float = { line, type -> line.length * type.size * 0.5f }
    private val chartBox = ChartBox(40f, 100f, 555f, 250f)

    private fun draft(chart: ReportChart, rtl: Boolean = false, box: ChartBox = chartBox): ChartOp? =
        ChartDrafter({ (it as ReportText.Plain).text }, measure, Locale.ENGLISH, InternationalProfile.datePattern, rtl)
            .draft(chart, box)

    private fun ChartText.box(): ChartBox = ChartBox(x, y - 7f, x + measure(text, type), y + 2f)

    @Test
    fun aChartWithNoPointsIsNotDrawn() {
        assertNull(draft(chart(ChartSeries(emptyList(), ChartStyle.LINE))))
        assertNotNull(draft(chart(line(day to 5.0))))
    }

    /** "Below 3.0" is shaded from 3.0 down to the bottom of the plot; "above 1.0" up to its top. */
    @Test
    fun aOneSidedBandShadesToThePlotEdge() {
        val points = line(day.minusDays(30) to 2.1, day to 2.6)
        val below = draft(chart(points, band = ChartBand(null, 3.0)))!!
        assertEquals(below.plot.bottom, below.band!!.bottom, 0f)
        assertEquals(below.bandEdges.single(), below.band.top, 1e-3f)
        val above = draft(chart(points, band = ChartBand(1.0, null)))!!
        assertEquals(above.plot.top, above.band!!.top, 0f)
        assertEquals(above.bandEdges.single(), above.band.bottom, 1e-3f)
        assertNull(draft(chart(points))!!.band)
    }

    @Test
    fun aLegendIsDrawnOnlyWhereTwoSeriesHaveNames() {
        val systolic = line(day.minusDays(3) to 128.0, day to 131.0, label = "Systolic")
        val diastolic = line(day.minusDays(3) to 82.0, day to 85.0, style = ChartStyle.LINE_DOTS, label = "Diastolic")
        assertTrue(draft(chart(systolic))!!.swatches.isEmpty())
        val both = draft(chart(systolic, diastolic))!!
        assertEquals(listOf(0 to ChartStyle.LINE, 1 to ChartStyle.LINE_DOTS), both.swatches.map { it.ink to it.style })
        assertTrue(both.texts.map(ChartText::text).containsAll(listOf("Systolic", "Diastolic")))
        both.swatches.forEach { assertTrue(it.box.bottom <= both.plot.top) }
    }

    /** The months with no reading leave a hole; the lone reading between two holes is still a dot. */
    @Test
    fun aGapBreaksTheLineAndALoneFigureStaysVisible() {
        val points = line(
            LocalDate.of(2026, 4, 1) to 5.4,
            LocalDate.of(2026, 5, 1) to 5.5,
            LocalDate.of(2026, 6, 1) to 5.6,
            LocalDate.of(2026, 8, 20) to 5.9,
        )
        val trace = draft(chart(points))!!.traces.single()
        assertEquals(listOf(3), trace.runs.map { it.size })
        assertEquals(1, trace.dots.size)
        assertTrue(trace.dots.single().x > trace.runs.single().last().x)
    }

    /** A share of 120 on a chart that stops at 100 is drawn, dot and label, inside the plot. */
    @Test
    fun aFigurePastAFixedEndIsDrawnInsideThePlot() {
        val points = listOf(ChartPoint(day.minusDays(10), 95.0), ChartPoint(day, 120.0, label = "120"))
        val op = draft(chart(ChartSeries(points, ChartStyle.LINE_DOTS)).copy(yFloor = 0.0, yCeiling = 100.0))!!
        val dots = op.traces.single().dots
        assertEquals(2, dots.size)
        dots.forEach { assertTrue("$it outside ${op.plot}", it.y >= op.plot.top && it.y <= op.plot.bottom) }
        val label = op.texts.single { it.text == "120" }.box()
        assertTrue("$label is not over its dot", dots.last().x in label.left..label.right)
    }

    @Test
    fun barsStandOnTheBottomOfThePlot() {
        val steps = ChartSeries((0L..20L).map { ChartPoint(day.minusDays(it), 6_000.0 + it * 100) }, ChartStyle.BARS)
        val op = draft(chart(steps, from = day.minusDays(20)))!!
        val bars = op.traces.single().bars
        assertEquals(21, bars.size)
        bars.forEach { assertEquals(op.plot.bottom, it.bottom, 1e-3f) }
        bars.zipWithNext { a, b -> assertTrue("bars touch", a.right <= b.left) }
    }

    @Test
    fun marksAreDrawnInsideTheirWindowOnly() {
        val op = draft(
            chart(
                line(day.minusDays(60) to 108.0, day to 127.0),
                marks = listOf(
                    ChartMark(day.minusDays(40), ReportText.Plain("Iron")),
                    ChartMark(day.minusYears(2), ReportText.Plain("Earlier")),
                ),
            ),
        )!!
        assertEquals(1, op.marks.size)
        assertTrue("Iron" in op.texts.map(ChartText::text))
        assertFalse("Earlier" in op.texts.map(ChartText::text))
    }

    /** Close readings with their figures printed: none printed over another, none outside the chart. */
    @Test
    fun noLabelCoversAnother() {
        val points = (0L..11L).map { ChartPoint(day.minusDays(it * 3), 4.0 + (it % 3) * 0.05, label = "4.${it % 3}") }
        val op = draft(
            chart(ChartSeries(points, ChartStyle.LINE_DOTS), from = day.minusDays(40), title = ReportText.Plain("LDL")),
        )!!
        val boxes = op.texts.map { it.box() }
        boxes.forEachIndexed { index, box ->
            assertTrue(
                "${op.texts[index].text} outside",
                box.left >= op.box.left - 1e-3f && box.right <= op.box.right + 1e-3f,
            )
            boxes.drop(index + 1).forEach { other ->
                assertFalse("${op.texts[index].text} covers another at ${other.left}", box.overlaps(other))
            }
        }
    }

    /** A year on a half-width chart: month labels that fit side by side rather than every month crushed together. */
    @Test
    fun theDatesUnderAHalfChartFitSideBySide() {
        val points = (0L..364L step 7).map { day.minusDays(it) to 5.0 + it / 100.0 }.toTypedArray()
        val half = ChartBox(40f, 100f, 290f, 230f)
        val op = draft(chart(line(*points), from = day.minusYears(1), width = ChartWidth.HALF), box = half)!!
        val dates = op.texts.filter { it.y > op.plot.bottom }
        assertTrue("only ${dates.size} dates", dates.size >= 3)
        dates.zipWithNext { a, b -> assertTrue("${a.text} runs into ${b.text}", a.box().right < b.box().left) }
        assertEquals(dates.size, op.ticks.size)
    }

    /** A right-to-left sheet moves the title to the right; the plot, and the time in it, stay as they are. */
    @Test
    fun rightToLeftMovesTheTitleButNotTime() {
        val chart = chart(line(day.minusDays(90) to 5.0, day to 6.0), title = ReportText.Plain("HbA1c"))
        val ltr = draft(chart)!!
        val rtl = draft(chart, rtl = true)!!
        assertEquals(ltr.plot, rtl.plot)
        assertEquals(ltr.traces, rtl.traces)
        assertEquals(ltr.ticks, rtl.ticks)
        val left = ltr.texts.single { it.text == "HbA1c" }
        val right = rtl.texts.single { it.text == "HbA1c" }
        assertEquals(chartBox.left, left.x, 0f)
        assertEquals(chartBox.right, right.box().right, 1e-3f)
        assertTrue(rtl.texts.single { it.text == "mmol/L" }.box().right < right.x)
    }

    /** The unit is printed once, at the top, beside the title or alone where there is none. */
    @Test
    fun theUnitIsPrintedOnceAtTheTop() {
        val untitled = draft(chart(line(day to 5.0)))!!
        val unit = untitled.texts.single { it.text == "mmol/L" }
        assertEquals(chartBox.left, unit.x, 0f)
        assertTrue(unit.y < untitled.plot.top)
    }

    /** A unit too long for the line wraps under the title inside the chart, and the plot starts below it. */
    @Test
    fun aLongUnitWrapsInsideTheChart() {
        val phrase = "per cent of the scheduled doses marked each week, as a longer language puts it"
        val half = ChartBox(40f, 100f, 290f, 250f)
        val chart = chart(line(day.minusDays(30) to 50.0, day to 80.0), title = ReportText.Plain("Vitamin D3"))
            .copy(unit = ReportText.Plain(phrase))
        val op = draft(chart, box = half)!!
        val unit = op.texts.filter { it.type == SheetType.NOTE && it.y < op.plot.top }
        assertTrue("${unit.size} lines", unit.size >= 2)
        unit.forEach { assertTrue("${it.text} runs out of the chart", it.box().right <= half.right + 1e-3f) }
        assertTrue(unit.all { it.y < op.plot.top })
        assertEquals(phrase.replace(" ", ""), unit.joinToString("") { it.text.replace(" ", "") })
    }
}
