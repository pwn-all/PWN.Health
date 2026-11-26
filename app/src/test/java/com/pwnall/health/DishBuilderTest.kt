package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The dish a person assembles from catalogue rows instead of hunting for it pre-cooked. The
 * arithmetic is the photograph path's — this holds the part the buttons touch, and the sum that
 * lands in the journal.
 */
class DishBuilderTest {
    @Test
    fun aPortionAtATimeUpAndDown() {
        assertEquals("50", "".stepped(50.0, 1))
        assertEquals("100", "50".stepped(50.0, 1))
        assertEquals("50", "100".stepped(50.0, -1))
        // Never below nothing: tapping minus on an empty row is not a debt of fifty grams.
        assertEquals("0", "20".stepped(50.0, -1))
        // Whole grams, because no kitchen scale reads 133.3.
        assertEquals("133", "100".stepped(33.3, 1))
    }

    /** A fillet, an egg and a tomato add up to one meal, and the numbers are the sum. */
    @Test
    fun theDishIsTheSumOfItsProducts() {
        val fillet = requireNotNull(TOP_FOODS.firstOrNull { it.id == "chicken_breast_grilled" })
        val egg = requireNotNull(TOP_FOODS.firstOrNull { it.id.startsWith("egg_fried") || it.id == "egg_boiled" })
        val items = listOf(DishItem(fillet, 150.0), DishItem(egg, 50.0))

        val state = FoodState().withPhotoDish("Ужин", "Собрано из продуктов", items)

        val expected = items.sumOf { it.food.nutrition.kcal * it.grams / 100.0 }
        assertEquals(expected, state.portionCalories, 1.0)
        assertEquals("200", state.serving)
        assertEquals("Ужин", state.title)
    }

    /** A row left at zero grams is a product somebody changed their mind about, not a zero-weight food. */
    @Test
    fun emptyRowsNeverReachTheJournal() {
        val fillet = requireNotNull(TOP_FOODS.firstOrNull { it.id == "chicken_breast_grilled" })
        val items = listOf(DishItem(fillet, 150.0), DishItem(fillet, 0.0)).filter { it.grams > 0.0 }

        assertEquals(1, items.size)
        assertEquals(150.0, items.sumOf(DishItem::grams), 0.01)
    }

    /** The step is the food's own portion, and the row says so in the person's language. */
    @Test
    fun theStepIsThePortionTheCatalogueDeclares() {
        val egg = requireNotNull(TOP_FOODS.firstOrNull { it.id == "egg_boiled" })

        assertEquals(egg.defaultPortion.grams, "".stepped(egg.defaultPortion.grams, 1).toDouble(), 0.51)
        assertEquals("1 шт.", egg.defaultPortion.label.localized(Locale.forLanguageTag("ru")))
    }

    /**
     * Only food that is genuinely counted gets the buttons. An egg is one egg; buckwheat's
     * "portion" is a bare 150 g somebody typed, and USDA's is "Quantity not specified" or "1 cup"
     * — none of which is a thing to tap.
     */
    @Test
    fun onlyCountableFoodOffersAStep() {
        fun countable(id: String) =
            requireNotNull(TOP_FOODS.firstOrNull { it.id == id }).defaultPortion.stepsInPieces

        assertTrue(countable("egg_boiled"))
        assertFalse(countable("buckwheat"))
        assertFalse(
            TOP_FOODS.filter { it.defaultPortion.label.en.contains("Quantity", ignoreCase = true) }
                .any { it.defaultPortion.stepsInPieces },
        )
        assertFalse(
            TOP_FOODS.filter { it.defaultPortion.label.en.equals("cup", ignoreCase = true) }
                .any { it.defaultPortion.stepsInPieces },
        )
    }
}
