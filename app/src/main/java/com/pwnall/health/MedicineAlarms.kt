package com.pwnall.health

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A date is part of a dose's identity, including when its reminder crosses midnight.
 *
 * [key] is what the dose is announced under, and doses that fall due together for the same reason —
 * one day, one slot, one sentence — share it: they are one notification, one ring, one "Later" and
 * one record of what was said. A reminder never names a medicine, so three courses at eight would
 * otherwise be three cards alike word for word, three alarms at once, and three "I took it" buttons
 * that each marked a course nobody could tell from its card.
 */
internal data class DatedMedNudge(val day: LocalDate, val nudge: MedNudge) {
    val key: String get() = "dose:$day:${nudge.dose.slot}:${nudge.reason.name}"
}

/**
 * Every dose worth speaking about right now, one per course and slot.
 *
 * [snoozedUntil] is keyed the way [DatedMedNudge.key] spells an announcement: doses put off by hand
 * are not due again until the moment it names. It is a parameter rather than a lookup so the rule
 * stays testable without a Context.
 */
internal fun dueMedicine(
    medications: List<Medication>,
    journal: List<JournalEntry>,
    nowMs: Long,
    zone: ZoneId,
    snoozedUntil: Map<String, Long> = emptyMap(),
): List<DatedMedNudge> {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val today = now.toLocalDate()
    val minute = now.hour * 60 + now.minute
    val past = journal.filter { it.occurredAt <= nowMs }
    val meal = lastMealMinuteOf(past, today, zone)
    val food = lastMealMinuteOf(past, today, zone, cups = true)
    return listOf(today.minusDays(1), today).flatMap { day ->
        val doses = plannedDoses(medications, journal, day, wakeMinuteOf(journal, day, zone))
        val previousMeal = lastMealMinuteOf(past, day, zone)
        val previousFood = lastMealMinuteOf(past, day, zone, cups = true)
        doses.mapNotNull { dose ->
            val nudge = if (day == today) {
                medNudge(listOf(dose), minute, meal, lastFoodMinute = food)
            } else {
                medNudge(emptyList(), minute, meal, listOf(dose), previousMeal, food, previousFood)
            }
            nudge?.let { DatedMedNudge(day, it) }
        }
    }.filterNot { nowMs < (snoozedUntil[it.key] ?: Long.MIN_VALUE) }
}

/**
 * The next clock slot, food fallback or retry. Recompute at midnight for wake-time schedules.
 *
 * [followUps] are the moments a dose already spoken about is owed its next word — see
 * [MedicineAlarms.followUpChecks]. They are counted from when it was spoken about, so like a dose
 * put off by hand they sit on no ladder counted from the dose's own time.
 */
internal fun nextMedicineCheck(
    medications: List<Medication>,
    journal: List<JournalEntry>,
    nowMs: Long,
    zone: ZoneId,
    snoozedUntil: Map<String, Long> = emptyMap(),
    followUps: List<Long> = emptyList(),
): Long? {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    // A course whose last day cannot be read is running, as [Medication.activeOn] plans its doses: compared
    // as text, it would have doses planned that no alarm was booked for.
    if (medications.none { !it.endedBefore(today.minusDays(1)) }) return null
    val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val candidates = listOf(today.minusDays(1), today, today.plusDays(1)).flatMap { day ->
        plannedDoses(medications, journal, day, wakeMinuteOf(journal, day, zone))
            .filterNot(PlannedDose::taken)
            .flatMap { dose ->
                val local = day.atTime(dose.minuteOfDay / 60, dose.minuteOfDay % 60)
                // A time the clocks skip is due when they pass it, as [dueMedicine] reads the wall
                // clock; placed in the zone it would move a gap's length on, and 02:30 would ring at 03:30.
                val skipped = zone.rules.getTransition(local)?.takeIf { it.isGap }?.instant
                val due = (skipped ?: local.atZone(zone).toInstant()).toEpochMilli()
                val offsets = if (dose.food == MedFood.AFTER || dose.food == MedFood.WITH) {
                    listOf(0, 60, MED_MEAL_WAIT_MINUTES, 120, 180, MED_GRACE_MINUTES)
                } else {
                    listOf(0, 60, 120, 180, MED_GRACE_MINUTES)
                }
                offsets.map { due + it * 60_000L }
            }
    }
    // A dose put off by hand is due again at the moment it was put off until, which is not on any
    // ladder counted from its own time.
    val snoozes = snoozedUntil.values
    return (candidates + snoozes + followUps + tomorrow).filter { it > nowMs }.minOrNull()
}

/**
 * How loudly a dose is about to be spoken about, or not at all.
 *
 * [QUIET] exists because an alarm-sounding notification carries a one-minute life so the ringing
 * stops on its own — see [MedicineAlarmNotification.RING_MS] — and a person who was in a meeting
 * for that minute would otherwise be left with nothing at all in the shade until the next hour came round.
 * The loud minute is followed by a silent card that simply stays.
 */
internal enum class DoseAnnouncement { NONE, LOUD, QUIET }

/** Clock alarms, the hourly fallback and journal writes share one serialized delivery path. */
internal object MedicineAlarms {
    private val mutex = Mutex()
    private const val REPEAT_MS = 60 * 60_000L

    /**
     * How long after a ringing announcement its silent card goes up: the ring's minute and a little
     * more. The system counts that minute from when the notification reached it, a moment after the
     * time written down here, and a card posted over a notification that is still ringing can be
     * taken away by the timer the ring left running.
     */
    private const val RING_OVER_MS = MedicineAlarmNotification.RING_MS + 2_000L

    private const val PREFS = "medicine_alarms"

    /** What a dose put off by hand is filed under, beside the record of what was last said about it. */
    private const val SNOOZE_PREFIX = "snooze:"

    /** How long "Later" puts a dose off for. One quarter of an hour, and never past its grace. */
    const val SNOOZE_MS = 15 * 60_000L

    suspend fun refresh(context: Context, db: StepsDb = StepsDb.get(context)): Boolean = mutex.withLock {
        val enabled = db.dao().profileNow()?.remindersEnabled == true
        if (!enabled) {
            cancel(context)
            return@withLock false
        }
        val nowMs = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val medications = db.dao().allMedications()
        val journal = db.dao().journalOnNow(today.toString()) +
            db.dao().journalOnNow(today.minusDays(1).toString())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snoozes = snoozes(prefs)
        val due = dueMedicine(medications, journal, nowMs, zone, snoozes)
        // A snoozed dose is not due, so it must not be cancelled out of existence either: it is
        // still coming back, and its record has to survive the sweep below to come back with.
        val live = (due.map { it.key } + snoozes.filterValues { it > nowMs }.keys).toSet()
        MedicineAlarmNotification.cancelExcept(context, due.map { it.key }.toSet())
        // Remove old dates without forgetting a dose that remains eligible across midnight.
        prefs.edit {
            prefs.all.keys.filterNot { it.removePrefix(SNOOZE_PREFIX) in live }.forEach(::remove)
        }
        var delivered = false
        // Whether a dose rings, not only whether the switch is on: with the alarm's channel muted
        // the dose is the card, and is written down as the card ([doseRings]).
        val alarm = MedicineAlarmNotification.rings(context)
        // The doses that share a key are announced once, as one ([DatedMedNudge.key]).
        due.groupBy { it.key }.forEach { (key, doses) ->
            val reason = doses.first().nudge.reason.name
            val last = prefs.getString(key, null)
            val announcement = announcementFor(reason, last, nowMs, alarm, snoozes[key], doses.size)
            if (announcement != DoseAnnouncement.NONE) {
                val loud = announcement == DoseAnnouncement.LOUD
                val dose = doses.first()
                val later = snoozable(dose, nowMs, zone)
                if (MedicineAlarmNotification.post(context, dose, loud, several = doses.size > 1, offerLater = later)) {
                    prefs.edit { putString(key, "$reason|$nowMs|$loud|${doses.size}") }
                    delivered = true
                }
            }
        }
        // Read back after the loop, so that what was said just now is followed up as well.
        val followUps = due.flatMap { followUpChecks(prefs.getString(it.key, null)) }
        schedule(context, nextMedicineCheck(medications, journal, nowMs, zone, snoozes, followUps))
        delivered
    }

    /**
     * Whether this dose is announced now, and how.
     *
     * [last] is what was written down about the previous announcement — `REASON|atMs|loud|doses` — or
     * null where nothing has been said yet. A new reason, a clock that went backwards and the hourly
     * repeat all speak at full volume; the one quiet case is the minute after a ringing one, whose
     * notification the system has by then deleted.
     *
     * [snoozedUntil] is the moment "Later" put this dose off until, where it did. What was said
     * before that moment no longer counts: the person asked to hear about the dose again then, and
     * holding the earlier announcement against it would keep the reminder they asked for silent until
     * its hour was up — which, in the last hour of the dose's grace, is never.
     *
     * [doses] is how many share the announcement now ([DatedMedNudge.key]). One that spoke for fewer
     * — a course added since for the same time, a mark undone — does not count either: the card in
     * the shade would say one dose while its "I took it" marked two. A record that names no number
     * spoke for one.
     */
    internal fun announcementFor(
        reason: String,
        last: String?,
        nowMs: Long,
        alarmEnabled: Boolean,
        snoozedUntil: Long? = null,
        doses: Int = 1,
    ): DoseAnnouncement {
        val parts = last?.split('|')?.takeIf { (it.getOrNull(3)?.toIntOrNull() ?: 1) >= doses }
        val lastReason = parts?.getOrNull(0)
        val lastMs = parts?.getOrNull(1)?.toLongOrNull()?.takeIf { snoozedUntil == null || it >= snoozedUntil }
        val wasLoud = parts?.getOrNull(2)?.toBooleanStrictOrNull() == true
        if (shouldAnnounceDose(reason, lastReason, nowMs, lastMs)) {
            return if (alarmEnabled) DoseAnnouncement.LOUD else DoseAnnouncement.QUIET
        }
        // The replacement card, once and only once, for the ringing one the system has removed.
        if (wasLoud && lastMs != null && nowMs - lastMs >= RING_OVER_MS) {
            return DoseAnnouncement.QUIET
        }
        return DoseAnnouncement.NONE
    }

    internal fun shouldAnnounceDose(reason: String, lastReason: String?, nowMs: Long, lastMs: Long?): Boolean =
        reason != lastReason || lastMs == null || nowMs < lastMs || nowMs - lastMs >= REPEAT_MS

    /**
     * When a dose is owed its next word, counted from [last], the record of when it was last spoken
     * about: the card that takes a ring's place, and the hourly repeat.
     *
     * [announcementFor] measures both waits from that record, and the moment in it is on no ladder
     * counted from the dose's own time. A meal logged at seventeen minutes past rings then, and an
     * alarm that had a process to start writes down a moment some hundreds of milliseconds after
     * the hour. A check scheduled from the dose's time comes a hair early for either, says nothing,
     * and the shade stays empty until the next rung, an hour on.
     */
    internal fun followUpChecks(last: String?): List<Long> {
        val parts = last?.split('|').orEmpty()
        val lastMs = parts.getOrNull(1)?.toLongOrNull() ?: return emptyList()
        val wasLoud = parts.getOrNull(2)?.toBooleanStrictOrNull() == true
        return listOfNotNull((lastMs + RING_OVER_MS).takeIf { wasLoud }, lastMs + REPEAT_MS)
    }

    private fun snoozes(prefs: SharedPreferences): Map<String, Long> = prefs.all
        .filterKeys { it.startsWith(SNOOZE_PREFIX) }
        .mapNotNull { (key, value) ->
            (value as? Long)?.let { key.removePrefix(SNOOZE_PREFIX) to it }
        }
        .toMap()

    /**
     * Writes down that the doses behind a notification were taken, from the notification itself:
     * every dose it stands for ([DatedMedNudge.key]), which is what its text says of a shared one.
     *
     * Nothing else is needed to call the reminder off: a MED row triggers the refresh above, which
     * finds the doses marked, drops them out of [dueMedicine] and cancels their notification.
     *
     * The notification goes once the record has been read, whatever it says. A key that names no dose
     * left to mark — marked a moment before, from the tab, the widget or a tap the shade let through
     * twice, or past its grace before the check that takes its card down came round — would otherwise
     * leave the card up with a button that did nothing.
     */
    suspend fun markTaken(context: Context, key: String, db: StepsDb = StepsDb.get(context)): Boolean {
        val doses = dueNow(db).filter { it.key == key }
        MedicineAlarmNotification.cancel(context, key)
        if (doses.isEmpty()) return false
        val atMs = System.currentTimeMillis()
        db.markDosesTaken(doses.map { medTakenEntry(it.nudge.dose, it.day, atMs) })
        return true
    }

    /**
     * Puts a dose off for [SNOOZE_MS], unless that would carry it past the window it is still worth
     * mentioning in.
     *
     * Refused rather than clamped: a dose whose grace runs out in four minutes has nothing useful to
     * offer a quarter of an hour from now, and an unbounded "later" is how a dose leaves the day
     * silently and the adherence record becomes fiction.
     *
     * Refused, the tap still does something. The card stays, since the dose is still due, but goes up
     * again quiet and without the button it cannot keep; a key that names no dose still due takes its
     * card down, as [markTaken] does. Either way the card changes: left as it was, a ring would go on
     * ringing.
     */
    suspend fun snooze(context: Context, key: String, db: StepsDb = StepsDb.get(context)): Boolean {
        val doses = dueNow(db).filter { it.key == key }
        // Any of the doses the key stands for: they share the day and the slot, and so the grace.
        val dose = doses.firstOrNull()
        val nowMs = System.currentTimeMillis()
        when {
            dose == null -> MedicineAlarmNotification.cancel(context, key)

            !snoozable(dose, nowMs, ZoneId.systemDefault()) -> MedicineAlarmNotification.post(
                context,
                dose,
                loud = false,
                several = doses.size > 1,
                offerLater = false,
            )

            else -> {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit { putLong(SNOOZE_PREFIX + key, nowMs + SNOOZE_MS) }
                MedicineAlarmNotification.cancel(context, key)
                refresh(context, db)
                return true
            }
        }
        return false
    }

    /**
     * Whether "Later" can be kept at [nowMs]: the dose is still within its grace when its quarter of
     * an hour is up. Counted on the wall clock, as [dueMedicine] counts the grace — the minutes from
     * the dose's midnight to the time the clock will show. Counted from that midnight in milliseconds
     * it would be an hour off on the days the clocks change: in spring "Later" would be taken for a dose
     * let go before it came back, and in autumn refused an hour early.
     */
    internal fun snoozable(dose: DatedMedNudge, nowMs: Long, zone: ZoneId): Boolean {
        val back = Instant.ofEpochMilli(nowMs + SNOOZE_MS).atZone(zone).toLocalDateTime()
        val late = ChronoUnit.MINUTES.between(dose.day.atStartOfDay(), back) - dose.nudge.dose.minuteOfDay
        return late <= MED_GRACE_MINUTES
    }

    /** The doses due just now: what a notification's tag names is recomputed from the record, not carried in it. */
    private suspend fun dueNow(db: StepsDb): List<DatedMedNudge> {
        val nowMs = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val journal = db.dao().journalOnNow(today.toString()) +
            db.dao().journalOnNow(today.minusDays(1).toString())
        return dueMedicine(db.dao().allMedications(), journal, nowMs, zone)
    }

    /**
     * Calls every dose off: the alarm, the notifications, and the record of what they said. That
     * record is what keeps a dose from being said again within the hour, and kept once its card is
     * gone it would keep the doses quiet in an empty shade when reminders come back on. What was put off
     * by hand stays put off.
     */
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
        MedicineAlarmNotification.cancelExcept(context, emptySet())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { prefs.all.keys.filterNot { it.startsWith(SNOOZE_PREFIX) }.forEach(::remove) }
    }

    private fun schedule(context: Context, atMs: Long?) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = pending(context)
        if (atMs == null) {
            manager.cancel(operation)
            return
        }
        if (manager.canScheduleExactAlarms()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
                return
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and scheduling.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
    }

    /**
     * Asks for the check once more in [RETRY_MS], after one that threw or ran out of its broadcast's
     * seconds. [schedule] is the last thing a refresh does, so such a check has booked nothing, and the
     * alarm that started it is spent or, after a reboot, was never there: the doses would wait for the
     * hourly worker, or for the app to be opened.
     *
     * An alarm of its own, under [ACTION_RETRY], rather than the check's: the broadcast may have
     * been a clock change with a dose's exact alarm still standing a minute away, and booking over
     * that one would ring it late. Inexact, which needs no permission that can be missing.
     */
    fun retrySoon(context: Context) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + RETRY_MS,
            pending(context, ACTION_RETRY),
        )
    }

    private fun pending(context: Context, action: String = ACTION_CHECK): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, MedicineAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    const val ACTION_CHECK = "com.pwnall.health.CHECK_MEDICINE"

    /**
     * The check made again after one that failed ([retrySoon]). Its own action so that it is told
     * apart when it fails too: a retry books no retry, or a fault that does not pass — a key store
     * gone for good — would wake the phone every few minutes for as long as it lasted.
     */
    const val ACTION_RETRY = "com.pwnall.health.RETRY_MEDICINE"

    /** How long a check that failed waits to be made again. */
    private const val RETRY_MS = 5 * 60_000L
}

/**
 * Marks doses as taken, each once: the rows are [medTakenEntry]'s, and one whose course and slot its
 * day already holds a mark for ([markedDoseOf]) is left unwritten. Answers how many were written.
 *
 * The look and the write are one transaction, as they are for the one-tap loggers in Data.kt and at
 * the chat's door. Apart, the reminder's "I took it", the alarm page's button, the dose widget's Taken
 * and the Medicine tab would each read "not marked yet" and then write, and two taps close together
 * — a bounce in the shade, a widget tapped twice while the process was still starting — would both
 * read it before either had written: two rows for one dose in the day's list and the backup, and an
 * Undo that left the dose marked.
 *
 * Not cancellable once begun, as [saveJournalEvent] is not. Inside the transaction that function
 * holds back the alarms' check and the widgets' redraw, so both follow here, after the commit.
 */
internal suspend fun StepsDb.markDosesTaken(entries: List<JournalEntry>): Int = withContext(NonCancellable) {
    val written = withTransaction {
        entries.count { entry ->
            val open = markedDoseOf(entry, dao().journalOnNow(entry.day)) == null
            if (open) saveJournalEvent(entry)
            open
        }
    }
    if (written > 0) {
        refreshMedicineAfterWrite()
        refreshWidgetsAfterWrite(KIND_MED)
    }
    written
}

/** Also restores alarms after reboot, an app update, a clock change or an exact-alarm grant. */
class MedicineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        val app = context.applicationContext
        val first = intent.action != MedicineAlarms.ACTION_RETRY
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Nothing thrown here may take the process down — a key store silent after boot, a
                // database that would not open: the step service lives in it. And a check that
                // failed or was cut short has booked no next one, so it is asked for once more.
                runCatchingCancellable { withTimeout(8_000) { MedicineAlarms.refresh(app) } }
                    // The retry is caught too: an alarm the system refuses to book throws out here.
                    .onFailure { if (first) runCatching { MedicineAlarms.retrySoon(app) } }
                    // Caught is not the same as unseen: a check that keeps failing leaves the doses
                    // to the hourly worker, and the log is the one place that says why.
                    .onFailure { Log.w("MedicineAlarms", "the dose check failed", it) }
            } finally {
                pending.finish()
            }
        }
    }

    /** Internal for the test that holds the manifest's filter to it. */
    internal companion object {
        val ACTIONS = setOf(
            MedicineAlarms.ACTION_CHECK,
            MedicineAlarms.ACTION_RETRY,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
