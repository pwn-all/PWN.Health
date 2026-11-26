package com.pwnall.health

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.DeflaterOutputStream

/**
 * The relief under the map: the tile format tools/world-relief.py writes, the asset that ships, and
 * the light the phone shades it with.
 */
class WorldReliefTest {
    /** A tile as the encoder writes it: MED residuals, zigzag varints, one zlib stream. */
    private fun encodeTile(cells: IntArray, size: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { out ->
            for (index in cells.indices) {
                val row = index / size
                val col = index % size
                val west = if (col > 0) cells[index - 1] else 0
                val north = if (row > 0) cells[index - size] else 0
                val corner = if (row > 0 && col > 0) cells[index - size - 1] else 0
                writeZigzag(out, cells[index] - medPrediction(west, north, corner))
            }
        }
        return bytes.toByteArray()
    }

    private fun writeZigzag(out: OutputStream, value: Int) {
        var rest = (value shl 1) xor (value shr 31)
        while (rest >= 0x80) {
            out.write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
    }

    @Test
    fun aTileSurvivesTheRoundTrip() {
        val size = 16
        // A ridge, a pit below sea level and a cliff: every branch of the predictor, and steps of
        // more than one varint byte.
        val cells = IntArray(size * size) { index ->
            val row = index / size
            val col = index % size
            when {
                col == 8 -> 900
                row == 3 && col == 3 -> -55
                else -> row * 7 - col * 3
            }
        }

        assertArrayEquals(cells, decodeReliefTile(encodeTile(cells, size), size))
    }

    @Test
    fun theMedianEdgeDetectorTakesTheEdgeOrThePlane() {
        // A corner above both neighbours: an edge falling away, so the lower of the two.
        assertEquals(3, medPrediction(west = 3, north = 5, corner = 9))
        // Below both: an edge rising, so the higher.
        assertEquals(5, medPrediction(west = 3, north = 5, corner = 1))
        // Between them: the plane through all three.
        assertEquals(4, medPrediction(west = 3, north = 5, corner = 4))
    }

    @Test
    fun aFileThatIsNotOneIsRefused() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parseWorldRelief { ByteArrayInputStream("PWNMAP3".toByteArray()) }
        }
        assertEquals("not a world relief", error.message)
    }

    /**
     * The asset that ships, read by the code that reads it on a phone: four levels, coarse to fine,
     * and the heights of places whose heights are known. This is the check that fails when
     * tools/world-relief.py and the reader stop agreeing.
     */
    @Test
    fun theShippedReliefReadsTheGroundItShouldRead() {
        val file = sequenceOf("src/main/assets", "app/src/main/assets")
            .map { File(it, WORLD_RELIEF_ASSET) }
            .first(File::isFile)
        val bytes = file.readBytes()
        val relief = parseWorldRelief { ByteArrayInputStream(bytes) }

        // Sixteen, eight and four minutes, then the one minute itself: the two-minute level is left out.
        assertEquals(listOf(960, 480, 240, 60), relief.levels.map { it.cellSeconds })
        val finest = relief.levels.lastIndex

        fun height(latitude: Double, longitude: Double): Float {
            val level = relief.levels[finest]
            val row = ((90.0 - latitude) / level.cellDegrees).toInt()
            val column = ((longitude + 180.0) / level.cellDegrees).toInt()
            return relief.heights(finest, row, 1, column, 1).single()
        }

        // The Everest massif, averaged over a two-minute cell, is still far above anything else.
        assertTrue(height(27.99, 86.93) > 6_000f)
        // Bratislava stands on the Danube at about 140 m.
        assertTrue(height(48.15, 17.11) in 100f..260f)
        // The Atlantic is sea, and the sea is drawn flat at sea level.
        assertEquals(0f, height(0.0, -30.0), 0f)
        // ETOPO holds Baikal's bed, far below the sea; filled to its shores the lake is a surface
        // near its true 456 m, so its banks are not drawn as the rim of a pit.
        assertTrue(height(53.5, 108.2) in 400f..560f)
        // The Dead Sea's shore is the lowest dry ground there is, and stays below the sea.
        assertTrue(height(31.5, 35.5) < -100f)
        // Left out with the map: Greenland's ice cap and Antarctica's read as sea.
        assertEquals(0f, height(72.0, -40.0), 0f)
        assertEquals(0f, height(-80.0, 30.0), 0f)
    }

    @Test
    fun columnsRunOnRoundTheAntimeridian() {
        val file = sequenceOf("src/main/assets", "app/src/main/assets")
            .map { File(it, WORLD_RELIEF_ASSET) }
            .first(File::isFile)
        val bytes = file.readBytes()
        val relief = parseWorldRelief { ByteArrayInputStream(bytes) }
        val level = relief.levels.first()
        // 67° N, where Chukotka runs across the seam: ground on both sides of it, not sea.
        val row = 86

        // Two columns either side of the seam, read in one window from west of it, are the last two
        // and the first two columns of the level.
        val across = relief.heights(0, row, 1, -2, 4)
        val west = relief.heights(0, row, 1, level.columns - 2, 2)
        val east = relief.heights(0, row, 1, 0, 2)
        assertTrue(across.all { it > 100f })
        assertArrayEquals(west + east, across, 0f)
    }

    /**
     * The map, its relief, its forests and its towns ship inside the APK, in the 30 MB [WORLD_MAP_ASSET]
     * says the lot may weigh. A rebuild of any one of them that grows past it fails here rather than
     * quietly in the size of every download.
     */
    @Test
    fun theFourMapFilesStayInsideTheirThirtyMegabytes() {
        val assets = sequenceOf("src/main/assets", "app/src/main/assets")
            .map { File(it) }
            .first { File(it, WORLD_MAP_ASSET).isFile }
        val files = listOf(WORLD_MAP_ASSET, WORLD_RELIEF_ASSET, WORLD_FOREST_ASSET, WORLD_TOWNS_ASSET)
            .map { File(assets, it) }

        assertTrue(files.filterNot(File::isFile).toString(), files.all(File::isFile))
        val bytes = files.sumOf { it.length() }
        assertTrue("the map weighs $bytes bytes", bytes <= 30_000_000L)
    }

    @Test
    fun theLevelChosenIsTheCoarsestWhoseCellsStayAFewPixelsAcross() {
        val levels = listOf(960, 480, 240, 120).map {
            ReliefLevel(it, 256, 16, 1, 1, LongArray(1), IntArray(1))
        }
        val relief = WorldRelief({ ByteArrayInputStream(ByteArray(0)) }, levels)

        // A view of the whole world, before the first layout too, takes the coarsest.
        assertEquals(0, relief.levelFor(Double.MAX_VALUE))
        assertEquals(0, relief.levelFor(0.1))
        // A street zoom past every level falls back to the finest.
        assertEquals(3, relief.levelFor(1e-6))
        // In between, the cell may span up to four pixels: four minutes of arc at a fiftieth of a
        // degree a pixel, two at a hundredth.
        assertEquals(2, relief.levelFor(0.02))
        assertEquals(3, relief.levelFor(0.01))
    }

    @Test
    fun flatGroundKeepsItsTintAtAnyHeight() {
        val palette = DAY_MAP.relief
        palette.heights.forEachIndexed { index, height ->
            assertEquals(palette.colors[index].toArgb(), shade(height, 0.0, 0.0, palette))
        }
    }

    @Test
    fun aSlopeFacingTheLightIsLighterThanOneFacingAway() {
        val palette = DAY_MAP.relief
        // Rising to the south-east faces north-west, toward the light; rising to the north-west
        // faces away from it.
        val lit = Color(shade(500f, 0.3, -0.3, palette))
        val dark = Color(shade(500f, -0.3, 0.3, palette))
        val flat = Color(shade(500f, 0.0, 0.0, palette))
        assertTrue(lit.luminance() > flat.luminance())
        assertTrue(dark.luminance() < flat.luminance())
    }

    /**
     * Turned with the map, the light stays at the screen's upper left: sent from the south-east, it
     * lifts the slope the north-west light shadows, so a ridge keeps its shape whichever way one faces.
     */
    @Test
    fun theLightCanComeFromAnySide() {
        val palette = DAY_MAP.relief
        // Rising to the north-west faces south-east.
        val fromNorthWest = Color(shade(500f, -0.3, 0.3, palette))
        val fromSouthEast = Color(shade(500f, -0.3, 0.3, palette, lightEast = 0.5, lightNorth = -0.5))
        assertTrue(fromSouthEast.luminance() > Color(shade(500f, 0.0, 0.0, palette)).luminance())
        assertTrue(fromNorthWest.luminance() < fromSouthEast.luminance())
    }

    @Test
    fun theCoarserTheCellTheSteeperTheSlopesAreDrawn() {
        val cells = listOf(120, 240, 480, 960).map { exaggeration(it / 3_600.0 * 111_320.0) }
        assertEquals(cells.sorted(), cells)
        assertTrue(cells.first() > 1.0)
    }

    @Test
    fun aMixLandsBetweenItsEnds() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(black, mixArgb(black, white, 0.0))
        assertEquals(white, mixArgb(black, white, 1.0))
        assertEquals(0xFF808080.toInt(), mixArgb(black, white, 0.5))
    }

    @Test
    fun latitudeIsMercatorRunBackwards() {
        listOf(-80.0, -45.0, 0.0, 12.5, 48.15, 85.0).forEach {
            assertEquals(it, latitudeAt(mercatorY(it)), 1e-9)
        }
    }

    /**
     * The picture is shaded a cell row at a time, as its texel rows pass, instead of whole before it
     * is laid onto the map's rows. It has to come out as it did: every texel row the blend of the two
     * shaded cell rows round its latitude, and its heights in grey. Far north, where Mercator lays
     * several texel rows on one cell row and the rows held in hand are passed by and swapped.
     */
    @Test
    fun aPictureShadedRowByRowIsThePictureShadedWhole() {
        val rows = 12
        val columns = 5
        val firstRow = 5
        val cell = 2.0
        val heights = FloatArray(rows * columns) { (it / columns * 90 - it % columns * 35).toFloat() }
        val cells = ReliefCells(heights, rows, columns, firstRow, firstColumn = 40, cellDegrees = cell)
        val palette = DAY_MAP.relief
        val shader = RowShader(cells, palette, RELIEF_LIGHT_FROM)
        val whole = Array(rows) { row -> IntArray(columns).also { shader.paint(row, it) } }
        val low = heights.min()
        val span = heights.max() - low
        val frame = ReliefFrame(cells)

        var seen = 0
        frame.rows(palette, RELIEF_LIGHT_FROM) { y, tints, greys ->
            val latitude = latitudeAt(frame.top + (y + 0.5) * frame.texelY)
            val exact = ((90.0 - latitude) / cell - 0.5 - firstRow).coerceIn(0.0, rows - 1.0)
            val above = exact.toInt()
            val below = (above + 1).coerceAtMost(rows - 1)
            val share = exact - above
            assertArrayEquals("row $y", IntArray(columns) { mixArgb(whole[above][it], whole[below][it], share) }, tints)
            val grey = IntArray(columns) {
                val metres = heights[above * columns + it] * (1 - share) + heights[below * columns + it] * share
                0xFF000000.toInt() or ((metres - low) / span * 255).toInt().coerceIn(0, 255) * 0x010101
            }
            assertArrayEquals("grey $y", grey, greys)
            seen++
        }
        assertEquals(frame.height, seen)
        assertTrue("several texel rows to a cell row up here", frame.height > rows + 2)
    }

    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
