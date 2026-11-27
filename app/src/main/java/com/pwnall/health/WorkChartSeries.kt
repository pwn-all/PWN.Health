package com.pwnall.health

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

// The week-by-week series the Work charts page draws, worked out with no canvas in sight.
//
// The weeks are the work cards' own: every whole week, from the locale's first day of the week, of
// the thirteen weeks to yesterday ([workInsightWindow]), so a bar is a counted week of
// [workSummary] and the dashed line across the bars is its median, the figures the cards print
// under them. A week that was not counted is a gap, never a zero: a bar on the floor would claim a
// week off that nobody timed.
//
// Beside the hours stand only what the doctor's sheet already sets beside them (work_mood,
// work_wound_up, work_sleep, work_alcohol in docs/REPORT_COMPARISONS.md), each as a line from the
// person's own average. Nothing here is a verdict: the charts set two things side by side in the
// same weeks and say no more than that.

/** What a comparison chart sets beside the hours, in the order the doctor's sheet pairs them. */
internal enum class WorkOutcome { MOOD, WOUND_UP, SLEEP, ALCOHOL }

/**
 * One figure week by week: [points], null for a week with too few readings, and [average], the
 * person's own over these weeks — the mean of the weeks that make a point, each weighing as one, as
 * each bar does. Null with no such week.
 */
internal data class WeekLine(val points: List<Double?>) {
    val average: Double? get() = points.filterNotNull().takeIf { it.isNotEmpty() }?.average()

    /** Each point's distance from [average]: above zero a week higher than the average, below lower. */
    val fromAverage: List<Double?> get() = average.let { mean ->
        points.map { point -> if (point == null || mean == null) null else point - mean }
    }
}

/** One comparison: the hours' bars are the page's own, so a chart carries only its line. */
internal data class WorkPairChart(val outcome: WorkOutcome, val line: WeekLine)

/**
 * The comparison, or null when it has too little to show: fewer than [WORK_MIN_COUNTED_WEEKS] weeks
 * with a bar and a point both, or no week whose point is above zero — twelve weeks without a drink
 * beside the hours say nothing about drinking, nor do twelve weeks never wound up about being so.
 */
internal fun workPairChart(outcome: WorkOutcome, hours: List<Double?>, line: WeekLine): WorkPairChart? {
    val both = hours.indices.count { hours[it] != null && line.points.getOrNull(it) != null }
    val shown = both >= WORK_MIN_COUNTED_WEEKS && line.points.any { (it ?: 0.0) > 0.0 }
    return if (shown) WorkPairChart(outcome, line) else null
}

/**
 * Each week's mood: the mean of its days' own means ([moodSeries], one a day however many taps),
 * from [WEEK_MIN_READINGS] days with a mood.
 */
internal fun weeklyMood(journal: List<JournalEntry>, weeks: List<ClosedRange<LocalDate>>): List<Double?> {
    val byDay = moodSeries(journal).byDay
    return weeks.map { week -> weekMean(byDay.filterKeys { it in week }.values.toList()) }
}

/**
 * Each week's share of its days answered wound up or restless ([tenseDays]), from [WEEK_MIN_READINGS]
 * answered days; a day the Energy question was not answered is unknown, never a calm one.
 */
internal fun weeklyWoundUp(journal: List<JournalEntry>, weeks: List<ClosedRange<LocalDate>>): List<Double?> {
    if (weeks.isEmpty()) return emptyList()
    val tense = tenseDays(journal, weeks.first().start..weeks.last().endInclusive)
    return weeks.map { week ->
        weekMean(tense.filterKeys { it in week }.values.map { if (it) 1.0 else 0.0 })
    }
}

/**
 * Everything the page shows. [hours] is each of [weeks]' net hours, null for a week that was not
 * counted; [pairs] are the comparisons that have enough to show; [insights] are the cards' figures,
 * read over the same weeks.
 */
internal data class WorkChartsData(
    val weeks: List<ClosedRange<LocalDate>>,
    val hours: List<Double?>,
    val pairs: List<WorkPairChart>,
    val insights: WorkInsights,
) {
    /**
     * The hours' chart is drawn from [WORK_MIN_COUNTED_WEEKS] counted weeks, and only when one of them
     * holds any work: a run of weeks kept by hand without a session is not a shape of hours.
     */
    val hoursShown: Boolean get() = insights.enoughWeeks && hours.any { (it ?: 0.0) > 0.0 }

    /** The top of the hours' axis: the longest week rounded up to a whole hour, so the figure is true. */
    val hoursTop: Double get() = ceil(hours.maxOf { it ?: 0.0 }).coerceAtLeast(1.0)

    /**
     * Nothing to draw and no work in the weeks read at all: the cards would only count zeros, so the
     * page says only to come back later.
     */
    val empty: Boolean get() = !hoursShown && insights.summary.workDays == 0
}

internal fun workChartsData(
    journal: List<JournalEntry>,
    today: LocalDate,
    zone: ZoneId,
    daysOff: Set<DayOfWeek>,
    longDayMinutes: Int,
    alcohol: Boolean,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
): WorkChartsData {
    val insights = workInsights(journal, today, zone, daysOff, longDayMinutes, firstDayOfWeek)
    val weeks = wholeWorkWeeks(workInsightWindow(today), firstDayOfWeek)
    val counted = insights.summary.weeks.associate { it.monday to it.hours }
    val hours = weeks.map { counted[it.start] }
    val shown = WorkChartsData(weeks, hours, emptyList(), insights)
    if (!shown.hoursShown) return shown
    // The drinks are read up to today itself: every week here ended by yesterday.
    val pairs = listOfNotNull(
        workPairChart(WorkOutcome.MOOD, hours, WeekLine(weeklyMood(journal, weeks))),
        workPairChart(WorkOutcome.WOUND_UP, hours, WeekLine(weeklyWoundUp(journal, weeks))),
        workPairChart(WorkOutcome.SLEEP, hours, WeekLine(weeklyNightHours(journal, weeks, zone))),
        if (alcohol) workPairChart(WorkOutcome.ALCOHOL, hours, WeekLine(weeklyDrinks(journal, weeks, today))) else null,
    )
    return shown.copy(pairs = pairs)
}
