package com.pwnall.health

// The vocabulary a report for a doctor is assembled in, before it is a page.
//
// Nothing here knows about Android resources, a renderer or a country. It is the one place the app
// says what a measurement *is* — what it was measured in and where it came from — so that every
// decision in it can be pinned by a test without a device.

/**
 * The units the app stores and prints, spelled the way UCUM spells them.
 *
 * [ucum] is what a lab record stores; [labelRes] is what a person reads. The two are
 * separate on purpose — "°C" is a label, `Cel` is a unit — and no country profile may change the
 * first.
 */
enum class UcumUnit(val ucum: String, val labelRes: Int) {
    KILOGRAM("kg", R.string.unit_kg),
    POUND("[lb_av]", R.string.unit_lb),
    CENTIMETRE("cm", R.string.unit_cm),
    PERCENT("%", R.string.labs_unit_percent),
    CELSIUS("Cel", R.string.vitals_unit_celsius),
    FAHRENHEIT("[degF]", R.string.vitals_unit_fahrenheit),
    MILLIMOLE_PER_LITRE("mmol/L", R.string.labs_unit_mmol),
    MILLIGRAM_PER_DECILITRE("mg/dL", R.string.labs_unit_mgdl),
    MILLIGRAM_PER_LITRE("mg/L", R.string.labs_unit_mgl),
    GRAM_PER_LITRE("g/L", R.string.labs_unit_gl),

    // Haemoglobin's other unit, the one LOINC 718-7 is reported in: a panel records which of the two
    // its figure was typed in, and without a spelling for this one the field could offer no choice.
    GRAM_PER_DECILITRE("g/dL", R.string.labs_unit_gdl),
    NANOGRAM_PER_MILLILITRE("ng/mL", R.string.labs_unit_ngml),
    NANOMOLE_PER_LITRE("nmol/L", R.string.labs_unit_nmol),
    PICOMOLE_PER_LITRE("pmol/L", R.string.labs_unit_pmol),

    // The two a lab field can be switched to that nothing else uses: B12's pg/mL and free T4's ng/dL.
    // Both validate as written against UCUM 2.2 on tx.fhir.org.
    PICOGRAM_PER_MILLILITRE("pg/mL", R.string.labs_unit_pgml),
    NANOGRAM_PER_DECILITRE("ng/dL", R.string.labs_unit_ngdl),

    // The urine albumin-to-creatinine ratio's two, and urine pH, validate as written against UCUM
    // on tx.fhir.org. pH takes the brackets: a bare "pH" validates too, as picohenry.
    MILLIGRAM_PER_MILLIMOLE("mg/mmol", R.string.labs_unit_mgmmol),
    MILLIGRAM_PER_GRAM("mg/g", R.string.labs_unit_mgg),
    PH("[pH]", R.string.labs_unit_none),
    MILLIUNIT_PER_LITRE("m[IU]/L", R.string.labs_unit_miul),
    MICROUNIT_PER_MILLILITRE("u[IU]/mL", R.string.labs_unit_uiuml),
    UNIT_PER_LITRE("U/L", R.string.labs_unit_ul),
    GIGA_PER_LITRE("10*9/L", R.string.labs_unit_e9l),
    MICROMOLE_PER_LITRE("umol/L", R.string.labs_unit_umol),

    // HbA1c's IFCC unit, the one a British sheet prints it in alone: a panel records which of its two
    // units the figure was typed in, and without a spelling for this one the field could offer no choice.
    MILLIMOLE_PER_MOLE("mmol/mol", R.string.labs_unit_mmolmol),

    /**
     * The filtration rate, with the body-surface normalisation UCUM writes as an annotation.
     *
     * "mL/min" alone would be a different quantity: what a laboratory reports is per 1.73 m² of
     * body surface. The annotated form validates against the terminology server as written.
     */
    MILLILITRE_PER_MINUTE_PER_BSA("mL/min/{1.73_m2}", R.string.labs_unit_egfr),
    COUNT("1", R.string.unit_count),
}

/** Where a datum came from, in words a doctor can use, without naming a package. */
enum class ProvenanceCategory(val labelRes: Int) {
    MANUAL(R.string.provenance_manual),
    DEVICE(R.string.provenance_device),
    PHONE_SENSOR(R.string.provenance_phone),
    HEALTH_CONNECT(R.string.provenance_health_connect),
    LABORATORY(R.string.provenance_laboratory),
    IMPORTED_APP(R.string.provenance_imported),
    ESTIMATE(R.string.provenance_estimate),
    UNKNOWN(R.string.provenance_unknown),
}

/**
 * How a series of readings reached the phone, counted rather than named.
 *
 * A doctor reading a blood-pressure series needs to know whether the cuff wrote it or somebody
 * typed it, and where a series has both, saying only one would be wrong. [detail] is the device's
 * own name where a device gave one; it is never a package name.
 */
data class Provenance(val category: ProvenanceCategory, val detail: String? = null)

/**
 * Whether a section has anything in it, and if not, which kind of nothing.
 *
 * The three empties are different answers and a doctor reads them differently: "no known allergies"
 * is a statement about the person, "no data" is a statement about the app, and "not included" is a
 * statement about the person's choice. Collapsing them into a blank section is the single most
 * misleading thing a summary can do.
 */
enum class SectionPresence(val labelRes: Int) {
    PRESENT(R.string.section_present),
    NO_KNOWN(R.string.section_no_known),
    NO_DATA(R.string.section_no_data),
    NOT_INCLUDED(R.string.section_not_included),
}

/**
 * How sensitive a section is, which decides what a tick can do without being asked twice.
 *
 * [SENSITIVE] is not a stronger word for "personal": it is the class of data whose disclosure can
 * put somebody at risk, and in this app that is sexual activity. It is never selected by a
 * "select all", never carried by a preset, and never included without its own answer.
 *
 * [REPRODUCTIVE] is an ordinary tick that nobody makes by default: the page opens without it and
 * "My own choice" leaves it off, and only a doctor it was written for, or the person, ticks it.
 * [ROUTINE] is what the page opens with.
 */
enum class DataSensitivity { ROUTINE, REPRODUCTIVE, SENSITIVE }

/**
 * How complete a series is over the period asked for: what a doctor needs in order to know whether
 * an average is an average of a month or of four days.
 *
 * Printed as "18 of 30 days" rather than left to be assumed. [expectedDays] is null where the datum
 * is not one a day is expected to hold — a blood test is not missing on the days nobody drew blood.
 */
data class Completeness(val daysWithData: Int, val expectedDays: Int?, val measurements: Int)
