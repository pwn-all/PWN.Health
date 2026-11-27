package com.pwnall.health

import java.time.LocalDate
import java.time.YearMonth

internal enum class CalendarFilter(val title: Int, val art: Int) {
    ALL(R.string.events_all, R.drawable.ic_art_schedule),
    FOOD(R.string.home_card_food, R.drawable.ic_art_food),
    VISITS(R.string.visit_title, R.drawable.ic_art_doctor),
    WORKOUTS(R.string.home_workouts, R.drawable.ic_art_sport),
    SLEEP(R.string.sleep, R.drawable.ic_art_sleep),
    WATER(R.string.water_title, R.drawable.ic_art_water),
    MEASUREMENTS(R.string.add_measurement, R.drawable.ic_art_height),
    PAIN(R.string.pain_title, R.drawable.ic_art_pain),
    SYMPTOMS(R.string.symptom_title, R.drawable.ic_art_doctor),
    OTHER(R.string.events_other, R.drawable.ic_art_schedule),
}

internal fun calendarEntryFilter(entry: JournalEntry): CalendarFilter = when {
    entry.kind == KIND_EAT -> CalendarFilter.FOOD
    entry.kind == KIND_ACTIVITY || entry.kind == KIND_NOT_DONE -> CalendarFilter.WORKOUTS
    entry.sleepStartAt != null || entry.kind == "SLEEP" -> CalendarFilter.SLEEP
    entry.kind == KIND_WATER -> CalendarFilter.WATER
    entry.kind == KIND_MEASUREMENT -> CalendarFilter.MEASUREMENTS
    entry.kind == KIND_PAIN -> CalendarFilter.PAIN
    entry.kind == KIND_SYMPTOM -> CalendarFilter.SYMPTOMS
    else -> CalendarFilter.OTHER
}

internal fun calendarCanMoveForward(month: YearMonth, today: LocalDate, yearly: Boolean): Boolean =
    if (yearly) month.year < today.year else month < YearMonth.from(today)

/**
 * The day that stays selected when the calendar steps to [next]: the same day of the month where
 * [next] has one, its last day where it is shorter — the 31st of January lands on the 28th or 29th
 * of February — and never a day after [today].
 */
internal fun calendarMovedSelection(selected: LocalDate, next: YearMonth, today: LocalDate): LocalDate =
    next.atDay(selected.dayOfMonth.coerceAtMost(next.lengthOfMonth())).coerceAtMost(today)

/**
 * How many days of each of [months] have been lived by [today], the divisors of the year's averages.
 * A month still ahead has lived none, and counting its thirty-one into the year's divisor is how an
 * average for the year comes out a third short every January.
 */
internal fun calendarElapsedDays(months: List<YearMonth>, today: LocalDate): List<Int> {
    val thisMonth = YearMonth.from(today)
    return months.map { month ->
        when {
            month.isAfter(thisMonth) -> 0
            month == thisMonth -> today.dayOfMonth
            else -> month.lengthOfMonth()
        }
    }
}

/**
 * The days an opened-out month lists, latest first: every day of [month], up to [today], that a
 * shown entry is filed under ([entryDays]) or a shown visit falls on ([visitDays]). An entry's day
 * is text out of the database, asked for by a range of text, and a restored copy can carry a day
 * that is no date — "2026-11-1" sorts inside November. Such a day is passed over, as everywhere
 * else ([toLocalDateOrNull]); parsed bare in the composition, it would throw the calendar off the
 * screen every time that month was opened out.
 */
internal fun calendarAgendaDates(
    entryDays: List<String>,
    visitDays: List<LocalDate>,
    month: YearMonth,
    today: LocalDate,
): List<LocalDate> = (entryDays.mapNotNull { it.toLocalDateOrNull() } + visitDays)
    .filter { YearMonth.from(it) == month && !it.isAfter(today) }
    .distinct()
    .sortedDescending()
