package com.pwnall.health

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.sqrt

/** Which sensor carries the repetition. */
enum class RepSignal {
    /** Accelerometer: the landing arrives as an impact spike in |a|, one spike per repetition. */
    IMPACT,

    /**
     * Gyroscope: the movement is a rotation of the limb the phone rides on, and |ω| peaks twice per
     * repetition — once going down, once coming back up.
     */
    ROTATION,

    /**
     * Accelerometer: the body is hauled up off a bar and the phone rides along. At the top of each
     * repetition the body slows and the phone goes briefly light, |a| dipping well under a g, once
     * per repetition. The dip is counted rather than the pull that starts it, which rings.
     */
    LIFT,
}

/**
 * Every threshold a repetition count depends on, per exercise.
 *
 * These are starting points, not measurements. How sharp a landing reads depends on the phone, the
 * pocket and the floor — the same jump on a concrete slab and on a mat differ by a factor of two —
 * and how far a knee turns depends on where the phone sits. Record a session with the trace
 * recorder, compare it against a count you made yourself, and retune here rather than editing the
 * loop.
 */
data class RepProfile(
    val signal: RepSignal,
    /** Two peaks closer together than this are one movement ringing, not two. */
    val minIntervalSeconds: Double,
    /** A longer gap than this is a rest rather than a slow repetition: it does not count towards cadence. */
    val maxIntervalSeconds: Double,
    /** A peak must clear the session's own quiet level by this much: m/s² for impact, rad/s for rotation. */
    val minPeakAboveRest: Double,
    /** How many standard deviations above the mean also counts as a peak. */
    val peakSigma: Double = 1.0,
    /**
     * Boxcar width in seconds — by the clock, not by the sample, so a 30 Hz recording and the 50 Hz
     * live count smooth alike. Wider kills sensor noise, too wide flattens the movement itself. The
     * default is three samples of the live count.
     */
    val smoothingSeconds: Double = 0.06,
    /** Peaks the signal shows per repetition. */
    val peaksPerRep: Int = 1,
    /**
     * A peak after which the phone rests more than this many degrees away from where it rested
     * before is not a repetition. A repetition brings the body back to where it began; the phone
     * going into a pocket or coming out of one — or the body climbing onto the bars — leaves it
     * pointing elsewhere. Null where the samples are not the accelerometer's or it is unmeasured.
     */
    val maxTurnDegrees: Double? = null,
)

/**
 * The exercises the phone can count, and how.
 *
 * Membership of this map is what makes an exercise countable — [ActivitySpec.counted] reads it — so
 * an entry here is all it takes, and it changes more than the counting: the exercise's form becomes
 * the rep counter instead of the set-by-set session, and it leaves the superset and "add another
 * exercise" pickers. Running on the spot is deliberately absent: the pedometer already counts those
 * steps, and a second counter would count them twice.
 */
val REP_PROFILES = mapOf(
    "JUMP_ROPE" to RepProfile(RepSignal.IMPACT, 0.22, 1.5, 2.5),
    "JUMPING_JACKS" to RepProfile(RepSignal.IMPACT, 0.35, 2.0, 2.0),
    "STAIRS" to RepProfile(RepSignal.IMPACT, 0.25, 1.5, 1.5),
    "BURPEES" to RepProfile(RepSignal.IMPACT, 1.0, 8.0, 3.0),
    "SQUATS" to RepProfile(RepSignal.ROTATION, 0.4, 6.0, 0.8, peaksPerRep = 2),
    "LUNGES" to RepProfile(RepSignal.ROTATION, 0.4, 6.0, 0.8, peaksPerRep = 2),
    "PUSHUPS" to RepProfile(RepSignal.ROTATION, 0.35, 5.0, 0.6, peaksPerRep = 2),
    // The heels coming down on the floor are the landing; rising onto the toes is too smooth to be a
    // second peak at this threshold. A slow, soft lowering lands below it — the count is asked after.
    "CALF_RAISE" to RepProfile(RepSignal.IMPACT, 0.5, 6.0, 1.0),
    // The thigh tilts up with the hips and back down, a slower turn than a squat's.
    "HIP_THRUST" to RepProfile(RepSignal.ROTATION, 0.5, 8.0, 0.5, peaksPerRep = 2),
    // Measured in a pocket, not guessed: 7 of 7 pull-ups and 6 × 10 of 6 × 10 knee raises on the
    // bars (RepTracesTest). The step counter made 14 and 64 steps of the same sets. A rep's top is
    // light for a third of a second and turns the phone 30° at most; the flick of jumping off the
    // bars lasts a twentieth, and the phone going into a pocket or out of one turns it 77–134°.
    "PULLUPS" to RepProfile(RepSignal.LIFT, 1.0, 6.0, 3.0, smoothingSeconds = 0.2, maxTurnDegrees = 45.0),
    "BAR_LEG_RAISE" to RepProfile(RepSignal.LIFT, 1.0, 6.0, 3.0, smoothingSeconds = 0.2, maxTurnDegrees = 45.0),
)

/** The exercise repeats one movement the sensors can follow. [REP_PROFILES] holds the thresholds. */
val ActivitySpec.counted: Boolean get() = code in REP_PROFILES

/**
 * [activeSeconds] counts only the gaps that look like the exercise, so a set with a two-minute rest
 * in the middle reports the cadence of the working part rather than an average over the pause.
 */
data class RepCount(
    val reps: Int,
    val activeSeconds: Double,
    val cadenceHz: Double,
    /** The highest jump in the set, in metres, or 0 where nothing left the ground. */
    val bestJumpM: Double = 0.0,
)

/** Free fall: airborne, the phone reads near nothing on every axis; on the ground it reads a g. */
private const val FREE_FALL_MS2 = 3.0

/** A jump is pushed off and landed on, and both ends of the flight show it. */
private const val CONTACT_MS2 = 14.0

/** How far either side of the flight to look for that contact. */
private const val CONTACT_WINDOW_SECONDS = 0.4

/** Shorter than this is noise between two footfalls; longer is a phone that was dropped. */
private const val MIN_FLIGHT_SECONDS = 0.15
private const val MAX_FLIGHT_SECONDS = 0.9

private const val GRAVITY_MS2 = 9.81

/**
 * The highest jump in an accelerometer recording, in metres, or 0 when nothing left the ground.
 *
 * Flight time, not double integration: integrating an accelerometer twice drifts into nonsense
 * inside a second, whereas the time the body spends in the air is a clean measurement of exactly
 * the thing being asked about. Rise and fall are symmetric, so a flight of `t` seconds is a jump of
 * `g·t²/8` — the same method a contact mat uses.
 *
 * The push-off check is what separates a jump from a dropped phone. A drop is also free fall, also
 * lands hard, and its `g·t²/8` is a plausible-looking number; only the missing push-off before the
 * flight tells the two apart.
 */
fun jumpHeightM(samples: List<Accel>): Double {
    if (samples.size < 3) return 0.0
    val period = medianPeriodSeconds(samples)
    if (period <= 0.0) return 0.0
    val contactSamples = ceil(CONTACT_WINDOW_SECONDS / period).toInt()

    var best = 0.0
    var airborneFrom = -1
    samples.indices.forEach { i ->
        val airborne = samples[i].magnitude < FREE_FALL_MS2
        if (airborne && airborneFrom < 0) airborneFrom = i
        // A run still open at the end of the recording never landed, so it was never a jump.
        if (!airborne && airborneFrom >= 0) {
            best = maxOf(best, jumpHeight(samples, airborneFrom, i - 1, period, contactSamples))
            airborneFrom = -1
        }
    }
    return best
}

private fun jumpHeight(
    samples: List<Accel>,
    from: Int,
    to: Int,
    period: Double,
    contactSamples: Int,
): Double {
    // N airborne samples span N-1 intervals, plus about half an interval of air at each end.
    val flight = (samples[to].t - samples[from].t) / 1e9 + period
    val jumped = flight in MIN_FLIGHT_SECONDS..MAX_FLIGHT_SECONDS &&
        contact(samples, from - contactSamples, from) &&
        contact(samples, to + 1, to + 1 + contactSamples)
    return if (jumped) GRAVITY_MS2 * flight * flight / 8.0 else 0.0
}

private fun contact(samples: List<Accel>, from: Int, until: Int): Boolean =
    (from.coerceAtLeast(0) until until.coerceAtMost(samples.size))
        .any { samples[it].magnitude >= CONTACT_MS2 }

private fun medianPeriodSeconds(samples: List<Accel>): Double {
    val gaps = (1 until samples.size).map { (samples[it].t - samples[it - 1].t) / 1e9 }.sorted()
    return gaps[gaps.size / 2]
}

/**
 * Counts repetitions in a three-axis sensor recording.
 *
 * Peak counting on the magnitude, not a frequency transform: a landing is an impulse and a squat is
 * a turn, and the useful question is how many arrived, not which frequency carried most energy. An
 * uneven tempo still produces one movement per repetition, which an FFT smears and a peak counter
 * does not.
 *
 * The same code serves the live counter and the offline trace analysis, so a threshold tuned against
 * a recording is the threshold the app then uses.
 */
object RepCounter {
    /**
     * [samples] carry whichever signal [RepProfile.signal] names: m/s² for [RepSignal.IMPACT],
     * rad/s for [RepSignal.ROTATION]. [Accel] is reused for both, because three axes and a
     * timestamp is all either needs.
     *
     * A set begun and ended by a tap was [handled] at both ends: its first and last seconds are not
     * counted ([withoutHandling]). They are still read to judge how far the phone turned around a peak
     * near the edge, which is exactly where the phone goes into the pocket and comes out of it.
     */
    fun count(samples: List<Accel>, profile: RepProfile, handled: Boolean = false): RepCount {
        val worked = if (handled) samples.withoutHandling() else samples
        if (worked.size < 3) return RepCount(0, 0.0, 0.0)
        val magnitudes = worked.map { it.magnitude.toDouble() }
        // A lift is counted on its dips, so the curve is turned over and the dips become the peaks.
        val curve = smooth(
            if (profile.signal == RepSignal.LIFT) magnitudes.map { -it } else magnitudes,
            worked,
            profile.smoothingSeconds,
        )
        val mean = curve.average()
        val sd = sqrt(curve.sumOf { (it - mean) * (it - mean) } / curve.size)
        // The median, not the mean, is the quiet level: the mean is dragged upwards by the very
        // peaks being counted, which raises the bar in proportion to how hard the person worked.
        val rest = curve.sorted()[curve.size / 2]
        val threshold = rest + maxOf(profile.minPeakAboveRest, profile.peakSigma * sd)

        // `>=` on the left and `>` on the right, so a flat top counts once and not once per sample.
        val peaks = (1 until curve.size - 1)
            .filter { curve[it] >= threshold && curve[it] >= curve[it - 1] && curve[it] > curve[it + 1] }
            .map { worked[it].t }

        // Peaks closer than the shortest repetition are one movement ringing, which its first stands for.
        val movements = mutableListOf<Long>()
        peaks.forEach { t ->
            if (movements.isEmpty() || (t - movements.last()) / 1e9 >= profile.minIntervalSeconds) movements += t
        }
        // Judged once the ringing is folded, not before: dropping a moved phone's first peak would let
        // its second stand in for it.
        val reps = profile.maxTurnDegrees
            ?.let { most -> movements.filter { turnDegrees(samples, it) <= most } }
            ?: movements
        val gaps = reps.zipWithNext { a, b -> (b - a) / 1e9 }.filter { it <= profile.maxIntervalSeconds }
        val active = gaps.sum()
        return RepCount(
            reps = reps.size / profile.peaksPerRep,
            activeSeconds = active,
            cadenceHz = if (active > 0) gaps.size / active / profile.peaksPerRep else 0.0,
            // Only for impact profiles: a rotation recording is rad/s, in which free fall means
            // nothing at all.
            bestJumpM = if (profile.signal == RepSignal.IMPACT) jumpHeightM(worked) else 0.0,
        )
    }

    /**
     * Reads the accelerometer samples back out of a [SensorTrace] CSV.
     *
     * Splits on commas without honouring quotes, which is safe here because only `S` rows are read
     * and every one of their fields is a number. The quoted fields live in the `M` metadata rows.
     *
     * Limitation: accelerometer only, because that is all the trace records. Tuning a
     * [RepSignal.ROTATION] profile offline needs gyroscope rows in the trace format first.
     */
    fun samplesFromTraceCsv(csv: String): List<Accel> {
        val lines = csv.lineSequence()
        val columns = lines.firstOrNull()?.split(',').orEmpty()
        val wanted = listOf("eventTimestampNanos", "x", "y", "z").map(columns::indexOf)
        return if (wanted.any { it < 0 }) {
            emptyList()
        } else {
            lines.drop(1)
                .filter { it.startsWith("S,") }
                .mapNotNull { accel(it.split(','), wanted) }
                .toList()
        }
    }

    private fun accel(cells: List<String>, wanted: List<Int>): Accel? {
        if (cells.size <= wanted.max()) return null
        val t = cells[wanted[0]].toLongOrNull() ?: return null
        val x = cells[wanted[1]].toFloatOrNull() ?: return null
        val y = cells[wanted[2]].toFloatOrNull() ?: return null
        val z = cells[wanted[3]].toFloatOrNull() ?: return null
        return Accel(t, x, y, z)
    }

    /**
     * Each value averaged with its neighbours within half of [seconds] of it. A clock that jumps — a
     * reboot, two files end to end — ends the window rather than stretching it across the jump.
     */
    private fun smooth(values: List<Double>, samples: List<Accel>, seconds: Double): List<Double> {
        val half = (seconds / 2 * 1e9).toLong()
        val sums = values.runningFold(0.0, Double::plus)
        return values.indices.map { i ->
            var from = i
            while (from > 0 && abs(samples[i].t - samples[from - 1].t) <= half) from--
            var to = i
            while (to < values.lastIndex && abs(samples[to + 1].t - samples[i].t) <= half) to++
            (sums[to + 1] - sums[from]) / (to - from + 1)
        }
    }

    /**
     * How far the phone turned across the peak at [t], in degrees: the way it pointed half a second to a
     * second before against the way it pointed as long after — the summed axes, which at rest are
     * gravity. Zero where either side falls outside the recording, so an edge is not judged.
     */
    private fun turnDegrees(samples: List<Accel>, t: Long): Double {
        val before = samples.pull(t - TURN_FAR_NANOS, t - TURN_NEAR_NANOS) ?: return 0.0
        val after = samples.pull(t + TURN_NEAR_NANOS, t + TURN_FAR_NANOS) ?: return 0.0
        val dot = before[0] * after[0] + before[1] * after[1] + before[2] * after[2]
        val lengths = sqrt(before.sumOf { it * it }) * sqrt(after.sumOf { it * it })
        return if (lengths > 0.0) Math.toDegrees(acos((dot / lengths).coerceIn(-1.0, 1.0))) else 0.0
    }

    /** The x, y and z of the samples in [from, until) summed, a direction; null for none there. */
    private fun List<Accel>.pull(from: Long, until: Long): DoubleArray? {
        var i = binarySearch { it.t.compareTo(from) }.let { if (it < 0) -it - 1 else it }
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var n = 0
        while (i < size && this[i].t < until) {
            x += this[i].x
            y += this[i].y
            z += this[i].z
            n++
            i++
        }
        return if (n > 0) doubleArrayOf(x, y, z) else null
    }
}

/** Either side of a peak, the phone's direction is read from this far away to [TURN_FAR_NANOS]. */
private const val TURN_NEAR_NANOS = 500_000_000L
private const val TURN_FAR_NANOS = 1_000_000_000L

/** How long the phone is in the hand after Start and before Stop. */
private const val HANDLING_NANOS = 3_000_000_000L

/**
 * The set without its first and last three seconds. Putting the phone into a pocket after Start and
 * taking it out before Stop shakes it like a repetition: three extra pull-ups on a set of seven,
 * measured, and two seconds still left one. The live count lags by the same three seconds, which
 * nobody watching a pocket notices.
 */
private fun List<Accel>.withoutHandling(): List<Accel> {
    if (isEmpty()) return this
    val kept = first().t + HANDLING_NANOS..last().t - HANDLING_NANOS
    return filter { it.t in kept }
}

/** Minutes a recording covers, for the duration field its count belongs to. */
fun List<Accel>.spanMinutes(): Double =
    if (size < 2) 0.0 else abs(last().t - first().t) / 60_000_000_000.0
