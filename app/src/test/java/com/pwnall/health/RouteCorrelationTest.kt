package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCorrelationTest {
    private fun fix(
        token: Long,
        seconds: Double,
        northMeters: Double,
        accuracyM: Double = 2.0,
        reportedSpeedMps: Double? = null,
        speedAccuracyMps: Double? = reportedSpeedMps?.let { 0.5 },
        motion: Motion = Motion.WALKING,
    ) = RouteFix(
        token = token,
        timestampNanos = (seconds * 1e9).toLong(),
        lat = 25.0 + northMeters / 111_195.0,
        lon = 55.0,
        accuracyM = accuracyM,
        reportedSpeedMps = reportedSpeedMps,
        speedAccuracyMps = speedAccuracyMps,
        motion = motion,
    )

    private fun cityFix(
        token: Long,
        seconds: Double,
        lat: Double,
        lon: Double,
        accuracyM: Double = 8.0,
        reportedSpeedMps: Double? = null,
        speedAccuracyMps: Double? = reportedSpeedMps?.let { 0.5 },
        motion: Motion = Motion.WALKING,
    ) = RouteFix(
        token = token,
        timestampNanos = (seconds * 1e9).toLong(),
        lat = lat,
        lon = lon,
        accuracyM = accuracyM,
        reportedSpeedMps = reportedSpeedMps,
        speedAccuracyMps = speedAccuracyMps,
        motion = motion,
    )

    private fun kmhBetween(first: RouteFix, second: RouteFix): Double {
        val meanLat = Math.toRadians((first.lat + second.lat) / 2.0)
        val east = Math.toRadians(second.lon - first.lon) * Math.cos(meanLat) * 6_371_000.0
        val north = Math.toRadians(second.lat - first.lat) * 6_371_000.0
        val seconds = (second.timestampNanos - first.timestampNanos) / 1e9
        return Math.hypot(east, north) / seconds * 3.6
    }

    /**
     * Ten seconds of a real walk through a Tokyo street canyon, where
     * the reflected fixes drew a 94 km/h zigzag across the map and had the walk counted as a car.
     */
    @Test
    fun `a multipath zigzag never confirms itself as vehicle movement`() {
        val filter = RouteCorrelationFilter()
        val walk = listOf(
            Triple(0.0, 35.6933289, 139.7003701),
            Triple(43.555, 35.6933594, 139.6995811),
            Triple(45.703, 35.6935997, 139.6995468),
            Triple(47.705, 35.6936579, 139.7000188),
            Triple(49.717, 35.6936035, 139.6994400),
            Triple(51.785, 35.6934738, 139.6993256),
            Triple(53.963, 35.6935215, 139.6992683),
        )
        // Match each exported leg, including the provider echoing the 94 km/h positional spike.
        // Direction and positive gait evidence must still keep that self-consistent error out.
        val reportedSpeeds = listOf(null, 1.64, 12.52, 21.53, 26.14, 8.55, 3.40)

        val resolved = walk.flatMapIndexed { index, (seconds, lat, lon) ->
            filter.offer(
                cityFix(
                    token = index + 1L,
                    seconds = seconds,
                    lat = lat,
                    lon = lon,
                    reportedSpeedMps = reportedSpeeds[index],
                ),
            )
        } + filter.flush()

        val accepted = resolved.filter(RouteFixResolution::accepted).map { it.fix }
        val spike = resolved.single { it.fix.token == 4L }
        assertEquals(false, spike.accepted)
        val fastestLeg = accepted.zipWithNext(::kmhBetween).max()
        assertTrue("fastest accepted leg was $fastestLeg km/h", fastestLeg < 15.0)
    }

    @Test
    fun `doppler still confirms a genuine vehicle above the unsupported speed limit`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 1.0, 0.0))

        assertTrue(
            filter.offer(
                fix(2, 2.0, 20.0, reportedSpeedMps = 20.0, motion = Motion.VEHICLE),
            ).isEmpty(),
        )
        val resolved = filter.offer(
            fix(3, 3.0, 40.0, reportedSpeedMps = 20.0, motion = Motion.VEHICLE),
        )

        assertEquals(listOf(2L, 3L), resolved.map { it.fix.token })
        assertTrue(resolved.all(RouteFixResolution::accepted))
    }

    @Test
    fun `poor speed accuracy cannot prove a vehicle jump`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 1.0, 0.0, motion = Motion.VEHICLE))

        val offered = filter.offer(
            fix(
                token = 2,
                seconds = 2.0,
                northMeters = 20.0,
                reportedSpeedMps = 0.0,
                speedAccuracyMps = 20.0,
                motion = Motion.VEHICLE,
            ),
        )

        assertTrue(offered.isEmpty())
        val rejected = filter.flush().single()
        assertEquals(false, rejected.accepted)
        assertEquals(RouteFixReason.UNCONFIRMED_JUMP, rejected.reason)
    }

    @Test
    fun `two straight fast fixes cannot override walking gait`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 1.0, 0.0))
        assertTrue(filter.offer(fix(2, 2.0, 20.0, reportedSpeedMps = 20.0)).isEmpty())

        val resolved = filter.offer(fix(3, 3.0, 40.0, reportedSpeedMps = 20.0)) +
            filter.flush()

        assertTrue(resolved.none(RouteFixResolution::accepted))
        assertEquals(listOf(2L, 3L), resolved.map { it.fix.token })
    }

    @Test
    fun `normal walking fixes are accepted immediately`() {
        val filter = RouteCorrelationFilter()
        assertTrue(filter.offer(fix(1, 1.0, 0.0)).single().accepted)

        val next = filter.offer(fix(2, 2.0, 1.5, reportedSpeedMps = 1.5))
        assertTrue(next.single().accepted)
        assertEquals(RouteFixReason.PREDICTION_MATCH, next.single().reason)
    }

    @Test
    fun `ten metre jump and immediate return rejects the middle fix`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 1.0, 0.0))

        assertTrue(filter.offer(fix(2, 2.0, 10.0, reportedSpeedMps = 1.2)).isEmpty())
        val resolved = filter.offer(fix(3, 3.0, 0.5, reportedSpeedMps = 0.5))

        assertEquals(2, resolved.size)
        assertEquals(2L, resolved[0].fix.token)
        assertEquals(RouteFixReason.RETURN_SPIKE, resolved[0].reason)
        assertEquals(false, resolved[0].accepted)
        assertEquals(3L, resolved[1].fix.token)
        assertTrue(resolved[1].accepted)
    }

    @Test
    fun `continued fast displacement confirms a real vehicle path`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 1.0, 0.0))

        assertTrue(
            filter.offer(
                fix(2, 2.0, 10.0, reportedSpeedMps = 1.0, motion = Motion.WALKING),
            ).isEmpty(),
        )
        val resolved = filter.offer(
            fix(3, 3.0, 20.0, reportedSpeedMps = 10.0, motion = Motion.VEHICLE),
        )

        assertEquals(listOf(2L, 3L), resolved.map { it.fix.token })
        assertTrue(resolved.all(RouteFixResolution::accepted))
        assertTrue(resolved.all { it.reason == RouteFixReason.CONFIRMED_BY_NEXT })
    }

    @Test
    fun `persistent offset without forward progress never confirms a jump`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 1.0, 0.0))

        assertTrue(
            filter.offer(
                fix(2, 2.0, 20.0, reportedSpeedMps = 0.0, motion = Motion.STILL),
            ).isEmpty(),
        )

        val secondOffset = filter.offer(
            fix(3, 3.0, 20.0, reportedSpeedMps = 0.0, motion = Motion.STILL),
        )
        assertEquals(listOf(2L), secondOffset.map { it.fix.token })
        assertTrue(secondOffset.none(RouteFixResolution::accepted))

        val thirdOffset = filter.offer(
            fix(4, 4.0, 20.0, reportedSpeedMps = 0.0, motion = Motion.STILL),
        )
        assertEquals(listOf(3L), thirdOffset.map { it.fix.token })
        assertTrue(thirdOffset.none(RouteFixResolution::accepted))

        val finalOffset = filter.flush().single()
        assertEquals(4L, finalOffset.fix.token)
        assertEquals(false, finalOffset.accepted)
        assertEquals(RouteFixReason.UNCONFIRMED_JUMP, finalOffset.reason)
    }

    @Test
    fun `slow consecutive increments eventually confirm cumulative progress`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 1.0, 0.0))

        assertTrue(
            filter.offer(
                fix(2, 2.0, 10.0, reportedSpeedMps = 0.75),
            ).isEmpty(),
        )

        val positions = listOf(10.75, 11.5, 12.25, 13.0, 13.75)
        val resolutions = positions.mapIndexed { index, position ->
            filter.offer(
                fix(
                    token = index + 3L,
                    seconds = index + 3.0,
                    northMeters = position,
                    reportedSpeedMps = 0.75,
                ),
            )
        }

        assertTrue(resolutions.dropLast(1).flatten().none(RouteFixResolution::accepted))
        assertEquals(listOf(6L, 7L), resolutions.last().map { it.fix.token })
        assertTrue(resolutions.last().all(RouteFixResolution::accepted))
    }

    /**
     * A walk recorded straight through a metro ride: two fixes, four minutes without one, then the
     * walk again somewhere else. If only an accepted fix moved the anchor, every fix after the ride
     * would be measured from the station left behind — too fast to have been walked from there, never
     * a continuation of the jump — and all 150 of these would be refused: ten minutes of a walk would
     * go missing after 2.5 km. The three rides are the three ways a fix is refused: held and argued
     * with; rolled forward as a cluster, since a 2.8 m step is under the progress a 5 m fix has to
     * show; and impossible outright.
     */
    @Test
    fun `a walk is recorded again half a minute after a stretch the phone did not see`() {
        val rides = listOf(2_500.0 to 2.0, 2_500.0 to 5.0, 30_000.0 to 2.0)

        for ((rideMeters, accuracyM) in rides) {
            val filter = RouteCorrelationFilter()
            filter.offer(fix(1, 0.0, 0.0, reportedSpeedMps = 1.4))
            filter.offer(fix(2, 2.0, 2.8, reportedSpeedMps = 1.4))

            // A fix every two seconds, walking back the way the ride came rather than on along it.
            val accepted = (0 until 150)
                .flatMap { step ->
                    val walked = fix(3L + step, 242.0 + 2.0 * step, rideMeters - 2.8 * step, accuracyM, 1.4)
                    filter.offer(walked)
                }
                .filter(RouteFixResolution::accepted)

            // The fifteenth fix after the ride starts the route again, and the walk runs on from it.
            val ride = "$rideMeters m ride, fixes to $accuracyM m"
            assertEquals(ride, (17L..152L).toList(), accepted.map { it.fix.token })
            assertEquals(ride, RouteFixReason.REANCHORED, accepted.first().reason)
            assertTrue(ride, accepted.drop(1).all { it.reason == RouteFixReason.PREDICTION_MATCH })
        }
    }

    /**
     * The same ride, and the walk after it sets off at a right angle to it: east along a street,
     * where the stop the phone last saw lies 2.5 km to the south. No fix after the ride carries the
     * jump on, so none can confirm it; the anchor has to be given up, and is, at the fifteenth.
     */
    @Test
    fun `a walk setting off across the line of the ride is recorded again after it`() {
        val metresPerDegree = 111_195.0
        val north = 2_500.0
        val lat = 25.0 + north / metresPerDegree
        val metresPerDegreeEast = metresPerDegree * Math.cos(Math.toRadians(lat))

        for (accuracyM in listOf(2.0, 8.0)) {
            val filter = RouteCorrelationFilter()
            filter.offer(cityFix(1, 0.0, 25.0, 55.0, accuracyM, reportedSpeedMps = 1.4))
            filter.offer(cityFix(2, 2.0, 25.0 + 2.8 / metresPerDegree, 55.0, accuracyM, reportedSpeedMps = 1.4))

            val accepted = (0 until 150)
                .flatMap { step ->
                    val east = 55.0 + 2.8 * step / metresPerDegreeEast
                    filter.offer(cityFix(3L + step, 242.0 + 2.0 * step, lat, east, accuracyM, reportedSpeedMps = 1.4))
                }
                .filter(RouteFixResolution::accepted)

            val walk = "fixes to $accuracyM m"
            assertEquals(walk, (17L..152L).toList(), accepted.map { it.fix.token })
            assertEquals(walk, RouteFixReason.REANCHORED, accepted.first().reason)
            assertTrue(walk, accepted.drop(1).all { it.reason == RouteFixReason.PREDICTION_MATCH })
        }
    }

    /**
     * Fifteen refusals alone are not a stale anchor: at a fix a second they take a quarter of a
     * minute, and noise can argue with a walk for that long. Here the fixes land 2.5 km away with no
     * gap before them, and the anchor stands until it is a minute old.
     */
    @Test
    fun `an anchor is not given up inside its first minute however many fixes argue with it`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 0.0, 0.0, reportedSpeedMps = 1.4))
        filter.offer(fix(2, 2.0, 2.8, reportedSpeedMps = 1.4))

        val resolved = (3L..70L).flatMap { second ->
            filter.offer(fix(second, second.toDouble(), 2_500.0, reportedSpeedMps = 0.0, motion = Motion.STILL))
        }

        val firstAccepted = resolved.firstOrNull(RouteFixResolution::accepted)
        assertEquals(62L, firstAccepted?.fix?.token)
        assertEquals(RouteFixReason.REANCHORED, firstAccepted?.reason)
    }

    /**
     * A stale anchor is not a persistent offset, and that one still never confirms itself: three
     * minutes of fixes 30 m off the anchor are an offset, not a place the walker went. Thirty metres
     * is a walk of seconds, so nothing says the anchor is out of date, and a reflection lasts as long
     * as the wait beside the wall that makes it.
     */
    @Test
    fun `an offset within a walk of the anchor stays out of the route however long it lasts`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 0.0, 0.0))

        val resolved = (1L..90L).flatMap { step ->
            filter.offer(fix(1 + step, 2.0 * step, 30.0, reportedSpeedMps = 0.0, motion = Motion.STILL))
        } + filter.flush()

        assertEquals(90, resolved.size)
        assertTrue(resolved.none(RouteFixResolution::accepted))
    }

    /**
     * One refused fix before a tunnel does not make the first fix out of it proof of a stale anchor:
     * it is the second refusal, not the fifteenth, however old the anchor is by then. The drive is
     * held and confirmed by the next fix, as any held jump is, and the line runs through the tunnel.
     */
    @Test
    fun `one spike before a tunnel leaves the drive out of it to be confirmed by the next fix`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 0.0, 0.0, reportedSpeedMps = 1.4))
        filter.offer(fix(2, 2.0, 2.8, reportedSpeedMps = 1.4))
        assertTrue(filter.offer(fix(3, 4.0, 40.0, reportedSpeedMps = 1.4)).isEmpty())

        val out = filter.offer(fix(4, 64.0, 900.0, reportedSpeedMps = 15.0, motion = Motion.VEHICLE))
        val onward = filter.offer(fix(5, 66.0, 930.0, reportedSpeedMps = 15.0, motion = Motion.VEHICLE))

        assertEquals(listOf(3L), out.map { it.fix.token })
        assertTrue(out.none(RouteFixResolution::accepted))
        assertEquals(listOf(4L, 5L), onward.map { it.fix.token })
        assertTrue(onward.all { it.reason == RouteFixReason.CONFIRMED_BY_NEXT })
    }

    @Test
    fun `geometrically impossible speed is rejected immediately`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 1.0, 0.0))

        val result = filter.offer(fix(2, 2.0, 100.0, motion = Motion.VEHICLE)).single()
        assertEquals(false, result.accepted)
        assertEquals(RouteFixReason.IMPOSSIBLE_SPEED, result.reason)
    }

    /**
     * Two metres of pavement on Taveuni, where longitude runs from 179.99999 to -179.99999. Read
     * raw, that step is a trip round the world; the fix would be rejected as impossible and, since
     * a rejection does not advance the anchor, so would every fix of the minute after it.
     */
    @Test
    fun `a walk across the antimeridian is not an impossible jump`() {
        val filter = RouteCorrelationFilter()
        assertTrue(filter.offer(cityFix(1, 1.0, 25.0, 179.99999)).single().accepted)

        val crossing = filter.offer(cityFix(2, 2.0, 25.0, -179.99999, reportedSpeedMps = 1.4))
        val after = filter.offer(cityFix(3, 3.0, 25.0, -179.99997, reportedSpeedMps = 1.4))

        assertTrue(crossing.single().accepted)
        assertTrue(after.single().accepted)
    }

    @Test
    fun `unconfirmed final jump is retained as rejected on flush`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 1.0, 0.0))
        assertTrue(filter.offer(fix(2, 2.0, 10.0, reportedSpeedMps = 1.0)).isEmpty())

        val result = filter.flush().single()
        assertEquals(false, result.accepted)
        assertEquals(RouteFixReason.UNCONFIRMED_JUMP, result.reason)
    }

    @Test
    fun `non monotonic point cannot alter the route`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 2.0, 0.0))

        val result = filter.offer(fix(2, 1.0, 1.0)).single()
        assertEquals(false, result.accepted)
        assertEquals(RouteFixReason.NON_MONOTONIC_TIME, result.reason)
    }

    /**
     * The service keys the samples it holds for this filter by [RouteFix.token]. Keyed by the fix's
     * time, a fix redelivered with the same elapsedRealtimeNanos would overwrite the sample held under
     * it, and when both came back here the second would find nothing to persist and its raw row would
     * be lost — so the token is a counter, and the two resolutions come back under their own.
     *
     * The copy says nothing about where the held fix led, so it is refused alone and the held one
     * waits for the next fix to judge it. Refused with it, the held fix would be lost to a duplicate,
     * and a real start of a ride with it.
     */
    @Test
    fun `a fix redelivered at the same instant is refused under its own token and the held one waits`() {
        val filter = RouteCorrelationFilter()
        filter.offer(fix(1, 1.0, 0.0))
        // A jump big enough to be held back for the next fix to judge.
        assertTrue(filter.offer(fix(2, 2.0, 30.0)).isEmpty())

        val redelivered = filter.offer(fix(3, 2.0, 30.0))

        assertEquals(listOf(3L), redelivered.map { it.fix.token })
        assertEquals(RouteFixReason.NON_MONOTONIC_TIME, redelivered.single().reason)
        assertEquals(false, redelivered.single().accepted)
        assertEquals(2L, filter.offer(fix(4, 3.0, 60.0)).first().fix.token)
    }
}
