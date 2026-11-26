package com.pwnall.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One accelerometer sample. `t` is nanos since boot (SensorEvent.timestamp). */
data class Accel(val t: Long, val x: Float, val y: Float, val z: Float) {
    val magnitude: Float get() = sqrt(x * x + y * y + z * z)
}

/**
 * What the device is doing.
 *
 * [UNKNOWN] means the window could not be classified — too few samples, a discontinuous run, a
 * non-finite reading — and is deliberately different from [NON_GAIT], which is a positive finding
 * of movement without a stride. Claiming [VEHICLE] for everything unrecognised would be wrong
 * twice over: it would put a ride on screen for a walk the gate failed to parse, and throw away the
 * pedometer's steps with it.
 */
enum class Motion { UNKNOWN, STILL, WALKING, RUNNING, VEHICLE, SHAKING, NON_GAIT }

/** Where the device is being carried. */
enum class Posture { UNKNOWN, IN_HAND, POCKET, FLAT }

/**
 * Every threshold, in one place, because a real accelerometer is not the ideal one. These are
 * starting points measured off a phone in a pocket — retune per device rather than editing logic.
 */
data class GateConfig(
    val minSamples: Int = 16,
    /** Minimum time represented by a window before it may make a motion claim. */
    val minWindowSeconds: Float = 2.5f,
    /** A missing run longer than this makes the window discontinuous. */
    val maxSampleGapSeconds: Float = 0.20f,
    /** Amount of history considered for one live or event-aligned decision. */
    val analysisWindowSeconds: Float = 6f,
    /** Below this, nothing is moving. m/s². */
    val stillStd: Float = 0.35f,
    /** Above this it is being waved about, not walked with. m/s². */
    val shakeStd: Float = 12f,
    /**
     * Measured off real recordings rather than guessed: a bus ride sits at ~0.4 m/s² of magnitude
     * spread, while walking with the phone in a pocket sits between 3.5 and 7.5. The floor is what
     * actually keeps a ride out — periodicity does not, because road and engine vibration is
     * genuinely periodic.
     */
    val gaitMinStd: Float = 0.8f,
    val gaitMaxStd: Float = 10f,
    /** ~48 steps/min, retaining slow and mobility-limited gait. */
    val gaitMinHz: Float = 0.8f,
    /** ~228 steps/min, covering fast running without admitting high-frequency vibration. */
    val gaitMaxHz: Float = 3.8f,
    /**
     * Above ~141 repeats/min, sustained periodic gait is treated as running. Compared against the
     * dominant rhythm, so a run with the phone in a pocket — where the stride dominates and reads
     * at half the step rate — is labelled WALKING. Both allow steps through, so the cost is a word
     * on the developer screen, not a lost step, and that is cheaper than guessing at a doubling.
     */
    val runningMinHz: Float = 2.35f,
    /** A brief shake at running cadence is not a running interval. */
    val runningMinWindowSeconds: Float = 5f,
    /**
     * Minimum normalized autocorrelation at the dominant period. Real gait is far less regular than
     * a sinusoid — a phone swinging in a hand or shifting in a coat pocket loses correlation fast —
     * so this is a calibration knob, not a constant of nature. Recorded walks sit at 0.78–0.86 and
     * a bus at 0.33, which is where this number comes from.
     */
    val periodicityMin: Float = 0.40f,
    /** Slowest repeat treated as a candidate gait rhythm: one stride every two seconds. */
    val rhythmMinHz: Float = 0.5f,
    /** Fastest, above which it is vibration rather than a body moving. */
    val rhythmMaxHz: Float = 4f,
    /** Screen within this many degrees of horizontal counts as lying flat. */
    val flatTiltDeg: Float = 30f,
    /** Above this the device is upright — a trouser pocket, a bag. */
    val uprightTiltDeg: Float = 60f,
    /**
     * Lying flat with the screen off is the cupholder / seat / table case. Engine, rail and road
     * vibration land squarely in the gait band, so demand more energy there before believing it.
     */
    val flatVehicleStd: Float = 1.5f,
    /**
     * A walker's energy without a walker's regularity is a doubt, not a veto ([MotionReading.stepsDoubtful]).
     * Two real stair climbs put every window the gate refused at 2.9–4.3 m/s² — the phone
     * going from the hand into a pocket mid-climb — and the pocket jostling its tests keep out at 2.0.
     */
    val doubtfulMinStd: Float = 2.5f,
) {
    /** The strongest rhythm is the step rate or, for a phone that feels only the stride, half of it. */
    fun rhythmIsGait(cadenceHz: Float): Boolean =
        cadenceHz in gaitMinHz..gaitMaxHz || cadenceHz * 2f in gaitMinHz..gaitMaxHz
}

/** The thresholds a [MotionReading] is judged against after the fact. */
private val DEFAULT_GATE = GateConfig()

data class MotionReading(
    val motion: Motion = Motion.UNKNOWN,
    val posture: Posture = Posture.UNKNOWN,
    val std: Float = 0f,
    /**
     * The dominant rhythm, which is the stride rate or the step rate depending on where the phone
     * is carried — not a step count. Steps come from the hardware pedometer; this only says how
     * regularly the body is repeating itself.
     */
    val cadenceHz: Float = 0f,
    val periodicity: Float = 0f,
    val tiltDeg: Float = 0f,
    val screenOn: Boolean = false,
) {
    /**
     * The gate vetoes a step-counter increase only when it has evidence against a stride.
     *
     * `TYPE_STEP_COUNTER` is already a filtered signal from the platform's own gait model, so an
     * unclassifiable window is not grounds for discarding it. Requiring positive gait here would
     * throw away as NO_GAIT every batched delivery whose accelerometer window has not arrived yet —
     * the normal case on a batching sensor hub.
     */
    val stepsAllowed: Boolean
        get() = motion == Motion.WALKING || motion == Motion.RUNNING || motion == Motion.UNKNOWN

    /**
     * Movement with a walker's energy and a stride's tempo that the gate still could not call a
     * stride — neither evidence for one nor against it. Climbing stairs while the phone goes from the
     * hand into a pocket reads exactly so: in two recorded climbs every refused window had a gait-band
     * rhythm and 2.9–4.3 m/s² of spread, but a periodicity of 0.26–0.39, which random jostling also
     * reaches, so no periodicity threshold can tell them apart. What can is the walk around them:
     * [StepAccumulator] credits these steps only within a walk the gate has just confirmed.
     */
    val stepsDoubtful: Boolean
        get() = (motion == Motion.NON_GAIT || motion == Motion.VEHICLE) &&
            std >= DEFAULT_GATE.doubtfulMinStd &&
            DEFAULT_GATE.rhythmIsGait(cadenceHz)
}

/**
 * Second line of defence behind the hardware pedometer: classify the accelerometer window and
 * refuse to credit steps that don't look like walking.
 *
 * Pure Kotlin, so every regime is a unit test rather than a walk around the block.
 */
object MotionGate {

    /** How close to the strongest correlation a shorter lag has to come to be preferred to it. */
    private const val HARMONIC_TOLERANCE = 0.9f

    // The gate is a decision table over the sensor features it measures.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun analyse(
        window: List<Accel>,
        screenOn: Boolean,
        config: GateConfig = GateConfig(),
    ): MotionReading {
        if (window.size < config.minSamples) return MotionReading(screenOn = screenOn)

        val gaps = window.zipWithNext { left, right -> right.t - left.t }
        if (gaps.any { it <= 0L }) return MotionReading(screenOn = screenOn)
        val seconds = (window.last().t - window.first().t) / NANOS_PER_SECOND
        if (seconds < config.minWindowSeconds) return MotionReading(screenOn = screenOn)
        if (gaps.max() > config.maxSampleGapSeconds * NANOS_PER_SECOND) {
            return MotionReading(screenOn = screenOn)
        }
        // One non-finite sample poisons the mean, the spread and the tilt at once, and every
        // comparison against a NaN is false — so the decision table below would fall all the way
        // through to NON_GAIT and veto the pedometer. That is a positive finding of "not a stride"
        // manufactured out of no evidence; a window that cannot be evaluated is UNKNOWN.
        if (window.any { !it.magnitude.isFinite() }) return MotionReading(screenOn = screenOn)

        val magnitudes = window.map { it.magnitude }
        val mean = magnitudes.average().toFloat()
        val std = sqrt(
            magnitudes.sumOf {
                val d = it - mean
                (d * d).toDouble()
            } / magnitudes.size,
        )
            .toFloat()

        // The mean acceleration vector is gravity: its angle off the device's +Z axis says how the
        // device is being held. 0° = screen up, 90° = upright, 180° = screen down.
        val mx = window.map { it.x }.average().toFloat()
        val my = window.map { it.y }.average().toFloat()
        val mz = window.map { it.z }.average().toFloat()
        val gravity = sqrt(mx * mx + my * my + mz * mz)
        val tiltDeg =
            if (gravity == 0f) {
                0f
            } else {
                Math.toDegrees(acos((mz / gravity).coerceIn(-1f, 1f).toDouble())).toFloat()
            }

        // A three-sample moving average takes the worst of the sensor chatter off before the
        // rhythm is looked for.
        val smoothed = magnitudes.indices.map { index ->
            val from = (index - 2).coerceAtLeast(0)
            magnitudes.subList(from, index + 1).average().toFloat()
        }
        val sampleRateHz = if (seconds > 0f) (window.size - 1) / seconds else 0f
        val (cadenceHz, periodicity) = dominantRhythm(smoothed, sampleRateHz, config)

        // Energy left after smoothing, which is the energy at frequencies a body can produce. The
        // gait test uses this rather than the raw spread because a normalized autocorrelation does
        // not care how small the signal is: a phone resting on something that buzzes at 8 Hz keeps
        // 2.1 m/s² of raw spread but only 0.2 once the buzz is filtered out, and it correlates
        // beautifully with itself either way. Raw std still decides STILL, SHAKING and a ride.
        val smoothedMean = smoothed.average().toFloat()
        val gaitEnergy = sqrt(
            smoothed.sumOf {
                val d = it - smoothedMean
                (d * d).toDouble()
            } / smoothed.size,
        ).toFloat()

        val posture = when {
            tiltDeg < config.flatTiltDeg || tiltDeg > 180f - config.flatTiltDeg -> Posture.FLAT
            screenOn -> Posture.IN_HAND
            tiltDeg > config.uprightTiltDeg -> Posture.POCKET
            else -> Posture.UNKNOWN
        }

        // A phone in a pocket feels the stride — one repeat for every two steps — while a phone in
        // the hand feels each step. So the strongest rhythm is either the step rate or half of it,
        // and both are gait. Demanding the step rate itself would reject every pocket walk on file:
        // 200 steps recorded at 1.38 steps/s show their dominant rhythm at 0.73 Hz.
        val rhythmIsGait = config.rhythmIsGait(cadenceHz)

        // A lift or an escalator is kept out by the gaitMinStd floor, not by a per-axis rule: which
        // axis a leg swing lands on depends on how the phone is carried, and a walk with the phone
        // lying flat puts nothing at all on Y.
        val inGaitBand = gaitEnergy >= config.gaitMinStd &&
            std <= config.gaitMaxStd &&
            rhythmIsGait &&
            periodicity >= config.periodicityMin
        val looksLikeRide = posture == Posture.FLAT && !screenOn && std < config.flatVehicleStd

        val motion = when {
            std < config.stillStd -> Motion.STILL

            std > config.shakeStd -> Motion.SHAKING

            inGaitBand &&
                !looksLikeRide &&
                cadenceHz >= config.runningMinHz &&
                seconds >= config.runningMinWindowSeconds -> Motion.RUNNING

            inGaitBand && !looksLikeRide -> Motion.WALKING

            // A ride is a claim about the world, so it needs its own evidence: the device is lying
            // flat with the screen off and what it feels has no stride in it. Anything else that
            // fails the gait test is only that — movement that is not a stride.
            posture == Posture.FLAT && !screenOn -> Motion.VEHICLE

            else -> Motion.NON_GAIT
        }

        return MotionReading(motion, posture, std, cadenceHz, periodicity, tiltDeg, screenOn)
    }

    /**
     * The period of the signal, and how strongly it repeats at that period.
     *
     * The period is not inferred by counting mean crossings and evaluating the autocorrelation at
     * whatever lag that implies. On real walks the crossing count runs high — sensor chatter adds
     * crossings the body never made — and the lag it produces lands *between* the true peaks, exactly
     * where a periodic signal is anti-correlated. A recorded 200-step walk measures a periodicity of
     * −0.19 at a claimed 2.2 Hz while the same window correlates +0.89 at its real period: read that
     * way, the gate would call it NON_GAIT and throw all 200 steps away.
     *
     * Instead the autocorrelation is searched for its own peak across the range a human gait can
     * occupy, and that peak is both the rhythm and the confidence in it.
     */
    // One early return per way the window cannot carry a rhythm at all.
    private fun dominantRhythm(
        values: List<Float>,
        sampleRateHz: Float,
        config: GateConfig,
    ): Pair<Float, Float> {
        if (sampleRateHz <= 0f) return 0f to 0f
        val shortestLag = (sampleRateHz / config.rhythmMaxHz).roundToInt().coerceAtLeast(2)
        val longestLag = (sampleRateHz / config.rhythmMinHz).roundToInt()
            // A candidate period must occur at least twice in the observed data. Allowing lags up
            // to lastIndex would make a single matching pair a mathematically perfect correlation.
            .coerceAtMost(values.lastIndex / MIN_OBSERVED_PERIODS)
        if (longestLag < shortestLag) return 0f to 0f

        // Hoisted: the mean does not depend on the lag, and computing it inside the callee would walk
        // the whole window again for each of the ~40 candidate lags.
        val mean = values.average().toFloat()
        val correlations = (shortestLag..longestLag).map {
            normalizedAutocorrelation(values, it, mean)
        }
        val best = correlations.max()
        if (best <= 0f) return 0f to 0f

        // Take the shortest lag that comes close to the best, not the best itself. A signal that
        // repeats every N samples repeats every 2N and 3N just as faithfully, so the strongest
        // correlation is often a harmonic several times slower than the truth — a clean 3 Hz stride
        // scores a perfect 1.0 one full second out and would be read as 1 Hz.
        val index = correlations.indexOfFirst { it >= best * HARMONIC_TOLERANCE }
        return (sampleRateHz / (shortestLag + index)) to correlations[index]
    }

    private fun normalizedAutocorrelation(values: List<Float>, lag: Int, mean: Float): Float {
        if (lag <= 0 || lag * MIN_OBSERVED_PERIODS > values.lastIndex) return 0f
        var numerator = 0.0
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        for (index in lag until values.size) {
            val left = (values[index] - mean).toDouble()
            val right = (values[index - lag] - mean).toDouble()
            numerator += left * right
            leftEnergy += left * left
            rightEnergy += right * right
        }
        val denominator = sqrt(leftEnergy * rightEnergy)
        return if (denominator > 0.0) {
            (numerator / denominator).toFloat().coerceIn(-1f, 1f)
        } else {
            0f
        }
    }

    private const val NANOS_PER_SECOND = 1_000_000_000f
    private const val MIN_OBSERVED_PERIODS = 2
}

/** The gate's verdict for one moment, with the window of samples it was read from, as a trace records it. */
data class MotionDecision(
    val reading: MotionReading,
    val window: TraceWindowMetadata,
)

/**
 * In-memory window of recent samples plus the current verdict. The accelerometer listeners write it;
 * a step, a pressure reading and a route's fix each read the window of their own moment from it, and
 * the sensor screen and a trace read the running verdict. Deliberately not persisted — it is a live
 * view, not a record.
 */
object SensorFeed {
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val RETENTION_NANOS = 12L * NANOS_PER_SECOND
    private const val EMIT_INTERVAL_NANOS = 400_000_000L

    /** Safety ceiling only; normal eviction is timestamp-based, not sample-count-based. */
    private const val MAX_BUFFER_SAMPLES = 1_024

    /** A step this close to a route fix, either side, is the pedometer saying the fix was on foot. */
    private const val STEP_EVIDENCE_NANOS = 10L * NANOS_PER_SECOND

    private data class BufferedAccel(val accel: Accel, val screenOn: Boolean)

    private val samples = ArrayDeque<BufferedAccel>()
    private var lastEmitTimestamp = Long.MIN_VALUE
    private var lastTimestamp = Long.MIN_VALUE

    @Volatile
    private var lastStepNanos: Long? = null

    private val _state = MutableStateFlow(DevState())
    val state: StateFlow<DevState> = _state.asStateFlow()

    @Synchronized
    fun add(
        sample: Accel,
        screenOn: Boolean,
        deliveryElapsedRealtimeNanos: Long? = null,
    ) {
        // The service and the sensor screen may both be listening. They receive the same hardware
        // events, so dropping anything not newer makes the second producer a no-op — the same
        // idempotence trick the step accumulator uses.
        if (sample.t <= lastTimestamp) return
        lastTimestamp = sample.t

        samples.addLast(BufferedAccel(sample, screenOn))
        evictBefore(sample.t - RETENTION_NANOS)
        while (samples.size > MAX_BUFFER_SAMPLES) samples.removeFirst()

        val feed = _state.value
        SensorTrace.sample(sample, feed.reading, deliveryElapsedRealtimeNanos, feed)
        // The verdict kept here is read by the sensor screen and written into a trace, and by
        // nothing else: a step, a pressure reading and a route's fix each ask for the window of
        // their own moment ([decisionAt], [readingAt]). With neither there it is not worked out —
        // that would be nine thousand windows analysed in every hour the service runs, for nobody.
        val watched = _state.subscriptionCount.value > 0
        val emitDue = lastEmitTimestamp == Long.MIN_VALUE || sample.t - lastEmitTimestamp >= EMIT_INTERVAL_NANOS
        if (!emitDue || !(watched || SensorTrace.state.value.recording)) return
        lastEmitTimestamp = sample.t

        val config = GateConfig()
        val decision = decisionAtLocked(sample.t, config)
        _state.value = _state.value.copy(
            // The window is copied for the sensor screen alone: a trace takes the reading.
            samples = if (watched) {
                boundedWindow(sample.t, config.analysisWindowSeconds).map { it.accel }
            } else {
                emptyList()
            },
            reading = decision.reading,
        )
    }

    @Synchronized
    fun onSteps(
        rawCounter: Long,
        rawDelta: Long,
        creditedDelta: Long,
        verdict: StepVerdict,
        timestampNanos: Long = 0,
        decision: MotionDecision? = null,
        deliveryElapsedRealtimeNanos: Long? = null,
    ) {
        if (rawDelta > 0) lastStepNanos = timestampNanos
        val s = _state.value
        val exactReading = decision?.reading ?: s.reading
        SensorTrace.step(
            eventTimestampNanos = timestampNanos,
            reading = exactReading,
            rawCounter = rawCounter,
            rawDelta = rawDelta,
            credited = creditedDelta,
            verdict = verdict,
            deliveryElapsedRealtimeNanos = deliveryElapsedRealtimeNanos,
            gaitEvidence = exactReading.stepsAllowed,
            window = decision?.window ?: TraceWindowMetadata(),
        )
        val rejectedDelta = when (verdict) {
            StepVerdict.NON_MONOTONIC_TIME,
            StepVerdict.IMPOSSIBLE_RATE,
            StepVerdict.NO_GAIT,
            -> rawDelta

            else -> 0
        }
        _state.value = s.copy(
            rawCounter = rawCounter,
            credited = s.credited + creditedDelta,
            rejected = s.rejected + rejectedDelta,
        )
    }

    /**
     * Whether the hardware pedometer counted steps near [timestampNanos]: the platform's own gait
     * model, which a route fix falls back on when its accelerometer window is missing.
     */
    fun stepsNear(timestampNanos: Long): Boolean =
        lastStepNanos?.let { abs(timestampNanos - it) <= STEP_EVIDENCE_NANOS } == true

    /**
     * The exact causal window for a sensor event. No sample newer than [timestampNanos] can
     * influence the result, and old or discontinuous data is reported as insufficient rather than
     * being replaced with the live window.
     */
    @Synchronized
    fun decisionAt(
        timestampNanos: Long,
        config: GateConfig = GateConfig(),
    ): MotionDecision = decisionAtLocked(timestampNanos, config)

    @Synchronized
    fun readingAt(timestampNanos: Long): MotionReading = decisionAtLocked(
        timestampNanos,
        GateConfig(),
    ).reading

    /** Clears process-global sensor state between pure JVM tests. */
    @Synchronized
    internal fun resetForTest() {
        samples.clear()
        lastEmitTimestamp = Long.MIN_VALUE
        lastTimestamp = Long.MIN_VALUE
        lastStepNanos = null
        _state.value = DevState()
    }

    private fun decisionAtLocked(timestampNanos: Long, config: GateConfig): MotionDecision {
        val bounded = boundedWindow(timestampNanos, config.analysisWindowSeconds)
        val maxGapNanos = secondsToNanos(config.maxSampleGapSeconds)

        // After a delivery gap, use only the new contiguous run. It must build up the full minimum
        // duration before the gate can make another claim.
        var segmentStart = 0
        bounded.zipWithNext().forEachIndexed { index, (left, right) ->
            if (right.accel.t - left.accel.t > maxGapNanos) segmentStart = index + 1
        }
        val selected = bounded.drop(segmentStart)
        val accelerations = selected.map { it.accel }
        val screenOn = selected.lastOrNull()?.screenOn ?: false
        val analysed = MotionGate.analyse(accelerations, screenOn, config)
        val endpointLag = accelerations.lastOrNull()?.let { timestampNanos - it.t }
        val endpointIsFresh = endpointLag != null && endpointLag in 0..maxGapNanos
        val enoughData = endpointIsFresh && analysed.motion != Motion.UNKNOWN
        val reading = if (enoughData) analysed else MotionReading(screenOn = screenOn)
        val gaps = accelerations.zipWithNext { left, right -> right.t - left.t }
        val metadata = TraceWindowMetadata(
            source = if (enoughData) {
                TraceReadingSource.EVENT_WINDOW
            } else {
                TraceReadingSource.INSUFFICIENT_DATA
            },
            firstSampleTimestampNanos = accelerations.firstOrNull()?.t,
            lastSampleTimestampNanos = accelerations.lastOrNull()?.t,
            sampleCount = accelerations.size,
            maxGapNanos = gaps.maxOrNull(),
        )
        return MotionDecision(reading, metadata)
    }

    private fun boundedWindow(
        timestampNanos: Long,
        lookbackSeconds: Float,
    ): List<BufferedAccel> {
        val lookbackNanos = secondsToNanos(lookbackSeconds)
        val earliest = if (timestampNanos < Long.MIN_VALUE + lookbackNanos) {
            Long.MIN_VALUE
        } else {
            timestampNanos - lookbackNanos
        }
        return samples.filter { it.accel.t in earliest..timestampNanos }
    }

    private fun evictBefore(timestampNanos: Long) {
        while (samples.firstOrNull()?.accel?.t?.let { it < timestampNanos } == true) {
            samples.removeFirst()
        }
    }

    private fun secondsToNanos(seconds: Float): Long =
        (seconds * NANOS_PER_SECOND).toLong().coerceAtLeast(0L)
}

data class DevState(
    val samples: List<Accel> = emptyList(),
    val reading: MotionReading = MotionReading(),
    val rawCounter: Long = 0,
    val credited: Long = 0,
    val rejected: Long = 0,
)
