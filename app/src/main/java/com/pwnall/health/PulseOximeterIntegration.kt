package com.pwnall.health

import java.time.ZoneId
import java.util.UUID

data class PulseOximeterMeasurement(
    val spo2Percent: Double?,
    val pulseRateBpm: Double?,
    val fastSpo2Percent: Double? = null,
    val fastPulseRateBpm: Double? = null,
    val slowSpo2Percent: Double? = null,
    val slowPulseRateBpm: Double? = null,
    val pulseAmplitudeIndexPercent: Double? = null,
    val measurementStatus: Int? = null,
    val deviceAndSensorStatus: Int? = null,
    val occurredAt: Long,
    val isSpotCheck: Boolean,
    val deviceClockSet: Boolean? = null,
) {
    /**
     * The oximeter's own verdict on its number. Measurement Status bit 13 says no measurement was
     * possible and bit 15 that what was measured is invalid; Device and Sensor Status bits 11 and
     * 15 say the sensor was off the person or off the device. A value the device itself disowns
     * does not reach the journal with a Save button under it.
     */
    val flaggedInvalid: Boolean
        get() = (measurementStatus ?: 0) and (MEASUREMENT_UNAVAILABLE or INVALID_MEASUREMENT) != 0 ||
            (deviceAndSensorStatus ?: 0) and (SENSOR_UNCONNECTED_TO_USER or SENSOR_DISCONNECTED) != 0

    private companion object {
        const val MEASUREMENT_UNAVAILABLE = 1 shl 13
        const val INVALID_MEASUREMENT = 1 shl 15
        const val SENSOR_UNCONNECTED_TO_USER = 1 shl 11
        const val SENSOR_DISCONNECTED = 1 shl 15
    }
}

/** Bluetooth SIG Pulse Oximeter Service 1.0.1 spot-check and continuous measurements. */
object BluetoothPulseOximeter {
    val serviceUuid: UUID = bluetoothUuid(0x1822)
    val spotCheckMeasurementUuid: UUID = bluetoothUuid(0x2A5E)
    val continuousMeasurementUuid: UUID = bluetoothUuid(0x2A5F)
    val featureUuid: UUID = bluetoothUuid(0x2A60)

    private const val SPOT_FLAG_TIMESTAMP = 0x01
    private const val SPOT_FLAG_MEASUREMENT_STATUS = 0x02
    private const val SPOT_FLAG_DEVICE_STATUS = 0x04
    private const val SPOT_FLAG_PULSE_AMPLITUDE_INDEX = 0x08
    private const val SPOT_FLAG_DEVICE_CLOCK_NOT_SET = 0x10

    private const val CONTINUOUS_FLAG_FAST = 0x01
    private const val CONTINUOUS_FLAG_SLOW = 0x02
    private const val CONTINUOUS_FLAG_MEASUREMENT_STATUS = 0x04
    private const val CONTINUOUS_FLAG_DEVICE_STATUS = 0x08
    private const val CONTINUOUS_FLAG_PULSE_AMPLITUDE_INDEX = 0x10

    private const val FLAGS_RFU = 0xE0
    private const val MEASUREMENT_STATUS_RFU = 0x001F
    private const val DEVICE_STATUS_RFU = 0xFF0000

    fun parse(
        characteristicUuid: UUID,
        payload: ByteArray,
        fallbackOccurredAt: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PulseOximeterMeasurement? = when (characteristicUuid) {
        spotCheckMeasurementUuid -> parseSpotCheck(payload, fallbackOccurredAt, zoneId)
        continuousMeasurementUuid -> parseContinuous(payload, fallbackOccurredAt)
        else -> null
    }

    @Suppress("CyclomaticComplexMethod")
    fun parseSpotCheck(
        payload: ByteArray,
        fallbackOccurredAt: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PulseOximeterMeasurement? = try {
        val packet = BluetoothMedicalPacket(payload)
        val flags = packet.u8()
        if (flags and FLAGS_RFU != 0) return null

        val spo2 = packet.sfloat().validPercent()
        val pulseRate = packet.sfloat().validPulseRate()
        val clockSet = flags and SPOT_FLAG_DEVICE_CLOCK_NOT_SET == 0
        val timestamped = flags and SPOT_FLAG_TIMESTAMP != 0
        val deviceTime = if (timestamped) {
            packet.deviceDateTimeEpochMillis(fallbackOccurredAt, zoneId)
        } else {
            null
        }
        val measurementStatus = if (flags and SPOT_FLAG_MEASUREMENT_STATUS != 0) {
            packet.measurementStatus()
        } else {
            null
        }
        val deviceStatus = if (flags and SPOT_FLAG_DEVICE_STATUS != 0) {
            packet.deviceStatus()
        } else {
            null
        }
        val pulseAmplitudeIndex = if (flags and SPOT_FLAG_PULSE_AMPLITUDE_INDEX != 0) {
            packet.sfloat().validPercent()
        } else {
            null
        }

        if (!packet.exhausted) return null
        PulseOximeterMeasurement(
            spo2Percent = spo2,
            pulseRateBpm = pulseRate,
            pulseAmplitudeIndexPercent = pulseAmplitudeIndex,
            measurementStatus = measurementStatus,
            deviceAndSensorStatus = deviceStatus,
            // A present timestamp from a clock explicitly marked "not set" is not trustworthy, and
            // neither is one the flag vouches for while the date itself says the clock was never set.
            occurredAt = if (clockSet) deviceTime ?: fallbackOccurredAt else fallbackOccurredAt,
            isSpotCheck = true,
            deviceClockSet = clockSet && (!timestamped || deviceTime != null),
        )
    } catch (_: MalformedBluetoothMedicalPacket) {
        null
    }

    @Suppress("CyclomaticComplexMethod")
    fun parseContinuous(
        payload: ByteArray,
        fallbackOccurredAt: Long = System.currentTimeMillis(),
    ): PulseOximeterMeasurement? = try {
        val packet = BluetoothMedicalPacket(payload)
        val flags = packet.u8()
        if (flags and FLAGS_RFU != 0) return null

        val spo2 = packet.sfloat().validPercent()
        val pulseRate = packet.sfloat().validPulseRate()
        val fast = if (flags and CONTINUOUS_FLAG_FAST != 0) packet.oximetryPair() else null
        val slow = if (flags and CONTINUOUS_FLAG_SLOW != 0) packet.oximetryPair() else null
        val measurementStatus = if (flags and CONTINUOUS_FLAG_MEASUREMENT_STATUS != 0) {
            packet.measurementStatus()
        } else {
            null
        }
        val deviceStatus = if (flags and CONTINUOUS_FLAG_DEVICE_STATUS != 0) {
            packet.deviceStatus()
        } else {
            null
        }
        val pulseAmplitudeIndex = if (flags and CONTINUOUS_FLAG_PULSE_AMPLITUDE_INDEX != 0) {
            packet.sfloat().validPercent()
        } else {
            null
        }

        if (!packet.exhausted) return null
        PulseOximeterMeasurement(
            spo2Percent = spo2,
            pulseRateBpm = pulseRate,
            fastSpo2Percent = fast?.first,
            fastPulseRateBpm = fast?.second,
            slowSpo2Percent = slow?.first,
            slowPulseRateBpm = slow?.second,
            pulseAmplitudeIndexPercent = pulseAmplitudeIndex,
            measurementStatus = measurementStatus,
            deviceAndSensorStatus = deviceStatus,
            occurredAt = fallbackOccurredAt,
            isSpotCheck = false,
        )
    } catch (_: MalformedBluetoothMedicalPacket) {
        null
    }

    private fun BluetoothMedicalPacket.oximetryPair(): Pair<Double?, Double?> =
        sfloat().validPercent() to sfloat().validPulseRate()

    private fun BluetoothMedicalPacket.measurementStatus(): Int = u16().also {
        if (it and MEASUREMENT_STATUS_RFU != 0) throw MalformedBluetoothMedicalPacket()
    }

    private fun BluetoothMedicalPacket.deviceStatus(): Int = u24().also {
        if (it and DEVICE_STATUS_RFU != 0) throw MalformedBluetoothMedicalPacket()
    }

    private fun Double?.validPercent(): Double? {
        if (this != null && this !in 0.0..100.0) throw MalformedBluetoothMedicalPacket()
        return this
    }

    private fun Double?.validPulseRate(): Double? {
        if (this != null && this < 0.0) throw MalformedBluetoothMedicalPacket()
        return this
    }
}
