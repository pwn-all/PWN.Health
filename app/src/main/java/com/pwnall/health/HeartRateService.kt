package com.pwnall.health

import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class HeartRateMeasurement(
    val bpm: Int,
    /** RR intervals in milliseconds, when the strap reports them. The raw material for HRV. */
    val rrIntervalsMs: List<Int> = emptyList(),
    val energyExpendedKj: Int? = null,
    /** null when the sensor does not report contact at all, which is common and not an error. */
    val contact: Boolean? = null,
)

/**
 * RMSSD over the RR intervals a strap has reported, or null where there are too few to mean
 * anything.
 *
 * The root mean square of successive differences is the one heart-rate-variability figure that can
 * be computed honestly from what the Bluetooth Heart Rate Service gives: beat-to-beat intervals,
 * and nothing about breathing, posture or how long the person had been sitting. It is what
 * short-recording guidance uses for exactly that reason — it is the time-domain measure least
 * disturbed by a recording that runs a minute rather than five.
 *
 * Three rules, all of them about not inventing a number:
 *
 * 1. **An interval outside [PLAUSIBLE_RR_MS] is not a beat.** 300 to 2000 ms is 30 to 200 bpm; what
 *    falls outside is a dropped or doubled detection, and squaring it would move RMSSD more than
 *    every real difference in the recording put together.
 * 2. **A difference is only taken across two intervals that both survived that gate**, so the gap
 *    left by a discarded beat is not counted as a difference of its own.
 * 3. **Under [MIN_RR_INTERVALS] there is no answer at all.** Roughly half a minute of beats is the
 *    floor below which the figure says more about which thirty seconds were caught than about the
 *    person, and a number that unstable is worse than a blank.
 */
fun rmssdMs(intervalsMs: List<Int>): Double? {
    var sumOfSquares = 0.0
    var differences = 0
    var previous: Int? = null
    var counted = 0
    intervalsMs.forEach { interval ->
        if (interval !in PLAUSIBLE_RR_MS) {
            previous = null
            return@forEach
        }
        counted++
        previous?.let { earlier ->
            val difference = (interval - earlier).toDouble()
            sumOfSquares += difference * difference
            differences++
        }
        previous = interval
    }
    if (counted < MIN_RR_INTERVALS || differences == 0) return null
    return sqrt(sumOfSquares / differences)
}

/** 30 to 200 bpm as an interval. Outside it a reading is a detection artefact, not a beat. */
private val PLAUSIBLE_RR_MS = 300..2_000

/** About half a minute of beats: below it the figure is a property of the sample, not the person. */
private const val MIN_RR_INTERVALS = 30

/**
 * Bluetooth SIG Heart Rate Service 0x180D.
 *
 * A chest strap is the only way this app can see a real pulse without a phone camera or a vendor
 * cloud, and the profile is small enough to decode exactly: one flags byte, then the fields its bits
 * announce. Same shape as the weight-scale and fitness-machine decoders — no Android types in here,
 * so every packet layout is a unit test.
 */
object BluetoothHeartRate {
    val serviceUuid: UUID = bluetoothUuid(0x180D)
    val measurementUuid: UUID = bluetoothUuid(0x2A37)

    /** No upper bound in the profile; this one only keeps a decoding slip out of the journal. */
    private const val MAX_PLAUSIBLE_BPM = 300

    private const val FLAG_UINT16 = 0x01
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY_PRESENT = 0x08
    private const val FLAG_RR_PRESENT = 0x10

    // One early return per field the flags promised and the packet did not deliver.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun parse(payload: ByteArray): HeartRateMeasurement? {
        if (payload.size < 2) return null
        val flags = payload[0].toInt() and 0xFF
        val wide = flags and FLAG_UINT16 != 0

        var offset = 1
        if (wide && payload.size < 3) return null
        val bpm = if (wide) {
            (payload.u8(offset) or (payload.u8(offset + 1) shl 8)).also { offset += 2 }
        } else {
            payload.u8(offset).also { offset += 1 }
        }
        // 0 bpm is what a strap reports when it is not touching skin, together with a cleared
        // contact bit. It is a legitimate reading, and the packet carrying it may still hold the RR
        // intervals HRV is derived from — dropping the whole notification would throw those away too.
        if (bpm !in 0..MAX_PLAUSIBLE_BPM) return null

        var energy: Int? = null
        if (flags and FLAG_ENERGY_PRESENT != 0) {
            if (payload.size < offset + 2) return null
            energy = payload.u8(offset) or (payload.u8(offset + 1) shl 8)
            offset += 2
        }

        val rr = mutableListOf<Int>()
        if (flags and FLAG_RR_PRESENT != 0) {
            if ((payload.size - offset) % 2 != 0) return null
            while (payload.size >= offset + 2) {
                // RR is in 1/1024 s units, not milliseconds. Truncating the conversion biases every
                // interval a millisecond short, which is not noise: HRV is computed from the
                // differences between consecutive intervals, so a one-sided error survives the
                // whole aggregation. Round instead.
                val raw = payload.u8(offset) or (payload.u8(offset + 1) shl 8)
                rr += (raw * 1_000.0 / 1_024.0).roundToInt()
                offset += 2
            }
        } else if (offset != payload.size) {
            return null
        }

        val contact = if (flags and FLAG_CONTACT_SUPPORTED != 0) {
            flags and FLAG_CONTACT_DETECTED != 0
        } else {
            null
        }

        return HeartRateMeasurement(bpm, rr, energy, contact)
    }
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
