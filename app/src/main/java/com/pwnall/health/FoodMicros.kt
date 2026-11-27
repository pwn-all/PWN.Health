package com.pwnall.health

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round

// Vitamins and minerals of the food somebody logged, worked out from the catalogue rows each meal
// points at.
//
// The figures are USDA FoodData Central's, per 100 g, in `top_foods_micros.csv` beside the catalogue
// — written by tools/build-food-micros.py, which says how a row was matched and why a cell is empty.
// Empty means USDA did not report it, and it stays unknown all the way up: a meal with one unknown
// part does not know that nutrient, and a day is only read where the meals that do know it carry most
// of the day's energy. Nothing here fills a gap with zero, or with a guess scaled from the calories:
// vitamin C is not proportional to energy, and an orange is not a burger.
//
// Only the vitamin table and the report read the file. The catalogue's search never does, so a
// keystroke in the food picker does not pay for twenty more columns.

/**
 * One column of the micros file: a [Nutrient] USDA reports for food, or a part of one that has an
 * upper level of its own — preformed [RETINOL] and [FOLIC_ACID]. The names are the file's headers.
 *
 * Retinol is part of [VITAMIN_A] rather than a nutrient of its own. It has a column because vitamin
 * A's upper level counts preformed retinol only (see [Nutrient.upperLevels]) — a plate of carrots is
 * a great deal of vitamin A and none of that.
 *
 * Folic acid is part of [FOLATE] the same way. FOLATE is dietary folate equivalents, and USDA's
 * figure already holds the food's added folic acid, 1.7 times over: food folate plus
 * [FOLIC_ACID_DFE] times folic acid, to the rounding of the printed figures, in all 6 478 SR Legacy
 * foods that report the three and in 5 412 of FNDDS's 5 431. So [FOLIC_ACID] is the fortification
 * inside that figure, kept for the upper level, and has no [nutrient]: merged into
 * [Nutrient.FOLIC_ACID], where a tablet's folic acid is counted and then read into folate at 1.7,
 * a fortified bun's would be counted in folate twice.
 *
 * Iodine is not here: neither SR Legacy nor FNDDS reports it for any food.
 */
internal enum class FoodMicro(val nutrient: Nutrient?, val fatSoluble: Boolean = false) {
    VITAMIN_A(Nutrient.VITAMIN_A, fatSoluble = true),
    RETINOL(null, fatSoluble = true),
    VITAMIN_C(Nutrient.VITAMIN_C),
    VITAMIN_D(Nutrient.VITAMIN_D, fatSoluble = true),
    VITAMIN_E(Nutrient.VITAMIN_E, fatSoluble = true),
    VITAMIN_K(Nutrient.VITAMIN_K, fatSoluble = true),
    THIAMIN(Nutrient.THIAMIN),
    RIBOFLAVIN(Nutrient.RIBOFLAVIN),
    NIACIN(Nutrient.NIACIN),
    VITAMIN_B6(Nutrient.VITAMIN_B6),
    VITAMIN_B12(Nutrient.VITAMIN_B12),
    FOLATE(Nutrient.FOLATE),
    FOLIC_ACID(null),
    CALCIUM(Nutrient.CALCIUM),
    IRON(Nutrient.IRON),
    MAGNESIUM(Nutrient.MAGNESIUM),
    PHOSPHORUS(Nutrient.PHOSPHORUS),
    POTASSIUM(Nutrient.POTASSIUM),
    ZINC(Nutrient.ZINC),
    SELENIUM(Nutrient.SELENIUM),
}

/** How a catalogue row came by its USDA food. */
internal enum class FoodMicroMatch {
    /** The FDC food's own energy, protein, fat and carbohydrate are the row's, to the printed decimal. */
    EXACT,

    /** A person matched them by what the food is; the row's own numbers were averaged by hand. */
    PROXY,
}

/** One catalogue row's vitamins and minerals per 100 g, in each nutrient's own unit. */
internal class FoodMicros(val fdcId: String, val match: FoodMicroMatch, private val per100g: DoubleArray) {
    /** Null where USDA does not report it for this food — never zero for that. */
    operator fun get(micro: FoodMicro): Double? = per100g[micro.ordinal].takeUnless(Double::isNaN)
}

/**
 * The micros file by catalogue id. A row missing from it is a row nobody can say anything about.
 *
 * An array per row rather than a map: three thousand rows of twenty boxed doubles would be megabytes
 * of heap for a table two screens read.
 */
internal val TOP_FOOD_MICROS: Map<String, FoodMicros> by lazy { parseMicros() }

internal const val MICROS_RESOURCE = "/top_foods_micros.csv"

/**
 * The columns before the nutrients. The FDC food's own four numbers, which the test that keeps
 * `exact` honest reads, are in a test resource instead: here they would be an eighth of this file,
 * in every install, for a check that runs at build time.
 */
internal val MICROS_LEADING_COLUMNS = listOf("id", "fdc_id", "match")

private fun parseMicros(): Map<String, FoodMicros> {
    val lines = catalogResource(MICROS_RESOURCE).lineSequence().filter(String::isNotBlank).toList()
    val header = lines.first().split(';')
    require(header.take(MICROS_LEADING_COLUMNS.size) == MICROS_LEADING_COLUMNS) {
        "$MICROS_RESOURCE: the header must start with $MICROS_LEADING_COLUMNS"
    }
    // By name, so the script may reorder its columns; an unknown name throws rather than being skipped.
    val columns = header.drop(MICROS_LEADING_COLUMNS.size).map(FoodMicro::valueOf)
    return lines.drop(1).associate { line ->
        val cell = line.split(';')
        require(cell.size == header.size) { "$MICROS_RESOURCE: expected ${header.size} columns in `$line`" }
        val values = DoubleArray(FoodMicro.entries.size) { Double.NaN }
        columns.forEachIndexed { index, micro ->
            cell[MICROS_LEADING_COLUMNS.size + index].toDoubleOrNull()?.let { values[micro.ordinal] = it }
        }
        cell[0] to FoodMicros(cell[1], FoodMicroMatch.valueOf(cell[2].uppercase()), values)
    }
}

/**
 * The catalogue items a pointer names, or null if any part of it no longer names one.
 *
 * [decodeDish] drops a part it cannot read, which is right for a saved dish being put back on a
 * form and wrong here: a meal that had lost a part would add up to less than was eaten, and the
 * table would read the missing part as nothing.
 */
internal fun foodSourceItems(source: String): List<DishItem>? =
    source.decodeDish().takeIf { it.isNotEmpty() && it.size == source.split('|').size }

/**
 * What a meal is saved pointing at: the rows the form was filled from, at the portion that was saved.
 *
 * [picked] is the pointer as it stood when the food was chosen, at the grams it was chosen at. A
 * single row takes the saved portion whole. A dish's rows are scaled together to it — to a tenth of
 * a gram, finer than anybody weighs a portion — because the portion field is where a person says "I
 * ate half of that" after building the plate. Rows at nought grams are dropped: they are products
 * somebody took off the plate, not part of it.
 */
internal fun savedFoodSource(picked: String?, servingG: Double): String? {
    val items = picked?.let(::foodSourceItems).orEmpty()
    val plate = items.filter { it.grams > 0.0 }
    val total = plate.sumOf(DishItem::grams)
    return when {
        items.isEmpty() || servingG <= 0.0 -> null
        items.size == 1 -> listOf(items.single().copy(grams = servingG)).encodeDish()
        plate.isEmpty() -> null
        total.sameWeightAs(servingG) -> plate.encodeDish()
        else -> plate.map { it.copy(grams = round(it.grams * servingG / total * TENTHS) / TENTHS) }.encodeDish()
    }
}

private const val TENTHS = 10.0

/**
 * The catalogue rows this meal was eaten as: its own pointer, or, for a meal saved without one, the
 * row its name and figures still are ([inferredFoodSource]). Null for anything but a meal.
 */
internal fun JournalEntry.foodSourceOrInferred(): String? =
    if (kind == KIND_EAT) foodSource ?: inferredFoodSource() else null

/**
 * A meal saved without a pointer into the catalogue, recognised as the row it was picked from.
 *
 * No migration writes this: it is worked out each time it is asked, and it answers only where the
 * answer is plain. The name must be a catalogue row's name as the picker wrote it, in Russian or
 * English; the qualifier chooses among rows that share a name; and the portion's figures must be
 * that row's per-100 g figures scaled by the portion ([catalogVariant]). A name typed over a label's
 * numbers stays unknown, and so does a name two rows answer equally well.
 */
internal fun JournalEntry.inferredFoodSource(): String? {
    val eligible = kind == KIND_EAT && foodSource == null && servingG > 0.0
    val named = CATALOG_BY_NAME[title.trim()].orEmpty().takeIf { eligible }.orEmpty()
    val qualifier = subtitle.trim()
    val qualified = named.filter { qualifier == it.detail.ru || qualifier == it.detail.en }.ifEmpty { named }
    return qualified.mapNotNull { catalogVariant(it, servingG, calories, proteinG, fatG, carbsG) }
        .singleOrNull()
        ?.let { listOf(it).encodeDish() }
}

/** Every catalogue row under each of its names, the way the picker writes a name onto a meal. */
private val CATALOG_BY_NAME: Map<String, List<TopFood>> by lazy {
    TOP_FOODS.flatMap { food -> setOf(food.name.ru, food.name.en).filter(String::isNotBlank).map { it to food } }
        .groupBy({ it.first }, { it.second })
}

/**
 * [food] as these portion figures describe it — at its own fat content or at one on its rail — or
 * null if they describe neither.
 *
 * The allowance is the rounding the form itself puts in. It writes per-100 g figures to one decimal
 * and saves the portion's, so the energy may drift by 2% or 2 kcal and a macro by 0.15 g and 1%.
 * Anything wider was an edit, and an edited meal is no longer the row.
 */
internal fun catalogVariant(
    food: TopFood,
    servingG: Double,
    kcal: Double,
    proteinG: Double,
    fatG: Double,
    carbsG: Double,
): DishItem? {
    val scale = servingG / 100.0
    fun near(actual: Double, per100g: Double): Boolean =
        abs(actual - per100g * scale) <= MACRO_SLACK_G + MACRO_SLACK_SHARE * per100g * scale
    fun describes(per100: CatalogNutrition): Boolean =
        abs(kcal - per100.kcal * scale) <= maxOf(KCAL_SLACK_SHARE * per100.kcal * scale, KCAL_SLACK) &&
            near(proteinG, per100.proteinG) && near(fatG, per100.fatG) && near(carbsG, per100.carbsG)
    return if (describes(food.nutrition)) {
        DishItem(food, servingG)
    } else {
        food.fatSteps.firstOrNull { describes(food.nutrition.atFatPercent(it)) }?.let { DishItem(food, servingG, it) }
    }
}

private const val KCAL_SLACK = 2.0
private const val KCAL_SLACK_SHARE = 0.02
private const val MACRO_SLACK_G = 0.15
private const val MACRO_SLACK_SHARE = 0.01

/**
 * What one meal carried of each nutrient, summed over the rows it points at. A nutrient is present
 * only when every part of the meal reports it — the rule [withPhotoDish] keeps for fibre — so a
 * missing entry means unknown, never none.
 *
 * A part eaten at another fat content than its row's is scaled the way [atFatPercent] scales
 * protein: the fat-free part concentrates as the fat comes out. Its fat-soluble vitamins are unknown
 * instead, because they leave with the fat, and whether a skimmed product had them put back is on
 * its label rather than in USDA's one food.
 */
internal fun JournalEntry.foodMicros(): Map<FoodMicro, Double> {
    val items = foodSourceOrInferred()?.let(::foodSourceItems)?.filter { it.grams > 0.0 }.orEmpty()
    return FoodMicro.entries.mapNotNull { micro ->
        val amounts = items.map { item -> item.per100g(micro)?.times(item.grams / 100.0) }
        amounts.filterNotNull().takeIf { it.isNotEmpty() && it.size == amounts.size }?.let { micro to it.sum() }
    }.toMap()
}

private fun DishItem.per100g(micro: FoodMicro): Double? {
    val own = TOP_FOOD_MICROS[food.id]?.get(micro)
    val fat = fatPercent ?: return own
    return if (micro.fatSoluble) null else own?.let { value -> food.nutrition.fatFreeScale(fat)?.let { value * it } }
}

/**
 * A day counts towards the mean only if its meals that report the nutrient carry this share of
 * its meal energy. An editorial line, not a published one: a fifth of the day unaccounted for is
 * a fifth that might hold the vitamin, so the mean stands for what the diary shows rather than for
 * what was eaten. Below it the day still adds to [FoodMicroSummary.lowerBoundPerDay].
 */
internal const val FOOD_MICROS_MIN_DAY_COVERAGE = 0.8

/**
 * The same as `LAB_DIET_MIN_MEALS_PER_DAY` in LabFoodWindow.kt, and for the same reason: a day with
 * one meal on it is a day somebody stopped logging, not a day they fasted. FoodMicrosTest holds the
 * two equal.
 */
internal const val FOOD_MICROS_MIN_MEALS_PER_DAY = 2

/**
 * The same as `LAB_DIET_MIN_DAYS` in LabFoodWindow.kt: fewer counted days than this and no mean is
 * offered, only the counts. Held equal by FoodMicrosTest too.
 */
internal const val FOOD_MICROS_MIN_DAYS = 14

/**
 * One nutrient across a stretch of the diary: the figures and the counts behind them, and no
 * verdict. What the numbers are allowed to say is the table's to decide.
 */
internal data class FoodMicroSummary(
    val micro: FoodMicro,
    /** The mean over [countedDays], or null below [FOOD_MICROS_MIN_DAYS] of them. */
    val meanPerDay: Double?,
    /**
     * What the meals that report it add up to, per [loggedDays] — a floor, because every meal that
     * does not report it holds zero or more of it. Null where no meal reports it at all, which is
     * not the same as a floor of nothing.
     */
    val lowerBoundPerDay: Double?,
    /** Logged days whose reporting meals carry [FOOD_MICROS_MIN_DAY_COVERAGE] of their energy. */
    val countedDays: Int,
    /** Days in the range with at least [FOOD_MICROS_MIN_MEALS_PER_DAY] meals on them. */
    val loggedDays: Int,
    /** The share of the logged days' meal energy whose meals report it; null with no logged days. */
    val coverage: Double?,
    /** Meals on the logged days that report it, and all meals on them — the "9 of 12 foods" count. */
    val knownMeals: Int,
    val meals: Int,
)

/**
 * Every [FoodMicro] across the days from [from] to [until], both included.
 *
 * Meals only: a drink logged in the alcohol tracker is not a meal, and its energy is not in the
 * denominator; nor is a vitamin tablet imported as one ([isImportedNothing]). Energy
 * is the share because a teaspoon of sugar and a steak are not the same amount of a day. A day
 * whose meals hold no energy at all — two glasses of diet cola — falls back to the count of meals.
 */
internal fun foodMicroSummaries(
    entries: List<JournalEntry>,
    from: LocalDate,
    until: LocalDate,
): Map<FoodMicro, FoodMicroSummary> {
    val days = entries.filter { it.kind == KIND_EAT && !it.isImportedNothing() }
        .mapNotNull { entry -> entry.day.toLocalDateOrNull()?.let { it to entry } }
        .filter { (day, _) -> day in from..until }
        .groupBy({ it.first }, { it.second })
        .values
        .filter { meals -> meals.count(JournalEntry::countsAsMeal) >= FOOD_MICROS_MIN_MEALS_PER_DAY }
        .map { meals -> meals.map { it to it.foodMicros() } }
    return FoodMicro.entries.associateWith { micro -> summarise(micro, days) }
}

/**
 * A row imported from a Health Connect nutrition record that stated no energy, macro or caffeine —
 * another app's vitamin tablet. The import keeps such records out ([statesMealNutrition]), but rows
 * of them a journal already holds stay in it, and counted as meals they would make a day with one
 * real meal pass the two-meal rule, fully covered, at the energy of that one meal.
 *
 * Stored, the zeros can no longer say whether they were stated, so a diet cola another app logged
 * without its caffeine is left out with them: nought kilocalories are no sign that a day was logged.
 */
private fun JournalEntry.isImportedNothing(): Boolean =
    healthConnectRecordKey != null && caffeineMg == null &&
        calories == 0.0 && proteinG == 0.0 && fatG == 0.0 && carbsG == 0.0

/** A logged day's meals, each beside what it carried. */
private typealias MealDay = List<Pair<JournalEntry, Map<FoodMicro, Double>>>

private fun summarise(micro: FoodMicro, days: List<MealDay>): FoodMicroSummary {
    var counted = 0
    var countedTotal = 0.0
    var knownTotal = 0.0
    var knownKcal = 0.0
    var allKcal = 0.0
    var knownMeals = 0
    days.forEach { meals ->
        val known = meals.filter { (_, micros) -> micro in micros }
        val amount = known.sumOf { (_, micros) -> micros.getValue(micro) }
        val dayKnownKcal = known.sumOf { (meal, _) -> meal.calories.coerceAtLeast(0.0) }
        val dayKcal = meals.sumOf { (meal, _) -> meal.calories.coerceAtLeast(0.0) }
        if (share(dayKnownKcal, dayKcal, known.size, meals.size) >= FOOD_MICROS_MIN_DAY_COVERAGE) {
            counted++
            countedTotal += amount
        }
        knownTotal += amount
        knownKcal += dayKnownKcal
        allKcal += dayKcal
        knownMeals += known.size
    }
    val meals = days.sumOf { it.size }
    return FoodMicroSummary(
        micro = micro,
        meanPerDay = (countedTotal / counted).takeIf { counted >= FOOD_MICROS_MIN_DAYS },
        lowerBoundPerDay = (knownTotal / days.size).takeIf { knownMeals > 0 },
        countedDays = counted,
        loggedDays = days.size,
        coverage = share(knownKcal, allKcal, knownMeals, meals).takeIf { days.isNotEmpty() },
        knownMeals = knownMeals,
        meals = meals,
    )
}

/** Energy's share where there is energy to share, and the count's where there is none. */
private fun share(knownKcal: Double, allKcal: Double, known: Int, all: Int): Double = when {
    allKcal > 0.0 -> knownKcal / allKcal
    all > 0 -> known.toDouble() / all
    else -> 0.0
}
