package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopFoodCatalogTest {
    /** The CSV is hand-edited, so the parse itself is the thing most likely to break. */
    @Test
    fun everyCatalogRowParses() {
        assertTrue(TOP_FOODS.size >= 150)
        assertEquals(TOP_FOODS.size, TOP_FOODS.map(TopFood::id).toSet().size)
        assertTrue(TOP_FOODS.all { it.portions.isNotEmpty() && it.nutrition.kcal >= 0.0 })
        assertTrue(TOP_FOODS.all { food -> food.portions.all { it.grams > 0.0 } })
    }

    /**
     * The pass the app's window runs off the main thread as it opens leaves a page nothing to build:
     * every row is in, and every row has the phrases a search or a recent meal's picture asks for.
     */
    @Test
    fun warmingReadiesEveryRowAndItsSearchPhrases() {
        val phrases = warmFoodCatalog()

        assertEquals(TOP_FOODS.sumOf { it.searchIndex.size }, phrases)
        assertTrue(TOP_FOODS.all { it.searchIndex.isNotEmpty() })
    }

    /** Every group the picker offers a chip for must have something behind it. */
    @Test
    fun catalogCoversTheGroupsItAdvertises() {
        assertTrue(TOP_FOOD_GROUPS.size >= 10)
        assertEquals(TOP_FOOD_GROUPS.toSet(), TOP_FOODS.map(TopFood::group).toSet())
        assertTrue(FoodCategory.OTHER !in TOP_FOOD_GROUPS)
    }

    @Test
    fun searchUnderstandsRussianEnglishAndCommonTypos() {
        assertEquals("big_mac", searchTopFoods("Big Mac", limit = 1).single().id)
        assertEquals("fast_food_fries", searchTopFoods("fired fries", limit = 1).single().id)
        assertEquals("fast_food_fries", searchTopFoods("картофель фри", limit = 1).single().id)
        // Cooking method is a search synonym, not a row of its own: the boiled, grilled and fried
        // breasts are one product, and their words are its aliases.
        assertEquals("chicken_breast_grilled", searchTopFoods("курица вареная", limit = 1).single().id)
    }

    /**
     * Taking fat out of a dairy product concentrates everything the fat displaced, which is why
     * skimmed milk lists more protein per 100 g than whole. Cholesterol is the one that rides with
     * the fat instead of against it.
     */
    @Test
    fun fatPercentRescalesWhatTheFatDisplaces() {
        val whole = CatalogNutrition(kcal = 61.0, proteinG = 3.2, fatG = 3.3, carbsG = 4.8, cholesterolMg = 10.0)

        val skimmed = whole.atFatPercent(0.5)

        assertEquals(0.5, skimmed.fatG, 0.001)
        assertTrue("protein per 100 g must rise as fat falls", skimmed.proteinG > whole.proteinG)
        assertEquals(3.29, skimmed.proteinG, 0.02)
        assertEquals(36.7, skimmed.kcal, 0.2)
        assertTrue("cholesterol follows the fat", skimmed.cholesterolMg!! < whole.cholesterolMg!!)
        // Asking for the fat it already has must change nothing.
        assertEquals(whole.kcal, whole.atFatPercent(3.3).kcal, 0.001)
    }

    /** A rail the product's own figure sits outside of would scale the rest of the row into nonsense. */
    @Test
    fun everyFatRailBracketsTheRowItBelongsTo() {
        val railed = TOP_FOODS.filter { it.fatSteps.isNotEmpty() }

        assertTrue(railed.size >= 15)
        assertTrue(railed.all { it.fatSteps.size in 2..6 })
        assertTrue(railed.all { food -> food.fatSteps == food.fatSteps.sorted() })
        assertTrue(
            railed.all { food -> food.nutrition.fatG in (food.fatSteps.min() - 2.0)..(food.fatSteps.max() + 2.0) },
        )
        // The percentage belongs on the chip, not in the name the picker shows.
        assertTrue(railed.none { "%" in it.name.ru || "%" in it.name.en })
    }

    @Test
    fun categoryFilterNeverLeaksAnotherGroup() {
        val fish = searchTopFoods("", FoodCategory.FISH)

        assertTrue(fish.isNotEmpty())
        assertTrue(fish.all { it.group == FoodCategory.FISH })
    }

    @Test
    fun friesCarryRealPortionChoicesRatherThanOneMagicWeight() {
        val fries = TOP_FOODS.single { it.id == "fast_food_fries" }

        assertEquals(listOf(110.0, 70.0, 145.0, 180.0), fries.portions.map { it.grams })
        assertEquals("средняя", fries.portions[2].label.ru)
        assertEquals(452.4, portionFromPer100(fries.nutrition.kcal, 145.0), 0.05)
    }

    /**
     * The weight form starts from the default portion, and the script writes portions smallest
     * first: "1 surface inch" of pizza, oat milk "per fl oz", a pound of back fat. A helping wins
     * where the row has one; a food that comes only in spoons and cups is eaten by the spoon.
     */
    @Test
    fun theDefaultPortionIsAHelping() {
        fun grams(id: String) = requireNotNull(TOP_FOODS_BY_ID[id]).defaultPortion.grams

        assertEquals(61.0, grams("breakfast_pizza_with_egg"), 0.01)
        assertEquals(14.0, grams("olive_oil"), 0.01)
        assertEquals(240.0, grams("water_nfs"), 0.01)
        assertEquals(113.0, grams("beef_rib_back_ribs"), 0.01)
        assertEquals(113.0, grams("pork_fresh_backfat"), 0.01)
        assertTrue(grams("oat_milk") >= 10.0)
        // Hand-written rows keep the portion somebody put first.
        assertEquals(110.0, grams("fast_food_fries"), 0.01)
        assertEquals(50.0, grams("egg_boiled"), 0.01)

        val unitLike = TOP_FOODS.count { MEASURE_LABEL.containsMatchIn(it.defaultPortion.label.en.lowercase()) }
        println("top_foods.csv: $unitLike of ${TOP_FOODS.size} default portions are still a measure, not a helping")
        assertTrue(unitLike < TOP_FOODS.size / 2)
    }

    @Test
    fun everyDefaultPortionIsAtLeastFiveGrams() {
        val crumbs = TOP_FOODS.filter { it.defaultPortion.grams < 5.0 }.map { "${it.id} ${it.defaultPortion.grams}" }

        assertEquals(emptyList<String>(), crumbs)
    }

    /** Two rows the picker shows as the same Russian word are one row to the person choosing. */
    @Test
    fun noTwoRowsShareARussianNameWithoutADetail() {
        val twins = TOP_FOODS.filter { it.name.ru.isNotBlank() && it.detail.ru.isBlank() }
            .groupBy { it.name.ru }.filterValues { it.size > 1 }.keys

        assertEquals(emptySet<String>(), twins)
    }

    /** Sugars and fibre are parts of the carbohydrate, and no part is bigger than the whole. */
    @Test
    fun sugarAndFibreNeverExceedCarbs() {
        val contradictions = TOP_FOODS.filter { food ->
            val carbs = food.nutrition.carbsG + 1e-9
            (food.nutrition.sugarG ?: 0.0) > carbs || (food.nutrition.fiberG ?: 0.0) > carbs
        }.map(TopFood::id)

        assertEquals(emptyList<String>(), contradictions)
    }
}

/** The measures counted as non-helpings: oz, cup, tbsp, fl oz, guideline amount, cubic or surface inch. */
private val MEASURE_LABEL = Regex("""\b(oz|cup|tbsp|tablespoon|tsp|teaspoon|fl|guideline|cubic|surface)\b""")
