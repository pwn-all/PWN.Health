package com.pwnall.health

import androidx.room.withTransaction
import org.json.JSONObject
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

/*
 * Bringing a history in from the twelve services people actually keep one in.
 *
 * None of this is a network sync and none of it can be: the app holds no INTERNET permission, which
 * is a promise its privacy policy makes and a test enforces. What every one of these services does
 * offer is an export — the file the GDPR obliges them to hand over, or the one their own settings
 * screen writes — and that file is the whole of the road in.
 *
 * Half of the twelve are already reachable another way. Fitbit, Garmin, Strava, Samsung Health,
 * Withings, Oura and Google's own health data all publish into Health Connect on Android, and the
 * wizard next door already reads that. This exists for what the wizard cannot reach:
 *
 *   - Apple Health, which has no Android app at all. The file is the only road.
 *   - Cronometer, Nike Run Club and Flo, none of which write into Health Connect.
 *   - History older than the thirty days Health Connect serves without the extra grant, or older
 *     than the other app chose to publish. An export goes back to the day the account opened.
 *   - A phone with no Health Connect provider at all — a de-Googled ROM, which this app supports.
 *
 * The shape is the one [readWorkoutExport] already established, widened from a table of sets to a
 * table of facts: a format is recognised by what is inside the file and never by its name, a row
 * becomes a [HealthRecord], and a record becomes a journal row filed under a marker so that opening
 * the same export twice adds nothing the second time.
 */

/** The services whose exports this reads, named as each names itself. */
enum class HealthExportApp(val label: String) {
    FITBIT("Fitbit"),
    GARMIN("Garmin Connect"),
    STRAVA("Strava"),
    MYFITNESSPAL("MyFitnessPal"),
    APPLE_HEALTH("Apple Health"),
    NIKE_RUN_CLUB("Nike Run Club"),
    CRONOMETER("Cronometer"),
    FLO("Flo"),
    OURA("Oura"),
    WITHINGS("Withings"),
    SAMSUNG_HEALTH("Samsung Health"),
    GOOGLE_HEALTH("Google Health"),
}

/**
 * One fact out of somebody else's export, in the terms this journal holds.
 *
 * Deliberately not one type per service. Twelve services write twelve file formats and between them
 * describe about ten different things, and it is the ten that the journal cares about — a weighing
 * off the Withings scales and a weighing off the Fitbit account are the same row once they are here.
 * Everything a service records that this app has no column for — a route, a running power curve, a
 * sleep hypnogram, a training-readiness score — has no member here, on the same reasoning the
 * workout import gives: a column invented for an import nobody may run is a column nobody fills.
 *
 * A record whose moment the file stated as a date or a clock with no zone carries `wallClock = true`:
 * the instant was made in the phone's zone, and its marker names the wall clock instead, so the same
 * file read in another zone names the same row (see [importStamp]). A record whose moment the file
 * stated with its offset leaves it false.
 */
internal sealed interface HealthRecord

/** A day's step count, as the only figure most of these services export about walking. */
internal data class StepDayRecord(val day: LocalDate, val steps: Long) : HealthRecord

/** One night, start to end. Stages are dropped; the journal holds a night and not a hypnogram. */
internal data class SleepRecord(val startMs: Long, val endMs: Long, val wallClock: Boolean = false) : HealthRecord

/** A weighing, with whatever else the scales worked out at the same moment. */
internal data class WeightRecord(
    val atMs: Long,
    val kg: Double,
    val bodyFatPercent: Double? = null,
    val muscleMassKg: Double? = null,
    val bodyWaterMassKg: Double? = null,
    val wallClock: Boolean = false,
) : HealthRecord

/**
 * One reading of one instrument vital, addressed by the [Vital] it is a reading of.
 *
 * One member for pulse, pressure, saturation and temperature rather than four, because [Vital]
 * already holds what separates them: the payload key the app stores the number under, the range
 * outside which a reading is an artefact, and the title a row wears. A blood pressure is the only
 * one that is two numbers, and [secondary] is that second number.
 */
internal data class VitalRecord(
    val atMs: Long,
    val vital: Vital,
    val value: Double,
    val secondary: Double? = null,
    val wallClock: Boolean = false,
) : HealthRecord

/**
 * Something eaten, with as much of the label as the exporting diary happened to keep.
 *
 * [meal] is the meal in the diary's own words, which are the person's; [numberedMeal] is the meal a
 * diary states by a number instead, which has no words in the file at all.
 */
internal data class NutritionRecord(
    val atMs: Long,
    val title: String,
    val meal: String,
    val calories: Double,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    val carbsG: Double = 0.0,
    val servingG: Double = 0.0,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val saturatedFatG: Double? = null,
    val sodiumMg: Double? = null,
    val cholesterolMg: Double? = null,
    val caffeineMg: Double? = null,
    val wallClock: Boolean = false,
    val numberedMeal: NumberedMeal? = null,
) : HealthRecord

/**
 * A meal a diary states by a number of its own rather than in words: Fitbit's `mealTypeId` and
 * Samsung's `meal_type`, each a table of six that neither export spells out.
 *
 * The row wears the reader's word for the meal ([HealthImportTitles.meal]), and its marker the
 * constant's name, which no language changes, so the same file names the same row on every phone.
 */
enum class NumberedMeal(val titleRes: Int) {
    BREAKFAST(R.string.import_meal_breakfast),
    MORNING_SNACK(R.string.import_meal_morning_snack),
    LUNCH(R.string.import_meal_lunch),
    AFTERNOON_SNACK(R.string.import_meal_afternoon_snack),
    DINNER(R.string.import_meal_dinner),
    EVENING_SNACK(R.string.import_meal_evening_snack),
}

/**
 * One recorded session, as an activity rather than as a set list.
 *
 * The workout import next door reads files that are lists of sets; these twelve write files that
 * are lists of sessions, which is a different thing and lands in the same journal row from the other
 * side — the duration and the distance are stated outright and there is nothing to aggregate.
 *
 * [name] is the row's title, and where a service lets the person write one it is whatever they
 * wrote. [type] is the service's own word for the kind of session, where the file states one beside
 * the title; it is asked first for the catalogue entry and is no part of the title or the marker.
 */
internal data class WorkoutRecord(
    val startMs: Long,
    val name: String,
    val seconds: Int?,
    val distanceM: Double? = null,
    val calories: Double? = null,
    val heartRateBpm: Int? = null,
    val wallClock: Boolean = false,
    val type: String = "",
) : HealthRecord

/** A period's first or last day. Flo and Apple Health are the two exports that carry one. */
internal data class PeriodRecord(val day: LocalDate, val start: Boolean) : HealthRecord

/** Water or caffeine: one journal kind each, and the millilitres or milligrams in [servingG]. */
internal data class DrinkRecord(
    val atMs: Long,
    val kind: String,
    val amount: Double,
    val wallClock: Boolean = false,
) : HealthRecord

/** What one export turned into: the service that wrote it, and every fact read out of it. */
internal data class HealthImportFile(val app: HealthExportApp, val records: List<HealthRecord>)

/**
 * The words an imported row wears, handed in rather than looked up.
 *
 * Every one of these is a string resource the app already ships and already puts on a row of the
 * same kind logged by hand, so an imported weighing reads exactly like a weighed one — all but the
 * names of [NumberedMeal], which nothing logged here is filed under. They arrive as a parameter
 * because everything in this file is meant to be readable by a test on a plain JVM, where there is
 * no `Resources` to ask.
 */
data class HealthImportTitles(
    val weight: String,
    val sleep: String,
    val periodStart: String,
    val periodEnd: String,
    val water: String,
    val caffeine: String,
    val vital: (Vital) -> String,
    val meal: (NumberedMeal) -> String,
)

// ─── from a record to a journal row ─────────────────────────────────────────────────────────────

/** The source name an imported row carries, and what the sources screen prints beside it. */
internal fun HealthExportApp.sourceId(): String = "$FILE_IMPORT_MARKER_PREFIX$label"

/**
 * What one export becomes: journal rows, and the daily step totals that are not journal rows.
 *
 * Steps are separated out because this app has never held a day's steps in the journal — they live
 * in their own per-source table, merged by taking the day's highest single count and never a sum,
 * which is the only safe arithmetic when a phone in a pocket and a watch on a wrist have counted
 * the same walk. An imported day joins that merge as one more source rather than being added to it.
 */
internal data class HealthImportRows(
    val entries: List<JournalEntry>,
    val stepDays: List<DirectBleDaySteps>,
    /** Rows left out because their moment is one no history holds ([importableMoments]). */
    val undated: Int = 0,
)

/**
 * The rows, each under its marker — and the second and later of one marker's rows in the file under
 * that marker with their place among them (`#2`, `#3`), wherever the file states no instant
 * ([statesNoInstant]).
 *
 * A diary that keeps no clock files a whole day at midday, so two coffees logged under one
 * breakfast and every glass of a day's water share a marker, and without their place the import
 * would keep the first of them and count the rest as already here. The place is the same in a later
 * export of the same day, so the rows that landed are still recognised and only the ones that were
 * lost are new.
 *
 * A row is left out where its moment is one no history holds ([importableMoments]), and counted so
 * that the import can say so; a day of steps on such a date, or of more steps than a day has, is
 * left out too. The file is whatever somebody picked, and a damaged cell costs its own row.
 */
internal fun HealthImportFile.rows(
    zone: ZoneId,
    titles: HealthImportTitles,
    nowMs: Long = System.currentTimeMillis(),
): HealthImportRows {
    val moments = importableMoments(nowMs)
    val days = moments.first.localDate(zone)..moments.last.localDate(zone)
    val seen = HashMap<String, Int>()
    var undated = 0
    val entries = records.mapNotNull { record ->
        val entry = record.journalEntry(app, zone, titles) ?: return@mapNotNull null
        if (entry.occurredAt !in moments) {
            undated += 1
            return@mapNotNull null
        }
        val marker = entry.healthConnectRecordKey
        if (marker == null || !record.statesNoInstant()) return@mapNotNull entry
        val place = (seen[marker] ?: 0) + 1
        seen[marker] = place
        if (place == 1) entry else entry.copy(healthConnectRecordKey = "$marker#$place")
    }
    val steps = records.filterIsInstance<StepDayRecord>()
        .filter { it.steps in 1..MOST_STEPS_IN_A_DAY && it.day in days }
        // One row per day even where the file states a day twice, which several of these do: a
        // Samsung export overlaps its own monthly files, and a Fitbit archive repeats the day it
        // was taken on. The larger count wins, for the same reason the merge above takes a maximum.
        .groupBy(StepDayRecord::day)
        .map { (day, sameDay) ->
            DirectBleDaySteps(
                sourceId = app.sourceId(),
                day = day.toString(),
                steps = sameDay.maxOf(StepDayRecord::steps),
                sourceLabel = app.label,
            )
        }
    return HealthImportRows(entries, steps, undated)
}

/**
 * The moments an imported row may stand at: from the first day of 2000 — before any of these
 * services kept a history, and after the 1970 that a nought, or a count of seconds taken for
 * milliseconds, turns into — to a day past now, which no history reaches. Outside it a stamp is a
 * damaged cell, and a row filed there would stand on a day nobody lived, `+292278994-08-17` included.
 */
internal fun importableMoments(nowMs: Long): LongRange = EARLIEST_IMPORTED_MS..nowMs + MS_IN_A_DAY

/** 2000-01-01T00:00:00Z. */
private const val EARLIEST_IMPORTED_MS = 946_684_800_000L
private const val MS_IN_A_DAY = 24L * 60 * 60 * 1_000

/**
 * Past this a day's steps are a damaged cell. `1e999` reads as a number, and as a count it is the
 * largest a Long holds, which overflows the first total it is added to; a million is past anything
 * walked in a day and far short of that.
 */
private const val MOST_STEPS_IN_A_DAY = 1_000_000L

/**
 * How much of a title, a meal or an exercise name out of a file is kept. A quote that never closes
 * turns the rest of a CSV into one cell, megabytes long, and that cell would be the row's title and
 * a part of its marker; no name anybody gave a meal or a workout comes near this.
 */
internal const val IMPORT_TEXT_LIMIT = 200

/** What an amount out of a file can be, whatever it counts: kilocalories, grams, milligrams, reps. */
internal val CREDIBLE_AMOUNT = 0.0..100_000.0

/**
 * A figure out of a file as an amount a row can hold, or null where it is not one.
 *
 * `NaN`, `Infinity` and `1e999` all read as numbers. SQLite stores the first as NULL, which the
 * calories column refuses, so the whole import would roll back over one cell; the others would
 * stand in a day's total. A cell like that is read as the blank cell it should have been.
 */
private fun Double.amount(): Double? = takeIf { it in CREDIBLE_AMOUNT }

/**
 * Whether two of this record's rows under one marker are two facts rather than one stated twice.
 *
 * Only a meal, a session or a drink the file gave no instant for (`wallClock`): a day, and at most a
 * clock. Where the file states the instant, two rows naming it are two sources recording one moment,
 * and the import still folds those into one.
 */
private fun HealthRecord.statesNoInstant(): Boolean = when (this) {
    is NutritionRecord -> wallClock
    is WorkoutRecord -> wallClock
    is DrinkRecord -> wallClock
    else -> false
}

/**
 * The name a row is filed under, and the same name on every phone that reads the same export.
 *
 * Deliberately built out of what the record *is* rather than out of where it sat in the file: an
 * export taken a month later holds the same night and the same weighing with another hundred rows
 * above them, and a marker made from a row number would import the whole history again. The instant
 * and the kind are what identify a fact, and they are what is here.
 *
 * It shares [FILE_IMPORT_MARKER_PREFIX] with the workout import on purpose. That prefix is what the
 * "already here" query looks for and what a tombstone is filed under, so sharing it means a row
 * imported here and then deleted by hand stays deleted through every later import, exactly as one
 * imported from a Hevy file does.
 */
internal fun healthImportMarker(app: HealthExportApp, what: String, at: String): String =
    "$FILE_IMPORT_MARKER_PREFIX${app.label}:$what:$at"

/**
 * The stamp a marker ends in: the wall clock the file stated, for a record read from a date or a
 * zone-less clock ([HealthRecord.wallClock]), so that the same export read in another zone names the
 * same row; the instant otherwise, which is the same everywhere already.
 */
private fun importStamp(atMs: Long, wallClock: Boolean, zone: ZoneId): String =
    if (wallClock) wallClockStamp(atMs, zone) else atMs.toString()

/**
 * A health-export marker filed under the instant, under the name the import gives it — the stamp is
 * the last part, after the `what` — or null for one already so named, or not an import's. The
 * counterpart of [withWallClockStamp], whose stamp sits before the exercise.
 */
internal fun String.withWallClockStampLast(zone: ZoneId): String? {
    val stamp = substringAfterLast(':')
    val filedUnderAnInstant = startsWith(FILE_IMPORT_MARKER_PREFIX) && stamp.isNotEmpty() && stamp.all(Char::isDigit)
    if (!filedUnderAnInstant) return null
    return substringBeforeLast(':') + ":" + wallClockStamp(stamp.toLong(), zone)
}

/**
 * A Fitbit day's figure filed under the UTC midnight its file stamps the day with, under the name
 * the import gives it — the midday of that day — or null for any other marker.
 *
 * The resting pulse and the nightly saturation are one figure a day, and the stamp beside each is
 * the day at nought hours. Read as an instant it is the evening before anywhere west of Greenwich,
 * so both are filed at the day's own midday; a row filed under the instant is still the same
 * history, and must not land a second time beside itself.
 */
internal fun String.withFitbitDayStamp(): String? {
    val word = FITBIT_DAY_FIGURES.firstOrNull { startsWith(it) } ?: return null
    val midnight = removePrefix(word).toLongOrNull()?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC) }
    return midnight?.takeIf { it.toLocalTime() == LocalTime.MIDNIGHT }
        ?.let { word + wallClockStamp(it.withHour(NOON_HOUR).toInstant().toEpochMilli(), ZoneOffset.UTC) }
}

/** What the markers of Fitbit's two daily figures begin with, up to the stamp. */
private val FITBIT_DAY_FIGURES = listOf(Vital.RESTING_HEART_RATE, Vital.OXYGEN_SATURATION)
    .map { healthImportMarker(HealthExportApp.FITBIT, it.markerWord(), "") }

private fun HealthRecord.journalEntry(
    app: HealthExportApp,
    zone: ZoneId,
    titles: HealthImportTitles,
): JournalEntry? = when (this) {
    // Steps are not journal rows; [HealthImportFile.rows] takes them to their own table.
    is StepDayRecord -> null

    is SleepRecord -> sleepEntry(app, zone, titles)

    is WeightRecord -> weightEntry(app, zone, titles)

    is VitalRecord -> vitalEntry(app, zone, titles)

    is NutritionRecord -> nutritionEntry(app, zone, titles)

    is WorkoutRecord -> workoutEntry(app, zone)

    is PeriodRecord -> periodEntry(app, zone, titles)

    is DrinkRecord -> drinkEntry(app, zone, titles)
}

/** The day a moment falls on, in the zone the reader was given. */
private fun Long.dayIn(zone: ZoneId): String =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate().toString()

/**
 * A night, filed at the moment it ended.
 *
 * On the day of waking, which is where the app's own sleep rows and the Health Connect import file
 * a night, so an imported night sorts into the day list where a logged one would — and so the
 * import's "this night is already here" question, asked of the waking day, finds it rather than
 * writing the night a second time. The marker names the start alone: an export taken twice can
 * round the end differently — Fitbit republishes a night when its stages are re-scored — and a
 * marker carrying both would import the same night a second time.
 *
 * A night that is not shorter than a day is not one: its start is a damaged cell, and the sleep
 * summary would pass the row over for the same reason.
 */
private fun SleepRecord.sleepEntry(app: HealthExportApp, zone: ZoneId, titles: HealthImportTitles) =
    JournalEntry(
        day = endMs.dayIn(zone),
        occurredAt = endMs,
        kind = KIND_MEASUREMENT,
        title = titles.sleep,
        calories = 0.0,
        sleepStartAt = startMs,
        sleepEndAt = endMs,
        measurementSource = app.label,
        healthConnectRecordKey = healthImportMarker(app, "sleep", importStamp(startMs, wallClock, zone)),
    ).takeIf { endMs - startMs in 1 until MS_IN_A_DAY }

/**
 * A weighing. One outside [PLAUSIBLE_KG] is not stored: most readers refuse it already, and the
 * ones that state a unit beside the figure — Apple's — do not.
 */
private fun WeightRecord.weightEntry(app: HealthExportApp, zone: ZoneId, titles: HealthImportTitles) =
    JournalEntry(
        day = atMs.dayIn(zone),
        occurredAt = atMs,
        kind = KIND_MEASUREMENT,
        title = titles.weight,
        calories = 0.0,
        measurementWeightKg = kg,
        bodyFatPercent = bodyFatPercent?.takeIf { it in Vital.BODY_FAT.valid },
        measurementSource = app.label,
        // The extra figures a smart scale works out beside the weight ride in the payload under the
        // keys the vitals screen already reads, so an imported Withings weighing draws its muscle
        // and water on the same cards a Bluetooth scale's does.
        measurementPayloadJson = compositionPayload(),
        healthConnectRecordKey = healthImportMarker(app, "weight", importStamp(atMs, wallClock, zone)),
    ).takeIf { kg in PLAUSIBLE_KG }

private fun WeightRecord.compositionPayload(): String? {
    val extras = listOfNotNull(
        muscleMassKg?.let { Vital.MUSCLE_MASS.key to it },
        bodyWaterMassKg?.let { Vital.BODY_WATER.key to it },
    )
    if (extras.isEmpty()) return null
    return JSONObject().put("schema", 1).apply { extras.forEach { (key, value) -> put(key, value) } }.toString()
}

/**
 * One instrument reading, under the payload key its own screen reads it back by.
 *
 * A reading outside [Vital.valid] is dropped rather than stored. These files carry sentinels — a
 * Withings row with a blood pressure of 0/0 where the cuff errored, an Oura temperature deviation
 * mistaken for a temperature — and a reading the vitals screen would then have to grade is worse
 * than one that never arrived: it moves an average and colours a card.
 */
private fun VitalRecord.vitalEntry(
    app: HealthExportApp,
    zone: ZoneId,
    titles: HealthImportTitles,
): JournalEntry? {
    if (value !in vital.valid) return null
    val second = secondary?.takeIf { vital.secondaryValid?.contains(it) == true }
    // Half a blood pressure is not a blood pressure — the vitals screen says as much — so a paired
    // vital arriving with one half readable is not stored as a reading of the half that survived.
    if (vital.paired && second == null) return null
    val payload = JSONObject().put("schema", 1).put(vital.key, value)
    vital.secondaryKey?.let { key -> second?.let { payload.put(key, it) } }
    // A resting rate has no column for the day list to print, so the figure rides in the title, as
    // it does on the row Health Connect brings one in on.
    val figure = if (vital == Vital.RESTING_HEART_RATE) " ${value.roundToInt()}" else ""
    return JournalEntry(
        day = atMs.dayIn(zone),
        occurredAt = atMs,
        kind = KIND_MEASUREMENT,
        title = titles.vital(vital) + figure,
        calories = 0.0,
        // The pulse has a column of its own as well as a payload key, and the column is what the
        // day list prints; both are filled so the reading reads the same from either side.
        measurementHeartRateBpm = value.toInt().takeIf { vital == Vital.HEART_RATE },
        measurementSource = app.label,
        measurementPayloadJson = payload.toString(),
        healthConnectRecordKey = healthImportMarker(app, vital.markerWord(), importStamp(atMs, wallClock, zone)),
    )
}

/**
 * The word a vital's marker names it by: its own, except for the resting rate.
 *
 * Rows already imported from the four files that state a resting rate are filed under `heart_rate`,
 * the word of an ordinary pulse. Under any other word the same export would land every one of those
 * days a second time. No reader writes both vitals for one service, so the two cannot meet under
 * the one word.
 */
private fun Vital.markerWord(): String =
    (if (this == Vital.RESTING_HEART_RATE) Vital.HEART_RATE else this).name.lowercase()

/**
 * Something eaten. Energy is taken from the file rather than recomputed from the macros: a diary
 * that states 480 kcal for a meal whose macros multiply out to 505 is stating what its own database
 * said, and the person read the first number all year.
 */
private fun NutritionRecord.nutritionEntry(
    app: HealthExportApp,
    zone: ZoneId,
    titles: HealthImportTitles,
): JournalEntry {
    // Cut before they are the title and the marker ([IMPORT_TEXT_LIMIT]).
    val food = title.take(IMPORT_TEXT_LIMIT)
    val course = (numberedMeal?.name?.lowercase() ?: meal).take(IMPORT_TEXT_LIMIT)
    return JournalEntry(
        day = atMs.dayIn(zone),
        occurredAt = atMs,
        kind = KIND_EAT,
        title = food,
        // A diary that logs a whole meal at once has nothing to name but the meal, so the row is
        // titled with it; repeating the same word underneath would be a subtitle that adds nothing.
        subtitle = numberedMeal?.let(titles.meal) ?: course.takeIf { it != food }.orEmpty(),
        calories = calories.amount() ?: 0.0,
        proteinG = proteinG.amount() ?: 0.0,
        fatG = fatG.amount() ?: 0.0,
        carbsG = carbsG.amount() ?: 0.0,
        servingG = servingG.amount() ?: 0.0,
        fiberG = fiberG?.amount(),
        sugarG = sugarG?.amount(),
        saturatedFatG = saturatedFatG?.amount(),
        sodiumMg = sodiumMg?.amount(),
        cholesterolMg = cholesterolMg?.amount(),
        caffeineMg = caffeineMg?.amount(),
        measurementSource = app.label,
        // The meal and the food are both in the marker: a diary holds the same yoghurt at breakfast
        // and at supper on one day, and both of them at the same stated minute where the diary files
        // a whole meal at once. Filed on the instant alone, the second would be read as the first
        // arriving twice.
        healthConnectRecordKey = healthImportMarker(app, "eat:$course:$food", importStamp(atMs, wallClock, zone)),
    )
}

/**
 * One session. The energy the file states is kept where it is stated, and the row is left to the
 * app's own recalculation where it is not — an activity's calories depend on what the person weighed
 * at the time, which is a walk along the measurement timeline the import does once at the end. The
 * recalculation estimates every session that carries a MET, so a stated figure carries none: it is
 * a measurement the source made, not an estimate this profile's weight may rewrite.
 *
 * A figure that is not one a session can have — an energy that is no amount, a length past a week,
 * a pulse no heart keeps — is read as not stated, as a blank cell would be.
 */
private fun WorkoutRecord.workoutEntry(app: HealthExportApp, zone: ZoneId): JournalEntry {
    // The service's own word first: a title is whatever the person typed — "Fat man run", "Lunch
    // break" — and only a type no rule recognises, or none stated, leaves the question to the title.
    val code = activityCodeFor(type).takeIf { it != FALLBACK_ACTIVITY_CODE } ?: activityCodeFor(name)
    val spec = ACTIVITY_CATALOG.firstOrNull { it.code == code }
        ?: ACTIVITY_CATALOG.first { it.code == FALLBACK_ACTIVITY_CODE }
    val title = name.take(IMPORT_TEXT_LIMIT)
    val energy = calories?.amount()
    return JournalEntry(
        day = startMs.dayIn(zone),
        occurredAt = startMs,
        kind = KIND_ACTIVITY,
        title = title,
        subtitle = distanceM?.takeIf { it in MEANINGFUL_DISTANCE_M..FARTHEST_SESSION_M }
            ?.let { "${(it / METRES_IN_A_KILOMETRE).pretty()} km" }
            .orEmpty(),
        calories = energy ?: 0.0,
        activityCode = spec.code,
        met = spec.met.takeIf { energy == null },
        durationMinutes = seconds?.takeIf { it in 1..LONGEST_SESSION_SECONDS }?.let { it / SECONDS_IN_A_MINUTE },
        measurementHeartRateBpm = heartRateBpm?.takeIf { it.toDouble() in Vital.HEART_RATE.valid },
        measurementSource = app.label,
        healthConnectRecordKey = healthImportMarker(app, "activity:$title", importStamp(startMs, wallClock, zone)),
    )
}

/**
 * A period boundary, as the two kinds the cycle screen already pairs.
 *
 * Filed at noon rather than at midnight, for the reason [ExportInstants] gives about a dateless day:
 * midnight is the one hour where being an hour out moves a day, and a period start on the wrong day
 * shifts every cycle length computed from it.
 */
private fun PeriodRecord.periodEntry(app: HealthExportApp, zone: ZoneId, titles: HealthImportTitles): JournalEntry {
    val at = day.atTime(NOON_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    return JournalEntry(
        day = day.toString(),
        occurredAt = at,
        kind = if (start) KIND_CYCLE else KIND_CYCLE_END,
        title = if (start) titles.periodStart else titles.periodEnd,
        calories = 0.0,
        measurementSource = app.label,
        healthConnectRecordKey =
        healthImportMarker(app, if (start) "period" else "periodend", day.toString()),
    )
}

/** A drink, which is nothing but its amount: where that is no amount, there is no row. */
private fun DrinkRecord.drinkEntry(app: HealthExportApp, zone: ZoneId, titles: HealthImportTitles) =
    JournalEntry(
        day = atMs.dayIn(zone),
        occurredAt = atMs,
        kind = kind,
        title = if (kind == KIND_WATER) titles.water else titles.caffeine,
        calories = 0.0,
        servingG = amount,
        measurementSource = app.label,
        healthConnectRecordKey = healthImportMarker(app, kind.lowercase(), importStamp(atMs, wallClock, zone)),
    ).takeIf { amount in CREDIBLE_AMOUNT }

/** Below this a distance is a treadmill's rounding rather than a distance covered. */
private const val MEANINGFUL_DISTANCE_M = 100.0

/** And past this — ten thousand kilometres — it is no session's distance, whatever the cell says. */
internal const val FARTHEST_SESSION_M = 10_000_000.0

/** A week, which no session anybody records reaches; `Infinity` as a count of seconds is far past it. */
internal const val LONGEST_SESSION_SECONDS = 7 * 24 * 60 * 60

// ─── the import itself ──────────────────────────────────────────────────────────────────────────

/**
 * What one run did, in the facts the card reports.
 *
 * [app] is null when nothing claimed the file, which is the one outcome that is not a count: a
 * person who picked the wrong archive and is told "0 added" reads it as "my history is empty".
 */
data class HealthImportOutcome(
    /** The service that wrote the file, as it names itself; null when nothing claimed it. */
    val label: String?,
    val added: Int,
    val alreadyHeld: Int,
    val stepDays: Int,
    /** Files in the archive never read: too large to hold whole, or not the JSON they were taken for. */
    val skipped: Int = 0,
    /**
     * Whether the walk gave up on the archive before its end ([walkArchive]), or the file itself
     * stopped before its end ([AppleHealthXml.whole]). What was read up to there reads exactly like
     * a whole history, so this is the one thing that says it is not.
     */
    val cutShort: Boolean = false,
    /**
     * Rows left out because no shape read their date ([ExportInstants.unread]), or because the date
     * read is one no history holds ([importableMoments]). A file written in a language or a layout
     * this build has not met loses every row the first way, and without the count it would be
     * reported as a history with nothing in it.
     */
    val undated: Int = 0,
) {
    val unreadable: Boolean get() = label == null

    /** Whether the run put anything at all into the journal or the step table. */
    val empty: Boolean get() = added == 0 && stepDays == 0

    /**
     * Whether the run moved the step table and nothing else: no row was written, so nothing that
     * follows a row's write runs, and the widgets that show steps have to be asked for by name.
     */
    internal fun redrawsStepsAlone(): Boolean = added == 0 && stepDays > 0
}

/**
 * Reads one picked file into the journal, whoever wrote it.
 *
 * The one door. A person who has just found the file their old app exported should not first have
 * to work out which of two buttons it belongs behind, and they could not answer reliably in any
 * case — half of these services call their export `activities.csv`. So the workout readers are
 * tried first, on the text of a file that is not an archive, and everything else falls to the twelve
 * readers next door. The two cannot claim the same file: one wants a table of sets and the other a
 * table of days.
 *
 * Idempotent, because it is a button somebody will press twice and because these exports overlap by
 * design — a person who exports every month hands over eleven months they have already imported.
 * Every row is filed under a marker naming the fact, and a marker already in the journal, or in the
 * tombstones because the row was imported once and then deliberately deleted, is skipped rather than
 * written again. Nothing is updated in place: a row already here keeps every correction made to it.
 */
suspend fun StepsDb.importExportedFile(
    open: () -> InputStream,
    fileName: String,
    zone: ZoneId,
    imperial: Boolean,
    titles: HealthImportTitles,
): HealthImportOutcome {
    workoutExportText(open)?.let { text ->
        val outcome = importWorkoutExport(text, zone, imperial, titles.weight)
        if (!outcome.unreadable) {
            return HealthImportOutcome(
                label = outcome.app?.label,
                added = outcome.added,
                alreadyHeld = outcome.alreadyHeld,
                stepDays = 0,
                undated = outcome.undated,
            )
        }
    }
    return importHealthExport(open, fileName, zone, imperial, titles)
}

/**
 * The picked file as text, where it can be a training log at all and is small enough.
 *
 * None of the five training loggers ships a ZIP, and all of them write a CSV, so the first bytes
 * decide ([mayBeASetTable]) before anything more is read: a loose Apple `export.xml` of tens of
 * megabytes would otherwise be read whole, decoded and split into cells here, on a heap it does not
 * fit, only to be declined and handed to the reader that streams it.
 *
 * Nor is any of their files large — a decade of lifting is a few megabytes of CSV — so anything
 * bigger than [WORKOUT_TEXT_LIMIT] is certainly not one of theirs and is not decoded into a string
 * to find out: the file is read only as far as the limit.
 */
internal fun workoutExportText(open: () -> InputStream): String? = open().use { input ->
    val head = input.readNBytes(WORKOUT_SNIFF_BYTES)
    if (!head.mayBeASetTable()) return null
    val rest = input.readNBytes(WORKOUT_TEXT_LIMIT + 1 - head.size)
    if (head.size + rest.size > WORKOUT_TEXT_LIMIT) null else (head + rest).exportText()
}

/**
 * A file's bytes as text, in the encoding they are written in, and without the byte-order mark.
 *
 * UTF-8 nearly always — but a spreadsheet's "Unicode text" is UTF-16, and read as UTF-8 every other
 * byte of it is a NUL: the header would still be recognised, because [headerKey] keeps letters
 * alone, and every cell under it would read as no number and no date, so the rows would go without
 * a word. The mark says which order the two bytes go in; without one, the NUL beside the first
 * character does, which no UTF-8 text has.
 *
 * And the mark a Windows spreadsheet leaves at the front of a UTF-8 file goes. It matters far more
 * than it looks: it sits inside the first column's name, where no format would recognise `Date`;
 * before a JSON file's first brace, which nothing forgives; and inside the first cell of a file
 * with no header — a wger file of nothing but dates and weights, whose first date would read as no
 * date and sink the whole file. This is where an archive's entries become text too
 * ([ArchiveEntry.text]).
 */
internal fun ByteArray.exportText(): String {
    val opening = take(2)
    val nul = opening.map { it == NUL }
    return when {
        opening in UTF16_MARKS -> String(this, Charsets.UTF_16)
        nul == listOf(false, true) -> String(this, Charsets.UTF_16LE)
        nul == listOf(true, false) -> String(this, Charsets.UTF_16BE)
        else -> decodeToString().removePrefix(UTF8_MARK)
    }
}

/** `FF FE` opens a little-endian UTF-16 file and `FE FF` a big-endian one; the decoder reads either. */
private val UTF16_MARKS = listOf(listOf<Byte>(-1, -2), listOf<Byte>(-2, -1))
private const val NUL: Byte = 0

/** U+FEFF, written as its code point because lint rightly objects to an invisible one in a source file. */
private val UTF8_MARK = Char(0xFEFF).toString()

/** Enough to see past a byte-order mark and a blank line to the file's first character. */
private const val WORKOUT_SNIFF_BYTES = 16

private const val WORKOUT_TEXT_LIMIT = 16 * 1024 * 1024

/**
 * Whether a file that begins like this can be a table of sets: not an archive, and not the XML or
 * JSON the health exports are written in — `<`, `{` and `[` open those and no CSV header.
 */
internal fun ByteArray.mayBeASetTable(): Boolean {
    // The first character that is one: past a blank line, and past the byte-order mark a
    // spreadsheet leaves, which is a format character and not white space.
    val first = exportText().firstOrNull { !it.isWhitespace() && it.category != CharCategory.FORMAT }
    return !looksLikeAnArchive() && first != null && first !in NOT_A_TABLE
}

private const val NOT_A_TABLE = "<{["

private fun ByteArray.looksLikeAnArchive(): Boolean =
    size >= 2 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte()

/**
 * The rows of a file the journal does not already hold under another name, and the guessed nights
 * they replace.
 *
 * A marker recognises only what a file import filed. The same night also comes in through Health
 * Connect, is typed in by hand or is guessed from screen use, and the same period day is tapped on
 * the cycle screen — none of them under a file marker, so by marker alone the import would write
 * its own row beside each and the day's sleep would read as the sum of the two. [recorded] is what
 * the journal holds on the days in question, whatever wrote it, and the two rules are the ones the
 * Health Connect import keeps:
 *
 *   - A waking day that already holds a measured night is left alone, and one that holds only a
 *     guess gives the guess up to the measurement ([sleepGuessesToReplace]). The nights of one file
 *     do not rule each other out: a night and the nap after lunch end on one day and both land.
 *   - A day holds one period start and one period end — the cycle screen's own rule, which
 *     [importCycleRecords] applies too. The first one stated wins, within the file as well.
 *
 * A day holds many weighings, meals and sessions, so the day cannot answer for those; what Health
 * Connect brought of them is asked another way ([withoutWhatHealthConnectBrought]).
 */
internal fun List<JournalEntry>.withoutWhatIsRecorded(
    recorded: List<JournalEntry>,
): Pair<List<JournalEntry>, List<JournalEntry>> {
    val nights = recorded.filter { it.sleepStartAt != null }.groupBy(JournalEntry::day)
    val boundaries = recorded.filter { it.isPeriodBoundary() }.mapTo(mutableSetOf()) { it.kind to it.day }
    val fresh = filter { row ->
        when {
            row.sleepStartAt != null -> sleepGuessesToReplace(nights[row.day].orEmpty()) != null

            // Adding is the question and the answer at once: a day keeps the first boundary it is given.
            row.isPeriodBoundary() -> boundaries.add(row.kind to row.day)

            else -> true
        }
    }
    // A night that was kept stands on a day that held nothing but guesses, so what the day held goes.
    val guesses = fresh.filter { it.sleepStartAt != null }.map(JournalEntry::day).distinct()
        .flatMap { nights[it].orEmpty() }
    return fresh to guesses
}

private fun JournalEntry.isPeriodBoundary(): Boolean = kind == KIND_CYCLE || kind == KIND_CYCLE_END

/**
 * What the marker of a row Health Connect brought from each service's own app begins with, by the
 * label that service's file rows carry: `Health Connect:com.fitbit.FitbitMobile:` and then the
 * record's id ([HEALTH_IMPORT_MARKER_PREFIX]). Apple Health has no Android app and is not here; a
 * service that turns out to publish nothing has no such rows, and its line costs nothing.
 *
 * The two training loggers that publish their sessions are here as well, for the exchange to know
 * a session whose sets a file has already brought ([FiledFacts]) and for the file to know the
 * session it replaces ([sessionsTheSetsReplace]); the other three publish nothing.
 *
 * Each name is the app's id on Google Play, which is what Health Connect states as a record's
 * origin; the two loggers' are `com.hevy` and `io.strongapp.strong`.
 */
private val HEALTH_CONNECT_MARKERS: Map<String, String> = mapOf(
    HealthExportApp.FITBIT.label to "com.fitbit.FitbitMobile",
    HealthExportApp.GARMIN.label to "com.garmin.android.apps.connectmobile",
    HealthExportApp.STRAVA.label to "com.strava",
    HealthExportApp.MYFITNESSPAL.label to "com.myfitnesspal.android",
    HealthExportApp.NIKE_RUN_CLUB.label to "com.nike.plusgps",
    HealthExportApp.CRONOMETER.label to "com.cronometer.android.gold",
    HealthExportApp.FLO.label to "org.iggymedia.periodtracker",
    HealthExportApp.OURA.label to "com.ouraring.oura",
    HealthExportApp.WITHINGS.label to "com.withings.wiscale2",
    HealthExportApp.SAMSUNG_HEALTH.label to "com.sec.android.app.shealth",
    HealthExportApp.GOOGLE_HEALTH.label to "com.google.android.apps.fitness",
    WorkoutExportApp.HEVY.label to "com.hevy",
    WorkoutExportApp.STRONG.label to "io.strongapp.strong",
).mapValues { (_, packageName) -> "$HEALTH_IMPORT_MARKER_PREFIX$packageName:" }

/** What the marker of a row Health Connect brought from [service]'s own app begins with; null where it has none. */
internal fun healthConnectMarkerOf(service: String): String? = HEALTH_CONNECT_MARKERS[service]

/** The service whose own app published this row into Health Connect, by its label; null for any other row. */
internal fun JournalEntry.publishingService(): String? = healthConnectRecordKey?.let { marker ->
    HEALTH_CONNECT_MARKERS.entries.firstOrNull { marker.startsWith(it.value) }?.key
}

/**
 * What a row is a record of, in terms both roads share: its kind, and for a measurement the figure
 * it carries, because the file import and the exchange title the same reading differently. Null for
 * a night and a period day, which the day itself answers for ([withoutWhatIsRecorded]), and for a
 * measurement that carries no figure of the kinds below.
 */
private fun JournalEntry.sharedFact(): String? = when {
    sleepStartAt != null || isPeriodBoundary() -> null

    kind != KIND_MEASUREMENT -> kind

    measurementWeightKg != null -> "weight"

    else -> Vital.entries.firstOrNull { measurementPayloadJson.orEmpty().contains("\"${it.key}\"") }?.key
        ?: Vital.BODY_FAT.key.takeIf { bodyFatPercent != null }
}

/**
 * The rows of a file that Health Connect has not already brought from the service that wrote it.
 *
 * Most of these services also publish into Health Connect, and the exchange files what it reads
 * under the record's own id, which no export states. So no marker can say that the weighing in the
 * file is the weighing already here, and the months both roads cover would land twice — every meal,
 * session and weighing of them, and the day's calories doubled with them.
 *
 * What can be told is which days the other road has already covered. A day that holds a row of a
 * kind from a service's own app is a day that app published that kind on, and the exchange keeps up
 * with it; the file's rows of that kind on that day are left to it, and every other day is what the
 * file is for. Day by day, and not everything after the first such row: one old record edited in
 * the other app arrives on its own, years before the rest, and would cut a real history off behind
 * it. Kind by kind, because a service publishes some of what it exports and not the rest; service
 * by service, because what the scales' app published says nothing about the diary's file.
 * [published] is the journal, or as much of it as holds those rows: a row no such app published is
 * not looked at.
 *
 * Limitation: a day the app published only a part of — one session of two — keeps only that part,
 * until the exchange brings the rest.
 */
internal fun List<JournalEntry>.withoutWhatHealthConnectBrought(published: List<JournalEntry>): List<JournalEntry> {
    val covered = HashSet<Triple<String?, String, String>>()
    published.forEach { row ->
        val service = row.publishingService() ?: return@forEach
        row.sharedFact()?.let { covered += Triple(service, it, row.day) }
    }
    return filterNot { row ->
        val fact = row.sharedFact()
        fact != null && Triple(row.measurementSource, fact, row.day) in covered
    }
}

/**
 * What the export files of the services have already put in the journal, for the exchange to ask
 * before it files a record one of those services' own apps published: the other half of
 * [withoutWhatHealthConnectBrought], for a person who read the file first and connected Health
 * Connect afterwards. The record's id is one no file states, so without this every weighing, meal
 * and session of the months both roads cover would land a second time.
 *
 * The same question, turned round: a day that holds a file's row of a kind is a day the file
 * covered, and a record of that service and kind on it is left out. Up to the last such row and no
 * further — a file is written at one moment and never read again, so what the service recorded
 * after its last row, the evening of the day the export was taken above all, is the exchange's to
 * bring. Left out by the day alone it would never arrive: the feed offers a record once.
 *
 * Limitation: a diary that states no clock files its rows at one hour of the day, so on that last
 * day a meal the file already holds can stand later than the file's last row and land twice.
 */
internal class FiledFacts(filed: List<JournalEntry>) {
    private val days = HashSet<Triple<String?, String, String>>()
    private val latest = HashMap<Pair<String?, String>, Long>()

    init {
        filed.forEach { row ->
            val fact = row.sharedFact() ?: return@forEach
            days += Triple(row.measurementSource, fact, row.day)
            val reach = row.measurementSource to fact
            latest[reach] = maxOf(latest[reach] ?: Long.MIN_VALUE, row.occurredAt)
        }
    }

    /** Whether [brought], a row the exchange is about to file, is one a file of the same service has covered. */
    fun cover(brought: JournalEntry): Boolean {
        val service = brought.publishingService() ?: return false
        val fact = brought.sharedFact() ?: return false
        val last = latest[service to fact] ?: return false
        // To the minute: a file states its moments by the minute, the record by the second, so the
        // very last session of a logger's file would stand seconds past its own row and land twice.
        return brought.occurredAt - last < FILE_CLOCK_STEP_MS && Triple(service, fact, brought.day) in days
    }
}

/** The coarsest clock these exports state a moment by: a minute. */
private const val FILE_CLOCK_STEP_MS = 60_000L

/**
 * The sessions Health Connect brought from a training logger's own app that [this], the rows its
 * file has just added, state set by set: every such session on a day the file added an exercise to.
 *
 * The logger publishes a workout as one session with a length and nothing else; its file states the
 * same hour as exercises, sets and loads. Both standing, the day's training would be counted and
 * paid for in energy twice. The sets are the fuller account, so the session gives way to them, as a
 * guessed night does to a measured one ([withoutWhatIsRecorded]). A session a person has changed
 * here is theirs and stays, as the exchange leaves it alone ([JournalEntry.editedAt]).
 */
internal fun List<JournalEntry>.sessionsTheSetsReplace(brought: List<JournalEntry>): List<JournalEntry> {
    val days = filter { it.kind == KIND_ACTIVITY }.mapTo(HashSet(), JournalEntry::day)
    return brought.filter { it.kind == KIND_ACTIVITY && it.editedAt == null && it.day in days }
}

/**
 * The journal for [withoutWhatHealthConnectBrought] to look through, or nothing where the exchange
 * has brought no row from the services in [files] — asked first, by marker, because the answer is
 * otherwise the whole journal read to find none.
 */
private suspend fun StepsDao.broughtByHealthConnect(files: List<HealthImportFile>): List<JournalEntry> {
    val markers = files.mapNotNull { HEALTH_CONNECT_MARKERS[it.app.label] }
    return if (markers.any { fileImportMarkers(it).isNotEmpty() }) allJournal() else emptyList()
}

/**
 * Reads one exported archive into the journal.
 *
 * Idempotent, because it is a button somebody will press twice and because these exports overlap by
 * design — a person who exports every month hands over eleven months they have already imported.
 * Every row is filed under a marker naming the fact, and a marker already in the journal, or in the
 * tombstones because the row was imported once and then deliberately deleted, is skipped rather than
 * written again. Nothing is updated in place: a row already here keeps every correction made to it.
 * A night or a period day the journal holds from anywhere else is not written a second time either,
 * and a night guessed from screen use gives way to the imported one ([withoutWhatIsRecorded]); nor
 * is what Health Connect has been bringing from the same service
 * ([withoutWhatHealthConnectBrought]). All of them are counted as already here.
 */
suspend fun StepsDb.importHealthExport(
    open: () -> InputStream,
    fileName: String,
    zone: ZoneId,
    imperial: Boolean,
    titles: HealthImportTitles,
): HealthImportOutcome {
    val exports = readHealthExports(open, fileName, zone, imperial)
    val files = exports.files
    if (files.isEmpty()) {
        return HealthImportOutcome(null, 0, 0, 0, exports.skipped, exports.cutShort, exports.undated)
    }
    val read = files.map { it.rows(zone, titles) }
    val rows = HealthImportRows(read.flatMap { it.entries }, read.flatMap { it.stepDays }, read.sumOf { it.undated })
    val outcome = withTransaction {
        val dao = dao()
        // Under the names they were filed under and under the names the import gives them
        // ([withWallClockStampLast], [withFitbitDayStamp]): a row whose marker names the instant
        // rather than the wall clock, or a day's figure by its UTC midnight rather than its day, is
        // still the same history.
        val filed = dao.fileImportMarkers(FILE_IMPORT_MARKER_PREFIX) +
            dao.allTombstones().map(JournalTombstone::recordKey)
        val held = filed.toSet() + filed.mapNotNull { it.withWallClockStampLast(zone) } +
            filed.mapNotNull(String::withFitbitDayStamp)
        // One marker, one row — also within the file: two sources recording one instant would
        // otherwise both land, and the second import would then rightly skip both. The rows a file
        // gave no instant for are not folded here: [rows] has already named them apart.
        val unfiled = rows.entries
            .filterNot { it.healthConnectRecordKey in held }
            .distinctBy { it.healthConnectRecordKey }
        // A marker knows only what a file filed; what anything else put on the same days is asked
        // of the days themselves ([withoutWhatIsRecorded]).
        val wakingDays = unfiled.filter { it.sleepStartAt != null }.map(JournalEntry::day).distinct()
        val recorded = wakingDays.flatMap { dao.sleepOn(it) } + dao.cycleMarksNow()
        val (fresh, guesses) = unfiled
            .withoutWhatHealthConnectBrought(dao.broughtByHealthConnect(files))
            .withoutWhatIsRecorded(recorded)
        if (fresh.isNotEmpty()) dao.insertJournalEntries(fresh)
        // A replaced guess leaves its tombstone, as one deleted through [deleteJournalEvent] does:
        // its published copy is still in Health Connect, and a sync would read it back as history.
        val replacedAt = System.currentTimeMillis()
        guesses.forEach { guess ->
            dao.deleteJournalEntry(guess.id)
            journalTombstoneKeys(guess.id, guess.healthConnectRecordKey)
                .forEach { dao.saveTombstone(JournalTombstone(it, replacedAt)) }
        }
        // Step days are replaced rather than skipped: unlike a journal row, a day's total is a
        // figure the exporting service revises — a Fitbit day re-syncs when the watch catches up —
        // and the table's own primary key is the source and the day, so a later export corrects it.
        if (rows.stepDays.isNotEmpty()) dao.putDirectBleDays(rows.stepDays)
        // Priced in the same transaction as the rows, as [importWorkoutExport] does and for its
        // reason: a session left at nought by a process that ended in between is never priced later.
        if (fresh.isNotEmpty()) recalculateJournal()
        HealthImportOutcome(
            label = files.joinToString(" + ") { it.app.label },
            added = fresh.size,
            alreadyHeld = rows.entries.size - fresh.size,
            stepDays = rows.stepDays.size,
            skipped = exports.skipped,
            cutShort = exports.cutShort,
            undated = exports.undated + rows.undated,
        )
    }
    // What the recalculation does after a commit, now that there has been one: the alarms worked
    // out again and every widget redrawn. A file that brought days of steps and no row has nothing
    // to recalculate, so the widgets that read steps are asked for by name, or they would stand at
    // the old count.
    when {
        outcome.added > 0 -> {
            refreshMedicineAfterWrite()
            refreshWidgetsAfterWrite()
        }

        outcome.redrawsStepsAlone() -> refreshWidgetsAfterWrite(WIDGETS_OF_STEPS)
    }
    return outcome
}
