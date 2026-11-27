package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The other half of the meal path: the catalogue rows a photograph resolves to, the dish that is
 * saved as one line of text and restored from somebody's backup, and the arithmetic that turns a
 * plate into a journal entry.
 *
 * The through-line is that none of this is watched while it runs. A classifier's label reaches the
 * catalogue with nobody to reject a wrong match, a portion button multiplies rows nobody re-reads,
 * and a saved dish comes back months later on another phone. So the tests here are about the
 * boundaries and the round trips rather than about the happy path, which [MealPhotoTest] and
 * [TopFoodCatalogTest] already walk.
 */
class NutritionParsingEdgeCasesExtraTest {
    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private fun portion(grams: Double, label: String) = CatalogPortion(grams, CatalogText(label, label))

    private fun food(vararg portions: CatalogPortion) = TopFood(
        id = "test",
        group = FoodCategory.OTHER,
        name = CatalogText("Еда", "Food"),
        detail = CatalogText("", ""),
        nutrition = CatalogNutrition(kcal = 100.0, proteinG = 1.0, fatG = 1.0, carbsG = 1.0),
        portions = portions.toList(),
        aliases = emptyList(),
    )

    /**
     * A saved dish is a row in the database and a line in every backup: `id=grams`, or `id=grams=fat`
     * where somebody chose a fat content on the chip. A two-field row is a dish with no fat chosen
     * and has to keep meaning just that — and a row naming a food the catalogue has since dropped is
     * skipped rather than taking the whole restore down with it.
     */
    @Test
    fun aSavedDishSurvivesEncodeAndDecode() {
        val dish = listOf(
            DishItem(TOP_FOODS_BY_ID.getValue("egg_boiled"), 50.0),
            DishItem(TOP_FOODS_BY_ID.getValue("pasta_cooked"), 180.0, 5.0),
        )

        val restored = dish.encodeDish().decodeDish()

        assertEquals(dish.map { it.food.id }, restored.map { it.food.id })
        assertEquals(dish.map { it.grams }, restored.map { it.grams })
        assertEquals(dish.map { it.fatPercent }, restored.map { it.fatPercent })

        assertEquals(listOf("egg_boiled"), "nosuchfood=100|egg_boiled=50".decodeDish().map { it.food.id })
        assertEquals(emptyList<DishItem>(), "".decodeDish())
        assertEquals(emptyList<DishItem>(), "egg_boiled".decodeDish())
    }

    /**
     * Both confidence floors are the sheet's whole defence against offering noise as dinner, and
     * both are inclusive: a dish scoring exactly the floor is shown and one a thousandth below it is
     * not. Every label used here resolves to a catalogue row on its own — asserted first — so a
     * label missing from the answer was left out by its score and by nothing else.
     */
    @Test
    fun bothConfidenceGatesAreInclusiveOfTheirOwnFloor() {
        assertNotNull(dishFor("Ramen", 1f))
        assertNotNull(dishFor("Borscht", 1f))
        assertNotNull(productFor("banana", 1f))
        assertNotNull(productFor("cucumber", 1f))

        val dishes = FloatArray(FOOD_LABELS.size)
        dishes[0] = 1f
        dishes[FOOD_LABELS.indexOf("Ramen")] = 0.02f
        dishes[FOOD_LABELS.indexOf("Borscht")] = 0.019f
        val products = FloatArray(IMAGENET_LABELS.size)
        products[IMAGENET_LABELS.indexOf("banana")] = 0.30f
        products[IMAGENET_LABELS.indexOf("cucumber")] = 0.29f

        val guesses = photoGuesses(PhotoScores(dishes, products))

        assertEquals(listOf("Ramen"), guesses.dishes.map { it.label })
        assertEquals(listOf("banana"), guesses.products.map { it.label })
        assertEquals("banana", guesses.chosen?.label)
    }

    /**
     * The model and its label map are two separate assets, and swapping one for a build with a
     * different class count is the realistic way this breaks. Scores past the end of the label map
     * name nothing and must be dropped rather than indexed into; an empty tensor is a photograph
     * nothing was recognised in, and the sheet has to be told so exactly once.
     */
    @Test
    fun aScoreArrayThatOutgrewItsLabelFileOffersOnlyLabelsItHas() {
        val overlong =
            PhotoScores(FloatArray(FOOD_LABELS.size + 500) { 1f }, FloatArray(IMAGENET_LABELS.size + 500) { 1f })

        val guesses = photoGuesses(overlong)

        assertTrue("was ${guesses.dishes.size}", guesses.dishes.size <= 5)
        assertTrue("was ${guesses.products.size}", guesses.products.size <= 5)
        assertTrue(guesses.dishes.all { it.label in FOOD_LABELS })
        assertTrue(guesses.products.all { it.label in IMAGENET_LABELS })

        val steps = mutableListOf<DishStep>()
        val nothing = photoGuesses(PhotoScores(FloatArray(0), FloatArray(0)), onStep = steps::add)

        assertTrue(nothing.isEmpty)
        assertNull(nothing.chosen)
        assertEquals(listOf(DishStep.Empty), steps)
    }

    /**
     * The weight form opens on this portion, and four chained filters decide which it is: at least
     * ten grams to be a helping rather than a crumb, at most half a kilogram to be something served
     * rather than something the shop wraps, a measure only when nothing else is offered, and — once
     * every filter has emptied the list — whatever the row actually carries, rather than an
     * exception thrown on an empty list.
     */
    @Test
    fun theDefaultPortionSitsBetweenItsTwoWeightLimits() {
        assertEquals(480.0, food(portion(9.9, "1 piece"), portion(480.0, "1 bowl")).defaultPortion.grams, 1e-9)
        assertEquals(10.0, food(portion(10.0, "1 piece"), portion(480.0, "1 bowl")).defaultPortion.grams, 1e-9)
        assertEquals(500.0, food(portion(500.0, "1 bowl")).defaultPortion.grams, 1e-9)
        assertEquals(300.0, food(portion(500.1, "1 bowl"), portion(300.0, "1 cup")).defaultPortion.grams, 1e-9)
        assertEquals(453.6, food(portion(453.6, "1 lb")).defaultPortion.grams, 1e-9)
    }

    /**
     * Asking a row for the fat content it already has must hand it back untouched — not only the
     * calories, but the sugar, sodium and cholesterol that ride on the same scale factor and would
     * each drift on their own if a denominator were wrong. At 100% fat nothing else is left to
     * dilute; and a row that is already pure fat has nothing to concentrate at all, so it comes back
     * as it was rather than dividing by the zero that is left of it.
     */
    @Test
    fun recalculatingToTheFatContentARowAlreadyHasChangesNothing() {
        val milk = CatalogNutrition(
            kcal = 61.0,
            proteinG = 3.2,
            fatG = 3.3,
            carbsG = 4.8,
            fiberG = 0.0,
            sugarG = 4.7,
            sodiumMg = 44.0,
            cholesterolMg = 10.0,
        )

        assertEquals(milk, milk.atFatPercent(3.3))

        val pure = milk.atFatPercent(100.0)

        assertEquals(900.0, pure.kcal, 1e-9)
        assertEquals(100.0, pure.fatG, 1e-9)
        assertEquals(0.0, pure.proteinG, 1e-9)
        assertEquals(0.0, pure.carbsG, 1e-9)
        // Cholesterol rides with the fat, so it keeps the proportion to it that was printed.
        assertEquals(milk.cholesterolMg!! / milk.fatG, pure.cholesterolMg!! / pure.fatG, 1e-9)

        val lard = milk.copy(fatG = 100.0)

        assertEquals(lard, lard.atFatPercent(5.0))
    }

    /**
     * Every fat rail in the catalogue is walked in both directions — curd is one row at 0% and at
     * 18% — so the upward half is pinned as well as the downward one. 100 g of the 5% product holds 95 g of
     * what the skimmed one was made of, so the protein falls with the dilution while the energy
     * rises with the fat that displaced it.
     */
    @Test
    fun addingFatToASkimmedRowDilutesEverythingElse() {
        val skimmed = CatalogNutrition(kcal = 33.0, proteinG = 3.4, fatG = 0.0, carbsG = 4.9)

        val fattened = skimmed.atFatPercent(5.0)

        assertEquals(5.0, fattened.fatG, 1e-9)
        assertEquals(3.4 * 0.95, fattened.proteinG, 1e-9)
        assertEquals(4.9 * 0.95, fattened.carbsG, 1e-9)
        assertEquals(33.0 * 0.95 + 9.0 * 5.0, fattened.kcal, 1e-9)
        assertTrue("was ${fattened.proteinG}", fattened.proteinG < skimmed.proteinG)
    }

    /**
     * The saved-dish key is a primary key, so that saving the same dish twice replaces it. A key
     * folded through the phone's own locale would make a Turkish keyboard's "Idli" a second row that
     * never replaces the first — and a backup restored onto such a phone would stack up rather than
     * merge. The category guess folds the same way, and there every English keyword holding an I
     * would stop matching. Both have to fold on the invariant locale whatever the phone is set to.
     */
    @Test
    fun theFoldsThatKeyAndClassifyAFoodIgnoreThePhonesLocale() {
        assertEquals("идли", savedDishKey("  Идли  "))
        assertEquals(savedDishKey("идли"), savedDishKey("  Идли  "))

        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        assertEquals("idli", savedDishKey("Idli"))
        assertEquals(FoodCategory.FAST_FOOD, foodCategory("PIZZA"))
        assertEquals(FoodCategory.FAST_FOOD, foodCategory("Pizza margherita"))
    }

    /**
     * The sweets list carries "мёд" and "мед" side by side because both spellings are printed, and
     * the second is written with a trailing space to keep it out of «медальон» and «медведь». The title
     * is trimmed before the match, so that space can never be there when the word ends the name —
     * which is where the word usually stands. Honey then gets the nothing-in-particular plate.
     */
    @Test
    fun honeyAtTheEndOfANameReachesTheSweets() {
        assertEquals(FoodCategory.SWEET, foodCategory("Липовый мед"))
        assertEquals(FoodCategory.SWEET, foodCategory("Мед"))
        assertEquals(FoodCategory.SWEET, foodCategory("Мёд"))
        // And whatever that trailing space was keeping out stays out.
        assertEquals(FoodCategory.OTHER, foodCategory("Медальон"))
    }

    /**
     * The catalogue is 4 704 hand-edited rows and every column but two fails the build when it is
     * mistyped. A portion field of two parts silently loses its labels to the default "порция"; a
     * fat step that is not a number is silently dropped, leaving a product with a rail that no
     * longer brackets its own row. Both produce a wrong app rather than a failed build.
     */
    @Test
    fun theCataloguesTwoUnguardedColumnsParseAsTheyAreMeantTo() {
        val brokenPortions = mutableListOf<String>()
        val brokenSteps = mutableListOf<String>()

        catalogResource("/top_foods.csv").lineSequence().drop(1).filter(String::isNotBlank).forEach { line ->
            val cell = line.split(';')
            cell[14].split('|').forEach { field ->
                val parts = field.split('=').size
                if (parts != 1 && parts != 3) brokenPortions += "${cell[0]}: $field"
            }
            if (cell[16].isNotBlank() && cell[16].split('|').any { it.toDoubleOrNull() == null }) {
                brokenSteps += "${cell[0]}: ${cell[16]}"
            }
        }

        assertEquals(emptyList<String>(), brokenPortions)
        assertEquals(emptyList<String>(), brokenSteps)
    }

    /**
     * Deleting the last row of the review sheet is one tap away, and everything the sheet sums is
     * then a sum over nothing: the totals divide by a portion weight of zero, and the optional
     * nutrients compare two sizes that are both zero. The form has to open on zeroes rather than on
     * NaN, and the entry has to be worth no calories rather than an unknown number of them.
     */
    @Test
    fun aPlateWithNothingLeftOnItIsZeroRatherThanNaN() {
        val empty = FoodState().withPhotoDish("Ужин", "Оценка по фото", emptyList())
        val typed = listOf(empty.protein, empty.fat, empty.carbs, empty.reportedEnergy)

        assertEquals("0", empty.serving)
        assertEquals(listOf("0", "0", "0", "0"), typed)
        assertEquals(MealBasis.PER_PORTION, empty.basis)
        assertTrue("was ${empty.extra}", empty.extra.isEmpty())
        assertEquals(0.0, empty.portionCalories, 1e-9)
        assertEquals(0.0, emptyList<DishItem>().photoCalories(), 1e-9)
    }

    /**
     * A denormal weight is not something the weight field can hand these functions: every keystroke
     * goes through String.decimalInput(), which keeps digits and a single dot — so "e" and "-" never
     * survive — and caps the fraction at two places. The smallest weight the form can produce is
     * therefore 0.01 g, and the smallest it treats as no weight at all is zero, which both functions
     * answer with zero rather than an infinity.
     */
    @Test
    fun aWeightTooSmallToDivideByStillYieldsAFiniteFigure() {
        assertEquals(1_000_000.0, per100FromPortion(100.0, 0.01), 1e-9)
        assertTrue(per100FromPortion(100.0, 0.01).isFinite())
        // The weight the form already refuses stays refused, in both directions.
        assertEquals(0.0, per100FromPortion(100.0, 0.0), 1e-9)
        assertEquals(0.0, portionFromPer100(100.0, 0.0), 1e-9)
    }

    /**
     * The smallest of the three portion buttons multiplies every row at once, and a factor small
     * enough to round a row away must still leave a row: clearing one is something the person does,
     * never something a portion button does behind them. Grams land on the five-gram step, rounded
     * half up, because "117,3 г" reads as a measurement nobody took.
     */
    @Test
    fun portionScalingNeverErasesARowAndLandsOnTheFiveGramStep() {
        val plate = listOf(
            DishItem(TOP_FOODS_BY_ID.getValue("egg_boiled"), 100.0),
            DishItem(TOP_FOODS_BY_ID.getValue("pasta_cooked"), 7.5),
            DishItem(TOP_FOODS_BY_ID.getValue("onion"), 12.5),
        )

        assertEquals(listOf(5.0, 5.0, 5.0), plate.scaledBy(0.0).map { it.grams })
        assertEquals(listOf(100.0, 10.0, 15.0), plate.scaledBy(1.0).map { it.grams })
    }

    /**
     * The search box is fed by a text field, and a text field holds whatever a keyboard sends.
     * Punctuation normalises away to the empty query, which answers with the popular head of the
     * catalogue rather than with nothing; and a limit of zero or below is a list with no room in it,
     * where take() throws on a negative number instead of returning an empty list.
     */
    @Test
    fun theSearchBoxAnswersAQueryWithNothingSearchableInIt() {
        assertEquals(searchTopFoods("").map { it.id }, searchTopFoods("!!!").map { it.id })
        assertTrue(searchTopFoods("chicken", limit = 0).isEmpty())
        assertTrue(searchTopFoods("chicken", limit = -3).isEmpty())
        assertTrue(searchTopFoods("!!!", limit = -3).isEmpty())
        assertEquals(TOP_FOODS.size, searchTopFoods("", limit = 10_000).size)
    }

    /**
     * These two paths run with nobody watching: a classifier's label arrives and whatever they
     * answer becomes dinner. "Coq au vin" and "Risotto ai funghi" put two-letter prepositions into
     * the catalogue's own names, so a needle that short has to be refused outright — three
     * characters is where a word worth matching starts. The phrase path normalises first, so case,
     * spacing and punctuation cannot keep a label from the row it names.
     */
    @Test
    fun theExactMatchPathsRefuseANeedleTooShortToMeanAnything() {
        assertNull(catalogNameMatch("au"))
        assertNull(catalogNameMatch("ai"))
        assertNull(catalogPhraseMatch("au"))

        val rice = requireNotNull(catalogNameMatch("рис"))

        assertTrue("was ${rice.name.ru}", rice.name.ru.lowercase(Locale.ROOT).split(' ').contains("рис"))
        assertEquals("mashed_potato", requireNotNull(catalogPhraseMatch("  MASHED   POTATO ")).id)
    }

    /**
     * A percentage chosen on a chip has to reach every figure its row contributes, not only the
     * calories: curd at 0% is a different protein figure as much as a different energy one, because
     * what the fat leaves behind concentrates. Two separate summations walk these rows — the sheet's
     * running total and the meal form's — and a percentage that reached one and missed the other
     * would show a plate whose calories and macros describe different food.
     */
    @Test
    fun aChosenFatPercentageReachesTheEnergyAndTheProteinAlike() {
        val curd = TOP_FOODS.first { it.fatSteps.isNotEmpty() && it.nutrition.fatG > 0.0 }
        val asSold = listOf(DishItem(curd, 200.0))
        val skimmed = listOf(DishItem(curd, 200.0, fatPercent = 0.0))
        val energy = skimmed.photoCalories()

        assertTrue("was $energy against ${asSold.photoCalories()}", energy < asSold.photoCalories())

        val soldMeal = FoodState().withPhotoDish("Творог", "Оценка по фото", asSold)
        val skimmedMeal = FoodState().withPhotoDish("Творог", "Оценка по фото", skimmed)
        val protein = skimmedMeal.portionProtein

        assertTrue("was $protein against ${soldMeal.portionProtein}", protein > soldMeal.portionProtein)
        assertEquals(energy, skimmedMeal.portionCalories, 0.1)
    }
}
