package com.pwnall.health

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The line's colour and width along a walk: each movement's own away from a change, faded between
 * the two over the ground around it, never stepping back, and legible on the ground at every step.
 */
class RouteInkTest {
    /** [count] fixes of [movement] eastward along the equator, about 2.2 m apart, from [start]. */
    private fun stretch(count: Int, movement: RouteMovement, start: Int = 0) = List(count) {
        TrackPointEntity(
            trackId = 1,
            timestamp = 0,
            lat = 0.0,
            lon = (start + it) * 0.00002,
            altitudeM = null,
            accuracyM = 5f,
            speedMps = 1.4f,
            movementType = movement.name,
            provider = "gps",
            accepted = true,
        )
    }

    private fun ink(points: List<TrackPointEntity>, style: MapStyle = DAY_MAP) =
        routeInk(points, projectRoute(points), style)

    /** Every channel within one of [expected]'s: Oklab there and back rounds a channel at most by one. */
    private fun assertInk(expected: Color, argb: Int) {
        val want = expected.toArgb()
        for (shift in 0 until 32 step 8) {
            val channel = abs((want ushr shift and 0xFF) - (argb ushr shift and 0xFF))
            assertTrue("${Integer.toHexString(argb)} against ${Integer.toHexString(want)}", channel <= 1)
        }
    }

    @Test
    fun aWalkBreakingIntoARunFadesFromOneToTheOtherAndNeverBack() {
        val points = stretch(100, RouteMovement.WALKING) + stretch(100, RouteMovement.RUNNING, start = 100)
        val ink = ink(points)
        assertInk(DAY_MAP.walk, ink.colors.first())
        assertInk(DAY_MAP.run, ink.colors.last())
        assertEquals(3f, ink.widths.first())
        assertEquals(5f, ink.widths.last())
        assertEquals("never narrows on the way", ink.widths.sorted(), ink.widths.toList())
        // Centred on the change rather than dragged after it, and spread over tens of metres.
        assertEquals(4f, ink.widths[100], 0.25f)
        val fading = ink.widths.count { it > 3f && it < 5f }
        assertTrue("$fading fixes of fade", fading in 30..120)
        // The blue grows all the way, so the colour too moves only one way.
        val blue = ink.colors.map { it and 0xFF }
        assertEquals(blue.sorted(), blue)
    }

    @Test
    fun aStopOrAStretchNotYetToldLeavesTheLineTheColourAroundIt() {
        val still = stretch(40, RouteMovement.STOPPED, start = 30).map { it.copy(lon = 30 * 0.00002) }
        val points = stretch(30, RouteMovement.WALKING) + still + stretch(30, RouteMovement.WALKING, start = 31) +
            stretch(10, RouteMovement.UNKNOWN, start = 61) + stretch(30, RouteMovement.WALKING, start = 71)
        val ink = ink(points)
        ink.colors.forEach { assertInk(DAY_MAP.walk, it) }
        ink.widths.forEach { assertEquals(3f, it) }
    }

    @Test
    fun everyStepOfEveryFadeClearsThreeToOneOnTheGround() {
        val points = stretch(60, RouteMovement.WALKING) +
            stretch(60, RouteMovement.RUNNING, start = 60) +
            stretch(60, RouteMovement.VEHICLE, start = 120) +
            stretch(60, RouteMovement.WALKING, start = 180) +
            stretch(60, RouteMovement.UNKNOWN, start = 240)
        for (style in listOf(DAY_MAP, NIGHT_MAP)) {
            // The casing laid under the line, the paper between the dots, and a town where the map is
            // drawn in fills: what the line is ever drawn straight on.
            ink(points, style).colors.forEach { argb ->
                for (ground in listOf(style.land, style.dots.paper, style.town)) {
                    val ratio = contrastRatio(Color(argb), ground)
                    assertTrue("${Integer.toHexString(argb)} on $ground: $ratio < 3", ratio >= 3f)
                }
            }
        }
    }

    @Test
    fun aLineWithNoMovementToldIsTheUndecidedGrey() {
        ink(stretch(20, RouteMovement.UNKNOWN)).colors.forEach { assertInk(DAY_MAP.unknown, it) }
    }

    @Test
    fun noFixesNoInk() {
        assertEquals(0, ink(emptyList()).colors.size)
    }
}
