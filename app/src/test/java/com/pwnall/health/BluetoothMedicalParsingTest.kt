package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class BluetoothMedicalParsingTest {
    private val arrival = LocalDateTime.of(2026, 9, 4, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /**
     * A cuff fresh out of the box counts from 2000-01-01 with a valid year, month and day, so the
     * calendar check alone would believe it and file the reading a quarter of a century back.
     */
    @Test
    fun aClockNobodySetIsNotBelieved() {
        val factoryDefault = LocalDateTime.of(2000, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertFalse(isPlausibleDeviceTime(factoryDefault, arrival))
        assertFalse(LocalDateTime.of(2000, 1, 1, 0, 0).isPlausibleDeviceTime(arrival, ZoneOffset.UTC))
    }

    @Test
    fun theWindowReachesTenYearsBackAndFiveMinutesAhead() {
        val year = 365L * 24 * 60 * 60 * 1_000
        val minute = 60_000L
        assertTrue(isPlausibleDeviceTime(arrival, arrival))
        assertTrue(isPlausibleDeviceTime(arrival - 9 * year, arrival))
        assertFalse(isPlausibleDeviceTime(arrival - 11 * year, arrival))
        assertTrue(isPlausibleDeviceTime(arrival + 4 * minute, arrival))
        assertFalse(isPlausibleDeviceTime(arrival + 6 * minute, arrival))
    }

    @Test
    fun theReaderReturnsNullForAnImplausibleDateAndTheInstantForAPlausibleOne() {
        val unset = BluetoothMedicalPacket(bytes(0xD0, 0x07, 0x01, 0x01, 0x00, 0x00, 0x00)) // 2000-01-01
        assertNull(unset.deviceDateTimeEpochMillis(arrival, ZoneOffset.UTC))

        val today = BluetoothMedicalPacket(bytes(0xEA, 0x07, 0x09, 0x04, 0x0B, 0x3B, 0x00)) // 2026-09-04 11:59
        assertEquals(arrival - 60_000L, today.deviceDateTimeEpochMillis(arrival, ZoneOffset.UTC))
    }

    /** The profiles that wrap the reader all fall back the same way and say so in the flag. */
    @Test
    fun everyClinicalProfileDatesAnUnsetClockOnArrivalAndFlagsIt() {
        val cuff = BluetoothBloodPressure.parse(
            bytes(0x02, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00, 0xD0, 0x07, 0x01, 0x01, 0x00, 0x00, 0x00),
            fallbackOccurredAt = arrival,
            zoneId = ZoneOffset.UTC,
        )!!
        assertEquals(arrival, cuff.occurredAt)
        assertEquals(false, cuff.deviceClockSet)

        val thermometer = BluetoothHealthThermometer.parse(
            bytes(0x02, 0x6C, 0x01, 0x00, 0xFF, 0xD0, 0x07, 0x01, 0x01, 0x00, 0x00, 0x00),
            fallbackOccurredAt = arrival,
            zoneId = ZoneOffset.UTC,
        )!!
        assertEquals(arrival, thermometer.occurredAt)
        assertEquals(false, thermometer.deviceClockSet)

        // The oximeter's own flag vouches for its clock; the date says otherwise and the date wins.
        val oximeter = BluetoothPulseOximeter.parseSpotCheck(
            bytes(0x01, 0xD4, 0xF3, 0x48, 0x00, 0xD0, 0x07, 0x01, 0x01, 0x00, 0x00, 0x00),
            fallbackOccurredAt = arrival,
            zoneId = ZoneOffset.UTC,
        )!!
        assertEquals(arrival, oximeter.occurredAt)
        assertEquals(false, oximeter.deviceClockSet)
    }

    @Test
    fun aReadingWithoutATimestampCarriesNoVerdictOnTheClock() {
        val cuff = BluetoothBloodPressure.parse(
            bytes(0x00, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00),
            fallbackOccurredAt = arrival,
            zoneId = ZoneOffset.UTC,
        )!!
        assertNull(cuff.deviceClockSet)
        assertEquals(arrival, cuff.occurredAt)
    }

    // --- variability over a run of beats -----------------------------------------------------------

    /**
     * RMSSD is the root mean square of the differences between successive intervals, and this is
     * the arithmetic written out: differences of 20, 20, 20 and 20 ms, each squared, averaged, and
     * rooted, which is 20.
     */
    @Test
    fun theVariabilityIsTheRootMeanSquareOfSuccessiveDifferences() {
        val intervals = List(40) { index -> 800 + if (index % 2 == 0) 0 else 20 }

        assertEquals(20.0, rmssdMs(intervals)!!, 0.001)
    }

    /** Under half a minute of beats there is no answer, because the answer would be about the sample. */
    @Test
    fun tooFewBeatsIsNoFigureRatherThanAnUnstableOne() {
        assertNull(rmssdMs(List(29) { 800 }))
        assertNull(rmssdMs(emptyList()))
        assertEquals(0.0, rmssdMs(List(30) { 800 })!!, 0.001)
    }

    /**
     * A dropped beat is not a difference.
     *
     * A strap that misses one detection reports an interval twice as long, and counting the two
     * jumps either side of it would put a figure in the record that says more about the strap than
     * about the heart. The interval is discarded and so is the gap it leaves.
     */
    @Test
    fun anArtefactIntervalIsDiscardedAndSoIsTheGapItLeaves() {
        val steady = List(40) { 800 }
        val withArtefact = steady.toMutableList().apply { set(20, 2_400) }

        assertEquals(0.0, rmssdMs(withArtefact)!!, 0.001)
        assertNull(rmssdMs(List(20) { 800 } + List(20) { 2_400 }))
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
