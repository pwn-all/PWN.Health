package com.pwnall.health

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The pages of the templates screen, each standing in the same window in place of the last. */
private enum class RoutinePage { LIST, VIEW, EDIT, PLAY, FREE, LOG }

/**
 * Saved sessions: the list of them, a template's own page, and the pages that write one, play one,
 * run a session with no plan or log a workout already done — all in the one window, each page
 * standing in for the last.
 *
 * Start runs a template against the clock ([RoutinePlayer]); Log writes it as done exactly as planned
 * ([logRoutine]). Either way the result is ordinary journal entries — editable, deletable, exported
 * like everything else — with what fell short of the plan kept beside the row ([shortfallJson]) and an
 * exercise never begun written as a row that says so ([KIND_NOT_DONE]).
 */
@Composable
fun RoutinesScreen(
    modifier: Modifier,
    profile: UserProfile,
    journal: String? = null,
    onWalk: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val db = remember { StepsDb.get(context) }
    val dao = remember { db.dao() }
    val scope = rememberCoroutineScope()
    // Null until the first read, so the list does not say "no templates yet" on the way to showing them.
    val routines by remember { dao.routines() }.collectAsStateWithLifecycle(null)
    // Null until its own first read as well, which lands apart from the templates': "not read yet" is
    // not "none", and a session rebuilt after a turn of the phone must not be drawn over the second.
    val exercises by remember { dao.allRoutineExercises() }.collectAsStateWithLifecycle(null)
    // A workout told to the chat opens straight on Freestyle's page for logging it, and leaves with it.
    // Taken once: the page is still drawn on its way out, after the caller has let the journal go.
    val taken by rememberSaveable { mutableStateOf(journal) }
    val seed = remember(taken) { journalPrefill(taken) }
    var page by rememberSaveable { mutableStateOf(if (seed != null) RoutinePage.LOG else RoutinePage.LIST) }
    var openId by rememberSaveable { mutableLongStateOf(0L) }
    // Every flow opened is a new one: "add another" after a save starts on empty pages, not on the
    // answers of the template just written.
    var flowRun by rememberSaveable { mutableIntStateOf(0) }
    // The template the bin was tapped on, held until the dialog has its answer: it goes with every
    // exercise in it and its picture, and that is not a thing one tap should do.
    var deleting by remember { mutableStateOf<Routine?>(null) }
    var planning by remember { mutableStateOf(false) }
    // Log writes the whole template as done, every set of it; one tap on the wrong button should not.
    var confirmingLog by rememberSaveable { mutableStateOf(false) }
    var status by rememberStatusLine()
    // Above the pages that stand in for the list, so that back from one the list is where it was left.
    val listScroll = rememberScrollState()
    val open = routines?.firstOrNull { it.id == openId }
    val openRows = open?.let { exercisesOf(exercises, it.id) }

    fun rowsOf(id: Long) = exercisesOf(exercises, id).orEmpty()
    fun startFlow(id: Long) {
        openId = id
        flowRun++
        page = RoutinePage.EDIT
    }
    fun startFreestyle() {
        flowRun++
        page = RoutinePage.FREE
    }

    // A template gone from under its page — deleted from the list's bin while the page was in the
    // back of the trail, say — leaves the list showing rather than an empty page.
    LaunchedEffect(routines, page, openId) {
        val showsOne = page == RoutinePage.VIEW || page == RoutinePage.PLAY
        if (showsOne && routines != null && open == null) page = RoutinePage.LIST
    }
    BackHandler(enabled = page == RoutinePage.VIEW) { page = RoutinePage.LIST }

    when {
        page == RoutinePage.EDIT -> key(flowRun) {
            val editing = open?.takeIf { openId != 0L }
            RoutineFlow(
                modifier = modifier,
                editing = editing,
                rows = rowsOf(openId),
                profile = profile,
                onLeave = { page = if (editing != null) RoutinePage.VIEW else RoutinePage.LIST },
                onOpen = { id ->
                    openId = id
                    page = RoutinePage.VIEW
                },
                onAnother = { startFlow(0L) },
            )
        }

        // The session itself, in the same window: back, or Done once it has ended, returns to the
        // template's page. Not before its exercises are read: a session restored onto a template with
        // none in it finds nothing left to do, and closes with nothing written.
        page == RoutinePage.PLAY && open != null && openRows != null -> RoutinePlayer(
            modifier = modifier,
            routine = open,
            rows = openRows,
            imperial = profile.imperial,
            onLog = { outcomes -> logSession(context, db, profile, outcomes, session = open.name) },
            onClose = { page = RoutinePage.VIEW },
        )

        page == RoutinePage.FREE -> key(flowRun) {
            FreestyleSession(
                modifier = modifier,
                imperial = profile.imperial,
                sex = profile.sex,
                restingKcalPerHour = profile.restingKcalPerHour(),
                onLog = { outcomes ->
                    val freestyle = resources.getString(R.string.routines_freestyle)
                    logSession(context, db, profile, outcomes, session = freestyle)
                },
                onWalk = onWalk,
                onClose = { page = RoutinePage.LIST },
            )
        }

        page == RoutinePage.LOG && seed != null -> {
            val day = seed.prefillDay(LocalDate.now()) ?: LocalDate.now()
            FreestyleLog(
                modifier = modifier,
                seed = seed,
                day = day,
                imperial = profile.imperial,
                sex = profile.sex,
                onLog = { outcomes, note -> logSession(context, db, profile, outcomes, day, note) },
                onClose = onBack,
            )
        }

        page == RoutinePage.VIEW && open != null -> Box(modifier.fillMaxSize()) {
            RoutineViewPage(
                routine = open,
                rows = rowsOf(open.id),
                imperial = profile.imperial,
                restingKcalPerHour = profile.restingKcalPerHour(),
                bodyKg = profile.weightKg,
                onBack = { page = RoutinePage.LIST },
                onShare = {
                    val rows = rowsOf(open.id)
                    val text = routineShareText(resources, open, rows, profile.imperial, locale)
                    scope.launch { context.shareRoutine(open, rows, text) }
                },
                onEdit = { startFlow(open.id) },
                onDelete = { deleting = open },
                onPlay = { page = RoutinePage.PLAY },
                onLog = { confirmingLog = true },
                onPlan = { planning = true },
            )
            // Over the page rather than at the end of its scroll: both notices answer a button at the
            // foot of the page, and on a long template the foot is where the eye already is not.
            PwnActionFlash(status)
        }

        else -> Box(modifier.fillMaxSize()) {
            RoutineListPage(
                routines = routines,
                exercises = exercises.orEmpty(),
                scroll = listScroll,
                onBack = onBack,
                onAdd = { startFlow(0L) },
                onView = {
                    openId = it.id
                    page = RoutinePage.VIEW
                },
                onFreestyle = ::startFreestyle,
                onMachine = { result ->
                    scope.launch {
                        withContext(NonCancellable) {
                            db.saveJournalEvent(machineEntry(resources, profile, result))
                            writeLocalHealthIfAvailable(context, db)
                        }
                        status = resources.getQuantityString(R.plurals.routines_started, 1, 1)
                    }
                },
            )
            PwnActionFlash(status)
        }
    }

    deleting?.let { routine ->
        DestructiveDialog(
            title = stringResource(R.string.routines_delete),
            // Its name, and what goes with it: the days it is planned for do, the sessions logged stay.
            body = stringResource(R.string.routines_delete_body, routine.name),
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                if (page == RoutinePage.VIEW) page = RoutinePage.LIST
                scope.launch { db.deleteRoutineWithPlans(context, routine) }
            },
        )
    }

    if (confirmingLog) {
        AlertDialog(
            onDismissRequest = { confirmingLog = false },
            title = { Text(stringResource(R.string.routines_log_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLog = false
                    open?.let { routine ->
                        scope.launch {
                            // The answer was yes, so the session is written: leaving the page between
                            // the read of the template and the write does not let it go.
                            val logged = withContext(NonCancellable) { startRoutine(context, db, profile, routine) }
                            status = resources.getQuantityString(R.plurals.routines_started, logged, logged)
                        }
                    }
                }) { Text(stringResource(R.string.common_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (planning) {
        PlanDateDialog(onDismiss = { planning = false }) { day ->
            planning = false
            val plan = open ?: return@PlanDateDialog
            scope.launch {
                withContext(NonCancellable) {
                    // The same date asked for a second time is planned once: a second row is a second
                    // workout on the calendar's day and a second planned session in Health Connect.
                    if (!dao.journalOnNow(day.toString()).holdsPlanOf(plan.id)) {
                        db.saveJournalEvent(planEntry(plan, rowsOf(plan.id), day))
                    }
                }
                // The date as the phone's language writes one, not as the journal keys it.
                val date = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
                status = resources.getString(R.string.routines_planned, date)
            }
        }
    }
}

/**
 * Deletes [routine] with the plans made from it — a date nobody can start it from any more, and a
 * morning reminder naming a template that is gone — as one act, which leaving the page does not cut
 * off: the deletion was confirmed. One transaction, so a process that dies half-way leaves no plan of
 * a template that is gone on the calendar; and the reminders and the widgets are worked out again
 * once after it rather than once a plan, each plan's own [deleteJournalEvent] standing its refreshes
 * down inside it. The picture after the rows, as a course's goes: a row still naming it keeps it.
 */
internal suspend fun StepsDb.deleteRoutineWithPlans(context: Context, routine: Routine) {
    withContext(NonCancellable) {
        withTransaction {
            dao().deleteRoutine(routine.id)
            dao().journalAround(KIND_PLAN, Long.MIN_VALUE, Long.MAX_VALUE)
                .filter { it.planRoutineId() == routine.id }
                .forEach { deleteJournalEvent(it.id) }
        }
        refreshMedicineAfterWrite()
        refreshWidgetsAfterWrite()
        withContext(Dispatchers.IO) { routinePhotoFile(context, routine.photo)?.delete() }
    }
}

/**
 * The workouts page: the templates, each a row of its own — its picture in a
 * square at the start, its name and few words — opening on a tap, those meant for today first and
 * ringed by spreading waves; under a rule, Freestyle, a session with no plan; the round "+" in the
 * corner that writes a new template; and in the top corner the Bluetooth sign that takes a training
 * machine's numbers, in the corner a "?" about templates would otherwise take.
 */
@Composable
private fun RoutineListPage(
    routines: List<Routine>?,
    exercises: List<RoutineExercise>,
    scroll: ScrollState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onView: (Routine) -> Unit,
    onFreestyle: () -> Unit,
    onMachine: (MachineResult) -> Unit,
) {
    val context = LocalContext.current
    var machineOpen by rememberSaveable { mutableStateOf(false) }
    val today = rememberToday().dayOfWeek.routineBit()
    // Read as the list comes up, not at every redraw: the session can only change on Freestyle's own
    // page, and the list is built again on the way back from it.
    val freestyleSince = remember { context.savedFreestyle().sessionStart }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PwnTopBar(stringResource(R.string.home_workouts), onBack) {
                IconButton(
                    onClick = { machineOpen = true },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                ) {
                    Icon(Icons.Outlined.Bluetooth, stringResource(R.string.machine_connect))
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(scroll).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The machine's live card stands over the list while a link is up, as it stands under the
                // exercise in the workout form; its numbers are saved as today's workout.
                MachineSection(open = machineOpen, onOpen = { machineOpen = it }, onResult = onMachine)
                Spacer(Modifier.height(10.dp))
                if (routines?.isEmpty() == true) {
                    Text(stringResource(R.string.routines_empty), color = PwnMuted)
                }
                // Stable, so the rest keep the order they were written in. Keyed, so a row deleted
                // above them does not hand the rows below its picture to decode again.
                routines.orEmpty().sortedByDescending { it.days and today != 0 }.forEach { routine ->
                    key(routine.id) {
                        RoutineRow(
                            routine = routine,
                            rows = exercises.filter { it.routineId == routine.id },
                            today = routine.days and today != 0,
                            onView = { onView(routine) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                FreestyleRow(freestyleSince, onFreestyle)
                // The last row scrolls clear of the round button rather than ending under it.
                Spacer(Modifier.height(FAB_CLEARANCE))
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Outlined.Add, stringResource(R.string.routines_create))
        }
    }
}

/**
 * One template on the list, which opens on a tap anywhere on it — the eye and the bin live on the
 * template's own page, in its menu. Its name is a heading, so
 * a screen reader moves template by template; the chevron says the row opens a page, as rows do
 * across the app. A template for [today] is ringed by waves, as the home screen's chat is.
 */
@Composable
private fun RoutineRow(routine: Routine, rows: List<RoutineExercise>, today: Boolean, onView: () -> Unit) {
    val context = LocalContext.current
    val wave = if (today) rememberWave() else null
    val ring = PwnIndigo
    PwnCard(
        Modifier.fillMaxWidth().then(
            if (wave == null) Modifier else Modifier.drawBehind { waveRings(wave.floatValue, ring) },
        ),
        onClick = onView,
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoutinePicture(routinePhotoFile(context, routine.photo), 0, ROW_PICTURE_SIZE)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    routine.name,
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    routine.description.ifEmpty { routineCountText(rows) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PwnMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = PwnMuted)
        }
    }
}

/**
 * Freestyle under the rule: laid out as a template's row, the workout drawing in its square — and, while
 * a session begun at [since] is still open, its clock where the chevron stands, since a tap goes back to it.
 */
@Composable
private fun FreestyleRow(since: Long, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (since >= 0) {
            now = System.currentTimeMillis()
            delay(1_000L - (now - since).mod(1_000L))
        }
    }
    PwnCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(ROW_PICTURE_SIZE).clip(RoundedCornerShape(PwnCorners.large)).background(PwnPurpleSoft),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_art_sport), null, Modifier.size(ROW_PICTURE_SIZE - 20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.routines_freestyle),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.routines_freestyle_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PwnMuted,
                )
            }
            if (since >= 0) {
                SessionClock(now - since)
            } else {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = PwnMuted)
            }
        }
    }
}

/**
 * The waves' time, 0 to 1 over [WAVE_MS] each time the page comes up, then still at 1 — once, not in a
 * loop, so a page left open does not keep twitching in the corner of the eye. Off with the phone's
 * animations.
 */
@Composable
private fun rememberWave(): MutableFloatState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val wave = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(lifecycle) {
        if (!context.animationsOn()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                animate(0f, 1f, animationSpec = tween(WAVE_MS, easing = LinearEasing)) { value, _ ->
                    wave.floatValue = value
                }
            } finally {
                wave.floatValue = 1f // a pause in the middle must not leave a ring frozen round the card
            }
        }
    }
    return wave
}

/** [WAVES] rounded rings spreading [WAVE_REACH] out from the card's edge and fading, one after another. */
private fun DrawScope.waveRings(wave: Float, color: Color) {
    val corner = PwnCorners.extraLarge.toPx()
    repeat(WAVES) { i ->
        val t = (wave - i / (WAVES + 1f)) * 2
        if (t > 0f && t < 1f) {
            val grow = WAVE_REACH.toPx() * t
            drawRoundRect(
                color.copy(alpha = (1 - t) / 2),
                topLeft = Offset(-grow, -grow),
                size = Size(size.width + 2 * grow, size.height + 2 * grow),
                cornerRadius = CornerRadius(corner + grow),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

/**
 * Template [id]'s rows out of every template's [exercises], or null while those are still unread. The
 * templates and their exercises are two reads that land apart, and after a turn of the phone the page
 * is rebuilt in between: a session handed "none" for "not read yet" would have nothing left to do,
 * end itself with nothing done and close, and what had been done of it would be gone.
 */
internal fun exercisesOf(exercises: List<RoutineExercise>?, id: Long): List<RoutineExercise>? =
    exercises?.filter { it.routineId == id }

/**
 * A training machine's numbers as today's workout, taken from the workouts page where no exercise is
 * chosen: the machine says what it is, and a treadmill under walking pace is a walk.
 */
internal fun machineEntry(resources: Resources, profile: UserProfile, result: MachineResult): JournalEntry {
    val code = when (result.type) {
        FitnessMachineType.TREADMILL -> if ((result.kph ?: Double.MAX_VALUE) < WALK_TOP_KPH) "WALK" else "RUN"

        FitnessMachineType.INDOOR_BIKE -> "CYCLING"

        // Concept2's monitor on a machine it does not name: most of them sit on a rower.
        FitnessMachineType.ROWER, FitnessMachineType.UNKNOWN -> "ROWING_MACHINE"
    }
    val spec = ACTIVITY_CATALOG.first { it.code == code }
    return JournalEntry(
        day = LocalDate.now().toString(),
        occurredAt = System.currentTimeMillis(),
        kind = KIND_ACTIVITY,
        title = resources.getString(spec.titleRes),
        // The console measured the work; the MET figure only estimates a population's average.
        calories =
        result.kcal?.toDouble() ?: netActivityCalories(spec.met, profile.restingKcalPerHour(), result.minutes),
        activityCode = spec.code,
        met = spec.met,
        durationMinutes = result.minutes,
        note = result.note,
        measurementPayloadJson = result.payloadJson.ifBlank { null },
    )
}

/**
 * Whether this workout's energy is a console's own count: its payload is a machine's frame
 * ([isMachinePayload]) that reported kilocalories ([fitnessPayload]). [machineEntry] and the exercise
 * form both file such a row under the catalogue's exercise — a run, a ride — with that exercise's MET
 * beside it, so the frame is all that says the figure was measured: it is not the MET estimate, and
 * the pass that works the estimates out again ([recalculatedActivityTimeline]) must leave it be.
 */
internal fun JournalEntry.hasMachineEnergy(): Boolean {
    val frame = measurementPayloadJson?.takeIf { kind == KIND_ACTIVITY && it.isMachinePayload() } ?: return false
    return runCatching { JSONObject(frame).optInt("totalEnergyKcal") > 0 }.getOrDefault(false)
}

/**
 * A template as words, for whatever it is sent to: its name, its few words and its days, then each
 * step numbered with the rest after it, a superset's exercises one per line under it.
 */
internal fun routineShareText(
    resources: Resources,
    routine: Routine,
    rows: List<RoutineExercise>,
    imperial: Boolean,
    locale: Locale,
): String = buildString {
    appendLine(routine.name)
    if (routine.description.isNotEmpty()) appendLine(routine.description)
    if (routine.days != 0) appendLine(routineDaysText(routine.days, locale))
    val blocks = routineBlocks(rows)
    blocks.forEachIndexed { index, block ->
        appendLine()
        val line = { exercise: RoutineExercise ->
            "${routineExerciseName(resources, exercise)} — ${routineExerciseSummary(resources, exercise, imperial)}"
        }
        if (block.superset) {
            appendLine("${index + 1}. ${resources.getString(R.string.workout_superset)}")
            block.exercises.forEach { appendLine("   • ${line(it)}") }
        } else {
            block.exercises.forEach { appendLine("${index + 1}. ${line(it)}") }
        }
        if (index < blocks.lastIndex && block.restSeconds > 0) {
            appendLine("   ${resources.getString(R.string.workout_rest)} — ${block.restSeconds.elapsedClockText()}")
        }
    }
}.trimEnd()

/**
 * The template handed to the share sheet as a file the app on another phone imports, from the corner
 * of the first page of adding one ([routineFileText]), its picture inside it — and the same template
 * as words beside the file, for whoever it reaches without the app. The file goes into a folder of the
 * template's own in the cache, as a shared walk's does. Nothing is uploaded: the app holds no
 * INTERNET permission, and where it goes is the app the person picks. A file that cannot be written
 * is logged and the sheet not opened; failing to share is not worth taking the page down for.
 */
private suspend fun Context.shareRoutine(routine: Routine, rows: List<RoutineExercise>, text: String) {
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val jpeg = routinePhotoFile(this@shareRoutine, routine.photo)?.takeIf(File::isFile)?.readBytes()
            val folder = File(File(cacheDir, ROUTINE_SHARE_DIR), routine.id.toString()).apply { mkdirs() }
            File(folder, walkShareName(routine.name, routine.id, "json")).apply {
                writeText(routineFileText(routine, rows, jpeg))
            }
        }.onFailure { Log.w("RoutinesScreen", "the template file could not be written", it) }.getOrNull()
    } ?: return
    val uri = FileProvider.getUriForFile(this, "$packageName.photos", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, routine.name)
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_STREAM, uri)
        // The clip carries the URI a second time: it is what the chooser reads to preview the file.
        clipData = ClipData.newUri(contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, getString(R.string.achievement_share)))
}

/**
 * A plan is an ordinary journal row on a future day, pointing back at the template. No new table:
 * the journal already carries days, titles and durations, and nothing else needs to know.
 */
internal fun planEntry(routine: Routine, exercises: List<RoutineExercise>, day: LocalDate) = JournalEntry(
    day = day.toString(),
    occurredAt = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    kind = KIND_PLAN,
    title = routine.name,
    calories = 0.0,
    durationMinutes = exercises.sumOf { it.durationMinutes },
    measurementPayloadJson = JSONObject().put(PLAN_ROUTINE_KEY, routine.id).toString(),
)

/** Whether a day's rows already hold a plan made from the template [routineId]. */
internal fun List<JournalEntry>.holdsPlanOf(routineId: Long): Boolean =
    any { it.kind == KIND_PLAN && it.planRoutineId() == routineId }

/**
 * The plans among [plans] made from [routine], for [today] or later, that no longer say what it is —
 * its name, its [exercises]' minutes — as they now read. A plan keeps both as they stood when it was
 * made ([planEntry]), and the calendar, the morning's reminder and Health Connect read them from the
 * plan: so a template renamed or rewritten takes the dates ahead of it along ([saveRoutine]). A day
 * gone by keeps what was planned for it then.
 */
internal fun plansFollowing(
    plans: List<JournalEntry>,
    routine: Routine,
    exercises: List<RoutineExercise>,
    today: String,
): List<JournalEntry> {
    val minutes = exercises.sumOf { it.durationMinutes }
    return plans
        .filter { it.planRoutineId() == routine.id && it.day >= today }
        .filter { it.title != routine.name || it.durationMinutes != minutes }
        .map { it.copy(title = routine.name, durationMinutes = minutes) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanDateDialog(onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = rememberToday()
    val state = rememberDatePickerState(
        selectableDates = object : SelectableDates {
            // A plan is for today or a day to come: the reminder goes out on its morning, and a day
            // gone by has no morning left.
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today.toPickerMillis()

            override fun isSelectableYear(year: Int) = year >= today.year
        },
    )
    LocalDateDialog(state, onDismiss, onPick, confirm = R.string.routines_plan)
}

/** Turns a whole template into today's journal ([logRoutine]): the session it describes has happened. */
internal suspend fun startRoutine(
    context: Context,
    db: StepsDb,
    profile: UserProfile,
    routine: Routine,
): Int = logRoutine(context, db, profile, db.dao().routineExercises(routine.id), routine.name)

/**
 * Turns [exercises] of a template into today's journal as done exactly as planned — what Log writes
 * for a session already done ([logSession]) — as one [session] of that name.
 */
internal suspend fun logRoutine(
    context: Context,
    db: StepsDb,
    profile: UserProfile,
    exercises: List<RoutineExercise>,
    session: String? = null,
): Int = logSession(context, db, profile, exercises.map(::plannedOutcome), session = session)

/** A workout session's name in the journal, "Freestyle #12": the rows' [JournalEntry.subtitle]. */
internal fun sessionTitle(name: String, number: Int) = "$name #$number"

/** The name may hold anything, a line break too: one an older file or a backup brought is still its sessions' name. */
private val SESSION_TITLE = Regex("(.+) #([0-9]+)", RegexOption.DOT_MATCHES_ALL)

/** The number after the highest of [taken] — the sessions named [name] so far — or 1 for the first. */
internal fun nextSessionNumber(taken: List<String>, name: String): Int =
    (
        taken.mapNotNull { title ->
            SESSION_TITLE.matchEntire(title)?.groupValues?.takeIf { it[1] == name }?.get(2)?.toIntOrNull()
        }.maxOrNull() ?: 0
        ) + 1

/**
 * The session this workout row was done in — "Freestyle #12", "Push day #3" — or null for one logged
 * on its own. A session's rows are one workout to the calendar. A row imported from Health Connect
 * is never one of a session here, whatever its subtitle says.
 */
internal val JournalEntry.session: String?
    get() = subtitle.takeIf {
        (kind == KIND_ACTIVITY || kind == KIND_NOT_DONE) &&
            activityCode?.startsWith("HEALTH_CONNECT") != true &&
            SESSION_TITLE.matches(it)
    }

/**
 * Writes a session's [outcomes] into the journal, one entry per exercise ([sessionRows]), and reports
 * how many exercises were done.
 *
 * One act of writing, as a superset's two rows are ([saveSupersetEvent]): the rows land together or
 * not at all, and once begun the write is not cut off by the page that asked for it going away. A turn
 * of the phone or a step back in the middle of it would otherwise leave the session's first exercises
 * in the journal and the rest nowhere.
 */
internal suspend fun logSession(
    context: Context,
    db: StepsDb,
    profile: UserProfile,
    outcomes: List<RoutineOutcome>,
    day: LocalDate = LocalDate.now(),
    note: String = "",
    session: String? = null,
): Int = withContext(NonCancellable) {
    // An earlier day's at its noon ([dayInstant]): Health Connect files a row by the moment, not the day.
    // Read before the wait for the database, which is no part of the session.
    val finishedAt = dayInstant(day.toString())
    val rows = db.withTransaction {
        val dao = db.dao()
        // Every row of a [session] carries its name and number, which the calendar folds them by.
        val subtitle = session?.let { sessionTitle(it, nextSessionNumber(dao.sessionTitles(it), it)) }.orEmpty()
        sessionRows(context.resources, profile, outcomes, day, finishedAt, subtitle, note)
            .onEach { dao.saveJournalEntry(it) }
    }
    // Written row by row through the DAO, so the widgets are redrawn once here rather than once a row.
    db.refreshWidgetsAfterWrite()
    writeLocalHealthIfAvailable(context, db)
    rows.count { it.kind == KIND_ACTIVITY }
}

/**
 * A session's [outcomes] as journal rows, one per exercise in the order they were done: each written
 * as it went — the minutes it actually ran, its own sets' rests included, and the energy of those
 * minutes alone; one left short says what it was planned at ([shortfallJson]); one never done is a row
 * that says so ([KIND_NOT_DONE]), which counts as no workout anywhere a workout is counted.
 *
 * The session ended at [finishedAt], so the last row is stamped there and each one before it where the
 * next began, as the editor stamps a session of its own. [sessionInterval] runs a row back from its
 * stamp by its minutes: stamped together at the finish, a second apart, every exercise would claim the
 * session's closing minutes, and Health Connect would be handed them lying one on top of another, all
 * but the first ending after Finish was tapped. Every row is filed under [day], the day the session was
 * finished on, however far back its first exercise began.
 *
 * A template can outlive the catalog entry it names — a release that drops or renames an activity
 * code leaves the stored exercise unreadable — and such a row writes nothing.
 *
 * Calories use the same net figure a hand-entered workout would, so a session logged from a template
 * and the same session typed in by hand come out identical.
 */
internal fun sessionRows(
    resources: Resources,
    profile: UserProfile,
    outcomes: List<RoutineOutcome>,
    day: LocalDate,
    finishedAt: Long,
    subtitle: String = "",
    note: String = "",
): List<JournalEntry> {
    val dayKey = day.toString()
    val factor = loadUnitFactor(profile.imperial)
    var endAt = finishedAt
    return outcomes.asReversed().mapNotNull { outcome ->
        val exercise = outcome.exercise
        val spec = ACTIVITY_CATALOG.firstOrNull { it.code == exercise.activityCode } ?: return@mapNotNull null
        val title = resources.getString(spec.titleRes)
        val row = if (outcome.done.isEmpty()) {
            JournalEntry(
                day = dayKey,
                occurredAt = endAt,
                kind = KIND_NOT_DONE,
                title = title,
                subtitle = subtitle,
                calories = 0.0,
                activityCode = spec.code,
            )
        } else {
            // An exercise planned set by set is written as a session writes one: each set in the payload,
            // the editor reopening it set by set, and the columns the sum a session's columns are.
            val sets = doneSets(outcome, factor)
            val minutes = sets.sumOf { it.actualSeconds + it.actualRestSeconds } / 60.0
            val whole = sets.takeIf { exercise.setsJson.isNotEmpty() }?.aggregate()
            val payload = whole?.let { sets.encodeToJson(loadUnitFactor = factor, preserveTrailingRest = true) }
            val loadKg = if (whole != null) (whole.load / factor).takeIf { it > 0.0 } else exercise.loadKg
            val costedMet = loadedMet(spec.code, spec.met, profile.weightKg, loadKg)
            JournalEntry(
                day = dayKey,
                occurredAt = endAt,
                kind = KIND_ACTIVITY,
                title = title,
                subtitle = subtitle,
                calories = netActivityCalories(costedMet, profile.restingKcalPerHour(year = day.year), minutes),
                activityCode = spec.code,
                met = spec.met,
                durationMinutes = minutes,
                sets = whole?.sets ?: exercise.sets,
                reps = if (whole != null) whole.reps.takeIf { it > 0 } else exercise.reps,
                loadKg = loadKg,
                measurementPayloadJson = shortfallJson(payload, outcome),
                note = note,
            )
        }
        // A row with no minutes, one not done, still takes a second: the journal keeps the plan's order.
        endAt -= ((row.durationMinutes ?: 0.0) * 60_000).toLong().coerceAtLeast(1_000L)
        row
    }.asReversed()
}

/**
 * The sets [outcome] did, as a row stores them, its loads in the profile's unit ([factor]). The rest
 * before the next exercise is the last set's, as a rest between its own sets is: a session is one
 * workout, its rests included. A climb's floors go with its steps: asked about and corrected at the
 * end of each set, they are written with the row rather than left on the session's last page.
 */
private fun doneSets(outcome: RoutineOutcome, factor: Double): List<PlannedSet> = outcome.done.mapIndexed { at, set ->
    PlannedSet(
        reps = set.repsDone.takeIf { it > 0 }?.toString().orEmpty(),
        load = set.figures?.loadKg?.let { (it * factor).toString() }.orEmpty(),
        completed = true,
        actualSeconds = set.seconds,
        actualRestSeconds = set.restAfter + if (at == outcome.done.lastIndex) outcome.restAfter else 0,
        floors = set.figures?.floors ?: 0,
    )
}

/**
 * [payload] with what the plan was, where [outcome] fell short of it — fewer sets done, fewer reps,
 * or fewer seconds held — so the journal can say "3 min of 5 min" or "Goal not reached · Repetitions
 * 16 of 27" ([routineShortfallText]). An exercise done in full keeps its payload exactly as a session
 * would write it. A set of reps is timed by the person, not the plan, so only a timed exercise is
 * measured against its seconds.
 */
private fun shortfallJson(payload: String?, outcome: RoutineOutcome): String? {
    val doneSeconds = outcome.done.sumOf { it.seconds }
    val doneReps = outcome.done.sumOf { it.repsDone }
    val short = outcome.done.size < outcome.plannedSets ||
        doneReps < outcome.plannedReps ||
        (outcome.plannedReps == 0 && doneSeconds < outcome.plannedSeconds)
    if (!short) return payload
    return (payload?.let(::JSONObject) ?: JSONObject())
        .put(PLANNED_SETS_KEY, outcome.plannedSets)
        .put(DONE_SETS_KEY, outcome.done.size)
        .put(PLANNED_SECONDS_KEY, outcome.plannedSeconds)
        .put(DONE_SECONDS_KEY, doneSeconds)
        .put(PLANNED_REPS_KEY, outcome.plannedReps)
        .put(DONE_REPS_KEY, doneReps)
        .toString()
}

/**
 * What a row a template session left short says about it: "3 min of 5 min" for one held for a time,
 * "Goal not reached · Repetitions 16 of 27" for one counted in reps; null for a row done in full or
 * not from a session.
 */
@Composable
internal fun JournalEntry.routineShortfallText(): String? {
    val json = measurementPayloadJson?.takeIf { it.contains(PLANNED_SETS_KEY) } ?: return null
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val plannedReps = root.optInt(PLANNED_REPS_KEY)
    return if (plannedReps > 0) {
        "${stringResource(R.string.routines_goal_missed)} · ${stringResource(R.string.editor_reps)} " +
            stringResource(R.string.routines_of, root.optInt(DONE_REPS_KEY), plannedReps)
    } else {
        val resources = LocalResources.current
        stringResource(
            R.string.routines_done_of,
            durationText(resources, root.optInt(DONE_SECONDS_KEY)),
            durationText(resources, root.optInt(PLANNED_SECONDS_KEY)),
        )
    }
}

/** The keys a row left short carries beside its sets. */
private const val PLANNED_SETS_KEY = "plannedSets"
private const val DONE_SETS_KEY = "doneSets"
private const val PLANNED_SECONDS_KEY = "plannedSeconds"
private const val DONE_SECONDS_KEY = "doneSeconds"
private const val PLANNED_REPS_KEY = "plannedReps"
private const val DONE_REPS_KEY = "doneReps"

/**
 * Where a shared template's file is written, a folder per template, in the cache's shared folder the
 * FileProvider serves ([SHARED_CACHE_DIR]); the next start empties it.
 */
internal const val ROUTINE_SHARE_DIR = "$SHARED_CACHE_DIR/routine-share"

/** A treadmill at a pace under this is walked rather than run. */
private const val WALK_TOP_KPH = 6.5

/** The waves round today's template: as the home screen's chat has them. */
private const val WAVE_MS = 2_000
private const val WAVES = 3
private val WAVE_REACH = 10.dp

/** Room under the list for the round button, so the last row can scroll out from under it. */
private val FAB_CLEARANCE = 88.dp
private val ROW_PICTURE_SIZE = 76.dp
