package com.pwnall.health

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class StepsMotion(val seconds: Long, val meters: Double, val estimated: Boolean = false) {
    fun paceSeconds(imperial: Boolean): Int? = if (seconds > 0 && meters > 0) {
        (seconds / meters * if (imperial) METRES_IN_A_MILE else METRES_IN_A_KILOMETRE).roundToInt()
    } else {
        null
    }
}

/** An explicit fallback, not a measured cadence: ordinary steps only have hourly timestamps. */
private const val ESTIMATED_STEPS_PER_MINUTE = 100.0
private const val HOUR_MILLIS = 3_600_000L

/** The walking a recording holds per metre of ride, at the least, to count toward the day. */
private const val MIN_WALK_PER_RIDE = 4.0

private data class MotionSpan(
    val start: Long,
    val end: Long,
    val movingFraction: Double,
    val metersPerMillis: Double,
    val estimated: Boolean = false,
)

private fun routeSpans(tracks: List<TrackEntity>, now: Long): List<MotionSpan> = tracks.mapNotNull { track ->
    val end = track.endedAt ?: return@mapNotNull null
    // A walk with a stretch the GPS took for a ride does not drop out whole, leaving the day's
    // distance to the pedometer; only the ride's own metres are left out. Its minutes still
    // count as moving, so a longer ride still drops the recording, and the pedometer covers the
    // walking in it: at a quarter of the walking, a bus at six times walking pace adds 4% to the time.
    val vehicle = track.vehicleDistanceMeters.coerceAtLeast(0.0)
    val onFoot = track.distanceMeters - vehicle
    val validDistance = onFoot.isFinite() && onFoot > 0 && vehicle <= onFoot / MIN_WALK_PER_RIDE
    val validTime = end > track.startedAt && end <= now
    if (!validTime || !validDistance) {
        return@mapNotNull null
    }
    val elapsed = end - track.startedAt
    // A pause is not walking: the recording stood still, whatever the walker did meanwhile.
    val moving = (elapsed - track.stoppedMillis.coerceAtLeast(0) - track.pausedMillis.coerceAtLeast(0)).coerceAtLeast(0)
    if (moving == 0L) return@mapNotNull null
    // Limitation: TrackEntity keeps no vehicle time, hence the cap on the ride.
    MotionSpan(track.startedAt, end, moving.toDouble() / elapsed, onFoot / elapsed)
}

/** Split overlapping recordings into a union. A measured route takes precedence over a manual log. */
private fun recordedMotion(spans: List<MotionSpan>, from: Long, to: Long): StepsMotion {
    val relevant = spans.filter { it.start < to && it.end > from }
    val boundaries = (
        listOf(from, to) + relevant.flatMap {
            listOf(it.start.coerceAtLeast(from), it.end.coerceAtMost(to))
        }
        ).distinct().sorted()
    var millis = 0.0
    var meters = 0.0
    var estimated = false
    boundaries.zipWithNext().forEach { (start, end) ->
        val span = relevant.filter { it.start <= start && it.end >= end }
            .minWithOrNull(compareBy<MotionSpan> { it.estimated }.thenBy { it.start }.thenBy { it.end })
        if (span != null) {
            millis += (end - start) * span.movingFraction
            meters += (end - start) * span.metersPerMillis
            estimated = estimated || span.estimated
        }
    }
    return StepsMotion((millis / 1000).roundToLong(), meters, estimated)
}

internal fun stepsMotion(tracks: List<TrackEntity>, from: Long, now: Long): StepsMotion =
    recordedMotion(routeSpans(tracks.filter { it.startedAt >= from }, now), from, now)

/**
 * Reconcile within each hour, never add a route's distance to the steps that may describe it.
 * Only steps exceeding the recorded distance contribute estimated extra time. Daily-only device
 * totals are reconciled against the whole day, because their overlap cannot be located in time.
 * Hourly counts cannot establish exact walking time or distinguish separate walks within an hour.
 */
internal fun dayStepsMotion(
    steps: Long,
    hourly: List<HourSteps>,
    tracks: List<TrackEntity>,
    journal: List<JournalEntry>,
    heightCm: Int,
    day: LocalDate,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): StepsMotion {
    val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = minOf(now, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    val stride = stepDistanceKm(1, heightCm) * 1000
    val metersPerSecond = stride * ESTIMATED_STEPS_PER_MINUTE / 60
    if (to <= from || !metersPerSecond.isFinite() || metersPerSecond <= 0) return StepsMotion(0, 0.0)
    val spans = routeSpans(tracks, now) + manualSpans(journal, metersPerSecond, now)
    var seconds = 0L
    var meters = 0.0
    var estimated = false
    var cursor = from
    while (cursor < to) {
        val end = minOf(cursor + HOUR_MILLIS, to)
        val hour = java.time.Instant.ofEpochMilli(cursor).atZone(zone).hour
        // On a repeated DST hour the persisted bucket covers both occurrences; use it once.
        val firstOccurrence = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli() == cursor
        val count = if (firstOccurrence) {
            hourly.filter { it.day == day.toString() && it.hour == hour }.sumOf { it.steps.coerceAtLeast(0) }
        } else {
            0L
        }
        val recorded = recordedMotion(spans, cursor, end)
        val extraMeters = (count * stride - recorded.meters).coerceAtLeast(0.0)
        val extraSeconds = (extraMeters / metersPerSecond).roundToLong()
            .coerceAtMost(((end - cursor) / 1000 - recorded.seconds).coerceAtLeast(0))
        seconds += recorded.seconds + extraSeconds
        meters += maxOf(recorded.meters, count * stride)
        estimated = estimated || recorded.estimated || extraMeters > 0
        cursor = end
    }
    val dailyExtra = (steps.coerceAtLeast(0) * stride - meters).coerceAtLeast(0.0)
    seconds += (dailyExtra / metersPerSecond).roundToLong()
        .coerceAtMost(((to - from) / 1000 - seconds).coerceAtLeast(0))
    return StepsMotion(seconds, meters + dailyExtra, estimated || dailyExtra > 0)
}

private fun manualSpans(entries: List<JournalEntry>, metersPerSecond: Double, now: Long): List<MotionSpan> =
    entries.mapNotNull { entry ->
        val minutes = entry.durationMinutes ?: return@mapNotNull null
        val validDuration = minutes.isFinite() && minutes > 0
        val walking = entry.kind == KIND_ACTIVITY && entry.activityCode in setOf("WALK", "HIKING")
        if (!walking || !validDuration || entry.occurredAt > now) {
            return@mapNotNull null
        }
        val duration = (minutes.coerceAtMost(1440.0) * 60_000).roundToLong()
        // Manual activities end at occurredAt, matching JournalEntry.sessionInterval.
        MotionSpan(entry.occurredAt - duration, entry.occurredAt, 1.0, metersPerSecond / 1000, true)
    }

/**
 * The longest unbroken run of today's hours that held no walking, as an inclusive range of hours.
 *
 * This is not standing or sitting time and is deliberately not called either. No Android sensor can
 * tell an upright body from a seated one — a phone reports its own tilt, not its owner's posture,
 * and on a desk it reports neither — and Health Connect has no record type for either, so there is
 * nothing to record and nothing to publish. What the app does hold is one step total per hour, and
 * the longest gap in it is the honest half of the question.
 *
 * An hour counts as moved at [ESTIMATED_STEPS_PER_MINUTE], which is about a minute of walking and
 * is the cadence the walking-time estimate above already assumes rather than a second guess
 * standing beside it. The run is measured from the first hour that moved, never from midnight: the
 * longest still stretch of everybody's day is the night they slept through, and saying so is not
 * information. Null while no hour has moved yet, and null when every hour since the first has.
 *
 * The hour in progress cannot be a stretch by itself: two minutes past the hour it would print
 * "14:00–14:02" and call it the longest of the day. It still extends a stretch that reaches it,
 * which is the case worth showing live — a sit that is still going.
 */
internal fun longestStillHours(hours: List<Float>, throughHour: Int): IntRange? {
    val elapsed = (throughHour + 1).coerceIn(0, hours.size)
    val moved = hours.take(elapsed).map { it >= ESTIMATED_STEPS_PER_MINUTE }
    val first = moved.indexOfFirst { it }
    if (first < 0) return null
    var best: IntRange? = null
    var bestLength = 0
    var start = -1
    // The bound is inclusive so the last run is closed by the loop rather than after it.
    for (hour in first..moved.size) {
        if (hour < moved.size && !moved[hour]) {
            if (start < 0) start = hour
        } else if (start >= 0) {
            if (hour - start > bestLength) {
                bestLength = hour - start
                best = start until hour
            }
            start = -1
        }
    }
    return best?.takeUnless { it.first == throughHour }
}
