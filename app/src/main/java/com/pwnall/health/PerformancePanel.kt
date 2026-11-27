package com.pwnall.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** How tall the plot is. The rulings, the scale beside it and the bars all stand in this height. */
private val PANEL_CHART_HEIGHT = 90.dp

/** The tallest a bar grows, short of the plot's top so the selected bar's marker still fits. */
private val PANEL_BAR_MAX = 78.dp

/** The narrowest the scale beside the plot is drawn; it widens when its labels need more. */
private val PANEL_SCALE_WIDTH = 42.dp

/** Today's elapsed hours on a raised panel, with a selected interval and bars in the accent. */
@Composable
internal fun PerformancePanel(
    title: String,
    interval: String,
    values: List<Long>,
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    /** What the bars do not show, behind a [HelpDot] after the title; null for a chart with nothing to add. */
    help: String? = null,
) {
    // Held left to right whatever the language: a clock range holds no letter to set a direction,
    // and in an Arabic paragraph its two times swap places.
    val clocks = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr)
    val selectedEnd = if (selectedIndex == values.lastIndex) {
        interval.substringAfter("–")
    } else {
        hourText(selectedIndex + 1)
    }
    val selectedRange = "${labels.getOrElse(selectedIndex) { "00:00" }}–$selectedEnd"
    val top = (ceil((values.maxOrNull() ?: 0).coerceAtLeast(1) / 1000.0) * 1000).toLong()
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            Modifier.weight(1f, fill = false).semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        help?.let { HelpDot(title) { Text(it) } }
                    }
                    Text(interval, color = PwnMuted, style = clocks)
                }
                Column(
                    Modifier.background(PwnBlueSoft, RoundedCornerShape(PwnCorners.large)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(selectedRange, color = PwnMuted, style = clocks)
                    Text(
                        stringResource(
                            R.string.home_peak_hour,
                            values.getOrElse(selectedIndex) { 0 }.formatted(),
                            stringResource(R.string.steps_unit),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                PanelScale(top)
                Column(Modifier.weight(1f)) {
                    PanelBars(values, labels, selectedIndex, top, onSelect)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val indices = listOf(0, values.lastIndex / 2, values.lastIndex).distinct().filter { it >= 0 }
                        indices.forEach { index ->
                            Text(
                                if (index == values.lastIndex) interval.substringAfter("–") else labels[index],
                                color = PwnMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The three figures up the side of the plot.
 *
 * As wide as the widest of them, never narrower than it was drawn: a fixed gutter would wrap a label
 * at a large font scale, or in a language that writes the thousand as a word, and show only the
 * half before the break. The height stays the plot's, so each label keeps to its ruling.
 */
@Composable
private fun PanelScale(top: Long) {
    Column(
        Modifier.height(PANEL_CHART_HEIGHT).widthIn(min = PANEL_SCALE_WIDTH),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf(top, top / 2, 0L).forEach {
            Text(
                if (it >= 1000) {
                    stringResource(R.string.steps_chart_thousands, (it / 1000.0).shown())
                } else {
                    it.formatted()
                },
                color = PwnMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * The rulings, the hours as bars, and the dashed marker over the chosen one.
 *
 * The whole plot takes the tap and picks the hour under the finger. A target per bar would give each
 * a slot as narrow as a finger's edge by the evening; the plot as one target is the full width. Each
 * bar still stands as a choice of its own to a screen reader, which moves bar to bar anyway.
 */
@Composable
private fun PanelBars(
    values: List<Long>,
    labels: List<String>,
    selectedIndex: Int,
    top: Long,
    onSelect: (Int) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = PwnBorder
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val select by rememberUpdatedState(onSelect)
    Box(Modifier.fillMaxWidth().height(PANEL_CHART_HEIGHT)) {
        Canvas(Modifier.matchParentSize()) {
            for (i in 0..2) {
                val y = size.height * i / 2
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            if (values.getOrElse(selectedIndex) { 0 } > 0) {
                // The bars are a Row, which mirrors in a right-to-left layout, and a canvas does
                // not: in Arabic the chosen bar stands in the mirrored slot, so the marker does too.
                val centre = (selectedIndex + 0.5f) / values.size
                val x = size.width * if (layoutDirection == LayoutDirection.Rtl) 1f - centre else centre
                val end = size.height - (PANEL_BAR_MAX * (values[selectedIndex].toFloat() / top)).toPx()
                drawLine(
                    accent.copy(alpha = 0.3f),
                    Offset(x, 0f),
                    Offset(x, end),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                )
            }
        }
        Row(
            Modifier.matchParentSize().selectableGroup().pointerInput(values.size, rtl) {
                detectTapGestures { offset ->
                    if (values.isEmpty() || size.width <= 0) return@detectTapGestures
                    val slot = (offset.x / size.width * values.size).toInt().coerceIn(0, values.lastIndex)
                    select(if (rtl) values.lastIndex - slot else slot)
                }
            },
            verticalAlignment = Alignment.Bottom,
        ) {
            values.forEachIndexed { index, value ->
                val spoken = stringResource(R.string.steps_in_interval, labels[index]) + ": ${value.formatted()}"
                PanelBar(value, top, selectedIndex == index, spoken) { select(index) }
            }
        }
    }
}

/**
 * One hour: the bar in the accent, solid when chosen and worn thin when not, and the chosen one
 * capped with a dot. The quiet bars keep the same opacity as the home card's, which still clears
 * 3:1 against the card, because they are the only drawing of when the day's steps were taken.
 */
@Composable
private fun RowScope.PanelBar(value: Long, top: Long, chosen: Boolean, spoken: String, onSelect: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val markerEdge = PwnSurfaceElevated
    val height = (PANEL_BAR_MAX * (value.toFloat() / top)).coerceAtLeast(3.dp)
    Box(
        Modifier.weight(1f).fillMaxHeight().semantics {
            role = Role.RadioButton
            selected = chosen
            contentDescription = spoken
            onClick {
                onSelect()
                true
            }
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier.width(14.dp).height(height)
                .background(if (chosen) accent else accent.copy(alpha = QUIET_BAR_ALPHA), CircleShape),
        )
        if (chosen && value > 0) {
            Box(
                Modifier.padding(bottom = (height - 5.dp).coerceAtLeast(0.dp))
                    .size(9.dp).background(accent, CircleShape)
                    .border(1.5.dp, markerEdge, CircleShape),
            )
        }
    }
}
