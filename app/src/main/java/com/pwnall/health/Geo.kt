package com.pwnall.health

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round

/**
 * The sphere the route maths runs on. [haversineMetres] in Facilities.kt keeps the IUGG mean radius
 * its tests pin; the eight metres between the two are a part in a million.
 */
internal const val EARTH_RADIUS_M = 6_371_000.0

/**
 * A longitude difference folded across the antimeridian: the raw difference between 179.9° and
 * −179.9° is about 360°, which reads as a leg of tens of thousands of kilometres.
 */
internal fun wrappedLongitudeDegrees(raw: Double): Double = raw - 360.0 * round(raw / 360.0)

/**
 * Equirectangular distance. Over the tens of metres between two fixes, and the few hundred between
 * a fix and a workplace, the error against the great circle is millimetres, and it costs two
 * trigonometric calls instead of six.
 */
internal fun equirectangularMetres(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
    val a = fromLat * PI / 180.0
    val b = toLat * PI / 180.0
    val deltaLon = wrappedLongitudeDegrees(toLon - fromLon) * PI / 180.0
    return hypot(deltaLon * cos((a + b) / 2.0) * EARTH_RADIUS_M, (b - a) * EARTH_RADIUS_M)
}
