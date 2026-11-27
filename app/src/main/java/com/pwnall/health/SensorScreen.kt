package com.pwnall.health

import android.content.res.Resources
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Developer view: what the accelerometer is doing right now, what [MotionGate] made of it, and how
 * many steps that verdict has let through or thrown away.
 *
 * It has two doors: seven taps on the version line under Settings → About the app, in any build,
 * and a long press on the home stop of the bottom bar that only a debuggable build offers. The
 * second is for tuning the gate's thresholds; the first exists because a step count that came out
 * wrong cannot be argued about without the CSV this screen writes, and the person holding the wrong
 * count is never the person holding a debug build.
 */
@Composable
fun SensorScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val state by SensorFeed.state.collectAsStateWithLifecycle()
    val reading = state.reading
    val latest = state.samples.lastOrNull()
    val context = LocalContext.current
    // Held one level above the recorder that writes it: the confirmation is drawn over the whole
    // screen, and a composable cannot paint outside the card it sits in. The recorder still owns
    // every `status =` — it is handed the same holder rememberStatusLine gives it, which still takes
    // the word back down after the window.
    val traceStatus = rememberStatusLine()

    // The screen feeds itself rather than relying on the service. Two reasons: on a device with no
    // step counter the service stops immediately, and even when it runs it batches at 2 s, which
    // makes for a stuttering graph. SensorFeed drops duplicate timestamps, so both producers
    // running at once is harmless.
    DisposableEffect(Unit) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = SensorFeed.add(
                Accel(event.timestamp, event.values[0], event.values[1], event.values[2]),
                power.isInteractive,
                SystemClock.elapsedRealtimeNanos(),
            )

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(listener, it, 40_000) // 25 Hz, unbatched: this is a live view
        }
        onDispose { sensors.unregisterListener(listener) }
    }

    Column(modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.sensors_title), onBack)
        // The trace recorder sits below the fold on a phone screen. Without a scroll it would simply be
        // cut off, which reads as a missing feature rather than as content further down.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
            ) {
                GateBanner(reading)
                Spacer(Modifier.height(16.dp))

                Wave(state.samples)
                Spacer(Modifier.height(20.dp))

                PwnCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(PwnDimensions.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Words on screen; the trace CSV keeps the enum names, so shared traces
                        // stay comparable across languages.
                        Field(stringResource(R.string.field_motion), stringResource(reading.motion.label()))
                        Field(stringResource(R.string.field_posture), stringResource(reading.posture.label()))
                        Field(stringResource(R.string.field_energy), "%.2f m/s²".format(Locale.US, reading.std))
                        Field(stringResource(R.string.field_cadence), "%.2f Hz".format(Locale.US, reading.cadenceHz))
                        Field(
                            stringResource(R.string.field_periodicity),
                            "%.2f".format(Locale.US, reading.periodicity),
                        )
                        Field(stringResource(R.string.field_tilt), "%.0f°".format(Locale.US, reading.tiltDeg))
                        Field(
                            stringResource(R.string.field_screen),
                            stringResource(if (reading.screenOn) R.string.on else R.string.off),
                        )
                        latest?.let {
                            Field(
                                stringResource(R.string.field_axes),
                                "%.1f  %.1f  %.1f".format(Locale.US, it.x, it.y, it.z),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                PwnCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(PwnDimensions.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Field(stringResource(R.string.field_raw_counter), state.rawCounter.formatted())
                        Field(stringResource(R.string.field_credited), state.credited.formatted())
                        Field(stringResource(R.string.field_rejected), state.rejected.formatted())
                    }
                }

                Spacer(Modifier.height(12.dp))
                TraceRecorder(traceStatus)
                Spacer(Modifier.height(12.dp))
                RepTraceAnalyser()
            }
            // "Saved" over the middle of the screen instead of a line at the foot of the recorder's
            // card. That card is the third of four here, under a 160dp graph and two tables, which
            // is the reason the scroll above it exists at all: a line there would be written below the
            // fold, where the news of the write could not be read without going to look for it. It hangs
            // off the Box rather than the scrolling Column so it neither travels with the content
            // nor is clipped by the Card it is announcing.
            PwnActionFlash(traceStatus.value)
        }
    }
}

/**
 * The most of a picked file [RepTraceAnalyser] reads. The recorder stops at 200 000 rows of some 130
 * bytes, about thirty megabytes, so a file past twice that is not a trace it wrote.
 */
private const val MAX_TRACE_CSV_BYTES = 64 * 1024 * 1024

/**
 * Runs a recorded trace through every accelerometer-based [RepProfile] and reports what each would
 * have counted. This is how the thresholds get checked: jump with the recorder running, load the
 * CSV, compare against the number you counted yourself, and edit [REP_PROFILES].
 *
 * Gyroscope exercises are missing from the list because the trace does not record a gyroscope.
 */
@Composable
private fun RepTraceAnalyser() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // Not rememberStatusLine, which the ticks elsewhere use: what lands here is the measurement —
    // a line per profile, read against the count you kept yourself — and a report that takes itself
    // down after three seconds is a report nobody finishes. The next load replaces it.
    var status by remember { mutableStateOf<String?>(null) }

    // A two-hour trace is tens of megabytes; parsing it on the main thread freezes the screen for
    // long enough to look crashed.
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        status = resources.getString(R.string.rep_trace_working)
        scope.launch {
            status = withContext(Dispatchers.Default) {
                runCatching {
                    // Read no further than a trace goes: a large file picked by mistake would be read whole,
                    // for as long as the memory held, and the step service shares that memory.
                    val bytes = context.contentResolver.openInputStream(uri)!!.use {
                        it.readNBytes(MAX_TRACE_CSV_BYTES + 1)
                    }
                    if (bytes.size > MAX_TRACE_CSV_BYTES) {
                        return@runCatching resources.getString(R.string.file_import_out_of_memory)
                    }
                    val samples = RepCounter.samplesFromTraceCsv(bytes.decodeToString())
                    val rows = REP_PROFILES
                        .filterValues { it.signal != RepSignal.ROTATION }
                        .map { (code, profile) ->
                            resources.repRow(code, RepCounter.count(samples, profile, handled = true))
                        }
                    // Jump height comes off the flight time, not off any profile's thresholds, so
                    // it is one line for the whole recording rather than one per exercise.
                    val jumpM = jumpHeightM(samples)
                    val jump = if (jumpM > 0.0) {
                        listOf(resources.getString(R.string.rep_trace_jump, (jumpM * 100).roundToInt()))
                    } else {
                        emptyList()
                    }
                    (
                        listOf(
                            resources.getString(
                                R.string.rep_trace_span,
                                "%.1f".format(Locale.US, samples.spanMinutes()),
                            ),
                        ) + rows + jump
                        ).joinToString("\n")
                }.getOrElse { failure ->
                    // A file that would not load, in a file import's words, not "Save error".
                    if (failure is OutOfMemoryError) {
                        resources.getString(R.string.file_import_out_of_memory)
                    } else {
                        resources.getString(R.string.file_import_error, failure.message ?: failure.javaClass.simpleName)
                    }
                }
            }
        }
    }

    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.rep_trace_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.rep_trace_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                // Files apps hand CSVs out under all three types, and a picker that greys out the
                // file the app itself just wrote is worse than one that shows a text file too.
                onClick = { open.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
            ) {
                Text(stringResource(R.string.rep_trace_load))
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/**
 * Records the raw feed to a CSV the person can hand over. Walking with this on is how a wrong count
 * stops being a guess: every sample, every gate verdict and every step decision, in order.
 */
@Composable
private fun TraceRecorder(statusLine: MutableState<String?>) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val trace by SensorTrace.state.collectAsStateWithLifecycle()
    var status by statusLine
    val savedText = stringResource(R.string.trace_saved)

    // Two hours of recording is tens of megabytes through a SAF provider that may be cloud-backed:
    // building and writing that on the main thread is seconds of frozen screen.
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // Caught inside the switch, not around it: around it, a page left mid-save would have its
            // cancellation taken for a failed write and reported as one.
            status = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use {
                        SensorTrace.writeCsv(it)
                    }
                }
            }.fold(
                onSuccess = {
                    // The rows were worth keeping until they were in a file; now they are.
                    SensorTrace.discard()
                    savedText
                },
                onFailure = { resources.getString(R.string.trace_error, it.message ?: "") },
            )
        }
    }

    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.trace_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.trace_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(
                stringResource(R.string.trace_rows),
                pluralStringResource(R.plurals.trace_rows_value, trace.rows, trace.rows),
            )
            if (trace.dropped > 0) {
                Field(stringResource(R.string.trace_dropped), trace.dropped.toLong().formatted())
            }
            if (trace.recording) {
                Text(
                    stringResource(R.string.trace_recording),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            // Save goes onto a line of its own when the two do not fit side by side. In a Row the
            // first button would take its whole width and Save be given what was left, which a long
            // locale or a large font leaves too narrow to hold its label.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (trace.recording) {
                    Button(onClick = {
                        // The same three counters the recording started with, so the file carries
                        // its own before/after and the session's deltas need no row arithmetic.
                        SensorTrace.stopCounters(SensorFeed.state.value)
                        SensorTrace.stop()
                    }) {
                        Text(stringResource(R.string.trace_stop))
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                val dao = StepsDb.get(context).dao()
                                val persisted = dao.state()
                                val today = LocalDate.now().toString()
                                val startedAtEpochMillis = System.currentTimeMillis()
                                val startedElapsedNanos = SystemClock.elapsedRealtimeNanos()
                                val snapshot = persisted?.toStepState()
                                val dev = SensorFeed.state.value
                                SensorTrace.start(
                                    TraceSessionMetadata(
                                        startedAtEpochMillis = startedAtEpochMillis,
                                        startedAtElapsedRealtimeNanos = startedElapsedNanos,
                                        baselineRawCounter = snapshot?.lastCumulative
                                            ?: dev.rawCounter,
                                        bootId = snapshot?.bootId ?: startedAtEpochMillis -
                                            startedElapsedNanos / 1_000_000L,
                                        day = today,
                                        accumulatorState = snapshot,
                                        // A feed that has seen no step event still reads 0, which
                                        // is not a baseline; the persisted counter is.
                                        startRawCounter = dev.rawCounter.takeIf { it > 0 }
                                            ?: snapshot?.lastCumulative,
                                        startCredited = dev.credited,
                                        startRejected = dev.rejected,
                                        // Which of the three step tables the day's figure comes
                                        // from. A total above the local one means the number on
                                        // screen is Health Connect's or a band's, and its movement
                                        // is not what the accumulator credited — see the dao's
                                        // day(), which is a MAX over days, external_step_days and
                                        // direct_ble_step_days. Without this pair a trace cannot
                                        // tell a step the gate refused from a step this phone
                                        // never counted in the first place.
                                        extras = mapOf(
                                            "day.local" to (dao.localStepsOn(today) ?: 0L).toString(),
                                            "day.total" to (dao.stepsOn(today) ?: 0L).toString(),
                                        ),
                                    ),
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.trace_start))
                    }
                }
                // Saving mid-recording writes a snapshot rather than refusing: a disabled button
                // with no explanation is indistinguishable from a broken one.
                OutlinedButton(
                    onClick = { save.launch("pwnfit-trace.csv") },
                    enabled = trace.rows > 0,
                ) { Text(stringResource(R.string.trace_save)) }
            }
        }
    }
}

private fun Resources.repRow(code: String, count: RepCount) = getString(
    R.string.rep_trace_row,
    getString(ACTIVITY_CATALOG.first { it.code == code }.titleRes),
    count.reps,
    "%.2f".format(Locale.US, count.cadenceHz),
    "%.0f".format(Locale.US, count.activeSeconds),
)

private fun StateRow.toStepState() = StepState(
    bootId = bootId,
    lastCumulative = lastCumulative,
    lastEventNanos = lastEventNanos,
    pendingSteps = pendingSteps,
    boutStartedNanos = boutStartedNanos,
    boutLastNanos = boutLastNanos,
    boutConfirmed = boutConfirmed,
    rateWindowStartedNanos = rateWindowStartedNanos,
    rateWindowStartCounter = rateWindowStartCounter,
    pendingDay = pendingDay,
    pendingDaySteps = pendingDaySteps,
)

@Composable
private fun GateBanner(reading: MotionReading) {
    val open = reading.stepsAllowed
    Text(
        text = stringResource(if (open) R.string.gate_open else R.string.gate_blocked),
        style = MaterialTheme.typography.titleMedium,
        // Status colour always ships with its label, never on its own.
        color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

/** Acceleration magnitude over the last few seconds. One series, so no legend — the title names it. */
@Composable
private fun Wave(samples: List<Accel>) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant

    Text(
        text = stringResource(R.string.wave_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        if (samples.size < 2) return@Canvas
        val magnitudes = samples.map { it.magnitude }
        val low = magnitudes.min()
        val high = magnitudes.max()
        val span = (high - low).coerceAtLeast(0.5f) // never divide a flat line by zero
        val mean = magnitudes.average().toFloat()

        fun y(value: Float) = size.height * (1f - (value - low) / span)

        // Hairline mean rule, one shade off the surface.
        drawLine(axis, Offset(0f, y(mean)), Offset(size.width, y(mean)), strokeWidth = 1f)

        val path = Path()
        magnitudes.forEachIndexed { i, magnitude ->
            val x = size.width * i / (magnitudes.size - 1)
            if (i == 0) path.moveTo(x, y(magnitude)) else path.lineTo(x, y(magnitude))
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun Field(label: String, value: String) {
    // The label yields and the reading does not, as in the route page's telemetry rows: with
    // neither weighted, a label long enough to take the whole row would leave the value no width at all.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            Modifier.weight(1f, fill = false).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the gate made of the movement, in words: the enum's name is for the trace file. */
private fun Motion.label(): Int = when (this) {
    Motion.UNKNOWN -> R.string.m3_work_sensor_unclassified
    Motion.STILL -> R.string.m3_work_motion_still
    Motion.WALKING -> R.string.track_movement_walking
    Motion.RUNNING -> R.string.track_movement_running
    Motion.VEHICLE -> R.string.track_movement_vehicle
    Motion.SHAKING -> R.string.m3_work_motion_shaking
    Motion.NON_GAIT -> R.string.m3_work_motion_non_gait
}

private fun Posture.label(): Int = when (this) {
    Posture.UNKNOWN -> R.string.m3_work_sensor_unclassified
    Posture.IN_HAND -> R.string.m3_work_posture_in_hand
    Posture.POCKET -> R.string.m3_work_posture_pocket
    Posture.FLAT -> R.string.m3_work_posture_flat
}
