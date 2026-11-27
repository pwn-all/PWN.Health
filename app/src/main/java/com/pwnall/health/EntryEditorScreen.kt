package com.pwnall.health

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wc
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sleep is not a kind of its own — it is the sleep face of the measurement form — so a caller that
 * has a sleep-shaped door to open says "SLEEP" and lands on it.
 */
private fun String.asEditorKind() =
    if (this in setOf("SLEEP", "HEIGHT", "HEART_RATE", "GIRTH")) KIND_MEASUREMENT else this

/**
 * What a pull off [step] lands on, and so what the panel over it draws behind itself.
 *
 * Not always the hub. The dish builder is opened from the products and goes back to them, and a
 * blurred hub behind it would be a picture of somewhere the pull is not going. Back off the
 * products while a dish is being assembled only drops the assembly and stays put, so the products
 * take the hub as their backdrop either way — it is what is conceptually underneath them.
 *
 * Null for three steps. The hub is not a panel at all. The label reader runs a recogniser for
 * seconds at a time, and the products page kept running behind it is a cost the view does not earn,
 * so it stands over a photograph of that page instead — see [BackdropCamera]. And leaving the
 * success page leaves the whole flow rather than landing anywhere in it, so the hub behind it would
 * be a picture of somewhere nobody is going, still holding the meal just logged; it stands over a
 * photograph of where it does land, [LocalReturnPhoto].
 */
private fun foodBackdrop(step: FoodStep): FoodStep? = when (step) {
    FoodStep.DISH -> FoodStep.SEARCH
    FoodStep.SEARCH, FoodStep.EDIT -> FoodStep.HUB
    FoodStep.HUB, FoodStep.LABEL, FoodStep.DONE -> null
}

/**
 * Which form the editor's form scroll belongs to, for [kind] at meal step [foodStep].
 *
 * One per kind, kept through every page opened over the form — except a meal's. The meal flow's
 * pages do not stand over the form, they replace it: whoever comes back to it from the hub, the
 * search or the label reader brings a food with them, and that is a new form. Held at the last
 * food's offset, the next food's form opened halfway down, its name, portion and catalogue matches
 * above the fold and Save next to the bottom fields — a portion that could be logged unseen. So
 * the key is another one for as long as the flow stands in for the form, and the form is back at
 * its top whenever the flow hands it a food. Every other kind ignores the meal step, which reads
 * HUB on every new entry whatever is being logged.
 */
internal fun formScrollKey(kind: String, foodStep: FoodStep): String =
    if (kind == KIND_EAT && foodStep != FoodStep.EDIT) "$kind/flow" else kind

/**
 * Whether a new entry's form holds a workout that leaving it would throw away: a session under way
 * or a plan changed ([draftChanged]), another exercise chosen, a note or a duration typed.
 *
 * Nothing of a session is written down before its Save, so every road off the form asks first, and
 * all of them on this one answer: back here, and in MainActivity the bottom bar's tabs and a route
 * from a widget or a notification — see [leavesWorkout].
 */
internal fun workoutInputAtStake(
    kind: String,
    draftChanged: Boolean,
    activityCode: String,
    note: String,
    duration: String,
): Boolean = kind == KIND_ACTIVITY &&
    (draftChanged || activityCode != ACTIVITY_CATALOG.first().code || note.isNotBlank() || duration.isNotBlank())

/** The one question every such road asks: [onConfirm] is the leaving itself, [onDismiss] staying. */
@Composable
internal fun WorkoutDiscardDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workout_discard_title)) },
        text = { Text(stringResource(R.string.workout_discard_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.workout_discard_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

// One early return per page this screen can show instead of the form: the food flow, the progress
// gallery and the workout summary each replace the whole editor rather than unfolding inside it.
@Suppress("ReturnCount")
@Composable
fun EntryEditorScreen(
    modifier: Modifier,
    profile: UserProfile,
    entryId: Long?,
    /** Skips the type chooser when a widget already said what is being added. */
    initialKind: String = "",
    initialDay: String? = null,
    /** Bound model arguments carried by the chat; applying them never saves a record. */
    initialJournal: String? = null,
    onSaved: () -> Unit,
    onTrack: (() -> Unit)? = null,
    /** The work timer's screen; no callback, no door. */
    onWork: (() -> Unit)? = null,
    onMood: (() -> Unit)? = null,
    onBreathing: (() -> Unit)? = null,
    onLabs: (() -> Unit)? = null,
    onAlcohol: (() -> Unit)? = null,
    onSmoking: (() -> Unit)? = null,
    onBathroom: (() -> Unit)? = null,
    onScreenTime: (() -> Unit)? = null,
    onPain: (() -> Unit)? = null,
    /** A chat for the day the hub was opened for: only an earlier day's, today's is the home screen's circle. */
    onSymptom: (() -> Unit)? = null,
    onIntimate: (() -> Unit)? = null,
    onRoutines: (() -> Unit)? = null,
    /** Opens the drink dialog the water and caffeine widgets open, for the phone without one. */
    onDrink: ((String) -> Unit)? = null,
    /** Opens a record a chat's card names, over this editor; none, no Open. */
    onOpenRecord: ((ChatRecordRef) -> Unit)? = null,
    onJournal: ((ChatJournalEvent) -> Unit)? = null,
    onLabDocument: ((String) -> Unit)? = null,
    /**
     * Told whether leaving this form now would lose a workout — see [workoutInputAtStake]. The add
     * tab is left by roads that pass no handler of this screen, the bottom bar's tabs and a widget's
     * route, and whoever owns those has to ask first as back does here.
     */
    onWorkoutAtStake: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val dao = remember { db.dao() }
    val scope = rememberCoroutineScope()
    val loadUnitFactor = if (profile.imperial) POUNDS_IN_A_KILOGRAM else 1.0
    val journalSeed = remember(entryId, initialJournal) {
        if (entryId == null) journalPrefill(initialJournal) else null
    }
    var journalApplied by rememberSaveable(entryId, initialJournal) { mutableStateOf(false) }
    var chatFoodsJson by rememberSaveable(entryId, initialJournal) {
        mutableStateOf(journalSeed?.prefillFoods().orEmpty().encodeChatFoods())
    }
    val chatFoods = remember(chatFoodsJson) { chatFoodsJson.decodeChatFoods() }
    fun updateChatFood(index: Int, update: (ChatFoodDraft) -> ChatFoodDraft) {
        chatFoodsJson = chatFoodsJson.decodeChatFoods().mapIndexed { at, item ->
            if (at == index) update(item) else item
        }.encodeChatFoods()
    }
    val reportedWorkout = journalSeed?.type == ChatJournalType.WORKOUT_LOG
    val journalActivity = remember(journalSeed, resources) {
        journalSeed?.prefillExercise(ACTIVITY_CATALOG.associate { it.code to resources.getString(it.titleRes) })
    }
    var journalActivityConfirmed by rememberSaveable(entryId, initialJournal) {
        mutableStateOf(journalActivity != null)
    }
    // Read off the seed rather than off the boxes the effect below fills: the session's plan is made
    // the first time it is drawn, which is before that effect has run.
    val chatPlannedSets = remember(journalSeed, loadUnitFactor) {
        journalSeed?.takeIf { it.type == ChatJournalType.WORKOUT_PLAN }?.let { seed ->
            chatPlan(
                sets = seed.prefillCount("SETS"),
                reps = seed.prefillCount("REPS"),
                load = seed.prefillQuantity("LOAD", "mass")?.let { it / 1000 * loadUnitFactor }?.asInput().orEmpty(),
            )
        }.orEmpty()
    }
    var original by remember { mutableStateOf<JournalEntry?>(null) }
    // Saving is a round trip through Room, and the button stays on screen for all of it: a second
    // tap on a new row inserts it twice, since id 0 upserts as an insert. Saved with the fields,
    // because a recreation brings those back: a phone turned between the tap and the way back
    // would return the form filled in and looking unsaved over a row already written, and the next Save
    // would write it again. The write itself cannot be lost to a recreation — commitSave starts it before
    // it returns — so the page that comes back with this set only has the way back left to take,
    // and takes it in the effect under commitSave instead of standing there with a dead button.
    var saving by rememberSaveable(entryId) { mutableStateOf(false) }
    var kind by rememberSaveable(entryId, initialKind) {
        mutableStateOf(if (entryId == null) initialKind.asEditorKind() else KIND_EAT)
    }
    var title by rememberSaveable(entryId) { mutableStateOf("") }
    var subtitle by rememberSaveable(entryId) { mutableStateOf("") }
    var serving by rememberSaveable(entryId) { mutableStateOf("") }
    var protein by rememberSaveable(entryId) { mutableStateOf("") }
    var fat by rememberSaveable(entryId) { mutableStateOf("") }
    var carbs by rememberSaveable(entryId) { mutableStateOf("") }
    var reportedEnergy by rememberSaveable(entryId) { mutableStateOf("") }
    // Per 100 g by default: that is what a packet prints.
    var basisPer100 by rememberSaveable(entryId) { mutableStateOf(true) }
    // One string per optional nutrient rather than a map, because rememberSaveable can carry a
    // String across a configuration change and cannot carry a Map<ExtraNutrient, String>.
    var fiber by rememberSaveable(entryId) { mutableStateOf("") }
    var sugar by rememberSaveable(entryId) { mutableStateOf("") }
    var saturatedFat by rememberSaveable(entryId) { mutableStateOf("") }
    var sodium by rememberSaveable(entryId) { mutableStateOf("") }
    var cholesterol by rememberSaveable(entryId) { mutableStateOf("") }
    var activityCode by rememberSaveable(entryId) { mutableStateOf(ACTIVITY_CATALOG.first().code) }
    var duration by rememberSaveable(entryId) { mutableStateOf("") }
    var sets by rememberSaveable(entryId) { mutableStateOf("") }
    var reps by rememberSaveable(entryId) { mutableStateOf("") }
    var load by rememberSaveable(entryId) { mutableStateOf("") }
    var note by rememberSaveable(entryId) { mutableStateOf("") }
    // The per-set breakdown a workout session produced, JSON-encoded the same way it will be
    // saved — empty until a session runs, or when re-editing a row that holds no sets.
    var workoutSetsJson by rememberSaveable(entryId) { mutableStateOf("") }
    var workoutDraftChanged by rememberSaveable(entryId) { mutableStateOf(false) }
    var confirmWorkoutDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmCorrectionLeave by rememberSaveable(entryId) { mutableStateOf(false) }
    // The pair's second exercise, for as long as the session lasts. Never restored from a saved
    // row: reopening one of the two rows a superset wrote gives back that exercise, not the pair —
    // the pairing was a way of doing the work, and the work is now two facts in the journal.
    var partnerCode by rememberSaveable(entryId) { mutableStateOf<String?>(null) }
    // Saved, not merely remembered: a machine session ends on this screen, and a phone turned on
    // the handlebars must not come back to an empty form with the ride gone.
    var showActivitySummary by rememberSaveable(entryId) { mutableStateOf(false) }
    // The form as it stood when the label reader, the progress gallery or the workout summary was
    // opened over it: the picture those panels stand over, since only one of them is open at once.
    val behind = rememberBackdropShot()
    val returnPhoto = LocalReturnPhoto.current
    // The gallery is a page of this screen rather than a screen of its own, the way the summary and
    // the food flow are: it is reached from the weight form and returns to it.
    var showPhotoGallery by rememberSaveable(entryId) { mutableStateOf(false) }
    // What a body-composition scale measured beside the weight. This screen has no field for it,
    // so without keeping it the figure the scale actually took would be dropped on save.
    var deviceBodyFatPercent by rememberSaveable(entryId) { mutableStateOf<Double?>(null) }
    // What a training machine reported, when one did. The energy is the console's own measurement
    // of work done and the payload is its raw frame; both are kept apart from the MET estimate the
    // form computes, so that neither silently stands in for the other.
    var machineKcal by rememberSaveable(entryId) { mutableStateOf<Int?>(null) }
    var machinePayload by rememberSaveable(entryId) { mutableStateOf("") }
    // A meal walks through four pages; correcting a saved one opens straight at the form, because
    // the hub's whole job is choosing a food and that choice has already been made.
    var foodStep by rememberSaveable(entryId) {
        mutableStateOf(if (entryId == null && chatFoods.isEmpty()) FoodStep.HUB else FoodStep.EDIT)
    }
    // The packet photograph the label page is reading, as a string because a Uri does not survive
    // the process dying behind the camera and the page has to come back to the same picture.
    var labelPhoto by rememberSaveable(entryId) { mutableStateOf<String?>(null) }
    // The catalogue row a food came from, when it came from one: what a favourite is filed under,
    // and what the portion chips need to offer their weights.
    var foodCatalogId by rememberSaveable(entryId) { mutableStateOf<String?>(null) }
    // What the meal will point at in the catalogue: the rows the form was filled from, as encodeDish
    // writes them, at the grams they were chosen at — scaled to the saved portion on the way out.
    // Null for a food typed in, read off a label, or renamed after it was picked.
    var foodPicked by rememberSaveable(entryId) { mutableStateOf<String?>(null) }
    // Which door was opened at the hub. It routes nothing but the camera: the search page's scan
    // button photographs a plate or a printed table depending on what was said one page earlier.
    var foodKind by rememberSaveable(entryId) { mutableStateOf(FoodKind.MEAL) }
    var measurementMode by rememberSaveable(entryId) {
        mutableStateOf(if (initialKind in setOf("SLEEP", "HEIGHT", "HEART_RATE", "GIRTH")) initialKind else "WEIGHT")
    }
    var measuredWeightKg by rememberSaveable(entryId) { mutableDoubleStateOf(profile.weightKg) }
    var measuredHeightCm by rememberSaveable(entryId) { mutableIntStateOf(profile.heightCm) }
    var measuredHeartRateBpm by rememberSaveable(entryId) { mutableIntStateOf(72) }
    // A tape measure writes only what somebody actually held it against: a girth left alone is
    // absent from the payload rather than saved at whatever the ruler happened to open on. The
    // three open on a plain default and say "not measured" until moved; a wheel nobody touched
    // writes nothing.
    var measuredGirthCm by rememberSaveable(entryId, saver = GIRTH_SAVER) {
        mutableStateOf(emptyMap<Vital, Int>())
    }
    var touchedGirth by rememberSaveable(entryId, saver = GIRTH_TOUCHED_SAVER) {
        mutableStateOf(emptySet<Vital>())
    }
    var sleepStartMinute by rememberSaveable(entryId) { mutableIntStateOf(23 * 60) }
    var sleepEndMinute by rememberSaveable(entryId) { mutableIntStateOf(7 * 60) }
    var chatSleepDuration by rememberSaveable(entryId, initialJournal) {
        mutableStateOf(
            journalSeed?.takeIf { it.type == ChatJournalType.SLEEP_LOG }
                ?.prefillQuantity("DURATION", "duration")?.asInput().orEmpty(),
        )
    }
    var measurementSource by rememberSaveable(entryId) { mutableStateOf("MANUAL") }
    // The option a one-tap row was logged with, empty when its kind offers no choice.
    var simpleValue by rememberSaveable(entryId) { mutableStateOf("") }
    // The fields above survive an Activity recreation; the effect below does not, and would run a
    // second time. It has to refetch `original` — that one is plain remember — but filling the form
    // again would write the stored row over an edit that was still being typed.
    var loadedId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(entryId, initialJournal) {
        val seed = journalSeed ?: return@LaunchedEffect
        if (journalApplied) return@LaunchedEffect
        note = seed.prefillNote()
        if (chatFoods.isNotEmpty()) foodStep = FoodStep.EDIT
        if (reportedWorkout || seed.type == ChatJournalType.WORKOUT_PLAN) {
            activityCode = journalActivity ?: "STRENGTH"
            duration = seed.prefillQuantity("DURATION", "duration")?.asInput().orEmpty()
            sets = seed.prefillCount("SETS")?.toString().orEmpty()
            reps = seed.prefillCount("REPS")?.toString().orEmpty()
            load = seed.prefillQuantity("LOAD", "mass")?.let { it / 1000 * loadUnitFactor }?.asInput().orEmpty()
        }
        if (seed.type == ChatJournalType.SLEEP_LOG) {
            val night = chatNight(
                start = seed.prefillClock("START_TIME"),
                end = seed.prefillClock("END_TIME"),
                // As Save reads the box under it, so the dial opens on a night Save will take.
                minutes = chatSleepDuration.number().toInt(),
                opensAt = sleepStartMinute to sleepEndMinute,
            )
            sleepStartMinute = night.first
            sleepEndMinute = night.second
        }
        journalApplied = true
    }

    LaunchedEffect(entryId) {
        if (entryId == null) return@LaunchedEffect
        // Read before the query: a page that comes back with `saving` set is already being let go by
        // the effect under commitSave, and a second way back would leave the page underneath too.
        val leaving = saving
        val entry = dao.journalEntry(entryId)
        // Deleted behind the link that opened it — by a sync, on another screen. Left standing, the page
        // would be an empty meal under "Edit" with a Save that could never light, so it goes back instead.
        if (entry == null) {
            if (!leaving) onSaved()
            return@LaunchedEffect
        }
        original = entry
        if (loadedId == entryId) return@LaunchedEffect
        kind = entry.kind
        chatFoodsJson = if (entry.kind == KIND_EAT) {
            savedChatFoods(entry.measurementPayloadJson).encodeChatFoods()
        } else {
            emptyList<ChatFoodDraft>().encodeChatFoods()
        }
        title = entry.title
        subtitle = entry.subtitle
        foodPicked = entry.foodSource
        serving = entry.servingG.asTypedInput()
        // Stored figures are always for the portion. Reopening on the basis they were typed on puts
        // the cursor back where the person left it, so an edit is a correction rather than a retype.
        basisPer100 = entry.enteredPer100g
        fun typed(portionValue: Double): String = when {
            portionValue == 0.0 -> ""
            entry.enteredPer100g -> per100FromPortion(portionValue, entry.servingG).asTypedInput()
            else -> portionValue.asTypedInput()
        }
        protein = typed(entry.proteinG)
        fat = typed(entry.fatG)
        carbs = typed(entry.carbsG)
        // In a nullable extra 0.0 is a stated "none", not an unfilled field: it must reopen as the
        // "0" that was typed, or the next save would turn it into an unsaid null.
        fun typedExtra(portionValue: Double): String =
            if (portionValue == 0.0) "0" else typed(portionValue)
        // So is a meal's nought: one is only saved where it was stated ([mealSaveable]), and reopened
        // as an empty box it would be a glass of water whose Save would not light again.
        reportedEnergy = typedExtra(entry.calories)
        fiber = entry.fiberG?.let(::typedExtra).orEmpty()
        sugar = entry.sugarG?.let(::typedExtra).orEmpty()
        saturatedFat = entry.saturatedFatG?.let(::typedExtra).orEmpty()
        sodium = entry.sodiumMg?.let(::typedExtra).orEmpty()
        cholesterol = entry.cholesterolMg?.let(::typedExtra).orEmpty()
        activityCode = entry.activityCode ?: ACTIVITY_CATALOG.first().code
        duration = entry.durationMinutes?.asInput().orEmpty()
        sets = entry.sets?.toString().orEmpty()
        reps = entry.reps?.toString().orEmpty()
        load = entry.loadKg?.let { (it * loadUnitFactor).asInput() }.orEmpty()
        workoutSetsJson = entry.measurementPayloadJson
            .takeIf { entry.kind == KIND_ACTIVITY && it?.isMachinePayload() != true }
            .orEmpty()
        note = entry.note
        measurementMode = when {
            entry.measurementWeightKg != null && entry.measurementHeightCm != null -> "BOTH"
            entry.measurementHeightCm != null -> "HEIGHT"
            entry.measurementHeartRateBpm != null -> "HEART_RATE"
            entry.sleepStartAt != null && entry.sleepEndAt != null -> "SLEEP"
            entry.girthCm().isNotEmpty() -> "GIRTH"
            else -> "WEIGHT"
        }
        measuredGirthCm = entry.girthCm()
        measuredWeightKg = entry.measurementWeightKg ?: profile.weightKg
        measuredHeightCm = entry.measurementHeightCm ?: profile.heightCm
        measuredHeartRateBpm = entry.measurementHeartRateBpm ?: 72
        if (entry.sleepStartAt != null && entry.sleepEndAt != null) {
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochMilli(entry.sleepStartAt).atZone(zone)
            val end = Instant.ofEpochMilli(entry.sleepEndAt).atZone(zone)
            sleepStartMinute = start.hour * 60 + start.minute
            sleepEndMinute = end.hour * 60 + end.minute
        }
        measurementSource = entry.measurementSource ?: "MANUAL"
        // A machine session is reopened with the console's own account of it intact. Without this
        // the payload would identify the row as a machine's while the energy silently reverted to the
        // MET estimate on the next Save.
        machinePayload = entry.measurementPayloadJson?.takeIf { it.isMachinePayload() }.orEmpty()
        // Only where the frame says the energy is the console's ([machineFrame]): a frame that reported
        // none holds the MET estimate, which the box would have offered as the console's own count.
        machineKcal = entry.calories.roundToInt().takeIf { entry.hasMachineEnergy() && it > 0 }
        simpleValue = simpleValueOf(entry)
        loadedId = entryId
    }

    // firstOrNull, not first: imports write codes that are not in the catalogue — "HEALTH_CONNECT"
    // from a synced session, "BLUETOOTH_RSCS" and friends from a sensor, anything at all from a
    // restored backup — and opening one of those entries with first() would throw inside composition.
    val spec = ACTIVITY_CATALOG.firstOrNull { it.code == activityCode } ?: ACTIVITY_CATALOG.first()
    val partnerSpec = partnerCode?.let { code -> ACTIVITY_CATALOG.firstOrNull { it.code == code } }
    // The fallback is only there so the form has something to draw. It may not become the row: on
    // save such a session keeps the code, name, MET and energy it arrived with, until the exercise
    // picker is used to name a catalogue one — see [workoutRow].
    val specTitle = stringResource(spec.titleRes)
    val budgetYear = rememberToday().year
    val dailyBudgetKcal = remember(profile, budgetYear) { energyBudget(profile, budgetYear).targetKcal }
    val manualFood = FoodState(
        title = title,
        subtitle = subtitle,
        serving = serving,
        protein = protein,
        fat = fat,
        carbs = carbs,
        reportedEnergy = reportedEnergy,
        basis = if (basisPer100) MealBasis.PER_100G else MealBasis.PER_PORTION,
        extra = buildMap {
            if (fiber.isNotEmpty()) put(ExtraNutrient.FIBER, fiber)
            if (sugar.isNotEmpty()) put(ExtraNutrient.SUGAR, sugar)
            if (saturatedFat.isNotEmpty()) put(ExtraNutrient.SATURATED_FAT, saturatedFat)
            if (sodium.isNotEmpty()) put(ExtraNutrient.SODIUM, sodium)
            if (cholesterol.isNotEmpty()) put(ExtraNutrient.CHOLESTEROL, cholesterol)
        },
        dailyBudgetKcal = dailyBudgetKcal,
    )
    val food = if (chatFoods.isEmpty()) manualFood else chatFoods.combinedFood().copy(dailyBudgetKcal = dailyBudgetKcal)
    val onFood: (FoodState) -> Unit = { next ->
        // Choosing another meal through the normal flow replaces the chat's ingredient draft.
        chatFoodsJson = emptyList<ChatFoodDraft>().encodeChatFoods()
        title = next.title
        subtitle = next.subtitle
        serving = next.serving
        protein = next.protein
        fat = next.fat
        carbs = next.carbs
        reportedEnergy = next.reportedEnergy
        basisPer100 = next.basis.per100
        fiber = next.extra[ExtraNutrient.FIBER].orEmpty()
        sugar = next.extra[ExtraNutrient.SUGAR].orEmpty()
        saturatedFat = next.extra[ExtraNutrient.SATURATED_FAT].orEmpty()
        sodium = next.extra[ExtraNutrient.SODIUM].orEmpty()
        cholesterol = next.extra[ExtraNutrient.CHOLESTEROL].orEmpty()
    }
    val workoutCalories = netActivityCalories(
        loadedMet(spec.code, spec.met, profile.weightKg, load.number() / loadUnitFactor),
        profile.restingKcalPerHour(),
        duration.number(),
    )

    // Whether the form was opened for a day that is not today, asked once: a session begun late on
    // a form opened for today by its date must not turn into typed fields at midnight.
    val anotherDay = rememberSaveable(entryId, initialDay) {
        initialDay != null && initialDay != LocalDate.now().toString()
    }
    // An imported session's row — a synced one, a sensor's, a backup's — whose code names nothing in
    // the catalogue. [spec] is then only a fallback, and the row gets a form drawn without it: its
    // own name and energy, and the minutes ([ImportedSessionFields]).
    val imported = original?.takeIf { kind == KIND_ACTIVITY && spec.code != activityCode }
    val typedWorkout = workoutTyped(
        reported = reportedWorkout,
        counted = spec.countedForm(workoutSetsJson),
        anotherDay = entryId == null && anotherDay,
        saved = original,
        imported = imported != null,
    )

    /**
     * Whether this activity runs as a session rather than as a form to fill in.
     *
     * The camera exercises keep typed fields — they count for you, and there is nothing to
     * start — unless the row already holds its sets ([countedForm]). A workout that is already over
     * is typed too ([workoutTyped]). Everything else gets planned sets and a "Start workout", and
     * its own page ends there: what a session is worth is settled on the summary, not before it.
     */
    val sessionWorkout = kind == KIND_ACTIVITY && !spec.countedForm(workoutSetsJson) && !typedWorkout

    // Which of the three forms this row gets, decided once and read everywhere below.
    //
    // The measurement form is claimed only by rows it can actually express — see
    // [measurementFormShows]. Handing it a blood pressure would let one tap on Save replace a
    // 128/84 with the profile's weight and drop the reading, and the Health Connect sweep would
    // then delete the record that reading had written.
    val measurementForm = kind == KIND_MEASUREMENT && measurementFormShows(original)
    val simpleForm = kind.isNotEmpty() && kind != KIND_EAT && kind != KIND_ACTIVITY && !measurementForm
    // None for a row logged as none of them — a drink poured to a size of its own ([rechoosable]).
    val simpleChoices = simpleOptions(kind, profile.imperial).takeIf { rechoosable(original) }.orEmpty()
    // A one-tap row is rebuilt by the factory that wrote it, keeping only its id and its time;
    // [journalEntry] has no fields for it and would flatten it into an empty husk labelled with
    // its old kind. Null means this build cannot rebuild the row — a day string it cannot
    // parse, an option it does not know — and then Save stays dark instead of flattening it.
    val rebuiltSimple = original
        ?.takeIf { simpleForm }
        ?.let { rebuiltSimpleEntry(resources, it, simpleValue) }

    val valid = when {
        kind.isEmpty() -> false

        kind == KIND_EAT -> mealSaveable(food) && chatFoods.all { it.valid }

        // An imported session is judged by its minutes alone: the sets and the load the fallback
        // exercise would be asked for are not its own, and its form does not draw them.
        kind == KIND_ACTIVITY && imported != null -> workoutMinutesValid(duration.number())

        // Only a workout is judged by a duration, and not as the `else` branch: a breathing practice
        // or a planned session — both of which carry a duration and neither of which this screen can
        // show — would then arrive with Save lit up over a weight wheel.
        kind == KIND_ACTIVITY -> (!reportedWorkout || journalActivityConfirmed) &&
            workoutMinutesValid(duration.number()) &&
            (spec.input == ActivityInput.DURATION || (sets.intNumber() > 0 && reps.intNumber() > 0)) &&
            (spec.input != ActivityInput.WEIGHTED_SETS || spec.bodyWeight || load.number() > 0)

        measurementForm -> measurementValid(
            mode = measurementMode,
            weightKg = measuredWeightKg,
            heightCm = measuredHeightCm,
            heartRateBpm = measuredHeartRateBpm,
            sleepMinutes = sleepDurationMinutes(sleepStartMinute, sleepEndMinute),
            chatSleepDuration = chatSleepDuration,
            // Moved on a new entry, or read off the row being corrected: its note can be changed
            // without the tape being taken out again.
            girthMeasured = measuredGirthCm.isNotEmpty(),
        )

        // A one-fact row is saveable exactly when there is a choice on it to have made, and the
        // factory that wrote it can put the row back together from that choice.
        else -> simpleValue.isNotEmpty() && rebuiltSimple != null
    }

    val eating = kind == KIND_EAT
    val foodLocale = LocalLocale.current.platformLocale

    // A repeat of a past meal arrives as portion figures, so it lands on the portion basis whatever
    // the form was on. Switching afterwards re-expresses it, exactly as if it had been typed that
    // way. Named rather than inlined because the hub, the search page and the form all offer it.
    fun repeatMeal(meal: JournalEntry) {
        onFood(
            food.copy(
                title = meal.title,
                subtitle = meal.subtitle,
                serving = meal.servingG.asInput(),
                protein = meal.proteinG.asInput(),
                fat = meal.fatG.asInput(),
                carbs = meal.carbsG.asInput(),
                reportedEnergy = meal.calories.asInput(),
                basis = MealBasis.PER_PORTION,
                extra = buildMap {
                    meal.fiberG?.let { put(ExtraNutrient.FIBER, it.asInput()) }
                    meal.sugarG?.let { put(ExtraNutrient.SUGAR, it.asInput()) }
                    meal.saturatedFatG?.let { put(ExtraNutrient.SATURATED_FAT, it.asInput()) }
                    meal.sodiumMg?.let { put(ExtraNutrient.SODIUM, it.asInput()) }
                    meal.cholesterolMg?.let { put(ExtraNutrient.CHOLESTEROL, it.asInput()) }
                },
            ),
        )
    }

    val favorites by remember { dao.favoriteFoods() }.collectAsStateWithLifecycle(emptyList())
    val recentMeals by remember { dao.recentMeals() }.collectAsStateWithLifecycle(emptyList())
    val favoriteMarked = favorites.any { it.key == favoriteKey(foodCatalogId, title) }
    val labelSource = stringResource(R.string.photo_label_source)
    // One camera for the whole flow, opened from whichever tile was tapped. Both recognisers write
    // into the same form, so both land here rather than inside a page that is about to go away.
    val takeFoodPhoto = rememberFoodPhotography(
        onDish = { dishTitle, dishSubtitle, items ->
            foodCatalogId = null
            foodPicked = items.encodeDish()
            onFood(food.withPhotoDish(dishTitle, dishSubtitle, items))
            foodStep = FoodStep.EDIT
        },
        onLabelPhoto = { taken ->
            behind.open {
                labelPhoto = taken.toString()
                foodStep = FoodStep.LABEL
            }
        },
    )
    // Back off the label page returns to the products, and the photograph goes with it: it was an
    // input to the reader, and the reader is done with it.
    val leaveLabelPage: () -> Unit = {
        clearFoodPhoto(context, labelPhoto?.toUri())
        labelPhoto = null
        foodStep = FoodStep.SEARCH
    }
    // Which catalogue rows are going into the dish, how much of each, and — for a product a shop
    // sells at several fat contents — which of them. Ids rather than TopFood so the three survive
    // the process dying behind a camera; the rows are looked up again on the way out, which is a
    // map lookup over a value the app already holds.
    var dishPicks by rememberSaveable(entryId) { mutableStateOf(listOf<String>()) }
    var dishGrams by rememberSaveable(entryId) { mutableStateOf(mapOf<String, Double>()) }
    var dishFats by rememberSaveable(entryId) { mutableStateOf(mapOf<String, Double>()) }
    // Whether the page is showing a dish out of "your dishes" rather than one being put together.
    // It is already kept, so it is offered to be logged instead of asked for a name a second time.
    var dishSaved by rememberSaveable(entryId) { mutableStateOf(false) }
    // A lambda and not TOP_FOODS_BY_ID::get: a bound reference takes its receiver where it is
    // written, so an editor with no dish in it — a weight, a mood — would load the catalogue to open.
    val dishFoods = remember(dishPicks) { dishPicks.mapNotNull { TOP_FOODS_BY_ID[it] } }
    val dishItems = dishFoods.map { DishItem(it, dishGrams[it.id] ?: it.defaultPortion.grams, dishFats[it.id]) }
    val dishSource = stringResource(R.string.dish_build_source)
    val savedDishes by remember { dao.savedDishes() }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(foodStep, dishItems) {
        if (foodStep == FoodStep.DISH && dishItems.isNotEmpty()) {
            // The pointer follows the figures: backing out of the builder leaves the dish's sums on
            // the form, and a single row picked before it must not be saved under them.
            foodPicked = dishItems.encodeDish()
            onFood(food.withPhotoDish(dishItems.first().food.name.localized(foodLocale), dishSource, dishItems))
        }
    }

    var dishBuilding by rememberSaveable(entryId) { mutableStateOf(false) }
    // A dish left behind is a dish forgotten. Every way out of the flow comes through here, because
    // the one thing nobody means by "Build a dish" is last time's products still ticked — and a
    // plate that has been logged is finished with, not a draft the next meal starts from.
    val clearDish: () -> Unit = {
        dishPicks = emptyList()
        dishGrams = emptyMap()
        dishFats = emptyMap()
        dishSaved = false
    }
    // Back off the builder returns to the products behind it, which is where an assembly came from
    // and where its ticks must still be waiting. A saved dish came from nowhere on that page, so it
    // is dropped on the way rather than left lying in the state.
    val leaveDishPage: () -> Unit = {
        if (!dishBuilding) clearDish()
        foodStep = FoodStep.SEARCH
    }
    // Every panel below leaves through the dispatcher rather than through a close of its own: the
    // handlers just below know what "back" means at each step of the meal flow — the search returns
    // to the hub, the dish page to where it was opened from — and a panel that shut itself would
    // have to repeat all of it. Its own handler is off by then, so the press lands on theirs.
    val pageBack = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val leavePage: () -> Unit = { pageBack?.onBackPressed() }
    // What a row that has been written does to its page. A new meal stays for another, on the
    // success page — a dish's products going with it, so the next dish starts from nothing — and
    // every other save takes the way back. Read off the page's own state rather than handed to the
    // save, so that a page brought back by a recreation can still do it.
    val leaveSaved: () -> Unit = {
        saving = false
        showActivitySummary = false
        if (eating && entryId == null) {
            if (foodStep == FoodStep.DISH) {
                clearDish()
                dishBuilding = false
            }
            foodStep = FoodStep.DONE
        } else {
            onSaved()
        }
    }
    // What a write that threw does to its page — a full disk, a key the Keystore will not give: the
    // page stays where it is, its Save lit again for another try, and says so ([writeThenMirror]).
    var saveFailed by rememberSaveable(entryId) { mutableStateOf(false) }
    val saveRefused: () -> Unit = {
        saving = false
        saveFailed = true
    }

    // Split out of the Save button so an activity's summary screen can call the same commit its
    // Done button stands in front of, rather than the button underneath it saving a second time.
    fun commitSave() {
        // `enabled` only takes effect at the next recomposition, so a bounced touch that lands in
        // the same frame still arrives here. Once, as the onboarding finish button guards itself:
        // a new row upserts from id 0 and inserts. And not before the row being corrected is here
        // to be corrected — see [savedRowLoaded].
        if (saving || !savedRowLoaded(entryId, original)) return
        saving = true
        val previous = original
        // One reading of the clock for the day and the time both: read twice, a Save on the stroke
        // of midnight would take the old day's date and the new day's 00:00, and the row would go a day back.
        val clock = LocalDateTime.now()
        val entry = rebuiltSimple ?: EditorForm(
            kind = kind,
            note = note,
            food = food,
            chatFoods = chatFoods,
            foodPicked = foodPicked,
            activityCode = activityCode,
            spec = spec,
            specTitle = specTitle,
            typedWorkout = typedWorkout,
            duration = duration,
            sets = sets,
            reps = reps,
            load = load,
            loadUnitFactor = loadUnitFactor,
            workoutCalories = workoutCalories,
            workoutSetsJson = workoutSetsJson,
            machineKcal = machineKcal,
            machinePayload = machinePayload,
            measurementMode = measurementMode,
            measurementTitle = resources.getString(measurementTitleRes(measurementMode)),
            weightKg = measuredWeightKg,
            heightCm = measuredHeightCm,
            heartRateBpm = measuredHeartRateBpm,
            sleepStartMinute = sleepStartMinute,
            sleepEndMinute = sleepEndMinute,
            girthCm = measuredGirthCm,
            touchedGirth = touchedGirth,
            measurementSource = measurementSource,
            bodyFatPercent = deviceBodyFatPercent,
        ).journalEntry(
            previous = previous,
            recordingDay = initialDay?.let(LocalDate::parse) ?: clock.toLocalDate(),
            now = clock.toLocalTime(),
        )
        // A new session is a row per exercise it held — see [sessionRowsOf]; a correction is its one row.
        val sessionEntries = if (kind == KIND_ACTIVITY && previous == null) {
            entry.sessionRowsOf(
                plan = decodeSetsFromJson(workoutSetsJson, loadUnitFactor),
                spec = spec,
                partner = partnerSpec,
                loadUnitFactor = loadUnitFactor,
                bodyKg = profile.weightKg,
                restingKcalPerHour = profile.restingKcalPerHour(),
                titleOf = { resources.getString(it.titleRes) },
            )
        } else {
            listOf(entry)
        }
        // This scope dies with the screen, and a back press or a tab change can close it a frame
        // after the tap. A save that is confirmed is written and mirrored all the same, and the
        // page is let go in between — see [writeThenMirror]. Undispatched, so that the write has
        // begun before this returns: a launch still queued when the scope dies never runs, and
        // `saving` would then come back saying a row was written that never was.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writeThenMirror(
                page = scope,
                write = {
                    if (sessionEntries.size == 1) {
                        db.saveJournalEvent(sessionEntries.single())
                    } else {
                        db.saveSupersetEvent(sessionEntries)
                    }
                },
                release = leaveSaved,
                // Mirror whatever was just written, whatever its kind. A corrected meal is a
                // NutritionRecord that is now wrong in Health Connect, and
                // leaving it wrong until the next daily sync means the correction was only half made.
                mirror = { writeLocalHealthIfAvailable(context, db) },
                failed = saveRefused,
            )
        }
    }

    // A page that opens with `saving` set was recreated between its Save, or its bin, and its way
    // back: turned, or left behind another page while the row was being written. The coroutine that
    // would have let the page go died with the page it belonged to, and the write went on without it:
    // this page waits for how the write ended ([editorWrite]). Written, it takes the way back; refused,
    // it stands with its fields and says so rather than closing as if the row had gone in.
    LaunchedEffect(Unit) {
        if (saving) {
            val ended = editorWrite.first { it != EditorWrite.RUNNING }
            if (ended == EditorWrite.REFUSED) saveRefused() else leaveSaved()
        }
    }
    // A dialog rather than the failure card the other pages use: Save is reached from the form, the
    // workout's summary and the dish page, each drawn below in a layout of its own, and a dialog is
    // the one thing that stands over all three.
    if (saveFailed) {
        AlertDialog(
            onDismissRequest = { saveFailed = false },
            title = { Text(stringResource(R.string.quick_log_not_saved)) },
            confirmButton = {
                TextButton(onClick = { saveFailed = false }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
    val workoutAtStake =
        entryId == null && workoutInputAtStake(kind, workoutDraftChanged, activityCode, note, duration)
    // Said again whenever the answer changes, and taken back when this screen goes: a form that is
    // no longer standing has nothing left to ask about. Above every early return below, so the
    // summary of a finished session, which is not saved yet either, is still spoken for.
    DisposableEffect(workoutAtStake) {
        onWorkoutAtStake?.invoke(workoutAtStake)
        onDispose { onWorkoutAtStake?.invoke(false) }
    }
    // The type chooser is a step, not a screen: the system back gesture returns to it before
    // leaving the editor.
    BackHandler(
        enabled = entryId == null && kind.isNotEmpty() && !showActivitySummary &&
            !(eating && foodStep != FoodStep.HUB),
    ) {
        if (workoutAtStake) {
            confirmWorkoutDiscard = true
        } else {
            kind = ""
        }
    }
    fun takeMachineResult(result: MachineResult) {
        // The machine counted the time and, where it could, the energy; both go in the same fields a
        // hand-logged session fills, so the summary that follows is the ordinary one and every
        // figure on it stays correctable.
        duration = result.minutes.asInput()
        machineKcal = result.kcal
        machinePayload = result.payloadJson
        // The machine's account replaces any plan typed before it. Left in place, the plan would win the
        // payload column while the console won the energy, and the saved row could be read back as
        // neither.
        workoutSetsJson = ""
        // Taking the numbers again replaces the line rather than adding a second copy of it: it says
        // which machine this came from, and there is one.
        note = if (note.contains(result.note)) {
            note
        } else {
            listOf(note, result.note).filter(String::isNotBlank).joinToString(" · ")
        }
        behind.open { showActivitySummary = true }
    }
    // Not saved: a turn of the phone drops the question and the numbers with it, and the session they
    // would have replaced is still there to take them over again.
    var machineOverSession by remember { mutableStateOf<MachineResult?>(null) }
    machineOverSession?.let { result ->
        WorkoutDiscardDialog(
            onDismiss = { machineOverSession = null },
            onConfirm = {
                machineOverSession = null
                takeMachineResult(result)
            },
        )
    }
    if (confirmWorkoutDiscard) {
        WorkoutDiscardDialog(
            onDismiss = { confirmWorkoutDiscard = false },
            onConfirm = {
                confirmWorkoutDiscard = false
                workoutDraftChanged = false
                workoutSetsJson = ""
                partnerCode = null
                duration = ""
                sets = ""
                reps = ""
                load = ""
                note = ""
                kind = ""
            },
        )
    }
    // A saved workout has no Save of its own: a set put right on it is written by End and the
    // summary's Done, and until then it is in this page and nowhere else. So Back asks first rather
    // than leaving with it gone and nothing said, and so does the panel's own close, which leaves through
    // the same dispatcher. Above the summary's handler, which is composed later and so answers
    // first while the summary stands.
    val savedRow = original
    val correctionAtStake = savedRow != null && sessionWorkout && savedWorkoutCorrected(
        draftChanged = workoutDraftChanged,
        formSets = workoutSetsJson,
        rowSets = savedRow.measurementPayloadJson?.takeUnless { it.isMachinePayload() },
    )
    BackHandler(enabled = correctionAtStake && !showActivitySummary) { confirmCorrectionLeave = true }
    if (confirmCorrectionLeave) {
        WorkoutCorrectionLeaveDialog(
            timed = spec.input == ActivityInput.DURATION,
            onDismiss = { confirmCorrectionLeave = false },
            onConfirm = {
                confirmCorrectionLeave = false
                onSaved()
            },
        )
    }
    BackHandler(enabled = showActivitySummary) { showActivitySummary = false }
    BackHandler(enabled = showPhotoGallery) { showPhotoGallery = false }
    // Two scrolls, held up here above every page that stands in for the form, so that each of them
    // is still where it was left when the form comes back. The chooser keeps one of its own: going
    // into a form and back out of it lands on the card that was tapped, not at the top of the list.
    // A form gets a fresh one for every kind it is opened as, and for every food a meal's flow puts
    // into it — see [formScrollKey] — and keeps it through anything opened over it: the workout
    // summary, the gallery, the saved templates.
    //
    // Not an effect keyed on the kind sending a new kind to the top: such an effect also runs every
    // time the add tab is composed again, so back from Symptoms, from any page opened off the
    // chooser, the tab would be put back where it was left and then thrown to its top a frame later.
    val chooserScroll = rememberScrollState()
    val formScroll = rememberSaveable(formScrollKey(kind, foodStep), saver = ScrollState.Saver) { ScrollState(0) }
    // Inside a meal, back walks the flow rather than leaving it: search returns to the hub, the
    // form to wherever it was opened from, and the success page is done with — it goes home.
    BackHandler(enabled = eating && foodStep == FoodStep.SEARCH && dishBuilding) {
        dishBuilding = false
        clearDish()
    }
    BackHandler(enabled = eating && foodStep == FoodStep.SEARCH && !dishBuilding) { foodStep = FoodStep.HUB }
    BackHandler(enabled = eating && foodStep == FoodStep.DISH, onBack = leaveDishPage)
    BackHandler(enabled = eating && foodStep == FoodStep.LABEL, onBack = leaveLabelPage)
    BackHandler(enabled = eating && foodStep == FoodStep.EDIT && entryId == null) { foodStep = FoodStep.HUB }
    BackHandler(enabled = eating && foodStep == FoodStep.DONE) { onSaved() }

    // A pain is painted on the body, not typed into this form, and its page stands in for the
    // editor. Gated on the loaded row rather than on [kind], which reads "EAT" until it loads.
    original?.takeIf { it.kind == KIND_PAIN }?.let { row ->
        PainEntryPage(modifier, profile, entry = row, onBack = leavePage, onDone = onSaved)
        return
    }
    // A symptom is told in a chat, which stands in for the editor in the same way, on the same loaded
    // row: read as it was said, or carried on when it is today's and was left unfinished. A journal row
    // its cards name opens in this editor's place; a course would open on the medicine tab, which this
    // editor is drawn over, so a course's card offers no Open here.
    original?.takeIf { it.kind == KIND_SYMPTOM }?.let { row ->
        SymptomChatScreen(
            modifier,
            profile,
            entry = row,
            onBack = leavePage,
            onDone = onSaved,
            onBreathing = onBreathing,
            onLabs = onLabs,
            onOpenRecord = onOpenRecord,
            onJournal = onJournal,
            onLabDocument = onLabDocument,
            opensCourses = false,
        )
        return
    }

    if (eating && foodStep != FoodStep.EDIT) {
        val pickFavorite: (FavoriteFood) -> Unit = { favorite ->
            foodCatalogId = favorite.key.takeIf(TOP_FOODS_BY_ID::containsKey)
            foodPicked = favorite.foodSource()
            onFood(food.withFavorite(favorite))
            foodStep = FoodStep.EDIT
        }
        // The id the portion chips need is found again by the meal's name, which is usually enough
        // to find the row, and the chip the person just tapped already proved it: its picture
        // comes from exactly this lookup. Nulling the id here would make the form disagree with the chip
        // that opened it, and offer "matches from the offline catalogue" for a food that had just
        // been chosen — three guesses at something already answered, sitting where the portion
        // chips belong. The numbers still come from the row, not the catalogue:
        // repeatMeal writes them after this, so a portion someone edited survives being repeated.
        val pickRecent: (JournalEntry) -> Unit = { meal ->
            foodCatalogId = catalogPhraseMatch(meal.title)?.id
            // Not the chip's lookup, which only has to find a picture: the pointer is the meal's own,
            // or the row an older meal's name and figures still are, and the save scales it.
            foodPicked = meal.foodSourceOrInferred()
            repeatMeal(meal)
            foodStep = FoodStep.EDIT
        }
        // The step in front and the step behind it are the same `when`, told which to draw. Two
        // call sites rather than one: the panel is handed the open step, and the panel's backdrop
        // the step a pull off it lands on — so what comes back into focus is the page actually
        // being returned to rather than always the hub. The builder came off the products, and it
        // is the products it goes back to.
        val foodPage: @Composable (Modifier, FoodStep) -> Unit = { pageBase, shown ->
            when (shown) {
                FoodStep.HUB -> FoodHubPage(
                    modifier = pageBase,
                    selected = food.takeIf { it.title.isNotBlank() },
                    favorites = favorites,
                    recent = recentMeals,
                    onKind = { chosen ->
                        foodKind = chosen
                        foodStep = FoodStep.SEARCH
                    },
                    onFavorite = pickFavorite,
                    onRecent = pickRecent,
                    onEditSelected = { foodStep = FoodStep.EDIT },
                    onClearSelected = {
                        onFood(food.cleared())
                        foodCatalogId = null
                        foodPicked = null
                    },
                )

                FoodStep.SEARCH -> FoodSearchPage(
                    modifier = pageBase,
                    kind = foodKind,
                    onPhoto = takeFoodPhoto,
                    onBuildDish = {
                        // Every build opens empty: what went into the last dish is not a suggestion
                        // about this one, and a tick left over from it is a wrong meal waiting to be
                        // logged by somebody who did not look.
                        clearDish()
                        dishBuilding = true
                    },
                    favorites = favorites,
                    recent = recentMeals,
                    onBack = { foodStep = FoodStep.HUB },
                    onPick = { picked ->
                        foodCatalogId = picked.id
                        foodPicked = listOf(DishItem(picked, picked.defaultPortion.grams)).encodeDish()
                        onFood(food.withCatalogFood(picked, picked.defaultPortion, foodLocale))
                        foodStep = FoodStep.EDIT
                    },
                    onFavorite = pickFavorite,
                    onRecent = pickRecent,
                    building = dishBuilding,
                    picked = dishPicks,
                    onToggle = { food ->
                        dishPicks = if (food.id in dishPicks) dishPicks - food.id else dishPicks + food.id
                    },
                    onContinue = { foodStep = FoodStep.DISH },
                    savedDishes = savedDishes,
                    onSavedDish = { dish ->
                        // Back exactly as it was kept: the same rows, the same weights, the same fat
                        // contents, on the page that built them — so it can be adjusted before it is
                        // logged rather than only accepted. Kept it already is, which is what the page
                        // is told, so it drops the block asking to save it again.
                        val items = dish.items.decodeDish()
                        dishPicks = items.map { it.food.id }
                        dishGrams = items.associate { it.food.id to it.grams }
                        dishFats = items.mapNotNull { item -> item.fatPercent?.let { item.food.id to it } }.toMap()
                        dishSaved = true
                        foodStep = FoodStep.DISH
                    },
                    onDeleteDish = { dish ->
                        scope.launch { withContext(NonCancellable) { dao.deleteSavedDish(dish.key) } }
                    },
                )

                FoodStep.DISH -> DishBuilderPage(
                    modifier = pageBase,
                    foods = dishFoods,
                    grams = dishGrams,
                    fats = dishFats,
                    calories = dishItems.photoCalories(),
                    note = note,
                    onNote = { note = it },
                    onGrams = { picked, grams -> dishGrams = dishGrams + (picked.id to grams) },
                    onFat = { picked, percent -> dishFats = dishFats + (picked.id to percent) },
                    onRemove = { picked ->
                        dishPicks = dishPicks - picked.id
                        if (dishPicks.isEmpty()) leaveDishPage()
                    },
                    onBack = leaveDishPage,
                    alreadySaved = dishSaved,
                    onSaveDish = { name ->
                        val row = SavedDish(
                            key = savedDishKey(name),
                            name = name,
                            items = dishItems.encodeDish(),
                            createdAt = System.currentTimeMillis(),
                        )
                        scope.launch { withContext(NonCancellable) { dao.saveSavedDish(row) } }
                    },
                    onAdd = {
                        // The dish is one row in the journal, summed from its parts, and it is saved by
                        // the same road every other food takes — the state is already up to date,
                        // because every change on that page wrote it. Once it is written the assembly
                        // is over: the products go with it, so the next dish starts from nothing.
                        foodCatalogId = null
                        foodPicked = dishItems.encodeDish()
                        commitSave()
                    },
                )

                // The packet, read: the name is typed here, the product can be kept here, and the
                // figures go to the same form every other food ends in.
                FoodStep.LABEL -> ProductLabelPage(
                    modifier = pageBase,
                    photo = labelPhoto?.toUri() ?: Uri.EMPTY,
                    favorites = favorites,
                    onBack = leaveLabelPage,
                    onKeep = { name, reading ->
                        val row = reading.toFavorite(name, labelSource, System.currentTimeMillis())
                        scope.launch { withContext(NonCancellable) { dao.saveFavoriteFood(row) } }
                    },
                    onContinue = { name, reading ->
                        foodCatalogId = null
                        foodPicked = null
                        onFood(food.withLabel(labelSource, reading).copy(title = name.ifBlank { food.title }))
                        clearFoodPhoto(context, labelPhoto?.toUri())
                        labelPhoto = null
                        foodStep = FoodStep.EDIT
                    },
                )

                FoodStep.DONE -> FoodSuccessPage(
                    modifier = pageBase,
                    state = food,
                    onAddAnother = {
                        // A fresh sheet, not the last meal with its numbers still in it.
                        onFood(food.cleared())
                        note = ""
                        foodCatalogId = null
                        foodPicked = null
                        foodStep = FoodStep.HUB
                    },
                    onDone = onSaved,
                )

                FoodStep.EDIT -> Unit
            }
        }
        PwnPageHost(
            sheet = foodStep != FoodStep.HUB,
            onClose = leavePage,
            modifier = modifier,
            backdrop = foodBackdrop(foodStep)?.let { under -> { foodPage(Modifier, under) } }
                ?: when (foodStep) {
                    FoodStep.LABEL -> behind.backdrop
                    FoodStep.DONE -> returnPhoto?.let { shot -> { BackdropPhoto(shot) } }
                    else -> null
                },
        ) { pageBase ->
            foodPage(pageBase, foodStep)
        }
        return
    }

    // Not before the row has loaded: the day is what a new photograph is filed under, and an
    // unloaded row would file it under today. The same guard the card downstairs carries.
    if (showPhotoGallery && savedRowLoaded(entryId, original)) {
        PwnSheetPage(onClose = leavePage, modifier = modifier, backdrop = behind.backdrop) {
            ProgressPhotosScreen(
                modifier = Modifier,
                day = original?.day ?: rememberToday().toString(),
                onBack = { showPhotoGallery = false },
            )
        }
        return
    }

    if (showActivitySummary) {
        PwnSheetPage(onClose = leavePage, modifier = modifier, backdrop = behind.backdrop) {
            WorkoutSummaryScreen(
                modifier = Modifier,
                spec = spec,
                partner = partnerSpec,
                calories = machineKcal?.toDouble() ?: decodeSetsFromJson(workoutSetsJson, loadUnitFactor)
                    .takeIf { it.isNotEmpty() }?.groupBy { it.exercise(spec, partnerSpec) }
                    ?.entries?.sumOf { (exercise, completed) ->
                        val whole = completed.aggregate()
                        netActivityCalories(
                            loadedMet(exercise.code, exercise.met, profile.weightKg, whole.load / loadUnitFactor),
                            profile.restingKcalPerHour(),
                            whole.durationMinutes,
                        )
                    } ?: workoutCalories,
                duration = duration,
                sets = sets,
                reps = reps,
                load = load,
                loadUnitRes = if (profile.imperial) R.string.unit_lb else R.string.unit_kg,
                plannedSets = decodeSetsFromJson(workoutSetsJson, loadUnitFactor),
                note = note,
                onNote = { note = it },
                onDone = ::commitSave,
                onBack = { showActivitySummary = false },
                // A machine session has no planned sets to go back and correct, so its two figures are
                // typed here or nowhere.
                onDuration = if (machinePayload.isNotBlank()) {
                    { duration = it }
                } else {
                    null
                },
                machineKcal = machineKcal,
                onMachineKcal = { machineKcal = it },
                // Typing the machine's minutes away is allowed; saving nought minutes as a workout is
                // not, and Done is the same commit the form's own Save button is.
                doneEnabled = machinePayload.isBlank() || valid,
                excludeId = entryId,
            )
        }
        return
    }

    val scroll = if (kind.isEmpty()) chooserScroll else formScroll
    Column(
        modifier.fillMaxSize().panelVerticalScroll(scroll).padding(20.dp).padding(bottom = LocalBottomBarInset.current),
    ) {
        if (eating) {
            // The food form is a page of a flow rather than the whole editor, so it carries the
            // flow's furniture: the way back to the hub, and the heart for keeping this food.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (entryId == null) {
                    IconButton(onClick = {
                        foodStep = FoodStep.HUB
                    }, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    stringResource(R.string.editor_food_title),
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                FavoriteToggle(favoriteMarked) {
                    val key = favoriteKey(foodCatalogId, title)
                    scope.launch {
                        // Not cut off by leaving the form: the star was tapped, so the row changes.
                        withContext(NonCancellable) {
                            if (favoriteMarked) {
                                dao.deleteFavoriteFood(key)
                            } else {
                                dao.saveFavoriteFood(food.toFavorite(foodCatalogId, System.currentTimeMillis()))
                            }
                        }
                    }
                }
            }
        } else {
            Text(
                stringResource(
                    when {
                        entryId != null -> R.string.editor_edit_title
                        kind.isEmpty() -> R.string.add_sheet_title
                        else -> R.string.editor_new_title
                    },
                ),
                // Over the type chooser's three sections of cards, or over a form of several cards:
                // either way a long scroll, and headings are what a screen reader jumps between.
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(18.dp))
        if (kind.isEmpty()) {
            Text(
                stringResource(R.string.add_sheet_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(R.string.add_section_food),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(10.dp))
            RecordTypeCard(
                art = R.drawable.ic_art_food,
                icon = FoodIcon,
                tint = PwnOrange,
                background = PwnOrangeSoft,
                title = stringResource(R.string.add_meal),
                description = stringResource(R.string.add_meal_body),
                onClick = { kind = KIND_EAT },
            )
            // A glass, a cup, a drink and a cigarette: logged most often and typed least, so one row
            // of pictures under the meal — each the same dialog or screen its
            // widget opens, so a phone with no widget can still record them. Drink and tobacco sit
            // under food because that is when they are thought of. No alcohol or smoking callback
            // means the profile's age is not offered either ([AgeGate]): their places stay empty,
            // so the other two keep their size.
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RecordTypeIcon(R.drawable.ic_art_water, PwnBlue, stringResource(R.string.water_title)) {
                    onDrink?.invoke(WidgetRoute.WATER)
                }
                RecordTypeIcon(R.drawable.ic_art_coffee, PwnOrange, stringResource(R.string.caffeine_title)) {
                    onDrink?.invoke(WidgetRoute.CAFFEINE)
                }
                RecordTypeIcon(R.drawable.ic_art_beer, PwnOrange, stringResource(R.string.alcohol_title), onAlcohol)
                RecordTypeIcon(
                    R.drawable.ic_art_cigarette,
                    PwnOrange,
                    stringResource(R.string.smoking_title),
                    onSmoking,
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(R.string.add_section_activity),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(10.dp))
            RecordTypeCard(
                art = R.drawable.ic_art_sport,
                icon = ActivityIcon,
                tint = PwnPurple,
                background = PwnPurpleSoft,
                title = stringResource(R.string.add_workout),
                description = stringResource(R.string.add_workout_body),
                // Today's workout is chosen and run on the workouts page; an
                // earlier day's is written here, since a session cannot be run in the past.
                onClick = {
                    val today = initialDay == null || initialDay == LocalDate.now().toString()
                    onRoutines?.takeIf { today }?.invoke() ?: run { kind = KIND_ACTIVITY }
                },
            )
            Spacer(Modifier.height(10.dp))
            RecordTypeCard(
                art = R.drawable.ic_art_place,
                icon = WalkIcon,
                tint = PwnPurple,
                background = PwnPurpleSoft,
                title = stringResource(R.string.add_walk_route),
                description = stringResource(R.string.add_walk_route_body),
                onClick = { onTrack?.invoke() },
            )
            onWork?.let { open ->
                Spacer(Modifier.height(10.dp))
                RecordTypeCard(
                    art = R.drawable.ic_art_time,
                    icon = Icons.Outlined.Work,
                    tint = PwnPurple,
                    background = PwnPurpleSoft,
                    title = stringResource(R.string.work_title),
                    description = stringResource(R.string.work_add_body),
                    onClick = open,
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(R.string.add_section_health),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(10.dp))
            // One door rather than four: the editor behind it already asks which measurement this
            // is, so offering the same choice twice only makes the sheet longer.
            RecordTypeCard(
                art = R.drawable.ic_art_weight,
                icon = WeightIcon,
                tint = PwnIndigo,
                background = PwnIndigoSoft,
                title = stringResource(R.string.add_measurement),
                description = stringResource(R.string.add_measurement_body),
                onClick = {
                    measurementMode = "WEIGHT"
                    kind = KIND_MEASUREMENT
                },
            )
            Spacer(Modifier.height(10.dp))
            // IntrinsicSize.Min, because the two descriptions wrap to different numbers of lines in
            // some languages and a pair of side-by-side cards of different heights reads as a bug.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PwnCompactHealthCard(
                    R.drawable.ic_art_mood,
                    Icons.Outlined.Mood,
                    stringResource(R.string.add_mood),
                    stringResource(R.string.add_mood_body),
                ) { onMood?.invoke() }
                PwnCompactHealthCard(
                    R.drawable.ic_art_breathing,
                    Icons.Outlined.Air,
                    stringResource(R.string.add_breathing),
                    stringResource(R.string.add_breathing_body),
                ) { onBreathing?.invoke() }
            }
            Spacer(Modifier.height(10.dp))
            RecordTypeCard(
                art = R.drawable.ic_art_labs,
                icon = Icons.Outlined.Science,
                tint = PwnIndigo,
                background = PwnIndigoSoft,
                title = stringResource(R.string.add_labs),
                description = stringResource(R.string.add_labs_body),
                onClick = { onLabs?.invoke() },
            )
            Spacer(Modifier.height(10.dp))
            RecordTypeCard(
                art = R.drawable.ic_art_toilet,
                icon = Icons.Outlined.Wc,
                tint = PwnIndigo,
                background = PwnIndigoSoft,
                title = stringResource(R.string.bathroom_title),
                description = stringResource(R.string.add_bathroom_body),
                onClick = { onBathroom?.invoke() },
            )
            onScreenTime?.let { open ->
                Spacer(Modifier.height(10.dp))
                RecordTypeCard(
                    art = R.drawable.ic_art_eye,
                    icon = Icons.Outlined.Visibility,
                    tint = PwnIndigo,
                    background = PwnIndigoSoft,
                    title = stringResource(R.string.screen_time_title),
                    description = stringResource(R.string.screen_time_add_body),
                    onClick = open,
                )
            }
            onPain?.let { open ->
                Spacer(Modifier.height(10.dp))
                RecordTypeCard(
                    art = R.drawable.ic_art_pain,
                    icon = PainIcon,
                    tint = PwnIndigo,
                    background = PwnIndigoSoft,
                    title = stringResource(R.string.pain_title),
                    description = stringResource(R.string.pain_add_body),
                    onClick = open,
                )
            }
            onSymptom?.let { open ->
                Spacer(Modifier.height(10.dp))
                RecordTypeCard(
                    art = R.drawable.ic_art_doctor,
                    icon = SymptomIcon,
                    tint = PwnIndigo,
                    background = PwnIndigoSoft,
                    title = stringResource(R.string.symptom_chat_title),
                    description = stringResource(R.string.symptom_chat_add_body),
                    onClick = open,
                )
            }
            onIntimate?.let { open ->
                Spacer(Modifier.height(10.dp))
                RecordTypeCard(
                    art = R.drawable.ic_art_sex,
                    icon = Icons.Outlined.FavoriteBorder,
                    tint = PwnPurple,
                    background = PwnPurpleSoft,
                    title = stringResource(R.string.sexual_title),
                    description = stringResource(R.string.add_intimate_body),
                    onClick = open,
                )
            }
            Spacer(Modifier.height(12.dp))
            DataNoteLink(R.string.add_local_body)
            Spacer(Modifier.height(16.dp))
        } else {
            if (kind == KIND_EAT) {
                if (chatFoods.isEmpty()) {
                    FoodFields(
                        state = food,
                        catalogId = foodCatalogId,
                        onState = onFood,
                        onCatalogPick = { item ->
                            foodCatalogId = item?.food?.id
                            foodPicked = item?.let { listOf(it).encodeDish() }
                        },
                    )
                    // What the catalogue rows behind the meal usually contain, here as on the search
                    // row it may have been picked from: a plate the camera recognised and a dish put
                    // together never passed one, and without this would be logged with no reminder at all.
                    val pickedAllergens = remember(foodPicked) {
                        foodPicked?.decodeDish().orEmpty().flatMap { FoodAllergens.of(it.food) }.toSet()
                    }
                    if (pickedAllergens.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        FoodAllergenLine(pickedAllergens)
                    }
                } else {
                    chatFoods.forEachIndexed { index, item ->
                        if (index > 0) {
                            Spacer(Modifier.height(20.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(20.dp))
                        }
                        FoodFields(
                            state = item.state,
                            catalogId = item.catalogId,
                            preserveServing = true,
                            onState = { state -> updateChatFood(index) { it.copy(state = state) } },
                            onCatalogPick = { picked ->
                                updateChatFood(index) {
                                    it.copy(catalogId = picked?.food?.id, fatPercent = picked?.fatPercent)
                                }
                            },
                        )
                    }
                }
            } else if (imported != null) {
                ImportedSessionFields(
                    session = imported,
                    fallback = spec,
                    duration = duration,
                    onDuration = { duration = it.decimalInput() },
                    onSpec = {
                        activityCode = it.code
                        // The console's frame, where the session came with one, was about the
                        // session it came with, not about the exercise now named for it.
                        machineKcal = null
                        machinePayload = ""
                    },
                )
            } else if (kind == KIND_ACTIVITY) {
                ActivityFields(
                    spec = spec,
                    reported = typedWorkout,
                    sex = profile.sex,
                    duration = duration,
                    sets = sets,
                    reps = reps,
                    load = load,
                    calories = workoutCalories,
                    // The console's own energy, where the row holds one: Save writes it rather
                    // than the estimate ([workoutRow]), so the form shows it and lets it be typed.
                    machineKcal = machineKcal,
                    onMachineKcal = { kcal: Int? -> machineKcal = kcal }.takeIf { machinePayload.isNotBlank() },
                    loadUnitRes = if (profile.imperial) {
                        R.string.unit_lb
                    } else {
                        R.string.unit_kg
                    },
                    loadUnitFactor = loadUnitFactor,
                    workoutSetsJson = workoutSetsJson,
                    plannedFromChat = chatPlannedSets,
                    draftChanged = workoutDraftChanged,
                    partner = partnerSpec,
                    onSpec = {
                        activityCode = it.code
                        journalActivityConfirmed = true
                        // The machine's numbers were about the exercise that was chosen when they
                        // were taken; choosing another one leaves them describing nothing.
                        machineKcal = null
                        machinePayload = ""
                    },
                    onPartner = { partnerCode = it?.code },
                    onDuration = { duration = it.decimalInput() },
                    onSets = { sets = it.digitsOnly() },
                    onReps = { reps = it.digitsOnly() },
                    onLoad = { load = it.decimalInput() },
                    onDraftChanged = { workoutDraftChanged = it },
                    allowAdditionalExercises = entryId == null,
                    // Only on a new workout: a saved one is corrected here, and a template would
                    // start a different session on top of it.
                    onTemplates = onRoutines.takeIf { entryId == null },
                    onWorkoutFinished = { finished ->
                        // The flat columns on this row describe this row's exercise. The pair's
                        // other half is a row of its own, built from the sets flagged `second`, and
                        // the payload keeps the whole interleaved plan so the summary can read the
                        // round back in the order it happened.
                        val agg = finished.filter { it.exercise(spec, partnerSpec).code == spec.code }.aggregate()
                        duration = agg.durationMinutes.asInput()
                        sets = agg.sets.takeIf { it > 0 }?.toString().orEmpty()
                        reps = agg.reps.takeIf { it > 0 }?.toString().orEmpty()
                        // Already in the box's own units — see WorkoutAggregate.load. Converting
                        // here and dividing again in commitSave would store 100 lb as 100 kg.
                        load = agg.load.asInput()
                        // The payload, unlike the box above, stores kilograms.
                        workoutSetsJson = finishedSetsJson(finished, original?.measurementPayloadJson, loadUnitFactor)
                        // Ending the workout is the end of the workout: it goes straight
                        // from the rest to the summary, rather than dropping back onto a form whose
                        // every field this session has just filled in.
                        // The sets just run are this session's own account of it. A machine's
                        // figures from earlier in the same form would otherwise be saved over
                        // them — its energy instead of the sets' MET estimate, its frame instead
                        // of the plan.
                        machineKcal = null
                        machinePayload = ""
                        if (agg.sets > 0) behind.open { showActivitySummary = true }
                    },
                    onMachineResult = { result ->
                        if (machineReplacesSession(workoutDraftChanged, workoutSetsJson)) {
                            machineOverSession = result
                        } else {
                            takeMachineResult(result)
                        }
                    },
                    entryId = entryId,
                )
            } else if (simpleForm) {
                if (simpleChoices.isNotEmpty()) {
                    SimpleEntryFields(simpleChoices, simpleValue) { simpleValue = it }
                } else {
                    ReadOnlyEntryCard(original, profile.imperial)
                }
            } else {
                if (measurementMode == "SLEEP" && chatSleepDuration.isNotBlank()) {
                    NumberField(
                        chatSleepDuration,
                        { chatSleepDuration = it.decimalInput() },
                        stringResource(R.string.sleep_duration_label),
                        stringResource(R.string.unit_minutes),
                        decimal = true,
                    )
                    // Save takes a night only when this number and the dial agree, and while they
                    // do not it would stay dark with nothing to say why, so this line says what the
                    // dial shows: a chat that named both times and a length that contradicts them,
                    // or a number typed over.
                    sleepLengthMismatch(sleepDurationMinutes(sleepStartMinute, sleepEndMinute), chatSleepDuration)
                        ?.let { dialMinutes ->
                            Text(
                                stringResource(R.string.sleep_duration_mismatch, dialMinutes.toString()),
                                color = PwnWarn,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    Spacer(Modifier.height(12.dp))
                }
                MeasurementFields(
                    mode = measurementMode,
                    weightKg = measuredWeightKg,
                    heightCm = measuredHeightCm,
                    heartRateBpm = measuredHeartRateBpm,
                    sleepStartMinute = sleepStartMinute,
                    sleepEndMinute = sleepEndMinute,
                    unitSystem = profile.unitSystem,
                    source = measurementSource,
                    onMode = if (measurementTabsOffered(entryId)) {
                        { measurementMode = it }
                    } else {
                        null
                    },
                    onWeightKg = {
                        measuredWeightKg = it
                        measurementSource = "MANUAL"
                    },
                    onHeightCm = {
                        measuredHeightCm = it
                        measurementSource = "MANUAL"
                    },
                    onHeartRateBpm = {
                        measuredHeartRateBpm = it
                        measurementSource = "MANUAL"
                    },
                    onSensorHeartRateBpm = {
                        measuredHeartRateBpm = it
                        measurementSource = "PHONE_SENSOR"
                    },
                    // The length a chat said follows the dial once the dial is moved: the two are one
                    // night, and Save stays dark while they disagree, with nothing to say why.
                    onSleepStartMinute = {
                        sleepStartMinute = it
                        if (chatSleepDuration.isNotBlank()) {
                            chatSleepDuration = sleepDurationMinutes(it, sleepEndMinute).toString()
                        }
                        measurementSource = "MANUAL"
                    },
                    onSleepEndMinute = {
                        sleepEndMinute = it
                        if (chatSleepDuration.isNotBlank()) {
                            chatSleepDuration = sleepDurationMinutes(sleepStartMinute, it).toString()
                        }
                        measurementSource = "MANUAL"
                    },
                    girthCm = measuredGirthCm,
                    onGirthCm = { vital, cm ->
                        measuredGirthCm = measuredGirthCm + (vital to cm)
                        touchedGirth = touchedGirth + vital
                        measurementSource = "MANUAL"
                    },
                )
                // The scale says how much; a photograph says where. Only under a weight — a pulse
                // or a night's sleep has no picture worth keeping — and keyed by the row's day, so
                // an old weigh-in reopened shows the pictures of its own day, not today's. Not
                // before that row has loaded: a camera result redelivered after process death
                // would otherwise file the picture under today.
                val weighing = measurementMode == "WEIGHT" || measurementMode == "BOTH"
                if (weighing && savedRowLoaded(entryId, original)) {
                    Spacer(Modifier.height(14.dp))
                    ProgressPhotosCard { behind.open { showPhotoGallery = true } }
                }
                // A number a device already knows is not a number worth dragging a ruler to, and
                // the device list opens over this form rather than on a screen of its own: the
                // person is standing on the scale, and leaving the half-filled form to go and find
                // it elsewhere is how the reading would end up in a row this form never saw.
                if (measurementDeviceKinds(measurementMode).isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    MeasurementDeviceCard(measurementMode) { taken ->
                        taken.weightKg?.let { measuredWeightKg = it }
                        taken.heightCm?.let { measuredHeightCm = it }
                        taken.heartRateBpm?.let { measuredHeartRateBpm = it }
                        taken.bodyFatPercent?.let { deviceBodyFatPercent = it }
                        measurementSource = taken.source
                    }
                }
                Spacer(Modifier.height(14.dp))
                // What was recorded before is the context that makes today's number mean something.
                val history by remember(measurementMode) {
                    dao.recentMeasurements(measurementMode, MEASUREMENT_HISTORY_LIMIT)
                }.collectAsStateWithLifecycle(emptyList())
                MeasurementHistoryCard(history, measurementMode, profile.imperial) { onSaved() }
                Spacer(Modifier.height(12.dp))
                MeasurementTipCard(measurementMode)
            }

            // No note on a one-tap row: there was never a field to type one in, and for a mood the
            // note column holds the word for the face, so offering it here would invite a person to
            // overwrite the label with a thought.
            if (!simpleForm && !sessionWorkout) {
                Spacer(Modifier.height(12.dp))
                NoteField(note, { note = it })
            }
            Spacer(Modifier.height(12.dp))
            // The bin sits beside Save rather than as a full-width button above it: two wide
            // buttons stacked read as two equal choices, and deleting is the rarer one.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A row with nothing to choose has nothing to save; only the bin stays.
                if ((!simpleForm || simpleChoices.isNotEmpty()) && !sessionWorkout) {
                    EditorSaveButton(
                        modifier = Modifier.weight(1f),
                        label = stringResource(
                            when {
                                entryId != null -> R.string.editor_save_changes

                                // "Add food": the food screen is a
                                // thing you add, not a form you save.
                                eating -> R.string.food_add

                                else -> R.string.common_save
                            },
                        ),
                        // Dark until the row being corrected has loaded: the fields come back from
                        // a recreation before it does, and a Save in between would write the correction
                        // as a new row dated today.
                        enabled = valid && !saving && savedRowLoaded(entryId, original),
                    ) {
                        // A workout gets a finish screen between Save and the actual write; a meal
                        // gets one after it — see leaveSaved — because "add another" needs the first
                        // row already written. Every other kind commits the moment its button is
                        // tapped, and so does an imported session: the finish screen is drawn with
                        // the form's exercise, which for such a row is only the fallback.
                        if (kind == KIND_ACTIVITY && imported == null) {
                            behind.open { showActivitySummary = true }
                        } else {
                            commitSave()
                        }
                    }
                }
                // Only the one-tap kinds: every other form saves into a row the person can still
                // reach from the day's list, and its deletion lives there.
                original?.takeIf { simpleForm }?.let { entry ->
                    IconButton(
                        onClick = {
                            // As with Save, its flag included: this scope dies with the screen, and
                            // a deletion the person confirmed may not be half applied. A second tap
                            // on the bin would take the way back twice, out of the page underneath as
                            // well; and a phone turned over the deletion would bring the page back
                            // standing over a row that was gone, with nothing on it to press.
                            if (!saving) {
                                saving = true
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    writeThenMirror(
                                        page = scope,
                                        write = { db.deleteJournalEvent(entry.id) },
                                        release = leaveSaved,
                                        mirror = { writeLocalHealthIfAvailable(context, db) },
                                        failed = saveRefused,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.size(NOTE_BUTTON_HEIGHT),
                    ) {
                        Image(
                            themePainterResource(R.drawable.ic_art_delete),
                            stringResource(R.string.common_delete),
                            Modifier.size(26.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * A confirmed save or deletion in the order its page needs: [write], then [release] if [page] is
 * still standing, then [mirror].
 *
 * The release comes before the mirror. The mirror queues for the lock a whole Health Connect
 * exchange holds, so with the mirror first, while an exchange ran the row would be in Room and the
 * form would stay up with a dead button until the exchange ended; a phone turned in that wait would
 * bring the form back looking unsaved, and a second Save would insert the row again. And the
 * release, which navigates back, would run whenever the wait ended, over whichever page was up by
 * then.
 *
 * One block that cannot be cancelled, because [page] dies with its screen: a confirmed write that
 * is dropped loses the entry, and dropping only the mirror leaves the journal and Health Connect
 * disagreeing until the next full sync. That is also why [page] is asked rather than this
 * coroutine, which reads as alive for as long as the block runs.
 *
 * A write that throws — a full disk, a key the Keystore will not give — has written nothing, so
 * there is no way back to take and nothing to mirror: [failed] is called in place of both, for a
 * page still standing. Uncaught, it would end the app, as nothing above the page's scope handles it.
 *
 * How the write ends goes into [editorWrite] as well, for a page recreated while it ran: [page] is
 * the one that went, and its [release] or [failed] is never called.
 */
internal suspend fun writeThenMirror(
    page: CoroutineScope,
    write: suspend () -> Unit,
    release: () -> Unit,
    mirror: suspend () -> Unit,
    failed: () -> Unit,
): Unit = withContext(NonCancellable) {
    editorWrite.value = EditorWrite.RUNNING
    if (runCatching { write() }.isFailure) {
        editorWrite.value = EditorWrite.REFUSED
        if (page.isActive) failed()
        return@withContext
    }
    editorWrite.value = EditorWrite.WRITTEN
    if (page.isActive) release()
    mirror()
}

/** Where the last write an editor page confirmed stands — see [editorWrite]. */
internal enum class EditorWrite { RUNNING, WRITTEN, REFUSED }

/**
 * How the last save or deletion an editor page confirmed ended; null when none has run in this
 * process. Kept outside composition, because the page that began the write can be recreated while
 * it runs — turned, or put behind another — and the page that comes back has its fields and its
 * `saving` flag but not the coroutine. Taking the way back on the flag alone, a write that then
 * failed would close the page with the row unwritten and nothing said. None at all is a process
 * that died with the write, which then most likely went in, and the page takes the way back.
 *
 * Limitation: one slot for the process, as only one editor page writes at a time.
 */
internal val editorWrite = MutableStateFlow<EditorWrite?>(null)

/**
 * Whether the row being corrected is here to be corrected; a new entry has none to wait for.
 *
 * The form's fields come back from a recreation at once and the row a moment later, out of Room. A
 * Save in between would find no row to correct and write the correction as a new one, dated today,
 * with the row it was meant for left as it was; a photograph taken then would be filed under today.
 */
internal fun savedRowLoaded(entryId: Long?, loaded: JournalEntry?): Boolean = entryId == null || loaded != null

/**
 * Whether a workout is typed into the form — minutes, sets, reps and load — instead of run as a
 * session.
 *
 * A session times each set between its Start and its Stop, which is right while the sets are being
 * done. For a workout that is already over those are the seconds between two taps: a bench press
 * of forty minutes entered for yesterday would be saved as one of twenty seconds, at next to no
 * energy, and go to Health Connect that long. So what is over is told, as a workout told to the chat
 * is ([reported]): a new one for a day that is not today ([anotherDay]), and a [saved] row that holds
 * no sets to correct — one typed here, or one logged without per-set figures — which would otherwise
 * reopen as an empty session with no Save at all.
 *
 * "No sets" is asked of what the payload holds and not of whether there is one. A ride taken from a
 * training machine keeps the console's frame in that column, and a synced or a sensor's session
 * its own figures: neither is a set, and both would reopen as that empty session — a row whose minutes
 * and note could be read on the day's list and corrected nowhere.
 *
 * The exercises the phone counts keep their own form ([counted]): it has the fields already. An
 * [imported] session — a code the catalogue does not know — is typed whatever else is true: the
 * session it would otherwise run is the fallback exercise's, a plank offered over a morning run.
 */
internal fun workoutTyped(
    reported: Boolean,
    counted: Boolean,
    anotherDay: Boolean,
    saved: JournalEntry?,
    imported: Boolean = false,
): Boolean {
    val holdsNoSets = saved != null && saved.kind == KIND_ACTIVITY &&
        decodeSetsFromJson(saved.measurementPayloadJson).isEmpty()
    return reported || imported || (!counted && (anotherDay || holdsNoSets))
}

/** The most energy one meal's row may hold. A feast is a few thousand; past this the figure is a slip. */
private const val MAX_MEAL_KCAL = 10_000.0

/** The most minutes one workout's row may hold: it is filed under one day, and a day has no more. */
private const val MAX_WORKOUT_MINUTES = 1_440.0

/**
 * Whether [food] is a meal there is something to log about: a name, and energy — or a stated nought
 * for a portion of something, a glass of water or a diet cola — and no more than [MAX_MEAL_KCAL].
 *
 * Nought is not refused outright, or the catalogue's waters, teas and zero colas would open on an
 * Add that never lights. A name and a weight with no figure stated still make no meal: that is a form
 * left unfilled rather than a food without energy, and it would undercount the day without a word.
 * The top is capped too, or seven figures typed into a box would go into the day's balance as they
 * were.
 */
internal fun mealSaveable(food: FoodState): Boolean {
    val kcal = food.portionCalories
    val stated = food.reportedEnergy.isNotEmpty() ||
        listOf(food.protein, food.fat, food.carbs).any(String::isNotEmpty)
    return food.title.isNotBlank() && kcal <= MAX_MEAL_KCAL && (kcal > 0 || (stated && food.servingG > 0))
}

/** Whether [minutes] is a workout's length rather than nothing or a slip — see [MAX_WORKOUT_MINUTES]. */
internal fun workoutMinutesValid(minutes: Double): Boolean = minutes > 0 && minutes <= MAX_WORKOUT_MINUTES

/**
 * A console's [frame] carrying the energy its row is saved with: the console's own count, or the one
 * typed in its place off the console's screen, and none once that box is emptied and the row holds
 * the MET estimate instead.
 *
 * The frame is all that tells the pass re-costing every workout ([recalculatedActivityTimeline]) that
 * the figure was measured ([hasMachineEnergy]). Without it, a count typed over a frame that reported
 * none would be re-costed as the estimate at the next weigh-in, and an estimate saved over an emptied
 * box would never be re-costed at all.
 */
internal fun machineFrame(frame: String, kcal: Int?): String {
    val json = frame.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return frame
    if (kcal != null && kcal > 0) json.put("totalEnergyKcal", kcal) else json.remove("totalEnergyKcal")
    return json.toString()
}

/**
 * The plan a chat's workout plan opens its session on: [sets] sets of [reps] at [load], in the load
 * box's own unit, at most [MAX_PLAN_SETS] of them; nothing, and the session opens on its one empty
 * set, where the chat named none of the three.
 *
 * Put into the typed boxes only, which a session that has not run does not draw,
 * "three sets of ten at sixty" would open as one empty set.
 */
internal fun chatPlan(sets: Int?, reps: Int?, load: String): List<PlannedSet> {
    if (sets == null && reps == null && load.isEmpty()) return emptyList()
    return List((sets ?: 1).coerceIn(1, MAX_PLAN_SETS)) { PlannedSet(reps = reps?.toString().orEmpty(), load = load) }
}

/**
 * The payload a session's End hands the form: the [finished] sets, and the rest served before End on
 * the last of them — the minutes count it, and a payload without it would give the summary and the
 * row opened again a shorter session than the one the journal was handed.
 *
 * And, on a row being corrected, the exercise its [saved] payload says it was paired with. A pair is
 * two rows that each name the other ([encodeToJson]); one of them corrected without it would stop
 * saying so on the day's list, while the other went on naming it.
 */
internal fun finishedSetsJson(finished: List<PlannedSet>, saved: String?, loadUnitFactor: Double): String =
    finished.encodeToJson(
        supersetWith = supersetPartnerCode(saved),
        loadUnitFactor = loadUnitFactor,
        preserveTrailingRest = true,
    )

/**
 * The rows a new session is written as, this being the row its form built.
 *
 * One row cannot honestly hold two exercises: the calorie arithmetic and the muscle map both read a
 * single activity code. So a session that moved between exercises ([PlannedSet.exerciseCode]) is a
 * row for each run of sets, and a superset's second exercise ([PlannedSet.second]) a row of its own,
 * each with its own MET and energy, and each half of a pair naming the other. The last row is stamped
 * at the finish and each one before it where the next began: sessionInterval() runs a hand-logged
 * session backwards from occurredAt, so this keeps them from overlapping and in the order done. Only
 * one row may claim an imported record, so the rows split off this one claim none.
 *
 * The [plan] holds loads in the profile's unit, divided by [loadUnitFactor] into the kilograms a
 * row holds.
 */
internal fun JournalEntry.sessionRowsOf(
    plan: List<PlannedSet>,
    spec: ActivitySpec,
    partner: ActivitySpec?,
    loadUnitFactor: Double,
    bodyKg: Double,
    restingKcalPerHour: Double,
    titleOf: (ActivitySpec) -> String,
    zone: ZoneId = ZoneId.systemDefault(),
): List<JournalEntry> {
    fun dayOf(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    fun lengthOf(row: JournalEntry) = ((row.durationMinutes ?: 0.0) * 60_000).toLong().coerceAtLeast(1_000)
    fun leg(exercise: ActivitySpec, done: List<PlannedSet>, endAt: Long, payload: String?): JournalEntry {
        val aggregate = done.aggregate()
        val loadKg = (aggregate.load / loadUnitFactor).takeIf { it > 0 }
        return copy(
            id = 0,
            occurredAt = endAt,
            day = dayOf(endAt),
            title = titleOf(exercise),
            activityCode = exercise.code,
            met = exercise.met,
            durationMinutes = aggregate.durationMinutes,
            sets = aggregate.sets.takeIf { it > 0 },
            reps = aggregate.reps.takeIf { it > 0 },
            loadKg = loadKg.takeIf { exercise.input == ActivityInput.WEIGHTED_SETS },
            calories = netActivityCalories(
                loadedMet(exercise.code, exercise.met, bodyKg, loadKg),
                restingKcalPerHour,
                aggregate.durationMinutes,
            ),
            measurementPayloadJson = payload,
            healthConnectRecordKey = null,
        )
    }
    val legB = plan.filter { it.second }
    return when {
        plan.any { it.exerciseCode != null } -> {
            var endAt = occurredAt
            plan.completedExerciseGroups(spec, partner).asReversed().map { (exercise, completed) ->
                val pairedWith = when {
                    completed.any { it.exerciseCode != null } -> null
                    exercise.code == spec.code -> partner?.code
                    else -> spec.code
                }
                val payload = completed.map { it.copy(exerciseCode = null) }
                    .encodeToJson(pairedWith, loadUnitFactor, preserveTrailingRest = true)
                leg(exercise, completed, endAt, payload).also { endAt -= lengthOf(it) }
            }.asReversed()
        }

        partner != null && legB.any { it.completed } -> {
            val rowB = leg(
                partner,
                legB,
                occurredAt,
                legB.encodeToJson(spec.code, loadUnitFactor, preserveTrailingRest = true).ifBlank { null },
            )
            val startB = occurredAt - lengthOf(rowB)
            val rowA = copy(
                occurredAt = startB,
                day = dayOf(startB),
                measurementPayloadJson = plan.filterNot { it.second }
                    .encodeToJson(partner.code, loadUnitFactor, preserveTrailingRest = true).ifBlank { null },
            )
            listOf(rowA, rowB)
        }

        else -> listOf(this)
    }
}

/**
 * Whether taking a machine's numbers would throw away a session: one under way or its plan changed
 * ([draftChanged]), or sets End has already handed the form. The machine's account replaces the
 * plan, and sets done by hand would go with it unasked.
 */
internal fun machineReplacesSession(draftChanged: Boolean, workoutSetsJson: String): Boolean =
    draftChanged || decodeSetsFromJson(workoutSetsJson).isNotEmpty()

/**
 * The form as it stands when Save is tapped: everything [journalEntry] reads to build the row.
 *
 * Taken as one value rather than put together inside the screen out of some forty of its variables,
 * where no test could reach it, the rule each column follows can be run without a screen, and a save
 * cannot quietly drop what the form does not show. No defaults:
 * a field the screen forgot to hand over has to fail to compile, not save as nothing.
 */
internal data class EditorForm(
    val kind: String,
    val note: String,
    /** The meal as typed, or the chat's reviewed ingredients summed when there are [chatFoods]. */
    val food: FoodState,
    val chatFoods: List<ChatFoodDraft>,
    /** The catalogue rows the meal was filled from — see [savedFoodSource]. */
    val foodPicked: String?,
    /** The row's own code. An imported session's is not in the catalogue, and is not [spec]'s. */
    val activityCode: String,
    val spec: ActivitySpec,
    val specTitle: String,
    /** Sets, reps and load were typed rather than run — see [workoutTyped]. */
    val typedWorkout: Boolean,
    val duration: String,
    val sets: String,
    val reps: String,
    /** In the unit its box is labelled with; [loadUnitFactor] of them make a kilogram. */
    val load: String,
    val loadUnitFactor: Double,
    /** The MET estimate for the minutes typed, which a console's own [machineKcal] outranks. */
    val workoutCalories: Double,
    val workoutSetsJson: String,
    val machineKcal: Int?,
    val machinePayload: String,
    /** The tab the measurement form stands on, "BOTH" for the body row, which has none. */
    val measurementMode: String,
    val measurementTitle: String,
    val weightKg: Double,
    val heightCm: Int,
    val heartRateBpm: Int,
    val sleepStartMinute: Int,
    val sleepEndMinute: Int,
    val girthCm: Map<Vital, Int>,
    val touchedGirth: Set<Vital>,
    val measurementSource: String,
    /** What a scale measured beside the weight; the form has no field for it. */
    val bodyFatPercent: Double?,
)

/**
 * The row a tap on Save writes: [previous] as the form corrects it, or a new one for
 * [recordingDay] at [now].
 *
 * One rule for every column: a form writes what it shows, and what it does not show it keeps. The
 * columns nobody's form shows are kept in [carriedRow]; each kind's own are written over them by
 * [mealRow], [workoutRow] and [measurementRow]. A one-tap row never comes here — it is rebuilt by
 * the factory that wrote it ([rebuiltSimpleEntry]).
 */
internal fun EditorForm.journalEntry(
    previous: JournalEntry?,
    recordingDay: LocalDate,
    now: LocalTime,
    zone: ZoneId = ZoneId.systemDefault(),
): JournalEntry {
    val night = dialledNight(previous, recordingDay, zone)
    val occurredAt = night?.second ?: previous?.occurredAt
        ?: recordingDay.atTime(now).atZone(zone).toInstant().toEpochMilli()
    // A night is filed under the day it ended on, wherever its row stood before.
    val day = night?.second?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }
        ?: previous?.day ?: recordingDay.toString()
    val carried = carriedRow(previous, day, occurredAt)
    return when (kind) {
        KIND_EAT -> mealRow(carried, previous)
        KIND_ACTIVITY -> workoutRow(carried, previous)
        else -> measurementRow(carried, previous, night)
    }
}

/** The night on the dial as two instants, while the sleep tab is what is being saved. */
private fun EditorForm.dialledNight(
    previous: JournalEntry?,
    recordingDay: LocalDate,
    zone: ZoneId,
): Pair<Long, Long>? {
    if (kind != KIND_MEASUREMENT || measurementMode != "SLEEP") return null
    // A corrected night ends on the day it ended on; only a new one is of the day being recorded.
    val endDay = previous?.sleepEndAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: recordingDay
    return sleepEpochRange(endDay, sleepStartMinute, sleepEndMinute, zone)
}

/**
 * The row's identity and time, its note, and every column no form here has a field for, as
 * [previous] held them.
 *
 * Left out of the save, those columns would be reset to null by every save: a scale's
 * body-fat figure, a mood's score, a practice's breath count, and the payload that is the whole
 * content of a blood-pressure or glucose reading. An editor may not delete what it does not show.
 * The readings are here for the same reason — a meal or a workout shows none of them, and a
 * measurement only its own tab's.
 */
private fun EditorForm.carriedRow(previous: JournalEntry?, day: String, occurredAt: Long) = JournalEntry(
    id = previous?.id ?: 0,
    day = day,
    occurredAt = occurredAt,
    kind = kind,
    title = "",
    calories = 0.0,
    note = note.trim(),
    measurementSource = editedMeasurementSource(
        previous = previous?.measurementSource,
        kind = kind,
        selected = measurementSource,
    ),
    measurementWeightKg = previous?.measurementWeightKg,
    measurementHeightCm = previous?.measurementHeightCm,
    measurementHeartRateBpm = previous?.measurementHeartRateBpm,
    sleepStartAt = previous?.sleepStartAt,
    sleepEndAt = previous?.sleepEndAt,
    measurementPayloadJson = previous?.measurementPayloadJson,
    // Identity, not a field: no control on this screen writes it, and dropping it would let the
    // next sync import the record again.
    healthConnectRecordKey = previous?.healthConnectRecordKey,
    bodyFatPercent = bodyFatPercent ?: previous?.bodyFatPercent,
    moodScore = previous?.moodScore,
    energyScore = previous?.energyScore,
    breathCycles = previous?.breathCycles,
    caffeineMg = previous?.caffeineMg,
)

/** A meal's own columns over [carried]. */
private fun EditorForm.mealRow(carried: JournalEntry, previous: JournalEntry?) = carried.copy(
    title = food.title.trim(),
    subtitle = food.subtitle.trim(),
    calories = food.portionCalories,
    // Portion figures, whichever basis they were typed on: everything that reads a journal row
    // wants what was actually eaten.
    proteinG = food.portionProtein,
    fatG = food.portionFat,
    carbsG = food.portionCarbs,
    servingG = food.servingG,
    fiberG = food.portionExtra(ExtraNutrient.FIBER),
    sugarG = food.portionExtra(ExtraNutrient.SUGAR),
    saturatedFatG = food.portionExtra(ExtraNutrient.SATURATED_FAT),
    sodiumMg = food.portionExtra(ExtraNutrient.SODIUM),
    cholesterolMg = food.portionExtra(ExtraNutrient.CHOLESTEROL),
    enteredPer100g = food.basis.per100,
    // The chat's reviewed ingredients are kept here, beside whatever the row's payload already held.
    measurementPayloadJson = chatFoods.foodDraftPayload(previous?.measurementPayloadJson),
    // Worked out rather than carried: a corrected portion rescales it, and a meal renamed away from
    // its row has already let it go.
    foodSource = if (chatFoods.isEmpty()) {
        savedFoodSource(foodPicked, food.servingG)
    } else {
        chatFoods.combinedFoodSource()
    },
)

/** A workout's own columns over [carried]. */
private fun EditorForm.workoutRow(carried: JournalEntry, previous: JournalEntry?): JournalEntry {
    val row = carried.copy(
        // A workout's is the session it was done in, or a synced session's measured figures:
        // neither is typed here, and a corrected row keeps it.
        subtitle = previous?.subtitle.orEmpty(),
        // The code as it stands, not the fallback spec's: "HEALTH_CONNECT" is what keeps a synced
        // session out of the export that would duplicate it.
        activityCode = activityCode,
        durationMinutes = duration.number(),
        // A machine's frame and a hand-logged plan share this column. Taking a machine's numbers
        // clears the plan, so in practice only one of the two is ever set; where both somehow are,
        // the plan wins, because a strength row's reps and kilograms are the only record of it
        // there is.
        measurementPayloadJson = workoutSetsJson
            .ifBlank { machineFrame(machinePayload, machineKcal) }
            .ifBlank { null },
    )
    return if (spec.code != activityCode) {
        // An imported session, drawn with a catalogue exercise it is not. "Morning Run" is its own
        // name, and the fallback's MET and input kind are not its own either: the energy they give
        // is an estimate of nothing, and judging its set columns by them would strip them.
        row.copy(
            title = previous?.title ?: specTitle,
            calories = previous?.calories ?: workoutCalories,
            met = previous?.met,
            sets = previous?.sets,
            reps = previous?.reps,
            loadKg = previous?.loadKg,
        )
    } else {
        row.copy(
            title = specTitle,
            // A console measured the work; the MET table estimates a population's average for the
            // exercise. Where the machine spoke, its figure is the one about this ride.
            calories = machineKcal?.toDouble() ?: workoutCalories,
            met = spec.met,
            // A session's own sets count whatever the exercise is timed by — a plank held for three
            // sets is three sets too — and so do the ones typed for a workout that is over.
            sets = sets.intNumber().takeIf {
                it > 0 && (workoutSetsJson.isNotBlank() || spec.input != ActivityInput.DURATION || typedWorkout)
            },
            // A counted exercise has no sets, so its repetitions are the whole session: 300 rope
            // jumps go in the same column as 3 × 10 push-ups.
            reps = reps.intNumber().takeIf {
                it > 0 && (spec.input != ActivityInput.DURATION || spec.counted || typedWorkout)
            },
            loadKg = (load.number() / loadUnitFactor).takeIf {
                it > 0 && (spec.input == ActivityInput.WEIGHTED_SETS || typedWorkout)
            },
        )
    }
}

/**
 * A measurement's own columns over [carried]: the reading of the tab that is open, and no other.
 *
 * The tab strip has no BOTH tab, so correcting one half of a row that carries both readings must
 * not null the half the tab does not show. Kept for weight and height alone, the rule would let a
 * touch on the Pulse tab on the body row onboarding writes for every user — weight and height
 * together, and no tab lit, because none of the four equals BOTH — rewrite it as a 72 bpm pulse
 * nobody measured, and take the two readings out of Room and, on the next mirror, out of Health
 * Connect. So every column keeps what the open tab does not own. The strip itself is not drawn over
 * a saved row — see [measurementTabsOffered] — so a correction cannot change tabs; the rule stands
 * for whatever else a row carries.
 */
private fun EditorForm.measurementRow(
    carried: JournalEntry,
    previous: JournalEntry?,
    night: Pair<Long, Long>?,
): JournalEntry {
    val mode = measurementMode.takeIf { kind == KIND_MEASUREMENT }
    val body = mode == "BOTH"
    return carried.copy(
        title = measurementTitle,
        measurementWeightKg = weightKg.takeIf { mode == "WEIGHT" || body } ?: previous?.measurementWeightKg,
        measurementHeightCm = heightCm.takeIf { mode == "HEIGHT" || body } ?: previous?.measurementHeightCm,
        measurementHeartRateBpm = heartRateBpm.takeIf { mode == "HEART_RATE" } ?: previous?.measurementHeartRateBpm,
        sleepStartAt = night?.first ?: previous?.sleepStartAt,
        sleepEndAt = night?.second ?: previous?.sleepEndAt,
        measurementPayloadJson = if (mode == "GIRTH") {
            // Merged into whatever the row already carried rather than replacing it: a payload
            // this form does not show is a payload it may not delete.
            girthPayload(previous?.measurementPayloadJson, girthCm, touchedGirth)
        } else {
            previous?.measurementPayloadJson
        },
    )
}

/** What a measurement saved from [mode]'s tab is titled; the tape and the body row share the plain one. */
private fun measurementTitleRes(mode: String): Int = when (mode) {
    "WEIGHT" -> R.string.measurement_weight_title
    "HEIGHT" -> R.string.measurement_height_title
    "HEART_RATE" -> R.string.measurement_heart_rate_title
    "SLEEP" -> R.string.measurement_sleep_title
    else -> R.string.measurement_log_title
}

/**
 * Whether the measurement form can show everything a [saved] row carries; a new entry it always can.
 *
 * A blood pressure, an SpO2 reading or a body-composition row carries its numbers in the payload
 * and in bodyFatPercent, which no field on this screen renders, and such a row is shown read-only
 * instead. The tape's three girths live in the payload too, and those the form does show: a waist
 * saved here would otherwise reopen as a card saying it was logged with a single tap and could only
 * be deleted. So a payload holding the tape's keys and nothing else is the form's own.
 */
internal fun measurementFormShows(saved: JournalEntry?): Boolean {
    if (saved == null) return true
    val payload = saved.measurementPayloadJson
    return saved.bodyFatPercent == null && (payload == null || payload.holdsOnlyGirths())
}

private fun String.holdsOnlyGirths(): Boolean {
    val payload = this
    val keys = runCatching { JSONObject(payload).keys().asSequence().toList() }.getOrNull()
    return keys != null && keys.all { key -> key == "schema" || GIRTH_VITAL_ORDER.any { it.key == key } }
}

/**
 * The night a chat told, as the dial's two hands: [start] and [end] where it named them, and where
 * it named one or neither, the missing hand set from the [minutes] it said the night lasted.
 *
 * "Slept six hours" names no clock time at all. The dial would then stay where it opens ([opensAt],
 * eleven to seven) beside a box reading 360, and Save — which takes a night only when the two
 * agree — would stay dark with nothing to say why. The waking is kept and the falling asleep counted
 * back from it, since the morning is the end most people can still name. A length that is no night,
 * nought or a day and more, moves nothing.
 */
internal fun chatNight(start: Int?, end: Int?, minutes: Int, opensAt: Pair<Int, Int>): Pair<Int, Int> {
    val wake = end ?: opensAt.second
    return when {
        minutes !in 1..1_439 || (start != null && end != null) -> (start ?: opensAt.first) to wake
        start != null -> start to (start + minutes) % 1_440
        else -> (wake - minutes + 1_440) % 1_440 to wake
    }
}

/**
 * The night the dial shows, in minutes, where the length in the box beside it says another; null
 * while the two agree or the box is empty. One answer for the two places that ask: Save, which
 * takes a night only when they agree ([measurementValid]), and the line under the box that says
 * what the dial shows — so the button is never dark over a disagreement the form has not named.
 */
internal fun sleepLengthMismatch(dialMinutes: Int, chatSleepDuration: String): Int? =
    dialMinutes.takeIf { chatSleepDuration.isNotBlank() && it != chatSleepDuration.number().toInt() }

/**
 * Whether the measurement form, open on [mode], holds a reading to save.
 *
 * Each tab is judged by its own control. Falling through to the last branch, which is the body
 * row's — weight and height together, and those two open on the profile's figures — the tape would
 * have Save lit over three rulers that read "Not measured", writing a measurement with no value in it.
 * A tape has something to save once it holds a girth somebody measured ([girthMeasured]): a ruler
 * moved, or the reading of the row being corrected.
 */
internal fun measurementValid(
    mode: String,
    weightKg: Double,
    heightCm: Int,
    heartRateBpm: Int,
    sleepMinutes: Int,
    chatSleepDuration: String,
    girthMeasured: Boolean,
): Boolean = when (mode) {
    "WEIGHT" -> weightKg in PLAUSIBLE_KG
    "HEIGHT" -> heightCm in 50..250
    "HEART_RATE" -> heartRateBpm in 30..250
    "SLEEP" -> sleepMinutes in 1..1_439 && sleepLengthMismatch(sleepMinutes, chatSleepDuration) == null
    "GIRTH" -> girthMeasured
    else -> weightKg in PLAUSIBLE_KG && heightCm in 50..250
}

/**
 * Whether the measurement form draws its tab strip: over a new entry only.
 *
 * A saved row is corrected as what it is. The strip over one would turn it into another record: "Sleep"
 * on a weighing from the third would date the row today, at the end of a night nobody logged, with the
 * weight still on it, and "Pulse" would write the slider's untouched 72 beside it. A reading of another
 * kind is a new entry.
 */
internal fun measurementTabsOffered(entryId: Long?): Boolean = entryId == null

/**
 * The source a saved measurement reports.
 *
 * A measurement form shows this, so the form decides it: pinning an imported row to HEALTH_CONNECT
 * would make the screen read "manual" while it saved something else. Nothing depends on such a pin
 * — provenance is [JournalEntry.healthConnectRecordKey], which no control here can write — so an
 * imported reading someone corrects by hand may honestly say so. The kinds with no source field
 * inherit, because for them the column is the only place the origin is shown.
 */
internal fun editedMeasurementSource(previous: String?, kind: String, selected: String): String? = when {
    kind == KIND_MEASUREMENT -> if (previous == "PHONE_USAGE_ESTIMATE") "MANUAL" else selected
    previous == "HEALTH_CONNECT" -> previous
    else -> null
}

internal fun sleepDurationMinutes(startMinute: Int, endMinute: Int): Int =
    (endMinute.coerceIn(0, 1_439) - startMinute.coerceIn(0, 1_439) + 1_440) % 1_440

/**
 * The instants two picked clock times name, resolved on the wall clock rather than on the epoch.
 *
 * Adding minutes to the start of the day walks the instant time line, so on a day that gains or
 * loses an hour it lands an hour away from the time that was picked — and the transition, 02:00 to
 * 03:00, sits inside almost every night's range. Building the local time and zoning it lets the
 * calendar resolve the gap and the overlap the way the person reading a clock would.
 */
internal fun sleepEpochRange(
    endDay: LocalDate,
    startMinute: Int,
    endMinute: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): Pair<Long, Long> {
    val safeStart = startMinute.coerceIn(0, 1_439)
    val safeEnd = endMinute.coerceIn(0, 1_439)
    val startDay = if (safeStart >= safeEnd) endDay.minusDays(1) else endDay
    val start = startDay.atTime(safeStart / 60, safeStart % 60).atZone(zone).toInstant()
    val end = endDay.atTime(safeEnd / 60, safeEnd % 60).atZone(zone).toInstant()
    return start.toEpochMilli() to end.toEpochMilli()
}

/**
 * The form's one filled action, on the note button's height and radius.
 *
 * A floor rather than a height: a label that wraps at a large font size, or in a language whose
 * "Save changes" runs long, grows the button instead of being clipped inside it. Under the finger
 * the corners square off to the medium radius and spring back when it lifts — the pressed shape
 * [PwnWideButton] already wears.
 */
@Composable
private fun EditorSaveButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        if (pressed) PwnCorners.medium else NOTE_BUTTON_RADIUS,
        PwnMotion.fastSpatial(),
        label = "save corner",
    )
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = NOTE_BUTTON_HEIGHT),
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        interactionSource = interaction,
    ) {
        Text(label, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RecordTypeCard(
    art: Int?,
    icon: ImageVector,
    tint: Color,
    background: Color,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(PwnCorners.extraLarge),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PwnGlyph(art, icon, background, null)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // The muted grey taken down a further 22% by alpha composites to #7C8697 on this
                // card's #EDF2FC, which is 3.27:1 — well under the 4.5:1 a 14sp line needs, and
                // alpha is the one thing the darker muted token cannot rescue. The same alpha over
                // the ink is #465162: 7.16:1 by day, and #C7CCD7 at 7.01:1 at night, and the title
                // above it moves to the full ink so it stays the darker of the two.
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Drawn, not said: the card is one button already, and the glyph merged into its name
            // would read a punctuation mark out after every one of the type cards.
            Text(
                "›",
                Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One of a row of type cards that is only its picture, named for a screen reader by [title]. */
@Composable
private fun RowScope.RecordTypeIcon(art: Int, tint: Color, title: String, onClick: (() -> Unit)?) {
    if (onClick == null) {
        Spacer(Modifier.weight(1f))
        return
    }
    Card(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(PwnCorners.extraLarge),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f)),
    ) {
        Image(
            themePainterResource(art),
            title,
            Modifier.align(Alignment.CenterHorizontally).padding(14.dp).size(PwnDimensions.glyphSize),
        )
    }
}

/** A note is context, not an essay; the counter says so before the field stops accepting text. */
internal const val NOTE_MAX_CHARS = 120

/** How many past readings the history card shows before deferring to the calendar. */
private const val MEASUREMENT_HISTORY_LIMIT = 3

/** The five things this editor records, in the order the tab strip shows them. */
private val MEASUREMENT_TABS = listOf(
    "WEIGHT" to R.string.measurement_weight_title,
    "HEIGHT" to R.string.measurement_height_title,
    "HEART_RATE" to R.string.measurement_heart_rate_title,
    "SLEEP" to R.string.measurement_sleep_title,
    "GIRTH" to R.string.measurement_girth_title,
)

/**
 * The three circumferences, in the order the form asks for them, and where each is stored.
 *
 * The keys are [Vital]'s own, so a reading typed here and a reading that arrives from anywhere else
 * land in the same place and are read back by the same parser.
 */
internal val GIRTH_VITAL_ORDER = listOf(Vital.WAIST, Vital.HIP, Vital.CHEST)

/** Where a wheel opens for somebody who has never measured that circumference, in centimetres. */
private val GIRTH_DEFAULT_CM = mapOf(Vital.WAIST to 80, Vital.HIP to 95, Vital.CHEST to 95)

/**
 * What [vital]'s ruler offers, in centimetres: exactly the [Vital.valid] its reading is read back
 * through ([girthCm]). One range for all three starting at 30 would let a hip of 35 be written and
 * then dropped as unreadable — "not measured" on the very row it was saved to.
 */
internal fun girthRulerCm(vital: Vital): IntRange =
    vital.valid.start.roundToInt()..vital.valid.endInclusive.roundToInt()

/** The girths a measurement row already holds, read through the vitals' own payload keys. */
internal fun JournalEntry.girthCm(): Map<Vital, Int> {
    val payload = measurementPayloadJson ?: return emptyMap()
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyMap()
    return GIRTH_VITAL_ORDER.mapNotNull { vital ->
        json.optDouble(vital.key, Double.NaN)
            .takeIf { !it.isNaN() && it in vital.valid }
            ?.let { vital to it.roundToInt() }
    }.toMap()
}

/**
 * The three wheels' values across a rotation, as two lists a Bundle will take.
 *
 * A map of an enum to an Int is not one of the types [rememberSaveable] knows, and the pair of
 * lists is smaller than teaching it one.
 */
private val GIRTH_SAVER: Saver<MutableState<Map<Vital, Int>>, Any> = listSaver(
    save = { state -> state.value.map { (vital, cm) -> "${vital.name}=$cm" } },
    restore = { saved ->
        mutableStateOf(
            saved.mapNotNull { entry ->
                val (name, cm) = entry.split("=", limit = 2).let { it[0] to it.getOrNull(1) }
                val vital = GIRTH_VITAL_ORDER.firstOrNull { it.name == name }
                val value = cm?.toIntOrNull()
                if (vital != null && value != null) vital to value else null
            }.toMap(),
        )
    },
)

/** The same, for which of the three the person has actually moved. */
private val GIRTH_TOUCHED_SAVER: Saver<MutableState<Set<Vital>>, Any> = listSaver(
    save = { state -> state.value.map(Vital::name) },
    restore = { saved ->
        mutableStateOf(saved.mapNotNull { name -> GIRTH_VITAL_ORDER.firstOrNull { it.name == name } }.toSet())
    },
)

private fun measurementIcon(mode: String): ImageVector = when (mode) {
    "HEIGHT", "GIRTH" -> HeightIcon
    "HEART_RATE" -> HeartIcon
    "SLEEP" -> SleepIcon
    else -> WeightIcon
}

/**
 * The drawn set covers four of the five measurements — the ruler, the heart, the moon and the
 * scales — and girth borrows the ruler.
 */
private fun measurementArt(mode: String): Int = when (mode) {
    "HEIGHT", "GIRTH" -> R.drawable.ic_art_height
    "HEART_RATE" -> R.drawable.ic_art_pulse
    "SLEEP" -> R.drawable.ic_art_sleep
    else -> R.drawable.ic_art_weight
}

internal fun measurementTipRes(mode: String): Int = when (mode) {
    "HEIGHT" -> R.string.measurement_tip_height
    "HEART_RATE" -> R.string.measurement_tip_heart_rate
    "SLEEP" -> R.string.measurement_tip_sleep
    "GIRTH" -> R.string.measurement_tip_girth
    else -> R.string.measurement_tip_weight
}

/**
 * One strip instead of two rows of chips: five choices that are mutually exclusive read as a
 * segmented control, and the icon carries the meaning faster than the word underneath it.
 */
@Composable
private fun MeasurementTabs(mode: String, onMode: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PwnCorners.largeIncreased),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // IntrinsicSize.Min, with every pill filling it: a label that wraps further at a large font
        // size makes its own cell taller, and the lit pill has to stand as tall as its neighbours
        // rather than a different height from the strip around it.
        Row(
            Modifier.selectableGroup().height(IntrinsicSize.Min).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MEASUREMENT_TABS.forEach { (value, titleRes) ->
                val selected = mode == value
                // The lit tab is a solid pill of the primary with its glyph and word in onPrimary,
                // the treatment PwnSegmentedControl gives its answer. Against the strip the pill
                // is 5.74:1 by day and 8.25:1 at night, and the word on it 6.44:1 and 10.61:1. A
                // pale pill would be 1.06:1 against the strip, and at night its word would be
                // the very tint of the unlit ones. `selectable` says the state to TalkBack; the
                // weight below still says it without hue. The fill fades rather than jumps, once
                // per tap, and from the primary made clear rather than from transparent black,
                // which would have dragged it through grey on the way.
                val scheme = MaterialTheme.colorScheme
                val fill by animateColorAsState(
                    if (selected) scheme.primary else scheme.primary.copy(alpha = 0f),
                    PwnMotion.effects(),
                    label = "tab fill",
                )
                val content by animateColorAsState(
                    if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                    PwnMotion.effects(),
                    label = "tab ink",
                )
                val pill = RoundedCornerShape(PwnCorners.large)
                Surface(
                    // Clipped before the tap so the ripple takes the pill's shape, not its box.
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clip(pill)
                        .selectable(selected = selected, role = Role.Tab) { onMode(value) },
                    shape = pill,
                    color = fill,
                ) {
                    Column(
                        // 8dp rather than 10dp: the label below is allowed to wrap, and the 2dp
                        // given back on each edge covers a quarter of the 16dp a second line
                        // costs, so a cell grows 62dp -> 74dp rather than 62dp -> 78dp.
                        Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            measurementIcon(value),
                            null,
                            tint = content,
                            modifier = Modifier.size(22.dp),
                        )
                        // Five weighted cells leave under 60dp of text width on a 360dp screen,
                        // and at 11sp "Fréquence cardiaque" wants ~116dp, "Frequência cardíaca"
                        // the same and "Herzfrequenz" ~75dp. No line cap and no ellipsis: at a
                        // large font size a long name needs a third line, and a cut name is a tab
                        // nobody can read. A single overlong word breaks at a character boundary,
                        // which reads as a typo ("Сердцебиен / ие"), so where a language has a short
                        // word for the measurement the title is that word: "Пульс", "Puls".
                        Text(
                            stringResource(titleRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = content,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** Recent readings of the kind being edited, newest first. */
@Composable
internal fun MeasurementHistoryCard(
    entries: List<JournalEntry>,
    mode: String,
    imperial: Boolean,
    onAll: () -> Unit,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.measurement_history),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                // A clickable Text is exactly its own bounds — 14sp on one line is 20dp tall, 42%
                // of the 48dp minimum, because LocalMinimumInteractiveComponentSize only reaches
                // Material components. 14dp above and below makes the box 48dp without moving the
                // words, the clip keeps the ripple to a pill, and the role is what a bare Text
                // never announces.
                Text(
                    stringResource(R.string.measurement_history_all),
                    color = PwnAccentInk,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(PwnCorners.medium))
                        .clickable(role = Role.Button, onClick = onAll)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.measurement_history_empty),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            } else {
                entries.forEachIndexed { index, entry ->
                    MeasurementHistoryRow(entry, mode, imperial)
                    if (index < entries.lastIndex) HorizontalDivider(color = PwnBorder)
                }
            }
        }
    }
}

/** The tip is its own tinted card, not a footnote pinned under the history. */
@Composable
internal fun MeasurementTipCard(mode: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PwnCorners.extraLarge),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Lightbulb,
                null,
                tint = PwnMuted,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    stringResource(R.string.measurement_tip),
                    style = MaterialTheme.typography.labelLarge,
                    // Ink by day and the muted tone at night, which is what PwnAccentInk
                    // resolves to: 14.04:1 on this container by day and 5.33:1 at night, over
                    // the 4.5:1 a 14sp label needs. The lightbulb keeps the fill accent: a 20dp
                    // glyph is judged at 3:1, which it clears.
                    color = PwnAccentInk,
                )
                Text(
                    stringResource(measurementTipRes(mode)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Lowest and highest pulse the slider reaches. Not a resting range such as 40-140: that could not
 * log a working heart at 165, and — worse — a reading a strap or a ring had already written above
 * 140 would be dragged back to 140 by the first touch of the ruler, because the same constants clamp
 * what the ruler hands back. The bounds are the ones the save rule accepts, 30..250, so the control
 * and the validity check agree.
 */
private const val PULSE_MIN = 30
private const val PULSE_MAX = 250

/**
 * The value card: the unit in small grey above, the number large, the ruler under
 * it. The number is a readout, not a field — the ruler is where the value is chosen.
 */
@Composable
private fun RulerCard(
    label: String,
    headline: String,
    values: List<Int>,
    value: Int,
    tickLabel: (Int) -> String,
    onValue: (Int) -> Unit,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(label, color = PwnMuted, style = MaterialTheme.typography.bodyLarge)
            Text(
                headline,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            RulerPicker(values = values, value = value, onValue = onValue, label = tickLabel)
        }
    }
}

/**
 * The height card: the reading large with its unit beside it, and the vertical
 * ruler under them doing the choosing.
 */
@Composable
private fun VerticalRulerCard(
    label: String,
    headline: String,
    unit: String,
    values: List<Int>,
    value: Int,
    tickLabel: (Int) -> String,
    onValue: (Int) -> Unit,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(label, color = PwnMuted, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    headline,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        unit,
                        color = PwnMuted,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            VerticalRulerPicker(values = values, value = value, onValue = onValue, label = tickLabel)
        }
    }
}

/**
 * Sleep as this form records it: the night drawn on a 24-hour dial with a moon and a sun to
 * drag, and under it the same times as text with a pencil — tapping that row unfolds the two clock
 * wheels for anyone who knows the minutes exactly. Two instruments, one value.
 */
@Composable
private fun SleepCard(startMinute: Int, endMinute: Int, onStart: (Int) -> Unit, onEnd: (Int) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.measurement_sleep_title),
                color = PwnMuted,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            SleepDial(startMinute, endMinute, onStart, onEnd)
            Spacer(Modifier.height(12.dp))
            Surface(
                onClick = { editing = !editing },
                // The row unfolds the two wheels, and without the standard actions a screen reader
                // would hear the times and nothing about whether the wheels were open.
                modifier = Modifier.fillMaxWidth().semantics {
                    if (editing) {
                        collapse {
                            editing = false
                            true
                        }
                    } else {
                        expand {
                            editing = true
                            true
                        }
                    }
                },
                shape = RoundedCornerShape(PwnCorners.large),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PwnGlyph(R.drawable.ic_art_sleep, SleepIcon, PwnIndigoSoft, null, Modifier.size(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.sleep_time_label),
                            color = PwnMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(
                                R.string.sleep_clock_summary,
                                clockText(startMinute),
                                clockText(endMinute),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Icon(Icons.Outlined.Edit, null, tint = PwnMuted, modifier = Modifier.size(20.dp))
                }
            }
            InlineReveal(editing) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ClockTimePickerCard(
                        label = stringResource(R.string.sleep_start),
                        minuteOfDay = startMinute,
                        onValue = onStart,
                        modifier = Modifier.weight(1f),
                    )
                    ClockTimePickerCard(
                        label = stringResource(R.string.sleep_end),
                        minuteOfDay = endMinute,
                        onValue = onEnd,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Pulse gets a slider rather than a ruler: the useful range is a hundred beats wide, and
 * a hundred units is the one span where a slider still resolves a single unit per few pixels.
 */
@Composable
private fun PulseSliderCard(bpm: Int, onBpm: (Int) -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "${stringResource(R.string.heart_rate)} (${stringResource(R.string.unit_bpm)})",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    bpm.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.unit_bpm),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            PulseTickSlider(bpm = bpm, onBpm = onBpm)
            PulseScaleLabels()
        }
    }
}

/** The beats printed under the pulse scale: every other tall tick, from the first one in range. */
private val PULSE_LABELS = (40..PULSE_MAX step 40).toList()

/** Where [bpm] falls along the pulse track, from 0 at [PULSE_MIN] to 1 at [PULSE_MAX]. */
private fun pulseFraction(bpm: Int): Float =
    (bpm.coerceIn(PULSE_MIN, PULSE_MAX) - PULSE_MIN) / (PULSE_MAX - PULSE_MIN).toFloat()

/**
 * The printed numbers under the pulse scale, each standing on the beat it names.
 *
 * Spread evenly under a track that runs 30-250, numbers for a 40-140 scale would put the "100"
 * where the thumb means about 162 beats. Each is placed at its
 * own share of the width, over a tall tick, and kept inside the card at the two ends. Placed
 * relative, so a right-to-left layout mirrors them together with the track above.
 */
@Composable
private fun PulseScaleLabels() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            PULSE_LABELS.forEach { Text(it.toString(), color = color, style = MaterialTheme.typography.labelSmall) }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { index, placeable ->
                val centre = pulseFraction(PULSE_LABELS[index]) * width
                val room = (width - placeable.width).coerceAtLeast(0)
                placeable.placeRelative((centre - placeable.width / 2f).roundToInt().coerceIn(0, room), 0)
            }
        }
    }
}

/**
 * A slider of the app's own rather than Material's: a thin track, a plain round thumb, and a tick scale
 * under it — a mark every five beats, taller every twenty, with every other tall one numbered.
 *
 * The whole control is a finger's height, the track and thumb centred in its upper part and the
 * ticks under them, so the band a touch lands in is the platform's minimum rather than the strip
 * the drawing happens to need. It runs the way the layout reads: in a right-to-left language the
 * pulse fills from the right, under numbers that count from the right, as Material's own sliders
 * elsewhere in the app do.
 */
@Composable
private fun PulseTickSlider(bpm: Int, onBpm: (Int) -> Unit) {
    val name = stringResource(R.string.heart_rate)
    val description = "$bpm ${stringResource(R.string.unit_bpm)}"
    val track = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The rulers' treatment, for the rulers' reason: a tap and a drag are the whole of this
    // control, and neither is a gesture a screen reader can perform — heard but unmovable, the
    // scale would be a readout. The beats are listed rather than rebuilt because a drag
    // recomposes this on every frame, which is what the weight and height ladders above do too.
    val choices = remember { (PULSE_MIN..PULSE_MAX).toList() }
    val increase = stringResource(R.string.ruler_increase)
    val decrease = stringResource(R.string.ruler_decrease)
    // A fixed height, not a minimum: a Canvas is a Spacer, which measures itself 0dp tall under a
    // minimum and is then centred in the 48dp it is stretched to — so the drawing would hang 24dp low
    // and the ticks would run through the beats printed under the control.
    Canvas(
        Modifier.fillMaxWidth()
            .height(PwnDimensions.minimumTarget)
            .semantics {
                // The name is what is being set and the state is where it stands, the split the
                // portion slider makes: "72 bpm" alone would be a value with nothing saying of what.
                contentDescription = name
                stateDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = bpm.coerceIn(PULSE_MIN, PULSE_MAX).toFloat(),
                    range = PULSE_MIN.toFloat()..PULSE_MAX.toFloat(),
                    steps = choices.size - 2,
                )
                setProgress { target ->
                    onBpm(target.roundToInt().coerceIn(PULSE_MIN, PULSE_MAX))
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(increase) { stepRuler(choices, bpm - PULSE_MIN, 1, onBpm) },
                    CustomAccessibilityAction(decrease) { stepRuler(choices, bpm - PULSE_MIN, -1, onBpm) },
                )
            }
            // With only a tap and a drag, a hardware keyboard, a D-pad or Switch Access could hear
            // the pulse and never reach it. The arrows step one beat, the
            // same step the accessibility actions take, and left and right follow the layout.
            .onPreviewKeyEvent { event ->
                val step = pulseKeyStep(event.key, rtl)
                if (event.type != KeyEventType.KeyDown || step == null) return@onPreviewKeyEvent false
                stepRuler(choices, bpm - PULSE_MIN, step, onBpm)
                true
            }
            .focusable(interactionSource = interaction)
            .pointerInput(rtl) {
                detectTapGestures { pos -> onBpm(pulseAt(pos.x, size.width, rtl)) }
            }
            .pointerInput(rtl) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onBpm(pulseAt(change.position.x, size.width, rtl))
                }
            },
    ) {
        val trackY = PULSE_TRACK_Y.toPx()
        val filled = pulseFraction(bpm) * size.width
        // Drawn left to right and mirrored whole in a right-to-left layout, the way the touch
        // maths below mirror, so the fill always grows from the side the numbers start at.
        scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f) {
            drawLine(track, Offset(0f, trackY), Offset(size.width, trackY), 4.dp.toPx(), StrokeCap.Round)
            drawLine(accent, Offset(0f, trackY), Offset(filled, trackY), 4.dp.toPx(), StrokeCap.Round)
            for (v in PULSE_MIN..PULSE_MAX step 5) {
                val x = pulseFraction(v) * size.width
                val tall = if (v % 20 == 0) 8.dp.toPx() else 5.dp.toPx()
                drawLine(
                    color = track,
                    start = Offset(x, trackY + 12.dp.toPx()),
                    end = Offset(x, trackY + 12.dp.toPx() + tall),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            // Keyboard focus needs a mark of its own; the thumb alone looks the same either way.
            if (focused) {
                drawCircle(accent, 14.dp.toPx(), Offset(filled, trackY), style = Stroke(2.dp.toPx()))
            }
            drawCircle(accent, 10.dp.toPx(), Offset(filled, trackY))
        }
    }
}

/**
 * How far down the pulse control its track runs. The thumb reaches 10dp above it and the tall ticks
 * end 20dp below it, so the whole drawing sits inside the control's 48dp minimum height.
 */
private val PULSE_TRACK_Y = 20.dp

/** Which way one arrow key moves the pulse, or null for a key that is not an arrow. */
private fun pulseKeyStep(key: Key, rtl: Boolean): Int? = when (key) {
    Key.DirectionUp -> 1
    Key.DirectionDown -> -1
    Key.DirectionRight -> if (rtl) -1 else 1
    Key.DirectionLeft -> if (rtl) 1 else -1
    else -> null
}

private fun pulseAt(x: Float, width: Int, rtl: Boolean): Int {
    val along = if (rtl) width - x else x
    return (PULSE_MIN + along / width * (PULSE_MAX - PULSE_MIN)).roundToInt().coerceIn(PULSE_MIN, PULSE_MAX)
}

@Composable
private fun MeasurementHistoryRow(entry: JournalEntry, mode: String, imperial: Boolean) {
    val time = dayMonthClock(Instant.ofEpochMilli(entry.occurredAt).atZone(ZoneId.systemDefault()), rememberDayMonth())
    // One stop for the reading and its time. No chevron: the row opens nothing, and an arrow on
    // it would promise a tap that does nothing and read a punctuation mark out as a stop of its own.
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PwnGlyph(
            measurementArt(mode),
            measurementIcon(mode),
            PwnIndigoSoft,
            null,
            Modifier.size(40.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(entry.measurementSummary(mode, imperial), fontWeight = FontWeight.SemiBold)
            Text(time, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun JournalEntry.measurementSummary(mode: String, imperial: Boolean): String = when {
    // The mode being browsed decides, not field presence: a row carrying both weight and height
    // must read as a height under the height tab, not as the weight that happens to be on it too.
    mode == "HEIGHT" && measurementHeightCm != null -> heightSummary(measurementHeightCm, imperial)

    // Stored in kilograms and centimetres; printed on the scale the picker above this list is
    // printed on, or the history contradicts the wheel it sits under.
    measurementWeightKg != null -> if (imperial) {
        "${imperialWeight(measurementWeightKg).shown()} ${stringResource(R.string.unit_lb)}"
    } else {
        stringResource(R.string.measurement_weight_value, measurementWeightKg.shown())
    }

    measurementHeightCm != null -> heightSummary(measurementHeightCm, imperial)

    measurementHeartRateBpm != null ->
        stringResource(R.string.measurement_heart_rate_value, measurementHeartRateBpm)

    sleepStartAt != null && sleepEndAt != null -> {
        val minutes = ((sleepEndAt - sleepStartAt).coerceAtLeast(0) / 60_000).toInt()
        stringResource(R.string.measurement_sleep_value, minutes / 60, minutes % 60)
    }

    // Whichever of the three the row actually carries, named: a waist alone is the common one, and
    // "80" with no word in front of it could be any of them. Resolved through a list rather than
    // inside the join, because a non-inline transform cannot hold a composable call.
    mode == "GIRTH" && girthCm().isNotEmpty() -> girthCm()
        .map { (vital, cm) ->
            val unit = stringResource(vital.displayUnitRes(imperial))
            val reading = readingText(vital, cm.toDouble(), null, imperial, LocalLocale.current.platformLocale)
            "${stringResource(vital.titleRes)} $reading $unit"
        }
        .joinToString(" · ")

    else -> title
}

@Composable
private fun heightSummary(heightCm: Int, imperial: Boolean): String =
    if (imperial) imperialHeight(heightCm) else stringResource(R.string.measurement_height_value, heightCm)

@Composable
private fun MeasurementFields(
    mode: String,
    weightKg: Double,
    heightCm: Int,
    heartRateBpm: Int,
    sleepStartMinute: Int,
    sleepEndMinute: Int,
    unitSystem: String,
    source: String,
    /** Changes the tab; none on a saved row, which draws no strip — see [measurementTabsOffered]. */
    onMode: ((String) -> Unit)?,
    onWeightKg: (Double) -> Unit,
    onHeightCm: (Int) -> Unit,
    onHeartRateBpm: (Int) -> Unit,
    onSensorHeartRateBpm: (Int) -> Unit,
    onSleepStartMinute: (Int) -> Unit,
    onSleepEndMinute: (Int) -> Unit,
    girthCm: Map<Vital, Int>,
    onGirthCm: (Vital, Int) -> Unit,
) {
    val metric = unitSystem == "METRIC"
    val locale = LocalLocale.current.platformLocale
    val weightValue = weightTick(weightKg, metric)
    val heightValue = heightTick(heightCm, metric)
    // Remembered, not rebuilt: these are 3 800 and 200 boxed Ints, and the rulers below recompose
    // on every frame of a drag. Only the unit system can change what is in them.
    val weightChoices = remember(metric) { weightTicks(metric).toList() }
    val heightChoices = remember(metric) { heightTicks(metric).toList() }
    if (onMode != null) {
        MeasurementTabs(mode, onMode)
        Spacer(Modifier.height(14.dp))
    }
    when (mode) {
        "WEIGHT" -> {
            val unit = stringResource(if (metric) R.string.unit_kg else R.string.unit_lb)
            RulerCard(
                label = "${stringResource(R.string.weight)} ($unit)",
                headline = String.format(locale, "%.1f", weightValue / 10.0),
                values = weightChoices,
                value = weightValue,
                tickLabel = { String.format(locale, "%.1f", it / 10.0) },
                onValue = { onWeightKg(weightOfTick(it, metric)) },
            )
        }

        "HEIGHT" -> {
            VerticalRulerCard(
                label = if (metric) {
                    "${stringResource(R.string.height)} (${stringResource(R.string.unit_cm)})"
                } else {
                    stringResource(R.string.height)
                },
                headline = if (metric) heightValue.toString() else "${heightValue / 12}′ ${heightValue % 12}″",
                unit = if (metric) stringResource(R.string.unit_cm) else "",
                values = heightChoices,
                value = heightValue,
                tickLabel = { if (metric) it.toString() else "${it / 12}′ ${it % 12}″" },
                onValue = { onHeightCm(heightOfTick(it, metric)) },
            )
        }

        "HEART_RATE" -> {
            PulseSliderCard(heartRateBpm, onHeartRateBpm)
            Spacer(Modifier.height(10.dp))
            PulseMeasureCard(onMeasured = onSensorHeartRateBpm)
        }

        "GIRTH" -> GIRTH_VITAL_ORDER.forEachIndexed { index, vital ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            GirthRuler(
                vital = vital,
                cm = girthCm[vital],
                metric = metric,
                onCm = { onGirthCm(vital, it) },
            )
        }

        "SLEEP" -> SleepCard(
            startMinute = sleepStartMinute,
            endMinute = sleepEndMinute,
            onStart = onSleepStartMinute,
            onEnd = onSleepEndMinute,
        )

        else -> {
            val weightUnit = stringResource(if (metric) R.string.unit_kg else R.string.unit_lb)
            val heightUnit = stringResource(R.string.unit_cm)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WheelPickerCard(
                    label = stringResource(R.string.weight),
                    value = weightValue,
                    values = weightChoices,
                    formatter = { "${String.format(locale, "%.1f", it / 10.0)} $weightUnit" },
                    onValue = { onWeightKg(weightOfTick(it, metric)) },
                    modifier = Modifier.weight(1f),
                )
                WheelPickerCard(
                    label = stringResource(R.string.height),
                    value = heightValue,
                    values = heightChoices,
                    formatter = {
                        if (metric) "$it $heightUnit" else "${it / 12}′ ${it % 12}″"
                    },
                    onValue = { onHeightCm(heightOfTick(it, metric)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    // Only a reading that came from somewhere else says where. "Source: Manual" under a form the
    // reader has just typed into is a line saying what typing into a form means — and the sentence
    // about the Sync tab under it belongs to the Sync tab.
    if (source.isNotBlank() && source != "MANUAL") {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.measurement_source_value, measurementSourceLabel(source)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The row's payload with the girths that were actually measured written into it.
 *
 * A wheel nobody touched writes nothing: it opens on a plain 80 or 95, and saving that as a
 * measurement would put a number in the record that no tape ever read. A girth the row already
 * carried stays as it was stored, because correcting the waist may not delete the hips measured
 * with it — nor round them: the wheel shows whole centimetres, and an imported 82.5 it was never
 * moved off is still 82.5 after a correction of the note.
 */
internal fun girthPayload(existing: String?, girths: Map<Vital, Int>, touched: Set<Vital>): String? {
    val json = existing?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    GIRTH_VITAL_ORDER.forEach { vital ->
        val cm = girths[vital] ?: return@forEach
        if (vital in touched) json.put(vital.key, cm)
    }
    if (!json.has("schema")) json.put("schema", 1)
    return json.toString().takeIf { GIRTH_VITAL_ORDER.any { vital -> json.has(vital.key) } }
        ?: existing
}

/**
 * One circumference on a ruler, in the unit the profile reads in.
 *
 * Stored in centimetres whatever the ruler showed, which is the rule every measurement in this app
 * follows: the unit system decides what is drawn, never what is written down. A girth nobody has
 * measured opens on a plain middle value and says, above the number, that it has not been measured
 * — so that the figure under the tape is never mistaken for one somebody took.
 */
@Composable
private fun GirthRuler(vital: Vital, cm: Int?, metric: Boolean, onCm: (Int) -> Unit) {
    val unit = stringResource(if (metric) R.string.unit_cm else R.string.unit_in)
    val shownCm = cm ?: GIRTH_DEFAULT_CM.getValue(vital)
    val value = girthTick(shownCm, metric)
    val choices = remember(metric, vital) { girthTicks(vital, metric).toList() }
    RulerCard(
        label = "${stringResource(vital.titleRes)} ($unit)",
        headline = if (cm == null) stringResource(R.string.measurement_girth_untouched) else value.toString(),
        values = choices,
        value = value,
        tickLabel = Int::toString,
        onValue = { picked -> onCm(girthOfTick(picked, vital, metric)) },
    )
}

/*
 * What the measurement rulers draw and what they hand back. A ruler draws the profile's unit and the
 * row stores kilograms and centimetres, so every tick is converted on the way out and back again on
 * the way in, and the trip has to land on the tick it left from: truncating both ways would make
 * 67 in convert back to the same 170 cm it came from, pinning the imperial ruler to one value
 * no matter the drag. Weight ticks are tenths of a kilogram or of a pound, height and girth ticks
 * whole centimetres or inches.
 */

internal fun weightTicks(metric: Boolean): IntRange = if (metric) 200..4_000 else 441..8_818

internal fun weightTick(kg: Double, metric: Boolean): Int =
    (kg * (if (metric) 1.0 else POUNDS_IN_A_KILOGRAM) * 10).roundToInt()

internal fun weightOfTick(tick: Int, metric: Boolean): Double =
    (tick / 10.0 / (if (metric) 1.0 else POUNDS_IN_A_KILOGRAM)).coerceIn(PLAUSIBLE_KG)

internal fun heightTicks(metric: Boolean): IntRange = if (metric) 50..250 else 20..98

internal fun heightTick(cm: Int, metric: Boolean): Int =
    if (metric) cm else (cm * INCHES_IN_A_CENTIMETRE).roundToInt()

internal fun heightOfTick(tick: Int, metric: Boolean): Int =
    (if (metric) tick else (tick / INCHES_IN_A_CENTIMETRE).roundToInt()).coerceIn(50, 250)

internal fun girthTicks(vital: Vital, metric: Boolean): IntRange {
    val range = girthRulerCm(vital)
    return girthTick(range.first, metric)..girthTick(range.last, metric)
}

internal fun girthTick(cm: Int, metric: Boolean): Int = if (metric) cm else imperialGirth(cm.toDouble()).roundToInt()

internal fun girthOfTick(tick: Int, vital: Vital, metric: Boolean): Int {
    val range = girthRulerCm(vital)
    return (if (metric) tick else (tick * CENTIMETRES_IN_AN_INCH).roundToInt()).coerceIn(range.first, range.last)
}

@Composable
internal fun measurementSourceLabel(source: String): String = stringResource(
    when (source) {
        "HEALTH_CONNECT" -> R.string.measurement_source_health_connect
        "BLUETOOTH_SCALE" -> R.string.measurement_source_scale
        "BLUETOOTH_HEART_RATE" -> R.string.direct_bluetooth_heart_rate
        "WATCH" -> R.string.measurement_source_watch
        "FITNESS_MACHINE" -> R.string.measurement_source_machine
        "PHONE_USAGE_ESTIMATE" -> R.string.measurement_source_phone_estimate
        "PHONE_SENSOR" -> R.string.measurement_source_phone_sensor
        else -> R.string.measurement_source_manual
    },
)

/**
 * A counted exercise's own form, the typed fields and the rep counter, rather than a session. A row
 * that already holds its sets — from a session, a template or Freestyle — opens on those sets
 * instead: its reps column is their sum, which the form's "per set" box would show as 10 a set for
 * sets of 6 and 4.
 */
private fun ActivitySpec.countedForm(workoutSetsJson: String): Boolean =
    counted && decodeSetsFromJson(workoutSetsJson).isEmpty()

/**
 * The exercises the button at the top of the workout form offers.
 *
 * The whole catalogue, except while a session is under way or its plan changed ([draftChanged]) on
 * a row that holds no sets yet. There an exercise the phone counts would put the rep counter's card
 * where the session stands — see [countedForm] — and the session is kept nowhere but in its own
 * page: three finished sets would go with a tap on "Squats", and nothing would ask. They are left out for
 * as long as that holds, as "Add another exercise" leaves them out. Once the row holds its sets the
 * session stays whatever is chosen, so a saved one can still be given any exercise's name.
 *
 * Never the pair's [partner], as the partner's list never holds the exercise being done: chosen
 * here, both halves would be counted on the first row and the second written again beside it.
 */
internal fun mainExerciseChoices(
    draftChanged: Boolean,
    workoutSetsJson: String,
    partner: ActivitySpec? = null,
): List<ActivitySpec> {
    val choices = if (draftChanged && decodeSetsFromJson(workoutSetsJson).isEmpty()) {
        PICKABLE_ACTIVITIES.filterNot { it.counted }
    } else {
        PICKABLE_ACTIVITIES
    }
    return choices.filterNot { it.code == partner?.code }
}

/**
 * The reps of each set a row holds: "2 × 5" when they match, "6 + 4" when they do not; null for a
 * row without sets. Its reps column is their sum, so "sets × reps" from the columns would read 6 and 4 as
 * "2 × 10".
 */
internal fun sessionRepsText(payload: String?): String? {
    val reps = decodeSetsFromJson(payload).map { it.reps.intNumber() }.filter { it > 0 }
    return when {
        reps.isEmpty() -> null
        reps.distinct().size == 1 -> "${reps.size} × ${reps.first()}"
        else -> reps.joinToString(" + ")
    }
}

/**
 * The numbers to beat. A logger that does not show them is a notebook.
 *
 * Never [excludeId], the row being corrected, as the records under it leave it out: correcting the
 * last workout would read "Last time" off that same workout.
 */
@Composable
private fun LastSession(activityCode: String, loadUnitRes: Int, excludeId: Long?) {
    val context = LocalContext.current
    val dao = remember { StepsDb.get(context).dao() }
    var last by remember(activityCode, excludeId) { mutableStateOf<JournalEntry?>(null) }
    LaunchedEffect(activityCode, excludeId) {
        last = dao.activitiesOf(listOf(activityCode)).lastOrNull { it.id != excludeId }
    }

    val entry = last ?: return
    val parts = listOfNotNull(
        entry.durationMinutes?.let { stringResource(R.string.entry_duration, it.shown()) },
        sessionRepsText(entry.measurementPayloadJson) ?: entry.sets?.let { sets -> entry.reps?.let { "$sets × $it" } },
        entry.reps?.takeIf { entry.sets == null }?.let {
            pluralStringResource(R.plurals.count_repetitions, it, it)
        },
        // Stored in kilograms; printed beside the wheel that typed it, on the wheel's own scale.
        entry.loadKg?.takeIf { it > 0 }?.let {
            val shown = if (loadUnitRes == R.string.unit_lb) imperialWeight(it) else it
            "${shown.shown()} ${stringResource(loadUnitRes)}"
        },
    )
    if (parts.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.editor_last_time, parts.joinToString(" · ")),
        color = PwnMuted,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** 40 minutes at 50 Hz. A set longer than that stops growing the buffer rather than the heap. */
private const val MAX_REP_SAMPLES = 120_000

/** 50 Hz. Fast enough to catch the peak of a landing, slow enough to stay unbatched and cheap. */
private const val REP_SENSOR_RATE_US = 20_000

/**
 * Counts repetitions from the phone's own sensors, for the exercises [REP_PROFILES] knows.
 *
 * It listens between Start and Stop and at no other time, and only for the exercise already chosen
 * above. Guessing the exercise from a signal that runs all day gets the exercise wrong and spends
 * battery doing it; being told which exercise it is turns the same signal into a count worth having.
 *
 * Stop writes the number into the field below, where it can be corrected — the sensor is evidence,
 * not the last word. A repetition it missed because the phone shifted in a pocket still happened.
 */
@Composable
private fun RepCounterCard(
    spec: ActivitySpec,
    /** The saved sessions, beside Start as on every other workout; no callback, no door. */
    onTemplates: (() -> Unit)?,
    onStop: (reps: Int, seconds: Int) -> Unit,
) {
    val context = LocalContext.current
    val profile = REP_PROFILES.getValue(spec.code)
    val sensors = remember { context.getSystemService(SensorManager::class.java) }
    val sensor = remember(spec.code) {
        sensors.getDefaultSensor(
            if (profile.signal == RepSignal.ROTATION) {
                Sensor.TYPE_GYROSCOPE
            } else {
                Sensor.TYPE_ACCELEROMETER
            },
        )
    }
    // Saved, as a session's clocks are: these exercises are done with the phone in a hand or a
    // pocket, where it turns, and a turn recreates the activity. Unsaved, the card would come back at
    // nought with Start showing, and Stop — the one thing that writes the count into the form — would
    // never be reached. The clock is elapsedRealtime, so a restored one simply keeps counting.
    var running by rememberSaveable(spec.code) { mutableStateOf(false) }
    var reps by rememberSaveable(spec.code) { mutableIntStateOf(0) }
    var seconds by rememberSaveable(spec.code) { mutableIntStateOf(0) }
    var startedAt by rememberSaveable(spec.code) { mutableLongStateOf(0L) }
    // The samples are far too many for a Bundle and stay behind, so what they had counted crosses
    // as a number and the recount starts again on top of it: nought for a set just started, the
    // restored total after a recreation.
    var carried by remember(spec.code) { mutableIntStateOf(reps) }
    var count by remember(spec.code) { mutableStateOf(RepCount(0, 0.0, 0.0)) }
    val samples = remember(spec.code) { mutableListOf<Accel>() }

    // A set runs with nobody touching the screen for longer than most timeouts, and a phone that
    // dozes off stops delivering the samples — then, behind its lock, the app with them. The pulse
    // card below keeps it awake for the same reason.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    // SensorManager delivers on the main looper unless it is handed a Handler, which is also the
    // thread the recount below snapshots on: one list, one thread, no lock.
    DisposableEffect(running, spec.code) {
        val listener = if (running && sensor != null) {
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (samples.size < MAX_REP_SAMPLES) {
                        samples += Accel(
                            event.timestamp,
                            event.values[0],
                            event.values[1],
                            event.values[2],
                        )
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }.also { sensors.registerListener(it, sensor, REP_SENSOR_RATE_US) }
        } else {
            null
        }
        onDispose { listener?.let(sensors::unregisterListener) }
    }

    // Recounting the whole set twice a second rather than counting incrementally: it is the same
    // function the trace analysis uses, so a threshold checked against a recording is the one
    // running here. Sorting and smoothing a 40-minute set is not free, so only the snapshot is
    // taken on the main thread — the count itself runs off it, or a long set janks the screen.
    LaunchedEffect(running, spec.code) {
        if (!running) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(500)
            seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1_000L).toInt()
            val snapshot = samples.toList()
            count = withContext(Dispatchers.Default) { RepCounter.count(snapshot, profile, handled = true) }
            reps = carried + count.reps
        }
    }

    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.rep_counter_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
            )
            // Where the phone goes for this exercise, as Freestyle's page says it.
            phonePlacementRes(spec.code)?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        reps.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            R.string.rep_counter_rate,
                            "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                            // In the decimal mark of the sentence it stands in: "0,85 Гц", not "0.85".
                            String.format(LocalLocale.current.platformLocale, "%.2f", count.cadenceHz),
                        ),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Only for a set that actually left the ground: push-ups have no flight time,
                    // and "0 cm" under every exercise is noise.
                    if (count.bestJumpM > 0.0) {
                        Text(
                            stringResource(
                                R.string.rep_counter_jump,
                                (count.bestJumpM * 100).roundToInt(),
                            ),
                            color = PwnMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                // On the left of Start, as on the session of an exercise the phone does not count:
                // the templates are the other way to begin, and these exercises draw this card
                // instead of that session. Not while counting, when there is nothing to begin.
                if (!running) {
                    onTemplates?.let {
                        TemplatesButton(it, ButtonDefaults.MinHeight)
                        Spacer(Modifier.width(8.dp))
                    }
                }
                when {
                    sensor == null -> Text(
                        stringResource(R.string.rep_counter_missing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    running -> TextButton(onClick = {
                        running = false
                        onStop(reps, seconds)
                    }) {
                        Text(stringResource(R.string.editor_rest_stop))
                    }

                    // Tonal, not filled: Save is the form's one filled action, and a second solid
                    // button beside it would make the two equally loud.
                    else -> FilledTonalButton(onClick = {
                        samples.clear()
                        count = RepCount(0, 0.0, 0.0)
                        carried = 0
                        reps = 0
                        seconds = 0
                        startedAt = SystemClock.elapsedRealtime()
                        running = true
                    }) {
                        Text(stringResource(R.string.rep_counter_start))
                    }
                }
            }
        }
    }
}

/**
 * The rate the chest recordings this was tuned against were captured at. The estimator reads the
 * real rate off the timestamps, so a phone that delivers faster loses nothing.
 */
private const val PULSE_SENSOR_RATE_US = 33_000

/** How often to ask whether there is enough recording yet, once past [HEARTBEAT_MEASURE_SECONDS]. */
private const val RECHECK_EVERY_SECONDS = 5

/**
 * Measures a pulse from the phone lying on the chest, so a heart rate can be logged without a strap.
 *
 * The eyes are on the chest, not the screen, for the whole measurement — which is why every
 * transition is a sound as well as a number: three short beeps count 3…2…1, one prompt starts the
 * listening, and a double acknowledgement means there is a result to look at.
 *
 * The number lands in the picker below, where it can be corrected. See [Ballistocardiogram] for
 * what is actually being measured, and why holding still matters more than holding a breath.
 */
@Composable
private fun PulseMeasureCard(onMeasured: (Int) -> Unit) {
    val context = LocalContext.current
    val sensors = remember { context.getSystemService(SensorManager::class.java) }
    val sensor = remember { sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val samples = remember { mutableListOf<Accel>() }
    var run by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var seconds by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Ballistocardiogram.Result?>(null) }

    val tones = rememberToneGenerator()

    DisposableEffect(running) {
        val listener = if (running && sensor != null) {
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    samples += Accel(event.timestamp, event.values[0], event.values[1], event.values[2])
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }.also { sensors.registerListener(it, sensor, PULSE_SENSOR_RATE_US) }
        } else {
            null
        }
        onDispose { listener?.let(sensors::unregisterListener) }
    }

    // One pass: count down, listen, answer. Cancelling the effect — leaving the screen, pressing
    // Cancel — stops the sensor with it, because `running` goes back to false either way.
    var pass by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        pass = coroutineContext.job
        result = null
        seconds = 0
        for (i in HEARTBEAT_COUNTDOWN_SECONDS downTo 1) {
            countdown = i
            tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            kotlinx.coroutines.delay(1_000)
        }
        // The prompt is deliberately outside the recording: a phone speaker is a vibration source,
        // and its own start cue must not become the loudest event in the first signal window.
        tones?.startTone(ToneGenerator.TONE_PROP_PROMPT, 200)
        kotlinx.coroutines.delay(350)
        countdown = 0
        samples.clear()
        running = true
        val startedAt = SystemClock.elapsedRealtime()
        // Listen for the minimum, then keep listening while the answer is still "not yet" — a faint
        // signal needs more of it, and a strong one should not be made to wait for the worst case.
        var elapsed = 0
        var nextCheck = HEARTBEAT_MEASURE_SECONDS
        var attempt: Ballistocardiogram.Result? = null
        while (attempt !is Ballistocardiogram.Result.Ok && elapsed < HEARTBEAT_MAX_MEASURE_SECONDS) {
            kotlinx.coroutines.delay(200)
            elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1_000L).toInt()
            seconds = elapsed
            if (elapsed >= nextCheck) {
                attempt = Ballistocardiogram.estimate(samples.toList())
                nextCheck = elapsed + RECHECK_EVERY_SECONDS
            }
        }

        running = false
        result = attempt as? Ballistocardiogram.Result.Ok ?: Ballistocardiogram.estimate(samples.toList())
        (result as? Ballistocardiogram.Result.Ok)?.let { onMeasured(it.estimate.bpm) }
        tones?.startTone(ToneGenerator.TONE_PROP_ACK, 500)
    }
    // The activity stopping — the screen locked, another app in front — ends a pass as Cancel does.
    // Behind the lock the sensor stops delivering and the tones the measurement is followed by may
    // not sound: the pass would go on to an answer from part of a recording, or from the hand that
    // pressed the power button, and put it in the picker unheard. Cancelled here and not left to
    // the key, because a stopped activity recomposes nothing until it comes back.
    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            pass?.cancel()
            run = 0
            running = false
            countdown = 0
        }
    }

    val busy = countdown > 0 || running
    // The phone is face-up on a chest for half a minute with nobody touching it, which is longer
    // than most screen timeouts — and a phone that dozes off stops delivering sensor events.
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    // The heart and the title head the card; underneath, the filled Measure button anchors the
    // start of the paragraph and the instructions wrap around it like a drop cap — beside it
    // first, then full-width below. Theme containers rather than the fixed pale indigo, so dark
    // mode gets its own deep card instead of a glowing light one.
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PwnCorners.extraLarge),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PwnGlyph(
                    R.drawable.ic_art_pulse,
                    Icons.Outlined.MonitorHeart,
                    MaterialTheme.colorScheme.surface,
                    null,
                    Modifier.size(40.dp),
                )
                Text(
                    stringResource(R.string.pulse_measure_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                // How to hold the phone is worth reading once, on the first measurement ever
                // taken. The shared dot names itself "What is this?" rather than repeating the
                // card's title, which a screen reader would hear as a second Measure button.
                HelpDot(title = stringResource(R.string.pulse_measure_title)) {
                    Text(stringResource(R.string.pulse_measure_how, HEARTBEAT_MEASURE_SECONDS))
                }
            }
            if (sensor == null) {
                Text(
                    stringResource(R.string.rep_counter_missing),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // What is happening, in the middle, with the one thing to do about it under it.
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val status = when {
                        countdown > 0 -> "$countdown · ${stringResource(R.string.pulse_measure_place)}"

                        running ->
                            stringResource(R.string.breathing_seconds_value, seconds) +
                                " · ${stringResource(R.string.pulse_measure_hold)}"

                        result != null -> pulseResultText(result)

                        else -> null
                    }
                    status?.let {
                        Text(
                            it,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (busy) {
                        FilledTonalButton(onClick = {
                            run = 0
                            running = false
                            countdown = 0
                        }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    } else {
                        // Filled and iconed: the one solid primary element on the card, so it
                        // reads as the button it is and not as another chip of text.
                        Button(onClick = { run++ }) {
                            Icon(Icons.Outlined.MonitorHeart, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.pulse_measure_start))
                        }
                    }
                }
                result?.let { measured ->
                    Text(
                        pulseAdviceText(measured),
                        color = if (pulseResultNeedsAttention(measured)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                // The one figure in the app the phone itself produces, from a movement sensor
                // rather than a pulse sensor. Saying so on the card is what keeps it a convenience
                // rather than a measurement somebody acts on.
                Text(
                    stringResource(R.string.pulse_measure_disclaimer),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Kept for the lifetime of the card and released with it: a leaked one holds an audio track open. */
@Composable
private fun rememberToneGenerator(): ToneGenerator? {
    val tones = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
    }
    DisposableEffect(tones) { onDispose { tones?.release() } }
    return tones
}

@Composable
private fun pulseResultText(result: Ballistocardiogram.Result?): String = when (result) {
    is Ballistocardiogram.Result.Ok ->
        "${result.estimate.bpm} ${stringResource(R.string.unit_bpm)}"

    null -> "—"

    else -> "?"
}

@Composable
private fun pulseAdviceText(result: Ballistocardiogram.Result?): String = when (result) {
    null -> stringResource(R.string.pulse_measure_ready)

    is Ballistocardiogram.Result.Ok -> stringResource(
        when {
            result.estimate.hasExcessiveInterference -> R.string.pulse_measure_interference
            result.estimate.spreadBpm > STEADY_SPREAD_BPM -> R.string.pulse_measure_unsteady
            else -> R.string.pulse_measure_done
        },
    )

    is Ballistocardiogram.Result.Poor -> stringResource(
        if (result.reason == Ballistocardiogram.Failure.MOVING) {
            R.string.pulse_measure_moving
        } else {
            R.string.pulse_measure_no_rhythm
        },
    )
}

private fun pulseResultNeedsAttention(result: Ballistocardiogram.Result?): Boolean = when (result) {
    is Ballistocardiogram.Result.Ok ->
        result.estimate.hasExcessiveInterference || result.estimate.spreadBpm > STEADY_SPREAD_BPM

    is Ballistocardiogram.Result.Poor -> true

    null -> false
}

/** Windows disagreeing by more than this are not reading one steady rhythm; say so rather than hide it. */
private const val STEADY_SPREAD_BPM = 20

/**
 * A stopwatch, counting up. Counting up rather than down on purpose: the useful question is "how
 * long have I held this" — or been sitting here between sets — and a countdown that hits zero starts
 * nagging. [onStop] receives the elapsed seconds, which is how a plank fills its own duration field.
 *
 * Elapsed realtime deltas rather than counted ticks: a `delay(1_000)` loop drifts a few percent and
 * loses whole seconds whenever the composition is not being driven, which a held plank notices.
 */
@Composable
private fun ExerciseTimer(titleRes: Int, startRes: Int) {
    // Saved, as the rep counter's clock is: a rest is timed with the phone in a hand, where it turns,
    // and unsaved the turn would put the clock back at nought, stopped. The start is elapsedRealtime,
    // which the recreation does not touch, so a restored clock goes on from where it is.
    var startedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var typed by rememberSaveable { mutableIntStateOf(0) }
    var running by remember { mutableIntStateOf(0) }
    val shown = if (startedAt == null) typed else running

    LaunchedEffect(startedAt) {
        val at = startedAt ?: return@LaunchedEffect
        val from = typed
        // Read before the first wait, so a clock brought back by a turn does not show nought first.
        while (true) {
            running = from + ((SystemClock.elapsedRealtime() - at) / 1_000L).toInt()
            delay(200)
        }
    }

    TimerRow(
        titleRes = titleRes,
        startRes = startRes,
        seconds = shown,
        editable = startedAt == null,
        onSeconds = { typed = it },
        onStart = { startedAt = SystemClock.elapsedRealtime() },
        onStop = {
            startedAt = null
            typed = 0
            running = 0
        },
    )
}

/** The clock itself: a title, three fields, and the one button that applies. */
@Composable
private fun TimerRow(
    titleRes: Int,
    startRes: Int,
    seconds: Int,
    editable: Boolean,
    onSeconds: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.editor_timer_mask),
                    style = MaterialTheme.typography.labelSmall,
                    color = PwnMuted,
                )
            }
            // Hours, minutes and seconds, each one a field. Before the clock starts they can be
            // typed into — a plank held before the phone was picked up is still a plank — and
            // while it runs they are read-only and tick.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimerPart(seconds / 3_600, MAX_TIMER_HOURS, editable, stringResource(R.string.a11y_hours)) {
                    onSeconds(it * 3_600 + seconds % 3_600)
                }
                TimerColon()
                TimerPart(
                    seconds % 3_600 / 60,
                    MAX_TIMER_MINUTE,
                    editable,
                    stringResource(R.string.a11y_minutes),
                ) {
                    onSeconds(seconds / 3_600 * 3_600 + it * 60 + seconds % 60)
                }
                TimerColon()
                TimerPart(seconds % 60, MAX_TIMER_MINUTE, editable, stringResource(R.string.a11y_seconds)) {
                    onSeconds(seconds / 60 * 60 + it)
                }
            }
        }
        if (editable) {
            // Tonal for the reason the rep counter's Start is: Save stays the one filled button.
            FilledTonalButton(onClick = onStart) { Text(stringResource(startRes)) }
        } else {
            TextButton(onClick = onStop) { Text(stringResource(R.string.editor_rest_stop)) }
        }
    }
}

/** Nobody planks for a day, and a field that would take one is a field that takes a typo. */
private const val MAX_TIMER_HOURS = 23
private const val MAX_TIMER_MINUTE = 59

/**
 * One two-digit field of the clock, named for the part of the time it holds: three fields reading
 * out "00, edit box" in a row would give no clue which of them is the minutes.
 */
@Composable
private fun TimerPart(value: Int, max: Int, enabled: Boolean, name: String, onValue: (Int) -> Unit) {
    val text = rememberSyncedText(value.toString().padStart(2, '0'), {
        onValue((it.filter(Char::isDigit).takeLast(2).toIntOrNull() ?: 0).coerceIn(0, max))
    })
    BasicTextField(
        state = text.state,
        inputTransformation = text.handOff,
        enabled = enabled,
        readOnly = !enabled,
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.headlineSmall.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        // A raw BasicTextField inherits none of Material's 48dp minimum, so on its own this measures
        // 42x32dp — 58% of the 48x48dp target. The height is put in the decoration box, which
        // sits inside the node that owns the tap gesture, so the box grows to 48dp and the digits
        // stay centred on the colons; a height on the field's own modifier would have grown the
        // box but left the digits at its top. The width becomes a 48dp floor the two digits can
        // grow past instead of a hard 42dp they would be clipped in at font scale ~1.9.
        modifier = Modifier.widthIn(min = 48.dp).width(IntrinsicSize.Min).semantics { contentDescription = name },
        decorator = TextFieldDecorator { field ->
            Box(Modifier.heightIn(min = 48.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                field()
            }
        },
    )
}

/** Drawn between the fields and never read out: a colon is not a stop worth making. */
@Composable
private fun TimerColon() {
    Text(
        ":",
        Modifier.clearAndSetSemantics {},
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = PwnMuted,
    )
}

/**
 * Everything the meal form holds, plus the arithmetic hanging off it.
 *
 * One object rather than eleven `var`s so that switching the basis — which has to re-express every
 * typed figure at once — is a single assignment and cannot leave half the form on one basis and
 * half on the other.
 *
 * The typed strings are always read *through* [basis]. Nothing is stored twice, so the two columns
 * the screen shows can never disagree.
 */
internal data class FoodState(
    val title: String = "",
    val subtitle: String = "",
    val serving: String = "",
    val protein: String = "",
    val fat: String = "",
    val carbs: String = "",
    /** Source-reported energy on [basis]; blank means a manual entry should use 4/9/4. */
    val reportedEnergy: String = "",
    val basis: MealBasis = MealBasis.PER_100G,
    val extra: Map<ExtraNutrient, String> = emptyMap(),
    /** The day's energy target, for the calorie row's bar. Zero hides the bar rather than dividing. */
    val dailyBudgetKcal: Double = 0.0,
) {
    val servingG: Double get() = serving.number()

    fun portionOf(typed: String): Double =
        if (basis.per100) portionFromPer100(typed.number(), servingG) else typed.number()

    fun per100Of(typed: String): Double =
        if (basis.per100) typed.number() else per100FromPortion(typed.number(), servingG)

    val portionProtein: Double get() = portionOf(protein)
    val portionFat: Double get() = portionOf(fat)
    val portionCarbs: Double get() = portionOf(carbs)

    /** Labels and references win; 4/9/4 remains the honest fallback for a hand-entered meal. */
    val portionCalories: Double
        get() = if (reportedEnergy.isEmpty()) {
            macroCalories(portionProtein, portionFat, portionCarbs)
        } else {
            portionOf(reportedEnergy)
        }
    val per100Calories: Double get() = per100FromPortion(portionCalories, servingG)

    val energyShareOfDay: Float
        get() = if (dailyBudgetKcal <= 0.0) {
            0f
        } else {
            (portionCalories / dailyBudgetKcal).toFloat().coerceIn(0f, 1f)
        }

    /** The portion figure for an optional nutrient, read through the same basis as the macros. */
    fun portionExtra(nutrient: ExtraNutrient): Double? =
        extra[nutrient]?.takeIf { it.isNotEmpty() }?.let { portionOf(it) }

    fun withExtra(nutrient: ExtraNutrient, typed: String): FoodState =
        copy(extra = extra + (nutrient to typed))

    /**
     * Switching the basis re-expresses what is on screen; it does not reinterpret it. Typing 14.1 g
     * of protein per 100 g and then tapping "for the portion" must leave the food unchanged and show
     * 31.0 — not silently decide the packet meant 14.1 g in the whole 220 g.
     */
    fun withBasis(next: MealBasis): FoodState {
        if (next == basis) return this
        // No portion weight yet means nothing to re-express through: portionOf and per100Of both
        // collapse to zero, which would wipe every typed figure. Keep the strings as they are.
        if (servingG <= 0.0) return copy(basis = next)
        fun reExpress(typed: String): String {
            if (typed.isEmpty()) return typed
            val value = if (next.per100) per100Of(typed) else portionOf(typed)
            return value.asGramText()
        }
        return copy(
            basis = next,
            protein = reExpress(protein),
            fat = reExpress(fat),
            carbs = reExpress(carbs),
            reportedEnergy = reExpress(reportedEnergy),
            extra = extra.mapValues { (_, typed) -> reExpress(typed) },
        )
    }
}

/**
 * The five optional nutrients the "additional information" sheet can add, in the order it lists them.
 *
 * Each names a drawing rather than a Material icon. The icon set has nothing for fibre and nothing
 * for saturated fat, and a borrowed blade of grass, biscuit or water drop would leave a reader taking
 * the label's word for what the picture beside it meant. The drawn set covers all five, so no row
 * needs a fallback.
 */
internal enum class ExtraNutrient(val labelRes: Int, val unitRes: Int, val art: Int) {
    FIBER(R.string.editor_fiber, R.string.unit_grams, R.drawable.ic_art_fiber),
    SUGAR(R.string.editor_sugar, R.string.unit_grams, R.drawable.ic_art_sugar),
    SATURATED_FAT(R.string.editor_saturated_fat, R.string.unit_grams, R.drawable.ic_art_saturated_fat),
    SODIUM(R.string.editor_sodium, R.string.unit_milligrams, R.drawable.ic_art_sodium),
    CHOLESTEROL(R.string.editor_cholesterol, R.string.unit_milligrams, R.drawable.ic_art_cholesterol),
}

@Composable
private fun FoodFields(
    state: FoodState,
    catalogId: String?,
    preserveServing: Boolean = false,
    onState: (FoodState) -> Unit,
    /** The row, portion and fat content just picked — or null once the name no longer is that row's. */
    onCatalogPick: (DishItem?) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val selectedFood = remember(catalogId) {
        catalogId?.let(TOP_FOODS_BY_ID::get)
    }
    val pickFood: (TopFood, CatalogPortion, Double?) -> Unit = { food, portion, fat ->
        onCatalogPick(DishItem(food, portion.grams, fat))
        onState(state.withCatalogFood(food, portion, locale, fat))
    }
    val pickJournalFood: (TopFood, Double?) -> Unit = { food, fat ->
        onCatalogPick(DishItem(food, state.servingG, fat))
        onState(state.withJournalCatalogFood(food, locale, fat))
    }
    var pickingJournalFood by rememberSaveable { mutableStateOf(false) }
    if (preserveServing) {
        TextButton(onClick = { pickingJournalFood = true }) { Text(stringResource(R.string.food_catalog_title)) }
        if (pickingJournalFood) {
            CatalogPickerDialog(
                state.title,
                onPick = { picked ->
                    pickJournalFood(picked, null)
                    pickingJournalFood = false
                },
                onDismiss = { pickingJournalFood = false },
            )
        }
    }

    MealHeader(
        title = state.title,
        subtitle = state.subtitle,
        // A name is not a nutrition figure: only the macro rows contradict a printed energy, and
        // the label reader leaves the name for the person to type after applying one.
        onTitle = {
            onCatalogPick(null)
            onState(state.copy(title = it.take(FOOD_NAME_MAX)))
        },
        onSubtitle = {
            onCatalogPick(null)
            onState(state.copy(subtitle = it.take(FOOD_SUBTITLE_MAX)))
        },
    )
    if (selectedFood == null && state.title.trim().length >= CATALOG_INLINE_QUERY_MIN) {
        val suggestions = rememberFoodSearch(state.title, limit = 3)
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            CatalogInlineSuggestions(suggestions) { food ->
                if (preserveServing) {
                    pickJournalFood(food, null)
                } else {
                    pickFood(food, food.defaultPortion, null)
                }
            }
        }
    }
    selectedFood?.let { food ->
        Spacer(Modifier.height(10.dp))
        val portion = food.portions.firstOrNull { it.grams.sameWeightAs(state.servingG) } ?: food.defaultPortion
        CatalogPortions(
            food = food,
            selectedGrams = state.servingG,
            selectedFat = state.per100Of(state.fat).takeIf { food.fatSteps.isNotEmpty() },
            onFat = { fat ->
                if (preserveServing) pickJournalFood(food, fat) else pickFood(food, portion, fat)
            },
        ) { picked -> pickFood(food, picked, null) }
    }
    Spacer(Modifier.height(14.dp))
    // What "+" means when the field is still empty. The catalogue's own serving where the food is
    // known, and otherwise the hundred grams every per-100 figure is quoted against.
    PortionWeightCard(
        serving = state.serving,
        start = selectedFood?.defaultPortion?.grams ?: PORTION_START_G,
    ) { onState(state.copy(serving = it.decimalInput())) }
    Spacer(Modifier.height(14.dp))
    MealBasisSelector(state.basis, state.servingG) { onState(state.withBasis(it)) }
    Spacer(Modifier.height(18.dp))
    NutritionCard(state, onState)
    Spacer(Modifier.height(14.dp))
    ExtraNutrientsCard(state, onState)
}

/**
 * The catalogue itself, as a dialog: opened from the card above, and again from the photo sheet
 * when a product the camera missed has to be added by hand.
 */
@Composable
private fun CatalogPickerDialog(initialQuery: String, onPick: (TopFood) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var groupName by rememberSaveable { mutableStateOf("") }
    val selectedGroup = groupName.takeIf(String::isNotEmpty)?.let(FoodCategory::valueOf)
    val results = rememberFoodSearchAnswer(query, selectedGroup, CATALOG_DIALOG_RESULT_LIMIT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.food_catalog_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(FOOD_NAME_MAX) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    label = { Text(stringResource(R.string.food_catalog_search)) },
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PwnFilterChip(
                        selected = selectedGroup == null,
                        onClick = { groupName = "" },
                        label = stringResource(R.string.food_group_all),
                    )
                    TOP_FOOD_GROUPS.forEach { group ->
                        PwnFilterChip(
                            selected = selectedGroup == group,
                            onClick = { groupName = group.name },
                            label = foodGroupLabel(group),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Null until the first answer: the dialog opens on a query, and an empty list there
                // would say "nothing found" for the beat the search took.
                if (results?.isEmpty() == true) {
                    Text(
                        stringResource(R.string.food_catalog_empty),
                        color = PwnMuted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else if (results != null) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(results, key = TopFood::id) { food ->
                            CatalogFoodRow(food) { onPick(food) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * What the model saw, before any of it is believed.
 *
 * Three things are adjustable here because three things are guesses: which dish it is (the model's
 * runners-up are one tap away), how big the plate was, and what is actually in it. Only the weights
 * are pure invention — the dish comes from the classifier and the nutrition from the catalogue —
 * which is why the portion chips and the per-row grams are the loudest controls on the sheet.
 */
@Composable
internal fun PhotoDishSheet(photo: Uri, onDismiss: () -> Unit, onApply: (String, String, List<DishItem>) -> Unit) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    // null while the model is still running; empty means it finished and recognised nothing usable.
    var guesses by remember(photo) { mutableStateOf<PhotoGuesses?>(null) }
    // What a rebuilt activity brings back, a turn of the phone being one: the reading, as the
    // labels and scores it is made of again ([restoredGuesses]), and the sheet as the person left
    // it. Without them the sheet would come back recognising the photograph a second time, and on the
    // model's first guess again — the dish chosen, the portion and every weight typed gone with the turn.
    var savedGuesses by rememberSaveable(photo) { mutableStateOf<List<String>?>(null) }
    var chosenAt by rememberSaveable(photo) { mutableIntStateOf(NO_GUESS) }
    var portionName by rememberSaveable(photo) { mutableStateOf(PhotoPortion.MEDIUM.name) }
    var rows by rememberSaveable(photo, stateSaver = PHOTO_ROWS_SAVER) { mutableStateOf(emptyList<PhotoRow>()) }
    var adding by rememberSaveable(photo) { mutableStateOf(false) }
    val steps = remember(photo) { mutableStateListOf<DishStep>() }

    LaunchedEffect(photo) {
        val kept = savedGuesses
        val found = withContext(Dispatchers.Default) {
            // A photograph that cannot be decoded, or a model that will not load, is a dialog that
            // says nothing was recognised — never a crash in the middle of logging lunch. The log
            // line is the only place a broken model asset is distinguishable from a blurry plate.
            kept?.let(::restoredGuesses) ?: runCatching {
                val scores = FoodRecognizer.scores(context, photo) { step -> steps += step }
                photoGuesses(scores) { step -> steps += step }
            }
                .onFailure { Log.w("MealPhoto", "photo recognition failed", it) }
                .getOrDefault(PhotoGuesses(emptyList(), emptyList()))
        }
        guesses = found
        // Only a first reading opens the sheet on its best guess: one brought back keeps the dish,
        // the portion and the weights that were on it.
        if (kept == null) {
            savedGuesses = found.saved()
            chosenAt = found.chosen?.let { found.all.indexOf(it) } ?: NO_GUESS
            rows = found.chosen?.items.orEmpty().rows()
        }
    }

    val found = guesses
    val dish = found?.all?.getOrNull(chosenAt)
    val portion = PhotoPortion.entries.firstOrNull { it.name == portionName } ?: PhotoPortion.MEDIUM
    val source = stringResource(R.string.photo_dish_source)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.photo_dish_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).panelVerticalScroll(rememberScrollState())) {
                if (found == null) {
                    DishRecognitionProgress(steps)
                } else if (dish == null) {
                    // Plain products are the models' blind spot — neither has a class for a fried
                    // egg or a boiled chicken breast. The catalogue does, and it is one tap away
                    // rather than behind closing this dialog first.
                    Text(stringResource(R.string.photo_dish_empty), color = PwnMuted)
                    Spacer(Modifier.height(8.dp))
                } else {
                    PhotoDishGuesses(found, dish) { guess ->
                        chosenAt = found.all.indexOf(guess)
                        portionName = PhotoPortion.MEDIUM.name
                        rows = guess.items.rows()
                    }
                    if (dish.confidence < LOW_CONFIDENCE) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.photo_dish_unsure),
                            style = MaterialTheme.typography.bodySmall,
                            color = PwnWarn,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    PhotoDishPortions(portion) { next ->
                        // Rescaled from what is on screen, not from the template, so a product
                        // added or a weight corrected by hand survives the change of portion.
                        rows = rows.items().scaledBy(next.factor / portion.factor).rows()
                        portionName = next.name
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (found != null) {
                    PhotoDishItems(
                        rows = rows,
                        locale = locale,
                        onGrams = { index, typed ->
                            rows = rows.mapIndexed { at, row ->
                                if (at == index) row.copy(grams = typed.decimalInput()) else row
                            }
                        },
                        onRemove = { index -> rows = rows.filterIndexed { at, _ -> at != index } },
                        onAdd = { adding = true },
                    )
                }
            }
        },
        confirmButton = {
            // Enabled on the rows, not on the recognition: a photograph the models made nothing of
            // still ends in a saved meal once the person adds a product by hand.
            TextButton(
                enabled = rows.items().any { it.grams > 0.0 },
                onClick = {
                    val title = dish?.name?.localized(locale)
                        ?: rows.first().food.name.localized(locale)
                    onApply(title, source, rows.items().filter { it.grams > 0.0 })
                },
            ) { Text(stringResource(R.string.photo_dish_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )

    if (adding) {
        CatalogPickerDialog(
            initialQuery = "",
            onPick = { food ->
                rows = rows + PhotoRow(food, food.defaultPortion.grams.asWeightText())
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

/** The plate recogniser thinking out loud, the same way the label reader does. */
@Composable
private fun DishRecognitionProgress(steps: List<DishStep>) {
    val locale = LocalLocale.current.platformLocale
    PhotoDishProgress(stringResource(R.string.photo_dish_working))
    steps.takeLast(PROGRESS_LINES).forEach { step ->
        Text(
            step.spoken(locale),
            style = MaterialTheme.typography.bodySmall,
            color = PwnMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DishStep.spoken(locale: Locale): String = when (this) {
    DishStep.Decoding -> stringResource(R.string.photo_dish_step_decode)

    is DishStep.Looking -> stringResource(
        if (dishes) R.string.photo_dish_step_dishes else R.string.photo_dish_step_products,
    )

    is DishStep.Guessed -> stringResource(
        R.string.photo_dish_step_guess,
        name.localized(locale),
        (confidence * PERCENT).roundToInt(),
        items,
    )

    DishStep.Empty -> stringResource(R.string.photo_dish_step_empty)
}

private const val PERCENT = 100

@Composable
private fun PhotoDishProgress(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(text, color = PwnMuted)
    }
}

/** Enough of the reader's train of thought to watch, not so much that it becomes a log file. */
internal const val PROGRESS_LINES = 4

/**
 * The guesses as two rows of chips, dishes and then plain foods, one of them selected: the photo model's best
 * answers of each kind. The dish stays one tap away when a vegetable in it was the surer answer, and the plain
 * food when a dish was. The runners-up are right often enough to offer.
 */
@Composable
private fun PhotoDishGuesses(guesses: PhotoGuesses, chosen: DishGuess, onChoose: (DishGuess) -> Unit) {
    if (guesses.dishes.isNotEmpty()) {
        PhotoGuessRow(stringResource(R.string.photo_dish_guess), guesses.dishes, chosen, onChoose)
    }
    if (guesses.dishes.isNotEmpty() && guesses.products.isNotEmpty()) Spacer(Modifier.height(10.dp))
    if (guesses.products.isNotEmpty()) {
        PhotoGuessRow(stringResource(R.string.photo_dish_guess_products), guesses.products, chosen, onChoose)
    }
}

@Composable
private fun PhotoGuessRow(label: String, row: List<DishGuess>, chosen: DishGuess, onChoose: (DishGuess) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    Text(label, style = MaterialTheme.typography.labelMedium, color = PwnMuted)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        row.forEach { guess ->
            PwnFilterChip(
                selected = guess == chosen,
                onClick = { onChoose(guess) },
                label = stringResource(
                    R.string.photo_dish_match,
                    guess.name.localized(locale),
                    (guess.confidence * 100).roundToInt(),
                ),
            )
        }
    }
}

@Composable
private fun PhotoDishPortions(portion: PhotoPortion, onPortion: (PhotoPortion) -> Unit) {
    Text(stringResource(R.string.photo_dish_portion), style = MaterialTheme.typography.labelMedium, color = PwnMuted)
    Spacer(Modifier.height(6.dp))
    // The three Russian labels need ~299dp of chip against the ~264dp a dialog leaves on a 360dp
    // screen, so the last one would be handed ~53dp for a 90dp word and cut mid-word. The scroll is
    // what the guess chips directly above already do with the same problem.
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoPortion.entries.forEach { option ->
            PwnFilterChip(
                selected = option == portion,
                onClick = { onPortion(option) },
                label = stringResource(option.labelRes),
            )
        }
    }
}

@Composable
private fun PhotoDishItems(
    rows: List<PhotoRow>,
    locale: Locale,
    onGrams: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    /** False when the list was typed rather than recognised: there is no photo to caveat. */
    fromPhoto: Boolean = true,
) {
    val items = rows.items()
    val heading = stringResource(R.string.photo_dish_items)
    val headingStyle = MaterialTheme.typography.labelMedium.copy(color = PwnMuted)
    // Where the rows came from and what to do about a wrong one, beside the list rather than under
    // its total; a list typed by hand has no photograph to caveat.
    if (fromPhoto) {
        HelpTitle(heading, style = headingStyle) { Text(stringResource(R.string.photo_dish_disclaimer)) }
    } else {
        Text(heading, style = headingStyle)
    }
    rows.forEachIndexed { index, row ->
        DishItemRow(
            row = row,
            locale = locale,
            onGrams = { typed -> onGrams(index, typed) },
            onRemove = { onRemove(index) },
        )
    }
    TextButton(onClick = onAdd) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.photo_dish_add))
    }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(
            R.string.photo_dish_total,
            items.sumOf(DishItem::grams).asWeightText(locale),
            items.photoCalories().asKcalText(),
        ),
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * One product of a dish: what it is on the first line, how much of it on the second.
 *
 * A person assembling a dish thinks in pieces for some things and grams for others — two eggs, but
 * a hundred and forty grams of rice — so the field always takes grams and the buttons appear only
 * beside a food that is genuinely counted: an egg, a slice, a burger, a packet. Buckwheat's
 * "portion" is a bare 150 g somebody typed into the catalogue, and a button stepping by that is a
 * worse offer than the keyboard. Where the buttons do appear, the unit is named beside them
 * ("1 шт. · 50 г") so the step is never a mystery.
 */
@Composable
private fun DishItemRow(row: PhotoRow, locale: Locale, onGrams: (String) -> Unit, onRemove: () -> Unit) {
    val portion = row.food.defaultPortion.takeIf { it.stepsInPieces }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.food.name.localized(locale),
                Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRemove, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.photo_dish_remove),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (portion != null) {
                IconButton(onClick = {
                    onGrams(row.grams.stepped(portion.grams, -1))
                }, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                    Icon(
                        Icons.Outlined.Remove,
                        contentDescription = stringResource(R.string.dish_build_step_down),
                    )
                }
            }
            // The state-based field, as the other weight fields are, because the separator drawn over
            // the stored dot is an output transformation. The rows stand by position, so the
            // field is keyed by its food: a removed row's cursor and undo do not pass to a row of
            // another food. Two rows of one food share no such key, and the row that moves up keeps
            // the removed one's.
            val grams = rememberSyncedText(row.grams, { onGrams(it.decimalInput()) }, row.food.id)
            // A floor rather than a width, and whatever the row has left beyond it: at a large font
            // size the digits and the unit beside them outgrow a fixed box.
            OutlinedTextField(
                state = grams.state,
                inputTransformation = grams.handOff,
                outputTransformation = rememberDecimalSeparator(),
                modifier = Modifier.widthIn(min = 110.dp).weight(1f, fill = false),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                suffix = { Text(stringResource(R.string.unit_grams)) },
            )
            if (portion != null) {
                IconButton(onClick = {
                    onGrams(row.grams.stepped(portion.grams, 1))
                }, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.dish_build_step_up),
                    )
                }
            }
        }
        // Under the stepper rather than beside it: squeezed in after the two buttons and the field,
        // the caption would have a few dozen dp of a dialog's width and be cut off without a mark.
        if (portion != null) {
            Text(
                stringResource(
                    R.string.dish_build_step,
                    portion.label.localized(locale),
                    portion.grams.asWeightText(locale),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
        }
    }
}

/**
 * One portion more or less of this food, never below nothing. Rounded to whole grams: a scale in
 * the kitchen does not read 133.3, and neither should a row built by tapping a button.
 */
internal fun String.stepped(portion: Double, direction: Int): String =
    (number() + portion * direction).coerceAtLeast(0.0).roundToInt().toString()

/**
 * A weight mid-edit is whatever was typed, including "1" on the way to "150" and "" on the way to
 * anything — the same string-first shape the rest of this form uses for every number a person can
 * touch.
 *
 * The fat content rides along untouched: nothing on this sheet offers it, but a row can arrive
 * carrying one, and a trip through here and back must not quietly turn 5% curd into whichever kind
 * the catalogue happens to list first.
 */
internal data class PhotoRow(val food: TopFood, val grams: String, val fatPercent: Double? = null)

/** [PhotoDishSheet]'s place for the guess it stands on while there is none: nothing was recognised. */
private const val NO_GUESS = -1

/** A row is three cells of a saved state: the catalogue id, the weight as typed, the fat content or nothing. */
private const val SAVED_ROW_CELLS = 3

/**
 * The sheet's rows across a rebuilt activity. A catalogue product is nothing a saved state carries,
 * so each row crosses as its id and comes back as that id's product ([TOP_FOODS_BY_ID]).
 */
private val PHOTO_ROWS_SAVER = listSaver<List<PhotoRow>, String>(
    save = { rows -> rows.savedRows() },
    restore = { saved -> restoredRows(saved) },
)

internal fun List<PhotoRow>.savedRows(): List<String> =
    flatMap { listOf(it.food.id, it.grams, it.fatPercent?.toString().orEmpty()) }

/** The rows back out of [savedRows]; an id this build's catalogue does not hold goes with its weight. */
internal fun restoredRows(saved: List<String>): List<PhotoRow> = saved.chunked(SAVED_ROW_CELLS)
    .filter { it.size == SAVED_ROW_CELLS }
    .mapNotNull { (id, grams, fat) -> TOP_FOODS_BY_ID[id]?.let { PhotoRow(it, grams, fat.toDoubleOrNull()) } }

private fun List<DishItem>.rows(): List<PhotoRow> =
    map { PhotoRow(it.food, it.grams.asWeightText(), it.fatPercent) }

private fun List<PhotoRow>.items(): List<DishItem> = map { DishItem(it.food, it.grams.number(), it.fatPercent) }

private enum class PhotoPortion(val factor: Double, val labelRes: Int) {
    SMALL(0.7, R.string.photo_dish_small),
    MEDIUM(1.0, R.string.photo_dish_medium),
    LARGE(1.4, R.string.photo_dish_large),
}

@Composable
internal fun CatalogInlineSuggestions(foods: List<TopFood>, onPick: (TopFood) -> Unit) {
    PwnCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.food_catalog_suggestions),
                style = MaterialTheme.typography.labelMedium,
                color = PwnMuted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            foods.forEach { food -> CatalogFoodRow(food) { onPick(food) } }
        }
    }
}

/**
 * A line of the catalogue: what the food is, and what a hundred grams of it costs.
 *
 * The picture is asked of the food, not of its shelf. Every entry has its category plate to
 * fall back on, and the couple of dozen foods people log every day are drawn one by one, since a
 * search for a banana that answers with the generic fruit bowl reads as a near miss even when the
 * row beneath it is exactly right.
 */
@Composable
internal fun CatalogFoodRow(food: TopFood, onClick: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(themePainterResource(food.art()), null, Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(food.name.localized(locale), fontWeight = FontWeight.SemiBold)
            Text(food.detail.localized(locale), style = MaterialTheme.typography.bodySmall, color = PwnMuted)
            FoodAllergenLine(remember(food.id) { FoodAllergens.of(food) })
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(R.string.editor_kcal_value, food.nutrition.kcal.asKcalText()),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.food_catalog_per_100),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
        }
    }
}

/**
 * What this kind of food usually contains, out of the fourteen a label has to declare — [typical],
 * as [FoodAllergens] reads it off one catalogue row or off every row a dish was made of.
 *
 * Two readings of the same line. For somebody who reported no allergens it is a quiet note naming
 * what is typically in this food. For somebody who did, the ones they react to are named first and
 * in the warning colour, because that is the whole reason they told the app.
 *
 * It never says a food is safe. An empty line means this matcher recognised nothing, which is not
 * the same as a packet that contains nothing, and the wording — "usually contains" — is chosen so
 * that no reading of it can be taken as a clearance.
 */
@Composable
private fun FoodAllergenLine(typical: Set<Allergen>) {
    if (typical.isEmpty()) return
    val reacted = typical.intersect(LocalAllergyAnswer.current.allergens())
    // Only what this person reported, where they reported anything: a warning that read "what you
    // react to: milk, gluten" at somebody who reported only milk would be naming their allergy and
    // somebody else's in one breath, and the next one it named would be ignored.
    // map, not joinToString: the transform of a non-inline call cannot hold a composable, and
    // the allergen's own name is one.
    val named = if (reacted.isEmpty()) typical else reacted
    val shown = named.map { stringResource(it.titleRes) }.joinToString()
    Text(
        stringResource(
            if (reacted.isEmpty()) R.string.food_allergens_typical else R.string.food_allergens_yours,
            shown,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (reacted.isEmpty()) PwnMuted else PwnWarn,
        fontWeight = if (reacted.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
    )
}

@Composable
internal fun CatalogPortions(
    food: TopFood,
    selectedGrams: Double,
    selectedFat: Double? = null,
    onFat: ((Double) -> Unit)? = null,
    onPick: (CatalogPortion) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    PwnCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp)) {
            // That these are reference averages, beside the portions they are averages of.
            HelpTitle(
                stringResource(R.string.food_catalog_portion),
                style = MaterialTheme.typography.labelMedium.copy(color = PwnMuted),
            ) { Text(stringResource(R.string.food_catalog_disclaimer)) }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                food.portions.forEach { portion ->
                    PwnFilterChip(
                        selected = selectedGrams.sameWeightAs(portion.grams),
                        onClick = { onPick(portion) },
                        label = stringResource(
                            R.string.food_catalog_portion_value,
                            portion.label.localized(locale),
                            portion.grams.asWeightText(locale),
                        ),
                    )
                }
            }
            // Curd is one product sold at five fat contents, not five rows. The chip picks the
            // percentage and the rest of the panel is recalculated from it.
            if (food.fatSteps.isNotEmpty() && onFat != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.food_catalog_fat),
                    style = MaterialTheme.typography.labelMedium,
                    color = PwnMuted,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val nearest = selectedFat?.let { fat -> food.fatSteps.minBy { abs(it - fat) } }
                    food.fatSteps.forEach { step ->
                        PwnFilterChip(
                            selected = step == nearest,
                            onClick = { onFat(step) },
                            label = stringResource(R.string.food_catalog_fat_value, step.asGramText(locale)),
                        )
                    }
                }
            }
        }
    }
}

internal fun FoodState.withJournalCatalogFood(food: TopFood, locale: Locale, fatPercent: Double? = null): FoodState =
    withCatalogFood(food, food.defaultPortion, locale, fatPercent).copy(serving = serving)

private fun FoodState.withCatalogFood(
    food: TopFood,
    portion: CatalogPortion,
    locale: Locale,
    fatPercent: Double? = null,
): FoodState {
    val nutrition = fatPercent?.let(food.nutrition::atFatPercent) ?: food.nutrition
    return copy(
        title = food.name.localized(locale),
        subtitle = food.detail.localized(locale),
        serving = portion.grams.asWeightText(),
        protein = nutrition.proteinG.asGramText(),
        fat = nutrition.fatG.asGramText(),
        carbs = nutrition.carbsG.asGramText(),
        reportedEnergy = nutrition.kcal.asGramText(),
        basis = MealBasis.PER_100G,
        extra = buildMap {
            nutrition.fiberG?.let { put(ExtraNutrient.FIBER, it.asGramText()) }
            nutrition.sugarG?.let { put(ExtraNutrient.SUGAR, it.asGramText()) }
            nutrition.saturatedFatG?.let { put(ExtraNutrient.SATURATED_FAT, it.asGramText()) }
            nutrition.sodiumMg?.let { put(ExtraNutrient.SODIUM, it.asGramText()) }
            nutrition.cholesterolMg?.let { put(ExtraNutrient.CHOLESTEROL, it.asGramText()) }
        },
    )
}

@Composable
internal fun foodGroupLabel(group: FoodCategory): String = stringResource(
    when (group) {
        FoodCategory.FAST_FOOD -> R.string.food_group_fast_food
        FoodCategory.MEAT -> R.string.food_group_meat
        FoodCategory.FISH -> R.string.food_group_fish
        FoodCategory.DAIRY -> R.string.food_group_dairy
        FoodCategory.GRAIN -> R.string.food_group_grain
        FoodCategory.VEGETABLE -> R.string.food_group_vegetable
        FoodCategory.FRUIT -> R.string.food_group_fruit
        FoodCategory.NUTS -> R.string.food_group_nuts
        FoodCategory.DISH -> R.string.food_group_dish
        FoodCategory.SWEET -> R.string.food_group_sweet
        FoodCategory.SNACK -> R.string.food_group_snack
        FoodCategory.DRINK -> R.string.food_group_drink
        FoodCategory.OTHER -> R.string.food_group_other
    },
)

/**
 * Name and qualifier, behind the group icon.
 *
 * Both are editable in place rather than in labelled boxes: they stand as the heading of the thing
 * being logged, and a name that reads as a heading until you touch it is what
 * a "what am I eating" field should look like.
 */
@Composable
private fun MealHeader(
    title: String,
    subtitle: String,
    onTitle: (String) -> Unit,
    onSubtitle: (String) -> Unit,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val category = remember(title) { foodCategory(title) }
            Image(themePainterResource(category.art()), contentDescription = null, modifier = Modifier.size(54.dp))
            Spacer(Modifier.width(14.dp))
            // Each field is named for what it holds, so a filled one is more than its text to a
            // screen reader, and each is given the 48dp floor a raw BasicTextField does not carry:
            // the lines alone were 28dp and 20dp tall. The floor goes in the decoration box, inside
            // the node that owns the tap. The name sits against the bottom of its box and the
            // detail against the top of its own, so the two lines still read as one heading
            // rather than drifting apart into the extra height.
            val nameLabel = stringResource(R.string.editor_food_name)
            val detailLabel = stringResource(R.string.editor_food_subtitle)
            val titleText = rememberSyncedText(title, onTitle)
            val subtitleText = rememberSyncedText(subtitle, onSubtitle)
            Column(Modifier.weight(1f)) {
                BasicTextField(
                    state = titleText.state,
                    inputTransformation = titleText.handOff,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = nameLabel },
                    decorator = TextFieldDecorator { field ->
                        Box(
                            Modifier.heightIn(min = PwnDimensions.minimumTarget),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            if (title.isEmpty()) {
                                // The field already says this name; the placeholder only shows it.
                                Text(
                                    nameLabel,
                                    Modifier.clearAndSetSemantics {},
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PwnMuted,
                                )
                            }
                            field()
                        }
                    },
                )
                BasicTextField(
                    state = subtitleText.state,
                    inputTransformation = subtitleText.handOff,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = PwnMuted),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = detailLabel },
                    decorator = TextFieldDecorator { field ->
                        Box(
                            Modifier.heightIn(min = PwnDimensions.minimumTarget),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            if (subtitle.isEmpty()) {
                                Text(
                                    detailLabel,
                                    Modifier.clearAndSetSemantics {},
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PwnMuted,
                                )
                            }
                            field()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PortionWeightCard(serving: String, start: Double, onServing: (String) -> Unit) {
    // Both words are read twice: once as the heading a sighted person sees, once as the name and
    // the unit the slider below has to say for itself.
    val weightLabel = stringResource(R.string.editor_portion_weight)
    val gramsWord = stringResource(R.string.editor_unit_grams_long)
    val locale = LocalLocale.current.platformLocale
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    weightLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                // Grams only, so this is a label rather than a menu: there is no second unit to
                // switch to, and a picker with one entry is a worse lie than plain text.
                Text(gramsWord, color = PwnMuted)
            }
            // A rule sets the heading off from the controls under it, which is what stops
            // "Portion weight — grams" reading as a label for the stepper's own number.
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = PwnBorder)
            Spacer(Modifier.height(12.dp))
            PortionStepper(serving, start, weightLabel, onServing)
            // The slider is the coarse gesture and the field the fine one, so it only ever moves
            // in whole steps and stops offering weights a kitchen scale would laugh at. A portion
            // heavier than the track still shows — it just sits pinned at the far end rather than
            // being quietly rounded down to fit.
            val grams = serving.number()
            // Material's own slider, thumb and all: the bar thumb is the platform's current slider,
            // and a round thumb on a thin track would mean drawing both by hand in Slider's
            // thumb/track slots. No tick dots: forty of them read as broken.
            //
            // Material gives the slider a role and a range but no name, and its fallback state is
            // a bare percentage — "32 percent" for a 320 g portion, with neither the word portion
            // nor the word gram anywhere on the node. The heading is six composables away and
            // outside these semantics, so both halves are said here, the way the pulse slider does.
            Slider(
                state = rememberSyncedSliderState(
                    grams.coerceIn(0.0, PORTION_SLIDER_MAX).toFloat(),
                    0f..PORTION_SLIDER_MAX.toFloat(),
                ),
                onValueChange = { onServing(it.toDouble().roundToInt().toString()) },
                // The default unfilled track is the secondary container, which all but vanishes on
                // this card; the outline role is the one the pulse slider's track is drawn in.
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = weightLabel
                    // Spoken in the language around it: "12,5 грамма", where the default reads a point.
                    stateDescription = "${grams.asWeightText(locale)} $gramsWord"
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0, 250, 500, 750, 1_000).forEach { tick ->
                    Text(
                        tick.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = PwnMuted,
                    )
                }
            }
        }
    }
}

/**
 * The weight's − / field / + well: the food form's own, and a meal card's in the chat. [label] names
 * the field to a screen reader, which hears no heading from inside it; [start] is where + starts an
 * empty field.
 */
@Composable
internal fun PortionStepper(serving: String, start: Double, label: String, onServing: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PwnCorners.large),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A weight is nudged far more often than it is retyped — one portion up or
            // down from whatever the catalogue suggested. The field stays, because some
            // weights are read off a scale and typing 137 beats tapping to it.
            PortionStep(
                icon = Icons.Outlined.Remove,
                description = stringResource(R.string.editor_portion_less),
                onClick = { onServing(serving.stepped(PORTION_STEP_G, -1)) },
            )
            val servingText = rememberSyncedText(serving, onServing)
            BasicTextField(
                state = servingText.state,
                inputTransformation = servingText.handOff,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                outputTransformation = rememberDecimalSeparator(),
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Named with the heading's words, which sit outside this node: filled, the
                // field would be "320, edit box" and nothing about a portion. The floor makes
                // the field itself the 48dp target; the row's padding lies outside it.
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
                decorator = TextFieldDecorator { field ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = PwnDimensions.minimumTarget),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (serving.isEmpty()) {
                            Text(
                                stringResource(R.string.editor_portion_hint),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = PwnMuted,
                            )
                        }
                        field()
                    }
                },
            )
            PortionStep(
                icon = Icons.Outlined.Add,
                description = stringResource(R.string.editor_portion_more),
                // From empty, one step lands on a portion rather than on ten grams.
                //
                // Stepping up from nothing as 0 + 10 would read as "this meal
                // weighs ten grams" — and for a meal repeated out of Recent, whose energy
                // is already filled in and whose weight the row never carried, that turns
                // the per-100 g column into the whole meal times ten. A 2 710 kcal dish
                // asked for a weight would answer 27 100 kcal per 100 g, which is arithmetic
                // nobody wants from a button whose job is to start a number off.
                onClick = {
                    onServing(
                        if (serving.isEmpty()) {
                            start.asWeightText()
                        } else {
                            serving.stepped(PORTION_STEP_G, 1)
                        },
                    )
                },
            )
        }
    }
}

/** One tap of the weight, in its own soft square well so the two of them read as a pair. */
@Composable
private fun PortionStep(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(PwnCorners.medium), color = MaterialTheme.colorScheme.surfaceVariant) {
        // 48dp, not 46dp: an explicit size on an IconButton replaces the minimum it would
        // otherwise carry, and 46dp is 2116dp² against the 2304dp² the platform asks for. The
        // 22dp glyph inside is unchanged; only the well around it grows.
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = description, tint = PwnIndigo, modifier = Modifier.size(22.dp))
        }
    }
}

/** Nudged by a rough mouthful rather than a gram: the field is there for anything exact. */
private const val PORTION_STEP_G = 10.0

/** Where a weight starts when nothing else suggests one: the unit the other basis is quoted in. */
private const val PORTION_START_G = 100.0
private const val PORTION_SLIDER_MAX = 1_000.0

/**
 * Which of the two columns of a nutrition table is on screen, as one switch rather than two cards.
 *
 * A segment rather than a pair of tiles the width of half the screen, each carrying a two-line
 * explanation and a tick: those take three times the height, for a choice with two answers, and
 * read as two things you could add rather than one thing you could flip. The two halves stand side
 * by side in one track, and the explanation of whichever half is lit is written once underneath,
 * where it is a caption instead of competing body text.
 */
@Composable
private fun MealBasisSelector(basis: MealBasis, servingG: Double, onBasis: (MealBasis) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val caption = if (basis.per100) {
        stringResource(R.string.editor_basis_per_100_body)
    } else if (servingG > 0.0) {
        stringResource(R.string.editor_basis_portion_body, servingG.asWeightText(locale))
    } else {
        stringResource(R.string.editor_basis_portion_body_empty)
    }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // The house segmented control rather than two outlined tiles of its own, which would
            // light the chosen half with a fill barely apart from the unlit one and a border that
            // changes only in hue, and at night make the chosen word the dimmer of the two. The control's
            // solid pill, its tab semantics and its words that wrap instead of being cut all come
            // with it. No tick: on two halves the fill is the whole answer.
            PwnSegmentedControl(
                options = listOf(
                    stringResource(R.string.editor_basis_per_100) to null,
                    stringResource(R.string.editor_basis_portion) to null,
                ),
                selected = if (basis.per100) 0 else 1,
                onSelected = { onBasis(if (it == 0) MealBasis.PER_100G else MealBasis.PER_PORTION) },
            )
            Spacer(Modifier.height(10.dp))
            Text(caption, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
        }
    }
}

@Composable
private fun NutritionCard(state: FoodState, onState: (FoodState) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.editor_macros),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(
            if (state.servingG > 0.0) {
                stringResource(R.string.editor_calculated_for, state.servingG.asWeightText(locale))
            } else {
                stringResource(R.string.editor_calculated_for_empty)
            },
            style = MaterialTheme.typography.bodySmall,
            color = PwnMuted,
        )
    }
    Spacer(Modifier.height(10.dp))
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            MacroRow(
                label = stringResource(R.string.editor_protein),
                state = state,
                typed = state.protein,
                onTyped = { onState(state.copy(protein = it.decimalInput(), reportedEnergy = "")) },
                share = macroShare(
                    state.portionProtein,
                    state.portionProtein,
                    state.portionFat,
                    state.portionCarbs,
                ),
                unit = stringResource(R.string.unit_grams),
            )
            MacroRow(
                label = stringResource(R.string.editor_fat),
                state = state,
                typed = state.fat,
                onTyped = { onState(state.copy(fat = it.decimalInput(), reportedEnergy = "")) },
                share = macroShare(
                    state.portionFat,
                    state.portionProtein,
                    state.portionFat,
                    state.portionCarbs,
                ),
                unit = stringResource(R.string.unit_grams),
            )
            MacroRow(
                label = stringResource(R.string.editor_carbs),
                state = state,
                typed = state.carbs,
                onTyped = { onState(state.copy(carbs = it.decimalInput(), reportedEnergy = "")) },
                share = macroShare(
                    state.portionCarbs,
                    state.portionProtein,
                    state.portionFat,
                    state.portionCarbs,
                ),
                unit = stringResource(R.string.unit_grams),
            )
            // Energy is never typed here: it is the source's own figure when a catalogue row, a label
            // or a repeated meal supplied one, and the Atwater 4/9/4 estimate otherwise, so this row
            // is read-only in both bases. Its bar is the share of the macro mass that carries energy —
            // always full when anything is entered — so it tracks the day's budget instead.
            EnergyRow(state)
        }
    }
}

/**
 * One nutrient, both ways round: the portion figure large on the left, the per-100 g figure in the
 * accent on the right. Whichever of the two the current basis
 * says is the *typed* one is an editable field; the other is computed and read-only.
 */
@Composable
private fun MacroRow(
    label: String,
    state: FoodState,
    typed: String,
    onTyped: (String) -> Unit,
    share: Float,
    unit: String,
) {
    val locale = LocalLocale.current.platformLocale
    val portionText = state.portionOf(typed).asGramText(locale)
    val per100Text = state.per100Of(typed).asGramText(locale)
    // The field's own name: which nutrient, and on which of the two bases it is typed. Filled, it
    // would otherwise read as a bare "14.1, edit box" with nothing to say protein from carbs.
    val basisWord = stringResource(
        if (state.basis.per100) R.string.editor_basis_per_100 else R.string.editor_basis_portion,
    )
    val fieldName = "$label, $basisWord"
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MacroValue(
                text = portionText,
                typed = typed,
                editable = !state.basis.per100,
                onTyped = onTyped,
                unit = unit,
                accent = false,
                description = fieldName,
                modifier = Modifier.weight(1f),
            )
            MacroValue(
                text = per100Text,
                typed = typed,
                editable = state.basis.per100,
                onTyped = onTyped,
                unit = unit,
                accent = true,
                description = fieldName,
                modifier = Modifier.wrapContentWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Silent: the grams printed above already say what the bar draws, and a bare percentage
        // after every nutrient would be three unnamed stops in a row.
        NutrientBar(progress = { share }, modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * The thin bar under a nutrient's figures.
 *
 * The track is the outline role, 3.61:1 against the card by day and 4.32:1 at night, where the
 * container role would all but vanish. The fill and that track are close to each
 * other (1.53:1 and 1.73:1), so a small gap is left between them to mark where the fill stops, as
 * Material's own indicator does.
 */
@Composable
private fun NutrientBar(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier.fillMaxWidth().height(4.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outline,
        strokeCap = StrokeCap.Round,
        gapSize = 4.dp,
        drawStopIndicator = {},
    )
}

/**
 * A nutrient's figure: the typed one as a bare field in the figure's own type, with its unit
 * beside it, and the computed one as plain text. The product label page types its figures here
 * too, so a packet is corrected in the same field a meal is.
 */
@Composable
internal fun MacroValue(
    text: String,
    typed: String,
    editable: Boolean,
    onTyped: (String) -> Unit,
    unit: String,
    accent: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    val style = if (accent) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.headlineSmall
    }
    val color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    if (!editable) {
        Text("$text $unit", style = style, fontWeight = FontWeight.SemiBold, color = color, modifier = modifier)
        return
    }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        val text = rememberSyncedText(typed, onTyped)
        BasicTextField(
            state = text.state,
            inputTransformation = text.handOff,
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = style.copy(color = color, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            outputTransformation = rememberDecimalSeparator(),
            // On the default per-100 g basis this is the field protein, fat and carbs are typed
            // into, and a raw BasicTextField gets none of Material's 48dp minimum: at titleMedium
            // it would measure ~32x24dp, a third of the 2304dp² the platform asks for. The height goes
            // into the decoration box, which is inside the node carrying the tap gesture; aligned
            // to the bottom, the digits keep the baseline they share with the unit beside them.
            modifier = Modifier.widthIn(min = 48.dp).width(IntrinsicSize.Min).semantics {
                contentDescription = description
            },
            decorator = TextFieldDecorator { field ->
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.BottomStart) {
                    if (typed.isEmpty()) {
                        Text(
                            "0",
                            Modifier.clearAndSetSemantics {},
                            style = style,
                            fontWeight = FontWeight.SemiBold,
                            color = PwnMuted,
                        )
                    }
                    field()
                }
            },
        )
        Spacer(Modifier.width(4.dp))
        Text(unit, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun EnergyRow(state: FoodState) {
    val shareName = stringResource(R.string.m3_editor_energy_share)
    val sharePercent = NumberFormat.getPercentInstance(LocalLocale.current.platformLocale)
        .format(state.energyShareOfDay)
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            stringResource(R.string.editor_calories),
            style = MaterialTheme.typography.bodySmall,
            color = PwnMuted,
        )
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.editor_kcal_value, state.portionCalories.asKcalText()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.editor_kcal_value, state.per100Calories.asKcalText()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PwnAccentInk,
            )
        }
        Spacer(Modifier.height(8.dp))
        // Unlike the macro bars this one says something printed nowhere else on the form — how
        // much of the day's energy target the portion is — so it is named rather than silenced.
        // With no target there is nothing to be a share of, and it says nothing at all.
        NutrientBar(
            progress = { state.energyShareOfDay },
            modifier = if (state.dailyBudgetKcal > 0.0) {
                Modifier.semantics {
                    contentDescription = shareName
                    stateDescription = sharePercent
                }
            } else {
                Modifier.clearAndSetSemantics {}
            },
        )
    }
}

/**
 * Fibre, sugar and the rest: the second half of a nutrition table, folded away until wanted.
 *
 * All five lines are here, always, rather than behind a "+" in a disc and a dialog offering the five
 * nutrients one at a time — three controls and a window to type a number the card can simply have
 * a line for. The header keeps one control: the chevron that folds them away.
 *
 * The values are typed straight into the row rather than into a boxed field: plain figures at the
 * right margin, which is also what they look like on a packet. A bordered input per line would turn
 * the card into a form and push the label and its number to opposite ends of the screen.
 */
@Composable
private fun ExtraNutrientsCard(state: FoodState, onState: (FoodState) -> Unit) {
    var open by rememberSaveable { mutableStateOf(true) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            // A bare clickable Row is announced as static text, and the standard expand/collapse
            // actions say the fold state without asking fifteen locales for two new strings.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { open = !open }
                    .semantics {
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
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.editor_extra_title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    // The clickable Row merges its children, so describing the chevron with the
                    // title would repeat it: "Additional information, Additional information". Every
                    // other collapsible header in the app leaves it null for the same reason.
                    contentDescription = null,
                    tint = PwnMuted,
                )
            }
            InlineReveal(open) {
                Column {
                    ExtraNutrient.entries.forEachIndexed { index, nutrient ->
                        if (index > 0) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = PwnBorder)
                        }
                        ExtraNutrientRow(nutrient, state.extra[nutrient].orEmpty()) {
                            onState(state.withExtra(nutrient, it.decimalInput()))
                        }
                    }
                }
            }
        }
    }
}

/** One line of the table: what it is, and how much of it — typed in place, at the right margin. */
@Composable
private fun ExtraNutrientRow(nutrient: ExtraNutrient, typed: String, onTyped: (String) -> Unit) {
    val label = stringResource(nutrient.labelRes)
    // No padding of its own: the row's height is the field's 48dp floor, so the height belongs to
    // the thing being tapped rather than to padding around it.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(themePainterResource(nutrient.art), contentDescription = null, modifier = Modifier.size(24.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        val text = rememberSyncedText(typed, onTyped)
        BasicTextField(
            state = text.state,
            inputTransformation = text.handOff,
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            outputTransformation = rememberDecimalSeparator(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            // Row padding sits outside its children, so without a floor this field's own bounds would be
            // 72x24dp — half the 48dp minimum on the axis that matters, for five rows that exist to be typed
            // into. The decoration box below centres its content, so the digits and the
            // dash stay where they are drawn.
            modifier = Modifier
                .width(EXTRA_VALUE_WIDTH)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = label },
            decorator = TextFieldDecorator { field ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    // A dash rather than a blank: an empty right margin on a line that is asking
                    // for a number looks like the line failed to load.
                    if (typed.isEmpty()) {
                        Text(
                            stringResource(R.string.editor_extra_blank),
                            style = MaterialTheme.typography.bodyLarge,
                            color = PwnMuted,
                        )
                    }
                    field()
                }
            },
        )
        Text(
            stringResource(nutrient.unitRes),
            style = MaterialTheme.typography.bodyMedium,
            color = PwnMuted,
        )
    }
}

/** Wide enough for a four-figure sodium reading without the unit beside it moving. */
private val EXTRA_VALUE_WIDTH = 72.dp

/**
 * Shows the locale's decimal separator over the '.' the field actually stores.
 *
 * [decimalInput] normalises a typed comma to a dot so the string can be parsed, which would leave the
 * screen printing "1.6" in the column you type and "3,5" in the column beside it — on a Russian
 * screen that reads as two different numbers. A one-for-one character swap, so the offsets are
 * unchanged and the cursor needs no mapping.
 */
private class DecimalSeparatorTransformation(private val separator: String) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        for (index in 0 until length) {
            if (charAt(index) == '.') replace(index, index + 1, separator)
        }
    }
}

/** Null where the locale's separator is the dot already: there is nothing to draw over. */
@Composable
internal fun rememberDecimalSeparator(): OutputTransformation? {
    val locale = LocalLocale.current.platformLocale
    return remember(locale) {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        if (separator == '.') null else DecimalSeparatorTransformation(separator.toString())
    }
}

/** The drawn set's plate for a category. All thirteen have one. */
internal fun FoodCategory.art(): Int = when (this) {
    FoodCategory.FAST_FOOD -> R.drawable.ic_art_fast_food
    FoodCategory.MEAT -> R.drawable.ic_art_meat
    FoodCategory.FISH -> R.drawable.ic_art_fish
    FoodCategory.DAIRY -> R.drawable.ic_art_dairy
    FoodCategory.GRAIN -> R.drawable.ic_art_grain
    FoodCategory.VEGETABLE -> R.drawable.ic_art_vegetable
    FoodCategory.FRUIT -> R.drawable.ic_art_fruit
    FoodCategory.NUTS -> R.drawable.ic_art_nuts
    FoodCategory.DISH -> R.drawable.ic_art_dish
    FoodCategory.SWEET -> R.drawable.ic_art_sweet
    FoodCategory.SNACK -> R.drawable.ic_art_snack
    FoodCategory.DRINK -> R.drawable.ic_art_drink
    FoodCategory.OTHER -> R.drawable.ic_art_other_food
}

/**
 * The catalogue is thirty-five exercises long, which a dropdown cannot show: it opens over the field,
 * scrolls badly and gives no room for a thumbnail. A dialog with a scrolling list does all three.
 */
@Composable
internal fun ActivityPicker(
    selected: ActivitySpec,
    onPick: (ActivitySpec) -> Unit,
    onDismiss: () -> Unit,
    catalogue: List<ActivitySpec> = PICKABLE_ACTIVITIES,
) {
    val context = LocalContext.current
    val dao = remember { StepsDb.get(context).dao() }
    val used by remember { dao.mostUsedActivities(ACTIVITY_MOST_USED) }
        .collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val resources = LocalResources.current
    // The names are resources, so the search has to read them here rather than filter on `code`.
    val locale = resources.configuration.locales[0]
    val named = remember(resources) {
        ACTIVITY_CATALOG.associateWith { resources.getString(it.titleRes).searchFolded(locale) }
    }
    val shown = remember(query, used, named, catalogue) {
        val typed = query.searchFolded(locale)
        val matching = if (typed.isEmpty()) {
            catalogue
        } else {
            catalogue.filter { named[it]?.contains(typed) == true }
        }
        // What gets done most comes first, in the order it is done — and only while nothing is
        // typed, because once there is a query the answer is what matches it.
        if (typed.isNotEmpty()) {
            matching
        } else {
            val top = used.mapNotNull { code -> matching.firstOrNull { it.code == code } }
            top + matching.filterNot { it in top }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_pick_activity)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.editor_search_activity)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    // The exercises' own words: the food catalogue's line offered to enter a product by hand.
                    Text(
                        stringResource(R.string.routines_pick_empty),
                        color = PwnMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn {
                        items(shown, key = ActivitySpec::code) { spec ->
                            ActivityRow(spec, spec.code == selected.code) { onPick(spec) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** How many of the person's own exercises are lifted to the top of the catalogue. */
private const val ACTIVITY_MOST_USED = 6

/**
 * The workout form: the exercise, and under it a session to run, the phone's own counter, or — for
 * a workout that is already over ([reported], see [workoutTyped]) — the figures to type.
 *
 * A ride taken from a training machine is typed too once it is saved, and it is not this form's
 * estimate: the row holds the console's energy, and the card under the fields would have printed
 * the MET figure over a row Save writes the console's into. There the figure stands in a box of
 * its own ([onMachineKcal]), typed over like the minutes, and the estimate comes back only when
 * the box is emptied — which is what Save then writes.
 */
@Composable
private fun ActivityFields(
    spec: ActivitySpec,
    reported: Boolean = false,
    sex: String,
    duration: String,
    sets: String,
    reps: String,
    load: String,
    calories: Double,
    machineKcal: Int? = null,
    onMachineKcal: ((Int?) -> Unit)? = null,
    loadUnitRes: Int,
    loadUnitFactor: Double,
    workoutSetsJson: String,
    /** What a chat's plan named, for a session not yet run to open on — see [chatPlan]. */
    plannedFromChat: List<PlannedSet>,
    /** A session is under way or its plan was changed — what [mainExerciseChoices] is asked with. */
    draftChanged: Boolean,
    partner: ActivitySpec?,
    onSpec: (ActivitySpec) -> Unit,
    onPartner: (ActivitySpec?) -> Unit,
    onDuration: (String) -> Unit,
    onSets: (String) -> Unit,
    onReps: (String) -> Unit,
    onLoad: (String) -> Unit,
    onWorkoutFinished: (List<PlannedSet>) -> Unit,
    onDraftChanged: (Boolean) -> Unit,
    allowAdditionalExercises: Boolean,
    onMachineResult: (MachineResult) -> Unit,
    onTemplates: (() -> Unit)?,
    /** The saved row this form edits, left out of its own records; null for a new entry. */
    entryId: Long? = null,
) {
    var picking by remember { mutableStateOf(false) }
    // The pair's second exercise is chosen from the same catalogue by the same picker; only the
    // list it is given differs, and only where the answer lands.
    var pairing by remember { mutableStateOf(false) }
    // The step-by-step is asked for beside the label rather than from inside the picker: the
    // question is about the exercise already chosen, and the picker is where a person goes to
    // change it.
    var howTo by rememberSaveable { mutableStateOf(false) }
    // Whether the equipment list is up. The section it opens renders under the name, in this form's
    // own scroll, so choosing a bike never takes the person off the exercise they were logging.
    var machineOpen by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.editor_activity_type),
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
        )
        // Beside the exercise's own name, because the machine is a property of this exercise: the
        // rower under you while you log rowing, not a device somebody set up once in a settings
        // screen.
        IconButton(onClick = {
            machineOpen = true
        }, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
            Icon(
                Icons.Outlined.Bluetooth,
                contentDescription = stringResource(R.string.machine_connect),
                modifier = Modifier.size(22.dp),
            )
        }
        TextButton(
            onClick = { howTo = !howTo },
            modifier = Modifier.semantics {
                if (howTo) {
                    collapse {
                        howTo = false
                        true
                    }
                } else {
                    expand {
                        howTo = true
                        true
                    }
                }
            },
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = PwnMuted,
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.workout_how_to))
        }
    }
    ExerciseNameButton(stringResource(spec.titleRes)) { picking = true }
    MachineSection(open = machineOpen, onOpen = { machineOpen = it }, onResult = onMachineResult)
    InlineReveal(howTo) {
        Column {
            Spacer(Modifier.height(10.dp))
            ActivityHowTo(spec)
        }
    }
    if (picking) {
        ActivityPicker(
            selected = spec,
            catalogue = mainExerciseChoices(draftChanged, workoutSetsJson, partner),
            onPick = {
                onSpec(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
    if (pairing) {
        ActivityPicker(
            selected = partner ?: spec,
            // A camera-counted exercise draws a rep counter instead of a session, so it can never
            // be half of a pair; neither can the exercise already being done.
            catalogue = PICKABLE_ACTIVITIES.filter { !it.counted && it.code != spec.code },
            onPick = {
                onPartner(it)
                pairing = false
            },
            onDismiss = { pairing = false },
        )
    }
    if (spec.muscles.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        MuscleMap(spec.muscles, sex)
    }
    LastSession(spec.code, loadUnitRes, excludeId = entryId)
    // The best this exercise has been done, under the last time: the number to aim at, not only the
    // one to match.
    ExerciseRecordLine(spec.code, imperial = loadUnitRes == R.string.unit_lb, excludeId = entryId)
    Spacer(Modifier.height(12.dp))
    if (reported) {
        NumberField(
            duration,
            onDuration,
            stringResource(R.string.editor_time),
            stringResource(R.string.unit_minutes),
            decimal = true,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                sets,
                onSets,
                stringResource(R.string.editor_sets),
                stringResource(R.string.editor_count_support),
                Modifier.weight(1f),
            )
            NumberField(
                reps,
                onReps,
                stringResource(R.string.editor_reps),
                stringResource(R.string.editor_reps_support),
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        NumberField(
            load,
            onLoad,
            stringResource(R.string.editor_working_weight),
            stringResource(loadUnitRes),
            decimal = true,
        )
    } else if (spec.countedForm(workoutSetsJson)) {
        // A camera exercise brings its own clock and, for a timed one, its own duration — the
        // field above it would only disagree with what the camera just counted.
        if (spec.input != ActivityInput.DURATION) {
            NumberField(
                duration,
                onDuration,
                stringResource(R.string.editor_time),
                stringResource(R.string.unit_minutes),
                decimal = true,
            )
            Spacer(Modifier.height(12.dp))
        }
        RepCounterCard(spec, onTemplates) { counted, seconds ->
            if (spec.input == ActivityInput.DURATION) {
                // Reps here are the whole session, so a second set adds on, as the time does.
                onReps((reps.intNumber() + counted).toString())
                onDuration((duration.number() + seconds / 60.0).timerMinutes())
            } else {
                onReps(counted.toString())
                if (sets.isBlank()) {
                    // What was just counted is one set. Leaving the field empty would block saving
                    // over a number the person never had reason to doubt.
                    onSets("1")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // A timed exercise times itself and adds to the field above, so a second plank set does
        // not overwrite the first. Sets get the rest timer, which changes no field.
        if (spec.input != ActivityInput.DURATION) {
            ExerciseTimer(R.string.editor_rest_timer, R.string.editor_rest_start)
        } else {
            NumberField(
                reps,
                onReps,
                stringResource(R.string.editor_repetitions),
                stringResource(R.string.editor_repetitions_support),
            )
        }
        if (spec.input != ActivityInput.DURATION) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    sets,
                    onSets,
                    stringResource(R.string.editor_sets),
                    stringResource(R.string.editor_count_support),
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    reps,
                    onReps,
                    stringResource(R.string.editor_reps),
                    stringResource(R.string.editor_reps_support),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (spec.input == ActivityInput.WEIGHTED_SETS) {
            Spacer(Modifier.height(12.dp))
            NumberField(
                load,
                onLoad,
                stringResource(R.string.editor_working_weight),
                stringResource(loadUnitRes),
                decimal = true,
            )
        }
    } else {
        // Every field below — duration, sets, reps, load — is the session's own result now, not
        // something typed ahead of it. It sits right here, under the muscle map above, rather
        // than behind a "Start workout" door of its own.
        WorkoutSessionInline(
            spec = spec,
            loadUnitRes = loadUnitRes,
            loadUnitFactor = loadUnitFactor,
            workoutSetsJson = workoutSetsJson,
            seed = plannedFromChat,
            partner = partner,
            onPartner = { pairing = true },
            onUnpair = { onPartner(null) },
            onFinished = onWorkoutFinished,
            onDraftChanged = onDraftChanged,
            allowAdditionalExercises = allowAdditionalExercises,
            onTemplates = onTemplates,
        )
        // No reckoning here: the session has not run, so there is nothing yet to reckon. The
        // summary carries it once there is.
    }
    if (spec.countedForm(workoutSetsJson) || reported) {
        if (onMachineKcal != null) {
            Spacer(Modifier.height(12.dp))
            MachineEnergyField(machineKcal, onMachineKcal)
        }
        // The estimate, where it is what Save will write: not beside a console's figure.
        if (machineKcal == null) {
            Spacer(Modifier.height(14.dp))
            CalculationCard(
                stringResource(R.string.entry_energy, calories),
                stringResource(R.string.editor_met_calculation, spec.met),
            )
        }
    }
}

/**
 * The exercise's name on the note button's own height, radius and weight of word. Nothing about
 * being the first control on the form makes it a different kind of button from the ones below it,
 * and what the chevron says here it says everywhere else in the app: this opens a list.
 *
 * Outlined, as the note button is: it chooses a value, and a filled one would make it as loud as Save.
 * The edge is the outline role rather than the note button's hairline, because this is the one
 * line saying what is being logged and its box has to be seen. A floor instead of a height, and a
 * second line for the name, so a long catalogue name or a large font size grows the button instead
 * of being cut.
 */
@Composable
private fun ExerciseNameButton(name: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = NOTE_BUTTON_HEIGHT),
        shape = RoundedCornerShape(NOTE_BUTTON_RADIUS),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            name,
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = PwnMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A console's energy for the session, typed over as the summary lets it be: empty is "none said". */
@Composable
private fun MachineEnergyField(kcal: Int?, onKcal: (Int?) -> Unit) {
    NumberField(
        kcal?.toString().orEmpty(),
        { typed -> onKcal(typed.digitsOnly().toIntOrNull()) },
        stringResource(R.string.direct_bluetooth_energy),
        stringResource(R.string.unit_kcal),
    )
}

/**
 * An imported session's form: a synced workout, a sensor's, one a backup brought under a code this
 * build's catalogue does not know.
 *
 * Drawn as the workout form, it would show the catalogue's first exercise — a plank's muscles and
 * a plank's session to start, over a morning run — and no Save, so its minutes and its note
 * could be corrected nowhere. What Save keeps of such a row is its own name, energy and sets
 * ([workoutRow]), so that is what is shown and nothing is asked that would not be written: the
 * minutes are the one figure to type. The energy is the row's, printed without the MET arithmetic
 * that did not produce it. The button still opens the catalogue, [fallback] marked in it only
 * because the list has to mark something: naming an exercise there is how the row becomes one, and
 * the ordinary form takes over from that moment.
 */
@Composable
private fun ImportedSessionFields(
    session: JournalEntry,
    fallback: ActivitySpec,
    duration: String,
    onDuration: (String) -> Unit,
    onSpec: (ActivitySpec) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Text(stringResource(R.string.editor_activity_type), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    ExerciseNameButton(session.title) { picking = true }
    Spacer(Modifier.height(12.dp))
    NumberField(
        duration,
        onDuration,
        stringResource(R.string.editor_time),
        stringResource(R.string.unit_minutes),
        decimal = true,
    )
    if (session.calories > 0) {
        Spacer(Modifier.height(14.dp))
        CalculationCard(stringResource(R.string.entry_energy, session.calories), explanation = null)
    }
    if (picking) {
        ActivityPicker(
            selected = fallback,
            onPick = {
                onSpec(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * How large the exercise's own drawing stands when no step-by-step has been drawn for it yet:
 * something to look at, not a row's thumbnail.
 */
private val ACTIVITY_HOW_TO_ART = 160.dp

/**
 * How far the step-by-step panel is held off the card's edge, and the radius its own corners are
 * rounded to. The panel is an opaque, pale-backed picture rather than a glyph, so left square it
 * would poke out of the card's [PwnCorners.extraLarge] corner; the inset and the radius add back up
 * to that corner, which is what makes the two curves read as concentric instead of merely both
 * round.
 */
private val ACTIVITY_HOW_TO_PANEL_INSET = 12.dp
private val ACTIVITY_HOW_TO_PANEL_RADIUS = PwnCorners.large

/**
 * How the exercise is done, answered in place under the picker.
 *
 * A panel and not a window, because a sub-view of a form belongs inside that form's own scroll: the
 * picture arrives where the question was asked, and scrolling back to the fields costs nothing.
 *
 * Where the wordless step-by-step has been drawn, it is what the card shows: the start pose, the end
 * pose, the arrow between them and the rings on the joints that must not travel say the movement
 * without a sentence, so it takes the card's whole width at its own proportions and no line is
 * printed under it promising something that has already arrived. An exercise without one shows the
 * catalogue's portrait of the exercise, its name, and the muted line saying the step-by-step is not
 * drawn yet.
 */
@Composable
internal fun ActivityHowTo(spec: ActivitySpec) {
    val panel = spec.howTo()
    // The portrait is text-shaped and wants the card's usual margins round it; the panel wants to be
    // as wide as the card will let it be, and is held off the edge by only enough to keep its own
    // corners inside the card's.
    val horizontalInset = if (panel == null) 20.dp else ACTIVITY_HOW_TO_PANEL_INSET
    val verticalInset = if (panel == null) 18.dp else ACTIVITY_HOW_TO_PANEL_INSET
    PwnCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = horizontalInset, vertical = verticalInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (panel == null) {
                Image(
                    themePainterResource(spec.art()),
                    contentDescription = null,
                    modifier = Modifier.size(ACTIVITY_HOW_TO_ART),
                )
            } else {
                // No height is imposed: the panel is about twice as wide as it is tall, and reading
                // the grip and the arrow is the entire point, so it is given the width and left to
                // work out its own height from that. Unlabelled on purpose — the exercise's name is
                // right underneath it and the button that opened the card already said "How to?",
                // so a screen reader that announced a description here would only say one of those
                // twice, and there is no drawn-per-exercise sentence to say instead.
                Image(
                    themePainterResource(panel),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(ACTIVITY_HOW_TO_PANEL_RADIUS)),
                    contentScale = ContentScale.FillWidth,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(spec.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (panel == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.workout_how_to_soon),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CalculationCard(value: String, explanation: String?) {
    PwnCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            // The arithmetic behind the number is worth reading once. Printed under it, it is two
            // lines of grey type on every exercise the form is ever opened for. Behind the shared
            // dot it is named "What is this?" rather than making the whole formula the
            // button's name and reading it again in the window. A figure this form did not work
            // out — an imported session's — has no arithmetic here to show, and no dot.
            explanation?.let { HelpDot(title = value) { Text(it) } }
        }
    }
}

/**
 * Minutes with two decimals. `pretty()`'s single decimal is six seconds of slack, which is a fifth
 * of a plank.
 */
private fun Double.timerMinutes(): String =
    "%.2f".format(Locale.US, this).trimEnd('0').trimEnd('.')

/** A name long enough for "Куриная грудка запечённая с овощами", short enough for one line. */
internal const val FOOD_NAME_MAX = 80
private const val FOOD_SUBTITLE_MAX = 40
private const val CATALOG_INLINE_QUERY_MIN = 2
private const val CATALOG_DIALOG_RESULT_LIMIT = 60

/** One name, overwritten per shot: the classifier reads it, and nothing else ever wants it back. */
internal const val MEAL_PHOTO_FILE = "meal-photo.jpg"

/**
 * Whether a workout row's payload is a machine's frame rather than a plan of sets.
 *
 * The two share one column — a session run on equipment has no planned sets, so they never collide
 * — and this is how a reopened row tells which of the two it is holding.
 */
internal fun String.isMachinePayload(): Boolean = contains("\"machineType\"")

internal fun String.number(): Double = toDoubleOrNull() ?: 0.0
internal fun String.intNumber(): Int = toIntOrNull() ?: 0
private fun Double.asInput(): String = if (this == 0.0) "" else pretty()

/**
 * A saved meal's figure as the box it reopens in shows it: to the two decimals [decimalInput] lets
 * anyone type, so a meal saved again untouched writes back what it was saved with. One decimal would
 * turn 12.34 g into 12.3 on a Save of nothing.
 */
internal fun Double.asTypedInput(): String =
    if (this == 0.0) "" else "%.2f".format(Locale.US, this).trimEnd('0').trimEnd('.')
