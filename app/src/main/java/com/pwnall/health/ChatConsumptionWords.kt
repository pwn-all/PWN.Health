package com.pwnall.health

/**
 * A complete statement of drinking a named beverage or smoking a cigarette. The trained reader
 * mistakes Russian taking verbs and “whole” for a medicine dose, sometimes with crisis/overdose
 * flags. This bounded grammar supplies a negative example only when it accounts for the entire
 * message: any extra symptom, medicine, intent or unknown word keeps the usual reading. A drink's
 * stated amount (“200 мл”, “250 ml of”, “1,5 литра”) is part of the statement up to two litres
 * (64 fl oz) of water, juice or lemonade and half a litre (16 fl oz) of a drink with caffeine; more is
 * not an everyday drink, and the message keeps its usual reading.
 */
internal fun ordinaryConsumptionIn(text: String): Boolean {
    val words = loweredText(text).trim().replace(CONSUMPTION_SPACE, " ").replace(CONSUMPTION_END, "")
    return CONSUMPTION_STATEMENT.matches(words)
}

private val CONSUMPTION_SPACE = Regex("$SPACE+")
private val CONSUMPTION_END = Regex("[.,!?…]+$")
private const val CONSUMPTION_DAY = "(?:сегодня|вчера|утром|вечером|today|yesterday)"
private const val CONSUMPTION_TIME = "(?:в|at) (?:[01]?[0-9]|2[0-3])(?::[0-5][0-9])?(?: (?:утра|вечера|am|pm))?"
private const val CONSUMPTION_QUANTITY = "(?:(?:целую|целый|всю|весь|одну|один|a|one|the|whole|a whole) )?"

// A stated amount of an everyday drink: water, juice or lemonade up to two litres (64 fl oz); a drink with
// caffeine up to half a litre (16 fl oz) — two litres of coffee is near a seizure's dose, not an everyday drink.
private const val PLAIN_ML = "(?:[1-9][0-9]{0,2}|1[0-9]{3}|2000)"
private const val PLAIN_LITRES = "(?:0?[.,][0-9]{1,2}|1(?:[.,][0-9]{1,2})?|2(?:[.,]0{1,2})?)"
private const val PLAIN_OZ = "(?:[1-9]|[1-5][0-9]|6[0-4])"
private const val CAFFEINE_ML = "(?:[1-9][0-9]?|[1-4][0-9][0-9]|500)"
private const val CAFFEINE_LITRES = "(?:0?[.,](?:[0-4][0-9]?|50?))"
private const val CAFFEINE_OZ = "(?:[1-9]|1[0-6])"

private fun russianAmount(ml: String, litres: String) =
    "(?:$ml ?(?:мл|миллилитров|миллилитра)|$litres ?(?:л|литр|литра|литров))"

private fun englishAmount(ml: String, litres: String, oz: String) =
    "(?:$ml ?(?:ml|millilitres|milliliters)|$litres ?(?:l|litre|litres|liter|liters)|$oz ?(?:oz|fl oz|ounces))"

/** A drink of [names], with an amount of [amount] before it (and its container) or after it. */
private fun measured(amount: String, names: String, containers: String, of: String = "") =
    "(?:$amount$of )?$containers$names(?: $amount)?"

private const val RUSSIAN_CONTAINERS = "(?:(?:банку|бутылку|стакан|кружку|чашку) )?"
private const val RUSSIAN_PLAIN = "(?:воду|воды|сок|сока|лимонад|лимонада)"
private const val RUSSIAN_CAFFEINE = "(?:колу|колы|кока-колу|кока-колы|кока колу|кока колы|чай|чаю|кофе|коффе|" +
    "латте|лате|капучино|капуччино|матчу|матчи|матча|матча латте|матчу латте)"
private val RUSSIAN_BEVERAGE = "(?:выпил|выпила|выпили) " + CONSUMPTION_QUANTITY + "(?:" +
    measured(russianAmount(PLAIN_ML, PLAIN_LITRES), RUSSIAN_PLAIN, RUSSIAN_CONTAINERS) + "|" +
    measured(russianAmount(CAFFEINE_ML, CAFFEINE_LITRES), RUSSIAN_CAFFEINE, RUSSIAN_CONTAINERS) + ")"
private const val ENGLISH_CONTAINERS = "(?:(?:can|bottle|glass|cup|mug)(?: of)? )?"
private const val ENGLISH_PLAIN = "(?:water|juice|lemonade)"
private const val ENGLISH_CAFFEINE = "(?:cola|coke|tea|coffee|latte|cappuccino|matcha|matcha latte)"
private val ENGLISH_BEVERAGE = "(?:drank|had|finished) " + CONSUMPTION_QUANTITY + "(?:" +
    measured(englishAmount(PLAIN_ML, PLAIN_LITRES, PLAIN_OZ), ENGLISH_PLAIN, ENGLISH_CONTAINERS, of = "(?: of)?") +
    "|" +
    measured(
        englishAmount(CAFFEINE_ML, CAFFEINE_LITRES, CAFFEINE_OZ),
        ENGLISH_CAFFEINE,
        ENGLISH_CONTAINERS,
        of = "(?: of)?",
    ) + ")"
private const val CIGARETTE =
    "(?:(?:покурил|покурила|покурили|выкурил|выкурила|выкурили|курил|курила|курили) " +
        CONSUMPTION_QUANTITY + "(?:сигарету|сигаретку)|smoked (?:a |one |a whole )?cigarette)"
private val CONSUMPTION_STATEMENT = Regex(
    "(?:я |i )?(?:$CONSUMPTION_DAY )?(?:$RUSSIAN_BEVERAGE|$ENGLISH_BEVERAGE|$CIGARETTE)" +
        "(?: $CONSUMPTION_DAY)?(?: $CONSUMPTION_TIME)?",
)
