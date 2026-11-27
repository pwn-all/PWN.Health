package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two rules of the merge that look past the row in hand: a day's total against its own hours, and a
 * work session against the ones already here. BackupMergeTest holds the rest.
 */
class BackupMergeExtraTest {
    private val tapped = 1_800_000_000_000L

    private fun work(id: Long, from: Long, minutes: Double) = JournalEntry(
        id = id,
        day = "2026-08-10",
        occurredAt = from,
        kind = KIND_WORK,
        title = "Work",
        calories = 0.0,
        durationMinutes = minutes,
    )

    /**
     * A move to a new phone at lunchtime: the old one counted the morning, the new one the afternoon.
     * The hours merge to the whole day; a total taking the larger half alone would come back short.
     */
    @Test
    fun `a day two phones counted halves of is the sum of its merged hours`() {
        val day = "2026-08-10"
        // The table once the file's hours are in: the morning from the file, the afternoon from here.
        val hours = listOf(
            HourSteps(day, 8, 2_500),
            HourSteps(day, 9, 1_500),
            HourSteps(day, 14, 3_000),
            HourSteps(day, 15, 2_000),
            HourSteps("2026-08-11", 9, 7_000),
        )
        val merged = mergeDaySteps(listOf(DaySteps(day, 5_000)), listOf(DaySteps(day, 4_000)))

        assertEquals(listOf(DaySteps(day, 9_000)), withHourTotals(merged, hours))
        // A total above its hours — a day from before they were kept — stays as it is.
        assertEquals(listOf(DaySteps(day, 9_500)), withHourTotals(listOf(DaySteps(day, 9_500)), hours))
    }

    /**
     * A work session corrected here since another phone's file was written is not the row the file
     * holds, and the old version would come back beside the correction, counting the hours both cover
     * twice. A session that shares no worked time, and every other kind, still comes in.
     */
    @Test
    fun `a work session overlapping one here stays out of the restore`() {
        val hour = 60 * 60 * 1000L
        val corrected = work(1, tapped, 9 * 60.0)
        val old = work(7, tapped, 8 * 60.0)
        val evening = work(8, tapped + 9 * hour, 60.0)
        val water = JournalEntry(
            id = 9,
            day = "2026-08-10",
            occurredAt = tapped,
            kind = KIND_WATER,
            title = "Water",
            calories = 0.0,
            servingG = 250.0,
        )

        assertEquals(
            listOf(evening, water),
            withoutOverlappingWork(listOf(old, evening, water), here = listOf(corrected)),
        )
    }
}
