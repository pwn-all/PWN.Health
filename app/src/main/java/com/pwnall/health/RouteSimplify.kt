package com.pwnall.health

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round

/** Five metres is below GNSS noise, so the exported line looks identical and weighs a third. */
const val ROUTE_SIMPLIFY_TOLERANCE_M = 5.0

/**
 * Douglas–Peucker, applied on export and never before it.
 *
 * Distance and elevation gain are measured from every accepted fix; dropping points first would
 * quietly shorten the route. What simplification is for is the file that leaves the phone: a walk
 * at one fix per second is tens of thousands of points, most of them saying the same thing about a
 * straight stretch of pavement, and every program that opens it has to carry them.
 *
 * Both ends are always kept, and so is any point further than [toleranceMetres] from the line
 * between the ends — that is the whole algorithm. The stack is explicit because a route recorded
 * along a road is close to sorted, which is exactly the shape that makes the recursive form deep.
 */
fun simplifyRoute(
    points: List<TrackPointEntity>,
    toleranceMetres: Double = ROUTE_SIMPLIFY_TOLERANCE_M,
): List<TrackPointEntity> {
    if (points.size < 3 || toleranceMetres <= 0.0) return points

    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val scaleLon = cos(latitudeOf(points[points.size / 2]) * PI / 180.0)
    val pending = ArrayDeque<Pair<Int, Int>>()
    pending.addLast(0 to points.lastIndex)

    while (pending.isNotEmpty()) {
        val (first, last) = pending.removeLast()
        val farthest = farthestFrom(points, first, last, scaleLon, toleranceMetres)
        if (farthest > 0) {
            keep[farthest] = true
            pending.addLast(first to farthest)
            pending.addLast(farthest to last)
        }
    }

    return points.filterIndexed { index, _ -> keep[index] }
}

/** The point between [first] and [last] that the straight line misses by most, or -1 if none does. */
private fun farthestFrom(
    points: List<TrackPointEntity>,
    first: Int,
    last: Int,
    scaleLon: Double,
    toleranceMetres: Double,
): Int {
    var farthest = -1
    var farthestDistance = toleranceMetres
    for (index in first + 1 until last) {
        val distance = perpendicularMetres(points[index], points[first], points[last], scaleLon)
        if (distance > farthestDistance) {
            farthestDistance = distance
            farthest = index
        }
    }
    return farthest
}

private fun latitudeOf(point: TrackPointEntity) = point.correctedLat ?: point.lat

private fun longitudeOf(point: TrackPointEntity) = point.correctedLon ?: point.lon

/**
 * Distance from [point] to the segment [start]–[end], in metres.
 *
 * Degrees are turned into metres first — a degree of longitude is a shorter distance than a degree
 * of latitude everywhere but the equator, and comparing raw degrees to a tolerance in metres would
 * simplify north–south stretches far more aggressively than east–west ones.
 */
private fun perpendicularMetres(
    point: TrackPointEntity,
    start: TrackPointEntity,
    end: TrackPointEntity,
    scaleLon: Double,
): Double {
    val degreeMetres = EARTH_RADIUS_M * PI / 180.0
    val pointX = longitudeDelta(start, point) * scaleLon * degreeMetres
    val pointY = (latitudeOf(point) - latitudeOf(start)) * degreeMetres
    val endX = longitudeDelta(start, end) * scaleLon * degreeMetres
    val endY = (latitudeOf(end) - latitudeOf(start)) * degreeMetres
    val segmentSquared = endX * endX + endY * endY
    if (segmentSquared == 0.0) return hypot(pointX, pointY)
    // The projection is clamped to the segment because the distance to the line it lies on is not
    // the same thing: on an out-and-back the turnaround sits on that line, and measured against it
    // the whole outbound leg reads as zero and disappears from the export.
    val along = ((pointX * endX + pointY * endY) / segmentSquared).coerceIn(0.0, 1.0)
    return hypot(pointX - along * endX, pointY - along * endY)
}

/** Degrees of longitude, wrapped: two fixes either side of the antimeridian are metres apart. */
private fun longitudeDelta(from: TrackPointEntity, to: TrackPointEntity): Double {
    val raw = longitudeOf(to) - longitudeOf(from)
    return raw - 360.0 * round(raw / 360.0)
}
