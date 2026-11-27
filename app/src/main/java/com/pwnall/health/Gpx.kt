package com.pwnall.health

import java.time.Instant
import java.util.Locale

/**
 * GPX 1.1 for one recorded route.
 *
 * The whole-app JSON backup is for this app only; GPX is what every other piece of software on earth
 * can open, which is the difference between "your data is yours" and "your data is yours in a format
 * nobody reads". Corrected coordinates are exported when the filter produced them, because those are
 * the ones the distance was measured from; rejected fixes are left out.
 *
 * Each stretch walked between pauses is a segment of its own, so no reader draws a line across the
 * time the recording stood still — the walker may have gone anywhere in it.
 *
 * The line is simplified on the way out — see [simplifyRoute]. Distance and elevation were already
 * measured from every fix, so nothing here can shorten them.
 */
fun gpxOf(track: TrackEntity, points: List<TrackPointEntity>): String {
    // Built line by line rather than as one raw string: trimIndent measures the interpolated
    // point lines too, which would shift the XML declaration off column zero.
    val startedAt = Instant.ofEpochMilli(track.startedAt)
    val segments = exportableSegments(points)
    return buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<gpx version=\"1.1\" creator=\"PWN.Health\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        appendLine("  <metadata>")
        appendLine("    <time>$startedAt</time>")
        appendLine("  </metadata>")
        // Every GPX reader takes <ele> as height above sea level. A raw Android fix carries height
        // above the WGS84 ellipsoid instead — up to a hundred metres from the sea, depending on
        // where on earth — and only Android 14's fused provider adds the sea-level figure, so the
        // file says which one it holds rather than let a reader assume.
        elevationDatumNote(segments.flatten())?.let { appendLine("  <!-- $it -->") }
        appendLine("  <trk>")
        appendLine("    <name>$startedAt</name>")
        // A route with nothing accepted still gets one empty segment.
        segments.ifEmpty { listOf(emptyList()) }.forEach { segment ->
            appendLine("    <trkseg>")
            segment.forEach { point ->
                val lat = coord(point.correctedLat ?: point.lat)
                val lon = coord(point.correctedLon ?: point.lon)
                appendLine("      <trkpt lat=\"$lat\" lon=\"$lon\">")
                (point.barometricAltitudeM?.toDouble() ?: point.altitudeM)?.let {
                    appendLine("        <ele>${"%.1f".format(Locale.US, it)}</ele>")
                }
                appendLine("        <time>${Instant.ofEpochMilli(point.timestamp)}</time>")
                appendLine("      </trkpt>")
            }
            appendLine("    </trkseg>")
        }
        appendLine("  </trk>")
        appendLine("</gpx>")
    }
}

internal fun coord(value: Double) = "%.7f".format(Locale.US, value)

/** What the <ele> values are heights above, from the fixes that carry one; null when none does. */
internal fun elevationDatumNote(route: List<TrackPointEntity>): String? {
    val measured = route.filter { it.barometricAltitudeM != null || it.altitudeM != null }
    return when {
        measured.isEmpty() -> null
        measured.all { it.altitudeMsl } -> "ele: height above mean sea level"
        measured.none { it.altitudeMsl } -> "ele: height above the WGS84 ellipsoid, not above mean sea level"
        else -> "ele: mean sea level where the device gave it, height above the WGS84 ellipsoid elsewhere"
    }
}

/**
 * The accepted fixes in time order, as the stretches walked between pauses: a new one starts at each
 * fix marked [TrackPointEntity.segmentStart], and the first starts with the route. Empty when nothing
 * was accepted. A map draws a line along each stretch and none from one to the next.
 */
internal fun routeSegments(points: List<TrackPointEntity>): List<List<TrackPointEntity>> {
    val segments = mutableListOf<MutableList<TrackPointEntity>>()
    points.filter { it.accepted }.sortedBy { it.timestamp }.forEach { fix ->
        if (fix.segmentStart || segments.isEmpty()) segments += mutableListOf(fix) else segments.last() += fix
    }
    return segments
}

/**
 * [routeSegments], each thinned to its own shape. Simplified as one line, the fixes either side of a
 * pause would be measured against a chord drawn across it, and kept or dropped by a line nobody walked.
 */
internal fun exportableSegments(points: List<TrackPointEntity>): List<List<TrackPointEntity>> =
    routeSegments(points).map { simplifyRoute(it) }
