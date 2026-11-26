package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class BodyCompositionServiceTest {
    @Test
    fun parsesEveryOptionalSiField() {
        val result = BluetoothBodyComposition.parse(
            byteArrayOf(
                0xFE.toByte(), 0x0F, // every optional field, SI, single packet
                0xED.toByte(), 0x00, // 23.7% body fat
                0xEA.toByte(), 0x07, 0x07, 0x1F, 0x0A, 0x14, 0x1E, // 2026-07-31 10:20:30
                0x03, // user
                0x64, 0x19, // 6500 kJ
                0xA7.toByte(), 0x01, // 42.3% muscle
                0x80.toByte(), 0x25, // 48.0 kg muscle
                0xB4.toByte(), 0x2D, // 58.5 kg fat-free mass
                0x5C, 0x2B, // 55.5 kg soft lean mass
                0xD0.toByte(), 0x20, // 42.0 kg body water
                0x03, 0x14, // 512.3 ohm
                0xB0.toByte(), 0x36, // 70.0 kg
                0xD6.toByte(), 0x06, // 175.0 cm
            ),
            arrivalMillis = LocalDateTime.of(2026, 7, 31, 10, 21).toInstant(ZoneOffset.UTC).toEpochMilli(),
            zoneId = ZoneOffset.UTC,
        )!!

        assertEquals(23.7, result.bodyFatPercent!!, 0.001)
        assertEquals(LocalDateTime.of(2026, 7, 31, 10, 20, 30), result.timestamp)
        assertEquals(true, result.deviceClockSet)
        assertEquals(3, result.userId)
        assertTrue(result.userIdPresent)
        assertEquals(6500, result.basalMetabolismKj)
        assertEquals(42.3, result.musclePercent!!, 0.001)
        assertEquals(48.0, result.muscleMassKg!!, 0.001)
        assertEquals(58.5, result.fatFreeMassKg!!, 0.001)
        assertEquals(55.5, result.softLeanMassKg!!, 0.001)
        assertEquals(42.0, result.bodyWaterMassKg!!, 0.001)
        assertEquals(512.3, result.impedanceOhms!!, 0.001)
        assertEquals(70.0, result.weightKg!!, 0.001)
        assertEquals(175.0, result.heightCm!!, 0.001)
        assertTrue(result.successful)
        assertFalse(result.imperialSource)
        assertFalse(result.multiplePacket)
    }

    @Test
    fun convertsEveryImperialMassAndHeightToSi() {
        val result = BluetoothBodyComposition.parse(
            byteArrayOf(
                0x21, 0x0C, // imperial + muscle mass + weight + height
                0xC9.toByte(), 0x00, // 20.1% body fat
                0x10, 0x27, // 100.00 lb muscle mass
                0x48, 0x3C, // 154.32 lb weight
                0xB1.toByte(), 0x02, // 68.9 inches
            ),
        )!!

        assertTrue(result.imperialSource)
        assertEquals(45.359237, result.muscleMassKg!!, 0.000001)
        assertEquals(70.0, result.weightKg!!, 0.01)
        assertEquals(175.006, result.heightCm!!, 0.001)
    }

    @Test
    fun preservesMetadataNeededToPairMultipartIndications() {
        val first = BluetoothBodyComposition.parse(
            byteArrayOf(
                0x06, 0x14, // timestamp + user + weight + multiple packet
                0xFA.toByte(), 0x00, // 25.0% body fat
                0xEA.toByte(), 0x07, 0x07, 0x1F, 0x0A, 0x14, 0x1E,
                0x04,
                0xB0.toByte(), 0x36, // 70.0 kg
            ),
        )!!
        val continuation = BluetoothBodyComposition.parse(
            byteArrayOf(
                0x00,
                0x1A, // impedance + height + multiple packet, no identity fields
                0xFA.toByte(),
                0x00,
                0x03,
                0x14, // 512.3 ohm
                0xD6.toByte(),
                0x06, // 175.0 cm
            ),
        )!!

        assertTrue(first.multiplePacket)
        assertTrue(continuation.multiplePacket)
        assertEquals(4, first.userId)
        assertEquals(70.0, first.weightKg!!, 0.001)
        assertNull(continuation.timestamp)
        assertFalse(continuation.userIdPresent)
        assertEquals(512.3, continuation.impedanceOhms!!, 0.001)
        assertEquals(175.0, continuation.heightCm!!, 0.001)
    }

    @Test
    fun acceptsDefinedUnsuccessfulResultWithTimestampAndUnknownUser() {
        val result = BluetoothBodyComposition.parse(
            byteArrayOf(
                0x06, 0x00,
                0xFF.toByte(), 0xFF.toByte(),
                0xEA.toByte(), 0x07, 0x07, 0x1F, 0x0A, 0x14, 0x1E,
                0xFF.toByte(),
            ),
            // Pinned: against the wall clock this device time falls out of the window in 2036.
            arrivalMillis = LocalDateTime.of(2026, 7, 31, 10, 21).toInstant(ZoneOffset.UTC).toEpochMilli(),
        )!!

        assertFalse(result.successful)
        assertNull(result.bodyFatPercent)
        assertTrue(result.userIdPresent)
        assertNull(result.userId)
        assertEquals(LocalDateTime.of(2026, 7, 31, 10, 20, 30), result.timestamp)
    }

    @Test
    fun rejectsMalformedAndNonConformantPackets() {
        assertNull(BluetoothBodyComposition.parse(byteArrayOf()))
        assertNull(BluetoothBodyComposition.parse(byteArrayOf(0x00, 0x00, 0x64)))
        // RFU flag bit 13.
        assertNull(BluetoothBodyComposition.parse(byteArrayOf(0x00, 0x20, 0x64, 0x00)))
        // Failed result is allowed to retain time/user, but not weight or another result field.
        assertNull(
            BluetoothBodyComposition.parse(
                byteArrayOf(0x00, 0x04, 0xFF.toByte(), 0xFF.toByte(), 0xB0.toByte(), 0x36),
            ),
        )
        // No flags announce this trailing octet.
        assertNull(BluetoothBodyComposition.parse(byteArrayOf(0x00, 0x00, 0x64, 0x00, 0x00)))
    }

    /**
     * 2000-01-01 00:00 is a valid GSS Date Time and the factory default of a clock nobody set. The
     * weighing is kept, the timestamp is dropped so the collector dates it on arrival, and the flag
     * says why; a scale that sent no time at all gets no verdict.
     */
    @Test
    fun aTimestampFromAnUnsetClockIsDroppedAndFlagged() {
        val arrival = LocalDateTime.of(2026, 9, 4, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val unset = BluetoothBodyComposition.parse(
            byteArrayOf(0x02, 0x00, 0x64, 0x00, 0xD0.toByte(), 0x07, 0x01, 0x01, 0x00, 0x00, 0x00),
            arrivalMillis = arrival,
            zoneId = ZoneOffset.UTC,
        )!!
        assertEquals(10.0, unset.bodyFatPercent!!, 0.001)
        assertNull(unset.timestamp)
        assertEquals(false, unset.deviceClockSet)

        val untimed = BluetoothBodyComposition.parse(byteArrayOf(0x00, 0x00, 0x64, 0x00))!!
        assertNull(untimed.timestamp)
        assertNull(untimed.deviceClockSet)
    }

    @Test
    fun keepsMeasurementWithoutTimestampWhenDeviceClockIsUnknown() {
        // GSS "not known" Date Time (year 0, or month/day 0) from a scale whose clock was never
        // set, and a year outside 1582..9999 that LocalDateTime.of would otherwise believe: the
        // weighing is kept and only the timestamp is dropped.
        val packets = listOf(
            byteArrayOf(0x02, 0x00, 0x64, 0x00, 0, 0, 0, 0, 0, 0, 0),
            byteArrayOf(0x02, 0x00, 0x64, 0x00, 0, 0, 1, 1, 0x0A, 0x14, 0x1E),
            byteArrayOf(0x02, 0x00, 0x64, 0x00, 0xFF.toByte(), 0xFF.toByte(), 1, 1, 0x0A, 0x14, 0x1E),
        )
        for (packet in packets) {
            val result = BluetoothBodyComposition.parse(packet)!!
            assertEquals(10.0, result.bodyFatPercent!!, 0.001)
            assertNull(result.timestamp)
        }
    }

    @Test
    fun everyClippedOptionalPacketIsRejected() {
        val complete = byteArrayOf(
            0x06, 0x0C,
            0xFA.toByte(), 0x00,
            0xEA.toByte(), 0x07, 0x07, 0x1F, 0x0A, 0x14, 0x1E,
            0x04,
            0xB0.toByte(), 0x36,
            0xD6.toByte(), 0x06,
        )
        for (size in 0 until complete.size) {
            assertNull(
                "body composition clipped to $size bytes",
                BluetoothBodyComposition.parse(complete.copyOf(size)),
            )
        }
    }
}
