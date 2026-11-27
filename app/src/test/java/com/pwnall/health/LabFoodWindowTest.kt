package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The arithmetic behind "what were you eating when this was drawn".
 *
 * The thing worth guarding is that the window is anchored on the *draw* and never on today, since
 * that is the whole reason the function exists — every other food rule in the app counts back from
 * the current date, which describes a panel from three years ago by last fortnight's dinners.
 */
class LabFoodWindowTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(day: LocalDate, hour: Int = 12): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun meal(day: LocalDate, saturated: Double?, fiber: Double? = null, kcal: Double = 700.0) =
        JournalEntry(
            day = day.toString(),
            occurredAt = at(day),
            kind = "EAT",
            title = "meal",
            calories = kcal,
            saturatedFatG = saturated,
            fiberG = fiber,
        )

    /** Two rows a day, because a day carrying one is a day somebody stopped logging halfway. */
    private fun diary(from: LocalDate, days: Int, saturated: Double, fiber: Double = 20.0) =
        (0 until days).flatMap { offset ->
            val day = from.plusDays(offset.toLong())
            listOf(meal(day, saturated / 2, fiber / 2), meal(day, saturated / 2, fiber / 2))
        }

    private fun ldl(drawnOn: LocalDate) = LabReading(
        marker = LabMarker.LDL,
        value = 4.2,
        occurredAt = at(drawnOn),
        day = drawnOn.toString(),
        fasting = true,
        ageYears = 40,
        status = LabStatus.ABOVE,
        entryId = 1L,
    )

    @Test
    fun theWindowIsCountedBackFromTheDrawAndNotFromToday() {
        val drawn = LocalDate.of(2023, 3, 15)
        // Six weeks of a heavy diet before a draw two and a half years ago, and a light diet since.
        val journal = diary(drawn.minusDays(41), 42, saturated = 30.0) +
            diary(LocalDate.of(2026, 8, 1), 42, saturated = 4.0)

        val window = foodBeforeDraw(journal, ldl(drawn))!!

        assertEquals(42, window.windowDays)
        assertEquals(drawn, window.drawDay)
        assertTrue(window.enough)
        val saturated = window.readings.first { it.labelRes == R.string.labs_diet_saturated }
        // The 2023 diet, not the 2026 one: 30 g, and nothing in between.
        assertEquals(30.0, saturated.meanPerDay, 0.01)
    }

    @Test
    fun aThinDiaryIsRefusedRatherThanAveraged() {
        val drawn = LocalDate.of(2024, 6, 1)
        val journal = diary(drawn.minusDays(5), 6, saturated = 30.0)

        val window = foodBeforeDraw(journal, ldl(drawn))!!

        assertFalse(window.enough)
        assertEquals(6, window.daysLogged)
        assertTrue(window.readings.isEmpty())
    }

    /** A day with a single row is somebody who logged breakfast and got on with their morning. */
    @Test
    fun halfLoggedDaysAreNotCountedAsDaysOfLittleFood() {
        val drawn = LocalDate.of(2024, 6, 1)
        val journal = (0 until 42).map { meal(drawn.minusDays(it.toLong()), saturated = 15.0) }

        val window = foodBeforeDraw(journal, ldl(drawn))!!

        assertEquals(0, window.daysLogged)
        assertFalse(window.enough)
    }

    @Test
    fun aMarkerWithNoDietaryDriverSaysNothing() {
        val drawn = LocalDate.of(2024, 6, 1)
        val journal = diary(drawn.minusDays(41), 42, saturated = 30.0)
        val tsh = ldl(drawn).copy(marker = LabMarker.TSH, value = 6.0)

        assertNull(foodBeforeDraw(journal, tsh))
    }

    /**
     * HbA1c leans on the weeks nearest the draw, because that is how the molecule is formed — see
     * [LabDietWindow.halfLifeDays]. A flat mean of the same days would sit halfway between.
     */
    @Test
    fun aWeightedWindowLeansTowardsTheDraw() {
        val drawn = LocalDate.of(2024, 6, 1)
        val perDay = (0 until 90).associate { offset ->
            drawn.minusDays(offset.toLong()) to if (offset < 45) 100.0 else 0.0
        }

        val flat = weightedMean(perDay, drawn, halfLifeDays = null)
        val weighted = weightedMean(perDay, drawn, halfLifeDays = 35.0)

        assertEquals(50.0, flat, 0.01)
        assertTrue("weighted=$weighted", weighted > 70.0)
    }

    /** Nothing in the window at all is not the same question as a marker nobody measures a diet for. */
    @Test
    fun anEmptyDiaryStillNamesTheWindowItCouldNotDescribe() {
        val window = foodBeforeDraw(emptyList(), ldl(LocalDate.of(2020, 1, 20)))!!

        assertEquals(0, window.daysLogged)
        assertEquals(42, window.windowDays)
        assertFalse(window.enough)
    }

    /**
     * A drink is a row of its own, so a logged day without one is a day of none: two glasses in a
     * fully logged fortnight are three grams a day, not the twenty-one of a day with a drink.
     */
    @Test
    fun aLoggedDayWithoutADrinkIsADayOfNoneForTheAlcohol() {
        val drawn = LocalDate.of(2024, 6, 1)
        val drinks = listOf(0L, 7L).map { back ->
            JournalEntry(
                day = drawn.minusDays(back).toString(),
                occurredAt = at(drawn.minusDays(back), 20),
                kind = KIND_ALCOHOL,
                title = "wine",
                calories = 0.0,
                servingG = 21.0,
            )
        }
        val urate = ldl(drawn).copy(marker = LabMarker.URIC_ACID, value = 480.0)

        val window = foodBeforeDraw(diary(drawn.minusDays(13), 14, saturated = 20.0) + drinks, urate)!!

        assertTrue(window.enough)
        assertEquals(3.0, window.readings.single { it.labelRes == R.string.labs_diet_ethanol }.meanPerDay, 0.01)
    }
}
