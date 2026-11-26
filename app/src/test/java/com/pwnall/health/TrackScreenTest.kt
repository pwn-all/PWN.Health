package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The track screen's own rules: how many bars of signal a fix is worth, which state the recording
 * control stands in, the ground the map frames around the phone before a walk, where the line of a
 * walk that was paused breaks, which routes the history lists, what a touch on the tilted map is,
 * what is counted over a route's fixes where they are read, and the words for a fix thrown out.
 */
class TrackScreenTest {
    private fun fix(lon: Double, segmentStart: Boolean = false) = TrackPointEntity(
        trackId = 1,
        timestamp = 0,
        lat = 0.0,
        lon = lon,
        altitudeM = null,
        accuracyM = 5f,
        speedMps = 1.4f,
        movementType = RouteMovement.WALKING.name,
        provider = "gps",
        accepted = true,
        segmentStart = segmentStart,
    )

    @Test
    fun threeBarsToTenMetresTwoToTwentyFiveAndOneForAnythingCoarser() {
        assertEquals(3, gpsSignalLevel(3f, 0))
        assertEquals(3, gpsSignalLevel(10f, 0))
        assertEquals(2, gpsSignalLevel(10.1f, 0))
        assertEquals(2, gpsSignalLevel(25f, 0))
        assertEquals(1, gpsSignalLevel(25.1f, 0))
        assertEquals(1, gpsSignalLevel(2_000f, 0))
    }

    @Test
    fun aFixThatGaveNoAccuracyIsWorthOneBar() {
        assertEquals(1, gpsSignalLevel(null, 1_000))
    }

    @Test
    fun noFixForTenSecondsOrNoneAtAllIsSearching() {
        assertEquals(3, gpsSignalLevel(5f, 9_999))
        assertEquals(0, gpsSignalLevel(5f, 10_000))
        assertEquals(0, gpsSignalLevel(5f, 600_000))
        assertEquals(0, gpsSignalLevel(null, 10_000))
        assertEquals(0, gpsSignalLevel(5f, null))
        assertEquals(0, gpsSignalLevel(null, null))
    }

    @Test
    fun aFixNewerThanTheClockItIsMeasuredAgainstIsFresh() {
        // The screen's clock moves every couple of seconds, a fix can land between two of its ticks.
        assertEquals(3, gpsSignalLevel(5f, -1_500))
    }

    @Test
    fun theControlIsIdleWithNoOpenWalkAndPausedWhileTheWalkCarriesItsPause() {
        assertEquals(RecordState.IDLE, recordState(null))
        assertEquals(RecordState.RECORDING, recordState(TrackEntity(startedAt = 1_000)))
        assertEquals(RecordState.RECORDING, recordState(TrackEntity(startedAt = 1_000, pausedMillis = 60_000)))
        assertEquals(RecordState.PAUSED, recordState(TrackEntity(startedAt = 1_000, pausedAt = 5_000)))
    }

    @Test
    fun beforeAWalkTheMapFramesFiveHundredMetresOfGroundCentredOnThePhone() {
        val phone = Size(1080f, 2400f)
        val places = listOf(0.0 to 0.0, 25.2 to 55.27, 51.5 to -0.12, 55.75 to 37.62, -33.9 to 151.2, 64.1 to -21.9)
        for ((lat, lon) in places) {
            val box = viewAround(lat, lon)
            val y = mercatorY(lat)
            assertEquals("x at $lat", mercatorX(lon), box.center.x.toDouble(), 1e-6)
            assertEquals("y at $lat", y, box.center.y.toDouble(), 1e-6)
            // In metres of ground at the phone's own latitude, which Mercator stretches toward the poles.
            val metre = mapUnitsFor(1.0, y)
            assertEquals("across at $lat", HERE_VIEW_METRES, box.width / metre, 5.0)
            assertEquals("down at $lat", HERE_VIEW_METRES, box.height / metre, 5.0)
            // And the screen's camera shows that box, not a wider view it stopped zooming at: across a
            // phone 1080 pixels wide, the five hundred metres and the fit's margin either side of them.
            val camera = MapCamera(closest = HERE_PIXELS_PER_UNIT).apply {
                restrict(box, phone)
                fitTo(box, phone)
            }
            val shown = camera.viewport(phone).width / metre
            assertTrue("shown at $lat: $shown m", shown in HERE_VIEW_METRES..HERE_VIEW_METRES * 1.1)
        }
    }

    @Test
    fun theViewLandsBesideALineThatCrossedTheAntimeridian() {
        // The walk started just west of the antimeridian and the phone is now just east of it.
        val box = viewAround(-16.0, -179.9998, origin = 179.9997)
        assertEquals(mercatorX(-179.9998 + 360.0), box.center.x.toDouble(), 1e-6)
        assertTrue("unwrapped past the map's east edge, beside the line: ${box.center.x}", box.center.x > 1f)
    }

    @Test
    fun whileAWalkRecordsTheFrameHoldsBothTheRouteAndThePhone() {
        val route = Rect(0.10f, 0.20f, 0.30f, 0.40f)
        val phone = Rect(0.35f, 0.15f, 0.36f, 0.16f)
        assertEquals(Rect(0.10f, 0.15f, 0.36f, 0.40f), route.spanning(phone))
        assertEquals(route, route.spanning(null))
        assertEquals(phone, null.spanning(phone))
        assertNull(null.spanning(null))
    }

    @Test
    fun theLineNeverRunsIntoTheFirstFixAfterAPause() {
        // drawRoute asks this of every segment, and routeInk of every stride it blends along, so the
        // line and its colours break at the same place.
        val points = listOf(fix(0.0), fix(0.001), fix(0.002), fix(0.010, segmentStart = true), fix(0.011))
        assertEquals(listOf(false, true, true, false, true), points.indices.map { joinsPrevious(points, it) })
    }

    @Test
    fun theFirstFixOfAWalkStartsTheLineWhateverItCarries() {
        val points = listOf(fix(0.0, segmentStart = true), fix(0.001))
        assertEquals(listOf(false, true), points.indices.map { joinsPrevious(points, it) })
    }

    /**
     * The history is every route that has ended, however many there are. Drawn from the latest
     * twenty rows the screen keeps for its map, it would leave the walks before those unopenable.
     */
    @Test
    fun theHistoryListsEveryFinishedRouteNewestFirst() {
        // As the whole table is read: oldest first, and the walk still recording last.
        val finished = (1L..25L).map { TrackEntity(id = it, startedAt = it * 1_000, endedAt = it * 1_000 + 500) }
        val recording = TrackEntity(id = 26, startedAt = 26_000)

        assertEquals((25L downTo 1L).toList(), savedRoutes(finished + recording).map { it.id })
        // The latest twenty, as they stand in for the table until it is read, come out as they went in.
        val latest = finished.takeLast(20).reversed()
        assertEquals(latest, savedRoutes(listOf(recording) + latest))
    }

    /** The system's touch slop on a phone of density 2.75: eight dp. */
    private val slop = 22f

    /**
     * A drag stops the tilted map following the walker, so not every step drags it: not a tap that
     * slid a pixel, nor the finger left on the glass as its neighbour lifted off a pinch.
     */
    @Test
    fun aFingerThatOnlyShiftedDoesNotDragTheMap() {
        assertFalse(dragsMap(Offset.Zero, slop))
        assertFalse(dragsMap(Offset(1f, -2f), slop))
        assertFalse(dragsMap(Offset(0f, slop), slop))
        assertTrue(dragsMap(Offset(0f, slop + 1f), slop))
        // The way come, whichever way: sixteen across and sixteen down is past twenty-two.
        assertTrue(dragsMap(Offset(16f, 16f), slop))
    }

    /**
     * The move is not told by one event's step, which at three pixels an event is never half the
     * slop: told so, two fingers drawn slowly up the map would pinch and twist it and never tip it.
     */
    @Test
    fun twoFingersDrawnSlowlyUpOrDownStillTipTheCamera() {
        val step = Offset(0.5f, -3f)
        assertNull("one step alone says nothing", tipsCamera(step, 1f, slop))

        var path = Offset.Zero
        val told = (1..40).firstNotNullOf {
            path += step
            tipsCamera(path, 1f, slop)
        }

        assertTrue(told)
        // Told as soon as the fingers' middle has come half the slop, not at the end of the move.
        assertEquals(12f, -path.y, 0.001f)
    }

    @Test
    fun aMoveAcrossOrWithAPinchInItIsNotATip() {
        // Not steep enough: under twice as far down as across.
        assertEquals(false, tipsCamera(Offset(10f, 15f), 1f, slop))
        assertEquals(true, tipsCamera(Offset(5f, 15f), 1f, slop))
        // Straight down, but the fingers drew together or apart on the way.
        assertEquals(false, tipsCamera(Offset(0f, 15f), 1.03f, slop))
        assertEquals(false, tipsCamera(Offset(0f, 15f), 0.97f, slop))
        assertEquals(true, tipsCamera(Offset(0f, 15f), 1.01f, slop))
        // A pinch about a middle that has not moved is never told, and goes on pinching.
        assertNull(tipsCamera(Offset(1f, 1f), 1.5f, slop))
    }

    /**
     * What the screen counts over a route's fixes, counted in one place — off the thread that
     * draws, where the rows are read — comes to what counting in composition would.
     */
    @Test
    fun aRoutesFixesArePartedAndCountedInOnePlace() {
        val walked = (0..600).map { fix(it * 0.00001).copy(timestamp = it * 1_000L) }
        val stopped = walked.last().copy(movementType = RouteMovement.STOPPED.name)
        val thrownOut = fix(1.0).copy(accepted = false, filterReason = RouteFixReason.UNCONFIRMED_JUMP.name)
        val all = walked.take(300) + thrownOut + stopped + stopped + walked.drop(300) + stopped

        val fixes = routeFixes(all, weightKg = 80.0, heightCm = 180, restingKcalPerHour = 74.0)

        assertEquals(all, fixes.all)
        assertEquals(all.filter { it.accepted }, fixes.accepted)
        assertEquals(listOf(thrownOut), fixes.rejected)
        assertEquals(walkCalories(fixes.accepted, 80.0, 180, 74.0), fixes.kcal, 0.0)
        assertTrue("a ten-minute walk burns something: ${fixes.kcal}", fixes.kcal > 0.0)
        // Two stops: the pair of fixes in the middle is one, the last fix the other.
        assertEquals(2, fixes.stops)
    }

    /**
     * The map's line comes as one object, its arrays as long as its fixes, because the drawing
     * reads them side by side; and it is drawn only for the walk it was worked out from.
     */
    @Test
    fun theMapsLineComesWholeAndIsDrawnOnlyForItsOwnWalk() {
        val points = listOf(fix(0.0), fix(0.001), fix(0.002))

        val drawn = drawnRoute(points, DAY_MAP)

        assertEquals(points, drawn.points)
        assertTrue(projectRoute(points).contentEquals(drawn.route))
        assertTrue(routeLift(points).contentEquals(drawn.lift))
        assertEquals(points.size, drawn.ink.colors.size)
        assertEquals(points.size, drawn.ink.widths.size)
        // A fix behind the list just read, it is still that walk's line and goes on being drawn.
        assertTrue(drawn.isOf(points + fix(0.003)))
        // Another walk's is not, and neither is any line once there is no walk to draw.
        assertFalse(drawn.isOf(points.map { it.copy(trackId = 2) }))
        assertFalse(drawn.isOf(emptyList()))
        assertTrue(drawnRoute(emptyList(), DAY_MAP).isOf(emptyList()))
    }

    /** The row says every reason in words, never the stored name: UNCONFIRMED_JUMP between two translated lines. */
    @Test
    fun everyReasonAFixIsThrownOutForIsSaidInWords() {
        val rejecting = listOf(
            RouteFixReason.LOW_ACCURACY,
            RouteFixReason.STALE_FIX,
            RouteFixReason.UNCONFIRMED_JUMP,
            RouteFixReason.RETURN_SPIKE,
            RouteFixReason.IMPOSSIBLE_SPEED,
            RouteFixReason.NON_MONOTONIC_TIME,
        )
        val keeping = listOf(
            RouteFixReason.START,
            RouteFixReason.PREDICTION_MATCH,
            RouteFixReason.CONFIRMED_BY_NEXT,
            RouteFixReason.REANCHORED,
        )
        // Every reason is one or the other, so a new one is given its words or named here as kept.
        assertEquals(RouteFixReason.entries.toSet(), (rejecting + keeping).toSet())

        rejecting.forEach { assertNotNull(it.name, filterReasonLabel(it.name)) }
        // The two jumps share their words; every other reason has its own.
        assertEquals(
            filterReasonLabel(RouteFixReason.UNCONFIRMED_JUMP.name),
            filterReasonLabel(RouteFixReason.RETURN_SPIKE.name),
        )
        assertEquals(5, rejecting.map { filterReasonLabel(it.name) }.toSet().size)
        // A fix that was kept, one with no reason, and a reason from a newer build's backup: none.
        keeping.forEach { assertNull(it.name, filterReasonLabel(it.name)) }
        listOf(null, "", "A_REASON_THIS_BUILD_DOES_NOT_KNOW").forEach { assertNull(filterReasonLabel(it)) }
    }

    /**
     * "Current speed" and "Movement" read the last fix, which is now only while the walk records:
     * paused it is from before the pause, and on the last walk, over, from its end.
     */
    @Test
    fun onlyAWalkStillRecordingShowsItsLastFixAsNow() {
        assertNull(standingWord(RecordState.RECORDING))
        assertEquals(R.string.track_value_paused, standingWord(RecordState.PAUSED))
        assertEquals(R.string.track_value_ended, standingWord(RecordState.IDLE))
    }
}
