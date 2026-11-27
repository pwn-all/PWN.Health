package com.pwnall.health

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** How tall the plot stands: about a fifth of the screen. */
private val CYCLE_CHART_HEIGHT = 168.dp

/** The sticks stand clear of the curves at both ends rather than framing them. */
private val STICK_INSET = 10.dp

/**
 * The cycle over a window of time, drawn straight onto the violet: a stick at every date the axis
 * names, the curve of what she recorded with its own light spilling under it carried on by what her
 * own past cycles do, and a dashed line at the level the record has been running at.
 *
 * The plot and its axis are one canvas, because a stick and the date under it are one mark. Drawn
 * as two, the sticks would be seven evenly spaced divisions of the width and the dates would fall
 * wherever their own columns are, so on a month the two rows would line up nowhere and the sticks
 * would measure nothing.
 *
 * No card and no axis numbers. The scale is a ladder of four or five words and printing them up the
 * side would eat a third of the plot; what the picture says is said in the legend under it and in
 * full in the spoken description, which is the only place a screen reader can reach it.
 */
@Composable
internal fun CycleChart(chart: CycleChart, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    // The month spelt out, because this one is read aloud. A screen reader given "18 сент." reads
    // the abbreviation as one, and the sentence it sits in ends in a full stop of its own, so the
    // two would come out as "18 сент..". The marks under the plot keep the short form; they are looked at.
    val spokenDate = rememberDayMonthLong()
    val look = PlotLook(
        ink = CycleOnViolet,
        peach = CyclePeach,
        mint = CycleMint,
        gold = CycleEstimate,
        grid = CycleGrid,
        muted = CycleOnVioletMuted,
    )
    val spoken = remember(chart) { spokenCycleChart(resources, chart, spokenDate) }
    val measurer = rememberTextMeasurer()
    val locale = LocalLocale.current.platformLocale
    val style = MaterialTheme.typography.labelMedium
    val labels = remember(chart, locale, style) {
        chart.ticks.associateWith { index -> measurer.measure(chart.tickLabel(index, locale), style) }
    }
    val forecast = remember(chart, locale, style) {
        chart.predictedStart?.let {
            measurer.measure(chart.buckets[it].start.format(monthDay(locale)), style)
        }
    }
    // The column under a finger held on the plot. Null the rest of the time, which is all the time
    // the plot is being looked at rather than asked a question.
    var held by remember(chart) { mutableStateOf<Int?>(null) }
    // What the axis needs to name every mark at a readable spacing. At ordinary text sizes it is
    // far less than the screen and nothing below changes; at 200% it is twice the screen, and a
    // plot squeezed into the screen there drops four dates in seven. It is given the width it needs
    // and scrolls inside itself instead, which is what every other crowded strip in the app does.
    val density = LocalDensity.current
    val needed = remember(labels, forecast, density) {
        val words = labels.values.map { it.size.width } + listOfNotNull(forecast?.size?.width)
        with(density) { (words.size * ((words.maxOrNull() ?: 0) + AXIS_GAP.roundToPx() * 2)).toDp() }
    }
    val heldLabel = remember(chart, held, locale, style) {
        held?.let { measurer.measure(chart.buckets[it].start.format(monthDay(locale)), style) }
    }

    Column(modifier.fillMaxWidth()) {
        if (chart.empty) {
            // One sentence, never an empty frame. A plot with sticks and no curve on it reads as a
            // flat zero, and "you recorded nothing" and "you have none of it" are not the same
            // statement about a body.
            Text(
                stringResource(R.string.cycle_chart_empty),
                Modifier.padding(vertical = 28.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = CycleOnVioletMuted,
            )
            return@Column
        }
        // The plot runs left to right in every language, like the ruler of dates it is drawn on, and
        // so does the strip that scrolls it: laid out right to left, a plot wider than the screen
        // would open scrolled to its far end, with today — where the window starts — out of sight.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val plotWidth = maxOf(maxWidth, needed)
                Box(Modifier.horizontalScroll(remember(chart.window) { ScrollState(0) })) {
                    Canvas(
                        Modifier.width(plotWidth).height(CYCLE_CHART_HEIGHT + AXIS_HEIGHT)
                            .semantics { contentDescription = spoken }
                            .heldDayInput(chart, { held }) { held = it },
                    ) {
                        drawCyclePlot(chart, held, labels, forecast, heldLabel, look)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val kind = held
        if (kind == null) {
            CycleChartLegend(chart)
        } else {
            // While a day is held the key gives way to the answer: what that one day is. Said
            // politely as it changes, with its date, so stepping along the plot from a keyboard is
            // heard as well as seen.
            val answer = stringResource(chart.dayKind(kind))
            val heard = listOf(chart.buckets[kind].start.format(spokenDate), answer)
                .joinToString(stringResource(R.string.list_comma))
            Text(
                answer,
                Modifier.fillMaxWidth().semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = heard
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = CycleOnViolet,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The colours the plot is drawn in, all of them read off the white the hero carries. */
private class PlotLook(
    val ink: Color,
    val peach: Color,
    val mint: Color,
    val gold: Color,
    val grid: Color,
    val muted: Color,
)

/**
 * How a day is asked about: a finger held on the plot, or the arrow keys once the plot has focus.
 *
 * Held rather than tapped: the plot sits inside a page that scrolls, and a finger that meant to
 * scroll must not be answered as a question about a day. From a keyboard the arrows step the held
 * day along the plot — the first one landing on today, where the plot is read from — and leaving the
 * plot lets go of it.
 */
private fun Modifier.heldDayInput(chart: CycleChart, held: () -> Int?, onHeld: (Int?) -> Unit): Modifier =
    onFocusChanged { if (!it.isFocused) onHeld(null) }
        .onKeyEvent { event ->
            val next = heldStep(event)?.let { step -> held()?.plus(step) ?: chart.todayIndex }
            if (next != null && next in chart.buckets.indices) {
                onHeld(next)
                true
            } else {
                false
            }
        }
        .focusable()
        .pointerInput(chart) {
            val columns = chart.buckets.size
            fun at(x: Float) = (x * columns / size.width).toInt().coerceIn(0, columns - 1)
            detectDragGesturesAfterLongPress(
                onDragStart = { onHeld(at(it.x)) },
                onDrag = { change, _ -> onHeld(at(change.position.x)) },
                onDragEnd = { onHeld(null) },
                onDragCancel = { onHeld(null) },
            )
        }

/** Everything on the plot and its axis, back to front. */
private fun DrawScope.drawCyclePlot(
    chart: CycleChart,
    held: Int?,
    labels: Map<Int, TextLayoutResult>,
    forecast: TextLayoutResult?,
    heldLabel: TextLayoutResult?,
    look: PlotLook,
) {
    val plot = Rect(0f, 6.dp.toPx(), size.width, CYCLE_CHART_HEIGHT.toPx() - 6.dp.toPx())
    val slot = plot.width / chart.buckets.size
    fun x(index: Int) = plot.left + slot * (index + 0.5f)

    // Worked out once: a stick is drawn for every date that fits, and for no date that does not, so
    // nothing on the axis is a division of the width rather than a day.
    val marks = axisMarks(chart, labels, forecast, ::x, slot)
    held?.let { drawHeldBand(chart.bandOf(it), slot, plot, look.ink) }
    chart.stickColumns().forEach { drawDayStick(x(it), plot, look.grid) }
    chart.fertile.forEach { drawFertileRibbon(ribbonSpan(it, slot, plot), look.ink) }
    chart.bleeding.forEach { drawBleedingRibbon(ribbonSpan(it, slot, plot), look.peach, filled = true) }
    chart.predicted.forEach { drawBleedingRibbon(ribbonSpan(it, slot, plot), look.peach, filled = false) }
    chart.ovulation?.let { drawOvulationMark(x(it), plot, look.ink) }
    drawRecordedLevel(chart, plot, look.grid)

    // The comparison first and the record over it, so a day that went exactly as it usually does
    // shows the peach as an edge either side of the white rather than vanishing under it.
    chart.series.withIndex()
        .sortedBy { it.value.role == CycleSeriesRole.RECORDED }
        .forEach { (index, series) ->
            drawCycleSeries(
                series = series,
                plot = plot,
                colour = seriesColour(index, series.role, look.ink, look.mint, look.gold),
                x = ::x,
                allEstimate = series.role != CycleSeriesRole.RECORDED,
                leading = index == 0,
                todayIndex = chart.todayIndex,
                dotted = seriesStroke(index, series.role) == SeriesStroke.DOTTED,
            )
        }
    if (held != null && heldLabel != null) {
        drawHeldColumn(x(held), plot, look.ink)
        drawAxisMark(heldMark(held, heldLabel, x(held)), CYCLE_CHART_HEIGHT.toPx(), look.ink, look.muted)
    } else {
        marks.forEach { drawAxisMark(it, CYCLE_CHART_HEIGHT.toPx(), look.ink, look.muted) }
    }
}

/** The arrow a key event steps the held day by: back, forward, or nothing it answers to. */
private fun heldStep(event: KeyEvent): Int? = when {
    event.type != KeyEventType.KeyDown -> null
    event.key == Key.DirectionLeft -> -1
    event.key == Key.DirectionRight -> 1
    else -> null
}

/** Which of the three a line is drawn in: hers, the second thing she notes, the calendar's. */
private fun seriesColour(index: Int, role: CycleSeriesRole, ink: Color, mint: Color, gold: Color): Color = when {
    role == CycleSeriesRole.ESTIMATE -> gold
    index == 0 -> ink
    else -> mint
}

/**
 * And the stroke it is drawn with, which is what actually tells the lines apart: her record solid,
 * the second thing she notes dotted, the calendar's estimate dashed. The two record colours are a
 * white and a near-white, and nobody should have to tell those apart to read the plot.
 */
private enum class SeriesStroke { SOLID, DOTTED, DASHED }

private fun seriesStroke(index: Int, role: CycleSeriesRole): SeriesStroke = when {
    role == CycleSeriesRole.ESTIMATE -> SeriesStroke.DASHED
    index == 0 -> SeriesStroke.SOLID
    else -> SeriesStroke.DOTTED
}

/**
 * The kinds of day, as a ribbon along the foot of the plot rather than a panel behind it.
 *
 * A full-height block strong enough to be read at all would be a second, competing background. On
 * the floor it marks the same days, stays out of the curves' way, and reads as an axis rather than as
 * weather.
 *
 * The three kinds differ in shape, each at full strength: recorded bleeding a solid bar, forecast
 * bleeding the same bar in outline — the app's rule that what was written down and what was worked
 * out differ in shape — and the fertile days a row of dots. As three strengths of two pale colours
 * they would be within a shade of each other, and the forecast would barely show at all.
 */
private val RIBBON = 6.dp
private val RIBBON_OUTLINE = 1.5.dp

/** The record's curve, its forecast half, and the dot on it. */
private val RECORDED_STROKE = 3.dp
private val ESTIMATE_STROKE = 2.5.dp
private val DOT_RADIUS = 5.dp

private fun DrawScope.drawDayStick(x: Float, plot: Rect, colour: Color) {
    val inset = STICK_INSET.toPx()
    drawLine(
        color = colour,
        start = Offset(x, plot.top + inset),
        end = Offset(x, plot.bottom - inset),
        strokeWidth = 1.5.dp.toPx(),
    )
}

/** Where a run of columns lies on the floor of the plot: a strip one ribbon tall. */
private fun DrawScope.ribbonSpan(run: IntRange, slot: Float, plot: Rect): Rect {
    val thickness = RIBBON.toPx()
    val left = plot.left + slot * run.first + 1f
    return Rect(left, plot.bottom - thickness, left + slot * (run.last - run.first + 1) - 2f, plot.bottom)
}

/** Bleeding: a solid bar where she recorded it, the same bar in outline where it is forecast. */
private fun DrawScope.drawBleedingRibbon(span: Rect, colour: Color, filled: Boolean) {
    if (filled) {
        drawRoundRect(colour, span.topLeft, span.size, CornerRadius(span.height / 2f))
    } else {
        val stroke = RIBBON_OUTLINE.toPx()
        val inner = span.deflate(stroke / 2f)
        drawRoundRect(colour, inner.topLeft, inner.size, CornerRadius(inner.height / 2f), style = Stroke(stroke))
    }
}

/** The fertile days: a row of dots as tall as the ribbon, at least one however short the run. */
private fun DrawScope.drawFertileRibbon(span: Rect, colour: Color) {
    val dot = span.height
    val y = span.center.y
    val start = span.left + dot / 2f
    val end = span.right - dot / 2f
    if (end - start < dot) {
        drawCircle(colour, dot / 2f, Offset(span.center.x, y))
        return
    }
    drawLine(
        color = colour,
        start = Offset(start, y),
        end = Offset(end, y),
        strokeWidth = dot,
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0f, dot * 2f)),
    )
}

/**
 * The dashed line ruled across the plot: the level the record has actually been running at over
 * this window, so the curve can be read as above or below its own usual.
 *
 * Drawn before the curves, so they cross it rather than being interrupted by it, and only where
 * there is enough of a record for a level to mean anything.
 */
private fun DrawScope.drawRecordedLevel(chart: CycleChart, plot: Rect, colour: Color) {
    val values = chart.series.firstOrNull { it.role == CycleSeriesRole.RECORDED }
        ?.points?.filterNotNull()
        ?.takeIf { it.size >= CHART_TYPICAL_MIN_POINTS }
        ?: return
    val y = plot.bottom - (values.average().toFloat() * plot.height)
    drawLine(
        color = colour,
        start = Offset(plot.left, y),
        end = Offset(plot.right, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
    )
}

/**
 * One curve across the whole window, drawn twice: dashed end to end, then solid again over the
 * stretches that are her own record.
 *
 * Two passes rather than two paths, because the two have to be the same line. Cut into separate
 * paths they would meet at a seam, and where the record simply stops — at today, or at a day she did
 * not answer — the line would stop with it and the forecast begin somewhere else in another colour,
 * reading as a broken chart rather than as a cycle. Drawn twice, the shape is continuous and what
 * changes across it is the stroke: solid is measured, dashed is worked out from her own cycles.
 *
 * A [dotted] line is the second thing she notes, drawn beside her first in a near-identical white:
 * its record is close dots and its forecast sparse ones, and its dots are rings, so it is never
 * mistaken for the first line by colour alone.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawCycleSeries(
    series: CycleSeries,
    plot: Rect,
    colour: Color,
    x: (Int) -> Float,
    allEstimate: Boolean,
    leading: Boolean,
    todayIndex: Int,
    dotted: Boolean,
) {
    val width = if (allEstimate) ESTIMATE_STROKE.toPx() else RECORDED_STROKE.toPx()
    runsOfPoints(series.points).forEach { run ->
        val offsets = run.map { Offset(x(it), plotY(series.points[it]!!, plot)) }
        if (offsets.size == 1) {
            drawCurveDot(colour, offsets.first(), DOT_RADIUS.toPx(), dotted)
            return@forEach
        }
        val line = Path().apply { throughMidpoints(offsets) }
        if (leading) drawUnderLight(line, offsets, plot, colour)
        // The whole line first, as the estimate it is wherever it is not hers.
        val forecast = if (dotted) {
            PathEffect.dashPathEffect(floatArrayOf(0f, SPARSE_DOTS.toPx()))
        } else {
            PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx()))
        }
        drawPath(
            path = line,
            color = colour.copy(alpha = if (allEstimate) 1f else FORECAST_ALPHA),
            style = Stroke(width = width, cap = StrokeCap.Round, pathEffect = forecast),
        )
        if (!allEstimate) {
            drawHerOwn(series, run, plot, colour, x, width, dotted)
            drawCurveDots(series, run, plot, colour, x, todayIndex, dotted)
        }
    }
}

/** A point on a curve: a disc on the leading lines, a ring on the dotted one. */
private fun DrawScope.drawCurveDot(colour: Color, at: Offset, radius: Float, dotted: Boolean) {
    if (dotted) {
        val ring = RING_STROKE.toPx()
        drawCircle(colour, radius - ring / 2f, at, style = Stroke(ring))
    } else {
        drawCircle(colour, radius, at)
    }
}

/** The dotted line's spacing: close where it is her record, sparse where it is forecast. */
private val CLOSE_DOTS = 6.dp
private val SPARSE_DOTS = 12.dp
private val RING_STROKE = 2.dp

/**
 * Where a height between 0 and 1 lands, with air left at both ends.
 *
 * Mapped to the very top of the frame, the top rung of a ladder — "Обильная" — would be a curve
 * riding the ceiling with a flat top, read as clipped rather than as a peak. The floor gets the same
 * treatment: a nought sitting exactly on the bottom edge reads as an axis.
 */
private fun plotY(value: Double, plot: Rect): Float {
    val span = plot.height * (1f - PLOT_HEADROOM - PLOT_FLOOR)
    return plot.bottom - plot.height * PLOT_FLOOR - (value.coerceIn(0.0, 1.0) * span).toFloat()
}

private const val PLOT_HEADROOM = 0.10f
private const val PLOT_FLOOR = 0.06f

/** The light spilled under the leading curve, filled per run so it cannot cross a gap. */
private fun DrawScope.drawUnderLight(line: Path, offsets: List<Offset>, plot: Rect, colour: Color) {
    val under = Path().apply {
        addPath(line)
        lineTo(offsets.last().x, plot.bottom)
        lineTo(offsets.first().x, plot.bottom)
        close()
    }
    drawPath(
        under,
        Brush.verticalGradient(
            listOf(colour.copy(alpha = 0.30f), Color.Transparent),
            startY = plot.top,
            endY = plot.bottom,
        ),
    )
}

/** Her own record, solid, laid over the dashed line one unbroken stretch at a time. */
private fun DrawScope.drawHerOwn(
    series: CycleSeries,
    run: List<Int>,
    plot: Rect,
    colour: Color,
    x: (Int) -> Float,
    width: Float,
    dotted: Boolean,
) {
    val dots = PathEffect.dashPathEffect(floatArrayOf(0f, CLOSE_DOTS.toPx())).takeIf { dotted }
    val solid = Stroke(width = width, cap = StrokeCap.Round, pathEffect = dots)
    runsOfIndices(run.filter { series.recorded.getOrElse(it) { false } }).forEach { own ->
        val offsets = own.map { Offset(x(it), plotY(series.points[it]!!, plot)) }
        if (offsets.size == 1) {
            drawCurveDot(colour, offsets.first(), DOT_RADIUS.toPx() * 0.7f, dotted)
        } else {
            drawPath(Path().apply { throughMidpoints(offsets) }, colour, style = solid)
        }
    }
}

/** The two ends of the record and today, which are the three points the eye looks for. */
private fun DrawScope.drawCurveDots(
    series: CycleSeries,
    run: List<Int>,
    plot: Rect,
    colour: Color,
    x: (Int) -> Float,
    todayIndex: Int,
    dotted: Boolean,
) {
    val own = run.filter { series.recorded.getOrElse(it) { false } }
    setOf(own.firstOrNull(), own.lastOrNull(), todayIndex)
        .filterNotNull()
        .filter { it in run }
        .forEach { drawCurveDot(colour, Offset(x(it), plotY(series.points[it]!!, plot)), DOT_RADIUS.toPx(), dotted) }
}

/** How faint the worked-out half of a line is drawn against the half she wrote down. */
private const val FORECAST_ALPHA = 0.72f

/** The runs of consecutive integers in an already-sorted list. */
private fun runsOfIndices(indices: List<Int>): List<List<Int>> {
    val runs = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    indices.forEach { index ->
        if (current.isEmpty() || index == current.last() + 1) {
            current += index
        } else {
            runs += current
            current = mutableListOf(index)
        }
    }
    if (current.isNotEmpty()) runs += current
    return runs
}

/**
 * The estimated ovulation: one stick standing clear of the others with a ring at the top of it.
 *
 * The one day in the window a woman trying or avoiding actually looks for, so the plot marks it
 * before it arrives. It is drawn in the same white as the curve because it is the same kind of
 * thing — an estimate off her own dates — and named in the card below.
 */
private fun DrawScope.drawOvulationMark(x: Float, plot: Rect, colour: Color) {
    drawLine(
        color = colour.copy(alpha = 0.55f),
        start = Offset(x, plot.top + 4.dp.toPx()),
        end = Offset(x, plot.bottom),
        strokeWidth = 2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
    )
    drawCircle(colour, OVULATION_RING.toPx(), Offset(x, plot.top + 4.dp.toPx()), style = Stroke(2.dp.toPx()))
}

private val OVULATION_RING = 5.dp

/** The stretches of consecutive points that actually carry a value. */
private fun runsOfPoints(points: List<Double?>): List<List<Int>> {
    val runs = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    points.forEachIndexed { index, value ->
        if (value == null) {
            if (current.isNotEmpty()) runs += current
            current = mutableListOf()
        } else {
            current += index
        }
    }
    if (current.isNotEmpty()) runs += current
    return runs
}

/**
 * A curve through the points, its control points on the vertical midline between neighbours.
 *
 * Straight segments through a point a day come out as a saw, and every chart in this app already
 * draws its curves this way — see `drawSeries` in the calendar. Kept as one function here rather
 * than a fifth copy, because copies drift apart.
 */
internal fun Path.throughMidpoints(points: List<Offset>) {
    if (points.isEmpty()) return
    moveTo(points.first().x, points.first().y)
    points.forEachIndexed { index, point ->
        if (index == 0) return@forEachIndexed
        val previous = points[index - 1]
        val midX = (previous.x + point.x) / 2f
        cubicTo(midX, previous.y, midX, point.y, point.x, point.y)
    }
}

/**
 * One mark of the axis: a date, the column it belongs to, and where its words were fitted.
 *
 * [left] is not always under [index]: a label at either end is nudged inside the plot, and a month
 * name is allowed to slide anywhere over the run of columns that month owns rather than be thrown
 * away for standing too near its neighbour. The stick stays on the column, because the stick is the
 * claim about where the day falls and the words are only the reading of it.
 */
private class AxisMark(
    val index: Int,
    val label: TextLayoutResult,
    val left: Float,
    val kind: AxisMarkKind,
)

private enum class AxisMarkKind { TODAY, FORECAST, TICK }

/** The room the disc around today needs on either side of its words. */
private val TODAY_DISC = 9.dp

/** The words and the disc around them: what may not be crowded by the next mark along. */
private fun AxisMark.footprint(disc: Float): ClosedFloatingPointRange<Float> {
    val pad = if (kind == AxisMarkKind.TODAY) disc else 0f
    return (left - pad)..(left + label.size.width + pad)
}

/** The last column a mark's own stretch of time covers: up to the next mark, or the end. */
internal fun CycleChart.runEnd(index: Int): Int = ticks.firstOrNull { it > index }?.minus(1)
    ?: buckets.lastIndex

/**
 * Which dates the axis has room for, and where their words go.
 *
 * Today first — it is the mark the rest of the axis is read from — then the date the next period is
 * expected on, which is the one thing down here that answers a question rather than ruling a line,
 * then the ticks from left to right.
 *
 * A mark that would touch one already placed is pushed to its right. A date may not move far: it
 * names one day and words slid off that day would lie, so it is dropped instead. A month name may
 * move anywhere over its own month. That distinction is the whole of it: three months is three
 * marks, so dropping one leaves a third of the plot unnamed, and today's label at the left edge —
 * clamped inside the canvas, and wider since it carries its date — would reserve enough of the axis
 * to swallow the next month's mark on roughly a third of the days of the year.
 *
 * The sticks do not follow this at all. They are drawn on every column that has something to
 * mark, whether or not its words fitted, so the plot keeps its ruler on a phone whose text is too
 * large to name every one of them.
 */
private fun DrawScope.axisMarks(
    chart: CycleChart,
    labels: Map<Int, TextLayoutResult>,
    forecast: TextLayoutResult?,
    x: (Int) -> Float,
    slot: Float,
): List<AxisMark> {
    val placed = mutableListOf<AxisMark>()

    fun place(at: Float, index: Int, label: TextLayoutResult, kind: AxisMarkKind, mayReach: Float) {
        val left = pushClear(placed, at, label, kind)
        if (left <= roomFor(label, kind) && left <= mayReach) {
            placed += AxisMark(index, label, left, kind)
        }
    }

    labels[chart.todayIndex]?.let {
        place(x(chart.todayIndex), chart.todayIndex, it, AxisMarkKind.TODAY, Float.MAX_VALUE)
    }
    chart.predictedStart?.let { at ->
        forecast?.let {
            place(x(at), at, it, AxisMarkKind.FORECAST, x(at) - it.size.width / 2f + slot / 2f)
        }
    }
    chart.ticks.filter { it != chart.todayIndex && !chart.namedAlready(it, placed) }
        .forEach { index ->
            labels[index]?.let { label ->
                val (at, reach) = chart.wordsAt(index, label, x, slot)
                place(at, index, label, AxisMarkKind.TICK, reach)
            }
        }
    return placed
}

/** The furthest left a mark's words may start and still be inside the canvas. */
private fun DrawScope.roomFor(label: TextLayoutResult, kind: AxisMarkKind): Float {
    val pad = if (kind == AxisMarkKind.TODAY) TODAY_DISC.toPx() else 0f
    return (size.width - label.size.width - pad).coerceAtLeast(pad)
}

/** Where a mark's words land once pushed clear of everything already written. */
private fun DrawScope.pushClear(
    placed: List<AxisMark>,
    at: Float,
    label: TextLayoutResult,
    kind: AxisMarkKind,
): Float {
    val disc = TODAY_DISC.toPx()
    val gap = AXIS_GAP.toPx()
    val pad = if (kind == AxisMarkKind.TODAY) disc else 0f
    var left = (at - label.size.width / 2f).coerceIn(pad, roomFor(label, kind))
    placed.map { it.footprint(disc) }.forEach { taken ->
        val crowded = left - pad - gap < taken.endInclusive &&
            taken.start < left + label.size.width + pad + gap
        if (crowded) left = taken.endInclusive + gap + pad
    }
    return left
}

/**
 * Where a mark's words want to stand, and the furthest right they may be pushed and still be true.
 *
 * A month name belongs over the middle of its month, not on the line that opens it — the lines
 * divide the three months and a name sitting on one reads as belonging to either side of it — and
 * it may slide anywhere over the month it names. A date belongs on its day and may shift by half a
 * column, which is still the same day pointed at; further than that the words would lie, so they
 * are dropped instead.
 */
private fun CycleChart.wordsAt(
    index: Int,
    label: TextLayoutResult,
    x: (Int) -> Float,
    slot: Float,
): Pair<Float, Float> = if (window == CycleWindow.QUARTER) {
    val end = runEnd(index)
    val middle = (x(index) + x(end)) / 2f
    middle to maxOf(middle - label.size.width / 2f, x(end) + slot / 2f - label.size.width)
} else {
    x(index) to x(index) - label.size.width / 2f + slot / 2f
}

/**
 * A month the forecast is already standing inside does not need its name as well: "14 Oct" says
 * October, and the two side by side are one claim written twice.
 */
private fun CycleChart.namedAlready(index: Int, placed: List<AxisMark>): Boolean =
    window == CycleWindow.QUARTER &&
        placed.any { it.kind == AxisMarkKind.FORECAST && it.index in index..runEnd(index) }

/**
 * Every column the axis has something to say about, named or not — the plot's ruler.
 *
 * Over three months the lines are the month divisions and nothing else, so the plot reads as three
 * bands; a line drawn inside a month there would be taken for a fourth boundary.
 */
internal fun CycleChart.stickColumns(): List<Int> = if (window == CycleWindow.QUARTER) {
    ticks
} else {
    (ticks + listOfNotNull(predictedStart)).distinct().sorted()
}

/** The stretch of time a column belongs to: its month over a quarter, its week over a month. */
internal fun CycleChart.bandOf(index: Int): IntRange {
    val from = ticks.lastOrNull { it <= index } ?: 0
    return from..runEnd(from)
}

/** What a day is, in the three words the plot is shaded in. */
internal fun CycleChart.dayKind(index: Int): Int = when {
    bleeding.any { index in it } || predicted.any { index in it } -> R.string.cycle_legend_period
    fertile.any { index in it } -> R.string.cycle_legend_fertile
    else -> R.string.cycle_phase_ordinary
}

/**
 * The stretch of time the held column belongs to, lit behind the curves.
 *
 * Over three months that is its month, which is what the lines across the plot divide it into; over
 * a month it is its week. Answering "which one am I looking at" is most of what a finger put on a
 * plot of ninety days is asking.
 */
private fun DrawScope.drawHeldBand(band: IntRange, slot: Float, plot: Rect, colour: Color) {
    drawRect(
        color = colour.copy(alpha = HELD_BAND_ALPHA),
        topLeft = Offset(plot.left + slot * band.first, plot.top),
        size = Size(slot * (band.last - band.first + 1), plot.height),
    )
}

/** And the day itself, ruled through the curves so the reading is taken at one place. */
private fun DrawScope.drawHeldColumn(x: Float, plot: Rect, colour: Color) {
    drawLine(
        color = colour,
        start = Offset(x, plot.top),
        end = Offset(x, plot.bottom),
        strokeWidth = 2.dp.toPx(),
    )
}

/** The held day's own date, standing where today's normally does while a finger is down. */
private fun DrawScope.heldMark(index: Int, label: TextLayoutResult, at: Float): AxisMark {
    val pad = TODAY_DISC.toPx()
    val room = (size.width - label.size.width - pad).coerceAtLeast(pad)
    val left = (at - label.size.width / 2f).coerceIn(pad, room)
    return AxisMark(index, label, left, AxisMarkKind.TODAY)
}

private const val HELD_BAND_ALPHA = 0.16f

/**
 * A date under the plot: today inside a disc, the forecast in full ink, the rest dimmed.
 *
 * The disc darkens the blue rather than lightening it. A white one would put today's own date — the
 * mark the axis is read from — under the 4.5:1 text owes and make it the least legible date on the
 * axis.
 */
private fun DrawScope.drawAxisMark(mark: AxisMark, top: Float, ink: Color, muted: Color) {
    val label = mark.label
    if (mark.kind == AxisMarkKind.TODAY) {
        drawRoundRect(
            color = CycleTodayDisc,
            topLeft = Offset(mark.left - TODAY_DISC.toPx(), top),
            size = Size(label.size.width + TODAY_DISC.toPx() * 2f, label.size.height + 9.dp.toPx()),
            cornerRadius = CornerRadius(label.size.height / 2f + 5.dp.toPx()),
        )
    }
    drawText(
        label,
        color = if (mark.kind == AxisMarkKind.TICK) muted else ink,
        topLeft = Offset(mark.left, top + 5.dp.toPx()),
    )
}

/** How tall the strip of dates under the plot stands: one line, and the disc around today. */
private val AXIS_HEIGHT = 34.dp
private val AXIS_GAP = 8.dp

/**
 * What one mark is called: a weekday in a week, a date in a month, a month in anything longer.
 *
 * Today is always its own date, even where the rest of the axis names months. Its stick stands on
 * the day the window opens — the 18th — and a stick there labelled "September" reads as the month
 * beginning under it, which is a fortnight out.
 */
private fun CycleChart.tickLabel(index: Int, locale: java.util.Locale): String {
    val date = buckets[index].start
    return when {
        window == CycleWindow.WEEK -> date.format(localizedDatePattern(locale, "EEEd", "EEE d"))
        window == CycleWindow.MONTH || index == todayIndex -> date.format(monthDay(locale))
        else -> date.month.getDisplayName(TextStyle.SHORT, locale)
    }
}

/** Day and short month, in the order the phone's language writes them. */
private fun monthDay(locale: java.util.Locale): DateTimeFormatter = localizedDatePattern(locale, "dMMM", "d MMM")

/**
 * What is on the plot, named and centred: every curve, and every kind of day marked along the
 * floor.
 *
 * Each entry's swatch is the mark itself — a stroke of the line in its own pattern, a piece of the
 * ribbon in its own shape — rather than a dot of colour, because the colours on the blue are all
 * pale and near each other and the shapes are what tell them apart. The fertility estimate and the
 * fertile ribbon mark the same days, so they share one entry with both marks in front of it.
 *
 * A flowing row rather than a plain one: four entries in Russian are wider than a phone, and a
 * legend that runs off the edge names nothing.
 */
@Composable
private fun CycleChartLegend(chart: CycleChart) {
    val ink = CycleOnViolet
    val peach = CyclePeach
    val mint = CycleMint
    val gold = CycleEstimate
    val fertileShared = chart.fertile.isNotEmpty() && chart.series.any { it.role == CycleSeriesRole.ESTIMATE }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chart.series.forEachIndexed { index, series ->
            val colour = seriesColour(index, series.role, ink, mint, gold)
            val stroke = seriesStroke(index, series.role)
            val withRibbon = fertileShared && series.role == CycleSeriesRole.ESTIMATE
            CycleLegendEntry(stringResource(series.legendRes())) {
                drawLegendLine(colour, stroke, if (withRibbon) size.height / 4f else size.height / 2f)
                if (withRibbon) drawFertileRibbon(legendRibbon(centred = false), ink)
            }
        }
        if (chart.bleeding.isNotEmpty()) {
            CycleLegendEntry(stringResource(R.string.cycle_legend_period)) {
                drawBleedingRibbon(legendRibbon(centred = true), peach, filled = true)
            }
        }
        if (chart.predicted.isNotEmpty()) {
            CycleLegendEntry(stringResource(R.string.cycle_legend_predicted)) {
                drawBleedingRibbon(legendRibbon(centred = true), peach, filled = false)
            }
        }
        if (chart.fertile.isNotEmpty() && !fertileShared) {
            CycleLegendEntry(stringResource(R.string.cycle_legend_fertile)) {
                drawFertileRibbon(legendRibbon(centred = true), ink)
            }
        }
        if (chart.ovulation != null) {
            CycleLegendEntry(stringResource(R.string.cycle_legend_ovulation)) {
                drawCircle(ink, OVULATION_RING.toPx(), center, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** One entry of the key: the mark as it is drawn on the plot, and its name. */
@Composable
private fun CycleLegendEntry(name: String, swatch: DrawScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = LEGEND_SWATCH_WIDTH, height = LEGEND_SWATCH_HEIGHT), onDraw = swatch)
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = CycleOnViolet,
        )
    }
}

/** A short stroke of a curve, in the pattern the curve is drawn in, at height [y]. */
private fun DrawScope.drawLegendLine(colour: Color, stroke: SeriesStroke, y: Float) {
    val effect = when (stroke) {
        SeriesStroke.SOLID -> null
        SeriesStroke.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(0f, CLOSE_DOTS.toPx()))
        SeriesStroke.DASHED -> PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx()))
    }
    val width = if (stroke == SeriesStroke.DASHED) ESTIMATE_STROKE.toPx() else RECORDED_STROKE.toPx()
    val inset = width / 2f
    drawLine(
        color = colour,
        start = Offset(inset, y),
        end = Offset(size.width - inset, y),
        strokeWidth = width,
        cap = StrokeCap.Round,
        pathEffect = effect,
    )
}

/**
 * The strip a ribbon's piece is drawn in on a swatch: one ribbon tall, across the middle, or along
 * the foot where a curve's stroke shares the swatch above it.
 */
private fun DrawScope.legendRibbon(centred: Boolean): Rect {
    val thickness = RIBBON.toPx()
    val top = if (centred) (size.height - thickness) / 2f else size.height - thickness
    return Rect(0f, top, size.width, top + thickness)
}

private val LEGEND_SWATCH_WIDTH = 22.dp
private val LEGEND_SWATCH_HEIGHT = 14.dp

/** What a line is called under the plot. */
private fun CycleSeries.legendRes(): Int = when (role) {
    CycleSeriesRole.ESTIMATE -> R.string.cycle_legend_fertile
    CycleSeriesRole.RECORDED -> scale.titleRes()
}

/** What a ladder is called where it has to be named in a line of text. */
internal fun CycleScale.titleRes(): Int = when (this) {
    CycleScale.FLOW -> R.string.cycle_section_flow
    CycleScale.PAIN -> R.string.cycle_section_pain
    CycleScale.MUCUS -> R.string.cycle_section_mucus
    CycleScale.DESIRE -> R.string.cycle_section_desire
    CycleScale.OVULATION -> R.string.cycle_section_ovulation
}

/**
 * The plot in words, because a canvas publishes nothing a screen reader can reach.
 *
 * Every curve of hers is named with how many of its columns she answered herself — not how many
 * carry a value, which in a window that begins today is nearly all of them and nearly all forecast —
 * and where its highest reading fell, rather than a reading-out of ninety columns.
 *
 * Then the days along the floor, which the eye reads off the ribbons and a screen reader has no
 * other way to: when the bleeding was, when it is forecast, the fertile days and the ovulation
 * estimate.
 */
private fun spokenCycleChart(
    resources: Resources,
    chart: CycleChart,
    dayMonth: DateTimeFormatter,
): String {
    if (chart.empty) return resources.getString(R.string.cycle_chart_empty)
    val days = listOfNotNull(
        spokenRuns(resources, R.string.cycle_legend_period, chart.bleeding, chart, dayMonth),
        spokenRuns(resources, R.string.cycle_legend_predicted, chart.predicted, chart, dayMonth),
        spokenRuns(resources, R.string.cycle_legend_fertile, chart.fertile, chart, dayMonth),
        chart.ovulation?.let {
            resources.getString(
                R.string.m3_cal_chart_spoken_days,
                resources.getString(R.string.cycle_legend_ovulation),
                chart.buckets[it].start.format(dayMonth),
            )
        },
    )
    // A plot holding nothing but a floor she never answered has nothing to name, and says so.
    return (spokenSeries(resources, chart, dayMonth) + days).joinToString(" ")
        .ifEmpty { resources.getString(R.string.cycle_chart_empty) }
}

/** One kind of day, named with the stretches of the window it covers, or nothing if it covers none. */
private fun spokenRuns(
    resources: Resources,
    name: Int,
    runs: List<IntRange>,
    chart: CycleChart,
    dayMonth: DateTimeFormatter,
): String? {
    if (runs.isEmpty()) return null
    val stretches = runs.joinToString(resources.getString(R.string.list_comma)) { run ->
        val from = chart.buckets[run.first].start
        val to = chart.buckets[run.last].endInclusive
        if (from == to) {
            from.format(dayMonth)
        } else {
            resources.getString(R.string.cycle_first_range, from.format(dayMonth), to.format(dayMonth))
        }
    }
    return resources.getString(R.string.m3_cal_chart_spoken_days, resources.getString(name), stretches)
}

/**
 * The curves in words: each one's own record and where its highest reading fell. A curve with no
 * rung to name says nothing — see [spokenReading].
 */
private fun spokenSeries(resources: Resources, chart: CycleChart, dayMonth: DateTimeFormatter): List<String> =
    chart.series.mapNotNull { series ->
        series.spokenReading()?.let { reading ->
            resources.getString(
                R.string.cycle_chart_spoken_series,
                resources.getString(series.legendRes()),
                resources.getQuantityString(R.plurals.count_days, reading.answered, reading.answered),
                resources.getString(reading.peak.titleRes),
                chart.buckets[reading.peakIndex].start.format(dayMonth),
            )
        }
    }
