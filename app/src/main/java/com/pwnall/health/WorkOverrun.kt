package com.pwnall.health

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * How long until the forgotten-stop check next has something to decide, given the net time so far:
 * the reminder at [WORK_REMIND_AFTER_MS], then the cap at [WORK_CAP_MS], then at once.
 */
internal fun overrunCheckDelayMs(netMs: Long): Long = when {
    netMs < WORK_REMIND_AFTER_MS -> WORK_REMIND_AFTER_MS - netMs
    netMs < WORK_CAP_MS -> WORK_CAP_MS - netMs
    else -> 0L
}

/** What the forgotten-stop check does when it runs. */
internal enum class OverrunStep { WAIT, ASK, STOP }

/**
 * The check, decided for [netMs] of net time. At the cap it stops whether or not anybody could be
 * asked ([remind]): a denied notification permission must not leave a timer running for days. Below
 * it, it asks once the reminder is due and only where reminders are on, and otherwise waits.
 */
internal fun overrunStep(netMs: Long, remind: Boolean): OverrunStep = when {
    netMs >= WORK_CAP_MS -> OverrunStep.STOP
    netMs >= WORK_REMIND_AFTER_MS && remind -> OverrunStep.ASK
    else -> OverrunStep.WAIT
}

/**
 * The guard against a Stop nobody pressed.
 *
 * One unique piece of WorkManager work rather than an alarm: it survives a reboot without a boot
 * receiver, and nothing here needs to fire to the minute. It is moved on every Start and Resume —
 * the net time is what it waits out, and a pause stops the net time — and taken away by a Pause or
 * a Stop. No foreground service: the timer holds no sensor and no location, so there is nothing a
 * service would keep alive.
 */
internal object WorkOverrun {
    private const val WORK_NAME = "work_overrun"

    internal const val ACTION_STOP = "com.pwnall.health.WORK_STOP"
    internal const val EXTRA_FROM = "com.pwnall.health.WORK_FROM"

    /**
     * [policy] is REPLACE from a tap, which moves the check to fit the new net time. From inside
     * the check itself it is APPEND_OR_REPLACE: replacing the unique work that is running would
     * cancel the very check doing the replacing.
     */
    fun schedule(
        context: Context,
        session: WorkSession,
        now: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            policy,
            OneTimeWorkRequestBuilder<WorkOverrunWorker>()
                .setInitialDelay(overrunCheckDelayMs(session.netMs(now)), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    /** The check and the question it may have asked both go: there is no running timer to ask about. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        NotificationManagerCompat.from(context).cancel(NotificationIds.WORK_OVERRUN)
    }
}

/**
 * The check itself. At the reminder it asks — only where reminders are on — and waits for the cap;
 * at the cap it stops the session whether or not anybody could be asked, because a denied
 * notification permission must not leave a timer running for days. The row it writes is marked,
 * and the Work screen offers to set the real end.
 */
internal class WorkOverrunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val session = WorkClock.read(applicationContext)
        if (session.state == WorkState.RUNNING) decide(applicationContext, session, System.currentTimeMillis())
        return Result.success()
    }

    private suspend fun decide(app: Context, session: WorkSession, now: Long) {
        val net = session.netMs(now)
        // A database that will not open must not take the cap down with it: the switch it could
        // not read counts as off, and the timer is still stopped at the cap or the next check booked.
        val remind = runCatching { StepsDb.get(app).dao().profileNow()?.remindersEnabled == true }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(false)
        val step = overrunStep(net, remind)
        if (step == OverrunStep.STOP) {
            val stopped = WorkClock.stopIfCapped(app, now).moved
            if (stopped && remind) {
                postWorkNotice(
                    app,
                    app.getString(R.string.work_capped_title, WORK_CAP_MS / 3_600_000L),
                    app.getString(R.string.work_capped_body),
                )
            }
        } else {
            if (step == OverrunStep.ASK) {
                postWorkNotice(
                    app,
                    app.getString(R.string.work_overrun_title),
                    app.getString(R.string.work_overrun_body, net / 3_600_000L),
                    action = NotificationCompat.Action(
                        0,
                        app.getString(R.string.work_stop),
                        PendingIntent.getBroadcast(
                            app,
                            NotificationIds.WORK_OVERRUN,
                            Intent(app, WorkActionReceiver::class.java)
                                .setAction(WorkOverrun.ACTION_STOP)
                                .putExtra(WorkOverrun.EXTRA_FROM, WorkState.RUNNING.name),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    ),
                )
            }
            WorkOverrun.schedule(app, session, now, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }
}

/**
 * The same checks as the hourly reminders (Reminders.kt's `post`): the permission, the switch for
 * the whole app, and the channel's own switch. VISIBILITY_PRIVATE, as the reminders are: the text is
 * hidden on a lock screen only where the phone is set to hide sensitive notification content. [id] is
 * the notification's own: the forgotten-stop questions share one, the workplace check's notices
 * another. [channel] is the reminders' quiet one unless the caller has its own, as the workplace
 * check's notices do; [timeoutMs], when set, takes the notice away once what it offers no longer
 * holds.
 */
internal fun postWorkNotice(
    context: Context,
    title: String,
    body: String,
    action: NotificationCompat.Action? = null,
    id: Int = NotificationIds.WORK_OVERRUN,
    channel: NotificationChannel = NotificationChannel(
        Reminders.CHANNEL,
        context.getString(R.string.reminders_title),
        NotificationManager.IMPORTANCE_LOW,
    ),
    timeoutMs: Long = 0L,
) {
    val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    manager.createNotificationChannel(channel)
    if (manager.getNotificationChannel(channel.id).importance == NotificationManager.IMPORTANCE_NONE) return
    val builder = NotificationCompat.Builder(context, channel.id)
        .setSmallIcon(R.drawable.ic_steps)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                id,
                openAppIntent(context, WidgetRoute.WORK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setAutoCancel(true)
    action?.let(builder::addAction)
    if (timeoutMs > 0L) builder.setTimeoutAfter(timeoutMs)
    try {
        NotificationManagerCompat.from(context).notify(id, builder.build())
    } catch (_: SecurityException) {
        // The permission can be taken away while the notification is being built.
    }
}

/**
 * The notification's Stop. It carries the state it was posted in, like a widget tap, so a Stop
 * pressed on a notice that outlived its session settles nothing.
 */
class WorkActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WorkOverrun.ACTION_STOP) return
        val from = intent.getStringExtra(WorkOverrun.EXTRA_FROM)
            ?.let { name -> WorkState.entries.firstOrNull { it.name == name } }
            ?: return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Nothing thrown here may take the process down: the step service lives in it.
                runCatching { withTimeout(WORK_ACTION_TIMEOUT_MS) { WorkClock.act(app, from, WorkState.IDLE) } }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Inside the ten seconds a broadcast is given, with room to finish. */
private const val WORK_ACTION_TIMEOUT_MS = 8_000L
