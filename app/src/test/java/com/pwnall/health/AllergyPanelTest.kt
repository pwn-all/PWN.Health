package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Synthetic panels laid out like a Polycheck sheet: code and name, class, IgE in kU/L. */
class AllergyPanelTest {
    @Test
    fun readsCodeNameClassAndFigure() {
        val rows = readAllergyPanel(
            boxes(
                row("Алергени", "Клас", "IgE [kU/l]"),
                row("f02 молоко", "0", "<0 .15"),
                row("f13 apaxic", "1", "0 .36"),
                row("е02/е05 епітелій собаки", "0", "<0.15"),
                row("d02 kлiщ D. farinae", "2", "0,74"),
                row("fx10 f04 / f05 / f06 / f07 - Пшениця, жито, ячмінь"),
            ),
        )
        assertEquals(listOf("f2", "f13", "e2/e5", "d2"), rows.map { it.code })
        val milk = rows[0]
        assertEquals("молоко", milk.name)
        assertTrue(milk.below)
        assertEquals(0.15, milk.kuL, 1e-9)
        assertFalse(milk.positive)
        val peanut = rows[1]
        // Spelt in the letters both alphabets share, on a panel printed in Cyrillic: Cyrillic.
        assertEquals("арахіс", peanut.name)
        assertEquals("кліщ D. farinae", rows[3].name)
        assertEquals(0.36, peanut.kuL, 1e-9)
        assertEquals(1, peanut.igeClass)
        assertTrue(peanut.positive)
        assertEquals(Allergen.PEANUTS, peanut.foodAllergen())
        assertEquals(2, rows[3].igeClass)
        assertNull(rows[3].foodAllergen()) // A mite is not one of the fourteen foods.
    }

    @Test
    fun aMissingClassComesFromTheStandardScale() {
        assertEquals(0, igeClassOf(0.34, below = false))
        assertEquals(1, igeClassOf(0.35, below = false))
        assertEquals(3, igeClassOf(3.5, below = false))
        assertEquals(6, igeClassOf(120.0, below = false))
        assertEquals(0, igeClassOf(0.5, below = true))
        val row = readAllergyPanel(boxes(row("f14 soy", "4.2"))).single()
        assertEquals(3, row.igeClass)
    }

    @Test
    fun onlyAllergenCodesOpenARow() {
        assertNull(allergenCode("B12"))
        assertNull(allergenCode("0"))
        assertNull(allergenCode("Клас"))
        assertEquals("f2", allergenCode("f02"))
        assertEquals("fx10", allergenCode("fx10"))
        assertEquals("e2/e5", allergenCode("е02/е05"))
        // A whole number the class could be is not the figure.
        assertTrue(readAllergyPanel(boxes(row("f13 peanut", "2"))).isEmpty())
    }

    /**
     * A panel's file can carry the other tests of the same visit, and "T4" or "С3" reads as an allergen
     * code. Their reference ranges run well past the 0.35 an allergen's stops at, and that turns them
     * away; the allergen beside them, with its own range, stays.
     */
    @Test
    fun anOrdinaryTestOnTheSameSheetIsNotReadAsAnAllergen() {
        val rows = readAllergyPanel(
            boxes(
                row("Т4 свободный", "15.2", "пмоль/л", "9.0 - 19.0"),
                row("С3 компонент комплемента", "1,2", "г/л", "0,9-1,8"),
                row("f13 арахис", "0.36", "кЕдА/л", "0 - 0.35"),
            ),
        )
        assertEquals(listOf("f13"), rows.map { it.code })
        assertEquals(0.36, rows.single().kuL, 1e-9)
    }

    @Test
    fun mixesAreNotOfferedAsOneFood() {
        val mix = AllergenResult("fx10", "wheat, rye, barley, oats", 2.0, below = false, igeClass = 2)
        assertNull(mix.foodAllergen())
    }

    @Test
    fun thePanelTravelsInTheLabRowsPayload() {
        val rows = listOf(
            AllergenResult("f13", "арахіс", 0.36, below = false, igeClass = 1),
            AllergenResult("f2", "молоко", 0.15, below = true, igeClass = 0),
        )
        val entry = allergyPanelEntry("Allergy panel", "content://doc/1", LocalDate.of(2026, 10, 1), "note", rows)
        assertEquals(KIND_LABS, entry.kind)
        assertEquals(rows, entry.allergyPanel())
        assertEquals(1, medicalAttachments(entry.labAttachments()).size)
        val document = labDocumentEntry(AttachmentKind.OTHER, "x", "f", LocalDate.of(2026, 10, 1), "")
        assertTrue(document.allergyPanel().isEmpty())
    }

    @Test
    fun theDoctorsSheetPrintsThePanelBesideWhatThePersonSaid() {
        val today = LocalDate.of(2026, 10, 17)
        val panel = allergyPanelEntry(
            "Allergy panel",
            "content://doc/1",
            LocalDate.of(2026, 10, 1),
            "",
            listOf(
                AllergenResult("f13", "арахіс", 0.36, below = false, igeClass = 1),
                AllergenResult("d2", "кліщ D. farinae", 0.74, below = false, igeClass = 2),
                AllergenResult("f2", "молоко", 0.15, below = true, igeClass = 0),
            ),
        )
        val profile =
            UserProfile(sex = "FEMALE", weightKg = 60.0, heightCm = 165, birthDate = "1990-01-01", allergies = "NONE")
        val section = doctorReport(
            sources = ReportSources(profile, listOf(panel), emptyList()),
            request = ReportRequest(from = today.minusMonths(3), to = today),
            today = today,
            zone = java.time.ZoneOffset.UTC,
        ).sections.first { it.part == ReportPart.ALLERGIES }
        assertEquals(SectionPresence.PRESENT, section.presence)
        assertEquals(R.string.report_caveat_allergy_panel, section.caveatRes)
        // "None that I know of", the panel's count, and its two positives — the negative stays out.
        assertEquals(4, section.rows.size)
        assertEquals(ReportText.Res(R.string.allergy_none_known), section.rows[0].label)
        assertEquals(ReportText.Plain("f13 арахіс"), section.rows[2].label)
        assertEquals(ReportText.Plain("d2 кліщ D. farinae"), section.rows[3].label)
    }

    @Test
    fun theRicherPassWins() {
        val latin = AllergyPanelScan(listOf(AllergenResult("f2", "- :", 0.15, below = true, igeClass = 0)))
        val cyrillic = AllergyPanelScan(listOf(AllergenResult("f2", "молоко", 0.15, below = true, igeClass = 0)))
        assertEquals(cyrillic, bestAllergyPanel(listOf(latin, cyrillic)))
    }

    /**
     * On a screen the figure takes the decimal mark of the language it is read in, as every other
     * figure there does; the doctor's sheet asks with no locale and keeps the sheet's point.
     */
    @Test
    fun theFigureTakesTheDecimalMarkOfTheLanguageOnScreen() {
        val mite = AllergenResult("d2", "mite", 0.74, below = false, igeClass = 2)
        val milk = AllergenResult("f2", "milk", 0.15, below = true, igeClass = 0)
        val hundred = AllergenResult("f13", "peanut", 100.0, below = false, igeClass = 6)

        assertEquals("0.74", mite.figureText())
        assertEquals("0,74", mite.figureText(java.util.Locale.forLanguageTag("ru")))
        assertEquals("<0,15", milk.figureText(java.util.Locale.GERMAN))
        assertEquals("100", hundred.figureText(java.util.Locale.GERMAN))
    }

    private fun row(vararg cells: String): List<String> = cells.toList()

    private fun boxes(vararg rows: List<String>): List<TextBox> = rows.flatMapIndexed { y, cells ->
        cells.mapIndexed { x, text ->
            val start = if (x == 0) 0 else 400 + (x - 1) * 120
            TextBox(Box(start, y * 40, start + if (x == 0) 360 else 90, y * 40 + 18), text)
        }
    }
}
