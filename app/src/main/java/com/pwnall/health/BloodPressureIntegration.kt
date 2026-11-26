package com.pwnall.health

import java.time.ZoneId
import java.util.UUID

enum class BloodPressureUnit {
    MMHG,
    KPA,
}

data class BloodPressureMeasurement(
    val systolic: Double?,
    val diastolic: Double?,
    val meanArterialPressure: Double?,
    val unit: BloodPressureUnit,
    val pulseRateBpm: Double?,
    val userId: Int?,
    val measurementStatus: Int?,
    val occurredAt: Long,
    /** null when the cuff sent no time; false when it sent one this app declined to believe. */
    val deviceClockSet: Boolean? = null,
) {
    val systolicMmhg: Double? get() = systolic?.toMmhg()
    val diastolicMmhg: Double? get() = diastolic?.toMmhg()
    val meanArterialPressureMmhg: Double? get() = meanArterialPressure?.toMmhg()

    /**
     * Measurement Status as words. The cuff can be right about the pressure while the arm moved,
     * the cuff sat loose or the pulse was irregular, so none of these blocks a save: they travel
     * with the reading, where a later reader can weigh them, instead of being decoded and dropped.
     */
    val qualityFlags: List<String>
        get() {
            val status = measurementStatus ?: return emptyList()
            return buildList {
                if (status and STATUS_BODY_MOVEMENT != 0) add("bodyMovement")
                if (status and STATUS_CUFF_TOO_LOOSE != 0) add("cuffTooLoose")
                if (status and STATUS_IRREGULAR_PULSE != 0) add("irregularPulse")
                when ((status ushr STATUS_PULSE_RANGE_SHIFT) and STATUS_PULSE_RANGE_MASK) {
                    PULSE_RATE_ABOVE_UPPER_LIMIT -> add("pulseRateAboveLimit")
                    PULSE_RATE_BELOW_LOWER_LIMIT -> add("pulseRateBelowLimit")
                }
                if (status and STATUS_IMPROPER_POSITION != 0) add("improperPosition")
            }
        }

    private fun Double.toMmhg(): Double =
        if (unit == BloodPressureUnit.KPA) this * MMHG_PER_KPA else this

    private companion object {
        const val MMHG_PER_KPA = 7.500_616_827_041_698
        const val STATUS_BODY_MOVEMENT = 0x01
        const val STATUS_CUFF_TOO_LOOSE = 0x02
        const val STATUS_IRREGULAR_PULSE = 0x04
        const val STATUS_PULSE_RANGE_SHIFT = 3
        const val STATUS_PULSE_RANGE_MASK = 0x03
        const val PULSE_RATE_ABOVE_UPPER_LIMIT = 1
        const val PULSE_RATE_BELOW_LOWER_LIMIT = 2
        const val STATUS_IMPROPER_POSITION = 0x20
    }
}

/** Bluetooth SIG Blood Pressure Service, Blood Pressure Measurement characteristic 0x2A35. */
object BluetoothBloodPressure {
    val serviceUuid: UUID = bluetoothUuid(0x1810)
    val measurementUuid: UUID = bluetoothUuid(0x2A35)
    val featureUuid: UUID = bluetoothUuid(0x2A49)

    private const val FLAG_KPA = 0x01
    private const val FLAG_TIMESTAMP = 0x02
    private const val FLAG_PULSE_RATE = 0x04
    private const val FLAG_USER_ID = 0x08
    private const val FLAG_MEASUREMENT_STATUS = 0x10
    private const val FLAG_RFU = 0xE0

    private const val STATUS_PULSE_RANGE_SHIFT = 3
    private const val STATUS_PULSE_RANGE_MASK = 0x03
    private const val STATUS_PULSE_RANGE_RESERVED = 0x03
    private const val STATUS_RFU = 0xFFC0
    private const val UNKNOWN_USER_ID = 0xFF

    @Suppress("CyclomaticComplexMethod")
    fun parse(
        payload: ByteArray,
        fallbackOccurredAt: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): BloodPressureMeasurement? = try {
        val packet = BluetoothMedicalPacket(payload)
        val flags = packet.u8()
        if (flags and FLAG_RFU != 0) return null

        val systolic = packet.sfloat().validPressure()
        val diastolic = packet.sfloat().validPressure()
        val meanArterialPressure = packet.sfloat().validPressure()

        val timestamped = flags and FLAG_TIMESTAMP != 0
        val deviceTime = if (timestamped) {
            packet.deviceDateTimeEpochMillis(fallbackOccurredAt, zoneId)
        } else {
            null
        }
        val pulseRate = if (flags and FLAG_PULSE_RATE != 0) {
            packet.sfloat().validPulseRate()
        } else {
            null
        }
        val userId = if (flags and FLAG_USER_ID != 0) {
            packet.u8().takeUnless { it == UNKNOWN_USER_ID }
        } else {
            null
        }
        val status = if (flags and FLAG_MEASUREMENT_STATUS != 0) {
            packet.u16().also {
                if (it and STATUS_RFU != 0) throw MalformedBluetoothMedicalPacket()
                if ((it ushr STATUS_PULSE_RANGE_SHIFT) and STATUS_PULSE_RANGE_MASK ==
                    STATUS_PULSE_RANGE_RESERVED
                ) {
                    throw MalformedBluetoothMedicalPacket()
                }
            }
        } else {
            null
        }

        if (!packet.exhausted) return null
        BloodPressureMeasurement(
            systolic = systolic,
            diastolic = diastolic,
            meanArterialPressure = meanArterialPressure,
            unit = if (flags and FLAG_KPA != 0) BloodPressureUnit.KPA else BloodPressureUnit.MMHG,
            pulseRateBpm = pulseRate,
            userId = userId,
            measurementStatus = status,
            occurredAt = deviceTime ?: fallbackOccurredAt,
            deviceClockSet = if (timestamped) deviceTime != null else null,
        )
    } catch (_: MalformedBluetoothMedicalPacket) {
        null
    }

    /** Special SFLOAT values are null; an ordinary negative pressure is a malformed reading. */
    private fun Double?.validPressure(): Double? {
        if (this != null && this < 0.0) throw MalformedBluetoothMedicalPacket()
        return this
    }

    private fun Double?.validPulseRate(): Double? {
        if (this != null && this < 0.0) throw MalformedBluetoothMedicalPacket()
        return this
    }
}
