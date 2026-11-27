package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMotionTest {
    private fun observation(
        timestamp: Long,
        speedKmh: Double,
        motion: Motion = Motion.WALKING,
        cadenceHz: Double = 1.8,
        periodicity: Double = 0.8,
        displacementM: Double = 4.0,
        stepsCounted: Boolean = false,
        std: Double = 0.0,
    ) = RouteObservation(
        timestamp = timestamp,
        speedMps = speedKmh / 3.6,
        displacementM = displacementM,
        accuracyM = 5.0,
        motion = motion,
        cadenceHz = cadenceHz,
        periodicity = periodicity,
        stepsCounted = stepsCounted,
        std = std,
    )

    @Test
    fun `single GPS speed spike is not labeled walking or vehicle`() {
        val classifier = RouteMovementClassifier()
        classifier.classify(observation(0, 4.5))
        classifier.classify(observation(2_000, 4.8))

        assertEquals(
            RouteMovement.UNKNOWN,
            classifier.classify(observation(4_000, 20.0, motion = Motion.VEHICLE)),
        )
    }

    @Test
    fun `single speed spike at track start is neither vehicle nor maximum`() {
        val classifier = RouteMovementClassifier()

        assertEquals(
            RouteMovement.UNKNOWN,
            classifier.classify(observation(0, 94.0, motion = Motion.VEHICLE)),
        )
        assertEquals(0.0, classifier.medianSpeedMps, 0.0)

        assertEquals(
            RouteMovement.WALKING,
            classifier.classify(observation(2_000, 5.0, motion = Motion.WALKING)),
        )
        assertEquals(5.0 / 3.6, classifier.medianSpeedMps, 0.0001)
    }

    @Test
    fun `movement speed requires both coordinate and Doppler evidence`() {
        assertEquals(
            5.0 / 3.6,
            conservativeRouteSpeedMps(
                reportedSpeedMps = 94.0 / 3.6,
                speedAccuracyMps = 0.5,
                geometricSpeedMps = 5.0 / 3.6,
            ),
            0.0001,
        )
        assertEquals(
            5.0 / 3.6,
            conservativeRouteSpeedMps(
                reportedSpeedMps = 5.0 / 3.6,
                speedAccuracyMps = 0.5,
                geometricSpeedMps = 94.0 / 3.6,
            ),
            0.0001,
        )
    }

    @Test
    fun `uncertain Doppler speed falls back to accepted coordinates`() {
        assertEquals(
            5.0 / 3.6,
            conservativeRouteSpeedMps(
                reportedSpeedMps = 94.0 / 3.6,
                speedAccuracyMps = 8.0,
                geometricSpeedMps = 5.0 / 3.6,
            ),
            0.0001,
        )
    }

    @Test
    fun `sustained speed above seven without running gait is vehicle`() {
        val classifier = RouteMovementClassifier()
        classifier.classify(observation(0, 12.0, motion = Motion.VEHICLE))

        assertEquals(
            RouteMovement.VEHICLE,
            classifier.classify(observation(2_000, 13.0, motion = Motion.VEHICLE)),
        )
    }

    @Test
    fun `speed above seven with a periodic running interval is running`() {
        val classifier = RouteMovementClassifier()

        assertEquals(
            RouteMovement.RUNNING,
            classifier.classify(
                observation(
                    timestamp = 8_000,
                    speedKmh = 12.0,
                    motion = Motion.RUNNING,
                    cadenceHz = 2.8,
                    periodicity = 0.75,
                ),
            ),
        )
    }

    @Test
    fun `sustained fast pocket stride rhythm is running rather than vehicle`() {
        val classifier = RouteMovementClassifier()

        assertEquals(
            RouteMovement.UNKNOWN,
            classifier.classify(
                observation(
                    timestamp = 0,
                    speedKmh = 12.0,
                    motion = Motion.WALKING,
                    cadenceHz = 1.4,
                    periodicity = 0.75,
                ),
            ),
        )
        assertEquals(
            RouteMovement.RUNNING,
            classifier.classify(
                observation(
                    timestamp = 2_000,
                    speedKmh = 12.5,
                    motion = Motion.WALKING,
                    cadenceHz = 1.4,
                    periodicity = 0.75,
                ),
            ),
        )
    }

    @Test
    fun `a slow jog between the running cadences is running rather than a car`() {
        // 140 steps a minute: 2.33 Hz in the hand, 1.17 Hz from a pocket. Neither is a running
        // cadence, nor double one, and read by cadence alone recorded 8.5 km/h jogs would come back
        // as a car.
        for (cadence in listOf(2.33, 1.17)) {
            val classifier = RouteMovementClassifier()
            classifier.classify(observation(0, 8.5, Motion.WALKING, cadenceHz = cadence))

            assertEquals(
                "cadence $cadence",
                RouteMovement.RUNNING,
                classifier.classify(observation(2_000, 8.8, Motion.WALKING, cadenceHz = cadence)),
            )
        }
    }

    @Test
    fun `pedometer steps carry a run whose accelerometer window is missing`() {
        val counted = RouteMovementClassifier()
        counted.classify(observation(0, 9.0, Motion.UNKNOWN, stepsCounted = true))
        assertEquals(
            RouteMovement.RUNNING,
            counted.classify(observation(2_000, 9.0, Motion.UNKNOWN, stepsCounted = true)),
        )

        val silent = RouteMovementClassifier()
        silent.classify(observation(0, 9.0, Motion.UNKNOWN))
        assertEquals(RouteMovement.VEHICLE, silent.classify(observation(2_000, 9.0, Motion.UNKNOWN)))
    }

    @Test
    fun `a run too hard for the walk ceiling is running while the pedometer counts`() {
        // 8–13 km/h with the gate reading NON_GAIT or SHAKING would otherwise go down as a car.
        for (motion in listOf(Motion.NON_GAIT, Motion.SHAKING)) {
            val counted = RouteMovementClassifier()
            counted.classify(observation(0, 9.5, motion, periodicity = 0.2, stepsCounted = true, std = 11.0))
            assertEquals(
                "$motion",
                RouteMovement.RUNNING,
                counted.classify(observation(2_000, 10.0, motion, periodicity = 0.2, stepsCounted = true, std = 13.0)),
            )

            val silent = RouteMovementClassifier()
            silent.classify(observation(0, 9.5, motion, std = 11.0))
            assertEquals(RouteMovement.VEHICLE, silent.classify(observation(2_000, 10.0, motion, std = 11.0)))
        }

        // A bicycle's smooth NON_GAIT stays a ride even when the pedometer miscounts the pedals.
        val bike = RouteMovementClassifier()
        bike.classify(observation(0, 15.0, Motion.NON_GAIT, stepsCounted = true, std = 1.2))
        assertEquals(
            RouteMovement.VEHICLE,
            bike.classify(observation(2_000, 15.0, Motion.NON_GAIT, stepsCounted = true, std = 1.2)),
        )
    }

    @Test
    fun `a stride at car speed is a passenger`() {
        val classifier = RouteMovementClassifier()
        classifier.classify(observation(0, 40.0, Motion.WALKING, stepsCounted = true))

        assertEquals(
            RouteMovement.VEHICLE,
            classifier.classify(observation(2_000, 42.0, Motion.WALKING, stepsCounted = true)),
        )
    }

    @Test
    fun `one fast fix does not turn a walking stride harmonic into running`() {
        val classifier = RouteMovementClassifier()
        classifier.classify(
            observation(
                timestamp = 0,
                speedKmh = 5.0,
                motion = Motion.WALKING,
                cadenceHz = 1.4,
                periodicity = 0.75,
            ),
        )
        classifier.classify(
            observation(
                timestamp = 2_000,
                speedKmh = 5.0,
                motion = Motion.WALKING,
                cadenceHz = 1.4,
                periodicity = 0.75,
            ),
        )

        assertEquals(
            RouteMovement.UNKNOWN,
            classifier.classify(
                observation(
                    timestamp = 4_000,
                    speedKmh = 12.0,
                    motion = Motion.WALKING,
                    cadenceHz = 1.4,
                    periodicity = 0.75,
                ),
            ),
        )
    }

    @Test
    fun `fast cadence without periodicity does not prove running`() {
        val classifier = RouteMovementClassifier()
        classifier.classify(
            observation(0, 12.0, Motion.RUNNING, cadenceHz = 2.8, periodicity = 0.1),
        )

        assertEquals(
            RouteMovement.VEHICLE,
            classifier.classify(
                observation(2_000, 12.0, Motion.RUNNING, cadenceHz = 2.8, periodicity = 0.1),
            ),
        )
    }

    @Test
    fun `walking pace with unavailable motion evidence still counts as walking`() {
        val classifier = RouteMovementClassifier()

        assertEquals(
            RouteMovement.WALKING,
            classifier.classify(observation(0, 5.0, motion = Motion.UNKNOWN, periodicity = 0.1)),
        )
    }

    @Test
    fun `walking GPS pace cannot override positive non gait evidence`() {
        val classifier = RouteMovementClassifier()

        assertEquals(
            RouteMovement.UNKNOWN,
            classifier.classify(observation(0, 5.0, motion = Motion.NON_GAIT, periodicity = 0.1)),
        )
    }

    @Test
    fun `a phone lying still while the world moves is still a vehicle`() {
        val classifier = RouteMovementClassifier()
        classifier.classify(observation(0, 5.0, motion = Motion.STILL))

        assertEquals(
            RouteMovement.VEHICLE,
            classifier.classify(observation(2_000, 5.0, motion = Motion.STILL)),
        )
    }

    @Test
    fun `stop is confirmed only after fifteen seconds`() {
        val classifier = RouteMovementClassifier()
        assertEquals(
            RouteMovement.UNKNOWN,
            classifier.classify(
                observation(0, 0.0, Motion.STILL, displacementM = 0.2),
            ),
        )
        assertEquals(
            RouteMovement.STOPPED,
            classifier.classify(
                observation(16_000, 0.0, Motion.STILL, displacementM = 0.2),
            ),
        )
    }

    @Test
    fun `a walk under two kilometres an hour is not a stop while the pedometer counts`() {
        // A steep climb: 1.6 km/h and under a metre a fix, for forty seconds.
        fun climb(motion: Motion, stepsCounted: Boolean): List<RouteMovement> {
            val classifier = RouteMovementClassifier()
            return (0 until 20).map {
                classifier.classify(
                    observation(it * 2_000L, 1.6, motion, displacementM = 0.9, stepsCounted = stepsCounted),
                )
            }
        }

        val walked = climb(Motion.WALKING, stepsCounted = true)
        assertTrue("$walked", walked.all { it == RouteMovement.WALKING })
        // Screen off and the accelerometer window missing: the pedometer carries it alone.
        val pocketed = climb(Motion.UNKNOWN, stepsCounted = true)
        assertTrue("$pocketed", pocketed.all { it == RouteMovement.WALKING })

        // The same crawl with no steps counted is a receiver drifting, and so is one counted on a
        // phone the accelerometer says is lying still or being moved without a stride.
        assertEquals(RouteMovement.STOPPED, climb(Motion.WALKING, stepsCounted = false).last())
        assertEquals(RouteMovement.STOPPED, climb(Motion.STILL, stepsCounted = true).last())
        assertEquals(RouteMovement.STOPPED, climb(Motion.NON_GAIT, stepsCounted = true).last())
    }

    @Test
    fun `a stop begins when the counted stride ends`() {
        val classifier = RouteMovementClassifier()
        // Twenty seconds of slow walking do not start the clock a stop is confirmed on.
        for (second in 0L..20L step 2) {
            classifier.classify(observation(second * 1_000, 1.6, displacementM = 0.9, stepsCounted = true))
        }

        assertEquals(
            RouteMovement.UNKNOWN,
            classifier.classify(observation(22_000, 0.0, Motion.STILL, displacementM = 0.2)),
        )
        assertEquals(
            RouteMovement.STOPPED,
            classifier.classify(observation(37_000, 0.0, Motion.STILL, displacementM = 0.2)),
        )
    }

    /**
     * A barometer earns a metre-and-a-half step; a bare receiver earns its own stated vertical
     * accuracy and never less than three metres: given the barometer's step, a phone without a
     * pressure sensor would book every 1.5 m wander of the GPS altitude as climb, hundreds of metres
     * of it on a flat hour's walk.
     */
    @Test
    fun theElevationStepFollowsTheInstrument() {
        assertEquals(1.5, elevationStepThresholdM(barometric = true, verticalAccuracyM = 12f), 0.0)
        assertEquals(3.0, elevationStepThresholdM(barometric = false, verticalAccuracyM = null), 0.0)
        assertEquals(3.0, elevationStepThresholdM(barometric = false, verticalAccuracyM = 1f), 0.0)
        assertEquals(4.5, elevationStepThresholdM(barometric = false, verticalAccuracyM = 4.5f), 0.0)
        // Standing still climbs nothing, whatever the receiver says the altitude did.
        assertTrue(climbCounts(5.0, barometric = false, verticalAccuracyM = null, movement = RouteMovement.WALKING))
        assertFalse(climbCounts(5.0, barometric = false, verticalAccuracyM = null, movement = RouteMovement.STOPPED))
        assertFalse(climbCounts(2.0, barometric = false, verticalAccuracyM = null, movement = RouteMovement.WALKING))
        assertTrue(climbCounts(2.0, barometric = true, verticalAccuracyM = null, movement = RouteMovement.WALKING))
    }

    @Test
    fun `an unclassified fix holds the anchor instead of losing its metres`() {
        // The first fast fix of a walk becoming a run is UNKNOWN: the median has not caught up yet.
        // Moving the anchor there would credit nothing and measure the next fix from the wrong place.
        assertEquals(AnchorStep.HOLD, anchorStep(6.0, 5.0, RouteMovement.UNKNOWN))
        // The next fix, classified, credits the whole segment from the anchor it held.
        assertEquals(AnchorStep.CREDIT, anchorStep(12.0, 5.0, RouteMovement.RUNNING))
    }

    @Test
    fun `noise under the threshold holds and a confirmed stop discards`() {
        assertEquals(AnchorStep.HOLD, anchorStep(2.9, 5.0, RouteMovement.WALKING))
        assertEquals(AnchorStep.CREDIT, anchorStep(3.0, 5.0, RouteMovement.WALKING))
        // Half the accuracy when that is the larger: a 20 m fix has to move 10 m.
        assertEquals(AnchorStep.HOLD, anchorStep(9.0, 20.0, RouteMovement.VEHICLE))
        assertEquals(AnchorStep.CREDIT, anchorStep(10.0, 20.0, RouteMovement.VEHICLE))
        // Standing still moves the anchor whatever the drift, so drift never adds up to distance.
        assertEquals(AnchorStep.DISCARD, anchorStep(1.0, 5.0, RouteMovement.STOPPED))
        assertEquals(AnchorStep.DISCARD, anchorStep(40.0, 5.0, RouteMovement.STOPPED))
    }

    @Test
    fun `the speed baseline moves on every fix past the noise floor, whatever the anchor does`() {
        // A slow ride the gait gate calls NON_GAIT is UNKNOWN fix after fix, so the distance anchor
        // holds through all of it; the speed baseline must not, or the next fix's speed is the chord
        // since the hold began over all the time since.
        assertEquals(AnchorStep.HOLD, anchorStep(200.0, 5.0, RouteMovement.UNKNOWN))
        assertTrue(speedAnchorMoves(200.0, 5.0, RouteMovement.UNKNOWN))
        assertTrue(speedAnchorMoves(11.0, 5.0, RouteMovement.UNKNOWN))
        // Under the floor it stays, so a standing receiver's jitter is not a speed: 3 m, or half the
        // stated accuracy when that is more.
        assertFalse(speedAnchorMoves(2.9, 5.0, RouteMovement.WALKING))
        assertTrue(speedAnchorMoves(3.0, 5.0, RouteMovement.WALKING))
        assertFalse(speedAnchorMoves(9.0, 20.0, RouteMovement.VEHICLE))
        assertTrue(speedAnchorMoves(10.0, 20.0, RouteMovement.VEHICLE))
        // A confirmed stop moves it whatever the drift, like the distance anchor.
        assertTrue(speedAnchorMoves(1.0, 5.0, RouteMovement.STOPPED))
    }

    @Test
    fun `a ride measured from a held anchor would never read as a ride`() {
        // Twenty minutes meandering in a park, then straight home at 20 km/h: Doppler says 5.5 m/s,
        // the chord from where the hold began says 200 m over 1 200 s, and the classifier takes the
        // slower. Every fix stays UNKNOWN, so the hold that produced the chord never ends.
        val fromHeldAnchor = conservativeRouteSpeedMps(20.0 / 3.6, 0.5, 200.0 / 1_200.0) * 3.6
        val classifier = RouteMovementClassifier()
        repeat(3) { classifier.classify(observation(it * 2_000L, fromHeldAnchor, Motion.NON_GAIT)) }
        assertEquals(
            RouteMovement.UNKNOWN,
            classifier.classify(observation(6_000, fromHeldAnchor, Motion.NON_GAIT)),
        )

        // Measured from the last fix that cleared the noise floor, geometry agrees with Doppler.
        val fromLastFix = conservativeRouteSpeedMps(20.0 / 3.6, 0.5, 11.0 / 2.0) * 3.6
        classifier.reset()
        classifier.classify(observation(0, fromLastFix, Motion.NON_GAIT))
        assertEquals(
            RouteMovement.VEHICLE,
            classifier.classify(observation(2_000, fromLastFix, Motion.NON_GAIT)),
        )
    }

    /**
     * A flat walk under a barometer that breathes two metres either side of the truth. Raw, every
     * fix would clear the 1.5 m step and book it — hundreds of metres of climb on level ground, and
     * an equal descent beside it. Smoothed first, the same fixes book nothing, and a real hill still
     * arrives in full.
     */
    @Test
    fun `sensor noise is not climb`() {
        fun climb(smooth: Boolean, altitudes: List<Double>): Pair<Double, Double> {
            var up = 0.0
            var down = 0.0
            var anchor: Double? = null
            var mean: Double? = null
            altitudes.forEach { raw ->
                val elevation = if (smooth) smoothedElevationM(raw, mean) ?: raw else raw
                mean = elevation
                val delta = elevation - (anchor ?: elevation)
                val counts = climbCounts(
                    delta,
                    barometric = true,
                    verticalAccuracyM = null,
                    movement = RouteMovement.WALKING,
                )
                if (counts) {
                    if (delta > 0) up += delta else down -= delta
                    anchor = elevation
                } else if (anchor == null) {
                    anchor = elevation
                }
            }
            return up to down
        }

        val flat = List(200) { 200.0 + if (it % 2 == 0) 2.0 else -2.0 }
        val (rawUp, rawDown) = climb(smooth = false, altitudes = flat)
        assertTrue("raw booked $rawUp m of climb on flat ground", rawUp > 300.0)
        assertTrue("raw booked $rawDown m of descent on flat ground", rawDown > 300.0)

        // Smoothed, the whole flat walk costs one settling step: the mean starts on the first
        // reading and slides to the middle of the noise band, which is worth a couple of metres
        // once per track and nothing afterwards.
        val (smoothUp, smoothDown) = climb(smooth = true, altitudes = flat)
        assertEquals(0.0, smoothUp, 0.001)
        assertTrue("smoothed booked $smoothDown m on flat ground", smoothDown < 2.5)

        // A 50 m hill walked up under the same noisy sensor still lands within a couple of metres.
        val hill = List(200) { index ->
            200.0 + index * 0.25 + if (index % 2 == 0) 2.0 else -2.0
        }
        val (hillUp, hillDown) = climb(smooth = true, altitudes = hill)
        assertTrue("a 50 m hill measured as $hillUp m", hillUp in 45.0..52.0)
        assertTrue("a climb booked $hillDown m of descent", hillDown < 2.5)
    }
}
