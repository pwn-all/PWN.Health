package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * How much of the step history a screen must hand [activityAdvice], kept beside [HealthAdviceTest]
 * rather than in it: the home screen loads a window, and the window has to hold the month.
 */
class ActivityAdviceHistoryTest {
    private val today = LocalDate.parse("2026-07-31")

    private fun loadedSince(daysBack: Long) = (0..daysBack).map { offset ->
        DaySteps(today.minusDays(offset).toString(), 3_000L)
    }

    /**
     * The month is thirty finished days and today is still being walked, so a window that starts
     * twenty-nine days back holds twenty-nine of them, and the home screen would show a quiet week
     * where the health section, reading the whole record, shows a quiet month.
     */
    @Test
    fun theHistoryAScreenLoadsHoldsTheWholeMonth() {
        val month = activityAdvice(loadedSince(ACTIVITY_HISTORY_DAYS), ageYears = 30, today = today)!!
        assertEquals("activity-month", month.id)
        assertEquals(AdviceLevel.ATTENTION, month.level)

        val dayShort = activityAdvice(loadedSince(ACTIVITY_HISTORY_DAYS - 1), ageYears = 30, today = today)!!
        assertEquals("activity-week", dayShort.id)
    }
}
