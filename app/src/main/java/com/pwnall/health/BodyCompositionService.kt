package com.pwnall.health

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

data class BodyCompositionMeasurement(
    /** null is the profile's 0xFFFF "measurement unsuccessful" sentinel. */
    val bodyFatPercent: Double?,
    val timestamp: LocalDateTime? = null,
    /** null means either no user field or the defined 0xFF unknown-user value. */
    val userId: Int? = null,
    /** Distinguishes an absent User ID from a present 0xFF unknown-user field. */
    val userIdPresent: Boolean = false,
    val basalMetabolismKj: Int? = null,
    val musclePercent: Double? = null,
    val muscleMassKg: Double? = null,
    val fatFreeMassKg: Double? = null,
    val softLeanMassKg: Double? = null,
    val bodyWaterMassKg: Double? = null,
    val impedanceOhms: Double? = null,
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    /** The packet was encoded in imperial units; all public mass/height values above are SI. */
    val imperialSource: Boolean = false,
    /** Both parts carry this bit when one measurement is split across two indications. */
    val multiplePacket: Boolean = false,
    /** null when the scale sent no time; false when it sent one this app declined to believe. */
    val deviceClockSet: Boolean? = null,
) {
    val successful: Boolean get() = bodyFatPercent != null
}

/** Bluetooth SIG Body Composition Service 1.0.1 (0x181B). */
object BluetoothBodyComposition {
    val serviceUuid: UUID = bluetoothUuid(0x181B)
    val measurementUuid: UUID = bluetoothUuid(0x2A9C)
    val featureUuid: UUID = bluetoothUuid(0x2A9B)

    private const val FLAG_IMPERIAL = 1 shl 0
    private const val FLAG_TIMESTAMP = 1 shl 1
    private const val FLAG_USER_ID = 1 shl 2
    private const val FLAG_BASAL_METABOLISM = 1 shl 3
    private const val FLAG_MUSCLE_PERCENTAGE = 1 shl 4
    private const val FLAG_MUSCLE_MASS = 1 shl 5
    private const val FLAG_FAT_FREE_MASS = 1 shl 6
    private const val FLAG_SOFT_LEAN_MASS = 1 shl 7
    private const val FLAG_BODY_WATER_MASS = 1 shl 8
    private const val FLAG_IMPEDANCE = 1 shl 9
    private const val FLAG_WEIGHT = 1 shl 10
    private const val FLAG_HEIGHT = 1 shl 11
    private const val FLAG_MULTIPLE_PACKET = 1 shl 12
    private const val RFU_MASK = 0xE000

    private const val FAILED_MEASUREMENT = 0xFFFF
    private const val UNKNOWN_USER = 0xFF
    private const val OPTIONAL_RESULT_FIELDS_MASK =
        FLAG_BASAL_METABOLISM or
            FLAG_MUSCLE_PERCENTAGE or
            FLAG_MUSCLE_MASS or
            FLAG_FAT_FREE_MASS or
            FLAG_SOFT_LEAN_MASS or
            FLAG_BODY_WATER_MASS or
            FLAG_IMPEDANCE or
            FLAG_WEIGHT or
            FLAG_HEIGHT

    /**
     * Decodes one complete characteristic indication.
     *
     * For a split measurement this deliberately returns each indication separately. GATT does not
     * identify a packet as first/continuation on the wire: it only sets bit 12 in both packets. The
     * collector must pair two consecutive indications, while this model preserves that metadata.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun parse(
        payload: ByteArray,
        arrivalMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): BodyCompositionMeasurement? {
        if (payload.size < MINIMUM_BYTES) return null
        val cursor = BodyCompositionCursor(payload)
        val flags = cursor.u16() ?: return null
        if (flags and RFU_MASK != 0) return null

        val rawBodyFat = cursor.u16() ?: return null
        if (rawBodyFat == FAILED_MEASUREMENT && flags and OPTIONAL_RESULT_FIELDS_MASK != 0) {
            // BCS permits timestamp and user ID on a failed measurement, but requires every other
            // optional result field to be disabled.
            return null
        }

        val deviceTime = if (flags has FLAG_TIMESTAMP) {
            cursor.dateTime() ?: if (cursor.failed) return null else null
        } else {
            null
        }
        // 2000-01-01 is a valid Date Time and the default of a clock nobody set. Dropping the
        // timestamp keeps the weighing and lets the collector date it on arrival instead.
        val timestamp = deviceTime?.takeIf { it.isPlausibleDeviceTime(arrivalMillis, zoneId) }
        val userIdPresent = flags has FLAG_USER_ID
        val userId = if (userIdPresent) {
            cursor.u8()?.takeUnless { it == UNKNOWN_USER } ?: if (cursor.failed) return null else null
        } else {
            null
        }

        val basalMetabolism = cursor.optionalU16(flags, FLAG_BASAL_METABOLISM) ?: return null
        val musclePercentage = cursor.optionalU16(flags, FLAG_MUSCLE_PERCENTAGE) ?: return null
        val muscleMass = cursor.optionalU16(flags, FLAG_MUSCLE_MASS) ?: return null
        val fatFreeMass = cursor.optionalU16(flags, FLAG_FAT_FREE_MASS) ?: return null
        val softLeanMass = cursor.optionalU16(flags, FLAG_SOFT_LEAN_MASS) ?: return null
        val bodyWaterMass = cursor.optionalU16(flags, FLAG_BODY_WATER_MASS) ?: return null
        val impedance = cursor.optionalU16(flags, FLAG_IMPEDANCE) ?: return null
        val weight = cursor.optionalU16(flags, FLAG_WEIGHT) ?: return null
        val height = cursor.optionalU16(flags, FLAG_HEIGHT) ?: return null
        if (!cursor.atEnd) return null

        val imperial = flags has FLAG_IMPERIAL
        return BodyCompositionMeasurement(
            bodyFatPercent = rawBodyFat.takeUnless { it == FAILED_MEASUREMENT }?.times(0.1),
            timestamp = timestamp,
            userId = userId,
            userIdPresent = userIdPresent,
            basalMetabolismKj = basalMetabolism.value,
            musclePercent = musclePercentage.value?.times(0.1),
            muscleMassKg = muscleMass.value?.toKilograms(imperial),
            fatFreeMassKg = fatFreeMass.value?.toKilograms(imperial),
            softLeanMassKg = softLeanMass.value?.toKilograms(imperial),
            bodyWaterMassKg = bodyWaterMass.value?.toKilograms(imperial),
            impedanceOhms = impedance.value?.times(0.1),
            weightKg = weight.value?.toKilograms(imperial),
            heightCm = height.value?.toCentimetres(imperial),
            imperialSource = imperial,
            multiplePacket = flags has FLAG_MULTIPLE_PACKET,
            deviceClockSet = if (flags has FLAG_TIMESTAMP) timestamp != null else null,
        )
    }

    private const val MINIMUM_BYTES = 4

    private infix fun Int.has(flag: Int): Boolean = this and flag != 0

    /** SIG: 0.005 kg per bit in SI, 0.01 lb per bit in imperial. */
    private fun Int.toKilograms(imperial: Boolean): Double =
        if (imperial) this * 0.01 * KILOGRAMS_IN_A_POUND else this * 0.005

    /** SIG: 0.001 m per bit in SI, 0.1 inch per bit in imperial. */
    private fun Int.toCentimetres(imperial: Boolean): Double =
        if (imperial) this * 0.1 * CENTIMETRES_IN_AN_INCH else this * 0.1
}

/** Present-but-zero must not be confused with absent, hence the small wrapper. */
private data class OptionalBodyCompositionValue(val value: Int?)

private class BodyCompositionCursor(private val payload: ByteArray) {
    private var offset = 0
    var failed: Boolean = false
        private set

    val atEnd: Boolean get() = offset == payload.size

    fun u8(): Int? {
        if (offset >= payload.size) {
            failed = true
            return null
        }
        return payload[offset++].toInt() and 0xFF
    }

    fun u16(): Int? {
        val low = u8() ?: return null
        val high = u8() ?: return null
        return low or (high shl 8)
    }

    fun optionalU16(flags: Int, flag: Int): OptionalBodyCompositionValue? =
        if (flags and flag != 0) {
            OptionalBodyCompositionValue(u16() ?: return null)
        } else {
            OptionalBodyCompositionValue(null)
        }

    fun dateTime(): LocalDateTime? = gattDateTime(::u16, ::u8)
}
