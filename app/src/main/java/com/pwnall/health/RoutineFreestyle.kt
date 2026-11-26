package com.pwnall.health

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * One set of a freestyle session as it went: the exercise, the seconds it ran, the reps and load where
 * it is counted — the steps or jumps where the phone counted those — the floors of a climb, and the
 * rest taken after it before the next set began.
 */
internal data class FreeSet(
    val code: String,
    val seconds: Int,
    val reps: Int = 0,
    val loadKg: Double? = null,
    val rest: Int = 0,
    val floors: Int = 0,
)

/**
 * A freestyle session as it stands: the sets done, the exercise under way, and when the session, the
 * set under way and the last set's end were — wall-clock milliseconds, -1 for not yet — with a set of
 * reps stopped and waiting for its reps: the seconds it ran ([entering], -1 for none) and what has been
 * typed for it. Kept in the phone's preferences at every change ([saveFreestyle]), so leaving the page,
 * or the app, by mistake loses nothing: the session is where it was when Freestyle is opened again.
 */
internal data class FreestyleState(
    val sets: List<FreeSet> = emptyList(),
    val code: String = "",
    val sessionStart: Long = -1L,
    val setStart: Long = -1L,
    val setEnd: Long = -1L,
    val entering: Int = -1,
    val entryReps: String = "",
    val entryLoad: String = "",
    val entryFloors: String = "",
) {
    /** The first set has begun: that, not choosing an exercise, is what begins a session. */
    val started: Boolean get() = sessionStart >= 0

    /** No set running and none waiting for its reps. */
    val idle: Boolean get() = setStart < 0 && entering < 0

    /** The sets of the exercise under way done so far, in the whole session. */
    val doneOfCode: Int get() = sets.count { it.code == code }
}

private fun secondsBetween(from: Long, to: Long): Int = ((to - from) / 1_000).toInt().coerceAtLeast(0)

/**
 * The rest a set that ended at [from] has had by [to], and no more than [MAX_COUNTED_REST_SECONDS]. A
 * rest is minutes of its row, and a row's energy is its minutes at the exercise's own effort: a session
 * left open overnight and finished in the morning would otherwise write the night as twelve hours of
 * lifting.
 */
private fun restBetween(from: Long, to: Long): Int = secondsBetween(from, to).coerceAtMost(MAX_COUNTED_REST_SECONDS)

/** [code] chosen: only shown, so cards can be looked through without a clock running. */
internal fun FreestyleState.choosing(code: String): FreestyleState = copy(code = code)

/**
 * A set begun at [now]; the first begins the session, and its clock with it. The rest since the last
 * set is that set's own, as a session's plan keeps it, for as long as a rest counts ([restBetween]).
 */
internal fun FreestyleState.beginning(now: Long): FreestyleState = copy(
    sets = if (setEnd >= 0 &&
        sets.isNotEmpty()
    ) {
        sets.dropLast(1) + sets.last().copy(rest = restBetween(setEnd, now))
    } else {
        sets
    },
    sessionStart = if (started) sessionStart else now,
    setStart = now,
    setEnd = -1L,
)

/**
 * The set under way stopped at [now]: a timed one is done; one [counted] in reps waits for its reps,
 * offered this exercise's last set's figures — the usual next set — its load in the profile's unit.
 * One the phone [tracked] waits too, timed or not, offered what the phone counted to be confirmed.
 * A set of reps left running counts for no longer than a rest does ([MAX_COUNTED_REST_SECONDS]): nobody
 * lifts for longer in one set, and a Stop forgotten overnight would be twelve hours of lifting in the
 * journal.
 */
internal fun FreestyleState.stopping(
    now: Long,
    counted: Boolean,
    unitFactor: Double,
    tracked: TrackedCount? = null,
): FreestyleState {
    val ran = secondsBetween(setStart, now).let { if (counted) it.coerceAtMost(MAX_COUNTED_REST_SECONDS) else it }
    val stopped = copy(setStart = -1L, setEnd = now)
    if (!counted && tracked == null) return if (ran > 0) stopped.copy(sets = sets + FreeSet(code, ran)) else stopped
    val last = sets.lastOrNull { it.code == code }
    return stopped.copy(
        entering = ran,
        entryReps = tracked?.count?.toString() ?: last?.reps?.takeIf { it > 0 }?.toString().orEmpty(),
        entryLoad = last?.loadKg?.let { (it * unitFactor).pretty() }.orEmpty(),
        entryFloors = tracked?.floors?.takeIf { it > 0 }?.toString().orEmpty(),
    )
}

/** The set waiting for its reps, done with the reps typed and its load turned back into kilograms. */
internal fun FreestyleState.entered(unitFactor: Double): FreestyleState {
    val kg = entryLoad.number().takeIf { it > 0.0 }?.let { (it / unitFactor).coerceAtMost(MAX_FREE_LOAD_KG) }
    val set = FreeSet(code, entering, entryReps.intNumber(), kg, floors = entryFloors.intNumber())
    return copy(sets = sets + set, entering = -1, entryReps = "", entryLoad = "", entryFloors = "")
}

/**
 * The sets the session leaves when it ends at [now]: a timed set under way counts for what it ran, and
 * reps already typed count; a set of reps nobody has counted does not. The rest after the last set
 * runs until Finish, as every other rest runs until the next set: the session lasts until it is ended.
 * Neither counts for longer than a rest may ([restBetween]): past that the phone was left lying, and
 * the hours it lay are not the workout's.
 *
 * Limitation: a timed set left running is not held to anything — a walk or a dance is a set here, and
 * may well last hours ([stopping] holds a set of reps) — so one forgotten overnight counts in full.
 */
internal fun FreestyleState.finalSets(now: Long, counted: Boolean, unitFactor: Double): List<FreeSet> {
    var state = this
    if (state.setStart >= 0 && !counted) state = state.stopping(now, counted = false, unitFactor)
    if (state.entering >= 0 && state.entryReps.intNumber() in 1..MAX_FREE_REPS) state = state.entered(unitFactor)
    return if (state.setStart < 0 && state.setEnd >= 0 && state.sets.isNotEmpty()) {
        state.sets.dropLast(1) + state.sets.last().copy(rest = restBetween(state.setEnd, now))
    } else {
        state.sets
    }
}

internal fun FreestyleState.json(): String = JSONObject()
    .put("sets", JSONArray(sets.map { it.json() }))
    .put("code", code)
    .put("sessionStart", sessionStart)
    .put("setStart", setStart)
    .put("setEnd", setEnd)
    .put("entering", entering)
    .put("entryReps", entryReps)
    .put("entryLoad", entryLoad)
    .put("entryFloors", entryFloors)
    .toString()

/** The session [text] holds; none for nothing saved or anything unreadable. */
internal fun freestyleState(text: String?): FreestyleState = text?.let {
    runCatching {
        val json = JSONObject(it)
        val sets = json.getJSONArray("sets")
        FreestyleState(
            sets = List(sets.length()) { at -> freeSet(sets.getJSONObject(at)) },
            code = json.getString("code"),
            sessionStart = json.getLong("sessionStart"),
            setStart = json.getLong("setStart"),
            setEnd = json.getLong("setEnd"),
            entering = json.getInt("entering"),
            entryReps = json.getString("entryReps"),
            entryLoad = json.getString("entryLoad"),
            entryFloors = json.optString("entryFloors"),
        )
    }.getOrNull()
} ?: FreestyleState()

private fun FreeSet.json(): JSONObject = JSONObject()
    .put("code", code)
    .put("seconds", seconds)
    .put("reps", reps)
    .put("rest", rest)
    .also { json -> loadKg?.let { json.put("loadKg", it) } }
    .also { json -> if (floors > 0) json.put("floors", floors) }

private fun freeSet(json: JSONObject) = FreeSet(
    code = json.getString("code"),
    seconds = json.getInt("seconds"),
    reps = json.optInt("reps"),
    loadKg = if (json.has("loadKg")) json.getDouble("loadKg") else null,
    rest = json.optInt("rest"),
    floors = json.optInt("floors"),
)

/** The session in progress, as last saved; an empty one when there is none. */
internal fun Context.savedFreestyle(): FreestyleState =
    freestyleState(getSharedPreferences(FREESTYLE_PREFS, Context.MODE_PRIVATE).getString(FREESTYLE_KEY, null))

private fun Context.saveFreestyle(state: FreestyleState) =
    getSharedPreferences(FREESTYLE_PREFS, Context.MODE_PRIVATE).edit {
        if (state.started) putString(FREESTYLE_KEY, state.json()) else remove(FREESTYLE_KEY)
    }

/**
 * A freestyle session as the journal takes it ([logSession]): each run of sets of one exercise in the
 * order they were done — an exercise come back to after another is a row of its own, as a session's
 * groups are — and each written as done in full, since there was no plan for it to fall short of.
 */
internal fun freestyleOutcomes(sets: List<FreeSet>): List<RoutineOutcome> {
    val runs = mutableListOf<MutableList<FreeSet>>()
    sets.forEach { set ->
        if (runs.lastOrNull()?.last()?.code == set.code) runs.last() += set else runs += mutableListOf(set)
    }
    return runs.map { run ->
        val figures = run.map {
            RoutineSet(
                reps = it.reps,
                loadKg = it.loadKg,
                seconds = it.seconds,
                restSeconds = it.rest,
                floors = it.floors,
            )
        }
        val exercise = RoutineExercise(
            routineId = 0,
            position = 0,
            activityCode = run.first().code,
            durationMinutes = run.sumOf { it.seconds + it.rest } / 60.0,
            sets = run.size,
            reps = run.first().reps.takeIf { it > 0 },
            loadKg = run.first().loadKg,
            setsJson = routineSetsJson(figures),
        )
        val done = figures.map { SetDone(it, it.seconds, it.restSeconds) }
        RoutineOutcome(exercise, done, done.size, done.sumOf { it.seconds }, figures.sumOf { it.reps })
    }
}

/**
 * Freestyle: a workout with no plan, laid out as a template's session is. It opens on the catalogue's
 * cards; choosing one only shows it, so exercises can be looked through — back returns to the cards —
 * with no clock running and Finish greyed. The first Start begins the session; from then on it is kept
 * ([FreestyleState]) until Finish, so back — on purpose or not — leaves it running, and Freestyle opens
 * on it again.
 *
 * The exercise's page has its drawing across the top — held for two seconds it turns into the muscles
 * it works, and a tap turns it back — its name, a clock, how it is done behind a fold, and every set of
 * the session with the rests between them. Start, then "Set 2" and so on, runs the clock up rather than
 * down, since nothing was planned to run out; Stop ends the set, and a set of reps then asks how many
 * were done and with what weight. The rest runs from there until the next set, or until it has run
 * as long as a rest counts ([MAX_COUNTED_REST_SECONDS]). "+" picks the next exercise, and Finish writes
 * the sets as they went ([freestyleOutcomes]) and says how many exercises were logged — the page
 * standing as it was, its buttons off, while they are written — or, with no set done, simply ends the
 * session.
 *
 * Limitation: the clocks are wall time, read while the page is open — no foreground service, so nothing
 * sounds or counts on screen while the app is hidden, though the time is right when it comes back; and
 * a session left open overnight is logged on the day it is finished.
 */
@Composable
internal fun FreestyleSession(
    modifier: Modifier,
    imperial: Boolean,
    sex: String,
    restingKcalPerHour: Double,
    onLog: suspend (List<RoutineOutcome>) -> Int,
    onWalk: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(context.savedFreestyle()) }
    var picking by rememberSaveable { mutableStateOf(!state.started) }
    // How many exercises the end logged; -1 until the session has ended.
    var logged by rememberSaveable { mutableIntStateOf(-1) }
    var finished by rememberSaveable(stateSaver = DoneExercisesSaver) { mutableStateOf(emptyList<DoneExercise>()) }
    // The records the session is measured against, read just before it is written. Not kept through
    // a rotation: the done page then marks no record rather than one it can no longer check.
    var recordsBefore by remember { mutableStateOf<Map<String, ExerciseRecords>?>(null) }
    val recordsContext = LocalContext.current
    var ending by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun update(next: FreestyleState) {
        state = next
        now = System.currentTimeMillis()
        context.saveFreestyle(next)
    }

    // On while a session is under way only: the cards before its first set and the page it ends on
    // are read in hand, and the screen goes off by itself there as anywhere else.
    val view = LocalView.current
    val awake = state.started && logged < 0
    DisposableEffect(view, awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    val spec = ACTIVITY_CATALOG.firstOrNull { it.code == state.code }
    val counted = spec != null && spec.input != ActivityInput.DURATION
    val factor = loadUnitFactor(imperial)
    val tracker = rememberSetTracker(state.code, running = state.setStart >= 0)
    // A set of reps needs one; a walk's or a dance's time stands without a count.
    val entryRange = if (counted) 1..MAX_FREE_REPS else 0..MAX_FREE_COUNT

    fun end() {
        if (ending || logged >= 0) return
        val outcomes = freestyleOutcomes(state.finalSets(System.currentTimeMillis(), counted, factor))
        if (outcomes.isEmpty()) {
            update(FreestyleState())
            onClose()
            return
        }
        // Let go of first: a session still kept after Finish is one to finish, and write, a second time.
        // Only the kept copy, though. The page goes on showing the session it ended, its buttons off,
        // until the write is done: emptied as well, it would have no exercise left to show and would
        // stand as the catalogue's cards for as long as the write took, with a way out of the page among them.
        context.saveFreestyle(FreestyleState())
        ending = true
        finished = sessionSummary(outcomes)
        val codes = finished.map { it.code }
        // Undispatched and non-cancellable, as a breathing session's save is. The session kept is
        // already let go, so the write is all that holds it for good: it is neither dropped before it
        // began nor left at the read before it because the page went away.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                recordsBefore = recordsByExercise(StepsDb.get(recordsContext).dao(), codes)
                logged = onLog(outcomes)
            }
        }
    }

    BackHandler {
        when {
            logged >= 0 -> onClose()

            ending -> Unit

            picking && state.started -> picking = false

            // Looking through exercises before the first set: back is the way to the cards.
            !picking && spec != null && !state.started -> picking = true

            // The session stays as it is, saved, for Freestyle to open on again.
            else -> onClose()
        }
    }

    val ticking = state.started && logged < 0 && !ending
    LaunchedEffect(ticking) {
        // Woken on the clock's own second boundaries, so the figure turns over as a second does.
        while (ticking) {
            now = System.currentTimeMillis()
            val from = listOf(state.setStart, state.setEnd, state.sessionStart).firstOrNull { it >= 0 } ?: break
            delay(1_000L - (now - from).mod(1_000L))
        }
    }

    if (logged >= 0) {
        SessionDone(modifier, logged, finished, imperial, onClose, recordsBefore)
        return
    }
    if (picking || spec == null) {
        FreePickPage(
            modifier = modifier,
            current = spec,
            onBack = { if (state.started) picking = false else onClose() },
            onOutdoors = onWalk,
        ) {
            update(state.choosing(it))
            picking = false
        }
        return
    }

    val setLabel = stringResource(R.string.routines_set_number, state.doneOfCode + 1)
    // The other workouts' list changes the workout between sets, never during one.
    val switchOther = { other: ActivitySpec -> update(state.choosing(other.code)) }.takeIf { state.idle && !ending }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState())) {
                FreeHero(spec, sex)
                FreeSheet(
                    stringResource(R.string.routines_freestyle),
                    spec,
                    onOther = switchOther,
                ) {
                    when {
                        state.entering < 0 -> Unit

                        // A phone that could not count the set asks for its reps as any other is asked.
                        tracker != null && !tracker.blind -> CountQuestion(
                            spec = spec,
                            seconds = state.entering,
                            count = state.entryReps,
                            floors = state.entryFloors,
                            load = state.entryLoad,
                            loadUnit = stringResource(loadUnitRes(imperial)),
                            onFigures = { count, floors, load ->
                                update(state.copy(entryReps = count, entryFloors = floors, entryLoad = load))
                            },
                            onRight = { if (state.entryReps.intNumber() in entryRange) update(state.entered(factor)) },
                        )

                        else -> RepsCard(setLabel, state.entering, spec, imperial, state.entryReps, state.entryLoad) {
                                reps,
                                load,
                            ->
                            update(state.copy(entryReps = reps, entryLoad = load))
                        }
                    }
                    when {
                        state.setStart >= 0 -> {
                            val ran = secondsBetween(state.setStart, now)
                            ClockCircle(ran, { ran % 60 / 60f }, setLabel)
                        }

                        state.setEnd >= 0 -> {
                            // Stops where the rest stops counting: what the clock shows is what is written.
                            val rest = restBetween(state.setEnd, now)
                            ClockCircle(rest, { rest % 60 / 60f }, stringResource(R.string.workout_rest))
                        }

                        else -> ClockCircle(0, { 0f }, setLabel)
                    }
                    if (state.setStart >= 0) tracker?.let { TrackedTile(spec.code, it) }
                    if (spec.code in OTHER_WORKOUT_CODES) {
                        val seconds = state.sets.filter { it.code == spec.code }.sumOf { it.seconds } +
                            if (state.setStart >= 0) secondsBetween(state.setStart, now) else 0
                        BurnedTile(netActivityCalories(spec.met, restingKcalPerHour, seconds / 60.0))
                    }
                    if (tracker != null) PhonePlacementCard(spec.code)
                    spec.howTo()?.let { HowToFold(it) }
                    if (state.sets.isNotEmpty()) SessionSetsCard(state.sets, imperial)
                }
            }
            // Fixed over the drawing, as the template session's are: the way back on one side, the
            // session's clock on the other.
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoutineHeroButton(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.common_back),
                    onPicture = true,
                    onClick = {
                        when {
                            // Not while the session is being written, as the system's back is not: the
                            // page stays for the summary the write ends on.
                            ending -> Unit

                            state.started -> onClose()

                            else -> picking = true
                        }
                    },
                )
                Spacer(Modifier.weight(1f))
                SessionClock(if (state.started) now - state.sessionStart else 0L)
            }
        }
        val shape = RoundedCornerShape(PwnCorners.large)
        val tonal = routineTonalColors()
        val padding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tall = Modifier.heightIn(min = PwnDimensions.mediumButtonHeight).fillMaxHeight()
            FilledTonalButton(
                onClick = { picking = true },
                enabled = state.idle && !ending,
                modifier = tall.width(PwnDimensions.mediumButtonHeight),
                shape = shape,
                colors = tonal,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Filled.Add, stringResource(R.string.routines_add_exercise))
            }
            // The next set by its number once this exercise has one done: "Set 2", not Start again.
            val (icon, label) = when {
                state.entering >= 0 -> Icons.Filled.Check to stringResource(R.string.common_done)
                state.setStart >= 0 -> Icons.Filled.Stop to stringResource(R.string.workout_stop)
                state.doneOfCode > 0 -> Icons.Filled.PlayArrow to setLabel
                else -> Icons.Filled.PlayArrow to stringResource(R.string.common_start)
            }
            Button(
                onClick = {
                    val at = System.currentTimeMillis()
                    update(
                        when {
                            state.entering >= 0 -> state.entered(factor)

                            state.setStart >= 0 -> {
                                // A blind tracker's nought is no count: the set is offered its last
                                // figures instead. Asked after the count ends, which is when a signal lost
                                // since the last recount is seen.
                                val tracked = tracker?.let { t -> t.final().takeUnless { t.blind } }
                                state.stopping(at, counted, factor, tracked)
                            }

                            else -> state.beginning(at)
                        },
                    )
                },
                enabled = !ending && (state.entering < 0 || state.entryReps.intNumber() in entryRange),
                modifier = tall.weight(PRIMARY_WEIGHT),
                shape = shape,
                contentPadding = padding,
            ) {
                Icon(icon, null, Modifier.size(PwnDimensions.mediumButtonIcon))
                Spacer(Modifier.width(8.dp))
                ControlLabel(label)
            }
            FilledTonalButton(
                onClick = ::end,
                enabled = state.started && state.idle && !ending,
                modifier = tall.weight(1f),
                shape = shape,
                colors = tonal,
                contentPadding = padding,
            ) {
                StackedLabel(Icons.Filled.Flag, stringResource(R.string.routines_finish))
            }
        }
    }
}

/**
 * Freestyle for a workout already done — one told to the chat: the exercise it names chosen (the cards
 * first when it names none the catalogue knows), how long it took and its sets as it said them, its
 * words as the note — "1.5 km" has no other column to go to — and Log in place of the clock. Log writes
 * it on [day] ([logSession]) and ends on the summary a session ends on. The session Freestyle keeps is
 * not touched: a workout under way stays where it was.
 */
@Composable
internal fun FreestyleLog(
    modifier: Modifier,
    seed: ChatJournalEvent,
    day: LocalDate,
    imperial: Boolean,
    sex: String,
    onLog: suspend (List<RoutineOutcome>, String) -> Int,
    onClose: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val factor = loadUnitFactor(imperial)
    var code by rememberSaveable {
        val names = PICKABLE_ACTIVITIES.associate { it.code to resources.getString(it.titleRes) }
        mutableStateOf(seed.prefillExercise(names).orEmpty())
    }
    var picking by rememberSaveable { mutableStateOf(code.isEmpty()) }
    var seconds by rememberSaveable {
        mutableIntStateOf(((seed.prefillQuantity("DURATION", "duration") ?: 0.0) * 60).roundToInt())
    }
    var count by rememberSaveable { mutableIntStateOf((seed.prefillCount("SETS") ?: 1).coerceIn(1, MAX_PLAN_SETS)) }
    var reps by rememberSaveable { mutableStateOf(seed.prefillCount("REPS")?.toString().orEmpty()) }
    var load by rememberSaveable {
        mutableStateOf(seed.prefillQuantity("LOAD", "mass")?.let { (it / 1000 * factor).pretty() }.orEmpty())
    }
    var note by rememberSaveable { mutableStateOf(seed.prefillNote()) }
    var logged by rememberSaveable { mutableIntStateOf(-1) }
    var finished by rememberSaveable(stateSaver = DoneExercisesSaver) { mutableStateOf(emptyList<DoneExercise>()) }
    // The records the session is measured against, read just before it is written. Not kept through
    // a rotation: the done page then marks no record rather than one it can no longer check.
    var recordsBefore by remember { mutableStateOf<Map<String, ExerciseRecords>?>(null) }
    val recordsContext = LocalContext.current
    // Set from the start on a page that comes up with the summary and no count: the page before it had
    // Log tapped, and a turn of the phone took it away while the workout was being written. That write
    // lands without it ([logSession]), so this page is done as well ([loggedCount]) rather than offer
    // Log again and write the workout twice.
    var ending by remember { mutableStateOf(logged < 0 && finished.isNotEmpty()) }
    LaunchedEffect(Unit) { if (ending) logged = loggedCount(finished) }

    val spec = ACTIVITY_CATALOG.firstOrNull { it.code == code }
    val counted = spec != null && spec.input != ActivityInput.DURATION
    val ready = if (counted) reps.intNumber() in 1..MAX_FREE_REPS else seconds > 0

    fun log() {
        if (ending || !ready) return
        ending = true
        val kg = load.number().takeIf { counted && it > 0.0 }?.let { (it / factor).coerceAtMost(MAX_FREE_LOAD_KG) }
        val outcomes = freestyleOutcomes(loggedSets(code, seconds, if (counted) count else 0, reps.intNumber(), kg))
        finished = sessionSummary(outcomes)
        val codes = finished.map { it.code }
        // Undispatched and non-cancellable, as a breathing session's save is: Log was tapped, so the
        // workout is written whether or not the page outlasts the write.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                recordsBefore = recordsByExercise(StepsDb.get(recordsContext).dao(), codes)
                logged = onLog(outcomes, note.trim())
            }
        }
    }

    // Only what the page undoes itself, and the write under way. Leaving is the caller's back — which
    // [onClose] is too, so a handler that closed would hear its own close and call it again forever.
    BackHandler(enabled = (picking && spec != null) || (ending && logged < 0)) {
        if (picking) picking = false
    }

    if (logged >= 0) {
        SessionDone(modifier, logged, finished, imperial, onClose, recordsBefore)
        return
    }
    if (picking || spec == null) {
        FreePickPage(modifier, spec, onBack = { if (spec != null) picking = false else onClose() }) {
            code = it
            picking = false
        }
        return
    }

    val label = if (day == LocalDate.now()) {
        stringResource(R.string.routines_freestyle)
    } else {
        day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(LocalLocale.current.platformLocale))
    }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState())) {
                FreeHero(spec, sex)
                FreeSheet(label, spec, onOther = { code = it.code }) {
                    RoutineCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.editor_time),
                                Modifier.semantics { heading() },
                                style = RoutineHeadingStyle,
                                fontWeight = FontWeight.Bold,
                            )
                            ClockFields(seconds, editable = true) { seconds = it }
                        }
                    }
                    if (counted) {
                        SetCountCard(count) { count = it }
                        RoutineCard(Modifier.fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                                SetFigures(
                                    PlannedSet(reps = reps, load = load),
                                    spec,
                                    loadUnitRes(imperial),
                                    locked = false,
                                    compact = true,
                                ) {
                                    reps = it.reps
                                    load = it.load
                                }
                            }
                        }
                    }
                    NoteField(note, { note = it })
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                RoutineHeroButton(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.common_back),
                    onPicture = true,
                    onClick = onClose,
                )
            }
        }
        val shape = RoundedCornerShape(PwnCorners.large)
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tall = Modifier.heightIn(min = PwnDimensions.mediumButtonHeight).fillMaxHeight()
            FilledTonalButton(
                onClick = { picking = true },
                enabled = !ending,
                modifier = tall.width(PwnDimensions.mediumButtonHeight),
                shape = shape,
                colors = routineTonalColors(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.routines_pick_title))
            }
            Button(
                onClick = ::log,
                enabled = ready && !ending,
                modifier = tall.weight(1f),
                shape = shape,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Check, null, Modifier.size(PwnDimensions.mediumButtonIcon))
                Spacer(Modifier.width(8.dp))
                ControlLabel(stringResource(R.string.routines_start))
            }
        }
    }
}

/**
 * What a workout logged after the fact is as sets: [count] sets of [reps] at [kg], or, at a [count] of
 * nought, one stretch of [seconds]. A stated time is the sets' own, split evenly; with none, each set
 * takes a template's estimate and the usual rest between them, so the row's minutes are not nought.
 */
internal fun loggedSets(code: String, seconds: Int, count: Int, reps: Int, kg: Double?): List<FreeSet> {
    if (count == 0) return listOf(FreeSet(code, seconds))
    val each = if (seconds > 0) seconds / count else RoutineSet(reps = reps).duration()
    return List(count) { at ->
        FreeSet(code, each, reps, kg, if (seconds > 0 || at == count - 1) 0 else DEFAULT_REST_SECONDS)
    }
}

/**
 * The sheet over the drawing: [label] small above the exercise's name, then the page's cards. One of
 * the other workouts is named by their group, with the list of them under it to change it by —
 * [onOther] null keeps it as it is, during a set.
 */
@Composable
private fun FreeSheet(
    label: String,
    spec: ActivitySpec,
    onOther: ((ActivitySpec) -> Unit)?,
    content: @Composable () -> Unit,
) {
    val other = spec.code in OTHER_WORKOUT_CODES
    Column(
        Modifier.padding(top = HERO_HEIGHT - SHEET_OVERLAP)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
            Text(
                stringResource(if (other) R.string.routines_other_workouts else spec.titleRes),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        if (other) OtherWorkoutField(spec, onOther)
        content()
    }
}

/** The catalogue's cards under the round way back and the page's name on the same line; a tap chooses. */
@Composable
private fun FreePickPage(
    modifier: Modifier,
    current: ActivitySpec?,
    onBack: () -> Unit,
    onOutdoors: (() -> Unit)? = null,
    onChoose: (String) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(
                min = PwnDimensions.topBarHeight,
            ).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoutineHeroButton(
                Icons.AutoMirrored.Outlined.ArrowBack,
                stringResource(R.string.common_back),
                onPicture = false,
                onClick = onBack,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.routines_pick_title),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            ExercisePickPage(current, titled = false, onOutdoors = onOutdoors) { onChoose(it.code) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The exercise's drawing across the top. Held for [MUSCLE_HOLD_MILLIS] it becomes the plate of the
 * muscles the exercise works, with a tick of the phone, and a tap puts the drawing back; a screen
 * reader's long-press action turns it either way, named for the way it turns it.
 */
@Composable
private fun FreeHero(spec: ActivitySpec, sex: String) {
    var muscles by rememberSaveable(spec.code) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val label = stringResource(if (muscles) R.string.routines_show_drawing else R.string.routines_show_muscles)
    Box(
        Modifier.fillMaxWidth()
            .height(HERO_HEIGHT)
            .background(PwnIndigoSoft)
            .pointerInput(spec.code) {
                detectTapGestures(
                    onPress = {
                        var held = false
                        val hold = scope.launch {
                            delay(MUSCLE_HOLD_MILLIS)
                            held = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            muscles = true
                        }
                        val released = tryAwaitRelease()
                        hold.cancel()
                        if (released && !held) muscles = false
                    },
                )
            }
            .semantics {
                if (spec.muscles.isNotEmpty()) {
                    onLongClick(label) {
                        muscles = !muscles
                        true
                    }
                }
            }
            .padding(top = SHEET_OVERLAP),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(muscles && spec.muscles.isNotEmpty(), label = "muscles") { showing ->
            if (showing) {
                // Clear of the sheet's edge as well as of the buttons: the plate's feet are part of it.
                MuscleBodies(
                    spec.muscles,
                    sex,
                    HERO_HEIGHT - SHEET_OVERLAP * 3,
                    Modifier.padding(start = 40.dp, end = 40.dp, bottom = SHEET_OVERLAP),
                )
            } else {
                Image(themePainterResource(spec.heroArt()), null, Modifier.size(HERO_ART), colorFilter = washTint())
            }
        }
    }
}

/**
 * A set of reps just stopped: how many were done, and with what weight for a loaded exercise, in boxes
 * as wide as their figures rather than across the card.
 */
@Composable
private fun RepsCard(
    setLabel: String,
    seconds: Int,
    spec: ActivitySpec,
    imperial: Boolean,
    reps: String,
    load: String,
    onFigures: (String, String) -> Unit,
) {
    RoutineCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.routines_reps_done_question),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                "$setLabel · ${durationText(LocalResources.current, seconds)}",
                style = MaterialTheme.typography.bodyMedium,
                color = PwnMuted,
            )
            SetFigures(
                PlannedSet(reps = reps, load = load),
                spec,
                loadUnitRes(imperial),
                locked = false,
                compact = true,
            ) {
                onFigures(it.reps, it.load)
            }
        }
    }
}

/** "How to?" folded under its heading until it is opened: the drawn steps, on demand. */
@Composable
private fun HowToFold(panel: Int) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, PwnMotion.effects(), label = "fold")
    val state = disclosureState(open)
    RoutineCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) { open = !open }
                    .disclosureSemantics(open, state) { open = it }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.workout_how_to),
                    Modifier.weight(1f).semantics { heading() },
                    style = RoutineHeadingStyle,
                    fontWeight = FontWeight.Bold,
                )
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.rotate(turn), tint = PwnMuted)
            }
            InlineReveal(open) {
                Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), Alignment.Center) {
                    RoutineHowToPanel(panel, Modifier.heightIn(max = HOW_TO_MAX_HEIGHT))
                }
            }
        }
    }
}

/**
 * Every set of the session in the order it was done — "Plank — Set 2", its figures beside it — with the
 * rest taken after it on a green strip, as a template's sets show theirs. The rest under way is the
 * circle's, until the next set begins.
 */
@Composable
private fun SessionSetsCard(sets: List<FreeSet>, imperial: Boolean) {
    val resources = LocalResources.current
    RoutineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.editor_sets),
                Modifier.semantics { heading() },
                style = RoutineHeadingStyle,
                fontWeight = FontWeight.Bold,
            )
            sets.forEachIndexed { at, set ->
                val name =
                    ACTIVITY_CATALOG.firstOrNull { it.code == set.code }?.let { stringResource(it.titleRes) }
                        ?: set.code
                val number = sets.take(at + 1).count { it.code == set.code }
                val figures = routineSetText(
                    resources,
                    RoutineSet(set.reps, set.loadKg, set.seconds, floors = set.floors),
                    imperial,
                    set.code,
                )
                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$name — ${stringResource(R.string.routines_set_number, number)}",
                        Modifier.weight(1f).padding(end = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (set.reps > 0 || set.floors > 0) {
                            "$figures · ${durationText(resources, set.seconds)}"
                        } else {
                            figures
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (set.rest > 0) RestStrip(set.rest)
            }
        }
    }
}

/** A rest between two sets, on the green strip a template's set cards carry theirs on. */
@Composable
internal fun RestStrip(seconds: Int) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PwnGreenSoft)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.LocalCafe, null, Modifier.size(18.dp), tint = PwnGreen)
        Spacer(Modifier.width(8.dp))
        // Set apart by its colour and a space, not a colon: French puts a space before one, and a
        // colon glued on here would be the same in every language.
        Text(
            stringResource(R.string.workout_rest),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = PwnGreen,
        )
        Spacer(Modifier.width(6.dp))
        Text(durationText(LocalResources.current, seconds), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Where the session in progress is kept between visits. */
private const val FREESTYLE_PREFS = "freestyle"
private const val FREESTYLE_KEY = "session"

/** How long the drawing is held before it turns into the muscles. */
private const val MUSCLE_HOLD_MILLIS = 2_000L

/** The most reps and kilograms one set takes, as a template's set does. */
private const val MAX_FREE_REPS = 9_999
private const val MAX_FREE_LOAD_KG = 1_000.0

/** The most steps or jumps a counted set takes: a long walk's. */
private const val MAX_FREE_COUNT = 99_999

/**
 * The longest a rest counts for — here, and in a workout session ([countedRest]), which sets its planned
 * rests no longer either — a set of reps left running ([stopping]), and in a template's session a
 * stretch that waits for a tap ([RoutinePhase.countedSeconds]): a quarter of an hour, more than the
 * longest rest between heavy sets.
 * Past it nobody is resting beside the phone — and every minute counted is a minute of the row, costed
 * at the exercise's own effort.
 */
internal const val MAX_COUNTED_REST_SECONDS = 15 * 60

/** Start/Stop's share of the foot against Finish's one; "+" is square. */
private const val PRIMARY_WEIGHT = 1.7f

/** The template session's own proportions, so the two pages look one. */
private val HERO_HEIGHT = 260.dp
private val SHEET_OVERLAP = 28.dp
private val SHEET_CORNER = PwnCorners.largeIncreased
private val HERO_ART = 200.dp

/** Tallest the unfolded drawing of how it is done stands. */
private val HOW_TO_MAX_HEIGHT = 200.dp
