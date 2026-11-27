package com.pwnall.health

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** How far the drawing over the title leans. The sheet inside it already carries eight of its own. */
private const val REPORT_ART_TILT = 14f

/** How far up the drawing is carried: the back button's row and the spacing above it, to the day. */
private val REPORT_ART_LIFT = 106.dp

/** The windows the report is offered over, in months. Every doctor's own window is one of them. */
internal val REPORT_MONTHS = listOf(3, 6, 12, 24)

/** Three fixed phrases rather than one template: languages decline "months" differently at 3 and 12. */
private fun reportMonthsRes(months: Int): Int = when (months) {
    3 -> R.string.report_months_3
    6 -> R.string.report_months_6
    12 -> R.string.report_months_12
    else -> R.string.report_months_24
}

/**
 * A Bundle holds no enum, so the doctor travels as its name and the ticks as theirs — the same trick
 * the profile screen's open section already uses.
 */
private val SPECIALTY_SAVER: Saver<DoctorSpecialty?, String> = Saver(
    save = { it?.name ?: "" },
    restore = { name -> DoctorSpecialty.entries.firstOrNull { it.name == name } },
)

private val COUNTRY_SAVER: Saver<CountryProfile, String> = Saver(
    save = { it.id },
    restore = { CountryProfileRegistry.of(it) },
)

private val PARTS_SAVER: Saver<Set<ReportPart>, Any> = listSaver(
    save = { parts -> parts.map(ReportPart::name) },
    restore = { names -> ReportPart.entries.filter { it.name in names }.toSet() },
)

/**
 * The report for the doctor: the window, who it is for, what goes in it, and PDF or ZIP export.
 *
 * The questions above the buttons are what makes this a page rather than a button. A quarter of
 * everything the phone holds is not what anybody is asked for across a desk — a cardiologist wants
 * the pressure diary, a dietitian the meals — so the doctor is chosen first and arrives with a set
 * of ticks already made and a window of its own. The ticks stand on the page under the doctor as
 * the "What goes in" row, one tap from being changed, and a window the person picked themselves
 * is never moved by a doctor picked after it. The ticks are the whole of what the PDF carries; the
 * doctor is printed on it as who it was put together for.
 *
 * The courses come from their own table, so this page asks for them itself rather than having the
 * whole profile screen collect a list only this page reads.
 */
@Composable
internal fun DoctorReportScreen(modifier: Modifier, profile: UserProfile, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember(context) { StepsDb.get(context).dao() }
    // Null until Room has answered, and not an empty list: an empty list is "nothing was ever
    // recorded", which is what the sheet would then say — see [reportSources].
    val days by remember(dao) { dao.daysSince("0000-01-01") }.collectAsStateWithLifecycle(null)
    val journal by remember(dao) {
        dao.journalBetween("0000-01-01", "9999-12-31")
    }.collectAsStateWithLifecycle(null)
    var status by rememberStatusLine()
    // A failure is not written into [status], for the reason the profile's export gives: the tick
    // there is green and gone in three seconds, and "the report was not saved" is neither. It stays
    // under the error's mark until it is tapped away or the next export begins.
    var failure by remember { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .panelVerticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = LocalBottomBarInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReportHero(onBack)
            DoctorReportBody(
                profile = profile,
                journal = journal,
                days = days,
                onSaved = {
                    failure = null
                    status = it
                },
                onFailed = {
                    status = null
                    failure = it
                },
            )
        }
        PwnActionFlash(status, Modifier.align(Alignment.Center))
        PwnFailureFlash(failure) { failure = null }
    }
}

/**
 * The page's own head: the way back, what this is, and the drawing that says so.
 *
 * Bigger and left-aligned rather than the app's centred top bar, because this page is not one of a
 * stack of settings — it is the one thing in the app made to leave the phone, and it opens with the
 * two lines that say what is about to be handed to somebody else.
 */
@Composable
private fun ReportHero(onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.size(4.dp))
        Box(
            // A round back button, at the size a finger needs: clipped before the click
            // so the ripple is the circle rather than the square around it, and a button to a
            // screen reader rather than an unnamed tap.
            Modifier.size(PwnDimensions.minimumTarget)
                .clip(CircleShape)
                .background(PwnSurfaceElevated)
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                stringResource(R.string.common_back),
                tint = PwnMuted,
            )
        }
        Box(Modifier.fillMaxWidth()) {
            Image(
                themePainterResource(R.drawable.ic_art_report),
                contentDescription = null,
                // Twice the size and pushed past the page's own margin, so the drawing runs off the
                // top corner rather than sitting inside it, and tilted the way the sheet inside it
                // already leans.
                //
                // It is laid out as nothing at all — a zero-sized slot that draws unbounded — so
                // that doubling it does not also double the empty band under the title: a 232dp
                // image measured as 232dp of row would take the sentence under it down the page
                // with it. The offsets are what carry it into the corner, and they are as far as
                // it can go: the page scrolls, and a scroll clips whatever is drawn above its top.
                modifier = Modifier.align(Alignment.TopEnd)
                    .size(0.dp)
                    .wrapContentSize(align = Alignment.TopEnd, unbounded = true)
                    .offset(x = 40.dp, y = (-REPORT_ART_LIFT))
                    .size(232.dp)
                    .rotate(REPORT_ART_TILT),
            )
            Text(
                // Broken after the first word rather than wherever the column happens to run out:
                // the title reads "Отчет" over "для врача", and a width chosen to make that
                // break fall there would be a width no other language's title fits in.
                stringResource(R.string.report_menu).replaceFirst(' ', '\n'),
                // The page's name, and so its first heading for a screen reader jumping by them.
                Modifier.padding(end = 124.dp).semantics { heading() },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }
        // The sentence runs the width of the page, under the drawing rather than beside it.
        Text(stringResource(R.string.report_intro), color = PwnMuted)
        // The sentence under the title gets a good deal more room than the rest of the page's
        // rows are given; the column's own 12dp is a third of it.
        Spacer(Modifier.size(20.dp))
    }
}

@Composable
private fun ColumnScope.DoctorReportBody(
    profile: UserProfile,
    journal: List<JournalEntry>?,
    days: List<DaySteps>?,
    onSaved: (String) -> Unit,
    onFailed: (String?) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val dao = remember(context) { StepsDb.get(context).dao() }
    val medications by remember(dao) { dao.medications() }.collectAsStateWithLifecycle(null)
    val visits by remember(dao) { dao.appointments() }.collectAsStateWithLifecycle(null)
    val history by remember(dao) { dao.medicalHistory() }.collectAsStateWithLifecycle(null)
    // The usual days off and the long-day line the work section counts by, as the Work screen set them —
    // those two and nothing else of the timer: a Pause on the widget or its flash word would otherwise
    // build the whole sheet again. Null until they are read, so the sheet is not built on defaults first.
    val workSettings by remember(context) {
        WorkClock.flow(context).map { it.daysOff to it.longDayMinutes }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)
    var specialty by rememberSaveable(stateSaver = SPECIALTY_SAVER) { mutableStateOf<DoctorSpecialty?>(null) }
    var country by rememberSaveable(stateSaver = COUNTRY_SAVER) {
        mutableStateOf(CountryProfileRegistry.international)
    }
    var months by rememberSaveable { mutableIntStateOf(REPORT_MONTHS[1]) }
    // Whether the window is the person's own answer rather than the one a doctor came with: once it
    // is, picking another doctor changes the ticks and leaves the window alone.
    var periodChosen by rememberSaveable { mutableStateOf(false) }
    // By sex and by the age gate: a row the gate keeps from this person is not drawn here either.
    // Today as [rememberToday] gives it: the gate is asked about the day it is, and the window below
    // ends on it, on a page left open over midnight too.
    val today = rememberToday()
    val offered = remember(profile.sex, profile.birthDate, today) { reportPartsFor(profile, today) }
    var chosen by rememberSaveable(stateSaver = PARTS_SAVER) { mutableStateOf(openingParts(offered)) }
    var sexualDetail by rememberSaveable { mutableStateOf(SexualDetail.SUMMARY.name) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var withFiles by rememberSaveable { mutableStateOf(false) }
    // A restored backup can change whose profile this is, and the cycle goes with it. The stored
    // ticks are filtered back through what is offered rather than cleared — a name outlives the
    // gate that let it into the set, which is the rule the widget slots and the hidden advice keep.
    LaunchedEffect(offered) { chosen = chosen.filter { it in offered }.toSet() }
    val request = ReportRequest(
        from = today.minusMonths(months.toLong()),
        to = today,
        specialty = specialty,
        country = country,
        parts = chosen,
        sexualDetail = SexualDetail.valueOf(sexualDetail),
    )
    // The whole journal read twice over — once for the vitals, once for the blood tests — and a
    // year of doses counted: off the composition thread, the way the health section reads its advice.
    val report by produceState<DoctorReport?>(
        null,
        journal,
        days,
        medications,
        visits,
        history,
        profile,
        request,
        workSettings,
    ) {
        val sources = reportSources(profile, journal, days, medications, visits, history, workSettings)
            ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val now = LocalTime.now()
            doctorReport(
                sources = sources,
                request = request,
                today = LocalDate.now(),
                nowMinute = now.hour * 60 + now.minute,
            )
        }
    }
    // Only the sheet built for the choices on screen may leave: right after a doctor or a period is
    // picked, the old one is still standing while the new one is built.
    val current = report.builtFor(request)
    val ready = current != null

    // A failure with no message of its own is named by its kind, as the backup's line names one,
    // rather than leaving the card with nothing after its colon.
    fun failure(error: Throwable) =
        resources.getString(R.string.profile_export_error, error.message ?: error.javaClass.simpleName)
    // One export at a time. A PDF, or a ZIP with its scans, takes seconds to put together, and a
    // second Share in those seconds would empty the folder the first is still writing into and open
    // two choosers. Beginning one also takes down what the last one reported.
    var exporting by remember { mutableStateOf(false) }
    fun export(run: suspend () -> Unit) {
        exporting = true
        onFailed(null)
        // Undispatched, so the write is under way before this call returns: a dispatched launch is
        // cancelled without ever running when the page is left in the same moment, and the document
        // the picker made would stay empty with nothing left to write or remove it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                run()
            } finally {
                exporting = false
            }
        }
    }
    // The document the picker created waits here for its sheet rather than being written from the
    // picker's own answer. An Activity recreated under the picker — a rotation, a theme change, the
    // process reclaimed — is handed that answer before anything has been read again, and with no
    // sheet in hand the answer would be dropped, leaving the empty file the picker had already
    // made. Saved state, so the wait survives the same recreation; [waitingArchive] says which of
    // the two files it is to be.
    var waiting by rememberSaveable { mutableStateOf<Uri?>(null) }
    var waitingArchive by rememberSaveable { mutableStateOf(false) }
    // A page come back to from under another screen is restored with the wait it was left with,
    // and the document that wait names was removed as the page went (the effect below). Written
    // into now, the sheet would fail on a file that is gone and put up the export's error card
    // over a save nobody was waiting for any more — see [waitOutlivesReturn]. Read once, as the
    // page arrives, which is the one composition the answer stands for.
    val arrivedBack = LocalArrivingBack.current
    LaunchedEffect(Unit) { if (!waitOutlivesReturn(arrivedBack)) waiting = null }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(REPORT_MIME)) { uri ->
        waitingArchive = false
        waiting = uri
    }
    val saveArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(REPORT_ARCHIVE_MIME),
    ) { uri ->
        waitingArchive = true
        waiting = uri
    }
    LaunchedEffect(waiting, current) {
        val uri = waiting ?: return@LaunchedEffect
        val built = current ?: return@LaunchedEffect
        val archive = waitingArchive
        waiting = null
        export {
            // The screen can close under the write; the document is finished or removed regardless,
            // never left half written.
            withContext(NonCancellable) {
                val outcome = if (archive) {
                    saveDoctorArchive(context, built, uri)
                } else {
                    saveDoctorReport(context, built, uri)
                }
                outcome.fold({ onSaved(resources.getString(R.string.report_saved)) }, { onFailed(failure(it)) })
            }
        }
    }
    // A page left while the document is still waiting takes the document with it: no sheet will be
    // written into it now, and an empty file under the report's name is what the wait exists to
    // prevent. An Activity taken down with its state saved is not a page left — see
    // [abandonsWaitingDocument].
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        onDispose {
            val uri = waiting
            val abandoned = abandonsWaitingDocument(activity?.isDestroyed == true, activity?.isFinishing == true)
            if (uri != null && abandoned) {
                // Undispatched and non-cancellable, as the breathing page settles a session: this
                // scope is being torn down with the page.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    }
                }
            }
        }
    }

    ReportSectionTitle(stringResource(R.string.report_period))
    ReportPeriodChoice(months) {
        months = it
        periodChosen = true
    }
    ReportPeriodLine(report)
    // The dates belong to the control above them, not to the question below; without this they sit
    // the same distance from both and read as a caption on the wrong one.
    Spacer(Modifier.size(6.dp))
    ReportSectionTitle(stringResource(R.string.report_doctor_title))
    PwnCard(Modifier.fillMaxWidth()) {
        ProfileMenuRow(
            art = specialty?.art,
            icon = Icons.Outlined.PersonOutline,
            background = PwnOrangeSoft,
            title = specialty?.let { stringResource(it.titleRes) } ?: stringResource(R.string.report_doctor_choose),
            subtitle = null,
            divider = false,
            onClick = { picking = DOCTOR_PICKER },
        )
    }
    // What the choice is for sits behind the circle beside the question: printed on the row, one
    // line of it is all that fits and the sentence would be cut off in the middle.
    ReportSectionTitle(
        stringResource(R.string.report_country_title),
        help = stringResource(R.string.report_country_body),
    )
    PwnCard(Modifier.fillMaxWidth()) {
        ProfileMenuRow(
            art = null,
            icon = Icons.Outlined.Public,
            background = PwnPurpleSoft,
            title = stringResource(country.displayNameRes),
            subtitle = null,
            divider = false,
            onClick = { picking = COUNTRY_PICKER },
        )
    }
    // On the page whoever the report is for, not only under "My own choice": otherwise every
    // doctor would apply ticks nobody could see or change short of throwing them all away for
    // that answer, and the one row that takes alcohol off a GP's sheet would be out of reach.
    ReportSectionTitle(stringResource(R.string.report_contents))
    PwnCard(Modifier.fillMaxWidth()) {
        ProfileMenuRow(
            art = null,
            icon = Icons.Outlined.Checklist,
            // The text-weight blue: the fill blue on its own pale disc is too faint for a glyph.
            background = PwnBlueSoft,
            title = stringResource(R.string.report_selected_count, chosen.size, offered.size),
            subtitle = null,
            divider = false,
            onClick = { picking = DATA_PICKER },
        )
    }
    ReportEmptyNote(report)
    Spacer(Modifier.size(4.dp))
    // Both export actions preview the selected sections, including any original attachments.
    // The whole row answers the tap, as PwnSwitchRow does, at least a finger's height tall: the
    // switch inside hands its click to the row and so enforces no target of its own.
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = PwnDimensions.minimumTarget)
            .toggleable(
                value = withFiles,
                role = Role.Switch,
                onValueChange = { withFiles = it },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.report_with_files),
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleMedium,
            )
            HelpDot(stringResource(R.string.report_with_files)) {
                Text(stringResource(R.string.report_archive_hint))
            }
        }
        PwnSwitch(checked = withFiles, onCheckedChange = null)
    }
    // With the files the two ways out wear the archive's own glyph, so what leaves with them does
    // not look like the PDF alone.
    PwnWideButton(
        icon = if (withFiles) Icons.Outlined.FolderZip else Icons.Outlined.Description,
        // The words name what leaves: the PDF alone, or the ZIP that carries it with the files.
        label = stringResource(if (withFiles) R.string.report_save_zip else R.string.report_save),
        enabled = ready && !exporting,
        onClick = { picking = if (withFiles) ARCHIVE_SAVE_PREVIEW else SAVE_PREVIEW },
    )
    PwnWideButton(
        icon = if (withFiles) Icons.Outlined.FolderZip else Icons.Outlined.Share,
        label = stringResource(if (withFiles) R.string.report_share_zip else R.string.report_share_pdf),
        enabled = ready && !exporting,
        tonal = true,
        onClick = { picking = if (withFiles) ARCHIVE_SHARE_PREVIEW else SHARE_PREVIEW },
    )

    when (picking) {
        DOCTOR_PICKER -> DoctorPickerSheet(
            sex = profile.sex,
            offered = offered,
            chosen = specialty,
            onDismiss = { picking = null },
            onPick = {
                specialty = it
                chosen = preselectedParts(it, profile.sex).intersect(offered)
                if (!periodChosen) months = it.defaultMonths
                picking = null
            },
        )

        COUNTRY_PICKER -> CountryPickerSheet(
            chosen = country,
            onDismiss = { picking = null },
            onPick = {
                country = it
                picking = null
            },
        )

        SAVE_PREVIEW, SHARE_PREVIEW, ARCHIVE_SAVE_PREVIEW, ARCHIVE_SHARE_PREVIEW -> ReportPreviewSheet(
            report = current,
            months = months,
            withFiles = picking == ARCHIVE_SAVE_PREVIEW || picking == ARCHIVE_SHARE_PREVIEW,
            sensitiveOffered = offered.any { it.sensitivity == DataSensitivity.SENSITIVE },
            onDismiss = { picking = null },
            onConfirm = {
                val built = current
                val door = picking
                picking = null
                when {
                    built == null -> Unit

                    door == ARCHIVE_SAVE_PREVIEW -> saveArchive.launch(doctorArchiveFileName(built))

                    door == ARCHIVE_SHARE_PREVIEW ->
                        export { shareDoctorArchive(context, built).onFailure { onFailed(failure(it)) } }

                    door == SHARE_PREVIEW ->
                        export { shareDoctorReport(context, built).onFailure { onFailed(failure(it)) } }

                    door == SAVE_PREVIEW -> save.launch(doctorReportFileName(built.from, built.to))

                    // A second tap on Continue, landing before the sheet has gone, finds the door
                    // already shut. Without that it would fall through to the save and open a file
                    // picker behind a share.
                    else -> Unit
                }
            },
        )

        DATA_PICKER -> DataPickerSheet(
            offered = offered,
            chosen = chosen,
            suggested = specialty?.let { suggestedParts(it, profile.sex) }.orEmpty().toSet(),
            // Until the first report is built nothing is known to be missing, so nothing is
            // greyed: a row that says "nothing recorded" for a moment and then stops is worse
            // than one that never said it.
            filled = report?.filled ?: offered.toSet(),
            detail = SexualDetail.valueOf(sexualDetail),
            onDismiss = { picking = null },
            onApply = { parts, detail ->
                chosen = parts
                sexualDetail = detail.name
                picking = null
            },
        )

        else -> Unit
    }
}

private const val DOCTOR_PICKER = "DOCTOR"
private const val DATA_PICKER = "DATA"
private const val COUNTRY_PICKER = "COUNTRY"
private const val SAVE_PREVIEW = "SAVE"
private const val SHARE_PREVIEW = "SHARE"
private const val ARCHIVE_SAVE_PREVIEW = "ARCHIVE_SAVE"
private const val ARCHIVE_SHARE_PREVIEW = "ARCHIVE_SHARE"

/**
 * What the sheet is built from, or null while any of it is still being read.
 *
 * The work settings arrive with the page and Room's answers a moment later. An empty list is
 * indistinguishable from a record with nothing in it: a sheet built on one would stand as the one
 * for the choices on screen, and could be saved or shared saying
 * "nothing recorded" under every heading while the real one was still being put together. Null is
 * "not read yet", which no list can say, and there is no sheet until nothing here is null.
 */
internal fun reportSources(
    profile: UserProfile,
    journal: List<JournalEntry>?,
    days: List<DaySteps>?,
    medications: List<Medication>?,
    visits: List<Appointment>?,
    history: List<MedicalHistoryRow>?,
    work: Pair<Set<DayOfWeek>, Int>?,
): ReportSources? {
    val entries = journal ?: return null
    val stepDays = days ?: return null
    val courses = medications ?: return null
    val seen = visits ?: return null
    val written = history ?: return null
    val (daysOff, longDayMinutes) = work ?: return null
    return ReportSources(profile, entries, stepDays, courses, seen, written, daysOff, longDayMinutes)
}

/**
 * The sheet, where it is the one built for [request]; null where there is none yet or it answers
 * other choices. It is what the buttons wait for, what the preview reads out, and what a document
 * the picker created is written with — never the sheet that happened to be standing.
 */
internal fun DoctorReport?.builtFor(request: ReportRequest): DoctorReport? = this?.takeIf { it.request == request }

/**
 * What the page opens with ticked, before any doctor is chosen: the routine sections of what this
 * person is [offered], which is what "My own choice" ticks too.
 *
 * Not everything but the sensitive group: that would make a pregnancy status, a contraceptive and
 * the cycle something a person has to notice and take off. They are put on by a doctor they were
 * written for, or by the person's own tick.
 */
internal fun openingParts(offered: List<ReportPart>): Set<ReportPart> =
    offered.filter { it.sensitivity == DataSensitivity.ROUTINE }.toSet()

/**
 * Whether a document still waiting for its sheet is given up as the page goes, and removed.
 *
 * The page going is two different things. Left inside the app, or closed with its Activity for
 * good, the wait is over: nothing will write the document, and it is removed rather than left as
 * an empty file under the report's name. An Activity taken down to be put up again — a rotation,
 * or the system reclaiming it in the background — keeps the wait in its saved state, and the page
 * that comes back writes the document; removing it there would fail the very save the wait exists
 * to carry through. [destroyed] and [finishing] are the Activity's own two answers.
 */
internal fun abandonsWaitingDocument(destroyed: Boolean, finishing: Boolean): Boolean = !destroyed || finishing

/**
 * Whether the wait a page was restored with still has its document to write.
 *
 * The saved wait outlives both ways a page can go. What a page left inside the app remembered is
 * kept for the way back, and it is put by before the page's own effects are disposed, so nothing
 * done as the document is removed can take the wait out of it. The way the page arrives tells the
 * two apart: [arrivedBack], from under another screen of a living Activity, is the page whose
 * document [abandonsWaitingDocument] removed, and its wait goes too; a page put up again with its
 * Activity is the one the document was kept for.
 */
internal fun waitOutlivesReturn(arrivedBack: Boolean): Boolean = !arrivedBack

/**
 * What "Select all" says it reaches. The sweep stops short of the sensitive group, and says so —
 * but the age gate keeps that group off the list of a profile under sixteen ([reportPartsFor]),
 * and the line would then name a section the person cannot find on the sheet. Where no such row is
 * [offered], the sweep is simply every row.
 */
internal fun selectAllBodyRes(offered: List<ReportPart>): Int =
    if (offered.any { it.sensitivity == DataSensitivity.SENSITIVE }) {
        R.string.report_select_all_body
    } else {
        R.string.report_select_all_body_plain
    }

/**
 * A section's name on this page, said to a screen reader as a heading so the page can be walked by
 * its headings, with [help] behind a [HelpDot] beside it where the section has something to explain.
 */
@Composable
private fun ReportSectionTitle(title: String, help: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) { PwnSectionTitle(title) }
        help?.let { HelpDot(title) { Text(it) } }
    }
}

/**
 * How long a window the report covers: a row of four answers where they fit, the choice field where
 * they do not.
 *
 * Four answers split the column four ways, and at a large text size a word of one of them stops
 * fitting its quarter and breaks in the middle — the thing [PwnChoiceField] exists to prevent. The
 * row stays while every word of every answer fits its segment whole, measured in the type the chosen
 * segment is drawn in, and the field takes over the moment one does not.
 */
@Composable
private fun ReportPeriodChoice(months: Int, onPick: (Int) -> Unit) {
    val options = REPORT_MONTHS.map { stringResource(reportMonthsRes(it)) }
    val selected = REPORT_MONTHS.indexOf(months).coerceAtLeast(0)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val track = maxWidth - SEGMENT_TRACK_INSET * 2 - SEGMENT_DIVIDER * (options.size - 1)
        val room = with(density) { (track / options.size - SEGMENT_SIDE_PADDING * 2).roundToPx() }
        val fits = options.flatMap { it.split(' ') }.all { measurer.measure(it, style).size.width <= room }
        if (fits) {
            PwnSegmentedControl(
                options = options.map { it to null },
                selected = selected,
                onSelected = { onPick(REPORT_MONTHS[it]) },
            )
        } else {
            PwnChoiceField(
                label = stringResource(R.string.report_period),
                options = options,
                selected = selected,
                onSelected = { onPick(REPORT_MONTHS[it]) },
            )
        }
    }
}

/**
 * [PwnSegmentedControl]'s own geometry, which [ReportPeriodChoice] measures its words against: the
 * inset inside the track, the hairline between two neighbours, and a segment's padding each side.
 */
private val SEGMENT_TRACK_INSET = 5.dp
private val SEGMENT_DIVIDER = 1.dp
private val SEGMENT_SIDE_PADDING = 8.dp

/**
 * The days the window actually runs between, under the control that set its length — spelled as
 * the sheet and its preview spell them, in the chosen country's order rather than a third way.
 */
@Composable
private fun ReportPeriodLine(report: DoctorReport?) {
    val locale = LocalLocale.current.platformLocale
    val built = report ?: return
    val country = built.request.country
    val format = remember(locale, country) { reportDayFormat(locale, country.datePatternIn(locale)) }
    Text(
        stringResource(R.string.report_period_value, built.from.format(format), built.to.format(format)),
        color = PwnMuted,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The one line that stands between a person and a waiting room: a quarter with nothing recorded in
 * it, or one whose every section was unticked, would otherwise be discovered as a PDF of a profile.
 */
@Composable
private fun ReportEmptyNote(report: DoctorReport?) {
    val line = report?.let(::emptyNoteRes) ?: return
    Text(stringResource(line), color = PwnMuted, style = MaterialTheme.typography.bodySmall)
}

/**
 * Which of three things an empty sheet is, or null where the sheet is not empty.
 *
 * They are said differently on purpose. Nothing ticked is the person's own last decision. Nothing
 * recorded is the phone's answer about the period. And ticks over sections the period holds nothing
 * for, while other sections do hold something, is neither: reported as "nothing is
 * ticked" to somebody looking at a ticked blood-test row, it would be the app misreading its own
 * screen back to them — and the one thing that would help, a longer window or another section,
 * would go unsaid.
 */
internal fun emptyNoteRes(report: DoctorReport): Int? = when {
    !report.empty -> null
    report.request.parts.none(ReportPart::pickable) -> R.string.report_nothing_chosen
    report.filled.isEmpty() -> R.string.report_preview_empty
    else -> R.string.report_ticked_empty
}

/**
 * What goes in the report, ticked one row at a time, under the six headings the data falls into.
 *
 * The ticks are a draft until Apply: a sheet dragged away by mistake leaves the report as it was,
 * which a list that wrote every tap straight through could not. A section the period holds nothing
 * for still ticks — the window may move — but says so, so that nobody assembles a sheet out of
 * headings over nothing.
 *
 * "Select all" reaches everything except the sensitive group, and that is not a convenience: a
 * control that could sweep sexual-activity data into a report meant for a knee is the accident this
 * whole screen is built to make impossible. The sensitive row is ticked one way only — by answering
 * its own question — and [SensitiveConsentDialog] is that question.
 *
 * [suggested] is what the chosen doctor puts forward without ticking — [DoctorSpecialty.optional].
 * Such a row says so while it stands unticked and holds something; a suggestion over a section with
 * nothing in it would be sending the person to an empty heading. Suggesting is not ticking, and a
 * suggested sensitive row still asks its own question.
 */
@Composable
private fun DataPickerSheet(
    offered: List<ReportPart>,
    chosen: Set<ReportPart>,
    suggested: Set<ReportPart>,
    filled: Set<ReportPart>,
    detail: SexualDetail,
    onDismiss: () -> Unit,
    onApply: (Set<ReportPart>, SexualDetail) -> Unit,
) {
    // Saved, as the page's own choices are: the sheet comes back after a rotation with the ticks
    // not yet applied and the question being asked still standing.
    var draft by rememberSaveable(chosen, stateSaver = PARTS_SAVER) { mutableStateOf(chosen) }
    var draftDetail by rememberSaveable(detail) { mutableStateOf(detail.name) }
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    PwnPickerSheet(
        title = stringResource(R.string.report_data_title),
        subtitle = stringResource(R.string.report_data_body),
        onDismiss = onDismiss,
        action = {
            Button(
                onClick = { onApply(draft, SexualDetail.valueOf(draftDetail)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.report_apply))
            }
        },
    ) {
        // Everything but the sensitive group. The one row this cannot reach is the one row whose
        // inclusion has to be an answer rather than a side effect.
        val sweepable = offered.filter { it.sensitivity != DataSensitivity.SENSITIVE }.toSet()
        PickerRow(
            art = null,
            icon = Icons.Outlined.Layers,
            title = stringResource(R.string.report_select_all),
            subtitle = stringResource(selectAllBodyRes(offered)),
            checked = draft.containsAll(sweepable),
            onToggle = {
                draft = if (draft.containsAll(sweepable)) {
                    draft - sweepable
                } else {
                    draft + sweepable
                }
            },
        )
        ReportGroup.entries.forEach { group ->
            val rows = offered.filter { it.group == group }
            if (rows.isEmpty()) return@forEach
            PickerGroupHeading(stringResource(group.titleRes))
            rows.forEach { part ->
                val sensitive = part.sensitivity == DataSensitivity.SENSITIVE
                PickerRow(
                    art = part.art,
                    icon = part.icon,
                    title = stringResource(part.titleRes),
                    subtitle = when {
                        // A section that stands outside the window says so here too: "nothing
                        // recorded for this period" over a list of operations would be describing
                        // the wrong absence.
                        part !in filled && part.timeless ->
                            stringResource(R.string.section_nothing_written)

                        part !in filled -> stringResource(R.string.report_part_empty)

                        // Only the act row stands at a depth; the symptoms have one shape.
                        part == ReportPart.SEXUAL_HEALTH && part in draft ->
                            stringResource(detailBodyRes(SexualDetail.valueOf(draftDetail)))

                        else -> stringResource(part.bodyRes)
                    },
                    hint = if (part in suggested && part in filled && part !in draft) {
                        stringResource(R.string.report_part_suggested)
                    } else {
                        null
                    },
                    checked = part in draft,
                    onToggle = {
                        when {
                            part in draft -> draft = draft - part

                            // Each sensitive row asks its own question: they put different things
                            // on the sheet, so one answer cannot stand for both.
                            sensitive -> asking = part.name

                            else -> draft = draft + part
                        }
                    },
                )
            }
        }
    }
    when (asking) {
        ReportPart.SEXUAL_HEALTH.name -> SensitiveConsentDialog(
            onDismiss = { asking = null },
            onAgree = { agreed ->
                draft = draft + ReportPart.SEXUAL_HEALTH
                draftDetail = agreed.name
                asking = null
            },
        )

        ReportPart.SEXUAL_SYMPTOMS.name -> SymptomConsentDialog(
            onDismiss = { asking = null },
            onAgree = {
                draft = draft + ReportPart.SEXUAL_SYMPTOMS
                asking = null
            },
        )

        ReportPart.MORNING_ERECTIONS.name -> MorningConsentDialog(
            onDismiss = { asking = null },
            onAgree = {
                draft = draft + ReportPart.MORNING_ERECTIONS
                asking = null
            },
        )

        else -> Unit
    }
}

/** Which of the two depths the sensitive row is standing at, said on the row itself. */
private fun detailBodyRes(detail: SexualDetail): Int = when (detail) {
    SexualDetail.SUMMARY -> R.string.report_sexual_summary_body
    SexualDetail.DETAILED -> R.string.report_sexual_detailed_body
}

/** A heading between two runs of rows: one of the six kinds of thing a health record is made of. */
@Composable
private fun PickerGroupHeading(title: String) {
    Text(
        title,
        Modifier.padding(top = 14.dp, bottom = 2.dp).semantics { heading() },
        color = PwnMuted,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * The question the sensitive section is included by, and the only way into it.
 *
 * It says what would be sent and what would not, in the plainest words the screen has, and it
 * offers the two depths separately: how many acts and how they were protected, or the same with
 * their dates. Nothing about anybody else is in either, because nothing about anybody else is
 * recorded. Cancelling leaves the row unticked, which is the state it starts in and returns to.
 */
@Composable
private fun SensitiveConsentDialog(onDismiss: () -> Unit, onAgree: (SexualDetail) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, null, tint = PwnMuted) },
        title = { Text(stringResource(R.string.report_sensitive_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.report_sensitive_body))
                Text(stringResource(R.string.report_sensitive_detail_note), color = PwnMuted)
            }
        },
        // The two depths stand together as the two ways of saying yes, one over the other so a long
        // answer wraps inside its own button, and Cancel keeps a slot of its own apart from both.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onAgree(SexualDetail.SUMMARY) }) {
                    Text(stringResource(R.string.report_sensitive_summary))
                }
                TextButton(onClick = { onAgree(SexualDetail.DETAILED) }) {
                    Text(stringResource(R.string.report_sensitive_detailed))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * The question the symptoms row asks, which is a different question from the one beside it.
 *
 * It states the two things a person is deciding between: the sheet will say that a symptom was
 * reported, and it will not say how often, when, or anything about protection. It also says the
 * part that cannot be helped — the ticks live on the entries for sex, so a symptom on the sheet
 * does say that sex happened.
 */
@Composable
private fun SymptomConsentDialog(onDismiss: () -> Unit, onAgree: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, null, tint = PwnMuted) },
        title = { Text(stringResource(R.string.report_symptom_consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.report_symptom_consent_body))
                Text(stringResource(R.string.report_symptom_consent_note), color = PwnMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) { Text(stringResource(R.string.report_symptom_consent_agree)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * The question the morning check-ins ask, their own and not the sexual-activity one: they are a
 * different record, and the dated depth of that section says nothing about mornings.
 *
 * It says what the sheet would carry — how many mornings were answered and how often each answer was
 * given, the usual waking, a share a week on the chart and, where the changes are compared, a share a
 * stretch that may stand beside sleep — and what it would not: a morning with its date, or any act.
 * The weeks and the stretches are dates too, and a question that promised none would be untrue.
 * Cancelling leaves the row unticked, the state it starts in.
 */
@Composable
private fun MorningConsentDialog(onDismiss: () -> Unit, onAgree: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, null, tint = PwnMuted) },
        title = { Text(stringResource(R.string.report_morning_consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.report_morning_consent_body))
                Text(stringResource(R.string.report_morning_consent_note), color = PwnMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) { Text(stringResource(R.string.report_morning_consent_agree)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Which health system the sheet is laid out for.
 *
 * It changes units and the date format. It claims nothing about compliance with any country's law,
 * and the line under the list says so — a picker that implied otherwise would be the app's most
 * expensive sentence.
 */
@Composable
private fun CountryPickerSheet(chosen: CountryProfile, onDismiss: () -> Unit, onPick: (CountryProfile) -> Unit) {
    PwnPickerSheet(
        title = stringResource(R.string.report_country_title),
        subtitle = stringResource(R.string.report_country_sheet_body),
        onDismiss = onDismiss,
        action = null,
    ) {
        Column(Modifier.selectableGroup()) {
            CountryProfileRegistry.all.forEachIndexed { index, profile ->
                if (index > 0) HorizontalDivider(color = PwnBorder)
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(
                            selected = profile.id == chosen.id,
                            role = Role.RadioButton,
                            onClick = { onPick(profile) },
                        )
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Text(
                        stringResource(profile.displayNameRes),
                        Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (profile.id == chosen.id) {
                        Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(
                stringResource(R.string.report_country_disclaimer),
                Modifier.padding(top = 12.dp),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The last thing before anything leaves the phone: what is about to be handed over, in words.
 *
 * It names the doctor, the country, the window and every section that will be on the sheet — and,
 * separately and last, states whether sensitive data is included or not. Saying "not included" out
 * loud is the point: a person who is about to hand a report to a physiotherapist should be able to
 * see, without reading thirteen ticks, that nothing about their sex life is on it. Where the age gate
 * offers this person no sensitive row ([sensitiveOffered] is false) the line is not said at all: the
 * gate draws no door it keeps closed, and "not included" names the door.
 */
@Composable
private fun ReportPreviewSheet(
    report: DoctorReport?,
    months: Int,
    withFiles: Boolean,
    sensitiveOffered: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val built = report ?: return
    val locale = LocalLocale.current.platformLocale
    val format = remember(locale, built) { reportDayFormat(locale, built.request.country.datePatternIn(locale)) }
    PwnPickerSheet(
        title = stringResource(R.string.report_preview_title),
        subtitle = stringResource(reportMonthsRes(months)),
        onDismiss = onDismiss,
        action = {
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.report_preview_confirm))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PreviewLine(
                stringResource(R.string.report_doctor_title),
                built.specialty?.let { stringResource(it.titleRes) } ?: stringResource(R.string.report_doctor_choose),
            )
            PreviewLine(
                stringResource(R.string.report_for_country),
                stringResource(built.request.country.displayNameRes),
            )
            PreviewLine(
                stringResource(R.string.report_period),
                stringResource(
                    R.string.report_period_value,
                    built.from.format(format),
                    built.to.format(format),
                ),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = PwnBorder)
            built.sections.filter { it.part != ReportPart.PERSON }.forEach { section ->
                PreviewSectionLine(section)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = PwnBorder)
            if (withFiles) {
                Text(
                    stringResource(R.string.report_archive_preview, built.attachments.distinctBy { it.uri }.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Said whichever way it falls, and said last, where the eye stops. The morning check-ins
            // are named on a line of their own: their question promised nothing about sexual activity,
            // and a sheet carrying only them must not be announced as carrying it.
            val sexual = built.carriesSexualActivity
            if (sensitiveOffered || sexual) {
                Text(
                    stringResource(
                        if (sexual) R.string.report_preview_sensitive_in else R.string.report_preview_sensitive_out,
                    ),
                    color = if (sexual) PwnWarn else PwnMuted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (built.carriesMornings) {
                Text(
                    stringResource(R.string.report_preview_morning_in),
                    color = PwnWarn,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** One section on the preview: its name, and whether it is carrying anything. */
@Composable
private fun PreviewSectionLine(section: ReportSection) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (section.presence == SectionPresence.PRESENT) Icons.Outlined.Check else Icons.Outlined.Remove,
            contentDescription = null,
            tint = if (section.presence == SectionPresence.PRESENT) MaterialTheme.colorScheme.primary else PwnMuted,
            modifier = Modifier.size(18.dp),
        )
        Text(stringResource(section.part.titleRes), Modifier.weight(1f))
        if (section.presence != SectionPresence.PRESENT) {
            Text(
                stringResource(section.emptyLabelRes()),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * One named fact on the preview. Both halves are weighted: unweighted, a long label measured first
 * could take the whole row at a large text size and leave the value — the part being checked — with
 * no width at all. Each wraps inside its half instead.
 */
@Composable
private fun PreviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f, fill = false).padding(end = 8.dp), color = PwnMuted)
        Text(value, Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Who the report is for. Tapping a row is the whole commitment — choosing a doctor from a list
 * headed "which doctor" can mean nothing else — and it brings that doctor's ticks with it, and its
 * window unless the person already chose one.
 *
 * The list is the person's own: a man is not shown a gynaecologist, and a woman is not shown a
 * urologist, for the reason the routine examinations do not show either. [offered] is what this
 * person can tick at all, so a doctor's row names no section the age gate keeps from them.
 */
@Composable
private fun DoctorPickerSheet(
    sex: String,
    offered: List<ReportPart>,
    chosen: DoctorSpecialty?,
    onDismiss: () -> Unit,
    onPick: (DoctorSpecialty) -> Unit,
) {
    PwnPickerSheet(
        title = stringResource(R.string.report_doctor_title),
        subtitle = stringResource(R.string.report_doctor_body),
        onDismiss = onDismiss,
        action = null,
    ) {
        Column(Modifier.selectableGroup()) {
            specialtiesFor(sex).forEachIndexed { index, specialty ->
                if (index > 0) HorizontalDivider(color = PwnBorder)
                val parts = remember(specialty, sex, offered) { preselectedParts(specialty, sex).intersect(offered) }
                DoctorRow(specialty, parts, specialty == chosen) { onPick(specialty) }
            }
        }
    }
}

/** One doctor, under the sections they open the report with — named, not counted. */
@Composable
private fun DoctorRow(specialty: DoctorSpecialty, parts: Set<ReportPart>, chosen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        PwnGlyph(specialty.art, Icons.Outlined.MedicalServices, PwnOrangeSoft, null, Modifier.size(46.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(specialty.titleRes), fontWeight = FontWeight.SemiBold)
            Text(
                parts.map { stringResource(it.titleRes) }.joinToString(stringResource(R.string.list_comma)),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (chosen) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A row that can be ticked: the thing's picture, what it is, what it holds, and the box. [hint] is
 * a third line in the text-weight blue, for a word about the row rather than about its contents.
 */
@Composable
private fun PickerRow(
    art: Int?,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    hint: String? = null,
    onToggle: () -> Unit,
) {
    Row(
        // The box alone is silent to a screen reader; toggleable is what announces the answer.
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        PwnGlyph(art, icon, PwnBlueSoft, null, Modifier.size(46.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
            }
            hint?.let {
                Text(it, color = PwnBlueInk, style = MaterialTheme.typography.labelMedium)
            }
        }
        // The row carries the state and the click; the box is the picture of it.
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.minimumInteractiveComponentSize())
    }
}

/** The drawing beside a section in the picker, and the glyph for the ones the set has no drawing for. */
private val ReportPart.art: Int?
    get() = when (this) {
        ReportPart.PERSON -> R.drawable.ic_art_profile

        ReportPart.WEIGHT -> R.drawable.ic_art_weight

        // Body composition is read off the same scales the weight is, as the entry editor draws it.
        ReportPart.BODY_COMPOSITION -> R.drawable.ic_art_weight

        // The tape has no drawing of its own; the body one it shares with the scale is the nearest
        // true thing in the set.
        ReportPart.GIRTH -> R.drawable.ic_art_height

        ReportPart.ACTIVITY -> R.drawable.ic_art_steps

        ReportPart.NUTRITION -> R.drawable.ic_art_food

        ReportPart.SLEEP -> R.drawable.ic_art_sleep

        ReportPart.PULSE -> R.drawable.ic_art_pulse

        ReportPart.PRESSURE -> R.drawable.ic_art_blood

        ReportPart.OXYGEN -> R.drawable.ic_art_lungs

        // The set has no thermometer, and a doctor's own drawing on the temperature row would be
        // the doctor from the top of the page saying something else. The glyph stands instead.
        ReportPart.TEMPERATURE -> null

        ReportPart.MOOD -> R.drawable.ic_art_mood

        ReportPart.LABS, ReportPart.LAB_CONTEXT -> R.drawable.ic_art_labs

        ReportPart.MICRONUTRIENTS -> R.drawable.ic_art_food

        // Drawn from the sections above it; no one drawing is its subject.
        ReportPart.CHANGES -> null

        ReportPart.MEDICATIONS -> R.drawable.ic_art_doctor

        ReportPart.CYCLE -> R.drawable.ic_art_cycle

        ReportPart.SOCIAL -> R.drawable.ic_art_alcohol

        // The clock the work timer's own rows wear in the journal.
        ReportPart.WORK -> R.drawable.ic_art_time

        ReportPart.BOWEL -> R.drawable.ic_art_toilet

        ReportPart.PAIN -> R.drawable.ic_art_pain

        ReportPart.VISITS -> R.drawable.ic_art_doctor

        // The same drawing for both halves of it: they are one subject asked two ways.
        ReportPart.SEXUAL_HEALTH, ReportPart.SEXUAL_SYMPTOMS, ReportPart.MORNING_ERECTIONS -> R.drawable.ic_art_sex

        // The written-down history has no drawing in the set, and borrowing one would be the
        // wrong subject beside it: the cycle's drawing over a pregnancy row, or the doctor from the
        // top of the page over a list of operations. The glyph stands instead. Allergies are never
        // a tick at all, so nothing is ever drawn beside them.
        ReportPart.PROBLEMS,
        ReportPart.ALLERGIES,
        ReportPart.PROCEDURES,
        ReportPart.IMMUNISATIONS,
        ReportPart.PREGNANCY,
        ReportPart.CONTRACEPTION,
        -> null
    }

private val ReportPart.icon: ImageVector
    get() = if (this == ReportPart.TEMPERATURE) Icons.Outlined.DeviceThermostat else Icons.Outlined.MedicalServices

/** The specialties the visit form already draws keep its drawings; the rest take their own subject. */
private val DoctorSpecialty.art: Int
    get() = when (this) {
        DoctorSpecialty.GP -> R.drawable.ic_art_doctor

        DoctorSpecialty.CARDIOLOGIST -> R.drawable.ic_art_pulse

        DoctorSpecialty.ENDOCRINOLOGIST -> R.drawable.ic_art_labs

        DoctorSpecialty.DIETITIAN -> R.drawable.ic_art_food

        DoctorSpecialty.SPORTS -> R.drawable.ic_art_sport

        DoctorSpecialty.SOMNOLOGIST -> R.drawable.ic_art_sleep

        DoctorSpecialty.GYNECOLOGIST -> R.drawable.ic_art_gyn

        DoctorSpecialty.UROLOGIST -> R.drawable.ic_art_urology

        // Mood is the section this doctor opens with, and its drawing is the one that says so.
        DoctorSpecialty.PSYCHOLOGIST -> R.drawable.ic_art_mood

        // Not a doctor at all: the drawing is the report itself, which is what this choice is about.
        DoctorSpecialty.CUSTOM -> R.drawable.ic_art_report
    }
