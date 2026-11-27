package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the route map draws over the line: the photographs where they were taken, and the way each
 * camera faced.
 */
class RouteMapMarksTest {
    private fun fix(lat: Double, lon: Double) = TrackPointEntity(
        trackId = 1,
        timestamp = 0,
        lat = lat,
        lon = lon,
        altitudeM = null,
        accuracyM = 5f,
        speedMps = 1.4f,
        movementType = RouteMovement.UNKNOWN.name,
        provider = "gps",
        accepted = true,
    )

    private fun photo(takenAt: Long, lat: Double?, lon: Double?, bearing: Float? = null) = TrackPhotoEntity(
        trackId = 1,
        takenAt = takenAt,
        file = "$takenAt.jpg",
        lat = lat,
        lon = lon,
        bearingDeg = bearing,
    )

    @Test
    fun photographsKeepTheirPlaceInTheListAsTheirNumber() {
        val marks = projectPhotos(
            listOf(photo(1, 51.5, -0.1), photo(2, null, null), photo(3, 51.6, -0.2, 90f)),
            listOf(fix(51.5, -0.1)),
        )
        // The second has no position and is not drawn, but the third is still number three.
        assertEquals(listOf(1, 3), marks.map { it.number })
        assertEquals(90f, marks[1].bearingDeg)
    }

    @Test
    fun aPhotographIsProjectedExactlyAsTheLineIs() {
        val route = listOf(fix(51.5, -0.1), fix(51.51, -0.11))
        val line = projectRoute(route)
        val mark = projectPhotos(listOf(photo(1, 51.51, -0.11)), route).single()
        assertEquals(line[2], mark.x, 1e-12)
        assertEquals(line[3], mark.y, 1e-12)
    }

    @Test
    fun aPhotographAcrossTheAntimeridianLandsBesideTheLine() {
        val route = listOf(fix(-16.0, 179.9997), fix(-16.0, -179.9998))
        val line = projectRoute(route)
        val mark = projectPhotos(listOf(photo(1, -16.0, -179.9998)), route).single()
        assertEquals(line[2], mark.x, 1e-12)
        assertTrue("unwrapped past the map's east edge, beside the line: ${mark.x}", mark.x > 1.0)
    }

    @Test
    fun theWedgeIsCentredOnTheBearingOnANorthUpMap() {
        for (bearing in listOf(0f, 45f, 90f, 180f, 245f, 315f, 359f)) {
            val middle = Math.toRadians((viewWedgeStart(bearing) + PHOTO_VIEW_SPREAD / 2).toDouble())
            val b = Math.toRadians(bearing.toDouble())
            // Screen x runs east and screen y runs south, so a bearing b points along (sin b, −cos b).
            assertEquals("x for $bearing", sin(b), cos(middle), 1e-6)
            assertEquals("y for $bearing", -cos(b), sin(middle), 1e-6)
        }
    }
}
