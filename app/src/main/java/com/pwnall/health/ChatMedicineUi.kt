package com.pwnall.health

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

// The medicine threads' own drawing: the pick of the person's medicines, the times of a new course,
// the reminders' switches, and the tile in the person's bubble.

/**
 * The person's medicines to pick from ([ChatReply.MedicinePick]): each a radio with its picture or
 * glyph, its name, and its dose or the one open dose's time; past [PICK_ALL_AT] the first [PICK_FIRST]
 * and "All my medicines", which unfolds the rest in place; then the words' proposals and "Another medicine".
 */
@Composable
internal fun MedicinePickReply(reply: ChatReply.MedicinePick, report: SymptomReport, actions: ReplyActions) {
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    var all by rememberSaveable(reply.step) { mutableStateOf(false) }
    val held = report.values[reply.step]
    val folded = reply.more && !all
    Column(
        Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        (if (folded) reply.choices.take(PICK_FIRST) else reply.choices).forEach { choice ->
            val value = MED_COURSE_PREFIX + choice.createdAt + choice.slot?.let { "@$it" }.orEmpty()
            val detail = choice.slotLabel?.let { resources.render(it, locale, ZoneId.systemDefault()) } ?: choice.dose
            MedicineChoiceChip(choice, detail, held == value) { actions.onValue(reply.step, value) }
        }
        if (folded) {
            PwnOutlinedButton(onClick = { all = true }) { Text(stringResource(R.string.med_chat_pick_show_all)) }
        }
    }
    ReplyRow(single = true) {
        reply.proposals.forEach { chip ->
            val label = resources.render(chip.label, locale, ZoneId.systemDefault())
            PainAnswerChip(chip.value == held, { actions.onValue(reply.step, chip.value) }, label)
        }
    }
}

/** One of the person's courses as a choice: its picture, which a tap here never opens, its name and [detail]. */
@Composable
private fun MedicineChoiceChip(choice: MedicineChoice, detail: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(PwnCorners.small)
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .border(1.dp, scheme.outline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = PwnSpacing.large, vertical = PwnSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PwnSpacing.large),
    ) {
        MedicineGlyph(choice.photo, choice.name, CHOICE_GLYPH, openable = false) {
            PwnGlyph(
                R.drawable.ic_art_pills,
                Icons.Outlined.Medication,
                PwnIndigoSoft,
                null,
                Modifier.size(CHOICE_GLYPH),
            )
        }
        Column {
            Text(choice.name, style = MaterialTheme.typography.labelLarge)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
        }
    }
}

private val CHOICE_GLYPH = 36.dp

/**
 * The times of a new course ([ChatReply.TimesPick]): the proposals unticked, the times added ticked,
 * and "Add a time" with the app's own clock; the ticks are taken by the clock once one stands.
 */
@Composable
internal fun TimesPickReply(reply: ChatReply.TimesPick, report: SymptomReport, actions: ReplyActions) {
    val held = report.values[reply.step].orEmpty().split(',').mapNotNull { it.toIntOrNull() }
    var adding by rememberSaveable(reply.step) { mutableStateOf(false) }
    val send = { times: List<Int> -> actions.onValue(reply.step, times.distinct().sorted().joinToString(",")) }
    ReplyRow(single = false) {
        (reply.proposals + held).distinct().sorted().forEach { minute ->
            PwnFilterChip(
                selected = minute in held,
                onClick = { send(if (minute in held) held - minute else held + minute) },
                label = clockText(minute),
            )
        }
        PwnOutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.med_add_time)) }
    }
    if (adding) {
        ClockTimePickerDialog(
            label = stringResource(R.string.med_add_time),
            initialMinuteOfDay = MED_NEW_TIME_MINUTE,
            onDismiss = { adding = false },
            onConfirm = { minute ->
                adding = false
                send(held + minute)
            },
        )
    }
}

/**
 * One switch a new course's reminders need ([ChatReply.ReminderGates]): turning reminders on through
 * the Profile page's own door, the notifications' prompt or settings, the alarm settings inline —
 * each answered once it is done.
 */
@Composable
internal fun ReminderGatesReply(reply: ChatReply.ReminderGates, report: SymptomReport, actions: ReplyActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chosen = report.answers[reply.step].orEmpty()
    // "Allow" is the person's choice, taken on their way back whatever the switch is then: the thread
    // reads the switch as it stands and says when notifications are still off (gateLines in ChatCourse.kt).
    val notifications = rememberNotificationAccess { actions.onTap(reply.step, SymptomOption.ALLOW) }
    // While the reminders are being turned on no chip of the question answers: "Not now" tapped then
    // would stand as the answer of a question whose reminders came on a moment later.
    var turning by remember(reply.step) { mutableStateOf(false) }
    if (reply.gate == ReminderGate.ALARM) MedicineAlarmSettings()
    ReplyRow(single = true) {
        courseOptions(ChatState(report, female = false, earlier = null), reply.step).forEach { option ->
            val tap: () -> Unit = {
                when {
                    turning -> Unit

                    option == SymptomOption.TURN_ON -> {
                        turning = true
                        scope.launch {
                            try {
                                withContext(NonCancellable) { turnRemindersOn(context) }
                                actions.onTap(reply.step, option)
                            } finally {
                                turning = false
                            }
                        }
                    }

                    option == SymptomOption.ALLOW -> notifications()

                    else -> actions.onTap(reply.step, option)
                }
            }
            PwnChoiceChip(option in chosen, tap, stringResource(option.labelRes), enabled = !turning)
        }
    }
}

/** Reminders turned on as the Profile page turns them on: the profile saved, the worker and the alarms started. */
private suspend fun turnRemindersOn(context: Context) {
    val dao = StepsDb.get(context).dao()
    dao.profileNow()?.let { dao.saveProfile(it.copy(remindersEnabled = true)) }
    Reminders.apply(context, true)
}

/**
 * The way to the app's notifications: Android's own prompt while it still shows one, else the app's
 * notification settings. [onBack] runs when the person comes back from either. Android answers a
 * prompt it no longer shows — refused twice, here or on another page — with a refusal at once and no
 * prompt at all: the settings page is opened then, so the tap is never a dead end.
 */
@Composable
private fun rememberNotificationAccess(onBack: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = Manifest.permission.POST_NOTIFICATIONS
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onBack() }
    val openSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { settings.launch(intent) }.onFailure { onBack() }
    }
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val blocked = !granted && activity?.shouldShowRequestPermissionRationale(permission) == false
        if (blocked) openSettings() else onBack()
    }
    return {
        val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        // Refused twice, Android no longer shows its prompt: the app's own settings page instead.
        val blocked = asked && activity?.shouldShowRequestPermissionRationale(permission) == false
        if (!granted && !blocked) {
            asked = true
            prompt.launch(permission)
        } else {
            openSettings()
        }
    }
}

/**
 * The reminders' switches as they stand, for a new course's questions: reminders on in the profile,
 * a dose that can be announced at all, exact alarms, and — for a ringing alarm — the full-screen
 * alarm. Null when they cannot be read.
 *
 * Whether a dose can be announced is [MedicineAlarmNotification.shows], the answer the Medicine page
 * and the alarm itself go by. Reading the quiet channel alone would ask a person whose alarm rings to
 * allow notifications because the card they never see is muted.
 *
 * Read off the main thread: each switch is a call into the system's services, and the chat reads them
 * again after every answer it files.
 */
internal suspend fun readReminderGates(context: Context, db: StepsDb): ReminderGateState? {
    val reminders = db.dao().profileNow()?.remindersEnabled ?: true
    return withContext(Dispatchers.IO) { runCatching { gatesNow(context, reminders) }.getOrNull() }
}

private fun gatesNow(context: Context, reminders: Boolean): ReminderGateState = ReminderGateState(
    remindersOn = reminders,
    notificationsOn = MedicineAlarmNotification.shows(context),
    exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
    fullScreen = !MedicineAlarmNotification.rings(context) || MedicineAlarmNotification.fullScreenAllowed(context),
)
