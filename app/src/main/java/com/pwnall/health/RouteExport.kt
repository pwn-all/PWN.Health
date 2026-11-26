package com.pwnall.health

import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/**
 * The route as a GeoJSON feature — the format every map library and GIS tool reads without a plugin.
 *
 * A `LineString` with the track's own numbers as properties, simplified the same way GPX is, or a
 * `MultiLineString` when the recording was paused: one line for each stretch walked between pauses,
 * and none across the time it stood still — nor across the antimeridian, see [cutAtAntimeridian].
 * The coordinate order is longitude first: that is what the specification says, and getting it
 * backwards is the single most common way a GeoJSON file ends up drawn in the ocean.
 *
 * Written out rather than assembled with JSONObject. Nothing here is user text, so nothing
 * needs escaping, and the layout line by line is what the tests read; the real org.json is on the
 * test classpath, so a test may parse the result as well.
 */
fun geoJsonOf(track: TrackEntity, points: List<TrackPointEntity>): String {
    val segments = exportableSegments(points).flatMap { segment ->
        val positions = segment.map { point ->
            GeoPosition(
                point.correctedLon ?: point.lon,
                point.correctedLat ?: point.lat,
                point.barometricAltitudeM?.toDouble() ?: point.altitudeM,
            )
        }
        cutAtAntimeridian(positions).map { line -> line.map(GeoPosition::written) }
    }
    val geometry = routeGeometry(segments)
    // Appended line by line rather than one trimIndent block — trimIndent measures the
    // interpolated lines too, and the optional end time would shift the whole document's indent.
    return buildString {
        appendLine("{")
        appendLine("  \"type\": \"Feature\",")
        appendLine("  \"properties\": {")
        appendLine("    \"startedAt\": \"${Instant.ofEpochMilli(track.startedAt)}\",")
        track.endedAt?.let { appendLine("    \"endedAt\": \"${Instant.ofEpochMilli(it)}\",") }
        appendLine("    \"distanceMeters\": ${metres(track.distanceMeters)},")
        appendLine("    \"elevationGainM\": ${metres(track.elevationGainM)},")
        appendLine("    \"elevationLossM\": ${metres(track.elevationLossM)},")
        appendLine("    \"points\": ${geometry?.positions ?: 0}")
        appendLine("  },")
        if (geometry == null) {
            appendLine("  \"geometry\": null")
        } else {
            appendLine("  \"geometry\": {")
            appendLine("    \"type\": \"${geometry.type}\",")
            appendLine("    \"coordinates\": ${geometry.coordinates}")
            appendLine("  }")
        }
        appendLine("}")
    }
}

/** One position of a route as GeoJSON writes it: longitude first, then latitude, then the height if any. */
internal data class GeoPosition(val lon: Double, val lat: Double, val elevation: Double?) {
    fun written(): String = "[${coord(lon)}, ${coord(lat)}${elevation?.let { ", ${metres(it)}" }.orEmpty()}]"
}

/**
 * [line] cut wherever it crosses the antimeridian, as RFC 7946 §3.1.9 asks: a leg from 179.9° to
 * −179.9° written as it stands is drawn by a GIS tool the long way, across the whole world. The piece
 * before the crossing ends on the antimeridian and the next begins on it from the other side, at the
 * latitude and height the leg had there. A line that never crosses comes back whole.
 */
internal fun cutAtAntimeridian(line: List<GeoPosition>): List<List<GeoPosition>> {
    val pieces = mutableListOf(mutableListOf<GeoPosition>())
    for (index in line.indices) {
        val here = line[index]
        val before = line.getOrNull(index - 1)
        if (before != null && abs(here.lon - before.lon) > ANTIMERIDIAN) {
            val edge = if (before.lon > 0) ANTIMERIDIAN else -ANTIMERIDIAN
            val share = (edge - before.lon) / (unwrapLongitude(here.lon, before.lon) - before.lon)
            val lat = before.lat + (here.lat - before.lat) * share
            val from = before.elevation
            val to = here.elevation
            val elevation = if (from != null && to != null) from + (to - from) * share else null
            pieces.last() += GeoPosition(edge, lat, elevation)
            pieces += mutableListOf(GeoPosition(-edge, lat, elevation))
        }
        pieces.last() += here
    }
    return pieces
}

private const val ANTIMERIDIAN = 180.0

/** A GeoJSON geometry as written: its type, its coordinates, and how many positions they hold. */
private class RouteGeometry(val type: String, val coordinates: String, val positions: Int)

/**
 * The geometry of [segments] — each a stretch's positions, already written out — or null when there
 * is nothing to draw.
 *
 * RFC 7946 gives a line two positions or more, so a stretch of a single fix, resumed and stopped at
 * once, has no line to give and is left out when another stretch has one. A route with no stretch
 * long enough is a Point, or a MultiPoint across pauses, and one with nothing left to draw has no
 * geometry at all — either written as a line is a file that every GIS tool refuses to open.
 */
private fun routeGeometry(segments: List<List<String>>): RouteGeometry? {
    fun array(items: List<String>) = items.joinToString(", ", "[", "]")
    val lines = segments.filter { it.size >= 2 }
    val positions = segments.flatten()
    return when {
        lines.size > 1 -> RouteGeometry("MultiLineString", array(lines.map(::array)), lines.sumOf { it.size })
        lines.size == 1 -> RouteGeometry("LineString", array(lines.single()), lines.single().size)
        positions.size > 1 -> RouteGeometry("MultiPoint", array(positions), positions.size)
        positions.size == 1 -> RouteGeometry("Point", positions.single(), 1)
        else -> null
    }
}

/**
 * Every recorded fix as a row, including the ones the filter rejected.
 *
 * This is the export that does not decide anything for you: no simplification and no filtering, with
 * `accepted` and `filterReason` alongside each fix so a rejection can be argued with in a
 * spreadsheet, and `segmentStart` on the first fix the line took up again after a pause. GPX and
 * GeoJSON are for maps; this is for checking the app's work.
 */
fun csvOf(points: List<TrackPointEntity>): String = buildString {
    appendLine(
        "timestamp,lat,lon,correctedLat,correctedLon,altitudeM,barometricAltitudeM," +
            "accuracyM,speedMps,movementType,accepted,filterReason,segmentStart",
    )
    points.sortedBy { it.timestamp }.forEach { point ->
        appendLine(
            listOf(
                Instant.ofEpochMilli(point.timestamp).toString(),
                coord(point.lat),
                coord(point.lon),
                point.correctedLat?.let(::coord).orEmpty(),
                point.correctedLon?.let(::coord).orEmpty(),
                point.altitudeM.metres(),
                point.barometricAltitudeM?.toDouble().metres(),
                point.accuracyM?.toDouble().metres(),
                point.speedMps?.toDouble()?.let { "%.2f".format(Locale.US, it) }.orEmpty(),
                point.movementType,
                if (point.accepted) "true" else "false",
                point.filterReason,
                if (point.segmentStart) "true" else "false",
            ).joinToString(",", transform = ::csvField),
        )
    }
}

private fun metres(value: Double) = "%.1f".format(Locale.US, value)

private fun Double?.metres() = this?.let(::metres).orEmpty()

/** RFC 4180: quote when the value could otherwise end the field, and double any quote inside it. */
private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
