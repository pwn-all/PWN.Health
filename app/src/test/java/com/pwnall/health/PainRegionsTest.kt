package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The areas a painting marks. The part of the pain map that decides what is recorded, and the part
 * where one mistake — a side swapped between the two plates — would silently invert every side of
 * every entry, so it is pinned here rather than trusted to a look at the screen.
 *
 * Two maps: the first, frozen for the entries painted on it, and the second, which carves it finer.
 */
class PainRegionsTest {
    /** A brush about the size a 28dp finger paints with the whole figure on a phone. */
    private val wholeFigureBrush = 0.035f

    /** And one painted zoomed in about two times. */
    private val zoomedBrush = 0.018f

    /** And at the deepest zoom, five times. */
    private val finestBrush = 0.007f

    private val v1 = PainMapVersion.V1
    private val v2 = PainMapVersion.V2

    private fun stroke(view: PainView, radius: Float, vararg points: Pair<Float, Float>) =
        PainStroke(view, radius, points.map { Offset(it.first, it.second) })

    private fun dab(view: PainView, radius: Float, x: Float, y: Float) = listOf(stroke(view, radius, x to y))

    /** What a dab of [radius] at ([x], [y]) marks on [map], from the front and from behind. */
    private fun front(radius: Float, x: Float, y: Float, map: PainMapVersion = v2) =
        paintedRegions(dab(PainView.FRONT, radius, x, y), map)

    private fun back(radius: Float, x: Float, y: Float, map: PainMapVersion = v2) =
        paintedRegions(dab(PainView.BACK, radius, x, y), map)

    private fun only(region: PainRegion, side: BodySide = BodySide.CENTRE) = setOf(RegionSide(region, side))

    private fun area(region: PainRegion, side: BodySide = BodySide.CENTRE) = RegionSide(region, side)

    private val left = BodySide.LEFT
    private val right = BodySide.RIGHT

    /** The areas each map draws a patch for, on either view. */
    private fun drawn(map: PainMapVersion) = PainView.entries.flatMap { sidePatches(it, map) }.map { it.area }.toSet()

    /**
     * Every area below the head that a map offers is on its plates: the first map's eighteen, and on
     * the second every one but the two the plates do not show — the fingers, cut off at the palm, and
     * the sole — which are chosen from the list, as the head's are.
     */
    @Test
    fun everyAreaOfferedBelowTheHeadIsOnThePlatesOrListOnly() {
        val listOnly = setOf(PainRegion.FINGERS, PainRegion.SOLE)
        PainMapVersion.entries.forEach { map ->
            val offered = painAreasOffered(map).filter { it.region.group != PainGroup.HEAD }
            assertEquals(map.name, offered.filter { it.region !in listOnly }.toSet(), drawn(map))
        }
        val undrawn = painAreasOffered(v2).map { it.region }.toSet() - drawn(v2).map { it.region }.toSet()
        assertEquals(listOnly, undrawn - PainGroup.HEAD.areas)
    }

    /**
     * The first map offers exactly its twenty-three areas, the second every area there is but
     * the two it carved up whole, whose parts it offers in their place.
     */
    @Test
    fun eachMapOffersItsOwnAreas() {
        val first = painAreasOffered(v1).map { it.region }.distinct()
        assertEquals(PainRegion.entries.filter { it.parent == null }, first)
        assertEquals(23, first.size)
        val second = painAreasOffered(v2).map { it.region }.distinct()
        assertEquals(PainRegion.entries - PainRegion.ABDOMEN - PainRegion.ANKLE_FOOT, second)
        assertTrue(drawn(v2).none { it.region == PainRegion.ABDOMEN || it.region == PainRegion.ANKLE_FOOT })
        // Every area on every side of each, in the order the list shows them.
        PainMapVersion.entries.forEach { map ->
            assertEquals(RegionSide.ALL.filter { it in painAreasOffered(map) }, painAreasOffered(map))
        }
    }

    /**
     * The list for an entry offers what its map offers, and keeps an area already on the entry that its
     * map does not — the abdomen on an entry of the second map, which offers its six parts — at its
     * place head to foot, so that it can be taken off. Nothing is listed twice.
     */
    @Test
    fun theListKeepsAnAreaOnTheEntryThatItsMapDoesNotOffer() {
        val abdomen = area(PainRegion.ABDOMEN)
        val knee = area(PainRegion.KNEE, left)
        val list = painAreaChoices(v2, listOf(knee, abdomen))
        assertEquals(list.indexOf(area(PainRegion.UPPER_BELLY)) - 1, list.indexOf(abdomen))
        assertEquals(list.distinct(), list)
        assertFalse(abdomen in painAreaChoices(v2, listOf(knee)))
        assertEquals(painAreasOffered(v2), painAreaChoices(v2, listOf(knee)))
        assertEquals(painAreasOffered(v1), painAreaChoices(v1, listOf(knee, abdomen)))
    }

    /**
     * No head is drawn on either view, and its areas are still recorded: every one of them, on every
     * side it has, is in the list both maps offer.
     */
    @Test
    fun theHeadHasNoPatchOnTheBodyViewsAndEveryHeadAreaIsStillListed() {
        val head = PainRegion.entries.filter { it.group == PainGroup.HEAD }
        assertEquals(
            setOf(PainRegion.HEAD_FRONT, PainRegion.TEMPLE, PainRegion.HEAD_TOP, PainRegion.HEAD_BACK, PainRegion.JAW),
            head.toSet(),
        )
        val listed = listOf(
            RegionSide(PainRegion.HEAD_FRONT, BodySide.CENTRE),
            RegionSide(PainRegion.TEMPLE, BodySide.LEFT),
            RegionSide(PainRegion.TEMPLE, BodySide.RIGHT),
            RegionSide(PainRegion.HEAD_TOP, BodySide.CENTRE),
            RegionSide(PainRegion.HEAD_BACK, BodySide.CENTRE),
            RegionSide(PainRegion.JAW, BodySide.LEFT),
            RegionSide(PainRegion.JAW, BodySide.RIGHT),
        )
        PainMapVersion.entries.forEach { map ->
            PainView.entries.forEach { view ->
                assertTrue(view.name, sidePatches(view, map).none { it.area.region.group == PainGroup.HEAD })
                assertTrue(view.name, areaCells(view, map).keys.none { it.region.group == PainGroup.HEAD })
            }
            assertEquals(listed, painAreasOffered(map).filter { it.region.group == PainGroup.HEAD })
        }
        assertEquals(listed, RegionSide.ALL.filter { it.region.group == PainGroup.HEAD })
    }

    /** The body fills the canvas: the figure starts at the top of the plate, with no band above it. */
    @Test
    fun theFigureIsThePlateTopToFoot() {
        assertEquals(0f, FIGURE_TOP)
        assertEquals(1f, FIGURE_HEIGHT)
    }

    /**
     * The front plate faces the viewer, so the half of the picture on the viewer's left is the
     * person's right; from behind, it is the person's left. This is the whole of the mapping.
     */
    @Test
    fun theSideOfAPointIsThePersonsOwnOnBothViews() {
        assertEquals(BodySide.RIGHT, sideOf(PainView.FRONT, 0.3f))
        assertEquals(BodySide.LEFT, sideOf(PainView.FRONT, 0.7f))
        assertEquals(BodySide.LEFT, sideOf(PainView.BACK, 0.3f))
        assertEquals(BodySide.RIGHT, sideOf(PainView.BACK, 0.7f))
    }

    /** The same mapping, read off the patches of both maps: the knee of the first, its two parts on the second. */
    @Test
    fun theKneeOnTheViewersLeftIsTheRightKneeFromTheFrontAndTheLeftFromBehind() {
        fun sides(view: PainView, region: PainRegion, map: PainMapVersion) =
            sidePatches(view, map).filter { it.area.region == region }.let { patches ->
                patches.single { it.points.all { p -> p.x < 0.5f } }.area.side to
                    patches.single { it.points.all { p -> p.x > 0.5f } }.area.side
            }
        assertEquals(right to left, sides(PainView.FRONT, PainRegion.KNEE, v1))
        assertEquals(left to right, sides(PainView.BACK, PainRegion.KNEE, v1))
        assertEquals(right to left, sides(PainView.FRONT, PainRegion.KNEE, v2))
        assertEquals(left to right, sides(PainView.BACK, PainRegion.BACK_OF_KNEE, v2))
        assertTrue(sidePatches(PainView.BACK, v2).none { it.area.region == PainRegion.KNEE })
    }

    @Test
    fun everyPatchStaysInsideFigureSpace() {
        PainMapVersion.entries.forEach { map ->
            PainView.entries.flatMap { sidePatches(it, map) }.forEach { patch ->
                patch.points.forEach {
                    assertTrue("${map.name} ${patch.area.code} at $it", it.x in 0f..1f)
                    assertTrue("${map.name} ${patch.area.code} at $it", it.y in FIGURE_TOP..FIGURE_BOTTOM)
                }
            }
        }
    }

    /** A paired patch that crossed the middle would mark both sides with one touch. */
    @Test
    fun aPairedPatchNeverCrossesTheMiddleOfTheFigure() {
        PainMapVersion.entries.forEach { map ->
            PainView.entries.flatMap { sidePatches(it, map) }.filter { it.area.region.paired }.forEach { patch ->
                val leftHalf = patch.points.all { it.x <= 0.5f }
                val rightHalf = patch.points.all { it.x >= 0.5f }
                assertTrue("${map.name} ${patch.area.code}", leftHalf || rightHalf)
            }
        }
    }

    /**
     * An unpaired area is the centre, and its patch is symmetric about the axis: the hit grid gives
     * the viewer's right half the left half's cells mirrored, so a lopsided patch would be drawn one
     * way and hit another.
     */
    @Test
    fun anUnpairedAreaIsAlwaysTheCentreAndSymmetric() {
        PainMapVersion.entries.forEach { map ->
            PainView.entries.flatMap { sidePatches(it, map) }.filter { !it.area.region.paired }.forEach { patch ->
                assertEquals("${map.name} ${patch.area.code}", BodySide.CENTRE, patch.area.side)
                val points = patch.points.map { Offset(Math.round(it.x * 1000) / 1000f, it.y) }.toSet()
                val mirrored = points.map { Offset(Math.round((1f - it.x) * 1000) / 1000f, it.y) }.toSet()
                assertEquals("${map.name} ${patch.area.code}", points, mirrored)
            }
        }
    }

    @Test
    fun aStrokeAcrossTheLeftKneeFromTheFrontMarksTheLeftKneeAndNothingElse() {
        // The person's left knee is on the viewer's right from the front: the knee on both maps.
        val knee = listOf(stroke(PainView.FRONT, wholeFigureBrush, 0.64f to 0.615f, 0.70f to 0.62f))
        PainMapVersion.entries.forEach { map ->
            assertEquals(map.name, only(PainRegion.KNEE, left), paintedRegions(knee, map))
        }
    }

    /** From behind the left knee is on the viewer's left: the knee on the first map, the back of it on the second. */
    @Test
    fun theSameStrokeFromBehindIsOnTheLeftKneeAtTheViewersLeft() {
        val back = listOf(stroke(PainView.BACK, zoomedBrush, 0.30f to 0.62f, 0.37f to 0.62f))
        assertEquals(only(PainRegion.KNEE, left), paintedRegions(back, v1))
        assertEquals(only(PainRegion.BACK_OF_KNEE, left), paintedRegions(back, v2))
    }

    @Test
    fun paintOffTheBodyMarksNothing() {
        val beside = stroke(PainView.FRONT, wholeFigureBrush, 0.05f to 0.8f, 0.08f to 0.9f)
        val between = stroke(PainView.FRONT, 0.01f, 0.5f to 0.8f, 0.5f to 0.85f)
        PainMapVersion.entries.forEach { map ->
            assertEquals(map.name, emptySet<RegionSide>(), paintedRegions(listOf(beside, between), map))
        }
    }

    /** A dab too small for any threshold still says where it was put. */
    @Test
    fun aSmallDabMarksTheAreaItLandedOn() {
        assertEquals(only(PainRegion.ABDOMEN), front(0.006f, 0.5f, 0.30f, v1))
        assertEquals(only(PainRegion.UPPER_BELLY), front(0.006f, 0.5f, 0.24f))
    }

    /**
     * The hit grid is mirrored cell for cell, so a pair's two sides have the same cells on both
     * views. With an odd column count the middle column would go to one side every time.
     */
    @Test
    fun everyPairHasAsManyCellsOnOneSideAsOnTheOther() {
        PainMapVersion.entries.forEach { map ->
            PainView.entries.forEach { view ->
                val cells = areaCells(view, map)
                cells.keys.filter { it.side == BodySide.LEFT }.forEach { leftSide ->
                    val rightSide = RegionSide(leftSide.region, BodySide.RIGHT)
                    assertEquals("${leftSide.code} on $view, ${map.name}", cells[leftSide], cells[rightSide])
                }
            }
        }
    }

    /**
     * Every area-side a map draws owns cells of its grid, or a painting could never mark it: a patch
     * on the axis narrower than a column either side of it would own none.
     */
    @Test
    fun everyDrawnAreaOwnsCells() {
        PainMapVersion.entries.forEach { map ->
            val owning = PainView.entries.flatMap { view -> areaCells(view, map).filterValues { it > 0 }.keys }.toSet()
            assertEquals(map.name, drawn(map), owning)
        }
    }

    /** A dab on the figure's axis is split evenly between a pair, and marks both of it. */
    @Test
    fun aDabOnTheMiddleMarksBothSidesAlike() {
        assertEquals(
            setOf(area(PainRegion.CHEST, left), area(PainRegion.CHEST, right)),
            front(0.006f, 0.5f, 0.12f, v1),
        )
        assertEquals(
            setOf(area(PainRegion.GLUTE, left), area(PainRegion.GLUTE, right)),
            back(0.006f, 0.5f, 0.40f, v1),
        )
        // On the second map the middle of the back of the pelvis is the base of the spine, in halves.
        assertEquals(
            setOf(area(PainRegion.SACRUM, left), area(PainRegion.SACRUM, right)),
            back(zoomedBrush, 0.5f, 0.36f),
        )
    }

    /**
     * Above the plate, where no head is drawn, there is nothing to mark: dabs on the forehead, a
     * temple and the back of the head mark no area at all.
     */
    @Test
    fun paintWhereTheDrawnHeadStoodMarksNothing() {
        val forehead = stroke(PainView.FRONT, 0.008f, 0.5f to -0.08f)
        val temple = stroke(PainView.FRONT, wholeFigureBrush, 0.41f to -0.08f)
        val back = stroke(PainView.BACK, 0.008f, 0.5f to -0.07f)
        PainMapVersion.entries.forEach { map ->
            assertEquals(map.name, emptySet<RegionSide>(), paintedRegions(listOf(forehead, temple, back), map))
        }
    }

    /**
     * A knee painted front and back: one knee on the first map, which drew the back of the knee as
     * the knee; on the second the knee and the back of it — two areas of one family, which the
     * earlier-pain lookup takes for the same place ([sameFamilyAs]).
     */
    @Test
    fun aKneePaintedFrontAndBackIsOneKneeOnTheFirstMapAndOneFamilyOnTheSecond() {
        val front = stroke(PainView.FRONT, wholeFigureBrush, 0.64f to 0.615f, 0.70f to 0.62f)
        // From behind, the left knee is on the viewer's left.
        val back = stroke(PainView.BACK, zoomedBrush, 0.30f to 0.62f, 0.37f to 0.62f)
        assertEquals(only(PainRegion.KNEE, left), paintedRegions(listOf(front, back), v1))
        val second = paintedRegions(listOf(front, back), v2)
        assertEquals(setOf(area(PainRegion.KNEE, left), area(PainRegion.BACK_OF_KNEE, left)), second)
        assertEquals(setOf(PainRegion.KNEE), second.map { it.region.root }.toSet())
        assertTrue(second.first().sameFamilyAs(second.last()))
    }

    @Test
    fun aLongStrokeDownTheLegMarksEveryAreaItCovers() {
        val leg = listOf(stroke(PainView.FRONT, wholeFigureBrush, 0.33f to 0.50f, 0.33f to 0.62f, 0.31f to 0.75f))
        PainMapVersion.entries.forEach { map ->
            assertEquals(
                map.name,
                setOf(area(PainRegion.THIGH_FRONT, right), area(PainRegion.KNEE, right), area(PainRegion.SHIN, right)),
                paintedRegions(leg, map),
            )
        }
    }

    @Test
    fun anAreaAndSideAreWrittenAndReadBack() {
        RegionSide.ALL.forEach { assertEquals(it, RegionSide.parse(it.code)) }
        assertEquals("KNEE:L", RegionSide(PainRegion.KNEE, BodySide.LEFT).code)
        assertEquals("LOWER_BACK:C", RegionSide(PainRegion.LOWER_BACK, BodySide.CENTRE).code)
        assertEquals("BACK_OF_KNEE:R", RegionSide(PainRegion.BACK_OF_KNEE, BodySide.RIGHT).code)
    }

    /** A spine has no left, and a knee is never in the middle: neither is guessed at. */
    @Test
    fun anImpossibleSideIsRefused() {
        assertNull(RegionSide.parse("LOWER_BACK:L"))
        assertNull(RegionSide.parse("KNEE:C"))
        assertNull(RegionSide.parse("KNEE"))
        assertNull(RegionSide.parse("ELBOW_PIT:L"))
        assertNull(RegionSide.parse("KNEE:X"))
        assertNull(RegionSide.parse("STERNUM:L"))
        assertNull(RegionSide.parse("TAILBONE:R"))
        assertNull(RegionSide.parse("FLANK:C"))
    }

    @Test
    fun theGroupsPoolTheHeadAndTheLegs() {
        assertEquals(PainGroup.HEAD, PainRegion.TEMPLE.group)
        assertEquals(PainGroup.HEAD, PainRegion.JAW.group)
        assertEquals(PainGroup.NECK_SHOULDERS, PainRegion.UPPER_BACK.group)
        assertEquals(PainGroup.HIPS_LEGS, PainRegion.ANKLE_FOOT.group)
        assertTrue(PainRegion.entries.filter { it.joint }.all { it.paired })
    }

    /**
     * An area carved out of another pools with it — but for the base of the spine and the tailbone,
     * which the first map read as the buttock and which are the lower back's.
     */
    @Test
    fun aCarvedAreaPoolsWithItsParentButTheBaseOfTheSpine() {
        val carved = PainRegion.entries.filter { it.parent != null }
        assertTrue(carved.isNotEmpty())
        carved.forEach { child ->
            val expected = if (child == PainRegion.SACRUM || child == PainRegion.TAILBONE) {
                PainGroup.LOWER_BACK
            } else {
                child.parent!!.group
            }
            assertEquals(child.name, expected, child.group)
            // Carved once: the parent is one of the first map's own areas.
            assertNull(child.name, child.parent!!.parent)
        }
        // A bone or a tendon is never matched to a workout as soreness.
        listOf(PainRegion.STERNUM, PainRegion.SACRUM, PainRegion.TAILBONE, PainRegion.ACHILLES, PainRegion.HEEL)
            .forEach { assertNull(it.name, it.muscle) }
    }

    /**
     * The first map is frozen: a painting it recorded reads the same areas as when it was painted,
     * every one of them carved up on the second map. These are the strokes the carving would have
     * re-read as something else had the first map been redrawn in place.
     */
    @Test
    fun theFirstMapStillReadsAnOldPaintingAsItWasRecorded() {
        // The back of the knee, a dab on the right of the lower back, the middle of the chest, the
        // base of the spine, the Achilles tendon, the right of the lower belly.
        assertEquals(
            only(PainRegion.KNEE, left),
            paintedRegions(listOf(stroke(PainView.BACK, zoomedBrush, 0.30f to 0.62f, 0.37f to 0.62f)), v1),
        )
        assertEquals(only(PainRegion.LOWER_BACK), back(0.012f, 0.62f, 0.29f, v1))
        assertEquals(
            only(PainRegion.CHEST, left),
            front(wholeFigureBrush, 0.506f, 0.12f, v1),
        )
        assertEquals(only(PainRegion.GLUTE, left), back(zoomedBrush, 0.47f, 0.36f, v1))
        assertEquals(
            only(PainRegion.ANKLE_FOOT, left),
            back(zoomedBrush, 0.297f, 0.90f, v1),
        )
        assertEquals(only(PainRegion.ABDOMEN), front(wholeFigureBrush, 0.36f, 0.32f, v1))
    }

    /**
     * The places the first map left without an area, or gave two: on the second map each says one
     * thing. Under the collarbone beside the neck and at the pubis a cell of the first map's grid is
     * no area's, and a tap there finds nothing — a dab marks what it spills onto around it; in the
     * armpit from behind a dab marks the arm and the back at once.
     */
    @Test
    fun theSecondMapHasNoHoleWhereTheFirstHadOne() {
        val underCollarbones = listOf(0.372f to PainRegion.TRAPEZIUS, 0.628f to PainRegion.TRAPEZIUS)
        underCollarbones.forEach { (x, region) ->
            val point = Offset(x, 0.0575f)
            assertNull(areaAt(PainView.FRONT, point, v1))
            assertEquals(region, areaAt(PainView.FRONT, point, v2)?.region)
        }
        assertEquals(only(PainRegion.TRAPEZIUS, right), front(finestBrush, 0.372f, 0.0575f))
        assertEquals(only(PainRegion.TRAPEZIUS, left), front(finestBrush, 0.628f, 0.0575f))

        val pubis = Offset(0.5f, 0.4315f)
        assertNull(areaAt(PainView.FRONT, pubis, v1))
        assertEquals(area(PainRegion.LOWER_BELLY), areaAt(PainView.FRONT, pubis, v2))
        assertEquals(only(PainRegion.LOWER_BELLY), front(finestBrush, 0.5f, 0.4315f))

        val armpit = dab(PainView.BACK, finestBrush, 0.276f, 0.218f)
        assertEquals(setOf(area(PainRegion.UPPER_ARM, left), area(PainRegion.UPPER_BACK)), paintedRegions(armpit, v1))
        assertEquals(only(PainRegion.UPPER_BACK), paintedRegions(armpit, v2))
    }

    /** Between the thighs the two inner thighs meet on the axis, and a dab there is both alike. */
    @Test
    fun betweenTheThighsTheTwoSidesMeet() {
        assertEquals(
            setOf(area(PainRegion.INNER_THIGH, left), area(PainRegion.INNER_THIGH, right)),
            front(finestBrush, 0.5f, 0.465f),
        )
        assertEquals(
            setOf(area(PainRegion.THIGH_BACK, left), area(PainRegion.THIGH_BACK, right)),
            back(finestBrush, 0.5f, 0.48f),
        )
        // The axis itself between them belongs to one side or the other, never to nobody.
        listOf(0.45f, 0.47f, 0.485f).forEach { y ->
            assertEquals("front at $y", PainRegion.INNER_THIGH, areaAt(PainView.FRONT, Offset(0.4999f, y), v2)?.region)
        }
        listOf(0.47f, 0.48f, 0.49f).forEach { y ->
            assertEquals("back at $y", PainRegion.THIGH_BACK, areaAt(PainView.BACK, Offset(0.4999f, y), v2)?.region)
        }
    }

    /**
     * A whole-figure dab on the base of the neck in front, on the slope to the shoulder, marks the
     * chest on the first map — and with it the chest's safety line. On the second it is the neck or
     * the ridge to the shoulder, and no part of the chest: at these spots, and on every cell two rows
     * or more above the collarbones ([PainMapSweepTest.aDabWellAboveTheCollarbonesNeverMarksTheChest]).
     */
    @Test
    fun theBaseOfTheNeckIsNeverTheChest() {
        assertEquals(only(PainRegion.CHEST, right), front(wholeFigureBrush, 0.36f, 0.052f, v1))
        val chest = PainRegion.entries.filter { it.root == PainRegion.CHEST }.toSet()
        val neck = setOf(PainRegion.NECK, PainRegion.TRAPEZIUS)
        val spots = listOf(
            0.34f to 0.05f,
            0.36f to 0.052f,
            0.38f to 0.055f,
            0.43f to 0.05f,
            0.62f to 0.052f,
            0.5f to 0.04f,
        )
        spots.forEach { (x, y) ->
            val marked = front(wholeFigureBrush, x, y)
            assertTrue("($x, $y): $marked", marked.isNotEmpty())
            assertTrue("($x, $y): $marked", marked.all { it.region in neck })
            assertTrue("($x, $y): $marked", marked.none { it.region in chest })
            assertFalse("($x, $y)", painNeedsSafetyLine(marked, null))
        }
    }

    /** The middle of the chest is the breastbone, one area: not one side of the chest or the other. */
    @Test
    fun theCentreOfTheChestIsTheBreastbone() {
        listOf(0.5f, 0.506f, 0.494f).forEach { x ->
            assertEquals("whole at $x", only(PainRegion.STERNUM), front(wholeFigureBrush, x, 0.12f))
            assertEquals("zoomed at $x", only(PainRegion.STERNUM), front(zoomedBrush, x, 0.12f))
        }
        // Either side of it is still the chest on that side.
        assertEquals(only(PainRegion.CHEST, right), front(wholeFigureBrush, 0.40f, 0.12f))
        assertEquals(only(PainRegion.CHEST, left), front(wholeFigureBrush, 0.60f, 0.12f))
    }

    /**
     * The belly says where, and on which side: the lower right of it from the front is the person's
     * right lower side, the navel the lower belly, the pit of the stomach the upper belly, under the
     * right ribs the right hypochondrium.
     */
    @Test
    fun theBellySaysWhereAndOnWhichSide() {
        assertEquals(only(PainRegion.LOWER_SIDE, right), front(wholeFigureBrush, 0.36f, 0.32f))
        assertEquals(only(PainRegion.LOWER_SIDE, left), front(wholeFigureBrush, 0.64f, 0.32f))
        // The navel on both plates: the man's at (0.505, 0.302), the woman's at (0.497, 0.304).
        assertEquals(only(PainRegion.LOWER_BELLY), front(wholeFigureBrush, 0.505f, 0.302f))
        assertEquals(only(PainRegion.LOWER_BELLY), front(wholeFigureBrush, 0.497f, 0.304f))
        assertEquals(only(PainRegion.UPPER_BELLY), front(wholeFigureBrush, 0.5f, 0.23f))
        assertEquals(only(PainRegion.UNDER_RIBS, right), front(wholeFigureBrush, 0.34f, 0.24f))
        assertEquals(only(PainRegion.GROIN, right), front(wholeFigureBrush, 0.40f, 0.42f))
    }

    /**
     * From behind the back says its side too: the right loin is on the viewer's right, the left
     * shoulder blade on the viewer's left, and what lies between them is still the spine's.
     */
    @Test
    fun theBackSaysItsSide() {
        assertEquals(only(PainRegion.FLANK, right), back(wholeFigureBrush, 0.64f, 0.28f))
        assertEquals(only(PainRegion.FLANK, right), back(0.012f, 0.62f, 0.29f))
        assertEquals(only(PainRegion.LOWER_BACK), back(wholeFigureBrush, 0.5f, 0.29f))
        assertEquals(only(PainRegion.SHOULDER_BLADE, left), back(wholeFigureBrush, 0.35f, 0.12f))
        assertEquals(only(PainRegion.UPPER_BACK), back(wholeFigureBrush, 0.5f, 0.12f))
        assertEquals(only(PainRegion.TRAPEZIUS, left), back(wholeFigureBrush, 0.35f, 0.06f))
    }

    /** The places of the limbs the first map has no name for, each its own area on the second. */
    @Test
    fun theLegsAndFeetHaveTheirParts() {
        assertEquals(only(PainRegion.BACK_OF_KNEE, left), back(wholeFigureBrush, 0.35f, 0.62f))
        assertEquals(only(PainRegion.HEEL, left), back(wholeFigureBrush, 0.30f, 0.965f))
        // With the figure whole a dab is wider than the ankle, and marks the ankle beside the tendon.
        assertEquals(
            setOf(area(PainRegion.ACHILLES, left), area(PainRegion.ANKLE, left)),
            back(wholeFigureBrush, 0.297f, 0.90f),
        )
        assertEquals(only(PainRegion.ACHILLES, left), back(zoomedBrush, 0.297f, 0.90f))
        assertEquals(only(PainRegion.SACRUM, left), back(zoomedBrush, 0.47f, 0.36f))
        assertEquals(only(PainRegion.TAILBONE), back(zoomedBrush, 0.5f, 0.43f))
        assertEquals(only(PainRegion.INNER_THIGH, right), front(wholeFigureBrush, 0.44f, 0.50f))
        assertEquals(only(PainRegion.ANKLE, right), front(wholeFigureBrush, 0.31f, 0.88f))
        assertEquals(only(PainRegion.TOP_OF_FOOT, right), front(wholeFigureBrush, 0.29f, 0.93f))
        assertEquals(only(PainRegion.TOES, right), front(zoomedBrush, 0.33f, 0.975f))
        assertEquals(only(PainRegion.ELBOW_INNER, right), front(zoomedBrush, 0.17f, 0.27f))
        assertEquals(only(PainRegion.ELBOW, right), front(zoomedBrush, 0.06f, 0.27f))
        assertEquals(only(PainRegion.BACK_OF_UPPER_ARM, left), back(wholeFigureBrush, 0.18f, 0.20f))
    }

    /**
     * A tap is looked up by the cell under it, on the map asked for: the area there, the person's
     * side for a pair, nothing off the body or off the figure.
     */
    @Test
    fun aPointIsLookedUpOnTheMapAskedFor() {
        assertEquals(area(PainRegion.STERNUM), areaAt(PainView.FRONT, Offset(0.5f, 0.12f)))
        assertEquals(area(PainRegion.CHEST, left), areaAt(PainView.FRONT, Offset(0.5f, 0.12f), v1))
        assertEquals(area(PainRegion.LOWER_SIDE, right), areaAt(PainView.FRONT, Offset(0.36f, 0.32f)))
        assertEquals(area(PainRegion.ABDOMEN), areaAt(PainView.FRONT, Offset(0.36f, 0.32f), v1))
        assertEquals(area(PainRegion.FLANK, right), areaAt(PainView.BACK, Offset(0.62f, 0.28f)))
        assertEquals(area(PainRegion.TAILBONE), areaAt(PainView.BACK, Offset(0.5f, 0.43f)))
        assertEquals(area(PainRegion.SACRUM, left), areaAt(PainView.BACK, Offset(0.497f, 0.38f)))
        assertEquals(area(PainRegion.KNEE, left), areaAt(PainView.FRONT, Offset(0.67f, 0.62f)))
        assertNull(areaAt(PainView.FRONT, Offset(0.05f, 0.8f)))
        assertNull(areaAt(PainView.FRONT, Offset(0.5f, -0.05f)))
        assertNull(areaAt(PainView.BACK, Offset(1.2f, 0.5f)))
    }

    /**
     * A tap reads the same grid a painting does: at the centre of every cell of either view, on
     * either map, the area a tap chooses is the one a dab too small to leave the cell marks — or
     * nothing, for both, off the body.
     */
    @Test
    fun aTapChoosesWhatADabOnTheSameCellMarks() {
        val plates = PainMapVersion.entries.flatMap { map -> PainView.entries.map { view -> map to view } }
        plates.forEach { (map, view) ->
            (0 until GRID_ROWS * GRID_COLUMNS).forEach { cell ->
                val x = (cell % GRID_COLUMNS + 0.5f) / GRID_COLUMNS
                val y = (cell / GRID_COLUMNS + 0.5f) / GRID_ROWS
                val tapped = areaAt(view, Offset(x, y), map)
                val dabbed = paintedRegions(dab(view, 0.003f, x, y), map)
                assertEquals("${map.name} $view cell $cell", setOfNotNull(tapped), dabbed)
            }
        }
    }

    /**
     * An area chosen from the list is lit through its own patch; one the map carved up whole lights
     * its parts on its side; one recorded on the first map lights everything it covered there.
     */
    @Test
    fun anAreaIsLitThroughTheAreasThatShowItOnAMap() {
        assertEquals(only(PainRegion.KNEE, left), area(PainRegion.KNEE, left).litOn(v2))
        assertEquals(
            setOf(area(PainRegion.KNEE, left), area(PainRegion.BACK_OF_KNEE, left)),
            area(PainRegion.KNEE, left).litOn(v2, from = v1),
        )
        assertEquals(
            setOf(
                area(PainRegion.ABDOMEN),
                area(PainRegion.UPPER_BELLY),
                area(PainRegion.UNDER_RIBS, left),
                area(PainRegion.UNDER_RIBS, right),
                area(PainRegion.LOWER_BELLY),
                area(PainRegion.LOWER_SIDE, left),
                area(PainRegion.LOWER_SIDE, right),
            ),
            area(PainRegion.ABDOMEN).litOn(v2),
        )
        assertEquals(
            setOf(
                area(PainRegion.ANKLE_FOOT, right),
                area(PainRegion.ANKLE, right),
                area(PainRegion.ACHILLES, right),
                area(PainRegion.HEEL, right),
                area(PainRegion.TOP_OF_FOOT, right),
                area(PainRegion.TOES, right),
            ),
            area(PainRegion.ANKLE_FOOT, right).litOn(v2),
        )
        // A sided parent never lights the centre part carved from both its sides.
        assertEquals(only(PainRegion.CHEST, left), area(PainRegion.CHEST, left).litOn(v2, from = v1))
        // On its own map, an area the first map had is only itself; one no plate shows is only itself.
        assertEquals(only(PainRegion.ABDOMEN), area(PainRegion.ABDOMEN).litOn(v1))
        assertEquals(only(PainRegion.FINGERS, left), area(PainRegion.FINGERS, left).litOn(v2))
        // And every area that shows another on a map has a patch there, but the one shown.
        val shown = area(PainRegion.ABDOMEN).litOn(v2) - area(PainRegion.ABDOMEN)
        assertTrue(shown.all { a -> PainView.entries.any { view -> sidePatches(view, v2).any { it.area == a } } })
    }

    /**
     * Families: the same place told on two maps is one family on sides that agree. A back of the knee
     * is the knee of its own side, the breastbone either side of the chest, a loin the lower back the
     * first map gave no side to; a left loin is never a right one.
     */
    @Test
    fun theSamePlaceOnTwoMapsIsOneFamily() {
        assertTrue(area(PainRegion.BACK_OF_KNEE, left).sameFamilyAs(area(PainRegion.KNEE, left)))
        assertFalse(area(PainRegion.BACK_OF_KNEE, left).sameFamilyAs(area(PainRegion.KNEE, right)))
        assertTrue(area(PainRegion.STERNUM).sameFamilyAs(area(PainRegion.CHEST, left)))
        assertTrue(area(PainRegion.STERNUM).sameFamilyAs(area(PainRegion.CHEST, right)))
        assertTrue(area(PainRegion.FLANK, right).sameFamilyAs(area(PainRegion.LOWER_BACK)))
        assertFalse(area(PainRegion.FLANK, right).sameFamilyAs(area(PainRegion.FLANK, left)))
        assertTrue(area(PainRegion.TAILBONE).sameFamilyAs(area(PainRegion.GLUTE, left)))
        assertFalse(area(PainRegion.SACRUM, left).sameFamilyAs(area(PainRegion.LOWER_BACK)))
        assertFalse(area(PainRegion.KNEE, left).sameFamilyAs(area(PainRegion.SHIN, left)))
        assertEquals(PainRegion.SHOULDER, PainRegion.TRAPEZIUS.root)
        assertEquals(PainRegion.KNEE, PainRegion.KNEE.root)
    }
}
