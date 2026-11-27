package com.pwnall.health

import java.time.LocalDate

// The named hypotheses, and the sentences they are allowed to produce.
//
// Fixed in code rather than swept for. With forty-odd loggable quantities there are hundreds of
// pairs, and something always stands out: Peris et al. 2017 reported "an average of four associated
// factors per patient" from thirty-three uncorrected univariate tests, where chance alone predicts
// about 1.65. A short list somebody can read is the only version of this that is honest.
//
// None of it is ever pushed. [AdviceTopic.INSIGHTS] is in-app only, so a finding waits to be
// opened; an alert tied to a health value is the thing a general-wellness product may not send.

/** The window every hypothesis is measured over: four months, which is what makes 90 days reachable. */
const val INSIGHT_WINDOW_DAYS = 120

/** How much of the candidate list may be false discoveries — see [benjaminiHochberg]. */
private const val INSIGHT_FDR = 0.10

/**
 * The comparisons with mood that are made outside [insightReport]'s list and belong to it all the
 * same: the weather card's, air pressure against mood ([pressureMoodReading]).
 *
 * That one is measured on the mood page, from the barometer's rows, and neither place has the
 * other's p-values at hand. Left outside the correction it would be a sixth comparison judged alone
 * at one in twenty, while the screen said five had been made. So it is counted, and each side is held
 * to the line the correction draws when the other has nothing to show: the list is corrected with a
 * place kept for it at 1, and it answers to [standsAmongAllComparisons].
 */
private const val COMPARISONS_ELSEWHERE = 1

/**
 * Whether a comparison measured apart from the list stands under the list's correction, by its
 * [chance] alone.
 *
 * Benjamini-Hochberg keeps the smallest of m p-values when it is no more than q/m, and a value that
 * small is kept wherever it ranks; so this is the one line that needs none of the other values to
 * draw. It is the procedure's strictest, and exactly its answer whenever nothing else in the list
 * is a finding, which is the ordinary case. m is the whole list and the comparisons made elsewhere,
 * not the share of it an age gate leaves a profile: the stricter count, for everybody.
 */
internal fun standsAmongAllComparisons(chance: Double): Boolean =
    chance <= INSIGHT_FDR / (InsightHypothesis.entries.size + COMPARISONS_ELSEWHERE)

/**
 * One thing worth asking about, with the words it is answered in.
 *
 * [directionRes] is the sentence that may only be added when the forward lag stands and the
 * reverse one does not; where it is null the association is reported symmetrically whatever the
 * lags say, because for that pair the literature does not support a direction either.
 */
private enum class InsightHypothesis(
    val key: String,
    val titleRes: Int,
    val bodyRes: Int,
    val sourceRes: Int,
    val lag: InsightLag,
    val directionRes: Int?,
) {
    /**
     * Steps against the same day's mood.
     *
     * Contemporaneous rather than lagged because that is the shape that actually turns up — 32.1%
     * of people against 17.2% (van Zwieten 2026) — and reported without a direction because at a
     * daily timescale the paths run both ways: Difrancesco 2022 (NESDA, n=359) measured activity to
     * later positive affect at b=0.109 and the reverse at b=0.066, which is an asymmetry but not a
     * one-way street. The card says so in as many words.
     */
    STEPS_MOOD(
        key = "steps-mood",
        titleRes = R.string.insight_steps_title,
        bodyRes = R.string.insight_steps_body,
        sourceRes = R.string.insight_source_steps,
        lag = InsightLag.SAME_DAY,
        directionRes = null,
    ),

    /**
     * Drinking against the next day's mood.
     *
     * The one lagged finding here, and the direction is defensible: Gawn 2025 found medium- and
     * high-risk drinking — not low-risk — predicted lower next-day positive mood, partly through
     * sleep. The threshold matters and the copy carries it, because at low-risk levels the evidence
     * finds nothing and a card implying otherwise would be inventing a problem.
     */
    ALCOHOL_MOOD(
        key = "alcohol-mood",
        titleRes = R.string.insight_alcohol_title,
        bodyRes = R.string.insight_alcohol_body,
        sourceRes = R.string.insight_source_alcohol,
        lag = InsightLag.EXPOSURE_YESTERDAY,
        directionRes = R.string.insight_direction_followed,
    ),

    /**
     * Sleep against the next day's mood — the most replicated within-person link there is.
     *
     * Triantafillou 2019 (JMIR Mental Health 6(3):e12613, n=206, six weeks) measured sleep to
     * next-day mood at 0.344 against mood to next-night sleep at 0.132. But that 2.6-fold asymmetry
     * exists only in the z-scored models: on raw scores the same coefficients are 0.270 and 0.247,
     * a ratio of 1.09. This engine residualises each series, which is the equivalent, so the
     * asymmetry is the one that applies — and the copy claims no more than the order.
     *
     * The lag is [InsightLag.SAME_DAY] because of where a night is filed. Every writer of a sleep
     * row — the detector, Health Connect, a file import, the editor — puts it under the day the
     * sleeper woke on, and [sleepMinuteSeries] keys the series by that day, so the night before a
     * day's mood is that day's own row. Read a day back, the mood would be set against the night
     * before last, while the reverse control — the mood against the row a day on, which is the night
     * that followed it — would line up with exactly what it names, so the two directions would not be
     * compared on equal terms.
     *
     * Most people will never reach the gate for it: automatic sleep detection is off by default, so
     * for anybody who has not turned it on the series is far too sparse. That is the right outcome,
     * not a gap to paper over.
     */
    SLEEP_MOOD(
        key = "sleep-mood",
        titleRes = R.string.insight_sleep_title,
        bodyRes = R.string.insight_sleep_body,
        sourceRes = R.string.insight_source_sleep,
        lag = InsightLag.SAME_DAY,
        directionRes = R.string.insight_direction_followed_sleep,
    ),

    /**
     * Intimacy against the next day's mood, which is the only direction the evidence permits.
     *
     * Kashdan et al. 2018 (Emotion 18(4):563-576, n=152, 21 days, time-lagged) found sexual
     * activity predicted greater next-day positive affect, lower negative affect and more meaning —
     * and, in the paper's own words, that when the reverse direction was tested wellbeing did not
     * predict next-day sexual activity.
     *
     * Partnered only. Solo entries live under their own kind and are never pooled in: the
     * literature on them is cross-sectional and mixed, and averaging two different questions
     * together is exactly the mistake keeping the two kinds apart avoids.
     *
     * Two confounders are named on the card because both are visible in this app's own data: the
     * menstrual cycle, which moves desire and mood together and which the residualiser adjusts for
     * where there are cycles to adjust by, and an SSRI on the medication list, where a flat or
     * negative reading is expected and is not a finding about the person.
     */
    INTIMACY_MOOD(
        key = "intimacy-mood",
        titleRes = R.string.insight_intimacy_title,
        bodyRes = R.string.insight_intimacy_body,
        sourceRes = R.string.insight_source_intimacy,
        lag = InsightLag.EXPOSURE_YESTERDAY,
        directionRes = R.string.insight_direction_followed_intimacy,
    ),

    /**
     * Sugar against the same day's mood — and the answer is almost always "nothing".
     *
     * This one is here to be reported honestly rather than because a link is expected. Mantantzis
     * et al. 2019 (Neurosci Biobehav Rev 101:45-67, 176 effect sizes, 31 studies, 1,259
     * participants) found carbohydrate produced no mood benefit at any time point, with more
     * fatigue and less alertness within the first hour. Franja et al. 2021 (Br J Health Psychol
     * 26(3):825-838, n=141) found affect declining in the hours *before* an unhealthy snack.
     *
     * So the card can only ever report what a day looked like, never a lift, and the source line
     * says that controlled trials have not found one either. Its window is also the thinnest here,
     * since sugar is an optional column most hand-typed meals do not carry.
     */
    SUGAR_MOOD(
        key = "sugar-mood",
        titleRes = R.string.insight_sugar_title,
        bodyRes = R.string.insight_sugar_body,
        sourceRes = R.string.insight_source_sugar,
        lag = InsightLag.SAME_DAY,
        directionRes = null,
    ),
}

/** What was asked, over how long, and whatever survived being asked. */
data class InsightReport(
    val windowDays: Int,
    /**
     * How many comparisons with mood were made, said out loud on the screen — see [INSIGHT_FDR]:
     * the list's own, and the ones made elsewhere ([COMPARISONS_ELSEWHERE]).
     */
    val tested: Int,
    /** The best count of paired days any hypothesis managed, for the "not yet" counter. */
    val bestPairedDays: Int,
    val findings: List<Advice>,
)

/**
 * Everything the engine has to say about this record, which is usually nothing.
 *
 * Fewer than half of people have a detectable day-to-day association at all, so an empty list is
 * the ordinary result rather than a failure, and the screen is built to show it as an answer.
 */
fun insightReport(
    days: List<DaySteps>,
    journal: List<JournalEntry>,
    profile: UserProfile,
    today: LocalDate,
): InsightReport {
    val from = today.minusDays(INSIGHT_WINDOW_DAYS - 1L)
    val window = journal.inWindow(from, today)
    val active = activeDays(journal, from, today)
    // The cycle places each day at the same point of a repeating cycle, so that a link is not read
    // off something both series already share. Only where there are cycles to read.
    val anchors = if (profile.sex == "FEMALE") cycleAnchors(window, from, today) else null
    // The ratings as they were given, kept beside what is measured: a card that says "you rated
    // your mood … out of 5" has to print these, and what is left of them below averages near nought.
    val rated = moodSeries(window)
    val mood = residualise(rated, anchors)
    val exposures = mapOf(
        InsightHypothesis.STEPS_MOOD to residualise(stepSeries(days.inStepWindow(from, today))),
        InsightHypothesis.ALCOHOL_MOOD to ethanolExposure(journal, active),
        InsightHypothesis.SLEEP_MOOD to residualise(sleepMinuteSeries(window)),
        InsightHypothesis.INTIMACY_MOOD to exposureSeries(window, active) {
            it.cycleLog() in PARTNERED_LOGS
        },
        InsightHypothesis.SUGAR_MOOD to residualise(nutrientSeries(window) { it.sugarG }),
    )

    // Left out before the correction below and not after it: a question that is not put to this
    // profile is not a comparison made, and the count the screen says aloud is the count measured.
    val asked = InsightHypothesis.entries.filter { it.askedOf(profile, today) }
    val measured = asked.map { hypothesis ->
        val exposure = exposures.getValue(hypothesis)
        val seed = insightSeed(hypothesis.key, "mood", today)
        val forward = insightLink(exposure, mood, hypothesis.lag, INSIGHT_WINDOW_DAYS, seed, shown = rated)
        val reverse = insightLink(exposure, mood, InsightLag.OUTCOME_YESTERDAY, INSIGHT_WINDOW_DAYS, seed + 1)
        Triple(hypothesis, forward, reverse)
    }

    // Each forward link's bootstrap p-value, corrected across the whole candidate list — a link too
    // thin to have one counts as a comparison made, at 1, and so does the one made on another page
    // ([COMPARISONS_ELSEWHERE]): at 1 it can only make the line stricter for the rest, never pass
    // itself. A silent display filter, disclosed on the screen.
    val chances = measured.map { (_, forward, _) -> forward.contrast?.chance ?: 1.0 }
    val keep = benjaminiHochberg(chances + List(COMPARISONS_ELSEWHERE) { 1.0 }, INSIGHT_FDR)

    val findings = measured.filterIndexed { index, _ -> keep[index] }
        .mapNotNull { (hypothesis, forward, reverse) -> hypothesis.advice(forward, reverse, profile) }

    return InsightReport(
        windowDays = INSIGHT_WINDOW_DAYS,
        tested = asked.size + COMPARISONS_ELSEWHERE,
        bestPairedDays = measured.maxOfOrNull { (_, forward, _) -> forward.pairedDays } ?: 0,
        findings = findings,
    )
}

/**
 * Whether this profile is asked the question at all. The two about what the age gate removes
 * ([sexLogOffered], [habitsOffered]) are not put to a profile under it: this page reads the whole
 * journal, and the acts or the drinks a restored backup brought would come back as a card about
 * them, shown to somebody the app offers neither log to.
 */
private fun InsightHypothesis.askedOf(profile: UserProfile, today: LocalDate): Boolean = when (this) {
    InsightHypothesis.INTIMACY_MOOD -> sexLogOffered(profile.birthDate, today)
    InsightHypothesis.ALCOHOL_MOOD -> habitsOffered(profile.birthDate, today)
    else -> true
}

/**
 * Whether the reverse control was run and came back quiet, which is all that lets a direction into
 * words.
 *
 * A reverse under its gates has no range at all — it loses a day of the window to the lag, so it is
 * routinely the thinner of the two — and a control that was not run cannot clear a direction: the
 * rule [LaggedContrast.directed] holds the sheet's comparisons to. Read as "does not exclude zero",
 * a forward link on exactly ninety paired days would be worded as one-way on the strength of a
 * reverse that had eighty-nine and was never measured.
 */
internal fun reverseRuledOut(reverse: InsightLink): Boolean = reverse.contrast?.excludesZero == false

/** Grams of ethanol at or above the level the evidence is actually about, as a yes or a no. */
private fun ethanolExposure(journal: List<JournalEntry>, active: Set<LocalDate>): DailySeries {
    val byDay = ethanolSeries(journal).byDay
    return DailySeries(active.associateWith { if ((byDay[it] ?: 0.0) >= RISKY_ETHANOL_G) 1.0 else 0.0 })
}

/**
 * The grams of ethanol a day above which the next-day finding exists at all.
 *
 * Roughly three UK units. Gawn 2025 found the effect at medium- and high-risk drinking and not at
 * low-risk, and a card that fired below this would be reporting something nobody measured.
 */
private const val RISKY_ETHANOL_G = 24.0

/**
 * Turns a measured link into a card, or into nothing: the range must exclude zero and the difference
 * must reach [InsightGates.minEffect]. A direction is worded only for a hypothesis that carries one,
 * and only where the reverse lag was measured and its range does not exclude zero — see
 * [reverseRuledOut].
 */
private fun InsightHypothesis.advice(
    forward: InsightLink,
    reverse: InsightLink,
    profile: UserProfile,
): Advice? {
    val contrast = forward.contrast ?: return null
    if (!contrast.excludesZero) return null
    if (kotlin.math.abs(contrast.difference) < INSIGHT_GATES.minEffect) return null

    // A direction goes into words only when the forward reading stands and the reverse one was
    // weighed and does not. Otherwise the sentence stays symmetric, which is all the data can carry.
    val oneWay = directionRes != null && reverseRuledOut(reverse)
    return Advice(
        topic = AdviceTopic.INSIGHTS,
        level = AdviceLevel.HABIT,
        titleRes = titleRes,
        bodyRes = bodyRes,
        args = listOf(
            Counted(contrast.highDays, R.plurals.count_days),
            Decimal(contrast.highShown),
            Counted(contrast.lowDays, R.plurals.count_days),
            Decimal(contrast.lowShown),
            Decimal(contrast.difference),
            Decimal(contrast.lo),
            Decimal(contrast.hi),
            Counted(forward.pairedDays, R.plurals.count_days),
            Labeled(
                when {
                    oneWay -> directionRes
                    forward.confidence == InsightConfidence.EARLY -> R.string.insight_direction_early
                    else -> R.string.insight_direction_either_way
                },
            ),
        ),
        sourceRes = sourceRes,
        windowDays = INSIGHT_WINDOW_DAYS,
        id = "insight-$key-${profile.sex.lowercase()}",
    )
}

private fun List<JournalEntry>.inWindow(from: LocalDate, until: LocalDate): List<JournalEntry> = filter {
    it.day.toLocalDateOrNull()?.let { day -> day in from..until } == true
}

private fun List<DaySteps>.inStepWindow(from: LocalDate, until: LocalDate): List<DaySteps> = filter {
    it.day.toLocalDateOrNull()?.let { day -> day in from..until } == true
}

/** Partnered intimacy, which is the only kind the lagged finding is about. */
private val PARTNERED_LOGS = setOf(CycleLog.SEX_PROTECTED, CycleLog.SEX_UNPROTECTED)

/**
 * Each day's place in the menstrual cycle, for the residualiser to subtract by.
 *
 * Counted backwards from the next recorded period start rather than forwards from the last one,
 * and never from an assumed ovulation: calendar prediction of ovulation has been measured at no
 * better than about 21% accurate against daily urine LH (Johnson, Marriott & Zinaman 2018, Curr Med
 * Res Opin 34(9):1587-1594, 949 volunteers). Counting back from a start that actually happened
 * needs no prediction at all.
 *
 * It matters most for intimacy against mood, where desire and mood are both cycle-linked and a
 * link between them could be read off the cycle alone.
 */
private fun cycleAnchors(
    entries: List<JournalEntry>,
    from: LocalDate,
    until: LocalDate,
): Map<LocalDate, Int>? {
    val starts = entries.filter { it.kind == KIND_CYCLE }
        .mapNotNull { it.day.toLocalDateOrNull() }
        .sorted()
    if (starts.size < 2) return null
    var day = from
    val anchors = mutableMapOf<LocalDate, Int>()
    while (day <= until) {
        starts.firstOrNull { it > day }?.let { next ->
            anchors[day] = java.time.temporal.ChronoUnit.DAYS.between(day, next).toInt()
        }
        day = day.plusDays(1)
    }
    return anchors.takeIf { it.isNotEmpty() }
}
