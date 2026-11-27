package com.pwnall.health

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

// The devices page and the Health Connect cards on it. The exchange they report on — what is read,
// what is written, how the two are kept in step — is HealthSync.kt, which draws nothing.

/**
 * The whole of what this app ever asks Health Connect for, as this device and this person make it.
 *
 * Every launcher goes through here — onboarding, the devices card and the import wizard — and that
 * is the point: built three times over, an age filter applied at one of the requests would be a
 * filter a minor walks around by opening either of the other two. What a person is
 * asked for is a promise the privacy policy makes in words, and a promise kept in one of three
 * places is not kept.
 *
 * It is also what the devices card counts against, so "connected" means every type this person was
 * actually offered rather than every type the app declares.
 */
@Composable
internal fun rememberHealthPermissions(birthDate: String?): Set<String> {
    val context = LocalContext.current
    val today = rememberToday()
    // Remembered: fifty-seven package-manager lookups, on a screen somebody is standing on.
    return remember(birthDate, today) {
        healthPermissionsKnownTo(context).let {
            if (sexLogOffered(birthDate, today)) it else withoutIntimateTypes(it)
        }
    }
}

/**
 * Asks for the exchange, and reports back what Health Connect holds under the body pair.
 *
 * Onboarding calls this after the weight and height pickers, as their finish, and two things come
 * of it. What comes back decides whether the wheels' values are news to Health Connect or its own
 * numbers handed back to it — someone whose scale already writes there should not have their figure
 * published a second time as this app's own. The grant itself is what gives the first exchange,
 * which fires the moment the profile saves, anything at all to carry: asked for a weight and a
 * height alone, a new phone would count its own steps and nothing else would cross either way
 * until somebody found the devices screen.
 *
 * It asks for what that screen asks for, each type named beside its feature on the rationale
 * screen, less sexual activity for anyone the age gate does not offer that screen to. A device
 * without Health Connect, or a refused request, simply reports nothing and the pickers keep what
 * they were given.
 */
@Composable
internal fun rememberHealthConnectPrefill(
    birthDate: String?,
    onResult: (HealthConnectBody?) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val available = remember {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
    val requested = rememberHealthPermissions(birthDate)
    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        scope.launch {
            // A screen that left while Health Connect answered is not one that heard "nothing".
            val body = runCatching { readHealthConnectBody(context) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            onResult(body)
        }
    }
    return {
        // A Health Connect that reports itself available but has no screen to take the request answers
        // nothing, as a missing one does: onboarding's Next goes on rather than closing the app.
        if (!available || runCatching { launcher.launch(requested) }.isFailure) onResult(null)
    }
}

private suspend fun readHealthConnectBody(context: android.content.Context): HealthConnectBody {
    val client = HealthConnectClient.getOrCreate(context)
    val now = Instant.now()
    val weight = client.readRecords(
        ReadRecordsRequest(
            recordType = WeightRecord::class,
            timeRangeFilter = TimeRangeFilter.before(now),
            ascendingOrder = false,
            pageSize = 1,
        ),
    ).records.firstOrNull()?.weight?.inKilograms?.takeIf { it in PLAUSIBLE_KG }
    val height = client.readRecords(
        ReadRecordsRequest(
            recordType = HeightRecord::class,
            timeRangeFilter = TimeRangeFilter.before(now),
            ascendingOrder = false,
            pageSize = 1,
        ),
    ).records.firstOrNull()?.height?.inMeters?.times(100.0)?.roundToInt()?.takeIf { it in 50..250 }
    return HealthConnectBody(weight, height)
}

/**
 * Health Connect as seen from inside the app: what is allowed, when the last exchange ran and how
 * it ended, and the ways back in.
 *
 * The exchange itself runs on its own at every app start and return, so the card carries no plain
 * "sync now"; what it shows is how the last exchange went, and "Try again" appears only when it
 * failed. The import page behind the door below runs one on demand. What cannot be automatic is
 * access, so the card keeps speaking once anything at all is allowed: somebody who ticked steps
 * during onboarding and refused the rest needs a way in the app to allow the rest later, the time
 * of the last exchange, and an error when it fails. Health Connect also stops showing its own dialog
 * after two refusals, so its settings page is the one door that always opens, and the card holds
 * it. The count of allowed grants is a count and not a complaint: allowing steps and refusing the
 * cycle is honoured everywhere else in the app, and here it reads as a number.
 */
@Composable
private fun HealthConnectAccessCard(birthDate: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Asked again at every return: the update the card sends the person to the store for is made
    // there, and the card would otherwise come back from it still saying one is needed.
    var sdkStatus by remember { mutableIntStateOf(HealthConnectClient.getSdkStatus(context)) }
    LifecycleResumeEffect(Unit) {
        sdkStatus = HealthConnectClient.getSdkStatus(context)
        onPauseOrDispose { }
    }
    val client = remember(sdkStatus) {
        if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context.applicationContext)
        } else {
            null
        }
    }
    val requested = rememberHealthPermissions(birthDate)
    // The granted set arrives from a binder call, so it is unknown until the answer is in. A query
    // that failed is kept as a failure: defaulting it to "nothing granted" would show Connect to
    // people who are connected.
    var granted by remember { mutableStateOf<Result<Set<String>>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { permissions -> granted = Result.success(permissions) }
    // Asked again at every return to the foreground: Health Connect's own settings page is the
    // other place the grant changes, and this card is what the person comes back to from it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(client, lifecycle) {
        if (client == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Cancellation here is the lifecycle dropping below RESUMED mid-call, not Health
            // Connect refusing to answer: it travels on, so the card does not flash "could not
            // read" on the way out and settle only at the next resume.
            granted = runCatching { client.permissionController.getGrantedPermissions() }
                .onFailure { if (it is CancellationException) throw it }
        }
    }
    val status = healthSyncStatusState.value ?: remember { healthSyncStatus(context) }
    // The tap's own flag covers the moment before the launched job has counted itself in; the
    // process-wide one covers the rotation, which resets this composition but not the exchange.
    var retrying by remember { mutableStateOf(false) }

    // The app's card rather than the bare M3 one, the same container and corners as every other
    // card on this page: a bare Card here would read as a second app stacked on the first.
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // What the exchange does is worth reading once, behind the title's circle rather than as a
            // grey paragraph on every visit; the counts, the last exchange and the errors stay on the card.
            DeviceCardTitle(stringResource(R.string.sync_health_title)) {
                Text(stringResource(R.string.sync_health_body))
            }
            if (client == null) {
                // A phone whose Health Connect is missing or too old is a Play Store update away
                // from working, and silence would read as "this app does not do Health Connect".
                Text(
                    stringResource(syncUnavailableText(sdkStatus)),
                    color = MaterialTheme.colorScheme.error,
                )
                // The update has a door, the store page Health Connect's own guide sends apps to; a
                // phone with no store to open it leaves the line above to say where to go.
                if (sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                    OutlinedButton(
                        onClick = {
                            val store = Intent(Intent.ACTION_VIEW, HEALTH_CONNECT_UPDATE.toUri())
                            runCatching { context.startActivity(store) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.sync_health_update)) }
                }
            } else {
                HealthConnectStatus(
                    granted = granted,
                    requested = requested,
                    status = status,
                    running = retrying || healthSyncRunningState.value,
                    onAllow = { launcher.launch(requested) },
                    onRetry = {
                        retrying = true
                        scope.launch {
                            val db = StepsDb.get(context)
                            db.dao().profileNow()?.let {
                                autoSyncHealthConnectIfDue(context, db, it, force = true)
                            }
                            retrying = false
                        }
                    },
                )
            }
        }
    }
}

/**
 * The lines and buttons of a card whose Health Connect exists. Which buttons appear follows from
 * the two numbers: nothing allowed offers Connect, some allowed offers the rest, a failed exchange
 * offers another go, and the settings page is offered whatever the numbers say.
 */
@Composable
private fun HealthConnectStatus(
    granted: Result<Set<String>>?,
    requested: Set<String>,
    status: HealthSyncStatus,
    running: Boolean,
    onAllow: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val allowed = granted?.map { permissions -> permissions.count { it in requested } }
    val allowedCount = allowed?.getOrNull()
    when {
        allowed == null -> Unit

        allowedCount == null -> Text(
            stringResource(R.string.sync_health_permissions_unreadable),
            color = MaterialTheme.colorScheme.error,
        )

        else -> Text(stringResource(R.string.sync_health_allowed, allowedCount, requested.size), color = muted)
    }
    // One line for the exchange, whose words change in place: running, then how it ended. A
    // greyed "Try again" alone would be the only sign of a run, and the outcome would replace it in
    // silence; as one live node, the change is spoken and the running state is written out in words.
    val failed = !running && status.error != null
    Text(
        when {
            running -> stringResource(R.string.m3_sync_health_running)

            // The raw error is an exception class and an English platform message; it stays in
            // the preferences as a lead, and the card says what kind of failure it was.
            failed -> stringResource(R.string.sync_health_last_failed, stringResource(status.errorKind.reasonRes()))

            status.lastSuccessMs > 0L -> stringResource(
                R.string.sync_health_last_exchange,
                DateUtils.getRelativeTimeSpanString(
                    status.lastSuccessMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ),
                status.imported,
                status.exported,
            )

            else -> stringResource(R.string.sync_health_not_synced)
        },
        color = if (failed) MaterialTheme.colorScheme.error else muted,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    // The exchange ran and was refused this one type. Without this line the day simply reads zero
    // and the card reports a success: the person is told the steps are not there, when what
    // happened is that nobody was allowed to look.
    if (status.stepsBlocked && status.error == null) {
        Text(
            stringResource(R.string.sync_health_steps_blocked),
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (status.stepsWriteBlocked && status.error == null) {
        Text(
            stringResource(R.string.sync_health_steps_write_blocked),
            color = MaterialTheme.colorScheme.error,
        )
    }
    // Records Health Connect holds and counts in no total. Nothing in the app can put an app on
    // that list — it is the person's, and only Health Connect's own screen changes it — so naming
    // the app and the screen is the whole of what can be done.
    val uncountedSource = status.uncountedStepSource
    if (uncountedSource != null && status.error == null) {
        Text(
            stringResource(R.string.sync_health_steps_uncounted, installedAppName(context, uncountedSource)),
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (allowedCount == 0) {
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sync_connect))
        }
    } else if (allowedCount != null && allowedCount < requested.size) {
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sync_health_allow_more))
        }
    }
    if (status.error != null) {
        OutlinedButton(onClick = onRetry, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sync_health_retry))
        }
    }
    OutlinedButton(
        // A Health Connect with no settings page to open is the "unavailable" case the card already
        // names in words, so a refusal here is ignored rather than shown twice. When a source is
        // going uncounted, the door is the manage-data screen instead: the source list is on it,
        // and the line above has just asked the person to go there.
        onClick = {
            // Two actions for one door. `ACTION_HEALTH_CONNECT_SETTINGS` is the pre-Android-14
            // APK's own action; from 14 Health Connect is part of the platform and answers to
            // `HEALTH_HOME_SETTINGS` instead, and nothing on such a device resolves the older one —
            // so that one alone would open nothing at all on every version above the app's minimum.
            // Tried in turn, newest first, because minSdk 33 still reaches the APK.
            val doors = if (uncountedSource != null) {
                listOf(HealthConnectClient.getHealthConnectManageDataIntent(context))
            } else {
                listOf(
                    Intent(ACTION_HEALTH_HOME_SETTINGS),
                    @Suppress("DEPRECATION")
                    Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
                )
            }
            for (door in doors) {
                if (runCatching { context.startActivity(door) }.isSuccess) break
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.sync_health_open)) }
}

/**
 * Two of the profile's doors on one frame, told apart by [imports].
 *
 * The devices page holds the instruments that have no form of their own. A scale under a weight, a
 * strap under a pulse and a machine under an exercise are asked for from the form that wants the
 * number, in a modal over it; what is left is everything with no such form — a monitor, a meter, an
 * oximeter, a thermometer, a ring — and nothing else: Bluetooth only.
 *
 * The import page is how a history kept in another app comes in: Health Connect's card — how much
 * is allowed, how the last exchange went — with the door to the page that runs the exchange on
 * demand, and the file another app exported, which needs no Health Connect at all. The exchange
 * itself runs at every app start and return.
 */
@Composable
fun DevicesScreen(modifier: Modifier, profile: UserProfile, onBack: () -> Unit, imports: Boolean = false) {
    val context = LocalContext.current
    // The import wizard is a page of this screen rather than a screen of the app: it is reached
    // from the card under the Health Connect one and from nowhere else, and back — the arrow or
    // the gesture — returns here rather than leaving.
    var page by rememberSaveable(stateSaver = DEVICES_PAGE_SAVER) { mutableStateOf(DevicesPage.MAIN) }
    val healthConnectAvailable = remember {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
    // A page restored after the provider went away would open on a client that cannot be built.
    val importing = page == DevicesPage.IMPORT && healthConnectAvailable
    androidx.activity.compose.BackHandler(enabled = importing) { page = DevicesPage.MAIN }
    // A saved reading is confirmed over the middle of the page, where the eye already is.
    var status by rememberStatusLine()
    // The import page is held open over this one as a panel — see [PwnPageHost] — and this one, as
    // it stood when the import was opened, is the picture behind it.
    val behind = rememberBackdropShot()
    // Held here, above the two pages that share the Column below, because the page leaves
    // composition while the import is open over it: one scroll inside it would come back at the top
    // after every import, and drop the import page in at the offset this page was left at.
    val mainScroll = rememberScrollState()
    val importScroll = rememberSaveable(importing, saver = ScrollState.Saver) { ScrollState(0) }
    PwnPageHost(
        sheet = importing,
        onClose = { page = DevicesPage.MAIN },
        modifier = modifier,
        backdrop = behind.backdrop,
    ) { pageBase ->
        Box(pageBase.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                val title = stringResource(
                    when {
                        importing -> R.string.import_title
                        imports -> R.string.import_apps_title
                        else -> R.string.direct_bluetooth_title
                    },
                )
                PwnTopBar(
                    title,
                    onBack = { if (importing) page = DevicesPage.MAIN else onBack() },
                ) {
                    // What the exchange does and what it cannot reach, once, behind the question mark.
                    if (importing) HelpDot(title) { ImportExplained() }
                }
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .panelVerticalScroll(if (importing) importScroll else mainScroll)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Spacer(Modifier.height(2.dp))
                    when {
                        // The running flag lives beside the exchange and is private to it; the page
                        // is handed its value so a rotation cannot leave "Import now" enabled over a
                        // running import.
                        importing -> ImportWizardPage(profile, syncRunning = healthSyncRunningState.value)

                        imports -> {
                            HealthConnectAccessCard(profile.birthDate)
                            if (healthConnectAvailable) ImportWizardDoor { behind.open { page = DevicesPage.IMPORT } }
                            // Outside the Health Connect guard above it on purpose: a file another
                            // app exported is read off storage and needs no provider, and a phone
                            // without one has no other way to bring a history in.
                            WorkoutFileImportCard(profile)
                        }

                        else -> DirectBluetoothSection(profile, onSaved = { status = it })
                    }
                }
            }
            PwnActionFlash(status, Modifier.align(Alignment.Center))
        }
    }
}

/**
 * A card title on the devices page: a heading a screen reader can jump to, and beside it the
 * circle that holds what the card is about. [content] is that explanation.
 */
@Composable
internal fun DeviceCardTitle(title: String, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        HelpDot(title, content)
    }
}

/**
 * A rule between entries on a card of this page. The default outlineVariant is nearly the card's
 * own colour and the rules would vanish; the outline at this opacity measures 1.57:1 on the card by
 * day and 1.85:1 by night, a little firmer than the 1.39:1 of Material's baseline rule on its
 * container.
 */
@Composable
internal fun DeviceCardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = CARD_RULE_ALPHA))
}

private const val CARD_RULE_ALPHA = 0.4f

/** Health Connect's store page, with the return its guide gives to an app that asked for the update. */
private const val HEALTH_CONNECT_UPDATE =
    "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
