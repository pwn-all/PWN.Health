package com.pwnall.health

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SleepDetectionTest {
    @Test
    fun `overnight clock duration wraps at midnight`() {
        assertEquals(8 * 60, sleepDurationMinutes(23 * 60, 7 * 60))
    }

    @Test
    fun `equal clock values are not a valid sleep interval`() {
        assertEquals(0, sleepDurationMinutes(7 * 60, 7 * 60))
    }

    /**
     * A DST transition happens at 02:00, which is inside almost every night the sleep pickers can
     * describe, so this is the ordinary case twice a year rather than an edge one. Counting minutes
     * from the start of the day walks the instant time line and would store the wake time an hour
     * away from the clock the person had actually picked.
     */
    @Test
    fun `a picked wake time keeps its wall clock on the night the clocks go forward`() {
        val zone = ZoneId.of("America/New_York")

        val (start, end) = sleepEpochRange(LocalDate.of(2024, 3, 10), 23 * 60, 7 * 60, zone)

        assertEquals(LocalDateTime.of(2024, 3, 9, 23, 0), Instant.ofEpochMilli(start).atZone(zone).toLocalDateTime())
        assertEquals(LocalDateTime.of(2024, 3, 10, 7, 0), Instant.ofEpochMilli(end).atZone(zone).toLocalDateTime())
    }

    @Test
    fun `a picked wake time keeps its wall clock on the night the clocks go back`() {
        val zone = ZoneId.of("America/New_York")

        val (start, end) = sleepEpochRange(LocalDate.of(2024, 11, 3), 23 * 60, 7 * 60, zone)

        assertEquals(LocalDateTime.of(2024, 11, 2, 23, 0), Instant.ofEpochMilli(start).atZone(zone).toLocalDateTime())
        assertEquals(LocalDateTime.of(2024, 11, 3, 7, 0), Instant.ofEpochMilli(end).atZone(zone).toLocalDateTime())
    }

    @Test
    fun `night estimate selects the longest qualifying inactivity gap`() {
        val hour = 60L * 60 * 1_000
        val estimate = estimateNightSleep(
            interactions = listOf(1 * hour, 2 * hour, 10 * hour, 11 * hour),
            startEarliest = 0,
            startLatest = 4 * hour,
            wakeEarliest = 6 * hour,
            wakeLatest = 12 * hour,
        )

        assertEquals(2 * hour, estimate?.startAt)
        assertEquals(10 * hour, estimate?.endAt)
    }

    @Test
    fun `short phone break is not classified as sleep`() {
        val hour = 60L * 60 * 1_000
        val estimate = estimateNightSleep(
            interactions = listOf(2 * hour, 5 * hour),
            startEarliest = 0,
            startLatest = 4 * hour,
            wakeEarliest = 4 * hour,
            wakeLatest = 12 * hour,
        )

        assertNull(estimate)
    }

    /**
     * A phone set to restart itself after eight idle hours. The restart lights the lock screen;
     * filed as "the person was awake", it would cut the night's only candidate pair into 23:00 →
     * 03:20 (ending before wakeEarliest) and 03:21 → 07:00 (three and a half hours, under the four
     * hour floor). Both halves would be rejected, so a night that happened would produce nothing —
     * every night, because the restart lands at the same clock time.
     *
     * The windows here are the ones detectEstimatedSleep really passes, 04:00 being both the
     * latest bedtime and the earliest waking.
     */
    @Test
    fun `an unattended restart in the small hours does not erase the night`() {
        val zone = ZoneId.of("Europe/Moscow")
        fun at(day: Int, hour: Int, minute: Int = 0): Long =
            LocalDateTime.of(2026, 8, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
        val events = listOf(
            UsageEventRecord(UsageEvents.Event.ACTIVITY_RESUMED, at(19, 23)),
            UsageEventRecord(UsageEvents.Event.DEVICE_SHUTDOWN, at(20, 3, 19)),
            UsageEventRecord(UsageEvents.Event.DEVICE_STARTUP, at(20, 3, 20)),
            UsageEventRecord(UsageEvents.Event.SCREEN_INTERACTIVE, at(20, 3, 20)),
            UsageEventRecord(UsageEvents.Event.ACTIVITY_RESUMED, at(20, 3, 21)),
            UsageEventRecord(UsageEvents.Event.KEYGUARD_HIDDEN, at(20, 7)),
        )

        val estimate = estimateNightSleep(
            interactions = awakeMoments(events),
            startEarliest = at(19, 19),
            startLatest = at(20, 4),
            wakeEarliest = at(20, 4),
            wakeLatest = at(20, 12),
        )

        assertEquals(at(19, 23), estimate?.startAt)
        assertEquals(at(20, 7), estimate?.endAt)
    }

    /**
     * Every opening of the app reads the week's nights again, and a night whose estimate has not moved
     * is left alone: saved again, it would be stamped as edited by hand and sent to Health Connect once
     * more, seven rows at every return to the app.
     */
    @Test
    fun `a night already on file with the same hours is not written again`() {
        val hour = 60L * 60 * 1_000
        val estimate = EstimatedSleep(startAt = 23 * hour, endAt = 31 * hour)
        val onFile = JournalEntry(
            day = "2026-11-18",
            occurredAt = estimate.endAt,
            kind = "MEASUREMENT",
            title = "Sleep",
            calories = 0.0,
            sleepStartAt = estimate.startAt,
            sleepEndAt = estimate.endAt,
            measurementSource = PHONE_ESTIMATE,
        )

        assertTrue(estimateIsOnFile(onFile, estimate))
        assertFalse(estimateIsOnFile(null, estimate))
        // A morning still under way: the night grows as the phone reports its first touches.
        assertFalse(estimateIsOnFile(onFile, estimate.copy(endAt = 32 * hour)))
        assertFalse(estimateIsOnFile(onFile, estimate.copy(startAt = 22 * hour)))
    }

    /** Restarting the phone by hand and unlocking it is still a person getting up. */
    @Test
    fun `an unlock right after a restart is still a waking moment`() {
        val hour = 60L * 60 * 1_000
        val moments = awakeMoments(
            listOf(
                UsageEventRecord(UsageEvents.Event.ACTIVITY_RESUMED, 0),
                UsageEventRecord(UsageEvents.Event.DEVICE_STARTUP, 8 * hour),
                UsageEventRecord(UsageEvents.Event.SCREEN_INTERACTIVE, 8 * hour),
                UsageEventRecord(UsageEvents.Event.KEYGUARD_HIDDEN, 8 * hour + 30_000),
            ),
        )

        assertEquals(listOf(0L, 8 * hour + 30_000), moments)
    }

    /**
     * The nights remembered as estimated are the seven still looked back over, not a day for every
     * night ever estimated: only those seven are ever asked about.
     */
    @Test
    fun `only the nights still looked back over are remembered`() {
        val today = LocalDate.of(2026, 3, 2)
        val processed = setOf("2025-11-30", "2026-02-23", "2026-02-24", "2026-02-28", "2026-03-02")

        assertEquals(setOf("2026-02-24", "2026-02-28", "2026-03-02"), nightsStillLookedAt(processed, today))
    }

    /**
     * Two readings of the nights side by side — the app coming to the front, and the estimate switched
     * on from the Usage access page, which is a coming to the front as well — could each find a night
     * with no row and each write it one. The reading that checks and writes runs under one lock, and
     * nothing reaches it past the lock.
     */
    @Test
    fun `one reading of the nights runs at a time`() {
        val source = File("src/main/java/com/pwnall/health/SleepDetection.kt").readText()
        val detect = source.substringAfter("internal suspend fun detectEstimatedSleep(").substringBefore("\n}\n")

        assertTrue(detect, "sleepDetection.withLock { estimateNights(appContext, db) }" in detect)
        assertTrue("private suspend fun estimateNights(" in source)
        assertEquals(2, Regex("""\bestimateNights\(""").findAll(source).count())
    }
}
