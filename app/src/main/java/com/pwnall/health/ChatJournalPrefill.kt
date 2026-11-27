package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/** Only arguments already bound to this event may seed its confirmation form. */
internal fun ChatJournalEvent.prefillArguments(role: String): List<ChatJournalSpan> =
    arguments.filter { it.role == role }.map { it.span }.distinct().sortedBy { it.start }

internal fun ChatJournalEvent.prefillNote(): String =
    sourceText
        ?: (listOf(anchor) + arguments.map { it.span }).distinct().sortedBy { it.start }.joinToString(" · ") { it.text }

/** The one event a form was opened with; a payload that is no JSON array — state restored damaged — seeds nothing. */
internal fun journalPrefill(payload: String?): ChatJournalEvent? =
    decodeJournalEvents(payload?.let { runCatching { JSONArray(it) }.getOrNull() }).singleOrNull()?.takeIf { event ->
        event.destination() != null && (event.sourceText?.let(event::groundedIn) != false)
    }

internal data class ChatFoodDraft(
    val state: FoodState,
    val catalogId: String? = null,
    val fatPercent: Double? = null,
) {
    val valid: Boolean get() = state.servingG > 0 && mealSaveable(state)

    fun catalogItem(): DishItem? = catalogId?.let(TOP_FOODS_BY_ID::get)?.let {
        DishItem(it, state.servingG, fatPercent)
    }
}

/** Mutual unique neighbours; an ambiguous portion stays in the source note rather than changing foods. */
internal fun ChatJournalEvent.prefillFoods(): List<ChatFoodDraft> {
    if (type != ChatJournalType.FOOD_LOG || destination() == null) return emptyList()
    val foods = prefillArguments("FOOD").ifEmpty { prefillArguments("ITEM") }
    val portions = foodMasses().filterNot { portion ->
        val preceding = sourceText?.substring(maxOf(0, portion.start - 16), portion.start).orEmpty()
        // "всего 300г" is the whole meal's, whether the span starts at "всего" or just after it.
        val total = JOURNAL_TOTAL_BEFORE.containsMatchIn(preceding) || JOURNAL_TOTAL.containsMatchIn(portion.text)
        foods.size > 1 && total
    }.oneSpanPerMass()
    val assignments = portions.mapNotNull { portion ->
        uniqueNearest(portion, foods)?.let { food -> food to portion }
    }.groupBy({ it.first }, { it.second })
    return foods.map { food ->
        val portion = assignments[food]?.singleOrNull()?.takeIf { uniqueNearest(food, portions) == it }
        ChatFoodDraft(
            FoodState(
                title = food.text,
                serving = portion?.text?.journalQuantity("mass")?.asWeightText().orEmpty(),
            ),
        )
    }
}

private val JOURNAL_TOTAL = Regex("всего|total|altogether", RegexOption.IGNORE_CASE)
private val JOURNAL_TOTAL_BEFORE = Regex("(?:${JOURNAL_TOTAL.pattern})$SPACE*$", RegexOption.IGNORE_CASE)

/** The model may tag grams as any amount role; a volume or a count never becomes a food's weight. */
private fun ChatJournalEvent.foodMasses(): List<ChatJournalSpan> = listOf("PORTION", "AMOUNT", "VALUE")
    .flatMap { prefillArguments(it) }
    .map { withQuantityUnit(it, "mass") }
    .filter { it.text.journalQuantity("mass") != null }

/** Overlapping spans that read the same mass ("около 200 г" and "200 г") are one statement of it. */
private fun List<ChatJournalSpan>.oneSpanPerMass(): List<ChatJournalSpan> =
    sortedBy { it.start }.fold(emptyList()) { kept, span ->
        val same = kept.lastOrNull()?.takeIf {
            span.start < it.end && span.text.journalQuantity("mass") == it.text.journalQuantity("mass")
        }
        if (same == null) kept + span else kept.dropLast(1) + same.copy(end = maxOf(same.end, span.end))
    }

private fun ChatJournalEvent.withQuantityUnit(span: ChatJournalSpan, kind: String): ChatJournalSpan {
    if (span.text.journalQuantity(kind) != null) return span
    val unit = uniqueNearest(span, prefillArguments("UNIT")) ?: return span
    val text = "${span.text} ${unit.text}"
    val gap = maxOf(0, span.start - unit.end, unit.start - span.end)
    return if (gap <= 2 && text.journalQuantity(kind) != null) {
        ChatJournalSpan(minOf(span.start, unit.start), maxOf(span.end, unit.end), text)
    } else {
        span
    }
}

private fun uniqueNearest(span: ChatJournalSpan, candidates: List<ChatJournalSpan>): ChatJournalSpan? {
    val distances = candidates.map { it to maxOf(0, span.start - it.end, it.start - span.end) }
    val nearest = distances.minOfOrNull { it.second } ?: return null
    return distances.filter { it.second == nearest }.singleOrNull()?.first
}

/**
 * Values are normalised only after the model supplies the role; this never infers an event from words.
 * Words around the amount are allowed ("около 200 г"), but the span holds exactly one unsigned number, and its
 * unit follows it or, when no word follows, precedes it ("грамм 200").
 */
internal fun String.journalQuantity(kind: String): Double? {
    val normalized = asciiDigits().lowercase(Locale.ROOT).replace('٫', '.')
        .replace(JOURNAL_NUMBER_WORD) { JOURNAL_NUMBER_WORDS.getValue(it.value) }
    val match = JOURNAL_QUANTITY.findAll(normalized).singleOrNull()
        ?.takeUnless { JOURNAL_SIGNED.containsMatchIn(normalized) } ?: return null
    val (before, number, after) = match.destructured
    val factor = JOURNAL_UNITS[kind]?.get(after.ifEmpty { before })
    // "1,500 ml" and "2.000 g" group thousands: nobody states a thousandth of a gram, a millilitre or a minute.
    val grouped = factor == 1.0 && number.matches(JOURNAL_THOUSANDS)
    val value = (if (grouped) number.filter(Char::isDigit) else number.replace(',', '.')).toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0 }
    return if (value != null && factor != null) (value * factor).takeIf { it.isFinite() } else null
}

private val JOURNAL_QUANTITY =
    Regex("(?:([\\p{L}\\p{M}]+)$SPACE*)?([0-9]+(?:[.,][0-9]+)?)(?:$SPACE*([\\p{L}\\p{M}]+))?")

/** A thousands' separator, not a decimal one: one to three digits before it, the first no zero, and three after. */
private val JOURNAL_THOUSANDS = Regex("[1-9][0-9]{0,2}[.,][0-9]{3}")

/** A sign, a dash or a bare decimal point on a digit: "-100г", "100-200 г" and ".5 кг" are not one amount. */
private val JOURNAL_SIGNED = Regex("[+±−\\p{Pd}][0-9]|[0-9][+±−\\p{Pd}]|(?<![0-9])[.,][0-9]")

/** Spoken amounts read as their digits; a longer number ("сто пятьдесят") keeps a word and stays unknown. */
private val JOURNAL_NUMBER_WORDS = mapOf(
    "сто" to "100", "двести" to "200", "триста" to "300", "четыреста" to "400", "пятьсот" to "500",
    "шестьсот" to "600", "семьсот" to "700", "восемьсот" to "800", "девятьсот" to "900",
    "тысяча" to "1000", "тысячу" to "1000", "полкило" to "500 г", "пол кило" to "500 г",
    "полтора кило" to "1500 г", "полтора килограмма" to "1500 г",
    "a hundred" to "100", "one hundred" to "100", "two hundred" to "200", "three hundred" to "300",
    "four hundred" to "400", "five hundred" to "500", "six hundred" to "600", "seven hundred" to "700",
    "eight hundred" to "800", "nine hundred" to "900", "half a kilo" to "500 g",
    "пол-литра" to "500 мл", "пол литра" to "500 мл", "поллитра" to "500 мл", "полтора литра" to "1500 мл",
    "half a liter" to "500 ml", "half a litre" to "500 ml",
)
private val JOURNAL_NUMBER_WORD =
    Regex(JOURNAL_NUMBER_WORDS.keys.joinToString("|", "(?<![\\p{L}\\p{M}])(?:", ")(?![\\p{L}\\p{M}])"))

private val JOURNAL_UNITS = mapOf(
    "mass" to mapOf(
        "g" to 1.0, "г" to 1.0, "гр" to 1.0, "gram" to 1.0, "grams" to 1.0,
        "грамм" to 1.0, "грамма" to 1.0, "граммов" to 1.0, "kg" to 1000.0, "кг" to 1000.0,
        "oz" to 28.349523125, "lb" to 453.59237,
        "gr" to 1.0, "gramm" to 1.0, "gramme" to 1.0, "grammes" to 1.0, "grammi" to 1.0, "gramos" to 1.0,
        "gramas" to 1.0, "gramów" to 1.0, "gramy" to 1.0, "グラム" to 1.0, "克" to 1.0, "그램" to 1.0,
        "غرام" to 1.0, "جرام" to 1.0, "ग्राम" to 1.0, "dag" to 10.0, "dkg" to 10.0,
        "kilo" to 1000.0, "kilos" to 1000.0, "kilogram" to 1000.0, "kilograms" to 1000.0,
        "kilogramm" to 1000.0, "кило" to 1000.0, "公斤" to 1000.0,
        "килограмм" to 1000.0, "килограмма" to 1000.0, "килограмму" to 1000.0, "килограммом" to 1000.0,
        "килограмме" to 1000.0, "килограммы" to 1000.0, "килограммов" to 1000.0, "килограммам" to 1000.0,
        "килограммами" to 1000.0, "килограммах" to 1000.0,
    ),
    "volume" to mapOf(
        "ml" to 1.0,
        "мл" to 1.0,
        "l" to 1000.0,
        "л" to 1000.0,
        "литр" to 1000.0,
        "литра" to 1000.0,
        "литров" to 1000.0,
        "литру" to 1000.0, "литром" to 1000.0, "литре" to 1000.0, "литры" to 1000.0, "литрам" to 1000.0,
        "литрами" to 1000.0, "литрах" to 1000.0,
        "миллилитр" to 1.0, "миллилитра" to 1.0, "миллилитров" to 1.0, "миллилитру" to 1.0, "миллилитром" to 1.0,
        "миллилитре" to 1.0, "миллилитры" to 1.0, "миллилитрам" to 1.0, "миллилитрами" to 1.0, "миллилитрах" to 1.0,
        "milliliter" to 1.0, "milliliters" to 1.0, "millilitre" to 1.0, "millilitres" to 1.0, "mililitro" to 1.0,
        "mililitros" to 1.0, "millilitro" to 1.0, "millilitri" to 1.0, "mililitr" to 1.0, "mililitry" to 1.0,
        "mililitrów" to 1.0, "mililitrů" to 1.0, "mililitre" to 1.0, "mililiter" to 1.0, "cl" to 10.0, "dl" to 100.0,
        "liter" to 1000.0, "liters" to 1000.0, "litre" to 1000.0, "litres" to 1000.0, "litro" to 1000.0,
        "litros" to 1000.0, "litri" to 1000.0, "litr" to 1000.0, "litry" to 1000.0, "litrów" to 1000.0,
        "litrů" to 1000.0, "ミリリットル" to 1.0, "リットル" to 1000.0, "毫升" to 1.0, "升" to 1000.0,
        "밀리리터" to 1.0, "리터" to 1000.0, "مل" to 1.0, "لتر" to 1000.0, "ليتر" to 1000.0,
        "मिली" to 1.0, "मिलीलीटर" to 1.0, "लीटर" to 1000.0,
    ),
    "duration" to mapOf(
        "min" to 1.0, "minute" to 1.0, "minutes" to 1.0, "мин" to 1.0,
        "минут" to 1.0, "минуты" to 1.0, "минута" to 1.0, "h" to 60.0, "hr" to 60.0,
        "hours" to 60.0, "hour" to 60.0, "ч" to 60.0, "час" to 60.0, "часа" to 60.0, "часов" to 60.0,
    ),
)

/** Water's volume in millilitres: the model states it as an amount, a value beside its unit, or a portion. */
internal fun ChatJournalEvent.prefillWaterMl(): Double? =
    listOf("AMOUNT", "VALUE", "PORTION").firstNotNullOfOrNull { prefillQuantity(it, "volume") }

/**
 * The cup a caffeine record's drink words name («капучино», «чая», "cappuccino"), so its card saves it
 * as the caffeine dialog would. A drink the dialog has none of — a latte, a cola — is none, and so is
 * any but a cappuccino where the message puts milk in it: their form opens.
 */
internal fun ChatJournalEvent.prefillDrink(): CaffeineDrink? {
    if (type != ChatJournalType.CAFFEINE_LOG) return null
    val named = prefillArguments("DRINK_KIND").joinToString(" ") { it.text }
    val drink = DRINK_WORDS.entries.firstOrNull { named.says(it.value) }?.key ?: return null
    return drink.takeIf { it.withMilk || !(sourceText ?: named).says(MILK_WORDS) }
}

/** Whether a word of [this] begins with one of [stems]; a stem of a script that runs its words together, anywhere. */
private fun String.says(stems: List<String>): Boolean {
    val lowered = lowercase(Locale.ROOT)
    val words = lowered.split(NOT_A_LETTER)
    return stems.any { stem ->
        if (stem.all { Character.UnicodeScript.of(it.code) in SPACED_SCRIPTS }) {
            words.any { it.startsWith(stem) }
        } else {
            stem in lowered
        }
    }
}

private val NOT_A_LETTER = Regex("[^\\p{L}\\p{M}]+")
private val SPACED_SCRIPTS = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC)

// Limitation: stems of the dialog's five drinks in the app's languages, not a dictionary; a drink not read opens
// the dialog. A drink before the one its name holds: 浓缩咖啡 is an espresso, 抹茶 a matcha, before 咖啡 and 茶.
private val DRINK_WORDS = mapOf(
    CaffeineDrink.CAPPUCCINO to listOf(
        "капуч", "каппуч", "cappuc", "capuc", "kapuč", "kapuç",
        "كابتشينو", "كابوتشينو", "कैपुचीनो", "カプチーノ", "卡布奇诺", "카푸치노",
    ),
    CaffeineDrink.ESPRESSO to listOf("эспрес", "espres", "expres", "إسبريسو", "एस्प्रेसो", "エスプレッソ", "浓缩咖啡", "에스프레소"),
    CaffeineDrink.MATCHA to listOf("матч", "matcha", "ماتشا", "माचा", "抹茶", "말차"),
    CaffeineDrink.COFFEE to listOf(
        "коф", "coffe", "kaffe", "café", "cafe", "caffè", "caffe", "kahve", "kaw", "kopi", "káv",
        "قهوة", "قهوه", "कॉफ़ी", "कॉफी", "コーヒー", "咖啡", "커피",
    ),
    CaffeineDrink.TEA to listOf(
        "чай", "чаю", "чая", "чаё", "tea", "tee", "thé", "té", "tè", "chá", "herbat", "teh", "çay", "čaj",
        "شاي", "चाय", "茶", "차",
    ),
)

// Milk in the cup: then its energy is the milk, which of the five only the cappuccino counts.
private val MILK_WORDS = listOf(
    "молок", "молоч", "латте", "лате", "сливк", "milk", "latte", "cream", "milch", "lait", "leche", "leite",
    "mlek", "mlék", "süt", "susu", "حليب", "दूध", "ミルク", "ラテ", "牛奶", "拿铁", "우유", "라떼",
)

internal fun ChatJournalEvent.prefillQuantity(role: String, kind: String): Double? {
    val value = prefillArguments(role).singleOrNull() ?: return null
    return prefillQuantity(value, kind)
}

private fun ChatJournalEvent.prefillQuantity(value: ChatJournalSpan, kind: String): Double? {
    val unit = uniqueNearest(value, prefillArguments("UNIT"))?.text
    return value.text.journalQuantity(kind) ?: unit?.let { "${value.text} $it".journalQuantity(kind) }
}

internal fun ChatJournalEvent.prefillCount(role: String): Int? {
    val value = prefillArguments(role).singleOrNull()?.text?.asciiDigits() ?: return null
    if (value.any { it in "-−.," }) return null
    val numbers = Regex("[0-9]+").findAll(value).toList()
    return numbers.singleOrNull()?.value?.toIntOrNull()?.takeIf { it > 0 }
}

internal fun ChatJournalEvent.prefillClock(role: String): Int? = prefillArguments(role).singleOrNull()?.text
    ?.let { clockMinutesIn(it).singleOrNull() }

/**
 * The day the event is said to be of, counted from [base] or from the day its source carries: the day
 * its one TIME opens with ([DAY_WORDS]: «вчера вечером», "yesterday at 8", 昨天晚上) or an ISO date, and
 * the day counted from with no TIME at all. A TIME that names no day this reads is none: every caller
 * then files under the day it counts from, and the card shows that day to be changed.
 */
internal fun ChatJournalEvent.prefillDay(base: LocalDate): LocalDate? {
    val day = sourceDay?.let { it.toLocalDateOrNull() } ?: base
    val time = prefillArguments("TIME").singleOrNull()?.text?.trim()?.lowercase(Locale.ROOT)?.replace('’', '\'')
        ?: return day
    val named = DAY_WORDS.firstOrNull { time.opensWith(it.first) }
        ?.takeUnless { it.first in EITHER_WAY_DAYS && attributes["phase"] != "DONE" }
    return named?.let { day.minusDays(it.second) }
        ?: time.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }?.let { it.toLocalDateOrNull() }
}

/**
 * Whether [this] opens with [words]: as whole words in a script that spaces its words, as its first
 * characters in one that runs them together ([SPACED_SCRIPTS]), where «어제는» is "yesterday" still.
 */
private fun String.opensWith(words: String): Boolean {
    val next = getOrNull(words.length)
    val whole = next == null || !next.isLetter() || Character.UnicodeScript.of(words.last().code) !in SPACED_SCRIPTS
    return startsWith(words) && whole
}

// The words a TIME opens with for today, yesterday and the day before, in the app's languages, and how
// many days back each is; the longest first, so «ieri l'altro» is not read as «ieri». Not a date parser: "last
// night", a weekday, "3 days ago" name no day here.
private val DAY_WORDS: List<Pair<String, Long>> = listOf(
    0L to listOf(
        "today", "сегодня", "اليوم", "dnes", "heute", "hoy", "aujourd'hui", "आज", "hari ini", "oggi",
        "今日", "きょう", "오늘", "dziś", "dzisiaj", "hoje", "bugün", "今天",
    ),
    1L to listOf(
        "yesterday", "вчера", "أمس", "امس", "بالأمس", "البارحة", "včera", "gestern", "ayer", "hier", "कल",
        "kemarin", "ieri", "昨日", "きのう", "어제", "어저께", "wczoraj", "ontem", "dün", "昨天",
    ),
    2L to listOf(
        "the day before yesterday", "day before yesterday", "позавчера", "أول أمس", "أول امس", "předevčírem",
        "أمس الأول", "امس الاول", "أول من أمس",
        "vorgestern", "anteayer", "antes de ayer", "avant-hier", "परसों", "kemarin dulu", "kemarin lusa",
        "l'altro ieri", "ieri l'altro", "一昨日", "おととい", "그저께", "przedwczoraj", "anteontem", "evvelsi gün", "前天",
    ),
).flatMap { (back, words) -> words.map { it to back } }.sortedByDescending { it.first.length }

// Hindi's कल is yesterday and tomorrow, परसों the day before and the day after: a day back only in what is done.
private val EITHER_WAY_DAYS = setOf("कल", "परसों")

/**
 * The catalogue's exercise this event names, from its EXERCISE and its anchor together: the shipped
 * reader reads "я бегал 1.5км" as the event "бегал" with an EXERCISE cut to "бе", a piece of a word,
 * and "i was running" with no EXERCISE at all. Names are matched
 * on the start of their words found inside the stated ones, three letters at least, so "бегал" and
 * "побегал" find "Бег, спокойный темп" and "жим лёжа" finds the bench over the leg press; ties go to the
 * catalogue's order. English the titles miss ("jogging") goes through the importer's phrases.
 */
internal fun ChatJournalEvent.prefillExercise(names: Map<String, String>): String? {
    val stated = (prefillArguments("EXERCISE") + anchor).distinct().joinToString(" ") { it.text.trim() }
        .lowercase(Locale.ROOT)
    val said = stated.letterWords()
    val best = names.entries.map { (code, title) ->
        val parts = title.lowercase(Locale.ROOT).letterWords()
        code to said.sumOf { word -> parts.maxOfOrNull { it.startFoundIn(word) }?.takeIf { it >= 3 } ?: 0 }
    }.maxByOrNull { it.second }
    return best?.takeIf { it.second > 0 }?.first
        ?: activityCodeFor(stated).takeIf { it != FALLBACK_ACTIVITY_CODE && it in names }
}

private fun String.letterWords(): List<String> = split(Regex("[^\\p{L}]+")).filter { it.length >= 3 }

/** How much of this word's start appears somewhere in [word]: "бег" in "побегал" is 3. */
private fun String.startFoundIn(word: String): Int = (length downTo 1).firstOrNull { word.contains(take(it)) } ?: 0

/** The complete reviewed rows survive recreation, including manual nutrition and catalogue choices. */
internal fun List<ChatFoodDraft>.encodeChatFoods(): String = JSONArray().apply {
    for (item in this@encodeChatFoods) {
        put(
            JSONObject().apply {
                put("title", item.state.title)
                put("subtitle", item.state.subtitle)
                put("serving", item.state.serving)
                put("protein", item.state.protein)
                put("fat", item.state.fat)
                put("carbs", item.state.carbs)
                put("energy", item.state.reportedEnergy)
                put("per100", item.state.basis.per100)
                put("extra", JSONObject(item.state.extra.mapKeys { it.key.name }))
                put("catalog", item.catalogId)
                put("fatPercent", item.fatPercent)
            },
        )
    }
}.toString()

internal fun String.decodeChatFoods(): List<ChatFoodDraft> {
    val array = JSONArray(this)
    return (0 until array.length()).map { index ->
        val row = array.getJSONObject(index)
        val extra = row.getJSONObject("extra")
        ChatFoodDraft(
            FoodState(
                title = row.getString(
                    "title",
                ),
                subtitle = row.getString("subtitle"), serving = row.getString("serving"),
                protein = row.getString("protein"), fat = row.getString("fat"), carbs = row.getString("carbs"),
                reportedEnergy = row.getString("energy"),
                basis = if (row.getBoolean("per100")) MealBasis.PER_100G else MealBasis.PER_PORTION,
                extra = ExtraNutrient.entries.filter { extra.has(it.name) }.associateWith { extra.getString(it.name) },
            ),
            catalogId = row.optString("catalog").takeIf { it.isNotEmpty() && it != "null" },
            fatPercent = row.optDouble("fatPercent").takeIf { it.isFinite() },
        )
    }
}

private const val CHAT_FOOD_PAYLOAD_KEY = "chat_food_v1"

/** Keep reviewed ingredient amounts even when nutrition was entered without a catalogue match. */
internal fun List<ChatFoodDraft>.foodDraftPayload(existing: String?): String? {
    val parsed = existing?.let { runCatching { JSONObject(it) }.getOrNull() }
    if (isEmpty() && parsed?.has(CHAT_FOOD_PAYLOAD_KEY) != true) return existing
    val payload = parsed ?: JSONObject().apply {
        existing?.let { put("original_payload", it) }
    }
    if (isEmpty()) {
        payload.remove(
            CHAT_FOOD_PAYLOAD_KEY,
        )
    } else {
        payload.put(CHAT_FOOD_PAYLOAD_KEY, JSONArray(encodeChatFoods()))
    }
    return payload.toString().takeIf { payload.length() > 0 }
}

internal fun savedChatFoods(payload: String?): List<ChatFoodDraft> = payload?.let {
    runCatching { JSONObject(it).getJSONArray(CHAT_FOOD_PAYLOAD_KEY).toString().decodeChatFoods() }.getOrNull()
}.orEmpty()

internal fun List<ChatFoodDraft>.combinedFood(): FoodState {
    fun total(value: (FoodState) -> Double): String = sumOf { value(it.state) }.asWeightText()
    return FoodState(
        title = joinToString(" + ") { it.state.title },
        serving = total { it.servingG },
        protein = total { it.portionProtein },
        fat = total { it.portionFat },
        carbs = total { it.portionCarbs },
        reportedEnergy = total { it.portionCalories },
        basis = MealBasis.PER_PORTION,
        extra = ExtraNutrient.entries.mapNotNull { nutrient ->
            val values = mapNotNull { it.state.portionExtra(nutrient) }
            if (values.size == size) nutrient to values.sum().asWeightText() else null
        }.toMap(),
    )
}

internal fun List<ChatFoodDraft>.combinedFoodSource(): String? = mapNotNull { it.catalogItem() }
    .takeIf { it.size == size && it.isNotEmpty() }?.encodeDish()
