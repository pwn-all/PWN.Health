package com.pwnall.health

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * One exercise of a finished session as its end page lists it: the sets as they went — figures,
 * seconds, and the rest after each — and the rest after its step, before the next exercise. No sets:
 * an exercise of the plan never begun.
 */
internal data class DoneExercise(val code: String, val sets: List<RoutineSet>, val restAfter: Int)

/** A finished session in three figures: the weight lifted, the seconds of the sets, the seconds rested. */
internal data class SessionTotals(val liftedKg: Double, val workSeconds: Int, val restSeconds: Int)

/** The session's [outcomes] as the end page lists them. */
internal fun sessionSummary(outcomes: List<RoutineOutcome>): List<DoneExercise> = outcomes.map { outcome ->
    DoneExercise(outcome.exercise.activityCode, outcome.done.map { it.asDone(outcome.exercise) }, outcome.restAfter)
}

/**
 * How many of [finished]'s exercises the journal took as done — [logSession]'s own answer, for a page
 * that never heard it. A page rebuilt by a turn of the phone while its session was being written comes
 * back with the summary and no count; that write lands without the page ([logSession]), so the page
 * counts for itself rather than run the session on and write it a second time. An exercise never
 * begun is a row that says so, and one the catalogue has lost is no row at all ([sessionRows]).
 */
internal fun loggedCount(finished: List<DoneExercise>): Int =
    finished.count { done -> done.sets.isNotEmpty() && ACTIVITY_CATALOG.any { it.code == done.code } }

internal fun sessionTotals(exercises: List<DoneExercise>): SessionTotals = SessionTotals(
    liftedKg = exercises.sumOf { exercise -> exercise.sets.sumOf { it.reps * (it.loadKg ?: 0.0) } },
    workSeconds = exercises.sumOf { exercise -> exercise.sets.sumOf { it.seconds } },
    restSeconds = exercises.sumOf { exercise -> exercise.restAfter + exercise.sets.sumOf { it.restSeconds } },
)

/** A set of reps as the records measure it; the end page keeps no moment for it. */
private fun RoutineSet.strength() = StrengthSet(loadKg ?: 0.0, reps, 0L)

/**
 * The records the session broke against [before] — each exercise's as the journal held them before the
 * session was written, null while unknown — one line a record however often the exercise was come back
 * to. An exercise on two steps of a template, or returned to in Freestyle, is in [exercises] once a
 * run, and each run is a journal row of its own ([logSession]). So each is measured by itself — the
 * weight lifted is one row's, never the runs' sum — and of two runs past the same record the larger
 * figure is the one listed.
 */
internal fun sessionRecords(exercises: List<DoneExercise>, before: Map<String, ExerciseRecords>?): List<BrokenRecord> =
    exercises.groupBy { it.code }.flatMap { (code, runs) ->
        val records = before?.get(code)
        runs.flatMap { run -> brokenRecords(code, records, run.sets.filter { it.reps > 0 }.map { it.strength() }) }
            .groupBy { it.kind }
            .map { (_, same) -> same.maxBy { it.value } }
            .sortedBy { it.kind }
    }

/**
 * Which sets of each of [exercises] wear the record badge, by their place in the exercise's own list,
 * which a timed set also sits in. An exercise's sets of reps are measured together across its runs, in
 * the order they were done, as the journal's records are: the badge goes to the one set that holds the
 * record, in the run it was done in, and a run is never marked by another run's count.
 */
internal fun recordBadges(exercises: List<DoneExercise>, before: Map<String, ExerciseRecords>?): List<Set<Int>> {
    val badges = exercises.map { mutableSetOf<Int>() }
    exercises.withIndex().groupBy { it.value.code }.forEach { (code, runs) ->
        // Every set of reps of the exercise, as the run it was done in and its place there.
        val places = runs.flatMap { (run, exercise) ->
            exercise.sets.withIndex().filter { it.value.reps > 0 }.map { run to it }
        }
        recordSetIndices(before?.get(code), places.map { it.second.value.strength() }).forEach { index ->
            val (run, set) = places[index]
            badges[run].add(set.index)
        }
    }
    return badges
}

/**
 * The end page's list kept through a turn of the phone or a change of theme, as text — the page is
 * rebuilt then, and without it would say only how many were logged.
 */
internal val DoneExercisesSaver = Saver<List<DoneExercise>, String>(
    save = { exercises ->
        JSONArray(
            exercises.map { exercise ->
                JSONObject()
                    .put("code", exercise.code)
                    .put("rest", exercise.restAfter)
                    .put(
                        "sets",
                        JSONArray(
                            exercise.sets.map { set ->
                                JSONObject()
                                    .put("reps", set.reps)
                                    .put("seconds", set.seconds)
                                    .put("rest", set.restSeconds)
                                    .put("floors", set.floors)
                                    .also { json -> set.loadKg?.let { json.put("loadKg", it) } }
                            },
                        ),
                    )
            },
        ).toString()
    },
    restore = { text ->
        runCatching {
            val exercises = JSONArray(text)
            List(exercises.length()) { at ->
                val exercise = exercises.getJSONObject(at)
                val sets = exercise.getJSONArray("sets")
                DoneExercise(
                    exercise.getString("code"),
                    List(sets.length()) { index ->
                        val set = sets.getJSONObject(index)
                        RoutineSet(
                            reps = set.getInt("reps"),
                            loadKg = if (set.has("loadKg")) set.getDouble("loadKg") else null,
                            seconds = set.getInt("seconds"),
                            restSeconds = set.getInt("rest"),
                            floors = set.optInt("floors"),
                        )
                    },
                    exercise.getInt("rest"),
                )
            }
        }.getOrDefault(emptyList())
    },
)

/**
 * The end of a session, a template's or Freestyle's: the rosette with its salute, the words and how many
 * exercises went into the journal stand at the top and Done at the foot. Between them scrolls each
 * exercise as it went — its sets numbered, the rests between them on the green strips the session
 * showed them on, the rest before the next exercise between the cards — and under them the session in
 * a grid: the weight lifted across the top, the time trained and the time rested side by side
 * ([sessionTotals]). The figures count up as the page opens; a screen reader reads each tile once, at
 * its final figure.
 *
 * The weight tile stands only where something was lifted, not as "0 kg" under a plank.
 */
@Composable
internal fun SessionDone(
    modifier: Modifier,
    logged: Int,
    exercises: List<DoneExercise>,
    imperial: Boolean,
    onClose: () -> Unit,
    /**
     * Each exercise's records as the journal held them just before this session was written; null
     * while unknown, which marks nothing. Read before the write, so the session is never measured
     * against itself.
     */
    before: Map<String, ExerciseRecords>? = null,
) {
    val broken = remember(exercises, before) { sessionRecords(exercises, before) }
    val badges = remember(exercises, before) { recordBadges(exercises, before) }
    Column(modifier.fillMaxSize()) {
        // The salute and the words stay put over the summary, which scrolls between them and Done.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            DoneBadge()
            Text(
                stringResource(R.string.routines_finished),
                Modifier.fillMaxWidth().semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (logged >= 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    pluralStringResource(R.plurals.routines_started, logged, logged),
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(
            Modifier.weight(1f)
                .fillMaxWidth()
                .panelVerticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NewRecordsCard(broken, imperial)
            exercises.forEachIndexed { at, exercise ->
                ExerciseDone(exercise, imperial, badges[at])
                val rest = exercise.restAfter + (exercise.sets.lastOrNull()?.restSeconds ?: 0)
                if (rest > 0) RestStrip(rest)
            }
            if (exercises.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TotalsGrid(sessionTotals(exercises), imperial)
            }
        }
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .heightIn(min = PwnDimensions.mediumButtonHeight),
        ) { Text(stringResource(R.string.common_done)) }
    }
}

/** One exercise as it went: its drawing, name and time — or "Not done" — then its sets and the rests between. */
@Composable
private fun ExerciseDone(exercise: DoneExercise, imperial: Boolean, records: Set<Int> = emptySet()) {
    val resources = LocalResources.current
    val spec = ACTIVITY_CATALOG.firstOrNull { it.code == exercise.code }
    RoutineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(EXERCISE_ART).clip(RoundedCornerShape(PwnCorners.medium)).background(PwnIndigoSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    spec?.let {
                        Image(
                            themePainterResource(it.art()),
                            null,
                            Modifier.size(EXERCISE_ART - 6.dp),
                            colorFilter = washTint(),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        spec?.let { stringResource(it.titleRes) } ?: exercise.code,
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // The time of all its sets; one set's own line already says it.
                    val note = when (exercise.sets.size) {
                        0 -> stringResource(R.string.routines_not_done)
                        1 -> null
                        else -> durationText(resources, exercise.sets.sumOf { it.seconds })
                    }
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PwnMuted) }
                }
            }
            exercise.sets.forEachIndexed { at, set ->
                SetLine(at + 1, set, imperial, exercise.code, record = at in records)
                if (at < exercise.sets.lastIndex && set.restSeconds > 0) RestStrip(set.restSeconds)
            }
        }
    }
}

/** A set's number in its disc, its figures, and — for a set of reps — the time it took. */
@Composable
private fun SetLine(number: Int, set: RoutineSet, imperial: Boolean, code: String, record: Boolean = false) {
    val resources = LocalResources.current
    val name = stringResource(R.string.routines_set_number, number)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(SET_DISC).background(PwnIndigoSoft, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                "$number",
                Modifier.semantics { contentDescription = name },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            routineSetText(resources, set, imperial, code),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (record) RecordBadge(Modifier.padding(horizontal = 8.dp))
        if ((set.reps > 0 || set.floors > 0) && set.seconds > 0) {
            Text(durationText(resources, set.seconds), style = MaterialTheme.typography.bodySmall, color = PwnMuted)
        }
    }
}

/** The weight lifted across the top, the time trained and the time rested under it, side by side. */
@Composable
private fun TotalsGrid(totals: SessionTotals, imperial: Boolean) {
    val resources = LocalResources.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (totals.liftedKg > 0) {
            val unit = stringResource(loadUnitRes(imperial))
            TotalTile(
                Icons.Outlined.FitnessCenter,
                stringResource(R.string.routines_total_lifted),
                (totals.liftedKg * loadUnitFactor(imperial)).roundToInt(),
                Modifier.fillMaxWidth(),
                largest = 34.sp,
            ) { "${it.toLong().formatted()} $unit" }
        }
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TotalTile(
                Icons.Outlined.Timer,
                stringResource(R.string.routines_total_work),
                totals.workSeconds,
                Modifier.weight(1f).fillMaxHeight(),
            ) { durationText(resources, it) }
            TotalTile(
                Icons.Outlined.LocalCafe,
                stringResource(R.string.workout_rest),
                totals.restSeconds,
                Modifier.weight(1f).fillMaxHeight(),
                disc = PwnGreenSoft,
                ink = PwnGreen,
            ) { durationText(resources, it) }
        }
    }
}

/**
 * One figure of the grid: its disc and name, and under them the figure, its units a size under its
 * digits, counting up from nothing when the page opens.
 */
@Composable
private fun TotalTile(
    icon: ImageVector,
    label: String,
    value: Int,
    modifier: Modifier,
    largest: TextUnit = 26.sp,
    disc: Color = PwnIndigoSoft,
    ink: Color = PwnMuted,
    words: (Int) -> String,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val figure by animateIntAsState(
        if (shown) value else 0,
        tween(COUNT_UP_MILLIS, easing = FastOutSlowInEasing),
        label = "total",
    )
    val final = words(value)
    RoutineCard(modifier) {
        Column(
            Modifier.padding(14.dp).clearAndSetSemantics { contentDescription = "$label, $final" },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(TOTAL_DISC).background(disc, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(20.dp), tint = ink)
                }
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = PwnMuted, maxLines = 2)
            }
            // Steps down rather than wrapping, which would leave a bare figure with its unit on a line below.
            Text(
                smallUnits(words(figure), PwnMuted),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = largest, stepSize = 1.sp),
            )
        }
    }
}

/** [text] with its words — "kg", "min", "sec" — a size under its figures and in [color]. */
private fun smallUnits(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val style = SpanStyle(color = color, fontSize = UNIT_SIZE, fontWeight = FontWeight.SemiBold)
    UNIT_WORDS.findAll(text).forEach { addStyle(style, it.range.first, it.range.last + 1) }
}

/** Letters with their marks, so a Hindi unit is one word rather than its consonants alone. */
private val UNIT_WORDS = Regex("[\\p{L}\\p{M}]+")
private val UNIT_SIZE = 0.55.em
private const val COUNT_UP_MILLIS = 900

private val EXERCISE_ART = 48.dp
private val SET_DISC = 26.dp
private val TOTAL_DISC = 36.dp
