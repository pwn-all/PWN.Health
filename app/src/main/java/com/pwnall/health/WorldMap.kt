package com.pwnall.health

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.util.zip.InflaterInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sinh
import kotlin.math.tan

/**
 * The world behind a recorded route: coasts and islands, lakes, rivers and streams, glaciers and salt
 * flats. No roads, no borders, no labels and no place names: the map is drawn in dots at the zooms a
 * walk is looked at, and there a road or a border would be a line nobody reads among them. Towns are
 * [WorldTowns]', a circle each; Greenland and Antarctica are left out.
 *
 * Built from Natural Earth 1:10m (public domain) by `tools/world-map.py` into `assets/world.map`,
 * 4.6 MB for 1.3 million source points across five levels of detail, each deflated; the relief
 * under it is [WorldRelief]'s, 17.5 MB, and the forests [WorldForest]'s, 7.1 MB, the lot inside the
 * 30 MB the map may weigh. A tiled map would be the usual answer and cannot be the answer here: this
 * app has no INTERNET permission, and it is not going to grow one for scenery.
 *
 * Nothing here reads the whole file. One level is read at a time, for one region, and only the
 * lines inside it are kept — see [WorldMap.level] and [MapCamera.limit].
 */
const val WORLD_MAP_ASSET = "world.map"

/** Where Mercator stops: the latitude at which the projection has stretched the world square. */
const val MERCATOR_MAX_LAT = 85.051129

/** East from the antimeridian, 0 to 1. */
fun mercatorX(longitude: Double): Double = longitude / 360.0 + 0.5

/**
 * [longitude] moved by whole turns until it is within half a turn of [reference]. A route across
 * the antimeridian holds 179.9997 next to -179.9997; projected as they are, the two land at opposite
 * edges of the map and the leg between them runs across the whole world. Unwrapped against the
 * route's first fix they sit a few metres apart, just past the map's edge, which is where they are.
 */
fun unwrapLongitude(longitude: Double, reference: Double): Double =
    longitude - 360.0 * round((longitude - reference) / 360.0)

/** South from the top, 0 to 1. Latitudes past the poles of the projection are pinned to its edge. */
fun mercatorY(latitude: Double): Double {
    val radians = latitude.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT) * PI / 180.0
    return 0.5 - ln(tan(PI / 4 + radians / 2)) / (2 * PI)
}

/**
 * How many map units [metres] covers at Mercator [y]. Mercator stretches with latitude — a unit
 * near Murmansk is a third of the ground a unit at the equator is — so a distance in metres only
 * becomes a distance on this map once the latitude is known.
 */
fun mapUnitsFor(metres: Double, y: Double): Double {
    val latitude = atan(sinh(PI * (1 - 2 * y.coerceIn(0.0, 1.0))))
    return metres / (EARTH_CIRCUMFERENCE_M * cos(latitude))
}

private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

/** One polyline, already projected, carrying the box that decides whether it is worth drawing. */
class MapLine(
    val points: FloatArray,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
)

/**
 * One level of detail, decoded for one part of the world: the lines grouped by layer, in the order
 * the pens expect. [covers] is the region it was read for — outside it the level has nothing, so
 * the screen asks for a new one when the view leaves.
 */
class MapLevel(val index: Int, val covers: Rect, val layers: List<List<MapLine>>)

/** The whole map, for a view that has not been measured yet. */
val WHOLE_WORLD = Rect(0f, 0f, 1f, 1f)

/**
 * A layer and how to draw it. The list passed to [drawWorld] is also the drawing order.
 *
 * A pen with [dots] draws its line as a row of round dots its [width] across, that far apart, the
 * way the dotted map draws its rivers.
 *
 * A filled pen expects closed rings — the encoder cuts those layers to a grid instead of into
 * strips for exactly that reason — and fills them even-odd, so a lake cut out of a country stays
 * cut out.
 *
 * [upTo] is the coarsest zoom the pen draws at, in degrees per pixel: a road layer that appears on
 * a view of the whole world turns every continent into scribble, which is why no map draws one.
 * [from] is the zoom it hands over at on the way in, to a pen whose [upTo] it is, so a layer can
 * change how it looks closer in. Two pens on the same layer, a wide dark one and a narrow light
 * one, give a road its casing. A [dash] breaks the stroke into dashes that long, with gaps of
 * [DASH_GAP] of it between them. The land's pen also carries the [relief] painted over the land.
 */
class MapPen(
    val layer: Int,
    val color: Color,
    val width: Dp = Dp.Hairline,
    val fill: Boolean = false,
    val upTo: Double = Double.MAX_VALUE,
    val from: Double = 0.0,
    val dash: Dp = 0.dp,
    val relief: ReliefPalette? = null,
    val dots: Dp = 0.dp,
)

/** A dashed pen's gap, as a share of its dash. */
private const val DASH_GAP = 0.6f

/** The dash a dotted pen draws: as near nothing as a dash can be, so its round cap is all that shows. */
private const val DOT_DASH_PX = 0.01f

/**
 * The map's table of contents. Reading it costs a few dozen bytes; a level costs what a level
 * costs, which is why they are read one at a time and only when the zoom asks for one.
 */
class WorldMap(
    private val open: () -> InputStream,
    private val units: IntArray,
    private val offsets: IntArray,
    private val lengths: IntArray,
) {
    val levelCount: Int get() = units.size

    /**
     * The coarsest level whose error hides under a pixel. Detail below that is work nobody can
     * see, and it is the difference between drawing some 40 000 points for a globe and 1.2 million.
     */
    fun levelFor(degreesPerPixel: Double): Int =
        units.indices.firstOrNull { 2.0 / units[it] <= degreesPerPixel } ?: units.lastIndex

    /**
     * Reads one level and keeps only the lines that touch [covers]. Blocking, and slow enough to
     * matter — never on the main thread.
     *
     * Walking the level's varints is unavoidable; holding all of it is not. The finest level is
     * 1.2 million points and is only ever chosen at a zoom where the screen holds a fraction of a
     * degree, so keeping what is near the view rather than what is in the file is the difference
     * between some ten megabytes of heap and a small part of them.
     *
     * Where [covers] reaches past the antimeridian, as it does round a route unwrapped across it, the
     * lines beyond it are kept a whole turn over, where that route is drawn: see [turnsInto].
     */
    fun level(index: Int, covers: Rect): MapLevel {
        require(offsets[index] > 0 && lengths[index] > 0) { "level $index is not in this map" }
        val layers = open().use { stream ->
            skipFully(stream, offsets[index].toLong())
            // Straight off the stream: pulling the level's bytes into an array first would be a
            // four-megabyte allocation per read, for a copy nothing ever looks at twice. Each body
            // is its own zlib stream, so the inflater starts at the body and needs nothing before it.
            // Closed with the read, or its native zlib memory waits on the finaliser.
            InflaterInputStream(stream).use { LevelReader(VarintReader(it), units[index], covers).read() }
        }
        return MapLevel(index, covers, layers)
    }
}

/**
 * The whole turns that carry what the map holds into [region]: none but its own place, and one round
 * either way where the region reaches past the antimeridian. The file holds each coast once, between
 * x = 0 and 1; Taveuni's east, or Chukotka's, lies at the far edge from a route unwrapped beside it,
 * and read only where the file has it, that ground would be drawn as sea.
 */
internal fun turnsInto(region: Rect): IntRange = (if (region.left < 0f) -1 else 0)..(if (region.right > 1f) 1 else 0)

/** True when [other] is entirely inside this box, so a level read for it still holds everything. */
fun Rect.holds(other: Rect): Boolean {
    val insideX = left <= other.left && right >= other.right
    val insideY = top <= other.top && bottom >= other.bottom
    return insideX && insideY
}

/**
 * Reads the table of contents of the format `tools/world-map.py` writes; that file documents it.
 * Errors are thrown, not repaired — the asset ships with the app, so a broken one is a build
 * mistake rather than a runtime state.
 */
fun parseWorldMap(open: () -> InputStream): WorldMap = open().use { stream ->
    val reader = VarintReader(stream)
    require(MAGIC.all { reader.byte() == it.code }) { "not a world map" }
    val count = reader.next()
    val units = IntArray(count)
    val offsets = IntArray(count)
    val lengths = IntArray(count)
    for (index in 0 until count) {
        units[index] = reader.next()
        offsets[index] = reader.int32()
        lengths[index] = reader.int32()
    }
    WorldMap(open, units, offsets, lengths)
}

private const val MAGIC = "PWNMAP3"

/**
 * Walks one level's body and keeps the lines that touch [covers]. Every line is read into the same
 * scratch buffer and copied out only if it is wanted, so the ones off screen cost nothing but the
 * time to step over their bytes.
 */
private class LevelReader(
    private val reader: VarintReader,
    private val units: Int,
    private val covers: Rect,
) {
    private var scratch = FloatArray(SCRATCH_FLOATS)
    private val turns = turnsInto(covers)

    fun read(): List<List<MapLine>> = List(reader.next()) { layer() }

    private fun layer(): List<MapLine> {
        val lines = ArrayList<MapLine>()
        repeat(reader.next()) { line(lines) }
        return lines
    }

    /** Reads one line, and adds it to [lines] at each whole turn of the world that brings it into [covers]. */
    private fun line(lines: MutableList<MapLine>) {
        val count = reader.next()
        if (count * 2 > scratch.size) scratch = FloatArray(count * 2)
        var longitude = 0
        var latitude = 0
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (index in 0 until count) {
            longitude += reader.signed()
            latitude += reader.signed()
            val x = mercatorX(longitude.toDouble() / units).toFloat()
            val y = mercatorY(latitude.toDouble() / units).toFloat()
            scratch[index * 2] = x
            scratch[index * 2 + 1] = y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxY < covers.top || minY > covers.bottom) return
        for (turn in turns) keepAt(turn, count, minX, minY, maxX, maxY, lines)
    }

    /**
     * Adds the line just read into [scratch] to [lines] a whole [turn] over, where that brings it into
     * [covers]. Its bounds come as four floats, not an object for each of the many lines read.
     */
    private fun keepAt(
        turn: Int,
        count: Int,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        lines: MutableList<MapLine>,
    ) {
        val shift = turn.toFloat()
        if (maxX + shift < covers.left || minX + shift > covers.right) return
        val points = scratch.copyOf(count * 2)
        if (turn != 0) for (index in 0 until count) points[index * 2] += shift
        lines += MapLine(points, minX + shift, minY, maxX + shift, maxY)
    }
}

/** The encoder cuts lines into 128-point pieces; this is room for a few of those, and it grows. */
private const val SCRATCH_FLOATS = 512

/**
 * LEB128, the same encoding protobuf uses, because a coastline is mostly one-byte steps. Reads
 * through its own window on the stream: `BufferedInputStream.read()` is a synchronised call per
 * byte, and this walks tens of millions of them.
 */
internal class VarintReader(private val input: InputStream) {
    private val window = ByteArray(WINDOW_BYTES)
    private var filled = 0
    private var index = 0
    private var before = 0L

    /** How many bytes have been read so far: where a table of contents ends and the bodies begin. */
    val position: Long get() = before + index

    fun byte(): Int {
        if (index == filled) {
            before += filled.coerceAtLeast(0)
            filled = input.read(window)
            index = 0
            if (filled <= 0) throw EOFException("world map ended early")
        }
        return window[index++].toInt() and 0xFF
    }

    fun next(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = byte()
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    fun signed(): Int = next().let { (it ushr 1) xor -(it and 1) }

    /** The offset table is fixed width, so the encoder can patch it once the bodies are sized. */
    fun int32(): Int {
        var result = 0
        for (step in 0 until 4) result = result or (byte() shl (step * 8))
        return result
    }
}

private const val WINDOW_BYTES = 1 shl 15

/**
 * One table of contents for the life of the process — it is a few dozen bytes, and the levels it
 * points at are held and released by the screen. Two compositions racing here would read the
 * header twice and keep the second, which is cheaper than a lock.
 */
private var parsedWorldMap: WorldMap? = null

/** Null until the asset is opened; the screen draws the route without it and gains the map later. */
@Composable
fun rememberWorldMap(): WorldMap? {
    val assets = LocalContext.current.applicationContext.assets
    return produceState(parsedWorldMap) {
        if (value == null) {
            // The map is scenery. If the asset is missing or truncated the route still draws, and
            // that is a better answer than a screen that will not open.
            value = withContext(Dispatchers.IO) {
                runCatching { parseWorldMap { assets.open(WORLD_MAP_ASSET) } }.getOrNull()
            }?.also { parsedWorldMap = it }
        }
    }.value
}

/**
 * Where the map is looked at from: the centre in normalised Mercator units and how many pixels one
 * of those units covers. The screen holds it, so the inline panel and the full-screen dialog share
 * a single view and opening the dialog keeps the zoom the person just set.
 *
 * [closest] is as far as it zooms in, in those pixels a unit: by default about a metre a pixel at
 * the equator, which is what a walk's own page allows; the track screen, which frames a few streets
 * around the phone, asks for closer.
 */
@Stable
class MapCamera(internal val closest: Double = MAX_PIXELS_PER_UNIT) {
    var centerX by mutableDoubleStateOf(0.5)
        private set
    var centerY by mutableDoubleStateOf(0.5)
        private set
    var pixelsPerUnit by mutableDoubleStateOf(0.0)
        private set

    /**
     * Until the first drag the view follows the route; after one, the person is driving. A zoom about
     * the middle — the buttons, a double tap, a pinch on the tilted map — keeps it following, at the
     * zoom asked for: see [zoomed].
     */
    var manual by mutableStateOf(false)
        private set

    /** Whether the person has zoomed by hand: following then keeps their zoom and only moves the middle. */
    var zoomed by mutableStateOf(false)
        private set

    /**
     * The only part of the world this camera will show: the route, and round it as far as the
     * farthest view reaches — [MAP_FARTHEST_ZOOM] times the track screen's closest, which is also
     * as far as it zooms out. It is why a walk never has to read a continent, and why the map cannot
     * be browsed as an atlas — this is a record of where somebody went, not a place to look up Peru.
     */
    var limit by mutableStateOf(WHOLE_WORLD)
        private set

    /** Sets that area from the route's own box and pulls the current view back inside it. */
    fun restrict(bounds: Rect?, size: Size) {
        limit = bounds?.let {
            // Half the farthest view's longer side: zoomed all the way out, the route can still be
            // anywhere under the middle of the screen.
            val margin = (size.maxDimension / 2 / FARTHEST_PIXELS_PER_UNIT).toFloat()
            // Clipped at the poles only. A route unwrapped across the antimeridian sits just past
            // x = 1, and a limit cut at the world's edge would leave the far end of it off screen.
            val roam = it.inflate(margin)
            Rect(roam.left, roam.top.coerceAtLeast(0f), roam.right, roam.bottom.coerceAtMost(1f))
        } ?: WHOLE_WORLD
        clampTo(size)
    }

    /**
     * Frames [bounds] with a margin, or the whole world when there is no route yet. A route wider
     * than the farthest view the camera allows ([MAP_FARTHEST_ZOOM]) is framed about its middle at
     * that view, its ends past the edges — the cap is kept for a long ride too, and the button that
     * comes back here is named for the route, not for the whole of it.
     */
    fun fitTo(bounds: Rect?, size: Size) {
        if (size.minDimension <= 0f) return
        if (bounds == null) {
            centerX = 0.5
            centerY = 0.5
            pixelsPerUnit = size.minDimension.toDouble()
            clampTo(size)
            return
        }
        centerX = bounds.center.x.toDouble()
        centerY = bounds.center.y.toDouble()
        val spanX = bounds.width.toDouble().coerceAtLeast(MIN_SPAN)
        val spanY = bounds.height.toDouble().coerceAtLeast(MIN_SPAN)
        pixelsPerUnit = minOf(
            (size.width - 2 * FIT_PADDING_PX) / spanX,
            (size.height - 2 * FIT_PADDING_PX) / spanY,
        )
        clampTo(size)
    }

    /**
     * Travels to the view [fitTo] would frame, on the spatial spring: the centre along a straight
     * line and the zoom by equal ratios, so a jump from a street to a whole route reads as the camera
     * pulling back rather than as a cut. It ends on exactly that view, and the spring runs on the
     * system's animation scale, so with animations off it lands at once.
     */
    suspend fun glideTo(bounds: Rect?, size: Size) {
        val target = MapCamera(closest).also {
            it.limit = limit
            it.fitTo(bounds, size)
        }
        if (pixelsPerUnit <= 0.0 || target.pixelsPerUnit <= 0.0) {
            fitTo(bounds, size)
            return
        }
        val fromX = centerX
        val fromY = centerY
        val fromScale = ln(pixelsPerUnit)
        val toScale = ln(target.pixelsPerUnit)
        animate(0f, 1f, animationSpec = PwnMotion.spatial()) { t, _ ->
            centerX = fromX + (target.centerX - fromX) * t
            centerY = fromY + (target.centerY - fromY) * t
            pixelsPerUnit = exp(fromScale + (toScale - fromScale) * t)
        }
        centerX = target.centerX
        centerY = target.centerY
        pixelsPerUnit = target.pixelsPerUnit
    }

    /** One pinch-and-drag: the point under [centroid] stays under it, then follows [pan]. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float, size: Size) {
        val before = pixelsPerUnit
        if (before <= 0.0 || size.minDimension <= 0f) return
        manual = true
        val focusX = centerX + (centroid.x - size.width / 2) / before
        val focusY = centerY + (centroid.y - size.height / 2) / before
        pixelsPerUnit = (before * zoom).coerceIn(minScaleFor(size), closest)
        centerX = focusX - (centroid.x + pan.x - size.width / 2) / pixelsPerUnit
        centerY = focusY - (centroid.y + pan.y - size.height / 2) / pixelsPerUnit
        clampTo(size)
    }

    /**
     * Moves the view [dx] east and [dy] south, in map units: a drag across the tilted map, where the
     * point under the finger is not a point of the flat picture [transform] works in.
     */
    fun shift(dx: Double, dy: Double, size: Size) {
        if (pixelsPerUnit <= 0.0 || size.minDimension <= 0f) return
        manual = true
        centerX += dx
        centerY += dy
        clampTo(size)
    }

    /** Back to following the route, framed as at first — what the button in the corner of the map does. */
    fun follow() {
        manual = false
        zoomed = false
    }

    /** [factor] times closer, about the middle of the view, which stays where it is and keeps following. */
    fun zoomBy(factor: Float, size: Size) {
        if (pixelsPerUnit <= 0.0 || size.minDimension <= 0f) return
        zoomed = true
        pixelsPerUnit = (pixelsPerUnit * factor).coerceIn(minScaleFor(size), closest)
        clampTo(size)
    }

    /** Puts the middle of the view on [x], [y] at the zoom it has: following, once zoomed by hand. */
    fun centreOn(x: Double, y: Double, size: Size) {
        if (pixelsPerUnit <= 0.0 || size.minDimension <= 0f) return
        centerX = x
        centerY = y
        clampTo(size)
    }

    /** Where a map point lands on a canvas of [size]. The drawing loops inline this for speed. */
    fun screenPosition(x: Double, y: Double, size: Size): Offset = Offset(
        (size.width / 2 + (x - centerX) * pixelsPerUnit).toFloat(),
        (size.height / 2 + (y - centerY) * pixelsPerUnit).toFloat(),
    )

    /** How much ground a pixel covers, which is what chooses the level of detail. */
    fun degreesPerPixel(): Double =
        if (pixelsPerUnit <= 0.0) Double.MAX_VALUE else DEGREES_PER_UNIT / pixelsPerUnit

    /** The part of the map on screen, in map units. */
    fun viewport(size: Size): Rect {
        if (pixelsPerUnit <= 0.0 || size.minDimension <= 0f) return WHOLE_WORLD
        val halfWidth = (size.width / 2 / pixelsPerUnit).toFloat()
        val halfHeight = (size.height / 2 / pixelsPerUnit).toFloat()
        val x = centerX.toFloat()
        val y = centerY.toFloat()
        return Rect(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
    }

    /** No zooming out past [limit], and no panning off it either: the screen stays inside. */
    private fun clampTo(size: Size) {
        if (size.minDimension <= 0f || pixelsPerUnit <= 0.0) return
        pixelsPerUnit = pixelsPerUnit.coerceIn(minScaleFor(size), closest)
        val halfWidth = size.width / 2 / pixelsPerUnit
        val halfHeight = size.height / 2 / pixelsPerUnit
        centerX = between(limit.left + halfWidth, limit.right - halfWidth, centerX)
        centerY = between(limit.top + halfHeight, limit.bottom - halfHeight, centerY)
    }

    /**
     * The furthest out anyone can go: where [limit] exactly covers the canvas, and round a route never
     * past [MAP_FARTHEST_ZOOM]. Without one the world is the view, as before the first fix.
     */
    private fun minScaleFor(size: Size): Double {
        val cover = maxOf(size.width / limit.width, size.height / limit.height).toDouble()
        val farthest = if (limit == WHOLE_WORLD) cover else maxOf(cover, FARTHEST_PIXELS_PER_UNIT)
        return minOf(farthest, closest)
    }

    /** [value] inside the range, or its middle when rounding has left no range at all. */
    private fun between(low: Double, high: Double, value: Double): Double =
        if (low >= high) (low + high) / 2 else value.coerceIn(low, high)

    companion object {
        /**
         * Rotating the phone while looking at a route is the natural gesture, and it must not throw
         * the zoom away. [limit] is left out on purpose: it comes from the route's own box, and the
         * screen sets it again on the first measure after the camera is back. [closest] is kept, or a
         * camera allowed nearer would come back held to the default; one saved without it comes back
         * with the default.
         */
        val Saver: Saver<MapCamera, Any> = listSaver(
            save = { listOf<Any>(it.centerX, it.centerY, it.pixelsPerUnit, it.manual, it.closest, it.zoomed) },
            restore = { saved ->
                MapCamera(saved.getOrNull(4) as? Double ?: MAX_PIXELS_PER_UNIT).apply {
                    centerX = saved[0] as Double
                    centerY = saved[1] as Double
                    pixelsPerUnit = saved[2] as Double
                    manual = saved[3] as Boolean
                    zoomed = saved.getOrNull(5) as? Boolean ?: false
                }
            },
        )
    }
}

/**
 * How far out the map zooms, in times the track screen's closest view: about 48 km across a phone in
 * central Europe. Held to the route and a few hundred metres round it, the view would leave a summit
 * a carpet of forest; past this the tilted ground to read and draw grows toward a country, and the
 * map lags.
 */
const val MAP_FARTHEST_ZOOM = 168.0

/** That zoom, in pixels a map unit. */
private const val FARTHEST_PIXELS_PER_UNIT = HERE_PIXELS_PER_UNIT / MAP_FARTHEST_ZOOM

/**
 * The smallest patch of map a level is ever read for. Reading any patch costs one pass over the
 * level's bytes, so a patch the size of the screen would mean a fresh pass every kilometre of
 * panning; at this size the camera runs out of room to roam before it runs out of map.
 */
const val MAP_READ_METRES = 400_000.0

private const val DEGREES_PER_UNIT = 360.0

/** Roughly one metre per pixel at the equator. Past that the map has nothing left to say. */
private const val MAX_PIXELS_PER_UNIT = 4.0e7

/** About four hundred metres: the view a single fix gets, instead of an infinite zoom. */
private const val MIN_SPAN = 1.0e-5
private const val FIT_PADDING_PX = 24f

/**
 * Draws one level under whatever the caller puts on top, one pen at a time in the order given.
 * Lines whose box misses the view are skipped, which is what keeps a street zoom cheap even though
 * the level it is reading holds the whole planet. [onLand] is painted straight after the land, inside
 * the land's own outline — the relief, which the coast then cuts as sharply as it cuts the water,
 * and which everything after the land is drawn over.
 */
fun DrawScope.drawWorld(
    level: MapLevel,
    camera: MapCamera,
    path: Path,
    pens: List<MapPen>,
    onLand: (DrawScope.() -> Unit)? = null,
) {
    if (camera.pixelsPerUnit <= 0.0) return
    val zoom = camera.degreesPerPixel()
    pens.forEach { pen ->
        if (zoom > pen.upTo || zoom <= pen.from) return@forEach
        val lines = level.layers.getOrNull(pen.layer) ?: return@forEach
        val painted = paintMapLines(lines, camera, path, pen)
        if (painted && pen.layer == LAND_LAYER && pen.fill) onLand?.let { clipPath(path) { it() } }
    }
}

/** The layer tools/world-map.py writes the land into: the ground the relief is laid on. */
const val LAND_LAYER = 0

/** The other layers, in the order tools/world-map.py writes them: its LAYER_NAMES. */
const val LAKE_LAYER = 1
const val RIVER_LAYER = 2
const val GLACIER_LAYER = 3
const val SALT_LAYER = 4
const val STREAM_LAYER = 5

/** Strokes or fills [lines] with [pen]; false when none of them is on screen and nothing was drawn. */
private fun DrawScope.paintMapLines(
    lines: List<MapLine>,
    camera: MapCamera,
    path: Path,
    pen: MapPen,
): Boolean {
    val scale = camera.pixelsPerUnit
    val originX = size.width / 2 - camera.centerX * scale
    val originY = size.height / 2 - camera.centerY * scale
    val left = -originX / scale
    val top = -originY / scale
    val right = (size.width - originX) / scale
    val bottom = (size.height - originY) / scale

    path.rewind()
    path.fillType = if (pen.fill) PathFillType.EvenOdd else PathFillType.NonZero
    var any = false
    lines.forEach { line ->
        val onScreenX = line.maxX >= left && line.minX <= right
        val onScreenY = line.maxY >= top && line.minY <= bottom
        if (!onScreenX || !onScreenY) return@forEach
        val points = line.points
        path.moveTo((originX + points[0] * scale).toFloat(), (originY + points[1] * scale).toFloat())
        for (index in 2 until points.size step 2) {
            path.lineTo(
                (originX + points[index] * scale).toFloat(),
                (originY + points[index + 1] * scale).toFloat(),
            )
        }
        if (pen.fill) path.close()
        any = true
    }
    if (!any) return false
    when {
        pen.fill -> drawPath(path, pen.color)

        // A dash next to nothing long, with a round cap, is a dot as wide as the stroke.
        pen.dots > 0.dp -> drawPath(
            path,
            pen.color,
            style = Stroke(
                pen.width.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DOT_DASH_PX, pen.dots.toPx())),
            ),
        )

        else -> {
            val dash = pen.dash.toPx()
            val effect = if (dash > 0f) PathEffect.dashPathEffect(floatArrayOf(dash, dash * DASH_GAP)) else null
            drawPath(path, pen.color, style = Stroke(pen.width.toPx(), pathEffect = effect))
        }
    }
    return true
}
