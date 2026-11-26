package com.pwnall.health

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LevelsTest {
    private val today = LocalDate.of(2026, 9, 11)
    private val zone = ZoneId.of("Asia/Dubai")

    @Test
    fun everyTransitionConsumesItsOwnCostAndStopsAt99() {
        val costs = listOf(50L, 100L, 200L, 350L, 600L, 1_000L, 1_300L, 1_600L, 2_000L, 3_000L)
        assertEquals(costs, (0..9).map(::levelCost))
        var total = 0L
        for (level in 0 until MAX_USER_LEVEL) {
            val cost = if (level < 10) costs[level] else 1_000L
            assertEquals(LevelProgress(level, 0L, cost), levelProgress(total))
            assertEquals(LevelProgress(level, cost - 1L, cost), levelProgress(total + cost - 1L))
            total += cost
        }
        assertEquals(LevelProgress(99, 0L, 0L), levelProgress(total))
        assertEquals(LevelProgress(99, 0L, 0L), levelProgress(Long.MAX_VALUE))
        assertEquals(LevelProgress(0, 0L, 50L), levelProgress(-1L))
    }

    @Test
    fun openingRewardsChangeOnDaysSevenAndThirtyWithoutRetroactiveBonuses() {
        val start = today.minusDays(30)
        fun xp(
            count: Int,
        ) = openingXp((0 until count).map { AppOpenDay(start.plusDays(it.toLong()).toString()) }, today)
        assertEquals(6L, xp(6))
        assertEquals(8L, xp(7))
        assertEquals(52L, xp(29))
        assertEquals(55L, xp(30))
        assertEquals(58L, xp(31))
    }

    @Test
    fun repeatedOpensAreOneDayAndAMissedDayRestartsTheStreak() {
        val days = (0..6).map { AppOpenDay(today.minusDays(9L - it).toString()) }
        val returning = AppOpenDay(today.toString())
        assertEquals(9L, openingXp((days + returning + returning).reversed(), today))
        assertEquals(9L, openingXp(days + returning + AppOpenDay("invalid") + AppOpenDay("2027-01-01"), today))
    }

    @Test
    fun newYearOnlyKeepsTheOpeningStreakNotLastYearsXp() {
        val newYear = LocalDate.of(2027, 1, 1)
        val opens = (0..6).map { AppOpenDay(newYear.minusDays(it.toLong()).toString()) }
        val xp = annualXp(
            newYear,
            listOf(DaySteps("2026-12-31", 100_000)),
            listOf(entry("EAT", "2026-12-31"), entry("ACTIVITY", "2026-12-31")),
            listOf(visit("2026-12-31")),
            opens,
            zone,
        )
        assertEquals(AnnualXp(opens = 2L), xp)
        assertEquals(0, xp.progress.level)
    }

    @Test
    fun stepRemaindersDoNotCarryBetweenDaysAndMedalsCountOnceEach() {
        val xp = calculate(days = listOf(DaySteps("2026-09-10", 999), DaySteps("2026-09-11", 1_999)))
        assertEquals(1L, xp.steps)
        assertEquals(0L, xp.medals)
        val medal = calculate(days = listOf(DaySteps("2026-09-11", 500_000)))
        assertEquals(500L, medal.steps)
        assertEquals(100L, medal.medals)
        assertEquals(medal, calculate(days = listOf(DaySteps("2026-09-11", 500_000))))
        assertEquals(0L, calculate(days = listOf(DaySteps("2026-09-11", -1))).total)
    }

    @Test
    fun foodAwardsPerEntryAndWorkoutAwardsOnlyOncePerDay() {
        val rows = listOf(entry("EAT"), entry("EAT"), entry("ACTIVITY"), entry("ACTIVITY"), entry("WATER"))
        assertEquals(AnnualXp(meals = 2L, workouts = 2L), calculate(journal = rows))
        val edited = rows.map { it.copy(title = "Edited", calories = 900.0) }
        assertEquals(calculate(journal = rows), calculate(journal = edited))
        assertEquals(1L, calculate(journal = rows.drop(1)).meals)
        assertEquals(4L, calculate(journal = rows + entry("ACTIVITY", "2026-09-10")).workouts)
    }

    @Test
    fun bookedOrCancelledVisitsEarnNothingUntilMarkedCompleted() {
        val done = visit(today.toString())
        val booked = done.copy(id = 2, doneAt = null)
        assertEquals(2L, calculate(visits = listOf(done, booked)).visits)
        assertEquals(0L, calculate(visits = listOf(booked)).visits)
        assertEquals(0L, calculate(visits = listOf(visit("2027-01-01"))).visits)
    }

    @Test
    fun completedVisitUsesTheLocalCompletionDate() {
        val instant = java.time.Instant.parse("2025-12-31T21:00:00Z").toEpochMilli()
        val visit = Appointment(specialty = "DENTAL", atMs = instant, doneAt = instant)
        assertEquals(2L, calculate(visits = listOf(visit)).visits)
        assertEquals(0L, annualXp(today, emptyList(), emptyList(), listOf(visit), emptyList(), ZoneId.of("UTC")).visits)
    }

    @Test
    fun futureRecordsAndOtherYearsDoNotAdvanceThisYearsLevel() {
        val xp = calculate(
            days = listOf(DaySteps("2027-01-01", 5_000_000), DaySteps("2026-09-12", 5_000_000)),
            journal = listOf(entry("EAT", "2025-12-31"), entry("ACTIVITY", "2026-09-12")),
        )
        assertEquals(0L, xp.total)
        assertTrue(xp.progress.fraction == 0f)
    }

    /**
     * The four tables answer one after another, and the year is counted once the last of them has.
     * Counted from whichever had arrived, the badge would open every visit on a zero and run up
     * through partial sums to the level it had shown a moment before.
     */
    @Test
    fun theYearIsCountedOnlyOnceEveryTableHasAnswered() = runTest {
        val days = listOf(DaySteps(today.toString(), 12_000L))
        val opens = listOf(AppOpenDay(today.toString()))
        fun <T> answering(afterMs: Long, rows: List<T>) = flow {
            delay(afterMs)
            emit(rows)
        }

        val counts = annualXpFlow(
            today,
            zone,
            answering(30L, days),
            answering(10L, listOf(entry("EAT"))),
            answering(20L, emptyList<Appointment>()),
            answering(40L, opens),
        ).toList()

        assertEquals(listOf(annualXp(today, days, listOf(entry("EAT")), emptyList(), opens, zone)), counts)
        assertTrue(counts.single().total > 0L)
    }

    private fun calculate(
        days: List<DaySteps> = emptyList(),
        journal: List<JournalEntry> = emptyList(),
        visits: List<Appointment> = emptyList(),
    ) = annualXp(today, days, journal, visits, emptyList(), zone)

    private fun entry(kind: String, day: String = today.toString()) =
        JournalEntry(day = day, occurredAt = 0, kind = kind, title = kind, calories = 0.0)

    private fun visit(day: String): Appointment {
        val at = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()
        return Appointment(id = 1, specialty = "DENTAL", atMs = at, doneAt = at)
    }
}
