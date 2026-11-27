package com.pwnall.health

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Base64
import java.util.Locale
import kotlin.math.roundToInt

/** The three pages of writing a template, in order: what it is and its days, its exercises, and done. */
internal enum class RoutineStep { ABOUT, FLOW, DONE }

/**
 * One step of a template while it is written: an exercise done on its own, or a superset of several
 * done back to back, and the rest after it. Stored as rows ([routineRows]) and read back from them
 * ([routineBlocks]).
 */
internal data class RoutineBlock(
    val exercises: List<RoutineExercise>,
    val superset: Boolean = false,
    val restSeconds: Int = 0,
)

/** The stored rows as the steps they were saved from: the rows of one superset share its number. */
internal fun routineBlocks(rows: List<RoutineExercise>): List<RoutineBlock> =
    rows.sortedBy(RoutineExercise::position).fold(emptyList()) { blocks, row ->
        val last = blocks.lastOrNull()
        val number = row.superset
        // A row carrying the number of the row before it is the next exercise of the same superset.
        if (last != null && number != null && last.exercises.lastOrNull()?.superset == number) {
            blocks.dropLast(1) + last.copy(exercises = last.exercises + row, restSeconds = row.restSeconds)
        } else {
            blocks + RoutineBlock(listOf(row), superset = number != null, restSeconds = row.restSeconds)
        }
    }

/**
 * The steps as rows, in order: a superset's number on each of its rows, a step's rest on its last
 * row, and no rest after the last step, where there is nothing left to rest for. A superset with
 * nothing in it is dropped.
 */
internal fun routineRows(blocks: List<RoutineBlock>): List<RoutineExercise> {
    val kept = blocks.filter { it.exercises.isNotEmpty() }
    return kept.flatMapIndexed { index, block ->
        block.exercises.mapIndexed { at, row ->
            row.copy(
                superset = index.takeIf { block.superset },
                restSeconds = if (at == block.exercises.lastIndex && index < kept.lastIndex) block.restSeconds else 0,
            )
        }
    }.mapIndexed { position, row -> row.copy(id = 0, position = position) }
}

/**
 * One set a template asks for: its reps and, for a lift, its kilograms — or, for an exercise done for a
 * time, its [seconds]; and the rest after it, which the last set never takes. A set Freestyle wrote as
 * the phone counted it carries the steps or jumps in [reps] beside its seconds, and a climb its [floors].
 */
internal data class RoutineSet(
    val reps: Int = 0,
    val loadKg: Double? = null,
    val seconds: Int = 0,
    val restSeconds: Int = 0,
    val floors: Int = 0,
)

/** [RoutineExercise.setsJson] for [sets]: `[{"reps":10,"loadKg":20.0,"rest":60}, {"seconds":330,"rest":30}, …]`. */
internal fun routineSetsJson(sets: List<RoutineSet>): String = if (sets.isEmpty()) {
    ""
} else {
    JSONArray(
        sets.map { set ->
            JSONObject().apply {
                if (set.reps > 0) put("reps", set.reps)
                set.loadKg?.let { put("loadKg", it) }
                if (set.seconds > 0) put("seconds", set.seconds)
                if (set.floors > 0) put("floors", set.floors)
                put("rest", set.restSeconds)
            }
        },
    ).toString()
}

/** The sets [json] holds; none for an empty or unreadable one. A set written without its rest takes the default. */
private fun parsedSets(json: String): List<RoutineSet> = runCatching {
    val list = JSONArray(json)
    List(list.length()) { at ->
        val set = list.getJSONObject(at)
        val reps = set.optInt("reps")
        RoutineSet(
            reps = reps,
            loadKg = if (set.has("loadKg")) set.getDouble("loadKg") else null,
            seconds = set.optInt("seconds"),
            restSeconds = set.optInt("rest", planRest(counted = reps > 0)),
            floors = set.optInt("floors"),
        )
    }
}.getOrDefault(emptyList())

/**
 * The sets [exercise] asks for, one by one: its own where it has them. One with no sets of its own
 * reads as its single figures repeated — "3 × 10 at 20 kg" is three sets of 10
 * at 20 kg, a plank of "3 × 45 sec" three holds of 45 seconds, ten minutes' walk one set of ten
 * minutes; an exercise this catalogue does not have, with no reps, has none.
 */
internal fun routineSets(exercise: RoutineExercise): List<RoutineSet> =
    parsedSets(exercise.setsJson).ifEmpty { olderSets(exercise) }

private fun olderSets(exercise: RoutineExercise): List<RoutineSet> {
    val sets = exercise.sets
    val reps = exercise.reps
    val timed = ACTIVITY_CATALOG.firstOrNull { it.code == exercise.activityCode }?.input == ActivityInput.DURATION
    return when {
        sets != null && reps != null ->
            List(sets.coerceIn(0, MAX_PLAN_SETS)) { RoutineSet(reps, exercise.loadKg, restSeconds = planRest(true)) }

        !timed -> emptyList()

        else -> {
            val (count, each) = routineSetSeconds(exercise)
                ?: (1 to (exercise.durationMinutes * 60).roundToInt().coerceAtLeast(1))
            List(count.coerceAtMost(MAX_PLAN_SETS)) { RoutineSet(seconds = each, restSeconds = planRest(false)) }
        }
    }
}

/** The rest a new set takes after it: a minute after a set of reps, half that after a timed one. */
internal fun planRest(counted: Boolean): Int = if (counted) DEFAULT_REST_SECONDS else TIMED_REST_SECONDS

/** How long [this] set takes: its own time, or its reps at a steady [SECONDS_PER_REP]. */
internal fun RoutineSet.duration(): Int = if (seconds > 0) seconds else reps * SECONDS_PER_REP

/**
 * The minutes [sets] take: each set and the rest after each but the last. Stored as the exercise's
 * minutes, so the template's total, its kcal and the journal read it as they read any exercise's.
 */
internal fun routineSetsMinutes(sets: List<RoutineSet>): Double =
    (sets.sumOf { it.duration() } + sets.dropLast(1).sumOf { it.restSeconds }) / 60.0

/** [exercise] put into the steps: a step of its own ([NEW_STEP]), into a superset, or over the one [at] it changes. */
internal fun List<RoutineBlock>.placing(step: Int, at: Int, exercise: RoutineExercise): List<RoutineBlock> =
    if (step == NEW_STEP) {
        this + RoutineBlock(listOf(exercise))
    } else {
        mapIndexed { index, block ->
            when {
                index != step -> block
                at < 0 -> block.copy(exercises = block.exercises + exercise)
                else -> block.copy(exercises = block.exercises.mapIndexed { i, row -> if (i == at) exercise else row })
            }
        }
    }

/** Exercise [at] of [step] taken out. A step of one goes with it; a superset stays, to be filled again or binned. */
internal fun List<RoutineBlock>.removing(step: Int, at: Int): List<RoutineBlock> =
    mapIndexedNotNull { index, block ->
        when {
            index != step -> block
            !block.superset -> null
            else -> block.copy(exercises = block.exercises.filterIndexed { i, _ -> i != at })
        }
    }

/** Step [step] with a rest of [seconds] after it. */
private fun List<RoutineBlock>.withRest(step: Int, seconds: Int): List<RoutineBlock> =
    mapIndexed { index, block -> if (index == step) block.copy(restSeconds = seconds) else block }

/** What a template takes and costs: its minutes, rests between steps included, and its kcal. */
internal data class RoutineTotals(val minutes: Int, val kcal: Int)

/**
 * The minutes a session from [blocks] takes — every exercise's own and every rest between steps —
 * and the energy its exercises use at [restingKcalPerHour] for a [bodyKg] body, by the same net figure
 * a logged session is written with, so the estimate on the page is what the journal will say.
 */
internal fun routineTotals(blocks: List<RoutineBlock>, restingKcalPerHour: Double, bodyKg: Double): RoutineTotals {
    val rows = routineRows(blocks)
    val seconds = rows.sumOf { it.durationMinutes * 60 } + rows.sumOf { it.restSeconds }
    val kcal = rows.sumOf { row ->
        // The working weight the journal will write: the mean of the loaded sets.
        val loads = routineSets(row).mapNotNull { it.loadKg?.takeIf { kg -> kg > 0.0 } }
        val loadKg = if (loads.isEmpty()) row.loadKg else loads.average()
        ACTIVITY_CATALOG.firstOrNull { it.code == row.activityCode }?.let {
            netActivityCalories(loadedMet(it.code, it.met, bodyKg, loadKg), restingKcalPerHour, row.durationMinutes)
        } ?: 0.0
    }
    return RoutineTotals((seconds / 60).roundToInt(), kcal.roundToInt())
}

/** The muscle groups a template works most, most-worked first, at most [limit] of them. */
internal fun routineMuscles(rows: List<RoutineExercise>, limit: Int = TOP_MUSCLES): List<Muscle> = rows
    .flatMap { row -> ACTIVITY_CATALOG.firstOrNull { it.code == row.activityCode }?.muscles.orEmpty() }
    .groupingBy { it }
    .eachCount()
    .entries
    .sortedWith(compareByDescending<Map.Entry<Muscle, Int>> { it.value }.thenBy { it.key.ordinal })
    .take(limit)
    .map { it.key }

/** Every day of the week, as [Routine.days] holds them. */
internal const val ALL_ROUTINE_DAYS = 0b111_1111

/** The bit [Routine.days] keeps this day in, Monday the lowest. */
internal fun DayOfWeek.routineBit(): Int = 1 shl ordinal

/** The week as the phone's locale lays it out, Sunday first in some places, as the work page's days off are. */
internal fun localWeek(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L..6L).map(first::plus)
}

/** "Mon · Wed · Fri": the days [mask] holds, in the locale's order and its own short names. */
internal fun routineDaysText(mask: Int, locale: Locale): String = localWeek(locale)
    .filter { mask and it.routineBit() != 0 }
    .joinToString(" · ") { it.getDisplayName(TextStyle.SHORT, locale) }

/** An exercise's name, or its code for one this catalogue does not hold. */
internal fun routineExerciseName(resources: Resources, exercise: RoutineExercise): String =
    ACTIVITY_CATALOG.firstOrNull { it.code == exercise.activityCode }?.let { resources.getString(it.titleRes) }
        ?: exercise.activityCode

/**
 * What one exercise of a template asks for, in the profile's units: its sets where it is counted in
 * reps ("3 × 10 · 60 kg"), else its minutes ("10 min").
 */
internal fun routineExerciseSummary(resources: Resources, exercise: RoutineExercise, imperial: Boolean): String {
    val sets = routineSets(exercise)
    if (sets.isNotEmpty()) return routineSetsText(resources, sets, imperial)
    val locale = resources.configuration.locales[0]
    return buildList {
        add("${exercise.durationMinutes.pretty(locale)} ${resources.getString(R.string.unit_minutes)}")
        exercise.loadKg?.let {
            add("${shownLoad(resources, it, imperial)} ${resources.getString(loadUnitRes(imperial))}")
        }
    }.joinToString(" · ")
}

/**
 * Sets in words: "3 × 10 · 20 kg" while every set is the same, else each of them — "10 × 20 · 10 × 25
 * · 7 × 30 kg", or "10 · 10 · 7 reps" with no load; timed ones "3 × 45 sec", or "5 min 30 sec · 3 min".
 */
internal fun routineSetsText(resources: Resources, sets: List<RoutineSet>, imperial: Boolean): String {
    val unit = resources.getString(loadUnitRes(imperial))
    val first = sets.first()
    return when {
        sets.none { it.reps > 0 } -> timedSetsText(resources, sets)

        sets.all { it.reps == first.reps && it.loadKg == first.loadKg } ->
            listOfNotNull(
                "${sets.size} × ${first.reps}",
                first.loadKg?.let { "${shownLoad(resources, it, imperial)} $unit" },
            ).joinToString(" · ")

        else -> {
            val each = sets.joinToString(" · ") { set ->
                set.loadKg?.let { "${set.reps} × ${shownLoad(resources, it, imperial)}" } ?: "${set.reps}"
            }
            "$each ${if (sets.any { it.loadKg != null }) unit else resources.getString(R.string.workout_reps_short)}"
        }
    }
}

/** Timed sets in words: "45 sec" for one, "3 × 45 sec" for the same three, else each one's time. */
private fun timedSetsText(resources: Resources, sets: List<RoutineSet>): String {
    val first = durationText(resources, sets.first().seconds)
    return when {
        sets.any {
            it.seconds != sets.first().seconds
        } -> sets.joinToString(" · ") { durationText(resources, it.seconds) }

        sets.size > 1 -> "${sets.size} × $first"

        else -> first
    }
}

/**
 * One set in words, as the session shows it running: "10 × 25 kg", "10 reps", or "1 min 30 sec" — and a
 * set of [code] the phone counted in steps or jumps, as those: "3 floors · 156 steps", "120 jumps".
 */
internal fun routineSetText(resources: Resources, set: RoutineSet, imperial: Boolean, code: String = ""): String =
    when {
        set.reps == 0 && set.floors == 0 -> durationText(resources, set.seconds)

        set.loadKg != null ->
            "${set.reps} × ${shownLoad(resources, set.loadKg, imperial)} ${resources.getString(loadUnitRes(imperial))}"

        countsInOwnWords(code) -> trackedText(resources, code, set.reps, set.floors)

        else -> "${set.reps} ${resources.getString(R.string.workout_reps_short)}"
    }

/** [kg] in the profile's unit, as a set's box shows it: the field's text, read back on the way out. */
private fun loadText(kg: Double, imperial: Boolean): String = (kg * loadUnitFactor(imperial)).pretty()

/** [loadText] for a line a person reads, with the decimal mark of the language showing: "22,5" in German. */
private fun shownLoad(resources: Resources, kg: Double, imperial: Boolean): String =
    (kg * loadUnitFactor(imperial)).pretty(resources.configuration.locales[0])

/**
 * The editor's own pair, for the one other place a load is typed: the label on the box, and what
 * the figure is divided by on the way into the kilograms the row stores. Without it, a profile that
 * reads in pounds would see "kg" over the box and have the pounds it typed saved as kilograms.
 */
internal fun loadUnitRes(imperial: Boolean): Int = if (imperial) R.string.unit_lb else R.string.unit_kg

internal fun loadUnitFactor(imperial: Boolean): Double = if (imperial) POUNDS_IN_A_KILOGRAM else 1.0

/** Where a template's picture is kept, by the name in its row, as a course's photograph is kept in its own. */
internal fun routinePhotoDir(context: Context): File = File(context.filesDir, ROUTINE_PHOTO_DIR)

/** The file of a template's picture, or null for a template without one. */
internal fun routinePhotoFile(context: Context, photo: String): File? =
    photo.takeIf(::isMedicinePhotoName)?.let { File(routinePhotoDir(context), it) }

/** A picked picture waits here until the template is saved, under one name, so the next replaces it. */
internal fun pendingRoutinePhoto(context: Context): File = File(context.cacheDir, ROUTINE_PENDING_FILE)

/**
 * [source] shrunk into the waiting picture: written aside and moved over the one waiting only once
 * whole, as a course's is. False for a picture that will not decode or be written. Blocking.
 */
private fun storePendingRoutinePhoto(context: Context, source: Uri): Boolean {
    val part = File(context.cacheDir, "$ROUTINE_PENDING_FILE.part")
    return runCatching {
        writeShrunkJpeg(context, source, part)
        check(part.renameTo(pendingRoutinePhoto(context))) { "cannot replace the waiting picture" }
    }.onFailure {
        part.delete()
        Log.w("RoutineFlow", "cannot store the picture", it)
    }.isSuccess
}

/**
 * A template as a file the app on another phone imports ([readRoutineFile]): its name, few words and
 * days, its exercises as a backup writes them, and its picture inside it, so that one file is all
 * there is to send.
 */
internal fun routineFileText(routine: Routine, rows: List<RoutineExercise>, jpeg: ByteArray?): String = JSONObject()
    .put(ROUTINE_FILE_MARK, ROUTINE_FILE_VERSION)
    .put("name", routine.name)
    .put("description", routine.description)
    .put("days", routine.days)
    .put("exercises", JSONArray(rows.sortedBy(RoutineExercise::position).map(RoutineExercise::toJson)))
    .apply { jpeg?.let { put("jpeg", Base64.getEncoder().encodeToString(it)) } }
    .toString()

/**
 * What a template file holds: the pages it fills, and its picture's bytes where it carries one.
 * [cutFrom] is how many exercises the file listed where more were listed than are taken
 * ([MAX_FILE_EXERCISES]), and nought where all of them were: the first page says so, since a
 * template that arrived a hundred exercises long out of three hundred would look whole.
 */
internal class RoutineFile(
    val name: String,
    val description: String,
    val days: Int,
    val rows: List<RoutineExercise>,
    val jpeg: ByteArray?,
    val cutFrom: Int,
)

/**
 * The template in a file's [text], or null for a file that is not one. The file comes from somebody
 * else's phone, so nothing in it is taken on trust: an exercise this catalogue does not have is left
 * out, every figure the form has a box for is held under that box's own ceiling, and a file with no
 * exercise left is none. Its name is held to one line ([routineName]); a set's floors, which the form
 * has no box for, are not taken; and no more than [MAX_FILE_EXERCISES] exercises are — the page that
 * lists them draws every one at once and keeps them all through a turn of the phone, and a file of
 * four megabytes holds tens of thousands. A picture that is not Base64 is left behind and the
 * template taken without it, as one that will not decode as an image is ([importRoutineFile]).
 */
internal fun readRoutineFile(text: String): RoutineFile? = runCatching {
    val json = JSONObject(text)
    val name = routineName(json.getString("name"))
    val list = json.getJSONArray("exercises")
    // One past the ceiling, and no further: enough to know that the file went on.
    val read = (0 until list.length()).asSequence()
        .mapNotNull { list.optJSONObject(it)?.let(::importedExercise) }
        .take(MAX_FILE_EXERCISES + 1)
        .toList()
    val rows = read.take(MAX_FILE_EXERCISES)
    require(json.optInt(ROUTINE_FILE_MARK) == ROUTINE_FILE_VERSION && name.isNotEmpty() && rows.isNotEmpty()) {
        "not a workout template"
    }
    RoutineFile(
        name = name,
        description = json.optString("description").trim().take(ROUTINE_DESCRIPTION_MAX),
        days = json.optInt("days") and ALL_ROUTINE_DAYS,
        rows = rows,
        jpeg = json.optString("jpeg").takeIf(String::isNotEmpty)
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() },
        cutFrom = if (read.size > rows.size) list.length() else 0,
    )
}.getOrNull()

/**
 * A template's name as it is kept: one line, with no space at either end, no longer than its box
 * takes. A line break in it — pasted into the box, or written in somebody else's file — would stand in
 * the name of the file it is shared as, in the notification on the morning it is planned for, and in
 * the "Push day #3" every session of it is called by.
 */
internal fun routineName(text: String): String =
    text.replace(NAME_BREAKS, " ").trim().take(ROUTINE_NAME_MAX).trimEnd()

/** One exercise of a file, held to the form's bounds; null for one this catalogue does not know. */
private fun importedExercise(json: JSONObject): RoutineExercise? = runCatching { json.toRoutineExercise() }
    .getOrNull()
    ?.takeIf { row -> ACTIVITY_CATALOG.any { it.code == row.activityCode } }
    ?.let { row ->
        // Sets of the exercise's own kind — reps, or a time — each held to the form's bounds.
        val counted = ACTIVITY_CATALOG.first { it.code == row.activityCode }.input != ActivityInput.DURATION
        val sets = parsedSets(row.setsJson)
            .filter { if (counted) it.reps in 1..MAX_COUNT else it.seconds in 1..MAX_REST_SECONDS }
            .map { set ->
                set.copy(
                    reps = if (counted) set.reps else 0,
                    loadKg = set.loadKg?.takeIf { counted && it.isFinite() && it > 0.0 && it <= MAX_LOAD_KG },
                    seconds = if (counted) 0 else set.seconds,
                    restSeconds = set.restSeconds.coerceIn(0, MAX_REST_SECONDS),
                    // What the phone counted of a set done, never a thing a plan asks for.
                    floors = 0,
                )
            }
            .take(MAX_PLAN_SETS)
        row.copy(
            id = 0,
            routineId = 0,
            durationMinutes = if (sets.isNotEmpty()) {
                routineSetsMinutes(sets)
            } else {
                row.durationMinutes.takeIf(Double::isFinite)?.coerceIn(1.0, 600.0) ?: 10.0
            },
            sets = row.sets?.takeIf { it in 1..MAX_COUNT },
            reps = row.reps?.takeIf { it in 1..MAX_COUNT },
            loadKg = row.loadKg?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_LOAD_KG },
            restSeconds = row.restSeconds.coerceAtMost(MAX_REST_SECONDS),
            setsJson = routineSetsJson(sets),
        )
    }

/**
 * The template in the file at [uri] and whether its picture is now the waiting one; null for a file
 * that holds no template, or one too big to be a template. Blocking.
 */
private fun importRoutineFile(context: Context, uri: Uri): Pair<RoutineFile, Boolean>? {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readNBytes(ROUTINE_FILE_MAX_BYTES + 1) }
    }.getOrNull()
    val file = bytes?.takeIf { it.size <= ROUTINE_FILE_MAX_BYTES }?.let { readRoutineFile(it.decodeToString()) }
    return file?.let {
        // Decoded and shrunk again rather than copied: a picture from another phone is kept the way
        // every picture here is kept, and one that will not decode is simply left behind.
        val raw = File(context.cacheDir, ROUTINE_IMPORT_FILE)
        val pictured = it.jpeg != null &&
            runCatching { raw.writeBytes(it.jpeg) }.isSuccess &&
            storePendingRoutinePhoto(context, Uri.fromFile(raw))
        raw.delete()
        it to pictured
    }
}

/**
 * The template written as one act: its row, new or changed, and its exercises in place of the ones it
 * had. A picture picked in the flow is moved in before the row names it, and the one it replaced goes
 * once the row no longer does. [before] is the picture the stored template had. The dates still ahead
 * that it is planned for take its new name and minutes with it ([plansFollowing]). Returns the template
 * as saved.
 *
 * A new template saved a second time is the one the first save wrote, found by [Routine.createdAt],
 * the moment its flow was opened, which the flow keeps through a turn of the phone. A turn while the
 * first save is being written rebuilds the page without its answer — the write lands all the same —
 * and Save, ready again, would otherwise write the template a second time. The picture that save
 * moved in is no longer waiting, so it stays the template's unless another has been picked since.
 */
internal suspend fun StepsDb.saveRoutine(
    context: Context,
    routine: Routine,
    before: String,
    pendingPhoto: Boolean,
    blocks: List<RoutineBlock>,
): Routine {
    val stored = if (routine.id == 0L) {
        dao().allRoutinesNow().firstOrNull { it.createdAt == routine.createdAt }
    } else {
        null
    }
    val (photo, replaced) = withContext(Dispatchers.IO) {
        val formPhoto = if (stored != null && pendingPhoto) stored.photo else routine.photo
        savedMedicinePhoto(stored?.photo ?: before, formPhoto, pendingPhoto) {
            keepPendingMedicinePhoto(
                context,
                System.currentTimeMillis(),
                into = routinePhotoDir(context),
                pending = pendingRoutinePhoto(context),
            )
        }
    }
    val today = LocalDate.now().toString()
    val saved = withTransaction {
        val dao = dao()
        val row = routine.copy(id = stored?.id ?: routine.id, photo = photo)
        val id = if (row.id == 0L) dao.insertRoutine(row) else row.id.also { dao.updateRoutine(row) }
        dao.deleteRoutineExercises(id)
        val rows = routineRows(blocks)
        rows.forEach { dao.insertRoutineExercise(it.copy(routineId = id)) }
        val written = row.copy(id = id)
        // The dates it is planned for say its name and its minutes themselves ([planEntry]), so they are
        // rewritten in the same act: left alone, a plan would go on naming a template no longer called that.
        plansFollowing(dao.journalAround(KIND_PLAN, Long.MIN_VALUE, Long.MAX_VALUE), written, rows, today)
            .forEach { dao.saveJournalEntry(it) }
        written
    }
    replaced?.let { withContext(Dispatchers.IO) { routinePhotoFile(context, it)?.delete() } }
    return saved
}

/**
 * Writing a workout template, or changing one, page by page as adding lab results is, with the same
 * bar filling over them: what it is — its name, its picture, a few words and the days it is for —
 * its exercises and supersets with the rests between them, and the page that says it is saved.
 *
 * [editing] is the template being changed, with its [rows]; null for a new one. [onLeave] goes back
 * to where the flow was opened from, [onOpen] opens a saved template's page, [onAnother] starts a
 * new flow.
 *
 * The first page is typed, so it moves on by Continue; the exercises are a list, saved by Save.
 */
@Composable
internal fun RoutineFlow(
    modifier: Modifier,
    editing: Routine?,
    rows: List<RoutineExercise>,
    profile: UserProfile,
    onLeave: () -> Unit,
    onOpen: (Long) -> Unit,
    onAnother: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { StepsDb.get(context) }
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(RoutineStep.ABOUT) }
    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(editing?.description.orEmpty()) }
    var photo by rememberSaveable { mutableStateOf(editing?.photo.orEmpty()) }
    // A picture picked here waits in the cache until Save. [shots] counts the picks, so a second one
    // under the same cache name is decoded again rather than shown from the first.
    var pending by rememberSaveable { mutableStateOf(false) }
    var shots by rememberSaveable { mutableIntStateOf(0) }
    var days by rememberSaveable { mutableIntStateOf(editing?.days ?: 0) }
    var blocks by rememberSaveable(stateSaver = BLOCKS_SAVER) { mutableStateOf(routineBlocks(rows)) }
    var savedId by rememberSaveable { mutableLongStateOf(0L) }
    // Kept through a turn of the phone, as the moment a new template's save finds it by ([saveRoutine]).
    val createdAt = rememberSaveable { editing?.createdAt ?: System.currentTimeMillis() }
    var saving by remember { mutableStateOf(false) }
    // The exercise page standing in for the flow: the step it writes into ([NEW_STEP] for a step of
    // its own, [NO_PAGE] while it is shut) and which exercise of that step it changes, -1 for a new one.
    var exerciseStep by rememberSaveable { mutableIntStateOf(NO_PAGE) }
    var exerciseAt by rememberSaveable { mutableIntStateOf(-1) }
    // The step whose rest is being set from nothing; one that has a rest sets it from its own row.
    var restAfter by rememberSaveable { mutableIntStateOf(-1) }
    // The last file chosen in the corner held no template: said on the first page until another is.
    var unreadable by rememberSaveable { mutableStateOf(false) }
    // And how many exercises the last one listed, where it listed more than a template takes.
    var cutFrom by rememberSaveable { mutableIntStateOf(0) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val read = withContext(Dispatchers.IO) { importRoutineFile(context, uri) }
                unreadable = read == null
                cutFrom = read?.first?.cutFrom ?: 0
                read?.let { (file, pictured) ->
                    name = file.name
                    description = file.description
                    days = file.days
                    blocks = routineBlocks(file.rows)
                    photo = ""
                    pending = pictured
                    shots++
                }
            }
        }
    }

    if (exerciseStep != NO_PAGE) {
        val current = blocks.getOrNull(exerciseStep)?.exercises?.getOrNull(exerciseAt)
        BackHandler { exerciseStep = NO_PAGE }
        ExercisePage(
            modifier = modifier,
            imperial = profile.imperial,
            initial = current,
            onBack = { exerciseStep = NO_PAGE },
        ) { exercise ->
            blocks = blocks.placing(exerciseStep, exerciseAt, exercise)
            exerciseStep = NO_PAGE
        }
        return
    }

    fun leaveStep() {
        if (step == RoutineStep.FLOW) {
            step = RoutineStep.ABOUT
        } else {
            onLeave()
        }
    }
    // Back walks the pages, as the arrow does; from the first page and from the last it leaves.
    BackHandler { leaveStep() }

    fun save() {
        saving = true
        scope.launch {
            // Not cut off by a back press mid-transaction: the page's scope goes with the page.
            val saved = withContext(NonCancellable) {
                db.saveRoutine(
                    context,
                    Routine(
                        id = editing?.id ?: 0L,
                        name = routineName(name),
                        createdAt = createdAt,
                        description = description.trim(),
                        photo = photo,
                        days = days,
                    ),
                    before = editing?.photo.orEmpty(),
                    pendingPhoto = pending,
                    blocks = blocks,
                )
            }
            photo = saved.photo
            pending = false
            savedId = saved.id
            saving = false
            step = RoutineStep.DONE
        }
    }

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier.fillMaxSize()) {
        // A template somebody sent is imported from the first page's corner: it fills the pages,
        // which are then walked and saved as a template typed in by hand is. Only for a new one —
        // a file over a template being changed would be a different template under its name.
        val importAction: (@Composable () -> Unit)? = if (editing == null && step == RoutineStep.ABOUT) {
            {
                IconButton(
                    onClick = { importer.launch(ROUTINE_FILE_TYPES) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                ) {
                    Icon(Icons.Outlined.FileOpen, stringResource(R.string.routines_import))
                }
            }
        } else {
            null
        }
        LabStepBar(step.ordinal, RoutineStep.entries.size, ::leaveStep, importAction)
        // Each page slides in from the side it is ahead of, and back out to it: the bar above says
        // which page this is, the slide says which way the flow went.
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val ahead = if ((targetState > initialState) != rtl) 1 else -1
                (slideInHorizontally(PwnMotion.spatial()) { ahead * it / SLIDE_PART } + fadeIn(PwnMotion.effects()))
                    .togetherWith(
                        slideOutHorizontally(PwnMotion.spatial()) { -ahead * it / SLIDE_PART } +
                            fadeOut(PwnMotion.effects()),
                    )
            },
            label = "routine step",
        ) { shown ->
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .panelVerticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    when (shown) {
                        RoutineStep.ABOUT -> AboutPage(
                            unreadable = unreadable,
                            cutFrom = cutFrom,
                            name = name,
                            onName = { name = it.take(ROUTINE_NAME_MAX) },
                            description = description,
                            onDescription = { description = it.take(ROUTINE_DESCRIPTION_MAX) },
                            shown = if (pending) pendingRoutinePhoto(context) else routinePhotoFile(context, photo),
                            shots = shots,
                            onPicked = {
                                pending = true
                                shots++
                            },
                            onRemove = {
                                pending = false
                                photo = ""
                                shots++
                            },
                            days = days,
                            onDays = { days = it },
                            onContinue = { step = RoutineStep.FLOW },
                        )

                        RoutineStep.FLOW -> {
                            LabStepTitle(
                                stringResource(R.string.routines_flow_title),
                                stringResource(R.string.routines_flow_lead),
                            )
                            blocks.forEachIndexed { index, block ->
                                RoutineBlockCard(
                                    number = index,
                                    block = block,
                                    imperial = profile.imperial,
                                    onEdit = { at ->
                                        exerciseStep = index
                                        exerciseAt = at
                                    },
                                    onRemove = { at -> blocks = blocks.removing(index, at) },
                                    onAddInto = {
                                        exerciseStep = index
                                        exerciseAt = -1
                                    },
                                    onRemoveStep = { blocks = blocks.filterIndexed { i, _ -> i != index } },
                                )
                                if (index < blocks.lastIndex) {
                                    RestSlot(block.restSeconds, onAdd = { restAfter = index }) { seconds ->
                                        blocks = blocks.withRest(index, seconds)
                                    }
                                }
                            }
                        }

                        RoutineStep.DONE -> RoutineDonePage(
                            name = name,
                            description = description,
                            photo = photo,
                            rows = routineRows(blocks),
                            onOpen = { onOpen(savedId) },
                            onAnother = onAnother,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                // The exercises scroll; adding one, adding a superset and Save stay at the foot.
                if (shown == RoutineStep.FLOW) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Level: a label that wraps makes both buttons taller, not one.
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val half = Modifier.weight(1f).fillMaxHeight().heightIn(min = PwnDimensions.minimumTarget)
                            PwnOutlinedButton(
                                onClick = {
                                    exerciseStep = NEW_STEP
                                    exerciseAt = -1
                                },
                                modifier = half,
                            ) { Text(stringResource(R.string.routines_add_exercise), textAlign = TextAlign.Center) }
                            PwnOutlinedButton(
                                onClick = { blocks = blocks + RoutineBlock(emptyList(), superset = true) },
                                modifier = half,
                            ) { Text(stringResource(R.string.routines_add_superset), textAlign = TextAlign.Center) }
                        }
                        Button(
                            onClick = ::save,
                            enabled = blocks.any { it.exercises.isNotEmpty() } && !saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                        ) { Text(stringResource(R.string.common_save)) }
                    }
                }
            }
        }
    }

    if (restAfter >= 0) {
        DurationPickerDialog(DEFAULT_REST_SECONDS, onDismiss = { restAfter = -1 }) { seconds ->
            blocks = blocks.withRest(restAfter, seconds)
            restAfter = -1
        }
    }
}

/**
 * The first page: the name with the picture's square beside it, the few words under them, the days
 * of the week as circles, and Continue — and, [unreadable], the line saying the file just chosen
 * held no template, or, [cutFrom] being how many exercises it listed, the line saying that only the
 * first of them were taken.
 */
@Composable
private fun AboutPage(
    unreadable: Boolean,
    cutFrom: Int,
    name: String,
    onName: (String) -> Unit,
    description: String,
    onDescription: (String) -> Unit,
    shown: File?,
    shots: Int,
    onPicked: () -> Unit,
    onRemove: () -> Unit,
    days: Int,
    onDays: (Int) -> Unit,
    onContinue: () -> Unit,
) {
    LabStepTitle(stringResource(R.string.routines_about_title), null)
    if (unreadable) {
        Text(stringResource(R.string.routines_import_error), color = MaterialTheme.colorScheme.error)
    }
    if (cutFrom > 0) {
        Text(
            stringResource(R.string.routines_import_cut, MAX_FILE_EXERCISES, cutFrom),
            color = MaterialTheme.colorScheme.error,
        )
    }
    // Bottom-aligned: the outlined field keeps room above its border for the floating label, and it
    // is the border box, not the label, the square stands level with.
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text(stringResource(R.string.routines_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.weight(1f),
        )
        RoutinePhotoButton(shown, shots, onPicked, onRemove)
    }
    OutlinedTextField(
        value = description,
        onValueChange = onDescription,
        label = { Text(stringResource(R.string.routines_description)) },
        minLines = 2,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.routines_days_title),
        Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    DayCircles(days, onDays)
    Button(
        onClick = onContinue,
        enabled = routineName(name).isNotEmpty(),
        modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
    ) { Text(stringResource(R.string.common_continue)) }
}

/**
 * The week as seven circles in one row, the way a phone's alarm asks for its days: each holds its
 * day's short name and fills with the accent once it is one of the template's days. In the locale's
 * own order — Sunday first where the week starts on it. A short name too long for a circle (Arabic
 * writes the days out in full) is the one-letter form instead; a screen reader hears the full name.
 * The Work settings' usual days off are picked on the same circles.
 */
@Composable
internal fun DayCircles(days: Int, onDays: (Int) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        localWeek(locale).forEach { day ->
            val on = days and day.routineBit() != 0
            val short = day.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
                .let { if (it.length > DAY_LABEL_MAX) day.getDisplayName(TextStyle.NARROW_STANDALONE, locale) else it }
                .replaceFirstChar { it.titlecase(locale) }
            val full = day.getDisplayName(TextStyle.FULL_STANDALONE, locale)
            val fill by animateColorAsState(
                if (on) MaterialTheme.colorScheme.primary else PwnIndigoSoft,
                PwnMotion.effects(),
                label = "day circle",
            )
            Box(
                Modifier.weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(fill)
                    .toggleable(value = on, role = Role.Checkbox) { onDays(days xor day.routineBit()) }
                    .semantics { contentDescription = full },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    short,
                    Modifier.clearAndSetSemantics {},
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * The square beside the template's name: a picture to add while there is none, the picture once
 * there is. A tap offers the camera and the gallery either way — a template's picture is as often one
 * already saved as one taken on the spot — and, once there is one, taking it off.
 */
@Composable
private fun RoutinePhotoButton(shown: File?, shots: Int, onPicked: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val capture = remember(context) {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.photos",
            File(sharedCacheDir(context), ROUTINE_CAPTURE_FILE),
        )
    }
    val keep: (Uri) -> Unit = { source ->
        scope.launch {
            // The page can close under the camera; a picture that was taken still lands in the cache.
            val kept = withContext(NonCancellable + Dispatchers.IO) {
                storePendingRoutinePhoto(context, source)
                    .also { File(sharedCacheDir(context), ROUTINE_CAPTURE_FILE).delete() }
            }
            if (kept) onPicked()
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) keep(capture)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        picked?.let(keep)
    }
    val pick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val label = stringResource(R.string.routines_photo)
    Box {
        Surface(
            onClick = { menu = true },
            modifier = Modifier.size(PHOTO_FIELD_SIZE).opensMenu(menu) { menu = it }.semantics {
                role = Role.Button
                contentDescription = label
            },
            shape = RoundedCornerShape(PwnCorners.medium),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            RoutinePicture(shown, shots, PHOTO_FIELD_SIZE, empty = Icons.Outlined.AddPhotoAlternate)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.progress_photo_take)) },
                leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) },
                onClick = {
                    menu = false
                    // A tablet without a camera app throws on launch; the gallery stands in for it.
                    runCatching { camera.launch(capture) }.onFailure { pick() }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.progress_photo_pick)) },
                leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                onClick = {
                    menu = false
                    pick()
                },
            )
            if (shown != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.med_photo_remove)) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/**
 * A template's picture filling its square, or the square with [empty] drawn in it for a template
 * without one — and for one whose file has gone. Silent: the name beside it says whose it is.
 * [shots] counts the pictures picked under one name, so each is decoded afresh.
 */
@Composable
internal fun RoutinePicture(
    file: File?,
    shots: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    empty: ImageVector = Icons.Outlined.FitnessCenter,
) {
    val image = rememberMedicineThumbnail(file, shots)
    val bitmap = image?.getOrNull()
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(PwnCorners.medium))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)

            // Nothing while the picture decodes, so a row with one does not flash the drawing first.
            image != null -> Icon(empty, null, tint = PwnMuted)
        }
    }
}

/**
 * One step of a template, numbered: an exercise on its own, or a superset with its exercises under
 * it. On the flow page each exercise opens on a tap and has its bin, and a superset takes more
 * exercises and has a bin of its own; on the template's own page the same card only shows.
 */
@Composable
internal fun RoutineBlockCard(
    number: Int,
    block: RoutineBlock,
    imperial: Boolean,
    onEdit: ((Int) -> Unit)? = null,
    onRemove: ((Int) -> Unit)? = null,
    onAddInto: (() -> Unit)? = null,
    onRemoveStep: (() -> Unit)? = null,
) {
    val resources = LocalResources.current
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (block.superset) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SetBadge(number)
                    Spacer(Modifier.width(10.dp))
                    val title = stringResource(R.string.workout_superset)
                    HelpTitle(title, Modifier.weight(1f), MaterialTheme.typography.titleSmall) {
                        Text(stringResource(R.string.workout_superset_explain))
                    }
                    onRemoveStep?.let {
                        IconButton(onClick = it) {
                            Image(
                                themePainterResource(R.drawable.ic_art_delete),
                                stringResource(R.string.med_contents_remove, title),
                                Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
            block.exercises.forEachIndexed { at, exercise ->
                val name = routineExerciseName(resources, exercise)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(PwnCorners.medium))
                        .then(
                            onEdit?.let {
                                Modifier.clickable(
                                    onClickLabel = stringResource(R.string.common_edit),
                                    role = Role.Button,
                                ) { it(at) }
                            } ?: Modifier,
                        )
                        .heightIn(min = PwnDimensions.minimumTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!block.superset) {
                        SetBadge(number)
                        Spacer(Modifier.width(10.dp))
                    }
                    ACTIVITY_CATALOG.firstOrNull { it.code == exercise.activityCode }?.let { spec ->
                        Image(themePainterResource(spec.art()), null, Modifier.size(34.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            routineExerciseSummary(resources, exercise, imperial),
                            style = MaterialTheme.typography.bodySmall,
                            color = PwnMuted,
                        )
                    }
                    // It names the exercise it drops: one bin per row, each read out as "Delete",
                    // would leave a screen reader's list of actions with no way to tell them apart.
                    onRemove?.let {
                        IconButton(onClick = { it(at) }) {
                            Image(
                                themePainterResource(R.drawable.ic_art_delete),
                                stringResource(R.string.med_contents_remove, name),
                                Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
            if (block.superset) {
                onAddInto?.let {
                    TextButton(onClick = it) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.routines_add_exercise))
                    }
                }
            }
        }
    }
}

/**
 * The gap after a step on the flow page: the session's own rest row once there is a rest, its Edit
 * setting it on the session's own wheels, and a small "+ Rest" while there is none. A rest set to
 * nothing is none again.
 */
@Composable
private fun RestSlot(seconds: Int, onAdd: () -> Unit, onChange: (Int) -> Unit) {
    if (seconds > 0) {
        RestPlanRow(seconds, done = false, onEdit = onChange)
    } else {
        TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = PwnDimensions.minimumTarget)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.workout_rest))
        }
    }
}

/**
 * The last page: the template is saved, and the two things worth doing next — opening it, or
 * writing the next one. The rosette and the salute the lab results end on.
 */
@Composable
private fun RoutineDonePage(
    name: String,
    description: String,
    photo: String,
    rows: List<RoutineExercise>,
    onOpen: () -> Unit,
    onAnother: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        DoneHeading(R.string.routines_saved_title)
        PwnCard(Modifier.fillMaxWidth(), onClick = onOpen) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        name.trim(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        description.trim().ifEmpty { routineCountText(rows) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = PwnMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                RoutinePicture(routinePhotoFile(context, photo), 0, DONE_PICTURE_SIZE)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
        ) { Text(stringResource(R.string.routines_open_template)) }
        Spacer(Modifier.height(10.dp))
        PwnOutlinedButton(
            onClick = onAnother,
            modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
        ) { Text(stringResource(R.string.routines_add_another)) }
    }
}

/** "3 exercises · 25 min", for a template nobody described. */
@Composable
internal fun routineCountText(rows: List<RoutineExercise>): String = pluralStringResource(
    R.plurals.routines_exercise_count,
    rows.size,
    rows.size,
    rows.sumOf { it.durationMinutes }.shown(),
)

/** The three pages "Add exercise" opens on. */
private enum class ExerciseStep { PICK, COUNT, SETS }

/**
 * One exercise for a template, in three pages: the exercise, picked from the catalogue by name; how
 * many sets, on a minus and a plus under its picture; and each set — its reps and weight, or its
 * time on the minute and second wheels — with the rest after it on the same wheels. It stands in for
 * the flow while it is open, in the same window, and opens on the sets when an exercise already in
 * the template is changed.
 */
@Composable
private fun ExercisePage(
    modifier: Modifier,
    imperial: Boolean,
    initial: RoutineExercise?,
    onBack: () -> Unit,
    onSave: (RoutineExercise) -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The sets as the session's own set rows hold them: reps and a load in the profile's unit, or a
    // timed set's seconds, and the rest after each.
    val initialSets = remember(initial) { initial?.let(::routineSets).orEmpty() }
    val initialPlan = remember(initialSets) {
        initialSets.map {
            PlannedSet(
                reps = if (it.reps > 0) it.reps.toString() else "",
                load = it.loadKg?.let { kg -> loadText(kg, imperial) }.orEmpty(),
                actualSeconds = it.seconds,
                restSeconds = it.restSeconds,
            )
        }
    }
    // Null for a new exercise until one is picked: the cards show none chosen before a tap. The pages
    // after the pick read the catalogue's first in the meantime, which they are never shown with.
    var code by rememberSaveable { mutableStateOf(initial?.activityCode) }
    val picked = ACTIVITY_CATALOG.firstOrNull { it.code == code }
    val spec = picked ?: ACTIVITY_CATALOG.first()
    val counted = spec.input != ActivityInput.DURATION
    var plan by rememberSaveable(stateSaver = plannedSetsSaver) {
        mutableStateOf(
            initialPlan.ifEmpty {
                List(DEFAULT_PLAN_SETS) {
                    PlannedSet(actualSeconds = DEFAULT_SET_SECONDS, restSeconds = planRest(counted))
                }
            },
        )
    }
    var step by rememberSaveable { mutableStateOf(if (initial == null) ExerciseStep.PICK else ExerciseStep.SETS) }
    val goBack = { if (step == ExerciseStep.PICK) onBack() else step = ExerciseStep.entries[step.ordinal - 1] }
    BackHandler(onBack = goBack)

    fun pick(candidate: ActivitySpec) {
        // Changing between a lift and a timed exercise gives the sets that kind's rest and, where they
        // have none, a time to hold.
        val nowCounted = candidate.input != ActivityInput.DURATION
        if (nowCounted != counted) {
            plan = plan.map {
                it.copy(
                    restSeconds = planRest(nowCounted),
                    actualSeconds = it.actualSeconds.takeIf { s -> s > 0 } ?: DEFAULT_SET_SECONDS,
                )
            }
        }
        code = candidate.code
    }

    fun save() {
        val loaded = spec.input == ActivityInput.WEIGHTED_SETS
        val sets = plan.mapIndexed { at, set ->
            if (!counted) {
                RoutineSet(seconds = set.actualSeconds, restSeconds = set.restSeconds)
            } else {
                RoutineSet(
                    reps = set.reps.intNumber(),
                    loadKg = when {
                        !loaded -> null

                        // Untouched, a load stays the kilograms it was: pounds rounded and back would
                        // move it every time the exercise was opened.
                        set.load == initialPlan.getOrNull(at)?.load && initial?.activityCode == spec.code ->
                            initialSets[at].loadKg

                        else -> set.load.number().takeIf { it > 0.0 }
                            ?.let { (it / loadUnitFactor(imperial)).coerceAtMost(MAX_LOAD_KG) }
                    },
                    restSeconds = set.restSeconds,
                )
            }
        }
        onSave(
            RoutineExercise(
                routineId = 0,
                position = 0,
                activityCode = spec.code,
                durationMinutes = routineSetsMinutes(sets),
                // The first set's figures stand for the lot wherever one figure is read: a backup
                // restored by an older release, Health Connect's planned block.
                sets = sets.size,
                reps = sets.first().reps.takeIf { counted },
                loadKg = sets.first().loadKg,
                setsJson = routineSetsJson(sets),
            ),
        )
    }

    Column(modifier.fillMaxSize()) {
        ExerciseStepBar(step.ordinal, ExerciseStep.entries.size, goBack)
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val ahead = if ((targetState > initialState) != rtl) 1 else -1
                (slideInHorizontally(PwnMotion.spatial()) { ahead * it / SLIDE_PART } + fadeIn(PwnMotion.effects()))
                    .togetherWith(
                        slideOutHorizontally(PwnMotion.spatial()) { -ahead * it / SLIDE_PART } +
                            fadeOut(PwnMotion.effects()),
                    )
            },
            label = "exercise step",
        ) { shown ->
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .panelVerticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (shown) {
                        // A tap on a card is the choice: the next page follows at once.
                        ExerciseStep.PICK -> ExercisePickPage(picked) {
                            pick(it)
                            step = ExerciseStep.COUNT
                        }

                        ExerciseStep.COUNT -> {
                            val other = spec.code in OTHER_WORKOUT_CODES
                            LabStepTitle(
                                stringResource(if (other) R.string.routines_other_workouts else spec.titleRes),
                                stringResource(R.string.routines_setup_lead),
                            )
                            if (other) OtherWorkoutField(spec) { pick(it) }
                            ExerciseHero(spec)
                            SetCountCard(plan.size) { count ->
                                // A set added takes the last one's figures, the usual next set, to be changed.
                                plan =
                                    plan.take(count) + List((count - plan.size).coerceAtLeast(0)) { plan.last().copy() }
                            }
                        }

                        ExerciseStep.SETS -> {
                            LabStepTitle(stringResource(spec.titleRes), stringResource(R.string.editor_sets))
                            plan.forEachIndexed { at, set ->
                                SetCard(
                                    at = at,
                                    set = set,
                                    spec = spec,
                                    imperial = imperial,
                                    last = at == plan.lastIndex,
                                    onChange = { changed -> plan = plan.toMutableList().also { it[at] = changed } },
                                    onRemove = {
                                        plan = plan.filterIndexed { i, _ -> i != at }
                                    }.takeIf { plan.size > 1 },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (shown !=
                    ExerciseStep.PICK
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        when (shown) {
                            ExerciseStep.PICK -> Unit

                            ExerciseStep.COUNT -> PwnWideButton(
                                icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                label = stringResource(R.string.common_continue),
                                onClick = { step = ExerciseStep.SETS },
                            )

                            // Every set needs its reps or its time; a weight may be left out, as for the bar alone.
                            ExerciseStep.SETS -> PwnWideButton(
                                icon = Icons.Outlined.Check,
                                label = stringResource(R.string.common_save),
                                enabled = plan.all {
                                    if (counted) {
                                        it.reps.intNumber() in 1..MAX_COUNT
                                    } else {
                                        it.actualSeconds >
                                            0
                                    }
                                },
                                onClick = ::save,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The page's top: the round way back on one side, and which of the [count] pages this is on the other. */
@Composable
private fun ExerciseStepBar(step: Int, count: Int, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = PwnDimensions.topBarHeight).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoutineHeroButton(
            Icons.AutoMirrored.Outlined.ArrowBack,
            stringResource(R.string.common_back),
            onPicture = false,
            onClick = onBack,
        )
        Spacer(Modifier.weight(1f))
        Surface(shape = CircleShape, color = PwnIndigoSoft) {
            Text(
                stringResource(R.string.lab_add_step, step + 1, count),
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * A name of the catalogue, or what was typed into its search, as the two are compared.
 *
 * Lowered by the rules of the language the names are in, not by the invariant ones a bare
 * `lowercase()` uses: under those the Turkish "İp atlama" becomes an i, a combining dot and the
 * rest, and "ip" would not be found in it — in this picker or in the editor's.
 */
internal fun String.searchFolded(locale: Locale): String = trim().lowercase(locale)

/**
 * The first page: the catalogue as cards with their drawings, narrowed by what is typed above it; a tap
 * on a card picks it. [titled] puts the page's title over the search, for a page with no title of its own.
 *
 * The workouts only the clock can measure are one card, "Other workouts", which picks the one chosen
 * before or the first of them, for the next page's list to change; typed for, each is found by its own
 * name. With [onOutdoors], a walk, a run or a hike unfolds into indoors — picked, its steps counted —
 * and outdoors, which goes to the walk page and its route instead.
 */
@Composable
internal fun ExercisePickPage(
    selected: ActivitySpec?,
    titled: Boolean = true,
    onOutdoors: (() -> Unit)? = null,
    onPick: (ActivitySpec) -> Unit,
) {
    val resources = LocalResources.current
    var query by rememberSaveable { mutableStateOf("") }
    // The names are resources, so the search has to read them here rather than filter on `code`.
    val locale = resources.configuration.locales[0]
    val named = remember(resources) {
        ACTIVITY_CATALOG.associateWith { resources.getString(it.titleRes).searchFolded(locale) }
    }
    val typed = query.searchFolded(locale)
    if (titled) LabStepTitle(stringResource(R.string.routines_pick_title), stringResource(R.string.routines_pick_lead))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.editor_search_activity)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.food_recent_clear))
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
    )
    val shown = PICKABLE_ACTIVITIES.filter { typed.isEmpty() || named[it]?.contains(typed) == true }
    if (shown.isEmpty()) Text(stringResource(R.string.routines_pick_empty), color = PwnMuted)
    val others = otherWorkouts()
    val grouped = typed.isEmpty()
    // The group's card stands where the first of them stands in the catalogue, among the cardio.
    val groupAt = ACTIVITY_CATALOG.first { it.code in OTHER_WORKOUT_CODES }.code
    shown.forEach { spec ->
        when {
            grouped && spec.code in OTHER_WORKOUT_CODES -> if (spec.code == groupAt) {
                OtherWorkoutsCard(others, selected) { onPick(it) }
            }

            onOutdoors != null && spec.code in ON_FOOT_CODES -> OnFootCard(spec, onIndoors = {
                onPick(spec)
            }, onOutdoors)

            else -> ExercisePickCard(spec, spec.code == selected?.code) { onPick(spec) }
        }
    }
}

/**
 * The workouts of the clock as one card: interval training's drawing, the group's name and a few of its own;
 * a tap picks the one [selected] already is, or else the first by name.
 */
@Composable
private fun OtherWorkoutsCard(others: List<ActivitySpec>, selected: ActivitySpec?, onPick: (ActivitySpec) -> Unit) {
    val chosen = selected?.takeIf { it.code in OTHER_WORKOUT_CODES }
    PickCard(
        art = OTHER_WORKOUTS_ART,
        title = stringResource(R.string.routines_other_workouts),
        line = stringResource(R.string.routines_other_workouts_body),
        selected = chosen != null,
    ) { onPick(chosen ?: others.first()) }
}

/** A walk, a run or a hike, unfolding on a tap into where it is done: indoors, or outdoors on the walk page. */
@Composable
private fun OnFootCard(spec: ActivitySpec, onIndoors: () -> Unit, onOutdoors: () -> Unit) {
    var open by rememberSaveable(spec.code) { mutableStateOf(false) }
    Column {
        ExercisePickCard(spec, open, unfolds = true) { open = !open }
        InlineReveal(open) {
            Column(Modifier.padding(start = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabChoiceRow(
                    Icons.Outlined.Home,
                    stringResource(R.string.routines_indoors),
                    stringResource(R.string.routines_indoors_body),
                    onIndoors,
                )
                LabChoiceRow(
                    Icons.Outlined.Park,
                    stringResource(R.string.routines_outdoors),
                    stringResource(R.string.routines_outdoors_body),
                    onOutdoors,
                )
            }
        }
    }
}

/** A card of the catalogue: the drawing in its tile, the name, what it works, and the accent edge once chosen. */
@Composable
private fun ExercisePickCard(spec: ActivitySpec, selected: Boolean, unfolds: Boolean = false, onClick: () -> Unit) {
    // joinToString takes a plain function, which cannot call stringResource; map is inline.
    val worked = Muscle.entries.filter { it in spec.muscles }.map { stringResource(it.labelRes()) }
    PickCard(spec.art(), stringResource(spec.titleRes), worked.joinToString(" · "), selected, unfolds, onClick)
}

/**
 * The card itself: [art] in its tile, [title] over [line], and the accent edge once [selected]. A card
 * that [unfolds] into choices of its own is told to a screen reader as a fold, open while [selected],
 * rather than as one of the cards' radio buttons: it chooses nothing yet.
 */
@Composable
private fun PickCard(
    art: Int,
    title: String,
    line: String,
    selected: Boolean,
    unfolds: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(PwnCorners.large)
    val state = disclosureState(selected)
    Surface(
        Modifier.fillMaxWidth()
            .clip(shape)
            .then(
                if (unfolds) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                        .disclosureSemantics(selected, state) { onClick() }
                } else {
                    Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                },
            ),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(
                    PICK_ART_WIDTH,
                    PICK_ART_HEIGHT,
                ).clip(RoundedCornerShape(12.dp)).background(PwnIndigoSoft),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    themePainterResource(art),
                    null,
                    Modifier.size(PICK_ART_HEIGHT - 8.dp),
                    colorFilter = washTint(),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(line, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = PwnMuted)
        }
    }
}

/** The exercise's drawing across the page, on the wash its page and its session show it on. */
@Composable
private fun ExerciseHero(spec: ActivitySpec) {
    Box(
        Modifier.fillMaxWidth().height(
            EXERCISE_HERO_HEIGHT,
        ).clip(RoundedCornerShape(PwnCorners.large)).background(PwnIndigoSoft),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            themePainterResource(spec.heroArt()),
            null,
            Modifier.fillMaxHeight().padding(12.dp),
            colorFilter = washTint(),
        )
    }
}

/** "Number of sets": a minus, the count in large type and a plus, from one to [MAX_PLAN_SETS]. */
@Composable
internal fun SetCountCard(count: Int, onCount: (Int) -> Unit) {
    RoutineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PwnIconBubble(Icons.Outlined.Layers, PwnMuted, PwnIndigoSoft, null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.routines_sets_count),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.routines_sets_count_lead),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PwnMuted,
                    )
                }
            }
            CountStepper(count, 1..MAX_PLAN_SETS, onCount)
        }
    }
}

/** A minus, the count in large type and a plus, held to [range], for choosing a small number. */
@Composable
internal fun CountStepper(count: Int, range: IntRange, onCount: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { onCount(count - 1) },
            enabled = count > range.first,
            modifier = Modifier.size(COUNT_BUTTON_SIZE),
        ) { Icon(Icons.Outlined.Remove, stringResource(R.string.ruler_decrease)) }
        Text(
            count.toString(),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        FilledIconButton(
            onClick = { onCount(count + 1) },
            enabled = count < range.last,
            modifier = Modifier.size(COUNT_BUTTON_SIZE),
        ) { Icon(Icons.Outlined.Add, stringResource(R.string.ruler_increase)) }
    }
}

/**
 * One set on the last page: its number, a stopwatch that opens the wheels
 * for a timed set, and ⋯ to take it out; then its reps and weight, or its time in large type; then,
 * for every set but the last, the rest after it on a green strip that opens the same wheels.
 */
@Composable
private fun SetCard(
    at: Int,
    set: PlannedSet,
    spec: ActivitySpec,
    imperial: Boolean,
    last: Boolean,
    onChange: (PlannedSet) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val resources = LocalResources.current
    val counted = spec.input != ActivityInput.DURATION
    var timing by rememberSaveable { mutableStateOf(false) }
    var resting by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val name = stringResource(R.string.routines_set_number, at + 1)
    RoutineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SetBadge(at)
                Spacer(Modifier.width(10.dp))
                Text(
                    name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!counted) {
                    IconButton(onClick = { timing = true }) {
                        Icon(
                            Icons.Outlined.Timer,
                            "$name: ${stringResource(R.string.common_edit)}",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (onRemove != null) {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                "$name: ${stringResource(R.string.common_delete)}",
                                tint = PwnMuted,
                            )
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_delete)) },
                                onClick = {
                                    menu = false
                                    onRemove()
                                },
                            )
                        }
                    }
                }
            }
            if (counted) {
                SetFigures(set, spec, loadUnitRes(imperial), locked = false, compact = true, onChange = onChange)
            } else {
                Surface(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = stringResource(R.string.common_edit), role = Role.Button) {
                            timing =
                                true
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = PwnIndigoSoft,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            stringResource(R.string.editor_time),
                            style = MaterialTheme.typography.bodySmall,
                            color = PwnMuted,
                        )
                        Text(
                            durationText(resources, set.actualSeconds),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (!last) {
                Surface(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = stringResource(R.string.workout_rest_change), role = Role.Button) {
                            resting = true
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = PwnGreenSoft,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.LocalCafe, null, Modifier.size(18.dp), tint = PwnGreen)
                        Spacer(Modifier.width(8.dp))
                        // As a session's rest strip sets it ([RestStrip]): by its colour and a space, not a colon.
                        Text(
                            stringResource(R.string.workout_rest),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PwnGreen,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(durationText(resources, set.restSeconds), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if (timing) {
        DurationPickerDialog(set.actualSeconds, { timing = false }) {
            onChange(set.copy(actualSeconds = it))
            timing = false
        }
    }
    if (resting) {
        DurationPickerDialog(set.restSeconds, { resting = false }) {
            onChange(set.copy(restSeconds = it))
            resting = false
        }
    }
}

/** The steps through a rotation and a process death, their rows written as a backup writes them. */
internal val BLOCKS_SAVER = Saver<List<RoutineBlock>, String>(
    save = { blocks ->
        JSONArray(
            blocks.map { block ->
                JSONObject()
                    .put("superset", block.superset)
                    .put("rest", block.restSeconds)
                    .put("rows", JSONArray(block.exercises.map(RoutineExercise::toJson)))
            },
        ).toString()
    },
    restore = { saved ->
        val steps = JSONArray(saved)
        List(steps.length()) { index ->
            val block = steps.getJSONObject(index)
            val rows = block.getJSONArray("rows")
            RoutineBlock(
                exercises = List(rows.length()) { rows.getJSONObject(it).toRoutineExercise() },
                superset = block.getBoolean("superset"),
                restSeconds = block.getInt("rest"),
            )
        }
    },
)

/** [placing]'s step for an exercise that is a step of its own. */
internal const val NEW_STEP = -1

/** The exercise page is shut. */
private const val NO_PAGE = -2

private const val ROUTINE_NAME_MAX = 60
private const val ROUTINE_DESCRIPTION_MAX = 200
private const val ROUTINE_PHOTO_DIR = "routines"

/** The camera's shot for a template's picture, in the cache until read; [sweepLostCaptures] takes one left there. */
internal const val ROUTINE_CAPTURE_FILE = "routine-capture.jpg"

/** The picture waiting for its template to be saved; [sweepLostCaptures] takes one no flow came back for. */
internal const val ROUTINE_PENDING_FILE = "routine-photo.jpg"
private const val ROUTINE_IMPORT_FILE = "routine-import.jpg"

/** The mark a template file carries, and its shape's version: a file without it is not one. */
private const val ROUTINE_FILE_MARK = "pwnHealthWorkoutTemplate"
private const val ROUTINE_FILE_VERSION = 1

/** A template with its picture is well under a megabyte; a file past this is not one. */
private const val ROUTINE_FILE_MAX_BYTES = 4 * 1024 * 1024

/** What the picker offers: a template file as a messenger saves it, as JSON or as anything at all. */
private val ROUTINE_FILE_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

/** The form's own bounds: four digits of sets or reps, a tonne of load, the rest wheels' 99:59. */
private const val MAX_COUNT = 9_999
private const val MAX_LOAD_KG = 1_000.0
private const val MAX_REST_SECONDS = MAX_PICKED_SECONDS

/** The most sets an exercise of a template is planned in. */
internal const val MAX_PLAN_SETS = 9

/** The most exercises a template file brings: several times the longest session anybody trains. */
internal const val MAX_FILE_EXERCISES = 100

/** What [routineName] turns into one space: every run of spaces, line breaks and control characters. */
private val NAME_BREAKS = Regex("[\\p{Z}\\p{Cc}]+")

/** What a new exercise starts with, before its count is touched. */
private const val DEFAULT_PLAN_SETS = 3

/** The rest after a timed set before the next, until it is changed. */
private const val TIMED_REST_SECONDS = 30

/** What a new timed set holds for, until its wheels are turned. */
private const val DEFAULT_SET_SECONDS = 45

/** A rep at a steady tempo: two seconds down, one up. */
private const val SECONDS_PER_REP = 3

/** How many muscle groups a template's chips name. */
private const val TOP_MUSCLES = 3

/** The longest short day name a circle holds; longer ones are said in one letter. */
private const val DAY_LABEL_MAX = 4

/** How far a page slides as it comes and goes: a quarter of the width, the rest is the fade. */
private const val SLIDE_PART = 4

/** The exercise page's picture sizes and its count buttons. */
private val PICK_ART_WIDTH = 96.dp
private val PICK_ART_HEIGHT = 72.dp
private val EXERCISE_HERO_HEIGHT = 200.dp
private val COUNT_BUTTON_SIZE = 64.dp

/** The outlined field's own height, so the square stands level with the box it sits beside. */
private val PHOTO_FIELD_SIZE = 56.dp
private val DONE_PICTURE_SIZE = 64.dp
