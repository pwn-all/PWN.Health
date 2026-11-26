package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class GlucoseServiceTest {
    /**
     * A meter nobody set counts from 2000-01-01 and dates its whole memory there. The wire time is
     * kept — the saved payload is the record's identity across syncs — and the flag says the
     * collector must date the record on arrival instead.
     */
    @Test
    fun aRecordFromAnUnsetClockKeepsItsWireTimeAndLosesTheAppsTrust() {
        val arrival = LocalDateTime.of(2026, 9, 4, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val unset = BluetoothGlucose.parse(
            byteArrayOf(0x00, 0x01, 0x00, 0xD0.toByte(), 0x07, 0x01, 0x01, 0x00, 0x00, 0x00),
            arrivalMillis = arrival,
            zoneId = ZoneOffset.UTC,
        )!!
        assertFalse(unset.deviceClockSet)
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0), unset.baseTime)

        val today = BluetoothGlucose.parse(
            byteArrayOf(0x00, 0x01, 0x00, 0xEA.toByte(), 0x07, 0x09, 0x04, 0x0B, 0x00, 0x00),
            arrivalMillis = arrival,
            zoneId = ZoneOffset.UTC,
        )!!
        assertTrue(today.deviceClockSet)
    }

    /**
     * Sensor status bits 1 to 10 are the meter saying the number is not a concentration: a faulty
     * sensor or strip, too little blood, out of range, too hot or cold, a strip pulled too soon.
     */
    @Test
    fun theMeterOwnVerdictBlocksTheRecord() {
        fun record(status: Int?) = GlucoseMeasurement(
            sequenceNumber = 1,
            baseTime = LocalDateTime.of(2026, 7, 31, 12, 0),
            sensorStatus = status,
        )
        assertFalse(record(null).flaggedInvalid)
        assertFalse(record(0x0801).flaggedInvalid) // battery low and time fault: the reading stands
        (1..10).forEach { bit -> assertTrue("bit $bit", record(1 shl bit).flaggedInvalid) }
    }

    /** Status bit 11 is the meter's own word that its time may be wrong, and the record is dated on arrival. */
    @Test
    fun aTimeFaultTheMeterAnnouncesTakesTheAppsTrustInTheTime() {
        val arrival = LocalDateTime.of(2026, 9, 4, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        fun parsed(status: Int) = BluetoothGlucose.parse(
            byteArrayOf(
                0x08, // status
                0x01, 0x00,
                0xEA.toByte(), 0x07, 0x09, 0x04, 0x0B, 0x00, 0x00, // an hour before arrival
                status.toByte(), (status shr 8).toByte(),
            ),
            arrivalMillis = arrival,
            zoneId = ZoneOffset.UTC,
        )!!
        assertTrue(parsed(0x0001).deviceClockSet)
        assertFalse(parsed(0x0801).deviceClockSet)
        assertFalse(parsed(0x0801).flaggedInvalid)
    }

    /**
     * Type 10 and Sample Location 4 are the profile's Control Solution: a test of the meter with a
     * reference liquid, so it is not saved, graded against the diabetes thresholds or exported to
     * Health Connect as the person's blood glucose.
     */
    @Test
    fun aControlSolutionTestIsNotAReadingOfThePerson() {
        fun record(typeAndLocation: Int) = BluetoothGlucose.parse(
            byteArrayOf(
                0x06, // concentration + mol/L
                0x01, 0x00,
                0xEA.toByte(), 0x07, 0x07, 0x1F, 0x09, 0x0F, 0x00,
                0xA0.toByte(), 0xC0.toByte(), // 0.0160 mol/L = 16 mmol/L, a high control level
                typeAndLocation.toByte(),
            ),
        )!!
        assertEquals(16.0, record(0x4A).concentrationMmolL!!, 0.001)
        assertTrue(record(0x4A).controlSolution)
        assertTrue(record(0x1A).controlSolution) // the type alone says so
        assertTrue(record(0x41).controlSolution) // and so does the sample location alone
        assertFalse(record(0x11).controlSolution)
    }

    /**
     * What a transfer saves: neither a record the meter disowned, nor a test of the meter itself, nor
     * a record without a concentration, which would be a "Glucose" row with no glucose in it.
     */
    @Test
    fun aTransferSavesOnlyTheReadingsOfThePerson() {
        fun record(sequence: Int, type: Int = 1, location: Int = 1, status: Int? = null) = GlucoseRecord(
            measurement(sequence, contextFollows = false).copy(
                concentration = 0.0055,
                concentrationUnit = GlucoseConcentrationUnit.MOLES_PER_LITER,
                type = type,
                sampleLocation = location,
                sensorStatus = status,
            ),
            null,
        )
        val reading = record(1)
        val control = record(2, type = 10, location = 4)
        val disowned = record(3, status = 1 shl 5)
        val withoutFigure = GlucoseRecord(measurement(4, contextFollows = false), null)

        assertEquals(listOf(reading), listOf(reading, control, disowned, withoutFigure).readingsToSave())
    }

    @Test
    fun rejectsZeroYearInMandatoryBaseTime() {
        assertNull(
            BluetoothGlucose.parse(
                byteArrayOf(
                    0x00,
                    0x01,
                    0x00,
                    0x00,
                    0x00,
                    0x01,
                    0x01,
                    0x00,
                    0x00,
                    0x00,
                ),
            ),
        )
    }

    @Test
    fun parsesMassConcentrationTimeOffsetStatusAndContextFlag() {
        val result = BluetoothGlucose.parse(
            byteArrayOf(
                0x1B, // offset + concentration in kg/L + status + context follows
                0x34, 0x12, // sequence
                0xEA.toByte(), 0x07, 0x07, 0x1F, 0x09, 0x0F, 0x00, // base time
                0xC4.toByte(), 0xFF.toByte(), // -60 minutes
                0x7B, 0xB0.toByte(), // 0.00123 kg/L = 123 mg/dL
                0x12, // capillary plasma, finger
                0x01, 0x08, // battery-low and time-fault status bits
            ),
        )!!

        assertEquals(0x1234, result.sequenceNumber)
        assertEquals(LocalDateTime.of(2026, 7, 31, 9, 15), result.baseTime)
        assertEquals(-60, result.timeOffsetMinutes)
        assertEquals(LocalDateTime.of(2026, 7, 31, 8, 15), result.userFacingTime)
        assertEquals(GlucoseConcentrationUnit.KILOGRAMS_PER_LITER, result.concentrationUnit)
        assertEquals(0.00123, result.concentration!!, 0.0000001)
        assertEquals(123.0, result.concentrationMgDl!!, 0.001)
        assertNull(result.concentrationMmolL)
        assertEquals(2, result.type)
        assertEquals(1, result.sampleLocation)
        assertEquals(0x0801, result.sensorStatus)
        assertTrue(result.contextFollows)
    }

    @Test
    fun parsesMolarConcentrationAndUnavailableSampleLocation() {
        val result = BluetoothGlucose.parse(
            byteArrayOf(
                0x06, // concentration + mol/L
                0x01, 0x00,
                0xEA.toByte(), 0x07, 0x07, 0x1F, 0x09, 0x0F, 0x00,
                0x37, 0xC0.toByte(), // 0.0055 mol/L = 5.5 mmol/L
                0xF9.toByte(), // interstitial fluid, location unavailable
            ),
        )!!

        assertEquals(GlucoseConcentrationUnit.MOLES_PER_LITER, result.concentrationUnit)
        assertEquals(5.5, result.concentrationMmolL!!, 0.001)
        assertNull(result.concentrationMgDl)
        assertEquals(9, result.type)
        assertEquals(15, result.sampleLocation)
    }

    @Test
    fun keepsIeeeNanAsAnUnavailableConcentration() {
        val result = BluetoothGlucose.parse(
            byteArrayOf(
                0x02,
                0x01, 0x00,
                0xEA.toByte(), 0x07, 0x07, 0x1F, 0x09, 0x0F, 0x00,
                0xFF.toByte(), 0x07, // IEEE-11073 SFLOAT NaN
                0x11,
            ),
        )!!

        assertEquals(GlucoseConcentrationUnit.KILOGRAMS_PER_LITER, result.concentrationUnit)
        assertNull(result.concentration)
        assertNull(result.concentrationMgDl)
    }

    @Test
    fun parsesEveryContextFieldAndDisplayUnits() {
        val result = BluetoothGlucose.parseContext(
            byteArrayOf(
                0xFF.toByte(),
                0x34, 0x12,
                0x00, // extended flags: all RFU and therefore zero
                0x01, 0x4B, 0xD0.toByte(), // breakfast, 0.075 kg = 75 g carbohydrate
                0x02, // after meal
                0x51, // self-test, no health issues
                0x10, 0x0E, 0x50, // 3600 s at 80%
                0x01, 0x0F, 0xC0.toByte(), // rapid insulin, 0.0015 L = 1.5 mL
                0x38, 0xF0.toByte(), // HbA1c 5.6%
            ),
        )!!

        assertEquals(0x1234, result.sequenceNumber)
        assertTrue(result.extendedFlagsPresent)
        assertEquals(1, result.carbohydrateId)
        assertEquals(75.0, result.carbohydrateGrams!!, 0.001)
        assertEquals(2, result.meal)
        assertEquals(1, result.tester)
        assertEquals(5, result.health)
        assertEquals(3600, result.exerciseDurationSeconds)
        assertEquals(80, result.exerciseIntensityPercent)
        assertFalse(result.exerciseDurationOverrun)
        assertEquals(1, result.medicationId)
        assertEquals(GlucoseMedicationUnit.LITERS, result.medicationUnit)
        assertEquals(1.5, result.medicationMilliliters!!, 0.001)
        assertNull(result.medicationMilligrams)
        assertEquals(5.6, result.hba1cPercent!!, 0.001)
    }

    @Test
    fun pairsContextOnlyWhenFollowsBitAndSequenceBothAgree() {
        val withContext = measurement(sequence = 7, contextFollows = true)
        val matchingContext = context(sequence = 7)

        val record = BluetoothGlucose.matchContext(withContext, matchingContext)
        assertNotNull(record)
        assertEquals(matchingContext, record!!.context)
        assertNull(BluetoothGlucose.matchContext(withContext, null))
        assertNull(BluetoothGlucose.matchContext(withContext, context(sequence = 8)))

        val withoutContext = measurement(sequence = 7, contextFollows = false)
        assertNotNull(BluetoothGlucose.matchContext(withoutContext, null))
        assertNull(BluetoothGlucose.matchContext(withoutContext, matchingContext))
    }

    @Test
    fun rejectsMalformedMeasurements() {
        val minimal = byteArrayOf(
            0x00,
            0x01, 0x00,
            0xEA.toByte(), 0x07, 0x07, 0x1F, 0x09, 0x0F, 0x00,
        )
        assertNotNull(BluetoothGlucose.parse(minimal))
        assertNull(BluetoothGlucose.parse(minimal + 0x00))
        assertNull(BluetoothGlucose.parse(minimal.copyOf(9)))
        assertNull(BluetoothGlucose.parse(minimal.copyOf().also { it[0] = 0x20 })) // RFU flag
        assertNull(BluetoothGlucose.parse(minimal.copyOf().also { it[5] = 0x00 })) // month zero

        val badType = minimal.copyOf().also { it[0] = 0x02 } +
            byteArrayOf(0x7B, 0xB0.toByte(), 0x10) // type zero is RFU
        assertNull(BluetoothGlucose.parse(badType))

        val badStatus = minimal.copyOf().also { it[0] = 0x08 } +
            byteArrayOf(0x00, 0x10) // status bits 12..15 are RFU
        assertNull(BluetoothGlucose.parse(badStatus))
    }

    @Test
    fun rejectsMalformedContexts() {
        assertNull(BluetoothGlucose.parseContext(byteArrayOf(0x00, 0x01, 0x00)))
        // Medication-unit bit is not a field and cannot stand alone.
        assertNull(BluetoothGlucose.parseContext(byteArrayOf(0x20, 0x01, 0x00)))
        // It is still a defined selector, not RFU: tolerate it when another context field exists.
        assertEquals(
            1,
            BluetoothGlucose.parseContext(byteArrayOf(0x22, 0x01, 0x00, 0x01))!!.meal,
        )
        // Extended flags exist in v1.0.1 but all eight bits are RFU.
        assertNull(BluetoothGlucose.parseContext(byteArrayOf(0x80.toByte(), 0x01, 0x00, 0x01)))
        // Meal zero is RFU.
        assertNull(BluetoothGlucose.parseContext(byteArrayOf(0x02, 0x01, 0x00, 0x00)))
        // Announced carbohydrate value is clipped.
        assertNull(BluetoothGlucose.parseContext(byteArrayOf(0x01, 0x01, 0x00, 0x01, 0x4B)))
        // No flag announces the trailing byte.
        assertNull(BluetoothGlucose.parseContext(byteArrayOf(0x02, 0x01, 0x00, 0x01, 0x00)))
    }

    @Test
    fun everyClippedMeasurementAndContextIsRejected() {
        val measurement = byteArrayOf(
            0x1B,
            0x34, 0x12,
            0xEA.toByte(), 0x07, 0x07, 0x1F, 0x09, 0x0F, 0x00,
            0xC4.toByte(), 0xFF.toByte(),
            0x7B, 0xB0.toByte(), 0x12,
            0x01, 0x08,
        )
        val context = byteArrayOf(
            0x4B,
            0x34, 0x12,
            0x01, 0x4B, 0xD0.toByte(),
            0x02,
            0x10, 0x0E, 0x50,
            0x38, 0xF0.toByte(),
        )
        for (size in 0 until measurement.size) {
            assertNull("measurement clipped to $size", BluetoothGlucose.parse(measurement.copyOf(size)))
        }
        for (size in 0 until context.size) {
            assertNull("context clipped to $size", BluetoothGlucose.parseContext(context.copyOf(size)))
        }
    }

    private fun measurement(sequence: Int, contextFollows: Boolean) = GlucoseMeasurement(
        sequenceNumber = sequence,
        baseTime = LocalDateTime.of(2026, 7, 31, 12, 0),
        contextFollows = contextFollows,
    )

    private fun context(sequence: Int) = GlucoseMeasurementContext(
        sequenceNumber = sequence,
        meal = 1,
    )
}
