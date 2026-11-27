package com.pwnall.health

import kotlin.math.roundToInt

/** Water is a journal entry like any other; [JournalEntry.servingG] carries the millilitres. */
const val KIND_WATER = "WATER"

val WATER_PORTIONS_ML = listOf(100, 330, 400, 500)

/** Caffeine is a journal entry like water; [JournalEntry.servingG] carries the milligrams. */
const val KIND_CAFFEINE = "CAFFEINE"

/**
 * Caffeine per drink for the cups that cover almost every one someone logs, weakest first.
 *
 * The figures are EFSA's (Scientific Opinion on the safety of caffeine, 2015), rounded and put on
 * the 300 ml cup the dialog starts from: about 50 mg in 220 ml of black tea, so 70 in a mug; 80 mg
 * in a 60 ml espresso; 90 mg in 200 ml of filter coffee, so 135 in a mug — USDA's brewed coffee, 95
 * mg per 240 ml, is the same strength within the rounding. Matcha has no EFSA figure, so its value
 * is an estimate of the app's own. A real cup varies with the leaf, the bean and the brew, so all
 * of these are an estimate the way a food label is.
 *
 * [fixedDose] marks a drink that is a shot rather than a volume. An espresso is the same 60 ml
 * whatever it is served in, and a cappuccino is that shot under milk, so a larger cup holds more milk
 * and the same caffeine; scaling both by the cup would log an S espresso at 20 mg and an L one at
 * 100. Everything else is brewed to the cup and scales with it.
 *
 * [per100g] is the drink's energy as brewed, from USDA FoodData Central's survey foods (FNDDS,
 * 2024-10 release), per 100 g of the drink:
 * - tea — "Tea, hot, leaf, black", FDC 2710488: 1 kcal, 0.3 g carbohydrate;
 * - matcha — whisked in water, which FDC has no entry for: [MATCHA_POWDER_G_PER_CUP] of the powder
 *   per standard cup, at the powder's label figure on [MATCHA_POWDER_KCAL_PER_100G] — 5 kcal a cup;
 * - espresso — "Coffee, espresso", FDC 2710378: 9 kcal, 0.12 g protein, 0.18 g fat, 1.67 g
 *   carbohydrate;
 * - cappuccino — "Coffee, Cappuccino", FDC 2710472: 27 kcal, 1.71 g protein, 0.99 g fat, 2.75 g
 *   carbohydrate. FNDDS builds it from an espresso and reduced-fat (2 %) milk and has no whole-milk
 *   cappuccino; whole milk is 61 kcal per 100 g against 2 % milk's 50 (FDC 2705385, 2705386), and the
 *   milk is nearly all of the cup's energy, so a whole-milk cup is about a fifth more than this;
 * - coffee — "Coffee, brewed", FDC 2710375: 1 kcal, 0.12 g protein, 0.02 g fat.
 *
 * [withMilk] marks the one drink whose energy is its milk. It is logged as food carrying caffeine
 * (see [cupEntry]) and its energy scales with the cup even though its caffeine is the shot.
 */
enum class CaffeineDrink(
    val milligrams: Int,
    val titleRes: Int,
    val per100g: DrinkNutrients,
    val fixedDose: Boolean = false,
    val withMilk: Boolean = false,
) {
    TEA(70, R.string.caffeine_tea, DrinkNutrients(kcal = 1.0, carbsG = 0.3)),
    MATCHA(
        70,
        R.string.caffeine_matcha,
        DrinkNutrients(
            kcal = MATCHA_POWDER_KCAL_PER_100G * MATCHA_POWDER_G_PER_CUP / STANDARD_CUP_ML,
            proteinG = MATCHA_POWDER_PROTEIN_PER_100G * MATCHA_POWDER_G_PER_CUP / STANDARD_CUP_ML,
            carbsG = MATCHA_POWDER_CARBS_PER_100G * MATCHA_POWDER_G_PER_CUP / STANDARD_CUP_ML,
        ),
    ),
    ESPRESSO(
        80,
        R.string.caffeine_espresso,
        DrinkNutrients(kcal = 9.0, proteinG = 0.12, fatG = 0.18, carbsG = 1.67),
        fixedDose = true,
    ),
    CAPPUCCINO(
        80,
        R.string.caffeine_cappuccino,
        DrinkNutrients(kcal = 27.0, proteinG = 1.71, fatG = 0.99, carbsG = 2.75),
        fixedDose = true,
        withMilk = true,
    ),
    COFFEE(135, R.string.caffeine_coffee, DrinkNutrients(kcal = 1.0, proteinG = 0.12, fatG = 0.02)),
}

/** Energy and the three macronutrients of a drink, per 100 g of it or for a whole cup. */
data class DrinkNutrients(
    val kcal: Double,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    val carbsG: Double = 0.0,
)

/**
 * Matcha powder per 100 g. FDC's survey and reference tables have no matcha, so this is a label in
 * its branded foods: Maeda-en's "Green tea powder, matcha", FDC 2033655, the leaf and nothing else
 * — 5 kcal, 1 g protein and 1 g carbohydrate in its 2 g serving.
 */
const val MATCHA_POWDER_KCAL_PER_100G = 250.0

/** Protein of the same label, per 100 g; see [MATCHA_POWDER_KCAL_PER_100G]. */
const val MATCHA_POWDER_PROTEIN_PER_100G = 50.0

/** Carbohydrate of the same label, per 100 g; see [MATCHA_POWDER_KCAL_PER_100G]. */
const val MATCHA_POWDER_CARBS_PER_100G = 50.0

/**
 * Powder per standard cup of matcha: the teaspoon the food catalogue's matcha row says makes one
 * cup. A thicker or thinner whisk moves the energy with it.
 */
const val MATCHA_POWDER_G_PER_CUP = 2.0

/** The shot an espresso is, in millilitres: EFSA's 60 ml cup that its caffeine is quoted on. */
const val ESPRESSO_SHOT_ML = 60

/**
 * The food catalogue's cappuccino, which a cup with milk points at as its [JournalEntry.foodSource].
 * It is the same FNDDS food as [CaffeineDrink.CAPPUCCINO]'s figures to the catalogue's rounding, so
 * the sugar, sodium and the rest that the cup row does not carry come from the same cup.
 */
const val CAPPUCCINO_FOOD_ID = "coffee_cappuccino"

/**
 * EFSA 2015's daily figure for adults. Shown as context next to the total, never enforced. The same
 * opinion puts a single dose at 200 mg and the day in pregnancy at 200 mg; neither has a line on
 * screen to sit in, so neither is stated here.
 */
const val CAFFEINE_DAILY_LIMIT_MG = 400

/** Cup sizes the caffeine dialog steps through with its arrows. */
val CUP_SIZES = listOf("S" to 100, "M" to 300, "L" to 500)

/** The cup the [CaffeineDrink] doses are quoted for — the M in the row above. */
const val STANDARD_CUP_ML = 300

/** Which size the arrows start on: the standard cup, not the smallest. */
const val CUP_SIZE_DEFAULT_INDEX = 1

/**
 * Caffeine for a chosen cup. A brewed drink scales by volume against the standard cup; a shot-based
 * one is the shot, whatever it was poured into — an S espresso is not a third of a shot and an L
 * cappuccino is not two.
 */
fun caffeineForCup(drink: CaffeineDrink, millilitres: Int): Int =
    if (drink.fixedDose) {
        drink.milligrams
    } else {
        (drink.milligrams * millilitres.toDouble() / STANDARD_CUP_ML).roundToInt()
    }

/**
 * How much of a chosen cup is the drink its energy is counted on. An espresso is its shot whatever
 * cup it is poured into, so its energy is the shot's; a cappuccino's caffeine is the shot too, but
 * its energy is the milk, and a larger cup holds more of it. A brewed drink is the cup.
 */
fun energyMillilitres(drink: CaffeineDrink, millilitres: Int): Int =
    if (drink.fixedDose && !drink.withMilk) ESPRESSO_SHOT_ML else millilitres

/**
 * The energy and macronutrients of a chosen cup, as brewed. FDC's figures are per 100 g and a
 * millilitre of any of these drinks weighs a gram to within the rounding, so the millilitres are
 * taken as grams. Milk or sugar added at the table is not in here: that is food, logged as food.
 */
fun nutrientsForCup(drink: CaffeineDrink, millilitres: Int): DrinkNutrients {
    val share = energyMillilitres(drink, millilitres) / 100.0
    val per100g = drink.per100g
    return DrinkNutrients(
        kcal = per100g.kcal * share,
        proteinG = per100g.proteinG * share,
        fatG = per100g.fatG * share,
        carbsG = per100g.carbsG * share,
    )
}

/** The cup's energy rounded to the kilocalorie, as the dialog prints it. */
fun kcalForCup(drink: CaffeineDrink, millilitres: Int): Int = nutrientsForCup(drink, millilitres).kcal.roundToInt()

/**
 * A row's caffeine in milligrams, or null where it states none. A cup keeps its milligrams as its
 * serving; food carrying caffeine — a cappuccino logged with its milk, an energy drink, a synced
 * record — keeps them beside its portion, and food that states none is not food with none.
 *
 * The one rule every reader of caffeine goes through: the dialog's total, the doctor's report, what
 * changed between two stretches and the Health Connect export.
 */
fun caffeineMgOf(entry: JournalEntry): Double? =
    if (entry.kind == KIND_CAFFEINE) entry.servingG else entry.caffeineMg

/** The day's caffeine in milligrams over [entries]; see [caffeineMgOf]. */
fun dayCaffeineMg(entries: List<JournalEntry>): Double = entries.sumOf { caffeineMgOf(it) ?: 0.0 }

/**
 * A cup from the caffeine dialog that was filed as food for its milk ([cupEntry]): a food row that
 * carries caffeine and points at the catalogue's cappuccino alone. The food editor puts no caffeine
 * on a meal it builds and an import carries no catalogue pointer, so the pair is the mark — and the
 * pointer is held to that one row, not to any: a cup corrected in the editor keeps both and stays a
 * cup, while an imported drink that carries caffeine and is later given another catalogue food in
 * the editor does not turn into one. [StepsDao.recentMeals] reads the same pair in SQL.
 */
val JournalEntry.isCaffeineCup: Boolean
    get() = kind == KIND_EAT && caffeineMg != null && foodSource?.let(CUP_SOURCE::matches) == true

/** [cupEntry]'s pointer: the catalogue's cappuccino and the cup's grams, and nothing else. */
private val CUP_SOURCE = Regex(Regex.escape(CAPPUCCINO_FOOD_ID) + "=[0-9.]+")

/**
 * Whether a row is a meal where the app counts meals: the two meals that make a day kept in full,
 * the days food was logged on, the last meal a dose with or after food is timed after, and the
 * reminder that asks whether it is time to eat. The levels count food entries, cups included.
 *
 * A cup with milk is food, and its energy and nutrients are summed with the meals' everywhere, but
 * it is a drink: two cappuccinos are not a day of eating, and one beside a dinner is not a day
 * logged to the end. The fasting before a blood draw still reads it as food, because a fast there
 * is hours without calories ([inferredFasting]) and the milk has them.
 */
val JournalEntry.countsAsMeal: Boolean
    get() = kind == KIND_EAT && !isCaffeineCup

/**
 * Health Connect stores caffeine as a mass in grams; the journal stores the milligrams that are
 * printed on the packet.
 */
fun caffeineGrams(milligrams: Double): Double = (if (milligrams > 0.0) milligrams else 0.0) / 1_000.0
