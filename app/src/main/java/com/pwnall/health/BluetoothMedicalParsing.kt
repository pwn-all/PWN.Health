package com.pwnall.health

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId

/** Bounds-checked little-endian reader shared only by the SIG medical-profile parsers. */
internal class BluetoothMedicalPacket(private val bytes: ByteArray) {
    private var offset = 0

    val exhausted: Boolean get() = offset == bytes.size

    fun u8(): Int {
        ensureAvailable(1)
        return bytes[offset++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun u24(): Int = u8() or (u8() shl 8) or (u8() shl 16)

    fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)

    fun sfloat(): Double? = Ieee11073.decodeSfloat(u16())

    fun float(): Double? = Ieee11073.decodeFloat(u32())

    /**
     * Bluetooth Date Time has no zone. The app applies the phone zone, just as its WSS importer
     * does. Unknown or malformed calendar fields do not erase a valid measurement, and neither does
     * a clock nobody ever set: null means the caller dates the reading on arrival and records that
     * the device's time was not believed.
     */
    @Suppress("ComplexCondition")
    fun deviceDateTimeEpochMillis(arrivalMillis: Long, zoneId: ZoneId): Long? {
        val year = u16()
        val month = u8()
        val day = u8()
        val hour = u8()
        val minute = u8()
        val second = u8()
        val calendarUnknown = year !in GATT_YEARS || month == 0 || day == 0
        if (calendarUnknown) return null
        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
                .takeIf { isPlausibleDeviceTime(it, arrivalMillis) }
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun ensureAvailable(octets: Int) {
        if (offset + octets > bytes.size) throw MalformedBluetoothMedicalPacket()
    }
}

internal class MalformedBluetoothMedicalPacket : RuntimeException()

/** The years Bluetooth's Date Time characteristic can hold as a date; 0 is "not known". */
internal val GATT_YEARS = 1582..9999

/**
 * Bluetooth's Date Time characteristic — a uint16 year and five uint8 fields — read through [u16]
 * and [u8], or null when the bytes run out or do not make a date. GSS: year 0 is "not known" and
 * only [GATT_YEARS] are dates. LocalDateTime.of accepts both year 0 and year 65535, so a device
 * that never had its clock set would otherwise be believed and its reading filed millennia away.
 */
internal inline fun gattDateTime(u16: () -> Int?, u8: () -> Int?): LocalDateTime? {
    val year = u16() ?: return null
    val month = u8() ?: return null
    val day = u8() ?: return null
    val hour = u8() ?: return null
    val minute = u8() ?: return null
    val second = u8() ?: return null
    if (year !in GATT_YEARS) return null
    return try {
        LocalDateTime.of(year, month, day, hour, minute, second)
    } catch (_: DateTimeException) {
        null
    }
}

/**
 * Whether a device's own clock can be believed against the phone's.
 *
 * A cuff, a scale or a thermometer fresh out of the box counts from 2000-01-01, and the profiles
 * give it no way to say so: the year is a year, the month and the day are real. Believing it would file
 * the reading a quarter of a century back, where the journal's today and the vitals card never
 * look, and hand Health Connect a record dated the same. Ten years behind covers a device set
 * once and never again; five minutes ahead covers two clocks that disagree about the minute
 * without either being wrong. Outside that, arrival time is the date and the payload says why.
 */
internal fun isPlausibleDeviceTime(deviceMillis: Long, arrivalMillis: Long): Boolean =
    deviceMillis >= arrivalMillis - MAX_DEVICE_CLOCK_BEHIND_MS &&
        deviceMillis <= arrivalMillis + MAX_DEVICE_CLOCK_AHEAD_MS

/** The same window for the profiles that keep the device's wall-clock fields rather than an instant. */
internal fun LocalDateTime.isPlausibleDeviceTime(arrivalMillis: Long, zoneId: ZoneId): Boolean =
    isPlausibleDeviceTime(atZone(zoneId).toInstant().toEpochMilli(), arrivalMillis)

private const val MAX_DEVICE_CLOCK_BEHIND_MS = 10L * 365 * 24 * 60 * 60 * 1_000
private const val MAX_DEVICE_CLOCK_AHEAD_MS = 5L * 60 * 1_000
