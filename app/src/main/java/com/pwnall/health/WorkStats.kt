package com.pwnall.health

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// What the work sessions add up to, for the Work screen's cards and the doctor's sheet alike.
//
// Hours are the time between Start and Stop less the pauses that were pressed, split at midnight
// ([workMinutesByDay]): a work day is a local date with net minutes on it. Only time that was tracked
// counts, and a day nobody timed is not a day off — it is unknown, unless the diary was kept by hand
// that day. Days before the timer was first used are unknown whatever else was written on them.
//
// A session the timer stopped itself at its cap is almost always a Stop nobody pressed: its end is
// not known, so neither are the hours of the days it touches. It is counted apart, and those days
// are unknown — never sixteen-hour days, never days off — until the end is set by hand.
//
// The figures are counts and the person's own median; none of them is compared with a limit, a
// norm or anybody else's week.

/** Whether [minutes] of net work make a long day: at or past the setting, which is the person's own. */
internal fun isLongWorkDay(minutes: Double, longDayMinutes: Int): Boolean = minutes >= longDayMinutes - MINUTE_SLACK

/** Room for the last bit of a double: minutes are summed from milliseconds. */
private const val MINUTE_SLACK = 1e-6

/** A week is counted when this many of its seven days were kept or had a session. */
const val WORK_COUNTED_WEEK_DAYS = 5

/** The weekly bands the sheet counts weeks in: the 48-hour average of EU working-time rules, and the 55 h band. */
const val WORK_WEEK_48_HOURS = 48.0
const val WORK_WEEK_55_HOURS = 55.0

/** Work after this hour of the day is evening work. */
val WORK_EVENING_FROM: LocalTime = LocalTime.of(20, 0)

/** A usual day off counts as worked from this many net minutes. */
const val WORK_DAY_OFF_MINUTES = 15.0

/** A gap between two days' work shorter than this is a short one: the daily rest of EU working-time rules. */
const val WORK_SHORT_GAP_HOURS = 11L

/** A stretch between Start (or Resume) and Pause (or Stop) of at least this long is a long one. */
const val WORK_LONG_STRETCH_HOURS = 6L

private const val HOUR_MS = 3_600_000L
private const val MINUTES_PER_HOUR = 60.0

/**
 * One whole counted week, starting on the caller's first day of the week (the locale's on the Work
 * page, Monday in the doctor's report), with its net minutes and its days with work. [monday] is that
 * first day, whatever weekday it is.
 */
data class WorkWeek(val monday: LocalDate, val netMinutes: Double, val workDays: Int) {
    val hours: Double get() = netMinutes / MINUTES_PER_HOUR
}

/**
 * The longest run of consecutive days with work: [days] from [from] to [to]. [atLeast] when a day
 * next to it is unknown or outside the window — the run may have gone on — rather than a day kept
 * without work.
 */
data class WorkRun(val days: Int, val from: LocalDate, val to: LocalDate, val atLeast: Boolean)

/**
 * The work timer over a window, as the Work screen's counts and the sheet's rows print it.
 *
 * [dailyMinutes] holds the days with work. [weeks] are the counted weeks: whole weeks inside the
 * window, cut from the caller's first day of the week ([wholeWorkWeeks]), with
 * [WORK_COUNTED_WEEK_DAYS] days kept or with a session. [eveningDays]
 * are the work days with any net time after [WORK_EVENING_FROM]; [usualDaysOff] the usual days off
 * worked [WORK_DAY_OFF_MINUTES] or more, of those known. [shortGaps] of [gapPairs] pairs of
 * consecutive days with sessions left under [WORK_SHORT_GAP_HOURS] between the last end on the first
 * and the first start on the second. [longStretchSessions] of [timedSessions] had a stretch of
 * [WORK_LONG_STRETCH_HOURS] or more without a pressed pause; [typedSessions] were typed in afterwards
 * and are left out of that count, since nobody pressed a pause in them. [cappedSessions] stopped at
 * the timer's cap with their end still unset, and are in none of the rest.
 */
data class WorkSummary(
    val dailyMinutes: Map<LocalDate, Double>,
    val weeks: List<WorkWeek>,
    val longDays: Int,
    val eveningDays: Int,
    val usualDaysOff: DayShare,
    val longestRun: WorkRun?,
    val shortGaps: Int,
    val gapPairs: Int,
    val timedSessions: Int,
    val typedSessions: Int,
    val longStretchSessions: Int,
    val cappedSessions: Int,
) {
    val workDays: Int get() = dailyMinutes.size

    val longestDayMinutes: Double? get() = dailyMinutes.values.maxOrNull()

    val medianWeekHours: Double?
        get() = weeks.map(WorkWeek::hours).sorted().takeIf { it.isNotEmpty() }?.let { hours ->
            val middle = hours.size / 2
            if (hours.size % 2 == 1) hours[middle] else (hours[middle - 1] + hours[middle]) / 2
        }

    /** The shortest counted week to the longest, in hours. */
    val weekHoursRange: ClosedFloatingPointRange<Double>?
        get() = weeks.takeIf { it.isNotEmpty() }?.map(WorkWeek::hours)?.let { it.min()..it.max() }

    val weeksAtLeast48: Int get() = weeks.count { it.hours >= WORK_WEEK_48_HOURS - MINUTE_SLACK }

    val weeksAtLeast55: Int get() = weeks.count { it.hours >= WORK_WEEK_55_HOURS - MINUTE_SLACK }

    /** Counted weeks with work on all seven days. */
    val weeksWithoutADayOff: Int get() = weeks.count { it.workDays == DayOfWeek.entries.size }
}

/**
 * The work record read once — net minutes by day, the days known, and the rows themselves — for the
 * work figures and for the pain figures that set work days beside pain, so the two cannot disagree
 * about which days were work days.
 */
internal class WorkRecord(journal: List<JournalEntry>, val zone: ZoneId) {
    private val sessions = journal.filter { it.kind == KIND_WORK }

    /** Sessions the timer stopped at its cap, their end still unset: their length is not known. */
    val capped: List<JournalEntry> = sessions.filter { workFlag(it, WORK_CAPPED) }

    /** Every other session: the ones whose end is known. */
    val rows: List<JournalEntry> = sessions.filterNot { workFlag(it, WORK_CAPPED) }

    /** The days a capped session has time on: worked, but for how long nobody knows. */
    val uncertain: Set<LocalDate> =
        capped.flatMap(::workSegmentsOf).flatMapTo(HashSet()) { segment ->
            splitAtMidnight(segment, zone).map { it.first }
        }

    /** Net minutes on the days with work whose hours are known. */
    val minutes: Map<LocalDate, Double> =
        workMinutesByDay(rows, zone).filter { (day, minutes) -> minutes > 0.0 && day !in uncertain }

    /** The first day the timer was used, a capped session's included. */
    val tracked: LocalDate? = (minutes.keys + uncertain).minOrNull()

    /** Days kept by hand or with work, from the first day the timer was used; a capped day is neither. */
    val known: Set<LocalDate> = tracked?.let { from ->
        (keptDays(journal) + minutes.keys).filterTo(HashSet()) { it >= from && it !in uncertain }
    }.orEmpty()
}

fun workSummary(
    journal: List<JournalEntry>,
    window: ClosedRange<LocalDate>,
    zone: ZoneId,
    daysOff: Set<DayOfWeek>,
    longDayMinutes: Int,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
): WorkSummary {
    val record = WorkRecord(journal, zone)
    val daily = record.minutes.filterKeys { it in window }
    val inWindow = { row: JournalEntry -> row.localDay()?.let { it in window } == true }
    val sessions = record.rows.filter(inWindow)
    val (typed, timed) = sessions.partition { workFlag(it, WORK_TYPED) }
    val usualOff = record.known.filter { it in window && it.dayOfWeek in daysOff }
    val gaps = restGaps(sessions, record.uncertain)
    return WorkSummary(
        dailyMinutes = daily,
        weeks = countedWeeks(record, window, firstDayOfWeek),
        longDays = daily.values.count { isLongWorkDay(it, longDayMinutes) },
        eveningDays = eveningWorkDays(record.rows, record.zone).count { it in daily },
        usualDaysOff = DayShare(
            usualOff.count { (daily[it] ?: 0.0) >= WORK_DAY_OFF_MINUTES - MINUTE_SLACK },
            usualOff.size,
        ),
        longestRun = longestRun(daily.keys, record.known, window),
        shortGaps = gaps.count { it < WORK_SHORT_GAP_HOURS * HOUR_MS },
        gapPairs = gaps.size,
        timedSessions = timed.size,
        typedSessions = typed.size,
        longStretchSessions = timed.count { row ->
            workSegmentsOf(row).any { it.length >= WORK_LONG_STRETCH_HOURS * HOUR_MS }
        },
        cappedSessions = record.capped.count(inWindow),
    )
}

/**
 * Every whole week inside [window], oldest first, counted or not. A week starts on [firstDayOfWeek]:
 * the locale's on the Work page, so its bars and its insights cut the same seven days — a long
 * Sunday shift would otherwise move between weeks on an en-US phone — and Monday in the report for
 * the doctor.
 */
internal fun wholeWorkWeeks(
    window: ClosedRange<LocalDate>,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
): List<ClosedRange<LocalDate>> {
    if (window.isEmpty()) return emptyList()
    return generateSequence(window.start.with(TemporalAdjusters.nextOrSame(firstDayOfWeek))) { it.plusWeeks(1) }
        .takeWhile { it.plusDays(DAYS_IN_WEEK - 1) <= window.endInclusive }
        .map { first -> first..first.plusDays(DAYS_IN_WEEK - 1) }
        .toList()
}

/** Whole weeks inside [window] with enough of their days known. */
private fun countedWeeks(
    record: WorkRecord,
    window: ClosedRange<LocalDate>,
    firstDayOfWeek: DayOfWeek,
): List<WorkWeek> =
    wholeWorkWeeks(window, firstDayOfWeek)
        .map { week -> (0 until DAYS_IN_WEEK).map(week.start::plusDays) }
        .filter { days -> days.count { it in record.known } >= WORK_COUNTED_WEEK_DAYS }
        .map { days ->
            WorkWeek(days.first(), days.sumOf { record.minutes[it] ?: 0.0 }, days.count { it in record.minutes })
        }

private const val DAYS_IN_WEEK = 7L

/**
 * Net hours of each counted week in [stretch], the weeks counted back from its last day seven days at
 * a time: the shape the sheet's two stretches are read in. A month-long third then holds four whole
 * weeks whatever day it starts on, where the Monday-to-Sunday weeks of [workSummary] hold only three
 * when it starts in the middle of a week. A week counts as those do —
 * [WORK_COUNTED_WEEK_DAYS] of its days kept by hand or with a session, a capped session's days in
 * neither — and the days left over at the stretch's start are no week at all.
 */
internal fun workWeekHoursBack(
    journal: List<JournalEntry>,
    stretch: ClosedRange<LocalDate>,
    zone: ZoneId,
): List<Double> {
    if (stretch.isEmpty()) return emptyList()
    val record = WorkRecord(journal, zone)
    val weeks = (ChronoUnit.DAYS.between(stretch.start, stretch.endInclusive) + 1) / DAYS_IN_WEEK
    return (0 until weeks)
        .map { back -> (0 until DAYS_IN_WEEK).map { stretch.endInclusive.minusDays(back * DAYS_IN_WEEK + it) } }
        .filter { days -> days.count { it in record.known } >= WORK_COUNTED_WEEK_DAYS }
        .map { days -> days.sumOf { record.minutes[it] ?: 0.0 } / MINUTES_PER_HOUR }
}

/** The days with any net work after [WORK_EVENING_FROM] on that same day, on the clock in [zone]. */
private fun eveningWorkDays(rows: List<JournalEntry>, zone: ZoneId): Set<LocalDate> =
    rows.flatMap(::workSegmentsOf).flatMapTo(HashSet()) { segment ->
        daysTouched(segment, zone).filter { day ->
            val evening = day.atTime(WORK_EVENING_FROM).atZone(zone).toInstant().toEpochMilli()
            val midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            minOf(segment.to, midnight) > maxOf(segment.from, evening)
        }
    }

/** The local dates a segment has time on. */
private fun daysTouched(segment: WorkSegment, zone: ZoneId): List<LocalDate> =
    generateSequence(Instant.ofEpochMilli(segment.from).atZone(zone).toLocalDate()) { it.plusDays(1) }
        .takeWhile { it.atStartOfDay(zone).toInstant().toEpochMilli() < segment.to }
        .toList()

/**
 * The longest run of consecutive work days in [window]; the latest of equal ones, and one that may
 * be longer before one that cannot.
 */
private fun longestRun(work: Set<LocalDate>, known: Set<LocalDate>, window: ClosedRange<LocalDate>): WorkRun? {
    fun bounded(day: LocalDate) = day in window && day in known
    return work.filter { it.minusDays(1) !in work }.map { from ->
        var to = from
        while (to.plusDays(1) in work) to = to.plusDays(1)
        WorkRun(
            days = (ChronoUnit.DAYS.between(from, to) + 1).toInt(),
            from = from,
            to = to,
            atLeast = !bounded(from.minusDays(1)) || !bounded(to.plusDays(1)),
        )
    }.maxWithOrNull(compareBy<WorkRun>({ it.days }, { it.atLeast }, { it.to }))
}

/**
 * The rest between consecutive days with sessions, in ms: from the last end of the sessions that
 * started on a day to the first start of those that started on the next. A pair with an [uncertain]
 * day in it is left out: a capped session may have filled the rest, or not.
 */
private fun restGaps(sessions: List<JournalEntry>, uncertain: Set<LocalDate>): List<Long> {
    val byDay = sessions.mapNotNull { row ->
        val segments = workSegmentsOf(row).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        row.localDay()?.let { it to segments }
    }.groupBy({ it.first }, { it.second })
    return byDay.keys.sorted().mapNotNull { day ->
        val next = day.plusDays(1)
        if (day in uncertain || next in uncertain) return@mapNotNull null
        val following = byDay[next] ?: return@mapNotNull null
        following.minOf { it.first().from } - byDay.getValue(day).maxOf { it.last().to }
    }
}

/**
 * Work days' evenings, next days and next nights on long work days against the other work days.
 *
 * [moodSameEvening] reads the mood entries written after the day's work ended; [moodNextDay] the
 * whole of the next day, with its reverse — the day before — as the control; [woundUp] the day's
 * Energy answers; [sleepNextNight] the night that ended the next morning ([nightEndingOn], so an
 * afternoon nap is not part of it), with its reverse — the night that ended that morning;
 * [drinkEvenings] and [drinkGrams] the day's drinks on days kept by hand, where a day without a
 * drink is a day without one. [daysOff] holds the same for known days without work, beside the
 * comparison rather than inside it.
 */
data class WorkDayComparisons(
    val longDays: Int,
    val otherDays: Int,
    val daysOffKnown: Int,
    val moodSameEvening: DayMeanContrast,
    val moodNextDay: LaggedContrast<DayMeanContrast>,
    val woundUp: DayShareContrast,
    val sleepNextNight: LaggedContrast<DayMeanContrast>,
    val drinkEvenings: DayShareContrast,
    val drinkGrams: DayMeanContrast,
    val daysOff: WorkDaysOff,
) {
    /** Alcohol evenings are notable when either the share with a drink or the grams are. */
    val drinksNotable: Boolean get() = drinkEvenings.notable || drinkGrams.notable
}

/** The days-off column of [WorkDayComparisons]: the same readings, on known days without work. */
data class WorkDaysOff(
    val mood: DayMean,
    val moodNextDay: DayMean,
    val woundUp: DayShare,
    val sleepNextNight: DayMean,
    val drinkEvenings: DayShare,
    val drinkGrams: DayMean,
)

fun workDayComparisons(
    journal: List<JournalEntry>,
    window: ClosedRange<LocalDate>,
    zone: ZoneId,
    longDayMinutes: Int,
): WorkDayComparisons {
    val record = WorkRecord(journal, zone)
    val long = record.minutes.filterKeys { it in window }.mapValues { isLongWorkDay(it.value, longDayMinutes) }
    val off = record.known.filter { it in window && it !in record.minutes }
    val mood = moodSeries(journal).byDay.filterKeys { it in window }
    val evening = eveningMood(journal, record, window)
    val tense = tenseDays(journal, window)
    val nights = nightEndingOn(journal).filterKeys { it in window }.mapValues { it.value.toDouble() }
    val kept = keptDays(journal)
    val grams = ethanolSeries(journal).byDay
    val drinks = { day: LocalDate -> if (day in kept && day in window) grams[day] ?: 0.0 else null }
    val moodApart = { a: Double, b: Double -> apartBy(a, b, MOOD_MIN_CHANGE) }
    val sleepApart = { a: Double, b: Double ->
        apartBy(a, b, SLEEP_MIN_CHANGE_MINUTES) && apartBy(a, b, SLEEP_MIN_SHARE * b)
    }
    fun means(shift: Long, outcome: (LocalDate) -> Double?, differs: (Double, Double) -> Boolean) =
        splitDays(long, shift, outcome).let { (a, b) -> dayMeanContrast(a, b, differs) }
    fun shares(outcome: (LocalDate) -> Boolean?) = splitDays(long, 0, outcome).let { (a, b) -> dayShareContrast(a, b) }
    return WorkDayComparisons(
        longDays = long.values.count { it },
        otherDays = long.values.count { !it },
        daysOffKnown = off.size,
        moodSameEvening = means(0, evening::get, moodApart),
        moodNextDay = LaggedContrast(means(1, mood::get, moodApart), means(-1, mood::get, moodApart)),
        woundUp = shares(tense::get),
        sleepNextNight = LaggedContrast(means(1, nights::get, sleepApart), means(0, nights::get, sleepApart)),
        drinkEvenings = shares { day -> drinks(day)?.let { it > 0.0 } },
        drinkGrams = means(0, drinks) { a, b -> apartBy(a, b, STANDARD_DRINK_G) },
        daysOff = WorkDaysOff(
            mood = off.dayMean(mood::get),
            moodNextDay = off.dayMean { mood[it.plusDays(1)] },
            woundUp = off.mapNotNull(tense::get).let { answers -> DayShare(answers.count { it }, answers.size) },
            sleepNextNight = off.dayMean { nights[it.plusDays(1)] },
            drinkEvenings = off.mapNotNull(drinks).let { grams -> DayShare(grams.count { it > 0.0 }, grams.size) },
            drinkGrams = off.dayMean(drinks),
        ),
    )
}

private fun Collection<LocalDate>.dayMean(of: (LocalDate) -> Double?): DayMean =
    mapNotNull(of).let { values -> DayMean(values.size, values.takeIf { it.isNotEmpty() }?.average()) }

/**
 * Each work day's mood from the entries written after its work ended — the latest end of any work
 * on that date, a night shift's cut at midnight — as one mean a day, like [moodSeries].
 */
private fun eveningMood(
    journal: List<JournalEntry>,
    record: WorkRecord,
    window: ClosedRange<LocalDate>,
): Map<LocalDate, Double> {
    val ended = HashMap<LocalDate, Long>()
    record.rows.flatMap(::workSegmentsOf).forEach { segment ->
        daysTouched(segment, record.zone).forEach { day ->
            val end = minOf(segment.to, day.plusDays(1).atStartOfDay(record.zone).toInstant().toEpochMilli())
            ended[day] = maxOf(ended[day] ?: end, end)
        }
    }
    return journal.mapNotNull { entry ->
        val score = entry.moodScore?.takeIf { it in 1..Mood.FUN.score } ?: return@mapNotNull null
        val day = entry.localDay()?.takeIf { it in window } ?: return@mapNotNull null
        if (ended[day]?.let { entry.occurredAt > it } != true) return@mapNotNull null
        day to score.toDouble()
    }.groupBy({ it.first }, { it.second }).mapValues { (_, scores) -> scores.average() }
}
