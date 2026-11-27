package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The in-chat body map's arithmetic: a tap is kept as a dab whose every reader names the areas under
// the finger, a drag as it was painted; the areas a chat painting records; where the map opens framed.
// Plain arithmetic over the hit grid of PainRegions.kt, so all of it is tested on the JVM.

/** How far from a tapped point the four points beside it are looked at, in figure units. */
private const val NEAR = 1e-4f

/**
 * The areas under a finger at [point] of [view] on [map]: the area there and at four points 1e-4
 * away, the one found most often — every one found as often, when they tie. Empty off the body.
 */
internal fun areasNear(view: PainView, point: Offset, map: PainMapVersion = PainMapVersion.CURRENT): Set<RegionSide> {
    val around = listOf(
        point,
        Offset(point.x + NEAR, point.y),
        Offset(point.x - NEAR, point.y),
        Offset(point.x, point.y + NEAR),
        Offset(point.x, point.y - NEAR),
    )
    val counts = around.mapNotNull { areaAt(view, it, map) }.groupingBy { it }.eachCount()
    val most = counts.values.maxOrNull() ?: return emptySet()
    return counts.filterValues { it == most }.keys.toSortedSet(REGION_SIDE_ORDER)
}

/** A touch whose path stays within half its radius of where it landed: a tap, not a stroke. */
internal fun isTap(stroke: PainStroke): Boolean {
    val start = stroke.points.firstOrNull() ?: return false
    val reach = stroke.radius / 2f
    return stroke.points.all { distanceOnPlate(it, start) <= reach }
}

/** How far apart two figure points are, in plate heights. */
private fun distanceOnPlate(a: Offset, b: Offset): Float {
    val dx = (a.x - b.x) * PLATE_ASPECT
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/** Thousandths: what the payload stores a point and a radius in. */
private const val THOUSANDTHS = 1_000

/**
 * A touch as the chat keeps it. A drag is kept exactly as painted. A tap becomes a dab of one point,
 * quantised as the payload stores it, at the largest radius in whole thousandths, no larger than the
 * brush, at which every reader of the stroke ([paintedRegions]) names the areas under the finger
 * ([areasNear]); a tap on no area is no stroke at all (null).
 *
 * Where no dab at the finger itself names them — a tap on a corner where the cells of other areas
 * meet, which no whole thousandth of a radius can single out — the dab stands at the centre of the
 * finger's cell instead, a few thousandths away, where the smallest one names its area alone.
 */
internal fun chatStroke(stroke: PainStroke, map: PainMapVersion = PainMapVersion.CURRENT): PainStroke? {
    if (!isTap(stroke)) return stroke
    val point = quantised(stroke.points.first())
    val want = areasNear(stroke.view, point, map)
    if (want.isEmpty()) return null
    val brush = (stroke.radius * THOUSANDTHS).roundToInt().coerceAtLeast(1)
    return dabAt(stroke.view, point, want, brush, map) ?: cellDab(stroke.view, point, want, brush, map)
}

/** The widest dab at [point], [brush] thousandths at most, whose every reader names [want]. */
private fun dabAt(view: PainView, point: Offset, want: Set<RegionSide>, brush: Int, map: PainMapVersion): PainStroke? =
    (brush downTo 1).firstNotNullOfOrNull { radius ->
        PainStroke(view, radius / THOUSANDTHS.toFloat(), listOf(point))
            .takeIf { paintedRegions(listOf(it), map).containsAll(want) }
    }

/** The dab at the centre of the cell under the finger that holds one of [want]: what [chatStroke] falls back on. */
private fun cellDab(
    view: PainView,
    point: Offset,
    want: Set<RegionSide>,
    brush: Int,
    map: PainMapVersion,
): PainStroke? {
    val near = listOf(
        point,
        Offset(point.x + NEAR, point.y),
        Offset(point.x - NEAR, point.y),
        Offset(point.x, point.y + NEAR),
        Offset(point.x, point.y - NEAR),
    )
    val under = near.firstOrNull { areaAt(view, it, map) in want } ?: return null
    val column = floor(under.x * GRID_COLUMNS).coerceIn(0f, GRID_COLUMNS - 1f)
    val row = floor((under.y - FIGURE_TOP) / FIGURE_HEIGHT * GRID_ROWS).coerceIn(0f, GRID_ROWS - 1f)
    val x = (column + 0.5f) / GRID_COLUMNS
    val y = FIGURE_TOP + (row + 0.5f) * FIGURE_HEIGHT / GRID_ROWS
    val centre = quantised(Offset(x, y))
    return dabAt(view, centre, areasNear(view, centre, map), brush, map)
}

/**
 * The areas a chat painting records: under each dab the areas near its point that the dab marks —
 * or, where it marks none of them, what it marks — and what the drags mark. Every reader of the
 * stored strokes then agrees with it: a dab's areas are among the areas its stroke marks.
 */
internal fun chatMarkedAreas(strokes: List<PainStroke>, map: PainMapVersion = PainMapVersion.CURRENT): Set<RegionSide> {
    val (dabs, drags) = strokes.partition { it.points.size == 1 }
    val fromDabs = dabs.flatMap { dab ->
        val painted = paintedRegions(listOf(dab), map)
        (areasNear(dab.view, dab.points.first(), map) intersect painted).ifEmpty { painted }
    }
    return (fromDabs + paintedRegions(drags, map)).toSortedSet(REGION_SIDE_ORDER)
}

/** The areas the map records: what its painting marks, less what was struck off, plus what was added. */
internal fun chatMapAreas(strokes: List<PainStroke>, added: List<String>, removed: List<String>): Set<RegionSide> =
    painMarkedAreas(
        chatMarkedAreas(strokes),
        added.mapNotNull(RegionSide::parse),
        removed.mapNotNull(RegionSide::parse),
    )

/** Those areas as the area answer keeps them: codes, head to foot, comma-joined. */
internal fun chatMapCodes(strokes: List<PainStroke>, added: List<String>, removed: List<String>): String =
    chatMapAreas(strokes, added, removed).joinToString(",") { it.code }

/**
 * An answer's areas split back into the map's two lists beside its painting: those added by hand,
 * and those the painting marks that the answer does not record — read the chat's own way
 * ([chatMarkedAreas]), so a map opened again records exactly what was sent.
 */
internal fun chatMapLists(codes: String?, strokes: List<PainStroke>): Pair<List<String>, List<String>> {
    val recorded = codes.orEmpty().split(',').mapNotNull { RegionSide.parse(it.trim()) }.toSet()
    val painted = chatMarkedAreas(strokes)
    return (recorded - painted).sortedWith(REGION_SIDE_ORDER).map { it.code } to
        (painted - recorded).sortedWith(REGION_SIDE_ORDER).map { it.code }
}

/** How much room is left around a framed family: fifteen per cent of its size, shared on both sides. */
private const val FRAME_MARGIN = 0.15f

/**
 * Where the map opens framed on [frame]'s family, in figure units: the bounds of the family's patches
 * on the frame's view, with [FRAME_MARGIN] about them. Null for the whole body — no family named, or
 * one no plate draws (the head).
 */
internal fun painFrameTarget(frame: PainFrame, map: PainMapVersion = PainMapVersion.CURRENT): Rect? {
    val family = frame.family ?: return null
    val points = sidePatches(frame.view, map).filter { family in bodyFamilies(it.area.region) }.flatMap { it.points }
    return boundsOf(points)?.let(::withMargin)
}

/**
 * Where a sent painting's figure on [view] is framed: its strokes there, each point with its brush
 * about it, and the patches of [areas] chosen without painting; null when the view shows neither.
 */
internal fun painMarksTarget(
    view: PainView,
    strokes: List<PainStroke>,
    areas: Collection<RegionSide>,
    map: PainMapVersion = PainMapVersion.CURRENT,
): Rect? {
    val painted = strokes.filter { it.view == view }.flatMap { stroke ->
        stroke.points.flatMap { point ->
            val across = stroke.radius / PLATE_ASPECT
            listOf(
                Offset(point.x - across, point.y - stroke.radius),
                Offset(point.x + across, point.y + stroke.radius),
            )
        }
    }
    val listed = sidePatches(view, map).filter { it.area in areas }.flatMap { it.points }
    return boundsOf(painted + listed)?.let(::withMargin)
}

private fun boundsOf(points: List<Offset>): Rect? {
    if (points.isEmpty()) return null
    return Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
}

private fun withMargin(rect: Rect): Rect {
    val dx = rect.width * FRAME_MARGIN / 2f
    val dy = rect.height * FRAME_MARGIN / 2f
    return Rect(rect.left - dx, rect.top - dy, rect.right + dx, rect.bottom + dy)
}
