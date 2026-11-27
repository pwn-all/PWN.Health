package com.pwnall.health

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * Where the forests are: a cell is forest when trees cover at least half of it, counted on ESA
 * WorldCover 2021's ten-metre map of what covers the ground (CC BY 4.0). Built by
 * `tools/world-forest.py` into `assets/world.forest`, 7.1 MB: five levels from sixteen minutes of arc
 * a cell down to half a minute, about 900 m in the middle latitudes — a wood a walk goes through. As
 * the relief does, it is read a level and a region at a time — see [cells].
 */
const val WORLD_FOREST_ASSET = "world.forest"

/** One level's table of contents: its grid, its tiles and where each lies in the file. */
class ForestLevel(
    val cellSeconds: Int,
    override val tileCells: Int,
    override val tilesX: Int,
    val tilesY: Int,
    /** Where each tile starts in the file, row by row from the north-west; unused for a tile without forest. */
    override val offsets: LongArray,
    /** Each tile's length in bytes, 0 for a tile with no forest in it. */
    override val lengths: IntArray,
) : TileLevel {
    override val columns: Int get() = FULL_TURN_SECONDS / cellSeconds
    val rows: Int get() = HALF_TURN_SECONDS / cellSeconds
    val cellDegrees: Double get() = cellSeconds / SECONDS_PER_DEGREE
}

/** The forests' table of contents, read once; a level's tiles are read only when a view asks. */
class WorldForest(private val open: () -> InputStream, val levels: List<ForestLevel>) {

    /** The coarsest level whose cells are no wider than [degrees], or the finest there is. */
    fun levelFor(degrees: Double): Int =
        levels.indices.firstOrNull { levels[it].cellDegrees <= degrees } ?: levels.lastIndex

    /**
     * Whether each of the [rowCount] × [columnCount] cells of level [index] from [firstRow] and
     * [firstColumn] is forest, row by row. Columns run on round the antimeridian in either direction,
     * as an unwrapped route does; rows must lie inside the level. Blocking — never on the main thread.
     */
    fun cells(index: Int, firstRow: Int, rowCount: Int, firstColumn: Int, columnCount: Int): BooleanArray {
        val level = levels[index]
        val size = level.tileCells
        val out = BooleanArray(rowCount * columnCount)
        val column = IntArray(columnCount) { (firstColumn + it).mod(level.columns) }
        readTiles(open, level, firstRow, rowCount, column) { bytes, ty, tx ->
            val bits = decodeForestTile(bytes, size)
            val rowFrom = maxOf(firstRow, ty * size)
            val rowTo = minOf(firstRow + rowCount, (ty + 1) * size)
            for (row in rowFrom until rowTo) {
                val source = (row - ty * size) * size - tx * size
                val target = (row - firstRow) * columnCount
                column.forEachIndexed { w, col ->
                    if (col / size == tx) out[target + w] = bits[source + col]
                }
            }
        }
        return out
    }
}

/**
 * One tile's cells: [size] rows of [size] bits, eight cells a byte with the westernmost in the high
 * bit, as NumPy's packbits writes them.
 */
internal fun decodeForestTile(bytes: ByteArray, size: Int): BooleanArray {
    val packed = InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    val rowBytes = (size + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
    require(packed.size == rowBytes * size) { "a forest tile of ${packed.size} bytes, not ${rowBytes * size}" }
    return BooleanArray(size * size) { index ->
        val row = index / size
        val col = index % size
        val byte = packed[row * rowBytes + col / Byte.SIZE_BITS].toInt()
        byte and (HIGH_BIT ushr (col % Byte.SIZE_BITS)) != 0
    }
}

private const val HIGH_BIT = 0x80

/** Reads the table of contents `tools/world-forest.py` writes; that file documents the format. */
fun parseWorldForest(open: () -> InputStream): WorldForest {
    val levels = open().use { stream ->
        val reader = VarintReader(stream)
        require(FOREST_MAGIC.all { reader.byte() == it.code }) { "not a world forest" }
        val headers = List(reader.next()) {
            val cellSeconds = reader.next()
            val tileCells = reader.next()
            val tilesX = reader.next()
            val tilesY = reader.next()
            val lengths = IntArray(tilesX * tilesY) { reader.next() }
            ForestLevel(cellSeconds, tileCells, tilesX, tilesY, LongArray(lengths.size), lengths)
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
    return WorldForest(open, levels)
}

private const val FOREST_MAGIC = "PWNFOR1"
private const val SECONDS_PER_DEGREE = 3_600.0
private const val FULL_TURN_SECONDS = 360 * 3_600
private const val HALF_TURN_SECONDS = 180 * 3_600

/** One table of contents for the process, as the map's own — see [rememberWorldMap]. */
private var parsedWorldForest: WorldForest? = null

/** Null until the asset is read, or for good if it cannot be: the map then simply has no forests. */
@Composable
fun rememberWorldForest(): WorldForest? {
    val assets = LocalContext.current.applicationContext.assets
    return produceState(parsedWorldForest) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { parseWorldForest { assets.open(WORLD_FOREST_ASSET) } }.getOrNull()
            }?.also { parsedWorldForest = it }
        }
    }.value
}
