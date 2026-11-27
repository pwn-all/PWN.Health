package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three rules that read a panel by where its ink is rather than by what order the boxes came
 * back in: which column the figures belong to, which figure is the headline, and where the panel
 * is on the photograph when the whole frame turned out to be unreadable.
 */
class LabelPanelTest {
    @Test
    fun theColumnTheHeaderNamesIsTheOneKept() {
        val page = listOf(
            box(300, 0, 520, 20, "Avg quantity per serving"),
            box(560, 0, 780, 20, "Avg quantity per 100 g"),
            box(0, 40, 120, 60, "Energy"),
            box(320, 40, 400, 60, "593"),
            box(600, 40, 690, 60, "1190"),
            box(0, 80, 120, 100, "Protein"),
            box(320, 80, 380, 100, "3.4"),
            box(600, 80, 660, 100, "6.8"),
        )

        val kept = requireNotNull(page.asPerHundredColumn())

        assertTrue(kept, kept.contains("1190") && !kept.contains("593"))
        assertTrue(kept, kept.contains("6.8") && !kept.contains("3.4"))
        // The header is the parser's evidence for the basis it just chose.
        assertTrue(nutritionLabel(kept).perHundred)
    }

    /** One column of figures has nothing to disambiguate, and the rule stands aside. */
    @Test
    fun oneColumnIsLeftAlone() {
        val page = listOf(
            box(0, 0, 200, 20, "Nutrition information per 100 g"),
            box(0, 40, 120, 60, "Protein"),
            box(300, 40, 360, 60, "6.8"),
        )

        assertNull(page.asPerHundredColumn())
    }

    /**
     * A tuna tin photographed at arm's length: "110" survives at twice the height of everything
     * else and the word beside it does not. The largest type on a nutrition panel is always the
     * calories, which is how a person reads one across a shop aisle.
     */
    @Test
    fun theLoudestFigureOnAPanelIsItsCalories() {
        val panel = listOf(
            box(0, 0, 180, 30, "Nutrition Facts"),
            box(0, 40, 100, 115, "110"),
            box(120, 50, 200, 80, "Total Fat"),
            box(220, 50, 260, 80, "5.0g"),
            box(120, 90, 200, 120, "Sodium"),
            box(220, 90, 280, 120, "860mg"),
            box(120, 130, 200, 160, "Protein"),
            box(220, 130, 260, 160, "18g"),
        )

        assertEquals(110.0, requireNotNull(panel.headlineFigure()), 0.01)
    }

    /** Nothing about a page of ingredients says its biggest number is a calorie count. */
    @Test
    fun aPageThatIsNotAPanelHasNoHeadline() {
        val page = (0..9).map { box(0, it * 40, 300, it * 40 + 30, "Ingredients: water, sugar $it") } +
            box(0, 400, 200, 500, "250")

        assertNull(page.headlineFigure())
    }

    /** The panel is the crowd of words and figures; a frame that is all panel is not worth cutting. */
    @Test
    fun thePanelIsCutOutOnlyWhenItIsPartOfTheFrame() {
        val corner = (0..9).map { box(700 + it, 700 + it * 20, 900, 720 + it * 20, "Protein 7g") }
        assertNotNull(corner.panelBounds(2000, 2000))

        val everywhere = (0..9).map { box(0, it * 200, 2000, it * 200 + 150, "Calories 250") }
        assertNull(everywhere.panelBounds(2000, 2000))
    }

    /** A crop is the same pixels, and a stretch keeps the size while pulling the ends apart. */
    @Test
    fun theRasterCropsAndStretches() {
        val flat = Raster(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() })
        val cut = requireNotNull(flat.cropped(Box(0, 0, 200, 200)))
        assertEquals(64, cut.width)
        // Below a crop worth reading, nothing is returned rather than a strip of pixels.
        assertNull(flat.cropped(Box(0, 0, 8, 8)))

        val dim = Raster(8, 8, IntArray(64) { index -> if (index < 32) 0xFF404040.toInt() else 0xFF808080.toInt() })
        val stretched = dim.stretched()
        assertEquals(dim.width, stretched.width)
        assertTrue(stretched.argb.max() >= dim.argb.max())
    }

    private fun box(x0: Int, y0: Int, x1: Int, y1: Int, text: String) = TextBox(Box(x0, y0, x1, y1), text)

    /**
     * The ladder keeps the first rich parse it meets, and the box-by-box assembly of a two-column
     * panel is rich too — with the serving column's figures, which stand nearest each nutrient's
     * name. The column the header names has to be offered first, or an Australian tin reads its
     * "per serving" numbers and files them as per 100 g.
     */
    @Test
    fun theNamedColumnIsOfferedBeforeTheBoxes() {
        val page = listOf(
            box(300, 0, 520, 20, "Avg quantity per serving"),
            box(560, 0, 780, 20, "Avg quantity per 100 g"),
            box(0, 40, 120, 60, "Energy"),
            box(320, 40, 400, 60, "593 kJ"),
            box(600, 40, 690, 60, "1190 kJ"),
            box(0, 80, 120, 100, "Protein"),
            box(320, 80, 380, 100, "3.4 g"),
            box(600, 80, 660, 100, "6.8 g"),
            box(0, 120, 120, 140, "Fat"),
            box(320, 120, 380, 140, "0.5 g"),
            box(600, 120, 660, 140, "1.0 g"),
            box(0, 160, 160, 180, "Carbohydrate"),
            box(320, 160, 380, 180, "15 g"),
            box(600, 160, 660, 180, "30 g"),
        )

        val first = LabelReader.readingsOf(page).first()

        assertEquals(6.8, first.proteinG!!, 0.01)
        assertEquals(30.0, first.carbsG!!, 0.01)
        assertEquals(1_190 / 4.184, first.kcal!!, 1.0)
        assertTrue(first.perHundred)
    }
}
