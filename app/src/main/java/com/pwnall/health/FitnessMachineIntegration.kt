package com.pwnall.health

import java.util.UUID

enum class FitnessMachineType {
    UNKNOWN,
    TREADMILL,
    ROWER,
    INDOOR_BIKE,
}

data class FitnessMachineMeasurement(
    val type: FitnessMachineType,
    val speedKph: Double? = null,
    val cadenceRpm: Double? = null,
    val strokeRateSpm: Double? = null,
    val strokeCount: Int? = null,
    val totalDistanceMeters: Int? = null,
    val inclinationPercent: Double? = null,
    val resistanceLevel: Double? = null,
    val powerWatts: Int? = null,
    val totalEnergyKcal: Int? = null,
    val heartRateBpm: Int? = null,
    val elapsedSeconds: Int? = null,
    /**
     * FTMS 4.9.1.1 More Data: this frame continues the one before it and omits the mandatory
     * field. Set only on a raw frame; [FitnessMachineAccumulator] clears it on what it hands out.
     */
    val moreData: Boolean = false,
)

/**
 * Joins an FTMS record that a small ATT_MTU split across two notifications.
 *
 * A machine whose record does not fit sends the mandatory field and what follows it first, then
 * the rest in a notification with More Data set and the mandatory field absent. Each half decodes
 * as a valid record on its own, and posting both as whole measurements would make the screen
 * alternate between them and the finished workout take calories, heart rate and elapsed time from
 * whichever half happened to come last.
 *
 * The first half cannot say whether a second is coming, so it is posted as it is and the
 * continuation is posted merged into it. The continuation's fields are ones the machine never fits
 * into the first half, so they are carried into the next first half as well — without that, every
 * cycle would blink them off and on again. A field's newest value always wins.
 */
internal class FitnessMachineAccumulator {
    private var primary: FitnessMachineMeasurement? = null
    private var continuation: FitnessMachineMeasurement? = null

    fun update(frame: FitnessMachineMeasurement): FitnessMachineMeasurement {
        if (frame.moreData) {
            continuation = frame
            return primary?.takeIf { it.type == frame.type }?.overlaid(frame) ?: frame.copy(moreData = false)
        }
        primary = frame
        return continuation?.takeIf { it.type == frame.type }?.overlaid(frame) ?: frame
    }

    /** This record with every field [newer] carries written over it. */
    private fun FitnessMachineMeasurement.overlaid(newer: FitnessMachineMeasurement) =
        FitnessMachineMeasurement(
            type = type,
            speedKph = newer.speedKph ?: speedKph,
            cadenceRpm = newer.cadenceRpm ?: cadenceRpm,
            strokeRateSpm = newer.strokeRateSpm ?: strokeRateSpm,
            strokeCount = newer.strokeCount ?: strokeCount,
            totalDistanceMeters = newer.totalDistanceMeters ?: totalDistanceMeters,
            inclinationPercent = newer.inclinationPercent ?: inclinationPercent,
            resistanceLevel = newer.resistanceLevel ?: resistanceLevel,
            powerWatts = newer.powerWatts ?: powerWatts,
            totalEnergyKcal = newer.totalEnergyKcal ?: totalEnergyKcal,
            heartRateBpm = newer.heartRateBpm ?: heartRateBpm,
            elapsedSeconds = newer.elapsedSeconds ?: elapsedSeconds,
        )
}

/**
 * Bluetooth SIG Fitness Machine Service 1.0.1 packet decoder.
 *
 * Independent of Android Bluetooth APIs on purpose: the runtime GATT collector subscribes to a data
 * characteristic and hands each complete notification here, so every packet layout is a unit test.
 * Control Point writes are out of scope: collection is read-only.
 *
 * Two fields changed width between the revision in force when FTMS 1.0 was adopted and the current
 * GATT Specification Supplement — Resistance Level (sint16 → uint8) and the treadmill pace fields
 * (uint8 km/min → uint16 seconds per 500 m). Guessing either way mis-decodes half the machines on
 * the market, and a wrong guess does not fail loudly: it shifts every later field by one byte and
 * still returns a confident measurement. So this does not guess. Every FTMS record has a length that
 * the Flags field determines exactly, which makes the packet self-describing: parse it under each
 * candidate width and keep the reading that consumes the payload to the last byte.
 */
object BluetoothFitnessMachine {
    val serviceUuid: UUID = bluetoothUuid(0x1826)
    val featureUuid: UUID = bluetoothUuid(0x2ACC)
    val treadmillDataUuid: UUID = bluetoothUuid(0x2ACD)
    val rowerDataUuid: UUID = bluetoothUuid(0x2AD1)
    val indoorBikeDataUuid: UUID = bluetoothUuid(0x2AD2)
    val controlPointUuid: UUID = bluetoothUuid(0x2AD9)

    /** Both revisions of Resistance Level, widest first so a 2-byte fit is preferred when both fit. */
    private val RESISTANCE_WIDTHS = listOf(2, 1)

    /** Both revisions of the pace fields. */
    private val PACE_WIDTHS = listOf(2, 1)

    fun parse(characteristicUuid: UUID, payload: ByteArray): FitnessMachineMeasurement? =
        try {
            when (characteristicUuid) {
                treadmillDataUuid -> exactFit(payload, PACE_WIDTHS, ::parseTreadmill)
                rowerDataUuid -> exactFit(payload, RESISTANCE_WIDTHS, ::parseRower)
                indoorBikeDataUuid -> exactFit(payload, RESISTANCE_WIDTHS, ::parseIndoorBike)
                else -> null
            }
        } catch (_: MalformedFtmsPacket) {
            null
        }

    /**
     * Parses under each candidate width and accepts only the one that lands exactly on the end of
     * the payload. A packet that fits no candidate is rejected rather than half-read — the whole
     * point of the exercise is that a mis-decoded field must not reach the journal.
     */
    private fun exactFit(
        payload: ByteArray,
        widths: List<Int>,
        parse: (FtmsPacket, Int) -> FitnessMachineMeasurement,
    ): FitnessMachineMeasurement {
        widths.forEach { width ->
            val packet = FtmsPacket(payload)
            val measurement = try {
                parse(packet, width)
            } catch (_: MalformedFtmsPacket) {
                return@forEach
            }
            if (packet.exhausted) return measurement
        }
        throw MalformedFtmsPacket()
    }

    // Flag-driven FTMS frame: every flag bit adds an optional field.
    @Suppress("CyclomaticComplexMethod")
    private fun parseIndoorBike(packet: FtmsPacket, resistanceOctets: Int): FitnessMachineMeasurement {
        val flags = packet.u16()
        // Bit 0 is the one inverted bit in the frame: clear means the instantaneous field IS here.
        val speed = if (flags and 0x0001 == 0) packet.u16() * 0.01 else null
        if (flags and 0x0002 != 0) packet.u16() // average speed
        val cadence = if (flags and 0x0004 != 0) packet.u16() * 0.5 else null
        if (flags and 0x0008 != 0) packet.u16() // average cadence
        val distance = if (flags and 0x0010 != 0) packet.u24() else null
        val resistance = if (flags and 0x0020 != 0) packet.resistance(resistanceOctets) else null
        val power = if (flags and 0x0040 != 0) packet.power() else null
        if (flags and 0x0080 != 0) packet.s16() // average power
        val energy = if (flags and 0x0100 != 0) packet.energy() else null
        val heartRate = if (flags and 0x0200 != 0) packet.u8() else null
        if (flags and 0x0400 != 0) packet.u8() // metabolic equivalent
        val elapsed = if (flags and 0x0800 != 0) packet.u16() else null
        if (flags and 0x1000 != 0) packet.u16() // remaining time
        return FitnessMachineMeasurement(
            type = FitnessMachineType.INDOOR_BIKE,
            speedKph = speed,
            cadenceRpm = cadence,
            totalDistanceMeters = distance,
            resistanceLevel = resistance,
            powerWatts = power,
            totalEnergyKcal = energy,
            heartRateBpm = heartRate,
            elapsedSeconds = elapsed,
            moreData = flags and 0x0001 != 0,
        )
    }

    // Flag-driven FTMS frame: every flag bit adds an optional field.
    @Suppress("CyclomaticComplexMethod")
    private fun parseTreadmill(packet: FtmsPacket, paceOctets: Int): FitnessMachineMeasurement {
        val flags = packet.u16()
        val speed = if (flags and 0x0001 == 0) packet.u16() * 0.01 else null
        if (flags and 0x0002 != 0) packet.u16() // average speed
        val distance = if (flags and 0x0004 != 0) packet.u24() else null
        val inclination = if (flags and 0x0008 != 0) {
            packet.angle().also { packet.angle() } // ramp angle follows inclination
        } else {
            null
        }
        if (flags and 0x0010 != 0) {
            packet.u16() // positive elevation gain
            packet.u16() // negative elevation gain
        }
        if (flags and 0x0020 != 0) packet.take(paceOctets) // instantaneous pace
        if (flags and 0x0040 != 0) packet.take(paceOctets) // average pace
        val energy = if (flags and 0x0080 != 0) packet.energy() else null
        val heartRate = if (flags and 0x0100 != 0) packet.u8() else null
        if (flags and 0x0200 != 0) packet.u8() // metabolic equivalent
        val elapsed = if (flags and 0x0400 != 0) packet.u16() else null
        if (flags and 0x0800 != 0) packet.u16() // remaining time
        val power = if (flags and 0x1000 != 0) {
            packet.s16() // force on belt
            packet.power() // power output
        } else {
            null
        }
        return FitnessMachineMeasurement(
            type = FitnessMachineType.TREADMILL,
            speedKph = speed,
            totalDistanceMeters = distance,
            inclinationPercent = inclination,
            powerWatts = power,
            totalEnergyKcal = energy,
            heartRateBpm = heartRate,
            elapsedSeconds = elapsed,
            moreData = flags and 0x0001 != 0,
        )
    }

    // Flag-driven FTMS frame: every flag bit adds an optional field.
    @Suppress("CyclomaticComplexMethod")
    private fun parseRower(packet: FtmsPacket, resistanceOctets: Int): FitnessMachineMeasurement {
        val flags = packet.u16()
        val strokeRate = if (flags and 0x0001 == 0) packet.u8() * 0.5 else null
        val strokeCount = if (flags and 0x0001 == 0) packet.u16() else null
        if (flags and 0x0002 != 0) packet.u8() // average stroke rate
        val distance = if (flags and 0x0004 != 0) packet.u24() else null
        if (flags and 0x0008 != 0) packet.u16() // instantaneous pace
        if (flags and 0x0010 != 0) packet.u16() // average pace
        val power = if (flags and 0x0020 != 0) packet.power() else null
        if (flags and 0x0040 != 0) packet.s16() // average power
        val resistance = if (flags and 0x0080 != 0) packet.resistance(resistanceOctets) else null
        val energy = if (flags and 0x0100 != 0) packet.energy() else null
        val heartRate = if (flags and 0x0200 != 0) packet.u8() else null
        if (flags and 0x0400 != 0) packet.u8() // metabolic equivalent
        val elapsed = if (flags and 0x0800 != 0) packet.u16() else null
        if (flags and 0x1000 != 0) packet.u16() // remaining time
        return FitnessMachineMeasurement(
            type = FitnessMachineType.ROWER,
            strokeRateSpm = strokeRate,
            strokeCount = strokeCount,
            totalDistanceMeters = distance,
            resistanceLevel = resistance,
            powerWatts = power,
            totalEnergyKcal = energy,
            heartRateBpm = heartRate,
            elapsedSeconds = elapsed,
            moreData = flags and 0x0001 != 0,
        )
    }
}

private class FtmsPacket(private val bytes: ByteArray) {
    private var offset = 0

    /** True when the record ended exactly where the flags said it would. */
    val exhausted: Boolean get() = offset == bytes.size

    fun u8(): Int {
        ensure(1)
        return bytes[offset++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun s16(): Int = u16().let { if (it and 0x8000 != 0) it - 0x10000 else it }

    fun u24(): Int = u8() or (u8() shl 8) or (u8() shl 16)

    /** Skips a field whose width differs between spec revisions. */
    fun take(octets: Int) {
        repeat(octets) { u8() }
    }

    /** Unitless level; the older revision sent it as a signed 16-bit value. */
    fun resistance(octets: Int): Double =
        if (octets == 1) u8().toDouble() else s16().toDouble()

    /**
     * Inclination and ramp angle, in 0.1 %. 0x7FFF is the defined "data not available" value, which
     * read as a figure would reach the screen as an incline of 3276.7 %.
     */
    fun angle(): Double? = s16().takeUnless { it == ANGLE_UNAVAILABLE }?.let { it * 0.1 }

    /**
     * Instantaneous power, in watts. 0x7FFF is the defined "data not available" value here too,
     * which read as a figure would reach the workout screen as 32,767 W.
     */
    fun power(): Int? = s16().takeUnless { it == POWER_UNAVAILABLE }

    /**
     * Total energy, energy per hour and energy per minute always travel together. Each has its own
     * "not available" value, and total energy is the only one this app keeps.
     */
    fun energy(): Int? {
        val total = u16().takeUnless { it == ENERGY_UNAVAILABLE_16 }
        u16() // energy per hour
        u8() // energy per minute
        return total
    }

    private fun ensure(size: Int) {
        if (offset + size > bytes.size) throw MalformedFtmsPacket()
    }

    private companion object {
        const val ANGLE_UNAVAILABLE = 0x7FFF
        const val POWER_UNAVAILABLE = 0x7FFF
        const val ENERGY_UNAVAILABLE_16 = 0xFFFF
    }
}

private class MalformedFtmsPacket : RuntimeException()
