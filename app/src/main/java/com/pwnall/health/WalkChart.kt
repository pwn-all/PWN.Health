package com.pwnall.health

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sign

// The walk page's chart: how high the ground was, or how fast the walk went, from its start to its
// end. One measure at a time behind a two-way switch, as a smooth line with the ground under it
// tinted in the theme's own colour. Every figure is worked out by [walkSeries], which knows nothing
// of Compose, so a JVM test reads what the chart will draw; the composables only place it.

/**
 * The walk's elevation or its speed along the way, behind a two-way switch, as a smooth area chart.
 *
 * [points] are the walk's fixes as the database holds them, rejected ones included: the chart keeps
 * the accepted ones itself. An empty list reads as a walk with nothing to draw and prints the line
 * that says so, so the page shows this once the fixes have loaded rather than while they load.
 */
@Composable
internal fun WalkProfile(points: List<TrackPointEntity>, imperial: Boolean, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val measure = WalkMeasure.entries[selected]
    // Held against the fixes: the page recomposes for its photographs, its shares and its map long
    // after the route has stopped changing, and this walks the whole recording.
    val series = remember(points, measure, imperial) { walkSeries(points, measure, imperial) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PwnSegmentedControl(
            options = listOf(
                stringResource(R.string.walk_chart_elevation) to Icons.Outlined.Terrain,
                stringResource(R.string.walk_chart_speed) to Icons.Outlined.Speed,
            ),
            selected = selected,
            onSelected = { selected = it },
        )
        if (series.isEmpty()) {
            val nothing = when (measure) {
                WalkMeasure.ELEVATION -> R.string.walk_chart_no_elevation
                WalkMeasure.SPEED -> R.string.walk_chart_no_speed
            }
            Text(stringResource(nothing), color = PwnMuted, style = MaterialTheme.typography.bodyMedium)
        } else {
            WalkProfileChart(series, measure, imperial)
        }
    }
}

/**
 * The curve over dashed rulings at the scale's ticks, the figures up the left and the unit once
 * above them. The curve and the ground under it draw in from the left each time the chart is shown
 * and each time the switch is turned, and then stay still: the figures are a measurement, and a
 * line that kept moving would ask to be read again.
 */
@Composable
private fun WalkProfileChart(series: List<Double>, measure: WalkMeasure, imperial: Boolean) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    // Speed stands on zero, so a stroll at half the pace of a brisk stretch is drawn half as high.
    // Elevation does not: a walk along a hillside high above the sea, drawn up from sea level, is a
    // flat line along the top, and its hills are the whole point.
    val scale = remember(series, measure, imperial) {
        when (measure) {
            WalkMeasure.ELEVATION -> elevationScale(series, imperial)
            WalkMeasure.SPEED -> valueScale(series, fromZero = true)
        }
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = PwnMuted)
    val figures = valueLabels(scale.ticks, locale).map { measurer.measure(it, style) }
    val unit = measurer.measure(stringResource(measure.unit(imperial)), style)
    val spoken = remember(series, measure, imperial, resources) { spokenProfile(resources, series, measure, imperial) }
    val curve = MaterialTheme.colorScheme.primary
    val grid = PwnBorder
    // Made afresh for each measure, with nothing drawn, rather than snapped back by the effect: the
    // effect starts only once the first frame after a switch is on the screen, and one kept from the
    // last measure would draw that frame with the whole of the new curve.
    val progress = remember(measure) { Animatable(0f) }
    LaunchedEffect(measure) {
        progress.animateTo(1f, tween(WALK_PROFILE_DRAW_IN_MS, easing = FastOutSlowInEasing))
    }
    Canvas(Modifier.fillMaxWidth().height(WALK_PROFILE_HEIGHT).semantics { contentDescription = spoken }) {
        val gap = WALK_PROFILE_GAP.toPx()
        // A figure is centred on its ruling, so half of one can stand over the plot's top or bottom.
        val halfFigure = (figures.maxOfOrNull { it.size.height } ?: 0) / 2f
        val plot = Rect(
            left = (figures.maxOfOrNull { it.size.width } ?: 0) + gap,
            top = unit.size.height + gap + halfFigure,
            right = size.width,
            bottom = size.height - halfFigure,
        )
        // Nothing to draw into before the page has given the chart its width.
        if (plot.width <= 0f || plot.height <= 0f) return@Canvas
        drawText(unit, topLeft = Offset.Zero)
        val dashes = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        scale.ticks.zip(figures).forEach { (tick, figure) ->
            val y = valueY(tick, scale, plot.top, plot.bottom)
            drawLine(grid, Offset(plot.left, y), Offset(plot.right, y), 1f, pathEffect = dashes)
            drawText(figure, topLeft = Offset(plot.left - gap - figure.size.width, y - figure.size.height / 2f))
        }
        val line = Path().apply {
            monotoneThrough(
                series.mapIndexed { index, value ->
                    val x = plot.left + plot.width * index / series.lastIndex
                    Offset(x, valueY(value, scale, plot.top, plot.bottom))
                },
            )
        }
        val ground = Path().apply {
            addPath(line)
            lineTo(plot.right, plot.bottom)
            lineTo(plot.left, plot.bottom)
            close()
        }
        // The progress is read here, in the drawing, so the animation redraws the chart without
        // composing it again.
        clipRect(plot.left, plot.top, plot.left + plot.width * progress.value, plot.bottom) {
            drawPath(
                ground,
                Brush.verticalGradient(
                    listOf(curve.copy(alpha = WALK_PROFILE_GROUND_ALPHA), Color.Transparent),
                    startY = plot.top,
                    endY = plot.bottom,
                ),
            )
            drawPath(line, curve, style = Stroke(width = WALK_PROFILE_STROKE.toPx(), cap = StrokeCap.Round))
        }
    }
}

/**
 * A smooth curve through [points], left to right, that never overshoots them: between two points it
 * stays between their heights, so no hill is drawn taller than its figures say.
 *
 * [throughMidpoints] keeps the same promise by laying every point flat, which suits the other charts'
 * point a day and not a few dozen figures along a hillside: the curve would go flat at every point
 * and twice as steep between them, and a steady climb would come out as a flight of stairs. Here a
 * point keeps the slope it is on, and only a top, a bottom or a level stretch is drawn flat.
 */
private fun Path.monotoneThrough(points: List<Offset>) {
    if (points.isEmpty()) return
    val tangents = monotoneTangents(points)
    moveTo(points.first().x, points.first().y)
    for (index in 1..points.lastIndex) {
        val from = points[index - 1]
        val to = points[index]
        // The cubic that leaves one point and reaches the next at their slopes, as Bézier controls.
        val third = (to.x - from.x) / 3f
        cubicTo(
            from.x + third,
            from.y + tangents[index - 1] * third,
            to.x - third,
            to.y - tangents[index] * third,
            to.x,
            to.y,
        )
    }
}

/**
 * The slope the curve through [points] has at each of them: Steffen's monotone cubic (1990), the
 * method d3 draws its "monotone" curves with. A point between a rise and a fall, or beside a level
 * stretch, is flat; any other takes the blend of the slopes either side of it, held to twice the
 * gentler of the two, which is what keeps the curve from overshooting. An end takes the slope of the
 * stretch it starts or finishes.
 */
internal fun monotoneTangents(points: List<Offset>): List<Float> {
    val slopes = points.zipWithNext { from, to -> (to.y - from.y) / (to.x - from.x) }
    return points.indices.map { index ->
        when (index) {
            0 -> slopes.firstOrNull() ?: 0f

            points.lastIndex -> slopes.last()

            else -> {
                val before = slopes[index - 1]
                val after = slopes[index]
                val left = points[index].x - points[index - 1].x
                val right = points[index + 1].x - points[index].x
                val blend = (before * right + after * left) / (left + right)
                (sign(before) + sign(after)) * minOf(abs(before), abs(after), abs(blend) / 2f)
            }
        }
    }
}

/**
 * The elevation axis: from a little under the lowest figure of [series] to a little over its highest,
 * never less than [WALK_PROFILE_LEAST_HEIGHT_M] apart, with [valueScale]'s ticks between.
 *
 * Left to find its own ends, [valueScale] never spans less than a tenth of the figures' size. That
 * suits a weight, and on a height it measures the walk against the sea it was walked above: the same
 * hills would fill most of the plot on a walk along a coast and a sliver of it on one in the
 * mountains, and an altitude measured from the ellipsoid rather than the sea would scale them again.
 * Ends that are given are kept as they are, so the hills are drawn the same size wherever they stand.
 * The least height keeps a flat walk flat: the few metres its altitude wandered by stay a few metres.
 */
internal fun elevationScale(series: List<Double>, imperial: Boolean): ValueScale {
    val low = series.min()
    val high = series.max()
    val least = heightValue(WALK_PROFILE_LEAST_HEIGHT_M, imperial)
    val reach = maxOf(high - low, least) / 2.0 * (1.0 + 2.0 * WALK_PROFILE_AIR)
    // A walk that never went below the sea is not drawn on an axis that does, as on the other charts.
    val floor = ((low + high) / 2.0 - reach).let { if (low >= 0.0) it.coerceAtLeast(0.0) else it }
    return valueScale(series, yFloor = floor, yCeiling = floor + 2.0 * reach)
}

/** The short unit printed once above the chart's figures. */
@StringRes
private fun WalkMeasure.unit(imperial: Boolean): Int = when (this) {
    WalkMeasure.ELEVATION -> if (imperial) R.string.walk_chart_unit_ft else R.string.walk_chart_unit_m
    WalkMeasure.SPEED -> if (imperial) R.string.walk_chart_unit_mph else R.string.walk_chart_unit_kmh
}

/**
 * The chart in one sentence, because a canvas publishes nothing a screen reader can reach: the
 * lowest and the highest of the curve for elevation, its average and its top for speed.
 *
 * Read off the drawn curve rather than the raw fixes, so what is heard is what is seen, and a single
 * fix that jumped is smoothed out of both. The average is the curve's own, taken step by step along
 * the way, so a stop at a crossing, which covers no ground, hardly moves it.
 */
private fun spokenProfile(resources: Resources, series: List<Double>, measure: WalkMeasure, imperial: Boolean): String =
    when (measure) {
        WalkMeasure.ELEVATION -> {
            val unit = resources.getString(measure.unit(imperial))
            fun height(value: Double) =
                resources.getString(R.string.report_value_unit, value.roundToLong().formatted(), unit)
            resources.getString(R.string.walk_chart_spoken_elevation, height(series.min()), height(series.max()))
        }

        WalkMeasure.SPEED -> {
            val speed = if (imperial) R.string.track_speed_value_mph else R.string.track_speed_value
            resources.getString(
                R.string.walk_chart_spoken_speed,
                resources.getString(speed, series.average()),
                resources.getString(speed, series.max()),
            )
        }
    }

/** What the walk's chart shows: the height of the ground, or how fast the walker went over it. */
internal enum class WalkMeasure { ELEVATION, SPEED }

/**
 * The walk's [measure] as the chart draws it: [WALK_PROFILE_STEPS] figures at even steps from the
 * first fix that carries the measure to the last, in the units the profile reads in — metres or feet
 * of elevation, km/h or mph. Empty when fewer than two accepted fixes carry it.
 *
 * The steps are steps of the ground the walk covered along the route as the map draws it, counted as
 * the walk's own distance is ([groundCovered]), so a climb sits under the stretch of the route it was
 * on whether it was walked quickly or slowly, and a stop, which covers none, takes no width however
 * long it lasted. A walk that covered almost none — a phone left on a table — is laid out by time
 * instead, on a clock that stood still while the recording was paused ([withoutPauses]); along
 * either axis a pause takes no width at all.
 *
 * Elevation is the barometer's where the phone has one and the satellites' where it does not, as on
 * the recording screen: the barometer follows a few metres of climb that a GNSS altitude loses in its
 * own noise. Speed is the one each fix reported.
 */
internal fun walkSeries(points: List<TrackPointEntity>, measure: WalkMeasure, imperial: Boolean): List<Double> {
    val fixes = withoutPauses(points.filter { it.accepted }.sortedBy { it.timestamp })
    val metres = groundCovered(fixes)
    val read = fixes.indices.mapNotNull { index -> fixes[index].reading(measure, imperial)?.let { index to it } }
    if (read.size < 2) return emptyList()
    val first = read.first().first
    val byDistance = metres[read.last().first] - metres[first] >= WALK_PROFILE_LEAST_METRES
    val readings = read.map { (index, value) ->
        val at = if (byDistance) metres[index] else (fixes[index].timestamp - fixes[first].timestamp).toDouble()
        at to value
    }
    return movingAverage(bucketMeans(readings, WALK_PROFILE_STEPS), WALK_PROFILE_SMOOTHING)
}

/**
 * How far along the walk each of [fixes] stands, in metres of ground covered: measured from an anchor
 * by [anchorStep], as the recording counts the walk's distance and [routeSplits] its kilometres, so
 * the chart is laid out along the same metres the walk's distance is printed in.
 *
 * Summed from each fix to the next instead, the metres would take in the zig-zag of the receiver's
 * noise and the wander of a phone standing still: a stop would take a stretch of the chart for as
 * long as it lasted, and a phone left on a table would be laid out along metres it never went. Here
 * a fix within the noise of the anchor, or one whose movement was not yet decided, stands where the
 * anchor does, and a stop's wander is dropped, so all of a stop's fixes stand at one place. The
 * first fix after a pause starts the anchor again where it stands, as the recording does: whatever
 * ground the walker covered while it stood paused is not known, and none of it is counted.
 */
private fun groundCovered(fixes: List<TrackPointEntity>): List<Double> {
    var anchor = fixes.firstOrNull() ?: return emptyList()
    var metres = 0.0
    return fixes.map { fix ->
        // Haversine needs no unwrapping across the antimeridian: it reads the longitudes only through
        // the squared sine of half their difference, which a whole turn does not change.
        val leg = haversineMetres(anchor.drawnAt, fix.drawnAt)
        val step = if (fix.segmentStart) {
            AnchorStep.DISCARD
        } else {
            anchorStep(leg, fix.accuracyM?.toDouble() ?: 0.0, fix.movement())
        }
        when (step) {
            AnchorStep.CREDIT -> {
                metres += leg
                anchor = fix
            }

            AnchorStep.DISCARD -> anchor = fix

            AnchorStep.HOLD -> Unit
        }
        metres
    }
}

/** Where the map draws this fix: the corrected position when there is one. */
private val TrackPointEntity.drawnAt: GeoPoint get() = GeoPoint(correctedLat ?: lat, correctedLon ?: lon)

/** This fix's [measure] in the profile's units, or null where the fix did not carry it. */
private fun TrackPointEntity.reading(measure: WalkMeasure, imperial: Boolean): Double? = when (measure) {
    WalkMeasure.ELEVATION -> (barometricAltitudeM?.toDouble() ?: altitudeM)?.let { heightValue(it, imperial) }

    // Metres a second times the seconds in an hour is metres an hour, and kilometres or miles out of
    // that is the question the distance figures already answer.
    WalkMeasure.SPEED -> speedMps?.let { distanceValue(it * HOUR_SECONDS, imperial) }
}?.takeIf(Double::isFinite)

/**
 * [readings] — a position along the way and the figure read there — as [steps] figures at even
 * steps from the first position to the last: the mean of the readings that fall in each step.
 *
 * A step no reading fell in lies on the straight line between its nearest neighbours that have one,
 * rather than dropping to zero or breaking the curve: a tunnel, or a stretch where the receiver
 * lost the sky, was still walked through. Empty when every reading is at one position.
 */
internal fun bucketMeans(readings: List<Pair<Double, Double>>, steps: Int): List<Double> {
    val from = readings.minOfOrNull { it.first } ?: 0.0
    val span = (readings.maxOfOrNull { it.first } ?: 0.0) - from
    if (span <= 0.0) return emptyList()
    val sums = DoubleArray(steps)
    val counts = IntArray(steps)
    readings.forEach { (at, value) ->
        // The last reading lands exactly on the far edge, which belongs to the last step.
        val step = ((at - from) / span * steps).toInt().coerceAtMost(steps - 1)
        sums[step] += value
        counts[step]++
    }
    // The first step holds the first reading and the last step the last, so every empty step has a
    // filled one on either side to be drawn between.
    val means = DoubleArray(steps)
    var filled = 0
    for (step in 0 until steps) {
        if (counts[step] == 0) continue
        means[step] = sums[step] / counts[step]
        for (gap in filled + 1 until step) {
            means[gap] = means[filled] + (means[step] - means[filled]) * (gap - filled) / (step - filled)
        }
        filled = step
    }
    return means.toList()
}

/**
 * A centred moving average over [window] figures, the window shrinking near the ends so it stays
 * centred: the first and last figures are kept as they are, and a straight stretch stays straight
 * all the way to the edge. Light on purpose — it takes the step-to-step jitter off the curve and
 * leaves the hills — and it can only flatten: a constant stays constant and a climb stays a climb.
 */
internal fun movingAverage(values: List<Double>, window: Int): List<Double> = values.indices.map { index ->
    val reach = minOf(window / 2, index, values.lastIndex - index)
    values.subList(index - reach, index + reach + 1).average()
}

/**
 * How many figures the curve is drawn through: enough to give a walk's climbs and descents their
 * shape on a phone-wide chart, few enough that on a walk of a usual length each is the mean of a
 * stretch of fixes rather than one fix's noise.
 */
internal const val WALK_PROFILE_STEPS = 48

/** How many neighbouring figures the light smoothing averages, the figure itself in the middle. */
internal const val WALK_PROFILE_SMOOTHING = 5

/**
 * Under this many metres of ground covered between the first and the last reading the walk is laid
 * out by time. A phone that went nowhere covers none, or only the odd few metres of drift that the
 * stop detector let through, and spaced along those the readings would be bunched by noise rather
 * than by anything the walker did, or have no distance to be laid along at all.
 */
internal const val WALK_PROFILE_LEAST_METRES = 50.0

/**
 * The least height the elevation axis spans, air aside, in metres; a design choice. A walk whose
 * lowest and highest points are this far apart or more is drawn as tall as the plot goes, and one
 * whose are closer is drawn to this scale, so the wander of an altitude on a flat walk is a ripple
 * along the curve rather than hills from the bottom of the plot to the top.
 */
internal const val WALK_PROFILE_LEAST_HEIGHT_M = 20.0

/**
 * The room left over the highest elevation and under the lowest, as a share of the height the axis
 * spans without it, so the curve's tops and bottoms do not sit on the plot's edges.
 */
private const val WALK_PROFILE_AIR = 0.08

private const val HOUR_SECONDS = 3_600.0

/** The chart's height, its figures and unit included. */
private val WALK_PROFILE_HEIGHT = 180.dp

/** The room between a figure and the plot it labels, and under the unit. */
private val WALK_PROFILE_GAP = 6.dp

private val WALK_PROFILE_STROKE = 2.5.dp

/** How strongly the ground under the curve is tinted at the top; it fades to nothing at the bottom. */
private const val WALK_PROFILE_GROUND_ALPHA = 0.35f

private const val WALK_PROFILE_DRAW_IN_MS = 700
