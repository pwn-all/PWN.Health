package com.pwnall.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

// The arithmetic behind "these two things in your record went together", and the several ways it
// refuses to say so.
//
// Everything here is written against one person's own daily record, which is a harder problem than
// it looks. Three findings from the literature shape the whole file:
//
//  * A reliable within-person correlation needs more than a hundred paired observations (Wright,
//    Scharf & Zimmermann 2025, Psychological Assessment 37(10):466-478). A reliable within-person
//    *mean* needs ten to thirty. Most apps quote the second figure and compute the first.
//  * Fewer than half of people have any detectable day-to-day association between activity and
//    affect at all — 45.3% in van Zwieten et al. 2026 (IJMPR 35(2):e70085, n=300). "No clear
//    pattern" is therefore the expected answer, not a failure, and the UI has to treat it as one.
//  * Testing many pairs against one outcome manufactures findings. Zebenholzer et al. 2011
//    (Cephalalgia 31(4):391-400) tested eleven weather variables against migraine in 238 people:
//    four results were nominally significant and none survived correction. Hence a fixed, named
//    list of hypotheses rather than a sweep — see [InsightHypothesis].
//
// No p-value reaches a reader. The headline a person reads is a difference in plain units with
// a range around it, following Amrhein, Greenland & McShane 2019 (Nature 567:305-307) on retiring
// significance, and van der Bles et al. 2020 (PNAS 117:7672-7683, five experiments, n=5,780), who
// found a numeric range costs little trust while verbal hedging costs more and says less. The one
// p-value there is — [bootstrapChance] — exists because the correction across the list of
// hypotheses is defined on p-values and on nothing else, and it stays inside that correction.

/** One quantity, one number a day. A day that is absent is unknown, never zero. */
data class DailySeries(val byDay: Map<LocalDate, Double>)

/** Which day of the exposure is lined up against which day of the outcome. */
enum class InsightLag {
    /** The same day. The commonest shape there is: 32.1% of people against 17.2% lagged. */
    SAME_DAY,

    /** Exposure yesterday against outcome today. */
    EXPOSURE_YESTERDAY,

    /**
     * Outcome yesterday against exposure today — the reverse-causality control.
     *
     * Computed and shown, never reported as a finding of its own. A direction is put into words
     * only when the forward range excludes zero and this one does not; otherwise the sentence is
     * symmetric, because that is all the data can support.
     */
    OUTCOME_YESTERDAY,
}

/** How much of a record stands behind a link, which decides the verb the screen is allowed. */
enum class InsightConfidence { NOT_ENOUGH, EARLY, STEADY }

/**
 * The headline: what the outcome averaged on the days the exposure ran high, against the days it
 * ran low, with a range around the difference.
 *
 * In the outcome's own units — mood points, hours, whatever it is — rather than as a coefficient.
 * A correlation coefficient is read as a fact by everybody who is not a statistician, and at these
 * sample sizes it is not one.
 *
 * [highMean], [lowMean] and the range are of the series that was measured, which for a mood is what
 * is left of a rating once the drift and the weekday are taken out: a figure near nought, as often
 * below it as above, and no rating anybody gave. [highShown] and [lowShown] are the same two groups
 * of days read in the figure the person wrote down, and the only pair a sentence may print as "out
 * of 5". Where nothing was taken out of the outcome the two pairs are the same numbers; where
 * something was, the difference is the measured one and need not be the plain subtraction of the
 * two levels beside it.
 *
 * [chance] is the same resamples read as a p-value — see [bootstrapChance] — for the correction
 * across hypotheses to rank by. It is never printed.
 */
data class InsightContrast(
    val highDays: Int,
    val lowDays: Int,
    val highMean: Double,
    val lowMean: Double,
    val lo: Double,
    val hi: Double,
    val highShown: Double,
    val lowShown: Double,
    val chance: Double,
) {
    val difference: Double get() = highMean - lowMean

    /** Whether the whole range sits on one side of zero. */
    val excludesZero: Boolean get() = (lo > 0.0 && hi > 0.0) || (lo < 0.0 && hi < 0.0)
}

/**
 * Everything a link has to clear before it is shown at all.
 *
 * The figures are defended one by one in [INSIGHT_GATES]. They are a parameter rather than
 * constants so the tests can build a link out of a handful of days without the file's own floor
 * getting in the way.
 */
data class InsightGates(
    val minPairedDays: Int,
    val minCoverage: Double,
    val minDistinctOutcomes: Int,
    val maxOutcomeShare: Double,
    val minGroupDays: Int,
    val minEffectiveN: Int,
    val minEffect: Double,
    val steadyDays: Int,
)

/**
 * The floor, and why each figure is where it is.
 *
 * - **90 paired days.** Between Wright 2025's hundred-plus and a number somebody can actually
 *   reach. Below it nothing is shown but a counter.
 * - **60% coverage.** Missing mood is not missing at random — people log less on their worst days
 *   — so a window that is mostly gaps describes the days somebody felt like logging.
 * - **Three distinct outcome values, none on more than 70% of days.** Kendall's tau-b is unstable
 *   under heavy ties, and a scale somebody answers "3" to nine times out of ten carries no signal
 *   whatever the arithmetic says.
 * - **15 days in each group.** For a yes/no exposure the groups are the days with it and the days
 *   without — see [highAndLow] — so fewer than fifteen days with it is refused. Far stricter than
 *   the three-and-three some trackers use, which at these effect sizes is a noise generator.
 * - **45 effective days.** See [effectiveN] — a display heuristic, used only as a gate.
 * - **0.3 of a point.** Smaller than this is not worth a card even when the range excludes zero.
 */
val INSIGHT_GATES = InsightGates(
    minPairedDays = 90,
    minCoverage = 0.6,
    minDistinctOutcomes = 3,
    maxOutcomeShare = 0.7,
    minGroupDays = 15,
    minEffectiveN = 45,
    minEffect = 0.3,
    steadyDays = 180,
)

/** One evaluated hypothesis at one lag. */
data class InsightLink(
    val lag: InsightLag,
    /** Kendall's tau-b. Secondary, never shown as a percentage — see [kendallTauB]. */
    val tau: Double?,
    val contrast: InsightContrast?,
    val pairedDays: Int,
    val coverage: Double,
    val effectiveN: Int,
    val confidence: InsightConfidence,
)

/**
 * Kendall's tau-b.
 *
 * Chosen over Pearson because one side of every pair here is an ordinal self-report on a five-point
 * scale, and over tau-a because a five-point scale over a hundred days produces ties by the
 * hundred and tau-b is the one whose denominator corrects for them.
 *
 * Two things about it that are easy to get wrong and must not reach the copy:
 *
 *  * **It is not the share of pairs that agree.** That reading belongs to tau-a. Tau-b divides by
 *    `sqrt((n0 - n1)(n0 - n2))`, so "they went together 64% of the time" is simply false of it.
 *  * **Ties cap what it can reach.** Somebody whose mood is 60% threes cannot attain the tau
 *    somebody using the whole scale can, so one fixed threshold on tau means different things for
 *    different people. It is reported beside the contrast, never instead of it.
 *
 * O(n²), which at a few hundred days is tens of thousands of comparisons and belongs off the main
 * thread but needs nothing cleverer.
 */
fun kendallTauB(pairs: List<Pair<Double, Double>>): Double? {
    if (pairs.size < 2) return null
    var concordant = 0L
    var discordant = 0L
    var tiedX = 0L
    var tiedY = 0L
    for (i in pairs.indices) {
        for (j in i + 1 until pairs.size) {
            val dx = pairs[i].first.compareTo(pairs[j].first)
            val dy = pairs[i].second.compareTo(pairs[j].second)
            when {
                dx == 0 && dy == 0 -> {
                    tiedX++
                    tiedY++
                }

                dx == 0 -> tiedX++

                dy == 0 -> tiedY++

                dx * dy > 0 -> concordant++

                else -> discordant++
            }
        }
    }
    val n0 = pairs.size.toLong() * (pairs.size - 1) / 2
    val denominator = sqrt((n0 - tiedX).toDouble() * (n0 - tiedY).toDouble())
    return if (denominator <= 0.0) null else (concordant - discordant) / denominator
}

/** Pearson's r over paired values; null for fewer than two pairs or a side that never varies. */
fun pearson(pairs: List<Pair<Double, Double>>): Double? {
    if (pairs.size < 2) return null
    val meanX = pairs.sumOf { it.first } / pairs.size
    val meanY = pairs.sumOf { it.second } / pairs.size
    var covariance = 0.0
    var varianceX = 0.0
    var varianceY = 0.0
    pairs.forEach { (x, y) ->
        val dx = x - meanX
        val dy = y - meanY
        covariance += dx * dy
        varianceX += dx * dx
        varianceY += dy * dy
    }
    val denominator = sqrt(varianceX * varianceY)
    return if (denominator <= 0.0) null else covariance / denominator
}

/** How long a rolling window is, for taking the slow drift out of a series. */
private const val RESIDUAL_WINDOW_DAYS = 28L

/**
 * Takes out of a series everything that is not about the day itself.
 *
 * Two steps. First a centred 28-day rolling median, which removes a season, a weight-loss arc, a
 * new medication, a fortnight away. Second the person's own median for that weekday, taken on what
 * is left.
 *
 * **The second step is confounder control on the exposure, not a claim about mood.** Gnambs 2021
 * (Zeitschrift für Psychologie, 221 ESS samples, 408,637 participants) found the day-of-week effect
 * on life satisfaction and happiness negligible and "trivial", with only Sunday standing apart. But
 * steps, drinking, eating out and sex all have obvious weekly structure, and two things with the
 * same weekly shape correlate through the calendar alone. On a five-point mood dial this step will
 * usually subtract nothing, and that is the expected outcome rather than a sign it is not working.
 *
 * [anchors] optionally places each day at a point in some repeating cycle — the menstrual one is
 * the case it exists for — and subtracts the median of the residual at that same point.
 *
 * **The first and last fortnight are detrended less well than the middle**, because a centred
 * window has only half of itself to work with there and no filter can invent the missing half. It
 * is left rather than trimmed: the gates already ask for ninety paired days out of a window that
 * is rarely much longer, and taking a fortnight off each end to buy a cleaner edge would refuse
 * far more records than the artefact would ever mislead.
 */
fun residualise(series: DailySeries, anchors: Map<LocalDate, Int>? = null): DailySeries {
    if (series.byDay.isEmpty()) return series
    val half = RESIDUAL_WINDOW_DAYS / 2
    val detrended = series.byDay.mapValues { (day, value) ->
        val window = series.byDay.filterKeys { abs(ChronoUnit.DAYS.between(it, day)) <= half }.values
        value - (window.medianOrNull() ?: 0.0)
    }
    val byWeekday = detrended.entries.groupBy({ it.key.dayOfWeek }, { it.value })
        .mapValues { (_, values) -> values.medianOrNull() ?: 0.0 }
    val weekdayAdjusted = detrended.mapValues { (day, value) -> value - (byWeekday[day.dayOfWeek] ?: 0.0) }
    if (anchors == null) return DailySeries(weekdayAdjusted)
    val byAnchor = weekdayAdjusted.entries
        .mapNotNull { (day, value) -> anchors[day]?.let { it to value } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.medianOrNull() ?: 0.0 }
    return DailySeries(
        weekdayAdjusted.mapValues { (day, value) ->
            value - (anchors[day]?.let { byAnchor[it] } ?: 0.0)
        },
    )
}

/** How many blocks long a bootstrap resample is cut into: a week, so the weekly shape survives it. */
private const val BOOTSTRAP_BLOCK_DAYS = 7

/** Enough resamples that the percentile ends are stable to the decimal the screen prints. */
private const val BOOTSTRAP_ROUNDS = 2_000

private const val BOOTSTRAP_LOW_PERCENTILE = 0.025
private const val BOOTSTRAP_HIGH_PERCENTILE = 0.975

/** What the resamples of a statistic came to: the 95% range, and [chance] — see [bootstrapChance]. */
data class BootstrapRange(val lo: Double, val hi: Double, val chance: Double)

/**
 * How often the resamples of a difference fell on the far side of nought, as the two-sided p-value
 * [benjaminiHochberg] is calibrated for: twice the smaller tail, which is the level at which a
 * percentile range would first reach zero.
 *
 * One is added to the count and to the total (Phipson & Smyth 2010, Stat Appl Genet Mol Biol 9(1),
 * Article 39, doi:10.2202/1544-6115.1585, "Permutation P-values should never be zero"), so two
 * thousand resamples that never crossed read as about one in a thousand rather than as never.
 *
 * A p-value rather than how far the range sits from zero in units of its own width: that figure is an
 * ordering and not a probability, and held to thresholds written for p-values it would let through
 * only a range standing a whole width clear of zero — an estimate near six standard errors — so the
 * 10% the correction names would not be the rate it works at.
 */
internal fun bootstrapChance(resamples: List<Double>): Double {
    val notAbove = resamples.count { it <= 0.0 }
    val notBelow = resamples.count { it >= 0.0 }
    return (2.0 * (minOf(notAbove, notBelow) + 1) / (resamples.size + 1)).coerceAtMost(1.0)
}

/**
 * A 95% range for a statistic, by moving-block bootstrap.
 *
 * Blocks rather than single days because a health record is serially correlated — today's mood
 * resembles yesterday's — and resampling day by day would pretend otherwise and hand back a range
 * far too narrow. A seven-day block keeps both that dependence and the weekly cycle.
 *
 * Whole pairs are resampled, never the two series separately: drawing x and y independently would
 * destroy the very association being measured.
 *
 * [seed] is passed in and must be derived from what is being measured rather than from the clock.
 * A card that shows different numbers each time it is opened loses a reader's trust faster than any
 * error in the statistics could.
 */
fun movingBlockRange(
    pairs: List<Pair<Double, Double>>,
    seed: Long,
    block: Int = BOOTSTRAP_BLOCK_DAYS,
    rounds: Int = BOOTSTRAP_ROUNDS,
    statistic: (List<Pair<Double, Double>>) -> Double?,
): BootstrapRange? {
    if (pairs.size < block * 2) return null
    val random = Random(seed)
    val starts = pairs.size - block + 1
    val blocksPerRound = (pairs.size + block - 1) / block
    val values = ArrayList<Double>(rounds)
    repeat(rounds) {
        val sample = ArrayList<Pair<Double, Double>>(blocksPerRound * block)
        repeat(blocksPerRound) {
            val from = random.nextInt(starts)
            for (offset in 0 until block) sample.add(pairs[from + offset])
        }
        statistic(sample.subList(0, pairs.size))?.let(values::add)
    }
    if (values.size < rounds / 2) return null
    values.sort()
    val lo = values[(values.size * BOOTSTRAP_LOW_PERCENTILE).toInt().coerceIn(0, values.size - 1)]
    val hi = values[(values.size * BOOTSTRAP_HIGH_PERCENTILE).toInt().coerceIn(0, values.size - 1)]
    return BootstrapRange(minOf(lo, hi), maxOf(lo, hi), bootstrapChance(values))
}

/** The share of days at each end of the exposure that the headline is built from. */
private const val TERCILE_SHARE = 1.0 / 3.0

/**
 * The outcome on the third of days the exposure ran highest, against the third it ran lowest.
 *
 * Terciles rather than a median split because the middle of somebody's own range is the part that
 * says least, and rather than the extremes because a tenth of a hundred days is ten days.
 */
fun terciles(pairs: List<Pair<Double, Double>>): Pair<List<Double>, List<Double>>? {
    if (pairs.size < 6) return null
    val sorted = pairs.sortedBy { it.first }
    val cut = (sorted.size * TERCILE_SHARE).toInt().coerceAtLeast(1)
    val low = sorted.take(cut).map { it.second }
    val high = sorted.takeLast(cut).map { it.second }
    return if (low.isEmpty() || high.isEmpty()) null else high to low
}

/**
 * The two groups of days the headline sets side by side, high first: [terciles], or — for an
 * exposure that only ever took two values, of which [yes] is the larger — the days with it against
 * the days without.
 *
 * A yes-or-no series has no thirds to cut. Sorted, its top third is every day with a drink and then
 * whichever days without one happened to sort last, so three heavier evenings in four months would become
 * forty "days after drinking", thirty-seven of them sober, set against the forty sober days at the
 * other end of the window. Split by the value, the groups are the ones the card names and the
 * fifteen-day floor counts the days it says it counts.
 *
 * Null when either group is empty, which a resample of a rare exposure can be.
 */
internal fun highAndLow(pairs: List<Pair<Double, Double>>, yes: Double?): Pair<List<Double>, List<Double>>? {
    if (yes == null) return terciles(pairs)
    val (high, low) = pairs.partition { it.first == yes }
    return if (high.isEmpty() || low.isEmpty()) null else high.map { it.second } to low.map { it.second }
}

/**
 * Benjamini-Hochberg over the candidates' p-values, which is what its thresholds are written for.
 *
 * Used as a silent display filter over the fixed list of hypotheses, so that testing a list of
 * things does not hand somebody a finding or two a year purely by arithmetic. The screen says out
 * loud how many comparisons were made, which is the honest half of any such correction.
 *
 * [pValues] are each link's [bootstrapChance]; a hypothesis too thin to measure is handed in as 1,
 * so it still counts as a comparison made and can never pass.
 */
fun benjaminiHochberg(pValues: List<Double>, q: Double): List<Boolean> {
    if (pValues.isEmpty()) return emptyList()
    val ordered = pValues.withIndex().sortedBy { it.value }
    var largest = -1
    ordered.forEachIndexed { rank, (_, pValue) ->
        if (pValue <= q * (rank + 1) / pValues.size) largest = rank
    }
    val keep = BooleanArray(pValues.size)
    for (rank in 0..largest) keep[ordered[rank].index] = true
    return keep.toList()
}

/**
 * A rough count of how many independent days a serially correlated pair of series is worth.
 *
 * **This is a display heuristic and is documented as one.** The corrections in this family are
 * known to be biased once the true correlation is non-zero, which is exactly the case here, and the
 * dramatic figures quoted for how badly a naive count misleads come from sub-second brain imaging
 * rather than from daily behavioural series. It is used only as a gate — never to compute a p-value
 * and never shown to anybody.
 */
fun effectiveN(x: List<Double>, y: List<Double>): Int {
    if (x.size < 3 || x.size != y.size) return x.size
    val ax = lagOneAutocorrelation(x)
    val ay = lagOneAutocorrelation(y)
    val inflation = 1.0 + 2.0 * ax * ay
    return if (inflation <= 0.0) x.size else (x.size / inflation).toInt().coerceIn(1, x.size)
}

private fun lagOneAutocorrelation(values: List<Double>): Double {
    val mean = values.average()
    var top = 0.0
    var bottom = 0.0
    values.forEachIndexed { index, value ->
        val d = value - mean
        bottom += d * d
        if (index > 0) top += d * (values[index - 1] - mean)
    }
    return if (bottom <= 0.0) 0.0 else (top / bottom).coerceIn(-1.0, 1.0)
}

/**
 * Whether a set of paired days is worth measuring at all.
 *
 * Every one of these is a way of being talked out of a finding, which is the point: see the
 * defence of each figure on [INSIGHT_GATES].
 */
private fun enoughToMeasure(
    pairs: List<Pair<Double, Double>>,
    coverage: Double,
    effective: Int,
    gates: InsightGates,
): Boolean {
    if (pairs.size < gates.minPairedDays) return false
    if (coverage < gates.minCoverage) return false
    if (effective < gates.minEffectiveN) return false
    val outcomes = pairs.map { it.second }
    if (outcomes.distinct().size < gates.minDistinctOutcomes) return false
    val commonest = outcomes.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
    return commonest.toDouble() / outcomes.size <= gates.maxOutcomeShare
}

/** How many days the exposure is shifted by, so that the pairing reads as the lag it is named for. */
private fun InsightLag.shiftDays(): Long = when (this) {
    InsightLag.SAME_DAY -> 0L
    InsightLag.EXPOSURE_YESTERDAY -> 1L
    InsightLag.OUTCOME_YESTERDAY -> -1L
}

/**
 * Lines two series up at a lag and measures what is there, or says there is not enough to measure.
 *
 * [windowDays] is the stretch the two were read over, which is what coverage is a share of.
 *
 * [shown] is the outcome as the person wrote it down, for an [outcome] that has had something taken
 * out of it: the two groups' levels are read off it, on the same days, for the sentence that prints
 * them. A day it does not carry is read from [outcome].
 */
@Suppress("ReturnCount")
fun insightLink(
    exposure: DailySeries,
    outcome: DailySeries,
    lag: InsightLag,
    windowDays: Int,
    seed: Long,
    gates: InsightGates = INSIGHT_GATES,
    shown: DailySeries = outcome,
): InsightLink {
    val shift = lag.shiftDays()
    // In calendar order, whatever order the map was built in: a resampled block is a run of
    // neighbouring days and [effectiveN] compares each day with the one before it, and both read
    // the list's order as the calendar's.
    val rows = outcome.byDay.entries.sortedBy { it.key }.mapNotNull { (day, value) ->
        exposure.byDay[day.minusDays(shift)]?.let { Triple(it, value, shown.byDay[day] ?: value) }
    }
    val pairs = rows.map { it.first to it.second }
    val coverage = if (windowDays <= 0) 0.0 else pairs.size.toDouble() / windowDays
    val effective = effectiveN(pairs.map { it.first }, pairs.map { it.second })
    val thin = InsightLink(lag, null, null, pairs.size, coverage, effective, InsightConfidence.NOT_ENOUGH)
    if (!enoughToMeasure(pairs, coverage, effective, gates)) return thin

    // An exposure that never moved has no high days and no low ones: thirds cut from it would set
    // the start of the window against its end and call the gap the exposure's.
    val levels = pairs.map { it.first }.distinct()
    if (levels.size < 2) return thin
    val yes = levels.max().takeIf { levels.size == 2 }
    val (high, low) = highAndLow(pairs, yes) ?: return thin
    if (high.size < gates.minGroupDays || low.size < gates.minGroupDays) return thin
    val range = movingBlockRange(pairs, seed) { sample ->
        highAndLow(sample, yes)?.let { (a, b) -> a.average() - b.average() }
    } ?: return thin
    // The same days in the same order, and the cut reads nothing but the exposure, so it falls on
    // the very days it fell on above and only the figure read off them differs.
    val (highShown, lowShown) = highAndLow(rows.map { it.first to it.third }, yes) ?: return thin

    return InsightLink(
        lag = lag,
        tau = kendallTauB(pairs),
        contrast = InsightContrast(
            highDays = high.size,
            lowDays = low.size,
            highMean = high.average(),
            lowMean = low.average(),
            lo = range.lo,
            hi = range.hi,
            highShown = highShown.average(),
            lowShown = lowShown.average(),
            chance = range.chance,
        ),
        pairedDays = pairs.size,
        coverage = coverage,
        effectiveN = effective,
        confidence = if (pairs.size >= gates.steadyDays) {
            InsightConfidence.STEADY
        } else {
            InsightConfidence.EARLY
        },
    )
}

/**
 * A seed that depends on what is being measured and on nothing else.
 *
 * So the same card opened twice in a row shows the same range, and a new day's data moves it for a
 * reason rather than because the clock did.
 */
fun insightSeed(exposure: String, outcome: String, until: LocalDate): Long =
    (exposure.hashCode().toLong() * 31 + outcome.hashCode()) * 31 + until.toEpochDay()

/**
 * Newcombe's hybrid score interval for the difference between two independent proportions,
 * [x2]/[n2] − [x1]/[n1], at 95%: method 10 of Newcombe 1998 (Stat Med 17:873), which combines the
 * two Wilson score intervals and keeps its coverage on the small counts a diary gives.
 *
 * The one implementation: the sheet's two-stretch figures and the day comparisons below both ask it.
 */
internal fun newcombeInterval(x1: Int, n1: Int, x2: Int, n2: Int): ClosedFloatingPointRange<Double> {
    val p1 = x1.toDouble() / n1
    val p2 = x2.toDouble() / n2
    val (l1, u1) = wilson(x1, n1)
    val (l2, u2) = wilson(x2, n2)
    val difference = p2 - p1
    val below = sqrt((p2 - l2) * (p2 - l2) + (u1 - p1) * (u1 - p1))
    val above = sqrt((u2 - p2) * (u2 - p2) + (p1 - l1) * (p1 - l1))
    return (difference - below)..(difference + above)
}

/** The Wilson score interval for [x] of [n] at 95%. */
private fun wilson(x: Int, n: Int): Pair<Double, Double> {
    val p = x.toDouble() / n
    val z2 = Z_95 * Z_95
    val centre = (p + z2 / (2 * n)) / (1 + z2 / n)
    val half = Z_95 * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n)
    return (centre - half) to (centre + half)
}

/** The two-sided 95% normal quantile. */
private const val Z_95 = 1.959964

// Two groups of days set side by side — long work days against the others, tense days against calm
// ones, mornings after a long night against those after a short one — and the gates between the
// counts and any word about them. All of it is the app's own rule, and the cards and the sheet say so.
//
// Neighbouring days are not independent: a week of long days is also a week of whatever else that
// week held, and Newcombe's interval, which treats every day as a fresh draw, is optimistic about it.
// Hence the split-half check on top: the two halves of the stretch have to point the same way
// before anything is called notable.

/** Days each group needs before a comparison between them says anything: the app's own floor. */
const val CONTRAST_MIN_DAYS = 10

/** How far apart two shares must be on a card: fifteen points, the app's own line. */
const val CARD_SHARE_POINTS = 0.15

/** And in one of the sheet's pairs: twenty points, the wound-up figure's line. */
const val SHEET_SHARE_POINTS = 0.20

/** What a comparison between two groups of days may say. */
enum class ContrastVerdict {
    /** A group is under the floor: the counts and what is still needed, nothing else. */
    NOT_ENOUGH_DAYS,

    /**
     * Enough days, and the gap is under the line, its range reaches nought, or the two halves of the
     * stretch disagree. "No clear difference yet" — never "no effect".
     */
    NO_CLEAR_DIFFERENCE,

    /** Every gate passed. Still two things on the same days, and worded as that. */
    NOTABLE,
}

/** [days] of [of] days had the thing counted. */
data class DayShare(val days: Int, val of: Int) {
    val share: Double? get() = if (of == 0) null else days.toDouble() / of
}

/** The mean of one number a day over [days] days; null when there were none. */
data class DayMean(val days: Int, val mean: Double?)

/** The part of a comparison every card and every sheet row reads the same way. */
sealed interface DayContrast {
    val verdict: ContrastVerdict

    /** The floor each group had to reach, for "needs 10 each". */
    val minDays: Int

    val notable: Boolean get() = verdict == ContrastVerdict.NOTABLE
}

/**
 * The share of [exposed] days with the outcome against the share of [other] days with it: the
 * counts always, and [interval] — Newcombe's 95% for exposed minus other — once both reach the floor.
 */
data class DayShareContrast(
    val exposed: DayShare,
    val other: DayShare,
    val interval: ClosedFloatingPointRange<Double>?,
    override val minDays: Int,
    override val verdict: ContrastVerdict,
) : DayContrast

/**
 * A number's mean on [exposed] days against its mean on [other] days, and [interval] — Welch's 95%
 * for exposed minus other — once both reach the floor.
 */
data class DayMeanContrast(
    val exposed: DayMean,
    val other: DayMean,
    val interval: ClosedFloatingPointRange<Double>?,
    override val minDays: Int,
    override val verdict: ContrastVerdict,
) : DayContrast

/**
 * A lagged comparison and its reverse: [forward] sets the outcome after the exposure, [reverse] sets
 * the outcome on the day before it — the control for "the pain came first and the tense day followed".
 */
data class LaggedContrast<C : DayContrast>(val forward: C, val reverse: C) {
    /**
     * Whether "on days after" may be said: only when the forward pairing is notable and the reverse
     * one was weighed and found quiet. Otherwise the two went together at most, in no order the
     * record can show. A reverse under its floor is never weighed at all — it loses the window's
     * first day and needs the day before each exposure answered, so it is routinely the thinner of
     * the two — and a control that was not run cannot clear a direction.
     */
    val directed: Boolean get() = forward.notable && reverse.verdict == ContrastVerdict.NO_CLEAR_DIFFERENCE
}

/**
 * Two groups of days, each day with its outcome yes or no, compared as shares.
 *
 * Notable only when each group has [minDays] days, the shares are [minPoints] or more apart, the
 * Newcombe interval for the difference leaves out nought and — with [splitHalf] — each half of the
 * stretch the days cover points the same way as the whole.
 */
fun dayShareContrast(
    exposed: Map<LocalDate, Boolean>,
    other: Map<LocalDate, Boolean>,
    minDays: Int = CONTRAST_MIN_DAYS,
    minPoints: Double = CARD_SHARE_POINTS,
    splitHalf: Boolean = true,
): DayShareContrast {
    val a = DayShare(exposed.values.count { it }, exposed.size)
    val b = DayShare(other.values.count { it }, other.size)
    if (a.of < minDays || b.of < minDays) {
        return DayShareContrast(a, b, null, minDays, ContrastVerdict.NOT_ENOUGH_DAYS)
    }
    val interval = newcombeInterval(b.days, b.of, a.days, a.of)
    // Cross-multiplied, so a gap of exactly fifteen points is not lost to a division.
    val gap = a.days.toLong() * b.of - b.days.toLong() * a.of
    val wide = abs(gap.toDouble()) >= minPoints * a.of * b.of - CONTRAST_SLACK
    val notable = gap != 0L && wide && interval.leavesOutNought() &&
        (!splitHalf || halvesAgree(exposed, other, gap.sign) { days -> days.count { it }.toDouble() / days.size })
    return DayShareContrast(a, b, interval, minDays, verdictOf(notable))
}

/**
 * Two groups of days, each with a number, compared as means: the same gates as [dayShareContrast],
 * with [differs] — handed the exposed mean and the other one — as the line the gap must reach, and
 * Welch's interval in place of Newcombe's.
 */
fun dayMeanContrast(
    exposed: Map<LocalDate, Double>,
    other: Map<LocalDate, Double>,
    differs: (exposedMean: Double, otherMean: Double) -> Boolean,
    minDays: Int = CONTRAST_MIN_DAYS,
    splitHalf: Boolean = true,
): DayMeanContrast {
    val a = DayMean(exposed.size, exposed.values.takeIf { it.isNotEmpty() }?.average())
    val b = DayMean(other.size, other.values.takeIf { it.isNotEmpty() }?.average())
    if (a.mean == null || b.mean == null || minOf(a.days, b.days) < maxOf(minDays, 2)) {
        return DayMeanContrast(a, b, null, minDays, ContrastVerdict.NOT_ENOUGH_DAYS)
    }
    val interval = welchInterval(exposed.values, other.values)
    val sign = signOf(a.mean - b.mean)
    val notable = sign != 0 && differs(a.mean, b.mean) && interval.leavesOutNought() &&
        (!splitHalf || halvesAgree(exposed, other, sign) { it.average() })
    return DayMeanContrast(a, b, interval, minDays, verdictOf(notable))
}

/**
 * The two groups a comparison sets side by side. [exposure] puts each day in a group — true for the
 * exposed one — and [outcome] is asked of the day [shift] days later: 1 for "the day after", -1 for
 * the reverse control, 0 for the same day. A null outcome is an unknown day, and that pair is left
 * out rather than read as a no. Both maps are keyed by the exposure's day.
 */
internal fun <V : Any> splitDays(
    exposure: Map<LocalDate, Boolean>,
    shift: Long,
    outcome: (LocalDate) -> V?,
): Pair<Map<LocalDate, V>, Map<LocalDate, V>> {
    val exposed = HashMap<LocalDate, V>()
    val other = HashMap<LocalDate, V>()
    exposure.forEach { (day, isExposed) ->
        val value = outcome(day.plusDays(shift)) ?: return@forEach
        if (isExposed) exposed[day] = value else other[day] = value
    }
    return exposed to other
}

/** Whether [a] and [b] are [by] or more apart, allowing for the last bit of a double. */
internal fun apartBy(a: Double, b: Double, by: Double): Boolean = abs(a - b) >= by - CONTRAST_SLACK

private const val CONTRAST_SLACK = 1e-9

private fun verdictOf(notable: Boolean): ContrastVerdict =
    if (notable) ContrastVerdict.NOTABLE else ContrastVerdict.NO_CLEAR_DIFFERENCE

private fun ClosedFloatingPointRange<Double>.leavesOutNought(): Boolean = start > 0.0 || endInclusive < 0.0

private fun signOf(difference: Double): Int = when {
    difference > CONTRAST_SLACK -> 1
    difference < -CONTRAST_SLACK -> -1
    else -> 0
}

/**
 * The split-half check: the stretch from the first day either group holds to the last is cut in two
 * by date — the middle day, where there is one, goes with the first half — and in each half both
 * groups need a day and their difference has to have [sign]. A half where one group is missing
 * cannot point any way, and fails.
 */
private fun <V> halvesAgree(
    exposed: Map<LocalDate, V>,
    other: Map<LocalDate, V>,
    sign: Int,
    statistic: (Collection<V>) -> Double,
): Boolean {
    val days = exposed.keys + other.keys
    val first = days.min()
    val middle = first.plusDays(ChronoUnit.DAYS.between(first, days.max()) / 2)
    val halves = listOf<(LocalDate) -> Boolean>({ it <= middle }, { it > middle })
    return halves.all { inHalf ->
        val a = exposed.filterKeys(inHalf).values
        val b = other.filterKeys(inHalf).values
        a.isNotEmpty() && b.isNotEmpty() && signOf(statistic(a) - statistic(b)) == sign
    }
}

/**
 * Welch's 95% interval for the difference of two means, [a] minus [b], each over two values or more:
 * unequal variances allowed, since long days and short ones need not vary alike. Two groups that do
 * not vary at all give the difference itself.
 */
internal fun welchInterval(a: Collection<Double>, b: Collection<Double>): ClosedFloatingPointRange<Double> {
    val meanA = a.average()
    val meanB = b.average()
    val errorA = a.sumOf { (it - meanA) * (it - meanA) } / (a.size - 1) / a.size
    val errorB = b.sumOf { (it - meanB) * (it - meanB) } / (b.size - 1) / b.size
    val difference = meanA - meanB
    val error = sqrt(errorA + errorB)
    if (error == 0.0) return difference..difference
    val freedom = (errorA + errorB) * (errorA + errorB) /
        (errorA * errorA / (a.size - 1) + errorB * errorB / (b.size - 1))
    val reach = studentT975(freedom) * error
    return (difference - reach)..(difference + reach)
}

/**
 * The 97.5th percentile of Student's t with [freedom] degrees of freedom, by the Cornish–Fisher
 * expansion around the normal quantile, three terms: within 0.0005 of the tables from eight degrees
 * of freedom up, and nine is as low as the ten-day floor lets Welch's go. Slightly narrow below that.
 */
internal fun studentT975(freedom: Double): Double {
    val z = Z_95
    val z3 = z * z * z
    val z5 = z3 * z * z
    val z7 = z5 * z * z
    return z + (z3 + z) / (4 * freedom) +
        (5 * z5 + 16 * z3 + 3 * z) / (96 * freedom * freedom) +
        (3 * z7 + 19 * z5 + 17 * z3 - 15 * z) / (384 * freedom * freedom * freedom)
}
