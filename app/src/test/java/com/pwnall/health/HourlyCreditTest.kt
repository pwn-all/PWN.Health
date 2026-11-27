package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which hour a credit lands in. A bout that straddles midnight is credited to two days at once
 * ([StepAccumulator.accumulate] returns both), and the hour the batch arrived in only describes one
 * of them — writing it to yesterday's row files a walk taken before midnight as a walk taken after.
 */
class HourlyCreditTest {
    private val today = "2026-08-12"
    private val yesterday = "2026-08-11"

    @Test
    fun todaysCreditLandsInTheHourItArrived() {
        assertEquals(14, creditedHour(today, today, 14))
        assertEquals(0, creditedHour(today, today, 0))
    }

    @Test
    fun theOtherHalfOfAMidnightBoutLandsInTheHourThatDayEndedIn() {
        assertEquals(23, creditedHour(yesterday, today, 0))
    }
}
