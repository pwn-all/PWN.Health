package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tables between the classifiers and the editor: which products a label names, how many grams
 * of them, and what that adds up to on the meal form.
 *
 * The recognition end — what the models answer and how the cascade ranks it — is
 * [FoodRecognitionTest]. These are the hand-written CSVs, which is where a typo silently becomes a
 * wrong dinner.
 */
class MealPhotoTest {
    private fun idsFor(label: String) = dishFor(label, 1f)?.items?.map { it.food.id }

    /** Templates are hand-edited: a typo in a catalogue id has to fail here, not on a plate. */
    @Test
    fun everyTemplateResolvesAndIsReachable() {
        val labels = FOOD_LABELS.map { it.lowercase() }
        val templated = FOOD_LABELS.mapNotNull { dishFor(it, 1f) }

        assertTrue(templated.isNotEmpty())
        assertTrue(templated.all { guess -> guess.items.all { it.grams > 0.0 } })
        // Every keyword row must be one the model can actually trigger.
        listOf(
            "ramen", "fried rice", "carbonara", "poke", "syrniki", "shashlik", "risotto", "sushi",
            "steak", "taco", "chips", "burrito", "yakitori", "scrambled", "fried chicken",
        ).forEach { keyword -> assertTrue(keyword, labels.any { it.contains(keyword) }) }
    }

    /** Every product-model row must name a class it can emit and a food the catalogue stocks. */
    @Test
    fun everyProductLabelIsRealOnBothSides() {
        val mapped = IMAGENET_LABELS.mapNotNull { productFor(it, 1f) }

        // 224 of the product model's 459 foods open a catalogue product; the rest are learned, never shown.
        assertTrue(mapped.size >= 200)
        assertEquals("banana", productFor("banana", 1f)?.items?.single()?.food?.id)
        assertEquals("apple", productFor("apple", 1f)?.items?.single()?.food?.id)
        assertEquals("egg_boiled", productFor("egg", 1f)?.items?.single()?.food?.id)
        assertEquals("white_rice", productFor("rice", 1f)?.items?.single()?.food?.id)
        assertEquals("potato_boiled", productFor("potato", 1f)?.items?.single()?.food?.id)
        // The dish model names pizzas by city style, never by topping; the product model's plain
        // "pizza" opens the plain one, not the fast-food chain's row a name search would find.
        assertEquals("pizza_cheese", productFor("pizza", 1f)?.items?.single()?.food?.id)
        // A food the catalogue has no product for, and the one Nutrition5k row that named nothing.
        listOf("pork_n_s", "unnamed_nutrition5k_ingredient", "Granny Smith")
            .forEach { assertNull(it, productFor(it, 1f)) }
    }

    @Test
    fun aTemplatedDishBreaksIntoItsProducts() {
        val ramen = requireNotNull(dishFor("Ramen", 0.9f))

        assertEquals("Рамен", ramen.name.ru)
        assertEquals(
            listOf("pasta_cooked", "chicken_soup", "pork_tenderloin", "egg_boiled", "onion"),
            ramen.items.map { it.food.id },
        )
        assertEquals(545.0, ramen.items.sumOf { it.grams }, 0.01)
    }

    /**
     * The sheet crosses a rebuilt activity as labels, scores and catalogue ids, and comes back as it
     * stood: the same guesses in the same two rows, and the rows as corrected — a weight half typed
     * and a fat content chosen among them. It does not come back recognising the photograph again.
     */
    @Test
    fun theSheetComesBackFromASavedStateAsItStood() {
        val guesses = PhotoGuesses(
            dishes = listOfNotNull(dishFor("Ramen", 0.9f), dishFor("Borscht", 0.12f)),
            products = listOfNotNull(productFor("banana", 0.31f)),
        )
        assertEquals(3, guesses.all.size)
        assertEquals(guesses, restoredGuesses(guesses.saved()))
        assertTrue(restoredGuesses(emptyList()).isEmpty)
        // A label the tables do not resolve goes; the guess beside it stays.
        assertEquals(
            PhotoGuesses(emptyList(), listOfNotNull(productFor("apple", 0.2f))),
            restoredGuesses(listOf("PRODUCT", "Granny Smith", "0.5", "PRODUCT", "apple", "0.2")),
        )

        val rows = listOf(
            PhotoRow(TOP_FOODS_BY_ID.getValue("banana"), "12"),
            PhotoRow(TOP_FOODS_BY_ID.getValue("kefir"), "", fatPercent = 2.5),
        )
        assertEquals(rows, restoredRows(rows.savedRows()))
        assertEquals(rows.take(1), restoredRows(rows.take(1).savedRows() + listOf("no_such_food", "40", "")))
    }

    /**
     * A saved dish comes back from the database and from restored backups, where toDouble reads
     * "NaN" and "Infinity" as readily as a weight: a part that weighs no amount of anything is no
     * part, and a plate missing one is not a plate its micronutrients can be summed from.
     */
    @Test
    fun aSavedDishPartWithoutARealWeightIsDropped() {
        assertEquals(
            listOf("orange"),
            "apple=NaN|orange=100.0|apple=Infinity|apple=-5.0".decodeDish().map { it.food.id },
        )
        assertEquals(0.0, "apple=0.0".decodeDish().single().grams, 0.0)
        assertNull("orange=100.0=NaN".decodeDish().single().fatPercent)
        assertNull(foodSourceItems("apple=NaN|orange=100.0"))
    }

    /**
     * The labels are far more specific than the catalogue, so the fallback carries most dishes:
     * the whole phrase first, then single words from the head noun back.
     */
    @Test
    fun anUntemplatedDishFallsBackToTheCatalogue() {
        assertEquals(listOf("cheeseburger"), idsFor("Cheeseburger"))
        // What the model actually answers for a photograph of a pepperoni pizza.
        assertEquals(listOf("pizza_pepperoni"), idsFor("Pepperoni"))
        assertEquals(listOf("pizza_cheese"), idsFor("Neapolitan pizza"))
        assertEquals(listOf("mashed_potato"), idsFor("Mashed potato"))
        assertEquals(listOf("borscht"), idsFor("Borscht"))
        // Coconut rice has a row of its own rather than falling back to plain rice.
        assertEquals(listOf("rice_cooked_with_coconut_milk"), idsFor("Coconut rice"))
    }

    /**
     * A forgiving search would let 205 of the 2 023 labels land on Big Mac, because
     * "McDonald's" normalises to "mcdonald s" and that "s" prefixes every second dish.
     * Silence is the correct answer for a dish this catalogue does not stock.
     */
    @Test
    fun anUnstockedDishAnswersNothingRatherThanSomethingWrong() {
        listOf("Suimono", "Sciusceddu", "Sartù", "Stracciatella", "Kondowole", "Chaudin")
            .forEach { assertNull(it, dishFor(it, 1f)) }
        // 22 labels are bare Knowledge Graph ids with no name a person could read.
        assertNull(dishFor("/g/11b8_rxx4d", 1f))
        val bigMacs = FOOD_LABELS.count { dishFor(it, 1f)?.items?.singleOrNull()?.food?.id == "big_mac" }
        assertTrue("$bigMacs labels claim to be a Big Mac", bigMacs <= 2)
    }

    /**
     * Keywords match whole words, because substrings are a minefield: "steamed" and "steak" both
     * contain "tea", "eggplant" contains "egg", "pierogi" contains "pie".
     */
    @Test
    fun keywordsRespectWordBoundaries() {
        assertEquals(listOf("shrimp"), idsFor("Steamed clams"))
        assertEquals(listOf("eggplant"), idsFor("Eggplant"))
        assertEquals(listOf("egg_boiled"), idsFor("Tea egg"))
        assertEquals(listOf("croissant"), idsFor("Cinnamon roll"))
        assertEquals(listOf("pelmeni", "sour_cream"), idsFor("Pierogi"))
    }

    /** The generic rows are the coverage: 2 023 dish classes against 156 products in stock. */
    @Test
    fun genericTemplatesCarryTheLongTail() {
        assertEquals(listOf("chicken_breast_grilled"), idsFor("Orange chicken"))
        assertEquals(listOf("chicken_soup"), idsFor("Miso soup"))
        assertEquals(listOf("omelet"), idsFor("Omelette"))
        assertEquals(listOf("donut"), idsFor("Apple pie"))
        assertEquals(listOf("california_roll"), idsFor("Sushi"))

        val covered = FOOD_LABELS.drop(1).count { dishFor(it, 1f) != null }
        assertTrue("only $covered labels reach a product", covered >= 500)
    }

    /**
     * The dishes people actually photograph — a steak, tacos, chips, fried eggs — have no plain
     * class in the dish model, only compounds: "Salisbury steak", "Chips and dip", "Loco moco".
     * These rows catch the compounds and still keep the preparation the label names, but they carry
     * it as oil rather than as a second chicken breast: one product row per food, and the frying fat
     * spelled out in grams the user can see and correct. Guessing the oil is honest; hiding a guess
     * about somebody else's frying pan inside a product's calorie figure is not.
     */
    @Test
    fun everydayDishesKeepTheirPreparation() {
        assertEquals(listOf("beef_steak"), idsFor("Salisbury steak"))
        assertEquals(listOf("beef_steak"), idsFor("Chicken fried steak"))
        assertEquals(listOf("beef_steak"), idsFor("Churrasco"))
        assertEquals(listOf("chicken_breast_grilled", "sunflower_oil"), idsFor("Fried chicken"))
        assertEquals(listOf("chicken_breast_grilled", "sunflower_oil"), idsFor("Crispy fried chicken"))
        // Boiled is the one preparation that adds nothing: no oil row, and the same product.
        assertEquals(listOf("chicken_breast_grilled"), idsFor("White cut chicken"))
        assertEquals(listOf("chicken_breast_grilled"), idsFor("Yakitori"))
        assertEquals(listOf("egg_scrambled"), idsFor("Stir-fried tomato and scrambled eggs"))
        assertEquals(listOf("egg_boiled", "sunflower_oil"), idsFor("Huevos rancheros"))
        assertEquals(listOf("egg_boiled", "sunflower_oil"), idsFor("Loco moco"))
        assertEquals(listOf("beef_taco"), idsFor("Taco"))
        assertEquals(listOf("potato_chips"), idsFor("Chips and dip"))
        assertEquals(listOf("beef_burrito"), idsFor("Burrito"))
        // The rows that must not be shadowed by the keywords for compounds.
        assertEquals(listOf("white_bread", "ham", "cheddar", "tomato"), idsFor("Steak sandwich"))
        assertEquals(listOf("fish_nfs", "fast_food_fries"), idsFor("Fish and chips"))
        assertEquals(listOf("fast_food_fries"), idsFor("Carne asada fries"))
        assertEquals(listOf("chicken_breast_grilled"), idsFor("Hainanese curry rice").orEmpty().take(1))
    }

    @Test
    fun portionScalingStaysOnRoundNumbers() {
        val large = requireNotNull(dishFor("Ramen", 1f)).items.scaledBy(1.4)

        assertEquals(listOf(250.0, 350.0, 110.0, 35.0, 15.0), large.map { it.grams })
        // Down and back up again must not walk a row to zero.
        assertTrue(large.scaledBy(0.5).scaledBy(0.5).all { it.grams >= 5.0 })
        // But a row the person cleared stays cleared: a change of portion must not resurrect it.
        val cleared = large.map { it.copy(grams = 0.0) }.scaledBy(1.4)
        assertTrue(cleared.all { it.grams == 0.0 })
    }

    /** The plate becomes one entry, and its figures are the sum of the rows the person approved. */
    @Test
    fun theReviewedPlateBecomesOneEditableMeal() {
        val items = requireNotNull(dishFor("Ramen", 1f)).items

        val meal = FoodState().withPhotoDish("Рамен", "Оценка по фото", items)

        assertEquals("545", meal.serving)
        assertEquals(MealBasis.PER_PORTION, meal.basis)
        // A tenth, not a hundredth: the form carries typed strings, and one decimal is what it types.
        assertEquals(items.photoCalories(), meal.portionCalories, 0.1)
        assertEquals(items.sumOf { it.food.nutrition.proteinG * it.grams / 100.0 }, meal.portionProtein, 0.1)
        // Noodles and onion carry a fibre figure, pork and egg do not, so the meal claims none.
        assertTrue(meal.extra.isEmpty())
    }

    @Test
    fun anOptionalNutrientIsOnlySummedWhenEveryProductReportsIt() {
        fun product(id: String) = TOP_FOODS.first { it.id == id }
        val complete = listOf(DishItem(product("pasta_cooked"), 100.0), DishItem(product("onion"), 100.0))
        val partial = complete + DishItem(product("pork_tenderloin"), 100.0)

        assertEquals("3.5", FoodState().withPhotoDish("x", "y", complete).extra[ExtraNutrient.FIBER])
        assertNull(FoodState().withPhotoDish("x", "y", partial).extra[ExtraNutrient.FIBER])
    }
}
