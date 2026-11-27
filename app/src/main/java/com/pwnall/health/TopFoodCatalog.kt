package com.pwnall.health

import android.icu.text.Transliterator
import java.text.Normalizer
import java.util.Locale

/**
 * A deliberately small, offline-first food reference.
 *
 * The rows live in `src/main/resources/top_foods.csv`, one food per line, semicolon separated —
 * editable in a spreadsheet by anyone, without touching Kotlin or fighting the formatter. A Java
 * resource rather than an Android asset because the classloader reaches it from both the APK and a
 * plain JVM unit test, so [TOP_FOODS] stays a value and needs no `Context`.
 *
 * Values are representative per-100 g figures from USDA FoodData Central (FNDDS 2021-2023). They
 * are a fast starting point, not a claim that every restaurant, recipe or packet has the same
 * formulation. The editor keeps every number editable after a pick.
 */
internal data class CatalogText(val ru: String, val en: String) {
    /**
     * The long tail of the catalogue comes from USDA and carries English only — some 2 600 of the
     * 4 995 rows have no Russian name, past what anyone translates by hand — so a missing side
     * falls back to the one that exists rather than showing a person an empty row. The app's other
     * fourteen languages read the English: the catalogue has no column of theirs, and nobody has
     * translated five thousand food names into them.
     */
    fun localized(locale: Locale): String {
        val wanted = if (locale.language == "ru") ru else en
        return wanted.ifBlank { if (locale.language == "ru") en else ru }
    }
}

internal data class CatalogNutrition(
    val kcal: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null,
    val cholesterolMg: Double? = null,
    /**
     * The fraction of [fatG] the lipid guidance is actually written about, where USDA had it.
     *
     * Two thirds of the catalogue carries it. The rest is blank rather than guessed: a share of
     * total fat assumed from the food's category would be a number nobody measured, and it would
     * end up in front of somebody reading it beside a cholesterol result.
     */
    val saturatedFatG: Double? = null,
)

internal data class CatalogPortion(val grams: Double, val label: CatalogText)

/**
 * The same food at another fat content, per 100 g.
 *
 * Curd is sold at 0, 2, 5, 9 and 18 percent and it is one product, not five rows. Take the fat out
 * and what is left concentrates: 100 g of 18% curd holds 82 g of everything else, 100 g of the
 * skimmed one holds 100 g of it, so the protein per 100 g goes up as the fat comes down. That
 * ratio is the whole calculation — it is why skimmed milk lists more protein than whole.
 *
 * Cholesterol is the exception that rides with the fat rather than against it.
 */
internal fun CatalogNutrition.atFatPercent(percent: Double): CatalogNutrition {
    val scale = fatFreeScale(percent) ?: return this
    val fatRatio = if (fatG > 0.0) percent / fatG else 0.0
    return CatalogNutrition(
        kcal = (kcal - FAT_KCAL_PER_G * fatG) * scale + FAT_KCAL_PER_G * percent,
        proteinG = proteinG * scale,
        fatG = percent,
        carbsG = carbsG * scale,
        fiberG = fiberG?.times(scale),
        sugarG = sugarG?.times(scale),
        sodiumMg = sodiumMg?.times(scale),
        cholesterolMg = cholesterolMg?.times(fatRatio),
        // With the fat rather than against it, like cholesterol: take fat out of curd and the
        // saturated share goes with it, while the protein left behind concentrates.
        saturatedFatG = saturatedFatG?.times(fatRatio),
    )
}

/**
 * The ratio [atFatPercent] is built on: how much the fat-free part of 100 g grows or shrinks when
 * the fat goes to [percent]. Null for a row that is all fat, which has no fat-free part to scale.
 * A meal's minerals and water-soluble vitamins are scaled by it too, the way protein is.
 */
internal fun CatalogNutrition.fatFreeScale(percent: Double): Double? {
    val rest = 100.0 - fatG
    return if (rest <= 0.0) null else (100.0 - percent) / rest
}

private const val FAT_KCAL_PER_G = 9.0

/**
 * Whether this portion is something a person counts rather than weighs.
 *
 * The catalogue's portions come from two places and both lie in their own way. A hand-written row
 * with no label at all gets the word "portion" put on it, which says nothing — Buckwheat's is a
 * bare 150 g. And USDA's most common label, on three thousand rows, is literally "Quantity not
 * specified", followed by "oz", "cup" and "1 cubic inch": units of measure, and imperial ones at
 * that, in an app that weighs in grams. None of those is a thing to tap a button for.
 */
internal val CatalogPortion.stepsInPieces: Boolean
    get() {
        val name = label.en.lowercase(Locale.ROOT)
        val tokens = name.split(NON_LETTERS)
        return name.isNotBlank() && MEASURE_WORDS.none(name::contains) && MEASURE_UNITS.none(tokens::contains)
    }

private val MEASURE_WORDS = listOf(
    "quantity", "serving", "порция", "cup", "чашк", "tbsp", "tablespoon", "teaspoon",
    "ложк", "cubic", "gram", "грамм", "small", "medium", "large",
)

/** These hide inside ordinary words — "oz" in "frozen", "fl" in "flatbread" — so they only match as whole tokens. */
private val MEASURE_UNITS = listOf("oz", "fl", "ml", "мл", "tsp")

private val NON_LETTERS = Regex("[^\\p{L}]+")

/**
 * A label that names a measure rather than a helping — "oz", "1 cup", "1 cubic inch", "Guideline
 * amount per fl oz" — is not a portion anyone chose, only the unit USDA weighed in. Narrower than
 * [stepsInPieces]'s list on purpose: "1 serving", "1 medium" and "small" are helpings too, just not
 * countable ones, and they make fine places for the weight form to start.
 */
private val CatalogPortion.isMeasure: Boolean
    get() {
        val name = label.en.lowercase(Locale.ROOT)
        return MEASURE_LABEL_WORDS.any(name::contains) || MEASURE_LABEL_UNITS.any(name.split(NON_LETTERS)::contains)
    }

/** The measures a spoonable food is eaten by; among measures alone, the spoon is the helping. */
private val CatalogPortion.isSpoon: Boolean
    get() = SPOON_WORDS.any(label.en.lowercase(Locale.ROOT)::contains)

/** A pound is what the butcher wraps, never what is on the plate — even under the weight ceiling. */
private val CatalogPortion.isShopWeight: Boolean
    get() = label.en.lowercase(Locale.ROOT).split(NON_LETTERS).any { it == "lb" || it == "pound" || it == "pounds" }

private val SPOON_WORDS = listOf("tbsp", "tablespoon", "teaspoon", "tsp", "ложк")
private val MEASURE_LABEL_WORDS = SPOON_WORDS + listOf(
    "cup", "чашк", "cubic", "surface", "guideline", "gram", "грамм", "quart", "pint", "gallon", "liter", "litre",
)
private val MEASURE_LABEL_UNITS = listOf("oz", "fl", "ml", "мл")

private const val MIN_DEFAULT_PORTION_G = 10.0
private const val MAX_DEFAULT_PORTION_G = 500.0

internal data class TopFood(
    val id: String,
    val group: FoodCategory,
    val name: CatalogText,
    val detail: CatalogText,
    val nutrition: CatalogNutrition,
    val portions: List<CatalogPortion>,
    val aliases: List<String>,
    /** The fat percentages this product is sold at, or empty for a food that comes only one way. */
    val fatSteps: List<Double> = emptyList(),
) {
    /**
     * The portion the weight form starts from.
     *
     * tools/build-top-foods.py writes a row's portions smallest first, and USDA's smallest is usually a measure
     * rather than a helping — "1 surface inch" of naan at 2.4 g, "1 shoestring" of fries at 2 g,
     * oat milk "per fl oz" — so the first portion that is a helping wins: named as something a
     * person is served, at least ten grams, and at most half a kilogram, because a "roast" of
     * 900 g or a pound of rib eye is what the shop sells, not what the plate holds. A food that
     * comes only in measures is eaten by the spoon when a spoon is among them — oil, syrup, icing —
     * and otherwise by the largest vessel that is still a helping: the cup of milk, not the fluid
     * ounce listed beside it.
     */
    val defaultPortion: CatalogPortion
        get() {
            val servable = portions.filter { it.grams <= MAX_DEFAULT_PORTION_G && !it.isShopWeight }
            val helpings = servable.filter { it.grams >= MIN_DEFAULT_PORTION_G }
            return helpings.firstOrNull { !it.isMeasure }
                ?: helpings.filter { it.isSpoon }.maxByOrNull { it.grams }
                ?: servable.maxByOrNull { it.grams }
                ?: portions.first()
        }

    fun searchablePhrases(): List<String> = buildList {
        add(name.ru)
        add(name.en)
        add(detail.ru)
        add(detail.en)
        addAll(aliases)
    }

    /**
     * The same phrases, normalised once and kept. The catalogue is 4 995 rows and every keystroke
     * walks all of them: normalising on the fly would mean tens of thousands of Unicode decompositions
     * per letter typed, which is a visibly janky list on a mid-range phone.
     */
    val searchIndex: List<String> by lazy {
        searchablePhrases().map(::normalizeFoodText).filter(String::isNotEmpty)
    }

    /** The phrases as their words, split once: every keystroke asks for a word that begins with the query. */
    val searchWords: List<List<String>> by lazy { searchIndex.map { it.split(' ') } }

    /** Every distinct word of every phrase, for the misspelling allowance; split once rather than per letter. */
    val searchTokens: List<String> by lazy { searchWords.flatten().distinct() }

    /**
     * The words of the two names alone, normalised once: a photograph asks [catalogNameMatch] about
     * every word of every label it was offered, and each question would otherwise walk all 4 995 rows
     * folding both names again.
     */
    val nameWords: Set<String> by lazy {
        listOf(name.ru, name.en).flatMap { normalizeFoodText(it).split(' ') }.toSet()
    }

    /** The names and aliases without the details, for the Latin spelling of a query: see [latinSearchScore]. */
    val namePhrases: List<String> by lazy {
        (listOf(name.ru, name.en) + aliases).map(::normalizeFoodText).filter(String::isNotEmpty)
    }

    val namePhraseWords: Set<String> by lazy { namePhrases.flatMap { it.split(' ') }.toSet() }
}

/** Popular first: an empty catalogue opens on the foods most likely to be logged in one tap. */
internal val TOP_FOODS: List<TopFood> by lazy { parseCatalog() }

/** The groups the catalogue actually fills, in file order — the picker's filter chips. */
internal val TOP_FOOD_GROUPS: List<FoodCategory> by lazy { TOP_FOODS.map(TopFood::group).distinct() }

/**
 * The catalogue by id.
 *
 * `TOP_FOODS.firstOrNull { it.id == … }` walks 4 995 rows, and the places that ask are the
 * ones that ask repeatedly: a dish resolves every one of its ingredients, and a rail of chips
 * resolves every chip. Built once, on first use, like the catalogue itself.
 */
internal val TOP_FOODS_BY_ID: Map<String, TopFood> by lazy { TOP_FOODS.associateBy(TopFood::id) }

/**
 * Loads the catalogue and every row's search phrases now, for a caller that is off the main thread,
 * and says how many phrases stand ready.
 *
 * All of it is built on first use, and the first use would otherwise be a composition: the food hub asks for the
 * picture of a recent meal and the editor for a kept food's row, on the main thread, where a
 * megabyte of text to parse and twenty-five thousand phrases to fold are frames nobody draws. The
 * app's window calls this as it opens, so a page that asks later finds the answer standing, and
 * one that asks sooner waits out the rest of this pass rather than starting its own.
 */
internal fun warmFoodCatalog(): Int = TOP_FOODS_BY_ID.values.sumOf { it.searchIndex.size }

private const val CATALOG_RESOURCE = "/top_foods.csv"
private const val CATALOG_COLUMNS = 18

/** The hand-edited tables — this one, the dish templates, the model's label map — all load here. */
internal fun catalogResource(path: String): String =
    checkNotNull(TopFood::class.java.getResourceAsStream(path)) {
        "$path is missing from the build"
    }.bufferedReader().use { it.readText() }

private fun parseCatalog(): List<TopFood> =
    catalogResource(CATALOG_RESOURCE).lineSequence().drop(1).filter(String::isNotBlank).map(::parseFood).toList()

private fun parseFood(line: String): TopFood {
    val cell = line.split(';')
    require(cell.size == CATALOG_COLUMNS) { "$CATALOG_RESOURCE: expected $CATALOG_COLUMNS columns in `$line`" }
    return TopFood(
        id = cell[0],
        group = FoodCategory.valueOf(cell[1]),
        name = CatalogText(cell[2], cell[3]),
        detail = CatalogText(cell[4], cell[5]),
        nutrition = CatalogNutrition(
            kcal = cell[6].toDouble(),
            proteinG = cell[7].toDouble(),
            fatG = cell[8].toDouble(),
            carbsG = cell[9].toDouble(),
            fiberG = cell[10].toDoubleOrNull(),
            sugarG = cell[11].toDoubleOrNull(),
            sodiumMg = cell[12].toDoubleOrNull(),
            cholesterolMg = cell[13].toDoubleOrNull(),
            saturatedFatG = cell[17].toDoubleOrNull(),
        ),
        portions = cell[14].split('|').map(::parsePortion),
        aliases = cell[15].split(',').filter(String::isNotBlank),
        fatSteps = cell[16].split('|').mapNotNull(String::toDoubleOrNull),
    )
}

/** `205=1 бургер=1 burger`, or a bare `150` for a portion with nothing better to call it. */
private fun parsePortion(field: String): CatalogPortion {
    val part = field.split('=')
    return CatalogPortion(
        grams = part[0].toDouble(),
        label = if (part.size == 3) CatalogText(part[1], part[2]) else DEFAULT_PORTION,
    )
}

private val DEFAULT_PORTION = CatalogText("порция", "serving")

/** Search both languages and aliases; a two-edit allowance catches `fired fries` and `chiken`. */
internal fun searchTopFoods(
    query: String,
    group: FoodCategory? = null,
    limit: Int = 40,
    groups: Set<FoodCategory>? = null,
): List<TopFood> {
    val candidates = TOP_FOODS.withIndex().filter {
        (group == null || it.value.group == group) && (groups == null || it.value.group in groups)
    }
    val normalized = normalizeFoodText(query)
    if (normalized.isEmpty()) return candidates.take(limit.coerceAtLeast(0)).map { it.value }

    val queryTokens = normalized.split(' ').filter(String::isNotEmpty)
    val direct = candidates.mapNotNull { indexed ->
        foodSearchScore(indexed.value, normalized, queryTokens)?.let { indexed.index to it }
    }
    // After every direct match, and only when they leave room: a spelling ICU guessed never outranks
    // a name the person typed, and is not worked out for a page the direct matches already fill.
    val spelt = latinSpelling(query)?.takeIf { direct.size < limit }?.let { text ->
        val tokens = text.split(' ')
        val matched = direct.mapTo(HashSet()) { it.first }
        candidates.mapNotNull { indexed ->
            if (indexed.index in matched) return@mapNotNull null
            latinSearchScore(indexed.value, text, tokens)?.let { indexed.index to it }
        }
    }.orEmpty()
    val byScore = compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first }
    return (direct.sortedWith(byScore) + spelt.sortedWith(byScore))
        .take(limit.coerceAtLeast(0))
        .map { TOP_FOODS[it.first] }
}

/** How well [query] as typed matches [food], lower first; null for no match. */
internal fun foodSearchScore(food: TopFood, query: String, queryTokens: List<String>): Int? {
    val phrases = food.searchIndex
    val directScore = when {
        phrases.any { it == query } -> 0
        phrases.any { it.startsWith(query) } -> 5
        food.searchWords.any { words -> words.any { word -> word.startsWith(query) } } -> 10
        phrases.any { it.contains(query) } -> 15
        else -> null
    }
    if (directScore != null) return directScore

    val targetTokens = food.searchTokens
    val tokenScores = queryTokens.map { queryToken ->
        targetTokens.minOfOrNull { target -> tokenDistance(queryToken, target) } ?: NO_MATCH
    }
    return if (tokenScores.any { it >= NO_MATCH }) null else 30 + tokenScores.sum()
}

private fun tokenDistance(query: String, target: String): Int = when {
    query == target -> 0

    // Both sides must be a real word before a prefix counts. "McDonald's" normalises to
    // "mcdonald s", and that stray "s" would make Big Mac a prefix match for every food
    // beginning with one — invisible while a person is typing and reading the list, fatal when
    // something automatic picks the first row.
    (target.length >= PREFIX_MIN && query.startsWith(target)) ||
        (query.length >= PREFIX_MIN && target.startsWith(query)) -> 1

    query.length >= 4 && levenshtein(query, target) <= typoAllowance(query.length) -> {
        2 + levenshtein(query, target)
    }

    else -> NO_MATCH
}

private fun typoAllowance(length: Int): Int = if (length >= 5) 2 else 1

/**
 * A query in another script, spelt in Latin letters for the English names and aliases: "гуакамоле"
 * reaches guacamole. Null for a query already in Latin, or where there is no ICU.
 *
 * It takes the query as typed: [normalizeFoodText] strips the combining marks, which would turn «й»
 * into «и» ("iogurt") and ビ into ヒ before ICU saw them.
 */
internal fun latinSpelling(query: String): String? {
    if (query.none { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }) {
        return null
    }
    // BGN writes «ь» and «ъ» as ' and ", which would split "pel'meni" in two; English drops them.
    return LATIN_SPELLING?.let { normalizeFoodText(it.transliterate(query).replace(PRIMES, "")) }
        ?.takeIf(String::isNotEmpty)
}

/**
 * A guessed spelling is a looser key than a typed one, so it counts only from the start of a name
 * or alias, or of one of their words when the word typed has four letters or more, never inside a
 * word or in a detail, and forgives one slip only in a word of six. Scored as loosely as a typed
 * query, "мед" as "med" would list MCT oil and smørrebrød "med sild", "сало" as "salo" salt and
 * pasta, and "брауни" as "brauni" every bran.
 */
private fun latinSearchScore(food: TopFood, query: String, queryTokens: List<String>): Int? {
    val names = food.namePhrases
    if (names.any { it == query }) return 0
    if (names.any { it.startsWith(query) }) return 5
    val distances = queryTokens.map { token ->
        food.namePhraseWords.minOfOrNull { word ->
            when {
                token.length < LATIN_WORD_MIN -> NO_MATCH
                word.startsWith(token) -> 0
                token.length >= LATIN_TYPO_MIN && levenshtein(token, word) == 1 -> 1
                else -> NO_MATCH
            }
        } ?: NO_MATCH
    }
    return if (distances.any { it >= NO_MATCH }) null else 10 + distances.sum()
}

/**
 * Russian by BGN/PCGN, which spells «х» kh, «ж» zh and «й» y as English borrows them, and every other
 * script by ICU's default. Built once: an ICU transliterator is safe to share between the searches
 * that overlap on Dispatchers.Default (its rule-based steps lock their own data). Null where the
 * platform lacks ICU, as on the plain JVM the unit tests run on, and search matches directly.
 */
private val LATIN_SPELLING: Transliterator? by lazy {
    listOf("Russian-Latin/BGN", "Cyrillic-Latin").firstNotNullOfOrNull { cyrillic ->
        runCatching { Transliterator.getInstance("$cyrillic; Any-Latin; Latin-ASCII") }.getOrNull()
    }
}

private const val LATIN_WORD_MIN = 4
private const val LATIN_TYPO_MIN = 6
private val PRIMES = Regex("['\"]")

/**
 * Exact lookup, for when nothing human is watching.
 *
 * [searchTopFoods] is generous on purpose — a person typing "chiken" wants chicken, and they can see
 * and reject whatever comes back. A label arriving from a classifier gets no such review, so this
 * path takes equality and nothing else. The whole phrase first: "Mashed potato" *is* a catalogue row.
 */
internal fun catalogPhraseMatch(phrase: String): TopFood? {
    val needle = normalizeFoodText(phrase)
    if (needle.length < CATALOG_WORD_MIN) return null
    return TOP_FOODS.firstOrNull { food -> food.searchIndex.any { it == needle } }
}

/**
 * Then one word against the *names* only, for labels the catalogue has no row for: "Coconut rice"
 * reaches rice, "Orange chicken" reaches chicken breast, "Bazin" reaches nothing.
 *
 * Names only, because aliases carry multi-word phrases whose individual words belong to no one in
 * particular — "beet soup" on borscht would make every one of the model's 45 soups borscht, and
 * "chicken mcnuggets" all 35 of its chicken dishes nuggets. Catalogue order breaks ties, and
 * that order is by how often the food is eaten.
 */
internal fun catalogNameMatch(word: String): TopFood? {
    val needle = normalizeFoodText(word)
    if (needle.length < CATALOG_WORD_MIN) return null
    return TOP_FOODS.firstOrNull { needle in it.nameWords }
}

private const val CATALOG_WORD_MIN = 3
private const val PREFIX_MIN = 3

private fun levenshtein(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { leftIndex, leftChar ->
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        right.forEachIndexed { rightIndex, rightChar ->
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
            )
        }
        previous = current
    }
    return previous[right.length]
}

private fun normalizeFoodText(value: String): String = Normalizer.normalize(
    value.lowercase(Locale.ROOT).replace('ё', 'е'),
    Normalizer.Form.NFD,
).replace(DIACRITICS, "")
    .replace(NON_ALPHANUMERIC, " ")
    .trim()
    .replace(SPACES, " ")

private const val NO_MATCH = 1_000
private val DIACRITICS = Regex("\\p{Mn}+")
private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{Nd}]+")
private val SPACES = Regex("$SPACE+")
