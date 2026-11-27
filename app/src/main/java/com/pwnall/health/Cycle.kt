package com.pwnall.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** A period start. Only the day is recorded — nothing else is needed to be useful. */
const val KIND_CYCLE = "CYCLE"

/**
 * The day the period finished. Its own kind rather than a flag inside [KIND_CYCLE_LOG], because
 * pairing starts with ends is the one cycle question that has to work without parsing anything.
 */
const val KIND_CYCLE_END = "CYCLE_END"

/** Flow, spotting, an ovulation test, mucus, sex: the entry carries which one in its payload. */
const val KIND_CYCLE_LOG = "CYCLE_LOG"

/** Payload key holding a [CycleLog] name. */
const val CYCLE_PAYLOAD_KEY = "cycle"

/**
 * The kinds of row the cycle screen reads, and so the only ones it asks the journal for: the starts
 * and ends, the day's logs, the solo act its calendar marks, and the moods and nights its two
 * patterns compare — a night is a measurement row. Collecting the whole journal would make every
 * write anywhere re-read years of meals and drinks for the screen to walk past.
 */
internal val CYCLE_SCREEN_KINDS = listOf(
    KIND_CYCLE,
    KIND_CYCLE_END,
    KIND_CYCLE_LOG,
    KIND_MASTURBATION,
    KIND_MOOD,
    KIND_MEASUREMENT,
)

/** Used until there is enough history to measure the person's own. */
const val DEFAULT_CYCLE_DAYS = 28

/**
 * The onset-to-onset gaps read as one cycle. Shorter is a double entry, longer a missed one — or a
 * stretch the app has no way to tell from one — and both are dropped rather than allowed to drag an
 * average or a prompt. One constant for the screen and for the advice, so a gap left out of the
 * average and the prediction never counts towards "long cycles, see a clinician" either, and the two
 * halves of one app cannot contradict each other about the same date.
 */
val PLAUSIBLE_CYCLE_DAYS = 15..60

/**
 * How many recent cycles anything is measured over: the last twelve plausible gaps since the last
 * break in the record ([CYCLE_BREAK_DAYS]). FIGO 2018 System 1 states its reference ranges over
 * twelve months; counted in months, that bound is applied only where one day is compared with past
 * cycles ([CycleFacts.comparable]) — twelve 40-day cycles run sixteen months and are all hers. The
 * screen and the advice both read the last twelve, so the two halves of the app answer alike about
 * the same date.
 */
const val CYCLE_REFERENCE_INTERVALS = 12

/**
 * A gap between two starts this long is a break in the record rather than a missed entry, and
 * nothing is averaged or predicted across it. Ninety days is where [cycleAdvice] already calls
 * bleeding absent. Whether she stopped bleeding — a pregnancy, a hormonal method — or stopped
 * logging, the app cannot tell, and either way the cycles before it are history: without the break,
 * the first start after two silent years would bring back a prediction, "regular" and a fertile
 * window, all drawn from cycles that old.
 */
internal const val CYCLE_BREAK_DAYS = 90

/**
 * FIGO 2018: menstrual bleeding longer than eight days is "prolonged". The figure is the one the
 * period pairs already measure, so it costs nothing to hold the record against it.
 */
const val PERIOD_LONG_DAYS = 8

/**
 * FIGO's own regularity band for the age group it quotes it for (26–41), applied at every age here.
 * It is used for one thing only — whether a fertile window may be drawn at all — and the tighter
 * bar can only withhold an estimate, never raise a false alarm, so the age-dependent widening the
 * advice uses is deliberately not applied.
 */
internal const val CYCLE_REGULAR_SPREAD = 7

/**
 * The luteal phase: the stretch from ovulation to the next period, which varies far less between
 * women than the follicular half does. Counting back from the *predicted next start* rather than
 * forward from the last one is what makes a calendar estimate worth drawing at all.
 */
internal const val LUTEAL_DAYS = 14L

/**
 * Sperm survive up to about five days in the reproductive tract, so the window is the five days
 * before ovulation plus the day itself — six days, the standard figure (Wilcox et al., NEJM 1995).
 */
internal const val FERTILE_LEAD_DAYS = 5L

/**
 * Everything Health Connect can hold about a cycle besides the period itself. One enum rather than
 * one kind per record type: they are all the same journal row with a different word in it.
 */
enum class CycleLog(val titleRes: Int) {
    FLOW_LIGHT(R.string.cycle_flow_light),
    FLOW_MEDIUM(R.string.cycle_flow_medium),
    FLOW_HEAVY(R.string.cycle_flow_heavy),
    SPOTTING(R.string.cycle_spotting),
    OVULATION_POSITIVE(R.string.cycle_ovulation_positive),
    OVULATION_HIGH(R.string.cycle_ovulation_high),
    OVULATION_NEGATIVE(R.string.cycle_ovulation_negative),
    MUCUS_DRY(R.string.cycle_mucus_dry),
    MUCUS_STICKY(R.string.cycle_mucus_sticky),
    MUCUS_CREAMY(R.string.cycle_mucus_creamy),
    MUCUS_WATERY(R.string.cycle_mucus_watery),
    MUCUS_EGG_WHITE(R.string.cycle_mucus_egg_white),
    SEX_PROTECTED(R.string.cycle_sex_protected),
    SEX_UNPROTECTED(R.string.cycle_sex_unprotected),

    /**
     * Period pain, and how much desire she noticed — the two the screen asks about beside the flow
     * and the mucus, and the two Health Connect has no record for. They are written to the journal
     * and to this app's own backup and go no further; see the note in [cycleHealthRecords].
     *
     * Both ladders carry their own "none" rung. A day with no pain is a fact about that day and the
     * screen has to be able to record it; leaving it out would make "nothing" and "not asked" the
     * same answer, which is the difference the rest of this file is built on.
     */
    PAIN_NONE(R.string.cycle_pain_none),
    PAIN_MILD(R.string.cycle_pain_mild),
    PAIN_MODERATE(R.string.cycle_pain_moderate),
    PAIN_SEVERE(R.string.cycle_pain_severe),
    DESIRE_NONE(R.string.cycle_desire_none),
    DESIRE_LOW(R.string.cycle_desire_low),
    DESIRE_MODERATE(R.string.cycle_desire_moderate),
    DESIRE_HIGH(R.string.cycle_desire_high),
}

/**
 * The screen's groups, and the order they are asked about in.
 *
 * Sexual activity is not among them: this screen is offered to a female profile only, and an act is
 * something a male profile logs too — it is logged on [IntimateScreen], where every profile of age
 * can reach it, and by the three buttons under these sections ([CycleIntimateRow]), and writes the
 * same [CycleLog] entries.
 */
enum class CycleSection(val titleRes: Int, val logs: List<CycleLog>) {
    FLOW(
        R.string.cycle_section_flow,
        listOf(CycleLog.FLOW_LIGHT, CycleLog.FLOW_MEDIUM, CycleLog.FLOW_HEAVY, CycleLog.SPOTTING),
    ),
    OVULATION(
        R.string.cycle_section_ovulation,
        listOf(
            CycleLog.OVULATION_POSITIVE,
            CycleLog.OVULATION_HIGH,
            CycleLog.OVULATION_NEGATIVE,
        ),
    ),
    MUCUS(
        R.string.cycle_section_mucus,
        listOf(
            CycleLog.MUCUS_DRY,
            CycleLog.MUCUS_STICKY,
            CycleLog.MUCUS_CREAMY,
            CycleLog.MUCUS_WATERY,
            CycleLog.MUCUS_EGG_WHITE,
        ),
    ),

    /**
     * Their own sections, not extra options inside [FLOW] and [MUCUS], and the reason is in
     * [StepsDb.logCycleLog]: an answer replaces every row on the day whose log is in the same
     * section. Pain filed under [FLOW] would delete the day's flow the moment it was tapped, and
     * nothing in the app would have reported it.
     */
    PAIN(
        R.string.cycle_section_pain,
        listOf(
            CycleLog.PAIN_NONE,
            CycleLog.PAIN_MILD,
            CycleLog.PAIN_MODERATE,
            CycleLog.PAIN_SEVERE,
        ),
    ),
    DESIRE(
        R.string.cycle_section_desire,
        listOf(
            CycleLog.DESIRE_NONE,
            CycleLog.DESIRE_LOW,
            CycleLog.DESIRE_MODERATE,
            CycleLog.DESIRE_HIGH,
        ),
    ),
}

/** Unknown names come from a future version's backup; they are ignored rather than crashed on. */
fun cycleLogOf(name: String?): CycleLog? = CycleLog.entries.firstOrNull { it.name == name }

/**
 * The [CycleLog] behind a journal row, or null for a row that is not one. The title is localised
 * and the subtitle is prose, so the stored enum name in the payload is the only part worth reading.
 *
 * Read by pattern rather than through `JSONObject`, for the reason [medSlotOf] gives: `org.json` is
 * a stub on the unit-test classpath, and a payload only a real device can read is a payload no test
 * can hold to. The value is one of this file's own enum names, so nothing here needs unescaping.
 */
internal fun JournalEntry.cycleLog(): CycleLog? {
    if (kind != KIND_CYCLE_LOG) return null
    val payload = measurementPayloadJson ?: return null
    return cycleLogOf(CYCLE_PAYLOAD_PATTERN.find(payload)?.groupValues?.get(1))
}

private val CYCLE_PAYLOAD_PATTERN = Regex("\"$CYCLE_PAYLOAD_KEY\"$SPACE*:$SPACE*\"([A-Z_]+)\"")

private val BLEEDING_LOGS = setOf(
    CycleLog.FLOW_LIGHT,
    CycleLog.FLOW_MEDIUM,
    CycleLog.FLOW_HEAVY,
    CycleLog.SPOTTING,
)

/**
 * Every day this record says there was bleeding on, as days rather than as rows.
 *
 * A closed period fills in: a start on the 1st and an end on the 5th is five bleeding days, because
 * that is what the two marks state between them, and two marks with three blanks between them would
 * be misread as two days of bleeding.
 *
 * A start still waiting for its end contributes only the days actually marked: the period is still
 * running, and how long it will run is not yet a fact about anything.
 */
fun recordedBleedingDays(entries: List<JournalEntry>): Set<LocalDate> {
    val days = mutableSetOf<LocalDate>()
    cyclePeriods(entries).forEach { period ->
        var day = period.start
        while (!day.isAfter(period.end)) {
            days += day
            day = day.plusDays(1)
        }
    }
    entries.forEach { entry ->
        val bleeding = entry.kind == KIND_CYCLE || entry.kind == KIND_CYCLE_END ||
            entry.cycleLog() in BLEEDING_LOGS
        if (bleeding) entry.day.toLocalDateOrNull()?.let { days += it }
    }
    return days
}

/**
 * One cycle: where it began, how long it runs, and where its fertile window sat.
 *
 * A past cycle is the honest one. Its length is measured — the gap to the start that actually
 * followed — and so is its ovulation, counted back fourteen days from a start that happened rather
 * than from one the average predicts. The current cycle is the estimate: its length is the average
 * and its window comes from [fertileWindow], which refuses an irregular record altogether.
 */
data class CycleView(
    val start: LocalDate,
    val lengthDays: Int,
    /** The start that followed, for a cycle that has one. Null on the cycle still running. */
    val nextStart: LocalDate?,
    val fertile: ClosedRange<LocalDate>?,
    /**
     * The day ovulation is put on. A finished cycle's window ends on it; the running cycle's window
     * is as wide as her cycles differ ([fertileWindow]) and holds it somewhere inside, so there it
     * is [estimatedOvulation] and not the window's last day.
     */
    val ovulation: LocalDate? = fertile?.endInclusive,
) {
    val current: Boolean get() = nextStart == null
}

/**
 * Every cycle the record holds, oldest first, the one still running last.
 *
 * A gap outside [PLAUSIBLE_CYCLE_DAYS] is a double or a missed entry rather than a cycle, and the
 * pair it would make is left out here for the same reason it is left out of the average — see
 * [cycleSummary]. The last entry is always the cycle running now, however long it has gone on.
 */
fun cycleViews(entries: List<JournalEntry>, summary: CycleSummary, today: LocalDate): List<CycleView> {
    val starts = entries
        .filter { it.kind == KIND_CYCLE }
        .mapNotNull { it.day.toLocalDateOrNull() }
        .distinct()
        .sorted()
    if (starts.isEmpty()) return emptyList()

    return starts.mapIndexedNotNull { index, start ->
        val next = starts.getOrNull(index + 1)
        if (next == null) {
            CycleView(
                start = start,
                // The cycle running now is drawn out to today when it overruns, and no further than
                // twice the average — where [CycleSummary.dayOfCycle] also gives up counting, and
                // where a start from two years ago would otherwise put seven hundred dots on a ring.
                lengthDays = ChronoUnit.DAYS.between(start, today).plus(1)
                    .coerceIn(summary.averageLengthDays.toLong(), summary.averageLengthDays * 2L)
                    .toInt(),
                nextStart = null,
                fertile = fertileWindow(summary),
                ovulation = estimatedOvulation(summary),
            )
        } else {
            val length = ChronoUnit.DAYS.between(start, next).toInt()
            if (length !in PLAUSIBLE_CYCLE_DAYS) {
                null
            } else {
                CycleView(start, length, next, observedFertileWindow(start, next))
            }
        }
    }
}

/**
 * The fertile window of a finished cycle, counted back from the start that actually followed.
 *
 * This is the one place the app can put a window on the calendar without predicting anything: the
 * luteal phase is the half that holds still, and the date it ran to is on record. No regularity
 * gate applies for that reason — it is not being extrapolated from anything.
 *
 * Null where the arithmetic would put the window on or before day one, which a cycle shorter than
 * about three weeks does. A window overlapping the period it follows is not an estimate, it is a
 * rounding error with a colour.
 */
internal fun observedFertileWindow(start: LocalDate, nextStart: LocalDate): ClosedRange<LocalDate>? {
    val ovulation = nextStart.minusDays(LUTEAL_DAYS)
    val opens = ovulation.minusDays(FERTILE_LEAD_DAYS)
    return (opens..ovulation).takeIf { opens.isAfter(start) }
}

/** A period that has both ends. */
data class CyclePeriod(val start: LocalDate, val end: LocalDate)

/**
 * Pairs each period start with the day it was marked as finished. A start still waiting for its end
 * is left out: Health Connect stores a period as a closed interval, and guessing the length would
 * publish an average as if it were a measurement.
 */
fun cyclePeriods(entries: List<JournalEntry>): List<CyclePeriod> {
    fun days(kind: String) = entries
        .filter { it.kind == kind }
        .mapNotNull { it.day.toLocalDateOrNull() }
        .distinct()
        .sorted()

    val starts = days(KIND_CYCLE)
    val ends = days(KIND_CYCLE_END)
    return starts.mapIndexedNotNull { index, start ->
        val nextStart = starts.getOrNull(index + 1)
        val end = ends.firstOrNull { it >= start && (nextStart == null || it < nextStart) }
        end?.let { CyclePeriod(start, it) }
    }
}

/**
 * The end marks that go when [start] is deleted: the end of the period it begins, unless another
 * start on the same day still begins it. Nothing for any other row — an end deleted alone is the
 * period reopened, as [StepsDb.reopenCyclePeriod] reopens it.
 *
 * The calendar and the entry editor delete one row at a time, so without this a start deleted there
 * would leave its end behind. An end with no start of its own closes nothing, or closes an older
 * start still waiting for one, since [cyclePeriods] gives a start the first end before the next; and
 * it would be drawn as a day of bleeding and held against every period filed around it later
 * ([pastPeriodFits]), with nothing on the cycle screen to say why.
 */
internal fun periodEndsDeletedWith(start: JournalEntry, marks: List<JournalEntry>): List<JournalEntry> {
    val shared = marks.any { it.kind == KIND_CYCLE && it.day == start.day && it.id != start.id }
    if (start.kind != KIND_CYCLE || shared) return emptyList()
    val end = cyclePeriods(marks).firstOrNull { it.start.toString() == start.day }?.end?.toString()
    return marks.filter { it.kind == KIND_CYCLE_END && it.day == end }
}

data class CycleSummary(
    val lastStart: LocalDate?,
    /** The middle length of the cycles measured, not their mean — see [usualCycleDays]. */
    val averageLengthDays: Int,
    /** Null until two periods have been recorded — before that there is nothing to predict from. */
    val nextExpected: LocalDate?,
    val dayOfCycle: Int?,
    val recordedCycles: Int,
    /**
     * The shortest and longest of the cycles measured, so the screen can print the span the
     * prediction was drawn from instead of a single average standing in for a spread of eleven
     * days. Null while there is nothing to span.
     */
    val shortestDays: Int? = null,
    val longestDays: Int? = null,
    /**
     * Typical bleeding length from the periods that have both ends, as a median rather than a mean:
     * one period left open for a fortnight and closed in a hurry would drag a mean by days. Null
     * until one period has been closed.
     */
    val periodLengthDays: Int? = null,
) {
    /**
     * Whether the recorded cycles are close enough in length for anything to be read off them
     * beyond the next start — see [CYCLE_REGULAR_SPREAD]. Fewer than [CYCLE_MIN_FOR_FERTILE]
     * cycles is not irregular, it is unmeasured, and answers false for the same reason.
     */
    val regular: Boolean
        get() = recordedCycles >= CYCLE_MIN_FOR_FERTILE &&
            shortestDays != null && longestDays != null &&
            longestDays - shortestDays <= CYCLE_REGULAR_SPREAD
}

/** Three cycles before a calendar estimate of ovulation is drawn from them. */
const val CYCLE_MIN_FOR_FERTILE = 3

/**
 * The days around the estimated ovulation, or null where this record cannot support the estimate.
 *
 * Deliberately conservative, because this is the one figure on the screen somebody might act on.
 * It is drawn only from a regular record of at least [CYCLE_MIN_FOR_FERTILE] cycles, and it is
 * counted back from the next start rather than forward from the last one — the luteal half is the
 * half that holds still.
 *
 * Which next start is the other half of being conservative. "Regular" still lets her cycles differ
 * by [CYCLE_REGULAR_SPREAD] days, and the cycle running now may be her shortest or her longest, so
 * the window opens where her shortest cycle would open it and shuts where her longest would shut
 * it. Counted from the average alone it would stand on six days, and on a record running 25 to 32
 * days every other day would read "Low" — four of the six fertile days of her own 32-day cycle among
 * them, by this app's own arithmetic. For cycles of 26 to 32 days the span is days 8 to 19, the
 * fixed window of the Standard Days Method (Arévalo, Jennings & Sinai, Contraception 2002;65:333-8);
 * on a record that never varies it is the plain six-day window.
 *
 * The page prints under the word that it is an estimate from dates and not a method of
 * contraception, the calendar under its key, and the dialog behind the "?" in full; the widget
 * names the phase without that sentence. An irregular record gets nothing rather than a wider
 * window still: a window nobody can trust is worse than a blank.
 *
 * So does a record whose shortest cycle ran under twenty days, where the arithmetic opens the
 * window on the day the cycle began or before it — inside the period, which [observedFertileWindow]
 * refuses a finished cycle for the same reason.
 */
fun fertileWindow(summary: CycleSummary): ClosedRange<LocalDate>? {
    val last = summary.lastStart?.takeIf { summary.regular }
    val shortest = summary.shortestDays
    val longest = summary.longestDays
    if (last == null || shortest == null || longest == null) return null
    val opens = last.plusDays(shortest - LUTEAL_DAYS - FERTILE_LEAD_DAYS)
    return (opens..last.plusDays(longest - LUTEAL_DAYS)).takeIf { opens.isAfter(last) }
}

/**
 * The one day ovulation is put on in the cycle running now: [LUTEAL_DAYS] back from the start the
 * average predicts. Answered where [fertileWindow] is and nowhere else, and always inside it — the
 * average lies between her shortest cycle and her longest.
 */
fun estimatedOvulation(summary: CycleSummary): LocalDate? =
    summary.nextExpected?.minusDays(LUTEAL_DAYS)?.takeIf { fertileWindow(summary) != null }

/**
 * What the cycle screen offers today, which is decided by the record rather than by the screen.
 *
 * Asking only whether there is a start without an end, and offering "log a period
 * start" whenever there is not, would put the button on the day a period was closed — a day the
 * same screen draws as a bleeding day — and pressing it would do real damage: a start dated inside
 * an existing period steals that period's end for itself, leaving a ten-day period orphaned and a
 * one-day period in its place, because [cyclePeriods] pairs a start with the first end before the
 * next start. So the question is "what day is she on", and it has three answers.
 */
sealed interface CycleAction {
    /** A period is running. The one thing to say about it is when it stopped. */
    data class Finish(val start: LocalDate) : CycleAction

    /**
     * Today falls inside a period she has already closed — she ended it today, or dated the end
     * ahead. Starting a period is not a thing that can happen today; saying it has not finished
     * after all is, and that is the correction this offers.
     */
    data class Reopen(val period: CyclePeriod) : CycleAction

    /** The ordinary case: no bleeding recorded today, so a period may begin. */
    data object Start : CycleAction
}

/** Which of the three today is. */
fun cycleAction(entries: List<JournalEntry>, today: LocalDate): CycleAction {
    val starts = entries
        .filter { it.kind == KIND_CYCLE }
        .mapNotNull { it.day.toLocalDateOrNull() }
        .distinct()
        .sorted()
    val periods = cyclePeriods(entries)

    val open = starts.lastOrNull()?.takeIf { start -> periods.none { it.start == start } }
    if (open != null && !open.isAfter(today)) return CycleAction.Finish(open)

    // The period today sits inside, if there is one. Checked over every closed period rather than
    // only the last, because an end dated ahead can leave a later period on file as well.
    val covering = periods.lastOrNull { !today.isBefore(it.start) && !today.isAfter(it.end) }
    return covering?.let { CycleAction.Reopen(it) } ?: CycleAction.Start
}

/**
 * Whether a period remembered afterwards can be filed on [start] to [end] without touching one
 * already on record. A null [end] is a period she says is still running, which reaches to today.
 *
 * One missed start is what this is for: the gap it leaves is read as a single cycle twice as long,
 * which moves the average for a year and takes "regular" away with it, and the record button only
 * ever writes today. The danger is the one [CycleAction] describes. [cyclePeriods] gives each start
 * the first end before the next start, so a start or an end dropped inside another period's days,
 * or days laid over another period's marks, re-pairs both. So the days have to be clear of every
 * mark and every period on file — a start still waiting for its end holds everything from itself
 * to today — with [PERIOD_RESUME_DAYS] to spare on either side, since a period that ends the day
 * before another begins is that same period ([resumedPeriodEnd]).
 */
internal fun pastPeriodFits(
    entries: List<JournalEntry>,
    start: LocalDate,
    end: LocalDate?,
    today: LocalDate,
): Boolean {
    val running = (cycleAction(entries, today) as? CycleAction.Finish)?.let { it.start..today }
    return periodClear(entries, start, end ?: today, today, listOfNotNull(running))
}

/**
 * Whether the period running since [open] can be closed as [from] to [to]: the same rule, over the
 * record without that start's own mark, which the closing keeps or moves ([StepsDb.logCyclePeriod]).
 *
 * Checked before the dialog closes rather than left to the write: a start moved into or against an
 * earlier period is turned down by the write after the dialog has gone, with nothing said, and an end
 * moved past another period would re-pair both. No running period is held against it — it is the one
 * running — so an older start left without its end does not shut it for good either.
 */
internal fun closingPeriodFits(
    marks: List<JournalEntry>,
    open: LocalDate,
    from: LocalDate,
    to: LocalDate,
    today: LocalDate,
): Boolean = periodClear(
    marks.filterNot { it.kind == KIND_CYCLE && it.day == open.toString() },
    from,
    to,
    today,
    emptyList(),
)

/** [start] to [last] clear of every mark and every period on file, and of [running], a day either side. */
private fun periodClear(
    entries: List<JournalEntry>,
    start: LocalDate,
    last: LocalDate,
    today: LocalDate,
    running: List<ClosedRange<LocalDate>>,
): Boolean {
    val from = start.minusDays(PERIOD_RESUME_DAYS)
    val to = last.plusDays(PERIOD_RESUME_DAYS)
    val marks = entries
        .filter { it.kind == KIND_CYCLE || it.kind == KIND_CYCLE_END }
        .mapNotNull { it.day.toLocalDateOrNull() }
    val taken = cyclePeriods(entries).map { it.start..it.end } + running
    return !start.isAfter(last) && !last.isAfter(today) &&
        marks.none { it in from..to } &&
        taken.none { !it.start.isAfter(to) && !it.endInclusive.isBefore(from) }
}

/** What the home screen has to say about the cycle, when it has anything to say. */
enum class CycleNudgeKind {
    /** A period was started and never closed, and it has been open longer than one lasts. */
    CLOSE_PERIOD,

    /** Nothing has been logged for long enough that the record has a hole in it. */
    LOG_START,

    /**
     * Nothing has ever been logged. The one nudge that does not wait: until a first start is on
     * record there is no day of the cycle, no length, no prediction and nothing on this screen at
     * all, and the day it is asked for is the day the answer is still remembered.
     */
    FIRST_CYCLE,

    /**
     * A period is running. Not a reminder and not a gap in the record — the front page saying so,
     * and offering the two things there are to do about it while it lasts.
     */
    PERIOD_RUNNING,
}

/** The nudge itself: which of the two, and the day it is counted from. */
data class CycleNudge(val kind: CycleNudgeKind, val since: LocalDate?)

/** Longer than this and a period that was never closed is a record somebody forgot to finish. */
internal const val PERIOD_OPEN_DAYS = 8

/** How far past the expected start the app waits before saying anything. */
internal const val CYCLE_LATE_DAYS = 7

/**
 * What to say about the cycle under the day's headline number, if anything.
 *
 * Three things are worth interrupting for and nothing else is: a record with nothing in it yet — a
 * cycle screen with no first day is an empty screen, and she is the only one who can say when the
 * last period began — a period left open, since the end is the half of the record that gets
 * forgotten and without it that period's length is unknown for ever, and a gap where the next start
 * should have been. Everything else the cycle card below already says, and saying it twice on one
 * screen is how a screen stops being read.
 *
 * Returns null for the ordinary case, which is most days. The first of the three is offered on the
 * first open rather than after a month of silence: the woman who installs a health app in the
 * middle of her period is exactly the one who can answer it, and a month later she is guessing.
 * The home screen keeps the dismissal, so the ask is an ask and not a nag.
 */
fun cycleNudge(entries: List<JournalEntry>, today: LocalDate): CycleNudge? {
    val summary = cycleSummary(entries, today)
    val start = summary.lastStart ?: return CycleNudge(CycleNudgeKind.FIRST_CYCLE, null)

    val closed = cyclePeriods(entries).any { it.start == start }
    val sinceStart = ChronoUnit.DAYS.between(start, today)
    return when {
        !closed && sinceStart >= PERIOD_OPEN_DAYS -> CycleNudge(CycleNudgeKind.CLOSE_PERIOD, start)

        // Inside a period the card stops nudging and starts being useful: the two acts a woman
        // wants in those days are recording what it is like and saying when it stopped.
        !closed && sinceStart >= 0 -> CycleNudge(CycleNudgeKind.PERIOD_RUNNING, start)

        closed && sinceStart >= summary.averageLengthDays + CYCLE_LATE_DAYS ->
            CycleNudge(CycleNudgeKind.LOG_START, start)

        else -> null
    }
}

/**
 * Cycle length from the person's own history, not from a textbook average.
 *
 * Two starts make one cycle, so nothing is predicted before that; textbook 28 days is used only as
 * the label for the first entry. Gaps outside [PLAUSIBLE_CYCLE_DAYS] are dropped as a missed or
 * double entry rather than being allowed to drag the average, and a gap of [CYCLE_BREAK_DAYS] or
 * more ends the stretch that is measured: only the cycles after the last such break are counted.
 * The length itself is their middle one — see [usualCycleDays].
 */
fun cycleSummary(entries: List<JournalEntry>, today: LocalDate): CycleSummary {
    val starts = entries
        .filter { it.kind == KIND_CYCLE }
        .mapNotNull { it.day.toLocalDateOrNull() }
        .distinct()
        .sorted()

    val last = starts.lastOrNull()
        ?: return CycleSummary(null, DEFAULT_CYCLE_DAYS, null, null, 0)

    val lengths = starts.zipWithNext { from, to -> ChronoUnit.DAYS.between(from, to).toInt() }
        .takeLastWhile { it < CYCLE_BREAK_DAYS }
        .filter { it in PLAUSIBLE_CYCLE_DAYS }
        .takeLast(CYCLE_REFERENCE_INTERVALS)
    // Rounded, not truncated: a length of 29.9 days truncates to 29 and moves every prediction a
    // day earlier than the history supports.
    val average = usualCycleDays(lengths)?.roundToInt() ?: DEFAULT_CYCLE_DAYS

    return CycleSummary(
        lastStart = last,
        averageLengthDays = average,
        nextExpected = if (lengths.isEmpty()) null else last.plusDays(average.toLong()),
        dayOfCycle = (ChronoUnit.DAYS.between(last, today) + 1).toInt().takeIf { it in 1..average * 2 },
        recordedCycles = lengths.size,
        shortestDays = lengths.minOrNull(),
        longestDays = lengths.maxOrNull(),
        periodLengthDays = medianPeriodLength(entries),
    )
}

/**
 * The length her cycles usually run, in days: the middle one of [lengths], or the mean of the two
 * middle ones, as [medianOrNull] has it; null of none.
 *
 * Not their mean. One start nobody logged leaves a gap of two cycles — fifty-six days among
 * twenty-eights, well inside [PLAUSIBLE_CYCLE_DAYS] and so counted — and a mean would take it at its
 * word: every prediction a week late, a late-period prompt that waits a week too long, and three
 * cycles of thirty days with one forgotten tap among them read as "long cycles, worth a mention to
 * a clinician". The middle length is hers whatever one gap says, which is also why the period's own
 * length is a median ([medianPeriodLength]). One function for the screen, the advice and the
 * profile's card, because the three print the same figure about the same person.
 */
internal fun usualCycleDays(lengths: List<Int>): Double? = lengths.map(Int::toDouble).medianOrNull()

/**
 * The middle length of the closed periods on record, over the same reference stretch as the cycles.
 *
 * Both ends are inclusive days, so a period marked on the 1st and closed on the 5th is five days —
 * the figure a clinician means by "how long do your periods last", and the figure [PERIOD_LONG_DAYS]
 * is stated against. Lengths no period can have are dropped: the pair-up in [cyclePeriods] cannot
 * produce a backwards one, but a restored backup or another app's records can produce one that runs
 * for months.
 */
internal fun medianPeriodLength(entries: List<JournalEntry>): Int? {
    val lengths = cyclePeriods(entries)
        .takeLast(CYCLE_REFERENCE_INTERVALS)
        .map { ChronoUnit.DAYS.between(it.start, it.end).toInt() + 1 }
        .filter { it in 1..PLAUSIBLE_PERIOD_DAYS.last }
        .sorted()
    if (lengths.isEmpty()) return null
    // The middle of an even count is the mean of its two middle lengths, as [medianOrNull] has it: the
    // upper one alone would print periods of three and seven days as "7 days · Median" on the doctor's sheet.
    val upper = lengths.size / 2
    return if (lengths.size % 2 == 1) lengths[upper] else ((lengths[upper - 1] + lengths[upper]) / 2.0).roundToInt()
}

/**
 * Below this a period that has just been closed is asked about again.
 *
 * Two days is not impossible — it is the short end of ordinary — but it is also exactly what an
 * accidental tap produces the day after a start, and the two are indistinguishable in the record.
 * So the app does not refuse it and does not silently keep it: it asks once, with the dates in
 * front of her, and takes her answer.
 */
internal const val PERIOD_SHORT_DAYS = 2

/**
 * How soon after a period was closed a new start is read as the same period carrying on.
 *
 * "It stopped" and "it started again the next morning" is one period with a quiet day in it, not two
 * periods a day apart. Left as two, [cyclePeriods] would file a one-day period and a two-day one,
 * [medianPeriodLength] would read the median of her periods as one day, and every figure drawn from
 * it would follow. The rejoin is in the write path — see [StepsDb.startOrResumeCycle] — because a
 * screen is not the only thing that writes a start.
 */
internal const val PERIOD_RESUME_DAYS = 1L

/**
 * What a period can be, in whole days, for the purpose of being counted. The ceiling is well past
 * [PERIOD_LONG_DAYS] on purpose: a fifteen-day period is the record this app most wants to show a
 * woman, so it is counted and flagged, not thrown away. Longer than three weeks is a forgotten tap.
 */
val PLAUSIBLE_PERIOD_DAYS = 1..21

/**
 * What her own record measures, as data rather than as sentences: what the line under the cycle
 * screen's week is chosen from.
 *
 * Kept out of the UI for the reason the advice engine keeps its own args out of it: every one of
 * these is a claim about a woman's body, and a claim assembled in a composable is a claim no test
 * can hold to. Each case carries the figures it was drawn from, so the line can print them and a
 * test can check them.
 */
sealed interface CycleInsight {
    /**
     * Her own cycles, and whether they sit close enough together to be called regular. Given only
     * from [CYCLE_MIN_FOR_FERTILE] cycles on: fewer measure neither answer.
     */
    data class Length(val cycles: Int, val shortest: Int, val longest: Int, val regular: Boolean) : CycleInsight

    /** How long her periods run, from the ones she has closed. */
    data class PeriodLength(val median: Int, val periods: Int, val longest: Int) : CycleInsight

    /**
     * Mood in the first half of the cycle against the second, from her own taps. [difference] is
     * follicular minus luteal on the 1..5 scale, so positive means the first half reads better.
     */
    data class MoodPattern(val difference: Double, val days: Int) : CycleInsight

    /** Sleep on bleeding days against the rest, in minutes a night, from her own nights. */
    data class SleepPattern(val periodMinutes: Int, val otherMinutes: Int, val nights: Int) : CycleInsight
}

/** Two of each half before mood or sleep is compared across them — see [cycleInsights]. */
internal const val CYCLE_PATTERN_MIN_DAYS = 8

/** Below this the two halves are the same number with noise on it, and nothing is said. */
private const val MOOD_PATTERN_MIN_DIFFERENCE = 0.4
private const val SLEEP_PATTERN_MIN_MINUTES = 20

/**
 * Everything her own record measures: her cycles, her periods, then the two patterns that need
 * months of it. Where she is today is the headline's and the phase card's to say, and is not here.
 *
 * Every case is drawn from recorded rows and returns nothing where the rows do not support it. No
 * line here names a condition, for the same reason [cycleAdvice] does not.
 */
fun cycleInsights(
    entries: List<JournalEntry>,
    summary: CycleSummary,
    today: LocalDate,
): List<CycleInsight> = buildList {
    // Not before the cycles are a measurement. With one or two on record [CycleSummary.regular] is
    // false for "not measured", and the line under the week would read it as "your cycle length varies".
    val measured = summary.recordedCycles >= CYCLE_MIN_FOR_FERTILE
    if (measured && summary.shortestDays != null && summary.longestDays != null) {
        add(
            CycleInsight.Length(
                cycles = summary.recordedCycles,
                shortest = summary.shortestDays,
                longest = summary.longestDays,
                regular = summary.regular,
            ),
        )
    }

    val periods = cyclePeriods(entries).takeLast(CYCLE_REFERENCE_INTERVALS)
        .map { ChronoUnit.DAYS.between(it.start, it.end).toInt() + 1 }
        .filter { it in PLAUSIBLE_PERIOD_DAYS }
    summary.periodLengthDays?.let { median ->
        if (periods.isNotEmpty()) {
            add(CycleInsight.PeriodLength(median, periods.size, periods.max()))
        }
    }

    moodPattern(entries, summary, today)?.let(::add)
    sleepPattern(entries, today)?.let(::add)
}

/**
 * The one reading of her record the line under the week has room for, most specific first: the two
 * patterns that take months of entries to say anything, then whether her cycles hold still.
 *
 * The patterns come before the length because the length is on file from the third cycle on and the
 * mood pattern needs a regular record: with the length first, the mood pattern would be worked out on
 * every change to the journal and never printed, and the sleep pattern only before the third cycle.
 */
internal fun patternInsight(insights: List<CycleInsight>): CycleInsight? =
    insights.firstOrNull { it is CycleInsight.MoodPattern }
        ?: insights.firstOrNull { it is CycleInsight.SleepPattern }
        ?: insights.firstOrNull { it is CycleInsight.Length }

/**
 * Mood in the follicular half against the luteal half, over the cycles on record.
 *
 * The split is the estimated ovulation of a cycle her usual length — [LUTEAL_DAYS] before its end,
 * counted on from each start — which is where [followingBound] stops the numbered days, so the two
 * halves of the app divide the month identically and the halves mean what a clinician means by them
 * rather than "first fortnight, second fortnight". A cycle that ran a few days shorter or longer is
 * split on the same day. Answered only from a regular record, where those few days are at most
 * [CYCLE_REGULAR_SPREAD] — an irregular one has no reliable midpoint to split on — and only where
 * both halves carry enough days to compare.
 */
@Suppress("ReturnCount")
internal fun moodPattern(
    entries: List<JournalEntry>,
    summary: CycleSummary,
    today: LocalDate,
): CycleInsight.MoodPattern? {
    if (!summary.regular) return null
    val follicularDays = followingBound(summary)
    if (follicularDays < 1) return null

    val starts = entries.filter { it.kind == KIND_CYCLE }
        .mapNotNull { it.day.toLocalDateOrNull() }
        .distinct()
        .sorted()
    if (starts.isEmpty()) return null

    val follicular = mutableListOf<Double>()
    val lutealScores = mutableListOf<Double>()
    entries.filter { (it.moodScore ?: 0) in 1..5 }
        .groupBy { it.day }
        .forEach { (day, rows) ->
            val date = day.toLocalDateOrNull() ?: return@forEach
            if (date.isAfter(today)) return@forEach
            val start = starts.lastOrNull { !it.isAfter(date) } ?: return@forEach
            val offset = ChronoUnit.DAYS.between(start, date).toInt()
            if (offset >= summary.averageLengthDays) return@forEach
            // Day-weighted, like [moodSummary]: five taps on one hard day are one day.
            val mean = rows.mapNotNull { it.moodScore }.average()
            if (offset < follicularDays) follicular += mean else lutealScores += mean
        }

    if (follicular.size < CYCLE_PATTERN_MIN_DAYS || lutealScores.size < CYCLE_PATTERN_MIN_DAYS) return null
    val difference = follicular.average() - lutealScores.average()
    if (kotlin.math.abs(difference) < MOOD_PATTERN_MIN_DIFFERENCE) return null
    return CycleInsight.MoodPattern(difference, follicular.size + lutealScores.size)
}

/**
 * Sleep on bleeding days against sleep on the rest, in minutes a night.
 *
 * Bleeding days are the ones she wrote down — [recordedBleedingDays] — not a window counted off a
 * prediction: this is a comparison between two sets of real nights, and a night assigned to the
 * wrong set by arithmetic would be the whole of the finding.
 */
internal fun sleepPattern(entries: List<JournalEntry>, today: LocalDate): CycleInsight.SleepPattern? {
    val bleeding = recordedBleedingDays(entries)
    if (bleeding.isEmpty()) return null

    val onPeriod = mutableListOf<Int>()
    val otherwise = mutableListOf<Int>()
    entries.forEach { entry ->
        val start = entry.sleepStartAt ?: return@forEach
        val end = entry.sleepEndAt ?: return@forEach
        val minutes = ((end - start) / 60_000L).toInt()
        if (minutes !in PLAUSIBLE_SLEEP_MINUTES) return@forEach
        val date = entry.day.toLocalDateOrNull() ?: return@forEach
        if (date.isAfter(today)) return@forEach
        if (date in bleeding) onPeriod += minutes else otherwise += minutes
    }

    if (onPeriod.size < CYCLE_PATTERN_MIN_DAYS || otherwise.size < CYCLE_PATTERN_MIN_DAYS) return null
    val period = onPeriod.average().roundToInt()
    val other = otherwise.average().roundToInt()
    if (kotlin.math.abs(period - other) < SLEEP_PATTERN_MIN_MINUTES) return null
    return CycleInsight.SleepPattern(period, other, onPeriod.size + otherwise.size)
}

/** A night this app is willing to average: anything outside is a sensor fault or a forgotten stop. */
private val PLAUSIBLE_SLEEP_MINUTES = 120..840

/**
 * The end mark a new start dated [day] should take the place of, or null for an ordinary new start.
 *
 * Its own function so the rule can be held to without a database: [StepsDb.startOrResumeCycle] is a
 * transaction, and a rule that only a device can check is a rule no test can hold to.
 */
fun resumedPeriodEnd(entries: List<JournalEntry>, day: LocalDate): LocalDate? =
    cyclePeriods(entries).lastOrNull()?.end
        ?.takeIf { ChronoUnit.DAYS.between(it, day) in 0..PERIOD_RESUME_DAYS }
