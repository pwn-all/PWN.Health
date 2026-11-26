package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarFiltersTest {
    @Test
    fun navigationStopsAtCurrentMonthAndYear() {
        val today = LocalDate.of(2026, 1, 1)
        assertFalse(calendarCanMoveForward(YearMonth.of(2026, 1), today, false))
        assertFalse(calendarCanMoveForward(YearMonth.of(2026, 1), today, true))
        assertTrue(calendarCanMoveForward(YearMonth.of(2025, 12), today, false))
        assertTrue(calendarCanMoveForward(YearMonth.of(2025, 12), today, true))
        assertFalse(calendarCanMoveForward(YearMonth.of(2027, 1), today, true))
    }

    @Test
    fun sleepMeasurementsAreNotMixedWithBodyMeasurements() {
        val row = entry("MEASUREMENT")
        assertEquals(CalendarFilter.MEASUREMENTS, calendarEntryFilter(row))
        assertEquals(CalendarFilter.SLEEP, calendarEntryFilter(row.copy(sleepStartAt = 1L, sleepEndAt = 2L)))
    }

    @Test
    fun eventCategoriesIncludeUnknownJournalKinds() {
        assertEquals(CalendarFilter.FOOD, calendarEntryFilter(entry("EAT")))
        assertEquals(CalendarFilter.WORKOUTS, calendarEntryFilter(entry("ACTIVITY")))
        assertEquals(CalendarFilter.WATER, calendarEntryFilter(entry(KIND_WATER)))
        assertEquals(CalendarFilter.PAIN, calendarEntryFilter(entry(KIND_PAIN)))
        assertEquals(CalendarFilter.SYMPTOMS, calendarEntryFilter(entry(KIND_SYMPTOM)))
        assertEquals(CalendarFilter.OTHER, calendarEntryFilter(entry("UNKNOWN")))
        // Work is not a health event of its own kind in the calendar; it is filed with the rest.
        assertEquals(CalendarFilter.OTHER, calendarEntryFilter(entry(KIND_WORK)))
    }

    /** A morning check-in carries its waking in the payload, so the sleep filter never takes it for a night. */
    @Test
    fun aMorningCheckIsNotASleep() {
        val morning = morningCheckEntry(
            "Morning",
            LocalDate.of(2026, 1, 1),
            MorningCheck(MorningErection.FIRM, 1_767_250_000_000, WakeSource.SLEEP),
        )
        assertEquals(CalendarFilter.OTHER, calendarEntryFilter(morning))
    }

    /** A restored copy can file a row under text that is no date, and text sorts "2026-11-1" inside November. */
    @Test
    fun aDayThatIsNoDateIsLeftOutOfTheOpenedMonth() {
        val november = YearMonth.of(2026, 11)
        val today = LocalDate.of(2026, 11, 18)

        val dates = calendarAgendaDates(
            entryDays = listOf("2026-11-1", "2026-11-02", "2026-11-02", "2026-10-31", "2026-11-30"),
            visitDays = listOf(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 25)),
            month = november,
            today = today,
        )

        assertEquals(listOf(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 2)), dates)
    }

    @Test
    fun aStepToAShorterMonthLandsOnItsLastDay() {
        val today = LocalDate.of(2026, 11, 18)
        val endOfJanuary = LocalDate.of(2024, 1, 31)

        assertEquals(LocalDate.of(2024, 2, 29), calendarMovedSelection(endOfJanuary, YearMonth.of(2024, 2), today))
        assertEquals(LocalDate.of(2025, 2, 28), calendarMovedSelection(endOfJanuary, YearMonth.of(2025, 2), today))
        assertEquals(LocalDate.of(2024, 4, 30), calendarMovedSelection(endOfJanuary, YearMonth.of(2024, 4), today))
        val midFebruary = LocalDate.of(2024, 2, 15)
        assertEquals(LocalDate.of(2024, 3, 15), calendarMovedSelection(midFebruary, YearMonth.of(2024, 3), today))
    }

    @Test
    fun aStepIntoThisMonthNeverSelectsADayStillAhead() {
        val today = LocalDate.of(2026, 11, 18)

        assertEquals(today, calendarMovedSelection(LocalDate.of(2026, 10, 25), YearMonth.of(2026, 11), today))
        assertEquals(today, calendarMovedSelection(LocalDate.of(2025, 11, 30), YearMonth.of(2026, 11), today))
        assertEquals(
            LocalDate.of(2026, 11, 3),
            calendarMovedSelection(LocalDate.of(2026, 10, 3), YearMonth.of(2026, 11), today),
        )
    }

    @Test
    fun theYearsAveragesAreDividedByTheDaysLivedSoFar() {
        val today = LocalDate.of(2026, 3, 10)

        val thisYear = calendarElapsedDays((1..12).map { YearMonth.of(2026, it) }, today)
        assertEquals(listOf(31, 28, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0), thisYear)

        assertEquals(366, calendarElapsedDays((1..12).map { YearMonth.of(2024, it) }, today).sum())
        assertEquals(365, calendarElapsedDays((1..12).map { YearMonth.of(2025, it) }, today).sum())
        assertEquals(0, calendarElapsedDays((1..12).map { YearMonth.of(2027, it) }, today).sum())
    }

    private fun entry(kind: String) = JournalEntry(
        day = "2026-01-01",
        occurredAt = 0L,
        kind = kind,
        title = "",
        calories = 0.0,
    )
}
