package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pinch and pan over the pain map, which adb cannot reach: it has no second finger. */
class PainCameraTest {
    private val frame = Size(1080f, 1300f)
    private val fit = figureFit(423f, 900f, frame)

    private fun assertNear(expected: Offset, actual: Offset, tolerance: Float = 1e-3f) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }

    @Test
    fun aFigurePointComesBackFromTheScreenWhereItWent() {
        val points = listOf(Offset(0.5f, -0.1f), Offset(0.3f, 0.62f), Offset(0f, 1f), Offset(1f, FIGURE_TOP))
        val cameras = listOf(
            PainCamera(),
            PainCamera(2.5f, Offset(-300f, -200f)),
            PainCamera(5f, Offset(-2000f, -900f)),
        )
        cameras.forEach { camera ->
            points.forEach { point -> assertNear(point, camera.toFigure(camera.toScreen(point, fit), fit)) }
        }
    }

    @Test
    fun theWholeFigureFitsTheFrameAtZoomOne() {
        val camera = PainCamera()
        val top = camera.toScreen(Offset(0.5f, FIGURE_TOP), fit)
        val bottom = camera.toScreen(Offset(0.5f, FIGURE_BOTTOM), fit)
        val left = camera.toScreen(Offset(0f, 0.5f), fit)
        val right = camera.toScreen(Offset(1f, 0.5f), fit)
        assertEquals(frame.width / 2f, top.x, 0.5f)
        assertEquals(frame.height, bottom.y - top.y, 0.5f)
        assertEquals(0f, top.y, 0.5f)
        // Narrower than the frame, and centred in it.
        assertEquals(frame.width - right.x, left.x, 0.5f)
    }

    @Test
    fun zoomStaysBetweenOneAndFive() {
        val centre = Offset(frame.width / 2f, frame.height / 2f)
        assertEquals(PAIN_ZOOM_MAX, PainCamera(4f).transformed(centre, Offset.Zero, 3f, fit, frame).zoom)
        assertEquals(PAIN_ZOOM_MIN, PainCamera(2f).transformed(centre, Offset.Zero, 0.1f, fit, frame).zoom)
    }

    /** Far enough in that the figure overflows the frame both ways, so nothing re-centres it. */
    @Test
    fun aPinchKeepsThePointUnderTheFingersWhereItWas() {
        val centroid = Offset(540f, 650f)
        val before = PainCamera().toFigure(centroid, fit)
        val after = PainCamera().transformed(centroid, Offset.Zero, 3f, fit, frame)
        assertNear(centroid, after.toScreen(before, fit), 0.5f)
    }

    /**
     * One and a half times in, this figure — the plate alone, 611 pixels wide in this frame — is still
     * narrower than the frame: across, it stays in the middle.
     */
    @Test
    fun anAxisTheFigureDoesNotFillStaysCentred() {
        val after = PainCamera().transformed(Offset(100f, 650f), Offset.Zero, 1.5f, fit, frame)
        val left = after.toScreen(Offset(0f, 0.5f), fit)
        val right = after.toScreen(Offset(1f, 0.5f), fit)
        assertEquals(frame.width - right.x, left.x, 0.5f)
    }

    /** Zoomed out, a drag cannot move the figure off centre; there is nowhere for it to go. */
    @Test
    fun atZoomOneThePanIsAlwaysZero() {
        val dragged = PainCamera().transformed(Offset(500f, 500f), Offset(400f, -300f), 1f, fit, frame)
        assertNear(Offset.Zero, dragged.pan)
    }

    /** Zoomed in, the figure always covers the frame on the axis it overflows. */
    @Test
    fun aDragZoomedInStopsAtTheFiguresEdge() {
        val zoomed = PainCamera(3f)
        val far = zoomed.transformed(Offset(500f, 500f), Offset(10_000f, 10_000f), 1f, fit, frame)
        // The figure's top edge, zoomed three times, sits exactly at the frame's top.
        assertEquals(0f, far.toScreen(Offset(0.5f, FIGURE_TOP), fit).y, 0.5f)
        val other = zoomed.transformed(Offset(500f, 500f), Offset(-10_000f, -10_000f), 1f, fit, frame)
        assertEquals(frame.height, other.toScreen(Offset(0.5f, FIGURE_BOTTOM), fit).y, 0.5f)
    }

    @Test
    fun theFitPlacesAndUnplacesThePlatesOwnCorners() {
        val corner = fit.place(Offset(0f, 0f))
        assertNear(Offset(0f, 0f), fit.unplace(corner))
        assertNear(Offset(1f, 1f), fit.unplace(fit.place(Offset(1f, 1f))))
    }
}
