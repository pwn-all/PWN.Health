package com.pwnall.health

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** The window the practice statistics read. */
private const val BREATHING_SCREEN_DAYS = 6L

/** How far the ring's radius swells and dips around its mean: a half-wavy line. */
private const val WAVE_AMPLITUDE = 0.018f

/** Crests on the wavy ring. An odd count keeps the waviness from looking stamped. */
private const val WAVE_CRESTS = 7

/** Scalloped translucent layers stacked into a watercolour cloud around the disc. */
private const val CLOUD_LAYERS = 7

/**
 * How coarsely the ring and the cloud are sampled into line segments. Every frame of a fifteen
 * minute session rebuilds them, and at these radii three degrees is a chord of a couple of pixels —
 * the curve reads the same and the draw costs a third of what a degree at a time would.
 */
private const val PATH_STEP_DEG = 3

/**
 * The bud's palette, a daylight set and a dark one. The glow is the point of the screen, but the
 * discs follow the theme — pale ones with dark copy in daylight, deep ones with light copy after
 * dusk — since after dusk a pale pause disc would be a near-white slab on a dark page.
 *
 * The inhale disc is the one place the copy turns white, and it has a blue of its own. On the
 * shared illustration accent white measures 2.93:1, under the 4.5:1 the 16sp phase word and the
 * 14sp unit need; on [BudInhale] it is 5.31:1, in both themes. The ring, its arc, its dot and the
 * phase pager draw in [BudColors.accent], which clears 3:1 on the page in each theme: the dot is
 * 5.21:1 in daylight and 6.16:1 after dusk. The cloud stays in the illustration colour — it is a
 * glow and nothing is read off it.
 */
private class BudColors(
    /** The ring, its arc, the travelling dot and the phase pager. */
    val accent: Color,
    /** The opacity the arc starts at where the lap began: the least at which it still clears 3:1. */
    val tailFloor: Float,
    /** The countdown and the two holds. */
    val hold: Color,
    val exhale: Color,
    /** The copy on the hold and the exhale discs. */
    val ink: Color,
    /** What a disc's belly and rim lighten towards. */
    val glow: Color,
)

private val BudInhale = Color(0xFF3F66C9)

/** Dark copy on pale discs: 11.83:1 on the hold disc and 8.28:1 on the exhale one, before the glow. */
private val LightBud = BudColors(
    accent = BudInhale,
    tailFloor = 0.71f,
    hold = Color(0xFFE1EAFC),
    exhale = Color(0xFFA9C6FB),
    ink = Color(0xFF1B2A47),
    glow = Color.White,
)

/**
 * Light copy on deep discs. The belly lightens towards a muted blue rather than white, which would
 * wash the copy out: the hold disc gives 10.37:1 flat and 8.21:1 at its lightest, the exhale disc
 * 7.29:1 and 6.97:1.
 */
private val DarkBud = BudColors(
    accent = PwnIndigoRaw,
    tailFloor = 0.6f,
    hold = Color(0xFF283B59),
    exhale = Color(0xFF31508A),
    ink = Color(0xFFF4F5FA),
    glow = Color(0xFF47628B),
)

/** The set for the theme showing, told apart by the page's lightness as [darkOr] tells it. */
@Composable
private fun budColors(): BudColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkBud else LightBud

/**
 * The bud is capped by the page's height as well as its width. A square as wide as a phone held
 * sideways is taller than the screen, and would push the timer and the stop button out of reach of a
 * practice done with the eyes shut. Held upright, most phones' pages are tall enough that the cap
 * never bites.
 */
private const val BUD_PAGE_SHARE = 0.55f
private val BUD_MAX_SIZE = 400.dp

private const val BREATH_PREFS = "breathing"

/**
 * The breathing practice: headline, a practice/statistics
 * toggle, the wavy ring with its bud, then the three settings and the start button.
 */
@Composable
fun BreathingScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val dao = remember { db.dao() }
    val scope = rememberCoroutineScope()
    val doneText = stringResource(R.string.breathing_done)
    val savedText = stringResource(R.string.breathing_saved)
    val notSavedText = stringResource(R.string.quick_log_not_saved)
    val today = rememberToday()
    val entries by remember(today) {
        dao.journalBetween(today.minusDays(BREATHING_SCREEN_DAYS).toString(), today.toString())
    }.collectAsStateWithLifecycle(emptyList())
    val stats = remember(entries) { breathingStats(entries) }
    val prefs = remember { context.getSharedPreferences(BREATH_PREFS, Context.MODE_PRIVATE) }
    var durationMin by remember {
        mutableIntStateOf(
            breathSetting(prefs.getInt("duration_min", 0), BREATH_DURATION_CHOICES, BREATH_DURATION_DEFAULT),
        )
    }
    var tempoSec by remember {
        mutableIntStateOf(breathSetting(prefs.getInt("tempo_sec", 0), BREATH_TEMPO_CHOICES, BREATH_TEMPO_DEFAULT))
    }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Saveable: a rotation or a theme change mid-session must not drop back to the settings page and
    // throw the practice away without recording a second of it.
    var practising by rememberSaveable { mutableStateOf(false) }
    var status by rememberStatusLine()
    // A session that could not be written says so, on the card the quick-log tiles use.
    var failure by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.breathing_title), onBack)
        // The confirmation goes over the page rather than inside the scroll. A practice runs for
        // up to fifteen minutes with the eyes shut, and the word that says the session was recorded
        // belongs where the eyes open — the middle of the screen — not on a line at the foot of the
        // settings. The scroll takes the height the top bar leaves; the flash sits on top of it.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Read here, outside the scroll, where the height is the page's rather than unbounded.
            val budMax = minOf(maxHeight * BUD_PAGE_SHARE, BUD_MAX_SIZE)
            Column(
                Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                if (practising) {
                    // The session gets the whole screen: the pitch and the tabs would only compete with
                    // the one instruction that matters right now.
                    BreathingRunner(durationMin, tempoSec, sound, budMax) { cycles, seconds ->
                        practising = false
                        // Ended during the countdown or before one whole cycle, nothing was practised;
                        // saving it would grow the statistics by a session that never began.
                        val outcome = breathOutcome(cycles, seconds, durationMin * 60)
                        if (outcome != BreathOutcome.DISCARDED) {
                            // Undispatched and non-cancellable: a session settled by leaving the screen
                            // arrives here while this composition — scope included — is being torn down,
                            // and a dispatched launch would be cancelled before it ever ran.
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                withContext(NonCancellable) {
                                    // A write that throws is said on the failure card. Uncaught, it would end
                                    // the process — and from here that is as the screen is being left.
                                    runCatching {
                                        // The day is read when the session ends, not when the screen opened:
                                        // a practice that ran through midnight belongs to the day it
                                        // finished on.
                                        db.saveJournalEvent(breathingEntry(resources, cycles, seconds, LocalDate.now()))
                                    }.onSuccess {
                                        // "Complete" for the session that ran its length, and no more than
                                        // "saved" for one ended part of the way through.
                                        status = if (outcome == BreathOutcome.COMPLETE) doneText else savedText
                                    }.onFailure {
                                        if (it is CancellationException) throw it
                                        failure = notSavedText
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.breathing_headline),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.breathing_subtitle),
                        color = PwnMuted,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PwnSegmentedControl(
                        options = listOf(
                            stringResource(R.string.breathing_tab_practice) to null,
                            stringResource(R.string.breathing_tab_stats) to null,
                        ),
                        selected = tab,
                        onSelected = { tab = it },
                    )
                    if (tab == 1) {
                        BreathingStatsCard(stats)
                    } else {
                        // A picture of the practice, not an instruction: read aloud, it would tell somebody who
                        // has not started to inhale, and the pace row under it says the same.
                        BreathingBud(
                            title = stringResource(R.string.breathing_inhale),
                            seconds = tempoSec,
                            breath = { 0.35f },
                            progress = { 0f },
                            maxSize = budMax,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                        BreathingSettingsCard(
                            durationMin = durationMin,
                            tempoSec = tempoSec,
                            sound = sound,
                            onDuration = {
                                durationMin = it
                                prefs.edit { putInt("duration_min", it) }
                            },
                            onTempo = {
                                tempoSec = it
                                prefs.edit { putInt("tempo_sec", it) }
                            },
                            onSound = {
                                sound = it
                                prefs.edit { putBoolean("sound", it) }
                            },
                        )
                        // The page's one door, drawn as the app draws its doors: a Medium button that
                        // grows with its words at a large font size and squares its corners when pressed.
                        PwnWideButton(
                            icon = Icons.Filled.PlayArrow,
                            label = stringResource(R.string.breathing_start),
                            onClick = {
                                status = null
                                failure = null
                                practising = true
                            },
                        )
                        BreathingInfoLink()
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            PwnActionFlash(status)
            PwnFailureFlash(failure) { failure = null }
        }
    }
}

/**
 * Whether a signal from the screen's lifecycle ends the practice at what was breathed: the activity
 * stopping, or — [event] null — the runner leaving the composition, which is all that navigating
 * away inside the app gives. Neither while the activity is only being rebuilt for a rotation or a
 * theme change ([changingConfigurations]): the saveable start resumes that session in place, and
 * settling it there would save half a practice and throw the person back to the settings page.
 */
internal fun breathSettlesOn(event: Lifecycle.Event?, changingConfigurations: Boolean): Boolean =
    !changingConfigurations && (event == null || event == Lifecycle.Event.ON_STOP)

/**
 * Runs the practice: a three-second countdown on a pale disc,
 * then the pattern — the disc filling indigo through the inhale, paling for the holds and turning
 * blue for the exhale, while the dot laps the wavy ring once per cycle with its arc trailing it.
 */
@Composable
private fun BreathingRunner(
    durationMin: Int,
    tempoSec: Int,
    sound: Boolean,
    budMax: Dp,
    onFinished: (cycles: Int, seconds: Int) -> Unit,
) {
    // The session is measured against the clock rather than counted in ticks: a screen that goes
    // dark pauses recomposition while the loop below keeps running, and a counter would then bank
    // minutes nobody breathed. Saveable so a rotation resumes the same session in place.
    val startedAt = rememberSaveable { SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val total = durationMin * 60
    val (countdown, elapsed) = breathProgressAt(now - startedAt, total)
    val cycleSeconds = breathCycleSeconds(tempoSec)
    val totalCycles = (total / cycleSeconds).coerceAtLeast(1)
    val tick = breathTickAt(elapsed, tempoSec)
    val preparing = countdown > 0
    val done = !preparing && elapsed >= total
    val cues = rememberBreathCues(sound)

    // Latched: the finish tone's grace delay and a second tap on the cancel button must not be
    // able to report the same session twice.
    var finished by remember { mutableStateOf(false) }
    fun finish() {
        if (!finished) {
            finished = true
            onFinished(tick.completedCycles, elapsed)
        }
    }

    // The phone is on a table with its owner's eyes closed for up to a quarter of an hour, which is
    // longer than any screen timeout, and a dark screen stops the phase cues with it.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // The practice is a foreground exercise: once the screen is gone the phase cues are gone with
    // it, so leaving settles the session at what was actually breathed instead of banking silent
    // minutes in the background or losing the lot if the process is dropped while stopped. A
    // configuration change is not leaving — the saveable start above resumes the session in place.
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settle by rememberUpdatedState(::finish)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (breathSettlesOn(event, activity?.isChangingConfigurations == true)) settle()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // Navigating away inside the app never stops the activity, so disposal is the only
            // signal the screen gets; the finished latch keeps a double settle harmless.
            if (breathSettlesOn(null, activity?.isChangingConfigurations == true)) settle()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        // Woken on the session's own second boundaries, so the counter turns over with the tones
        // rather than drifting a fraction of a second past them. Stops with the session rather
        // than at the next recomposition, which a stopped activity does not get around to.
        while (!finished) {
            delay(1_000L - (SystemClock.elapsedRealtime() - startedAt) % 1_000L)
            now = SystemClock.elapsedRealtime()
        }
    }
    LaunchedEffect(done) {
        if (done) {
            // The chime gets its 400 ms before the runner unmounts and releases the generator —
            // for an eyes-closed user it is the only signal that the session is over. The motor
            // needs no such grace, and gives the end its longer tap.
            val given = cues.cue(ToneGenerator.TONE_PROP_ACK, 400, HapticFeedbackConstants.LONG_PRESS)
            if (given == BreathCue.TONE) delay(450)
            finish()
        }
    }
    LaunchedEffect(countdown) {
        // A tick per second of countdown and a longer tone on the first inhale, the same grammar
        // the pulse measurement uses: the eyes are meant to be closed for this. Bounded by the
        // countdown's own seconds so a recreated runner does not replay it mid-session.
        if (countdown < BREATH_PREPARE_SECONDS && elapsed == 0) {
            cues.cue(
                if (countdown == 0) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP,
                if (countdown == 0) 300 else 120,
            )
        }
    }
    LaunchedEffect(tick.phase) {
        // Not on the final boundary: most durations end exactly on one, and the boundary beep
        // would clobber the finish chime on the shared generator.
        if (elapsed > 0 && !done) cues.cue(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    // Both are held as State and read where the bud is drawn, as the home cards read theirs: read
    // here they would recompose this whole runner on every frame of a session up to fifteen minutes
    // long, for a change that only the canvas shows.
    val breath = animateFloatAsState(
        targetValue = when {
            preparing -> 0.35f
            tick.phase == BreathPhase.INHALE || tick.phase == BreathPhase.HOLD_IN -> 1f
            else -> 0f
        },
        animationSpec = tween(
            if (preparing) 600 else tick.secondsLeft * 1_000,
            easing = LinearEasing,
        ),
        label = "breath",
    )
    // The clock is animated once and the lap position derived from it, so the dot wraps at the
    // top of the ring instead of sweeping backwards through it at every new cycle. It aims one
    // second ahead — at the second being lived, not the one just finished — so the dot reaches
    // the top exactly when the new cycle's beep sounds.
    val clock = animateFloatAsState(
        targetValue = if (preparing) 0f else (elapsed + 1).coerceAtMost(total).toFloat(),
        animationSpec = tween(1_000, easing = LinearEasing),
        label = "clock",
    )
    val inhaling = !preparing && tick.phase == BreathPhase.INHALE
    val colors = budColors()
    val disc by animateColorAsState(
        targetValue = when {
            preparing -> colors.hold
            tick.phase == BreathPhase.INHALE -> BudInhale
            tick.phase == BreathPhase.EXHALE -> colors.exhale
            else -> colors.hold
        },
        animationSpec = tween(800),
        label = "disc",
    )
    // Every disc keeps the theme's copy except the deep inhale blue, which takes white.
    val onDisc by animateColorAsState(
        targetValue = if (inhaling) Color.White else colors.ink,
        animationSpec = tween(800),
        label = "onDisc",
    )

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Said as it changes, politely: the eyes are meant to be shut, and a screen reader would
        // otherwise leave the phase to be found by touch. It changes once a phase, never per second.
        Text(
            stringResource(if (preparing) R.string.breathing_prepare_hint else tick.phase.hintRes()),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        BreathingBud(
            title = stringResource(if (preparing) R.string.breathing_prepare else tick.phase.titleRes),
            seconds = if (preparing) countdown else tick.secondsLeft,
            breath = { breath.value },
            progress = { if (preparing) 0f else clock.value % cycleSeconds / cycleSeconds },
            maxSize = budMax,
            showUnit = !preparing,
            showRing = !preparing,
            // The exhale keeps most of its blue at the belly; the pauses go near the glow.
            coreLightTarget = when {
                inhaling -> 0f
                !preparing && tick.phase == BreathPhase.EXHALE -> 0.18f
                else -> 0.4f
            },
            rimLightTarget = if (inhaling) 0f else 0.22f,
            colors = colors,
            disc = disc,
            onDisc = onDisc,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val left = (total - elapsed).coerceAtLeast(0)
            val locale = LocalLocale.current.platformLocale
            val spoken =
                stringResource(R.string.m3_pain_time_left, remember(left, locale) { spokenDuration(left, locale) })
            // One node, said in words: minutes and seconds with a colon can be read as a time of day.
            Row(
                Modifier.clearAndSetSemantics { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "${left / 60}:${(left % 60).toString().padStart(2, '0')}",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    stringResource(
                        R.string.breathing_cycles_progress,
                        (tick.completedCycles + 1).coerceAtMost(totalCycles),
                        totalCycles,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.breathing_cycles),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        PhaseDots(active = if (preparing) 0 else tick.phase.ordinal + 1, accent = colors.accent)
        TextButton(onClick = { finish() }) {
            Text(stringResource(R.string.breathing_stop))
        }
    }
}

/** "4 minutes, 5 seconds" in the language showing, without a part that is nought. */
private fun spokenDuration(seconds: Int, locale: Locale): String {
    val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
    val minutes = Measure(seconds / 60, MeasureUnit.MINUTE)
    val rest = Measure(seconds % 60, MeasureUnit.SECOND)
    return when {
        seconds < 60 -> format.formatMeasures(rest)
        seconds % 60 == 0 -> format.formatMeasures(minutes)
        else -> format.formatMeasures(minutes, rest)
    }
}

private fun BreathPhase.hintRes(): Int = when (this) {
    BreathPhase.INHALE -> R.string.breathing_inhale_hint
    BreathPhase.HOLD_IN -> R.string.breathing_hold_hint
    BreathPhase.EXHALE -> R.string.breathing_exhale_hint
    BreathPhase.HOLD_OUT -> R.string.breathing_pause_hint
}

/**
 * Five dots — get-ready plus the four phases — as a little pager. The current one is drawn
 * long as well as in the accent, the way a pager marks its page: told apart by colour alone, the lit
 * dot would be a shade off the others. The rest are in the outline colour, which clears 3:1 on the page.
 * Nothing here is read aloud; the phase is said by the line above the bud.
 */
@Composable
private fun PhaseDots(active: Int, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(BreathPhase.entries.size + 1) { index ->
            val lit = index == active
            val width by animateDpAsState(
                if (lit) PHASE_DOT_LIT_WIDTH else PHASE_DOT_SIZE,
                PwnMotion.spatial(),
                label = "phase dot",
            )
            Box(
                Modifier.height(PHASE_DOT_SIZE).width(width).background(
                    if (lit) accent else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
            )
        }
    }
}

private val PHASE_DOT_SIZE = 7.dp
private val PHASE_DOT_LIT_WIDTH = 18.dp

/**
 * The screen's centrepiece: a thin, faintly wavy ring
 * whose progress arc fades in behind the travelling dot; a watercolour cloud of scalloped
 * translucent layers that swells with the breath; and a disc that is a soft glow, not a hard
 * circle — it grows through the inhale and shrinks back through the exhale. All of it is
 * decorative; the phase word and the count in the middle are the accessible copy, read as one.
 *
 * [maxSize] caps the square, which otherwise takes the whole width of the page it stands in.
 *
 * [breath] and [progress] are asked for where the canvas draws, so a value that moves on every frame
 * redraws the bud and recomposes nothing.
 */
@Composable
private fun BreathingBud(
    title: String,
    seconds: Int,
    breath: () -> Float,
    progress: () -> Float,
    maxSize: Dp,
    modifier: Modifier = Modifier,
    showUnit: Boolean = true,
    showRing: Boolean = true,
    /** How much the disc's belly lightens towards the glow: 0 keeps the inhale's saturated core,
     * the pauses go near the glow. From the target phase, not the animating colour — deriving it
     * from the colour would flip the gradient only on the tween's very last frame. */
    coreLightTarget: Float = 0.4f,
    /** How far the 0.75 stop lifts towards the glow before the edge dissolves into the cloud. The
     * inhale zeroes it, so its white copy stands on the flat core however wide the phase word is:
     * one as wide as the German "Einatmen" reaches past the 0.45 stop with its outer letters.
     * Tweened from the target phase for the same reason [coreLightTarget] is. */
    rimLightTarget: Float = 0.22f,
    colors: BudColors = budColors(),
    disc: Color = colors.hold,
    onDisc: Color = colors.ink,
) {
    // Tweened alongside the 800 ms colour tween so the gradient never snaps.
    val coreLight by animateFloatAsState(
        targetValue = coreLightTarget,
        animationSpec = tween(800),
        label = "coreLight",
    )
    val rimLight by animateFloatAsState(
        targetValue = rimLightTarget,
        animationSpec = tween(800),
        label = "rimLight",
    )
    // One path for every outline of every frame, rewound between them, rather than a new Path for
    // each — seven for the cloud, one for the ring, up to twenty-four for the arc — on every frame of
    // the session, all of them left for the collector. A drawn path is copied into the frame as it is
    // drawn, so the next outline may take it over.
    val outline = remember { Path() }
    Box(
        modifier.widthIn(max = maxSize).fillMaxWidth().aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val ringR = size.minDimension / 2f - 10.dp.toPx()

            fun ringPoint(deg: Int): Offset {
                val rad = Math.toRadians(deg.toDouble())
                val r = ringR * (1f + WAVE_AMPLITUDE * sin(WAVE_CRESTS * rad).toFloat())
                return Offset(
                    centre.x + (cos(rad) * r).toFloat(),
                    centre.y + (sin(rad) * r).toFloat(),
                )
            }

            fun wavyPath(fromDeg: Int, toDeg: Int) = outline.apply {
                rewind()
                val first = ringPoint(fromDeg)
                moveTo(first.x, first.y)
                var deg = fromDeg + PATH_STEP_DEG
                while (deg < toDeg) {
                    val p = ringPoint(deg)
                    lineTo(p.x, p.y)
                    deg += PATH_STEP_DEG
                }
                val last = ringPoint(toDeg)
                lineTo(last.x, last.y)
            }

            val breathC = breath().coerceIn(0f, 1f)

            // The cloud: scalloped layers, outermost faintest, all breathing with the phase.
            // Layer counts and alphas are tuned so the stack composites to ~30%
            // lavender near the disc on a full breath and to a thin fringe on an empty one.
            repeat(CLOUD_LAYERS) { i ->
                val eased = breathC.pow(0.65f + 0.12f * i)
                val spread = 0.66f + 0.34f * i / (CLOUD_LAYERS - 1f)
                val reach = ringR * (0.70f + 0.22f * eased) * spread
                rotate(i * 49f, centre) {
                    drawPath(
                        path = outline.apply {
                            rewind()
                            val crests = 5 + (i * 2) % 4
                            val amp = 0.05f + 0.02f * (i % 3)
                            for (deg in 0..360 step PATH_STEP_DEG) {
                                val rad = Math.toRadians(deg.toDouble())
                                val r = reach * (1f + amp * sin(crests * rad).toFloat())
                                val p = Offset(
                                    centre.x + (cos(rad) * r).toFloat(),
                                    centre.y + (sin(rad) * r).toFloat(),
                                )
                                if (deg == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                            }
                            close()
                        },
                        color = PwnIndigoRaw.copy(alpha = 0.028f + 0.05f * eased),
                    )
                }
            }

            // The disc: a glow that swells with the lungs, its edge dissolving into the cloud.
            val discR = ringR * (0.60f + 0.16f * breathC)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to lerp(disc, colors.glow, coreLight),
                        0.45f to disc,
                        0.75f to lerp(disc, colors.glow, rimLight * (1f - coreLight / 0.4f)),
                        1f to disc.copy(alpha = 0f),
                    ),
                    center = centre,
                    radius = discR,
                ),
                radius = discR,
                center = centre,
            )

            // The prepare frame has no ring yet: it appears with the first inhale,
            // drawn over the cloud so the dot never dives underneath it.
            if (showRing) {
                // The whole lap is only a faint guide under the arc: the dot and the arc carry where
                // the breath is, and the phase word and the count in the middle say it outright.
                drawPath(
                    wavyPath(-90, 270),
                    colors.accent.copy(alpha = 0.16f),
                    style = Stroke(1.5.dp.toPx()),
                )
                val travelled = (progress().coerceIn(0f, 1f) * 360).toInt()
                // The arc is a comet tail: fainter where the lap began, saturated at the dot, and
                // never so faint that it drops under 3:1 on the page — [BudColors.tailFloor].
                // Sweep gradients can't start at the top of a circle, so it is stroked in short
                // segments with stepped alpha. Butt caps, deliberately: round caps overlap at
                // the joints and double the alpha there, beading the faint end of the tail.
                val steps = 24
                for (s in 0 until steps) {
                    val from = -90 + travelled * s / steps
                    val to = -90 + travelled * (s + 1) / steps
                    if (to > from) {
                        drawPath(
                            path = wavyPath(from, to),
                            color = colors.accent.copy(
                                alpha = colors.tailFloor + (1f - colors.tailFloor) * (s + 1f) / steps,
                            ),
                            style = Stroke(2.5.dp.toPx()),
                        )
                    }
                }
                drawCircle(colors.accent, 7.dp.toPx(), ringPoint(-90 + travelled))
            }
        }
        // The phase word and the unit take the disc's ink at full strength: dimmed to 80% of it, on the
        // inhale they would fall under the 4.5:1 the 16sp word and the 14sp unit need. The ratios are the
        // palette's, at [BudColors]. Read as one: three separate stops
        // for a word, a number and "sec" would leave a screen reader to put the sentence back together.
        Column(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = onDisc, style = MaterialTheme.typography.titleMedium)
            Text(
                seconds.toString(),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = onDisc,
            )
            if (showUnit) {
                Text(
                    stringResource(R.string.breathing_seconds_unit),
                    color = onDisc,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Duration, pace and sound, one row each. */
@Composable
private fun BreathingSettingsCard(
    durationMin: Int,
    tempoSec: Int,
    sound: Boolean,
    onDuration: (Int) -> Unit,
    onTempo: (Int) -> Unit,
    onSound: (Boolean) -> Unit,
) {
    var picking by remember { mutableStateOf<String?>(null) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            BreathingSettingRow(
                art = R.drawable.ic_art_time,
                icon = Icons.Outlined.Schedule,
                title = stringResource(R.string.breathing_duration),
                value = stringResource(R.string.breathing_duration_value, durationMin),
                support = null,
            ) { picking = "duration" }
            HorizontalDivider(color = PwnBorder)
            BreathingSettingRow(
                art = R.drawable.ic_art_lungs,
                icon = Icons.Outlined.Air,
                title = stringResource(R.string.breathing_tempo),
                value = stringResource(R.string.breathing_tempo_value, tempoSec),
                support = stringResource(R.string.breathing_tempo_pattern),
            ) { picking = "tempo" }
            HorizontalDivider(color = PwnBorder)
            // The drawing belongs to this row rather than to PwnSwitchRow, which is words and a
            // switch; the row itself carries the tap, so the picture and the label answer it too.
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = sound, role = Role.Switch, onValueChange = onSound)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(
                    themePainterResource(R.drawable.ic_art_reminders),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Text(stringResource(R.string.breathing_sound), Modifier.weight(1f))
                PwnSwitch(checked = sound, onCheckedChange = null)
            }
        }
    }
    val resources = LocalResources.current
    when (picking) {
        "duration" -> WheelPickerDialog(
            label = stringResource(R.string.breathing_duration),
            initialValue = durationMin,
            values = BREATH_DURATION_CHOICES,
            formatter = { resources.getString(R.string.breathing_duration_value, it) },
            onDismiss = { picking = null },
            onConfirm = {
                onDuration(it)
                picking = null
            },
        )

        "tempo" -> WheelPickerDialog(
            label = stringResource(R.string.breathing_tempo),
            initialValue = tempoSec,
            values = BREATH_TEMPO_CHOICES,
            formatter = { resources.getString(R.string.breathing_tempo_value, it) },
            onDismiss = { picking = null },
            onConfirm = {
                onTempo(it)
                picking = null
            },
        )
    }
}

@Composable
private fun BreathingSettingRow(
    art: Int?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    support: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PwnGlyph(art, icon, PwnIndigoSoft, null, Modifier.size(40.dp))
        // The name and the pattern share the middle column, one above the other. Side by side, the
        // pattern — four words and three dashes — would take the width and leave "Breathing pace" a
        // column four letters wide, which the layout would then break into five lines of it.
        //
        // The value rides on the name's line while the two fit, and drops under the name when they
        // do not. Held to one line at the row's end, it would leave the name a column a few letters wide
        // at a large font size, with both lines cut to an ellipsis; so each wraps instead.
        Column(Modifier.weight(1f)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, Modifier.padding(end = 12.dp))
                Text(value, fontWeight = FontWeight.SemiBold)
            }
            support?.let {
                Text(it, color = PwnMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = PwnMuted)
    }
}

/** The footnote link under the button; the dialog says what the exercise actually is. */
@Composable
private fun BreathingInfoLink() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Text(stringResource(R.string.breathing_info))
        Spacer(Modifier.size(6.dp))
        Icon(Icons.Outlined.Info, null, modifier = Modifier.size(16.dp))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.breathing_info)) },
            text = { Text(stringResource(R.string.breathing_info_body)) },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.common_done))
                }
            },
        )
    }
}

@Composable
private fun BreathingStatsCard(stats: BreathingStats) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.breathing_stats_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (stats.sessions == 0) {
                Text(
                    stringResource(R.string.breathing_never),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                BreathingStatsRow(stats)
            }
        }
    }
}

/**
 * The cues of a practice done with the eyes off the screen — a breathing session's, and the steps of
 * a workout template, whose tones alone would mark nothing on a phone on vibrate. Each cue is a
 * tone or, on a phone that would play the tone at nothing, a tap of the motor;
 * see [breathCue]. The ringer is asked at every cue and not once: it is switched mid-practice too.
 */
internal class BreathCues(
    private val sound: Boolean,
    private val tones: ToneGenerator?,
    private val audio: AudioManager,
    private val view: View,
) {
    /** Gives one cue and says how it was given; [haptic] is the tap that stands in for the tone. */
    fun cue(tone: Int, toneMs: Int, haptic: Int = HapticFeedbackConstants.CONFIRM): BreathCue {
        val audible = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0
        val given = breathCue(sound, audible)
        when (given) {
            BreathCue.TONE -> tones?.startTone(tone, toneMs)
            BreathCue.HAPTIC -> view.performHapticFeedback(haptic)
            BreathCue.NONE -> Unit
        }
        return given
    }
}

/** The cues for as long as [sound] stands as it is; their tone generator goes with them. */
@Composable
internal fun rememberBreathCues(sound: Boolean): BreathCues {
    val tones = rememberBreathTones(sound)
    val context = LocalContext.current
    val view = LocalView.current
    return remember(sound, tones, context, view) {
        BreathCues(sound, tones, context.getSystemService(AudioManager::class.java), view)
    }
}

/** Kept for the card's lifetime and released with it; null while sound cues are switched off. */
@Composable
private fun rememberBreathTones(enabled: Boolean): ToneGenerator? {
    val tones = remember(enabled) {
        if (enabled) {
            runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85) }.getOrNull()
        } else {
            null
        }
    }
    DisposableEffect(tones) { onDispose { tones?.release() } }
    return tones
}

@Composable
private fun BreathingStatsRow(stats: BreathingStats) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BreathingStat(stringResource(R.string.breathing_sessions), stats.sessions.toString(), Modifier.weight(1f))
        BreathingStat(stringResource(R.string.breathing_cycles), stats.cycles.toString(), Modifier.weight(1f))
        BreathingStat(
            stringResource(R.string.breathing_minutes),
            stats.minutes.shown(),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun BreathingStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
    }
}
