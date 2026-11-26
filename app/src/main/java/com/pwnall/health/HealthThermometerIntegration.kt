package com.pwnall.health

import java.time.ZoneId
import java.util.UUID

enum class TemperatureUnit {
    CELSIUS,
    FAHRENHEIT,
}

enum class TemperatureType(val wireValue: Int) {
    ARMPIT(1),
    BODY(2),
    EAR(3),
    FINGER(4),
    GASTROINTESTINAL_TRACT(5),
    MOUTH(6),
    RECTUM(7),
    TOE(8),
    TYMPANUM(9),
    ;

    companion object {
        fun fromWireValue(value: Int): TemperatureType? = entries.firstOrNull { it.wireValue == value }
    }
}

data class TemperatureMeasurement(
    val temperatureCelsius: Double?,
    val sourceUnit: TemperatureUnit,
    val type: TemperatureType?,
    val occurredAt: Long,
    /** null when the thermometer sent no time; false when it sent one this app declined to believe. */
    val deviceClockSet: Boolean? = null,
) {
    /**
     * A measurement that named no site takes the one the thermometer published for all of them.
     * The vitals card grades an ear or armpit reading against a different band than a core one,
     * so a site that is known and not carried is a wrong verdict, not a missing label.
     */
    fun withDefaultType(default: TemperatureType?): TemperatureMeasurement =
        if (type != null || default == null) this else copy(type = default)
}

/** Bluetooth SIG Health Thermometer Service, Temperature Measurement characteristic 0x2A1C. */
object BluetoothHealthThermometer {
    val serviceUuid: UUID = bluetoothUuid(0x1809)
    val measurementUuid: UUID = bluetoothUuid(0x2A1C)
    val temperatureTypeUuid: UUID = bluetoothUuid(0x2A1D)

    private const val FLAG_FAHRENHEIT = 0x01
    private const val FLAG_TIMESTAMP = 0x02
    private const val FLAG_TEMPERATURE_TYPE = 0x04
    private const val FLAG_RFU = 0xF8

    fun parse(
        payload: ByteArray,
        fallbackOccurredAt: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TemperatureMeasurement? = try {
        val packet = BluetoothMedicalPacket(payload)
        val flags = packet.u8()
        if (flags and FLAG_RFU != 0) return null

        val sourceUnit = if (flags and FLAG_FAHRENHEIT != 0) {
            TemperatureUnit.FAHRENHEIT
        } else {
            TemperatureUnit.CELSIUS
        }
        val sourceTemperature = packet.float()
        val temperatureCelsius = sourceTemperature?.let {
            if (sourceUnit == TemperatureUnit.FAHRENHEIT) (it - 32.0) * 5.0 / 9.0 else it
        }
        val timestamped = flags and FLAG_TIMESTAMP != 0
        val deviceTime = if (timestamped) {
            packet.deviceDateTimeEpochMillis(fallbackOccurredAt, zoneId)
        } else {
            null
        }
        val type = if (flags and FLAG_TEMPERATURE_TYPE != 0) {
            TemperatureType.fromWireValue(packet.u8()) ?: return null
        } else {
            null
        }

        if (!packet.exhausted) return null
        TemperatureMeasurement(
            temperatureCelsius = temperatureCelsius,
            sourceUnit = sourceUnit,
            type = type,
            occurredAt = deviceTime ?: fallbackOccurredAt,
            deviceClockSet = if (timestamped) deviceTime != null else null,
        )
    } catch (_: MalformedBluetoothMedicalPacket) {
        null
    }

    /**
     * HTS 3.2: a thermometer whose site never changes publishes it once in the Temperature Type
     * characteristic and leaves the type flag clear in every measurement. One octet, the same
     * enumeration as the in-measurement field; anything else is not a site.
     */
    fun parseTemperatureType(payload: ByteArray): TemperatureType? =
        payload.singleOrNull()?.let { TemperatureType.fromWireValue(it.toInt() and 0xFF) }
}
