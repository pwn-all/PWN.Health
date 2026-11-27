package com.pwnall.health

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.NavigableMap
import java.util.NavigableSet
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

// What the pain record adds up to, for the Pain screen's cards and the doctor's sheet alike: one
// place, so a card and the sheet count a day by the same rules. They can still differ where the card
// was handed less of the record than the sheet ([PainDiary]).
//
// A day is a pain row's day — the day of the report, never the onset. A day counts as answered when
// it was kept by hand ([keptDays]: the sheet's own rule, sexual rows and the work timer's left out) or
// holds a pain row of either kind, "no pain today" included. A day with nothing written on it is
// unknown: never a day without pain, or every rate here would be a rate of how often the diary was
// forgotten. Where what came before a pain matters — the steps before it, a session before it — the
// pain is placed by its onset answer instead, so the days read before it are days before it began.
//
// Everything that compares two groups of days goes through [dayShareContrast] and its gates, and
// every comparison is of days that went together — never of what caused what.

/** The areas an analysis group pools. */
val PainGroup.areas: Set<PainRegion> get() = PainRegion.entries.filterTo(HashSet()) { it.group == this }

/**
 * The areas a session of this muscle group is matched against: the ones the pain map puts over it,
 * the joints it crosses included — a knee for the legs, an elbow and a wrist for the arms, a
 * shoulder for the shoulders, a hip for the glutes. Fixed by [PainRegion.muscle] and nothing else,
 * so the app never searches every group against every area and reports whichever pair came out.
 */
val Muscle.painAreas: Set<PainRegion> get() = PainRegion.entries.filterTo(HashSet()) { it.muscle == this }

/** What one day held for a set of areas: its highest score, its words, and whether a painkiller was taken. */
data class PainDay(val nrs: Int?, val types: Set<PainType>, val painkiller: Boolean)

/** A set of areas over a window: the days answered, and those among them with pain in the areas. */
data class PainDaySeries(val answered: Set<LocalDate>, val pain: Map<LocalDate, PainDay>)

/**
 * A run of pain days in a set of areas, with gaps of up to [PAIN_EPISODE_GAP_DAYS] days merged —
 * the app's own heuristic, since a diary cannot tell one long pain from two close together — and a
 * day the person called the same pain as one in the run joined to it across any gap.
 *
 * [fit] is how the episode compares with soreness after training, from the row that opened it
 * ([trainingSoreness]), and — the episode's own part of the rule — whether it was over by the
 * seventh day after the session. [reasons] names what did not match.
 */
data class PainEpisode(
    val first: LocalDate,
    val last: LocalDate,
    val painDays: Int,
    val fit: SorenessFit,
    val reasons: Set<SorenessReason>,
    val opening: JournalEntry,
    val session: JournalEntry?,
) {
    val fitsSoreness: Boolean get() = fit == SorenessFit.FITS
}

/** Pain days whose gap to the next is at most this are one episode. */
const val PAIN_EPISODE_GAP_DAYS = 2

/** An episode that fits soreness is over by this many days after its session's day. */
const val SORENESS_EPISODE_DAYS = 7L

/** A rise of this much from the last answered day starts a pain again. */
const val PAIN_START_RISE = 2

/** How long a pain has to have been recorded before the sheet says so: the span, never a class. */
const val PAIN_SPAN_MONTHS = 3L

/**
 * Whether a row's onset answer places the start close enough to its report to count it as a start.
 * "About a week ago" is the fourth to the tenth day back: seven days, wider than the three days of
 * steps or the three-day training window a start is set against, so it could never sit inside one
 * and would only read as a start after nothing. It counts as a pain day, as "4 days to 3
 * months ago" ([PainOnset.WEEK]) does, with the answers that reach farther ([PainOnset.longStanding]).
 */
private val PainOnset?.placesAStart: Boolean
    get() = this == null || (!longStanding && this != PainOnset.ABOUT_A_WEEK)

/**
 * The pain record read once — which days were answered, and what each pain row said — for every
 * question the cards and the sheet ask of it.
 *
 * [journal] is as much of the record as the caller read, and it has to reach back before the window
 * asked about wherever what came before matters: whether a day is a pain start depends on the day
 * answered before it, and an episode or the first record of an area can lie before the window. The
 * doctor's sheet hands the whole record, the Pain screen its last half year ([PainScreen]). What lies
 * before the journal handed in is not seen, so a span or a first report older than that reads
 * differently on a card and on the sheet.
 */
class PainDiary(internal val journal: List<JournalEntry>, internal val zone: ZoneId = ZoneId.systemDefault()) {
    /** Every day answered, as far back as [journal] reaches. */
    val answered: NavigableSet<LocalDate>

    internal val rows: List<PainDiaryRow>

    private val byAreas = ConcurrentHashMap<Set<PainRegion>, NavigableMap<LocalDate, AreaDay>>()

    init {
        // All the soreness rule reads of the record, sifted once: the sheet hands the whole journal,
        // and each verdict would otherwise go through every row of it.
        val sifted = journal.filter { it.kind == KIND_PAIN || it.isOwnSession() }
        rows = journal.filter { it.kind == KIND_PAIN }.mapNotNull { entry ->
            val day = entry.localDay() ?: return@mapNotNull null
            val report = decodePainPayload(entry.measurementPayloadJson)
            if (report.none) null else PainDiaryRow(entry, day, report, zone) { trainingSoreness(entry, sifted, zone) }
        }
        // A painkiller noted on its own "for pain" says the day had pain: never a day without, until a
        // pain row — "No pain today" among them — answers it.
        val painDoses = journal.filter { isOneOffDose(it) && medTakenFor(it) == MedTakenFor.PAIN }
            .mapNotNullTo(HashSet()) { it.localDay() }
        val days = TreeSet(keptDays(journal) - painDoses)
        journal.filter { it.kind == KIND_PAIN }.mapNotNullTo(days) { it.localDay() }
        answered = Collections.unmodifiableNavigableSet(days)
    }

    /**
     * The pain days of a set of areas, as far back as [journal] reaches, each with the rows that named
     * the areas that day.
     */
    internal fun painDays(areas: Set<PainRegion>): NavigableMap<LocalDate, AreaDay> = byAreas.getOrPut(areas) {
        rows.filter { row -> row.report.regions.any { it.region in areas } }
            .groupBy(PainDiaryRow::day)
            .mapValuesTo(TreeMap()) { (_, ofDay) -> AreaDay(ofDay.sortedBy { it.entry.occurredAt }) }
    }
}

/** A pain row with its day, its answers and — worked out when first asked — its soreness verdict. */
internal class PainDiaryRow(
    val entry: JournalEntry,
    val day: LocalDate,
    val report: PainReport,
    zone: ZoneId,
    verdictOf: () -> SorenessVerdict,
) {
    val verdict: SorenessVerdict by lazy(verdictOf)

    /**
     * The days the pain can have begun on, by the same reading of the onset answer the soreness rule
     * makes ([painOnsetSpan]): the report's day for "just now" or "earlier today", the day before for
     * "yesterday", the two days before that for "2–3 days ago", the fourth to the tenth day back for
     * "about a week ago". Null for a month or more back ([PainOnset.longStanding]).
     */
    val onsetDays: ClosedRange<LocalDate>? = painOnsetSpan(report, entry, zone)?.let { span ->
        fun dayOf(at: Long) = minOf(day, Instant.ofEpochMilli(at).atZone(zone).toLocalDate())
        dayOf(span.first)..dayOf(span.last)
    }
}

/** One day's rows for a set of areas, in the order they were reported. */
internal class AreaDay(val rows: List<PainDiaryRow>) {
    /** The highest score of the day's rows: the day as the sheet's dots draw it. */
    val nrs: Int? = rows.mapNotNull { it.report.nrs }.maxOrNull()

    val facts = PainDay(nrs, rows.flatMapTo(HashSet()) { it.report.types }, rows.any { it.report.painkiller })
}

private fun <V> NavigableMap<LocalDate, V>.within(window: ClosedRange<LocalDate>): NavigableMap<LocalDate, V> =
    if (window.isEmpty()) TreeMap() else subMap(window.start, true, window.endInclusive, true)

private fun NavigableSet<LocalDate>.within(window: ClosedRange<LocalDate>): Set<LocalDate> =
    if (window.isEmpty()) emptySet() else subSet(window.start, true, window.endInclusive, true)

/** The days of [window] answered, and those with pain in [areas]: the analysis-group day series. */
fun PainDiary.series(areas: Set<PainRegion>, window: ClosedRange<LocalDate>): PainDaySeries =
    PainDaySeries(answered.within(window), painDays(areas).within(window).mapValues { it.value.facts })

/**
 * The episodes in [areas] that have a pain day in [window]. Days after the window are not read, so
 * a past window gives the same episodes whatever was written since; days before it are, so an
 * episode that began before the window is one episode rather than a new one on its first day.
 */
fun PainDiary.episodes(areas: Set<PainRegion>, window: ClosedRange<LocalDate>): List<PainEpisode> {
    if (window.isEmpty()) return emptyList()
    val days = painDays(areas).headMap(window.endInclusive, true)
    // The day each first report of a pain was filed on, for the follow-ups that name it.
    val firstDays = rows.filter { it.report.same == null }.associate { it.entry.occurredAt to it.day }
    val runs = mutableListOf<MutableList<LocalDate>>()
    days.forEach { (day, areaDay) ->
        val run = runs.lastOrNull()
        val close = run != null && ChronoUnit.DAYS.between(run.last(), day) <= PAIN_EPISODE_GAP_DAYS + 1
        // Said to be the same pain as one in the run: one episode however long the gap.
        val linked = run != null && areaDay.rows.any { row -> row.report.same?.let(firstDays::get) in run }
        if (run != null && (close || linked)) {
            run += day
        } else {
            runs += mutableListOf(day)
        }
    }
    return runs.filter { it.last() >= window.start }
        .map { run -> episodeOf(run, days.getValue(run.first()).rows.first()) }
}

/**
 * The episode over [run], judged from the row that opened it. A pain that fit the usual timing but
 * was still being recorded past the seventh day after the session no longer fits: soreness is
 * usually over within a week.
 */
private fun episodeOf(run: List<LocalDate>, opening: PainDiaryRow): PainEpisode {
    val verdict = opening.verdict
    val sessionDay = verdict.session?.localDay()
    val outlasted = sessionDay != null && run.last() > sessionDay.plusDays(SORENESS_EPISODE_DAYS)
    val fit = if (verdict.fit == SorenessFit.FITS && outlasted) SorenessFit.NOT_TYPICAL else verdict.fit
    val reasons = if (outlasted) verdict.reasons + SorenessReason.LASTED_OVER_A_WEEK else verdict.reasons
    return PainEpisode(run.first(), run.last(), run.size, fit, reasons, opening.entry, verdict.session)
}

/**
 * The days in [window] a pain in [areas] started: a pain day whose last answered day before it had
 * none there, or had a score at least [PAIN_START_RISE] lower. The start is the report's day.
 *
 * Three things are not starts. The first answered day of the record has nothing before it to say the
 * pain was not already there. A pain whose every row that day says it began about a week or more back
 * — the first report of a pain months old usually follows days kept without one — did not begin
 * that day, whatever came before it ([placesAStart]).
 * Nor did one whose rows are all follow-ups of a pain reported before ([PainReport.same]).
 */
fun PainDiary.starts(areas: Set<PainRegion>, window: ClosedRange<LocalDate>): Set<LocalDate> =
    startsFrom(areas, window).keys

/**
 * [starts], each with the days it can have begun on: a report on Friday of a pain that began "2–3
 * days ago" began on Tuesday or Wednesday. A new pain began when the earliest of that day's rows says
 * it did; a rise began after the last answered day, which still had the lower score, and no earlier
 * than the rows place the pain itself.
 */
internal fun PainDiary.startsFrom(
    areas: Set<PainRegion>,
    window: ClosedRange<LocalDate>,
): Map<LocalDate, ClosedRange<LocalDate>> {
    val pain = painDays(areas)
    val found = TreeMap<LocalDate, ClosedRange<LocalDate>>()
    pain.within(window).forEach { (day, today) ->
        val before = answered.lower(day) ?: return@forEach
        val then = pain[before]
        // A follow-up of a pain already reported began with that report, not on this day.
        val placed = today.rows.filter { it.report.same == null && it.report.onset.placesAStart }
            .mapNotNull { it.onsetDays }
        val began = when {
            then == null -> placed.takeIf { it.isNotEmpty() }?.let { spans ->
                spans.minOf { it.start }..spans.minOf { it.endInclusive }
            }

            today.nrs != null && then.nrs != null && today.nrs - then.nrs >= PAIN_START_RISE ->
                maxOf(before.plusDays(1), placed.minOfOrNull { it.start } ?: before.plusDays(1))..day

            else -> null
        }
        began?.let { found[day] = it }
    }
    return found
}

/** Every day from the first a start can have begun on to the day it was reported. */
private fun Map<LocalDate, ClosedRange<LocalDate>>.spanDays(): Set<LocalDate> = flatMapTo(HashSet()) { (day, began) ->
    generateSequence(began.start) { it.plusDays(1) }.takeWhile { it <= day }.toList()
}

/**
 * One analysis group over a window, as the Pain screen's "Where and how often" and the sheet's rows
 * print it. [sorenessDays] are the pain days inside episodes that fit soreness after training — part
 * of [painDays], so the two can be printed apart; [episodes] leaves those episodes out and
 * [sorenessEpisodes] counts them. [firstRecorded] and [lastRecorded] reach before the window.
 */
data class PainGroupSummary(
    val group: PainGroup,
    val answeredDays: Int,
    val painDays: Int,
    val sorenessDays: Int,
    val ratedDays: Int,
    val medianNrs: Double?,
    val highestNrs: Int?,
    val types: Map<PainType, Int>,
    val episodes: Int,
    val sorenessEpisodes: Int,
    val painkillerDays: Int,
    val firstRecorded: LocalDate?,
    val lastRecorded: LocalDate?,
) {
    /**
     * Whether pain here was recorded over more than [PAIN_SPAN_MONTHS] months, first record to last:
     * a span of the record, printed with the classification's threshold as its reason and never as
     * a class.
     */
    val recordedOverThreeMonths: Boolean
        get() = firstRecorded != null && lastRecorded != null &&
            lastRecorded.isAfter(firstRecorded.plusMonths(PAIN_SPAN_MONTHS))
}

fun PainDiary.summary(group: PainGroup, window: ClosedRange<LocalDate>): PainGroupSummary {
    val areas = group.areas
    val series = series(areas, window)
    val episodes = episodes(areas, window)
    val sore = episodes.filter { it.fitsSoreness }
    val rated = series.pain.values.mapNotNull { it.nrs }
    val recorded = if (window.isEmpty()) TreeMap() else painDays(areas).headMap(window.endInclusive, true)
    return PainGroupSummary(
        group = group,
        answeredDays = series.answered.size,
        painDays = series.pain.size,
        sorenessDays = series.pain.keys.count { day -> sore.any { day in it.first..it.last } },
        ratedDays = rated.size,
        medianNrs = rated.map(Int::toDouble).medianOrNull(),
        highestNrs = rated.maxOrNull(),
        types = series.pain.values.flatMap { it.types }.groupingBy { it }.eachCount(),
        episodes = episodes.size - sore.size,
        sorenessEpisodes = sore.size,
        painkillerDays = series.pain.values.count { it.painkiller },
        firstRecorded = recorded.firstEntry()?.key,
        lastRecorded = recorded.lastEntry()?.key,
    )
}

/** The fewest starts, and control days, before "Movement before pain started" prints its counts. */
const val MOVEMENT_MIN_STARTS = 5
const val MOVEMENT_MIN_CONTROLS = 15

/** The days before a start whose steps are averaged: the three before it, never the day itself. */
private const val MOVEMENT_DAYS_BEFORE = 3L

/**
 * Steps in the three days before a pain started, against the same on the other answered days of the
 * same calendar months — a case-crossover, each start compared with the person's own days around
 * it. No day the pain can already have been there is read: the three days are the ones before the
 * earliest day its onset answer allows, since a pain lowers the walking of the days it hurts. Those
 * days and the report's are no control days either.
 *
 * [starts] and [controls] count the days with steps on all three days before them, a start only
 * where its month holds a control day; [startSteps] is the starts' mean three-day mean, and
 * [controlSteps] the control days' with each month weighed by the starts in it — every start set
 * against its own month, so a season with more walking and more starts does not pass for a pattern.
 * [lowestThirdUpTo] and [highestThirdFrom] cut the person's own three-day means over the window into
 * thirds, and [afterLowest] and [afterHighest] set the share of starts after such a stretch against
 * the share of control days after one — both ends, since too little and too much movement have each
 * gone with back pain in other people. Their counts are the plain ones; [controlShareAfterLowest] and
 * [controlShareAfterHighest] are the control days' shares weighed month by month as [controlSteps]
 * is, and a comparison is notable only when the starts' share also clears that by the card's fifteen
 * points, on the same side.
 */
data class MovementBeforeStarts(
    val starts: Int,
    val controls: Int,
    val startSteps: Double?,
    val controlSteps: Double?,
    val lowestThirdUpTo: Double?,
    val highestThirdFrom: Double?,
    val afterLowest: DayShareContrast,
    val afterHighest: DayShareContrast,
    val controlShareAfterLowest: Double?,
    val controlShareAfterHighest: Double?,
) {
    val enough: Boolean get() = starts >= MOVEMENT_MIN_STARTS && controls >= MOVEMENT_MIN_CONTROLS
}

/**
 * [MovementBeforeStarts] for [areas] over [window], from [steps] — a day at nought is a counter
 * nobody carried, as the sheet reads it, so a stretch with one is not a stretch of steps at all.
 */
fun PainDiary.movementBeforeStarts(
    areas: Set<PainRegion>,
    window: ClosedRange<LocalDate>,
    steps: List<DaySteps>,
): MovementBeforeStarts {
    val before = StepsBefore(steps)
    val exposure = generateSequence(window.start) { it.plusDays(1) }.takeWhile { it <= window.endInclusive }
        .mapNotNull { day -> before.of(day)?.let { day to it } }
        .toMap()
    val allStarts = startsFrom(areas, window)
    val placed = allStarts.spanDays()
    // Each start keyed by its report's day, with the steps before the first day it can have begun.
    val stretches = allStarts.mapNotNull { (day, began) ->
        before.of(began.start)?.let { day to (began.start to it) }
    }.toMap()
    val controlsByMonth = answered.within(window).filter { it !in placed && it in exposure }.groupBy(YearMonth::from)
    val starts = stretches.filterValues { (began, _) -> YearMonth.from(began) in controlsByMonth }
    val startsByMonth = starts.values.groupingBy { YearMonth.from(it.first) }.eachCount()
    val controls = controlsByMonth.filterKeys { it in startsByMonth }.values.flatten()
    // A control day stands for its month's starts shared out over its month's controls.
    val weight = { day: LocalDate ->
        val month = YearMonth.from(day)
        startsByMonth.getValue(month).toDouble() / controlsByMonth.getValue(month).size
    }
    fun weighed(of: (LocalDate) -> Double): Double? =
        controls.takeIf { it.isNotEmpty() }?.let { days -> days.sumOf { weight(it) * of(it) } / days.sumOf(weight) }
    val (low, high) = thirdsOf(exposure.values)

    // With fewer than three stretches there are no thirds, and nothing is after one.
    fun after(inThird: (Double) -> Boolean): Pair<DayShareContrast, Double?> {
        val base = weighed { if (inThird(exposure.getValue(it))) 1.0 else 0.0 }
        val contrast = dayShareContrast(
            starts.mapValues { (_, stretch) -> inThird(stretch.second) },
            controls.associateWith { inThird(exposure.getValue(it)) },
        )
        return contrast.alsoFrom(base) to base
    }
    val (afterLowest, lowestBase) = after { low != null && it <= low }
    val (afterHighest, highestBase) = after { high != null && it >= high }
    return MovementBeforeStarts(
        starts = starts.size,
        controls = controls.size,
        startSteps = starts.takeIf { it.isNotEmpty() }?.values?.map { it.second }?.average(),
        controlSteps = weighed(exposure::getValue),
        lowestThirdUpTo = low,
        highestThirdFrom = high,
        afterLowest = afterLowest,
        afterHighest = afterHighest,
        controlShareAfterLowest = lowestBase,
        controlShareAfterHighest = highestBase,
    )
}

/** Each day's mean steps over the three days before it, or null where one of them has no count. */
private class StepsBefore(steps: List<DaySteps>) {
    private val carried = steps.filter { it.steps > 0L }
        .mapNotNull { row -> row.day.toLocalDateOrNull()?.let { it to row.steps } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, counts) -> counts.max() }

    fun of(day: LocalDate): Double? = (1..MOVEMENT_DAYS_BEFORE).map {
        carried[day.minusDays(it)] ?: return null
    }.average()
}

/** Where the lowest third of [means] ends and the highest begins; neither under three means. */
private fun thirdsOf(means: Collection<Double>): Pair<Double?, Double?> {
    val sorted = means.sorted().takeIf { it.size >= 3 } ?: return null to null
    val third = (sorted.size + 2) / 3
    return sorted[third - 1] to sorted[sorted.size - third]
}

/**
 * The contrast as it stands when the exposed share is also [CARD_SHARE_POINTS] from [base], the
 * other group's share weighed month by month, and on the same side of it; otherwise no clear
 * difference. The plain shares can differ only because the starts fell in other months than the
 * control days did, and then the months are what differ.
 */
private fun DayShareContrast.alsoFrom(base: Double?): DayShareContrast {
    val share = exposed.share
    val plain = other.share
    val holds = base != null && share != null && plain != null &&
        apartBy(share, base, CARD_SHARE_POINTS) && (share - base) * (share - plain) > 0.0
    return if (!notable || holds) this else copy(verdict = ContrastVerdict.NO_CLEAR_DIFFERENCE)
}

/**
 * Wound-up or restless days against pain in [sameDay] and on [nextDay]. A day is tense when an
 * Energy answer that day was wound up or restless — the sheet's own rule — and calm when it was
 * answered and none was; days without an answer are in neither group.
 *
 * The same-day comparison is worded symmetrically: an evening's answer can follow the day's
 * headache as easily as come before it. The next-day one carries its reverse — pain the day before
 * a tense day — and only [LaggedContrast.directed] lets a card say "on days after".
 */
data class TensionAndPain(val sameDay: DayShareContrast, val nextDay: LaggedContrast<DayShareContrast>)

fun PainDiary.tensionAndPain(areas: Set<PainRegion>, window: ClosedRange<LocalDate>): TensionAndPain {
    val tense = tenseDays(journal, window)
    val series = series(areas, window)
    val outcome = { day: LocalDate -> if (day in series.answered) day in series.pain else null }
    fun contrast(shift: Long) = splitDays(tense, shift, outcome).let { (a, b) -> dayShareContrast(a, b) }
    return TensionAndPain(contrast(0), LaggedContrast(contrast(1), contrast(-1)))
}

/**
 * The days of [window] the Energy question was answered, each true when an answer that day was wound
 * up or restless — the rungs [woundUpDays] counts — and false when none was.
 */
internal fun tenseDays(journal: List<JournalEntry>, window: ClosedRange<LocalDate>): Map<LocalDate, Boolean> = journal
    .mapNotNull { entry ->
        val score = entry.energyScore?.takeIf { it in 1..Energy.SETTLED.score } ?: return@mapNotNull null
        entry.localDay()?.takeIf { it in window }?.let { it to score }
    }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, scores) -> scores.any { it <= Energy.RESTLESS.score } }

/**
 * Pain in some areas on long work days against the other work days, with days off beside them
 * rather than pooled with either. Same-day only, and worded symmetrically: a pain can shorten a
 * working day as easily as a long one can come before a pain.
 */
data class WorkAndPain(val longVsOther: DayShareContrast, val daysOff: DayShare)

/**
 * [WorkAndPain] over the answered days of [window] from the first day the work timer was ever used:
 * the days before it are not days off, only days nobody timed. A work day is one with net minutes
 * after the midnight split; a long one reaches [longDayMinutes]. A day a capped session touches is
 * in neither group: worked, for how long nobody knows ([WorkRecord]).
 */
fun PainDiary.workAndPain(areas: Set<PainRegion>, window: ClosedRange<LocalDate>, longDayMinutes: Int): WorkAndPain {
    val record = WorkRecord(journal, zone)
    val minutes = record.minutes
    val tracked = record.tracked
    val series = series(areas, window)
    val known = if (tracked ==
        null
    ) {
        emptyList()
    } else {
        series.answered.filter { it >= tracked && it !in record.uncertain }
    }
    val (work, off) = known.partition { it in minutes }
    val long = work.associateWith { isLongWorkDay(minutes.getValue(it), longDayMinutes) }
    val (exposed, other) = splitDays(long, 0) { day -> day in series.pain }
    return WorkAndPain(dayShareContrast(exposed, other), DayShare(off.count { it in series.pain }, off.size))
}

/** The fewest sessions of a group, and starts in its areas, before "After training" prints its counts. */
const val TRAINED_MIN_SESSIONS = 5
const val TRAINED_MIN_STARTS = 3

/** The days after a session its window covers: the session's own day and the two after it. */
private const val TRAINED_WINDOW_DAYS = 2L

/**
 * Pain starting in a trained group's areas within days 0–2 after its sessions, against how much of
 * the record such windows cover. [sessions] counts the days in the window with a session of the
 * group — one a day, since each exercise of a workout is a row of its own. [daysInWindows] is the
 * base: the answered days inside a window, of all answered days. [startsInWindows] sets the starts
 * inside a window — every day the onset answer allows inside one, so a pain that may have begun
 * before the session is not after it — against the other answered days inside one. Starts inside
 * episodes that fit soreness after training are left out of it and counted in [sorenessStarts], so a
 * sore week after a first leg day does not read as a pattern.
 */
data class TrainedAreaPattern(
    val muscle: Muscle,
    val sessions: Int,
    val sorenessStarts: Int,
    val daysInWindows: DayShare,
    val startsInWindows: DayShareContrast,
) {
    val enough: Boolean
        get() = sessions >= TRAINED_MIN_SESSIONS && startsInWindows.exposed.of >= TRAINED_MIN_STARTS
}

/**
 * [TrainedAreaPattern] for [muscle] over [window]. The sessions are the workouts logged here that
 * worked it ([isOwnSession]); one in the two days before the window still covers the window's
 * first days, but only those inside it are counted.
 */
fun PainDiary.trainedArea(muscle: Muscle, window: ClosedRange<LocalDate>): TrainedAreaPattern {
    val areas = muscle.painAreas
    val sessionDays = journal.filter { it.isOwnSession() && muscle in it.workedMuscles() }
        .mapNotNullTo(HashSet()) { it.localDay() }
    val covered = sessionDays.flatMapTo(HashSet()) { day -> (0..TRAINED_WINDOW_DAYS).map(day::plusDays) }
    val answeredIn = answered.within(window)
    val starts = startsFrom(areas, window)
    val sore = episodes(areas, window).filter { it.fitsSoreness }
    val soreStarts = starts.keys.filter { day -> sore.any { day in it.first..it.last } }.toSet()
    val inWindows = { began: ClosedRange<LocalDate> ->
        generateSequence(began.start) { it.plusDays(1) }.takeWhile { it <= began.endInclusive }.all { it in covered }
    }
    return TrainedAreaPattern(
        muscle = muscle,
        sessions = sessionDays.count { it in window },
        sorenessStarts = soreStarts.size,
        daysInWindows = DayShare(answeredIn.count { it in covered }, answeredIn.size),
        startsInWindows = dayShareContrast(
            (starts - soreStarts).mapValues { (_, began) -> inWindows(began) },
            (answeredIn - starts.spanDays()).associateWith { it in covered },
        ),
    )
}
