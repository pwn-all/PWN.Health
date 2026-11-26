package com.pwnall.health

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * [ReportPart.CHANGES]: the same figures at the start and at the end of the period, side by side, and
 * what changed in the same months.
 *
 * A doctor asks "over these months, what moved?", and the correlation engine behind the insights
 * cannot answer it: it takes out a moving 28-day median before it compares anything, which removes
 * exactly the drift of months this section is about. So this compares two stretches instead —
 * [changeStretches] says which — and every figure is counted over the days it was recorded on, with
 * those days printed beside it.
 *
 * What it will not do is say what the figures mean. A row is marked only by the word "changed", and
 * only past the app's own threshold for that figure; the rows keep one fixed order, with no colour
 * and no ranking, because an order of importance is a clinical judgement. Two figures that both
 * changed get one row from a fixed catalogue — [ComparisonPair] — whichever way either moved, since
 * printing "weight up, steps down" and staying silent about "weight up, steps up" would be choosing
 * results to fit a story. The row sets the two changes side by side, earlier → later, and says they
 * happened in the same months; the caveat under the section says the diary cannot tell which came
 * first or whether either affected the other, and a footnote gives the published background for the
 * pair, which is about other people and says so.
 *
 * The same holds between two blood results of one marker: the diary in the weeks before each draw
 * against the two results — [LabComparison] — printed only where both moved.
 *
 * It reads only sections the sheet prints. A figure is here only when its own section is in [printed],
 * and partnered sexual activity only when that section went with its dates as well: a stretch-by-
 * stretch count says more than the period's total the summary was agreed to. The one reach past the
 * start of the period is the window before a blood draw, and only beside a marker whose newer result
 * falls inside the period — the rule the blood-test context keeps. docs/REPORT_COMPARISONS.md
 * is the standing record of every pair: why its two figures can move together, what else moves them,
 * and what the app does not record for it.
 *
 * Null only when no printed section it compares holds anything in the period, and no pair of blood
 * results has a row to print.
 */
internal fun changesSection(
    sources: ReportSources,
    request: ReportRequest,
    printed: Set<ReportPart>,
    zone: ZoneId,
): ReportSection? {
    // Cut from the days the record holds, not from the start of the period: a 24-month sheet over a
    // diary begun a year ago would compare an empty stretch with the last one and could say nothing.
    val stretches = firstRecordedDay(sources, request, printed)?.let { changeStretches(it, request.to) }
    val figures = stretches?.let { changeFigures(sources, request, printed, it, zone) }.orEmpty()
    val blood = bloodComparisons(sources, request, printed, zone)
    if (figures.isEmpty() && blood.rows.isEmpty()) return null
    val pairs = pairsShown(figures)
    val rows = buildList {
        if (stretches != null && figures.isNotEmpty()) {
            addAll(stretchRows(stretches))
            addAll(figureRows(figures.values))
            addAll(pairRows(figures, pairs))
        }
        blood.checked?.let(::add)
        addAll(blood.rows)
        if (stretches != null && figures.isNotEmpty()) betweenRow(sources, printed, stretches)?.let(::add)
    }
    return ReportSection(
        part = ReportPart.CHANGES,
        rows = rows,
        provenance = provenanceOf(figures.values.flatMap(Figure::sources) + blood.sources),
        caveatRes = R.string.report_changes_trailer,
        footnotes = (pairs.map { it.footnote(sources.profile.sex) } + blood.shown.map(LabComparison::footnote))
            .distinct(),
    )
}

/**
 * The first day in the period with anything on it — a journal row or a counted day of steps.
 *
 * A laboratory sheet is not such a row: it is copied in afterwards under the date of the draw, which
 * can be a year before the diary was begun. One old result would start the stretches in months that
 * hold nothing else, and every figure on a 24-month sheet would go to "not compared" — the empty stretch
 * the cut from the record is there to avoid. No figure here reads a sheet; the blood results are
 * compared by their own draws, whatever the stretches are.
 *
 * Nor is a planned workout, put on a day ahead of it and saying nothing of the day it stands on; nor
 * a sexual row no printed figure reads — an act, a solo one or a morning check-in. The stretches'
 * dates are printed, and the day a diary of sex was begun would stand on the sheet as the day the
 * record began, on a sheet that left that section off.
 */
private fun firstRecordedDay(sources: ReportSources, request: ReportRequest, printed: Set<ReportPart>): LocalDate? {
    val read = ChangeDomain.entries.filter { it.printedOn(printed, request, sources.profile.sex) }
    val rows = sources.entries.filter { it.startsTheRecord(read) }.map(JournalEntry::day)
    return (rows + sources.days.map(DaySteps::day))
        .filter { it >= request.from.toString() && it <= request.to.toString() }
        .minOrNull()
        ?.toDayOrNull()
}

/** Whether a row can be the day the record the stretches are cut from begins: see [firstRecordedDay]. */
private fun JournalEntry.startsTheRecord(read: List<ChangeDomain>): Boolean = when {
    kind == KIND_LABS || kind == KIND_PLAN -> false
    isSexualAct() -> read.any { it in DATED_ONLY }
    kind == KIND_MORNING_CHECK -> ChangeDomain.MORNING in read
    else -> true
}

/**
 * The two stretches of a period that are compared.
 *
 * [yearApart] where they are the same days of the year a year apart, which takes the season out of
 * the comparison; otherwise they are the first and the last third, and the middle third is left out
 * because it blurs the change between them. The two windows before two blood draws are held in the
 * same shape, so every figure reads them the way it reads the thirds.
 */
internal data class ChangeStretches(
    val first: ClosedRange<LocalDate>,
    val last: ClosedRange<LocalDate>,
    val yearApart: Boolean,
) {
    /**
     * Whether the thirds are far enough apart to fall in different seasons, which a doctor has to be
     * told before reading two step counts: Clemes 2011 (J Phys Act Health 8:36; n = 96, four weeks in
     * summer and four the next winter) measured the same people walking 1,737 fewer steps a day in
     * winter in the normal-weight group and 781 fewer in the overweight one.
     *
     * Read off how far apart the stretches' middles are rather than off their length: the thirds of
     * a 365-day diary are 121 days each, and a test on 122 would leave an autumn set against a summer
     * without a word. Half a year apart is two seasons; the thirds of a half-year are four months
     * apart and in neighbouring ones.
     *
     * Nor are they in different seasons for being further apart still. On a 24-month sheet over a
     * diary of fourteen to sixteen months the thirds are up to 324 days apart — most of a year, and
     * so largely the same days of it — and the line would tell a doctor to allow for a season that is
     * not there. Where the last stretch, set a year back, shares half its days or more with the
     * first, nothing is said: they are not the same days of the year either, which is what the
     * other line claims.
     */
    val seasonsDiffer: Boolean
        get() = !yearApart &&
            ChronoUnit.DAYS.between(first.middle, last.middle) >= SEASONS_APART_DAYS &&
            sharedDaysOfYear * 2 < last.dayCount

    /** The days of the year both stretches hold: those the last one, set a year back, shares with the first. */
    private val sharedDaysOfYear: Long
        get() {
            val from = maxOf(first.start, last.start.minusYears(1))
            val to = minOf(first.endInclusive, last.endInclusive.minusYears(1))
            return (ChronoUnit.DAYS.between(from, to) + 1).coerceAtLeast(0)
        }
}

private val ClosedRange<LocalDate>.middle: LocalDate
    get() = start.plusDays(ChronoUnit.DAYS.between(start, endInclusive) / 2)

/** Half a year between the middles of the two stretches. */
private const val SEASONS_APART_DAYS = 182

/**
 * The stretches a period from [from] to [to] is compared over, or null where it is too short to have
 * any.
 *
 * Where the period reaches far enough back to hold them, the last [YEAR_STRETCH_DAYS] days are
 * compared with the same days a year earlier — the 24-month sheet. Otherwise the first third is
 * compared with the last: a month each on a quarter's sheet, two on a half-year's, and a third of a
 * year each on a year's, whose stretches then fall in different seasons and say so.
 */
internal fun changeStretches(from: LocalDate, to: LocalDate): ChangeStretches? {
    val lastStart = to.minusDays(YEAR_STRETCH_DAYS - 1L)
    val yearBefore = lastStart.minusYears(1)..to.minusYears(1)
    if (yearBefore.start >= from) return ChangeStretches(yearBefore, lastStart..to, yearApart = true)
    val third = (ChronoUnit.DAYS.between(from, to) + 1) / 3
    return if (third < 1) {
        null
    } else {
        ChangeStretches(from..from.plusDays(third - 1), to.minusDays(third - 1)..to, yearApart = false)
    }
}

/**
 * The figures the section can compare, each tied to the section it is read out of. The order is the
 * order they are printed in, and it never changes with what they show.
 */
internal enum class ChangeDomain(val part: ReportPart) {
    WEIGHT(ReportPart.WEIGHT),
    PRESSURE(ReportPart.PRESSURE),
    PULSE(ReportPart.PULSE),
    STEPS(ReportPart.ACTIVITY),
    SLEEP(ReportPart.SLEEP),
    WOUND_UP(ReportPart.MOOD),
    MOOD(ReportPart.MOOD),
    WORK(ReportPart.WORK),
    HEAD_NECK_PAIN(ReportPart.PAIN),
    PARTNERED(ReportPart.SEXUAL_HEALTH),
    SOLO(ReportPart.SEXUAL_HEALTH),
    MORNING(ReportPart.MORNING_ERECTIONS),
    ALCOHOL(ReportPart.SOCIAL),
    TOBACCO(ReportPart.SOCIAL),
    FOOD(ReportPart.NUTRITION),
    SUGAR(ReportPart.NUTRITION),
    SATURATED_FAT(ReportPart.NUTRITION),
    SODIUM(ReportPart.NUTRITION),
    CAFFEINE(ReportPart.NUTRITION),
    WATER(ReportPart.NUTRITION),
}

/**
 * Whether the sheet prints the section this figure is read out of — and, for partnered and solo
 * activity, prints it with its dates, which is what a stretch-by-stretch count discloses — on a
 * profile the section is offered to: the morning check-ins are a man's, and a figure read out of them
 * stays off anybody else's sheet whatever the ticks say.
 */
private fun ChangeDomain.printedOn(printed: Set<ReportPart>, request: ReportRequest, sex: String): Boolean =
    part in printed && (part.sex == null || part.sex == sex) &&
        (this !in DATED_ONLY || request.sexualDetail == SexualDetail.DETAILED)

/** The figures that count acts stretch by stretch, and so go only with the sexual section's dates. */
private val DATED_ONLY = setOf(ChangeDomain.PARTNERED, ChangeDomain.SOLO)

/**
 * The fixed catalogue of pairs: every pair of figures the section looks at on every sheet, and the
 * only ones it will ever set in a sentence.
 *
 * A pair is printed when both of its figures changed past their thresholds, whichever way either
 * moved — never picked by direction — as one row: [exposure] earlier → later, and "in the same
 * months" [outcome] earlier → later, with the days behind both. The names say which figure is read
 * first, not which came first: the arrow is "then → now". [also] is a third figure the sentence
 * names where it changed too, in [alsoSentenceRes].
 *
 * Each pair carries one footnote, [backgroundRes] with [sourceRes]: published research about other
 * people, printed at the foot of the sheet as background and never as a finding about this one. A
 * pair with a finding about men alone carries it in [maleBackgroundRes] and [maleSourceRes], printed
 * instead on a male profile's sheet only: a finding about erections says nothing about a woman's
 * count, and set under it would read as though it did.
 *
 * Every [key] has a section of its own in docs/REPORT_COMPARISONS.md — why the two can move
 * together, what else moves them, what the app does not record — and a test holds the two lists
 * equal, so a pair cannot be added without being worked through there.
 *
 * Sensitive pairs need no gate of their own: partnered and solo activity are figures only where
 * their section went with its dates, and the mornings only where their own section went, on a man's
 * sheet ([printedOn]) — so a pair that names one cannot reach a sheet that does not.
 */
internal enum class ComparisonPair(
    val key: String,
    val exposure: ChangeDomain,
    val outcome: ChangeDomain,
    val sentenceRes: Int,
    val backgroundRes: Int,
    val sourceRes: Int,
    val also: ChangeDomain? = null,
    val alsoSentenceRes: Int? = null,
    val maleBackgroundRes: Int? = null,
    val maleSourceRes: Int? = null,
) {
    WEIGHT_STEPS(
        "weight_steps",
        ChangeDomain.WEIGHT,
        ChangeDomain.STEPS,
        R.string.report_changes_pair_weight_steps,
        R.string.report_changes_background_weight_steps,
        R.string.report_changes_source_weight_steps,
        also = ChangeDomain.WOUND_UP,
        alsoSentenceRes = R.string.report_changes_pair_weight_steps_wound_up,
    ),
    WOUND_UP_PARTNERED(
        "wound_up_partnered",
        ChangeDomain.WOUND_UP,
        ChangeDomain.PARTNERED,
        R.string.report_changes_pair_wound_up_partnered,
        R.string.report_changes_background_wound_up_partnered,
        R.string.report_changes_source_wound_up_partnered,
    ),
    FOOD_WEIGHT(
        "food_weight",
        ChangeDomain.FOOD,
        ChangeDomain.WEIGHT,
        R.string.report_changes_pair_food_weight,
        R.string.report_changes_background_food_weight,
        R.string.report_changes_source_food_weight,
    ),
    ALCOHOL_MOOD(
        "alcohol_mood",
        ChangeDomain.ALCOHOL,
        ChangeDomain.MOOD,
        R.string.report_changes_pair_alcohol_mood,
        R.string.report_changes_background_alcohol_mood,
        R.string.report_changes_source_alcohol_mood,
    ),
    ALCOHOL_WOUND_UP(
        "alcohol_wound_up",
        ChangeDomain.ALCOHOL,
        ChangeDomain.WOUND_UP,
        R.string.report_changes_pair_alcohol_wound_up,
        R.string.report_changes_background_alcohol_wound_up,
        R.string.report_changes_source_alcohol_wound_up,
    ),
    ALCOHOL_SLEEP(
        "alcohol_sleep",
        ChangeDomain.ALCOHOL,
        ChangeDomain.SLEEP,
        R.string.report_changes_pair_alcohol_sleep,
        R.string.report_changes_background_alcohol_sleep,
        R.string.report_changes_source_alcohol_sleep,
    ),
    ALCOHOL_PARTNERED(
        "alcohol_partnered",
        ChangeDomain.ALCOHOL,
        ChangeDomain.PARTNERED,
        R.string.report_changes_pair_alcohol_partnered,
        R.string.report_changes_background_alcohol_partnered,
        R.string.report_changes_source_alcohol_partnered,
        maleBackgroundRes = R.string.report_changes_background_alcohol_partnered_male,
        maleSourceRes = R.string.report_changes_source_alcohol_partnered_male,
    ),
    SLEEP_MOOD(
        "sleep_mood",
        ChangeDomain.SLEEP,
        ChangeDomain.MOOD,
        R.string.report_changes_pair_sleep_mood,
        R.string.report_changes_background_sleep_mood,
        R.string.report_changes_source_sleep_mood,
    ),
    SLEEP_WOUND_UP(
        "sleep_wound_up",
        ChangeDomain.SLEEP,
        ChangeDomain.WOUND_UP,
        R.string.report_changes_pair_sleep_wound_up,
        R.string.report_changes_background_sleep_wound_up,
        R.string.report_changes_source_sleep_wound_up,
    ),
    SLEEP_FOOD(
        "sleep_food",
        ChangeDomain.SLEEP,
        ChangeDomain.FOOD,
        R.string.report_changes_pair_sleep_food,
        R.string.report_changes_background_sleep_food,
        R.string.report_changes_source_sleep_food,
    ),
    CAFFEINE_SLEEP(
        "caffeine_sleep",
        ChangeDomain.CAFFEINE,
        ChangeDomain.SLEEP,
        R.string.report_changes_pair_caffeine_sleep,
        R.string.report_changes_background_caffeine_sleep,
        R.string.report_changes_source_caffeine_sleep,
    ),
    STEPS_MOOD(
        "steps_mood",
        ChangeDomain.STEPS,
        ChangeDomain.MOOD,
        R.string.report_changes_pair_steps_mood,
        R.string.report_changes_background_steps_mood,
        R.string.report_changes_source_steps_mood,
    ),
    WEIGHT_PRESSURE(
        "weight_pressure",
        ChangeDomain.WEIGHT,
        ChangeDomain.PRESSURE,
        R.string.report_changes_pair_weight_pressure,
        R.string.report_changes_background_weight_pressure,
        R.string.report_changes_source_weight_pressure,
    ),
    SODIUM_PRESSURE(
        "sodium_pressure",
        ChangeDomain.SODIUM,
        ChangeDomain.PRESSURE,
        R.string.report_changes_pair_sodium_pressure,
        R.string.report_changes_background_sodium_pressure,
        R.string.report_changes_source_sodium_pressure,
    ),
    ALCOHOL_PRESSURE(
        "alcohol_pressure",
        ChangeDomain.ALCOHOL,
        ChangeDomain.PRESSURE,
        R.string.report_changes_pair_alcohol_pressure,
        R.string.report_changes_background_alcohol_pressure,
        R.string.report_changes_source_alcohol_pressure,
    ),
    STEPS_PRESSURE(
        "steps_pressure",
        ChangeDomain.STEPS,
        ChangeDomain.PRESSURE,
        R.string.report_changes_pair_steps_pressure,
        R.string.report_changes_background_steps_pressure,
        R.string.report_changes_source_steps_pressure,
    ),
    TOBACCO_PULSE(
        "tobacco_pulse",
        ChangeDomain.TOBACCO,
        ChangeDomain.PULSE,
        R.string.report_changes_pair_tobacco_pulse,
        R.string.report_changes_background_tobacco_pulse,
        R.string.report_changes_source_tobacco_pulse,
    ),
    WORK_MOOD(
        "work_mood",
        ChangeDomain.WORK,
        ChangeDomain.MOOD,
        R.string.report_changes_pair_work_mood,
        R.string.report_changes_background_work_mood,
        R.string.report_changes_source_work_mood,
    ),
    WORK_WOUND_UP(
        "work_wound_up",
        ChangeDomain.WORK,
        ChangeDomain.WOUND_UP,
        R.string.report_changes_pair_work_wound_up,
        R.string.report_changes_background_work_wound_up,
        R.string.report_changes_source_work_wound_up,
    ),
    WORK_SLEEP(
        "work_sleep",
        ChangeDomain.WORK,
        ChangeDomain.SLEEP,
        R.string.report_changes_pair_work_sleep,
        R.string.report_changes_background_work_sleep,
        R.string.report_changes_source_work_sleep,
    ),
    WORK_ALCOHOL(
        "work_alcohol",
        ChangeDomain.WORK,
        ChangeDomain.ALCOHOL,
        R.string.report_changes_pair_work_alcohol,
        R.string.report_changes_background_work_alcohol,
        R.string.report_changes_source_work_alcohol,
    ),
    WOUND_UP_PAIN(
        "wound_up_pain",
        ChangeDomain.WOUND_UP,
        ChangeDomain.HEAD_NECK_PAIN,
        R.string.report_changes_pair_wound_up_pain,
        R.string.report_changes_background_wound_up_pain,
        R.string.report_changes_source_wound_up_pain,
    ),
    SLEEP_MORNING(
        "sleep_morning",
        ChangeDomain.SLEEP,
        ChangeDomain.MORNING,
        R.string.report_changes_pair_sleep_morning,
        R.string.report_changes_background_sleep_morning,
        R.string.report_changes_source_sleep_morning,
    ),

    /**
     * The one pair whose published direction differs by sex: in the surveys behind it, men who
     * masturbated more tended to have somewhat less partnered sex and women more. The plain background
     * is the women's finding, the male one the men's; neither is a verdict on this person's two counts.
     */
    SOLO_PARTNERED(
        "solo_partnered",
        ChangeDomain.SOLO,
        ChangeDomain.PARTNERED,
        R.string.report_changes_pair_solo_partnered,
        R.string.report_changes_background_solo_partnered,
        R.string.report_changes_source_solo_partnered,
        maleBackgroundRes = R.string.report_changes_background_solo_partnered_male,
        maleSourceRes = R.string.report_changes_source_solo_partnered_male,
    ),
}

/**
 * The pair's footnote on the sheet of a profile of [sex]: its published background and where it was
 * published — the one about men where the profile is male and the pair has one.
 */
internal fun ComparisonPair.footnote(sex: String): ReportText {
    val background = maleBackgroundRes?.takeIf { sex == "MALE" } ?: backgroundRes
    val source = maleSourceRes?.takeIf { sex == "MALE" } ?: sourceRes
    return res(R.string.report_changes_background, res(background), res(source))
}

/**
 * The catalogue for two blood results of one marker: the diary figure read in the window before each
 * draw, and the markers it is read beside.
 *
 * The window is the one [LAB_DIET_WINDOWS] gives the marker, or [BLOOD_WINDOW_DAYS] where it gives
 * none; the figure is read with its own gate in each window, and a food figure also with the share of
 * the window the blood-test screen asks of food ([LAB_DIET_MIN_COVERAGE]). A row is printed where both the
 * figure moved past its threshold and the two results the blood-test section prints differ as printed;
 * a window that cannot be described refuses with its date. Every [key] has its section in
 * docs/REPORT_COMPARISONS.md, like a [ComparisonPair]'s.
 *
 * HbA1c is beside no diary figure: in trials of adults without diabetes it did not clearly differ with
 * walking or with added sugars, no checked figure ties it to weight, and the study behind its
 * three-month window describes the window, not a link. The blood-test context still prints the steps
 * and the sugar before each draw of it, and the nearest weigh-in.
 *
 * No verdict, no percentage and no word on which way is good: whether a change in a laboratory
 * result means anything is exactly the interpretation that makes software a medical device (FD&C Act
 * §520(o)(1)(D); MDCG 2019-11 rev.1), and one person's two results are not evidence of it anyway.
 */
internal enum class LabComparison(
    val key: String,
    val exposure: ChangeDomain,
    val markers: List<LabMarker>,
    val backgroundRes: Int,
    val sourceRes: Int,
) {
    ALCOHOL_LIVER_ENZYMES(
        "alcohol_liver_enzymes",
        ChangeDomain.ALCOHOL,
        listOf(LabMarker.GGT, LabMarker.ALT, LabMarker.AST),
        R.string.report_changes_background_alcohol_liver_enzymes,
        R.string.report_changes_source_alcohol_liver_enzymes,
    ),
    ALCOHOL_LIPIDS(
        "alcohol_lipids",
        ChangeDomain.ALCOHOL,
        listOf(LabMarker.TRIGLYCERIDES, LabMarker.HDL),
        R.string.report_changes_background_alcohol_lipids,
        R.string.report_changes_source_alcohol_lipids,
    ),
    ALCOHOL_URATE(
        "alcohol_urate",
        ChangeDomain.ALCOHOL,
        listOf(LabMarker.URIC_ACID),
        R.string.report_changes_background_alcohol_urate,
        R.string.report_changes_source_alcohol_urate,
    ),
    STEPS_BLOOD(
        "steps_blood",
        ChangeDomain.STEPS,
        listOf(LabMarker.TRIGLYCERIDES, LabMarker.HDL),
        R.string.report_changes_background_steps_blood,
        R.string.report_changes_source_steps_blood,
    ),
    WEIGHT_BLOOD(
        "weight_blood",
        ChangeDomain.WEIGHT,
        listOf(LabMarker.LDL, LabMarker.TRIGLYCERIDES, LabMarker.ALT),
        R.string.report_changes_background_weight_blood,
        R.string.report_changes_source_weight_blood,
    ),
    SUGAR_BLOOD(
        "sugar_blood",
        ChangeDomain.SUGAR,
        listOf(LabMarker.TRIGLYCERIDES),
        R.string.report_changes_background_sugar_blood,
        R.string.report_changes_source_sugar_blood,
    ),
    SATURATED_FAT_CHOLESTEROL(
        "saturated_fat_cholesterol",
        ChangeDomain.SATURATED_FAT,
        listOf(LabMarker.LDL, LabMarker.NON_HDL),
        R.string.report_changes_background_saturated_fat_cholesterol,
        R.string.report_changes_source_saturated_fat_cholesterol,
    ),
}

/** The comparison's footnote, as a pair's: its published background and where it was published. */
internal val LabComparison.footnote: ReportText
    get() = res(R.string.report_changes_background, res(backgroundRes), res(sourceRes))

/**
 * One figure in both stretches.
 *
 * [values] is null where either stretch holds too little to describe; [counts] then says how little,
 * [described] says which stretch it was, and the figure is listed as not compared. [note] is what
 * the printed row carries beside the two figures — the counts, unless the figures already are
 * counts, and for food energy the counts with the entries a counted day held: see [foodFigure].
 */
private class Figure(
    val label: ReportText,
    val counts: ReportText,
    val values: Pair<ReportText, ReportText>? = null,
    val changed: Boolean = false,
    val stretchSources: StretchSources = emptyList<String?>() to emptyList(),
    val note: ReportText? = counts,
    val described: Pair<Boolean, Boolean> = (values != null) to (values != null),
) {
    /** "80 kg → 84 kg": earlier, then later. */
    val fromTo: ReportText? get() = values?.let { (a, b) -> res(R.string.report_changes_from_to, a, b) }

    /** Who wrote the rows behind the figure, both stretches together. */
    val sources: List<String?> get() = stretchSources.first + stretchSources.second

    /**
     * "source: Entered by hand → Received through Health Connect", where the rows of the two stretches
     * came from different places: a pulse typed in and a watch's, or a cuff's and one copied from
     * another app's file, are not taken the same way, and the change between them can be that alone.
     * Null where either stretch says nothing of who wrote it, as the steps never do. It sits beside
     * the mark and never moves it.
     */
    val sourceChange: ReportText?
        get() {
            fun categories(of: List<String?>) = of.map(::provenanceCategory).distinct().sorted()
            fun labels(of: List<ProvenanceCategory>) = ReportText.Join(of.map { res(it.labelRes) }, ", ")
            val first = categories(stretchSources.first)
            val last = categories(stretchSources.second)
            return if (first.isEmpty() || last.isEmpty() || first == last) {
                null
            } else {
                res(R.string.report_changes_sources, res(R.string.report_changes_from_to, labels(first), labels(last)))
            }
        }
}

/** Who wrote the rows behind a figure: the first stretch's, then the last's. */
private typealias StretchSources = Pair<List<String?>, List<String?>>

/** One figure from a number per stretch, or listed as not compared where either stretch has none. */
private fun figure(
    label: ReportText,
    counts: ReportText,
    stretches: Pair<Double?, Double?>,
    print: (Double) -> ReportText,
    moved: (Double, Double) -> Boolean,
    sources: StretchSources = emptyList<String?>() to emptyList(),
    note: ReportText = counts,
): Figure {
    val (a, b) = stretches
    val both = if (a != null && b != null) a to b else null
    return Figure(
        label = label,
        counts = counts,
        values = both?.let { (x, y) -> print(x) to print(y) },
        changed = both?.let { (x, y) -> moved(x, y) } == true,
        stretchSources = sources,
        note = note,
        described = (a != null) to (b != null),
    )
}

/**
 * The rows a set of figures is read from, with what several of them need worked out once: the whole
 * period for the thirds, everything up to the end of it for the windows before a blood draw.
 *
 * [foodShare] is the share of a stretch a food figure needs on top of its own floor of days — none
 * over the thirds, whose floors are their own, and the blood-test screen's share in the weeks before
 * a draw, rounded down to whole days as that screen rounds it. The blood-test context prints the
 * same window on the same sheet by the screen's rule, and sixteen full days of twenty-eight would be
 * described there and "not recorded" here, were the share left at 16.8.
 *
 * What the share is asked of still differs, and is meant to: every logged day there, the days on
 * which every entry carries the nutrient here — the nutrition section's rule for a day's sum. A
 * diary with blanks in it can be described there and left uncompared here.
 */
private class ChangeDiary(
    val entries: List<JournalEntry>,
    val days: List<DaySteps>,
    val profile: UserProfile,
    val imperial: Boolean,
    val zone: ZoneId,
    val foodShare: Double = 0.0,
) {
    val byHand: Set<String> by lazy { daysByHand(entries) }

    /**
     * The days the sexual diary was kept as well: a partnered count is over the days anything was
     * written by hand, the acts and solo acts included, and only this figure — read only where that
     * section went with its dates — may see them.
     *
     * Acts, never the morning check-ins: a morning adds no act, only a day without one, and those
     * mornings go to a doctor only through a part of their own that the dated section does not bring.
     * Counted here, a man who answers every morning would thin the rate out and carry how many
     * mornings he answered onto this line.
     */
    val byHandWithSex: Set<String> by lazy {
        byHand + entries.filter { it.byHand() && it.isSexualAct() }.map(JournalEntry::day)
    }

    val meals: List<JournalEntry> by lazy { entries.filter { it.kind == KIND_EAT } }

    /** One vital's readings, a reading that reached the phone twice counted once, by the sheet's own rule. */
    fun vitals(vital: Vital): List<VitalReading> = deduplicated(vitalSeries(entries, vital, profile))
}

/** What reads each figure out of a diary over two stretches; null where the diary holds none of it. */
private val FIGURES: Map<ChangeDomain, (ChangeDiary, ChangeStretches) -> Figure?> = mapOf(
    ChangeDomain.WEIGHT to { diary, stretches -> weightFigure(diary.entries, stretches, diary.imperial) },
    ChangeDomain.PRESSURE to { diary, stretches -> pressureFigure(diary.vitals(Vital.BLOOD_PRESSURE), stretches) },
    ChangeDomain.PULSE to { diary, stretches -> pulseFigure(diary.vitals(Vital.HEART_RATE), stretches) },
    ChangeDomain.STEPS to { diary, stretches -> stepFigure(diary.days, stretches) },
    ChangeDomain.SLEEP to { diary, stretches -> sleepFigure(diary.entries, stretches) },
    ChangeDomain.WOUND_UP to { diary, stretches -> woundUpFigure(diary.entries, stretches) },
    ChangeDomain.MOOD to { diary, stretches -> moodFigure(diary.entries, stretches) },
    ChangeDomain.WORK to { diary, stretches -> workFigure(diary, stretches) },
    ChangeDomain.HEAD_NECK_PAIN to { diary, stretches -> headNeckPainFigure(diary, stretches) },
    ChangeDomain.PARTNERED to { diary, stretches -> partneredFigure(diary.entries, diary.byHandWithSex, stretches) },
    ChangeDomain.SOLO to { diary, stretches -> soloFigure(diary.entries, diary.byHandWithSex, stretches) },
    ChangeDomain.MORNING to { diary, stretches -> morningFigure(diary.entries, stretches) },
    ChangeDomain.ALCOHOL to { diary, stretches -> alcoholFigure(diary.entries, diary.byHand, stretches) },
    ChangeDomain.TOBACCO to { diary, stretches -> tobaccoFigure(diary.entries, diary.byHand, stretches) },
    ChangeDomain.FOOD to { diary, stretches -> foodFigure(diary.entries, stretches) },
    ChangeDomain.SUGAR to { diary, stretches -> sugarFigure(diary, stretches) },
    ChangeDomain.SATURATED_FAT to { diary, stretches -> saturatedFatFigure(diary, stretches) },
    ChangeDomain.SODIUM to { diary, stretches -> sodiumFigure(diary, stretches) },
    ChangeDomain.CAFFEINE to { diary, stretches -> caffeineFigure(diary.entries, diary.byHand, stretches) },
    ChangeDomain.WATER to { diary, stretches -> waterFigure(diary.entries, stretches) },
)

/** Every figure whose section is printed and that holds anything in the period, in the fixed order. */
private fun changeFigures(
    sources: ReportSources,
    request: ReportRequest,
    printed: Set<ReportPart>,
    stretches: ChangeStretches,
    zone: ZoneId,
): Map<ChangeDomain, Figure> {
    val period = request.from..request.to
    val diary = ChangeDiary(
        entries = sources.entries.filter { period.holds(it.day) },
        days = sources.days.filter { period.holds(it.day) },
        profile = sources.profile,
        imperial = request.imperial(sources.profile),
        zone = zone,
    )
    return ChangeDomain.entries
        .filter { it.printedOn(printed, request, sources.profile.sex) }
        .mapNotNull { domain -> FIGURES.getValue(domain)(diary, stretches)?.let { domain to it } }
        .toMap()
}

/**
 * The days somebody wrote something down themselves, which is what a frequency is counted over.
 *
 * A day with nothing on it but a watch's pulse, a Health Connect record or the phone's own guess at a
 * night is not a day the diary was kept, and counting it would turn every such day into one without
 * a drink and without sex. A planned workout is left out too: it is put on a day ahead of it and says
 * nothing about whether that day was logged; and so is a laboratory sheet, copied in afterwards under
 * the date of the draw.
 *
 * Nor does a sexual row mark a day here, whatever the sheet ticks — an act, a solo one or a morning
 * check-in, the rule the blood-test context keeps for the same reason: a count of days beside the
 * alcohol would otherwise carry the days of sex onto a sheet that left that section off. The one
 * figure allowed to count the acts adds those back, and only those — see [ChangeDiary.byHandWithSex].
 *
 * Nor does the work timer's own row. A Start and a Stop on the widget write nothing else down:
 * counted as keeping the day, every work day would be an evening without a drink and an answered
 * day without pain, while a day off would need an entry of its own — and the work pairs would set
 * the hours against a drink rate that fell with them for no other reason. The work figures add the days
 * with a session back where they ask what is known about work ([WorkRecord.known]).
 */
internal fun daysByHand(entries: List<JournalEntry>): Set<String> = entries
    .filter {
        it.byHand() &&
            it.kind !in setOf(KIND_PLAN, KIND_NOT_DONE, KIND_LABS, KIND_WORK) &&
            !it.isSexualData()
    }
    .mapTo(HashSet(), JournalEntry::day)

/**
 * The same days as dates, for the pain, work and morning statistics, which read an outcome on them —
 * a day answered without pain, an evening without a drink. A day that does not parse is not a day.
 */
internal fun keptDays(entries: List<JournalEntry>): Set<LocalDate> =
    daysByHand(entries).mapNotNullTo(HashSet()) { it.toDayOrNull() }

private fun JournalEntry.byHand(): Boolean =
    (measurementSource == null || measurementSource == SOURCE_MANUAL) && healthConnectRecordKey == null

/**
 * Weight as the median of each stretch's weigh-ins, from [WEIGH_INS_MIN] of them at least
 * [WEIGH_IN_SPREAD_DAYS] days apart, and changed from [WEIGHT_MIN_SHARE] and [WEIGHT_MIN_KG] both.
 *
 * The median rather than the ends, and a spread rather than a count alone, because weight swings
 * inside a week and over a holiday: Turicchi 2020 (PLoS One; smart-scale users of the NoHoW trial)
 * measured 0.35% within a week and 1.35% over Christmas, not wholly lost in the months after it. A
 * stretch holding Christmas would otherwise end on it. The share sits above both swings; the
 * kilograms are the app's own floor, so that on a light person the share alone is not enough.
 */
private fun weightFigure(entries: List<JournalEntry>, stretches: ChangeStretches, imperial: Boolean): Figure? {
    val readings = weightReadings(entries)
    if (readings.isEmpty()) return null
    val first = readings.filter { stretches.first.holds(it.day) }
    val last = readings.filter { stretches.last.holds(it.day) }
    return figure(
        label = res(R.string.report_changes_weight),
        counts = res(R.string.report_changes_both, weighIns(first), weighIns(last)),
        stretches = first.describedMedian() to last.describedMedian(),
        print = { kg ->
            val shown = if (imperial) imperialWeight(kg) else kg
            withUnit(shown.pretty(), res(if (imperial) R.string.unit_lb else R.string.unit_kg))
        },
        moved = { a, b -> changedBy(a, b, WEIGHT_MIN_SHARE, WEIGHT_MIN_KG) },
        sources = first.map(WeightReading::source) to last.map(WeightReading::source),
    )
}

/** "4 weigh-ins over 20 days": how many, and how far apart the first and the last were. */
private fun weighIns(readings: List<WeightReading>): ReportText {
    val count = ReportText.Quantity(R.plurals.report_changes_weigh_ins, readings.size)
    val span = readings.spanDays()
    val over = ReportText.Quantity(R.plurals.count_days, span)
    return if (span == 0) count else res(R.string.report_changes_over, count, over)
}

/** The stretch's median weigh-in, or null where there are too few of them or they are too close together. */
private fun List<WeightReading>.describedMedian(): Double? =
    takeIf { it.size >= WEIGH_INS_MIN && it.spanDays() >= WEIGH_IN_SPREAD_DAYS }?.map(WeightReading::kg)?.medianOrNull()

/** Days from the first weigh-in to the last; nought for one weigh-in, or none. */
private fun List<WeightReading>.spanDays(): Int {
    val days = mapNotNull { it.day.toDayOrNull() }
    return if (days.size < 2) 0 else ChronoUnit.DAYS.between(days.min(), days.max()).toInt()
}

/**
 * The mean of a cuff's readings in each stretch, systolic over diastolic, from [READINGS_MIN] readings
 * on [READING_DAYS_MIN] days in each, and changed where the rounded means moved by
 * [SYSTOLIC_MIN_CHANGE] systolic or [DIASTOLIC_MIN_CHANGE] diastolic — the app's own figures, and
 * judged on the whole millimetres the row prints so that a row reading 125/80 → 130/80 is never left
 * unmarked by a fraction.
 *
 * A mean rather than any single reading, because a home pressure moves from one sitting to the next;
 * six readings on three days is the app's own floor for calling a mean a stretch's.
 */
private fun pressureFigure(readings: List<VitalReading>, stretches: ChangeStretches): Figure? {
    if (readings.isEmpty()) return null
    val first = readings.filter { stretches.first.holds(it.day) }
    val last = readings.filter { stretches.last.holds(it.day) }
    fun mean(of: List<VitalReading>): Pair<Int, Int>? = of.forAMean()?.let { rows ->
        rows.map(VitalReading::value).average().roundToInt() to
            rows.mapNotNull(VitalReading::secondary).average().roundToInt()
    }
    fun print(pair: Pair<Int, Int>) = withUnit("${pair.first}/${pair.second}", res(R.string.vitals_unit_mmhg))
    val a = mean(first)
    val b = mean(last)
    return Figure(
        label = res(R.string.report_changes_pressure),
        counts = readingCounts(first, last),
        values = if (a != null && b != null) print(a) to print(b) else null,
        changed = a != null && b != null &&
            (abs(b.first - a.first) >= SYSTOLIC_MIN_CHANGE || abs(b.second - a.second) >= DIASTOLIC_MIN_CHANGE),
        stretchSources = first.map(VitalReading::source) to last.map(VitalReading::source),
        described = (a != null) to (b != null),
    )
}

/**
 * The mean pulse of each stretch — the vitals series already leaves out what a workout wrote, but
 * cannot say a reading was taken at rest, which is why the row says "Pulse, mean" and not resting —
 * from [READINGS_MIN] readings on [READING_DAYS_MIN] days in each, and changed where the rounded
 * means moved by [PULSE_MIN_CHANGE] beats a minute: the app's own figure.
 */
private fun pulseFigure(readings: List<VitalReading>, stretches: ChangeStretches): Figure? {
    if (readings.isEmpty()) return null
    val first = readings.filter { stretches.first.holds(it.day) }
    val last = readings.filter { stretches.last.holds(it.day) }
    fun mean(of: List<VitalReading>) = of.forAMean()?.map(VitalReading::value)?.average()
    return figure(
        label = res(R.string.report_changes_pulse),
        counts = readingCounts(first, last),
        stretches = mean(first) to mean(last),
        print = { withUnit(it.roundToInt(), res(R.string.unit_bpm)) },
        moved = { a, b -> abs(b.roundToInt() - a.roundToInt()) >= PULSE_MIN_CHANGE },
        sources = first.map(VitalReading::source) to last.map(VitalReading::source),
    )
}

/** A stretch's readings where they are enough for a mean: [READINGS_MIN] of them on [READING_DAYS_MIN] days. */
private fun List<VitalReading>.forAMean(): List<VitalReading>? =
    takeIf { it.size >= READINGS_MIN && it.readingDays() >= READING_DAYS_MIN }

/** The days the readings were taken on. */
private fun List<VitalReading>.readingDays(): Int = map(VitalReading::day).distinct().size

/**
 * Steps a day over the days a counter was carried — a day at 0 is a counter nobody carried, as the
 * activity section reads it — from [STEP_DAYS_MIN] such days and [STEP_DAYS_SHARE] of the stretch,
 * and changed from [STEPS_MIN_SHARE] and [STEPS_MIN_CHANGE] a day both.
 *
 * Both floors are about the phone rather than the walking. Duncan 2018 (J Sports Sci 36:1695) found
 * people's own iPhones in everyday use counting 21.5% — 1,340 steps a day — apart from an
 * accelerometer worn alongside, which the authors put down to the phone not being carried; a change
 * smaller than that can be one habit of leaving it on a desk. The season adds more on top: see
 * [ChangeStretches.seasonsDiffer].
 *
 * Which device counted a day is not something the report is handed — [ReportSources.days] is each
 * day's largest count, whoever wrote it — so a change of device between the stretches is not seen
 * here.
 */
private fun stepFigure(days: List<DaySteps>, stretches: ChangeStretches): Figure? {
    val carried = days.groupBy(DaySteps::day).mapValues { (_, rows) -> rows.maxOf(DaySteps::steps) }
        .filterValues { it > 0L }
    if (carried.isEmpty()) return null
    val first = carried.filterKeys { stretches.first.holds(it) }.values
    val last = carried.filterKeys { stretches.last.holds(it) }.values
    fun mean(counts: Collection<Long>, stretch: ClosedRange<LocalDate>): Double? =
        counts.takeIf { it.size >= STEP_DAYS_MIN && it.size >= stretch.dayCount * STEP_DAYS_SHARE }?.average()
    return figure(
        label = res(R.string.steps),
        counts = onDays(first.size, last.size, stretches),
        stretches = mean(first, stretches.first) to mean(last, stretches.last),
        print = { res(R.string.report_steps_per_day, it.roundToLong().sheetCount()) },
        moved = { a, b -> changedBy(a, b, STEPS_MIN_SHARE, STEPS_MIN_CHANGE) },
    )
}

/**
 * Sleep a night over the nights recorded — a day is a night, summed the way the sleep section sums
 * it — from [NIGHTS_MIN] nights in each stretch, and changed from [SLEEP_MIN_CHANGE_MINUTES] and
 * [SLEEP_MIN_SHARE] both: the app's own figures. Half an hour is the floor on a short night, where 8%
 * comes to less; 8% is the floor on a long one, where half an hour does.
 *
 * Tiredness is not recorded anywhere in the app, so a pair with this figure never says it; the mood
 * and the wound-up dial are what was asked.
 */
private fun sleepFigure(entries: List<JournalEntry>, stretches: ChangeStretches): Figure? {
    val nights = nightMinutesByDay(entries).mapValues { it.value.toDouble() }
    if (nights.isEmpty()) return null
    return dailyFigure(
        label = res(R.string.sleep),
        byDay = nights,
        stretches = stretches,
        minDays = NIGHTS_MIN,
        print = { res(R.string.report_changes_per_night, formatSleepClock(it.roundToInt())) },
        moved = { a, b -> changedBy(a, b, SLEEP_MIN_SHARE, SLEEP_MIN_CHANGE_MINUTES) },
        sources = entries.filter { it.sleepStartAt != null }.sourcesIn(stretches),
    )
}

/**
 * A figure of one number a day: the mean over the days in each stretch that hold one, from [minDays]
 * of them and [minShare] of the stretch — the share in whole days, rounded down, which is how the
 * blood-test screen counts the same share of the same window (see [ChangeDiary.foodShare]).
 */
private fun dailyFigure(
    label: ReportText,
    byDay: Map<LocalDate, Double>,
    stretches: ChangeStretches,
    minDays: Int,
    print: (Double) -> ReportText,
    moved: (Double, Double) -> Boolean,
    sources: StretchSources,
    minShare: Double = 0.0,
): Figure {
    val first = byDay.filterKeys { it in stretches.first }.values
    val last = byDay.filterKeys { it in stretches.last }.values
    fun mean(values: Collection<Double>, stretch: ClosedRange<LocalDate>): Double? =
        values.takeIf { it.size >= minDays && it.size >= (stretch.dayCount * minShare).toInt() }?.average()
    return figure(
        label = label,
        counts = onDays(first.size, last.size, stretches),
        stretches = mean(first, stretches.first) to mean(last, stretches.last),
        print = print,
        moved = moved,
        sources = sources,
    )
}

/**
 * The share of the days the second dial was answered on that came back wound up or restless, from
 * [ANSWERED_DAYS_MIN] answered days in each stretch.
 *
 * Changed from [SHARE_MIN_CHANGE] of the answered days and only where the 95% interval for the
 * difference leaves out nought — both the app's own rule, as the row explaining "changed" says.
 * The figures are the two counts rather than a percentage, as the mood section prints them.
 */
private fun woundUpFigure(entries: List<JournalEntry>, stretches: ChangeStretches): Figure? {
    val answered = entries.filter { (it.energyScore ?: 0) in 1..Energy.SETTLED.score }
    if (answered.isEmpty()) return null
    val first = answered.filter { stretches.first.holds(it.day) }
    val last = answered.filter { stretches.last.holds(it.day) }
    return shareFigure(
        label = res(R.string.report_changes_wound_up),
        shares = DayShare(woundUpDays(first), first.map(JournalEntry::day).distinct().size) to
            DayShare(woundUpDays(last), last.map(JournalEntry::day).distinct().size),
        answeredRes = R.plurals.report_days_answered,
        stretches = stretches,
        sources = first.map(JournalEntry::measurementSource) to last.map(JournalEntry::measurementSource),
    )
}

/**
 * A share of answered days in each stretch, from [ANSWERED_DAYS_MIN] answered in each, and changed
 * from [SHARE_MIN_CHANGE] of them where the Newcombe 95% interval for the difference leaves out
 * nought — the wound-up figure's rule, which the pain and the morning figures keep as it is. The
 * values are the two counts, "3 of 20 days answered", rather than a percentage; [answeredRes] names
 * what was answered.
 */
private fun shareFigure(
    label: ReportText,
    shares: Pair<DayShare, DayShare>,
    answeredRes: Int,
    stretches: ChangeStretches,
    sources: StretchSources,
): Figure {
    val (first, last) = shares
    val enough = first.of >= ANSWERED_DAYS_MIN && last.of >= ANSWERED_DAYS_MIN
    fun share(of: DayShare) = res(R.string.report_count_of, of.days, ReportText.Quantity(answeredRes, of.of))
    // Cross-multiplied, so a difference of exactly twenty points is not lost to a division — nor to
    // the threshold itself, which is not a fifth in binary: 3 of 14 days against 1 of 70 is twenty
    // points, and 196 falls short of a product that comes to 196.00000000000003.
    val apart = abs(last.days * first.of - first.days * last.of).toDouble()
    val moved = apart atLeast SHARE_MIN_CHANGE * first.of * last.of
    return Figure(
        label = label,
        counts = onDays(first.of, last.of, stretches),
        values = (share(first) to share(last)).takeIf { enough },
        changed = enough && moved &&
            newcombeInterval(first.days, first.of, last.days, last.of).let { it.start > 0.0 || it.endInclusive < 0.0 },
        stretchSources = sources,
        note = null,
    )
}

/**
 * The share of the days answered in each stretch with pain in the head or the neck and shoulders —
 * the pain map's [PainGroup.HEAD] and [PainGroup.NECK_SHOULDERS] together — read as the pain
 * statistics read it ([PainDiary]): a day is answered when it was kept by hand or holds a pain row,
 * "no pain today" included, and a day with nothing on it is left out rather than read as a day
 * without pain. The wound-up figure's gate and threshold: see [shareFigure].
 */
private fun headNeckPainFigure(diary: ChangeDiary, stretches: ChangeStretches): Figure? {
    val rows = diary.entries.filter { it.kind == KIND_PAIN }
    if (rows.isEmpty()) return null
    val pain = PainDiary(diary.entries, diary.zone)
    val areas = PainGroup.HEAD.areas + PainGroup.NECK_SHOULDERS.areas
    fun share(stretch: ClosedRange<LocalDate>) =
        pain.series(areas, stretch).let { DayShare(it.pain.size, it.answered.size) }
    return shareFigure(
        label = res(R.string.report_changes_head_neck_pain),
        shares = share(stretches.first) to share(stretches.last),
        answeredRes = R.plurals.report_days_answered,
        stretches = stretches,
        sources = rows.sourcesIn(stretches),
    )
}

/**
 * The share of the mornings answered in each stretch that were firm or partly, "didn't notice" and a
 * morning without a check-in left out ([morningShares]), with the wound-up figure's gate and
 * threshold: see [shareFigure].
 */
private fun morningFigure(entries: List<JournalEntry>, stretches: ChangeStretches): Figure? {
    val rows = entries.filter { it.kind == KIND_MORNING_CHECK }
    if (rows.isEmpty()) return null
    return shareFigure(
        label = res(R.string.report_changes_morning),
        shares = morningShares(entries, stretches.first).firmOrPartlyShare to
            morningShares(entries, stretches.last).firmOrPartlyShare,
        answeredRes = R.plurals.report_mornings_answered,
        stretches = stretches,
        sources = rows.sourcesIn(stretches),
    )
}

/**
 * Net hours of work a week in each stretch, the mean over its counted weeks ([workWeekHoursBack]) —
 * a week counted when five of its seven days were kept by hand or had a session, so a week nobody
 * timed is not a week off — from [WORK_WEEKS_MIN] counted weeks in each, and changed from
 * [WORK_MIN_SHARE] and [WORK_MIN_HOURS] both: the app's own rule. A session the timer stopped at its
 * cap is in no week, as it is in none of the work section's figures.
 */
private fun workFigure(diary: ChangeDiary, stretches: ChangeStretches): Figure? {
    val sessions = diary.entries.filter { it.kind == KIND_WORK }
    if (sessions.isEmpty()) return null
    val first = workWeekHoursBack(diary.entries, stretches.first, diary.zone)
    val last = workWeekHoursBack(diary.entries, stretches.last, diary.zone)
    fun weeks(count: Int) = ReportText.Quantity(R.plurals.report_counted_weeks, count)
    return figure(
        label = res(R.string.report_changes_work),
        counts = res(R.string.report_changes_both, weeks(first.size), weeks(last.size)),
        stretches = first.takeIf { it.size >= WORK_WEEKS_MIN }?.average() to
            last.takeIf { it.size >= WORK_WEEKS_MIN }?.average(),
        print = { res(R.string.report_changes_per_week, res(R.string.report_hours_value, it.pretty())) },
        moved = { a, b -> changedBy(a, b, WORK_MIN_SHARE, WORK_MIN_HOURS) },
        sources = sessions.sourcesIn(stretches),
    )
}

/**
 * At or past [threshold], allowing for the last bit of a double: a median of 82.0 against 84.46 is a
 * 3% change on paper and 2.99999… in arithmetic, and a figure exactly on a line is over it.
 */
private infix fun Double.atLeast(threshold: Double): Boolean = this >= threshold - THRESHOLD_SLACK

/**
 * Moved from [a] to [b] by [share] of [a] and by [floor] both, and by something at all: a figure that
 * stood at nought in both stretches, which a share of nought would pass, has not changed.
 */
private fun changedBy(a: Double, b: Double, share: Double, floor: Double = 0.0): Boolean {
    val change = abs(b - a)
    return change > THRESHOLD_SLACK && change atLeast a * share && change atLeast floor
}

private const val THRESHOLD_SLACK = 1e-9

/**
 * The mean of the daily mood means, from [ANSWERED_DAYS_MIN] answered days in each stretch, and
 * changed from [MOOD_MIN_CHANGE] points: the app's own figure, set above the 0.3 of a point the
 * insights ask of one day set against another.
 */
private fun moodFigure(entries: List<JournalEntry>, stretches: ChangeStretches): Figure? {
    val daily = moodSeries(entries).byDay
    if (daily.isEmpty()) return null
    val first = daily.filterKeys { it in stretches.first }.values
    val last = daily.filterKeys { it in stretches.last }.values
    fun mean(days: Collection<Double>) = days.takeIf { it.size >= ANSWERED_DAYS_MIN }?.average()
    return figure(
        label = res(R.string.report_mood_average),
        counts = onDays(first.size, last.size, stretches),
        stretches = mean(first) to mean(last),
        print = { res(R.string.report_changes_mood_value, it.pretty()) },
        moved = { a, b -> abs(b - a) atLeast MOOD_MIN_CHANGE },
        sources = entries.filter { row -> (row.moodScore ?: 0) in 1..Mood.FUN.score }.sourcesIn(stretches),
    )
}

/**
 * Partnered acts per four weeks over the days something was written down by hand — never solo ones,
 * which the sexual section keeps apart and which answer a different question.
 *
 * From [BY_HAND_SHARE] of each stretch's days and [ACTS_MIN] acts over both, because a count this
 * small otherwise prints noise; changed where the rate moved by [RATE_RATIO] either way and the exact
 * test for two counts over their days leaves "no change" out — see [poissonRatioExcludesOne]. Both are
 * the app's own rule.
 */
private fun partneredFigure(entries: List<JournalEntry>, byHand: Set<String>, stretches: ChangeStretches): Figure? {
    val acts = entries.filter { it.cycleLog() in INTIMATE_LOGS }
    return actsFigure(res(R.string.report_changes_partnered), acts, byHand, stretches)
}

/**
 * Masturbation per four weeks, kept exactly as the partnered count is — over the days written down by
 * hand with the acts included, from the same floors, changed by the same rule — and, like it, a figure
 * only where the sexual section went with its dates.
 */
private fun soloFigure(entries: List<JournalEntry>, byHand: Set<String>, stretches: ChangeStretches): Figure? =
    actsFigure(res(R.string.report_changes_solo), entries.filter { it.kind == KIND_MASTURBATION }, byHand, stretches)

/** Acts per four weeks over [byHand]: the arithmetic the partnered and the solo figure share. */
private fun actsFigure(
    label: ReportText,
    acts: List<JournalEntry>,
    byHand: Set<String>,
    stretches: ChangeStretches,
): Figure? {
    if (acts.isEmpty()) return null
    val counted = acts.filter { it.day in byHand }
    val d1 = byHand.count { stretches.first.holds(it) }
    val d2 = byHand.count { stretches.last.holds(it) }
    val x1 = counted.count { stretches.first.holds(it.day) }
    val x2 = counted.count { stretches.last.holds(it.day) }
    val enough = d1 >= stretches.first.dayCount * BY_HAND_SHARE &&
        d2 >= stretches.last.dayCount * BY_HAND_SHARE && x1 + x2 >= ACTS_MIN
    fun rate(x: Int, d: Int) = res(R.string.report_changes_per_four_weeks, (x * FOUR_WEEKS / d.toDouble()).pretty())
    val moved = x2.toDouble() * d1 >= RATE_RATIO * x1 * d2 || x1.toDouble() * d2 >= RATE_RATIO * x2 * d1
    return Figure(
        label = label,
        counts = ReportText.Join(
            listOf(onDaysByHand(d1, d2, stretches), ReportText.Quantity(R.plurals.report_changes_acts, x1 + x2)),
        ),
        values = if (enough) rate(x1, d1) to rate(x2, d2) else null,
        changed = enough && moved && poissonRatioExcludesOne(x1, d1, x2, d2),
        stretchSources = counted.sourcesIn(stretches),
    )
}

/**
 * Grams of ethanol a week over the days something was written down by hand, from [BY_HAND_SHARE] of
 * each stretch's days, and changed from [ALCOHOL_MIN_SHARE] and [ALCOHOL_MIN_DRINKS] of the app's
 * standard drinks a week both — the app's own rule.
 *
 * A day written in by hand without a drink on it is a day without one; a day nothing was written on
 * is not counted at all, so a fortnight away from the app is not a fortnight sober — and a change is
 * marked only where it holds over every day of the stretch as well: see [rateFigure].
 */
private fun alcoholFigure(entries: List<JournalEntry>, byHand: Set<String>, stretches: ChangeStretches): Figure? {
    val drinks = entries.filter { it.kind == KIND_ALCOHOL && it.servingG > 0.0 }
    if (drinks.isEmpty()) return null
    return rateFigure(
        label = res(R.string.alcohol_title),
        amounts = drinks.filter { it.day in byHand }.map { it to it.servingG },
        days = byHand,
        stretches = stretches,
        per = WEEK,
        print = { res(R.string.report_changes_per_week, withUnit(it.roundToInt(), res(R.string.unit_grams))) },
        moved = { a, b -> changedBy(a, b, ALCOHOL_MIN_SHARE, ALCOHOL_MIN_DRINKS * STANDARD_DRINK_G) },
        counts = ::onDaysByHand,
    )
}

/**
 * Cigarette equivalents a week — the social section's own unit, grams of tobacco over
 * [CIGARETTE_EQUIVALENT_G] — over the days something was written down by hand, from [BY_HAND_SHARE]
 * of each stretch's days as the alcohol is, and changed from [TOBACCO_MIN_SHARE] and
 * [TOBACCO_MIN_WEEKLY] a week both: the app's own rule. A day written in without a cigarette is a day
 * without one, for the reason a day without a drink is, and a change is marked only where it holds
 * over every day of the stretch as well: see [rateFigure].
 */
private fun tobaccoFigure(entries: List<JournalEntry>, byHand: Set<String>, stretches: ChangeStretches): Figure? {
    val smoked = entries.filter { it.kind == KIND_SMOKING && it.servingG > 0.0 }
    if (smoked.isEmpty()) return null
    return rateFigure(
        label = res(R.string.smoking_title),
        amounts = smoked.filter { it.day in byHand }.map { it to it.servingG / CIGARETTE_EQUIVALENT_G },
        days = byHand,
        stretches = stretches,
        per = WEEK,
        print = { res(R.string.report_changes_per_week, res(R.string.report_cigarette_equivalents, it.pretty())) },
        moved = { a, b -> changedBy(a, b, TOBACCO_MIN_SHARE, TOBACCO_MIN_WEEKLY) },
        counts = ::onDaysByHand,
    )
}

/**
 * Milligrams of caffeine a day over the days it was logged or anything was written down by hand, from
 * [BY_HAND_SHARE] of each stretch's days, and changed from [CAFFEINE_MIN_SHARE] and [CAFFEINE_MIN_MG]
 * both: the app's own rule. As the alcohol's, a change is marked only where it holds over every day
 * of the stretch as well: see [rateFigure].
 *
 * A cup keeps its milligrams as its serving and a meal beside its portion, as the nutrition section
 * reads them; a meal that states none is not a meal with none, so it adds nothing. A day kept by hand
 * with no cup on it is a day without one — the alcohol's rule — and a day a synced meal brought
 * caffeine on is counted as well, since its milligrams are in the sum.
 */
private fun caffeineFigure(entries: List<JournalEntry>, byHand: Set<String>, stretches: ChangeStretches): Figure? {
    val cups = entries.mapNotNull { entry -> caffeineMgOf(entry)?.let { entry to it } }
    if (cups.isEmpty()) return null
    return rateFigure(
        label = res(R.string.caffeine_title),
        amounts = cups,
        days = byHand + cups.map { it.first.day },
        stretches = stretches,
        per = 1,
        print = { res(R.string.report_per_day_rate, withUnit(it.roundToInt(), res(R.string.unit_milligrams))) },
        moved = { a, b -> changedBy(a, b, CAFFEINE_MIN_SHARE, CAFFEINE_MIN_MG) },
        counts = ::onDaysCaffeine,
    )
}

/**
 * An amount over [per] days, counted over [days] — the days an absent row may be read as none — from
 * [BY_HAND_SHARE] of each stretch's days.
 *
 * A day nothing was written on is either a day left out or a day without any, and the diary cannot
 * say which. The rate printed leaves it out; the mark asks that the change hold over every day of
 * the stretch as well. Read the first way alone, a stretch kept more fully turns the same amount
 * into a lower rate — 140 g over 14 days kept is 70 g a week, over 28 it is 35 — and keeping the
 * diary more often would be marked as a change in the amount.
 */
private fun rateFigure(
    label: ReportText,
    amounts: List<Pair<JournalEntry, Double>>,
    days: Set<String>,
    stretches: ChangeStretches,
    per: Int,
    print: (Double) -> ReportText,
    moved: (Double, Double) -> Boolean,
    counts: (Int, Int, ChangeStretches) -> ReportText,
): Figure {
    val d1 = days.count { stretches.first.holds(it) }
    val d2 = days.count { stretches.last.holds(it) }
    fun total(stretch: ClosedRange<LocalDate>): Double =
        amounts.filter { (row, _) -> stretch.holds(row.day) }.sumOf { it.second }
    fun rate(stretch: ClosedRange<LocalDate>, over: Int): Double? = over
        .takeIf { it > 0 && it >= stretch.dayCount * BY_HAND_SHARE }
        ?.let { total(stretch) * per / it }
    fun everyDay(stretch: ClosedRange<LocalDate>): Double = total(stretch) * per / stretch.dayCount
    return figure(
        label = label,
        counts = counts(d1, d2, stretches),
        stretches = rate(stretches.first, d1) to rate(stretches.last, d2),
        print = print,
        moved = { a, b -> moved(a, b) && moved(everyDay(stretches.first), everyDay(stretches.last)) },
        sources = amounts.map { it.first }.sourcesIn(stretches),
    )
}

/**
 * Food energy a day over the days holding [LAB_DIET_MIN_MEALS_PER_DAY] entries — the nutrition
 * section's own rule for a day somebody finished logging — from [FOOD_DAYS_MIN] such days and
 * [FOOD_DAYS_SHARE] of the stretch, and changed from [FOOD_MIN_SHARE] and [FOOD_MIN_KCAL] both.
 *
 * A diary misses food, and not evenly: Foster 2019 (J Nutr Sci; Intake24 against doubly labelled
 * water) found energy under-reported by 22–25% on average, with the 95% limits for a single recall
 * running from 73% under to 68% over. One person's own habit of missing things cancels out between
 * the stretches; how tired of the diary they got does not, which is why a smaller change is not
 * marked, and why the row prints how many entries a counted day held in each stretch beside it.
 */
private fun foodFigure(entries: List<JournalEntry>, stretches: ChangeStretches): Figure? {
    val meals = entries.filter { it.kind == KIND_EAT }
    if (meals.isEmpty()) return null
    fun fullDays(stretch: ClosedRange<LocalDate>) = meals.filter { stretch.holds(it.day) }
        .groupBy(JournalEntry::day).values.filter { it.count(JournalEntry::countsAsMeal) >= LAB_DIET_MIN_MEALS_PER_DAY }
    fun mean(days: List<List<JournalEntry>>, stretch: ClosedRange<LocalDate>): Double? = days
        .takeIf { it.size >= FOOD_DAYS_MIN && it.size >= stretch.dayCount * FOOD_DAYS_SHARE }
        ?.map { day -> day.sumOf(JournalEntry::calories) }?.average()
    fun entriesADay(days: List<List<JournalEntry>>) = days.map { it.size }.average().pretty()
    val first = fullDays(stretches.first)
    val last = fullDays(stretches.last)
    val counts = onDays(first.size, last.size, stretches)
    // Only the row of a figure both stretches describe prints it; a stretch without a counted day has no mean.
    val perDay = if (first.isEmpty() || last.isEmpty()) {
        null
    } else {
        res(
            R.string.report_changes_entries_a_day,
            res(R.string.report_changes_from_to, entriesADay(first), entriesADay(last)),
        )
    }
    return figure(
        label = ReportText.Quantity(R.plurals.report_changes_food, LAB_DIET_MIN_MEALS_PER_DAY),
        counts = counts,
        stretches = mean(first, stretches.first) to mean(last, stretches.last),
        print = { res(R.string.report_per_day_rate, withUnit(it.roundToInt(), res(R.string.unit_kcal))) },
        moved = { a, b -> changedBy(a, b, FOOD_MIN_SHARE, FOOD_MIN_KCAL) },
        sources = first.flatten().map(JournalEntry::measurementSource) to
            last.flatten().map(JournalEntry::measurementSource),
        note = ReportText.Join(listOfNotNull(counts, perDay)),
    )
}

/**
 * Sugar, grams a day over the full days on which every entry carries it — the nutrition section's own
 * rule, since a blank is "never entered" and one blank turns a day's sum into part of a day — from
 * [NUTRIENT_DAYS_MIN] such days, and changed from [SUGAR_MIN_SHARE] and [SUGAR_MIN_G] both: the app's
 * own rule.
 */
private fun sugarFigure(diary: ChangeDiary, stretches: ChangeStretches): Figure? {
    val byDay = carriedByDay(diary.meals, JournalEntry::sugarG)
    if (byDay.isEmpty()) return null
    return dailyFigure(
        label = res(R.string.editor_sugar),
        byDay = byDay,
        stretches = stretches,
        minDays = NUTRIENT_DAYS_MIN,
        print = { res(R.string.report_per_day_rate, withUnit(it.pretty(), res(R.string.unit_grams))) },
        moved = { a, b -> changedBy(a, b, SUGAR_MIN_SHARE, SUGAR_MIN_G) },
        sources = mealSources(diary.meals, byDay.keys, stretches),
        minShare = diary.foodShare,
    )
}

/**
 * Sodium, milligrams a day over the full days on which every entry carries it, from
 * [NUTRIENT_DAYS_MIN] such days, and changed from [SODIUM_MIN_SHARE] and [SODIUM_MIN_MG] both: the
 * app's own rule. What is logged is the food's sodium; salt added at the table is not in it.
 */
private fun sodiumFigure(diary: ChangeDiary, stretches: ChangeStretches): Figure? {
    val byDay = carriedByDay(diary.meals, JournalEntry::sodiumMg)
    if (byDay.isEmpty()) return null
    return dailyFigure(
        label = res(R.string.editor_sodium),
        byDay = byDay,
        stretches = stretches,
        minDays = NUTRIENT_DAYS_MIN,
        print = { res(R.string.report_per_day_rate, withUnit(it.roundToInt(), res(R.string.unit_milligrams))) },
        moved = { a, b -> changedBy(a, b, SODIUM_MIN_SHARE, SODIUM_MIN_MG) },
        sources = mealSources(diary.meals, byDay.keys, stretches),
        minShare = diary.foodShare,
    )
}

/**
 * Saturated fat as a share of the day's food energy — the form the guidance is written in — over the
 * full days on which every entry carries it, from [NUTRIENT_DAYS_MIN] such days, and changed from
 * [SATURATED_MIN_SHARE] of itself and [SATURATED_MIN_POINTS] both: the app's own rule.
 */
private fun saturatedFatFigure(diary: ChangeDiary, stretches: ChangeStretches): Figure? {
    val byDay = fullDays(diary.meals).mapNotNull { (day, meals) ->
        val fat = meals.mapNotNull(JournalEntry::saturatedFatG).takeIf { it.size == meals.size }?.sum()
        val kcal = meals.sumOf(JournalEntry::calories)
        fat?.takeIf { kcal > 0.0 }?.let { day to it * FAT_KCAL_PER_G / kcal * PERCENT }
    }.toMap()
    if (byDay.isEmpty()) return null
    return dailyFigure(
        label = res(R.string.labs_diet_saturated),
        byDay = byDay,
        stretches = stretches,
        minDays = NUTRIENT_DAYS_MIN,
        print = { res(R.string.report_changes_energy_share, it.pretty()) },
        moved = { a, b -> changedBy(a, b, SATURATED_MIN_SHARE, SATURATED_MIN_POINTS) },
        sources = mealSources(diary.meals, byDay.keys, stretches),
        minShare = diary.foodShare,
    )
}

/**
 * The days holding [LAB_DIET_MIN_MEALS_PER_DAY] meals or more, each with its food — a cup with milk
 * among it but not among the meals counted ([countsAsMeal]).
 */
private fun fullDays(meals: List<JournalEntry>): Map<LocalDate, List<JournalEntry>> = meals
    .groupBy(JournalEntry::day)
    .filterValues { it.count(JournalEntry::countsAsMeal) >= LAB_DIET_MIN_MEALS_PER_DAY }
    .mapNotNull { (day, ofDay) -> day.toDayOrNull()?.let { it to ofDay } }
    .toMap()

/** A nutrient's total on each full day on which every meal carries it. */
private fun carriedByDay(meals: List<JournalEntry>, of: (JournalEntry) -> Double?): Map<LocalDate, Double> =
    fullDays(meals).mapNotNull { (day, ofDay) ->
        ofDay.mapNotNull(of).takeIf { it.size == ofDay.size }?.let { day to it.sum() }
    }.toMap()

/** Who wrote the meals of [days] that fall in the stretches. */
private fun mealSources(meals: List<JournalEntry>, days: Set<LocalDate>, stretches: ChangeStretches): StretchSources =
    meals.filter { meal -> meal.day.toDayOrNull()?.let { it in days } == true }.sourcesIn(stretches)

/** Who wrote the rows of each stretch; the middle feeds nothing. */
private fun List<JournalEntry>.sourcesIn(stretches: ChangeStretches): StretchSources =
    filter { stretches.first.holds(it.day) }.map(JournalEntry::measurementSource) to
        filter { stretches.last.holds(it.day) }.map(JournalEntry::measurementSource)

/**
 * Water a day over the days a glass was logged, from [ANSWERED_DAYS_MIN] such days: described, and
 * never marked or paired. What is logged is not all that is drunk, some people are told to drink
 * less, and a figure beside a kidney result would read as a finding the app has no business making.
 */
private fun waterFigure(entries: List<JournalEntry>, stretches: ChangeStretches): Figure? {
    val water = entries.filter { it.kind == KIND_WATER }
    if (water.isEmpty()) return null
    fun byDay(stretch: ClosedRange<LocalDate>) = water.filter { stretch.holds(it.day) }.groupBy(JournalEntry::day)
    fun mean(days: Map<String, List<JournalEntry>>): Double? = days.takeIf { it.size >= ANSWERED_DAYS_MIN }
        ?.values?.map { day -> day.sumOf(JournalEntry::servingG) }?.average()
    val first = byDay(stretches.first)
    val last = byDay(stretches.last)
    return figure(
        label = res(R.string.water_title),
        counts = onDays(first.size, last.size, stretches),
        stretches = mean(first) to mean(last),
        print = { res(R.string.report_per_day_rate, withUnit(it.roundToInt(), res(R.string.unit_ml))) },
        moved = { _, _ -> false },
        sources = water.sourcesIn(stretches),
    )
}

/** The stretches as dates, and — where the period is long enough to hold seasons — which ones. */
private fun stretchRows(stretches: ChangeStretches): List<ReportRow> = listOfNotNull(
    ReportRow(
        label = res(R.string.report_changes_stretches),
        value = res(
            R.string.report_changes_stretches_value,
            ReportText.Day(stretches.first.start),
            ReportText.Day(stretches.first.endInclusive),
            ReportText.Day(stretches.last.start),
            ReportText.Day(stretches.last.endInclusive),
        ),
        note = res(R.string.report_changes_counted),
    ),
    when {
        stretches.yearApart -> R.string.report_changes_seasons_same
        stretches.seasonsDiffer -> R.string.report_changes_seasons_differ
        else -> null
    }?.let { ReportRow(res(R.string.report_changes_seasons), res(it), detail = true) },
)

/**
 * One row per figure both stretches could describe, with a change of source between them beside it,
 * then what "changed" means where anything was marked, then the figures that could not be compared
 * with the counts that stopped them.
 */
private fun figureRows(figures: Collection<Figure>): List<ReportRow> {
    val compared = figures.mapNotNull { figure -> figure.fromTo?.let { figure to it } }
    val left = figures.filter { it.values == null }
    return buildList {
        compared.forEach { (figure, fromTo) ->
            val marks = listOfNotNull(
                figure.note,
                figure.sourceChange,
                res(R.string.report_changes_changed).takeIf { figure.changed },
            )
            add(ReportRow(figure.label, fromTo, marks.takeIf { it.isNotEmpty() }?.let { ReportText.Join(it) }))
        }
        if (compared.any { (figure, _) -> figure.changed }) {
            add(ReportRow(res(R.string.report_changes_changed_label), res(R.string.report_changes_changed_means)))
        }
        if (left.isNotEmpty()) {
            add(
                ReportRow(
                    label = res(R.string.report_changes_not_compared),
                    value = ReportText.Join(left.map { res(R.string.report_changes_item, it.label, it.counts) }, "; "),
                    note = res(R.string.report_changes_too_few),
                ),
            )
        }
    }
}

/** The catalogue's pairs whose two figures both changed, in the catalogue's order. */
private fun pairsShown(figures: Map<ChangeDomain, Figure>): List<ComparisonPair> = ComparisonPair.entries.filter {
    figures[it.exposure]?.changed == true && figures[it.outcome]?.changed == true
}

/**
 * The count of pairs looked at — printed on every sheet, so seventeen looked at and one shown is never
 * read as one looked for — and one row per pair shown.
 *
 * Where a figure is named in more than one pair shown, the count says so: one change in the alcohol
 * can fill five pairs, and "Shown: 5" would otherwise read as five things that moved.
 */
private fun pairRows(figures: Map<ChangeDomain, Figure>, shown: List<ComparisonPair>): List<ReportRow> {
    val checked = res(
        R.string.report_changes_checked,
        ReportText.Quantity(R.plurals.report_changes_pair_count, ComparisonPair.entries.size),
        shown.size,
    )
    val count = ReportRow(res(R.string.report_changes_pairs), checked, sharedNote(shown.flatMap { it.named(figures) }))
    return listOf(count) + shown.map { pairRow(it, figures) }
}

/** The note under a count of rows that name one figure more than once between them. */
private fun sharedNote(named: List<ChangeDomain>): ReportText? =
    res(R.string.report_changes_pairs_shared).takeIf { named.size > named.distinct().size }

/** The figures a pair's row names: its two, and its third where that changed as well. */
private fun ComparisonPair.named(figures: Map<ChangeDomain, Figure>): List<ChangeDomain> =
    listOfNotNull(exposure, outcome, also?.takeIf { figures[it]?.changed == true && alsoSentenceRes != null })

/**
 * "Alcohol 11 g a week → 25 g a week; in the same months, average mood 3.4 of 5 → 2.7 of 5.", with the
 * days behind each figure and the words that say what the row is: two changes in the same months.
 */
private fun pairRow(pair: ComparisonPair, figures: Map<ChangeDomain, Figure>): ReportRow {
    val domains = pair.named(figures)
    val named = domains.mapNotNull(figures::get)
    val sentence = pair.alsoSentenceRes?.takeIf { pair.also in domains } ?: pair.sentenceRes
    return ReportRow(
        label = res(R.string.report_changes_pair),
        // Every figure named has changed, and a changed figure always has both of its values.
        value = ReportText.Res(sentence, named.mapNotNull(Figure::fromTo)),
        note = ReportText.Join(
            named.map { res(R.string.report_changes_item, it.label, it.counts) } +
                res(R.string.report_changes_same_months),
        ),
        detail = true,
    )
}

/** What the blood-result comparisons add to the section: its rows, the count row, and what they read. */
private class BloodComparisons(
    val rows: List<ReportRow> = emptyList(),
    val checked: ReportRow? = null,
    val shown: List<LabComparison> = emptyList(),
    val sources: List<String?> = emptyList(),
)

/**
 * The newest two results of a marker the blood-test section prints, with the figures it prints them
 * at — and so where they differ as printed, which is all "differ" means here.
 */
private class BloodDraws(val marker: LabMarker, val earlier: LocalDate, val later: LocalDate, val printed: ReportText) {
    val windowDays: Int get() = LAB_DIET_WINDOWS[marker]?.windowDays ?: BLOOD_WINDOW_DAYS

    /** The window before each draw, the day of the draw included, in the shape the figures read. */
    val windows: ChangeStretches
        get() = ChangeStretches(
            earlier.minusDays(windowDays - 1L)..earlier,
            later.minusDays(windowDays - 1L)..later,
            yearApart = false,
        )

    /**
     * Whether the window before the later draw opens after the earlier draw. Two GGT results twenty
     * days apart share thirty-six of their fifty-six days, and a change read across them would be read
     * largely off the same days twice.
     */
    val windowsApart: Boolean get() = windows.last.start > earlier
}

/**
 * The catalogue of [LabComparison]s over the markers the blood-test section prints two results of
 * that differ: the diary figure before each draw, compared where both windows can be described and
 * the figure moved; refused, with the draw it could not be described before, where either cannot.
 *
 * Only beside a marker whose newer result falls inside the period, as the blood-test context keeps
 * it: two results that both predate the period would put on the sheet the diary of months before the
 * stretch the person chose to share. Beside one that does, the window before the earlier draw may
 * reach back past the period's start — that result is on the sheet, and so is what came before it —
 * and it reads only sections the sheet prints, the blood tests among them.
 */
private fun bloodComparisons(
    sources: ReportSources,
    request: ReportRequest,
    printed: Set<ReportPart>,
    zone: ZoneId,
): BloodComparisons {
    if (ReportPart.LABS !in printed) return BloodComparisons()
    val profile = sources.profile
    val imperial = request.imperial(profile)
    val reach = sources.entries.filter { it.day <= request.to.toString() }
    val history = labHistory(reach, profile)
    val candidates = LabComparison.entries.filter { it.exposure.part in printed }.flatMap { comparison ->
        comparison.markers.mapNotNull { draws(history[it].orEmpty(), profile.sex, imperial, request.from) }
            .map { comparison to it }
    }
    if (candidates.isEmpty()) return BloodComparisons()
    val diary = ChangeDiary(
        entries = reach,
        days = sources.days.filter { it.day <= request.to.toString() },
        profile = profile,
        imperial = imperial,
        zone = zone,
        foodShare = LAB_DIET_MIN_COVERAGE,
    )
    val read = candidates.mapNotNull { (comparison, draws) ->
        FIGURES.getValue(comparison.exposure)(diary, draws.windows)?.let { Triple(comparison, draws, it) }
    }
    val compared = read.filter { (_, _, figure) -> figure.changed }
    val refused = read.filter { (_, _, figure) -> figure.values == null }
    val checked = res(
        R.string.report_changes_checked,
        ReportText.Quantity(R.plurals.report_changes_pair_count, LabComparison.entries.sumOf { it.markers.size }),
        compared.size,
    )
    // GGT and ALT beside one change in the alcohol are two rows and one change.
    val shared = sharedNote(compared.map { it.first.exposure })
    return BloodComparisons(
        rows = compared.map { (_, draws, figure) -> bloodRow(draws, figure) } +
            refusedRows(refused),
        checked = ReportRow(res(R.string.report_changes_blood), checked, shared),
        shown = compared.map { it.first }.distinct(),
        sources = (compared + refused).flatMap { it.third.sources },
    )
}

/**
 * The marker's two newest printed results, where they differ as the blood-test section prints them,
 * the newer of them was drawn on or after [from] — the start of the period — and the windows before
 * them share no day ([BloodDraws.windowsApart]).
 */
private fun draws(history: List<LabReading>, sex: String, imperial: Boolean, from: LocalDate): BloodDraws? {
    // The newest result and the ones under it, exactly as the blood-test section takes them.
    val shown = history.filter(LabReading::fromPanel).asReversed().take(LAB_PRINTED_RESULTS)
    val laterDay = shown.getOrNull(0)?.day?.toDayOrNull()
    val earlierDay = shown.getOrNull(1)?.day?.toDayOrNull()
    if (laterDay == null || earlierDay == null || laterDay < from) return null
    val marker = shown[0].marker
    val places = marker.printPlaces(shown, sex, imperial)
    val later = marker.printed(shown[0], sex, imperial, places)
    val earlier = marker.printed(shown[1], sex, imperial, places)
    val unit = res(marker.unitLabelRes(imperial))
    val fromTo = res(R.string.report_changes_from_to, withUnit(earlier, unit), withUnit(later, unit))
    // Apart also rules out two results on one day, whose windows are the same days.
    return BloodDraws(marker, earlierDay, laterDay, fromTo).takeIf { earlier != later && it.windowsApart }
}

/** "Alcohol in the 56 days before each result". */
private fun windowLabel(draws: BloodDraws, figure: Figure): ReportText =
    res(R.string.report_changes_blood_window, figure.label, ReportText.Quantity(R.plurals.count_days, draws.windowDays))

/**
 * "5 g a week → 30 g a week; GGT 22 U/L → 41 U/L (12 Mar 2024 → 20 Aug 2026)", with the days behind it
 * and a change of source between the two windows.
 */
private fun bloodRow(draws: BloodDraws, figure: Figure): ReportRow = ReportRow(
    label = windowLabel(draws, figure),
    // A changed figure always has both of its values, so its earlier → later is never missing here.
    value = ReportText.Res(
        R.string.report_changes_blood_value,
        listOfNotNull(
            figure.fromTo,
            res(draws.marker.titleRes),
            draws.printed,
            ReportText.Day(draws.earlier),
            ReportText.Day(draws.later),
        ),
    ),
    note = ReportText.Join(listOfNotNull(figure.counts, figure.sourceChange)),
)

/**
 * One line for every pair the diary could not describe: the results it does not reach back to, and
 * which figures and markers were left uncompared for it. Nine lines saying "before the March 2024
 * result: not recorded", one per figure and marker, would bury the one comparison that could be made.
 */
private fun refusedRows(refused: List<Triple<LabComparison, BloodDraws, Figure>>): List<ReportRow> {
    if (refused.isEmpty()) return emptyList()
    val missing = refused.flatMap { (_, draws, figure) ->
        listOfNotNull(
            draws.earlier.takeUnless { figure.described.first },
            draws.later.takeUnless { figure.described.second },
        )
    }.distinct().sorted()
    return listOf(
        ReportRow(
            label = res(R.string.report_changes_not_compared),
            value = ReportText.Join(
                missing.map {
                    res(R.string.report_changes_blood_not_recorded, ReportText.Day(it))
                },
                "; ",
            ),
            note = ReportText.Join(
                refused.map { it.third.label }.distinct() + refused.map { res(it.second.marker.titleRes) }.distinct(),
            ),
        ),
    )
}

/**
 * What else began or ended from the start of the first stretch to the end of the last: the courses
 * started or ended then, where the medicines are printed, and the conditions written down as starting
 * then, as they were written, where the problem list is. It is the first thing a doctor looks at
 * beside a change, and the sheet sets it there without saying anything about it.
 *
 * Inside the stretches as well as between them. A course begun in the first week of the last stretch
 * was taken through nearly all of it, and one begun late in the first through little of it; either
 * can be what moved a figure, and a row that read only the days between would name neither.
 */
private fun betweenRow(sources: ReportSources, printed: Set<ReportPart>, stretches: ChangeStretches): ReportRow? {
    val span = stretches.first.start..stretches.last.endInclusive
    val courses = sources.medications.takeIf { ReportPart.MEDICATIONS in printed }.orEmpty()
    val conditions = sources.history.ofHistoryKind(HistoryKind.CONDITION)
        .takeIf { ReportPart.PROBLEMS in printed }.orEmpty()
    fun named(items: List<Pair<String, LocalDate?>>): List<ReportText> = items
        .mapNotNull { (name, day) -> day?.takeIf { it in span }?.let { name to it } }
        .sortedBy { it.second }
        .map { (name, day) -> res(R.string.report_changes_item, ReportText.Plain(name), ReportText.Day(day)) }
    val parts = listOfNotNull(
        listed(R.string.report_changes_started, named(courses.map { it.name to it.startDay.asHistoryDay() })),
        listed(R.string.report_changes_ended, named(courses.map { it.name to it.endDay?.asHistoryDay() })),
        listed(R.string.report_changes_conditions, named(conditions.map { it.title to it.startDay.asHistoryDay() })),
    )
    return parts.takeIf { it.isNotEmpty() }
        ?.let { ReportRow(res(R.string.report_changes_between), ReportText.Join(it, "; ")) }
}

private fun listed(id: Int, items: List<ReportText>): ReportText? =
    items.takeIf { it.isNotEmpty() }?.let { res(id, ReportText.Join(it, ", ")) }

/**
 * Whether the exact 95% interval for the ratio of two rates — [x1] events over [t1] days against [x2]
 * over [t2] — leaves out 1.
 *
 * Given the total, the second count is binomial with the share of the days the second stretch holds,
 * so the exact interval for the ratio is the Clopper–Pearson interval for that share turned into a
 * ratio, and it leaves out 1 exactly where one tail of that binomial at the no-change share is under
 * 2.5%. No quantile is needed, only the two tails.
 */
internal fun poissonRatioExcludesOne(x1: Int, t1: Int, x2: Int, t2: Int): Boolean {
    val n = x1 + x2
    val share = t2.toDouble() / (t1 + t2)
    val logFactorial = DoubleArray(n + 1)
    for (i in 1..n) logFactorial[i] = logFactorial[i - 1] + ln(i.toDouble())
    val pmf = DoubleArray(n + 1) { k ->
        exp(logFactorial[n] - logFactorial[k] - logFactorial[n - k] + k * ln(share) + (n - k) * ln(1 - share))
    }
    val atLeast = (x2..n).sumOf { pmf[it] }
    val atMost = (0..x2).sumOf { pmf[it] }
    return minOf(atLeast, atMost) < TAIL_95
}

/** "on 25 of 31 days and 27 of 31 days". */
private fun onDays(first: Int, last: Int, stretches: ChangeStretches): ReportText =
    res(R.string.report_changes_on_days, daysOf(first, stretches.first), daysOf(last, stretches.last))

/** The same, for figures counted over the days something was written down by hand. */
private fun onDaysByHand(first: Int, last: Int, stretches: ChangeStretches): ReportText =
    res(R.string.report_changes_on_days_by_hand, daysOf(first, stretches.first), daysOf(last, stretches.last))

/** The same, for caffeine, counted over the days it was logged as well. */
private fun onDaysCaffeine(first: Int, last: Int, stretches: ChangeStretches): ReportText =
    res(R.string.report_changes_on_days_caffeine, daysOf(first, stretches.first), daysOf(last, stretches.last))

/** "8 readings over 5 days and 11 readings over 7 days". */
private fun readingCounts(first: List<VitalReading>, last: List<VitalReading>): ReportText =
    res(R.string.report_changes_both, readings(first), readings(last))

/** "6 readings over 2 days": how many, and on how many days. */
private fun readings(of: List<VitalReading>): ReportText {
    val count = ReportText.Quantity(R.plurals.count_readings, of.size)
    val days = ReportText.Quantity(R.plurals.count_days, of.readingDays())
    return if (of.isEmpty()) count else res(R.string.report_changes_over, count, days)
}

private fun daysOf(count: Int, stretch: ClosedRange<LocalDate>): ReportText =
    res(R.string.report_count_of, count, ReportText.Quantity(R.plurals.count_days, stretch.dayCount))

private val ClosedRange<LocalDate>.dayCount: Int get() = ChronoUnit.DAYS.between(start, endInclusive).toInt() + 1

/** Whether a stored ISO day falls in the range; compared as text, which ISO days sort as. */
private fun ClosedRange<LocalDate>.holds(day: String): Boolean =
    day >= start.toString() && day <= endInclusive.toString()

private fun String.toDayOrNull(): LocalDate? = toLocalDateOrNull()

private fun res(id: Int, vararg args: Any): ReportText = ReportText.Res(id, args.toList())

private fun withUnit(figure: Any, unit: ReportText): ReportText = res(R.string.report_value_unit, figure, unit)

/** A third of a year, rounded up: a year's thirds, and the stretch the 24-month sheet sets a year apart. */
private const val YEAR_STRETCH_DAYS = 122

private const val WEIGH_INS_MIN = 3
private const val WEIGH_IN_SPREAD_DAYS = 14
private const val WEIGHT_MIN_SHARE = 0.03
private const val WEIGHT_MIN_KG = 2.0

/** Six readings of a vital in each stretch: the app's own floor for a stretch's mean. */
private const val READINGS_MIN = 6

/**
 * The days those readings must fall on, the app's own floor as well: six taken in one evening are one
 * evening's pressure, not a stretch's. Motivated by the ESH position paper on home monitoring (Parati
 * 2021, J Hypertens 39:1742, Box 15), which asks for seven days of readings and not fewer than three;
 * the figure is still never called a home average in its sense, since nothing here asks for its
 * morning and evening readings.
 */
private const val READING_DAYS_MIN = 3
private const val SYSTOLIC_MIN_CHANGE = 5
private const val DIASTOLIC_MIN_CHANGE = 4
private const val PULSE_MIN_CHANGE = 5

private const val STEP_DAYS_MIN = 21
private const val STEP_DAYS_SHARE = 0.6
private const val STEPS_MIN_SHARE = 0.2
private const val STEPS_MIN_CHANGE = 1_000.0

/**
 * Two weeks of nights, the app's own floor; half an hour and 8% of the earlier night, its own
 * threshold — which the work statistics set between two groups of days as it is.
 */
private const val NIGHTS_MIN = 14
internal const val SLEEP_MIN_CHANGE_MINUTES = 30.0
internal const val SLEEP_MIN_SHARE = 0.08

/**
 * Two weeks of answered days: the app's own floor for describing a stretch. The mood's half a point
 * is also what the work statistics ask between two groups of days.
 */
private const val ANSWERED_DAYS_MIN = 14
private const val SHARE_MIN_CHANGE = SHEET_SHARE_POINTS
internal const val MOOD_MIN_CHANGE = 0.5

/**
 * Four counted weeks in each stretch, and a change of a fifth and of five hours a week both: the app's
 * own rule. The fifth keeps the ordinary drift of a long week from passing; the five hours keep a
 * fifth of a short week from passing on its own.
 */
private const val WORK_WEEKS_MIN = 4
private const val WORK_MIN_SHARE = 0.2
private const val WORK_MIN_HOURS = 5.0

private const val BY_HAND_SHARE = 0.5
private const val ACTS_MIN = 10

/** The same factor either way: 1.5 times the rate, or 1/1.5 ≈ 0.67 of it. */
private const val RATE_RATIO = 1.5
private const val FOUR_WEEKS = 28
private const val WEEK = 7

private const val ALCOHOL_MIN_SHARE = 0.5
private const val ALCOHOL_MIN_DRINKS = 2

private const val TOBACCO_MIN_SHARE = 0.5
private const val TOBACCO_MIN_WEEKLY = 7.0

private const val CAFFEINE_MIN_SHARE = 0.5
private const val CAFFEINE_MIN_MG = 100.0

private const val FOOD_DAYS_MIN = 14
private const val FOOD_DAYS_SHARE = 0.25
private const val FOOD_MIN_SHARE = 0.2

/** Two weeks of full days carrying an optional nutrient: the app's own floor. */
private const val NUTRIENT_DAYS_MIN = 14
private const val SUGAR_MIN_SHARE = 0.2
private const val SATURATED_MIN_SHARE = 0.2
private const val SODIUM_MIN_SHARE = 0.2
private const val SODIUM_MIN_MG = 500.0

/**
 * Floors under the fifth for three food figures, as sodium has its milligrams: a hundred
 * kilocalories, a teaspoon of sugar and a point of the day's energy from saturated fat, all the
 * app's own. From a stretch at nought a fifth is nothing, and without a floor any change at all
 * would be marked — "0 g a day → 0.0 g a day · changed".
 */
private const val FOOD_MIN_KCAL = 100.0
private const val SUGAR_MIN_G = 4.0
private const val SATURATED_MIN_POINTS = 1.0

private const val FAT_KCAL_PER_G = 9.0
private const val PERCENT = 100.0

/**
 * The window before a draw of a marker [LAB_DIET_WINDOWS] gives none: eight weeks, the longest of its
 * windows but the three months of HbA1c — GGT's, the one it gives a liver enzyme.
 */
private const val BLOOD_WINDOW_DAYS = 56

/** The newest result and the three under it: what the blood-test section prints of a marker. */
private const val LAB_PRINTED_RESULTS = PRINTED_LAB_RESULTS

/** The tail each side of a 95% interval leaves out. */
private const val TAIL_95 = 0.025
