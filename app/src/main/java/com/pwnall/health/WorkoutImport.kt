package com.pwnall.health

import androidx.room.withTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Reading a training history out of the file another logger exported.
 *
 * Five apps, one shape: every one of them writes a CSV with one row per completed set, and rebuilds
 * the session above it by repeating the workout's own columns on each row. So there is one reader
 * here and not five — a [ExportFormat] is a table of column names and a handful of conversions, and
 * the walk from rows to journal entries is shared.
 *
 * What the app takes from these files is what it can hold: the exercise, its sets, the reps, the
 * load, and when the session was. Route, photographs, per-exercise notes and template structure are
 * left where they are; the journal has no column for them and inventing one for an import that may
 * never be run is a table nobody would fill.
 */

/** The loggers whose exports this reads, named as each names itself. */
enum class WorkoutExportApp(val label: String) {
    HEVY("Hevy"),
    STRONG("Strong"),
    FITNOTES("FitNotes"),
    WGER("wger"),
    LYFTA("Lyfta"),
}

/**
 * One completed set, in the terms all five files agree on.
 *
 * [startedAtMs] is the session's own start and is therefore the same on every row of a session —
 * that repetition is what the grouping below reads as "these rows are one workout". [loadKg] is
 * always kilograms by the time a row reaches here, whatever the file counted in.
 */
internal data class ImportedSet(
    val workoutTitle: String,
    val startedAtMs: Long,
    val exercise: String,
    val reps: Int?,
    val loadKg: Double?,
    val seconds: Int?,
    val distanceM: Double?,
    /** Reps left in the tank, where the file records an effort rating this app can hold. */
    val rir: Int?,
    /** A warm-up set: counted into the session, and not into the working numbers a person compares. */
    val warmup: Boolean,
    /** The session's whole length in seconds, where the file states one; null where it does not. */
    val sessionSeconds: Int?,
)

/**
 * One body-weight reading, out of a file that exports the scales rather than the training.
 *
 * wger is the reason this exists: its workout logs never leave the server as a file at all, and the
 * one export its interface offers an ordinary user is a two-column list of weights.
 *
 * [wallClock] is false where the file stated the moment with its offset, which wger's does: the
 * instant is then the same on every phone and is what the row is named by. A date with no offset
 * was made an instant in the phone's zone, and that row is named by the wall clock instead.
 */
internal data class ImportedWeight(val atMs: Long, val kg: Double, val wallClock: Boolean = true)

/** What one file turned into: the app that wrote it, and everything it held. */
internal data class WorkoutImportFile(
    val app: WorkoutExportApp,
    val sets: List<ImportedSet>,
    val weights: List<ImportedWeight> = emptyList(),
    /** Rows left out because no shape read their date ([ExportInstants.unread]). */
    val undated: Int = 0,
)

// ─── the file itself ────────────────────────────────────────────────────────────────────────────

/**
 * One record's cells, quotes honoured.
 *
 * Written out rather than split on the delimiter because every one of these files puts a comma
 * inside an exercise name or a workout title sooner or later — "Morning Workout, easy"
 * is one such title — and a naive split shifts every later column of that row without saying so. A
 * quote opens a field only where a field begins, so a bare inch mark inside a note ("5\" deficit")
 * stays the character it is instead of swallowing the rest of the file.
 */
// One branch per character class the machine can be looking at; splitting them up would hide the
// state machine rather than simplify it.
@Suppress("CyclomaticComplexMethod")
internal fun csvRecords(text: String, delimiter: Char): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var atFieldStart = true
    fun endCell() {
        row.add(cell.toString())
        cell.setLength(0)
        atFieldStart = true
    }
    fun endRow() {
        endCell()
        records += row
        row = mutableListOf()
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                cell.append('"')
                i++
            }

            quoted && c == '"' -> quoted = false

            quoted -> cell.append(c)

            c == '"' && atFieldStart -> quoted = true

            c == delimiter -> endCell()

            c == '\r' -> Unit

            c == '\n' -> endRow()

            else -> {
                cell.append(c)
                atFieldStart = false
            }
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
    // A trailing newline leaves one empty cell behind, and a blank line between sections leaves a
    // row of them; neither is a record.
    return records.filter { record -> record.any { it.isNotBlank() } }
}

/**
 * The character the file separates cells with.
 *
 * Asked rather than assumed: Strong has shipped both a comma-separated and a semicolon-separated
 * export, and a European spreadsheet re-saving any of these files turns the one into the other. The
 * answer is whichever candidate splits the header into the most cells, which is the same test a
 * person applies by eye.
 */
internal fun csvDelimiter(text: String): Char {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    return listOf(',', ';', '\t').maxByOrNull { candidate ->
        csvRecords(firstLine, candidate).firstOrNull()?.size ?: 0
    } ?: ','
}

/**
 * A header cell reduced to what identifies it: letters and digits, folded to lower case.
 *
 * The five files name the same column five ways — `set_index`, `Set Order`, `Set Type`, `weight_kg`
 * — and one of them ships a header whose very first byte is a space. Comparing on the letters alone
 * makes every one of those spellings the same key, and makes a file re-saved by a spreadsheet that
 * has retitled `Weight (kg)` to `Weight (kg) ` compare equal too.
 */
internal fun String.headerKey(): String =
    trim().trim('"').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

/** One row, addressed by column name rather than by position. */
internal class CsvRow(private val columns: Map<String, Int>, private val cells: List<String>) {
    /**
     * The cell under [name], trimmed; empty when the column is absent, blank, or the literal `null`.
     *
     * `null` as four letters rather than as an empty cell is Lyfta's, and it is one of three
     * spellings of absence that file mixes inside itself — sets logged before about August 2024
     * write a bare `0`, later ones write nothing, and a 2026 export writes `null`. The third is
     * handled here; the `0` cannot be, because a `0` in the weight column is a real reading — a set
     * carrying nothing but the body — and only the columns where it cannot be one reject it.
     */
    operator fun get(name: String): String =
        columns[name]?.let(cells::getOrNull).orEmpty().trim().let { if (it.equals("null", true)) "" else it }

    fun number(name: String): Double? = this[name].replace(',', '.').toDoubleOrNull()

    fun int(name: String): Int? = number(name)?.toInt()

    /**
     * The name of the first column whose name begins with [prefix], for [number] to read.
     *
     * Hevy writes the unit into the column name and nowhere else — `weight_kg` or `weight_lbs`,
     * `distance_km` or `distance_miles`, and the two settings are independent of each other — so
     * the reader has to ask which of them the file carries before it can read the number under it.
     */
    fun startingWith(prefix: String): String? = columns.keys.firstOrNull { it.startsWith(prefix) }
}

// ─── the formats ────────────────────────────────────────────────────────────────────────────────

/**
 * One app's export, as the reader needs to know it: how to recognise the file, and how to read one
 * of its rows.
 *
 * [identity] is the set of columns that no other one of these files has all of. Recognition is by
 * column names and never by file name, because a file arrives from a downloads folder called
 * whatever the person renamed it to, and because two of the five call their export `workouts.csv`.
 */
internal class ExportFormat(
    val app: WorkoutExportApp,
    val identity: Set<String>,
    val row: (CsvRow) -> ImportedSet?,
)

/**
 * `HH:mm:ss`, `mm:ss` or a bare number of seconds, as seconds.
 *
 * All three appear: a session length is written with hours, a timed set without them, and a file
 * that has been through a spreadsheet may have lost the colons altogether.
 */
internal fun clockSeconds(text: String): Int? {
    val parts = text.split(':')
    val numbers = parts.mapNotNull { it.trim().toIntOrNull() }
    return when {
        text.isBlank() -> null

        parts.size == 1 -> text.toDoubleOrNull()?.toInt()

        // A part that is not a number at all makes the whole span unreadable rather than shorter.
        numbers.size != parts.size -> null

        else -> numbers.fold(0) { total, part -> total * 60 + part }
    }
}

/**
 * The timestamps of one file, read in every language they might have been written in.
 *
 * Hevy renders its timestamps through the exporting device's own locale, so a Spanish phone writes
 * `9 jun 2026`, an Italian one `18 ago 2026`, a French one `01 janv. 2024` and a Turkish one
 * `27 Ara 2024`. Read under English alone, every row of such a file fails to parse — and a file
 * whose every row fails is not reported as broken, it is imported as an empty history, which is the
 * worst way this could go wrong. So the same handful of shapes is offered in each language the app
 * itself ships, and in whatever language the phone is set to now.
 *
 * "Now" is the reason this is an object rather than a table of constants: the app's language can be
 * changed while it is running, and a default locale captured once at class-loading time would be
 * the language the app started in rather than the one it is showing.
 *
 * Each distinct string is read once. A workout repeats its own start on every row of it, so a file
 * with a thousand sessions holds a thousand stamps across tens of thousands of rows, and offering
 * seventeen languages of seven shapes to every one of those rows in turn — most of them failing —
 * would be a great deal of work repeated for an answer already known. Reading each string once is
 * what makes the breadth above free.
 */
internal class ExportInstants(private val zone: ZoneId) {
    private val locales = (listOf(Locale.getDefault()) + SHIPPED_LOCALES).distinct()
    private val timestamps = TIMESTAMP_PATTERNS.formatters()
    private val dates = DATE_PATTERNS.formatters()
    private val known = HashMap<String, Long?>()
    private val zoned = HashSet<String>()

    /**
     * How many times a row's own moment was asked for and no shape read it: the rows the file lost
     * to its dates. The import reports the number, because a file whose every row is lost this way
     * otherwise reads as a history with nothing in it.
     */
    var unread = 0
        private set

    private fun List<String>.formatters(): List<DateTimeFormatter> =
        locales.flatMap { locale -> map { DateTimeFormatter.ofPattern(it, locale) } }

    /** A row's own moment. One that no shape reads is counted ([unread]): the row goes with it. */
    fun of(text: String): Long? = peek(text).also { if (it == null && text.isNotBlank()) unread += 1 }

    /** The same reading of a stamp the row can do without — a session's end, a clock beside a day. */
    fun peek(text: String): Long? =
        if (known.containsKey(text)) known[text] else read(text).also { known[text] = it }

    /**
     * Whether [text], once read, turned out to state no offset: its instant was made in the phone's
     * zone, and what it dates is named by the wall clock so that another zone names it the same. A
     * stamp that carries its offset is one instant everywhere and is named by that.
     */
    fun isWallClock(text: String): Boolean = text !in zoned

    /**
     * One stamp, as milliseconds.
     *
     * Four of the five files write local time with no offset, which is the right reading: a set was
     * pressed at half past six in the evening where the person was standing, and that is the moment
     * the journal files it at.
     *
     * A file that stores a date and no clock — FitNotes keeps one — is read as midday rather than
     * as midnight. Nothing is known about the hour either way, and midnight is the one instant in
     * the day where being an hour out puts the workout on the wrong date: a zone that skips 00:00
     * for summer time, or a record read back after the person has moved a zone west, both land the
     * session on the day before the one the file names.
     */
    private fun read(text: String): Long? {
        val cleaned = text.trim().trim('"')
        if (cleaned.isBlank()) return null
        // The offset first. wger writes a Python datetime — an ISO date and time joined by a space,
        // carrying a UTC offset and sometimes microseconds — so swapping in the `T` it is missing
        // makes it the ISO instant it almost already is, and the offset is then read rather than
        // assumed. It is the one of the five that does not write local time. A stamp with no offset
        // fails here and falls through to the local-time shapes below, which is also right.
        val stated = runCatching { OffsetDateTime.parse(cleaned.replaceFirst(" ", "T")).toInstant() }
            .getOrNull()
            ?.toEpochMilli()
        if (stated != null) zoned += text
        return stated
            ?: timestamps.firstNotNullOfOrNull { pattern ->
                runCatching { LocalDateTime.parse(cleaned, pattern).atZone(zone).toInstant().toEpochMilli() }
                    .getOrNull()
            }
            ?: dates.firstNotNullOfOrNull { pattern ->
                runCatching { LocalDate.parse(cleaned, pattern).atTime(NOON, 0).atZone(zone) }
                    .getOrNull()
                    ?.toInstant()
                    ?.toEpochMilli()
            }
    }
}

/** Every language this app ships, which is the set a month abbreviation is likely written in. */
private val SHIPPED_LOCALES: List<Locale> = (
    listOf(Locale.ROOT) +
        listOf("en", "ru", "de", "fr", "es", "it", "pt", "pl", "cs", "tr", "in", "hi", "ja", "ko", "zh", "ar")
            .map(Locale::forLanguageTag)
    ).distinct()

/** The hour a dateless day is filed at. */
private const val NOON = 12

/** Limitation: the shapes observed in exports, tried in turn, rather than a general parser. */
private val TIMESTAMP_PATTERNS = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "d MMM yyyy, HH:mm",
    "dd MMM yyyy, HH:mm",
    // The same Hevy export rendered on a phone set to the United States. Two commas in one value,
    // a twelve-hour clock, and no padding on either the day or the hour.
    "MMM d, yyyy, h:mm a",
    "MMM d, yyyy, h:mm:ss a",
    // Strava renders its one timestamp column through the account's own language: an English
    // account writes `Dec 15, 2024, 11:29:06 PM`, a British one `22 May 2024, 08:19:26`, and a
    // German one `02.04.2024, 14:05:15`. A shape that fails to parse is not reported as a broken
    // file, it is imported as an empty history — so all three are offered.
    "d MMM yyyy, HH:mm:ss",
    "dd MMM yyyy, HH:mm:ss",
    "dd.MM.yyyy, HH:mm:ss",
    "dd.MM.yy, HH:mm:ss",
    // A day and a clock out of two separate columns, joined by the reader that found them.
    // Cronometer and MyFitnessPal both keep the date and the time apart and both write the clock on
    // twelve hours; Cronometer has also been seen writing it on twenty-four, which the shapes above
    // already read.
    "yyyy-MM-dd h:mm a",
    "yyyy-MM-dd h:mm:ss a",
)

private val DATE_PATTERNS = listOf(
    "yyyy-MM-dd",
    "d MMM yyyy",
    "dd MMM yyyy",
)

/**
 * Lyfta.
 *
 * One row per set; the session is the run of rows sharing `Title`, `Date` and `Duration`. Loads are
 * kilograms to three decimal places, `0.000` meaning the set carried nothing but the body; an empty
 * cell arrives as the four letters `null` rather than as a blank, which [CsvRow.get] already reads
 * as nothing. The set type is SCREAMING_SNAKE and only `WARMUP_SET` changes how a set is counted —
 * the others (drop, failure, negatives, back-off) are working sets that this app has no column to
 * distinguish and does not pretend to.
 *
 * The header's very first byte is a space, before `Title`. [headerKey] eats it.
 *
 * Nothing in this file says what unit the loads are in — see [unitLessFactor]. And an exercise may
 * appear twice in one session with other exercises between the two runs, which happens in about one
 * session in ten; both runs are one row of this journal, because one row per exercise per session
 * is how a session logged in this app is stored, and splitting them would show one bench press
 * twice on the same evening.
 */
private fun lyftaFormat(instants: ExportInstants, imperial: Boolean) = ExportFormat(
    app = WorkoutExportApp.LYFTA,
    identity = setOf("title", "date", "duration", "exercise", "supersetid", "settype"),
) { row ->
    val startedAt = instants.of(row["date"]) ?: return@ExportFormat null
    ImportedSet(
        workoutTitle = row["title"],
        startedAtMs = startedAt,
        exercise = row["exercise"],
        reps = row.int("reps"),
        loadKg = row.number("weight")?.takeIf { it > 0.0 }?.div(unitLessFactor(imperial)),
        // A bare `0` here is the older spelling of "this set was not timed", never a set that took
        // no time; reading it as a zero-second hold would make the session's own length disagree
        // with the sets under it.
        seconds = clockSeconds(row["time"])?.takeIf { it > 0 },
        // Limitation: kilometres is a guess, and the only one in this file. The column is left
        // empty in the exports this reads, so its unit is not known; it reaches nothing but the
        // row's subtitle, which is why a guess is tolerable here and nowhere else.
        distanceM = row.number("distance")?.times(METRES_IN_A_KILOMETRE),
        rir = null,
        warmup = row["settype"].headerKey() == "warmupset",
        sessionSeconds = clockSeconds(row["duration"]),
    )
}

/**
 * FitNotes.
 *
 * Nine columns, or ten when "include comments" is on. It is the plainest of the five and the
 * trickiest for it: nothing is quoted except that tenth column, there is no workout name, no set
 * order and no clock — a session is a day, and nothing more precise exists in the file.
 *
 * Two columns carry their own unit and both are read per row rather than per file, because they
 * genuinely vary inside one: FitNotes hangs the weight unit off the exercise, so a person who logs
 * the bench in kilograms and the dumbbells in pounds exports a file that mixes them. `Time` here is
 * how long the set took, not the hour of day.
 */
private fun fitNotesFormat(instants: ExportInstants) = ExportFormat(
    app = WorkoutExportApp.FITNOTES,
    // Neither the weight column nor the comment column is asked for: the Android build names its
    // weight `Weight` beside a `Weight Unit`, the iOS one writes `Weight (kg)` and `Weight (lbs)`
    // side by side with no unit column at all, and the comment is there only when its setting is on.
    identity = setOf("date", "exercise", "category", "reps", "distance", "distanceunit", "time"),
) { row ->
    val startedAt = instants.of(row["date"]) ?: return@ExportFormat null
    ImportedSet(
        workoutTitle = row["category"],
        startedAtMs = startedAt,
        exercise = row["exercise"],
        reps = row.int("reps"),
        loadKg = fitNotesLoadKg(row),
        seconds = clockSeconds(row["time"])?.takeIf { it > 0 },
        distanceM = row.number("distance")?.times(metresPerUnit(row["distanceunit"])),
        rir = null,
        // FitNotes has no notion of a warm-up set: every row it writes is a set that was performed.
        warmup = false,
        sessionSeconds = null,
    )
}

/**
 * The load in kilograms, from whichever of the two FitNotes builds wrote the row.
 *
 * The iOS app puts kilograms and pounds in two columns of their own and fills whichever the person
 * logged in, so the populated one names its own unit. The Android app writes one number and a
 * `Weight Unit` beside it, and the number is already in that unit — it is not kilograms wearing a
 * label — so it is divided rather than left alone. That column is per-exercise rather than per-file,
 * which is why it is read row by row: somebody who benches in kilograms and presses dumbbells in
 * pounds exports a file that genuinely mixes the two.
 */
private fun fitNotesLoadKg(row: CsvRow): Double? =
    row.number("weightkg")?.takeIf { it > 0.0 }
        ?: row.number("weightlbs")?.takeIf { it > 0.0 }?.div(POUNDS_IN_A_KILOGRAM)
        ?: row.number("weight")?.takeIf { it > 0.0 }?.div(poundsPerUnit(row["weightunit"]))

/**
 * How many of a unit make a kilogram. `lbs` and `kgs` are the only two FitNotes writes, and an
 * unnamed unit is kilograms — which is also the right answer for a column that is not there at all.
 */
private fun poundsPerUnit(unit: String): Double =
    if (unit.headerKey() == "lbs") POUNDS_IN_A_KILOGRAM else 1.0

/**
 * Metres in one of the distance units these files name; an unnamed one counts as metres.
 *
 * Seven of them, because the iOS FitNotes offers seven — the Android one writes only the first four.
 */
private fun metresPerUnit(unit: String): Double = when (unit.headerKey()) {
    "km" -> METRES_IN_A_KILOMETRE
    "mi", "miles" -> METRES_IN_A_MILE
    "ft", "feet" -> METRES_IN_A_FOOT
    "yd", "yards" -> METRES_IN_A_YARD
    "cm" -> METRES_IN_A_CENTIMETRE
    "in", "inches" -> METRES_IN_AN_INCH
    else -> 1.0
}

/**
 * Hevy.
 *
 * Fourteen columns, one row per set, and every workout and exercise column repeated on every row of
 * it. The session is the run of rows sharing `start_time`, and its length is the distance to
 * `end_time` — the only one of the five that states a session's start and finish rather than a
 * start and a duration.
 *
 * Two things about this file need care. The unit is written into the column name and nowhere else,
 * and the weight and distance settings move independently, so a file can hold pounds and kilometres
 * at once; [CsvRow.startingWith] is what asks which. And the timestamp is rendered in the phone's
 * own locale, so the same app writes `5 Dec 2025, 11:37` in Prague and `Jun 19, 2026, 9:40 AM` in
 * Chicago — the second of which puts a comma inside a value, which is what the quoting is for.
 *
 * Hevy is also the only one of the five that records effort as RPE, which is this app's RIR read
 * from the other end: eight out of ten is two left in the tank.
 */
private fun hevyFormat(instants: ExportInstants) = ExportFormat(
    app = WorkoutExportApp.HEVY,
    identity = setOf("title", "starttime", "endtime", "exercisetitle", "supersetid", "setindex", "settype"),
) { row ->
    val startedAt = instants.of(row["starttime"]) ?: return@ExportFormat null
    val weight = row.startingWith("weight")
    val distance = row.startingWith("distance")
    ImportedSet(
        workoutTitle = row["title"],
        startedAtMs = startedAt,
        exercise = row["exercisetitle"],
        reps = row.int("reps"),
        // Read as [CsvRow.number] reads every figure, so that `82,5` from a file a European
        // spreadsheet has re-saved is a load and not a blank.
        loadKg = weight?.let(row::number)?.takeIf { it > 0.0 }
            ?.let { if (weight == "weightlbs") it / POUNDS_IN_A_KILOGRAM else it },
        seconds = row.int("durationseconds")?.takeIf { it > 0 },
        distanceM = distance?.let(row::number)
            ?.times(if (distance == "distancemiles") METRES_IN_A_MILE else METRES_IN_A_KILOMETRE),
        rir = row.number("rpe")?.let(::rirFromRpe),
        warmup = row["settype"].headerKey() == "warmup",
        sessionSeconds = instants.peek(row["endtime"])
            ?.minus(startedAt)
            ?.takeIf { it > 0 }
            ?.let { (it / MILLISECONDS_IN_A_SECOND).toInt() },
    )
}

/**
 * An RPE as this app's reps in reserve, which is the same measurement counted from the other end:
 * nothing left is ten, two in reserve is eight. Hevy and Strong both write halves (7.5, 9.5) and
 * this app's rest card offers whole ones, so the half rounds — upward, the way every other figure
 * in the app rounds, which credits the harder-sounding rating with the easier reserve rather than
 * the other way about. Anything past the far end of the scale's four is four.
 */
internal fun rirFromRpe(rpe: Double): Int? =
    (RPE_TOP - rpe).takeIf { rpe > 0.0 }?.let { Math.round(it).toInt().coerceIn(RIR_CHOICES) }

private const val RPE_TOP = 10.0
private const val MILLISECONDS_IN_A_SECOND = 1_000L

/**
 * What to divide a load by when the file never says what unit it is in.
 *
 * Lyfta writes a bare number and carries the unit in the exporting account's profile, which is not
 * in the file at all; wger's body-weight CSV does the same. There is no way to recover it from the
 * bytes — 90 is a plausible bench in either unit — and getting it wrong is not a rounding error but
 * a history 2.2 times too heavy. So the reader's own profile decides, on the reasoning that a
 * person moving their training history between two apps was almost certainly reading both of them
 * in the same unit; the import card says as much before the file is picked, so the assumption is
 * one the person can check rather than one made behind them.
 */
internal fun unitLessFactor(imperial: Boolean): Double = if (imperial) POUNDS_IN_A_KILOGRAM else 1.0

/** Every format this build can read. */
internal fun exportFormats(instants: ExportInstants, imperial: Boolean): List<ExportFormat> = listOf(
    hevyFormat(instants),
    strongFormat(instants, imperial),
    lyftaFormat(instants, imperial),
    fitNotesFormat(instants),
)

/**
 * Strong, which has shipped four exports and calls three of them the same thing.
 *
 * The iOS export is comma-separated with twelve columns and a `Duration` of `1h 5m`; the Android
 * one is SEMICOLON-separated with fourteen, carries its own `Weight Unit` column and puts the
 * session length last under `Workout Duration`; an older Android build wrote the same thing without
 * that column at all; and the newest layout quotes every single cell, numbers the workouts, and
 * names the unit inside the column heading as `Weight (kg)` and `Duration (sec)`.
 *
 * One reader takes all four, because [headerKey] and [csvDelimiter] have already flattened the
 * differences that are only spelling. What is left is genuinely different and is handled here:
 * where the unit lives, and what a row is.
 *
 * The unit lives in three places depending on the variant — in a column of its own, in the column
 * heading, or nowhere at all — and the column of its own is a translated word, so a Russian export
 * says `кг`. [strongLoadKg] tries the heading, then the column, then falls back to the reader's own
 * profile the way [unitLessFactor] does everywhere else.
 *
 * And not every row is a set. Strong writes a rest interval as a row of its own with `Rest Timer`
 * in the set-order column, and an exercise note as one with `Note`; in some exports half the lines
 * are rest timers. A rest timer looks exactly like a plank — no weight, no reps, and seconds — so
 * they cannot be told apart by their numbers, only by that column, which is why a row whose set
 * order is neither a number nor one of Strong's own set letters is dropped rather than read.
 */
private fun strongFormat(instants: ExportInstants, imperial: Boolean) = ExportFormat(
    app = WorkoutExportApp.STRONG,
    identity = setOf("date", "workoutname", "exercisename", "setorder", "seconds"),
) { row ->
    val order = row["setorder"]
    if (!order.isStrongSet()) return@ExportFormat null
    val startedAt = instants.of(row["date"]) ?: return@ExportFormat null
    ImportedSet(
        workoutTitle = row["workoutname"],
        startedAtMs = startedAt,
        exercise = row["exercisename"],
        reps = row.int("reps")?.takeIf { it > 0 },
        loadKg = strongLoadKg(row, imperial),
        seconds = row.int("seconds")?.takeIf { it > 0 },
        distanceM = strongDistanceM(row),
        rir = row.number("rpe")?.let(::rirFromRpe),
        warmup = order.equals(STRONG_WARMUP_SET, ignoreCase = true),
        // Named `Duration` on one variant and `Workout Duration` on another, absent on a third, and
        // a plain count of seconds on the fourth.
        sessionSeconds = spanSeconds(row["duration"].ifBlank { row["workoutduration"] })
            ?: row.int("durationsec"),
    )
}

/** The set-order cell Strong marks a warm-up with; the drop and failure letters are working sets. */
private const val STRONG_WARMUP_SET = "W"
private val STRONG_SET_LETTERS = setOf(STRONG_WARMUP_SET, "D", "F")

/**
 * Whether this set-order cell names a set at all.
 *
 * A number does, and so do Strong's three set letters. `Rest Timer` and `Note` do not, and neither
 * does whatever those two are called in a language this does not read — which is the point of
 * asking the question this way round: an unrecognised word is not imported as a set of nothing.
 */
private fun String.isStrongSet(): Boolean =
    toDoubleOrNull() != null || uppercase(Locale.ROOT) in STRONG_SET_LETTERS

/**
 * The load in kilograms, from whichever of the three places this variant put the unit.
 *
 * The heading first (`Weight (kg)`), then a column of its own, then the reader's own profile. The
 * column of its own is translated, so it is matched against the spellings of a pound and of a
 * kilogram rather than against the token `lbs`; a word in neither list is treated as no answer at
 * all, which lands on the same profile fallback as a file that never had the column.
 */
private fun strongLoadKg(row: CsvRow, imperial: Boolean): Double? {
    val named = row.startingWith("weight")?.takeIf { it != "weightunit" } ?: return null
    // The Android export is the semicolon-separated one, and a comma is its decimal mark wherever
    // the phone's language writes one: `82,5`.
    val raw = row.number(named)?.takeIf { it > 0.0 } ?: return null
    return raw / when (named) {
        "weightkg" -> 1.0
        "weightlbs" -> POUNDS_IN_A_KILOGRAM
        else -> namedUnitFactor(row["weightunit"], imperial)
    }
}

/**
 * Metres, from a heading that names the unit or a column that translates it; a distance with no
 * unit named is read as kilometres.
 */
private fun strongDistanceM(row: CsvRow): Double? {
    val named = row.startingWith("distance")?.takeIf { it != "distanceunit" } ?: return null
    val raw = row.number(named)?.takeIf { it > 0.0 } ?: return null
    return raw * when {
        named == "distancemeters" -> 1.0
        MILE_WORDS.any { row["distanceunit"].startsWith(it, ignoreCase = true) } -> METRES_IN_A_MILE
        else -> METRES_IN_A_KILOMETRE
    }
}

/** How many of a named unit make a kilogram, or the profile's answer when the name is not one of these. */
private fun namedUnitFactor(unit: String, imperial: Boolean): Double = when {
    POUND_WORDS.any { unit.startsWith(it, ignoreCase = true) } -> POUNDS_IN_A_KILOGRAM
    KILOGRAM_WORDS.any { unit.startsWith(it, ignoreCase = true) } -> 1.0
    else -> unitLessFactor(imperial)
}

// The unit columns hold a translated string resource rather than a token, so these are the openings
// of the word in the languages this app itself ships. Anything else falls back to the profile.
private val POUND_WORDS = listOf("lb", "фунт", "libra", "livre", "pfund", "ポンド", "파운드", "磅")
private val KILOGRAM_WORDS = listOf("kg", "кг", "kilo", "キロ", "킬로", "公斤", "千克")
private val MILE_WORDS = listOf("mi", "ми", "мил", "マイル", "마일", "英里")

/**
 * A written span — `1h 5m`, `48m`, `8s`, `1ч 15м` — as seconds, or a bare number taken as seconds.
 *
 * Strong writes the unit letters through its own translations, so the suffix is read by its first
 * character against the openings of hour and second; everything else is minutes, which is both the
 * commonest part and the safe assumption, since a span written with no suffix at all is not one of
 * the shapes this app has ever seen out of these files.
 */
internal fun spanSeconds(text: String): Int? {
    val parts = SPAN_PART.findAll(text).toList()
    return text.trim().toDoubleOrNull()?.toInt()
        ?: parts.takeIf { it.isNotEmpty() }
            ?.sumOf { part -> part.groupValues[1].toInt() * part.groupValues[2].spanUnitSeconds() }
}

private val SPAN_PART = Regex("""([0-9]+)$SPACE*([^0-9${SPACE_CHARS}]+)""")

private fun String.spanUnitSeconds(): Int = when (first().lowercaseChar()) {
    'h', 'ч', '時', '시' -> SECONDS_IN_AN_HOUR
    's', 'с', '秒', '초' -> 1
    else -> SECONDS_IN_A_MINUTE.toInt()
}

private const val SECONDS_IN_AN_HOUR = 3_600

/**
 * wger, which is the odd one of the five.
 *
 * wger's training log never leaves the server as a file. Its interface exports body weight and
 * nothing else; the sets, reps and routines are reachable only through the REST API, which needs a
 * network this app deliberately holds no permission for — and which names every exercise by a
 * numeric id into a catalogue the response does not carry, so even a saved copy of that JSON would
 * import a history of "exercise 73". What is read here is therefore what wger actually hands over.
 *
 * It cannot be recognised by its column names, because wger translates them: `Date,Weight` in
 * English, `Datum,Gewicht` in German, `Fecha,Peso` in Spanish. It is recognised by its shape
 * instead — two columns, every row a moment and a weight written as wger writes one ([WGER_WEIGHT]).
 * Any moment and any number in a person's range would be too weak a claim: a resting pulse a day
 * fits it, and would import as weighings. It is still asked only after the four named formats have all declined.
 *
 * The unit is the exporting account's own setting and appears nowhere in the file, so it is read
 * the way Lyfta's loads are; see [unitLessFactor]. A reading outside what a person can weigh sinks
 * the whole file rather than being skipped on its own: one impossible row in a two-column file is
 * the sign that this was never a weight export, and a file of something else fails on every row.
 */
private fun wgerWeights(records: List<List<String>>, instants: ExportInstants, imperial: Boolean): WorkoutImportFile? {
    val rows = records.filter { it.size == WGER_COLUMNS }.map { CsvRow(WGER_POSITIONS, it) }
    if (rows.isEmpty()) return null
    // The first row is dropped only if it is a header: wger writes a translated word this reader
    // cannot know it by, and the same two columns may equally arrive with no header at all.
    val body = if (rows.first().number(WGER_VALUE) == null) rows.drop(1) else rows
    val weights = body.mapNotNull { row ->
        val at = instants.of(row[WGER_DATE]) ?: return@mapNotNull null
        if (!WGER_WEIGHT.matches(row[WGER_VALUE])) return@mapNotNull null
        val kg = row.number(WGER_VALUE)?.div(unitLessFactor(imperial)) ?: return@mapNotNull null
        ImportedWeight(at, kg, instants.isWallClock(row[WGER_DATE])).takeIf { kg in PLAUSIBLE_KG }
    }
    return if (weights.isEmpty() || weights.size != body.size) {
        null
    } else {
        WorkoutImportFile(WorkoutExportApp.WGER, sets = emptyList(), weights = weights)
    }
}

private const val WGER_COLUMNS = 2

/** wger's two columns are addressed by position, because their names are translated. */
private const val WGER_DATE = "0"
private const val WGER_VALUE = "1"
private val WGER_POSITIONS = mapOf(WGER_DATE to 0, WGER_VALUE to 1)

/**
 * A weight as wger writes every one: a Decimal of two places, `83.40`, never `83.4` or `83`. The old
 * `WeightEntry` held it as `DecimalField(max_digits=5, decimal_places=2)`, and the measurements that
 * replaced it keep two places and quantize a converted value to two; the export prints it as is.
 */
private val WGER_WEIGHT = Regex("[0-9]+\\.[0-9]{2}")

/**
 * What a file holds, or null when nothing claims it.
 *
 * A file is claimed by the format all of whose identifying columns it has; where more than one
 * claims it — a spreadsheet that has been edited into a shape two formats both fit — the one
 * matching on more columns wins, because the more specific match is the less accidental one.
 * [wgerWeights] is asked last and only if none of them did, because it knows a file by its shape
 * rather than by its column names and would otherwise claim files that are not its.
 */
internal fun readWorkoutExport(text: String, zone: ZoneId, imperial: Boolean): WorkoutImportFile? {
    // No byte-order mark to take off: the bytes became this text through [exportText], which did.
    val records = csvRecords(text, csvDelimiter(text))
    val columns = records.firstOrNull()
        .orEmpty()
        .mapIndexed { index, cell -> cell.headerKey() to index }
        .filter { it.first.isNotEmpty() }
        .toMap()
    val instants = ExportInstants(zone)
    val format = exportFormats(instants, imperial)
        .filter { it.identity.all(columns::containsKey) }
        .maxByOrNull { it.identity.size }
    return format?.let {
        val sets = records.drop(1).mapNotNull { row -> it.row(CsvRow(columns, row)) }
            .filter { set -> set.exercise.isNotBlank() }
        // Counted once the rows have been read, which is when the dates were.
        WorkoutImportFile(it.app, sets, undated = instants.unread)
    } ?: wgerWeights(records, instants, imperial)
}

// ─── from foreign exercise names to this app's catalogue ────────────────────────────────────────

/**
 * The exercise in this app's catalogue that a foreign name means.
 *
 * These files name an exercise the way the app that wrote it names one: "Bench Press (Barbell)",
 * "Barbell Bench Press", "Lever Narrow Grip Seated Row", "Bench Press - Dumbbell". There is no
 * identifier to join on and there never will be, so the match is on the words — the first rule
 * whose phrase appears in the name wins, and the order below is the whole of the logic: "Bulgarian
 * Split Squat" has to reach the lunge rule before it reaches the squat rule, and "Box Jump" must
 * not reach boxing.
 *
 * A name no rule claims becomes [FALLBACK_ACTIVITY_CODE] — generic strength training — and keeps
 * its own name as the row's title. That is the honest answer: the sets, reps and load are all
 * recorded, and only the energy estimate falls back to the average of resistance training. Guessing
 * a specific exercise from a name nobody recognised would put a wrong MET on it and a wrong muscle
 * map under it.
 */
internal fun activityCodeFor(exercise: String): String {
    val name = exercise.lowercase(Locale.ROOT).replace('-', ' ').replace('_', ' ')
    return EXERCISE_RULES.firstOrNull { (_, phrases) -> phrases.any(name::containsPhrase) }?.first
        ?: FALLBACK_ACTIVITY_CODE
}

/**
 * Whether [phrase] appears in this name as the start of a word rather than buried inside one.
 *
 * Some of the phrases below are short and generic — `row`, `dip`, `curl` — because that is how the
 * exercises are named, and a plain substring test files a medicine-ball throw as a barbell row.
 * Only the start of the phrase is pinned to a boundary, not its end, so a rule can still be written
 * as the opening of a word it means to catch every ending of: `hik` takes both hike and hiking.
 */
private fun String.containsPhrase(phrase: String): Boolean =
    generateSequence(indexOf(phrase)) { at -> indexOf(phrase, at + 1).takeIf { it >= 0 } }
        .takeWhile { it >= 0 }
        .any { it == 0 || !this[it - 1].isLetter() }

/** What an unrecognised exercise is filed as: resistance training, in general. */
internal const val FALLBACK_ACTIVITY_CODE = "STRENGTH"

/**
 * Ordered. Read it as a sieve, not as a dictionary: each line takes everything the lines above it
 * left behind. Machine variants come before the free-weight ones they contain ("shoulder press
 * (machine)" before "shoulder press"), compound names before their parts ("split squat" before
 * "squat"), and every phrase is one a real export writes.
 */
private val EXERCISE_RULES: List<Pair<String, List<String>>> = listOf(
    // Machines first: each of these names contains a free-weight movement's name inside it.
    "SHOULDER_PRESS_MACHINE" to listOf("shoulder press (machine", "machine shoulder press", "shoulder press machine"),
    "CHEST_PRESS" to listOf("chest press", "machine press"),
    // Before the pec deck, whose names it contains.
    "REVERSE_FLY" to
        listOf("reverse pec", "reverse peck", "reverse fly", "reverse flye", "reverse butterfly", "rear delt"),
    "PEC_DECK" to listOf("pec deck", "pec fly", "chest fly", "chest flye", "butterfly", "peck deck"),
    "CABLE_CROSSOVER" to listOf("crossover", "cable fly", "cable flye"),
    "LAT_PULLDOWN_CLOSE" to listOf("close grip lat", "narrow grip lat", "close grip pulldown", "v bar pulldown"),
    "LAT_PULLDOWN" to listOf("pulldown", "pull down", "lat pull"),
    "SEATED_ROW" to listOf("seated row", "cable row", "seated cable", "narrow grip seated"),
    // "Rowing" on its own is a session — what Apple, Strava and Garmin call one, and Strong's
    // "Rowing (Machine)" — and it has to be claimed here, before the barbell row whose "row" opens it.
    "ROWING_MACHINE" to listOf("rowing", "row (machine", "rower", "ergometer", "concept2", "concept 2"),
    "TRICEPS_PUSHDOWN" to listOf("pushdown", "push down", "pressdown", "press down"),
    "LEG_PRESS" to listOf("leg press"),
    "LEG_EXTENSION" to listOf("leg extension", "quad extension", "knee extension"),
    "LEG_CURL" to listOf("leg curl", "hamstring curl", "knee curl"),
    "CALF_RAISE" to listOf("calf raise", "calf press", "calve raise"),
    "HIP_THRUST" to listOf("hip thrust", "glute bridge", "glute thrust"),
    "HIP_ABDUCTION" to listOf("abduction", "abductor"),
    "HIP_ADDUCTION" to listOf("adduction", "adductor"),
    "BACK_EXTENSION" to listOf("back extension", "hyperextension", "hyper extension", "good morning"),
    "LATERAL_RAISE" to listOf("lateral raise", "side raise", "lateral delt", "side lateral"),
    // Compounds before the movements whose names they contain.
    "LUNGES" to listOf("lunge", "split squat", "step up", "bulgarian"),
    "DEADLIFT" to listOf("deadlift", "dead lift", "romanian", "rdl", "sumo pull"),
    "SQUATS" to listOf("squat", "hack squat", "pistol"),
    "BENCH_PRESS" to listOf("bench press", "bench (barbell", "bench (dumbbell", "floor press"),
    "OVERHEAD_PRESS" to
        listOf("overhead press", "military press", "shoulder press", "ohp", "arnold press", "push press"),
    "BARBELL_ROW" to listOf("row", "pendlay", "t bar", "tbar"),
    "TRICEPS_BODY" to listOf("close grip bench", "diamond push", "bench dip", "skull crusher", "skullcrusher"),
    "TRICEPS_DUMBBELL" to listOf("tricep", "triceps", "french press", "kickback"),
    "BICEPS_CURL" to listOf("curl", "bicep", "biceps", "preacher", "concentration"),
    "PULLUPS" to listOf("pull up", "pullup", "chin up", "chinup", "muscle up"),
    "PUSHUPS" to listOf("push up", "pushup", "press up"),
    // Before the crunches, whose name it holds.
    "BICYCLE_CRUNCH" to listOf("bicycle crunch", "bicycle sit up", "bicycle ab"),
    // Before dips and the crunches: "dip station leg raise" holds both their names.
    "BAR_LEG_RAISE" to listOf(
        "captain",
        "vertical knee raise",
        "vertical leg raise",
        "dip station leg",
        "dip station knee",
        "parallel bar leg",
        "parallel bar knee",
    ),
    "DIPS" to listOf("dip"),
    "PLANK" to listOf("plank", "hollow hold", "dead bug"),
    "ABS" to listOf("crunch", "sit up", "situp", "ab wheel", "leg raise", "russian twist", "knee raise", "toes to bar"),
    "BURPEES" to listOf("burpee"),
    "JUMPING_JACKS" to listOf("jumping jack", "star jump"),
    "JUMP_ROPE" to listOf("jump rope", "skipping rope", "skip rope", "rope skipping"),
    // Cardio and the rest, where a whole session rather than an exercise is what was logged. The
    // session exports name one in a word — Strava's and Fitbit's "Run", Strava's "Ride" — and unclaimed
    // they would fall through to strength training, with its MET and outside the day's count of steps.
    "RUN" to listOf("run", "treadmill", "jog", "sprint", "5k", "10k"),
    "WALK" to listOf("walk", "rucking", "ruck"),
    "HIKING" to listOf("hik", "trek"),
    "STAIRS" to listOf("stair", "step mill", "stepmill"),
    "ELLIPTICAL" to listOf("elliptical", "cross trainer", "crosstrainer"),
    "CYCLING" to listOf("cycling", "bike", "biking", "ride", "spinning", "spin bike", "airdyne"),
    "SWIMMING" to listOf("swim", "freestyle", "backstroke", "breaststroke"),
    "YOGA" to listOf("yoga"),
    "PILATES" to listOf("pilates"),
    "STRETCHING" to listOf("stretch", "mobility", "foam roll"),
    "BOXING" to listOf("boxing", "kickbox", "punch", "heavy bag", "shadow box"),
    "BASKETBALL" to listOf("basketball"),
    "FOOTBALL" to listOf("football", "soccer"),
    "TENNIS" to listOf("tennis", "padel", "squash", "badminton"),
    "DANCING" to listOf("dancing", "zumba", "dance"),
    "HIIT" to listOf("hiit", "interval", "circuit", "tabata", "metcon", "amrap", "emom"),
    "CALISTHENICS" to listOf("calisthenic", "bodyweight", "body weight"),
)

// ─── from sets to journal rows ──────────────────────────────────────────────────────────────────

/** What every imported row's key begins with; the counterpart of [HEALTH_IMPORT_MARKER_PREFIX]. */
internal const val FILE_IMPORT_MARKER_PREFIX = "File import:"

/**
 * A minute and a half a set, where the file times nothing at all.
 *
 * FitNotes and wger record the day a set was done and no clock, and a workout with no duration
 * burns nothing: it would land in the journal as a row that says three sets of eight and nought
 * calories. Ninety seconds is this app's own default rest plus the half-minute a set of reps takes,
 * so the estimate is the app's own arithmetic rather than a new invention — and it is an estimate,
 * which is why any real figure in the file beats it.
 */
private const val ESTIMATED_SECONDS_PER_SET = 90

/** Below this a distance is rounding on a treadmill's odometer rather than a distance covered. */
private const val A_MEANINGFUL_DISTANCE_M = 100.0

/**
 * The journal rows one file becomes: one row per exercise per session, which is exactly how a
 * session logged inside this app is stored.
 *
 * The sets are encoded by [encodeToJson], the app's own encoder, rather than by anything written
 * here — a payload that merely reformats itself is a different row as far as the backup's identity
 * key is concerned, and an imported workout has to read back through the same editor as a logged
 * one. That is also why the loads are handed over in the profile's own unit and the factor with
 * them: it is the call the editor makes when it saves a session.
 *
 * [calories] is left at nought deliberately. Energy depends on what the person weighed at the time,
 * which is a walk over the measurement timeline; `recalculateJournal` does that walk once after the
 * whole file has landed rather than once per row.
 */
internal fun importedJournalEntries(
    file: WorkoutImportFile,
    zone: ZoneId,
    imperial: Boolean,
    weightTitle: String,
): List<JournalEntry> = importedWorkoutEntries(file, zone, imperial) +
    file.weights.map { it.journalEntry(file.app, zone, weightTitle) }

/**
 * One weighing, as the journal already stores one from the scales or from Health Connect: the kind
 * that carries a weight, and nothing else filled in. A row like this moves the timeline every later
 * workout's energy is worked out against, which is why the import recalculates afterwards.
 */
private fun ImportedWeight.journalEntry(
    app: WorkoutExportApp,
    zone: ZoneId,
    title: String,
) = JournalEntry(
    day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate().toString(),
    occurredAt = atMs,
    kind = KIND_MEASUREMENT,
    title = title,
    calories = 0.0,
    measurementWeightKg = kg,
    measurementSource = app.label,
    // By the instant where the file stated one and by the wall clock where the phone's zone made
    // it, so that either way another zone names the row the same ([importMarker]).
    healthConnectRecordKey = "$FILE_IMPORT_MARKER_PREFIX${app.label}:" +
        "${if (wallClock) wallClockStamp(atMs, zone) else atMs.toString()}:weight",
)

/**
 * A set as the journal can hold it.
 *
 * The file is whatever somebody picked, and one damaged cell must cost that cell and not the
 * import. `1e999` in a reps column is the largest an Int holds by the time it is a number, and the
 * session's totals overflow on it; a quote that never closes turns the rest of the file into one
 * exercise name megabytes long, which is then the row's title and its marker. So a figure past what
 * a set can be is read as not stated, and a name is cut to [IMPORT_TEXT_LIMIT].
 */
private fun ImportedSet.heldToBounds(): ImportedSet = copy(
    workoutTitle = workoutTitle.take(IMPORT_TEXT_LIMIT),
    exercise = exercise.take(IMPORT_TEXT_LIMIT),
    reps = reps?.takeIf { it.toDouble() in CREDIBLE_AMOUNT },
    loadKg = loadKg?.takeIf { it in CREDIBLE_AMOUNT },
    seconds = seconds?.takeIf { it in 1..LONGEST_SESSION_SECONDS },
    distanceM = distanceM?.takeIf { it in 0.0..FARTHEST_SESSION_M },
)

private fun importedWorkoutEntries(
    file: WorkoutImportFile,
    zone: ZoneId,
    imperial: Boolean,
): List<JournalEntry> {
    val sets = file.sets.map { it.heldToBounds() }
    val setsPerSession = sets.groupingBy(ImportedSet::startedAtMs).eachCount()
    val factor = if (imperial) POUNDS_IN_A_KILOGRAM else 1.0
    return sets
        .groupBy { it.startedAtMs to it.exercise }
        .map { (_, group) ->
            val first = group.first()
            val spec = ACTIVITY_CATALOG.firstOrNull { it.code == activityCodeFor(first.exercise) }
                ?: ACTIVITY_CATALOG.first { it.code == FALLBACK_ACTIVITY_CODE }
            val plan = group.map { it.plannedSet(factor) }
            // Warm-ups are in the payload — they were done, and the editor shows every set — but
            // not in the three flat columns, which are the numbers the last-session panel compares
            // one week against the next. A twenty-kilogram warm-up dragging the average down would
            // read as a week of lost strength.
            val working = group.filterNot(ImportedSet::warmup).map { it.plannedSet(factor) }
                .ifEmpty { plan }
                .aggregate()
            JournalEntry(
                day = Instant.ofEpochMilli(first.startedAtMs).atZone(zone).toLocalDate().toString(),
                occurredAt = first.startedAtMs,
                kind = KIND_ACTIVITY,
                title = first.exercise,
                subtitle = listOfNotNull(
                    first.workoutTitle.ifBlank { null },
                    // The one number of these files the journal has no column for. It goes in the
                    // subtitle.
                    group.sumOf { it.distanceM ?: 0.0 }
                        .takeIf { it >= A_MEANINGFUL_DISTANCE_M }
                        ?.let { "${(it / METRES_IN_A_KILOMETRE).pretty()} km" },
                ).joinToString(" · "),
                calories = 0.0,
                activityCode = spec.code,
                met = spec.met,
                durationMinutes = exerciseSeconds(group, setsPerSession[first.startedAtMs] ?: group.size) /
                    SECONDS_IN_A_MINUTE,
                sets = working.sets.takeIf { it > 0 },
                reps = working.reps.takeIf { it > 0 },
                loadKg = (working.load / factor).takeIf { working.load > 0.0 },
                measurementSource = file.app.label,
                measurementPayloadJson = plan.encodeToJson(loadUnitFactor = factor).ifBlank { null },
                healthConnectRecordKey = importMarker(file.app, first, zone),
            )
        }
}

/**
 * The name an imported row is filed under, and the same name on every phone that reads the file.
 *
 * The moment is written as the wall clock the file states, not as the instant the phone made of it:
 * these five loggers write local time with no offset, so the instant depends on the zone the phone
 * was in when it read the file, and the same export imported after a move to another zone would
 * carry every set under a new name and land the whole history a second time.
 */
internal fun importMarker(app: WorkoutExportApp, set: ImportedSet, zone: ZoneId): String =
    "$FILE_IMPORT_MARKER_PREFIX${app.label}:${wallClockStamp(set.startedAtMs, zone)}:${set.exercise}"

/** `2025-12-05T113700`: the wall clock in [zone], without the colons that separate a marker's parts. */
internal fun wallClockStamp(atMs: Long, zone: ZoneId): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), zone).format(WALL_CLOCK_STAMP)

private val WALL_CLOCK_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss")

/**
 * The same marker under the name the import gives it, for a row whose marker names the instant
 * rather than the wall clock: `File import:Hevy:1764934620000:Bench Press` becomes
 * `File import:Hevy:2025-12-05T113700:Bench Press` in the zone the row was read in — which is this
 * one, unless the phone has moved since. Null for a marker already in the wall-clock form, or not a
 * file import's at all.
 */
internal fun String.withWallClockStamp(zone: ZoneId): String? {
    val rest = removePrefix(FILE_IMPORT_MARKER_PREFIX)
    val app = rest.substringBefore(':')
    val afterApp = rest.substringAfter(':', "")
    val stamp = afterApp.substringBefore(':')
    val tail = afterApp.substringAfter(':', "")
    val filedUnderAnInstant = rest != this && stamp.isNotEmpty() && tail.isNotEmpty() && stamp.all(Char::isDigit)
    if (!filedUnderAnInstant) return null
    return "$FILE_IMPORT_MARKER_PREFIX$app:${wallClockStamp(stamp.toLong(), zone)}:$tail"
}

/**
 * The load is handed over in the unit the profile is showing and the factor goes with it, because
 * that is the pair [encodeToJson] expects; it divides back to kilograms, the unit every payload
 * holds. Full precision rather than the app's printed rounding: this string is never shown
 * to anybody, and a hundredth of a pound of drift per set is drift for nothing.
 */
private fun ImportedSet.plannedSet(factor: Double) = PlannedSet(
    reps = reps?.takeIf { it > 0 }?.toString().orEmpty(),
    load = loadKg?.takeIf { it > 0.0 }?.let { (it * factor).toString() }.orEmpty(),
    completed = true,
    actualSeconds = seconds ?: 0,
    actualRestSeconds = 0,
    rir = rir,
)

/**
 * How many seconds of the session this one exercise is credited with.
 *
 * The session's own length, split between its exercises in proportion to their sets, wherever the
 * file states one: the split between one exercise and the next is an estimate, but the day's total
 * energy comes out right, which is the figure the app actually shows. Failing that, the seconds the
 * sets themselves were held for — a plank and a row on the ergometer are timed and nothing else in
 * the row is. Failing both, [ESTIMATED_SECONDS_PER_SET].
 */
private fun exerciseSeconds(group: List<ImportedSet>, setsInSession: Int): Double {
    val session = group.first().sessionSeconds?.takeIf { it in 1..LONGEST_CREDIBLE_SESSION_SECONDS }
    val held = group.sumOf { it.seconds ?: 0 }
    return when {
        session != null -> session.toDouble() * group.size / setsInSession.coerceAtLeast(1)
        held > 0 -> held.toDouble()
        else -> (group.size * ESTIMATED_SECONDS_PER_SET).toDouble()
    }
}

/**
 * Longer than this and the session length is not believed.
 *
 * A Strong session whose owner never tapped Finish is exported with the time from its start until
 * now: real files carry `144h 3m` and `264h 1m`. Taken at face value, eleven days of bench press
 * would be tens of thousands of calories in one afternoon of the record. Six hours is past any
 * training session and short of every one of those, and a session refused here simply falls to the
 * estimate below it.
 */
private const val LONGEST_CREDIBLE_SESSION_SECONDS = 6 * 60 * 60

// ─── the import itself ──────────────────────────────────────────────────────────────────────────

/**
 * What one run of the import did, in the facts the screen reports.
 *
 * [app] is null when no format claimed the file, which is the one outcome that is not a count: the
 * person picked the wrong file, or exported it in a shape this build has never seen, and telling
 * them "0 workouts" would read as "your history is empty". [undated] is the rows left out because
 * no shape read their date, which is the other way a real history reads as an empty one.
 */
data class WorkoutImportOutcome(
    val app: WorkoutExportApp?,
    val added: Int,
    val alreadyHeld: Int,
    val undated: Int = 0,
) {
    val unreadable: Boolean get() = app == null
}

/**
 * Reads one exported file into the journal.
 *
 * Idempotent by design, because it is a button somebody will press twice: every row is filed under
 * a marker naming the session and the exercise, and a marker already in the journal — or one in the
 * tombstones, meaning the row was imported once and then deliberately deleted — is skipped rather
 * than written again. Nothing is updated in place: a row that is already here is left exactly as it
 * is, including any correction made to it by hand since. The one row that goes is the session Health
 * Connect brought from the same logger for a day the file states set by set.
 *
 * The energy of every imported workout is worked out once the rows are in, in one pass rather than
 * row by row, because it depends on what the person weighed at the time and that is a walk along the
 * whole measurement timeline. This is the same thing a restored backup does with the rows it brings
 * in, and it is done in the same transaction as the rows, so that no row is ever left unpriced.
 */
suspend fun StepsDb.importWorkoutExport(
    text: String,
    zone: ZoneId,
    imperial: Boolean,
    weightTitle: String,
): WorkoutImportOutcome {
    val file = readWorkoutExport(text, zone, imperial) ?: return WorkoutImportOutcome(null, 0, 0)
    val read = importedJournalEntries(file, zone, imperial, weightTitle)
    // A moment no history holds is a damaged cell, here as in a health export ([importableMoments]),
    // and the rows that go with it are counted beside the ones whose date no shape read.
    val moments = importableMoments(System.currentTimeMillis())
    val entries = read.filter { it.occurredAt in moments }
    val undated = file.undated + read.size - entries.size
    val outcome = withTransaction {
        val dao = dao()
        // The rows already here, under the names they were filed under and under the names the
        // import gives them ([withWallClockStamp]): a history whose markers name the instant is still
        // the same history.
        val filed = dao.fileImportMarkers(FILE_IMPORT_MARKER_PREFIX) +
            dao.allTombstones().map(JournalTombstone::recordKey)
        val held = filed.toSet() + filed.mapNotNull { it.withWallClockStamp(zone) }
        val fresh = entries.filterNot { it.healthConnectRecordKey in held }
        if (fresh.isNotEmpty()) dao.insertJournalEntries(fresh)
        // The session Health Connect brought from the logger's own app is the same training without
        // its sets, and gives way to them ([sessionsTheSetsReplace]). It leaves its tombstone, as a
        // row deleted by hand does: the record is still in Health Connect, and the exchange would
        // read it back.
        val brought = healthConnectMarkerOf(file.app.label)?.let { dao.rowsMarked(it) }.orEmpty()
        val replacedAt = System.currentTimeMillis()
        fresh.sessionsTheSetsReplace(brought).forEach { session ->
            dao.deleteJournalEntry(session.id)
            journalTombstoneKeys(session.id, session.healthConnectRecordKey)
                .forEach { dao.saveTombstone(JournalTombstone(it, replacedAt)) }
        }
        // Inside the transaction: otherwise rows written at nought kilocalories by a process ended
        // before the pass that prices them would stay at nought, and a second run, finding them all
        // here, would add nothing and so never run it. In here the pass leaves the alarms and widgets
        // to the commit.
        if (fresh.isNotEmpty()) recalculateJournal()
        WorkoutImportOutcome(file.app, fresh.size, alreadyHeld = entries.size - fresh.size, undated = undated)
    }
    if (outcome.added > 0) {
        refreshMedicineAfterWrite()
        refreshWidgetsAfterWrite()
    }
    return outcome
}
