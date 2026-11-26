package com.pwnall.health

import android.content.res.Resources
import androidx.annotation.StringRes
import java.util.Locale
import kotlin.math.roundToInt

// The unit factors, in one table. Every reader, scale, ruler and card that turns a pound, an inch,
// a mile or a fluid ounce into the kilograms, centimetres, metres and millilitres the journal keeps
// reads its figure from here; a copy in another file is a second place to be wrong.

/** The avoirdupois pound: 0.45359237 kg exactly, by the agreement of 1959. */
internal const val KILOGRAMS_IN_A_POUND = 0.45359237

/**
 * The same pound the other way up, to ten places. Kept as this literal rather than
 * `1 / KILOGRAMS_IN_A_POUND`, so a load typed in pounds lands on exactly the kilograms already stored
 * for the same figure.
 */
internal const val POUNDS_IN_A_KILOGRAM = 2.2046226218

/** The international inch — 25.4 mm exactly — and the foot, yard and statute mile built on it. */
internal const val CENTIMETRES_IN_AN_INCH = 2.54
internal const val METRES_IN_AN_INCH = 0.0254
internal const val METRES_IN_A_FOOT = 0.3048
internal const val METRES_IN_A_YARD = 0.9144
internal const val METRES_IN_A_MILE = 1_609.344
internal const val METRES_IN_A_CENTIMETRE = 0.01
internal const val METRES_IN_A_KILOMETRE = 1_000.0
internal const val MILLILITRES_IN_A_LITRE = 1_000.0

/**
 * The weight of the person the profile describes, in kilograms: a measurement outside it is not one.
 *
 * One bound for the editor, the importers, Health Connect, the doctor's sheet and the weight the app
 * counts with ([StepsDao.latestMeasuredWeightKg]): a reader that skipped it would let a child's 9 kg
 * on a connected scale become the weight every formula uses.
 * The two ends are constants of their own because a query can only be built from constants.
 */
internal const val MIN_PLAUSIBLE_KG = 20.0
internal const val MAX_PLAUSIBLE_KG = 400.0
internal val PLAUSIBLE_KG = MIN_PLAUSIBLE_KG..MAX_PLAUSIBLE_KG

/**
 * The inch the other way up, to ten places, as the height wheels use it. Kept as this literal rather
 * than `1 / CENTIMETRES_IN_AN_INCH`, so a stored height keeps the feet and inches it is shown with.
 */
internal const val INCHES_IN_A_CENTIMETRE = 0.3937007874

/**
 * The US fluid ounce — a 128th of the 3.785411784 l gallon — and not the imperial one of 28.4 ml.
 * The setting is offered as "US / Imperial", and the cups the person choosing it drinks from are
 * marked in the American kind; the imperial ounce is here for the exports that state it.
 */
internal const val MILLILITRES_IN_A_US_FLUID_OUNCE = 29.5735295625
internal const val MILLILITRES_IN_AN_IMPERIAL_FLUID_OUNCE = 28.4130625

/**
 * Whether this profile is read in miles and fluid ounces.
 *
 * The one place the stored spelling is compared, so a screen asks for the answer instead of writing
 * the literal out again: a typo in "IMPERIAL" fails silently, as metric, and no test could catch it
 * in every copy of the literal.
 */
val UserProfile.imperial: Boolean get() = unitSystem == "IMPERIAL"

/**
 * Metres as the number a distance is printed with: kilometres, or miles where those are used.
 *
 * Unrounded — the templates carry the `%.2f`, so the rounding happens once, where the number
 * becomes text.
 */
fun distanceValue(metres: Double, imperial: Boolean): Double =
    if (imperial) metres / METRES_IN_A_MILE else metres / METRES_IN_A_KILOMETRE

/** Millilitres as litres, or as US fluid ounces. Unrounded; [formatVolumeNumber] rounds it. */
fun volumeValue(millilitres: Double, imperial: Boolean): Double =
    if (imperial) millilitres / MILLILITRES_IN_A_US_FLUID_OUNCE else millilitres / MILLILITRES_IN_A_LITRE

/**
 * "3.42 km", or "2.13 mi". Two templates rather than a number and a unit glued together, because
 * the unit is baked into the English wording and a translator needs the whole sentence to move.
 */
fun formatDistance(resources: Resources, metres: Double, imperial: Boolean): String =
    resources.getString(
        if (imperial) R.string.track_distance_miles else R.string.track_distance,
        distanceValue(metres, imperial),
    )

/**
 * The volume without its unit: the water cards print the figure large and the unit small underneath,
 * so the two reach the screen separately — [volumeUnitRes] is the other half.
 *
 * [pretty] to a single decimal, and to none when the number is whole: "2 l", and "16.9 fl oz" —
 * "16,9" where [locale] writes a decimal comma. Display alone: nothing reads this figure back.
 */
fun formatVolumeNumber(millilitres: Double, imperial: Boolean, locale: Locale): String =
    volumeValue(millilitres, imperial).pretty(locale)

/** The caption that goes under [formatVolumeNumber]. */
@StringRes
fun volumeUnitRes(imperial: Boolean): Int = if (imperial) R.string.unit_fl_oz else R.string.unit_litres

/**
 * Metres as the number a height is printed with: feet where the profile reads in miles.
 *
 * Heights get no [distanceValue] of their own, because a hundred metres of climb written in miles is
 * a row of zeroes. So the imperial half of this app is miles across the ground and feet up it, which
 * is how a trail sign in the United States is written too — and how the day's climb up the stairs is
 * read there as well.
 */
fun heightValue(metres: Double, imperial: Boolean): Double =
    if (imperial) metres / METRES_IN_A_FOOT else metres

/**
 * Celsius as the number a temperature is printed with: Fahrenheit where the profile reads in miles.
 *
 * Only the printing moves. Every band in [Vital], every stored reading and everything that leaves
 * for Health Connect stays on the Celsius scale the thermometer wrote, so this is called where a
 * figure becomes text and never any earlier — a conversion applied before a comparison would grade
 * 37.8 °C against a fever threshold of 38 and call a Fahrenheit reading normal.
 */
fun temperatureValue(celsius: Double, imperial: Boolean): Double =
    if (imperial) celsius * 9.0 / 5.0 + 32.0 else celsius

/** The caption a temperature is printed with — see [temperatureValue] for what stays Celsius. */
@StringRes
fun temperatureUnitRes(imperial: Boolean): Int =
    if (imperial) R.string.vitals_unit_fahrenheit else R.string.vitals_unit_celsius

/**
 * A portion of water as it is offered and as it is listed back: "330 ml", or "11.2 fl oz".
 *
 * The stored figure is millilitres whatever the profile says, and this is display alone. The metric
 * side prints the round number a bottle is actually sold in rather than the litres [volumeValue]
 * gives — 0.3 l is not what anybody pours — while the imperial side is the honest conversion of that
 * same round number, because inventing an 8 fl oz portion would log 237 ml where 250 was drunk.
 */
fun formatVolume(resources: Resources, millilitres: Int, imperial: Boolean): String = if (imperial) {
    resources.getString(
        R.string.water_entry_value_fl_oz,
        formatVolumeNumber(millilitres.toDouble(), true, resources.configuration.locales[0]),
    )
} else {
    resources.getString(R.string.water_entry_value, millilitres)
}

/** The same portion on a widget button, where the unit is already in the line above it: "+330". */
fun formatVolumeAdd(resources: Resources, millilitres: Int, imperial: Boolean): String = if (imperial) {
    resources.getString(
        R.string.water_add_fl_oz,
        formatVolumeNumber(millilitres.toDouble(), true, resources.configuration.locales[0]),
    )
} else {
    resources.getString(R.string.water_add, millilitres)
}

/**
 * Feet and inches, rounded rather than truncated: 180 cm is 70.9 in, which is 5′11″ and not 5′10″,
 * and the pounds on the card beside it are rounded too.
 */
internal fun imperialHeight(heightCm: Int): String {
    val inches = (heightCm * INCHES_IN_A_CENTIMETRE).roundToInt()
    return "${inches / 12}′ ${inches % 12}″"
}

/**
 * Kilograms as pounds, unrounded: the card and the report each round the figure the way they round
 * the kilograms beside it, and one factor here keeps the two from printing different pounds.
 */
internal fun imperialWeight(weightKg: Double): Double = weightKg * POUNDS_IN_A_KILOGRAM
