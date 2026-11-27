package com.pwnall.health

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * One reader per service, each argued at itself.
 *
 * What they have in common is [HealthExportReader]: a test that says which of an archive's files are
 * this service's, and a function handed all of them at once. What they do not have in common is
 * anything else — these twelve formats agree on nothing, not the date, not the units, not whether a
 * unit is stated at all, and not whether a number that is missing is written as a blank, a nought or
 * the word `null`.
 */

// ─── the shapes several of them share ───────────────────────────────────────────────────────────

/**
 * A day and a clock out of two columns, as one instant.
 *
 * Cronometer and MyFitnessPal both keep them apart, and both leave the clock empty on an entry
 * somebody backdated. A row with no clock is filed at midday for the reason [ExportInstants] already
 * gives about a dateless day: midnight is the one hour where being an hour out moves the row to
 * another date. A row whose clock no shape reads is filed there too, which is why the clock is only
 * looked at ([ExportInstants.peek]) and the day is what the row stands or falls by.
 */
internal fun HealthReadContext.dayAndClock(day: String, clock: String): Long? {
    if (day.isBlank()) return null
    val stamp = if (clock.isBlank()) day else "$day $clock"
    return instants.peek(stamp) ?: instants.of(day)
}

/** The day an instant falls on, where a record is filed by day rather than by moment. */
internal fun Long.localDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/**
 * A mass in whatever unit the file named, as kilograms.
 *
 * The unit arrives as a word out of the file — a cell of its own in Cronometer, part of a column
 * heading in MyFitnessPal — and the words are not a fixed set, so an unrecognised one is read as
 * kilograms rather than refused. That is the same last resort [unitLessFactor] takes and is right
 * for the same reason: the alternative is dropping a real weighing.
 */
internal fun Double.asKilograms(unit: String): Double = when (unit.trim().lowercase(Locale.ROOT)) {
    "lb", "lbs", "pound", "pounds" -> this / POUNDS_IN_A_KILOGRAM
    "st", "stone", "stones" -> this * KILOGRAMS_IN_A_STONE
    "g" -> this / GRAMS_IN_A_KILOGRAM
    else -> this
}

/**
 * A temperature in whatever unit the file named, as degrees Celsius.
 *
 * The unit is spelled four ways across these files — `degF` in Apple's, a bare `F` in Cronometer's,
 * and `°C` or `C` elsewhere — so the letters are what is read and the rest is thrown away. Getting
 * this wrong is not a rounding error: 98.6 read as Celsius is a temperature nobody has survived.
 */
internal fun Double.asCelsius(unit: String): Double {
    val letters = unit.filter(Char::isLetter).lowercase(Locale.ROOT).removePrefix("deg")
    return if (letters.startsWith("f")) (this - FAHRENHEIT_ZERO) / FAHRENHEIT_PER_CELSIUS else this
}

/**
 * A distance in whatever unit the file named, as metres.
 *
 * Apple states the unit beside every figure and the others state metres; an unnamed unit is read as
 * kilometres because that is what the one caller that omits it writes.
 */
internal fun Double.metresFrom(unit: String): Double = when (unit.lowercase(Locale.ROOT)) {
    "mi" -> this * METRES_IN_A_MILE
    "m" -> this
    "ft" -> this * METRES_IN_A_FOOT
    "yd" -> this * METRES_IN_A_YARD
    else -> this * METRES_IN_A_KILOMETRE
}

/**
 * Energy as kilocalories.
 *
 * Three spellings mean the same magnitude — `kcal`, and HealthKit's `Cal` with a capital letter,
 * which is the large calorie — and only `kJ` is a different number. Matching on `kcal` alone reads
 * every modern Apple workout as burning nothing; see [WorkoutRecord].
 */
internal fun Double.kilocaloriesFrom(unit: String): Double =
    if (unit.equals("kJ", ignoreCase = true)) this / KJ_IN_A_KCAL else this

/**
 * A volume as millilitres, in the unit spellings HealthKit writes: `mL`, `L`, and the fluid ounces
 * and cups it keeps the underscores in — `fl_oz_us`, `fl_oz_imp`, `cup_us`, `cup_imp`.
 */
internal fun String?.millilitres(unit: String): Double? = this?.toDoubleOrNull()?.let {
    when (unit.lowercase(Locale.ROOT)) {
        "l" -> it * MILLILITRES_IN_A_LITRE
        "fl_oz_us" -> it * MILLILITRES_IN_A_US_FLUID_OUNCE
        "fl_oz_imp" -> it * MILLILITRES_IN_AN_IMPERIAL_FLUID_OUNCE
        "cup_us" -> it * MILLILITRES_IN_A_US_CUP
        "cup_imp" -> it * MILLILITRES_IN_AN_IMPERIAL_CUP
        else -> it
    }
}

/** A mass as milligrams, in the unit spellings HealthKit writes. */
internal fun String?.milligrams(unit: String): Double? = this?.toDoubleOrNull()?.let {
    when (unit.lowercase(Locale.ROOT)) {
        "g" -> it * MILLIGRAMS_IN_A_GRAM
        "mcg" -> it / MICROGRAMS_IN_A_MILLIGRAM
        else -> it
    }
}

/**
 * A percentage, out of a file that writes some of them as fractions.
 *
 * HealthKit's own unit for a body-fat share is the fraction, and the export prints `unit="%"` beside
 * a value of `0.184`; an oxygen saturation off a third-party oximeter arrives as `98`. No attribute
 * distinguishes them, so the magnitude does: no share of a body and no saturation this app would
 * keep is at or below one percent, and every fraction is.
 */
internal fun String?.asPercent(): Double? =
    this?.toDoubleOrNull()?.let { if (it <= 1.0) it * WHOLE_PERCENT else it }

internal const val KILOGRAMS_IN_A_STONE = 6.35029318
internal const val GRAMS_IN_A_KILOGRAM = 1_000.0
internal const val FAHRENHEIT_ZERO = 32.0
internal const val FAHRENHEIT_PER_CELSIUS = 1.8
internal const val MILLILITRES_IN_A_US_CUP = 236.5882365
internal const val MILLILITRES_IN_AN_IMPERIAL_CUP = 284.130625
internal const val MILLIGRAMS_IN_A_GRAM = 1_000.0
internal const val MICROGRAMS_IN_A_MILLIGRAM = 1_000.0
internal const val MINUTES_IN_AN_HOUR = 60.0

// ─── MyFitnessPal ───────────────────────────────────────────────────────────────────────────────

/**
 * MyFitnessPal, whose export is exactly three CSVs and whose traps are all about units.
 *
 * The energy column *renames itself*: an account set to kilojoules exports `Kilojoules` where a
 * kilocalorie account exports `Calories`, and a reader that matched only the second would read
 * every metric-energy user's whole diary as nought. The same trick names the strength column
 * `Pounds` or `Kilograms`. And the body weight in the third file states no unit at all — so the unit
 * is taken from the *exercise* file's column heading where there is one, and from this app's own
 * profile where there is not, which is the same last resort [unitLessFactor] takes.
 *
 * Two things in the export are deliberately not imported. There is a `Steps` column, and it is only
 * ever filled on the rows MyFitnessPal calls a "Health Connect calorie adjustment" — those steps
 * came *out* of Health Connect, and feeding them back into an app that reads Health Connect would
 * be importing a number from itself. Those adjustment rows are not workouts either: they are an
 * energy delta with a placeholder duration of one minute, and importing them as exercise would put
 * a daily one-minute workout in the journal for years.
 *
 * The diary is meal-level. There are no per-food rows anywhere in the export, so a day is four or
 * five journal rows named after the meals, which is all the file can support.
 */
internal fun myFitnessPalReader() = HealthExportReader(
    app = HealthExportApp.MYFITNESSPAL,
    claims = { part ->
        part is ExportTable && (part.isMfpNutrition() || part.isMfpExercise() || part.isMfpMeasurement())
    },
    read = { parts, context ->
        parts.tables().flatMap { table ->
            when {
                table.isMfpNutrition() -> table.mfpNutrition(context)

                table.isMfpExercise() -> {
                    if (!context.mfpHasExerciseTable) {
                        context.mfpHasExerciseTable = true
                        when {
                            table.has("kilograms") -> context.mfpWeightUnit = "kg"
                            table.has("pounds") -> context.mfpWeightUnit = "lb"
                        }
                    }
                    table.mfpExercise(context)
                }

                else -> {
                    table.collectMfpWeights(context)
                    emptyList()
                }
            }
        }
    },
)

private fun ExportTable.isMfpNutrition() = has("date", "meal") && (has("calories") || has("kilojoules"))

/**
 * The exercise file renames its energy column exactly as the nutrition file does — an account set
 * to kilojoules exports `Exercise Kilojoules` — which a reader matching only the first spelling
 * would fail to recognise as MyFitnessPal's at all.
 */
private fun ExportTable.isMfpExercise() = has("date", "exercise", "exerciseminutes") &&
    (has("exercisecalories") || has("exercisekilojoules"))

/**
 * The third file is claimed by its name as well as its columns, which nothing else here does.
 *
 * Its header can be as little as `Date,Weight`, and two columns that generic would claim half the
 * CSVs on a phone. Inside an archive the name is the service's own — `Measurement-Summary-…` — and
 * is therefore evidence in a way a loose file's name is not; a loose `Date,Weight` CSV is left
 * alone rather than guessed at.
 */
private fun ExportTable.isMfpMeasurement() =
    has("date", "weight") && name.lowercase(Locale.ROOT).contains("measurement")

private fun ExportTable.mfpNutrition(context: HealthReadContext): List<HealthRecord> {
    val kilojoules = has("kilojoules")
    return rows.mapNotNull { row ->
        val day = row["date"]
        if (!day.isIsoDay()) return@mapNotNull null
        val meal = row["meal"].ifBlank { return@mapNotNull null }
        val at = context.dayAndClock(day, row["time"]) ?: return@mapNotNull null
        val energy = row.number(if (kilojoules) "kilojoules" else "calories") ?: 0.0
        NutritionRecord(
            atMs = at,
            wallClock = true,
            title = meal,
            meal = meal,
            calories = if (kilojoules) energy / KJ_IN_A_KCAL else energy,
            proteinG = row.number("proteing") ?: 0.0,
            fatG = row.number("fatg") ?: 0.0,
            carbsG = row.number("carbohydratesg") ?: 0.0,
            fiberG = row.number("fiber"),
            sugarG = row.number("sugar"),
            saturatedFatG = row.number("saturatedfat"),
            sodiumMg = row.number("sodiummg"),
            cholesterolMg = row.number("cholesterol"),
        )
    }
}

private fun ExportTable.mfpExercise(context: HealthReadContext): List<HealthRecord> {
    val kilojoules = has("exercisekilojoules")
    val energy = if (kilojoules) "exercisekilojoules" else "exercisecalories"
    return rows.mapNotNull { row ->
        val day = row["date"]
        if (!day.isIsoDay()) return@mapNotNull null
        val name = row["exercise"].trim('"').trim()
        // Not a workout: an energy delta MyFitnessPal computed from a tracker, with a placeholder
        // duration of one minute. One export can hold a hundred and seventy of them, and the family
        // is wider than any one name — Health Connect, Samsung, MapMyFitness and Fitbit all write
        // one — so the match is on the words they end with rather than on a list of sources.
        if (name.isBlank() || name.contains(MFP_ADJUSTMENT, ignoreCase = true)) return@mapNotNull null
        val burned = row.number(energy)?.takeIf { it > 0.0 }
        WorkoutRecord(
            startMs = context.dayAndClock(day, "") ?: return@mapNotNull null,
            wallClock = true,
            name = name,
            seconds = row.number("exerciseminutes")?.times(SECONDS_IN_A_MINUTE)?.toInt(),
            calories = if (kilojoules) burned?.div(KJ_IN_A_KCAL) else burned,
        )
    }
}

/** What MyFitnessPal calls the row that is a tracker's arithmetic rather than an exercise. */
private const val MFP_ADJUSTMENT = "calorie adjustment"

/**
 * Whether a cell is a plain `2024-07-02`, which is how a broken row is spotted.
 *
 * MyFitnessPal writes its `Note` column *unquoted*, so a note holding a comma pushes every later
 * cell along and a note holding a line break splits one record into two. The second half of such a
 * record begins with the rest of somebody's sentence where the date should be, and this is what
 * stops it being read as a day.
 */
private fun String.isIsoDay(): Boolean = ISO_DAY.matches(trim())

private val ISO_DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

private fun ExportTable.collectMfpWeights(context: HealthReadContext) {
    // Only the weight column is guaranteed. Other measurements have names chosen by the user.
    rows.forEach { row ->
        val weight = row.number("weight") ?: return@forEach
        val at = context.dayAndClock(row["date"], "") ?: return@forEach
        context.mfpWeights += at to weight
    }
}

// The exercise table might name the unit after the measurements have already been read.
internal fun HealthReadContext.myFitnessPalWeights(): List<WeightRecord> = mfpWeights.mapNotNull { (at, weight) ->
    val kg = weight.asKilograms(mfpWeightUnit).takeIf { it in PLAUSIBLE_KG } ?: return@mapNotNull null
    WeightRecord(atMs = at, kg = kg, wallClock = true)
}

// ─── Cronometer ─────────────────────────────────────────────────────────────────────────────────

/**
 * Cronometer, which exports one file per kind and is the best-behaved of the twelve.
 *
 * Its units are written into the column headings and stay there whatever the account is set to, so
 * `Energy (kcal)` is kilocalories for everybody. The one exception is `biometrics.csv`, which is a
 * long table — a row is a metric name, a unit and a number — and there the unit is *data*, read per
 * row. That file is the reason this reader exists at all: it is where a person's weighings, blood
 * pressures, pulses and saturations are, and it is the only one of these twelve exports that keeps
 * them all in one place.
 *
 * `dailysummary.csv` is deliberately not read. It is the same food as `servings.csv` added up, and
 * importing both would put a day's meals in the journal twice — once as what was eaten and once as
 * the total of it.
 */
internal fun cronometerReader() = HealthExportReader(
    app = HealthExportApp.CRONOMETER,
    claims = { part ->
        part is ExportTable &&
            (part.isCronometerBiometrics() || part.isCronometerServings() || part.isCronometerExercises())
    },
    read = { parts, context ->
        parts.tables().flatMap { table ->
            when {
                table.isCronometerBiometrics() -> table.cronometerBiometrics(context)
                table.isCronometerServings() -> table.cronometerServings(context)
                else -> table.cronometerExercises(context)
            }
        }
    },
)

private fun ExportTable.isCronometerBiometrics() = has("day", "metric", "unit", "amount")

private fun ExportTable.isCronometerServings() = has("day", "foodname", "amount", "energykcal")

private fun ExportTable.isCronometerExercises() = has("day", "exercise", "minutes", "caloriesburned")

/**
 * The long table, one reading a row.
 *
 * `Metric` is free text and carries the instrument's name where the reading was synced rather than
 * typed — `Weight (Withings)` is a real value — so every match here is on a word inside it rather
 * than on the whole cell. A metric no rule claims is skipped: the file holds girths, ketones and
 * whatever else the person invented a tracker for, and this app has a column for none of them.
 *
 * A blood pressure arrives as one cell holding both halves, `120/80`, which is why it cannot be read
 * by [CsvRow.number] like everything else here.
 */
private fun ExportTable.cronometerBiometrics(context: HealthReadContext): List<HealthRecord> =
    rows.mapNotNull { row ->
        val at = context.dayAndClock(row["day"], row["time"]) ?: return@mapNotNull null
        val metric = row["metric"].lowercase(Locale.ROOT)
        val unit = row["unit"]
        val amount = row["amount"]
        val number = amount.replace(',', '.').toDoubleOrNull()
        when {
            metric.contains("blood pressure") -> amount.bloodPressure(at)

            metric.contains("weight") -> number?.asKilograms(unit)
                ?.takeIf { it in PLAUSIBLE_KG }
                ?.let { WeightRecord(at, it, wallClock = true) }

            metric.contains("body fat") -> number?.vital(at, Vital.BODY_FAT)

            // "Heart Rate Variability" is milliseconds between beats, a figure that sits inside a
            // pulse's range and would be imported as one; nor does the file say which of the two
            // measures of it this is, so it is no reading here.
            metric.contains("variability") -> null

            metric.contains("heart rate") || metric.contains("pulse") ->
                number?.vital(at, Vital.HEART_RATE)

            metric.contains("oxygen") || metric.contains("spo2") ->
                number?.vital(at, Vital.OXYGEN_SATURATION)

            metric.contains("temperature") -> number?.asCelsius(unit)?.vital(at, Vital.BODY_TEMPERATURE)

            else -> null
        }
    }

/** `120/80` in one cell, both halves of a pressure, as the long table writes one. */
private fun String.bloodPressure(at: Long): VitalRecord? {
    val halves = split('/', '\\').map { it.trim().toDoubleOrNull() }
    return if (halves.size == PRESSURE_HALVES && halves.none { it == null }) {
        // From a day and a clock with no zone, like every other reading of this file ([vital]).
        VitalRecord(at, Vital.BLOOD_PRESSURE, halves[0]!!, halves[1]!!, wallClock = true)
    } else {
        null
    }
}

private const val PRESSURE_HALVES = 2

private fun Double.vital(at: Long, vital: Vital) = VitalRecord(at, vital, this, wallClock = true)

/**
 * One logged food.
 *
 * `Amount` is one free-text cell holding a quantity and its unit together — `100.00 g`,
 * `1.00 cup, whole pieces`, `3.00 medium link - breakfast size` — so a portion in grams is only
 * recoverable from the ones that happen to be stated in grams, and the rest keep the words as the
 * subtitle instead. Nothing is converted out of cups: a cup of flour and a cup of milk are not the
 * same mass and the file does not say which this was.
 */
private fun ExportTable.cronometerServings(context: HealthReadContext): List<HealthRecord> =
    rows.mapNotNull { row ->
        val title = row["foodname"].ifBlank { return@mapNotNull null }
        val at = context.dayAndClock(row["day"], row["time"]) ?: return@mapNotNull null
        val amount = row["amount"]
        NutritionRecord(
            atMs = at,
            wallClock = true,
            title = title,
            meal = row["group"].ifBlank { amount },
            calories = row.number("energykcal") ?: 0.0,
            proteinG = row.number("proteing") ?: 0.0,
            fatG = row.number("fatg") ?: 0.0,
            carbsG = row.number("carbsg") ?: 0.0,
            servingG = amount.gramsStated(),
            fiberG = row.number("fiberg"),
            sugarG = row.number("sugarsg"),
            saturatedFatG = row.number("saturatedg"),
            sodiumMg = row.number("sodiummg"),
            cholesterolMg = row.number("cholesterolmg"),
            caffeineMg = row.number("caffeinemg"),
        )
    }

/** `100.00 g` as a hundred; anything measured in cups or links as nothing, because it is not a mass. */
private fun String.gramsStated(): Double =
    GRAMS_STATED.matchEntire(trim())?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

private val GRAMS_STATED = Regex("([0-9]+(?:\\.[0-9]+)?)$SPACE*(?:g|gram|grams)", RegexOption.IGNORE_CASE)

/**
 * One logged activity.
 *
 * The energy here can be negative, which is not an error: a row synced from a device as an
 * energy-balance adjustment — `Active Energy Balance (Apple Health)` is a real name — carries the
 * difference rather than a burn. Those are not workouts and are skipped rather than imported as one
 * that burned a negative number of calories.
 */
private fun ExportTable.cronometerExercises(context: HealthReadContext): List<HealthRecord> =
    rows.mapNotNull { row ->
        val name = row["exercise"].ifBlank { return@mapNotNull null }
        val calories = row.number("caloriesburned")
        val minutes = row.number("minutes")
        if (calories != null && calories < 0.0) return@mapNotNull null
        if (name.contains("energy balance", ignoreCase = true)) return@mapNotNull null
        WorkoutRecord(
            startMs = context.dayAndClock(row["day"], row["time"]) ?: return@mapNotNull null,
            wallClock = true,
            name = name,
            seconds = minutes?.takeIf { it > 0.0 }?.times(SECONDS_IN_A_MINUTE)?.toInt(),
            calories = calories?.takeIf { it > 0.0 },
        )
    }

// ─── Flo ────────────────────────────────────────────────────────────────────────────────────────

/**
 * Flo, whose export is JSON and whose dates are the single sharpest trap in this whole file.
 *
 * `period_start_date` looks like a date and is not one. It is an instant in UTC standing for the
 * user's own local midnight, so an account in Istanbul exports the 31st of October as
 * `2024-10-30 21:00:00.0`. A reader that took the first ten characters would move every period in
 * the history a day earlier, and one that handed the string to an ISO date parser — which is what a
 * shipping open-source period tracker does — throws on every row and imports nothing.
 *
 * So the string is read as the UTC instant it is and then asked what day it was in the phone's own
 * zone. That is exactly right for the ordinary case, which is somebody importing their own history
 * on a phone in the zone they were living in; a person who has since moved several zones can be a
 * day out, and there is nothing in the file that could tell us otherwise — the offset it was written
 * with is not in it.
 *
 * Flo has no self-service export: the file arrives from support in a password-protected ZIP with the
 * password in a second email, so what reaches this reader is the JSON somebody has already unpacked
 * on a desktop. The plain-text alternative Flo also offers is not read — it is a report written for
 * a person, with no header, no delimiter and localised labels, and a positional parser over it would
 * break silently rather than loudly.
 */
internal fun floReader() = HealthExportReader(
    app = HealthExportApp.FLO,
    claims = { part -> part is ExportDocument && part.text.contains(FLO_CYCLES) },
    read = { parts, context ->
        parts.filterIsInstance<ExportDocument>().flatMap { it.floPeriods(context.zone) }
    },
)

private const val FLO_CYCLES = "period_start_date"

private fun ExportDocument.floPeriods(zone: ZoneId): List<HealthRecord> {
    val root = json() ?: return emptyList()
    val cycles = root.optJSONObject("operationalData")?.optJSONArray("cycles") ?: return emptyList()
    val own = root.floZone() ?: zone
    return cycles.objects().flatMap { cycle ->
        // A pregnancy is a cycle row too. Nothing in the one export anybody has published is a
        // pregnancy, so this is inference from the field rather than something observed — but the
        // cost of being right is nothing and the cost of being wrong is a nine-month period.
        if (cycle.optBoolean("pregnant")) return@flatMap emptyList()
        listOfNotNull(
            floLocalDate(cycle.optString("period_start_date"), own)?.let { PeriodRecord(it, start = true) },
            floLocalDate(cycle.optString("period_end_date"), own)?.let { PeriodRecord(it, start = false) },
        )
    }
}

/**
 * The zone the export was written in, which Flo does state — just not next to the dates.
 *
 * It is in the session history, where the app recorded which zone the phone was installed in. That
 * is what turns the cycle dates from a guess into a reading: they are local midnights written as
 * UTC instants, and this is the offset they were local to. Without it the phone's own zone has to
 * stand in, which is right for somebody importing their own history and a day out for somebody who
 * has since moved far enough east or west.
 */
private fun JSONObject.floZone(): ZoneId? = optJSONObject("sessionsData")
    ?.optJSONArray("installations")
    ?.objects()
    ?.firstNotNullOfOrNull { it.optText("time_zone").takeIf(String::isNotBlank) }
    ?.let { runCatching { ZoneId.of(it) }.getOrNull() }

/**
 * One of Flo's four coexisting timestamp shapes, as the day it stands for.
 *
 * The cycle dates carry a tenth of a second and no zone marker; the audit fields beside them carry
 * six digits of microseconds. Both are UTC. The fraction is cut off rather than parsed, because it
 * is always nought on these fields and its width is not stable.
 */
private fun floLocalDate(text: String?, zone: ZoneId): LocalDate? {
    val cleaned = text?.substringBefore('.')?.trim().orEmpty()
    if (cleaned.isBlank() || cleaned == "null") return null
    return runCatching { LocalDateTime.parse(cleaned, FLO_STAMP) }
        .getOrNull()
        ?.toInstant(ZoneOffset.UTC)
        ?.atZone(zone)
        ?.toLocalDate()
}

private val FLO_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

internal const val SECONDS_IN_A_MINUTE = 60.0
internal const val KJ_IN_A_KCAL = 4.184

// ─── Strava ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Strava, whose one useful file is a CSV written in the account's own language.
 *
 * `activities.csv` translates its column *headings* — a German account exports `Aktivitätsdatum`
 * where an English one exports `Activity Date` — and it translates the activity *types* with them,
 * so `Run` is `Lauf`. It also grows over the years — from eleven columns to over a hundred — with
 * new ones inserted in the middle rather than only appended, and it repeats five heading names at
 * two different positions holding two different things: `Distance` appears once in kilometres and
 * once in metres.
 *
 * Nothing about that can be read by matching heading names, so this reader does not. What is stable
 * across every export seen, in every language, is the first block of columns — the human-facing
 * summary Strava puts before the raw one — and its order:
 *
 *     0 Activity ID · 1 Activity Date · 2 Activity Name · 3 Activity Type
 *     4 Description · 5 Elapsed Time (seconds) · 6 Distance (km) · 7 Max Heart Rate
 *
 * so those are read by position. Energy is not in that block and is only found where the headings
 * happen to be English; a German export therefore imports its sessions with their times and
 * distances and lets the app estimate the energy from the activity, which is what it does for a
 * session logged by hand anyway.
 *
 * The row's title is the name the person gave the activity, and the type only where they gave none.
 * The type is handed on beside it all the same, in whatever language it was written in, and
 * [activityCodeFor] is asked about it before the name: a run called "Lunch break" is still a run.
 * Where neither is recognised the session, its length and its distance are all recorded, and only
 * the MET behind the estimate falls back to a general one.
 */
internal fun stravaReader() = HealthExportReader(
    app = HealthExportApp.STRAVA,
    claims = ExportPart::isStravaActivities,
    read = { parts, context ->
        parts.tables().flatMap { table ->
            val calories = table.columns["calories"]
            val averageHeartRate = table.columns["averageheartrate"]
            table.cells.mapNotNull { row -> row.stravaActivity(context, calories, averageHeartRate) }
        }
    },
)

/**
 * Strava's file, recognised without reading a single heading.
 *
 * By its name, which inside an archive is Strava's own and not anybody's, and by the presence of a
 * cell pointing into the `activities/` folder beside it — the route file each row was recorded to.
 * That cell is a path Strava writes and never translates, so it identifies the file in every
 * language. A loose CSV that happens to be called `activities.csv` and holds no such path is left
 * alone rather than guessed at.
 */
private fun ExportPart.isStravaActivities(): Boolean {
    if (this !is ExportTable || name.substringAfterLast('/').lowercase(Locale.ROOT) != "activities.csv") return false
    return has("activityid", "activitydate") ||
        cells.take(STRAVA_ROWS_SNIFFED).any { row -> row.any { it.startsWith(STRAVA_ROUTE_FOLDER) } }
}

private const val STRAVA_ROUTE_FOLDER = "activities/"
private const val STRAVA_ROWS_SNIFFED = 20

private const val STRAVA_DATE = 1
private const val STRAVA_NAME = 2
private const val STRAVA_TYPE = 3
private const val STRAVA_ELAPSED = 5
private const val STRAVA_DISTANCE_KM = 6
private const val STRAVA_COLUMNS = 8

private fun List<String>.stravaActivity(
    context: HealthReadContext,
    calories: Int?,
    averageHeartRate: Int?,
): WorkoutRecord? {
    if (size < STRAVA_COLUMNS) return null
    val start = context.instants.of(this[STRAVA_DATE]) ?: return null
    val type = this[STRAVA_TYPE].trim()
    val title = this[STRAVA_NAME].trim().ifBlank { type }
    if (title.isBlank()) return null
    return WorkoutRecord(
        startMs = start,
        // "Activity Date" carries no zone and is read as local time, so the row is named by it.
        wallClock = true,
        name = title,
        type = type,
        seconds = this[STRAVA_ELAPSED].stravaNumber()?.toInt(),
        // The summary block states kilometres to two places; the raw block further along the row
        // states metres, and both are called Distance. This is the first of the two.
        distanceM = this[STRAVA_DISTANCE_KM].stravaNumber()?.times(METRES_IN_A_KILOMETRE),
        calories = calories?.let { getOrNull(it) }?.stravaNumber()?.takeIf { it > 0.0 },
        heartRateBpm = averageHeartRate?.let { getOrNull(it) }?.stravaNumber()?.toInt()?.takeIf { it > 0 },
    )
}

/**
 * A number written the way the account's language writes one.
 *
 * A German export writes `23,02` for a distance and `4.808` for a step count in the very same row —
 * comma for the decimal point, full stop for the thousands — and an English one writes them the
 * other way about. The separator that appears last is the decimal one, which is true in both
 * conventions, and everything before it that is not a digit is grouping.
 */
private fun String.stravaNumber(): Double? {
    val cleaned = trim().trim('"').takeUnless { it.isBlank() || it == "--" } ?: return null
    val decimal = maxOf(cleaned.lastIndexOf(','), cleaned.lastIndexOf('.'))
    if (decimal < 0) return cleaned.toDoubleOrNull()
    val whole = cleaned.substring(0, decimal).filter { it.isDigit() || it == '-' }
    val fraction = cleaned.substring(decimal + 1).filter(Char::isDigit)
    // A group of exactly three digits after the only separator is a thousands group and not a
    // fraction: `4,808` steps and `4.808` steps are both four thousand eight hundred and eight.
    val grouped = fraction.length == STRAVA_GROUP_DIGITS && cleaned.count { it == ',' || it == '.' } == 1
    return if (grouped) (whole + fraction).toDoubleOrNull() else "$whole.$fraction".toDoubleOrNull()
}

private const val STRAVA_GROUP_DIGITS = 3

// ─── Nike Run Club ──────────────────────────────────────────────────────────────────────────────

/**
 * Nike Run Club, which answers a data request with one TCX file per run and nothing else.
 *
 * No CSV, no daily totals, no weight, no sleep — the export is a folder of `<uuid>.tcx`, and every
 * one of them holds exactly one activity with exactly one lap. So what is read is the lap summary:
 * how long it took, how far it went, what it burned and what the heart averaged.
 *
 * The track inside is not read, and there are two good reasons rather than one. It is second-by-
 * second geometry this app has nowhere to draw. And it is wrong in a way that would poison the
 * summary if it were: Nike writes each trackpoint's `DistanceMeters` as the *increment* since the
 * last one, where the format says cumulative, and it writes the points out of order — one group per
 * metric stream, so the timestamps jump backwards. A reader that took the last trackpoint's distance
 * as the total would report a few metres for a marathon. Reading stops at `<Track>` for that reason,
 * which also settles the newer exports that state no lap distance at all: those import with their
 * time and no distance, rather than with the first GPS step mistaken for the whole run.
 */
internal fun nikeRunClubReader() = HealthExportReader(
    app = HealthExportApp.NIKE_RUN_CLUB,
    claims = { part -> part is ExportDocument && part.text.contains(TCX_ROOT) && part.text.contains(NIKE_NAMESPACE) },
    read = { parts, _ ->
        parts.filterIsInstance<ExportDocument>().mapNotNull { it.text.readTcxLap(HealthExportApp.NIKE_RUN_CLUB) }
    },
)

private const val TCX_ROOT = "TrainingCenterDatabase"
private const val NIKE_NAMESPACE = "nike.com/xmlschemas"

/**
 * The one lap of a TCX file, read down to where the samples begin.
 *
 * Written against Nike's two serialisers — an older one that puts everything on a single line and a
 * newer one that pretty-prints every scalar onto its own line surrounded by whitespace — which is
 * why the values are trimmed rather than taken as they are. The heart rate is two elements deep,
 * `<AverageHeartRateBpm><Value>153</Value></AverageHeartRateBpm>`, and `Value` on its own is also
 * the maximum's, so the average arms a flag that the next `Value` spends.
 */
// One branch per element the lap states, which is the whole of the mapping; splitting them apart
// would hide it rather than simplify it, exactly as [csvRecords] argues for its own state machine.
@Suppress("CyclomaticComplexMethod")
private fun String.readTcxLap(app: HealthExportApp): WorkoutRecord? {
    var sport: String? = null
    var startedAt: Long? = null
    var seconds: Double? = null
    var metres: Double? = null
    var calories: Double? = null
    var averageHeartRate: Int? = null
    var expectingAverage = false
    scanXml(reader(), TCX_TAGS) { tag, attributes, text ->
        when (tag) {
            "Activity" -> sport = attributes["Sport"]

            "Lap" -> startedAt = startedAt ?: tcxInstantMs(attributes["StartTime"])

            "TotalTimeSeconds" -> seconds = seconds ?: text.toDoubleOrNull()

            "DistanceMeters" -> metres = metres ?: text.toDoubleOrNull()

            "Calories" -> calories = calories ?: text.toDoubleOrNull()

            "AverageHeartRateBpm" -> expectingAverage = true

            "Value" -> {
                if (expectingAverage) averageHeartRate = text.toDoubleOrNull()?.toInt()
                expectingAverage = false
            }

            else -> Unit
        }
        // Everything above is stated before the samples; nothing after them is the lap's own.
        tag != "Track"
    }
    val start = startedAt ?: return null
    return WorkoutRecord(
        startMs = start,
        name = sport?.takeIf { it.isNotBlank() && !it.equals("Other", ignoreCase = true) }
            ?: app.label,
        seconds = seconds?.takeIf { it > 0.0 }?.toInt(),
        distanceM = metres?.takeIf { it > 0.0 },
        calories = calories?.takeIf { it > 0.0 },
        heartRateBpm = averageHeartRate?.takeIf { it > 0 },
    )
}

private val TCX_TAGS = setOf(
    "Activity",
    "Lap",
    "TotalTimeSeconds",
    "DistanceMeters",
    "Calories",
    "AverageHeartRateBpm",
    "Value",
    "Track",
)

/**
 * An ISO-8601 instant: a TCX stamp in UTC, with or without the milliseconds a newer export adds,
 * or Oura's and Withings' spelling with an offset in place of the `Z`.
 *
 * Unlike most of what these twelve services write, this one is unambiguous — it carries its own
 * zone — so it is parsed as the instant it says rather than guessed at through a zone. The offset
 * form is read on its own because `Instant.parse` takes it only on a JDK 12 java.time, and the
 * phone's may be older: left to the first form alone, every such night would be dropped without a
 * word.
 */
internal fun tcxInstantMs(text: String?): Long? = text
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?.let { stamp ->
        runCatching { Instant.parse(stamp) }
            .recoverCatching { OffsetDateTime.parse(stamp).toInstant() }
            .getOrNull()
            ?.toEpochMilli()
    }

private fun String.reader() = java.io.StringReader(this)

// ─── Garmin Connect ─────────────────────────────────────────────────────────────────────────────

/**
 * Garmin, whose account export is several hundred JSON files and whose every root is a bare array.
 *
 * Four of those files carry something this app can hold, and they are recognised by a key inside
 * them rather than by their names — the names carry date ranges, a numeric profile id, and in one
 * case the account's own email address, none of which a reader should be constructing.
 *
 * Garmin writes to Health Connect but refuses to read from it, and only publishes what a connected
 * device recorded from the day the link was made. So the export is the only road to the years before
 * that, which for a Garmin owner is usually most of their history.
 */
internal fun garminReader() = HealthExportReader(
    app = HealthExportApp.GARMIN,
    claims = { part -> part is ExportDocument && GARMIN_MARKERS.any(part.text::contains) },
    read = { parts, context ->
        parts.filterIsInstance<ExportDocument>().flatMap { document ->
            when {
                document.text.contains(GARMIN_ACTIVITIES) -> document.garminActivities()
                document.text.contains(GARMIN_SLEEP) -> document.garminSleep()
                document.text.contains(GARMIN_BODY) -> document.garminWeights()
                else -> document.garminDays(context)
            }
        }
    },
)

private const val GARMIN_ACTIVITIES = "summarizedActivitiesExport"
private const val GARMIN_SLEEP = "sleepStartTimestampGMT"
private const val GARMIN_BODY = "userSetNullForWeight"
private const val GARMIN_DAYS = "totalSteps"
private val GARMIN_MARKERS = listOf(GARMIN_ACTIVITIES, GARMIN_SLEEP, GARMIN_BODY, GARMIN_DAYS)

/**
 * The daily aggregate, which is where a Garmin owner's steps live.
 *
 * The same array carries hydration entries of an entirely different shape — `{"hydration": {…}}`
 * with no `calendarDate` at all — so a day is recognised by having one rather than by its position.
 *
 * The resting pulse is filed at midday of the day it describes. It is a figure for the whole day and
 * has no instant of its own; midday keeps it on that day in any zone, which midnight would not.
 */
private fun ExportDocument.garminDays(context: HealthReadContext): List<HealthRecord> =
    jsonArray()?.objects().orEmpty().flatMap { day ->
        val date = day.optString("calendarDate").takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
        val on = date.toLocalDateOrNull() ?: return@flatMap emptyList()
        val noon = on.atTime(NOON_HOUR, 0).atZone(context.zone).toInstant().toEpochMilli()
        val resting = day.positive("restingHeartRate") ?: day.positive("currentDayRestingHeartRate")
        listOfNotNull(
            day.positive("totalSteps")?.let { StepDayRecord(on, it.toLong()) },
            resting?.let { VitalRecord(noon, Vital.RESTING_HEART_RATE, it, wallClock = true) },
        )
    }

/**
 * The nights.
 *
 * `sleepStartTimestampGMT` is written two incompatible ways in the wild — as epoch milliseconds, and
 * as an ISO-like GMT string with a trailing tenth of a second and no zone marker — and both occur
 * across the years of one account's own export. [garminInstantMs] takes either.
 */
private fun ExportDocument.garminSleep(): List<HealthRecord> =
    jsonArray()?.objects().orEmpty().mapNotNull { night ->
        val start = garminInstantMs(night.opt("sleepStartTimestampGMT")) ?: return@mapNotNull null
        val end = garminInstantMs(night.opt("sleepEndTimestampGMT")) ?: return@mapNotNull null
        SleepRecord(start, end).takeIf { end > start }
    }

/**
 * The sessions, two arrays deep: the file's root is an array whose every element is an object
 * holding one key, and that key's value is the array of activities.
 */
private fun ExportDocument.garminActivities(): List<HealthRecord> {
    val activities = jsonArray()?.objects().orEmpty()
        .flatMap { it.optJSONArray(GARMIN_ACTIVITIES)?.objects().orEmpty() }
    val units = garminUnits(
        activities.mapNotNull { session -> session.positive("duration")?.let { it to session.positive("distance") } },
    )
    return activities.mapNotNull { activity ->
        val start = garminInstantMs(activity.opt("startTimeGmt"))
            ?: garminInstantMs(activity.opt("beginTimestamp"))
            ?: return@mapNotNull null
        val type = activity.optText("activityType").replace('_', ' ')
        WorkoutRecord(
            startMs = start,
            name = activity.optText("name").ifBlank { type }.ifBlank { return@mapNotNull null },
            type = type,
            seconds = activity.positive("duration")?.div(units.perSecond)?.toInt(),
            distanceM = activity.positive("distance")?.div(units.perMetre),
            calories = activity.positive("calories"),
            heartRateBpm = activity.positive("avgHr")?.toInt(),
        )
    }
}

/**
 * The weighings, out of a file that is a list of profile snapshots rather than of measurements.
 *
 * The outer `weight` key holds an *object*, and that object has its own `weight` member — in grams.
 * Reading the outer key as a number is the mistake this file exists to catch. The snapshots repeat
 * the same measurement across records, so the instant is what makes a weighing distinct.
 *
 * A nought means "not measured" here rather than nought: a weight typed into the phone rather than
 * read off an Index scale carries zeros for the fat, the water and the muscle.
 */
private fun ExportDocument.garminWeights(): List<HealthRecord> =
    jsonArray()?.objects().orEmpty()
        .mapNotNull { snapshot -> snapshot.optJSONObject("weight") }
        .distinctBy { it.optString("timestampGMT") }
        .mapNotNull { weight ->
            val at = garminInstantMs(weight.opt("timestampGMT")) ?: return@mapNotNull null
            val kg = weight.positive("weight")?.div(GRAMS_IN_A_KILOGRAM)?.takeIf { it in PLAUSIBLE_KG }
                ?: return@mapNotNull null
            WeightRecord(
                atMs = at,
                kg = kg,
                bodyFatPercent = weight.positive("bodyFat")?.takeIf { it in Vital.BODY_FAT.valid },
                muscleMassKg = weight.positive("muscleMass")?.div(GRAMS_IN_A_KILOGRAM)
                    ?.takeIf { it in Vital.MUSCLE_MASS.valid },
                bodyWaterMassKg = weight.positive("bodyWater")
                    ?.let { percent -> kg * percent / WHOLE_PERCENT }
                    ?.takeIf { it in Vital.BODY_WATER.valid },
            )
        }

/**
 * A Garmin instant, which is a number in some files and a string in others.
 *
 * The string shape is an ISO-like GMT stamp with a trailing tenth of a second and no zone marker at
 * all — `2019-08-04T20:28:00.0` — so the `Z` it is missing is supplied rather than guessed: the
 * field's own name says GMT.
 */
internal fun garminInstantMs(value: Any?): Long? = when (value) {
    is Number -> value.toLong().takeIf { it > 0 }

    is String -> value.trim().takeIf { it.isNotBlank() && it != "null" }?.let { text ->
        text.toLongOrNull()?.takeIf { it > 0 }
            ?: runCatching { Instant.parse(text.substringBefore('.') + "Z").toEpochMilli() }.getOrNull()
    }

    else -> null
}

/** How many of an activities file's duration units make a second, and of its distance units a metre. */
internal class GarminUnits(val perSecond: Double, val perMetre: Double)

/**
 * What one activities file counts in, from each session's duration and its distance where it has one.
 *
 * Garmin has been reported stating a duration in seconds and in milliseconds, and a distance in
 * metres and in centimetres, under field names that say neither. A file is written one way, so the
 * question is asked of the file and not of each figure. Asked of each — is this one past two days,
 * past a thousand kilometres — a two-minute session in milliseconds and a five-kilometre run in
 * centimetres would both sit under the line and come out as thirty-three hours and five hundred and
 * twenty kilometres, beside sessions of the same file that were read right.
 *
 * The middle session decides. Its duration is past two days only as milliseconds: an ordinary half
 * hour is 1 800 or 1 800 000. And once the durations are seconds, its speed is past fifty metres a
 * second only as centimetres: a walk is 1.4 or 140. The middle and not the largest, so that one
 * session nobody ended, or one recorded from an aeroplane seat, does not turn the rest of the file.
 *
 * What this cannot tell is a file of milliseconds whose sessions are mostly under three minutes;
 * that one is still read as seconds.
 */
internal fun garminUnits(sessions: List<Pair<Double, Double?>>): GarminUnits {
    val middleDuration = sessions.map { it.first }.medianOrNull() ?: 0.0
    val perSecond = if (middleDuration > LONGEST_CREDIBLE_SESSION_SECONDS) MS_IN_A_SECOND else 1.0
    val middleSpeed = sessions
        .mapNotNull { (duration, distance) -> distance?.let { it / (duration / perSecond) } }
        .medianOrNull() ?: 0.0
    return GarminUnits(perSecond, perMetre = if (middleSpeed > FASTEST_CREDIBLE_MPS) CM_IN_A_METRE else 1.0)
}

private const val LONGEST_CREDIBLE_SESSION_SECONDS = 2 * 24 * 60 * 60

/** 180 km/h: past anything on foot, on wheels or in water, and well under a walk counted in centimetres. */
private const val FASTEST_CREDIBLE_MPS = 50.0
private const val MS_IN_A_SECOND = 1_000.0
private const val CM_IN_A_METRE = 100.0
internal const val NOON_HOUR = 12

/**
 * A whole share, as this file counts one. Named apart from the editor's own `PERCENT`, which is an
 * integer and lives in the same package.
 */
internal const val WHOLE_PERCENT = 100.0
