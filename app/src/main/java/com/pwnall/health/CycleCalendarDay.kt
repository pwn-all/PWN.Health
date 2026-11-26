package com.pwnall.health

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * What one square of the calendar has to say.
 *
 * Every field is a different kind of claim and they are kept apart on purpose: [bleeding] is a day
 * she wrote down, [predicted] is the same thing worked out from her average, and the two are drawn
 * so that nobody has to be told which is which.
 */
data class CycleCalendarDay(
    val date: LocalDate,
    val bleeding: Boolean,
    val predicted: Boolean,
    val fertile: Boolean,
    val ovulation: Boolean,
    /** 1, 2 or 3 — light, medium, heavy — for the drops under the number. Null on a day with none. */
    val flow: Int?,
    /**
     * Spotting between periods and nothing else ([isSpottingDay]). Such a day is [bleeding] too,
     * but it is not a day of a period: it wears its own mark instead of the ring and is called by
     * its own name.
     */
    val spotting: Boolean,
    val intimacy: Boolean,
)

/** What a square's bleeding is called: spotting where it is only that, "Period" for the rest. */
internal fun bleedingWord(bleeding: Boolean, spotting: Boolean): Int? = when {
    spotting -> R.string.cycle_spotting
    bleeding -> R.string.cycle_legend_period
    else -> null
}

/**
 * One month of it.
 *
 * The whole grid is built in one pass rather than a query per square: a month is forty-two cells and
 * each of them would otherwise walk the journal for itself.
 *
 * [acts] is the age gate's answer ([sexLogOffered]). Where it is false no square carries an act,
 * even if the journal holds one — a restored backup can put such rows in front of anyone — so the
 * heart is neither drawn nor read out to a profile the log is not offered to.
 */
fun CycleFacts.calendarMonth(
    month: YearMonth,
    firstDayOfWeek: DayOfWeek,
    acts: Boolean,
): List<CycleCalendarDay> {
    val first = month.atDay(1)
    val lead = ((first.dayOfWeek.value - firstDayOfWeek.value) + CALENDAR_WEEK) % CALENDAR_WEEK
    val start = first.minusDays(lead.toLong())
    val days = (0 until CALENDAR_CELLS).map { start.plusDays(it.toLong()) }

    val bleeding = recordedBleedingDays(entries)
    val predicted = predictedPeriodDays(summary, today, days.last())
    val windows = views.mapNotNull { it.fertile } +
        listOfNotNull(freshFertileWindow(summary, today))
    val ovulations = views.mapNotNull { it.ovulation }.toSet()
    val intimate = entries
        .filter { it.isSexualAct() }
        .mapNotNull { it.day.toLocalDateOrNull() }
        .toSet()

    return days.map { day ->
        CycleCalendarDay(
            date = day,
            bleeding = day in bleeding,
            predicted = day in predicted,
            fertile = windows.any { day in it },
            ovulation = day in ovulations,
            flow = rungOn(day, CycleScale.FLOW),
            spotting = isSpottingDay(day),
            intimacy = acts && day in intimate,
        )
    }
}

internal val INTIMATE_LOGS = setOf(CycleLog.SEX_PROTECTED, CycleLog.SEX_UNPROTECTED)

/** Six rows of seven, so a month never reflows its own height as it is paged through. */
internal const val CALENDAR_CELLS = 42
internal const val CALENDAR_WEEK = 7
