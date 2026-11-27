package com.pwnall.health

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A spoken-with-beeps 3…2…1: enough to put the phone down without making the start feel remote. */
const val HEARTBEAT_COUNTDOWN_SECONDS = 3

/**
 * The shortest the phone will listen for. [Ballistocardiogram.Config] takes a median over
 * overlapping windows, so this has to cover several of them — `HeartbeatTest` checks that it does.
 */
const val HEARTBEAT_MEASURE_SECONDS = 30

/**
 * The longest it will keep listening when the first half-minute produced nothing.
 *
 * Listening for as long as it takes rather than a fixed stretch, because how long it takes depends
 * on where the phone is: on a chest, half a minute is plenty, while a phone pinned underneath
 * someone lying on it reads a signal several times fainter and has needed the full minute. Making
 * everyone wait the worst case would be a minute of lying still to log one number.
 */
const val HEARTBEAT_MAX_MEASURE_SECONDS = 60

/**
 * Reading a pulse from the accelerometer, with the phone lying on the chest.
 *
 * Every heartbeat throws the body about a hundredth of a g — a ballistocardiogram — and a phone
 * resting over the heart rides that. The signal is far below what a step looks like, so the whole
 * job is separating three things that share the same sensor: breathing (0.1–0.4 Hz, much larger
 * than the pulse), the pulse itself (0.7–2.5 Hz), and sensor noise above that. A band-pass drops
 * the first and the last, and what remains is periodic at the heart rate.
 *
 * Autocorrelation rather than peak counting: an individual beat is not reliably the tallest thing
 * in its second, but the *period* survives even when single beats are buried. [RepCounter] counts
 * peaks because a landing is an impulse; a pulse is a rhythm, and the rhythm is what to measure.
 *
 * The screen tells people to breathe normally rather than to hold their breath, and that is a
 * measurement decision rather than a kindness. Paired 30-second recordings of the same resting
 * pulse read 57 bpm either way; holding the breath only raised the correlation, 0.32 to 0.49. It is
 * not worth the trade, because half a minute without breathing moves the pulse itself — that is
 * what respiratory arrhythmia and the diving response do — so the cleaner signal would be of the
 * wrong number. Holding the breath is offered only after a reading fails.
 */
object Ballistocardiogram {
    /**
     * Tuning knobs. Defaults came from chest recordings on a real phone at ~30 Hz, one with the
     * breath held and one breathing normally, both of a resting pulse near 57 bpm. A different
     * phone, a heavier build, or a pulse under load will want these retuned — record a trace with
     * the sensor recorder and compare against a counted pulse rather than editing the maths.
     */
    data class Config(
        /** Below this is breathing and posture drift, not a pulse. */
        val minBpm: Int = 40,
        /** Above this the band starts collecting sensor noise; it is also past a resting pulse. */
        val maxBpm: Int = 150,
        /** Long enough for ~10 beats at a resting rate, short enough that the rate is steady across it. */
        val windowSeconds: Double = 15.0,
        /** How far each window starts after the last one. Overlap is what makes the median honest. */
        val stepSeconds: Double = 3.0,
        /** A window whose best lag correlates worse than this was measuring noise. */
        val minCorrelation: Double = 0.20,
        /**
         * Raw |a| spread above this is the phone moving, not a chest rising, and the window holding
         * it is dropped. See [GateConfig.stillStd].
         *
         * Judged per window rather than over the whole recording, because the loudest thing in a
         * recording is usually the person getting into position at the start of it — lying down onto
         * a phone spikes |a| past 1.0 for a few seconds, after which the same recording is the
         * quietest kind there is. One veto over the whole recording throws that away.
         */
        val maxMotionStd: Double = 0.35,
        /** Fewer accepted windows than this and the median has nothing to lean on. */
        val minWindows: Int = 3,
        /**
         * How tall the peak at a half or a third of the strongest lag has to stand, as a share of
         * the strongest, to be taken as the beat instead of it. See [fundamentalLag].
         *
         * The one default here that did not come from a chest: it was set on synthetic traces. At
         * 0.9 a steady pulse of 84 to 150 with the breath held reads as itself, and a resting pulse
         * is not doubled until its second harmonic carries about three times the amplitude of its
         * fundamental. Lower, and a resting pulse with a strong second harmonic starts to read
         * double; higher, and noise alone keeps a fast pulse at half its rate.
         */
        val subharmonicRatio: Double = 0.9,
    )

    /**
     * [confidence] is the autocorrelation of the winning lag, 0..1 — how strongly the signal
     * actually repeats. [spreadBpm] is how far the individual windows disagreed; a wide spread with
     * a high confidence usually means the pulse genuinely changed, and a wide spread with a low one
     * means the reading is guesswork. [windows] is the accepted count out of [totalWindows], so a
     * result built from a few clean stretches in an otherwise disturbed recording can say so.
     */
    data class Estimate(
        val bpm: Int,
        val confidence: Double,
        val spreadBpm: Int,
        val windows: Int,
        val totalWindows: Int,
    ) {
        val rejectedWindows: Int get() = (totalWindows - windows).coerceAtLeast(0)

        /** At least half the candidate windows failed an existing movement or rhythm quality gate. */
        val hasExcessiveInterference: Boolean
            get() = totalWindows > 0 && rejectedWindows * 2 >= totalWindows
    }

    /** Why a recording produced no number, in the order the checks run. */
    enum class Failure { TOO_SHORT, MOVING, NO_RHYTHM }

    sealed interface Result {
        data class Ok(val estimate: Estimate) : Result
        data class Poor(val reason: Failure) : Result
    }

    /**
     * [samples] is a chest recording from [Sensor.TYPE_ACCELEROMETER]; anything above ~25 Hz works.
     * Sampling is assumed even, which it is for an unbatched accelerometer — the mean interval
     * carries the rate.
     */
    fun estimate(samples: List<Accel>, config: Config = Config()): Result {
        val magnitudes = samples.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z).toDouble() }
        val spanSeconds = if (samples.size < 2) {
            0.0
        } else {
            (samples.last().t - samples.first().t) / 1e9
        }
        val hz = if (spanSeconds > 0.0) (samples.size - 1) / spanSeconds else 0.0
        val scan = if (spanSeconds >= config.windowSeconds) {
            windowRates(magnitudes, bandPass(magnitudes, hz, HIGH_PASS_HZ, LOW_PASS_HZ), hz, config)
        } else {
            Scan(emptyList(), 0, 0)
        }

        return when {
            spanSeconds < config.windowSeconds -> Result.Poor(Failure.TOO_SHORT)

            scan.readings.size < config.minWindows ->
                // Which of the two it was decides what the screen tells the person to change, so it
                // is worth answering honestly: at least half shaken means lie still, everything else
                // means the pulse never came through where the phone was.
                Result.Poor(
                    if (scan.total > 0 && scan.moved * 2 >= scan.total) Failure.MOVING else Failure.NO_RHYTHM,
                )

            else -> {
                val rates = scan.readings.map { it.first }.sorted()
                Result.Ok(
                    Estimate(
                        // Rounded, not truncated: the parabolic peak fit below exists to recover
                        // the fraction of a bpm that a whole-sample lag cannot reach, and flooring
                        // it here would give that precision back as a bias of up to a whole beat.
                        bpm = (rates.medianOrNull() ?: 0.0).roundToInt(),
                        confidence = scan.readings.map { it.second }.medianOrNull() ?: 0.0,
                        spreadBpm = (rates.last() - rates.first()).roundToInt(),
                        windows = scan.readings.size,
                        totalWindows = scan.total,
                    ),
                )
            }
        }
    }

    /**
     * [readings] is every window that produced a rate, as bpm paired with how strongly it
     * correlated; [moved] counts the windows dropped for movement out of [total] looked at.
     */
    private data class Scan(
        val readings: List<Pair<Double, Double>>,
        val moved: Int,
        val total: Int,
    )

    private fun windowRates(
        magnitudes: List<Double>,
        filtered: List<Double>,
        hz: Double,
        config: Config,
    ): Scan {
        val window = (config.windowSeconds * hz).toInt()
        val step = (config.stepSeconds * hz).toInt().coerceAtLeast(1)
        val readings = mutableListOf<Pair<Double, Double>>()
        var moved = 0
        var total = 0
        var start = 0
        while (start + window <= filtered.size) {
            total++
            if (standardDeviation(magnitudes.subList(start, start + window)) > config.maxMotionStd) {
                moved++
            } else {
                dominantPeriod(filtered.subList(start, start + window), hz, config)?.let(readings::add)
            }
            start += step
        }
        return Scan(readings, moved, total)
    }

    /** Everything below is breathing; the band starts above the fastest breath anyone takes lying down. */
    private const val HIGH_PASS_HZ = 0.7

    /** Everything above is sensor noise and the ring of the phone itself. */
    private const val LOW_PASS_HZ = 3.0

    /**
     * One-pole filters rather than a designed biquad: at 30 Hz with a band this wide the roll-off is
     * already steeper than the separation needs, and a filter that fits on a screen is a filter
     * whose behaviour is obvious at 3am.
     *
     * Limitation: a pulse fast enough to need the band narrowed until the skirts matter would need a
     * Butterworth biquad in place of this one-pole cascade.
     */
    private fun bandPass(values: List<Double>, hz: Double, low: Double, high: Double): List<Double> {
        val slowAlpha = exp(-2 * PI * low / hz)
        var passed = values
        // Two high-pass poles, not one. A breath moves the phone several times further than a
        // heartbeat does, and one pole leaves enough of it that the residual — being far slower than
        // any lag being searched — correlates near 1.0 across the whole band and wins every window.
        repeat(2) {
            var slow = passed.first()
            passed = passed.map { value ->
                slow = slowAlpha * slow + (1 - slowAlpha) * value
                value - slow
            }
        }
        val fastAlpha = exp(-2 * PI * high / hz)
        repeat(2) {
            var running = passed.first()
            passed = passed.map { value ->
                running = fastAlpha * running + (1 - fastAlpha) * value
                running
            }
        }
        return passed
    }

    /**
     * The rate at which the window repeats itself most strongly, as bpm paired with how strongly, or
     * null if nothing in the plausible band repeats convincingly. The bpm is always one
     * `minBpm..maxBpm` admits, because the number ends up in a health journal and a band that
     * reports past itself is not one.
     *
     * Only a *peak* of the correlation curve counts, never merely its highest point. Anything slower
     * than the band — leftover breath, a shoulder settling — correlates better and better as the lag
     * shrinks, so its highest point in the band is always the shortest lag searched, and a rising
     * slope with no peak is exactly the shape that says "this is not the rhythm you are looking for".
     *
     * Lags are walked from the slowest rate towards the fastest and a later peak has to beat an
     * earlier one outright: the second harmonic of a real pulse sits at half the period and
     * correlates almost as well, and preferring the longer lag on a tie is what stops a 57 bpm heart
     * reading 114. The strongest peak is then checked for being a multiple of the beat rather than
     * the beat — see [fundamentalLag].
     */
    private fun dominantPeriod(window: List<Double>, hz: Double, config: Config): Pair<Double, Double>? {
        val longestLag = (hz * 60 / config.minBpm).toInt().coerceAtMost(window.size - 2)
        // Rounded up, not down: a lag is a whole number of samples, and rounding the other way lets
        // the reported rate land above [Config.maxBpm] — a limit that reports past itself is not one.
        val shortestLag = ceil(hz * 60 / config.maxBpm).toInt().coerceAtLeast(1)
        // One lag either side of the band, so its first and last candidate can be tested for being a
        // peak and interpolated like any other.
        val curve = autocorrelation(window, (shortestLag - 1).coerceAtLeast(1), longestLag + 1)
        val strongestLag = highestPeak(curve, shortestLag, longestLag)
        val bestLag = fundamentalLag(curve, strongestLag, shortestLag, config.subharmonicRatio)
        val best = curve[bestLag]
        return if (best == null || best < config.minCorrelation) {
            null
        } else {
            // Clamped as well as rounded up, because the sub-sample fit reaches past the whole
            // sample the ceil() guarantees: half a sample off a lag of 12 at 30 Hz is 6 bpm, so a
            // window sitting at the top of the band can interpolate its way out of it.
            val rate = 60 * hz / (bestLag + peakOffset(curve, bestLag, best))
            rate.coerceIn(config.minBpm.toDouble(), config.maxBpm.toDouble()) to best
        }
    }

    /**
     * Where the peak at [bestLag] really sits, in samples, between that lag and its neighbours.
     *
     * A lag is a whole number of samples, so at 30 Hz the reachable rates near 100 bpm are three bpm
     * apart. Fitting a parabola to the peak and its two neighbours recovers the position between
     * them, which is the difference between a reading and a rounding.
     */
    private fun peakOffset(curve: Map<Int, Double>, bestLag: Int, best: Double): Double {
        val before = curve[bestLag - 1] ?: best
        val after = curve[bestLag + 1] ?: best
        val curvature = before - 2 * best + after
        return if (curvature == 0.0) 0.0 else (0.5 * (before - after) / curvature).coerceIn(-0.5, 0.5)
    }

    /**
     * Correlation of the window with itself, per lag, normalised so a perfect repeat is 1.0. Empty
     * when the window is perfectly flat and there is nothing to normalise by.
     */
    private fun autocorrelation(window: List<Double>, from: Int, until: Int): Map<Int, Double> {
        val mean = window.average()
        val centred = window.map { it - mean }
        val power = centred.sumOf { it * it } / centred.size
        if (power <= 0.0) return emptyMap()
        return (from..until.coerceAtMost(centred.size - 1)).associateWith { lag ->
            var sum = 0.0
            for (i in 0 until centred.size - lag) sum += centred[i] * centred[i + lag]
            sum / (centred.size - lag) / power
        }
    }

    /**
     * The strongest lag in `shortestLag..longestLag` that is a genuine peak of [curve], or 0.
     *
     * Only a peak counts, never merely the highest point. Anything slower than the band — leftover
     * breath, a shoulder settling — correlates better and better as the lag shrinks, so its highest
     * point in the band is always the shortest lag searched, and a rising slope with no peak is
     * exactly the shape that means "no rhythm here".
     *
     * Walked from the slowest rate towards the fastest, and a later peak has to beat an earlier one
     * outright: the second harmonic of a real pulse sits at half the period and correlates almost as
     * well, and preferring the longer lag on a tie is what stops a 57 bpm heart reading 114.
     */
    private fun highestPeak(curve: Map<Int, Double>, shortestLag: Int, longestLag: Int): Int {
        var bestLag = 0
        var best = 0.0
        for (lag in longestLag downTo shortestLag) {
            val here = curve[lag] ?: continue
            if (isPeak(curve, lag) && here > best) {
                best = here
                bestLag = lag
            }
        }
        return bestLag
    }

    /** Whether [curve] tops out at [lag]: lower just before it, and no higher just after. */
    private fun isPeak(curve: Map<Int, Double>, lag: Int): Boolean {
        val here = curve[lag] ?: return false
        return (curve[lag - 1] ?: Double.NEGATIVE_INFINITY) < here &&
            (curve[lag + 1] ?: Double.NEGATIVE_INFINITY) <= here
    }

    /**
     * The lag of one beat behind the strongest peak: [strongestLag] itself, or a half or a third of
     * it.
     *
     * A steady rhythm repeats after two and after three beats exactly as well as after one, so the
     * curve of a 108 bpm pulse has peaks of one height at 0.56 s and at 1.11 s, and both are in the
     * band. Which comes out strongest is down to noise and to where the whole-sample grid falls.
     * While the person breathes, what is left of the breath tilts the curve towards the short lag;
     * with the breath held nothing does, and on synthetic traces 108 would read as 54 and 144 as 48
     * in most windows — after the very advice the screen gives when a reading fails.
     *
     * So a genuine peak within a sample of a half or a third of the strongest lag is taken as the
     * beat when it stands nearly as tall. Nearly, and no less, because the other mistake is the
     * one [highestPeak] guards against: a slow pulse with a strong second harmonic has a peak at
     * half its period too, lower by twice the share of the signal that only repeats at the whole
     * period, and that peak must go on losing. The ratio is [Config.subharmonicRatio].
     */
    private fun fundamentalLag(curve: Map<Int, Double>, strongestLag: Int, shortestLag: Int, ratio: Double): Int {
        val strongest = peakHeight(curve, strongestLag) ?: return strongestLag
        return listOf(3, 2).firstNotNullOfOrNull { beats ->
            val near = strongestLag / beats
            (near - 1..near + 1)
                .filter { it >= shortestLag && isPeak(curve, it) }
                .maxByOrNull { peakHeight(curve, it) ?: 0.0 }
                ?.takeIf { (peakHeight(curve, it) ?: 0.0) >= ratio * strongest }
        } ?: strongestLag
    }

    /**
     * How tall the peak at [lag] stands between the samples: the top of the parabola [peakOffset]
     * fits. Two peaks of one rhythm are compared by this and not by the samples themselves, because
     * the one that happens to land on a sample reads taller than the one that falls between two —
     * by as much as a tenth at the short lags of a fast pulse, the whole margin being judged.
     */
    private fun peakHeight(curve: Map<Int, Double>, lag: Int): Double? {
        val here = curve[lag] ?: return null
        val before = curve[lag - 1] ?: here
        val after = curve[lag + 1] ?: here
        val curvature = before - 2 * here + after
        return if (curvature == 0.0) here else here - (before - after) * (before - after) / (8 * curvature)
    }

    private fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
}
