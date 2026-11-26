package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What [labHistory] makes of a panel before anything prints it: whose range and analyser a figure
 * the app worked out carries, when the meals around a draw may say it was fasting, and how one day's
 * panels are ordered and read together.
 */
class LabHistoryTest {
    private val today = LocalDate.parse("2026-08-08")

    private fun profile() = UserProfile(sex = "MALE", weightKg = 75.0, heightCm = 180, birthDate = "1976-06-15")

    private fun at(day: LocalDate): Long = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** A panel as the journal holds one, drawn at noon today. */
    private fun panel(payload: String, id: Long = 0L) = JournalEntry(
        id = id,
        day = today.toString(),
        occurredAt = at(today),
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = payload,
    )

    private fun panel(id: Long, vararg values: Pair<LabMarker, Double>) =
        panel(labsPayload(values = values.toMap(), fasting = true), id)

    /**
     * Two panels of one day share their noon, and the one written down later is the newer, not
     * whichever of the two SQLite happens to hand over last.
     */
    @Test
    fun ofTwoPanelsOnOneDayTheOneWrittenLaterIsTheNewest() {
        val history = labHistory(
            listOf(panel(7, LabMarker.GLUCOSE to 6.2), panel(3, LabMarker.GLUCOSE to 5.1)),
            profile(),
        )

        assertEquals(listOf(3L, 7L), history.getValue(LabMarker.GLUCOSE).map(LabReading::entryId))
    }

    /**
     * A sheet saved again writes only what the day's panels lack, so a visit's total cholesterol can
     * lie in one row and its HDL in the next; its non-HDL is still worked out, and a remark that needs
     * two figures off one sheet still sees them both. Two totals that day are two draws, and the HDL
     * belongs to neither.
     */
    @Test
    fun oneVisitSavedInTwoRowsIsStillOneSheet() {
        val split = labHistory(
            listOf(
                panel(1, LabMarker.CHOLESTEROL to 5.4, LabMarker.HBA1C to 6.1),
                panel(2, LabMarker.HDL to 1.1, LabMarker.HEMOGLOBIN to 110.0),
            ),
            profile(),
        )
        assertEquals(4.3, split.getValue(LabMarker.NON_HDL).single().value, 1e-9)
        val caveat = hba1cReliabilityAdvice(split.values.flatten(), imperial = false, today = today)
        assertEquals("labs-a1c-context", caveat?.id)

        val twoDraws = labHistory(
            listOf(
                panel(1, LabMarker.CHOLESTEROL to 5.4),
                panel(2, LabMarker.CHOLESTEROL to 6.0),
                panel(3, LabMarker.HDL to 1.1),
            ),
            profile(),
        )
        assertNull(twoDraws[LabMarker.NON_HDL])
    }

    /**
     * A figure the app works out is not the laboratory's: an eGFR that carried the creatinine's
     * printed range would show "the laboratory's own range" in µmol/L beside a filtration rate, and the
     * exported file would carry it as the laboratory's too. The sample stays, since the code needs it.
     */
    @Test
    fun aDerivedFigureCarriesNoLaboratoryRangeOrAnalyser() {
        val entry = panel(
            labsPayload(
                values = mapOf(
                    LabMarker.CREATININE to 98.0,
                    LabMarker.CHOLESTEROL to 5.4,
                    LabMarker.HDL to 1.1,
                    LabMarker.GLUCOSE to 5.6,
                    LabMarker.INSULIN to 9.0,
                ),
                fasting = true,
                specimen = LabSpecimen.SERUM,
                method = "Invitro",
                reportedRanges = mapOf(
                    LabMarker.CREATININE to ReportedRange(low = 62.0, high = 106.0),
                    LabMarker.CHOLESTEROL to ReportedRange(high = 5.2),
                    LabMarker.GLUCOSE to ReportedRange(low = 4.1, high = 5.9),
                ),
            ),
        )

        val history = labHistory(listOf(entry), profile())

        listOf(LabMarker.EGFR, LabMarker.NON_HDL, LabMarker.HOMA_IR).forEach { marker ->
            val derived = history.getValue(marker).single()
            assertNull("$marker inherited a laboratory range", derived.reported)
            assertEquals("$marker inherited an analyser", "", derived.method)
            assertEquals(LabSpecimen.SERUM, derived.specimen)
        }
        // The results the laboratory did print keep theirs.
        val creatinine = history.getValue(LabMarker.CREATININE).single()
        assertEquals(ReportedRange(low = 62.0, high = 106.0), creatinine.reported)
        assertEquals("Invitro", creatinine.method)
    }

    /**
     * "Not fasting" typed on the form is an answer, and the meals around the draw do not overrule
     * it. A dinner logged the evening before, and no breakfast logged, does not turn a sample drawn
     * after breakfast into a fasting one, graded against the fasting bands and with a HOMA-IR worked
     * out of it. A payload that says nothing either way is still read from the meals.
     */
    @Test
    fun anExplicitFedSampleStaysFed() {
        val dinner = JournalEntry(
            day = today.minusDays(1).toString(),
            occurredAt = at(today) - 16 * 60 * 60 * 1000L,
            kind = "EAT",
            title = "Dinner",
            calories = 700.0,
        )
        val fed = panel(
            labsPayload(
                values = mapOf(LabMarker.GLUCOSE to 6.6, LabMarker.TRIGLYCERIDES to 1.9, LabMarker.INSULIN to 14.0),
                fasting = false,
            ),
        )

        val history = labHistory(listOf(dinner, fed), profile())

        val glucose = history.getValue(LabMarker.GLUCOSE).single()
        assertFalse(glucose.fasting)
        assertEquals(LabStatus.NORMAL, glucose.status)
        assertEquals(LabStatus.NORMAL, history.getValue(LabMarker.TRIGLYCERIDES).single().status)
        assertNull("a HOMA-IR from a fed sample", history[LabMarker.HOMA_IR])

        val silent = panel("""{"schema":1,"concentrationMmolL":6.6}""")
        assertTrue(labHistory(listOf(dinner, silent), profile()).getValue(LabMarker.GLUCOSE).single().fasting)
    }
}
