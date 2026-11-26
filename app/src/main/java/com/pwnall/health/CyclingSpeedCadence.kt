package com.pwnall.health

import java.util.UUID

/**
 * One Bluetooth SIG CSC Measurement (0x2A5B).
 *
 * The event times intentionally remain unsigned raw ticks. They are free-running 16-bit counters
 * and clients need both the raw value and the 65,536-tick rollover to derive speed or cadence from
 * two notifications without losing precision.
 */
data class CyclingSpeedCadenceMeasurement(
    val cumulativeWheelRevolutions: Long? = null,
    val lastWheelEventTimeTicks: Int? = null,
    val cumulativeCrankRevolutions: Int? = null,
    val lastCrankEventTimeTicks: Int? = null,
)

/** Bluetooth SIG Cycling Speed and Cadence Service 0x1816. */
object BluetoothCyclingSpeedCadence {
    val serviceUuid: UUID = bluetoothUuid(0x1816)
    val measurementUuid: UUID = bluetoothUuid(0x2A5B)
    val featureUuid: UUID = bluetoothUuid(0x2A5C)

    const val EVENT_TIME_TICKS_PER_SECOND = 1_024
    const val EVENT_TIME_ROLLOVER_TICKS = 65_536

    private const val FLAG_WHEEL_REVOLUTION_DATA = 0x01
    private const val FLAG_CRANK_REVOLUTION_DATA = 0x02
    private const val KNOWN_FLAGS = FLAG_WHEEL_REVOLUTION_DATA or FLAG_CRANK_REVOLUTION_DATA

    /**
     * Decodes one complete characteristic value. A conforming CSC record contains wheel data,
     * crank data, or both; RFU flags and bytes not accounted for by those flags are rejected.
     */
    @Suppress("ReturnCount")
    fun parse(payload: ByteArray): CyclingSpeedCadenceMeasurement? {
        if (payload.isEmpty()) return null
        val flags = payload.u8(0)
        if (flags and KNOWN_FLAGS == 0 || flags and KNOWN_FLAGS.inv() != 0) return null

        var offset = 1
        var cumulativeWheelRevolutions: Long? = null
        var lastWheelEventTimeTicks: Int? = null
        if (flags and FLAG_WHEEL_REVOLUTION_DATA != 0) {
            if (payload.size < offset + WHEEL_DATA_BYTES) return null
            cumulativeWheelRevolutions = payload.u32(offset)
            lastWheelEventTimeTicks = payload.u16(offset + 4)
            offset += WHEEL_DATA_BYTES
        }

        var cumulativeCrankRevolutions: Int? = null
        var lastCrankEventTimeTicks: Int? = null
        if (flags and FLAG_CRANK_REVOLUTION_DATA != 0) {
            if (payload.size < offset + CRANK_DATA_BYTES) return null
            cumulativeCrankRevolutions = payload.u16(offset)
            lastCrankEventTimeTicks = payload.u16(offset + 2)
            offset += CRANK_DATA_BYTES
        }

        if (offset != payload.size) return null
        return CyclingSpeedCadenceMeasurement(
            cumulativeWheelRevolutions = cumulativeWheelRevolutions,
            lastWheelEventTimeTicks = lastWheelEventTimeTicks,
            cumulativeCrankRevolutions = cumulativeCrankRevolutions,
            lastCrankEventTimeTicks = lastCrankEventTimeTicks,
        )
    }

    private const val WHEEL_DATA_BYTES = 6
    private const val CRANK_DATA_BYTES = 4
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

private fun ByteArray.u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

private fun ByteArray.u32(index: Int): Long =
    u16(index).toLong() or (u16(index + 2).toLong() shl 16)
