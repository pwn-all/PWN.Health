package com.pwnall.health

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

// What the morning check-ins add up to, for the Intimate screen's cards and the doctor's sheet.
//
// A morning is the day he woke on. It is answered when the answer was Firm, Partly or None; "Didn't
// notice" and a morning with no check-in are both unknown, and neither is ever a morning with none.
// The figure is the share of answered mornings, always with its two counts: a single morning says
// little, and a share over weeks is what the research behind the question counted.

/**
 * Where a waking is read on the clock: minutes from midnight, with the seam at six in the evening —
 * a waking at 19:00 reads as five hours before midnight. The hour nobody wakes from the night at,
 * so 23:50 and 00:10 are twenty minutes apart, as the sleep summary's bedtimes are.
 */
private const val WAKE_SEAM_MINUTE = 18 * 60
private const val MINUTES_PER_DAY = 24 * 60

/** Sleep that goes on within this long of where the night has got to is the same night, broken by a waking. */
internal const val NIGHT_BREAK_MS = 60 * 60_000L

/** The longest a sleep row may be and still be a night, as [nightMinutesByDay] reads one. */
private const val NIGHT_MAX_MINUTES = 1_439

/** A waking within this of its answer is the moment of answering, taken for the waking when none was set. */
private const val WOKE_NOW_MS = 60_000L

/**
 * The night that ended on each day, in minutes: the sleep filed under the day that ended first — the
 * app's own reading of a night ([recordedWakeAt]), since an afternoon nap is filed under the same day
 * as the night before it — together with any sleep of that day that overlaps it or goes on within
 * [NIGHT_BREAK_MS] of where it has got to. A night broken by a waking is one night, a night recorded
 * twice is not two, and the nap is no part of it. The day's total ([nightMinutesByDay]) adds the nap,
 * which is what the sleep summary wants and not "the sleep that ended that morning".
 */
internal fun nightEndingOn(entries: List<JournalEntry>): Map<LocalDate, Int> = entries
    .filter(JournalEntry::isNightSpan)
    .mapNotNull { entry -> entry.localDay()?.let { it to (entry.sleepStartAt!! to entry.sleepEndAt!!) } }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, spans) -> nightOf(spans) }

/**
 * Whether the row is a sleep [nightEndingOn] reads at all: a start and an end, a minute to a day apart.
 * A row of none or of days — an import's half-written session — is no sleep.
 */
internal fun JournalEntry.isNightSpan(): Boolean {
    val start = sleepStartAt ?: return false
    val end = sleepEndAt ?: return false
    return (end - start) / 60_000L in 1L..NIGHT_MAX_MINUTES.toLong()
}

/** The minutes asleep in the night [spans] hold, as [nightEndingOn] joins them. */
private fun nightOf(spans: List<Pair<Long, Long>>): Int {
    // Where the night has got to: the first end at the start, pushed on by every stretch that joins.
    var reach = spans.minOf { it.second }
    // The time already counted, so a stretch recorded twice is counted once.
    var covered = Long.MIN_VALUE
    var asleep = 0L
    for ((start, end) in spans.sortedBy { it.first }) {
        if (start > reach + NIGHT_BREAK_MS) break
        reach = maxOf(reach, end)
        asleep += (end - maxOf(start, covered)).coerceAtLeast(0L)
        covered = maxOf(covered, end)
    }
    return (asleep / 60_000L).toInt().coerceAtMost(NIGHT_MAX_MINUTES)
}

/** The answers over a stretch. [answered] leaves out "Didn't notice". */
data class MorningShares(val firm: Int, val partly: Int, val none: Int, val didntNotice: Int) {
    val answered: Int get() = firm + partly + none

    val firmOrPartly: Int get() = firm + partly

    /** Firm or partly, of the mornings answered: the figure the cards and the sheet print. */
    val firmOrPartlyShare: DayShare get() = DayShare(firmOrPartly, answered)

    /** Firm only, of the mornings answered, since the answer is graded. */
    val firmShare: DayShare get() = DayShare(firm, answered)
}

/** The check-ins of [window], one a morning, the answer given last standing ([morningChecksByDay]). */
fun morningShares(journal: List<JournalEntry>, window: ClosedRange<LocalDate>): MorningShares {
    val answers = morningsIn(journal, window).values.groupingBy { it.answer }.eachCount()
    return MorningShares(
        firm = answers[MorningErection.FIRM] ?: 0,
        partly = answers[MorningErection.PARTLY] ?: 0,
        none = answers[MorningErection.NONE] ?: 0,
        didntNotice = answers[MorningErection.DIDNT_NOTICE] ?: 0,
    )
}

private fun morningsIn(journal: List<JournalEntry>, window: ClosedRange<LocalDate>): Map<LocalDate, MorningCheck> =
    morningChecksByDay(journal).mapNotNull { (day, check) ->
        day.toLocalDateOrNull()?.takeIf { it in window }?.let { it to check }
    }.toMap()

/**
 * The wakings the check-ins of a stretch carry, whatever the answer: how many, their median on the
 * clock and their spread — the standard deviation, as the sleep section prints the spread of its
 * wakings. Null where there is nothing to take it of; the spread needs two. [source] is where every
 * one of them was read from, null with none; [fromPhone], that a measured one among them is the
 * phone's first use after the night rather than a sleep record's end ([MorningCheck.fromPhone]).
 */
data class Wakings(
    val count: Int,
    val medianMinute: Int?,
    val spreadMinutes: Int?,
    val source: WakeSource?,
    val fromPhone: Boolean = false,
)

/**
 * The wakings of [window] as the comparisons read them: never the moment of answering ([tookNow]),
 * which an answer tapped at eleven would make a waking at eleven, and from one source only
 * ([wakeSourceOf]) — a recorded end and a remembered time side by side would put the difference
 * between them into the spread.
 */
fun morningWakings(journal: List<JournalEntry>, window: ClosedRange<LocalDate>, zone: ZoneId): Wakings {
    val checks = morningsIn(journal, window).filterValues { !it.tookNow() }
    val source = wakeSourceOf(checks, checks.keys)
    val counted = checks.values.filter { it.wake == source }
    val minutes = counted.map { seamedWakeMinute(it.wokeAt, zone) }
    return Wakings(
        count = minutes.size,
        medianMinute = minutes.takeIf { it.isNotEmpty() }?.median()?.roundToInt()?.mod(MINUTES_PER_DAY),
        spreadMinutes = minutes.takeIf { it.size >= 2 }?.let { standardDeviation(it).roundToInt() },
        source = source,
        fromPhone = counted.any { it.fromPhone },
    )
}

/** A waking's minute on the clock in [zone], the seam at [WAKE_SEAM_MINUTE]. */
private fun seamedWakeMinute(at: Long, zone: ZoneId): Int =
    morningMinute(at, zone).let { if (it >= WAKE_SEAM_MINUTE) it - MINUTES_PER_DAY else it }

private fun List<Int>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle].toDouble() else (sorted[middle - 1] + sorted[middle]) / 2.0
}

/**
 * Whether the waking a check-in holds is only the moment it was answered: a morning with no sleep
 * record and no time set files "now", which an answer given at eleven would make a waking at eleven.
 * A check-in that does not say when it was answered cannot tell, and is kept.
 */
private fun MorningCheck.tookNow(): Boolean =
    wake == WakeSource.TYPED && answeredAt?.let { abs(it - wokeAt) < WOKE_NOW_MS } == true

/**
 * The one place the wakings of a comparison are read from: whichever of the sleep record and a time
 * set by hand most of [days] used, the record where they are as many. Two sources in one comparison
 * would set a recorded end against a remembered time and measure the difference between them.
 */
private fun wakeSourceOf(checks: Map<LocalDate, MorningCheck>, days: Set<LocalDate>): WakeSource? =
    days.mapNotNull { checks[it]?.wake }.groupingBy { it }.eachCount()
        .maxWithOrNull(compareBy({ it.value }, { it.key == WakeSource.SLEEP }))?.key
