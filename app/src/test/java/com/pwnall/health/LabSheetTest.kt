package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Synthetic documents: no patient names or personal reports are checked into the test suite. */
class LabSheetTest {
    private val today = LocalDate.of(2026, 9, 25)

    @Test
    fun convertsResultAndReferenceRangeTogether() {
        val value = scan(row("Glucose", "90", "mg/dL", "70 - 100")).readings.single()
        assertEquals(LabMarker.GLUCOSE, value.marker)
        assertEquals(90 / 18.016, value.value, 1e-6)
        assertEquals(70 / 18.016, value.range.low!!, 1e-6)
        assertEquals(100 / 18.016, value.range.high!!, 1e-6)
        assertEquals("mg/dL", value.printedUnit) // The form shows it on the chip the sheet printed.
    }

    @Test
    fun readsCyrillicNamesAndDecimalCommas() {
        val result = scan(row("Глюкоза", "5,2", "ммоль/л", "3,9 - 6,1"))
        assertEquals(5.2, result.readings.single().value, 1e-6)
    }

    @Test
    fun readsAccentsAndJapaneseNames() {
        assertEquals(LabMarker.HEMOGLOBIN, scan(row("Hämoglobin", "13,9", "g/dL", "12 - 16")).readings.single().marker)
        assertEquals(LabMarker.HEMOGLOBIN, scan(row("ヘモグロビン", "13.9", "g/dL", "12 - 16")).readings.single().marker)
    }

    @Test
    fun convertsPlateletPowersAndThousandsSeparators() {
        val a = scan(row("Platelets", "250", "10^3/uL", "150 - 400")).readings.single()
        val b = scan(row("Platelets", "250,000", "/uL", "150,000 - 400,000")).readings.single()
        assertEquals(250.0, a.value, 1e-6)
        assertEquals(a.value, b.value, 1e-6)
    }

    @Test
    fun convertsAffineHba1cAndItsRange() {
        val reading = scan(row("HbA1c", "42", "mmol/mol", "20 - 42")).readings.single()
        assertEquals(42 * 0.09148 + 2.152, reading.value, 1e-6)
        assertEquals(20 * 0.09148 + 2.152, reading.range.low!!, 1e-6)
        // And it fills the field's mmol/mol chip with the sheet's own 42, not 5.99416%.
        val chip = reading.marker.fieldUnit(reading.printedUnit, imperialUnits = false)
        assertEquals("mmol/mol", reading.printedUnit)
        assertEquals("42", labImportNumber(reading.value, chip))
        assertEquals("20", labImportNumber(reading.range.low, chip))
    }

    @Test
    fun refusesMissingOrIncompatibleUnits() {
        for (unit in listOf("", "bananas", "mg/L")) {
            val result = scan(row("Glucose", "90", unit))
            assertTrue("unit=$unit", result.readings.isEmpty())
            assertEquals(1, result.unread)
        }
    }

    @Test
    fun infersUnitOnlyWhenRangeDistinguishesIt() {
        val reading = scan(row("Glucose", "90", "", "70 - 100")).readings.single()
        assertEquals(90 / 18.016, reading.value, 1e-6)
        assertTrue(scan(row("Hemoglobin", "7.2", "", "7.3 - 9.5")).readings.isEmpty())
    }

    @Test
    fun rejectsOutOfBoundsAndNonNumericValues() {
        for (value in listOf("999999", "-5", "NaN", "Infinity", "positive", "<5")) {
            assertTrue(value, scan(row("Glucose", value, "mmol/L")).readings.isEmpty())
        }
    }

    @Test
    fun neverTreatsReferenceRangeAsResult() {
        assertTrue(scan(row("Glucose", "", "mmol/L", "3.9 - 6.1")).readings.isEmpty())
    }

    @Test
    fun conflictingDuplicateResultsAreOmitted() {
        val result = scan(row("Glucose", "5.2", "mmol/L"), row("Glucose", "6.2", "mmol/L"))
        assertTrue(result.readings.isEmpty())
        assertEquals(1, result.unread)
    }

    @Test
    fun agreeingDuplicatesCollapseToOne() {
        assertEquals(1, scan(row("Glucose", "5.2", "mmol/L"), row("Glucose", "5.2", "mmol/L")).readings.size)
    }

    @Test
    fun ratioAndUnsupportedNeighboursCannotBecomeResults() {
        assertTrue(scan(row("Cholesterol/HDL Ratio", "3.2", "mmol/L")).readings.isEmpty())
        val result = scan(row("Hemoglobin"), row("MCH", "29", "pg"))
        assertTrue(result.readings.isEmpty())
    }

    @Test
    fun urineSectionDoesNotBecomeBloodGlucose() {
        val result = scan(row("Urinalysis"), row("Glucose", "5", "mmol/L"), row("pH", "6.0"))
        assertFalse(result.readings.any { it.marker == LabMarker.GLUCOSE })
        assertEquals(6.0, result.readings.single { it.marker == LabMarker.URINE_PH }.value, 1e-6)
    }

    @Test
    fun aGaugeBesideTheFigureIsNotASecondFigure() {
        // Synlab's rating column, "|  |*|  |", as PP-OCRv6 reads it on a phone: its bars come back as ones.
        val result = scan(
            row("Hemoglobin [HGB]", "163", "*1", "135 - 175", "g/l"),
            row("Moč chemicky"),
            row("Specifická hustota", "1023", "1|1", "1015 - 1025", "kg/m^3"),
            row("pH", "5,5", "*|1", "4,5 - 6,0"),
        )
        assertEquals(163.0, result.readings.single { it.marker == LabMarker.HEMOGLOBIN }.value, 1e-6)
        val gravity = result.readings.single { it.marker == LabMarker.URINE_SG }
        assertEquals(1.023, gravity.value, 1e-9)
        assertEquals(1.015, gravity.range.low!!, 1e-9)
        assertEquals(5.5, result.readings.single { it.marker == LabMarker.URINE_PH }.value, 1e-6)
        assertEquals(0, result.unread)
    }

    @Test
    fun aUkrainianSheetFilesNamesUnderGroupsAndPrintsARangeForEachAge() {
        // Synevo: each urine row under its group, then a blood count with a range for every age.
        val result = scan(
            row("Аналіз сечі загальний"),
            row("Фізико-хімічні властивості: Відносна щільність", "1.027", "г/мл", "1.002 - 1.030"),
            row("Фізико-хімічні властивості: рН", "5", "", "5.0 - 7.0"),
            row("Фізико-хімічні властивості: Гемоглобін", "0.05", "мг/дл", "<0.03"),
            row("Аналіз крові розгорнутий"),
            row("Гемоглобін (HGB)", "141", "г/л", "Діти: <1 року: 100.0 - 140.0"),
            row("", "", "", "1-6 років: 110.0 - 145.0"),
        )
        assertEquals(1.027, result.readings.single { it.marker == LabMarker.URINE_SG }.value, 1e-9)
        assertEquals(5.0, result.readings.single { it.marker == LabMarker.URINE_PH }.value, 1e-9)
        // The urine's haemoglobin is not the blood's, and a baby's range is not this person's.
        val hemoglobin = result.readings.single { it.marker == LabMarker.HEMOGLOBIN }
        assertEquals(141.0, hemoglobin.value, 1e-9)
        assertTrue(hemoglobin.range.empty)
    }

    @Test
    fun aFigureGluedAfterTheNamesAbbreviationIsStillTheFigure() {
        // A phone's OCR joins the name and the result 11 px apart into one cell.
        val reading = scan(row("Aspartate Aminotransferase (AST/SGOT), 17.94", "U/L", "< 32")).readings.single()
        assertEquals(LabMarker.AST, reading.marker)
        assertEquals(17.94, reading.value, 1e-9)
        assertEquals(32.0, reading.range.high!!, 1e-9)
    }

    @Test
    fun aUnitInTheFiguresCellDoesNotTakeTheFiguresLastDigits() {
        // "IU/L" comes back as "1U/L", so a "1" before "U/L" is unit — but not the last digit of a 131.
        assertEquals(131.0, scan(row("ALT", "131 U/L", "0 - 41")).readings.single().value, 1e-9)
        assertEquals(21.0, scan(row("АЛТ", "21 Ед/л", "0 - 41")).readings.single().value, 1e-9)
        assertEquals(41.0, scan(row("AST", "25", "< 41 U/L")).readings.single().range.high!!, 1e-9)
        // Nor are a count's last four digits the multiplier of "×1000/µL".
        assertEquals(251.0, scan(row("Platelets", "251,000 /uL")).readings.single().value, 1e-6)
        // Standing apart from the figure, the same digits are still the unit's.
        assertEquals(25.0, scan(row("ALT", "25 1U/L")).readings.single().value, 1e-9)
        assertEquals(251.0, scan(row("Platelets", "251 1000/uL")).readings.single().value, 1e-6)
    }

    /**
     * The same for every unit a figure is kept in as printed, and every figure whose digits a unit
     * could take — a last 1, a last 10, a 1000 — spaced from its unit or glued on: the figure read is
     * the figure printed, or none. Only a lone "1" before "U/L" may be left unread, since that is how
     * OCR hands back "IU/L" whose figure was lost; two digits or more are always read.
     */
    @Test
    fun aFigureSharingItsCellWithItsUnitIsNeverReadAsAnotherFigure() {
        val cases = listOf(
            Triple("ALT", "U/L", LabMarker.ALT),
            Triple("ALT", "IU/L", LabMarker.ALT),
            Triple("АЛТ", "Ед/л", LabMarker.ALT),
            Triple("Glucose", "mmol/L", LabMarker.GLUCOSE),
            Triple("Creatinine", "µmol/L", LabMarker.CREATININE),
            Triple("Creatinine", "umol/L", LabMarker.CREATININE),
            Triple("Hemoglobin", "g/L", LabMarker.HEMOGLOBIN),
            Triple("TSH", "mIU/L", LabMarker.TSH),
            Triple("Platelets", "10^9/L", LabMarker.PLATELETS),
        )
        val cells = cases.flatMap { (name, unit, marker) ->
            listOf(1, 11, 21, 101, 1000).filter { it.toDouble() in marker.valid }.flatMap { figure ->
                // A count's exponent glued to its figure cannot be told from it: "25110^9/L".
                val glued = listOf("$figure$unit").takeIf { !unit.startsWith("10") }.orEmpty()
                (listOf("$figure $unit") + glued).map { Triple(name, it, figure) }
            }
        }
        cells.forEach { (name, cell, figure) ->
            val read = scan(row(name, cell)).readings.map { it.value }
            if (figure >= 10) {
                assertEquals(cell, listOf(figure.toDouble()), read)
            } else {
                assertTrue("$cell read as $read", read.all { it == figure.toDouble() })
            }
        }
    }

    /**
     * A Russian sheet's units in Cyrillic: insulin's "мкЕд/мл" must not come out as "мкu/ml", no unit,
     * and a count's Cyrillic "х" before "10^9/л" is a letter the count's pattern opens after.
     */
    @Test
    fun aRussianInsulinAndACountWithACyrillicTimesSignAreRead() {
        val insulin = scan(row("Инсулин", "8,5", "мкЕд/мл", "2,6 - 24,9")).readings.single()
        assertEquals(LabMarker.INSULIN, insulin.marker)
        assertEquals(8.5, insulin.value, 1e-9)
        val platelets = scan(row("Тромбоциты", "250", "х10^9/л", "150 - 400")).readings.single()
        assertEquals(250.0, platelets.value, 1e-9)
        assertEquals(400.0, platelets.range.high!!, 1e-9)
    }

    /**
     * An Indian count grouped by lakhs, "2,51,000 /cumm", is 251 thousand. Its commas are not taken for
     * decimal ones, nor the sheet's with them, and the count is read.
     */
    @Test
    fun aCountGroupedByLakhsIsReadWhole() {
        val platelets = scan(row("Platelet Count", "2,51,000", "/cumm", "1,50,000 - 4,10,000")).readings.single()
        assertEquals(251.0, platelets.value, 1e-6)
        assertEquals(150.0, platelets.range.low!!, 1e-6)
        assertEquals(410.0, platelets.range.high!!, 1e-6)
    }

    /**
     * A sheet whose decimals all have three places after the comma: "0,012" is no thousands, so it
     * names the comma as the decimal mark, and the "15,500" under it is fifteen and a half. Read as
     * thousands, neither figure would be one its marker could hold.
     */
    @Test
    fun aZeroBeforeTheCommaMakesItTheDecimalMark() {
        val result = scan(
            row("TSH", "0,012", "mIU/L", "0,270 - 4,200"),
            row("Free T4", "15,500", "pmol/L", "12,000 - 22,000"),
        )
        assertEquals(0.012, result.readings.single { it.marker == LabMarker.TSH }.value, 1e-9)
        val ft4 = result.readings.single { it.marker == LabMarker.FT4 }
        assertEquals(15.5, ft4.value, 1e-9)
        assertEquals(22.0, ft4.range.high!!, 1e-9)
    }

    @Test
    fun anMcvRangeOnlyAnAnimalPrintsMarksTheSheetAnimal() {
        assertTrue(scan(row("MCV", "47", "fL", "39-55")).veterinary)
        // A person's MCV beside the haematocrit's range, read into its row, is still a person's.
        assertFalse(scan(row("MCV", "90.91", "fL", "38-48")).veterinary)
    }

    @Test
    fun refusesVeterinaryReports() {
        val result = scan(row("Species: Canine"), row("Glucose", "5.2", "mmol/L"))
        assertTrue(result.veterinary)
        assertTrue(result.readings.isEmpty())
    }

    @Test
    fun unrelatedTextProducesNoResults() {
        assertTrue(scan(row("Reddit community discussion"), row("123 456 789")).readings.isEmpty())
        assertEquals(LabSheetScan(), scan())
    }

    @Test
    fun birthDateAndFutureDateAreNotCollectionDates() {
        val result =
            scan(row("DOB 01/02/1995"), row("Collected 2026-09-20"), row("2027-09-20"), row("Glucose", "5.2", "mmol/L"))
        assertEquals(LocalDate.of(2026, 9, 20), result.day)
        assertNull(scan(row("DOB 01/02/1995"), row("Glucose", "5.2", "mmol/L")).day)
    }

    @Test
    fun aDrawDateWithItsClockWeldedOnStillDatesTheSheet() {
        val boxes = boxes(
            row("Collected: 01/13/20257:30 am"),
            row("Completed: 01/14/2025 9:05"),
            row("Glucose", "5.2", "mmol/L"),
        )
        assertEquals(LocalDate.of(2025, 1, 13), scanLabSheet(boxes, today, true).day)
    }

    @Test
    fun aMonthNamedBetweenSlashesDatesTheSheet() {
        val result =
            scan(row("Date Coll: 16/Sep/2024 12:28", "Date Rec: 17/Sep/2024 02:59"), row("Glucose", "5.2", "mmol/L"))
        assertEquals(LocalDate.of(2024, 9, 16), result.day)
    }

    @Test
    fun aRowOfBareDatesHeadsTheColumnsAndTheNewestIsRead() {
        val result = scan(row("CMP", "10/31/15", "10/26/16"), row("Glucose", "90", "85", "mg/dL", "70 - 99"))
        assertEquals(85 / 18.016, result.readings.single().value, 1e-6)
        assertEquals(LocalDate.of(2016, 10, 26), result.day)
    }

    @Test
    fun ambiguousSlashDatesFollowRequestedRegion() {
        val boxes = boxes(row("Collected 03/04/2026"), row("Glucose", "5.2", "mmol/L"))
        assertEquals(LocalDate.of(2026, 4, 3), scanLabSheet(boxes, today, false).day)
        assertEquals(LocalDate.of(2026, 3, 4), scanLabSheet(boxes, today, true).day)
    }

    /**
     * A dashed date is read as a slashed one is. Read day first on every phone, an American sheet's
     * 03-04-2026 would be the third of April on an American phone, and its 01-13-2026 no date.
     */
    @Test
    fun ambiguousDashDatesFollowRequestedRegion() {
        val boxes = boxes(row("Collected 03-04-2026"), row("Glucose", "5.2", "mmol/L"))
        assertEquals(LocalDate.of(2026, 4, 3), scanLabSheet(boxes, today, false).day)
        assertEquals(LocalDate.of(2026, 3, 4), scanLabSheet(boxes, today, true).day)
        // A dotted date stays day first, whatever the region.
        val dotted = boxes(row("Collected 03.04.2026"), row("Glucose", "5.2", "mmol/L"))
        assertEquals(LocalDate.of(2026, 4, 3), scanLabSheet(dotted, today, true).day)
        assertEquals(LocalDate.of(2026, 4, 3), scanLabSheet(dotted, today, false).day)
        // A date only one order can read is read in that order, on either phone, whichever part it is.
        val january = boxes(row("Collected 01-13-2026"), row("Glucose", "5.2", "mmol/L"))
        assertEquals(LocalDate.of(2026, 1, 13), scanLabSheet(january, today, true).day)
        assertEquals(LocalDate.of(2026, 1, 13), scanLabSheet(january, today, false).day)
        val thirteenth = boxes(row("Collected 13-01-2026"), row("Glucose", "5.2", "mmol/L"))
        assertEquals(LocalDate.of(2026, 1, 13), scanLabSheet(thirteenth, today, true).day)
        assertEquals(LocalDate.of(2026, 1, 13), scanLabSheet(thirteenth, today, false).day)
    }

    /** And such a date settles the order of the sheet's other dashed dates, as a slashed one does. */
    @Test
    fun aDashedDateOnlyOneOrderReadsSettlesTheSheetsOthers() {
        val sheet = scan(row("Collected 02-03-2026"), row("Reported 02-14-2026"), row("Glucose", "5.2", "mmol/L"))
        assertEquals(LocalDate.of(2026, 2, 3), sheet.day)
        // A sample number shaped like a date says nothing: the year has to be a year.
        val numbered = scan(row("Sample 02-14-0042"), row("Collected 02-03-2026"), row("Glucose", "5.2", "mmol/L"))
        assertEquals(LocalDate.of(2026, 3, 2), numbered.day)
        // Nor does a telephone number: a part over 31 is no day.
        val telephone = scan(row("Tel 12-34-56"), row("Collected 02-03-2026"), row("Glucose", "5.2", "mmol/L"))
        assertEquals(LocalDate.of(2026, 3, 2), telephone.day)
    }

    /**
     * A sheet out of an archive is dated as printed, back to the first day the form's date picker
     * offers and the results page reads, and no further. A reader taking any day since 1990 while the
     * other two stop ten years back would save a 2014 sheet where the results page never shows it, or,
     * with the import refusing such a day, under today's date.
     */
    @Test
    fun anArchivedSheetIsDatedAsFarBackAsTheFormAndTheResultsPageGo() {
        fun dated(day: String) = scan(row("Collected $day"), row("Glucose", "5.2", "mmol/L")).day
        assertEquals(LocalDate.of(2014, 3, 12), dated("12.03.2014"))
        val first = LocalDate.of(today.year - LAB_HISTORY_YEARS, 1, 1)
        assertEquals(first.toString(), labWindowStart(today))
        assertEquals(first, dated("01.01.${first.year}"))
        assertNull(dated("31.12.${first.year - 1}"))
    }

    @Test
    fun formConversionPreservesPrintedPrecision() {
        val reading = scan(row("Glucose", "99.95", "mg/dL", "70 - 100")).readings.single()
        val unit = reading.marker.fieldUnit(reading.printedUnit, true)
        assertEquals("99.95", labImportNumber(reading.value, unit))
        assertEquals(
            reading.value,
            reading.marker.storedIn(labImportNumber(reading.value, unit).toDouble(), unit),
            1e-6,
        )
        assertEquals("0.01", labImportNumber(0.1, LabMarker.CRP.otherUnit))
    }

    /** A bilirubin read off a mg/dL sheet fills its field in mg/dL, on that chip, whatever the profile reads. */
    @Test
    fun aBilirubinKeepsTheUnitItsSheetPrinted() {
        val reading = scan(row("Total Bilirubin", "2.5", "mg/dL", "0.2 - 1.2")).readings.single()
        assertEquals(LabMarker.BILIRUBIN, reading.marker)
        assertEquals(2.5 / 0.0585, reading.value, 1e-6)
        assertEquals("mg/dL", reading.printedUnit)
        assertEquals("2.5", labImportNumber(reading.value, reading.marker.fieldUnit(reading.printedUnit, false)))
    }

    /**
     * A figure a pattern cut out is parsed with toDoubleOrNull, never toDouble. On the phone `\d` is
     * ICU's and takes every script's digits — the East Slavic recogniser's dictionary holds a
     * Devanagari zero — while toDouble takes ASCII alone and throws, which would fail the whole file. No
     * sheet typed here can show that, since java.util.regex reads `\d` as ASCII, so this reads the
     * source instead, as [IcuRegexTest] does. The reader's patterns name their digits `[0-9]` for the
     * same reason: written with the class, each would be one pattern here and another on the phone.
     */
    @Test
    fun aFigureAPatternCutOutIsNeverParsedByACallThatThrows() {
        val source = File("src/main/java/com/pwnall/health/LabSheet.kt").readLines()
        assertTrue(source.size > 500)
        assertEquals(emptyList<String>(), source.filter { "].toDouble()" in it })
        assertEquals(emptyList<String>(), source.filter { "\\d" in it })
    }

    private fun row(vararg cells: String): List<String> = cells.toList()

    private fun boxes(vararg rows: List<String>): List<TextBox> = rows.flatMapIndexed { y, cells ->
        cells.mapIndexed { x, text ->
            val start = if (x == 0) 0 else 260 + (x - 1) * 150
            TextBox(Box(start, y * 40, start + if (x == 0) 220 else 120, y * 40 + 18), text)
        }
    }

    private fun scan(vararg rows: List<String>): LabSheetScan = scanLabSheet(boxes(*rows), today, false)
}
