package com.pwnall.health

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * What the nutrition table on a package declares, per 100 g of product.
 *
 * This is the other thing a meal photograph can be: not a plate for the classifiers
 * ([FoodRecognizer]) but a packet whose numbers are printed right on it. Reading print beats
 * guessing from pixels — the label is the manufacturer's own measurement — so when a photograph
 * parses as one of these, it is offered ahead of any dish guess.
 *
 * Every field is nullable because labels omit freely: a tea packet prints only the calories, a
 * European one adds fibre and salt. What was not read stays empty on the form rather than
 * becoming a zero the person has to notice and delete.
 *
 * The parsing is keyword lines + regex, deliberately not a model: dictionaries and rules for the
 * extraction step, never an LLM, whatever OCR engine stands in front of it ([LabelReader]). Wrong
 * readings cost one edit in the form, where every figure lands editable.
 */
internal data class NutritionLabel(
    val kcal: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val sugarG: Double? = null,
    val fiberG: Double? = null,
    val sodiumMg: Double? = null,
    /**
     * The portion the figures describe, when the label says one — "(190g当たり)" on a Japanese
     * carton. Null means the EAEU/EU default: the figures are per 100 g.
     */
    val perServingG: Double? = null,
    /**
     * Whether this text named a per-100 g basis of its own. A pot printing both "Serving size 1 pot
     * (130 g)" and "Amount per 100 g" declares the second for its figures, and a serving size read
     * off some other pass of the same photograph must not overrule that.
     */
    val perHundred: Boolean = false,
    /**
     * Whether the energy figure is the reader's best guess rather than something it can stand
     * behind: the macros disagree with it arithmetically, or it came from the panel's largest
     * type because the word beside it never read. The form shows those with a "≈" and asks.
     */
    val approximate: Boolean = false,
) {
    /** A reading worth offering: energy, or enough macros that this was really a label. */
    val found: Boolean get() = kcal != null || listOfNotNull(proteinG, fatG, carbsG).size >= 2
}

/**
 * Reads OCR text line by line; the first line naming a nutrient answers for it.
 *
 * Line-oriented on purpose: a nutrition table prints one nutrient per row, and the reader keeps
 * rows as lines. On a two-column table ("на 100 г | на порцию") the leftmost number wins, which
 * is the per-100 g column everywhere this app's labels come from — EAEU and EU tables lead with
 * it by regulation.
 *
 * A label written as one running line — "エネルギー 123kcal/たんぱく質 5.8g/脂質 1.1g/…" on a
 * drinking-yoghurt carton — wraps wherever the packet runs out of width, so a nutrient's name and
 * its number land on neighbouring lines. That one case carries over: a line ending in exactly one
 * unanswered nutrient hands it to the next line, and the next line pays only if it leads with a
 * number ([leadingReading]) — a line that opens with words is a new row, not a continuation.
 */
internal fun nutritionLabel(ocrText: String): NutritionLabel {
    val sketch = LabelSketch(
        NutritionLabel(
            perServingG = servingOn(ocrText),
            perHundred = PER_HUNDRED.containsMatchIn(normalized(ocrText)),
        ),
    )
    for (raw in ocrText.lines()) sketch.absorb(normalized(raw))
    return sketch.result()
}

/** Accumulates the table while the lines go by, at most one wrapped nutrient in flight. */
private class LabelSketch(private var label: NutritionLabel) {
    private var saltSodiumMg: Double? = null
    private var pending: Pending? = null

    /**
     * Every number an energy line offered, kept in reading order and resolved once at the end —
     * the EU table prints its kJ and kcal as a pair, and only seeing every candidate lets the
     * pair ratio arbitrate when OCR mangles one half's unit ("434 kal" beside a phantom "4kcal").
     */
    private val energy = mutableListOf<Reading>()

    fun absorb(line: String) {
        if (line.isEmpty()) {
            // A wrapped pair never straddles a paragraph break; only the very next line may pay.
            pending = null
            return
        }
        carryOver(line)
        if (ENERGY_WORDS.any(line::contains)) energy += energyOn(line)
        // "Углеводы 44 г, в том числе сахара 20 г": the parent figure lives before the marker and
        // the sub-row after it, whether they share a printed line or not. So the plain macros read
        // only the head of the line, and the sub-nutrients read all of it.
        val head = SUBORDINATE_WORDS.mapNotNull { word -> line.indexOf(word).takeIf { it >= 0 } }
            .minOrNull()?.let(line::take) ?: line
        label = label.readingOn(line, head)
        if (saltSodiumMg == null) saltSodiumMg = gramsOn(line, SALT_WORDS)?.times(SODIUM_MG_PER_SALT_G)
        pending = danglingOn(line, head, label, saltSodiumMg, energy)
    }

    private fun carryOver(line: String) {
        val nutrient = pending ?: return
        pending = null
        val lead = leadingReading(line) ?: return
        // By its unit, as a row's own figure is: "20 mg" on the line under "Salt" is not 20 g.
        val grams = lead.asGrams()?.takeIf { it in 0.0..MAX_GRAMS_PER_100G }
        when {
            nutrient == Pending.ENERGY -> energy += energyOn(line)
            grams != null && nutrient == Pending.SALT -> saltSodiumMg = grams * SODIUM_MG_PER_SALT_G
            grams != null -> label = label.carrying(nutrient, grams)
        }
    }

    // A printed sodium figure always beats one derived from salt, and they are never summed.
    fun result(): NutritionLabel {
        val candidates = energyCandidates(energy, label)
        val kcal = witnessedKcal(energy) ?: believableKcal(candidates, label)
        var read = label.copy(kcal = kcal, approximate = kcal != null && !kcal.agreesWithMacros(label))
        if (read.sodiumMg == null) read = read.copy(sodiumMg = saltSodiumMg)
        return read
    }
}

/**
 * The kilocalories the collected energy readings agree on. A number wearing its «ккал» is believed
 * when a kJ companion — united or bare — confirms it at the kJ/kcal ratio, because the phantom
 * "4kcal" OCR conjures from a portion column has no such witness; a lone kJ converts; a bare pair
 * at the ratio names its kcal half; and only then is an unwitnessed «ккал» or bare figure believed.
 */
private fun energyCandidates(energy: List<Reading>, label: NutritionLabel): List<Double> {
    val kcalUnit = energy.firstOrNull { it.unit in KCAL_UNITS && it.value <= MAX_KCAL_PER_100G }?.value
    val kj = energy.firstOrNull { it.unit in KJ_UNITS }?.value
    val bare = energy.filter { it.unit == null }.map { it.value }
    val bareKcal = bare.filter { it in MIN_BARE_KCAL..MAX_KCAL_PER_100G }.nearestAtwaterFirst(label)
    // In falling order of trust: a «ккал» its kJ companion confirms; a lone united kJ, converted;
    // a bare pair at the ratio; an unwitnessed «ккал»; a bare figure in range — but never a bare
    // single digit, which is OCR debris and not diet food; and last, a lone bare figure only a
    // kilojoule could be ("2256" with its "540 ккал" half unread), converted rather than lost.
    // Lone means lone: among several bare figures a kJ-sized one is as likely shredded digits.
    return listOfNotNull(
        kcalUnit?.takeIf { it > 0.0 && it.witnessedBy(kj, bare) },
        kj?.div(KJ_PER_KCAL)?.takeIf { it <= MAX_KCAL_PER_100G },
        barePairKcal(bare),
        kcalUnit,
        bareKcal.firstOrNull(),
        bare.singleOrNull()?.takeIf { it in MIN_BARE_KJ..MAX_BARE_KJ }?.div(KJ_PER_KCAL),
    ) + bareKcal
}

/**
 * Several bare figures on one energy line are told apart by the arithmetic, where there is any.
 * "Энергетическая ценность 100 г продукта 150 кал" with the «г» lost to OCR offers 100 and 150 with
 * no unit on either, and reading order would take the column header for the calories — 100 sits
 * inside the tolerance of a 145 kcal table, so the check that follows would let it stand. The
 * figure nearest what the macros weigh goes first instead; with no macros the order is as printed.
 */
private fun List<Double>.nearestAtwaterFirst(label: NutritionLabel): List<Double> {
    val atwater = label.atwaterKcal ?: return this
    return sortedBy { abs(it - atwater) }
}

/**
 * The first candidate the macros can live with.
 *
 * Protein and carbohydrate carry 4 kcal per gram, fat 9 — Atwater's factors, printed on the back
 * of half these packets themselves. A table that reads "Calories 980" beside 0.05 g of fat and 15 g
 * of carbohydrate has not read its calories; it has read the servings-per-container column, or two
 * figures welded into one. So the candidates are tried in order of trust and the first that lands
 * anywhere near the arithmetic wins.
 *
 * Generous by design: the tolerance below forgives sugar alcohols, rounding on a small serving and
 * a fibre row nobody printed. It is meant to catch the tenfold errors, not to audit a manufacturer.
 * With fewer than two macros read there is nothing to check against, and the first candidate stands.
 */
private fun believableKcal(candidates: List<Double>, label: NutritionLabel): Double? {
    val first = candidates.firstOrNull()
    if (first == null || !label.macrosCanArbitrate) return first
    return candidates.firstOrNull { it.agreesWithMacros(label) } ?: first
}

/** Two macros are the fewest that can say anything about an energy figure. */
private val NutritionLabel.macrosCanArbitrate: Boolean
    get() = listOfNotNull(proteinG, fatG, carbsG).size >= 2

/** What the macros read so far weigh in kilocalories, or null while too few have read to say. */
private val NutritionLabel.atwaterKcal: Double?
    get() = if (!macrosCanArbitrate) {
        null
    } else {
        (proteinG ?: 0.0) * KCAL_PER_G_PROTEIN + (fatG ?: 0.0) * KCAL_PER_G_FAT + (carbsG ?: 0.0) * KCAL_PER_G_CARB
    }

/** Whether this many kilocalories is what those grams of protein, fat and carbohydrate weigh. */
private fun Double.agreesWithMacros(label: NutritionLabel): Boolean {
    val atwater = label.atwaterKcal ?: return true
    return abs(this - atwater) <= maxOf(MIN_ATWATER_SLACK, atwater * ATWATER_SLACK)
}

/**
 * The kilocalories the packet's own kJ figure vouches for, before any arithmetic of ours is asked.
 * Two printed numbers at the kJ/kcal ratio are the label's own sum, and they outrank the macros:
 * a 12-language wafer wrapper whose fat row reads "Ma3Hw/…: 31,1г" as 3 g would make Atwater refuse
 * its witnessed 2256 kJ / 540 kcal and believe a Georgian "კკალ" read as 333 instead. The macros still
 * flag the disagreement — [NutritionLabel.approximate] — so a wrong macro is asked about, not hidden.
 */
private fun witnessedKcal(energy: List<Reading>): Double? {
    val kcalUnit = energy.firstOrNull { it.unit in KCAL_UNITS && it.value <= MAX_KCAL_PER_100G }?.value
    val kj = energy.firstOrNull { it.unit in KJ_UNITS }?.value
    val bare = energy.filter { it.unit == null }.map { it.value }
    return kcalUnit?.takeIf { it > 0.0 && it.witnessedBy(kj, bare) } ?: barePairKcal(bare)
}

/**
 * An energy line's figures, less a bare one written with a thousands group. "2,000 calories a day"
 * is the US footnote's diet and not this packet: read as 2.0, it would make the "Calories 10" above
 * it the kJ half of a pair whose kcal was 2. No packet prints energy to the thousandth, and a kJ figure
 * written that way keeps its unit and is read as thousands ([THOUSANDS_GROUP]).
 */
private fun energyOn(line: String): List<Reading> =
    readings(line, 0).filterNot { it.unit == null && THOUSANDS_GROUP.matchesAt(line, it.position) }

/** Whether some kJ-sized companion — united or bare — stands at the pair ratio to this «ккал». */
private fun Double.witnessedBy(kj: Double?, bare: List<Double>): Boolean =
    kj?.let { it / this in KJ_PAIR_RATIO } == true || bare.any { it / this in KJ_PAIR_RATIO }

/** The first two bare figures standing in the kJ/kcal ratio, in reading order; kcal is the second. */
private fun barePairKcal(bare: List<Double>): Double? {
    for (i in bare.indices) {
        for (j in i + 1 until bare.size) {
            if (bare[j] > 0.0 && bare[i] / bare[j] in KJ_PAIR_RATIO && bare[j] <= MAX_KCAL_PER_100G) {
                return bare[j]
            }
        }
    }
    return null
}

/** What this one line answers directly: each still-empty field asks it once. */
private fun NutritionLabel.readingOn(line: String, head: String): NutritionLabel {
    var read = this
    if (read.proteinG == null) read = read.copy(proteinG = gramsOn(head, PROTEIN_WORDS))
    if (read.carbsG == null) read = read.copy(carbsG = gramsOn(head, CARB_WORDS))
    if (read.fatG == null && SATURATED_WORDS.none(head::contains)) {
        read = read.copy(fatG = gramsOn(head, FAT_WORDS))
    }
    if (read.sugarG == null) read = read.copy(sugarG = gramsOn(line, SUGAR_WORDS))
    if (read.fiberG == null) read = read.copy(fiberG = gramsOn(line, FIBER_WORDS))
    if (read.sodiumMg == null) read = read.copy(sodiumMg = sodiumOn(line))
    return read
}

/**
 * Lowercased, space-stripped, and cured of the two digit impostors OCR actually produces: a
 * Cyrillic "з" welded to digits ("21ЗК" for 213 kcal on a konbini sticker) and an "o" standing
 * where a zero was — including the zero-calorie tea whose whole reading is "Okcal". Spaces go
 * first because Japanese OCR shreds words into characters — "水 化物", "当 た り" — and Russian
 * and English keywords survive the same squeeze unharmed. Figures do not all survive it: "1 130 kJ"
 * is one figure, as printed, but two unitless columns side by side weld the same way — "Protein 3 1"
 * reads 31. A weld past what 100 g can hold is refused ([gramsOn]), and a table whose header names
 * its 100 g column is also read with that column alone ([asPerHundredColumn]).
 *
 * Compatibility folding goes before even that. The Japanese recogniser's dictionary carries the
 * full-width digits, ｇ, ｋｃａｌ and brackets a packet prints in its own script and returns them
 * as printed, and Java's \d is ASCII only — unfolded, "１２３kcal" is no number at all, and a label
 * written entirely that way would read as nothing. The same fold spells ㎉, ㎎ and ㎖ out as kcal, mg and ml
 * and turns the full-width space into the one stripped next.
 */
private fun normalized(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(" ", "")
    .replace(LETTER_THREE, "з")
    .replace(LETTER_ZERO, "о")
    .replace(CONFUSED_ZERO, "0")
    .replace(CONFUSED_THREE, "3")
    .replace(LONE_ZERO_KCAL) { match -> match.groupValues[1] + "0kcal" }

/** The nutrients a wrapped line can leave dangling; salt converts on arrival like its own row. */
private enum class Pending { ENERGY, PROTEIN, CARBS, FAT, SUGAR, FIBER, SALT }

/** The line's first reading when nothing wordy stands before it — a continuation, not a new row. */
private fun leadingReading(line: String): Reading? {
    val first = readings(line, 0).firstOrNull() ?: return null
    if (line.take(first.position).any(Char::isLetterOrDigit)) return null
    return first
}

/** The one nutrient this line names without answering, if there is exactly one. */
private fun danglingOn(
    line: String,
    head: String,
    label: NutritionLabel,
    saltSodiumMg: Double?,
    energy: List<Reading>,
): Pending? {
    val dangling = buildList {
        if (energyDangles(line, energy)) add(Pending.ENERGY)
        addDanglingMacros(head, label)
        if (label.sugarG == null && dangles(line, SUGAR_WORDS)) add(Pending.SUGAR)
        if (label.fiberG == null && dangles(line, FIBER_WORDS)) add(Pending.FIBER)
        if (saltSodiumMg == null && dangles(line, SALT_WORDS)) add(Pending.SALT)
    }
    return dangling.singleOrNull()
}

private fun energyDangles(line: String, energy: List<Reading>): Boolean =
    energy.isEmpty() && ENERGY_WORDS.any(line::contains) && readings(line, 0).isEmpty()

private fun MutableList<Pending>.addDanglingMacros(head: String, label: NutritionLabel) {
    if (label.proteinG == null && dangles(head, PROTEIN_WORDS)) add(Pending.PROTEIN)
    if (label.carbsG == null && dangles(head, CARB_WORDS)) add(Pending.CARBS)
    if (label.fatG == null && SATURATED_WORDS.none(head::contains) && dangles(head, FAT_WORDS)) {
        add(Pending.FAT)
    }
}

private fun dangles(line: String, words: List<String>): Boolean {
    val after = nameEnd(line, words) ?: return false
    return readings(line, after).isEmpty()
}

private fun NutritionLabel.carrying(nutrient: Pending, value: Double): NutritionLabel = when (nutrient) {
    Pending.PROTEIN -> copy(proteinG = value)
    Pending.CARBS -> copy(carbsG = value)
    Pending.FAT -> copy(fatG = value)
    Pending.SUGAR -> copy(sugarG = value)
    Pending.FIBER -> copy(fiberG = value)
    Pending.ENERGY, Pending.SALT -> this
}

/** One number as printed, with the unit that follows it, if any, and where on the line it began. */
private data class Reading(val value: Double, val unit: String?, val position: Int)

/**
 * Longest unit alternatives first, so "ккал" is never read as "к" short of anything; kca and its
 * Cyrillic double «кса» are what kcal looks like after OCR loses a letter. The digits refuse to
 * start mid-run: a box border misread as a leading "1" welds onto the real figure, and
 * "3221 3kcal" split freely would invent a 3 kcal packet out of a 213 kcal one. Three decimals
 * are allowed because sodium is printed with them — "Sodium 0.125 g" cut to 0.12 would lose its
 * unit to the leftover digit and be saved as 0.12 mg instead of 125.
 */
private val NUMBER =
    Regex(
        """(?<![0-9])([0-9]{1,4}(?:[.,][0-9]{1,3})?)$SPACE*""" +
            """(ккал|kcall|keall|kcal|keal|кдж|kj|kl|kca|кса|мг|mg|мл|ml|гр|г|g|%)?""",
    )

/**
 * "1.130 kJ" on a German or Italian packet is eleven hundred and thirty: a three-digit group after
 * the separator beside a kilojoule unit is a thousands group, since no packet prints kilojoules to
 * the thousandth, and read as a fraction it would name a quarter of a kilocalorie as the energy.
 * Milligrams go the same way: "Sodium 1,200mg" on a US panel is twelve hundred, nobody prints a
 * thousandth of a milligram, and read as 1.2 mg it would file a day's worth of salt as none. Grams keep
 * their three decimals — those never reach a thousand per 100 g, and sodium is printed "0.125 g".
 */
private val THOUSANDS_GROUP = Regex("""[0-9]{1,3}[.,][0-9]{3}""")

/**
 * The unit spellings that mean kilocalories once OCR has had its say. keal and keall are what
 * "kcal" becomes when the c is read as an e: a bread wrapper's "745 kJ (177 kcal)" can come back
 * as "745 kl (177 keall" — both halves unrecognisable without these, and the kJ figure believed as
 * the answer.
 */
private val KCAL_UNITS = setOf("ккал", "kcal", "kcall", "keal", "keall", "kca", "кса")

/** kl is kJ with its J read as an l; no label prints kilolitres of anything. */
private val KJ_UNITS = setOf("кдж", "kj", "kl")

/**
 * OCR's digit lookalikes, curable only where a digit stands right beside them. An "o" ahead of a
 * digit is a zero only where no letter stands before it: the spaces are gone by here, and
 * "Białko 12 g" arrives as "białko12g". The letter that ends the word would make its figure "012"
 * — 0,12 g once [decimalOf] restores a comma nobody ate — and "pro 100 g" would name no basis.
 */
private val CONFUSED_ZERO = Regex("""(?<=[0-9])[оo]|(?<!\p{L})[оo](?=[0-9])""")
private val CONFUSED_THREE = Regex("""(?<=[0-9])з|з(?=[0-9])""")

/**
 * The reverse confusion: a digit standing between Cyrillic letters is a letter — "Ма3нини" —
 * unless the letters after it are a unit. The spaces are already gone by here, so "Жиры 0 г"
 * arrives as "жиры0г" with its zero looking exactly like that, and the rescue would delete the
 * figure of every zero-fat and three-gram-protein row an EAEU packet prints.
 */
private const val UNIT_AHEAD = """(?!(?:ккал|кдж|мг|мл|кг|гр|г)(?![а-яё]))"""
private val LETTER_THREE = Regex("""(?<=[а-я])3(?=[а-я])""" + UNIT_AHEAD)
private val LETTER_ZERO = Regex("""(?<=[а-я])0(?=[а-я])""" + UNIT_AHEAD)

/**
 * "Okcal" — a zero-calorie tea whose zero came back as a letter. Only when no Latin or Cyrillic
 * letter precedes: an Italian "energetico" glued to its unit must not donate its final o as
 * somebody's zero, but the ー of エネルギー is a letter to Unicode and must not block the cure.
 */
private val LONE_ZERO_KCAL = Regex("""(^|[^a-zа-яё])[оo]kcal?""")

/**
 * The numbers on a line from [from], left to right, minus the known impostors: the "100" of a
 * "на 100 г" column header, anything in percent — a share of the daily norm is not an amount of
 * anything — and the head of a digit run too long to have been printed as one figure.
 */
private fun readings(line: String, from: Int): List<Reading> =
    NUMBER.findAll(line, from).mapNotNull { match ->
        val unit = match.groupValues[2].ifEmpty { null }
        val digits = match.groupValues[1]
        val grouped = (unit in KJ_UNITS || unit in MG_UNITS) && THOUSANDS_GROUP.matches(digits)
        val value = decimalOf(if (grouped) digits.filter(Char::isDigit) else digits) ?: return@mapNotNull null
        val lead = line.take(match.range.first).trimEnd()
        // A column header names no amount — except that "Углеводов 100 г" is a macro's own
        // genitive welded onto its own figure, and pure sugar really is 100 g of carbohydrate. Its
        // grams are what tell the two apart: a 100 lent from a neighbouring row arrives without one.
        val header = value == 100.0 && HEADER_LEADS.any(lead::endsWith) &&
            (unit !in GRAM_UNITS || MACRO_GENITIVES.none(lead::endsWith))
        // The pattern caps a run at four digits, so a fifth means two figures welded into one — a
        // table border read as a leading 1, or a column glued to its neighbour — and the first four
        // digits of that are a measurement printed nowhere on the packet.
        val truncated = line.getOrNull(match.range.first + digits.length)?.isDigit() == true
        // The unit alone can disqualify a figure: a percentage is a share of the daily norm rather
        // than an amount, and millilitres are a volume — the serving column of "Amount per serving
        // 100 ml" — which is neither energy nor grams of anything.
        val unitCarriesNoAmount = unit == "%" || unit == "ml" || unit == "мл"
        if (unitCarriesNoAmount || header || truncated) null else Reading(value, unit, match.range.first)
    }.toList()

/**
 * "01" is "0,1" with its comma eaten: no label prints an integer with a leading zero, and OCR
 * loses decimal commas wholesale — a cheddar's carbs come back "01", a crepe's salt "014". A
 * leading zero therefore reads as the decimal it was.
 */
private fun decimalOf(digits: String): Double? {
    val wholeNumber = digits.none { it == '.' || it == ',' }
    val restored = if (wholeNumber && digits.length > 1 && digits.startsWith("0")) {
        "0." + digits.drop(1)
    } else {
        digits
    }
    return restored.replace(',', '.').toDoubleOrNull()
}

/**
 * The first number after the nutrient's name, in grams, if this line names it and both its unit and
 * physics allow it.
 */
private fun gramsOn(line: String, words: List<String>): Double? {
    val after = nameEnd(line, words) ?: return null
    val first = readings(line, after).firstOrNull() ?: return null
    return first.asGrams()?.takeIf { it in 0.0..MAX_GRAMS_PER_100G } ?: first.unweldedGrams()
}

/**
 * The figure as grams, by the unit printed beside it. Sugar, fibre and salt are sometimes stated in
 * milligrams, and the number alone would make "Salt 20 mg" twenty grams of salt — 8 000 mg of sodium
 * kept beside the favourites and counted into every meal logged from it. A figure wearing kcal or kJ is
 * an energy and no weight at all; one with no unit is taken for grams, as a table's rows print them.
 */
private fun Reading.asGrams(): Double? = when (unit) {
    in MG_UNITS -> value / MG_PER_G
    in KCAL_UNITS, in KJ_UNITS -> null
    else -> value
}

/**
 * Where the nutrient's name ends on this line, if the line names it at all. Most spellings are
 * roots — an inflection or a 相当量 may follow — but the ones in [WHOLE_WORD_ONLY] are read only
 * where they stand alone: inside «подсолнечное» (sunflower), soluble or girasol they name no salt, and the
 * grams printed there would otherwise be saved as the meal's sodium.
 */
private fun nameEnd(line: String, words: List<String>): Int? = words.firstNotNullOfOrNull { word ->
    if (word in WHOLE_WORD_ONLY) {
        line.wholeWordEnd(word)
    } else {
        line.unhiddenEnd(word)
    }
}

/** Where [word] ends at its first occurrence with no letter beside it; the text is space-stripped. */
private fun String.wholeWordEnd(word: String): Int? {
    var at = indexOf(word)
    while (at >= 0) {
        val end = at + word.length
        if (!letterAt(at - 1) && !letterAt(end)) return end
        at = indexOf(word, at + 1)
    }
    return null
}

/** Whether a letter stands at [index]; past either end of the line nothing stands at all. */
private fun String.letterAt(index: Int): Boolean = getOrNull(index)?.isLetter() == true

/** Where [word] ends at its first occurrence that no word in [HIDING_WORDS] accounts for. */
private fun String.unhiddenEnd(word: String): Int? {
    val hiders = HIDING_WORDS[word].orEmpty()
    var at = indexOf(word)
    while (at >= 0) {
        if (hiders.none { regionMatches(at - it.indexOf(word), it, 0, it.length) }) return at + word.length
        at = indexOf(word, at + 1)
    }
    return null
}

/**
 * "57 g" welds into "579" when OCR reads the unit as a digit: an impossible unitless gram figure
 * ending in 9 sheds that 9 and asks physics again.
 */
private fun Reading.unweldedGrams(): Double? {
    if (unit != null || value % 10.0 != 9.0) return null
    return ((value - 9.0) / 10.0).takeIf { it in 0.0..MAX_GRAMS_PER_100G }
}

/** Sodium prints in either unit — "натрий 0,4 г" and "sodium 400 mg" are the same figure. */
private fun sodiumOn(line: String): Double? {
    val after = SODIUM_WORDS.firstNotNullOfOrNull { word ->
        line.indexOf(word).takeIf { it >= 0 }?.plus(word.length)
    } ?: return null
    val reading = readings(line, after).firstOrNull() ?: return null
    val milligrams = if (reading.unit == "г" || reading.unit == "гр" || reading.unit == "g") {
        reading.value * MG_PER_G
    } else {
        reading.value
    }
    return milligrams.takeIf { it in 0.0..MAX_SODIUM_MG_PER_100G }
}

/**
 * Fills the meal form with what the label declared, on the basis the label itself named: per
 * 100 g — what an EAEU or EU packet prints — unless a portion marker said otherwise, in which
 * case the figures are that portion's and its grams land in the serving field.
 *
 * Title stays whatever the person typed: the label knows the nutrition, never the name on the
 * front of the pack — that field is exactly what the person adds next, in the same form every
 * manual entry uses.
 */
internal fun FoodState.withLabel(subtitle: String, reading: NutritionLabel): FoodState = copy(
    subtitle = subtitle,
    basis = if (reading.perServingG != null) MealBasis.PER_PORTION else MealBasis.PER_100G,
    serving = reading.perServingG?.asWeightText() ?: serving,
    protein = reading.proteinG?.asGramText().orEmpty(),
    fat = reading.fatG?.asGramText().orEmpty(),
    carbs = reading.carbsG?.asGramText().orEmpty(),
    reportedEnergy = reading.kcal?.asGramText().orEmpty(),
    extra = buildMap {
        reading.fiberG?.let { put(ExtraNutrient.FIBER, it.asGramText()) }
        reading.sugarG?.let { put(ExtraNutrient.SUGAR, it.asGramText()) }
        reading.sodiumMg?.let { put(ExtraNutrient.SODIUM, it.asGramText()) }
    },
)

/**
 * The label as a product of one's own, kept beside the favourites so the packet is read once.
 *
 * A favourite stores a portion's figures, and a packet that prints per 100 g has stated exactly
 * one weight — so that is the portion it becomes; a label that named its serving keeps it. An
 * energy line the packet did not print is what the macros add up to, the same 4/9/4 the form
 * falls back on, rather than a zero that would log a free meal.
 */
internal fun NutritionLabel.toFavorite(name: String, subtitle: String, now: Long): FavoriteFood {
    val protein = proteinG ?: 0.0
    val fat = fatG ?: 0.0
    val carbs = carbsG ?: 0.0
    return FavoriteFood(
        key = favoriteKey(null, name),
        title = name.trim(),
        subtitle = subtitle,
        servingG = perServingG ?: HUNDRED_GRAMS,
        calories = kcal ?: macroCalories(protein, fat, carbs),
        proteinG = protein,
        fatG = fat,
        carbsG = carbs,
        fiberG = fiberG,
        sugarG = sugarG,
        sodiumMg = sodiumMg,
        createdAt = now,
    )
}

private const val HUNDRED_GRAMS = 100.0

// Substrings of lowercased, space-stripped OCR text. Roots rather than words where languages
// decline them: "белк" covers «белки» and «белков» but not «белок», so both are listed. The Japanese
// entries are the label vocabulary itself — 熱量, たんぱく質, 脂質, 炭水化物, 糖類, 食物繊維,
// 食塩相当量 — trimmed to the characters that survive OCR: the leading 炭 of 炭水化物 can come
// back as 区, so the carbohydrate key is the tail. The European entries are the forms OCR returns
// on real packets — diacritics often do not survive recognition, so Bílkoviny arrives as
// "bilkoviny" and Sůl as "sul" — with the accented spelling kept alongside wherever OCR preserves it.
// kj is a trigger and not only a unit: on a mangled line the kJ figure is often the sole
// survivor of the pair, and the line naming it earns its place in the energy pool. "hodnota" is
// the tail of "energetická hodnota" that survives when OCR eats the head; ネルギー the tail of
// エネルギー — never ルギー alone, which lives inside アレルギー on every allergen box.
private val ENERGY_WORDS = listOf(
    "ккал", "кдж", "калорийн", "энергетич", "енерг", "kcal", "kj", "energ", "calor", "hodnota", "熱量", "ネルギー",
)

// たんぱ/たんば/たんぽ are OCR, not Japanese: a recogniser drops or swaps the handakuten of ぱ and
// loses the trailing く often enough that the two-kana root is what actually survives. PP-OCRv6
// drops the mark altogether and reads たんはく質; "たんは" alone is too short to be safe, so that
// one keeps its く.
private val PROTEIN_WORDS = listOf(
    "белк", "белок", "білк", "белтъ", "protein", "bilkovin", "bílkovin", "bielkovin", "fehérje", "feherje",
    "bialk", "białk", "biatk", "eiweiß", "eiweiss", "olbaltumviel", "baltym", "valgud", "bjelancevin",
    "bjelančevin", "beljakovin", "たんぱ", "たんば", "たんぽ", "たんはく", "タンパク", "蛋白",
)
private val FAT_WORDS = listOf(
    "жир", "мазнини", "fat", "tuky", "zsír", "zsir", "tłuszcz", "tluszcz", "fett", "grăsimi", "grasimi",
    "tauki", "riebal", "rasvad", "masti", "maščobe", "mascobe", "脂質",
)
private val CARB_WORDS = listOf(
    "углевод", "вуглевод", "въглехидрат", "carbohydrate", "carbs", "sacharid", "szénhidrát", "szenhidrat",
    "węglowodan", "weglowodan", "kohlenhydrat", "glucid", "hidrat", "angliavanden", "süsivesik", "susivesik",
    "化物",
)
private val SUGAR_WORDS = listOf(
    "сахар", "захар", "цукр", "sugar", "cukr", "cukor", "cukur", "zucker", "zahar", "suhkru", "šećer",
    "secer", "sladkor", "suiker", "sucre", "糖類",
)
private val FIBER_WORDS = listOf(
    "клетчатк", "волокн", "влакнин", "fibre", "fiber", "vlákn", "vlakn", "viaknin", "błonnik", "blonnik",
    "ballaststoff", "šķiedrviel", "skiedrviel", "skaidul", "kiudaine", "繊維",
)
private val SODIUM_WORDS = listOf("натри", "sodium")

// Salt rows in more European languages: Ukrainian «сіль», Latvian sāls where OCR keeps its macron, the
// Lithuanian root of druska and druskos, Hungarian só.
private val SALT_WORDS = listOf(
    "соль", "сол", "salt", "sůl", "sul", "soľ", "sol", "salz", "sool", "sals", "sare", "食塩",
    "сіль", "sāls", "drusk", "só",
)

/**
 * The spellings short enough to hide inside another word — «подсолнечное», «солод», soluble, solids,
 * girasol, the Hungarian utolsó — where the grams that follow belong to an ingredient and not to a
 * salt row. Only the short ones: "salt" still finds a "salted" and "食塩" its 相当量.
 */
private val WHOLE_WORD_ONLY = setOf("сол", "sůl", "sul", "soľ", "sol", "só")

/**
 * The longer spellings hide whole inside a food name of their own: «фасоль» (beans) ends in "соль" (salt), salsa
 * opens with "sals", and «обезжиренный», «нежирный» and "fat free" carry the fat root inside what the
 * front of the pack calls itself — where the grams that follow are the net weight, so a 100 g tub
 * of curd would be filed as 100 g of fat and its 900 kcal would sit in judgement over the energy line.
 */
private val HIDING_WORDS = mapOf(
    "соль" to listOf("фасоль"),
    "sals" to listOf("salsa"),
    "жир" to listOf("обезжир", "нежир"),
    "fat" to listOf("fatfree", "fat-free"),
)

// satfat and sat.fat are how a small US panel abbreviates the row; the text is space-stripped
// before any of this is matched, so "Sat Fat" arrives welded and "Sat. Fat" keeps only its point.
private val SATURATED_WORDS = listOf(
    "насыщен", "насичен", "наситен", "saturated", "satfat", "sat.fat", "транс", "trans",
    "nasycen", "nasýten", "nasyten", "nasycon", "nasičen", "nasicen", "zasićen", "zasicen",
    "telített", "telitett", "gesättigt", "gesattigt", "piesātinā", "piesatina", "飽和",
)
private val SUBORDINATE_WORDS =
    listOf("втомчисле", "вт.ч", "изних", "ofwhich", "toho", "wtym", "davon", "amelyb", "waarvan")

/** What "на 100 г" / "per 100 g" / "na 100 g" ends with just before its number starts. */
private val HEADER_LEADS = listOf("на", "per", "в", "/", "na", "w", "pro")

/**
 * «Белков», «жиров» and «углеводов» end in the same "в" the preposition of "в 100 г" is, and the spaces
 * are gone by the time the header rule looks. A macro's own row is not a column header — sugar
 * really is 100 g of carbohydrate per 100 g — but only where its grams came with it: the assembly
 * that lends a row the figure printed above it lends the "100" of the header without any unit.
 */
private val MACRO_GENITIVES = listOf("белков", "жиров", "углеводов", "сахаров")
private val GRAM_UNITS = setOf("г", "гр", "g")
private val MG_UNITS = setOf("мг", "mg")

/**
 * "(190g当たり)" — the portion a Japanese label declares its figures for. Space-stripped first,
 * because OCR renders it "(190g 当 た り)". Millilitres pass as grams: the cartons that print
 * this are drinks, and a millilitre of one weighs close enough to a gram to start editing from.
 * The marker itself varies by packet: 当たり on cartons, plain-kana あたり on frozen bags,
 * 当り with the kana dropped on chilled coffees, and "1本（240ml）当り" closes a bracket first —
 * a full-width one, which is what a Japanese keyboard types and what the recognizer returns.
 */
private val SERVING_MARKER = Regex("""([0-9]{1,4}(?:[.,][0-9]{1,2})?)(g|г|ml|мл)?[)）]?(?:当|あた)""")

private fun servingOn(ocrText: String): Double? {
    val text = normalized(ocrText)
    return SERVING_MARKER.findAll(text).firstNotNullOfOrNull(::japaneseServing) ?: servingSizeOn(text)
}

/**
 * The grams a Japanese marker declares. The unit is optional because OCR drops it — "（1パック45当
 * り)" for 45 g — but a figure without one has to be big enough to be a weight: "1枚当たり" and
 * "2個当たり" are counts of pieces, and reading those as grams would file a chocolate bar as two
 * grams of chocolate.
 */
private fun japaneseServing(match: MatchResult): Double? {
    val grams = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val floor = if (match.groupValues[2].isEmpty()) MIN_UNITLESS_SERVING_G else 1.0
    return grams.takeIf { it in floor..MAX_SERVING_G }
}

/**
 * "Serving size 2 tablespoon (32g)" — what a US-style panel declares its figures for, and a common
 * line on real packets. Those figures are per serving, and
 * 32 g of peanut butter filed as 100 g is a threefold lie in the diary. A Canadian panel says the
 * same thing as "Per 1 cup (250 mL)" or "Per 2 cookies (30 g)" and its French half as "pour", a
 * German one as "Portion" and a Spanish one as "porción"; without a serving-size row of their own
 * those would be read per 100 g by default, and everything on them two and a half times too small.
 *
 * Only where the label names no per-100 basis of its own: a European table prints both, and there
 * the figures the parser reads are the 100 g column. The grams may arrive welded to a unit OCR
 * lost — "(1309)" for 130 g, the same fault [unweldedGrams] cures on the macros — so a bare
 * figure ending in 9 is read that way after no united one is found.
 */
private fun servingSizeOn(text: String): Double? {
    if (PER_HUNDRED.containsMatchIn(text)) return null
    return SERVING_SIZE.findAll(text).firstNotNullOfOrNull { marker -> servingGramsIn(marker.value) }
}

private fun servingGramsIn(afterMarker: String): Double? {
    // "Serv. size:" ends one printed line and "2 Tbsp (32g)" opens the next, so the window reaches
    // across the break — but never across a nutrient of its own, on either line: a wrapper printing
    // "Serving size 1 bar Total Fat 15g" in one row would otherwise offer 15 g as the bar.
    val continuation = afterMarker.substringAfter('\n', "").takeWhile { it != '\n' }
        .takeIf { NUTRIENT_WORDS.none(it::contains) }.orEmpty()
    val window = (afterMarker.substringBefore('\n') + continuation).untilNutrient()
    val united = SERVING_GRAMS.find(window)?.groupValues?.get(1)?.let(::decimalOf)
    val welded = WELDED_SERVING_GRAMS.find(window)?.groupValues?.get(1)?.toDoubleOrNull()
        ?.let { (it - 9.0) / 10.0 }
    return (united ?: welded)?.takeIf { it in 1.0..MAX_SERVING_G }
}

/** The grams standing after a nutrient's name are that nutrient's, never the serving's. */
private fun String.untilNutrient(): String =
    NUTRIENT_WORDS.mapNotNull { word -> indexOf(word).takeIf { it >= 0 } }
        .minOrNull()?.let { take(it) } ?: this

/**
 * The marker and the 40 characters it may hide its grams in — "serving size 1 cup (240 ml)". Per
 * and pour count only with a quantity right behind them: the bare words stand in "servings per
 * container" and in half the footnotes, and "per 100 g" was answered for before this is asked.
 */
private val SERVING_SIZE =
    Regex("""(?:serv(?:ing)?\.?size|perservingof|(?:per|pour)(?=[0-9])|portion|porci[oó]n)(?s:.{0,40})""")

/** What must not stand between the marker and the figure: those grams belong to a nutrient. */
private val NUTRIENT_WORDS = listOf("calor", "ккал", "kcal", "fat", "жир", "protein", "белк", "carb", "углевод")

/** Millilitres pass as grams: a millilitre of milk or juice weighs close enough to start from. */
private val SERVING_GRAMS = Regex("""([0-9]{1,4}(?:[.,][0-9]{1,2})?)(?:g|г|ml|мл)""")
private val WELDED_SERVING_GRAMS = Regex("""(?<![0-9])([0-9]{2,4}9)(?![0-9])""")

/**
 * A table that names its own 100 g basis; its figures are that column's, whatever it serves.
 *
 * No bare "na": the Gulf footnote "the % Daily Value tells you how much a nutrient in a 100g of
 * food contributes" carries one inside "in a", and it would quietly throw away the serving size
 * of every packet that prints that sentence. No bare "/100" either: a Japanese chilled coffee
 * prints "脂質0.5g未満/100ml" as a fat-free claim, not as the basis of its table. Pour and por
 * are here because "pour 1 tasse" and "porción" name a serving, and the French and Spanish
 * tables that print "pour 100 g" and "por 100 g" must keep declaring the basis those defer to.
 * Never a "100 %" either: "Содержание витаминов 100 % от нормы" ends in the same bare "в" the
 * preposition is, and a share of the daily norm names no basis — readings() drops those figures
 * for the same reason. It would throw away the serving size of the panel printed underneath.
 */
private val PER_HUNDRED = Regex("""(?:per|pour|por|на|pro|в)100(?!%)""")

private const val KJ_PER_KCAL = 4.184

/** Atwater's factors, and how far a printed figure may stray from them before it is misread OCR. */
private const val KCAL_PER_G_PROTEIN = 4.0
private const val KCAL_PER_G_FAT = 9.0
private const val KCAL_PER_G_CARB = 4.0
private const val ATWATER_SLACK = 0.6
private const val MIN_ATWATER_SLACK = 40.0

/**
 * A kJ figure sits this far above its kcal twin; OCR noise almost never lands in the band. Wider
 * than 4.184 deserves alone because a misread digit in either half shifts the quotient — a real
 * "2276 kJ/544 kcal" read as "2772/544" still names its kcal half.
 */
private val KJ_PAIR_RATIO = 3.4..5.2

/** What a lone bare kilojoule figure can plausibly be: above any kcal, below 999 kcal worth. */
private const val MIN_BARE_KJ = 1000.0
private const val MAX_BARE_KJ = 4180.0

/** Pure fat is 900 kcal per 100 g; nothing edible prints higher, so above it is misread OCR. */
private const val MAX_KCAL_PER_100G = 999.0

/** Below this, a bare unitless energy figure is noise; real ones carry «ккал» or a kJ partner. */
private const val MIN_BARE_KCAL = 10.0
private const val MAX_GRAMS_PER_100G = 100.0

/** 100 g of pure salt per 100 g — the ceiling nothing real approaches but nothing valid trips. */
private const val MAX_SODIUM_MG_PER_100G = 40_000.0
private const val SODIUM_MG_PER_SALT_G = 400.0
private const val MG_PER_G = 1000.0

/** A kilogram-sized "portion" is a misread net weight, not something anyone is served. */
private const val MAX_SERVING_G = 1000.0

/** Below this, a figure with no unit beside 当たり is a count of pieces rather than a weight. */
private const val MIN_UNITLESS_SERVING_G = 4.0
