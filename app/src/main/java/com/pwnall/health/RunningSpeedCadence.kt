package com.pwnall.health

import java.util.UUID

data class RunningMeasurement(
    /** Metres per second, as the sensor reports it. */
    val speedMps: Double,
    /**
     * Instantaneous Cadence as the sensor reports it. RSCS 3.1 defines it as the times per minute
     * the foot with the sensor hits the ground — one foot, not both — and it is not doubled here.
     */
    val cadenceSpm: Int,
    val strideLengthCm: Int? = null,
    /**
     * Total Distance. As the sensor reports it, that is what it has travelled since its counter was
     * last reset: an odometer. [sinceStart] turns it into the distance of one connection.
     */
    val totalDistanceMeters: Double? = null,
    /** The sensor's own call on whether this is walking or running. */
    val running: Boolean = false,
) {
    val speedKph: Double get() = speedMps * 3.6

    /**
     * This measurement with its distance counted from [startMeters], the Total Distance of the first
     * packet of the connection.
     *
     * The field is an odometer, and this app only listens: it never writes the control point that
     * would reset it. Shown as it came, a five-kilometre run on a pod with 1 234.5 km behind it would
     * be a workout of 1 239.5 km on the card, in its note, and in the distance sent to Health Connect.
     * A counter that reads less than it did at the start was reset under way, and what it reads
     * now is the distance since.
     */
    fun sinceStart(startMeters: Double?): RunningMeasurement {
        val total = totalDistanceMeters
        if (total == null || startMeters == null) return this
        return copy(totalDistanceMeters = if (total >= startMeters) total - startMeters else total)
    }
}

/**
 * Bluetooth SIG Running Speed and Cadence Service 0x1814.
 *
 * A foot pod is the one thing that measures cadence and distance honestly on a treadmill, where GPS
 * sees nothing and the accelerometer in a pocket has to guess. The profile is small and fully
 * published, so it costs one parser and no vendor deal.
 *
 * Units are the trap here, and they are not FTMS's: speed is 1/256 m/s, stride length is
 * centimetres, and total distance is 1/10 of a metre. Reusing FTMS's 0.01 scaling would put every
 * number out by a factor with no error to warn you.
 */
object BluetoothRunningSpeedCadence {
    val serviceUuid: UUID = bluetoothUuid(0x1814)
    val measurementUuid: UUID = bluetoothUuid(0x2A53)
    val featureUuid: UUID = bluetoothUuid(0x2A54)

    private const val FLAG_STRIDE_LENGTH = 0x01
    private const val FLAG_TOTAL_DISTANCE = 0x02
    private const val FLAG_RUNNING = 0x04

    private const val SPEED_UNITS_PER_MPS = 256.0
    private const val DISTANCE_UNITS_PER_METRE = 10.0

    /** Same shape as the other decoders: no Android types, so every layout is a unit test. */
    // One early return per field the flags promised and the record did not deliver.
    fun parse(payload: ByteArray): RunningMeasurement? {
        if (payload.size < MINIMUM_BYTES) return null
        val flags = payload.u8(0)
        var offset = 1

        val speed = payload.u16(offset) / SPEED_UNITS_PER_MPS
        offset += 2
        val cadence = payload.u8(offset)
        offset += 1

        var strideLengthCm: Int? = null
        if (flags and FLAG_STRIDE_LENGTH != 0) {
            if (payload.size < offset + 2) return null
            strideLengthCm = payload.u16(offset)
            offset += 2
        }

        var totalDistance: Double? = null
        if (flags and FLAG_TOTAL_DISTANCE != 0) {
            if (payload.size < offset + 4) return null
            totalDistance = payload.u32(offset) / DISTANCE_UNITS_PER_METRE
            offset += 4
        }

        // The record is fully determined by its flags; anything left over means it was read wrong.
        if (offset != payload.size) return null

        return RunningMeasurement(
            speedMps = speed,
            cadenceSpm = cadence,
            strideLengthCm = strideLengthCm,
            totalDistanceMeters = totalDistance,
            running = flags and FLAG_RUNNING != 0,
        )
    }

    /** Flags, speed, cadence. Everything after that is optional. */
    private const val MINIMUM_BYTES = 4

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
    private fun ByteArray.u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)
    private fun ByteArray.u32(index: Int): Long =
        u16(index).toLong() or (u16(index + 2).toLong() shl 16)
}
