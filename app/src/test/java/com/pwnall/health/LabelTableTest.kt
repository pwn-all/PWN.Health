package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assembly between the detector's boxes and the parser. A US panel prints the calories as the
 * loudest thing on the packet — the figure on one line, the word on the next — and no rule that
 * reads a line at a time can pair them.
 */
class LabelTableTest {
    @Test
    fun theWordAdoptsTheFigurePrintedAboveIt() {
        val page = listOf(
            row(0, 0, 200, 20, "Amount per serving"),
            row(210, 0, 260, 22, "250"),
            row(0, 30, 90, 50, "Calories"),
            row(0, 60, 200, 80, "Total Fat 4 g"),
        )

        val table = page.asTableText()

        assertTrue(table, table.lines().any { it.contains("Calories") && it.contains("250") })
        assertEquals(250.0, requireNotNull(nutritionLabel(table).kcal), 0.01)
    }

    /** A row that already prints its own figure keeps it, and lends nothing to its neighbour. */
    @Test
    fun aRowThatCarriesItsFigureIsLeftAlone() {
        val page = listOf(
            row(0, 0, 200, 20, "Total Fat 12 g"),
            row(0, 30, 200, 50, "Protein 7 g"),
        )

        val label = nutritionLabel(page.asTableText())

        assertEquals(12.0, requireNotNull(label.fatG), 0.01)
        assertEquals(7.0, requireNotNull(label.proteinG), 0.01)
    }

    /** Only a row with nothing of its own to say may lend: a nutrient never gives its figure away. */
    @Test
    fun aNutrientNeverLendsItsOwnFigure() {
        val page = listOf(
            row(0, 0, 200, 20, "Total Fat 12 g"),
            row(0, 30, 90, 50, "Calories"),
        )

        assertEquals(12.0, requireNotNull(nutritionLabel(page.asTableText()).fatG), 0.01)
    }

    private fun row(x0: Int, y0: Int, x1: Int, y1: Int, text: String) = TextBox(Box(x0, y0, x1, y1), text)
}
