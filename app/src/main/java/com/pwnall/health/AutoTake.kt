package com.pwnall.health

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

// The one clock behind every page that asks one question at a time: an answer that takes more than
// one touch has no "Next" to close it. It is taken three seconds after the last change instead, and
// a thin line filling under the question shows the three seconds running, so the page moving on
// does not come as a surprise. One implementation, so the pages that use it cannot drift apart.

/** How long a question whose answer takes more than one touch waits after the last before taking it. */
internal const val AUTO_TAKE_MS = 3_000L

/** How a question asked one at a time gives its answer, which decides whether the clock runs under it. */
internal enum class AnswerKind {
    /** One tap is the whole answer — a single choice — and the tap takes it. No clock. */
    TAP,

    /**
     * Several touches make the answer — a set of chips, a ruler, a time — and it is taken
     * [AUTO_TAKE_MS] after the last of them, each one starting the three seconds again.
     */
    SETTLE,
}

/**
 * Whether the clock runs under a question now.
 *
 * A question is never answered for the person: the clock runs only once it [holds] an answer the
 * person gave — [touched] since it was put, or [answered] before it was reopened. A number resting
 * where the ruler starts, or words carried over from an earlier entry, are where the control starts,
 * not answers. Nothing is passed over by being left alone: a question with a "don't know" offers it
 * as one of its answers.
 */
internal fun autoTakeArmed(kind: AnswerKind, holds: Boolean, touched: Boolean, answered: Boolean): Boolean =
    when (kind) {
        AnswerKind.TAP -> false
        AnswerKind.SETTLE -> holds && (touched || answered)
    }

/**
 * Whether the three seconds count now: [armed] ([autoTakeArmed]), not [held] by a finger on the
 * control, and with the page's window [focused] — not under a dialog, a list or a sheet.
 */
internal fun autoTakeRuns(armed: Boolean, held: Boolean, focused: Boolean): Boolean = armed && !held && focused

/**
 * The clock under the question it belongs to: a thin line that fills over [AUTO_TAKE_MS], then
 * [onTaken]. [restart] is anything that changes with every change to the answer, each one starting
 * the three seconds again. The clock stands still while the page's window is not focused — every
 * dialog, list or sheet opened over it is a window of its own and takes the focus: a "?" read over an
 * open question, the selector's list, the area picker, a system sheet — and the three seconds start
 * again when it closes, so nothing is taken behind it. [held] stops it as well, for what the window
 * cannot see: a finger resting on a ruler mid-drag. Not [armed], the line keeps its height and draws
 * nothing, so arming it does not move the page.
 *
 * [takeOnLeave] is for a page whose answer is a record rather than a step: what is armed is taken
 * when the clock leaves the page, too, rather than thrown away — back, another page, another day
 * picked — as a one-tap answer is; and when the app is left, Home or the power
 * button, since a clock that only stood still there would file the record at the moment of coming
 * back, hours later and perhaps on another day. Taken at the activity's stop, which comes before its
 * state is saved, so a page the system rebuilds later does not bring the answer back armed and file
 * it a second time. Not on a rotation: the page's saved state brings the answer back armed and the
 * clock starts again, and taking it on the way out as well would file it twice. Taken once per
 * [restart], so the clock running out and the page leaving in the same frame file it once.
 *
 * The three seconds are the person's own "time to take action" where the accessibility settings set
 * one longer, and ten times as long while touch exploration is on ([autoTakeWindow]).
 *
 * Counted on the frame clock rather than run as an animation: with animations switched off in the
 * system's settings an animation ends the moment it starts, and the answer would be taken the moment
 * it was given. Each frame adds at most [FRAME_STEP_CAP_NANOS], so a stretch with no frames — the
 * screen off, the app in the background — is no time at all to the clock, and coming back does not
 * take an answer on the first frame. Silent to a screen reader, which would otherwise hear a progress
 * bar on every frame.
 */
@Composable
internal fun AutoTakeClock(
    armed: Boolean,
    restart: Any?,
    onTaken: () -> Unit,
    modifier: Modifier = Modifier,
    held: Boolean = false,
    takeOnLeave: Boolean = false,
) {
    val take by rememberUpdatedState(onTaken)
    val latestArmed by rememberUpdatedState(armed)
    var fraction by remember { mutableFloatStateOf(0f) }
    val focused = LocalWindowInfo.current.isWindowFocused
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    // A plain holder, not state: nothing is drawn from it. Written here rather than in an effect so a
    // page leaving in the same frame as a change still sees the change as not yet taken.
    val taken = remember { TakenChange() }
    if (taken.restart != restart) {
        taken.restart = restart
        taken.done = false
    }
    LaunchedEffect(armed, restart, held, focused) {
        fraction = 0f
        if (!autoTakeRuns(armed, held, focused)) return@LaunchedEffect
        val window = autoTakeWindow(
            recommendedMs = accessibility?.getRecommendedTimeoutMillis(
                AUTO_TAKE_MS.toInt(),
                AccessibilityManager.FLAG_CONTENT_CONTROLS,
            )?.toLong() ?: AUTO_TAKE_MS,
            touchExploration = accessibility?.isTouchExplorationEnabled == true,
        )
        var last = withFrameNanos { it }
        var elapsed = 0L
        while (fraction < 1f) {
            val now = withFrameNanos { it }
            elapsed += autoTakeFrameStep(now - last)
            last = now
            fraction = autoTakeFraction(elapsed / NANOS_PER_MS, window)
        }
        taken.done = true
        take()
    }
    if (takeOnLeave) {
        val activity = LocalActivity.current
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            fun leave() {
                if (latestArmed && !taken.done && activity?.isChangingConfigurations != true) {
                    taken.done = true
                    take()
                }
            }
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) leave() }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                leave()
            }
        }
    }
    val ink = MaterialTheme.colorScheme.primary
    // Read in the draw phase only, so the three seconds redraw the line and recompose nothing.
    Box(
        modifier.fillMaxWidth()
            .height(AUTO_TAKE_LINE)
            .clearAndSetSemantics {}
            .drawBehind {
                if (fraction > 0f) {
                    val y = size.height / 2f
                    val length = (size.width - 2 * y) * fraction
                    // From the start of the line in reading order: the right edge in Arabic.
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val from = if (rtl) size.width - y else y
                    drawLine(
                        color = ink,
                        start = Offset(from, y),
                        end = Offset(if (rtl) from - length else from + length, y),
                        strokeWidth = size.height,
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
}

/**
 * The scope a record taken by the clock is written in. Its work is not cancelled with the page: an
 * answer taken as the page leaves ([AutoTakeClock]'s takeOnLeave) is written after the page's own
 * scope has gone, and a write the clock started a moment before the person left must not be cut off
 * inside its transaction.
 *
 * So the page's job is swapped for a supervisor of its own, as every scope of the app that outlives
 * its screen is made. Not NonCancellable, which would do the same here but is meant for withContext
 * alone: a coroutine launched under it has no parent at all.
 *
 * Nothing above that work handles a failure in it, either: the supervisor lets it through, and it
 * ends the process as one in the page's own scope does. A write launched here that can fail in a way
 * the page could answer catches its own failure and says so, as the chat's filing does.
 */
@Composable
internal fun rememberAutoTakeScope(): CoroutineScope {
    val scope = rememberCoroutineScope()
    return remember(scope) { CoroutineScope(scope.coroutineContext + SupervisorJob()) }
}

/**
 * How long the clock runs: [recommendedMs], the platform's answer for a control given [AUTO_TAKE_MS]
 * (longer when the person asked for more time in the accessibility settings), and while touch
 * exploration is on at least ten times [AUTO_TAKE_MS] — the stretch WCAG 2.2 success criterion 2.2.1
 * (Timing Adjustable) asks a time limit to allow, since moving a screen reader's focus from one
 * control to the next is a swipe each.
 */
internal fun autoTakeWindow(recommendedMs: Long, touchExploration: Boolean): Long =
    maxOf(recommendedMs, if (touchExploration) AUTO_TAKE_MS * TOUCH_EXPLORATION_STRETCH else AUTO_TAKE_MS)

/**
 * What one frame adds to the clock: the time since the frame before, but never more than
 * [FRAME_STEP_CAP_NANOS]. A gap longer than that is not a slow frame but no frames at all — the
 * screen off or the app away — and none of it was time the person had in front of the question.
 */
internal fun autoTakeFrameStep(sinceLastNanos: Long): Long = sinceLastNanos.coerceIn(0L, FRAME_STEP_CAP_NANOS)

/** How far the line has filled after [elapsedMs] of [windowMs]: none at the start, all of it at the end. */
internal fun autoTakeFraction(elapsedMs: Long, windowMs: Long = AUTO_TAKE_MS): Float =
    (elapsedMs.toFloat() / windowMs).coerceIn(0f, 1f)

/** Which change the clock last saw, and whether it has been taken. */
private class TakenChange {
    var restart: Any? = null
    var done = false
}

private const val NANOS_PER_MS = 1_000_000L

/** A tenth of a second: several frames late even at 30 a second, far short of any time away. */
internal const val FRAME_STEP_CAP_NANOS = 100L * NANOS_PER_MS

/** WCAG 2.2.1's "at least ten times the length of the default setting". */
private const val TOUCH_EXPLORATION_STRETCH = 10

private val AUTO_TAKE_LINE = 3.dp
