package com.pwnall.health

import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Starting the work timer by where the phone is: the rules, apart from Android, so that a test can
 * run them on a fake clock and fake positions. The alarm, the one fix and the notices that carry
 * them out are in WorkPlaceCheck.kt.
 */

/** How often the place is checked while the day's window is open. */
internal const val WORK_CHECK_EVERY_MS = 10 * 60_000L

/** Away on this many checks in a row ends a session the app started. */
internal const val WORK_AWAY_CHECKS = 2

/** How far back the usual hours are read: four weeks, long enough for a habit, short for a change. */
internal const val WORK_HOURS_LOOKBACK_DAYS = 28L

/** Fewer sessions than this on working days is not yet a pattern of hours. */
internal const val WORK_HOURS_MIN_SESSIONS = 3

/** How far before the usual start the checks begin, and how far after the usual end they stop. */
internal const val WORK_HOURS_MARGIN_MINUTES = 60

/** Where the morning is taken to start when the app has no way to see the wake: seven o'clock. */
internal const val WORK_MORNING_DEFAULT_MINUTE = 7 * 60

/** The latest the checks wait for a wake the phone could show but has not: ten o'clock. */
internal const val WORK_MORNING_LATEST_MINUTE = 10 * 60

/** The earliest the app looks for the wake at all; the sleep estimate's own night ends from four. */
internal const val WORK_WAKE_LOOK_FROM_MINUTE = 5 * 60

/** Where the checks stop on a day the usual hours cannot say: nine in the evening. */
internal const val WORK_EVENING_MINUTE = 21 * 60

/**
 * The earliest the phone alone can say the person woke: six. A glance at the phone at half past
 * four can end the night's longest gap of use and so read as the waking; taken as such, it would
 * have the app take positions every ten minutes while the person sleeps on at home.
 */
internal const val WORK_PHONE_WAKE_FLOOR_MINUTE = WORK_MORNING_DEFAULT_MINUTE - 60

/** How long Undo on an automatic stop holds: long enough to see the notice, short of a new evening. */
internal const val WORK_UNDO_STOP_MS = 60 * 60_000L

/**
 * The widest a saved point may be. The point is the centre every later check is measured from: one
 * saved from a network position 900 m wide can sit 900 m from the desk, and a precise fix at the
 * desk then never lands inside a 150 m radius. 100 m is the smallest radius the ruler offers.
 */
internal const val WORK_SAVE_MAX_ACCURACY_M = 100.0

private const val MINUTES_PER_DAY = 24 * 60

/** Whether a fix is narrow enough to be saved as the workplace; one with no accuracy given is not. */
internal fun workplaceFixPreciseEnough(fix: PlaceFix): Boolean = fix.accuracyM <= WORK_SAVE_MAX_ACCURACY_M

/** The radii a workplace can be given. A building with its car park is about 150 m across. */
internal val WORK_RADII_M = (100..500 step 50).toList()
internal const val WORK_RADIUS_DEFAULT_M = 150

/**
 * The one point the feature keeps: where the person stood when they saved it, how sure Android was
 * of that position, and how far from it still counts as being there.
 */
internal data class WorkPlace(
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val accuracyM: Double,
    val savedAt: Long,
)

/** One position Android gave for a check, never kept: it is compared with the saved point and dropped. */
internal data class PlaceFix(val lat: Double, val lon: Double, val accuracyM: Double)

/** What one fix says about the person and the workplace. */
internal enum class PlaceReading { INSIDE, AWAY, UNSURE }

/**
 * INSIDE only when the fix lies within the radius and is sure enough to say so — a cell-tower fix a
 * kilometre wide that happens to centre near the office is no proof of anybody being there. AWAY
 * only when even the near edge of the fix is outside the radius. Anything between, and no fix at
 * all, is UNSURE, which neither starts nor stops anything.
 */
internal fun placeReading(place: WorkPlace, fix: PlaceFix?): PlaceReading {
    fix ?: return PlaceReading.UNSURE
    val distance = equirectangularMetres(place.lat, place.lon, fix.lat, fix.lon)
    return when {
        distance <= place.radiusM && fix.accuracyM <= 2.0 * place.radiusM -> PlaceReading.INSIDE
        distance - fix.accuracyM > place.radiusM -> PlaceReading.AWAY
        else -> PlaceReading.UNSURE
    }
}

/** The hours the person usually logs on a working day, as minutes of the day. */
internal data class UsualHours(val startMinute: Int, val endMinute: Int)

/**
 * The usual working hours, read from the last [WORK_HOURS_LOOKBACK_DAYS] days of work sessions
 * before [today]: timed and typed alike, since both are how the person records the hours, but not
 * one the timer stopped by itself, whose end is the cap rather than the day.
 *
 * Only sessions that started on a working day count; the days off are the Work settings' own. The
 * start is the lower quartile of the starts and the end the upper quartile of the ends — a wide
 * reading of the usual day, so an early or late one is still inside it, without one odd session
 * stretching it to the whole day. A session past midnight ends at midnight here. Null with fewer
 * than [WORK_HOURS_MIN_SESSIONS] sessions.
 */
internal fun usualWorkHours(
    rows: List<JournalEntry>,
    today: LocalDate,
    zone: ZoneId,
    daysOff: Set<DayOfWeek>,
): UsualHours? {
    val from = today.minusDays(WORK_HOURS_LOOKBACK_DAYS).toString()
    val until = today.toString()
    val spans = rows
        .filter { it.kind == KIND_WORK && it.day >= from && it.day < until && !workFlag(it, WORK_CAPPED) }
        .mapNotNull { row ->
            val segments = workSegmentsOf(row).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val first = segments.minOf { it.from }
            val start = Instant.ofEpochMilli(first).atZone(zone)
            if (start.dayOfWeek in daysOff) return@mapNotNull null
            val startMinute = start.hour * 60 + start.minute
            val length = ((segments.maxOf { it.to } - first) / 60_000L).toInt()
            startMinute to minOf(startMinute + length, MINUTES_PER_DAY)
        }
    if (spans.size < WORK_HOURS_MIN_SESSIONS) return null
    val starts = spans.map { it.first }.sorted()
    val ends = spans.map { it.second }.sorted()
    val quarter = (spans.size - 1) / 4
    return UsualHours(starts[quarter], ends[ends.size - 1 - quarter])
}

/**
 * The morning's wake as the window takes it: the earlier of the end of the day's sleep record
 * ([recorded]) and the phone's first use after the night ([phone]). The phone's reading alone is
 * not believed before [WORK_PHONE_WAKE_FLOOR_MINUTE], or before an hour ahead of the usual start
 * where that comes earlier still: such a reading counts from that floor. A sleep record is the
 * person's own word, and is taken as it is.
 */
internal fun morningWake(recorded: Long?, phone: Long?, day: LocalDate, zone: ZoneId, hours: UsualHours?): Long? {
    val hoursFloor = hours?.let { (it.startMinute - WORK_HOURS_MARGIN_MINUTES).coerceAtLeast(0) }
    val floorMinute = minOf(WORK_PHONE_WAKE_FLOOR_MINUTE, hoursFloor ?: WORK_PHONE_WAKE_FLOOR_MINUTE)
    return listOfNotNull(recorded, phone?.let { maxOf(it, clockOn(day, floorMinute, zone)) }).minOrNull()
}

/**
 * [minute] of [day] as the clock on the wall reads it. Not minutes counted on from midnight: on the
 * days the clocks change, that count runs an hour off the clock from the change on.
 */
private fun clockOn(day: LocalDate, minute: Int, zone: ZoneId): Long =
    day.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()

/**
 * One day's checks. Location is asked for only from [open] to [close]; from [lookFrom] to [open] the
 * app looks only at whether the person is up yet, which takes no position. [dayEnd] is midnight,
 * where the day's round for a session the app started ends. A session still running then is followed
 * again from the next working day's window, unless the sixteen-hour cap has stopped it first.
 */
internal data class WorkCheckWindow(val lookFrom: Long, val open: Long, val close: Long, val dayEnd: Long) {
    /** Whether a check at [now] takes a position: inside the window, or later the same day for [ownRunning]. */
    fun covers(now: Long, ownRunning: Boolean): Boolean = now >= open && now <= (if (ownRunning) dayEnd else close)
}

/**
 * When [day]'s checks run, built from both signals, so that the app never asks for the position
 * all day and night.
 *
 * The morning: the wake ([morningWake]) when the app has seen one. When it could see one but has not yet
 * ([wakeReadable], the usage access the sleep estimate uses), it waits for it until
 * [WORK_MORNING_LATEST_MINUTE]; when it has no way to see one, the morning is
 * [WORK_MORNING_DEFAULT_MINUTE].
 *
 * The hours: with [hours] known, the checks open an hour before the usual start — but never before
 * the morning — and close an hour after the usual end. Without them they run from the morning until
 * [WORK_EVENING_MINUTE]. A day off has no checks; null also when the day leaves no time between the
 * two ends, as when the wake comes after the usual end.
 */
internal fun workCheckWindow(
    day: LocalDate,
    zone: ZoneId,
    daysOff: Set<DayOfWeek>,
    hours: UsualHours?,
    wake: Long?,
    wakeReadable: Boolean,
): WorkCheckWindow? {
    if (day.dayOfWeek in daysOff) return null
    fun at(minute: Int): Long = clockOn(day, minute, zone)
    val hoursOpen = hours?.let { at((it.startMinute - WORK_HOURS_MARGIN_MINUTES).coerceAtLeast(0)) }
    val morning = wake ?: at(if (wakeReadable) WORK_MORNING_LATEST_MINUTE else WORK_MORNING_DEFAULT_MINUTE)
    val open = maxOf(morning, hoursOpen ?: morning)
    val close = hours?.let { at(minOf(it.endMinute + WORK_HOURS_MARGIN_MINUTES, MINUTES_PER_DAY)) }
        ?: at(WORK_EVENING_MINUTE)
    val lookFrom = if (wake == null && wakeReadable) {
        maxOf(at(WORK_WAKE_LOOK_FROM_MINUTE), hoursOpen ?: 0L).coerceAtMost(open)
    } else {
        open
    }
    return WorkCheckWindow(lookFrom, open, close, at(MINUTES_PER_DAY)).takeIf { close > open }
}

/**
 * When the alarm should next go off, or null when no day of the coming week has a window.
 *
 * Never sooner than [WORK_CHECK_EVERY_MS] after the last look, so opening the Work screen or
 * turning the phone on again does not add a check to the ten-minute round. Today it is the next
 * round inside the window — or before it, the look for the wake, or the window's start — while the
 * person is [watching]: something a check could still change. A day the person has taken over, or
 * a window gone by, moves the alarm to the next day's first look.
 *
 * A last look later than [now] is a clock that has been set back since: it counts as a look made
 * now, or the round would stand still until the clock caught up with it.
 */
internal fun nextWorkCheckAt(
    now: Long,
    zone: ZoneId,
    windowOn: (LocalDate) -> WorkCheckWindow?,
    ownRunning: Boolean,
    watching: Boolean,
    lastLookAt: Long,
): Long? {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val soonest = maxOf(now, minOf(lastLookAt, now) + WORK_CHECK_EVERY_MS)
    val window = windowOn(today)
    if (window != null && watching) {
        val close = if (ownRunning) window.dayEnd else window.close
        if (soonest <= close) return maxOf(soonest, window.lookFrom)
    }
    return (1L..7L).firstNotNullOfOrNull { windowOn(today.plusDays(it))?.lookFrom }
}

/**
 * What the checks remember between one alarm and the next, and nothing else: no position.
 *
 * [ownStart] is the start of the session the app started itself and still follows (0 for none),
 * [lastInside] the last check that found the person there, [awayChecks] the run of checks since
 * that found them away, and [quietDay] a day the person has taken over, on which nothing more is
 * started. [stoppedStart] and [stoppedAt] are the session the app last stopped and when it did,
 * which is all an Undo on that stop may put back.
 */
internal data class WorkAutoMemory(
    val ownStart: Long = 0L,
    val lastInside: Long = 0L,
    val awayChecks: Int = 0,
    val quietDay: String? = null,
    val stoppedStart: Long = 0L,
    val stoppedAt: Long = 0L,
)

/** The day taken over by the person: what any tap of theirs, and either Undo, leaves. */
internal fun quietMemory(today: LocalDate): WorkAutoMemory = WorkAutoMemory(quietDay = today.toString())

/**
 * The memory squared with the timer as it stands. A session the app started that is no longer
 * running as it left it — stopped at the sixteen-hour cap, or replaced — means the day is the
 * person's now.
 */
internal fun WorkAutoMemory.settled(session: WorkSession, today: LocalDate): WorkAutoMemory =
    if (ownStart != 0L && (session.state != WorkState.RUNNING || session.sessionStart != ownStart)) {
        quietMemory(today)
    } else {
        this
    }

/**
 * Whether a check could change anything today: the timer is idle on a day the person has not taken
 * over, so an arrival would start it; or the session running is the one the app started, so a
 * departure would stop it. A session the person started, or paused, is theirs, and takes no checks.
 */
internal fun WorkAutoMemory.watching(session: WorkSession, today: LocalDate): Boolean = when (session.state) {
    WorkState.IDLE -> quietDay != today.toString()
    WorkState.RUNNING -> ownStart != 0L && ownStart == session.sessionStart
    WorkState.PAUSED -> false
}

/** What a check does to the timer. */
internal sealed interface WorkAutoAct {
    data object None : WorkAutoAct

    data object Start : WorkAutoAct

    /** The session the app started ends at [end], the last check that found the person there. */
    data class Stop(val end: Long) : WorkAutoAct
}

internal data class WorkAutoOutcome(val memory: WorkAutoMemory, val act: WorkAutoAct)

/**
 * One check, decided.
 *
 * Arriving: with the timer idle on a day still being watched, a check that finds the person there
 * starts it, now — the arrival happened somewhere in the ten minutes before, and the start is set
 * where the app knows it.
 *
 * Leaving: only a session the app started itself, and only while it is running as it was started.
 * Away on [WORK_AWAY_CHECKS] checks in a row stops it, and the session ends at the last check that
 * found the person there: the time after that is not known to have been worked, and a session that
 * says less than was worked is corrected in a moment, where one that says more is believed. One
 * away reading is not enough — a fix can land across the road, and a walk to lunch takes minutes.
 * An unsure reading neither counts towards the run nor breaks it. Coming back later the same day
 * starts a new session; a session the person started is never stopped by a check.
 */
internal fun decideWorkCheck(
    memory: WorkAutoMemory,
    session: WorkSession,
    reading: PlaceReading,
    now: Long,
    today: LocalDate,
): WorkAutoOutcome {
    val settled = memory.settled(session, today)
    return when {
        !settled.watching(session, today) -> WorkAutoOutcome(settled, WorkAutoAct.None)

        session.state == WorkState.IDLE && reading == PlaceReading.INSIDE ->
            WorkAutoOutcome(
                settled.copy(ownStart = now, lastInside = now, awayChecks = 0, stoppedStart = 0L, stoppedAt = 0L),
                WorkAutoAct.Start,
            )

        session.state == WorkState.IDLE -> WorkAutoOutcome(settled, WorkAutoAct.None)

        reading == PlaceReading.INSIDE ->
            WorkAutoOutcome(settled.copy(lastInside = now, awayChecks = 0), WorkAutoAct.None)

        reading == PlaceReading.UNSURE -> WorkAutoOutcome(settled, WorkAutoAct.None)

        else -> {
            val away = settled.awayChecks + 1
            if (away >= WORK_AWAY_CHECKS) {
                WorkAutoOutcome(
                    settled.copy(
                        ownStart = 0L,
                        lastInside = 0L,
                        awayChecks = 0,
                        stoppedStart = settled.ownStart,
                        stoppedAt = now,
                    ),
                    WorkAutoAct.Stop(maxOf(settled.lastInside, settled.ownStart)),
                )
            } else {
                WorkAutoOutcome(settled.copy(awayChecks = away), WorkAutoAct.None)
            }
        }
    }
}

/** The notice a check leaves: the session it started or stopped, and whether an Undo is offered. */
internal sealed interface WorkCheckNotice {
    data class Started(val sessionStart: Long, val undo: Boolean) : WorkCheckNotice

    /** [kept]: the session left a row, so there is something for an Undo to put back. */
    data class Stopped(val sessionStart: Long, val end: Long, val kept: Boolean) : WorkCheckNotice
}

/**
 * What a check's [outcome] does to the timer and the memory, and the notice it leaves. [saveIf]
 * writes the memory only if nothing else wrote it since the check read it
 * ([WorkPlaceStore.saveMemoryIf]); [autoStart] and [autoStop] are the timer's own
 * ([WorkClock.autoStart], [WorkClock.autoStop]).
 */
internal suspend fun carryOutWorkCheck(
    outcome: WorkAutoOutcome,
    session: WorkSession,
    saveIf: (WorkAutoMemory) -> Boolean,
    autoStart: suspend () -> WorkSession?,
    autoStop: suspend (sessionStart: Long, end: Long) -> WorkStep,
): WorkCheckNotice? = when (val act = outcome.act) {
    WorkAutoAct.None -> {
        saveIf(outcome.memory)
        null
    }

    // Started before the memory says so: a screen settling the memory in between would otherwise
    // find the app's own start with an idle timer, and hand the day over.
    WorkAutoAct.Start -> {
        val started = autoStart()
        if (started == null) {
            saveIf(outcome.memory.copy(ownStart = 0L, lastInside = 0L))
            null
        } else {
            // Something of the person's came in meanwhile: the session is theirs, and an Undo
            // would be refused, so the notice says what happened and offers none.
            val followed = saveIf(
                outcome.memory.copy(ownStart = started.sessionStart, lastInside = started.sessionStart),
            )
            WorkCheckNotice.Started(started.sessionStart, undo = followed)
        }
    }

    // A stop is decided only for the session the app started, so its start is the session's. Not
    // written means a tap, an Undo or the switch came first: the session is not ours to stop.
    is WorkAutoAct.Stop ->
        if (saveIf(outcome.memory)) {
            val step = autoStop(session.sessionStart, act.end)
            WorkCheckNotice.Stopped(session.sessionStart, act.end, kept = step.entry != null).takeIf { step.moved }
        } else {
            null
        }
}

private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
    Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

/**
 * Whether the windows an alarm built at [builtAt] still answer at [now], for the reschedule that
 * ends the same round: younger than the next round, and built the same day — a fix that ran past
 * midnight would otherwise book the new day by windows that never read its wake.
 */
internal fun roundWindowsHold(builtAt: Long, now: Long, zone: ZoneId): Boolean =
    now - builtAt in 0L until WORK_CHECK_EVERY_MS && sameDay(builtAt, now, zone)

/**
 * Whether Undo on the start of the session begun at [sessionStart] still applies at [now]: that
 * session is running as the app started it, the app still follows it, and it is the same day. A
 * pause, a resume or any other tap of the person's makes the session theirs (the memory forgets
 * it), and a notice left in the shade must not throw away a working day they have taken over.
 */
internal fun undoStartAccepted(
    memory: WorkAutoMemory,
    session: WorkSession,
    sessionStart: Long,
    now: Long,
    zone: ZoneId,
): Boolean = sessionStart != 0L &&
    session.state == WorkState.RUNNING &&
    session.sessionStart == sessionStart &&
    memory.ownStart == sessionStart &&
    sameDay(sessionStart, now, zone)

/**
 * Whether Undo on the stop of the session begun at [sessionStart] still applies at [now]: it is the
 * session the app stopped last, the timer has stayed idle since, nothing of the person's came in
 * between, and it is within [WORK_UNDO_STOP_MS] of the stop on the same day. Put back later, the
 * session would run on from its morning to the sixteen-hour cap, or replace a day already settled.
 */
internal fun undoStopAccepted(
    memory: WorkAutoMemory,
    session: WorkSession,
    sessionStart: Long,
    now: Long,
    zone: ZoneId,
): Boolean = sessionStart != 0L &&
    session.state == WorkState.IDLE &&
    memory.stoppedStart == sessionStart &&
    now >= memory.stoppedAt &&
    now - memory.stoppedAt <= WORK_UNDO_STOP_MS &&
    sameDay(memory.stoppedAt, now, zone)

/** How long a notice posted at [at] offers its Undo: [limitMs], and never past that day's midnight. */
internal fun undoNoticeTimeoutMs(at: Long, zone: ZoneId, limitMs: Long = Long.MAX_VALUE): Long {
    val midnight = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
    return minOf(limitMs, midnight.toInstant().toEpochMilli() - at).coerceAtLeast(1L)
}

/** The switch, the saved point and the memory, as the settings page draws them. */
internal data class WorkPlaceState(
    val enabled: Boolean = false,
    val place: WorkPlace? = null,
    /** When the alarm is next set for; 0 for none. */
    val nextCheckAt: Long = 0L,
)

/**
 * Where the feature keeps what it keeps, in the app's own settings beside the work timer's: the
 * switch, the one saved point, the memory between two checks, and when the next alarm is set for.
 * Never a position from a check. Not in Room, for the timer's own reasons — a handful of numbers a
 * broadcast reads and writes at once — and so not in a backup file either, nor under the database's
 * encryption; a move to a new phone carries the app's settings, and this with them.
 */
internal object WorkPlaceStore {
    private const val PREFS = "work_place"
    private const val ENABLED = "enabled"
    private const val LAT = "lat"
    private const val LON = "lon"
    private const val RADIUS = "radiusM"
    private const val ACCURACY = "accuracyM"
    private const val SAVED_AT = "savedAt"
    private const val OWN_START = "ownStart"
    private const val LAST_INSIDE = "lastInside"
    private const val AWAY = "awayChecks"
    private const val QUIET_DAY = "quietDay"
    private const val STOPPED_START = "stoppedStart"
    private const val STOPPED_AT = "stoppedAt"
    private const val LAST_LOOK = "lastLookAt"
    private const val NEXT_CHECK = "nextCheckAt"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(context: Context): WorkPlaceState {
        val prefs = prefs(context)
        return WorkPlaceState(
            enabled = prefs.getBoolean(ENABLED, false),
            place = place(prefs),
            nextCheckAt = prefs.getLong(NEXT_CHECK, 0L),
        )
    }

    fun place(context: Context): WorkPlace? = place(prefs(context))

    /** Doubles as their raw bits: a Float would move the point by up to a metre. */
    private fun place(prefs: SharedPreferences): WorkPlace? {
        if (!prefs.contains(LAT) || !prefs.contains(LON)) return null
        return WorkPlace(
            lat = Double.fromBits(prefs.getLong(LAT, 0L)),
            lon = Double.fromBits(prefs.getLong(LON, 0L)),
            radiusM = prefs.getInt(RADIUS, WORK_RADIUS_DEFAULT_M),
            accuracyM = Double.fromBits(prefs.getLong(ACCURACY, 0L)),
            savedAt = prefs.getLong(SAVED_AT, 0L),
        )
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    /**
     * Switched off, the checks also forget what they were following, in the same write: a session
     * the app started is now simply a running timer, the person's to stop.
     *
     * This and the three setters after it are the settings page's taps, on the main thread, so they
     * apply rather than commit. The preferences in memory change before they return, in the order
     * of the taps — what a check reads, and what the guards below hold to — and the file follows on
     * Android's own writer, which an activity waits for when it stops. The radius ruler writes on
     * every mark it passes; committed off the main thread instead, two marks could land out of order.
     */
    @Synchronized
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit {
            putBoolean(ENABLED, on)
            if (!on) memoryKeys.forEach(::remove)
        }
    }

    fun savePlace(context: Context, place: WorkPlace) {
        prefs(context).edit {
            putLong(LAT, place.lat.toRawBits())
            putLong(LON, place.lon.toRawBits())
            putInt(RADIUS, place.radiusM)
            putLong(ACCURACY, place.accuracyM.toRawBits())
            putLong(SAVED_AT, place.savedAt)
        }
    }

    fun setRadius(context: Context, radiusM: Int) {
        prefs(context).edit { putInt(RADIUS, radiusM) }
    }

    /** The point goes, and with it the switch and everything the checks remembered. */
    @Synchronized
    fun forget(context: Context) {
        prefs(context).edit { clear() }
    }

    private val memoryKeys = listOf(OWN_START, LAST_INSIDE, AWAY, QUIET_DAY, STOPPED_START, STOPPED_AT)

    @Synchronized
    fun memory(context: Context): WorkAutoMemory = memory(prefs(context))

    private fun memory(prefs: SharedPreferences) = WorkAutoMemory(
        ownStart = prefs.getLong(OWN_START, 0L),
        lastInside = prefs.getLong(LAST_INSIDE, 0L),
        awayChecks = prefs.getInt(AWAY, 0),
        quietDay = prefs.getString(QUIET_DAY, null),
        stoppedStart = prefs.getLong(STOPPED_START, 0L),
        stoppedAt = prefs.getLong(STOPPED_AT, 0L),
    )

    /**
     * Writes [memory], but only while the feature is on and a point is saved: a check finishing
     * after the switch went off, or after Remove, would otherwise write its memory back into
     * settings that were just cleared.
     */
    @Synchronized
    fun saveMemory(context: Context, memory: WorkAutoMemory) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(ENABLED, false) || place(prefs) == null) return
        prefs.edit(commit = true) {
            putLong(OWN_START, memory.ownStart)
            putLong(LAST_INSIDE, memory.lastInside)
            putInt(AWAY, memory.awayChecks)
            if (memory.quietDay == null) remove(QUIET_DAY) else putString(QUIET_DAY, memory.quietDay)
            putLong(STOPPED_START, memory.stoppedStart)
            putLong(STOPPED_AT, memory.stoppedAt)
        }
    }

    /**
     * Writes [memory] only if what is stored is still [expected], and says whether it did. A check
     * decides on the memory it read, and in the meantime a tap, an Undo or the switch can have
     * written theirs: that one is newer, and wins.
     */
    @Synchronized
    fun saveMemoryIf(context: Context, expected: WorkAutoMemory, memory: WorkAutoMemory): Boolean {
        if (memory(prefs(context)) != expected) return false
        saveMemory(context, memory)
        return true
    }

    /**
     * The memory squared with the timer ([settled]), written back only when that changed it, and
     * in one step with the read: a screen rescheduling while a check decides must not put back the
     * memory as it stood before the check.
     */
    @Synchronized
    fun settle(context: Context, session: WorkSession, today: LocalDate): WorkAutoMemory {
        val stored = memory(prefs(context))
        val settled = stored.settled(session, today)
        if (settled != stored) saveMemory(context, settled)
        return settled
    }

    /**
     * A tap on Start, Pause, Resume or Stop — on the screen, the widget or a notification — hands
     * the rest of the day to the person: nothing more is started today, and a session the app
     * started and they touched is theirs to end. The start or stop notice goes as well, since its
     * Undo would now be about a session that is theirs.
     */
    fun tapped(context: Context, now: Long) {
        NotificationManagerCompat.from(context).cancel(NotificationIds.WORK_PLACE_NOTICE)
        if (!enabled(context)) return
        saveMemory(context, quietMemory(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()))
    }

    fun lastLookAt(context: Context): Long = prefs(context).getLong(LAST_LOOK, 0L)

    fun setLastLookAt(context: Context, at: Long) {
        prefs(context).edit(commit = true) { putLong(LAST_LOOK, at) }
    }

    fun setNextCheckAt(context: Context, at: Long) {
        prefs(context).edit(commit = true) { putLong(NEXT_CHECK, at) }
    }
}
