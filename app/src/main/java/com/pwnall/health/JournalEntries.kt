package com.pwnall.health

import android.content.res.Resources
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

// The rows the quick taps write — a glass of water, a cup, a period's start, a mood — built here
// and nowhere else, so the home screen, the widgets, the cycle page and the chat all write the same
// row for the same act. The text helpers beside them are the ones those rows and their cards share.

/**
 * A number as the app prints it: whole ones without a decimal point, everything else to one place.
 *
 * The whole branch goes through Long rather than Int because Double.toInt() saturates instead of
 * overflowing, so a volume of sets × reps × load — or a figure out of a hand-edited backup — would
 * print as 2147483647, a different number that still looks like a real reading. NaN and both
 * infinities give NaN for `% 1.0` and so keep falling to the fractional branch, which prints them.
 */
internal fun Double.pretty(): String =
    if (this % 1.0 == 0.0) toLong().toString() else "%.1f".format(Locale.US, this)

/**
 * [pretty] for a figure a person reads on a screen: the same rounding, in the digits and with the
 * decimal mark of [locale] — "72,4" in Russian or German, where [pretty] writes "72.4".
 *
 * It is what a string resource does with a `%.1f` of its own, so a figure printed through this and
 * one printed by the template beside it agree: otherwise the profile would show a weight of "72.4 kg"
 * over a BMI of "23,5". [pretty] itself keeps its point and its ASCII digits for everything that is stored,
 * sent, put into a field or read back — a title, a subtitle, an export, the doctor's sheet — and
 * a composable asks for this one as [shown].
 */
internal fun Double.pretty(locale: Locale): String =
    if (this % 1.0 == 0.0) String.format(locale, "%d", toLong()) else String.format(locale, "%.1f", this)

/**
 * A figure already printed at its own precision for the sheet — a lab value's "0.035", a range's
 * "5.7" — in the digits and with the decimal mark of [locale], every digit kept: "0,035" on a screen
 * read in Russian. What is no plain figure comes back as it came.
 */
internal fun shownFigure(figure: String, locale: Locale): String =
    figure.toBigDecimalOrNull()?.let { String.format(locale, "%.${it.scale().coerceAtLeast(0)}f", it) } ?: figure

internal fun Long.formatted(): String = NumberFormat.getIntegerInstance().format(this)

/**
 * When a row filed against [day] happened, for the column that leaves the phone.
 *
 * Health Connect is built from `occurredAt` and not from `day` — every cycle record is
 * `Instant.ofEpochMilli(entry.occurredAt)` — so an answer given today about last Tuesday has to
 * carry last Tuesday's stamp. Stamped with the wall clock it is published dated today, and the
 * importer rebuilds the day from the record's time, so a restore moves her answers onto the day she
 * happened to type them.
 *
 * Midday, so the row sorts inside its own date in every zone rather than on the edge of it, and the
 * wall clock when the day is today — which is almost every write, and the only case where the
 * moment is worth keeping. [today] is the caller's own reading of the date where it has one: asked
 * for again here, a midnight between the two readings files a row written as today's at noon of a
 * day already over.
 */
internal fun dayInstant(day: String, today: LocalDate = LocalDate.now()): Long {
    val date = day.toLocalDateOrNull()
    if (date == null || date == today) return System.currentTimeMillis()
    return date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/**
 * The day a row was filed under, or null where the text is not a date. The journal's `day` is ISO
 * text, read in a hundred places; this is the one place that says a malformed day is skipped rather
 * than thrown over.
 */
internal fun String?.toLocalDateOrNull(): LocalDate? = try {
    this?.let(LocalDate::parse)
} catch (_: DateTimeParseException) {
    null
}

/** The kinds whose every row the app names itself, and by one name. */
private val KIND_TITLES = mapOf(
    KIND_WATER to R.string.water_title,
    KIND_MOOD to R.string.mood_title,
    KIND_CYCLE to R.string.cycle_title,
    KIND_CYCLE_END to R.string.cycle_period_end,
    KIND_ALCOHOL to R.string.alcohol_title,
    KIND_SMOKING to R.string.smoking_title,
    KIND_STOOL to R.string.stool_title,
    KIND_URINATION to R.string.urination_title,
    KIND_PAIN to R.string.pain_title,
    KIND_WORK to R.string.work_title,
    KIND_MASTURBATION to R.string.sexual_masturbation,
    KIND_MORNING_CHECK to R.string.sexual_morning_title,
)

/**
 * The name a row is shown under, in the language on screen now.
 *
 * A row the app names itself — a glass of water, the start of a period, a cigarette, an exercise
 * of the catalogue — was given that name as text when it was written. After a change of language,
 * or a backup restored on a phone set to another one, the day's list would read in two languages at once.
 * Where the kind has one name, or the row says which entry of a catalogue it is, the name is looked
 * up again; [JournalEntry.title] stays as written for what reads the row elsewhere.
 *
 * What a person or another app named keeps its own words: a meal, a medicine, a routine, and any
 * imported row that can carry a name ([JournalEntry.healthConnectRecordKey]) — an imported "Morning
 * run" is filed under the catalogue's running and is not called by it. A measurement, a cup with milk
 * (the food editor can rename it) and a symptom told in the person's own words are shown as stored.
 */
internal fun JournalEntry.shownTitle(resources: Resources): String = ownTitleRes()?.let(resources::getString) ?: title

/**
 * The name the app gave this row, as a resource to be read in whatever language is showing; null
 * where the row keeps the words it was written with ([shownTitle] says which those are).
 *
 * A symptom is named by its topic in the payload, «something else» excepted, whose title is the
 * person's words. A lab row by what it holds: an allergy panel, figures, or one examination's file
 * by the kind it was saved as. A black cup by the drink its subtitle keeps ([cupEntry]); one with no
 * drink there was logged by its milligrams and is the caffeine row.
 */
internal fun JournalEntry.ownTitleRes(): Int? {
    val own = healthConnectRecordKey == null
    return when (kind) {
        KIND_CYCLE_LOG -> cycleLog()?.titleRes

        KIND_ACTIVITY, KIND_NOT_DONE -> ACTIVITY_CATALOG.firstOrNull { own && it.code == activityCode }?.titleRes

        KIND_BREATHING -> R.string.breathing_title.takeIf { own }

        KIND_SYMPTOM ->
            decodeSymptomPayload(measurementPayloadJson).symptom?.takeIf { it != Symptom.OTHER }?.labelRes

        KIND_LABS -> labTitleRes()

        KIND_CAFFEINE -> cupTitleRes().takeIf { own }

        else -> KIND_TITLES[kind]
    }
}

/** A black cup's drink, which [cupEntry] keeps in the subtitle; a cup logged by its milligrams has none. */
private fun JournalEntry.cupTitleRes(): Int =
    CaffeineDrink.entries.firstOrNull { it.name == subtitle }?.titleRes ?: R.string.caffeine_title

internal fun cycleEntry(resources: Resources, day: String) = JournalEntry(
    day = day,
    occurredAt = dayInstant(day),
    kind = KIND_CYCLE,
    title = resources.getString(R.string.cycle_title),
    calories = 0.0,
)

/** A cup logged by its label on the packet rather than by name. */
internal fun caffeineEntry(resources: Resources, milligrams: Int, day: String) = JournalEntry(
    day = day,
    occurredAt = System.currentTimeMillis(),
    kind = KIND_CAFFEINE,
    title = resources.getString(R.string.caffeine_title),
    calories = 0.0,
    servingG = milligrams.toDouble(),
)

/** A cup logged by drink and size, now; see [cupEntry]. */
internal fun caffeineEntry(resources: Resources, drink: CaffeineDrink, millilitres: Int, day: String) =
    cupEntry(resources.getString(drink.titleRes), drink, millilitres, day, System.currentTimeMillis())

/**
 * The journal row for a cup of [drink] holding [millilitres].
 *
 * A black cup stays a [KIND_CAFFEINE] row with its milligrams as the serving: tea, coffee, an
 * espresso and matcha come to a few kilocalories at most, and a cup of tea must not become a meal —
 * medicine taken with food, the reminders, the levels and the fasting before a blood test all read
 * food rows. Their energy is shown in the dialog and not counted.
 *
 * A cup that is mostly milk is food, so it is the row [JournalEntry.caffeineMg] was made for: food
 * carrying its caffeine, with the energy and macronutrients of the cup ([nutrientsForCup]) and its
 * millilitres as the grams. Every reader of the day's caffeine goes through [caffeineMgOf], which
 * adds food's milligrams to the cups'. It points at the catalogue's cappuccino as its
 * [JournalEntry.foodSource], in [encodeDish]'s `id=grams`, which is what marks it as a cup
 * ([isCaffeineCup]): its energy is summed with the day's food, but it is not counted as a meal
 * ([countsAsMeal]) and it stays out of the recent meals, whose repeat would drop its caffeine.
 */
internal fun cupEntry(
    title: String,
    drink: CaffeineDrink,
    millilitres: Int,
    day: String,
    occurredAt: Long,
): JournalEntry {
    val milligrams = caffeineForCup(drink, millilitres).toDouble()
    if (!drink.withMilk) {
        return JournalEntry(
            day = day,
            occurredAt = occurredAt,
            kind = KIND_CAFFEINE,
            title = title,
            // The drink by its enum name, as a stool's kind is kept: the day list names it in the
            // language showing then ([ownTitleRes]), not in the one it was poured in.
            subtitle = drink.name,
            calories = 0.0,
            servingG = milligrams,
        )
    }
    val cup = nutrientsForCup(drink, millilitres)
    val grams = energyMillilitres(drink, millilitres).toDouble()
    // The columns a meal row carries beside its energy, from the catalogue row the cup points at: a
    // day's fibre, sugar, sodium and the rest are only counted when every row of the day carries
    // them, so a cup without them would take its whole day out of those figures.
    val per100 = TOP_FOODS_BY_ID[CAPPUCCINO_FOOD_ID]?.nutrition
    fun part(value: Double?) = value?.let { it * grams / 100.0 }
    return JournalEntry(
        day = day,
        occurredAt = occurredAt,
        kind = KIND_EAT,
        title = title,
        calories = cup.kcal,
        proteinG = cup.proteinG,
        fatG = cup.fatG,
        carbsG = cup.carbsG,
        servingG = grams,
        fiberG = part(per100?.fiberG),
        sugarG = part(per100?.sugarG),
        saturatedFatG = part(per100?.saturatedFatG),
        sodiumMg = part(per100?.sodiumMg),
        cholesterolMg = part(per100?.cholesterolMg),
        caffeineMg = milligrams,
        foodSource = "$CAPPUCCINO_FOOD_ID=$grams",
    )
}

/**
 * "1 200 of 2 400 ml", or the same in fluid ounces for the profile that reads in them. Two whole
 * sentences rather than a figure with a unit glued on, because the word between the numbers moves
 * with the language. The water widget prints the same line, so it reads it from here.
 */
internal fun waterOfTargetText(resources: Resources, millilitres: Int, targetMl: Int, imperial: Boolean): String =
    if (imperial) {
        resources.getString(
            R.string.water_value_fl_oz,
            formatVolumeNumber(millilitres.toDouble(), true, resources.configuration.locales[0]),
            formatVolumeNumber(targetMl.toDouble(), true, resources.configuration.locales[0]),
        )
    } else {
        resources.getString(R.string.water_value, millilitres, targetMl)
    }

internal fun waterEntry(resources: Resources, millilitres: Int, day: String) = JournalEntry(
    day = day,
    occurredAt = System.currentTimeMillis(),
    kind = KIND_WATER,
    title = resources.getString(R.string.water_title),
    calories = 0.0,
    servingG = millilitres.toDouble(),
)

internal fun moodEntry(resources: Resources, mood: Mood, energy: Energy?, day: LocalDate) =
    moodEntry(resources::getString, mood, energy, day)

/**
 * The mood row, as this page writes it: the face, the tension when it was answered, and the two
 * words of them as its note, in the language [strings] gives. The chat writes its mood through the
 * same factory, at the moment its dial was taken ([at]) and with what the mood was about ([payload],
 * [encodeMoodPayload]); the page's own rows carry none.
 */
internal fun moodEntry(
    strings: (Int) -> String,
    mood: Mood,
    energy: Energy?,
    day: LocalDate,
    at: Long = System.currentTimeMillis(),
    payload: String? = null,
) = JournalEntry(
    day = day.toString(),
    occurredAt = at,
    kind = KIND_MOOD,
    title = strings(R.string.mood_title),
    calories = 0.0,
    moodScore = mood.score,
    energyScore = energy?.score,
    note = listOfNotNull(strings(mood.titleRes), energy?.let { strings(it.titleRes) }).joinToString(", "),
    measurementPayloadJson = payload,
)
