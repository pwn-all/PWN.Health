package com.pwnall.health

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

@Composable
internal fun MedicineAlarmSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var alarm by remember { mutableStateOf(MedicineAlarmNotification.alarmEnabled(context)) }
    var exact by remember {
        mutableStateOf(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
    }
    var fullScreen by remember { mutableStateOf(MedicineAlarmNotification.fullScreenAllowed(context)) }
    var rings by remember { mutableStateOf(MedicineAlarmNotification.rings(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        exact = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        fullScreen = MedicineAlarmNotification.fullScreenAllowed(context)
        rings = MedicineAlarmNotification.rings(context)
        scope.launch { MedicineAlarms.refresh(context) }
    }
    // The channel is as often muted or given back from Android's own settings, with this page open
    // behind them and no launcher of its own to answer.
    LifecycleResumeEffect(Unit) {
        rings = MedicineAlarmNotification.rings(context)
        onPauseOrDispose { }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PwnSwitchRow(
            title = stringResource(R.string.med_alarm_title),
            help = stringResource(R.string.med_alarm_settings_body),
            checked = alarm,
            onCheckedChange = {
                alarm = it
                MedicineAlarmNotification.setAlarmEnabled(context, it)
                rings = MedicineAlarmNotification.rings(context)
                scope.launch { MedicineAlarms.refresh(context) }
            },
        )
        // The switch on and nothing ringing: the alarm's channel is muted in Android's settings,
        // most often from the ringing notification itself ([doseRings]). The doses then come as
        // ordinary notifications, and the switch alone would go on saying "on" with no word of it.
        // Said here, with the way to the one page that gives the ring back.
        if (alarm && !rings) {
            Text(
                stringResource(R.string.med_alarm_channel_muted, stringResource(R.string.med_alarm_title)),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { runCatching { request.launch(MedicineAlarmNotification.channelSettings(context)) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.med_alarm_channel_open)) }
        }
        if (!exact) {
            OutlinedButton(onClick = {
                val intent =
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
                // A build without the page throws, as [rememberAlarmAccessRequest] allows for.
                runCatching { request.launch(intent) }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.med_alarm_exact_access)) }
        }
        if (alarm && !fullScreen && Build.VERSION.SDK_INT >= 34) {
            OutlinedButton(onClick = {
                val intent =
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri())
                runCatching { request.launch(intent) }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.med_alarm_full_screen_access)) }
        }
    }
}

/**
 * What keeps a course's doses from being announced at all, and the button that lifts it. The app's
 * own switch comes first — off, it calls every dose off whatever the phone allows
 * ([MedicineAlarms.refresh]) — and then the phone's leave to show the notification.
 */
internal enum class ReminderBlock(val bodyRes: Int, val actionRes: Int) {
    REMINDERS_OFF(R.string.med_reminders_off, R.string.med_chat_remind_on),
    NOTIFICATIONS_OFF(R.string.med_chat_ask_remind_notify, R.string.med_chat_notify_allow),
}

/**
 * The [ReminderBlock] the Medicine tab says above the day's doses, or null where there is none to
 * say: nothing stands in the way, or no course is running to be reminded of.
 *
 * The form asks about both as a course is added, and only then. A course added before the switch
 * went off, a prompt Android no longer shows, the channel muted from the shade a month later: the
 * tab would go on listing doses nothing would announce, with no word of it anywhere.
 */
internal fun reminderBlock(courseRunning: Boolean, remindersOn: Boolean, notificationsOn: Boolean): ReminderBlock? =
    when {
        !courseRunning -> null
        !remindersOn -> ReminderBlock.REMINDERS_OFF
        !notificationsOn -> ReminderBlock.NOTIFICATIONS_OFF
        else -> null
    }

/**
 * The way to the app's page in Android's notification settings; [onBack] runs when the person comes
 * back from it. The page and not Android's prompt: it is there whatever shut the notifications off
 * — a permission never given, one refused until the prompt stopped showing, the dose channel muted
 * — so the tap is never a dead end.
 */
@Composable
internal fun rememberNotificationSettings(onBack: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onBack() }
    return {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { settings.launch(intent) }.onFailure { onBack() }
    }
}

/**
 * What a new course's reminder rings with, asked for as the course is added: exact alarms, then —
 * for the ringing alarm — the full-screen one. Each opens its own settings screen, and only where it
 * is missing. A screen with no such page on this build is skipped.
 *
 * Before either, Android's own prompt for notifications where that permission is missing. Without
 * it no reminder shows at all ([MedicineAlarmNotification.post]): it is the one the alarm's two both
 * depend on. A prompt Android no longer shows answers at once with a refusal, and the alarm's two
 * are asked all the same.
 */
@Composable
internal fun rememberAlarmAccessRequest(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fullScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch { MedicineAlarms.refresh(context) }
    }

    fun askFullScreen() {
        // Asked only where a dose rings: with the alarm's channel muted nothing would use it.
        if (Build.VERSION.SDK_INT >= 34 && MedicineAlarmNotification.rings(context) &&
            !MedicineAlarmNotification.fullScreenAllowed(context)
        ) {
            val intent =
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri())
            runCatching { fullScreen.launch(intent) }
        }
    }
    val exact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch { MedicineAlarms.refresh(context) }
        askFullScreen()
    }

    fun askAlarms() {
        if (context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            askFullScreen()
        } else {
            val intent =
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
            runCatching { exact.launch(intent) }.onFailure { askFullScreen() }
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { MedicineAlarms.refresh(context) }
        askAlarms()
    }
    return {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            askAlarms()
        } else {
            runCatching { notifications.launch(permission) }.onFailure { askAlarms() }
        }
    }
}
