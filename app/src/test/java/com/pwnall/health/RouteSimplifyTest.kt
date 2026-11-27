package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimplifyTest {
    private fun point(index: Int, lat: Double, lon: Double) = TrackPointEntity(
        trackId = 1,
        timestamp = 1_000L * index,
        lat = lat,
        lon = lon,
        altitudeM = null,
        accuracyM = 5f,
        speedMps = 1.4f,
        provider = "gps",
        accepted = true,
    )

    /** One degree of latitude is about 111 km, so 0.00001 is roughly a metre. */
    private fun straightNorth(count: Int) =
        (0 until count).map { point(it, 55.75 + it * 0.00001, 37.62) }

    @Test
    fun aStraightLineCollapsesToItsEnds() {
        val simplified = simplifyRoute(straightNorth(50))

        assertEquals(2, simplified.size)
        assertEquals(55.75, simplified.first().lat, 1e-9)
        assertEquals(55.75 + 49 * 0.00001, simplified.last().lat, 1e-9)
    }

    @Test
    fun aCornerIsKept() {
        // Twenty metres east, then twenty metres north: the turn is the point of the route.
        val corner = point(1, 55.75, 37.62 + 0.00032)
        val route = listOf(point(0, 55.75, 37.62), corner, point(2, 55.7502, 37.62 + 0.00032))

        val simplified = simplifyRoute(route)

        assertEquals(3, simplified.size)
        assertEquals(corner.timestamp, simplified[1].timestamp)
    }

    @Test
    fun noiseUnderTheToleranceGoes() {
        // A metre of jitter on an otherwise straight line is GNSS noise, not a detour.
        val route = (0 until 30).map { index ->
            point(index, 55.75 + index * 0.00001, 37.62 + if (index % 2 == 0) 0.000005 else 0.0)
        }

        val simplified = simplifyRoute(route, toleranceMetres = 5.0)

        assertTrue("kept ${simplified.size} of ${route.size}", simplified.size < route.size / 3)
    }

    /**
     * Finishing on the street you started from is the ordinary shape of a run, and it is the shape
     * that the distance to the infinite line through the two ends cannot see: the turnaround sits
     * on that line, so judged by it the kilometre out and the kilometre back would export as an
     * eight-metre stub.
     */
    @Test
    fun anOutAndBackRouteKeepsItsTurnaround() {
        // Fifty metres at a time: a kilometre north, then back to eleven metres from the start.
        val out = (0 until 21).map { point(it, 55.75 + it * 0.00045, 37.62) }
        val back = (1 until 21).map { point(20 + it, 55.759 - it * 0.000445, 37.62) }

        val simplified = simplifyRoute(out + back)

        assertTrue("kept ${simplified.size} of ${out.size + back.size}", simplified.size >= 3)
        assertEquals(55.759, simplified.maxOf(TrackPointEntity::lat), 1e-9)
    }

    /** Fifty metres of pavement on Taveuni, where the longitude jumps from 180 to -180. */
    @Test
    fun aStraightLineAcrossTheAntimeridianStillCollapses() {
        val route = (0 until 30).map { index ->
            val lon = 179.9997 + index * 0.00002
            point(index, 25.0, if (lon > 180.0) lon - 360.0 else lon)
        }

        val simplified = simplifyRoute(route)

        assertEquals(2, simplified.size)
    }

    @Test
    fun shortRoutesAndZeroToleranceAreLeftAlone() {
        val two = straightNorth(2)
        val thirty = straightNorth(30)

        assertEquals(two, simplifyRoute(two))
        assertEquals(thirty, simplifyRoute(thirty, toleranceMetres = 0.0))
    }

    @Test
    fun theOrderAndTheEndsSurvive() {
        val route = (0 until 40).map { index ->
            point(index, 55.75 + index * 0.0001, 37.62 + index * index * 0.00001)
        }

        val simplified = simplifyRoute(route)

        assertEquals(route.first(), simplified.first())
        assertEquals(route.last(), simplified.last())
        assertEquals(simplified.map(TrackPointEntity::timestamp).sorted(), simplified.map(TrackPointEntity::timestamp))
    }
}
