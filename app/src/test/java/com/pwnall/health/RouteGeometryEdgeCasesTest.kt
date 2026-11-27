package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The route maths at its edges: away from the equator, across the antimeridian, on a lap that ends
 * where it started, and on the export of a track that is still being recorded.
 */
class RouteGeometryEdgeCasesTest {
    /** Metres per degree of latitude on the sphere every route file in this app uses. */
    private val metresPerDegree = 6_371_000.0 * PI / 180.0

    private fun fix(
        secondsIn: Long,
        lat: Double,
        lon: Double,
        movement: RouteMovement = RouteMovement.WALKING,
        accepted: Boolean = true,
        altitudeM: Double? = null,
    ) = TrackPointEntity(
        trackId = 1,
        timestamp = secondsIn * 1_000,
        lat = lat,
        lon = lon,
        altitudeM = altitudeM,
        accuracyM = 5f,
        speedMps = 1.4f,
        provider = "gps",
        accepted = accepted,
        movementType = movement.name,
    )

    /**
     * [legs] legs of ten metres due east along [latitude], one fix every ten seconds — a metre a
     * second, so a kilometre takes a thousand seconds wherever on earth it is walked.
     */
    private fun eastwardWalk(latitude: Double, startLon: Double, legs: Int): List<TrackPointEntity> {
        val step = 10.0 / (metresPerDegree * cos(latitude * PI / 180.0))
        return (0..legs).map { index ->
            val raw = startLon + index * step
            fix(index * 10L, latitude, if (raw > 180.0) raw - 360.0 else raw)
        }
    }

    /**
     * A degree of longitude at 78°N is a fifth of the ground a degree of latitude is. On the
     * equator the two are the same distance, so a split fixture there lets the cosine in the leg be
     * deleted unnoticed — and every walk in northern Europe would be credited nearly five times its
     * length.
     */
    @Test
    fun aKilometreWalkedEastInTheArcticIsStillAKilometre() {
        val splits = routeSplits(eastwardWalk(latitude = 78.0, startLon = 20.0, legs = 250))

        assertEquals("splits were ${splits.map { it.metres }}", 3, splits.size)
        assertEquals(2, splits.count { !it.partial })
        assertEquals(1_000.0, splits[0].metres, 1e-6)
        assertTrue(splits.last().partial)
        assertEquals(500.0, splits.last().metres, 0.01)
        assertEquals(2_500.0, splits.sumOf { it.metres }, 0.01)
        // A metre a second, so the kilometre and its pace are both a thousand seconds.
        assertEquals(1_000L, splits[0].seconds)
        assertEquals(1_000L, splits[0].paceSeconds)
    }

    /**
     * Fifty minutes of pavement on Taveuni, where the longitude a fix is stored with flips from
     * +179.99 to -179.99 partway through. Unwrapped, that one leg reads as most of the way round
     * the world, and the table fills with forty thousand kilometre rows under a 2.5 km walk.
     */
    @Test
    fun aWalkAcrossTheAntimeridianSplitsAtTheKilometreNotAtTheWorld() {
        val walk = eastwardWalk(latitude = 0.0, startLon = 179.995, legs = 250)

        assertTrue(walk.any { it.lon > 179.0 })
        assertTrue(walk.any { it.lon < -179.0 })
        val splits = routeSplits(walk)

        assertEquals("split into ${splits.size}", 3, splits.size)
        assertEquals(1_000.0, splits[0].metres, 1e-6)
        assertEquals(500.0, splits.last().metres, 0.01)
        assertEquals(1_000L, splits[1].seconds)
    }

    /** The rows come back from the DAO in whatever order the query gave them. */
    @Test
    fun splitsDoNotDependOnTheOrderTheRowsCameBackIn() {
        val walk = eastwardWalk(latitude = 55.75, startLon = 37.62, legs = 250)

        assertEquals(3, routeSplits(walk).size)
        assertEquals(routeSplits(walk), routeSplits(walk.shuffled(Random(7))))
        assertEquals(routeSplits(walk), routeSplits(walk.reversed()))
    }

    /**
     * A redelivered fix arrives carrying the timestamp of the one before it — the correlation
     * suite shows the app really does see those. Time inside a leg is shared out in proportion to
     * distance, and a leg of no time at all has to contribute no seconds rather than a negative
     * number or a NaN in the pace column.
     */
    @Test
    fun twoFixesAtTheSameInstantDoNotBendTimeBackwards() {
        val walk = eastwardWalk(latitude = 55.75, startLon = 37.62, legs = 250)
        val redelivered = walk.mapIndexed { index, point ->
            if (index == 100) point.copy(timestamp = walk[99].timestamp) else point
        }

        val splits = routeSplits(redelivered)

        assertEquals(2_500.0, splits.sumOf { it.metres }, 0.01)
        assertTrue("seconds were ${splits.map { it.seconds }}", splits.all { it.seconds >= 0L })
        assertTrue("paces were ${splits.map { it.paceSeconds }}", splits.all { it.paceSeconds >= 0L })
        assertTrue(splits.all { it.metres.isFinite() })
    }

    /**
     * The rejected fixes are filtered out before the size guard, so "four rows, one of them
     * accepted" is the shape that actually reaches it; and two fixes on top of each other cover no
     * ground at all, which is the zero-distance path through the interpolation loop.
     */
    @Test
    fun aTrackWithFewerThanTwoAcceptedFixesHasNoSplits() {
        val lonely = listOf(
            fix(0, 55.75, 37.62),
            fix(10, 55.75, 37.63, accepted = false),
            fix(20, 55.76, 37.64, accepted = false),
            fix(30, 55.77, 37.65, accepted = false),
        )
        val stationary = listOf(fix(0, 55.75, 37.62), fix(60, 55.75, 37.62))

        assertTrue(routeSplits(emptyList()).isEmpty())
        assertTrue("split ${routeSplits(lonely)}", routeSplits(lonely).isEmpty())
        assertTrue("split ${routeSplits(stationary)}", routeSplits(stationary).isEmpty())
    }

    /**
     * A movement class written by a newer build, or a column a corruption left blank. The KDoc
     * promises that a name this build does not know is UNKNOWN; RouteMovement.valueOf() would turn
     * opening an old track into a crash on a screen with no way out.
     */
    @Test
    fun aMovementClassThisBuildHasNeverHeardOfIsNotACrash() {
        assertTrue(RouteMovement.entries.none { it.name == "SKIING" })
        val skied = (0..250).map { index ->
            fix(index * 10L, 55.75, 37.62 + index * 10.0 / metresPerDegree)
                .copy(movementType = "SKIING")
        }
        val blank = skied.map { it.copy(movementType = "") }

        // UNKNOWN holds the anchor and credits nothing, exactly like the ride the gait gate never
        // made up its mind about: nothing to split, and nothing to throw either.
        assertTrue("split ${routeSplits(skied).size}", routeSplits(skied).isEmpty())
        assertTrue("split ${routeSplits(blank).size}", routeSplits(blank).isEmpty())
    }

    /**
     * A pace of "10:00" has seconds of two zeros, so padStart could be deleted and it would still
     * pass, while a 10:05 kilometre would print as "10:5".
     */
    @Test
    fun paceIsMinutesAndTwoDigitSecondsOrElseADash() {
        assertEquals("—", formatPace(0))
        assertEquals("—", formatPace(-30))
        assertEquals("0:01", formatPace(1))
        assertEquals("0:59", formatPace(59))
        assertEquals("1:00", formatPace(60))
        assertEquals("10:05", formatPace(605))
        assertEquals("59:59", formatPace(3_599))
        assertEquals("60:00", formatPace(3_600))
    }

    /**
     * The tail of a walk is a few metres of drift over real seconds, and extrapolating a pace from
     * it would print hours per kilometre under a route that had just been run in five minutes. Fifty
     * metres is where there is enough tail to quote from, so the cutoff is held at its edge.
     */
    @Test
    fun fiftyMetresIsEnoughTailToQuoteAPaceFrom() {
        // 50 m in 100 s is two seconds a metre, so two thousand seconds a kilometre.
        assertEquals(2_000L, RouteSplit(1, 50.0, seconds = 100, partial = true).paceSeconds)
        assertEquals(0L, RouteSplit(1, 49.9, seconds = 100, partial = true).paceSeconds)
        assertEquals(0L, RouteSplit(1, 0.0, seconds = 100, partial = true).paceSeconds)
        assertEquals(0L, RouteSplit(1, -1.0, seconds = 100, partial = true).paceSeconds)
        // A whole split is never a tail: its pace is its own seconds, however short the split is.
        assertEquals(300L, RouteSplit(1, METRES_IN_A_KILOMETRE, seconds = 300, partial = false).paceSeconds)
    }

    /** A 200 m square walked clockwise, twenty fixes a side, ending on the bench it started from. */
    private fun closedLap(): List<TrackPointEntity> {
        val corners = listOf(0.0 to 0.0, 0.0 to 200.0, 200.0 to 200.0, 200.0 to 0.0, 0.0 to 0.0)
        val points = mutableListOf<TrackPointEntity>()
        for ((from, to) in corners.zipWithNext()) {
            repeat(20) { step ->
                val part = step / 20.0
                val north = from.first + (to.first - from.first) * part
                val east = from.second + (to.second - from.second) * part
                points += fix(points.size * 5L, north / metresPerDegree, east / metresPerDegree)
            }
        }
        return points + fix(points.size * 5L, 0.0, 0.0)
    }

    /** Metres from the corner the lap starts at, on an equator where a degree east is a degree north. */
    private fun metresFromStart(point: TrackPointEntity) =
        hypot(point.lat * metresPerDegree, point.lon * metresPerDegree)

    /**
     * A lap of a park that ends on the bench it started from hands Douglas–Peucker a base segment
     * of zero length, and that is the classic input on which an implementation returns its two
     * endpoints — an empty exported line under a distance card that says 800 m.
     */
    @Test
    fun anExactlyClosedLoopKeepsItsFarSide() {
        val lap = closedLap()
        assertEquals(lap.first().lat, lap.last().lat, 0.0)
        assertEquals(lap.first().lon, lap.last().lon, 0.0)

        val simplified = simplifyRoute(lap)

        assertTrue("kept ${simplified.size} of ${lap.size}", simplified.size >= 5)
        // The far corner of a 200 m square is 200 × √2 from the start, and it has to survive.
        assertEquals(282.84, simplified.maxOf(::metresFromStart), 0.5)
    }

    /**
     * keep[0] and keep[lastIndex] are written before anything is looked at, so the size guard is
     * the only thing between an empty list and an index out of bounds; five fixes on top of each
     * other are the zero-length segment again.
     */
    @Test
    fun nothingOneFixAndAStackOfIdenticalFixesAllSimplify() {
        val one = listOf(fix(0, 55.75, 37.62))
        val identical = (0..4).map { fix(it * 5L, 55.75, 37.62) }
        val straight = (0..29).map { fix(it * 5L, 55.75 + it * 0.00001, 37.62) }

        assertTrue(simplifyRoute(emptyList()).isEmpty())
        assertEquals(one, simplifyRoute(one))
        assertEquals(2, simplifyRoute(identical).size)
        // A negative tolerance is as meaningless as a zero one, and leaves the route alone.
        assertEquals(straight, simplifyRoute(straight, toleranceMetres = -1.0))
    }

    /** Thirty fixes 20 m apart; one of the two coordinate pairs wanders 50 m either side of the line. */
    private fun correctedZigzag(rawWanders: Boolean): List<TrackPointEntity> =
        (0 until 30).map { index ->
            val lat = index * 20.0 / metresPerDegree
            val wander = (if (index % 2 == 0) 50.0 else -50.0) / metresPerDegree
            fix(index * 10L, lat, if (rawWanders) wander else 0.0).copy(
                correctedLat = lat,
                correctedLon = if (rawWanders) 0.0 else wander,
            )
        }

    /**
     * The exported coordinates are correctedLat ?: lat, so simplification has to judge the same
     * line it is about to print. Judging the raw fixes and printing the corrected ones drops
     * exactly the points the corrected line needed, and keeps the ones it did not.
     */
    @Test
    fun simplificationThinsTheLineTheExportActuallyDraws() {
        val correctedIsStraight = simplifyRoute(correctedZigzag(rawWanders = true))
        val correctedWanders = simplifyRoute(correctedZigzag(rawWanders = false))

        assertEquals("kept ${correctedIsStraight.size}", 2, correctedIsStraight.size)
        assertTrue("kept ${correctedWanders.size} of 30", correctedWanders.size >= 25)
    }

    /**
     * An hour's walk at a fix a second is this many points, and the KDoc says the stack is explicit
     * precisely because a route recorded along a road is the shape that makes the recursive form
     * deep. A recursive form fails only on routes this long.
     */
    @Test
    fun tenThousandFixesAlongARoadSimplifyWithoutOverflowingTheStack() {
        val road = (0 until 10_000).map { index ->
            fix(index.toLong(), 55.75 + index * 0.00001, 37.62 + index * index * 1e-9)
        }

        val simplified = simplifyRoute(road)

        assertTrue("kept ${simplified.size} of ${road.size}", simplified.size in 2 until road.size)
        assertEquals(road.first(), simplified.first())
        assertEquals(road.last(), simplified.last())
        val times = simplified.map(TrackPointEntity::timestamp)
        assertEquals(times.sorted(), times)
    }

    private val liveTrack = TrackEntity(
        id = 7,
        startedAt = 1_754_000_000_000,
        distanceMeters = 5_120.0,
        elevationGainM = 42.0,
    )

    /** The bracketed positions inside the geometry, as text: the document is written out as text. */
    private fun positionsOf(geoJson: String): List<String> =
        Regex("\\[[-0-9., ]+]")
            .findAll(geoJson.substringAfter("\"coordinates\": ["))
            .map { it.value }
            .toList()

    /**
     * The export button sits on the screen of a track that is still recording, so an absent end
     * time is the ordinary state — and endedAt is the one optional line in a hand-written JSON
     * document, the line carrying the comma of the key before it.
     */
    @Test
    fun aTrackStillRecordingExportsWellFormedGeoJson() {
        val json = geoJsonOf(liveTrack, listOf(fix(1, 55.75, 37.61), fix(9, 55.76, 37.62)))

        assertFalse(json, json.contains("endedAt"))
        assertTrue(json, json.contains("\"distanceMeters\": 5120.0,"))
        // Nothing may be left dangling in front of a closing brace or bracket.
        assertFalse(json, Regex(",\\s*[}\\]]").containsMatchIn(json))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
        assertEquals(0, json.count { it == '"' } % 2)
    }

    /**
     * GpxTest pins the locale for gpxOf alone; geoJsonOf and csvOf carry their own formatters, so
     * each of the three files has to pin its own. A decimal comma turns the JSON into a syntax error
     * and shifts every CSV column one to the right.
     */
    @Test
    fun aGermanPhoneWritesNoDecimalCommaIntoTheJsonOrTheCsv() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val route = listOf(
                fix(1, 55.75, 37.61, altitudeM = 150.0),
                fix(9, 55.76, 37.62, altitudeM = 150.0),
            )
            val json = geoJsonOf(liveTrack, route)
            val csv = csvOf(route)

            assertTrue(json, json.contains("\"distanceMeters\": 5120.0"))
            assertTrue(json, json.contains("\"elevationGainM\": 42.0"))
            assertTrue(json, json.contains("[37.6100000, 55.7500000, 150.0]"))
            val rows = csv.trim().lines().drop(1)
            assertTrue(csv, rows.all { it.split(",").size == 13 })
        } finally {
            Locale.setDefault(original)
        }
    }

    /**
     * A cold start or a tunnel leaves one fix with no altitude while the fixes either side have
     * one. RFC 7946 asks a position for two or more elements with elevation an optional third, and
     * says nothing about siblings sharing an arity — so the gap is written as a flat position
     * rather than dropping the heights either side of it or inventing one to fill it.
     */
    @Test
    fun aFixWithNoAltitudeIsStillALegalPositionInTheLine() {
        val route = listOf(
            fix(1, 55.75, 37.61, altitudeM = 150.0),
            fix(5, 55.75, 37.62),
            fix(9, 55.76, 37.62, altitudeM = 151.0),
        )

        val json = geoJsonOf(liveTrack, route)

        val positions = positionsOf(json)
        assertTrue(json, json.contains("\"type\": \"LineString\""))
        assertEquals("the corner was thinned away: $positions", 3, positions.size)
        assertEquals("positions were $positions", listOf(3, 2, 3), positions.map { it.split(",").size })
    }

    /**
     * The filterReason column is the one most often empty, and the only one that ever holds text a
     * person wrote. RFC 4180 wraps a value that could otherwise end the field and doubles the quotes
     * inside it, and a comma is not the only such value.
     */
    @Test
    fun aCsvFieldThatCouldEndTheRowIsQuotedAndANullRowStillHasThirteenColumns() {
        val point = fix(1, 55.75, 37.61, altitudeM = 150.0)
        val quoted = csvOf(listOf(point.copy(filterReason = "he said \"8 m\"")))
        val wrapped = csvOf(listOf(point.copy(filterReason = "line one\nline two")))
        val bare = csvOf(listOf(fix(1, 55.75, 37.61).copy(accuracyM = null, speedMps = null)))

        assertTrue(quoted, quoted.contains("\"he said \"\"8 m\"\"\""))
        assertTrue(wrapped, wrapped.contains("\"line one\nline two\""))
        val row = bare.trim().lines().last()
        assertEquals("row was $row", 13, row.split(",").size)
        // The header promises thirteen columns for every fix: the reason is empty here, and the fix
        // starts no stretch after a pause.
        assertTrue(row, row.endsWith(",true,,false"))
    }

    /**
     * GPX 1.1 dates are UTC. This file's neighbours format dates through ZoneId.systemDefault(),
     * and a local formatter here would move every point of every exported track by the phone's
     * offset — silently changing which day a walk happened on for anyone far enough east.
     */
    @Test
    fun everyTimeInAGpxFileIsUtcWhateverZoneThePhoneIsIn() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
        try {
            val gpx = gpxOf(
                TrackEntity(id = 1, startedAt = 1_785_400_000_000L),
                listOf(fix(0, 55.75, 37.61, altitudeM = 150.0).copy(timestamp = 1_785_400_000_123L)),
            )

            // 1 785 400 000 s after the epoch is 08:26:40 on 30 July 2026 in UTC, and 22:26:40 the
            // same day on a phone set fourteen hours ahead of it.
            assertTrue(gpx, gpx.contains("<time>2026-07-30T08:26:40Z</time>"))
            assertTrue(gpx, gpx.contains("<name>2026-07-30T08:26:40Z</name>"))
            assertTrue(gpx, gpx.contains("<time>2026-07-30T08:26:40.123Z</time>"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /**
     * The barometer keeps reporting once it has started, so a fix whose receiver gave no altitude
     * still carries a pressure height — calibrated, here, against the mean-sea-level fix before it.
     * The note stays conservative anyway: altitudeMsl documents altitudeM, and a row without one
     * carries the column's default, so the file claims sea level only where the device said so.
     */
    @Test
    fun aBarometerOnlyFixLeavesTheDatumNoteMixed() {
        val route = listOf(
            fix(1, 55.75, 37.61, altitudeM = 150.0).copy(altitudeMsl = true, barometricAltitudeM = 150.0f),
            fix(5, 55.75, 37.62).copy(barometricAltitudeM = 151.5f),
            fix(9, 55.76, 37.62, altitudeM = 152.0).copy(altitudeMsl = true, barometricAltitudeM = 152.3f),
        )

        assertEquals(
            "ele: mean sea level where the device gave it, height above the WGS84 ellipsoid elsewhere",
            elevationDatumNote(route),
        )
    }
}
