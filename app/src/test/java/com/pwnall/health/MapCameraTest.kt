package com.pwnall.health

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pinch and drag maths, which no emulator run can reach: adb has no second finger. */
class MapCameraTest {
    private val size = Size(1000f, 1200f)

    /** A route the size of a long walk, somewhere in the northern hemisphere. */
    private fun fitted() = MapCamera().apply { fitTo(Rect(0.40f, 0.30f, 0.42f, 0.32f), size) }

    @Test
    fun aFittedRouteSitsInTheMiddleOfTheCanvas() {
        val camera = fitted()

        val corner = camera.screenPosition(0.40, 0.30, size)
        val opposite = camera.screenPosition(0.42, 0.32, size)
        assertEquals(size.width / 2, (corner.x + opposite.x) / 2, 0.5f)
        assertEquals(size.height / 2, (corner.y + opposite.y) / 2, 0.5f)
        // Fitted to the narrower side, with a margin, and never spilling over the edge.
        assertTrue(corner.x > 0f && opposite.x < size.width)
        assertTrue(corner.y > 0f && opposite.y < size.height)
        assertFalse(camera.manual)
    }

    @Test
    fun aPinchLeavesThePointUnderTheFingersWhereItWas() {
        val camera = fitted()
        val before = camera.screenPosition(0.415, 0.305, size)
        val scale = camera.pixelsPerUnit

        camera.transform(centroid = before, pan = Offset.Zero, zoom = 2f, size = size)

        val after = camera.screenPosition(0.415, 0.305, size)
        assertEquals(before.x, after.x, 0.5f)
        assertEquals(before.y, after.y, 0.5f)
        assertEquals(scale * 2, camera.pixelsPerUnit, scale * 1e-6)
        // One gesture and the view stops chasing the route, which is what the corner button undoes.
        assertTrue(camera.manual)
        camera.follow()
        assertFalse(camera.manual)
    }

    @Test
    fun aDragMovesTheMapByExactlyThatMuch() {
        val camera = fitted()
        val before = camera.screenPosition(0.41, 0.31, size)

        camera.transform(Offset(500f, 600f), pan = Offset(-30f, 45f), zoom = 1f, size = size)

        val after = camera.screenPosition(0.41, 0.31, size)
        assertEquals(before.x - 30f, after.x, 0.5f)
        assertEquals(before.y + 45f, after.y, 0.5f)
    }

    @Test
    fun zoomingOutStopsWhereTheAllowedAreaFillsTheScreen() {
        val camera = fitted()

        repeat(20) { camera.transform(Offset(500f, 600f), Offset.Zero, zoom = 0.2f, size = size) }

        // With no route the allowed area is the world, and it covers the canvas rather than
        // sitting inside it: even here there is no zoom that shows the whole map at once. The
        // taller axis is the one with no slack left, so the view is pinned there and free across.
        assertEquals(size.maxDimension.toDouble(), camera.pixelsPerUnit, 1e-6)
        assertEquals(0.5, camera.centerY, 1e-9)
        assertTrue(camera.limit.holds(camera.viewport(size)))
    }

    /** A walk two hundred metres across, which is the case the limit exists for. */
    private val walk = Rect(0.400f, 0.300f, 0.402f, 0.302f)

    private fun restricted() = MapCamera().apply {
        restrict(walk, size)
        fitTo(walk, size)
    }

    @Test
    fun theViewCannotRoamPastWhatTheFarthestViewShowsRoundTheRoute() {
        val camera = restricted()

        repeat(40) { camera.transform(Offset(500f, 600f), Offset(-400f, -400f), zoom = 1f, size) }
        repeat(40) { camera.transform(Offset(500f, 600f), Offset(400f, 400f), zoom = 1f, size) }

        assertTrue(camera.limit.holds(camera.viewport(size)))
        // Half the farthest view's longer side, all round the walk.
        val margin = size.maxDimension / 2 / (HERE_PIXELS_PER_UNIT / MAP_FARTHEST_ZOOM)
        assertEquals(walk.left - margin, camera.limit.left.toDouble(), margin * 0.01)
        assertEquals(walk.right + margin, camera.limit.right.toDouble(), margin * 0.01)
    }

    /** The cap: 168 times the track screen's closest view, for a short walk and a long ride alike. */
    @Test
    fun theMapZoomsOutNoFurtherThanItsCap() {
        for (route in listOf(walk, Rect(0.30f, 0.30f, 0.50f, 0.302f))) {
            val camera = MapCamera().apply {
                restrict(route, size)
                fitTo(route, size)
            }

            repeat(40) { camera.transform(Offset(500f, 600f), Offset.Zero, zoom = 0.5f, size) }

            assertEquals(HERE_PIXELS_PER_UNIT / MAP_FARTHEST_ZOOM, camera.pixelsPerUnit, 1e-6)
        }
    }

    /**
     * A ride wider than the farthest view comes back framed about its middle at that view, its two
     * ends past the edges. The cap holds for a long ride too, which is why the button that
     * comes back here says "Back to the route" and does not promise the whole of it.
     */
    @Test
    fun aRouteWiderThanTheFarthestViewIsFramedAboutItsMiddle() {
        val ride = Rect(0.30f, 0.30f, 0.50f, 0.302f)
        val camera = MapCamera().apply {
            restrict(ride, size)
            fitTo(ride, size)
        }

        assertEquals(HERE_PIXELS_PER_UNIT / MAP_FARTHEST_ZOOM, camera.pixelsPerUnit, 1e-6)
        assertEquals(ride.center.x.toDouble(), camera.centerX, 1e-9)
        val view = camera.viewport(size)
        assertTrue("view $view", view.left > ride.left && view.right < ride.right)
    }

    @Test
    fun thereIsNoZoomThatShowsTheWholeMap() {
        val camera = restricted()

        repeat(40) { camera.transform(Offset(500f, 600f), Offset.Zero, zoom = 0.5f, size) }

        val view = camera.viewport(size)
        // Some fifty kilometres at the very most, not a hemisphere.
        assertTrue("view ${view.width}", view.width < 0.02f)
        assertTrue(camera.limit.holds(view))
    }

    /** A zoom about the middle keeps the view following, at the zoom asked for; only a drag drives. */
    @Test
    fun aZoomKeepsFollowingAndADragDoesNot() {
        val camera = restricted()
        val scale = camera.pixelsPerUnit

        camera.zoomBy(2f, size)

        assertFalse(camera.manual)
        assertTrue(camera.zoomed)
        assertEquals(scale * 2, camera.pixelsPerUnit, scale * 1e-9)
        val saved = with(MapCamera.Saver) { Anything().save(camera) }!!
        assertTrue(MapCamera.Saver.restore(saved)!!.zoomed)
        // Following on: the middle goes to the phone, and the zoom stays the person's.
        camera.centreOn(0.4011, 0.3011, size)
        assertEquals(0.4011, camera.centerX, 1e-9)
        assertEquals(scale * 2, camera.pixelsPerUnit, scale * 1e-9)
        camera.shift(1e-5, 0.0, size)
        assertTrue(camera.manual)
        camera.follow()
        assertFalse(camera.manual || camera.zoomed)
    }

    @Test
    fun aLongerRouteWidensTheAreaItAllows() {
        val camera = MapCamera()

        camera.restrict(walk, size)
        val short = camera.limit.width
        camera.restrict(Rect(0.30f, 0.30f, 0.50f, 0.302f), size)

        assertTrue(camera.limit.width > short)
    }

    @Test
    fun aRouteOfOnePointGetsAViewInsteadOfAnInfiniteZoom() {
        val camera = MapCamera()

        camera.fitTo(Rect(0.4f, 0.3f, 0.4f, 0.3f), size)

        assertTrue(camera.pixelsPerUnit > 0.0)
        assertTrue(camera.pixelsPerUnit.isFinite())
        assertEquals(size.width / 2, camera.screenPosition(0.4, 0.3, size).x, 0.5f)
    }

    @Test
    fun withoutARouteTheWholeWorldIsTheView() {
        val camera = MapCamera()

        camera.fitTo(null, size)

        assertEquals(size.maxDimension.toDouble(), camera.pixelsPerUnit, 1e-9)
        assertEquals(size.width / 2, camera.screenPosition(0.5, 0.5, size).x, 0.5f)
    }

    /** Saving asks the host what it can store; a bundle takes all five of these. */
    private class Anything : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test
    fun aRotationKeepsTheViewThePersonSet() {
        val camera = restricted()
        camera.transform(Offset(500f, 600f), Offset(-40f, 25f), zoom = 3f, size = size)

        // save() takes the SaverScope as its extension receiver and the Saver as its dispatch one,
        // so the Saver has to be the `with`: Saver.save(camera) would read camera's scope off it.
        val saved = with(MapCamera.Saver) { Anything().save(camera) }!!
        val restored = MapCamera.Saver.restore(saved)!!

        assertEquals(camera.centerX, restored.centerX, 1e-12)
        assertEquals(camera.centerY, restored.centerY, 1e-12)
        assertEquals(camera.pixelsPerUnit, restored.pixelsPerUnit, 1e-12)
        // Still driving: a restored camera that forgot this would snap back to the whole route.
        assertTrue(restored.manual)
    }

    @Test
    fun aRotationKeepsHowCloseTheCameraMayZoom() {
        // The track screen's camera, allowed nearer than the default, framing a few streets.
        val camera = MapCamera(closest = HERE_PIXELS_PER_UNIT)
        val streets = Rect(0.4f, 0.3f, 0.400012f, 0.300012f)
        camera.fitTo(streets, size)

        val saved = with(MapCamera.Saver) { Anything().save(camera) }!!
        val restored = MapCamera.Saver.restore(saved)!!
        restored.fitTo(streets, size)

        // Held to the default, it would come back framing twice the ground.
        assertEquals(camera.pixelsPerUnit, restored.pixelsPerUnit, camera.pixelsPerUnit * 1e-9)
        assertTrue(restored.pixelsPerUnit > MapCamera().apply { fitTo(streets, size) }.pixelsPerUnit)
    }

    private fun taveuni(lat: Double, lon: Double) = TrackPointEntity(
        trackId = 1,
        timestamp = 0,
        lat = lat,
        lon = lon,
        altitudeM = null,
        accuracyM = 5f,
        speedMps = 1f,
        provider = "gps",
        accepted = true,
    )

    /**
     * A walk on Taveuni, which the 180th meridian runs through. Projected as stored, its two ends
     * would sit at opposite edges of the map and the fit frame the planet; unwrapped against the first
     * fix they are a hundred metres apart, just past the map's edge.
     */
    @Test
    fun aWalkAcrossTheAntimeridianIsFramedAsAWalk() {
        val walk = listOf(taveuni(-16.8, 179.9997), taveuni(-16.8005, 179.9999), taveuni(-16.801, -179.9997))
        val route = projectRoute(walk)
        val bounds = routeBounds(route)!!
        val camera = MapCamera()

        camera.restrict(bounds, size)
        camera.fitTo(bounds, size)

        // A few hundred metres across, not the world.
        assertTrue("view ${camera.viewport(size).width}", camera.viewport(size).width < 1e-4f)
        // Both ends on the canvas, the far one past x = 1, which the roam limit does not cut at.
        val first = camera.screenPosition(route[0], route[1], size)
        val last = camera.screenPosition(route[4], route[5], size)
        assertTrue(first.x in 0f..size.width && last.x in 0f..size.width)
        assertTrue(last.x > first.x)
    }
}
