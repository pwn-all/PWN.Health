package com.pwnall.health

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

/**
 * What a reading taken off a device puts into a form, and where it says it came from.
 *
 * Deliberately smaller than any of the profile measurements below: a form asks for one or two
 * numbers, and this is the shape of what a scale or a strap is allowed to answer with.
 */
internal data class DeviceReading(
    val weightKg: Double? = null,
    val heightCm: Int? = null,
    val heartRateBpm: Int? = null,
    val bodyFatPercent: Double? = null,
    val source: String,
)

data class StandardScaleMeasurement(
    val weightKg: Double,
    val heightCm: Int?,
    val bmi: Double?,
    val userId: Int?,
    val occurredAt: Long,
    val impedanceOhms: Int? = null,
    val isStable: Boolean = true,
    val sourceModel: String? = null,
    /** null when the scale sent no time; false when it sent one this app declined to believe. */
    val deviceClockSet: Boolean? = null,
)

/**
 * Packet-level boundary for direct device adapters. Android discovery/GATT code can select an
 * adapter by advertised service UUID without knowing anything about its packet format.
 */
interface BluetoothMeasurementAdapter {
    val id: String
    val advertisedServiceUuids: Set<UUID>

    /** True only when the service-data payload itself is a documented measurement transport. */
    val parsesAdvertisementData: Boolean get() = false

    fun supports(deviceName: String?, serviceUuids: Collection<UUID>): Boolean =
        serviceUuids.any(advertisedServiceUuids::contains)

    fun parse(
        payload: ByteArray,
        fallbackOccurredAt: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): StandardScaleMeasurement?
}

/**
 * Bluetooth SIG Weight Scale Service v1.0.1, characteristic Weight Measurement (0x2A9D).
 *
 * This parser is intentionally independent from Android Bluetooth APIs so captured packets can be
 * regression-tested. The runtime GATT collector passes only complete indication values here.
 */
object BluetoothWeightScale : BluetoothMeasurementAdapter {
    override val id: String = "bluetooth_sig_weight_scale"
    val serviceUuid: UUID = bluetoothUuid(0x181D)
    val measurementUuid: UUID = bluetoothUuid(0x2A9D)
    val bodyCompositionServiceUuid: UUID = bluetoothUuid(0x181B)
    override val advertisedServiceUuids: Set<UUID> = setOf(serviceUuid)

    // One early return per malformed or absent field in the advertised packet.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    override fun parse(
        payload: ByteArray,
        fallbackOccurredAt: Long,
        zoneId: ZoneId,
    ): StandardScaleMeasurement? {
        if (payload.size < 3) return null
        val flags = payload.u8(0)
        // WSS 1.0.1 defines only bits 0..3. Xiaomi v1 reuses RFU bits for stabilization, so a
        // packet with any high bit set must go through its model adapter, never the generic path.
        if (flags and 0xF0 != 0) return null
        val imperial = flags and 0x01 != 0
        val rawWeight = payload.u16(1)
        if (rawWeight == 0xFFFF) return null
        var offset = 3
        var occurredAt = fallbackOccurredAt
        var deviceClockSet: Boolean? = null

        if (flags and 0x02 != 0) {
            if (payload.size < offset + 7) return null
            val deviceTime = payload.gattDeviceTimeMillis(offset, zoneId, fallbackOccurredAt)
            deviceClockSet = deviceTime != null
            occurredAt = deviceTime ?: fallbackOccurredAt
            offset += 7
        }

        val userId = if (flags and 0x04 != 0) {
            if (payload.size <= offset) return null
            // 0xFF is the defined "unknown user" value, not user number 255.
            payload.u8(offset++).takeUnless { it == UNKNOWN_USER_ID }
        } else {
            null
        }

        var bmi: Double? = null
        var heightCm: Int? = null
        if (flags and 0x08 != 0) {
            if (payload.size < offset + 4) return null
            bmi = payload.u16(offset) * 0.1
            val rawHeight = payload.u16(offset + 2)
            // Nearest centimetre, not the one below: truncation would turn 66.9 in into 169 cm.
            heightCm = if (imperial) {
                (rawHeight * 0.1 * CENTIMETRES_IN_AN_INCH).roundToInt()
            } else {
                (rawHeight * 0.1).roundToInt()
            }
            offset += 4
        }

        if (offset != payload.size) return null

        val weightKg = if (imperial) {
            rawWeight * 0.01 * KILOGRAMS_IN_A_POUND
        } else {
            rawWeight * 0.005
        }
        if (weightKg !in SCALE_READING_KG) return null
        return StandardScaleMeasurement(
            weightKg,
            heightCm,
            bmi,
            userId,
            occurredAt,
            deviceClockSet = deviceClockSet,
        )
    }
}

/**
 * The scale profiles define one sentinel (0xFFFF) and no range at all, so this bound is ours, not
 * the spec's. It exists to keep a decoding slip off the chart, and the floor sits below a newborn
 * so a real person is never rejected — an infant on a bathroom scale is a plausible weighing.
 *
 * Wider than [PLAUSIBLE_KG] on purpose, and named apart from it: what the scale said can be kept as
 * a row, while only a weighing inside [PLAUSIBLE_KG] becomes the weight the app counts with.
 */
private val SCALE_READING_KG = 2.0..MAX_PLAUSIBLE_KG

/** GSS User Index: 0xFF is "unknown user". */
private const val UNKNOWN_USER_ID = 0xFF

/** GSS Date Time: year 0 means "not known" and only 1582..9999 is a date at all. */
private val VALID_GATT_YEARS = 1582..9999

/**
 * Seven-octet GSS Date Time at [offset], or null when there is nothing to believe. A year, month
 * or day of zero means "not known". A scale that has never been set to the wall clock says exactly
 * that, and dropping the packet over it would throw away a perfectly good weighing, so the caller
 * dates it on arrival instead. LocalDateTime.of accepts year 0 and every other value a uint16 holds,
 * hence the explicit range; a scale counting from 2000-01-01 is caught by [isPlausibleDeviceTime].
 */
private fun ByteArray.gattDeviceTimeMillis(offset: Int, zoneId: ZoneId, arrivalMillis: Long): Long? {
    val year = u16(offset)
    val month = u8(offset + 2)
    val day = u8(offset + 3)
    if (year !in VALID_GATT_YEARS || month == 0 || day == 0) return null
    return try {
        LocalDateTime.of(year, month, day, u8(offset + 4), u8(offset + 5), u8(offset + 6))
            .atZone(zoneId).toInstant().toEpochMilli()
            .takeIf { isPlausibleDeviceTime(it, arrivalMillis) }
    } catch (_: DateTimeException) {
        null
    }
}

/**
 * Xiaomi Mi Smart Scale v1 adds stabilization and weight-removed bits to an otherwise standard
 * Weight Scale Service packet. The generic decoder supplies units/timestamp while this adapter
 * prevents transient or post-measurement values from being saved.
 */
object XiaomiMiScaleV1 : BluetoothMeasurementAdapter {
    override val id: String = "xiaomi_mi_scale_v1"
    override val advertisedServiceUuids: Set<UUID> = setOf(bluetoothUuid(0x181D))
    override val parsesAdvertisementData: Boolean = true

    override fun supports(deviceName: String?, serviceUuids: Collection<UUID>): Boolean {
        val normalizedName = deviceName?.replace("_", " ")?.uppercase() ?: return false
        return (normalizedName.startsWith("MISCA") || normalizedName.startsWith("MI SCALE")) &&
            serviceUuids.any(advertisedServiceUuids::contains)
    }

    override fun parse(
        payload: ByteArray,
        fallbackOccurredAt: Long,
        zoneId: ZoneId,
    ): StandardScaleMeasurement? {
        if (payload.isEmpty()) return null
        val control = payload.u8(0)
        // Strip Xiaomi's stabilization/weight-removed bits before handing the standard WSS fields
        // to the strict generic decoder. No other caller may silently accept those RFU bits.
        val standardPayload = payload.copyOf().also { it[0] = (control and 0x0F).toByte() }
        val decoded = BluetoothWeightScale.parse(standardPayload, fallbackOccurredAt, zoneId) ?: return null
        val stable = control and 0x20 != 0
        val weightRemoved = control and 0x80 != 0
        return decoded.copy(
            isStable = stable && !weightRemoved,
            sourceModel = "Xiaomi Mi Smart Scale v1",
        )
    }
}

/**
 * Xiaomi Mi Body Composition Scale v2 (XMTZC02HM / XMTZC05HM / NUN4049CN).
 *
 * It advertises under the SIG Body Composition service UUID and borrows SIG flag *numbering*, but
 * the payload is not a Body Composition Measurement: it is a **fixed 13-byte** record with no
 * optional fields, and it omits the mandatory Body Fat Percentage that a real 0x2A9C packet must
 * carry. Read as a flag-driven SIG frame, every measurement would land in the impedance slot — a
 * real 105.8 kg weighing would decode as zero and be thrown away as implausible.
 *
 * Layout, offsets fixed: `[0]` control, `[1]` control, `[2..8]` timestamp, `[9..10]` impedance,
 * `[11..12]` weight. Units come from control byte 0; stabilized, weight-removed and whether the
 * impedance bytes hold a measurement from byte 1.
 *
 * No Xiaomi specification exists — this is community reverse-engineering, cross-checked against
 * Bluetooth-Devices/xiaomi-ble `_parse_scale_v2`, which likewise rejects any length but 13.
 *
 * S400 (MJTZC01YM / MJTZC03YM) uses encrypted MiBeacon V5 needing a per-device bindkey that is only
 * obtainable through the Xiaomi cloud, so it is out of scope. The S800 protocol is unknown; do not
 * assume it is the same.
 */
object XiaomiMiScaleV2 : BluetoothMeasurementAdapter {
    /** Fixed by the format. xiaomi-ble rejects anything else, and so does this. */
    private const val PACKET_BYTES = 13

    private const val CONTROL_LBS = 0x01 // byte 0, bit 0
    private const val CONTROL_IMPEDANCE = 0x02 // byte 1, bit 1
    private const val CONTROL_STABILIZED = 0x20 // byte 1, bit 5
    private const val CONTROL_WEIGHT_REMOVED = 0x80 // byte 1, bit 7

    override val id: String = "xiaomi_mi_scale_v2"
    override val advertisedServiceUuids: Set<UUID> = setOf(bluetoothUuid(0x181B))
    override val parsesAdvertisementData: Boolean = true

    override fun supports(deviceName: String?, serviceUuids: Collection<UUID>): Boolean {
        if (!serviceUuids.any(advertisedServiceUuids::contains)) return false
        // 0x181B is also the adopted SIG Body Composition Service. Treating every unnamed BCS
        // peripheral as Xiaomi would turn an unrelated medical device into a vendor scale. A
        // missed unnamed packet is safer than a false measurement; exact models are recognised by
        // their known local-name prefixes and can be expanded only with captured evidence.
        val name = deviceName ?: return false
        return name.startsWith("MIBFS", ignoreCase = true) || name.startsWith("MIBCS", ignoreCase = true)
    }

    override fun parse(
        payload: ByteArray,
        fallbackOccurredAt: Long,
        zoneId: ZoneId,
    ): StandardScaleMeasurement? {
        if (payload.size != PACKET_BYTES) return null
        val units = payload.u8(0)
        val control = payload.u8(1)

        val rawWeight = payload.u16(11)
        val weightKg = if (units and CONTROL_LBS != 0) {
            rawWeight * 0.01 * KILOGRAMS_IN_A_POUND
        } else {
            // Both catty and kilograms land on the same factor: the scale reports 0.01 jin in one
            // case and 0.005 kg in the other, and one jin is half a kilogram.
            rawWeight * 0.005
        }
        if (weightKg !in SCALE_READING_KG) return null

        // A scale that has lost its clock sends zeroes here; that is not a reason to drop the
        // weight. The layout of bytes 2..8 is the same GSS Date Time the WSS packet carries.
        val deviceTime = payload.gattDeviceTimeMillis(2, zoneId, fallbackOccurredAt)
        return StandardScaleMeasurement(
            weightKg = weightKg,
            heightCm = null,
            bmi = null,
            userId = null,
            occurredAt = deviceTime ?: fallbackOccurredAt,
            deviceClockSet = deviceTime != null,
            // Whole ohms, unlike the SIG field. Do not feed this to a 0x2A9C consumer unconverted.
            // Read only when the scale says it has measured one: it advertises the stable weight
            // first, and what the two bytes hold until then is not a figure xiaomi-ble or openScale
            // will take either, and read early it would go into the body-fat estimate.
            impedanceOhms = payload.u16(9)
                .takeIf { control and CONTROL_IMPEDANCE != 0 }
                ?.takeUnless { it == 0 || it == 0xFFFF },
            isStable = control and CONTROL_STABILIZED != 0 &&
                control and CONTROL_WEIGHT_REMOVED == 0,
            sourceModel = "Xiaomi Mi Body Composition Scale v2",
        )
    }
}

val BLUETOOTH_MEASUREMENT_ADAPTERS: List<BluetoothMeasurementAdapter> =
    listOf(XiaomiMiScaleV2, XiaomiMiScaleV1, BluetoothWeightScale)

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF
private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

internal fun bluetoothUuid(shortUuid: Int): UUID =
    UUID.fromString("0000${shortUuid.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb")
