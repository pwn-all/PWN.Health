package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allergy answer, and the matcher that reads a food's name for the fourteen.
 *
 * Two kinds of case. The first is the answer itself: "never tested" and "none known" are different
 * things and a column that could not tell them apart would be the whole feature undone. The second
 * is the food matcher, where the cases are the traps — the words that look like an allergen and are
 * not, and the foods that carry one without saying so in their name.
 *
 * Every fixture is synthetic, and the catalogue rows named here are real rows of the shipped file,
 * which is what makes them worth pinning: they are the names that actually exist to be got wrong.
 */
class AllergyTest {
    private fun food(name: String, detail: String = "", aliases: List<String> = emptyList()) = TopFood(
        id = name.lowercase().replace(' ', '-'),
        group = FoodCategory.DISH,
        name = CatalogText(ru = "", en = name),
        detail = CatalogText(ru = "", en = detail),
        nutrition = CatalogNutrition(kcal = 100.0, proteinG = 1.0, fatG = 1.0, carbsG = 1.0),
        portions = emptyList(),
        aliases = aliases,
    )

    // --- the answer ---------------------------------------------------------------------------

    /** The three answers and the absence of one survive a round trip through the column. */
    @Test
    fun everyAnswerSurvivesTheColumn() {
        val answers = listOf(
            AllergyAnswer.Unanswered,
            AllergyAnswer.NotTested,
            AllergyAnswer.NoneKnown,
            AllergyAnswer.Known(setOf(Allergen.MILK)),
            AllergyAnswer.Known(setOf(Allergen.PEANUTS, Allergen.GLUTEN, Allergen.MOLLUSCS)),
        )

        answers.forEach { assertEquals(it, allergyAnswerOf(it.stored())) }
    }

    /**
     * "Never tested" is not "none known", in either direction. This is the assertion the whole
     * question exists for: one of them is a statement about the person and the other is a statement
     * about what nobody has established, and a doctor reads them differently.
     */
    @Test
    fun neverTestedIsNotTheSameAsNoneKnown() {
        assertFalse(AllergyAnswer.NotTested == AllergyAnswer.NoneKnown)
        assertFalse(allergyAnswerOf("NOT_TESTED") == allergyAnswerOf("NONE"))
        assertTrue(allergyAnswerOf("").let { it is AllergyAnswer.Unanswered })
        // And neither of them is a list of allergens.
        assertTrue(AllergyAnswer.NotTested.allergens().isEmpty())
        assertTrue(AllergyAnswer.NoneKnown.allergens().isEmpty())
    }

    /** A column written by a newer version, or edited by hand, cannot invent an allergy. */
    @Test
    fun anUnknownNameIsDroppedRatherThanGuessedAt() {
        assertEquals(AllergyAnswer.Known(setOf(Allergen.MILK)), allergyAnswerOf("MILK,KIWI"))
        assertEquals(AllergyAnswer.Unanswered, allergyAnswerOf("KIWI,LATEX"))
        assertEquals(AllergyAnswer.Unanswered, allergyAnswerOf("   "))
    }

    /** The stored order is the enum's, so the same set never writes two different columns. */
    @Test
    fun theStoredOrderIsStable() {
        val one = AllergyAnswer.Known(setOf(Allergen.MOLLUSCS, Allergen.MILK, Allergen.EGGS))
        val other = AllergyAnswer.Known(setOf(Allergen.EGGS, Allergen.MOLLUSCS, Allergen.MILK))

        assertEquals("EGGS,MILK,MOLLUSCS", one.stored())
        assertEquals(one.stored(), other.stored())
    }

    /** The profile reads its own column through the one parser, and its default is "never asked". */
    @Test
    fun aProfileWithNoAnswerIsUnanswered() {
        val profile = UserProfile(sex = "FEMALE", weightKg = 60.0, heightCm = 165, birthDate = "1990-01-01")

        assertEquals(AllergyAnswer.Unanswered, profile.allergyAnswer())
        assertEquals(AllergyAnswer.NotTested, profile.copy(allergies = "NOT_TESTED").allergyAnswer())
    }

    // --- the foods that are obvious ------------------------------------------------------------

    @Test
    fun theOrdinaryCasesAreRecognised() {
        assertEquals(setOf(Allergen.MILK), FoodAllergens.of(food("Cheddar cheese")))
        assertEquals(setOf(Allergen.PEANUTS), FoodAllergens.of(food("Peanut butter")))
        assertEquals(setOf(Allergen.FISH), FoodAllergens.of(food("Grilled salmon")))
        assertEquals(setOf(Allergen.CRUSTACEANS), FoodAllergens.of(food("Shrimp cocktail")))
        assertEquals(setOf(Allergen.MOLLUSCS), FoodAllergens.of(food("Steamed mussels")))
        assertEquals(setOf(Allergen.TREE_NUTS), FoodAllergens.of(food("Roasted almonds")))
        assertEquals(setOf(Allergen.SESAME), FoodAllergens.of(food("Tahini sauce")))
        assertEquals(setOf(Allergen.CELERY), FoodAllergens.of(food("Celeriac purée")))
    }

    /** A food can carry several, and a matcher that stopped at the first would hide the rest. */
    @Test
    fun aFoodCanCarrySeveral() {
        val found = FoodAllergens.of(food("Cheese crackers with peanut butter"))

        assertTrue(Allergen.MILK in found)
        assertTrue(Allergen.GLUTEN in found)
        assertTrue(Allergen.PEANUTS in found)
    }

    // --- the traps ------------------------------------------------------------------------------

    /**
     * The word "nut" is not an allergen, and four real rows of the catalogue prove it: a doughnut is
     * a gluten food, a butternut squash is a vegetable, and a coconut and a chestnut are neither of
     * them on the regulation's list of eight tree nuts.
     *
     * A matcher that flagged all four would be wrong three times, and a person who saw it be wrong
     * three times would stop reading the line — which is the real damage.
     */
    @Test
    fun aWordEndingInNutIsNotATreeNut() {
        assertFalse(Allergen.TREE_NUTS in FoodAllergens.of(food("Chocolate doughnut")))
        assertFalse(Allergen.TREE_NUTS in FoodAllergens.of(food("Butternut squash")))
        assertFalse(Allergen.TREE_NUTS in FoodAllergens.of(food("Coconut milk")))
        assertFalse(Allergen.TREE_NUTS in FoodAllergens.of(food("Chestnuts")))
        assertFalse(Allergen.TREE_NUTS in FoodAllergens.of(food("Nutmeg")))
        // The doughnut is still a gluten food, and the coconut milk is not a milk one.
        assertTrue(Allergen.GLUTEN in FoodAllergens.of(food("Chocolate doughnut")))
    }

    /**
     * The words that contain an allergen's word and are not it. Read as substrings, each of these
     * would be a false positive: a goat cheese a gluten food, a kale salad a beer, a piece of anything
     * a pie, a casserole a sole and a beef carpaccio a carp.
     */
    @Test
    fun aWordThatMerelyContainsAnAllergenIsNotOne() {
        assertFalse(Allergen.GLUTEN in FoodAllergens.of(food("Goat cheese")))
        assertFalse(Allergen.GLUTEN in FoodAllergens.of(food("Kale salad")))
        assertFalse(Allergen.GLUTEN in FoodAllergens.of(food("One piece of chicken")))
        assertFalse(Allergen.FISH in FoodAllergens.of(food("Vegetable casserole")))
        assertFalse(Allergen.FISH in FoodAllergens.of(food("Beef carpaccio")))
        assertFalse(Allergen.FISH in FoodAllergens.of(food("Orange peel")))
        assertFalse(Allergen.EGGS in FoodAllergens.of(food("Eggplant parmigiana")))
        assertFalse(Allergen.MILK in FoodAllergens.of(food("Peanut butter")))
        assertFalse(Allergen.MILK in FoodAllergens.of(food("Coconut milk")))
        // The goat cheese is still a milk food and the aubergine dish is still a milk one.
        assertTrue(Allergen.MILK in FoodAllergens.of(food("Goat cheese")))
        assertTrue(Allergen.MILK in FoodAllergens.of(food("Eggplant parmigiana")))
    }

    /**
     * A decoy misleads one allergen and no other. An almond milk is not a milk and is very much an
     * almond: were the phrase a decoy for the nuts as well, the catalogue's five almond milks and
     * three almond butters would name no nut to the person who had reported reacting to them.
     */
    @Test
    fun aNutMilkIsNotAMilkAndIsStillTheNut() {
        assertEquals(setOf(Allergen.TREE_NUTS), FoodAllergens.of(food("Almond milk")))
        assertEquals(setOf(Allergen.TREE_NUTS), FoodAllergens.of(food("Almond butter")))
        assertEquals(setOf(Allergen.GLUTEN, Allergen.TREE_NUTS), FoodAllergens.of(food("Almond butter sandwich")))
    }

    /** A plural is the same word. A stem that is not a plural is not. */
    @Test
    fun onlyThePluralOfAKeywordCounts() {
        assertTrue(Allergen.MOLLUSCS in FoodAllergens.of(food("Steamed mussels")))
        assertTrue(Allergen.TREE_NUTS in FoodAllergens.of(food("Walnuts")))
        assertTrue(Allergen.GLUTEN in FoodAllergens.of(food("Crackers")))
        assertTrue(Allergen.FISH in FoodAllergens.of(food("Anchovies")))
        // A phrase has a plural too, and these three rows are written in it.
        assertTrue(Allergen.TREE_NUTS in FoodAllergens.of(food("Brazil nuts")))
        assertTrue(Allergen.SULPHITES in FoodAllergens.of(food("Dried apricots")))
        assertTrue(Allergen.GLUTEN in FoodAllergens.of(food("Frankfurters or hot dogs and sauerkraut")))
    }

    /**
     * A compound is a word of its own, and the table has to name it. Reading whole words is what
     * keeps the goat out of the oats, and it is also what would hide the cheese in a cheeseburger
     * and the fish in a swordfish. The second half is why the compounds are listed one by one and
     * not matched by the word inside them: those are real rows too.
     */
    @Test
    fun aCompoundIsReadAndItsLookalikeIsNot() {
        assertEquals(setOf(Allergen.GLUTEN, Allergen.MILK), FoodAllergens.of(food("Double cheeseburger")))
        assertEquals(setOf(Allergen.GLUTEN, Allergen.MILK), FoodAllergens.of(food("Fruit cheesecake")))
        assertEquals(setOf(Allergen.MILK), FoodAllergens.of(food("Vanilla milkshake")))
        assertEquals(setOf(Allergen.MILK), FoodAllergens.of(food("Buttermilk")))
        assertEquals(setOf(Allergen.SOY), FoodAllergens.of(food("Roasted soybeans")))
        assertEquals(setOf(Allergen.FISH), FoodAllergens.of(food("Swordfish")))
        assertEquals(setOf(Allergen.FISH), FoodAllergens.of(food("Butterfish")))
        assertEquals(setOf(Allergen.GLUTEN), FoodAllergens.of(food("Beef ramen")))
        assertEquals(setOf(Allergen.LUPIN), FoodAllergens.of(food("Lupini beans")))

        assertTrue(FoodAllergens.of(food("Sweetbreads")).isEmpty())
        assertTrue(FoodAllergens.of(food("Breadfruit")).isEmpty())
        assertTrue(FoodAllergens.of(food("Headcheese")).isEmpty())
        assertTrue(FoodAllergens.of(food("Dried jellyfish")).isEmpty())
        assertTrue(FoodAllergens.of(food("Buckwheat, dry")).isEmpty())
    }

    /**
     * The catalogue's own groups are not allergen groups. Its NUTS group holds lentils, chickpeas
     * and beans, and its FISH group holds shrimp and octopus — which is exactly why the matcher
     * reads names and not groups.
     */
    @Test
    fun theCatalogueGroupsAreNotAllergenGroups() {
        assertTrue(FoodAllergens.of(food("Lentils")).isEmpty())
        assertTrue(FoodAllergens.of(food("Chickpeas")).isEmpty())
        assertEquals(setOf(Allergen.CRUSTACEANS), FoodAllergens.of(food("Gambas al ajillo")))
        assertEquals(setOf(Allergen.MOLLUSCS), FoodAllergens.of(food("Pulpo a la gallega")))
    }

    /** Where a name hides an allergen, the matcher takes the reading that flags. */
    @Test
    fun aHiddenAllergenIsFlagged() {
        val soySauce = FoodAllergens.of(food("Soy sauce"))
        assertTrue(Allergen.SOY in soySauce)
        assertTrue("wheat in soy sauce is the classic hidden gluten", Allergen.GLUTEN in soySauce)

        assertTrue(Allergen.SESAME in FoodAllergens.of(food("Hummus")))
        // An apostrophe is not a letter: the matcher reads "za atar", and the table has to say that.
        assertTrue(Allergen.SESAME in FoodAllergens.of(food("Manakish with za'atar")))
        assertTrue(Allergen.EGGS in FoodAllergens.of(food("Mayonnaise")))
        assertTrue(Allergen.FISH in FoodAllergens.of(food("Worcestershire sauce")))
        assertTrue(Allergen.SULPHITES in FoodAllergens.of(food("Red wine")))
    }

    /** A food nobody recognised says nothing, and saying nothing is never a clearance. */
    @Test
    fun anUnrecognisedFoodClaimsNothing() {
        assertTrue(FoodAllergens.of(food("Boiled potato")).isEmpty())
        assertTrue(FoodAllergens.of(food("Apple")).isEmpty())
    }

    /**
     * The warning only ever names what this person reported, out of what the food carries: the food's
     * line intersects the two, and an answer that is not a list has nothing to intersect.
     */
    @Test
    fun onlyTheAllergensThisPersonReportedAreMatchedAgainst() {
        val cake = FoodAllergens.of(food("Carrot cake with walnuts"))
        fun reacted(answer: AllergyAnswer) = cake.intersect(answer.allergens())

        assertEquals(setOf(Allergen.TREE_NUTS), reacted(AllergyAnswer.Known(setOf(Allergen.TREE_NUTS))))
        assertTrue(reacted(AllergyAnswer.NoneKnown).isEmpty())
        assertTrue(reacted(AllergyAnswer.NotTested).isEmpty())
        assertTrue(reacted(AllergyAnswer.Unanswered).isEmpty())
        // And the food still says what it carries, whoever is looking at it.
        assertTrue(Allergen.GLUTEN in cake)
    }

    /**
     * Each tick in the panel is worked out from the ticks before it, not from the answer the profile
     * last handed back: a second tick landing before the database had returned the first one would
     * otherwise write the first one away. Unticking the last one puts the question back to unanswered.
     */
    @Test
    fun ticksInARowKeepEachOtherAndTheLastUntickLeavesTheQuestionOpen() {
        val both = AllergyAnswer.Unanswered.toggled(Allergen.MILK).toggled(Allergen.EGGS)

        assertEquals(AllergyAnswer.Known(setOf(Allergen.MILK, Allergen.EGGS)), both)
        assertEquals(AllergyAnswer.Known(setOf(Allergen.EGGS)), both.toggled(Allergen.MILK))
        assertEquals(AllergyAnswer.Unanswered, both.toggled(Allergen.MILK).toggled(Allergen.EGGS))
        // A tick over "none that I know of" starts the list rather than adding to nothing.
        assertEquals(AllergyAnswer.Known(setOf(Allergen.SESAME)), AllergyAnswer.NoneKnown.toggled(Allergen.SESAME))
    }

    /**
     * The matcher run over the whole shipped catalogue, as a shape rather than as a list.
     *
     * A keyword table is easy to break in a way no single example catches: one careless word and a
     * quarter of the catalogue is suddenly a fish. The bounds here are loose on purpose — they are
     * not a claim about how many foods contain milk, they are a tripwire for a keyword that has
     * started matching everything, and for one that has stopped matching anything.
     */
    @Test
    fun theMatcherKeepsItsShapeOverTheWholeCatalogue() {
        val catalogue = java.io.File("src/main/resources/top_foods.csv").readLines().drop(1)
        val found = catalogue.mapNotNull { line ->
            val parts = line.split(';')
            if (parts.size < 16) return@mapNotNull null
            FoodAllergens.of(food(parts[3], parts[5], parts[15].split(',').filter(String::isNotBlank)))
        }

        assertTrue("the catalogue did not parse", found.size > 4_000)
        Allergen.entries.forEach { allergen ->
            val share = found.count { allergen in it }.toDouble() / found.size
            assertTrue("$allergen matched $share of the catalogue, which is too much", share < 0.45)
        }
        // The four that must certainly be there, in a catalogue holding 236 dairy and 239 fish rows.
        listOf(Allergen.MILK, Allergen.GLUTEN, Allergen.FISH, Allergen.EGGS).forEach { allergen ->
            assertTrue("$allergen matched nothing at all", found.any { allergen in it })
        }
        // Most foods are not an allergen food, and a matcher that thought otherwise would be noise.
        assertTrue(found.count { it.isEmpty() } > found.size / 3)
        // Measured on the shipped catalogue: gluten 1296, milk 630, fish 202,
        // eggs 183, crustaceans 80, peanuts 59, soy 57, tree nuts 52, molluscs 43, sulphites 39,
        // sesame 29, mustard 14, celery 5, lupin 0 — and 2 468 of 4 704 rows flagged nothing.
    }

    /**
     * No compound of an allergen's word goes unread in the shipped catalogue.
     *
     * The matcher reads whole words, so a "milkshake" is found only because the table lists it, and
     * a catalogue regenerated with a new compound would lose it silently — to the person who
     * reported that allergen, as a line that is simply not there. Every word of a row's English
     * name and detail built on one of these stems has to be flagged for its allergen, or be one of
     * the words below that somebody has read and found to be something else.
     */
    @Test
    fun noCompoundInTheCatalogueGoesUnread() {
        val stems = mapOf(
            Allergen.MILK to listOf("milk", "cheese", "butter", "cream"),
            Allergen.FISH to listOf("fish"),
            Allergen.SOY to listOf("soy"),
            Allergen.GLUTEN to listOf("bread", "cake", "burger"),
            // The tree nuts by the names Annex II gives them: an "almondine" or a "walnutbread" the
            // next catalogue brings would otherwise lose its nut as quietly as a cheeseburger its milk.
            Allergen.TREE_NUTS to listOf("almond", "hazelnut", "walnut", "cashew", "pecan", "pistachio", "macadamia"),
            Allergen.PEANUTS to listOf("peanut"),
            Allergen.EGGS to listOf("egg"),
            Allergen.CRUSTACEANS to listOf("shrimp", "prawn", "crab", "lobster"),
            Allergen.MOLLUSCS to listOf("mussel", "oyster", "squid", "scallop", "clam"),
            Allergen.SESAME to listOf("sesame"),
            Allergen.MUSTARD to listOf("mustard"),
            Allergen.CELERY to listOf("celery"),
        )
        // A fruit, an offal, a cornmeal pudding and a cheese. A crustacean, a mollusc, a cracker and
        // a jellyfish. A plant, a squash, a brawn, two fish, a soy drink, and a brand name that says
        // nothing about butter. A vegetable, a bakery chain, a cheese and a veggie burger; a fruit;
        // potatoes cut in scallops and a veal escalope.
        val somethingElse = mapOf(
            Allergen.GLUTEN to setOf("breadfruit", "sweetbreads", "spoonbread", "limburger"),
            Allergen.FISH to setOf("crayfish", "cuttlefish", "goldfish", "jellyfish"),
            Allergen.EGGS to setOf("eggplant", "greggs", "taleggio", "veggie"),
            Allergen.CRUSTACEANS to setOf("crabapples"),
            Allergen.MOLLUSCS to setOf("scalloped", "scallopini"),
            Allergen.MILK to setOf(
                "butterbur",
                "butternut",
                "headcheese",
                "butterfish",
                "milkfish",
                "soymilk",
                "butterfinger",
            ),
        )
        val notALetter = Regex("[^\\p{L}]+")

        val unread = mutableListOf<String>()
        TOP_FOODS.forEach { row ->
            val found = FoodAllergens.of(row)
            val words = "${row.name.en} ${row.detail.en}".lowercase().split(notALetter)
            (stems - found).forEach { (allergen, itsStems) ->
                // The stem standing alone is the matcher's own word: unflagged, a decoy phrase took it.
                val compounds = words.filter { word -> itsStems.any { it in word && word != it && word != "${it}s" } }
                unread += (compounds - somethingElse[allergen].orEmpty()).map { "$allergen in ${row.id}: $it" }
            }
        }

        assertEquals(emptyList<String>(), unread)
    }

    // --- what the doctor\'s sheet says -----------------------------------------------------------

    /** The three answers reach the report as three different section states. */
    @Test
    fun theSheetPrintsTheThreeAnswersDifferently() {
        fun section(stored: String): ReportSection {
            val profile = UserProfile(
                sex = "FEMALE",
                weightKg = 60.0,
                heightCm = 165,
                birthDate = "1990-01-01",
                allergies = stored,
            )
            val today = java.time.LocalDate.of(2026, 9, 19)
            return doctorReport(
                sources = ReportSources(profile, emptyList(), emptyList()),
                request = ReportRequest(from = today.minusMonths(3), to = today),
                today = today,
                zone = java.time.ZoneOffset.UTC,
            ).sections.first { it.part == ReportPart.ALLERGIES }
        }

        assertEquals(SectionPresence.NO_KNOWN, section("NONE").presence)
        assertEquals(SectionPresence.NO_DATA, section("NOT_TESTED").presence)
        assertEquals(SectionPresence.NO_DATA, section("").presence)
        assertEquals(SectionPresence.PRESENT, section("MILK,EGGS").presence)
        assertEquals(2, section("MILK,EGGS").rows.size)
        // "Never tested" says why it is empty; "never asked" has nothing to say.
        assertEquals(R.string.report_caveat_allergies_untested, section("NOT_TESTED").caveatRes)
        assertEquals(null, section("").caveatRes)
    }
}
