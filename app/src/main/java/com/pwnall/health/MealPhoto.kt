package com.pwnall.health

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What a photograph of a plate turns into before the person edits it.
 *
 * The classifiers ([FoodRecognizer]) answer one question well — *what is this* — and cannot answer
 * the one people actually log: *how many grams of what*. Weight is not observable from a single RGB
 * frame without a scale reference, and the published attempts that do have depth still land around
 * ±17% on mass. So nothing here pretends to weigh anything. The pipeline is:
 *
 * ```text
 * label ("Ramen")   →  dish template     ┐
 * label ("banana")  →  product mapping   ├→  catalogue products at typical grams  →  editor
 * label ("Chaudin") →  nothing at all    ┘
 * ```
 *
 * The tables are what carry the accuracy, and they are hand-written CSVs rather than models
 * precisely because a person can fix a row in ten seconds. 2 023 dish classes meet a catalogue of
 * about five thousand rows, and the last line still matters as much as the first: more than half
 * of the vocabulary reaches a product, and the rest — Chaudin, Kondowole, Funge — resolves to
 * nothing on purpose.
 *
 * Everything produced here is an opening bid: [DishGuess.items] lands in a sheet where every gram
 * figure is editable, rows can be dropped, and missing products can be added from the same
 * catalogue the manual form uses.
 */
internal data class DishItem(
    val food: TopFood,
    val grams: Double,
    /**
     * Which fat content of it, for the products a shop sells at several. Curd at 0, 2, 5, 9 and 18
     * percent is one catalogue row carrying a rail of percentages, and the one chosen moves every
     * figure that row contributes — so the choice travels beside the weight rather than being
     * applied once and forgotten. Null is the ordinary case: a food that comes only one way.
     */
    val fatPercent: Double? = null,
)

/**
 * What one row is worth per 100 g — the catalogue's own figures, or that product recalculated to
 * the fat content somebody picked for it. Everything that sums a dish goes through here, so a
 * chosen percentage cannot reach the calories and miss the protein.
 */
internal val DishItem.nutrition: CatalogNutrition
    get() = fatPercent?.let(food.nutrition::atFatPercent) ?: food.nutrition

/**
 * A dish as one line of text: `id=grams`, pipe separated, in the order it was assembled — or
 * `id=grams=fat` for a row whose fat content was chosen rather than taken as it came.
 *
 * The same shape the hand-written dish templates are already written in, so there is one format
 * for "a list of catalogue rows and how much of each" rather than two. The figures are not stored
 * with it — they are recomputed from the catalogue on the way back, so a corrected calorie count
 * reaches every dish that was built out of that row.
 *
 * The third field is written only where there is one to write. A row of two fields — every row
 * whose fat content was taken as it came, in the database and in the backups restored from — means
 * the product as the catalogue has it, and has to decode to that rather than to nothing.
 */
internal fun List<DishItem>.encodeDish(): String = joinToString("|") { item ->
    val fat = item.fatPercent?.let { "=$it" }.orEmpty()
    "${item.food.id}=${item.grams}$fat"
}

/**
 * The way back. A row the catalogue no longer carries is dropped rather than faked, and so is a
 * weight that is no amount of anything: the line comes from the database and from restored backups,
 * and toDouble reads "NaN", "Infinity" and "-5" as readily as "100.0" — into every sum of the dish.
 */
internal fun String.decodeDish(): List<DishItem> = split('|').mapNotNull { part ->
    val field = part.split('=')
    val food = TOP_FOODS_BY_ID[field[0]] ?: return@mapNotNull null
    val grams = field.getOrNull(1)?.storedAmount() ?: return@mapNotNull null
    DishItem(food, grams, field.getOrNull(2)?.storedAmount())
}

private fun String.storedAmount(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

/** What a saved dish is filed under, so saving the same name twice replaces it. */
internal fun savedDishKey(name: String): String = name.trim().lowercase()

/**
 * What the plate recogniser is doing, in the same spirit as the label reader's steps: the photo
 * model runs over a decoded photograph, and the catalogue is searched for whatever it names. Seconds
 * on a mid-range phone, and a spinner spends them saying nothing.
 */
internal sealed interface DishStep {
    data object Decoding : DishStep

    /** The photo model running; [dishes] picks the step's wording, the dishes or the plain products. */
    data class Looking(val dishes: Boolean) : DishStep

    data class Guessed(val name: CatalogText, val confidence: Float, val items: Int) : DishStep

    data object Empty : DishStep
}

/**
 * One reading of the photograph: what the model thinks the dish is, and what that implies is on
 * the plate. Several are offered at once — the second guess being right is common enough that
 * making the person retake the photo would be the wrong answer.
 */
internal data class DishGuess(
    /** The model's own English label, kept so the sheet can show what was actually recognised. */
    val label: String,
    val name: CatalogText,
    val confidence: Float,
    val items: List<DishItem>,
)

/**
 * Class index → label, straight from the model's own label map, so index 0 is its background class.
 *
 * A Java resource rather than an asset for the same reason as the food catalogue: a plain JVM unit
 * test can then check every template against the labels the model can actually emit.
 */
internal val FOOD_LABELS: List<String> by lazy { labelsFrom(LABELS_RESOURCE) }

/**
 * The same, for the photo model's product answer ([PhotoModel]): the foods it was trained on, in the order it
 * emits them. The resource keeps the ImageNet name of the product model's label file.
 */
internal val IMAGENET_LABELS: List<String> by lazy { labelsFrom(IMAGENET_RESOURCE) }

private fun labelsFrom(resource: String): List<String> =
    catalogResource(resource).lineSequence().drop(1).filter(String::isNotBlank)
        .map { it.substringAfter(',') }
        .toList()

/**
 * Product-model class → catalogue product, from product_labels.csv.
 *
 * A list rather than a search: each class is linked once, in that file, to a catalogue row with the same name or
 * alias or to one chosen for it, and a class without a row is never shown. The model learns
 * those classes anyway; it only means the catalogue has no product to open for them.
 */
private val PRODUCT_MAP: Map<String, TopFood> by lazy {
    catalogResource(PRODUCTS_RESOURCE).lineSequence().drop(1).filter(String::isNotBlank).associate { line ->
        val cell = line.split(';')
        require(cell.size == PRODUCT_COLUMNS) { "$PRODUCTS_RESOURCE: expected $PRODUCT_COLUMNS columns in `$line`" }
        val food = TOP_FOODS.firstOrNull { it.id == cell[1] }
        requireNotNull(food) { "$PRODUCTS_RESOURCE: `${cell[1]}` is not a catalogue id" }
        cell[0] to food
    }
}

/**
 * The photo model's raw output for one photograph.
 *
 * Two answers that fail in opposite directions, from one network. The dish answer knows 2 023 named dishes and
 * not one plain ingredient — it has no class for an apple, an egg or a chicken breast. The product answer knows
 * 459 foods from photographs of food — the plain ones cold — and has never heard of «окрошка». Measured on test
 * photographs: recognition_samples.csv, read in FoodRecognitionTest.
 */
internal class PhotoScores(val dishes: FloatArray, val products: FloatArray)

/**
 * What the sheet offers for one photograph: the best dishes and the best plain foods side by side, and the one it
 * opens on.
 *
 * Two rows rather than one merged list. A soup whose tomato the product answer is surer of still shows the soup
 * one tap away, and a plate of rice still shows the rice when the dish answer guesses a pilaf.
 */
internal data class PhotoGuesses(val dishes: List<DishGuess>, val products: List<DishGuess>) {
    /** The guess the sheet opens on: whichever row's best answer is the surer one (a tie goes to the dish). */
    val chosen: DishGuess?
        get() = listOfNotNull(dishes.firstOrNull(), products.firstOrNull()).maxByOrNull { it.confidence }

    val isEmpty: Boolean get() = dishes.isEmpty() && products.isEmpty()

    /** Both rows as one list, dishes first: where the sheet counts the guess it stands on. */
    val all: List<DishGuess> get() = dishes + products

    /**
     * The guesses as a saved state carries them: for each, the row it stands in, the model's label and its score.
     * [restoredGuesses] makes the same guesses of them again, so a sheet rebuilt with its activity — the phone
     * turned over a plate — comes back without the model being run on the photograph a second time.
     */
    fun saved(): List<String> =
        dishes.flatMap { listOf(SAVED_DISH, it.label, it.confidence.toString()) } +
            products.flatMap { listOf(SAVED_PRODUCT, it.label, it.confidence.toString()) }
}

private const val SAVED_DISH = "DISH"
private const val SAVED_PRODUCT = "PRODUCT"

/** A guess is three cells of [PhotoGuesses.saved]: its row, its label, its score. */
private const val SAVED_GUESS_CELLS = 3

/**
 * The guesses back out of [PhotoGuesses.saved], through the tables that made them: [dishFor] and [productFor]
 * answer the same for the same label. One this build's tables no longer resolve is left out.
 */
internal fun restoredGuesses(saved: List<String>): PhotoGuesses {
    val cells = saved.chunked(SAVED_GUESS_CELLS).filter { it.size == SAVED_GUESS_CELLS }
    fun row(kind: String, guess: (String, Float) -> DishGuess?): List<DishGuess> = cells
        .filter { it[0] == kind }
        .mapNotNull { (_, label, score) -> score.toFloatOrNull()?.let { guess(label, it) } }
    return PhotoGuesses(row(SAVED_DISH, ::dishFor), row(SAVED_PRODUCT, ::productFor))
}

/**
 * Reads the photo model's two answers as two short rows of editable guesses, each best first.
 *
 * Candidates that resolve to nothing in the catalogue are dropped rather than shown empty: the dish model knows
 * 2 023 dishes and hundreds of them name nothing the catalogue holds, so "Chaudin" scoring third is noise to
 * everyone except the person who would have to close a dialog about it.
 */
internal fun photoGuesses(
    scores: PhotoScores,
    limit: Int = MAX_GUESSES,
    onStep: (DishStep) -> Unit = {},
): PhotoGuesses {
    val dishes = scores.dishes.withIndex()
        // Index 0 is the dish model's background class: not a dish, and it can rank anywhere.
        .filter { it.index != 0 && it.value >= MIN_CONFIDENCE }
        .mapNotNull { (index, score) -> FOOD_LABELS.getOrNull(index)?.let { dishFor(it, score) } }
        .sortedByDescending { it.confidence }
        .distinctBy { it.name.en }
        .take(limit)
    val products = scores.products.withIndex()
        .filter { it.value >= MIN_PRODUCT_CONFIDENCE }
        .mapNotNull { (index, score) -> IMAGENET_LABELS.getOrNull(index)?.let { productFor(it, score) } }
        .sortedByDescending { it.confidence }
        .distinctBy { it.name.en }
        .take(limit)
    val guesses = PhotoGuesses(dishes, products)
    (dishes + products).sortedByDescending { it.confidence }
        .forEach { onStep(DishStep.Guessed(it.name, it.confidence, it.items.size)) }
    if (guesses.isEmpty) onStep(DishStep.Empty)
    return guesses
}

/** A template if one covers this dish, otherwise the single catalogue product the label names. */
internal fun dishFor(label: String, confidence: Float): DishGuess? {
    // 22 of the labels are bare Knowledge Graph ids — "/g/11b8_rxx4d" — with no name to show.
    if (label.startsWith('/')) return null
    val lower = label.lowercase(Locale.ROOT)
    val words = lower.split(' ', '-', ',', '(', ')').filter(String::isNotEmpty)
    val template = DISH_TEMPLATES.firstOrNull { it.matches(lower, words) }
    if (template != null) return DishGuess(label, template.name, confidence, template.items)
    return catalogMatch(label)?.let { food -> singleProduct(label, food, confidence) }
}

/**
 * The product model's classes go through [PRODUCT_MAP] rather than a search: each class is linked once,
 * in product_labels.csv, and a class the catalogue cannot open never reaches the sheet.
 */
internal fun productFor(label: String, confidence: Float): DishGuess? =
    PRODUCT_MAP[label]?.let { food -> singleProduct(label, food, confidence) }

private fun singleProduct(label: String, food: TopFood, confidence: Float): DishGuess =
    DishGuess(label, food.name, confidence, listOf(DishItem(food, food.defaultPortion.grams)))

/**
 * The labels are fine-grained English dish names — "Neapolitan pizza", "Mashed potato", "Coconut
 * rice" — so the whole phrase almost never names a catalogue row, but its head noun does. English
 * puts that noun last: what "Neapolitan pizza" *is* is a pizza.
 *
 * Whole words only, through [catalogNameMatch]. The forgiving search behind the catalogue's own
 * box would answer "Sciusceddu" with Big Mac and "Bazin" with bacon — 205 of the model's 2 023
 * labels would land on Big Mac that way. A wrong product presented as a reading is worse than
 * the dialog admitting it does not know.
 */
private fun catalogMatch(label: String): TopFood? = catalogPhraseMatch(label)
    ?: label.split(' ', '-', ',')
        .filter { it.length >= MATCHABLE_WORD }
        .asReversed()
        .firstNotNullOfOrNull(::catalogNameMatch)

/**
 * Portion size, the one thing about weight a photograph can be asked for honestly.
 *
 * A template holds a middle portion; a small bowl and a large bowl of the same dish differ by
 * about a factor of two, which is the range these three cover. Grams land on a multiple of five —
 * "115 г" reads as an estimate, "117,3 г" reads as a measurement nobody took.
 *
 * A row at zero stays at zero: that is a person having cleared the field, and a change of portion
 * size must not quietly resurrect it as five grams.
 */
internal fun List<DishItem>.scaledBy(factor: Double): List<DishItem> = map { item ->
    if (item.grams <= 0.0) {
        item
    } else {
        item.copy(grams = ((item.grams * factor / GRAM_STEP).roundToInt() * GRAM_STEP).coerceAtLeast(GRAM_STEP))
    }
}

/**
 * Collapses the reviewed products into the meal form.
 *
 * One journal entry, not one per product: the day's log wants "рамен, 235 г" and the person wants
 * one row to delete if they change their mind. The breakdown has already done its job by the time
 * this runs — it is what made the totals defensible — and the totals stay editable afterwards.
 *
 * [title] and [subtitle] arrive already localised because the strings live in resources.
 */
internal fun FoodState.withPhotoDish(title: String, subtitle: String, items: List<DishItem>): FoodState {
    fun total(pick: (CatalogNutrition) -> Double): Double =
        items.sumOf { pick(it.nutrition) * it.grams / 100.0 }

    // An optional nutrient is only summed when every product reports it: a fibre figure covering
    // three products out of four is not a smaller number, it is a wrong one.
    fun optional(pick: (CatalogNutrition) -> Double?): String? = items
        .mapNotNull { item -> pick(item.nutrition)?.times(item.grams / 100.0) }
        .takeIf { it.size == items.size && it.isNotEmpty() }
        ?.sum()
        ?.asGramText()

    return copy(
        title = title,
        subtitle = subtitle,
        serving = items.sumOf(DishItem::grams).asWeightText(),
        basis = MealBasis.PER_PORTION,
        protein = total { it.proteinG }.asGramText(),
        fat = total { it.fatG }.asGramText(),
        carbs = total { it.carbsG }.asGramText(),
        reportedEnergy = total { it.kcal }.asGramText(),
        extra = buildMap {
            optional { it.fiberG }?.let { put(ExtraNutrient.FIBER, it) }
            optional { it.sugarG }?.let { put(ExtraNutrient.SUGAR, it) }
            // Summed like the rest: left out, every dish built by hand or read off a photo would save no
            // saturated fat, and the six weeks before a lipid panel would lose every one of them.
            optional { it.saturatedFatG }?.let { put(ExtraNutrient.SATURATED_FAT, it) }
            optional { it.sodiumMg }?.let { put(ExtraNutrient.SODIUM, it) }
            optional { it.cholesterolMg }?.let { put(ExtraNutrient.CHOLESTEROL, it) }
        },
    )
}

/** Energy of the reviewed plate, for the sheet's running total. */
internal fun List<DishItem>.photoCalories(): Double = sumOf { it.nutrition.kcal * it.grams / 100.0 }

private data class DishTemplate(
    val keywords: List<String>,
    val name: CatalogText,
    val items: List<DishItem>,
) {
    /**
     * A one-word keyword matches a whole word, a multi-word one matches anywhere.
     *
     * Substring matching alone would be a minefield: "steamed" and "steak" both contain "tea",
     * "eggplant" contains "egg", "pierogi" contains "pie". The trailing "s" is the one bit of
     * grammar worth knowing — the labels say Pancakes as often as Pancake.
     */
    fun matches(label: String, words: List<String>): Boolean = keywords.any { keyword ->
        if (' ' in keyword) label.contains(keyword) else words.any { it == keyword || it == keyword + "s" }
    }
}

/**
 * Keywords rather than whole labels, because the model names dishes far more precisely than a
 * nutrition table ever will: "Chicago-style pizza", "Neapolitan pizza" and "Pizza bagel" are three
 * classes and one row here. First match wins, so the file reads specific-first.
 */
private val DISH_TEMPLATES: List<DishTemplate> by lazy {
    catalogResource(TEMPLATES_RESOURCE).lineSequence().drop(1).filter(String::isNotBlank)
        .map(::parseTemplate)
        .toList()
}

private fun parseTemplate(line: String): DishTemplate {
    val cell = line.split(';')
    require(cell.size == TEMPLATE_COLUMNS) { "$TEMPLATES_RESOURCE: expected $TEMPLATE_COLUMNS columns in `$line`" }
    return DishTemplate(
        keywords = cell[0].split('|').map { it.lowercase(Locale.ROOT) },
        name = CatalogText(cell[1], cell[2]),
        items = cell[3].split('|').map(::parseTemplateItem),
    )
}

/**
 * `white_rice=180`, referencing the catalogue by id so the nutrition stays in one file — and the
 * saved dishes' third field, `curd=200=5`, where a template wants a fat content of its own.
 */
private fun parseTemplateItem(field: String): DishItem {
    val part = field.split('=')
    val food = TOP_FOODS.firstOrNull { it.id == part[0] }
    requireNotNull(food) { "$TEMPLATES_RESOURCE: `${part[0]}` is not a catalogue id" }
    return DishItem(food, part[1].toDouble(), part.getOrNull(2)?.toDoubleOrNull())
}

private const val LABELS_RESOURCE = "/food_labels.csv"
private const val IMAGENET_RESOURCE = "/imagenet_labels.csv"
private const val PRODUCTS_RESOURCE = "/product_labels.csv"
private const val TEMPLATES_RESOURCE = "/dish_templates.csv"
private const val TEMPLATE_COLUMNS = 4
private const val PRODUCT_COLUMNS = 2

/** Five, not three: when the top guess is wrong the right one is usually two rows further down. */
private const val MAX_GUESSES = 5

/**
 * Low on purpose. Across 2 023 classes a clear photograph scores 0.5 and a plausible one 0.1; the
 * cost of a wrong guess here is one tap on another chip, and the cost of no guess is the whole
 * feature going quiet.
 */
private const val MIN_CONFIDENCE = 0.02f

/**
 * Higher for the product model: its outputs are calibrated per food rather than shared out by a softmax,
 * so a plate scores several foods at once and the floor has to sit above the side dishes. Above 0.30 its hits on
 * the test photographs are real — apple 0.98, potato 0.89, banana 0.68, egg 0.67 — and so is its worst miss,
 * tomato 0.31 for a chicken breast.
 */
internal const val MIN_PRODUCT_CONFIDENCE = 0.30f

/** Under this, the sheet says so rather than letting a 6%-sure guess look like a reading. */
internal const val LOW_CONFIDENCE = 0.15f
private const val MATCHABLE_WORD = 4
private const val GRAM_STEP = 5.0
