package com.pwnall.health

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class Map3DTest {
    /**
     * 45 m of climb over a 1.85 km walk, drawn only at the ×1.68 the hills in the relief round the
     * view call for, would be a pair of pixels high. In a 3 km view the walk's climb is raised ×8 in all, and
     * the ground's own ×1.68 is part of that.
     */
    @Test
    fun aWalksClimbStandsAFifthOfTheWalkInItsOwnView() {
        assertEquals(8f / 1.68f, routeBoost(45f, 1_850.0, 3_000.0, 1.68f), 1e-4f)
        // Six walks across, the walk is raised as the ground is and no more.
        assertEquals(1f, routeBoost(45f, 1_850.0, 6 * 1_850.0, 1.68f), 0f)
        assertEquals(1f, routeBoost(45f, 1_850.0, 40_000.0, 1f), 0f)
        // Halfway between two and six walks across, on a log scale, halfway raised.
        assertEquals(4.5f, routeBoost(45f, 1_850.0, 2 * sqrt(3.0) * 1_850.0, 1f), 1e-3f)
    }

    /**
     * A 100 m walk is a seventh of the closest view the walk page allows, about 720 m: against its
     * own size it would always be six walks away and never raised. Half the closest view
     * is the size its climb is measured against, so at that zoom 13 m stands as a fifth of 360 m.
     */
    @Test
    fun aShortWalkSeenAsCloseAsTheMapGoesIsRaisedInFull() {
        assertEquals(360.0, riseSize(94.0, 720.0), 1e-9)
        assertEquals(1_850.0, riseSize(1_850.0, 720.0), 1e-9)
        assertEquals(0.2f * 360 / 13, routeBoost(13f, riseSize(94.0, 720.0), 720.0, 1f), 1e-4f)
    }

    @Test
    fun aClimbTheBarometerCannotTellFromNoiseIsBarelyRaised() {
        // Two metres over a 100 m walk counts as ten: 0.2 × 100 / 10 = ×2, not ×8.
        assertEquals(2f, routeBoost(2f, 100.0, 150.0, 1f), 1e-4f)
        // A ground already raised past what the walk wants is never lowered.
        assertEquals(1f, routeBoost(45f, 1_850.0, 3_000.0, 8f), 0f)
        assertEquals(1f, routeBoost(45f, 0.0, 3_000.0, 1f), 0f)
    }
    private val size = Size(1_080f, 2_424f)

    private fun camera(heading: Float, zoom: Double = 2.0e6, pitch: Float = DEFAULT_PITCH) =
        TiltCamera(mercatorX(16.49), mercatorY(48.83), zoom, heading, pitch, size, metrePx = 0.5f, baseM = 180f)

    @Test
    fun theViewsMiddleLandsInTheMiddleOfTheScreen() {
        for (heading in listOf(0f, 37f, 90f, 200f, 359f)) {
            val view = camera(heading)
            val out = FloatArray(3)
            assertTrue(view.project(view.centerX, view.centerY, view.baseM, out))
            assertEquals(view.middleX, out[0], 1e-2f)
            assertEquals(view.middleY, out[1], 1e-2f)
            assertEquals(1f, out[2], 1e-4f)
        }
    }

    /** The ground under a pixel, projected back, lands on that pixel: the shader's ray and the CPU's line agree. */
    @Test
    fun theGroundUnderAPixelProjectsBackOntoIt() {
        for ((heading, pitch) in listOf(0f to DEFAULT_PITCH, 45f to MIN_PITCH, 135f to MAX_PITCH, 270f to 55f)) {
            val view = camera(heading, pitch = pitch)
            val out = FloatArray(3)
            for ((px, py) in listOf(540f to 1_212f, 100f to 300f, 1_000f to 2_300f, 300f to 900f)) {
                val ground = view.groundUnder(px, py)!!
                assertTrue(view.project(ground.x, ground.y, view.baseM, out))
                assertEquals("x at $heading", px, out[0], 0.5f)
                assertEquals("y at $heading", py, out[1], 0.5f)
            }
        }
    }

    @Test
    fun aMapTurnedToTheEastHasTheEastAhead() {
        val view = camera(90f)
        val out = FloatArray(3)
        // A point east of the middle is up the screen, one west of it down.
        val east = view.centerX + 1e-4
        assertTrue(view.project(east, view.centerY, view.baseM, out))
        assertTrue(out[1] < view.middleY)
        assertEquals(view.middleX, out[0], 1f)
    }

    @Test
    fun higherGroundStandsHigherOnScreen() {
        val view = camera(0f)
        val low = FloatArray(3)
        val high = FloatArray(3)
        assertTrue(view.project(view.centerX, view.centerY, view.baseM, low))
        assertTrue(view.project(view.centerX, view.centerY, view.baseM + 100f, high))
        assertTrue(high[1] < low[1])
    }

    /** The arrow on the position points where the phone faces, however the map is turned. */
    @Test
    fun theArrowPointsWhereThePhoneFaces() {
        val north = camera(0f)
        val up = north.screenBearing(north.centerX, north.centerY, north.baseM, 0f)!!
        assertEquals(0f, up.x, 1e-3f)
        assertTrue(up.y < -0.99f)
        val east = north.screenBearing(north.centerX, north.centerY, north.baseM, 90f)!!
        assertEquals(1f, east.x, 1e-3f)
        // Turned to the east, the map has the east up the screen, and the north to the left.
        val turned = camera(90f)
        assertTrue(turned.screenBearing(turned.centerX, turned.centerY, turned.baseM, 90f)!!.y < -0.99f)
        assertTrue(turned.screenBearing(turned.centerX, turned.centerY, turned.baseM, 0f)!!.x < -0.99f)
    }

    @Test
    fun theSkyHasNoGround() {
        // Far above the horizon a ray never comes down.
        assertNull(camera(0f).groundUnder(540f, -50_000f))
    }

    /**
     * The rotation matrix as [android.hardware.SensorManager.getRotationMatrixFromVector] fills it: the
     * phone's axes in the world (east, north, up) are its columns.
     */
    private fun matrix(x: FloatArray, y: FloatArray, z: FloatArray) = floatArrayOf(
        x[0], y[0], z[0],
        x[1], y[1], z[1],
        x[2], y[2], z[2],
    )

    @Test
    fun aPhoneFlatOnAPalmFacesWhereItsTopPoints() {
        val up = floatArrayOf(0f, 0f, 1f)
        val north = matrix(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), up)
        val east = matrix(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), up)
        assertEquals(0f, facingDeg(north, 0)!!, 0.01f)
        assertEquals(90f, facingDeg(east, 0)!!, 0.01f)
        // Held on its side, with the display turned a quarter, the screen's top is the phone's right edge.
        assertEquals(90f, facingDeg(north, 1)!!, 0.01f)
    }

    @Test
    fun aPhoneHeldUpFacesWhereItsCameraLooks() {
        // Upright, facing west: the top points at the sky, the back camera west.
        val upright = matrix(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f))
        assertEquals(270f, facingDeg(upright, 0)!!, 0.01f)
    }

    @Test
    fun bearingsAreApartTheShortWayRound() {
        assertEquals(2f, bearingGap(359f, 1f), 1e-4f)
        assertEquals(180f, bearingGap(0f, 180f), 1e-4f)
        assertEquals(30f, bearingGap(100f, 70f), 1e-4f)
    }

    private fun fix(barometer: Float?, gps: Double? = 180.0) = TrackPointEntity(
        trackId = 1,
        timestamp = 0,
        lat = 48.83,
        lon = 16.49,
        altitudeM = gps,
        accuracyM = 5f,
        speedMps = 1.4f,
        barometricAltitudeM = barometer,
        provider = "gps",
        accepted = true,
    )

    @Test
    fun aWalksLiftStartsAtNothingAndIgnoresABarometersSpike() {
        val heights = List(100) { 180f + it * 0.1f }.toMutableList()
        heights[50] = 210f // a gust of wind
        val lift = routeLift(heights.map { fix(it) })
        assertEquals(0f, lift.first(), 1e-4f)
        assertTrue(lift.last() in 8f..10f)
        assertTrue("the spike leaks ${lift[50] - lift[49]}", abs(lift[50] - lift[49]) < 0.5f)
    }

    /**
     * With the climb raised ×5.5, the barometer's drift through a stop at the start would stand the
     * stop's dots up into an orange pillar. Four metres climbed, then four metres of drift
     * standing still, then level walking: the drift is not a climb, before the stop's end or after it.
     */
    @Test
    fun aStopDoesNotClimbWhateverTheBarometerDrifts() {
        val heights = List(40) { 180f + it * 0.1f } + List(40) { 184f + (it + 1) * 0.1f } + List(40) { 188f }
        val points = heights.mapIndexed { index, height ->
            fix(height).let { if (index in 40 until 80) it.copy(movementType = RouteMovement.STOPPED.name) else it }
        }
        val lift = routeLift(points)
        assertTrue("the stop rose ${lift[70] - lift[50]}", abs(lift[70] - lift[50]) < 0.2f)
        // About the 4 m climbed, 3.3 once the smoothing lends the stop part of the climb's last metre;
        // with the drift counted it would read 7.2.
        assertTrue("the walk ended ${lift.last()} up", lift.last() in 2.5f..5f)
    }

    @Test
    fun aWalkWithABarometerIgnoresTheReceiversHeights() {
        val points = List(40) { fix(barometer = 300f, gps = 150.0 + it * 5) }
        assertTrue(routeLift(points).all { abs(it) < 1e-4f })
    }

    @Test
    fun aWalkWithNoHeightsAtAllLiesFlat() {
        val points = List(10) { fix(barometer = null, gps = null) }
        assertTrue(routeLift(points).all { it == 0f })
    }

    @Test
    fun aRunningMedianDropsALoneOutlier() {
        val values = listOf(1.0, 1.0, 9.0, 1.0, 1.0)
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0), runningMedian(values, 3))
    }

    /**
     * From the side the wall's pieces stand hundreds of pixels tall side by side, and where neighbours
     * only touch, Skia rasterises each as a span of its own, some 43 ms of CPU a frame for the wall
     * alone. So each piece reaches a twentieth of a pixel into the next.
     */
    @Test
    fun eachPieceOfTheWallReachesIntoTheNext() {
        // Three fixes going east 4 px apart, the line standing 300 px over its shadow.
        val shadow = floatArrayOf(100f, 500f, 104f, 500f, 108f, 500f)
        val air = floatArrayOf(100f, 200f, 104f, 200f, 108f, 200f)
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        wallPiece(air, shadow, 1, xs, ys)
        val shadowEnd = xs[1]
        val lineEnd = xs[2]
        wallPiece(air, shadow, 2, xs, ys)
        assertEquals(WALL_OVERLAP_PX, shadowEnd - xs[0], 1e-4f)
        assertEquals(WALL_OVERLAP_PX, lineEnd - xs[3], 1e-4f)
        assertEquals(500f, ys[1], 0f)
        // A stop, two fixes on one point, leaves its piece where it is.
        wallPiece(floatArrayOf(100f, 200f, 100f, 200f), floatArrayOf(100f, 500f, 100f, 500f), 1, xs, ys)
        assertEquals(100f, xs[1], 0f)
        assertEquals(100f, xs[2], 0f)
    }
}
