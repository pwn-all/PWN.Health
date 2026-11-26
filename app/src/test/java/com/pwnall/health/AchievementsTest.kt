package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {
    private fun days(vararg pairs: Pair<String, Long>) = pairs.map { DaySteps(it.first, it.second) }

    private fun medal(all: List<Achievement>, threshold: Long) = all.single { it.threshold == threshold }

    @Test
    fun everyMedalIsReportedBeforeASingleStepIsWalked() {
        val all = stepAchievements(emptyList(), 2026)

        assertEquals(STEP_MEDALS, all.map(Achievement::threshold))
        assertTrue(all.none(Achievement::earned))
        assertTrue(all.all { it.progress == 0f })
    }

    /** One ladder, so a medal is the sum of every day — no day of its own has to be remarkable. */
    @Test
    fun aMedalIsTheSumOfTheDaysAndNotTheBestOne() {
        val all = stepAchievements(
            days("2026-07-28" to 40_000L, "2026-07-29" to 40_000L, "2026-07-30" to 40_000L),
            2026,
        )

        assertTrue(medal(all, 100_000L).earned)
        assertEquals(120_000f / 500_000f, medal(all, 500_000L).progress, 0.001f)
    }

    @Test
    fun aMedalRecordsTheDayTheRunningTotalCrossedIt() {
        val all = stepAchievements(
            days("2026-07-01" to 60_000L, "2026-07-02" to 50_000L, "2026-07-03" to 40_000L),
            2026,
        )

        assertEquals("2026-07-02", medal(all, 100_000L).earnedOnDay)
    }

    /** A restored backup arrives in whatever order it was written; the dates decide, not the list. */
    @Test
    fun unorderedDaysStillCrossThresholdsChronologically() {
        val shuffled = days("2026-07-03" to 40_000L, "2026-07-01" to 60_000L, "2026-07-02" to 50_000L)

        assertEquals("2026-07-02", medal(stepAchievements(shuffled, 2026), 100_000L).earnedOnDay)
    }

    @Test
    fun aLockedMedalHasNoEarnedDayAndProgressTowardsTheTotal() {
        val all = stepAchievements(days("2026-07-29" to 150_000L, "2026-07-30" to 100_000L), 2026)

        assertFalse(medal(all, 500_000L).earned)
        assertNull(medal(all, 500_000L).earnedOnDay)
        assertEquals(0.5f, medal(all, 500_000L).progress, 0.001f)
    }

    @Test
    fun fiveMillionStepsEarnsEveryMedalAndNothingIsAskedBeyondIt() {
        val all = stepAchievements(days("2026-07-30" to 4_999_999L, "2026-07-31" to 1L), 2026)

        assertTrue(all.all(Achievement::earned))
        assertTrue(all.all { it.progress == 1f })
        assertEquals("2026-07-31", medal(all, 5_000_000L).earnedOnDay)
        // Walking past the last medal leaves the ring full rather than overflowing it.
        assertTrue(stepAchievements(days("2026-08-01" to 9_000_000L), 2026).all { it.progress == 1f })
    }

    /**
     * The whole point of the year: the same rows are a finished climb read as last year and barely
     * begun read as this one. Nothing goes and clears the medals on the 1st of January — December's
     * days simply stop being asked about, so a person who walked a million by Christmas starts the
     * new year on an empty ladder rather than looking at five discs they cannot win again.
     */
    @Test
    fun neitherLastYearsStepsNorLastYearsMedalsCrossIntoTheNewYear() {
        val acrossNewYear = days("2025-12-30" to 60_000L, "2025-12-31" to 50_000L, "2026-01-02" to 20_000L)

        val lastYear = stepAchievements(acrossNewYear, 2025)
        assertTrue(medal(lastYear, 100_000L).earned)
        assertEquals("2025-12-31", medal(lastYear, 100_000L).earnedOnDay)

        val thisYear = stepAchievements(acrossNewYear, 2026)
        assertFalse(medal(thisYear, 100_000L).earned)
        assertNull(medal(thisYear, 100_000L).earnedOnDay)
        assertEquals(20_000f / 100_000f, medal(thisYear, 100_000L).progress, 0.001f)
    }

    @Test
    fun badgeLabelsAreShort() {
        assertEquals(
            listOf("100k", "500k", "1M", "3M", "5M"),
            STEP_MEDALS.map(::badgeLabel),
        )
    }
}
