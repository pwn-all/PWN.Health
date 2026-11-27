package com.pwnall.health

import kotlin.math.abs
import kotlin.math.min

private const val WALK_SPEED_LIMIT_MPS = 7.0 / 3.6

/** Past this, a stride the phone feels belongs to a passenger rather than a runner. */
private const val RUN_SPEED_LIMIT_MPS = 25.0 / 3.6
private const val RUNNING_CADENCE_MIN_HZ = 2.35
private const val GAIT_PERIODICITY_MIN = 0.35
private const val STOP_SPEED_MPS = 0.55
private const val STOP_CONFIRM_MS = 15_000L
private const val MAX_TRUSTED_ROUTE_SPEED_ACCURACY_MPS = 3.0
private const val DEFAULT_ROUTE_SPEED_ACCURACY_MPS = 1.5
private const val ROUTE_SPEED_MARGIN_MPS = 1.5
private const val MAX_ROUTE_SPEED_TOLERANCE_MPS = 6.0

/** The spread [GateConfig] calls a walker's energy; a ride's vibration stays under it. */
private val WALKER_ENERGY_STD = GateConfig().doubtfulMinStd.toDouble()

/** A speed estimate too uncertain to classify movement cannot prove that a jump is genuine. */
internal fun trustedRouteSpeedMps(reportedSpeedMps: Double?, speedAccuracyMps: Double?): Double? {
    val reported = reportedSpeedMps?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    // A provider that reports no accuracy at all still gets the benefit of the doubt; one that
    // reports a number has to stand behind it. The range check also disposes of NaN and both
    // infinities on its own — every comparison against them is false — so no separate finite test.
    val accuracy = speedAccuracyMps ?: return reported
    return reported.takeIf { accuracy in 0.0..MAX_TRUSTED_ROUTE_SPEED_ACCURACY_MPS }
}

internal fun routeSpeedToleranceMps(speedAccuracyMps: Double?): Double = min(
    MAX_ROUTE_SPEED_TOLERANCE_MPS,
    3.0 * (speedAccuracyMps ?: DEFAULT_ROUTE_SPEED_ACCURACY_MPS) + ROUTE_SPEED_MARGIN_MPS,
)

/**
 * Uses the slower of Doppler and accepted-coordinate speed for movement totals and maximum speed.
 * Either source can jump high on its own; requiring both to be high prevents one bad measurement
 * from turning a walk into a ride. A missing geometric estimate is normal for the first fix.
 */
internal fun conservativeRouteSpeedMps(
    reportedSpeedMps: Double?,
    speedAccuracyMps: Double?,
    geometricSpeedMps: Double?,
): Double {
    val geometric = geometricSpeedMps?.takeIf { it.isFinite() && it >= 0.0 }
    val reported = trustedRouteSpeedMps(reportedSpeedMps, speedAccuracyMps)
    return when {
        geometric == null -> reported ?: 0.0
        reported == null -> geometric
        else -> min(reported, geometric)
    }
}

internal enum class RouteMovement {
    UNKNOWN,
    STOPPED,
    WALKING,
    RUNNING,
    VEHICLE,
}

/** The classes whose metres are route distance: STOPPED is drift, and UNKNOWN is not decided yet. */
internal val RouteMovement.creditsDistance: Boolean
    get() = this == RouteMovement.WALKING || this == RouteMovement.RUNNING || this == RouteMovement.VEHICLE

internal data class RouteObservation(
    val timestamp: Long,
    val speedMps: Double,
    val displacementM: Double,
    val accuracyM: Double,
    val motion: Motion,
    val cadenceHz: Double,
    val periodicity: Double,
    /** The hardware pedometer counted steps around this fix. */
    val stepsCounted: Boolean = false,
    /** The accelerometer window's magnitude spread, m/s² ([MotionReading.std]). */
    val std: Double = 0.0,
)

/**
 * Combines a three-segment median speed with the six-second accelerometer gait window.
 *
 * A raw speed over 7 km/h can never become walking. It is running when a periodic running cadence
 * is present, or when a sustained speed up to 25 km/h comes with a stride: a periodic gait at any
 * rhythm, or steps from the pedometer while the accelerometer window is missing. Otherwise a
 * sustained increase is vehicle movement. The median prevents one bad GNSS fix from creating a
 * vehicle or inferred running segment.
 *
 * The stride need not sit at a running cadence, or at half of one for a pocket. A jog at 8–9 km/h
 * falls between: 140 steps a minute is 2.33 Hz, under the running floor, and its stride harmonic
 * doubled is 2.33 again. On a screen-off run the accelerometer window is UNKNOWN while the
 * pedometer keeps counting. Held to a cadence, both runs would come back as a car with their steps
 * intact.
 *
 * A run in a pocket lands harder than the walks the gate's ceiling was set on: past
 * [GateConfig.gaitMaxStd] the gate calls the stride NON_GAIT, past [GateConfig.shakeStd] SHAKING,
 * and an 8–13 km/h run would come back a car between flickers of RUNNING. So the
 * pedometer also vouches for those windows when they carry a walker's energy; a ride has neither.
 *
 * A stop is fifteen seconds under 2 km/h without leaving the stop radius, and so is a slow walk:
 * uphill, or at a small child's pace, no fix clears either for as long as it lasts, and the whole
 * stretch would be dropped as standing. So a fix the pedometer counts steps around is not a stop
 * while the accelerometer hears the stride too or has no window to offer; the anchor then credits
 * the metres once they clear its own threshold.
 */
internal class RouteMovementClassifier {
    private val recentSpeeds = ArrayDeque<Double>(3)
    private var stoppedSince: Long? = null

    /**
     * The same three-fix median the class decision is taken on, published for the track's maximum
     * speed. A maximum read off one pair of fixes is a maximum of the noise: two seconds and one
     * reflected fix are all it takes to record a speed nobody travelled.
     */
    var medianSpeedMps = 0.0
        private set

    // Speed and accuracy thresholds form a decision table, not nested logic.
    @Suppress("CyclomaticComplexMethod")
    fun classify(observation: RouteObservation): RouteMovement {
        val rawSpeed = observation.speedMps.coerceAtLeast(0.0)
        if (recentSpeeds.size == 3) recentSpeeds.removeFirst()
        recentSpeeds.addLast(rawSpeed)
        // During warm-up the lower median of two samples is their minimum. Taking the upper one
        // would let a single spike at the beginning of a track become both VEHICLE and the lifetime max.
        val medianSpeed = recentSpeeds.sorted()[(recentSpeeds.size - 1) / 2]
        medianSpeedMps = medianSpeed.takeIf { recentSpeeds.size >= 2 } ?: 0.0

        val directRunningInterval = observation.motion == Motion.RUNNING &&
            observation.cadenceHz >= RUNNING_CADENCE_MIN_HZ &&
            observation.periodicity >= GAIT_PERIODICITY_MIN
        val hasSustainedFastSpeed = recentSpeeds.size >= 2 &&
            recentSpeeds.takeLast(2).all { it > WALK_SPEED_LIMIT_MPS }
        val periodicGait = (observation.motion == Motion.WALKING || observation.motion == Motion.RUNNING) &&
            observation.periodicity >= GAIT_PERIODICITY_MIN
        val hardLanding = (observation.motion == Motion.NON_GAIT || observation.motion == Motion.SHAKING) &&
            observation.std >= WALKER_ENERGY_STD
        val pedometerGait = observation.stepsCounted && (observation.motion == Motion.UNKNOWN || hardLanding)
        val stridingAtRunningPace = (periodicGait || pedometerGait) &&
            hasSustainedFastSpeed &&
            medianSpeed <= RUN_SPEED_LIMIT_MPS
        val runningInterval = directRunningInterval || stridingAtRunningPace
        if (runningInterval) {
            stoppedSince = null
            return RouteMovement.RUNNING
        }

        if (rawSpeed > WALK_SPEED_LIMIT_MPS) {
            stoppedSince = null
            return if (recentSpeeds.size >= 2 && medianSpeed > WALK_SPEED_LIMIT_MPS) {
                RouteMovement.VEHICLE
            } else {
                RouteMovement.UNKNOWN
            }
        }

        val withinStopRadius = observation.displacementM <=
            maxOf(2.5, observation.accuracyM * 0.4)
        // Speed and radius alone cannot tell standing from a slow walk: a steep climb or a small
        // child's pace stays under both for as long as it lasts, and a confirmed stop drops its
        // metres at every fix. Steps the pedometer is counting are not a stop, unless the
        // accelerometer window is there to say they are not a stride.
        val countedStride = observation.stepsCounted && (periodicGait || observation.motion == Motion.UNKNOWN)
        if (medianSpeed <= STOP_SPEED_MPS && withinStopRadius && !countedStride) {
            val since = stoppedSince ?: observation.timestamp.also { stoppedSince = it }
            return if (observation.timestamp - since >= STOP_CONFIRM_MS) {
                RouteMovement.STOPPED
            } else {
                RouteMovement.UNKNOWN
            }
        }
        stoppedSince = null

        return when {
            observation.motion == Motion.WALKING -> RouteMovement.WALKING

            observation.motion == Motion.VEHICLE && medianSpeed > 0.8 -> RouteMovement.VEHICLE

            observation.motion == Motion.STILL && medianSpeed > 0.8 -> RouteMovement.VEHICLE

            // Missing accelerometer evidence may fall back to GPS walking pace. NON_GAIT is a
            // positive veto and must not be overwritten here: scooters and bicycles can have the
            // same speed without a stride.
            observation.motion == Motion.UNKNOWN &&
                medianSpeed in 0.3..WALK_SPEED_LIMIT_MPS -> RouteMovement.WALKING

            else -> RouteMovement.UNKNOWN
        }
    }

    fun reset() {
        recentSpeeds.clear()
        stoppedSince = null
        medianSpeedMps = 0.0
    }
}

/** Barometric altitude is quiet enough to trust a step of a metre and a half. */
private const val BAROMETRIC_ELEVATION_STEP_M = 1.5

/** GPS altitude is not: never a step under the 3 m docs/DESIGN.md §4.2 asks for. */
private const val GPS_ELEVATION_STEP_M = 3.0

/**
 * How far the altitude has to move between two fixes before it counts as climb or descent.
 *
 * With a barometer a change of a metre and a half is real. Without one the only altitude is the
 * receiver's, which wanders by its own vertical accuracy — 2 to 5 m in the open, more under trees —
 * and a phone without a pressure sensor would book hundreds of metres of climb on a flat hour's
 * walk by counting every wander that cleared 1.5 m. The step is the fix's stated accuracy when it
 * has one, and never under 3 m.
 */
internal fun elevationStepThresholdM(barometric: Boolean, verticalAccuracyM: Float?): Double = if (barometric) {
    BAROMETRIC_ELEVATION_STEP_M
} else {
    maxOf(GPS_ELEVATION_STEP_M, verticalAccuracyM?.toDouble() ?: GPS_ELEVATION_STEP_M)
}

/**
 * Exponential smoothing on the altitude a climb is measured from, a fifth of the way at each fix — the
 * share [FloorTracker]'s time constant moves it at each of the barometer's 5 Hz samples, for the same
 * reason.
 */
private const val ELEVATION_SMOOTHING = 0.2

/**
 * The altitude the climb is measured from: an exponential mean of the fixes, never a raw reading.
 *
 * A pressure sensor wanders about a metre either side of the truth from one fix to the next, and a
 * receiver's altitude several. Against a raw series, hysteresis does not filter that: every wander
 * that clears the step books the step, the anchor moves to the wander, and the next wander books
 * again. A ninety-minute walk at a fix every two seconds would report 1472 m of climb and 1465 m of
 * descent over a hill 50 m high — the two sums nearly equal, because almost all of both is the
 * sensor breathing rather than the ground. Smoothed first, the same noise never reaches the step.
 *
 * A reading that is missing or non-finite leaves the mean where it was rather than poisoning it:
 * the filter feeds its own output back in, so one NaN would make every later altitude NaN.
 */
internal fun smoothedElevationM(elevationM: Double?, smoothedM: Double?): Double? = when {
    elevationM == null || !elevationM.isFinite() -> smoothedM
    smoothedM == null -> elevationM
    else -> smoothedM + ELEVATION_SMOOTHING * (elevationM - smoothedM)
}

/**
 * Whether the altitude change between two fixes is climb or descent worth booking: never while the
 * person stands still — a receiver's altitude wanders at a crossing, and every wander would be a
 * metre up or a metre down — and only past the step the instrument earns, [elevationStepThresholdM].
 */
internal fun climbCounts(
    deltaM: Double,
    barometric: Boolean,
    verticalAccuracyM: Float?,
    movement: RouteMovement,
): Boolean = movement != RouteMovement.STOPPED &&
    abs(deltaM) >= elevationStepThresholdM(barometric, verticalAccuracyM)

/** The least a fix has to move from the anchor before that is ground covered rather than noise. */
private const val MIN_MOVE_M = 3.0

/** [MIN_MOVE_M], or half the fix's stated accuracy when the receiver admits to worse. */
private fun movementThresholdM(accuracyM: Double): Double = maxOf(MIN_MOVE_M, accuracyM * 0.5)

/** What a fix does to the anchor the route's distance is measured from. */
internal enum class AnchorStep {
    /** The anchor stays where it is: the fix is within noise of it, or its movement is not yet classified. */
    HOLD,

    /** The metres from the anchor to the fix are route distance, and the anchor moves to the fix. */
    CREDIT,

    /** The anchor moves to the fix and the metres are dropped: the wander of standing still. */
    DISCARD,
}

/**
 * Distance is summed from an anchor rather than fix to fix, so that GNSS noise has to add up to
 * [MIN_MOVE_M] — or half the fix's stated accuracy — before it counts as ground covered.
 *
 * A fix whose movement is still UNKNOWN holds the anchor rather than moving it. Moving it and
 * crediting nothing would lose the metres of every transitional fix for good: the first fast fix
 * of a walk becoming a run, or a slow ride the gait gate calls NON_GAIT, would go uncounted, and
 * the next fix would measure only from where they ended. Held, the segment keeps growing until a classified
 * fix either credits the whole of it or, on STOPPED, drops it as the wander of standing still.
 */
internal fun anchorStep(anchorDistanceM: Double, accuracyM: Double, movement: RouteMovement): AnchorStep =
    when {
        movement == RouteMovement.STOPPED -> AnchorStep.DISCARD
        anchorDistanceM < movementThresholdM(accuracyM) -> AnchorStep.HOLD
        movement.creditsDistance -> AnchorStep.CREDIT
        else -> AnchorStep.HOLD
    }

/**
 * Whether the fix becomes the baseline the next fix's geometric speed is measured from: any fix
 * that clears the noise floor, whatever its class, and every confirmed stop — where the distance
 * anchor of [anchorStep] holds through an unclassified fix.
 *
 * The speed cannot be measured from the held anchor. Through a long UNKNOWN stretch — a slow ride
 * the gait gate calls NON_GAIT is one, fix after fix — the chord from where the hold began, over all
 * the time since, reads as a crawl however fast the rider now goes. The classifier takes the slower
 * of that and Doppler, so it would never see the speed that ends the hold, and the hold would never
 * end: the speed card would read under a kilometre an hour at twenty, and the next confirmed stop
 * would discard the whole ride as drift.
 */
internal fun speedAnchorMoves(anchorDistanceM: Double, accuracyM: Double, movement: RouteMovement): Boolean =
    movement == RouteMovement.STOPPED || anchorDistanceM >= movementThresholdM(accuracyM)
