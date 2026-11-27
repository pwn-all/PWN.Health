package com.pwnall.health

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

enum class GlucoseConcentrationUnit {
    KILOGRAMS_PER_LITER,
    MOLES_PER_LITER,
}

enum class GlucoseMedicationUnit {
    KILOGRAMS,
    LITERS,
}

data class GlucoseMeasurement(
    val sequenceNumber: Int,
    val baseTime: LocalDateTime,
    val timeOffsetMinutes: Int? = null,
    /** Value in [concentrationUnit]'s SIG base unit; null also represents IEEE-11073 NaN. */
    val concentration: Double? = null,
    /** null means that the concentration and Type-Sample Location fields were absent. */
    val concentrationUnit: GlucoseConcentrationUnit? = null,
    val type: Int? = null,
    val sampleLocation: Int? = null,
    val sensorStatus: Int? = null,
    val contextFollows: Boolean = false,
    /**
     * False when the record's time fell outside [isPlausibleDeviceTime]: a meter nobody set counts
     * from 2000-01-01, and every record it holds is dated there. False too when the meter itself
     * annunciated a time fault (sensor status bit 11): it says the time may be wrong. [baseTime]
     * keeps the wire value either way, because the saved payload is the record's identity across
     * syncs.
     */
    val deviceClockSet: Boolean = true,
) {
    val userFacingTime: LocalDateTime
        get() = baseTime.plusMinutes(timeOffsetMinutes?.toLong() ?: 0L)

    /**
     * Sensor Status Annunciation bits by which the meter disowns its own number: a sensor
     * malfunction (1), sample size insufficient (2), a strip insertion error (3), the wrong strip
     * (4), result higher (5) or lower (6) than the device can process, a temperature too high (7) or
     * too low (8) for a valid result, a strip pulled too soon (9), a general device fault (10). What
     * was read is not a glucose concentration, and it must not be saved as one. A low battery (0)
     * and a time fault (11) leave the number standing; the time fault is [deviceClockSet]'s.
     */
    val flaggedInvalid: Boolean
        get() = (sensorStatus ?: 0) and INVALID_STATUS_MASK != 0

    /**
     * A test of the meter, not of the person: Type 10 and Sample Location 4 are both the profile's
     * Control Solution. The strip was dipped in a reference liquid of known strength, and the high
     * one reads like severe hyperglycaemia. The record is a genuine one, so the parser keeps it; it
     * is not a blood glucose reading, and it must not be saved or exported as one.
     */
    val controlSolution: Boolean
        get() = type == TYPE_CONTROL_SOLUTION || sampleLocation == LOCATION_CONTROL_SOLUTION

    /** Conventional display unit for mass-concentration meters. */
    val concentrationMgDl: Double?
        get() = concentration
            ?.takeIf { concentrationUnit == GlucoseConcentrationUnit.KILOGRAMS_PER_LITER }
            ?.times(KG_PER_LITER_TO_MG_PER_DL)

    /** Conventional display unit for molar-concentration meters. */
    val concentrationMmolL: Double?
        get() = concentration
            ?.takeIf { concentrationUnit == GlucoseConcentrationUnit.MOLES_PER_LITER }
            ?.times(MOLES_PER_LITER_TO_MMOL_PER_LITER)

    private companion object {
        const val KG_PER_LITER_TO_MG_PER_DL = 100_000.0
        const val MOLES_PER_LITER_TO_MMOL_PER_LITER = 1_000.0
        const val INVALID_STATUS_MASK = 0x07FE // bits 1 to 10
        const val TYPE_CONTROL_SOLUTION = 10
        const val LOCATION_CONTROL_SOLUTION = 4
    }
}

data class GlucoseMeasurementContext(
    val sequenceNumber: Int,
    val extendedFlagsPresent: Boolean = false,
    val carbohydrateId: Int? = null,
    /** SIG base unit: kilograms. [carbohydrateGrams] is normally more useful for display. */
    val carbohydrateKg: Double? = null,
    val meal: Int? = null,
    val tester: Int? = null,
    val health: Int? = null,
    val exerciseDurationSeconds: Int? = null,
    val exerciseIntensityPercent: Int? = null,
    val medicationId: Int? = null,
    /** SIG base unit selected by [medicationUnit]. */
    val medication: Double? = null,
    val medicationUnit: GlucoseMedicationUnit? = null,
    val hba1cPercent: Double? = null,
) {
    val carbohydrateGrams: Double? get() = carbohydrateKg?.times(1_000.0)

    val medicationMilligrams: Double?
        get() = medication
            ?.takeIf { medicationUnit == GlucoseMedicationUnit.KILOGRAMS }
            ?.times(1_000_000.0)

    val medicationMilliliters: Double?
        get() = medication
            ?.takeIf { medicationUnit == GlucoseMedicationUnit.LITERS }
            ?.times(1_000.0)

    val exerciseDurationOverrun: Boolean get() = exerciseDurationSeconds == 0xFFFF
}

/** A complete GLS patient record after the optional context notification has been paired. */
data class GlucoseRecord(
    val measurement: GlucoseMeasurement,
    val context: GlucoseMeasurementContext?,
)

/**
 * The records of a transfer that go into the journal. One the meter itself disowned — strip
 * underfilled, HI or LO — is left out, and so is a control-solution test, and so is a record that
 * came without a concentration: saved, it would be a "Glucose" row with no glucose in it.
 */
internal fun List<GlucoseRecord>.readingsToSave(): List<GlucoseRecord> = filter {
    it.measurement.concentration != null && !it.measurement.flaggedInvalid && !it.measurement.controlSolution
}

/** Bluetooth SIG Glucose Service 1.0.1 (0x1808). RACP transport remains a GATT concern. */
object BluetoothGlucose {
    val serviceUuid: UUID = bluetoothUuid(0x1808)
    val measurementUuid: UUID = bluetoothUuid(0x2A18)
    val contextUuid: UUID = bluetoothUuid(0x2A34)
    val featureUuid: UUID = bluetoothUuid(0x2A51)
    val racpUuid: UUID = bluetoothUuid(0x2A52)

    private const val FLAG_TIME_OFFSET = 1 shl 0
    private const val FLAG_CONCENTRATION = 1 shl 1
    private const val FLAG_MOLAR_CONCENTRATION = 1 shl 2
    private const val FLAG_SENSOR_STATUS = 1 shl 3
    private const val FLAG_CONTEXT_FOLLOWS = 1 shl 4
    private const val MEASUREMENT_RFU_MASK = 0xE0

    private const val CONTEXT_FLAG_CARBOHYDRATES = 1 shl 0
    private const val CONTEXT_FLAG_MEAL = 1 shl 1
    private const val CONTEXT_FLAG_TESTER_HEALTH = 1 shl 2
    private const val CONTEXT_FLAG_EXERCISE = 1 shl 3
    private const val CONTEXT_FLAG_MEDICATION = 1 shl 4
    private const val CONTEXT_FLAG_MEDICATION_LITERS = 1 shl 5
    private const val CONTEXT_FLAG_HBA1C = 1 shl 6
    private const val CONTEXT_FLAG_EXTENDED = 1 shl 7
    private const val CONTEXT_PRESENCE_MASK = 0xDF

    private const val STATUS_RFU_MASK = 0xF000
    private const val STATUS_TIME_FAULT = 1 shl 11

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun parse(
        payload: ByteArray,
        arrivalMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): GlucoseMeasurement? {
        if (payload.size < MINIMUM_MEASUREMENT_BYTES) return null
        val cursor = GlucoseCursor(payload)
        val flags = cursor.u8() ?: return null
        if (flags and MEASUREMENT_RFU_MASK != 0) return null

        val sequenceNumber = cursor.u16() ?: return null
        val baseTime = cursor.dateTime() ?: return null
        val timeOffset = if (flags has FLAG_TIME_OFFSET) cursor.s16() ?: return null else null

        var concentration: Double? = null
        var concentrationUnit: GlucoseConcentrationUnit? = null
        var type: Int? = null
        var sampleLocation: Int? = null
        if (flags has FLAG_CONCENTRATION) {
            val rawConcentration = cursor.u16() ?: return null
            concentration = Ieee11073.decodeSfloat(rawConcentration)
            concentrationUnit = if (flags has FLAG_MOLAR_CONCENTRATION) {
                GlucoseConcentrationUnit.MOLES_PER_LITER
            } else {
                GlucoseConcentrationUnit.KILOGRAMS_PER_LITER
            }

            val typeAndLocation = cursor.u8() ?: return null
            type = typeAndLocation and 0x0F
            sampleLocation = typeAndLocation ushr 4
            if (type !in VALID_TYPES || sampleLocation !in VALID_SAMPLE_LOCATIONS) return null
        }

        val sensorStatus = if (flags has FLAG_SENSOR_STATUS) {
            (cursor.u16() ?: return null).also {
                if (it and STATUS_RFU_MASK != 0) return null
            }
        } else {
            null
        }
        if (!cursor.atEnd) return null

        // The user-facing time is what the record is filed under, so it is the one held against
        // the phone's clock; the offset is minutes and cannot move a reading across the window.
        val userFacingTime = baseTime.plusMinutes(timeOffset?.toLong() ?: 0L)
        return GlucoseMeasurement(
            sequenceNumber = sequenceNumber,
            baseTime = baseTime,
            timeOffsetMinutes = timeOffset,
            concentration = concentration,
            concentrationUnit = concentrationUnit,
            type = type,
            sampleLocation = sampleLocation,
            sensorStatus = sensorStatus,
            contextFollows = flags has FLAG_CONTEXT_FOLLOWS,
            // The meter's own word that its clock may be wrong counts as much as a date that is.
            deviceClockSet = !((sensorStatus ?: 0) has STATUS_TIME_FAULT) &&
                userFacingTime.isPlausibleDeviceTime(arrivalMillis, zoneId),
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun parseContext(payload: ByteArray): GlucoseMeasurementContext? {
        if (payload.size < MINIMUM_CONTEXT_BYTES) return null
        val cursor = GlucoseCursor(payload)
        val flags = cursor.u8() ?: return null
        // The service requires at least one actual field after Flags and Sequence Number. The unit
        // selector in bit 5 is not a field by itself.
        if (flags and CONTEXT_PRESENCE_MASK == 0) return null

        val sequenceNumber = cursor.u16() ?: return null
        val extendedFlagsPresent = flags has CONTEXT_FLAG_EXTENDED
        if (extendedFlagsPresent && cursor.u8() != 0) return null // all eight bits are RFU in v1.0.1

        var carbohydrateId: Int? = null
        var carbohydrateKg: Double? = null
        if (flags has CONTEXT_FLAG_CARBOHYDRATES) {
            carbohydrateId = cursor.u8() ?: return null
            if (carbohydrateId !in VALID_CARBOHYDRATE_IDS) return null
            carbohydrateKg = Ieee11073.decodeSfloat(cursor.u16() ?: return null)
        }

        val meal = if (flags has CONTEXT_FLAG_MEAL) {
            (cursor.u8() ?: return null).takeIf { it in VALID_MEALS } ?: return null
        } else {
            null
        }

        var tester: Int? = null
        var health: Int? = null
        if (flags has CONTEXT_FLAG_TESTER_HEALTH) {
            val testerAndHealth = cursor.u8() ?: return null
            tester = testerAndHealth and 0x0F
            health = testerAndHealth ushr 4
            if (tester !in VALID_TESTERS || health !in VALID_HEALTH_VALUES) return null
        }

        var exerciseDuration: Int? = null
        var exerciseIntensity: Int? = null
        if (flags has CONTEXT_FLAG_EXERCISE) {
            exerciseDuration = cursor.u16() ?: return null
            exerciseIntensity = cursor.u8() ?: return null
        }

        var medicationId: Int? = null
        var medication: Double? = null
        var medicationUnit: GlucoseMedicationUnit? = null
        if (flags has CONTEXT_FLAG_MEDICATION) {
            medicationId = cursor.u8() ?: return null
            if (medicationId !in VALID_MEDICATION_IDS) return null
            medication = Ieee11073.decodeSfloat(cursor.u16() ?: return null)
            medicationUnit = if (flags has CONTEXT_FLAG_MEDICATION_LITERS) {
                GlucoseMedicationUnit.LITERS
            } else {
                GlucoseMedicationUnit.KILOGRAMS
            }
        }

        val hba1c = if (flags has CONTEXT_FLAG_HBA1C) {
            Ieee11073.decodeSfloat(cursor.u16() ?: return null)
        } else {
            null
        }
        if (!cursor.atEnd) return null

        return GlucoseMeasurementContext(
            sequenceNumber = sequenceNumber,
            extendedFlagsPresent = extendedFlagsPresent,
            carbohydrateId = carbohydrateId,
            carbohydrateKg = carbohydrateKg,
            meal = meal,
            tester = tester,
            health = health,
            exerciseDurationSeconds = exerciseDuration,
            exerciseIntensityPercent = exerciseIntensity,
            medicationId = medicationId,
            medication = medication,
            medicationUnit = medicationUnit,
            hba1cPercent = hba1c,
        )
    }

    /** Context belongs to a measurement only when both the follows bit and sequence agree. */
    fun matchContext(
        measurement: GlucoseMeasurement,
        context: GlucoseMeasurementContext?,
    ): GlucoseRecord? = when {
        measurement.contextFollows && context == null -> null
        !measurement.contextFollows && context != null -> null
        context != null && context.sequenceNumber != measurement.sequenceNumber -> null
        else -> GlucoseRecord(measurement, context)
    }

    private const val MINIMUM_MEASUREMENT_BYTES = 10
    private const val MINIMUM_CONTEXT_BYTES = 3
    private val VALID_TYPES = 1..10
    private val VALID_SAMPLE_LOCATIONS = setOf(1, 2, 3, 4, 15)
    private val VALID_CARBOHYDRATE_IDS = 1..7
    private val VALID_MEALS = 1..5
    private val VALID_TESTERS = setOf(1, 2, 3, 15)
    private val VALID_HEALTH_VALUES = setOf(1, 2, 3, 4, 5, 15)
    private val VALID_MEDICATION_IDS = 1..5

    private infix fun Int.has(flag: Int): Boolean = this and flag != 0
}

private class GlucoseCursor(private val payload: ByteArray) {
    private var offset = 0
    val atEnd: Boolean get() = offset == payload.size

    fun u8(): Int? {
        if (offset >= payload.size) return null
        return payload[offset++].toInt() and 0xFF
    }

    fun u16(): Int? {
        val low = u8() ?: return null
        val high = u8() ?: return null
        return low or (high shl 8)
    }

    fun s16(): Int? = u16()?.let { if (it and 0x8000 != 0) it - 0x1_0000 else it }

    fun dateTime(): LocalDateTime? = gattDateTime(::u16, ::u8)
}
