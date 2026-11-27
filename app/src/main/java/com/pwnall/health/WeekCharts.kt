package com.pwnall.health

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// The pieces the week-by-week chart pages are drawn from — the sex-life charts and the work charts —
// so that a bar, a line, a gap and "your average" look and read the same on both. Each page works
// its own figures out with no canvas in sight; these only place them.

/**
 * The frame every chart here is drawn in: [axis] — the top figure and the floor's — up the left side,
 * the weeks' last days under the plot at a stride the width can print without collisions, and
 * [draw] over the plot. The drawing says nothing to a screen reader; [spoken] says all of it.
 */
@Composable
internal fun WeekChart(
    weeks: List<ClosedRange<LocalDate>>,
    spoken: String,
    axis: List<String>,
    draw: DrawScope.(WeekPlot) -> Unit,
) {
    val measurer = rememberTextMeasurer(cacheSize = MEASURER_CACHE)
    val locale = LocalLocale.current.platformLocale
    val dateFormat = remember(locale) { localizedDatePattern(locale, "dMMM", "d MMM") }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = PwnMuted)
    val grid = PwnBorder
    val dates = remember(weeks, dateFormat) { weeks.map { it.endInclusive.format(dateFormat) } }
    Box(Modifier.fillMaxWidth().height(CHART_HEIGHT).clearAndSetSemantics { contentDescription = spoken }) {
        Canvas(Modifier.fillMaxSize()) {
            val gap = AXIS_GAP.toPx()
            val side = axis.map { measurer.measure(it, labelStyle) }
            val gutter = side.maxOf { it.size.width }.toFloat() + gap
            val footer = measurer.measure("0", labelStyle).size.height.toFloat() + gap
            // Room over the plot for what is drawn above its top: a mark lifted over a point at the
            // ceiling, and the top figure centred on the ceiling's rule.
            val crown = max(side.first().size.height / 2f, CROWN.toPx())
            val rect = Rect(gutter, crown, size.width, size.height - footer)
            if (rect.width <= 0f || rect.height <= 0f) return@Canvas
            val plot = WeekPlot(rect, weeks.size)
            drawLine(grid, Offset(rect.left, rect.top), Offset(rect.right, rect.top), 1f)
            drawLine(grid, Offset(rect.left, rect.bottom), Offset(rect.right, rect.bottom), 1f)
            drawSideLabel(side.first(), rect.top, rect, gap)
            drawSideLabel(side.last(), rect.bottom, rect, gap)
            drawWeekDates(plot, dates, measurer, labelStyle, gap)
            draw(plot)
        }
    }
}

/** The plot and the column a week stands in: one equal slot each, the newest on the right. */
internal class WeekPlot(val rect: Rect, val count: Int) {
    val slot: Float get() = rect.width / count

    fun x(index: Int): Float = rect.left + slot * (index + 0.5f)
}

/**
 * One comparison: a figure a week as a bar from the floor, scaled to [top] and printed up the side
 * as [axis], and the other figure as one line drawn from a centre line — [line] each week's distance
 * from the person's own average, above it in a week higher than the average, below it in a week
 * lower, broken where a week has no point. Side by side in the same weeks and no more: the card's
 * question mark says so.
 *
 * The line is scaled to the widest swing in either direction, never to less than [minSwing], in the
 * line's own units: a line that strays a little from the average would otherwise fill the plot like
 * one that strays a lot.
 */
@Composable
internal fun WeekPairChart(
    weeks: List<ClosedRange<LocalDate>>,
    spoken: String,
    bars: List<Double?>,
    top: Double,
    axis: List<String>,
    line: List<Double?>,
    minSwing: Double,
    barName: String,
    lineName: String,
) {
    // The bars in the secondary ink, thinned until the body still clears 3:1 on the card (WCAG 1.4.11)
    // and the line, in the card's own ink, still clears 3:1 where it crosses a bar; both held by
    // DesignContrastTest. The data blue would sit near 1.5:1 on such a bar by day.
    val barEdge = PwnPurple
    val bar = weekBarFill()
    val ink = PwnInk
    val centre = PwnMuted
    WeekChart(weeks, spoken, axis) { plot ->
        drawWeekBars(plot, bars.map { it?.let { value -> (value / top).toFloat() } }, bar, barEdge)
        val middle = plot.rect.center.y
        drawCentreLine(plot.rect, middle, centre)
        val swing = max(line.maxOf { abs(it ?: 0.0) }, minSwing)
        val reach = plot.rect.height / 2f * SWING_REACH
        drawWeekLine(plot, line.map { it?.let { off -> middle - (off / swing).toFloat() * reach } }, ink, dots = true)
    }
    ChartLegend {
        LegendEntry(barName) { drawLegendBar(bar, barEdge) }
        LegendEntry(lineName) { drawLegendStroke(ink) }
        LegendEntry(stringResource(R.string.intimate_charts_legend_average)) { drawLegendStroke(centre, dashed = true) }
    }
}

/** The bars' fill: the secondary ink, thinned by day and by night as [WEEK_BAR_ALPHA_LIGHT] says. */
@Composable
internal fun weekBarFill(): Color =
    darkOr(PwnPurple.copy(alpha = WEEK_BAR_ALPHA_DARK), PwnPurple.copy(alpha = WEEK_BAR_ALPHA_LIGHT))

/** When the chart appears at all: the floor below which it is not drawn rather than drawn empty. */
@Composable
internal fun ShowRule(weeks: Int) {
    PatternNote(pluralStringResource(R.plurals.intimate_charts_show_rule, weeks, weeks))
}

private fun DrawScope.drawSideLabel(label: TextLayoutResult, y: Float, plot: Rect, gap: Float) {
    val top = (y - label.size.height / 2f).coerceIn(0f, maxOf(0f, size.height - label.size.height))
    drawText(label, topLeft = Offset(plot.left - gap - label.size.width, top))
}

/** The weeks' last days under the plot, the newest always among them, every [stride]th back from it. */
private fun DrawScope.drawWeekDates(
    plot: WeekPlot,
    dates: List<String>,
    measurer: TextMeasurer,
    style: TextStyle,
    gap: Float,
) {
    val texts = dates.map { measurer.measure(it, style) }
    val widest = texts.maxOf { it.size.width } + gap * 2f
    val stride = ceil(widest / plot.slot).toInt().coerceAtLeast(1)
    texts.forEachIndexed { index, text ->
        if ((texts.lastIndex - index) % stride != 0) return@forEachIndexed
        val left = (plot.x(index) - text.size.width / 2f)
            .coerceIn(plot.rect.left, maxOf(plot.rect.left, plot.rect.right - text.size.width))
        drawText(text, topLeft = Offset(left, plot.rect.bottom + gap))
    }
}

/** A dashed rule across the plot: the person's own average or median, the line the weeks are read against. */
internal fun DrawScope.drawCentreLine(plot: Rect, y: Float, colour: Color) {
    val dashes = PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()))
    drawLine(colour, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx(), pathEffect = dashes)
}

/**
 * A line through the weeks' points, straight from week to week: a curve would invent the days in
 * between. A null breaks it; a point with no neighbour is a dot on its own, [dots] or not, so a lone
 * week never vanishes.
 */
internal fun DrawScope.drawWeekLine(plot: WeekPlot, ys: List<Float?>, colour: Color, dots: Boolean) {
    val path = Path()
    var drawing = false
    ys.forEachIndexed { index, y ->
        if (y == null) {
            drawing = false
        } else if (drawing) {
            path.lineTo(plot.x(index), y)
        } else {
            path.moveTo(plot.x(index), y)
            drawing = true
        }
    }
    drawPath(path, colour, style = Stroke(CHART_LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    ys.forEachIndexed { index, y ->
        y ?: return@forEachIndexed
        val alone = ys.getOrNull(index - 1) == null && ys.getOrNull(index + 1) == null
        if (dots || alone) drawCircle(colour, POINT_RADIUS.toPx(), Offset(plot.x(index), y))
    }
}

/**
 * Bars from the floor, [heights] a share of the plot's height each, a week with none left empty. Their
 * tops are rounded and their outline is the [edge], so a week at zero is still a line on the floor;
 * [grow], from 0 to 1, raises them all together as a chart opens.
 */
internal fun DrawScope.drawWeekBars(
    plot: WeekPlot,
    heights: List<Float?>,
    fill: Color,
    edge: Color,
    grow: Float = 1f,
) {
    val width = plot.slot * BAR_SHARE
    val corner = CornerRadius(min(width / 2f, BAR_CORNER.toPx()))
    val outline = Stroke(BAR_EDGE.toPx())
    heights.forEachIndexed { index, share ->
        share ?: return@forEachIndexed
        val left = plot.x(index) - width / 2f
        val top = plot.rect.bottom - share * grow * plot.rect.height
        val bar = Path().apply {
            addRoundRect(
                RoundRect(
                    left,
                    top,
                    left + width,
                    plot.rect.bottom,
                    topLeftCornerRadius = corner,
                    topRightCornerRadius = corner,
                ),
            )
        }
        drawPath(bar, fill)
        drawPath(bar, edge, style = outline)
    }
}

/**
 * A line through the weeks' points as one smooth curve. From week to week it leaves each point level
 * and arrives at the next level, so it never rises above the higher of two weeks or dips below the
 * lower: the curve rounds the corners without inventing a week that was not there. A null breaks it;
 * a point with no neighbour is a dot. Under it runs a [halo] in the card's colour, so where it crosses
 * a bar or another line it stands edged by the card rather than against their colours.
 */
internal fun DrawScope.drawSmoothWeekLine(plot: WeekPlot, ys: List<Float?>, colour: Color, halo: Color) {
    val path = Path()
    var last: Offset? = null
    ys.forEachIndexed { index, y ->
        val point = y?.let { Offset(plot.x(index), it) }
        val from = last
        if (point != null && from == null) path.moveTo(point.x, point.y)
        if (point != null && from != null) {
            val middle = (from.x + point.x) / 2f
            path.cubicTo(middle, from.y, middle, point.y, point.x, point.y)
        }
        last = point
    }
    val width = CHART_LINE_WIDTH.toPx()
    val rim = LINE_HALO.toPx()
    drawPath(path, halo, style = Stroke(width + rim * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    ys.forEachIndexed { index, y ->
        y ?: return@forEachIndexed
        if (ys.getOrNull(index - 1) != null || ys.getOrNull(index + 1) != null) return@forEachIndexed
        val at = Offset(plot.x(index), y)
        drawCircle(halo, POINT_RADIUS.toPx() + rim, at)
        drawCircle(colour, POINT_RADIUS.toPx(), at)
    }
}

internal fun DrawScope.drawLegendStroke(colour: Color, dashed: Boolean = false) {
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx())) else null
    val width = if (dashed) 1.dp.toPx() else CHART_LINE_WIDTH.toPx()
    drawLine(
        colour,
        Offset(0f, center.y),
        Offset(size.width, center.y),
        width,
        cap = StrokeCap.Round,
        pathEffect = effect,
    )
}

internal fun DrawScope.drawLegendBar(fill: Color, edge: Color) {
    val width = size.width / 2f
    val left = (size.width - width) / 2f
    drawRect(fill, Offset(left, 0f), Size(width, size.height))
    drawLine(edge, Offset(left, 0f), Offset(left + width, 0f), BAR_EDGE.toPx())
}

/**
 * The key under a chart, each entry's swatch the mark as it is drawn on the plot. A flowing row: four
 * entries in Russian are wider than a phone.
 */
@Composable
internal fun ChartLegend(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
internal fun LegendEntry(name: String, swatch: DrawScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = LEGEND_SWATCH_WIDTH, height = LEGEND_SWATCH_HEIGHT), onDraw = swatch)
        Text(name, style = MaterialTheme.typography.labelMedium, color = PwnMuted)
    }
}

/**
 * What a chart is to a screen reader: its title, how many weeks, and each week by its last day with
 * what it holds — [week] null for a week with nothing to say, which is left out.
 */
internal fun spokenChart(
    resources: Resources,
    locale: Locale,
    title: String,
    weeks: List<ClosedRange<LocalDate>>,
    week: (Int) -> String?,
): String {
    val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val said = weeks.indices.mapNotNull { index ->
        week(index)?.let {
            resources.getString(R.string.intimate_charts_spoken_week, weeks[index].endInclusive.format(day), it)
        }
    }.joinToString("; ")
    return resources.getQuantityString(R.plurals.intimate_charts_spoken, weeks.size, title, weeks.size, said)
}

/**
 * The bars' share of the secondary ink, by day and by night: the most of the card that shows through
 * while the bar still clears 3:1 on it and the card's ink still clears 3:1 on the bar
 * (DesignContrastTest).
 */
internal const val WEEK_BAR_ALPHA_LIGHT = 0.75f
internal const val WEEK_BAR_ALPHA_DARK = 0.65f

/** How far towards the frame the widest swing reaches, so its dot is never cut by the edge. */
internal const val SWING_REACH = 0.9f

/** How much of a week's slot its bar takes. */
private const val BAR_SHARE = 0.56f

/** Strings a chart measures in one frame: two figures up the side and thirteen dates under it. */
private const val MEASURER_CACHE = 16

/** The width of every line on these charts; the acts chart nudges two tied points apart by half of it. */
internal val CHART_LINE_WIDTH = 2.5.dp

private val CHART_HEIGHT = 176.dp
private val CROWN = 16.dp
private val AXIS_GAP = 6.dp
private val POINT_RADIUS = 3.dp
private val BAR_EDGE = 1.5.dp
private val BAR_CORNER = 6.dp

/** The card-coloured rim either side of a smooth line. */
private val LINE_HALO = 2.dp
private val DASH_ON = 4.dp
private val DASH_OFF = 4.dp
private val LEGEND_SWATCH_WIDTH = 22.dp
private val LEGEND_SWATCH_HEIGHT = 12.dp
