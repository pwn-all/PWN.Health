package com.pwnall.health

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Whether a dose rings: the switch in the app is on, and the alarm's channel is not muted in
 * Android's own settings. [channelImportance] is that channel's importance as Android holds it, or
 * null where the channel has not been made yet.
 *
 * Silencing the ring from the notification itself mutes the channel and leaves the app's switch on.
 * A check that asked for the muted channel again would be refused, and no dose would be announced
 * at all — not even as the ordinary card on its own channel, which §10 of the privacy policy says
 * muting the alarm leaves alone. Muted, the dose is that card, as it is with the switch off.
 */
internal fun doseRings(switchOn: Boolean, channelImportance: Int?): Boolean =
    switchOn && channelImportance != NotificationManager.IMPORTANCE_NONE

/**
 * Whether a dose can be announced at all, as a ring or as the card: what [MedicineAlarmNotification.post]
 * is refused by. [permitted] is the phone's leave for the app's notifications, [rings] is
 * [doseRings], and [cardImportance] is the ordinary channel's importance as [doseRings] takes the
 * alarm's. A ring needs only its own channel; without one the dose is the card, and a card on a
 * muted channel is nothing.
 */
internal fun doseShows(permitted: Boolean, rings: Boolean, cardImportance: Int?): Boolean =
    permitted && (rings || cardImportance != NotificationManager.IMPORTANCE_NONE)

/** Uses the system alarm sound; Android still owns volume, DND and lock-screen presentation. */
internal object MedicineAlarmNotification {
    private const val CHANNEL = "medicine_alarm"
    private const val ID = NotificationIds.MEDICINE_DOSE
    const val RING_MS = 60_000L
    const val EXTRA_KEY = "dose_key"
    const val EXTRA_BODY = "dose_body"
    const val EXTRA_EXPIRES = "alarm_expires"

    fun alarmEnabled(context: Context): Boolean = preferences(context).getBoolean("alarm", true)

    /** Whether a dose rings just now ([doseRings]): the app's switch, and the channel as Android holds it. */
    fun rings(context: Context): Boolean = doseRings(
        alarmEnabled(context),
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance,
    )

    /** Whether a dose can be announced at all just now ([doseShows]), read off the phone as [post] reads it. */
    fun shows(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val permitted =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return doseShows(
            permitted && manager.areNotificationsEnabled(),
            rings(context),
            manager.getNotificationChannel(Reminders.MEDICINE_CHANNEL)?.importance,
        )
    }

    /**
     * Android's own page for the alarm's channel: the one place a ring muted there is given back,
     * which the switch in the app cannot do. The page exists once the channel does, and a channel
     * that is muted has been made.
     */
    fun channelSettings(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)

    /**
     * Switched off, the alarm stops a ring under way and takes nothing else down: the card that
     * follows a ring comes as it would have once the ring's minute was up
     * ([MedicineAlarms.followUpChecks]), and a card already up stays up. Taking every dose's
     * notification down here while the record of what it said stayed would leave the dose quiet in an
     * empty shade until its hour came round.
     */
    fun setAlarmEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit { putBoolean("alarm", enabled) }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.id == ID && it.notification.channelId == CHANNEL }
            .forEach { manager.cancel(it.tag, ID) }
    }

    private fun preferences(context: Context) = context.getSharedPreferences(
        "medicine_alarm_options",
        Context.MODE_PRIVATE,
    )

    fun fullScreenAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun cancel(context: Context, key: String) {
        context.getSystemService(NotificationManager::class.java).cancel(key, ID)
    }

    fun cancelExcept(context: Context, keys: Set<String>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.id == ID && it.tag?.startsWith("dose:") == true }
            .filter { it.tag !in keys }
            .forEach { manager.cancel(it.tag, ID) }
        // Every dose posts under a tag; a notification under the bare id is a leftover, and goes too.
        manager.cancel(ID)
    }

    /**
     * Posts the notification for one dose, or for the doses that share its key
     * ([DatedMedNudge.key]): [several] says there is more than one of them, and the text then says
     * so, and that "I took it" marks them all — still without a name or a number.
     *
     * [loud] is the ringing variant: the alarm channel, the looping sound and the full-screen
     * intent. The quiet one is the same card on the ordinary channel — it is what goes up in place
     * of a ringing notification once the ringing one has timed itself out, so the dose is still
     * there to act on for somebody who was not holding the phone that minute. It is also what a ring
     * asked for becomes where the dose does not ring ([rings]).
     *
     * [offerLater] is whether "Later" is on it: not in the dose's last quarter of an hour, where it
     * could not be kept ([MedicineAlarms.snoozable]) and the tap would do nothing.
     */
    fun post(context: Context, dose: DatedMedNudge, loud: Boolean, several: Boolean, offerLater: Boolean): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val alarm = loud && rings(context)
        val channel = if (alarm) CHANNEL else Reminders.MEDICINE_CHANNEL
        createChannel(context, manager, channel, alarm)
        if (manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE) return false
        val body = body(context, dose.nudge, several)
        val screen = alarmScreen(context, dose, body)
        val stop = doseBroadcast(context, MedicineAlarmDismissReceiver::class.java, dose.key)
        val taken = doseBroadcast(context, MedicineTakenReceiver::class.java, dose.key)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_steps)
            .setContentTitle(context.getString(R.string.med_reminder_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(
                if (alarm) {
                    screen
                } else {
                    // The tab the dose is marked on, as the dose widget opens it, and not the home page.
                    PendingIntent.getActivity(
                        context,
                        ID,
                        openAppIntent(context, WidgetRoute.MEDICINE),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                },
            )
            .setAutoCancel(true)
            // Marking the dose comes first: it is the one action that answers the question the
            // notification asked, and the only one that stops it coming back.
            .addAction(0, context.getString(R.string.med_action_taken), taken)
        if (offerLater) {
            val later = doseBroadcast(context, MedicineSnoozeReceiver::class.java, dose.key)
            builder.addAction(0, context.getString(R.string.med_action_later), later)
        }
        if (alarm) {
            // Silencing stays separate from marking a dose taken, and only the ringing variant has
            // anything to silence.
            builder.addAction(0, context.getString(R.string.med_alarm_dismiss), stop)
            builder.setTimeoutAfter(RING_MS)
            if (fullScreenAllowed(context)) builder.setFullScreenIntent(screen, true)
        } else {
            // The replacement card must not chime every time the hour's check re-posts it.
            builder.setOnlyAlertOnce(true).setOngoing(false)
        }
        val notification = builder.build()
        if (alarm) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        return try {
            manager.notify(dose.key, ID, notification)
            true
        } catch (_: SecurityException) {
            // A permission may be revoked while the notification is being built.
            false
        }
    }

    /** One announcement, one receiver, one pending intent — the tag keeps them from colliding. */
    private fun doseBroadcast(context: Context, receiver: Class<*>, key: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, receiver)
                .setData("pwnfit://medicine/$key".toUri())
                .putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmScreen(context: Context, dose: DatedMedNudge, body: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MedicineAlarmActivity::class.java)
                .setData("pwnfit://medicine/${dose.key}".toUri())
                .putExtra(EXTRA_KEY, dose.key)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_EXPIRES, System.currentTimeMillis() + RING_MS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun body(context: Context, nudge: MedNudge, several: Boolean): String {
        val at = if (nudge.dose.onWaking) {
            context.getString(R.string.med_dose_on_waking)
        } else {
            context.getString(R.string.med_dose_at, clockText(nudge.dose.minuteOfDay))
        }
        val said = context.getString(nudge.reason.bodyRes, at)
        if (!several) return said
        // A line of its own, naming the button by its own label: what the tap will do has to be
        // read before it is made, in the shade and on the alarm's page alike.
        val shared = context.getString(R.string.med_reminder_several, context.getString(R.string.med_action_taken))
        return "$said\n$shared"
    }

    private fun createChannel(context: Context, manager: NotificationManager, id: String, alarm: Boolean) {
        val channel = NotificationChannel(
            id,
            context.getString(if (alarm) R.string.med_alarm_title else R.string.med_reminder_title),
            if (alarm) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
        )
        if (alarm) {
            channel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            channel.enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }
}

/** Silencing is deliberately separate from marking a dose taken. */
class MedicineAlarmDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(MedicineAlarmNotification.EXTRA_KEY)?.let {
            MedicineAlarmNotification.cancel(context, it)
        }
    }
}

/**
 * Writes the dose down as taken without the app being opened — every dose the notification stands
 * for, where several share it ([MedicineAlarms.markTaken]).
 *
 * No Activity is started from here — Android 12 onwards refuses a notification that trampolines
 * through a receiver, and the whole point of this action is that it answers the reminder where the
 * reminder is. [goAsync] carries a hard budget of a few seconds. The timeout below bounds the reads;
 * the write itself is not cancellable once begun ([saveJournalEvent]), and it refreshes the alarms
 * and redraws the dose widget before it returns.
 */
class MedicineTakenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(MedicineAlarmNotification.EXTRA_KEY) ?: return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Nothing thrown here may take the process down — a database that would not open, a
                // full disk: the step service lives in it. The notification is taken down only once
                // the record has been read, so a tap that failed before that can be made again.
                runCatchingCancellable { withTimeout(MED_ACTION_TIMEOUT_MS) { MedicineAlarms.markTaken(app, key) } }
                    .onFailure { Log.w("MedicineAlarms", "the dose was not marked", it) }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Puts the dose off for a quarter of an hour, or, where its grace will not reach that far, leaves its
 * card up without the "Later" ([MedicineAlarms.snooze]).
 */
class MedicineSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(MedicineAlarmNotification.EXTRA_KEY) ?: return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // As above: nothing a "Later" throws may take the process down.
                runCatchingCancellable { withTimeout(MED_ACTION_TIMEOUT_MS) { MedicineAlarms.snooze(app, key) } }
                    .onFailure { Log.w("MedicineAlarms", "the dose was not put off", it) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** What a broadcast receiver may spend before the system stops waiting for it. */
private const val MED_ACTION_TIMEOUT_MS = 8_000L
