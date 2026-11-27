package com.pwnall.health

import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/*
 * The arithmetic of a report chart: where a figure and a day land on the page, which ticks an axis
 * carries, where a line is broken and where a label can go without covering another.
 *
 * Nothing here touches android.graphics or Resources. The PDF renderer is handed a [ChartOp] with
 * every position already worked out and only strokes it, so a JVM test can read what a chart will
 * show — the ticks a doctor reads the figures against, the gap a missing month leaves — without a
 * device.
 */

/** A value axis carries at most this many ticks; more and the labels crowd a small chart. */
private const val MOST_TICKS = 5

/** And at least this many where its ends are free to move: two ticks give no sense of the slope. */
private const val FEWEST_TICKS = 3

/** The steps a tick may climb by within each power of ten: numbers a reader adds up at a glance. */
private val TICK_STEPS = listOf(1.0, 2.0, 2.5, 5.0)

/** The air above and below the figures, as a share of their spread, so no dot sits on the frame. */
private const val VALUE_AIR = 0.08

/**
 * The least spread a fitted value axis shows, as a share of the size of its figures: a tenth, five
 * per cent either side of their middle. Without it weights of 80.0 and 80.6 kg are stretched over
 * the whole plot and a change of under one per cent is drawn as a steep climb. A design choice for
 * a readable picture, not a clinical threshold; the ticks still carry the figures.
 */
private const val LEAST_SPREAD = 0.1

/** How far the tick arithmetic lets a division miss a whole number, so 0.3 / 0.1 still counts as 3. */
private const val TICK_EPSILON = 1e-9

/**
 * A line is broken where two points are further apart than this many times the usual gap between
 * its points: a month nobody measured is a hole in the line, not a straight stroke across it.
 */
private const val GAP_BREAK = 2.0

/** The longest three calendar months, July to September: up to this span the ticks are days or weeks. */
private const val QUARTER_DAYS = 92L

/** Two years, a leap day included: up to this span the ticks are months, and beyond it years. */
private const val TWO_YEARS_DAYS = 731L

/**
 * How much of the room between two points a bar takes, so neighbouring bars stay apart; and the
 * thinnest and widest a bar is drawn, in points.
 */
private const val BAR_FILL = 0.7f
private const val THINNEST_BAR = 0.8f
private const val WIDEST_BAR = 18f

/** The legend's swatch, the room between it and its label, and the room between two entries. */
internal const val SWATCH = 8f
private const val SWATCH_GAP = 3f
private const val LEGEND_GAP = 10f

/** A rectangle on the page, in points, top down. */
internal data class ChartBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left

    fun overlaps(other: ChartBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun inside(bounds: ChartBox): Boolean =
        left >= bounds.left && right <= bounds.right && top >= bounds.top && bottom <= bounds.bottom
}

/** A point on the page. */
internal data class ChartDot(val x: Float, val y: Float)

/** The value axis of a chart: the figures at its bottom and top edges, and the ticks between them. */
internal data class ValueScale(val min: Double, val max: Double, val ticks: List<Double>)

/**
 * The value axis for [values]: the figures and the band's edges with a little air, the ends fixed
 * where the chart fixes them, and three to five ticks on the 1-2-2.5-5 ladder.
 *
 * The ends are the figures' own rather than rounded out to a tick — a year of steps between 6,000
 * and 10,400 is not drawn over 0 to 15,000 — and are rounded out only where fewer than three ticks
 * would fall inside. The axis never spans less than [LEAST_SPREAD] of the figures' size, so a
 * change of a few tenths on a large figure is not drawn as a climb. Figures that are all zero or
 * more never get a negative axis. A chart of bars is given zero to stand on ([fromZero]): a bar's
 * height is its figure, and a bar cut off at 6,000 reads as a fraction of what it is.
 *
 * A fixed end holds only while every figure is on its side of it. A percentage of 120 on a chart
 * that stops at 100 moves the top out to the figure: an end that cut the point off would draw its
 * line into the frame at about 100 and leave its label with no dot under it. The band never moves a
 * fixed end; it is shaded as far as the plot goes.
 */
internal fun valueScale(
    values: List<Double>,
    band: ChartBand? = null,
    yFloor: Double? = null,
    yCeiling: Double? = null,
    fromZero: Boolean = false,
): ValueScale {
    val figures = values.filter(Double::isFinite)
    val fitted = (figures + listOfNotNull(band?.low, band?.high, 0.0.takeIf { fromZero })).filter(Double::isFinite)
    val low = fitted.minOrNull() ?: 0.0
    val high = fitted.maxOrNull() ?: 1.0
    val middle = (low + high) / 2.0
    val spread = maxOf(high - low, abs(middle) * LEAST_SPREAD)
    val air = if (spread > 0.0) spread * VALUE_AIR else 1.0
    val fixedFloor = yFloor?.takeIf { end -> figures.all { it >= end } }
    val fixedCeiling = yCeiling?.takeIf { end -> figures.all { it <= end } }
    var min = fixedFloor ?: (middle - spread / 2.0 - air).let { if (low >= 0.0) it.coerceAtLeast(0.0) else it }
    var max = fixedCeiling ?: (middle + spread / 2.0 + air)
    if (max <= min) max = min + 1.0
    val step = tickStep(min, max)
    if (ticksWithin(min, max, step).size < FEWEST_TICKS) {
        if (fixedFloor == null) min = floor(min / step) * step
        if (fixedCeiling == null) max = ceil(max / step) * step
    }
    return ValueScale(min, max, ticksWithin(min, max, step))
}

/** The finest step on the ladder that puts no more than [MOST_TICKS] ticks between [min] and [max]. */
private fun tickStep(min: Double, max: Double): Double =
    generateSequence(10.0.pow(floor(log10((max - min) / MOST_TICKS)))) { it * 10.0 }
        .flatMap { decade -> TICK_STEPS.asSequence().map { it * decade } }
        .first { ticksWithin(min, max, it).size <= MOST_TICKS }

/** Every multiple of [step] from [min] to [max], both included. */
private fun ticksWithin(min: Double, max: Double, step: Double): List<Double> =
    (ceil(min / step - TICK_EPSILON).toLong()..floor(max / step + TICK_EPSILON).toLong()).map { it * step }

/**
 * The ticks as the axis prints them: to as many decimals as the step needs, with a point, the way
 * the sheet prints a laboratory figure; whole numbers grouped the way [locale] groups them, the way
 * the sheet prints a count of steps.
 */
internal fun valueLabels(ticks: List<Double>, locale: Locale): List<String> {
    val plain = labLadderText(ticks)
    if (plain.any { '.' in it }) return plain
    val whole = NumberFormat.getIntegerInstance(locale)
    return ticks.map { whole.format(it) }
}

/** The grain the time axis is marked in. */
internal enum class DateUnit { DAY, MONTH, YEAR }

/** One way of marking a time axis: the days that get a tick, and what kind of day they are. */
internal data class DateTicks(val unit: DateUnit, val days: List<LocalDate>)

/**
 * The ways [from]..[to] can be marked, finest first; the chart takes the finest whose labels fit.
 *
 * Days and weeks up to three months, months up to two years, years beyond: the grain a doctor
 * reads a quarter, a year and a history in. A week starts on a Monday, a month on its first and a
 * year on the first of January, so the ticks fall on days a reader can name.
 */
internal fun dateTickChoices(from: LocalDate, to: LocalDate): List<DateTicks> {
    val span = ChronoUnit.DAYS.between(from, to)
    return when {
        span <= QUARTER_DAYS -> {
            val monday = from.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
            listOf(from to 1L, from to 2L, monday to 7L, monday to 14L, monday to 28L)
                .map { (first, days) -> DateTicks(DateUnit.DAY, every(first, to, days)) }
        }

        span <= TWO_YEARS_DAYS -> {
            val first = from.withDayOfMonth(1).let { if (it < from) it.plusMonths(1) else it }
            val starts = generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= to }.toList()
            listOf(1, 2, 3, 6).map { n -> DateTicks(DateUnit.MONTH, starts.filter { (it.monthValue - 1) % n == 0 }) }
        }

        else -> {
            val starts = (from.year..to.year).map { LocalDate.of(it, 1, 1) }.filter { it in from..to }
            listOf(1, 2, 5, 10).map { n -> DateTicks(DateUnit.YEAR, starts.filter { it.year % n == 0 }) }
        }
    }
}

private fun every(first: LocalDate, to: LocalDate, days: Long): List<LocalDate> =
    generateSequence(first) { it.plusDays(days) }.takeWhile { it <= to }.toList()

/**
 * The ticks' labels in [locale]'s words and Latin digits — java.time prints the standard digits
 * whatever the language, which is what the rest of the sheet prints.
 *
 * A day is the sheet's own date pattern without its year, so a sheet that writes "Sep 12, 2026"
 * marks its axis "Sep 12" rather than "12 Sep". A month is its short name, with the year on the
 * first tick and on every January so a reader always knows which year a stretch belongs to. It is
 * the stand-alone name ("LLL"), not the one that follows a day ("MMM"): a Russian axis reads
 * "май 2026", where "мая 2026" is the form of "7 мая" with its day torn off.
 */
internal fun dateTickLabels(ticks: DateTicks, locale: Locale, datePattern: String): List<String> {
    val day = runCatching { DateTimeFormatter.ofPattern(shortDayPattern(datePattern), locale) }
        .getOrElse { DateTimeFormatter.ofPattern("d MMM", locale) }
    val month = DateTimeFormatter.ofPattern("LLL", locale)
    val monthYear = DateTimeFormatter.ofPattern("LLL yyyy", locale)
    val year = DateTimeFormatter.ofPattern("yyyy", locale)
    return ticks.days.mapIndexed { index, date ->
        when (ticks.unit) {
            DateUnit.DAY -> day
            DateUnit.MONTH -> if (index == 0 || date.monthValue == 1) monthYear else month
            DateUnit.YEAR -> year
        }.format(date)
    }
}

/** A date pattern without its year and the punctuation that joined the year on, a month name shortened. */
internal fun shortDayPattern(pattern: String): String =
    pattern.replace(Regex("[^A-Za-z']*[yu]+[^A-Za-z']*"), "").replace("MMMM", "MMM")

/**
 * Where [day] lands between [left] and [right]: the middle of its own slot, each day of
 * [from]..[to] one slot wide, so a daily bar on the first day stays inside the frame and a dot sits over
 * its tick. Time runs left to right whatever the language: a chart's axis is not text.
 */
internal fun dayX(day: LocalDate, from: LocalDate, to: LocalDate, left: Float, right: Float): Float {
    val slots = ChronoUnit.DAYS.between(from, to) + 1
    return left + (ChronoUnit.DAYS.between(from, day) + 0.5f) / slots * (right - left)
}

/** Where [value] lands between the plot's [top] and [bottom], the scale's minimum at the bottom. */
internal fun valueY(value: Double, scale: ValueScale, top: Float, bottom: Float): Float =
    (bottom - (value - scale.min) / (scale.max - scale.min) * (bottom - top)).toFloat()

/**
 * The stretches of [days] a line may join, as index ranges: broken wherever two neighbours are more
 * than [GAP_BREAK] times the median gap apart.
 *
 * The median is taken over the gaps between different days: two blood pressures on one morning
 * are no gap at all, and counting them would halve the usual gap and break the line everywhere.
 * Where the count of gaps is even it is the lower of the two middle ones, never their mean: with
 * weights on 1 and 2 January and 1 June, a mean of the gaps 1 and 150 is 75.5, the long gap lifts
 * its own threshold past itself, and five empty months are drawn as one stroke.
 */
internal fun lineRuns(days: List<LocalDate>): List<IntRange> {
    if (days.isEmpty()) return emptyList()
    val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
    val usual = gaps.filter { it > 0 }.sorted().let { sorted ->
        if (sorted.isEmpty()) 0.0 else sorted[(sorted.size - 1) / 2].toDouble()
    }
    val runs = mutableListOf<IntRange>()
    var start = 0
    gaps.forEachIndexed { index, gap ->
        if (usual > 0.0 && gap > usual * GAP_BREAK) {
            runs += start..index
            start = index + 1
        }
    }
    runs += start..days.lastIndex
    return runs
}

/**
 * A bar's width: most of the narrowest room between two of [days], shared among [lanes] series of
 * bars standing side by side: never thinner than a hairline, and never so wide that a handful of
 * monthly figures turns into blocks.
 */
internal fun barWidth(days: List<LocalDate>, dayWidth: Float, lanes: Int): Float {
    val spacing = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.filter { it > 0 }.minOrNull() ?: 1L
    return (spacing * dayWidth * BAR_FILL / lanes.coerceAtLeast(1)).coerceIn(THINNEST_BAR, WIDEST_BAR)
}

/**
 * Each label's place: the first of its [candidates] that lies inside [bounds] and clears every
 * label placed before it, or null where none does.
 *
 * A label that can go nowhere is left off rather than printed over another: two figures on top of
 * each other are two figures nobody can read, and the rows under the chart carry every one.
 */
internal fun placeLabels(candidates: List<List<ChartBox>>, bounds: ChartBox): List<ChartBox?> {
    val placed = mutableListOf<ChartBox>()
    return candidates.map { places ->
        places.firstOrNull { place -> place.inside(bounds) && placed.none(place::overlaps) }?.also { placed += it }
    }
}

/**
 * A box [width] wide centred on [x], pushed sideways as far as it takes to stay between [left] and
 * [right]: a label at the edge of a chart moves in rather than hanging out of it.
 */
internal fun centredBox(x: Float, width: Float, top: Float, bottom: Float, left: Float, right: Float): ChartBox {
    val start = (x - width / 2f).coerceAtMost(right - width).coerceAtLeast(left)
    return ChartBox(start, top, start + width, bottom)
}

/**
 * The legend's entries flowed into lines no wider than [width]: for each entry, its line and its
 * distance from the chart's start edge. An entry is a swatch, a gap and a label [labelWidths] wide,
 * set on [labelLines] lines (one each unless given): a label wrapped over several lines starts a
 * line of its own, and the next entry starts on the line after its last.
 */
internal fun flowLegend(
    labelWidths: List<Float>,
    width: Float,
    labelLines: List<Int> = labelWidths.map { 1 },
): List<Pair<Int, Float>> {
    var line = 0
    var offset = 0f
    return labelWidths.zip(labelLines) { label, lines ->
        val entry = SWATCH + SWATCH_GAP + label
        if (offset > 0f && (lines > 1 || offset + entry > width)) {
            line += 1
            offset = 0f
        }
        (line to offset).also {
            if (lines > 1) {
                line += lines
                offset = 0f
            } else {
                offset += entry + LEGEND_GAP
            }
        }
    }
}

/** Where a legend entry's label starts, past its swatch. */
internal const val LEGEND_LABEL_OFFSET = SWATCH + SWATCH_GAP

/**
 * The figures a chart can draw: its series' points that fall on [ReportChart.from]..[ReportChart.to]
 * and are numbers at all. A figure outside the window would be drawn outside the frame, and one that
 * is not finite has no place on any axis.
 */
internal fun ReportChart.drawnPoints(series: ChartSeries): List<ChartPoint> =
    series.points.filter { it.day in from..to && it.value.isFinite() }.sortedBy(ChartPoint::day)

/** Whether a chart has anything to draw; one that has not is left off the sheet rather than drawn empty. */
internal fun ReportChart.drawable(): Boolean = series.any { drawnPoints(it).isNotEmpty() }

/**
 * Charts in the rows they are printed in: a full-width one alone, half-width ones two to a row,
 * and a half-width one with no partner alone at the start of its row.
 */
internal fun chartRows(charts: List<ReportChart>): List<List<ReportChart>> {
    val rows = mutableListOf<MutableList<ReportChart>>()
    charts.forEach { chart ->
        val last = rows.lastOrNull()
        if (chart.width == ChartWidth.HALF && last?.singleOrNull()?.width == ChartWidth.HALF) {
            last += chart
        } else {
            rows += mutableListOf(chart)
        }
    }
    return rows
}

/** The chart's own lines: a header line, a legend line, and the height a small label is set in. */
private const val HEADER_LINE = 12f
private const val HEADER_BASELINE = 9f
private const val UNIT_GAP = 6f
private const val LEGEND_LINE = 11f
private const val LEGEND_BASELINE = 8f
private const val LABEL_BOX = 9f
private const val LABEL_BASELINE = 7f

/** Half the height of a digit, which is how far a tick's label drops to sit centred on its tick. */
private const val DIGIT_MIDDLE = 3f

/** The room between the header and the plot, which the top tick's label reaches into. */
private const val PLOT_TOP_GAP = 6f

/** The room between the value labels and the plot, and between the plot and the chart's end. */
private const val Y_LABEL_GAP = 4f
private const val PLOT_END_GAP = 4f

/** The band under the plot the dates are printed in, where their labels start, and the room between two. */
private const val X_LABEL_ROOM = 14f
private const val X_LABEL_TOP = 5f
private const val X_LABEL_SPACE = 6f

/** How far a point's label sits off its dot, and a mark's label off its line. */
private const val POINT_LABEL_LIFT = 3f
private const val MARK_LABEL_INSET = 2f

/** How many lines down a mark's label may step to clear the one before it. */
private const val MARK_LABEL_LINES = 3

/**
 * A [ReportChart] laid into a box on the page: its texts resolved and measured, every position
 * worked out, and the result a [ChartOp] the renderer only strokes.
 *
 * On a right-to-left sheet the title and the legend are anchored at the right, where the reader
 * starts; the plot itself is not mirrored, because time on a chart runs left to right in every
 * language a doctor reads one in.
 */
internal class ChartDrafter(
    private val text: (ReportText) -> String,
    private val measure: (String, SheetType) -> Float,
    private val locale: Locale,
    private val datePattern: String,
    private val rtl: Boolean,
) {
    /** [chart] laid into [box], or null where it has no figure to draw. */
    fun draft(chart: ReportChart, box: ChartBox): ChartOp? = if (chart.drawable()) Draft(chart, box).op() else null

    private inner class Draft(private val chart: ReportChart, private val box: ChartBox) {
        private val texts = mutableListOf<ChartText>()
        private val series = chart.series.map { chart.drawnPoints(it) to it }.filter { it.first.isNotEmpty() }

        fun op(): ChartOp {
            val legendTop = box.top + header()
            val (swatches, legendHeight) = legend(legendTop)
            val scale = valueScale(
                values = series.flatMap { (points, _) -> points.map(ChartPoint::value) },
                band = chart.band,
                yFloor = chart.yFloor,
                yCeiling = chart.yCeiling,
                fromZero = series.any { it.second.style == ChartStyle.BARS },
            )
            val labels = valueLabels(scale.ticks, locale)
            val plot = ChartBox(
                left = box.left + (labels.maxOfOrNull { measure(it, SheetType.NOTE) } ?: 0f) + Y_LABEL_GAP,
                top = legendTop + legendHeight + PLOT_TOP_GAP,
                right = box.right - PLOT_END_GAP,
                bottom = box.bottom - X_LABEL_ROOM,
            )
            val grid = scale.ticks.map { valueY(it, scale, plot.top, plot.bottom) }
            grid.zip(labels).forEach { (y, label) ->
                val width = measure(label, SheetType.NOTE)
                texts += ChartText(label, plot.left - Y_LABEL_GAP - width, y + DIGIT_MIDDLE, SheetType.NOTE)
            }
            val positions = series.map { (points, _) ->
                points.map { ChartDot(x(it.day, plot), valueY(it.value, scale, plot.top, plot.bottom)) }
            }
            val marks = chart.marks.filter { it.day in chart.from..chart.to }
            labels(plot, marks, positions)
            return ChartOp(
                box = box,
                plot = plot,
                grid = grid,
                band = shade(plot, scale),
                bandEdges = listOfNotNull(chart.band?.low, chart.band?.high)
                    .map { valueY(it, scale, plot.top, plot.bottom) }
                    .filter { it in plot.top..plot.bottom },
                ticks = timeAxis(plot),
                marks = marks.map { x(it.day, plot) },
                traces = traces(plot, scale, positions),
                swatches = swatches,
                texts = texts,
            )
        }

        private fun x(day: LocalDate, plot: ChartBox): Float = dayX(day, chart.from, chart.to, plot.left, plot.right)

        /** A text at [offset] from the chart's start edge: its left end, or its right on a mirrored sheet. */
        private fun atStart(line: String, type: SheetType, offset: Float, baseline: Float) {
            if (line.isEmpty()) return
            val width = measure(line, type)
            texts += ChartText(line, if (rtl) box.right - offset - width else box.left + offset, baseline, type)
        }

        /**
         * The title, wrapped to the chart's width, with the unit after it, or the unit alone where
         * there is no title: printed once, at the top, so no tick has to carry it. Returns its height.
         */
        private fun header(): Float {
            val lines = chart.title?.let { title ->
                wrapText(text(title), box.width, locale) { measure(it, SheetType.LABEL) }
            }.orEmpty()
            lines.forEachIndexed { index, line ->
                atStart(line, SheetType.LABEL, 0f, box.top + HEADER_BASELINE + index * HEADER_LINE)
            }
            val unit = text(chart.unit)
            val after = lines.lastOrNull()?.let { measure(it, SheetType.LABEL) + UNIT_GAP } ?: 0f
            val inline = after + measure(unit, SheetType.NOTE) <= box.width
            val first = if (inline) (lines.size - 1).coerceAtLeast(0) else lines.size
            // A unit that is a phrase ("per cent of scheduled doses marked, each week") wraps on its own
            // lines: set on one, a half-width chart's would run into the chart beside it.
            val units = if (inline) listOf(unit) else wrapText(unit, box.width, locale) { measure(it, SheetType.NOTE) }
            units.forEachIndexed { index, part ->
                atStart(
                    part,
                    SheetType.NOTE,
                    if (inline) after else 0f,
                    box.top + HEADER_BASELINE + (first + index) * HEADER_LINE,
                )
            }
            return (first + units.size.coerceAtLeast(1)) * HEADER_LINE
        }

        /** A swatch and a name for each named series, where more than one is named; and their height. */
        private fun legend(top: Float): Pair<List<ChartSwatch>, Float> {
            val named = series.mapIndexedNotNull { ink, (_, s) -> s.label?.let { Triple(ink, s.style, text(it)) } }
            if (named.size < 2) return emptyList<ChartSwatch>() to 0f
            // A label wider than a half-width chart — a long one, or any in a longer language — wraps.
            val wrapped = named.map { (_, _, label) ->
                wrapText(label, box.width - LEGEND_LABEL_OFFSET, locale) {
                    measure(it, SheetType.NOTE)
                }.ifEmpty { listOf(label) }
            }
            val flow =
                flowLegend(
                    wrapped.map { lines ->
                        lines.maxOf { measure(it, SheetType.NOTE) }
                    },
                    box.width,
                    wrapped.map { it.size },
                )
            val swatches = named.zip(flow).zip(wrapped) { (entry, place), lines ->
                val (ink, style, _) = entry
                val (line, offset) = place
                val baseline = top + LEGEND_BASELINE + line * LEGEND_LINE
                lines.forEachIndexed { index, part ->
                    atStart(part, SheetType.NOTE, offset + LEGEND_LABEL_OFFSET, baseline + index * LEGEND_LINE)
                }
                val left = if (rtl) box.right - offset - SWATCH else box.left + offset
                ChartSwatch(ChartBox(left, baseline - SWATCH + 1f, left + SWATCH, baseline + 1f), ink, style)
            }
            return swatches to (flow.last().first + wrapped.last().size) * LEGEND_LINE
        }

        /** The reference band, shaded; a one-sided band runs to the edge of the plot on its open side. */
        private fun shade(plot: ChartBox, scale: ValueScale): ChartBox? =
            chart.band?.takeIf { it.low != null || it.high != null }?.let { band ->
                fun y(value: Double) = valueY(value, scale, plot.top, plot.bottom).coerceIn(plot.top, plot.bottom)
                ChartBox(plot.left, band.high?.let(::y) ?: plot.top, plot.right, band.low?.let(::y) ?: plot.bottom)
            }

        /**
         * The dates under the plot: the finest marking whose labels all fit side by side, and where
         * even the coarsest does not, the coarsest with the labels that would collide left off.
         * Returns where the ticks fall.
         */
        private fun timeAxis(plot: ChartBox): List<Float> {
            val choices = dateTickChoices(chart.from, chart.to)
            val (xs, labels, places) = choices.asSequence().map { dates(it, plot) }
                .firstOrNull { (_, _, places) -> places.all { it != null } }
                ?: dates(choices.last(), plot)
            labels.zip(places).forEach { (label, place) ->
                place?.let {
                    texts += ChartText(label, it.left + X_LABEL_SPACE / 2f, it.top + LABEL_BASELINE, SheetType.NOTE)
                }
            }
            return xs
        }

        private fun dates(ticks: DateTicks, plot: ChartBox): Triple<List<Float>, List<String>, List<ChartBox?>> {
            val xs = ticks.days.map { x(it, plot) }
            val labels = dateTickLabels(ticks, locale, datePattern)
            val top = plot.bottom + X_LABEL_TOP
            val places = placeLabels(
                xs.zip(labels) { x, label ->
                    val width = measure(label, SheetType.NOTE) + X_LABEL_SPACE
                    listOf(centredBox(x, width, top, top + LABEL_BOX, box.left, box.right))
                },
                bounds = box,
            )
            return Triple(xs, labels, places)
        }

        /**
         * The marks' labels at the top of the plot, then the points' labels just above their dots —
         * below them, or a line higher, where the place above is taken — none over another.
         */
        private fun labels(plot: ChartBox, marks: List<ChartMark>, positions: List<List<ChartDot>>) {
            val wanted = marks.map { mark ->
                val label = text(mark.label)
                label to markPlaces(x(mark.day, plot), measure(label, SheetType.NOTE), plot)
            } + series.zip(positions).flatMap { (drawn, dots) ->
                drawn.first.zip(dots).mapNotNull { (point, dot) ->
                    // The figure with what qualifies it — "5.2 · after food" — since a fed glucose
                    // drawn in a fasting band with nothing beside it reads as out of range.
                    val words = listOfNotNull(point.label, point.note?.let(text)).joinToString(" · ")
                    words.takeIf { it.isNotEmpty() }?.let { it to pointPlaces(dot, measure(it, SheetType.NOTE), plot) }
                }
            }
            placeLabels(wanted.map { it.second }, plot).zip(wanted).forEach { (place, want) ->
                place?.let { texts += ChartText(want.first, it.left, it.top + LABEL_BASELINE, SheetType.NOTE) }
            }
        }

        private fun pointPlaces(dot: ChartDot, width: Float, plot: ChartBox): List<ChartBox> = listOf(
            dot.y - POINT_LABEL_LIFT - LABEL_BOX,
            dot.y + POINT_LABEL_LIFT,
            dot.y - POINT_LABEL_LIFT - 2 * LABEL_BOX,
        ).map { top -> centredBox(dot.x, width, top, top + LABEL_BOX, plot.left, plot.right) }

        private fun markPlaces(x: Float, width: Float, plot: ChartBox): List<ChartBox> =
            (0 until MARK_LABEL_LINES).flatMap { line ->
                val top = plot.top + MARK_LABEL_INSET / 2f + line * LABEL_BOX
                val after = x + MARK_LABEL_INSET
                val before = x - MARK_LABEL_INSET - width
                listOf(
                    ChartBox(after, top, after + width, top + LABEL_BOX),
                    ChartBox(before, top, before + width, top + LABEL_BOX),
                )
            }

        /**
         * Each series in its style: a line broken at its gaps, with a lone point between two gaps
         * drawn as a dot so no figure vanishes; dots; or bars standing on zero, side by side where
         * two series are bars.
         */
        private fun traces(plot: ChartBox, scale: ValueScale, positions: List<List<ChartDot>>): List<ChartTrace> {
            val lanes = series.count { it.second.style == ChartStyle.BARS }
            var lane = 0
            return series.zip(positions).mapIndexed { ink, (drawn, dots) ->
                val (points, s) = drawn
                when (s.style) {
                    ChartStyle.BARS -> ChartTrace(ink, s.style, bars = bars(points, dots, plot, scale, lane++ to lanes))

                    ChartStyle.DOTS -> ChartTrace(ink, s.style, dots = dots)

                    ChartStyle.LINE, ChartStyle.LINE_DOTS -> {
                        val runs = lineRuns(points.map(ChartPoint::day)).map { dots.slice(it) }
                        val lone = if (s.style == ChartStyle.LINE_DOTS) dots else runs.filter { it.size == 1 }.flatten()
                        ChartTrace(ink, s.style, runs = runs.filter { it.size > 1 }, dots = lone)
                    }
                }
            }
        }

        /** [lane] is this series' place among the series of bars, and how many there are. */
        private fun bars(
            points: List<ChartPoint>,
            dots: List<ChartDot>,
            plot: ChartBox,
            scale: ValueScale,
            lane: Pair<Int, Int>,
        ): List<ChartBox> {
            val (index, lanes) = lane
            val dayWidth = plot.width / (ChronoUnit.DAYS.between(chart.from, chart.to) + 1)
            val width = barWidth(points.map(ChartPoint::day), dayWidth, lanes)
            val base = valueY(0.0.coerceIn(scale.min, scale.max), scale, plot.top, plot.bottom)
            val shift = (index - (lanes - 1) / 2f) * width
            return dots.map { dot ->
                ChartBox(dot.x + shift - width / 2f, minOf(dot.y, base), dot.x + shift + width / 2f, maxOf(dot.y, base))
            }
        }
    }
}
