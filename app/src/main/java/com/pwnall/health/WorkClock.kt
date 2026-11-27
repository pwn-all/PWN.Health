package com.pwnall.health

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A stretch of work timed with Start and Stop, written as one journal row when it is stopped.
 *
 * The row holds the session in [JournalEntry.durationMinutes] and the payload alone. It never
 * touches sleepStartAt/sleepEndAt — the Health Connect export turns any row carrying those into a
 * sleep session — so nothing about work leaves the phone. Tapped in the day list it falls through
 * to the editor's read-only card and its Delete button, on purpose: its start and end are edited
 * on the Work screen's own page, which knows about the pauses inside it.
 */
const val KIND_WORK = "WORK"

/** A second tap this soon after the last change was the same tap: a bounce, or a face redrawing. */
internal const val WORK_BOUNCE_MS = 1_500L

/** Net time after which a still-running timer asks whether the person is still working. */
internal const val WORK_REMIND_AFTER_MS = 10 * 3_600_000L

/**
 * Net time at which a forgotten timer stops itself. A row that long is almost always a Stop nobody
 * pressed, so it is kept, marked, and offered for correction rather than left to grow.
 */
internal const val WORK_CAP_MS = 16 * 3_600_000L

/**
 * How long a pause can stand and still be a pause: a night. A Resume after it is the next morning's
 * tap on a timer whose Stop was forgotten — the widget draws Start and Resume with one glyph — and
 * carrying on would make a single row of two working days: filed under the first, past the cap, and
 * longer than a session's page, which holds one turn of the clock, can show. A split shift's
 * afternoon off is shorter, and a session split here by mistake still adds up to the same day.
 */
internal const val WORK_PAUSE_MAX_MS = 8 * 3_600_000L

/** Under a minute of net time is a Start pressed by mistake, and writes nothing. */
internal const val WORK_MIN_ROW_MS = 60_000L

/**
 * The longest stretch a row is believed for. The timer stops itself at [WORK_CAP_MS] and a session's
 * page holds under a day, so two days is past anything the app writes. Everything counted per day
 * walks a stretch one midnight at a time: a stretch out of a hand-edited backup that never ends, or
 * is in microseconds, would keep the Work screen walking it for as long as the row exists.
 */
internal const val WORK_SEGMENT_MAX_MS = 48 * 3_600_000L

/** 10000-01-01 UTC. A row's day is written with a four-digit year, so no stretch reaches it. */
private const val WORK_MOMENT_END_MS = 253_402_300_800_000L

/** The app's own default for a long day: an editable setting, never a health limit. */
internal const val WORK_LONG_DAY_DEFAULT_MINUTES = 600

/** Weekends differ by country; this is only where the chips start. */
internal val WORK_DAYS_OFF_DEFAULT: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

internal enum class WorkState { IDLE, RUNNING, PAUSED }

/** One stretch between a Start (or Resume) and the Pause (or Stop) after it, in epoch ms. */
internal data class WorkSegment(val from: Long, val to: Long) {
    val length: Long get() = (to - from).coerceAtLeast(0L)
}

/**
 * The live session, which exists only between Start and Stop.
 *
 * Wall-clock moments rather than a running counter, so a process that dies or a phone that reboots
 * mid-session loses nothing: the elapsed time is always worked out again from these.
 */
internal data class WorkSession(
    val state: WorkState = WorkState.IDLE,
    val sessionStart: Long = 0L,
    val closed: List<WorkSegment> = emptyList(),
    /** Where the running segment began; 0 unless [state] is [WorkState.RUNNING]. */
    val openSince: Long = 0L,
    /** The last transition, which the bounce guard measures from. */
    val changedAt: Long = 0L,
)

/**
 * What a transition did: the new session, whether anything changed, and the row a Stop wrote — or a
 * Resume that came too late to carry the session on ([WORK_PAUSE_MAX_MS]).
 */
internal data class WorkStep(
    val session: WorkSession,
    val moved: Boolean,
    val entry: JournalEntry? = null,
)

/** Every segment so far, the running one closed at [now] and never shorter than nothing. */
internal fun WorkSession.segmentsAt(now: Long): List<WorkSegment> =
    if (state == WorkState.RUNNING) closed + WorkSegment(openSince, maxOf(now, openSince)) else closed

/** Net time so far, uncapped: what the timer has actually been left running for. */
internal fun WorkSession.netMs(now: Long): Long = segmentsAt(now).sumOf { it.length }

/** The segments as a row would keep them now — the cap applied — for totals that include today. */
internal fun WorkSession.liveSegments(now: Long): List<WorkSegment> = capSegments(segmentsAt(now)).first

private fun WorkSession.unchanged() = WorkStep(this, moved = false)

internal fun WorkSession.start(now: Long): WorkStep =
    if (state != WorkState.IDLE) {
        unchanged()
    } else {
        WorkStep(WorkSession(WorkState.RUNNING, now, emptyList(), now, now), moved = true)
    }

/**
 * A clock that went backwards since the segment began closes it at zero length rather than at a
 * negative one: the time really worked before the jump is the only thing that can be said.
 */
internal fun WorkSession.pause(now: Long): WorkStep =
    if (state != WorkState.RUNNING) {
        unchanged()
    } else {
        val segment = WorkSegment(openSince, maxOf(now, openSince))
        WorkStep(
            copy(state = WorkState.PAUSED, closed = closed + segment, openSince = 0L, changedAt = now),
            moved = true,
        )
    }

/** Never before the last segment ended, so two segments cannot overlap whatever the clock did. */
internal fun WorkSession.resume(now: Long): WorkStep =
    if (state != WorkState.PAUSED) {
        unchanged()
    } else {
        val since = maxOf(now, closed.lastOrNull()?.to ?: sessionStart)
        WorkStep(copy(state = WorkState.RUNNING, openSince = since, changedAt = now), moved = true)
    }

/** Whether the pause standing at [now] has outlasted [WORK_PAUSE_MAX_MS], counted from the Pause itself. */
internal fun WorkSession.pausedTooLong(now: Long): Boolean =
    state == WorkState.PAUSED && now - changedAt >= WORK_PAUSE_MAX_MS

/**
 * A Resume tapped at [now]. After a pause that is no longer one ([pausedTooLong]) it does not carry
 * the session on: the paused session ends where it was paused, as a Stop pressed now would end it,
 * and a new one starts at [now]. One step, so the row and the running session reach the preferences
 * in the same write.
 */
private fun WorkSession.resumeOrRestart(now: Long, title: String, zone: ZoneId): WorkStep =
    if (pausedTooLong(now)) {
        WorkStep(WorkSession().start(now).session, moved = true, entry = stop(now, title, zone).entry)
    } else {
        resume(now)
    }

/** Ends the session; the row it returns is null when there was under a minute to keep. */
internal fun WorkSession.stop(now: Long, title: String, zone: ZoneId): WorkStep =
    if (state == WorkState.IDLE) {
        unchanged()
    } else {
        val (segments, capped) = capSegments(segmentsAt(now))
        WorkStep(
            WorkSession(changedAt = now),
            moved = true,
            entry = workEntry(segments, title, zone, capped = capped),
        )
    }

/** The forgotten-stop rule: a running session that has reached the cap ends itself, at the cap. */
internal fun WorkSession.stopIfCapped(now: Long, title: String, zone: ZoneId): WorkStep =
    if (state == WorkState.RUNNING && netMs(now) >= WORK_CAP_MS) stop(now, title, zone) else unchanged()

/**
 * One tap, from the face it was pressed on. The tap names the state it expects to leave, so a face
 * that is a moment out of date — a widget not yet redrawn, a second tap landing on the button that
 * replaced the first — does nothing instead of flipping the session the wrong way.
 */
internal fun WorkSession.transition(
    from: WorkState,
    to: WorkState,
    now: Long,
    title: String,
    zone: ZoneId,
): WorkStep {
    if (state != from || isDuplicateTap(changedAt.takeIf { it > 0L }, now, WORK_BOUNCE_MS)) return unchanged()
    return when {
        from == WorkState.IDLE && to == WorkState.RUNNING -> start(now)
        from == WorkState.RUNNING && to == WorkState.PAUSED -> pause(now)
        from == WorkState.PAUSED && to == WorkState.RUNNING -> resumeOrRestart(now, title, zone)
        from != WorkState.IDLE && to == WorkState.IDLE -> stop(now, title, zone)
        else -> unchanged()
    }
}

/**
 * The segments cut off where their total reaches [cap], and whether it did. Zero-length segments —
 * a Pause pressed the instant after Resume — are dropped, since they are nothing but noise in the
 * payload and would count as a pause that never was.
 */
internal fun capSegments(segments: List<WorkSegment>, cap: Long = WORK_CAP_MS): Pair<List<WorkSegment>, Boolean> {
    val kept = mutableListOf<WorkSegment>()
    var total = 0L
    for (segment in segments.filter { it.length > 0L }) {
        val room = cap - total
        if (room <= 0L) break
        val taken = minOf(segment.length, room)
        kept += WorkSegment(segment.from, segment.from + taken)
        total += taken
    }
    return kept to (segments.sumOf { it.length } >= cap)
}

/**
 * The journal row for a finished session, or null when under a minute of it is left.
 *
 * The day is the local date the session started on — a shift from 22:00 to 06:00 is one row on
 * the first day — and [workMinutesByDay] is what splits it at midnight for anything counted per day.
 * The start is a real moment, so it is the row's time as it stands, never a day's midday.
 */
internal fun workEntry(
    segments: List<WorkSegment>,
    title: String,
    zone: ZoneId,
    capped: Boolean = false,
    typed: Boolean = false,
): JournalEntry? {
    val kept = segments.filter { it.length > 0L }.sortedBy { it.from }
    val net = kept.sumOf { it.length }
    if (kept.isEmpty() || net < WORK_MIN_ROW_MS) return null
    val start = kept.first().from
    return JournalEntry(
        day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString(),
        occurredAt = start,
        kind = KIND_WORK,
        title = title,
        calories = 0.0,
        durationMinutes = (net / 60_000L).toDouble(),
        measurementPayloadJson = encodeWorkPayload(kept, capped = capped, typed = typed),
    )
}

/**
 * Written by hand in one fixed order, because these bytes are part of the row's backup identity
 * (Backup.kt's journalKey): a payload that reformats itself restores as a second copy of the row.
 * The two flags are written only when set, for the same reason.
 */
internal fun encodeWorkPayload(segments: List<WorkSegment>, capped: Boolean = false, typed: Boolean = false): String =
    buildString {
        append("{\"schema\":1,\"segments\":[")
        segments.joinTo(this, ",") { "[${it.from},${it.to}]" }
        append(']')
        if (capped) append(",\"capped\":true")
        if (typed) append(",\"typed\":true")
        append('}')
    }

/** The key a session carries when the timer stopped itself at [WORK_CAP_MS]. */
internal const val WORK_CAPPED = "capped"

/** The key a session carries when it was typed in afterwards rather than timed. */
internal const val WORK_TYPED = "typed"

/**
 * A work row's segments, read from its payload. A row whose payload is missing or unreadable — an
 * imported one, a hand-edited backup — still counts, as one stretch from its time for its minutes.
 * A stretch nobody could have worked ([believable]) is not read either way: the row stays in the
 * lists, to be opened and set right or deleted, and adds nothing to a total.
 */
internal fun workSegmentsOf(entry: JournalEntry): List<WorkSegment> =
    entry.measurementPayloadJson?.let(::decodePayloadSegments)?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(
            entry.durationMinutes?.takeIf { it > 0.0 }
                ?.let { WorkSegment(entry.occurredAt, entry.occurredAt + (it * 60_000L).toLong()) }
                ?.takeIf { it.believable() },
        )

internal fun decodePayloadSegments(payload: String): List<WorkSegment> = runCatching {
    val array = JSONObject(payload).optJSONArray("segments") ?: return@runCatching emptyList()
    (0 until array.length()).mapNotNull { i ->
        val pair = array.optJSONArray(i) ?: return@mapNotNull null
        if (pair.length() != 2) return@mapNotNull null
        WorkSegment(pair.getLong(0), pair.getLong(1)).takeIf { it.believable() }
    }
}.getOrDefault(emptyList())

/**
 * Whether a stretch read from a row can be counted: its ends in order, both inside the years a
 * row's day can name, and no further apart than [WORK_SEGMENT_MAX_MS]. The order and the range are
 * asked first, so the difference cannot overflow, and no midnight after such a stretch is out of
 * the calendar's reach.
 */
private fun WorkSegment.believable(): Boolean =
    from >= 0L && to in from until WORK_MOMENT_END_MS && to - from <= WORK_SEGMENT_MAX_MS

/** Whether a work row's payload carries [key] set to true: [WORK_CAPPED] or [WORK_TYPED]. */
internal fun workFlag(entry: JournalEntry, key: String): Boolean = workPayloadFlag(entry.measurementPayloadJson, key)

internal fun workPayloadFlag(payload: String?, key: String): Boolean =
    payload?.let { runCatching { JSONObject(it).optBoolean(key, false) }.getOrNull() } ?: false

/** Pauses in a row: the gaps between its segments. */
internal fun workPauseCount(entry: JournalEntry): Int = (workSegmentsOf(entry).size - 1).coerceAtLeast(0)

/**
 * Minutes of work per local date, each segment split at midnight — 23:00 to 02:00 is an hour on
 * the first day and two on the second — for the week chart, and for anything later counted per day.
 * [live] adds stretches that are not among [entries]: the session still running, which has no row
 * yet, and rows whose stretches the caller has read already, as the Work page holds them.
 */
internal fun workMinutesByDay(
    entries: List<JournalEntry>,
    zone: ZoneId = ZoneId.systemDefault(),
    live: List<WorkSegment> = emptyList(),
): Map<LocalDate, Double> {
    val totals = sortedMapOf<LocalDate, Double>()
    val segments = entries.filter { it.kind == KIND_WORK }.flatMap(::workSegmentsOf) + live
    segments.forEach { segment ->
        splitAtMidnight(segment, zone).forEach { (day, ms) ->
            totals[day] = (totals[day] ?: 0.0) + ms / 60_000.0
        }
    }
    return totals
}

/** The part of [segment] that falls on each local date, in ms. */
internal fun splitAtMidnight(segment: WorkSegment, zone: ZoneId): List<Pair<LocalDate, Long>> {
    val parts = mutableListOf<Pair<LocalDate, Long>>()
    var cursor = segment.from
    while (cursor < segment.to) {
        val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        val midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(segment.to, midnight)
        parts += day to (end - cursor)
        cursor = end
    }
    return parts
}

/**
 * A session moved to new ends, keeping the pauses that still lie wholly inside them. Editing when
 * the day started must not throw away the lunch break marked in the middle of it — and the ends the
 * person sets are the ends kept: a pause the new start or end falls into is dropped rather than
 * allowed to move the time they just picked.
 */
internal fun reshapeSegments(segments: List<WorkSegment>, start: Long, end: Long): List<WorkSegment> {
    if (end <= start) return emptyList()
    val pauses = segments.sortedBy { it.from }
        .zipWithNext { a, b -> WorkSegment(a.to, b.from) }
        .filter { it.to > it.from && it.from > start && it.to < end }
    val out = mutableListOf<WorkSegment>()
    var cursor = start
    pauses.forEach { pause ->
        if (pause.from > cursor) out += WorkSegment(cursor, pause.from)
        cursor = maxOf(cursor, pause.to)
    }
    if (end > cursor) out += WorkSegment(cursor, end)
    return out
}

/**
 * Whether a session runs, first start to last end, a day or longer: more than its page can hold.
 * The page keeps the day it started and two times on one turn of the clock, and reads an end
 * earlier than the start as the next morning, so such a session is drawn as its first day alone —
 * and saved as that. The page says so before Save does it.
 */
internal fun workOutgrowsPage(segments: List<WorkSegment>): Boolean =
    segments.isNotEmpty() && segments.maxOf { it.to } - segments.minOf { it.from } >= WORK_PAGE_SPAN_MS

private const val WORK_PAGE_SPAN_MS = 24 * 3_600_000L

/**
 * How far past now a session set by hand may end: one snap of the dial, so the end just worked,
 * rounded up to the next mark, is not refused as a time still to come.
 */
internal const val WORK_FUTURE_GRACE_MS = 5 * 60_000L

/** Why a session set by hand cannot be saved as it stands. */
internal enum class WorkSaveProblem {
    /** It ends in the future: a past session that has not happened yet. */
    FUTURE,

    /** It shares worked time with another session, which every per-day total would count twice. */
    OVERLAP,
}

/**
 * What stops [kept] — the segments a typed or edited session would be saved with — from being
 * saved, or null when nothing does. Other work rows ([editingId]'s own row aside) and the session
 * still on the clock are what it must not overlap; only worked time counts, so a session typed into
 * another one's lunch break is allowed, and one ending as the next begins is not an overlap.
 */
internal fun workSaveProblem(
    kept: List<WorkSegment>,
    editingId: Long?,
    rows: List<JournalEntry>,
    live: WorkSession,
    now: Long,
): WorkSaveProblem? {
    if (kept.isEmpty()) return null
    if (kept.maxOf { it.to } > now + WORK_FUTURE_GRACE_MS) return WorkSaveProblem.FUTURE
    val others = rows.filter { it.kind == KIND_WORK && it.id != editingId }.flatMap(::workSegmentsOf) +
        live.liveSegments(now)
    val overlaps = kept.any { mine ->
        others.any { other -> mine.from < other.to && other.from < mine.to }
    }
    return if (overlaps) WorkSaveProblem.OVERLAP else null
}

/** Closed segments as the preferences hold them: `from:to`, comma separated. */
internal fun encodeWorkSegments(segments: List<WorkSegment>): String =
    segments.joinToString(",") { "${it.from}:${it.to}" }

internal fun decodeStoredSegments(stored: String?): List<WorkSegment> =
    stored.orEmpty().split(',').mapNotNull { part ->
        val bounds = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val from = bounds[0].toLongOrNull() ?: return@mapNotNull null
        val to = bounds[1].toLongOrNull() ?: return@mapNotNull null
        WorkSegment(from, to).takeIf { to >= from }
    }

/** The stopped sessions still waiting for their rows, as the preferences hold them: a payload a line. */
internal fun decodePending(stored: String?): List<String> = stored.orEmpty().split('\n').filter(String::isNotBlank)

/** The queue with [payload] at its end, which a Stop stores in the same write as the idle state. */
internal fun pendingWith(stored: String?, payload: String): String =
    (decodePending(stored) + payload).joinToString("\n")

/**
 * What is left of the queue once [written] have their rows. Taken from the queue as it stands then,
 * not as the writer read it: a Stop committed while Room was being written to has added its payload
 * since, and keeps it.
 */
internal fun pendingWithout(stored: String?, written: List<String>): List<String> =
    decodePending(stored).filter { it !in written }

/**
 * The row a waiting payload stands for, built again from its bytes: the day, the start, the minutes
 * and the payload of the row the Stop returned, so one written before a kill is known afterwards.
 */
internal fun pendingWorkEntry(payload: String, title: String, zone: ZoneId): JournalEntry? = workEntry(
    decodePayloadSegments(payload),
    title,
    zone,
    capped = workPayloadFlag(payload, WORK_CAPPED),
    typed = workPayloadFlag(payload, WORK_TYPED),
)

/** The work row among a day's [rows] that began at [start]: a session is known by its start. */
internal fun workRowAt(rows: List<JournalEntry>, start: Long): JournalEntry? =
    rows.firstOrNull { it.kind == KIND_WORK && it.occurredAt == start }

/**
 * Writes the row of each waiting payload the journal does not hold yet. [rowsOn] reads a day's rows
 * and [save] writes one — Room on the phone, a list in a test. A row already there at the same start
 * with the same stretches is the one an earlier attempt wrote before being killed, and is not written
 * twice. One there with other stretches is the session as it stood before an Undo put it back on the
 * clock, left by a process killed before the Undo could delete it ([WorkClock.reopen]): the waiting
 * row takes its place, under its id, or the time worked since the Undo would be lost.
 */
internal suspend fun writePendingRows(
    payloads: List<String>,
    title: String,
    zone: ZoneId,
    rowsOn: suspend (String) -> List<JournalEntry>,
    save: suspend (JournalEntry) -> Unit,
) {
    payloads.forEach { payload ->
        val entry = pendingWorkEntry(payload, title, zone) ?: return@forEach
        val there = workRowAt(rowsOn(entry.day), entry.occurredAt)
        if (there?.measurementPayloadJson != entry.measurementPayloadJson) save(entry.copy(id = there?.id ?: 0L))
    }
}

/**
 * A stopped session back on the clock, for Undo on an automatic stop: running again from where its
 * last stretch began, so the time since the stop counts as it would have. Null with nothing to
 * put back.
 */
internal fun reopenedSession(segments: List<WorkSegment>, now: Long): WorkSession? =
    segments.takeIf { it.isNotEmpty() }?.let {
        WorkSession(
            state = WorkState.RUNNING,
            sessionStart = it.first().from,
            closed = it.dropLast(1),
            openSince = it.last().from,
            changedAt = now,
        )
    }

/**
 * Undo on an automatic stop, applied to the timer as it stands: [open] takes the clock only while
 * the timer is idle. A session started since the stop is the person's, and is not replaced.
 */
internal fun WorkSession.reopenedWith(open: WorkSession?): WorkStep =
    if (state == WorkState.IDLE && open != null) WorkStep(open, moved = true) else unchanged()

internal fun encodeDaysOff(days: Set<DayOfWeek>): String = days.map { it.value }.sorted().joinToString(",")

internal fun decodeDaysOff(stored: String?): Set<DayOfWeek> =
    stored?.split(',')
        ?.mapNotNull { it.toIntOrNull()?.takeIf { value -> value in 1..7 }?.let(DayOfWeek::of) }
        ?.toSet()
        ?: WORK_DAYS_OFF_DEFAULT

/** Everything the screen and the widget draw from, read in one go. */
internal data class WorkClockState(
    val session: WorkSession = WorkSession(),
    /** The widget's confirmation word, shown until [flashUntil]. */
    val flash: String? = null,
    val flashUntil: Long = 0L,
    val daysOff: Set<DayOfWeek> = WORK_DAYS_OFF_DEFAULT,
    val longDayMinutes: Int = WORK_LONG_DAY_DEFAULT_MINUTES,
)

/**
 * The running session, kept in SharedPreferences rather than Room.
 *
 * There is at most one live session and it is a handful of numbers; a widget callback can read and
 * write it synchronously; it survives a reboot because every value is a wall-clock moment; and the
 * device transfer already carries shared preferences. The durable record is the journal row a Stop
 * writes, which the backup carries like any other. An open journal row while the timer ran would
 * have put a half-finished session in front of every reader of the journal.
 *
 * Every change goes through one synchronized read-transition-write with `commit`, not `apply`: a
 * widget's broadcast can be killed the moment its callback returns.
 */
internal object WorkClock {
    private const val PREFS = "work_clock"
    private const val STATE = "state"
    private const val SESSION_START = "sessionStart"
    private const val SEGMENTS = "segments"
    private const val OPEN_SINCE = "openSince"
    private const val CHANGED_AT = "changedAt"
    private const val FLASH = "flash"
    private const val FLASH_UNTIL = "flashUntil"

    /**
     * Stopped sessions whose row is not written yet, one payload per line. The Stop commits the
     * idle state and this in one write, so a process killed before Room is reached still has the
     * session to write the next time anything here runs.
     */
    private const val PENDING = "pending"
    private const val DAYS_OFF = "daysOff"
    private const val LONG_DAY = "longDayMinutes"

    /** Two writers of one pending row — the widget's tap and the screen opening — take turns. */
    private val writing = Mutex()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): WorkSession = readSession(prefs(context))

    fun snapshot(context: Context): WorkClockState {
        val prefs = prefs(context)
        return WorkClockState(
            session = readSession(prefs),
            flash = prefs.getString(FLASH, null),
            flashUntil = prefs.getLong(FLASH_UNTIL, 0L),
            daysOff = decodeDaysOff(prefs.getString(DAYS_OFF, null)),
            longDayMinutes = prefs.getInt(LONG_DAY, WORK_LONG_DAY_DEFAULT_MINUTES),
        )
    }

    /**
     * Every change to the clock, as it lands. The listener is held by the flow's own close handler:
     * SharedPreferences keeps its listeners weakly, and one held by nothing else stops firing.
     */
    fun flow(context: Context): Flow<WorkClockState> = callbackFlow {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(snapshot(context)) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(snapshot(context))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    private fun readSession(prefs: SharedPreferences): WorkSession {
        val state = prefs.getString(STATE, null)
            ?.let { name -> WorkState.entries.firstOrNull { it.name == name } }
            ?: WorkState.IDLE
        return WorkSession(
            state = state,
            sessionStart = prefs.getLong(SESSION_START, 0L),
            closed = decodeStoredSegments(prefs.getString(SEGMENTS, null)),
            openSince = prefs.getLong(OPEN_SINCE, 0L),
            changedAt = prefs.getLong(CHANGED_AT, 0L),
        )
    }

    @Synchronized
    private fun commit(context: Context, change: (WorkSession) -> WorkStep): WorkStep {
        val prefs = prefs(context)
        val step = change(readSession(prefs))
        if (step.moved) {
            val session = step.session
            val pending = prefs.getString(PENDING, null)
            prefs.edit(commit = true) {
                putString(STATE, session.state.name)
                putLong(SESSION_START, session.sessionStart)
                putString(SEGMENTS, encodeWorkSegments(session.closed))
                putLong(OPEN_SINCE, session.openSince)
                putLong(CHANGED_AT, session.changedAt)
                step.entry?.measurementPayloadJson?.let { putString(PENDING, pendingWith(pending, it)) }
            }
        }
        return step
    }

    /**
     * One tap from the app, the widget or the notification: the transition, the row a Stop leaves,
     * the forgotten-stop check moved to fit, and the widget redrawn.
     */
    suspend fun act(
        context: Context,
        from: WorkState,
        to: WorkState,
        now: Long = System.currentTimeMillis(),
    ): WorkStep {
        val app = context.applicationContext
        val title = app.getString(R.string.work_title)
        val zone = ZoneId.systemDefault()
        // The day handed to the person in the same trip off the main thread, which the Work screen's
        // tap comes from: that memory too is written to disk before the call returns.
        val step = withContext(Dispatchers.IO) {
            commit(app) { it.transition(from, to, now, title, zone) }
                .also { if (it.moved) WorkPlaceStore.tapped(app, now) }
        }
        if (!step.moved) return step
        if (step.session.state == WorkState.RUNNING) {
            WorkOverrun.schedule(app, step.session, now)
        } else {
            WorkOverrun.cancel(app)
        }
        writePending(app)
        refreshWorkWidget(app)
        return step
    }

    /**
     * The workplace check's Start. No bounce guard and no tap to name a state: no finger was
     * involved, and the check runs ten minutes apart. Null when the timer was not idle.
     */
    suspend fun autoStart(context: Context, now: Long): WorkSession? {
        val app = context.applicationContext
        val step = withContext(Dispatchers.IO) { commit(app) { it.start(now) } }
        if (!step.moved) return null
        WorkOverrun.schedule(app, step.session, now)
        refreshWorkWidget(app)
        return step.session
    }

    /**
     * The workplace check's Stop, for the session it started at [sessionStart] and only that one,
     * ended at [end] — the last check that found the person there, which is in the past.
     */
    suspend fun autoStop(context: Context, sessionStart: Long, end: Long): WorkStep {
        val app = context.applicationContext
        val title = app.getString(R.string.work_title)
        val zone = ZoneId.systemDefault()
        val step = withContext(Dispatchers.IO) {
            commit(app) {
                if (it.state == WorkState.RUNNING && it.sessionStart == sessionStart) {
                    it.stop(end, title, zone)
                } else {
                    it.unchanged()
                }
            }
        }
        if (step.moved) {
            WorkOverrun.cancel(app)
            writePending(app)
            refreshWorkWidget(app)
        }
        return step
    }

    /**
     * Undo on an automatic start: the session started at [sessionStart] goes without a row, as if
     * it had never begun. Anything else on the clock by now — a session the person started since —
     * is left alone.
     */
    suspend fun discard(context: Context, sessionStart: Long, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        val step = withContext(Dispatchers.IO) {
            commit(app) {
                if (it.state != WorkState.IDLE && it.sessionStart == sessionStart) {
                    WorkStep(WorkSession(changedAt = now), moved = true)
                } else {
                    it.unchanged()
                }
            }
        }
        if (step.moved) {
            WorkOverrun.cancel(app)
            refreshWorkWidget(app)
        }
        return step.moved
    }

    /**
     * Undo on an automatic stop: the row the stop wrote goes back on the clock, running again from
     * its last stretch, so the time since the stop counts as it would have. Only while the timer is
     * idle — a session started since is not replaced.
     *
     * The session is put back before the row is deleted. Killed between the two, the row is still
     * there and the timer running, which the next Stop sorts out: its row, at the same start, takes
     * the old one's place ([writePendingRows]). The other order would lose the session.
     */
    suspend fun reopen(context: Context, sessionStart: Long, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        writePending(app)
        val db = StepsDb.get(app)
        val day = Instant.ofEpochMilli(sessionStart).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val row = workRowAt(db.dao().journalOnNow(day), sessionStart)
        val open = reopenedSession(row?.let(::workSegmentsOf).orEmpty(), now)
        val step = withContext(Dispatchers.IO) {
            commit(app) { it.reopenedWith(open) }
        }
        if (step.moved && row != null) {
            db.deleteJournalEvent(row.id)
            WorkOverrun.schedule(app, step.session, now)
            refreshWorkWidget(app)
        }
        return step.moved
    }

    /**
     * Ends a session that has run past the cap, without touching the scheduled check: the check is
     * the usual caller, and cancelling its own unique work from inside it would cancel the check.
     */
    suspend fun stopIfCapped(context: Context, now: Long = System.currentTimeMillis()): WorkStep {
        val app = context.applicationContext
        val title = app.getString(R.string.work_title)
        val zone = ZoneId.systemDefault()
        val step = withContext(Dispatchers.IO) { commit(app) { it.stopIfCapped(now, title, zone) } }
        if (step.moved) {
            writePending(app)
            refreshWorkWidget(app)
        }
        return step
    }

    /**
     * What opening the screen settles: a session left running past the cap is stopped at it — the
     * check that should have done so may have been held back by the battery saver — and any row a
     * killed process left unwritten is written. [into] is the database the rows go to where it is
     * not the app's own: WorkClockSettleTest's, in memory.
     */
    suspend fun settle(context: Context, into: StepsDb? = null) {
        val app = context.applicationContext
        if (stopIfCapped(app).moved) WorkOverrun.cancel(app)
        writePending(app, into)
    }

    /**
     * Writes the stopped sessions still waiting in the preferences, each once ([writePendingRows]).
     * The database is opened only once there is something to write: a Start from the widget
     * comes through here too, and has no use for it.
     */
    private suspend fun writePending(context: Context, into: StepsDb? = null) = writing.withLock {
        val payloads = decodePending(prefs(context).getString(PENDING, null))
        if (payloads.isEmpty()) return@withLock
        val db = into ?: StepsDb.get(context)
        writePendingRows(
            payloads,
            context.getString(R.string.work_title),
            ZoneId.systemDefault(),
            rowsOn = { day -> db.dao().journalOnNow(day) },
            save = { entry -> db.saveJournalEvent(entry) },
        )
        forgetPending(context, payloads)
    }

    @Synchronized
    private fun forgetPending(context: Context, written: List<String>) {
        val prefs = prefs(context)
        val left = pendingWithout(prefs.getString(PENDING, null), written)
        prefs.edit(commit = true) {
            if (left.isEmpty()) remove(PENDING) else putString(PENDING, left.joinToString("\n"))
        }
    }

    fun showFlash(context: Context, text: String, until: Long) {
        prefs(context).edit(commit = true) {
            putString(FLASH, text)
            putLong(FLASH_UNTIL, until)
        }
    }

    /** Takes the flash down only if it is still the one [until] put up; a newer tap keeps its own. */
    @Synchronized
    fun clearFlash(context: Context, until: Long) {
        val prefs = prefs(context)
        if (prefs.getLong(FLASH_UNTIL, 0L) != until) return
        prefs.edit(commit = true) {
            remove(FLASH)
            remove(FLASH_UNTIL)
        }
    }

    fun setDaysOff(context: Context, days: Set<DayOfWeek>) {
        prefs(context).edit { putString(DAYS_OFF, encodeDaysOff(days)) }
    }

    fun setLongDayMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(LONG_DAY, minutes) }
    }
}
