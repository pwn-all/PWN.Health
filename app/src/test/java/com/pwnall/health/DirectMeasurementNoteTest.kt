package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DirectMeasurementNoteTest {
    /**
     * The identity is what a reading is traced by: a name two monitors of the same make share is
     * not provenance, so it must never be the only thing the saved row carries.
     */
    @Test
    fun everyReadingKeepsTheDeviceIdentityBesideWhateverTheDeviceCalledItself() {
        assertEquals(
            "Omron · BLE_5d04e9dd729e5b70",
            directMeasurementNote("Omron", "BLE_5d04e9dd729e5b70"),
        )
        assertEquals(
            "BLE_5d04e9dd729e5b70",
            directMeasurementNote(null, "BLE_5d04e9dd729e5b70"),
        )
    }

    /** An absent name leaves no separator behind it: no " · " with nothing on either side. */
    @Test
    fun anAbsentPartLeavesNoSeparatorBehind() {
        assertEquals("Bluetooth SIG FTMS", directMeasurementNote("Bluetooth SIG FTMS", "", null))
        assertEquals("", directMeasurementNote(null, "", "   "))
    }

    // A reading restored from Health Connect and the same reading sent again by the device are one
    // reading, though the restored row has no source and a payload of one figure.

    private val readAt = 1_754_000_000_000

    private fun measurement(
        payload: String,
        source: String?,
        at: Long = readAt,
        weightKg: Double? = null,
    ) = JournalEntry(
        day = "2026-08-01",
        occurredAt = at,
        kind = "MEASUREMENT",
        title = "Reading",
        calories = 0.0,
        measurementWeightKg = weightKg,
        measurementSource = source,
        measurementPayloadJson = payload,
    )

    @Test
    fun theMetersReadingIsTheRestoredRecordUnderAnotherName() {
        val restored = measurement("""{"schema":1,"concentrationMmolL":5.6}""", null)
        val meter = measurement(
            """{"schema":1,"profile":"Bluetooth SIG GLS","sequenceNumber":12,"concentrationMmolL":5.6}""",
            "BLUETOOTH_GLS",
            at = readAt + 400,
        )

        assertTrue(sameDirectReading(restored, meter))
        // More than a second apart, or another figure, is another reading.
        assertFalse(sameDirectReading(restored, meter.copy(occurredAt = readAt + 1_001)))
        assertFalse(
            sameDirectReading(restored, meter.copy(measurementPayloadJson = """{"concentrationMmolL":5.62}""")),
        )
    }

    @Test
    fun aMeterSpeakingMgDlMatchesTheRecordItsReadingWasPublishedAs() {
        // 101 mg/dL is 5.606 mmol/L, the figure the export wrote and the import read back.
        val restored = measurement("""{"concentrationMmolL":5.6061}""", null)
        val meter = measurement("""{"profile":"Bluetooth SIG GLS","concentrationMgDl":101}""", "BLUETOOTH_GLS")

        assertTrue(sameDirectReading(restored, meter))
    }

    @Test
    fun aCuffsPairIsMatchedWholeAndBothHalvesHaveToAgree() {
        val restored = measurement("""{"systolicMmhg":121.0,"diastolicMmhg":79.0}""", null)
        val cuff = measurement(
            """{"profile":"Bluetooth SIG BPS","systolicMmhg":121,"diastolicMmhg":79,"pulseRateBpm":64}""",
            "BLUETOOTH_BPS",
        )

        assertTrue(sameDirectReading(restored, cuff))
        val diastolicOff = cuff.copy(measurementPayloadJson = """{"systolicMmhg":121,"diastolicMmhg":80}""")
        assertFalse(sameDirectReading(restored, diastolicOff))
    }

    @Test
    fun eachFigureIsMatchedToThePrecisionItIsStoredTo() {
        val thermometer = measurement("""{"temperatureCelsius":37.62,"type":"ARMPIT"}""", "BLUETOOTH_HTS")
        assertTrue(sameDirectReading(measurement("""{"temperatureCelsius":37.6}""", null), thermometer))
        assertFalse(sameDirectReading(measurement("""{"temperatureCelsius":37.7}""", null), thermometer))

        val oximeter = measurement("""{"spo2Percent":97,"pulseRateBpm":70}""", "BLUETOOTH_PLXS")
        assertTrue(sameDirectReading(measurement("""{"spo2Percent":97.0}""", null), oximeter))
        assertFalse(sameDirectReading(measurement("""{"spo2Percent":96.0}""", null), oximeter))

        val scale = measurement("""{"profile":"Bluetooth SIG BCS","weightKg":81.4}""", "BLUETOOTH_BCS", weightKg = 81.4)
        assertTrue(sameDirectReading(measurement("{}", null, weightKg = 81.404), scale))
        assertFalse(sameDirectReading(measurement("{}", null, weightKg = 81.42), scale))
    }

    /** Nothing in common but the second: another kind of row, or a reading with no figure at all. */
    @Test
    fun aRowOfAnotherKindOrAReadingWithNoFigureIsNobodysTwin() {
        val standing = measurement("""{"spo2Percent":97}""", null)
        val reading = measurement("""{"spo2Percent":97}""", "BLUETOOTH_PLXS")

        assertFalse(sameDirectReading(standing.copy(kind = "ACTIVITY"), reading))
        assertFalse(sameDirectReading(standing, reading.copy(measurementPayloadJson = """{"schema":1}""")))
    }

    /**
     * A meter running seven minutes fast. Synced a minute after the test, its record is dated ahead
     * of the phone and not believed; synced again in the evening, it is. The verdict is written
     * into the payload, so the one record comes back under a second payload and is still one record.
     */
    @Test
    fun aMeterRecordIsOneRecordWhicheverWayItsClockWasJudged() {
        val bytes = byteArrayOf(
            0x06, // concentration + mol/L
            0x07, 0x00,
            0xEA.toByte(), 0x07, 0x09, 0x04, 0x0A, 0x07, 0x00, // 2026-09-04 10:07:00 by the meter
            0x37, 0xC0.toByte(), // 5.5 mmol/L
            0x11,
        )
        val aMinuteAfter = LocalDateTime.of(2026, 9, 4, 10, 1).toInstant(ZoneOffset.UTC).toEpochMilli()
        val thatEvening = LocalDateTime.of(2026, 9, 4, 20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val disbelieved = GlucoseRecord(BluetoothGlucose.parse(bytes, aMinuteAfter, ZoneOffset.UTC)!!, null)
        val believed = GlucoseRecord(BluetoothGlucose.parse(bytes, thatEvening, ZoneOffset.UTC)!!, null)
        assertFalse(disbelieved.measurement.deviceClockSet)
        assertTrue(believed.measurement.deviceClockSet)

        val saved = disbelieved.journalPayload(clockBelieved = false)!!
        assertNotEquals(saved, believed.journalPayload(clockBelieved = true))
        assertEquals(saved, believed.journalPayload(clockBelieved = false))
        // The other way round, for a record first saved while its clock was believed.
        assertEquals(believed.journalPayload(clockBelieved = true), disbelieved.journalPayload(clockBelieved = true))
    }

    /**
     * The payload is a meter record's identity across syncs, and every sync asks for the whole
     * memory: a key added to it, or one every record carries, makes each record the meter still
     * holds look new, and the next sync saves all of them again. These are the keys it is saved
     * under; changing them is a decision about every meter's next sync, not a detail.
     */
    @Test
    fun aGlucosePayloadCarriesTheKeysEveryEarlierSyncSavedItUnder() {
        val record = GlucoseRecord(
            GlucoseMeasurement(
                sequenceNumber = 7,
                baseTime = LocalDateTime.of(2026, 9, 4, 10, 7),
                concentration = 0.0055,
                concentrationUnit = GlucoseConcentrationUnit.MOLES_PER_LITER,
                type = 1,
                sampleLocation = 1,
            ),
            context = null,
        )
        val keys = setOf(
            "schema",
            "profile",
            "sequenceNumber",
            "baseTime",
            "concentration",
            "concentrationUnit",
            "concentrationMmolL",
            "type",
            "sampleLocation",
        )

        val believed = JSONObject(record.journalPayload(clockBelieved = true)!!)
        assertEquals(keys, believed.keyNames())
        assertEquals("Bluetooth SIG GLS", believed.getString("profile"))
        assertEquals("2026-09-04T10:07", believed.getString("baseTime"))
        assertEquals("MOLES_PER_LITER", believed.getString("concentrationUnit"))

        // Only a disbelieved clock leaves a mark, and only that one key.
        val disbelieved = JSONObject(record.journalPayload(clockBelieved = false)!!)
        assertEquals(keys + "deviceClockSet", disbelieved.keyNames())
        assertFalse(disbelieved.getBoolean("deviceClockSet"))

        // A context carries only what the meter sent in it.
        val context = GlucoseMeasurementContext(sequenceNumber = 7, meal = 1)
        val withContext = JSONObject(record.copy(context = context).journalPayload(clockBelieved = true)!!)
        assertEquals(keys + "context", withContext.keyNames())
        assertEquals(
            setOf("sequenceNumber", "extendedFlagsPresent", "meal"),
            withContext.getJSONObject("context").keyNames(),
        )
    }
}
