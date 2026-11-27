package com.pwnall.health

/**
 * Keiser M Series indoor-bike broadcast decoder.
 *
 * The console is transmit-only: a non-connectable advertisement carrying the whole ride in one
 * manufacturer-specific structure, repeated every 2 s on old firmware and every ~0.32 s from 6.32.
 * There is no service, no characteristic and no pairing, so this profile never connects — it reads
 * what is already in the air, the way the Xiaomi scales are read.
 *
 * Every offset below is against the payload Android hands back from
 * `ScanRecord.getManufacturerSpecificData(COMPANY_ID)`, which has already removed the two
 * company-id bytes. Keiser's own documentation numbers the same fields two higher because it counts
 * from the company id; ports of published parsers that keep those indices read power out of the
 * calorie bytes and produce numbers that look plausible.
 *
 * Written from the vendor's published field table rather than adapted from any implementation, so
 * the well-known distance bug in Keiser's own C# and TypeScript samples — neither masks the unit
 * flag out of the magnitude — is not inherited here.
 */
object BluetoothKeiserM3i {
    /** Keiser Corporation in the SIG company identifiers. On air it is the little-endian `02 01`. */
    const val COMPANY_ID = 0x0102

    /** Every shipped M Series console reports major version 6; anything else is not this packet. */
    private const val BUILD_MAJOR = 6

    /** The gear byte was appended in 6.21. Nothing else has ever moved. */
    private const val GEAR_FROM_MINOR = 21

    /** Offsets 0..15 are guaranteed; the seventeenth byte is the gear. */
    private const val MIN_LENGTH = 16
    private const val FULL_LENGTH = 17

    private const val MAX_EQUIPMENT_ID = 200

    /** Bit 15 of the distance word says kilometres rather than miles; the rest is the magnitude. */
    private const val DISTANCE_METRIC_FLAG = 0x8000
    private const val DISTANCE_VALUE_MASK = 0x7FFF

    /**
     * Whether this is a Keiser console at all, ride or no ride.
     *
     * Kept apart from [parse] so that a bike replaying the ride that just ended still shows up in a
     * device list. Identifying a bike only by a packet worth recording would make it appear when
     * the rider pedals and vanish when they stop.
     */
    fun isBroadcast(payload: ByteArray?): Boolean = firmwareMinor(payload) != null

    /**
     * One advertisement, decoded, or null when the packet is not a live Keiser ride.
     *
     * Refused: anything too short, a version byte that is not two decimal digits, a console that is
     * not build 6, an equipment ordinal outside the documented range, and — the one that matters
     * most — a review broadcast. A console that has finished or paused a ride keeps advertising the
     * ride that just ended, byte-identical apart from this one field, so a decoder that ignores it
     * records a workout nobody is doing.
     */
    fun parse(payload: ByteArray?): FitnessMachineMeasurement? {
        val bytes = payload ?: return null
        val minor = firmwareMinor(bytes) ?: return null
        if (!isRealTime(bytes.u8(2))) return null

        val heartRate = bytes.u16(6)
        return FitnessMachineMeasurement(
            type = FitnessMachineType.INDOOR_BIKE,
            cadenceRpm = bytes.u16(4) / TENTHS,
            // A console with no strap in range transmits zero rather than omitting the field.
            heartRateBpm = heartRate.takeIf { it > 0 }?.let { (it / TENTHS).toInt() },
            powerWatts = bytes.u16(8),
            totalEnergyKcal = bytes.u16(10),
            elapsedSeconds = bytes.u8(12) * SECONDS_PER_MINUTE + bytes.u8(13),
            totalDistanceMeters = distanceMetres(bytes.u16(14)),
            // The console shows a gear, not a percentage; it is the nearest thing FTMS has a field
            // for, and it is what a rider would recognise on the summary.
            resistanceLevel = bytes.gear(minor),
        )
    }

    /**
     * The firmware's minor version, and by returning it at all, the verdict that this really is a
     * Keiser packet: long enough, build 6, two decimal version digits and an ordinal the console
     * could have been given.
     */
    private fun firmwareMinor(payload: ByteArray?): Int? {
        if (payload == null || payload.size < MIN_LENGTH) return null
        if (decimalByte(payload[0]) != BUILD_MAJOR) return null
        if (payload.u8(3) > MAX_EQUIPMENT_ID) return null
        return decimalByte(payload[1])
    }

    /**
     * The data-type byte, which is the only difference between a ride happening now and the one
     * that finished ten minutes ago. Zero is the live main set; 128..227 are live intervals. The
     * vendor's own sample parsers exclude 128 and admit 228..254; the published table is followed
     * here instead.
     */
    private fun isRealTime(dataType: Int): Boolean =
        dataType == 0 || dataType in REAL_TIME_INTERVALS

    private fun ByteArray.gear(minor: Int): Double? =
        if (size >= FULL_LENGTH && minor >= GEAR_FROM_MINOR) u8(16).takeIf { it > 0 }?.toDouble() else null

    /**
     * Distance is accumulated in whatever unit the console is set to, at a tenth's resolution.
     *
     * The tenths are scaled before they are divided: dividing first turns 323 tenths into
     * 32.299999999999997, and truncating that after the multiply reports a metric ride of a whole
     * 32.3 km as 32 299 m. In metric every legal reading is a multiple of 100 m, so the lost metre
     * is a representation artefact rather than rounding, and it is persisted — Health Connect gets
     * the payload's metres verbatim, not a rounded kilometre.
     */
    private fun distanceMetres(word: Int): Int {
        val tenths = word and DISTANCE_VALUE_MASK
        val perUnit = if (word and DISTANCE_METRIC_FLAG != 0) METRES_IN_A_KILOMETRE else METRES_IN_A_MILE
        return (tenths * perUnit / TENTHS).toInt()
    }

    /**
     * The two version bytes are the only ones sent as packed decimal: 0x30 is 6.30, not 6.48. A
     * nibble above nine is not a version, and a packet claiming one is not worth reading further.
     */
    private fun decimalByte(byte: Byte): Int? {
        val value = byte.toInt() and 0xFF
        val tens = value shr 4
        val units = value and 0x0F
        return if (tens > 9 || units > 9) null else tens * 10 + units
    }

    private val REAL_TIME_INTERVALS = 128..227

    private const val TENTHS = 10.0
    private const val SECONDS_PER_MINUTE = 60

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF
    private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
}
