package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the pain map shows against what it records: the strokes as painted, the areas named from
 * them, and the areas chosen by name — and an entry with strokes above the plates, where no figure
 * is drawn, which must open again with every area it was saved with.
 */
class PainMarksTest {
    private val wholeFigureBrush = 0.035f

    private fun stroke(view: PainView, radius: Float, vararg points: Pair<Float, Float>) =
        PainStroke(view, radius, points.map { Offset(it.first, it.second) })

    private val rightTemple = RegionSide(PainRegion.TEMPLE, BodySide.RIGHT)
    private val backOfHead = RegionSide(PainRegion.HEAD_BACK, BodySide.CENTRE)
    private val leftKnee = RegionSide(PainRegion.KNEE, BodySide.LEFT)
    private val rightKnee = RegionSide(PainRegion.KNEE, BodySide.RIGHT)
    private val lowerBack = RegionSide(PainRegion.LOWER_BACK, BodySide.CENTRE)
    private val rightLoin = RegionSide(PainRegion.FLANK, BodySide.RIGHT)

    /** The left knee from the front, PainRegionsTest's own stroke. */
    private val kneeStroke = stroke(PainView.FRONT, wholeFigureBrush, 0.64f to 0.615f, 0.70f to 0.62f)

    /** The right knee from the front, the same stroke mirrored. */
    private val rightKneeStroke = stroke(PainView.FRONT, wholeFigureBrush, 0.36f to 0.615f, 0.30f to 0.62f)

    /** A dab on the right of the lower back, from behind: the person's right is the viewer's right. */
    private val rightOfLowerBack = stroke(PainView.BACK, 0.012f, 0.62f to 0.29f)

    private fun painRow(day: String, report: PainReport) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = KIND_PAIN,
        title = "Pain",
        calories = 0.0,
        measurementPayloadJson = encodePainPayload(report),
    )

    /**
     * An entry whose temple and back of the head were painted above the plate, read back from its
     * stored payload: the strokes reach no area there, and the page opens with exactly the areas it
     * was saved with — the knee painted beside them included.
     */
    @Test
    fun anOldEntryPaintedOnTheDrawnHeadKeepsItsAreasWhenOpened() {
        val headStrokes = listOf(
            stroke(PainView.FRONT, wholeFigureBrush, 0.41f to -0.08f),
            stroke(PainView.BACK, 0.008f, 0.5f to -0.07f, 0.5f to -0.06f),
        )
        val saved = PainReport(
            regions = listOf(rightTemple, backOfHead, leftKnee),
            strokes = headStrokes + kneeStroke,
        )
        val opened = decodePainPayload(encodePainPayload(saved))
        assertEquals(3, opened.strokes.size)
        assertTrue(opened.strokes.take(2).all { stroke -> stroke.points.all { it.y < 0f } })

        val painted = paintedRegions(opened.strokes, opened.map)
        assertEquals(setOf(leftKnee), painted)
        val (added, removed) = painPageLists(opened)
        assertEquals(setOf(rightTemple, backOfHead), added)
        assertEquals(emptySet<RegionSide>(), removed)
        assertEquals(saved.regions.toSet(), painMarkedAreas(painted, added, removed))
    }

    /** An area the strokes mark that the entry had struck off the list stays struck off when it opens. */
    @Test
    fun anAreaStruckOffStaysOffWhenOpened() {
        val saved = PainReport(regions = emptyList(), strokes = listOf(kneeStroke))
        val (added, removed) = painPageLists(saved)
        assertEquals(emptySet<RegionSide>(), added)
        assertEquals(setOf(leftKnee), removed)
        assertEquals(emptySet<RegionSide>(), painMarkedAreas(paintedRegions(saved.strokes, saved.map), added, removed))
    }

    /**
     * What the page lights, from what it records ([painMarkedAreas]): a dab on the right of the lower
     * back marks the right loin and lights nothing — the figure shows the dab, not the whole of the
     * loin, nor on the first map the whole of the back across both sides. Beside it an area added by
     * name is lit whole, until it is painted too: then its strokes show it and it is no longer lit. A
     * twin added by name is lit on its own side only, and the painted side is not.
     */
    @Test
    fun aPaintedAreaIsNotLit() {
        assertEquals(setOf(lowerBack), paintedRegions(listOf(rightOfLowerBack), PainMapVersion.V1))
        val back = paintedRegions(listOf(rightOfLowerBack), PainMapVersion.V2)
        assertEquals(setOf(rightLoin), back)
        assertEquals(emptyMap<RegionSide, Float>(), painLitAreas(painMarkedAreas(back, emptyList(), emptyList()), back))

        val added = listOf(rightTemple, rightKnee)
        val withList = painMarkedAreas(back, added, emptyList())
        assertEquals(setOf(rightLoin, rightTemple, rightKnee), withList)
        assertEquals(mapOf(rightTemple to 1f, rightKnee to 1f), painLitAreas(withList, back))

        val thenPainted = paintedRegions(listOf(rightOfLowerBack, rightKneeStroke), PainMapVersion.V2)
        assertEquals(setOf(rightKnee, rightLoin), thenPainted)
        val marked = painMarkedAreas(thenPainted, added, emptyList())
        assertEquals(setOf(rightLoin, rightTemple, rightKnee), marked)
        assertEquals(mapOf(rightTemple to 1f), painLitAreas(marked, thenPainted))

        val oneKnee = paintedRegions(listOf(kneeStroke), PainMapVersion.V2)
        assertEquals(setOf(leftKnee), oneKnee)
        val twins = painMarkedAreas(oneKnee, listOf(rightKnee), emptyList())
        assertEquals(mapOf(rightKnee to 1f), painLitAreas(twins, oneKnee))
    }

    /**
     * How strongly the figure lights each area: an area its map carved up whole through its parts, and
     * a part lit by itself and by its parent at the stronger of the two; nothing for an area at nought.
     */
    @Test
    fun theFigureLightsAnAreaThroughItsParts() {
        val abdomen = RegionSide(PainRegion.ABDOMEN, BodySide.CENTRE)
        val upperBelly = RegionSide(PainRegion.UPPER_BELLY, BodySide.CENTRE)
        val lowerBelly = RegionSide(PainRegion.LOWER_BELLY, BodySide.CENTRE)
        // The parent last, so that the part's own strength is not simply the last one written.
        val strengths = litStrengths(mapOf(upperBelly to 1f, leftKnee to 0f, abdomen to 0.5f), PainMapVersion.V2)
        assertEquals(1f, strengths.getValue(upperBelly))
        assertEquals(0.5f, strengths.getValue(lowerBelly))
        assertEquals(0.5f, strengths.getValue(RegionSide(PainRegion.UNDER_RIBS, BodySide.RIGHT)))
        assertFalse(leftKnee in strengths)
        // On the first map the abdomen is one patch, and only itself is lit.
        assertEquals(mapOf(abdomen to 0.5f), litStrengths(mapOf(abdomen to 0.5f), PainMapVersion.V1))
    }

    /** Areas chosen from the list are lit whole beside a painting, and the painted ones are not. */
    @Test
    fun onlyTheAreasChosenByNameAreLit() {
        val painted = setOf(leftKnee)
        val marked = setOf(leftKnee, rightTemple, lowerBack)
        assertEquals(mapOf(rightTemple to 1f, lowerBack to 1f), painLitAreas(marked, painted))
        assertEquals(emptyMap<RegionSide, Float>(), painLitAreas(emptySet(), painted))
    }

    /**
     * The summary shows each entry's strokes as painted, and lights by area only what was chosen by
     * name: the painted knee is in the marks and not in the days, the temple from the list in the days.
     */
    @Test
    fun theSummaryShowsTheStrokesAndLightsOnlyTheListedAreas() {
        val heat = painHeat(
            listOf(
                painRow("2026-03-01", PainReport(regions = listOf(leftKnee), strokes = listOf(kneeStroke))),
                painRow("2026-03-02", PainReport(regions = listOf(leftKnee), strokes = listOf(kneeStroke))),
                painRow("2026-03-02", PainReport(regions = listOf(rightTemple))),
                painRow("2026-03-03", PainReport(regions = listOf(rightTemple))),
                painRow("2026-03-04", PainReport(none = true)),
            ),
        )
        assertEquals(2, heat.marks.size)
        assertEquals(listOf(kneeStroke.view), heat.marks.flatten().map { it.view }.distinct())
        assertEquals(mapOf(rightTemple to 2), heat.listedDays)
    }

    /**
     * An area chosen from the list is lit from faint at one day to full colour at the most-hurt area's
     * days, and no further: a first-map "Knee" counts its days on the back of the knee too, which can
     * then come to more days than the most. No day counted at all lights nothing, and is not a number.
     */
    @Test
    fun anAreaFromTheListIsLitOnTheMostHurtAreasScale() {
        assertEquals(1f, painHeatStrength(count = 10, most = 10), 0f)
        assertEquals(0.415f, painHeatStrength(count = 1, most = 10), 1e-5f)
        assertTrue(painHeatStrength(count = 2, most = 10) > painHeatStrength(count = 1, most = 10))
        assertEquals(1f, painHeatStrength(count = 12, most = 10), 0f)
        assertEquals(0f, painHeatStrength(count = 0, most = 0), 0f)
        assertEquals(0f, painHeatStrength(count = 3, most = 0), 0f)
    }

    /**
     * An entry's strokes above the plate, on the head, show nothing on the summary, even with the
     * whole-figure brush on the jaw and the back of the head, whose reach crosses onto the neck: where
     * they were painted decides. The entry records every area they mark, so only that can leave them
     * out. Every area it records — the head's, and the neck those dabs reach — is still
     * counted by its days and lit whole, since no drawn stroke shows it.
     */
    @Test
    fun oldHeadStrokesShowNothingOnTheSummary() {
        val jaw = RegionSide(PainRegion.JAW, BodySide.RIGHT)
        val head = listOf(
            stroke(PainView.FRONT, wholeFigureBrush, 0.44f to -0.02f),
            stroke(PainView.BACK, wholeFigureBrush, 0.5f to -0.03f),
        )
        val regions = listOf(jaw, backOfHead) + paintedRegions(head, PainMapVersion.CURRENT)
        val heat = painHeat(listOf(painRow("2026-03-01", PainReport(regions = regions, strokes = head))))
        assertEquals(emptyList<List<PainStroke>>(), heat.marks)
        assertEquals(regions.toSet().associateWith { 1 }, heat.listedDays)
    }

    /**
     * A neck painted on the strip just above the plate: the stroke is not drawn,
     * so the neck it recorded is lit whole on the summary rather than shown nowhere.
     */
    @Test
    fun aNeckPaintedOnTheOldStripIsLitOnTheSummary() {
        val neck = RegionSide(PainRegion.NECK, BodySide.CENTRE)
        val strip = stroke(PainView.FRONT, 0.01f, 0.5f to -0.01f)
        val heat = painHeat(listOf(painRow("2026-03-01", PainReport(regions = listOf(neck), strokes = listOf(strip)))))

        assertEquals(emptyList<List<PainStroke>>(), heat.marks)
        assertEquals(mapOf(neck to 1), heat.listedDays)
    }

    /** A stroke over an area struck off the list is not shown on the summary; one still recorded is. */
    @Test
    fun aStrokeOverAnAreaStruckOffIsNotOnTheSummary() {
        val heat = painHeat(
            listOf(
                painRow("2026-03-01", PainReport(nrs = 4, strokes = listOf(kneeStroke))),
                painRow(
                    "2026-03-02",
                    PainReport(regions = listOf(leftKnee), strokes = listOf(kneeStroke, rightOfLowerBack)),
                ),
            ),
        )
        assertEquals(listOf(listOf(PainView.FRONT)), heat.marks.map { strokes -> strokes.map { it.view } })
    }

    /**
     * An entry painted on the first map, read back from its payload, is read on the first map
     * everywhere: the stroke on the back of the knee it recorded as the knee opens with nothing added
     * or struck off, is shown on the summary, and lights nothing whole. Read on the second map the
     * same stroke marks the back of the knee, which the entry never named — the stroke would vanish
     * from the summary and the knee be lit whole in its place.
     */
    @Test
    fun anEntryOfTheFirstMapIsReadOnTheFirstMap() {
        val backOfKnee = stroke(PainView.BACK, 0.018f, 0.30f to 0.62f, 0.37f to 0.62f)
        val payload = """{"schema":1,"regions":["KNEE:L"],"nrs":4,"strokes":[{"v":"B","r":18,"p":[300,620,370,620]}]}"""
        val old = decodePainPayload(payload)
        assertEquals(PainMapVersion.V1, old.map)
        assertEquals(listOf(backOfKnee), old.strokes)
        assertEquals(
            setOf(RegionSide(PainRegion.BACK_OF_KNEE, BodySide.LEFT)),
            paintedRegions(old.strokes, PainMapVersion.V2),
        )

        assertEquals(emptySet<RegionSide>() to emptySet<RegionSide>(), painPageLists(old))
        assertEquals(emptySet<RegionSide>(), painListedAreas(old))
        assertEquals(listOf(backOfKnee), painShownStrokes(old))
        assertEquals(emptySet<RegionSide>(), painUnshownAreas(old))
        val heat = painHeat(listOf(painRow("2026-03-01", old)))
        assertEquals(listOf(listOf(backOfKnee)), heat.marks)
        assertEquals(emptyMap<RegionSide, Int>(), heat.listedDays)
        // And saved again it is the same string: nothing about it moved to the second map.
        assertEquals(payload, encodePainPayload(old))
    }

    /**
     * An area chosen from the list lights, on the summary's figure — the current map's — itself and the
     * parts the current map carved out of it, on its side: the first map's knee its front and its back,
     * the first map's abdomen the six parts of the belly, which have patches where it has none.
     * "Abdomen" named on an entry of the second map, as one written elsewhere might, lights the same six.
     */
    @Test
    fun anAreaChosenFromTheListLightsWhatItCovered() {
        val belly = setOf(
            RegionSide(PainRegion.ABDOMEN, BodySide.CENTRE),
            RegionSide(PainRegion.UPPER_BELLY, BodySide.CENTRE),
            RegionSide(PainRegion.UNDER_RIBS, BodySide.LEFT),
            RegionSide(PainRegion.UNDER_RIBS, BodySide.RIGHT),
            RegionSide(PainRegion.LOWER_BELLY, BodySide.CENTRE),
            RegionSide(PainRegion.LOWER_SIDE, BodySide.LEFT),
            RegionSide(PainRegion.LOWER_SIDE, BodySide.RIGHT),
        )
        val abdomen = RegionSide(PainRegion.ABDOMEN, BodySide.CENTRE)
        val first = PainMapVersion.V1
        val heat = painHeat(
            listOf(
                painRow("2026-03-01", PainReport(regions = listOf(leftKnee), map = first)),
                painRow("2026-03-02", PainReport(regions = listOf(abdomen), map = first)),
                painRow("2026-03-03", PainReport(regions = listOf(abdomen))),
            ),
        )
        assertEquals(
            belly.associateWith { 2 } + mapOf(leftKnee to 1, RegionSide(PainRegion.BACK_OF_KNEE, BodySide.LEFT) to 1),
            heat.listedDays,
        )
        // Every one of them but the patchless abdomen itself has a patch on the figure that shows them.
        val drawn = PainView.entries.flatMap { sidePatches(it, PainMapVersion.CURRENT) }.map { it.area }.toSet()
        assertEquals(heat.listedDays.keys - abdomen, heat.listedDays.keys.filter { it in drawn }.toSet())
    }

    /**
     * The head has no figure, so its areas are spoken with the front's; every other area with its own
     * view — on either map: the knee, which the second map draws on the front only, with the back as
     * well, where the first map drew it and its entries painted it.
     */
    @Test
    fun everyAreaIsSpokenByExactlyOneOrBothFigures() {
        assertTrue(painSpokenOn(PainView.FRONT, rightTemple))
        assertFalse(painSpokenOn(PainView.BACK, rightTemple))
        assertTrue(painSpokenOn(PainView.BACK, lowerBack))
        assertFalse(painSpokenOn(PainView.FRONT, lowerBack))
        assertTrue(painSpokenOn(PainView.FRONT, leftKnee))
        assertTrue(painSpokenOn(PainView.BACK, leftKnee))
        assertTrue(painSpokenOn(PainView.BACK, RegionSide(PainRegion.BACK_OF_KNEE, BodySide.LEFT)))
        assertFalse(painSpokenOn(PainView.FRONT, RegionSide(PainRegion.BACK_OF_KNEE, BodySide.LEFT)))
        // The abdomen, which the second map draws only as its parts, with the front they are on.
        assertTrue(painSpokenOn(PainView.FRONT, RegionSide(PainRegion.ABDOMEN, BodySide.CENTRE)))
        assertFalse(painSpokenOn(PainView.BACK, RegionSide(PainRegion.ABDOMEN, BodySide.CENTRE)))
        RegionSide.ALL.forEach { area ->
            assertTrue(area.code, PainView.entries.any { painSpokenOn(it, area) })
        }
    }
}
