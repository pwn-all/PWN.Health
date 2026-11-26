package com.pwnall.health

import java.time.LocalDate

// Turning the journal into one number a day, which is the shape [Insights] works in.
//
// Every reader here follows the rule [moodSummary] set: a day is one observation, whatever the row
// count. Five taps on a bad Tuesday must not outvote the four ordinary days around it.
//
// What is *not* here matters as much. The optional nutrient columns — fibre, sugar, sodium,
// cholesterol — are null unless somebody opened the extras sheet on that meal, so they are sparse
// in a way that a correlation would silently misread; the same goes for every measurement that
// arrives from an instrument a few times a year. Those are annotated beside a result, not
// correlated against one.

/** One mean a day, over the rows that carry a mood score at all. */
fun moodSeries(entries: List<JournalEntry>): DailySeries = DailySeries(
    entries.filter { (it.moodScore ?: 0) in 1..5 }
        .mapNotNull { entry -> entry.localDay()?.let { it to entry.moodScore!!.toDouble() } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ofDay) -> ofDay.average() },
)

/** Steps come from the day table rather than the journal, and are already one figure a day. */
fun stepSeries(days: List<DaySteps>): DailySeries = DailySeries(
    days.mapNotNull { row ->
        row.day.toLocalDateOrNull()?.let { it to row.steps.toDouble() }
    }.toMap(),
)

/**
 * A day's total of some nutrient, over the days that actually carry the column.
 *
 * A day whose meals never mention the column is absent, not zero — averaging those in would invent
 * a diet nobody ate. A day with a single meal row is absent too: that is somebody who logged
 * breakfast and got on with their morning, and counting it as a light day drags every mean down.
 */
fun nutrientSeries(entries: List<JournalEntry>, of: (JournalEntry) -> Double?): DailySeries {
    val byDay = entries.filter { it.kind == KIND_EAT }
        .mapNotNull { entry -> entry.localDay()?.let { it to entry } }
        .groupBy({ it.first }, { it.second })
        .filterValues { meals -> meals.count(JournalEntry::countsAsMeal) >= MIN_MEALS_FOR_A_DAY }
    return DailySeries(
        byDay.mapNotNull { (day, meals) ->
            val carried = meals.mapNotNull(of)
            if (carried.isEmpty()) null else day to carried.sum()
        }.toMap(),
    )
}

private const val MIN_MEALS_FOR_A_DAY = 2

/** Grams of pure ethanol a day, which is the only part of a drink row that survives a locale. */
fun ethanolSeries(entries: List<JournalEntry>): DailySeries = DailySeries(
    entries.filter { it.kind == KIND_ALCOHOL && it.servingG > 0.0 }
        .mapNotNull { entry -> entry.localDay()?.let { it to entry.servingG } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ofDay) -> ofDay.sum() },
)

/** Minutes asleep a day, summed the way [sleepSummary] sums them: a day is a night. */
fun sleepMinuteSeries(entries: List<JournalEntry>): DailySeries =
    DailySeries(nightMinutesByDay(entries).mapValues { it.value.toDouble() })

/**
 * A yes-or-no exposure as a series of ones and zeroes, over a stated stretch of days.
 *
 * The stretch has to be handed in: unlike a nutrient, the absence of a row here *is* the answer —
 * a day with no drink logged is a day without one — but only across days the person was using the
 * app at all, which [activeDays] decides.
 */
fun exposureSeries(
    entries: List<JournalEntry>,
    activeDays: Set<LocalDate>,
    predicate: (JournalEntry) -> Boolean,
): DailySeries {
    val marked = entries.filter(predicate).mapNotNull(JournalEntry::localDay).toSet()
    return DailySeries(activeDays.associateWith { if (it in marked) 1.0 else 0.0 })
}

/**
 * The days the record shows somebody was actually keeping it.
 *
 * Any row at all counts. Without this, every gap in a diary would read as a day of no drinking, no
 * intimacy and no exercise, and a fortnight's holiday from the app would become a fortnight of
 * abstinence in every series at once.
 */
fun activeDays(entries: List<JournalEntry>, from: LocalDate, until: LocalDate): Set<LocalDate> =
    entries.mapNotNull(JournalEntry::localDay).filter { it in from..until }.toSet()

/** The row's day as a date, or null for one that does not parse — which is then no day at all. */
internal fun JournalEntry.localDay(): LocalDate? = day.toLocalDateOrNull()
