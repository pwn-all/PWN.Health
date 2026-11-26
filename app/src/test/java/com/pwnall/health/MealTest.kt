package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A worked example: chicken breast, 220 g, typed per 100 g as 14.1 g protein / 1.6 g fat /
 * 0 g carbohydrate / 165 kcal, which the meal screen renders for the portion as 31.0 / 3.6 / 0 / 363.
 */
class MealTest {
    @Test
    fun theReferenceExampleScalesToItsPrintedNumbers() {
        assertEquals(31.0, portionFromPer100(14.1, 220.0), 0.05)
        assertEquals(3.5, portionFromPer100(1.6, 220.0), 0.05)
        assertEquals(0.0, portionFromPer100(0.0, 220.0), 0.001)
        assertEquals(363.0, portionFromPer100(165.0, 220.0), 0.5)
    }

    @Test
    fun theTwoBasesAreInverses() {
        val portion = portionFromPer100(14.1, 220.0)

        assertEquals(14.1, per100FromPortion(portion, 220.0), 0.001)
    }

    @Test
    fun sourceReportedEnergyWinsAndManualFoodStillUsesAtwater() {
        val catalogFood = FoodState(
            serving = "205",
            protein = "11.9",
            fat = "14.1",
            carbs = "21.5",
            reportedEnergy = "261",
        )
        val manualFood = catalogFood.copy(reportedEnergy = "")

        assertEquals(535.05, catalogFood.portionCalories, 0.01)
        assertEquals(
            macroCalories(manualFood.portionProtein, manualFood.portionFat, manualFood.portionCarbs),
            manualFood.portionCalories,
            0.001,
        )
    }

    /** A meal with no weight yet must not divide by it — the editor shows zeros until one is typed. */
    @Test
    fun noWeightMeansNoScaling() {
        assertEquals(0.0, portionFromPer100(14.1, 0.0), 0.001)
        assertEquals(0.0, per100FromPortion(31.0, 0.0), 0.001)
        assertEquals(0.0, portionFromPer100(14.1, -5.0), 0.001)
    }

    @Test
    fun barsSplitTheMacroMassAndNeverExceedTheirTrack() {
        assertEquals(0.5f, macroShare(10.0, 10.0, 5.0, 5.0), 0.001f)
        assertEquals(0.25f, macroShare(5.0, 10.0, 5.0, 5.0), 0.001f)
        // Nothing logged yet: an empty track rather than a division by zero.
        assertEquals(0f, macroShare(0.0, 0.0, 0.0, 0.0), 0.001f)
        // A negative typed into a field must not draw a bar backwards.
        assertEquals(0f, macroShare(-3.0, 10.0, 5.0, 5.0), 0.001f)
    }

    /** Every number the meal screen prints, in the format it prints it. */
    @Test
    fun numbersPrintTheWayTheReferenceDoes() {
        assertEquals("31.0", 31.02.asGramText())
        assertEquals("3.6", 3.55.asGramText())
        assertEquals("14.1", 14.1.asGramText())
        // Exactly nothing is "0 г", not "0,0 г".
        assertEquals("0", 0.0.asGramText())

        assertEquals("363", 363.0.asKcalText())
        assertEquals("165", 165.4.asKcalText())

        assertEquals("220", 220.0.asWeightText())
        assertEquals("12.5", 12.5.asWeightText())
    }

    /** The decimal separator follows the locale: Russian writes a comma. */
    @Test
    fun theSeparatorFollowsTheLocale() {
        assertEquals("3,6", 3.6.asGramText(java.util.Locale.forLanguageTag("ru")))
        assertEquals("12,5", 12.5.asWeightText(java.util.Locale.forLanguageTag("ru")))
    }

    @Test
    fun theCategoryIconIsGuessedFromTheNameInBothLanguages() {
        assertEquals(FoodCategory.MEAT, foodCategory("Куриная грудка"))
        assertEquals(FoodCategory.MEAT, foodCategory("grilled chicken"))
        assertEquals(FoodCategory.DAIRY, foodCategory("Творог 5%"))
        assertEquals(FoodCategory.GRAIN, foodCategory("Гречка отварная"))
        assertEquals(FoodCategory.FRUIT, foodCategory("Банан"))
    }

    /** Naming a drink outranks the ingredient in it: this is a coffee, not a dairy product. */
    @Test
    fun aDrinkOutranksWhatIsInIt() {
        assertEquals(FoodCategory.DRINK, foodCategory("Кофе с молоком"))
        assertEquals(FoodCategory.DRINK, foodCategory("Яблочный сок"))
        assertEquals(FoodCategory.DAIRY, foodCategory("Молоко 3,2%"))
    }

    /**
     * A portion still matches itself after a round trip through the text field.
     *
     * USDA's ounce is 28.35 g, which prints as "28.4" and parses back as 28.4 — so an exact
     * comparison could never light the chip that had just been tapped.
     */
    @Test
    fun aPortionSurvivesBeingWrittenDown() {
        val ounce = 28.35
        assertTrue(ounce.sameWeightAs(ounce.asWeightText().toDouble()))
        assertTrue(150.0.sameWeightAs(150.0))
        assertFalse(150.0.sameWeightAs(151.0))
        assertFalse(28.35.sameWeightAs(28.5))
    }

    /** An unknown name gets the neutral icon; guessing wrong looks worse than not guessing. */
    @Test
    fun anUnknownNameGetsTheNeutralIcon() {
        assertEquals(FoodCategory.OTHER, foodCategory("Бажин по-ливийски"))
        assertEquals(FoodCategory.OTHER, foodCategory(""))
        assertEquals(FoodCategory.OTHER, foodCategory("   "))
    }

    /**
     * Five groups that only their words make guessable.
     *
     * Without them five of the thirteen drawn plates would be unreachable by name — a shawarma, a bowl
     * of borscht and a bag of crisps would come back [FoodCategory.OTHER] and wear the
     * nothing-in-particular plate. "Шаверма по-питерски" is here on purpose: it is exactly the kind of
     * name there is no excuse for not guessing.
     */
    @Test
    fun theLateGroupsAreReachableByName() {
        assertEquals(FoodCategory.FAST_FOOD, foodCategory("Шаверма по-питерски"))
        assertEquals(FoodCategory.FAST_FOOD, foodCategory("Pizza margherita"))
        assertEquals(FoodCategory.DISH, foodCategory("Борщ"))
        assertEquals(FoodCategory.SNACK, foodCategory("Чипсы с паприкой"))
        assertEquals(FoodCategory.NUTS, foodCategory("Грецкий орех"))
    }

    /**
     * The order of the lists is the tie-break, and the last five groups come after the others.
     *
     * So a name an earlier group already files somewhere sensible is not quietly re-filed. A borscht
     * with beef in its name is meat first, exactly as "куриный суп" is, and almond butter is dairy.
     */
    @Test
    fun theEarlierListsStillWinTies() {
        assertEquals(FoodCategory.MEAT, foodCategory("Борщ с говядиной"))
        assertEquals(FoodCategory.MEAT, foodCategory("Куриный суп"))
        assertEquals(FoodCategory.DAIRY, foodCategory("Almond butter"))
    }

    /** Nuts are a group of their own, not the fruit list's, where they would be drawn as fruit. */
    @Test
    fun nutsAreNutsRatherThanFruit() {
        assertEquals(FoodCategory.NUTS, foodCategory("Миндаль"))
        assertEquals(FoodCategory.NUTS, foodCategory("Almond"))
        assertEquals(FoodCategory.FRUIT, foodCategory("Яблоко"))
    }
}
