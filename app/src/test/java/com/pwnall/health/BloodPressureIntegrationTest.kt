package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class BloodPressureIntegrationTest {
    @Test
    fun decodesMandatoryMmhgValues() {
        val result = BluetoothBloodPressure.parse(
            bytes(0x00, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00),
            fallbackOccurredAt = 1234L,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(120.0, result.systolic!!, 0.001)
        assertEquals(80.0, result.diastolic!!, 0.001)
        assertEquals(93.0, result.meanArterialPressure!!, 0.001)
        assertEquals(BloodPressureUnit.MMHG, result.unit)
        assertEquals(120.0, result.systolicMmhg!!, 0.001)
        assertEquals(1234L, result.occurredAt)
        assertNull(result.pulseRateBpm)
    }

    @Test
    fun decodesEveryOptionalField() {
        val expectedTime = LocalDateTime.of(2026, 7, 31, 10, 20, 30)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        val result = BluetoothBloodPressure.parse(
            bytes(
                0x1E, // timestamp, pulse, user and status; mmHg
                0x78, 0x00, // systolic 120
                0x50, 0x00, // diastolic 80
                0x5D, 0x00, // MAP 93
                0xEA, 0x07, 0x07, 0x1F, 0x0A, 0x14, 0x1E, // 2026-07-31 10:20:30
                0xD5, 0xF2, // 72.5 bpm
                0x03, // user 3
                0x2D, 0x00, // movement, irregular pulse, pulse above range, bad position
            ),
            fallbackOccurredAt = expectedTime + 60_000L,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(expectedTime, result.occurredAt)
        assertEquals(true, result.deviceClockSet)
        assertEquals(72.5, result.pulseRateBpm!!, 0.001)
        assertEquals(3, result.userId)
        assertEquals(0x002D, result.measurementStatus)
        // The status travels with the reading, in words.
        assertEquals(
            listOf("bodyMovement", "irregularPulse", "pulseRateAboveLimit", "improperPosition"),
            result.qualityFlags,
        )
    }

    @Test
    fun qualityFlagsNameEachSoftStatusBitAndNothingElse() {
        fun flags(status: Int?) = BluetoothBloodPressureMeasurementFixture.withStatus(status).qualityFlags
        assertEquals(emptyList<String>(), flags(null))
        assertEquals(emptyList<String>(), flags(0x0000))
        assertEquals(listOf("cuffTooLoose"), flags(0x0002))
        assertEquals(listOf("pulseRateBelowLimit"), flags(0x0010))
        assertEquals(
            listOf("bodyMovement", "cuffTooLoose", "irregularPulse", "pulseRateBelowLimit", "improperPosition"),
            flags(0x0037),
        )
    }

    private object BluetoothBloodPressureMeasurementFixture {
        fun withStatus(status: Int?) = BloodPressureMeasurement(
            systolic = 120.0,
            diastolic = 80.0,
            meanArterialPressure = 93.0,
            unit = BloodPressureUnit.MMHG,
            pulseRateBpm = 72.0,
            userId = null,
            measurementStatus = status,
            occurredAt = 1L,
        )
    }

    @Test
    fun preservesKpaAndOffersMmhgConversion() {
        val result = BluetoothBloodPressure.parse(
            bytes(
                0x01,
                0xA0,
                0xF0, // 16.0 kPa
                0x6B,
                0xF0, // 10.7 kPa
                0x7D,
                0xF0, // 12.5 kPa
            ),
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(BloodPressureUnit.KPA, result.unit)
        assertEquals(16.0, result.systolic!!, 0.001)
        assertEquals(120.01, result.systolicMmhg!!, 0.01)
        assertEquals(80.26, result.diastolicMmhg!!, 0.01)
    }

    @Test
    fun ieeeSpecialValuesBecomeUnavailable() {
        val result = BluetoothBloodPressure.parse(
            bytes(
                0x00,
                0xFE,
                0x07, // +infinity
                0xFF,
                0x07, // NaN
                0x00,
                0x08, // NRes
            ),
            zoneId = ZoneOffset.UTC,
        )!!

        assertNull(result.systolic)
        assertNull(result.diastolic)
        assertNull(result.meanArterialPressure)
    }

    @Test
    fun invalidDeviceTimeFallsBackWithoutLosingPressure() {
        val fallback = 1_600_000_000_000L
        val result = BluetoothBloodPressure.parse(
            bytes(
                0x02,
                0x78, 0x00, 0x50, 0x00, 0x5D, 0x00,
                0xEA, 0x07, 0x00, 0x1F, 0x0A, 0x14, 0x1E, // month zero
            ),
            fallbackOccurredAt = fallback,
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(fallback, result.occurredAt)
        assertEquals(120.0, result.systolic!!, 0.001)
    }

    @Test
    fun unknownUserIsNotUser255() {
        val result = BluetoothBloodPressure.parse(
            bytes(0x08, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00, 0xFF),
            zoneId = ZoneOffset.UTC,
        )!!

        assertNull(result.userId)
    }

    @Test
    fun rejectsRfuReservedNegativeAndOverlongRecords() {
        assertNull(
            BluetoothBloodPressure.parse(
                bytes(0x20, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00),
                zoneId = ZoneOffset.UTC,
            ),
        )
        // Pulse-rate-range status value 0b11 is reserved.
        assertNull(
            BluetoothBloodPressure.parse(
                bytes(0x10, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00, 0x18, 0x00),
                zoneId = ZoneOffset.UTC,
            ),
        )
        // Measurement Status bits 6..15 are RFU.
        assertNull(
            BluetoothBloodPressure.parse(
                bytes(0x10, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00, 0x40, 0x00),
                zoneId = ZoneOffset.UTC,
            ),
        )
        // Ordinary -1 is not one of the defined unavailable values and cannot be a pressure.
        assertNull(
            BluetoothBloodPressure.parse(
                bytes(0x00, 0xFF, 0x0F, 0x50, 0x00, 0x5D, 0x00),
                zoneId = ZoneOffset.UTC,
            ),
        )
        assertNull(
            BluetoothBloodPressure.parse(
                bytes(0x00, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00, 0x00),
                zoneId = ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun everyClippedRecordIsRejected() {
        val full = bytes(
            0x1E,
            0x78, 0x00, 0x50, 0x00, 0x5D, 0x00,
            0xEA, 0x07, 0x07, 0x1F, 0x0A, 0x14, 0x1E,
            0xD5, 0xF2, 0x03, 0x2D, 0x00,
        )
        for (size in 0 until full.size) {
            assertNull(
                "clipped to $size bytes",
                BluetoothBloodPressure.parse(full.copyOf(size), zoneId = ZoneOffset.UTC),
            )
        }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
