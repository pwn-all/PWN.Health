package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeiserM3iTest {
    /**
     * A firmware 6.24 bike being ridden, captured off real hardware: 82.0 rpm, 52 W, one minute in,
     * 0.2 km, gear 8, no strap.
     */
    private val livePacket = bytes(
        0x06, 0x24, 0x00, 0x00,
        0x34, 0x03, // 82.0 rpm
        0x00, 0x00, // no heart rate
        0x34, 0x00, // 52 W
        0x02, 0x00, // 2 kcal
        0x01, 0x00, // 1:00
        0x02, 0x80, // 0.2 km — the high bit is the unit, not the magnitude
        0x08, // gear 8
    )

    @Test
    fun aRideInProgressDecodesEveryField() {
        val measurement = BluetoothKeiserM3i.parse(livePacket)!!

        assertEquals(FitnessMachineType.INDOOR_BIKE, measurement.type)
        assertEquals(82.0, measurement.cadenceRpm!!, 0.0001)
        assertNull(measurement.heartRateBpm)
        assertEquals(52, measurement.powerWatts)
        assertEquals(2, measurement.totalEnergyKcal)
        assertEquals(60, measurement.elapsedSeconds)
        assertEquals(200, measurement.totalDistanceMeters)
        assertEquals(8.0, measurement.resistanceLevel!!, 0.0001)
    }

    /**
     * The same console after the ride, replaying it. Everything but one byte looks like a workout,
     * and taking it would credit a ride nobody is doing — this is the whole reason the data-type
     * byte is read before anything else.
     */
    @Test
    fun aReviewBroadcastIsNotARide() {
        val review = livePacket.copyOf().also { it[2] = 0xFF.toByte() }

        assertNull(BluetoothKeiserM3i.parse(review))
        // 1..99 are the review intervals, and 228..254 are undocumented rather than live.
        assertNull(BluetoothKeiserM3i.parse(livePacket.copyOf().also { it[2] = 0x01 }))
        assertNull(BluetoothKeiserM3i.parse(livePacket.copyOf().also { it[2] = 0xE4.toByte() }))
        // A live interval still is one.
        assertEquals(52, BluetoothKeiserM3i.parse(livePacket.copyOf().also { it[2] = 0x80.toByte() })?.powerWatts)
    }

    /** A console set to miles sends the same word with the flag clear; 0.2 miles is 321 m. */
    @Test
    fun anImperialConsoleIsReadInMiles() {
        val imperial = livePacket.copyOf().also { it[15] = 0x00 }

        assertEquals(321, BluetoothKeiserM3i.parse(imperial)!!.totalDistanceMeters)
    }

    /** 135.0 bpm arrives as 1350; the tenth is the field's own scale, not a decimal to print. */
    @Test
    fun heartRateCarriesATenth() {
        val strapped = livePacket.copyOf().also {
            it[6] = 0x46
            it[7] = 0x05
        }

        assertEquals(135, BluetoothKeiserM3i.parse(strapped)!!.heartRateBpm)
    }

    /**
     * The version bytes are packed decimal. Read as plain binary, 0x30 is 48 and a 6.30 console
     * would be refused as an impossible firmware.
     */
    @Test
    fun theVersionIsPackedDecimal() {
        val newer = livePacket.copyOf().also { it[1] = 0x30 }
        assertEquals(52, BluetoothKeiserM3i.parse(newer)!!.powerWatts)

        // A nibble above nine is not a version, so the packet is not a Keiser packet.
        assertNull(BluetoothKeiserM3i.parse(livePacket.copyOf().also { it[1] = 0x2A }))
        assertNull(BluetoothKeiserM3i.parse(livePacket.copyOf().also { it[0] = 0x07 }))
    }

    /** Before 6.21 there is no gear byte, and the sixteen bytes that are there still decode. */
    @Test
    fun anOldConsoleReportsNoGear() {
        val old = livePacket.copyOf(16).also { it[1] = 0x20 }

        val measurement = BluetoothKeiserM3i.parse(old)!!
        assertEquals(52, measurement.powerWatts)
        assertNull(measurement.resistanceLevel)
    }

    @Test
    fun nonsenseIsRefused() {
        assertNull(BluetoothKeiserM3i.parse(null))
        assertNull(BluetoothKeiserM3i.parse(byteArrayOf()))
        assertNull(BluetoothKeiserM3i.parse(livePacket.copyOf(15)))
        // The ordinal is documented as 0..200; outside it, the packet is somebody else's.
        assertNull(BluetoothKeiserM3i.parse(livePacket.copyOf().also { it[3] = 0xC9.toByte() }))
        // The company id left in place — the mistake every ported parser makes — starts with 0x02.
        assertNull(BluetoothKeiserM3i.parse(byteArrayOf(0x02, 0x01) + livePacket))
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }
}
