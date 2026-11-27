package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class RouteExportTest {
    private val track = TrackEntity(
        id = 7,
        startedAt = 1_754_000_000_000,
        endedAt = 1_754_003_600_000,
        distanceMeters = 5_120.0,
        points = 3,
        elevationGainM = 42.0,
    )

    private fun point(
        timestamp: Long,
        lat: Double,
        lon: Double,
        accepted: Boolean = true,
        reason: String = "",
    ) = TrackPointEntity(
        trackId = 7,
        timestamp = timestamp,
        lat = lat,
        lon = lon,
        altitudeM = 150.0,
        accuracyM = 5f,
        speedMps = 1.4f,
        provider = "gps",
        accepted = accepted,
        filterReason = reason,
    )

    // The document is written out line by line, so it is checked as the text it is.
    private fun positions(geoJson: String) =
        Regex("\\[[-0-9., ]+\\]").findAll(geoJson.substringAfter("\"coordinates\": [")).toList()

    @Test
    fun geoJsonPutsLongitudeFirst() {
        val geoJson = geoJsonOf(track, listOf(point(1_000, 55.75, 37.61), point(2_000, 55.76, 37.62)))

        assertTrue(geoJson.contains("\"type\": \"Feature\""))
        assertTrue(geoJson.contains("\"type\": \"LineString\""))
        assertEquals("[37.6100000, 55.7500000, 150.0]", positions(geoJson).first().value)
    }

    @Test
    fun geoJsonLeavesOutRejectedFixes() {
        val geoJson = geoJsonOf(
            track,
            listOf(point(1_000, 55.75, 37.61), point(2_000, 55.76, 37.62, accepted = false)),
        )

        // RFC 7946 gives a LineString two positions or more, so a lone accepted fix is a Point —
        // written as a one-position line the file is rejected by every GIS tool that reads it.
        assertTrue(geoJson.contains("\"type\": \"Point\""))
        assertTrue(geoJson.contains("\"coordinates\": [37.6100000, 55.7500000, 150.0]"))
        assertTrue(geoJson.contains("\"points\": 1"))
    }

    @Test
    fun geoJsonWithNothingAcceptedCarriesNoGeometry() {
        val geoJson = geoJsonOf(track, listOf(point(1_000, 55.75, 37.61, accepted = false)))

        assertTrue(geoJson.contains("\"geometry\": null"))
        assertFalse(geoJson.contains("LineString"))
    }

    @Test
    fun csvKeepsEveryFixIncludingTheRejectedOnes() {
        val csv = csvOf(
            listOf(
                point(2_000, 55.76, 37.62, accepted = false, reason = "accuracy, 90 m"),
                point(1_000, 55.75, 37.61),
            ),
        )

        val lines = csv.trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("timestamp,lat,lon"))
        // Time order, not insertion order.
        assertTrue(lines[1].contains("55.7500000"))
        assertTrue(lines[2].contains("false"))
        // A comma inside a field must not become a new column.
        assertTrue(lines[2].contains("\"accuracy, 90 m\""))
        assertEquals(13, lines[0].split(",").size)
        assertEquals(13, lines[1].split(",").size)
    }

    /** The CSV says which fix the line took up again from after a pause, in a column of its own at the end. */
    @Test
    fun csvMarksTheFirstFixAfterAPause() {
        val csv = csvOf(listOf(point(1_000, 55.75, 37.61), point(900_000, 55.80, 37.70).copy(segmentStart = true)))

        val lines = csv.trim().lines()
        assertTrue(lines[0].endsWith(",accepted,filterReason,segmentStart"))
        assertTrue(lines[1].endsWith(",true,,false"))
        assertTrue(lines[2].endsWith(",true,,true"))
    }

    /**
     * RFC 7946: a paused route is one line for each stretch walked between pauses — a MultiLineString —
     * and never a line drawn across a pause.
     */
    @Test
    fun aPausedRouteIsALineForEachStretch() {
        val geoJson = geoJsonOf(
            track,
            listOf(
                point(1_000, 55.75, 37.61),
                point(2_000, 55.76, 37.62),
                point(900_000, 55.80, 37.70).copy(segmentStart = true),
                point(901_000, 55.81, 37.71),
            ),
        )

        assertTrue(geoJson.contains("\"type\": \"MultiLineString\""))
        assertTrue(
            geoJson.contains(
                "\"coordinates\": [" +
                    "[[37.6100000, 55.7500000, 150.0], [37.6200000, 55.7600000, 150.0]], " +
                    "[[37.7000000, 55.8000000, 150.0], [37.7100000, 55.8100000, 150.0]]]",
            ),
        )
        assertTrue(geoJson.contains("\"points\": 4"))
        assertEquals(4, positions(geoJson).size)
    }

    /**
     * A stretch of a single fix has no line to give, and is not joined to the next across the pause:
     * beside a line it is left out, and on its own beside another lone fix the two are points.
     */
    @Test
    fun aStretchOfOneFixIsNeverJoinedAcrossAPause() {
        val lineAndOne = geoJsonOf(
            track,
            listOf(
                point(1_000, 55.75, 37.61),
                point(2_000, 55.76, 37.62),
                point(900_000, 55.80, 37.70).copy(segmentStart = true),
            ),
        )
        val twoAlone = geoJsonOf(
            track,
            listOf(point(1_000, 55.75, 37.61), point(900_000, 55.80, 37.70).copy(segmentStart = true)),
        )

        assertTrue(lineAndOne.contains("\"type\": \"LineString\""))
        assertTrue(lineAndOne.contains("\"points\": 2"))
        assertFalse(lineAndOne.contains("55.8000000"))
        assertTrue(twoAlone.contains("\"type\": \"MultiPoint\""))
        assertTrue(
            twoAlone.contains("\"coordinates\": [[37.6100000, 55.7500000, 150.0], [37.7000000, 55.8000000, 150.0]]"),
        )
        assertTrue(twoAlone.contains("\"points\": 2"))
    }

    /**
     * RFC 7946 §3.1.9: a line across the antimeridian is cut in two on it. Written whole, the leg from
     * 179.99° to −179.99° would be drawn by a GIS tool the long way, round the whole world.
     */
    @Test
    fun aRouteAcrossTheAntimeridianIsCutOnIt() {
        val geoJson = geoJsonOf(track, listOf(point(1_000, -16.80, 179.99), point(2_000, -16.80, -179.99)))

        assertTrue(geoJson.contains("\"type\": \"MultiLineString\""))
        assertTrue(
            geoJson.contains(
                "\"coordinates\": [" +
                    "[[179.9900000, -16.8000000, 150.0], [180.0000000, -16.8000000, 150.0]], " +
                    "[[-180.0000000, -16.8000000, 150.0], [-179.9900000, -16.8000000, 150.0]]]",
            ),
        )
    }

    /** The cut lies where the leg crosses, with the latitude, and the height, the leg had there. */
    @Test
    fun theCutTakesTheLatitudeAndHeightOfTheLegWhereItCrosses() {
        // Westward, a third of the way along; the far fix has no height, so neither has the cut.
        val west = cutAtAntimeridian(listOf(GeoPosition(-179.0, 10.0, 100.0), GeoPosition(178.0, 13.0, null)))

        assertEquals(2, west.size)
        assertEquals(-180.0, west[0].last().lon, 1e-9)
        assertEquals(11.0, west[0].last().lat, 1e-9)
        assertNull(west[0].last().elevation)
        assertEquals(180.0, west[1].first().lon, 1e-9)
        assertEquals(11.0, west[1].first().lat, 1e-9)
        assertEquals(GeoPosition(178.0, 13.0, null), west[1].last())
        val east = cutAtAntimeridian(listOf(GeoPosition(179.0, 0.0, 100.0), GeoPosition(-179.0, 0.0, 200.0)))
        assertEquals(150.0, east[0].last().elevation!!, 1e-9)
        // Nowhere near it, a line comes back whole.
        val moscow = listOf(GeoPosition(37.61, 55.75, 150.0), GeoPosition(37.62, 55.76, 150.0))
        assertEquals(listOf(moscow), cutAtAntimeridian(moscow))
    }

    /** A route that never paused is one LineString. */
    @Test
    fun aRouteThatNeverPausedIsOneLine() {
        val geoJson = geoJsonOf(track, listOf(point(1_000, 55.75, 37.61), point(2_000, 55.76, 37.62)))

        assertTrue(geoJson.contains("\"type\": \"LineString\""))
        assertTrue(
            geoJson.contains("\"coordinates\": [[37.6100000, 55.7500000, 150.0], [37.6200000, 55.7600000, 150.0]]"),
        )
        assertTrue(geoJson.contains("\"points\": 2"))
    }

    @Test
    fun aStraightRouteIsThinnedOnTheWayOutButNotInTheCsv() {
        val straight = (0 until 40).map { point(it * 1_000L, 55.75 + it * 0.00001, 37.62) }

        val geoJson = geoJsonOf(track, straight)
        val csv = csvOf(straight)

        assertEquals(2, positions(geoJson).size)
        assertEquals(41, csv.trim().lines().size)
        assertFalse(csv.contains("\"\""))
    }

    /** The menu offers the three in this order, each under its own name and line, as its own file type. */
    @Test
    fun eachFormatIsNamedAndTypedOnce() {
        assertEquals(listOf(RouteFormat.GPX, RouteFormat.GEO_JSON, RouteFormat.CSV), RouteFormat.entries)
        fun said(format: RouteFormat) = listOf(format.title, format.hint, format.extension, format.mimeType)
        assertEquals(
            listOf(R.string.track_export_gpx, R.string.route_format_gpx_hint, "gpx", "application/gpx+xml"),
            said(RouteFormat.GPX),
        )
        assertEquals(
            listOf(
                R.string.track_export_geojson,
                R.string.route_format_geojson_hint,
                "geojson",
                "application/geo+json",
            ),
            said(RouteFormat.GEO_JSON),
        )
        assertEquals(
            listOf(R.string.track_export_csv, R.string.route_format_csv_hint, "csv", "text/csv"),
            said(RouteFormat.CSV),
        )
        // A shared walk's file takes its format's extension after the walk's own name.
        assertEquals("Coffee run.geojson", walkShareName("Coffee run", 7, RouteFormat.GEO_JSON.extension))
    }

    /** Each format writes the file its own writer writes, from the same track and fixes. */
    @Test
    fun eachFormatWritesItsOwnFile() {
        val fixes = listOf(point(1_000, 55.75, 37.61), point(2_000, 55.76, 37.62, accepted = false, reason = "jump"))

        assertEquals(gpxOf(track, fixes), RouteFormat.GPX.write(track, fixes))
        assertEquals(geoJsonOf(track, fixes), RouteFormat.GEO_JSON.write(track, fixes))
        assertEquals(csvOf(fixes), RouteFormat.CSV.write(track, fixes))
    }

    /** A line needs an accepted fix to be drawn from; the CSV, which keeps the rejected ones, any fix. */
    @Test
    fun aLineNeedsAnAcceptedFixAndTheCsvAnyFix() {
        fun available(fixes: List<TrackPointEntity>) = RouteFormat.entries.filter { it.availableFor(fixes) }

        assertEquals(emptyList<RouteFormat>(), available(emptyList()))
        assertEquals(listOf(RouteFormat.CSV), available(listOf(point(1_000, 55.75, 37.61, accepted = false))))
        assertEquals(
            RouteFormat.entries,
            available(listOf(point(1_000, 55.75, 37.61, accepted = false), point(2_000, 55.76, 37.62))),
        )
    }

    /**
     * A save is written from the fixes read for its route when the picker answers. The page's own
     * list is still empty when the answer comes back to a screen composed afresh, and a file written
     * from that list would have no point in it and be reported as the route exported.
     */
    @Test
    fun aSaveWritesTheFixesReadForItsRouteAndNeverAnEmptyFile() = runBlocking {
        val fixes = listOf(point(1_000, 55.75, 37.61), point(2_000, 55.76, 37.62))
        val asked = mutableListOf<Long>()

        val gpx = routeFileText(RouteFormat.GPX, track) { id -> fixes.also { asked += id } }

        assertEquals(listOf(track.id), asked)
        assertEquals(gpxOf(track, fixes), gpx)
        assertTrue(gpx.orEmpty().contains("<trkpt lat=\"55.7500000\" lon=\"37.6100000\">"))

        // Nothing to hold is no file at all, so the save reports a failure rather than an export.
        for (format in RouteFormat.entries) {
            assertNull(format.name, routeFileText(format, track) { emptyList() })
        }
        val rejected = listOf(point(1_000, 55.75, 37.61, accepted = false))
        assertNull(routeFileText(RouteFormat.GPX, track) { rejected })
        assertEquals(csvOf(rejected), routeFileText(RouteFormat.CSV, track) { rejected })
    }

    /** One route as both map files ought to hold it: [geometry] null for none, and [segments] of GPX. */
    private class Shape(
        val name: String,
        val track: TrackEntity,
        val fixes: List<TrackPointEntity>,
        val geometry: String?,
        val positions: Int,
        val segments: Int,
    )

    private fun positionsIn(geometry: JSONObject): Int {
        val coordinates = geometry.getJSONArray("coordinates")
        return when (geometry.getString("type")) {
            "Point" -> 1
            "LineString", "MultiPoint" -> coordinates.length()
            else -> (0 until coordinates.length()).sumOf { coordinates.getJSONArray(it).length() }
        }
    }

    /**
     * The two map files read by the parsers a map tool reads them with, not as text: a comma or a
     * bracket out of place passes every substring check above and opens nowhere else. A walk still
     * recording, one paused twice, one with nothing accepted, and one of a single fix.
     */
    @Test
    fun bothMapFilesParseAsJsonAndXmlWithTheirShapeIntact() {
        val zigzag = listOf(point(1_000, 55.75, 37.61), point(2_000, 55.76, 37.63), point(3_000, 55.75, 37.65))
        val paused = listOf(
            point(1_000, 55.75, 37.61),
            point(2_000, 55.76, 37.62),
            point(900_000, 55.80, 37.70).copy(segmentStart = true),
            point(901_000, 55.81, 37.71),
            point(1_800_000, 55.85, 37.75).copy(segmentStart = true),
            point(1_801_000, 55.86, 37.76),
        )
        val shapes = listOf(
            Shape("recording", track.copy(endedAt = null), zigzag, "LineString", 3, 1),
            Shape("paused twice", track, paused, "MultiLineString", 6, 3),
            Shape("nothing accepted", track, listOf(point(1_000, 55.75, 37.61, accepted = false)), null, 0, 1),
            Shape("one fix", track, listOf(point(1_000, 55.75, 37.61)), "Point", 1, 1),
        )
        val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        val gpxNamespace = "http://www.topografix.com/GPX/1/1"

        for (shape in shapes) {
            val feature = JSONObject(geoJsonOf(shape.track, shape.fixes))
            assertEquals(shape.name, "Feature", feature.getString("type"))
            val properties = feature.getJSONObject("properties")
            assertEquals(shape.name, shape.positions, properties.getInt("points"))
            assertEquals(shape.name, shape.track.endedAt != null, properties.has("endedAt"))
            if (shape.geometry == null) {
                assertTrue(shape.name, feature.isNull("geometry"))
            } else {
                val geometry = feature.getJSONObject("geometry")
                assertEquals(shape.name, shape.geometry, geometry.getString("type"))
                assertEquals(shape.name, shape.positions, positionsIn(geometry))
            }

            val gpx = xml.parse(gpxOf(shape.track, shape.fixes).byteInputStream())
            assertEquals(shape.name, shape.segments, gpx.getElementsByTagNameNS(gpxNamespace, "trkseg").length)
            assertEquals(shape.name, shape.positions, gpx.getElementsByTagNameNS(gpxNamespace, "trkpt").length)
        }
        // Longitude first, as a parser reads it back.
        val line = JSONObject(geoJsonOf(track, zigzag)).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(37.61, line.getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(55.75, line.getJSONArray(0).getDouble(1), 1e-9)
    }
}
