package com.pwnall.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal const val MAX_USER_LEVEL = 99

/** XP costs per transition, rather than cumulative thresholds. */
internal fun levelCost(level: Int): Long = when (level) {
    0 -> 50L
    1 -> 100L
    2 -> 200L
    3 -> 350L
    4 -> 600L
    5 -> 1_000L
    6 -> 1_300L
    7 -> 1_600L
    8 -> 2_000L
    9 -> 3_000L
    in 10 until MAX_USER_LEVEL -> 1_000L
    else -> 0L
}

internal data class LevelProgress(val level: Int, val earned: Long, val required: Long) {
    val fraction: Float get() = if (required == 0L) 1f else (earned.toDouble() / required).toFloat()
}

internal fun levelProgress(xp: Long): LevelProgress {
    var remaining = xp.coerceAtLeast(0L)
    var level = 0
    while (level < MAX_USER_LEVEL && remaining >= levelCost(level)) {
        remaining -= levelCost(level)
        level++
    }
    return LevelProgress(level, if (level == MAX_USER_LEVEL) 0L else remaining, levelCost(level))
}

internal data class AnnualXp(
    val opens: Long = 0,
    val steps: Long = 0,
    val meals: Long = 0,
    val workouts: Long = 0,
    val visits: Long = 0,
    val medals: Long = 0,
) {
    val total: Long get() = opens + steps + meals + workouts + visits + medals
    val progress: LevelProgress get() = levelProgress(total)
}

/**
 * The run of days the app was opened on, ending today or yesterday: still alive while today is
 * young, and 0 once a day has gone by without an opening. The same count [openingXp] pays by.
 */
internal fun openingStreak(openDays: List<AppOpenDay>, today: LocalDate): Int {
    val dates = openDays.mapNotNull { it.day.toLocalDateOrNull() }.filter { it <= today }.toSortedSet()
    var day = dates.lastOrNull()?.takeIf { it >= today.minusDays(1) } ?: return 0
    var streak = 0
    while (day in dates) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

/** A foreground day is unique; a streak may cross New Year, but its XP belongs to its own year. */
internal fun openingXp(openDays: List<AppOpenDay>, today: LocalDate): Long {
    val dates = openDays.mapNotNull { it.day.toLocalDateOrNull() }.filter { it <= today }.distinct().sorted()
    var previous: LocalDate? = null
    var streak = 0
    var xp = 0L
    dates.forEach { day ->
        streak = if (previous?.plusDays(1) == day) streak + 1 else 1
        if (day.year == today.year) {
            xp += when {
                streak >= 30 -> 3L
                streak >= 7 -> 2L
                else -> 1L
            }
        }
        previous = day
    }
    return xp
}

/** Derived from saved records so edits, restores and repeated app opens cannot award twice. */
internal fun annualXp(
    today: LocalDate,
    days: List<DaySteps>,
    journal: List<JournalEntry>,
    appointments: List<Appointment>,
    openDays: List<AppOpenDay>,
    zone: ZoneId,
): AnnualXp {
    val start = today.withDayOfYear(1).toString()
    val end = today.toString()
    val yearDays = days.filter { it.day in start..end }
    val yearJournal = journal.filter { it.day in start..end }
    val completedVisits = appointments.count { visit ->
        visit.doneAt?.let { at ->
            val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            day.year == today.year && day <= today
        } == true
    }
    return AnnualXp(
        opens = openingXp(openDays, today),
        steps = yearDays.sumOf { it.steps.coerceAtLeast(0L) / 1_000L },
        // Every food entry, a cup with milk from the caffeine dialog included: the row is labelled
        // "Food entries", not meals.
        meals = yearJournal.count { it.kind == KIND_EAT }.toLong(),
        workouts = yearJournal.filter { it.kind == KIND_ACTIVITY }.map { it.day }.distinct().size * 2L,
        visits = completedVisits * 2L,
        medals = stepAchievements(yearDays, today.year).count { it.earned } * 50L,
    )
}
