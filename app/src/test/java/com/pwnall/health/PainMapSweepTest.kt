package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a dab marks on every cell of a map, rather than on a few points chosen for a test: the chest's
 * safety line over the whole breastbone and wherever the first map showed it, the base of the neck
 * that never marks the chest, the ankle bones on both plates, and a tap kept as a stroke.
 */
class PainMapSweepTest {
    private val v1 = PainMapVersion.V1
    private val v2 = PainMapVersion.V2

    /** The brushes of the pain page: the whole figure, zoomed in two times, five times. */
    private val brushes = listOf(0.035f, 0.018f, 0.007f)

    private val left = BodySide.LEFT
    private val right = BodySide.RIGHT

    private fun area(region: PainRegion, side: BodySide = BodySide.CENTRE) = RegionSide(region, side)

    private fun dab(view: PainView, radius: Float, point: Offset, map: PainMapVersion) =
        paintedRegions(listOf(PainStroke(view, radius, listOf(point))), map)

    /** The centre of one cell of the hit grid, by its column and row. */
    private fun centre(column: Int, row: Int) = Offset((column + 0.5f) / GRID_COLUMNS, (row + 0.5f) / GRID_ROWS)

    /** Every cell of [view] on [map] with the area that owns it, cell by cell. */
    private fun cells(view: PainView, map: PainMapVersion): List<Triple<Int, Int, RegionSide>> =
        (0 until GRID_ROWS).flatMap { row ->
            (0 until GRID_COLUMNS).mapNotNull { column ->
                areaAt(view, centre(column, row), map)?.let { Triple(column, row, it) }
            }
        }

    private fun safetyLine(marked: Set<RegionSide>) = painNeedsSafetyLine(marked, null)

    /**
     * The first map's areas are each their own, so on the first map a dab's largest share is reckoned
     * among them; the second map's parts are reckoned as the area they were carved from, on the first
     * map's sides.
     */
    @Test
    fun aPartIsReckonedAsTheFirstMapsArea() {
        PainView.entries.flatMap { sidePatches(it, v1) }.map { it.area }.forEach { own ->
            assertEquals(own.code, listOf(own), own.firstMapAreas)
        }
        assertEquals(
            listOf(area(PainRegion.CHEST, left), area(PainRegion.CHEST, right)),
            area(PainRegion.STERNUM).firstMapAreas,
        )
        assertEquals(
            listOf(area(PainRegion.GLUTE, left), area(PainRegion.GLUTE, right)),
            area(PainRegion.TAILBONE).firstMapAreas,
        )
        assertEquals(listOf(area(PainRegion.LOWER_BACK)), area(PainRegion.FLANK, right).firstMapAreas)
        assertEquals(listOf(area(PainRegion.ABDOMEN)), area(PainRegion.UPPER_BELLY).firstMapAreas)
        assertEquals(listOf(area(PainRegion.KNEE, left)), area(PainRegion.BACK_OF_KNEE, left).firstMapAreas)
        assertEquals(listOf(area(PainRegion.SHOULDER, right)), area(PainRegion.TRAPEZIUS, right).firstMapAreas)
    }

    /**
     * Both ends of the breastbone with the whole-figure brush: a third each falls on it and on the two
     * sides of the chest, so reckoned area by area the neck above it or the upper belly under it would
     * take the largest share and the chest's safety line with it. Reckoned as the chest, they are the
     * breastbone, or the side of the chest the dab leans to.
     */
    @Test
    fun theEndsOfTheBreastboneAreTheChest() {
        val whole = 0.035f
        assertEquals(setOf(area(PainRegion.STERNUM)), dab(PainView.FRONT, whole, Offset(0.5f, 0.072f), v2))
        assertEquals(setOf(area(PainRegion.STERNUM)), dab(PainView.FRONT, whole, Offset(0.506f, 0.185f), v2))
        assertEquals(setOf(area(PainRegion.CHEST, right)), dab(PainView.FRONT, whole, Offset(0.47f, 0.185f), v2))
        // A dab on the base of the neck beside the ridge, which would tie the ridge with the chest under it.
        assertEquals(setOf(area(PainRegion.TRAPEZIUS, right)), dab(PainView.FRONT, whole, Offset(0.36f, 0.06f), v2))
    }

    /** A dab of any of the page's brushes centred anywhere on the breastbone shows the safety line. */
    @Test
    fun theWholeBreastboneShowsTheSafetyLine() {
        val sternum = cells(PainView.FRONT, v2).filter { it.third.region == PainRegion.STERNUM }
        assertEquals(76, sternum.size)
        sternum.forEach { (column, row) ->
            brushes.forEach { radius ->
                val marked = dab(PainView.FRONT, radius, centre(column, row), v2)
                assertTrue("c$column r$row at $radius: $marked", safetyLine(marked))
            }
        }
    }

    /**
     * Wherever the first map showed the safety line for a dab centred on what the second map calls the
     * chest, the second shows it too. Between the two shoulders, that is: beside each of them the second
     * map follows the groove between the shoulder and the chest, and what the first map called chest
     * above it is the shoulder's on the second.
     */
    @Test
    fun theChestShowsTheSafetyLineWhereverTheFirstMapDid() {
        val chest = cells(PainView.FRONT, v2).filter { (column, _, owner) ->
            owner.region.root == PainRegion.CHEST && centre(column, 0).x in 0.35f..0.65f
        }
        assertTrue(chest.size > 400)
        var firstMap = 0
        chest.forEach { (column, row) ->
            brushes.forEach { radius ->
                if (safetyLine(dab(PainView.FRONT, radius, centre(column, row), v1))) {
                    firstMap++
                    val marked = dab(PainView.FRONT, radius, centre(column, row), v2)
                    assertTrue("c$column r$row at $radius: $marked", safetyLine(marked))
                }
            }
        }
        assertTrue(firstMap > 1_000)
    }

    /**
     * A dab on the base of the neck in front, or on the ridge from it to the shoulder, centred two rows
     * of the grid or more above the chest — anywhere from one shoulder to the other — marks no part of
     * the chest with any of the page's brushes. Nearer the collarbones it reaches over them, and the
     * chest it reaches is marked as any area a dab spills onto is.
     */
    @Test
    fun aDabWellAboveTheCollarbonesNeverMarksTheChest() {
        val front = cells(PainView.FRONT, v2)
        val chestTop = front.filter { it.third.region.root == PainRegion.CHEST }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, rows) -> rows.min() }
        val neck = front.filter { (column, row, owner) ->
            owner.region in setOf(PainRegion.NECK, PainRegion.TRAPEZIUS) &&
                chestTop[column]?.let { row <= it - 2 } == true
        }
        assertTrue(neck.size > 150)
        neck.forEach { (column, row) ->
            brushes.forEach { radius ->
                val marked = dab(PainView.FRONT, radius, centre(column, row), v2)
                assertFalse("c$column r$row at $radius: $marked", marked.any { it.region.root == PainRegion.CHEST })
            }
        }
    }

    /**
     * From behind, the ankle bones are the ankle's on both plates and the tendon between them the
     * Achilles tendon's, for a tap and zoomed in. The man's ankle is drawn further out than the
     * woman's: his inner ankle bone is where her tendon is, and her outer one where his is.
     */
    @Test
    fun theAnkleBonesAreTheAnklesOnBothPlates() {
        val ankle = area(PainRegion.ANKLE, left)
        val tendon = area(PainRegion.ACHILLES, left)
        val bones = listOf(
            Offset(0.240f, 0.912f), // his outer ankle bone
            Offset(0.338f, 0.912f), // his inner one
            Offset(0.272f, 0.912f), // her outer one
            Offset(0.362f, 0.905f), // her inner one
        )
        val tendons = listOf(Offset(0.29f, 0.90f), Offset(0.32f, 0.90f), Offset(0.29f, 0.88f), Offset(0.318f, 0.88f))
        listOf(bones to ankle, tendons to tendon).forEach { (points, expected) ->
            points.forEach { point ->
                assertEquals("$point", expected, areaAt(PainView.BACK, point, v2))
                listOf(0.018f, 0.007f).forEach { radius ->
                    assertEquals("$point at $radius", setOf(expected), dab(PainView.BACK, radius, point, v2))
                }
            }
        }
        // With the figure whole a dab is wider than the ankle: on the tendon it marks the ankle as well.
        assertEquals(setOf(tendon, ankle), dab(PainView.BACK, 0.035f, Offset(0.297f, 0.90f), v2))
    }

    /**
     * A tap kept as a dab of 0.003 plate heights at the very point tapped marks the area the tap chose,
     * or nothing: never another, which would hide the dab as one painted over an area struck off.
     */
    @Test
    fun aTapKeptAsATinyDabMarksOnlyWhatTheTapChose() {
        // Points at no particular place in their cells: off the centres, the edges and the corners alike.
        val xs = generateSequence(0.0013f) { it + 0.0037f }.takeWhile { it < 1f }.toList()
        val ys = generateSequence(0.0021f) { it + 0.0047f }.takeWhile { it < 1f }.toList()
        val points = xs.flatMap { x -> ys.map { y -> Offset(x, y) } }
        PainMapVersion.entries.forEach { map ->
            PainView.entries.forEach { view ->
                points.forEach { point ->
                    val tapped = setOfNotNull(areaAt(view, point, map))
                    val marked = dab(view, 0.003f, point, map)
                    assertTrue("${map.name} $view $point: $marked, tapped $tapped", tapped.containsAll(marked))
                }
            }
        }
    }

    /**
     * A drag begun anywhere in any cell of either plate, with the whole-figure brush and the brush
     * zoomed in five times, reads back from the payload as the very stroke the canvas kept ([quantised]):
     * the areas the page read off it are the areas the summary and the doctor's sheet read off it later.
     * Points at no thousandth of their own, so the rounding has something to move.
     */
    @Test
    fun everyStrokeTheCanvasKeepsReadsBackFromThePayloadUnmoved() {
        val kept = PainView.entries.flatMap { view ->
            listOf(0.0318f, 0.0064f).flatMap { radius ->
                (0 until GRID_ROWS).flatMap { row ->
                    (0 until GRID_COLUMNS).map { column ->
                        val start = centre(column, row) + Offset(0.00037f, 0.00041f)
                        val drag = listOf(start, start + Offset(0.0123f, 0.0077f))
                        quantised(PainStroke(view, radius, simplifyStroke(drag)))
                    }
                }
            }
        }
        val knee = listOf(area(PainRegion.KNEE, left))
        // The same strokes, point for point: the hit test of every later reader cannot come out otherwise.
        kept.chunked(PAIN_MAX_STROKES).forEach { strokes ->
            val payload = encodePainPayload(PainReport(regions = knee, strokes = strokes))
            assertEquals(strokes, decodePainPayload(payload).strokes)
        }
    }
}
