package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CyclingSpeedCadenceTest {
    @Test
    fun decodesWheelDataAsUnsignedLittleEndian() {
        val result = BluetoothCyclingSpeedCadence.parse(
            byteArrayOf(
                0x01,
                0x78,
                0x56,
                0x34,
                0x12,
                0xFE.toByte(),
                0xFF.toByte(),
            ),
        )!!

        assertEquals(0x12345678L, result.cumulativeWheelRevolutions)
        assertEquals(65_534, result.lastWheelEventTimeTicks)
        assertNull(result.cumulativeCrankRevolutions)
        assertNull(result.lastCrankEventTimeTicks)
    }

    @Test
    fun decodesCrankDataAsUnsignedLittleEndian() {
        val result = BluetoothCyclingSpeedCadence.parse(
            byteArrayOf(
                0x02,
                0xFF.toByte(),
                0xFF.toByte(),
                0x01,
                0x80.toByte(),
            ),
        )!!

        assertNull(result.cumulativeWheelRevolutions)
        assertNull(result.lastWheelEventTimeTicks)
        assertEquals(65_535, result.cumulativeCrankRevolutions)
        assertEquals(32_769, result.lastCrankEventTimeTicks)
    }

    @Test
    fun decodesWheelThenCrankInTheSpecifiedOrder() {
        val result = BluetoothCyclingSpeedCadence.parse(
            byteArrayOf(
                0x03,
                0x04, 0x03, 0x02, 0x01,
                0x06, 0x05,
                0x08, 0x07,
                0x0A, 0x09,
            ),
        )!!

        assertEquals(0x01020304L, result.cumulativeWheelRevolutions)
        assertEquals(0x0506, result.lastWheelEventTimeTicks)
        assertEquals(0x0708, result.cumulativeCrankRevolutions)
        assertEquals(0x090A, result.lastCrankEventTimeTicks)
    }

    @Test
    fun uint32WheelCounterDoesNotBecomeNegative() {
        val result = BluetoothCyclingSpeedCadence.parse(
            byteArrayOf(
                0x01,
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
            ),
        )!!

        assertEquals(4_294_967_295L, result.cumulativeWheelRevolutions)
        assertEquals(65_535, result.lastWheelEventTimeTicks)
    }

    @Test
    fun rejectsEmptyRfuAndNoDataRecords() {
        assertNull(BluetoothCyclingSpeedCadence.parse(byteArrayOf()))
        assertNull(BluetoothCyclingSpeedCadence.parse(byteArrayOf(0x00)))
        assertNull(BluetoothCyclingSpeedCadence.parse(byteArrayOf(0x04)))
        assertNull(
            BluetoothCyclingSpeedCadence.parse(
                byteArrayOf(0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            ),
        )
    }

    @Test
    fun rejectsEveryTruncatedAndOverlongRecord() {
        val complete = byteArrayOf(
            0x03,
            0x04, 0x03, 0x02, 0x01,
            0x06, 0x05,
            0x08, 0x07,
            0x0A, 0x09,
        )

        for (size in 0 until complete.size) {
            assertNull(
                "CSC record clipped to $size bytes",
                BluetoothCyclingSpeedCadence.parse(complete.copyOf(size)),
            )
        }
        assertNull(BluetoothCyclingSpeedCadence.parse(complete + 0x00))
    }

    @Test
    fun exposesTheOfficialEventClock() {
        assertEquals(1_024, BluetoothCyclingSpeedCadence.EVENT_TIME_TICKS_PER_SECOND)
        assertEquals(65_536, BluetoothCyclingSpeedCadence.EVENT_TIME_ROLLOVER_TICKS)
    }
}
