package com.pwnall.health

/**
 * The time a route's fix is stored under ([TrackPointEntity.timestamp]), and the boot clock it was
 * read off; the route's clock, carried from one fix to the next by [routeFixClock].
 */
internal data class RouteClock(val timeMs: Long, val elapsedRealtimeNanos: Long)

/**
 * The clock after a fix taken at [fixTimeMs] by its own clock and [fixElapsedNanos] by the boot's.
 *
 * The line, the splits and the GPX all put a route's fixes in the order of their stored times, and a
 * fix's own time can step back: a provider that falls back from the satellites to the phone's clock,
 * or the phone's clock set back under a walk, would draw the line in a zigzag. So only the route's first
 * fix keeps its own time; every later one is the time before it plus what the boot clock, which
 * never steps, says passed between them. A fix taken before the latest one — a cached position handed
 * over late — is stored at the latest one's time and leaves the clock where it was, or the fix after
 * it would be dated by the whole age of the cached one. Across a reboot, where the two boot clocks
 * say nothing of each other ([previous] carries 0), a fix is never stored earlier than the last.
 */
internal fun routeFixClock(previous: RouteClock?, fixTimeMs: Long, fixElapsedNanos: Long): RouteClock {
    previous ?: return RouteClock(fixTimeMs, fixElapsedNanos)
    val sameBoot = previous.elapsedRealtimeNanos > 0 && fixElapsedNanos > 0
    return when {
        !sameBoot -> RouteClock(maxOf(fixTimeMs, previous.timeMs), fixElapsedNanos)

        fixElapsedNanos < previous.elapsedRealtimeNanos -> previous

        else -> RouteClock(
            previous.timeMs + (fixElapsedNanos - previous.elapsedRealtimeNanos) / 1_000_000L,
            fixElapsedNanos,
        )
    }
}

/** Where an accepted fix put the walker, after the correlation filter's correction, and when. */
internal data class RoutePosition(
    val lat: Double,
    val lon: Double,
    /** The route clock's time of the fix ([routeFixClock]). */
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
)

/** What an accepted fix brings to [RouteTally.add] besides where it was. */
internal data class TalliedFix(
    val position: RoutePosition,
    /** The receiver's stated horizontal accuracy: the noise floor the anchors hold under. */
    val accuracyM: Double,
    val reportedSpeedMps: Double?,
    val speedAccuracyMps: Double?,
    /** The gate's verdict for the fix's own moment. */
    val reading: MotionReading,
    /** The hardware pedometer counted steps around the fix ([SensorFeed.stepsNear]). */
    val stepsCounted: Boolean,
    /** The barometer's calibrated altitude, or the receiver's on a phone without one. */
    val elevationM: Double?,
    val barometric: Boolean,
    val verticalAccuracyM: Float?,
) {
    /** The fix as the route classifier sees it, with the pedometer beside the accelerometer. */
    fun observation(speedMps: Double, displacementM: Double) = RouteObservation(
        timestamp = position.timeMs,
        speedMps = speedMps,
        displacementM = displacementM,
        accuracyM = accuracyM,
        motion = reading.motion,
        cadenceHz = reading.cadenceHz.toDouble(),
        periodicity = reading.periodicity.toDouble(),
        stepsCounted = stepsCounted,
        std = reading.std.toDouble(),
    )
}

/** How an accepted fix was booked: the class its leg went to, and the speed it was judged at. */
internal data class TalliedLeg(val movement: RouteMovement, val speedMps: Double)

/** A route's running totals, as [StepsDao.updateTrack] writes them and a restart reads them back. */
internal data class RouteTotals(
    val meters: Double = 0.0,
    val points: Int = 0,
    val walkingMeters: Double = 0.0,
    val runningMeters: Double = 0.0,
    val vehicleMeters: Double = 0.0,
    val stoppedMillis: Long = 0L,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
)

/** The totals a route's row holds, for a recording picked back up after a restart. */
internal fun TrackEntity.routeTotals() = RouteTotals(
    meters = distanceMeters,
    points = points,
    walkingMeters = walkingDistanceMeters,
    runningMeters = runningDistanceMeters,
    vehicleMeters = vehicleDistanceMeters,
    stoppedMillis = stoppedMillis,
    elevationGainM = elevationGainM,
    elevationLossM = elevationLossM,
    maxSpeedMps = maxSpeedMps,
)

/**
 * A route's accounting of its accepted fixes: the distance and the class each leg is credited to, the
 * time stood still, the climb and the descent, the fastest speed. Over plain values rather than the
 * platform's Location, so that a test can walk a route through it; the service keeps one for the
 * recording in hand, hands it every accepted fix, and stores what it answers.
 *
 * Every distance is on the sphere the route maths runs on, as the walk's chart and the splits measure
 * the same legs again from the stored fixes ([equirectangularMetres]).
 */
internal class RouteTally {
    /** Replaced whole at each fix: the notification reads it on whichever thread posts it. */
    @Volatile
    var totals = RouteTotals()

    private val classifier = RouteMovementClassifier()
    private var last: RoutePosition? = null
    private var distanceAnchor: RoutePosition? = null

    // Where the geometric speed is measured from. It cannot be [distanceAnchor]: that one holds
    // through an unclassified stretch, and the chord since the hold began over all the time since is
    // not a speed. This one moves by [speedAnchorMoves].
    private var speedAnchor: RoutePosition? = null
    private var lastElevationM: Double? = null

    /** The exponential mean the climb is measured from; see [smoothedElevationM]. */
    private var elevationMeanM: Double? = null
    private var lastMovement = RouteMovement.UNKNOWN

    /** Whether the line runs on from an earlier fix, or the next accepted one starts it afresh. */
    val lineRunsOn: Boolean
        get() = last != null

    /** Books an accepted fix, and says which class its leg went to and at what speed. */
    fun add(fix: TalliedFix): TalliedLeg {
        val here = fix.position
        val previous = last
        val anchor = distanceAnchor ?: here.also { distanceAnchor = it }
        val anchorDistance = metresBetween(anchor, here)
        val speedBase = speedAnchor ?: here.also { speedAnchor = it }
        val speedDistance = metresBetween(speedBase, here)
        val speedMps = conservativeRouteSpeedMps(
            reportedSpeedMps = fix.reportedSpeedMps,
            speedAccuracyMps = fix.speedAccuracyMps,
            geometricSpeedMps = geometricSpeedMps(speedDistance, speedBase, here),
        )
        val displacement = previous?.let { metresBetween(it, here) } ?: 0.0
        // The class is this fix's own; the metres credited with it are the whole leg's.
        val movement = legMovement(
            classifier.classify(fix.observation(speedMps, displacement)),
            anchorDistance,
            secondsBetween(anchor, here),
        )
        var booked = totals
        when (anchorStep(anchorDistance, fix.accuracyM, movement)) {
            AnchorStep.CREDIT -> {
                booked = booked.credited(movement, anchorDistance)
                distanceAnchor = here
            }

            // Stationary GNSS drift must not add up until it crosses the distance threshold.
            AnchorStep.DISCARD -> distanceAnchor = here

            AnchorStep.HOLD -> Unit
        }
        if (speedAnchorMoves(speedDistance, fix.accuracyM, movement)) speedAnchor = here
        if (movement == RouteMovement.STOPPED && lastMovement == RouteMovement.STOPPED) {
            val stoodMs = (secondsBetween(previous, here) * 1_000).toLong().coerceAtLeast(0)
            booked = booked.copy(stoppedMillis = booked.stoppedMillis + stoodMs)
        }
        lastMovement = movement
        last = here
        totals = climbed(booked, fix, movement).copy(
            points = booked.points + 1,
            maxSpeedMps = maxOf(booked.maxSpeedMps, classifier.medianSpeedMps),
        )
        return TalliedLeg(movement, speedMps)
    }

    /**
     * Forgets where the line was: the next fix is measured from nothing, as a route's first is — after
     * a pause, or a stretch the correlation filter gave up on. The totals stay.
     */
    fun breakLine() {
        last = null
        distanceAnchor = null
        speedAnchor = null
        dropElevation()
        lastMovement = RouteMovement.UNKNOWN
        classifier.reset()
    }

    /**
     * The barometer has just been pinned to the satellites: the anchor was measured on the uncalibrated
     * datum. Dropping it costs one point's climb and keeps the calibration step itself — tens of metres
     * of weather — out of the day's ascent.
     */
    fun dropElevation() {
        lastElevationM = null
        elevationMeanM = null
    }

    /** Runs the line on from [position], the last fix stored before a restart, as if it had just been booked. */
    fun runOnFrom(position: RoutePosition, elevationM: Double?, movement: RouteMovement) {
        last = position
        distanceAnchor = position
        speedAnchor = position
        lastElevationM = elevationM
        elevationMeanM = elevationM
        lastMovement = movement
    }

    /** [booked] with the fix's climb or descent, booked only past the step its instrument earns. */
    private fun climbed(booked: RouteTotals, fix: TalliedFix, movement: RouteMovement): RouteTotals {
        val elevation = smoothedElevationM(fix.elevationM, elevationMeanM)
        elevationMeanM = elevation
        val from = lastElevationM
        // Nothing until both ends exist.
        val delta = if (elevation != null && from != null) elevation - from else 0.0
        if (!climbCounts(delta, fix.barometric, fix.verticalAccuracyM, movement)) {
            if (lastElevationM == null) lastElevationM = elevation
            return booked
        }
        lastElevationM = elevation
        return if (delta > 0) {
            booked.copy(elevationGainM = booked.elevationGainM + delta)
        } else {
            booked.copy(elevationLossM = booked.elevationLossM - delta)
        }
    }
}

/** [this] with [metres] credited to the route and to [movement]'s own total. */
private fun RouteTotals.credited(movement: RouteMovement, metres: Double): RouteTotals = when (movement) {
    RouteMovement.WALKING -> copy(meters = meters + metres, walkingMeters = walkingMeters + metres)
    RouteMovement.RUNNING -> copy(meters = meters + metres, runningMeters = runningMeters + metres)
    RouteMovement.VEHICLE -> copy(meters = meters + metres, vehicleMeters = vehicleMeters + metres)
    else -> copy(meters = meters + metres)
}

private fun metresBetween(from: RoutePosition, to: RoutePosition): Double =
    equirectangularMetres(from.lat, from.lon, to.lat, to.lon)

private fun secondsBetween(previous: RoutePosition?, current: RoutePosition): Double {
    previous ?: return 0.0
    if (previous.elapsedRealtimeNanos > 0 && current.elapsedRealtimeNanos > 0) {
        return (current.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1e9
    }
    return (current.timeMs - previous.timeMs) / 1_000.0
}

/**
 * Straight-line speed from the speed anchor to the fix, or null while the two share an instant: the
 * first fix of a track is its own anchor.
 */
private fun geometricSpeedMps(distanceM: Double, from: RoutePosition, to: RoutePosition): Double? {
    val seconds = secondsBetween(from, to)
    return if (seconds > 0.0) distanceM / seconds else null
}
