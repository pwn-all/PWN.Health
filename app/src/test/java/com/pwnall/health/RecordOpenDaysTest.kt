package com.pwnall.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The open-day loop on virtual time: docs/levels.md promises that an app kept in the foreground
 * across midnight records the new date as well, and a foreground that stays on one day writes it
 * once rather than every round. That the table keeps one row for a day written twice is
 * [LevelBackupTest]'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordOpenDaysTest {
    /** The loop running from [start], its clock the scheduler's, and every date it wrote. */
    private fun TestScope.openFrom(start: LocalDateTime): List<LocalDate> {
        val written = mutableListOf<LocalDate>()
        backgroundScope.launch {
            recordOpenDays({ start.plusNanos(currentTime * 1_000_000L).toLocalDate() }) { written += it }
        }
        runCurrent()
        return written
    }

    @Test
    fun anAppLeftOpenThroughMidnightRecordsTheNewDayToo() = runTest {
        val written = openFrom(LocalDateTime.of(2026, 9, 10, 23, 59, 50))
        assertEquals(listOf(LocalDate.of(2026, 9, 10)), written)

        advanceTimeBy(OPEN_DAY_ROUND_MS + 1)

        assertEquals(listOf(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11)), written)
    }

    @Test
    fun aDayInFrontIsWrittenOnceNotEveryRound() = runTest {
        val written = openFrom(LocalDateTime.of(2026, 9, 10, 9, 0))

        advanceTimeBy(OPEN_DAY_ROUND_MS * 10)

        assertEquals(listOf(LocalDate.of(2026, 9, 10)), written)
    }
}
