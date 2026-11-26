package com.pwnall.health

import android.content.res.Resources
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What a stretch of a session done from a template is: getting ready, an exercise, or a rest. */
internal enum class PhaseKind { READY, EXERCISE, REST }

/**
 * One stretch of a session and how long it runs. For an exercise, [index] is its place in the plan,
 * [set] which of its [sets] this is, from 1 (0 for an exercise not counted in sets), and [restAfter]
 * the rest that follows its step, and [setRest] the rest after this set before the exercise's next. A
 * [manual] stretch waits for the person rather than a clock: a set of reps ends when "Complete set"
 * is held, the rest after it when the next set is started, and its [seconds] are only the estimate,
 * and for the rest the suggestion, the clock counts down.
 */
internal data class RoutinePhase(
    val kind: PhaseKind,
    val seconds: Int,
    val exercise: RoutineExercise? = null,
    val index: Int = -1,
    val set: Int = 0,
    val sets: Int = 0,
    val restAfter: Int = 0,
    val manual: Boolean = false,
    val setRest: Int = 0,
    /** For a rest between two sets, the [index] of the exercise whose sets it stands between; else -1. */
    val owner: Int = -1,
)

/**
 * A template as a session to be done, stretch by stretch: a few seconds to get ready, then each
 * exercise set by set — its planned minutes shared out among its sets — and the rest after each step
 * but the last. A superset goes round its exercises a set at a time, with no rest between them, as a
 * superset is done; an exercise on its own runs its sets in a row.
 */
internal fun routinePhases(blocks: List<RoutineBlock>): List<RoutinePhase> {
    val kept = blocks.filter { it.exercises.isNotEmpty() }
    var index = 0
    return buildList {
        add(RoutinePhase(PhaseKind.READY, READY_SECONDS))
        kept.forEachIndexed { step, block ->
            val rest = if (step < kept.lastIndex) block.restSeconds else 0
            val first = index
            val stretches = block.exercises.mapIndexed { at, exercise -> setStretches(exercise, first + at, rest) }
            index += block.exercises.size
            if (block.superset) {
                for (round in 0 until stretches.maxOf { it.size }) {
                    stretches.forEach { sets -> sets.getOrNull(round)?.let(::add) }
                }
            } else {
                stretches.forEach { sets ->
                    sets.forEach { stretch ->
                        add(stretch)
                        // The rest planned after a set: a timed one's runs out by itself, a set of reps'
                        // lasts until the next set is started.
                        if (stretch.setRest > 0) {
                            add(
                                RoutinePhase(
                                    PhaseKind.REST,
                                    stretch.setRest,
                                    manual = stretch.manual,
                                    owner = stretch.index,
                                ),
                            )
                        }
                    }
                }
            }
            if (rest > 0) add(RoutinePhase(PhaseKind.REST, rest))
        }
    }
}

/**
 * An exercise's planned minutes as its sets, each an even share — the last taking what the division
 * leaves — or as one stretch for an exercise not counted in sets, or counted in more than a session
 * could hold.
 */
private fun setStretches(exercise: RoutineExercise, index: Int, restAfter: Int): List<RoutinePhase> {
    // Planned set by set: a timed set for its time, a set of reps until it is said to be done.
    // Limitation: a superset of these goes round with no rest between rounds, so its total on the
    // template's page counts rests it never serves.
    val own = if (exercise.setsJson.isEmpty()) emptyList() else routineSets(exercise)
    if (own.isNotEmpty()) {
        return own.mapIndexed { set, figures ->
            RoutinePhase(
                kind = PhaseKind.EXERCISE,
                seconds = figures.duration(),
                exercise = exercise,
                index = index,
                set = if (own.size > 1) set + 1 else 0,
                sets = if (own.size > 1) own.size else 0,
                restAfter = restAfter,
                manual = figures.seconds == 0,
                setRest = if (set < own.lastIndex) figures.restSeconds else 0,
            )
        }
    }
    val total = (exercise.durationMinutes * 60).roundToInt().coerceAtLeast(1)
    val sets = exercise.sets?.takeIf { it in 2..MAX_SPLIT_SETS && it <= total } ?: 1
    return List(sets) { set ->
        RoutinePhase(
            kind = PhaseKind.EXERCISE,
            seconds = total / sets + if (set == sets - 1) total % sets else 0,
            exercise = exercise,
            index = index,
            set = if (sets > 1) set + 1 else 0,
            sets = if (sets > 1) sets else 0,
            restAfter = restAfter,
        )
    }
}

/**
 * One set as it went: the plan's figures where it had them, the seconds it took, the rest taken after
 * it, and the [reps] actually done where they fell short of the plan's.
 */
internal data class SetDone(val figures: RoutineSet?, val seconds: Int, val restAfter: Int, val reps: Int? = null) {
    /** The reps this set counts: those said to be done, else the plan's. */
    val repsDone: Int get() = reps ?: figures?.reps ?: 0

    /** The set as it went, the [exercise]'s own reps and load standing in for a plan kept in minutes. */
    fun asDone(exercise: RoutineExercise): RoutineSet = RoutineSet(
        reps = if (figures == null) reps ?: exercise.reps ?: 0 else repsDone,
        loadKg = if (figures == null) exercise.loadKg else figures.loadKg,
        seconds = seconds,
        restSeconds = restAfter,
        floors = figures?.floors ?: 0,
    )
}

/**
 * What became of one exercise of a session: the sets that were done, and how many sets and seconds of
 * work it was planned in. An exercise with no set done was not done. [restAfter] is the rest between
 * its step and the next, which the end page shows and the journal adds to the last set's rest.
 */
internal data class RoutineOutcome(
    val exercise: RoutineExercise,
    val done: List<SetDone>,
    val plannedSets: Int,
    val plannedSeconds: Int,
    val plannedReps: Int = 0,
    val restAfter: Int = 0,
)

/**
 * The session as it went, from the seconds [spent] on each stretch and which sets of reps were [held]
 * done: every exercise of the plan in its order, begun or not, with the sets that were done. A set of
 * reps counts once it was said to be done — "Complete set" held, or the reps done given when it was
 * skipped or the session finished on it ([doneReps], -1 for none given); a timed set counts for as long
 * as it was held, so three minutes of a five-minute plank are three minutes.
 */
internal fun routineOutcomes(
    phases: List<RoutinePhase>,
    spent: IntArray,
    held: BooleanArray,
    doneReps: IntArray,
): List<RoutineOutcome> {
    val positions = linkedMapOf<Int, MutableList<Int>>()
    // The rest after a step, kept by the exercise it followed.
    val stepRests = mutableMapOf<Int, Int>()
    var last = -1
    phases.forEachIndexed { at, stretch ->
        if (stretch.kind == PhaseKind.EXERCISE) {
            positions.getOrPut(stretch.index, ::mutableListOf) += at
            last = stretch.index
        } else if (stretch.kind == PhaseKind.REST && stretch.owner < 0 && last >= 0) {
            stepRests[last] = (stepRests[last] ?: 0) + spent[at]
        }
    }
    return positions.map { (index, sets) ->
        val exercise = checkNotNull(phases[sets.first()].exercise)
        val own = if (exercise.setsJson.isEmpty()) emptyList() else routineSets(exercise)
        val done = sets.mapNotNull { at ->
            val stretch = phases[at]
            // The rest straight after a set is that set's, as a session's own plan keeps it.
            val rest = phases.getOrNull(at + 1)?.takeIf { it.kind == PhaseKind.REST && it.owner == stretch.index }
                ?.let { spent[at + 1] } ?: 0
            SetDone(own.getOrNull((stretch.set - 1).coerceAtLeast(0)), spent[at], rest, doneReps[at].takeIf { it >= 0 })
                .takeIf { if (stretch.manual) held[at] else spent[at] > 0 }
        }
        RoutineOutcome(
            exercise,
            done,
            sets.size,
            sets.sumOf { phases[it].seconds },
            own.sumOf { it.reps },
            restAfter = stepRests[index] ?: 0,
        )
    }
}

/**
 * How long, in milliseconds, a stretch begun at [startedAt] has run at [at]: what it had run when it
 * was paused ([pausedRan], -1 while its clock runs), else the clock's own figure. A pause keeps what
 * ran, not what was left of the stretch's seconds: for a set of reps and the rest after it those are
 * only an estimate, and a pause taken past it would otherwise cut the stretch back to the estimate.
 */
internal fun stretchRan(pausedRan: Long, startedAt: Long, at: Long): Long =
    if (pausedRan >= 0) pausedRan else (at - startedAt).coerceAtLeast(0)

/**
 * Where the clock has carried a session that stood at [phase], begun at [startedAt], by [now]: past
 * every timed stretch whose seconds have run out — several at once for a page come back from behind
 * another app — each begun where the one before it ran out, and no further than the first stretch
 * that waits for the person: a set of reps waits for its "Complete set". The stretch reached and the
 * moment it began; [phase] and [startedAt] themselves while the stretch under way still runs.
 */
internal fun clockCaughtUp(phases: List<RoutinePhase>, phase: Int, startedAt: Long, now: Long): Pair<Int, Long> {
    var at = phase
    var start = startedAt
    while (at < phases.size && !phases[at].manual && now - start >= phases[at].seconds * 1_000L) {
        start += phases[at].seconds * 1_000L
        at++
    }
    return at to start
}

/**
 * The seconds a stretch that ran [millis] counts for. One the clock ends never outruns its plan. One
 * that waits for the person could wait all night, and every second of it is the journal's minutes and
 * energy — so it counts for no longer than a rest may ([MAX_COUNTED_REST_SECONDS]), or than it was
 * planned at where that is longer.
 */
internal fun RoutinePhase.countedSeconds(millis: Long): Int {
    val ran = (millis / 1_000).toInt().coerceAtLeast(0)
    return if (manual) ran.coerceAtMost(maxOf(seconds, MAX_COUNTED_REST_SECONDS)) else ran
}

/**
 * Whether leaving the session now throws work away: a set already done, as [routineOutcomes] counts
 * one — a set of reps said to be done, a timed one held for any time at all — or the exercise under
 * way ([at]) having [ran] a second or more. Getting ready and resting are no work.
 */
internal fun sessionHasWork(
    phases: List<RoutinePhase>,
    spent: IntArray,
    held: BooleanArray,
    at: Int,
    ran: Int,
): Boolean = phases.indices.any { index ->
    val stretch = phases[index]
    val done = if (stretch.manual) held[index] else spent[index] > 0
    stretch.kind == PhaseKind.EXERCISE && (done || (index == at && ran > 0))
}

/**
 * Whether "Complete set" asks about a set of reps before it is done rather than writing it as planned.
 * A set the phone [followed] is asked about with what it counted. One whose count was [interrupted] —
 * the signal went quiet mid-set, so the phone is [blind] to the rest of it — is asked too: its plan is
 * no truer than its count, and written as done in full it would be a set the person may well have
 * cut short. A phone that never could count it has nothing to ask about.
 */
internal fun completeAsks(followed: Boolean, blind: Boolean, interrupted: Boolean): Boolean =
    followed && (!blind || interrupted)

/** [exercise] done exactly as planned: what Log writes for a session already done. */
internal fun plannedOutcome(exercise: RoutineExercise): RoutineOutcome {
    val own = if (exercise.setsJson.isEmpty()) emptyList() else routineSets(exercise)
    val done = if (own.isEmpty()) {
        listOf(SetDone(null, (exercise.durationMinutes * 60).roundToInt(), 0))
    } else {
        own.mapIndexed { at, set -> SetDone(set, set.duration(), if (at == own.lastIndex) 0 else set.restSeconds) }
    }
    return RoutineOutcome(exercise, done, done.size, done.sumOf { it.seconds }, own.sumOf { it.reps })
}

/**
 * An exercise counted in sets alone — no reps, no load — as its sets and each one's seconds, the even
 * share the player counts it by; null for any other, which keeps its own figures, so a lift's working
 * weight is never dropped — and for one planned set by set, whose minutes hold its rests as well.
 */
internal fun routineSetSeconds(exercise: RoutineExercise): Pair<Int, Int>? {
    val total = (exercise.durationMinutes * 60).roundToInt()
    val sets = exercise.sets ?: 0
    val alone = exercise.setsJson.isEmpty() && exercise.reps == null && exercise.loadKg == null
    return if (alone && sets in 2..MAX_SPLIT_SETS && sets <= total) sets to total / sets else null
}

/** [routineSetSeconds] in words, as a plank's sets are written: "3 × 45 sec". */
internal fun routineSetSpan(resources: Resources, exercise: RoutineExercise): String? =
    routineSetSeconds(exercise)?.let { (sets, seconds) -> "$sets × ${durationText(resources, seconds)}" }

/** A span of seconds in words, never as a clock: "20 sec", "2 min", "5 min 30 sec". */
internal fun durationText(resources: Resources, seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return listOfNotNull(
        "$minutes ${resources.getString(R.string.unit_minutes)}".takeIf { minutes > 0 },
        "$rest ${resources.getString(R.string.breathing_seconds_unit)}".takeIf { rest > 0 || minutes == 0 },
    ).joinToString(" ")
}

/**
 * Doing a template: the exercise's drawing
 * across the top, with the way back, the session's own clock and the sound switch over it; a sheet
 * with where the session is, the exercise's name and figures, the whole session's progress, tiles
 * for the time left, the set and the rest to come, the drawing of how it is done where one is drawn,
 * and what comes next; and fixed at the foot, back, pause, skip and finish.
 *
 * Each stretch is counted down with three short tones before it ends and a longer one as the next
 * begins — taps of the motor on a phone that would play the tones at nothing ([BreathCues]) — and the
 * screen is kept on: the phone is on a bench or the floor. An exercise the phone can
 * count ([SetTracker]) is counted while its set runs, from a pocket: a timed set keeps what was counted,
 * and a set of reps asks at its end whether the count is right, or, counting nothing because the phone
 * lay on the bench, how many were done. Finishing, or reaching
 * the end, logs what was actually done ([routineOutcomes], [logSession]) — the time each set ran, the
 * sets of reps said to be done, an exercise left short marked so and one never reached as not done —
 * and the page says how many were logged; back, or finishing before anything was done, leaves without
 * logging anything. Back once something was done ([sessionHasWork]) asks first: it is one swipe from
 * the screen's edge, and "Complete set" is held for two seconds for less.
 *
 * The card of the next exercise does not open it: Skip does that, and a card just over Skip would be
 * hit by mistake.
 *
 * Limitation: counted from elapsedRealtime while the page is open — leaving the app and coming back
 * finds the session where the time says, but nothing sounds while the app is not on screen, since no
 * foreground service runs for it.
 */
@Composable
internal fun RoutinePlayer(
    modifier: Modifier,
    routine: Routine,
    rows: List<RoutineExercise>,
    imperial: Boolean,
    onLog: suspend (List<RoutineOutcome>) -> Int,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val phases = remember(rows) { routinePhases(routineBlocks(rows)) }
    val total = remember(phases) { phases.sumOf { it.seconds }.coerceAtLeast(1) }
    var phase by rememberSaveable(routine.id) { mutableIntStateOf(0) }
    var startedAt by rememberSaveable { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // How long the stretch had run when it was paused, in milliseconds; -1 while the clock runs.
    var pausedRan by rememberSaveable { mutableLongStateOf(-1L) }
    // The session's own clock: when it began, how long it stood paused, and since when it stands.
    val sessionStart by rememberSaveable { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var pausedFor by rememberSaveable { mutableLongStateOf(0L) }
    var pausedSince by rememberSaveable { mutableLongStateOf(0L) }
    var sound by rememberSaveable { mutableStateOf(true) }
    // What was actually done: the seconds spent on each stretch, the pauses left out, and which sets
    // of reps were said to be done.
    var spent by rememberSaveable(routine.id) { mutableStateOf(IntArray(phases.size)) }
    var held by rememberSaveable(routine.id) { mutableStateOf(BooleanArray(phases.size)) }
    var doneReps by rememberSaveable(routine.id) { mutableStateOf(IntArray(phases.size) { -1 }) }
    // A set of reps left by Skip or Finish rather than by "Complete set" asks first whether it was done.
    var asking by rememberSaveable { mutableStateOf<SetLeave?>(null) }
    // How many exercises the end logged; -1 until the session has ended. And what it logged, for the
    // end page to go through.
    var logged by rememberSaveable { mutableIntStateOf(-1) }
    var finished by rememberSaveable(stateSaver = DoneExercisesSaver) { mutableStateOf(emptyList<DoneExercise>()) }
    // The records the session is measured against, read just before it is written. Not kept through
    // a rotation: the done page then marks no record rather than one it can no longer check.
    var recordsBefore by remember { mutableStateOf<Map<String, ExerciseRecords>?>(null) }
    val recordsContext = LocalContext.current
    // Set from the start on a page that comes up with the summary and no count: the page before it
    // began the end, and a turn of the phone took it away while the session was being written. That
    // write lands without it ([logSession]), so the session is over here as well ([loggedCount]) — run
    // on from where it stood, it would be written a second time.
    // Limitation: the saved state is all that remembers an end begun, so a session that runs out while
    // the app is hidden, in a process killed before it is seen again, is restored from before its end
    // and written twice.
    var ending by remember { mutableStateOf(logged < 0 && finished.isNotEmpty()) }
    LaunchedEffect(Unit) { if (ending) logged = loggedCount(finished) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // Read where a cue is given, not where its coroutine began: the clock's loop and the write at the
    // end outlive the composition that started them, and the sound switch releases the generator that
    // composition held — a tone asked of a released one throws.
    val cues by rememberUpdatedState(rememberBreathCues(sound))
    // A tracker of its own for each set, so one set's samples never run into the next one's; null for
    // an exercise the phone cannot follow, and between sets.
    val setCode = phases.getOrNull(phase)?.takeIf { it.kind == PhaseKind.EXERCISE }?.exercise?.activityCode.orEmpty()
    // Paused with the session: what the phone does while it stands paused is not the set's, and the
    // wake lock is let go with the sensors.
    val tracker = key(phase) {
        rememberSetTracker(setCode, running = setCode.isNotEmpty() && logged < 0 && !ending, paused = pausedRan >= 0)
    }
    // Which set the tracker is counting, for the clock's loop, which can run several stretches on at once.
    val tracking by rememberUpdatedState(phase to tracker)
    // What the phone counted of a set of reps being left, while that is asked about; -1 for one it did not count.
    var counted by rememberSaveable { mutableIntStateOf(-1) }

    // On for the session, and not on the page it ends on, which is read in hand.
    val view = LocalView.current
    val awake = logged < 0
    DisposableEffect(view, awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    // The seconds the stretch under way counts for so far, its pauses left out; none past the last.
    fun runSoFar(): Int =
        phases.getOrNull(phase)?.countedSeconds(stretchRan(pausedRan, startedAt, SystemClock.elapsedRealtime())) ?: 0

    // Back with work done asks before it throws the session away; the question is held through a turn
    // of the phone. A session ended, or being written, has nothing left to lose.
    var leaving by rememberSaveable { mutableStateOf(false) }
    fun back() {
        val losing = logged < 0 && !ending && sessionHasWork(phases, spent, held, phase, runSoFar())
        if (losing) leaving = true else onClose()
    }
    BackHandler(onBack = ::back)
    if (leaving && logged < 0 && !ending) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.routines_leave_title)) },
            text = { Text(stringResource(R.string.routines_leave_body)) },
            confirmButton = {
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.workout_discard_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    fun spend(at: Int, seconds: Int) {
        spent = spent.copyOf().also { it[at] += seconds }
    }

    // A timed set the phone counted keeps the count — a rope's jumps, a walk's steps — as the clock moves
    // on; a set of reps is asked about as it is left instead. One the clock ended was never taken out of
    // the pocket, so it is not [SetTracker.final]'s handled set.
    fun keepCounted(at: Int, handled: Boolean) {
        if (phases.getOrNull(at)?.manual != false) return
        val setTracker = tracking.second?.takeIf { tracking.first == at } ?: return
        val count = setTracker.final(handled).count
        if (count > 0) doneReps = doneReps.copyOf().also { it[at] = count }
    }

    // A set of reps being left, asked about first — with what the phone counted of it, where it counted.
    fun leave(how: SetLeave) {
        counted = tracker?.takeUnless { it.blind }?.final()?.count ?: -1
        asking = how
    }

    fun end() {
        if (ending || logged >= 0) return
        if (phase < phases.size) {
            spend(phase, runSoFar())
            keepCounted(phase, handled = true)
        }
        val outcomes = routineOutcomes(phases, spent, held, doneReps)
        // Nothing done at all: nothing is logged or celebrated.
        if (outcomes.all { it.done.isEmpty() }) {
            onClose()
            return
        }
        ending = true
        finished = sessionSummary(outcomes)
        val codes = finished.map { it.code }
        // Undispatched and non-cancellable, as a breathing session's save is: the end can come while
        // this page is being taken down — a turn of the phone, a step back — and the session is written
        // all the same, neither dropped before it began nor left at the read before the write.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                recordsBefore = recordsByExercise(StepsDb.get(recordsContext).dao(), codes)
                logged = onLog(outcomes)
            }
            // Only for a page still there: one taken down has released its tone generator, and a tone
            // asked of a released one throws.
            ensureActive()
            // The end gets the longer tap, as a breathing session's does.
            cues.cue(ToneGenerator.TONE_PROP_ACK, 400, HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // Stretch [to], from its start, still paused if the session was; the one left counts what it ran.
    fun jump(to: Int) {
        if (phase < phases.size) {
            spend(phase, runSoFar())
            keepCounted(phase, handled = true)
        }
        val at = SystemClock.elapsedRealtime()
        phase = to
        startedAt = at
        now = at
        if (pausedRan >= 0) pausedRan = 0L
        if (to >= phases.size) end() else cues.cue(ToneGenerator.TONE_PROP_BEEP2, 300)
    }

    val running = pausedRan < 0 && logged < 0 && !ending
    LaunchedEffect(running) {
        // Woken on the stretch's own second boundaries, so the figures turn over with the tones.
        while (running) {
            now = SystemClock.elapsedRealtime()
            val (reached, reachedAt) = clockCaughtUp(phases, phase, startedAt, now)
            for (passed in phase until reached) {
                spend(passed, phases[passed].seconds)
                keepCounted(passed, handled = false)
                if (passed + 1 < phases.size) cues.cue(ToneGenerator.TONE_PROP_BEEP2, 300)
            }
            phase = reached
            startedAt = reachedAt
            if (phase >= phases.size) {
                end()
                break
            }
            delay(1_000L - (now - startedAt) % 1_000L)
        }
    }
    val current = phases.getOrNull(phase)
    if (logged >= 0 || current == null) {
        SessionDone(modifier, logged, finished, imperial, onClose, recordsBefore)
        return
    }
    val left = (current.seconds * 1_000L - stretchRan(pausedRan, startedAt, now)).coerceAtLeast(0)
    val secondsLeft = ((left + 999) / 1_000).toInt()
    val waitingRest = current.manual && current.kind == PhaseKind.REST
    LaunchedEffect(phase, secondsLeft) {
        if (!running) return@LaunchedEffect
        val clocked = !current.manual || waitingRest
        if (clocked && secondsLeft in 1..COUNTDOWN_TONES) cues.cue(ToneGenerator.TONE_PROP_BEEP, 120)
        // A rest that waits for the next set says when its suggested minute is up, as a stretch's end does.
        if (waitingRest && secondsLeft == 0) cues.cue(ToneGenerator.TONE_PROP_BEEP2, 300)
    }
    val elapsed = now - sessionStart - pausedFor - if (pausedRan >= 0) now - pausedSince else 0L
    // Only what the clock decides moves the bar: a stretch that waits for the person counts once done.
    val done = phases.take(phase).sumOf { it.seconds } + if (current.manual) 0.0 else current.seconds - left / 1_000.0
    // The next exercise other than this one: a superset's partner, or the next step's first.
    val upcoming = phases.drop(phase + 1).firstOrNull { it.exercise != null && it.index != current.index }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState())) {
                PhaseHero(current.exercise ?: upcoming?.exercise)
                Column(
                    Modifier.padding(top = HERO_HEIGHT - SHEET_OVERLAP)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
                        .background(MaterialTheme.colorScheme.background)
                        // The cards reach 10dp nearer the edges than the words.
                        .padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PhaseTitle(phases, phase, imperial, Modifier.padding(horizontal = 10.dp))
                    OverallProgress(
                        (done / total).toFloat().coerceIn(0f, 1f),
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    if (current.kind ==
                        PhaseKind.REST
                    ) {
                        RestCircle(secondsLeft, current.seconds)
                    } else {
                        PhaseTiles(current, secondsLeft)
                        tracker?.let { TrackedTile(setCode, it) }
                    }
                    val leave = asking
                    when {
                        !current.manual -> Unit

                        current.kind == PhaseKind.EXERCISE && leave != null -> SetQuestion(
                            figures = phaseFigures(current, imperial),
                            plannedReps =
                            current.exercise?.let(::routineSets)?.getOrNull((current.set - 1).coerceAtLeast(0))
                                ?.reps ?: 0,
                            code = setCode,
                            counted = counted.takeIf { it >= 0 },
                            modifier = Modifier.padding(horizontal = 10.dp),
                            onCancel = { asking = null },
                        ) { reps ->
                            // Done in full, or the reps that were done: written either way, and a set
                            // short of its reps marks the exercise's goal as not reached.
                            asking = null
                            if (reps != null) doneReps = doneReps.copyOf().also { it[phase] = reps }
                            if (reps == null || reps > 0) held = held.copyOf().also { it[phase] = true }
                            if (leave == SetLeave.FINISH) end() else jump(phase + 1)
                        }

                        // A counted set says what was counted before it is done, and one whose count was
                        // lost asks; any other is done as planned.
                        current.kind == PhaseKind.EXERCISE -> HoldToComplete(Modifier.padding(horizontal = 10.dp)) {
                            if (completeAsks(tracker != null, tracker?.blind == true, tracker?.interrupted == true)) {
                                leave(SetLeave.COMPLETE)
                            } else {
                                held = held.copyOf().also { it[phase] = true }
                                jump(phase + 1)
                            }
                        }

                        else -> Button(
                            onClick = { jump(phase + 1) },
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = PwnDimensions.mediumButtonHeight)
                                .padding(horizontal = 10.dp),
                        ) {
                            Text(
                                stringResource(R.string.workout_start_next_set),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    // Where the phone goes, while there is still time to put it there: on the rest before too.
                    (current.exercise ?: upcoming?.exercise)?.let { PhonePlacementCard(it.activityCode) }
                    current.exercise
                        ?.takeIf { current.kind == PhaseKind.EXERCISE }
                        ?.let { exercise -> ACTIVITY_CATALOG.firstOrNull { it.code == exercise.activityCode } }
                        ?.howTo()
                        ?.let { HowToCard(it) }
                    upcoming?.exercise?.let { NextCard(it, imperial) }
                }
            }
            // Fixed over the drawing: the way back on one side, the session's clock and its sound on
            // the other, where the thumb left them however far the sheet has been read.
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoutineHeroButton(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.common_back),
                    onPicture = true,
                    onClick = ::back,
                )
                Spacer(Modifier.weight(1f))
                SessionClock(elapsed)
                RoutineHeroButton(
                    if (sound) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    stringResource(R.string.breathing_sound),
                    onPicture = true,
                ) { sound = !sound }
            }
        }
        PlayerControls(
            paused = pausedRan >= 0,
            canGoBack = phase > 0,
            finishing = ending,
            onPrevious = { jump(phase - 1) },
            onPause = {
                val at = SystemClock.elapsedRealtime()
                if (pausedRan >= 0) {
                    // Continue: the stretch began as long ago as it had run when it was paused.
                    startedAt = at - pausedRan
                    pausedFor += at - pausedSince
                    pausedRan = -1L
                } else {
                    pausedRan = stretchRan(-1L, startedAt, at)
                    pausedSince = at
                }
                now = at
            },
            onSkip = { if (current.isRepsSet()) leave(SetLeave.SKIP) else jump(phase + 1) },
            onFinish = { if (current.isRepsSet()) leave(SetLeave.FINISH) else end() },
        )
    }
}

/**
 * The drawing of the exercise under way — or of the one coming, while getting ready or resting —
 * centred in what the buttons over it and the sheet's edge leave in sight: the box less the overlap.
 */
@Composable
private fun PhaseHero(exercise: RoutineExercise?) {
    val spec = exercise?.let { row -> ACTIVITY_CATALOG.firstOrNull { it.code == row.activityCode } }
    Box(
        Modifier.fillMaxWidth().height(HERO_HEIGHT).background(PwnIndigoSoft).padding(top = SHEET_OVERLAP),
        contentAlignment = Alignment.Center,
    ) {
        if (spec != null) {
            Image(themePainterResource(spec.heroArt()), null, Modifier.size(HERO_ART), colorFilter = washTint())
        } else {
            Icon(Icons.Outlined.Bedtime, null, Modifier.size(HERO_ART / 2), tint = PwnMuted)
        }
    }
}

/** Where the session is, the stretch's name in large type, and the exercise's figures under it. */
@Composable
private fun PhaseTitle(phases: List<RoutinePhase>, phase: Int, imperial: Boolean, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val current = phases[phase]
    val exercises = phases.filter { it.exercise != null }.distinctBy { it.index }.size
    // Getting ready and resting count as the exercise they lead into.
    val at = phases.drop(phase).firstOrNull { it.exercise != null }?.index ?: (exercises - 1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.lab_add_step, at + 1, exercises),
            style = MaterialTheme.typography.bodySmall,
            color = PwnMuted,
        )
        Text(
            when (current.kind) {
                PhaseKind.READY -> stringResource(R.string.routines_get_ready)
                PhaseKind.REST -> stringResource(R.string.workout_rest)
                PhaseKind.EXERCISE -> current.exercise?.let { routineExerciseName(resources, it) }.orEmpty()
            },
            // Said as each stretch begins, for whoever is not looking: the figures below change every
            // second and are not, or a screen reader would read a clock aloud.
            Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        current.exercise?.takeIf { current.kind == PhaseKind.EXERCISE }?.let {
            // The set under way, where each has its own figures: "10 × 25 kg".
            val set = routineSets(it).takeIf { _ ->
                it.setsJson.isNotEmpty()
            }?.getOrNull((current.set - 1).coerceAtLeast(0))
            Text(
                set?.let { figures -> routineSetText(resources, figures, imperial) }
                    ?: routineSetSpan(resources, it)
                    ?: routineExerciseSummary(resources, it, imperial),
                style = MaterialTheme.typography.bodyLarge,
                color = PwnMuted,
            )
        }
    }
}

/**
 * The whole session done so far, by time: a bar and its percentage. The track is drawn under the bar
 * rather than by it: M3 draws the two as round-capped strokes that only touch, which would leave a
 * notch of the page where the fill ends.
 */
@Composable
private fun OverallProgress(fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(
                stringResource(R.string.routines_overall),
                Modifier.weight(1f).alignByBaseline(),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
            Text(
                // Where the sign goes is the language's: "%50" in Turkish.
                stringResource(R.string.home_percent, (fraction * 100).roundToInt()),
                Modifier.alignByBaseline(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            trackColor = Color.Transparent,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/**
 * The tiles: the stretch's time left — for a rest that waits, the suggested minute — but for a set of
 * reps, which has no clock; the set, for an exercise counted in sets; the rest to come.
 */
@Composable
private fun PhaseTiles(current: RoutinePhase, secondsLeft: Int) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!current.manual) {
            Tile(Icons.Outlined.Schedule, stringResource(R.string.routines_left), secondsLeft.elapsedClockText())
        }
        if (current.set > 0) {
            Tile(
                Icons.Outlined.BarChart,
                stringResource(R.string.routines_set),
                stringResource(R.string.routines_of, current.set, current.sets),
            )
        }
        if (current.kind == PhaseKind.EXERCISE && current.restAfter > 0) {
            Tile(
                Icons.Outlined.LocalCafe,
                stringResource(R.string.routines_rest_after),
                durationText(LocalResources.current, current.restAfter),
                weight = REST_TILE_WEIGHT,
            )
        }
    }
}

/**
 * "Complete set", held for [SET_HOLD_MILLIS] rather than tapped: a set of reps ends when the person
 * says it has, and a brush of the screen on the bench must not end it. The button fills while it is
 * held and empties when let go early; the phone ticks once as the hold completes. A screen reader's
 * double tap completes it at once — a hold is not a gesture TalkBack passes through.
 */
@Composable
private fun HoldToComplete(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.workout_complete_set)
    val hint = stringResource(R.string.workout_hold_to_complete)
    val ink = MaterialTheme.colorScheme.onPrimary
    Box(
        modifier.fillMaxWidth()
            .heightIn(min = PwnDimensions.mediumButtonHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            // The ink, faint, so the words on the button keep their contrast however far it has filled.
            .drawBehind {
                drawRect(ink.copy(alpha = HOLD_FILL_ALPHA), size = size.copy(width = size.width * fill.value))
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val hold = scope.launch {
                            fill.animateTo(1f, tween(SET_HOLD_MILLIS, easing = LinearEasing))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            fill.snapTo(0f)
                            done()
                        }
                        tryAwaitRelease()
                        if (hold.isActive) {
                            hold.cancel()
                            scope.launch { fill.animateTo(0f, tween(HOLD_RELEASE_MILLIS)) }
                        }
                    },
                )
            }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                onClick(label) {
                    done()
                    true
                }
            }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(hint, color = ink, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** How a set of reps was left: skipped, the session finished on it, or — a set the phone counted — completed. */
private enum class SetLeave { SKIP, FINISH, COMPLETE }

private fun RoutinePhase.isRepsSet(): Boolean = manual && kind == PhaseKind.EXERCISE

/** The figures of the set under way, as the title shows them: "10 × 25 kg". */
@Composable
private fun phaseFigures(current: RoutinePhase, imperial: Boolean): String {
    val resources = LocalResources.current
    val exercise = current.exercise ?: return ""
    return routineSets(exercise).getOrNull((current.set - 1).coerceAtLeast(0))
        ?.let { routineSetText(resources, it, imperial) }
        ?: routineExerciseSummary(resources, exercise, imperial)
}

/**
 * The rest as a circle in the middle of the sheet: the time left in large type inside it and a ring
 * round its edge that empties as the rest runs out, turning smoothly between the seconds.
 */
@Composable
private fun RestCircle(secondsLeft: Int, seconds: Int) {
    val left by animateFloatAsState(
        if (seconds <= 0) 0f else (secondsLeft / seconds.toFloat()).coerceIn(0f, 1f),
        tween(1_000, easing = LinearEasing),
        label = "rest left",
    )
    ClockCircle(secondsLeft, { left }, stringResource(R.string.routines_left))
}

/** [seconds] as a clock in large type inside a ring filled to [fraction], with [label] under the figure. */
@Composable
internal fun ClockCircle(seconds: Int, fraction: () -> Float, label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(REST_CIRCLE), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxSize(),
                strokeWidth = REST_RING,
                trackColor = PwnIndigoSoft,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    seconds.elapsedClockText(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PwnMuted,
                )
            }
        }
    }
}

/**
 * Asked when a set of reps is skipped or the session finished on it: was it done? Yes counts it in
 * full; No asks how many reps were done, on a minus and a plus, and those are what is written — with
 * the exercise marked as short of its goal. Cancel goes back to the set, which is then neither.
 *
 * A set the phone [counted] — completed too — asks instead whether the count is right: Yes takes it,
 * No opens it on the minus and plus to be corrected, either way. One it counted nothing of, the phone
 * having lain on the bench, opens on them at the plan's reps, to be said by hand.
 *
 * Limitation: "counted nothing" is the whole test for a phone left lying, since a still phone clears
 * no threshold. A floor shaken by jumps beside it could count a few; No corrects them.
 */
@Composable
private fun SetQuestion(
    figures: String,
    plannedReps: Int,
    code: String,
    counted: Int?,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
    onAnswer: (Int?) -> Unit,
) {
    var short by rememberSaveable { mutableStateOf(counted == 0) }
    var reps by rememberSaveable {
        mutableIntStateOf(
            when (counted) {
                null -> (plannedReps - 1).coerceAtLeast(0)
                0 -> plannedReps
                else -> counted
            },
        )
    }
    // Said short of the plan, the reps are fewer than planned; a count can be out either way.
    val range = if (counted == null) 0..(plannedReps - 1).coerceAtLeast(0) else 0..MAX_SET_REPS
    val title = when {
        counted == null && short -> R.string.routines_reps_done_question
        counted == null -> R.string.routines_set_done_question
        counted == 0 -> R.string.routines_reps_done_question
        short -> R.string.track_count_fix
        else -> R.string.track_count_question
    }
    val said = counted?.takeIf { it > 0 && !short }?.let { trackedText(LocalResources.current, code, it, 0) }
    RoutineCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(title),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(said ?: figures, style = MaterialTheme.typography.bodyMedium, color = PwnMuted)
            if (short) {
                CountStepper(reps, range) { reps = it }
                Button(onClick = { onAnswer(reps) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_done))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { short = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.common_no))
                    }
                    Button(onClick = { onAnswer(counted) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.common_yes))
                    }
                }
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

/** A tile: its disc on the left, the label over the figure beside it. */
@Composable
internal fun RowScope.Tile(icon: ImageVector, label: String, value: String, weight: Float = 1f) {
    RoutineCard(Modifier.weight(weight).fillMaxHeight()) {
        // One stop for a screen reader: "Left, 00:35" rather than three.
        Row(
            Modifier.fillMaxHeight()
                .padding(horizontal = 8.dp, vertical = 14.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(TILE_DISC).background(PwnIndigoSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = PwnMuted)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = PwnMuted, maxLines = 2)
                // Steps down rather than wrapping, which would leave a bare "150" with its unit hidden.
                Text(
                    value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 22.sp, stepSize = 1.sp),
                )
            }
        }
    }
}

/**
 * How the exercise is done, as a technique card: its heading inside, the drawn steps
 * under it, and the name not said again — the headline above says it.
 */
@Composable
private fun HowToCard(panel: Int) {
    RoutineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeading(stringResource(R.string.workout_how_to))
            RoutineHowToPanel(panel, Modifier.align(Alignment.CenterHorizontally).heightIn(max = HOW_TO_MAX_HEIGHT))
        }
    }
}

/** What comes next: the next exercise's drawing, its name and its figures. */
@Composable
private fun NextCard(exercise: RoutineExercise, imperial: Boolean) {
    val resources = LocalResources.current
    val spec = ACTIVITY_CATALOG.firstOrNull { it.code == exercise.activityCode }
    RoutineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeading(stringResource(R.string.common_next))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = NEXT_ART_WIDTH, height = NEXT_ART_HEIGHT)
                        .clip(RoundedCornerShape(PwnCorners.medium))
                        .background(PwnIndigoSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    spec?.let {
                        Image(
                            themePainterResource(it.art()),
                            null,
                            Modifier.size(NEXT_ART_HEIGHT - 4.dp),
                            colorFilter = washTint(),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        routineExerciseName(resources, exercise),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        routineSetSpan(resources, exercise) ?: routineExerciseSummary(resources, exercise, imperial),
                        style = MaterialTheme.typography.bodySmall,
                        color = PwnMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = RoutineHeadingStyle, fontWeight = FontWeight.Bold)
}

/** The session's own clock over the drawing, in the accent, as tall as the discs beside it. */
@Composable
internal fun SessionClock(elapsed: Long) {
    Row(
        Modifier.heightIn(min = CLOCK_HEIGHT)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Timer,
            stringResource(R.string.routines_total_time),
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            (elapsed / 1_000).toInt().coerceAtLeast(0).elapsedClockText(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * Fixed at the foot: back a stretch, pause — the wide one, what a hand reaches
 * for mid-set — skip, and finish, all rounded squares on the page's pale fill but pause. Words step
 * down in size before they would wrap.
 */
@Composable
private fun PlayerControls(
    paused: Boolean,
    canGoBack: Boolean,
    finishing: Boolean,
    onPrevious: () -> Unit,
    onPause: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    val shape = RoundedCornerShape(PwnCorners.large)
    val tonal = routineTonalColors()
    val padding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tall = Modifier.heightIn(min = PwnDimensions.mediumButtonHeight).fillMaxHeight()
        FilledTonalButton(
            onClick = onPrevious,
            enabled = canGoBack,
            modifier = tall.width(PwnDimensions.mediumButtonHeight),
            shape = shape,
            colors = tonal,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.routines_previous))
        }
        Button(onClick = onPause, modifier = tall.weight(PAUSE_WEIGHT), shape = shape, contentPadding = padding) {
            Icon(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                null,
                Modifier.size(PwnDimensions.mediumButtonIcon),
            )
            Spacer(Modifier.width(8.dp))
            ControlLabel(stringResource(if (paused) R.string.common_continue else R.string.breathing_pause))
        }
        FilledTonalButton(
            onClick = onSkip,
            modifier = tall.weight(1f),
            shape = shape,
            colors = tonal,
            contentPadding = padding,
        ) {
            StackedLabel(Icons.Filled.SkipNext, stringResource(R.string.common_skip))
        }
        FilledTonalButton(
            onClick = onFinish,
            enabled = !finishing,
            modifier = tall.weight(1f),
            shape = shape,
            colors = tonal,
            contentPadding = padding,
        ) {
            StackedLabel(Icons.Filled.Stop, stringResource(R.string.routines_finish))
        }
    }
}

/** Skip's and finish's glyph over a word one size under pause's. */
@Composable
internal fun StackedLabel(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(PwnDimensions.mediumButtonIcon))
        ControlLabel(label, maxSize = MaterialTheme.typography.labelMedium.fontSize)
    }
}

@Composable
internal fun ControlLabel(label: String, maxSize: TextUnit = 14.sp) {
    Text(
        label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelLarge,
        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = maxSize, stepSize = 0.5.sp),
    )
}

/** The seconds before the first exercise, and the last seconds of a stretch that each get a tone. */
private const val READY_SECONDS = 5

/** The rest's circle and the width of the ring round it. */
private val REST_CIRCLE = 220.dp
private val REST_RING = 12.dp

/** How long "Complete set" is held: the hint under it says two seconds in every language. */
private const val SET_HOLD_MILLIS = 2_000
private const val HOLD_RELEASE_MILLIS = 200
private const val HOLD_FILL_ALPHA = 0.16f
private const val COUNTDOWN_TONES = 3

/** Most reps a set the phone counted can be corrected to. */
private const val MAX_SET_REPS = 999

/** Most sets an exercise's minutes are shared out among; past it the exercise is one stretch. */
private const val MAX_SPLIT_SETS = 20

/** The pause button's share of the foot against skip's and finish's one each. */
private const val PAUSE_WEIGHT = 1.7f

/** The rest tile's share of the row: its label is the longest of the three. */
private const val REST_TILE_WEIGHT = 1.15f
private val HERO_HEIGHT = 260.dp
private val SHEET_OVERLAP = 28.dp
private val SHEET_CORNER = PwnCorners.largeIncreased
private val HERO_ART = 200.dp
private val TILE_DISC = 32.dp
private val CLOCK_HEIGHT = 36.dp

/** The next exercise's drawing, landscape. */
private val NEXT_ART_WIDTH = 92.dp
private val NEXT_ART_HEIGHT = 56.dp

/** Tallest the how-to drawing stands in the player. */
private val HOW_TO_MAX_HEIGHT = 120.dp
