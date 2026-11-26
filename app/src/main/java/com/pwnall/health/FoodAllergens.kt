package com.pwnall.health

import java.util.Locale

/**
 * Which of the fourteen declarable allergens a catalogue food usually contains.
 *
 * This is a **reminder to read the packet**, and the wording everywhere it is printed says so. It
 * is not an ingredient list: the catalogue holds a name, a group and a nutrition table, and nobody
 * told this app what went into a particular manufacturer's biscuit. What it can do is recognise
 * that a food called "cheesecake" is a milk food and a food called "pad thai" is a peanut one, and
 * put that in front of somebody before they tap it.
 *
 * Two rules keep it from being dangerous:
 *
 * 1. **A miss is not a clearance.** The app never says a food is free of anything. An allergen not
 *    listed here means this matcher did not recognise one, which is not evidence of absence, and no
 *    screen may phrase it as such.
 * 2. **A false positive is cheap; a false negative is not.** Where a keyword is ambiguous the
 *    matcher takes the reading that flags, not the one that stays quiet — "soy sauce" carries both
 *    soy and gluten, because it usually does.
 *
 * The one place that rule is inverted is the word "nut", and deliberately. Annex II names eight
 * tree nuts; a coconut, a chestnut, a nutmeg, a doughnut and a butternut squash are not among them,
 * and matching the substring would put four of them on the list and teach a person to ignore it.
 * The matcher names the eight.
 */
internal object FoodAllergens {
    /**
     * The allergens a food of this name usually contains.
     *
     * Matched against the English name, its detail and its search aliases, because every row of the
     * catalogue carries an English name and only about half carry a Russian one. Lower-cased and
     * matched word by word against every allergen's table, never stopping at the first one found:
     * "Cheese crackers with peanut butter" has to answer milk, gluten and peanuts at once.
     */
    internal fun of(food: TopFood): Set<Allergen> {
        val text = buildString {
            append(' ')
            append(food.name.en.lowercase(Locale.ROOT))
            append(' ')
            append(food.detail.en.lowercase(Locale.ROOT))
            append(' ')
            food.aliases.forEach {
                append(it.lowercase(Locale.ROOT))
                append(' ')
            }
        }.replace(NOT_A_LETTER, " ")
        return Allergen.entries
            .filter { allergen ->
                // The decoys come out first, and only for the allergen they decoy. "Peanut butter"
                // stops being a milk food before the milk words are looked for, and goes on being a
                // peanut one, because the peanut pass never saw the phrase removed.
                val searched = DECOYS[allergen].orEmpty().fold(text) { carried, decoy ->
                    carried.replace(" $decoy ", " ")
                }
                KEYWORDS.getValue(allergen).any { keyword -> searched.holds(keyword) }
            }
            .toSet()
    }

    /**
     * Whether the text holds this keyword as a word, allowing only the plural.
     *
     * A prefix match would be tempting — it catches every plural at once — and it is exactly how
     * "carp" finds carpaccio, "pie" finds piece and "tart" finds tartare. The plural is the only
     * ending allowed, so mussels, crackers and walnuts are found and those three are not.
     *
     * A keyword with a space in it is a phrase and is matched as one, with the same plural on its
     * last word: the catalogue's row is "Brazil nuts", and its apricots and hot dogs are plural too.
     */
    private fun String.holds(keyword: String): Boolean =
        " $keyword " in this || " ${keyword}s " in this || " ${keyword}es " in this

    /**
     * Everything that is not a letter becomes a space, so a keyword can be matched as a whole word.
     *
     * This is the single change that keeps the matcher honest. As substrings, "oat" is inside
     * "goat", "ale" inside "kale", "pie" inside "piece", "sole" inside "casserole", "carp" inside
     * "carpaccio" and "eel" inside "peel" — six wrong answers from six right keywords. As words
     * they are none of them a match, and the list stays readable.
     */
    private val NOT_A_LETTER = Regex("[^\\p{L}]+")

    /**
     * Phrases that cancel one allergen's keywords without touching another's.
     *
     * A nut butter is not a dairy butter, a coconut is not a milk and an aubergine is not an egg,
     * and each of them is a word a person would otherwise be warned about every time they logged
     * one. They are removed only from the pass for the allergen they mislead: an almond milk stops
     * being a milk and goes on being an almond, so no nut butter or nut milk is a decoy for the nut.
     */
    private val DECOYS: Map<Allergen, List<String>> = mapOf(
        Allergen.MILK to listOf(
            "peanut butter", "almond butter", "cashew butter", "nut butter", "shea butter",
            "cocoa butter", "apple butter", "butter bean", "butter beans", "coconut milk",
            "almond milk", "soy milk", "soya milk", "oat milk", "rice milk", "coconut cream",
            "butterhead", "butterfly",
        ),
        Allergen.EGGS to listOf("eggplant", "eggplants"),
        Allergen.FISH to listOf("shellfish", "cuttlefish", "jellyfish", "fish sauce free"),
    )

    /**
     * The words that name each allergen in a food's name.
     *
     * Kept in code rather than in the catalogue's CSV: the CSV is generated by a script from USDA's
     * own tables, which carry no allergen column, so a column here would be four thousand judgements
     * nobody could review. A table of keywords is a hundred, and a test can hold it to the traps.
     *
     * A keyword is matched as a whole word, so a compound is a word of its own: "cheeseburger" is
     * not "cheese" and "swordfish" is not "fish" until this table says so. The ones the catalogue
     * holds are listed one by one rather than matched by the word inside them, because sweetbreads,
     * breadfruit, headcheese and jellyfish are built from the same words and hold none of the
     * allergens. Every key is written the way the text looks once its punctuation has become
     * spaces, which is why za'atar is two words here.
     */
    private val KEYWORDS: Map<Allergen, List<String>> = mapOf(
        Allergen.GLUTEN to listOf(
            "wheat", "spelt", "khorasan", "rye", "barley", "oat", "oats", "oatmeal", "porridge",
            "bread", "toast", "bun", "roll",
            "bagel", "baguette", "brioche", "croissant", "pasta", "spaghetti", "macaroni", "noodle",
            "lasagna", "lasagne", "ravioli", "gnocchi", "couscous", "bulgur", "semolina", "cracker",
            "biscuit",
            "cookie", "cake", "pastry", "pie", "tart", "pizza", "dough", "batter", "breaded",
            "pancake", "waffle", "muffin", "cereal", "granola", "muesli", "pretzel", "crouton",
            "dumpling", "pelmeni", "vareniki", "pierogi", "blini", "crepe", "beer", "ale", "stout",
            "soy sauce", "seitan", "farro", "flour", "tortilla", "pita", "naan", "wrap", "sandwich",
            "burger", "hot dog", "donut", "doughnut", "churro", "strudel", "baklava",
            "ramen", "udon", "tortellini", "brownie", "battered", "toastie", "spanakopita", "mcmuffin",
            "hamburger", "cheeseburger", "chiliburger", "chefburger",
            "flatbread", "cornbread", "gingerbread", "shortbread", "breadstick", "breadcrumb",
            "cheesecake", "shortcake", "fruitcake", "hotcake", "johnnycake",
        ),
        Allergen.CRUSTACEANS to listOf(
            "shrimp", "prawn", "gambas", "crab", "lobster", "crayfish", "crawfish", "langoustine",
            "krill", "scampi",
        ),
        Allergen.EGGS to listOf(
            "egg", "omelet", "omelette", "mayonnaise", "mayo", "meringue", "frittata", "quiche",
            "custard", "eggnog", "tiramisu", "hollandaise", "carbonara",
        ),
        Allergen.FISH to listOf(
            "salmon", "tuna", "cod", "trout", "mackerel", "sardine", "herring", "anchovy", "anchovies", "halibut",
            "sea bass", "pollock", "tilapia", "carp", "perch", "sprat", "caviar", "roe", "surimi",
            "fish", "haddock", "sole", "snapper", "mahi", "catfish", "pike", "eel", "worcestershire",
            "swordfish", "monkfish", "whitefish", "bluefish", "rockfish", "sablefish", "tilefish",
            "butterfish", "wolffish", "milkfish", "saltfish", "sunfish", "lingcod",
        ),
        Allergen.PEANUTS to listOf("peanut", "groundnut", "arachis", "satay", "pad thai"),
        Allergen.SOY to listOf("soy", "soya", "soybean", "soymilk", "tofu", "edamame", "miso", "tempeh"),
        Allergen.MILK to listOf(
            "milk", "cheese", "yogurt", "yoghurt", "butter", "cream", "kefir", "curd", "cottage",
            "ricotta", "mozzarella", "parmesan", "parmigiana", "parmigiano", "feta", "cheddar",
            "brie", "camembert", "gouda",
            "mascarpone", "whey", "casein", "latte", "cappuccino", "smetana", "tvorog", "ghee",
            "custard", "tiramisu", "alfredo", "gelato", "kashk",
            "cheeseburger", "cheesecake", "milkshake", "buttermilk", "buttercream", "buttered",
            "butterscotch", "creamed", "creamy", "creamer",
        ),
        // The eight the regulation names, and nothing that merely ends in "nut".
        Allergen.TREE_NUTS to listOf(
            "almond", "hazelnut", "walnut", "cashew", "pecan", "brazil nut", "pistachio",
            "macadamia", "marzipan", "praline", "nutella", "gianduja", "amlou",
        ),
        Allergen.CELERY to listOf("celery", "celeriac"),
        Allergen.MUSTARD to listOf("mustard", "dijon"),
        Allergen.SESAME to listOf("sesame", "tahini", "halva", "hummus", "za atar", "zaatar"),
        // Wine and dried fruit are the two everyday foods routinely over the 10 mg/kg line.
        Allergen.SULPHITES to listOf("wine", "raisin", "dried apricot", "prune", "sultana"),
        Allergen.LUPIN to listOf("lupin", "lupine", "lupini"),
        Allergen.MOLLUSCS to listOf(
            "mussel", "oyster", "squid", "calamari", "octopus", "pulpo", "scallop", "clam",
            "snail", "escargot", "cuttlefish", "abalone", "whelk",
        ),
    )
}
