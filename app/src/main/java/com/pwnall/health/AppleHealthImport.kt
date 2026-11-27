package com.pwnall.health

import java.io.Reader
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Apple Health, which is the one of the twelve that has no other road in.
 *
 * Every other service on the list either publishes into Health Connect or could; Apple's Health app
 * exists on one platform and exports one file. Somebody moving from an iPhone to an Android phone
 * has `export.zip` and nothing else, and inside it is `apple_health_export/export.xml`.
 *
 * That file is the reason this is written the way it is. It is not a large XML file, it is an
 * enormous one: a watch writes a heart rate every few seconds and a step count every few minutes,
 * so five years of wearing one is routinely half a gigabyte of `<Record/>` elements and has been
 * seen past two. It cannot be read into a string, it cannot be handed to a DOM parser, and the
 * records inside it cannot all become journal rows — twenty million heart-rate samples are not a
 * history anybody wants in a day list.
 *
 * So the file is streamed once, and what comes out of it is a summary rather than a transcript:
 * days of steps, nights of sleep, sessions, weighings, and the readings that are taken a handful of
 * times a day rather than a handful of times a minute. Steps, resting heart rate, oxygen saturation
 * and sleep are the ones summarised rather than transcribed.
 */

/** How Apple writes an instant: local time, a space, and the offset it was recorded at. */
private val APPLE_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)

/**
 * One `<Record/>` or `<Workout/>`, as its attributes.
 *
 * A map and not a typed object because the two elements share almost nothing but their dates, and
 * because Apple adds attributes between iOS versions — `sourceVersion`, `device`, and in recent
 * exports a `WorkoutStatistics` child carrying figures that older exports put in attributes.
 */
internal typealias AppleElement = Map<String, String>

/**
 * Reads the elements of an Apple Health export without holding the file.
 *
 * A scanner rather than an XML parser, which wants a word of defence: `android.util.Xml` is not on
 * a unit test's classpath, and the parsing this needs is one shape — a start tag, its attributes,
 * and nothing about what is nested inside it. Apple writes `export.xml` from one generator, so the
 * shape is stable: attribute values are always double-quoted and the five XML entities are the only
 * escapes it emits.
 *
 * Only the tags in [wanted] are built into a map; every other tag's attributes are skipped without
 * being collected, which is what keeps a file of twenty million elements bounded. [visit] returns
 * false to stop the scan.
 *
 * Answers the name of the last element the file closed, which for a whole document is its root
 * and for one cut off on its way here is not ([AppleHealthXml.whole]).
 */
internal fun scanAppleHealth(
    reader: Reader,
    wanted: Set<String>,
    visit: (String, AppleElement) -> Boolean,
): String? = scanXml(reader, wanted) { tag, attributes, _ -> visit(tag, attributes) }

/**
 * The same walk, with the text inside an element as well as its attributes.
 *
 * Apple states everything in attributes; the TCX files Nike and Strava export state everything as
 * element text — `<TotalTimeSeconds>1395.313</TotalTimeSeconds>` — so one scanner has to offer both
 * or there would be two of them. The text handed over is the characters up to the next tag, which
 * for a leaf element is its value and for a branch is the whitespace before its first child.
 */
internal fun scanXml(
    reader: Reader,
    wanted: Set<String>,
    visit: (String, AppleElement, String) -> Boolean,
): String? {
    val scanner = AppleScanner(reader)
    while (true) {
        val tag = scanner.nextTag() ?: return scanner.closed
        if (tag in wanted) {
            val attributes = scanner.attributes()
            if (!visit(tag, attributes, scanner.text())) return scanner.closed
        } else {
            scanner.skipTag()
        }
    }
}

/**
 * The character-at-a-time walk underneath [scanAppleHealth].
 *
 * Buffered by the [Reader] it is given; reading one character at a time out of a
 * `BufferedReader` is a field access, not a system call.
 */
private class AppleScanner(private val reader: Reader) {
    private var pending = NOTHING

    /** The name in the last closing tag passed; at the end of a whole document, its root's. */
    var closed: String? = null
        private set

    private fun read(): Int {
        val held = pending
        if (held != NOTHING) {
            pending = NOTHING
            return held
        }
        return reader.read()
    }

    private fun back(c: Int) {
        pending = c
    }

    /**
     * The name of the next start tag, skipping text, comments, declarations and closing tags.
     *
     * A closing tag, a comment, a doctype or a processing instruction carries nothing this reads
     * but the closing tag's name ([closed]), and all of them end at the next `>` outside a quoted
     * value. That is also how Apple's DOCTYPE gets past: its internal subset is a run of
     * `<!ELEMENT …>` declarations, each of which is skipped in turn, and the `]` it ends with is text.
     */
    fun nextTag(): String? {
        var name: String? = null
        var ended = false
        while (name == null && !ended) {
            val opener = if (skipToTag()) read() else -1
            when {
                opener == -1 -> ended = true

                // A closing tag carries nothing this reads but its name, which says at the end of
                // the file whether the document was closed or cut off.
                opener == '/'.code -> {
                    closed = readName(read())
                    skipTag()
                }

                // A comment, a doctype or a processing instruction carries nothing at all: skip it,
                // and go round for the next one.
                opener in NOT_A_START_TAG -> skipTag()

                else -> name = readName(opener)
            }
        }
        return name
    }

    /**
     * Everything up to the end of the current tag, thrown away.
     *
     * Quotes are tracked because a real `device` attribute is one Objective-C description string
     * with `>` characters inside it, and a tag ended at the first of those would take the rest of
     * the attributes with it.
     */
    fun skipTag() {
        var quoted = false
        while (true) {
            val c = read()
            if (c == -1) return
            if (c == '"'.code) quoted = !quoted
            if (c == '>'.code && !quoted) return
        }
    }

    /** Forward to just past the next `<`; false at the end of the file. */
    private fun skipToTag(): Boolean {
        var c = read()
        while (c != -1 && c != '<'.code) c = read()
        return c != -1
    }

    /** The tag's name, given its first character, leaving the reader on whatever ended it. */
    private fun readName(first: Int): String? {
        val name = StringBuilder().append(first.toChar())
        while (true) {
            val c = read()
            if (c == -1) return null
            if (!c.isNameCharacter()) {
                back(c)
                return name.toString()
            }
            name.keep(c)
        }
    }

    /**
     * The characters between the end of this tag and the start of the next one.
     *
     * Empty on a self-closing element, and whitespace on one with children — a caller that wants a
     * number reads nothing out of either, which is the right answer for both.
     */
    fun text(): String {
        val found = StringBuilder()
        var c = read()
        while (c != -1 && c != '<'.code) {
            found.keep(c)
            c = read()
        }
        if (c != -1) back(c)
        return found.toString().trim().unescapeXml()
    }

    /**
     * The current tag's attributes.
     *
     * Names run to the `=`, values are quoted, and the escapes Apple emits are turned back into
     * their characters — an exercise called `Wife &amp; kids walk` is a real row, and so is a
     * `sourceName` whose space is written as `&#160;`.
     */
    fun attributes(): AppleElement {
        val found = HashMap<String, String>()
        while (true) {
            val name = readAttributeName() ?: return found
            val value = readAttributeValue() ?: return found
            if (found.size < SCANNED_ATTRIBUTE_LIMIT) found[name] = value
        }
    }

    /** The next attribute's name, or null where the tag ended instead. */
    private fun readAttributeName(): String? {
        val name = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1 || c == '>'.code) return null
            if (c == '='.code) return name.toString().takeIf(String::isNotEmpty)
            // Whitespace and the `/` of a self-closing tag separate one attribute from the next.
            if (c.toChar().isWhitespace() || c == '/'.code) name.setLength(0) else name.keep(c)
        }
    }

    /** The value after an `=`, from its opening quote to its closing one. */
    private fun readAttributeValue(): String? {
        var c = read()
        while (c != -1 && c != '"'.code && c != '\''.code) c = read()
        if (c == -1) return null
        val quote = c
        val value = StringBuilder()
        while (true) {
            c = read()
            if (c == -1 || c == quote) return value.toString().unescapeXml()
            value.keep(c)
        }
    }
}

/**
 * [c] appended, until there are [SCANNED_TEXT_LIMIT] characters: what is past that is read and not
 * kept.
 *
 * The file is whatever somebody picked, and a name, a value or a text that never ends — a quote that
 * never closes, written on purpose or cut off — would be gathered until the heap ran out. Nothing Apple
 * or a TCX file writes comes near the limit: the longest real value is a `device` description of a
 * few hundred characters.
 */
private fun StringBuilder.keep(c: Int) {
    if (length < SCANNED_TEXT_LIMIT) append(c.toChar())
}

internal const val SCANNED_TEXT_LIMIT = 64 * 1024

/** Apple's widest element carries about a dozen attributes; a tag with thousands is not one of theirs. */
private const val SCANNED_ATTRIBUTE_LIMIT = 64

/** The characters a tag or attribute name is spelled out of. */
private fun Int.isNameCharacter(): Boolean =
    toChar().isLetterOrDigit() || this == '_'.code || this == ':'.code || this == '-'.code

private const val NOTHING = -2

/** The character after a `<` on everything that is not the start of an element. */
private val NOT_A_START_TAG = setOf('/'.code, '!'.code, '?'.code)

/**
 * The escapes an attribute value can carry.
 *
 * The five named entities, and the numeric references Apple emits for anything outside plain ASCII
 * — an export carries `sourceName="Apple&#160;Watch"`, where the space between the words is
 * a non-breaking one written as a number. `&amp;` is expanded last so that an `&amp;lt;` in the
 * source, which is the way a file says the four letters `&lt;`, stays those four letters.
 *
 * There is no general entity expansion here and cannot be: nothing declared in the document's own
 * DOCTYPE is ever looked up, so the whole class of expansion attacks the DOCTYPE enables has no
 * road in. That is not a precaution taken; it is what a scanner that does not resolve entities is.
 */
private fun String.unescapeXml(): String =
    if ('&' !in this) {
        this
    } else {
        NUMERIC_ENTITY.replace(this) { found ->
            val digits = found.groupValues[2]
            val code = if (found.groupValues[1].isEmpty()) {
                digits.toIntOrNull()
            } else {
                digits.toIntOrNull(HEXADECIMAL)
            }
            code?.takeIf { it in 1..Character.MAX_CODE_POINT }
                ?.let { String(Character.toChars(it)) }
                ?: found.value
        }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }

private val NUMERIC_ENTITY = Regex("&#(x?)([0-9A-Fa-f]+);")
private const val HEXADECIMAL = 16

// ─── what is taken out of the file ──────────────────────────────────────────────────────────────

/**
 * The HealthKit identifiers this reads, and what each becomes here.
 *
 * Everything absent from this table is skipped on purpose, and the two reasons are worth separating.
 * Some of it the journal has no column for — a VO2 max, an audio-exposure level, a walking
 * asymmetry, an ECG. The rest is a sampling rate rather than a history: a watch writes a heart rate
 * every few seconds, and importing twenty million of them would produce a journal nobody could
 * scroll and a database several times the size of the app. For those, the daily figure Apple itself
 * derives is taken instead — [APPLE_RESTING_HEART_RATE] is one number a day and is the one the
 * vitals card would draw anyway.
 */
internal const val APPLE_STEPS = "HKQuantityTypeIdentifierStepCount"
internal const val APPLE_BODY_MASS = "HKQuantityTypeIdentifierBodyMass"
internal const val APPLE_BODY_FAT = "HKQuantityTypeIdentifierBodyFatPercentage"
internal const val APPLE_LEAN_MASS = "HKQuantityTypeIdentifierLeanBodyMass"
internal const val APPLE_RESTING_HEART_RATE = "HKQuantityTypeIdentifierRestingHeartRate"
internal const val APPLE_SYSTOLIC = "HKQuantityTypeIdentifierBloodPressureSystolic"
internal const val APPLE_DIASTOLIC = "HKQuantityTypeIdentifierBloodPressureDiastolic"
internal const val APPLE_OXYGEN = "HKQuantityTypeIdentifierOxygenSaturation"
internal const val APPLE_TEMPERATURE = "HKQuantityTypeIdentifierBodyTemperature"
internal const val APPLE_WATER = "HKQuantityTypeIdentifierDietaryWater"
internal const val APPLE_CAFFEINE = "HKQuantityTypeIdentifierDietaryCaffeine"
internal const val APPLE_SLEEP = "HKCategoryTypeIdentifierSleepAnalysis"
internal const val APPLE_MENSTRUAL = "HKCategoryTypeIdentifierMenstrualFlow"

/** An Apple stamp as milliseconds; the offset is in the string, so nothing is assumed about zones. */
internal fun appleInstantMs(text: String?): Long? = text
    ?.takeIf { it.isNotBlank() }
    ?.let { runCatching { OffsetDateTime.parse(it, APPLE_STAMP).toInstant().toEpochMilli() }.getOrNull() }

/**
 * Reads one `export.xml` into the handful of facts this app can hold.
 *
 * Two passes' worth of work in one pass over the file: the aggregating records — steps, sleep, water,
 * caffeine and the oxygen saturation — accumulate into small maps as they go by, and the sparse ones
 * become records immediately. Nothing keeps more than a day's worth of anything, so the memory this
 * uses is a function of how long the history is and not of how densely it was sampled.
 */
@Suppress("CyclomaticComplexMethod")
internal fun readAppleHealthXml(reader: Reader, zone: ZoneId): AppleHealthXml {
    val steps = AppleStepDays()
    val sleep = AppleNights()
    val drinks = AppleDrinkDays(zone)
    val pressures = ApplePressures()
    val periods = mutableSetOf<LocalDate>()
    val saturation = AppleSaturationDays(zone)
    val records = mutableListOf<HealthRecord>()
    val workouts = AppleWorkouts()
    // The latest moment any record of the file states, which is as far as the export knows anything.
    var latest: Long? = null
    val closed = scanAppleHealth(reader, setOf("Record", "Workout", "WorkoutStatistics")) { tag, element ->
        if (tag == "Workout" || tag == "WorkoutStatistics") {
            workouts.add(tag, element)
        } else {
            val at = appleInstantMs(element["startDate"]) ?: return@scanAppleHealth true
            latest = maxOf(latest ?: at, at)
            val value = element["value"]
            val unit = element["unit"].orEmpty()
            when (element["type"]) {
                APPLE_STEPS -> steps.add(element, at, zone)

                APPLE_SLEEP -> sleep.add(element)

                APPLE_MENSTRUAL -> element.menstrualDay(at, zone)?.let(periods::add)

                APPLE_WATER -> drinks.add(KIND_WATER, element, at, value.millilitres(unit))

                APPLE_CAFFEINE -> drinks.add(KIND_CAFFEINE, element, at, value.milligrams(unit))

                APPLE_SYSTOLIC -> pressures.systolic(element, at)

                APPLE_DIASTOLIC -> pressures.diastolic(element, at)

                APPLE_BODY_MASS -> element.appleWeight(at)?.let(records::add)

                APPLE_RESTING_HEART_RATE ->
                    value?.toDoubleOrNull()?.let { records += VitalRecord(at, Vital.RESTING_HEART_RATE, it) }

                APPLE_OXYGEN -> saturation.add(at, value.asPercent())

                APPLE_TEMPERATURE -> value?.toDoubleOrNull()?.asCelsius(unit)
                    ?.let { records += VitalRecord(at, Vital.BODY_TEMPERATURE, it) }

                APPLE_BODY_FAT ->
                    value.asPercent()?.let { records += VitalRecord(at, Vital.BODY_FAT, it) }

                APPLE_LEAN_MASS -> Unit

                else -> Unit
            }
        }
        true
    }
    return AppleHealthXml(
        records = records + workouts.records() + steps.records() + sleep.records() + drinks.records() +
            pressures.records() + saturation.records() + periodRecords(periods, latest?.localDate(zone)),
        whole = closed == APPLE_ROOT_ELEMENT,
    )
}

/**
 * What one `export.xml` held, and whether it was all there.
 *
 * [whole] is false for a file that ends before its root element closes: a loose `export.xml` whose
 * copy stopped on its way to the phone — it is hundreds of megabytes — reads up to there exactly
 * like a history that ends early, and would be imported as one without a word ([HealthExports.cutShort]).
 */
internal class AppleHealthXml(val records: List<HealthRecord>, val whole: Boolean)

private const val APPLE_ROOT_ELEMENT = "HealthData"

/**
 * The day's steps, counted per source and then per day.
 *
 * Per source first because an iPhone in a pocket and a Watch on the wrist both write step records
 * all day, and Apple exports both streams whole. Added together, a person who wore both would
 * import roughly twice the steps they took. The day's answer is therefore the highest single
 * source's — the same arithmetic [effectiveStepTotal] does with a phone and a wearable, for the
 * same reason.
 */
private class AppleStepDays {
    private val perSource = HashMap<LocalDate, HashMap<String, Long>>()

    fun add(element: AppleElement, at: Long, zone: ZoneId) {
        val count = element["value"]?.toDoubleOrNull()?.toLong() ?: return
        if (count <= 0) return
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val source = element["sourceName"].orEmpty()
        val sources = perSource.getOrPut(day) { HashMap() }
        sources[source] = (sources[source] ?: 0L) + count
    }

    fun records(): List<HealthRecord> = perSource.map { (day, sources) ->
        StepDayRecord(day, sources.values.max())
    }
}

/**
 * The night's fragments, joined back into nights.
 *
 * Apple writes sleep as a stage at a time — core, deep, REM, a few minutes awake, core again — so
 * one night is dozens of records and none of them is the night. Fragments closer together than
 * [APPLE_SLEEP_GAP_MS] are one night; a longer gap starts another, which is what separates a night
 * from the nap after lunch.
 *
 * `InBed` is dropped where an asleep night overlaps it: an iPhone that recorded time in bed
 * alongside a watch that recorded sleep would otherwise give one night twice, once bracketed by the
 * bedtime and once by the sleep. Where it is all there is, it is the night — an older iPhone with no
 * watch recorded nothing else, and the years before the watch arrived are nights too.
 */
private class AppleNights {
    private val asleep = mutableListOf<LongRange>()
    private val inBed = mutableListOf<LongRange>()

    fun add(element: AppleElement) {
        val start = appleInstantMs(element["startDate"]) ?: return
        val end = appleInstantMs(element["endDate"]) ?: return
        if (end <= start) return
        val value = element["value"].orEmpty()
        when {
            value.contains("Asleep", ignoreCase = true) -> asleep += start..end

            value.contains("InBed", ignoreCase = true) -> inBed += start..end

            // "Awake" is time inside a night, not a night; it is neither counted nor allowed to
            // start one, and the gap rule below closes over it.
            else -> Unit
        }
    }

    fun records(): List<HealthRecord> {
        val slept = joined(asleep)
        val bedOnly = joined(inBed).filterNot { bed -> slept.any { it.first <= bed.last && bed.first <= it.last } }
        return (slept + bedOnly).sortedBy(LongRange::first).map { SleepRecord(it.first, it.last) }
    }

    private fun joined(fragments: List<LongRange>): List<LongRange> {
        if (fragments.isEmpty()) return emptyList()
        val sorted = fragments.sortedBy(LongRange::first)
        val nights = mutableListOf<LongRange>()
        var open = sorted.first()
        sorted.drop(1).forEach { next ->
            open = if (next.first - open.last <= APPLE_SLEEP_GAP_MS) {
                open.first..maxOf(open.last, next.last)
            } else {
                nights += open
                next
            }
        }
        nights += open
        return nights.filter { it.last - it.first >= SHORTEST_NIGHT_MS }
    }
}

/**
 * How far apart two sleep fragments may sit and still be one night.
 *
 * An hour. A watch records a trip to the bathroom as a gap of a few minutes, and a person who reads
 * for half an hour in the middle of the night has one broken night rather than two. An hour is also
 * comfortably shorter than the gap between a night and the nap the next afternoon, which is the
 * distinction the rule exists to make.
 */
private const val APPLE_SLEEP_GAP_MS = 60L * 60 * 1000

/** Below this a "night" is a doze on the sofa the watch noticed, and not a night's sleep. */
private const val SHORTEST_NIGHT_MS = 30L * 60 * 1000

/**
 * A blood pressure, which Apple stores as two records that share nothing but their instant.
 *
 * There is a correlation identifier in HealthKit itself, but it is not written into the export, so
 * the instant is what pairs them — and it does so exactly, because both halves of one cuff reading
 * are written with the same `startDate` by construction.
 */
private class ApplePressures {
    private val systolic = HashMap<Pair<String, Long>, Double>()
    private val diastolic = HashMap<Pair<String, Long>, Double>()

    fun systolic(element: AppleElement, at: Long) {
        element.reading()?.let { systolic[element.source() to at] = it }
    }

    fun diastolic(element: AppleElement, at: Long) {
        element.reading()?.let { diastolic[element.source() to at] = it }
    }

    fun records(): List<HealthRecord> = systolic.mapNotNull { (key, high) ->
        diastolic[key]?.let { low -> VitalRecord(key.second, Vital.BLOOD_PRESSURE, high, low) }
    }

    private fun AppleElement.reading(): Double? = this["value"]?.toDoubleOrNull()
}

/**
 * The cuff a reading came from, with the space Apple writes as a non-breaking one put back.
 *
 * An export carries `sourceName="Apple&#160;Watch"`, and the character is U+00A0 rather
 * than a space. It is only ever compared with itself here, so the normalisation is belt and braces
 * — but a pair of readings whose two halves disagreed about one invisible character would be a
 * blood pressure silently dropped, and that is the failure this is cheap insurance against.
 */
private fun AppleElement.source(): String = this["sourceName"].orEmpty().replace('\u00a0', ' ')

/**
 * Water and caffeine, totalled by day.
 *
 * A sip at a time is how a hydration app writes them and is not how this app holds them: fifty rows
 * of 50 ml would bury a day list. The day's total is one row, filed at the first of the day's
 * entries so it sorts where the drinking started.
 *
 * And a drink is counted once however often the file states it. A food logged with its water and
 * caffeine is a `<Correlation>` holding those records, and this scanner reads every `<Record>`
 * whatever it is nested in; where the export states them again on their own at the top of the file,
 * as it is reported to, adding them up as they came would count that water twice. Two records that agree
 * on every attribute, down to the moment each was created, are one record; two glasses drunk are not.
 */
private class AppleDrinkDays(private val zone: ZoneId) {
    private val totals = HashMap<Pair<String, LocalDate>, Double>()
    private val firstAt = HashMap<Pair<String, LocalDate>, Long>()
    private val seen = HashSet<String>()

    fun add(kind: String, element: AppleElement, at: Long, amount: Double?) {
        if (amount == null || amount <= 0.0) return
        if (!seen.add(DRINK_IDENTITY.joinToString("|") { element[it].orEmpty() })) return
        val key = kind to Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        totals[key] = (totals[key] ?: 0.0) + amount
        firstAt[key] = minOf(firstAt[key] ?: Long.MAX_VALUE, at)
    }

    fun records(): List<HealthRecord> = totals.map { (key, amount) ->
        DrinkRecord(firstAt.getValue(key), key.first, amount)
    }
}

/** What makes one drink's record the same record a second time. */
private val DRINK_IDENTITY = listOf("type", "sourceName", "creationDate", "startDate", "endDate", "value", "unit")

/**
 * The period days Apple recorded, as the starts and ends the cycle screen pairs.
 *
 * Apple writes one record per day of flow and marks the first with `HKMenstrualCycleStart`, but that
 * metadata attribute is not written into every export — older iOS versions omit it — so the run is
 * found from the days themselves: a day with no flow the day before it is a start, and a day with
 * none after it is an end. That reads a gap of one day as two cycles, which is what a gap of one day
 * in the source data actually says.
 *
 * Except at the end of the file. An export taken on the second day of a period holds two days of
 * flow and nothing after them, and that is the file ending, not the period: read as an end, it
 * would stay in the journal when next month's export brought the real one, and the cycle screen pairs
 * a start with the first end after it. So a run is given its end only once a whole day has passed
 * after it with nothing logged — [lastDay] is the day of the file's latest record, and a run that
 * reaches it or the day before is still open as far as this file can say.
 */
internal fun periodRecords(days: Set<LocalDate>, lastDay: LocalDate?): List<HealthRecord> = days.flatMap { day ->
    val over = lastDay == null || day.plusDays(1) < lastDay
    listOfNotNull(
        PeriodRecord(day, start = true).takeIf { day.minusDays(1) !in days },
        PeriodRecord(day, start = false).takeIf { over && day.plusDays(1) !in days },
    )
}

/**
 * The oxygen saturation, as one figure a day.
 *
 * A watch samples it a few dozen times a day, most of them asleep, and a row for each is the raw
 * stream this import says it leaves behind: three years of wearing one comes to tens of thousands of
 * rows. The day's average at the day's midday is what Fitbit's own daily file states and how
 * [fitbitReader] files it, so the two wearables' histories read alike.
 */
private class AppleSaturationDays(private val zone: ZoneId) {
    private val totals = HashMap<LocalDate, Double>()
    private val samples = HashMap<LocalDate, Int>()

    fun add(at: Long, percent: Double?) {
        // Only what a row would keep: an artefact averaged in moves a day it has no place in.
        if (percent == null || percent !in Vital.OXYGEN_SATURATION.valid) return
        val day = at.localDate(zone)
        totals[day] = (totals[day] ?: 0.0) + percent
        samples[day] = (samples[day] ?: 0) + 1
    }

    fun records(): List<HealthRecord> = totals.map { (day, total) ->
        val noon = day.atTime(NOON_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        VitalRecord(noon, Vital.OXYGEN_SATURATION, total / samples.getValue(day), wallClock = true)
    }
}

/** A flow record that says there was no flow is not a day of the period. */
private fun AppleElement.menstrualDay(at: Long, zone: ZoneId): LocalDate? =
    Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        .takeIf { this["value"]?.contains("None", ignoreCase = true) == false }

private fun AppleElement.appleWeight(at: Long): WeightRecord? =
    this["value"]?.toDoubleOrNull()?.asKilograms(this["unit"].orEmpty())?.let { WeightRecord(at, it) }

/**
 * The sessions, and the figures modern exports moved out from under them.
 *
 * `totalDistance` and `totalEnergyBurned` were attributes of `<Workout>` until iOS 18, which moved
 * the real values into `<WorkoutStatistics>` children and left the attributes absent or zero. A
 * reader that takes only the attributes therefore imports every recent run as nought kilometres and
 * nought calories — which is not a rounding error but a training history with its numbers removed.
 *
 * The children have no back-reference to their parent, so the association is positional: statistics
 * appear inside the workout they belong to, so they belong to whichever workout is still open. That
 * is the whole of the state this holds, and it is why the scan asks for both tags at once.
 */
private class AppleWorkouts {
    private val finished = mutableListOf<WorkoutRecord>()
    private var open: WorkoutRecord? = null

    fun add(tag: String, element: AppleElement) {
        if (tag == "Workout") {
            close()
            open = element.appleWorkout()
        } else {
            open = open?.withStatistic(element)
        }
    }

    fun records(): List<HealthRecord> {
        close()
        return finished
    }

    private fun close() {
        open?.let(finished::add)
        open = null
    }
}

/**
 * One statistic folded into the session it was recorded inside.
 *
 * A figure already stated as an attribute wins over the child, on the reasoning that an older export
 * states it in one place only and a newer one states it in the other; where both are present they
 * agree. A zero attribute is treated as absent, because that is exactly what a modern export writes
 * beside the child that holds the real number.
 *
 * The unit strings are the trap here. Inside `<WorkoutStatistics>` HealthKit spells kilocalories
 * `Cal` — the large calorie, the same magnitude — where the attribute beside it spells them `kcal`.
 * A reader matching only `kcal` reads every modern workout as burning nothing.
 */
private fun WorkoutRecord.withStatistic(element: AppleElement): WorkoutRecord {
    val unit = element["unit"].orEmpty()
    val sum = element["sum"]?.toDoubleOrNull()
    return when {
        element["type"] == APPLE_WORKOUT_ENERGY && sum != null ->
            copy(calories = calories ?: sum.kilocaloriesFrom(unit))

        element["type"] in APPLE_WORKOUT_DISTANCES && sum != null ->
            copy(distanceM = distanceM ?: sum.metresFrom(unit))

        element["type"] == APPLE_WORKOUT_HEART_RATE ->
            copy(heartRateBpm = heartRateBpm ?: element["average"]?.toDoubleOrNull()?.toInt())

        else -> this
    }
}

private const val APPLE_WORKOUT_ENERGY = "HKQuantityTypeIdentifierActiveEnergyBurned"
private const val APPLE_WORKOUT_HEART_RATE = "HKQuantityTypeIdentifierHeartRate"

/** Apple counts a session's distance under whichever of these the activity was. */
private val APPLE_WORKOUT_DISTANCES = setOf(
    "HKQuantityTypeIdentifierDistanceWalkingRunning",
    "HKQuantityTypeIdentifierDistanceCycling",
    "HKQuantityTypeIdentifierDistanceSwimming",
    "HKQuantityTypeIdentifierDistanceWheelchair",
    "HKQuantityTypeIdentifierDistanceDownhillSnowSports",
)

/**
 * One session's own attributes.
 *
 * `duration` is a count of minutes with full double precision — `31.73680251737436` is a real value
 * — and it is not the same as the end minus the start, because a paused session excludes the pause.
 * The duration is what the energy estimate should rest on, so it is what is taken.
 */
private fun AppleElement.appleWorkout(): WorkoutRecord? {
    val start = appleInstantMs(this["startDate"]) ?: return null
    val name = this["workoutActivityType"].orEmpty().removePrefix("HKWorkoutActivityType")
    if (name.isBlank()) return null
    val stated = this["duration"]?.toDoubleOrNull()
    val seconds = when (this["durationUnit"]) {
        "s", "sec" -> stated

        "hr" -> stated?.times(MINUTES_IN_AN_HOUR * SECONDS_IN_A_MINUTE)

        // Apple's own default, and what every export seen writes.
        else -> stated?.times(SECONDS_IN_A_MINUTE)
    }
    return WorkoutRecord(
        startMs = start,
        // "HKWorkoutActivityTypeHighIntensityIntervalTraining" is not a title; the words are split
        // apart so the row reads as one and so [activityCodeFor] can match on them.
        name = name.splitCamelCase(),
        seconds = seconds?.toInt(),
        // Nought is what a modern export writes where the real figure has moved into a child, so it
        // is read as "not stated here" rather than as a workout that covered no ground.
        distanceM = this["totalDistance"]?.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?.metresFrom(this["totalDistanceUnit"].orEmpty()),
        calories = this["totalEnergyBurned"]?.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?.kilocaloriesFrom(this["totalEnergyBurnedUnit"].orEmpty()),
    )
}

/** `HighIntensityIntervalTraining` as `High Intensity Interval Training`. */
internal fun String.splitCamelCase(): String =
    replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
