package com.pwnall.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The stretch of time the chart is drawn over. A one-day "D" window is not among them: a single day
 * has no shape, and everything a day has to say is already the headline above the plot.
 */
enum class CycleWindow(val titleRes: Int) {
    WEEK(R.string.cycle_window_week),
    MONTH(R.string.cycle_window_month),
    QUARTER(R.string.cycle_window_quarter),
}

/**
 * The last day each window reaches.
 *
 * The quarter is three named months and not ninety-one days. Counted in days from the 18th it would
 * end in the middle of the fourth month, so a plot captioned "3m" would carry four month marks and
 * the last of them a fortnight wide — a month nobody asked to see. Ended on the last day of the
 * second month ahead, it is September, October and November whatever day of September it is opened
 * on, and the three marks under it are the three months it draws.
 */
internal fun cycleWindowEnd(window: CycleWindow, today: LocalDate): LocalDate = when (window) {
    CycleWindow.WEEK -> today.plusDays(WEEK_SPAN - 1L)

    CycleWindow.MONTH -> today.plusDays(MONTH_SPAN - 1L)

    CycleWindow.QUARTER -> today.plusMonths(QUARTER_MONTHS - 1L)
        .let { it.withDayOfMonth(it.lengthOfMonth()) }
}

private const val WEEK_SPAN = 7
private const val MONTH_SPAN = 28
private const val QUARTER_MONTHS = 3L

/**
 * What a line is: her own record carried forward by her own past cycles, or a shape the calendar
 * draws and she never records at all.
 */
enum class CycleSeriesRole { RECORDED, ESTIMATE }

/**
 * One line. [points] runs one value per bucket, between 0 and 1 of that ladder's own height, and a
 * null is a gap rather than a zero — the distinction the whole model turns on. A day she did not
 * answer is not a day with nothing on it, and a line drawn along the floor across a month is a
 * picture claiming she recorded "none" every day of it.
 */
data class CycleSeries(
    val scale: CycleScale,
    val role: CycleSeriesRole,
    val points: List<Double?>,
    /**
     * Which of those points are her own record rather than filled in from her past cycles. The
     * curve is one unbroken line either way — what changes is how it is drawn, because a line that
     * stops dead at today says nothing about tomorrow and a line that is all one stroke would claim
     * the forecast was measured.
     */
    val recorded: List<Boolean> = points.map { it != null },
)

/** What one curve says aloud: how many columns she answered herself, and its highest rung and where. */
internal data class SeriesReading(val answered: Int, val peak: CycleLog, val peakIndex: Int)

/**
 * A curve in the terms a screen reader is given, or null for a curve with no rung to name.
 *
 * The rung is read back off the height by [CycleScale.rungAt]. Arithmetic that undoes
 * [CycleScale.heightOf] only for a ladder without a "none" rung would speak pain and desire a rung
 * low — "Mild" as "No pain" — and a nought of flow as "Light".
 *
 * A column counts as answered only where it holds a rung of hers. A day of a cycle outside its
 * period with no flow written on it is drawn as a measured nought ([CycleScale.absentIsNone]) and
 * is in [CycleSeries.recorded] for the stroke's sake, and counting it would say "1 days recorded" of
 * a day she never answered.
 *
 * The fertility estimate has no reading: nobody recorded it and its ladder is borrowed, so its
 * peak would be spoken as "Egg white", a mucus nobody saw. Its days are the fertile days and the
 * ovulation estimate, which the description names after the curves.
 */
internal fun CycleSeries.spokenReading(): SeriesReading? {
    val rungs = points.map { height -> height?.let(scale::rungAt) }
    val peak = points.indices.filter { points[it] != null }.maxByOrNull { points[it] ?: 0.0 }
    val rung = peak?.let(rungs::get)
    return if (role == CycleSeriesRole.ESTIMATE || peak == null || rung == null) {
        null
    } else {
        SeriesReading(
            answered = rungs.indices.count { recorded[it] && rungs[it] != null },
            peak = scale.logs[rung - 1],
            peakIndex = peak,
        )
    }
}

/**
 * Everything the plot needs, worked out with no canvas in sight.
 *
 * [buckets] are the columns, left to right, each the span of days it covers — one day each in all
 * three windows. [todayIndex] is the column today stands in — always the first — and the boundary
 * where record becomes forecast. Today is always alone in its own bucket, so no column can be half
 * measured and half predicted.
 */
data class CycleChart(
    val window: CycleWindow,
    val buckets: List<ClosedRange<LocalDate>>,
    val todayIndex: Int,
    val series: List<CycleSeries>,
    /** Columns holding a period she recorded, as runs so the drawing can shade them in one go. */
    val bleeding: List<IntRange>,
    /** Columns where the average puts the next periods. Drawn faint: this is arithmetic. */
    val predicted: List<IntRange>,
    /** Columns inside an estimated or observed fertile window. */
    val fertile: List<IntRange>,
    /** The column the estimated ovulation falls in, where the record supports an estimate. */
    val ovulation: Int?,
    /**
     * The columns that carry a mark and a date under the plot: every day of a week, every week of a
     * month, every month of a longer span. A plot with seven anonymous sticks on it tells nobody what
     * any of them is.
     */
    val ticks: List<Int>,
    /** The first column of the next period the average predicts, so the plot can name its date. */
    val predictedStart: Int?,
) {
    val empty: Boolean get() = series.isEmpty()
}

/**
 * The widest a plot is drawn: beyond this the columns are thinner than the hairline between them
 * and the chart is a smear. Ninety-two is the longest three named months can run — July through
 * September, or March through May — so the quarter is always one day to a column.
 */
internal const val CHART_MAX_POINTS = 92

/** Two previous cycles is as far as the comparison line is chained forward. See [cycleWindowSpan]. */
internal const val CHART_PREDICT_CYCLES = 2

/** Three points before a curve is drawn at all: fewer is not a shape, it is two dots and a gap. */
internal const val CHART_TYPICAL_MIN_POINTS = 3

/** And this many previous cycles contributing to a point before that point is drawn. */
internal const val CHART_TYPICAL_MIN_CYCLES = 2

/**
 * How far ahead the plot runs, and how many days to a column.
 *
 * The window starts today and goes forward: a week is today and six days, a month today and
 * twenty-seven, a quarter the rest of this month and the two after it ([cycleWindowEnd]). The plot
 * is about what is coming, and what has already happened is what the calendar is for.
 */
internal fun cycleWindowSpan(window: CycleWindow, today: LocalDate): Pair<Int, Int> {
    val forward = ChronoUnit.DAYS.between(today, cycleWindowEnd(window, today)).toInt()
    return forward to ceilDiv(forward + 1, CHART_MAX_POINTS)
}

private fun ceilDiv(value: Int, by: Int) = (value + by - 1) / by

/**
 * The whole chart, for one phase and one window.
 *
 * The phase decides only *which* ladders are drawn; every day of the window is then read on those
 * ladders whatever phase that day itself was in. A quarter opened during a period is a comb of
 * periods, which is the view worth having — the alternative, months of blank because those days
 * were not bleeding days, answers nothing.
 *
 * [acts] is the age gate's answer ([sexLogOffered]), as [questionsOn] takes it: where it is false
 * the desire ladder is not drawn, whatever a restored backup put in the journal.
 */
fun CycleFacts.chart(phase: CyclePhase, window: CycleWindow, acts: Boolean): CycleChart {
    val (forward, bucketDays) = cycleWindowSpan(window, today)
    val future = (1..forward).map { today.plusDays(it.toLong()) }
    // Today alone in the first column, so no column ever holds a measured day and a forecast day
    // at once — the one distinction every stroke on the plot is drawn from.
    val columns = listOf(listOf(today)) + future.chunked(bucketDays)
    val todayIndex = 0

    val days = columns.flatten()
    val anchorKind = anchorOf(today)?.kind
    val series = listOfNotNull(
        curve(columns, phase.scale(), anchorKind),
        phase.secondScale().takeIf { acts || it != CycleScale.DESIRE }?.let { curve(columns, it, anchorKind) },
        fertilitySeries(columns),
    )

    return CycleChart(
        window = window,
        buckets = columns.map { it.first()..it.last() },
        todayIndex = todayIndex,
        series = series,
        // Spotting between periods is bleeding and is not a period, which is what this ribbon is
        // named in the key, said aloud as, and answered as under a held finger.
        bleeding = runsOf(columns, recordedBleedingDays(entries).filterNot { isSpottingDay(it) }.toSet()),
        predicted = runsOf(columns, predictedPeriodDays(summary, today, days.lastOrNull())),
        fertile = runsOf(columns, fertileDays(days)),
        ovulation = ovulationDay(days)?.let { day -> columns.indexOfFirst { day in it } }
            ?.takeIf { it >= 0 },
        ticks = tickColumns(columns, window, todayIndex),
        predictedStart = summary.nextExpected
            ?.let { expected -> columns.indexOfFirst { expected in it } }
            ?.takeIf { it >= 0 },
    )
}

/**
 * Which columns are named under the plot.
 *
 * A week names every day, a month every week, and a longer span every month — so the marks are
 * always something a person already counts in, and how many there are falls out of the span rather
 * than being fixed at seven whatever is being shown. Today is always named, wherever it falls.
 */
private fun tickColumns(
    columns: List<List<LocalDate>>,
    window: CycleWindow,
    todayIndex: Int,
): List<Int> {
    val marks = when (window) {
        CycleWindow.WEEK -> columns.indices.toList()

        CycleWindow.MONTH -> columns.indices.filter { (it - todayIndex) % DAYS_IN_WEEK == 0 }

        else -> columns.indices.filter { index ->
            index == 0 || columns[index - 1].last().month != columns[index].first().month
        }
    }
    return (marks + todayIndex).distinct().sorted()
}

private const val DAYS_IN_WEEK = 7

/**
 * Her own line: what she recorded while the record reaches, and what her own past cycles say for the
 * rest of it — one curve, not two, and not a stub that stops at today.
 *
 * The distinction is kept rather than blurred. [CycleSeries.recorded] marks the points that are hers
 * and the drawing strokes those solid and everything else dashed, so the line runs unbroken across
 * the window while never claiming a forecast was measured.
 */
private fun CycleFacts.curve(
    columns: List<List<LocalDate>>,
    scale: CycleScale,
    anchorKind: CycleAnchorKind?,
): CycleSeries? {
    val mine = columns.map { column ->
        column.filter { !it.isAfter(today) }.mapNotNull { levelOn(it, scale) }.maxOrNull()
    }
    val usual = typicalPoints(columns, scale, anchorKind)
    val points = mine.indices.map { mine[it] ?: usual[it] }
    // Drawn on the strength of the whole line, not of her half of it: in a window that begins today
    // there is one column she can have answered, and a day not yet answered is not an empty plot.
    if (points.all { it == null }) return null
    return CycleSeries(scale, CycleSeriesRole.RECORDED, points, mine.map { it != null })
}

/**
 * What her own finished cycles averaged to at each point of the month.
 *
 * Computed a day at a time and only then folded into columns, so it is reduced by exactly the same
 * operation the recorded line is and the two cannot drift apart in the wide windows.
 *
 * The middle value rather than the mean, for the reason [medianPeriodLength] takes the median: these
 * are rungs of a ladder, not quantities, and the average of "light" and "heavy" is not "medium" —
 * it is a number with no word for it that would then be spoken as one.
 *
 * It is not drawn as a second line beside hers. In a window that begins today there is nothing for
 * it to compare against: every column but the first is forecast, and the forecast is these very
 * numbers, so the two lines would be one line striped in two colours.
 */
private fun CycleFacts.typicalPoints(
    columns: List<List<LocalDate>>,
    scale: CycleScale,
    anchorKind: CycleAnchorKind?,
): List<Double?> {
    if (anchorKind == null) return columns.map { null }
    val byDay = mutableMapOf<LocalDate, Double>()
    columns.flatten().forEach { day ->
        val anchor = anchorOf(day, anchorKind)
        val levels = anchor?.let { pastLevelsAt(it, scale) }.orEmpty().sorted()
        byDay[day] = when {
            levels.size >= CHART_TYPICAL_MIN_CYCLES -> levels[(levels.size - 1) / 2]

            // A day of a cycle the record brackets, on a ladder whose absence is a measured nought:
            // she was not bleeding then and she usually is not now. Without this the comparison would
            // have a value only on the handful of days a period covers and be dropped for want of three
            // points, so the line the legend names would never be drawn.
            scale.absentIsNone && cycleSpanOf(day) != null -> 0.0

            else -> return@forEach
        }
    }
    return smoothed(columns.map { column -> column.mapNotNull(byDay::get).maxOrNull() })
}

/**
 * A gentle three-point pass over the comparison, which is what takes the staircase out of it.
 *
 * Every point of it is the middle of a handful of past cycles at one anchor, so neighbouring points
 * step rather than slope and the curve comes out as a flight of stairs. Smoothing a measurement would
 * be falsifying it; this is a model of her cycles already, and the shape is the whole of what it is
 * for. The recorded line is never touched.
 *
 * The centre is weighted four to one against its neighbours rather than averaged flat with them. A
 * flat mean moves the peak: her heaviest day has a nought on one side of it and a lighter day on the
 * other, so the average of the three lands on the day after — a picture saying she usually bleeds
 * most on the second day when her record says the first.
 */
private fun smoothed(points: List<Double?>): List<Double?> = points.indices.map { index ->
    val centre = points[index] ?: return@map null
    val before = points.getOrNull(index - 1) ?: centre
    val after = points.getOrNull(index + 1) ?: centre
    (before + SMOOTH_CENTRE * centre + after) / (SMOOTH_CENTRE + 2)
}

/**
 * The days the average puts the next periods on, up to the end of the window.
 *
 * Only forward of the prediction and only while there is a measured period length to give them —
 * guessing a length as well as a date would be drawing a forecast out of a forecast.
 *
 * Nothing once the prediction no longer stands ([predictionStands]), and no further than the
 * comparison line is chained ([CHART_PREDICT_CYCLES]). Without both limits the walk would run from
 * the predicted start to the end of whatever month the calendar is turned to, however long ago that
 * start was due: a record silent since March would draw "Predicted period" in December and in every
 * month after it, nine cycles out, in the same dashes as next week's.
 */
internal fun predictedPeriodDays(summary: CycleSummary, today: LocalDate, until: LocalDate?): Set<LocalDate> {
    val expected = summary.nextExpected ?: return emptySet()
    val length = summary.periodLengthDays ?: return emptySet()
    val last = until ?: return emptySet()
    if (!predictionStands(summary, today)) return emptySet()
    val days = mutableSetOf<LocalDate>()
    repeat(CHART_PREDICT_CYCLES + 1) { cycle ->
        val start = expected.plusDays(summary.averageLengthDays.toLong() * cycle)
        for (offset in 0 until length) {
            val day = start.plusDays(offset.toLong())
            if (!day.isBefore(today) && !day.isAfter(last)) days += day
        }
    }
    return days
}

/**
 * The fertile days inside the window: the observed one of every finished cycle, counted back from
 * the start that actually followed, plus the estimated one of the cycle running now.
 */
private fun CycleFacts.fertileDays(days: List<LocalDate>): Set<LocalDate> {
    val windows = views.mapNotNull { it.fertile }
    return days.filter { day -> windows.any { day in it } }.toSet()
}

/** How hard the middle of the three is held. Flat would be 1, and flat moves peaks. */
private const val SMOOTH_CENTRE = 4.0

private fun CycleFacts.fertilitySeries(columns: List<List<LocalDate>>): CycleSeries? {
    val points = columns.map { column ->
        column.mapNotNull { day -> fertilityShape(ovulationFor(day), day) }.maxOrNull()
    }
    if (points.none { it != null && it > 0.0 }) return null
    return CycleSeries(
        scale = CycleScale.MUCUS,
        role = CycleSeriesRole.ESTIMATE,
        points = points,
        // None of it is her record. The whole line is drawn as the estimate it is.
        recorded = points.map { false },
    )
}

/**
 * The ovulation of whichever cycle [date] belongs to: counted back from the start that actually
 * followed where the record has one, and from the start the average predicts where it does not.
 *
 * The regularity gate applies to the predicted half only. A finished cycle's window is not being
 * extrapolated from anything — the date it ran to is on file — which is the same reason
 * [observedFertileWindow] carries no gate of its own.
 */
private fun CycleFacts.ovulationFor(date: LocalDate): LocalDate? {
    val finished = views.lastOrNull { cycle ->
        !date.isBefore(cycle.start) && cycle.nextStart?.let { date.isBefore(it) } == true
    }
    return if (finished?.nextStart != null) {
        observedFertileWindow(finished.start, finished.nextStart)?.endInclusive
    } else {
        cycleSpanOf(date)
            ?.takeIf { summary.regular }
            ?.let { span -> span.endInclusive.minusDays(LUTEAL_DAYS).takeIf { it.isAfter(span.start) } }
    }
}

/**
 * The estimated ovulation the window holds, if it holds one: the running cycle's own, or the one the
 * chained prediction puts in a cycle that has not begun.
 *
 * A woman opening this screen on day two wants to know when the fertile days are coming, so the plot
 * marks them before they arrive and not only once they have.
 */
private fun CycleFacts.ovulationDay(days: List<LocalDate>): LocalDate? {
    val candidates = views.mapNotNull { it.ovulation } + predictedOvulations(summary, days.lastOrNull())
    return candidates.firstOrNull { it in days }
}

/** Where the average puts ovulation in the periods it predicts: fourteen days before each start. */
private fun predictedOvulations(summary: CycleSummary, until: LocalDate?): List<LocalDate> {
    val expected = summary.nextExpected ?: return emptyList()
    val last = until ?: return emptyList()
    if (summary.averageLengthDays <= 0 || !summary.regular) return emptyList()
    val out = mutableListOf<LocalDate>()
    var start = expected
    repeat(CHART_PREDICT_CYCLES + 1) {
        val ovulation = start.plusDays(summary.averageLengthDays.toLong()).minusDays(LUTEAL_DAYS)
        if (!ovulation.isAfter(last)) out += ovulation
        start = start.plusDays(summary.averageLengthDays.toLong())
    }
    return out
}

/** The columns holding any of [days], as runs, so a shaded band is one rectangle and not thirty. */
private fun runsOf(columns: List<List<LocalDate>>, days: Set<LocalDate>): List<IntRange> {
    if (days.isEmpty()) return emptyList()
    val hit = columns.indices.filter { index -> columns[index].any { it in days } }
    return hit.fold(mutableListOf<IntRange>()) { runs, index ->
        val last = runs.lastOrNull()
        if (last != null && last.last == index - 1) {
            runs[runs.lastIndex] = last.first..index
        } else {
            runs += index..index
        }
        runs
    }
}
