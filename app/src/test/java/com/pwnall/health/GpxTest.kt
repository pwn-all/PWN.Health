package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GpxTest {
    private val track = TrackEntity(id = 1, startedAt = 1_785_400_000_000L)

    private fun point(
        timestamp: Long,
        lat: Double,
        lon: Double,
        accepted: Boolean = true,
        correctedLat: Double? = null,
        altitude: Double? = 120.0,
    ) = TrackPointEntity(
        trackId = 1,
        timestamp = timestamp,
        lat = lat,
        lon = lon,
        correctedLat = correctedLat,
        correctedLon = correctedLat?.let { lon + 0.001 },
        altitudeM = altitude,
        accuracyM = 5f,
        speedMps = 1.4f,
        provider = "gps",
        accepted = accepted,
    )

    @Test
    fun producesOneTrackpointPerAcceptedFix() {
        val gpx = gpxOf(track, listOf(point(2_000, 55.75, 37.61), point(1_000, 55.74, 37.60)))

        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("<gpx version=\"1.1\" creator=\"PWN.Health\""))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun pointsComeOutInTimeOrder() {
        val gpx = gpxOf(track, listOf(point(2_000, 55.75, 37.61), point(1_000, 55.74, 37.60)))

        assertTrue(gpx.indexOf("55.7400000") < gpx.indexOf("55.7500000"))
    }

    @Test
    fun rejectedFixesAreLeftOut() {
        val gpx = gpxOf(track, listOf(point(1_000, 55.74, 37.60, accepted = false)))

        assertEquals(0, Regex("<trkpt ").findAll(gpx).count())
    }

    @Test
    fun correctedCoordinatesWinBecauseTheDistanceUsedThem() {
        val gpx = gpxOf(track, listOf(point(1_000, 55.74, 37.60, correctedLat = 55.99)))

        assertTrue(gpx.contains("lat=\"55.9900000\""))
        assertFalse(gpx.contains("lat=\"55.7400000\""))
    }

    @Test
    fun aPointWithoutAltitudeStillExports() {
        val gpx = gpxOf(track, listOf(point(1_000, 55.74, 37.60, altitude = null)))

        assertTrue(gpx.contains("<trkpt "))
        assertFalse(gpx.contains("<ele>"))
    }

    /**
     * Actually sets the locale it names. Without the switch this would pass on any en_* machine
     * whether or not [gpxOf] formatted with [Locale.ROOT] — a German default turns 55.75 into
     * "55,7500000" and the GPX becomes unparseable. try/finally rather than @Before/@After so an
     * unrelated failure here cannot leave Locale.GERMANY set for the rest of the JVM.
     */
    @Test
    fun coordinatesUseADecimalPointRegardlessOfLocale() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val gpx = gpxOf(track, listOf(point(1_000, 55.75, 37.61)))

            assertTrue(gpx.contains("lat=\"55.7500000\" lon=\"37.6100000\""))
        } finally {
            Locale.setDefault(original)
        }
    }

    /**
     * Every reader takes <ele> as height above sea level; a raw Android fix is height above the
     * WGS84 ellipsoid, and only Android 14's fused provider says otherwise. The file says which.
     */
    @Test
    fun theFileSaysWhatItsElevationsAreHeightsAbove() {
        val ellipsoidal = gpxOf(track, listOf(point(1_000, 55.74, 37.60)))
        val seaLevel = gpxOf(track, listOf(point(1_000, 55.74, 37.60).copy(altitudeMsl = true)))

        assertTrue(ellipsoidal.contains("<!-- ele: height above the WGS84 ellipsoid, not above mean sea level -->"))
        assertTrue(seaLevel.contains("<!-- ele: height above mean sea level -->"))
        assertTrue(seaLevel.contains("<ele>120.0</ele>"))
        // Before the track, where a reader looking for the datum finds it.
        assertTrue(seaLevel.indexOf("<!--") < seaLevel.indexOf("<trk>"))
    }

    @Test
    fun aRouteWithoutAltitudesMakesNoClaimAboutThem() {
        val gpx = gpxOf(track, listOf(point(1_000, 55.74, 37.60, altitude = null)))

        assertFalse(gpx.contains("<!--"))
    }

    @Test
    fun aRouteOfBothDatumsSaysSo() {
        val gpx = gpxOf(
            track,
            listOf(point(1_000, 55.74, 37.60), point(2_000, 55.75, 37.61).copy(altitudeMsl = true)),
        )

        assertTrue(gpx.contains("<!-- ele: mean sea level where the device gave it"))
    }

    /**
     * A pause is a gap in the line: each stretch walked between pauses is a segment of its own, so no
     * reader draws a line across the time the recording stood still.
     */
    @Test
    fun eachStretchBetweenPausesIsASegmentOfItsOwn() {
        val gpx = gpxOf(
            track,
            listOf(
                point(1_000, 55.74, 37.60),
                point(2_000, 55.75, 37.61),
                point(900_000, 55.80, 37.70).copy(segmentStart = true),
                point(901_000, 55.81, 37.71),
            ),
        )

        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertEquals(4, Regex("<trkpt ").findAll(gpx).count())
        // The second segment opens with the first fix after the pause and holds nothing from before it.
        val second = gpx.substring(gpx.lastIndexOf("<trkseg>"))
        assertTrue(second.indexOf("55.8000000") in 0 until second.indexOf("55.8100000"))
        assertFalse(second.contains("55.7500000"))
    }

    /**
     * Five fixes on one straight line, paused after the third. Thinned as one line, only the two ends
     * would be left and a reader would draw straight across the pause; thinned a stretch at a time,
     * each keeps its own ends.
     */
    @Test
    fun eachSegmentIsThinnedOnItsOwn() {
        val straight = (0 until 5).map { point(it * 1_000L, 55.75 + it * 0.0001, 37.61) }
        val paused = straight.mapIndexed { index, fix -> if (index == 3) fix.copy(segmentStart = true) else fix }

        val segments = exportableSegments(paused)

        assertEquals(
            listOf(listOf(0L, 2_000L), listOf(3_000L, 4_000L)),
            segments.map { segment -> segment.map { it.timestamp } },
        )
        assertEquals(1, exportableSegments(straight).size)
        assertEquals(2, exportableSegments(straight).single().size)
    }

    /**
     * The stretches are cut at the accepted fixes marked as starting one, in time order whatever the
     * order the rows came in; a route whose very first fix is so marked is still one stretch.
     */
    @Test
    fun aStretchStartsAtEachMarkedFixAndNowhereElse() {
        val fixes = listOf(
            point(3_000, 55.77, 37.62).copy(segmentStart = true),
            point(1_000, 55.75, 37.61).copy(segmentStart = true),
            point(2_000, 55.76, 37.61),
            point(4_000, 55.78, 37.62, accepted = false),
            point(5_000, 55.79, 37.63),
        )

        assertEquals(
            listOf(listOf(1_000L, 2_000L), listOf(3_000L, 5_000L)),
            routeSegments(fixes).map { segment -> segment.map { it.timestamp } },
        )
        val nothingAccepted = listOf(point(1_000, 55.75, 37.61, accepted = false))
        assertEquals(emptyList<List<TrackPointEntity>>(), routeSegments(nothingAccepted))
    }

    @Test
    fun aRouteWithNothingAcceptedKeepsItsOneEmptySegment() {
        val gpx = gpxOf(track, listOf(point(1_000, 55.74, 37.60, accepted = false)))

        assertEquals(1, Regex("<trkseg>").findAll(gpx).count())
        assertTrue(gpx.contains("    <trkseg>\n    </trkseg>"))
    }
}
