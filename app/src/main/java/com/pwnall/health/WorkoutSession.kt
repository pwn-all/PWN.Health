package com.pwnall.health

import android.content.res.Resources
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * One planned or completed set of a workout session: a person's target reps/load (or, for a timed
 * exercise, nothing typed at all — the hold itself is the target), and once it runs, what actually
 * happened.
 */
internal data class PlannedSet(
    val reps: String = "",
    val load: String = "",
    val restSeconds: Int = DEFAULT_REST_SECONDS,
    val completed: Boolean = false,
    val actualSeconds: Int = 0,
    val actualRestSeconds: Int = 0,
    /**
     * This set is the pair's second exercise, and the set before it served no rest. That absence is
     * the whole of what a superset is; nothing else in the plan marks one.
     */
    val second: Boolean = false,
    /**
     * Reps left in the tank when the set stopped — null when nobody said, which is most sets. Only
     * ever answered on the rest card, while the set is still fresh enough to answer honestly.
     */
    val rir: Int? = null,
    val exerciseCode: String? = null,
    /** The floors of a climb the phone counted, beside its steps in [reps]; none for anything else. */
    val floors: Int = 0,
)

/**
 * Whether the set at [index] hands straight over to a partner instead of resting after itself.
 *
 * The gap between the two halves of a round is not a short rest; there is no gap. Every place that
 * draws, plans or serves a rest asks this first.
 */
internal fun handsOver(plan: List<PlannedSet>, index: Int): Boolean =
    !plan[index].second && plan.getOrNull(index + 1)?.second == true

/**
 * The same plan with a partner leg behind every set, and the same plan with those legs taken away.
 *
 * Pairing and unpairing are the only two shapes a plan changes into, and both must move two rows at
 * a time or the `second` flags, the badges and the two-row save drift apart. Named functions rather
 * than two expressions inlined in an effect, because that parity is the thing worth a test.
 *
 * The partner's row starts empty: of the first half's it keeps only the rest, which the round now
 * serves after itself. Copied whole it would take the first half's typed hold as a head start of its
 * own.
 */
internal fun List<PlannedSet>.paired(): List<PlannedSet> =
    unpaired().flatMap { listOf(it, PlannedSet(restSeconds = it.restSeconds, second = true)) }

internal fun List<PlannedSet>.unpaired(): List<PlannedSet> = filterNot { it.second }

/** One set the rest card is asking about, and the way back to the plan row it belongs to. */
internal data class EffortAsk(val label: String?, val rir: Int?, val onRir: (Int?) -> Unit)

/**
 * How many reps in reserve the chips offer. RIR 0..4 is RPE 10..6, which is the range the RPE
 * scale is actually used over; below that a set is a warm-up and nobody rates it.
 */
internal val RIR_CHOICES = 0..4

/** The other end of the same measurement: nothing left is RPE 10, two in reserve is RPE 8. */
internal fun Int.rpeFromRir(): Int = RPE_TOP - this

private const val RPE_TOP = 10

internal const val DEFAULT_REST_SECONDS = 60

/**
 * The rest a session writes for [elapsedSeconds] on the rest card's clock, never more than
 * [MAX_COUNTED_REST_SECONDS] — the one ceiling Freestyle and a template's session keep too: a phone
 * left on the card overnight and ended in the morning would write the night into the row's minutes
 * and its energy. Every rest of the session passes through here, the one it is ended on and the ones between
 * its sets alike.
 */
internal fun countedRest(elapsedSeconds: Int): Int = elapsedSeconds.coerceIn(0, MAX_COUNTED_REST_SECONDS)

/**
 * Where a session's clocks stand: the set under way ([activeIndex], -1 for none), whether its rest
 * is, and the elapsedRealtime marks and seconds the set's and the rest's clocks run by. The session
 * saves each of them on its own; these are the moves between them, pure over a `now` handed in, so
 * that the hand-over, the rest's start and a second Stop can be walked by a test.
 */
internal data class SessionClocks(
    val activeIndex: Int = -1,
    val resting: Boolean = false,
    val runStartedAt: Long = 0L,
    val runFrom: Int = 0,
    val runElapsed: Int = 0,
    val restStartedAt: Long = 0L,
    val restElapsed: Int = 0,
)

private fun secondsBetween(from: Long, to: Long): Int = ((to - from) / 1_000L).toInt()

/**
 * The set at [index] under way since [at], counting on from [from]: the seconds typed into a hold,
 * nought for anything else. [at] is now for a Start, and for a partner's set of reps the moment the
 * first half stopped, as a rest counts from its set's Stop: the seconds spent answering for the first
 * half are the hand-over, and with no rest between the halves only the partner's set can hold them.
 */
internal fun SessionClocks.started(index: Int, from: Int, at: Long, now: Long): SessionClocks = copy(
    activeIndex = index,
    resting = false,
    runFrom = from,
    runElapsed = from + secondsBetween(at, now),
    runStartedAt = at,
)

/** The plan and the clocks once a set has stopped. */
internal data class SetStopped(val plan: List<PlannedSet>, val clocks: SessionClocks)

/**
 * The set at [index] stopped at [now], or, once its reps were asked about, at [confirmedAt] — the
 * "Complete set" the question followed, where its time and its rest begin. A first half hands
 * straight over to its partner; anything else rests.
 *
 * Null for a set already stopped. A second touch of Yes or Stop can land before the first one's
 * recomposition takes the button away, and by then the clocks are the partner's set's or the
 * rest's: stopping again would write those over this set's time and start the rest from nought.
 */
internal fun SessionClocks.stopped(
    plan: List<PlannedSet>,
    index: Int,
    confirmedAt: Long?,
    now: Long,
    spec: ActivitySpec,
    partner: ActivitySpec?,
): SetStopped? {
    if (plan[index].completed) return null
    val endedAt = confirmedAt ?: now
    val elapsed = if (confirmedAt != null) runElapsed else runFrom + secondsBetween(runStartedAt, endedAt)
    val done = plan.toMutableList().also { it[index] = it[index].copy(completed = true, actualSeconds = elapsed) }
    // The round does not stop here when a partner set follows: putting one bar down and picking the
    // next up is the whole point, so no rest card is allowed to appear between the halves.
    val next = if (handsOver(done, index)) {
        // A hold's clock is the hold: started at the Stop, it would read the answering seconds as held.
        val hold = done[index + 1].exercise(spec, partner).input == ActivityInput.DURATION
        started(index + 1, from = if (hold) done[index + 1].actualSeconds else 0, at = if (hold) now else endedAt, now)
    } else {
        copy(
            resting = true,
            runElapsed = elapsed,
            restStartedAt = endedAt,
            restElapsed = countedRest(secondsBetween(endedAt, now)),
        )
    }
    return SetStopped(done, next)
}

/**
 * Whether the session holds something its form has not been handed, which is what leaving the page
 * now would lose.
 *
 * On a new entry that is a session under way ([running]), a plan that is no longer the one empty
 * set, or a partner chosen ([paired]): nothing of it is written before its Save. A saved row
 * ([savedAs], the plan it opened with) has all of that from the start, and what it can lose is a
 * correction — a set's figures put right, a set added, run or taken out.
 */
internal fun sessionHoldsDraft(
    plan: List<PlannedSet>,
    running: Boolean,
    paired: Boolean,
    savedAs: List<PlannedSet>?,
): Boolean = when {
    running -> true
    savedAs == null -> plan != listOf(PlannedSet()) || paired
    else -> plan != savedAs
}

/**
 * Whether a saved workout's page holds a correction its row does not: one still in the session's own
 * plan ([draftChanged], see [sessionHoldsDraft]), or one End has handed the form ([formSets]) and the
 * summary's Done has not written over the row's ([rowSets]). A saved session has no Save of its own —
 * End and that Done are how a correction is kept — so until then it is in the page and nowhere else,
 * and leaving would drop it without a word. Read as sets and not as text: End hands back the same
 * sets in its own spelling. And without the superset flag: a saved row's plan drops it as it opens
 * ([plannedSetsFor]), so End alone hands the second half of a pair back unflagged, and counting the
 * flag would ask about a correction nobody had made.
 */
internal fun savedWorkoutCorrected(draftChanged: Boolean, formSets: String, rowSets: String?): Boolean =
    draftChanged || unflaggedSets(formSets) != unflaggedSets(rowSets)

private fun unflaggedSets(json: String?): List<PlannedSet> = decodeSetsFromJson(json).map { it.copy(second = false) }

/**
 * Asked before a saved workout's page is left with a correction on it ([savedWorkoutCorrected]). It
 * names the two buttons that keep one, because the page has no Save to point at; [timed] is whether
 * its End is a hold's.
 */
@Composable
internal fun WorkoutCorrectionLeaveDialog(timed: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workout_correction_leave_title)) },
        text = {
            Text(
                stringResource(
                    R.string.workout_correction_leave_body,
                    stringResource(if (timed) R.string.workout_end else R.string.workout_end_workout),
                    stringResource(R.string.common_done),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.workout_discard_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** As far as the duration wheels turn where a caller names no ceiling: 99:59, what two digits hold. */
internal const val MAX_PICKED_SECONDS = 99 * 60 + 59

/**
 * What two wheels standing at [minutes] and [remainder] come to, and never more than [ceiling]: the
 * minutes wheel ends on the ceiling's own minute, and the seconds wheel beside it still turns to 59.
 */
internal fun pickedSeconds(minutes: Int, remainder: Int, ceiling: Int): Int =
    (minutes * 60 + remainder).coerceAtMost(ceiling)

/**
 * The flat columns a session's completed sets add up to — the same figures typing them by hand
 * always produced.
 *
 * [load] is in the units the set boxes are labelled with, not kilograms: a set holds the text
 * somebody typed under a box that says "lb" on an imperial profile, and this only averages it.
 * The form converts to kilograms once, on save.
 */
internal data class WorkoutAggregate(
    val durationMinutes: Double,
    val sets: Int,
    val reps: Int,
    val load: Double,
)

internal fun PlannedSet.exercise(spec: ActivitySpec, partner: ActivitySpec?): ActivitySpec =
    ACTIVITY_CATALOG.firstOrNull { it.code == exerciseCode } ?: if (second) partner ?: spec else spec

internal fun List<PlannedSet>.exerciseSetIndex(index: Int, spec: ActivitySpec, partner: ActivitySpec?): Int =
    take(index).count { it.exercise(spec, partner).code == this[index].exercise(spec, partner).code }

/** The finished sets, and one of [code] to do next — resting as long as the sets before it did. */
internal fun List<PlannedSet>.continueWithExercise(code: String): List<PlannedSet> {
    val done = filter { it.completed }
    return done + PlannedSet(exerciseCode = code, restSeconds = done.lastOrNull()?.restSeconds ?: DEFAULT_REST_SECONDS)
}

/**
 * The plan with the finished set at [index] taken out, or null where that set has to stay.
 *
 * A round goes whole — a paired plan only ever changes two rows at a time — and with a set goes the
 * rest served after it. The last finished set of [spec] stays: the row this form saves is that
 * exercise's own, and a session left with none of its sets is not a session corrected, it is one to
 * discard.
 */
internal fun List<PlannedSet>.withoutSet(index: Int, spec: ActivitySpec, partner: ActivitySpec?): List<PlannedSet>? {
    val first = if (index > 0 && handsOver(this, index - 1)) index - 1 else index
    val last = if (handsOver(this, first)) first + 1 else first
    val kept = filterIndexed { at, _ -> at !in first..last }
    return kept.takeIf { sets -> sets.any { it.completed && it.exercise(spec, partner).code == spec.code } }
}

/** Keep consecutive exercises in their performed order, including returning to an earlier one. */
internal fun List<PlannedSet>.completedExerciseGroups(
    spec: ActivitySpec,
    partner: ActivitySpec?,
): List<Pair<ActivitySpec, List<PlannedSet>>> {
    val blocks = mutableListOf<MutableList<PlannedSet>>()
    filter { it.completed }.forEach { set ->
        if (blocks.isEmpty() || blocks.last().last().exerciseCode != set.exerciseCode) {
            blocks.add(mutableListOf())
        }
        blocks.last().add(set)
    }
    return blocks.flatMap { block -> block.groupBy { it.exercise(spec, partner) }.toList() }
}

internal fun List<PlannedSet>.aggregate(): WorkoutAggregate {
    val done = filter { it.completed }
    val loads = done.mapNotNull { it.load.number().takeIf { kg -> kg > 0 } }
    return WorkoutAggregate(
        durationMinutes = done.sumOf { it.actualSeconds + it.actualRestSeconds } / 60.0,
        sets = done.size,
        reps = done.sumOf { it.reps.intNumber() },
        load = if (loads.isEmpty()) 0.0 else loads.average(),
    )
}

/**
 * The per-set breakdown a session produces, folded into [JournalEntry.measurementPayloadJson] —
 * the column already exists for exactly this, a free-form object beside the row it belongs to.
 * Empty when no set was completed, so a session abandoned before its first "Complete set" leaves
 * nothing behind and the row falls back to whatever aggregate columns are otherwise set.
 *
 * [loadUnitFactor] is what a typed load is divided by to reach kilograms — the editor's own figure,
 * 1.0 on a metric profile. A payload holds kilograms whoever wrote it, as the row's own `loadKg`
 * column does, and says nothing more about its unit: if an unmarked load meant "whatever the
 * profile was showing", a profile switched from kilograms to pounds read 100 kg as "100 lb" and
 * saved it again as 45.
 *
 * A rest is written where a set follows it. [preserveTrailingRest] also keeps the one served after
 * the last set, when there was one: a row whose minutes count that rest needs it in the payload too,
 * or the row read back is shorter than the row saved.
 */
internal fun List<PlannedSet>.encodeToJson(
    supersetWith: String? = null,
    loadUnitFactor: Double = 1.0,
    preserveTrailingRest: Boolean = false,
): String {
    val done = filter { it.completed }
    if (done.isEmpty()) return ""
    val array = JSONArray()
    done.forEachIndexed { i, set ->
        val obj = JSONObject()
        set.reps.intNumber().takeIf { it > 0 }?.let { obj.put("reps", it) }
        set.load.number().takeIf { it > 0 }?.let { obj.put("loadKg", it / loadUnitFactor) }
        obj.put("seconds", set.actualSeconds)
        if (set.floors > 0) obj.put("floors", set.floors)
        // Both keys are written only when they hold something. A workout with no partner and no
        // effort rating has to keep encoding to the same bytes: journalKey() hashes this
        // string as a row's cross-install identity, so a payload that merely reformats itself
        // restores every ACTIVITY row ever backed up as a second copy of itself.
        if (set.second) obj.put("second", true)
        set.rir?.let { obj.put("rir", it) }
        set.exerciseCode?.let { obj.put("exerciseCode", it) }
        if (i != done.lastIndex || (preserveTrailingRest && set.actualRestSeconds > 0)) {
            obj.put("restSeconds", set.actualRestSeconds)
        }
        array.put(obj)
    }
    val payload = JSONObject().put("sets", array)
    // The tie between the pair's two journal rows, and the only thing that outlives the session:
    // each row names the other's exercise, so the day list can say what it was paired with. The tie
    // is that exercise's code and not its row, so a row goes on saying so after its partner's row
    // is deleted; only a code the catalogue no longer knows says nothing.
    supersetWith?.let { payload.put("supersetWith", it) }
    return payload.toString()
}

/**
 * A plan across the activity's recreation: each set flattened to its fields, the shape a Bundle
 * takes. Not [encodeToJson], which keeps only the completed sets and only what a row stores — here
 * the sets still to come, the reps typed into them and the rest each will take are the point.
 */
internal val plannedSetsSaver: Saver<List<PlannedSet>, Any> = listSaver(
    save = { plan ->
        plan.flatMap { set ->
            listOf(
                set.reps,
                set.load,
                set.restSeconds,
                set.completed,
                set.actualSeconds,
                set.actualRestSeconds,
                set.second,
                set.rir ?: NO_RIR,
                set.exerciseCode.orEmpty(),
                set.floors,
            )
        }
    },
    restore = { flat ->
        flat.chunked(PLANNED_SET_FIELDS).map { fields ->
            val next = fields.iterator()
            PlannedSet(
                reps = next.next() as String,
                load = next.next() as String,
                restSeconds = next.next() as Int,
                completed = next.next() as Boolean,
                actualSeconds = next.next() as Int,
                actualRestSeconds = next.next() as Int,
                second = next.next() as Boolean,
                rir = (next.next() as Int).takeIf { it != NO_RIR },
                exerciseCode = (next.next() as String).ifBlank { null },
                floors = next.next() as Int,
            )
        }
    },
)

private const val PLANNED_SET_FIELDS = 10

/** A Bundle list holds no nulls comfortably; an unanswered effort rating travels as this. */
private const val NO_RIR = -1

/** The exercise this row was supersetted with, when it was one. */
internal fun supersetPartnerCode(json: String?): String? = runCatching {
    if (json.isNullOrBlank()) return@runCatching null
    JSONObject(json).optString("supersetWith").ifBlank { null }
}.getOrNull()

/**
 * The sets a row stored, with each load back in the reader's unit: the payload holds kilograms and
 * is multiplied out by [loadUnitFactor], whichever profile wrote it. Rounded to a hundredth before
 * it becomes text, so 100 lb stored as 45.359237 kg comes back as "100" and not "100.0".
 *
 * The text is [pretty]'s, which keeps one decimal: the same payload reads "45.4" on a metric
 * profile, and 21.25 kg comes back as "21.3". It is what a set box shows; whatever compares two
 * weights reads the number the payload stores, as [strengthSetsOf] does.
 */
internal fun decodeSetsFromJson(json: String?, loadUnitFactor: Double = 1.0): List<PlannedSet> = runCatching {
    if (json.isNullOrBlank()) return@runCatching emptyList()
    val root = JSONObject(json)
    val array = root.optJSONArray("sets") ?: return@runCatching emptyList()
    (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        PlannedSet(
            reps = if (obj.has("reps")) obj.getInt("reps").toString() else "",
            load = if (obj.has("loadKg")) {
                (obj.getDouble("loadKg") * loadUnitFactor).roundToHundredth().pretty()
            } else {
                ""
            },
            actualSeconds = obj.optInt("seconds"),
            actualRestSeconds = obj.optInt("restSeconds"),
            second = obj.optBoolean("second"),
            rir = if (obj.has("rir")) obj.getInt("rir") else null,
            exerciseCode = obj.optString("exerciseCode").ifBlank { null },
            floors = obj.optInt("floors"),
            completed = true,
        )
    }
}.getOrDefault(emptyList())

/**
 * The plan a saved row re-opens with.
 *
 * A row written as the second half of a superset carries `"second": true` on every set it holds —
 * that is what [encodeToJson] was handed — and the pairing itself is not restored with it: which
 * two exercises ran together is a property of the session, not of either row. Left in place those
 * flags describe a first leg that is not in the plan, and every piece of the session that asks
 * "which half is this" answers wrongly: the badges, the two-row save, and `startNextSet`, which
 * would go looking for a first leg to copy the next set from, find none, and take the process down
 * with it on the "New set" it is drawn under.
 *
 * With no partner the halves cannot mean anything, so the row is read as the plain plan it now is.
 */
internal fun plannedSetsFor(json: String, loadUnitFactor: Double, paired: Boolean): List<PlannedSet> {
    val decoded = decodeSetsFromJson(json, loadUnitFactor)
    val plan = if (paired) decoded else decoded.map { it.copy(second = false) }
    return plan.ifEmpty { listOf(PlannedSet()) }
}

internal fun Double.roundToHundredth(): Double = Math.round(this * 100.0) / 100.0

/** The full HH:MM:SS, for the one number a running hold is read by. */
private fun Int.clockText(): String =
    String.format(Locale.ROOT, "%02d:%02d:%02d", this / 3_600, this % 3_600 / 60, this % 60)

internal fun Int.elapsedClockText(): String {
    val h = this / 3_600
    val m = this % 3_600 / 60
    val s = this % 60
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", m, s)
    }
}

/**
 * The exercise's own place in the form, directly under the muscle map — planning the sets, running
 * them, resting between them, all as one page rather than a window that opens over it.
 * [workoutSetsJson] seeds the plan when reopening a session that already ran, and [seed] — a chat's
 * plan — one that has not; [onFinished] hands the completed sets up once the workout ends.
 *
 * [partner] is the pair's second exercise when this session is a superset. It changes one thing in
 * the machine below — a stopped first set hands straight over instead of resting — and everything
 * else follows from the [PlannedSet.second] flags that pairing writes into the plan.
 *
 * [allowAdditionalExercises] is false on a saved row, which stays the one row it is: neither another
 * exercise nor a superset partner is offered there, because each is a journal row of its own and
 * only a new entry writes one.
 */
@Composable
internal fun WorkoutSessionInline(
    spec: ActivitySpec,
    loadUnitRes: Int,
    loadUnitFactor: Double,
    workoutSetsJson: String,
    partner: ActivitySpec?,
    onPartner: () -> Unit,
    onUnpair: () -> Unit,
    onFinished: (List<PlannedSet>) -> Unit,
    onDraftChanged: (Boolean) -> Unit,
    allowAdditionalExercises: Boolean,
    /** The saved sessions, offered beside Start; no callback, no door — a saved row is corrected, not restarted. */
    onTemplates: (() -> Unit)? = null,
    /** The sets a chat's plan named, for a plan that opens on no saved sets — see [chatPlan]. */
    seed: List<PlannedSet> = emptyList(),
) {
    // Saved, all of it: the plan with what was typed and what was done, which set is running, and
    // the clocks it runs by. A phone turned on the bench recreates the activity, and a session held
    // in plain memory would come back as an empty plan with every completed set gone. The clocks are
    // elapsedRealtime, so a restored one simply keeps counting.
    var plan by rememberSaveable(workoutSetsJson, stateSaver = plannedSetsSaver) {
        mutableStateOf(
            seed.takeIf { workoutSetsJson.isBlank() && it.isNotEmpty() }
                ?: plannedSetsFor(workoutSetsJson, loadUnitFactor, paired = partner != null),
        )
    }
    // -1: nothing is running — either the workout has yet to begin, or it is paused between sets
    // after an End. Once started, the active index runs, then rests, until either the next set
    // starts (advancing it) or the workout ends.
    var activeIndex by rememberSaveable { mutableIntStateOf(-1) }
    var resting by rememberSaveable { mutableStateOf(false) }
    var runStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var runFrom by rememberSaveable { mutableIntStateOf(0) }
    var runElapsed by rememberSaveable { mutableIntStateOf(0) }
    var restStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var restElapsed by rememberSaveable { mutableIntStateOf(0) }
    var confirmingIndex by rememberSaveable { mutableIntStateOf(-1) }
    var stoppedAt by rememberSaveable { mutableLongStateOf(0L) }

    // A timed set is its own clock: the fields are the set, so starting counts up from whatever was
    // typed rather than from nothing. Everything else counts the time under load from zero.
    //
    // Which exercise a row belongs to is a property of the row, not of the session: pair a plank
    // with curls and one leg wants a clock while the other wants rep boxes. Every per-row call site
    // below asks these two rather than reading the session's own spec.
    fun specAt(index: Int): ActivitySpec = plan[index].exercise(spec, partner)
    fun setIndex(index: Int): Int = plan.exerciseSetIndex(index, spec, partner)
    fun timedAt(index: Int): Boolean = specAt(index).input == ActivityInput.DURATION

    // Whether there is a rest after this set worth naming in the plan: a hold has none, and neither
    // does the first half of a round, which hands straight over instead.
    fun restFollows(index: Int): Boolean = !timedAt(index) && !handsOver(plan, index)
    var addingExercise by rememberSaveable { mutableStateOf(false) }
    // The plan a saved row opened with, to tell a correction from the row as it stands; null on a
    // new entry, where anything at all is a draft — see [sessionHoldsDraft].
    val savedAs = remember(workoutSetsJson, loadUnitFactor, partner != null) {
        plannedSetsFor(workoutSetsJson, loadUnitFactor, paired = partner != null)
            .takeUnless { allowAdditionalExercises }
    }
    SideEffect { onDraftChanged(sessionHoldsDraft(plan, activeIndex >= 0, partner != null, savedAs)) }
    // The plan's shape follows the pairing — one row per exercise per round — but only when the
    // pairing actually changes. Saved rather than remembered, because coming back from process
    // death with the partner still chosen must not double the plan a second time.
    var pairedTo by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(partner?.code) {
        val code = partner?.code
        if (code == pairedTo) return@LaunchedEffect
        // Sets that have already run are facts; a pairing decision taken after them changes only
        // what happens next, and the paused session is where that lands.
        if (plan.none { it.completed }) {
            plan = if (code == null) plan.unpaired() else plan.paired()
        }
        pairedTo = code
    }

    LaunchedEffect(activeIndex, resting, confirmingIndex) {
        if (activeIndex < 0 || confirmingIndex >= 0) return@LaunchedEffect
        val at = if (resting) restStartedAt else runStartedAt
        while (true) {
            delay(200)
            val since = ((SystemClock.elapsedRealtime() - at) / 1_000L).toInt()
            // The rest clock stops where the rest stops counting: what it shows is what is written.
            if (resting) restElapsed = countedRest(since) else runElapsed = runFrom + since
        }
    }

    // A set or a rest runs with the phone on a bench and nobody touching it for longer than most
    // screen timeouts, as in Freestyle and a template's player, which keep the screen on too. Not
    // past the longest rest that counts: a phone left on the rest card does not stay lit all night.
    val awake = activeIndex >= 0 && !(resting && restElapsed >= MAX_COUNTED_REST_SECONDS)
    val view = LocalView.current
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    // The clocks as one value for the moves in [SessionClocks], and back into the saved states.
    fun clocks() = SessionClocks(activeIndex, resting, runStartedAt, runFrom, runElapsed, restStartedAt, restElapsed)
    fun follow(next: SessionClocks) {
        activeIndex = next.activeIndex
        resting = next.resting
        runStartedAt = next.runStartedAt
        runFrom = next.runFrom
        runElapsed = next.runElapsed
        restStartedAt = next.restStartedAt
        restElapsed = next.restElapsed
    }

    fun startSet(index: Int) {
        val now = SystemClock.elapsedRealtime()
        follow(clocks().started(index, from = if (timedAt(index)) plan[index].actualSeconds else 0, at = now, now))
    }

    fun stopSet(index: Int) {
        val confirmedAt = stoppedAt.takeIf { confirmingIndex == index }
        val stop = clocks().stopped(plan, index, confirmedAt, SystemClock.elapsedRealtime(), spec, partner) ?: return
        confirmingIndex = -1
        plan = stop.plan
        follow(stop.clocks)
    }

    // Picking the workout back up: the next set is whichever one was planned and never run, and
    // once the plan is spent it is a fresh set worth what the last one was — the same inheritance
    // the resting card makes when it adds another.
    fun startNextSet() {
        val last = plan.last()
        if (last.exerciseCode != null && last.completed) {
            plan = plan + PlannedSet(
                reps = last.reps,
                load = last.load,
                restSeconds = last.restSeconds,
                exerciseCode = last.exerciseCode,
            )
            startSet(plan.lastIndex)
            return
        }
        val next = plan.indexOfFirst { !it.completed }
        if (next >= 0) {
            startSet(next)
            return
        }
        // A paired plan is only ever added to or taken from two rows at a time. One row out of step
        // and the second flags, the badges and the two-row save all drift apart from each other.
        //
        // lastOrNull rather than last: [plannedSetsFor] is what keeps a plan of nothing but second
        // halves from reaching here, and this is a click handler — the one place where being wrong
        // takes the whole process with it rather than drawing something odd.
        val legB = plan.lastOrNull { it.second }
        val legA = plan.lastOrNull { !it.second } ?: plan.last()
        plan = plan + PlannedSet(reps = legA.reps, load = legA.load, restSeconds = legA.restSeconds) +
            listOfNotNull(
                legB?.let {
                    PlannedSet(reps = it.reps, load = it.load, restSeconds = it.restSeconds, second = true)
                },
            )
        startSet(plan.indexOfLast { !it.second })
    }

    // Ending is one act with two doors into it — the resting card's End, and the End a paused
    // session offers — so whoever ends says which sets it ends with, rather than this reaching for
    // a rest clock that stopped long ago.
    fun finish(sets: List<PlannedSet>) {
        onFinished(sets)
        // This view stays mounted — nothing here unmounts it the way closing a dialog would — so
        // the run/rest state has to be put back by hand or the resting card keeps ticking under its
        // own finished session.
        activeIndex = -1
        resting = false
    }

    if (addingExercise) {
        ActivityPicker(
            selected = specAt(activeIndex.coerceAtLeast(0)),
            catalogue = PICKABLE_ACTIVITIES.filter { !it.counted },
            onDismiss = { addingExercise = false },
            onPick = { chosen ->
                if (resting && activeIndex >= 0) {
                    plan = plan.toMutableList().also {
                        it[activeIndex] = it[activeIndex].copy(actualRestSeconds = restElapsed)
                    }
                }
                plan = plan.continueWithExercise(chosen.code)
                addingExercise = false
                startSet(plan.lastIndex)
            },
        )
    }
    Column {
        when {
            activeIndex >= 0 -> {
                plan.forEachIndexed { i, set ->
                    when {
                        // A set already behind the pointer, or the one that has just been stopped.
                        i < activeIndex || (i == activeIndex && set.completed) ->
                            CompletedSetRow(setIndex(i), set, specAt(i), loadUnitRes)

                        i == activeIndex -> RunningSetCard(
                            index = setIndex(i),
                            set = set,
                            spec = specAt(i),
                            loadUnitRes = loadUnitRes,
                            timed = timedAt(i),
                            elapsed = runElapsed,
                            // Said before the bar goes down rather than after: the button that ends
                            // this set names the exercise that starts in the same breath, so nobody
                            // stops looking for a rest that is not coming.
                            nextLabel = partner
                                ?.takeIf { !set.second && plan.getOrNull(i + 1)?.second == true }
                                ?.let { stringResource(it.titleRes) },
                            confirming = confirmingIndex == i,
                            onChange = { updated ->
                                plan = plan.toMutableList().also { it[i] = updated }
                            },
                            onStop = {
                                if (timedAt(i)) {
                                    stopSet(i)
                                } else {
                                    stoppedAt = SystemClock.elapsedRealtime()
                                    runElapsed = runFrom + ((stoppedAt - runStartedAt) / 1_000L).toInt()
                                    confirmingIndex = i
                                }
                            },
                        )

                        else -> UpcomingSetRow(setIndex(i), set, specAt(i), loadUnitRes, timedAt(i))
                    }
                    if (confirmingIndex == i) {
                        Spacer(Modifier.height(10.dp))
                        WorkoutSetOutcome(
                            index = i,
                            reps = set.reps,
                            onConfirm = { actual ->
                                plan = plan.toMutableList().also { it[i] = it[i].copy(reps = actual) }
                                stopSet(i)
                            },
                        )
                    }
                    if (i == activeIndex && resting) {
                        Spacer(Modifier.height(10.dp))
                        RestingCard(
                            elapsed = restElapsed,
                            plannedSeconds = set.restSeconds,
                            timed = timedAt(i),
                            paired = partner != null && set.exerciseCode == null,
                            // The round that just ended, not the set: leg A's completion shows no
                            // rest card at all, so asking only about the set in hand would never
                            // ask about the first half of a pair.
                            efforts = ((if (set.second) i - 1 else i)..i)
                                .filter { !timedAt(it) }
                                .map { at ->
                                    EffortAsk(
                                        label = partner?.let { stringResource(specAt(at).titleRes) },
                                        rir = plan[at].rir,
                                        onRir = { chosen ->
                                            plan = plan.toMutableList()
                                                .also { it[at] = it[at].copy(rir = chosen) }
                                        },
                                    )
                                },
                            onAddSet = {
                                plan = plan.toMutableList()
                                    .also { it[i] = it[i].copy(actualRestSeconds = restElapsed) }
                                startNextSet()
                            },
                            onAddExercise = if (allowAdditionalExercises) ({ addingExercise = true }) else null,
                            onEnd = {
                                // The rest just served is the last thing to happen to this set and
                                // part of the session, as Freestyle's last rest is: the minutes
                                // the form is handed count it, up to the ceiling every rest has
                                // ([countedRest]). Whoever encodes these sets keeps it
                                // (preserveTrailingRest), or the summary and the row opened again
                                // measure a shorter session than the one the journal is given.
                                finish(
                                    plan.toMutableList()
                                        .also { it[i] = it[i].copy(actualRestSeconds = restElapsed) },
                                )
                            },
                        )
                    } else if (i == activeIndex && !set.completed && restFollows(i)) {
                        // The rest that is still ahead of this set, named so the plan stays readable
                        // while the set it follows is running. A set that hands straight over to its
                        // partner has no rest after it to name. Its length can still be changed
                        // here, and the sets added after this one inherit it.
                        Spacer(Modifier.height(10.dp))
                        RestPlanRow(
                            seconds = set.restSeconds,
                            done = false,
                            onEdit = { seconds ->
                                plan = plan.toMutableList().also { it[i] = it[i].copy(restSeconds = seconds) }
                            },
                            maxSeconds = MAX_COUNTED_REST_SECONDS,
                        )
                    } else if (i < activeIndex && !handsOver(plan, i)) {
                        Spacer(Modifier.height(10.dp))
                        RestPlanRow(set.actualRestSeconds, done = true, onEdit = null)
                    }
                    if (i != plan.lastIndex) Spacer(Modifier.height(10.dp))
                }
            }

            // A tick beside a set is a fact, not a draft: those sets happened, at the times written
            // next to them, and a planner offering to reorder them under "Start workout" pretends
            // none of it did. This is also where coming back from the summary lands — and a fact
            // can have been typed wrong, so this is where a finished set's figures are put right.
            plan.any { it.completed } -> PausedSession(
                plan = plan,
                specAt = { specAt(it) },
                loadUnitRes = loadUnitRes,
                timedAt = { timedAt(it) },
                paired = partner != null && plan.last().exerciseCode == null,
                setIndex = { setIndex(it) },
                without = { plan.withoutSet(it, spec, partner) },
                onPlan = { plan = it },
                onAddExercise = if (allowAdditionalExercises) ({ addingExercise = true }) else null,
                onNewSet = { startNextSet() },
                onEnd = { finish(plan) },
            )

            else -> {
                Text(
                    stringResource(R.string.workout_planned_sets),
                    style = MaterialTheme.typography.titleSmall,
                    color = PwnMuted,
                )
                Spacer(Modifier.height(8.dp))
                PlanEditor(
                    plan = plan,
                    specAt = { specAt(it) },
                    loadUnitRes = loadUnitRes,
                    timedAt = { timedAt(it) },
                    onChange = { plan = it },
                )
                // A pair is two journal rows, and only a new entry writes the second. Paired on a
                // saved row, the partner's sets would be folded into that row's payload and read
                // back as its own exercise's — so, like another exercise, a partner is not offered
                // there.
                if (allowAdditionalExercises) {
                    Spacer(Modifier.height(16.dp))
                    SupersetField(partner = partner, onPair = onPartner, onUnpair = onUnpair)
                }
                Spacer(Modifier.height(16.dp))
                // The templates stand at the start of the row Start ends: a saved session is the
                // other way to begin, and it is chosen at the moment of beginning, not from a card
                // of its own on the add sheet two screens back.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onTemplates?.let { TemplatesButton(it) }
                    SessionButton(
                        label = stringResource(R.string.workout_start_workout),
                        filled = true,
                        icon = Icons.Outlined.PlayArrow,
                        onClick = { startSet(0) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The door to the saved sessions, as a square on Start's own height and radius: the drawn template
 * picture rather than a word, because Start beside it already carries the row's one label. The
 * picture is named for a screen reader by what it opens. [side] is the height of the Start it stands
 * beside, which on the rep counter is the smaller tonal one.
 */
@Composable
internal fun TemplatesButton(onClick: () -> Unit, side: Dp = NOTE_BUTTON_HEIGHT) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(side),
        shape = RoundedCornerShape(NOTE_BUTTON_RADIUS),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(0.dp),
    ) {
        Image(
            themePainterResource(R.drawable.ic_art_schedule),
            stringResource(R.string.routines_open),
            Modifier.size(TEMPLATES_ART),
        )
    }
}

/** How large the template picture is drawn inside its square: the size the forms' bins are drawn at. */
private val TEMPLATES_ART = 26.dp

/**
 * The plan before the session starts: one set, or the two halves of one round, and the rest that
 * follows it.
 *
 * Nothing more is planned ahead — every later set is added from the rest card, worth what the last
 * one was — so there is nothing here to reorder or to remove. What is chosen here is what the later
 * sets inherit: the figures, and how long the rest runs.
 */
@Composable
private fun PlanEditor(
    plan: List<PlannedSet>,
    specAt: (Int) -> ActivitySpec,
    loadUnitRes: Int,
    timedAt: (Int) -> Boolean,
    onChange: (List<PlannedSet>) -> Unit,
) {
    Column {
        plan.forEachIndexed { i, set ->
            PlanRow(
                index = (0 until i).count { specAt(it).code == specAt(i).code },
                set = set,
                spec = specAt(i),
                loadUnitRes = loadUnitRes,
                timed = timedAt(i),
                onChange = { updated -> onChange(plan.toMutableList().also { it[i] = updated }) },
            )
            // The rest is the gap after a set, so it stands under the set it follows — the rule the
            // running plan names it by. Never between the halves of a round, which is what makes
            // the round a superset, and never under a hold.
            if (!timedAt(i) && !handsOver(plan, i)) {
                Spacer(Modifier.height(10.dp))
                RestPlanRow(
                    seconds = set.restSeconds,
                    done = false,
                    // Written to every row: pairing and unpairing rebuild the plan from its first
                    // halves, and the length chosen has to outlast both.
                    onEdit = { seconds -> onChange(plan.map { it.copy(restSeconds = seconds) }) },
                    maxSeconds = MAX_COUNTED_REST_SECONDS,
                )
            }
        }
    }
}

/**
 * The pair, chosen before the workout starts and never after.
 *
 * A superset is two exercises done back to back with no rest between them, so the only thing this
 * decides is what the plan looks like — one row per exercise per round. It sits under the plan
 * rather than in a window over it, and the word carries its own explanation behind the question
 * mark beside it: most people who open this screen have never been told what the word means.
 */
@Composable
private fun SupersetField(partner: ActivitySpec?, onPair: () -> Unit, onUnpair: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val word = stringResource(R.string.workout_superset)
            Text(word, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            HelpDot(word) { Text(stringResource(R.string.workout_superset_explain)) }
        }
        if (partner == null) {
            SessionButton(
                label = stringResource(R.string.workout_pair_exercise),
                filled = false,
                icon = Icons.Outlined.Add,
                onClick = onPair,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val name = stringResource(partner.titleRes)
                // Two lines rather than one: the partner's name is the whole content of this row,
                // and a long one cut to an ellipsis would leave the pair unnamed.
                Text(
                    name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Named for what goes: the partner, and with it the rows planned for it.
                IconButton(onClick = onUnpair) {
                    Image(
                        themePainterResource(R.drawable.ic_art_delete),
                        stringResource(R.string.med_contents_remove, name),
                        Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

/**
 * The workout with nothing running: sets behind it, and two ways out.
 *
 * A session that has been ended is not a plan waiting to be started — the sets are done, with the
 * times they took written beside them — so it offers the two things the resting card offers:
 * another set, or the summary. Sets the plan still holds and nobody reached are drawn as what is
 * ahead, because picking the workout back up starts with them before it makes a new one.
 *
 * It is also the one place a finished set is corrected: without it a set answered and gone could
 * not be touched anywhere, and a load typed as 600 for 60 would go into the journal, the volume and
 * the records unless the whole row were deleted. A tap on a finished set opens its figures here —
 * and its bin, where [without] has a plan left without it — and End then writes the session as
 * corrected. Both ways here pass it: back from the summary, and a saved row opened again.
 */
@Composable
private fun PausedSession(
    plan: List<PlannedSet>,
    specAt: (Int) -> ActivitySpec,
    loadUnitRes: Int,
    timedAt: (Int) -> Boolean,
    paired: Boolean,
    setIndex: (Int) -> Int,
    without: (Int) -> List<PlannedSet>?,
    onPlan: (List<PlannedSet>) -> Unit,
    onAddExercise: (() -> Unit)?,
    onNewSet: () -> Unit,
    onEnd: () -> Unit,
) {
    // The finished set open for correction, by its place in the plan; -1 while none is.
    var correcting by rememberSaveable { mutableIntStateOf(-1) }
    Column {
        plan.forEachIndexed { i, set ->
            when {
                !set.completed -> UpcomingSetRow(setIndex(i), set, specAt(i), loadUnitRes, timedAt(i))

                correcting == i -> PlanRow(
                    index = setIndex(i),
                    set = set,
                    spec = specAt(i),
                    loadUnitRes = loadUnitRes,
                    timed = timedAt(i),
                    onChange = { updated -> onPlan(plan.toMutableList().also { it[i] = updated }) },
                    done = true,
                    // Which round the bin takes, counted by the first halves up to this row: on
                    // either row of a pair it says the round, since that is what it removes.
                    round = (0..i).count { handsOver(plan, it) }
                        .takeIf { handsOver(plan, i) || (i > 0 && handsOver(plan, i - 1)) },
                    onRemove = without(i)?.let { shorter ->
                        {
                            correcting = -1
                            onPlan(shorter)
                        }
                    },
                    onClose = { correcting = -1 },
                )

                else -> CompletedSetRow(setIndex(i), set, specAt(i), loadUnitRes, onCorrect = { correcting = i })
            }
            // The rest is the gap between two sets, so it is drawn only where there is a set
            // after it for the gap to be before — and never inside a round.
            if (set.completed && i != plan.lastIndex && !handsOver(plan, i)) {
                Spacer(Modifier.height(10.dp))
                RestPlanRow(set.actualRestSeconds, done = true, onEdit = null)
            }
            if (i != plan.lastIndex) Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(16.dp))
        SessionButton(
            label = stringResource(if (paired) R.string.workout_new_round else R.string.workout_new_set),
            filled = true,
            icon = Icons.Outlined.Add,
            onClick = onNewSet,
        )
        Spacer(Modifier.height(10.dp))
        if (onAddExercise != null) {
            SessionButton(
                label = stringResource(R.string.workout_add_exercise),
                filled = false,
                icon = Icons.Outlined.Add,
                onClick = onAddExercise,
            )
            Spacer(Modifier.height(10.dp))
        }
        SessionButton(
            label = stringResource(
                if (timedAt(0)) R.string.workout_end else R.string.workout_end_workout,
            ),
            filled = false,
            onClick = onEnd,
        )
    }
}

/** The set's own number, in its disc; a template's flow numbers its steps with it. */
@Composable
internal fun SetBadge(index: Int, done: Boolean = false, active: Boolean = false) {
    // A green disc would be the only saturated green on a page built out of indigo, and with the
    // scheme's onPrimary — dark ink in daylight — it would put a black tick on a dark green ground.
    // A set that has happened and the set happening now are both the page's own accent; what tells
    // them apart is the tick, and a set not reached yet stays the palest step of the same ramp.
    val fill = if (done || active) PwnIndigo else PwnIndigoSoft
    Box(
        // A floor rather than a size, so the disc stretches into a pill when a large font or a
        // two-digit set outgrows it instead of cutting the number off. Silent to a screen reader:
        // every row it stands in names the set in words beside it.
        Modifier.sizeIn(minWidth = SET_BADGE_SIZE, minHeight = SET_BADGE_SIZE)
            .background(fill, CircleShape)
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                Icons.Outlined.Check,
                null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(
                (index + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                // The idle disc is primaryContainer, so its number is the ink that container is
                // paired with; 12sp Bold is not WCAG-large, so it owes 4.5:1. The active disc is
                // primary itself and keeps the scheme's onPrimary.
                color = if (active) MaterialTheme.colorScheme.onPrimary else PwnIndigoDark,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private val SET_BADGE_SIZE = 26.dp

/**
 * One set with its figures open: the set a plan starts with, and a finished set being corrected on
 * the paused session. The second is [done], may be taken out ([onRemove], which in a pair takes the
 * whole [round]) and is closed again by [onClose].
 */
@Composable
private fun PlanRow(
    index: Int,
    set: PlannedSet,
    spec: ActivitySpec,
    loadUnitRes: Int,
    timed: Boolean,
    onChange: (PlannedSet) -> Unit,
    done: Boolean = false,
    round: Int? = null,
    onRemove: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val setName = stringResource(R.string.workout_exercise_set, stringResource(spec.titleRes), index + 1)
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SetBadge(index, done = done)
                Spacer(Modifier.width(10.dp))
                // Every row says which exercise it is and which of its sets — that is what tells
                // the two halves of a round apart. Two lines, because that name is the row's
                // identity and an ellipsis would take it away. The badge beside it is silent, so the
                // set's number is spoken here with the name.
                Text(
                    setName,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onRemove != null) {
                    // The drawn bin, untinted: it is the one control on this row that destroys
                    // something. It names its set — out of the row's context, in the actions list
                    // of a screen reader, Switch Access or Voice Access, a bare "Delete" says
                    // nothing about which set goes. In a pair it takes both rows of the round, and
                    // its description says so.
                    IconButton(onClick = onRemove) {
                        Image(
                            themePainterResource(R.drawable.ic_art_delete),
                            if (round != null) {
                                stringResource(R.string.workout_delete_round, round)
                            } else {
                                "$setName: ${stringResource(R.string.common_delete)}"
                            },
                            Modifier.size(26.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (timed) {
                // The fields are the set: whatever stands here is what the clock starts from, so a
                // hold begun before the phone was picked up is typed rather than lost — and on a
                // finished hold, the time it is written with.
                ClockFields(
                    seconds = set.actualSeconds,
                    editable = true,
                    onSeconds = { onChange(set.copy(actualSeconds = it)) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.workout_tap_to_edit),
                    style = MaterialTheme.typography.labelSmall,
                    color = PwnMuted,
                )
            } else {
                SetFigures(set, spec, loadUnitRes, locked = false, onChange = onChange)
            }
            if (onClose != null) {
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.common_done))
                }
            }
        }
    }
}

/**
 * The reps box and, for a loaded exercise, the load box beside it — the same pair on the plan and
 * on the running card. The keyboard's action key walks from reps to load and closes on the last
 * box, rather than every box offering the same bare return.
 */
@Composable
internal fun SetFigures(
    set: PlannedSet,
    spec: ActivitySpec,
    loadUnitRes: Int,
    locked: Boolean,
    compact: Boolean = false,
    onChange: (PlannedSet) -> Unit,
) {
    val loaded = spec.input == ActivityInput.WEIGHTED_SETS
    // Compact boxes are each as wide as their figure needs and no narrower than a thumb's width, rather
    // than sharing the whole row; where they stand is the column's to say.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ValueBox(
            value = set.reps,
            unit = stringResource(R.string.workout_reps_short),
            label = stringResource(R.string.editor_reps),
            modifier = if (compact) Modifier.width(compactBoxWidth(set.reps)) else Modifier.weight(1f),
            locked = locked,
            keyboard = KeyboardType.Number,
            imeAction = if (loaded) ImeAction.Next else ImeAction.Done,
            onValue = { onChange(set.copy(reps = it.digitsOnly())) },
        )
        if (loaded) {
            ValueBox(
                value = set.load,
                unit = stringResource(loadUnitRes),
                label = stringResource(R.string.editor_working_weight),
                modifier = if (compact) Modifier.width(compactBoxWidth(set.load)) else Modifier.weight(1f),
                locked = locked,
                keyboard = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
                onValue = { onChange(set.copy(load = it.decimalInput())) },
            )
        }
    }
}

/** As wide as [value] stands in the box's type, its padding and the cursor, and never under [COMPACT_BOX_MIN]. */
@Composable
internal fun compactBoxWidth(value: String): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    val text = with(LocalDensity.current) { measurer.measure(value, style).size.width.toDp() }
    return maxOf(COMPACT_BOX_MIN, text + COMPACT_BOX_ROOM)
}

private val COMPACT_BOX_MIN = 104.dp

/** The frame's padding on both sides, its edge and a cursor's width. */
private val COMPACT_BOX_ROOM = 36.dp

/**
 * One figure of the set: the number large, its unit small under it, and a padlock in the corner once
 * the set is running and the figure is no longer a plan.
 *
 * [unit] is what the box shows under the number and [label] is what a screen reader says for it:
 * the printed unit is an abbreviation ("reps", "kg", "HH") that reads badly aloud or letter by
 * letter, so it is kept out of the accessibility tree and the field is named in words instead.
 */
@Composable
internal fun ValueBox(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Number,
    imeAction: ImeAction = ImeAction.Done,
    onValue: ((String) -> Unit)? = null,
) {
    if (onValue == null || locked) {
        val shown = value.ifBlank { "0" }
        ValueFrame(modifier, focused = false) {
            Column(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label, $shown" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    shown,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (locked) PwnMuted else MaterialTheme.colorScheme.onSurface,
                )
                ValueUnit(unit)
            }
            if (locked) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = PwnMuted,
                    modifier = Modifier.align(Alignment.TopEnd).size(13.dp),
                )
            }
        }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val text = rememberSyncedText(value, onValue)
    BasicTextField(
        state = text.state,
        inputTransformation = text.handOff,
        modifier = modifier.semantics { contentDescription = label },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction),
        textStyle = MaterialTheme.typography.titleLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        // The whole box is the field. Drawn around the field from outside, the box would look larger
        // than what answers a tap: the touch area Compose widens around the line of digits does not
        // reach its lower edge under the unit, and a tap there would do nothing. Inside the
        // decoration the padding and the unit are part of the node the tap gesture is on.
        decorator = TextFieldDecorator { field ->
            ValueFrame(Modifier, focused) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    field()
                    ValueUnit(unit)
                }
            }
        },
    )
}

/**
 * The box a figure stands in. A focused one says so the way Material's outlined field does: the
 * edge thickens and takes the accent, so the box being typed into does not look like every other.
 */
@Composable
private fun ValueFrame(modifier: Modifier, focused: Boolean, content: @Composable BoxScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = if (focused) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        },
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), content = content)
    }
}

@Composable
private fun ValueUnit(unit: String) {
    Text(
        unit,
        Modifier.clearAndSetSemantics {},
        style = MaterialTheme.typography.labelSmall,
        color = PwnMuted,
    )
}

/**
 * Duration in minutes and seconds, edited with the same wheels as other time fields.
 *
 * The button speaks its figure as minutes and seconds in words: "05:30" on its own is read out as
 * a time of day, or as digits and a colon.
 */
@Composable
internal fun ClockFields(seconds: Int, editable: Boolean, onSeconds: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val spoken = "${stringResource(R.string.a11y_minutes)}, ${seconds / 60}, " +
        "${stringResource(R.string.a11y_seconds)}, ${seconds % 60}"
    OutlinedButton(
        onClick = { open = true },
        enabled = editable,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
    ) {
        Text(
            String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
    if (open) {
        DurationPickerDialog(seconds, onDismiss = { open = false }, onConfirm = {
            onSeconds(it)
            open = false
        })
    }
}

/**
 * Every space-separated part wrapped in a first-strong isolate, so a left-to-right paragraph lays
 * the parts out in the order they are written whatever script each one is in.
 */
private fun String.isolatedParts(): String = split(' ').joinToString(" ") { "\u2068$it\u2069" }

/**
 * Minutes and seconds on two wheels.
 *
 * Laid out left to right in every language, as Material's own time input is: the running clock
 * prints its digits minutes-first from the left even in Arabic, and a mirrored row would put the
 * minutes wheel on the right, where that clock has its seconds. Each wheel's printed unit is an
 * abbreviation ("MM", "SS") that reads badly aloud, so it is named in words for a screen reader,
 * and the colon between them, which would otherwise be read out as "colon", is silent.
 *
 * [maxSeconds] is as far as they turn. A session's planned rest names its own
 * ([MAX_COUNTED_REST_SECONDS]): the rest card's clock stops there, so a rest planned at forty minutes
 * would be a ring that never closes over a clock standing at 15:00. A figure already past the ceiling — a
 * rest a template planned longer — is still on the wheel it opens on, and can be kept.
 */
@Composable
internal fun DurationPickerDialog(
    seconds: Int,
    onDismiss: () -> Unit,
    maxSeconds: Int = MAX_PICKED_SECONDS,
    onConfirm: (Int) -> Unit,
) {
    var minutes by rememberSaveable { mutableIntStateOf(seconds / 60) }
    var remainder by rememberSaveable { mutableIntStateOf(seconds % 60) }
    val ceiling = maxOf(maxSeconds, seconds)
    val minuteValues = remember { (0..ceiling / 60).toList() }
    val secondValues = remember { (0..59).toList() }
    // One tick for both wheels: each left to its own builds a SoundPool of its own.
    val tick = rememberRulerTick()
    val minutesLabel = stringResource(R.string.a11y_minutes)
    val secondsLabel = stringResource(R.string.a11y_seconds)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workout_duration_picker)) },
        text = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.workout_minutes_short),
                            Modifier.semantics { contentDescription = minutesLabel },
                        )
                        Wheel(minuteValues, minutes, { it.toString().padStart(2, '0') }, { minutes = it }, tick)
                    }
                    Text(":", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineMedium)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.workout_seconds_short),
                            Modifier.semantics { contentDescription = secondsLabel },
                        )
                        Wheel(secondValues, remainder, { it.toString().padStart(2, '0') }, { remainder = it }, tick)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickedSeconds(minutes, remainder, ceiling)) }) {
                Text(stringResource(R.string.common_done))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The gap between two sets, as its own row rather than a chip inside the set before it — and the gap
 * between two steps of a template. [maxSeconds] is as long as its wheels set it: a session's rest no
 * longer than its clock counts, a template's as long as the wheels go, since its clock runs the plan out.
 */
@Composable
internal fun RestPlanRow(
    seconds: Int,
    done: Boolean,
    onEdit: ((Int) -> Unit)?,
    maxSeconds: Int = MAX_PICKED_SECONDS,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing) {
        DurationPickerDialog(seconds, { editing = false }, maxSeconds) {
            onEdit?.invoke(it)
            editing = false
        }
    }
    Surface(
        // One stop for a screen reader, not an icon, a sentence and a tick apart. A clickable is a
        // node of its own, so the Edit below still stands on its own inside the merged row.
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            // The row is not itself a target — the Edit label carries its own 48dp through the
            // padding inside its clickable — so this floor only lifts the row that has no Edit on
            // it: the done row's tallest child is the 20dp label line, which leaves it at 44dp. The
            // 12dp of vertical padding stays. A floor stops mattering the moment the label needs a
            // second line, which the Edit hit rect's own side padding and any font scale above 1
            // both bring closer in a longer language. Two lines hard against a 1dp outline is what
            // the padding prevents.
            Modifier.heightIn(min = 48.dp).padding(vertical = 12.dp, horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, null, tint = PwnMuted, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "${stringResource(R.string.workout_rest)} — ${seconds.elapsedClockText()}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                // Same reason as the badge above: the rest that has been taken belongs to the
                // page's own accent, not to a green that appears nowhere else on it. The tick is
                // the only thing that tells a rest taken from one still ahead, so it is spoken.
                done -> Icon(
                    Icons.Outlined.CheckCircle,
                    stringResource(R.string.common_done),
                    tint = PwnIndigo,
                    modifier = Modifier.size(19.dp),
                )

                // The hit rect grows, the word does not. Four glyphs of bodyMedium (14sp, 20sp
                // line height) lay the label out at about 28x20dp, and a bare Text is not a
                // Material component, so LocalMinimumInteractiveComponentSize never expands it:
                // 20 of the 48dp Android minimum in height, 560 of the 2304dp² minimum in area,
                // and this is the only handle on a rest length. Padding inside the clickable takes
                // it to 52x48dp; the Surface already clips, so the clip is what rounds the ripple
                // to the row's own 12dp radius rather than letting it land as a bare rectangle.
                //
                // A tap opens the duration wheels for this rest. So it is a button that says what
                // it changes, and the length the rest has now is its state, read back when the
                // wheels close. Printed in the accent rather than the row's own ink, which would
                // leave SemiBold as the only sign the word can be pressed.
                onEdit != null -> Text(
                    stringResource(R.string.common_edit),
                    Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(
                            onClickLabel = stringResource(R.string.workout_rest_change),
                            role = Role.Button,
                        ) { editing = true }
                        .semantics { stateDescription = seconds.elapsedClockText() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RunningSetCard(
    index: Int,
    set: PlannedSet,
    spec: ActivitySpec,
    loadUnitRes: Int,
    timed: Boolean,
    elapsed: Int,
    nextLabel: String?,
    confirming: Boolean,
    onChange: (PlannedSet) -> Unit,
    onStop: () -> Unit,
) {
    // The set's one action takes keyboard focus as the card appears: the button that started the
    // set has just left the page, and focus would otherwise fall back to the top of it. A touch
    // screen gives a button no focus, so this changes nothing for a finger or a screen reader.
    val stop = remember { FocusRequester() }
    LaunchedEffect(index) { stop.requestFocus() }
    PwnCard(Modifier.fillMaxWidth(), containerColor = PwnIndigoSoft) {
        Column(Modifier.padding(PwnDimensions.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SetBadge(index, active = true)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.workout_exercise_set, stringResource(spec.titleRes), index + 1),
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                if (!timed) {
                    Text(
                        elapsed.elapsedClockText(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (timed) {
                // A running hold is one number and it is the whole point of the screen, so it is
                // read at a glance from across a mat rather than in three little boxes.
                Text(
                    elapsed.clockText(),
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                // Left to right in every language, like the digits above it. A left-to-right
                // paragraph alone does not do it: in Arabic the three labels and the colons between
                // them resolve to one right-to-left run, which still lays the hours label out on
                // the right, under the seconds. Each part in its own isolate keeps written order.
                Text(
                    stringResource(R.string.workout_clock_mask).isolatedParts(),
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
                    color = PwnMuted,
                    textAlign = TextAlign.Center,
                )
            } else {
                SetFigures(set, spec, loadUnitRes, locked = confirming, onChange = onChange)
            }
            Spacer(Modifier.height(14.dp))
            // Stop/Complete is a standalone full-width action like Start, Pair, New set and End,
            // and wears the same SessionButton. Its label wraps rather than ellipsising: when a
            // partner follows, the label names it, and the name is the part an ellipsis would cut.
            SessionButton(
                label = when {
                    nextLabel != null -> stringResource(R.string.workout_next_no_rest, nextLabel)
                    timed -> stringResource(R.string.workout_stop)
                    else -> stringResource(R.string.workout_complete_set)
                },
                filled = true,
                icon = if (timed) Icons.Outlined.Stop else Icons.Outlined.Check,
                enabled = !confirming,
                modifier = Modifier.focusRequester(stop),
                onClick = onStop,
            )
        }
    }
}

/**
 * The rest, which starts itself.
 *
 * A person who has just put a bar down is not going to reach for a button to begin resting, so the
 * clock is already running by the time this appears; the two buttons are only about what happens
 * next. For a timed exercise the set just stopped is shown above it, because the number that was
 * on screen a second ago is the one being asked about.
 *
 * [elapsed] stops at the longest a rest counts for ([countedRest]), so a card left open shows the
 * rest that will be written and not the hours the phone lay there.
 */
@Composable
private fun RestingCard(
    elapsed: Int,
    plannedSeconds: Int,
    timed: Boolean,
    paired: Boolean,
    efforts: List<EffortAsk>,
    onAddExercise: (() -> Unit)?,
    onAddSet: () -> Unit,
    onEnd: () -> Unit,
) {
    val progress = if (plannedSeconds <= 0) 1f else (elapsed / plannedSeconds.toFloat()).coerceIn(0f, 1f)
    // The card replaces the running set without a word: a pane title is what a screen reader
    // announces when a pane appears, so the rest is said as it starts rather than found by
    // swiping. Keyboard focus lands on the next set's button for the same reason.
    val pane = stringResource(R.string.workout_resting)
    val next = remember { FocusRequester() }
    LaunchedEffect(Unit) { next.requestFocus() }
    PwnCard(Modifier.fillMaxWidth().semantics { paneTitle = pane }, containerColor = PwnIndigoSoft) {
        Column(
            Modifier.padding(PwnDimensions.cardPadding).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(56.dp).background(PwnIndigo, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.HourglassBottom,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.workout_resting),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                elapsed.elapsedClockText(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = PwnIndigoDark,
            )
            Spacer(Modifier.height(8.dp))
            RestArc(progress)
            if (efforts.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                EffortBlock(efforts)
            }
            Spacer(Modifier.height(16.dp))
            // The same two buttons the paused session offers, so they wear the same SessionButton:
            // a smaller, squarer pair of its own would make one action look like two across the page.
            SessionButton(
                label = stringResource(
                    if (paired) R.string.workout_add_another_round else R.string.workout_add_another_set,
                ),
                filled = true,
                icon = Icons.Outlined.Add,
                modifier = Modifier.focusRequester(next),
                onClick = onAddSet,
            )
            Spacer(Modifier.height(10.dp))
            if (onAddExercise != null) {
                SessionButton(
                    label = stringResource(R.string.workout_add_exercise),
                    filled = false,
                    icon = Icons.Outlined.Add,
                    onClick = onAddExercise,
                )
                Spacer(Modifier.height(10.dp))
            }
            SessionButton(
                label = stringResource(if (timed) R.string.workout_end else R.string.workout_end_workout),
                filled = false,
                onClick = onEnd,
            )
        }
    }
}

/**
 * What the set cost, asked while the answer is still in the arms.
 *
 * The rest is the only honest moment for it: you cannot predict it while planning, you are holding
 * a bar while running, and by the summary you have forgotten. Nothing here is required — five
 * unlit chips is a complete answer, and tapping the lit one puts it back to nothing — so a person
 * who does not rate their sets sees one extra label and never has to dismiss anything.
 */
@Composable
private fun EffortBlock(efforts: List<EffortAsk>) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val word = stringResource(R.string.workout_effort)
            Text(word, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            HelpDot(word) { Text(stringResource(R.string.workout_rir_explain)) }
        }
        efforts.forEach { ask ->
            // The exercise's name only when there are two of them to tell apart.
            ask.label?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
                Spacer(Modifier.height(4.dp))
            }
            // Flowing rather than one row: five chips only just fit the card on a narrow phone, and
            // a chosen one grows by its check mark, so on a narrower window or a larger display
            // size the last chip would be squeezed out of reach instead of dropping to a second line.
            // Centred under the clock and the arc, and each wrapped line centred with them.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RIR_CHOICES.forEach { value ->
                    // A bare digit would say "2, not selected" with nothing to say what the 2 counts.
                    val spoken = stringResource(R.string.workout_rir_selected, value, value.rpeFromRir())
                    PwnFilterChip(
                        selected = ask.rir == value,
                        onClick = { ask.onRir(value.takeIf { it != ask.rir }) },
                        label = value.toString(),
                        modifier = Modifier.semantics { contentDescription = spoken },
                    )
                }
            }
            ask.rir?.let {
                Text(
                    stringResource(R.string.workout_rir_selected, it, it.rpeFromRir()),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * How much of the rest has gone, as a shallow arc under the clock.
 *
 * A sixty-degree slice of a wide circle, so what shows is almost a straight line with a lift in
 * it — the shape reads as a progress bar that belongs to the number above it rather than as a
 * gauge with a scale of its own.
 */
@Composable
private fun RestArc(progress: Float) {
    val track = PwnIndigo.copy(alpha = 0.22f)
    val fill = PwnIndigo
    // The clock above it says how long the rest has run, and nothing on the card says how long it
    // was meant to run: the share between the two is this arc's alone, so it is published as a
    // progress bar and read out as a percentage rather than left as a shape only eyes can use.
    val label = stringResource(R.string.workout_rest)
    // A bar fills in the direction of reading, so in a right-to-left language the arc is drawn
    // mirrored and fills from the right.
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        Modifier.width(REST_ARC_WIDTH)
            .height(REST_ARC_HEIGHT)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            },
    ) {
        scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f) {
            val stroke = Stroke(width = REST_ARC_STROKE.toPx(), cap = StrokeCap.Round)
            // The circle is far taller than the box, so only the bottom of it is on screen.
            val radius = size.width
            val topLeft = Offset(size.width / 2f - radius, size.height - radius * 2f + REST_ARC_STROKE.toPx())
            val arcSize = Size(radius * 2f, radius * 2f)
            drawArc(track, START_ANGLE, SWEEP_ANGLE, false, topLeft, arcSize, style = stroke)
            if (progress > 0f) {
                drawArc(fill, START_ANGLE, SWEEP_ANGLE * progress, false, topLeft, arcSize, style = stroke)
            }
        }
    }
}

/**
 * Centred on the bottom of the circle: thirty degrees either side of straight down.
 *
 * Swept backwards from the far end, because Compose measures clockwise from three o'clock — start
 * at the lower left and sweep negative, and the fill grows left to right the way a bar does. Swept
 * the other way it would fill from the right, which reads as a countdown emptying rather than a rest
 * being served. Right to left is the same drawing mirrored, not these angles changed.
 */
private const val START_ANGLE = 120f
private const val SWEEP_ANGLE = -60f

private val REST_ARC_WIDTH = 120.dp
private val REST_ARC_HEIGHT = 18.dp
private val REST_ARC_STROKE = 4.dp

/**
 * The note button's size, and with it every action that sits under a note: Material's Medium
 * button, which is what these tokens are.
 *
 * The note and the button below it carry equal weight, so the pair reads as two choices rather than
 * as a control and its afterthought. Shared rather than repeated: the food form ends the same way,
 * and the two screens drifting apart is exactly what this is preventing.
 */
internal val NOTE_BUTTON_HEIGHT = PwnDimensions.mediumButtonHeight
internal val NOTE_BUTTON_RADIUS = PwnCorners.large

/**
 * The shape every action standing on its own in this session wears.
 *
 * Add note's — that height, that capsule, that weight of word — which Done on the summary shares.
 * Starting a workout, picking it up again and ending it are choices of exactly that kind, so they
 * share it too rather than each finding its own default height. [filled] tells the two apart the
 * only way they should differ: the solid one carries the session on, the outlined one lets it go.
 *
 * Material's Medium button throughout: a floor of its height rather than the height itself, so a
 * label that wraps at a large font makes the button taller instead of losing its second line; its
 * icon size and gap; and the Expressive pressed shape, the corners squaring a step under the finger
 * and springing back when it lifts, as on the wide buttons. The outlined one has one recipe
 * wherever it stands: an outline edge and a label in the accent.
 */
@Composable
private fun SessionButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        if (pressed) PwnCorners.medium else NOTE_BUTTON_RADIUS,
        PwnMotion.fastSpatial(),
        label = "session button corner",
    )
    val shape = RoundedCornerShape(corner)
    val sized = modifier.fillMaxWidth().heightIn(min = NOTE_BUTTON_HEIGHT)
    // The button's own content colour, so the disabled state greys the icon and the label with it.
    val body: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null, Modifier.size(PwnDimensions.mediumButtonIcon))
            Spacer(Modifier.width(PwnDimensions.buttonGap))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
    if (filled) {
        Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            interactionSource = interaction,
            content = body,
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            interactionSource = interaction,
            content = body,
        )
    }
}

/**
 * A set that is done: its number becomes a tick, and what it cost stays readable. With [onCorrect]
 * the row is the way into its figures, as it is on the paused session; while the session runs it is
 * only read.
 */
@Composable
private fun CompletedSetRow(
    index: Int,
    set: PlannedSet,
    spec: ActivitySpec,
    loadUnitRes: Int,
    onCorrect: (() -> Unit)? = null,
) {
    // One stop for a screen reader rather than the set's name, its figures and its time read out as
    // three. Where the row opens for correction that stop is a button named for it, clipped to the
    // row's own corners so the ripple does not land as a bare rectangle.
    val row = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
    Surface(
        if (onCorrect == null) {
            row
        } else {
            row.clip(MaterialTheme.shapes.medium).clickable(
                onClickLabel = stringResource(R.string.common_edit),
                role = Role.Button,
                onClick = onCorrect,
            )
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SetBadge(index, done = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.workout_exercise_set, stringResource(spec.titleRes), index + 1),
                    fontWeight = FontWeight.Bold,
                )
                val detail = setDetailLine(set, spec, loadUnitRes)
                if (detail.isNotEmpty()) {
                    Text(detail, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                set.actualSeconds.elapsedClockText(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (onCorrect != null) {
                // The sign that the row opens; the button's own label is what says it aloud.
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Outlined.Edit, null, tint = PwnMuted, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun UpcomingSetRow(index: Int, set: PlannedSet, spec: ActivitySpec, loadUnitRes: Int, timed: Boolean) {
    Row(
        // Read-only, like the completed row: one stop.
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetBadge(index)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.workout_exercise_set, stringResource(spec.titleRes), index + 1),
                color = PwnMuted,
                fontWeight = FontWeight.SemiBold,
            )
            val detail = if (timed) set.actualSeconds.elapsedClockText() else setDetailLine(set, spec, loadUnitRes)
            if (detail.isNotEmpty()) {
                Text(detail, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * What the set was worth, in one line.
 *
 * Every place a finished set is read from — the live list, the paused list and the summary card —
 * comes through here, so the effort rating lights up in all three from this one spot.
 */
@Composable
private fun setDetailLine(set: PlannedSet, spec: ActivitySpec, loadUnitRes: Int): String {
    val body = when {
        // A timed set the phone counted keeps its count in its own words, as the session's end page
        // says it: "3 floors · 156 steps", "120 jumps".
        spec.input == ActivityInput.DURATION -> countedSetText(LocalResources.current, spec.code, set)

        // No kilograms on a set — push-ups with nothing on top, a bar typed in as nought — is its reps.
        // The box's text keeps a point in every language; the line is read, and takes the language's.
        spec.input == ActivityInput.WEIGHTED_SETS && set.load.number() > 0 ->
            "${set.load.number().shown()} ${stringResource(loadUnitRes)} × ${set.reps.ifBlank { "0" }}"

        else -> "${set.reps} ${stringResource(R.string.workout_reps_short)}"
    }
    val rpe = set.rir?.let { stringResource(R.string.workout_rpe_short, it.rpeFromRir()) } ?: return body
    return if (body.isEmpty()) rpe else "$body · $rpe"
}

/**
 * What the phone counted of a timed set of [code], in the words a count of it is said in — "3 floors ·
 * 156 steps", "120 jumps" — or nothing, for a set it counted nothing of or a count that is plain reps.
 */
internal fun countedSetText(resources: Resources, code: String, set: PlannedSet): String {
    val count = set.reps.intNumber()
    val counted = countsInOwnWords(code) && (count > 0 || set.floors > 0)
    return if (counted) trackedText(resources, code, count, set.floors) else ""
}

@Composable
internal fun WorkoutSummaryScreen(
    modifier: Modifier,
    spec: ActivitySpec,
    partner: ActivitySpec?,
    calories: Double,
    duration: String,
    sets: String,
    reps: String,
    load: String,
    loadUnitRes: Int,
    plannedSets: List<PlannedSet>,
    note: String,
    onNote: (String) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    // Supplied only when a training machine filled this session in. A hand-logged session's figures
    // are the sets it actually ran, and are corrected by correcting those — one step back, where a
    // finished set opens on the paused session; a machine's are two numbers it reported, and this
    // is the only screen between them and the journal.
    onDuration: ((String) -> Unit)? = null,
    machineKcal: Int? = null,
    onMachineKcal: ((Int?) -> Unit)? = null,
    /** False while the figures on this screen would not make a row worth saving. */
    doneEnabled: Boolean = true,
    /** The saved row being finished again, left out of the records it is measured against. */
    excludeId: Long? = null,
) {
    var checkShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { checkShown = true }
    // The records each exercise held before this session, and which of its sets beat them. The
    // loads on this screen are in the reader's unit; records are kept in kilograms.
    val imperial = loadUnitRes == R.string.unit_lb
    val toKg = if (imperial) POUNDS_IN_A_KILOGRAM else 1.0
    val byExercise = remember(plannedSets, spec, partner) {
        plannedSets.withIndex().groupBy { it.value.exercise(spec, partner).code }
    }
    val before = rememberRecordsBefore(byExercise.keys.toList(), excludeId)
    val sessionSets = remember(byExercise, toKg) {
        byExercise.mapValues { (_, sets) -> sets.map { it.value.strengthSet(toKg) } }
    }
    // The rows the save writes ([completedExerciseGroups]): a return to an exercise after another is
    // a row of its own, and a volume is one row's.
    val sessionRows = remember(plannedSets, spec, partner, toKg) {
        plannedSets.completedExerciseGroups(spec, partner)
            .groupBy({ it.first.code }, { (_, sets) -> sets.map { it.strengthSet(toKg) } })
    }
    val broken = before?.let { records ->
        sessionSets.flatMap { (code, sets) ->
            brokenRecords(code, records[code], sets, sessionRows[code] ?: listOf(sets))
        }
    }.orEmpty()
    val recordSets = before?.let { records ->
        sessionSets.flatMap { (code, sets) ->
            val indices = byExercise.getValue(code).map { it.index }
            recordSetIndices(records[code], sets).map(indices::get)
        }.toSet()
    }.orEmpty()

    Column(modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(20.dp)) {
        // The arrow, the title, and a blank the arrow's width: the title is centred on the page
        // and wraps between the two instead of running under the arrow in a long language. It
        // says the workout is complete, not saved — nothing is written until Done, and the back
        // arrow returns to the form with the session still unsaved.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back))
            }
            Text(
                stringResource(R.string.workout_complete_title),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(PwnDimensions.minimumTarget))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            WorkoutSalute(Modifier.fillMaxWidth().height(150.dp), running = checkShown)
            // Fully qualified: the enclosing Column would otherwise pick the ColumnScope overload,
            // which cannot be called from inside this Box.
            androidx.compose.animation.AnimatedVisibility(
                visible = checkShown,
                enter = scaleIn(spring(dampingRatio = 0.55f)) + fadeIn(),
            ) {
                // The moment the whole screen exists for, drawn as a rosette with the tick already
                // inside it. It brings its own disc and its own colour, so a circle behind it would be
                // a second badge under the first — the rosette stands on its own, and the scale-in
                // lands it.
                Image(themePainterResource(R.drawable.ic_art_done), null, Modifier.size(110.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        // Both exercises, each with its own worked muscles. A pair of titles says more than one
        // title joined by a plus sign, and it needs no string to join them with.
        MuscleTitleRow(stringResource(spec.titleRes), spec.muscles)
        (listOfNotNull(partner) + plannedSets.map { it.exercise(spec, partner) })
            .distinctBy { it.code }.filter { it.code != spec.code }.forEach {
                Spacer(Modifier.height(8.dp))
                MuscleTitleRow(stringResource(it.titleRes), it.muscles)
            }
        Spacer(Modifier.height(14.dp))
        if (plannedSets.isNotEmpty()) {
            PwnCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(PwnDimensions.cardPadding)) {
                    plannedSets.forEachIndexed { i, set ->
                        SummarySetLine(
                            plannedSets.exerciseSetIndex(i, spec, partner),
                            set,
                            set.exercise(spec, partner),
                            loadUnitRes,
                            record = i in recordSets,
                        )
                        // Inside a round there is no rest to report. After the last set there is
                        // one only where the payload kept it — the rest a session was ended on —
                        // and those seconds are in the minutes below, so they are listed rather
                        // than left for the total to outrun the lines above it.
                        val last = i == plannedSets.lastIndex
                        if (!handsOver(plannedSets, i) && (!last || set.actualRestSeconds > 0)) {
                            SummaryRestLine(set.actualRestSeconds)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        if (broken.isNotEmpty()) {
            NewRecordsCard(broken, imperial)
            Spacer(Modifier.height(14.dp))
        }
        if (onDuration != null) {
            MachineResultFields(duration, onDuration, machineKcal, onMachineKcal)
        } else {
            SummaryStatsRow(spec, duration, sets, reps, load, loadUnitRes, plannedSets)
        }
        Spacer(Modifier.height(14.dp))
        if (machineKcal == null) WorkoutEnergyCard(calories, spec.met)
        Spacer(Modifier.height(14.dp))
        NoteField(note, onNote)
        Spacer(Modifier.height(14.dp))
        // Done and the note above it are a pair, not a control and its afterthought: the same
        // height, the same capsule, the same weight of word, and only the fill telling them apart.
        // This is the one that commits the session, so this is the solid one.
        SessionButton(
            label = stringResource(R.string.common_done),
            filled = true,
            enabled = doneEnabled,
            onClick = onDone,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** A set as the records read it: in kilograms, from boxes in the reader's unit ([toKg] divides). */
private fun PlannedSet.strengthSet(toKg: Double) = StrengthSet(load.number() / toKg, reps.intNumber(), 0L)

/**
 * What the machine reported, in boxes rather than tiles.
 *
 * The console is not always right — it is reset late, left running after the ride, or counting a
 * warm-up nobody wants logged — and this is the last screen before the journal, so the two figures
 * it supplied are typed over here rather than corrected afterwards by editing a saved row.
 */
@Composable
private fun MachineResultFields(
    duration: String,
    onDuration: (String) -> Unit,
    kcal: Int?,
    onKcal: ((Int?) -> Unit)?,
) {
    NumberField(
        duration,
        { onDuration(it.decimalInput()) },
        stringResource(R.string.editor_time),
        stringResource(R.string.unit_minutes),
        decimal = true,
    )
    if (onKcal != null) {
        Spacer(Modifier.height(12.dp))
        NumberField(
            kcal?.toString().orEmpty(),
            { typed -> onKcal(typed.digitsOnly().toIntOrNull()) },
            stringResource(R.string.direct_bluetooth_energy),
            stringResource(R.string.unit_kcal),
        )
    }
}

/**
 * A note starts as a button, not an empty box a form has to scroll past — it only grows into a
 * field once there is something to say, or once a previous note is there to read.
 */
@Composable
internal fun NoteField(
    note: String,
    onNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: Int = R.string.editor_note,
) {
    var open by remember { mutableStateOf(note.isNotBlank()) }
    // Set only by the button: a field opened by asking for it takes the cursor and the keyboard
    // at once, rather than needing a second tap before anything can be typed. A note already
    // there opens as a field to read, and does not pull the keyboard up over the page on arrival.
    var asked by remember { mutableStateOf(false) }
    if (open) {
        val field = remember { FocusRequester() }
        LaunchedEffect(asked) { if (asked) field.requestFocus() }
        OutlinedTextField(
            value = note,
            onValueChange = { onNote(it.take(NOTE_MAX_CHARS)) },
            label = { Text(stringResource(hint)) },
            supportingText = {
                Text(
                    stringResource(R.string.note_counter, note.length, NOTE_MAX_CHARS),
                    Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
            },
            modifier = modifier.fillMaxWidth().focusRequester(field),
            minLines = 2,
        )
    } else {
        SessionButton(
            label = stringResource(R.string.workout_add_note),
            filled = false,
            icon = Icons.AutoMirrored.Outlined.Notes,
            modifier = modifier,
            onClick = {
                open = true
                asked = true
            },
        )
    }
}

/** What the session cost, and the arithmetic behind it, folded away until asked for. */
@Composable
private fun WorkoutEnergyCard(calories: Double, met: Double) {
    var open by remember { mutableStateOf(false) }
    // The chevron is a picture, so the card says in words whether the arithmetic is showing, and
    // offers the screen reader's own expand and collapse actions rather than a bare "button".
    val expanded = stringResource(R.string.a11y_state_expanded)
    val collapsed = stringResource(R.string.a11y_state_collapsed)
    PwnCard(
        Modifier.fillMaxWidth().semantics {
            stateDescription = if (open) expanded else collapsed
            if (open) {
                collapse {
                    open = false
                    true
                }
            } else {
                expand {
                    open = true
                    true
                }
            }
        },
        onClick = { open = !open },
    ) {
        Column(Modifier.padding(PwnDimensions.cardPadding)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.entry_energy, calories),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = PwnMuted,
                )
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.editor_met_calculation, met),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
            }
        }
    }
}

@Composable
private fun SummarySetLine(index: Int, set: PlannedSet, spec: ActivitySpec, loadUnitRes: Int, record: Boolean = false) {
    // A line of the report, not a control: one stop for a screen reader.
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetBadge(index)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.workout_exercise_set, stringResource(spec.titleRes), index + 1),
                fontWeight = FontWeight.Bold,
            )
            val detail = setDetailLine(set, spec, loadUnitRes)
            if (detail.isNotEmpty()) {
                Text(detail, color = PwnMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (record) {
            RecordBadge(Modifier.padding(end = 10.dp))
        }
        Text(set.actualSeconds.elapsedClockText(), style = MaterialTheme.typography.bodyMedium, color = PwnMuted)
    }
}

@Composable
private fun SummaryRestLine(restSeconds: Int) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Schedule, null, tint = PwnMuted, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.workout_rest),
            Modifier.weight(1f),
            color = PwnMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(restSeconds.elapsedClockText(), color = PwnMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SummaryStatsRow(
    spec: ActivitySpec,
    duration: String,
    sets: String,
    reps: String,
    load: String,
    loadUnitRes: Int,
    plannedSets: List<PlannedSet>,
) {
    val loadUnit = stringResource(loadUnitRes)
    val minutesUnit = stringResource(R.string.unit_minutes)
    // A pair is one block of work, so the tiles count the block. The editor's own fields hold leg
    // A alone — they become that row's columns — and reading them here would report half a session.
    val whole = plannedSets.takeIf { it.isNotEmpty() }?.aggregate()
    val minutes = whole?.durationMinutes ?: duration.number()
    val sets = whole?.sets?.takeIf { it > 0 }?.toString() ?: sets
    val reps = whole?.reps?.takeIf { it > 0 }?.toString() ?: reps
    val tiles = buildList {
        if (sets.intNumber() > 0) {
            // The exercise's own drawing, the one the picker showed when this session was chosen.
            // The tile counts its sets, but the first picture on the page should be of the thing
            // that was actually done — a generic dumbbell over a set of squats would answer a
            // question nobody asked.
            add(SummaryStat(stringResource(R.string.editor_sets), sets, spec.art()))
        }
        when (spec.input) {
            ActivityInput.WEIGHTED_SETS -> {
                if (reps.isNotBlank()) {
                    add(SummaryStat(stringResource(R.string.editor_reps), reps, art = R.drawable.ic_art_barbell))
                }
                val volume = plannedSets.filter { it.completed }.sumOf { it.reps.intNumber() * it.load.number() }
                if (volume > 0) {
                    add(
                        SummaryStat(
                            stringResource(R.string.workout_volume),
                            "${volume.shown()} $loadUnit",
                            art = R.drawable.ic_art_weight,
                        ),
                    )
                }
            }

            ActivityInput.DURATION -> Unit
        }
        if (minutes > 0) {
            add(
                SummaryStat(
                    stringResource(R.string.editor_time),
                    "${minutes.shown()} $minutesUnit",
                    art = R.drawable.ic_art_time,
                ),
            )
        }
    }.take(3)
    if (tiles.isEmpty()) return
    PwnCard(Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            tiles.forEachIndexed { index, tile ->
                if (index > 0) VerticalDivider(Modifier.padding(vertical = 14.dp), color = PwnBorder)
                StatTile(tile, Modifier.weight(1f))
            }
        }
    }
}

/**
 * One tile of that row: the number, the word under it, and the picture above them both.
 *
 * Every tile is a drawing — a flexed bicep for the reps, a kettlebell marked in kilograms for the
 * volume, a clock for the time, and for the sets the exercise itself — with no line icon among them
 * for the eye to trip over, so [art] is simply what a tile is made of.
 */
private data class SummaryStat(
    val label: String,
    val value: String,
    val art: Int,
)

@Composable
private fun StatTile(stat: SummaryStat, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(themePainterResource(stat.art), null, Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        // The figure wraps — its unit drops under the number — rather than ending in an ellipsis:
        // a tile is a third of the card, little room for a volume and its unit, and at a large
        // font an ellipsis would be all that was left of the session's totals.
        Text(
            stat.value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            stat.label,
            style = MaterialTheme.typography.bodySmall,
            color = PwnMuted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The salute over a finished workout.
 *
 * A firework rather than a scatter of dots: shells that climb before they open, stars that
 * decelerate and fall and cool from a bright core to an ember. [drawSalute] is the drawing; all
 * this supplies is the box to fire over and the colours to fire in.
 *
 * One float runs the whole display, so the same second is the same picture every time — nothing
 * here is random and nothing is kept between plays.
 */
@Composable
internal fun WorkoutSalute(modifier: Modifier, running: Boolean) {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (running) clock.animateTo(1f, tween(durationMillis = SALUTE_MILLIS, easing = LinearEasing))
    }
    // The app's own accents rather than a firework palette: this is the brand celebrating, and a
    // shell that burns from indigo down to gold reads as this app's rather than as a stock burst.
    val shells = listOf(
        PwnIndigo to PwnOrange,
        PwnGreen to PwnIndigo,
        PwnPurple to PwnOrange,
        PwnOrange to PwnIndigo,
    )
    // Applause, and nothing but: every figure it is fired over is already on the summary in words,
    // so the salute is kept out of the accessibility tree rather than left silent by accident.
    Canvas(modifier.clearAndSetSemantics {}) {
        drawSalute(clock.value, SaluteBox(size.width, size.height), shells)
    }
}

/** Long enough for six shells to climb, open and cool. */
private const val SALUTE_MILLIS = 2200
