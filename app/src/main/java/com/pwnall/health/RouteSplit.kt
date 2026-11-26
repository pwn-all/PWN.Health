package com.pwnall.health

data class RouteSplit(
    /** 1 for the first kilometre — or the first mile, on a profile read in miles. */
    val index: Int,
    val metres: Double,
    val seconds: Long,
    /** True when the route ended mid-split, so the pace is over a partial distance. */
    val partial: Boolean,
    /** What a whole split measures: [METRES_IN_A_KILOMETRE], or [METRES_IN_A_MILE] on a profile read in miles. */
    val splitMetres: Double = METRES_IN_A_KILOMETRE,
) {
    /**
     * Seconds per split — per kilometre or per mile, the number runners actually compare. Zero when
     * the tail is too short to extrapolate from: a few metres of drift over real seconds reads as
     * hours per km.
     */
    val paceSeconds: Long
        get() = if (metres <= 0.0 || (partial && metres < MIN_PACE_METRES)) {
            0L
        } else {
            (seconds / (metres / splitMetres)).toLong()
        }
}

private const val MIN_TRAILING_METRES = 1.0
private const val MIN_PACE_METRES = 50.0

/** The leg between two fixes, on the corrected position where there is one. */
private fun distanceMetres(from: TrackPointEntity, to: TrackPointEntity): Double = equirectangularMetres(
    from.correctedLat ?: from.lat,
    from.correctedLon ?: from.lon,
    to.correctedLat ?: to.lat,
    to.correctedLon ?: to.lon,
)

/**
 * Cuts an accepted track into splits of [splitMetres]: kilometres, or miles for a profile that reads
 * the cards above the table in miles.
 *
 * The boundary is interpolated inside the leg that crosses it rather than snapped to the next fix:
 * at a 5 s sampling interval a fix lands anywhere in ~7 m of walking, and snapping would move every
 * split by that much and make the last one absorb the error.
 *
 * Legs are measured from the same anchor the headline total is, by [anchorStep], so the two agree.
 * Summing every pair of adjacent fixes would count the zig-zag of GNSS noise and the wander of the
 * fifteen seconds before a stop is confirmed: a 10.0 km run with traffic lights in it would show an
 * eleventh split under a 10.0 km total, and a ride the gait gate never classified a full table
 * under 0 km.
 *
 * A pause is neither distance nor time: the first fix after one starts the anchor again, as the
 * recording did, and the clock is read without the pauses ([withoutPauses]), so a coffee stop with
 * the recording paused in the middle of a kilometre does not slow it.
 */
fun routeSplits(points: List<TrackPointEntity>, splitMetres: Double = METRES_IN_A_KILOMETRE): List<RouteSplit> {
    val fixes = withoutPauses(points.filter { it.accepted }.sortedBy { it.timestamp })
    if (fixes.size < 2) return emptyList()

    val splits = mutableListOf<RouteSplit>()
    var metresIntoSplit = 0.0
    var splitStartedAt = fixes.first().timestamp
    var anchor = fixes.first()

    for (to in fixes.subList(1, fixes.size)) {
        val leg = distanceMetres(anchor, to)
        val step = if (to.segmentStart) {
            // A pause lies between this fix and the one before: what ground it covered is not known,
            // so the anchor starts again here, as the recording's did, and nothing is counted.
            AnchorStep.DISCARD
        } else {
            anchorStep(leg, to.accuracyM?.toDouble() ?: 0.0, to.movement())
        }
        if (step == AnchorStep.HOLD) continue
        val from = anchor
        anchor = to
        if (step == AnchorStep.CREDIT) {
            var remaining = leg
            var legFrom = from.timestamp
            while (metresIntoSplit + remaining >= splitMetres) {
                val needed = splitMetres - metresIntoSplit
                // Time inside a leg is proportional to distance along it.
                val timeLeft = (to.timestamp - legFrom).coerceAtLeast(0)
                val crossedAt = legFrom + (timeLeft * (needed / remaining)).toLong()
                splits += RouteSplit(
                    index = splits.size + 1,
                    metres = splitMetres,
                    seconds = secondsBetween(splitStartedAt, crossedAt),
                    partial = false,
                    splitMetres = splitMetres,
                )
                splitStartedAt = crossedAt
                legFrom = crossedAt
                remaining -= needed
                metresIntoSplit = 0.0
            }
            metresIntoSplit += remaining
        }
    }

    // A leg that lands exactly on a split leaves a floating-point crumb behind it, and a split of
    // 0.0000000001 m is not a split.
    if (metresIntoSplit >= MIN_TRAILING_METRES) {
        splits += RouteSplit(
            index = splits.size + 1,
            metres = metresIntoSplit,
            seconds = secondsBetween(splitStartedAt, fixes.last().timestamp),
            partial = true,
            splitMetres = splitMetres,
        )
    }
    return splits
}

/**
 * [fixes] — accepted, in time order — on a clock that stops while the recording is paused: each fix's
 * time less every pause before it, a pause being the time from the last fix before it to the first
 * fix after, the one marked [TrackPointEntity.segmentStart]. That fix lands on the moment of the one
 * before, so the time between them counts for nothing, and the fixes keep their order.
 */
internal fun withoutPauses(fixes: List<TrackPointEntity>): List<TrackPointEntity> {
    var paused = 0L
    return fixes.mapIndexed { index, fix ->
        if (index > 0 && fix.segmentStart) paused += (fix.timestamp - fixes[index - 1].timestamp).coerceAtLeast(0)
        if (paused == 0L) fix else fix.copy(timestamp = fix.timestamp - paused)
    }
}

/** The movement class the fix was stored with; a name this build does not know is UNKNOWN. */
internal fun TrackPointEntity.movement(): RouteMovement =
    RouteMovement.entries.firstOrNull { it.name == movementType } ?: RouteMovement.UNKNOWN

/**
 * Rounded, not truncated: a boundary interpolated to sub-millisecond precision would otherwise
 * lose up to a second on every split, and the losses accumulate down the list.
 */
private fun secondsBetween(fromMs: Long, toMs: Long): Long =
    Math.round((toMs - fromMs).coerceAtLeast(0) / 1_000.0)

/** "5:30" — minutes and seconds per split; a dash when there is no pace to show. */
fun formatPace(secondsPerSplit: Long): String {
    if (secondsPerSplit <= 0) return "—"
    return "${secondsPerSplit / 60}:${(secondsPerSplit % 60).toString().padStart(2, '0')}"
}
