package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

class WorldTownsAndForestTest {
    private fun asset(name: String): File =
        sequenceOf("src/main/assets", "app/src/main/assets").map { File(it, name) }.first(File::isFile)

    private fun varint(out: ByteArrayOutputStream, value: Int) {
        var left = value
        while (left >= 0x80) {
            out.write((left and 0x7F) or 0x80)
            left = left ushr 7
        }
        out.write(left)
    }

    private fun zigzag(out: ByteArrayOutputStream, value: Int) = varint(out, (value shl 1) xor (value shr 31))

    @Test
    fun aListOfTownsReadsBack() {
        val body = ByteArrayOutputStream()
        // Brno at 49.195, 16.608, round, 4 km; then Vienna, 48.208, 16.373, 9 km, twice as long as it
        // is wide and turned 45° from east toward south — deltas in thousandths.
        zigzag(body, 16_608)
        zigzag(body, 49_195)
        varint(body, 80 shl 1)
        zigzag(body, 16_373 - 16_608)
        zigzag(body, 48_208 - 49_195)
        varint(body, 180 shl 1 or 1)
        body.write(4 shl 4 or 4)
        val file = ByteArrayOutputStream()
        file.write("PWNTOWN2".toByteArray())
        varint(file, 2)
        DeflaterOutputStream(file).use { it.write(body.toByteArray()) }

        val towns = parseWorldTowns(file.toByteArray())
        assertEquals(2, towns.size)
        assertEquals(mercatorX(16.608).toFloat(), towns.x[0], 1e-7f)
        assertEquals(mercatorY(48.208).toFloat(), towns.y[1], 1e-7f)
        // An ellipse as large as a 9 km circle and twice as long as wide reaches 9 km × √2.
        val reach = floatArrayOf(
            mapUnitsFor(4_000.0, mercatorY(49.195)).toFloat(),
            mapUnitsFor(9_000.0 * sqrt(2.0), mercatorY(48.208)).toFloat(),
        )
        assertArrayEquals(reach, towns.reach, 1e-9f)
        assertEquals(1f, towns.narrowness(0), 0f)
        assertEquals(0.5f, towns.narrowness(1), 1e-6f)
        assertEquals(45f, towns.turnDegrees(1), 0f)
        val far = 0.9 * towns.reach[1]
        // Toward the south-east the long axis reaches; toward the north-east only half as far.
        assertTrue(towns.covers(1, far * sqrt(0.5), far * sqrt(0.5)))
        assertTrue(!towns.covers(1, far * sqrt(0.5), -far * sqrt(0.5)))
        assertTrue(towns.covers(1, 0.4 * far * sqrt(0.5), -0.4 * far * sqrt(0.5)))
    }

    /**
     * A village of about a thousand must not show as a blot three kilometres north of it, in fields:
     * Natural Earth does not have the village at all, and such a blot is one of its 1 km satellite
     * pixels' guesses. The point checked is the village's centre as the shipped towns give it.
     */
    @Test
    fun aVillageSitsWhereTheVillageIs() {
        val towns = parseWorldTowns(asset(WORLD_TOWNS_ASSET).readBytes())
        fun covered(lat: Double, lon: Double) = (0 until towns.size).filter {
            towns.covers(it, mercatorX(lon) - towns.x[it], mercatorY(lat) - towns.y[it])
        }
        val start = covered(48.836, 16.5)
        assertTrue("the village's centre", start.isNotEmpty())
        val middle = start.minOf {
            hypot(towns.x[it] - mercatorX(16.5), towns.y[it] - mercatorY(48.836))
        }
        assertTrue("the village's middle", middle <= mapUnitsFor(250.0, mercatorY(48.836)))
        assertEquals("the fields to the north", emptyList<Int>(), covered(48.867, 16.506))
    }

    @Test
    fun theShippedTownsHoldTheWorldsTownsButNotAntarcticas() {
        val towns = parseWorldTowns(asset(WORLD_TOWNS_ASSET).readBytes())
        assertTrue(towns.size > 10_000)
        val brno = (0 until towns.size).any {
            abs(towns.x[it] - mercatorX(16.6).toFloat()) < 2e-4f && abs(towns.y[it] - mercatorY(49.2).toFloat()) < 2e-4f
        }
        assertTrue("Brno", brno)
        val south = mercatorY(-60.0).toFloat()
        assertEquals(0, (0 until towns.size).count { towns.y[it] > south })
    }

    @Test
    fun aForestTileReadsItsBitsWestFirst() {
        val size = 16
        val packed = ByteArray(size * size / 8)
        // Row 0: the westernmost cell; row 3: the ninth.
        packed[0] = 0x80.toByte()
        packed[3 * 2 + 1] = 0x80.toByte()
        val bytes = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(packed) }
        }.toByteArray()

        val cells = decodeForestTile(bytes, size)
        assertTrue(cells[0])
        assertTrue(cells[3 * size + 8])
        assertEquals(2, cells.count { it })
    }

    /** The asset that ships, read by the code that reads it on a phone. */
    @Test
    fun theShippedForestsAreWhereTheForestsAre() {
        val file = asset(WORLD_FOREST_ASSET)
        val forest = parseWorldForest { ByteArrayInputStream(file.readBytes()) }
        assertEquals(listOf(960, 480, 240, 120, 30), forest.levels.map { it.cellSeconds })
        val finest = forest.levels.lastIndex

        fun forested(latitude: Double, longitude: Double): Boolean {
            val cell = forest.levels[finest].cellDegrees
            val row = floor((90.0 - latitude) / cell).toInt()
            val column = floor((longitude + 180.0) / cell).toInt()
            return forest.cells(finest, row, 1, column, 1)[0]
        }

        // The Black Forest, the taiga by Lake Baikal and the Amazon are forest.
        assertTrue(forested(48.30, 8.25))
        assertTrue(forested(52.00, 105.00))
        assertTrue(forested(-5.00, -62.00))
        // The Sahara, the North Sea and Greenland's ice are not.
        assertTrue(!forested(23.00, 10.00))
        assertTrue(!forested(56.00, 3.00))
        assertTrue(!forested(72.00, -40.00))
    }
}
