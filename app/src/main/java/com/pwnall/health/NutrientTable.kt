package com.pwnall.health

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// How each daily figure is being filled: from the meals picked from the catalogue and from the
// supplement doses marked, read against NASEM's daily figure and the upper levels.
//
// The diary is a floor twice over. A meal with no USDA figure for a nutrient holds nought or more of
// it, and a dose left unmarked may still have been swallowed. So what is known can show that a
// figure was reached, or an upper level passed, at any coverage — and can show that one was not
// reached almost never: only where every meal on every logged day carries the nutrient, every dose
// asked for was marked, and the figure is an allowance rather than an intake somebody observed.
//
// The report's table and the page in the food flow are both this; neither decides anything of its
// own. Pure, like [doctorReport]: a test can pin every verdict without a device.

/** The rows every table carries, in the order it prints them. */
internal val NUTRIENT_TABLE_ORDER = listOf(
    Nutrient.VITAMIN_A,
    Nutrient.VITAMIN_C,
    Nutrient.VITAMIN_D,
    Nutrient.VITAMIN_E,
    Nutrient.VITAMIN_K,
    Nutrient.THIAMIN,
    Nutrient.RIBOFLAVIN,
    Nutrient.NIACIN,
    Nutrient.VITAMIN_B6,
    Nutrient.FOLATE,
    Nutrient.VITAMIN_B12,
    Nutrient.CALCIUM,
    Nutrient.IRON,
    Nutrient.MAGNESIUM,
    Nutrient.PHOSPHORUS,
    Nutrient.POTASSIUM,
    Nutrient.ZINC,
    Nutrient.SELENIUM,
)

/**
 * Rows that appear only where a course holds them: none has a food column ([FoodMicro] carries no
 * iodine, since neither of USDA's two tables reports it for any food), so without a supplement there
 * is nothing to put on the line.
 */
internal val NUTRIENT_TABLE_EXTRAS = listOf(Nutrient.BIOTIN, Nutrient.IODINE, Nutrient.OMEGA3, Nutrient.CREATINE)

/**
 * The two a diary can never show to be short of their daily figure.
 *
 * Vitamin D: NASEM and EFSA both write the figure assuming little sun on the skin, and the diary
 * cannot see the sun. Niacin: the figure is niacin equivalents, which count the tryptophan in the
 * day's protein at 60 mg to the milligram as well as the niacin itself, and USDA's column is the
 * preformed niacin alone.
 */
private val NEVER_SHORT = setOf(Nutrient.VITAMIN_D, Nutrient.NIACIN)

/** What a row may say about the daily figure. The absence of a verdict is null, not a fourth word. */
internal enum class NutrientVerdict(val labelRes: Int) {
    AT_OR_ABOVE(R.string.nutrient_verdict_at_or_above),
    BELOW_BY_ENTRIES(R.string.nutrient_verdict_below),
    CANNOT_SAY(R.string.nutrient_verdict_cannot_say),
}

/** One upper level, and the floor of what the entries put against it — in the level's own scope. */
internal data class UpperLevelCheck(val level: UpperLevel, val counted: Double) {
    val above: Boolean get() = counted > level.amount
}

/**
 * One line of the table.
 *
 * [food] is the row's own food column, null where food was not read or the nutrient has none. For
 * [Nutrient.FOLATE] it is USDA's folate in dietary folate equivalents, which already holds the food's
 * own folic acid; [supplements] is then the courses' folate and their folic acid read at
 * [FOLIC_ACID_DFE], day by day. [supplements] is null where no course holding the nutrient asked for
 * a dose; a course that asked and was never marked is here, at nought marked — see [IntakeWindow].
 * [periodDays] is the window's length, which the doses are spread over in [floorPerDay].
 */
internal data class NutrientRow(
    val nutrient: Nutrient,
    val food: FoodMicroSummary?,
    val supplements: IntakeWindow?,
    val reference: NutrientReference?,
    val upperLevels: List<UpperLevelCheck>,
    val verdict: NutrientVerdict?,
    val periodDays: Int,
) {
    /**
     * The food figure the row prints: the mean over the counted days once there are enough of them,
     * the floor over every logged day before that — see [FoodMicroSummary]. Null where no meal
     * reports it. The mean is not a floor for the window, since the counted days are the ones whose
     * meals were known, so nothing is read against a figure from it.
     */
    val foodFloor: Double? get() = food?.let { it.meanPerDay ?: it.lowerBoundPerDay }

    /**
     * The food's floor over every logged day, which is what the verdicts and the upper levels read:
     * what the meals report, divided by all the logged days, the days nothing was known on included.
     */
    val foodLowerBound: Double? get() = food?.lowerBoundPerDay

    /**
     * Food and the doses marked, added: what "at or above" compares, and never more than was
     * recorded. The doses are spread over every day of the window rather than the days a course
     * asked on — a daily figure is an intake over time, so a five-day course in a month is not a
     * month of that dose. The line itself still prints the mean over the days asked.
     */
    val floorPerDay: Double
        get() = (foodLowerBound ?: 0.0) + (supplements?.let { it.meanMarked * it.askedDays / periodDays } ?: 0.0)
}

/**
 * The whole table over one window.
 *
 * [loggedDays] and [meals] are the food side's counts — days with at least
 * [FOOD_MICROS_MIN_MEALS_PER_DAY] meals and the meals on them — and nought where food was not read.
 */
internal data class NutrientTable(
    val rows: List<NutrientRow>,
    val foodIncluded: Boolean,
    val supplementsIncluded: Boolean,
    val loggedDays: Int,
    val meals: Int,
    val periodDays: Int,
) {
    /** Nothing any row could print: no meal carries a figure and no course asked for a dose. */
    val empty: Boolean get() = rows.all { it.foodFloor == null && it.supplements == null }
}

/**
 * The table from [from] to [to], both included, for [profile] at the age they are on [to].
 *
 * [includeFood] and [includeSupplements] are the two doors a report can shut: a sheet whose owner
 * left the food or the medicines off it does not have them read here either, rather than read and
 * then hidden. With food in and some meal carrying a figure, every row of [NUTRIENT_TABLE_ORDER] is
 * printed — a nutrient no meal reports says so, which a doctor needs as much as a figure. Without,
 * only the nutrients a course holds.
 */
@Suppress("LongParameterList") // The window, the moment it is read at and the two doors: nothing shared.
internal fun nutrientTable(
    entries: List<JournalEntry>,
    medications: List<Medication>,
    profile: UserProfile,
    from: LocalDate,
    to: LocalDate,
    today: LocalDate,
    nowMinute: Int,
    zone: ZoneId,
    includeFood: Boolean,
    includeSupplements: Boolean,
): NutrientTable {
    val food = if (includeFood) foodMicroSummaries(entries, from, to) else emptyMap()
    val intake = if (includeSupplements) {
        supplementIntake(medications, entries, from, to, today, nowMinute, zone)
    } else {
        emptyMap()
    }
    val periodDays = (ChronoUnit.DAYS.between(from, to) + 1).toInt()
    val intakes = Intakes(food, supplementWindow(intake), folateWindow(intake), periodDays)
    val foodKnown = food.values.any { it.lowerBoundPerDay != null }
    val shown = NUTRIENT_TABLE_ORDER.filter { foodKnown || it in intakes.rows } +
        NUTRIENT_TABLE_EXTRAS.filter { it in intakes.rows }
    val age = ageOn(to.toString(), profile.birthDate)
    val complete = includeFood && includeSupplements
    val anyFood = food.values.firstOrNull()
    return NutrientTable(
        rows = shown.map { nutrientRow(it, intakes, profile.sex, age, complete) },
        foodIncluded = includeFood,
        supplementsIncluded = includeSupplements,
        loggedDays = anyFood?.loggedDays ?: 0,
        meals = anyFood?.meals ?: 0,
        periodDays = periodDays,
    )
}

/**
 * Everything a row is read from: the food summaries, the courses' windows as they were marked,
 * [rows] — the same windows with folic acid folded into folate, which is what the table prints —
 * and the number of days in the window.
 */
private class Intakes(
    val food: Map<FoodMicro, FoodMicroSummary>,
    val marked: Map<Nutrient, IntakeWindow>,
    folate: IntakeWindow?,
    val periodDays: Int,
) {
    val rows: Map<Nutrient, IntakeWindow> =
        marked - Nutrient.FOLIC_ACID - Nutrient.FOLATE + listOfNotNull(folate?.let { Nutrient.FOLATE to it })

    fun foodOf(micro: FoodMicro): Double = food[micro]?.lowerBoundPerDay ?: 0.0

    fun markedOf(nutrient: Nutrient): Double = marked[nutrient]?.meanMarked ?: 0.0
}

/**
 * The courses' folate as the daily figure counts it: any folate a label states as such, and its
 * folic acid at [FOLIC_ACID_DFE] — added day by day before the window is taken, so a day on which
 * only one of the two was asked for is one day, not half of two.
 */
private fun folateWindow(intake: Map<Nutrient, Map<LocalDate, NutrientDay>>): IntakeWindow? {
    val folate = intake[Nutrient.FOLATE].orEmpty()
    val folicAcid = intake[Nutrient.FOLIC_ACID].orEmpty()
    val days = (folate.keys + folicAcid.keys).associateWith { day ->
        val stated = folate[day] ?: NutrientDay(0.0, 0.0)
        val synthetic = folicAcid[day] ?: NutrientDay(0.0, 0.0)
        NutrientDay(
            marked = stated.marked + synthetic.marked * FOLIC_ACID_DFE,
            planned = stated.planned + synthetic.planned * FOLIC_ACID_DFE,
        )
    }
    return supplementWindow(mapOf(Nutrient.FOLATE to days))[Nutrient.FOLATE]
}

private fun nutrientRow(nutrient: Nutrient, intakes: Intakes, sex: String, age: Int, complete: Boolean): NutrientRow {
    val food = FoodMicro.entries.firstOrNull { it.nutrient == nutrient }?.let(intakes.food::get)
    val supplements = intakes.rows[nutrient]
    val reference = nutrient.reference(sex, age)
    val upperLevels = upperLevelChecks(nutrient, age, intakes)
    val partial = NutrientRow(nutrient, food, supplements, reference, upperLevels, null, intakes.periodDays)
    return partial.copy(verdict = verdictOf(partial, complete))
}

/**
 * The upper levels that apply to the row, each against the floor of what it counts: the food's over
 * every logged day, and the doses' mean over the days a course asked for one — an upper level is a
 * daily intake, and a course past it was past it over the days it ran, however few of the window's
 * days those were.
 *
 * Folate's are folic acid's: NASEM's limit is on the synthetic form alone, in micrograms of folic
 * acid rather than of folate equivalents, so it is the courses' folic acid and the food's added folic
 * acid that meet it. Vitamin A's is preformed retinol's, which a plate of carrots holds none of. The
 * other scopes are [UpperLevelScope]'s: a synthetic form in fortified food is counted where USDA
 * lists it apart — folic acid — and nowhere else, so those sums stay floors.
 */
private fun upperLevelChecks(nutrient: Nutrient, age: Int, intakes: Intakes): List<UpperLevelCheck> {
    val limited = if (nutrient == Nutrient.FOLATE) Nutrient.FOLIC_ACID else nutrient
    val supplements = intakes.rows[nutrient]?.meanMarked ?: 0.0
    val food = FoodMicro.entries.firstOrNull { it.nutrient == nutrient }?.let(intakes::foodOf) ?: 0.0
    return limited.upperLevels(age).map { level ->
        val counted = when (level.scope) {
            UpperLevelScope.TOTAL -> food + supplements

            UpperLevelScope.SUPPLEMENTS -> supplements

            UpperLevelScope.SYNTHETIC -> if (nutrient == Nutrient.FOLATE) {
                intakes.markedOf(Nutrient.FOLIC_ACID) + intakes.foodOf(FoodMicro.FOLIC_ACID)
            } else {
                supplements
            }

            UpperLevelScope.PREFORMED -> intakes.foodOf(FoodMicro.RETINOL) + intakes.markedOf(Nutrient.VITAMIN_A)
        }
        UpperLevelCheck(level, counted)
    }
}

/**
 * What the row may say, which is mostly what it may not.
 *
 * "At or above" needs only [NutrientRow.floorPerDay] to reach the figure; "below" is [mayReadShort]'s
 * to allow. Vitamin D without a marked dose has no verdict at all: the diary cannot see the sun,
 * which makes it too, and a course nobody ticked would leave the food to speak alone.
 */
private fun verdictOf(row: NutrientRow, complete: Boolean): NutrientVerdict? {
    val reference = row.reference
    return when {
        reference == null -> null
        row.nutrient == Nutrient.VITAMIN_D && (row.supplements?.meanMarked ?: 0.0) <= 0.0 -> null
        row.floorPerDay >= reference.amount -> NutrientVerdict.AT_OR_ABOVE
        mayReadShort(row, reference, complete) -> NutrientVerdict.BELOW_BY_ENTRIES
        else -> NutrientVerdict.CANNOT_SAY
    }
}

/**
 * "Below" needs the floor to be the whole of it: an allowance rather than an adequate intake —
 * NASEM's *Applications in Dietary Assessment* (2000) reads nothing about adequacy into an intake
 * under an AI — a nutrient the diary can see all of, [FOOD_MICROS_MIN_DAYS] counted days, every meal
 * on every logged day carrying it, every dose asked for marked, and both halves read: a sheet without
 * the medicines cannot say what a tablet added. It also needs the sum to fall short with the doses at
 * their mean over the days a course asked, not spread over the window: a day before a course was
 * written down is not a day without it.
 */
private fun mayReadShort(row: NutrientRow, reference: NutrientReference, complete: Boolean): Boolean {
    val food = row.food
    val supplements = row.supplements
    val whole = complete && food?.meanPerDay != null && food.knownMeals == food.meals &&
        (supplements == null || supplements.markedShare >= 1.0)
    val onAskedDays = (row.foodLowerBound ?: 0.0) + (supplements?.meanMarked ?: 0.0)
    return whole && onAskedDays < reference.amount && reference.kind == ReferenceKind.RDA &&
        row.nutrient !in NEVER_SHORT
}

/**
 * A figure as the table prints it: whole from ten up, two significant figures under it.
 *
 * Under ten a fixed single decimal would print 0.04 mg as "0.0", which reads as none. Plain digits
 * with a point, the way [pretty] prints the rest of the sheet's figures.
 */
internal fun nutrientFigure(value: Double): String = when {
    value >= 10.0 -> value.roundToInt().toString()
    value <= 0.0 -> "0"
    else -> BigDecimal(value).round(MathContext(2, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
}
