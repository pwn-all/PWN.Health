package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * The geometry between the detector's probability map and the crop the recognizer reads, at the
 * inputs a photographed packet actually produces: a blob one pixel wide, a blob filling the frame,
 * two blobs grazing at a corner, a rectangle hanging over the edge of the picture.
 *
 * Every number asserted here is derived from the detector's published DB postprocessing — mask
 * above 0.2, keep a box at mean 0.45, unclip outward by area x ratio / perimeter — or from the
 * KDoc's own promises, never from what the code happens to compute.
 */
class OcrGeometryEdgeCasesTest {

    /**
     * The unclip pushes every region outward, so a line printed near the edge of the frame comes
     * back with corners at 15.4 on one side and -2.5 on the other. The upright box is what reading
     * order sorts on and what panelBounds unions; a box that stops short of its own region shaves
     * the first or last letter off the crop the recognizer is handed.
     */
    @Test
    fun `the upright bounds contain every corner of the oriented rectangle`() {
        // Corners at 4.6 and 15.4: containment needs the near edge floored and the far edge ceiled.
        val inside = Region(cx = 10.0, cy = 10.0, ux = 5.4, uy = 0.0, vx = 0.0, vy = 5.4).bounds
        assertTrue("was $inside", inside.x0 <= 4 && inside.y0 <= 4)
        assertTrue("was $inside", inside.x1 >= 16 && inside.y1 >= 16)

        // Corners at -2.5 and 4.5, which is ordinary once the unclip has pushed a region outward.
        val overhanging = Region(cx = 1.0, cy = 1.0, ux = 3.5, uy = 0.0, vx = 0.0, vy = 3.5).bounds
        assertTrue("was $overhanging", overhanging.x0 <= -3 && overhanging.y0 <= -3)
        assertTrue("was $overhanging", overhanging.x1 >= 5 && overhanging.y1 >= 5)
    }

    /**
     * DB unclips a detected box outward by area x ratio / perimeter. A factor of two in either
     * direction still crops most labels and still reads them; what it loses is the small print at
     * the bottom of the panel, which is where the macros are.
     */
    @Test
    fun `the unclip distance is area times ratio over perimeter`() {
        val region = requireNotNull(rectangleComponent(10, 2, pixels = 33, mean = 0.9).region(1.0, 1.0))
        val offset = 10.0 * 2.0 * 1.4 / (2.0 * (10 + 2))

        assertEquals(7.0 / 6.0, offset, 1e-12)
        assertEquals(10.0 + 2 * offset, region.length, 1e-9)
        assertEquals(2.0 + 2 * offset, region.thickness, 1e-9)
        assertEquals(5.0, region.cx, 1e-9)
        assertEquals(1.0, region.cy, 1e-9)
        assertEquals(0.0, region.uy, 1e-9)
    }

    /**
     * The detection map is stretched to a multiple of the model's stride per axis, so the two scales
     * differ. Applying one of them to both axes skews every crop, and with sx equal to sy in every
     * other fixture the difference is invisible.
     */
    @Test
    fun `each detection axis is scaled by its own factor`() {
        val region = requireNotNull(rectangleComponent(10, 2, pixels = 33, mean = 0.9).region(2.0, 0.5))
        val offset = 10.0 * 2.0 * 1.4 / (2.0 * (10 + 2))

        assertEquals(10.0, region.cx, 1e-9)
        assertEquals(0.5, region.cy, 1e-9)
        assertEquals((10.0 + 2 * offset) * 2.0, region.length, 1e-9)
        assertEquals((2.0 + 2 * offset) * 0.5, region.thickness, 1e-9)
    }

    /** The minimum-pixel gate is a floor: it decides whether a whole line reaches the recognizer. */
    @Test
    fun `a blob of eight pixels is noise and nine is a component`() {
        assertNull(rectangleComponent(10, 2, pixels = 8, mean = 0.9).region(1.0, 1.0))
        assertNotNull(rectangleComponent(10, 2, pixels = 9, mean = 0.9).region(1.0, 1.0))
    }

    /** The detector's published rule is to keep a box at mean 0.45; below it the blob is paper, not ink. */
    @Test
    fun `a mean score of exactly the box threshold is kept`() {
        val kept = rectangleComponent(10, 2, pixels = 10, mean = 0.45)
        val refused = rectangleComponent(10, 2, pixels = 10, mean = 0.44)

        assertEquals(0.45, kept.score, 1e-12)
        assertNotNull(kept.region(1.0, 1.0))
        assertNull(refused.region(1.0, 1.0))
        // A component the fill never credited scores zero rather than dividing by its pixel count.
        assertEquals(0.0, Component(emptyList(), 0, 0.0).score, 1e-12)
    }

    /**
     * A hand-held photograph of a carton tilts a few degrees, and reading a tilted row through an
     * upright box sweeps in the row below it: on a real photograph, the same models read
     * "Vyivove udajnavyrobk" through an upright box and the whole line through its own axis.
     */
    @Test
    fun `a line tilted within the hand-held limit keeps its own axis`() {
        val region = requireNotNull(tiltedComponent(15.0).region(1.0, 1.0))

        val slope = region.uy / region.ux
        assertEquals(tan(15.0 * PI / 180), slope, 0.02)
        assertTrue("was ${region.length} by ${region.thickness}", region.length > region.thickness * 4)
    }

    /**
     * A rectangle claiming thirty degrees is not a tilted line of text but a blob whose long side
     * points elsewhere — two rows fused into a square, a logo, a barcode — and sampling along that
     * axis reads the print sideways. Without this rule the Japanese carton would lose its energy row.
     */
    @Test
    fun `a blob tilted past the hand-held limit is read upright`() {
        val region = requireNotNull(tiltedComponent(30.0).region(1.0, 1.0))
        // The upright extent of that turned rectangle is 92 by 58, unclipped by its own perimeter.
        val offset = 92.0 * 58.0 * 1.4 / (2.0 * (92 + 58))

        assertEquals(0.0, region.uy, 1e-9)
        assertEquals(0.0, region.vx, 1e-9)
        assertEquals(200.0, region.cx, 1e-9)
        assertEquals(200.0, region.cy, 1e-9)
        assertEquals(92.0 + 2 * offset, region.length, 1e-6)
        assertEquals(58.0 + 2 * offset, region.thickness, 1e-6)
    }

    /**
     * A single-pixel stroke and a single-pixel component are ordinary detector output. Both unclip
     * by zero — the offset divides by width plus height — so a NaN half-axis would reach the crop's
     * coordinates and sample the whole raster into noise.
     */
    @Test
    fun `a degenerate outline yields no region rather than a NaN one`() {
        val dot = Component(List(12) { Point(4, 4) }, 12, 10.8)
        val upright = Component((0..8).map { Point(0, it) }, 9, 8.1)
        val flat = Component((0..19).map { Point(it, 0) }, 20, 18.0)

        assertNull(dot.region(1.0, 1.0))
        assertNull(upright.region(1.0, 1.0))
        assertNull(flat.region(1.0, 1.0))
    }

    /**
     * A single-pixel-wide stroke of a letter arrives as a run of collinear points. A duplicated
     * endpoint out of the chain's bookkeeping then hands the calipers a zero-length edge.
     */
    @Test
    fun `collinear points collapse to the two extremes whatever order they arrive in`() {
        val diagonal = listOf(Point(0, 0), Point(1, 1), Point(2, 2), Point(3, 3), Point(4, 4))
        val ends = listOf(Point(0, 0), Point(4, 4))
        val scrambled = listOf(diagonal[2], diagonal[0], diagonal[4], diagonal[1], diagonal[3])

        assertEquals(ends, hullOf(diagonal))
        assertEquals(ends, hullOf(diagonal.reversed()))
        assertEquals(ends, hullOf(scrambled))
    }

    /** A repeated first vertex makes the calipers evaluate a degenerate edge and can win the tie. */
    @Test
    fun `interior points are dropped and every hull vertex appears once`() {
        val corners = listOf(Point(0, 0), Point(10, 0), Point(10, 10), Point(0, 10))
        val scrambled = corners.reversed() + listOf(Point(5, 5), Point(2, 7), Point(0, 0))

        val hull = hullOf(scrambled)

        assertEquals(4, hull.size)
        assertEquals(hull.size, hull.distinct().size)
        assertEquals(corners.toSet(), hull.toSet())
    }

    /** The fill can hand back a component whose points are one row's leftmost and rightmost pixel. */
    @Test
    fun `empty single and repeated points make hulls no larger than their input`() {
        assertEquals(emptyList<Point>(), hullOf(emptyList()))
        assertEquals(listOf(Point(3, 3)), hullOf(listOf(Point(3, 3))))
        assertEquals(listOf(Point(3, 3)), hullOf(List(12) { Point(3, 3) }))
    }

    /**
     * The map is a flat array, so the pixel after the last of one row is the first of the next. A
     * fill that leaks across the right margin fuses the end of one line of text with the start of
     * the line below it, and the fused blob's long axis then points nowhere.
     */
    @Test
    fun `the fill does not wrap around the end of a row`() {
        val map = floatArrayOf(0f, 0f, 0.9f, 0.9f, 0f, 0f)

        val found = componentsOf(map, width = 3, height = 2)

        assertEquals(2, found.size)
        assertTrue("was ${found.map(Component::pixels)}", found.all { it.pixels == 1 })
    }

    /** Four neighbours, not eight: two table rows that graze at a corner stay two rows. */
    @Test
    fun `two blobs touching at a corner stay two blobs`() {
        val map = FloatArray(9)
        map[0] = 0.9f
        map[4] = 0.9f

        assertEquals(2, componentsOf(map, width = 3, height = 3).size)
    }

    /**
     * DB binarizes strictly above the mask threshold. The seed loop and the fill's own offer test it
     * separately, and a pixel sitting exactly on it is the only input that shows them disagreeing.
     */
    @Test
    fun `a probability of exactly the mask threshold is outside the mask`() {
        val onTheLine = FloatArray(16) { MASK_THRESHOLD }
        assertEquals(emptyList<Component>(), componentsOf(onTheLine, 4, 4))

        val justOver = FloatArray(16) { MASK_THRESHOLD }
        justOver[5] = MASK_THRESHOLD + 0.001f

        val found = componentsOf(justOver, 4, 4)

        assertEquals(1, found.size)
        assertEquals(1, found.first().pixels)
    }

    /**
     * A photograph of a dark packet lights the whole detection map. A recursive fill dies on it, and
     * the outline has to stay each row's two extreme pixels — the KDoc's few thousand points for a
     * page of text instead of a few hundred thousand.
     */
    @Test
    fun `a map that is one blob is filled without recursion and keeps two points per row`() {
        val side = 200

        val found = componentsOf(FloatArray(side * side) { 0.9f }, side, side)

        assertEquals(1, found.size)
        assertEquals(side * side, found.first().pixels)
        assertEquals(side * 2, found.first().points.size)
    }

    /**
     * The leftmost and rightmost arrays are one pair reused by every component and cleared only over
     * the rows the finished component touched. A leak there fuses the two halves of a two-column
     * panel into one region whose crop reads as neither column.
     */
    @Test
    fun `two blobs sharing the same rows keep their own outlines`() {
        val map = FloatArray(40)
        for (y in 0 until 4) {
            for (x in listOf(0, 1, 7, 8)) map[y * 10 + x] = 0.9f
        }

        val found = componentsOf(map, width = 10, height = 4)

        assertEquals(2, found.size)
        val left = found.first { it.points.first().x < 5 }
        val right = found.first { it.points.first().x >= 5 }
        assertTrue("was ${left.points}", left.points.all { it.x in 0..1 })
        assertTrue("was ${right.points}", right.points.all { it.x in 7..8 })
    }

    /** The outline of an upright [width] by [height] block: each row's leftmost and rightmost. */
    private fun rectangleComponent(width: Int, height: Int, pixels: Int, mean: Double): Component {
        val points = (0..height).flatMap { y -> listOf(Point(0, y), Point(width, y)) }
        return Component(points, pixels, mean * pixels)
    }

    /** The four corners of a 100 by 10 line of text centred at (200, 200), turned by [degrees]. */
    private fun tiltedComponent(degrees: Double): Component {
        val radians = degrees * PI / 180
        val corners = listOf(50.0 to 5.0, 50.0 to -5.0, -50.0 to -5.0, -50.0 to 5.0)
        val points = corners.map { (along, across) ->
            Point(
                (200 + along * cos(radians) - across * sin(radians)).roundToInt(),
                (200 + along * sin(radians) + across * cos(radians)).roundToInt(),
            )
        }
        return Component(points, 400, 360.0)
    }
}
