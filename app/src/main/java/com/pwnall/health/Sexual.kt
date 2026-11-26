package com.pwnall.health

import android.content.res.Resources
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Sexual activity as the journal holds it, for everyone rather than for half the profiles.
 *
 * The cycle screen is shown only to a female profile ([HomeScreen] gates it), so acts are logged
 * here as well, where a male profile can record them too — and where the prostate association below
 * finds them. The entries keep the cycle screen's [KIND_CYCLE_LOG] kind, so the Health Connect path
 * is the same: a logged act becomes a SexualActivityRecord with its protection, and comes back on a
 * restore.
 *
 * Masturbation is the exception and stays on the device. Health Connect's record carries
 * protection and nothing else, and filing a solo act under it would publish a claim the record
 * cannot qualify; the frequency it answers for — ejaculations, for [prostateAdvice] — is a
 * question this app asks and no other app should inherit an answer to.
 */
const val KIND_MASTURBATION = "MASTURBATION"

/** Who a note is offered to, since half of them only make sense for one body. */
enum class SexAudience { ANY, MALE, FEMALE }

/**
 * What can be noted about an act, as ticks rather than a text field.
 *
 * Pain and desire are asked of everyone; the rest are the questions a clinician asks one sex and
 * not the other, and they are the symptoms with an actual evaluation behind them — dyspareunia
 * and postcoital bleeding for a woman, erectile difficulty, painful ejaculation and blood in the
 * semen for a man. Ticks because this is logged in passing, and because a fixed vocabulary is what
 * a month of entries can be counted over; free text is not.
 *
 * None of it reaches Health Connect: its sexual-activity record carries protection and nothing
 * else, so these stay in the journal and in the app's own export.
 */
enum class SexNote(val titleRes: Int, val audience: SexAudience) {
    PAIN(R.string.sex_note_pain, SexAudience.ANY),
    LOW_DESIRE(R.string.sex_note_low_desire, SexAudience.ANY),
    DISCOMFORT(R.string.sex_note_discomfort, SexAudience.ANY),
    BLEEDING(R.string.sex_note_bleeding, SexAudience.FEMALE),
    DRYNESS(R.string.sex_note_dryness, SexAudience.FEMALE),
    ERECTION(R.string.sex_note_erection, SexAudience.MALE),
    EJACULATION_PAIN(R.string.sex_note_ejaculation_pain, SexAudience.MALE),
    BLOOD_IN_SEMEN(R.string.sex_note_blood_semen, SexAudience.MALE),
}

/** The ticks a profile is offered: the shared ones, then the ones that fit the body. */
fun sexNotesFor(sex: String?): List<SexNote> = SexNote.entries.filter {
    it.audience == SexAudience.ANY || it.audience.name == sex
}

/** Where the ticks live inside [JournalEntry.measurementPayloadJson], beside the log's own name. */
const val CYCLE_NOTES_KEY = "notes"

/**
 * The ticks an entry carried, in enum order, ignoring names a later version may have added.
 *
 * Read by matching the names inside the notes array rather than through a JSON parser: the
 * vocabulary is a handful of constants this file owns, and reading it this way is what lets the
 * restore path have a plain JVM test — android's org.json is a throwing stub off the device, so a
 * parser here would be the one step no test could cover.
 */
fun sexNotesOf(payloadJson: String?): List<SexNote> {
    val inside = payloadJson?.let(NOTES_ARRAY::find)?.groupValues?.get(1) ?: return emptyList()
    return SexNote.entries.filter { "\"${it.name}\"" in inside }
}

/**
 * The ticks [payloadJson] carries, named in the language showing and parted by its comma: what the day
 * list says of a solo act or a partnered one. The row's subtitle holds them in the words of the day
 * they were written, for whatever reads the row outside the app.
 */
internal fun sexNotesText(resources: Resources, payloadJson: String?): String =
    sexNotesOf(payloadJson).joinToString(resources.getString(R.string.list_comma)) { resources.getString(it.titleRes) }

private val NOTES_ARRAY = Regex(""""$CYCLE_NOTES_KEY"$SPACE*:$SPACE*\[([^]]*)]""")

/** A solo act. Its own kind, because it is the one thing here that never leaves the device. */
internal fun masturbationEntry(resources: Resources, day: LocalDate, notes: List<SexNote>) = JournalEntry(
    day = day.toString(),
    occurredAt = dayInstant(day.toString()),
    kind = KIND_MASTURBATION,
    title = resources.getString(R.string.sexual_masturbation),
    subtitle = notes.joinToString(", ") { resources.getString(it.titleRes) },
    calories = 0.0,
    measurementPayloadJson = notesPayload(notes),
)

/** The payload a solo act carries: only its ticks, since it has no protection to record. */
private fun notesPayload(notes: List<SexNote>): String? = notes
    .takeIf { it.isNotEmpty() }
    ?.joinToString(",", prefix = """{"$CYCLE_NOTES_KEY":[""", postfix = "]}") { "\"${it.name}\"" }

/**
 * The three acts the sex-life page files, in the order its tiles stand: the two that reach Health
 * Connect as a sexual-activity record with their protection, and the solo act that never leaves the
 * device. [log] is the cycle log a partnered act is filed as; [key] is what the page's one-tap lock
 * and the data layer's duplicate guard know the act by.
 */
internal enum class IntimateAct(val log: CycleLog?, val titleRes: Int, val art: Int) {
    PROTECTED(CycleLog.SEX_PROTECTED, R.string.cycle_sex_protected, R.drawable.ic_art_security),
    UNPROTECTED(CycleLog.SEX_UNPROTECTED, R.string.cycle_sex_unprotected, R.drawable.ic_art_sex),
    SOLO(null, R.string.sexual_masturbation, R.drawable.ic_art_profile),
    ;

    val key: String get() = log?.name ?: MASTURBATION_KEY

    /** Whether rows of this act are published: the store a row lives in, besides this journal. */
    val reachesHealthConnect: Boolean get() = log != null
}

/** The act a row records, or null for a row that is not one — a flow, a mucus mark, anything else. */
internal fun JournalEntry.intimateAct(): IntimateAct? = if (kind == KIND_MASTURBATION) {
    IntimateAct.SOLO
} else {
    cycleLog()?.let { log -> IntimateAct.entries.firstOrNull { it.log == log } }
}

/**
 * The row a tile files for [act] on [day] with [notes]: [cycleLogEntry] for a partnered act and
 * [masturbationEntry] for the solo one. The sex-life page's tiles and its edit page both build their
 * rows here, so an edited act carries the kind, the title and the payload a tile would have written,
 * and reads, backs up and restores exactly as one.
 */
internal fun actEntry(resources: Resources, act: IntimateAct, day: LocalDate, notes: List<SexNote>): JournalEntry {
    val log = act.log
    return if (log == null) {
        masturbationEntry(resources, day, notes)
    } else {
        cycleLogEntry(resources, log, day.toString(), notes)
    }
}

/** What the sexual-health rules count over a window: acts, solo acts, and the ticks on them. */
data class SexualWindow(
    val acts: Int,
    val soloActs: Int,
    val days: Int,
    val notes: Map<SexNote, Int>,
) {
    /**
     * Acts and solo acts together, which is what the prostate association was measured over —
     * the cohort counted ejaculations, however they happened.
     */
    val ejaculations: Int get() = acts + soloActs
}

/** Everything sexual in [days] up to and including [today], counted once. */
fun sexualWindow(journal: List<JournalEntry>, today: LocalDate, days: Int): SexualWindow {
    val from = today.minusDays(days - 1L)
    val window = journal.filter {
        it.day.toLocalDateOrNull()?.let { day -> day in from..today } == true
    }
    val acts = window.filter { entry ->
        entry.kind == KIND_CYCLE_LOG && cycleLogOf(cyclePayloadName(entry)) in SEXUAL_LOGS
    }
    val solo = window.filter { it.kind == KIND_MASTURBATION }
    val notes = (acts + solo)
        .flatMap { sexNotesOf(it.measurementPayloadJson) }
        .groupingBy { it }
        .eachCount()
    return SexualWindow(acts = acts.size, soloActs = solo.size, days = days, notes = notes)
}

/** The two cycle logs that are an act; the rest of that enum is flow, ovulation and mucus. */
private val SEXUAL_LOGS = setOf(CycleLog.SEX_PROTECTED, CycleLog.SEX_UNPROTECTED)

/** The log's own name out of the payload, read the same dependency-free way the ticks are. */
private fun cyclePayloadName(entry: JournalEntry): String? =
    entry.measurementPayloadJson?.let(PAYLOAD_NAME::find)?.groupValues?.get(1)

private val PAYLOAD_NAME = Regex(""""$CYCLE_PAYLOAD_KEY"$SPACE*:$SPACE*"([^"]*)"""")

/** Acts and solo acts: what the cycle calendar's dots and the cycle analysis count, and nothing else. */
internal fun JournalEntry.isSexualAct(): Boolean = kind == KIND_MASTURBATION || cycleLog() in INTIMATE_LOGS

/**
 * Anything that says a sexual diary is being kept: the acts, and the morning check-ins. The sheet's
 * "days kept by hand" leave all of these out — a day holding nothing but a morning answer, counted as
 * kept, would carry the fact that one exists onto a sheet that left the sexual section off.
 */
internal fun JournalEntry.isSexualData(): Boolean = isSexualAct() || kind == KIND_MORNING_CHECK

/**
 * What the age gate ([sexLogOffered]) keeps out of sight while it is shut: the sexual diary, and the
 * answers about desire the cycle page asks beside it. Nobody under the gate can file either — the
 * doors to them are not drawn — but a restored backup, or a birth date corrected afterwards, leaves
 * them in the journal, and a gate that took the question away takes the answer away with it wherever
 * the journal is listed, counted or printed.
 */
internal fun JournalEntry.behindSexGate(): Boolean = isSexualData() || cycleLog() in CycleScale.DESIRE.logs

/**
 * The rows a profile is shown: all of them where the gate is open ([acts]), and without those
 * [behindSexGate] where it is shut. Nothing is deleted — they are back the day the profile is of age.
 */
internal fun List<JournalEntry>.shownTo(acts: Boolean): List<JournalEntry> =
    if (acts) this else filterNot { it.behindSexGate() }

/**
 * A man's answer about his own waking: was there an erection, and when did he wake.
 *
 * Asked once a day because the evidence behind it is about mornings counted over weeks, never one
 * morning read alone. Erections during sleep come with REM sleep at every age (Hirshkowitz & Schmidt
 * 2005), and whether one is still there on waking depends on the stage of sleep the waking cut into
 * (Dijk & Czeisler 1995), so a single "none" says very little. What a daily answer adds to the
 * month-long recall of the EMAS item (Wu 2010) is that it can sit beside that night's sleep — which is
 * why the waking time travels with it.
 *
 * The waking time lives in the payload and nowhere else. The Health Connect sleep export, the sleep
 * summary, the dose timetable and the calendar's sleep filter all pick rows by
 * [JournalEntry.sleepEndAt], so a check-in stored there would be published as a night and counted as
 * one. Nothing about this kind reaches Health Connect, and it is shown only on the intimate screen,
 * behind the same age gate as the acts: never on the home screen, a widget or a notification.
 */
const val KIND_MORNING_CHECK = "MORNING_CHECK"

/**
 * What was there on waking, graded, because the EMAS item asks about a *full* erection and a single
 * graded self-report is a reliable one (Mulhall 2007). [score] is what a later analysis counts.
 * "Didn't notice" scores nothing: it is an unknown morning, like one left unanswered, and never a
 * morning with none.
 */
enum class MorningErection(val titleRes: Int, val score: Int?) {
    FIRM(R.string.sexual_morning_firm, 2),
    PARTLY(R.string.sexual_morning_partly, 1),
    NONE(R.string.sexual_morning_none, 0),
    DIDNT_NOTICE(R.string.sexual_morning_didnt_notice, null),
}

/**
 * Where the waking time came from: measured — the night's own recorded end, or the phone's first use
 * after it ([MorningCheck.fromPhone] tells those two apart) — or a time the person set.
 */
enum class WakeSource { SLEEP, TYPED }

/**
 * What ended the night, when he says. Optional, and kept only to tell mornings apart later: an alarm
 * can cut into any stage of sleep, where a night that ends on its own tends to end out of REM.
 */
enum class WokeBy(val titleRes: Int) {
    ALARM(R.string.sexual_morning_woke_by_alarm),
    OWN(R.string.sexual_morning_woke_by_own),
    WOKEN(R.string.sexual_morning_woke_by_woken),
}

/**
 * One morning's check-in as its payload holds it. [later] marks an answer given after its day;
 * [answeredAt] is the moment it was last saved, which is what decides between two rows for one
 * morning — see [morningChecksByDay].
 *
 * [fromPhone] marks a [WakeSource.SLEEP] waking read off the phone's first use after the night
 * ([phoneWakeAt]) rather than off a sleep record. It is stored with the row because it cannot be
 * worked out afterwards, and a reader that sets wakings against each other, or tells a clinician
 * where they came from, needs to keep the two apart: a phone picked up is not a recorded night's end.
 */
data class MorningCheck(
    val answer: MorningErection,
    val wokeAt: Long,
    val wake: WakeSource,
    val wokeBy: WokeBy? = null,
    val later: Boolean = false,
    val answeredAt: Long? = null,
    val fromPhone: Boolean = false,
)

/** Only a man is asked. The age gate is the screen's own and stands in front of this. */
fun morningCheckOffered(sex: String?): Boolean = sex == SexAudience.MALE.name

/**
 * The payload, spelt by hand in one fixed order with the optional keys only when they are set: the
 * payload is part of a row's identity in a backup, so the same answer has to be written the same way
 * every time.
 */
internal fun morningCheckPayload(check: MorningCheck): String = buildString {
    append("""{"schema":1,"$MORNING_KEY":"${check.answer.name}","$WOKE_AT_KEY":${check.wokeAt},""")
    append(""""$WAKE_KEY":"${check.wake.name}"""")
    if (check.fromPhone) append(""","$PHONE_KEY":true""")
    check.answeredAt?.let { append(""","$ANSWERED_AT_KEY":$it""") }
    check.wokeBy?.let { append(""","$WOKE_BY_KEY":"${it.name}"""") }
    if (check.later) append(""","$LATER_KEY":true""")
    append("}")
}

/**
 * A check-in read back, or null when its answer is not one this build knows: a row from a newer
 * version is left alone rather than read as some other answer. Read by pattern, as the ticks above
 * are, and a key this build has never heard of is simply never asked for.
 */
fun morningCheckOf(payloadJson: String?): MorningCheck? {
    val json = payloadJson.orEmpty()
    val answer = answerOf(json)
    val wokeAt = wokeAtOf(json)
    return if (answer == null || wokeAt == null) {
        null
    } else {
        MorningCheck(
            answer = answer,
            wokeAt = wokeAt,
            wake = wakeSourceOf(json),
            wokeBy = wokeByOf(json),
            later = LATER_PATTERN.containsMatchIn(json),
            answeredAt = ANSWERED_AT_PATTERN.find(json)?.groupValues?.get(1)?.toLongOrNull(),
            fromPhone = PHONE_PATTERN.containsMatchIn(json),
        )
    }
}

/** A morning check-in row. Its moment is the waking itself, and its day the day he woke on. */
internal fun morningCheckEntry(title: String, day: LocalDate, check: MorningCheck) = JournalEntry(
    day = day.toString(),
    occurredAt = check.wokeAt,
    kind = KIND_MORNING_CHECK,
    title = title,
    calories = 0.0,
    measurementPayloadJson = morningCheckPayload(check),
)

/** The same row, titled in the language showing now, as [masturbationEntry] titles its own. */
internal fun morningCheckEntry(resources: Resources, day: LocalDate, check: MorningCheck) =
    morningCheckEntry(resources.getString(R.string.sexual_morning_title), day, check)

/**
 * Each day's check-in, the one answered last where a day holds two. One is all [logMorningCheck] ever
 * leaves, but a backup restored over a changed answer brings the old one back beside it — the payload
 * is part of what makes two rows the same row — and the answer given last is the one that stands.
 *
 * A row this build cannot read is passed over rather than let hide a morning it can: the day then
 * reads as the newest answer this build knows, or as unanswered.
 */
fun morningChecksByDay(journal: List<JournalEntry>): Map<String, MorningCheck> = journal
    .filter { it.kind == KIND_MORNING_CHECK }
    .groupBy(JournalEntry::day)
    .mapNotNull { (day, rows) -> readableMornings(rows).maxWithOrNull(ANSWER_ORDER)?.let { day to it.second } }
    .toMap()

/**
 * What writing [entry] does to its day: the row to write, carrying the id of the check-in the day
 * already holds so that a second answer corrects the first in place, and the ids of any further ones
 * to delete. Pure, so the one-row-a-day rule has a test; [logMorningCheck] carries it out.
 *
 * Only rows this build can read are reused or deleted. One written by a newer build is left as it is,
 * as [morningCheckOf] promises: rewriting it would lose an answer and keys this build never knew.
 */
internal fun morningCheckReplacement(dayRows: List<JournalEntry>, entry: JournalEntry): Pair<JournalEntry, List<Long>> {
    val existing = readableMornings(dayRows.filter { it.day == entry.day })
    val kept =
        existing.firstOrNull { entry.id != 0L && it.first.id == entry.id } ?: existing.maxWithOrNull(ANSWER_ORDER)
    val written = entry.copy(id = kept?.first?.id ?: entry.id)
    return written to existing.map { it.first.id }.filter { it != written.id }
}

/** The check-ins among [rows] this build can read, each beside its row. */
private fun readableMornings(rows: List<JournalEntry>): List<Pair<JournalEntry, MorningCheck>> = rows
    .filter { it.kind == KIND_MORNING_CHECK }
    .mapNotNull { row -> morningCheckOf(row.measurementPayloadJson)?.let { row to it } }

/**
 * The order a day's check-ins stand in, the one answered last at the end: by
 * [MorningCheck.answeredAt], then by id for rows that do not say. Not by id alone — a restore inserts
 * the rows it brings under fresh, higher ids, so an old answer brought back would outrank the
 * correction made since; nor by the waking, which a changed answer keeps.
 */
private val ANSWER_ORDER = compareBy<Pair<JournalEntry, MorningCheck>>({ it.second.answeredAt }, { it.first.id })

/**
 * When a morning's waking is taken to be, from where, and whether it is only the moment of answering.
 * [fromPhone] marks a waking read off the phone: the first touch or unlock at or after the end of the
 * night the sleep estimate finds ([phoneWakeAt]) — that end itself when a hand closed the gap, later
 * when a screen no hand lit closed it. Measured rather than set, so it is filed as [WakeSource.SLEEP],
 * and the row keeps the mark ([MorningCheck.fromPhone]) so it is never read as a sleep record's end.
 */
internal data class MorningWake(
    val at: Long,
    val source: WakeSource,
    val isNow: Boolean = false,
    val fromPhone: Boolean = false,
)

/**
 * The waking a morning is filed at, in the order a person would expect: a time he set just now,
 * then the one the morning being changed already holds, then the phone's first use after the night
 * ([phoneWakeAt], when the usage access is there), then the end of that day's sleep record — the
 * same earliest end [wakeMinuteOf] reads, without its made-up default — and for today with none of
 * those the moment of answering. A past day with none of them has nothing to offer: null, and the
 * screen asks.
 *
 * The phone comes before the sleep record because it is the moment he picked the phone up, which is
 * the waking being asked about; a record ends where its night did, and a typed night ends on a
 * round number. A time set for today is never later than now; a waking still to come is not one to
 * answer for.
 */
internal fun morningWake(
    day: LocalDate,
    now: ZonedDateTime,
    typedMinute: Int?,
    stored: MorningCheck?,
    recorded: Long?,
    phone: Long? = null,
): MorningWake? {
    val today = day == now.toLocalDate()
    val nowMs = now.toInstant().toEpochMilli()
    return when {
        typedMinute != null -> {
            val at = day.atTime(LocalTime.of(typedMinute / 60, typedMinute % 60))
                .atZone(now.zone).toInstant().toEpochMilli()
            MorningWake(if (today) minOf(at, nowMs) else at, WakeSource.TYPED)
        }

        stored != null -> MorningWake(stored.wokeAt, stored.wake, fromPhone = stored.fromPhone)

        phone != null -> MorningWake(if (today) minOf(phone, nowMs) else phone, WakeSource.SLEEP, fromPhone = true)

        recorded != null -> MorningWake(recorded, WakeSource.SLEEP)

        today -> MorningWake(nowMs, WakeSource.TYPED, isNow = true)

        else -> null
    }
}

/**
 * Where the time picker starts when a past morning has no time to offer: the middle of the recent
 * check-ins' wakings, or seven o'clock before there are any. A starting point only — the morning is
 * not filed until a time has been set in the picker, so the time is one he sets.
 */
internal fun usualWakeMinute(checks: Collection<MorningCheck>, zone: ZoneId): Int {
    val minutes = checks.map { morningMinute(it.wokeAt, zone) }.sorted()
    return minutes.getOrNull(minutes.size / 2) ?: DEFAULT_WAKE_MINUTE
}

/** The minute of the day [at] falls on, on the clock in [zone]. */
internal fun morningMinute(at: Long, zone: ZoneId): Int {
    val time = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
    return time.hour * 60 + time.minute
}

/** "07:12": a waking as the check-in prints it, on the clock where the phone is now. */
internal fun wakeClock(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = clockText(morningMinute(at, zone))

/**
 * Whether an answer about [day] counts as given after it, and so from memory. A morning answered later
 * stays so whatever happens to it; saving again the answer it already had changes nothing about when
 * that was given; any other answer is later when it is given after its day.
 */
internal fun answeredLater(stored: MorningCheck?, answer: MorningErection, day: LocalDate, today: LocalDate): Boolean =
    stored?.later == true || (stored?.answer != answer && today.isAfter(day))

/**
 * The same morning with a different answer, for the calendar's editor: the waking, where it came from
 * and what ended the night all stay as they were, [answeredLater] decides the mark, and [answeredAt]
 * is this save. Null when the day cannot be read or no answer was chosen that this build knows.
 */
internal fun rebuiltMorningCheck(
    title: String,
    previous: JournalEntry,
    answer: MorningErection?,
    today: LocalDate,
    answeredAt: Long,
): JournalEntry? {
    val day = previous.day.toLocalDateOrNull()
    if (answer == null || day == null) return null
    val json = previous.measurementPayloadJson.orEmpty()
    val check = MorningCheck(
        answer = answer,
        wokeAt = wokeAtOf(json) ?: previous.occurredAt,
        wake = wakeSourceOf(json),
        wokeBy = wokeByOf(json),
        later = answeredLater(morningCheckOf(json), answer, day, today),
        answeredAt = answeredAt,
        fromPhone = PHONE_PATTERN.containsMatchIn(json),
    )
    return morningCheckEntry(title, day, check)
}

private const val MORNING_KEY = "morning"
private const val WOKE_AT_KEY = "wokeAt"
private const val WAKE_KEY = "wake"
private const val WOKE_BY_KEY = "wokeBy"
private const val LATER_KEY = "later"
private const val ANSWERED_AT_KEY = "answeredAt"
private const val PHONE_KEY = "phone"

/** Seven o'clock, for [usualWakeMinute] before a single morning has been answered. */
private const val DEFAULT_WAKE_MINUTE = 7 * 60

private val ANSWER_PATTERN = Regex(""""$MORNING_KEY"$SPACE*:$SPACE*"([A-Z_]+)"""")
private val WOKE_AT_PATTERN = Regex(""""$WOKE_AT_KEY"$SPACE*:$SPACE*(-?[0-9]+)""")
private val WAKE_PATTERN = Regex(""""$WAKE_KEY"$SPACE*:$SPACE*"([A-Z_]+)"""")
private val WOKE_BY_PATTERN = Regex(""""$WOKE_BY_KEY"$SPACE*:$SPACE*"([A-Z_]+)"""")
private val LATER_PATTERN = Regex(""""$LATER_KEY"$SPACE*:$SPACE*true""")
private val ANSWERED_AT_PATTERN = Regex(""""$ANSWERED_AT_KEY"$SPACE*:$SPACE*(-?[0-9]+)""")
private val PHONE_PATTERN = Regex(""""$PHONE_KEY"$SPACE*:$SPACE*true""")

private fun answerOf(json: String): MorningErection? =
    ANSWER_PATTERN.find(json)?.groupValues?.get(1).let { name ->
        MorningErection.entries.firstOrNull { it.name == name }
    }

private fun wokeAtOf(json: String): Long? = WOKE_AT_PATTERN.find(json)?.groupValues?.get(1)?.toLongOrNull()

/** A source this build does not know counts as set by hand: coming from the sleep record is the claim. */
private fun wakeSourceOf(json: String): WakeSource =
    WAKE_PATTERN.find(json)?.groupValues?.get(1).let { name -> WakeSource.entries.firstOrNull { it.name == name } }
        ?: WakeSource.TYPED

private fun wokeByOf(json: String): WokeBy? =
    WOKE_BY_PATTERN.find(json)?.groupValues?.get(1).let { name -> WokeBy.entries.firstOrNull { it.name == name } }
