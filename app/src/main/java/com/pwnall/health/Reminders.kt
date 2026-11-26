package com.pwnall.health

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Year
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Hourly food, water and activity reminders, the morning one for a planned workout, with a fallback
 * check for medicine alarms.
 * Medicine also runs at scheduled times and immediately after a journal write.
 * Everything is decided on-device from the local database.
 */
object Reminders {
    private const val WORK_NAME = "reminders"
    internal const val CHANNEL = "reminders"

    /**
     * Doses and appointments get their own channel at ordinary importance, where the nudges above
     * stay silent: a step reminder that arrives unannounced is a courtesy, and a dose reminder that
     * does the same is a missed dose. One channel each also lets a person mute one and keep the
     * other, which is the whole point of channels.
     */
    internal const val MEDICINE_CHANNEL = "medicine"

    /** Two hours without a step, during waking hours, is worth a nudge. */
    internal const val IDLE_MINUTES = 120L

    internal val QUIET_UNTIL: LocalTime = LocalTime.of(9, 0)
    internal val QUIET_FROM: LocalTime = LocalTime.of(22, 0)
    internal val EVENING_LOG_FROM: LocalTime = LocalTime.of(19, 0)

    suspend fun apply(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            work.cancelUniqueWork(WORK_NAME)
            MedicineAlarms.cancel(context)
            return
        }
        work.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.HOURS).build(),
        )
        MedicineAlarms.refresh(context)
    }
}

/** What the worker decided to say, if anything. Pure so the rules are testable. */
enum class ReminderKind { NONE, GOAL_REACHED, MOVE, LOG_SOMETHING, HEALTH }

/**
 * Decides which single reminder — if any — this hour deserves.
 *
 * At most one, because two notifications in the same hour is how an app gets muted. Goal first: it
 * is the only good news of the three.
 */
fun reminderFor(
    now: LocalTime,
    steps: Long,
    goal: Long,
    goalAlreadyAnnounced: Boolean,
    minutesSinceLastStep: Long?,
    loggedAnythingToday: Boolean,
): ReminderKind {
    if (now < Reminders.QUIET_UNTIL || now >= Reminders.QUIET_FROM) return ReminderKind.NONE
    if (goal > 0 && steps >= goal && !goalAlreadyAnnounced) return ReminderKind.GOAL_REACHED
    if (minutesSinceLastStep != null && minutesSinceLastStep >= Reminders.IDLE_MINUTES) {
        return ReminderKind.MOVE
    }
    if (now >= Reminders.EVENING_LOG_FROM && !loggedAnythingToday) return ReminderKind.LOG_SOMETHING
    return ReminderKind.NONE
}

internal fun consumesGoalAnnouncement(kind: ReminderKind, delivered: Boolean): Boolean =
    kind == ReminderKind.GOAL_REACHED && delivered

/** Food and water are independent of step goals and other journal records. Night does not count. */
internal fun routineReminderDue(
    now: LocalTime,
    lastLogged: LocalTime?,
    lastPushed: LocalTime?,
    gapMinutes: Long,
): Boolean {
    if (now < Reminders.QUIET_UNTIL || now >= Reminders.QUIET_FROM) return false
    val since = maxOf(Reminders.QUIET_UNTIL, lastLogged ?: Reminders.QUIET_UNTIL, lastPushed ?: Reminders.QUIET_UNTIL)
    return ChronoUnit.MINUTES.between(since, now) >= gapMinutes
}

/**
 * The names of today's workouts planned from one of [routines] that are worth a reminder [now]: none
 * in the quiet hours, none once the day's reminder went out, and none of another app's planned
 * sessions — those came in from Health Connect, and their own app does the reminding. Nor a plan
 * whose template is gone, which a database can still hold.
 */
internal fun plannedToRemind(
    today: List<JournalEntry>,
    routines: Set<Long>,
    now: LocalTime,
    reminded: Boolean,
): List<String> =
    if (reminded || now < Reminders.QUIET_UNTIL || now >= Reminders.QUIET_FROM) {
        emptyList()
    } else {
        today.filter { it.kind == KIND_PLAN && it.planRoutineId() in routines }.map { it.title }.distinct()
    }

/**
 * The visit [announced] as [rows] hold it now, marked reminded at [atMs]; null when nothing is left
 * to mark. The row read before the notification went out is not written back whole: an edit saved
 * in the moment between would be undone, and a visit deleted in it would come back. One deleted is
 * left deleted, and one moved to another time or notice is left unmarked, as its editor leaves it,
 * with a reminder of its own to come.
 */
internal fun visitAnnounced(rows: List<Appointment>, announced: Appointment, atMs: Long): Appointment? =
    rows.firstOrNull { it.id == announced.id }
        ?.takeIf { it.atMs == announced.atMs && it.remindDaysBefore == announced.remindDaysBefore }
        ?.copy(remindedAt = atMs)

/**
 * How much of the gap since the last step counts toward [Reminders.IDLE_MINUTES].
 *
 * The night does not. A phone spends it on a nightstand, so by [Reminders.QUIET_UNTIL] the raw gap
 * is already eight hours and the first run of the day would nudge everybody for having slept. Only
 * the part of the gap that falls after the quiet hours end is stillness anybody chose.
 */
fun wakingIdleMinutes(minutesSinceLastStep: Long?, now: LocalTime): Long? {
    val since = minutesSinceLastStep ?: return null
    val awake = ChronoUnit.MINUTES.between(Reminders.QUIET_UNTIL, now).coerceAtLeast(0)
    return minOf(since, awake)
}

/**
 * The minutes since this phone last credited a step, for the "time to move" nudge — or null when
 * that figure says nothing about the person, and the nudge would state "no steps for two hours"
 * of steps the app has no way to know about.
 *
 * It is the phone's own stamp, moved by the pedometer alone. So it stands still while the phone is
 * not [counting] — the step service is not running, or the physical activity permission is gone —
 * and while the steps come from somewhere else: a band, or Health Connect, has then given the day
 * more than the phone has ([mergedSteps] over [localSteps]). A stamp older than a day is a phone
 * that was not carried, not a person who sat for one. Each of those would otherwise send the nudge
 * every hour from eleven until ten at night.
 */
internal fun idleMinutesForNudge(
    lastStepAtMs: Long?,
    nowMs: Long,
    counting: Boolean,
    localSteps: Long,
    mergedSteps: Long,
): Long? {
    val last = lastStepAtMs?.takeIf { it > 0 && counting && mergedSteps <= localSteps } ?: return null
    return ((nowMs - last) / 60_000).takeIf { it < STALE_STEP_MINUTES }
}

/** Past this the last step says the phone was left behind; see [idleMinutesForNudge]. */
private const val STALE_STEP_MINUTES = 24 * 60L

internal class ReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    // One guard per reason there is nothing to say this hour.
    @Suppress("ReturnCount")
    override suspend fun doWork(): Result {
        val db = StepsDb.get(applicationContext)
        val profile = db.dao().profileInEffect() ?: return Result.success()
        if (!profile.remindersEnabled) return Result.success()

        // Medicine goes first, and outside the quiet hours the rules below obey: the times on a
        // course are the person's own instructions, and an appointment is somewhere they have to be.
        val medicineDelivered = MedicineAlarms.refresh(applicationContext, db)
        pushFoodAndWater(db)
        // Asked whether or not a dose went out: behind `||` a dose delivered this run would put the
        // visit off to the next one, an hour later, which for a visit announced an hour ahead is after it.
        val visitDelivered = remindAboutVisit(db)
        if (medicineDelivered || visitDelivered) return Result.success()

        val today = LocalDate.now().toString()
        val now = LocalTime.now()
        if (remindAboutPlan(db, today, now)) return Result.success()
        val steps = db.dao().stepsOn(today) ?: 0L
        val logged = db.dao().journalCountOn(today)
        val kind = reminderFor(
            now = now,
            steps = steps,
            goal = profile.dailyStepGoal,
            goalAlreadyAnnounced = marker(GOAL_KEY) == today,
            minutesSinceLastStep = wakingIdleMinutes(
                idleMinutesForNudge(
                    lastStepAtMs = db.dao().lastStepAtMs(),
                    nowMs = System.currentTimeMillis(),
                    counting = StepService.inForeground && applicationContext.hasStepPermission(),
                    localSteps = db.dao().localStepsOn(today) ?: 0L,
                    mergedSteps = steps,
                ),
                now,
            ),
            loggedAnythingToday = logged > 0,
        )
        if (kind != ReminderKind.NONE) {
            if (consumesGoalAnnouncement(kind, notify(kind, steps))) mark(GOAL_KEY, today)
            return Result.success()
        }

        pushAdvice(db, profile, now, today)
        return Result.success()
    }

    private suspend fun pushFoodAndWater(db: StepsDb) {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone)
        val journal = db.dao().journalOnNow(now.toLocalDate().toString())
        listOf(KIND_EAT to 240L, KIND_WATER to 120L).forEach { (kind, gap) ->
            val key = "routine_$kind"
            // A cup of coffee with milk from the caffeine dialog is food but not a meal: it does not
            // answer "time to eat?" for the next four hours.
            val last = journal.filter {
                (if (kind == KIND_EAT) it.countsAsMeal else it.kind == kind) &&
                    it.occurredAt <= now.toInstant().toEpochMilli()
            }
                .maxOfOrNull(JournalEntry::occurredAt)
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            val pushed = prefs().getLong(key, 0).takeIf { it > 0 }
                ?.let { Instant.ofEpochMilli(it).atZone(zone) }
                ?.takeIf { it.toLocalDate() == now.toLocalDate() }?.toLocalTime()
            if (routineReminderDue(now.toLocalTime(), last, pushed, gap)) {
                val food = kind == KIND_EAT
                val delivered = post(
                    id = if (food) NotificationIds.MEAL else NotificationIds.WATER,
                    title = applicationContext.getString(
                        if (food) R.string.reminder_food_title else R.string.reminder_water_title,
                    ),
                    body = applicationContext.getString(
                        if (food) R.string.reminder_food_body else R.string.reminder_water_body,
                    ),
                )
                if (delivered) prefs().edit { putLong(key, now.toInstant().toEpochMilli()) }
            }
        }
    }

    /**
     * Health advice rides on the same hourly worker but at most once a week, inside the same quiet
     * hours the nudges obey, and never repeats a clinician prompt already shown once.
     */
    private suspend fun pushAdvice(db: StepsDb, profile: UserProfile, now: LocalTime, today: String) {
        if (now < Reminders.QUIET_UNTIL || now >= Reminders.QUIET_FROM) return
        val lastAdvice = marker(ADVICE_KEY)
        val recentlyPushed = lastAdvice != null &&
            ChronoUnit.DAYS.between(LocalDate.parse(lastAdvice), LocalDate.now()) < 7
        if (!recentlyPushed) {
            val days = db.dao().allDays()
            val journal = db.dao().allJournal()
            val advice = AdviceLog.pushable(
                applicationContext,
                allAdvice(days, journal, profile, LocalDate.now(), Year.now().value),
            )
            if (advice != null && notifyAdvice(advice)) {
                mark(ADVICE_KEY, today)
                if (advice.level == AdviceLevel.CLINICIAN) {
                    AdviceLog.remember(applicationContext, advice.id)
                }
            }
        }
    }

    private suspend fun remindAboutVisit(db: StepsDb): Boolean {
        val zone = ZoneId.systemDefault()
        val visit = appointmentToRemind(db.dao().allAppointments(), System.currentTimeMillis(), zone)
            ?: return false
        // Only a reminder that was actually delivered consumes the appointment. Stamping it after a
        // notification the missing permission dropped retires the visit for good: appointmentToRemind
        // never looks at a row with remindedAt set, so granting the permission later brings it back.
        if (!notifyVisit(visit, zone)) return false
        // Stamped on the row as it stands by now, read and written in one transaction ([visitAnnounced]).
        db.withTransaction {
            visitAnnounced(db.dao().allAppointments(), visit, System.currentTimeMillis())
                ?.let { db.dao().saveAppointment(it) }
        }
        return true
    }

    /**
     * The morning of a day a template was planned for: one notification naming the day's workouts,
     * in the first run after the quiet hours. Marked only once delivered, as a visit is.
     */
    private suspend fun remindAboutPlan(db: StepsDb, today: String, now: LocalTime): Boolean {
        val names = plannedToRemind(
            db.dao().journalOnNow(today),
            db.dao().allRoutinesNow().mapTo(HashSet(), Routine::id),
            now,
            reminded = marker(PLAN_KEY) == today,
        )
        if (names.isEmpty()) return false
        val delivered = post(
            id = NotificationIds.PLANNED_WORKOUT,
            title = applicationContext.getString(R.string.routines_reminder_title),
            body = names.joinToString(applicationContext.getString(R.string.list_comma)),
        )
        if (delivered) mark(PLAN_KEY, today)
        return delivered
    }

    /**
     * The same discretion: when and where, without which doctor it is. The date in the phone's own
     * words and digits, the weekday with it, rather than "2026-11-23" in every language.
     */
    private fun notifyVisit(visit: Appointment, zone: ZoneId): Boolean {
        val at = Instant.ofEpochMilli(visit.atMs).atZone(zone)
        return post(
            id = NotificationIds.VISIT,
            title = applicationContext.getString(R.string.visit_reminder_title),
            body = applicationContext.getString(
                R.string.visit_reminder_body,
                DateUtils.formatDateTime(
                    applicationContext,
                    visit.atMs,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
                ),
                clockText(at.hour * 60 + at.minute),
            ),
            channel = Reminders.MEDICINE_CHANNEL,
        )
    }

    private fun notifyAdvice(advice: Advice): Boolean =
        post(
            id = NotificationIds.ADVICE,
            title = applicationContext.getString(advice.titleRes),
            body = applicationContext.resources.formatAdviceBody(advice),
        )

    private fun notify(kind: ReminderKind, steps: Long): Boolean {
        val resources = when (kind) {
            ReminderKind.NONE -> null

            ReminderKind.GOAL_REACHED -> R.string.reminder_goal_title to R.string.reminder_goal_body

            ReminderKind.MOVE -> R.string.reminder_move_title to R.string.reminder_move_body

            ReminderKind.LOG_SOMETHING -> R.string.reminder_log_title to R.string.reminder_log_body

            // Health advice carries its own strings and never reaches this table.
            ReminderKind.HEALTH -> null
        } ?: return false
        val (titleRes, bodyRes) = resources
        val body = if (kind == ReminderKind.GOAL_REACHED) {
            applicationContext.getString(bodyRes, steps.formatted())
        } else {
            applicationContext.getString(bodyRes)
        }
        return post(NotificationIds.reminder(kind), applicationContext.getString(titleRes), body)
    }

    /** Reports whether the notification was actually handed over, which a denied permission is not. */
    private fun post(
        id: Int,
        title: String,
        body: String,
        channel: String = Reminders.CHANNEL,
    ): Boolean {
        val granted =
            applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return false

        val medicine = channel == Reminders.MEDICINE_CHANNEL
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(
                channel,
                applicationContext.getString(
                    if (medicine) R.string.med_reminder_title else R.string.reminders_title,
                ),
                if (medicine) {
                    NotificationManager.IMPORTANCE_DEFAULT
                } else {
                    NotificationManager.IMPORTANCE_LOW
                },
            ),
        )
        if (manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE) return false
        NotificationManagerCompat.from(applicationContext).notify(
            id,
            NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(R.drawable.ic_steps)
                .setContentTitle(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentText(body)
                // Without one the tap does nothing and setAutoCancel below is inert: auto-cancel
                // only fires when a content intent does. The id keeps the reminders from sharing
                // one PendingIntent.
                .setContentIntent(
                    PendingIntent.getActivity(
                        applicationContext,
                        id,
                        openAppIntent(applicationContext),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .setAutoCancel(true)
                .build(),
        )
        return true
    }

    private fun marker(key: String): String? = prefs().getString(key, null)

    private fun mark(key: String, day: String) {
        prefs().edit { putString(key, day) }
    }

    private fun prefs() = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        /**
         * Both markers live on disk rather than in the process. WorkManager starts a fresh process
         * for most of the day's hourly runs, so a process-lifetime field throttles nothing: the
         * goal is re-announced every hour until the evening and non-clinician advice, which leaves
         * no other trace, is re-pushed hourly instead of weekly.
         */
        const val PREFS = "reminder_throttle"

        /** Announce the goal once a day, not once an hour. */
        const val GOAL_KEY = "goal_announced_on"

        /** Health advice is pushed at most weekly, whatever else the worker finds. */
        const val ADVICE_KEY = "advice_pushed_on"

        /** The day's planned workouts are named once, on their morning. */
        const val PLAN_KEY = "plan_reminded_on"
    }
}
