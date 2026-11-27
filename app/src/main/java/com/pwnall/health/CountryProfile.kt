package com.pwnall.health

import java.util.Locale

/**
 * Which health system a report is being taken into, and the few things that honestly depends on.
 *
 * A country profile is about **presentation**, not about compliance. Choosing one does not make this
 * app conform to anything, and nothing in the app may tell a person it does: there is no HIPAA, GDPR,
 * EHDS or NHS claim here, and the profiles carry no certification. What a profile decides is the
 * units a figure is printed in and how a date is written.
 *
 * The domain model underneath stays international: a weight is stored in kilograms whatever a
 * profile prints. A profile may never change what was recorded — only how it is shown.
 */
interface CountryProfile {
    /** ISO 3166-1 alpha-2, or "XX" for the international profile, which belongs to no country. */
    val id: String

    val displayNameRes: Int

    /**
     * How this system writes a date: a pattern for [java.time.format.DateTimeFormatter.ofPattern],
     * so the fields stand in this order in every language and only the month's name is translated.
     * The sheet asks [datePatternIn], which is this pattern for every profile that names a country.
     */
    val datePattern: String

    /**
     * Whether the sheet is printed on that system's customary scale rather than the metric one:
     * pounds, feet and inches, Fahrenheit and the blood tests' mg/dL together. Null where the
     * profile has no answer of its own — see [ReportRequest.imperial].
     *
     * One switch, because one switch is what the sheet reads.
     */
    val imperial: Boolean?
}

/**
 * The international profile, for every country without one of its own.
 *
 * The units are whatever the person set the app to, and the date is unambiguous. "11/09" is two
 * different days on two sides of an ocean, and a sheet that may cross one writes the month in
 * letters.
 */
object InternationalProfile : CountryProfile {
    override val id = "XX"
    override val displayNameRes = R.string.country_international
    override val datePattern = "d MMM yyyy"
    override val imperial: Boolean? = null
}

/** United States: pounds, feet and inches, Fahrenheit, mg/dL and the month first. */
object UnitedStatesProfile : CountryProfile {
    override val id = "US"
    override val displayNameRes = R.string.country_us
    override val datePattern = "MMM d, yyyy"
    override val imperial = true
}

/**
 * Germany.
 *
 * Glucose is the one unit this profile refuses to be clever about. The regulator's own guidance
 * records that mg/dL is customary in the west of the country and mmol/L in the east, so there is no
 * national answer to print; the unit the app stores, mmol/L, is the one printed.
 */
object GermanyProfile : CountryProfile {
    override val id = "DE"
    override val displayNameRes = R.string.country_de
    override val datePattern = "dd.MM.yyyy"
    override val imperial = false
}

/** United Kingdom: metric, with the month written out. */
object UnitedKingdomProfile : CountryProfile {
    override val id = "GB"
    override val displayNameRes = R.string.country_gb
    override val datePattern = "d MMMM yyyy"
    override val imperial = false
}

/** Australia: metric, with the month written out. */
object AustraliaProfile : CountryProfile {
    override val id = "AU"
    override val displayNameRes = R.string.country_au
    override val datePattern = "d MMMM yyyy"
    override val imperial = false
}

/**
 * The pattern this profile's sheet writes a date with in [locale]'s language.
 *
 * A national profile's order is that country's, in every language. The international one names no
 * country, so it has no order to insist on: day, month in letters, year reads naturally in most of
 * the languages the app is in, but in Chinese, Japanese and Korean — which write the year first — it
 * would print "5 9月 2026" on every line of the sheet. Those three get their own order; the month is
 * still unmistakable there, because it carries its own character.
 */
fun CountryProfile.datePatternIn(locale: Locale): String =
    YEAR_FIRST_PATTERNS[locale.language].takeIf { id == InternationalProfile.id } ?: datePattern

/** How the languages that write the year first write a whole date, by ISO 639 language code. */
private val YEAR_FIRST_PATTERNS = mapOf(
    "zh" to "y年M月d日",
    "ja" to "y年M月d日",
    "ko" to "y년 M월 d일",
)

/**
 * The profiles the app ships, and the one it falls back to.
 *
 * A country is here only when its customary units and date format could be read off an official
 * source. Every other country in the world gets [InternationalProfile].
 */
object CountryProfileRegistry {
    val international: CountryProfile = InternationalProfile

    val all: List<CountryProfile> = listOf(
        InternationalProfile,
        UnitedStatesProfile,
        GermanyProfile,
        UnitedKingdomProfile,
        AustraliaProfile,
    )

    /** The profile for an ISO 3166-1 alpha-2 code, or the international one where none is shipped. */
    fun of(id: String?): CountryProfile =
        all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: international
}
