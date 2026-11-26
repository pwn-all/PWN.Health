package com.pwnall.health

import androidx.annotation.StringRes
import androidx.core.content.edit
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * How strongly a piece of advice is put.
 *
 * [CLINICIAN] is the only level that mentions a doctor, and it is shown once per topic: a person who
 * read it and chose not to act does not need it repeated every week. That is a deliberate product
 * decision, not an oversight — nagging is how an app gets uninstalled, and the record stays visible
 * in the Health section either way.
 */
enum class AdviceLevel { HABIT, ATTENTION, CLINICIAN }

/**
 * What a piece of advice is about — and, inseparably, whether it may be shown outside the app.
 *
 * A notification is read by whoever is holding the phone, and by anyone standing behind them: a
 * lock screen announcing a cycle, a bowel habit, a blood result or a sexual symptom is a
 * disclosure the person never agreed to. Only [ACTIVITY] — steps, and getting up to move — is
 * public; everything else waits inside the app, where it took a deliberate act to look.
 *
 * The flag defaults to in-app for a new topic precisely so that forgetting to think about it fails
 * closed. [AdviceLog.pushable] is the one door, and [BodyAdviceTest] and [HealthAdviceTest] hold it
 * shut.
 *
 * [titleRes] is what the topic is called, and the card is expected to draw it. A heading reading
 * "Often" over a paragraph, on the front page, names nothing and answers nothing — the person, and
 * anyone glancing over their shoulder, is owed the subject before the sentence.
 */
enum class AdviceTopic(@StringRes val titleRes: Int, val inAppOnly: Boolean) {
    SLEEP(R.string.advice_topic_sleep, true),
    ACTIVITY(R.string.advice_topic_activity, false),
    MOOD(R.string.advice_topic_mood, true),
    CYCLE(R.string.advice_topic_cycle, true),
    ENERGY(R.string.advice_topic_energy, true),
    LABS(R.string.advice_topic_labs, true),
    BOWEL(R.string.advice_topic_bowel, true),
    HYDRATION(R.string.advice_topic_hydration, true),
    TOBACCO(R.string.advice_topic_tobacco, true),
    SEXUAL(R.string.advice_topic_sexual, true),

    /**
     * Patterns measured between two things the person writes down, rather than one reading against
     * a published figure.
     *
     * In-app only, and not by accident: an alert tied to a health value is ongoing monitoring, and
     * this is a description of somebody's own record that waits to be opened.
     */
    INSIGHTS(R.string.advice_topic_insights, true),
}

/**
 * Whether this profile is offered [topic] at all, before a single row of the journal is read.
 *
 * Three topics answer to the profile rather than to what was written down. Tobacco and intimate
 * health stand behind the age gate, and the cycle belongs to the profile that has one; every other
 * topic is the same app for everybody. Both the advice and the list of switched-off
 * topics ask here, because a name outlives the gate that let it into the set: a restored backup
 * can leave a profile with no birth date at all, which closes both gates while the set still holds
 * `SEXUAL`, and a row reading "Intimate health" is the subject itself, not a route to it.
 */
fun topicOffered(topic: AdviceTopic, profile: UserProfile, today: LocalDate): Boolean = when (topic) {
    AdviceTopic.TOBACCO -> habitsOffered(profile.birthDate, today)
    AdviceTopic.SEXUAL -> sexLogOffered(profile.birthDate, today)
    AdviceTopic.CYCLE -> profile.sex == "FEMALE"
    else -> true
}

/**
 * The switched-off topics this profile may be shown, in the order [AdviceTopic] declares them.
 *
 * The stored set is a set of names and nothing else, which leaves three problems for whoever draws
 * it. A set has no order, so the same three topics would arrange themselves differently from one
 * reading to the next; a build that drops a topic leaves its name behind in the set for ever,
 * where mapping it back would produce a row with no title; and a name switched off while a gate
 * was open stays there after it closes. Filtering the enum through [offered] — [topicOffered], for
 * every caller that has a profile — answers all three: the declaration order is the order, a name
 * this build has never heard of is not among the entries, and a topic the profile is not offered
 * is not named on the way back either.
 */
fun switchedOffTopics(hidden: Set<String>, offered: (AdviceTopic) -> Boolean): List<AdviceTopic> =
    AdviceTopic.entries.filter { it.name in hidden && offered(it) }

/**
 * A number that has to be declined with its noun. Russian has four forms where English has two, so
 * the count cannot be interpolated as a bare integer and the plural has to be resolved where the
 * string is rendered — by [formatAdviceBody] — rather than where the advice is computed.
 */
data class Counted(val quantity: Int, val pluralRes: Int)

/**
 * A word that lives in the resources, for the same reason [Counted] does: the advice is computed
 * where there is no [android.content.res.Resources], and a marker's name has to be translated.
 */
data class Labeled(val stringRes: Int)

/**
 * A reference range as its two ends, printed "24–38". Its own type rather than two plain numbers in
 * the argument list, so that the digits are laid out by the resources like every other figure in
 * the sentence, and so that lint is not left reading "%3$d and" as a count that wants a plural.
 */
data class Band(val low: Int, val high: Int)

/**
 * A volume with its unit, for thresholds the plurals cannot express — 2500 ml is not a whole number
 * of litres.
 *
 * Named for the water it carries, and not for volume in general: `androidx.health.connect.client
 * .units.Volume` is imported by name in the export, and two types called Volume in one package is
 * a resolution puzzle nobody reading either file should have to solve.
 *
 * [millilitres] is what the journal stores whatever the profile says, and [imperial] is the scale
 * it is read out on. The flag is carried here rather than asked for at rendering time for the same
 * reason [Counted] carries a plural: the advice is computed where the profile is in reach and
 * rendered where it is not, and a threshold printed in millilitres to somebody whose water card
 * says fl oz is a number they cannot check against their own screen.
 */
data class WaterVolume(val millilitres: Int, val imperial: Boolean)

/**
 * A figure with a fraction to it — a mean, a difference and the ends of its range, a BMI — printed
 * as [pretty] prints it, in the digits and with the decimal mark of the language the sentence is
 * read in. The advice is computed where no language is in reach, and a figure made text there with
 * [pretty] would read "3.5" in every one of them, where Russian and German write "3,5".
 */
data class Decimal(val value: Double)

/**
 * A figure printed already at the precision it was graded at — a lab value and the edge of its band,
 * [LabMarker.printed] — whose decimal mark and digits are the language's, like [Decimal]'s, at the
 * moment the sentence is read ([shownFigure]). As text it would read "5.7" in every language.
 */
data class PrintedFigure(val text: String)

data class Advice(
    val topic: AdviceTopic,
    val level: AdviceLevel,
    val titleRes: Int,
    val bodyRes: Int,
    /** Substituted into the body, in order. */
    val args: List<Any> = emptyList(),
    /** Named in the UI so the number is checkable rather than authoritative. */
    val sourceRes: Int,
    /** How many days the statement was computed over, shown next to it. */
    val windowDays: Int,
    /** Stable key for "already told them once". */
    val id: String,
)

// --- windows, chosen from the guidance rather than from taste ----------------------------------

/**
 * Sleep: a weekly mean needs five nights, a monthly one ten or more (consumer-tracker reliability
 * work). Fourteen nights is the shortest window that carries both a stable mean and a usable spread,
 * and it spans two weekends, without which schedule variability is invisible.
 */
const val SLEEP_WINDOW_DAYS = 14
const val SLEEP_MIN_NIGHTS = 7

/**
 * Steps: seven days is the floor that is both statistically reliable per person and free of
 * weekday/weekend bias. Three or four days is a group-level estimate, never individual coaching.
 */
const val ACTIVITY_WINDOW_DAYS = 7
const val ACTIVITY_LONG_WINDOW_DAYS = 30

/**
 * How many days before today a screen has to load for [activityAdvice] to see its month: the whole
 * long window, because the month is thirty finished days and today is not one of them. A screen
 * that read one day fewer could never tell a quiet month from a quiet week, while the health
 * section and the reminder, which read everything, would say "month" about the same record.
 */
const val ACTIVITY_HISTORY_DAYS = ACTIVITY_LONG_WINDOW_DAYS.toLong()

/**
 * Mood: fourteen days is the shortest window anything may be said over — both DSM-5-TR and ICD-11
 * anchor persistence at two weeks, and a single-item scale supports a within-person trend and
 * nothing more.
 */
const val MOOD_WINDOW_DAYS = 14
const val MOOD_MIN_ENTRIES = 7

/**
 * Energy: a week, and only the days food was actually logged — an unlogged day is not a fasted day,
 * and averaging zeros into the intake would invent a deficit nobody has.
 */
const val ENERGY_WINDOW_DAYS = 7
const val ENERGY_MIN_LOGGED_DAYS = 4

/** Cycle: three intervals before any statistic, six before the word "varies" is used at all. */
const val CYCLE_MIN_INTERVALS = 3
const val CYCLE_MIN_FOR_VARIABILITY = 6

// --- thresholds ---------------------------------------------------------------------------------

/**
 * AASM/SRS 2015: seven hours or more, on a regular basis, for adults 18–60; the NSF 2015 ceiling is
 * nine, and eight from 65. Long sleep is a marker far more often than a cause — worded accordingly.
 */
private val ADULT_SLEEP_HOURS = 7..9
private val OLDER_SLEEP_HOURS = 7..8
private const val OLDER_SLEEP_AGE = 65

/**
 * AASM 2016 paediatric consensus (Paruthi et al., J Clin Sleep Med 12(6)): 8–10 hours at 13–18 and
 * 9–12 at 6–12. The app admits minors, and without this band a fifteen-year-old averaging
 * 7 h 20 min would be told nothing while an adult sentence sat ready for anyone under seven hours.
 */
private val TEEN_SLEEP_HOURS = 8..10
private val CHILD_SLEEP_HOURS = 9..12
private const val ADULT_SLEEP_AGE = 18
private const val TEEN_SLEEP_AGE = 13
private const val CHILD_SLEEP_AGE = 6

/**
 * The band a night is read against at an age, in minutes, or null where this app has no band it may
 * honestly apply: under six the consensus counts naps into its figures, and this journal records
 * nights, so nothing is said rather than something measured against the wrong total.
 */
internal fun sleepBandMinutes(ageYears: Int): IntRange? = when {
    ageYears < CHILD_SLEEP_AGE -> null
    ageYears < TEEN_SLEEP_AGE -> CHILD_SLEEP_HOURS
    ageYears < ADULT_SLEEP_AGE -> TEEN_SLEEP_HOURS
    ageYears < OLDER_SLEEP_AGE -> ADULT_SLEEP_HOURS
    else -> OLDER_SLEEP_HOURS
}?.let { it.first * 60..it.last * 60 }

/** Ding et al. 2025: about 7000 steps a day carries most of the population-level benefit. */
private const val STEP_REFERENCE = 7_000L

/** Paluch et al. 2022: the curve flattens earlier after 60. */
private const val STEP_REFERENCE_OLDER = 6_000L
private const val OLDER_ADULT_AGE = 60

/** FIGO 2018 System 1: most adult cycles fall between these. */
private const val CYCLE_SHORT_DAYS = 24
private const val CYCLE_LONG_DAYS = 38

/**
 * ACOG Committee Opinion 651: in the years after menarche a cycle anywhere from 21 to 45 days is
 * ordinary, and the adult band does not apply. Held against the adult 24–38 instead, a
 * fifteen-year-old with 42-day cycles would be handed "longer than the reference range" and pointed
 * at a clinician for a pattern her own reference calls normal — the worst thing a health app can do
 * to a teenager, and the reason this band exists.
 *
 * The opinion states it as "within the first few years after menarche", which this app cannot see;
 * age twenty is the proxy, chosen to be generous, since a wider band only ever withholds an alarm.
 */
private const val ADOLESCENT_CYCLE_AGE = 20
private const val ADOLESCENT_CYCLE_SHORT_DAYS = 21
private const val ADOLESCENT_CYCLE_LONG_DAYS = 45

/** The band a usual cycle length ([usualCycleDays]) is read against at an age. */
internal fun cycleLengthBand(ageYears: Int): IntRange =
    if (ageYears in 1 until ADOLESCENT_CYCLE_AGE) {
        ADOLESCENT_CYCLE_SHORT_DAYS..ADOLESCENT_CYCLE_LONG_DAYS
    } else {
        CYCLE_SHORT_DAYS..CYCLE_LONG_DAYS
    }

/** FIGO regularity: shortest-to-longest spread within the reference period. */
private const val CYCLE_SPREAD_NORMAL = 7
private const val CYCLE_SPREAD_YOUNG_OR_OLDER = 9

/** Absent bleeding this long is worth raising whatever the rest of the record looks like. */
private const val CYCLE_ABSENT_DAYS = 90L

/**
 * Where the absence stops being counted out loud. Twelve months without a period is how NICE NG23
 * recognises the menopause after 45, and at any age a start a year old is as likely a record she
 * stopped keeping: by then the card has stood for nine months, and "1,095 days since the last one"
 * counts the journal, not the body. Nor is anything said about the cycles before it: they are
 * history, as the cycles before any break are ([CYCLE_BREAK_DAYS]).
 */
private const val CYCLE_ABSENT_UNTIL_DAYS = 365L

/**
 * NICE NG23: from 45, a period that has stopped is read as the menopause without a test, so the
 * absence card names it among the reasons from that age. Not before it: earlier the same silence is
 * an early menopause or, under 40, premature ovarian insufficiency — what the card is there to send
 * to a clinician — and naming the menopause to a thirty-year-old would offer her the wrong comfort.
 */
private const val MENOPAUSE_NAMED_AGE = 45

/**
 * Six hours, an absolute figure rather than a share of anybody's own usual.
 *
 * Lee (2022, *Annals of Behavioral Medicine* 56(4):393-404, N=1,958 adults over eight diary days)
 * counted consecutive nights under six hours, not nights under the sleeper's own average, and the
 * band this app already carries for a short night is a different question — that one asks whether
 * a fortnight averages badly, this one asks whether the last few nights ran together.
 */
const val SHORT_NIGHT_MINUTES = 360

/** Below this a run is a bad night or two, which everybody has and nobody needs told about. */
const val SHORT_NIGHT_RUN = 3

/**
 * How many nights in a row, ending last night, came in under [SHORT_NIGHT_MINUTES].
 *
 * Counted back from the newest night on record rather than from today, but only where that night
 * is last night or the one before: a run that ended a fortnight ago is history, and "three nights
 * in a row" is not something to say about last month. A night with nothing written down ends the
 * run — a gap is silence, not a long sleep and not a short one.
 */
fun shortNightRun(byDay: Map<LocalDate, Int>, today: LocalDate): Int {
    var day = byDay.keys.filter { it in today.minusDays(1)..today }.maxOrNull() ?: return 0
    var run = 0
    while (true) {
        val minutes = byDay[day] ?: return run
        if (minutes >= SHORT_NIGHT_MINUTES) return run
        run++
        day = day.minusDays(1)
    }
}

/** A day's sleep, summed the way [sleepSummary] sums it: a day is a night, whatever the row count. */
internal fun nightMinutesByDay(entries: List<JournalEntry>): Map<LocalDate, Int> = entries
    .mapNotNull { entry ->
        val start = entry.sleepStartAt ?: return@mapNotNull null
        val end = entry.sleepEndAt ?: return@mapNotNull null
        val minutes = ((end - start) / 60_000L).toInt().takeIf { it in 1..1_439 } ?: return@mapNotNull null
        val day = entry.day.toLocalDateOrNull() ?: return@mapNotNull null
        day to minutes
    }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, ofDay) -> ofDay.sum().coerceAtMost(1_439) }

/**
 * A run of short nights, which is a different finding from a fortnight that averages short.
 *
 * Lee 2022 found that each consecutive night under six hours went with lower positive affect
 * (-0.11), higher negative affect (+0.08) and 18% more physical symptoms — and that the curve
 * decelerates, so the largest single response is to the *first* short night rather than to the
 * third. The copy says so, because "it gets worse every night" is the obvious thing to assume and
 * it is not what the paper found.
 *
 * Nothing here promises that catching up undoes it: that study did not test recovery sleep, and a
 * promise it cannot support is worse than no sentence at all.
 */
fun shortNightsAdvice(entries: List<JournalEntry>, today: LocalDate): Advice? {
    val run = shortNightRun(nightMinutesByDay(entries), today)
    if (run < SHORT_NIGHT_RUN) return null
    return Advice(
        topic = AdviceTopic.SLEEP,
        level = AdviceLevel.ATTENTION,
        titleRes = R.string.advice_sleep_run_title,
        bodyRes = R.string.advice_sleep_run_body,
        args = listOf(Counted(run, R.plurals.count_nights), (SHORT_NIGHT_MINUTES / 60).toString()),
        sourceRes = R.string.advice_source_sleep_run,
        windowDays = run,
        id = "sleep-run",
    )
}

// One early return per reason the record cannot support a statement yet.
fun sleepAdvice(entries: List<JournalEntry>, ageYears: Int, today: LocalDate): Advice? {
    val band = sleepBandMinutes(ageYears) ?: return null
    val from = today.minusDays(SLEEP_WINDOW_DAYS - 1L)
    // A day is a night, as [sleepSummary] counts it. Read row by row, an hour on the sofa would be
    // a second night of sixty minutes: it would pull the mean down, count towards the seven nights
    // and be printed as one of them.
    val nights = nightMinutesByDay(
        entries.filter { it.day.toLocalDateOrNull()?.let { d -> d in from..today } == true },
    ).values
    if (nights.size < SLEEP_MIN_NIGHTS) return null

    val mean = nights.average().toInt()
    val minor = ageYears < ADULT_SLEEP_AGE
    return when {
        mean < band.first -> Advice(
            topic = AdviceTopic.SLEEP,
            level = AdviceLevel.ATTENTION,
            titleRes = R.string.advice_sleep_short_title,
            // The sentence names the band's own age group: "adult guidance" said to a fifteen-year-old
            // is the wrong figure with the wrong word in front of it.
            bodyRes = when {
                ageYears < TEEN_SLEEP_AGE -> R.string.advice_sleep_short_child_body
                minor -> R.string.advice_sleep_short_teen_body
                else -> R.string.advice_sleep_short_body
            },
            args = listOf(Counted(nights.size, R.plurals.count_nights), formatSleepClock(mean)),
            sourceRes = if (minor) R.string.advice_source_aasm_pediatric else R.string.advice_source_aasm,
            windowDays = SLEEP_WINDOW_DAYS,
            id = "sleep-short",
        )

        mean > band.last -> Advice(
            topic = AdviceTopic.SLEEP,
            level = AdviceLevel.HABIT,
            titleRes = R.string.advice_sleep_long_title,
            bodyRes = R.string.advice_sleep_long_body,
            // The band's two hours travel as text: "%d hour band" reads to lint as a quantity
            // wanting a plural, and "7–9 hour" is an adjective that never declines.
            args = listOf(
                Counted(nights.size, R.plurals.count_nights),
                formatSleepClock(mean),
                (band.first / 60).toString(),
                (band.last / 60).toString(),
            ),
            sourceRes = if (minor) R.string.advice_source_aasm_pediatric else R.string.advice_source_nsf,
            windowDays = SLEEP_WINDOW_DAYS,
            id = "sleep-long",
        )

        else -> null
    }
}

// One early return per reason the record cannot support a statement yet.
fun activityAdvice(days: List<DaySteps>, ageYears: Int, today: LocalDate): Advice? {
    val reference = if (ageYears >= OLDER_ADULT_AGE) STEP_REFERENCE_OLDER else STEP_REFERENCE
    // Today's count is still being written, so averaging it in would flag every morning as a quiet
    // week.
    val lastFullDay = today.minusDays(1)
    val week = daysIn(days, lastFullDay, ACTIVITY_WINDOW_DAYS)
    if (week.size < ACTIVITY_WINDOW_DAYS) return null

    val weekMean = week.sumOf(DaySteps::steps) / week.size
    if (weekMean >= reference) return null

    val month = daysIn(days, lastFullDay, ACTIVITY_LONG_WINDOW_DAYS)
    val monthMean = if (month.isEmpty()) weekMean else month.sumOf(DaySteps::steps) / month.size
    val persistent = month.size >= ACTIVITY_LONG_WINDOW_DAYS && monthMean < reference

    return Advice(
        topic = AdviceTopic.ACTIVITY,
        level = if (persistent) AdviceLevel.ATTENTION else AdviceLevel.HABIT,
        titleRes = R.string.advice_activity_title,
        bodyRes = if (persistent) R.string.advice_activity_month_body else R.string.advice_activity_week_body,
        args = listOf(
            if (persistent) monthMean.formatted() else weekMean.formatted(),
            reference.formatted(),
        ),
        sourceRes = R.string.advice_source_steps,
        windowDays = if (persistent) ACTIVITY_LONG_WINDOW_DAYS else ACTIVITY_WINDOW_DAYS,
        id = if (persistent) "activity-month" else "activity-week",
    )
}

// One early return per reason the record cannot support a statement yet.
fun moodAdvice(entries: List<JournalEntry>, today: LocalDate): Advice? {
    val from = today.minusDays(MOOD_WINDOW_DAYS - 1L)
    val window = entries.filter {
        it.kind == KIND_MOOD &&
            it.day.toLocalDateOrNull()?.let { d -> d in from..today } == true
    }
    val summary = moodSummary(window)
    if (summary.entries < MOOD_MIN_ENTRIES) return null
    // The mood card's own word for the fortnight, and no second line drawn here: low or poor by
    // [wellbeingBand], or tense by the second dial. A cut-off of its own that took 50 in, the
    // band's "Neutral", would hand a fortnight averaging the middle of the dial — "balanced" on the
    // card — the advice for a low one.
    if (!summary.suggestBreathing) return null

    val lowDays = window.filter { (it.moodScore ?: 5) <= Mood.LOW.score }
        .map(JournalEntry::day)
        .distinct()
        .size
    // The body counts the low days, and a tense fortnight can hold none: with none to name there
    // is no statement to make.
    if (lowDays == 0) return null
    return Advice(
        topic = AdviceTopic.MOOD,
        level = AdviceLevel.ATTENTION,
        titleRes = R.string.advice_mood_title,
        bodyRes = R.string.advice_mood_body,
        args = listOf(lowDays, Counted(MOOD_WINDOW_DAYS, R.plurals.count_days)),
        sourceRes = R.string.advice_source_who5,
        windowDays = MOOD_WINDOW_DAYS,
        id = "mood-low",
    )
}

/**
 * Cycle advice, and the one place a clinician is mentioned.
 *
 * Everything here is phrased as a count over the recorded window. No condition is named: naming one
 * is both wrong from an app that sees only dates, and the line that turns a wellness app into a
 * regulated medical device. The absence card lists what ordinarily stops a period — pregnancy,
 * contraception, breastfeeding, and from [MENOPAUSE_NAMED_AGE] the menopause — as things she can
 * rule in herself, not as a finding.
 */
@Suppress("ReturnCount", "LongMethod")
fun cycleAdvice(entries: List<JournalEntry>, ageYears: Int, today: LocalDate): Advice? {
    val starts = entries
        .filter { it.kind == KIND_CYCLE }
        .mapNotNull { it.day.toLocalDateOrNull() }
        .distinct()
        .sorted()
    val last = starts.lastOrNull() ?: return null

    val sinceLast = ChronoUnit.DAYS.between(last, today)
    if (sinceLast >= CYCLE_ABSENT_UNTIL_DAYS) return null
    if (sinceLast >= CYCLE_ABSENT_DAYS) {
        return Advice(
            topic = AdviceTopic.CYCLE,
            level = AdviceLevel.CLINICIAN,
            titleRes = R.string.advice_cycle_absent_title,
            bodyRes = if (ageYears >= MENOPAUSE_NAMED_AGE) {
                R.string.advice_cycle_absent_older_body
            } else {
                R.string.advice_cycle_absent_body
            },
            args = listOf(Counted(sinceLast.toInt(), R.plurals.count_days)),
            sourceRes = R.string.advice_source_figo,
            windowDays = sinceLast.toInt(),
            id = "cycle-absent",
        )
    }

    // The period pairs are read before the cycle lengths, and before the gate that wants three
    // cycles: how long the bleeding lasts is measured from one period, it is the finding here with
    // a cause worth chasing, and holding it behind three months of use would keep it from exactly
    // the woman it is for.
    prolongedPeriodAdvice(entries)?.let { return it }

    // The same gaps the cycle screen averages and predicts from, so the prompt and the screen never
    // disagree about one date — see [PLAUSIBLE_CYCLE_DAYS].
    val intervals = starts.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
        .takeLastWhile { it < CYCLE_BREAK_DAYS }
        .filter { it in PLAUSIBLE_CYCLE_DAYS }
        .takeLast(12)
    // The screen's own figure for how long her cycles run, their middle length ([usualCycleDays]):
    // as a mean, three cycles of thirty days with one start left unlogged between them read as long
    // cycles. Compared as it stands rather than as its whole part: FIGO's band is "more than
    // 38 days", and truncating 38.5 to 38 would hide a whole stretch of long cycles.
    val usual = intervals.takeIf { it.size >= CYCLE_MIN_INTERVALS }?.let(::usualCycleDays) ?: return null
    val spread = intervals.max() - intervals.min()
    val allowedSpread = if (ageYears in 26..41) CYCLE_SPREAD_NORMAL else CYCLE_SPREAD_YOUNG_OR_OLDER
    val band = cycleLengthBand(ageYears)

    return when {
        usual > band.last -> Advice(
            topic = AdviceTopic.CYCLE,
            level = AdviceLevel.CLINICIAN,
            titleRes = R.string.advice_cycle_long_title,
            bodyRes = R.string.advice_cycle_long_body,
            args = listOf(
                Counted(intervals.size, R.plurals.count_cycles),
                Counted(usual.roundToInt(), R.plurals.count_days),
                Band(band.first, band.last),
            ),
            sourceRes = R.string.advice_source_figo,
            windowDays = intervals.sum(),
            id = "cycle-long",
        )

        usual < band.first -> Advice(
            topic = AdviceTopic.CYCLE,
            level = AdviceLevel.CLINICIAN,
            titleRes = R.string.advice_cycle_short_title,
            bodyRes = R.string.advice_cycle_short_body,
            args = listOf(
                Counted(intervals.size, R.plurals.count_cycles),
                Counted(usual.roundToInt(), R.plurals.count_days),
                Band(band.first, band.last),
            ),
            sourceRes = R.string.advice_source_figo,
            windowDays = intervals.sum(),
            id = "cycle-short",
        )

        intervals.size >= CYCLE_MIN_FOR_VARIABILITY && spread > allowedSpread -> Advice(
            topic = AdviceTopic.CYCLE,
            level = AdviceLevel.ATTENTION,
            titleRes = R.string.advice_cycle_spread_title,
            bodyRes = R.string.advice_cycle_spread_body,
            args = listOf(
                intervals.min(),
                Counted(intervals.max(), R.plurals.count_days),
                Counted(allowedSpread, R.plurals.count_days),
            ),
            sourceRes = R.string.advice_source_figo,
            windowDays = intervals.sum(),
            id = "cycle-spread",
        )

        else -> null
    }
}

/**
 * Bleeding that runs longer than FIGO's eight days, when the record shows it happening rather than
 * having happened once.
 *
 * Measured over the closed periods only: an open start is a period still running or a forgotten tap,
 * and neither is a length. Two of the last four, so that one long period — a first cycle after a
 * coil, a month of illness — is not turned into a finding, while a pattern is. No condition is
 * named here either, for the reason [cycleAdvice] gives.
 */
internal fun prolongedPeriodAdvice(entries: List<JournalEntry>): Advice? {
    val recent = cyclePeriods(entries).takeLast(PERIOD_RECENT_COUNT)
        .map { ChronoUnit.DAYS.between(it.start, it.end).toInt() + 1 }
        .filter { it in PLAUSIBLE_PERIOD_DAYS }
    val long = recent.filter { it > PERIOD_LONG_DAYS }
    if (recent.size < PERIOD_MIN_FOR_PROLONGED || long.size < PERIOD_MIN_FOR_PROLONGED) return null
    return Advice(
        topic = AdviceTopic.CYCLE,
        level = AdviceLevel.CLINICIAN,
        titleRes = R.string.advice_period_long_title,
        bodyRes = R.string.advice_period_long_body,
        args = listOf(
            Counted(long.size, R.plurals.count_periods),
            Counted(recent.size, R.plurals.count_periods),
            Counted(long.max(), R.plurals.count_days),
            Counted(PERIOD_LONG_DAYS, R.plurals.count_days),
        ),
        sourceRes = R.string.advice_source_figo,
        windowDays = recent.sum(),
        id = "period-long",
    )
}

/** The stretch prolonged bleeding is read over, and how much of it has to be prolonged. */
private const val PERIOD_RECENT_COUNT = 4
private const val PERIOD_MIN_FOR_PROLONGED = 2

/**
 * Whether the week's record matches the mode the profile asks for.
 *
 * "Just tracking" says nothing at all — someone who did not ask to change their weight does not need
 * to be told their intake matched it. The comparison is the same one the day budget card makes:
 * what was eaten, minus what movement paid for, against the target the goal already shifted.
 */
// One early return per reason the record cannot support a statement yet.
fun energyAdvice(
    days: List<DaySteps>,
    journal: List<JournalEntry>,
    profile: UserProfile,
    today: LocalDate,
    currentYear: Int,
): Advice? {
    // The goal the budget itself was built on: a minor's is not acted on, and a week measured
    // against a target nothing shifted has no "weight-loss target" to be over.
    val goal = coachedEnergyGoal(profile, currentYear)
    if (goal !in setOf("LOSE", "GAIN")) return null
    // Today is still being eaten, the way [activityAdvice]'s today is still being walked: a small
    // breakfast averaged in as a whole day would make the deficit look on track every morning.
    val lastFullDay = today.minusDays(1)
    val from = lastFullDay.minusDays(ENERGY_WINDOW_DAYS - 1L)
    val inWindow = journal.filter {
        it.day.toLocalDateOrNull()?.let { day -> day in from..lastFullDay } == true
    }
    // The days a meal was logged on, with everything eaten on them: a cappuccino from the caffeine
    // dialog adds its energy, but a day of nothing else is not a day of eating ([countsAsMeal]).
    val eatenPerDay = inWindow.filter { it.kind == KIND_EAT }
        .groupBy(JournalEntry::day)
        .filterValues { it.any(JournalEntry::countsAsMeal) }
        .mapValues { (_, entries) -> entries.sumOf(JournalEntry::calories) }
    if (eatenPerDay.size < ENERGY_MIN_LOGGED_DAYS) return null

    val stepsPerDay = daysIn(days, lastFullDay, ENERGY_WINDOW_DAYS).associate { it.day to it.steps }
    val workoutsPerDay = inWindow.filter { it.kind == KIND_ACTIVITY }.groupBy(JournalEntry::day)
    val net = eatenPerDay.map { (day, eaten) ->
        val moved = movedCalories(
            workoutsPerDay[day].orEmpty(),
            stepCalories(stepsPerDay[day] ?: 0L, profile.weightKg, profile.heightCm),
        )
        eaten - moved
    }.average()
    val target = energyBudget(profile, currentYear).targetKcal

    val losing = goal == "LOSE"
    // Inclusive on both sides, because the day budget card this quotes is: [remainingKcal] leaves
    // the budget only below zero, so landing on the target is meeting it. Read strictly, a week
    // landing on the target would be both too much food for a losing goal and too little for a
    // gaining one.
    val onTrack = if (losing) net <= target else net >= target
    if (onTrack) return null
    return Advice(
        topic = AdviceTopic.ENERGY,
        level = AdviceLevel.HABIT,
        titleRes = if (losing) R.string.advice_energy_lose_title else R.string.advice_energy_gain_title,
        bodyRes = if (losing) R.string.advice_energy_lose_body else R.string.advice_energy_gain_body,
        args = listOf(
            Counted(eatenPerDay.size, R.plurals.count_days),
            net.roundToLong().formatted(),
            target.roundToLong().formatted(),
        ),
        sourceRes = R.string.advice_source_energy,
        windowDays = ENERGY_WINDOW_DAYS,
        id = if (losing) "energy-lose" else "energy-gain",
    )
}

/**
 * Everything the record currently supports, most pressing first.
 *
 * [hidden] is [AdviceLog.hidden] — the topics a person switched off, by name. The filtering happens
 * here rather than in each screen because the home page and the health section both come through
 * this one function, and a topic switched off in one of them is switched off in both. The screens
 * hold the context this function deliberately does not, so they pass the set in.
 */
fun allAdvice(
    days: List<DaySteps>,
    journal: List<JournalEntry>,
    profile: UserProfile,
    today: LocalDate,
    currentYear: Int,
    hidden: Set<String> = emptySet(),
): List<Advice> {
    // Whole years on the day, not a subtraction of years: the sleep band and the step reference are
    // birthday boundaries, and [birthYear] hands the adult band over on the 1st of January, up to
    // eleven months early. It also answers a profile with no birth date — the state a restored backup
    // leaves — with an assumed adult of forty; [ageOn] answers zero there, so the bands stay silent
    // rather than applying an age nobody gave.
    val age = ageOn(today.toString(), profile.birthDate)
    val labs = latestLabs(journal, profile)
    return (
        listOfNotNull(
            sleepAdvice(journal, age, today),
            shortNightsAdvice(journal, today),
            activityAdvice(days, age, today),
            moodAdvice(journal, today),
            energyAdvice(days, journal, profile, today, currentYear),
            cycleAdvice(journal, age, today).takeIf { topicOffered(AdviceTopic.CYCLE, profile, today) },
            hydrationAdvice(journal, today, profile.imperial),
            bowelAdvice(journal, today),
            // The last two read entries an age-gated profile has no way to write. They are gated
            // anyway, because a restored backup can put such entries in front of anyone, and a card
            // reading "tobacco every day this week" is the content itself, not a route to it. The
            // question is asked of [topicOffered] rather than of the gate directly, so that the
            // switched-off list beside this one cannot answer it differently.
            tobaccoAdvice(journal, today).takeIf { topicOffered(AdviceTopic.TOBACCO, profile, today) },
            sexualAdvice(journal, profile, today).takeIf { topicOffered(AdviceTopic.SEXUAL, profile, today) },
        ) + labsSectionAdvice(labs, journal, days, profile, currentYear, today)
        ).filterNot { it.topic.name in hidden }
        .sortedByDescending { it.level.ordinal }
}

/**
 * [this] with [today]'s count rounded down to [ADVICE_STEP_GRAIN]: the days the advice is worked
 * out from, and the key it is worked out again under. Today's row moves with every step the
 * pedometer writes, and the advice reads it, if at all, through a week's mean; keyed on the row
 * itself, every write of a walk would walk a year of the journal again.
 */
fun List<DaySteps>.coarseToday(today: String): List<DaySteps> =
    map { if (it.day == today) it.copy(steps = it.steps - it.steps % ADVICE_STEP_GRAIN) else it }

/** Steps today may move before the advice is worked out again: a week's mean moves by a seventh of it. */
private const val ADVICE_STEP_GRAIN = 500L

private fun daysIn(days: List<DaySteps>, today: LocalDate, window: Int): List<DaySteps> {
    val from = today.minusDays(window - 1L)
    return days.filter {
        it.day.toLocalDateOrNull()?.let { d -> d in from..today } == true
    }
}

/** Resolves the counted arguments, then the body. Used by both the screen and the notification. */
@Suppress("SpreadOperator") // getString takes Java varargs; there is no other way to call it.
fun android.content.res.Resources.formatAdviceBody(advice: Advice): String =
    getString(
        advice.bodyRes,
        *advice.args.map { arg ->
            when (arg) {
                is Counted -> getQuantityString(arg.pluralRes, arg.quantity, arg.quantity)
                is Labeled -> getString(arg.stringRes)
                is WaterVolume -> formatVolume(this, arg.millilitres, arg.imperial)
                is Band -> getString(R.string.cycle_figure_range, arg.low, arg.high)
                is Decimal -> arg.value.pretty(configuration.locales[0])
                is PrintedFigure -> shownFigure(arg.text, configuration.locales[0])
                else -> arg
            }
        }.toTypedArray(),
    )

/** The window and the body that published the threshold, as one line. */
fun android.content.res.Resources.formatAdviceWindow(advice: Advice): String = getString(
    R.string.health_window,
    getQuantityString(R.plurals.count_days, advice.windowDays, advice.windowDays),
    getString(advice.sourceRes),
)

/**
 * Remembers which clinician-level advice has already been said once, and which topics were told to
 * stop.
 *
 * The person who read a prompt and did not act does not need it again next week; the advice stays
 * visible in the Health section, it just stops being pushed. A hidden topic goes further and stops
 * appearing at all. Same SharedPreferences pattern the sleep estimator already uses for its
 * processed days, one file, two keys.
 */
object AdviceLog {
    private const val PREFS = "health_advice"
    private const val SHOWN = "shown_ids"
    private const val HIDDEN = "hidden_topics"

    fun alreadyTold(context: android.content.Context, id: String): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getStringSet(SHOWN, emptySet())
            .orEmpty()
            .contains(id)

    fun remember(context: android.content.Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val shown = prefs.getStringSet(SHOWN, emptySet()).orEmpty().toMutableSet()
        shown += id
        prefs.edit { putStringSet(SHOWN, shown) }
    }

    /** The topics switched off, by [AdviceTopic.name] — the only thing worth storing about them. */
    fun hidden(context: android.content.Context): Set<String> =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getStringSet(HIDDEN, emptySet())
            .orEmpty()

    /**
     * Switches a topic off everywhere: the home card, the health section and the notifications.
     *
     * The way back is [show], and it is in the health section, under the advice the switch
     * silenced. A tap on a card about somebody's own bowel or their own bedroom is often a tap
     * made in a hurry, in company, and it should not cost them the subject for good. Nothing is
     * written down beyond the set of names either way.
     */
    fun hide(context: android.content.Context, topic: AdviceTopic) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        prefs.edit { putStringSet(HIDDEN, hidden(context) + topic.name) }
    }

    /** Switches a topic back on, by taking its name back out of the one set that holds them. */
    fun show(context: android.content.Context, topic: AdviceTopic) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        prefs.edit { putStringSet(HIDDEN, hidden(context) - topic.name) }
    }

    /**
     * Whether one topic may be spoken outside the app at all: public by nature, and not switched
     * off. It is the whole privacy rule, kept where it can be read on its own — a unit test has no
     * [android.content.Context] to ask [pushable] with, and this is the part worth proving.
     */
    fun mayLeaveTheApp(topic: AdviceTopic, hidden: Set<String>): Boolean =
        !topic.inAppOnly && topic.name !in hidden

    /**
     * What may be pushed as a notification right now.
     *
     * Three rules, and only the last is about nagging. A topic marked [AdviceTopic.inAppOnly] is
     * never posted at all, whatever its level, because a notification is read by whoever is near
     * the phone; a topic the person switched off is not posted either, since off means off in every
     * direction, the lock screen included; and a clinician prompt already shown is not repeated.
     */
    fun pushable(context: android.content.Context, advice: List<Advice>): Advice? {
        val hidden = hidden(context)
        return advice
            .filter { mayLeaveTheApp(it.topic, hidden) }
            .firstOrNull { it.level != AdviceLevel.CLINICIAN || !alreadyTold(context, it.id) }
    }
}
