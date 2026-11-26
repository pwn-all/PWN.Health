package com.pwnall.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val THEME_WHEEL_INNER = 0.55f

/** Angle is hue; distance across the ring is saturation. The middle is never a colour target. */
internal fun themeWheelSelection(x: Float, y: Float, radius: Float): Pair<Float, Float> {
    require(radius > 0f)
    val hue = ((atan2(y, x) * 180f / PI.toFloat()) + 360f) % 360f
    val saturation = ((hypot(x, y) / radius - THEME_WHEEL_INNER) / (1f - THEME_WHEEL_INNER)).coerceIn(0f, 1f)
    return hue to saturation
}

@Composable
internal fun ThemeColorWheel(hue: Float, saturation: Float, brightness: Float, onColor: (Float, Float) -> Unit) {
    val change by rememberUpdatedState(onColor)
    val description = stringResource(R.string.theme_wheel)
    val selection = stringResource(R.string.theme_wheel_value, hue.roundToInt(), (saturation * 100f).roundToInt())
    val nextHue = stringResource(R.string.theme_next_hue)
    val previousHue = stringResource(R.string.theme_previous_hue)
    val moreSaturation = stringResource(R.string.theme_more_saturation)
    val lessSaturation = stringResource(R.string.theme_less_saturation)
    // The arrows move the marker, but nothing else about the wheel says it is the control they are
    // moving rather than the slider or the hex field: a ring in the accent around the wheel does.
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .semantics {
                contentDescription = description
                stateDescription = selection
                customActions = listOf(
                    CustomAccessibilityAction(nextHue) {
                        change((hue + 15f) % 360f, saturation)
                        true
                    },
                    CustomAccessibilityAction(previousHue) {
                        change((hue + 345f) % 360f, saturation)
                        true
                    },
                    CustomAccessibilityAction(moreSaturation) {
                        change(hue, (saturation + 0.1f).coerceAtMost(1f))
                        true
                    },
                    CustomAccessibilityAction(lessSaturation) {
                        change(hue, (saturation - 0.1f).coerceAtLeast(0f))
                        true
                    },
                )
            }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key) {
                    Key.DirectionRight -> change((hue + 5f) % 360f, saturation)
                    Key.DirectionLeft -> change((hue + 355f) % 360f, saturation)
                    Key.DirectionUp -> change(hue, (saturation + 0.05f).coerceAtMost(1f))
                    Key.DirectionDown -> change(hue, (saturation - 0.05f).coerceAtLeast(0f))
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(interactionSource = source)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.width.coerceAtMost(size.height) / 2f - 8.dp.toPx()
                    val relative = down.position - center
                    if (radius <= 0f || relative.getDistance() !in radius * THEME_WHEEL_INNER..radius) {
                        return@awaitEachGesture
                    }
                    val first = themeWheelSelection(relative.x, relative.y, radius)
                    change(first.first, first.second)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.pressed) {
                            val point = pointer.position - center
                            val color = themeWheelSelection(point.x, point.y, radius)
                            change(color.first, color.second)
                        }
                        pointer.consume()
                    } while (pointer.pressed)
                }
            },
    ) {
        val radius = size.minDimension / 2f - 8.dp.toPx()
        val inner = radius * THEME_WHEEL_INNER
        val width = radius - inner
        val ringRadius = (radius + inner) / 2f
        val white = Color.hsv(0f, 0f, brightness)
        drawCircle(
            brush = Brush.sweepGradient((0..6).map { Color.hsv(it * 60f, 1f, brightness) }, center),
            radius = ringRadius,
            style = Stroke(width),
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to white,
                THEME_WHEEL_INNER to white,
                1f to white.copy(alpha = 0f),
                center = center,
                radius = radius,
            ),
            radius = ringRadius,
            style = Stroke(width),
        )
        val chosen = Color.hsv(hue, saturation, brightness)
        val squareSide = inner
        drawRect(chosen, center - Offset(squareSide / 2f, squareSide / 2f), Size(squareSide, squareSide))
        drawRect(
            themeContentColor(chosen).copy(alpha = 0.35f),
            center - Offset(squareSide / 2f, squareSide / 2f),
            Size(squareSide, squareSide),
            style = Stroke(1.dp.toPx()),
        )
        val angle = hue * PI.toFloat() / 180f
        val markerRadius = inner + saturation * width
        val marker = center + Offset(cos(angle), sin(angle)) * markerRadius
        drawCircle(Color.Black, 7.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), marker, style = Stroke(1.5f.dp.toPx()))
        // In the margin the wheel keeps around its ring, so it never touches the colours.
        if (focused) drawCircle(ring, radius + 4.dp.toPx(), center, style = Stroke(2.dp.toPx()))
    }
}
