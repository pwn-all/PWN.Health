package com.pwnall.health

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** All positions avoid the central text band. The seed survives a rotation, but differs next time. */
private data class WelcomeSymbol(val art: Int, val x: Float, val y: Float, val phase: Float, val delay: Float)

private fun welcomeSymbols(seed: Int): List<WelcomeSymbol> {
    val arts = listOf(
        R.drawable.ic_art_weight,
        R.drawable.ic_art_height,
        R.drawable.ic_art_mood,
        R.drawable.ic_art_steps,
        R.drawable.ic_art_pulse,
        R.drawable.ic_art_sleep,
    )
    val random = Random(seed)
    return List(SYMBOL_COUNT) { index ->
        val row = index / 3
        val x = (index % 3 + 0.35f + random.nextFloat() * 0.3f) / 3f
        val y = listOf(0.08f, 0.20f, 0.80f, 0.92f)[row] + random.nextFloat() * 0.025f
        WelcomeSymbol(
            art = arts[index % arts.size],
            x = x,
            y = y,
            phase = random.nextFloat() * (2 * PI).toFloat(),
            // The farther a symbol stands from the centre, the later it sets off, so they arrive
            // together in a ring rather than the nearest ones queuing in the middle.
            delay = (hypot(x - 0.5f, y - 0.5f) / 0.75f).coerceIn(0f, 1f) * GATHER_STAGGER,
        )
    }
}

/**
 * The finish. The symbols that drift behind the welcome drift behind it too; in front of them is
 * what the answers came to, counted up from nothing once, and the one button. Start then plays the
 * way out in four parts, each its own easing:
 *
 * 1. the figures sink and fade, because they have been read;
 * 2. the symbols set off for the centre, the farthest first, growing visible as they come;
 * 3. they close into a ring that tightens to a point;
 * 4. the point opens into the page the home screen is drawn on.
 *
 * With animations switched off in the system settings nothing drifts, nothing counts and Start goes
 * straight on.
 */
@Composable
internal fun OnboardingReadyScreen(summary: ReadySummary, onBack: () -> Unit, onStart: () -> Unit) {
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val seed = rememberSaveable { Random.nextInt() }
    val symbols = remember(seed) { welcomeSymbols(seed) }
    val drift = rememberInfiniteTransition(label = "welcome drift")
    val time by drift.animateFloat(
        0f,
        (2 * PI).toFloat(),
        infiniteRepeatable(tween(DRIFT_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "float",
    )
    val count = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        count.animateTo(1f, tween(COUNT_MS, delayMillis = COUNT_DELAY_MS, easing = EMPHASIZED_DECELERATE))
    }
    val exit = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    // Once Start is pressed the way out is under way and the back arrow is gone, and the system's
    // Back is not taken either. Left to the questionnaire it would go a page back while this page,
    // still leaving, would call [onStart] at the end of the animation and finish onboarding regardless.
    BackHandler(enabled = leaving) { }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val ringRadius = with(density) { RING_RADIUS.toPx() }

    // The four parts of the way out, each a 0..1 of its own. Asked for only inside the layer and
    // drawing blocks below, as the drift is: read out here, every frame of the way out — and of the
    // count before it — would compose the whole page again, twelve drawings and the figures, to move
    // what a layer moves without it.
    val sink = { EMPHASIZED_ACCELERATE.transform(phase(exit.value, 0f, 0.30f)) }
    val tighten = { EMPHASIZED.transform(phase(exit.value, 0.62f, 0.78f)) }
    val bloom = { EMPHASIZED_ACCELERATE.transform(phase(exit.value, 0.74f, 1f)) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        symbols.forEachIndexed { index, symbol ->
            // Where on the ring this symbol lands: evenly round it, starting at the top.
            val angle = index * 2 * PI / symbols.size - PI / 2
            Image(
                themePainterResource(symbol.art),
                null,
                Modifier.align(Alignment.Center).size(38.dp).graphicsLayer {
                    val gather = EMPHASIZED.transform(phase(exit.value, 0.12f + symbol.delay, 0.62f))
                    val tight = tighten()
                    val ringX = cos(angle).toFloat() * ringRadius * (1f - tight)
                    val ringY = sin(angle).toFloat() * ringRadius * (1f - tight)
                    val lean = if (still) 0f else sin(time + symbol.phase)
                    val float = lean * 7.dp.toPx() * (1f - gather)
                    val homeX = (symbol.x - 0.5f) * width + float
                    val homeY = (symbol.y - 0.5f) * height + float
                    translationX = homeX + (ringX - homeX) * gather
                    translationY = homeY + (ringY - homeY) * gather
                    val scale = 1f - gather * 0.45f - tight * 0.55f
                    scaleX = scale
                    scaleY = scale
                    rotationZ = lean * 7f * (1f - gather)
                    alpha = (0.10f + gather * 0.75f) * (1f - tight)
                },
            )
        }
        // The ring the symbols close into, drawn as they arrive and gone as it tightens. Until Start
        // every part of it stands at nothing and nothing is drawn.
        val ring = MaterialTheme.colorScheme.primary
        val ground = MaterialTheme.colorScheme.surface
        Canvas(Modifier.fillMaxSize()) {
            val progress = exit.value
            val tight = tighten()
            val opened = bloom()
            val centre = Offset(size.width / 2, size.height / 2)
            val formed = phase(progress, 0.45f, 0.62f)
            if (formed > 0f && tight < 1f) {
                drawCircle(
                    ring.copy(alpha = 0.35f * formed * (1f - tight)),
                    ringRadius * (1f - tight).coerceAtLeast(0.08f),
                    centre,
                    style = Stroke(2.dp.toPx()),
                )
            }
            // The point the ring tightens to: the accent, small, the one dot everything went into.
            val point = phase(progress, 0.66f, 0.78f) * (1f - opened)
            if (point > 0f) drawCircle(ring, 7.dp.toPx() * point, centre)
            if (opened > 0f) {
                // The point opens into the page the home screen is drawn on — the theme's own
                // surface rather than white, which in the dark palette would be a flash of glare —
                // with its edge drawn in the accent, fading as it reaches the corners, so the
                // opening is seen on a page the same colour as the one it opens over.
                val radius = hypot(size.width, size.height) / 2 * opened
                drawCircle(ground, radius, centre)
                drawCircle(ring.copy(alpha = 0.6f * (1f - opened)), radius, centre, style = Stroke(3.dp.toPx()))
            }
        }
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().widthIn(max = PwnDimensions.maxPaneWidth)
                .padding(horizontal = ONBOARDING_SIDE)
                .graphicsLayer {
                    val sunk = sink()
                    alpha = 1f - sunk
                    translationY = sunk * 28.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.onboarding_ready_title),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(28.dp))
            ReadyFigures(summary) { count.value }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.onboarding_ready_local),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
        }
        Box(
            Modifier.align(Alignment.TopStart).padding(start = ONBOARDING_SIDE - 8.dp, top = 8.dp)
                .graphicsLayer { alpha = 1f - sink() },
        ) {
            if (!leaving) OnboardingBack(onBack)
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = ONBOARDING_SIDE).padding(bottom = 20.dp)
                .graphicsLayer { alpha = 1f - sink() },
        ) {
            OnboardingAction(
                stringResource(R.string.common_start),
                Icons.AutoMirrored.Outlined.ArrowForward,
                enabled = !leaving,
                onClick = {
                    leaving = true
                    scope.launch {
                        if (!still) exit.animateTo(1f, tween(EXIT_MS, easing = LinearEasing))
                        onStart()
                    }
                },
            )
        }
    }
}

/**
 * The three figures, one row each with a hairline between: a name on one side and the figure on the
 * other, so the eye runs down a short table rather than across three tiles. [count] runs 0 to 1 once
 * and the figures count up with it; it is asked for here, so a frame of the count composes these
 * three rows and not the page around them.
 */
@Composable
private fun ReadyFigures(summary: ReadySummary, count: () -> Float) {
    val locale = LocalLocale.current.platformLocale
    val counted = count()
    val steps = String.format(locale, "%,d", (summary.stepGoal * counted).roundToInt())
    val kcal = stringResource(
        R.string.onboarding_ready_kcal_day,
        String.format(locale, "%,d", (summary.restingKcal * counted).roundToInt()),
    )
    val index = String.format(locale, "%.1f", summary.bmi * counted)
    val rows = listOf(
        stringResource(R.string.onboarding_ready_steps) to steps,
        stringResource(R.string.onboarding_ready_resting) to kcal,
        stringResource(R.string.profile_bmi) to "$index · ${stringResource(summary.bmiLabel)}",
    )
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { at, (name, figure) ->
            if (at > 0) HorizontalDivider(color = PwnBorder)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp).semantics(mergeDescendants = true) {
                    contentDescription = "$name: $figure"
                },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = PwnMuted)
                Text(
                    figure,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** Where [progress] is between [from] and [to], as 0..1. */
private fun phase(progress: Float, from: Float, to: Float): Float = ((progress - from) / (to - from)).coerceIn(0f, 1f)

/** Material's emphasized curves: the decelerate for things arriving, the accelerate for things leaving. */
private val EMPHASIZED = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EMPHASIZED_DECELERATE = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EMPHASIZED_ACCELERATE = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

private const val SYMBOL_COUNT = 12

/** How much of the gathering the farthest symbol waits before it sets off. */
private const val GATHER_STAGGER = 0.18f

/** The ring the symbols close into, before it tightens. */
private val RING_RADIUS = 56.dp

/** One full sway of the welcome symbols. */
private const val DRIFT_MS = 7_000

/** The way out, all four parts of it. */
private const val EXIT_MS = 1_700

/** The figures' count from nothing, after the page has had a moment to arrive. */
private const val COUNT_MS = 900
private const val COUNT_DELAY_MS = 250
