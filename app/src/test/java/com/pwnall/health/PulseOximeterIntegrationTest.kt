package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class PulseOximeterIntegrationTest {
    private val fullSpotCheck = bytes(
        0x0F, // timestamp, measurement status, device status, PAI
        0xD4, 0xF3, // SpO2 98.0%
        0x48, 0x00, // pulse 72 bpm
        0xEA, 0x07, 0x07, 0x1F, 0x0A, 0x14, 0x1E,
        0x80, 0x01, // validated + fully qualified
        0x20, 0x20, 0x00, // low perfusion + sensor displaced
        0x37, 0xF0, // PAI 5.5%
    )

    @Test
    fun decodesSpotCheckWithEveryOptionalField() {
        val expectedTime = LocalDateTime.of(2026, 7, 31, 10, 20, 30)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        val result = BluetoothPulseOximeter.parse(
            BluetoothPulseOximeter.spotCheckMeasurementUuid,
            fullSpotCheck,
            fallbackOccurredAt = expectedTime + 60_000L,
            zoneId = ZoneOffset.UTC,
        )!!

        assertFalse(result.flaggedInvalid)
        assertEquals(98.0, result.spo2Percent!!, 0.001)
        assertEquals(72.0, result.pulseRateBpm!!, 0.001)
        assertEquals(5.5, result.pulseAmplitudeIndexPercent!!, 0.001)
        assertEquals(0x0180, result.measurementStatus)
        assertEquals(0x002020, result.deviceAndSensorStatus)
        assertEquals(expectedTime, result.occurredAt)
        assertTrue(result.isSpotCheck)
        assertEquals(true, result.deviceClockSet)
    }

    @Test
    fun decodesContinuousNormalFastAndSlowMetrics() {
        val result = BluetoothPulseOximeter.parse(
            BluetoothPulseOximeter.continuousMeasurementUuid,
            bytes(
                0x1F,
                0xCA, 0xF3, 0x46, 0x00, // normal: 97.0%, 70 bpm
                0xB6, 0xF3, 0x48, 0x00, // fast: 95.0%, 72 bpm
                0xD4, 0xF3, 0x44, 0x00, // slow: 98.0%, 68 bpm
                0x20, 0x00, // measurement ongoing
                0x40, 0x00, 0x00, // erratic signal
                0x20, 0xF0, // PAI 3.2%
            ),
            fallbackOccurredAt = 4444L,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(97.0, result.spo2Percent!!, 0.001)
        assertEquals(70.0, result.pulseRateBpm!!, 0.001)
        assertEquals(95.0, result.fastSpo2Percent!!, 0.001)
        assertEquals(72.0, result.fastPulseRateBpm!!, 0.001)
        assertEquals(98.0, result.slowSpo2Percent!!, 0.001)
        assertEquals(68.0, result.slowPulseRateBpm!!, 0.001)
        assertEquals(3.2, result.pulseAmplitudeIndexPercent!!, 0.001)
        assertEquals(4444L, result.occurredAt)
        assertTrue(!result.isSpotCheck)
        assertNull(result.deviceClockSet)
    }

    /**
     * PLX Measurement Status bit 13 is "measurement unavailable" and bit 15 "invalid measurement";
     * Device and Sensor Status bits 11 and 15 put the sensor off the person or off the device. None
     * may reach the card with a live Save button under the number the device has disowned.
     */
    @Test
    fun theDeviceOwnVerdictBlocksTheReading() {
        fun reading(measurementStatus: Int? = null, deviceStatus: Int? = null) = PulseOximeterMeasurement(
            spo2Percent = 97.0,
            pulseRateBpm = 70.0,
            measurementStatus = measurementStatus,
            deviceAndSensorStatus = deviceStatus,
            occurredAt = 1L,
            isSpotCheck = true,
        )
        assertFalse(reading().flaggedInvalid)
        assertFalse(reading(measurementStatus = 0x0180, deviceStatus = 0x002020).flaggedInvalid)
        assertTrue(reading(measurementStatus = 1 shl 13).flaggedInvalid)
        assertTrue(reading(measurementStatus = 1 shl 15).flaggedInvalid)
        assertTrue(reading(deviceStatus = 1 shl 11).flaggedInvalid)
        assertTrue(reading(deviceStatus = 1 shl 15).flaggedInvalid)
    }

    @Test
    fun clockNotSetMakesEvenAPresentTimestampUntrusted() {
        val fallback = 7777L
        val result = BluetoothPulseOximeter.parseSpotCheck(
            bytes(
                0x11, // timestamp present, device clock not set
                0xD4, 0xF3, 0x48, 0x00,
                0xEA, 0x07, 0x07, 0x1F, 0x0A, 0x14, 0x1E,
            ),
            fallbackOccurredAt = fallback,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(fallback, result.occurredAt)
        assertEquals(false, result.deviceClockSet)
    }

    @Test
    fun specialValuesAreUnavailableNotHugeMeasurements() {
        val result = BluetoothPulseOximeter.parseContinuous(
            bytes(0x00, 0xFF, 0x07, 0x02, 0x08), // NaN and -infinity
            fallbackOccurredAt = 1L,
        )!!

        assertNull(result.spo2Percent)
        assertNull(result.pulseRateBpm)
    }

    @Test
    fun rejectsRfuStatusesAndImpossiblePercentages() {
        // Flags bits 5..7 are RFU.
        assertNull(BluetoothPulseOximeter.parseSpotCheck(bytes(0x20, 0xD4, 0xF3, 0x48, 0x00)))
        // Measurement Status bits 0..4 are RFU.
        assertNull(
            BluetoothPulseOximeter.parseSpotCheck(
                bytes(0x02, 0xD4, 0xF3, 0x48, 0x00, 0x01, 0x00),
            ),
        )
        // Device and Sensor Status bits 16..23 are RFU.
        assertNull(
            BluetoothPulseOximeter.parseSpotCheck(
                bytes(0x04, 0xD4, 0xF3, 0x48, 0x00, 0x00, 0x00, 0x01),
            ),
        )
        // SpO2 is a percentage; 101 cannot be represented as a valid observation.
        assertNull(BluetoothPulseOximeter.parseSpotCheck(bytes(0x00, 0x65, 0x00, 0x48, 0x00)))
    }

    @Test
    fun rejectsUnknownCharacteristicAndWrongLengths() {
        assertNull(
            BluetoothPulseOximeter.parse(
                UUID.randomUUID(),
                bytes(0x00, 0xD4, 0xF3, 0x48, 0x00),
            ),
        )
        assertNull(BluetoothPulseOximeter.parseSpotCheck(bytes(0x00, 0xD4, 0xF3, 0x48)))
        assertNull(BluetoothPulseOximeter.parseSpotCheck(bytes(0x00, 0xD4, 0xF3, 0x48, 0x00, 0x00)))
    }

    @Test
    fun everyClippedSpotCheckRecordIsRejected() {
        for (size in 0 until fullSpotCheck.size) {
            assertNull(
                "clipped to $size bytes",
                BluetoothPulseOximeter.parseSpotCheck(
                    fullSpotCheck.copyOf(size),
                    zoneId = ZoneOffset.UTC,
                ),
            )
        }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
