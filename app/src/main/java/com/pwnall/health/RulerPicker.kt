package com.pwnall.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Spacing between two neighbouring ticks. Wide enough to aim at, tight enough to show a range. */
private val TICK_SPACING = 9.dp

/** Every nth tick is drawn tall and carries a label. */
private const val MAJOR_EVERY = 5

/** The strip's own height where a caller does not ask for another. */
private val RULER_HEIGHT = 64.dp

/**
 * A horizontal tick ruler, for a weight or a height.
 *
 * Drag moves the strip under a fixed indicator, which is the part that makes it a ruler rather than
 * a slider: the number under the needle is the value, and the neighbouring numbers stay on screen
 * so the choice has context. A slider would hide them and, over a 40-to-140 range on a phone, spend
 * several units on every pixel.
 *
 * [values] is the whole selectable range in its own units — tenths of a kilogram, whole centimetres,
 * beats per minute — so this composable never has to know what it is measuring. [label] renders one
 * of those units for the scale beneath the ticks and for the screen reader.
 */
@Composable
internal fun RulerPicker(
    values: List<Int>,
    value: Int,
    onValue: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    /**
     * How tall the strip stands and how far apart its ticks are. The defaults are the entry editor's,
     * where the ruler is one field among many; a screen whose whole question
     * is this ruler asks for a bigger one.
     */
    height: Dp = RULER_HEIGHT,
    spacing: Dp = TICK_SPACING,
    /** Which values stand tall; without it, every fifth tick counted from the first of [values]. */
    major: ((Int) -> Boolean)? = null,
    /**
     * What the ruler sets — "Weight" — for a screen reader, which then hears the name and the value
     * as the control's state. Without it only the value is spoken, for a caller whose own label is
     * read just before the ruler.
     */
    name: String? = null,
) {
    if (values.isEmpty()) return
    val density = LocalDensity.current
    val spacingPx = with(density) { spacing.toPx() }
    val index = values.indexOf(value).takeIf { it >= 0 } ?: (values.size / 2)
    val motion = rememberRulerMotion(index, values.lastIndex) { onValue(values[it]) }
    // The graduations are drawn in onSurfaceVariant: on the PwnCard these rulers usually sit in it
    // is 4.90:1 in daylight and 6.02:1 by night, and the 0.8-alpha minor ticks composite to 3.33:1
    // and 4.44:1, past the 3:1 a non-text graphic has to hold. The outline role would leave the minor
    // ticks under that line in daylight.
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val increaseLabel = stringResource(R.string.ruler_increase)
    val decreaseLabel = stringResource(R.string.ruler_decrease)
    // One road for every way of setting a value without dragging: the screen reader's actions and
    // the keyboard's arrows. The strip stops coasting and lands on the value asked for.
    val jump: (Int) -> Unit = {
        motion.stop()
        motion.fling(0f, false)
        onValue(it)
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth()
                .height(height)
                // A drag that begins near either end of the strip is inside the system's back
                // gesture zone on a page that leaves only its own margin at the edge, and would
                // go back instead of moving the ruler. The strip is far shorter than the
                // exclusion the system allows each edge.
                .systemGestureExclusion()
                .rulerSemantics(values, index, label, name, jump, increaseLabel, decreaseLabel)
                .rulerKeys(values, index, Orientation.Horizontal, jump)
                .rulerDraggable(motion, spacingPx, Orientation.Horizontal),
        ) {
            Canvas(Modifier.fillMaxWidth().height(height).clipToBounds()) {
                val centre = size.width / 2f
                val visible = (size.width / spacingPx).toInt() / 2 + 2
                for (offset in -visible..visible) {
                    val at = index + offset
                    if (at !in values.indices) continue
                    val x = centre + (at - motion.position) * spacingPx
                    val tall = major?.invoke(values[at]) ?: (at % MAJOR_EVERY == 0)
                    val tickHeight = if (tall) size.height * 0.42f else size.height * 0.24f
                    drawLine(
                        color = if (tall) tick else tick.copy(alpha = 0.8f),
                        start = Offset(x, size.height / 2f - tickHeight / 2f),
                        end = Offset(x, size.height / 2f + tickHeight / 2f),
                        strokeWidth = if (tall) 2.dp.toPx() else 1.dp.toPx(),
                    )
                }
                drawLine(
                    color = accent,
                    start = Offset(centre, size.height * 0.16f),
                    end = Offset(centre, size.height * 0.84f),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }
        // The scale under the ticks: the value at the needle in the middle, its neighbours around
        // it, so a drag has somewhere to aim rather than being a blind nudge. Two major intervals
        // apart: deriving the step from the size of the whole range instead would spread them over the
        // entire scale — 36 kg to 99 kg under a ruler showing a couple of kilos either side.
        val step = MAJOR_EVERY * 2
        // Past either end of the range there is nothing to label. Clamping onto the last value
        // instead would print it two or three times across the width, which reads as a rendering fault.
        val slots = listOf(-2, -1, 0, 1, 2).filter { index + it * step in values.indices }
        // Each label is laid under its own tick rather than spread evenly over the width. Spread,
        // they would match the ticks only at the default spacing: on the work ruler's wider one, "16 h"
        // would stand under the 9 h tick while the 16 h tick was far off the strip. Placed absolutely,
        // as the canvas draws its ticks: the larger values lie to the right in every language.
        Layout(
            content = {
                slots.forEach { slot ->
                    Text(
                        label(values[index + slot * step]),
                        style = if (height > RULER_HEIGHT) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.labelSmall
                        },
                        fontWeight = if (slot == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (slot == 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) { measurables, constraints ->
            val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labels.sumOf { it.width }
            layout(width, labels.maxOfOrNull { it.height } ?: 0) {
                labels.forEachIndexed { i, laid ->
                    rulerLabelLeft(width, laid.width, (slots[i] * step + index - motion.position) * spacingPx)?.let {
                        laid.place(it, 0)
                    }
                }
            }
        }
    }
}

/**
 * Where a scale label's left edge goes on a row [rowWidth] wide whose middle is the needle: centred
 * under its tick, [tickOffset] from the needle, and nudged inward so the whole number stays on the
 * row. A tick just off the strip keeps its label at the edge, where the scale plainly runs on towards
 * it — at the default spacing that is the outer pair on a phone. Null when the tick lies further
 * past the edge than the label is wide: pushed in that far, the label would stand under another
 * value's tick, the way "16 h" would stand under the work ruler's 9 h.
 */
internal fun rulerLabelLeft(rowWidth: Int, labelWidth: Int, tickOffset: Float): Int? {
    val centre = rowWidth / 2f
    if (abs(tickOffset) > centre + labelWidth) return null
    return (centre + tickOffset - labelWidth / 2f).roundToInt().coerceIn(0, (rowWidth - labelWidth).coerceAtLeast(0))
}

/**
 * What both rulers publish to a screen reader. A drag is not a gesture a screen reader can pass
 * through, so without progress semantics and [setProgress] the value can be heard but never
 * changed: the position on the strip is the whole control. The range is in indices rather than in
 * the values themselves, because the values need not be evenly spaced.
 *
 * The value itself is the state. Left to the range alone, the screen reader would describe it as a
 * percentage of the index range, which means nothing beside a weight or a height. [name], where the
 * caller gives one, is the description, so the value is never read twice.
 *
 * The two custom actions are the part a person actually reaches: [setProgress] asks for a position
 * on a scale nobody can see, while "increase" and "decrease" step the ruler one value at a time
 * from TalkBack's own actions menu.
 */
private fun Modifier.rulerSemantics(
    values: List<Int>,
    index: Int,
    label: (Int) -> String,
    name: String?,
    onValue: (Int) -> Unit,
    increaseLabel: String,
    decreaseLabel: String,
) = semantics {
    name?.let { contentDescription = it }
    stateDescription = label(values[index])
    progressBarRangeInfo = ProgressBarRangeInfo(
        current = index.toFloat(),
        range = 0f..values.lastIndex.toFloat(),
        steps = (values.size - 2).coerceAtLeast(0),
    )
    setProgress { target ->
        onValue(values[target.roundToInt().coerceIn(values.indices)])
        true
    }
    customActions = listOf(
        CustomAccessibilityAction(increaseLabel) { stepRuler(values, index, 1, onValue) },
        CustomAccessibilityAction(decreaseLabel) { stepRuler(values, index, -1, onValue) },
    )
}

/**
 * The rulers under a keyboard or a D-pad: Tab reaches them, and the arrows along the ruler's own
 * axis step one value at a time — right or up for the larger value, as on any slider. At either end
 * the arrow is not taken, so a D-pad moves on to the next control rather than sticking; mid-range it
 * is, so the ruler keeps focus while it is being set. A ring in the accent says where focus is,
 * since nothing else about the strip changes when it arrives.
 */
@Composable
private fun Modifier.rulerKeys(
    values: List<Int>,
    index: Int,
    orientation: Orientation,
    onValue: (Int) -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring = MaterialTheme.colorScheme.primary
    val (larger, smaller) = if (orientation == Orientation.Horizontal) {
        Key.DirectionRight to Key.DirectionLeft
    } else {
        Key.DirectionUp to Key.DirectionDown
    }
    return this
        .then(if (focused) Modifier.border(RULER_FOCUS_RING, ring, RoundedCornerShape(PwnCorners.small)) else Modifier)
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                larger -> stepRuler(values, index, 1, onValue)
                smaller -> stepRuler(values, index, -1, onValue)
                else -> false
            }
        }
        .focusable(interactionSource = source)
}

/** The focus ring's width: Material's focus indicator. */
private val RULER_FOCUS_RING = 2.dp

/**
 * One value along the strip. False at either end, which is how an accessibility action reports that
 * it did nothing — coercing onto the last index instead would announce a change that never happened.
 */
internal fun stepRuler(values: List<Int>, index: Int, step: Int, onValue: (Int) -> Unit): Boolean {
    val target = index + step
    if (target !in values.indices) return false
    onValue(values[target])
    return true
}

/** One value's row on the vertical ruler. Five rows fit the window of neighbours. */
private val ROW_SPACING = 52.dp

/** Cosmetic subdivisions drawn between two neighbouring values. */
private const val SUBTICKS = 5

/**
 * The vertical sibling of [RulerPicker]: a tick column in the
 * middle, every value labelled beside it, and the chosen one carried on an accent line that runs
 * the full width and ends in an arrowhead.
 *
 * Vertical because height is vertical: the drag reads as sliding a measuring rod, larger values
 * arriving from below the way they do on a wall chart.
 */
@Composable
internal fun VerticalRulerPicker(
    values: List<Int>,
    value: Int,
    onValue: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    /** What the ruler sets, for a screen reader; see [RulerPicker]. */
    name: String? = null,
) {
    if (values.isEmpty()) return
    val density = LocalDensity.current
    val spacingPx = with(density) { ROW_SPACING.toPx() }
    val index = values.indexOf(value).takeIf { it >= 0 } ?: (values.size / 2)
    val motion = rememberRulerMotion(index, values.lastIndex) { onValue(values[it]) }
    val measurer = rememberTextMeasurer()
    // Same ink and the same reason as the horizontal ruler.
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val plainStyle = MaterialTheme.typography.titleMedium.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val chosenStyle = MaterialTheme.typography.headlineSmall.copy(
        color = accent,
        fontWeight = FontWeight.Bold,
    )
    val increaseLabel = stringResource(R.string.ruler_increase)
    val decreaseLabel = stringResource(R.string.ruler_decrease)
    val jump: (Int) -> Unit = {
        motion.stop()
        motion.fling(0f, false)
        onValue(it)
    }

    Canvas(
        modifier.fillMaxWidth()
            .height(ROW_SPACING * 5)
            .clipToBounds()
            .rulerSemantics(values, index, label, name, jump, increaseLabel, decreaseLabel)
            .rulerKeys(values, index, Orientation.Vertical, jump)
            .rulerDraggable(motion, spacingPx, Orientation.Vertical),
    ) {
        // Mirrored in a right-to-left language, where the horizontal ruler is not: its values run
        // across the strip, larger to the right in every language, while here they run down it, and
        // the side the labels and the arrow stand on is only the page's layout, which then reads from
        // the right. Text is placed, not flipped: [across] puts what starts at [left] and is [extent]
        // wide in a left-to-right layout the same distance from the other edge.
        val rtl = layoutDirection == LayoutDirection.Rtl
        fun across(left: Float, extent: Float = 0f) = if (rtl) size.width - left - extent else left
        val centreY = size.height / 2f
        val tickX = across(size.width * 0.40f)
        val labelX = size.width * 0.47f
        val visible = (size.height / spacingPx).toInt() / 2 + 1
        for (offset in -visible..visible) {
            val at = index + offset
            if (at !in values.indices) continue
            val y = centreY + (at - motion.position) * spacingPx
            if (offset != 0) {
                drawLine(
                    color = tick,
                    start = Offset(tickX - 8.dp.toPx(), y),
                    end = Offset(tickX + 8.dp.toPx(), y),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            if (at + 1 in values.indices) {
                for (sub in 1 until SUBTICKS) {
                    val subY = y + spacingPx * sub / SUBTICKS
                    drawLine(
                        color = tick.copy(alpha = 0.8f),
                        start = Offset(tickX - 4.dp.toPx(), subY),
                        end = Offset(tickX + 4.dp.toPx(), subY),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            if (offset != 0) {
                val laid = measurer.measure(label(values[at]), plainStyle)
                drawText(laid, topLeft = Offset(across(labelX, laid.size.width.toFloat()), y - laid.size.height / 2f))
            }
        }
        // The chosen value: its label on the accent line, the line broken around the text, and an
        // arrowhead closing the end the labels face.
        val chosen = measurer.measure(label(values[index]), chosenStyle)
        val gap = 10.dp.toPx()
        drawLine(accent, Offset(across(0f), centreY), Offset(across(labelX - gap), centreY), 2.dp.toPx())
        drawLine(
            color = accent,
            start = Offset(across(labelX + chosen.size.width + gap), centreY),
            end = Offset(across(size.width), centreY),
            strokeWidth = 2.dp.toPx(),
        )
        drawText(
            chosen,
            topLeft = Offset(across(labelX, chosen.size.width.toFloat()), centreY - chosen.size.height / 2f),
        )
        val arrow = Path().apply {
            moveTo(across(size.width - 12.dp.toPx()), centreY)
            lineTo(across(size.width), centreY - 7.dp.toPx())
            lineTo(across(size.width), centreY + 7.dp.toPx())
            close()
        }
        drawPath(arrow, accent)
    }
}
