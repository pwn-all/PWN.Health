package com.pwnall.health

import java.util.UUID
import kotlin.math.roundToInt

enum class Concept2MachineKind {
    ROWER,
    SKIERG,
    BIKEERG,
    UNKNOWN,
}

/**
 * One partial PM5 status notification. The three official characteristics are emitted
 * independently, so absent values mean "carried by another packet", not zero.
 */
data class Concept2Pm5Measurement(
    val elapsedSeconds: Double? = null,
    val distanceMeters: Double? = null,
    val speedMps: Double? = null,
    val strokeRateSpm: Int? = null,
    val heartRateBpm: Int? = null,
    /** Distinguishes an absent field from PM5's 0xFF "not available" sentinel. */
    val heartRateFieldPresent: Boolean = false,
    val averagePowerWatts: Int? = null,
    val totalEnergyKcal: Int? = null,
    val dragFactor: Int? = null,
    val machineKind: Concept2MachineKind = Concept2MachineKind.UNKNOWN,
)

/**
 * Concept2 PM5 proprietary Bluetooth profile, published by Concept2 in its Bluetooth Smart
 * Communication Interface Definition v1.30. This is read-only: no CSAFE control commands and no
 * vendor app, account or network route.
 */
object BluetoothConcept2Pm5 {
    /**
     * What a PM5 puts in its advertisement: the base of its UUID range, not the rowing service
     * itself. The three services under it show only once the phone has connected and asked, so a
     * scan filtered on the rowing service alone would never see an erg at all.
     */
    val advertisedServiceUuid: UUID = concept2Uuid(0x0000)
    val deviceInformationServiceUuid: UUID = concept2Uuid(0x0010)
    val modelNumberUuid: UUID = concept2Uuid(0x0011)
    val serialNumberUuid: UUID = concept2Uuid(0x0012)
    val hardwareRevisionUuid: UUID = concept2Uuid(0x0013)
    val firmwareRevisionUuid: UUID = concept2Uuid(0x0014)
    val manufacturerNameUuid: UUID = concept2Uuid(0x0015)
    val serviceUuid: UUID = concept2Uuid(0x0030)
    val generalStatusUuid: UUID = concept2Uuid(0x0031)
    val additionalStatusOneUuid: UUID = concept2Uuid(0x0032)
    val additionalStatusTwoUuid: UUID = concept2Uuid(0x0033)

    fun parse(characteristicUuid: UUID, payload: ByteArray): Concept2Pm5Measurement? =
        when (characteristicUuid) {
            generalStatusUuid -> parseGeneralStatus(payload)
            additionalStatusOneUuid -> parseAdditionalStatusOne(payload)
            additionalStatusTwoUuid -> parseAdditionalStatusTwo(payload)
            else -> null
        }

    private fun parseGeneralStatus(payload: ByteArray): Concept2Pm5Measurement? {
        if (payload.size != GENERAL_STATUS_BYTES) return null
        return Concept2Pm5Measurement(
            elapsedSeconds = payload.u24(0) * CENTISECONDS,
            distanceMeters = payload.u24(3) * DECIMETRES,
            dragFactor = payload.u8(18),
        )
    }

    private fun parseAdditionalStatusOne(payload: ByteArray): Concept2Pm5Measurement? {
        if (payload.size != ADDITIONAL_STATUS_ONE_BYTES) return null
        return Concept2Pm5Measurement(
            elapsedSeconds = payload.u24(0) * CENTISECONDS,
            speedMps = payload.u16(3) * MILLIMETRES,
            strokeRateSpm = payload.u8(5),
            heartRateBpm = payload.u8(6).takeUnless { it == HEART_RATE_INVALID },
            heartRateFieldPresent = true,
            machineKind = machineKind(payload.u8(16)),
        )
    }

    private fun parseAdditionalStatusTwo(payload: ByteArray): Concept2Pm5Measurement? {
        if (payload.size != ADDITIONAL_STATUS_TWO_BYTES) return null
        return Concept2Pm5Measurement(
            elapsedSeconds = payload.u24(0) * CENTISECONDS,
            averagePowerWatts = payload.u16(4),
            totalEnergyKcal = payload.u16(6),
        )
    }

    private fun machineKind(raw: Int): Concept2MachineKind = when (raw) {
        in ROWER_MACHINE_TYPES -> Concept2MachineKind.ROWER
        in SKIERG_MACHINE_TYPES -> Concept2MachineKind.SKIERG
        in BIKEERG_MACHINE_TYPES -> Concept2MachineKind.BIKEERG
        else -> Concept2MachineKind.UNKNOWN
    }

    private const val GENERAL_STATUS_BYTES = 19
    private const val ADDITIONAL_STATUS_ONE_BYTES = 17
    private const val ADDITIONAL_STATUS_TWO_BYTES = 20
    private const val HEART_RATE_INVALID = 0xFF
    private const val CENTISECONDS = 0.01
    private const val DECIMETRES = 0.1
    private const val MILLIMETRES = 0.001
    private val ROWER_MACHINE_TYPES = setOf(
        0,
        1,
        2,
        3,
        5,
        7,
        8,
        16,
        17,
        18,
        19,
        20,
        32,
        64,
        224,
    )
    private val SKIERG_MACHINE_TYPES = setOf(128, 143, 225)
    private val BIKEERG_MACHINE_TYPES = setOf(192, 193, 194, 207, 226)
}

/** Combines the PM5's independent characteristics into the existing live-workout model. */
internal class Concept2Pm5Accumulator {
    private var elapsedSeconds: Double? = null
    private var distanceMeters: Double? = null
    private var speedMps: Double? = null
    private var strokeRateSpm: Int? = null
    private var heartRateBpm: Int? = null
    private var averagePowerWatts: Int? = null
    private var totalEnergyKcal: Int? = null
    private var machineKind = Concept2MachineKind.UNKNOWN

    fun update(partial: Concept2Pm5Measurement): FitnessMachineMeasurement {
        elapsedSeconds = partial.elapsedSeconds ?: elapsedSeconds
        distanceMeters = partial.distanceMeters ?: distanceMeters
        speedMps = partial.speedMps ?: speedMps
        strokeRateSpm = partial.strokeRateSpm ?: strokeRateSpm
        if (partial.heartRateFieldPresent) heartRateBpm = partial.heartRateBpm
        averagePowerWatts = partial.averagePowerWatts ?: averagePowerWatts
        totalEnergyKcal = partial.totalEnergyKcal ?: totalEnergyKcal
        if (partial.machineKind != Concept2MachineKind.UNKNOWN) machineKind = partial.machineKind

        return FitnessMachineMeasurement(
            type = when (machineKind) {
                Concept2MachineKind.BIKEERG -> FitnessMachineType.INDOOR_BIKE
                Concept2MachineKind.ROWER, Concept2MachineKind.SKIERG -> FitnessMachineType.ROWER
                Concept2MachineKind.UNKNOWN -> FitnessMachineType.UNKNOWN
            },
            speedKph = speedMps?.times(3.6),
            cadenceRpm = strokeRateSpm?.toDouble().takeIf {
                machineKind == Concept2MachineKind.BIKEERG
            },
            strokeRateSpm = strokeRateSpm?.toDouble().takeUnless {
                machineKind == Concept2MachineKind.BIKEERG
            },
            totalDistanceMeters = distanceMeters?.roundToInt(),
            powerWatts = averagePowerWatts,
            totalEnergyKcal = totalEnergyKcal,
            heartRateBpm = heartRateBpm,
            elapsedSeconds = elapsedSeconds?.roundToInt(),
        )
    }
}

private fun concept2Uuid(shortUuid: Int): UUID = UUID.fromString(
    "ce06${shortUuid.toString(16).padStart(4, '0')}-43e5-11e4-916c-0800200c9a66",
)

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF
private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
private fun ByteArray.u24(offset: Int): Int = u16(offset) or (u8(offset + 2) shl 16)
