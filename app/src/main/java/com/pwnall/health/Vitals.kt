package com.pwnall.health

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * The measurements the journal holds from cuffs, oximeters, thermometers and scales, shown to the
 * person they came from.
 *
 * Every number here is already in the journal. A Bluetooth cuff, oximeter, thermometer or body
 * composition scale writes it into [JournalEntry.measurementPayloadJson]; a chest strap and a
 * Health Connect import write a pulse and a body-fat percentage into columns of their own. The
 * vitals screen stores nothing of its own — no table, no column, no query.
 *
 * Modelled on [LabMarker] deliberately, down to reusing [LabBand] and [LabStatus] and taking the
 * age the way [LabMarker.bandFor] takes it: one enum carrying the title, the unit, where the value
 * is kept, the band the reading is read against and the body that published that band. Nothing here
 * interprets a reading and nothing here diagnoses.
 *
 * A band nobody published is left empty rather than invented. Muscle mass, body water and basal
 * metabolism therefore carry no edges at all: sarcopenia thresholds are written about appendicular
 * skeletal muscle, not the whole-body figure a bathroom scale reports; total body water is
 * published as a share of weight, not as kilograms; and a resting metabolic rate has no normal
 * range because it is a function of the body that produced it. Those three are shown as a number
 * and a history, which is the whole truth about them.
 *
 * Glucose is not here on purpose: [LabMarker.GLUCOSE] already owns it, with its own bands, its own
 * fasting rules and its own screen, and a second home for it would be a second answer.
 *
 * Band edges are read exactly the way [LabMarker.status] reads them — at or above [LabBand.high] is
 * above the band, strictly below [LabBand.low] is below it. So every upper edge here is the first
 * value its guideline puts OUTSIDE the range, at the resolution that vital is measured in: 101 bpm,
 * because the AHA counts 100 as normal; 25.1% of body mass, because AACE's obesity cut-off is
 * "greater than" 25; 140/90, because the ESH's hypertension does begin at 140/90. The figure a card
 * prints as the top of a range is therefore [insideTop], one [Vital.step] below the edge.
 *
 * And where there is no band this app may honestly apply, there is no verdict at all rather than a
 * comforting one: [VitalReading.status] is null, which keeps the same silence an absent [LabBand]
 * edge keeps about the side it was never given. Three things do that — a vital nobody published a
 * band for, an adult band on somebody who is not an adult yet, and a thermometer that says it read
 * somewhere the fever threshold is not written for.
 */
enum class Vital(
    val titleRes: Int,
    val unitRes: Int,
    /** The key inside [JournalEntry.measurementPayloadJson]. Two vitals also have a column. */
    val key: String,
    /** What a real reading can be. Outside it the row is a parse artefact, not a measurement. */
    val valid: ClosedFloatingPointRange<Double>,
    val band: LabBand = LabBand(),
    /** Where the published band differs for women; null when no body publishes a different one. */
    val femaleBand: LabBand? = null,
    /** Who published [band], named on screen so a threshold can be checked rather than believed. */
    val sourceRes: Int? = null,
    /** Blood pressure is the only pair; these three are null on every other vital. */
    val secondaryKey: String? = null,
    val secondaryValid: ClosedFloatingPointRange<Double>? = null,
    val secondaryBand: LabBand? = null,
) {
    /**
     * The American Heart Association's resting adult range: "a normal resting heart rate for adults
     * ranges from 60 to 100 bpm", so 100 is inside it and the first value outside is 101.
     *
     * The pulse a fitness machine wrote is not in the series at all — see [WORKOUT_PULSE_SOURCES] —
     * and nor is the average a workout row carries from an imported file, rather than either being
     * graded against a resting band it was never a candidate for. What no row can say
     * is what the body was doing when a wearable took its one sample of the day, and that one is
     * still read against this band; the screen names the band a resting one so such a reading can
     * be discounted by eye.
     */
    HEART_RATE(
        titleRes = R.string.heart_rate,
        unitRes = R.string.unit_bpm,
        key = "pulseRateBpm",
        valid = 30.0..250.0,
        band = LabBand(high = 101.0, low = 60.0),
        sourceRes = R.string.vitals_source_heart_rate,
    ),

    /**
     * The resting rate a watch or a ring works out for itself, usually overnight, and hands to
     * Health Connect as a record of its own — a figure about rest by construction, which is the one
     * thing [HEART_RATE]'s one sample of the day can never say about itself.
     *
     * Kept apart from that series for exactly that reason: mixed in, a 52 measured asleep and a 96
     * sampled on the stairs would be averaged into a resting pulse neither of them was. The band is
     * the same AHA one, because it is the range that body wrote for a resting adult; and this is the
     * figure it was written for. The app never computes it, so it only ever arrives from Health
     * Connect or an export file.
     */
    RESTING_HEART_RATE(
        titleRes = R.string.vital_resting_heart_rate_title,
        unitRes = R.string.unit_bpm,
        key = "restingHeartRateBpm",
        valid = 25.0..200.0,
        band = LabBand(high = 101.0, low = 60.0),
        sourceRes = R.string.vitals_source_heart_rate,
    ),

    /**
     * ESH 2023 office grades: hypertension from 140/90, grade 3 from 180/110. Either half alone
     * grades the pair, which is the classification's own wording — and it is the wording of the
     * HIGH side only.
     *
     * Nearly every reading here was taken out of an office — a cuff at home, a figure typed in — and
     * for those the same guideline draws its line at 135/85. The office grades are kept all the
     * same: the home figure is defined on the average of several days of readings and not on one of
     * them, a typed reading may well be a clinic's, and grade 3 has no home counterpart to move with
     * it. So 137/87 still reads "inside the band", and the card's own explanation says whose band
     * that is and where the home line runs ([vitalExplanations]).
     *
     * Both sets of figures are as the guideline prints them (Mancia, Kreutz et al., J Hypertens
     * 2023;41:1874–2071). The grades are its Table 1, "140–159 and/or 90–99" up to "≥180 and/or
     * ≥110", the category "defined by the highest level of BP, whether systolic or diastolic". The
     * home line is its Table 4, a "home BP mean" of "≥135 and/or ≥85", which section 4.7 takes over
     * seven days of readings, not fewer than three, with the first day's left out.
     *
     * The low edge is the NHS's "generally considered to be 90/60 mmHg or lower", one paired figure
     * and the only one a national body prints. No body publishes a diastolic hypotension threshold
     * on its own, so the diastolic carries no low edge here and the low side rests on the systolic
     * the NHS names. Read symmetrically instead, an ordinary 118/58 would come out below the band and
     * the card would colour the whole reading — and a low diastolic beside a normal systolic is common,
     * healthy, and the ordinary shape of an older person's wide pulse pressure.
     *
     * The plausible range is wider than the export's, which stops at Health Connect's own accepted
     * 200/180 because a record above that is one Health Connect refuses. A screen about a person's
     * own body must not quietly drop the highest reading their cuff ever gave them.
     */
    BLOOD_PRESSURE(
        titleRes = R.string.direct_bluetooth_blood_pressure_title,
        unitRes = R.string.vitals_unit_mmhg,
        key = "systolicMmhg",
        valid = 40.0..300.0,
        band = LabBand(high = 140.0, veryHigh = 180.0, low = 90.0, lowInclusive = true),
        sourceRes = R.string.vitals_source_blood_pressure,
        secondaryKey = "diastolicMmhg",
        secondaryValid = 20.0..200.0,
        secondaryBand = LabBand(high = 90.0, veryHigh = 110.0),
    ),

    /**
     * WHO's pulse oximetry training manual: a healthy person at sea level reads 95 to 100%, and
     * below 90% is the level its guidance calls an emergency.
     */
    OXYGEN_SATURATION(
        titleRes = R.string.direct_bluetooth_spo2,
        unitRes = R.string.labs_unit_percent,
        key = "spo2Percent",
        valid = 50.0..100.0,
        band = LabBand(low = 95.0, veryLow = 90.0),
        sourceRes = R.string.vitals_source_spo2,
    ),

    /**
     * Fever from 38.0 °C, the figure WHO's own surveillance case definitions use, and the European
     * Resuscitation Council's hypothermia staging below: under 35.0 °C is hypothermia, under
     * 32.0 °C is its moderate stage.
     *
     * The band is written for a core-equivalent reading, and the thermometer says where it read. So
     * a reading taken at the armpit, the ear lobe, a finger or a toe is not graded against it at
     * all — see [temperatureBandApplies]. Nothing is added to such a reading to make it gradable:
     * axillary sits below core by what is written down as "roughly 0.5 to 1.0 °C", which is a range
     * and not a figure this app could attribute to anybody, and half a degree is exactly the
     * difference between a fever and no fever. The number and the site are shown and the verdict is
     * left blank, because 37.6 at an armpit is a real fever graded normal by the band above.
     */
    BODY_TEMPERATURE(
        titleRes = R.string.direct_bluetooth_temperature_title,
        unitRes = R.string.vitals_unit_celsius,
        key = "temperatureCelsius",
        valid = 25.0..45.0,
        band = LabBand(high = 38.0, low = 35.0, veryLow = 32.0),
        sourceRes = R.string.vitals_source_temperature,
    ),

    /**
     * The one vital whose published band genuinely differs by sex. AACE's obesity guidance is body
     * fat "greater than" 25% of body mass for men and 35% for women, so at the tenth of a percent a
     * Body Composition Service actually decodes, the first value outside is 25.1 and 35.1.
     *
     * There is no floor, for the reason muscle mass, body water and basal metabolism have no edges
     * either: nobody published one. ACSM's 3% and 12% are essential fat — the physiological minimum
     * below which a body cannot run — and printing them as the bottom of a normal range would tell
     * a man at 4% that he is inside it, which is the false-reassurance direction. They are not in
     * [valid] either: that gate is one range for both sexes, and 1 to 80% is what separates an
     * impedance artefact from a measurement of somebody.
     *
     * No unisex band exists to fall back on, so a profile that names neither sex is read against
     * the male one — the same thing [LabMarker.bandFor] does with its own sex-specific markers.
     */
    BODY_FAT(
        titleRes = R.string.direct_bluetooth_body_fat,
        unitRes = R.string.labs_unit_percent,
        key = "bodyFatPercent",
        valid = 1.0..80.0,
        band = LabBand(high = 25.1),
        femaleBand = LabBand(high = 35.1),
        sourceRes = R.string.vitals_source_body_fat,
    ),

    /** No band: the published sarcopenia cut-offs are about appendicular muscle, not this figure. */
    MUSCLE_MASS(
        titleRes = R.string.direct_bluetooth_muscle,
        unitRes = R.string.unit_kg,
        key = "muscleMassKg",
        valid = 1.0..150.0,
    ),

    /** No band: total body water is published as a share of body mass, never as kilograms. */
    BODY_WATER(
        titleRes = R.string.direct_bluetooth_body_water,
        unitRes = R.string.unit_kg,
        key = "bodyWaterMassKg",
        valid = 1.0..200.0,
    ),

    /**
     * No band, and a unit that has to be converted: the Body Composition Service reports basal
     * metabolism in kilojoules, and [valid] — like everything this app prints — is kilocalories.
     */
    BASAL_METABOLISM(
        titleRes = R.string.direct_bluetooth_basal_metabolism,
        unitRes = R.string.unit_kcal,
        key = "basalMetabolismKj",
        valid = 200.0..10_000.0,
    ),

    /**
     * The waist, and the one circumference any body has published numbers for.
     *
     * WHO's 2008 expert consultation on waist circumference and waist–hip ratio, which the IDF and
     * NICE both restate: risk of metabolic complications is raised from 94 cm in men and 80 cm in
     * women, and substantially raised from 102 cm and 88 cm. The first value outside each band is
     * therefore the cut-off itself, as everywhere else here.
     *
     * Those thresholds were derived in European adults and the same report says so, which is why
     * the band is withheld from anybody under [ADULT_BAND_AGE] and why the source line names who
     * published it. The app applies the numbers; it does not vouch for them for every body.
     */
    WAIST(
        titleRes = R.string.measurement_waist_title,
        unitRes = R.string.unit_cm,
        key = "waistCircumferenceCm",
        valid = 30.0..250.0,
        band = LabBand(high = 94.0, veryHigh = 102.0),
        femaleBand = LabBand(high = 80.0, veryHigh = 88.0),
        sourceRes = R.string.vitals_source_waist,
    ),

    /**
     * The hips. No band: what the guidance publishes is the waist-to-hip *ratio*, and a hip
     * measurement on its own is a number a tailor uses, not a threshold anybody set.
     */
    HIP(
        titleRes = R.string.measurement_hip_title,
        unitRes = R.string.unit_cm,
        key = "hipCircumferenceCm",
        valid = 40.0..250.0,
    ),

    /** The chest. No band at all: nobody publishes one, and it is recorded to be watched move. */
    CHEST(
        titleRes = R.string.measurement_chest_title,
        unitRes = R.string.unit_cm,
        key = "chestCircumferenceCm",
        valid = 40.0..250.0,
    ),

    /**
     * Heart-rate variability as RMSSD, the root mean square of successive RR differences.
     *
     * No band, and the reason is worth stating: published RMSSD values move with age, posture,
     * breathing, the length of the recording and the device that took it, and there is no
     * population range a phone can grade a strap's two-minute sample against. What a person can do
     * with it is watch their own figure over time, which is what the card shows.
     */
    HRV(
        titleRes = R.string.measurement_hrv_title,
        unitRes = R.string.unit_ms,
        key = "hrvRmssdMs",
        valid = 1.0..500.0,
    ),
    ;

    /** Whether a reading of this vital is two numbers over one another rather than one. */
    val paired: Boolean get() = secondaryKey != null

    /** Whether any body published edges for this figure at all. */
    val banded: Boolean get() = band != LabBand() || femaleBand != null

    /** Whether the published band is an adult one and nobody else's — see [ADULT_ONLY_VITALS]. */
    val adultOnly: Boolean get() = this in ADULT_ONLY_VITALS

    /**
     * The finest step this vital is really measured in, and so the distance between the last value
     * inside a band and the first one outside it.
     *
     * A Body Composition Service decodes body fat at a tenth of a percent and a clinical
     * thermometer reads to a tenth of a degree; a beat, a millimetre of mercury and a percent of
     * saturation are counted whole. Held as a property rather than an eleventh constructor
     * argument, for the reason [LabMarker.derived] gives for the same thing.
     */
    val step: Double get() = if (this == BODY_FAT || this == BODY_TEMPERATURE) 0.1 else 1.0
}

/**
 * One reading, at the instant the device or the person recorded it.
 *
 * [status] is settled here rather than at draw time, the way [LabReading] settles its own: the sex
 * and the age a band depends on belong to the person, not to the card.
 */
data class VitalReading(
    val vital: Vital,
    val value: Double,
    /**
     * The diastolic half of a blood pressure, and null on every other vital.
     *
     * Kept beside [value] rather than modelled as a vital of its own, because 128 and 84 were one
     * squeeze of one cuff. A screen holding two separate series would average systolics over one
     * set of rows and diastolics over another and print a pressure that never happened.
     */
    val secondary: Double?,
    val occurredAt: Long,
    val day: String,
    /**
     * Where on the body the thermometer said it read. Null on every other vital, on the
     * thermometers that send no site at all, and on a name this app cannot place — for which
     * [siteName] is the one that still remembers something was written.
     */
    val site: TemperatureType?,
    /**
     * The site exactly as the payload spelled it, kept beside [site] because a null [site] cannot
     * distinguish "no thermometer said anything" from "a thermometer named somewhere I have no
     * name for", and those two get opposite answers — see [temperatureBandApplies].
     */
    val siteName: String?,
    /**
     * Which side of its band the reading fell on, or null when no band could honestly be read
     * against it. Null is not "normal": it is the number standing on its own, which is what this
     * screen has to say when a band nobody wrote for this reading is the only band there is.
     */
    val status: LabStatus?,
    /**
     * The row's own [JournalEntry.measurementSource], carried rather than resolved.
     *
     * A reading handed to somebody else has to be able to say whether a cuff took it or a thumb
     * typed it, and the series is the only place that can be counted — one screen's worth of
     * readings is regularly both. Null is a row that never said, which is not the same as manual
     * and is not turned into it here.
     */
    val source: String? = null,
)

/**
 * What a series says taken together.
 *
 * [count] is on the record rather than hidden inside it because a mean over two readings is not a
 * statistic worth the word, and only the screen can decide how to say so.
 */
data class VitalStats(
    val vital: Vital,
    /** The newest reading, which carries its own instant in [VitalReading.occurredAt]. */
    val latest: VitalReading,
    val lowest: VitalReading,
    val highest: VitalReading,
    val mean: Double,
    /** The mean diastolic over the very same readings [mean] averaged; null off blood pressure. */
    val meanSecondary: Double?,
    val count: Int,
)

/**
 * Every reading of one vital in the rows handed in, oldest first.
 *
 * The rows are the ones the caller already holds — the calendar loads a range, the day list loads a
 * day — so nothing here asks the database for anything it has not already been given.
 *
 * [profile] is read for two things and nothing else: the sex a band may depend on, spelled the way
 * [UserProfile.sex] spells it, and the birth date the age on the day of each reading comes from.
 */
fun vitalSeries(entries: List<JournalEntry>, vital: Vital, profile: UserProfile): List<VitalReading> =
    entries.sortedBy(JournalEntry::occurredAt).mapNotNull { it.reading(vital, profile) }

/** The same, for every vital at once, sorting the rows once. A vital with no reading is absent. */
fun vitalHistory(entries: List<JournalEntry>, profile: UserProfile): Map<Vital, List<VitalReading>> {
    val sorted = entries.sortedBy(JournalEntry::occurredAt)
    return Vital.entries.mapNotNull { vital ->
        sorted.mapNotNull { it.reading(vital, profile) }
            .takeIf { it.isNotEmpty() }
            ?.let { vital to it }
    }.toMap()
}

/**
 * Latest, lowest, highest, mean and how many, over one vital's series. Null when there is none.
 *
 * Lowest and highest are whole readings rather than bare numbers, so a blood pressure keeps its
 * pair: the lowest reading is the one with the lowest systolic, printed with the diastolic that was
 * measured with it. The mean is the single place the two halves are averaged apart, which is how a
 * home blood-pressure diary is averaged anyway — and both halves are averaged over exactly the same
 * readings, never over two different sets.
 */
fun vitalStats(readings: List<VitalReading>): VitalStats? {
    if (readings.isEmpty()) return null
    val seconds = readings.mapNotNull(VitalReading::secondary)
    return VitalStats(
        vital = readings.first().vital,
        latest = readings.maxBy(VitalReading::occurredAt),
        lowest = readings.minBy(VitalReading::value),
        highest = readings.maxBy(VitalReading::value),
        mean = readings.map(VitalReading::value).average(),
        meanSecondary = seconds.takeIf { it.size == readings.size }?.average(),
        count = readings.size,
    )
}

/**
 * A person's own usual figure, and how far the latest reading sits from it.
 *
 * [median] is taken over the readings dated in the [BASELINE_DAYS] days before the latest one's
 * day — that day itself excluded, so the reading being judged is never part of what it is judged
 * against — and [count] is how many there were. [delta] is the latest minus the median.
 */
data class VitalBaseline(val median: Double, val count: Int, val delta: Double)

/**
 * The two vitals whose meaning is the person's own trend rather than a published range: variability
 * has no population band at all, and a resting rate that drifts five beats above where it usually
 * sits says more about the last week than its place inside 60 to 100 does.
 */
val BASELINE_VITALS: Set<Vital> = setOf(Vital.RESTING_HEART_RATE, Vital.HRV)

/**
 * The baseline of [readings], or null when there is not enough history to call one usual.
 *
 * A median, not a mean, because one night with a strap slipping or a fever is exactly the reading a
 * baseline should not move for; and at least [BASELINE_MIN_READINGS] of them, because the median of
 * two nights is a coin toss. The window is what the caller's rows cover: a calendar showing a week
 * may hold too little and simply shows no baseline, rather than one built from the days it has.
 */
fun vitalBaseline(readings: List<VitalReading>): VitalBaseline? {
    val latest = readings.maxByOrNull(VitalReading::occurredAt) ?: return null
    val latestDay = latest.day.toLocalDateOrNull() ?: return null
    val from = latestDay.minusDays(BASELINE_DAYS)
    val prior = readings.mapNotNull { reading ->
        val day = reading.day.toLocalDateOrNull() ?: return@mapNotNull null
        reading.value.takeIf { day >= from && day < latestDay }
    }.sorted()
    if (prior.size < BASELINE_MIN_READINGS) return null
    val middle = prior.size / 2
    val median = if (prior.size % 2 == 1) prior[middle] else (prior[middle - 1] + prior[middle]) / 2.0
    return VitalBaseline(median = median, count = prior.size, delta = latest.value - median)
}

/** Four weeks: long enough to hold a training block's ups and downs, short enough to follow a season. */
internal const val BASELINE_DAYS = 28L

/** Fewer than five readings make a median that one more night would overturn. */
internal const val BASELINE_MIN_READINGS = 5

/**
 * The band a reading is read against — the female one wherever a body published a different one —
 * or null when there is no band this app may honestly apply to this person.
 *
 * [ageYears] is the age on the day of the reading, the way [LabMarker.bandFor] takes it. Below
 * [ADULT_BAND_AGE] the adult bands — every vital in [ADULT_ONLY_VITALS] — are withheld rather
 * than stretched over a child. A ten-year-old at 118/78 is stage 1 hypertensive by AAP 2017 and sits
 * comfortably inside the adult band, so the reading is shown with no verdict instead: the percentile
 * of age, sex and height a child's pressure is really read against is not something this app holds,
 * and inventing it would be worse than saying nothing.
 */
fun Vital.bandFor(sex: String, ageYears: Int): LabBand? = when {
    !banded -> null
    adultOnly && ageYears < ADULT_BAND_AGE -> null
    sex == "FEMALE" && femaleBand != null -> femaleBand
    else -> band
}

/**
 * Which side of its band a reading fell on, or null where there is no band to fall either side of.
 *
 * Grading a pressure by whichever half is worse is what both the ESH and the ESC classifications
 * do: 150/85 is grade 1 on its systolic alone, 130/112 grade 3 on its diastolic alone. That is the
 * high side. The low side is one paired figure, 90/60, and is read on the systolic alone — the
 * diastolic simply has no low edge, so a low diastolic under an ordinary systolic says nothing here
 * and grades nothing.
 */
fun Vital.status(
    value: Double,
    secondary: Double?,
    sex: String,
    ageYears: Int,
    siteName: String? = null,
): LabStatus? {
    // A thermometer that says it read an armpit has said this band is not the one to read it by,
    // and the honest answer about a reading nobody can grade is no answer.
    val band = bandFor(sex, ageYears)?.takeIf { temperatureBandApplies(siteName) } ?: return null
    val pairBand = secondaryBand
    val pair = if (pairBand != null && secondary != null) {
        pairBand.statusOf(secondary)
    } else {
        LabStatus.NORMAL
    }
    return maxOf(band.statusOf(value), pair)
}

/**
 * The largest reading still inside a band whose upper edge is [edge]: the figure to print.
 *
 * [LabBand.high] and [LabBand.veryHigh] are the first value outside, because that is the comparison
 * [LabMarker.status] makes and because it is how the guidelines are worded — tachycardia over 100
 * bpm, obesity above 25% of body mass. A card printing the edge itself as the top of the range
 * would print 101 where the AHA prints 100 and 25.1 where AACE prints 25, and the same screen would
 * then be grading against one number while naming another.
 */
fun Vital.insideTop(edge: Double): Double = edge - step

/**
 * The smallest reading still inside a band whose lower edge is [edge]: the edge itself, since below
 * is strictly below — and one [Vital.step] above it where [band] counts that edge as low already
 * ([LabBand.lowInclusive]). Otherwise the pressure's band would print the NHS's "90 or lower" as the
 * start of its range, "90 – 139/89", and colour 90 as low: the bottom of the range doing what
 * [insideTop] stops the top from doing.
 */
fun Vital.insideBottom(edge: Double, band: LabBand): Double =
    if (band.lowInclusive && edge == band.low) edge + step else edge

/**
 * The figure as this profile reads it, on the scale they read it on — and the only thing about a
 * vital that a unit setting is allowed to move.
 *
 * Six vitals are stored in a unit that is not the one an imperial profile reads. The thermometer
 * writes Celsius, the scale writes kilograms of muscle and kilograms of water, and the tape writes
 * centimetres of waist, hip and chest — [Vital.key] says so in as many letters, `temperatureCelsius`,
 * `muscleMassKg`, `bodyWaterMassKg`, `waistCircumferenceCm` and its two neighbours. All six stay in
 * those units everywhere a number is compared, stored, backed up or handed to Health Connect; this
 * is called where a figure becomes text and never any earlier. A conversion applied before a
 * comparison would grade 37.8 °C against a fever threshold of 38 and call a Fahrenheit reading
 * normal, and neither of the two mass vitals has a band to be graded against at all.
 *
 * The other seven do not move. A beat, the milliseconds between two of them, a millimetre of
 * mercury and a percent of anything are the same figure in both systems, and basal metabolism is
 * printed in kilocalories, which the United States counts its food in too.
 */
internal fun Vital.displayValue(value: Double, imperial: Boolean): Double = when {
    !imperial -> value
    this == Vital.BODY_TEMPERATURE -> temperatureValue(value, true)
    this in MASS_VITALS -> imperialWeight(value)
    this in GIRTH_VITALS -> imperialGirth(value)
    else -> value
}

/**
 * The caption beside [displayValue], and the one place [Vital.unitRes] is allowed to be overruled.
 *
 * The enum keeps naming °C and kg, and stays truthful in doing so: those are the units the reading
 * is stored in, graded in and exported in, and a unit on the enum that changed with a profile
 * setting would be a claim about all three.
 */
internal fun Vital.displayUnitRes(imperial: Boolean): Int = when {
    !imperial -> unitRes
    this == Vital.BODY_TEMPERATURE -> temperatureUnitRes(true)
    this in MASS_VITALS -> R.string.unit_lb
    this in GIRTH_VITALS -> R.string.unit_in
    else -> unitRes
}

/** The two vitals measured as a mass of the body, which an imperial profile reads in pounds. */
private val MASS_VITALS = setOf(Vital.MUSCLE_MASS, Vital.BODY_WATER)

/** The three measured with a tape, which an imperial profile reads in inches. */
private val GIRTH_VITALS = setOf(Vital.WAIST, Vital.HIP, Vital.CHEST)

/** Centimetres to inches, the one conversion a tape measure needs. */
internal fun imperialGirth(cm: Double): Double = cm / CENTIMETRES_IN_AN_INCH

/**
 * One reading as a person reads it out, on the scale they read it on: a bare number, or a pressure
 * over its diastolic. Written with [locale]'s digits and decimal mark, which a screen passes as the
 * reader's — "36,6" in Russian; the doctor's sheet leaves it at [Locale.US], the point every other
 * figure on the sheet is printed with ([pretty]).
 *
 * Called on band edges, which are exact by construction. Anything that came off a device or out of
 * an average goes through [readingText] instead.
 */
internal fun Vital.printed(value: Double, secondary: Double?, imperial: Boolean, locale: Locale = Locale.US): String {
    val first = displayValue(value, imperial).pretty(locale)
    val second = secondary?.let { displayValue(it, imperial).pretty(locale) }
    return if (second == null) first else "$first/$second"
}

/**
 * A measured figure, both halves of it, printed at the resolution it was graded at.
 *
 * The card and the doctor's report both print through this, [printed] and [atStep], so a figure on
 * the sheet a doctor is handed cannot disagree with the same figure on the phone it came from — a
 * temperature in Fahrenheit on one and in Celsius on the other.
 */
internal fun readingText(
    vital: Vital,
    value: Double,
    secondary: Double?,
    imperial: Boolean,
    locale: Locale = Locale.US,
): String = vital.printed(vital.atStep(value), secondary?.let(vital::atStep), imperial, locale)

/**
 * A measured figure at the resolution its band was compared at, rounded the one way that cannot
 * disagree with the verdict beside it: down.
 *
 * [Double.pretty] prints a tenth HALF_UP, so a body fat of 25.05 — graded NORMAL, because 25.05 is
 * below the 25.1 that is the first value outside — would print as "25.1" in the colour of a reading
 * inside the band, beside a range whose next line says it runs up to 25. Every published edge in
 * [Vital] sits on a multiple of [Vital.step], and rounding down at that step cannot cross one, so a
 * figure shown as 25.1 was graded as 25.1 and never as 25.0.
 *
 * Down at the step the band is written in, and therefore before [displayValue] and never after: the
 * band was compared in the unit the reading was stored in, and flooring a Fahrenheit figure at a
 * Celsius resolution would put a tenth back where this exists to take one away.
 *
 * [BigDecimal] rather than arithmetic on the double, because 25.1 / 0.1 is 250.99999999999997 and a
 * floor of that would print the first value outside the band as the last one inside it.
 *
 * [BigDecimal.valueOf] and never the `BigDecimal(double)` constructor, which is the same mistake
 * one layer down: the constructor takes the exact binary expansion, and 37.9 stored as a double is
 * 37.89999999999999857, which floors to 37.8. Eighty of the two hundred and one tenths a
 * thermometer can report inside [Vital.BODY_TEMPERATURE]'s valid range would come out a tenth low
 * that way — a fever of 37.9 read out as 37.8. [BigDecimal.valueOf] goes through the shortest decimal
 * that round-trips, which is the tenth the device actually sent, and still floors the 25.05 above
 * to 25.0, because that figure is a real hundredth and not an artefact of binary.
 *
 * A figure nobody published a band for is printed as it was measured. There is no edge for it to
 * disagree with, and [Vital.step] is the resolution of a band rather than of a scale: muscle mass
 * really is reported to a tenth of a kilogram and would lose that tenth for nothing.
 */
internal fun Vital.atStep(value: Double): Double = if (!banded) {
    value
} else {
    BigDecimal.valueOf(value).setScale(if (step < 1.0) 1 else 0, RoundingMode.FLOOR).toDouble()
}

/**
 * Whether the fever and hypothermia band may be read against a temperature taken at [site].
 *
 * The armpit runs below core by what is published as a range — roughly half a degree to a whole one
 * — rather than as a figure, and the Health Thermometer Service's EAR is the lobe (TYMPANUM is the
 * drum), while a finger and a toe are skin further out still. None of those four is on the scale
 * 38.0 °C is written on, and none of them is offset onto it here.
 *
 * A thermometer that named no site is still graded: nothing in the reading says the band is the
 * wrong one to read it by. BODY, which the specification glosses as
 * "body (general)", says exactly as little as saying nothing, and is treated the same way.
 *
 * A site that was named and cannot be placed is the opposite case, and closes rather than opens.
 * [TemperatureType] is the Bluetooth profile's list; Health Connect's is longer and carries WRIST
 * and FOREHEAD, which are as peripheral as an armpit. Reading an unplaceable name as "no site" would
 * put the core threshold back over exactly the readings this rule exists to keep it off, so the answer
 * to a name this app cannot place is no answer.
 */
fun temperatureBandApplies(siteName: String?): Boolean = if (siteName == null) {
    true
} else {
    temperatureSite(siteName)?.let { it !in PERIPHERAL_TEMPERATURE_SITES } ?: false
}

/**
 * The site as a person reads it, for the line a temperature is printed with.
 *
 * The Health Thermometer Service's own list in its own terms, EAR and TYMPANUM kept apart because
 * they are two different readings rather than two names for one.
 */
val TemperatureType.labelRes: Int
    get() = when (this) {
        TemperatureType.ARMPIT -> R.string.vitals_site_armpit
        TemperatureType.BODY -> R.string.vitals_site_body
        TemperatureType.EAR -> R.string.vitals_site_ear
        TemperatureType.FINGER -> R.string.vitals_site_finger
        TemperatureType.GASTROINTESTINAL_TRACT -> R.string.vitals_site_gi_tract
        TemperatureType.MOUTH -> R.string.vitals_site_mouth
        TemperatureType.RECTUM -> R.string.vitals_site_rectum
        TemperatureType.TOE -> R.string.vitals_site_toe
        TemperatureType.TYMPANUM -> R.string.vitals_site_tympanum
    }

/**
 * Which side of the band a value fell on.
 *
 * The same four comparisons in the same order as [LabMarker.status], because that classification
 * lives inside the [LabMarker] enum and cannot be called with a band of someone else's: at or above
 * [LabBand.high] is above, strictly below [LabBand.low] is below, and an absent edge is an edge the
 * band does not have — so a band with no edge on a side says nothing about that side.
 *
 * Private to this file: [Vital.status] is the way to ask, and a second file declaring the same
 * helper in this package would be a redeclaration rather than a reuse.
 */
private fun LabBand.statusOf(value: Double): LabStatus = when {
    veryHigh != null && value >= veryHigh -> LabStatus.FAR_ABOVE
    veryLow != null && value < veryLow -> LabStatus.FAR_BELOW
    high != null && value >= high -> LabStatus.ABOVE
    low != null && (value < low || (lowInclusive && value == low)) -> LabStatus.BELOW
    else -> LabStatus.NORMAL
}

/** The reading this row holds for one vital, or null when it holds none the band can be read on. */
private fun JournalEntry.reading(vital: Vital, profile: UserProfile): VitalReading? {
    val value = vital.valueIn(this)?.takeIf { it in vital.valid } ?: return null
    val secondary = vital.secondaryKey?.let { key ->
        payloadNumber(key)?.takeIf { it in (vital.secondaryValid ?: vital.valid) }
    }
    // Half a blood pressure is not a blood pressure: there is nothing to print the systolic over,
    // and a lone one in the series would pull a mean towards a reading nobody ever measured.
    //
    // Nor is a pair whose halves are the wrong way round. The two numbers are gated against their
    // own plausible ranges, and this checks the one relation that makes them a measurement — the
    // diastolic is the pressure between beats and is below the systolic by construction. A payload
    // of 90/150 passes both gates; without this check it would grade FAR_ABOVE on its diastolic and
    // go into the mean and the highest as a squeeze no cuff can have taken.
    return if (vital.paired && (secondary == null || secondary >= value)) {
        null
    } else {
        val siteName = if (vital == Vital.BODY_TEMPERATURE) payloadSiteName() else null
        val site = siteName?.let(::temperatureSite)
        VitalReading(
            vital = vital,
            value = value,
            secondary = secondary,
            occurredAt = occurredAt,
            day = day,
            site = site,
            siteName = siteName,
            // The age on the day of the reading rather than today's, the same way [labHistory]
            // reads a panel: a measurement from four years ago belongs to who gave it.
            status = vital.status(value, secondary, profile.sex, ageOn(day, profile.birthDate), siteName),
            source = measurementSource,
        )
    }
}

/**
 * The vital's number on this row, before its plausibility gate.
 *
 * Two of them are kept in a column as well as in a payload, and both places are read. A pulse is a
 * column when a chest strap, the editor or a Health Connect import wrote the row, and a payload key
 * when a cuff or an oximeter did. A body-fat percentage is a column on every row a scale or an
 * import wrote and a payload key on the rows a Body Composition Service scale filled out in full.
 */
private fun Vital.valueIn(entry: JournalEntry): Double? = when (this) {
    Vital.HEART_RATE -> entry.restingPulse(key)

    Vital.BODY_FAT -> entry.bodyFatPercent ?: entry.payloadNumber(key)

    // The scale reports this one in kilojoules and every screen in this app prints kilocalories, so
    // the division happens once, here, rather than in each place that would otherwise print kJ.
    Vital.BASAL_METABOLISM -> entry.payloadNumber(key)?.div(KJ_PER_KCAL)

    else -> entry.payloadNumber(key)
}

/**
 * The pulse on this row, unless the row says a workout produced it.
 *
 * The end of a workout saves the machine's last beat as a measurement row of its own, and a spin
 * class ends at 165: read against a resting range that grades above, and it drags the mean and the
 * highest this screen prints as though both described rest. The row records who wrote it, so the
 * sources that are workouts by construction — [WORKOUT_PULSE_SOURCES] — are left out of the series
 * altogether rather than graded against a band they were never candidates for.
 *
 * So is a workout row itself. A run imported from a Garmin, Strava or other export file keeps the
 * session's average heart rate in the same column a resting pulse is kept in, and that average is
 * a fact about the run: it stays on the activity row, where the journal prints it, and never
 * reaches a vitals series, a verdict or the doctor's sheet as a pulse.
 */
private fun JournalEntry.restingPulse(key: String): Double? {
    val source = measurementSource
    return if (kind == KIND_ACTIVITY || (source != null && source in WORKOUT_PULSE_SOURCES)) {
        null
    } else {
        measurementHeartRateBpm?.toDouble() ?: payloadNumber(key)
    }
}

/**
 * A number out of the stored payload, read by pattern rather than through `JSONObject`.
 *
 * The reason [medSlotOf] and [JournalEntry.cycleLog] give for doing the same: `org.json` is a stub
 * on the unit-test classpath and throws instead of parsing, so a payload only a real device can
 * read is a payload no test can hold to — and these are numbers about a person's body, where the
 * extraction is exactly the part that has to be pinned. [JournalEntry.payloadValue] stays the
 * reader everywhere the answer does not have to be visible to a test.
 *
 * Limitation: the pattern reads a bare JSON number following the key, and the leading quote is what
 * keeps `spo2Percent` from matching inside `fastSpo2Percent`. Every writer in this app puts these
 * keys at the top level of the payload with a numeric value, so that is all it needs to see; a
 * quoted number, or one of these keys nested inside another object, would be missed.
 */
private fun JournalEntry.payloadNumber(key: String): Double? =
    measurementPayloadJson
        // The cheap substring test first: this runs over the whole journal once per vital.
        ?.takeIf { key in it }
        ?.let { payload -> PAYLOAD_NUMBERS[key]?.find(payload) }
        ?.groupValues
        ?.get(1)
        ?.toDoubleOrNull()

/**
 * Where the thermometer said it read, out of the same payload and by the same means.
 *
 * Only ever asked of a row that already gave up a temperature, so the glucose payload's own numeric
 * `type` is never in front of it — and could not match anyway, the pattern wanting a quoted name.
 *
 * The raw name rather than the parsed site, because the two answers this has to tell apart are
 * "nothing was named" and "something was named that I cannot place", and a null [TemperatureType]
 * says both. [temperatureBandApplies] is what decides; this only reports what was written.
 */
private fun JournalEntry.payloadSiteName(): String? =
    measurementPayloadJson
        ?.let { payload -> PAYLOAD_SITE.find(payload) }
        ?.groupValues
        ?.get(1)

/**
 * Whether a birth date is a date at all.
 *
 * [ageOn] answers zero for anything it cannot parse, which is indistinguishable from a newborn and
 * therefore from a child — so a profile carrying no birth date at all would have its adult bands
 * withheld and be told the range "is not applied under 18", which is a statement about an age nobody
 * knows.
 * A restored backup reaches this: [Backup] writes an empty string where it has neither a date nor a
 * year. The screen asks this to tell the two silences apart.
 */
fun birthDateKnown(birthDate: String): Boolean =
    runCatching { java.time.LocalDate.parse(birthDate) }.isSuccess

/** The site behind a written name, or null when this app has no name for it. */
private fun temperatureSite(name: String): TemperatureType? =
    TemperatureType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

/** One compiled pattern per stored key, built once rather than per row. */
private val PAYLOAD_NUMBERS: Map<String, Regex> = Vital.entries
    .flatMap { listOfNotNull(it.key, it.secondaryKey) }
    .associateWith { Regex(""""$it"$SPACE*:$SPACE*(-?[0-9.eE+]+)""") }

/** The thermometer's site, written by the same hand as the keys above and read the same way. */
private val PAYLOAD_SITE = Regex(""""type"$SPACE*:$SPACE*"([A-Za-z_]+)""")

/**
 * The two sources whose pulse is a workout's by construction: the Fitness Machine Service's
 * end-of-workout beat and the Concept2 PM5's, each saved as its own measurement row.
 *
 * What is deliberately still read: a chest strap somebody pressed save on, the pulse a cuff or an
 * oximeter takes beside its own reading, a rate typed into the editor, and Health Connect's one
 * sample a day — the wearable's latest of that day, taken whatever the body was doing, which
 * nothing in the row records. That last one cannot be told apart from a resting rate here, and is
 * not pretended to be one.
 *
 * Limitation: the label on the row is the whole of what this reads, and the label is not permanent.
 * Every row carrying a pulse goes out to Health Connect as a one-sample heart rate, and
 * [HealthImport.readOwnHeartRate] brings this app's own records back as HEALTH_CONNECT — so an
 * end-of-workout beat that has been through a reinstall comes home unlabelled and is read as a rest.
 * Telling those apart would want a column saying what the body was doing, which is not worth a
 * migration for the one reading a restore brings back.
 */
private val WORKOUT_PULSE_SOURCES = setOf("BLUETOOTH_FTMS", "BLUETOOTH_CONCEPT2_PM5")

/** The sites a core-temperature threshold is not written for — see [temperatureBandApplies]. */
private val PERIPHERAL_TEMPERATURE_SITES = setOf(
    TemperatureType.ARMPIT,
    TemperatureType.EAR,
    TemperatureType.FINGER,
    TemperatureType.TOE,
)

/**
 * The bands published for adults and nobody else, held as a set rather than as a constructor flag
 * because the enum is already at detekt's parameter limit — the reason [LabMarker.derived] gives.
 *
 * These in the words of the bodies themselves: the AHA's range is "for adults", and both heart-rate
 * vitals are read against it; the ESH/ESC office grades are an adult classification; AACE's cut-offs
 * are adult ones; and WHO's waist thresholds were derived in adults. WHO's saturation figures, its
 * fever threshold and the Resuscitation Council's hypothermia staging are not written for adults
 * alone, so those two vitals keep their bands at every age.
 */
private val ADULT_ONLY_VITALS =
    setOf(Vital.HEART_RATE, Vital.RESTING_HEART_RATE, Vital.BLOOD_PRESSURE, Vital.BODY_FAT, Vital.WAIST)

/**
 * Eighteen, read out of the birth date through [ageOn] like every other age in this app.
 *
 * Paediatric blood pressure is a percentile of age, sex and height up to 13 and a fixed 130/80
 * above it (AAP 2017), while the ESH's paediatric guidance hands over to the adult criteria at 16.
 * Taking the latest of those means the years the sources disagree in are years this screen says
 * nothing in, which is the one direction a wrong verdict cannot come from. A birth date nothing can
 * parse is zero years old and silent for the same reason: [ageOn] closes when it cannot tell.
 */
private const val ADULT_BAND_AGE = 18

/**
 * Kilojoules per kilocalorie. [exportBodyRecords] keeps its own copy because that one is private to
 * the Health Connect export; the number is the definition of the thermochemical calorie, not a
 * choice either file made.
 */
private const val KJ_PER_KCAL = 4.184
