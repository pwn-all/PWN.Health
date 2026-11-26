package com.pwnall.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TraceState(val recording: Boolean = false, val rows: Int = 0, val dropped: Int = 0)

/** How the accelerometer window used for a step decision was selected. */
enum class TraceReadingSource {
    /** All samples in the window were selected relative to the step event timestamp. */
    EVENT_WINDOW,

    /** There were not enough usable samples to classify the event. */
    INSUFFICIENT_DATA,

    /** The caller did not report how the reading was obtained. */
    UNSPECIFIED,
}

/**
 * Provenance of the [MotionReading] supplied to a step decision.
 *
 * All timestamps use the same elapsed-realtime nanosecond clock as [Accel.t]. Keeping the
 * boundaries as data (rather than deriving them later from whatever buffer happens to exist) is
 * what makes a trace deterministic when sensor batches overlap or have gaps.
 */
data class TraceWindowMetadata(
    val source: TraceReadingSource = TraceReadingSource.UNSPECIFIED,
    val firstSampleTimestampNanos: Long? = null,
    val lastSampleTimestampNanos: Long? = null,
    val sampleCount: Int? = null,
    val maxGapNanos: Long? = null,
) {
    val coverageNanos: Long?
        get() = if (firstSampleTimestampNanos != null && lastSampleTimestampNanos != null) {
            lastSampleTimestampNanos - firstSampleTimestampNanos
        } else {
            null
        }
}

/**
 * Session data needed to replay a trace under the same assumptions.
 *
 * This type deliberately contains no Android classes. The service may provide clock, counter and
 * device fields when it has them; pure JVM tests and callers can leave them absent.
 */
data class TraceSessionMetadata(
    val startedAtEpochMillis: Long? = null,
    val startedAtElapsedRealtimeNanos: Long? = null,
    val baselineRawCounter: Long? = null,
    val bootId: Long? = null,
    val day: String? = null,
    /** Exact persisted accumulator snapshot at trace start, when available. */
    val accumulatorState: StepState? = null,
    /**
     * The live session counters as they stood when recording began, mirroring the snapshot
     * [SensorTrace.stopCounters] takes at the end — the pair is what makes a recording's own
     * raw/credited/rejected deltas readable without arithmetic over the rows.
     */
    val startRawCounter: Long? = null,
    val startCredited: Long? = null,
    val startRejected: Long? = null,
    val gateConfig: GateConfig = GateConfig(),
    val extras: Map<String, String> = emptyMap(),
)

/**
 * Records what the sensors and the gate actually did, so a walk that counted wrong can be looked at
 * afterwards instead of guessed at. Every accelerometer sample and every step verdict becomes a CSV
 * row; the file is written only where the person chooses to put it.
 *
 * `eventTimestampNanos` is when the sensor event happened. `deliveryElapsedRealtimeNanos` is when
 * the app received or processed it. They must never be substituted for one another: their
 * difference is the sensor batching delay that a deterministic replay needs to reproduce.
 *
 * In memory and never persisted on its own: it is evidence for tuning, not app data.
 */
object SensorTrace {
    /** ~2 hours at 25 Hz. Recording past this keeps the newest rows and counts what it drops. */
    private const val MAX_ROWS = 200_000
    private const val SCHEMA_VERSION = 2

    /**
     * How long after its first sample a recording stops itself, an hour past [MAX_ROWS]. Without it, one
     * forgotten on the sensor page would go on formatting 25 rows a second on the main thread for as long
     * as the process lived, its wake lock long gone: the service holds the processor for this long and no
     * longer. The file says that it ended so (`session.limitReachedAtNanos`).
     */
    internal const val MAX_RECORDING_MS = 3 * 60 * 60 * 1_000L
    private const val NANOS_PER_MILLI = 1_000_000L

    const val HEADER =
        "kind,eventTimestampNanos,deliveryElapsedRealtimeNanos,x,y,z,magnitude,std,cadenceHz," +
            "periodicity,tiltDeg,motion,posture,screenOn,gaitEvidence,readingSource," +
            "windowStartNanos,windowEndNanos,windowSampleCount,windowCoverageNanos," +
            "windowMaxGapNanos,rawCounter,rawDelta,credited,verdict,metadataKey,metadataValue"

    /**
     * Publishing the row count on every sample means a state emission at 25 Hz, which recomposes
     * the whole screen — waveform included — twenty-five times a second. The recorder then looks
     * frozen while it is in fact working. One update a second is all a counter needs.
     */
    private const val PUBLISH_EVERY = 25

    private val rows = ArrayDeque<String>()
    private val metadata = linkedMapOf<String, String>()
    private var recording = false
    private var dropped = 0
    private var sincePublish = 0
    private var firstSampleNanos: Long? = null

    private val _state = MutableStateFlow(TraceState())
    val state: StateFlow<TraceState> = _state.asStateFlow()

    @Synchronized
    fun start(session: TraceSessionMetadata = TraceSessionMetadata()) {
        rows.clear()
        metadata.clear()
        dropped = 0
        sincePublish = 0
        firstSampleNanos = null
        recording = true
        recordSessionMetadata(session)
        publish()
    }

    /**
     * Adds or replaces a JVM-safe session metadata value without consuming the sensor row budget.
     * This is useful when a counter baseline or sensor identity becomes known after recording began.
     */
    @Synchronized
    fun metadata(key: String, value: String?) {
        if (!recording || value == null) return
        metadata[key] = value
    }

    /**
     * The end-of-recording twin of the start counters, as [feed] holds them; written just before
     * [stop], and by a recording that stops itself at its limit ([sample]). The raw counter is left
     * out while no step event has been seen: the feed's 0 then would be indistinguishable from a
     * genuine just-after-boot reading.
     */
    @Synchronized
    fun stopCounters(feed: DevState) {
        if (!recording) return
        putMetadata("counter.stopRaw", feed.rawCounter.takeIf { it > 0 })
        metadata["counter.stopCredited"] = feed.credited.toString()
        metadata["counter.stopRejected"] = feed.rejected.toString()
    }

    @Synchronized
    fun stop() {
        recording = false
        publish()
    }

    /**
     * Lets the rows go once the file holding them is written: two hours of recording is tens of
     * megabytes, and they would otherwise live until the next Start. A recording in progress keeps its rows.
     */
    @Synchronized
    fun discard() {
        if (recording) return
        rows.clear()
        metadata.clear()
        dropped = 0
        publish()
    }

    /**
     * [feed] is the state the sample came in under, whose counters a recording that runs out here
     * ends with, as one stopped by hand does ([stopCounters]).
     */
    @Synchronized
    fun sample(
        sample: Accel,
        reading: MotionReading,
        deliveryElapsedRealtimeNanos: Long? = null,
        feed: DevState? = null,
    ) {
        if (!recording) return
        val first = firstSampleNanos ?: sample.t.also { firstSampleNanos = it }
        if (sample.t - first > MAX_RECORDING_MS * NANOS_PER_MILLI) {
            metadata["session.limitReachedAtNanos"] = sample.t.toString()
            feed?.let(::stopCounters)
            stop()
            return
        }
        add(
            row(
                "S",
                sample.t,
                deliveryElapsedRealtimeNanos,
                f(sample.x),
                f(sample.y),
                f(sample.z),
                f(sample.magnitude),
                f(reading.std),
                f(reading.cadenceHz),
                f(reading.periodicity),
                f(reading.tiltDeg),
                reading.motion,
                reading.posture,
                reading.screenOn,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            ),
        )
    }

    @Synchronized
    fun step(
        eventTimestampNanos: Long,
        reading: MotionReading,
        rawCounter: Long,
        rawDelta: Long,
        credited: Long,
        verdict: StepVerdict,
        deliveryElapsedRealtimeNanos: Long? = null,
        gaitEvidence: Boolean = reading.stepsAllowed,
        window: TraceWindowMetadata = TraceWindowMetadata(),
    ) {
        if (!recording) return
        add(
            row(
                "P",
                eventTimestampNanos,
                deliveryElapsedRealtimeNanos,
                null,
                null,
                null,
                null,
                f(reading.std),
                f(reading.cadenceHz),
                f(reading.periodicity),
                f(reading.tiltDeg),
                reading.motion,
                reading.posture,
                reading.screenOn,
                gaitEvidence,
                window.source,
                window.firstSampleTimestampNanos,
                window.lastSampleTimestampNanos,
                window.sampleCount,
                window.coverageNanos,
                window.maxGapNanos,
                rawCounter,
                rawDelta,
                credited,
                verdict,
                null,
                null,
            ),
        )
    }

    fun csv(): String = snapshot().joinToString("\n", postfix = "\n")

    /**
     * Writes the trace to [out] a row at a time. At [MAX_ROWS] the whole file is tens of megabytes,
     * which as one String plus the byte array it encodes to is more than a phone should be asked
     * for at once. Only the row snapshot is taken under the lock: the sensor thread must never wait
     * on a file being written.
     */
    fun writeCsv(out: Appendable) {
        snapshot().forEach { out.append(it).append('\n') }
    }

    @Synchronized
    private fun snapshot(): List<String> {
        val lines = ArrayList<String>(rows.size + metadata.size + 1)
        lines += HEADER
        metadata.forEach { (key, value) ->
            lines += row(
                "M",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                key,
                value,
            )
        }
        lines += rows
        return lines
    }

    private fun recordSessionMetadata(session: TraceSessionMetadata) {
        metadata["schema.version"] = SCHEMA_VERSION.toString()
        metadata["clock.event"] = "sensor_elapsed_realtime_nanos"
        metadata["clock.delivery"] = "elapsed_realtime_nanos"
        putMetadata("session.startedAtEpochMillis", session.startedAtEpochMillis)
        putMetadata("session.startedAtElapsedRealtimeNanos", session.startedAtElapsedRealtimeNanos)
        putMetadata("counter.baselineRaw", session.baselineRawCounter)
        putMetadata("counter.bootId", session.bootId)
        putMetadata("counter.day", session.day)
        putMetadata("counter.startRaw", session.startRawCounter)
        putMetadata("counter.startCredited", session.startCredited)
        putMetadata("counter.startRejected", session.startRejected)
        session.accumulatorState?.let(::recordAccumulatorState)
        recordGateConfig(session.gateConfig)
        session.extras.toSortedMap().forEach { (key, value) -> metadata["extra.$key"] = value }
    }

    private fun recordAccumulatorState(state: StepState) {
        putMetadata("state.bootId", state.bootId)
        putMetadata("state.lastCumulative", state.lastCumulative)
        putMetadata("state.lastEventNanos", state.lastEventNanos)
        putMetadata("state.pendingSteps", state.pendingSteps)
        putMetadata("state.boutStartedNanos", state.boutStartedNanos)
        putMetadata("state.boutLastNanos", state.boutLastNanos)
        putMetadata("state.boutConfirmed", state.boutConfirmed)
        putMetadata("state.rateWindowStartedNanos", state.rateWindowStartedNanos)
        putMetadata("state.rateWindowStartCounter", state.rateWindowStartCounter)
        putMetadata("state.pendingDay", state.pendingDay)
        putMetadata("state.pendingDaySteps", state.pendingDaySteps)
    }

    private fun recordGateConfig(config: GateConfig) {
        putMetadata("gate.minSamples", config.minSamples)
        putMetadata("gate.minWindowSeconds", config.minWindowSeconds)
        putMetadata("gate.maxSampleGapSeconds", config.maxSampleGapSeconds)
        putMetadata("gate.analysisWindowSeconds", config.analysisWindowSeconds)
        putMetadata("gate.stillStd", config.stillStd)
        putMetadata("gate.shakeStd", config.shakeStd)
        putMetadata("gate.gaitMinStd", config.gaitMinStd)
        putMetadata("gate.gaitMaxStd", config.gaitMaxStd)
        putMetadata("gate.gaitMinHz", config.gaitMinHz)
        putMetadata("gate.gaitMaxHz", config.gaitMaxHz)
        putMetadata("gate.runningMinHz", config.runningMinHz)
        putMetadata("gate.runningMinWindowSeconds", config.runningMinWindowSeconds)
        putMetadata("gate.periodicityMin", config.periodicityMin)
        putMetadata("gate.rhythmMinHz", config.rhythmMinHz)
        putMetadata("gate.rhythmMaxHz", config.rhythmMaxHz)
        putMetadata("gate.flatTiltDeg", config.flatTiltDeg)
        putMetadata("gate.uprightTiltDeg", config.uprightTiltDeg)
        putMetadata("gate.flatVehicleStd", config.flatVehicleStd)
    }

    private fun putMetadata(key: String, value: Any?) {
        if (value != null) metadata[key] = value.toString()
    }

    private fun add(row: String) {
        if (rows.size == MAX_ROWS) {
            rows.removeFirst()
            dropped++
        }
        rows.addLast(row)
        if (++sincePublish < PUBLISH_EVERY) return
        sincePublish = 0
        publish()
    }

    private fun publish() {
        _state.value = TraceState(recording = recording, rows = rows.size, dropped = dropped)
    }

    private fun row(vararg values: Any?): String = values.joinToString(",") { csvValue(it) }

    private fun csvValue(value: Any?): String {
        if (value == null) return ""
        val text = value.toString()
        return if (text.any { it in ",\"\n\r" }) {
            "\"${text.replace("\"", "\"\"")}\""
        } else {
            text
        }
    }

    private fun f(value: Float) = "%.4f".format(java.util.Locale.US, value)
}
