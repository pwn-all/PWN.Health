package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-chat map's tap rule: a tap is kept as a dab every reader of the stored stroke
 * names the areas under the finger by — the hit test, the summary's shown strokes, the page that
 * opens the entry again — and a drag as it was painted. A dab that disagreed with its readers would
 * be hidden on the doctor's sheet, or light an area the person never touched.
 */
class ChatPainMapTest {
    /** The finger's radius, in plate heights, on a map of about 385 dp (the design's measure) and on a small one. */
    private val brushes = listOf(0.036f, 0.06f)

    private fun tap(view: PainView, point: Offset, radius: Float) = PainStroke(view, radius, listOf(point))

    /**
     * Every cell centre of the body on [view], the corner above and to the left of each, and the axis at its height.
     */
    private fun tapPoints(view: PainView): List<Offset> = (0 until GRID_ROWS * GRID_COLUMNS).flatMap { cell ->
        val centre = gridCellCentre(cell)
        val column = cell % GRID_COLUMNS
        val corner = Offset(column.toFloat() / GRID_COLUMNS, (cell / GRID_COLUMNS).toFloat() / GRID_ROWS)
        val axis = Offset(0.5f, centre.y).takeIf { column == GRID_COLUMNS / 2 }
        if (areaAt(view, centre) == null) emptyList() else listOfNotNull(centre, corner, axis)
    }

    /**
     * One tap against every reader: kept unless off the body, its dab names the areas under the finger in
     * the hit test, the record, the payload read back, the summary and the page. True when it is kept.
     */
    private fun tapAgrees(view: PainView, point: Offset, brush: Float): Boolean {
        val dab = chatStroke(tap(view, point, brush))
        if (dab == null) {
            // Only a tap off the body is no stroke: one on a corner where other areas meet is kept too.
            assertTrue("$view $point $brush kept nothing", areasNear(view, quantised(point)).isEmpty())
            return false
        }
        val want = areasNear(view, dab.points.single())
        val painted = paintedRegions(listOf(dab), PainMapVersion.CURRENT)
        assertTrue("$view $point $brush: $want ⊄ $painted", painted.containsAll(want))
        val recorded = chatMarkedAreas(listOf(dab))
        assertEquals("$view $point", want, recorded)
        // As the record keeps it: the payload's own stroke, read back.
        val stored = encodePainPayload(PainReport(regions = recorded.toList(), strokes = listOf(dab)))
        val report = decodePainPayload(stored)
        assertEquals(listOf(dab), report.strokes)
        assertEquals(listOf(dab), painShownStrokes(report))
        assertTrue(painUnshownAreas(report).isEmpty())
        val (added, removed) = painPageLists(report)
        assertEquals(recorded, painMarkedAreas(paintedRegions(report.strokes, report.map), added, removed))
        return true
    }

    /**
     * Every cell centre of the body on both views — and every exact midline and corner beside one — agrees with every
     * reader.
     */
    @Test
    fun everyTapOnEveryCellAgreesWithEveryReader() {
        val taps = PainView.entries.sumOf { view ->
            tapPoints(view).sumOf { point -> brushes.count { brush -> tapAgrees(view, point, brush) } }
        }
        assertTrue("only $taps taps", taps > 6_000)
    }

    /**
     * A map opened again records what was sent: an area added by hand where a dab only spills stays
     * added, and one struck off stays struck off — the lists are read the chat's way ([chatMarkedAreas]).
     */
    @Test
    fun aReopenedMapSplitsTheAnswerBackAsSent() {
        val taps = PainView.entries.asSequence().flatMap { view ->
            (0 until GRID_ROWS * GRID_COLUMNS).asSequence().map { tap(view, gridCellCentre(it), 0.06f) }
        }
        val (dab, spill) = taps.mapNotNull { chatStroke(it) }.firstNotNullOf { dab ->
            val spills = paintedRegions(listOf(dab), PainMapVersion.CURRENT) - chatMarkedAreas(listOf(dab))
            spills.firstOrNull()?.let { dab to it }
        }
        val strokes = listOf(dab)
        val sent = chatMapCodes(strokes, added = listOf(spill.code), removed = emptyList())
        assertTrue(spill.code in sent.split(','))
        val (added, removed) = chatMapLists(sent, strokes)
        assertEquals(listOf(spill.code), added)
        assertEquals(sent, chatMapCodes(strokes, added, removed))
        val marked = chatMarkedAreas(strokes).first().code
        val struck = chatMapCodes(strokes, added = emptyList(), removed = listOf(marked))
        val (again, off) = chatMapLists(struck, strokes)
        assertEquals(listOf(marked), off)
        assertEquals(struck, chatMapCodes(strokes, again, off))
    }

    /** A tap off the body is no stroke at all: nothing drawn, nothing marked. */
    @Test
    fun aTapOffTheBodyIsNoStroke() {
        assertNull(chatStroke(tap(PainView.FRONT, Offset(0.02f, 0.02f), 0.036f)))
        assertNull(chatStroke(tap(PainView.BACK, Offset(0.98f, 0.5f), 0.036f)))
        assertTrue(areasNear(PainView.FRONT, Offset(0.02f, 0.02f)).isEmpty())
        // Off the figure altogether too.
        assertTrue(areasNear(PainView.FRONT, Offset(1.5f, 0.5f)).isEmpty())
    }

    /** A drag is kept exactly as painted; a touch that wanders under half its radius is still a tap. */
    @Test
    fun aDragIsKeptAsPainted() {
        val drag = PainStroke(PainView.FRONT, 0.036f, listOf(Offset(0.4f, 0.62f), Offset(0.4f, 0.70f)))
        assertTrue(drag === chatStroke(drag))
        // A move of more than half the radius is a drag, however short.
        val short = PainStroke(PainView.FRONT, 0.036f, listOf(Offset(0.4f, 0.62f), Offset(0.4f, 0.65f)))
        assertTrue(short === chatStroke(short))
        val wobble = PainStroke(PainView.FRONT, 0.036f, listOf(Offset(0.4f, 0.62f), Offset(0.405f, 0.625f)))
        val dab = checkNotNull(chatStroke(wobble))
        assertEquals(listOf(quantised(Offset(0.4f, 0.62f))), dab.points)
        // A painting of drags marks what the drags mark; a dab, the areas under the finger.
        assertEquals(paintedRegions(listOf(drag), PainMapVersion.CURRENT), chatMarkedAreas(listOf(drag)))
        assertEquals(
            areasNear(PainView.FRONT, dab.points.single()) + paintedRegions(listOf(drag), PainMapVersion.CURRENT),
            chatMarkedAreas(listOf(dab, drag)),
        )
    }

    /** A dab's radius is whole thousandths, no larger than the brush, so the payload stores it as it was drawn. */
    @Test
    fun theDabRadiusIsWholeThousandthsNoLargerThanTheBrush() {
        val rows = 0 until GRID_ROWS step 5
        val cells = rows.flatMap { row -> (0 until GRID_COLUMNS step 3).map { row * GRID_COLUMNS + it } }
        val dabs = PainView.entries.flatMap { view ->
            cells.map(::gridCellCentre).filter { areaAt(view, it) != null }.flatMap { point ->
                brushes.map { brush -> brush to checkNotNull(chatStroke(tap(view, point, brush))) { "$view $point" } }
            }
        }
        dabs.forEach { (brush, dab) ->
            val thousandths = dab.radius * 1_000
            assertEquals("$dab $brush", Math.round(thousandths).toFloat(), thousandths, 1e-3f)
            assertTrue(dab.radius <= brush + 1e-6f)
            assertTrue(dab.radius >= 0.001f - 1e-7f)
            val stored = decodePainPayload(encodePainPayload(PainReport(strokes = listOf(dab))))
            assertEquals(listOf(dab), stored.strokes)
        }
        // Most taps keep the whole finger; one near a border is made smaller, never larger.
        val whole = dabs.count { (brush, dab) -> Math.round(dab.radius * 1_000) == Math.round(brush * 1_000) }
        val shrunk = dabs.size - whole
        assertTrue("whole $whole, shrunk $shrunk", whole > shrunk && shrunk > 0)
    }

    /** The map opens framed on a family where the words named one, and whole where they did not. */
    @Test
    fun theFrameIsTheFamilysPatchesWithRoomAround() {
        assertNull(painFrameTarget(PainFrame(PainView.FRONT, null)))
        assertNull(painFrameTarget(PainFrame(PainView.FRONT, FAMILY_HEAD)))
        val knee = checkNotNull(painFrameTarget(PainFrame(PainView.FRONT, "KNEE")))
        val kneeCells = sidePatches(PainView.FRONT, PainMapVersion.CURRENT).filter { it.area.region == PainRegion.KNEE }
        kneeCells.flatMap { it.points }.forEach {
            assertTrue("$it", it.x >= knee.left && it.x <= knee.right && it.y >= knee.top && it.y <= knee.bottom)
        }
        assertTrue(knee.height < 0.2f)
        assertNotNull(painFrameTarget(PainFrame(PainView.BACK, "BACK")))
        // And the camera that shows it zooms in, never past the framing's cap, centred on it.
        val frame = androidx.compose.ui.geometry.Size(600f, 1_000f)
        val fit = figureFit(423f, 900f, frame)
        val camera = painFraming(knee, fit, frame)
        assertTrue(camera.zoom > 1f && camera.zoom <= PAIN_FRAMING_ZOOM)
        val centre = camera.toScreen(knee.center, fit)
        assertEquals(frame.width / 2, centre.x, 1f)
    }

    /** An answer opened again on the map splits back into the same painting and lists: exactly what was sent. */
    @Test
    fun anAnswerOpenedAgainRecordsWhatWasSent() {
        val dab = checkNotNull(chatStroke(tap(PainView.FRONT, Offset(0.36f, 0.62f), 0.036f)))
        val painted = chatMarkedAreas(listOf(dab))
        val struck = painted.first()
        val sent = chatMapCodes(listOf(dab), added = listOf("TEMPLE:L", "SOLE:R"), removed = listOf(struck.code))
        val (added, removed) = chatMapLists(sent, listOf(dab))
        assertEquals(listOf("TEMPLE:L", "SOLE:R").sorted(), added.sorted())
        assertEquals(listOf(struck.code), removed)
        assertEquals(sent, chatMapCodes(listOf(dab), added, removed))
    }

    /**
     * The in-chat map on any viewport: never shorter than the column of its buttons, and above that never
     * more than about half the list's height nor a figure wider than the screen — landscape and an open
     * keyboard give the buttons' floor, a narrow phone a map its width allows, not the height's share.
     */
    @Test
    fun theChatsMapFitsEveryViewportAboveItsButtonsFloor() {
        val tallests = listOf(200.dp, 400.dp, 700.dp)
        val widths = listOf(320.dp, 411.dp, 840.dp)
        for (tallest in tallests) {
            for (width in widths) {
                val height = chatMapHeight(tallest, width)
                assertTrue("$tallest x $width: $height", height >= PAIN_CANVAS_MIN_HEIGHT)
                assertTrue("$tallest x $width: $height", height <= maxOf(PAIN_CANVAS_MIN_HEIGHT, tallest * 0.55f))
                if (height > PAIN_CANVAS_MIN_HEIGHT) assertTrue("$tallest x $width", height * PLATE_ASPECT < width)
            }
        }
        assertEquals(PAIN_CANVAS_MIN_HEIGHT, chatMapHeight(200.dp, 840.dp))
        assertEquals(385.dp, chatMapHeight(700.dp, 411.dp))
        assertTrue(chatMapHeight(700.dp, 320.dp) < chatMapHeight(700.dp, 411.dp))
    }
}
