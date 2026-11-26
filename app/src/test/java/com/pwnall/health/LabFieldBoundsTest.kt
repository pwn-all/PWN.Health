package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The figures the lab form tells the person it accepts have to be figures it accepts: the refusal
 * hint is read by someone retyping a number that was just turned down.
 */
class LabFieldBoundsTest {
    private val day = LocalDate.parse("2026-08-08")

    private fun reading(marker: LabMarker, value: Double, age: Int) = LabReading(
        marker = marker,
        value = value,
        occurredAt = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        day = day.toString(),
        fasting = true,
        ageYears = age,
        status = marker.status(value, "MALE", age, fasting = true),
    )

    @Test
    fun everyBoundTheFormPrintsIsABoundTheFormTakes() {
        LabMarker.entries.forEach { marker ->
            listOf(false, true).forEach { imperial ->
                val lowest = marker.lowestAcceptedIn(marker.unit(imperial))
                val highest = marker.highestAcceptedIn(marker.unit(imperial))
                assertTrue(
                    "${marker.key} lowest $lowest, imperial=$imperial",
                    marker.stored(lowest, imperial) in marker.valid,
                )
                assertTrue(
                    "${marker.key} highest $highest, imperial=$imperial",
                    marker.stored(highest, imperial) in marker.valid,
                )
            }
        }
    }

    /**
     * The same promise for a field switched to its other unit, CRP's typed-only mg/dL included — and
     * the bound is the last figure taken, not one a floating-point residue stepped inwards.
     */
    @Test
    fun everyBoundASwitchedFieldPrintsIsABoundItTakes() {
        LabMarker.entries.mapNotNull { marker -> marker.otherUnit?.let { marker to it } }.forEach { (marker, unit) ->
            val lowest = marker.lowestAcceptedIn(unit)
            val highest = marker.highestAcceptedIn(unit)
            val step = Math.pow(10.0, -unit.decimals.toDouble())
            assertTrue("${marker.key} lowest $lowest in the other unit", marker.storedIn(lowest, unit) in marker.valid)
            assertTrue(
                "${marker.key} highest $highest in the other unit",
                marker.storedIn(highest, unit) in marker.valid,
            )
            assertFalse("${marker.key} under $lowest", marker.storedIn(lowest - step, unit) in marker.valid)
            assertFalse("${marker.key} over $highest", marker.storedIn(highest + step, unit) in marker.valid)
        }
    }

    /**
     * A field with no unit choice has to refuse a figure copied in its other unit, or the figure is
     * kept as something the sheet never said. Bilirubin's two units meet from 1 to 35.1, every ordinary
     * result in either unit; glucose's from 10 to 50, LDL's from 12 to 15, the triglycerides' from 18
     * to 20 and creatinine's from 20 to 22.6, the far ends where a wrong unit costs most. Each has the
     * choice, haemoglobin too, whose two units never meet, and no field without one meets its other
     * unit anywhere — which a new marker, or a widened range, cannot change unnoticed.
     */
    @Test
    fun aFieldWithNoUnitChoiceNeverMeetsItsOtherUnit() {
        val meeting = LabMarker.entries.filter { !it.derived && it.otherUnit == null }.mapNotNull { marker ->
            val other = marker.imperial ?: return@mapNotNull null
            val from = maxOf(marker.lowestAcceptedIn(null), marker.lowestAcceptedIn(other))
            val to = minOf(marker.highestAcceptedIn(null), marker.highestAcceptedIn(other))
            (marker to (from to to)).takeIf { from <= to }
        }.toMap()
        assertEquals(emptyMap<LabMarker, Pair<Double, Double>>(), meeting)
    }

    /**
     * A British sheet's glucose of 12 mmol/L under the imperial setting. In
     * the profile's mg/dL it is 0.67 mmol/L and far below; against the mmol/L chip it is what the sheet
     * said, and far above. The line under the field prints the other reading before anything is saved.
     */
    @Test
    fun aGlucoseAtTheFarEndIsReadInTheUnitItsChipNames() {
        val glucose = LabMarker.GLUCOSE
        fun status(typedIn: LabImperial?) = glucose.status(glucose.storedIn(12.0, typedIn), "MALE", 40, fasting = true)
        assertEquals(LabStatus.FAR_BELOW, status(glucose.fieldUnit(null, imperialUnits = true)))
        assertEquals(LabStatus.FAR_ABOVE, status(glucose.fieldUnit("mmol/L", imperialUnits = true)))
        assertEquals(glucose.imperial, glucose.fieldUnit("mg/dL", imperialUnits = false))
        assertEquals("216" to R.string.labs_unit_mgdl, glucose.inOtherUnit(12.0, null))
        assertEquals("0.7" to R.string.labs_unit_mmol, glucose.inOtherUnit(12.0, glucose.otherUnit))
    }

    /**
     * A British sheet prints HbA1c in mmol/mol alone, and a field taking only 3 to 20% would refuse its 42.
     * On the field's mmol/mol chip it is the 6.0% it means, through the IFCC–NGSP equation the sheet
     * reader converts with, and the panel records the unit by its UCUM spelling.
     */
    @Test
    fun anHba1cInMmolPerMolIsTakenOnItsOwnChip() {
        val a1c = LabMarker.HBA1C
        val ifcc = a1c.fieldUnit("mmol/mol", imperialUnits = false)
        assertEquals(a1c.otherUnit, ifcc)
        assertEquals("mmol/mol", a1c.typedUnitCode(ifcc))
        assertFalse(a1c.storedIn(42.0, null) in a1c.valid)
        assertEquals(42 * 0.09148 + 2.152, a1c.storedIn(42.0, ifcc), 1e-9)
        assertEquals(LabStatus.ABOVE, a1c.status(a1c.storedIn(42.0, ifcc), "MALE", 40, fasting = true))
        assertEquals("10" to "195", a1c.acceptedBounds(ifcc))
        assertEquals("42" to R.string.labs_unit_mmolmol, a1c.inOtherUnit(6.0, null))
        assertEquals("6" to R.string.labs_unit_percent, a1c.inOtherUnit(42.0, ifcc))
        assertEquals("42", labImportNumber(a1c.storedIn(42.0, ifcc), ifcc))
    }

    /** The rounded edge of the raw range would be refused, so the hint prints the lowest figure taken. */
    @Test
    fun theRoundedEdgeOfTheRawRangeWouldHaveBeenRefused() {
        val printedStart = LabMarker.GLUCOSE.display(LabMarker.GLUCOSE.valid.start, imperialUnits = true)
        assertEquals(9.0, printedStart, 0.0001)
        assertFalse(LabMarker.GLUCOSE.stored(printedStart, imperialUnits = true) in LabMarker.GLUCOSE.valid)
        assertEquals(10.0, LabMarker.GLUCOSE.lowestAcceptedIn(LabMarker.GLUCOSE.unit(imperialUnits = true)), 0.0001)
    }

    /** Metric fields print their own range: nothing is converted, so nothing is rounded. */
    @Test
    fun theMetricBoundsAreTheRangeItself() {
        assertEquals(0.005, LabMarker.TSH.lowestAcceptedIn(null), 0.0)
        assertEquals(150.0, LabMarker.TSH.highestAcceptedIn(null), 0.0)
    }

    @Test
    fun eGfrIsReportedAsNotCalculatedOnlyUnderEighteen() {
        val child = mapOf(LabMarker.CREATININE to listOf(reading(LabMarker.CREATININE, 60.0, age = 14)))
        val adult = mapOf(LabMarker.CREATININE to listOf(reading(LabMarker.CREATININE, 90.0, age = 40)))
        assertTrue(egfrNotCalculated(child))
        assertFalse(egfrNotCalculated(adult))
        assertFalse(egfrNotCalculated(emptyMap()))
    }
}
