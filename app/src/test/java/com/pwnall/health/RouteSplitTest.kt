package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The splits held against the headline total. Both measure from the same anchor: summing every
 * adjacent pair of fixes would count the noise the header refuses and close the splits early.
 */
class RouteSplitTest {
    /** Metres per degree on the sphere the route code uses. */
    private val metresPerDegree = 6_371_000.0 * PI / 180.0

    /** A fix on the equator, where a degree east is a degree north. */
    private fun point(
        secondsIn: Long,
        metresNorth: Double,
        metresEast: Double = 0.0,
        movement: RouteMovement = RouteMovement.WALKING,
    ) = TrackPointEntity(
        trackId = 1,
        timestamp = secondsIn * 1_000,
        lat = metresNorth / metresPerDegree,
        lon = metresEast / metresPerDegree,
        altitudeM = null,
        accuracyM = 5f,
        speedMps = 1.4f,
        provider = "gps",
        accepted = true,
        movementType = movement.name,
    )

    private fun metresBetween(from: TrackPointEntity, to: TrackPointEntity): Double {
        val north = (to.lat - from.lat) * metresPerDegree
        val east = (to.lon - from.lon) * metresPerDegree * cos((from.lat + to.lat) / 2 * PI / 180)
        return hypot(north, east)
    }

    /** What StepService credits: [anchorStep] run over the same fixes, as [RouteTally.add] does. */
    private fun headerMetres(points: List<TrackPointEntity>): Double {
        var anchor = points.first()
        var total = 0.0
        for (fix in points.drop(1)) {
            val leg = metresBetween(anchor, fix)
            when (anchorStep(leg, fix.accuracyM!!.toDouble(), RouteMovement.valueOf(fix.movementType))) {
                AnchorStep.CREDIT -> {
                    total += leg
                    anchor = fix
                }

                AnchorStep.DISCARD -> anchor = fix

                AnchorStep.HOLD -> Unit
            }
        }
        return total
    }

    /**
     * Ten laps of 1 008 m north at 1.2 m/s, a fix every two seconds, each ending at a traffic light:
     * fourteen seconds of wander before the stop is confirmed, then forty seconds standing. The header
     * credits exactly 10 080 m, and the kilometre marks fall inside walking legs rather than on a fix,
     * where a floating-point crumb would decide which side of the mark a fix lands.
     */
    private fun runWithTrafficLights(): List<TrackPointEntity> {
        val points = mutableListOf(point(0, 0.0))
        var seconds = 0L
        var north = 0.0
        repeat(10) {
            repeat(420) {
                seconds += 2
                north += 2.4
                points += point(seconds, north)
            }
            // Not yet confirmed as a stop: the classifier says UNKNOWN for fifteen seconds.
            repeat(7) { index ->
                seconds += 2
                val drift = if (index % 2 == 0) 1.0 else -1.0
                points += point(seconds, north, metresEast = drift, movement = RouteMovement.UNKNOWN)
            }
            // Stopped, drifting two metres east and back, and ending where the walk resumes.
            repeat(20) { index ->
                seconds += 2
                val drift = if (index % 2 == 0) 2.0 else 0.0
                points += point(seconds, north, metresEast = drift, movement = RouteMovement.STOPPED)
            }
        }
        return points
    }

    @Test
    fun aRunWithStopsHasNoMoreSplitsThanItsTotal() {
        val points = runWithTrafficLights()

        val splits = routeSplits(points)

        // Summed fix to fix, the wander at ten lights would be another 140 m and the tail a 220 m split.
        assertEquals(10_080.0, headerMetres(points), 0.01)
        assertEquals(10, splits.count { !it.partial })
        assertEquals(80.0, splits.last().metres, 0.01)
        assertEquals(headerMetres(points), splits.sumOf { it.metres }, 0.01)
    }

    @Test
    fun aStopIsInTheTimeOfTheSplitItFallsIn() {
        val splits = routeSplits(runWithTrafficLights())

        // A kilometre at 1.2 m/s is 833 s. The first mark comes before the first light; the second
        // kilometre has the 54 s spent at that light in it — 887 once both boundaries are
        // interpolated inside their legs and rounded.
        assertEquals(833L, splits[0].seconds)
        assertEquals(887L, splits[1].seconds)
    }

    @Test
    fun anUnclassifiedStretchIsCreditedWhenTheNextClassifiedFixArrives() {
        // A kilometre walked, a kilometre the gait gate never made up its mind about — a slow ride —
        // and then a fix fast enough to be a vehicle. The header credits the whole held segment
        // at that fix, and the splits agree with it rather than skipping the kilometre.
        val points = mutableListOf<TrackPointEntity>()
        (0..200).forEach { points += point(it * 4L, it * 5.0) }
        (1..100).forEach { points += point(800 + it * 4L, 1_000.0 + it * 10.0, movement = RouteMovement.UNKNOWN) }
        points += point(1_204, 2_020.0, movement = RouteMovement.VEHICLE)

        val splits = routeSplits(points)

        assertEquals(2_020.0, headerMetres(points), 0.01)
        assertEquals(headerMetres(points), splits.sumOf { it.metres }, 0.01)
        assertEquals(3, splits.size)
    }

    @Test
    fun aRideTheGateNeverClassifiedHasNoSplitsLikeItsZeroTotal() {
        val ride = (0..100).map { point(it * 2L, it * 6.0, movement = RouteMovement.UNKNOWN) }

        assertEquals(0.0, headerMetres(ride), 0.0)
        assertTrue(routeSplits(ride).isEmpty())
    }

    @Test
    fun mileSplitsCutAtAMileAndPacePerMile() {
        // Two and a half miles at ten minutes a mile.
        val splits = routeSplits(listOf(point(0, 0.0), point(1_500, 2.5 * METRES_IN_A_MILE)), METRES_IN_A_MILE)

        assertEquals(3, splits.size)
        assertEquals(METRES_IN_A_MILE, splits[1].metres, 1e-6)
        assertEquals(600L, splits[1].seconds)
        assertEquals(600L, splits[1].paceSeconds)
        assertEquals("10:00", formatPace(splits[1].paceSeconds))
        assertEquals(METRES_IN_A_MILE, splits[1].splitMetres, 0.0)
        assertTrue(splits.last().partial)
        assertEquals(METRES_IN_A_MILE / 2, splits.last().metres, 0.01)
    }

    @Test
    fun aPartialMileIsPacedPerMileToo() {
        val partial = RouteSplit(
            index = 1,
            metres = METRES_IN_A_MILE / 2,
            seconds = 300,
            partial = true,
            splitMetres = METRES_IN_A_MILE,
        )

        assertEquals(600L, partial.paceSeconds)
    }

    /**
     * 600 m north at 1.2 m/s, the recording paused for ten minutes while the walker went another
     * 300 m, then 600 m more at the same pace. The splits hold the 1 200 m walked and none of the
     * 300 m gone in the pause, and their time is the walking alone: the first kilometre takes the
     * 833 s it took to walk, not the 1 433 s that passed.
     */
    @Test
    fun aPauseIsNeitherDistanceNorTime() {
        val before = (0..250).map { point(it * 2L, it * 2.4) }
        val after = (0..250).map { point(1_100 + it * 2L, 900.0 + it * 2.4) }
            .mapIndexed { index, fix -> if (index == 0) fix.copy(segmentStart = true) else fix }

        val splits = routeSplits(before + after)

        assertEquals(2, splits.size)
        assertEquals(1_000.0, splits[0].metres, 0.0)
        assertEquals(833L, splits[0].seconds)
        assertTrue(splits[1].partial)
        assertEquals(200.0, splits[1].metres, 0.01)
        assertEquals(167L, splits[1].seconds)
    }

    /**
     * The clock the splits are timed by stands still through a pause: the first fix after it lands
     * on the moment of the last one before, and the fixes after it move up by as much.
     */
    @Test
    fun theClockStandsStillThroughAPause() {
        val fixes = listOf(
            point(0, 0.0),
            point(10, 12.0),
            point(100, 50.0).copy(segmentStart = true),
            point(110, 62.0),
            point(500, 90.0).copy(segmentStart = true),
            point(505, 96.0),
        )

        assertEquals(listOf(0L, 10_000L, 10_000L, 20_000L, 20_000L, 25_000L), withoutPauses(fixes).map { it.timestamp })
        // A route that never paused keeps its own times.
        val unpaused = fixes.map { it.copy(segmentStart = false) }
        assertEquals(unpaused, withoutPauses(unpaused))
    }
}
