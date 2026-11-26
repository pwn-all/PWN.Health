package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A packet read once becomes a product of one's own: what the favourite row holds when the label
 * printed its figures per 100 g, per serving, or without an energy line at all.
 */
class ProductLabelTest {
    private val now = 1_800_000_000_000L

    @Test
    fun aPerHundredGramLabelBecomesAHundredGramPortion() {
        val label = NutritionLabel(kcal = 363.0, proteinG = 12.0, fatG = 1.5, carbsG = 71.0, sugarG = 2.0)

        val kept = label.toFavorite("  Oat flakes ", "From the package label", now)

        assertEquals("name:oat flakes", kept.key)
        assertEquals("Oat flakes", kept.title)
        assertEquals("From the package label", kept.subtitle)
        assertEquals(100.0, kept.servingG, 0.0)
        assertEquals(363.0, kept.calories, 0.0)
        assertEquals(12.0, kept.proteinG, 0.0)
        assertEquals(1.5, kept.fatG, 0.0)
        assertEquals(71.0, kept.carbsG, 0.0)
        assertEquals(2.0, kept.sugarG!!, 0.0)
        assertNull(kept.fiberG)
        assertNull(kept.sodiumMg)
        assertNull(kept.saturatedFatG)
        assertEquals(now, kept.createdAt)
    }

    /** A Japanese carton prints its figures for the pack; the pack's grams are the portion then. */
    @Test
    fun aPerServingLabelKeepsTheServingItPrinted() {
        val label = NutritionLabel(kcal = 47.0, proteinG = 0.9, fatG = 0.0, carbsG = 11.0, perServingG = 65.0)

        assertEquals(65.0, label.toFavorite("Yakult", "", now).servingG, 0.0)
    }

    /** No energy line: the figure is what the macros add up to, the same 4/9/4 the form falls back on. */
    @Test
    fun aLabelWithoutCaloriesIsWorthItsMacros() {
        val label = NutritionLabel(proteinG = 10.0, fatG = 5.0, carbsG = 20.0)

        assertEquals(macroCalories(10.0, 5.0, 20.0), label.toFavorite("Curd", "", now).calories, 0.0)
    }

    /** A misread figure is typed over on the label page, and the typed one is what is kept. */
    @Test
    fun typedFiguresReplaceTheReadersOwn() {
        val read = NutritionLabel(kcal = 950.0, proteinG = 12.0, fatG = 1.5, carbsG = 71.0, approximate = true)
        val typed = mapOf(
            LabelFigure.ENERGY to "363",
            LabelFigure.PROTEIN to "12",
            LabelFigure.FAT to "",
            LabelFigure.CARBS to "71.5",
        )

        val corrected = read.typedOver(typed, energyTyped = true)

        assertEquals(363.0, corrected.kcal!!, 0.0)
        assertEquals(12.0, corrected.proteinG!!, 0.0)
        assertNull(corrected.fatG)
        assertEquals(71.5, corrected.carbsG!!, 0.0)
        assertFalse(corrected.approximate)
        assertTrue(read.typedOver(typed, energyTyped = false).approximate)
    }

    @Test
    fun emptiedFieldsLeaveNothingToGoOnWith() {
        val read = NutritionLabel(kcal = 120.0, proteinG = 3.0, fatG = 1.0)

        assertFalse(read.typedOver(LabelFigure.entries.associateWith { "" }, energyTyped = false).found)
    }

    @Test
    fun missingMacrosAreZeroNotInvented() {
        val kept = NutritionLabel(kcal = 4.0).toFavorite("Tea", "", now)

        assertEquals(0.0, kept.proteinG, 0.0)
        assertEquals(0.0, kept.fatG, 0.0)
        assertEquals(0.0, kept.carbsG, 0.0)
        assertEquals(4.0, kept.calories, 0.0)
    }

    /** The name the person typed wins over the packet, which never knew it. */
    @Test
    fun theFormTakesTheTypedNameOverTheLabelsFigures() {
        val label = NutritionLabel(kcal = 100.0, proteinG = 1.0, fatG = 2.0, carbsG = 3.0)

        val state = FoodState().withLabel("From the package label", label).copy(title = "Crackers")

        assertEquals("Crackers", state.title)
        assertEquals("From the package label", state.subtitle)
        assertEquals(MealBasis.PER_100G, state.basis)
        assertEquals("100.0", state.reportedEnergy)
    }

    /**
     * What is kept is what the page showed: sugar, fibre and sodium each have a field to see and
     * correct them in where the packet printed them, rather than going beside the favourites as read.
     */
    @Test
    fun everyFigureTheReaderFoundHasAField() {
        val macros = listOf(LabelFigure.ENERGY, LabelFigure.PROTEIN, LabelFigure.FAT, LabelFigure.CARBS)
        val bare = NutritionLabel(kcal = 363.0, proteinG = 12.0)
        val full = bare.copy(sugarG = 2.0, sodiumMg = 8000.0)

        assertEquals("a macro the reader missed is typed in", macros, bare.figuresShown())
        assertEquals(macros + listOf(LabelFigure.SUGAR, LabelFigure.SODIUM), full.figuresShown())
        assertEquals(setOf(LabelFigure.ENERGY, LabelFigure.PROTEIN), bare.figuresRead().keys)
        assertEquals("8000", full.figuresRead()[LabelFigure.SODIUM])
        assertTrue(full.figuresShown().containsAll(full.figuresRead().keys))
    }

    @Test
    fun aMisreadSodiumIsTypedOverLikeAnyOtherFigure() {
        val read = NutritionLabel(kcal = 363.0, proteinG = 12.0, sugarG = 2.0, sodiumMg = 8000.0)
        val typed = read.figuresRead() + mapOf(LabelFigure.SODIUM to "8", LabelFigure.SUGAR to "")

        val kept = read.typedOver(typed, energyTyped = false).toFavorite("Oat flakes", "", now)

        assertEquals(8.0, kept.sodiumMg!!, 0.0)
        assertNull("an emptied field is a figure the packet does not state", kept.sugarG)
        assertEquals(12.0, kept.proteinG, 0.0)
        assertEquals(363.0, kept.calories, 0.0)
    }

    /** The fields cross a rebuilt activity as names and texts, a half-typed figure among them. */
    @Test
    fun theFieldsComeBackAsTheyWereSaved() {
        val typed = mapOf(LabelFigure.ENERGY to "363", LabelFigure.FAT to "", LabelFigure.SODIUM to "12.")

        assertEquals(typed, restoredFields(typed.savedFields()))
        assertEquals(mapOf(LabelFigure.ENERGY to "363"), restoredFields(listOf("ENERGY", "363", "POTASSIUM", "5")))
        assertTrue(restoredFields(emptyList()).isEmpty())
    }

    /**
     * And the reading crosses with them, so the packet is not read a second time behind the fields:
     * every figure it printed, the ones it did not print still unsaid, and its two flags. A table
     * that would not read crosses as nothing — which is still a reader that has finished.
     */
    @Test
    fun theReadingComesBackAsItWasRead() {
        val read = NutritionLabel(kcal = 363.0, proteinG = 12.0, sodiumMg = 8.5, perServingG = 190.0)
            .copy(approximate = true)
        // A printed nought is a figure, and stays one.
        val fatFree = NutritionLabel(kcal = 30.0, fatG = 0.0, perHundred = true)

        assertEquals(read, restoredReading(read.savedReading()))
        assertEquals(fatFree, restoredReading(fatFree.savedReading()))
        assertTrue(null.savedReading().isEmpty())
        assertNull(restoredReading(null.savedReading()))
    }

    /**
     * A name is filed where no catalogue id can stand. Hundreds of ids are one lowercase word, and
     * a packet named "Kefir" filed under the catalogue row's own key would replace the kept row or be
     * replaced by it, and open in the form as the catalogue's kefir.
     */
    @Test
    fun aPacketNamedLikeACatalogueRowIsNotThatRow() {
        val row = TOP_FOODS_BY_ID.getValue("kefir")
        val packet = NutritionLabel(kcal = 50.0, proteinG = 2.9, fatG = 2.5, carbsG = 4.0).toFavorite("Kefir", "", now)

        assertEquals("the catalogue's own favourite keeps its id", row.id, favoriteKey(row.id, "Kefir"))
        assertNotEquals(row.id, packet.key)
        assertEquals("the same packet kept twice replaces itself", favoriteKey(null, " kefir "), packet.key)
        assertNull(packet.foodSource())
        assertTrue("no id may ever open like a name's key", TOP_FOODS.none { it.id.startsWith(FAVORITE_NAME_PREFIX) })
    }
}
