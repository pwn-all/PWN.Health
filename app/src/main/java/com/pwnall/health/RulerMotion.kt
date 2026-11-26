package com.pwnall.health

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign

/** Strong throws keep coasting until touched or the finite scale ends; small throws settle naturally. */
internal fun rulerCoastSpeed(initial: Float, elapsedSeconds: Float, sustained: Boolean): Float {
    val speed = abs(initial).coerceAtMost(180f)
    val floor = if (sustained) (speed * 0.18f).coerceAtLeast(1.5f) else 0f
    return initial.sign * (speed * exp(-elapsedSeconds * 1.6f)).coerceAtLeast(floor)
}

internal class RulerMotion(
    initial: Int,
    private val last: Int,
    private val scope: CoroutineScope,
    private val select: (Int) -> Unit,
) {
    var position by mutableFloatStateOf(initial.toFloat())
        private set
    var moving by mutableStateOf(false)
        private set
    private var animation: Job? = null
    private var dragging = false

    fun sync(index: Int) {
        if (!moving && !dragging) position = index.toFloat()
    }

    /** A finger on the strip: the coast ends, and [sync] waits until the finger lifts ([fling]). */
    fun stop() {
        animation?.cancel()
        animation = null
        moving = false
        dragging = true
        position = position.roundToInt().toFloat()
    }

    /**
     * The coast ends with no finger on the strip — its page has stopped being the one shown. [stop]
     * alone would leave the ruler held for a drag nobody is making, so after Next and a quick Back the
     * same ruler would ignore every later value: "use the suggestion" would change the figure and not
     * the strip.
     */
    fun halt() {
        stop()
        dragging = false
    }

    fun drag(delta: Float) {
        moveTo(position + delta)
    }

    private fun moveTo(next: Float): Boolean {
        val safe = next.coerceIn(0f, last.toFloat())
        val before = position.roundToInt()
        position = safe
        if (safe.roundToInt() != before) select(safe.roundToInt())
        return safe == next
    }

    fun fling(velocity: Float, sustained: Boolean) {
        dragging = false
        if (abs(velocity) < 0.5f) {
            position = position.roundToInt().toFloat()
            return
        }
        moving = true
        animation = scope.launch {
            try {
                val start = withFrameNanos { it }
                var previous = start
                while (true) {
                    val now = withFrameNanos { it }
                    val speed = rulerCoastSpeed(velocity, (now - start) / 1_000_000_000f, sustained)
                    val delta = speed * ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
                    previous = now
                    if (abs(speed) < 0.5f || !moveTo(position + delta)) break
                }
            } finally {
                position = position.roundToInt().toFloat()
                moving = false
            }
        }
    }
}

@Composable
internal fun rememberRulerMotion(index: Int, last: Int, onIndex: (Int) -> Unit): RulerMotion {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onIndex)
    val tick = rememberRulerTick()
    val motion = remember(last) {
        RulerMotion(index, last, scope) {
            latest(it)
            tick()
        }
    }
    LaunchedEffect(index) { motion.sync(index) }
    val active = LocalRulerActive.current
    LaunchedEffect(active) { if (!active) motion.halt() }
    DisposableEffect(motion) { onDispose { motion.halt() } }
    return motion
}

@Composable
internal fun Modifier.rulerDraggable(motion: RulerMotion, spacing: Float, orientation: Orientation): Modifier {
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 500f * density }
    val velocity = remember { RulerDragVelocity() }
    return draggable(
        state = rememberDraggableState {
            velocity.record(it, SystemClock.uptimeMillis())
            motion.drag(-it / spacing)
        },
        orientation = orientation,
        startDragImmediately = motion.moving,
        onDragStarted = {
            velocity.reset(SystemClock.uptimeMillis())
            motion.stop()
        },
        onDragStopped = {
            val speed = velocity.released(it, SystemClock.uptimeMillis())
            motion.fling(-speed / spacing, abs(speed) >= threshold)
        },
    )
}

internal val LocalRulerActive = androidx.compose.runtime.staticCompositionLocalOf { true }

/** Some input devices report a zero terminal velocity; retain recent movement, never a held drag. */
internal class RulerDragVelocity {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var distance = 0f

    fun reset(now: Long) {
        samples.clear()
        distance = 0f
        samples.addLast(now to distance)
    }

    fun record(delta: Float, now: Long) {
        if (delta == 0f) return
        distance += delta
        samples.addLast(now to distance)
        while (samples.size > 2 && now - samples.first().first > 180L) samples.removeFirst()
    }

    fun released(platform: Float, now: Long): Float {
        if (abs(platform) > 1f) return platform
        val first = samples.firstOrNull() ?: return 0f
        val last = samples.last()
        if (now - last.first > 120L) return 0f
        return (last.second - first.second) * 1_000f / (last.first - first.first).coerceAtLeast(16L)
    }
}

/** A quiet mechanical tick, respecting the system's sound-effects switch and silent/vibrate modes. */
@Composable
internal fun rememberRulerTick(): () -> Unit {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val pool = remember {
        SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
    }
    var sound by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(pool) {
        pool.setOnLoadCompleteListener { _, sample, status -> if (status == 0) sound = sample }
        pool.load(context, R.raw.ruler_tick, 1)
        onDispose { pool.release() }
    }
    val gate = remember(context, audio) {
        RulerTickGate {
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
                Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1
        }
    }
    return {
        val sample = sound
        if (sample != null && gate.opens(SystemClock.uptimeMillis())) pool.play(sample, 0.28f, 0.28f, 1, 0, 1f)
    }
}

/**
 * Whether a tick may sound now: at most one in [TICK_GAP_MS], and then only if [soundOn].
 *
 * The gap is asked first. [soundOn] reads the ringer mode, which is a call into the system server
 * on the main thread, and asked first it would be made for every value a ruler passes — one a frame
 * while a throw coasts — to play a tick every other frame at most. A gap that has passed is spent whether or not
 * the tick sounds, so a silenced phone is asked no more often than one that ticks.
 */
internal class RulerTickGate(private val soundOn: () -> Boolean) {
    private var last = 0L

    fun opens(now: Long): Boolean {
        if (now - last < TICK_GAP_MS) return false
        last = now
        return soundOn()
    }
}

private const val TICK_GAP_MS = 28L
