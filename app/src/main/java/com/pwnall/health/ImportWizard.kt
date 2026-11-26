package com.pwnall.health

import android.content.res.Resources
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipException

/** The import screen's two pages: its own cards, and the Health Connect wizard opened from one of them. */
internal enum class DevicesPage { MAIN, IMPORT }

/** An enum is not one of the types a Bundle knows, so the open page travels as its name. */
internal val DEVICES_PAGE_SAVER: Saver<DevicesPage, String> =
    Saver(save = { it.name }, restore = { DevicesPage.valueOf(it) })

/**
 * Whether the grant that reaches past thirty days is held, missing, or not something this phone's
 * Health Connect defines at all — the third is an older provider, which [healthPermissionsKnownTo]
 * has already stopped asking for, and telling its owner to allow a grant that does not exist would
 * send them looking for a switch that is not there.
 */
internal enum class HistoryGrant { ALLOWED, MISSING, UNSUPPORTED }

/**
 * What the import page says about access, reduced from two permission sets to three facts.
 *
 * [allowed] counts only what was asked for: Health Connect answers with everything the app holds,
 * including grants an earlier build requested and this one no longer does, and those would carry
 * the count past [requested]. The history grant is read on its own because the reach of the import
 * turns on it alone.
 */
internal data class ImportAccess(val allowed: Int, val requested: Int, val history: HistoryGrant) {
    val complete: Boolean get() = allowed >= requested
}

internal fun importAccess(granted: Set<String>, requested: Set<String>): ImportAccess {
    val historyPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    return ImportAccess(
        allowed = granted.count { it in requested },
        requested = requested.size,
        history = when {
            historyPermission !in requested -> HistoryGrant.UNSUPPORTED
            historyPermission in granted -> HistoryGrant.ALLOWED
            else -> HistoryGrant.MISSING
        },
    )
}

/**
 * Whether "Import now" would do anything. The exchange returns at once when nothing at all is
 * allowed, and a button that does nothing when tapped is worse than one that says it cannot; a
 * grant set that could not be read is treated the same way, because the exchange reads the same
 * set and stops on the same answer. A tap during an exchange would queue a second full pass behind
 * the first, so the button waits for it wherever in the process it was started.
 */
internal fun importReady(access: ImportAccess?, running: Boolean): Boolean =
    access != null && access.allowed > 0 && !running

/** One kind's share of the imported rows, as [StepsDao.importedKindCounts] groups them. */
data class ImportedKindCount(val kind: String, val count: Int)

/**
 * The journal's own word for each kind, for a count that has no row to take a title from.
 *
 * A dispatch table like [JournalEntry.visual]'s, and for the same reason. The strings are the ones
 * the screen for each kind already wears, so the breakdown calls a thing what the rest of the app
 * calls it. A measurement row says what it is by which column is filled — the scales, the strap,
 * the tape — and a count per kind has already thrown the columns away, so measurements are one line.
 */
private val IMPORTED_KIND_TITLES: Map<String, Int> = mapOf(
    KIND_MEASUREMENT to R.string.import_kind_measurements,
    KIND_EAT to R.string.calendar_food,
    KIND_ACTIVITY to R.string.home_workouts,
    KIND_WATER to R.string.water_title,
    KIND_CAFFEINE to R.string.caffeine_title,
    KIND_CYCLE to R.string.cycle_title,
    KIND_CYCLE_END to R.string.cycle_title,
    KIND_CYCLE_LOG to R.string.cycle_title,
    KIND_MASTURBATION to R.string.sexual_title,
    KIND_MORNING_CHECK to R.string.sexual_title,
    KIND_BREATHING to R.string.breathing_title,
    KIND_PLAN to R.string.health_rationale_type_planned,
    KIND_NOT_DONE to R.string.home_workouts,
    KIND_MOOD to R.string.mood_title,
    KIND_MED to R.string.med_title,
    KIND_LABS to R.string.labs_title,
    KIND_ALCOHOL to R.string.alcohol_title,
    KIND_SMOKING to R.string.smoking_title,
    KIND_STOOL to R.string.bathroom_title,
    KIND_URINATION to R.string.bathroom_title,
    KIND_WORK to R.string.work_title,
    KIND_PAIN to R.string.pain_title,
    KIND_SYMPTOM to R.string.symptom_title,
)

/** A kind this build has never heard of is still counted, under the same heading the sources use. */
internal fun importedKindTitle(kind: String): Int = IMPORTED_KIND_TITLES[kind] ?: R.string.sources_metric_other

/**
 * The way into the wizard, on the import page under the Health Connect card.
 *
 * Drawn only where Health Connect is available: the page behind it opens on a permission request
 * and a live client, neither of which a phone without the provider can produce, and the card above
 * already says in words what that phone is missing.
 */
@Composable
internal fun ImportWizardDoor(onClick: () -> Unit) {
    PwnCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            PwnGlyph(
                R.drawable.ic_art_history,
                Icons.Outlined.History,
                PwnBlueSoft,
                null,
                Modifier.size(46.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.import_title), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.import_door_body),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.NavigateNext, null, tint = PwnMuted)
        }
    }
}

/**
 * The import wizard: what the exchange does, what it may read, a button that runs it now, and a
 * mirror of what it has brought in so far.
 *
 * Nothing here changes the exchange. It already runs at every launch, and "Import now" is the
 * card's "Try again" under a name that says what it is for. What the wizard adds is a place that
 * explains the migration before the history grant is asked for, asks for it in context rather
 * than as one line in the general dialog, and shows what arrived — which is also the visible
 * feature the history grant is declared against.
 *
 * [syncRunning] is handed in because the flag lives beside the exchange, private to it: a rotation
 * resets this composition but not the job it started, and the button must not come back enabled
 * over a running import.
 */
@Composable
internal fun ImportWizardPage(profile: UserProfile, syncRunning: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { HealthConnectClient.getOrCreate(context.applicationContext) }
    val requested = rememberHealthPermissions(profile.birthDate)
    // Unknown until the binder call answers, and a failed answer is kept as one: defaulting it to
    // "nothing granted" would offer the request to somebody who has already allowed everything.
    var granted by remember { mutableStateOf<Result<Set<String>>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { permissions -> granted = Result.success(permissions) }
    // Asked again at every return to the foreground, as the card above does: Health Connect's own
    // settings page is the other place the grant changes, and its dialog is a trip out and back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(client, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Cancellation is the lifecycle dropping below RESUMED mid-call, not a refusal, and it
            // travels on rather than settling as "could not read" until the next resume.
            granted = runCatching { client.permissionController.getGrantedPermissions() }
                .onFailure { if (it is CancellationException) throw it }
        }
    }
    val access = granted?.getOrNull()?.let { importAccess(it, requested) }
    val dao = remember { StepsDb.get(context).dao() }
    val importedCount by remember { dao.importedJournalCount() }.collectAsStateWithLifecycle(0)
    val oldestDay by remember { dao.oldestImportedDay() }.collectAsStateWithLifecycle(null)
    val kinds by remember { dao.importedKindCounts() }.collectAsStateWithLifecycle(emptyList())
    val status = healthSyncStatusState.value ?: remember { healthSyncStatus(context) }
    // The tap's own flag covers the moment before the launched job has counted itself in.
    var starting by remember { mutableStateOf(false) }
    val running = starting || syncRunning

    ImportAccessCard(
        access = access,
        unreadable = granted?.isFailure == true,
        onAllow = { launcher.launch(requested) },
    )
    ImportRunCard(
        status = status,
        running = running,
        ready = importReady(access, running),
        nothingAllowed = access != null && access.allowed == 0,
        onImport = {
            starting = true
            scope.launch {
                val db = StepsDb.get(context)
                // The saved profile is what the exchange exports under; the one the screen was
                // opened with is the fallback for a database that has none yet.
                autoSyncHealthConnectIfDue(context, db, db.dao().profileNow() ?: profile, force = true)
                starting = false
            }
        },
    )
    ImportedRecordsCard(count = importedCount, oldestDay = oldestDay, kinds = kinds)
}

/**
 * What the exchange does, in the three lines that decide whether the grant is worth giving, and
 * what it cannot reach — for the question mark in the import page's bar.
 */
@Composable
internal fun ImportExplained() {
    Text(stringResource(R.string.import_how_1))
    Text(stringResource(R.string.import_how_2))
    Text(stringResource(R.string.import_how_3))
    Text(stringResource(R.string.import_takeout_note))
}

/**
 * How much of the request is allowed and whether the history grant is part of it, with the request
 * itself as the button. The button stays until everything asked for is allowed, and goes when it
 * is: a request for grants already held is a dialog that closes itself.
 */
@Composable
private fun ImportAccessCard(access: ImportAccess?, unreadable: Boolean, onAllow: () -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.import_access_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            when {
                unreadable -> Text(
                    stringResource(R.string.sync_health_permissions_unreadable),
                    color = MaterialTheme.colorScheme.error,
                )

                access != null -> {
                    Text(
                        stringResource(R.string.sync_health_allowed, access.allowed, access.requested),
                        color = PwnMuted,
                    )
                    Text(
                        stringResource(historyText(access.history)),
                        color = if (access.history == HistoryGrant.MISSING) PwnWarn else PwnMuted,
                    )
                    // The one line of what is lost without it, in the words the rationale screen
                    // already uses for the same grant.
                    if (access.history == HistoryGrant.MISSING) {
                        Text(
                            stringResource(R.string.health_rationale_purpose_history),
                            color = PwnMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (access != null && !access.complete) {
                Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.import_allow))
                }
            }
        }
    }
}

private fun historyText(history: HistoryGrant): Int = when (history) {
    HistoryGrant.ALLOWED -> R.string.import_history_allowed
    HistoryGrant.MISSING -> R.string.import_history_missing
    HistoryGrant.UNSUPPORTED -> R.string.import_history_unsupported
}

/**
 * The button, and what the last exchange left to say. The line is the card's own status line —
 * when, how much crossed each way, or the error in words — because the exchange writes one record
 * of itself and two screens reading it differently would disagree about the same run.
 */
@Composable
private fun ImportRunCard(
    status: HealthSyncStatus,
    running: Boolean,
    ready: Boolean,
    nothingAllowed: Boolean,
    onImport: () -> Unit,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.import_running), Modifier.politeLiveRegion(), color = PwnMuted)
                }

                status.error != null -> Text(
                    stringResource(R.string.sync_health_last_failed, status.error),
                    Modifier.politeLiveRegion(),
                    color = MaterialTheme.colorScheme.error,
                )

                status.lastSuccessMs > 0L -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            R.string.sync_health_last_exchange,
                            DateUtils.getRelativeTimeSpanString(
                                status.lastSuccessMs,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ),
                            status.imported,
                            status.exported,
                        ),
                        color = PwnMuted,
                    )
                    Text(
                        stringResource(
                            R.string.import_last_breakdown,
                            status.records,
                            status.workouts,
                            status.stepDays,
                        ),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                else -> Text(stringResource(R.string.sync_health_not_synced), color = PwnMuted)
            }
            if (nothingAllowed) {
                Text(
                    stringResource(R.string.import_needs_access),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onImport, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.import_now))
            }
        }
    }
}

/**
 * What the journal holds from other apps, straight from the database: the count, the oldest day —
 * which is how far back the history actually reached, and the plainest reading of whether the
 * history grant did its work — and a line per kind. Live, so the rows an import is writing appear
 * as it writes them.
 */
@Composable
private fun ImportedRecordsCard(count: Int, oldestDay: String?, kinds: List<ImportedKindCount>) {
    val locale = LocalLocale.current.platformLocale
    val format = remember(locale) { localizedDatePattern(locale, "yMMMd", "d MMM yyyy") }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.import_mirror_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (count == 0) {
                Text(stringResource(R.string.import_mirror_empty), color = PwnMuted)
            } else {
                Text(pluralStringResource(R.plurals.backup_records, count, count))
                oldestDay?.let {
                    Text(
                        stringResource(R.string.import_mirror_since, dayLabel(it, format)),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                kinds.forEach { KindRow(it) }
            }
        }
    }
}

@Composable
private fun KindRow(row: ImportedKindCount) {
    val visual = remember(row.kind) { kindVisual(row.kind) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PwnGlyph(visual.art, visual.icon, visual.background, null, Modifier.size(38.dp))
        Text(stringResource(importedKindTitle(row.kind)), Modifier.weight(1f))
        // In the reader's own digits, as the total above it is: toString() writes ASCII in every language.
        Text(row.count.toLong().formatted(), color = PwnMuted)
    }
}

/**
 * Announced when it appears or changes. The lines this goes on report local work that finishes on
 * its own schedule, after the button that started it has gone away.
 */
internal fun Modifier.politeLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** A stored day spoken as a date; unparseable, it is printed as it was stored rather than dropped. */
internal fun dayLabel(day: String, format: DateTimeFormatter): String =
    runCatching { LocalDate.parse(day).format(format) }.getOrDefault(day)

/**
 * The other road into the journal: a file another logger exported, read off the phone's storage.
 *
 * A sibling of [ImportWizardDoor] and deliberately not behind it. Health Connect is how a history
 * arrives from an app that publishes to it; this reads the exports themselves — those of five
 * training loggers, which publish nothing, and of twelve health services — and on a phone with no
 * Health Connect provider at all — a de-Googled ROM, which this app supports — the wizard behind
 * that door cannot open and this card is the only way in. So it sits on the import page itself, and
 * asks for no permission: the picker hands over one file the person chose, and nothing else on the
 * phone is readable through it.
 *
 * The report is a line in the card rather than the centre flash a logged event gets. A flash is for
 * a confirmation that can be read in a glance and forgotten; this is a result with numbers in it,
 * and one of its outcomes is a paragraph explaining that the file was not an export it knows.
 */
@Composable
internal fun WorkoutFileImportCard(profile: UserProfile) {
    val context = LocalContext.current
    val running by fileImportRunning.collectAsStateWithLifecycle()
    val status by fileImportReport.collectAsStateWithLifecycle()
    val titles = rememberImportTitles()
    // A second tap in the moment before the picker covers the screen would open a second picker,
    // and its answer would start a second import beside the first.
    var picking by remember { mutableStateOf(false) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri == null || !claimFileImport()) return@rememberLauncherForActivityResult
        val app = context.applicationContext
        val imperial = profile.imperial
        fileImportReport.value = null
        fileImportScope.launch {
            // Everything that can fail is in here, the cache copy's own creation on a full disk
            // included: a failure outside it would end the process and leave the card spinning.
            val report = runCatchingCancellable {
                // Copied into the cache first, then streamed from there: a decade of lifting is a few
                // megabytes of CSV, an Apple Health archive hundreds of them, and neither is held
                // whole. The cache copy is also what lets the readers open the file more than once,
                // which a provider's stream does not promise.
                val staged = File.createTempFile(IMPORT_COPY_PREFIX, ".bin", app.cacheDir)
                try {
                    app.contentResolver.openInputStream(uri)
                        ?.use { document -> staged.outputStream().use(document::copyTo) }
                        ?: throw FileNotFoundException(uri.toString())
                    StepsDb.get(app).importExportedFile(
                        open = staged::inputStream,
                        // Asked here and not in the picker's callback, which is the main thread: the
                        // name is a call into the provider, and a cloud one answers slowly.
                        fileName = app.pickedFileName(uri),
                        zone = ZoneId.systemDefault(),
                        imperial = imperial,
                        titles = titles,
                    )
                } finally {
                    staged.delete()
                }
            }.fold(
                onSuccess = { app.resources.importReport(it) },
                onFailure = { app.resources.importFailure(it) },
            )
            fileImportReport.value = report
            fileImportRunning.value = false
        }
    }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Which file to give, and what a file brings with it, behind the question mark before
            // it is picked: a watch's recordings land as daily figures, not the stream; wger's
            // training cannot be read at all; and two training logs and MyFitnessPal's weighings
            // write bare numbers, read in this profile's unit — an assumption to see before it
            // multiplies a whole history by 2.2.
            HelpTitle(
                stringResource(R.string.file_import_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            ) {
                Text(stringResource(R.string.file_import_body))
                Text(stringResource(R.string.file_import_summarised))
                Text(stringResource(R.string.file_import_wger))
                Text(
                    stringResource(
                        R.string.file_import_units,
                        stringResource(if (profile.imperial) R.string.unit_lb else R.string.unit_kg),
                    ),
                )
            }
            Text(
                stringResource(R.string.file_import_apps),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            if (running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.file_import_working), Modifier.politeLiveRegion(), color = PwnMuted)
                }
            } else {
                Button(
                    // A Files app hands the same CSV out under any of four types and an archive
                    // under three more, and a picker that greyed out the very file somebody came
                    // here to open would be worse than one that also shows a text file.
                    onClick = {
                        if (!picking) {
                            picking = true
                            open.launch(PICKABLE_EXPORT_TYPES)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.file_import_pick))
                }
            }
            // The picker button the person pressed is gone while the file is read, and with it the
            // screen reader's focus, so the result is announced where it lands rather than found.
            status?.let {
                Text(
                    it,
                    Modifier.politeLiveRegion(),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Where a file import runs: not the card's own scope, which a rotation ends — and with it a
 * two-minute Apple import halfway, with nothing said — but one that lives with the process, as the
 * Health Connect exchange's does. The card only watches the two states.
 */
private val fileImportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
internal val fileImportRunning = MutableStateFlow(false)
private val fileImportReport = MutableStateFlow<String?>(null)

/**
 * Whether a picked file may start its import: only when none is running, asked and marked in one
 * step. Two imports side by side would each end by clearing the one flag the other still ran under,
 * bringing the button back — and [sweepExportLeftovers] onto the cache copy — over a running import.
 */
internal fun claimFileImport(): Boolean = fileImportRunning.compareAndSet(expect = false, update = true)

/** What an import's cache copy is named from, for [sweepExportLeftovers] to find one Android cut off. */
internal const val IMPORT_COPY_PREFIX = "import"

private val PICKABLE_EXPORT_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "application/csv",
    "text/plain",
    "application/json",
    // A loose Apple `export.xml`, and the `.tcx` files Nike's export is a folder of.
    "text/xml",
    "application/xml",
    "application/vnd.garmin.tcx+xml",
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)

/**
 * What the run has to say for itself.
 *
 * A file no format claimed is its own answer and not a count: "nothing was added" out of a file the
 * reader never understood would be read as "my history is empty". The two numbers are always both
 * printed, because "already here" is the whole explanation of a second run adding nothing.
 *
 * Under the line goes whatever of the file was not read, each in its own words: an archive the
 * walk gave up on or a file cut off, entries too large or not what they were taken for, and rows
 * whose date no shape read. Any of them makes the numbers above a part of the history and not the
 * whole of it.
 */
private fun Resources.importReport(outcome: HealthImportOutcome): String {
    val unread = listOfNotNull(
        getString(R.string.file_import_cut_short).takeIf { outcome.cutShort },
        outcome.skipped.takeIf { it > 0 }?.let { getString(R.string.file_import_skipped, it) },
        outcome.undated.takeIf { it > 0 }?.let { getString(R.string.file_import_undated, it) },
    )
    // Nothing read and something refused: the refusal is the whole story, and "not an export" would
    // be a guess about a file that may simply have been too large to hold.
    if (outcome.unreadable) {
        return unread.ifEmpty { listOf(getString(R.string.file_import_unreadable)) }.joinToString("\n")
    }
    val line = when {
        // A file that was recognised and held nothing this app can keep is its own answer. It happens
        // for real reasons — a Nike archive of runs already imported, a Flo export of a pregnancy — and
        // "added: 0 · already here: 0" reads as a failure when it is a complete and correct run. Not
        // where part of the file went unread, though: "held nothing" would then be said of rows that
        // were there, and the noughts above the reason are the truer line.
        outcome.empty && outcome.alreadyHeld == 0 && unread.isEmpty() ->
            getString(R.string.file_import_nothing, outcome.label.orEmpty())

        outcome.stepDays > 0 -> getString(
            R.string.file_import_done_steps,
            outcome.label.orEmpty(),
            outcome.added,
            outcome.alreadyHeld,
            outcome.stepDays,
        )

        else -> getString(
            R.string.file_import_done,
            outcome.label.orEmpty(),
            outcome.added,
            outcome.alreadyHeld,
        )
    }
    return (listOf(line) + unread).joinToString("\n")
}

/**
 * Which sentence a failed file import is reported with.
 *
 * Said in the reader's own language where there is a plain reason to give. "Failed to allocate a
 * 67108880 byte allocation" tells the person holding the file nothing; nor does "encrypted ZIP entry
 * not supported" for an archive locked with a password, or "Unexpected end of ZLIB input stream" for
 * one whose download stopped halfway — and inside a translated sentence they would be English besides.
 * Anything else, a full disk or a provider that went away, keeps the general line, which ends in the
 * system's own words, as a failed backup import's does ([importFailureRes]).
 */
internal fun fileImportFailureRes(failure: Throwable): Int = when (failure) {
    is OutOfMemoryError -> R.string.file_import_out_of_memory
    is FileNotFoundException -> R.string.file_import_unopenable
    is ZipException, is EOFException -> R.string.file_import_damaged
    else -> R.string.file_import_error
}

/** [fileImportFailureRes] as text; the general line is given the failure's own words. */
private fun Resources.importFailure(failure: Throwable): String =
    when (val sentence = fileImportFailureRes(failure)) {
        R.string.file_import_error -> getString(sentence, failure.message ?: failure.javaClass.simpleName)
        else -> getString(sentence)
    }

/**
 * The words an imported row wears, gathered once where there are resources to ask.
 *
 * The reader itself takes plain strings rather than resource ids so that it can be read by a test on
 * a bare JVM; this is the one place that bridges the two.
 */
@Composable
private fun rememberImportTitles(): HealthImportTitles {
    val resources = LocalResources.current
    return remember(resources) {
        HealthImportTitles(
            weight = resources.getString(R.string.measurement_log_title),
            sleep = resources.getString(R.string.measurement_sleep_title),
            periodStart = resources.getString(R.string.cycle_title),
            periodEnd = resources.getString(R.string.cycle_period_end),
            water = resources.getString(R.string.water_title),
            caffeine = resources.getString(R.string.caffeine_title),
            vital = { resources.getString(it.titleRes) },
            meal = { resources.getString(it.titleRes) },
        )
    }
}

/**
 * What the picker called the file.
 *
 * Only ever used to decide how hard to work — an archive's own entry names are what the readers go
 * by — but a `content://` URI has no name of its own, so it is asked for rather than parsed out of
 * the path, which on many providers is an opaque number. A provider that refuses the question is
 * answered the same way as one that has no name to give: the file has already been read.
 */
private fun android.content.Context.pickedFileName(uri: android.net.Uri): String =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
        ?: uri.lastPathSegment
        ?: "export"
