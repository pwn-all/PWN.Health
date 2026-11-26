package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessMachineIntegrationTest {
    private val indoorBikePacket = byteArrayOf(
        0x74, 0x0B, // cadence, distance, resistance, power, energy, HR, elapsed
        0xE6.toByte(), 0x09, // 25.34 km/h
        0xB4.toByte(), 0x00, // 90 rpm
        0x39, 0x30, 0x00, // 12,345 m
        0x4B, 0x00, // resistance level 75, sent as the older sint16
        0xF5.toByte(), 0x00, // 245 W
        0x41, 0x01, 0x58, 0x02, 0x0A, // 321 kcal
        0x94.toByte(), // 148 bpm
        0x84.toByte(), 0x03, // 900 s
    )

    private val treadmillPacket = byteArrayOf(
        0x8C.toByte(), 0x05, // distance, incline, energy, HR, elapsed
        0xE8.toByte(), 0x03, // 10.00 km/h
        0xD0.toByte(), 0x07, 0x00, // 2,000 m
        0x19, 0x00, 0x00, 0x00, // 2.5% incline, 0 degree ramp
        0x96.toByte(), 0x00, 0xF4.toByte(), 0x01, 0x08, // 150 kcal
        0x8C.toByte(), // 140 bpm
        0x58, 0x02, // 600 s
    )

    private val rowerPacket = byteArrayOf(
        0x2C, 0x0B, // distance, pace, power, energy, HR, elapsed
        0x3C, 0xF4.toByte(), 0x01, // 30 spm, 500 strokes
        0xE8.toByte(), 0x03, 0x00, // 1,000 m
        0x78, 0x00, // 120 s / 500 m
        0xC8.toByte(), 0x00, // 200 W
        0x64, 0x00, 0x90.toByte(), 0x01, 0x07, // 100 kcal
        0x96.toByte(), // 150 bpm
        0x2C, 0x01, // 300 s
    )

    /**
     * At a 23-octet MTU a treadmill splits its record: speed, distance and incline first, then a
     * frame with More Data set carrying energy, heart rate and elapsed time and no speed. Neither half
     * replaces the other on screen, or the finished workout would take its calories from whichever
     * came last.
     */
    @Test
    fun aSplitTreadmillRecordIsOneMeasurement() {
        val accumulator = FitnessMachineAccumulator()
        val primary = byteArrayOf(
            0x0C, 0x00, // distance, incline; speed present
            0xE8.toByte(), 0x03, // 10.00 km/h
            0xD0.toByte(), 0x07, 0x00, // 2,000 m
            0x19, 0x00, 0x00, 0x00, // 2.5% incline, 0 degree ramp
        )
        val continuation = byteArrayOf(
            0x81.toByte(), 0x05, // More Data; energy, HR, elapsed
            0x96.toByte(), 0x00, 0xF4.toByte(), 0x01, 0x08, // 150 kcal
            0x8C.toByte(), // 140 bpm
            0x58, 0x02, // 600 s
        )
        val second = BluetoothFitnessMachine.parse(BluetoothFitnessMachine.treadmillDataUuid, continuation)!!
        assertTrue(second.moreData)
        assertNull(second.speedKph)

        val first = accumulator.update(
            BluetoothFitnessMachine.parse(BluetoothFitnessMachine.treadmillDataUuid, primary)!!,
        )
        assertEquals(10.0, first.speedKph!!, 0.001)
        assertNull(first.totalEnergyKcal)

        val merged = accumulator.update(second)
        assertFalse(merged.moreData)
        assertEquals(FitnessMachineType.TREADMILL, merged.type)
        assertEquals(10.0, merged.speedKph!!, 0.001)
        assertEquals(2_000, merged.totalDistanceMeters)
        assertEquals(2.5, merged.inclinationPercent!!, 0.001)
        assertEquals(150, merged.totalEnergyKcal)
        assertEquals(140, merged.heartRateBpm)
        assertEquals(600, merged.elapsedSeconds)

        // The next first half keeps what only the continuation carries, so nothing blinks off,
        // and the next continuation's newer values win.
        val laterPrimary = primary.copyOf().also {
            it[4] = 0x02 // 2,050 m
            it[5] = 0x08
        }
        val carried = accumulator.update(
            BluetoothFitnessMachine.parse(BluetoothFitnessMachine.treadmillDataUuid, laterPrimary)!!,
        )
        assertEquals(2_050, carried.totalDistanceMeters)
        assertEquals(150, carried.totalEnergyKcal)
        assertEquals(600, carried.elapsedSeconds)

        val laterContinuation = continuation.copyOf().also {
            it[8] = 0x62 // 610 s
            it[9] = 0x02
        }
        val refreshed = accumulator.update(
            BluetoothFitnessMachine.parse(BluetoothFitnessMachine.treadmillDataUuid, laterContinuation)!!,
        )
        assertEquals(2_050, refreshed.totalDistanceMeters)
        assertEquals(610, refreshed.elapsedSeconds)
    }

    @Test
    fun parsesIndoorBikeMetrics() {
        val result = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.indoorBikeDataUuid,
            indoorBikePacket,
        )!!

        assertEquals(FitnessMachineType.INDOOR_BIKE, result.type)
        assertEquals(25.34, result.speedKph!!, 0.001)
        assertEquals(90.0, result.cadenceRpm!!, 0.001)
        assertEquals(12_345, result.totalDistanceMeters)
        // Unitless in both revisions of the spec: no revision defines a multiplier.
        assertEquals(75.0, result.resistanceLevel!!, 0.001)
        assertEquals(245, result.powerWatts)
        assertEquals(321, result.totalEnergyKcal)
        assertEquals(148, result.heartRateBpm)
        assertEquals(900, result.elapsedSeconds)
    }

    @Test
    fun parsesTreadmillMetrics() {
        val result = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.treadmillDataUuid,
            treadmillPacket,
        )!!

        assertEquals(FitnessMachineType.TREADMILL, result.type)
        assertEquals(10.0, result.speedKph!!, 0.001)
        assertEquals(2_000, result.totalDistanceMeters)
        assertEquals(2.5, result.inclinationPercent!!, 0.001)
        assertEquals(150, result.totalEnergyKcal)
        assertEquals(140, result.heartRateBpm)
        assertEquals(600, result.elapsedSeconds)
    }

    @Test
    fun parsesRowerMetrics() {
        val result = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.rowerDataUuid,
            rowerPacket,
        )!!

        assertEquals(FitnessMachineType.ROWER, result.type)
        assertEquals(30.0, result.strokeRateSpm!!, 0.001)
        assertEquals(500, result.strokeCount)
        assertEquals(1_000, result.totalDistanceMeters)
        assertEquals(200, result.powerWatts)
        assertEquals(100, result.totalEnergyKcal)
        assertEquals(150, result.heartRateBpm)
        assertEquals(300, result.elapsedSeconds)
    }

    /**
     * In FTMS the first flag bit means "instantaneous value is *not* present" — inverted compared
     * with every other bit in the frame.
     */
    @Test
    fun firstFlagBitMeansTheInstantaneousValueIsAbsent() {
        val bike = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.indoorBikeDataUuid,
            byteArrayOf(0x01, 0x00),
        )!!
        assertNull(bike.speedKph)

        val treadmill = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.treadmillDataUuid,
            byteArrayOf(0x01, 0x00),
        )!!
        assertNull(treadmill.speedKph)

        val rower = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.rowerDataUuid,
            byteArrayOf(0x01, 0x00),
        )!!
        assertNull(rower.strokeRateSpm)
        assertNull(rower.strokeCount)
    }

    @Test
    fun rejectsUnknownAndTruncatedPackets() {
        assertNull(BluetoothFitnessMachine.parse(BluetoothFitnessMachine.controlPointUuid, byteArrayOf()))
        assertNull(
            BluetoothFitnessMachine.parse(
                BluetoothFitnessMachine.indoorBikeDataUuid,
                byteArrayOf(0x40, 0x00),
            ),
        )
    }

    /**
     * A machine that drops the connection mid-notification must produce a null, never an exception
     * reaching the GATT callback.
     *
     * One truncation is genuinely undecidable: a 21-byte bike record with a 16-bit resistance level
     * clipped by one byte is byte-for-byte a valid 20-byte record with an 8-bit one. Since the two
     * spec revisions differ in exactly that width, no parser can tell them apart, and pretending
     * otherwise would mean rejecting conformant hardware. It is excluded rather than hidden.
     */
    @Test
    fun everyClippedPacketIsRejected() {
        val undecidable = mapOf(BluetoothFitnessMachine.indoorBikeDataUuid to indoorBikePacket.size - 1)
        mapOf(
            BluetoothFitnessMachine.indoorBikeDataUuid to indoorBikePacket,
            BluetoothFitnessMachine.treadmillDataUuid to treadmillPacket,
            BluetoothFitnessMachine.rowerDataUuid to rowerPacket,
        ).forEach { (uuid, packet) ->
            for (size in 0 until packet.size) {
                if (undecidable[uuid] == size) continue
                assertNull(
                    "$uuid clipped to $size bytes must not parse",
                    BluetoothFitnessMachine.parse(uuid, packet.copyOf(size)),
                )
            }
        }
    }

    /**
     * The current supplement makes Resistance Level a single unsigned octet. Read as the older
     * sint16, that layout would shift power, energy, heart rate and elapsed time one byte to the left
     * and hand back a confidently wrong workout; the record length tells the two apart.
     */
    @Test
    fun readsTheSingleOctetResistanceLayoutToo() {
        val result = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.indoorBikeDataUuid,
            byteArrayOf(
                0x74, 0x0B,
                0xE6.toByte(), 0x09, // 25.34 km/h
                0xB4.toByte(), 0x00, // 90 rpm
                0x39, 0x30, 0x00, // 12,345 m
                0x4B, // resistance level 75, one octet
                0xF5.toByte(), 0x00, // 245 W
                0x41, 0x01, 0x58, 0x02, 0x0A, // 321 kcal
                0x94.toByte(), // 148 bpm
                0x84.toByte(), 0x03, // 900 s
            ),
        )!!

        assertEquals(75.0, result.resistanceLevel!!, 0.001)
        assertEquals(245, result.powerWatts)
        assertEquals(321, result.totalEnergyKcal)
        assertEquals(148, result.heartRateBpm)
        assertEquals(900, result.elapsedSeconds)
    }

    /** Pace is two octets in the current supplement and one in the revision FTMS 1.0 shipped with. */
    @Test
    fun readsBothTreadmillPaceLayouts() {
        val wide = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.treadmillDataUuid,
            byteArrayOf(
                0x60, 0x01, // instantaneous pace, average pace, heart rate
                0xE8.toByte(), 0x03, // 10.00 km/h
                0x78, 0x00, // 120 s per 500 m
                0x82.toByte(), 0x00, // 130 s per 500 m
                0x8C.toByte(), // 140 bpm
            ),
        )!!
        assertEquals(140, wide.heartRateBpm)

        val narrow = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.treadmillDataUuid,
            byteArrayOf(
                0x60,
                0x01,
                0xE8.toByte(),
                0x03,
                0x78, // one octet
                0x82.toByte(),
                0x8C.toByte(),
            ),
        )!!
        assertEquals(140, narrow.heartRateBpm)
    }

    /** Treadmill power output is decoded and kept. */
    @Test
    fun keepsTreadmillPowerOutput() {
        val result = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.treadmillDataUuid,
            byteArrayOf(
                0x00,
                0x10, // force on belt and power output
                0xE8.toByte(),
                0x03, // 10.00 km/h
                0x2C,
                0x01, // 300 N
                0xC8.toByte(),
                0x00, // 200 W
            ),
        )!!

        assertEquals(200, result.powerWatts)
    }

    /**
     * FTMS says "unavailable" with a sentinel rather than by omitting the field. Stored verbatim, it
     * would put 65 535 kcal and a 3 276.7 % incline in front of the user.
     */
    @Test
    fun sentinelValuesBecomeAbsentRatherThanAbsurd() {
        val energy = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.indoorBikeDataUuid,
            byteArrayOf(
                0x01,
                0x01, // no instantaneous speed, energy present
                0xFF.toByte(),
                0xFF.toByte(), // total energy unavailable
                0xFF.toByte(),
                0xFF.toByte(), // per hour unavailable
                0xFF.toByte(), // per minute unavailable
            ),
        )!!
        assertNull(energy.totalEnergyKcal)

        val incline = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.treadmillDataUuid,
            byteArrayOf(
                0x09,
                0x00, // no instantaneous speed, inclination present
                0xFF.toByte(),
                0x7F, // inclination unavailable
                0xFF.toByte(),
                0x7F, // ramp angle unavailable
            ),
        )!!
        assertNull(incline.inclinationPercent)
    }

    /** Power Output is a sint16 with the same 0x7FFF "not available" value as the ramp angle. */
    @Test
    fun unavailablePowerIsAbsentRatherThan32767Watts() {
        val treadmillPower = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.treadmillDataUuid,
            byteArrayOf(
                0x00,
                0x10, // force on belt and power output
                0xE8.toByte(),
                0x03, // 10.00 km/h
                0x2C,
                0x01, // 300 N
                0xFF.toByte(),
                0x7F, // power output unavailable
            ),
        )!!
        assertNull(treadmillPower.powerWatts)

        val bikePower = BluetoothFitnessMachine.parse(
            BluetoothFitnessMachine.indoorBikeDataUuid,
            byteArrayOf(
                0x41,
                0x00, // no instantaneous speed, power present
                0xFF.toByte(),
                0x7F, // instantaneous power unavailable
            ),
        )!!
        assertNull(bikePower.powerWatts)
    }
}
