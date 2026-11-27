package com.pwnall.health

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Runtime grants and special-access settings are separate Android flows, refreshed on every return. */
@Composable
internal fun OnboardingAccessScreen(onNext: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var revision by remember { mutableIntStateOf(0) }
    var asked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var unavailable by remember { mutableStateOf(false) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }
    val allowed = remember(revision) {
        OnboardingAccess.entries.associateWith { it.allowed(context) }
    }
    // Asked as the request itself asks ([rememberHealthConnectPrefill]). Where Health Connect is not
    // on the phone — Android 13 without the app, a ROM without Google's — Next asks for nothing, so
    // the page neither describes an exchange nor says one is offered next.
    val healthConnect = remember {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
    fun openSettings(action: String) {
        // Unavailable only when every attempt was refused: see [settingsAttempts].
        unavailable = !settings.openSettingsScreen(action, context.packageName)
    }
    fun request(access: OnboardingAccess) {
        unavailable = false
        if (access.permissions.isNotEmpty()) {
            val missing = access.permissions.filter {
                context.checkSelfPermission(it) !=
                    PackageManager.PERMISSION_GRANTED
            }
            val blocked = accessBlocked(missing, asked) { activity?.shouldShowRequestPermissionRationale(it) }
            if (blocked || missing.isEmpty()) {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            } else {
                asked = asked + missing
                // Android requires coarse and fine location in the same request, even when
                // approximate location was already granted on an earlier attempt.
                runtime.launch(access.permissions.toList().toTypedArray())
            }
        } else {
            openSettings(access.settingsAction())
        }
    }
    OnboardingPage(
        stringResource(R.string.import_access_title),
        lead = stringResource(accessLeadRes(healthConnect)),
        action = {
            OnboardingAction(stringResource(R.string.common_next), Icons.AutoMirrored.Outlined.ArrowForward, onNext)
        },
    ) {
        // Next on this page is what opens Health Connect's request, so what the exchange is stands
        // here, above the rows, where it is read before the button is reached: what is imported,
        // what is written back, and that the person chooses. Android's own screen lists the data
        // types and says none of that, and Play asks for it in the app, before the request. The
        // words are the Health Connect card's own, so the two cannot come to say different things.
        // Only where there is a request for Next to open.
        if (healthConnect) {
            Text(stringResource(R.string.sync_health_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.sync_health_body),
                Modifier.padding(top = 6.dp, bottom = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = PwnMuted,
            )
        }
        OnboardingAccess.entries.filter { it != OnboardingAccess.FULL_SCREEN || Build.VERSION.SDK_INT >= 34 }
            .forEach { access ->
                val granted = allowed[access] == true
                PwnCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    // Disabled once allowed rather than a button that swallows the tap: the row's
                    // own "Allowed" line already says why there is nothing left to press.
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !granted, role = Role.Button) {
                            request(access)
                        }.padding(PwnDimensions.cardPadding),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Image(themePainterResource(access.art), null, Modifier.size(34.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(access.title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(access.body),
                                style = MaterialTheme.typography.bodySmall,
                                color = PwnMuted,
                            )
                            Text(
                                stringResource(
                                    if (granted) R.string.onboarding_allowed else R.string.permission_intro_allow,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = PwnBlueInk,
                            )
                        }
                        if (granted) Icon(Icons.Outlined.CheckCircle, null, tint = PwnIndigo)
                    }
                }
            }
        // Appears after a settings screen failed to open, with focus still on the row that was
        // tapped, so it is announced rather than left to be found.
        if (unavailable) {
            Text(stringResource(R.string.permission_intro_settings), Modifier.politeLiveRegion(), color = PwnMuted)
        }
    }
}

/**
 * Whether a row's tap opens the app's settings page rather than the system's request: a permission
 * still [missing] that has been [asked] for twice, and for which the platform offers no
 * [rationale] (null while there is no activity to ask).
 *
 * Twice and not once. A dialog closed without an answer is no refusal and leaves the rationale where
 * it was — false until the first refusal — so once would send the second tap to settings while
 * the dialog would still come up. Two refusals are what stops the dialog, and after one
 * the rationale is true, so asking a second time costs a refusal nothing. A permission no dialog
 * shows any more — fixed by policy, or refused twice before [asked], which lives only as long as the
 * page, began counting — costs two taps that do nothing before settings.
 */
internal fun accessBlocked(missing: List<String>, asked: List<String>, rationale: (String) -> Boolean?): Boolean =
    missing.any { permission -> asked.count { it == permission } >= 2 && rationale(permission) == false }

/**
 * The page's opening paragraph: with its last sentence, that Health Connect is offered next, only
 * where it is ([healthConnect]). On a phone without it Next goes straight on, and the sentence
 * would promise a screen that never comes.
 */
internal fun accessLeadRes(healthConnect: Boolean): Int =
    if (healthConnect) R.string.onboarding_access_body else R.string.onboarding_access_body_alone

/**
 * What each attempt at a settings screen carries as its data, in the order they are tried: this
 * app's own row, and for Usage access the plain list after it, which is the null. The platform
 * documents that one screen as taking no input, and builds whose filter has no package scheme
 * refuse the row, so the list is tried rather than telling the person to find it by hand. The other
 * screens are documented to take the package, so there a refusal means the screen is not on the phone.
 */
internal fun settingsAttempts(action: String, packageName: String): List<String?> {
    val own = "package:$packageName"
    return if (action == Settings.ACTION_USAGE_ACCESS_SETTINGS) listOf(own, null) else listOf(own)
}

/**
 * Opens the settings screen [action] by each of its [settingsAttempts] until one is taken, and says
 * whether one was. The one opening for this page and for every Usage access button elsewhere
 * ([openUsageAccess]).
 */
internal fun ActivityResultLauncher<Intent>.openSettingsScreen(action: String, packageName: String): Boolean =
    settingsAttempts(action, packageName).any { data ->
        runCatching { launch(data?.let { Intent(action, it.toUri()) } ?: Intent(action)) }.isSuccess
    }

private enum class OnboardingAccess(val title: Int, val body: Int, val art: Int, vararg val permissions: String) {
    MOTION(
        R.string.onboarding_motion_title,
        R.string.onboarding_motion_body,
        R.drawable.ic_art_sensors,
        Manifest.permission.ACTIVITY_RECOGNITION,
    ),
    NOTIFICATIONS(
        R.string.onboarding_notifications_title,
        R.string.onboarding_notifications_body,
        R.drawable.ic_art_reminders,
        Manifest.permission.POST_NOTIFICATIONS,
    ),
    BLUETOOTH(
        R.string.direct_bluetooth_allow,
        R.string.onboarding_bluetooth_body,
        R.drawable.ic_art_sensors,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    ),
    LOCATION(
        R.string.track_title,
        R.string.track_permission_needed,
        R.drawable.ic_art_place,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ),
    EXACT_ALARMS(R.string.med_alarm_exact_access, R.string.med_alarm_settings_body, R.drawable.ic_art_schedule),
    FULL_SCREEN(R.string.med_alarm_full_screen_access, R.string.med_alarm_settings_body, R.drawable.ic_art_reminders),
    USAGE(R.string.profile_open_usage_access, R.string.profile_auto_sleep_body, R.drawable.ic_art_sleep),
    ;

    fun allowed(context: Context): Boolean = when (this) {
        EXACT_ALARMS -> context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        FULL_SCREEN -> MedicineAlarmNotification.fullScreenAllowed(context)

        USAGE -> hasUsageStatsAccess(context)

        NOTIFICATIONS -> context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

        // Location counts only once it is precise: a route is recorded from fine location alone
        // (TrackStartActivity, StepService), so after "Approximate" the row still offers the
        // upgrade, which the system's dialog asks for when it is tapped.
        else -> permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    fun settingsAction(): String = when (this) {
        EXACT_ALARMS -> Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM

        FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) {
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }

        USAGE -> Settings.ACTION_USAGE_ACCESS_SETTINGS

        else -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    }
}
