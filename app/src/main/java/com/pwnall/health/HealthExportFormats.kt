package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.ZoneId

/*
 * Recognising whose export this is, and reading it.
 *
 * The shape is [readWorkoutExport]'s, widened for archives. A service is recognised by the columns
 * inside its files and never by their names, for the reason that reader already gives — a file
 * arrives called whatever the person renamed it to — with one deliberate exception: an entry inside
 * an archive was named by the service and not by anybody, so the name is evidence there even though
 * it is not evidence for a loose file. It is used only to decide what is worth *parsing*, never to
 * decide whose it is.
 *
 * Readers consume each table before the next archive entry is opened. Only the resulting records
 * and cross-table state survive the walk.
 */

/**
 * One file out of an export that a reader might want.
 *
 * Two shapes and no more: these twelve services write CSV or they write JSON. A [ExportDocument]
 * keeps its text rather than its parsed tree because most of an archive is not wanted — a Garmin
 * account export runs to hundreds of files — and a cheap look at the characters is what decides
 * whether the parse is worth doing at all.
 */
internal sealed interface ExportPart {
    val name: String
}

/** One parsed CSV out of an export: where it came from, its columns, and its rows. */
internal class ExportTable(
    override val name: String,
    val columns: Map<String, Int>,
    private val records: List<List<String>>,
) : ExportPart {
    /** The data rows, addressed by column name. */
    val rows: List<CsvRow> get() = records.map { CsvRow(columns, it) }

    /**
     * The data rows as bare cells.
     *
     * For the one export whose column *names* cannot be relied on: Strava translates its headers
     * into the account's language, so a reader that matched them would read a German export as an
     * empty history. See [stravaReader].
     */
    val cells: List<List<String>> get() = records

    /** Whether every one of these columns is present, which is how a reader claims a table. */
    fun has(vararg keys: String): Boolean = keys.all(columns::containsKey)

    /**
     * The name of the column at [index], for the files whose headings are translated but ordered.
     *
     * Withings is the caller. Its headings are in the account's language and its column order is
     * fixed, so the position says *which* figure this is — and the heading, which carries a unit in
     * brackets that no translator touches, says what unit it is in.
     */
    fun columnAt(index: Int): String? = columns.entries.firstOrNull { it.value == index }?.key
}

/** One JSON file out of an export, still as its characters. */
internal class ExportDocument(override val name: String, val text: String) : ExportPart {
    /** One parse behind both doors; null where the text is not JSON at all. */
    private val parsed: Any? by lazy { runCatching { JSONTokener(text).nextValue() }.getOrNull() }

    /** The parsed tree, or null where the file is JSON that is not an object — or is not JSON. */
    fun json(): JSONObject? = parsed as? JSONObject

    /**
     * The parsed tree where the file's root is an array, which most of Garmin's are.
     *
     * A bare array at the top level is not what a JSON file usually looks like and is what every
     * date-sharded Garmin file is, so both doors exist and a reader opens the one its service uses.
     */
    fun jsonArray(): JSONArray? = parsed as? JSONArray

    /** Whether the file is not JSON at all — a reader that claimed it by name or by a marker read nothing. */
    val unparsable: Boolean get() = parsed == null
}

/** Every object in an array, skipping whatever in it is not one. */
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)

/** A number that is present and not the nought these exports use to mean "not measured". */
internal fun JSONObject.positive(key: String): Double? = optDouble(key, 0.0).takeIf { it > 0.0 }

/**
 * The text under [key], or nothing where it is absent or JSON's `null`.
 *
 * Not [JSONObject.optString] on its own, which on a phone answers a `null` with the four letters:
 * Android's own org.json writes it out, and the library the tests run on does not, so no test could
 * see a Garmin session called `"name": null` titled "null", or a Fitbit food with no brand become
 * "null Porridge".
 */
internal fun JSONObject.optText(key: String): String = if (isNull(key)) "" else optString(key)

/** What every reader is told about the phone it is running on. */
internal class HealthReadContext(
    val zone: ZoneId,
    val instants: ExportInstants,
    /**
     * Whether the profile reads its figures in pounds and miles.
     *
     * The same last resort [unitLessFactor] is: three of these services write a body weight with no
     * unit anywhere in the file, and 85 is a plausible person in either unit. The card says which
     * way it will be read before the file is picked.
     */
    val imperial: Boolean,
) {
    val fitbitSteps = FitbitSteps(zone)
    val mfpWeights = mutableListOf<Pair<Long, Double>>()
    var mfpHasExerciseTable = false
    var mfpWeightUnit = if (imperial) "lb" else "kg"
}

/**
 * One service's reader: which of an archive's tables are its, and what it makes of them.
 *
 * [claims] is asked of each table on its own and must be specific enough that no other service's
 * file answers yes — the identity columns, exactly as [ExportFormat.identity] is. [read] receives
 * one entry at a time and uses [HealthReadContext] for state shared across files.
 */
internal class HealthExportReader(
    val app: HealthExportApp,
    val claims: (ExportPart) -> Boolean,
    val read: (List<ExportPart>, HealthReadContext) -> List<HealthRecord>,
)

/** The claimed parts that are tables, which is all most readers want. */
internal fun List<ExportPart>.tables(): List<ExportTable> = filterIsInstance<ExportTable>()

/**
 * Reads one picked file, whatever it turns out to be.
 *
 * Apple's is told apart by its own door: it is the one export that cannot be read into memory, so an
 * XML entry is recognised by streaming rather than by parsing and never reaches the table readers,
 * which would build a table out of half a gigabyte of XML to discover it is not a CSV.
 *
 * Where more than one service's reader claims tables — which happens with a folder somebody has
 * combined by hand — the one that claimed the most files wins, on the same reasoning the workout
 * reader gives for preferring the more specific match: it is the less accidental one.
 */
internal fun readHealthExport(
    open: () -> InputStream,
    fileName: String,
    zone: ZoneId,
    imperial: Boolean,
): HealthImportFile? = readHealthExports(open, fileName, zone, imperial).files.firstOrNull()

/**
 * Every service's export found in the file, the one that claimed the most tables first, and of them
 * only those that held anything — unless none did.
 *
 * An archive somebody combined by hand — a Takeout holding Fitbit's files beside the Google Fit
 * daily table — holds two services' histories, and importing only the one with more files would
 * drop the other's without a word. Each reader's rows carry its own name in their markers, so all
 * of them can land.
 */
internal fun readHealthExports(
    open: () -> InputStream,
    fileName: String,
    zone: ZoneId,
    imperial: Boolean,
): HealthExports {
    val context = HealthReadContext(zone, ExportInstants(zone), imperial)
    var apple: AppleHealthXml? = null
    val claimed = HashMap<HealthExportApp, ReadExport>()
    var skipped = 0
    // One walk recognises Apple's file and every other part: a walk of Apple's alone first would go
    // through every archive that is not Apple's twice, inflating each entry both times. An Apple
    // file that held records ends the walk and is the whole answer; one that held none is passed
    // over. An archive the walk gave up on is said to be one: the entries past the limit were never
    // opened, and without this the import would report the part it read as the whole.
    val whole = walkArchive(open, fileName) { entry ->
        if (entry.fileName.endsWith(".xml")) {
            apple = entry.appleHealthXml(zone)?.takeIf { it.records.isNotEmpty() }
        } else {
            entry.exportPart()?.let { part ->
                // An unclaimed part is dropped here and never held: an archive is far larger than the
                // handful of files inside it that anybody reads.
                HEALTH_EXPORT_READERS.firstOrNull { it.claims(part) }?.let { reader ->
                    val export = claimed.getOrPut(reader.app, ::ReadExport)
                    export.parts += 1
                    export.records += reader.read(listOf(part), context)
                    if (part is ExportDocument && part.unparsable) skipped += 1
                }
            }
        }
        if (entry.refused) skipped += 1
        apple == null
    }
    // A file cut off before its root closed is said to be one, as an archive the walk gave up on is.
    apple?.let {
        val file = HealthImportFile(HealthExportApp.APPLE_HEALTH, it.records)
        return HealthExports(listOf(file), skipped = 0, cutShort = !it.whole)
    }
    val found = claimed.entries
        .sortedByDescending { it.value.parts }
        .map { (app, export) ->
            val records = when (app) {
                HealthExportApp.FITBIT -> export.records.apply { addAll(context.fitbitSteps.totals()) }
                HealthExportApp.MYFITNESSPAL -> export.records.apply { addAll(context.myFitnessPalWeights()) }
                else -> export.records
            }
            HealthImportFile(app, records)
        }
    // A reader that claimed a file and found nothing in it is named only where no other found
    // anything: a Garmin archive's JSON with an `"error"` key in it is claimed by Fitbit's resting
    // pulse, and the report would read "Garmin Connect + Fitbit". Where nothing was found at all, the
    // service that claimed the most is the file's name, for "held nothing" to be said of it.
    val files = found.filter { it.records.isNotEmpty() }.ifEmpty { found.take(1) }
    // Read after the readers ran, as the count above is: it is their rows that were lost.
    return HealthExports(files, skipped, cutShort = !whole, undated = context.instants.unread)
}

/**
 * What a picked file held, and how much of it was never read. [skipped] is the entries past
 * [ENTRY_BYTE_LIMIT] and the documents a reader claimed that were not JSON; [cutShort] is an archive
 * the walk gave up on before its end ([walkArchive]), or an Apple file that stops before its own end
 * ([AppleHealthXml.whole]); [undated] is the rows whose date no shape read
 * ([ExportInstants.unread]). Nothing of the three is the usual case and the silent one.
 */
internal class HealthExports(
    val files: List<HealthImportFile>,
    val skipped: Int,
    val cutShort: Boolean = false,
    val undated: Int = 0,
)

private class ReadExport {
    var parts = 0
    val records = mutableListOf<HealthRecord>()
}

/** The export already in memory: the tests' way in. */
internal fun readHealthExport(bytes: ByteArray, fileName: String, zone: ZoneId, imperial: Boolean): HealthImportFile? =
    readHealthExport({ ByteArrayInputStream(bytes) }, fileName, zone, imperial)

/**
 * The entry as a table, or null when it is not one.
 *
 * The extension gate is the only place a file name decides anything, and it decides how hard to
 * work rather than what the file is: a Strava archive holds one `activities.csv` beside several
 * thousand `.gpx` and `.fit.gz` route files, and reading every one of them into memory to discover
 * it has no header row would be minutes of work for an answer already known. The archive's own
 * stream still inflates an entry on its way past it; what the gate saves is all that comes after.
 */
private fun ArchiveEntry.exportPart(): ExportPart? {
    val readable = READABLE_EXTENSIONS.any { fileName.endsWith(it) } &&
        !PER_SAMPLE_DUMPS.containsMatchIn(fileName)
    return readable.takeIf { it }?.let { text() }?.let { text ->
        when {
            fileName.endsWith(".tcx") -> ExportDocument(name, text.beforeTheTrack())
            DOCUMENT_EXTENSIONS.any { fileName.endsWith(it) } -> ExportDocument(name, text)
            else -> exportTable(text)
        }
    }
}

/**
 * A TCX file as far as its first `<Track>`, which is as far as its reader reads
 * ([nikeRunClubReader]).
 *
 * The reader needs only the header and lap summary, so the per-second samples can be discarded.
 */
internal fun String.beforeTheTrack(): String = substringBefore("<Track")

/** The text as a table, or null where it has no header row this could address a column by. */
private fun ArchiveEntry.exportTable(text: String): ExportTable? {
    val records = csvRecords(text, csvDelimiter(text)).dropPrefixLine()
    val columns = records.firstOrNull()
        .orEmpty()
        .mapIndexed { index, cell -> cell.headerKey() to index }
        .filter { it.first.isNotEmpty() }
        .toMap()
    return columns.takeIf { it.isNotEmpty() }?.let { ExportTable(name, it, records.drop(1)) }
}

private val READABLE_EXTENSIONS = listOf(".csv", ".tsv", ".txt", ".json", ".tcx")

/**
 * The readable files that are not tables.
 *
 * `.xml` is deliberately absent. The only XML any of these services exports is Apple's, which is
 * read by streaming above precisely because it does not fit in memory; letting it reach this door
 * would mean trying to hold half a gigabyte of it to discover that it is not a CSV.
 */
private val DOCUMENT_EXTENSIONS = listOf(".json", ".tcx")

/**
 * The entries that are a sensor's transcript rather than anybody's history.
 *
 * This is the one exclusion by name in the whole reader, and it is worth its keep. A Fitbit archive
 * writes a heart rate every few seconds and a calorie figure every minute of every day, asleep
 * included; the lifetime heart-rate files alone have been measured past two gigabytes, and the
 * per-minute calorie file is forty-three thousand rows for every thirty days. Every one of them is
 * also stated as a daily figure in a file a thousand times smaller, which is what is read instead —
 * so holding, decoding and parsing these would be minutes of work to arrive at the same numbers.
 *
 * Withings' thirty `raw_*` series are here for the same reason and one more: they are run-length
 * encoded into JSON arrays inside CSV cells, and the daily `aggregates_*` files beside them hold
 * the same walk already added up.
 *
 * The steps files are deliberately *not* excluded. They are per-minute too, but they are sparse —
 * a minute nobody moved in is simply absent — and they are the only place several of these services
 * state a step count at all.
 */
private val PER_SAMPLE_DUMPS = Regex(
    "^(heart_rate[-_]|calories[-_]|distance[-_]|altitude[-_]|minute spo2|" +
        "usersleepstages|raw_|swim_lengths|estimated_oxygen_variation)",
    RegexOption.IGNORE_CASE,
)

/**
 * Samsung Health's exports, and only theirs, begin with a line above the header.
 *
 * The line names the data type, the app version and the schema version —
 * `com.samsung.shealth.tracker.pedometer_day_summary,6305105,7` — and a reader that took the first
 * record as the header would find three columns with those names and never recognise the file.
 *
 * It is dropped by shape rather than by name: a first record with far fewer *cells* than the record
 * under it is a prefix and not a header, whoever wrote it. Cells and not separators, because a comma
 * inside a quoted value is not a column — a Strava row states its date as `"Dec 24, 2024, 1:57:27 AM"`
 * and its distance as `"1,234"`, which by raw commas makes that row three columns wider than the
 * header above it and would throw the header away.
 *
 * "Far fewer" rather than merely "fewer" for a second reason: these same Samsung files end every
 * data row with a trailing comma, so an ordinary header sits one cell short of its own rows and a
 * stricter rule would eat it. Three is comfortably past that and comfortably short of the ten-cell
 * gap a real prefix leaves; what the rule cannot do is spot a prefix above a table of three or four
 * columns, which is a file Samsung does not write.
 */
private fun List<List<String>>.dropPrefixLine(): List<List<String>> {
    if (size < 2) return this
    val above = this[0].size
    val header = this[1].size
    return if (header - above >= PREFIX_CELL_GAP && header >= PREFIXED_HEADER_COLUMNS) drop(1) else this
}

/** How many cells wider the real header must be than the line above it, to call that line a prefix. */
private const val PREFIX_CELL_GAP = 3

/** And how many cells the real header must carry at all. */
private const val PREFIXED_HEADER_COLUMNS = 4

// ─── Apple Health ───────────────────────────────────────────────────────────────────────────────

/**
 * Apple's archive, found by what is inside its XML rather than by what the file is called.
 *
 * The name cannot be trusted twice over. Apple localises it — `export.xml` in English,
 * `Exportieren.xml` in German — so matching the English spelling reads nothing on a German phone.
 * And `export_cda.xml` sits directly beside it in every archive, is comparably enormous, and is a
 * completely different schema; a reader that took the first `.xml` it found would spend minutes on
 * it and import nothing.
 *
 * So the root element decides. The first few kilobytes are looked at, the reader is wound back, and
 * a file whose root is `<HealthData` is streamed. That also skips the DOCTYPE, which is where the
 * declarations an entity-expansion attack would need are written — nothing here ever resolves one.
 * Null for an XML that is not Apple's.
 */
private fun ArchiveEntry.appleHealthXml(zone: ZoneId): AppleHealthXml? {
    val text = BufferedReader(reader(), APPLE_SNIFF_CHARS)
    text.mark(APPLE_SNIFF_CHARS)
    val opening = CharArray(APPLE_SNIFF_CHARS)
    val read = text.read(opening, 0, APPLE_SNIFF_CHARS)
    val looksLikeApple = read > 0 && String(opening, 0, read).contains(APPLE_ROOT)
    text.reset()
    return if (looksLikeApple) readAppleHealthXml(text, zone) else null
}

/**
 * How far into the file to look for the root element.
 *
 * Past the DOCTYPE, which is the whole reason this is not a handful of bytes: Apple writes the
 * entire internal subset — every element and attribute declaration — ahead of `<HealthData`, and it
 * has grown with each export version. A hundred kilobytes is several times the largest seen.
 */
private const val APPLE_SNIFF_CHARS = 128 * 1024
private const val APPLE_ROOT = "<HealthData"

// ─── the registry ───────────────────────────────────────────────────────────────────────────────

/**
 * Every export this build can read.
 *
 * Order matters only where two services' identity columns could both match one table, which the
 * columns are chosen to prevent; where it happens anyway, the earlier reader wins and the choice is
 * argued at the reader itself.
 */
internal val HEALTH_EXPORT_READERS: List<HealthExportReader> = listOf(
    myFitnessPalReader(),
    cronometerReader(),
    floReader(),
    stravaReader(),
    nikeRunClubReader(),
    garminReader(),
    fitbitReader(),
    ouraReader(),
    withingsReader(),
    samsungHealthReader(),
    googleHealthReader(),
)
