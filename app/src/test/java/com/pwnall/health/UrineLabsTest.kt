package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The form asks what was given before it offers a field, and a urine panel is read as urine all the
 * way down: its own figures, its own codes, its one graded ratio against KDIGO's lines, and nothing
 * that belongs to a blood draw — a fasting state, a filtration rate, a Health Connect glucose.
 */
class UrineLabsTest {
    private val today = LocalDate.parse("2026-09-25")

    private fun profile(birthDate: String = "1976-06-15") =
        UserProfile(sex = "MALE", weightKg = 75.0, heightCm = 180, birthDate = birthDate)

    private fun panel(payload: String) = JournalEntry(
        day = today.toString(),
        occurredAt = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = payload,
    )

    /**
     * What the answer to the first question lets through: a figure typed under the other sample stays
     * in its field and never reaches the panel being saved, and before any answer nothing does.
     */
    @Test
    fun onlyTheChosenSamplesFiguresAreSaved() {
        val typed = mapOf(LabMarker.GLUCOSE to "5.4", LabMarker.URINE_ACR to "2.1", LabMarker.URINE_PH to "6")
        val metric: (LabMarker) -> LabImperial? = { null }

        assertEquals(mapOf(LabMarker.GLUCOSE to 5.4), labFormValues(LabSample.BLOOD, typed, metric))
        assertEquals(
            mapOf(LabMarker.URINE_ACR to 2.1, LabMarker.URINE_PH to 6.0),
            labFormValues(LabSample.URINE, typed, metric),
        )
        assertTrue(labFormValues(null, typed, metric).isEmpty())
        // Every panel belongs to one of the two, and the urine one holds only urine figures.
        LabMarker.entries.filter { it.panel == LabPanel.URINE }.forEach {
            assertEquals(it.name, LabSample.URINE, it.panel.sample)
        }
        assertEquals(listOf(LabPanel.URINE), LabPanel.entries.filter { it.sample == LabSample.URINE })
    }

    /**
     * KDIGO 2024 Table 3: A1 "<3", A2 "3–30", A3 ">30" mg/mmol. 3.0 is raised, 30.0 still A2, and a
     * sheet in mg/g is converted at KDIGO's own 0.113, so its 26 stays A1 and its 27 does not, and its
     * 265 stays A2 where 266 is A3 — each a step above KDIGO's rounded 30 and 300, never below.
     */
    @Test
    fun theRatioIsGradedOnKdigosLines() {
        fun status(value: Double) = LabMarker.URINE_ACR.status(value, "FEMALE", ageYears = 40, fasting = false)
        assertEquals(LabStatus.NORMAL, status(2.99))
        assertEquals(LabStatus.ABOVE, status(3.0))
        assertEquals(LabStatus.ABOVE, status(30.0))
        assertEquals(LabStatus.FAR_ABOVE, status(30.01))

        val mgPerG = LabMarker.URINE_ACR.otherUnit!!
        assertEquals(LabStatus.NORMAL, status(LabMarker.URINE_ACR.storedIn(26.0, mgPerG)))
        assertEquals(LabStatus.ABOVE, status(LabMarker.URINE_ACR.storedIn(27.0, mgPerG)))
        assertEquals(LabStatus.ABOVE, status(LabMarker.URINE_ACR.storedIn(265.0, mgPerG)))
        assertEquals(LabStatus.FAR_ABOVE, status(LabMarker.URINE_ACR.storedIn(266.0, mgPerG)))
    }

    /**
     * KDIGO gives the same 3 mg/mmol for children over two, so a twelve-year-old's ratio is graded
     * and not called an adult band; everything else on the sheet is a figure only, at any age.
     */
    @Test
    fun theRatioIsGradedFromTwoAndTheRestNever() {
        assertEquals(LabStatus.ABOVE, LabMarker.URINE_ACR.status(4.0, "MALE", ageYears = 12, fasting = false))
        assertFalse(LabMarker.URINE_ACR.adultBandAt(12))
        assertNull(LabMarker.URINE_ACR.status(4.0, "MALE", ageYears = 1, fasting = false))

        val ungraded = LabMarker.entries.filter { it.panel == LabPanel.URINE && it != LabMarker.URINE_ACR }
        assertEquals(5, ungraded.size)
        ungraded.forEach { marker ->
            assertTrue(marker.name, marker.ungraded)
            assertNull(marker.name, marker.status(marker.valid.endInclusive, "MALE", ageYears = 40, fasting = true))
            assertNotNull(marker.name, marker.noteRes)
        }
    }

    /**
     * A raised ratio's advice asks for a first morning sample, not a fasting one, and names KDIGO's
     * lines in the unit KDIGO wrote them in.
     */
    @Test
    fun aRaisedRatioIsNotToldAboutFasting() {
        val reading = LabReading(
            marker = LabMarker.URINE_ACR,
            value = 5.0,
            occurredAt = 0L,
            day = today.toString(),
            fasting = false,
            ageYears = 50,
            status = LabStatus.ABOVE,
            specimen = LabSpecimen.URINE,
        )
        val advice = labsAdvice(listOf(reading), "MALE", imperial = false, today = today)!!
        assertEquals(R.string.advice_labs_urine_high_body, advice.bodyRes)
        assertEquals(R.string.labs_band_source_kdigo_acr, advice.sourceRes)
    }

    /**
     * A panel marked urine can arrive with the blood fields
     * filled. Its "glucose" and "creatinine" are not read as blood figures — not graded, not run
     * through CKD-EPI, not sent to Health Connect — while the same figures on a blood panel still are.
     */
    @Test
    fun aFigureOnAPanelMarkedUrineIsNotABloodFigure() {
        fun payload(specimen: LabSpecimen?) = labsPayload(
            values = mapOf(LabMarker.CREATININE to 98.0, LabMarker.GLUCOSE to 5.6),
            fasting = false,
            specimen = specimen,
        )
        val urine = panel(payload(LabSpecimen.URINE))
        val blood = panel(payload(null))

        assertTrue(labHistory(listOf(urine), profile()).isEmpty())
        assertEquals(
            setOf(LabMarker.CREATININE, LabMarker.GLUCOSE, LabMarker.EGFR),
            labHistory(listOf(blood), profile()).keys,
        )
        assertNull(urine.glucoseMmolL())
        assertEquals(5.6, blood.glucoseMmolL()!!, 1e-9)
    }

    /** A urine panel is stored as urine and not fasting, so nothing downstream calls it a fasting draw. */
    @Test
    fun aUrinePanelIsStoredAsUrineAndNotFasting() {
        val entry = panel(
            labsPayload(values = mapOf(LabMarker.URINE_SG to 1.02), fasting = false, specimen = LabSpecimen.URINE),
        )
        val reading = labHistory(listOf(entry), profile()).getValue(LabMarker.URINE_SG).single()

        assertEquals(LabSpecimen.URINE, reading.specimen)
        assertFalse(reading.fasting)
        assertEquals(LabSpecimen.URINE, entry.labSpecimen())
    }
}
