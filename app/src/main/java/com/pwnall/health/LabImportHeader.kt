package com.pwnall.health

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/**
 * The camera and the file picker that read a sheet into the editable form; only the form's Save
 * button writes results. The pages that offer them call [LabImportLaunch.photo] and
 * [LabImportLaunch.files]; [LabImportDrafts] shows what an import could not place on its own.
 */
@Composable
internal fun rememberLabImport(
    status: LabImportStatus,
    existing: Set<LabMarker>,
    day: LocalDate,
    initialSources: List<String> = emptyList(),
    onFill: (LabImportDraft) -> Unit,
): LabImportLaunch {
    // The application's, not the activity's: the reading outlives the activity it was started from.
    val context = LocalContext.current.applicationContext
    val currentExisting by rememberUpdatedState(existing)
    val currentDay by rememberUpdatedState(day)
    val fill by rememberUpdatedState(onFill)
    val busy = status.busy
    var capture by rememberSaveable { mutableStateOf<String?>(null) }
    // The files being read, kept in the page's saved state. A turn of the phone does not need them:
    // the reading runs past the activity ([LabImportRuns]) and the page that comes back finds it under
    // way. They are for the process Android ends under a reading, whose page would otherwise come back
    // to an empty form and ask for the file again, with nothing said: what is still listed here when
    // a page opens on no reading of its own is read again from its first page. It starts as the sheet
    // the page was handed, which is read the same way: once, and again only if cut short.
    var pending by rememberSaveable(initialSources) { mutableStateOf(initialSources) }
    val read: (List<Uri>) -> Unit = { picked ->
        if (picked.isNotEmpty() && !status.busy) {
            pending = picked.map(Uri::toString)
            status.error = false
            status.busy = true
            status.progress = 1 to 1
            status.job = LabImportRuns.scope.launch {
                try {
                    val pages = mutableListOf<LabImportPage>()
                    for (uri in picked.distinct()) {
                        // Our own camera URI needs no grant; document providers may grant persistent access.
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        status.fileName = withContext(Dispatchers.IO) { context.attachmentName(uri.toString()) }
                        // A sheet from the camera is brought to its kept size before it is read, so
                        // what is read is what stays; a picked file is another app's and is not touched.
                        withContext(Dispatchers.IO) { shrinkLabCapture(context, uri) }
                        val scans = try {
                            LabDocumentReader.read(
                                context,
                                uri,
                                LocalDate.now(),
                                localeMonthFirst(),
                                password = status::askPassword,
                            ) { at, total ->
                                withContext(Dispatchers.Main) { status.progress = at to total }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status.error = true
                            listOf(LabSheetScan()) // Keep the original attached for manual entry, even if OCR failed.
                        }
                        pages += scans.map { LabImportPage(uri.toString(), it) }
                    }
                    // Left for a page to take, not put into the form from here: the form this reading
                    // was started from may have been rebuilt since, and its fields are another page's.
                    status.arrived = labImportDrafts(pages)
                } finally {
                    status.busy = false
                }
            }
        }
    }
    LaunchedEffect(initialSources) {
        if (resumesLabReading(pending, started = status.job != null)) read(pending.map(String::toUri))
    }
    // What a reading leaves is taken by whichever page is on screen when it ends, or by the next one
    // to open. Read, failed or cancelled, nothing is left to take up again.
    LaunchedEffect(status.busy, status.arrived) {
        if (!status.busy && status.job != null) {
            pending = emptyList()
            status.arrived?.let { batch ->
                status.arrived = null
                val only = batch.singleOrNull()
                if (only != null && only.canApply(currentExisting, currentDay)) {
                    fill(only)
                    status.lastScan = only.scan
                } else {
                    status.drafts = status.drafts + batch
                    status.choosingDate = true
                }
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), read)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = capture?.toUri()
        capture = null
        if (uri != null) {
            if (taken) read(listOf(uri)) else context.contentResolver.delete(uri, null, null)
        }
    }
    return LabImportLaunch(
        ready = !busy && capture == null,
        photo = {
            runCatching {
                newLabCapture(context).also {
                    capture = it.toString()
                    camera.launch(it)
                }
            }.onFailure {
                capture?.let { uri -> context.contentResolver.delete(uri.toUri(), null, null) }
                capture = null
                status.error = true
            }
        },
        files = { pick.launch(arrayOf("image/*", "application/pdf")) },
    )
}

/**
 * Reads an allergy panel's file into its rows, with the sheet import's own lines for the pages, a
 * password and a failure ([LabImportStatusLines] over the same [status]). Every page's rows are
 * kept, the first of a code winning, and the first day a page names dates the panel.
 *
 * Read as a sheet is ([rememberLabImport]): past the activity, the rows left in
 * [LabImportStatus.panel] for the page on screen to take. Read in the page's own scope, a turn of the
 * phone would end the reading, and the page that came back would read the file again from its first
 * page.
 */
@Composable
internal fun rememberAllergyPanelReading(
    status: LabImportStatus,
    onRead: (AllergyPanelScan) -> Unit,
): (String) -> Unit {
    val context = LocalContext.current.applicationContext
    val read by rememberUpdatedState(onRead)
    // The file being read, for the process Android ends under a reading: see rememberLabImport.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val start: (String) -> Unit = { source ->
        if (!status.busy) {
            pending = source
            status.error = false
            status.busy = true
            status.progress = 1 to 1
            status.job = LabImportRuns.scope.launch {
                try {
                    status.fileName = withContext(Dispatchers.IO) { context.attachmentName(source) }
                    val pages = LabDocumentReader.readPanel(
                        context,
                        source.toUri(),
                        LocalDate.now(),
                        localeMonthFirst(),
                        password = status::askPassword,
                    ) { at, total -> withContext(Dispatchers.Main) { status.progress = at to total } }
                    status.panel = AllergyPanelScan(
                        pages.flatMap(AllergyPanelScan::rows).distinctBy(AllergenResult::code),
                        pages.firstNotNullOfOrNull(AllergyPanelScan::day),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status.error = true
                    status.panel = AllergyPanelScan()
                } finally {
                    status.busy = false
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (resumesLabReading(listOfNotNull(pending), started = status.job != null)) pending?.let(start)
    }
    LaunchedEffect(status.busy, status.panel) {
        if (!status.busy && status.job != null) {
            pending = null
            status.panel?.let { scan ->
                status.panel = null
                read(scan)
            }
        }
    }
    return start
}

/**
 * What a lab file is called, for a page to print: asked once for each file, and off the main thread.
 *
 * The name is a query to the app the file lives in ([attachmentName]). Asked in the composition it
 * would go out on every recomposition — in a document's form, on every letter typed into its note —
 * and hold the main thread for as long as that app takes to answer. Until it answers, the last part of
 * the path stands in, which is also what is printed when nothing answers at all.
 */
@Composable
internal fun labFileName(uri: String): String {
    val context = LocalContext.current
    // Keyed by the file, so a second one chosen never prints the first one's name while it waits.
    val name by key(uri) {
        produceState(uri.toUri().lastPathSegment ?: uri) {
            value = withContext(Dispatchers.IO) { context.attachmentName(uri) }
        }
    }
    return name
}

/**
 * Whether the phone's own short date is month, day, year, which settles a sheet's 03/04/2026 or
 * 03-04-2026. Not the month before the day alone: a region that writes the year first (2026-04-03 in
 * Sweden, Canada, China, Japan, Korea, Hungary, Lithuania) has it so too and says nothing of a date
 * whose year is last, which there is a sheet from somewhere else — and is read day first, as most of
 * the world writes one.
 */
internal fun localeMonthFirst(locale: Locale = Locale.getDefault()): Boolean {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        FormatStyle.SHORT,
        null,
        IsoChronology.INSTANCE,
        locale,
    )
    return pattern.indexOf('M') < pattern.indexOf('d') && pattern.indexOf('d') < pattern.indexOf('y')
}

/** A locked PDF's password, asked in the import's own lines ([LabImportStatusLines]) and awaited. */
internal suspend fun LabImportStatus.askPassword(wrong: Boolean): String = withContext(Dispatchers.Main) {
    val answer = CompletableDeferred<String>()
    passwordWrong = wrong
    password = answer
    try {
        answer.await()
    } finally {
        password = null
    }
}

/** The two ways a sheet comes in, the camera and the phone's files; [ready] while neither is under way. */
internal class LabImportLaunch(val ready: Boolean, val photo: () -> Unit, val files: () -> Unit)

/** The pages an import read and could not put into the form on its own, one button per date. */
@Composable
internal fun LabImportDrafts(
    status: LabImportStatus,
    existing: Set<LabMarker>,
    day: LocalDate,
    fill: (LabImportDraft) -> Unit,
) {
    val context = LocalContext.current
    val drafts = status.drafts
    if (status.choosingDate && drafts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { status.choosingDate = false },
            title = { Text(stringResource(R.string.lab_import_pick)) },
            text = {
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.lab_import_review))
                    drafts.forEach { draft ->
                        TextButton(
                            enabled = draft.canApply(existing, day),
                            onClick = {
                                fill(draft)
                                status.lastScan = draft.scan
                                status.drafts = status.drafts - draft
                                status.choosingDate = false
                            },
                        ) {
                            // The date as the form prints it, not as it is stored.
                            Text(
                                draft.scan.day?.let { stringResource(R.string.labs_drawn_on, it.formatLong()) }
                                    ?: stringResource(R.string.lab_import_date_unknown),
                            )
                        }
                        if (!draft.canApply(existing, day)) Text(stringResource(R.string.lab_import_date_conflict))
                        draft.sources.forEach { source ->
                            TextButton(onClick = { context.openAttachment(source) }) {
                                Text(labFileName(source))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { status.choosingDate = false }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
}

/**
 * What an import has to say while it reads and after it has read: the page it is on, what went
 * wrong, and what to check.
 *
 * Held apart from the bar the import is started from, so the page can put the words inside its own
 * scroll. Standing between the bar and the form, several of these lines at a large text size would
 * leave the form under them no height at all, Save included, and nothing to scroll them away with.
 */
@Stable
internal class LabImportStatus {
    var busy by mutableStateOf(false)
    var progress by mutableStateOf(1 to 1)
    var fileName by mutableStateOf("")
    var drafts by mutableStateOf<List<LabImportDraft>>(emptyList())
    var lastScan by mutableStateOf<LabSheetScan?>(null)
    var error by mutableStateOf(false)
    var choosingDate by mutableStateOf(false)
    var job: Job? = null

    /** The pages a reading has finished with, until a page on screen takes them ([rememberLabImport]). */
    var arrived by mutableStateOf<List<LabImportDraft>?>(null)

    /** The same for an allergy panel's rows ([rememberAllergyPanelReading]). */
    var panel by mutableStateOf<AllergyPanelScan?>(null)

    /** A locked PDF waiting for its password, and whether the last one typed was refused. */
    var password by mutableStateOf<CompletableDeferred<String>?>(null)
    var passwordWrong by mutableStateOf(false)
}

/**
 * Where a sheet is read, and what each page's reading has to say, kept past the activity.
 *
 * Not the page's own scope and state, which end when Android rebuilds the activity — a turn of the
 * phone, the night theme coming on. A reading cut off there would be lost with nothing said, or taken
 * up again from its first page: twenty pages of a PDF read twice, and its password asked for a second
 * time. Here the reading runs on, as a file import does (fileImportScope), and the page that
 * comes back finds its progress, its password question and its result where the last one left them.
 */
private object LabImportRuns {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val held = mutableMapOf<String, LabImportStatus>()

    /** A reader's lines by its name: the same ones for a page rebuilt, new ones for a page that opens. */
    fun status(of: String): LabImportStatus = held.getOrPut(of) { LabImportStatus() }

    /** The page is gone for good: its reading stops with it. */
    fun leave(of: String) {
        held.remove(of)?.job?.cancel()
    }
}

/**
 * A reader's lines for the page that asks — the sheet import's, or the allergy panel's beside it —
 * and the same ones again after the activity is rebuilt under it: each is known by a name the page's
 * saved state keeps. A page that is left is forgotten and its reading cancelled; so is one Android
 * destroys without rebuilding, whose saved list of files ([rememberLabImport]) is what the reading is
 * taken up from.
 */
@Composable
internal fun rememberLabImportStatus(): LabImportStatus {
    val page = rememberSaveable { UUID.randomUUID().toString() }
    val activity = LocalActivity.current
    DisposableEffect(page) {
        onDispose { if (activity?.isChangingConfigurations != true) LabImportRuns.leave(page) }
    }
    return remember(page) { LabImportRuns.status(page) }
}

/**
 * The import's own lines, drawn inside the form's scroll: how far it has read, what went wrong, what
 * to check, and the way back to pages it read but could not place.
 *
 * A polite live region, so a screen reader hears the reading finish or fail without having to go
 * looking for the line that says so. The failure is set apart by the error colour and its glyph as
 * well as by its words, and the bar fills by pages where there is more than one page to count.
 */
@Composable
internal fun LabImportStatusLines(status: LabImportStatus) {
    val review = status.lastScan
    val reopen = status.drafts.isNotEmpty() && !status.busy
    val reading = status.busy || status.error
    if (!reading && review == null && !reopen) return
    Column(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val asked = status.password
        if (asked != null) {
            LabPdfPassword(status, asked)
        } else if (status.busy) {
            val (at, total) = status.progress
            // The reader names the page it is about to read, so the pages already read are one fewer.
            if (total > 1) {
                LinearProgressIndicator(progress = { (at - 1f) / total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(status.fileName)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.report_page, at, total), Modifier.weight(1f))
                TextButton(onClick = { status.job?.cancel() }) { Text(stringResource(R.string.common_cancel)) }
            }
        }
        if (status.error) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(stringResource(R.string.lab_import_error), color = MaterialTheme.colorScheme.error)
            }
        }
        review?.let { scan ->
            Text(stringResource(R.string.lab_import_review))
            if (scan.day == null) Text(stringResource(R.string.lab_import_date_unknown))
            when {
                scan.veterinary -> Text(stringResource(R.string.lab_import_veterinary))
                scan.readings.isEmpty() -> Text(stringResource(R.string.lab_import_empty))
            }
            if (scan.unread > 0) Text(stringResource(R.string.lab_import_unread, scan.unread))
        }
        if (reopen) {
            TextButton(onClick = { status.choosingDate = true }) {
                Text(stringResource(R.string.m3_labs_import_reopen))
            }
        }
    }
}

/**
 * A locked PDF's password, asked in the import's own lines rather than a dialog over the form, the
 * way a sealed backup asks for its own. Typed text is never saved state, and a refused password
 * empties the field. Cancel stops the whole import, as it does while pages are read.
 */
@Composable
private fun LabPdfPassword(status: LabImportStatus, answer: CompletableDeferred<String>) {
    var typed by remember(answer) { mutableStateOf("") }
    Text(status.fileName)
    Text(stringResource(R.string.lab_import_password))
    PasswordField(
        typed,
        { typed = it },
        R.string.profile_password,
        error = if (status.passwordWrong) stringResource(R.string.profile_import_wrong_password) else null,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { status.job?.cancel() }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.common_cancel))
        }
        Button(onClick = { answer.complete(typed) }, enabled = typed.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.common_continue))
        }
    }
}
