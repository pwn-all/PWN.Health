package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round

/**
 * The other half of the route maths at its edges: the speed and elevation gates a fix is judged by,
 * the boundaries of the correlation filter's three constants, and the projection under the map.
 */
class RouteGeometryEdgeCasesExtraTest {
    /** Metres per degree of latitude on the sphere the route code uses. */
    private val metresPerDegree = 6_371_000.0 * PI / 180.0

    /**
     * Zero is a legitimate "standing still" Doppler measurement. Every gate in the correlation
     * filter is written as trustedRouteSpeedMps(...) ?: something-else, so a zero returned as null
     * silently swaps in a geometric fallback — and an elvis on a Double? is exactly the shape where
     * a well-meant takeIf { it > 0.0 } turns a real measurement into "unknown".
     */
    @Test
    fun aDopplerReadingOfZeroIsAReadingAndNonsenseIsNot() {
        assertEquals(0.0, trustedRouteSpeedMps(0.0, null)!!, 0.0)
        assertEquals(0.0, trustedRouteSpeedMps(0.0, 0.5)!!, 0.0)
        // Three metres a second of speed accuracy is the last value still stood behind.
        assertEquals(1.4, trustedRouteSpeedMps(1.4, 3.0)!!, 1e-9)
        assertNull(trustedRouteSpeedMps(1.4, 3.5))
        assertNull(trustedRouteSpeedMps(1.4, -1.0))
        // The KDoc says the range check disposes of NaN and both infinities on its own.
        assertNull(trustedRouteSpeedMps(1.4, Double.NaN))
        assertNull(trustedRouteSpeedMps(1.4, Double.POSITIVE_INFINITY))
        assertNull(trustedRouteSpeedMps(Double.NaN, 0.5))
        assertNull(trustedRouteSpeedMps(Double.POSITIVE_INFINITY, 0.5))
        assertNull(trustedRouteSpeedMps(-1.0, null))
        assertNull(trustedRouteSpeedMps(null, 0.5))
    }

    /** Three times the accuracy the provider stands behind, plus a margin, and never past six. */
    @Test
    fun theSpeedToleranceGrowsWithTheStatedAccuracyAndThenStops() {
        assertEquals(1.5, routeSpeedToleranceMps(0.0), 1e-9)
        assertEquals(5.7, routeSpeedToleranceMps(1.4), 1e-9)
        assertEquals(6.0, routeSpeedToleranceMps(1.5), 1e-9)
        assertEquals(6.0, routeSpeedToleranceMps(20.0), 1e-9)
        // A provider that says nothing is treated as one that claimed 1.5 m/s.
        assertEquals(routeSpeedToleranceMps(1.5), routeSpeedToleranceMps(null), 0.0)
    }

    /**
     * A NaN geometric speed is what a leg of no time at all produces and an infinity is what a
     * division by a zero interval hands over. Either reaching the maximum-speed card prints NaN
     * km/h across the whole track, and there is no way to clear it afterwards.
     */
    @Test
    fun aNonsenseSpeedNeverBecomesTheTracksSpeed() {
        assertEquals(2.0, conservativeRouteSpeedMps(2.0, 0.5, Double.NaN), 1e-9)
        assertEquals(2.0, conservativeRouteSpeedMps(2.0, 0.5, Double.POSITIVE_INFINITY), 1e-9)
        assertEquals(0.0, conservativeRouteSpeedMps(null, null, Double.NaN), 0.0)
        assertEquals(0.0, conservativeRouteSpeedMps(null, null, null), 0.0)
        // A Doppler reading the provider will not stand behind is no reading at all.
        assertEquals(0.0, conservativeRouteSpeedMps(3.0, 8.0, null), 0.0)
        assertEquals(2.0, conservativeRouteSpeedMps(-5.0, null, 2.0), 1e-9)
        // Standing still is a measurement, and the slower of the two sources is what counts.
        assertEquals(0.0, conservativeRouteSpeedMps(0.0, 0.5, 4.0), 0.0)
    }

    private fun counts(deltaM: Double, barometric: Boolean) =
        climbCounts(deltaM, barometric, verticalAccuracyM = null, movement = RouteMovement.WALKING)

    /**
     * A descent is what elevationLossM is made of, and abs() is the only thing making the two
     * symmetric, so a descent is held here beside a rise. The step is held at its 3 m threshold
     * itself, not a metre either side of it.
     */
    @Test
    fun aDescentCountsAndTheStepIsTheEdgeItself() {
        assertTrue(counts(3.0, barometric = false))
        assertTrue(counts(-3.0, barometric = false))
        assertFalse(counts(2.999, barometric = false))
        assertFalse(counts(-2.999, barometric = false))
        assertTrue(counts(1.5, barometric = true))
        assertTrue(counts(-1.5, barometric = true))
        assertFalse(counts(1.499, barometric = true))
        assertFalse(counts(-1.499, barometric = true))
        // The floor of docs/DESIGN.md §4.2 is three metres however good the receiver claims its altitude is.
        assertEquals(3.0, elevationStepThresholdM(barometric = false, verticalAccuracyM = 3.0f), 0.0)
        assertEquals(3.0, elevationStepThresholdM(barometric = false, verticalAccuracyM = 2.9f), 0.0)
        // A barometer's step does not move with a claim made about the GPS altitude.
        assertEquals(1.5, elevationStepThresholdM(barometric = true, verticalAccuracyM = 0.1f), 0.0)
    }

    private fun still(timestamp: Long) = RouteObservation(
        timestamp = timestamp,
        speedMps = 0.0,
        displacementM = 0.2,
        accuracyM = 5.0,
        motion = Motion.STILL,
        cadenceHz = 0.0,
        periodicity = 0.0,
    )

    /**
     * Fifteen seconds is the constant and "at least" is the comparison, so the stop is held on that
     * edge: either side of it is a second of standing booked as walking, at every traffic light of a
     * run.
     */
    @Test
    fun fifteenSecondsExactlyIsAConfirmedStop() {
        val nearly = RouteMovementClassifier()
        nearly.classify(still(0))

        assertEquals(RouteMovement.UNKNOWN, nearly.classify(still(14_999)))

        val confirmed = RouteMovementClassifier()
        confirmed.classify(still(0))

        assertEquals(RouteMovement.STOPPED, confirmed.classify(still(15_000)))
    }

    /**
     * A step restarts the clock a stop is counted from. stoppedSince is cleared in three separate
     * places — the running branch, the fast-speed branch and the fall-through — and one missing
     * reset confirms a stop straight across a pause at a lamp post, booking the walk after it as
     * standing time.
     */
    @Test
    fun oneStepRestartsTheClockAStopIsCountedFrom() {
        val classifier = RouteMovementClassifier()
        classifier.classify(still(0))
        val stepped = RouteObservation(
            timestamp = 6_000,
            speedMps = 1.2,
            displacementM = 4.0,
            accuracyM = 5.0,
            motion = Motion.WALKING,
            cadenceHz = 0.9,
            periodicity = 0.8,
        )

        assertEquals(RouteMovement.WALKING, classifier.classify(stepped))
        assertEquals(RouteMovement.UNKNOWN, classifier.classify(still(8_000)))
        // Twelve seconds after the clock restarted, not twenty after it first started.
        assertEquals(RouteMovement.UNKNOWN, classifier.classify(still(20_000)))
        assertEquals(RouteMovement.STOPPED, classifier.classify(still(23_000)))
    }

    /**
     * creditsDistance is a hand-written disjunction rather than a property of the enum, so the
     * compiler cannot check it exhaustively. A sixth class added later — CYCLING, say — defaults to
     * crediting nothing, and the route distance would silently stop counting those rides.
     */
    @Test
    fun distanceCreditsAndTheMovementCatalogueStayInStep() {
        val moving = setOf(RouteMovement.WALKING, RouteMovement.RUNNING, RouteMovement.VEHICLE)
        assertEquals(5, RouteMovement.entries.size)

        for (movement in RouteMovement.entries) {
            if (movement in moving) {
                assertTrue(movement.name, movement.creditsDistance)
            } else {
                assertFalse(movement.name, movement.creditsDistance)
            }
            val expected = when {
                movement == RouteMovement.STOPPED -> AnchorStep.DISCARD
                movement in moving -> AnchorStep.CREDIT
                else -> AnchorStep.HOLD
            }
            assertEquals(movement.name, expected, anchorStep(100.0, 5.0, movement))
            // Under the noise floor nothing is credited, whatever the class turned out to be.
            val quiet = if (movement == RouteMovement.STOPPED) AnchorStep.DISCARD else AnchorStep.HOLD
            assertEquals(movement.name, quiet, anchorStep(2.9, 5.0, movement))
        }
    }

    private fun fix(
        token: Long,
        seconds: Double,
        northMetres: Double,
        accuracyM: Double = 0.5,
        reportedSpeedMps: Double? = null,
        speedAccuracyMps: Double? = reportedSpeedMps?.let { 0.5 },
        motion: Motion = Motion.WALKING,
    ) = RouteFix(
        token = token,
        timestampNanos = (seconds * 1e9).toLong(),
        lat = 25.0 + northMetres / metresPerDegree,
        lon = 55.0,
        accuracyM = accuracyM,
        reportedSpeedMps = reportedSpeedMps,
        speedAccuracyMps = speedAccuracyMps,
        motion = motion,
    )

    /**
     * Under the noise floor a contradiction is not evidence: a still phone whose Doppler reads zero
     * still has its position wander a few metres, and holding every one of those behind a
     * look-ahead would put the live route a fix behind the walk. A jump of 10, 20 or 30 m says
     * nothing of the edge between noise and evidence, so the edge itself is held here.
     */
    @Test
    fun eightMetresIsWhereAFixStartsBeingArguedWith() {
        val quiet = RouteCorrelationFilter()
        quiet.offer(fix(1, 1.0, 0.0, motion = Motion.STILL, reportedSpeedMps = 0.0))
        val nearly = quiet
            .offer(fix(2, 2.0, 7.9, motion = Motion.STILL, reportedSpeedMps = 0.0))
            .single()

        assertEquals(RouteFixReason.PREDICTION_MATCH, nearly.reason)
        assertTrue(nearly.accepted)

        val judged = RouteCorrelationFilter()
        judged.offer(fix(1, 1.0, 0.0, motion = Motion.STILL, reportedSpeedMps = 0.0))
        val held = judged.offer(fix(2, 2.0, 8.0, motion = Motion.STILL, reportedSpeedMps = 0.0))

        assertTrue("resolved $held", held.isEmpty())
        val flushed = judged.flush().single()
        assertEquals(RouteFixReason.UNCONFIRMED_JUMP, flushed.reason)
        assertFalse(flushed.accepted)
    }

    /**
     * Seventy metres a second is 252 km/h — the ceiling is meant to be a train, and where it sits
     * decides whether a train journey keeps its route or loses every fix after the first. A speed of
     * 100 m/s, 43 % past the limit, would not notice the number being read as km/h, so the limit
     * itself is held here.
     */
    @Test
    fun aTrainIsPlausibleAndFourTimesAsFastIsNot() {
        val onTime = RouteCorrelationFilter()
        onTime.offer(fix(1, 1.0, 0.0, motion = Motion.VEHICLE, reportedSpeedMps = 69.99))
        val fast = onTime
            .offer(fix(2, 2.0, 69.99, motion = Motion.VEHICLE, reportedSpeedMps = 69.99))
            .single()

        assertEquals(RouteFixReason.PREDICTION_MATCH, fast.reason)
        assertTrue(fast.accepted)

        val impossible = RouteCorrelationFilter()
        impossible.offer(fix(1, 1.0, 0.0, motion = Motion.VEHICLE, reportedSpeedMps = 70.1))
        val rejected = impossible
            .offer(fix(2, 2.0, 70.1, motion = Motion.VEHICLE, reportedSpeedMps = 70.1))
            .single()

        assertEquals(RouteFixReason.IMPOSSIBLE_SPEED, rejected.reason)
        assertFalse(rejected.accepted)
    }

    /**
     * The radius a spike has to come back inside is a clamp of a clamp — max(4, min(15, the two
     * accuracies combined × 1.25)) — and the jump itself has to clear max(8, radius × 1.2). A spike
     * that comes back to half a metre of the anchor sits deep inside every one of those bounds and
     * pins none of the three constants, so each is held at its edge here.
     */
    @Test
    fun aSpikeIsOnlyASpikeWhileItComesBackInsideFourMetres() {
        val returned = RouteCorrelationFilter()
        returned.offer(fix(1, 1.0, 0.0, accuracyM = 2.0, motion = Motion.STILL))
        returned.offer(fix(2, 2.0, 10.0, accuracyM = 2.0, motion = Motion.STILL))
        val back = returned.offer(fix(3, 3.0, 4.0, accuracyM = 2.0, motion = Motion.STILL))

        assertEquals("resolved ${back.map { it.reason }}", 2, back.size)
        assertEquals(RouteFixReason.RETURN_SPIKE, back.first().reason)
        assertFalse(back.first().accepted)
        assertTrue(back.last().accepted)

        val elsewhere = RouteCorrelationFilter()
        elsewhere.offer(fix(1, 1.0, 0.0, accuracyM = 2.0, motion = Motion.STILL))
        elsewhere.offer(fix(2, 2.0, 10.0, accuracyM = 2.0, motion = Motion.STILL))
        val judged = elsewhere.offer(fix(3, 3.0, 4.5, accuracyM = 2.0, motion = Motion.STILL))

        // Half a metre further out the middle fix is judged as a continuation instead, and a
        // continuation that reverses direction is not one.
        assertEquals(RouteFixReason.UNCONFIRMED_JUMP, judged.first().reason)
        assertFalse(judged.first().accepted)
    }

    /**
     * StepService hands restore() the DAO's newest-first rows reversed into ascending order, and
     * restore() depends on that silently: it walks the list assigning previousAccepted and then
     * lastAccepted. Drop the .reversed() and the filter predicts backwards from the older fix, so
     * every fix after a restart contradicts a prediction and the drive is held apart.
     */
    @Test
    fun aFilterRestoredFromTheDatabaseCarriesTheDriveOnInsteadOfArguingWithIt() {
        val history = listOf(
            fix(1, 1.0, 0.0, motion = Motion.VEHICLE, reportedSpeedMps = 20.0),
            fix(2, 2.0, 20.0, motion = Motion.VEHICLE, reportedSpeedMps = 20.0),
        )
        val carriedOn = RouteCorrelationFilter().apply { restore(history) }
            .offer(fix(3, 3.0, 40.0, motion = Motion.VEHICLE, reportedSpeedMps = 20.0))

        assertEquals("resolved ${carriedOn.map { it.reason }}", 1, carriedOn.size)
        assertEquals(RouteFixReason.PREDICTION_MATCH, carriedOn.single().reason)
        assertTrue(carriedOn.single().accepted)

        // The last element of the restored list is the last fix, so one stamped before it is out
        // of order — which is only true if restore() read the list the way round it is given.
        val backwards = RouteCorrelationFilter().apply { restore(history) }
            .offer(fix(4, 1.5, 5.0, motion = Motion.VEHICLE, reportedSpeedMps = 20.0))

        assertEquals(RouteFixReason.NON_MONOTONIC_TIME, backwards.single().reason)
        assertFalse(backwards.single().accepted)
    }

    /**
     * Mercator stretches with latitude, so a hundred kilometres is a bigger slice of the map in
     * Murmansk than at the equator. The function inverts the projection with atan(sinh(...)) and
     * then takes a cosine — radians and degrees are one keystroke apart there — and it is only ever
     * exercised through the roam limit, where a factor of two looks like a generous margin.
     */
    @Test
    fun aHundredKilometresIsABiggerSliceOfTheMapFurtherNorth() {
        val equatorialCircumference = 40_075_016.686

        // One circumference is the whole width of the map at the equator, and twice the width of it
        // at 60°, where a degree east covers half the ground it does at the equator.
        assertEquals(1.0, mapUnitsFor(equatorialCircumference, mercatorY(0.0)), 1e-9)
        assertEquals(2.0, mapUnitsFor(equatorialCircumference, mercatorY(60.0)), 1e-6)
        assertEquals(2.0, mapUnitsFor(equatorialCircumference, mercatorY(-60.0)), 1e-6)
        assertEquals(0.0024953202, mapUnitsFor(100_000.0, 0.5), 1e-9)
        assertEquals(0.0, mapUnitsFor(0.0, 0.3), 0.0)
        // Past the edge of the projection behaves like the edge, because the y is coerced.
        assertEquals(mapUnitsFor(100_000.0, 1.0), mapUnitsFor(100_000.0, 2.0), 0.0)
        assertEquals(mapUnitsFor(100_000.0, 0.0), mapUnitsFor(100_000.0, -1.0), 0.0)
    }

    /**
     * The level chosen is the only thing between a phone and reading 3.7 million points for a view
     * of the whole world. A flipped comparison would load the finest level for the globe and the
     * coarsest for a street.
     */
    @Test
    fun theLevelChosenIsTheCoarsestWhoseErrorHidesUnderAPixel() {
        val map = WorldMap(
            open = { ByteArrayInputStream(ByteArray(0)) },
            units = intArrayOf(128, 512, 4_096),
            offsets = intArrayOf(1, 1, 1),
            lengths = intArrayOf(1, 1, 1),
        )

        assertEquals(3, map.levelCount)
        // Two units of error is exactly under a pixel that covers 2/128 of a degree.
        assertEquals(0, map.levelFor(2.0 / 128))
        assertEquals(1, map.levelFor(2.0 / 128 - 1e-9))
        // A street zoom past every level falls back to the finest rather than off the end.
        assertEquals(2, map.levelFor(1e-9))
        // Before the first layout degreesPerPixel is Double.MAX_VALUE: that asks for the globe.
        assertEquals(0, map.levelFor(Double.MAX_VALUE))
    }

    /**
     * The point of unwrapping is that it renames a longitude and never relocates it. Three values
     * near the antimeridian are not the whole of it; the invariant is what the projection actually
     * relies on, and a round() that ties the other way breaks only at the seam, where nobody looks.
     */
    @Test
    fun unwrappingMovesALongitudeByWholeTurnsAndNeverHalfATurnAway() {
        for (quarter in -4..4) {
            val reference = quarter * 45.0
            for (tick in -72..72) {
                val longitude = tick * 7.5
                val unwrapped = unwrapLongitude(longitude, reference)
                val turns = (unwrapped - longitude) / 360.0
                assertEquals("$longitude against $reference", round(turns), turns, 1e-9)
                assertTrue(
                    "$longitude against $reference became $unwrapped",
                    abs(unwrapped - reference) <= 180.0 + 1e-9,
                )
            }
        }
        // The half-turn itself is within half a turn of zero, whichever edge it is left on.
        assertTrue(abs(unwrapLongitude(180.0, 0.0)) <= 180.0)
        assertTrue(abs(unwrapLongitude(-180.0, 0.0)) <= 180.0)
    }

    private fun varint(value: Int, into: MutableList<Byte>) {
        var rest = value
        while (rest >= 0x80) {
            into.add(((rest and 0x7F) or 0x80).toByte())
            rest = rest ushr 7
        }
        into.add(rest.toByte())
    }

    /** A table of contents claiming [levels] levels while carrying [entries] of them. */
    private fun header(magic: String, levels: Int, entries: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        magic.forEach { bytes.add(it.code.toByte()) }
        varint(levels, bytes)
        repeat(entries) {
            varint(100, bytes)
            repeat(8) { bytes.add(0) }
        }
        return bytes.toByteArray()
    }

    /**
     * The asset ships inside the APK, so a broken one is a build mistake — and the KDoc says the
     * errors are thrown rather than repaired, because the alternative is a screen drawing garbage
     * lines out of whatever the truncated bytes happened to decode to. A parser fed truncated input
     * is exactly where a reader's own window and refill go wrong.
     */
    @Test
    fun aMapFileThatIsNotOneIsRefusedRatherThanHalfRead() {
        val wrongMagic = assertThrows(IllegalArgumentException::class.java) {
            parseWorldMap { ByteArrayInputStream(header("PWNMAP1", levels = 1, entries = 1)) }
        }
        assertEquals("not a world map", wrongMagic.message)

        assertThrows(EOFException::class.java) {
            parseWorldMap { ByteArrayInputStream(ByteArray(0)) }
        }
        assertThrows(EOFException::class.java) {
            parseWorldMap { ByteArrayInputStream(header("PWNMAP3", levels = 1, entries = 0)) }
        }
        val truncated = assertThrows(EOFException::class.java) {
            parseWorldMap { ByteArrayInputStream(header("PWNMAP3", levels = 2, entries = 1)) }
        }
        assertEquals("world map ended early", truncated.message)
    }
}
