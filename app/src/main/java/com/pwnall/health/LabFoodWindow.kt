package com.pwnall.health

import java.time.LocalDate
import kotlin.math.pow

// What the diary said in the stretch of time a blood marker was actually formed over.
//
// Every other food rule in this app measures the last fortnight counted back from *today*, which
// answers a different question: a panel drawn in March and typed in September would be described
// by September's dinners. This one is anchored on the draw, so a sheet from three years ago is
// described by the weeks that led up to it — and it is offered for any reading outside its band,
// however old, rather than only for the newest one.
//
// It describes. It does not advise, does not explain the result, and does not claim the diet moved
// the figure. One person's diary is not an experiment, and self-reported intake runs 22-25% under
// what doubly labelled water measures (Foster 2019, J Nutr Sci 8:e29), with limits of agreement
// from -73% to +68%. What it can honestly do is put the two things side by side.

/**
 * Which logged nutrient is worth printing beside a marker, and what to compare it against.
 *
 * [referenceG] is a published daily figure for the nutrient itself, never a target derived from the
 * blood result. [perEnergyShare], where it is set, is the share of energy the guidance is actually
 * written on, so the reference follows the person's own intake rather than a 2000 kcal stranger's.
 */
internal data class LabDietFactor(
    val labelRes: Int,
    val referenceG: Double,
    val perEnergyShare: Double? = null,
    /**
     * Whether a logged day with none of it is a day of none. True of a drink, which is a row of its
     * own: a day with its meals logged and no drink row had no drink. Never of a nutrient, where a
     * blank column is "nobody entered it".
     */
    val absentIsNone: Boolean = false,
    val of: (JournalEntry) -> Double?,
)

/**
 * The window a marker is formed over, and what in the diary is worth reading across it.
 *
 * [halfLifeDays] weights the window so that a marker with a turnover gives more to the days nearest
 * the draw. HbA1c is the reason it exists: Tahara and Shima (1995) measured a half-time of about
 * 34.6 days, and the three-month figure people quote is the tail of a smooth decay rather than
 * three equal months. A null half-life is a flat mean, which is what the rest of them get.
 *
 * Markers not in this table have no dietary driver this app can measure. Serum sodium and potassium
 * are held where the body wants them whatever is eaten; CRP has a within-subject coefficient of
 * variation around 42% and is moved more by a chest infection than by a diet; TSH, FT4, Lp(a) and
 * bilirubin have none at all. Silence is the honest output for those.
 */
internal data class LabDietWindow(
    val windowDays: Int,
    val halfLifeDays: Double? = null,
    val factors: List<LabDietFactor>,
)

/** Saturated fat as a share of energy, which is the form the guidance is written in. */
private const val SATURATED_FAT_REFERENCE_SHARE = 0.10
private const val FAT_KCAL_PER_GRAM = 9.0

/**
 * Saturated fat and fibre, which are the two the lipid trials are written about.
 *
 * Fibre is printed against the 25 g guideline for total fibre and nothing more is claimed of it:
 * Brown 1999 measured -0.057 mmol/L of LDL per gram of *soluble* fibre, and soluble fibre is
 * usually a fifth to a third of the total, so applying that coefficient to the diary's total would
 * overstate the effect three- or fourfold.
 */
private val LIPID_WINDOW = LabDietWindow(
    windowDays = 42,
    factors = listOf(
        LabDietFactor(
            R.string.labs_diet_saturated,
            referenceG = 0.0,
            perEnergyShare = SATURATED_FAT_REFERENCE_SHARE,
        ) { it.saturatedFatG },
        LabDietFactor(R.string.labs_diet_fiber, referenceG = FIBER_REFERENCE_G) { it.fiberG },
    ),
)

/**
 * The per-marker table.
 *
 * The windows come from how each marker is actually formed, not from how often it is re-tested:
 *
 * - **HbA1c, 90 days, half-life 35.** Nathan 2007 found HbA1c at weeks 8 and 12 correlated 0.90
 *   with mean glucose over the preceding 8 and 12 weeks; Tahara and Shima give the decay.
 * - **LDL, total and non-HDL cholesterol, 42 days.** An editorial choice, and labelled as one: the
 *   often-quoted "4 to 12 weeks" is the interval after starting or changing a statin, not after a
 *   change of diet. Six weeks sits inside the plateau the metabolic-ward trials reach (Clarke 1997,
 *   BMJ 314:112-117, 395 dietary experiments, median duration one month).
 * - **GGT, 56 days, half-life 26.** Orrego 1985 measured the half-life over eight weeks of
 *   abstinence. Note that it is a *long* half-life: the paper's own conclusion is that GGT can stay
 *   up well into abstinence, which is the opposite of the "normalises in a few weeks" folk figure.
 * - **Triglycerides, 28 days.** They move within days of a change in intake.
 * - **Urate, 14 days.**
 */
internal val LAB_DIET_WINDOWS: Map<LabMarker, LabDietWindow> = mapOf(
    LabMarker.HBA1C to LabDietWindow(
        windowDays = 90,
        halfLifeDays = 35.0,
        factors = listOf(
            LabDietFactor(R.string.labs_diet_carbs, referenceG = 0.0) { it.carbsG },
            LabDietFactor(R.string.labs_diet_sugar, referenceG = 50.0) { it.sugarG },
        ),
    ),
    LabMarker.TRIGLYCERIDES to LabDietWindow(
        windowDays = 28,
        factors = listOf(LabDietFactor(R.string.labs_diet_sugar, referenceG = 50.0) { it.sugarG }),
    ),
    LabMarker.LDL to LIPID_WINDOW,
    LabMarker.CHOLESTEROL to LIPID_WINDOW,
    LabMarker.NON_HDL to LIPID_WINDOW,
    LabMarker.GGT to LabDietWindow(
        windowDays = 56,
        halfLifeDays = 26.0,
        factors = listOf(
            LabDietFactor(R.string.labs_diet_ethanol, referenceG = 0.0, absentIsNone = true) { it.ethanolG() },
        ),
    ),
    LabMarker.URIC_ACID to LabDietWindow(
        windowDays = 14,
        factors = listOf(
            LabDietFactor(R.string.labs_diet_ethanol, referenceG = 0.0, absentIsNone = true) { it.ethanolG() },
        ),
    ),
)

/** Grams of ethanol on a drink row, where that is what the row is. */
private fun JournalEntry.ethanolG(): Double? = servingG.takeIf { kind == KIND_ALCOHOL && it > 0.0 }

/**
 * At least this share of the window must carry the column before a mean is worth printing.
 *
 * The doctor's report reads it too, for the food figure it sets beside two blood results, so the
 * sheet and this screen cannot ask one window for two shares.
 */
internal const val LAB_DIET_MIN_COVERAGE = 0.6

/**
 * And at least this many days in absolute terms, so a short window cannot pass on a handful of days.
 *
 * The fortnight's sugar rule asks for four days out of fourteen, which is loose enough to describe a
 * fortnight from three logged lunches.
 */
private const val LAB_DIET_MIN_DAYS = 14

/**
 * A day with fewer than this many meals is a day nobody finished logging, not a day of fasting.
 *
 * Shared with the doctor's report, whose average day is taken over the same days, so the sheet and
 * the window before a blood draw cannot describe one diary two ways.
 */
internal const val LAB_DIET_MIN_MEALS_PER_DAY = 2

/** How far back the person's own usual is taken from, for the line the window is compared against. */
private const val LAB_DIET_USUAL_DAYS = 365L

/** One nutrient across the window: what it averaged, what it usually averages, what it is read against. */
data class LabDietReading(
    val labelRes: Int,
    val meanPerDay: Double,
    /** The same nutrient over the year before the draw, or null where the year is too thin to say. */
    val usualPerDay: Double?,
    /** The published daily figure, or null for a nutrient nobody publishes one for. */
    val referenceG: Double?,
)

/**
 * What the diary held in the window before one blood draw.
 *
 * [enough] is the whole point of the type: a window the diary cannot describe comes back with the
 * counts and nothing else, so the screen can say "you wrote down food on 6 of these 42 days" rather
 * than averaging six days into a description of six weeks. Refusing is a first-class answer here.
 */
data class LabDietWindowResult(
    val marker: LabMarker,
    val drawDay: LocalDate,
    val windowDays: Int,
    val daysLogged: Int,
    val enough: Boolean,
    val readings: List<LabDietReading>,
)

/**
 * The diary in the window that formed [reading], or null for a marker with no measurable driver.
 *
 * Anchored on the day of the draw and on nothing else — not on today, and with no recency cut-off.
 * A marker that went out of band three years ago is exactly the case this is for.
 */
fun foodBeforeDraw(entries: List<JournalEntry>, reading: LabReading): LabDietWindowResult? {
    val window = LAB_DIET_WINDOWS[reading.marker] ?: return null
    val drawDay = reading.day.toLocalDateOrNull() ?: return null
    val from = drawDay.minusDays(window.windowDays - 1L)

    val meals = entries.mealsByDay(from, drawDay)
    val daysLogged = meals.size
    val enough = daysLogged >= LAB_DIET_MIN_DAYS &&
        daysLogged >= (window.windowDays * LAB_DIET_MIN_COVERAGE).toInt()
    if (!enough) {
        return LabDietWindowResult(reading.marker, drawDay, window.windowDays, daysLogged, false, emptyList())
    }

    val usualFrom = drawDay.minusDays(LAB_DIET_USUAL_DAYS - 1)
    val usualMeals = entries.mealsByDay(usualFrom, drawDay)
    val readings = window.factors.mapNotNull { factor ->
        val perDay = meals.nutrientByDay(factor)
        if (perDay.size < LAB_DIET_MIN_DAYS) return@mapNotNull null
        val mean = weightedMean(perDay, drawDay, window.halfLifeDays)
        val usual = usualMeals.nutrientByDay(factor)
            .takeIf { it.size >= LAB_DIET_MIN_DAYS }
            ?.values
            ?.average()
        val reference = factor.perEnergyShare?.let { share ->
            val kcal = meals.nutrientByDay(LabDietFactor(0, 0.0) { it.calories }).values.averageOrNull()
            kcal?.times(share)?.div(FAT_KCAL_PER_GRAM)
        } ?: factor.referenceG.takeIf { it > 0.0 }
        LabDietReading(factor.labelRes, mean, usual, reference)
    }
    return LabDietWindowResult(reading.marker, drawDay, window.windowDays, daysLogged, true, readings)
}

/**
 * The meals of each day in the range, keyed by day, with the half-logged days dropped.
 *
 * A day carrying one row is somebody who logged breakfast and then got on with their morning. Left
 * in, it drags every mean down and turns an ordinary week into a description of undereating.
 */
private fun List<JournalEntry>.mealsByDay(
    from: LocalDate,
    until: LocalDate,
): Map<LocalDate, List<JournalEntry>> = filter { it.kind == KIND_EAT || it.kind == KIND_ALCOHOL }
    .mapNotNull { entry ->
        val day = entry.day.toLocalDateOrNull() ?: return@mapNotNull null
        if (day < from || day > until) null else day to entry
    }
    .groupBy({ it.first }, { it.second })
    .filterValues { ofDay -> ofDay.count(JournalEntry::countsAsMeal) >= LAB_DIET_MIN_MEALS_PER_DAY }

/**
 * Days that actually carry the column, summed. A missing column is unknown, never zero — except a
 * factor that is a row of its own ([LabDietFactor.absentIsNone]): a logged day without a drink is a
 * day of none, and left out it would make the mean the grams of a drinking day, printed as "a day".
 */
private fun Map<LocalDate, List<JournalEntry>>.nutrientByDay(
    factor: LabDietFactor,
): Map<LocalDate, Double> = mapNotNull { (day, ofDay) ->
    val carried = ofDay.mapNotNull(factor.of)
    if (carried.isEmpty() && !factor.absentIsNone) null else day to carried.sum()
}.toMap()

/**
 * A mean that leans towards the draw where the marker has a turnover, and a plain one where it does
 * not.
 *
 * The weight of a day is `0.5 ^ (days before the draw / half-life)`, which is the same decay the
 * measurements of HbA1c turnover describe. It is an approximation of a smooth curve, not a formula
 * anybody published as a way of weighting a food diary, and the copy calls it an estimate.
 */
internal fun weightedMean(perDay: Map<LocalDate, Double>, drawDay: LocalDate, halfLifeDays: Double?): Double {
    if (perDay.isEmpty()) return 0.0
    if (halfLifeDays == null || halfLifeDays <= 0.0) return perDay.values.average()
    var total = 0.0
    var weights = 0.0
    perDay.forEach { (day, value) ->
        val before = java.time.temporal.ChronoUnit.DAYS.between(day, drawDay).toDouble().coerceAtLeast(0.0)
        val weight = 0.5.pow(before / halfLifeDays)
        total += value * weight
        weights += weight
    }
    return if (weights <= 0.0) perDay.values.average() else total / weights
}
