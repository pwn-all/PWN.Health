package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NutritionAdviceTest {
    private val now = LocalDateTime.of(2026, 9, 11, 19, 0)
    private val adult = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 175, birthDate = "1990-01-01")

    @Test
    fun emptyDiaryIsAnUnloggedMealPromptFromNoonNotAClaimOfFasting() {
        ENERGY_GOALS.forEach { goal ->
            assertTrue(advice(goal, emptyList(), 11).isEmpty())
            val item = advice(goal, emptyList(), 12).single()
            assertEquals("nutrition-unlogged", item.id)
            assertEquals(R.string.nutrition_unlogged_body, item.bodyRes)
        }
    }

    @Test
    fun aZeroCalorieEntryDoesNotBecomeAnUnloggedDay() {
        val items = advice("MAINTAIN", listOf(meal(0.0)), 19)
        assertEquals("nutrition-very-low", items.single().id)
        assertEquals(listOf("0"), items.single().args)
    }

    @Test
    fun lowIntakeWaitsUntilTheAppropriatePartOfTheDay() {
        val target = target("MAINTAIN")
        val quarter = listOf(meal(target * 0.249))
        assertTrue(advice("MAINTAIN", quarter, 13).isEmpty())
        assertEquals("nutrition-very-low", advice("MAINTAIN", quarter, 14).single().id)
        assertTrue(advice("MAINTAIN", listOf(meal(target * 0.25)), 14).isEmpty())
        val half = listOf(meal(target * 0.5))
        assertTrue(advice("MAINTAIN", half, 17).isEmpty())
        assertEquals("nutrition-low", advice("MAINTAIN", half, 18).single().id)
        assertTrue(advice("MAINTAIN", listOf(meal(target * 0.6)), 18).isEmpty())
    }

    @Test
    fun lowIntakeUsesDifferentWordingForEachGoal() {
        val expected = mapOf(
            "MAINTAIN" to R.string.nutrition_tracking_low_body,
            "LOSE" to R.string.nutrition_lose_low_body,
            "GAIN" to R.string.nutrition_gain_low_body,
        )
        expected.forEach { (goal, body) ->
            val item = advice(goal, listOf(meal(target(goal) * 0.1))).single()
            assertEquals(body, item.bodyRes)
            assertEquals(AdviceLevel.ATTENTION, item.level)
        }
    }

    @Test
    fun losingWarnsAboveTargetButNotAtTarget() {
        assertTrue(advice("LOSE", listOf(meal(target("LOSE"))), 9).isEmpty())
        assertEquals("nutrition-lose-over", advice("LOSE", listOf(meal(target("LOSE") * 1.01)), 9).single().id)
    }

    @Test
    fun gainingWarnsOnlyAtThirtyPercentExcessAndTrackingAtTwenty() {
        assertTrue(advice("GAIN", listOf(meal(target("GAIN") * 1.299))).isEmpty())
        assertEquals("nutrition-gain-over", advice("GAIN", listOf(meal(target("GAIN") * 1.3))).single().id)
        assertTrue(advice("MAINTAIN", listOf(meal(target("MAINTAIN") * 1.199))).isEmpty())
        assertEquals("nutrition-tracking-over", advice("MAINTAIN", listOf(meal(target("MAINTAIN") * 1.2))).single().id)
    }

    @Test
    fun aGainGapWaitsUntilEveningAndEndsAtEightyFivePercent() {
        val low = listOf(meal(target("GAIN") * 0.8))
        assertTrue(advice("GAIN", low, 17).isEmpty())
        assertEquals("nutrition-low", advice("GAIN", low, 18).single().id)
        assertTrue(advice("GAIN", listOf(meal(target("GAIN") * 0.85)), 18).isEmpty())
    }

    @Test
    fun activityCreditUsesTheSameTargetAsTheFoodCard() {
        val base = target("LOSE")
        val food = listOf(meal(base * 1.1))
        assertNotNull(advice("LOSE", food).find { it.id == "nutrition-lose-over" })
        val withMovement = dailyNutritionAdvice(food, adult.copy(energyGoal = "LOSE"), now, base * 0.2)
        assertTrue(withMovement.isEmpty())
        assertEquals(base * 1.2, dailyFoodTarget(base, base * 0.2), 0.001)
        assertEquals(base, dailyFoodTarget(base, Double.NaN), 0.0)
        assertEquals(base, dailyFoodTarget(base, -50.0), 0.0)
    }

    @Test
    fun aSubstantialHighFatMealProducesCompositionFeedbackInEveryMode() {
        ENERGY_GOALS.forEach { goal ->
            val calories = target(goal) * 0.8
            val entry = meal(calories).copy(fatG = calories * 0.6 / 9.0, carbsG = calories * 0.4 / 4.0)
            val item = advice(goal, listOf(entry)).find { it.id == "nutrition-fat" }
            assertNotNull(item)
            assertEquals(listOf("60"), item?.args)
        }
    }

    @Test
    fun aSmallFattySnackDoesNotCharacteriseTheWholeDay() {
        val calories = target("MAINTAIN") * 0.1
        val entry = meal(calories).copy(fatG = calories / 9.0)
        assertNull(advice("MAINTAIN", listOf(entry), 10).find { it.id == "nutrition-fat" })
    }

    @Test
    fun knownSaturatedFatTakesPriorityOverTheGeneralFatMessage() {
        val target = target("MAINTAIN")
        val entry = meal(target * 0.8).copy(fatG = target * 0.4 / 9.0, saturatedFatG = target * 0.11 / 9.0)
        val items = advice("MAINTAIN", listOf(entry))
        assertEquals(listOf("nutrition-saturated-fat"), items.map { it.id })
        assertFalse(
            advice("MAINTAIN", listOf(entry.copy(saturatedFatG = null))).any {
                it.id ==
                    "nutrition-saturated-fat"
            },
        )
    }

    @Test
    fun proteinFeedbackRequiresCompleteMacrosAndAGainGoal() {
        val calories = target("GAIN") * 0.8
        val complete = meal(calories).copy(proteinG = 10.0, carbsG = (calories - 40.0) / 4.0)
        assertEquals("nutrition-protein", advice("GAIN", listOf(complete), 15).single().id)
        assertTrue(advice("GAIN", listOf(meal(calories)), 15).isEmpty())
        assertFalse(advice("GAIN", listOf(complete, meal(100.0)), 15).any { it.id == "nutrition-protein" })
        assertFalse(advice("MAINTAIN", listOf(complete), 15).any { it.id == "nutrition-protein" })
    }

    @Test
    fun invalidNutritionDoesNotInventCompositionOrZeroIntake() {
        assertTrue(advice("LOSE", listOf(meal(Double.NaN))).isEmpty())
        assertTrue(advice("LOSE", listOf(meal(-1.0))).isEmpty())
        val entry = meal(target("MAINTAIN") * 0.8)
        assertTrue(advice("MAINTAIN", listOf(entry.copy(fatG = Double.NaN))).isEmpty())
        assertTrue(advice("MAINTAIN", listOf(entry.copy(fatG = 10_000.0))).isEmpty())
    }

    @Test
    fun otherDaysAndOtherKindsDoNotCountAsTodaysFood() {
        val rows = listOf(
            meal(5_000.0).copy(day = "2026-09-10"),
            meal(5_000.0).copy(day = "2026-09-12"),
            meal(5_000.0).copy(kind = "ACTIVITY"),
        )
        assertEquals("nutrition-unlogged", advice("LOSE", rows).single().id)
    }

    @Test
    fun childrenDoNotReceiveWeightLossOrGainCoaching() {
        val child = adult.copy(birthDate = "2010-01-01", energyGoal = "LOSE")
        val items = dailyNutritionAdvice(listOf(meal(5_000.0)), child, now)
        assertFalse(items.any { it.id == "nutrition-lose-over" || it.id == "nutrition-gain-over" })
    }

    @Test
    fun allDailyNutritionAdviceUsesTheHideableInAppEnergyTopic() {
        val rows = listOf(meal(target("LOSE") * 1.5).copy(fatG = target("LOSE") * 0.8 / 9.0))
        val items = advice("LOSE", rows)
        assertEquals(2, items.size)
        assertTrue(items.all { it.topic == AdviceTopic.ENERGY && it.topic.inAppOnly && it.windowDays == 1 })
        assertTrue(items.all { it.sourceRes == R.string.nutrition_source })
    }

    private fun advice(goal: String, rows: List<JournalEntry>, hour: Int = 19) =
        dailyNutritionAdvice(rows, adult.copy(energyGoal = goal), now.withHour(hour))

    private fun target(goal: String) = energyBudget(adult.copy(energyGoal = goal), now.year).targetKcal

    private fun meal(calories: Double) =
        JournalEntry(
            day = now.toLocalDate().toString(),
            occurredAt = 0,
            kind = "EAT",
            title = "Food",
            calories = calories,
        )
}
