package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.Locale

class LabImportTest {
    private val day = LocalDate.of(2026, 9, 20)

    @Test
    fun mergesPhotosAndPdfPagesAndAttachesEachOriginalOnce() {
        val result = labImportDrafts(
            listOf(
                page("photo", day, SheetReading(LabMarker.GLUCOSE, 5.2)),
                page("pdf", day, SheetReading(LabMarker.ALT, 20.0)),
                page("pdf", null, SheetReading(LabMarker.HEMOGLOBIN, 140.0)),
            ),
        ).single()
        assertEquals(day, result.scan.day)
        assertEquals(3, result.scan.readings.size)
        assertEquals(listOf("photo", "pdf"), result.sources)
    }

    @Test
    fun contradictoryCopiesAreNotFilled() {
        val result = labImportDrafts(
            listOf(
                page("first", day, SheetReading(LabMarker.GLUCOSE, 5.2)),
                page("second", day, SheetReading(LabMarker.GLUCOSE, 7.4)),
            ),
        ).single()
        assertTrue(result.scan.readings.isEmpty())
        assertEquals(1, result.scan.unread)
        assertEquals(2, result.sources.size)
    }

    @Test
    fun matchingCopiesKeepOneReadingAndThePrintedRange() {
        val range = ReportedRange(3.9, 6.1)
        val reading = SheetReading(LabMarker.GLUCOSE, 5.2)
        val result = labImportDrafts(
            listOf(page("a", day, reading), page("b", day, reading.copy(range = range))),
        ).single()
        assertEquals(reading.copy(range = range), result.scan.readings.single())
    }

    @Test
    fun differentDatesAndUnattributedPagesStaySeparate() {
        val drafts = labImportDrafts(
            listOf(page("a", day), page("b", day.minusDays(2)), page("unknown", null)),
        )
        assertEquals(listOf(day, day.minusDays(2), null), drafts.map { it.scan.day })
        assertTrue(drafts[0].canApply(setOf(LabMarker.ALT), day))
        assertFalse(drafts[1].canApply(setOf(LabMarker.ALT), day))
        assertTrue(drafts[1].canApply(emptySet(), day))
    }

    @Test
    fun unreadableAndVeterinaryFilesRemainAttachmentsWithoutAddingValues() {
        val vet = LabImportPage("vet", LabSheetScan(veterinary = true))
        val result = labImportDrafts(listOf(page("unreadable", null), vet)).single()
        assertTrue(result.scan.readings.isEmpty())
        assertEquals(listOf("unreadable", "vet"), result.sources)
    }

    /**
     * An upright page that is all heading is read once: its day stops the turns, as figures stop
     * them at any turn. A day alone off a turned page does not, since the table may be a turn away,
     * and neither does a page that named markers and read no figure.
     */
    @Test
    fun aDayReadUprightStopsTheTurnsAndOneReadTurnedDoesNot() {
        val heading = LabSheetScan(day = day)
        assertTrue(heading.settlesPage(0))
        assertEquals(listOf(false, false, false), listOf(90, 270, 180).map(heading::settlesPage))
        val table = LabSheetScan(listOf(SheetReading(LabMarker.GLUCOSE, 5.2)))
        assertEquals(listOf(true, true, true, true), listOf(0, 90, 270, 180).map(table::settlesPage))
        assertFalse(LabSheetScan().settlesPage(0))
        assertFalse(LabSheetScan(unread = 1).settlesPage(0))
    }

    /**
     * A PDF whose first page is all heading — the patient, the day of the draw, no table — and whose
     * second is the table, with no date. Judged by figures alone, the heading's best reading would be
     * the empty one every page starts from, since no pass has more figures than none, so its day would
     * be dropped and the table dated today. A reading with a day is kept over one without, and the
     * table takes that day.
     */
    @Test
    fun aPageOfNothingButItsDateStillDatesTheTableAfterIt() {
        val heading = LabSheetScan(day = day)
        assertTrue(heading.betterThan(LabSheetScan()))
        assertFalse(LabSheetScan().betterThan(heading))
        assertFalse(heading.betterThan(heading))
        // Figures first, then the markers named and left unread: a day decides only between equals.
        val table = LabSheetScan(listOf(SheetReading(LabMarker.GLUCOSE, 5.2)))
        assertTrue(table.betterThan(heading))
        assertFalse(heading.betterThan(table))
        assertTrue(LabSheetScan(unread = 1).betterThan(heading))
        assertFalse(heading.betterThan(LabSheetScan(unread = 1)))

        val draft = labImportDrafts(listOf(LabImportPage("pdf", heading), LabImportPage("pdf", table))).single()
        assertEquals(day, draft.scan.day)
        assertEquals(listOf(LabMarker.GLUCOSE), draft.scan.readings.map { it.marker })
        // A heading read with no day: the table has none to take.
        val undated = labImportDrafts(listOf(LabImportPage("pdf", LabSheetScan()), LabImportPage("pdf", table)))
        assertNull(undated.single().scan.day)
    }

    /**
     * The phone's region settles a sheet's 03/04/2026 or 03-04-2026 only where it writes a date that
     * way round itself. Sweden and Japan write the year first, so their month is before their day too,
     * and a dashed date from a European sheet is not read month first on their phones.
     */
    @Test
    fun onlyARegionThatWritesTheYearLastSettlesADateWrittenThatWay() {
        assertTrue(localeMonthFirst(Locale.US))
        assertFalse(localeMonthFirst(Locale.UK))
        assertFalse(localeMonthFirst(Locale.GERMANY))
        assertFalse(localeMonthFirst(Locale.forLanguageTag("sv-SE")))
        assertFalse(localeMonthFirst(Locale.JAPAN))
    }

    /**
     * A page takes up the reading its saved state lists only where this process never started one for
     * it. A turn of the phone rebuilds the page over a reading that runs on, and reading the file again
     * from its first page there would be the same twenty pages twice and a PDF's password asked for again.
     */
    @Test
    fun aRebuiltPageDoesNotStartItsReadingAgain() {
        val listed = listOf("content://sheet.pdf")
        assertTrue(resumesLabReading(listed, started = false))
        assertFalse(resumesLabReading(listed, started = true))
        assertFalse(resumesLabReading(emptyList(), started = false))
    }

    /**
     * A file's name is another app's answer — [attachmentName] queries its provider — so no lab page
     * asks for it in the composition, where the query would go out on the main thread at every
     * recomposition: on a document's form, at every letter typed into its note. Each asks through
     * [labFileName] or inside withContext(Dispatchers.IO). Read off the source, as [IcuRegexTest] reads
     * its patterns, because which thread a composition runs on is not something a test here can see.
     */
    @Test
    fun noLabPageAsksAFileItsNameOnTheMainThread() {
        val pages = File("src/main/java/com/pwnall/health").listFiles().orEmpty()
            .filter { it.name.startsWith("Lab") && it.extension == "kt" }
        assertTrue(pages.size > 10)
        val asked = pages.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}".takeIf { "attachmentName(" in line && "Dispatchers.IO" !in line }
            }
        }
        assertEquals(emptyList<String>(), asked)
    }

    private fun page(source: String, date: LocalDate?, vararg readings: SheetReading): LabImportPage =
        LabImportPage(source, LabSheetScan(readings.toList(), day = date))
}
