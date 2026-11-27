package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The engine, against series whose answer is known before it runs.
 *
 * The gates get as much attention as the arithmetic, because refusing is most of what this code
 * does: fewer than half of people have any day-to-day association to find, so "not enough to say"
 * is the ordinary output and the expensive failure is a gate that lets noise through.
 */
class InsightsTest {
    private val start: LocalDate = LocalDate.of(2026, 1, 1)

    private fun series(values: List<Double>, from: LocalDate = start) =
        DailySeries(values.mapIndexed { index, value -> from.plusDays(index.toLong()) to value }.toMap())

    // --- the statistics -------------------------------------------------------------------------

    @Test
    fun tauIsOneWhenEveryPairAgreesAndMinusOneWhenNoneDo() {
        val rising = (1..10).map { it.toDouble() to it.toDouble() }
        val falling = (1..10).map { it.toDouble() to -it.toDouble() }

        assertEquals(1.0, kendallTauB(rising)!!, 1e-9)
        assertEquals(-1.0, kendallTauB(falling)!!, 1e-9)
    }

    /** The textbook worked example: four concordant pairs, one discordant, no ties. */
    @Test
    fun tauMatchesAHandCountedExample() {
        val pairs = listOf(1.0 to 1.0, 2.0 to 3.0, 3.0 to 2.0, 4.0 to 4.0)
        // Six pairs in all; (2,3) against (3,2) is the only one that disagrees.
        assertEquals((5 - 1) / 6.0, kendallTauB(pairs)!!, 1e-9)
    }

    /** A column that never moves has no relationship to report, and NaN is not a finding. */
    @Test
    fun aFlatSeriesHasNoTauAndNoCorrelation() {
        val flat = List(10) { 3.0 to it.toDouble() }

        assertNull(kendallTauB(flat))
        assertNull(pearson(flat))
    }

    /**
     * A trend shared by both series is exactly what manufactures a correlation out of nothing, and
     * taking it out is what [residualise] is for.
     */
    @Test
    fun residualisingRemovesASlowDrift() {
        val drifting = series((0 until 120).map { it.toDouble() })

        val flattened = residualise(drifting).byDay

        // The interior is flat: a centred median over a straight line is the line.
        val interior = flattened.filterKeys { it in start.plusDays(20)..start.plusDays(99) }.values
        assertTrue(interior.all { kotlin.math.abs(it) < 1.0 })
        // And across the whole series the spread collapses, which is what the step is for. The
        // first and last fortnight keep an edge artefact no centred window can remove — that is
        // the documented limit, not a bug, and the point of asserting the scale here.
        val spread = flattened.values.max() - flattened.values.min()
        assertTrue("spread=$spread", spread < 20.0)
        assertTrue(spread < (119.0 - 0.0) / 5)
    }

    @Test
    fun theBootstrapRangeIsTheSameEveryTimeItIsAskedTheSameQuestion() {
        val pairs = (0 until 60).map { it.toDouble() to (it % 5).toDouble() }
        fun run() = movingBlockRange(pairs, seed = 42L, rounds = 200) { sample ->
            sample.sumOf { it.second } / sample.size
        }

        assertEquals(run(), run())
    }

    @Test
    fun benjaminiHochbergKeepsTheStrongAndDropsTheRest() {
        // One clear result among fifteen that are consistent with chance.
        val scores = listOf(0.001) + List(14) { 0.4 + it * 0.04 }

        val keep = benjaminiHochberg(scores, q = 0.10)

        assertTrue(keep.first())
        assertTrue(keep.drop(1).none { it })
    }

    @Test
    fun benjaminiHochbergKeepsNothingWhenNothingStandsOut() {
        assertTrue(benjaminiHochberg(List(16) { 0.5 }, q = 0.10).none { it })
    }

    /**
     * Twice the smaller tail, with one added to the count and to the total: never nought, 1 when the
     * resamples fall evenly on both sides, and a resample that came to exactly nothing counts against
     * the link whichever side it stands on.
     */
    @Test
    fun theChanceIsTwiceTheShareOfResamplesAcrossNought() {
        assertEquals(2.0 / 1_001, bootstrapChance(List(1_000) { 0.5 }), 1e-12)
        assertEquals(2.0 * 11 / 1_001, bootstrapChance(List(990) { 0.5 } + List(10) { -0.1 }), 1e-12)
        assertEquals(2.0 * 11 / 1_001, bootstrapChance(List(990) { -0.5 } + List(10) { 0.1 }), 1e-12)
        assertEquals(2.0 * 2 / 1_001, bootstrapChance(List(999) { 0.5 } + 0.0), 1e-12)
        assertEquals(1.0, bootstrapChance(List(500) { 0.5 } + List(500) { -0.5 }), 0.0)
        assertEquals(1.0, bootstrapChance(emptyList()), 0.0)
    }

    /**
     * A point higher on the days with it, all of it earned in the first half of the window, so the
     * difference swings with which weeks a resample draws and the range comes out wider than its
     * distance from zero. A figure made of that ratio would put this link near 0.4 against a line of
     * 0.02 for one finding among five, so the correction ranks the p-value the line was written for:
     * no resample crosses zero, and the p-value says so.
     */
    @Test
    fun aWideRangeThatNeverCrossesNoughtPassesTheCorrection() {
        val exposure = series((0 until 120).map { if (it % 3 == 0) 1.0 else 0.0 })
        val outcome = series(
            (0 until 120).map {
                when {
                    it >= 60 -> 4.0
                    it % 3 == 0 -> 5.0
                    else -> 3.0
                }
            },
        )

        val contrast = insightLink(exposure, outcome, InsightLag.SAME_DAY, windowDays = 120, seed = 5L).contrast!!

        assertEquals(1.0, contrast.difference, 1e-9)
        assertTrue("lo=${contrast.lo}", contrast.lo > 0.0)
        assertTrue("lo=${contrast.lo} hi=${contrast.hi}", contrast.hi - contrast.lo > contrast.lo)
        assertTrue("chance=${contrast.chance}", contrast.chance <= 0.02)
        val keep = benjaminiHochberg(listOf(contrast.chance, 1.0, 1.0, 1.0, 1.0), q = 0.10)
        assertEquals(listOf(true, false, false, false, false), keep)
    }

    @Test
    fun aSeriallyCorrelatedPairIsWorthFewerDaysThanItHas() {
        val smooth = (0 until 100).map { kotlin.math.sin(it / 10.0) }

        assertTrue(effectiveN(smooth, smooth) < smooth.size)
        assertEquals(100, effectiveN(List(100) { it.toDouble() }, List(100) { (it * 7 % 13).toDouble() }))
    }

    // --- the gates ------------------------------------------------------------------------------

    @Test
    fun tooFewPairedDaysSaysSoRatherThanGuessing() {
        val exposure = series((0 until 40).map { it.toDouble() })
        val outcome = series((0 until 40).map { (it % 5 + 1).toDouble() })

        val link = insightLink(exposure, outcome, InsightLag.SAME_DAY, windowDays = 120, seed = 1L)

        assertEquals(InsightConfidence.NOT_ENOUGH, link.confidence)
        assertNull(link.contrast)
        assertEquals(40, link.pairedDays)
    }

    /** A gappy window describes the days somebody felt like logging, which is not the same question. */
    @Test
    fun aWindowMostlyMadeOfGapsIsRefused() {
        val days = (0 until 100).map { it.toDouble() }
        val link = insightLink(
            series(days),
            series(days.map { (it.toInt() % 5 + 1).toDouble() }),
            InsightLag.SAME_DAY,
            windowDays = 400,
            seed = 1L,
        )

        assertEquals(InsightConfidence.NOT_ENOUGH, link.confidence)
        assertTrue(link.coverage < 0.6)
    }

    /** Somebody who answers "3" nine days in ten carries no signal, whatever the arithmetic says. */
    @Test
    fun anOutcomeStuckOnOneValueIsRefused() {
        val exposure = series((0 until 200).map { it.toDouble() })
        val outcome = series((0 until 200).map { if (it % 20 == 0) 5.0 else 3.0 })

        val link = insightLink(exposure, outcome, InsightLag.SAME_DAY, windowDays = 200, seed = 1L)

        assertEquals(InsightConfidence.NOT_ENOUGH, link.confidence)
    }

    /**
     * Three evenings with a drink in four months. Cut into thirds, the "high" group would be those three
     * and thirty-seven days without one, the floor of fifteen would be met by the padding, and the card
     * would count forty days after drinking.
     */
    @Test
    fun aHandfulOfYesDaysIsRefusedRatherThanPaddedOutWithNoDays() {
        val exposure = series((0 until 120).map { if (it in setOf(10, 50, 90)) 1.0 else 0.0 })
        val outcome = series((0 until 120).map { (it % 5 + 1).toDouble() })

        val link = insightLink(exposure, outcome, InsightLag.SAME_DAY, windowDays = 120, seed = 5L)

        assertEquals(InsightConfidence.NOT_ENOUGH, link.confidence)
        assertNull(link.contrast)
    }

    /** No day with it at all: the thirds of a flat series are the start of the window and its end. */
    @Test
    fun anExposureThatNeverMovedHasNothingToContrast() {
        val exposure = series(List(120) { 0.0 })
        val outcome = series((0 until 120).map { (it % 5 + 1).toDouble() })

        val link = insightLink(exposure, outcome, InsightLag.SAME_DAY, windowDays = 120, seed = 5L)

        assertEquals(InsightConfidence.NOT_ENOUGH, link.confidence)
        assertNull(link.contrast)
    }

    // --- a link that is really there --------------------------------------------------------------

    /** Forty days with it, eighty without, and the outcome a point higher on the forty. */
    @Test
    fun aYesOrNoExposureIsSplitIntoTheDaysWithItAndTheDaysWithout() {
        val exposure = series((0 until 120).map { if (it % 3 == 0) 1.0 else 0.0 })
        val outcome = series((0 until 120).map { 2.0 + it % 2 + if (it % 3 == 0) 1.0 else 0.0 })

        val contrast = insightLink(exposure, outcome, InsightLag.SAME_DAY, windowDays = 120, seed = 5L).contrast!!

        assertEquals(40, contrast.highDays)
        assertEquals(80, contrast.lowDays)
        assertEquals(1.0, contrast.difference, 1e-9)
        assertTrue("lo=${contrast.lo}", contrast.lo > 0.0)
    }

    /**
     * What is measured is a rating with the person's usual taken out of it, which averages near
     * nought; what a sentence may call a rating is the one that was given, on the same days.
     */
    @Test
    fun theLevelsAreReadOffTheFigureThatWasWrittenDown() {
        val exposure = series((0 until 120).map { (it % 3).toDouble() })
        val measured = series((0 until 120).map { it % 3 - 1.0 })
        val written = series((0 until 120).map { it % 3 + 3.0 })

        val link = insightLink(exposure, measured, InsightLag.SAME_DAY, windowDays = 120, seed = 9L, shown = written)
        val contrast = link.contrast!!

        assertEquals(1.0, contrast.highMean, 1e-9)
        assertEquals(-1.0, contrast.lowMean, 1e-9)
        assertEquals(5.0, contrast.highShown, 1e-9)
        assertEquals(3.0, contrast.lowShown, 1e-9)
        assertEquals(2.0, contrast.difference, 1e-9)
    }

    @Test
    fun aRealDifferenceIsFoundAndCarriesARangeThatExcludesZero() {
        // Mood two points higher on the high-exposure days, with ordinary wobble on both.
        val exposure = series((0 until 200).map { (it % 10).toDouble() })
        val outcome = series((0 until 200).map { if (it % 10 >= 5) 4.0 + (it % 2) else 2.0 + (it % 2) })

        val link = insightLink(exposure, outcome, InsightLag.SAME_DAY, windowDays = 200, seed = 7L)

        assertEquals(InsightConfidence.STEADY, link.confidence)
        val contrast = assertNotNull(link.contrast).let { link.contrast!! }
        assertTrue("difference=${contrast.difference}", contrast.difference > 1.5)
        assertTrue(contrast.excludesZero)
        assertTrue(contrast.highDays >= 15 && contrast.lowDays >= 15)
    }

    @Test
    fun twoUnrelatedSeriesComeBackWithARangeThatSpansZero() {
        val exposure = series((0 until 200).map { ((it * 37) % 101).toDouble() })
        val outcome = series((0 until 200).map { ((it * 53) % 5 + 1).toDouble() })

        val link = insightLink(exposure, outcome, InsightLag.SAME_DAY, windowDays = 200, seed = 11L)

        assertFalse(link.contrast?.excludesZero == true)
    }

    /** Ninety to a hundred and eighty days is an early pattern, and is not allowed to sound settled. */
    @Test
    fun aShortButSufficientRecordIsMarkedEarly() {
        val exposure = series((0 until 120).map { (it % 10).toDouble() })
        val outcome = series((0 until 120).map { if (it % 10 >= 5) 4.0 + (it % 2) else 2.0 + (it % 2) })

        val link = insightLink(exposure, outcome, InsightLag.SAME_DAY, windowDays = 120, seed = 3L)

        assertEquals(InsightConfidence.EARLY, link.confidence)
    }

    /**
     * The blocks the range is resampled from are runs of neighbouring days, and the serial
     * correction compares each day with the one before it. A series built in another order — the
     * journal arrives newest first — has to measure exactly as the same days in calendar order.
     */
    @Test
    fun theOrderASeriesWasBuiltInChangesNothing() {
        val exposure = series((0 until 120).map { (it % 10).toDouble() })
        val values = (0 until 120).map { if (it % 10 >= 5) 4.0 + (it % 2) else 2.0 + (it % 2) }
        // The same days, newest and oldest taken in turn.
        val dealt = (0 until 60).flatMap { listOf(119 - it, it) }
        val scrambled = DailySeries(dealt.associate { start.plusDays(it.toLong()) to values[it] })

        assertEquals(
            insightLink(exposure, series(values), InsightLag.SAME_DAY, windowDays = 120, seed = 3L),
            insightLink(exposure, scrambled, InsightLag.SAME_DAY, windowDays = 120, seed = 3L),
        )
    }

    @Test
    fun theSeedFollowsWhatIsBeingMeasuredRatherThanTheClock() {
        assertEquals(
            insightSeed("steps", "mood", start),
            insightSeed("steps", "mood", start),
        )
        assertTrue(insightSeed("steps", "mood", start) != insightSeed("sugar", "mood", start))
        assertTrue(insightSeed("steps", "mood", start) != insightSeed("steps", "mood", start.plusDays(1)))
    }
}
