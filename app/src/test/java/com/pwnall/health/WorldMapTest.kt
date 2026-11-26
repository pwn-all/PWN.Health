package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.util.zip.DeflaterOutputStream

class WorldMapTest {
    /** The reader is handed a way to open the asset, so bytes in hand stand in for the APK. */
    private fun opener(bytes: ByteArray): () -> ByteArrayInputStream = { ByteArrayInputStream(bytes) }

    private fun varint(value: Int, out: MutableList<Byte>) {
        var rest = value
        while (rest >= 0x80) {
            out.add(((rest and 0x7F) or 0x80).toByte())
            rest = rest ushr 7
        }
        out.add(rest.toByte())
    }

    private fun zigzag(value: Int, out: MutableList<Byte>) = varint((value shl 1) xor (value shr 31), out)

    private fun int32(value: Int, out: MutableList<Byte>) =
        repeat(4) { out.add(((value ushr (it * 8)) and 0xFF).toByte()) }

    /**
     * One level, one land line on a hundredth-degree grid, no regions: from [fromLongitude] hundredths
     * of a degree east on the equator, one degree east and two north.
     */
    private fun fixture(fromLongitude: Int = 0): ByteArray {
        val body = mutableListOf<Byte>()
        varint(2, body) // layers
        varint(1, body) // land lines
        varint(2, body) // points
        zigzag(fromLongitude, body)
        zigzag(0, body)
        zigzag(100, body) // +1 degree east
        zigzag(200, body) // +2 degrees north
        varint(0, body) // region lines
        // Each level's body is its own zlib stream.
        val packed = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(body.toByteArray()) }
        }.toByteArray()

        val head = mutableListOf<Byte>()
        "PWNMAP3".forEach { head.add(it.code.toByte()) }
        varint(1, head) // levels
        varint(100, head) // units per degree
        int32(head.size + 8, head)
        int32(packed.size, head)
        return head.toByteArray() + packed
    }

    @Test
    fun aLineSurvivesTheRoundTrip() {
        val level = parseWorldMap(opener(fixture())).level(0, WHOLE_WORLD)

        assertEquals(2, level.layers.size)
        assertEquals(1, level.layers[0].size)
        assertEquals(0, level.layers[1].size)
        val line = level.layers[0].single()
        assertEquals(mercatorX(0.0).toFloat(), line.points[0], 1e-7f)
        assertEquals(mercatorY(0.0).toFloat(), line.points[1], 1e-7f)
        assertEquals(mercatorX(1.0).toFloat(), line.points[2], 1e-7f)
        assertEquals(mercatorY(2.0).toFloat(), line.points[3], 1e-7f)
    }

    @Test
    fun theBoxIsTheLineItHolds() {
        val line = parseWorldMap(opener(fixture())).level(0, WHOLE_WORLD).layers[0].single()

        assertEquals(line.points[0], line.minX, 0f)
        assertEquals(line.points[2], line.maxX, 0f)
        // North is a smaller y: the second point is the top of the box, not the bottom.
        assertEquals(line.points[3], line.minY, 0f)
        assertEquals(line.points[1], line.maxY, 0f)
    }

    @Test
    fun mercatorPutsTheWorldInAUnitSquare() {
        assertEquals(0.5, mercatorX(0.0), 1e-12)
        assertEquals(0.0, mercatorX(-180.0), 1e-12)
        assertEquals(1.0, mercatorX(180.0), 1e-12)
        assertEquals(0.5, mercatorY(0.0), 1e-12)
        assertEquals(0.0, mercatorY(MERCATOR_MAX_LAT), 1e-6)
        assertEquals(1.0, mercatorY(-MERCATOR_MAX_LAT), 1e-6)
        // The poles are past where the projection ends; they are pinned, not sent to infinity.
        assertEquals(0.0, mercatorY(90.0), 1e-6)
    }

    /**
     * The asset that ships in the APK, read by the code that will read it on a phone. This is the
     * check that fails when tools/world-map.py and the reader stop agreeing.
     */
    @Test
    fun theShippedMapReads() {
        val file = sequenceOf("src/main/assets", "app/src/main/assets")
            .map { File(it, WORLD_MAP_ASSET) }
            .first(File::isFile)
        val map = parseWorldMap(opener(file.readBytes()))

        assertTrue(map.levelCount >= 2)
        val globe = map.level(map.levelFor(1.0), WHOLE_WORLD)
        val street = map.level(map.levelFor(1e-6), WHOLE_WORLD)

        assertEquals(MAP_LAYERS, globe.layers.size)
        // Every pen has something to draw up close; from the globe, what is drawn at that zoom does,
        // and what is drawn only closer in is not carried at all.
        assertTrue(street.layers.all { it.isNotEmpty() })
        GLOBE_LAYERS.forEach { assertTrue("layer $it", globe.layers[it].isNotEmpty()) }
        CLOSER_IN_LAYERS.forEach { assertTrue("layer $it", globe.layers[it].isEmpty()) }
        // Zooming in has to buy detail, or the levels are not levels.
        assertTrue(street.layers.sumOf { it.size } > globe.layers.sumOf { it.size })
        val outside = street.layers.flatten()
            .count { it.minX < 0f || it.maxX > 1f || it.minY < 0f || it.maxY > 1f }
        assertEquals(0, outside)
    }

    /**
     * `skip` may stop short of what it was asked, even at nothing, with the stream still running. Read
     * from wherever the stream had got to, the level would be read here from its first byte, the header.
     */
    @Test
    fun aStreamThatSkipsNothingStillReadsTheLevelAtItsOffset() {
        val bytes = fixture()
        val stingy = {
            object : FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun skip(n: Long) = 0L
            }
        }

        val line = parseWorldMap(stingy).level(0, WHOLE_WORLD).layers[0].single()

        assertEquals(mercatorX(1.0).toFloat(), line.points[2], 1e-7f)
        assertEquals(mercatorY(2.0).toFloat(), line.points[3], 1e-7f)
    }

    /**
     * A route unwrapped across the antimeridian is framed past the map's edge, and the land beyond the
     * antimeridian is at the file's other edge: read only where the file has it, Taveuni's east would be sea.
     */
    @Test
    fun theLandPastTheAntimeridianIsReadAWholeTurnOver() {
        // From 179.9° W, a tenth of a degree past 180° E.
        val map = parseWorldMap(opener(fixture(fromLongitude = -17_990)))

        val past = map.level(0, Rect(0.999f, 0.49f, 1.01f, 0.51f)).layers[0].single()
        val here = map.level(0, WHOLE_WORLD).layers[0].single()

        assertEquals(1 + mercatorX(-179.9).toFloat(), past.points[0], 1e-6f)
        assertEquals(1 + mercatorX(-178.9).toFloat(), past.points[2], 1e-6f)
        assertEquals(past.points[0], past.minX, 0f)
        assertEquals(here.points[1], past.points[1], 0f)
        // The world as the file has it holds the line once, where the file puts it.
        assertEquals(mercatorX(-179.9).toFloat(), here.points[0], 1e-7f)
    }

    @Test
    fun onlyARegionPastAnEdgeReachesAWholeTurnOver() {
        assertEquals(0..0, turnsInto(WHOLE_WORLD))
        assertEquals(0..1, turnsInto(Rect(0.99f, 0.4f, 1.01f, 0.5f)))
        assertEquals(-1..0, turnsInto(Rect(-0.01f, 0.4f, 0.01f, 0.5f)))
        assertEquals(-1..1, turnsInto(Rect(-0.01f, 0.4f, 1.01f, 0.5f)))
    }

    @Test
    fun aLineOutsideTheRegionIsNotKept() {
        val map = parseWorldMap(opener(fixture()))

        val elsewhere = map.level(0, Rect(0.9f, 0.9f, 0.95f, 0.95f))
        assertEquals(0, elsewhere.layers[0].size)
        // The box the line does fall in still yields it, so this is a filter and not a break.
        val here = map.level(0, Rect(0.49f, 0.48f, 0.52f, 0.51f))
        assertEquals(1, here.layers[0].size)
    }

    /**
     * The memory guard. The finest level is the whole planet, and it is only ever chosen at a zoom
     * where a screen holds a fraction of a degree — so it has to arrive holding a fraction of it.
     */
    @Test
    fun aStreetZoomOnlyKeepsTheStreetsNearby() {
        val file = sequenceOf("src/main/assets", "app/src/main/assets")
            .map { File(it, WORLD_MAP_ASSET) }
            .first(File::isFile)
        val map = parseWorldMap(opener(file.readBytes()))
        val finest = map.levelCount - 1

        // Half a degree around Moscow, which is roughly what a phone shows at that level.
        val x = mercatorX(37.62).toFloat()
        val y = mercatorY(55.75).toFloat()
        val here = map.level(finest, Rect(x - 0.0007f, y - 0.0007f, x + 0.0007f, y + 0.0007f))
        val everywhere = map.level(finest, WHOLE_WORLD)

        val nearby = here.layers.sumOf { lines -> lines.sumOf { it.points.size } }
        val whole = everywhere.layers.sumOf { lines -> lines.sumOf { it.points.size } }
        assertTrue(nearby > 0)
        assertTrue("kept $nearby of $whole", nearby * 100 < whole)
    }

    @Test
    fun longitudesUnwrapToTheSideOfTheReference() {
        assertEquals(180.0003, unwrapLongitude(-179.9997, 179.9997), 1e-9)
        assertEquals(-180.0003, unwrapLongitude(179.9997, -179.9997), 1e-9)
        // Nothing to do within half a turn, and the edge itself is left where it is.
        assertEquals(37.61, unwrapLongitude(37.61, 55.0), 0.0)
        assertEquals(-179.0, unwrapLongitude(-179.0, 0.0), 0.0)
        // Just past the edge on the map, a few metres from where it was, rather than at the other one.
        assertTrue(mercatorX(unwrapLongitude(-179.9997, 179.9997)) > 1.0)
        assertEquals(mercatorX(179.9997), mercatorX(unwrapLongitude(-179.9997, 179.9997)), 2e-6)
    }

    /**
     * Greenland and Antarctica are left out of the map: nothing at the finest level lies wholly south of
     * 60° S, and nothing inside a box well within Greenland's ice.
     */
    @Test
    fun greenlandAndAntarcticaAreLeftOut() {
        val file = sequenceOf("src/main/assets", "app/src/main/assets")
            .map { File(it, WORLD_MAP_ASSET) }
            .first(File::isFile)
        val map = parseWorldMap(opener(file.readBytes()))
        val street = map.level(map.levelCount - 1, WHOLE_WORLD)
        val south = mercatorY(-60.0).toFloat()
        val ice =
            Rect(
                mercatorX(-50.0).toFloat(),
                mercatorY(80.0).toFloat(),
                mercatorX(-30.0).toFloat(),
                mercatorY(65.0).toFloat(),
            )

        val lines = street.layers.flatten()
        assertEquals(0, lines.count { it.minY > south })
        assertEquals(
            0,
            lines.count {
                ice.contains(Offset(it.minX, it.minY)) && ice.contains(Offset(it.maxX, it.maxY))
            },
        )
    }

    private companion object {
        /** land, lake, river, glacier, playa, stream. */
        const val MAP_LAYERS = 6

        /** Land, lakes, ice and salt flats: what a view of the whole world draws. */
        val GLOBE_LAYERS = listOf(LAND_LAYER, LAKE_LAYER, GLACIER_LAYER, SALT_LAYER)

        /** Rivers and streams: drawn only from a continent's view inward. */
        val CLOSER_IN_LAYERS = listOf(RIVER_LAYER, STREAM_LAYER)
    }
}
