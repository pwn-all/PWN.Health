package com.pwnall.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * One marker's readings over a stretch of time: the band tinted, the dated marks under it, the
 * curve through the results and the newest figure called out.
 *
 * The same drawing on the card and in the page the card opens — a card shows the whole record at
 * once and the page shows whatever the fingers have left in the window, which is the only
 * difference between them. Values are printed beside their dots while there is room for them and
 * dropped to the newest one alone when there is not, on the measured width rather than on a count:
 * "21.3" and "0.0043" are not the same number of millimetres.
 *
 * The readings' figures are printed at [places] — the precision the card around it prints the
 * figure and the range at — and a band's edge is labelled as the card's range names it, the last
 * figure inside, at the same precision: glucose's line at 6.1 mmol/L says "109" in mg/dL beside the
 * card's "up to 109", not "110". So the drawing never rounds a figure across an edge the card does
 * not. The full-screen ladder is printed to the decimals its own step needs — see [labLadderText].
 */
@Composable
internal fun LabSeriesChart(
    marker: LabMarker,
    readings: List<LabReading>,
    band: LabBand?,
    sex: String,
    imperial: Boolean,
    places: Int,
    window: LabChartWindow,
    modifier: Modifier = Modifier,
    detailed: Boolean = false,
) {
    // Room for a whole axis: the default cache holds eight strings, and a chart being pinched
    // re-measures four figures up the side, six dates under it and a chip per reading every frame.
    val measurer = rememberTextMeasurer(cacheSize = MEASURER_CACHE)
    val locale = LocalLocale.current.platformLocale
    val zone = rememberZone()
    val dateFormat = remember(locale, detailed) {
        if (detailed) {
            localizedDatePattern(locale, "yyMMMd", "d MMM yy")
        } else {
            localizedDatePattern(locale, "yyMMM", "MMM yy")
        }
    }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = PwnMuted)
    // The band's caption is printed on the tinted strip rather than on the card, and the muted ink
    // reads under 4.5:1 on both strips where they lie on a card in daylight; the full ink clears it
    // on either strip, on the card and on the full-screen page alike.
    val captionStyle = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
    val outside = readings.any(LabReading::outside)
    val edge = if (outside) PwnWarn else PwnGreen
    // The strip outside the band is a whisper of the warning ink its edge is drawn in, so the two
    // verdicts differ in their fill as well as their line; inside, see [IN_BAND_TINT].
    val tint = (if (outside) PwnWarn else IN_BAND_TINT).copy(alpha = 0.14f)
    // The deepened blue: a chart's line is the data, drawn in the ink a lab figure is printed in.
    val line = PwnBlueInk
    val grid = PwnBorder
    val dot = PwnBlueInk
    val outsideDot = PwnWarn
    // The chip the newest figure stands in is a verdict, not a decoration: green where that reading
    // was inside its band, and the warning ink where it was not: green either way would put a pass
    // under a figure the card above it has just called too high. A reading with no verdict — an
    // ungraded marker, a band withheld at that age — gets the chart's own ink: green there would be
    // a pass under a figure whose note says nothing calls it high or low.
    val latest = readings.lastOrNull()
    val latestChip = when {
        latest?.status == null -> PwnBlueInk
        latest.outside -> PwnWarn
        else -> PwnGreen
    }
    val latestInk = MaterialTheme.colorScheme.background
    val bandCaption = stringResource(R.string.labs_typical_range)
    // The sentence [labTrendSummaryRes] chooses, over the last few dates: a drawing of numbers
    // publishes none of them, and this is the only form the series takes for a screen reader. The
    // dates in the reader's own form, since a stored day is read out as digits and dashes.
    val figures = remember(readings, sex, imperial, places, locale) {
        val spokenDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        readings.takeLast(SPOKEN_POINTS).joinToString {
            val day = runCatching { LocalDate.parse(it.day).format(spokenDate) }.getOrDefault(it.day)
            "$day: ${shownFigure(marker.printed(it, sex, imperial, places), locale)}"
        }
    }
    val spoken = stringResource(
        labTrendSummaryRes(readings, band),
        stringResource(marker.titleRes),
        figures,
        // Nothing to say after a pH: the dash that stands in for a unit is read out as "em dash".
        if (marker.unitless) "" else stringResource(marker.unitLabelRes(imperial)),
    )

    val low = (band?.low ?: band?.veryLow)?.let { marker.display(it, imperial) }
    val high = (band?.high ?: band?.veryHigh)?.let { marker.display(it, imperial) }
    // One reading either side of the window as well as the ones inside it: a curve that stopped at
    // the frame would hang in mid-air the moment the chart was zoomed in on the middle of a record.
    // Everything after this clamps its drawing to the plot, so the two extras only lend it a slope.
    val points = remember(readings, window, sex, imperial, places, locale) {
        // Taken as "the last one before the window, through to the first one after it", which also
        // covers the window that falls between two panels: there the slice is exactly those two,
        // and the line is drawn across the gap instead of the chart coming up empty.
        val before = readings.indexOfLast { it.occurredAt < window.startMs }
        val after = readings.indexOfFirst { it.occurredAt > window.endMs }
        val from = before.coerceAtLeast(0)
        val to = if (after < 0) readings.lastIndex else after
        if (readings.isEmpty()) {
            emptyList()
        } else {
            readings.subList(from, (to + 1).coerceAtMost(readings.size)).map {
                val figure = shownFigure(marker.printed(it, sex, imperial, places), locale)
                LabChartDot(it.occurredAt, marker.display(it.value, imperial), figure, it.outside)
            }
        }
    }
    // Scaled to what is actually in the window, not to the two neighbours lent to the curve: one
    // huge reading just off the left edge would otherwise squash everything on screen into a line.
    val scale = remember(points, window, low, high) {
        val inWindow = points.filter { it.at in window.startMs..window.endMs }.map { it.value }
        labValueScale(inWindow.ifEmpty { points.map { it.value } }, low, high)
    }

    Box(modifier.semantics { contentDescription = spoken }) {
        Canvas(Modifier.fillMaxSize()) {
            val yLabels = if (detailed) {
                val rungs = labValueLadder(scale)
                rungs.zip(labLadderText(rungs)) { at, text ->
                    at to measurer.measure(shownFigure(text, locale), labelStyle)
                }
            } else {
                // Drawn where the edge is, and labelled as the card's range names it.
                val (bottom, top) = band?.let { marker.printedEdges(it, imperial, places) } ?: (null to null)
                listOfNotNull(
                    high?.let { at -> top?.let { at to measurer.measure(shownFigure(it, locale), labelStyle) } },
                    low?.let { at -> bottom?.let { at to measurer.measure(shownFigure(it, locale), labelStyle) } },
                )
            }
            val gutter = yLabels.maxOfOrNull { it.second.size.width }?.toFloat() ?: 0f
            val footer = measurer.measure("0", labelStyle).size.height.toFloat()
            val crown = measurer.measure("0", valueStyle).size.height.toFloat() * CHIP_ROOM
            val gap = AXIS_GAP.toPx()
            val plot = Rect(
                left = gutter + if (gutter > 0f) gap else 0f,
                top = crown,
                right = size.width,
                bottom = size.height - footer - gap,
            )
            if (plot.width <= 0f || plot.height <= 0f) return@Canvas

            fun y(value: Double) =
                plot.bottom - (((value - scale.first) / (scale.second - scale.first)) * plot.height).toFloat()
            fun x(at: Long) = plot.left + plot.width * ((at - window.startMs).toDouble() / window.spanMs).toFloat()

            // The band first, so everything else is drawn over it.
            if (low != null && high != null) {
                val top = y(high).coerceIn(plot.top, plot.bottom)
                val bottom = y(low).coerceIn(plot.top, plot.bottom)
                drawRect(tint, Offset(plot.left, top), Size(plot.width, (bottom - top).coerceAtLeast(0f)))
                val caption = measurer.measure(bandCaption, captionStyle)
                if (bottom - top > caption.size.height * 1.4f) {
                    drawText(caption, topLeft = Offset(plot.left + gap, (top + bottom) / 2f - caption.size.height / 2f))
                }
            }
            val dashes = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            listOfNotNull(high, low).forEach { value ->
                val at = y(value)
                if (at in plot.top..plot.bottom) {
                    drawLine(edge, Offset(plot.left, at), Offset(plot.right, at), 1.dp.toPx(), pathEffect = dashes)
                }
            }
            yLabels.forEach { (value, label) ->
                val at = y(value)
                // A hair of slack at both ends: the topmost figure of the ladder lands exactly on
                // the frame, and in float arithmetic "exactly" is sometimes a fraction outside it.
                if (at in (plot.top - 1f)..(plot.bottom + 1f)) {
                    drawText(label, topLeft = Offset(plot.left - gap - label.size.width, at - label.size.height / 2f))
                }
            }

            // The dated marks, at a step chosen from what the width can print without collisions.
            val widest = measurer.measure(
                Instant.ofEpochMilli(window.endMs).atZone(zone).toLocalDate().format(dateFormat),
                labelStyle,
            )
            val slot = widest.size.width + gap * 2f
            val ticks = labChartTicks(window, zone, (plot.width / slot).toInt())
            ticks.forEach { at ->
                val px = x(at)
                drawLine(grid.copy(alpha = GRID_ALPHA), Offset(px, plot.top), Offset(px, plot.bottom), 1f)
                val text = measurer.measure(
                    Instant.ofEpochMilli(at).atZone(zone).toLocalDate().format(dateFormat),
                    labelStyle,
                )
                // Never an inverted range: on a plot narrower than one date the two bounds cross.
                val left = (px - text.size.width / 2f)
                    .coerceIn(plot.left, maxOf(plot.left, plot.right - text.size.width))
                drawText(text, topLeft = Offset(left, plot.bottom + gap))
            }

            if (points.isEmpty()) return@Canvas
            val curve = Path()
            points.forEachIndexed { index, (at, value) ->
                val px = x(at)
                val py = y(value)
                if (index == 0) {
                    curve.moveTo(px, py)
                } else {
                    val (previousAt, previousValue) = points[index - 1]
                    val midX = (x(previousAt) + px) / 2f
                    curve.cubicTo(midX, y(previousValue), midX, py, px, py)
                }
            }
            // Clipped to the plot: a curve fed by a neighbour off the edge runs past the frame,
            // and the gutter under it is where the figures up the side are printed.
            clipRect(plot.left, plot.top, plot.right, plot.bottom) {
                drawPath(curve, line, style = Stroke(width = 2.5.dp.toPx() / 2f, cap = StrokeCap.Round))
            }
            // Each dot in its own verdict's ink: the band drawn is the newest reading's, and an older
            // one read against another — after food, at another age — can sit inside it and be outside
            // its own, or the other way round.
            points.forEach { point ->
                val px = x(point.at)
                val ink = if (point.outside) outsideDot else dot
                if (px in plot.left..plot.right) drawCircle(ink, 3.5.dp.toPx() / 2f, Offset(px, y(point.value)))
            }

            // The figures themselves, where they fit. The newest one is always printed, in a filled
            // chip: it is the number the card's headline already says, and the dot it belongs to is
            // what says when it was taken.
            //
            // Drawn from the newest backwards, so the one figure that is always printed is the one
            // that gets the room: two panels drawn a day apart put their dots within a few pixels
            // of each other, and a chip for the older of them would be printed through the newer.
            val chips = points.map { (at, _, figure) -> at to measurer.measure(figure, valueStyle) }
            var taken = plot.right + gap
            chips.indices.reversed().forEach { index ->
                val (at, text) = chips[index]
                val last = index == chips.lastIndex && at == readings.last().occurredAt
                val px = x(at)
                if (px !in plot.left..plot.right) return@forEach
                if (!last && px + text.size.width / 2f + gap > taken) return@forEach
                taken = px - text.size.width / 2f
                drawValueChip(text, px, y(points[index].value), plot, gap, last, latestChip, latestInk)
            }
        }
    }
}

/** The pill a figure stands in over its dot, filled for the newest reading and bare for the rest. */
private fun DrawScope.drawValueChip(
    text: TextLayoutResult,
    px: Float,
    py: Float,
    plot: Rect,
    gap: Float,
    filled: Boolean,
    fill: Color,
    ink: Color,
) {
    val padding = gap * 0.75f
    val width = text.size.width + padding * 2f
    val height = text.size.height + padding
    val left = (px - width / 2f).coerceIn(plot.left, (plot.right - width).coerceAtLeast(plot.left))
    val top = (py - height - gap).coerceAtLeast(0f)
    if (filled) {
        drawRoundRect(
            fill,
            Offset(left, top),
            Size(width, height),
            androidx.compose.ui.geometry.CornerRadius(height / 2f),
        )
    }
    drawText(
        text,
        topLeft = Offset(left + padding, top + padding / 2f),
        color = if (filled) ink else Color.Unspecified,
    )
}

/** A reading where the chart draws it: when, on the profile's scale, as printed, and its own verdict. */
private data class LabChartDot(val at: Long, val value: Double, val figure: String, val outside: Boolean)

/** The floor and ceiling a chart is drawn between: the readings and the band, with air around them. */
internal fun labValueScale(values: List<Double>, low: Double?, high: Double?): Pair<Double, Double> {
    val all = values + listOfNotNull(low, high)
    if (all.isEmpty()) return 0.0 to 1.0
    val floor = all.min()
    val ceiling = all.max()
    val span = (ceiling - floor).takeIf { it > 0.0 } ?: (ceiling.takeIf { it > 0.0 } ?: 1.0)
    return floor - span * SCALE_AIR to ceiling + span * SCALE_AIR
}

/**
 * The figures up the side of a full-screen chart: round steps inside its floor and its ceiling.
 *
 * Divided evenly rather than rounded, a ladder over 4.87 to 5.53 prints 4.9, 5.1, 5.2, 5.3, 5.5 —
 * an axis climbing by two tenths, then one, then one, then two, which is the arithmetic a chart is
 * there to spare the reader. The step is taken from the 1-2-5 ladder instead, and the rungs land on
 * multiples of it.
 */
internal fun labValueLadder(scale: Pair<Double, Double>): List<Double> {
    val (floor, ceiling) = scale
    val span = ceiling - floor
    if (span <= 0.0) return listOf(floor)
    val rough = span / LADDER_STEPS
    var decade = 10.0.pow(floor(log10(rough)))
    if (decade <= 0.0 || decade.isNaN()) decade = rough
    val step = LADDER_SCALE.map { it * decade }.firstOrNull { it >= rough } ?: (decade * 10.0)
    val first = ceil(floor / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= ceiling + step / 100.0 }.toList()
}

/**
 * The ladder's figures as the side of the chart prints them: to as many decimals as the step
 * between them needs. Through [pretty], which prints one, a ladder over a few hundredths of TSH would
 * read 0.0, 0.0, 0.1, 0.1.
 */
internal fun labLadderText(rungs: List<Double>): List<String> {
    val step = rungs.zipWithNext { lower, upper -> upper - lower }.firstOrNull()
    val places = step?.let {
        (0..LADDER_MAX_PLACES).firstOrNull { places ->
            val scaled = it * 10.0.pow(places)
            abs(scaled - scaled.roundToLong()) < LADDER_EPSILON
        }
    } ?: LADDER_MAX_PLACES
    return rungs.map {
        BigDecimal.valueOf(it).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}

/**
 * The finest the ladder prints. Two figures typed a thousandth apart, the finest the form takes, are
 * laddered in steps of five ten-thousandths, and four places print those apart.
 */
private const val LADDER_MAX_PLACES = 4

/** Float noise on a step, far under any step the ladder takes. */
private const val LADDER_EPSILON = 1e-6

/** The last few readings are what a summary is made of; a decade of them read out is not a summary. */
private const val SPOKEN_POINTS = 4

/** Strings a chart measures in one frame: the ladder, the dates under it and a chip per reading. */
private const val MEASURER_CACHE = 32

/** How much air above and below the figures, as a share of their own spread. */
private const val SCALE_AIR = 0.18

/** How many steps the ladder aims for, and the round sizes a step is allowed to take. */
private const val LADDER_STEPS = 4
private val LADDER_SCALE = listOf(1.0, 2.0, 2.5, 5.0, 10.0)

/** Room left over the plot for the chips the figures stand in. */
private const val CHIP_ROOM = 1.9f

/** The dated rulings are structure rather than data, and stay under everything drawn on them. */
private const val GRID_ALPHA = 0.5f

/** The gap between a plot and the text around it. */
private val AXIS_GAP = 6.dp

/**
 * The green a band is tinted with where every reading in it landed inside, here and on the vitals'
 * strip, so the two pages grade in one green.
 *
 * A fill rather than a token: it is laid at 14% over the card, where the app's own green would come
 * out as a grey. Its edge is drawn in the
 * app's green, which carries the contrast the fill does not have to.
 */
internal val IN_BAND_TINT = Color(0xFF2FA968)
