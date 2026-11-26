package com.pwnall.health

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The four services whose export is a whole archive of a wearable's own telemetry.
 *
 * They are together because their problem is the same one, and it is not parsing: it is *choosing*.
 * A Fitbit archive holds a heart rate every few seconds and a step count every minute, and the
 * lifetime heart-rate files alone have been measured at over two gigabytes; a Withings archive
 * carries thirty intraday series in run-length-encoded arrays; Samsung ships a file per data type
 * per export with a version line above the header. Every one of them also publishes a daily summary
 * of the very same telemetry, in a file a thousand times smaller.
 *
 * So the summary is what is read wherever a service publishes one, and the per-sample dumps are
 * skipped by name before they are read into memory, decoded or parsed — see [PER_SAMPLE_DUMPS]. The
 * archive is walked as a `ZipInputStream`, so a skipped entry is still inflated and thrown away on
 * the way to the next one; what is saved is holding and parsing a gigabyte, not inflating it.
 */

// ─── Fitbit, which is now also Google Health ────────────────────────────────────────────────────

/**
 * Fitbit, whose app was renamed Google Health in May 2026 and whose export arrives through Takeout.
 *
 * The archive says the same thing twice over. `Global Export Data` holds Fitbit's own JSON, with
 * naive local timestamps in `MM/DD/YY HH:MM:SS` and numbers written as strings; `Physical
 * Activity_GoogleData` holds Google's re-encoding of the identical telemetry as CSV, with real UTC
 * timestamps. Where both exist the CSV is read, because a timestamp with a `Z` on it needs no
 * guessing about which zone somebody was standing in, and because ingesting both would count every
 * step twice.
 *
 * What is taken from the JSON side is what has no CSV twin: the resting pulse, the sessions, the
 * food and the water.
 */
internal fun fitbitReader() = HealthExportReader(
    app = HealthExportApp.FITBIT,
    claims = { it.fitbitFile() != null },
    read = { parts, context ->
        parts.flatMap { part ->
            when (part.fitbitFile()) {
                FitbitFile.STEPS -> {
                    context.fitbitSteps.add(part as ExportTable)
                    emptyList()
                }

                FitbitFile.WEIGHT -> (part as ExportTable).fitbitWeights()

                FitbitFile.BODY_FAT -> (part as ExportTable).fitbitBodyFat()

                FitbitFile.SLEEP -> (part as ExportTable).fitbitSleep()

                FitbitFile.SATURATION -> (part as ExportTable).fitbitSaturation(context)

                FitbitFile.CYCLES -> (part as ExportTable).fitbitCycles()

                FitbitFile.RESTING_PULSE -> (part as ExportDocument).fitbitRestingPulse(context)

                FitbitFile.EXERCISE -> (part as ExportDocument).fitbitExercise()

                FitbitFile.FOOD -> (part as ExportDocument).fitbitFood(context)

                FitbitFile.WATER -> (part as ExportDocument).fitbitWater(context)

                null -> emptyList()
            }
        }
    },
)

private enum class FitbitFile {
    STEPS,
    WEIGHT,
    BODY_FAT,
    SLEEP,
    SATURATION,
    CYCLES,
    RESTING_PULSE,
    EXERCISE,
    FOOD,
    WATER,
}

/**
 * Which of Fitbit's files this is.
 *
 * The Google-side CSVs name their value column after the metric rather than calling it `value`, and
 * every one of them carries the device that recorded it under a heading with a space in it. The
 * Fitbit-side JSON has no header to match, so a key that appears in that file and in no other one is
 * what identifies it.
 */
private fun ExportPart.fitbitFile(): FitbitFile? = when (this) {
    is ExportTable -> FITBIT_TABLES.firstNotNullOfOrNull { (columns, file) ->
        file.takeIf { columns.all(this.columns::containsKey) }
    }

    is ExportDocument -> FITBIT_DOCUMENTS.firstNotNullOfOrNull { (key, file) ->
        file.takeIf { text.contains(key) }
    }
}

/** The columns that identify each of Fitbit's tables, and no other service's. */
private val FITBIT_TABLES: List<Pair<List<String>, FitbitFile>> = listOf(
    listOf("timestamp", "steps", "datasource") to FitbitFile.STEPS,
    listOf("timestamp", "weightgrams") to FitbitFile.WEIGHT,
    listOf("timestamp", "bodyfatpercentage") to FitbitFile.BODY_FAT,
    listOf("sleepid", "sleepstart", "sleepend") to FitbitFile.SLEEP,
    listOf("timestamp", "averagevalue", "lowerbound") to FitbitFile.SATURATION,
    listOf("periodstartdate", "periodenddate", "cyclestartdate") to FitbitFile.CYCLES,
)

/**
 * A key that appears in one of Fitbit's JSON files and in none of the others.
 *
 * The resting pulse comes first because its `"error"` is the only one of these that could also turn
 * up beside another; the rest are each a name only their own file uses.
 */
private val FITBIT_DOCUMENTS: List<Pair<String, FitbitFile>> = listOf(
    "\"error\"" to FitbitFile.RESTING_PULSE,
    "\"activityTypeId\"" to FitbitFile.EXERCISE,
    "\"loggedFood\"" to FitbitFile.FOOD,
    "\"waterAmount\"" to FitbitFile.WATER,
)

/**
 * Daily totals across all Fitbit tables. A timestamp repeated by the same tracker contributes
 * only its largest count. Trackers can count the same walk, so the largest tracker total wins.
 */
internal class FitbitSteps(private val zone: ZoneId) {
    private val days = linkedMapOf<LocalDate, MutableMap<String, FitbitDaySamples>>()

    fun add(table: ExportTable) {
        table.rows.forEach { row ->
            val at = tcxInstantMs(row["timestamp"]) ?: return@forEach
            val steps = row.number("steps")?.toLong()?.takeIf { it > 0 } ?: return@forEach
            val day = at.localDate(zone)
            val trackers = days.getOrPut(day) { HashMap() }
            val samples = trackers.getOrPut(row["datasource"]) {
                FitbitDaySamples(day.atStartOfDay(zone).toInstant().toEpochMilli())
            }
            samples.add(at, steps)
        }
    }

    fun totals(): List<StepDayRecord> = days.map { (day, trackers) ->
        StepDayRecord(day, trackers.values.maxOf(FitbitDaySamples::total))
    }
}

/** One primitive long per sample: milliseconds into the local day, then the step count. */
private class FitbitDaySamples(private val start: Long) {
    private var samples = LongArray(16)
    private var count = 0

    fun add(at: Long, steps: Long) {
        if (count == samples.size) samples = samples.copyOf(count * 2)
        samples[count++] = ((at - start) shl Int.SIZE_BITS) or steps.coerceAtMost(Int.MAX_VALUE.toLong())
    }

    fun total(): Long {
        samples.sort(0, count)
        var total = 0L
        var seenAt = -1L
        for (index in count - 1 downTo 0) {
            val sample = samples[index]
            val at = sample ushr Int.SIZE_BITS
            if (at == seenAt) continue
            seenAt = at
            total += sample.toInt()
        }
        return total
    }
}

/** One file for the whole history, and the mass in grams. */
private fun ExportTable.fitbitWeights(): List<HealthRecord> = rows.mapNotNull { row ->
    val at = tcxInstantMs(row["timestamp"]) ?: return@mapNotNull null
    val kg = row.number("weightgrams")?.div(GRAMS_IN_A_KILOGRAM)?.takeIf { it in PLAUSIBLE_KG }
        ?: return@mapNotNull null
    WeightRecord(at, kg)
}

/** Body fat lives in a file of its own here rather than beside the weight it was measured with. */
private fun ExportTable.fitbitBodyFat(): List<HealthRecord> = rows.mapNotNull { row ->
    val at = tcxInstantMs(row["timestamp"]) ?: return@mapNotNull null
    val percent = row.number("bodyfatpercentage")?.takeIf { it in Vital.BODY_FAT.valid } ?: return@mapNotNull null
    VitalRecord(at, Vital.BODY_FAT, percent)
}

/**
 * The nights, out of the one file in the archive that states an offset.
 *
 * `2026-04-12 05:45:30+0000` — a space where the ISO shape wants a `T`, and an offset with no colon
 * in it — so the string is repaired into the instant it already is rather than parsed loosely. The
 * companion stage table is not read: a hypnogram has no column in this journal.
 */
private fun ExportTable.fitbitSleep(): List<HealthRecord> = rows
    .mapNotNull { row ->
        val start = fitbitOffsetMs(row["sleepstart"]) ?: return@mapNotNull null
        val end = fitbitOffsetMs(row["sleepend"]) ?: return@mapNotNull null
        SleepRecord(start, end).takeIf { end > start }
    }
    .withoutOverlaps()

/**
 * One night, once, out of a file that states most of them twice.
 *
 * Fitbit re-scores a night when its sleep algorithm changes and keeps both readings: a real file
 * holds 08:26:30–13:41:30 under `sleep_session_v1` and 08:38:00–13:36:00 under `sleep_session_v2`
 * for the same night, with different ids. They are not two nights, and imported as two they would
 * put nearly every night in the journal twice.
 *
 * The first of an overlapping pair wins rather than the newer algorithm, because the file states no
 * order between versions and the earlier row is the longer, outer reading — which is the one a
 * person would recognise as when they went to bed.
 */
private fun List<SleepRecord>.withoutOverlaps(): List<SleepRecord> {
    val kept = mutableListOf<SleepRecord>()
    sortedBy(SleepRecord::startMs).forEach { night ->
        if (kept.none { night.startMs < it.endMs && it.startMs < night.endMs }) kept += night
    }
    return kept
}

private fun fitbitOffsetMs(text: String): Long? = text.trim()
    .takeIf { it.isNotBlank() }
    ?.replaceFirst(" ", "T")
    ?.let { runCatching { java.time.OffsetDateTime.parse(it, FITBIT_OFFSET).toInstant().toEpochMilli() }.getOrNull() }

private val FITBIT_OFFSET: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT)

/**
 * The nightly oxygen saturation.
 *
 * The daily file and not the minute one, and the reason is a sentinel rather than size: about a
 * seventh of the minute rows read exactly 50.0, which is not a saturation of fifty percent but the
 * floor an invalid read is clamped to. The daily average has already had them taken out.
 *
 * The stamp is the day and no moment in it — `2026-04-06T00:00:00Z` on every row — so the day is
 * what is read and the figure is filed at its midday. Taken as the instant it spells, the row would
 * sit on the evening before for anybody west of Greenwich.
 */
private fun ExportTable.fitbitSaturation(context: HealthReadContext): List<HealthRecord> = rows.mapNotNull { row ->
    val day = row["timestamp"].asDay() ?: return@mapNotNull null
    val percent = row.number("averagevalue")?.takeIf { it in Vital.OXYGEN_SATURATION.valid } ?: return@mapNotNull null
    val noon = day.atTime(NOON_HOUR, 0).atZone(context.zone).toInstant().toEpochMilli()
    VitalRecord(noon, Vital.OXYGEN_SATURATION, percent, wallClock = true)
}

/** The period boundaries, out of a file that is present with a header and no rows for most accounts. */
private fun ExportTable.fitbitCycles(): List<HealthRecord> = rows.flatMap { row ->
    listOfNotNull(
        row["periodstartdate"].asDay()?.let { PeriodRecord(it, start = true) },
        row["periodenddate"].asDay()?.let { PeriodRecord(it, start = false) },
    )
}

private fun String.asDay(): LocalDate? = trim().takeIf { it.isNotBlank() }
    ?.let { it.take(ISO_DAY_LENGTH).toLocalDateOrNull() }

private const val ISO_DAY_LENGTH = 10

/**
 * The resting pulse, one figure a day, doubly nested.
 *
 * Every day of the account's life is in this file whether it was measured or not, and an unmeasured
 * day is written as `{"date": null, "value": 0.0, "error": 0.0}` rather than left out. A reader that
 * took the number at face value would import a resting heart rate of nought for every day somebody
 * did not wear the tracker.
 *
 * `dateTime` is the day with nought hours after it — `03/19/26 00:00:00`, beside a `date` of
 * `03/19/26` — and not a moment of that day. Only its date is read, which files the figure at the
 * day's midday as [fitbitStampMs] files any bare day; read whole, as the UTC instant the other
 * stamps of this archive are, it would be the evening of the 18th anywhere west of Greenwich.
 */
private fun ExportDocument.fitbitRestingPulse(context: HealthReadContext): List<HealthRecord> =
    jsonArray()?.objects().orEmpty().mapNotNull { entry ->
        val bpm = entry.optJSONObject("value")?.positive("value") ?: return@mapNotNull null
        val day = entry.optString("dateTime").trim().substringBefore(' ')
        val at = fitbitStampMs(day, context) ?: return@mapNotNull null
        VitalRecord(at, Vital.RESTING_HEART_RATE, bpm, wallClock = true)
    }

private fun ExportDocument.fitbitExercise(): List<HealthRecord> =
    jsonArray()?.objects().orEmpty().mapNotNull { session ->
        val name = session.optText("activityName").ifBlank { return@mapNotNull null }
        val start = fitbitStampMs(session.optString("startTime"), null)
            ?: tcxInstantMs(session.optString("startTime"))
            ?: return@mapNotNull null
        WorkoutRecord(
            startMs = start,
            name = name,
            // `duration` is the wall clock of the session and `activeDuration` what was moving;
            // the first is the one an energy estimate belongs on.
            seconds = session.positive("duration")?.div(MS_IN_A_SECOND)?.toInt(),
            distanceM = session.positive("distance")?.fitbitDistance(session.optString("distanceUnit")),
            calories = session.positive("calories"),
            heartRateBpm = session.positive("averageHeartRate")?.toInt(),
        )
    }

/** Fitbit states the unit beside the figure, and writes kilometres where it states nothing. */
private fun Double.fitbitDistance(unit: String): Double = when (unit.lowercase(Locale.ROOT)) {
    "meter", "metre", "m" -> this
    "mile", "mi" -> this * METRES_IN_A_MILE
    else -> this * METRES_IN_A_KILOMETRE
}

/**
 * The food diary.
 *
 * `logDate` is a bare day with no clock anywhere in the record, so nothing can be recovered about
 * when in the day something was eaten beyond which meal it was logged under. The meal's own name is
 * what the row is filed under, out of Fitbit's numbering.
 */
private fun ExportDocument.fitbitFood(context: HealthReadContext): List<HealthRecord> =
    jsonArray()?.objects().orEmpty().mapNotNull { entry ->
        val food = entry.optJSONObject("loggedFood") ?: return@mapNotNull null
        val name = food.optText("name").ifBlank { return@mapNotNull null }
        val at = context.dayAndClock(entry.optString("logDate"), "") ?: return@mapNotNull null
        val nutrition = entry.optJSONObject("nutritionalValues")
        NutritionRecord(
            atMs = at,
            wallClock = true,
            title = listOfNotNull(food.optText("brand").ifBlank { null }, name).joinToString(" "),
            meal = "",
            numberedMeal = FITBIT_MEALS[food.optInt("mealTypeId")],
            calories = nutrition?.positive("calories") ?: food.optDouble("calories", 0.0),
            // The crowd-sourced half of Fitbit's food database states an energy and nothing else,
            // so the macros are absent far more often than they are nought.
            proteinG = nutrition?.positive("protein") ?: 0.0,
            fatG = nutrition?.positive("fat") ?: 0.0,
            carbsG = nutrition?.positive("carbs") ?: 0.0,
            fiberG = nutrition?.positive("fiber"),
            sodiumMg = nutrition?.positive("sodium"),
        )
    }

/** Fitbit's own meal numbering, which is stated nowhere in the export and is the same in all of them. */
private val FITBIT_MEALS = mapOf(
    1 to NumberedMeal.BREAKFAST,
    2 to NumberedMeal.MORNING_SNACK,
    3 to NumberedMeal.LUNCH,
    4 to NumberedMeal.AFTERNOON_SNACK,
    5 to NumberedMeal.DINNER,
    6 to NumberedMeal.EVENING_SNACK,
)

private fun ExportDocument.fitbitWater(context: HealthReadContext): List<HealthRecord> =
    jsonArray()?.objects().orEmpty().mapNotNull { entry ->
        val amount = entry.positive("waterAmount") ?: return@mapNotNull null
        val stamp = entry.optString("date")
        val at = fitbitStampMs(stamp, context) ?: return@mapNotNull null
        val millilitres = when (entry.optString("measurementUnit").uppercase(Locale.ROOT)) {
            "FL_OZ" -> amount * MILLILITRES_IN_A_US_FLUID_OUNCE
            "CUP" -> amount * MILLILITRES_IN_A_CUP
            else -> amount
        }
        // A bare day was filed at the reader's own midday; a stamp with a clock is UTC.
        DrinkRecord(at, KIND_WATER, millilitres, wallClock = !stamp.contains(':'))
    }

private const val MILLILITRES_IN_A_CUP = 236.5882365

/**
 * Fitbit's own timestamp: an American date on two digits of year, and UTC.
 *
 * UTC despite carrying no `Z` and no offset, which is the opposite of what it looks like, and the
 * archive itself shows it. A weighing's `logId` is epoch milliseconds, and in a real archive
 * `logId 1666223999000` — which is `2022-10-19T23:59:59Z` — sits in a record whose own `date` and
 * `time` fields read `10/19/22` and `23:59:59`. The strings are that instant rendered in UTC.
 * Independently: bucketing the per-minute step JSON by its naive hour gives totals identical to the
 * `Z`-stamped CSV of the same days, hour for hour.
 *
 * Read as local time on a phone five hours from Greenwich, every one of these would move five hours
 * and the days either side of midnight would be wrong.
 *
 * The date-only form is a different thing and is not converted: a bare `02/05/10` on a water log is
 * a calendar day, so it is filed at midday in the reader's own zone, where being an hour out cannot
 * move it to another day.
 */
private fun fitbitStampMs(text: String?, context: HealthReadContext?): Long? {
    val cleaned = text?.trim().orEmpty()
    if (cleaned.isBlank() || cleaned == "null") return null
    val day = context?.zone ?: ZoneOffset.UTC
    return runCatching { LocalDateTime.parse(cleaned, FITBIT_STAMP).toInstant(ZoneOffset.UTC).toEpochMilli() }
        .getOrNull()
        ?: runCatching {
            LocalDate.parse(cleaned, FITBIT_DAY).atTime(NOON_HOUR, 0).atZone(day).toInstant().toEpochMilli()
        }.getOrNull()
        ?: runCatching {
            LocalDate.parse(cleaned).atTime(NOON_HOUR, 0).atZone(day).toInstant().toEpochMilli()
        }.getOrNull()
}

private val FITBIT_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss", Locale.ROOT)
private val FITBIT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT)
private const val MS_IN_A_SECOND = 1_000.0

// ─── Oura ───────────────────────────────────────────────────────────────────────────────────────

/**
 * Oura, whose whole export is one CSV of one row per day.
 *
 * Its columns come out in a different order in every export — only `date` is reliably first — so
 * everything here is by name, which is safe because the names are English in every language. Two of
 * them are worth naming out loud. `Sleep Timin Score` is missing its `g`, in every export since at
 * least 2021, and `Total Bedtime ` ends in a space; [String.headerKey] eats the space and the typo
 * is simply not a column this reads.
 *
 * What is deliberately not imported is the temperature. Oura states a *deviation* from the wearer's
 * own baseline — a signed number around a tenth of a degree — and the vitals screen holds body
 * temperatures and grades them for fever. Storing −0.07 there would be a hypothermic reading of a
 * healthy person every night.
 */
internal fun ouraReader() = HealthExportReader(
    app = HealthExportApp.OURA,
    claims = { part -> part is ExportTable && part.has("date", "sleepscore", "bedtimestart", "bedtimeend") },
    read = { parts, context ->
        parts.tables().flatMap { table ->
            table.rows.flatMap { row ->
                val night = row.ouraNight()
                val day = row["date"].toLocalDateOrNull()
                val noon = day?.atTime(NOON_HOUR, 0)?.atZone(context.zone)?.toInstant()?.toEpochMilli()
                listOfNotNull(
                    night,
                    day?.let { on -> row.number("steps")?.takeIf { it > 0 }?.let { StepDayRecord(on, it.toLong()) } },
                    // The lowest resting figure is the one Oura's own screens lead with, and the
                    // one the night is actually judged on; the average is over the whole night.
                    noon?.let {
                        (row.number("lowestrestingheartrate") ?: row.number("averagerestingheartrate"))
                            ?.let { bpm -> VitalRecord(it, Vital.RESTING_HEART_RATE, bpm, wallClock = true) }
                    },
                )
            }
        }
    },
)

/**
 * The night, out of the two ISO stamps that carry the offset the wearer was actually in.
 *
 * Both shapes appear — with milliseconds since about 2021, without them before — and both carry a
 * real offset, so this is one of the few of these twelve files that needs no guessing about zones.
 */
private fun CsvRow.ouraNight(): SleepRecord? {
    val start = tcxInstantMs(this["bedtimestart"]) ?: return null
    val end = tcxInstantMs(this["bedtimeend"]) ?: return null
    return SleepRecord(start, end).takeIf { end > start }
}

// ─── Withings ───────────────────────────────────────────────────────────────────────────────────

/**
 * Withings, whose files are small, whose headers are translated, and whose column order is not.
 *
 * A German export writes `Gewicht (kg)` where an English one writes `Weight (kg)`, so nothing here
 * matches a heading. What is fixed is the order inside each file, and it has been fixed across
 * every export seen since 2013, so the columns are read by position and the files are told apart by
 * their names — which Withings does *not* translate.
 *
 * The thirty `raw_*.csv` files are skipped. They hold the same steps, distance and heart rate as the
 * daily aggregates beside them, run-length encoded into JSON arrays, and one of them is twenty-odd
 * megabytes on its own.
 */
internal fun withingsReader() = HealthExportReader(
    app = HealthExportApp.WITHINGS,
    claims = { part -> part is ExportTable && part.withingsFile() != null },
    read = { parts, context ->
        parts.tables().flatMap { table ->
            when (table.withingsFile()) {
                WithingsFile.WEIGHT -> table.withingsWeights(context)
                WithingsFile.PRESSURE -> table.withingsPressures(context)
                WithingsFile.SLEEP -> table.withingsSleep()
                WithingsFile.STEPS -> table.withingsSteps()
                WithingsFile.OTHER -> table.withingsOther(context)
                null -> emptyList()
            }
        }
    },
)

private enum class WithingsFile { WEIGHT, PRESSURE, SLEEP, STEPS, OTHER }

/**
 * Which of Withings' files this is.
 *
 * By name, because the headings are translated and the shapes are not distinctive — `weight.csv`
 * and `bp.csv` both begin with a date column and continue with numbers. The name is checked against
 * the shape as well, so a file called `weight.csv` that is not Withings' does not get read as one.
 */
private fun ExportTable.withingsFile(): WithingsFile? {
    val leaf = name.substringAfterLast('/').lowercase(Locale.ROOT)
    return when {
        leaf == "weight.csv" && columns.size >= WITHINGS_WEIGHT_COLUMNS -> WithingsFile.WEIGHT
        leaf == "bp.csv" && columns.size >= WITHINGS_PRESSURE_COLUMNS -> WithingsFile.PRESSURE
        leaf == "sleep.csv" && columns.size >= WITHINGS_SLEEP_COLUMNS -> WithingsFile.SLEEP
        leaf == "aggregates_steps.csv" && columns.size == WITHINGS_AGGREGATE_COLUMNS -> WithingsFile.STEPS
        leaf == "other.csv" && columns.size == WITHINGS_OTHER_COLUMNS -> WithingsFile.OTHER
        else -> null
    }
}

private const val WITHINGS_WEIGHT_COLUMNS = 2
private const val WITHINGS_PRESSURE_COLUMNS = 4
private const val WITHINGS_SLEEP_COLUMNS = 2
private const val WITHINGS_AGGREGATE_COLUMNS = 2
private const val WITHINGS_OTHER_COLUMNS = 3

/**
 * `Date, Weight (kg), Fat mass (kg), Bone mass (kg), Muscle mass (kg), Hydration (kg)`.
 *
 * Every mass is in the unit the account displays, which the heading names — `(kg)`, or `(lb)` on an
 * American account — and the bracket survives translation because it is not a word
 * ([withingsMassUnit]). The fat is a *mass* here and this app holds a percentage, so it is left to
 * `other.csv`, which states the percentage directly.
 *
 * The date is `2025-03-30 12:09:03`, a clock with no zone, here and in the two files below, so the
 * records are named by the wall clock ([ExportInstants.isWallClock]): named by the instant the
 * phone's zone made of it, the same file read after a move would land every weighing a second time.
 */
private fun ExportTable.withingsWeights(context: HealthReadContext): List<HealthRecord> {
    val unit = withingsMassUnit()
    return cells.mapNotNull { row ->
        val stamp = row.getOrNull(0).orEmpty()
        val at = context.instants.of(stamp) ?: return@mapNotNull null
        val kg = row.mass(WITHINGS_WEIGHT, unit)?.takeIf { it in PLAUSIBLE_KG } ?: return@mapNotNull null
        WeightRecord(
            atMs = at,
            kg = kg,
            muscleMassKg = row.mass(WITHINGS_MUSCLE, unit)?.takeIf { it in Vital.MUSCLE_MASS.valid },
            bodyWaterMassKg = row.mass(WITHINGS_HYDRATION, unit)?.takeIf { it in Vital.BODY_WATER.valid },
            wallClock = context.instants.isWallClock(stamp),
        )
    }
}

private fun List<String>.mass(index: Int, unit: String): Double? =
    getOrNull(index)?.toDoubleOrNull()?.asKilograms(unit)

/**
 * The unit Withings wrote this file in, read out of the weight column's own heading.
 *
 * The headings are translated and the brackets are not: a German account exports `Gewicht (kg)` and
 * an American one `Weight (lb)`, and the archive's own README says kilograms in both. So the file
 * itself is asked rather than the README, and asked at the column rather than at the file, because
 * [String.headerKey] has already folded `Weight (lb)` to `weightlb` and the last letters are the
 * answer. Read as kilograms, an imperial account's whole history would land 2.2 times too heavy.
 */
private fun ExportTable.withingsMassUnit(): String = when {
    columnAt(WITHINGS_WEIGHT)?.endsWith("lb") == true -> "lb"
    columnAt(WITHINGS_WEIGHT)?.endsWith("lbs") == true -> "lb"
    columnAt(WITHINGS_WEIGHT)?.endsWith("st") == true -> "st"
    else -> "kg"
}

private const val WITHINGS_WEIGHT = 1
private const val WITHINGS_MUSCLE = 4
private const val WITHINGS_HYDRATION = 5

/**
 * `Date, Heart rate, Systolic, Diastolic`.
 *
 * A row can be a pulse with no pressure at all — that is what a weighing on a scale that also takes
 * a pulse looks like in this file — so the two are read as separate readings rather than as one
 * reading with holes in it.
 */
private fun ExportTable.withingsPressures(context: HealthReadContext): List<HealthRecord> =
    cells.flatMap { row ->
        val stamp = row.getOrNull(0).orEmpty()
        val at = context.instants.of(stamp) ?: return@flatMap emptyList()
        val wallClock = context.instants.isWallClock(stamp)
        val pulse = row.getOrNull(WITHINGS_PULSE)?.toDoubleOrNull()
        val systolic = row.getOrNull(WITHINGS_SYSTOLIC)?.toDoubleOrNull()
        val diastolic = row.getOrNull(WITHINGS_DIASTOLIC)?.toDoubleOrNull()
        listOfNotNull(
            if (systolic != null && diastolic != null) {
                VitalRecord(at, Vital.BLOOD_PRESSURE, systolic, diastolic, wallClock)
            } else {
                null
            },
            pulse?.let { VitalRecord(at, Vital.HEART_RATE, it, wallClock = wallClock) },
        )
    }

private const val WITHINGS_PULSE = 1
private const val WITHINGS_SYSTOLIC = 2
private const val WITHINGS_DIASTOLIC = 3

/** `from, to`, both ISO with the wearer's own offset. */
private fun ExportTable.withingsSleep(): List<HealthRecord> = cells.mapNotNull { row ->
    val start = tcxInstantMs(row.getOrNull(0)) ?: return@mapNotNull null
    val end = tcxInstantMs(row.getOrNull(1)) ?: return@mapNotNull null
    SleepRecord(start, end).takeIf { end > start }
}

/** `date, value`: the day's steps, already added up by Withings. */
private fun ExportTable.withingsSteps(): List<HealthRecord> = cells.mapNotNull { row ->
    val day = row.getOrNull(0)?.asDay() ?: return@mapNotNull null
    val steps = row.getOrNull(1)?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
    StepDayRecord(day, steps)
}

/**
 * `type, date, value`: everything the scale measures that has no file of its own.
 *
 * The type column mixes SCREAMING_SNAKE constants with human phrases in the same cell, and only the
 * constants survive translation, so only those are matched.
 */
private fun ExportTable.withingsOther(context: HealthReadContext): List<HealthRecord> =
    cells.mapNotNull { row ->
        val stamp = row.getOrNull(1).orEmpty()
        val at = context.instants.of(stamp) ?: return@mapNotNull null
        val value = row.getOrNull(2)?.toDoubleOrNull() ?: return@mapNotNull null
        val wallClock = context.instants.isWallClock(stamp)
        when (row.getOrNull(0)?.uppercase(Locale.ROOT)) {
            "FAT_PERCENT" -> VitalRecord(at, Vital.BODY_FAT, value, wallClock = wallClock)

            "SPO2_AUTO", "SPO2" -> VitalRecord(at, Vital.OXYGEN_SATURATION, value.asSaturation(), wallClock = wallClock)

            "TEMPERATURE", "BODY_TEMPERATURE" ->
                VitalRecord(at, Vital.BODY_TEMPERATURE, value.asBodyCelsius(), wallClock = wallClock)

            else -> null
        }
    }

/** Withings has written a saturation both as `0.97` and as `97`; only one of them is a percentage. */
private fun Double.asSaturation(): Double = if (this <= 1.0) this * WHOLE_PERCENT else this

/**
 * A temperature in whichever scale it was written. `other.csv` names no unit, and `weight.csv` shows
 * that Withings writes an account's figures in the unit the account displays. The two scales cannot
 * be mistaken for each other here: a body's temperature in Celsius is below 45 and in Fahrenheit
 * above 77, so a figure past the Celsius range is Fahrenheit — read as Celsius it would be dropped as
 * one no body has.
 */
private fun Double.asBodyCelsius(): Double =
    if (this > Vital.BODY_TEMPERATURE.valid.endInclusive) asCelsius("F") else this

// ─── Samsung Health ─────────────────────────────────────────────────────────────────────────────

/**
 * Samsung Health, whose export is one CSV per data type with a version line above the header.
 *
 * That line is what [ExportTable] already strips. What is left is ordinary, except for three habits
 * worth knowing: every data row ends in a trailing comma so a row has one more field than the header
 * has columns, which addressing by name makes harmless; a timestamp is local wall clock with the
 * offset in a column beside it spelled `UTC+0530`; and the day marker in the step files is epoch
 * milliseconds in app versions up to 6.3 and a written-out date from 7.0 onward.
 */
internal fun samsungHealthReader() = HealthExportReader(
    app = HealthExportApp.SAMSUNG_HEALTH,
    claims = { part -> part is ExportTable && part.samsungFile() != null },
    read = { parts, context ->
        parts.tables().flatMap { table ->
            when (table.samsungFile()) {
                SamsungFile.STEPS -> table.samsungSteps()
                SamsungFile.SLEEP -> table.samsungSleep(context)
                SamsungFile.WEIGHT -> table.samsungWeights(context)
                SamsungFile.HEART_RATE -> table.samsungPulses(context)
                SamsungFile.EXERCISE -> table.samsungExercise(context)
                SamsungFile.FOOD -> table.samsungFood(context)
                null -> emptyList()
            }
        }
    },
)

private enum class SamsungFile { STEPS, SLEEP, WEIGHT, HEART_RATE, EXERCISE, FOOD }

private fun ExportTable.samsungFile(): SamsungFile? = when {
    has("daytime", "count", "sourcepkgname") -> SamsungFile.STEPS
    has("comsamsunghealthsleepstarttime", "comsamsunghealthsleependtime") -> SamsungFile.SLEEP
    has("starttime", "weight", "timeoffset") && has("bodyfat") -> SamsungFile.WEIGHT
    has("comsamsunghealthheartrateheartrate", "comsamsunghealthheartratestarttime") -> SamsungFile.HEART_RATE
    has("comsamsunghealthexercisestarttime", "totalcalorie") -> SamsungFile.EXERCISE
    has("starttime", "calorie", "mealtype", "name") -> SamsungFile.FOOD
    else -> null
}

/**
 * The day's steps.
 *
 * `day_time` changed representation between Samsung Health 6 and 7 — epoch milliseconds became a
 * written-out local timestamp — and both are still in circulation because an export carries the
 * years before the upgrade as well as after it. Both are read.
 *
 * Several rows can share one day, one per device that counted it, and the day's answer is the
 * busiest of them rather than their sum.
 */
private fun ExportTable.samsungSteps(): List<HealthRecord> {
    // Samsung merges its own devices for us and marks the result -2. Where those rows exist they
    // are the answer and the per-device rows beside them are their parts; where they do not, the
    // per-device rows are all there is. Several merged rows can still share one day — a real export
    // carries three for most of them — so the day is still reduced to its highest single count.
    val merged = rows.filter { it.int("sourcetype") == SAMSUNG_MERGED_SOURCE }
    val counted = merged.ifEmpty { rows }
    val perDay = HashMap<LocalDate, Long>()
    counted.forEach { row ->
        val day = samsungDay(row["daytime"]) ?: return@forEach
        val count = row.number("count")?.toLong() ?: return@forEach
        if (count > 0) perDay[day] = maxOf(perDay[day] ?: 0L, count)
    }
    return perDay.map { (day, steps) -> StepDayRecord(day, steps) }
}

/** What Samsung marks the row it has already deduplicated across the account's own devices. */
private const val SAMSUNG_MERGED_SOURCE = -2

/**
 * The day a `day_time` cell names. As a number it is Samsung's day as UTC midnight in milliseconds
 * — `1622592000000` is 2021-06-02T00:00Z — and it is that day only when taken apart in UTC: read
 * through the phone's zone, anywhere west of Greenwich it is still the evening before. As a stamp
 * it is a local midnight, and its date is its date.
 */
private fun samsungDay(text: String): LocalDate? {
    val cleaned = text.trim()
    return cleaned.toLongOrNull()?.takeIf { it > SAMSUNG_EPOCH_FLOOR }?.localDate(ZoneOffset.UTC)
        ?: cleaned.takeIf(String::isNotBlank)?.let { written ->
            runCatching { LocalDateTime.parse(written.substringBefore('.'), SAMSUNG_STAMP).toLocalDate() }.getOrNull()
        }
}

/** Below this a "millisecond" stamp is something else entirely; the year 2001 in milliseconds. */
private const val SAMSUNG_EPOCH_FLOOR = 1_000_000_000_000L

private val SAMSUNG_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

private fun ExportTable.samsungSleep(context: HealthReadContext): List<HealthRecord> = rows.mapNotNull { row ->
    val offset = row["comsamsunghealthsleeptimeoffset"].ifBlank { row["timeoffset"] }
    val start = samsungInstant(row["comsamsunghealthsleepstarttime"], offset, context) ?: return@mapNotNull null
    val end = samsungInstant(row["comsamsunghealthsleependtime"], offset, context) ?: return@mapNotNull null
    SleepRecord(start.ms, end.ms, start.wallClock).takeIf { end.ms > start.ms }
}

/**
 * The weighings, with the whole body-composition panel a watch's electrodes produce.
 *
 * `body_fat` is the percentage and `body_fat_mass` the kilograms of it; the two sit next to each
 * other and are easy to bind the wrong way round. A weight typed in by hand fills the mass and
 * nothing else, so every panel figure is optional.
 */
private fun ExportTable.samsungWeights(context: HealthReadContext): List<HealthRecord> = rows.mapNotNull { row ->
    val at = samsungInstant(row["starttime"], row["timeoffset"], context) ?: return@mapNotNull null
    val kg = row.number("weight")?.takeIf { it in PLAUSIBLE_KG } ?: return@mapNotNull null
    WeightRecord(
        atMs = at.ms,
        kg = kg,
        bodyFatPercent = row.number("bodyfat")?.takeIf { it in Vital.BODY_FAT.valid },
        muscleMassKg = row.number("musclemass")?.takeIf { it in Vital.MUSCLE_MASS.valid },
        bodyWaterMassKg = row.number("totalbodywater")?.takeIf { it in Vital.BODY_WATER.valid },
        wallClock = at.wallClock,
    )
}

/**
 * The pulses.
 *
 * Two shapes share this file: a spot reading, where the start and the end are the same instant, and
 * a ten-minute window with a minimum and a maximum. Both state the figure in the same column. The
 * minimum and maximum are written as a literal `0.0` on a spot reading, which is what tells the two
 * apart ([isPulseWindow]).
 *
 * A spot reading is a row here as it is a row there. The windows are a watch measuring all day, a
 * hundred and forty-four of them where it never came off, and a row each would be the raw stream
 * this import promises not to transcribe. So a day's windows become the day's one figure — their
 * average, filed at the day's midday, which is what Google's daily table states and how
 * [googleHealthReader] files it. The day is the wearer's own, off the stamp's wall clock.
 */
private fun ExportTable.samsungPulses(context: HealthReadContext): List<HealthRecord> {
    val spots = mutableListOf<HealthRecord>()
    val windows = HashMap<LocalDate, MutableList<Double>>()
    rows.forEach { row ->
        val stamp = row["comsamsunghealthheartratestarttime"]
        val offset = row["comsamsunghealthheartratetimeoffset"].ifBlank { row["timeoffset"] }
        val at = samsungInstant(stamp, offset, context) ?: return@forEach
        val bpm = row.number("comsamsunghealthheartrateheartrate")?.takeIf { it in Vital.HEART_RATE.valid }
            ?: return@forEach
        val day = samsungDay(stamp).takeIf { row.isPulseWindow() }
        if (day == null) {
            spots += VitalRecord(at.ms, Vital.HEART_RATE, bpm, wallClock = at.wallClock)
        } else {
            windows.getOrPut(day) { mutableListOf() } += bpm
        }
    }
    return spots + windows.map { (day, bpms) ->
        val noon = day.atTime(NOON_HOUR, 0).atZone(context.zone).toInstant().toEpochMilli()
        VitalRecord(noon, Vital.HEART_RATE, bpms.average(), wallClock = true)
    }
}

/** A window states the lowest and the highest it saw; a spot reading writes both as `0.0`. */
private fun CsvRow.isPulseWindow(): Boolean =
    listOf("comsamsunghealthheartratemin", "comsamsunghealthheartratemax").all { (number(it) ?: 0.0) > 0.0 }

/**
 * The workouts.
 *
 * Seventy-three columns of which about ten ever carry anything. The activity is a numeric code
 * Samsung does not publish, so the row is titled with whatever the person called it and falls back
 * to the app's own name — which is honest: the session, its length and its distance are recorded,
 * and only the energy estimate falls back to a general one.
 */
private fun ExportTable.samsungExercise(context: HealthReadContext): List<HealthRecord> = rows.mapNotNull { row ->
    val offset = row["comsamsunghealthexercisetimeoffset"].ifBlank { row["timeoffset"] }
    val start = samsungInstant(row["comsamsunghealthexercisestarttime"], offset, context) ?: return@mapNotNull null
    val end = samsungInstant(row["comsamsunghealthexerciseendtime"], offset, context)
    WorkoutRecord(
        startMs = start.ms,
        wallClock = start.wallClock,
        name = row["title"].ifBlank { HealthExportApp.SAMSUNG_HEALTH.label },
        seconds = row.number("comsamsunghealthexerciseduration")?.div(MS_IN_A_SECOND)?.toInt()
            ?: end?.ms?.minus(start.ms)?.div(MS_IN_A_SECOND.toLong())?.toInt(),
        distanceM = row.number("comsamsunghealthexercisedistance")?.takeIf { it > 0.0 },
        // The session's own figure rather than `total_calorie` beside it: the two disagree on
        // about a third of real rows, with `total_calorie` always the larger, and it is the
        // exercise's own column that the app itself shows against the session.
        calories = (row.number("comsamsunghealthexercisecalorie") ?: row.number("totalcalorie"))
            ?.takeIf { it > 0.0 },
        heartRateBpm = row.number("meanheartrate")?.toInt()?.takeIf { it > 0 },
    )
}

/**
 * The food diary, one row per item eaten.
 *
 * The macros are not here — they are in a catalogue file keyed by an id, and in a per-meal rollup
 * beside it — so what an imported row carries is the food, the portion and the energy. That is
 * what the diary itself shows.
 */
private fun ExportTable.samsungFood(context: HealthReadContext): List<HealthRecord> = rows.mapNotNull { row ->
    val at = samsungInstant(row["starttime"], row["timeoffset"], context) ?: return@mapNotNull null
    val name = row["name"].ifBlank { return@mapNotNull null }
    NutritionRecord(
        atMs = at.ms,
        wallClock = at.wallClock,
        title = name,
        meal = "",
        numberedMeal = SAMSUNG_MEALS[row.int("mealtype")],
        calories = row.number("calorie") ?: 0.0,
        servingG = row.number("amount")?.takeIf { row["unit"].equals("g", ignoreCase = true) } ?: 0.0,
    )
}

/** Samsung's meal numbering, which is stated nowhere in the export. */
private val SAMSUNG_MEALS = mapOf(
    100_001 to NumberedMeal.BREAKFAST,
    100_002 to NumberedMeal.LUNCH,
    100_003 to NumberedMeal.DINNER,
    100_004 to NumberedMeal.MORNING_SNACK,
    100_005 to NumberedMeal.AFTERNOON_SNACK,
    100_006 to NumberedMeal.EVENING_SNACK,
)

/** A Samsung stamp as its instant, and whether the phone's zone had to place it ([HealthRecord]). */
private class SamsungInstant(val ms: Long, val wallClock: Boolean)

/**
 * A Samsung instant: local wall clock in one column, and the offset it was written in, in another.
 *
 * The offset is spelled `UTC+0530`, which is not an offset any parser accepts until the three
 * letters come off. Where the column is empty — older rows leave it so — the zone the reader was
 * given is what places the row, which is the same last resort every other reader here takes; and
 * such a row is a wall clock, named by it, so that the same file read in another zone names the
 * same row.
 */
private fun samsungInstant(text: String, offset: String, context: HealthReadContext): SamsungInstant? {
    val cleaned = text.trim().substringBefore('.')
    val local = cleaned.takeIf(String::isNotBlank)
        ?.let { runCatching { LocalDateTime.parse(it, SAMSUNG_STAMP) }.getOrNull() }
    val stated = runCatching { ZoneOffset.of(offset.trim().removePrefix("UTC")) }.getOrNull()
    return local?.let { at ->
        SamsungInstant(at.atZone(stated ?: context.zone).toInstant().toEpochMilli(), wallClock = stated == null)
    }
}

// ─── Google Health ──────────────────────────────────────────────────────────────────────────────

/**
 * Google's own daily activity table, out of a Google Fit Takeout.
 *
 * This is the older of the two things Google calls a health export. The newer one is Fitbit's
 * archive, which Takeout files under `Google Health` since the app was renamed in May 2026 and which
 * [fitbitReader] reads; this is what an account that used Google Fit rather than a Fitbit has, and
 * it is one CSV of one row per day.
 *
 * Every column carries its unit in its heading, so nothing about the numbers has to be assumed.
 */
internal fun googleHealthReader() = HealthExportReader(
    app = HealthExportApp.GOOGLE_HEALTH,
    claims = { part ->
        part is ExportTable && part.has("date", "stepcount") &&
            GOOGLE_DAILY_COMPANIONS.any(part.columns::containsKey)
    },
    read = { parts, context ->
        parts.tables().flatMap { table ->
            table.rows.flatMap { row ->
                val day = row["date"].asDay() ?: return@flatMap emptyList()
                val noon = day.atTime(NOON_HOUR, 0).atZone(context.zone).toInstant().toEpochMilli()
                listOfNotNull(
                    row.number("stepcount")?.takeIf { it > 0 }?.let { StepDayRecord(day, it.toLong()) },
                    row.number("averageheartratebpm")
                        ?.takeIf { it in Vital.HEART_RATE.valid }
                        ?.let { VitalRecord(noon, Vital.HEART_RATE, it, wallClock = true) },
                )
            }
        }
    },
)

/**
 * One of these must sit beside the step count for the table to be Google's daily one.
 *
 * `Date` and `Step count` on their own are two words half the CSVs in the world could carry; each of
 * these is a heading with its unit written into it, which is Google's own habit in this file.
 */
private val GOOGLE_DAILY_COMPANIONS =
    listOf("calorieskcal", "distancem", "averageheartratebpm", "moveminutescount")
