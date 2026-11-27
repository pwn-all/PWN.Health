package com.pwnall.health

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Which basis the person is typing on.
 *
 * A packet of chicken gives its figures per 100 g; a cooked meal on a kitchen scale gives them for
 * what is on the plate. Both are normal, and guessing wrong by a factor of two is the difference
 * between a tracked day and a useless one — so the screen asks instead of assuming.
 */
enum class MealBasis {
    /** Typed per 100 g of product; the portion figures are scaled up from them. */
    PER_100G,

    /** Typed for the whole portion; the per-100 g figures are scaled down from them. */
    PER_PORTION,
    ;

    val per100: Boolean get() = this == PER_100G
}

/** The portion is the figure of record, so a weight of zero has nothing to scale and yields zero. */
fun portionFromPer100(per100Value: Double, servingG: Double): Double =
    if (servingG <= 0.0) 0.0 else per100Value * servingG / 100.0

/** The inverse. Undefined without a weight — a portion of nothing is not "per 100 g" of anything. */
fun per100FromPortion(portionValue: Double, servingG: Double): Double =
    if (servingG <= 0.0) 0.0 else portionValue * 100.0 / servingG

/**
 * What one macro row's bar fills to: this macro's share of the portion's total macro mass.
 *
 * Mass rather than energy on purpose. The bars sit beside gram figures, so a fat bar that read
 * shorter than a protein bar of the same length in grams — which is what an energy share would
 * draw, fat carrying nine calories to protein's four — would look like an arithmetic mistake.
 */
fun macroShare(valueG: Double, proteinG: Double, fatG: Double, carbsG: Double): Float {
    val total = maxOf(0.0, proteinG) + maxOf(0.0, fatG) + maxOf(0.0, carbsG)
    if (total <= 0.0) return 0f
    return (maxOf(0.0, valueG) / total).toFloat().coerceIn(0f, 1f)
}

/**
 * Three formatters because a figure is printed three ways: a nutrient always carries one decimal
 * ("31,0 г", "14,1 г") unless it is exactly nothing ("0 г"), energy is whole ("363 ккал"), and a
 * portion weight drops a decimal it does not need ("220 г", but "12,5 г" when that is the weight).
 *
 * All three take a locale because the decimal separator is part of the figure: a Russian
 * screen reads "3,6", not "3.6".
 */
fun Double.asGramText(locale: Locale = Locale.ROOT): String {
    val rounded = round(this * 10.0) / 10.0
    return if (rounded == 0.0) "0" else String.format(locale, "%.1f", rounded)
}

fun Double.asKcalText(): String = round(this).toLong().toString()

/**
 * Whether two weights are the same weight.
 *
 * A portion round-trips through [asWeightText], which rounds to a tenth, so USDA's 28.35 g ounce
 * comes back as 28.4 and never equals itself again — compared exactly, the chip that offered it
 * could never show as chosen, however exactly it had been chosen. Half a tenth is finer than
 * anything these figures carry, so it is the whole of the difference worth noticing.
 */
fun Double.sameWeightAs(other: Double): Boolean = abs(this - other) < WEIGHT_EPSILON

private const val WEIGHT_EPSILON = 0.05

fun Double.asWeightText(locale: Locale = Locale.ROOT): String {
    val rounded = round(this * 10.0) / 10.0
    return if (rounded == floor(rounded)) {
        rounded.toLong().toString()
    } else {
        String.format(locale, "%.1f", rounded)
    }
}

/**
 * A coarse food group.
 *
 * Two things want one: the offline catalogue, where every row states its group, and the header of a
 * hand-typed meal, where [foodCategory] guesses one from the name. That header carries a drawing
 * rather than a photograph: this app has no INTERNET permission — [NoNetworkPermissionTest] enforces
 * it — so there is nowhere a photograph could come from. The drawing is the food's own where
 * [topFoodArt] has one for it, this group's plate everywhere else. The guess is decoration, never
 * data, and nothing is stored from it; the catalogue's group is data.
 *
 * [FAST_FOOD], [NUTS], [DISH] and [SNACK] only ever come from the catalogue: the word lists below
 * would need a cuisine's worth of vocabulary to guess them, and a wrong guess is worse than
 * [OTHER].
 */
enum class FoodCategory {
    FAST_FOOD,
    MEAT,
    FISH,
    DAIRY,
    GRAIN,
    VEGETABLE,
    FRUIT,
    NUTS,
    DISH,
    SWEET,
    SNACK,
    DRINK,
    OTHER,
}

private val CATEGORY_WORDS: List<Pair<FoodCategory, List<String>>> = listOf(
    // Drinks lead because naming one is the strongest signal in the list: "кофе с молоком" is a
    // drink, not a dairy product, and "яблочный сок" is a drink, not a fruit.
    FoodCategory.DRINK to listOf(
        "чай", "кофе", "сок", "вода", "напиток", "смузи", "компот", "морс", "лимонад", "пиво",
        "вино", "коктейл", "какао",
        "tea", "coffee", "juice", "water", "drink", "smoothie", "lemonade", "beer", "wine", "cocoa",
    ),
    FoodCategory.MEAT to listOf(
        "куриц", "курин", "грудка", "индейк", "говядин", "свинин", "баранин", "мясо", "котлет",
        "фарш", "бекон", "ветчин", "колбас", "сосиск", "стейк", "печенк", "печёнк",
        "chicken", "turkey", "beef", "pork", "lamb", "meat", "bacon", "ham", "sausage", "steak",
    ),
    FoodCategory.FISH to listOf(
        "рыб", "лосос", "сёмг", "семг", "тунец", "форел", "треск", "селёдк", "селедк", "креветк",
        "кальмар", "икра", "минтай", "скумбри",
        "fish", "salmon", "tuna", "trout", "cod", "shrimp", "prawn", "squid", "herring",
    ),
    FoodCategory.DAIRY to listOf(
        "молок", "кефир", "творог", "йогурт", "сыр", "сметан", "ряженк", "сливк", "масло сливоч",
        "milk", "kefir", "yogurt", "yoghurt", "cheese", "cottage", "cream", "butter",
    ),
    FoodCategory.GRAIN to listOf(
        "хлеб", "рис", "гречк", "овсян", "макарон", "паст", "булк", "каш", "крупа", "мюсли",
        "лаваш", "тост", "перловк", "булгур", "кускус", "лапш", "пшён", "пшен",
        "bread", "rice", "buckwheat", "oat", "pasta", "noodle", "cereal", "muesli", "granola",
    ),
    FoodCategory.VEGETABLE to listOf(
        "овощ", "салат", "огурец", "огурц", "помидор", "томат", "капуст", "морков", "картоф",
        "лук", "перец", "броккол", "брокол", "кабач", "свёкл", "свекл", "тыкв", "шпинат", "фасол",
        "горох", "чечевиц", "нут", "гриб",
        "vegetable", "salad", "cucumber", "tomato", "cabbage", "carrot", "potato", "onion",
        "pepper", "broccoli", "zucchini", "beet", "pumpkin", "spinach", "bean", "pea", "lentil",
        "mushroom",
    ),
    FoodCategory.NUTS to listOf(
        "орех", "фундук", "миндал", "кешью", "фисташк", "арахис", "семечк", "кедров",
        "nut", "almond", "cashew", "pistachio", "peanut", "hazelnut", "walnut", "seeds",
    ),
    FoodCategory.FRUIT to listOf(
        "фрукт", "яблок", "банан", "апельсин", "груш", "виноград", "ягод", "клубник", "малин",
        "черник", "киви", "манго", "персик", "слив", "ананас", "арбуз", "дын", "мандарин",
        "fruit", "apple", "banana", "orange", "pear", "grape", "berry", "strawberry", "kiwi",
        "mango", "peach", "pineapple", "melon",
    ),
    FoodCategory.SWEET to listOf(
        "шоколад", "конфет", "торт", "пирож", "печенье", "сахар", "мёд", "мед ", "варень",
        "мороженое", "десерт", "вафл", "зефир", "пряник", "круассан",
        "chocolate", "candy", "cake", "cookie", "biscuit", "sugar", "honey", "jam", "ice cream",
        "dessert", "waffle", "croissant",
    ),
    FoodCategory.FAST_FOOD to listOf(
        "бургер", "пицц", "шаурм", "шаверм", "хот-дог", "хотдог", "наггетс", "фри",
        "чизбургер", "гамбургер", "донер", "сэндвич", "сендвич",
        "burger", "pizza", "hot dog", "hotdog", "nugget", "fries", "taco", "burrito", "sandwich",
        "kebab", "doner", "shawarma",
    ),
    FoodCategory.DISH to listOf(
        "суп", "борщ", "щи", "солянк", "плов", "рагу", "запеканк", "пельмен", "вареник", "голубц",
        "лазан", "ризотто", "омлет", "яичниц", "бутерброд", "блин", "оладь", "сырник",
        "soup", "stew", "curry", "casserole", "lasagna", "lasagne", "risotto", "omelette",
        "omelet", "dumpling", "pancake", "porridge",
    ),
    FoodCategory.SNACK to listOf(
        "чипс", "сухарик", "попкорн", "крекер", "снек", "батончик", "хлебц",
        "chips", "crisps", "popcorn", "cracker", "snack", "pretzel",
    ),
)

/**
 * First matching group wins, and the order above is the tie-break: "куриный суп" is meat before it
 * is anything else. Unknown names get [FoodCategory.OTHER] rather than a wrong guess.
 *
 * The name is matched with a space glued to its end so that an entry written with a trailing space
 * — "мед ", spelled that way to stay out of «медальон» and «медведь» — can still match where the word
 * usually stands, which is last: a title trimmed and not padded would leave that entry dead for "Липовый
 * мед" and "Мед". "мед " is the only entry ending in a space, so the pad can enable nothing else.
 */
fun foodCategory(title: String): FoodCategory {
    val name = title.lowercase().trim() + " "
    if (name.isBlank()) return FoodCategory.OTHER
    return CATEGORY_WORDS.firstOrNull { (_, words) -> words.any { name.contains(it) } }
        ?.first
        ?: FoodCategory.OTHER
}

/**
 * The fourteen foods that have a drawing of their own, and the words that reach one.
 *
 * A category answers "what kind of thing is this", which is the right answer for two thousand
 * catalogue rows and a thin one for the handful of foods a person logs every single morning: 1934
 * rows of meat would all show the same cut. An egg, a slice of bread, a bowl of oats have portraits
 * of their own, so a name gets a second look before its group's plate is handed out.
 *
 * The words match the START of a word and never the middle of one, which is the whole difference
 * between this list and [CATEGORY_WORDS] above. A category survives a loose match — "ирис" filed
 * under sweets is none the worse for containing "рис" — but a picture does not: a toffee drawn as
 * a bowl of rice is simply wrong, and so is «пастила» served as a plate of pasta.
 *
 * Order is the tie-break, as it is above, and it is set so that the whole wins over the ingredient
 * it names: bread before banana, so banana bread is bread; pasta before cheese, so macaroni cheese
 * is macaroni; oats before milk, so porridge made with milk is still porridge.
 */
private val FOOD_ART_WORDS: List<Pair<Int, List<String>>> = listOf(
    R.drawable.ic_art_chicken_breast to listOf("грудк", "breast"),
    R.drawable.ic_art_grain to listOf("овсян", "геркулес", "oat"),
    R.drawable.ic_art_egg to listOf("яйц", "яич", "egg"),
    R.drawable.ic_art_bread to listOf("хлеб", "батон", "тост", "лаваш", "bread", "toast", "loaf"),
    R.drawable.ic_art_rice to listOf("рис", "rice"),
    // "паста" is missing on purpose: in Russian it is also the peanut, curry and tomato pastes,
    // all three of which the catalogue carries. "макарон" and "спагетти" name the food and nothing
    // else.
    R.drawable.ic_art_pasta to listOf("макарон", "спагетти", "pasta", "spaghetti", "macaroni"),
    // The whole word rather than the stem "молок", because what follows "молочный" is usually the
    // food — chocolate, a shake, a sweet — and the milk has been demoted to an adjective.
    R.drawable.ic_art_milk to listOf("молоко", "milk"),
    R.drawable.ic_art_cheese to listOf(
        "сыр", "чеддер", "моцарелл", "пармезан", "фета",
        "cheese", "cheddar", "mozzarella", "parmesan", "feta",
    ),
    R.drawable.ic_art_yogurt to listOf("йогурт", "yogurt", "yoghurt"),
    R.drawable.ic_art_banana to listOf("банан", "banana"),
    R.drawable.ic_art_apple to listOf("яблок", "apple"),
    R.drawable.ic_art_potato to listOf("картоф", "картош", "potato"),
    R.drawable.ic_art_tomato to listOf("помидор", "томат", "tomato"),
    R.drawable.ic_art_cucumber to listOf("огур", "cucumber"),
)

/**
 * Names that open with one of those words and mean something else entirely. Cottage cheese is
 * «творог» and not a wedge of anything, an eggplant is an aubergine, breadfruit grows on a tree and
 * a milkfish swims. A word start cannot tell them apart, so they are refused by name.
 */
private val FOOD_ART_IMPOSTORS = listOf("cottage", "eggplant", "breadfruit", "milkfish")

private val WORD_BREAK = Regex("[^\\p{L}]+")

/**
 * The drawing made for this food, or null when the name is none of the fourteen — which is the
 * usual answer, and the one that leaves the category plate in place.
 */
internal fun topFoodArt(title: String): Int? {
    val words = title.lowercase().split(WORD_BREAK).filter(String::isNotEmpty)
    if (words.any { word -> FOOD_ART_IMPOSTORS.any(word::startsWith) }) return null
    return FOOD_ART_WORDS
        .firstOrNull { (_, starts) -> starts.any { start -> words.any { it.startsWith(start) } } }
        ?.first
}

/**
 * The categories that are built out of foods rather than being one, and so never take a portrait.
 *
 * Fast food and a cooked dish are the obvious pair. Sweets, snacks and drinks are here for exactly
 * the same reason: the catalogue holds a cheesecake, potato crisps and tea with milk, and each of
 * them names an ingredient that is not what arrived on the table.
 */
private val ASSEMBLED_FOODS = setOf(
    FoodCategory.FAST_FOOD,
    FoodCategory.DISH,
    FoodCategory.SWEET,
    FoodCategory.SNACK,
    FoodCategory.DRINK,
)

/**
 * The one guess the set above cannot cover on its own.
 *
 * [foodCategory] matches anywhere inside a word and takes the first list that hits, so it reads
 * "cheeseburger" as dairy and "картофель фри" as a vegetable: both arrive at the rule below as
 * plain products, one word away from being drawn as their own filling. A name that says burger,
 * pizza or fries is fast food whatever the guess decided.
 */
private val FAST_FOOD_WORDS: List<String> =
    CATEGORY_WORDS.first { (category, _) -> category == FoodCategory.FAST_FOOD }.second

private fun namesFastFood(title: String): Boolean {
    val name = title.lowercase()
    return FAST_FOOD_WORDS.any(name::contains)
}

/**
 * The picture for a catalogue row.
 *
 * The group is data here rather than a guess, so it is what decides whether the row is a plain
 * product at all and the name only chooses between the fourteen. Both names are asked because a
 * curated row carries Russian and English while a USDA one carries only English: the drawing
 * belongs to the food, not to the language the phone happens to be set to.
 */
internal fun TopFood.art(): Int = foodArt(group, listOf(name.ru, name.en))

/** The same picture for a name somebody typed, where there is no row and the category is a guess. */
internal fun foodArt(title: String): Int = foodArt(foodCategory(title), listOf(title))

/**
 * The rule both paths pass through: a portrait is for a plain product and for nothing else.
 *
 * A cheeseburger says cheese, a cheesecake says cheese, "картофель фри" says potato and "чай с
 * молоком" says milk. Drawn as the ingredient it names, each of those tells a lie about what was
 * eaten — so anything assembled keeps the plate its category gives it, and the fourteen drawings
 * stay with the foods themselves.
 */
private fun foodArt(category: FoodCategory, names: List<String>): Int {
    val plate = category.art()
    if (category in ASSEMBLED_FOODS || names.any(::namesFastFood)) return plate
    return names.firstNotNullOfOrNull(::topFoodArt) ?: plate
}
