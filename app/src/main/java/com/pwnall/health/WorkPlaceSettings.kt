package com.pwnall.health

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The automatic start by location, the Work settings page's third block: off until the person turns
 * it on, and asked one thing at a time. First the point — where they stand, saved when they say so —
 * then how far from it counts, then the switch, then whatever Android still has to allow, one grant
 * at a time and each said for before it is asked. What the feature does with the position, when it
 * checks and when it stops a session is behind the block's "?" ([WorkPlaceHelp]).
 *
 * Location all the time is asked for only from here, after precise location, and its card says
 * what it is for before the tap that asks: Android shows its own screen for it, and nothing else
 * the app does needs it.
 */
@Composable
internal fun WorkPlaceSection(onFlash: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val activity = LocalActivity.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(WorkPlaceStore.snapshot(app)) }
    var missing by remember { mutableStateOf(missingWorkPlaceAccess(app)) }
    var locating by remember { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // Set when Save was pressed without precise location, so the grant carries on into the fix.
    var saveOnGrant by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            WorkPlaceChecks.reschedule(app)
            state = WorkPlaceStore.snapshot(app)
            missing = missingWorkPlaceAccess(app)
        }
    }

    fun locate() {
        locating = true
        scope.launch {
            // A fix the person waited for is not dropped because the page was left.
            val fix = withContext(NonCancellable) { workplaceFix(app, WORK_FIX_TIMEOUT_MS) }
            locating = false
            if (fix == null) {
                onFlash(resources.getString(R.string.work_place_no_fix))
            } else if (!workplaceFixPreciseEnough(fix)) {
                // Indoors Android often has only a network position; saved, it would be the
                // centre of every later check. The point already saved, if any, stays.
                onFlash(resources.getString(R.string.work_place_rough_fix))
            } else {
                val radius = WorkPlaceStore.place(app)?.radiusM ?: WORK_RADIUS_DEFAULT_M
                WorkPlaceStore.savePlace(
                    app,
                    WorkPlace(fix.lat, fix.lon, radius, fix.accuracyM, System.currentTimeMillis()),
                )
                onFlash(resources.getString(R.string.work_flash_saved))
            }
            refresh()
        }
    }

    val locationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (saveOnGrant && it[Manifest.permission.ACCESS_FINE_LOCATION] == true) locate()
        saveOnGrant = false
        refresh()
    }
    val singleRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    fun openSettings(action: String) {
        val intent = when (action) {
            Settings.ACTION_APP_NOTIFICATION_SETTINGS ->
                Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)

            // The notices' own channel, which has to exist for Android to open its page.
            Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS -> {
                app.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(WorkPlaceChecks.noticeChannel(app))
                Intent(action)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, WorkPlaceChecks.NOTICE_CHANNEL)
            }

            else -> Intent(action, "package:${app.packageName}".toUri())
        }
        runCatching { settings.launch(intent) }
    }

    // Once Android has stopped showing its own prompt — refused twice, or "don't ask again" —
    // asking again does nothing at all, so the app's own settings page is opened instead. Decided as
    // on the onboarding page ([accessBlocked]), so [asked] keeps every request, repeats included.
    fun blocked(permission: String): Boolean =
        accessBlocked(listOf(permission), asked) { activity?.shouldShowRequestPermissionRationale(it) }

    fun askPrecise() {
        if (blocked(Manifest.permission.ACCESS_FINE_LOCATION)) {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        } else {
            asked = asked + Manifest.permission.ACCESS_FINE_LOCATION
            // Android ignores a request for precise location that does not name approximate too.
            locationRequest.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    fun askSingle(permission: String) {
        if (blocked(permission)) {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        } else {
            asked = asked + permission
            singleRequest.launch(permission)
        }
    }

    fun request(access: WorkPlaceAccess) {
        when (access) {
            WorkPlaceAccess.PRECISE -> askPrecise()

            WorkPlaceAccess.BACKGROUND -> askSingle(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

            WorkPlaceAccess.EXACT_ALARMS -> openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)

            WorkPlaceAccess.NOTIFICATIONS -> when {
                !app.hasPermission(Manifest.permission.POST_NOTIFICATIONS) ->
                    askSingle(Manifest.permission.POST_NOTIFICATIONS)

                !app.getSystemService(NotificationManager::class.java).areNotificationsEnabled() ->
                    openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)

                else -> openSettings(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            }
        }
    }

    // Saving and moving the point both take the position here and now, and need precise location.
    val saveHere: () -> Unit = {
        if (app.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            locate()
        } else {
            saveOnGrant = true
            askPrecise()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val place = state.place
        if (place == null) {
            OutlinedButton(
                onClick = saveHere,
                enabled = !locating,
                modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.minimumTarget),
            ) {
                Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(PwnDimensions.buttonGap))
                Text(stringResource(if (locating) R.string.work_place_locating else R.string.work_place_save))
            }
            return@Column
        }
        WorkPlaceCard(
            place = place,
            locating = locating,
            onMove = saveHere,
            onRemove = {
                WorkPlaceStore.forget(app)
                onFlash(resources.getString(R.string.work_flash_deleted))
                // Which also takes the alarm away, off the main thread: with no point, nothing is ready.
                refresh()
            },
        )
        WorkPlaceRadius(place.radiusM) {
            WorkPlaceStore.setRadius(app, it)
            state = WorkPlaceStore.snapshot(app)
        }
        PwnSwitchRow(
            title = stringResource(R.string.work_place_switch),
            checked = state.enabled,
            onCheckedChange = { on ->
                // Switched off, this also forgets what the checks were following.
                WorkPlaceStore.setEnabled(app, on)
                refresh()
            },
        )
        if (state.enabled) {
            missing.firstOrNull()?.let { access -> WorkPlaceAccessCard(access) { request(access) } }
            if (missing.none { it.required }) {
                Text(nextCheckText(resources, state.nextCheckAt), color = PwnMuted)
            }
        }
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** Everything the automatic start does, behind the "?" at the end of its block's header. */
@Composable
internal fun WorkPlaceHelp() {
    HelpDot(stringResource(R.string.work_place_title)) {
        Text(stringResource(R.string.work_place_help_what))
        Text(
            stringResource(
                // The round, the four weeks and the three sessions are written into the words,
                // which WorkPlaceTest holds to the constants; the clock times are formatted here.
                R.string.work_place_help_when,
                clockText(WORK_MORNING_DEFAULT_MINUTE),
                clockText(WORK_MORNING_LATEST_MINUTE),
                clockText(WORK_EVENING_MINUTE),
                clockText(WORK_PHONE_WAKE_FLOOR_MINUTE),
            ),
        )
        Text(stringResource(R.string.work_place_help_leave))
        Text(stringResource(R.string.work_place_help_yours))
        Text(stringResource(R.string.work_place_help_access))
    }
}

/** The saved point: when it was saved and how sure Android was, moved or removed from here. */
@Composable
private fun WorkPlaceCard(place: WorkPlace, locating: Boolean, onMove: () -> Unit, onRemove: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val saved = remember(place.savedAt, locale) { savedText(place.savedAt, locale) }
    // On the page's own surface: inside its numbered block, a card in the block's tone would vanish.
    PwnCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.work_place_point), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (place.accuracyM.isFinite()) {
                            stringResource(R.string.work_place_saved_within, saved, place.accuracyM.toInt())
                        } else {
                            stringResource(R.string.work_place_saved, saved)
                        },
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onRemove, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.work_place_remove))
                }
            }
            TextButton(onClick = onMove, enabled = !locating) {
                Text(stringResource(if (locating) R.string.work_place_locating else R.string.work_place_move))
            }
        }
    }
}

/** How far from the point still counts as being there, on the same large ruler as the long day. */
@Composable
private fun WorkPlaceRadius(radiusM: Int, onRadius: (Int) -> Unit) {
    val resources = LocalResources.current
    val value = WORK_RADII_M.minBy { abs(it - radiusM) }
    Column {
        Text(stringResource(R.string.work_place_radius), fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.work_place_radius_value, value),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        RulerPicker(
            values = WORK_RADII_M,
            value = value,
            onValue = onRadius,
            label = { resources.getString(R.string.work_place_radius_value, it) },
            height = 104.dp,
            spacing = 26.dp,
            major = { it % 100 == 0 },
        )
    }
}

/**
 * The next grant Android has to give, with what it is for in front of the person before the tap
 * that asks for it — the order the platform requires for location all the time.
 */
@Composable
private fun WorkPlaceAccessCard(access: WorkPlaceAccess, onAllow: () -> Unit) {
    val (title, body) = when (access) {
        WorkPlaceAccess.PRECISE ->
            stringResource(R.string.work_place_access_precise_title) to
                stringResource(R.string.work_place_access_precise_body)

        WorkPlaceAccess.BACKGROUND ->
            stringResource(R.string.work_place_access_background_title) to
                stringResource(R.string.work_place_access_background_body)

        WorkPlaceAccess.EXACT_ALARMS ->
            stringResource(R.string.work_place_access_exact_title) to
                stringResource(R.string.work_place_access_exact_body)

        // Named, since the notices come on a channel of their own that can be blocked on its own.
        WorkPlaceAccess.NOTIFICATIONS ->
            stringResource(R.string.work_place_access_notifications_title) to
                stringResource(
                    R.string.work_place_access_notifications_body,
                    stringResource(R.string.work_place_notice_channel),
                )
    }
    PwnCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onAllow)
                .padding(PwnDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
            Text(
                stringResource(R.string.permission_intro_allow),
                style = MaterialTheme.typography.labelLarge,
                color = PwnBlueInk,
            )
        }
    }
}

private fun savedText(at: Long, locale: Locale): String {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    return dayMonthClock(time, localizedDatePattern(locale, "dMMM", "d MMM"))
}

/** When the alarm is next set for, as the page prints it; it says nothing about what that look will find. */
private fun nextCheckText(resources: Resources, at: Long): String {
    if (at <= 0L) return resources.getString(R.string.work_place_next_none)
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val clock = clockText(time.hour * 60 + time.minute)
    val today = LocalDate.now(zone)
    return when (time.toLocalDate()) {
        today -> resources.getString(R.string.work_place_next_today, clock)

        today.plusDays(1) -> resources.getString(R.string.work_place_next_tomorrow, clock)

        else -> resources.getString(
            R.string.work_place_next_day,
            time.dayOfWeek.getDisplayName(TextStyle.FULL, resources.configuration.locales[0]),
            clock,
        )
    }
}
