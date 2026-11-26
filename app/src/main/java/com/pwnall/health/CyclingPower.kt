package com.pwnall.health

import java.util.UUID

enum class CyclingPowerPedalReference {
    UNKNOWN,
    LEFT,
}

enum class CyclingPowerTorqueSource {
    WHEEL,
    CRANK,
}

/**
 * One complete Bluetooth SIG Cycling Power Measurement (0x2A63).
 *
 * Cumulative counters and event times remain unsigned raw values so the caller can perform the
 * modulo arithmetic across notifications. Resolved quantities use the exact resolutions assigned
 * by the GATT Specification Supplement.
 */
@Suppress("LongParameterList")
data class CyclingPowerMeasurement(
    val instantaneousPowerWatts: Int,
    val pedalPowerBalancePercent: Double? = null,
    val pedalPowerBalanceReference: CyclingPowerPedalReference? = null,
    val accumulatedTorqueNewtonMeters: Double? = null,
    val accumulatedTorqueSource: CyclingPowerTorqueSource? = null,
    val cumulativeWheelRevolutions: Long? = null,
    val lastWheelEventTimeTicks: Int? = null,
    val cumulativeCrankRevolutions: Int? = null,
    val lastCrankEventTimeTicks: Int? = null,
    val maximumForceNewtons: Int? = null,
    val minimumForceNewtons: Int? = null,
    val maximumTorqueNewtonMeters: Double? = null,
    val minimumTorqueNewtonMeters: Double? = null,
    val maximumAngleDegrees: Int? = null,
    val minimumAngleDegrees: Int? = null,
    val topDeadSpotAngleDegrees: Int? = null,
    val bottomDeadSpotAngleDegrees: Int? = null,
    val accumulatedEnergyKilojoules: Int? = null,
    val offsetCompensationRequired: Boolean = false,
)

/** Bluetooth SIG Cycling Power Service 0x1818. */
object BluetoothCyclingPower {
    val serviceUuid: UUID = bluetoothUuid(0x1818)
    val measurementUuid: UUID = bluetoothUuid(0x2A63)
    val featureUuid: UUID = bluetoothUuid(0x2A65)

    const val WHEEL_EVENT_TIME_TICKS_PER_SECOND = 2_048
    const val CRANK_EVENT_TIME_TICKS_PER_SECOND = 1_024
    const val EVENT_TIME_ROLLOVER_TICKS = 65_536

    private const val FLAG_PEDAL_POWER_BALANCE = 0x0001
    private const val FLAG_PEDAL_POWER_BALANCE_REFERENCE = 0x0002
    private const val FLAG_ACCUMULATED_TORQUE = 0x0004
    private const val FLAG_ACCUMULATED_TORQUE_SOURCE = 0x0008
    private const val FLAG_WHEEL_REVOLUTION_DATA = 0x0010
    private const val FLAG_CRANK_REVOLUTION_DATA = 0x0020
    private const val FLAG_EXTREME_FORCE_MAGNITUDES = 0x0040
    private const val FLAG_EXTREME_TORQUE_MAGNITUDES = 0x0080
    private const val FLAG_EXTREME_ANGLES = 0x0100
    private const val FLAG_TOP_DEAD_SPOT_ANGLE = 0x0200
    private const val FLAG_BOTTOM_DEAD_SPOT_ANGLE = 0x0400
    private const val FLAG_ACCUMULATED_ENERGY = 0x0800
    private const val FLAG_OFFSET_COMPENSATION = 0x1000
    private const val RFU_FLAGS = 0xE000

    private const val TORQUE_UNITS_PER_NEWTON_METRE = 32.0
    private const val PEDAL_BALANCE_UNITS_PER_PERCENT = 2.0

    /**
     * Decodes one complete characteristic value and rejects truncated, overlong, RFU-marked, or
     * internally contradictory records. Extreme force and extreme torque are mutually exclusive
     * because a conforming sensor has exactly one measurement context.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun parse(payload: ByteArray): CyclingPowerMeasurement? {
        if (payload.size < REQUIRED_BYTES) return null
        val packet = CyclingPowerPacket(payload)
        val flags = packet.u16()
        if (flags and RFU_FLAGS != 0) return null
        if (
            flags and FLAG_EXTREME_FORCE_MAGNITUDES != 0 &&
            flags and FLAG_EXTREME_TORQUE_MAGNITUDES != 0
        ) {
            return null
        }

        return try {
            val power = packet.s16()
            val pedalBalance = if (flags has FLAG_PEDAL_POWER_BALANCE) {
                packet.u8() / PEDAL_BALANCE_UNITS_PER_PERCENT
            } else {
                null
            }
            val pedalReference = if (pedalBalance != null) {
                if (flags has FLAG_PEDAL_POWER_BALANCE_REFERENCE) {
                    CyclingPowerPedalReference.LEFT
                } else {
                    CyclingPowerPedalReference.UNKNOWN
                }
            } else {
                null
            }

            val accumulatedTorque = if (flags has FLAG_ACCUMULATED_TORQUE) {
                packet.u16() / TORQUE_UNITS_PER_NEWTON_METRE
            } else {
                null
            }
            val accumulatedTorqueSource = if (accumulatedTorque != null) {
                if (flags has FLAG_ACCUMULATED_TORQUE_SOURCE) {
                    CyclingPowerTorqueSource.CRANK
                } else {
                    CyclingPowerTorqueSource.WHEEL
                }
            } else {
                null
            }

            val wheelRevolutions = if (flags has FLAG_WHEEL_REVOLUTION_DATA) packet.u32() else null
            val wheelEventTime = if (wheelRevolutions != null) packet.u16() else null
            val crankRevolutions = if (flags has FLAG_CRANK_REVOLUTION_DATA) packet.u16() else null
            val crankEventTime = if (crankRevolutions != null) packet.u16() else null

            val maximumForce = if (flags has FLAG_EXTREME_FORCE_MAGNITUDES) packet.s16() else null
            val minimumForce = if (maximumForce != null) packet.s16() else null

            val maximumTorque = if (flags has FLAG_EXTREME_TORQUE_MAGNITUDES) {
                packet.s16() / TORQUE_UNITS_PER_NEWTON_METRE
            } else {
                null
            }
            val minimumTorque = if (maximumTorque != null) {
                packet.s16() / TORQUE_UNITS_PER_NEWTON_METRE
            } else {
                null
            }

            val packedAngles = if (flags has FLAG_EXTREME_ANGLES) packet.u24() else null
            val maximumAngle = packedAngles?.and(ANGLE_MASK)
            val minimumAngle = packedAngles?.ushr(ANGLE_BITS)

            val topDeadSpot = if (flags has FLAG_TOP_DEAD_SPOT_ANGLE) packet.u16() else null
            val bottomDeadSpot = if (flags has FLAG_BOTTOM_DEAD_SPOT_ANGLE) packet.u16() else null
            val energy = if (flags has FLAG_ACCUMULATED_ENERGY) packet.u16() else null

            if (!packet.exhausted) return null
            CyclingPowerMeasurement(
                instantaneousPowerWatts = power,
                pedalPowerBalancePercent = pedalBalance,
                pedalPowerBalanceReference = pedalReference,
                accumulatedTorqueNewtonMeters = accumulatedTorque,
                accumulatedTorqueSource = accumulatedTorqueSource,
                cumulativeWheelRevolutions = wheelRevolutions,
                lastWheelEventTimeTicks = wheelEventTime,
                cumulativeCrankRevolutions = crankRevolutions,
                lastCrankEventTimeTicks = crankEventTime,
                maximumForceNewtons = maximumForce,
                minimumForceNewtons = minimumForce,
                maximumTorqueNewtonMeters = maximumTorque,
                minimumTorqueNewtonMeters = minimumTorque,
                maximumAngleDegrees = maximumAngle,
                minimumAngleDegrees = minimumAngle,
                topDeadSpotAngleDegrees = topDeadSpot,
                bottomDeadSpotAngleDegrees = bottomDeadSpot,
                accumulatedEnergyKilojoules = energy,
                offsetCompensationRequired = flags has FLAG_OFFSET_COMPENSATION,
            )
        } catch (_: MalformedCyclingPowerPacket) {
            null
        }
    }

    private infix fun Int.has(flag: Int): Boolean = this and flag != 0

    private const val REQUIRED_BYTES = 4
    private const val ANGLE_BITS = 12
    private const val ANGLE_MASK = 0x0FFF
}

private class CyclingPowerPacket(private val bytes: ByteArray) {
    private var offset = 0

    val exhausted: Boolean get() = offset == bytes.size

    fun u8(): Int {
        ensure(1)
        return bytes[offset++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun s16(): Int = u16().let { if (it and 0x8000 == 0) it else it - 0x10000 }

    fun u24(): Int = u8() or (u8() shl 8) or (u8() shl 16)

    fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)

    private fun ensure(octets: Int) {
        if (offset + octets > bytes.size) throw MalformedCyclingPowerPacket()
    }
}

private class MalformedCyclingPowerPacket : RuntimeException()
