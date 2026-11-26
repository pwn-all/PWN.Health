package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What a vitals card says beside its figure, where that is decided outside the composable: the
 * notes behind the question mark, and whose device a reading is put down to. [VitalsTest] holds the
 * model the card draws.
 */
class VitalsCardTest {
    private val profile = UserProfile(sex = "MALE", weightKg = 70.0, heightCm = 175, birthDate = "1990-01-01")

    private fun row(payload: String? = null, bodyFatPercent: Double? = null, source: String? = null, at: Long = 0L) =
        JournalEntry(
            day = "2026-09-04",
            occurredAt = at,
            kind = "MEASUREMENT",
            title = "measurement",
            calories = 0.0,
            measurementSource = source,
            measurementPayloadJson = payload,
            bodyFatPercent = bodyFatPercent,
        )

    private fun pressure(systolic: Int, diastolic: Int) =
        """{"schema":1,"systolicMmhg":$systolic,"diastolicMmhg":$diastolic}"""

    /**
     * The band is the ESH's for a measurement at a clinic, and nearly every reading here was taken
     * at home, where the same guideline draws its line at 135/85. The grades stay the office ones —
     * 137/87 is still inside the band — and the card says whose band that is. Only where a band is
     * applied at all: over a child's readings, graded by nothing, there is no band to explain.
     */
    @Test
    fun aGradedPressureSaysItsBandIsAClinics() {
        val note = R.string.vitals_note_pressure_office

        assertTrue(note in vitalExplanations(Vital.BLOOD_PRESSURE, graded = true))
        assertFalse(note in vitalExplanations(Vital.BLOOD_PRESSURE, graded = false))
        Vital.entries.filter { it != Vital.BLOOD_PRESSURE }.forEach { vital ->
            assertFalse(vital.name, note in vitalExplanations(vital, graded = true))
        }

        val atHome = vitalSeries(listOf(row(pressure(137, 87))), Vital.BLOOD_PRESSURE, profile).single()
        assertEquals(LabStatus.NORMAL, atHome.status)
    }

    /**
     * The pressure's low edge is the NHS's "90 or lower", so 90 is low and the printed range starts
     * at 91, the first reading the card colours as inside it. Printed from 90 it would read
     * "90 – 139/89" over a 90 it colours as low. An edge that is not itself low is printed as it
     * stands: oxygen's "from 95".
     */
    @Test
    fun theBottomOfAPrintedRangeIsInsideTheBand() {
        fun status(systolic: Int) =
            vitalSeries(listOf(row(pressure(systolic, 70))), Vital.BLOOD_PRESSURE, profile).single().status

        assertEquals(91.0, Vital.BLOOD_PRESSURE.insideBottom(90.0, Vital.BLOOD_PRESSURE.band), 1e-9)
        assertEquals(LabStatus.NORMAL, status(91))
        assertEquals(LabStatus.BELOW, status(90))
        assertEquals(95.0, Vital.OXYGEN_SATURATION.insideBottom(95.0, Vital.OXYGEN_SATURATION.band), 1e-9)
    }

    /**
     * A pressure typed in for 08:00 sharp and a weight imported for the same instant. Looked up by the
     * instant among every row of the journal, the source would be whichever row came last, and its name
     * would stand under the pressure.
     */
    @Test
    fun aReadingIsPutDownToTheRowItCameFrom() {
        val cuff = row(pressure(128, 84), source = "BLUETOOTH_BPS", at = 500L)
        val scale = row(bodyFatPercent = 21.0, source = "HEALTH_CONNECT", at = 500L)

        listOf(listOf(cuff, scale), listOf(scale, cuff)).forEach { entries ->
            val stats = requireNotNull(vitalStats(vitalSeries(entries, Vital.BLOOD_PRESSURE, profile)))
            assertEquals("BLUETOOTH_BPS", stats.latest.source)
        }
    }

    /**
     * The card prints a reading in the reader's decimal mark, as the rest of the app prints its
     * figures: "36,6" in Russian, not "36.6". The doctor's sheet passes no locale
     * and keeps the point every figure on it is printed with.
     */
    @Test
    fun aReadingIsPrintedInTheReadersDecimalMarkAndTheSheetKeepsThePoint() {
        val russian = Locale.forLanguageTag("ru")

        assertEquals("36,6", readingText(Vital.BODY_TEMPERATURE, 36.6, null, imperial = false, locale = russian))
        assertEquals("36,6", Vital.BODY_TEMPERATURE.printed(36.6, null, imperial = false, locale = russian))
        assertEquals("36.6", readingText(Vital.BODY_TEMPERATURE, 36.6, null, imperial = false))
        assertEquals("128/84", readingText(Vital.BLOOD_PRESSURE, 128.0, 84.0, imperial = false, locale = russian))
    }
}
