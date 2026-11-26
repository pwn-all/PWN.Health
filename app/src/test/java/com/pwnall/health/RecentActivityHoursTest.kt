package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentActivityHoursTest {
    @Test
    fun showsSixHoursEndingAtTheLatestActivityRatherThanTrailingEmptyHours() {
        val hours = MutableList(24) { 0f }
        (8..14).forEach { hours[it] = it.toFloat() }
        assertEquals((9..14).map(Int::toFloat), recentActivityHours(hours))
    }

    @Test
    fun earlyActivityAndAnEmptyDayKeepSixSlots() {
        assertEquals(listOf(0f, 0f, 0f, 0f, 2f, 3f), recentActivityHours(listOf(2f, 3f, 0f)))
        assertEquals(List(6) { 0f }, recentActivityHours(emptyList()))
        assertEquals(List(6) { 0f }, recentActivityHours(List(24) { 0f }))
    }
}
