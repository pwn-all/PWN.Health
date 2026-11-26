package com.pwnall.health

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A pain, as the person painted it on the body: where, how it felt, how much, since when.
 *
 * Its own kind, and one that stays on the phone. Health Connect has no record for pain, and nothing
 * here is written into a column any export reads — no sleep times, no heart rate, no weight — so
 * a row can never be published by accident as some other measurement.
 *
 * The row is stamped with the moment of the report, never with the onset. A pain that "started
 * five days ago" is told today; filed five days back it would mark an old day as kept by hand on
 * the doctor's sheet and turn a missing drink on that day into "none". The onset lives in the
 * payload instead.
 */
const val KIND_PAIN = "PAIN"

/** The glyph a pain row, door and filter wear where no drawing is shown. */
internal val PainIcon = Icons.Outlined.Healing

/**
 * What it feels like, in the app's own six words.
 *
 * Informed by the four groups the SF-MPQ-2 sorts pain words into — continuous, intermittent,
 * neuropathic, and the tension quality headache classification calls pressing — but never its
 * items, which are licensed. No affective words: how the day felt is the mood scale's question.
 */
enum class PainType(val labelRes: Int, val shortRes: Int) {
    ACHING(R.string.pain_type_aching, R.string.pain_type_aching_short),
    STIFF(R.string.pain_type_stiff, R.string.pain_type_stiff_short),
    THROBBING(R.string.pain_type_throbbing, R.string.pain_type_throbbing_short),
    SHARP(R.string.pain_type_sharp, R.string.pain_type_sharp_short),
    BURNING(R.string.pain_type_burning, R.string.pain_type_burning_short),
    CRAMPING(R.string.pain_type_cramping, R.string.pain_type_cramping_short),
}

/** How many words one entry takes: two say more than one, and three start to say nothing. */
const val PAIN_TYPES_MAX = 2

/**
 * Whether the word [name] can be ticked while [types] are: a ticked one can always be taken away,
 * and another only while fewer than [PAIN_TYPES_MAX] are ticked — at two, the other four are off.
 */
fun painTypeOffered(types: List<String>, name: String): Boolean = name in types || types.size < PAIN_TYPES_MAX

/**
 * Whether opening and closing the list keeps the words as they are: only while [types] are carried
 * from the same pain and nothing has been done with them yet — not [touched], and the question not
 * answered ([done]). Once the words are taken, a correction opened and closed again is no fresh
 * answer, and once touched, the clock already runs from the last change.
 */
fun painTypesKeepOffered(types: List<String>, touched: Boolean, done: Set<PainStep>): Boolean =
    types.isNotEmpty() && !touched && PainStep.TYPES !in done

/**
 * When it started, as far as a person can say without a clock: now, today, yesterday, two or three
 * days ago, about a week, about a month, or longer than that — the words people reckon a start in.
 * The names are what the payload stores, so they never change with the words shown for them.
 *
 * [offered] is false for two answers kept only for the entries that hold them:
 * "4 days to 3 months ago" ([WEEK]) and "more than 3 months ago" ([MONTHS_3_PLUS]). They are read,
 * shown and counted as long-standing for those entries, and never offered for a new one.
 *
 * [longStanding] marks a start too far back to place a day against: "about a month" and longer, as
 * either of those two answers, which reach as far. A pain told with one of them did not begin the day
 * it was told, and no workout before it is looked for.
 *
 * [sinceRes] is the answer as the history prints it, counted back from the entry rather than from
 * today — "started the day before" — since "yesterday" read a month later is a month wrong.
 */
enum class PainOnset(
    val labelRes: Int,
    val sinceRes: Int,
    val offered: Boolean = true,
    val longStanding: Boolean = false,
) {
    JUST_NOW(R.string.pain_onset_just_now, R.string.pain_history_onset_just_now),
    EARLIER_TODAY(R.string.pain_onset_earlier_today, R.string.pain_history_onset_earlier_today),
    YESTERDAY(R.string.pain_onset_yesterday, R.string.pain_history_onset_yesterday),
    DAYS_2_3(R.string.pain_onset_days_2_3, R.string.pain_history_onset_days_2_3),
    ABOUT_A_WEEK(R.string.pain_onset_about_week, R.string.pain_history_onset_about_week),
    ABOUT_A_MONTH(R.string.pain_onset_about_month, R.string.pain_history_onset_about_month, longStanding = true),
    MORE_THAN_A_MONTH(
        R.string.pain_onset_more_than_month,
        R.string.pain_history_onset_more_than_month,
        longStanding = true,
    ),
    WEEK(R.string.pain_onset_week, R.string.pain_history_onset_week, offered = false, longStanding = true),
    MONTHS_3_PLUS(
        R.string.pain_onset_months_3,
        R.string.pain_history_onset_months_3,
        offered = false,
        longStanding = true,
    ),
}

/**
 * The days back "about a week ago" covers, counted from the report's day: the fourth to the tenth,
 * a week either side by three days — the fourth is the first day "2–3 days ago" leaves, so the two
 * answers neither overlap nor leave a gap between them.
 */
internal const val PAIN_ABOUT_A_WEEK_NEAREST = 4L

/** The farthest back an onset answer places a start at all: the last day of "about a week ago". */
const val PAIN_ONSET_REACH_DAYS = 10L

/**
 * The answers the start question offers, in order, and the one [chosen] for an entry when it is not
 * offered — shown at the end of the list, so the entry opened again reads as saved.
 */
fun painOnsetChoices(chosen: PainOnset?): List<PainOnset> =
    PainOnset.entries.filter { it.offered || it == chosen }

/**
 * When it was first noticed — what the person noticed, never a cause. "After lifting" says when it
 * began to hurt; whether the lifting did it is not something one entry can know.
 *
 * [shortRes] is the answer as a phrase inside a line — "noticed after a workout" — written whole
 * for each answer rather than cut out of the chip's label, whose capital a language may need.
 */
enum class PainNoticed(val labelRes: Int, val shortRes: Int) {
    DURING_WORKOUT(R.string.pain_noticed_during_workout, R.string.pain_noticed_during_workout_short),
    AFTER_WORKOUT(R.string.pain_noticed_after_workout, R.string.pain_noticed_after_workout_short),
    WORK_OR_SITTING(R.string.pain_noticed_work_or_sitting, R.string.pain_noticed_work_or_sitting_short),
    ON_WAKING(R.string.pain_noticed_on_waking, R.string.pain_noticed_on_waking_short),
    AFTER_LIFTING(R.string.pain_noticed_after_lifting, R.string.pain_noticed_after_lifting_short),
    NO_CLEAR_MOMENT(R.string.pain_noticed_no_clear_moment, R.string.pain_noticed_no_clear_moment_short),
}

/**
 * The 1–10 scale's ends: mild, and unbearable. There is no 0: an entry is a pain, and a day
 * without one is answered by "No pain today", never by a pain of nothing.
 */
val PAIN_NRS_RANGE = 1..10

/**
 * One entry's answers.
 *
 * [none] is the explicit "no pain today": without it a day with no entry is unknown rather than
 * pain-free, and every rate worked out later would be wrong. [lastedMinutes] is set when the pain
 * has gone, and [endedAt] only when its start is a real moment to count from.
 *
 * [same] marks the entry as the same pain as one logged before. It holds the moment of that pain's
 * first report — the [JournalEntry.occurredAt] of its first row, which a restore keeps where a row
 * id would change. Such an entry has no start of its own: the pain started with the first one.
 *
 * [map] is the table of patches the entry is painted on, and the one its strokes are hit-tested on
 * wherever they are read again: the current map for a new entry, and for one read back from its
 * payload the map that payload names, which stays its own for good ([PainMapVersion]).
 */
data class PainReport(
    val regions: List<RegionSide> = emptyList(),
    val types: List<PainType> = emptyList(),
    val nrs: Int? = null,
    val onset: PainOnset? = null,
    val onsetAt: Long? = null,
    val endedAt: Long? = null,
    val lastedMinutes: Int? = null,
    val noticed: PainNoticed? = null,
    val painkiller: Boolean = false,
    val strokes: List<PainStroke> = emptyList(),
    val none: Boolean = false,
    val same: Long? = null,
    val map: PainMapVersion = PainMapVersion.CURRENT,
)

/**
 * The most painting one row carries. Past either, only the areas are stored: they are the record,
 * and the strokes exist to draw the painting again. The whole journal is held in memory by many
 * screens, so a row the size of a photograph would be paid for on every one of them.
 */
const val PAIN_MAX_STROKES = 40
const val PAIN_MAX_POINTS = 1_500

/** Figure units are stored as thousandths: under half a plate pixel across, under one down. */
private const val PAIN_QUANTUM = 1_000f

/**
 * [point] as the payload stores it: each coordinate rounded to whole thousandths, the writer's own
 * rounding — so a stroke made of it reads back exactly as it was made ([painStrokesJson]).
 */
internal fun quantised(point: Offset): Offset = Offset(
    (point.x * PAIN_QUANTUM).roundToInt() / PAIN_QUANTUM,
    (point.y * PAIN_QUANTUM).roundToInt() / PAIN_QUANTUM,
)

/**
 * [stroke] as the payload stores it: its radius and every point in whole thousandths, the repeats the
 * rounding makes dropped — the writer's own rounding, so it reads back exactly as it is.
 *
 * The canvas keeps every finished touch in this form. The hit test turns on a cell or two at an area's
 * edge and a thousandth moves that, so the areas read off an unrounded stroke would not always be the
 * areas every later reader of the saved row reads off it: an area on the record that its own drawing
 * did not mark would then be lit whole, as if chosen from the list.
 */
internal fun quantised(stroke: PainStroke): PainStroke = PainStroke(
    stroke.view,
    (stroke.radius * PAIN_QUANTUM).roundToInt().coerceAtLeast(1) / PAIN_QUANTUM,
    stroke.points.map { quantised(it) }.withoutRepeats(),
)

/**
 * The payload, written by hand in a fixed order.
 *
 * The payload is part of a row's identity in the backup, so the same answers must always make the
 * same string — org.json's own writer orders keys by a hash map on the JVM and by insertion on the
 * device. Optional keys are written only when they are set. Everything written is an enum name, a
 * number or a boolean, so nothing needs escaping.
 *
 * The order: schema, regions, types, nrs, same, onset, onsetAt, endedAt, noticed, painkiller,
 * strokes. `same` stands where the start would, because it takes the start's place: an entry that
 * is the same pain as an earlier one writes no onset and no onsetAt, whatever the report holds.
 *
 * The schema is the report's map ([PainReport.map], [PainMapVersion.schema]), written first so a later
 * reader knows which table its strokes were painted on: 1 for every entry painted on the first map,
 * which is what each of them goes on writing when it is saved again — the same string it was — and 2
 * for one painted on the second. A build that knows only the first map reads a second-map payload all
 * the same, as it reads any: it never looks at the schema, and skips the names it does not know.
 */
fun encodePainPayload(report: PainReport): String {
    val schema = report.map.schema
    if (report.none) return """{"schema":$schema,"none":true}"""
    val fields = mutableListOf("\"schema\":$schema")
    fields += "\"regions\":" + report.regions.sortedWith(REGION_SIDE_ORDER).distinct().quoted { it.code }
    if (report.types.isNotEmpty()) fields += "\"types\":" + report.types.distinct().quoted { it.name }
    report.nrs?.let { fields += "\"nrs\":${it.coerceIn(PAIN_NRS_RANGE)}" }
    report.same?.let { fields += "\"same\":$it" }
    if (report.same == null) {
        report.onset?.let { fields += "\"onset\":\"${it.name}\"" }
        report.onsetAt?.let { fields += "\"onsetAt\":$it" }
    }
    report.endedAt?.let { fields += "\"endedAt\":$it" }
    report.noticed?.let { fields += "\"noticed\":\"${it.name}\"" }
    if (report.painkiller) fields += "\"painkiller\":true"
    painStrokesJson(report.strokes)?.let { fields += "\"strokes\":$it" }
    return fields.joinToString(",", "{", "}")
}

/**
 * The strokes as a payload stores them — an array of `{"v":view,"r":radius,"p":[x,y,…]}`, all in whole
 * thousandths — or null when there are none, or past [PAIN_MAX_STROKES] or [PAIN_MAX_POINTS]. The pain
 * row's writer and a chat's thread keep a painting the one same way ([painStrokesFrom] reads both).
 */
internal fun painStrokesJson(strokes: List<PainStroke>): String? = storedStrokes(strokes)?.let { stored ->
    stored.joinToString(",", "[", "]") { stroke ->
        val points = stroke.points.joinToString(",") { "${it.first},${it.second}" }
        """{"v":"${stroke.view.code}","r":${stroke.radius},"p":[$points]}"""
    }
}

/**
 * Whether [strokes] are more painting than a row keeps ([PAIN_MAX_STROKES], [PAIN_MAX_POINTS]), which
 * the writer answers by storing the areas alone. Whoever shows the canvas says so while an Undo can
 * still mend it: a forty-first dab would otherwise take the whole drawing with it at the save, the
 * strokes of an entry opened again among them, without a word.
 */
internal fun painPaintingTooMuch(strokes: List<PainStroke>): Boolean =
    strokes.isNotEmpty() && storedStrokes(strokes) == null

/**
 * The strokes back out of [painStrokesJson]'s array; one that is not a stroke is skipped. A painting
 * past [PAIN_MAX_STROKES] or [PAIN_MAX_POINTS] reads as none, as its writer would have stored it: no
 * writer here makes one, but a restored or imported file can carry a million points, and every screen
 * that reads the row hit-tests and draws them. The areas are the record, and they stay.
 */
internal fun painStrokesFrom(array: JSONArray?): List<PainStroke> {
    val objects = array?.objects().orEmpty()
    val points = objects.sumOf { it.optJSONArray("p")?.length() ?: 0 } / 2
    return if (objects.size > PAIN_MAX_STROKES || points > PAIN_MAX_POINTS) {
        emptyList()
    } else {
        objects.mapNotNull(::strokeOf)
    }
}

private fun <T> List<T>.quoted(name: (T) -> String) = joinToString(",", "[", "]") { "\"${name(it)}\"" }

/** The list with each run of equal neighbours kept once. */
private fun <T> List<T>.withoutRepeats(): List<T> =
    filterIndexed { index, value -> index == 0 || this[index - 1] != value }

/** A stroke as it is stored: its view, its radius and its points, all in whole thousandths. */
private class StoredStroke(val view: PainView, val radius: Int, val points: List<Pair<Int, Int>>)

/**
 * The strokes quantised for storage, with the repeats quantising makes dropped, or null when there
 * are none or the painting is past [PAIN_MAX_STROKES] or [PAIN_MAX_POINTS].
 */
private fun storedStrokes(strokes: List<PainStroke>): List<StoredStroke>? {
    val stored = strokes.map { stroke ->
        StoredStroke(
            stroke.view,
            (stroke.radius * PAIN_QUANTUM).roundToInt().coerceAtLeast(1),
            stroke.points
                .map { (it.x * PAIN_QUANTUM).roundToInt() to (it.y * PAIN_QUANTUM).roundToInt() }
                .withoutRepeats(),
        )
    }.filter { it.points.isNotEmpty() }
    val fits = stored.size <= PAIN_MAX_STROKES && stored.sumOf { it.points.size } <= PAIN_MAX_POINTS
    return stored.takeIf { it.isNotEmpty() && fits }
}

/**
 * The answers back out of a payload. Names this build does not know — an area, a word, a key a
 * later version added — are skipped rather than guessed at; a payload that is not JSON at all reads
 * as an empty report, which is a new one, on the current map.
 *
 * The map is the one the payload's schema names ([PainMapVersion.ofSchema]): the first for schema 1
 * and for a payload without a schema at all.
 */
fun decodePainPayload(payload: String?): PainReport {
    val json = payload?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return PainReport()
    val map = PainMapVersion.ofSchema(json.optInt("schema", PainMapVersion.V1.schema))
    if (json.optBoolean("none")) return PainReport(none = true, map = map)
    return PainReport(
        regions = json.optJSONArray("regions").strings()
            .mapNotNull(RegionSide::parse)
            .distinct()
            .sortedWith(REGION_SIDE_ORDER),
        types = json.optJSONArray("types").strings()
            .mapNotNull { name -> PainType.entries.firstOrNull { it.name == name } }
            .distinct()
            .take(PAIN_TYPES_MAX),
        nrs = json.optInt("nrs", -1).takeIf { it in PAIN_NRS_RANGE },
        onset = json.optString("onset").let { name -> PainOnset.entries.firstOrNull { it.name == name } },
        onsetAt = json.optLong("onsetAt", 0L).takeIf { json.has("onsetAt") },
        endedAt = json.optLong("endedAt", 0L).takeIf { json.has("endedAt") },
        noticed = json.optString("noticed").let { name -> PainNoticed.entries.firstOrNull { it.name == name } },
        painkiller = json.optBoolean("painkiller"),
        strokes = painStrokesFrom(json.optJSONArray("strokes")),
        // Only a real moment: a null, a string or a nought would make the entry a follow-up of 1970.
        same = (json.opt("same") as? Number)?.toLong()?.takeIf { it > 0 },
        map = map,
    )
}

private fun JSONArray?.strings(): List<String> {
    val array = this ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
}

private fun strokeOf(json: JSONObject): PainStroke? {
    val view = PainView.entries.firstOrNull { it.code == json.optString("v") } ?: return null
    val radius = json.optInt("r", 0).takeIf { it > 0 } ?: return null
    val flat = json.optJSONArray("p") ?: return null
    if (flat.length() < 2 || flat.length() % 2 != 0) return null
    val points = (0 until flat.length() / 2).map {
        Offset(flat.optInt(it * 2) / PAIN_QUANTUM, flat.optInt(it * 2 + 1) / PAIN_QUANTUM)
    }
    return PainStroke(view, radius / PAIN_QUANTUM, points)
}

/**
 * A painted path with the points that add nothing taken out: Douglas–Peucker in plate heights, x
 * scaled by [PLATE_ASPECT] so a tolerance means the same across the figure as down it. The route
 * simplifier the tracks use works in latitude and longitude and cannot be borrowed for this.
 */
fun simplifyStroke(points: List<Offset>, tolerance: Float = STROKE_TOLERANCE): List<Offset> {
    if (points.size < 3) return points
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val spans = ArrayDeque(listOf(0 to points.lastIndex))
    while (spans.isNotEmpty()) {
        val (first, last) = spans.removeLast()
        var farthest = -1
        var distance = tolerance
        for (index in first + 1 until last) {
            val away = offLine(points[index], points[first], points[last])
            if (away > distance) {
                distance = away
                farthest = index
            }
        }
        if (farthest > 0) {
            keep[farthest] = true
            spans += first to farthest
            spans += farthest to last
        }
    }
    return points.filterIndexed { index, _ -> keep[index] }
}

/** About a plate pixel and a half: finer than a brush, coarse enough to drop a finger's tremor. */
private const val STROKE_TOLERANCE = 0.0016f

/** How far [point] is from the line through [a] and [b], in plate heights. */
private fun offLine(point: Offset, a: Offset, b: Offset): Float {
    val dx = (b.x - a.x) * PLATE_ASPECT
    val dy = b.y - a.y
    val px = (point.x - a.x) * PLATE_ASPECT
    val py = point.y - a.y
    val length = sqrt(dx * dx + dy * dy)
    return if (length == 0f) sqrt(px * px + py * py) else abs(px * dy - py * dx) / length
}

/**
 * The journal row for a report.
 *
 * [now] is the moment of the report; a report filed against an earlier [day] has no real moment of
 * its own and takes [dayInstant]'s midday of that day. Editing keeps [previous]'s id, day and
 * moment — correcting the answers does not move the report.
 *
 * The title is written in the language showing now, like every row's; the day list reads the
 * payload rather than the title, so it names the areas in whatever language is showing then.
 */
internal fun painEntry(
    title: String,
    report: PainReport,
    day: LocalDate,
    today: LocalDate,
    now: Long,
    note: String = "",
    previous: JournalEntry? = null,
): JournalEntry {
    val lasted = report.lastedMinutes?.takeUnless { report.none }
    val measured = report.endedAt?.let { end -> report.onsetAt?.let { start -> (end - start) / MINUTE_MS } }
    return JournalEntry(
        id = previous?.id ?: 0,
        day = previous?.day ?: day.toString(),
        occurredAt = previous?.occurredAt ?: if (day == today) now else dayInstant(day.toString()),
        kind = KIND_PAIN,
        title = title,
        calories = 0.0,
        durationMinutes = lasted?.let { (measured ?: it.toLong()).toDouble() },
        measurementPayloadJson = encodePainPayload(report),
        note = note.trim(),
    )
}

private const val MINUTE_MS = 60_000L

/**
 * The day list's line for a pain row — "Knee (L), Lower back · 6/10 · aching" — put together from
 * the enum names in the payload, so it is always in the language showing now, its lists parted by
 * that language's [comma].
 */
fun painSummaryLine(
    report: PainReport,
    area: (RegionSide) -> String,
    score: (Int) -> String,
    word: (PainType) -> String,
    none: String,
    comma: String,
): String {
    if (report.none) return none
    return listOfNotNull(
        report.regions.takeIf { it.isNotEmpty() }?.joinToString(comma, transform = area),
        report.nrs?.let(score),
        report.types.takeIf { it.isNotEmpty() }?.joinToString(comma, transform = word),
    ).joinToString(" · ")
}

/**
 * The days answered "no pain" that no pain entry contradicts.
 *
 * A "no pain today" tapped in the morning and a pain logged in the evening can both be on file for a
 * while — the pain page removes the answer when it saves, but a restore or an older copy can still
 * bring the two together. The pain entry is the later, more specific answer, so a day with one is
 * never counted as pain-free.
 */
fun painFreeDays(entries: List<JournalEntry>): Set<String> {
    val (none, pain) = entries.filter { it.kind == KIND_PAIN }
        .partition { decodePainPayload(it.measurementPayloadJson).none }
    return none.map { it.day }.toSet() - pain.map { it.day }.toSet()
}

/** What the summary page shows about today's "no pain": nothing, the answer given, or the button that gives it. */
enum class PainNoneControl { HIDDEN, ANSWERED, OFFERED }

/**
 * Today's "no pain" on the summary page. Nothing until the journal has been read ([loaded]): the
 * stand-in shown before that knows of no answer, and a button offered on its word could be tapped on
 * a day already answered — a second "no pain", or one filed against a pain already logged.
 */
fun painNoneControl(loaded: Boolean, todayNone: Boolean, todayPain: Boolean): PainNoneControl = when {
    !loaded -> PainNoneControl.HIDDEN
    todayNone -> PainNoneControl.ANSWERED
    todayPain -> PainNoneControl.HIDDEN
    else -> PainNoneControl.OFFERED
}

/**
 * The "no pain" answers a pain entry overrules: every one filed on the same day as [report], among
 * [sameDay] — that day's rows. None when [report] is itself the "no pain" answer.
 */
fun noPainRowsOverruled(report: PainReport, sameDay: List<JournalEntry>): List<JournalEntry> =
    if (report.none) {
        emptyList()
    } else {
        sameDay.filter { it.kind == KIND_PAIN && decodePainPayload(it.measurementPayloadJson).none }
    }

/**
 * The pain page's save: [row] written with [write], then the day's "no pain" answers it overrules
 * ([noPainRowsOverruled]) read with [sameDay] and taken away with [delete] — the morning's "no pain"
 * and this entry cannot both be the day's answer. A failure only when [row] itself was not written,
 * said on the page's failure card for Save to be tried again; one a failed delete leaves standing is
 * outweighed by the pain all the same ([painFreeDays]), and the entry, written, is not to be called
 * unsaved and written twice for it.
 */
internal suspend fun savePainRow(
    row: JournalEntry,
    report: PainReport,
    write: suspend (JournalEntry) -> Unit,
    sameDay: suspend (String) -> List<JournalEntry>,
    delete: suspend (Long) -> Unit,
): Result<Unit> = runCatching { write(row) }.onSuccess {
    runCatching { noPainRowsOverruled(report, sameDay(row.day)).forEach { delete(it.id) } }
}

/** How far back an area marked again is looked for: the pain page's own month. */
const val PAIN_EARLIER_DAYS = 30L

/** A pain row with its payload read once, for a lookup that runs again on every change of areas. */
data class PainRow(val entry: JournalEntry, val report: PainReport = decodePainPayload(entry.measurementPayloadJson))

/**
 * The pain an area marked now may already be on file as: the newest pain entry filed in the
 * [PAIN_EARLIER_DAYS] days up to and including [reportDay] that names one of [marked], or an area of
 * the same family on a side that agrees ([sameFamilyAs]) — a left knee is not a right knee, but the
 * back of the left knee painted today can be the left knee painted on the first map last week, when
 * the knee was one area. A family is the area a part was carved from, which is close to the first
 * map's own rule but not the same: where the second map moved a border, a place can belong to one
 * family now and have been recorded under another then. The ridge between the neck and the shoulder
 * is the shoulder's, though from behind the first map mostly recorded it as the upper back.
 *
 * The "no pain" answers name no area and are passed over, and so is [editingId], the entry being
 * corrected, which would otherwise find itself. Entries filed after [reportDay] are passed over as
 * well: a pain logged for a day gone by cannot be the same pain as one that came later. Newest is
 * the latest day, then the latest moment on it.
 */
fun earlierPainFor(
    marked: Set<RegionSide>,
    rows: List<JournalEntry>,
    reportDay: LocalDate,
    editingId: Long?,
): JournalEntry? =
    earlierPainAmong(marked, rows.filter { it.kind == KIND_PAIN }.map { PainRow(it) }, reportDay, editingId)?.entry

/**
 * [earlierPainFor] over rows whose payloads are already read. The pain page asks again each time a
 * stroke reaches a new area, and a month of drawings is too much JSON to read again on every one of
 * those while a finger is still on the glass.
 */
fun earlierPainAmong(
    marked: Set<RegionSide>,
    rows: List<PainRow>,
    reportDay: LocalDate,
    editingId: Long?,
): PainRow? {
    if (marked.isEmpty()) return null
    val window = reportDay.minusDays(PAIN_EARLIER_DAYS - 1).toString()..reportDay.toString()
    return rows
        .filter { (row, report) ->
            row.kind == KIND_PAIN && row.id != editingId && row.day in window &&
                !report.none && report.regions.namesAny(marked)
        }
        .maxWithOrNull(compareBy<PainRow>({ it.entry.day }, { it.entry.occurredAt }, { it.entry.id }))
}

/** Whether these areas name one of [areas], or one of the same family on a side that agrees. */
private fun Collection<RegionSide>.namesAny(areas: Collection<RegionSide>): Boolean =
    any { named -> areas.any(named::sameFamilyAs) }

/**
 * The areas of [earlier] that [marked] finds it by ([earlierPainAmong]), as [earlier] named them: the
 * card that asks whether it is the same pain says where that one was — "Knee · left" for the back of
 * the left knee painted today, the knee being what the first map called it.
 */
fun painSharedAreas(earlier: PainReport, marked: Collection<RegionSide>): List<RegionSide> =
    earlier.regions.filter { area -> marked.any(area::sameFamilyAs) }

/**
 * What an entry that is the same pain as [earlier] carries in its `same` key: the moment of the
 * pain's first report. That is [earlier]'s own `same` when it already follows up an older one, so
 * every entry of one pain points at the first, never along a chain.
 */
fun firstReportOf(earlier: JournalEntry): Long = firstReportOf(PainRow(earlier))

/** [firstReportOf] for a row already read. */
fun firstReportOf(earlier: PainRow): Long = earlier.report.same ?: earlier.entry.occurredAt

/**
 * The pain page's questions, in the order it asks them, one at a time: what it feels like, when it
 * started, how much, when it was first noticed, whether it is still there — and, once gone, how long
 * it lasted — and whether a painkiller was taken.
 */
enum class PainStep { TYPES, ONSET, INTENSITY, NOTICED, STATUS, LASTED, PAINKILLER }

/**
 * How each question gives its answer, and so whether the three-second clock runs under it
 * ([AnswerKind]): the words and the two rulers settle, and the rest are one tap — the start is a
 * choice from a list, and choosing from it is the answer. First-noticed is a tap too, and waits for
 * one: nothing passes it over, and "No clear moment" is the answer for someone who cannot say.
 */
internal val PainStep.answerKind: AnswerKind
    get() = when (this) {
        PainStep.TYPES, PainStep.INTENSITY, PainStep.LASTED -> AnswerKind.SETTLE
        PainStep.ONSET, PainStep.NOTICED, PainStep.STATUS, PainStep.PAINKILLER -> AnswerKind.TAP
    }

/**
 * Which of the pain page's questions it puts. [asked] is whether any show at all; [steps] is the
 * questions it asks, in order — [PainStep.LASTED] among them, though it only comes up for a pain
 * that has gone — and [carried] the ones the earlier pain has already answered, shown as answers
 * that cannot be reopened. [marked] is an area on the body, without which nothing is saved: a new
 * entry asks nothing until there is one, and an entry being corrected can lose its last.
 */
data class PainQuestions(
    val asked: Boolean,
    val steps: List<PainStep> = emptyList(),
    val carried: Set<PainStep> = emptySet(),
    val marked: Boolean = asked,
)

/**
 * What the pain page asks, from where its answers stand.
 *
 * [editing] is the entry being corrected: every question, but the start when it follows up an
 * earlier pain, which it started with. A "no pain today" opened again has no answers to correct: it
 * asks nothing over its empty body, and everything, as a new pain, once an area is marked on it. A
 * new entry asks nothing until an area is [marked] and the month has been read ([rowsLoaded]) —
 * until then an earlier pain may still turn up. With one found ([earlier]) it waits for "the same
 * pain?" ([same]) and, for the same one, "does it feel different?" ([changed]). A new one is asked
 * everything. The same one is never asked when it started; its words and when it was first noticed
 * are carried over from the earlier entry, the words asked again only when it has changed. An
 * earlier entry that never answered one of the two has nothing to carry: its words are then asked,
 * and first-noticed left out — it belongs to the pain's first report, which is what a follow-up
 * saves for it ([painReportToSave]), so an answer given here would be thrown away.
 */
fun painQuestions(
    editing: PainReport?,
    marked: Boolean,
    rowsLoaded: Boolean,
    earlier: PainReport?,
    same: Boolean?,
    changed: Boolean?,
): PainQuestions {
    if (editing != null) return correctionQuestions(editing, marked)
    val samePain = earlier != null && same == true
    val asked = marked && rowsLoaded && (earlier == null || same == false || (samePain && changed != null))
    if (!asked) return PainQuestions(asked = false)
    val carried = if (earlier != null && samePain) {
        setOfNotNull(
            PainStep.TYPES.takeIf { changed == false && earlier.types.isNotEmpty() },
            PainStep.NOTICED.takeIf { earlier.noticed != null },
        )
    } else {
        emptySet()
    }
    val notAsked = if (samePain) carried + PainStep.ONSET + PainStep.NOTICED else emptySet()
    return PainQuestions(asked = true, steps = PainStep.entries - notAsked, carried = carried)
}

/**
 * What an entry opened again asks: every question, but the start of one that follows up an earlier
 * pain; a "no pain today" nothing until an area is [marked], and then everything.
 */
private fun correctionQuestions(editing: PainReport, marked: Boolean): PainQuestions = if (editing.none) {
    PainQuestions(asked = marked, steps = PainStep.entries)
} else {
    val steps = PainStep.entries.filter { it != PainStep.ONSET || editing.same == null }
    PainQuestions(asked = true, steps = steps, marked = marked)
}

/**
 * The questions answered so far, and the one reopened or held open, if any: the form state behind
 * the one-at-a-time questions. The answers themselves live beside it; this says only which of them
 * the person has given. "Not answered yet" exists only here — the payload never learns of it.
 *
 * A single choice answers its question with the tap. A ruler, and the words, hold theirs open until
 * the three-second clock takes them ([stay], then [answered]): a ruler moves on every pixel of a
 * drag, and the words are ticked in a list that stays open for the next. There is no "Next": the
 * clock is the page's only way on ([AutoTakeClock]).
 */
data class PainFlow(val done: Set<PainStep> = emptySet(), val open: PainStep? = null) {
    /**
     * [step] answered — a tap, or the clock running out after the last change — and the page goes on
     * to the first unanswered.
     */
    fun answered(step: PainStep) = PainFlow(done + step, null)

    /**
     * [step]'s answer taken back — the chosen answer tapped again, the last word taken off — and the
     * question held open, unanswered, for another.
     */
    fun takenBack(step: PainStep) = PainFlow(done - step, step)

    /** An answered question tapped to change it, or one being answered that waits for the clock. */
    fun stay(step: PainStep) = copy(open = step)

    /** Answers that no longer stand — the ones "the same pain" fills in, or clears. */
    fun forget(vararg steps: PainStep) = PainFlow(done - steps.toSet(), null)

    /**
     * The words chosen now number [count]. Each change holds the question open for the clock, the
     * second word too: it is ticked in a list still open, and the question going from under it would
     * take the list with it. Taking off the last leaves it unanswered and open.
     */
    fun typesChosen(count: Int): PainFlow = if (count == 0) takenBack(PainStep.TYPES) else stay(PainStep.TYPES)

    /** Still there, or [gone]: gone asks how long it lasted, and still there takes that answer back. */
    fun status(gone: Boolean) =
        PainFlow(if (gone) done + PainStep.STATUS else done + PainStep.STATUS - PainStep.LASTED, null)
}

/**
 * Where the answers stand when [editing], an entry, is opened again: every question answered, so it
 * opens with each as a row and Save open — how long it lasted only for a pain that had [gone]. An
 * entry that lacks a word, a start or a rating shows the blank in its row; a tap reopens it, and
 * nothing has to be made up to correct an area or the note. A new entry, and a "no pain today"
 * opened again, have answered nothing.
 */
fun painFlowFor(editing: PainReport?, gone: Boolean): PainFlow {
    if (editing == null || editing.none) return PainFlow()
    return PainFlow(PainStep.entries.filter { it != PainStep.LASTED || gone }.toSet())
}

/**
 * What the pain page shows now: [asking], the question being put, and [shown], every question that
 * appears, in order — the answers carried over from the earlier pain first, above whatever is put,
 * then the rest with the one asked in its place among them. [canSave] once an area is marked and
 * every question asked is answered.
 */
data class PainProgress(val asking: PainStep?, val shown: List<PainStep>, val canSave: Boolean)

/**
 * The question put next — the one held open ([PainFlow.open]) if there is one, else the first in
 * order not yet answered, as the cycle page asks a day's — and what stands around it. How long it
 * lasted is a question only for a pain that has [gone].
 */
fun painProgress(questions: PainQuestions, flow: PainFlow, gone: Boolean): PainProgress {
    if (!questions.asked) return PainProgress(asking = null, shown = emptyList(), canSave = false)
    val steps = questions.steps.filter { it != PainStep.LASTED || gone }
    val asking = flow.open?.takeIf { it in steps } ?: steps.firstOrNull { it !in flow.done }
    val (carried, rest) = PainStep.entries.partition { it in questions.carried }
    val shown = carried + rest.filter { step -> step == asking || (step in steps && step in flow.done) }
    return PainProgress(asking, shown, canSave = questions.marked && steps.all { it in flow.done })
}

/**
 * The areas, in the order of [names], one of whose names holds every word of [query]; all of them
 * for a query with no words. Names and query are folded alike — case, accents and punctuation gone,
 * as the lab sheet reader folds a name ([nameTokens]) — and a word may be any part of a name's, so
 * "бедр" finds "Бедро спереди · слева" while it is still being typed. [names] holds each area’s
 * names, one per language searched.
 */
fun painAreasMatching(names: Map<RegionSide, List<String>>, query: String): List<RegionSide> {
    val words = nameTokens(query)
    if (words.isEmpty()) return names.keys.toList()
    return names.filterValues { ofArea ->
        ofArea.any { name -> nameTokens(name).joinToString(" ").let { folded -> words.all(folded::contains) } }
    }.keys.toList()
}

/**
 * An area's English words for the search, whatever language is showing: its own name and its side's
 * — "THIGH_FRONT LEFT", which folds to "thigh front left" — so "knee" finds the knee on a page in
 * Russian too. An area the second map carved out of another carries its family's name as well
 * ([root]) — "UNDER_RIBS ABDOMEN RIGHT" — so a search for what the first map called it finds its
 * parts: "abdomen" the six of the belly, which the second map offers in its place.
 */
fun painAreaSearchName(area: RegionSide): String =
    listOfNotNull(area.region.name, area.region.root.takeIf { it != area.region }?.name, area.side.name)
        .joinToString(" ")

/**
 * Everything the list of areas looks for an area by ([painAreasMatching]): its [name] in the language
 * showing; its family's on its side, when it was carved from another — "Живот · справа" finds the
 * right of the belly on a page in Russian, as "abdomen right" does in English — and its English
 * words ([painAreaSearchName]). The family's name is only searched, never shown or stored.
 */
fun painAreaSearchNames(area: RegionSide, name: (RegionSide) -> String): List<String> =
    listOfNotNull(
        name(area),
        area.region.root.takeIf { it != area.region }?.let { name(RegionSide(it, area.side)) },
        painAreaSearchName(area),
    )

/** What keeps Save off: no area, the earlier-pain card's two questions, or a question on the page. */
enum class PainWait { AREA, SAME, CHANGED, ANSWER }

/** What Save waits for, and with [PainWait.ANSWER] the [step] it waits for. */
data class PainSaveWait(val wait: PainWait, val step: PainStep? = null)

/**
 * What the earlier-pain card still waits for: "the same pain?" while an earlier pain is [found] and
 * unanswered, and "does it feel different?" once it is the [same] one and that is not yet [changed].
 */
fun painCardWait(found: Boolean, same: Boolean?, changed: Boolean?): PainWait? = when {
    found && same == null -> PainWait.SAME
    found && same == true && changed == null -> PainWait.CHANGED
    else -> null
}

/**
 * The one thing Save waits for, first in the order the page asks — for the line under it while it
 * is off, which says what is still to answer rather than leaving a button that looks dead: an area
 * while none is [marked], then what the earlier-pain card waits for ([card], from [painCardWait]),
 * then the first question not yet answered. Null once Save is open, and while the month is still
 * being read, when there is nothing yet to ask.
 */
fun painSaveWait(
    marked: Boolean,
    card: PainWait?,
    questions: PainQuestions,
    flow: PainFlow,
    gone: Boolean,
): PainSaveWait? {
    val unanswered = questions.steps.firstOrNull { (it != PainStep.LASTED || gone) && it !in flow.done }
    return when {
        painProgress(questions, flow, gone).canSave -> null
        !marked -> PainSaveWait(PainWait.AREA)
        card != null -> PainSaveWait(card)
        questions.asked && unanswered != null -> PainSaveWait(PainWait.ANSWER, unanswered)
        else -> null
    }
}

/**
 * The report the pain page saves from [answers] — what its questions hold, shown or not; its
 * onsetAt, endedAt and same are worked out here.
 *
 * "Just now" is [reportedAt], the moment of the report: the start of a pain that still hurts, and the
 * end of one gone by then, which began as long before it as it lasted — counted on from the report,
 * its end would stand in the future. A new entry answered as the same pain ([same]) as [earlier] has no start of
 * its own, whatever the start chips still hold from before the answer, and points at the pain's
 * first report; its first-noticed answer is the earlier one's, and so are its words unless it has
 * [changed] — or the earlier one had none, and the words were asked as for a new pain. An entry
 * being corrected ([editing]) keeps what it followed up, and still no start.
 *
 * An entry being corrected keeps its map too ([painEntryMap]): one painted on the first map is saved
 * on the first map again, however it was corrected or painted on. A new one keeps the map [answers]
 * was painted on, and so does a "no pain" answer painted on, which had no painting to keep a map for.
 */
fun painReportToSave(
    answers: PainReport,
    reportedAt: Long,
    earlier: PainRow?,
    same: Boolean?,
    changed: Boolean?,
    editing: PainReport?,
): PainReport {
    val carried = if (editing == null) earlier?.takeIf { same == true } else null
    val followsUp = if (editing == null) carried != null else editing.same != null
    val justNow = reportedAt.takeIf { answers.onset == PainOnset.JUST_NOW && !followsUp }
    val lasted = answers.lastedMinutes?.let { it * MINUTE_MS }
    return answers.copy(
        types = carried?.report?.types?.takeIf { changed == false && it.isNotEmpty() } ?: answers.types,
        onset = answers.onset.takeUnless { followsUp },
        onsetAt = justNow?.minus(lasted ?: 0L),
        endedAt = justNow?.takeIf { lasted != null },
        noticed = if (carried != null) carried.report.noticed else answers.noticed,
        same = if (editing == null) carried?.let { firstReportOf(it) } else editing.same,
        map = editing?.let(::painEntryMap) ?: answers.map,
    )
}

/**
 * The map the pain page paints [initial] on, and saves it on: the map an entry was painted on, for good
 * — so a painting of the first map is hit-tested, listed and saved on the first map — and the current
 * one for a new entry and for a "no pain" answer opened again, which has no painting to keep a map for:
 * once an area is marked on it, it is asked as a new pain, and painted as one.
 */
fun painEntryMap(initial: PainReport): PainMapVersion = if (initial.none) PainMapVersion.CURRENT else initial.map

/**
 * How many days each area hurt among [entries]: an area counted once a day however many entries
 * named it that day. The explicit "no pain" rows name no area and count for nothing here.
 *
 * Counted by the area each entry recorded, on whichever map, not rolled up into families: the
 * most-hurt list under the summary's body names "Back of the knee (L)" and "Knee (L)" apart, as the
 * second map records them. Rolled up, every area the second map adds would vanish into its parent's
 * name — the six parts of the belly into "Abdomen" — and the centre of the chest, whose parent is
 * paired, would have no side to be counted under. An area both maps have is one code on both and its
 * days add up, though on the first map "Knee" meant the back of the knee as well as the front.
 */
fun painDaysByArea(entries: List<JournalEntry>): Map<RegionSide, Int> = entries
    .filter { it.kind == KIND_PAIN }
    .flatMap { entry -> decodePainPayload(entry.measurementPayloadJson).regions.map { it to entry.day } }
    .distinct()
    .groupingBy { it.first }
    .eachCount()

/**
 * The areas [report] records that its strokes do not mark: chosen by name from the list — or painted
 * above the plates, where a stored stroke can lie but no patch does. A whole-figure dab on the jaw or
 * the neck strip above the plate may still reach the neck's patch, and then the neck is not among
 * these: the editor counts it as painted.
 *
 * The strokes are hit-tested on the map the report was painted on ([PainReport.map]), as here and in
 * every function after this one that reads an entry's strokes again: on a later map a stroke on the
 * back of the knee marks the back of the knee, which an entry of the first map, that recorded the
 * knee, never named.
 */
fun painListedAreas(report: PainReport): Set<RegionSide> =
    (report.regions.toSet() - paintedRegions(report.strokes, report.map)).toSortedSet(REGION_SIDE_ORDER)

/**
 * The areas the paint page records: what the strokes mark, less what was struck off the list, plus
 * what was added to it by name.
 */
fun painMarkedAreas(
    painted: Set<RegionSide>,
    added: Collection<RegionSide>,
    removed: Collection<RegionSide>,
): Set<RegionSide> = (painted - removed.toSet() + added).toSortedSet(REGION_SIDE_ORDER)

/**
 * What the page opens [initial] with, as the two lists it keeps beside the strokes: the areas added
 * by name ([painListedAreas]) and the ones the strokes mark that the entry does not record. With
 * both, [painMarkedAreas] gives back exactly the areas the entry was saved with, whatever the hit
 * test makes of its strokes: an entry painted on the head above the plates keeps its temple.
 */
fun painPageLists(initial: PainReport): Pair<Set<RegionSide>, Set<RegionSide>> =
    painListedAreas(initial) to (paintedRegions(initial.strokes, initial.map) - initial.regions.toSet())

/**
 * Which areas the paint page lights whole: only those [marked] by name, with nothing [painted] on
 * them. A painted area is shown by its strokes alone — lighting the whole of it would show pain on
 * the side of the back, or the twin of a pair, that the person never painted.
 */
fun painLitAreas(marked: Set<RegionSide>, painted: Set<RegionSide>): Map<RegionSide, Float> =
    (marked - painted).associateWith { 1f }

/**
 * The strokes of [report] that are drawn for someone else to read — the summary's body and the
 * doctor's sheet — exactly as painted.
 *
 * Two kinds are left out. A stroke painted wholly above the plate, on the head: whether a stroke is
 * on the plate is decided by where it was painted, not by how far its brush reaches, since a
 * whole-figure dab on the jaw above it reaches past the neck and would print as a cap of ink on it
 * next to a row that says Jaw. And a stroke every area of which was struck off the list: the person
 * said "not there", and a mark in a place no row names would be a place nobody pointed at. A stroke
 * that marks no area at all, between two patches, is kept — nothing was struck off it.
 */
fun painShownStrokes(report: PainReport): List<PainStroke> {
    val recorded = report.regions.toSet()
    return report.strokes.filter { stroke ->
        val marks = paintedRegions(listOf(stroke), report.map)
        paintedOnPlate(stroke) && (marks.isEmpty() || marks.any { it in recorded })
    }
}

/**
 * Whether any of [stroke] was painted on the plate rather than wholly above it, on the head: what
 * the stroke's points say, not how far its brush reaches.
 */
fun paintedOnPlate(stroke: PainStroke): Boolean = stroke.points.any { it.y >= 0f }

/**
 * What the summary's body shows of [entries]: every entry's strokes as painted ([painShownStrokes]),
 * one list per entry, and the days each area was recorded on with no shown stroke on it — chosen from
 * the list, or painted only where nothing is drawn, the head above the plate and its neck strip —
 * which are lit whole, the only way those areas can be shown ([painUnshownAreas]).
 *
 * The body is drawn on the current map, and [listedDays] names the areas of the current map that
 * light each one ([litOn]): an area an entry of the first map chose from the list lights itself and
 * the parts the current map carved out of it, on its side — "Knee" the front of the knee and the back
 * of it, "Abdomen" the six parts of the belly — on the days that entry was told on.
 */
data class PainHeat(val marks: List<List<PainStroke>>, val listedDays: Map<RegionSide, Int>)

/**
 * The areas [report] records that none of its shown strokes ([painShownStrokes]) marks. Not
 * [painListedAreas], which asks what the strokes mark at all: a dab on the neck strip above the plate
 * still marks the neck, but it is not drawn, so without this a recorded neck would be shown nowhere.
 */
fun painUnshownAreas(report: PainReport): Set<RegionSide> =
    (report.regions.toSet() - paintedRegions(painShownStrokes(report), report.map)).toSortedSet(REGION_SIDE_ORDER)

fun painHeat(entries: List<JournalEntry>): PainHeat {
    val reports = entries.filter { it.kind == KIND_PAIN }
        .map { it.day to decodePainPayload(it.measurementPayloadJson) }
        .filterNot { it.second.none }
    return PainHeat(
        marks = reports.map { painShownStrokes(it.second) }.filter { it.isNotEmpty() },
        listedDays = reports
            .flatMap { (day, report) ->
                painUnshownAreas(report)
                    .flatMap { it.litOn(PainMapVersion.CURRENT, from = report.map) }
                    .map { it to day }
            }
            .distinct()
            .groupingBy { it.first }
            .eachCount(),
    )
}

/**
 * Whether the answers call for the safety line: anything in the chest, or a head pain of eight or
 * more. The line itself says what is urgent without naming anything; this only decides when.
 *
 * The chest is its whole family ([root]): the centre of the chest, which the second map carved from
 * its two sides, before all — a dab on the breastbone is the pain the line is there for. The upper
 * belly is not in it: the line's words are about the chest, and a belly pain is not one.
 */
fun painNeedsSafetyLine(regions: Collection<RegionSide>, nrs: Int?): Boolean =
    regions.any { it.region.root == PainRegion.CHEST } ||
        ((nrs ?: 0) >= SEVERE_HEAD_NRS && regions.any { it.region.group == PainGroup.HEAD })

private const val SEVERE_HEAD_NRS = 8

/** How a pain compares with the usual picture of soreness after training. */
enum class SorenessFit {
    /** Every part of the usual picture holds. */
    FITS,

    /** A session of this group came before it, and some part of the picture does not hold. */
    NOT_TYPICAL,

    /**
     * No logged session of a group under these areas came in the window before it — or the start
     * is too far back ("about a month ago" or longer) to place a window at all.
     */
    NO_SESSION,
}

/** The parts of the usual picture a pain did not match, each one a fact about typical soreness. */
enum class SorenessReason(val labelRes: Int) {
    LASTED_OVER_A_WEEK(R.string.pain_soreness_long),

    /** First noticed during the workout — what was answered, not how suddenly it came on. */
    NOTICED_DURING_WORKOUT(R.string.pain_soreness_during),
    SHARP_OR_BURNING(R.string.pain_soreness_words),
    OTHER_WORDS(R.string.pain_soreness_other_words),
    ON_A_JOINT(R.string.pain_soreness_joint),
}

/** The verdict, the reasons behind a NOT_TYPICAL, and the session it was measured from. */
data class SorenessVerdict(
    val fit: SorenessFit,
    val reasons: Set<SorenessReason> = emptySet(),
    val session: JournalEntry? = null,
)

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

/** Soreness starts hours after the session, not during it. */
const val SORENESS_EARLIEST_MS = 6 * HOUR_MS

/** And is at its height one to three days later; a start past that is not tied to the session. */
const val SORENESS_LATEST_MS = 72 * HOUR_MS

/** It usually clears within about a week. */
const val SORENESS_LASTS_MS = 7 * DAY_MS

private val SORENESS_WORDS = setOf(PainType.ACHING, PainType.STIFF)
private val NOT_SORENESS_WORDS = setOf(PainType.SHARP, PainType.BURNING)

/**
 * When the pain began, as the stretch of time its answers allow: one moment where the entry gives
 * one, else the whole of what the onset answer covers — "yesterday" is any time yesterday, "2–3
 * days ago" any time over those two days, "today" the report's day up to the report, "about a week
 * ago" the fourth to the tenth day back. With no onset answered, the report is the start. Null for
 * the answers that reach back too far to place a workout against ([PainOnset.longStanding]): about a
 * month and longer, and the two answers kept but not offered ([PainOnset.offered]) that reach as far.
 *
 * Days are the report's own [JournalEntry.day] in [zone], so a report back-dated to Tuesday counts
 * "yesterday" from Tuesday.
 */
internal fun painOnsetSpan(report: PainReport, pain: JournalEntry, zone: ZoneId): LongRange? {
    val day = pain.day.toLocalDateOrNull()
    return when {
        report.onsetAt != null -> report.onsetAt..report.onsetAt
        report.onset?.longStanding == true -> null
        day == null -> pain.occurredAt..pain.occurredAt
        else -> daysOnsetSpan(report.onset, day, pain.occurredAt, zone)
    }
}

/** The stretch an onset answer covers, counted back from the report's [day] and moment [reported]. */
private fun daysOnsetSpan(onset: PainOnset?, day: LocalDate, reported: Long, zone: ZoneId): LongRange {
    fun start(daysBack: Long) = day.minusDays(daysBack).atStartOfDay(zone).toInstant().toEpochMilli()
    return when (onset) {
        PainOnset.EARLIER_TODAY -> start(0)..reported.coerceAtLeast(start(0))
        PainOnset.YESTERDAY -> start(1) until start(0)
        PainOnset.DAYS_2_3 -> start(3) until start(1)
        PainOnset.ABOUT_A_WEEK -> start(PAIN_ONSET_REACH_DAYS) until start(PAIN_ABOUT_A_WEEK_NEAREST - 1)
        else -> reported..reported
    }
}

/**
 * Whether a pain fits the usual timing of soreness after training the group under it — a rule of
 * thumb from the sports-medicine reviews (Mueller-Wohlfahrt 2013; Hotfiel 2018; Cheung 2003), never a
 * diagnosis, and worded as one wherever it is shown.
 *
 * FITS needs all of it: an area over a muscle group a logged session worked, that session's end
 * between six and seventy-two hours before the pain began, only aching or stiff as its words, not
 * first noticed during the workout, no area centred on a joint, and over within a week of starting.
 * With a session in the window and any of the rest missing it is NOT_TYPICAL, with each missing part
 * named; with none it is NO_SESSION.
 *
 * Where the start is a stretch rather than a moment ([painOnsetSpan]), a session counts only if it
 * is six to seventy-two hours before every moment of it: a pain that began "yesterday" is never
 * matched to a workout logged this morning, however the hours fall.
 *
 * The session is a workout logged here. Walks are left out, and so is anything imported from a file
 * or from Health Connect: an import that could not name its exercise is filed as general strength
 * work, which would claim muscles nobody knows were worked. A logged session ends at its
 * `occurredAt` — the moment it was written down, as [JournalEntry.sessionInterval] reads it.
 *
 * Everything is read off the record, never off the clock: the same entry gives the same verdict
 * the day it is logged and a month later.
 *
 * An entry that is the same pain as an earlier one ([PainReport.same]) has no start of its own and
 * is judged by the pain's first row in [journal]: that row's start, session, areas and first-noticed
 * answer, with this entry's words when it has any — the earlier ones carried over, or the ones it
 * changed to. To that verdict it adds "lasted longer" when this entry has the pain still there more
 * than a week after the first row's start, as [withinAWeek] measures it: whether it still hurts or
 * has gone since, a pain back ten days on was there ten days on. With the first row not in
 * [journal] there is nothing to measure from: NO_SESSION.
 */
fun trainingSoreness(
    pain: JournalEntry,
    journal: List<JournalEntry>,
    zone: ZoneId = ZoneId.systemDefault(),
): SorenessVerdict {
    val report = decodePainPayload(pain.measurementPayloadJson)
    return if (report.same == null) {
        sorenessOf(report, pain, journal, zone)
    } else {
        samePainSoreness(report, report.same, pain, journal, zone)
    }
}

/** [trainingSoreness] for an entry that follows up the pain first reported at [firstAt]. */
private fun samePainSoreness(
    report: PainReport,
    firstAt: Long,
    pain: JournalEntry,
    journal: List<JournalEntry>,
    zone: ZoneId,
): SorenessVerdict {
    // The moment is not unique: every entry filed for a day gone by carries that day's midday, a
    // follow-up filed for the same day as its first entry among them. The first entry is one that
    // follows up nothing, and among several the one naming an area this entry names — or one of its
    // family, as the earlier-pain lookup matched them: the knee of the first map for a back of the knee.
    val candidates = journal
        .filter { it.kind == KIND_PAIN && it.occurredAt == firstAt }
        .map { PainRow(it) }
        .filter { !it.report.none && it.report.same == null }
    val (first, firstReport) = candidates.firstOrNull { it.report.regions.namesAny(report.regions) }
        ?: candidates.firstOrNull()
        ?: return SorenessVerdict(SorenessFit.NO_SESSION)
    val verdict = sorenessOf(firstReport.copy(types = report.types.ifEmpty { firstReport.types }), first, journal, zone)
    val began = painOnsetSpan(firstReport, first, zone)?.first ?: first.occurredAt
    val longer = lastKnownPain(pain, zone) - began > SORENESS_LASTS_MS
    return if (verdict.fit == SorenessFit.NO_SESSION || !longer) {
        verdict
    } else {
        SorenessVerdict(SorenessFit.NOT_TYPICAL, verdict.reasons + SorenessReason.LASTED_OVER_A_WEEK, verdict.session)
    }
}

/**
 * The last moment a follow-up has its pain still there: the report while it still hurts; once it
 * has gone, the report less how long it lasted, but never before the start of the day it is filed
 * under — it is that day's pain.
 */
private fun lastKnownPain(pain: JournalEntry, zone: ZoneId): Long {
    val lasted = pain.durationMinutes?.let { (it * MINUTE_MS).toLong() } ?: return pain.occurredAt
    val dayStart = runCatching { LocalDate.parse(pain.day).atStartOfDay(zone).toInstant().toEpochMilli() }
        .getOrDefault(pain.occurredAt)
    return maxOf(pain.occurredAt - lasted, minOf(dayStart, pain.occurredAt))
}

/** [trainingSoreness] for one pain on its own, read from [report]: its answers, or a follow-up's words. */
private fun sorenessOf(
    report: PainReport,
    pain: JournalEntry,
    journal: List<JournalEntry>,
    zone: ZoneId,
): SorenessVerdict {
    val began = painOnsetSpan(report, pain, zone)?.takeUnless { report.none }
    val muscles = report.regions.mapNotNull { it.region.muscle }.toSet()
    val session = began?.let { span ->
        journal
            .filter { it.isOwnSession() && it.workedMuscles().any(muscles::contains) }
            .filter { span.first - it.occurredAt >= SORENESS_EARLIEST_MS }
            .filter { span.last - it.occurredAt <= SORENESS_LATEST_MS }
            .maxByOrNull { it.occurredAt }
    }
    if (began == null || session == null) return SorenessVerdict(SorenessFit.NO_SESSION)
    val reasons = buildSet {
        if (!withinAWeek(report, pain, began)) add(SorenessReason.LASTED_OVER_A_WEEK)
        if (report.noticed == PainNoticed.DURING_WORKOUT) add(SorenessReason.NOTICED_DURING_WORKOUT)
        if (report.types.any { it in NOT_SORENESS_WORDS }) {
            add(SorenessReason.SHARP_OR_BURNING)
        } else if (!SORENESS_WORDS.containsAll(report.types)) {
            add(SorenessReason.OTHER_WORDS)
        }
        if (report.regions.any { it.region.joint }) add(SorenessReason.ON_A_JOINT)
    }
    return if (reasons.isEmpty()) {
        SorenessVerdict(SorenessFit.FITS, session = session)
    } else {
        SorenessVerdict(SorenessFit.NOT_TYPICAL, reasons, session)
    }
}

/** The groups the catalogue says a session's exercise works; none for a code it does not list. */
internal fun JournalEntry.workedMuscles(): Set<Muscle> =
    ACTIVITY_CATALOG.firstOrNull { it.code == activityCode }?.muscles.orEmpty()

/**
 * A workout logged in this app, of an exercise that is not a walk. The Health Connect codes are
 * not in the catalogue and so work no muscles anyway; they are refused here by name so that
 * adding one to the catalogue cannot make an import count.
 */
internal fun JournalEntry.isOwnSession(): Boolean =
    kind == KIND_ACTIVITY &&
        healthConnectRecordKey == null &&
        activityCode != null &&
        activityCode != "WALK" &&
        !activityCode.startsWith("HEALTH_CONNECT")

/**
 * Whether the episode was over within a week of starting, as far as the entry says. A pain that
 * has gone is measured by how long it lasted; one that still hurt is measured to the report, since
 * that is the last moment the entry knows about — a still-hurting entry looked at a month later has
 * not been reported as lasting a month.
 */
private fun withinAWeek(report: PainReport, pain: JournalEntry, began: LongRange): Boolean {
    val lasted = when {
        report.endedAt != null -> report.endedAt - began.first
        pain.durationMinutes != null -> (pain.durationMinutes * MINUTE_MS).toLong()
        else -> pain.occurredAt - began.first
    }
    return lasted <= SORENESS_LASTS_MS
}
