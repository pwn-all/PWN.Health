package com.pwnall.health

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.InflaterInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * The lie of the land under the map: how high the ground stands, for the relief painted between the
 * land and the lines on it — slopes shaded as if lit from the north-west, the way every atlas lights
 * them, and the ground tinted by its height, so a range of mountains, a valley a river runs down and
 * a plain all read as what they are.
 *
 * Built from ETOPO 2022 (NOAA, public domain) by `tools/world-relief.py` into `assets/world.relief`,
 * 17.5 MB: heights only, four levels from sixteen minutes of arc a cell down to one, about 1.8 km
 * north to south — a mountain range, a valley, the slope a walk climbed, not the bank of a stream.
 * The colours and the light are worked out here, so the same heights serve the day map and the
 * night one. Like the lines, it is read a level and a region at a time — see [heights].
 */
const val WORLD_RELIEF_ASSET = "world.relief"

/** What reading a window of a level's tiles needs: its grid, and where each tile lies in the file. */
interface TileLevel {
    val tileCells: Int
    val tilesX: Int
    val columns: Int
    val offsets: LongArray
    val lengths: IntArray
}

/** One level's table of contents: its grid, its tiles and where each lies in the file. */
class ReliefLevel(
    val cellSeconds: Int,
    override val tileCells: Int,
    val step: Int,
    override val tilesX: Int,
    val tilesY: Int,
    /** Where each tile starts in the file, row by row from the north-west; unused for a sea tile. */
    override val offsets: LongArray,
    /** Each tile's length in bytes, 0 for a tile that is all sea. */
    override val lengths: IntArray,
) : TileLevel {
    override val columns: Int get() = FULL_TURN_SECONDS / cellSeconds
    val rows: Int get() = HALF_TURN_SECONDS / cellSeconds
    val cellDegrees: Double get() = cellSeconds / SECONDS_PER_DEGREE
}

/** The relief's table of contents, read once; a level's tiles are read only when a view asks. */
class WorldRelief(private val open: () -> InputStream, val levels: List<ReliefLevel>) {

    /**
     * The coarsest level whose cells span no more than [RELIEF_PIXELS_PER_CELL] pixels. The smoothing
     * that draws it spreads every cell over its neighbours, so finer than that is detail nobody sees
     * and a texture four times the size.
     */
    fun levelFor(degreesPerPixel: Double): Int =
        levels.indices.firstOrNull { levels[it].cellDegrees <= degreesPerPixel * RELIEF_PIXELS_PER_CELL }
            ?: levels.lastIndex

    /**
     * Heights in metres of the [rowCount] × [columnCount] cells of level [index] from [firstRow] and
     * [firstColumn], row by row. Columns run on round the antimeridian in either direction, as an
     * unwrapped route does; rows must lie inside the level. Blocking — never on the main thread.
     */
    fun heights(index: Int, firstRow: Int, rowCount: Int, firstColumn: Int, columnCount: Int): FloatArray {
        val level = levels[index]
        val out = FloatArray(rowCount * columnCount)
        // Each window column's column in the level; the tiles those fall in are the ones read.
        val column = IntArray(columnCount) { (firstColumn + it).mod(level.columns) }
        val window = Window(firstRow, rowCount, column, out)
        readTiles(open, level, firstRow, rowCount, column) { bytes, ty, tx ->
            window.copy(decodeReliefTile(bytes, level.tileCells), ty, tx, level)
        }
        return out
    }
}

/**
 * Reads, in file order, each tile of [level] under a window of [rowCount] rows from [firstRow] over
 * the level columns in [column], and hands its bytes with its tile row and column to [onTile]. A
 * tile of length 0 — all sea, no forest — is never read.
 */
internal inline fun readTiles(
    open: () -> InputStream,
    level: TileLevel,
    firstRow: Int,
    rowCount: Int,
    column: IntArray,
    onTile: (bytes: ByteArray, ty: Int, tx: Int) -> Unit,
) {
    val size = level.tileCells
    val tileColumns = column.map { it / size }.distinct()
    val tileRows = (firstRow / size)..((firstRow + rowCount - 1) / size)
    val wanted = tileRows.flatMap { ty -> tileColumns.map { tx -> ty * level.tilesX + tx } }
        .filter { level.lengths[it] > 0 }
        .sortedBy { level.offsets[it] }
    if (wanted.isEmpty()) return
    open().use { stream ->
        val data = DataInputStream(stream)
        var position = 0L
        wanted.forEach { tile ->
            skipFully(stream, level.offsets[tile] - position)
            val bytes = ByteArray(level.lengths[tile])
            data.readFully(bytes)
            position = level.offsets[tile] + bytes.size
            onTile(bytes, tile / level.tilesX, tile % level.tilesX)
        }
    }
}

/** The rows and columns a [WorldRelief.heights] call asked for, and the array it fills. */
private class Window(val firstRow: Int, val rowCount: Int, val column: IntArray, val out: FloatArray) {
    /** One decoded tile's cells, in metres, wherever the window holds them. */
    fun copy(cells: IntArray, ty: Int, tx: Int, level: ReliefLevel) {
        val size = level.tileCells
        val rowFrom = maxOf(firstRow, ty * size)
        val rowTo = minOf(firstRow + rowCount, (ty + 1) * size)
        for (row in rowFrom until rowTo) {
            val source = (row - ty * size) * size - tx * size
            val target = (row - firstRow) * column.size
            column.forEachIndexed { w, col ->
                if (col / size == tx) out[target + w] = (cells[source + col] * level.step).toFloat()
            }
        }
    }
}

/**
 * Moves [stream] on by [count] bytes, or throws where it ends first. `skip` may stop short of what it
 * was asked, even at nothing, with the stream still running; a byte read then tells which it was.
 */
internal fun skipFully(stream: InputStream, count: Long) {
    var left = count
    while (left > 0) {
        val step = stream.skip(left)
        left -= when {
            step > 0L -> step
            stream.read() >= 0 -> 1L
            else -> throw EOFException("a map asset ended early")
        }
    }
}

/**
 * One tile's heights in steps, undoing the encoder's prediction cell by cell: each is the MED guess
 * from its west, north and north-west neighbours plus the residual stored for it.
 */
internal fun decodeReliefTile(bytes: ByteArray, size: Int): IntArray =
    // Closed with the tile, or its inflater's native zlib memory waits on the finaliser.
    InflaterInputStream(ByteArrayInputStream(bytes)).use { undoPrediction(VarintReader(it), size) }

/** [size] rows of [size] cells off [reader], each the MED guess from the cells before it plus its residual. */
private fun undoPrediction(reader: VarintReader, size: Int): IntArray {
    val cells = IntArray(size * size)
    for (row in 0 until size) {
        for (col in 0 until size) {
            val index = row * size + col
            val west = if (col > 0) cells[index - 1] else 0
            val north = if (row > 0) cells[index - size] else 0
            val corner = if (row > 0 && col > 0) cells[index - size - 1] else 0
            cells[index] = medPrediction(west, north, corner) + reader.signed()
        }
    }
    return cells
}

/** JPEG-LS's median edge detector: the guess the encoder subtracted, made again from the same cells. */
internal fun medPrediction(west: Int, north: Int, corner: Int): Int {
    val low = minOf(west, north)
    val high = maxOf(west, north)
    return when {
        corner >= high -> low
        corner <= low -> high
        else -> west + north - corner
    }
}

/** Reads the table of contents `tools/world-relief.py` writes; that file documents the format. */
fun parseWorldRelief(open: () -> InputStream): WorldRelief {
    val levels = open().use { stream ->
        val reader = VarintReader(stream)
        require(RELIEF_MAGIC.all { reader.byte() == it.code }) { "not a world relief" }
        val headers = List(reader.next()) {
            val cellSeconds = reader.next()
            val tileCells = reader.next()
            val step = reader.next()
            val tilesX = reader.next()
            val tilesY = reader.next()
            val lengths = IntArray(tilesX * tilesY) { reader.next() }
            ReliefLevel(cellSeconds, tileCells, step, tilesX, tilesY, LongArray(lengths.size), lengths)
        }
        var offset = reader.position
        headers.forEach { level ->
            level.lengths.forEachIndexed { tile, length ->
                level.offsets[tile] = offset
                offset += length
            }
        }
        headers
    }
    return WorldRelief(open, levels)
}

private const val RELIEF_MAGIC = "PWNREL1"
private const val SECONDS_PER_DEGREE = 3_600.0
private const val FULL_TURN_SECONDS = 360 * 3_600
private const val HALF_TURN_SECONDS = 180 * 3_600

/**
 * How many pixels a cell may span before the next finer level is read. Four keeps a whole screen and
 * the margin round it near a million texels at most — a few megabytes, shaded in tens of
 * milliseconds — and the relief is soft ground, not lines a pixel wide.
 */
private const val RELIEF_PIXELS_PER_CELL = 4.0

/** One table of contents for the process, as the map's own — see [rememberWorldMap]. */
private var parsedWorldRelief: WorldRelief? = null

/** Null until the asset is read, or for good if it cannot be: the map simply goes without relief. */
@Composable
fun rememberWorldRelief(): WorldRelief? {
    val assets = LocalContext.current.applicationContext.assets
    return produceState(parsedWorldRelief) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { parseWorldRelief { assets.open(WORLD_RELIEF_ASSET) } }.getOrNull()
            }?.also { parsedWorldRelief = it }
        }
    }.value
}

/**
 * The colours the relief is painted in. [heights] in metres, rising, each with its tint in [colors];
 * between two the tint is blended. A slope turned from the light is drawn toward [shadow] and one
 * turned to it toward [light], by [shadowStrength] and [lightStrength]; [slopeStrength] darkens steep
 * ground whichever way it faces, so a ridge lit end-on does not vanish.
 */
class ReliefPalette(
    val heights: FloatArray,
    val colors: List<Color>,
    val shadow: Color,
    val light: Color,
    val shadowStrength: Float,
    val lightStrength: Float,
    val slopeStrength: Float,
) {
    init {
        require(heights.size == colors.size && heights.isNotEmpty()) { "a height for every colour" }
    }

    internal val argb: IntArray = IntArray(colors.size) { colors[it].toArgb() }
    internal val shadowArgb: Int = shadow.toArgb()
    internal val lightArgb: Int = light.toArgb()
}

/**
 * A part of the world's relief, shaded and coloured, as one picture: a texel a cell across, rows
 * spaced evenly down the Mercator map so the picture lies on the map by a scale and a shift alone.
 * [left] and [top] are where its corner lies in map units, [texelX] and [texelY] how much map one
 * texel covers. It reaches a cell or more past the region it was shaded for on every side, which is
 * the room the smoothing needs to leave that region's edge clean.
 */
class ReliefImage(
    val bitmap: Bitmap,
    val left: Double,
    val top: Double,
    val texelX: Double,
    val texelY: Double,
    val cells: ReliefCells,
    /**
     * The same texels' heights, for the tilted map to stand the ground on: grey from [heightLow] at
     * black to [heightLow] + [heightSpan] metres at white.
     */
    val heights: Bitmap,
    val heightLow: Float,
    val heightSpan: Float,
)

/**
 * The heights a [ReliefImage] was shaded from, in metres: [rows] × [columns] cells of [cellDegrees]
 * from [firstRow] and [firstColumn] of their level, row by row from the north-west.
 */
class ReliefCells(
    val heights: FloatArray,
    val rows: Int,
    val columns: Int,
    val firstRow: Int,
    val firstColumn: Int,
    val cellDegrees: Double,
) {
    /** The ground's height at map point [x], [y], blended between the four cells round it. */
    fun heightAt(x: Double, y: Double): Float {
        val latitude = latitudeAt(y)
        val longitude = x * DEGREES_PER_TURN - HALF_TURN
        val row = ((NORTH_POLE - latitude) / cellDegrees - 0.5 - firstRow).coerceIn(0.0, rows - 1.0)
        val column = ((longitude + HALF_TURN) / cellDegrees - 0.5 - firstColumn).coerceIn(0.0, columns - 1.0)
        val top = row.toInt()
        val west = column.toInt()
        val bottom = (top + 1).coerceAtMost(rows - 1)
        val east = (west + 1).coerceAtMost(columns - 1)
        val down = (row - top).toFloat()
        val across = (column - west).toFloat()
        val upper = heights[top * columns + west] * (1 - across) + heights[top * columns + east] * across
        val lower = heights[bottom * columns + west] * (1 - across) + heights[bottom * columns + east] * across
        return upper * (1 - down) + lower * down
    }
}

/**
 * Paints the relief of [region] — map units, as [MapCamera.viewport] gives them — at level [start],
 * or a coarser one if that would be a texture past [MAX_RELIEF_TEXELS] on a side. Null where no
 * part of the region is land. Blocking: the heights are read and every cell shaded here.
 */
fun WorldRelief.render(
    region: Rect,
    start: Int,
    palette: ReliefPalette,
    lightFromDeg: Float = RELIEF_LIGHT_FROM,
): ReliefImage? {
    if (region.isEmpty) return null
    var index = start
    while (true) {
        val level = levels[index]
        val cell = level.cellDegrees
        // Two cells past the region on every side: the spline reaches two texels out, and the
        // slopes at the region's edge want their neighbours on both sides.
        val firstColumn = floor(region.left * DEGREES_PER_TURN / cell).toInt() - SPLINE_REACH
        val lastColumn = ceil(region.right * DEGREES_PER_TURN / cell).toInt() + SPLINE_REACH
        val north = latitudeAt(region.top.toDouble())
        val south = latitudeAt(region.bottom.toDouble())
        val firstRow = (floor((NORTH_POLE - north) / cell).toInt() - SPLINE_REACH).coerceIn(0, level.rows - 1)
        val lastRow = (ceil((NORTH_POLE - south) / cell).toInt() + SPLINE_REACH).coerceIn(0, level.rows - 1)
        val columns = lastColumn - firstColumn + 1
        val rows = lastRow - firstRow + 1
        if ((columns > MAX_RELIEF_TEXELS || rows > MAX_RELIEF_TEXELS) && index > 0) {
            index--
            continue
        }
        val heights = heights(index, firstRow, rows, firstColumn, columns)
        if (heights.all { it == 0f }) return null
        return mercatorImage(ReliefCells(heights, rows, columns, firstRow, firstColumn, cell), palette, lightFromDeg)
    }
}

/**
 * A row of cells' colours: each cell's height's tint, then the light. The slope is taken across the
 * cell's neighbours in metres of ground — a degree of longitude narrows toward the poles, a degree of
 * latitude does not — and steepened by [exaggeration], which grows with the cell: averaged over
 * thirty kilometres the Alps are a gentle swell, and unexaggerated they would vanish from a view of
 * Europe.
 */
internal class RowShader(private val cells: ReliefCells, private val palette: ReliefPalette, lightFromDeg: Float) {
    private val northSouth = cells.cellDegrees * METRES_PER_DEGREE
    private val lift = exaggeration(northSouth)
    private val lightEast = sin(lightFromDeg * PI / HALF_TURN) * LIGHT_LEVEL
    private val lightNorth = cos(lightFromDeg * PI / HALF_TURN) * LIGHT_LEVEL

    /** Cell row [row]'s colours, into [out]. */
    fun paint(row: Int, out: IntArray) {
        val heights = cells.heights
        val columns = cells.columns
        val latitude = NORTH_POLE - (cells.firstRow + row + 0.5) * cells.cellDegrees
        val eastWest = northSouth * cos(latitude * PI / HALF_TURN).coerceAtLeast(MIN_COS_LATITUDE)
        val up = (row - 1).coerceAtLeast(0) * columns
        val here = row * columns
        val down = (row + 1).coerceAtMost(cells.rows - 1) * columns
        for (col in 0 until columns) {
            val west = (col - 1).coerceAtLeast(0)
            val east = (col + 1).coerceAtMost(columns - 1)
            val gx = (heights[here + east] - heights[here + west]) / ((east - west) * eastWest)
            val gy = (heights[up + col] - heights[down + col]) / (((down - up) / columns).coerceAtLeast(1) * northSouth)
            out[col] = shade(heights[here + col], gx * lift, gy * lift, palette, lightEast, lightNorth)
        }
    }
}

/**
 * One cell's colour from its height and its slope, east and north, already exaggerated. The light
 * comes from 45° up, from the side [lightEast] and [lightNorth] point to — by default the north-west;
 * ground facing it is lifted, ground facing away is shadowed, and flat ground at any height keeps its
 * tint exactly.
 */
internal fun shade(
    height: Float,
    slopeEast: Double,
    slopeNorth: Double,
    palette: ReliefPalette,
    lightEast: Double = -LIGHT_HALF,
    lightNorth: Double = LIGHT_HALF,
): Int {
    val length = sqrt(slopeEast * slopeEast + slopeNorth * slopeNorth + 1.0)
    // The normal is (−slopeEast, −slopeNorth, 1) / length; the light (lightEast, lightNorth, √½).
    val facing = (-slopeEast * lightEast - slopeNorth * lightNorth + LIGHT_UP) / length
    val turn = facing - LIGHT_UP
    val steep = 1.0 - 1.0 / length
    val tint = tintAt(height, palette)
    val dark = (-turn * palette.shadowStrength + steep * palette.slopeStrength).coerceIn(0.0, MAX_SHADOW)
    val bright = (turn * palette.lightStrength).coerceIn(0.0, MAX_LIGHT)
    return mixArgb(mixArgb(tint, palette.shadowArgb, dark), palette.lightArgb, bright)
}

private fun tintAt(height: Float, palette: ReliefPalette): Int {
    val stops = palette.heights
    val above = stops.indexOfFirst { height <= it }
    return when {
        above == 0 -> palette.argb.first()

        above < 0 -> palette.argb.last()

        else -> {
            val share = (height - stops[above - 1]) / (stops[above] - stops[above - 1])
            mixArgb(palette.argb[above - 1], palette.argb[above], share.toDouble())
        }
    }
}

/** [from] blended toward [to] by [share], channel by channel; opaque, as the relief always is. */
internal fun mixArgb(from: Int, to: Int, share: Double): Int {
    if (share <= 0.0) return from
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * share + 0.5).toInt().coerceIn(0, 0xFF)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/**
 * How much steeper than true the slopes are drawn on cells [cellMetres] across: three times on the
 * one-minute cells of the finest level, about six and a half on four-minute cells, rising as the
 * cells grow, because averaging flattens the ground the coarser it is sampled.
 */
internal fun exaggeration(cellMetres: Double): Double =
    (BASE_EXAGGERATION * (cellMetres / FINEST_CELL_METRES).pow(EXAGGERATION_GROWTH)).coerceAtMost(MAX_EXAGGERATION)

/**
 * Where the cells' picture lies on the map, and how many texel rows it takes: rows spaced evenly in
 * Mercator, so that the picture's rows are the map's rows. The spacing is that of a cell row where the
 * region comes nearest the equator, where Mercator stretches a cell least, so no cell row anywhere
 * falls between two texel rows and is lost.
 */
internal class ReliefFrame(private val cells: ReliefCells) {
    val top: Double
    val height: Int
    val texelY: Double
    val left = cells.firstColumn * cells.cellDegrees / DEGREES_PER_TURN
    val texelX = cells.cellDegrees / DEGREES_PER_TURN
    val low = cells.heights.min()
    val span = (cells.heights.max() - low).coerceAtLeast(1f)

    init {
        val northEdge = NORTH_POLE - cells.firstRow * cells.cellDegrees
        val southEdge = NORTH_POLE - (cells.firstRow + cells.rows) * cells.cellDegrees
        top = mercatorY(northEdge)
        val bottom = mercatorY(southEdge)
        val nearestEquator = if (northEdge >= 0.0 && southEdge <= 0.0) 0.0 else minOf(abs(northEdge), abs(southEdge))
        val rowSpan = cells.cellDegrees / DEGREES_PER_TURN / cos(nearestEquator * PI / HALF_TURN)
        height = ceil((bottom - top) / rowSpan).toInt().coerceIn(1, MAX_RELIEF_TEXELS)
        texelY = (bottom - top) / height
    }

    /**
     * Every texel row, top to bottom, to [onRow]: its colours, each texel row taking its latitude's
     * place between the two shaded cell rows around it, and its heights in grey, from [low] at black
     * to [low] + [span] at white. The two arrays are filled again for the next row, and each cell row
     * is shaded once, as the texel rows pass it: nothing the size of the picture is held but the
     * picture itself.
     */
    fun rows(palette: ReliefPalette, lightFromDeg: Float, onRow: (y: Int, tints: IntArray, greys: IntArray) -> Unit) {
        val rows = cells.rows
        val columns = cells.columns
        val heights = cells.heights
        val shader = RowShader(cells, palette, lightFromDeg)
        var upper = IntArray(columns)
        var lower = IntArray(columns)
        var upperRow = -1
        var lowerRow = -1
        val tints = IntArray(columns)
        val greys = IntArray(columns)
        for (y in 0 until height) {
            val latitude = latitudeAt(top + (y + 0.5) * texelY)
            val exact = ((NORTH_POLE - latitude) / cells.cellDegrees - 0.5 - cells.firstRow).coerceIn(0.0, rows - 1.0)
            val above = exact.toInt()
            val below = (above + 1).coerceAtMost(rows - 1)
            val share = exact - above
            // The rows only ever move south, so the lower row of one texel row is the upper of a later one.
            if (above != upperRow) {
                if (above == lowerRow) {
                    upper = lower.also { lower = upper }
                    lowerRow = -1
                } else {
                    shader.paint(above, upper)
                }
                upperRow = above
            }
            if (below != lowerRow) {
                shader.paint(below, lower)
                lowerRow = below
            }
            for (x in 0 until columns) {
                tints[x] = mixArgb(upper[x], lower[x], share)
                val metres = heights[above * columns + x] * (1 - share) + heights[below * columns + x] * share
                val grey = ((metres - low) / span * GREY_STEPS).toInt().coerceIn(0, GREY_STEPS)
                greys[x] = OPAQUE or (grey shl RED_SHIFT) or (grey shl GREEN_SHIFT) or grey
            }
            onRow(y, tints, greys)
        }
    }
}

/** The cells shaded and laid onto the map's rows: the colours in one picture, the heights in another. */
private fun mercatorImage(cells: ReliefCells, palette: ReliefPalette, lightFromDeg: Float): ReliefImage {
    val frame = ReliefFrame(cells)
    val columns = cells.columns
    val bitmap = createBitmap(columns, frame.height)
    val heights = createBitmap(columns, frame.height)
    frame.rows(palette, lightFromDeg) { y, tints, greys ->
        bitmap.setPixels(tints, 0, columns, 0, y, columns, 1)
        heights.setPixels(greys, 0, columns, 0, y, columns, 1)
    }
    return ReliefImage(bitmap, frame.left, frame.top, frame.texelX, frame.texelY, cells, heights, frame.low, frame.span)
}

/** The latitude at Mercator [y], south from the top, 0 to 1 — [mercatorY] the other way. */
internal fun latitudeAt(y: Double): Double = atan(sinh(PI * (1 - 2 * y.coerceIn(0.0, 1.0)))) * HALF_TURN / PI

/**
 * Draws a [ReliefImage] on the map, smoothed by a cubic B-spline on the GPU. The picture is a texel
 * a cell, and at a walk's zoom a cell is a thousand pixels across: the filtering a bitmap gets for
 * free would draw it as a quilt of creased diamonds, where the spline bends smoothly through every
 * texel and its slope with it, so a hillside stays a hillside however close the view comes. Four
 * filtered reads a pixel do it — the weights folded into where each read lands, as GPU Gems 2
 * describes — and a pan or a pinch only moves two numbers.
 */
class ReliefShading private constructor(private val shader: RuntimeShader) {
    private var bound: Bitmap? = null
    private val brush = ShaderBrush(shader)

    /** Paints [image] where it lies under [camera], clipped by the caller to the land. */
    fun DrawScope.drawRelief(image: ReliefImage, camera: MapCamera) {
        val scale = camera.pixelsPerUnit
        if (scale <= 0.0) return
        if (bound !== image.bitmap) {
            val texture = BitmapShader(image.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            texture.filterMode = BitmapShader.FILTER_MODE_LINEAR
            shader.setInputShader("relief", texture)
            bound = image.bitmap
        }
        val corner = camera.screenPosition(image.left, image.top, size)
        val texelX = (image.texelX * scale).toFloat()
        val texelY = (image.texelY * scale).toFloat()
        shader.setFloatUniform("origin", corner.x, corner.y)
        shader.setFloatUniform("texel", texelX, texelY)
        val left = corner.x.coerceAtLeast(0f)
        val top = corner.y.coerceAtLeast(0f)
        val right = (corner.x + image.bitmap.width * texelX).coerceAtMost(size.width)
        val bottom = (corner.y + image.bitmap.height * texelY).coerceAtMost(size.height)
        if (right <= left || bottom <= top) return
        drawRect(brush, Offset(left, top), Size(right - left, bottom - top))
    }

    companion object {
        /** Null where the renderer refuses to build the shader; the map then goes without relief. */
        fun createOrNull(): ReliefShading? = try {
            ReliefShading(RuntimeShader(RELIEF_SHADER))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * The B-spline as four bilinear reads. `origin` is where the picture's corner lands on the canvas
 * and `texel` how many pixels one texel spans; texel centres sit at whole numbers plus a half.
 */
private const val RELIEF_SHADER = """
uniform shader relief;
uniform float2 origin;
uniform float2 texel;

half4 main(float2 p) {
    float2 t = (p - origin) / texel - 0.5;
    float2 i = floor(t);
    float2 f = t - i;
    float2 f2 = f * f;
    float2 f3 = f2 * f;
    float2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    float2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    float2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
    float2 w3 = f3 / 6.0;
    float2 g0 = w0 + w1;
    float2 g1 = w2 + w3;
    float2 a = i - 0.5 + w1 / g0;
    float2 b = i + 1.5 + w3 / g1;
    float4 upper = g0.x * float4(relief.eval(float2(a.x, a.y))) + g1.x * float4(relief.eval(float2(b.x, a.y)));
    float4 lower = g0.x * float4(relief.eval(float2(a.x, b.y))) + g1.x * float4(relief.eval(float2(b.x, b.y)));
    return half4(g0.y * upper + g1.y * lower);
}
"""

private const val DEGREES_PER_TURN = 360.0

/** The heights picture's grey from black to white: a byte's worth. */
private const val GREY_STEPS = 255

/** An opaque ARGB colour's alpha, and where its red and green sit: the grey written without android.graphics. */
private const val OPAQUE = 0xFF shl 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/** The texels a cubic B-spline reads on either side of the point it draws. */
private const val SPLINE_REACH = 2
private const val HALF_TURN = 180.0
private const val NORTH_POLE = 90.0
private const val METRES_PER_DEGREE = 111_320.0

/** Past this a degree of longitude is too short to divide by: the slope is taken as at 89.4°. */
private const val MIN_COS_LATITUDE = 0.01

/** The light's direction, from the north-west and 45° up: its east and north parts, and its height. */
private const val LIGHT_HALF = 0.5
private const val LIGHT_UP = 0.7071067811865476

/** How far across the light's direction lies, 45° up: the length of its east and north parts together. */
private const val LIGHT_LEVEL = 0.7071067811865476

/**
 * The side the relief is lit from, in degrees clockwise from north: the north-west, the upper left of
 * a map with the north up, as every shaded map is lit. Turned with the map, it stays the upper left.
 */
internal const val RELIEF_LIGHT_FROM = 315f

/** How far the map turns before the light is moved round with it, in degrees. */
internal const val RELIEF_LIGHT_STEP = 30f

/** No slope is ever shadowed or lit all the way to the shadow or the light colour. */
private const val MAX_SHADOW = 0.75
private const val MAX_LIGHT = 0.6

/** A minute of arc of latitude: the cell the exaggeration is reckoned from. */
private const val FINEST_CELL_METRES = 1_852.0
private const val BASE_EXAGGERATION = 3.0
private const val EXAGGERATION_GROWTH = 0.55
private const val MAX_EXAGGERATION = 16.0

/** No texture past this on a side: a whole phone screen at four pixels a cell fits well inside it. */
private const val MAX_RELIEF_TEXELS = 2_048
