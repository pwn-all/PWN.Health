package com.pwnall.health

import android.content.res.Resources
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

/** Grams of ethanol are the only comparable unit a drink has; [JournalEntry.servingG] holds them. */
const val KIND_ALCOHOL = "ALCOHOL"

/** Grams of tobacco, the one figure a cigarette, a stick, a hookah bowl and a snus pouch share. */
const val KIND_SMOKING = "SMOKING"

/** One visit to the toilet, with what it was like; [JournalEntry.subtitle] holds the answer. */
const val KIND_STOOL = "STOOL"

/** One urination. Nothing but the count and the moment: that is the whole of what is asked. */
const val KIND_URINATION = "URINATION"

/**
 * A drink, by the ethanol it carries rather than by its volume.
 *
 * The figures are the ordinary serving each is poured in: half a litre of 5% beer, a 150 ml glass
 * of 12% wine, a 50 ml measure of 40% spirit. Ethanol = ml × strength × 0.789.
 *
 * That serving is where the chooser opens, not all a drink can be: beer comes in 330 ml bottles and
 * 750 ml ones, at 6% and at 12%, and wine is poured by the 25 ml. [abvRangeTenths] is how far the
 * strength ruler reaches for each, and [stopsMl] the sizes its arrows step through.
 */
enum class AlcoholDrink(
    val titleRes: Int,
    val detailRes: Int,
    val ethanolG: Double,
    val ml: Int,
    /** Strength in tenths of a percent, the unit the strength ruler counts in. */
    val abvTenths: Int,
    val abvRangeTenths: IntRange,
    val stopsMl: List<Int> = POUR_STOPS_ML,
    /** Where a pour stops being small and where it starts being large, for the word and the picture. */
    val mediumFromMl: Int = 101,
    val largeFromMl: Int = 250,
) {
    BEER(
        R.string.alcohol_beer,
        R.string.alcohol_beer_detail,
        20.0,
        ml = 500,
        abvTenths = 50,
        abvRangeTenths = 5..150,
        stopsMl = listOf(330, 500, 750),
        mediumFromMl = 500,
        largeFromMl = 750,
    ),
    WINE(
        R.string.alcohol_wine,
        R.string.alcohol_wine_detail,
        14.0,
        ml = 150,
        abvTenths = 120,
        abvRangeTenths = 50..250,
    ),
    SPIRIT(
        R.string.alcohol_spirit,
        R.string.alcohol_spirit_detail,
        16.0,
        ml = 50,
        abvTenths = 400,
        abvRangeTenths = 150..800,
    ),

    /**
     * A shot is the same spirit in the glass it is drunk from in one go — 40 ml rather than the
     * 50 ml measure poured over ice, which is 12.6 g of ethanol against 16 g. Separate from
     * [SPIRIT] because a person counts shots, not millilitres, and the two are not the same drink.
     */
    SHOT(
        R.string.alcohol_shot,
        R.string.alcohol_shot_detail,
        12.6,
        ml = 40,
        abvTenths = 400,
        abvRangeTenths = 150..800,
    ),
}

/** Wine, spirits and shots step by the 25 ml they are poured in; a typed size steps to its neighbour. */
private val POUR_STOPS_ML = (25..1_000 step 25).toList()

/** The most one pour may be typed as: a bottle and a half, past which the figure is a slip. */
const val MAX_POUR_ML = 3_000

/** Ethanol weighs 0.789 g a millilitre. */
private const val ETHANOL_G_PER_ML = 0.789

/** The three words a pour is shown with, and the three sizes its picture is drawn at. */
enum class PourSize(val titleRes: Int) {
    SMALL(R.string.alcohol_size_small),
    MEDIUM(R.string.alcohol_size_medium),
    LARGE(R.string.alcohol_size_large),
}

fun AlcoholDrink.sizeOf(ml: Int): PourSize = when {
    ml >= largeFromMl -> PourSize.LARGE
    ml >= mediumFromMl -> PourSize.MEDIUM
    else -> PourSize.SMALL
}

/** The stop an arrow lands on from [ml], or null past the last one that way. */
fun AlcoholDrink.stepMl(ml: Int, up: Boolean): Int? =
    if (up) stopsMl.firstOrNull { it > ml } else stopsMl.lastOrNull { it < ml }

/**
 * Grams of ethanol in [ml] at [abvTenths].
 *
 * The drink's own serving keeps its rounded figure: the entry editor recognises a standard pour by
 * its grams. Any other pour matches no drink there, and the editor shows
 * it as it is rather than offer it the standard pours ([rechoosable]).
 */
fun AlcoholDrink.pouredG(ml: Int, abvTenths: Int): Double = if (ml == this.ml && abvTenths == this.abvTenths) {
    ethanolG
} else {
    ml * abvTenths / 1_000.0 * ETHANOL_G_PER_ML
}

/**
 * What was smoked, by the tobacco it burns or heats.
 *
 * A cigarette holds about 0.8 g, a heated stick about 0.3 g, and a hookah bowl is sold by the
 * gram — the three sizes below are the ones the packets come in. Grams of tobacco are not a dose of
 * harm and the screen says so: a heated stick and a cigarette are not the same thing per gram, and
 * nothing here pretends to weigh that difference.
 */
enum class TobaccoProduct(val titleRes: Int, val detailRes: Int, val tobaccoG: Double) {
    CIGARETTE(R.string.smoking_cigarette, R.string.smoking_cigarette_detail, 0.8),
    HEATED(R.string.smoking_heated, R.string.smoking_heated_detail, 0.3),

    /**
     * The three bowls, named by how long they last rather than by a gram figure nobody weighs:
     * about an hour, an hour and a half, two hours. The grams behind them are the ones the bowls
     * are packed with, and [R.string.smoking_hookah_hint] tells the person which is which.
     */
    HOOKAH_SMALL(R.string.smoking_hookah_small, R.string.smoking_hookah_small_detail, 8.0),
    HOOKAH_MEDIUM(R.string.smoking_hookah_medium, R.string.smoking_hookah_medium_detail, 12.0),
    HOOKAH_LARGE(R.string.smoking_hookah_large, R.string.smoking_hookah_large_detail, 16.0),

    /**
     * Snus and the nicotine pouches sold beside it, told apart by strength rather than by size —
     * which is how they are sold and how anyone asks for them.
     *
     * The grams are the pouch, and they fall as the strength rises because that is how the
     * products are actually made: a normal portion is about a gram, a strong one is usually slim
     * at 0.9 g, an extra-strong one slimmer still at 0.7 g. The figure that matters is the
     * nicotine, and it is the detail line each of them carries: roughly 6, 12 and 20 mg, averaged
     * across the brands sold as normal, strong and extra strong. Grams are what this journal
     * counts because grams are what a cigarette and a bowl also have; they are not a dose of harm
     * and the screen's own disclaimer says so.
     */
    SNUS_LITE(R.string.smoking_snus_lite, R.string.smoking_snus_lite_detail, 1.0),
    SNUS_MEDIUM(R.string.smoking_snus_medium, R.string.smoking_snus_medium_detail, 0.9),
    SNUS_EXTRA(R.string.smoking_snus_extra, R.string.smoking_snus_extra_detail, 0.7),
}

/**
 * A tobacco product that is one tile and a choice behind it, rather than one tile per variant.
 *
 * Three hookah bowls and three snus strengths as six more tiles is a screen of eight near-identical
 * pictures where two of them differ only by a word at the end of the label. One tile each, and the
 * choice offered after the tap — which is also where the sentence explaining the choice belongs,
 * rather than permanently above a grid nobody reads twice.
 */
enum class TobaccoGroup(
    val titleRes: Int,
    val detailRes: Int,
    val hintRes: Int,
    /**
     * Whether the chooser's picture grows with the choice. A bowl does — small, medium and large
     * are literally its size. A pouch does not: every pouch is a pouch, and only the nicotine in
     * it changes.
     */
    val grows: Boolean,
    val members: List<TobaccoProduct>,
) {
    HOOKAH(
        R.string.smoking_hookah,
        R.string.smoking_hookah_detail,
        R.string.smoking_hookah_hint,
        grows = true,
        members = listOf(TobaccoProduct.HOOKAH_SMALL, TobaccoProduct.HOOKAH_MEDIUM, TobaccoProduct.HOOKAH_LARGE),
    ),
    SNUS(
        R.string.smoking_snus,
        R.string.smoking_snus_detail,
        R.string.smoking_snus_hint,
        grows = false,
        members = listOf(TobaccoProduct.SNUS_LITE, TobaccoProduct.SNUS_MEDIUM, TobaccoProduct.SNUS_EXTRA),
    ),
}

/** The products that are one tap and no question; everything else is a [TobaccoGroup] above. */
val SINGLE_TOBACCO = listOf(TobaccoProduct.CIGARETTE, TobaccoProduct.HEATED)

/**
 * What a visit to the toilet was like — the one question about it a person will actually answer.
 *
 * Three answers, not the seven of the Bristol chart: this is a screen someone taps in a hurry, and
 * "normal", "hard work" and "loose" are the distinctions that change what anyone would do about it.
 * A week of loose ones is the pattern worth seeing; a scale of seven only makes the tap slower.
 */
enum class StoolKind(val titleRes: Int, val detailRes: Int) {
    NORMAL(R.string.stool_normal, R.string.stool_normal_detail),
    STRAINED(R.string.stool_strained, R.string.stool_strained_detail),
    LOOSE(R.string.stool_loose, R.string.stool_loose_detail),
}

/**
 * What a visit felt like, as ticks rather than a text field.
 *
 * The same six are offered for both halves of this screen on purpose: burning, an urge that keeps
 * coming back and blood are the symptoms a clinician asks about at either end, and a person in a
 * hurry should not have to notice which list they are being shown. Ticks because this is logged in
 * passing, and because a fixed vocabulary is what a month of entries can be counted over — free
 * text is not.
 *
 * None of it reaches Health Connect, which has no record type for anything counted in a toilet.
 */
enum class BathroomNote(val titleRes: Int) {
    BURNING(R.string.bathroom_note_burning),
    TINGLING(R.string.bathroom_note_tingling),
    URGENCY(R.string.bathroom_note_urgency),
    PAIN(R.string.bathroom_note_pain),
    INCOMPLETE(R.string.bathroom_note_incomplete),
    BLOOD(R.string.bathroom_note_blood),
}

/**
 * The ticks an entry carried, read by matching names inside the payload's array rather than through
 * a JSON parser — android's org.json is a throwing stub off the device, and reading it this way is
 * what lets the restore path keep a plain JVM test. The twin of this lives in [sexNotesOf], which
 * answers the same question for the other private screen and shares the key it is written under.
 */
fun bathroomNotesOf(payloadJson: String?): List<BathroomNote> {
    val inside = payloadJson?.let(BATHROOM_NOTES::find)?.groupValues?.get(1) ?: return emptyList()
    return BathroomNote.entries.filter { "\"${it.name}\"" in inside }
}

private val BATHROOM_NOTES = Regex(""""$CYCLE_NOTES_KEY"$SPACE*:$SPACE*\[([^]]*)]""")

private fun bathroomPayload(notes: List<BathroomNote>): String? = notes
    .takeIf { it.isNotEmpty() }
    ?.joinToString(",", prefix = """{"$CYCLE_NOTES_KEY":[""", postfix = "]}") { "\"${it.name}\"" }

/** What the bathroom cards count: one day of visits, and the week they sit in. */
data class BathroomDay(val stools: Int, val looseStools: Int, val urinations: Int)

fun bathroomDay(journal: List<JournalEntry>, day: LocalDate): BathroomDay {
    val onDay = journal.filter { it.day == day.toString() }
    val stools = onDay.filter { it.kind == KIND_STOOL }
    return BathroomDay(
        stools = stools.size,
        looseStools = stools.count { it.subtitle == StoolKind.LOOSE.name },
        urinations = onDay.count { it.kind == KIND_URINATION },
    )
}

/** The same over [HABIT_WINDOW_DAYS], where a run of loose days becomes visible as a run. */
fun bathroomWeek(journal: List<JournalEntry>, today: LocalDate): BathroomDay {
    val from = today.minusDays(HABIT_WINDOW_DAYS - 1L)
    val window = journal.filter {
        it.day.toLocalDateOrNull()?.let { day -> day in from..today } == true
    }
    val stools = window.filter { it.kind == KIND_STOOL }
    return BathroomDay(
        stools = stools.size,
        looseStools = stools.count { it.subtitle == StoolKind.LOOSE.name },
        urinations = window.count { it.kind == KIND_URINATION },
    )
}

/** WHO's standard drink, the unit the guidance is written in. */
const val STANDARD_DRINK_G = 10.0

/**
 * 100 g of ethanol a week — ten standard drinks — is where the Lancet 2018 pooled analysis put the
 * inflection in all-cause mortality, and the level above which a raised GGT has an obvious
 * explanation. It is a reporting threshold here, not a recommendation.
 */
const val ALCOHOL_WEEKLY_G = 100.0

const val HABIT_WINDOW_DAYS = 7

/** A cigarette's worth of tobacco, for putting a hookah evening on a familiar scale. */
const val CIGARETTE_EQUIVALENT_G = 0.8

data class HabitWeek(
    val ethanolG: Double,
    val drinkingDays: Int,
    val tobaccoG: Double,
    val smokingDays: Int,
) {
    val standardDrinks: Double get() = ethanolG / STANDARD_DRINK_G
    val cigaretteEquivalents: Double get() = tobaccoG / CIGARETTE_EQUIVALENT_G

    /**
     * [standardDrinks] as the week's line prints them: rounded, but never up to the reporting line's
     * own figure while the week is under it: 96 g would read "10 standard drinks, reporting line 10" in
     * the plain colour that only 100 g turns to the warning one.
     */
    val shownStandardDrinks: Int
        get() {
            val rounded = standardDrinks.roundToInt()
            val line = (ALCOHOL_WEEKLY_G / STANDARD_DRINK_G).roundToInt()
            return if (ethanolG < ALCOHOL_WEEKLY_G) rounded.coerceAtMost(line - 1) else rounded
        }
}

/** The last seven days of both habits, which is the window every rule that reads them uses. */
fun habitWeek(journal: List<JournalEntry>, today: LocalDate): HabitWeek {
    val from = today.minusDays(HABIT_WINDOW_DAYS - 1L)
    val window = journal.filter {
        it.day.toLocalDateOrNull()?.let { day -> day in from..today } == true
    }
    val drinks = window.filter { it.kind == KIND_ALCOHOL }
    val smokes = window.filter { it.kind == KIND_SMOKING }
    return HabitWeek(
        ethanolG = drinks.sumOf(JournalEntry::servingG),
        drinkingDays = drinks.map(JournalEntry::day).distinct().size,
        tobaccoG = smokes.sumOf(JournalEntry::servingG),
        smokingDays = smokes.map(JournalEntry::day).distinct().size,
    )
}

/**
 * The same two counts over whatever rows are handed in, for a window that is not a week.
 *
 * The report asks over three months or a year; [habitWeek] asks over the seven days every rule in
 * this file is written against. Same figures, same data class, no second definition of a drink.
 */
fun habitsOver(entries: List<JournalEntry>): HabitWeek {
    val drinks = entries.filter { it.kind == KIND_ALCOHOL }
    val smokes = entries.filter { it.kind == KIND_SMOKING }
    return HabitWeek(
        ethanolG = drinks.sumOf(JournalEntry::servingG),
        drinkingDays = drinks.map(JournalEntry::day).distinct().size,
        tobaccoG = smokes.sumOf(JournalEntry::servingG),
        smokingDays = smokes.map(JournalEntry::day).distinct().size,
    )
}

/** What was drunk or smoked on one day, for the screen's own counters. */
fun habitDay(journal: List<JournalEntry>, day: LocalDate): HabitWeek {
    val onDay = journal.filter { it.day == day.toString() }
    return HabitWeek(
        ethanolG = onDay.filter { it.kind == KIND_ALCOHOL }.sumOf(JournalEntry::servingG),
        drinkingDays = if (onDay.any { it.kind == KIND_ALCOHOL }) 1 else 0,
        tobaccoG = onDay.filter { it.kind == KIND_SMOKING }.sumOf(JournalEntry::servingG),
        smokingDays = if (onDay.any { it.kind == KIND_SMOKING }) 1 else 0,
    )
}

internal fun alcoholEntry(
    resources: Resources,
    drink: AlcoholDrink,
    day: LocalDate,
    ml: Int = drink.ml,
    abvTenths: Int = drink.abvTenths,
) = JournalEntry(
    day = day.toString(),
    occurredAt = System.currentTimeMillis(),
    kind = KIND_ALCOHOL,
    title = resources.getString(R.string.alcohol_title),
    // What was poured, since the grams alone do not say: kept as the drink's enum name and two
    // figures, and said by the day list in the language showing then ([pourText]).
    measurementPayloadJson = JSONObject().put(POUR_DRINK_KEY, drink.name).put(POUR_ML_KEY, ml)
        .put(POUR_ABV_KEY, abvTenths).toString(),
    // Deliberately no energy. Ethanol carries 7 kcal a gram, but the day's budget counts meals, and
    // a drink logged here and again as a meal would be counted twice. Log the beer as food when its
    // calories matter; this row exists to answer "how much, how often". The reverse holds too: a
    // beer logged only as food carries no ethanol, and [habitsOver] — which the doctor's report
    // counts alcohol with — never sees it. Both sections of the report say so in their caveats.
    calories = 0.0,
    servingG = drink.pouredG(ml, abvTenths),
)

/** What a drink row poured, read back from the payload [alcoholEntry] wrote. */
internal data class Pour(val drink: AlcoholDrink, val ml: Int, val abvTenths: Int)

/** [entry]'s pour, or null for a row that carries none — an imported drink. */
internal fun pourOf(entry: JournalEntry): Pour? {
    val payload = entry.measurementPayloadJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    val drink = AlcoholDrink.entries.firstOrNull { it.name == payload.optString(POUR_DRINK_KEY) } ?: return null
    return Pour(drink, payload.optInt(POUR_ML_KEY, drink.ml), payload.optInt(POUR_ABV_KEY, drink.abvTenths))
}

/** "Beer · 330 ml · 6,5%" in the language [resources] read, its strength with that language's mark. */
internal fun pourText(resources: Resources, pour: Pour): String = listOf(
    resources.getString(pour.drink.titleRes),
    resources.getString(R.string.water_entry_value, pour.ml),
    resources.getString(R.string.alcohol_abv_value, (pour.abvTenths / 10.0).pretty(resources.configuration.locales[0])),
).joinToString(" · ")

private const val POUR_DRINK_KEY = "drink"
private const val POUR_ML_KEY = "ml"
private const val POUR_ABV_KEY = "abvTenths"

/** The subtitle holds the product's enum name, as a stool's holds its kind: the day list names it ([tobaccoOf]). */
internal fun smokingEntry(resources: Resources, product: TobaccoProduct, day: LocalDate) = JournalEntry(
    day = day.toString(),
    occurredAt = System.currentTimeMillis(),
    kind = KIND_SMOKING,
    title = resources.getString(R.string.smoking_title),
    subtitle = product.name,
    calories = 0.0,
    servingG = product.tobaccoG,
)

/**
 * The subtitle holds the enum's own name rather than its translated word: the day list reads it
 * back to pick a phrase in whatever language is current, and a backup restored under another
 * locale still knows which of the three it was.
 */
internal fun stoolEntry(
    resources: Resources,
    kind: StoolKind,
    day: LocalDate,
    notes: List<BathroomNote> = emptyList(),
) = JournalEntry(
    day = day.toString(),
    occurredAt = System.currentTimeMillis(),
    kind = KIND_STOOL,
    title = resources.getString(R.string.stool_title),
    subtitle = kind.name,
    calories = 0.0,
    measurementPayloadJson = bathroomPayload(notes),
)

internal fun urinationEntry(
    resources: Resources,
    day: LocalDate,
    notes: List<BathroomNote> = emptyList(),
) = JournalEntry(
    day = day.toString(),
    occurredAt = System.currentTimeMillis(),
    kind = KIND_URINATION,
    title = resources.getString(R.string.urination_title),
    calories = 0.0,
    measurementPayloadJson = bathroomPayload(notes),
)

/** The stool kind an entry recorded, or null where the subtitle names no kind. */
fun stoolKindOf(entry: JournalEntry): StoolKind? =
    StoolKind.entries.firstOrNull { it.name == entry.subtitle }

/** The product a smoking row was logged as, which [smokingEntry] keeps in the subtitle. */
internal fun tobaccoOf(entry: JournalEntry): TobaccoProduct? =
    TobaccoProduct.entries.firstOrNull { it.name == entry.subtitle }
