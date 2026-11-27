package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CyclingPowerTest {
    private val completeForceContextRecord = byteArrayOf(
        0x7F, 0x1F, // every optional field except torque extrema; offset compensation set
        0x41, 0x01, // 321 W
        0x65, // 50.5%, left referenced
        0x90.toByte(), 0x01, // 400 / 32 = 12.5 Nm, crank based
        0x98.toByte(), 0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(), // wheel revolutions
        0xFE.toByte(), 0xFF.toByte(), // wheel event time
        0xFF.toByte(), 0xFF.toByte(), // crank revolutions
        0x00, 0x80.toByte(), // crank event time
        0xE8.toByte(), 0x03, // maximum force 1000 N
        0x0C, 0xFE.toByte(), // minimum force -500 N
        0xBC.toByte(), 0x3A, 0x12, // maximum angle 0xABC, minimum angle 0x123
        0x5A, 0x00, // top dead spot 90 degrees
        0x0E, 0x01, // bottom dead spot 270 degrees
        0xFF.toByte(), 0xFF.toByte(), // 65,535 kJ
    )

    @Test
    fun decodesMandatorySignedPower() {
        val result = BluetoothCyclingPower.parse(
            byteArrayOf(0x00, 0x00, 0x38, 0xFF.toByte()),
        )!!

        assertEquals(-200, result.instantaneousPowerWatts)
        assertNull(result.pedalPowerBalancePercent)
        assertNull(result.cumulativeWheelRevolutions)
        assertFalse(result.offsetCompensationRequired)
    }

    @Test
    fun decodesEveryForceContextFieldWithItsAssignedResolution() {
        val result = BluetoothCyclingPower.parse(completeForceContextRecord)!!

        assertEquals(321, result.instantaneousPowerWatts)
        assertEquals(50.5, result.pedalPowerBalancePercent!!, 0.0)
        assertEquals(CyclingPowerPedalReference.LEFT, result.pedalPowerBalanceReference)
        assertEquals(12.5, result.accumulatedTorqueNewtonMeters!!, 0.0)
        assertEquals(CyclingPowerTorqueSource.CRANK, result.accumulatedTorqueSource)
        assertEquals(0xFEDCBA98L, result.cumulativeWheelRevolutions)
        assertEquals(65_534, result.lastWheelEventTimeTicks)
        assertEquals(65_535, result.cumulativeCrankRevolutions)
        assertEquals(32_768, result.lastCrankEventTimeTicks)
        assertEquals(1_000, result.maximumForceNewtons)
        assertEquals(-500, result.minimumForceNewtons)
        assertNull(result.maximumTorqueNewtonMeters)
        assertNull(result.minimumTorqueNewtonMeters)
        assertEquals(0xABC, result.maximumAngleDegrees)
        assertEquals(0x123, result.minimumAngleDegrees)
        assertEquals(90, result.topDeadSpotAngleDegrees)
        assertEquals(270, result.bottomDeadSpotAngleDegrees)
        assertEquals(65_535, result.accumulatedEnergyKilojoules)
        assertTrue(result.offsetCompensationRequired)
    }

    @Test
    fun decodesSignedTorqueExtremaInOneThirtySecondNewtonMetres() {
        val result = BluetoothCyclingPower.parse(
            byteArrayOf(
                0x80.toByte(),
                0x00, // extreme torque magnitudes present
                0x00,
                0x80.toByte(), // minimum sint16 power
                0x40,
                0x00, // maximum torque 64 / 32 = 2 Nm
                0xE0.toByte(),
                0xFF.toByte(), // minimum torque -32 / 32 = -1 Nm
            ),
        )!!

        assertEquals(-32_768, result.instantaneousPowerWatts)
        assertEquals(2.0, result.maximumTorqueNewtonMeters!!, 0.0)
        assertEquals(-1.0, result.minimumTorqueNewtonMeters!!, 0.0)
        assertNull(result.maximumForceNewtons)
        assertNull(result.minimumForceNewtons)
    }

    @Test
    fun zeroReferenceBitsHaveTheSpecifiedMeaningWhenTheirFieldsExist() {
        val result = BluetoothCyclingPower.parse(
            byteArrayOf(
                0x05,
                0x00, // pedal balance and accumulated torque, reference/source bits clear
                0xFA.toByte(),
                0x00, // 250 W
                0x64, // 50%
                0x20,
                0x00, // 1 Nm
            ),
        )!!

        assertEquals(CyclingPowerPedalReference.UNKNOWN, result.pedalPowerBalanceReference)
        assertEquals(CyclingPowerTorqueSource.WHEEL, result.accumulatedTorqueSource)
    }

    @Test
    fun uint32WheelCounterDoesNotBecomeNegative() {
        val result = BluetoothCyclingPower.parse(
            byteArrayOf(
                0x10, 0x00,
                0x00, 0x00,
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
            ),
        )!!

        assertEquals(4_294_967_295L, result.cumulativeWheelRevolutions)
        assertEquals(65_535, result.lastWheelEventTimeTicks)
    }

    @Test
    fun rejectsRfuFlagsAndMutuallyExclusiveMagnitudeContexts() {
        assertNull(
            BluetoothCyclingPower.parse(
                byteArrayOf(0x00, 0x20, 0x00, 0x00),
            ),
        )
        assertNull(
            BluetoothCyclingPower.parse(
                byteArrayOf(
                    0xC0.toByte(), 0x00,
                    0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                ),
            ),
        )
    }

    @Test
    fun rejectsEveryTruncatedAndOverlongRecord() {
        for (size in 0 until completeForceContextRecord.size) {
            assertNull(
                "Cycling Power record clipped to $size bytes",
                BluetoothCyclingPower.parse(completeForceContextRecord.copyOf(size)),
            )
        }
        assertNull(BluetoothCyclingPower.parse(completeForceContextRecord + 0x00))
    }

    @Test
    fun exposesTheTwoDifferentOfficialEventClocks() {
        assertEquals(2_048, BluetoothCyclingPower.WHEEL_EVENT_TIME_TICKS_PER_SECOND)
        assertEquals(1_024, BluetoothCyclingPower.CRANK_EVENT_TIME_TICKS_PER_SECOND)
        assertEquals(65_536, BluetoothCyclingPower.EVENT_TIME_ROLLOVER_TICKS)
    }
}
