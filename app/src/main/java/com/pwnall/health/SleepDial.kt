package com.pwnall.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val DIAL_SIZE = 320.dp
private val DIAL_STROKE = 16.dp

/** Clear space between a handle's rim and the hour label outside it. */
private val DIAL_LABEL_GAP = 4.dp

/** Degrees left empty at the bottom of the track, where the noon label sits. */
private const val DIAL_GAP_DEG = 36f

/** Dragging a handle snaps to this many minutes; nobody logs falling asleep at 23:13. */
private const val SNAP_MINUTES = 5

/** How far from the ring a touch still counts as grabbing a handle. */
private val DIAL_GRAB = 40.dp

/**
 * How far one accessibility action moves a handle. Three snaps rather than one: a screen reader
 * moves this clock a step at a time, and at five minutes an hour of sleep costs twelve taps.
 */
private const val DIAL_STEP_MINUTES = 15

/** The hours printed around the dial: every third one, so a glance lands within ninety minutes. */
private val DIAL_LABELS =
    listOf(0 to "24:00", 3 to "3", 6 to "6", 9 to "9", 12 to "12:00", 15 to "15", 18 to "18", 21 to "21")

/** How big the two draggable handles are. Half of it is what centres one on the ring. */
private val DIAL_HANDLE = 34.dp

/**
 * What a dial that is not measuring a night says instead: the line under the duration and the four
 * screen-reader steps. A work session is the same instrument — a start, an end, the span between
 * them — and "fell asleep later" read out over it would be the wrong clock named.
 */
internal data class DialWords(
    val caption: String,
    val startLater: String,
    val startEarlier: String,
    val endLater: String,
    val endEarlier: String,
)

/**
 * A 24-hour dial with midnight at the top: bedtime is the moon handle, waking is the sun, and the
 * indigo arc between them is the night being recorded. Dragging either handle moves its clock; the
 * duration in the middle is the answer being arrived at.
 *
 * The dial is the gesture, not the only door, and a caller has to put the other one beside it: the
 * two [ClockTimePickerCard] wheels under the dial, as the sleep card does, for anyone who knows the
 * times to the minute. A drag snaps to five minutes, a screen reader's step is fifteen, and the dial
 * takes no keyboard focus, so alone it cannot set an exact minute. [words] and the two icons are for
 * a span that is not sleep; left out, the dial is the sleep dial.
 */
@Composable
internal fun SleepDial(
    startMinute: Int,
    endMinute: Int,
    onStart: (Int) -> Unit,
    onEnd: (Int) -> Unit,
    words: DialWords? = null,
    startIcon: ImageVector = SleepIcon,
    endIcon: ImageVector = Icons.Outlined.WbSunny,
) {
    val start by rememberUpdatedState(startMinute)
    val end by rememberUpdatedState(endMinute)
    // True while the moon handle is held, false for the sun, null between drags.
    var holdingStart by remember { mutableStateOf<Boolean?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // The belt outside the ring is as deep as a handle's outer half, a gap, and a label: measured
    // from the label rather than fixed, because the labels grow with the font size and a fixed
    // belt would let them run into the handles — already by a hair at the default size.
    val labelHeight = with(LocalDensity.current) {
        measurer.measure(DIAL_LABELS.first().second, labelStyle).size.height.toDp()
    }
    val margin = DIAL_HANDLE / 2 + DIAL_LABEL_GAP + labelHeight
    val minutes = sleepDurationMinutes(startMinute, endMinute)
    val rangeText = stringResource(
        R.string.sleep_clock_summary,
        clockText(startMinute),
        clockText(endMinute),
    )
    val startLater = words?.startLater ?: stringResource(R.string.sleep_dial_start_later)
    val startEarlier = words?.startEarlier ?: stringResource(R.string.sleep_dial_start_earlier)
    val endLater = words?.endLater ?: stringResource(R.string.sleep_dial_end_later)
    val endEarlier = words?.endEarlier ?: stringResource(R.string.sleep_dial_end_earlier)

    BoxWithConstraints(
        // sizeIn, not size: on a narrow phone the 320dp the dial asks for does not fit, and the
        // canvas below would draw its ring at whatever it actually got while the handles kept sitting
        // at the radius the constant named — off the track, out over the hour labels.
        Modifier.sizeIn(maxWidth = DIAL_SIZE, maxHeight = DIAL_SIZE)
            .aspectRatio(1f)
            .semantics {
                contentDescription = rangeText
                // The dial is one gesture, and a drag is the gesture a screen reader cannot pass
                // through: without these four the two clocks can be heard and never moved. The
                // wheels a caller puts under the dial are the exact door for anyone who knows the
                // minute.
                customActions = listOf(
                    CustomAccessibilityAction(startLater) { nudgeHandle(startMinute, 1, onStart) },
                    CustomAccessibilityAction(startEarlier) { nudgeHandle(startMinute, -1, onStart) },
                    CustomAccessibilityAction(endLater) { nudgeHandle(endMinute, 1, onEnd) },
                    CustomAccessibilityAction(endEarlier) { nudgeHandle(endMinute, -1, onEnd) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Never smaller than a handle, however large the labels grow on a narrow card.
        val ringRadius = (minOf(maxWidth, maxHeight) / 2 - margin).coerceAtLeast(DIAL_HANDLE)
        // The drawing says nothing on its own account: the arc, the two handles over it and the
        // duration in the middle are one instrument, and the box above describes and drives all
        // of it. A description here would be the same night read out a second time.
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        holdingStart = grabbedHandle(
                            pos = pos,
                            size = size,
                            ringPx = ringRadius.toPx(),
                            grabPx = DIAL_GRAB.toPx(),
                            startMinute = start,
                            endMinute = end,
                        )
                    },
                    onDragEnd = { holdingStart = null },
                    onDragCancel = { holdingStart = null },
                ) { change, _ ->
                    if (holdingStart == null) return@detectDragGestures
                    change.consume()
                    val minute = minuteOfAngle(
                        dx = change.position.x - size.width / 2f,
                        dy = change.position.y - size.height / 2f,
                    )
                    when (holdingStart) {
                        true -> onStart(minute)
                        false -> onEnd(minute)
                        null -> Unit
                    }
                }
            },
        ) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val ringR = ringRadius.toPx()
            val topLeft = Offset(centre.x - ringR, centre.y - ringR)
            val arcSize = Size(ringR * 2f, ringR * 2f)
            val stroke = Stroke(DIAL_STROKE.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = accent.copy(alpha = 0.15f),
                startAngle = 90f + DIAL_GAP_DEG / 2f,
                sweepAngle = 360f - DIAL_GAP_DEG,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            val sweep = (end - start + MINUTES_PER_DAY) % MINUTES_PER_DAY / MINUTES_PER_DAY.toFloat() * 360f
            if (sweep > 0f) {
                drawArc(
                    color = accent,
                    startAngle = start / MINUTES_PER_DAY.toFloat() * 360f - 90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
            DIAL_LABELS.forEach { (hour, _) ->
                val rad = Math.toRadians(hour * 15.0 - 90.0)
                val inner = ringR + DIAL_STROKE.toPx() * 0.62f
                val outer = inner + DIAL_STROKE.toPx() * 0.34f
                drawLine(
                    color = accent.copy(alpha = 0.35f),
                    start = Offset(
                        centre.x + (cos(rad) * inner).toFloat(),
                        centre.y + (sin(rad) * inner).toFloat(),
                    ),
                    end = Offset(
                        centre.x + (cos(rad) * outer).toFloat(),
                        centre.y + (sin(rad) * outer).toFloat(),
                    ),
                    strokeWidth = DIAL_STROKE.toPx() * 0.12f,
                    cap = StrokeCap.Round,
                )
            }
            DIAL_LABELS.forEach { (hour, text) ->
                val rad = Math.toRadians(hour * 15.0 - 90.0)
                val laid = measurer.measure(text, labelStyle)
                val r = ringR + (DIAL_HANDLE / 2 + DIAL_LABEL_GAP).toPx() + laid.size.height / 2f
                drawText(
                    textLayoutResult = laid,
                    topLeft = Offset(
                        centre.x + (cos(rad) * r).toFloat() - laid.size.width / 2f,
                        centre.y + (sin(rad) * r).toFloat() - laid.size.height / 2f,
                    ),
                )
            }
        }
        DialHandle(startIcon, startMinute, ringRadius)
        DialHandle(endIcon, endMinute, ringRadius)
        // Held inside the ring's inner edge: unconstrained, a long duration in German or any duration
        // at a large font size would run out across the track and under the handles. Held in, it wraps.
        Column(
            Modifier.widthIn(max = ringRadius * 2 - DIAL_STROKE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.measurement_sleep_value, minutes / 60, minutes % 60),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(startIcon, null, tint = accent, modifier = Modifier.size(15.dp))
                Text(
                    words?.caption ?: stringResource(R.string.sleep_duration_label),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private const val MINUTES_PER_DAY = 1_440

/**
 * One accessibility step of a handle. It wraps rather than stopping, because a clock face has no
 * ends: winding a bedtime back past midnight is an ordinary night, not the edge of a scale.
 */
private fun nudgeHandle(minute: Int, direction: Int, onMinute: (Int) -> Unit): Boolean {
    val step = direction * DIAL_STEP_MINUTES
    onMinute((minute + step + MINUTES_PER_DAY) % MINUTES_PER_DAY)
    return true
}

@Composable
private fun BoxScope.DialHandle(icon: ImageVector, minute: Int, radius: Dp) {
    val rad = Math.toRadians(minute / MINUTES_PER_DAY.toDouble() * 360.0 - 90.0)
    Box(
        // Absolute, not offset(): the arc and the touch maths below are canvas coordinates, which
        // an RTL layout direction would mirror the handles away from.
        //
        // The offset is the ring point itself, with nothing subtracted for the handle's own size:
        // align(Center) has already placed the disc so that its centre is the dial's, so the
        // offset moves that centre. absoluteOffset does move a corner, but the corner align()
        // chose is the one that puts the centre where the maths wants it: taking half a handle
        // off here would pull both handles a handle's-width up and to the left, inside the track.
        Modifier.align(Alignment.Center)
            .absoluteOffset(
                x = radius * cos(rad).toFloat(),
                y = radius * sin(rad).toFloat(),
            )
            .size(DIAL_HANDLE)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/** Which handle a touch near the ring means: the closer clock wins, elsewhere means neither. */
private fun grabbedHandle(
    pos: Offset,
    size: IntSize,
    ringPx: Float,
    grabPx: Float,
    startMinute: Int,
    endMinute: Int,
): Boolean? {
    val dx = pos.x - size.width / 2f
    val dy = pos.y - size.height / 2f
    if (abs(hypot(dx, dy) - ringPx) > grabPx) return null
    val touched = minuteOfAngle(dx, dy)
    return circularGap(touched, startMinute) <= circularGap(touched, endMinute)
}

private fun circularGap(a: Int, b: Int): Int {
    val d = (a - b + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return min(d, MINUTES_PER_DAY - d)
}

private fun minuteOfAngle(dx: Float, dy: Float): Int {
    val deg = Math.toDegrees(atan2(dy, dx).toDouble())
    val fromTop = ((deg + 90.0) % 360.0 + 360.0) % 360.0
    val minute = (fromTop / 360.0 * MINUTES_PER_DAY / SNAP_MINUTES).roundToInt() * SNAP_MINUTES
    return minute % MINUTES_PER_DAY
}
