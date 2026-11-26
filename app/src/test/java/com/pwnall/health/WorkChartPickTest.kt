package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Work charts' bars are picked with the arrow keys as well as by touch. */
class WorkChartPickTest {
    @Test
    fun withNothingPickedTheArrowPicksTheBarAtTheEndItPointsInto() {
        assertEquals(0, steppedBar(null, step = 1, count = 7))
        assertEquals(6, steppedBar(null, step = -1, count = 7))
    }

    @Test
    fun arrowKeysStepThePickAndStopAtTheEnds() {
        assertEquals(2, steppedBar(3, step = -1, count = 7))
        assertEquals(4, steppedBar(3, step = 1, count = 7))
        assertEquals(6, steppedBar(6, step = 1, count = 7))
        assertEquals(0, steppedBar(0, step = -1, count = 7))
    }
}
