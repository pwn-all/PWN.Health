package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar's charts are drawn against [chartGridlines] rather than against their own tallest
 * value, so this is the one place the reader's "20k" is decided. A regression here is silent: the
 * bars still look like bars, they are simply measured against a ceiling nobody would print.
 */
class ChartAxisTest {
    @Test
    fun anEmptyChartGetsItsBaselineAndNothingElse() {
        // Four evenly spaced numbers over a month with no steps in it would be a scale invented
        // for data that does not exist.
        assertEquals(listOf(0.0), chartGridlines(0.0))
        assertEquals(listOf(0.0), chartGridlines(-1.0))
    }

    @Test
    fun aHandfulOfSomethingStillGetsAWholeAxis() {
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0), chartGridlines(7.0))
    }

    @Test
    fun aPeakThatIsAlreadyRoundIsNotLiftedAboveItself() {
        // 4,000 divides into four steps of 1,000 exactly, so the top ruling is the peak itself and
        // no height is wasted over it.
        assertEquals(listOf(0.0, 1_000.0, 2_000.0, 3_000.0, 4_000.0), chartGridlines(4_000.0))
    }

    @Test
    fun aBusiestDayIsRoundedUpToANumberAPersonReads() {
        assertEquals(
            listOf(0.0, 5_000.0, 10_000.0, 15_000.0, 20_000.0),
            chartGridlines(18_437.0),
        )
    }

    @Test
    fun aYearOfStepsClimbsInEightyThousands() {
        // The by-year chart totals a month at a time, and 285,000 over four steps of 71,250 is the
        // case the 1-2-4-5-8 ladder exists for: 80,000 a band rather than 100,000.
        assertEquals(
            listOf(0.0, 80_000.0, 160_000.0, 240_000.0, 320_000.0),
            chartGridlines(285_000.0),
        )
    }

    @Test
    fun theTopRulingAlwaysClearsThePeak() {
        for (peak in listOf(1.0, 9.0, 137.0, 999.0, 1_001.0, 12_345.0, 604_800.0)) {
            val top = chartGridlines(peak).last()
            assertTrue("$peak does not fit under $top", top >= peak)
        }
    }
}
