package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabResaveTest {
    /**
     * A sheet read a second time writes only what the day's panels do not hold: the same figure again
     * is the same result, a different one is a second draw, and a meter's glucose is not a panel.
     */
    @Test
    fun aSheetSavedTwiceKeepsOnlyWhatTheDayDoesNotHold() {
        fun row(kind: String, values: Map<LabMarker, Double>) = JournalEntry(
            day = "2026-10-01",
            occurredAt = 0L,
            kind = kind,
            title = "",
            calories = 0.0,
            measurementPayloadJson = labsPayload(values, fasting = true),
        )
        val saved = listOf(
            row(KIND_LABS, mapOf(LabMarker.GLUCOSE to 5.2, LabMarker.HDL to 1.4)),
            row("MEASUREMENT", mapOf(LabMarker.CHOLESTEROL to 4.8)),
        )

        val again = mapOf(LabMarker.GLUCOSE to 5.2, LabMarker.HDL to 1.5, LabMarker.CHOLESTEROL to 4.8)
        assertEquals(
            mapOf(LabMarker.HDL to 1.5, LabMarker.CHOLESTEROL to 4.8),
            unsavedLabValues(again, saved),
        )
        assertTrue(unsavedLabValues(mapOf(LabMarker.GLUCOSE to 5.2), saved).isEmpty())
        assertEquals(again, unsavedLabValues(again, emptyList()))
    }

    /**
     * Figures typed by hand, then the laboratory's PDF read to keep the original beside them. Every
     * figure is held already, so no figure is written — but the file, the laboratory's name and its
     * printed range do not go nowhere, under a page that says "already saved". They go to the panel
     * that holds the figures, and fill only what it lacks.
     */
    @Test
    fun aSheetSavedAgainLeavesItsFileWithThePanelThatHoldsItsFigures() {
        fun row(id: Long, payload: String, kind: String = KIND_LABS) = JournalEntry(
            id = id,
            day = "2026-10-01",
            occurredAt = 0L,
            kind = kind,
            title = "",
            calories = 0.0,
            measurementPayloadJson = payload,
        )
        val typed = mapOf(LabMarker.GLUCOSE to 5.2, LabMarker.HDL to 1.4)
        val saved = listOf(
            row(1, labsPayload(mapOf(LabMarker.ALT to 20.0), fasting = true)),
            row(2, labsPayload(typed, fasting = true, method = "Typed by hand")),
            row(3, labsPayload(typed, fasting = true), kind = "MEASUREMENT"),
        )
        val sheet = labsPayload(
            typed,
            fasting = true,
            attachments = "content://sheet.pdf",
            method = "City laboratory",
            reportedRanges = mapOf(LabMarker.GLUCOSE to ReportedRange(3.9, 6.1)),
        )

        val kept = panelWithSheet(typed, saved, sheet)!!
        assertEquals(2L, kept.id)
        assertEquals(listOf("content://sheet.pdf"), attachmentUris(kept.labAttachments()))
        val payload = JSONObject(kept.measurementPayloadJson!!)
        assertEquals(mapOf(LabMarker.GLUCOSE to ReportedRange(3.9, 6.1)), payload.reportedRanges())
        // What was saved stays as it was saved.
        assertEquals("Typed by hand", payload.getString(METHOD_KEY))
        assertEquals(5.2, payload.getDouble(LabMarker.GLUCOSE.key), 0.0)

        // The page after the save says which it was: the file reached the panel, nothing was left to
        // do, or figures were written. A laboratory's name filled in alone is not a file attached.
        assertEquals(LabSaveOutcome.ATTACHED, labSaveOutcome(emptyMap(), saved, kept))
        assertEquals(LabSaveOutcome.ALREADY, labSaveOutcome(emptyMap(), saved, null))
        assertEquals(LabSaveOutcome.ADDED, labSaveOutcome(typed, saved, null))
        val unnamed = listOf(row(4, labsPayload(typed, fasting = true, attachments = "content://sheet.pdf")))
        val named = panelWithSheet(typed, unnamed, sheet)!!
        assertEquals("City laboratory", JSONObject(named.measurementPayloadJson!!).getString(METHOD_KEY))
        assertEquals(LabSaveOutcome.ALREADY, labSaveOutcome(emptyMap(), unnamed, named))

        // Saved a third time it brings nothing the panel lacks, and nothing is written.
        assertNull(panelWithSheet(typed, saved.map { if (it.id == 2L) kept else it }, sheet))
        // Nor is anything where no panel of the day holds one of its figures.
        assertNull(panelWithSheet(mapOf(LabMarker.LDL to 3.0), saved, sheet))
        assertNull(panelWithSheet(typed, emptyList(), sheet))
    }

    /**
     * What a Save writes, as the page decides it: a panel all new is written whole; a panel the day
     * half holds writes the other half with that half's ranges and units alone; and a panel the day
     * holds whole writes no row, and its file goes to the panel that holds the figures, not nowhere.
     */
    @Test
    fun aSaveWritesOnlyTheNewFiguresWithTheirOwnRangesAndKeepsAHeldSheetsFile() {
        val held = listOf(
            JournalEntry(
                id = 7,
                day = "2026-10-01",
                occurredAt = 0L,
                kind = KIND_LABS,
                title = "",
                calories = 0.0,
                measurementPayloadJson = labsPayload(mapOf(LabMarker.GLUCOSE to 5.2), fasting = true),
            ),
        )
        val values = mapOf(LabMarker.GLUCOSE to 5.2, LabMarker.HDL to 1.4)
        val ranges = mapOf(LabMarker.GLUCOSE to ReportedRange(3.9, 6.1), LabMarker.HDL to ReportedRange(1.0, null))
        val units = mapOf(LabMarker.GLUCOSE to "mmol/L", LabMarker.HDL to "mg/dL")
        val sheet = labsPayload(values, fasting = true, attachments = "content://sheet.pdf", reportedRanges = ranges)

        val fresh = labPanelWrite(values, emptyList(), ranges, units, sheet)
        assertEquals(values, fresh.kept)
        assertEquals(ranges, fresh.ranges)
        assertEquals(units, fresh.units)
        assertNull(fresh.merged)

        val half = labPanelWrite(values, held, ranges, units, sheet)
        assertEquals(mapOf(LabMarker.HDL to 1.4), half.kept)
        assertEquals(mapOf(LabMarker.HDL to ReportedRange(1.0, null)), half.ranges)
        assertEquals(mapOf(LabMarker.HDL to "mg/dL"), half.units)
        assertNull(half.merged)

        val whole = labPanelWrite(mapOf(LabMarker.GLUCOSE to 5.2), held, ranges, units, sheet)
        assertTrue(whole.kept.isEmpty())
        val merged = whole.merged!!
        assertEquals(7L, merged.id)
        assertEquals(listOf("content://sheet.pdf"), attachmentUris(merged.labAttachments()))
        assertEquals(LabSaveOutcome.ATTACHED, labSaveOutcome(whole.kept, held, merged))
    }
}
