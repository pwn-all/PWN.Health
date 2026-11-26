package com.pwnall.health

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * The map is always seen tilted, from above and behind, turned the way the phone faces — a compass
 * card under the walk. The ground is [GroundShading]'s, drawn on the GPU; what is here is the camera
 * that both it and the CPU draw with, and the few things the CPU still draws through it: the walk at
 * its own height on stems down to its shadow, the rivers as rows of dots, the phone and the photos.
 */
@Stable
internal class MapTilt {
    /** Which way the camera looks, in degrees clockwise from true north. */
    var heading by mutableFloatStateOf(0f)

    /**
     * How far above the horizon the camera looks down at the middle, in degrees: from [MIN_PITCH],
     * just above the ground, to [MAX_PITCH], nearly overhead but never straight down on the walk.
     */
    var pitch by mutableFloatStateOf(DEFAULT_PITCH)

    /** Whether the phone's compass turns the map; without one, two fingers do. */
    var compass by mutableStateOf(false)

    /**
     * Where the phone faces, in degrees clockwise from true north, once the compass has read it. The
     * arrow on the position points there; while the camera follows, the map turns to it as well.
     */
    var facing by mutableStateOf<Float?>(null)

    companion object {
        /** Kept across a rotation of the phone, as the camera is. */
        val Saver: Saver<MapTilt, Any> = listSaver(
            save = { listOf<Any>(it.heading, it.pitch) },
            restore = { saved ->
                MapTilt().apply {
                    heading = saved[0] as Float
                    pitch = (saved.getOrNull(1) as? Float ?: DEFAULT_PITCH).coerceIn(MIN_PITCH, MAX_PITCH)
                }
            },
        )
    }
}

/**
 * The tilted camera for one frame. It looks at the map point [centerX], [centerY] — the middle of the
 * view, on screen at [middleX], [middleY] — toward the heading, from [distance] pixels away along a
 * line of sight [pitch] degrees below the horizon; a map unit there is [zoom] pixels, as on the flat
 * map. Heights are drawn [metrePx] pixels a metre, counted from [baseM], the ground under the middle.
 * [closestZoom] is as near as the camera may come, in the same pixels a unit as [zoom].
 *
 * In [project] and in the ground shader, positions are pixels at the middle, east and south of it.
 */
internal class TiltCamera(
    val centerX: Double,
    val centerY: Double,
    val zoom: Double,
    heading: Float,
    pitch: Float,
    size: Size,
    val metrePx: Float,
    val baseM: Float,
    val closestZoom: Double = zoom,
) {
    val sinHeading = sin(heading * PI / HALF_TURN).toFloat()
    val cosHeading = cos(heading * PI / HALF_TURN).toFloat()

    // The line of sight's angle from straight down, as the shader takes it: its sine and cosine.
    val sinTilt = cos(pitch * PI / HALF_TURN).toFloat()
    val cosTilt = sin(pitch * PI / HALF_TURN).toFloat()
    val distance = DISTANCE_SIDES * maxOf(size.height, size.width)
    val middleX = size.width / 2
    val middleY = size.height * MIDDLE_SHARE

    /**
     * Writes where map point [x], [y] standing [heightM] metres high lands on screen, and its nearness
     * — the middle's depth over its own — into [out]; false where it is behind the camera or too near it.
     */
    fun project(x: Double, y: Double, heightM: Float, out: FloatArray): Boolean {
        val east = ((x - centerX) * zoom).toFloat() + distance * sinTilt * sinHeading
        val south = ((y - centerY) * zoom).toFloat() - distance * sinTilt * cosHeading
        val up = (heightM - baseM) * metrePx - distance * cosTilt
        val ahead = east * sinHeading - south * cosHeading
        val across = east * cosHeading + south * sinHeading
        val depth = sinTilt * ahead - cosTilt * up
        if (depth < distance * NEAREST_SHARE) return false
        val rise = cosTilt * ahead + sinTilt * up
        out[0] = middleX + distance * across / depth
        out[1] = middleY - distance * rise / depth
        out[2] = distance / depth
        return true
    }

    /** The map point on level ground at the middle's height under pixel [px], [py]; null above the horizon. */
    fun groundUnder(px: Float, py: Float): Offset2? {
        val u = (px - middleX) / distance
        val v = (py - middleY) / distance
        val wz = -cosTilt - v * sinTilt
        if (wz > -HORIZON_GRAZE) return null
        val forward = sinTilt - v * cosTilt
        val wEast = forward * sinHeading + u * cosHeading
        val wSouth = -forward * cosHeading + u * sinHeading
        val t = distance * cosTilt / -wz
        val east = -distance * sinTilt * sinHeading + t * wEast
        val south = distance * sinTilt * cosHeading + t * wSouth
        return Offset2(centerX + east / zoom, centerY + south / zoom)
    }
}

/**
 * The camera for [camera]'s view of a canvas [size], turned and tipped as [tilt] says: the ground under the middle
 * from [relief], and its heights raised until the view's relief stands about [RELIEF_SHARE] of the
 * view's width, at most [MAX_EXAGGERATION] times — a plain needs it to show at all, a mountain range
 * none.
 */
internal fun tiltCamera(camera: MapCamera, tilt: MapTilt, size: Size, relief: ReliefImage?): TiltCamera {
    val base = relief?.cells?.heightAt(camera.centerX, camera.centerY) ?: 0f
    val unitsPerMetre = mapUnitsFor(1.0, camera.centerY)
    val viewMetres = size.width / camera.pixelsPerUnit / unitsPerMetre
    val span = maxOf(relief?.heightSpan ?: 1f, 1f)
    val exaggeration = (RELIEF_SHARE * viewMetres / span).toFloat().coerceIn(1f, MAX_EXAGGERATION)
    val metrePx = (unitsPerMetre * camera.pixelsPerUnit).toFloat() * exaggeration
    return TiltCamera(
        camera.centerX,
        camera.centerY,
        camera.pixelsPerUnit,
        tilt.heading,
        tilt.pitch,
        size,
        metrePx,
        base,
        camera.closest,
    )
}

/** The part of the world the tilted camera can see from the middle of [this] view, a margin included. */
internal fun Rect.tiltReach(): Rect = inflate(width * TILT_REACH_WIDTHS)

/**
 * How far past [this] limit, where the camera may go, a tilted [view] still has ground to show: a
 * view's length. Looking low across the ground the camera sees almost a screen's height behind its
 * middle, and from the edge of the limit that would be paper under the bottom of the screen.
 */
internal fun Rect.tiltLimit(view: Rect): Rect = inflate(maxOf(view.width, view.height))

/**
 * The ground's height under each fix of [route], from [heights]: what [drawRouteTilted] stands the walk
 * on. Worked out once for a route and a relief, not on every frame the compass turns the map.
 */
internal fun routeFloors(route: DoubleArray, heights: ReliefCells?): FloatArray? =
    heights?.let { FloatArray(route.size / 2) { index -> it.heightAt(route[index * 2], route[index * 2 + 1]) } }

/**
 * How many times higher than the ground the walk's own climb is drawn. The ground is raised for the
 * relief round the whole view, and beside a hill ten kilometres off a walk's forty-metre climb would
 * stand a pair of pixels high. So the walk's [climbM] is raised on its own until it stands a fifth of the
 * walk's [walkM] across, at most [MAX_EXAGGERATION] times, while the [viewM]-wide view is up to twice
 * the walk; by six times the walk it is raised no more than the ground's [groundExaggeration], and a
 * far view shows the hills and mountains alone. A climb under [MIN_CLIMB_M] is taken as that much: the
 * rest is the barometer's noise as much as the ground.
 */
internal fun routeBoost(climbM: Float, walkM: Double, viewM: Double, groundExaggeration: Float): Float {
    if (walkM <= 0.0 || viewM <= 0.0 || groundExaggeration <= 0f) return 1f
    val full = (ROUTE_RISE_SHARE * walkM / maxOf(climbM, MIN_CLIMB_M)).toFloat().coerceIn(1f, MAX_EXAGGERATION)
    val fade = (ln(ROUTE_RISE_GONE * walkM / viewM) / ln(ROUTE_RISE_GONE / ROUTE_RISE_FULL)).toFloat()
    val wanted = 1f + (full - 1f) * fade.coerceIn(0f, 1f)
    return maxOf(wanted / groundExaggeration, 1f)
}

/**
 * The size a walk's climb is measured against: the walk's own [walkM] or half the [closestViewM] the
 * map can come down to, the larger. A walk of a hundred metres seen as close as the map goes still
 * fills a seventh of the view, so against its own size its climb would never be raised.
 */
internal fun riseSize(walkM: Double, closestViewM: Double): Double = maxOf(walkM, closestViewM / ROUTE_RISE_FULL)

/** [routeBoost] for [lift] along [route] as [camera] shows it. */
private fun routeBoost(lift: FloatArray, route: DoubleArray, camera: TiltCamera): Float {
    var west = Double.MAX_VALUE
    var east = -Double.MAX_VALUE
    var north = Double.MAX_VALUE
    var south = -Double.MAX_VALUE
    for (index in 0 until route.size / 2) {
        west = minOf(west, route[index * 2])
        east = maxOf(east, route[index * 2])
        north = minOf(north, route[index * 2 + 1])
        south = maxOf(south, route[index * 2 + 1])
    }
    val unitsPerMetre = mapUnitsFor(1.0, camera.centerY)
    val ground = camera.metrePx / (unitsPerMetre * camera.zoom).toFloat()
    val climb = (lift.maxOrNull() ?: 0f) - (lift.minOrNull() ?: 0f)
    val viewM = 2 * camera.middleX / camera.zoom / unitsPerMetre
    val closestViewM = 2 * camera.middleX / camera.closestZoom / unitsPerMetre
    return routeBoost(climb, riseSize(maxOf(east - west, south - north) / unitsPerMetre, closestViewM), viewM, ground)
}

/**
 * The walk tilted: its shadow on the ground — [floors], from [routeFloors] — a pale wall up from it
 * and a stem every so often, the line itself at its own height — [lift], metres above its first fix,
 * raised by [routeBoost] — in its own colours, and its stops and two ends.
 */
internal fun DrawScope.drawRouteTilted(
    points: List<TrackPointEntity>,
    route: DoubleArray,
    lift: FloatArray,
    floors: FloatArray?,
    camera: TiltCamera,
    ink: RouteInk,
    style: MapStyle,
) {
    val count = route.size / 2
    if (count == 0 || lift.size != count) return
    val ground = floors?.takeIf { it.size == count }
    val start = ground?.get(0) ?: camera.baseM
    val boost = routeBoost(lift, route, camera)
    val air = FloatArray(count * 2)
    val shadow = FloatArray(count * 2)
    val shown = BooleanArray(count)
    val point = FloatArray(3)
    for (index in 0 until count) {
        val x = route[index * 2]
        val y = route[index * 2 + 1]
        val floor = ground?.get(index) ?: camera.baseM
        val height = maxOf(start + lift[index] * boost, floor) + ROUTE_LIFT_PX / camera.metrePx
        if (!camera.project(x, y, floor, point)) continue
        shadow[index * 2] = point[0]
        shadow[index * 2 + 1] = point[1]
        shown[index] = camera.project(x, y, height, point)
        air[index * 2] = point[0]
        air[index * 2 + 1] = point[1]
    }
    polyline(shadow, shown, points, Color.Black.copy(alpha = SHADOW_ALPHA), SHADOW_WIDTH.toPx())
    wall(air, shadow, shown, points, style)
    stems(air, shadow, shown, style)
    // The ground's own colour under the whole line, a little wider than it, as on the flat map: the
    // line owes its 3:1 to whatever it is drawn on, and over forest and rock that is the casing.
    polyline(air, shown, points, style.land, CASING_WIDTH.toPx())
    inkedLine(air, shown, points, ink)
    marks(air, shown, points, style)
}

/** The line through [at], in runs of one ink, broken where [shown] is false or the walk was paused. */
private fun DrawScope.inkedLine(at: FloatArray, shown: BooleanArray, points: List<TrackPointEntity>, ink: RouteInk) {
    var from = 0
    while (from < shown.size) {
        var to = from + 1
        while (to < shown.size && ink.colors[to] == ink.colors[from]) to++
        val path = Path()
        var open = false
        for (index in maxOf(from - 1, 0) until minOf(to + 1, shown.size)) {
            val joined = open && joinsPrevious(points, index)
            when {
                !shown[index] -> open = false

                joined -> path.lineTo(at[index * 2], at[index * 2 + 1])

                else -> {
                    path.moveTo(at[index * 2], at[index * 2 + 1])
                    open = true
                }
            }
        }
        drawPath(path, Color(ink.colors[from]), style = Stroke(ink.widths[from] * density, cap = StrokeCap.Round))
        from = to
    }
}

private fun DrawScope.polyline(
    at: FloatArray,
    shown: BooleanArray,
    points: List<TrackPointEntity>,
    color: Color,
    width: Float,
) {
    val path = Path()
    var open = false
    for (index in shown.indices) {
        when {
            !shown[index] -> open = false

            open && joinsPrevious(points, index) -> path.lineTo(at[index * 2], at[index * 2 + 1])

            else -> {
                path.moveTo(at[index * 2], at[index * 2 + 1])
                open = true
            }
        }
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

/**
 * A pale wall from the line down to its shadow, so a climb reads as one from any side. Each piece is
 * wound the same way round, so the pieces that overlap where the wall folds fill once, not twice; and
 * each reaches a hair into the next, see [wallPiece].
 */
private fun DrawScope.wall(
    air: FloatArray,
    shadow: FloatArray,
    shown: BooleanArray,
    points: List<TrackPointEntity>,
    style: MapStyle,
) {
    val path = Path()
    val xs = FloatArray(WALL_CORNERS)
    val ys = FloatArray(WALL_CORNERS)
    for (index in 1 until shown.size) {
        if (!shown[index - 1] || !shown[index] || !joinsPrevious(points, index)) continue
        wallPiece(air, shadow, index, xs, ys)
        var twice = 0f
        for (corner in 0 until WALL_CORNERS) {
            val next = (corner + 1) % WALL_CORNERS
            twice += xs[corner] * ys[next] - xs[next] * ys[corner]
        }
        for (step in 0 until WALL_CORNERS) {
            val corner = if (twice >= 0f) step else (WALL_CORNERS - step) % WALL_CORNERS
            if (step == 0) path.moveTo(xs[corner], ys[corner]) else path.lineTo(xs[corner], ys[corner])
        }
        path.close()
    }
    drawPath(path, style.walk.copy(alpha = WALL_ALPHA))
}

/**
 * The corners of the wall's piece that ends at fix [index], into [xs] and [ys]: the shadow's two ends,
 * then the line's, going back — the far end pushed [WALL_OVERLAP_PX] on along the walk, into the next
 * piece. Android rasterises an anti-aliased path this large on the CPU, every frame, and where two
 * pieces only touch, Skia takes each as a span of its own and walks the row again for every one.
 * From the side hundreds of tall pieces stand in each row: so drawn, the wall alone costs about 43 ms
 * of CPU a frame on an emulator, 60 ms or more at some headings, and turning the map stutters.
 * Overlapping, the pieces join into one span, and the same wall costs 4 ms; a twentieth of a pixel
 * does not show.
 */
internal fun wallPiece(air: FloatArray, shadow: FloatArray, index: Int, xs: FloatArray, ys: FloatArray) {
    xs[0] = shadow[index * 2 - 2]
    ys[0] = shadow[index * 2 - 1]
    xs[1] = shadow[index * 2]
    ys[1] = shadow[index * 2 + 1]
    xs[2] = air[index * 2]
    ys[2] = air[index * 2 + 1]
    xs[3] = air[index * 2 - 2]
    ys[3] = air[index * 2 - 1]
    val alongX = xs[1] - xs[0]
    val alongY = ys[1] - ys[0]
    val length = hypot(alongX, alongY)
    if (length <= 0f) return
    val pushX = alongX / length * WALL_OVERLAP_PX
    val pushY = alongY / length * WALL_OVERLAP_PX
    xs[1] += pushX
    ys[1] += pushY
    xs[2] += pushX
    ys[2] += pushY
}

private fun DrawScope.stems(air: FloatArray, shadow: FloatArray, shown: BooleanArray, style: MapStyle) {
    val gap = STEM_GAP.toPx()
    var lastX = Float.NaN
    var lastY = Float.NaN
    for (index in shown.indices) {
        val x = air[index * 2]
        val y = air[index * 2 + 1]
        val apart = lastX.isNaN() || abs(x - lastX) >= gap || abs(y - lastY) >= gap
        if (!shown[index] || !apart) continue
        drawLine(
            style.walk.copy(alpha = STEM_ALPHA),
            Offset(shadow[index * 2], shadow[index * 2 + 1]),
            Offset(x, y),
            STEM_WIDTH.toPx(),
        )
        lastX = x
        lastY = y
    }
}

/** The stops, a dot each, and the walk's two ends, as the flat map marks them. */
private fun DrawScope.marks(air: FloatArray, shown: BooleanArray, points: List<TrackPointEntity>, style: MapStyle) {
    var last = Offset.Infinite
    for (index in shown.indices) {
        val at = Offset(air[index * 2], air[index * 2 + 1])
        val stop = shown[index] && points[index].movementType == RouteMovement.STOPPED.name
        if (!stop || (abs(at.x - last.x) < 1f && abs(at.y - last.y) < 1f)) continue
        drawCircle(style.stopped, radius = STOP_RADIUS.toPx(), center = at)
        last = at
    }
    val first = shown.indexOfFirst { it }
    val final = shown.indexOfLast { it }
    if (first >= 0) {
        drawCircle(style.start, radius = END_RADIUS.toPx(), center = Offset(air[first * 2], air[first * 2 + 1]))
    }
    if (final > first && final == shown.lastIndex) {
        drawCircle(style.end, radius = END_RADIUS.toPx(), center = Offset(air[final * 2], air[final * 2 + 1]))
    }
}

/**
 * The rivers and streams across the tilted ground, rows of dots as on the flat map, at the ground's
 * height, as far out as [ground] reaches: past its edge the ground fades into the paper, and a river
 * drawn on beyond it would run across the sky.
 */
internal fun DrawScope.drawRiversTilted(
    level: MapLevel,
    camera: TiltCamera,
    heights: ReliefCells?,
    pens: List<MapPen>,
    ground: GroundImage,
) {
    val zoom = DEGREES_PER_TURN / camera.zoom
    val reach = ground.covers
    val fog = (minOf(reach.width, reach.height) / 2 * camera.zoom * RIVER_FOG_SHARE).toFloat()
    val point = FloatArray(3)
    pens.forEach { pen ->
        if (zoom > pen.upTo || zoom <= pen.from) return@forEach
        val path = Path()
        level.layers.getOrNull(pen.layer)?.forEach { line ->
            val inReachX = line.maxX >= reach.left && line.minX <= reach.right
            val inReachY = line.maxY >= reach.top && line.minY <= reach.bottom
            if (inReachX && inReachY) path.addTilted(line, camera, heights, fog, point)
        }
        val dotted = PathEffect.dashPathEffect(floatArrayOf(DOT_DASH_PX, pen.dots.toPx()))
        drawPath(path, pen.color, style = Stroke(pen.width.toPx(), cap = StrokeCap.Round, pathEffect = dotted))
    }
}

/** [line] on the tilted ground, broken where it runs out past [fog] pixels from the middle or behind the eye. */
private fun Path.addTilted(line: MapLine, camera: TiltCamera, heights: ReliefCells?, fog: Float, point: FloatArray) {
    var open = false
    for (index in 0 until line.points.size / 2) {
        val x = line.points[index * 2].toDouble()
        val y = line.points[index * 2 + 1].toDouble()
        val out = ((x - camera.centerX) * camera.zoom).toFloat()
        val down = ((y - camera.centerY) * camera.zoom).toFloat()
        val seen = hypot(out, down) < fog && camera.project(x, y, heights?.heightAt(x, y) ?: camera.baseM, point)
        when {
            !seen -> open = false

            open -> lineTo(point[0], point[1])

            else -> {
                moveTo(point[0], point[1])
                open = true
            }
        }
    }
}

/**
 * Where the phone is, on the tilted map: a disc on the ground ringed in the ground's colour, and, once
 * the compass has read where the phone faces, an arrow out of it pointing that way — [facing], degrees
 * from true north.
 */
internal fun DrawScope.drawPositionTilted(
    fix: PhoneFix,
    origin: Double,
    camera: TiltCamera,
    heights: ReliefCells?,
    fill: Color,
    ring: Color,
    facing: Float?,
) {
    val x = mercatorX(unwrapLongitude(fix.lon, origin))
    val y = mercatorY(fix.lat)
    val height = heights?.heightAt(x, y) ?: camera.baseM
    val point = FloatArray(3)
    if (!camera.project(x, y, height, point)) return
    val center = Offset(point[0], point[1])
    val disc = HERE_DISC.toPx()
    val rim = HERE_RIM.toPx()
    val arrow = facing?.let { camera.screenBearing(x, y, height, it) }?.let { pointer(center, it, disc) }
    arrow?.let { drawPath(it, ring, style = Stroke(rim * 2, join = StrokeJoin.Round)) }
    drawCircle(ring, radius = disc + rim, center = center)
    arrow?.let { drawPath(it, fill) }
    drawCircle(fill, radius = disc, center = center)
}

/**
 * Which way on screen the ground runs from [x], [y] toward [bearing], degrees from true north: a unit
 * vector, or null where the ground there is behind the eye.
 */
internal fun TiltCamera.screenBearing(x: Double, y: Double, heightM: Float, bearing: Float): Offset? {
    val step = BEARING_PROBE_PX / zoom
    val turn = Math.toRadians(bearing.toDouble())
    val from = FloatArray(3)
    val to = FloatArray(3)
    val ahead = project(x + sin(turn) * step, y - cos(turn) * step, heightM, to)
    if (!project(x, y, heightM, from) || !ahead) return null
    val across = to[0] - from[0]
    val down = to[1] - from[1]
    val length = hypot(across, down)
    return if (length < MIN_BEARING_PX) null else Offset(across / length, down / length)
}

/** The arrow out of a disc of radius [disc] at [center], pointing along the unit vector [toward]. */
private fun pointer(center: Offset, toward: Offset, disc: Float): Path {
    val tip = center + toward * (disc * POINTER_REACH)
    val base = center + toward * (disc * POINTER_BASE)
    val side = Offset(-toward.y, toward.x) * (disc * POINTER_HALF_WIDTH)
    return Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + side.x, base.y + side.y)
        lineTo(base.x - side.x, base.y - side.y)
        close()
    }
}

/**
 * Each photograph where it was taken, on the tilted ground: a disc in [fill] ringed in [ring], its
 * number in [ink] — the flat map's mark without the wedge, which a tilted ground would bend.
 */
internal fun DrawScope.drawPhotoMarksTilted(
    marks: List<PhotoMark>,
    labels: List<TextLayoutResult>,
    camera: TiltCamera,
    heights: ReliefCells?,
    fill: Color,
    ink: Color,
    ring: Color,
) {
    val point = FloatArray(3)
    marks.forEach { mark ->
        val label = labels.getOrNull(mark.number - 1) ?: return@forEach
        if (!camera.project(mark.x, mark.y, heights?.heightAt(mark.x, mark.y) ?: camera.baseM, point)) return@forEach
        val center = Offset(point[0], point[1])
        val radius = maxOf(PHOTO_DISC.toPx(), maxOf(label.size.width, label.size.height) / 2f + PHOTO_PADDING.toPx())
        drawCircle(ring, radius = radius + PHOTO_RIM.toPx(), center = center)
        drawCircle(fill, radius = radius, center = center)
        drawText(label, ink, center - Offset(label.size.width / 2f, label.size.height / 2f))
    }
}

/**
 * The walk's height above its first fix, in metres, fix by fix: the barometer's where the phone has
 * one, else the receiver's; a barometer's spikes and the receiver's wander taken out with a running
 * median and then a running mean, so a climb is a climb and not the needle of a gust of wind. A stop
 * does not climb: what the barometer drifts while the walker stands — the weather, a heated room — is
 * left out of every height after it. Raised with the climb by [routeBoost], that drift would stand a
 * stop up as a pillar.
 */
internal fun routeLift(points: List<TrackPointEntity>): FloatArray {
    if (points.isEmpty()) return FloatArray(0)
    val barometer = points.any { it.barometricAltitudeM != null }
    val raw = points.map { if (barometer) it.barometricAltitudeM?.toDouble() else it.altitudeM }
    val first = raw.firstOrNull { it != null } ?: return FloatArray(points.size)
    // A gap takes the last reading before it; a gap at the start, the first reading there is.
    var held: Double = first
    val filled = raw.map { value -> (value ?: held).also { held = it } }
    val smooth = movingAverage(runningMedian(filled, LIFT_MEDIAN), LIFT_MEAN)
    val lift = FloatArray(points.size)
    var drift = 0.0
    for (index in 1 until points.size) {
        if (points[index].movementType == RouteMovement.STOPPED.name) drift += smooth[index] - smooth[index - 1]
        lift[index] = (smooth[index] - smooth[0] - drift).toFloat()
    }
    return lift
}

/** Each value replaced by the median of the [window] values round it, the ends with what there is. */
internal fun runningMedian(values: List<Double>, window: Int): List<Double> {
    val half = window / 2
    return values.indices.map { index ->
        val around = values.subList(maxOf(0, index - half), minOf(values.size, index + half + 1)).sorted()
        around[around.size / 2]
    }
}

/**
 * Keeps [MapTilt.facing] on where the phone faces, and turns the map with it, as a compass card turns,
 * while [turnsMap] says so — while the camera follows; zoomed or dragged by hand the map holds still
 * and only the arrow on the position turns. Read while the map is on screen and the app is in front:
 * the rotation sensor where there is one, else the one built from the compass and the accelerometer
 * alone, true north by [declinationDeg]. With neither the map stays turned by hand, and
 * [MapTilt.compass] says so.
 */
@Composable
internal fun FollowCompass(tilt: MapTilt, declinationDeg: Float, turnsMap: () -> Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val turns by rememberUpdatedState(turnsMap)
    DisposableEffect(context, lifecycle, tilt, declinationDeg) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        tilt.compass = manager != null && sensor != null
        if (manager == null || sensor == null) return@DisposableEffect onDispose { }
        val listener = CompassListener(tilt, declinationDeg, { turns() }) { view.display?.rotation ?: 0 }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                Lifecycle.Event.ON_PAUSE -> manager.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            manager.unregisterListener(listener)
        }
    }
}

/**
 * Where the phone faces, from a rotation matrix: the top of the screen, laid level, and the back
 * camera's line of sight, laid level, added together. Flat on a palm the camera looks at the floor and
 * the screen's top points ahead; held up the screen's top points at the sky and the camera ahead; in
 * between both lean the same way. [screenTurns] is how many quarter turns the display is rotated from
 * the phone's own upright. Degrees clockwise from magnetic north, or null where neither points anywhere.
 */
internal fun facingDeg(rotation: FloatArray, screenTurns: Int): Float? {
    // The phone's own axes in the world — east, north, up — are the matrix's columns.
    val (topEast, topNorth) = when (screenTurns and QUARTER_MASK) {
        1 -> rotation[0] to rotation[3]
        2 -> -rotation[1] to -rotation[4]
        3 -> -rotation[0] to -rotation[3]
        else -> rotation[1] to rotation[4]
    }
    val east = topEast - rotation[2]
    val north = topNorth - rotation[5]
    if (hypot(east, north) < FACING_LEVEL) return null
    return normalisedBearing(Math.toDegrees(atan2(east.toDouble(), north.toDouble())))
}

/** The rotation sensor's readings as a heading, smoothed, written to the map only once it has moved. */
private class CompassListener(
    private val tilt: MapTilt,
    private val declinationDeg: Float,
    private val turnsMap: () -> Boolean,
    private val screenTurns: () -> Int,
) : SensorEventListener {
    private val rotation = FloatArray(ROTATION_MATRIX_SIZE)
    private var east = Float.NaN
    private var north = Float.NaN

    override fun onSensorChanged(event: SensorEvent) {
        // Copied out at once, since the framework hands the same event back with the next reading.
        val vector = event.values.copyOf(minOf(event.values.size, QUATERNION_SIZE))
        SensorManager.getRotationMatrixFromVector(rotation, vector)
        val facing = facingDeg(rotation, screenTurns()) ?: return
        val turn = trueBearing(facing, declinationDeg) * PI / HALF_TURN
        if (east.isNaN()) {
            east = sin(turn).toFloat()
            north = cos(turn).toFloat()
        } else {
            east += (sin(turn).toFloat() - east) * COMPASS_SMOOTHING
            north += (cos(turn).toFloat() - north) * COMPASS_SMOOTHING
        }
        val heading = normalisedBearing(Math.toDegrees(atan2(east.toDouble(), north.toDouble())))
        val faced = tilt.facing
        if (faced == null || bearingGap(heading, faced) >= COMPASS_STEP_DEG) tilt.facing = heading
        if (turnsMap() && bearingGap(heading, tilt.heading) >= COMPASS_STEP_DEG) tilt.heading = heading
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}

/** How many degrees apart two bearings are, the short way round. */
internal fun bearingGap(a: Float, b: Float): Float {
    val gap = abs(a - b) % FULL_TURN_DEGREES
    return if (gap > HALF_TURN_DEGREES) FULL_TURN_DEGREES - gap else gap
}

/** How far magnetic north lies east of true north at [lat], [lon] today; 0 with nowhere to ask about. */
internal fun mapDeclination(lat: Double?, lon: Double?): Float =
    if (lat == null || lon == null) 0f else declinationAt(lat, lon, null, System.currentTimeMillis())

/** Rivers stop where the ground starts to fade into the paper: this share of the ground's half-width out. */
private const val RIVER_FOG_SHARE = 0.85

/**
 * The line of sight's angle below the horizon, in degrees: by default steep enough to read the ground
 * and low enough for hills to stand, and two fingers drawn up or down move it between the bounds.
 */
internal const val DEFAULT_PITCH = 38f
internal const val MIN_PITCH = 20f
internal const val MAX_PITCH = 80f

/** The camera stands this many of the canvas's longer side from the middle: far enough to keep a walk on screen. */
private const val DISTANCE_SIDES = 2f

/** The middle of the view lands this far down the canvas. */
private const val MIDDLE_SHARE = 0.5f

/** Nothing nearer the camera than this share of the middle's depth is drawn. */
private const val NEAREST_SHARE = 0.15f

/** A ray this close to level is taken as missing the ground. */
private const val HORIZON_GRAZE = 0.01f

/** The tilted ground is read this many view widths out from the middle. */
internal const val TILT_REACH_WIDTHS = 4f

/** The view's relief is raised until it stands about this share of the view's width, within reason. */
private const val RELIEF_SHARE = 0.12
private const val MAX_EXAGGERATION = 8f

private const val HALF_TURN = 180.0
private const val HALF_TURN_DEGREES = 180f
private const val FULL_TURN_DEGREES = 360f
private const val DEGREES_PER_TURN = 360.0
private const val QUARTER_MASK = 3
private const val FACING_LEVEL = 0.2f
private const val COMPASS_SMOOTHING = 0.18f
private const val COMPASS_STEP_DEG = 0.5f
private const val ROTATION_MATRIX_SIZE = 9
private const val QUATERNION_SIZE = 4

/** How high the walk is drawn over its own fixes, in pixels: clear of the dots under it. */
private const val ROUTE_LIFT_PX = 6f
private val SHADOW_WIDTH = 3.dp
private val CASING_WIDTH = 6.dp
private const val SHADOW_ALPHA = 0.22f
private const val STEM_ALPHA = 0.35f
private const val WALL_ALPHA = 0.15f
private const val WALL_CORNERS = 4

/** How far each piece of the wall reaches into the next, in pixels: see [wallPiece]. */
internal const val WALL_OVERLAP_PX = 0.05f

// A walk's climb stands ROUTE_RISE_SHARE of the walk's size while the view is up to ROUTE_RISE_FULL
// walks across, and is raised no more than the ground once the view is ROUTE_RISE_GONE walks across.
private const val ROUTE_RISE_SHARE = 0.2
private const val ROUTE_RISE_FULL = 2.0
private const val ROUTE_RISE_GONE = 6.0
private const val MIN_CLIMB_M = 10f
private val STEM_WIDTH = 1.dp
private val STEM_GAP = 18.dp
private val END_RADIUS = 4.dp
private val STOP_RADIUS = 3.dp
private const val DOT_DASH_PX = 0.01f
private val HERE_DISC = 7.dp
private val HERE_RIM = 2.dp

/** The arrow's tip, and the middle of its base, in radii of the disc from its centre; half its base. */
private const val POINTER_REACH = 2.3f
private const val POINTER_BASE = 0.3f
private const val POINTER_HALF_WIDTH = 0.95f

/** How far along the ground, in pixels at the middle, the arrow's direction is taken over. */
private const val BEARING_PROBE_PX = 24.0
private const val MIN_BEARING_PX = 0.01f
private val PHOTO_DISC = 10.dp
private val PHOTO_PADDING = 3.dp
private val PHOTO_RIM = 1.5.dp

/** The running median and the running mean a walk's heights are smoothed with, in fixes. */
private const val LIFT_MEDIAN = 31
private const val LIFT_MEAN = 15
