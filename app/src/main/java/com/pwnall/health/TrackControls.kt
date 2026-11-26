package com.pwnall.health

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp

/**
 * Everything the track screen lays over its map, clear of the status and navigation bars: the way
 * back and the saved routes in the top corner, the GPS signal in the other, and at the foot the
 * camera beside the recording control. The saved routes are there only while no walk is open: while
 * one records or stands paused the map is that walk's, and nothing else stands over it.
 *
 * [state] is null until the database has said whether a walk is open, and the recording control is
 * not drawn until it has — see [RecordControl]. The camera is drawn from the first frame, greyed
 * out until a walk records or stands paused, since a photograph belongs to a walk. [imperial] is the
 * unit the pill speaks the accuracy in. Stop asks before it calls [onFinish]; the others act at once.
 */
@Composable
internal fun TrackOverlays(
    state: RecordState?,
    signal: GpsSignal,
    imperial: Boolean,
    startEnabled: Boolean,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onSignal: () -> Unit,
    onPhoto: () -> Unit,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Saved, so the question is still asked after the phone turns; dropped once the walk has ended
    // by another road — the notification, the widget — or it would come back with the next walk.
    var finishing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) { if (state == RecordState.IDLE) finishing = false }
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(OVERLAY_EDGE)) {
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(OVERLAY_GAP)) {
            OverlayDisc(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), onBack)
            if (state ==
                RecordState.IDLE
            ) {
                OverlayDisc(Icons.Outlined.History, stringResource(R.string.track_history), onHistory)
            }
        }
        SignalPill(signal, imperial, onSignal, Modifier.align(Alignment.TopEnd))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalkPhotoButton(onPhoto, enabled = state == RecordState.RECORDING || state == RecordState.PAUSED)
            Spacer(Modifier.width(RECORD_BESIDE))
            // A wide pill on a phone, and no wider than a button is ever drawn on a tablet: it keeps
            // to the end side, where it shrinks to once a walk records.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                state?.let {
                    RecordControl(
                        state = it,
                        startEnabled = startEnabled,
                        onStart = onStart,
                        onStop = { finishing = true },
                        onPause = onPause,
                        onResume = onResume,
                        modifier = Modifier.widthIn(max = PwnDimensions.maxButtonWidth).fillMaxWidth(),
                    )
                }
            }
        }
    }
    if (finishing && state != RecordState.IDLE) {
        FinishDialog(
            onKeep = { finishing = false },
            onFinish = {
                finishing = false
                onFinish()
            },
        )
    }
}

/** Where a walk stands, as the recording control draws it. */
internal enum class RecordState { IDLE, RECORDING, PAUSED }

/** No open route is idle; an open one is paused while it carries the moment it was paused. */
internal fun recordState(open: TrackEntity?): RecordState = when {
    open == null -> RecordState.IDLE
    open.pausedAt != null -> RecordState.PAUSED
    else -> RecordState.RECORDING
}

/**
 * A round button standing on the map: the panel's raised surface with a close shadow under it, so it
 * reads as a control on either theme's ground, and the resting slate for its glyph. Greyed out, it
 * keeps its ground — a see-through disc would be lost on the map.
 */
@Composable
internal fun OverlayDisc(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = DISC_SIZE,
    enabled: Boolean = true,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(size).dropShadow(CircleShape, BAR_SHADOW),
        enabled = enabled,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = PwnSurfaceElevated,
            contentColor = PwnMuted,
            disabledContainerColor = PwnSurfaceElevated,
            disabledContentColor = PwnMuted.copy(alpha = DISABLED_ALPHA),
        ),
    ) { Icon(icon, label) }
}

/**
 * The GPS signal as bars, and beside them the mark that opens what the screen knows right now — one
 * button, the whole pill, which a screen reader hears as the signal in words and where the tap goes.
 * The mark turns the error colour while location is off or not granted, which is when the bars
 * cannot say why they are empty.
 */
@Composable
private fun SignalPill(signal: GpsSignal, imperial: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val words = signalWords(signal)
    val accuracy = signalAccuracy(signal, imperial)
    val spoken = if (accuracy == null) {
        stringResource(R.string.track_signal_pill, words)
    } else {
        stringResource(R.string.track_signal_pill_accuracy, words, accuracy)
    }
    Row(
        modifier
            .heightIn(min = DISC_SIZE)
            .dropShadow(CircleShape, BAR_SHADOW)
            .clip(CircleShape)
            .background(PwnSurfaceElevated)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = spoken }
            .padding(horizontal = PILL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalBars(signal.level, Modifier.size(BARS_WIDTH, BARS_HEIGHT))
        Box(
            Modifier.padding(horizontal = PILL_DIVIDER_GAP)
                .size(width = 1.dp, height = PILL_DIVIDER_HEIGHT)
                .background(PwnBorder),
        )
        Icon(
            Icons.Outlined.PriorityHigh,
            contentDescription = null,
            modifier = Modifier.size(PILL_MARK),
            tint = if (signal.blocked) MaterialTheme.colorScheme.error else PwnMuted,
        )
    }
}

/**
 * Three bars rising left to right, as a phone draws its signal: the lit ones in green for three,
 * orange for two and red for one, and the rest outlined in the resting slate. None lit while the
 * phone is searching. The count says it as well as the colour does, for an eye that cannot tell
 * the three apart.
 */
@Composable
internal fun SignalBars(level: Int, modifier: Modifier = Modifier) {
    val lit = when (level) {
        SIGNAL_BARS -> PwnGreen
        SIGNAL_BARS - 1 -> PwnWarn
        else -> MaterialTheme.colorScheme.error
    }
    val edge = PwnMuted
    Canvas(modifier) {
        val gap = size.width * BAR_GAP_SHARE
        val barWidth = (size.width - gap * (SIGNAL_BARS - 1)) / SIGNAL_BARS
        val stroke = BAR_STROKE.toPx()
        val corner = CornerRadius(stroke, stroke)
        repeat(SIGNAL_BARS) { index ->
            val height = size.height * (index + 1) / SIGNAL_BARS
            val left = index * (barWidth + gap)
            if (index < level) {
                drawRoundRect(lit, Offset(left, size.height - height), Size(barWidth, height), corner)
            } else {
                // Inset by half the stroke, so the outline's outer edge stands where a lit bar's would.
                drawRoundRect(
                    edge,
                    Offset(left + stroke / 2, size.height - height + stroke / 2),
                    Size(barWidth - stroke, height - stroke),
                    corner,
                    style = Stroke(stroke),
                )
            }
        }
    }
}

/**
 * Start, and once a walk records, Stop and Pause. Idle it is one wide pill; a start narrows it
 * towards the end side and parts it into two discs, Stop and — outermost, under the thumb — Pause,
 * which reads Resume while the walk stands paused. The change plays at every start and backwards at
 * every end, on the spatial spring. It is first composed with the walk's own state, which
 * [animateFloatAsState] starts from without playing, so a screen opened on a walk already recording
 * does not act out its start again.
 */
@Composable
private fun RecordControl(
    state: RecordState,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = state == RecordState.IDLE
    // The two discs keep the glyph of the walk they last stood for while they close back into the
    // pill. A plain holder rather than state, as the action flash keeps its word: writing state
    // here would be a write during composition, and the state changing recomposes this anyway.
    val lastActive = remember { arrayOf(RecordState.RECORDING) }
    if (!idle) lastActive[0] = state
    val paused = lastActive[0] == RecordState.PAUSED
    val split by animateFloatAsState(if (idle) 0f else 1f, PwnMotion.spatial(), label = "record control")
    // The narrowing takes the spring's quick first stretch and the parting its settling tail, the
    // overshoot included: a gap that opens a little past its width and comes back.
    val narrowed = (split / NARROW_SHARE).coerceIn(0f, 1f)
    val parted = ((split - NARROW_SHARE) / (1f - NARROW_SHARE)).coerceAtLeast(0f)
    val shade = parted.coerceAtMost(1f)
    val scheme = MaterialTheme.colorScheme
    val pill = if (idle && !startEnabled) PwnSurfaceElevated else scheme.primary
    BoxWithConstraints(modifier.height(RECORD_SIZE), contentAlignment = Alignment.CenterEnd) {
        val outer = RECORD_SIZE / 2
        val inner = outer * shade
        val stopShape = RoundedCornerShape(outer, inner, inner, outer)
        val pauseShape = RoundedCornerShape(inner, outer, outer, inner)
        val gap = Arrangement.spacedBy(RECORD_GAP * parted)
        Box(Modifier.width(lerp(maxWidth, RECORD_SIZE * 2 + RECORD_GAP, narrowed)).fillMaxHeight()) {
            // Both shadows are laid before either half: a half's own shadow would otherwise fall
            // across the other where the two still touch, a dark seam down the middle of Start.
            Row(Modifier.matchParentSize(), horizontalArrangement = gap) {
                Spacer(Modifier.weight(1f).fillMaxHeight().dropShadow(stopShape, BAR_SHADOW))
                Spacer(Modifier.weight(1f).fillMaxHeight().dropShadow(pauseShape, BAR_SHADOW))
            }
            // The halves turn into buttons only once they begin to part. The walk is open from the
            // frame Room says so, but the control reads as Start until the narrowing is over, and a
            // second tap on Start must not pause the walk the first one began, or ask to finish it.
            val live = !idle && parted > 0f
            Row(Modifier.matchParentSize(), horizontalArrangement = gap) {
                RecordPart(
                    shape = stopShape,
                    color = lerp(pill, PwnSurfaceElevated, shade),
                    icon = Icons.Filled.Stop,
                    tint = PwnMuted,
                    shown = parted,
                    label = stringResource(R.string.track_stop),
                    onClick = onStop.takeIf { live },
                )
                RecordPart(
                    shape = pauseShape,
                    color = pill,
                    icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    tint = scheme.onPrimary,
                    shown = parted,
                    label = stringResource(if (paused) R.string.track_resume else R.string.track_pause),
                    onClick = (if (paused) onResume else onPause).takeIf { live },
                )
            }
            if (idle || narrowed < 1f) {
                StartLabel(
                    idle = idle,
                    enabled = startEnabled,
                    fade = (1f - narrowed * 2).coerceIn(0f, 1f),
                    onStart = onStart,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * One half of the control: the pill's start or end while idle, a disc once parted. It is a button
 * only once a walk is open and the halves have begun to part, and [onClick] is null until then:
 * idle, the pill's own words are the one button, and narrowing, the control has none. Its glyph
 * comes in with the parting, [shown] of the way. The button carries its [label] itself rather than
 * through the glyph, so it is named from its first frame as a button, however faint the glyph is.
 */
@Composable
private fun RowScope.RecordPart(
    shape: Shape,
    color: Color,
    icon: ImageVector,
    tint: Color,
    shown: Float,
    label: String,
    onClick: (() -> Unit)?,
) {
    Box(
        Modifier.weight(1f)
            .fillMaxHeight()
            .clip(shape)
            .background(color)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (shown > 0f) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.graphicsLayer { alpha = shown.coerceAtMost(1f) },
                tint = tint,
            )
        }
    }
}

/**
 * "Start recording" across the whole pill, and while idle the pill's one button. It fades out in the
 * first half of the narrowing, and is no button and says nothing once a walk is open.
 */
@Composable
private fun StartLabel(
    idle: Boolean,
    enabled: Boolean,
    fade: Float,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(CircleShape)
            .then(
                if (idle) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onStart)
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.track_start),
            Modifier.padding(horizontal = RECORD_LABEL_PADDING).graphicsLayer { alpha = fade },
            color = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = DISABLED_ALPHA),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Smaller before cut: a long word at a large font scale still says what the pill does.
            autoSize = TextAutoSize.StepBased(
                minFontSize = 11.sp,
                maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
                stepSize = 0.5.sp,
            ),
        )
    }
}

/** Stop's question: a walk ended by a slip of the thumb cannot be taken up again. */
@Composable
private fun FinishDialog(onKeep: () -> Unit, onFinish: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.track_finish_title)) },
        text = { Text(stringResource(R.string.track_finish_body)) },
        confirmButton = { TextButton(onClick = onFinish) { Text(stringResource(R.string.track_finish)) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.track_keep_recording)) } },
    )
}

/** How far the overlays stand in from the safe edges of the window. */
internal val OVERLAY_EDGE = 16.dp

/** Between the two discs in the top corner. */
private val OVERLAY_GAP = 8.dp

/** The back and history discs and the signal pill's height: the minimum a finger is owed. */
internal val DISC_SIZE = PwnDimensions.minimumTarget

/** The camera and the recording control's height, and each disc the control parts into. */
internal val RECORD_SIZE = PwnDimensions.mediumButtonHeight

/** Between the camera and the recording control. */
private val RECORD_BESIDE = 12.dp

/** Between Stop and Pause once parted. */
private val RECORD_GAP = PwnDimensions.buttonGap

/** How much of the change is the narrowing; the rest is the parting. */
private const val NARROW_SHARE = 0.6f

private val RECORD_LABEL_PADDING = 16.dp

/** Material's opacity for a disabled control's content. */
private const val DISABLED_ALPHA = 0.38f

private val PILL_PADDING = 14.dp
private val PILL_DIVIDER_GAP = 10.dp
private val PILL_DIVIDER_HEIGHT = 20.dp
private val PILL_MARK = 18.dp
private val BARS_WIDTH = 20.dp
private val BARS_HEIGHT = 16.dp
private val BAR_STROKE = 1.5.dp

/** The gap between two bars, as a share of the three bars' width. */
private const val BAR_GAP_SHARE = 0.15f
