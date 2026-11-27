package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recognition end of the photo feature: what the photo model's two answers actually are, and what
 * the sheet makes of them.
 *
 * The photo model's own file is exercised in [PhotoModelTest]. Here it was run once on real photographs,
 * and its output is checked in as `recognition_samples.csv`: twelve plates, and the top classes each of
 * its two answers emitted with the probabilities it gave them. Everything downstream of the tensors is
 * then exactly what runs on the phone.
 *
 * That makes these tests a record of measured behaviour rather than of hopes. Where the sheet is wrong —
 * a plate of boiled potatoes opening as a salad at 0.90 — the test says so, because that is what the
 * sheet will show and the number the person will see next to it.
 *
 * The photographs are Wikimedia Commons files (the `photo` column is the file's title), decoded the
 * way FoodRecognizer does; the fixture is measured again whenever a model file changes.
 */
class FoodRecognitionTest {
    private fun guesses(sample: String): PhotoGuesses =
        photoGuesses(requireNotNull(RECOGNITION_SAMPLES[sample]) { "no sample `$sample`" })

    /** The guess the sheet opens on. */
    private fun chosen(
        sample: String,
    ): DishGuess = requireNotNull(guesses(sample).chosen) { "`$sample` guessed nothing" }

    /** The catalogue rows the sheet opens with. */
    private fun ids(sample: String): List<String> = chosen(sample).items.map { it.food.id }

    /** The label maps have to line up with the output tensors, or every guess is a wrong one. */
    @Test
    fun theLabelMapsMatchTheModels() {
        assertEquals(2_024, FOOD_LABELS.size)
        assertEquals("__background__", FOOD_LABELS.first())
        assertEquals(459, IMAGENET_LABELS.size)
        assertTrue(FOOD_LABELS.none(String::isBlank) && IMAGENET_LABELS.none(String::isBlank))
    }

    /** A fixture naming a class neither model can emit is a fixture that has drifted. */
    @Test
    fun everySampleUsesClassesTheModelsHave() {
        assertEquals(12, RECOGNITION_SAMPLES.size)
        assertTrue(RECOGNITION_SAMPLES.values.all { it.dishes.size == FOOD_LABELS.size })
        assertTrue(RECOGNITION_SAMPLES.values.all { it.products.size == IMAGENET_LABELS.size })
        assertTrue(RECOGNITION_SAMPLES.values.all { it.dishes.any { score -> score > 0f } })
    }

    /** Named dishes are what the dish answer is for, and it is confident when it is right. */
    @Test
    fun namedDishesAreRecognisedAndBrokenIntoProducts() {
        assertEquals("Борщ", chosen("borscht").name.ru)
        assertEquals(0.994f, chosen("borscht").confidence, 0.001f)
        assertEquals(listOf("borscht"), ids("borscht"))
        // Not one plain food cleared its floor: the bread beside the bowl scored 0.22.
        assertTrue(guesses("borscht").products.isEmpty())

        assertEquals(listOf("pizza_cheese"), ids("pizza"))

        // Both answers know ramen; the dish answer is surer (0.716 against 0.652), so the sheet opens on its
        // template, the bowl's components, with the catalogue's ramen bowl one row down.
        assertEquals("Ramen", chosen("ramen").label)
        assertEquals(listOf("pasta_cooked", "chicken_soup", "pork_tenderloin", "egg_boiled", "onion"), ids("ramen"))
        assertEquals(listOf("ramen_bowl_nfs"), guesses("ramen").products.single().items.map { it.food.id })
    }

    /**
     * Plain products are the dish answer's blind spot — it has no class for an apple or a boiled egg —
     * and the product answer's reason to exist.
     */
    @Test
    fun plainProductsComeFromTheProductRow() {
        // The dish answer reaches for apple dishes (apple cake 0.38); the product answer says apple at 0.98.
        assertEquals("apple", chosen("apple").label)
        assertEquals(listOf("apple"), ids("apple"))
        assertEquals(0.979f, chosen("apple").confidence, 0.001f)
        assertEquals("Apple cake", guesses("apple").dishes.first().label)
        // A boiled egg: the product answer's egg (0.71) over the dish answer's deviled egg (0.58).
        assertEquals("egg", chosen("boiled_egg").label)
        assertEquals(listOf("egg_boiled"), ids("boiled_egg"))
        // A banana opens on the dish answer's "Frozen banana" (0.75), which is the catalogue's banana: the
        // product answer's banana (0.48) names the same row.
        assertEquals("Frozen banana", chosen("banana").label)
        assertEquals(listOf("banana"), ids("banana"))
        assertEquals(listOf("banana"), guesses("banana").products.map { it.label })
    }

    /** Two rows, one choice: the sheet opens on whichever row's best answer is the surer. */
    @Test
    fun theSurerRowOpensTheSheet() {
        // White rice 0.95 from the dish answer beats the product answer's rice 0.74; both name the same food.
        assertEquals("White rice", chosen("rice").label)
        assertEquals(listOf("white_rice"), ids("rice"))
        assertEquals(0.953f, chosen("rice").confidence, 0.001f)
        assertEquals(listOf("white_rice"), guesses("rice").products.single().items.map { it.food.id })

        RECOGNITION_SAMPLES.keys.forEach { sample ->
            val found = guesses(sample)
            val best = listOfNotNull(found.dishes.firstOrNull(), found.products.firstOrNull())
            assertEquals(sample, best.maxOf { it.confidence }, chosen(sample).confidence, 0f)
            assertTrue(sample, chosen(sample) in best)
        }
    }

    /**
     * A soup is still a soup when the network is surer of the tomato in it: the tomato opens the sheet,
     * and the soup sits in its own row, one tap away, rather than missing from a list the tomato leads.
     */
    @Test
    fun aDishIsNeverHiddenBehindAnIngredient() {
        val dishes = FloatArray(FOOD_LABELS.size)
        dishes[FOOD_LABELS.indexOf("Borscht")] = 0.25f
        val products = FloatArray(IMAGENET_LABELS.size)
        products[IMAGENET_LABELS.indexOf("tomato")] = 0.60f
        products[IMAGENET_LABELS.indexOf("cucumber")] = 0.50f

        val found = photoGuesses(PhotoScores(dishes, products))

        assertEquals("tomato", found.chosen?.label)
        assertEquals(listOf("Borscht"), found.dishes.map { it.label })
        assertEquals(listOf("tomato", "cucumber"), found.products.map { it.label })
    }

    /** A tie goes to the dish: its template carries the whole plate, a product only one row of it. */
    @Test
    fun aTieOpensTheDish() {
        val dishes = FloatArray(FOOD_LABELS.size)
        dishes[FOOD_LABELS.indexOf("Borscht")] = 0.5f
        val products = FloatArray(IMAGENET_LABELS.size)
        products[IMAGENET_LABELS.indexOf("tomato")] = 0.5f

        assertEquals("Borscht", photoGuesses(PhotoScores(dishes, products)).chosen?.label)
    }

    /**
     * A food the catalogue cannot open never reaches the sheet: the grilled chicken breast scored
     * "pork_n_s" 0.40 and "salad_leaf_salad_green" 0.35, the pizza "pizza_margherita_baked" 0.45, and no
     * catalogue row is linked to any of them.
     */
    @Test
    fun unlinkedClassesNeverReachTheSheet() {
        val everything = RECOGNITION_SAMPLES.keys.flatMap { guesses(it).dishes + guesses(it).products }.map { it.label }

        listOf("pork_n_s", "salad_leaf_salad_green", "pizza_margherita_baked", "unnamed_nutrition5k_ingredient")
            .forEach { assertTrue(it, it !in everything) }
        assertTrue(guesses("chicken_breast").products.isEmpty())
    }

    /**
     * The weak spots that remain, measured rather than assumed. The boiled potatoes open as the dish
     * answer's potato salad at 0.90, which the dish table reads as a salad, and the cucumbers as a pickled
     * cucumber at 0.88, which the catalogue answers with its dill pickle rather than a cucumber roll;
     * in both the plain food is the products row's first chip. The fried egg in its basket of chips
     * opens as Eggs Sardou at 0.19, with fried eggs second in the same row. The number beside the name
     * is the only defence a list can offer, which is why the person edits rows.
     */
    @Test
    fun aConfidentlyWrongGuessStillCarriesItsNumber() {
        assertEquals("Potato salad", chosen("boiled_potatoes").label)
        assertEquals(0.904f, chosen("boiled_potatoes").confidence, 0.001f)
        assertEquals(listOf("potato_boiled"), guesses("boiled_potatoes").products.first().items.map { it.food.id })
        assertTrue(guesses("boiled_potatoes").dishes.any { it.label == "Mashed potato" })

        assertEquals("Pickled cucumber", chosen("cucumber").label)
        assertEquals(listOf("pickles_dill"), ids("cucumber"))
        assertEquals(listOf("cucumber", "tomato"), guesses("cucumber").products.map { it.label })

        assertEquals("Eggs Sardou", chosen("fried_egg").label)
        assertEquals(0.187f, chosen("fried_egg").confidence, 0.001f)
        assertTrue(chosen("fried_egg").confidence > LOW_CONFIDENCE)
        assertEquals("Fried eggs", guesses("fried_egg").dishes[1].name.en)
        // The egg scored 0.26, under the products row's floor.
        assertTrue(guesses("fried_egg").products.isEmpty())
    }

    /** The grilled chicken breast opens as fried chicken, whose template is a grilled breast and its oil. */
    @Test
    fun aDishTemplateCanCarryThePlainFood() {
        assertEquals("Fried chicken", chosen("chicken_breast").label)
        assertEquals(0.466f, chosen("chicken_breast").confidence, 0.001f)
        assertEquals(listOf("chicken_breast_grilled", "sunflower_oil"), ids("chicken_breast"))
    }

    /** Five of each is what the sheet has room for, and every sample stays within it. */
    @Test
    fun everyPhotographYieldsTwoShortRows() {
        RECOGNITION_SAMPLES.keys.forEach { sample ->
            val found = guesses(sample)
            listOf(found.dishes, found.products).forEach { row ->
                assertTrue(sample, row.size <= 5)
                assertTrue(sample, row.map { it.name.en }.toSet().size == row.size)
                assertTrue(sample, row.all { guess -> guess.items.isNotEmpty() })
                assertEquals(sample, row.sortedByDescending { it.confidence }, row)
            }
        }
    }

    /** The dish answer's background class outranks real dishes on ordinary photographs. */
    @Test
    fun theBackgroundClassIsNeverADish() {
        val dishes = FloatArray(FOOD_LABELS.size)
        dishes[0] = 0.9f
        dishes[FOOD_LABELS.indexOf("Ramen")] = 0.05f

        val found = photoGuesses(PhotoScores(dishes, FloatArray(IMAGENET_LABELS.size)))

        assertEquals(listOf("Ramen"), found.dishes.map { it.label })
        assertEquals("Ramen", found.chosen?.label)
    }

    /** Below both floors is noise, and a dialog full of noise is worse than one that says nothing. */
    @Test
    fun anUnrecognisablePhotographGuessesNothing() {
        val noise = PhotoScores(FloatArray(FOOD_LABELS.size) { 0.001f }, FloatArray(IMAGENET_LABELS.size) { 0.2f })

        val found = photoGuesses(noise)

        assertTrue(found.isEmpty)
        assertNull(found.chosen)
    }
}

/**
 * Recorded model output, one row per photograph: `name;photo;dish_scores;product_scores`, where each score
 * list is `Label=probability|…` for the classes that cleared 0.02.
 */
private val RECOGNITION_SAMPLES: Map<String, PhotoScores> by lazy {
    val csv = checkNotNull(FoodRecognitionTest::class.java.getResourceAsStream("/recognition_samples.csv"))
        .bufferedReader().use { it.readText() }
    csv.lineSequence().drop(1).filter(String::isNotBlank).associate { line ->
        val cell = line.split(';')
        cell[0] to PhotoScores(scoresOf(cell[2], FOOD_LABELS), scoresOf(cell[3], IMAGENET_LABELS))
    }
}

private fun scoresOf(field: String, labels: List<String>): FloatArray {
    val scores = FloatArray(labels.size)
    field.split('|').filter(String::isNotBlank).forEach { entry ->
        val label = entry.substringBeforeLast('=')
        val index = labels.indexOf(label)
        require(index >= 0) { "`$label` is not a class either model can emit" }
        scores[index] = entry.substringAfterLast('=').toFloat()
    }
    return scores
}
