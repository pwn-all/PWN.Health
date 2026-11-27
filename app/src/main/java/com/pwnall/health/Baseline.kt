package com.pwnall.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What is normal *for this person*, measured from their own record.
 *
 * Population guidance says what is typical for adults; it cannot say what is typical for you. Every
 * piece of advice in this app compares against both: the guideline gives the outer reference, this
 * gives the inner one. Purely internal — nothing here is shown as a verdict, it is the denominator
 * the advice is written against.
 */
data class Baseline(
    /** null until there is enough of the measure to mean anything. */
    val meanStepsPerDay: Long?,
    val meanSleepMinutes: Int?,
    /** Standard deviation of nightly sleep: how steady the schedule is. */
    val sleepSpreadMinutes: Int?,
    val meanWaterMl: Int?,
    val meanFatG: Double?,
    val meanMoodScore: Double?,
    /** The middle onset-to-onset interval of this person's own cycles — [usualCycleDays], as the screen has it. */
    val usualCycleDays: Int?,
    /** Shortest-to-longest spread across the recorded window — FIGO's own regularity measure. */
    val cycleSpreadDays: Int?,
    val recordedCycles: Int,
    val daysObserved: Int,
)

/** Below these counts a mean is noise, so it is not computed at all. */
private const val MIN_DAYS_FOR_STEPS = 7
private const val MIN_NIGHTS_FOR_SLEEP = 5
private const val MIN_NIGHTS_FOR_SPREAD = 5
private const val MIN_DAYS_FOR_INTAKE = 5
private const val MIN_MOOD_ENTRIES = 5
private const val MIN_CYCLES_FOR_MEAN = 3

/** Three intervals of 15+ days cannot fit inside the 30-day window, so cycles get their own. */
private const val CYCLE_WINDOW_MONTHS = 6L

fun personalBaseline(
    days: List<DaySteps>,
    journal: List<JournalEntry>,
    today: LocalDate,
    windowDays: Long = 30,
): Baseline {
    fun ClosedRange<LocalDate>.holds(day: String) = day.toLocalDateOrNull()?.let { it in this } == true

    val window = today.minusDays(windowDays - 1)..today
    // A day's steps, water and fat are a total only once the day is over: counted in, this morning's
    // few hundred steps would pull the usual down until evening. The same number of finished days
    // instead, as [activityAdvice] reads its month. A night and a mood are whole when written down.
    val finished = today.minusDays(windowDays)..today.minusDays(1)

    val stepDays = days.filter { finished.holds(it.day) }
    val entries = journal.filter { window.holds(it.day) }
    val finishedEntries = journal.filter { finished.holds(it.day) }

    // One figure a day, the way [sleepSummary] and the advice count a night: a nap filed as a row of
    // its own is part of its day's sleep, not a second and shorter night in the mean and the spread.
    val nights = nightMinutesByDay(entries).values.toList()
    val waterDays = finishedEntries.filter { it.kind == KIND_WATER }.groupBy { it.day }
    // A cappuccino from the caffeine dialog is food on a day with a meal, not a food day of its own.
    val foodDays = finishedEntries.filter { it.kind == KIND_EAT }.groupBy { it.day }
        .filterValues { it.any(JournalEntry::countsAsMeal) }
    val moods = entries.mapNotNull { it.moodScore }.filter { it in 1..5 }

    val cycleFrom = today.minusMonths(CYCLE_WINDOW_MONTHS)
    val cycleStarts = journal
        .filter { it.kind == KIND_CYCLE }
        .mapNotNull { it.day.toLocalDateOrNull() }
        .filter { it in cycleFrom..today }
        .distinct()
        .sorted()
    // The profile card is a third reader of these dates, after the cycle screen and the advice, so
    // it drops the same implausible gaps they do: a range of its own would print "usual cycle 38
    // days" for a person the screen calls 28. See [PLAUSIBLE_CYCLE_DAYS].
    val intervals = cycleStarts.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
        .takeLastWhile { it < CYCLE_BREAK_DAYS }
        .filter { it in PLAUSIBLE_CYCLE_DAYS }

    return Baseline(
        meanStepsPerDay = stepDays.takeIf { it.size >= MIN_DAYS_FOR_STEPS }
            ?.let { rows -> rows.sumOf(DaySteps::steps) / rows.size },
        meanSleepMinutes = nights.takeIf { it.size >= MIN_NIGHTS_FOR_SLEEP }?.average()?.toInt(),
        sleepSpreadMinutes = nights.takeIf { it.size >= MIN_NIGHTS_FOR_SPREAD }
            ?.let { standardDeviation(it).toInt() },
        meanWaterMl = waterDays.takeIf { it.size >= MIN_DAYS_FOR_INTAKE }
            ?.let { byDay -> byDay.values.sumOf { day -> day.sumOf { it.servingG } } / byDay.size }
            ?.toInt(),
        meanFatG = foodDays.takeIf { it.size >= MIN_DAYS_FOR_INTAKE }
            ?.let { byDay -> byDay.values.sumOf { day -> day.sumOf { it.fatG } } / byDay.size },
        meanMoodScore = moods.takeIf { it.size >= MIN_MOOD_ENTRIES }?.average(),
        // The cycle screen's own figure, by the cycle screen's own function, and rounded as it is
        // there, not truncated: this card describes the same person from the same rows.
        usualCycleDays = intervals.takeIf { it.size >= MIN_CYCLES_FOR_MEAN }?.let(::usualCycleDays)?.roundToInt(),
        cycleSpreadDays = intervals.takeIf { it.size >= MIN_CYCLES_FOR_MEAN }
            ?.let { (it.max() - it.min()) },
        recordedCycles = intervals.size,
        daysObserved = stepDays.size,
    )
}

/** How far today sits from the person's own usual, as a share of it. Null when there is no usual. */
fun deviationFromBaseline(value: Double, baseline: Double?): Double? {
    if (baseline == null || baseline <= 0.0) return null
    return abs(value - baseline) / baseline
}
