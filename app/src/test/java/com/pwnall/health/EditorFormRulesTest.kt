package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the editor's boxes let through to Save, and what they hand it back: [mealSaveable] and
 * [workoutMinutesValid], a saved meal's figures reopened ([asTypedInput]), a chat's plan
 * ([chatPlan]) and a session's End ([finishedSetsJson]).
 */
class EditorFormRulesTest {
    private val water = FoodState(title = "Water", serving = "250", reportedEnergy = "0")

    /** The catalogue's waters and zero colas carry a stated nought, and are a meal to log. */
    @Test
    fun aFoodWithNoEnergyIsLoggedWhereTheNoughtWasStated() {
        assertTrue(mealSaveable(water))
        assertTrue(mealSaveable(FoodState(title = "Tea", serving = "200", protein = "0")))
        // A name and a weight with nothing stated is a form left unfilled, not a food without energy.
        assertFalse(mealSaveable(FoodState(title = "Tea", serving = "200")))
        assertFalse(mealSaveable(water.copy(serving = "")))
        assertFalse(mealSaveable(water.copy(title = " ")))
    }

    /** A meal past the ceiling is a slip: seven figures typed into a box would go into the day's balance. */
    @Test
    fun aMealPastTheCeilingIsASlip() {
        val portion = FoodState(title = "Cake", reportedEnergy = "10000", basis = MealBasis.PER_PORTION)

        assertTrue(mealSaveable(portion))
        assertFalse(mealSaveable(portion.copy(reportedEnergy = "10001")))
        assertFalse(mealSaveable(FoodState(title = "Rice", serving = "9999999", carbs = "80")))
    }

    /** A workout row is filed under one day, and a day holds no more minutes than it has. */
    @Test
    fun aWorkoutIsNoLongerThanADay() {
        assertFalse(workoutMinutesValid(0.0))
        assertTrue(workoutMinutesValid(0.5))
        assertTrue(workoutMinutesValid(1_440.0))
        assertFalse(workoutMinutesValid(1_440.5))
        assertFalse(workoutMinutesValid(9_999_999.0))
    }

    /**
     * A saved meal reopens to the two decimals a box takes, so a Save of nothing writes back what was
     * saved: one decimal would turn 12.34 g into 12.3, and a 28.35 g ounce into 28.4.
     */
    @Test
    fun aSavedMealReopensAsItWasTyped() {
        assertEquals("12.34", 12.34.asTypedInput())
        assertEquals("28.35", 28.35.asTypedInput())
        assertEquals("14.1", 14.1.asTypedInput())
        assertEquals("100", 100.0.asTypedInput())
        assertEquals("", 0.0.asTypedInput())
        // Per 100 g through the portion it was stored as, and back.
        assertEquals("14.13", per100FromPortion(portionFromPer100(14.13, 220.0), 220.0).asTypedInput())
    }

    /** "Three sets of ten at sixty" opens as the three sets, not as one empty set. */
    @Test
    fun aChatsPlanOpensTheSessionOnItsSets() {
        val plan = chatPlan(sets = 3, reps = 10, load = "60")

        assertEquals(List(3) { PlannedSet(reps = "10", load = "60") }, plan)
        assertEquals(listOf(PlannedSet(reps = "100")), chatPlan(sets = null, reps = 100, load = ""))
        assertEquals(MAX_PLAN_SETS, chatPlan(sets = 50, reps = 5, load = "").size)
        // Nothing named, nothing planned: the session opens on its own one empty set.
        assertEquals(emptyList<PlannedSet>(), chatPlan(sets = null, reps = null, load = ""))
    }

    /**
     * Each row of a pair names the other's exercise, and a half corrected through a session still
     * names its partner on the day's list, as the other half does.
     */
    @Test
    fun aCorrectedHalfOfAPairStillNamesItsPartner() {
        val done = listOf(PlannedSet(reps = "8", load = "40", completed = true, actualSeconds = 30))
        val saved = done.encodeToJson(supersetWith = "SQUATS")

        assertEquals("SQUATS", supersetPartnerCode(finishedSetsJson(done, saved, loadUnitFactor = 1.0)))
        assertNull(supersetPartnerCode(finishedSetsJson(done, saved = null, loadUnitFactor = 1.0)))
        assertEquals(40.0, decodeSetsFromJson(finishedSetsJson(done, saved, 1.0)).single().load.toDouble(), 0.0)
    }
}
