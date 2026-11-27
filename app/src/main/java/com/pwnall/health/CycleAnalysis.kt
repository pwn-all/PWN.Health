package com.pwnall.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/*
 * What the cycle screen says about today, and on what evidence.
 *
 * Kept out of the UI for the reason [cycleInsights] keeps its own args out of it: every answer here
 * is a claim about a woman's body, and a claim assembled in a composable is a claim no test can hold
 * to. Three questions, each its own function:
 *
 * - [CycleFacts.phase]: where today sits, which decides what the whole page is about.
 * - [normVerdict]: whether what she recorded today is what she usually records at this point. It is
 *   a statement about *her record* and never a verdict on her health — see the note on [NormVerdict].
 * - [fertility]: the word under the headline, from the calendar and from what she has noticed.
 *
 * Plus [cycleFlags], which is the one thing on this screen that does not wait to be asked: it needs
 * no tap today and no history at all, so it is the only part that can speak on the days everything
 * else has to stay silent about.
 */

/**
 * Where today sits in the cycle.
 *
 * Bleeding wins any overlap. A short cycle can put an estimated fertile window on top of a period —
 * [fertileWindow] counts back fourteen days from a prediction, and on a 19-day average that lands
 * inside the bleeding — and on such a day the answer she can check against her own body is the one
 * the screen has to lead with.
 */
sealed interface CyclePhase {
    /** A period is running, or today falls inside one she has closed. */
    data class Menstruation(val start: LocalDate, val dayOfPeriod: Int, val end: LocalDate?) : CyclePhase

    /** The estimated fertile days — only ever where [fertileWindow] agreed to answer at all. */
    data class Fertile(val window: ClosedRange<LocalDate>, val ovulation: LocalDate) : CyclePhase

    /** A day of a cycle the record can place, and neither of the above. */
    data class Ordinary(val dayOfCycle: Int) : CyclePhase

    /** No first start on record, or today is so far past the last one that it places nothing. */
    data object Unknown : CyclePhase
}

/**
 * Which of the four today is.
 *
 * The bleeding test is the period on file rather than [recordedBleedingDays], and the difference
 * matters: a single mid-cycle spotting tap is in that set, and reading it as a period would put day
 * one of a period on day fifteen of the cycle and compare the rest of the screen against it. A
 * period is a [KIND_CYCLE] start, and nothing else is.
 *
 * An unclosed start counts while it is still inside [PLAUSIBLE_PERIOD_DAYS]. The home screen's own
 * `CLOSE_PERIOD` nudge is what handles the start somebody forgot, and withholding the phase as well
 * would leave the screen blank on exactly the days it is most wanted.
 */
// Each answer is a guard that has already decided: detekt does not count `?: return` as one.
fun CycleFacts.phase(): CyclePhase {
    periodOn(today)?.let { (start, end) ->
        return CyclePhase.Menstruation(
            start = start,
            dayOfPeriod = ChronoUnit.DAYS.between(start, today).toInt() + 1,
            end = end,
        )
    }
    val window = freshFertileWindow(summary, today)
    val ovulation = freshOvulation(summary, today)
    if (window != null && ovulation != null && today in window) return CyclePhase.Fertile(window, ovulation)
    return summary.dayOfCycle?.let(CyclePhase::Ordinary) ?: CyclePhase.Unknown
}

/**
 * The ordered answers a day can carry, as ladders one day can be compared with another on.
 *
 * [CycleLog.SPOTTING] is deliberately not a rung of [FLOW]. It is a kind of bleeding rather than an
 * amount of it, and putting it at the bottom of the ladder would make a spotting day and a light day
 * one step apart on a scale whose steps are supposed to mean "more".
 *
 * [zeroed] is whether the bottom rung means "none of it". Pain and desire have such a rung and flow
 * and mucus do not, and it decides only how a rung is turned into a height: a ladder whose bottom is
 * nothing starts the plot at the floor, a ladder whose bottom is already an amount does not.
 *
 * [absentIsNone] is the other half of that question, and only flow answers yes to it. A day between
 * one period and the next with no flow written on it is a day she was not bleeding — the marks say
 * so — so it is a measured nought and the curve can run through it. A day with no mucus written on
 * it says only that she did not look; that one stays unknown, and is filled in from her own past
 * cycles and drawn as the estimate it is. So does a day of a period with no flow on it yet, which
 * says only that she has not answered ([levelOn]).
 */
enum class CycleScale(val logs: List<CycleLog>, val zeroed: Boolean, val absentIsNone: Boolean = false) {
    FLOW(listOf(CycleLog.FLOW_LIGHT, CycleLog.FLOW_MEDIUM, CycleLog.FLOW_HEAVY), false, absentIsNone = true),
    PAIN(
        listOf(CycleLog.PAIN_NONE, CycleLog.PAIN_MILD, CycleLog.PAIN_MODERATE, CycleLog.PAIN_SEVERE),
        true,
    ),
    MUCUS(
        listOf(
            CycleLog.MUCUS_DRY,
            CycleLog.MUCUS_STICKY,
            CycleLog.MUCUS_CREAMY,
            CycleLog.MUCUS_WATERY,
            CycleLog.MUCUS_EGG_WHITE,
        ),
        false,
    ),
    DESIRE(
        listOf(
            CycleLog.DESIRE_NONE,
            CycleLog.DESIRE_LOW,
            CycleLog.DESIRE_MODERATE,
            CycleLog.DESIRE_HIGH,
        ),
        true,
    ),

    /**
     * The ovulation test, which is a ladder only in the sense that its three results run one way:
     * a negative strip, a high reading and a surge. It is asked about on the screen and never
     * charted or compared — a test result is not an amount of anything.
     */
    OVULATION(
        listOf(
            CycleLog.OVULATION_NEGATIVE,
            CycleLog.OVULATION_HIGH,
            CycleLog.OVULATION_POSITIVE,
        ),
        false,
    ),
    ;

    /** Where this log stands on the ladder, one-based, or null for a log that is not on it. */
    fun rungOf(log: CycleLog?): Int? = logs.indexOf(log).takeIf { it >= 0 }?.plus(1)

    /** The rung as a height between 0 and 1, which is the only form the chart draws. */
    fun heightOf(rung: Int): Double = if (zeroed) {
        (rung - 1).toDouble() / (logs.size - 1)
    } else {
        rung.toDouble() / logs.size
    }

    /**
     * [heightOf] read backwards: the rung nearest a height, or null for a height that is no rung of
     * this ladder — the nought of flow and mucus, which have no "none" to name it by. What is said
     * aloud about a curve is read through this, so the word spoken is the rung that was drawn.
     */
    fun rungAt(height: Double): Int? {
        val rung = if (zeroed) (height * (logs.size - 1)).roundToInt() + 1 else (height * logs.size).roundToInt()
        return rung.takeIf { it in 1..logs.size }
    }
}

/**
 * The scale the screen leads with in each phase: what she is bleeding during a period, and what she
 * is noticing the rest of the time.
 */
fun CyclePhase.scale(): CycleScale = if (this is CyclePhase.Menstruation) CycleScale.FLOW else CycleScale.MUCUS

/** And the one asked second, once the first has been answered. */
fun CyclePhase.secondScale(): CycleScale = if (this is CyclePhase.Menstruation) CycleScale.PAIN else CycleScale.DESIRE

/**
 * Everything the per-day arithmetic reads, read once.
 *
 * The year window asks where each of three hundred and sixty-four days sits and what twelve previous
 * cycles held at the same point. Every one of those questions walks the journal, so without this the
 * chart would re-parse the whole record a thousand times to draw one line. Nothing here is a new fact —
 * it is the same four calls the rest of the file makes, hoisted to where they are made once.
 */
class CycleFacts(
    internal val entries: List<JournalEntry>,
    internal val summary: CycleSummary,
    internal val today: LocalDate,
) {
    internal val periods: List<CyclePeriod> = cyclePeriods(entries)

    internal val views: List<CycleView> = cycleViews(entries, summary, today)

    /** The last start on record, closed or not — the one the numbered fallback counts from. */
    internal val lastStart: LocalDate? = summary.lastStart

    /** The start of a period nobody has closed; [periodOn] decides how long it still counts as running. */
    internal val openStart: LocalDate? = lastStart
        ?.takeIf { start -> periods.none { it.start == start } }

    /**
     * The finished cycles anything is compared against: the last twelve, and none older than the
     * twelve months FIGO 2018 System 1 states its reference ranges over. Newest first.
     */
    internal val comparable: List<CycleView> = views
        .dropLast(1)
        .filter { !it.start.isBefore(today.minusMonths(CYCLE_REFERENCE_MONTHS)) }
        .takeLast(CYCLE_REFERENCE_INTERVALS)
        .reversed()

    /** Every cycle log of a day, oldest first, so the last one written is the one that counts. */
    internal val logsByDay: Map<String, List<CycleLog>> = entries
        .filter { it.kind == KIND_CYCLE_LOG }
        .sortedBy { it.occurredAt }
        .groupBy({ it.day }, { it.cycleLog() })
        .mapValues { (_, logs) -> logs.filterNotNull() }
}

/** The twelve months [CYCLE_REFERENCE_INTERVALS] is stated over, so a gap in logging cannot reach
 *  back past them and count a cycle from two years ago as one of the last twelve. */
internal const val CYCLE_REFERENCE_MONTHS = 12L

/**
 * What she recorded on [date] on one ladder, or null where she recorded nothing on it.
 *
 * The latest row wins. One answer per section per day is the write path's rule, but a restored
 * backup and a Health Connect import can both land two, and a silent disagreement between the chart
 * and the chip about which of them counts is worse than either answer.
 */
internal fun CycleFacts.rungOn(date: LocalDate, scale: CycleScale): Int? =
    logsByDay[date.toString()]?.mapNotNull(scale::rungOf)?.lastOrNull()

/**
 * How high [date] stands on one ladder, between 0 and 1, or null where the record cannot say.
 *
 * This is [rungOn] with the one thing a rung cannot express: nought. Flow is nought on every day of
 * a cycle the record brackets and she did not mark, which is what lets the curve run from nought up
 * through the period and back down instead of stopping dead at each end of it.
 *
 * But not on a day of the period itself. There an unmarked day is a question not yet answered, and
 * as a nought it would stand on the plot as a solid dot on the floor — her record of no bleeding on a
 * day of her period — until she answered it.
 */
internal fun CycleFacts.levelOn(date: LocalDate, scale: CycleScale): Double? =
    rungOn(date, scale)?.let(scale::heightOf)
        ?: 0.0.takeIf { scale.absentIsNone && periodOn(date) == null && cycleSpanOf(date) != null }

/** The same, in one cycle of the past, where being inside that cycle is already established. */
private fun CycleFacts.levelIn(date: LocalDate, scale: CycleScale): Double? =
    rungOn(date, scale)?.let(scale::heightOf) ?: 0.0.takeIf { scale.absentIsNone && periodOn(date) == null }

/** What her finished cycles stood at, at the same point of the month. Newest first. */
internal fun CycleFacts.pastLevelsAt(anchor: CycleAnchor, scale: CycleScale): List<Double> =
    comparable.mapNotNull { cycle -> anchoredDate(anchor, cycle)?.let { levelIn(it, scale) } }

/**
 * The acts already on one day, oldest first: the row to take one off by, and which act it was.
 *
 * Which act rather than the words on the row. A journal row's title is baked in at the moment it is
 * written, so a row logged on an English phone still reads English on a Russian one; the payload
 * holds the name and the screen looks the word up in whatever language is showing. Null is the solo
 * act, which has no [CycleLog] of its own.
 *
 * Two rows of the same kind on one day are two acts and not a correction, so nothing is folded.
 */
internal fun CycleFacts.intimacyOn(date: LocalDate): List<Pair<Long, CycleLog?>> {
    val day = date.toString()
    return entries
        .filter { it.day == day && it.isSexualAct() }
        .sortedBy { it.occurredAt }
        .map { it.id to it.cycleLog() }
}

/**
 * The spotting row of one day, to take it off by, or null where none was written. The latest wins,
 * as in [rungOn]: the write path keeps one, but an import can land a second.
 */
internal fun CycleFacts.spottingOn(date: LocalDate): Long? {
    val day = date.toString()
    return entries
        .filter { it.day == day && it.cycleLog() == CycleLog.SPOTTING }
        .maxByOrNull { it.occurredAt }
        ?.id
}

/** Every [CycleLog] recorded on one day, whatever ladder it belongs to. */
internal fun CycleFacts.logsOn(date: LocalDate): Set<CycleLog> =
    logsByDay[date.toString()].orEmpty().toSet()

/**
 * Whether the bleeding on [date] is spotting and nothing else: marked, outside any period, with no
 * amount of flow beside it.
 *
 * [recordedBleedingDays] counts such a day, rightly — it is bleeding — but the calendar, the week
 * strip and the plot must not call every day of that set "Period". Bleeding between periods is the
 * thing a clinician asks about apart from them, and Health Connect holds it as its own record, so
 * the three are told here which of the two a day is.
 */
internal fun CycleFacts.isSpottingDay(date: LocalDate): Boolean =
    CycleLog.SPOTTING in logsOn(date) && periodOn(date) == null && rungOn(date, CycleScale.FLOW) == null

/**
 * Where a day sits, said in terms another cycle can be looked up by. This is the whole of how one
 * day is compared with "the same day" of a cycle that has already finished.
 *
 * Three kinds, and the kind is chosen once from today and then applied to every other cycle. Mixing
 * them is the silent failure this type exists to prevent: a running cycle placed by day-of-cycle and
 * a past one placed by its ovulation would be compared at two different points of the body's month,
 * and the comparison would simply never match.
 */
enum class CycleAnchorKind {
    /** Day n of the bleeding, counted from the start. The one placement that needs no estimate. */
    PERIOD_DAY,

    /** Days from the ovulation, negative before it. The luteal half is the half that holds still. */
    FROM_OVULATION,

    /**
     * Day n of the cycle, and only early enough that every cycle on record is still in the same
     * phase there — see [followingBound].
     */
    CYCLE_DAY,
}

data class CycleAnchor(val kind: CycleAnchorKind, val index: Int)

/**
 * The last day of a cycle that can still be placed by its number alone.
 *
 * Day 24 of a 26-day cycle and day 24 of a 40-day cycle are different halves of the month, so the
 * numbered fallback stops where the earliest ovulation her own record supports could be. The split
 * is the one [moodPattern] already uses, so the two halves of the app divide the month identically.
 */
internal fun followingBound(summary: CycleSummary): Int =
    summary.averageLengthDays - LUTEAL_DAYS.toInt()

/**
 * The period [date] falls inside, as its start and the end she gave it, or null for a day outside
 * one. A closed period answers over its own span; a start still waiting for its end answers while
 * the days since it are a length a period can have.
 */
// Each answer is a guard that has already decided: detekt does not count `?: return` as one.
internal fun CycleFacts.periodOn(date: LocalDate): Pair<LocalDate, LocalDate?>? {
    periods.lastOrNull { !date.isBefore(it.start) && !date.isAfter(it.end) }
        ?.let { return it.start to it.end }
    val open = openStart ?: return null
    val day = ChronoUnit.DAYS.between(open, date).toInt() + 1
    return (open to null).takeIf { day in PLAUSIBLE_PERIOD_DAYS }
}

/**
 * What [date] is asked, in the order it is asked: on a day of a period how much and then how much
 * it hurts, on any other day what she is seeing, how much she wants to, and an ovulation test.
 *
 * Decided by the day the answers are about and not by today's phase. Asked today's questions, a past
 * day chosen on the week strip would go wrong both ways: once a period is over its missed days would
 * have no flow and no pain to fill in or to show, and during one a day of mid-cycle could be given
 * only bleeding.
 *
 * [acts] is the age gate's answer ([sexLogOffered]). Desire is a question about her sex life, so it
 * stands behind the same gate as the acts: where the gate is shut it is not asked, and an answer a
 * restored backup brought with it is not shown, because only a question that is asked shows one.
 */
internal fun CycleFacts.questionsOn(date: LocalDate, acts: Boolean): List<CycleScale> = if (periodOn(date) != null) {
    listOf(CycleScale.FLOW, CycleScale.PAIN)
} else {
    // The ovulation test stands on this side only: a strip run during the period reads negative
    // because it is meant to, and asking there teaches the wrong thing.
    listOfNotNull(CycleScale.MUCUS, CycleScale.DESIRE.takeIf { acts }, CycleScale.OVULATION)
}

/**
 * The cycle [date] falls in, as the day it began and the day the next one begins.
 *
 * A recorded cycle where the record reaches, and the average's continuation of it where it does not.
 * Without the second half the comparison line could only ever be drawn over the fortnight since the
 * last start: every earlier day of the window would fail to place itself, and the chart would answer
 * "nothing to compare" over months of entries it actually holds.
 */
// Each answer is a guard that has already decided: detekt does not count `?: return` as one.
@Suppress("ReturnCount")
internal fun CycleFacts.cycleSpanOf(date: LocalDate): ClosedRange<LocalDate>? {
    views.lastOrNull { !date.isBefore(it.start) }?.let { view ->
        val next = view.nextStart
            ?: summary.nextExpected
            ?: view.start.plusDays(summary.averageLengthDays.toLong())
        if (date.isBefore(next)) return view.start..next
    }
    val expected = summary.nextExpected?.takeIf { !date.isBefore(it) } ?: return null
    val length = summary.averageLengthDays.toLong()
    if (length <= 0) return null
    var start = expected
    repeat(CHART_PREDICT_CYCLES) {
        if (date.isBefore(start.plusDays(length))) return start..start.plusDays(length)
        start = start.plusDays(length)
    }
    return null
}

/**
 * Where [date] sits, or null where this record cannot place it at all.
 *
 * [kind] pins the answer to one kind — which is what every lookup after the first does, so that a
 * date is only ever compared with the same kind of point in another cycle.
 */
// Each answer is a guard that has already decided: detekt does not count `?: return` as one.
@Suppress("ReturnCount")
internal fun CycleFacts.anchorOf(date: LocalDate, kind: CycleAnchorKind? = null): CycleAnchor? {
    if (kind == null || kind == CycleAnchorKind.PERIOD_DAY) {
        periodOn(date)?.let { (start, _) ->
            val index = ChronoUnit.DAYS.between(start, date).toInt() + 1
            if (index in PLAUSIBLE_PERIOD_DAYS) return CycleAnchor(CycleAnchorKind.PERIOD_DAY, index)
        }
        predictedPeriodDay(date)?.let { return CycleAnchor(CycleAnchorKind.PERIOD_DAY, it) }
        if (kind == CycleAnchorKind.PERIOD_DAY) return null
    }
    val span = cycleSpanOf(date) ?: return null
    if (kind == null || kind == CycleAnchorKind.FROM_OVULATION) {
        observedFertileWindow(span.start, span.endInclusive)?.endInclusive?.let { ovulation ->
            val index = ChronoUnit.DAYS.between(ovulation, date).toInt()
            if (index in ANCHOR_REACH) return CycleAnchor(CycleAnchorKind.FROM_OVULATION, index)
        }
        if (kind == CycleAnchorKind.FROM_OVULATION) return null
    }
    val dayOfCycle = ChronoUnit.DAYS.between(span.start, date).toInt() + 1
    return CycleAnchor(CycleAnchorKind.CYCLE_DAY, dayOfCycle)
        .takeIf { dayOfCycle in 1..followingBound(summary) }
}

/**
 * Day n of a period the average predicts, for a day that has not happened yet.
 *
 * Without this the comparison line would stop at today: a day inside a forecast period matches no
 * recorded period, so it would place nowhere and the forward half of the plot would carry the shaded
 * band and no curve at all. The forward half is where the line is doing its only real work — saying
 * how the next period is likely to go — so it is drawn there, in the dashed stroke everything
 * estimated wears. Only forward of today, and only where a measured period length exists to give it:
 * guessing a length as well as a date would be a forecast drawn out of a forecast.
 */
internal fun CycleFacts.predictedPeriodDay(date: LocalDate): Int? {
    if (!date.isAfter(today)) return null
    val length = summary.periodLengthDays ?: return null
    val span = cycleSpanOf(date) ?: return null
    val index = ChronoUnit.DAYS.between(span.start, date).toInt() + 1
    return index.takeIf { it in 1..length }
}

/**
 * How far from an estimated ovulation a day may still be placed by it: the luteal phase on one side
 * and as much again on the other, which between them are the cycle either side of that point.
 */
private val ANCHOR_REACH = -(LUTEAL_DAYS.toInt())..(LUTEAL_DAYS.toInt() - 1)

/**
 * The day of [cycle] carrying the same anchor, or null where that cycle has no such day.
 *
 * Bounded by the cycle itself in every case. An unbounded answer would reach into the cycle before
 * or after and read a day belonging to neither — and since a cycle only knows its own span, the
 * bound is the only thing standing between "the same point of the month" and "a fortnight later".
 */
internal fun anchoredDate(anchor: CycleAnchor, cycle: CycleView): LocalDate? {
    val next = cycle.nextStart ?: cycle.start.plusDays(cycle.lengthDays.toLong())
    val date = when (anchor.kind) {
        CycleAnchorKind.PERIOD_DAY, CycleAnchorKind.CYCLE_DAY ->
            cycle.start.plusDays(anchor.index - 1L)

        CycleAnchorKind.FROM_OVULATION ->
            cycle.nextStart
                ?.let { observedFertileWindow(cycle.start, it)?.endInclusive }
                ?.plusDays(anchor.index.toLong())
                ?: return null
    }
    return date.takeIf { !it.isBefore(cycle.start) && it.isBefore(next) }
}

/** What she recorded at the same anchor in the cycles that have already finished, newest first. */
internal fun CycleFacts.pastRungsAt(anchor: CycleAnchor, scale: CycleScale): List<Int> =
    comparable.mapNotNull { cycle -> anchoredDate(anchor, cycle)?.let { rungOn(it, scale) } }

/**
 * Whether today is what she usually records at this point of her cycle.
 *
 * This is a statement about her own record and never a verdict on her health. "Так же, как обычно"
 * means she has written the same thing down at the same point before — not that it is fine, and the
 * strings must never say so. What a published threshold has to say is a separate answer that does
 * not wait to be asked: see [cycleFlags], which is drawn beside this one rather than inside it.
 */
sealed interface NormVerdict {
    val scale: CycleScale

    /** The rungs she has actually recorded at this point, or null where nothing was compared. */
    val band: IntRange?

    /** She has recorded this at this point before. [matching] of [compared] previous cycles. */
    data class Same(
        override val scale: CycleScale,
        val matching: Int,
        val compared: Int,
        override val band: IntRange,
    ) : NormVerdict

    /** Outside everything she has recorded at this point. [higher] is the direction. */
    data class Different(
        override val scale: CycleScale,
        val compared: Int,
        override val band: IntRange,
        val higher: Boolean,
    ) : NormVerdict

    /**
     * There is a value today and no usual to hold it against — either too few cycles carry one at
     * this point, or her record at this point has been the whole ladder, in which case [band] is
     * that span and the screen prints it instead of a verdict it cannot honestly give.
     */
    data class TooLittle(
        override val scale: CycleScale,
        val compared: Int,
        val needed: Int,
        override val band: IntRange? = null,
    ) : NormVerdict
}

/**
 * Three previous cycles carrying a value at the same point before anything is called usual or not.
 *
 * Two would be the smallest number that can show a habit at all, and it is also small enough that
 * roughly one day in three falls outside a two-point band by chance alone — so a screen built on two
 * would be calling ordinary variation unusual a third of the time. Three is the compromise: low
 * enough to start answering in a woman's fourth month of use, high enough that the sentence is
 * describing a pattern rather than a coin toss. The count is printed beside every answer, so the
 * reader can weigh it herself rather than take the word for it.
 */
internal const val NORM_MIN_OBSERVATIONS = 3

/** And this many exact repeats before the strongest form of the sentence is used. */
private const val NORM_MIN_MATCHES = 2

/**
 * The chip's answer, or null when nothing was recorded today on the phase's own ladder.
 *
 * Null is what makes the chip stop being a button: a control that invites a tap and then has nothing
 * to say is worse than one that is visibly not a control.
 */
// Each answer is a guard that has already decided: detekt does not count `?: return` as one.
fun CycleFacts.normVerdict(phase: CyclePhase): NormVerdict? {
    val scale = phase.scale()
    val rung = rungOn(today, scale) ?: return null
    val anchor = anchorOf(today) ?: return NormVerdict.TooLittle(scale, 0, NORM_MIN_OBSERVATIONS)
    return normVerdictOf(scale, rung, pastRungsAt(anchor, scale))
}

/**
 * The verdict itself, with no dates and no journal in it, so every branch below is four lines of
 * test rather than a fixture of a year of entries.
 */
internal fun normVerdictOf(scale: CycleScale, todayRung: Int, past: List<Int>): NormVerdict {
    if (past.size < NORM_MIN_OBSERVATIONS) {
        return NormVerdict.TooLittle(scale, past.size, NORM_MIN_OBSERVATIONS)
    }
    val band = past.min()..past.max()
    // A record that has been every rung of the ladder at this point has no usual. Reporting it as
    // "the same as always" would make the word mean "anything", which is how a sentence stops
    // carrying information while still sounding like it does.
    if (band.last - band.first >= scale.logs.size - 1) {
        return NormVerdict.TooLittle(scale, past.size, NORM_MIN_OBSERVATIONS, band)
    }
    val matching = past.count { it == todayRung }
    return when {
        matching >= NORM_MIN_MATCHES -> NormVerdict.Same(scale, matching, past.size, band)
        todayRung in band -> NormVerdict.Same(scale, matching, past.size, band)
        else -> NormVerdict.Different(scale, past.size, band, todayRung > band.last)
    }
}

/**
 * The things worth saying whether or not she taps anything today.
 *
 * Every other answer on this screen waits for a record: the chip needs a value today, the chart
 * needs a history, the fertility word needs one or the other. These need neither, which is why they
 * are the only part that can speak on the days a silent screen would otherwise read as a clean bill.
 *
 * Neither names a condition and neither is a diagnosis; both are the figure and the published line
 * it has passed. The referral belongs to [cycleAdvice], which has the age and the pattern over
 * several cycles that a single day does not.
 */
sealed interface CycleFlag {
    /** Bleeding past the day count a body publishes as prolonged. */
    data class Prolonged(val days: Int, val threshold: Int) : CycleFlag

    /** The predicted start has come and gone. */
    data class Late(val expected: LocalDate, val days: Int) : CycleFlag
}

/**
 * FIGO 2018 puts prolonged menstrual bleeding at more than eight days; ACOG Committee Opinion 651
 * puts the adolescent figure at more than seven. Age arrives as null when the profile has no birth
 * date, and a null age gets the adult figure rather than a guess.
 */
internal fun prolongedPeriodThreshold(ageYears: Int?): Int =
    if (ageYears != null && ageYears in 1 until ADOLESCENT_PERIOD_AGE) PERIOD_LONG_ADOLESCENT_DAYS else PERIOD_LONG_DAYS

private const val ADOLESCENT_PERIOD_AGE = 20
private const val PERIOD_LONG_ADOLESCENT_DAYS = 7

fun cycleFlags(
    summary: CycleSummary,
    today: LocalDate,
    phase: CyclePhase,
    ageYears: Int?,
): List<CycleFlag> = buildList {
    if (phase is CyclePhase.Menstruation) {
        val threshold = prolongedPeriodThreshold(ageYears)
        // Capped at a length a period can have. A start forgotten for a year would otherwise print
        // "400 days, which is longer than usual" — a true sentence about a record nobody kept, and
        // the home screen's close-period nudge is the right answer to that one.
        val days = phase.dayOfPeriod.coerceAtMost(PLAUSIBLE_PERIOD_DAYS.last)
        if (phase.dayOfPeriod > threshold) add(CycleFlag.Prolonged(days, threshold))
    }
    summary.nextExpected?.let { expected ->
        val late = ChronoUnit.DAYS.between(expected, today).toInt()
        // And only while the record still counts the days of this cycle. Past twice her average
        // [CycleSummary.dayOfCycle] gives up, the page says nothing has been logged since the last
        // start, and "your period is 231 days late" beside that is the same true sentence about a
        // record nobody kept that the cap above is there for.
        if (phase !is CyclePhase.Menstruation && late >= CYCLE_LATE_DAYS && summary.dayOfCycle != null) {
            add(CycleFlag.Late(expected, late))
        }
    }
}

/** How fertile today is, as a word rather than as a probability nobody could stand behind. */
enum class FertilityLevel { LOW, RISING, HIGH, PEAK }

/**
 * The word, and the two independent readings behind it, so the screen can say which one spoke.
 *
 * [fromDates] is the calendar; [fromSigns] is what she has actually noticed. They are kept apart
 * because they disagree often and for a reason worth showing rather than averaging away.
 */
data class Fertility(
    val level: FertilityLevel,
    val fromDates: FertilityLevel?,
    val fromSigns: FertilityLevel?,
    val ovulation: LocalDate?,
    val window: ClosedRange<LocalDate>?,
) {
    /**
     * Whether the word printed is the calendar's. Where it is, the page says under it that it is an
     * estimate from dates and not a method of contraception — "Low" above all, which is the one
     * reading here that could be taken for a clearance. A sign that outranks the dates is something
     * she saw, and is not captioned as arithmetic.
     */
    val estimated: Boolean get() = level == fromDates
}

/**
 * The fertility word, or null where this record supports neither reading.
 *
 * Null rather than [FertilityLevel.LOW], and that is the single most important line in this file.
 * LOW is a measured answer meaning "outside the estimated window"; a woman with no history shown
 * "your fertility is low" has been handed a reading the app does not have.
 *
 * The two readings are combined by taking the higher. Each is independently sufficient in its own
 * literature, so the lower would let a day she did not log pull a real calendar window down, and a
 * record too short for a calendar estimate would flatten a clear egg-white day to nothing. The
 * asymmetry is chosen on which error has a consequence: the page says under the word that it is an
 * estimate from dates and not a method of contraception ([Fertility.estimated]), and understating
 * fertility is the half of the mistake somebody could act on.
 */
fun CycleFacts.fertility(): Fertility? {
    val window = freshFertileWindow(summary, today)
    val fromDates = fertilityFromDates(window, today)
    val fromSigns = fertilityFromSigns(logsOn(today), logsOn(today.minusDays(1)))
    val level = listOfNotNull(fromDates, fromSigns).maxByOrNull { it.ordinal } ?: return null
    return Fertility(level, fromDates, fromSigns, freshOvulation(summary, today), window)
}

/**
 * Whether the predicted start is still something to count from.
 *
 * The prediction itself never moves: it is the last start plus her average, whatever the date is.
 * So on day seventy of a record whose average is twenty-eight it names a day six weeks gone. Past
 * the point where the app itself calls the period late, or where [CycleSummary.dayOfCycle] has
 * stopped counting, the calendar has been overtaken by events and has nothing left to say about
 * today or about the weeks ahead.
 */
internal fun predictionStands(summary: CycleSummary, today: LocalDate): Boolean {
    val expected = summary.nextExpected
    return summary.dayOfCycle != null && expected != null &&
        ChronoUnit.DAYS.between(expected, today) < CYCLE_LATE_DAYS
}

/**
 * The estimated window, but only while the prediction it was counted back from still stands.
 *
 * [fertileWindow] answers from the record's regularity alone and never expires — see
 * [predictionStands].
 */
internal fun freshFertileWindow(summary: CycleSummary, today: LocalDate): ClosedRange<LocalDate>? =
    fertileWindow(summary)?.takeIf { predictionStands(summary, today) }

/** And the ovulation estimated inside it, under the same expiry. */
internal fun freshOvulation(summary: CycleSummary, today: LocalDate): LocalDate? =
    estimatedOvulation(summary)?.takeIf { predictionStands(summary, today) }

/**
 * The ovulation estimate the line under the week leads with: only while that day is still to come.
 *
 * A woman on day two is asking when the fertile days are coming, and that is what the line answers.
 * Once the day has gone by the answer is in the calendar, and the line is given to what her record
 * measures instead ([patternInsight]) — printed on every day of a regular cycle, it would leave those
 * readings no day to be printed on.
 */
internal fun upcomingOvulation(summary: CycleSummary, today: LocalDate): LocalDate? =
    freshOvulation(summary, today)?.takeIf { !it.isBefore(today) }

/**
 * The shape of the fertile days around an ovulation, between 0 and 1, for drawing and for nothing
 * else.
 *
 * The *extent* is Wilcox 1995's six days ending on ovulation, plus the day or two after it that the
 * egg can still be met on. The *shape* inside that extent is a drawing and not a probability: this
 * app places ovulation by counting back from a predicted start, and the same authors' follow-up
 * (BMJ 2000) measured that the calendar puts the window in the right place about three times in ten.
 * A curve with numbers up the side would be claiming a precision the anchor does not have, so the
 * plot carries no scale, the whole curve is drawn dashed as the estimate it is, and the word under
 * the headline still comes from [fertilityFromDates], which says only "inside" or "outside".
 *
 * Null where no ovulation can be placed; 0.0 on a day that can be placed and is simply outside.
 */
internal fun fertilityShape(ovulation: LocalDate?, date: LocalDate): Double? {
    if (ovulation == null) return null
    val days = ChronoUnit.DAYS.between(ovulation, date)
    return when {
        days < -FERTILE_SHAPE_LEAD || days > FERTILE_SHAPE_TAIL -> 0.0
        days <= 0L -> smoothstep((days + FERTILE_SHAPE_LEAD).toDouble() / FERTILE_SHAPE_LEAD)
        else -> smoothstep(1.0 - days.toDouble() / FERTILE_SHAPE_TAIL)
    }
}

/** One day past the six, for the stretch the egg itself can still be met on. */
private const val FERTILE_SHAPE_LEAD = 6L
private const val FERTILE_SHAPE_TAIL = 2L

/** A rise with no corners at either end of it, so the hump reads as a hump and not as a tent. */
private fun smoothstep(t: Double): Double {
    val x = t.coerceIn(0.0, 1.0)
    return x * x * (3 - 2 * x)
}

/**
 * The calendar's reading: inside the estimated window or outside it, and nothing finer.
 *
 * Wilcox, Weinberg & Baird (NEJM 1995;333:1517-21) found conception only within the six days ending
 * on ovulation, and those six days, laid on her shortest cycle and on her longest, are the span
 * [fertileWindow] draws. The same authors' follow-up (Wilcox, Dunson & Baird, BMJ
 * 2000;321:1259-62) is why this stops there: across 696 cycles only about three women in ten had
 * their fertile window entirely inside cycle days 10–17, and the window's timing is "highly
 * unpredictable, even if their cycles are usually regular". A calendar counted back from a
 * predicted start cannot name the peak day of a window it can barely place, so no PEAK is produced
 * here. PEAK is reserved for [fertilityFromSigns], where something was actually observed.
 */
internal fun fertilityFromDates(window: ClosedRange<LocalDate>?, date: LocalDate): FertilityLevel? {
    if (window == null) return null
    return if (date in window) FertilityLevel.HIGH else FertilityLevel.LOW
}

/**
 * What she has noticed, which is the reading that can raise the word and never lower it.
 *
 * Dry and sticky mucus return null rather than LOW: one tap on a journal with no cycle history in it
 * would otherwise produce a fertility reading out of nothing at all. A ladder that can only raise is
 * also what lets the two readings be combined by taking the higher without a single sign overruling
 * a calendar it knows nothing about.
 *
 * The mucus ordering is the ovulation method's own and is the order [CycleSection.MUCUS] already
 * lists: Bigelow et al. (Hum Reprod 2004;19:889-92) rank the four mucus types against day-specific
 * conception probability, clear and stretchy highest. An LH test reads positive roughly a day before
 * ovulation, so a surge today or yesterday is the one observation that names the peak; a high
 * reading on a dual-hormone monitor is rising oestrogen and is not a surge. A negative test does not
 * confirm an imminent surge, so it caps the sign reading below PEAK — but never over a surge already
 * recorded, since a test run twice in a day will read both ways.
 */
// Each answer is a guard that has already decided: detekt does not count `?: return` as one.
internal fun fertilityFromSigns(today: Set<CycleLog>, yesterday: Set<CycleLog>): FertilityLevel? {
    val surge = CycleLog.OVULATION_POSITIVE in today || CycleLog.OVULATION_POSITIVE in yesterday
    if (surge) return FertilityLevel.PEAK
    val fromMucus = when {
        CycleLog.MUCUS_EGG_WHITE in today -> FertilityLevel.PEAK
        CycleLog.MUCUS_WATERY in today -> FertilityLevel.HIGH
        CycleLog.MUCUS_CREAMY in today -> FertilityLevel.RISING
        else -> null
    }
    val fromTest = if (CycleLog.OVULATION_HIGH in today) FertilityLevel.HIGH else null
    val level = listOfNotNull(fromMucus, fromTest).maxByOrNull { it.ordinal } ?: return null
    return if (CycleLog.OVULATION_NEGATIVE in today && level == FertilityLevel.PEAK) {
        FertilityLevel.HIGH
    } else {
        level
    }
}
