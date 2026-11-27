package com.pwnall.health

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The geometry between the text detector's probability map and the crops the recognizer reads.
 *
 * A photographed packet is never level: a table shot by hand sits a few degrees off, and a
 * cylindrical can bends its rows further still. An upright rectangle around a tilted line of text
 * swallows the background above it and the neighbouring row below — on one packet photograph the
 * same detector and recognizer read "Výivové údajnavýrobk" through an upright box and
 * "Výživové údaje na 100 ml výrobku/" through the line's own orientation. So the region a
 * component describes is an oriented rectangle, and the crop is sampled along its axes.
 *
 * Limitation: an oriented rectangle, not the arbitrary quadrilateral PaddleOCR unclips with a
 * polygon offsetter — the detector's blobs are lines of text, and a rectangle is what a line of
 * text is. No OpenCV either: the hull and the calipers below are the whole geometry library.
 */
internal data class Region(
    /** Centre, in raster pixels. */
    val cx: Double,
    val cy: Double,
    /** Half of the long axis: the direction the text runs, and how far it runs. */
    val ux: Double,
    val uy: Double,
    /** Half of the short axis: across the line, from baseline to cap height and a little beyond. */
    val vx: Double,
    val vy: Double,
) {
    /** How long the line is, and how tall — for ordering, filtering and the crop's aspect. */
    val length: Double get() = hypot(ux, uy) * 2
    val thickness: Double get() = hypot(vx, vy) * 2

    /**
     * The upright box that contains the oriented one: what reading order and assembly use.
     *
     * Outward on both edges, because containment is the promise. The unclip pushes a region past
     * the frame edge, so a corner at -2.5 is ordinary, and rounding it toward zero would start the
     * box inside the region it is supposed to hold.
     */
    val bounds: Box
        get() {
            val dx = abs(ux) + abs(vx)
            val dy = abs(uy) + abs(vy)
            return Box(floor(cx - dx).toInt(), floor(cy - dy).toInt(), ceil(cx + dx).toInt(), ceil(cy + dy).toInt())
        }
}

/** One point of a detected component's outline, in detection-map pixels. */
internal data class Point(val x: Int, val y: Int)

/**
 * A connected blob of the detection mask: the outline points the hull needs, how many pixels it
 * covered, and how confident the detector was across them.
 */
internal class Component(val points: List<Point>, val pixels: Int, private val probability: Double) {
    val score: Double get() = if (pixels == 0) 0.0 else probability / pixels
}

/**
 * The oriented region for a component, unclipped the way DB postprocessing does it — outward by
 * area x ratio / perimeter — and mapped into raster pixels.
 *
 * The detection map is resized per axis to a multiple of the model's stride, so the two scales
 * differ slightly; mapping the axes rather than the corners keeps that anisotropy honest.
 */
internal fun Component.region(sx: Double, sy: Double): Region? {
    if (pixels < MIN_COMPONENT_PIXELS || score < BOX_THRESHOLD) return null
    val hull = hullOf(points)
    val rect = hull.takeIf { it.size >= 2 }?.let(::textRect) ?: return null
    val offset = rect.width * rect.height * UNCLIP_RATIO / (2.0 * (rect.width + rect.height))
    val half = (rect.width + 2 * offset) / 2
    val across = (rect.height + 2 * offset) / 2
    return if (half < MIN_SIDE || across < MIN_SIDE) {
        null
    } else {
        Region(
            cx = rect.cx * sx,
            cy = rect.cy * sy,
            ux = cos(rect.angle) * half * sx,
            uy = sin(rect.angle) * half * sy,
            vx = -sin(rect.angle) * across * sx,
            vy = cos(rect.angle) * across * sy,
        )
    }
}

/**
 * The rectangle to read a component through.
 *
 * A hand-held photograph tilts a few degrees; it does not tilt thirty. A minimum-area rectangle
 * claiming more is not a tilted line of text but a blob whose long side points elsewhere — two
 * rows fused into a square, a logo, a barcode — and sampling along that axis reads the text
 * sideways. Those keep an upright rectangle, and the reading ladder's quarter turns stay the
 * answer to genuinely sideways print. Without this a Japanese carton can lose its energy row
 * entirely.
 */
private fun textRect(hull: List<Point>): Rect? {
    val tilted = minAreaRect(hull)?.folded() ?: return null
    return if (abs(tilted.angle) <= MAX_TILT) tilted else boundingRect(hull, 1.0, 0.0)
}

/** The minimum-area rectangle around a hull, its long side taken as the direction of the text. */
private class Rect(val cx: Double, val cy: Double, val width: Double, val height: Double, val angle: Double)

/** The same rectangle with its angle folded into (-90°, 90°], where tilt is comparable. */
private fun Rect.folded(): Rect {
    var tilt = angle
    while (tilt > HALF_PI) tilt -= Math.PI
    while (tilt <= -HALF_PI) tilt += Math.PI
    return Rect(cx, cy, width, height, tilt)
}

/**
 * Rotating calipers: the minimum-area rectangle around a convex hull always shares an edge with
 * it, so every edge is tried as the rectangle's direction and the narrowest wins.
 */
private fun minAreaRect(hull: List<Point>): Rect? {
    var best: Rect? = null
    var bestArea = Double.MAX_VALUE
    for (index in hull.indices) {
        val from = hull[index]
        val to = hull[(index + 1) % hull.size]
        val edge = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
        if (edge < 1e-9) continue
        val ux = (to.x - from.x) / edge
        val uy = (to.y - from.y) / edge
        val rect = boundingRect(hull, ux, uy)
        val area = rect.width * rect.height
        if (area < bestArea) {
            bestArea = area
            best = rect
        }
    }
    return best?.let { if (it.width >= it.height) it else Rect(it.cx, it.cy, it.height, it.width, it.angle + HALF_PI) }
}

/** The hull's extent along one direction and across it, as a rectangle in the original frame. */
private fun boundingRect(hull: List<Point>, ux: Double, uy: Double): Rect {
    var minU = Double.MAX_VALUE
    var maxU = -Double.MAX_VALUE
    var minV = Double.MAX_VALUE
    var maxV = -Double.MAX_VALUE
    for (point in hull) {
        val u = point.x * ux + point.y * uy
        val v = -point.x * uy + point.y * ux
        if (u < minU) minU = u
        if (u > maxU) maxU = u
        if (v < minV) minV = v
        if (v > maxV) maxV = v
    }
    val centreU = (minU + maxU) / 2
    val centreV = (minV + maxV) / 2
    return Rect(
        cx = centreU * ux - centreV * uy,
        cy = centreU * uy + centreV * ux,
        width = maxU - minU,
        height = maxV - minV,
        angle = atan2(uy, ux),
    )
}

/**
 * Andrew's monotone chain. The points fed in are each row's leftmost and rightmost pixel, which
 * is all a hull can ever need: a vertex that is neither cannot be extreme in any direction.
 */
internal fun hullOf(points: List<Point>): List<Point> {
    val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
    if (sorted.size <= 2) return sorted
    val lower = chainOf(sorted)
    val upper = chainOf(sorted.asReversed())
    return lower.dropLast(1) + upper.dropLast(1)
}

private fun chainOf(sorted: List<Point>): List<Point> {
    val chain = mutableListOf<Point>()
    for (point in sorted) {
        while (chain.size >= 2 && !turnsRight(chain[chain.size - 2], chain[chain.size - 1], point)) {
            chain.removeAt(chain.size - 1)
        }
        chain.add(point)
    }
    return chain
}

private fun turnsRight(a: Point, b: Point, c: Point): Boolean =
    (b.x - a.x).toLong() * (c.y - a.y) - (b.y - a.y).toLong() * (c.x - a.x) > 0

/**
 * Connected components of the detection mask, by iterative flood fill.
 *
 * Only each row's extreme pixels are kept: they carry the hull exactly, and a full page of text
 * blobs then costs a few thousand points instead of a few hundred thousand.
 */
internal fun componentsOf(probabilities: FloatArray, width: Int, height: Int): List<Component> {
    val seen = BooleanArray(width * height)
    val leftmost = IntArray(height) { -1 }
    val rightmost = IntArray(height) { -1 }
    val components = mutableListOf<Component>()
    for (start in probabilities.indices) {
        if (seen[start] || probabilities[start] <= MASK_THRESHOLD) continue
        components.add(fillFrom(start, probabilities, width, seen, leftmost, rightmost))
    }
    return components
}

private fun fillFrom(
    start: Int,
    probabilities: FloatArray,
    width: Int,
    seen: BooleanArray,
    leftmost: IntArray,
    rightmost: IntArray,
): Component {
    val stack = ArrayDeque<Int>()
    seen[start] = true
    stack.addLast(start)
    var pixels = 0
    var probability = 0.0
    var top = Int.MAX_VALUE
    var bottom = Int.MIN_VALUE
    while (stack.isNotEmpty()) {
        val index = stack.removeLast()
        val x = index % width
        val y = index / width
        pixels++
        probability += probabilities[index]
        if (y < top) top = y
        if (y > bottom) bottom = y
        if (leftmost[y] < 0 || x < leftmost[y]) leftmost[y] = x
        if (rightmost[y] < 0 || x > rightmost[y]) rightmost[y] = x
        offerNeighbours(index, x, width, probabilities, seen, stack)
    }
    val points = mutableListOf<Point>()
    for (y in top..bottom) {
        if (leftmost[y] < 0) continue
        points.add(Point(leftmost[y], y))
        points.add(Point(rightmost[y], y))
        leftmost[y] = -1
        rightmost[y] = -1
    }
    return Component(points, pixels, probability)
}

private fun offerNeighbours(
    index: Int,
    x: Int,
    width: Int,
    probabilities: FloatArray,
    seen: BooleanArray,
    stack: ArrayDeque<Int>,
) {
    if (x > 0) offer(index - 1, probabilities, seen, stack)
    if (x < width - 1) offer(index + 1, probabilities, seen, stack)
    offer(index - width, probabilities, seen, stack)
    offer(index + width, probabilities, seen, stack)
}

private fun offer(neighbour: Int, probabilities: FloatArray, seen: BooleanArray, stack: ArrayDeque<Int>) {
    val inside = neighbour >= 0 && neighbour < probabilities.size && !seen[neighbour]
    if (inside && probabilities[neighbour] > MASK_THRESHOLD) {
        seen[neighbour] = true
        stack.addLast(neighbour)
    }
}

/** The page as one text per region, top to bottom: the parser's wrap carry-over pairs the rest. */
internal fun List<TextBox>.asBoxText(): String = joinToString("\n") { it.text }

/**
 * The page reassembled into printed lines: regions sharing a vertical band join left to right, so
 * "Energie/Energia" meets its "267 kJ/64 kcal" on one line the way the table printed them.
 *
 * A row is anchored by the region that opened it and never re-averaged. An anchor that drifted
 * with each addition — and a band that grew to the tallest member — would walk down the page and
 * swallow the whole lower half of a table into one unreadable line, eight row names followed by
 * twelve figures that no parser could pair again.
 */
internal fun List<TextBox>.asLineText(): String =
    rowsOfText().joinToString("\n") { row -> row.joinToString(" ") { it.text } }

/**
 * The same rows, with a lone figure handed to the row that names it.
 *
 * A US panel prints its calories as the loudest thing on the packet — "250" on its own line, the
 * word "Calories" on the line below — and no rule that reads one line at a time can pair them: the
 * figure arrives before the word that owns it. So a row that names a nutrient and prints no figure
 * of its own looks at the row immediately above and below, and adopts one that is nothing but a
 * number.
 *
 * Only a row that ends in a figure qualifies — a table column looks like that and a sentence does
 * not — and only for a row that named something and got nothing: a row that already carries its
 * figure is never touched, and a number that belongs to a neighbour's text stays with that
 * neighbour.
 */
internal fun List<TextBox>.asTableText(): String {
    val rows = rowsOfText().map { row -> row.joinToString(" ") { it.text } }
    val lent = mutableSetOf<Int>()
    return rows.mapIndexed { index, line ->
        if (line.hasDigit() || !line.namesNutrient()) {
            line
        } else {
            val donor = listOf(index - 1, index + 1)
                .filter { it in rows.indices && it !in lent && rows[it].lendable() }
                .minByOrNull { rows[it].length }
            val figure = donor?.let { rows[it].lastFigure() }
            if (figure == null) line else "$line $figure".also { lent += donor }
        }
    }.joinToString("\n")
}

/**
 * Whether this row's figure may be borrowed by the row that names a nutrient beside it. A row
 * naming a nutrient of its own never lends — "Total Fat 12 g" owns that 12 — and a row with no
 * borrowable figure never claims to have one: a row of fullwidth digits passes [hasDigit] and
 * hands over nothing, and as the shorter neighbour it would otherwise win the choice and then leave
 * the nutrient with the empty half of a Japanese carton's energy row.
 */
private fun String.lendable(): Boolean = lastFigure() != null && !namesNutrient()

/**
 * The figure the row ends with, optionally with its unit: "Amount per serving 250" hands over the
 * 250, and the 55 of "Serving size 2/3 cup (55g)" stays with that prose, because a table column
 * ends in its number and a sentence carries the number in the middle.
 */
private fun String.lastFigure(): String? = TRAILING_FIGURE.find(trim())?.groupValues?.get(1)

private val TRAILING_FIGURE = Regex(
    """([0-9]{1,4}(?:[.,][0-9]{1,2})?)$SPACE*(?:kj|kcal|g|mg|ml|кдж|ккал|г|мг|мл)?$""",
    RegexOption.IGNORE_CASE,
)

/**
 * The table with only one column of figures left standing: the one the header says is per 100 g.
 *
 * An Australian or Gulf panel prints two — "Avg quantity per serving | Avg quantity per 100 g" —
 * and a parser reading left to right takes the serving column while believing it read the other.
 * The header names which is which and the figures sit under their own heading, so the column is
 * decided by where a number is on the packet, not by which came first in the reading order.
 *
 * Returns null where there is nothing to disambiguate: one column, or no header naming a basis.
 */
internal fun List<TextBox>.asPerHundredColumn(): String? {
    val rows = rowsOfText()
    val header = rows.firstNotNullOfOrNull { row ->
        row.firstOrNull { PER_HUNDRED_HEAD.containsMatchIn(it.text.lowercase()) }
    } ?: return null
    val centre = (header.box.x0 + header.box.x1) / 2
    val kept = rows.map { row ->
        val figures = row.filter { FIGURE_ONLY.matches(it.text.trim()) }
        val nearest = figures.takeIf { it.size >= 2 }
            ?.minByOrNull { abs((it.box.x0 + it.box.x1) / 2 - centre) }
        if (nearest == null) row else row.filter { it !in figures || it === nearest }
    }
    val dropped = kept.withIndex().any { (index, row) -> row.size < rows[index].size }
    // The header itself is the parser's evidence that what is left is per 100 g.
    return kept.joinToString("\n") { row -> row.joinToString(" ") { it.text } }
        .plus("\nper 100 g")
        .takeIf { dropped }
}

/**
 * The loudest figure on the page, when the page is a nutrition panel.
 *
 * A US panel sets its calories in the largest type on the packet, which is how a person finds them
 * across a shop aisle — and how the reader finds them when the small word beside them is lost. A
 * tuna tin photographed at arm's length can come back with "110" at twice the height of every other
 * box and no "Calories" anywhere: the figure survives the distance, the label does not.
 *
 * Loudest is tallest, never largest: a "100" split out of a "per 100 g" heading is the bigger
 * number and the smaller type, and on a carton it can sit beside a 64 kcal set twice its height.
 * The plausibility filter runs before the choice, so a tall net weight cannot win and then yield
 * nothing.
 *
 * Null unless the page looks like a panel at all, and unless one figure really does stand out.
 */
internal fun List<TextBox>.headlineFigure(): Double? {
    val anchored = any { box -> PANEL_WORDS.any(box.text.lowercase()::contains) }
    val heights = map { it.box.y1 - it.box.y0 }.filter { it > 0 }.sorted()
    if (!anchored || heights.size < MIN_PANEL_BOXES) return null
    val median = heights[heights.size / 2]
    return filter { box ->
        (box.box.y1 - box.box.y0) >= median * HEADLINE_HEIGHT && FIGURE_ONLY.matches(box.text.trim())
    }.mapNotNull { box ->
        val figure = box.text.trim().toDoubleOrNull()
        if (figure != null && figure in MIN_HEADLINE_KCAL..MAX_HEADLINE_KCAL) box to figure else null
    }.maxByOrNull { it.first.box.y1 - it.first.box.y0 }?.second
}

/**
 * A heading naming the 100 g column, in more languages than English and Russian: pro and je head it
 * in Czech and German, pour in French, por in Spanish and Portuguese, na in Czech, Slovak and the
 * South Slavic languages, w in Polish. Those only as words of their own and never before a
 * percent — "w 100%" on a Polish claim heads no column.
 */
private val PER_HUNDRED_HEAD =
    Regex(
        """per$SPACE*100|/$SPACE*100|на$SPACE*100|в$SPACE*100|""" +
            """(?<!\p{L})(?:pro|je|pour|por|na|w)$SPACE*100(?![0-9]|$SPACE*%)""",
    )

/**
 * A box that is a figure and nothing else — or a figure with its unit, which is how the OCR most
 * often boxes "593 kJ" and "3.4 g" on a two-column panel. Bare figures alone would make the column
 * rule blind to exactly the tables it is written for.
 */
private val FIGURE_ONLY = Regex(
    """[0-9]{1,4}(?:[.,][0-9]{1,2})?$SPACE*(?:kj|kcal|g|mg|ml|кдж|ккал|г|мг|мл)?""",
    RegexOption.IGNORE_CASE,
)
private val PANEL_WORDS = listOf("nutrition", "facts", "calor", "serving", "daily value", "энергет", "пищевая")

/** Twice the median line is a headline; a little over one and a half is already unmistakable. */
private const val HEADLINE_HEIGHT = 1.5
private const val MIN_PANEL_BOXES = 8
private const val MIN_HEADLINE_KCAL = 10.0
private const val MAX_HEADLINE_KCAL = 999.0

/**
 * Where the nutrition panel is on this page, when it is worth cutting out.
 *
 * The panel is the crowd: the words the table prints and the figures beside them, which cluster
 * tightly while a shelf background does not. The bounding box of that crowd, padded, is the crop —
 * and null when it covers most of the frame already, because re-reading the whole photograph
 * closer is just re-reading the whole photograph.
 */
internal fun List<TextBox>.panelBounds(width: Int, height: Int): Box? {
    val panel = filter { box ->
        val text = box.text.lowercase()
        TABLE_WORDS.any(text::contains) || FIGURE_ONLY.matches(box.text.trim())
    }
    if (panel.size < MIN_PANEL_BOXES) return null
    val pad = ((width + height) * PANEL_PAD).toInt()
    val bounds = Box(
        panel.minOf { it.box.x0 } - pad,
        panel.minOf { it.box.y0 } - pad,
        panel.maxOf { it.box.x1 } + pad,
        panel.maxOf { it.box.y1 } + pad,
    )
    val area = (bounds.x1 - bounds.x0).toLong() * (bounds.y1 - bounds.y0)
    return bounds.takeIf { area < width.toLong() * height * MAX_PANEL_SHARE }
}

/**
 * What a nutrition table is made of, for the purpose of finding where it sits. Wider than the
 * anchor [PANEL_WORDS] on purpose: the headline rule needs proof that this is a panel before it
 * dares call a number the calories, while the crop only needs to know where the crowd of table
 * words and figures is.
 */
private val TABLE_WORDS = PANEL_WORDS + listOf(
    "protein", "белк", "fat", "жир", "carb", "углевод", "sodium", "натри", "sugar", "сахар",
    "fibre", "fiber", "клетчат", "たんぱ", "脂質", "化物", "熱量",
)

private const val PANEL_PAD = 0.01
private const val MAX_PANEL_SHARE = 0.6

/** Rows of regions, left to right inside each, in the order the page was detected. */
internal fun List<TextBox>.rowsOfText(): List<List<TextBox>> {
    val rows = mutableListOf<MutableList<TextBox>>()
    var anchor: Box? = null
    for (item in this) {
        val current = anchor
        if (current != null && sharesRow(current, item.box)) {
            rows.last().add(item)
        } else {
            rows.add(mutableListOf(item))
            anchor = item.box
        }
    }
    return rows.map { row -> row.sortedBy { it.box.x0 } }
}

private fun String.hasDigit(): Boolean = any(Char::isDigit)

/** The words worth borrowing a figure for; the parser owns the full vocabulary from here on. */
private fun String.namesNutrient(): Boolean {
    val text = lowercase()
    return NUTRIENT_ROOTS.any(text::contains)
}

private val NUTRIENT_ROOTS = listOf(
    "calor", "kcal", "ккал", "энерг", "энэрг", "energ", "熱量", "ネルギー",
    "protein", "белк", "たんぱ", "fat", "жир", "脂質", "carb", "углевод", "化物",
)

/** Half of the shorter region's height has to fall inside the row's for it to be that row. */
private fun sharesRow(anchor: Box, box: Box): Boolean {
    val overlap = minOf(anchor.y1, box.y1) - maxOf(anchor.y0, box.y0)
    val shorter = minOf(anchor.y1 - anchor.y0, box.y1 - box.y0)
    return shorter > 0 && overlap >= shorter * ROW_OVERLAP
}

private const val ROW_OVERLAP = 0.5

/**
 * PP-OCRv6_small_det's own DB thresholds, from its inference.yml: mask at 0.2, keep a box at mean
 * 0.45, unclip by 1.4 — not PaddleX's pipeline defaults, 0.3, 0.6 and 1.5, the v5 detector's
 * values, which read fewer packets whole.
 */
internal const val MASK_THRESHOLD = 0.2f
private const val BOX_THRESHOLD = 0.45
private const val UNCLIP_RATIO = 1.4
private const val MIN_SIDE = 1.5
private const val MIN_COMPONENT_PIXELS = 9
private const val HALF_PI = Math.PI / 2

/** How far a hand-held shot may tilt before its rectangle stops being a line of text. */
private const val MAX_TILT = 25 * Math.PI / 180
