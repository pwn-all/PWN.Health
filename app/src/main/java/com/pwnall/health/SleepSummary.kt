package com.pwnall.health

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

data class SleepSummary(
    val nights: Int,
    val averageMinutes: Int,
    /** Standard deviation of bedtime in minutes: how regular the schedule is. */
    val bedtimeSpreadMinutes: Int,
    /** Minutes short of the target across the window; never negative. */
    val debtMinutes: Int,
    val score: Int,
    /**
     * The source written on every session counted above, one per row — a watch, Health Connect,
     * the editor or the app's own estimate from an untouched phone — so a reader of these figures
     * can say who wrote them. Rows the gate below dropped are not in it.
     */
    val sources: List<String?> = emptyList(),
    /**
     * Standard deviation of waking time in minutes: how regular the mornings are, which a bedtime
     * held to the minute does not say. It is not in the score.
     */
    val wakeSpreadMinutes: Int = 0,
)

/**
 * The app's own target, not a published one.
 *
 * Eight hours is the middle of the seven to nine the National Sleep Foundation recommends for
 * adults of 18 to 64 (seven to eight from 65; Hirshkowitz et al., Sleep Health 2015), and an hour
 * above the "seven or more" the American Academy of Sleep Medicine and the Sleep Research Society
 * recommend for adults of 18 to 60 (Watson et al., Sleep 2015). A night of 7:20 is therefore short
 * of this target and inside both guidelines, which is why the doctor's sheet names it as the app's.
 */
const val SLEEP_TARGET_MINUTES = 480

/**
 * Summarises the sleep the app can actually see.
 *
 * This app records intervals, not stages, and there will not be stages: a phone on a bedside table
 * cannot see them, and a score built on invented stages is worse than no score. What it can honestly
 * report is how long, how regular, and how far short — which is most of what sleep advice is about
 * anyway. A row is a session and a day is a night: one day may hold several rows.
 *
 * The score is duration against target and bedtime regularity, weighted 70/30, because consistency
 * is the part people can act on tonight.
 */
fun sleepSummary(entries: List<JournalEntry>, zone: ZoneId = ZoneId.systemDefault()): SleepSummary {
    val sessions = entries.mapNotNull { entry ->
        val start = entry.sleepStartAt ?: return@mapNotNull null
        val end = entry.sleepEndAt ?: return@mapNotNull null
        val minutes = ((end - start) / 60_000L).toInt()
        if (minutes in 1..1_439) entry to Triple(start, end, minutes) else null
    }
    // A day is a night, not a row. The app writes a row per session — the editor files a nap the
    // same way as a night, and the home screen sums a day's rows — so grouping by day is what keeps
    // an hour on the sofa from halving the average and charging a second night's worth of debt. The
    // day's bedtime and waking are the session that ended first, the rule wakeMinuteOf already
    // follows, so the nap cannot become the bedtime of the night it was taken after nor its waking;
    // the total is held to the same 23:59 ceiling one row is, since a day that adds up to more than
    // that is a bad import.
    val nights = sessions.groupBy({ it.first.day }, { it.second }).values.map { ofDay ->
        ofDay.minBy { (_, end) -> end } to ofDay.sumOf { it.third }.coerceAtMost(1_439)
    }
    if (nights.isEmpty()) return SleepSummary(0, 0, 0, 0, 0)

    val averageMinutes = nights.sumOf { it.second } / nights.size
    val debt = nights.sumOf { (SLEEP_TARGET_MINUTES - it.second).coerceAtLeast(0) }

    // Bedtimes are circular: 23:50 and 00:10 are twenty minutes apart, not twenty-three hours. The
    // day is rotated to start at noon rather than measured on a true circle, which moves the seam
    // instead of removing it: 11:50 and 12:10 still read as a day apart. That is the hour nobody
    // starts a night at — the detector only looks between 19:00 and 04:00 — so the seam was put
    // where the data is not.
    //
    // A waking is not rotated on its own but read off its night's bedtime, by the wall-clock minutes
    // from one to the other, so it inherits the bedtime's seam: up at 11:50 and at 12:10 after a
    // 04:00 bedtime is twenty minutes apart, not a day. The recorded end on the wall clock rather
    // than the start plus the whole minutes slept, so on the night the clocks change a waking at
    // seven still reads as seven, and 23:14:37 to 07:02:11 wakes at 07:02, as wakeMinuteOf reads it,
    // not at 07:01.
    fun clockMinute(at: Long) = Instant.ofEpochMilli(at).atZone(zone).let { it.hour * 60 + it.minute }
    val times = nights.map { (night, _) ->
        val (start, end) = night
        val bedClock = clockMinute(start)
        val bedtime = if (bedClock < 12 * 60) bedClock + 24 * 60 else bedClock
        bedtime to bedtime + (clockMinute(end) - bedClock).mod(24 * 60)
    }
    val spread = standardDeviation(times.map { it.first })

    val durationScore = (averageMinutes.toDouble() / SLEEP_TARGET_MINUTES).coerceAtMost(1.0)
    val regularityScore = (1.0 - spread / 120.0).coerceIn(0.0, 1.0)
    val score = ((durationScore * 0.7 + regularityScore * 0.3) * 100).toInt()

    return SleepSummary(
        nights = nights.size,
        averageMinutes = averageMinutes,
        bedtimeSpreadMinutes = spread.toInt(),
        debtMinutes = debt,
        score = score,
        sources = sessions.map { it.first.measurementSource },
        wakeSpreadMinutes = standardDeviation(times.map { it.second }).toInt(),
    )
}

/** Divided by the count, not one less: the figure describes the nights logged, not a wider run of them. */
internal fun standardDeviation(values: List<Int>): Double {
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}

/** How far a night sits from the average bedtime, in minutes, for a "later than usual" hint. */
fun bedtimeDriftMinutes(bedtimeMinuteOfDay: Int, averageMinuteOfDay: Int): Int {
    val raw = abs(bedtimeMinuteOfDay - averageMinuteOfDay)
    return minOf(raw, 24 * 60 - raw)
}
