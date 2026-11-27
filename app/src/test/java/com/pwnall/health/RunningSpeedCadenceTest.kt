package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The units in this profile are not the ones next door in FTMS: speed is 1/256 m/s and distance is
 * 1/10 m. Every assertion here is really a check that nobody reused FTMS's 0.01 scaling.
 */
class RunningSpeedCadenceTest {
    @Test
    fun decodesSpeedAndCadenceAlone() {
        // 3.0 m/s = 768 in 1/256 units, 170 spm, walking.
        val result = BluetoothRunningSpeedCadence.parse(
            byteArrayOf(0x00, 0x00, 0x03, 0xAA.toByte()),
        )!!

        assertEquals(3.0, result.speedMps, 0.0001)
        assertEquals(10.8, result.speedKph, 0.0001)
        assertEquals(170, result.cadenceSpm)
        assertNull(result.strideLengthCm)
        assertNull(result.totalDistanceMeters)
        assertTrue(!result.running)
    }

    @Test
    fun decodesStrideLengthAndTotalDistance() {
        // Flags: stride length + total distance + running. Stride 118 cm, distance 5432.1 m.
        val result = BluetoothRunningSpeedCadence.parse(
            byteArrayOf(
                0x07,
                0x00, 0x04, // 4.0 m/s
                0xB4.toByte(), // 180 spm
                0x76, 0x00, // 118 cm
                0x31, 0xD4.toByte(), 0x00, 0x00, // 54 321 tenths of a metre
            ),
        )!!

        assertEquals(4.0, result.speedMps, 0.0001)
        assertEquals(180, result.cadenceSpm)
        assertEquals(118, result.strideLengthCm)
        assertEquals(5432.1, result.totalDistanceMeters!!, 0.0001)
        assertTrue(result.running)
    }

    /** A distance past 65 535 tenths has to survive the 16-bit halves it is assembled from. */
    @Test
    fun aLongRunDoesNotWrapAround() {
        val result = BluetoothRunningSpeedCadence.parse(
            byteArrayOf(
                0x02,
                0x00,
                0x02,
                0x64,
                0x40,
                0x42,
                0x0F,
                0x00, // 1 000 000 tenths = 100 km
            ),
        )!!

        assertEquals(100_000.0, result.totalDistanceMeters!!, 0.001)
    }

    /**
     * Total Distance is the pod's odometer. A five-kilometre run on a pod with 1 234.5 km behind it
     * must not be saved, and sent to Health Connect, as a workout of 1 239.5 km.
     */
    @Test
    fun aWorkoutCoversWhatTheOdometerGainedSinceTheFirstPacket() {
        val atTheEnd = RunningMeasurement(speedMps = 3.0, cadenceSpm = 85, totalDistanceMeters = 1_239_500.0)

        assertEquals(5_000.0, atTheEnd.sinceStart(1_234_500.0).totalDistanceMeters!!, 0.001)
        // The first packet of a connection is its own start.
        assertEquals(0.0, atTheEnd.sinceStart(1_239_500.0).totalDistanceMeters!!, 0.001)
        // A counter reset under way reads the distance since the reset.
        val afterReset = atTheEnd.copy(totalDistanceMeters = 800.0)
        assertEquals(800.0, afterReset.sinceStart(1_234_500.0).totalDistanceMeters!!, 0.001)
        // A pod that reports no distance still reports none, and the rest of the packet is untouched.
        assertNull(RunningMeasurement(3.0, 85).sinceStart(1_234_500.0).totalDistanceMeters)
        assertEquals(atTheEnd, atTheEnd.sinceStart(null))
    }

    @Test
    fun rejectsShortAndOverlongRecords() {
        assertNull(BluetoothRunningSpeedCadence.parse(byteArrayOf(0x00, 0x00, 0x03)))
        // Flags promise nothing optional, so a trailing byte means we read the record wrong.
        assertNull(BluetoothRunningSpeedCadence.parse(byteArrayOf(0x00, 0x00, 0x03, 0xAA.toByte(), 0x00)))
        // Flags promise a stride length the packet does not carry.
        assertNull(BluetoothRunningSpeedCadence.parse(byteArrayOf(0x01, 0x00, 0x03, 0xAA.toByte())))
    }

    @Test
    fun everyClippedRecordIsRejected() {
        val full = byteArrayOf(
            0x07, 0x00, 0x04, 0xB4.toByte(), 0x76, 0x00, 0x31, 0xD4.toByte(), 0x00, 0x00,
        )
        for (size in 0 until full.size) {
            assertNull("clipped to $size bytes", BluetoothRunningSpeedCadence.parse(full.copyOf(size)))
        }
    }
}
