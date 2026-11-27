package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * The walk chart's figures, read off [walkSeries] and its two steps without drawing anything: the
 * even steps a walk is cut into and the means in them, the empty steps drawn across, the light
 * smoothing, which fixes count, which axis a walk is laid out along and in which units, how much
 * height the elevation axis spans — and the slopes the curve is drawn through them with.
 */
class WalkChartTest {
    /** Metres per degree on the sphere [haversineMetres] uses: along a meridian it measures exactly this. */
    private val metresPerDegree = 6_371_008.8 * PI / 180.0

    /** A fix as the recording stores it; walking unless it says otherwise, so its metres are ground covered. */
    private fun fix(
        secondsIn: Long,
        metresNorth: Double = 0.0,
        altitudeM: Double? = null,
        barometricM: Float? = null,
        speedMps: Float? = null,
        accepted: Boolean = true,
        lon: Double = 0.0,
        movement: RouteMovement = RouteMovement.WALKING,
    ) = TrackPointEntity(
        trackId = 1,
        timestamp = secondsIn * 1_000,
        lat = metresNorth / metresPerDegree,
        lon = lon,
        altitudeM = altitudeM,
        accuracyM = 5f,
        speedMps = speedMps,
        barometricAltitudeM = barometricM,
        movementType = movement.name,
        provider = "gps",
        accepted = accepted,
    )

    /** What [walkSeries] makes of [readings] — a position along the way and the figure read there. */
    private fun drawn(readings: List<Pair<Double, Double>>) =
        movingAverage(bucketMeans(readings, WALK_PROFILE_STEPS), WALK_PROFILE_SMOOTHING)

    private fun assertSeries(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (want, got) -> assertEquals(want, got, 1e-6) }
    }

    @Test
    fun eachStepIsTheMeanOfTheReadingsInIt() {
        val readings = (0..8).map { it.toDouble() to it.toDouble() }
        // Two readings to a step, and the last reading, on the far edge, in the last step.
        assertEquals(listOf(0.5, 2.5, 4.5, 7.0), bucketMeans(readings, 4))
    }

    @Test
    fun aWalkComesOutInTheSameNumberOfStepsWhateverItsLength() {
        val short = (0L..2L).map { fix(it * 60, metresNorth = it * 100.0, altitudeM = 100.0 + it) }
        val long = (0L..999L).map { fix(it, metresNorth = it * 1.4, altitudeM = 100.0 + it % 7) }
        assertEquals(WALK_PROFILE_STEPS, walkSeries(short, WalkMeasure.ELEVATION, imperial = false).size)
        assertEquals(WALK_PROFILE_STEPS, walkSeries(long, WalkMeasure.ELEVATION, imperial = false).size)
    }

    @Test
    fun anEmptyStepLiesOnTheLineBetweenItsNeighbours() {
        assertEquals(listOf(10.0, 12.5, 15.0, 17.5, 20.0), bucketMeans(listOf(0.0 to 10.0, 10.0 to 20.0), 5))
        // A stretch without fixes, a tunnel, is walked across in a straight line from where the
        // readings stopped to where they began again.
        val tunnel = listOf(0.0 to 0.0, 1.0 to 0.0, 9.0 to 8.0, 10.0 to 8.0)
        assertEquals(listOf(0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0), bucketMeans(tunnel, 10))
    }

    @Test
    fun readingsAllAtOnePositionGiveNoSteps() {
        assertEquals(emptyList<Double>(), bucketMeans(listOf(5.0 to 1.0, 5.0 to 2.0), 4))
        assertEquals(emptyList<Double>(), bucketMeans(emptyList(), 4))
    }

    @Test
    fun smoothingKeepsAConstantConstant() {
        assertSeries(List(WALK_PROFILE_STEPS) { 7.25 }, movingAverage(List(WALK_PROFILE_STEPS) { 7.25 }, 5))
    }

    @Test
    fun smoothingKeepsAClimbAClimbAndADescentADescent() {
        val climb = listOf(0.0, 0.0, 1.0, 5.0, 5.0, 6.0, 20.0, 20.0, 21.0, 40.0, 40.0)
        movingAverage(climb, 5).zipWithNext().forEach { (before, after) ->
            assertTrue("$before then $after", after >= before)
        }
        movingAverage(climb.reversed(), 5).zipWithNext().forEach { (before, after) ->
            assertTrue("$before then $after", after <= before)
        }
    }

    @Test
    fun smoothingKeepsTheEndsAndAStraightStretch() {
        val line = (0 until 10).map { 3.0 * it + 1.0 }
        assertSeries(line, movingAverage(line, 5))
    }

    @Test
    fun smoothingIsLightAndCentred() {
        // A spike is spread over the five figures centred on it, a fifth as high, and stays where it was.
        assertSeries(
            listOf(0.0, 0.0, 2.0, 2.0, 2.0, 2.0, 2.0, 0.0, 0.0),
            movingAverage(listOf(0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 0.0), 5),
        )
    }

    @Test
    fun aSteadyClimbKeepsItsSlopeAtEveryPoint() {
        // A flat tangent at every point would turn a hillside into a staircase.
        val climb = listOf(0f, 10f, 20f, 30f).map { Offset(it, 2f * it + 1f) }
        assertEquals(listOf(2f, 2f, 2f, 2f), monotoneTangents(climb))
    }

    @Test
    fun onlyATopABottomOrALevelStretchIsFlat() {
        assertEquals(0f, monotoneTangents(listOf(Offset(0f, 0f), Offset(1f, 5f), Offset(2f, 0f)))[1], 0f)
        assertEquals(0f, monotoneTangents(listOf(Offset(0f, 1f), Offset(1f, 1f), Offset(2f, 5f)))[1], 0f)
    }

    @Test
    fun theCurveNeverOvershootsItsPoints() {
        // A cubic stays inside the hull of its control points, so with both of a stretch's control
        // points between its two ends, the curve rises no higher and dips no lower than they do.
        val heights = listOf(0f, 50f, 52f, 10f, 10f, 80f, 81f, 82f, 0f, 100f, 3f)
        val points = heights.mapIndexed { index, height -> Offset(index * 10f, height) }
        val tangents = monotoneTangents(points)
        for (index in 1..points.lastIndex) {
            val from = points[index - 1]
            val to = points[index]
            val third = (to.x - from.x) / 3f
            val between = (minOf(from.y, to.y) - 1e-4f)..(maxOf(from.y, to.y) + 1e-4f)
            assertTrue("stretch $index", from.y + tangents[index - 1] * third in between)
            assertTrue("stretch $index", to.y - tangents[index] * third in between)
        }
    }

    @Test
    fun aWalkIsLaidOutAlongItsDistanceNotItsTime() {
        // A fix every hundred metres, the later ones further apart in time: a slowing walker. Each
        // fix falls in a step of its own along the distance, so the climb is drawn straight; along
        // the time it would be bunched at the start.
        val walk = (0L..47L).map { fix(it * it, metresNorth = it * 100.0, altitudeM = 100.0 + 2.0 * it) }
        assertSeries((0..47).map { 100.0 + 2.0 * it }, walkSeries(walk, WalkMeasure.ELEVATION, imperial = false))
    }

    @Test
    fun aWalkThatWentNowhereIsLaidOutByTime() {
        // Ten fixes a second apart wandering a metre to and fro, then one more at a thousand seconds
        // where they began: none of them ever far enough from the first to be ground covered. Along
        // the time the first ten share the first step and the last one is the last, and the climb
        // between them is a straight line.
        val wander = (0L..9L).map { fix(it, metresNorth = (it % 2).toDouble(), altitudeM = it.toDouble()) }
        val walk = wander + fix(1_000, altitudeM = 1_000.0)
        val expected = (0..47).map { 4.5 + (1_000.0 - 4.5) * it / 47 }
        assertSeries(expected, walkSeries(walk, WalkMeasure.ELEVATION, imperial = false))
    }

    @Test
    fun aPhoneLeftOnATableIsLaidOutByTimeHoweverLongItsFixesWandered() {
        // Ten minutes of fixes a second apart around one spot, a few metres to and fro while the
        // receiver settles and a fraction of that after. Added up from fix to fix the wander comes
        // to over a kilometre, more than a third of it in the first minute; none of it is ground
        // covered, and the readings are spread along their time.
        val wander = listOf(0.0, 2.5, -1.0, 3.0, -2.0, 1.5)
        val still = (0L until 600L).map { second ->
            val reach = if (second < 60) 2.0 else 0.4
            fix(
                second,
                metresNorth = wander[(second % wander.size).toInt()] * reach,
                altitudeM = 100.0 + second / 60.0,
                movement = RouteMovement.STOPPED,
            )
        }
        val byTime = (0 until 600).map { second -> second * 1_000.0 to 100.0 + second / 60.0 }
        assertSeries(drawn(byTime), walkSeries(still, WalkMeasure.ELEVATION, imperial = false))
    }

    @Test
    fun aStopTakesNoWidthHoweverLongItLasted() {
        // A walk north 45 m every half minute, ten minutes stood at a crossing at 900 m with the fixes
        // wandering around it, then on to 1,845 m. The stop's readings all stand at the crossing,
        // where they drag one step's speed down, rather than taking a stretch of the chart.
        val walkSpeed = 1.5f
        val kmh = distanceValue(walkSpeed * 3_600.0, imperial = false)
        val out = (0L..20L).map { fix(it * 30, metresNorth = it * 45.0, speedMps = walkSpeed) }
        // The last of them is back on the crossing itself, and the walk on is measured from it.
        val wander = listOf(0.0, 2.0, -1.5, 3.0, -2.5, 1.0)
        val stop = (1L..600L).map { second ->
            fix(
                600 + second,
                metresNorth = 900.0 + wander[(second % wander.size).toInt()],
                speedMps = 0f,
                movement = RouteMovement.STOPPED,
            )
        }
        val on = (1L..21L).map { fix(1_200 + it * 30, metresNorth = 900.0 + it * 45.0, speedMps = walkSpeed) }
        val expected = (0..20).map { it * 45.0 to kmh } +
            List(600) { 900.0 to 0.0 } +
            (1..21).map { 900.0 + it * 45.0 to kmh }
        assertSeries(drawn(expected), walkSeries(out + stop + on, WalkMeasure.SPEED, imperial = false))
    }

    @Test
    fun hillsAreDrawnTheSameSizeWhateverHeightTheyStandAt() {
        // The same thirty metres of hills on a walk along a coast, in the hills and in the mountains.
        val shares = listOf(5.0, 400.0, 1_500.0, 2_500.0).map { low ->
            val scale = elevationScale(listOf(low, low + 30.0, low + 10.0), imperial = false)
            assertTrue("$low", scale.min <= low && scale.max >= low + 30.0)
            30.0 / (scale.max - scale.min)
        }
        shares.forEach { assertEquals(shares.first(), it, 1e-9) }
    }

    @Test
    fun aFlatWalkIsDrawnFlat() {
        // Three metres an altitude wandered by, or ten feet, are drawn on the least height the axis
        // spans rather than stretched from the bottom of the plot to the top.
        val metres = elevationScale(listOf(100.0, 103.0, 101.0), imperial = false)
        assertTrue(metres.max - metres.min >= WALK_PROFILE_LEAST_HEIGHT_M)
        val feet = elevationScale(listOf(330.0, 340.0, 335.0), imperial = true)
        assertTrue(feet.max - feet.min >= WALK_PROFILE_LEAST_HEIGHT_M / 0.3048)
    }

    @Test
    fun theElevationAxisGoesBelowTheSeaOnlyForAWalkThatDid() {
        assertEquals(0.0, elevationScale(listOf(1.0, 4.0), imperial = false).min, 0.0)
        assertTrue(elevationScale(listOf(-3.0, 2.0), imperial = false).min < -3.0)
    }

    @Test
    fun aWalkThatNeverMovedStillHasAChart() {
        val still = (0L..30L).map { fix(it * 10, altitudeM = 200.0 + it) }
        val series = walkSeries(still, WalkMeasure.ELEVATION, imperial = false)
        assertEquals(WALK_PROFILE_STEPS, series.size)
        assertEquals(200.0, series.first(), 1e-6)
        assertEquals(230.0, series.last(), 1e-6)
    }

    @Test
    fun rejectedFixesAndMissingFiguresAreLeftOut() {
        val walk = listOf(
            fix(0, metresNorth = 0.0, altitudeM = 100.0, speedMps = 1.0f),
            fix(30, metresNorth = 50.0, altitudeM = 5_000.0, speedMps = 40f, accepted = false),
            fix(60, metresNorth = 100.0, altitudeM = null, speedMps = null),
            fix(120, metresNorth = 200.0, altitudeM = 110.0, speedMps = 2.0f),
        )
        val elevation = walkSeries(walk, WalkMeasure.ELEVATION, imperial = false)
        assertEquals(100.0, elevation.first(), 1e-6)
        assertEquals(110.0, elevation.last(), 1e-6)
        assertTrue(elevation.all { it in 100.0..110.0 })
        val speed = walkSeries(walk, WalkMeasure.SPEED, imperial = false)
        assertEquals(3.6, speed.first(), 1e-6)
        assertEquals(7.2, speed.last(), 1e-6)
        assertTrue(speed.all { it in 3.6..7.2 })
    }

    @Test
    fun fewerThanTwoReadingsIsNoChart() {
        assertEquals(emptyList<Double>(), walkSeries(emptyList(), WalkMeasure.ELEVATION, imperial = false))
        val one = listOf(fix(0, altitudeM = 100.0), fix(60, metresNorth = 100.0), fix(120, metresNorth = 200.0))
        assertEquals(emptyList<Double>(), walkSeries(one, WalkMeasure.ELEVATION, imperial = false))
        val oneAccepted = listOf(
            fix(0, altitudeM = 100.0),
            fix(60, metresNorth = 100.0, altitudeM = 120.0, accepted = false),
        )
        assertEquals(emptyList<Double>(), walkSeries(oneAccepted, WalkMeasure.ELEVATION, imperial = false))
        assertEquals(emptyList<Double>(), walkSeries(oneAccepted, WalkMeasure.SPEED, imperial = false))
    }

    @Test
    fun theBarometerIsPreferredToTheSatellites() {
        val walk = listOf(
            fix(0, metresNorth = 0.0, altitudeM = 300.0, barometricM = 250f),
            fix(60, metresNorth = 100.0, altitudeM = 320.0, barometricM = 250f),
        )
        assertSeries(List(WALK_PROFILE_STEPS) { 250.0 }, walkSeries(walk, WalkMeasure.ELEVATION, imperial = false))
    }

    @Test
    fun anImperialProfileReadsFeetAndMilesAnHour() {
        val walk = (0L..3L).map { fix(it * 60, metresNorth = it * 100.0, altitudeM = 100.0, speedMps = 1.5f) }
        fun flat(value: Double) = List(WALK_PROFILE_STEPS) { value }
        assertSeries(flat(100.0), walkSeries(walk, WalkMeasure.ELEVATION, imperial = false))
        // A foot is 0.3048 m by definition, and 1.5 m/s is 5,400 m an hour.
        assertSeries(flat(100.0 / 0.3048), walkSeries(walk, WalkMeasure.ELEVATION, imperial = true))
        assertSeries(flat(5.4), walkSeries(walk, WalkMeasure.SPEED, imperial = false))
        assertSeries(flat(5_400.0 / 1_609.344), walkSeries(walk, WalkMeasure.SPEED, imperial = true))
    }

    @Test
    fun aWalkAcrossTheAntimeridianIsMetresNotTheWorld() {
        // Steps of the same few dozen metres east along the equator, through the 180th meridian.
        // Measured as a jump of nearly a whole turn, the one leg across would hold the whole chart.
        val walk = (0L..47L).map { index ->
            val east = 179.999 + 0.0004 * index
            fix(index * 30, altitudeM = index.toDouble(), lon = if (east > 180.0) east - 360.0 else east)
        }
        assertSeries((0..47).map { it.toDouble() }, walkSeries(walk, WalkMeasure.ELEVATION, imperial = false))
    }

    /**
     * A walk north 45 m every half minute to 900 m at a stroll, the recording paused for twenty
     * minutes while the walker went on 500 m, then 900 m more at a brisker pace. Along the distance the
     * second stretch is drawn on from where the first ended: the pause takes no width, and the 500 m
     * gone unrecorded in it are not ground covered.
     */
    @Test
    fun aPauseTakesNoWidthAlongTheDistance() {
        val stroll = 1.5f
        val brisk = 2.0f
        val out = (0L..20L).map { fix(it * 30, metresNorth = it * 45.0, speedMps = stroll) }
        val on = (0L..20L).map { fix(1_800 + it * 30, metresNorth = 1_400.0 + it * 45.0, speedMps = brisk) }
            .mapIndexed { index, fix -> if (index == 0) fix.copy(segmentStart = true) else fix }
        val expected = (0..20).map { it * 45.0 to distanceValue(stroll * 3_600.0, imperial = false) } +
            (0..20).map { 900.0 + it * 45.0 to distanceValue(brisk * 3_600.0, imperial = false) }

        assertSeries(drawn(expected), walkSeries(out + on, WalkMeasure.SPEED, imperial = false))
    }

    /**
     * A phone left on a table for ten seconds, paused for a quarter of an hour, then ten seconds more:
     * laid out by time, the clock stands still through the pause, so the second ten seconds follow the
     * first instead of the quarter of an hour taking the chart.
     */
    @Test
    fun aPauseTakesNoWidthAlongTheTime() {
        val first = (0L..9L).map { fix(it, altitudeM = 100.0 + it, movement = RouteMovement.STOPPED) }
        val second = (0L..9L).map { fix(900 + it, altitudeM = 200.0 + it, movement = RouteMovement.STOPPED) }
            .mapIndexed { index, fix -> if (index == 0) fix.copy(segmentStart = true) else fix }
        val byTime = (0..9).map { it * 1_000.0 to 100.0 + it } + (0..9).map { (9 + it) * 1_000.0 to 200.0 + it }

        assertSeries(drawn(byTime), walkSeries(first + second, WalkMeasure.ELEVATION, imperial = false))
    }
}
