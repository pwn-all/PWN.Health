package com.pwnall.health

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * The workplace check, carried out: an exact alarm every ten minutes inside the day's window, and,
 * when a position could change something, one fix taken in a short foreground service.
 *
 * Why this shape, on Android 13 to 17 with no Google Play services:
 *  - An exact alarm (`setExactAndAllowWhileIdle`) is the one timer that still fires on time under
 *    Doze. WorkManager's periodic work runs no more often than every fifteen minutes and waits for
 *    Doze's maintenance windows, which come further apart the longer a phone lies still. A
 *    ten-minute round stays inside the quota Doze allows such alarms.
 *  - The fix is taken in a foreground service of the `location` type, started by that alarm: an
 *    exact alarm is one of the platform's exemptions from the ban on starting a foreground service
 *    from the background. A broadcast receiver could ask for the position itself, but Android
 *    computes positions for an app in the background only a few times an hour, so a fresh fix every
 *    ten minutes is not one it can count on; a location foreground service makes the request a
 *    foreground one. It lives for one fix — at most [WORK_FIX_TIMEOUT_MS] — and Android defers a
 *    foreground service's notification by ten seconds, so a quick fix usually shows none.
 *  - A service started from the background is given location only with the all-the-time
 *    permission, which is why the app asks for it here and for nothing else.
 *  - A geofence does not fit either. The platform's own proximity alert asks for positions
 *    around the clock, which is exactly what the window is there to avoid, and the geofencing that
 *    batches them is Google Play services', which the app does not use.
 *
 * Nothing is kept from a fix: it is compared with the saved point in memory and dropped.
 */
internal object WorkPlaceChecks {
    const val ACTION_CHECK = "com.pwnall.health.WORK_PLACE_CHECK"
    const val ACTION_UNDO_START = "com.pwnall.health.WORK_PLACE_UNDO_START"
    const val ACTION_UNDO_STOP = "com.pwnall.health.WORK_PLACE_UNDO_STOP"
    const val EXTRA_START = "com.pwnall.health.WORK_PLACE_START"

    /**
     * The start and stop notices' channel, of their own: muting the reminders' channel, which also
     * carries the nudges to eat, drink and walk, must not take away the one place to undo them from.
     */
    const val NOTICE_CHANNEL = "work_place_notice"
    const val CHECK_CHANNEL = "work_place_check"

    /** Whether checks can run at all: switched on, a point saved, and the three grants they need. */
    fun ready(context: Context): Boolean = WorkPlaceStore.enabled(context) &&
        WorkPlaceStore.place(context) != null &&
        missingWorkPlaceAccess(context).none { it.required }

    fun noticeChannel(context: Context): NotificationChannel = NotificationChannel(
        NOTICE_CHANNEL,
        context.getString(R.string.work_place_notice_channel),
        NotificationManager.IMPORTANCE_LOW,
    )

    /** The window for any day, today's built with the wake as far as it is known now. */
    private suspend fun windows(app: Context, now: Long): (LocalDate) -> WorkCheckWindow? {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val daysOff = WorkClock.snapshot(app).daysOff
        val dao = StepsDb.get(app).dao()
        val rows = dao.journalOfKindBetween(
            KIND_WORK,
            today.minusDays(WORK_HOURS_LOOKBACK_DAYS).toString(),
            today.toString(),
        ).first()
        val hours = usualWorkHours(rows, today, zone, daysOff)
        val readable = hasUsageStatsAccess(app)
        val wake = morningWake(
            recorded = recordedWakeAt(dao.journalOnNow(today.toString()), today),
            phone = if (readable) phoneWakeOn(app, today) else null,
            day = today,
            zone = zone,
            hours = hours,
        )
        return { day -> workCheckWindow(day, zone, daysOff, hours, wake.takeIf { day == today }, readable) }
    }

    /**
     * Sets the alarm for the next check, or takes it away when checks cannot run. Called from the
     * settings, from the Work screen, after every check, and after a reboot, an update or a change
     * of clock — each of which clears an app's alarms or moves what they mean.
     *
     * [windowOn] is the day's windows where they were just built: by the alarm, for a round that
     * takes no fix, and by the same alarm for the service that ends a round that does
     * ([takeRoundWindows]). Built again, they would read the sessions, the day's rows and the
     * night's usage log a second time, a moment after the first.
     *
     * On the IO dispatcher whoever calls: the settings and the Work screen call from the main thread,
     * and this writes the memory and the next check's time to disk and asks the alarm manager.
     */
    suspend fun reschedule(context: Context, windowOn: ((LocalDate) -> WorkCheckWindow?)? = null) =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (!ready(app)) {
                cancel(app)
                return@withContext
            }
            val now = System.currentTimeMillis()
            val today = LocalDate.now(ZoneId.systemDefault())
            val session = WorkClock.read(app)
            val memory = WorkPlaceStore.settle(app, session, today)
            val at = nextWorkCheckAt(
                now = now,
                zone = ZoneId.systemDefault(),
                windowOn = windowOn ?: windows(app, now),
                ownRunning = memory.ownStart != 0L,
                watching = memory.watching(session, today),
                lastLookAt = WorkPlaceStore.lastLookAt(app),
            )
            val manager = app.getSystemService(AlarmManager::class.java)
            if (at == null) {
                manager.cancel(alarm(app))
            } else {
                try {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(app))
                } catch (_: SecurityException) {
                    // The exact-alarm grant can be taken back between ready() and here. Nothing else
                    // would let the check start its service, so there is no alarm to fall back to.
                    WorkPlaceStore.setNextCheckAt(app, 0L)
                    return@withContext
                }
            }
            WorkPlaceStore.setNextCheckAt(app, at ?: 0L)
        }

    /**
     * The next round booked before anything that can fail or be cut short: a cold process opening
     * the database inside the broadcast's few seconds, a vendor's battery manager killing the check
     * mid-fix, an error thrown. Each would otherwise leave no alarm at all, and the checks would
     * stop until the app happened to be opened. The round's own reschedule replaces this one, being
     * the same PendingIntent.
     */
    private fun backstop(app: Context, now: Long) {
        val at = now + WORK_CHECK_EVERY_MS
        try {
            app.getSystemService(AlarmManager::class.java)
                .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(app))
            WorkPlaceStore.setNextCheckAt(app, at)
        } catch (_: SecurityException) {
            WorkPlaceStore.setNextCheckAt(app, 0L)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java).cancel(alarm(app))
        WorkPlaceStore.setNextCheckAt(app, 0L)
    }

    /** The windows the last alarm built, and when: what [takeRoundWindows] hands to the check's service. */
    @Volatile
    private var roundWindows: Pair<Long, (LocalDate) -> WorkCheckWindow?>? = null

    /**
     * The windows the alarm built for the round the check's service is ending, taken once. Null when
     * they were built for another round or another day ([roundWindowsHold]): those are built again.
     */
    fun takeRoundWindows(): ((LocalDate) -> WorkCheckWindow?)? {
        val (builtAt, windowOn) = roundWindows ?: return null
        roundWindows = null
        return windowOn.takeIf { roundWindowsHold(builtAt, System.currentTimeMillis(), ZoneId.systemDefault()) }
    }

    /**
     * The alarm going off: a look at whether the day is open yet and whether a position could change
     * anything, and, only when both, the fix. Before the window this is the look for the wake, which
     * takes no position.
     */
    suspend fun onAlarm(context: Context) {
        val app = context.applicationContext
        if (!ready(app)) {
            cancel(app)
            return
        }
        val now = System.currentTimeMillis()
        backstop(app, now)
        WorkPlaceStore.setLastLookAt(app, now)
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val session = WorkClock.read(app)
        val memory = WorkPlaceStore.settle(app, session, today)
        val windowOn = windows(app, now)
        roundWindows = now to windowOn
        val window = windowOn(today)
        val due = window != null &&
            memory.watching(session, today) &&
            window.covers(now, ownRunning = memory.ownStart != 0L)
        if (due && startCheckService(app)) return
        reschedule(app, windowOn)
    }

    /**
     * The start the exact alarm allows. Refused anyway — a vendor's battery manager, a grant taken
     * back a moment ago — the check is skipped, and the next one is booked by the caller.
     */
    private fun startCheckService(app: Context): Boolean = try {
        ContextCompat.startForegroundService(app, Intent(app, WorkPlaceCheckService::class.java))
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * One check: the fix, the decision on it, and what the decision does to the timer.
     *
     * The fix can take half a minute, and in that time the person can switch the feature off,
     * remove or move the point, or tap the timer. So the switch and the point are looked at again
     * after it, and the decision is for them as they are then or not taken; and the memory is
     * written back only if nothing else wrote it meanwhile ([WorkPlaceStore.saveMemoryIf]).
     */
    suspend fun check(context: Context) {
        val app = context.applicationContext
        val place = WorkPlaceStore.place(app) ?: return
        val fix = workplaceFix(app, WORK_FIX_TIMEOUT_MS)
        if (!ready(app) || WorkPlaceStore.place(app) != place) return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val session = WorkClock.read(app)
        val before = WorkPlaceStore.memory(app)
        val outcome = decideWorkCheck(before, session, placeReading(place, fix), now, today)
        val notice = carryOutWorkCheck(
            outcome,
            session,
            saveIf = { WorkPlaceStore.saveMemoryIf(app, before, it) },
            autoStart = { WorkClock.autoStart(app, now) },
            autoStop = { start, end -> WorkClock.autoStop(app, start, end) },
        )
        when (notice) {
            is WorkCheckNotice.Started -> noticeStarted(app, notice.sessionStart, zone, notice.undo)
            is WorkCheckNotice.Stopped -> noticeStopped(app, notice.sessionStart, notice.end, notice.kept, zone)
            null -> Unit
        }
    }

    /**
     * Undo on the start's notice: the session goes, and today is left to the person — while the
     * session is still the app's own, the same day ([undoStartAccepted]). Refused, the notice
     * simply goes.
     */
    suspend fun undoStart(context: Context, sessionStart: Long) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        NotificationManagerCompat.from(app).cancel(NotificationIds.WORK_PLACE_NOTICE)
        if (!undoStartAccepted(WorkPlaceStore.memory(app), WorkClock.read(app), sessionStart, now, zone)) return
        WorkClock.discard(app, sessionStart)
        WorkPlaceStore.saveMemory(app, quietMemory(Instant.ofEpochMilli(now).atZone(zone).toLocalDate()))
        reschedule(app)
    }

    /**
     * Undo on the stop's notice: the session runs again, and its end is the person's to press —
     * within the hour after the stop, with the timer idle since ([undoStopAccepted]). Refused, the
     * notice simply goes.
     */
    suspend fun undoStop(context: Context, sessionStart: Long) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        NotificationManagerCompat.from(app).cancel(NotificationIds.WORK_PLACE_NOTICE)
        if (!undoStopAccepted(WorkPlaceStore.memory(app), WorkClock.read(app), sessionStart, now, zone)) return
        WorkClock.reopen(app, sessionStart)
        WorkPlaceStore.saveMemory(app, quietMemory(Instant.ofEpochMilli(now).atZone(zone).toLocalDate()))
        reschedule(app)
    }

    /** Offered until the day ends: the start's Undo holds no longer ([undoStartAccepted]). */
    private fun noticeStarted(app: Context, sessionStart: Long, zone: ZoneId, undo: Boolean) {
        postWorkNotice(
            app,
            app.getString(R.string.work_place_started_title),
            app.getString(R.string.work_place_started_body, clockText(morningMinute(sessionStart, zone))),
            action = if (undo) undoAction(app, ACTION_UNDO_START, sessionStart) else null,
            id = NotificationIds.WORK_PLACE_NOTICE,
            channel = noticeChannel(app),
            timeoutMs = undoNoticeTimeoutMs(System.currentTimeMillis(), zone),
        )
    }

    /** Gone when its Undo would be refused: an hour after the stop, or at midnight. */
    private fun noticeStopped(app: Context, sessionStart: Long, end: Long, kept: Boolean, zone: ZoneId) {
        postWorkNotice(
            app,
            app.getString(R.string.work_place_stopped_title),
            if (kept) {
                app.getString(R.string.work_place_stopped_body, clockText(morningMinute(end, zone)))
            } else {
                app.getString(R.string.work_place_stopped_short)
            },
            // A session under a minute left no row, so there is nothing to put back.
            action = if (kept) undoAction(app, ACTION_UNDO_STOP, sessionStart) else null,
            id = NotificationIds.WORK_PLACE_NOTICE,
            channel = noticeChannel(app),
            timeoutMs = undoNoticeTimeoutMs(System.currentTimeMillis(), zone, WORK_UNDO_STOP_MS),
        )
    }

    private fun undoAction(app: Context, action: String, sessionStart: Long): NotificationCompat.Action =
        NotificationCompat.Action(
            0,
            app.getString(R.string.work_place_undo),
            PendingIntent.getBroadcast(
                app,
                NotificationIds.WORK_PLACE_NOTICE,
                Intent(app, WorkPlaceReceiver::class.java).setAction(action).putExtra(EXTRA_START, sessionStart),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

    private fun alarm(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        0,
        Intent(app, WorkPlaceReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** What the checks need from Android, in the order it has to be asked for. */
internal enum class WorkPlaceAccess(val required: Boolean) {
    /** Precise, since an approximate position is a couple of kilometres wide: no workplace fits in it. */
    PRECISE(required = true),

    /** "Allow all the time": a service started by an alarm is otherwise given no position. */
    BACKGROUND(required = true),

    /** Only an exact alarm may start the check's service with the app closed. */
    EXACT_ALARMS(required = true),

    /**
     * Not needed for the checks, but without it a start or stop has no notice to undo it from:
     * notifications on for the app, and the notices' own channel not blocked.
     */
    NOTIFICATIONS(required = false),
}

/**
 * Whether this build declares location all the time, without which the feature cannot run: the
 * sideload build does, the play build does not (src/sideload/AndroidManifest.xml says why). Read
 * from the installed manifest, so the settings section follows the one line that declares it.
 */
internal fun workPlaceAvailable(context: Context): Boolean = runCatching {
    context.packageManager
        .getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        .requestedPermissions
        ?.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == true
}.getOrDefault(false)

internal fun missingWorkPlaceAccess(context: Context): List<WorkPlaceAccess> {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return WorkPlaceAccess.entries.filter { access ->
        when (access) {
            WorkPlaceAccess.PRECISE -> !granted(Manifest.permission.ACCESS_FINE_LOCATION)

            WorkPlaceAccess.BACKGROUND -> !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

            WorkPlaceAccess.EXACT_ALARMS -> !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

            WorkPlaceAccess.NOTIFICATIONS -> {
                val manager = context.getSystemService(NotificationManager::class.java)
                !manager.areNotificationsEnabled() ||
                    manager.getNotificationChannel(WorkPlaceChecks.NOTICE_CHANNEL)?.importance ==
                    NotificationManager.IMPORTANCE_NONE
            }
        }
    }
}

/** Long enough for a satellite fix under a clear sky; a check that runs out of it reads as unsure. */
internal const val WORK_FIX_TIMEOUT_MS = 30_000L

/** A position Android already holds stands in for a fresh one only while it is this young. */
private const val WORK_LAST_KNOWN_MAX_AGE_MS = 2 * 60_000L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * One precise position from Android's own location service, with no Play services: the platform's
 * fused provider where the phone has one, satellites where it does not, the network as the last
 * resort. Waited for [timeoutMs]; failing that, the newest position Android already holds, if it
 * is under two minutes old. Null without the precise grant, with location switched off, or with
 * nothing in time. Nothing here keeps the answer.
 */
@SuppressLint("MissingPermission") // checked first: ACCESS_FINE_LOCATION
internal suspend fun workplaceFix(context: Context, timeoutMs: Long): PlaceFix? {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val manager = context.getSystemService(LocationManager::class.java)
    if (!granted || manager == null || !manager.isLocationEnabled) return null
    // Cancelled — the service going, the round out of time — the check ends here, rather than going
    // on to decide on no fix at all.
    return runCatching {
        val providers = WORK_FIX_PROVIDERS.filter { it in manager.allProviders && manager.isProviderEnabled(it) }
        val fresh = providers.firstOrNull()?.let { provider ->
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }
            }
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val fix = fresh ?: providers.mapNotNull(manager::getLastKnownLocation)
            .filter { now - it.elapsedRealtimeNanos <= WORK_LAST_KNOWN_MAX_AGE_MS * NANOS_PER_MILLI }
            .maxByOrNull(Location::getElapsedRealtimeNanos)
        fix?.let {
            PlaceFix(
                it.latitude,
                it.longitude,
                if (it.hasAccuracy()) it.accuracy.toDouble() else Double.POSITIVE_INFINITY,
            )
        }
    }.onFailure { if (it is CancellationException) throw it }.getOrNull()
}

private val WORK_FIX_PROVIDERS = listOf(
    LocationManager.FUSED_PROVIDER,
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
)

/** Room for the fix, the decision and the next alarm; a hung provider cannot keep the service up. */
private const val WORK_CHECK_LIMIT_MS = WORK_FIX_TIMEOUT_MS + 20_000L

/**
 * The check's foreground service: one fix, the decision, the next alarm, gone. START_NOT_STICKY —
 * a check the system killed is not run again late; the next alarm is the next check.
 */
class WorkPlaceCheckService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext
        val foreground = try {
            ServiceCompat.startForeground(
                this,
                NotificationIds.WORK_PLACE_CHECK,
                checkNotification(app),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
        // A refused call still keeps the promise startForegroundService made: the platform counts it
        // kept as the call comes in, before the checks that refuse it. So stopping now is not the
        // crash of a service stopped before it ever called startForeground (StepService's case).
        if (!foreground || running) {
            if (!foreground) finish(app)
            return START_NOT_STICKY
        }
        running = true
        scope.launch {
            // A check that fails — out of time, a database that would not open, anything thrown —
            // is a skipped one: the next is booked all the same, and the process is not taken down
            // with it, as an exception left to this scope would.
            runCatching { withTimeout(WORK_CHECK_LIMIT_MS) { WorkPlaceChecks.check(app) } }
            finish(app)
        }
        return START_NOT_STICKY
    }

    private fun finish(app: Context) {
        scope.launch {
            // Failing here leaves the round's backstop alarm in place.
            withContext(NonCancellable) {
                runCatching { WorkPlaceChecks.reschedule(app, WorkPlaceChecks.takeRoundWindows()) }
            }
            ServiceCompat.stopForeground(this@WorkPlaceCheckService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Says what the check is doing while it does it, on a channel of its own at the lowest importance. */
private fun checkNotification(context: Context): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            WorkPlaceChecks.CHECK_CHANNEL,
            context.getString(R.string.work_place_channel),
            NotificationManager.IMPORTANCE_MIN,
        ),
    )
    return NotificationCompat.Builder(context, WorkPlaceChecks.CHECK_CHANNEL)
        .setSmallIcon(R.drawable.ic_steps)
        .setContentTitle(context.getString(R.string.work_place_checking))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setSilent(true)
        .setOngoing(true)
        .build()
}

/**
 * The check's alarm, Undo on its notices, and the moments Android clears or moves alarms: a
 * reboot, an update of the app, a change of clock or zone, and the exact-alarm grant changing.
 * Not exported: the alarm and the notices are this app's own PendingIntents, and the system's
 * broadcasts reach it regardless.
 */
class WorkPlaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val start = intent.getLongExtra(WorkPlaceChecks.EXTRA_START, 0L)
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Cut short or failed, an alarm round has its backstop booked first (onAlarm), and
                // nothing thrown here is allowed to take the process down.
                runCatching {
                    withTimeout(RECEIVER_TIMEOUT_MS) {
                        when (action) {
                            WorkPlaceChecks.ACTION_CHECK -> WorkPlaceChecks.onAlarm(app)
                            WorkPlaceChecks.ACTION_UNDO_START -> WorkPlaceChecks.undoStart(app, start)
                            WorkPlaceChecks.ACTION_UNDO_STOP -> WorkPlaceChecks.undoStop(app, start)
                            else -> WorkPlaceChecks.reschedule(app)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** Inside the ten seconds a broadcast is given, with room to finish. */
        const val RECEIVER_TIMEOUT_MS = 8_000L

        val ACTIONS = setOf(
            WorkPlaceChecks.ACTION_CHECK,
            WorkPlaceChecks.ACTION_UNDO_START,
            WorkPlaceChecks.ACTION_UNDO_STOP,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
