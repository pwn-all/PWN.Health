package com.pwnall.health

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal enum class RouteFixReason {
    START,
    PREDICTION_MATCH,
    CONFIRMED_BY_NEXT,
    REANCHORED,
    RETURN_SPIKE,
    UNCONFIRMED_JUMP,
    LOW_ACCURACY,
    STALE_FIX,
    NON_MONOTONIC_TIME,
    IMPOSSIBLE_SPEED,
}

internal data class RouteFix(
    val token: Long,
    val timestampNanos: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val reportedSpeedMps: Double?,
    val speedAccuracyMps: Double?,
    val motion: Motion,
)

internal data class RouteFixResolution(
    val fix: RouteFix,
    val accepted: Boolean,
    val correctedLat: Double? = null,
    val correctedLon: Double? = null,
    val reason: RouteFixReason,
)

/**
 * Robust one-point-look-ahead filter for GNSS tracks.
 *
 * Ordinary fixes are accepted immediately. A fix that disagrees with the previous velocity,
 * Doppler speed, motion class, or uncertainty envelope is held until the next fix arrives:
 *
 * - continuation in the same direction confirms real fast movement (usually a vehicle);
 * - return to the predicted path rejects the middle fix as a GNSS spike;
 * - another unrelated fix rejects the unconfirmed candidate and is evaluated from the last
 *   confirmed position.
 *
 * Above [MAX_UNSUPPORTED_SPEED_MPS] the geometry alone is not evidence: an urban canyon reflects
 * the signal far enough to fake a car for one or two fixes, and two faked fixes in a row look
 * exactly like acceleration. Doppler speed comes from carrier frequency rather than position, so a
 * reflection does not move it — past that limit a fix is confirmed only when Doppler agrees.
 *
 * The anchor is the last confirmed position, not a fixed truth: after a stretch the phone could not
 * see — a metro ride — the walker is no longer anywhere near it. When fix after fix has been refused
 * against it for a minute, the filter gives the anchor up and starts again at the current fix
 * ([RouteFixReason.REANCHORED]); see [reanchorIfStale].
 *
 * The filter never fabricates intermediate coordinates. "Correction" means that a rejected raw
 * measurement remains stored for diagnostics while the displayed/countable line joins confirmed
 * positions only.
 */
internal class RouteCorrelationFilter {
    private var previousAccepted: RouteFix? = null
    private var lastAccepted: RouteFix? = null
    private var pending: RouteFix? = null
    private var pendingSequenceStart: RouteFix? = null

    // Fixes refused one after another since the last accepted one; see [reanchorIfStale].
    private var refusedInARow = 0

    // One guard per out-of-order, duplicate or unusable fix.
    @Suppress("ReturnCount")
    fun offer(fix: RouteFix): List<RouteFixResolution> {
        val held = pending
        if (held == null) return evaluate(fix)

        pending = null
        val anchor = lastAccepted
        if (anchor == null) {
            pendingSequenceStart = null
            return listOf(reject(held, RouteFixReason.UNCONFIRMED_JUMP)) + evaluate(fix)
        }

        // A fix from no later than the held one — the same one delivered again, or one out of order —
        // says nothing of where the held one led. It alone is refused, and the held one waits on for
        // the next fix to judge it; refused with it, a real start of a ride would be lost for a duplicate.
        if (fix.timestampNanos <= held.timestampNanos) {
            pending = held
            return listOf(reject(fix, RouteFixReason.NON_MONOTONIC_TIME))
        }

        val anchorToHeld = vectorMeters(anchor, held)
        val heldToCurrent = vectorMeters(held, fix)
        val anchorToCurrent = vectorMeters(anchor, fix)
        val returnRadius = max(
            MIN_RETURN_RADIUS_M,
            min(MAX_RETURN_RADIUS_M, hypot(anchor.accuracyM, fix.accuracyM) * 1.25),
        )
        val returnedToPath = anchorToCurrent.length <= returnRadius &&
            anchorToHeld.length >= max(MIN_SUSPICIOUS_JUMP_M, returnRadius * 1.2)

        if (returnedToPath) {
            pendingSequenceStart = null
            return listOf(reject(held, RouteFixReason.RETURN_SPIKE)) + evaluate(fix)
        }

        // A second fix at the same displaced position is not trajectory evidence. GNSS multipath
        // can keep returning the same offset for several samples, and a zero-length continuation
        // would have a cosine of 1.0, immediately turning that offset into accepted distance.
        // Roll the pending candidate forward instead: resolve the older raw fix while the newest
        // stays pending, and keep the shifted cluster outside the route until it actually advances
        // or returns to the anchor — or until the anchor itself is stale, see [reanchorIfStale].
        val minimumProgress = minimumContinuationProgress(held, fix)
        if (heldToCurrent.length <= minimumProgress) {
            return resolveSlowContinuation(anchor, held, fix)
        }

        return if (isContinuation(anchor, held, fix, minimumProgress)) {
            acceptContinuation(held, fix)
        } else {
            pendingSequenceStart = null
            listOf(reject(held, RouteFixReason.UNCONFIRMED_JUMP)) + evaluate(fix)
        }
    }

    /** Does the fix after a held jump carry it on, rather than merely land somewhere else? */
    private fun isContinuation(
        anchor: RouteFix,
        held: RouteFix,
        fix: RouteFix,
        minimumProgress: Double,
    ): Boolean {
        val anchorToHeld = vectorMeters(anchor, held)
        val heldToCurrent = vectorMeters(held, fix)
        val anchorToCurrent = vectorMeters(anchor, fix)
        val firstSpeed = anchorToHeld.length / secondsBetween(anchor, held)
        val nextSpeed = heldToCurrent.length / secondsBetween(held, fix)
        // Slack proportional to the first leg would grow with the very spike it judges: a 45 km/h
        // error would buy itself 36 km/h of tolerance, so a 77 km/h reflection would count as the
        // same movement. The agreement has to tighten as the claim gets faster, not loosen.
        val speedDifferenceAllowed = max(2.0, firstSpeed * SPEED_AGREEMENT_FRACTION)
        val bothDopplerSpeedsCorrelate = reportedSpeedSupports(held, firstSpeed) &&
            reportedSpeedSupports(fix, nextSpeed)
        val speedCorrelated = abs(firstSpeed - nextSpeed) <= speedDifferenceAllowed ||
            bothDopplerSpeedsCorrelate
        val needsDoppler = max(firstSpeed, nextSpeed) > MAX_UNSUPPORTED_SPEED_MPS
        // Two consecutive reflected fixes can agree with each other and some providers derive a
        // matching speed from those same positions. Positive gait evidence is independent of that
        // geometry: if both samples say the user is on foot, they cannot confirm vehicle pace.
        val contradictedByGait = motionContradictsSpeed(held, firstSpeed) &&
            motionContradictsSpeed(fix, nextSpeed)
        return cosine(anchorToHeld, heldToCurrent) >= MIN_DIRECTION_CORRELATION &&
            nextSpeed <= MAX_PLAUSIBLE_SPEED_MPS &&
            !contradictedByGait &&
            (!needsDoppler || bothDopplerSpeedsCorrelate) &&
            speedCorrelated &&
            anchorToCurrent.length >= anchorToHeld.length + minimumProgress
    }

    /** A suspicious final point has no later evidence and is retained as rejected raw data. */
    fun flush(): List<RouteFixResolution> {
        val held = pending ?: return emptyList()
        pending = null
        pendingSequenceStart = null
        return listOf(reject(held, RouteFixReason.UNCONFIRMED_JUMP))
    }

    fun restore(accepted: List<RouteFix>) {
        reset()
        accepted.takeLast(2).forEach {
            previousAccepted = lastAccepted
            lastAccepted = it
        }
    }

    fun reset() {
        previousAccepted = null
        lastAccepted = null
        pending = null
        pendingSequenceStart = null
        refusedInARow = 0
    }

    /**
     * Counts [fix] as refused against [anchor] and, when that leaves the anchor stale, starts the
     * route again at the fix instead.
     *
     * Were only an accepted fix to move the anchor, then after a stretch the phone could not see — a
     * metro ride, a bus through a tunnel — every fix would be measured from the stop the walker had
     * left: too fast to have been walked from there and never a continuation of the jump, so it would
     * be refused for as long as the average speed back to the anchor stayed above a walk. That is ten
     * minutes after 2.5 km, with the recording looking alive throughout.
     *
     * The anchor is given up only when all three hold. [STALE_ANCHOR_FIXES] fixes in a row were
     * refused, so one spike before a tunnel does not cost the first fix after it its chance to be
     * confirmed by the next. The anchor is [STALE_ANCHOR_S] old, because noise can argue with a
     * walk for a quarter of a minute and a count alone is that short at a fix a second. And the fix
     * is further from the anchor than a walk covers in that time: a cluster a few metres off could
     * have been walked to, and stays out of the route as an offset.
     *
     * The service starts a new stretch of line at a re-anchored fix, as after a pause, so nothing
     * is drawn or counted across what the phone did not see.
     */
    private fun reanchorIfStale(anchor: RouteFix, fix: RouteFix): RouteFixResolution? {
        refusedInARow++
        val seconds = secondsBetween(anchor, fix)
        val stale = refusedInARow >= STALE_ANCHOR_FIXES &&
            seconds >= STALE_ANCHOR_S &&
            vectorMeters(anchor, fix).length / seconds > WALKING_PRODUCT_LIMIT_MPS
        if (!stale) return null
        reset()
        return accept(fix, RouteFixReason.REANCHORED)
    }

    // One branch per GPS rejection reason; each is reported separately in the UI.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun evaluate(fix: RouteFix): List<RouteFixResolution> {
        val last = lastAccepted
        if (last == null) return listOf(accept(fix, RouteFixReason.START))
        if (fix.timestampNanos <= last.timestampNanos) {
            return listOf(reject(fix, RouteFixReason.NON_MONOTONIC_TIME))
        }

        val displacement = vectorMeters(last, fix)
        val seconds = secondsBetween(last, fix)
        val geometricSpeed = displacement.length / seconds
        if (geometricSpeed > MAX_PLAUSIBLE_SPEED_MPS) {
            return listOf(reanchorIfStale(last, fix) ?: reject(fix, RouteFixReason.IMPOSSIBLE_SPEED))
        }

        val uncertainty = hypot(last.accuracyM, fix.accuracyM)
        val predictionResidual = predictionResidual(fix)
        val expectedTravel = (
            trustedRouteSpeedMps(fix.reportedSpeedMps, fix.speedAccuracyMps)
                ?: trustedRouteSpeedMps(last.reportedSpeedMps, last.speedAccuracyMps)
                ?: geometricSpeed.coerceAtMost(4.0)
            ) * seconds
        val innovationGate = max(
            MIN_INNOVATION_GATE_M,
            max(uncertainty * 1.25, expectedTravel * 0.75 + 4.0),
        )
        val contradictsPrediction =
            predictionResidual != null && predictionResidual > innovationGate
        val trustedSpeed = trustedRouteSpeedMps(fix.reportedSpeedMps, fix.speedAccuracyMps)
        val contradictsDoppler = trustedSpeed != null &&
            geometricSpeed > trustedSpeed + routeSpeedToleranceMps(fix.speedAccuracyMps)
        val contradictsMotion = when (fix.motion) {
            Motion.STILL -> geometricSpeed > STILL_MAX_GEOMETRIC_SPEED_MPS
            Motion.WALKING -> geometricSpeed > WALK_MAX_GEOMETRIC_SPEED_MPS
            Motion.RUNNING -> geometricSpeed > RUN_MAX_GEOMETRIC_SPEED_MPS
            else -> false
        }
        val unconfirmedFastTransition = geometricSpeed > WALKING_PRODUCT_LIMIT_MPS &&
            last.motion != Motion.RUNNING &&
            last.motion != Motion.VEHICLE &&
            fix.motion != Motion.RUNNING
        // Every gate above can fall silent at once — no Doppler, a gap too long to predict across,
        // and an accelerometer that claims a vehicle. That combination alone would wave through any
        // speed under the impossible-speed ceiling. Vehicle pace with nothing but position behind
        // it waits one fix for a second opinion.
        val unsupportedVehicleSpeed = geometricSpeed > MAX_UNSUPPORTED_SPEED_MPS &&
            !reportedSpeedSupports(fix, geometricSpeed)

        val suspicious = displacement.length >= MIN_SUSPICIOUS_JUMP_M &&
            (
                contradictsPrediction ||
                    contradictsDoppler ||
                    contradictsMotion ||
                    unconfirmedFastTransition ||
                    unsupportedVehicleSpeed
                )
        if (!suspicious) return listOf(accept(fix, RouteFixReason.PREDICTION_MATCH))
        val reanchored = reanchorIfStale(last, fix)
        if (reanchored != null) return listOf(reanchored)
        pending = fix
        pendingSequenceStart = fix
        return emptyList()
    }

    private fun predictionResidual(fix: RouteFix): Double? {
        val previous = previousAccepted ?: return null
        val last = lastAccepted ?: return null
        val historySeconds = secondsBetween(previous, last)
        val futureSeconds = secondsBetween(last, fix)
        if (historySeconds > MAX_PREDICTION_INTERVAL_S || futureSeconds > MAX_PREDICTION_INTERVAL_S) {
            return null
        }
        val history = vectorMeters(previous, last)
        val expected = Vector(
            history.x * futureSeconds / historySeconds,
            history.y * futureSeconds / historySeconds,
        )
        val measured = vectorMeters(last, fix)
        return hypot(measured.x - expected.x, measured.y - expected.y)
    }

    private fun accept(fix: RouteFix, reason: RouteFixReason): RouteFixResolution {
        previousAccepted = lastAccepted
        lastAccepted = fix
        refusedInARow = 0
        return acceptedResolution(fix, reason)
    }

    private fun acceptedResolution(fix: RouteFix, reason: RouteFixReason) =
        RouteFixResolution(
            fix = fix,
            accepted = true,
            correctedLat = fix.lat,
            correctedLon = fix.lon,
            reason = reason,
        )

    private fun reject(fix: RouteFix, reason: RouteFixReason) =
        RouteFixResolution(fix = fix, accepted = false, reason = reason)

    private fun resolveSlowContinuation(
        anchor: RouteFix,
        held: RouteFix,
        fix: RouteFix,
    ): List<RouteFixResolution> {
        val sequenceStart = pendingSequenceStart ?: held
        if (hasCumulativeProgress(anchor, sequenceStart, fix)) return acceptContinuation(held, fix)
        // This branch never reaches evaluate(), so a cluster far from a stale anchor would roll on
        // here for good: a 2.8 m walking step is under the progress a 5 m fix has to show.
        val reanchored = reanchorIfStale(anchor, fix)
        if (reanchored == null) pending = fix
        return listOfNotNull(reject(held, RouteFixReason.UNCONFIRMED_JUMP), reanchored)
    }

    private fun acceptContinuation(held: RouteFix, fix: RouteFix): List<RouteFixResolution> {
        pendingSequenceStart = null
        accept(held, RouteFixReason.CONFIRMED_BY_NEXT)
        return listOf(
            acceptedResolution(held, RouteFixReason.CONFIRMED_BY_NEXT),
            accept(fix, RouteFixReason.CONFIRMED_BY_NEXT),
        )
    }

    private fun hasCumulativeProgress(
        anchor: RouteFix,
        sequenceStart: RouteFix,
        current: RouteFix,
    ): Boolean {
        val initialJump = vectorMeters(anchor, sequenceStart)
        val accumulated = vectorMeters(sequenceStart, current)
        val anchorToCurrent = vectorMeters(anchor, current)
        val requiredProgress = minimumCumulativeProgress(sequenceStart, current)
        val radialProgress = anchorToCurrent.length - initialJump.length
        val accumulatedSpeed = accumulated.length / secondsBetween(sequenceStart, current)
        return accumulated.length >= requiredProgress &&
            radialProgress >= requiredProgress &&
            cosine(initialJump, accumulated) >= MIN_DIRECTION_CORRELATION &&
            accumulatedSpeed <= MAX_PLAUSIBLE_SPEED_MPS
    }

    private fun reportedSpeedSupports(fix: RouteFix, geometricSpeed: Double): Boolean {
        val reported = trustedRouteSpeedMps(
            fix.reportedSpeedMps,
            fix.speedAccuracyMps,
        ) ?: return false
        return abs(reported - geometricSpeed) <= routeSpeedToleranceMps(fix.speedAccuracyMps)
    }

    private fun motionContradictsSpeed(fix: RouteFix, geometricSpeed: Double): Boolean =
        when (fix.motion) {
            Motion.WALKING -> geometricSpeed > WALK_MAX_GEOMETRIC_SPEED_MPS
            Motion.RUNNING -> geometricSpeed > RUN_MAX_GEOMETRIC_SPEED_MPS
            else -> false
        }

    private fun minimumContinuationProgress(from: RouteFix, to: RouteFix): Double = max(
        MIN_CONTINUATION_PROGRESS_M,
        min(
            MAX_CONTINUATION_PROGRESS_M,
            hypot(from.accuracyM, to.accuracyM) * ACCURACY_PROGRESS_FRACTION,
        ),
    )

    private fun minimumCumulativeProgress(from: RouteFix, to: RouteFix): Double = max(
        MIN_CUMULATIVE_PROGRESS_M,
        min(
            MAX_CUMULATIVE_PROGRESS_M,
            hypot(from.accuracyM, to.accuracyM) * CUMULATIVE_ACCURACY_FACTOR,
        ),
    )

    private fun secondsBetween(from: RouteFix, to: RouteFix): Double =
        ((to.timestampNanos - from.timestampNanos) / 1e9).coerceAtLeast(0.001)

    private data class Vector(val x: Double, val y: Double) {
        val length: Double get() = hypot(x, y)
    }

    private fun vectorMeters(from: RouteFix, to: RouteFix): Vector {
        val fromLat = from.lat * PI / 180.0
        val toLat = to.lat * PI / 180.0
        val deltaLat = toLat - fromLat
        val deltaLon = wrappedLongitudeDegrees(to.lon - from.lon) * PI / 180.0
        val x = deltaLon * cos((fromLat + toLat) / 2.0) * EARTH_RADIUS_M
        val y = deltaLat * EARTH_RADIUS_M
        return Vector(x, y)
    }

    private fun cosine(first: Vector, second: Vector): Double {
        val denominator = first.length * second.length
        if (denominator <= 0.001) return -1.0
        return ((first.x * second.x + first.y * second.y) / denominator).coerceIn(-1.0, 1.0)
    }

    private companion object {
        const val MAX_PLAUSIBLE_SPEED_MPS = 70.0

        /** 43 km/h: past this, position alone no longer separates a vehicle from a reflection. */
        const val MAX_UNSUPPORTED_SPEED_MPS = 12.0
        const val WALKING_PRODUCT_LIMIT_MPS = 7.0 / 3.6
        const val WALK_MAX_GEOMETRIC_SPEED_MPS = 4.0
        const val RUN_MAX_GEOMETRIC_SPEED_MPS = 10.0
        const val STILL_MAX_GEOMETRIC_SPEED_MPS = 2.0
        const val MIN_SUSPICIOUS_JUMP_M = 8.0
        const val MIN_INNOVATION_GATE_M = 8.0
        const val MIN_RETURN_RADIUS_M = 4.0
        const val MAX_RETURN_RADIUS_M = 15.0
        const val MIN_CONTINUATION_PROGRESS_M = 0.75
        const val MAX_CONTINUATION_PROGRESS_M = 3.0
        const val ACCURACY_PROGRESS_FRACTION = 0.5
        const val MIN_CUMULATIVE_PROGRESS_M = 3.0
        const val MAX_CUMULATIVE_PROGRESS_M = 8.0
        const val CUMULATIVE_ACCURACY_FACTOR = 1.25

        // A jump is only ever confirmed by movement that carries on the same way it started.
        // At -0.15 a right-angle turn would still count as "the same direction", and a
        // there-and-back multipath zigzag would confirm itself; 0.5 is a 60° cone.
        const val MIN_DIRECTION_CORRELATION = 0.5
        const val SPEED_AGREEMENT_FRACTION = 0.35
        const val MAX_PREDICTION_INTERVAL_S = 15.0

        /** Half a minute of fixes at the two seconds the service asks for, a quarter at one a second. */
        const val STALE_ANCHOR_FIXES = 15

        /**
         * A fix still refused this long after the anchor is over 116 m from it. A 5 km/h walk
         * covers 83 m, which leaves 33 m: more than the 25 m of error the quality gate lets in.
         */
        const val STALE_ANCHOR_S = 60.0
    }
}
