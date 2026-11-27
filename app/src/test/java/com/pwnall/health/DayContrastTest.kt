package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The gates between two groups of days and any word about them: the floor each group needs, the
 * points apart, Newcombe's and Welch's intervals, and the two halves of the stretch pointing the same
 * way. Every border is tested from both sides, since a card that calls fourteen and a half points
 * fifteen is the defect this file exists to prevent.
 */
class DayContrastTest {
    private val start = LocalDate.of(2026, 1, 5)

    private fun day(i: Int): LocalDate = start.plusDays(i.toLong())

    /** Two groups interleaved day by day — exposed on even days, other on odd — with their outcomes. */
    private fun interleaved(
        exposed: List<Boolean>,
        other: List<Boolean>,
    ): Pair<Map<LocalDate, Boolean>, Map<LocalDate, Boolean>> =
        exposed.withIndex().associate { day(2 * it.index) to it.value } to
            other.withIndex().associate { day(2 * it.index + 1) to it.value }

    /** [yes] of [days] true, spread evenly, so that each half of the stretch holds the same share. */
    private fun spread(days: Int, yes: Int): List<Boolean> =
        List(days) { i -> (i * yes) / days != ((i + 1) * yes) / days }

    /** Newcombe 1998 (Stat Med 17:873), Table II: method 10 on the paper's own four examples. */
    @Test
    fun theDifferenceIntervalIsNewcombesMethodTen() {
        fun assertInterval(low: Double, high: Double, interval: ClosedFloatingPointRange<Double>) {
            assertEquals(low, interval.start, 0.0001)
            assertEquals(high, interval.endInclusive, 0.0001)
        }
        // The paper's differences are the first proportion minus the second; the function's are the
        // second minus the first, so the arguments go in the other way round.
        assertInterval(0.0524, 0.3339, newcombeInterval(48, 80, 56, 70))
        assertInterval(0.1705, 0.8090, newcombeInterval(3, 10, 9, 10))
        assertInterval(0.0582, 0.8062, newcombeInterval(2, 7, 6, 7))
        assertInterval(-0.0381, 0.1926, newcombeInterval(0, 29, 5, 56))
    }

    @Test
    fun theSpreadHelperSpreadsWhatItIsAsked() {
        assertEquals(130, spread(200, 130).count { it })
        assertEquals(65, spread(200, 130).take(100).count { it })
        assertEquals(129, spread(200, 129).count { it })
    }

    /** "Not enough days yet: 9 and 31 (needs 10 each)" — the counts, the floor, and no interval. */
    @Test
    fun underTheFloorOnlyTheCountsComeBack() {
        val (a, b) = interleaved(List(9) { true }, List(31) { false })
        val contrast = dayShareContrast(a, b)
        assertEquals(ContrastVerdict.NOT_ENOUGH_DAYS, contrast.verdict)
        assertEquals(DayShare(9, 9), contrast.exposed)
        assertEquals(DayShare(0, 31), contrast.other)
        assertEquals(CONTRAST_MIN_DAYS, contrast.minDays)
        assertNull(contrast.interval)
        assertFalse(contrast.notable)
    }

    /** Ten each is enough; the interval then comes back even when nothing else passes. */
    @Test
    fun tenDaysEachIsTheFloor() {
        val (a, b) = interleaved(List(10) { true }, List(10) { false })
        val contrast = dayShareContrast(a, b)
        assertEquals(ContrastVerdict.NOTABLE, contrast.verdict)
        assertEquals(0.6075, contrast.interval!!.start, 0.0001)
    }

    /** 65% against 50% is fifteen points exactly and passes; 64.5% is fourteen and a half and does not. */
    @Test
    fun fifteenPointsIsTheCardsLineFromBothSides() {
        val (a, b) = interleaved(spread(200, 130), spread(200, 100))
        assertEquals(ContrastVerdict.NOTABLE, dayShareContrast(a, b).verdict)
        val (c, d) = interleaved(spread(200, 129), spread(200, 100))
        val under = dayShareContrast(c, d)
        // The interval still leaves nought out (0.048 to 0.238): only the line refuses it.
        assertTrue(under.interval!!.start > 0.0)
        assertEquals(ContrastVerdict.NO_CLEAR_DIFFERENCE, under.verdict)
    }

    /** 67.5% against 50% passes a card's fifteen points and not a sheet pair's twenty. */
    @Test
    fun theSheetsLineIsTwentyPoints() {
        val (a, b) = interleaved(spread(200, 135), spread(200, 100))
        assertEquals(ContrastVerdict.NOTABLE, dayShareContrast(a, b).verdict)
        val sheet = dayShareContrast(a, b, minPoints = SHEET_SHARE_POINTS)
        assertEquals(ContrastVerdict.NO_CLEAR_DIFFERENCE, sheet.verdict)
    }

    /** 5 of 10 against 3 of 10 is twenty points, and its range (-0.20 to 0.53) still reaches nought. */
    @Test
    fun aRangeThatReachesNoughtIsNoClearDifference() {
        val (a, b) = interleaved(spread(10, 5), spread(10, 3))
        val contrast = dayShareContrast(a, b)
        assertEquals(-0.2017, contrast.interval!!.start, 0.0001)
        assertEquals(ContrastVerdict.NO_CLEAR_DIFFERENCE, contrast.verdict)
    }

    /**
     * All of the first half's exposed days had it and none of the others; in the second half the
     * exposed days had it less often than the others. 70% against 25% over the whole, but the halves
     * disagree, so neighbouring days are all the whole was showing.
     */
    @Test
    fun halvesThatDisagreeAreNoClearDifference() {
        val exposed = List(20) { true } + spread(20, 8)
        val other = List(20) { false } + spread(20, 10)
        val (a, b) = interleaved(exposed, other)
        val contrast = dayShareContrast(a, b)
        assertEquals(DayShare(28, 40), contrast.exposed)
        assertEquals(DayShare(10, 40), contrast.other)
        assertTrue(contrast.interval!!.start > 0.0)
        assertEquals(ContrastVerdict.NO_CLEAR_DIFFERENCE, contrast.verdict)
        assertEquals(ContrastVerdict.NOTABLE, dayShareContrast(a, b, splitHalf = false).verdict)
    }

    /** A half with no exposed day in it cannot point any way. */
    @Test
    fun aHalfMissingAGroupFailsTheCheck() {
        val a = (0 until 10).associate { day(it) to true }
        val b = (10 until 40).associate { day(it) to false }
        assertEquals(ContrastVerdict.NO_CLEAR_DIFFERENCE, dayShareContrast(a, b).verdict)
        assertEquals(ContrastVerdict.NOTABLE, dayShareContrast(a, b, splitHalf = false).verdict)
    }

    /** The middle day of an odd stretch goes with the first half. */
    @Test
    fun theMiddleDayGoesWithTheFirstHalf() {
        // Exposed on days 0..10 and 111..120, other on 50..59: the stretch is 0..120 and its middle
        // day 60, so every other day is in the first half and the second half cannot point any way.
        val a = (0..10).associate { day(it) to true } + (111..120).associate { day(it) to true }
        val b = (50..59).associate { day(it) to false }
        assertEquals(ContrastVerdict.NO_CLEAR_DIFFERENCE, dayShareContrast(a, b).verdict)
        val c = b + (0..9).associate { day(it + 61) to false }
        assertEquals(ContrastVerdict.NOTABLE, dayShareContrast(a, c).verdict)
        // Day 60 itself is the first half's: an other day there does not rescue the second.
        val d = b + (day(60) to false)
        assertEquals(ContrastVerdict.NO_CLEAR_DIFFERENCE, dayShareContrast(a, d).verdict)
    }

    @Test
    fun welchsIntervalMatchesTheTextbook() {
        // 1..10 against 3..12: a difference of -2, a standard error of 1.3540 on 18 degrees of
        // freedom, and t(18) = 2.1009 from the tables.
        val interval = welchInterval((1..10).map(Int::toDouble), (3..12).map(Int::toDouble))
        assertEquals(-4.8447, interval.start, 0.001)
        assertEquals(0.8447, interval.endInclusive, 0.001)
        // Nothing varies: the difference itself.
        assertEquals(1.0..1.0, welchInterval(listOf(2.0, 2.0), listOf(1.0, 1.0, 1.0)))
    }

    @Test
    fun theTQuantileIsWithinTheTablesFromNineDegreesUp() {
        assertEquals(2.262157, studentT975(9.0), 0.0005)
        assertEquals(2.100922, studentT975(18.0), 0.0005)
        assertEquals(2.042272, studentT975(30.0), 0.0005)
        assertEquals(1.983972, studentT975(100.0), 0.0005)
    }

    /** Mood 3.5 against 3.0 is half a point and passes; against 3.01 it is 0.49 and does not. */
    @Test
    fun halfAPointOfMoodIsTheLineFromBothSides() {
        val moodApart = { a: Double, b: Double -> apartBy(a, b, MOOD_MIN_CHANGE) }
        val exposed = (0 until 40).associate { day(2 * it) to if (it % 2 == 0) 3.0 else 4.0 }
        val other = (0 until 40).associate { day(2 * it + 1) to if (it % 2 == 0) 2.5 else 3.5 }
        val on = dayMeanContrast(exposed, other, moodApart)
        assertEquals(DayMean(40, 3.5), on.exposed)
        assertEquals(DayMean(40, 3.0), on.other)
        assertEquals(ContrastVerdict.NOTABLE, on.verdict)
        val under = dayMeanContrast(exposed, other.mapValues { it.value + 0.01 }, moodApart)
        assertTrue(under.interval!!.start > 0.0)
        assertEquals(ContrastVerdict.NO_CLEAR_DIFFERENCE, under.verdict)
    }

    @Test
    fun meansUnderTheFloorGiveCountsAndNothingElse() {
        val exposed = (0 until 9).associate { day(it) to 5.0 }
        val other = (20 until 40).associate { day(it) to 1.0 }
        val contrast = dayMeanContrast(exposed, other, { _, _ -> true })
        assertEquals(ContrastVerdict.NOT_ENOUGH_DAYS, contrast.verdict)
        assertEquals(DayMean(9, 5.0), contrast.exposed)
        assertNull(contrast.interval)
        val none = dayMeanContrast(emptyMap(), other, { _, _ -> true })
        assertEquals(DayMean(0, null), none.exposed)
        assertEquals(ContrastVerdict.NOT_ENOUGH_DAYS, none.verdict)
    }

    /**
     * "On days after" only when the forward pairing is notable and its reverse was weighed and found
     * quiet. A reverse under its floor was never weighed, and clears no direction.
     */
    @Test
    fun aDirectionNeedsTheReverseToBeQuiet() {
        val (a, b) = interleaved(List(10) { true }, List(10) { false })
        val notable = dayShareContrast(a, b)
        val quiet = dayShareContrast(b, a.mapValues { false })
        val unweighed = dayShareContrast(b, a.filterKeys { it < day(10) }.mapValues { false })
        assertTrue(notable.notable)
        assertFalse(quiet.notable)
        assertEquals(ContrastVerdict.NOT_ENOUGH_DAYS, unweighed.verdict)
        assertTrue(LaggedContrast(notable, quiet).directed)
        assertFalse(LaggedContrast(notable, unweighed).directed)
        assertFalse(LaggedContrast(notable, notable).directed)
        assertFalse(LaggedContrast(quiet, quiet).directed)
        assertFalse(LaggedContrast(quiet, notable).directed)
    }

    /** The outcome is read [shift] days on, keyed by the exposure's day; an unknown day drops the pair. */
    @Test
    fun splittingDaysReadsTheOutcomeAtItsLag() {
        val exposure = mapOf(day(0) to true, day(1) to false, day(2) to true)
        val outcome = mapOf(day(1) to 10, day(2) to 20, day(3) to 30)
        assertEquals(mapOf(day(2) to 20) to mapOf(day(1) to 10), splitDays(exposure, 0, outcome::get))
        assertEquals(mapOf(day(0) to 10, day(2) to 30) to mapOf(day(1) to 20), splitDays(exposure, 1, outcome::get))
        assertEquals(mapOf(day(2) to 10) to emptyMap<LocalDate, Int>(), splitDays(exposure, -1, outcome::get))
    }
}
