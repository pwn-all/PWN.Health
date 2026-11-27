package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The second tier of food pictures: fourteen foods drawn one at a time, and the rule that keeps
 * every other food — and every burger, cheesecake and glass of milky tea — on its category plate.
 */
class TopFoodArtTest {
    private val drawn = listOf(
        Triple(R.drawable.ic_art_egg, "яйцо", "egg"),
        Triple(R.drawable.ic_art_bread, "хлеб", "bread"),
        Triple(R.drawable.ic_art_rice, "рис", "rice"),
        Triple(R.drawable.ic_art_chicken_breast, "куриная грудка", "chicken breast"),
        Triple(R.drawable.ic_art_milk, "молоко", "milk"),
        Triple(R.drawable.ic_art_banana, "банан", "banana"),
        Triple(R.drawable.ic_art_apple, "яблоко", "apple"),
        Triple(R.drawable.ic_art_potato, "картофель", "potato"),
        Triple(R.drawable.ic_art_grain, "овсянка", "oatmeal"),
        Triple(R.drawable.ic_art_cheese, "сыр", "cheese"),
        Triple(R.drawable.ic_art_yogurt, "йогурт", "yogurt"),
        Triple(R.drawable.ic_art_pasta, "макароны", "pasta"),
        Triple(R.drawable.ic_art_tomato, "помидор", "tomato"),
        Triple(R.drawable.ic_art_cucumber, "огурец", "cucumber"),
    )

    /**
     * Both languages reach all fourteen. A drawing nothing reaches is not merely a picture nobody
     * sees: Lint runs with warningsAsErrors, calls the file an unused resource and stops the build.
     */
    @Test
    fun everyDrawingIsReachableInRussianAndInEnglish() {
        assertEquals(14, drawn.size)
        drawn.forEach { (art, ru, en) ->
            assertEquals(ru, art, topFoodArt(ru))
            assertEquals(en, art, topFoodArt(en))
        }
    }

    /**
     * The words match the start of a word and never the middle of one, which is what separates
     * these from the category lists. Toffee is not rice, «пастила» is not pasta, «творог» is not a
     * wedge of cheese — and neither is cottage cheese, which is the same curd in English.
     */
    @Test
    fun aWordInsideAnotherWordIsNotAMatch() {
        assertNull(topFoodArt("ирис"))
        assertNull(topFoodArt("пастила"))
        assertNull(topFoodArt("творог"))
        assertNull(topFoodArt("cottage cheese"))
        assertNull(topFoodArt("молочный шоколад"))
        assertNull(topFoodArt("eggplant"))
    }

    /** Nothing drawn for them, so all four keep the plate their category hands out. */
    @Test
    fun theNearMissesKeepTheirOwnPlate() {
        assertEquals(FoodCategory.DAIRY.art(), foodArt("творог"))
        assertEquals(FoodCategory.DAIRY.art(), TOP_FOODS_BY_ID.getValue("cottage_cheese_5").art())
        assertEquals(FoodCategory.SWEET.art(), foodArt("молочный шоколад"))
        assertEquals(FoodCategory.SWEET.art(), TOP_FOODS_BY_ID.getValue("milk_chocolate").art())
        assertEquals(FoodCategory.GRAIN.art(), foodArt("пастила"))
    }

    /**
     * The rule that makes the second tier safe. The words do find a potato in "картофель фри" and
     * cheese in a cheeseburger; what stops either becoming the picture is that neither name is a
     * plain product, whatever [foodCategory] made of it.
     */
    @Test
    fun fastFoodKeepsItsPlateEvenWhenItsNameSaysPotato() {
        assertNotNull(topFoodArt("картофель фри"))
        assertEquals(foodCategory("картофель фри").art(), foodArt("картофель фри"))
        assertEquals(FoodCategory.FAST_FOOD.art(), foodArt("чизбургер"))
        assertEquals(FoodCategory.FAST_FOOD.art(), TOP_FOODS_BY_ID.getValue("fast_food_fries").art())
        assertEquals(FoodCategory.FAST_FOOD.art(), TOP_FOODS_BY_ID.getValue("cheeseburger").art())
    }

    /** And a plain catalogue product takes its portrait instead of its category's plate. */
    @Test
    fun aPlainProductTakesItsPortrait() {
        assertEquals(R.drawable.ic_art_banana, TOP_FOODS_BY_ID.getValue("banana").art())
        assertEquals(R.drawable.ic_art_egg, TOP_FOODS_BY_ID.getValue("egg_boiled").art())
        assertEquals(R.drawable.ic_art_milk, TOP_FOODS_BY_ID.getValue("milk_whole").art())
    }
}
