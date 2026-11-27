package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

class CalendarWeekTest {
    @Test
    fun leadingBlanksFollowTheLocaleFirstDayOfWeek() {
        // 1 August 2026 is a Saturday.
        val saturday = LocalDate.parse("2026-08-01").dayOfWeek
        assertEquals(5, firstDayOfWeekBlanks(saturday, DayOfWeek.MONDAY))
        assertEquals(6, firstDayOfWeekBlanks(saturday, DayOfWeek.SUNDAY))
        assertEquals(0, firstDayOfWeekBlanks(saturday, DayOfWeek.SATURDAY))
    }

    @Test
    fun theFirstOfTheMonthLandsUnderItsOwnWeekdayHeader() {
        val first = LocalDate.parse("2026-08-01")
        for (firstDay in (1..7).map(DayOfWeek::of)) {
            val column = firstDayOfWeekBlanks(first.dayOfWeek, firstDay)
            assertEquals(first.dayOfWeek, firstDay.plus(column.toLong()))
        }
    }

    @Test
    fun theWeekStripOpensOnTheLocaleFirstDay() {
        val wednesday = LocalDate.parse("2026-08-12")
        assertEquals(DayOfWeek.SUNDAY, WeekFields.of(Locale.US).firstDayOfWeek)
        assertEquals(
            LocalDate.parse("2026-08-09"),
            wednesday.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.US).firstDayOfWeek)),
        )
        assertEquals(
            LocalDate.parse("2026-08-10"),
            wednesday.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.FRANCE).firstDayOfWeek)),
        )
    }
}
