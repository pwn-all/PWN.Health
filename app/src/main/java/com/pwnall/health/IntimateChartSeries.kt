package com.pwnall.health

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// The week-by-week series the sex-life charts page draws, worked out with no canvas in sight.
//
// A week is the seven days ending on today's weekday, newest last, so the last point is always the
// last seven days and every point spans the same number of days. A week the record says nothing
// about is a gap, never a zero: a line along the floor would claim seven mornings with none, or a
// week without a drink, that nobody recorded. Nothing here is a verdict — the charts set a count
// beside the mornings in the same weeks and say no more than that.

/** The charts read the last twelve weeks: three four-week stretches, "Solo and partnered" included. */
internal const val INTIMATE_CHART_WEEKS = 12

private const val WEEK_DAYS = 7L

/**
 * A week's mornings make a point once this many of them were answered, Firm, Partly or None. One or
 * two answers make a share of 0 or 100% out of almost nothing, and a line through those would swing
 * from floor to ceiling on a forgotten tap.
 */
internal const val MORNING_WEEK_MIN_ANSWERED = 3

/**
 * A week's bar — its mean night, its mean day's steps, its drinks — needs a reading on this many of its
 * seven days, so that every bar on the page stands on as many days as any other.
 */
internal const val WEEK_MIN_READINGS = 4

/**
 * A chart with the mornings is shown once this many weeks carry a point — and, for a chart that sets
 * something beside them, a bar in the same week. Fewer is not a line, it is a few dots.
 */
internal const val MORNING_CHART_MIN_WEEKS = 4

/** "Solo and partnered" is drawn once acts fall in this many different weeks: one week is not a shape. */
internal const val ACTS_CHART_MIN_WEEKS = 2

/** The minutes of an hour, for a night drawn in hours. */
private const val MINUTES_PER_HOUR = 60.0

/** The [count] weeks to [today], oldest first, each the seven days ending on a weekday of today's. */
internal fun chartWeeks(today: LocalDate, count: Int = INTIMATE_CHART_WEEKS): List<ClosedRange<LocalDate>> =
    (count - 1 downTo 0).map { back ->
        val to = today.minusDays(back * WEEK_DAYS)
        to.minusDays(WEEK_DAYS - 1)..to
    }

/**
 * The mornings week by week: each week's firm-or-partly mornings of those answered ([shares]), as
 * [morningShares] counts them — "Didn't notice" and no check-in are unknown, never a morning with none.
 */
internal data class MorningWeeks(val shares: List<DayShare>) {
    /** Each week's share, or null for a week with fewer than [MORNING_WEEK_MIN_ANSWERED] answered. */
    val points: List<Double?> get() = shares.map { week -> week.share.takeIf { week.of >= MORNING_WEEK_MIN_ANSWERED } }

    /**
     * His own average: the firm-or-partly mornings of every week that makes a point, pooled, so a
     * week of seven answers weighs more than a week of three. Null with no such week.
     */
    val average: Double? get() = shares.filter { it.of >= MORNING_WEEK_MIN_ANSWERED }
        .let { kept -> DayShare(kept.sumOf { it.days }, kept.sumOf { it.of }).share }

    /**
     * Each point's distance from [average], in shares: above zero a week of more firm-or-partly
     * mornings than his own average, below zero fewer, null where the week has no point.
     */
    val fromAverage: List<Double?> get() = average.let { mean ->
        points.map { point -> if (point == null || mean == null) null else point - mean }
    }

    val weeksWithPoint: Int get() = points.count { it != null }
}

internal fun morningWeeks(journal: List<JournalEntry>, weeks: List<ClosedRange<LocalDate>>): MorningWeeks =
    MorningWeeks(weeks.map { morningShares(journal, it).firmOrPartlyShare })

/** What a comparison chart sets as bars beside the mornings. */
internal enum class MorningExposure { NIGHT, STEPS, ALCOHOL }

/**
 * One comparison: [bars], a figure a week rising from the floor, null where the week has none; and
 * [mornings], that week's distance from his own average ([MorningWeeks.fromAverage]), drawn from a
 * centre line.
 */
internal data class MorningPairChart(
    val exposure: MorningExposure,
    val bars: List<Double?>,
    val mornings: List<Double?>,
)

/**
 * The comparison, or null when it has too little to show: fewer than [MORNING_CHART_MIN_WEEKS] weeks
 * with a bar and a point both, or no week with anything above zero — twelve empty bars beside the
 * mornings of a man who never drinks say nothing about drinking.
 */
internal fun morningPairChart(
    exposure: MorningExposure,
    bars: List<Double?>,
    mornings: MorningWeeks,
): MorningPairChart? {
    val points = mornings.points
    val both = bars.indices.count { bars[it] != null && points.getOrNull(it) != null }
    val shown = both >= MORNING_CHART_MIN_WEEKS && bars.any { (it ?: 0.0) > 0.0 }
    return if (shown) MorningPairChart(exposure, bars, mornings.fromAverage) else null
}

/**
 * Each week's mean night in hours: the sleep that ended each morning ([nightEndingOn]), a nap left
 * out, over the mornings that have one, from [WEEK_MIN_READINGS] of them.
 *
 * Every sleep is read under the day it ended, as the app files its own: a file import files a night
 * under the evening it began, where it would share a key with the night before and one of the two
 * would be lost. And a nap ([isNap]) is read only beside the day's own sleep: a day whose every sleep
 * is a nap has no night, where [nightEndingOn] would take the nap for one, and a nap that ended more
 * than [NIGHT_BREAK_MS] before the day's own sleep began — a doze at dawn before the sleep after a
 * night shift — is left out, where it would be the earliest end the night is read from and the
 * sleep itself would be lost. A doze after a waking stays with the night it continues, which
 * [nightEndingOn] joins as it does for every other reader.
 */
internal fun weeklyNightHours(
    journal: List<JournalEntry>,
    weeks: List<ClosedRange<LocalDate>>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Double?> {
    // Only rows nightEndingOn reads at all: a broken one would stand in for the day's own sleep and let
    // the nap beside it through.
    val sleeps = journal.filter(JournalEntry::isNightSpan).map { entry ->
        entry.copy(day = Instant.ofEpochMilli(entry.sleepEndAt!!).atZone(zone).toLocalDate().toString())
    }
    val kept = sleeps.groupBy { it.day }.values.flatMap { day ->
        val (naps, own) = day.partition { it.isNap(zone) }
        val begins = own.mapNotNull { it.sleepStartAt }.minOrNull()
        if (begins == null) emptyList() else own + naps.filter { (it.sleepEndAt ?: 0L) >= begins - NIGHT_BREAK_MS }
    }
    val nights = nightEndingOn(kept)
    return weeks.map { week ->
        weekMean(nights.filterKeys { it in week }.values.map(Int::toDouble))?.div(MINUTES_PER_HOUR)
    }
}

/**
 * A nap: under [NAP_LONGEST_MS] and begun after [NAP_EARLIEST] on the day it ended. Neither alone
 * will do. By its length alone, a night of two hours — a bad night, the very thing set beside the
 * mornings — would drop out of the mean in the reassuring direction; by the clock alone, the day's
 * sleep after a night shift would, and so would every night a file import files under the day it
 * began.
 * Read off the sleep's own end rather than the row's day, which an import sets to its start.
 *
 * Limitation: a heuristic with a known blind spot — under three hours begun after six, a night that
 * would not start until then, or a short day sleep after a shift, reads as a nap on a day with
 * nothing else; telling those apart needs the person's own schedule, which the app does not ask.
 */
private fun JournalEntry.isNap(zone: ZoneId): Boolean {
    val start = sleepStartAt ?: return false
    val end = sleepEndAt ?: return false
    val endDay = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
    return end - start < NAP_LONGEST_MS && start >= endDay.atTime(NAP_EARLIEST).atZone(zone).toInstant().toEpochMilli()
}

/** Past any nap, and short of a night anybody would sleep by choice. */
private const val NAP_LONGEST_MS = 3L * 60 * 60 * 1_000

/** A sleep begun before this on the morning it ends in is the end of a night, however short. */
private val NAP_EARLIEST: LocalTime = LocalTime.of(6, 0)

/**
 * Each week's mean steps a day, over the days the step table counted any, from [WEEK_MIN_READINGS]
 * of them: a day at zero is a phone left on a table as often as a day without a walk. Not [today],
 * whose count is still climbing: read at nine in the morning it would pull the newest bar down.
 */
internal fun weeklySteps(
    days: List<DaySteps>,
    weeks: List<ClosedRange<LocalDate>>,
    today: LocalDate = weeks.last().endInclusive,
): List<Double?> {
    val byDay = stepSeries(days).byDay.filterValues { it > 0.0 }
    return weeks.map { week -> weekMean(byDay.filterKeys { it in week && it < today }.values.toList()) }
}

/**
 * Each week's standard drinks ([STANDARD_DRINK_G] of ethanol each), read only off its days kept by
 * hand ([keptDays]) and scaled from them to seven days, from [WEEK_MIN_READINGS] of them; a week with
 * fewer is null. A day with nothing written is not a day without a drink: counting it as one would
 * draw a week of one glass of water as a dry week, and a week of two evenings out as a week of two
 * drinking days and five dry ones. Scaled, a week of four kept days stands beside a week of seven.
 * Not [today]: a breakfast written down makes it a kept day hours before its evening has happened.
 */
internal fun weeklyDrinks(
    journal: List<JournalEntry>,
    weeks: List<ClosedRange<LocalDate>>,
    today: LocalDate = weeks.last().endInclusive,
): List<Double?> {
    val kept = keptDays(journal)
    val grams = ethanolSeries(journal).byDay
    return weeks.map { week ->
        weekMean(
            kept.filter {
                it in week && it < today
            }.map { grams[it] ?: 0.0 },
        )?.times(WEEK_DAYS)?.div(STANDARD_DRINK_G)
    }
}

/** A week's mean, from [WEEK_MIN_READINGS] readings; fewer make no figure at all. */
internal fun weekMean(values: List<Double>): Double? = values.takeIf { it.size >= WEEK_MIN_READINGS }?.average()

/**
 * One week of acts: partnered and solo, and how many of each carried the "little desire" and the
 * "erection difficulty" ticks. The ticks are counted on the two kinds of act apart, so a mark lands
 * on the line of the act it was ticked on.
 */
internal data class ActsWeek(
    val partnered: Int,
    val solo: Int,
    val partneredLowDesire: Int = 0,
    val soloLowDesire: Int = 0,
    val partneredErection: Int = 0,
    val soloErection: Int = 0,
)

/**
 * "Solo and partnered" week by week. A week with nothing written down on any of its days — nothing
 * kept by hand ([keptDays]) and no act or morning check-in — is null: before the diary was kept, or
 * over a holiday away from it, there were no zero weeks, only weeks nobody wrote down, and it breaks
 * both lines. What fills a week by itself does not count: the nights the sleep estimate writes, the
 * readings of a ring or a scale, another app's imports. A week with something written and no act is
 * a zero.
 */
internal data class ActsChart(val weeks: List<ActsWeek?>)

/** The acts of [weeks], or null while acts fall in fewer than [ACTS_CHART_MIN_WEEKS] of them. */
internal fun actsChart(journal: List<JournalEntry>, weeks: List<ClosedRange<LocalDate>>): ActsChart? {
    val active = keptDays(journal) + journal.filter { it.isSexualData() }.mapNotNull { it.localDay() }
    val dated = journal.mapNotNull { entry -> entry.localDay()?.let { it to entry } }
    val counted = weeks.map { week ->
        if (active.none { it in week }) {
            null
        } else {
            val inWeek = dated.filter { it.first in week }.map { it.second }
            val partnered = inWeek.filter {
                it.cycleLog() in INTIMATE_LOGS
            }.map { sexNotesOf(it.measurementPayloadJson) }
            val solo = inWeek.filter { it.kind == KIND_MASTURBATION }.map { sexNotesOf(it.measurementPayloadJson) }
            ActsWeek(
                partnered = partnered.size,
                solo = solo.size,
                partneredLowDesire = partnered.count { SexNote.LOW_DESIRE in it },
                soloLowDesire = solo.count { SexNote.LOW_DESIRE in it },
                partneredErection = partnered.count { SexNote.ERECTION in it },
                soloErection = solo.count { SexNote.ERECTION in it },
            )
        }
    }
    val withActs = counted.count { it != null && it.partnered + it.solo > 0 }
    return if (withActs >= ACTS_CHART_MIN_WEEKS) ActsChart(counted) else null
}

/**
 * Everything the page shows, each chart null or left out until it has enough to show. [mornings] and
 * the comparisons are there only for a profile asked the morning question; the alcohol comparison only
 * for one the app asks about drinks.
 */
internal data class IntimateChartsData(
    val weeks: List<ClosedRange<LocalDate>>,
    val mornings: MorningWeeks?,
    val pairs: List<MorningPairChart>,
    val acts: ActsChart?,
) {
    val empty: Boolean get() = mornings == null && pairs.isEmpty() && acts == null
}

internal fun intimateChartsData(
    journal: List<JournalEntry>,
    steps: List<DaySteps>,
    today: LocalDate,
    mornings: Boolean,
    alcohol: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): IntimateChartsData {
    val weeks = chartWeeks(today)
    val morning = if (mornings) morningWeeks(journal, weeks) else null
    val pairs = morning?.let { found ->
        listOfNotNull(
            morningPairChart(MorningExposure.NIGHT, weeklyNightHours(journal, weeks, zone), found),
            morningPairChart(MorningExposure.STEPS, weeklySteps(steps, weeks), found),
            if (alcohol) morningPairChart(MorningExposure.ALCOHOL, weeklyDrinks(journal, weeks), found) else null,
        )
    }.orEmpty()
    return IntimateChartsData(
        weeks = weeks,
        mornings = morning?.takeIf { it.weeksWithPoint >= MORNING_CHART_MIN_WEEKS },
        pairs = pairs,
        acts = actsChart(journal, weeks),
    )
}

/**
 * The days of the twelve weeks. The page reads one more, before them, for the night that ended on the
 * first morning and was filed the evening before ([weeklyNightHours]).
 */
internal const val INTIMATE_CHART_DAYS = INTIMATE_CHART_WEEKS * WEEK_DAYS
