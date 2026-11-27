package com.pwnall.health

import android.content.Context
import android.content.res.Resources
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Collator
import kotlin.math.roundToInt

/**
 * Workouts the clock alone measures: nothing in a dance, a match or a swim moves a pocketed phone in a
 * way it could count. They are one card among the exercises, and one list behind it.
 */
internal val OTHER_WORKOUT_CODES = setOf(
    "CYCLING",
    "ELLIPTICAL",
    "ROWING_MACHINE",
    "SWIMMING",
    "HIIT",
    "BOXING",
    "DANCING",
    "BASKETBALL",
    "FOOTBALL",
    "TENNIS",
    "PILATES",
    "YOGA",
    "STRETCHING",
)

/** Walked or run: indoors the step counter counts them, outdoors the walk page records the route. */
internal val ON_FOOT_CODES = setOf("WALK", "RUN", "HIKING")

/** Exercises whose count is jumps rather than repetitions. */
private val JUMP_CODES = setOf("JUMP_ROPE", "JUMPING_JACKS")

/** Counted by the step counter rather than by a [REP_PROFILES] entry; the stairs' floors by the barometer. */
private fun stepped(code: String) = code in ON_FOOT_CODES || code == "STAIRS"

/** The catalogue's [OTHER_WORKOUT_CODES] in the order of their names, as their list shows them. */
@Composable
internal fun otherWorkouts(): List<ActivitySpec> {
    val resources = LocalResources.current
    val collator = Collator.getInstance(LocalLocale.current.platformLocale)
    return ACTIVITY_CATALOG.filter { it.code in OTHER_WORKOUT_CODES }
        .sortedWith(compareBy(collator) { resources.getString(it.titleRes) })
}

/**
 * Where the phone has to be for [code] to be counted, said on the exercise's page before Start; null
 * for one it cannot follow. The place is the one the sensor sees the movement from: a landing anywhere
 * on the hips, a squat's or a bridge's turn only on the thigh, a push-up's only on the upper arm, and
 * the top of a pull-up from a pocket.
 */
internal fun phonePlacementRes(code: String): Int? = when {
    code == "STAIRS" -> R.string.track_phone_stairs
    stepped(code) -> R.string.track_phone_steps
    code == "SQUATS" || code == "LUNGES" || code == "HIP_THRUST" -> R.string.track_phone_thigh
    code == "PUSHUPS" -> R.string.track_phone_arm
    REP_PROFILES[code]?.signal == RepSignal.LIFT -> R.string.track_phone_lift
    code in REP_PROFILES -> R.string.track_phone_pocket
    else -> null
}

/** What a count of [code] is said in: steps on foot, jumps on a rope, repetitions otherwise. */
private fun countNounRes(code: String): Int = when {
    stepped(code) -> R.plurals.track_steps
    code in JUMP_CODES -> R.plurals.track_jumps
    else -> R.plurals.count_repetitions
}

/** The same count's short unit, under its figure in a box. */
private fun countUnitRes(code: String): Int = when {
    stepped(code) -> R.string.steps_unit
    code in JUMP_CODES -> R.string.track_unit_jumps
    else -> R.string.workout_reps_short
}

/** A count of [code] that is not repetitions — steps, jumps — and so is said in its own words. */
internal fun countsInOwnWords(code: String): Boolean = stepped(code) || code in JUMP_CODES

/** What the phone counted in a set, in words: "3 floors · 156 steps", "120 jumps", "12 repetitions". */
internal fun trackedText(resources: Resources, code: String, count: Int, floors: Int): String = listOfNotNull(
    resources.getQuantityString(R.plurals.track_floors, floors, floors).takeIf { floors > 0 },
    resources.getQuantityString(countNounRes(code), count, count).takeIf { count > 0 || floors == 0 },
).joinToString(" · ")

/** What a set stopped with: its count of steps, jumps or reps, and the floors of a climb. */
internal data class TrackedCount(val count: Int, val floors: Int)

/**
 * Counts a set of [code] from the phone's own sensors between [start] and [stop]: the step counter on
 * foot and on the stairs, the barometer for the stairs' floors, and for the exercises [REP_PROFILES]
 * knows, the accelerometer or the gyroscope through [RepCounter] — the counter the activity form and
 * the sensor page use, so a threshold tuned on a recording is the one counting here.
 *
 * A partial wake lock is held while a set runs: the phone goes into a pocket with its screen locked,
 * and with the processor asleep neither the accelerometer nor the barometer delivers anything. The
 * step counter keeps its count in its own chip and needs none of it. A pause lets the sensors and the
 * lock go ([stop]), and [resume] goes on from what was counted, the pause left out.
 *
 * The lock keeps the processor awake; it does not keep the samples coming. Behind a locked screen
 * the app is in the background, and Android stops delivering a continuous sensor to a background
 * app after about a minute unless something of the app's runs in the foreground — here only the step
 * service, which runs where steps may be counted and not otherwise. So the tracker watches its own
 * signal: a set whose samples stopped ([signalLost]) is [interrupted], says so on its tile, and ends
 * on no count rather than on the part of one it happened to catch.
 *
 * Limitation: the samples live as long as the page. Leaving it mid-set and coming back counts from the
 * return; the question at Stop is there for that, and for every other miscount.
 */
@Stable
internal class SetTracker(private val code: String, context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val wake = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pwnhealth:workout-set")
        ?.apply { setReferenceCounted(false) }
    private val profile = REP_PROFILES[code].takeUnless { stepped(code) }
    private val motion: Sensor? = when {
        stepped(code) -> sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        profile?.signal == RepSignal.ROTATION -> sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        profile != null -> sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        else -> null
    }
    private val barometer: Sensor? = if (code == "STAIRS") sensors?.getDefaultSensor(Sensor.TYPE_PRESSURE) else null

    // SensorManager delivers on the main looper, which is also where the recount snapshots and [final]
    // reads: one list, one thread, no lock.
    private val samples = mutableListOf<Accel>()

    // Where in [samples] each stretch after a pause begins ([resume]).
    private val cuts = mutableListOf<Int>()
    private var firstSteps = -1f
    private var floorTracker = FloorTracker()
    private var climbedM = 0.0

    var count by mutableIntStateOf(0)
        private set
    var floors by mutableIntStateOf(0)
        private set

    /**
     * Nothing on this phone could count the set — no sensor, or the step counter not allowed — or the
     * set was [interrupted]. Either way it is asked about by hand.
     */
    var blind by mutableStateOf(motion == null && barometer == null)
        private set

    /**
     * The rep signal went quiet in the middle of this set, or outran the samples a set may hold
     * ([MAX_SET_SAMPLES]). What was counted is then short by nobody knows how much, so the set is
     * [blind] from there to its end, whatever arrives afterwards.
     */
    var interrupted by mutableStateOf(false)
        private set

    // When the rep signal last delivered, on SystemClock.elapsedRealtime; 0 while it is not listened
    // to. Only that signal is watched: it is continuous, so its silence means something, while the
    // step counter speaks when a step is taken and catches up on its own.
    private var lastSampleAt = 0L

    private fun watchSignal() {
        if (lastSampleAt != 0L && signalLost(lastSampleAt, SystemClock.elapsedRealtime())) {
            interrupted = true
            blind = true
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                // Counted since the phone started; the first reading of the set is its nought, and the
                // first after a pause carries on from the count it stopped at.
                Sensor.TYPE_STEP_COUNTER -> {
                    if (firstSteps < 0f) firstSteps = event.values[0] - count
                    count = (event.values[0] - firstSteps).toInt()
                }

                Sensor.TYPE_PRESSURE -> {
                    val altitude = SensorManager.getAltitude(
                        SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                        event.values[0],
                    )
                    // Limitation: walking = true. A lift raises the phone as the stairs do, and the
                    // person said stairs.
                    climbedM += floorTracker.add(event.timestamp, altitude.toDouble(), walking = true)
                    floors = floorsClimbed(climbedM)
                }

                else -> {
                    // Read before the mark moves: a gap nothing was running to see is seen as it closes.
                    watchSignal()
                    lastSampleAt = SystemClock.elapsedRealtime()
                    if (samples.size < MAX_SET_SAMPLES) {
                        samples += Accel(event.timestamp, event.values[0], event.values[1], event.values[2])
                    } else {
                        // Full: a count that stopped growing here would be offered as the whole set's.
                        interrupted = true
                        blind = true
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** A set begins: everything from nought, and the sensors on. */
    fun start() {
        samples.clear()
        cuts.clear()
        climbedM = 0.0
        count = 0
        floors = 0
        interrupted = false
        listen()
    }

    /**
     * The set goes on after a pause, from what it had counted: the pause, [stop]ped, heard nothing,
     * and what the phone did in it — a walk to the bench, a ride in the lift — is not the set's.
     */
    fun resume() {
        cuts += samples.size
        listen()
    }

    private fun listen() {
        firstSteps = -1f
        // A fresh nought for the barometer: the climb of a pause is not the set's, the climb before it is.
        floorTracker = FloorTracker()
        val rate = if (profile != null) REP_RATE_US else SensorManager.SENSOR_DELAY_NORMAL
        val listening = listOfNotNull(motion, barometer).map { sensors?.registerListener(listener, it, rate) == true }
        blind = interrupted || listening.none { it }
        lastSampleAt = if (profile != null && !blind) SystemClock.elapsedRealtime() else 0L
        if (!blind) wake?.acquire(MAX_SET_MILLIS)
    }

    fun stop() {
        // The signal's last word on this set, for a count read once the sensors are off.
        watchSignal()
        lastSampleAt = 0L
        sensors?.unregisterListener(listener)
        if (wake?.isHeld == true) wake.release()
    }

    /** Recounts the reps so far, off the main thread: sorting and smoothing a long set is not free. */
    suspend fun recount() {
        val counting = profile ?: return
        watchSignal()
        if (blind) return
        val snapshot = samples.toList()
        val stretches = cuts.toList()
        count = withContext(Dispatchers.Default) { repsAcrossPauses(snapshot, stretches, counting, handled = true) }
    }

    /**
     * The count the set ends on, the reps recounted over every sample: the half-second recount lags. A set
     * begun and ended by a tap was [handled] at both ends and loses those seconds; one the clock began and
     * ended stayed in the pocket throughout and keeps them. An [interrupted] set ends on nought: the
     * question that follows opens on its boxes then, where a part of the count would have been offered
     * as the whole of it.
     */
    fun final(handled: Boolean = true): TrackedCount {
        watchSignal()
        profile?.let {
            count = if (interrupted) 0 else repsAcrossPauses(samples, cuts, it, handled)
        }
        return TrackedCount(count, floors)
    }
}

/**
 * The reps in [samples] of a set that may have been paused, each stretch after a pause beginning at
 * one of [cuts]. A set never paused is counted whole, [handled] at its ends or not. Pause and
 * Continue are taps as Stop and Start are — the phone out of the pocket and back into it — so a
 * paused set has each stretch counted as a tapped set is, its handling cut at both ends; counted
 * whole, every trip around a pause would shake the phone like a rep or two. A set the clock began or ended
 * loses those seconds at that end as well, which is the lesser miscount.
 */
internal fun repsAcrossPauses(samples: List<Accel>, cuts: List<Int>, profile: RepProfile, handled: Boolean): Int =
    if (cuts.isEmpty()) {
        RepCounter.count(samples, profile, handled).reps
    } else {
        (listOf(0) + cuts + samples.size).zipWithNext { from, until ->
            RepCounter.count(samples.subList(from, until), profile, handled = true).reps
        }.sum()
    }

/**
 * Whether a rep signal that last delivered at [lastSampleAt] has gone quiet by [now], both on
 * SystemClock.elapsedRealtime. It arrives fifty times a second while it arrives at all, so five
 * seconds of silence is not jitter or a busy main thread: Android has stopped delivering it, and at
 * least a rep or two went uncounted.
 */
internal fun signalLost(lastSampleAt: Long, now: Long): Boolean = now - lastSampleAt > SIGNAL_LOST_MILLIS

private const val SIGNAL_LOST_MILLIS = 5_000L

/**
 * The [SetTracker] for [code], counting while [running] and not [paused]; null for an exercise the
 * phone cannot follow. A pause lets the sensors and the wake lock go and keeps what was counted, and
 * Continue goes on from there; a set that stops running and runs again is a new set, from nought.
 */
@Composable
internal fun rememberSetTracker(code: String, running: Boolean, paused: Boolean = false): SetTracker? {
    val context = LocalContext.current
    val tracker = remember(code) {
        SetTracker(code, context.applicationContext).takeIf { phonePlacementRes(code) != null }
    }
    val listening = running && !paused
    // Whether this run of the set has listened yet: the first listen starts it, one after a pause resumes it.
    val begun = remember(tracker, running) { BooleanArray(1) }
    DisposableEffect(tracker, listening) {
        if (listening) {
            if (begun[0]) tracker?.resume() else tracker?.start()
            begun[0] = true
        }
        onDispose { tracker?.stop() }
    }
    // The whole set recounted twice a second rather than incrementally, so the figure on the screen is
    // the one the trace analysis would give.
    LaunchedEffect(tracker, listening) {
        while (listening && tracker != null) {
            delay(RECOUNT_MILLIS)
            tracker.recount()
        }
    }
    return tracker
}

/** Where the phone goes for the exercise to be counted: a phone in its disc, and the place beside it. */
@Composable
internal fun PhonePlacementCard(code: String) {
    val placement = phonePlacementRes(code) ?: return
    RoutineCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 14.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(PHONE_DISC).background(PwnIndigoSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Smartphone, null, Modifier.size(20.dp), tint = PwnMuted)
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.track_phone_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
                Text(stringResource(placement), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * What the phone has counted of the set under way, as the template session's tiles show its figures:
 * "Tracked · 24 jumps", "Tracked · 2 floors · 96 steps". A phone with nothing to count it says so,
 * and so does a set whose count was interrupted — a different thing to be told than a missing sensor.
 */
@Composable
internal fun TrackedTile(code: String, tracker: SetTracker) {
    val value = when {
        tracker.interrupted -> stringResource(R.string.track_count_interrupted)
        tracker.blind -> stringResource(R.string.rep_counter_missing)
        else -> trackedText(LocalResources.current, code, tracker.count, tracker.floors)
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Tile(Icons.Outlined.Sensors, stringResource(R.string.track_tracked), value)
    }
}

/** The energy of the time a workout of the clock has run, by [netActivityCalories] as the journal will cost it. */
@Composable
internal fun BurnedTile(kcal: Double) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Tile(
            Icons.Outlined.LocalFireDepartment,
            stringResource(R.string.track_burned),
            "${kcal.roundToInt()} ${stringResource(R.string.unit_kcal)}",
        )
    }
}

/**
 * A tracked set just stopped: what the phone counted and whether that is right, as a template's set
 * asks whether it was done. Yes takes the count; No opens the figures — the count, a climb's floors,
 * a loaded lift's weight — for Done at the foot to take as corrected. Nothing counted opens on them.
 */
@Composable
internal fun CountQuestion(
    spec: ActivitySpec,
    seconds: Int,
    count: String,
    floors: String,
    load: String,
    loadUnit: String,
    onFigures: (count: String, floors: String, load: String) -> Unit,
    onRight: () -> Unit,
) {
    val resources = LocalResources.current
    var fixing by rememberSaveable { mutableStateOf(count.intNumber() == 0 && floors.intNumber() == 0) }
    RoutineCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (fixing) R.string.track_count_fix else R.string.track_count_question),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            val counted = trackedText(resources, spec.code, count.intNumber(), floors.intNumber())
            Text(
                "$counted · ${durationText(resources, seconds)}",
                style = MaterialTheme.typography.bodyMedium,
                color = PwnMuted,
                textAlign = TextAlign.Center,
            )
            if (fixing) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val unit = stringResource(countUnitRes(spec.code))
                    ValueBox(
                        value = count,
                        unit = unit,
                        label = unit,
                        modifier = Modifier.width(compactBoxWidth(count)),
                        keyboard = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                        onValue = { onFigures(it.filter(Char::isDigit).take(MAX_COUNT_DIGITS), floors, load) },
                    )
                    if (spec.code == "STAIRS") {
                        val floorUnit = stringResource(R.string.track_unit_floors)
                        ValueBox(
                            value = floors,
                            unit = floorUnit,
                            label = floorUnit,
                            modifier = Modifier.width(compactBoxWidth(floors)),
                            onValue = { onFigures(count, it.filter(Char::isDigit).take(3), load) },
                        )
                    }
                    if (spec.input == ActivityInput.WEIGHTED_SETS) {
                        ValueBox(
                            value = load,
                            unit = loadUnit,
                            label = stringResource(R.string.editor_working_weight),
                            modifier = Modifier.width(compactBoxWidth(load)),
                            keyboard = KeyboardType.Decimal,
                            onValue = { onFigures(count, floors, it.decimalInput()) },
                        )
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { fixing = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.common_no))
                    }
                    Button(onClick = onRight, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.common_yes))
                    }
                }
            }
        }
    }
}

/**
 * Which of the [OTHER_WORKOUT_CODES] it is, as a field with their list hanging off it — the shape the
 * attachment's kind is chosen in: the field names the question, the menu says it opens, and the tick
 * marks the one chosen. [onPick] null shows the choice without offering the list, as during a set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OtherWorkoutField(spec: ActivitySpec, onPick: ((ActivitySpec) -> Unit)?) {
    var choosing by remember { mutableStateOf(false) }
    val options = otherWorkouts()
    ExposedDropdownMenuBox(expanded = choosing, onExpandedChange = { choosing = it && onPick != null }) {
        OutlinedTextField(
            value = stringResource(spec.titleRes),
            onValueChange = {},
            readOnly = true,
            enabled = onPick != null,
            singleLine = true,
            label = { Text(stringResource(R.string.add_workout)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = choosing) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, onPick != null)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
            options.forEach { option ->
                val current = option.code == spec.code
                DropdownMenuItem(
                    text = { Text(stringResource(option.titleRes)) },
                    onClick = {
                        onPick?.invoke(option)
                        choosing = false
                    },
                    trailingIcon = if (current) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics { selected = current },
                )
            }
        }
    }
}

/** 50 Hz: fast enough to catch the peak of a landing, slow enough to stay unbatched and cheap. */
private const val REP_RATE_US = 20_000

/**
 * 40 minutes at 50 Hz. The rate is only asked for — Android delivers faster where the sensor runs
 * faster, and a phone at 200 Hz fills this in ten minutes — so a set that outruns it is interrupted
 * ([SetTracker.interrupted]): the buffer stops growing, and so would the count it was offering.
 */
private const val MAX_SET_SAMPLES = 120_000

/** The longest a set keeps the processor awake: a set forgotten under way does not drain the battery. */
private const val MAX_SET_MILLIS = 2 * 60 * 60 * 1_000L

private const val RECOUNT_MILLIS = 500L

/** Five figures: a long walk's steps. */
private const val MAX_COUNT_DIGITS = 5

private val PHONE_DISC = 36.dp
