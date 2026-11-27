package com.pwnall.health

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the towns are: the middle of the ground built up round every place GeoNames counts five
 * hundred people in, a circle as large as that ground, and an ellipse of the same area where the
 * ground runs one way, written by `tools/world-towns.py` into `assets/world.towns`. A town on the
 * dotted map is a blot of dots, and a blot needs no outline: the outlines would be a megabyte, and
 * nobody walking out of one looks at its edge on this map.
 */
const val WORLD_TOWNS_ASSET = "world.towns"

/**
 * Every town, projected once: middles in Mercator units, as the map's own lines are, and [reach], the
 * long half axis of its ellipse, in the same units — for a round town, its radius. The reach is turned
 * from metres here rather than per frame: the map walks every town each frame it draws them, and the
 * latitude a Mercator stretch needs costs an arctangent a town. [shape] is the file's byte, 0 for a
 * round town: the stretch in its high four bits, the turn in its low four.
 */
class WorldTowns(val x: FloatArray, val y: FloatArray, val reach: FloatArray, val shape: ByteArray) {
    val size: Int get() = x.size

    /** The short half axis over the long one: 1 for a round town. */
    fun narrowness(town: Int): Float = TOWN_NARROWNESS[(shape[town].toInt() and BYTE) ushr TURN_BITS]

    /** The long axis's angle from east toward south, in degrees: south lies down on the map. */
    fun turnDegrees(town: Int): Float = (shape[town].toInt() and TURN_MASK) * TOWN_TURN_DEGREES

    /** Whether the point [dx], [dy] map units from [town]'s middle lies on its ground. */
    fun covers(town: Int, dx: Double, dy: Double): Boolean {
        val long = reach[town].toDouble()
        val code = shape[town].toInt() and BYTE
        if (code == 0) return dx * dx + dy * dy <= long * long
        val short = long * TOWN_NARROWNESS[code ushr TURN_BITS]
        val turn = code and TURN_MASK
        val along = (dx * TOWN_TURN_COS[turn] + dy * TOWN_TURN_SIN[turn]) / long
        val across = (dy * TOWN_TURN_COS[turn] - dx * TOWN_TURN_SIN[turn]) / short
        return along * along + across * across <= 1.0
    }
}

/**
 * Reads the format `tools/world-towns.py` documents. The file is small enough to take whole, which
 * spares the header reader's look-ahead from eating into the stream the inflater wants.
 */
fun parseWorldTowns(bytes: ByteArray): WorldTowns {
    val header = VarintReader(ByteArrayInputStream(bytes))
    require(TOWNS_MAGIC.all { header.byte() == it.code }) { "not a list of towns" }
    val count = header.next()
    val bodyAt = header.position.toInt()
    val x = FloatArray(count)
    val y = FloatArray(count)
    val reach = FloatArray(count)
    val shape = ByteArray(count)
    // Closed once read, or its inflater's native zlib memory waits on the finaliser.
    InflaterInputStream(ByteArrayInputStream(bytes, bodyAt, bytes.size - bodyAt)).use { inflated ->
        val body = VarintReader(inflated)
        var lon = 0
        var lat = 0
        for (index in 0 until count) {
            lon += body.signed()
            lat += body.signed()
            x[index] = mercatorX(lon.toDouble() / TOWN_PLACES).toFloat()
            y[index] = mercatorY(lat.toDouble() / TOWN_PLACES).toFloat()
            val radius = body.next()
            val code = if (radius and 1 == 1) body.byte() else 0
            shape[index] = code.toByte()
            // The file gives the radius of a circle of the town's area; an ellipse that large reaches
            // further along its long axis by the square root of its stretch.
            val stretch = 1.0 / TOWN_NARROWNESS[code ushr TURN_BITS]
            val metres = (radius ushr 1) * TOWN_RADIUS_UNIT_M * sqrt(stretch)
            reach[index] = mapUnitsFor(metres, y[index].toDouble()).toFloat()
        }
    }
    return WorldTowns(x, y, reach, shape)
}

private const val TOWNS_MAGIC = "PWNTOWN2"

/** A town's middle is written in thousandths of a degree, its radius in steps of this many metres. */
private const val TOWN_PLACES = 1_000.0
private const val TOWN_RADIUS_UNIT_M = 50.0

/** A step of stretch is the fourth root of two; sixteen turns share half a circle. */
private const val TURN_BITS = 4
private const val TURN_MASK = 15
private const val BYTE = 0xFF
private const val TOWN_TURN_DEGREES = 180f / 16
private val TOWN_NARROWNESS = FloatArray(16) { 2f.pow(-it / 4f) }
private val TOWN_TURN_COS = DoubleArray(16) { cos(Math.toRadians(it * TOWN_TURN_DEGREES.toDouble())) }
private val TOWN_TURN_SIN = DoubleArray(16) { sin(Math.toRadians(it * TOWN_TURN_DEGREES.toDouble())) }

/** One list for the process, as the map's own table of contents — see [rememberWorldMap]. */
private var parsedWorldTowns: WorldTowns? = null

/** Null until the asset is read, or for good if it cannot be: the map then simply has no towns. */
@Composable
fun rememberWorldTowns(): WorldTowns? {
    val assets = LocalContext.current.applicationContext.assets
    return produceState(parsedWorldTowns) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    assets.open(WORLD_TOWNS_ASSET).use(InputStream::readBytes).let(::parseWorldTowns)
                }.getOrNull()
            }?.also { parsedWorldTowns = it }
        }
    }.value
}
