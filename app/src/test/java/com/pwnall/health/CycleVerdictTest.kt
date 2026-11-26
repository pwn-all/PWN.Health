package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The three answers the cycle screen gives about today, and the one rule under all of them: a
 * reading the record does not support is not given.
 *
 * Every test here is a claim about a woman's body. The ones that matter most are the refusals —
 * that an empty journal produces no fertility word at all, that one tap on a phone with no history
 * produces none either, and that the comparison against her own record never turns into a verdict
 * on her health.
 */
class CycleVerdictTest {

    private fun day(text: String) = LocalDate.parse(text)

    private fun entry(kind: String, day: String) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = kind,
        calories = 0.0,
    )

    private fun log(day: String, log: CycleLog, at: Long = 0L) = JournalEntry(
        day = day,
        occurredAt = at,
        kind = KIND_CYCLE_LOG,
        title = log.name,
        calories = 0.0,
        measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"${log.name}"}""",
    )

    /** Twelve 28-day cycles, each a five-day period, ending with a start on 9 November. */
    private fun regular(length: Long = 4) = (0 until 12).flatMap { index ->
        val start = day("2026-01-05").plusDays(28L * index)
        listOf(entry(KIND_CYCLE, start.toString()), entry(KIND_CYCLE_END, start.plusDays(length).toString()))
    }

    private fun facts(journal: List<JournalEntry>, today: LocalDate) =
        CycleFacts(journal, cycleSummary(journal, today), today)

    // ---- fertility: what is refused --------------------------------------------------------

    /**
     * The single most important refusal in the file. "Low" is a measured band meaning "outside the
     * estimated window"; a woman with no history shown "your fertility is low" has been handed a
     * reading this app does not have.
     */
    @Test
    fun anEmptyRecordGetsNoFertilityWordAtAll() {
        assertNull(facts(emptyList(), day("2026-11-20")).fertility())
    }

    /** And one observation on a phone that knows nothing else is not a reading either. */
    @Test
    fun oneMucusTapWithNoHistoryIsStillNoReading() {
        val today = day("2026-11-20")
        val journal = listOf(log(today.toString(), CycleLog.MUCUS_DRY))

        assertNull(
            "dry mucus alone must not manufacture a fertility level",
            facts(journal, today).fertility(),
        )
        assertNull(fertilityFromSigns(setOf(CycleLog.MUCUS_DRY), emptySet()))
        assertNull(fertilityFromSigns(setOf(CycleLog.MUCUS_STICKY), emptySet()))
    }

    /**
     * A calendar counted back from a predicted start cannot name the peak day of a window it can
     * barely place — Wilcox, Dunson & Baird, BMJ 2000: only about three women in ten have their
     * window where the calendar puts it. Dates say in-window or out, and nothing finer.
     */
    @Test
    fun theCalendarNeverNamesAPeakDay() {
        val window = day("2026-11-18")..day("2026-11-23")

        assertEquals(FertilityLevel.HIGH, fertilityFromDates(window, day("2026-11-23")))
        assertEquals(FertilityLevel.HIGH, fertilityFromDates(window, day("2026-11-18")))
        assertEquals(FertilityLevel.LOW, fertilityFromDates(window, day("2026-11-24")))
        assertNull(fertilityFromDates(null, day("2026-11-23")))
        assertTrue(
            "no date ever produces PEAK",
            (0L..40L).map { fertilityFromDates(window, day("2026-11-01").plusDays(it)) }
                .none { it == FertilityLevel.PEAK },
        )
    }

    /** The estimate expires when the prediction it was counted back from is overtaken by events. */
    @Test
    fun aLatePeriodTakesTheCalendarEstimateAway() {
        val journal = regular()
        val inWindow = day("2026-11-20")
        assertEquals(FertilityLevel.HIGH, facts(journal, inWindow).fertility()?.level)

        // 7 December was the predicted start. A week past it the calendar has nothing left to say.
        val late = day("2026-12-15")
        assertNull(freshFertileWindow(cycleSummary(journal, late), late))
        assertNull(facts(journal, late).fertility())
    }

    /** A surge is the one observation that names the peak, and a second strip does not unname it. */
    @Test
    fun signsRaiseTheWordAndANegativeTestOnlyCapsMucus() {
        assertEquals(
            FertilityLevel.PEAK,
            fertilityFromSigns(setOf(CycleLog.OVULATION_NEGATIVE), setOf(CycleLog.OVULATION_POSITIVE)),
        )
        assertEquals(FertilityLevel.PEAK, fertilityFromSigns(setOf(CycleLog.MUCUS_EGG_WHITE), emptySet()))
        assertEquals(
            "a negative test does not confirm an imminent surge, so the mucus peak is capped",
            FertilityLevel.HIGH,
            fertilityFromSigns(setOf(CycleLog.MUCUS_EGG_WHITE, CycleLog.OVULATION_NEGATIVE), emptySet()),
        )
        assertEquals(FertilityLevel.HIGH, fertilityFromSigns(setOf(CycleLog.MUCUS_WATERY), emptySet()))
        assertEquals(FertilityLevel.RISING, fertilityFromSigns(setOf(CycleLog.MUCUS_CREAMY), emptySet()))
    }

    /** The two readings combine by taking the higher: understating is the half with a consequence. */
    @Test
    fun aSignRaisesAWordTheDatesCallLow() {
        val today = day("2026-11-27")
        val journal = regular() + log(today.toString(), CycleLog.MUCUS_EGG_WHITE)
        val reading = facts(journal, today).fertility()

        assertEquals(FertilityLevel.PEAK, reading?.level)
        assertEquals(FertilityLevel.LOW, reading?.fromDates)
        assertEquals(FertilityLevel.PEAK, reading?.fromSigns)
    }

    /**
     * The page says under the word that it is an estimate from dates and not a method of
     * contraception wherever the word printed is the calendar's — "Low" above all, the one reading
     * that could be taken for a clearance — and not over something she saw for herself.
     */
    @Test
    fun theWordIsCaptionedAsAnEstimateWhereTheCalendarSpoke() {
        val outside = day("2026-11-27")
        assertEquals(true, facts(regular(), outside).fertility()?.estimated)
        val seen = regular() + log(outside.toString(), CycleLog.MUCUS_EGG_WHITE)
        assertEquals("egg white outranks the dates", false, facts(seen, outside).fertility()?.estimated)

        // Inside the window a lesser sign does not change whose word it is.
        val inside = day("2026-11-20")
        val creamy = regular() + log(inside.toString(), CycleLog.MUCUS_CREAMY)
        assertEquals(FertilityLevel.HIGH, facts(creamy, inside).fertility()?.level)
        assertEquals(true, facts(creamy, inside).fertility()?.estimated)
    }

    // ---- the phase -------------------------------------------------------------------------

    /** Bleeding wins any overlap: a short cycle puts an estimated window on top of a period. */
    @Test
    fun bleedingOutranksAnOverlappingFertileWindow() {
        // Twenty-day cycles, the shortest the estimate still answers for: the window counted back
        // fourteen days opens on the second day, inside the bleeding.
        val journal = (0 until 6).flatMap { index ->
            val start = day("2026-06-01").plusDays(20L * index)
            listOf(
                entry(KIND_CYCLE, start.toString()),
                entry(KIND_CYCLE_END, start.plusDays(4).toString()),
            )
        } + entry(KIND_CYCLE, "2026-09-29")
        val today = day("2026-09-30")
        val phase = facts(journal, today).phase()

        val summary = cycleSummary(journal, today)
        assertTrue(
            "the fixture is only worth anything if the window really does overlap",
            freshFertileWindow(summary, today)?.contains(today) == true,
        )
        assertTrue("day 2 of a period is a period day", phase is CyclePhase.Menstruation)
        assertEquals(2, (phase as CyclePhase.Menstruation).dayOfPeriod)
    }

    /** A lone mid-cycle spotting tap is not day one of anything. */
    @Test
    fun spottingDoesNotStartAPeriod() {
        val today = day("2026-11-20")
        val journal = regular() + log(today.toString(), CycleLog.SPOTTING)
        val phase = facts(journal, today).phase()

        assertFalse("spotting is bleeding, but it is not a period start", phase is CyclePhase.Menstruation)
        assertTrue(today in recordedBleedingDays(journal))
    }

    // ---- the verdict -----------------------------------------------------------------------

    /** Below three previous cycles at the same point there is nothing worth calling usual. */
    @Test
    fun twoObservationsAreNotYetAHabit() {
        val verdict = normVerdictOf(CycleScale.FLOW, 3, listOf(3, 3))

        assertTrue(verdict is NormVerdict.TooLittle)
        assertEquals(2, (verdict as NormVerdict.TooLittle).compared)
        assertEquals(NORM_MIN_OBSERVATIONS, verdict.needed)
    }

    /** Her own record is the rule: the same thing at the same point before is the same thing now. */
    @Test
    fun theSameValueAtTheSamePointReadsAsUsual() {
        val verdict = normVerdictOf(CycleScale.FLOW, 3, listOf(3, 3, 2, 3))

        assertTrue(verdict is NormVerdict.Same)
        assertEquals(3, (verdict as NormVerdict.Same).matching)
        assertEquals(4, verdict.compared)
        assertEquals(2..3, verdict.band)
    }

    /** Inside the band she has recorded is still hers, even without an exact repeat. */
    @Test
    fun insideHerOwnBandIsStillUsual() {
        assertTrue(normVerdictOf(CycleScale.MUCUS, 3, listOf(2, 4, 4)) is NormVerdict.Same)
    }

    /** Outside it is reported as outside it, with the direction and the count, and nothing more. */
    @Test
    fun outsideTheBandCarriesTheDirectionAndTheCount() {
        val higher = normVerdictOf(CycleScale.FLOW, 3, listOf(1, 1, 2))
        assertTrue(higher is NormVerdict.Different)
        assertTrue((higher as NormVerdict.Different).higher)
        assertEquals(3, higher.compared)

        val lower = normVerdictOf(CycleScale.FLOW, 1, listOf(2, 3, 3))
        assertFalse((lower as NormVerdict.Different).higher)
    }

    /**
     * A record that has been every rung at this point has no usual. Reporting it as "the same as
     * always" would make the word mean "anything", which is how a sentence stops carrying
     * information while still sounding as though it does.
     */
    @Test
    fun aRecordThatHasBeenEverythingHasNoUsual() {
        assertTrue(normVerdictOf(CycleScale.FLOW, 2, listOf(1, 3, 1, 3)) is NormVerdict.TooLittle)
    }

    /** Nothing recorded today is no verdict at all, so the chip is not a button. */
    @Test
    fun nothingRecordedTodayIsNoVerdict() {
        val today = day("2026-11-10")
        val journal = regular()

        assertNull(facts(journal, today).let { it.normVerdict(it.phase()) })
    }

    /** With a value today and no history to place it against, the answer is the count, not a word. */
    @Test
    fun aValueWithNothingToCompareItWithSaysSo() {
        val today = day("2026-11-10")
        val journal = listOf(entry(KIND_CYCLE, "2026-11-09"), log(today.toString(), CycleLog.FLOW_HEAVY))
        val verdict = facts(journal, today).let { it.normVerdict(it.phase()) }

        assertTrue(verdict is NormVerdict.TooLittle)
        assertEquals(0, (verdict as NormVerdict.TooLittle).compared)
    }

    // ---- the flags, which do not wait to be asked -------------------------------------------

    /** FIGO 2018 past eight days, ACOG Committee Opinion 651 past seven for an adolescent. */
    @Test
    fun prolongedBleedingIsFlaggedAtTheAgeItIsPublishedFor() {
        assertEquals(PERIOD_LONG_DAYS, prolongedPeriodThreshold(null))
        assertEquals(PERIOD_LONG_DAYS, prolongedPeriodThreshold(31))
        assertEquals(7, prolongedPeriodThreshold(15))

        val summary = cycleSummary(regular(), day("2026-11-18"))
        val phase = CyclePhase.Menstruation(day("2026-11-09"), 10, null)
        val flags = cycleFlags(summary, day("2026-11-18"), phase, 31)

        assertEquals(listOf(CycleFlag.Prolonged(10, 8)), flags)
    }

    /** A start forgotten for a year must not print "four hundred days is longer than usual". */
    @Test
    fun aForgottenStartCannotPrintAnAbsurdLength() {
        val phase = CyclePhase.Menstruation(day("2025-11-09"), 400, null)
        val flags = cycleFlags(cycleSummary(regular(), day("2026-12-14")), day("2026-12-14"), phase, 31)

        assertEquals(PLAUSIBLE_PERIOD_DAYS.last, (flags.first() as CycleFlag.Prolonged).days)
    }

    /** The one thing this screen can say on a day with nothing recorded and no comparison to make. */
    @Test
    fun aLatePeriodIsSaidWithoutBeingAsked() {
        val today = day("2026-12-20")
        val summary = cycleSummary(regular(), today)
        val flags = cycleFlags(summary, today, CyclePhase.Ordinary(42), 31)

        assertEquals(listOf(CycleFlag.Late(day("2026-12-07"), 13)), flags)
    }

    /**
     * And not on a record gone quiet. Past twice her average the page stops counting the days of
     * the cycle and says nothing has been logged since the last start; "your period is 231 days
     * late" beside that is a true sentence about a record nobody kept.
     */
    @Test
    fun aRecordGoneQuietIsNotCalledLate() {
        val today = day("2026-11-09").plusDays(250)
        val summary = cycleSummary(regular(), today)

        assertNull(summary.dayOfCycle)
        assertTrue(cycleFlags(summary, today, CyclePhase.Unknown, 31).isEmpty())
    }

    // ---- placing one day against another ---------------------------------------------------

    /** The numbered fallback stops before the earliest ovulation her own cycles could have had. */
    @Test
    fun theNumberedFallbackStopsAtTheFollicularHalf() {
        val today = day("2026-11-20")
        val summary = cycleSummary(regular(), today)

        assertEquals(14, followingBound(summary))
        assertEquals(
            CycleAnchorKind.CYCLE_DAY,
            facts(regular(), today).anchorOf(day("2026-11-15"), CycleAnchorKind.CYCLE_DAY)?.kind,
        )
        assertNull(
            "day 20 of a 28-day cycle is the other half of the month",
            facts(regular(), today).anchorOf(day("2026-11-28"), CycleAnchorKind.CYCLE_DAY),
        )
    }

    /** A point of one cycle never resolves to a day belonging to the cycle before or after it. */
    @Test
    fun anAnchoredDayStaysInsideItsOwnCycle() {
        val today = day("2026-11-20")
        val cycles = cycleViews(regular(), cycleSummary(regular(), today), today).dropLast(1)
        val cycle = cycles.last()

        assertEquals(day("2026-10-12"), anchoredDate(CycleAnchor(CycleAnchorKind.PERIOD_DAY, 1), cycle))
        assertNull(
            "day 40 of a 28-day cycle is not a day of it",
            anchoredDate(CycleAnchor(CycleAnchorKind.CYCLE_DAY, 40), cycle),
        )
        assertTrue(
            cycles.all { view ->
                (1..21).mapNotNull { anchoredDate(CycleAnchor(CycleAnchorKind.PERIOD_DAY, it), view) }
                    .all { !it.isBefore(view.start) && it.isBefore(view.nextStart!!) }
            },
        )
    }

    /**
     * A day of a cycle that finished months ago must place itself too. Counted from the LAST start
     * alone, every day before the current cycle would fail to place and the comparison line could
     * only ever be drawn over the fortnight since that start — over a year of entries the chart
     * would answer "nothing to compare with".
     */
    @Test
    fun aDayOfAnOlderCycleStillPlacesItself() {
        val today = day("2026-11-20")
        val facts = facts(regular(), today)

        // 4 March is day 3 of the cycle that began on 2 March, nine cycles back.
        assertEquals(
            CycleAnchor(CycleAnchorKind.CYCLE_DAY, 3),
            facts.anchorOf(day("2026-03-04"), CycleAnchorKind.CYCLE_DAY),
        )
        // And a day inside the window the average predicts places itself against that prediction.
        assertEquals(
            CycleAnchor(CycleAnchorKind.CYCLE_DAY, 3),
            facts.anchorOf(day("2026-12-09"), CycleAnchorKind.CYCLE_DAY),
        )
        assertNull("but not indefinitely far ahead", facts.anchorOf(day("2027-06-01")))
    }

    /**
     * The window begins today, so nearly all of the line is carried by her own past cycles. That
     * half has to actually be drawn on a record that supports it — twelve cycles with the same
     * three days of flow logged in each — and it has to peak where the next period is forecast.
     */
    @Test
    fun theLineIsCarriedForwardByHerOwnCyclesWhereSheHasNotRecordedYet() {
        val today = day("2026-11-10")
        val flows = listOf(CycleLog.FLOW_HEAVY, CycleLog.FLOW_MEDIUM, CycleLog.FLOW_LIGHT)
        val journal = regular() + (0 until 12).flatMap { index ->
            val start = day("2026-01-05").plusDays(28L * index)
            flows.mapIndexed { offset, flow -> log(start.plusDays(offset.toLong()).toString(), flow) }
        }
        val facts = facts(journal, today)
        val phase = facts.phase()
        val chart = facts.chart(phase, CycleWindow.MONTH, acts = true)

        assertTrue("today is day 2 of a period", phase is CyclePhase.Menstruation)
        val line = chart.series.singleOrNull { it.role == CycleSeriesRole.RECORDED }
        assertEquals(CycleScale.FLOW, line?.scale)
        val ahead = line!!.points.withIndex().filter { it.index > chart.todayIndex && it.value != null }
        assertTrue(
            "a curve claims a shape, so it needs at least three points",
            ahead.size >= CHART_TYPICAL_MIN_POINTS,
        )
        // And it peaks inside the period it forecasts, which is where her own cycles peak.
        val peak = ahead.maxByOrNull { it.value!! }!!
        assertTrue(
            "the forecast peaks inside the period it forecasts",
            chart.predicted.any { peak.index in it },
        )
    }

    /**
     * The line has to run unbroken across today and keep going, and it has to stay honest about
     * which half is which. Stopping dead where the record stops would draw a stub over the two days
     * she had logged, then a gap, then the comparison starting again in another colour further
     * along, which reads as a broken chart rather than as a cycle.
     */
    @Test
    fun theCurveRunsUnbrokenAcrossTodayAndSaysWhichHalfIsHers() {
        val today = day("2026-11-10")
        val flows = listOf(CycleLog.FLOW_HEAVY, CycleLog.FLOW_MEDIUM, CycleLog.FLOW_LIGHT)
        val journal = regular() + (0 until 12).flatMap { index ->
            val start = day("2026-01-05").plusDays(28L * index)
            flows.mapIndexed { offset, flow -> log(start.plusDays(offset.toLong()).toString(), flow) }
        }
        val facts = facts(journal, today)
        val chart = facts.chart(facts.phase(), CycleWindow.MONTH, acts = true)
        val curve = chart.series.first { it.role == CycleSeriesRole.RECORDED }

        assertTrue("every column carries a value", curve.points.all { it != null })
        assertTrue("the forecast half is drawn", curve.points.size > chart.todayIndex + 1)
        assertTrue(
            "nothing past today is claimed as her record",
            curve.recorded.drop(chart.todayIndex + 1).none { it },
        )
        assertTrue(
            "and the days she did log are",
            curve.recorded.take(chart.todayIndex + 1).any { it },
        )
        // Day one of the period forecast stands at the top of the line and the dry days between
        // here and there at its floor, so the curve can run through them rather than break.
        val byDay = chart.buckets.map { it.start }.zip(curve.points).toMap()
        assertEquals(curve.points.filterNotNull().max(), byDay[day("2026-12-07")]!!, 1e-9)
        assertEquals(0.0, byDay[day("2026-11-20")]!!, 1e-9)
    }

    /**
     * A day of her period she has not answered yet is not a day without flow. Read as a measured
     * nought it would stand on the plot as a solid dot on the floor until she answered; it is unknown, and
     * the line there is carried by her past cycles in the estimate's stroke.
     */
    @Test
    fun anUnansweredDayOfAPeriodIsNotAMeasuredNought() {
        val today = day("2026-11-10")
        val facts = facts(regular(), today)
        val chart = facts.chart(facts.phase(), CycleWindow.MONTH, acts = true)
        val curve = chart.series.first { it.role == CycleSeriesRole.RECORDED }

        assertEquals(CycleScale.FLOW, curve.scale)
        assertNull(facts.levelOn(today, CycleScale.FLOW))
        assertFalse("today is not claimed as her record", curve.recorded[chart.todayIndex])
        assertTrue("the line still runs through it", curve.points[chart.todayIndex] != null)
        // Outside the period the marks still say she was not bleeding.
        assertEquals(0.0, facts.levelOn(day("2026-11-20"), CycleScale.FLOW) ?: -1.0, 1e-9)
        // And once she answers, the day is hers.
        val answered = facts(regular() + log(today.toString(), CycleLog.FLOW_LIGHT), today)
        assertEquals(CycleScale.FLOW.heightOf(1), answered.levelOn(today, CycleScale.FLOW) ?: -1.0, 1e-9)
    }

    /** And the ovulation the average predicts is marked, not only the one that has already passed. */
    @Test
    fun theChartMarksAnOvulationThatHasNotHappenedYet() {
        val today = day("2026-11-10")
        val journal = regular()
        val facts = facts(journal, today)
        val chart = facts.chart(facts.phase(), CycleWindow.MONTH, acts = true)

        // 9 November started the cycle running now; its ovulation is fourteen days before the
        // start the average predicts for 7 December.
        assertEquals(day("2026-11-23"), chart.ovulation?.let { chart.buckets[it].start })
    }

    /**
     * The fertile days are drawn as a hump on the ovulation, and it is a drawing: the six days it
     * spans are Wilcox 1995's, the shape inside them is not a probability, and outside a record that
     * can place an ovulation at all there is no curve.
     */
    @Test
    fun theFertileShapeRisesToTheOvulationAndIsNullWithoutOne() {
        val ovulation = day("2026-11-23")

        assertNull(fertilityShape(null, ovulation))
        assertEquals(1.0, fertilityShape(ovulation, ovulation)!!, 1e-9)
        assertEquals(0.0, fertilityShape(ovulation, ovulation.minusDays(6))!!, 1e-9)
        assertEquals(0.0, fertilityShape(ovulation, ovulation.plusDays(2))!!, 1e-9)
        assertEquals(0.0, fertilityShape(ovulation, ovulation.minusDays(20))!!, 1e-9)
        // It only ever rises on the way in, so the picture cannot say a day further from ovulation
        // is more fertile than one closer to it.
        val rising = (-6L..0L).map { fertilityShape(ovulation, ovulation.plusDays(it))!! }
        assertEquals(rising.sorted(), rising)
    }

    /** And the chart carries it, on a day nowhere near the window, so the forecast has a shape. */
    @Test
    fun theChartCarriesAFertilityCurveDuringAPeriod() {
        val today = day("2026-11-10")
        val facts = facts(regular(), today)
        val chart = facts.chart(facts.phase(), CycleWindow.MONTH, acts = true)
        val fertility = chart.series.single { it.role == CycleSeriesRole.ESTIMATE }

        assertTrue("today is a bleeding day", facts.phase() is CyclePhase.Menstruation)
        assertTrue(
            "the fertile days ahead are drawn, not only the bleeding behind",
            fertility.points.withIndex().any { it.index > chart.todayIndex && (it.value ?: 0.0) > 0.5 },
        )
        assertTrue("none of it is claimed as her record", fertility.recorded.none { it })
    }

    // ---- the write path ---------------------------------------------------------------------

    /**
     * "It stopped" on Tuesday and "it started" on Wednesday is one period with a quiet day in it.
     * Filed as two, her periods would read as one day long and every figure drawn from them with it.
     */
    @Test
    fun aStartTheDayAfterAnEndRejoinsThatPeriod() {
        val journal = listOf(entry(KIND_CYCLE, "2026-11-09"), entry(KIND_CYCLE_END, "2026-11-11"))

        assertEquals(day("2026-11-11"), resumedPeriodEnd(journal, day("2026-11-11")))
        assertEquals(day("2026-11-11"), resumedPeriodEnd(journal, day("2026-11-12")))
        assertNull("two days later is a period of its own", resumedPeriodEnd(journal, day("2026-11-13")))
        assertNull(resumedPeriodEnd(emptyList(), day("2026-11-12")))
    }

    // ---- the ladders -------------------------------------------------------------------------

    /**
     * Spotting is a kind of bleeding and not an amount of it, so it is not a rung of the flow
     * ladder — a spotting day and a light day one step apart would make the steps mean nothing.
     */
    @Test
    fun spottingIsNotARungOfTheFlowLadder() {
        assertNull(CycleScale.FLOW.rungOf(CycleLog.SPOTTING))
        assertEquals(1, CycleScale.FLOW.rungOf(CycleLog.FLOW_LIGHT))
        assertEquals(3, CycleScale.FLOW.rungOf(CycleLog.FLOW_HEAVY))
    }

    /** Every ladder's rungs are options of exactly one section, or an answer would delete another. */
    @Test
    fun everyLadderIsFiledUnderOneSection() {
        CycleScale.entries.forEach { scale ->
            val sections = scale.logs.map { log -> CycleSection.entries.filter { log in it.logs } }
            assertTrue(
                "${scale.name} spreads across sections: $sections",
                sections.all { it.size == 1 } && sections.map { it.single() }.distinct().size == 1,
            )
        }
    }

    /** A ladder whose bottom rung means "none of it" starts the plot at the floor. */
    @Test
    fun aLadderWithANoneRungStartsAtTheFloor() {
        assertEquals(0.0, CycleScale.PAIN.heightOf(1), 1e-9)
        assertEquals(1.0, CycleScale.PAIN.heightOf(4), 1e-9)
        assertEquals(0.0, CycleScale.DESIRE.heightOf(1), 1e-9)
        // Flow has no "none" rung — the lightest is still an amount, so it sits above the floor.
        assertTrue(CycleScale.FLOW.heightOf(1) > 0.0)
        assertEquals(1.0, CycleScale.FLOW.heightOf(3), 1e-9)
    }
}
