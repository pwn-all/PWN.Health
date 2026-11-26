package com.pwnall.health

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.system.exitProcess

private enum class ProfileSection { MAIN, SUMMARY, SETTINGS, DATA, SOURCES, HEALTH, ABOUT }

/** The sections that read the journal itself; the others, and the front page, never ask for its rows. */
private val JOURNAL_SECTIONS = setOf(ProfileSection.SUMMARY, ProfileSection.SOURCES, ProfileSection.HEALTH)

/** An enum is not one of the types a Bundle knows, so the open section travels as its name. */
private val SECTION_SAVER: Saver<ProfileSection, String> =
    Saver(save = { it.name }, restore = { ProfileSection.valueOf(it) })

// Two counts in one sentence: a <plurals> declines one quantity, so each noun gets its own.
private fun Resources.backupSummary(@StringRes template: Int, summary: BackupSummary): String =
    getString(
        template,
        getQuantityString(R.plurals.backup_days, summary.days, summary.days),
        getQuantityString(R.plurals.backup_records, summary.journalEntries, summary.journalEntries),
    ) + (if (summary.photosLost > 0) "\n" + getString(R.string.backup_photos_lost, summary.photosLost) else "") +
        heldBackLine(summary)

@Composable
fun ProfileScreen(
    modifier: Modifier,
    profile: UserProfile,
    baseProfile: UserProfile,
    onLabs: () -> Unit,
    onDevices: () -> Unit,
    onImport: () -> Unit,
    onSensors: () -> Unit,
    onPain: () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val dao = remember { db.dao() }
    val scope = rememberCoroutineScope()
    val days by remember { dao.daysSince("0000-01-01") }.collectAsStateWithLifecycle(emptyList())
    // Saveable, not remembered: these are the unsaved form, and a rotation while reading the long
    // explanations under it would otherwise hand back the stored profile with the edits gone.
    var unitSystem by rememberSaveable(baseProfile) { mutableStateOf(baseProfile.unitSystem) }
    var goal by rememberSaveable(baseProfile) { mutableIntStateOf(baseProfile.dailyStepGoal.toInt()) }
    var automaticSleepDetection by rememberSaveable(baseProfile) {
        mutableStateOf(baseProfile.automaticSleepDetection)
    }
    var energyGoal by rememberSaveable(baseProfile) { mutableStateOf(baseProfile.energyGoal) }
    var remindersEnabled by rememberSaveable(baseProfile) { mutableStateOf(baseProfile.remindersEnabled) }
    var usageAccessGranted by remember { mutableStateOf(hasUsageStatsAccess(context)) }
    val activity = LocalActivity.current
    val lockAvailable = remember { AppLock.available(context) }
    var lockEnabled by remember { mutableStateOf(AppLock.enabled(context)) }
    // The switch's own answer; with the lock on, the window is secure whatever it says.
    var lockSecure by remember { mutableStateOf(AppLock.blocksScreenshots(context)) }
    // The encryption switch is read once and moved here; the file is only touched at the next
    // start, so there is nothing about it to observe. The error is the last start's, kept until the
    // switch moves again.
    var dbEncryption by remember { mutableStateOf(DatabaseEncryption.state(context)) }
    var dbEncryptionError by remember { mutableStateOf(DatabaseEncryption.lastError(context)) }
    // What waits beside the database in use, if anything ([SetAside]): read once, and again when a
    // copy has been let go of, since a copy of another kind may be waiting behind it.
    var setAside by remember { mutableStateOf(DatabaseEncryption.setAside(context)) }
    // Every message written here — "Saved", the import's summary — takes itself back down after
    // the same window a quick log's tick is shown for, rather than sitting under the section until
    // the page is left, long after the thing it reported had happened.
    var status by rememberStatusLine()
    // A failure is not written into [status]: the tick there is green and gone in three seconds,
    // and "the backup was not made" is neither. It stays, under the error's mark, until it is tapped
    // away, replaced by the next report, or the section is left.
    var failure by remember { mutableStateOf<String?>(null) }

    fun confirm(message: String) {
        failure = null
        status = message
    }

    fun fail(message: String) {
        status = null
        failure = message
    }
    // An export's or an import's last word, said by whichever page is standing when it lands.
    LaunchedEffect(Unit) {
        backupReport.filterNotNull().collect { report ->
            if (report.failed) fail(report.text) else confirm(report.text)
            backupReport.compareAndSet(report, null)
        }
    }
    // Read from the preferences rather than held: the worker writes them in the background at three
    // in the morning, with no screen composed, so they are read again whenever a run ends, a
    // section opens or the status line changes — the three moments they can have moved.
    var lastExportMs by remember { mutableStateOf(AutoBackup.lastExportMs(context)) }
    var autoBackup by remember { mutableStateOf(AutoBackup.state(context)) }
    val autoRuns by remember { AutoBackup.runs(context) }.collectAsStateWithLifecycle(emptyList())
    val backupAge = backupAgeDays(lastExportMs, LocalDate.now(), ZoneId.systemDefault())
    // Only a tree that came back turns the switch on; a picker dismissed leaves it as it was.
    val pickBackupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let {
            AutoBackup.enable(context, it)
            autoBackup = AutoBackup.state(context)
        }
    }
    var protectExport by rememberSaveable { mutableStateOf(false) }
    // A protected backup that was picked and staged, waiting for the password that opens it.
    var lockedImport by remember { mutableStateOf<LockedImport?>(null) }
    var importPassword by remember { mutableStateOf("") }
    // A wrong password is said on the field it was typed into, beside the button that tried it.
    var importPasswordRejected by remember { mutableStateOf(false) }
    // The process's word, not this composition's: see [backupImportRunning].
    val importing by backupImportRunning.collectAsStateWithLifecycle()
    val fileImporting by fileImportRunning.collectAsStateWithLifecycle()
    val exporting by backupExportRunning.collectAsStateWithLifecycle()
    // The doors to [closeForDatabaseChange] stay shut while something is still writing, because the
    // close ends the process under it: a backup's photographs are put back after its rows commit, and
    // an import, an export or an exchange cut off halfway is reported nowhere.
    val writing = importing || fileImporting || exporting || healthSyncRunningState.value
    var section by rememberSaveable(stateSaver = SECTION_SAVER) {
        mutableStateOf(ProfileSection.MAIN)
    }
    // The export's password lives in plain memory for as long as the section does, never in saved
    // state: a Bundle outlives the screen and is the wrong place for a secret. Keyed by the section,
    // or it would stay typed for as long as the tab did, an eye's tap from being read by the next hand.
    var exportPassword by remember(section) { mutableStateOf("") }
    var exportPasswordRepeat by remember(section) { mutableStateOf("") }
    // The whole journal, and only while a section that reads it is open. Collected from the moment
    // the tab is, it would have the front page — a menu — read every row ever written into memory,
    // and again on every write, to learn whether there was one. Null until the rows
    // arrive, so that a section opening does not take "not read yet" for "nothing written".
    val readsJournal = section in JOURNAL_SECTIONS
    val journal by remember(readsJournal) {
        if (readsJournal) dao.journalBetween("0000-01-01", "9999-12-31") else flowOf<List<JournalEntry>?>(null)
    }.collectAsStateWithLifecycle(null)
    // What the front page asks of the journal: whether there is anything in it to lose. Counted
    // again whenever a section opens or closes — an import or a first record happens behind one.
    val hasRecords by produceState(false, section) { value = dao.journalCount() > 0 }
    LaunchedEffect(autoRuns, status, section) {
        lastExportMs = AutoBackup.lastExportMs(context)
        autoBackup = AutoBackup.state(context)
    }

    // The five answers above that wait for the Save button, against what is stored. The lock, the
    // screenshot block and the encryption switch save on the spot and are not part of it.
    val settingsDirty = unitSystem != baseProfile.unitSystem ||
        goal.toLong() != baseProfile.dailyStepGoal ||
        automaticSleepDetection != baseProfile.automaticSleepDetection ||
        energyGoal != baseProfile.energyGoal ||
        remindersEnabled != baseProfile.remindersEnabled
    val saveSettings: () -> Unit = {
        scope.launch {
            // Like every other write on this page: a tab tapped straight after Save leaves the page,
            // not the answers half-applied — saved with the reminders still as they were.
            withContext(NonCancellable) {
                db.saveProfile(
                    baseProfile.copy(
                        unitSystem = unitSystem,
                        dailyStepGoal = goal.toLong(),
                        automaticSleepDetection = automaticSleepDetection,
                        energyGoal = energyGoal,
                        remindersEnabled = remindersEnabled,
                    ),
                )
                Reminders.apply(context, remindersEnabled)
                if (automaticSleepDetection && hasUsageStatsAccess(context)) {
                    withContext(Dispatchers.IO) { detectEstimatedSleep(context) }
                }
                confirm(resources.getString(R.string.profile_saved))
            }
        }
    }

    // The switch turned on without Usage access opens that screen and stays off: it goes on only
    // when the access comes back granted.
    var wantsAutoSleep by rememberSaveable { mutableStateOf(false) }
    val requestUsageAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        usageAccessGranted = hasUsageStatsAccess(context)
        if (wantsAutoSleep && usageAccessGranted) automaticSleepDetection = true
        wantsAutoSleep = false
    }

    // One writer for both export doors: the plain JSON and, behind a password, the same JSON
    // encrypted on its way out. Either is spooled in the cache and copied to the document after.
    val exportTo: (Uri, CharArray?) -> Unit = { uri, password ->
        scope.launch {
            withContext(NonCancellable) {
                // The password lives only in memory, and an Activity recreated under the picker
                // comes back without it. A file sealed under nothing is not written at all: the
                // empty document the picker already created is removed, and the failure card says why.
                backupReport.value = if (password != null && password.all(Char::isWhitespace)) {
                    withContext(Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    }
                    BackupReport(resources.getString(R.string.profile_export_retype), failed = true)
                } else {
                    runCatching { whileExporting { exportSpooled(context, db, uri, password) } }.fold(
                        onSuccess = {
                            BackupReport(resources.backupSummary(R.string.profile_exported, it), failed = false)
                        },
                        onFailure = {
                            BackupReport(
                                resources.getString(R.string.profile_export_error, it.message ?: ""),
                                failed = true,
                            )
                        },
                    )
                }
                // The copy the picker's answer was handed; the field's own String cannot be wiped.
                password?.fill(Char(0))
                lastExportMs = AutoBackup.lastExportMs(context)
            }
        }
    }
    // The damaged database, zipped with its log into a document of the person's choosing: the app's
    // private storage is out of their reach, and the file is of use only where something can read it.
    val saveDamagedCopy = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let { document ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(document, "wt")!!.use { out ->
                            DatabaseEncryption.writeDamagedCopies(context, out)
                        }
                    }
                }.fold(
                    onSuccess = { confirm(resources.getString(R.string.db_damaged_saved)) },
                    onFailure = { fail(resources.getString(R.string.profile_export_error, it.message ?: "")) },
                )
            }
        }
    }
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { exportTo(it, null) } }
    val createLockedBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let {
            exportTo(it, exportPassword.toCharArray())
            // Off the page once the export has its copy: kept typed after the file is written, it would stand
            // an eye's tap from being read for as long as the section stayed open. An export that
            // fails asks for it again, as one whose window was rebuilt under the picker does.
            exportPassword = ""
            exportPasswordRepeat = ""
        }
    }

    fun reportImport(result: Result<ImportOutcome>) {
        backupReport.value = result.fold(
            onSuccess = { outcome ->
                BackupReport(
                    when (outcome) {
                        is ImportOutcome.Merged -> resources.backupSummary(R.string.profile_imported, outcome.summary)

                        is ImportOutcome.Profile -> resources.getString(
                            if (outcome.found) R.string.profile_profile_restored else R.string.profile_profile_absent,
                        )
                    },
                    failed = false,
                )
            },
            onFailure = { BackupReport(importFailureText(resources, it), failed = true) },
        )
    }
    // Both import doors come through here. The document is staged locally first: the import is one
    // transaction, and a document provider can be a cloud folder — reading it from inside the
    // transaction would hold the database for as long as the network takes, while the step service
    // is trying to write the walk happening at that moment. A protected file stops here and waits
    // for its password; a plain one is imported at once.
    //
    // Both doors, and the password's button after them, run inside [asOnlyBackupImport]: the buttons
    // go grey while an import runs, but a tap can land before the page is drawn again, and a picker
    // opened earlier can still hand over its file. Either is then left undone, not run beside it.
    val pickBackup: (Uri, Boolean) -> Unit = { uri, profileOnly ->
        failure = null
        scope.launch {
            withContext(NonCancellable) {
                asOnlyBackupImport {
                    val staged = withContext(Dispatchers.IO) { runCatching { stageDocument(context, uri) } }
                        .getOrElse {
                            val reason = it.message ?: it.javaClass.simpleName
                            backupReport.value =
                                BackupReport(resources.getString(R.string.profile_import_error, reason), failed = true)
                            return@asOnlyBackupImport
                        }
                    val encrypted = withContext(Dispatchers.IO) { staged.isEncryptedBackup() }
                    if (encrypted && !scope.isActive) {
                        // The page turned on its side, or left, while the copy was made: its password
                        // card went with it, and so does the copy, rather than wait in the cache for
                        // [sweepExportLeftovers]. On the main thread, as the page's onDispose is.
                        staged.delete()
                    } else if (encrypted) {
                        lockedImport?.staged?.delete()
                        lockedImport = LockedImport(staged, profileOnly)
                        importPassword = ""
                        importPasswordRejected = false
                    } else {
                        reportImport(runCatching { importStaged(context, db, staged, null, profileOnly) })
                        staged.delete()
                    }
                }
            }
        }
    }
    val unlockImport: () -> Unit = {
        lockedImport?.let { locked ->
            failure = null
            scope.launch {
                withContext(NonCancellable) {
                    asOnlyBackupImport {
                        val typed = importPassword.toCharArray()
                        val result = runCatching { importStaged(context, db, locked.staged, typed, locked.profileOnly) }
                        typed.fill(Char(0))
                        // A wrong password keeps the file for another try, and is said on the field
                        // rather than over the page; anything else is reported and done with — see
                        // [keepsLockedImport] for the page that went while the password was tried.
                        if (keepsLockedImport(result.exceptionOrNull(), locked.staged.exists())) {
                            importPasswordRejected = true
                        } else {
                            reportImport(result)
                            locked.staged.delete()
                            lockedImport = null
                            importPassword = ""
                        }
                    }
                }
            }
        }
    }
    val dropLockedImport: () -> Unit = {
        lockedImport?.staged?.delete()
        lockedImport = null
        importPassword = ""
        importPasswordRejected = false
    }
    // The staged copy is the size of the whole backup and lives in the cache: a screen that goes
    // away — another tab, a rotation — takes it along rather than leaving it for the OS to find.
    DisposableEffect(Unit) {
        onDispose { lockedImport?.staged?.delete() }
    }
    // Its own picker, because it is its own decision: the merge deliberately leaves the profile
    // alone, and replacing a set of answers that carry no timestamps is something to ask for rather
    // than to receive.
    val openProfileBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pickBackup(it, true) }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pickBackup(it, false) }
    }

    val bmiValue = bmi(profile.weightKg, profile.heightCm)
    val resting = restingCaloriesPerDay(profile, Year.now().value)
    // The budget's own figure, not resting energy times an activity factor: the home screen
    // measures movement and credits it as it happens, so its target is built on a sedentary base,
    // and a second daily need on this page — 300 kcal apart from the first — would be two answers
    // to one question.
    val dailyNeed = energyBudget(profile, Year.now().value).targetKcal
    val imperial = profile.imperial
    val weightText = if (imperial) {
        "${imperialWeight(profile.weightKg).shown()} ${stringResource(R.string.unit_lb)}"
    } else {
        "${profile.weightKg.shown()} ${stringResource(R.string.unit_kg)}"
    }
    val heightText = if (imperial) {
        imperialHeight(profile.heightCm)
    } else {
        "${profile.heightCm} ${stringResource(R.string.unit_cm)}"
    }

    val activeDays = days.count { it.steps > 0 }
    // The ladder is a calendar year's climb and empties every 1st of January, so the total printed
    // under it is that year's as well: a lifetime figure over medals that reset would be two
    // different counts on one card.
    val year = Year.now().value
    val yearSteps = remember(days, year) {
        days.filter { it.day.startsWith("$year-") }.sumOf(DaySteps::steps)
    }
    val badges = remember(days, year) { stepAchievements(days, year) }

    // Sections are internal state the app's history knows nothing about, so they answer the back
    // gesture here, before the app's own handler takes it for its trail.
    BackHandler(enabled = section != ProfileSection.MAIN) { section = ProfileSection.MAIN }
    val inSection = section != ProfileSection.MAIN
    // The page is the scroll, so the flash cannot live inside it: dropped in the Column it would
    // be laid out under the last section and ride the scroll away from the middle of the screen.
    // The Box is the whole screen, the scroll — with the settings' Save button under it while
    // there is something to save — is its first child, and the card is drawn over both.
    // A section is held open over the tab's front page as a panel — see [PwnPageHost]. Its header
    // carries the arrow back, and the system back leaves it as well. The arrow goes through the
    // dispatcher, so the panel's own handler plays the exit.
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    //
    // One scroll for the front page and one for whatever is open over it: they are two pages
    // sharing a Column, and with a single state, opening a section would throw away where the front
    // page was standing — and drop the section itself in at the same offset.
    val mainScroll = rememberScrollState()
    // A fresh one for every section, rather than one shared scroll sent to the top whenever a
    // section opens. That reset would run on every return to the tab as well, so back from a page
    // opened inside a section — the blood tests, the pain page — the section would be at its top; and
    // the shared one would stand for a frame at the last section's offset, which [keepReturnPosition]
    // would read as a place to go back to.
    val sectionScroll = rememberSaveable(section, saver = ScrollState.Saver) { ScrollState(0) }
    LaunchedEffect(section) { failure = null }
    // The front page and whatever is held open over it are the same Column, told which of the two
    // to draw. Two call sites rather than one: the panel is handed the open section, and the
    // panel's backdrop is handed the front page — so the page being returned to is behind the
    // panel, blurred, for the whole of the pull that lets it go.
    val profilePage: @Composable (Modifier, ProfileSection) -> Unit = { pageBase, shown ->
        val open = shown != ProfileSection.MAIN
        Box(pageBase.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .panelVerticalScroll(if (open) sectionScroll else mainScroll)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = LocalBottomBarInset.current),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(Modifier.height(8.dp))
                    if (!open) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.profile_title),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).semantics { heading() },
                            )
                            IconButton(onClick = {
                                section = ProfileSection.SETTINGS
                            }, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                                Icon(Icons.Outlined.Settings, stringResource(R.string.profile_settings))
                            }
                        }
                        YearGrid(days, profile.dailyStepGoal)
                        AchievementSection(
                            badges = badges,
                            subtitle = stringResource(R.string.achievement_year_value, yearSteps.formatted()),
                        )
                        // One row, and only when there is something to lose and no recent copy of it: a journal
                        // with nothing in it has nothing to back up, and a month is how long a phone's worth of
                        // records can reasonably go without one before the page should say so.
                        if (hasRecords && backupNeedsAttention(backupAge, autoBackup.error)) {
                            PwnCard(Modifier.fillMaxWidth()) {
                                ProfileMenuRow(
                                    R.drawable.ic_art_export,
                                    Icons.Outlined.FileDownload,
                                    PwnOrangeSoft,
                                    backupAgeText(backupAge),
                                    // A failed nightly copy is named, with the reason the worker wrote
                                    // down: "switch on the daily one" is no advice when it is on.
                                    autoBackup.error?.let { stringResource(R.string.backup_auto_failed, it) }
                                        ?: stringResource(R.string.backup_nudge_body),
                                    divider = false,
                                ) { section = ProfileSection.DATA }
                            }
                        }
                        // A database set aside beside the one in use ([SetAside]): this phone's own, which can be
                        // opened again; one no key here opens, for which the export is the way back; or a damaged
                        // one, which can be saved out to a file. Forgetting it is the other answer each time, and
                        // it is the person's.
                        setAside?.let { kind ->
                            SetAsideCard(
                                kind = kind,
                                actionEnabled = kind != SetAside.OWN || !writing,
                                onAction = {
                                    when (kind) {
                                        // A wish for the next start, like the switch's: the files change
                                        // places before anything holds them open, so the app closes.
                                        SetAside.OWN -> {
                                            DatabaseEncryption.requestReturn(context)
                                            activity?.let(::closeForDatabaseChange)
                                        }

                                        SetAside.LOCKED -> section = ProfileSection.DATA

                                        SetAside.DAMAGED -> saveDamagedCopy.launch("pwn-health-damaged-database.zip")
                                    }
                                },
                                // A whole database and its logs deleted: disk work, kept off the main thread.
                                onForget = {
                                    scope.launch {
                                        setAside = withContext(NonCancellable + Dispatchers.IO) {
                                            DatabaseEncryption.forget(context, kind)
                                            DatabaseEncryption.setAside(context)
                                        }
                                    }
                                },
                            )
                        }
                        PwnCard(Modifier.fillMaxWidth()) {
                            Column {
                                ProfileMenuRow(
                                    R.drawable.ic_art_profile,
                                    Icons.Outlined.PersonOutline,
                                    PwnIndigoSoft,
                                    stringResource(R.string.profile_summary_menu),
                                    stringResource(R.string.profile_summary_menu_body),
                                ) { section = ProfileSection.SUMMARY }
                                // A watch is not a setting, so the door is on the front page rather than three
                                // taps down behind a gear. What is behind it is narrow: the
                                // scale, the strap and the machine are asked for from the forms that want their
                                // numbers, and this is where the instruments with no form of their own live.
                                ProfileMenuRow(
                                    R.drawable.ic_art_sync,
                                    Icons.Outlined.Bluetooth,
                                    PwnBlueSoft,
                                    stringResource(R.string.direct_bluetooth_title),
                                    stringResource(R.string.devices_menu_body),
                                    onClick = onDevices,
                                )
                                // A history kept in another app comes in here: through Health Connect, or as
                                // the file that app exports. Not on the Bluetooth page, which is for a device
                                // in the room.
                                ProfileMenuRow(
                                    R.drawable.ic_art_history,
                                    Icons.Outlined.History,
                                    PwnBlueSoft,
                                    stringResource(R.string.import_apps_title),
                                    stringResource(R.string.import_apps_menu_body),
                                    onClick = onImport,
                                )
                                // Straight under the door to the devices: the question this answers — which of
                                // them wrote the number in front of me — is the one that follows connecting a
                                // second one.
                                ProfileMenuRow(
                                    R.drawable.ic_art_sensors,
                                    Icons.Outlined.Storage,
                                    PwnBlueSoft,
                                    stringResource(R.string.sources_title),
                                    stringResource(R.string.sources_menu_body),
                                ) { section = ProfileSection.SOURCES }
                                ProfileMenuRow(
                                    R.drawable.ic_art_pulse,
                                    Icons.Outlined.Favorite,
                                    PwnOrangeSoft,
                                    stringResource(R.string.health_title),
                                    stringResource(R.string.health_menu_body),
                                ) { section = ProfileSection.HEALTH }
                                ProfileMenuRow(
                                    R.drawable.ic_art_storage,
                                    Icons.Outlined.Storage,
                                    PwnPurpleSoft,
                                    stringResource(R.string.profile_local_data),
                                    stringResource(R.string.profile_data_menu_body),
                                    divider = false,
                                ) { section = ProfileSection.DATA }
                            }
                        }
                        PwnLocalDataCard()
                    } else {
                        ProfileSectionHeader(
                            title = stringResource(
                                when (shown) {
                                    ProfileSection.SUMMARY -> R.string.profile_summary_menu
                                    ProfileSection.SETTINGS -> R.string.profile_settings
                                    ProfileSection.DATA -> R.string.profile_local_data
                                    ProfileSection.SOURCES -> R.string.sources_title
                                    ProfileSection.HEALTH -> R.string.health_title
                                    ProfileSection.ABOUT -> R.string.about_title
                                },
                            ),
                            onBack = { dispatcher?.onBackPressed() },
                            // A note that covers the whole page goes beside its name, not over it.
                            help = when (shown) {
                                // How the figures on the summary are reached, behind its question mark.
                                ProfileSection.SUMMARY -> stringResource(R.string.profile_calorie_method_body)

                                ProfileSection.DATA -> stringResource(R.string.profile_local_note)

                                ProfileSection.SOURCES -> stringResource(R.string.sources_note)

                                else -> null
                            },
                            helpTitle = stringResource(R.string.profile_calorie_method)
                                .takeIf { shown == ProfileSection.SUMMARY },
                        )

                        when (shown) {
                            ProfileSection.SUMMARY -> {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SummaryCard(
                                        stringResource(R.string.profile_bmi),
                                        "%.1f".format(bmiValue),
                                        stringResource(bmiLabelRes(bmiValue, profile.birthDate, LocalDate.now())),
                                        Modifier.weight(1f),
                                    )
                                    SummaryCard(
                                        stringResource(R.string.weight),
                                        weightText,
                                        heightText,
                                        Modifier.weight(1f),
                                    )
                                }
                                // The body fat a smart scale's impedance gives, kept as a figure of its own
                                // rather than in a note.
                                journal?.firstNotNullOfOrNull { it.bodyFatPercent }?.let { percent ->
                                    PwnCard(Modifier.fillMaxWidth()) {
                                        Column(
                                            Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            HelpTitle(stringResource(R.string.body_fat_title)) {
                                                Text(stringResource(R.string.body_fat_note))
                                            }
                                            Text(
                                                stringResource(
                                                    R.string.body_fat_value,
                                                    percent.shown(),
                                                    fatMassKg(profile.weightKg, percent).shown(),
                                                ),
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SummaryCard(
                                        stringResource(R.string.profile_rest),
                                        "%.0f".format(resting),
                                        stringResource(R.string.profile_kcal_day),
                                        Modifier.weight(1f),
                                    )
                                    SummaryCard(
                                        stringResource(R.string.profile_days),
                                        activeDays.toString(),
                                        stringResource(R.string.profile_with_steps),
                                        Modifier.weight(1f),
                                    )
                                }
                                PwnCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(PwnDimensions.cardPadding)) {
                                        HelpTitle(stringResource(R.string.profile_energy_guide)) {
                                            Text(stringResource(R.string.profile_energy_note))
                                        }
                                        Text(
                                            stringResource(R.string.profile_energy_value, dailyNeed),
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }

                            ProfileSection.SETTINGS -> {
                                ThemeSettingsSection()
                                Text(
                                    stringResource(R.string.measurement_system),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                PwnSegmentedControl(
                                    options = listOf(
                                        stringResource(R.string.measurement_metric) to null,
                                        stringResource(R.string.measurement_imperial) to null,
                                    ),
                                    selected = if (unitSystem == "METRIC") 0 else 1,
                                    onSelected = { unitSystem = if (it == 0) "METRIC" else "IMPERIAL" },
                                )
                                WheelPickerCard(
                                    label = stringResource(R.string.profile_step_goal),
                                    value = (goal / 500).coerceIn(2, 200),
                                    values = (2..200).toList(),
                                    formatter = { (it * 500L).formatted() },
                                    onValue = { goal = it * 500 },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                // Asked of an adult only: under eighteen the goal moves neither the budget, the advice
                                // nor the step recommendation ([coachedEnergyGoal]), and a choice that changes nothing
                                // is not offered.
                                if (energyGoalOffered(profile.birthDate, Year.now().value)) {
                                    PwnChoiceField(
                                        label = stringResource(R.string.goal_energy),
                                        options = listOf(
                                            stringResource(R.string.goal_maintain),
                                            stringResource(R.string.goal_lose),
                                            stringResource(R.string.goal_gain),
                                        ),
                                        selected = ENERGY_GOALS.indexOf(energyGoal).coerceAtLeast(0),
                                        onSelected = { energyGoal = ENERGY_GOALS[it] },
                                    )
                                }
                                PwnCard(Modifier.fillMaxWidth()) {
                                    PwnSwitchRow(
                                        title = stringResource(R.string.reminders_title),
                                        body = stringResource(R.string.reminders_body),
                                        checked = remindersEnabled,
                                        onCheckedChange = { remindersEnabled = it },
                                        contentPadding = PaddingValues(16.dp),
                                    )
                                    if (remindersEnabled) MedicineAlarmSettings()
                                }
                                // Saved on the spot rather than with the button below: switching it either way
                                // has to pass the prompt first, and a setting you just authenticated for should
                                // not then be lost by leaving the screen without saving.
                                PwnCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PwnSwitchRow(
                                            title = stringResource(R.string.lock_title),
                                            body = stringResource(
                                                if (lockAvailable) R.string.lock_body else R.string.lock_unavailable,
                                            ),
                                            checked = lockEnabled,
                                            enabled = lockAvailable && activity != null,
                                            onCheckedChange = { on ->
                                                // Through the prompt both ways: on, to prove the lock can
                                                // be opened before it is put on; off, because whoever was
                                                // handed the phone with the app open could otherwise take it
                                                // off with a tap, and find it open the next time too. The lock
                                                // carries FLAG_SECURE with it, and the window is already
                                                // up, so the flag follows the switch at once.
                                                activity?.let {
                                                    AppLock.prompt(it) { ok ->
                                                        if (ok) {
                                                            lockEnabled = on
                                                            AppLock.setEnabled(context, on)
                                                            // The answer the lock asks for: a window shut
                                                            // under the prompt opens on it as well.
                                                            AppLock.unlocked.value = true
                                                        }
                                                        AppLock.applySecure(it)
                                                    }
                                                }
                                            },
                                        )
                                        // Shown on and held there while the lock is on: the recents thumbnail
                                        // of a locked app is the one place its numbers could still be read.
                                        PwnSwitchRow(
                                            title = stringResource(R.string.lock_secure_title),
                                            body = stringResource(
                                                if (lockEnabled) {
                                                    R.string.lock_secure_with_lock
                                                } else {
                                                    R.string.lock_secure_body
                                                },
                                            ),
                                            checked = lockSecure || lockEnabled,
                                            enabled = !lockEnabled,
                                            onCheckedChange = { on ->
                                                lockSecure = on
                                                AppLock.setSecure(context, on)
                                                // The window is already up, so the flag has to be set on it now.
                                                activity?.let { AppLock.applySecure(it) }
                                            },
                                        )
                                        // The file at rest, which the two switches above do nothing for: a lock
                                        // on the screen leaves steps.db readable to whatever reads the storage.
                                        // Off by default for the reason written under it, and only a wish until
                                        // the next start — the file is never rewritten under a running Room.
                                        PwnSwitchRow(
                                            title = stringResource(R.string.db_encrypt_title),
                                            help = stringResource(R.string.db_encrypt_body),
                                            checked = dbEncryption.wantsCipher,
                                            onCheckedChange = { on ->
                                                val move = {
                                                    dbEncryption = DatabaseEncryption.request(context, on)
                                                    dbEncryptionError = null
                                                }
                                                // Taken off behind the lock's own question while the
                                                // lock is on, as the lock itself is.
                                                if (on || !lockEnabled) {
                                                    move()
                                                } else {
                                                    activity?.let { host ->
                                                        AppLock.prompt(host) { ok ->
                                                            if (ok) {
                                                                move()
                                                                AppLock.unlocked.value = true
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                        // The switch promises noise to whoever reads the storage; the daily
                                        // copies are the same record in plain text, and with both on
                                        // nothing else would say the two together.
                                        plainCopyWarning(dbEncryption.wantsCipher, autoBackup.folder != null)?.let {
                                            Text(
                                                stringResource(it),
                                                color = PwnWarn,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        dbEncryptionError?.let {
                                            Text(
                                                stringResource(R.string.db_encrypt_error, it),
                                                color = PwnWarn,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        if (dbEncryption.pending) {
                                            Button(
                                                onClick = { activity?.let(::closeForDatabaseChange) },
                                                modifier = Modifier.fillMaxWidth(),
                                                enabled = !writing,
                                            ) { Text(stringResource(R.string.db_encrypt_restart)) }
                                        }
                                    }
                                }
                                PwnCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PwnSwitchRow(
                                            title = stringResource(R.string.profile_auto_sleep_title),
                                            help = stringResource(R.string.profile_auto_sleep_body),
                                            checked = automaticSleepDetection && usageAccessGranted,
                                            onCheckedChange = { on ->
                                                if (on && !hasUsageStatsAccess(context)) {
                                                    wantsAutoSleep = true
                                                    requestUsageAccess.openUsageAccess(context)
                                                } else {
                                                    automaticSleepDetection = on
                                                }
                                            },
                                        )
                                        Text(
                                            stringResource(
                                                if (usageAccessGranted) {
                                                    R.string.profile_usage_access_granted
                                                } else {
                                                    R.string.profile_usage_access_required
                                                },
                                            ),
                                            color = if (usageAccessGranted) PwnIndigo else PwnMuted,
                                        )
                                        if (!usageAccessGranted) {
                                            OutlinedButton(
                                                onClick = { requestUsageAccess.openUsageAccess(context) },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) { Text(stringResource(R.string.profile_open_usage_access)) }
                                        }
                                    }
                                }
                                // Under the settings rather than among the front page's doors: what a build is
                                // and whom to write to are things looked for, not things looked at.
                                val version = remember { appVersion(context) }
                                PwnCard(Modifier.fillMaxWidth()) {
                                    ProfileMenuRow(
                                        R.drawable.ic_art_info,
                                        Icons.Outlined.Info,
                                        PwnIndigoSoft,
                                        stringResource(R.string.about_title),
                                        stringResource(R.string.about_version, version.name, version.code),
                                        divider = false,
                                    ) { section = ProfileSection.ABOUT }
                                }
                            }

                            ProfileSection.ABOUT -> AboutSection(profile, lockEnabled, onSensors)

                            ProfileSection.HEALTH -> {
                                // Asked once in onboarding and changeable here for the reason a test result
                                // is: somebody who answers "never tested" and then goes and gets tested has
                                // to be able to say so, or the card on the medicine page never goes away.
                                AllergyCard(baseProfile.allergyAnswer()) { answer ->
                                    scope.launch {
                                        withContext(NonCancellable) {
                                            dao.saveProfile(baseProfile.copy(allergies = answer.stored()))
                                        }
                                    }
                                }
                                // The labs live behind their own screen but belong to this section: the advice
                                // above is half made of them, and this is where a person comes looking.
                                //
                                // The dot is the same one the bell on that screen carries: a result outside
                                // its band that nobody has read yet. Worked out off the composition thread,
                                // because it is the same parse of every payload in the journal the results
                                // page runs, and this section is opened from a list rather than waited for.
                                val unreadLabs by produceState(false, journal, profile) {
                                    value = withContext(Dispatchers.Default) {
                                        // The same years the results page reads: a dot raised by a row
                                        // that page never shows could not be cleared by opening it.
                                        val start = labWindowStart(LocalDate.now())
                                        val inWindow = journal.orEmpty().filter { it.day >= start }
                                        val notices = labNotices(labHistory(inWindow, profile))
                                        unreadLabNotices(notices, LabNoticeLog.seenUntil(context)) > 0
                                    }
                                }
                                val unreadLabel = stringResource(R.string.labs_notices_unread)
                                PwnCard(Modifier.fillMaxWidth(), onClick = onLabs) {
                                    Row(
                                        Modifier.padding(PwnDimensions.cardPadding),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        PwnGlyph(
                                            R.drawable.ic_art_labs,
                                            Icons.Outlined.Science,
                                            PwnBlueSoft,
                                            null,
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    stringResource(R.string.labs_title),
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                                if (unreadLabs) {
                                                    Box(
                                                        Modifier.size(8.dp)
                                                            .semantics { contentDescription = unreadLabel }
                                                            .background(PwnBleed, CircleShape),
                                                    )
                                                }
                                            }
                                            Text(
                                                stringResource(R.string.add_labs_body),
                                                color = PwnMuted,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                        Icon(Icons.AutoMirrored.Outlined.NavigateNext, null, tint = PwnMuted)
                                    }
                                }
                                PainDoorCard(onPain)
                                // The topics switched off, held as state rather than read inside the producer:
                                // hiding one has to send the list round again, and a set read once at
                                // composition would leave the hidden card on the page until this section was
                                // left and opened afresh.
                                var hiddenTopics by remember { mutableStateOf(AdviceLog.hidden(context)) }
                                // The three below read today's steps, if at all, through a mean: a walk's
                                // every write is no reason to run them again.
                                val today = rememberToday()
                                val coarseDays = remember(days, today) { days.coarseToday(today.toString()) }
                                // Ten passes over every row ever written, re-run whenever anything is saved:
                                // off the composition thread, so opening this section does not stutter.
                                // Nothing at all until the rows have arrived, here and in the three
                                // below: counted over no rows, the answer is the "nothing to say" card.
                                val advice by produceState<List<Advice>?>(
                                    null,
                                    coarseDays,
                                    journal,
                                    profile,
                                    hiddenTopics,
                                ) {
                                    value = journal?.let { rows ->
                                        withContext(Dispatchers.Default) {
                                            allAdvice(
                                                coarseDays,
                                                rows,
                                                profile,
                                                LocalDate.now(),
                                                Year.now().value,
                                                hiddenTopics,
                                            )
                                        }
                                    }
                                }
                                advice?.let { found ->
                                    if (found.isEmpty()) {
                                        PwnCard(Modifier.fillMaxWidth()) {
                                            Text(
                                                stringResource(R.string.health_empty),
                                                Modifier.padding(PwnDimensions.cardPadding),
                                                color = PwnMuted,
                                            )
                                        }
                                    } else {
                                        found.forEach { one ->
                                            AdviceCard(one) { hiddenTopics = AdviceLog.hidden(context) }
                                        }
                                    }
                                }
                                // The way back sits directly under the advice it was thrown from, and only
                                // when something is off: an empty list of switched-off topics is a card
                                // nobody needs to read past. Turning one on writes the set and re-reads it,
                                // so the producer above runs again and the topic returns to the page it left.
                                // The list passes through the same gate the advice above does, because the
                                // stored set is older than the profile in front of it: a backup restored on to
                                // a profile with no birth date would otherwise have this card printing
                                // "Intimate health" where the app shows nothing else of the kind.
                                // The gate takes a date. [rememberToday] hands the same one back until the
                                // day turns, so this is worked out again at midnight and not on every pass.
                                val switchedOff = remember(hiddenTopics, profile, today) {
                                    switchedOffTopics(hiddenTopics) { topicOffered(it, profile, today) }
                                }
                                if (switchedOff.isNotEmpty()) {
                                    HiddenTopicsCard(switchedOff) { topic ->
                                        AdviceLog.show(context, topic)
                                        hiddenTopics = AdviceLog.hidden(context)
                                    }
                                }
                                // The same walk over every row the advice above takes, and for the same reason
                                // off the composition thread: the journal re-emits on every glass of water.
                                val baseline by produceState<Baseline?>(null, coarseDays, journal) {
                                    value = journal?.let { rows ->
                                        withContext(Dispatchers.Default) {
                                            personalBaseline(coarseDays, rows, LocalDate.now())
                                        }
                                    }
                                }
                                baseline?.let { BaselineCard(it) }
                                // The summary the doctor's sheet prints, shown here too: the one summary
                                // of sleep the app can honestly make is not to be the one thing its owner
                                // cannot read.
                                val sleep by produceState<SleepSummary?>(null, journal) {
                                    value = journal?.let { rows ->
                                        withContext(Dispatchers.Default) { sleepSummary(rows) }
                                    }
                                }
                                sleep?.takeIf { it.nights > 0 }?.let { SleepSummaryCard(it) }
                                // Off the composition thread like its neighbours: the engine walks four
                                // months of two series and bootstraps two thousand resamples of each.
                                val insights by produceState<InsightReport?>(null, coarseDays, journal, profile) {
                                    value = journal?.let { rows ->
                                        withContext(Dispatchers.Default) {
                                            insightReport(coarseDays, rows, profile, LocalDate.now())
                                        }
                                    }
                                }
                                insights?.let { InsightsCard(it) }
                            }

                            ProfileSection.DATA -> {
                                Text(backupAgeText(backupAge), style = MaterialTheme.typography.titleMedium)
                                AutoBackupCard(
                                    state = autoBackup,
                                    onToggle = { on ->
                                        if (on) {
                                            // A phone with no document provider that offers a tree is a phone.
                                            runCatching { pickBackupFolder.launch(null) }
                                        } else {
                                            AutoBackup.disable(context)
                                            autoBackup = AutoBackup.state(context)
                                        }
                                    },
                                )
                                // The switch sits above the button it changes: on, the file goes out encrypted
                                // under the password typed below it; off, the line underneath says what a plain
                                // file is — at the moment that matters, not on a settings page.
                                //
                                // In a card, like the daily copy above it. Loose on the page, the row would run the
                                // full width while every other control on the screen stands inset, so its switch
                                // would hang past the column the cards draw and the page would have no edge left.
                                PwnCard(Modifier.fillMaxWidth()) {
                                    Column(
                                        Modifier.padding(PwnDimensions.cardPadding),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        PwnSwitchRow(
                                            title = stringResource(R.string.profile_export_protect),
                                            checked = protectExport,
                                            onCheckedChange = { protectExport = it },
                                            titleStyle = MaterialTheme.typography.bodyLarge,
                                        )
                                        if (protectExport) {
                                            PasswordField(
                                                exportPassword,
                                                { exportPassword = it },
                                                R.string.profile_password,
                                                error = exportPasswordError(resources, exportPassword),
                                                imeAction = ImeAction.Next,
                                            )
                                            // Said on the field, not only by a button that stays grey.
                                            PasswordField(
                                                exportPasswordRepeat,
                                                { exportPasswordRepeat = it },
                                                R.string.profile_password_repeat,
                                                error = if (passwordsDiverge(exportPassword, exportPasswordRepeat)) {
                                                    stringResource(R.string.m3_profile_password_mismatch)
                                                } else {
                                                    null
                                                },
                                            )
                                        } else {
                                            Text(
                                                stringResource(R.string.profile_export_plain_note),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = PwnMuted,
                                            )
                                        }
                                    }
                                }
                                val passwordReady = exportPasswordReady(exportPassword, exportPasswordRepeat)
                                PwnWideButton(
                                    icon = Icons.Outlined.FileDownload,
                                    label = stringResource(R.string.profile_export),
                                    // Not beside an import: a lab sheet's row is committed a moment
                                    // before its photograph takes its name.
                                    enabled = (!protectExport || passwordReady) && !importing,
                                    onClick = {
                                        failure = null
                                        val stamp = LocalDate.now()
                                        if (protectExport) {
                                            createLockedBackup.launch("pwn-health-backup-$stamp.pwnfit")
                                        } else {
                                            createBackup.launch("pwn-health-backup-$stamp.json")
                                        }
                                    },
                                )
                                lockedImport?.let {
                                    LockedImportCard(
                                        password = importPassword,
                                        rejected = importPasswordRejected,
                                        onPassword = {
                                            importPassword = it
                                            importPasswordRejected = false
                                        },
                                        onUnlock = unlockImport,
                                        onCancel = dropLockedImport,
                                        busy = importing,
                                    )
                                }
                                // A year of photographs takes its time, and every door here is grey
                                // for all of it: said in words, and announced, the way the workout
                                // file's card says it, rather than left to be guessed.
                                if (importing) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text(
                                            stringResource(R.string.file_import_working),
                                            Modifier.politeLiveRegion(),
                                            color = PwnMuted,
                                        )
                                    }
                                }
                                // The same three doors the doctor's report puts at the foot of its page,
                                // in the same shape: this page hands a file to somebody too.
                                PwnWideButton(
                                    icon = Icons.Outlined.FileUpload,
                                    label = stringResource(R.string.profile_import),
                                    enabled = !importing,
                                    tonal = true,
                                    onClick = { openBackup.launch(BACKUP_MIME_TYPES) },
                                )
                                PwnWideButton(
                                    icon = Icons.Outlined.PersonOutline,
                                    label = stringResource(R.string.profile_restore_profile),
                                    enabled = !importing,
                                    tonal = true,
                                    onClick = { openProfileBackup.launch(BACKUP_MIME_TYPES) },
                                )
                            }

                            // Once the rows have arrived: handed none, the cards would say "no sources yet".
                            ProfileSection.SOURCES -> journal?.let { DataSourceCards(it, profile) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                // Under the scroll rather than at the end of it, and only while the form differs from
                // what is stored. The switches on this page that take effect on the spot never raise
                // it; a change that waits for it is never out of sight of the button that keeps it.
                if (shown == ProfileSection.SETTINGS && settingsDirty) {
                    Button(
                        onClick = saveSettings,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    ) { Text(stringResource(R.string.profile_save)) }
                }
            }
            // Only on the page in front. Drawn in the backdrop too it would play the same
            // confirmation twice, once of them under a blur.
            if (shown == section) {
                PwnActionFlash(status)
                PwnFailureFlash(failure) { failure = null }
            }
        }
    }
    PwnPageHost(
        sheet = inSection,
        onClose = { section = ProfileSection.MAIN },
        modifier = modifier,
        backdrop = { profilePage(Modifier, ProfileSection.MAIN) },
    ) { pageBase ->
        profilePage(pageBase, section)
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    PwnCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = PwnMuted)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = PwnMuted,
            )
        }
    }
}

/**
 * One piece of advice, with the window it was computed over and the body that published the number.
 * Both are on screen on purpose: a threshold you cannot check is just an app being bossy.
 *
 * The topic is named above the title because the title on its own is a single word — "Rarely",
 * "Loose", "Often" — and a word standing over a paragraph tells the person nothing about what the
 * app has decided to bring up. It is drawn in the quiet label the window line below already uses:
 * the subject, not a second heading.
 *
 * Under it, the level in words, in the level's colour. Colour alone is not enough: for some topics
 * "raise it with a clinician" and "keep an eye on
 * it" are built from the same title and body, so a reader who cannot tell the hues apart, or is
 * listening rather than looking, would be given no difference at all.
 *
 * The eye beside it switches that whole topic off everywhere, including the notifications; the way
 * back is the list of switched-off topics in the health section. Advice about a bowel or a bedroom
 * is not something to have to explain to whoever is sitting beside you when the page is opened, and
 * the way out has to be on the card itself rather than on a settings page found afterwards.
 * [AdviceLog.hide] is written here rather than by each screen so that both pages drawing this card
 * get the escape; [onHide] only tells the page to ask for the list again, so a caller with nothing
 * to recompute may leave it out.
 */
@Composable
internal fun AdviceCard(advice: Advice, onHide: (AdviceTopic) -> Unit = {}) {
    val context = LocalContext.current
    PwnCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Image(
                themePainterResource(R.drawable.ic_art_hint),
                contentDescription = null,
                modifier = Modifier.size(46.dp),
            )
            // The fill blue is too light to print on a card in daylight; the ink blue is its text weight.
            val levelColor = when (advice.level) {
                AdviceLevel.CLINICIAN -> PwnWarn
                AdviceLevel.ATTENTION -> PwnBlueInk
                AdviceLevel.HABIT -> PwnIndigo
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(advice.topic.titleRes),
                    color = PwnMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    stringResource(adviceLevelLabel(advice.level)),
                    color = levelColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(advice.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = levelColor,
                )
                Text(
                    LocalResources.current.formatAdviceBody(advice),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    LocalResources.current.formatAdviceWindow(advice),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = {
                    AdviceLog.hide(context, advice.topic)
                    onHide(advice.topic)
                },
                colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
            ) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    stringResource(R.string.advice_hide),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** The level of a piece of advice in words, for the line under its topic. */
@StringRes
private fun adviceLevelLabel(level: AdviceLevel): Int = when (level) {
    AdviceLevel.CLINICIAN -> R.string.m3_profile_advice_level_clinician
    AdviceLevel.ATTENTION -> R.string.m3_profile_advice_level_attention
    AdviceLevel.HABIT -> R.string.m3_profile_advice_level_habit
}

/**
 * The topics that were switched off, and the one tap that brings each of them back.
 *
 * Hiding a topic is a single tap on the card itself, which is what makes it easy to do by accident
 * — and without this card the only way back would be to clear the app's data, which is to say
 * to throw the journal away. So the list lives here, under the advice, rather than on a settings
 * page of its own: the section that stopped saying something is the section a person looks in to
 * ask it to start again. The sentence above the rows says how far "off" reaches, because a topic
 * switched off here is also missing from the home screen and from the notifications, and none of
 * that is visible from a page where nothing is being shown.
 *
 * The card is drawn only when [topics] has something in it, so the section is unchanged for the
 * many people who have never hidden anything. [topics] is what the profile in front of the page may
 * be shown and not simply what the stored set holds: the age gate is read by the caller, which has
 * the profile, since a title printed here is the subject itself.
 */
@Composable
private fun HiddenTopicsCard(topics: List<AdviceTopic>, onShow: (AdviceTopic) -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HelpTitle(stringResource(R.string.advice_hidden_title)) {
                Text(stringResource(R.string.advice_hidden_note))
            }
            topics.forEach { topic ->
                val name = stringResource(topic.titleRes)
                // Every button on this card says the same two words, so a screen reader would read
                // the subject and then a label that could belong to any row. The button carries the
                // subject in its description instead; the printed word stays short. Added to the
                // button's semantics rather than replacing them: replacing them would keep the words but
                // throw away the button's role and its click.
                val spoken = stringResource(R.string.advice_hidden_show_topic, name)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(name, Modifier.weight(1f))
                    TextButton(
                        onClick = { onShow(topic) },
                        modifier = Modifier.semantics { contentDescription = spoken },
                    ) {
                        Text(stringResource(R.string.advice_hidden_show))
                    }
                }
            }
        }
    }
}

/**
 * What the engine found between two things the person writes down, which is usually nothing.
 *
 * "Nothing stood out" is drawn as an answer rather than as an empty state, because it is the
 * expected one: fewer than half of people have a detectable day-to-day pattern at all. And the
 * count of what was compared is printed whatever the outcome — testing several things and showing
 * only the winner is how a tracker manufactures a discovery, and saying how many were asked is the
 * honest half of correcting for it.
 */
@Composable
private fun InsightsCard(report: InsightReport) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = stringResource(R.string.insights_title)
            if (report.bestPairedDays < INSIGHT_GATES.minPairedDays) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                // A counter, not a verdict: below the floor there is nothing to say and the honest
                // thing is to show how far off it is.
                Text(
                    stringResource(
                        R.string.insights_counting,
                        pluralStringResource(
                            R.plurals.count_days,
                            report.bestPairedDays,
                            report.bestPairedDays,
                        ),
                        pluralStringResource(R.plurals.count_days, report.windowDays, report.windowDays),
                        pluralStringResource(
                            R.plurals.count_days,
                            INSIGHT_GATES.minPairedDays,
                            INSIGHT_GATES.minPairedDays,
                        ),
                    ),
                    color = PwnMuted,
                )
                return@Column
            }
            // How many comparisons were run over how many days, and that none of it is a finding
            // about the person: read once, behind the title, not under it on every visit.
            HelpTitle(title) {
                Text(
                    stringResource(
                        R.string.insights_disclosure,
                        pluralStringResource(R.plurals.count_comparisons, report.tested, report.tested),
                        pluralStringResource(R.plurals.count_days, report.windowDays, report.windowDays),
                    ),
                )
            }
            if (report.findings.isEmpty()) {
                Text(stringResource(R.string.insights_none), color = PwnMuted)
            }
        }
    }
    report.findings.forEach { AdviceCard(it) {} }
}

/**
 * What the app can honestly say about sleep: how long, how regular, how far short.
 *
 * No stages and no score built on invented ones — see [sleepSummary]. The 0-100 figure is duration
 * against the target and bedtime regularity, and the card says which two things it is made of
 * rather than presenting it as a reading off an instrument.
 */
@Composable
private fun SleepSummaryCard(summary: SleepSummary) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.health_sleep_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.health_sleep_nights,
                    pluralStringResource(R.plurals.count_nights, summary.nights, summary.nights),
                    formatSleepClock(summary.averageMinutes),
                ),
                color = PwnMuted,
            )
            Text(
                stringResource(
                    R.string.health_sleep_spread,
                    pluralStringResource(
                        R.plurals.count_minutes,
                        summary.bedtimeSpreadMinutes,
                        summary.bedtimeSpreadMinutes,
                    ),
                ),
                color = PwnMuted,
            )
            if (summary.debtMinutes > 0) {
                Text(
                    stringResource(R.string.health_sleep_debt, formatSleepClock(summary.debtMinutes)),
                    color = PwnMuted,
                )
            }
            // As text rather than as an integer: it is a score out of a hundred, not a count of
            // anything, so it wants the locale's own digits and not a plural form.
            Text(
                stringResource(R.string.health_sleep_score, summary.score.toLong().formatted()),
                color = PwnMuted,
            )
        }
    }
}

/** The person's own numbers — the denominator the advice above is written against. */
@Composable
private fun BaselineCard(baseline: Baseline) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HelpTitle(stringResource(R.string.health_baseline_title)) {
                Text(stringResource(R.string.health_baseline_note))
            }
            baseline.meanStepsPerDay?.let {
                Text(stringResource(R.string.health_baseline_steps, it.formatted()), color = PwnMuted)
            }
            baseline.meanSleepMinutes?.let { minutes ->
                Text(
                    stringResource(
                        R.string.health_baseline_sleep,
                        formatSleepClock(minutes),
                        pluralStringResource(
                            R.plurals.count_minutes,
                            baseline.sleepSpreadMinutes ?: 0,
                            baseline.sleepSpreadMinutes ?: 0,
                        ),
                    ),
                    color = PwnMuted,
                )
            }
            baseline.meanWaterMl?.let {
                Text(stringResource(R.string.health_baseline_water, it), color = PwnMuted)
            }
            baseline.meanFatG?.let {
                Text(stringResource(R.string.health_baseline_fat, it.shown()), color = PwnMuted)
            }
            baseline.meanMoodScore?.let {
                Text(stringResource(R.string.health_baseline_mood, it.shown()), color = PwnMuted)
            }
            baseline.usualCycleDays?.let { usual ->
                Text(
                    stringResource(
                        R.string.health_baseline_cycle,
                        pluralStringResource(R.plurals.count_days, usual, usual),
                        pluralStringResource(
                            R.plurals.count_days,
                            baseline.cycleSpreadDays ?: 0,
                            baseline.cycleSpreadDays ?: 0,
                        ),
                    ),
                    color = PwnMuted,
                )
            }
        }
    }
}

/**
 * The allergy answer, where it can be changed.
 *
 * The same three answers the onboarding page offers, in the shape a settings card takes: the two
 * that are not a list as a pair of chips, and the fourteen underneath. Unticking the last one
 * leaves the question unanswered, which is the state it starts in.
 */
@Composable
private fun AllergyCard(answer: AllergyAnswer, onAnswer: (AllergyAnswer) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.allergy_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.allergy_profile_body),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                // Hidden once an allergen is named, for the reason the onboarding page gives: the
                // two of them answer a question that has been answered another way, and choosing
                // one would drop the names without saying so.
                if (answer.allergens().isEmpty()) {
                    PwnSegmentedControl(
                        options = listOf(
                            stringResource(R.string.allergy_not_tested) to null,
                            stringResource(R.string.allergy_none_known) to null,
                        ),
                        selected = when (answer) {
                            AllergyAnswer.NotTested -> 0
                            AllergyAnswer.NoneKnown -> 1
                            else -> -1
                        },
                        onSelected = { onAnswer(if (it == 0) AllergyAnswer.NotTested else AllergyAnswer.NoneKnown) },
                    )
                }
            }
            AllergyPickerRow(answer) { picking = true }
        }
    }
    if (picking) {
        AllergyPickerSheet(answer = answer, onAnswer = onAnswer, onDismiss = { picking = false })
    }
}

/**
 * How strongly a walked day is shaded, from its share of the goal. The floor is what keeps the
 * faintest walked day apart from an empty one: it clears the 3:1 a meaningful graphic owes against
 * both the card and an empty cell in either palette, which YearGridContrastTest holds it to. The
 * rest of the range still darkens toward the goal, as the legend under the grid says.
 */
internal fun yearCellAlpha(share: Float): Float = YEAR_CELL_FLOOR + (1f - YEAR_CELL_FLOOR) * share

private const val YEAR_CELL_FLOOR = 0.7f

/** A year of days as one screen: the shape of the habit, not the numbers. */
@Composable
private fun YearGrid(days: List<DaySteps>, goal: Long) {
    val today = rememberToday()
    val byDay = remember(days) { days.associateBy(DaySteps::day) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HelpTitle(stringResource(R.string.year_title)) { Text(stringResource(R.string.year_legend)) }
            // Read in composition: the draw lambda cannot ask the theme itself. An empty day is a
            // dim theme cell and a walked day is the accent ramp — in dark theme the raw pastels
            // would invert this, rendering stepless days BRIGHTER than walked ones.
            val emptyCell = MaterialTheme.colorScheme.surfaceVariant
            val walkedCell = MaterialTheme.colorScheme.primary
            // 53 columns of 7 days, drawn as one canvas: 371 composables would cost more than the
            // whole rest of this screen.
            val columns = 53
            // To a screen reader a canvas is a blank rectangle, so the year is counted out in
            // words over exactly the window the squares are drawn from. Two numbers, because the
            // squares are a picture of two numbers: the days walked at all, and the days that
            // reached the goal — the shade of each cell is the second one.
            val start = remember(today) { today.minusDays(columns * 7L - 1) }
            val firstDay = start.toString()
            val walked = remember(days, firstDay) { days.count { it.day >= firstDay && it.steps > 0 } }
            val reached = remember(days, firstDay, goal) {
                if (goal <= 0L) 0 else days.count { it.day >= firstDay && it.steps >= goal }
            }
            val description = stringResource(
                R.string.year_grid_description,
                pluralStringResource(R.plurals.count_days, walked, walked),
                reached,
            )
            Canvas(
                Modifier.fillMaxWidth()
                    .height(96.dp)
                    .semantics { contentDescription = description },
            ) {
                val cell = size.width / columns
                val side = (cell - 2f).coerceAtLeast(1f)
                repeat(columns * 7) { index ->
                    val date = start.plusDays(index.toLong())
                    if (date.isAfter(today)) return@repeat
                    val steps = byDay[date.toString()]?.steps ?: 0L
                    val share = if (goal <= 0L) 0f else (steps.toFloat() / goal).coerceIn(0f, 1f)
                    drawRect(
                        color = if (share <= 0f) {
                            emptyCell
                        } else {
                            walkedCell.copy(alpha = yearCellAlpha(share))
                        },
                        topLeft = Offset((index / 7) * cell, (index % 7) * (size.height / 7f)),
                        size = Size(side, (size.height / 7f) - 2f),
                    )
                }
            }
        }
    }
}

/**
 * The five rungs of this year's climb, under the total they are counted from.
 *
 * Five medals across the width of a phone leave room for the picture and its label and nothing
 * else — and the date is the part of a medal worth reading, so it stands where there is room for
 * it: the medal opens.
 */
@Composable
private fun AchievementSection(badges: List<Achievement>, subtitle: String) {
    var opened by remember { mutableStateOf<Achievement?>(null) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.achievement_year_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(subtitle, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                badges.forEach { badge ->
                    AchievementBadge(badge, Modifier.weight(1f)) { opened = badge }
                }
            }
        }
    }
    opened?.let { medal -> MedalDialog(medal) { opened = null } }
}

@Composable
private fun AchievementBadge(achievement: Achievement, modifier: Modifier, onOpen: () -> Unit) {
    val label = badgeLabel(achievement.threshold)
    val percent = (achievement.progress * 100).roundToInt()
    val description = stringResource(R.string.achievement_year_value, label)
    Column(
        modifier
            .clickable(role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = if (achievement.earned) description else "$description · $percent%"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Medal(medalArt(achievement.threshold), achievement.earned, Modifier.size(48.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (achievement.earned) MaterialTheme.colorScheme.onSurface else PwnMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            // Five medals share the width of a phone, and at a large font "500k" does not fit a
            // fifth of it: the label shrinks to fit rather than losing its "k" to the edge.
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp),
        )
    }
}

/**
 * The tapped medal at a size worth looking at, and the day it was won.
 *
 * A locked one says so in words and gives the distance left as a percentage rather than a bar: the
 * first rung is a fortnight of walking and the last one is years, so a bar sitting at 4% for months
 * would read as something broken instead of as a climb that has barely started.
 *
 * The cross closes it and there is no button beside it to agree with: a medal being looked at is not
 * a question. The one thing there is to do with it is send it, and that sits in the middle, under
 * the date it is stamped with — only on a medal that has a date. A grey disc reading "4%" is not
 * something anybody meant to post, and offering to share one would be the app congratulating a walk
 * that has not happened.
 */
@Composable
private fun MedalDialog(achievement: Achievement, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val art = medalArt(achievement.threshold)
    val earnedOn = achievement.earnedOnDay
    // A second tap while the first card was still being drawn would write the same file under it and put
    // a second chooser over the first: held off for a duplicate tap's window, as the walk camera is.
    var sharedAt by remember { mutableLongStateOf(0L) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDismiss, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.common_close))
                }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Medal(art, achievement.earned, Modifier.size(120.dp))
                Text(
                    stringResource(R.string.achievement_year_value, achievement.threshold.formatted()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                // The day is what decides which line this is, not the flag beside it: a medal the
                // step rows can no longer date has nothing to put after "Reached", and nothing to
                // stamp a shared picture with either.
                if (earnedOn != null) {
                    Text(
                        stringResource(R.string.achievement_earned_value, earnedOn),
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (!isDuplicateTap(sharedAt.takeIf { it > 0L }, now)) {
                                sharedAt = now
                                scope.launch { shareMedal(context, achievement.threshold, art, earnedOn) }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Share, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.achievement_share))
                    }
                } else {
                    Text(
                        stringResource(R.string.achievement_locked),
                        color = PwnMuted,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(
                            R.string.achievement_progress_value,
                            (achievement.progress * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

/**
 * One rung of the climb as its own disc: in colour once it is won, grey until then.
 *
 * The five escalate by shape rather than by metal — a single star over a sprig of laurel at the
 * first rung, five stars inside a closed wreath at the last — because bronze to gold collapses
 * into five identical discs precisely in the greyed-out state most of them are in the first time
 * anybody looks at this card.
 *
 * The art carries its own light and cannot be tinted, so an unearned medal is desaturated instead:
 * the matrix keeps the shape and takes the colour away, and the alpha sets it back from the earned
 * ones beside it.
 */
@Composable
private fun Medal(art: Int, earned: Boolean, modifier: Modifier) {
    val drained = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Image(
        themePainterResource(art),
        contentDescription = null,
        modifier = modifier,
        alpha = if (earned) 1f else 0.45f,
        colorFilter = if (earned) null else drained,
    )
}

/**
 * Which disc a rung wears, chosen by the steps it stands for.
 *
 * Every face is stamped with its own figure, so the drawing has to follow the threshold and not
 * the badge's place in the row: shuffle the list and a medal reading "1M" under the label "3M" is
 * the kind of wrongness a picture cannot argue its way out of.
 *
 * [STEP_MEDALS] is data, and data gets longer. A rung this build has no disc drawn for keeps the
 * plain cup, so a ladder extended in Achievements.kt alone leaves the row whole rather than empty
 * in one place.
 */
private fun medalArt(threshold: Long): Int = when (threshold) {
    100_000L -> R.drawable.ic_art_medal_100k
    500_000L -> R.drawable.ic_art_medal_500k
    1_000_000L -> R.drawable.ic_art_medal_1m
    3_000_000L -> R.drawable.ic_art_medal_3m
    5_000_000L -> R.drawable.ic_art_medal_5m
    else -> R.drawable.ic_art_trophy
}

/** The door row's inset, drawing and gap, which together also start its divider where its text starts. */
private val MENU_ROW_INSET = 16.dp
private val MENU_ROW_GLYPH = 46.dp
private val MENU_ROW_GAP = 13.dp

/** A door in a list of doors: what is behind it, a line or two about it, and the chevron that says so. */
@Composable
internal fun ProfileMenuRow(
    art: Int?,
    icon: ImageVector,
    background: Color,
    title: String,
    subtitle: String?,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = MENU_ROW_INSET, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MENU_ROW_GAP),
        ) {
            PwnGlyph(art, icon, background, null, Modifier.size(MENU_ROW_GLYPH))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                // A row with nothing to add under its name keeps its name on the middle line
                // rather than reserving an empty one under it. Two lines, and an ellipsis where the
                // rest is cut: one line would drop the end of a translated subtitle without a sign.
                subtitle?.let {
                    Text(
                        it,
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Outlined.NavigateNext, null, tint = PwnMuted)
        }
        if (divider) {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(start = MENU_ROW_INSET + MENU_ROW_GLYPH + MENU_ROW_GAP),
                color = PwnBorder,
            )
        }
    }
}

/**
 * A section's name, marked as the page's heading, and — where [onBack] is given — the arrow back.
 * The profile's sections hide the bottom bar, and without an arrow their only ways out would be the
 * system back and a swipe nobody is told about. The arrow goes through the same back handler, so
 * the panel still plays its exit.
 */
@Composable
internal fun ProfileSectionHeader(
    title: String,
    art: Int? = null,
    onBack: (() -> Unit)? = null,
    help: String? = null,
    helpTitle: String? = null,
) {
    // The screen says what it is the way the home screen's rows do: the drawing first, the name
    // beside it. Screens the set has no drawing for keep the bare title rather than borrowing one.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        onBack?.let {
            IconButton(onClick = it, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.common_back),
                )
            }
        }
        art?.let {
            Image(themePainterResource(it), contentDescription = null, modifier = Modifier.size(46.dp))
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            help?.let { HelpDot(helpTitle ?: title) { Text(it) } }
        }
    }
}

/** What the import pickers accept: the JSON this app writes, and the protected file it writes too. */
private val BACKUP_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

/** A protected backup that was picked and staged, waiting for the password that opens it. */
private class LockedImport(val staged: File, val profileOnly: Boolean)

private sealed interface ImportOutcome {
    data class Merged(val summary: BackupSummary) : ImportOutcome

    data class Profile(val found: Boolean) : ImportOutcome
}

/** "today", or "N days ago" in the medicine chart's words — the same phrase, and the same quantity. */
@Composable
private fun backupAgeWords(ageDays: Long): String = if (ageDays == 0L) {
    stringResource(R.string.backup_today)
} else {
    pluralStringResource(R.plurals.med_chart_days_ago, ageDays.toInt(), ageDays.toInt())
}

/** The one line the front page and the data section share, so the two never say different things. */
@Composable
private fun backupAgeText(ageDays: Long?): String = if (ageDays == null) {
    stringResource(R.string.backup_never)
} else {
    stringResource(R.string.backup_last, backupAgeWords(ageDays))
}

/**
 * The daily copy's switch, with what it does written under it. Off, the line says what the copy
 * would be — plain, since no password is kept to seal it with — because that is the moment it
 * matters. On, it names the folder and says how the last run went, since a worker that fails at
 * three in the morning has no other way to be heard.
 */
@Composable
private fun AutoBackupCard(state: AutoBackupState, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    PwnCard(Modifier.fillMaxWidth()) {
        // Toggleable on the row rather than PwnSwitchRow: the lines under the title are produced
        // as the provider answers and there can be three of them, which one body string cannot hold.
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    value = state.folder != null,
                    role = Role.Switch,
                    onValueChange = onToggle,
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.backup_auto_title), style = MaterialTheme.typography.titleMedium)
                val tree = state.folder
                if (tree == null) {
                    Text(
                        stringResource(R.string.backup_auto_off_note),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    // The provider is asked off the main thread; until it answers the line
                    // holds the folder's own id, which is at least where the folder is.
                    val folder by produceState(tree.lastPathSegment?.substringAfterLast(':').orEmpty(), tree) {
                        value = withContext(Dispatchers.IO) { AutoBackup.folderName(context, tree) }
                    }
                    Text(
                        stringResource(R.string.backup_auto_on_note, folder),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val lastCopy = state.lastCopyMs
                    Text(
                        when {
                            state.error != null -> stringResource(R.string.backup_auto_failed, state.error)

                            lastCopy != null -> stringResource(
                                R.string.backup_auto_last,
                                backupAgeWords(backupAgeDays(lastCopy, LocalDate.now(), ZoneId.systemDefault()) ?: 0L),
                            )

                            else -> stringResource(R.string.backup_auto_pending)
                        },
                        color = if (state.error != null) PwnWarn else PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            PwnSwitch(checked = state.folder != null, onCheckedChange = null)
        }
    }
}

/**
 * Ends the process so that the next one can convert the file. The activity is finished first, so
 * the task is gone and the system does not bring it straight back over a dead process; then the
 * exit, because a finished activity alone leaves the step service holding the process — and with it
 * the open database the conversion must not meet. The service comes back on its own, START_STICKY,
 * and the conversion runs under [StepsDb.get]'s lock before it reads a row.
 *
 * Nothing is opened again, and the button says so: "Restart now" over an app that only goes away
 * would look like a crash at the very moment encryption was switched on. Starting the app from a
 * process about to die is a race the system does not promise to lose kindly — a window launched
 * into the dying process is removed with it — so the words say what happens rather than the
 * restart being attempted.
 */
internal fun closeForDatabaseChange(activity: Activity) {
    activity.finishAffinity()
    exitProcess(0)
}

/**
 * A database waiting beside the one in use ([SetAside]): what it is, and the two things that can be done
 * with it — the kind's own, and letting it go. Forgetting deletes the only copy of a record, so the button
 * asks to be pressed twice — the first press changes what it says, the second does it — inline, in the
 * card, rather than as a dialog over it: a sub-step of an open form stays in the form's own scroll.
 *
 * Without [onForget] the card offers the kind's own answer alone. That is how it stands on the first
 * page of onboarding ([OnboardingScreen]), where the app has just started on an empty database and
 * the record waiting beside it is the one thing its owner came for: deleting it is not offered there.
 * Without [onAction] either it only says what is waiting, as it does there of a record whose answer
 * — an import, a copy saved out — is the profile's. [actionEnabled] false greys the kind's own answer
 * out while it cannot be taken yet.
 */
@Composable
internal fun SetAsideCard(
    kind: SetAside,
    onAction: (() -> Unit)?,
    onForget: (() -> Unit)?,
    actionEnabled: Boolean = true,
) {
    // Keyed by the kind: the card that follows a forgotten copy is another record's, and starts unarmed.
    var forgetArmed by remember(kind) { mutableStateOf(false) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(themePainterResource(R.drawable.ic_art_lock), null, Modifier.size(54.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(kind.title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(kind.body),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Armed, the next press deletes the only copy, so it turns the error colour the
                // delete confirmation's own button wears as well as changing its words.
                val danger = MaterialTheme.colorScheme.error
                if (onForget != null) {
                    OutlinedButton(
                        onClick = { if (forgetArmed) onForget() else forgetArmed = true },
                        modifier = Modifier.weight(1f),
                        colors = if (forgetArmed) {
                            ButtonDefaults.outlinedButtonColors(contentColor = danger)
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        border = if (forgetArmed) {
                            BorderStroke(1.dp, danger)
                        } else {
                            ButtonDefaults.outlinedButtonBorder()
                        },
                    ) {
                        Text(
                            stringResource(
                                if (forgetArmed) R.string.db_locked_forget_sure else R.string.db_locked_forget,
                            ),
                        )
                    }
                }
                if (onAction != null) {
                    Button(onClick = onAction, modifier = Modifier.weight(1f), enabled = actionEnabled) {
                        Text(stringResource(kind.action))
                    }
                }
            }
        }
    }
}

/** The document copied into the cache, where a transaction can read it without the network. */
private fun stageDocument(context: Context, uri: Uri): File {
    val staged = File.createTempFile("backup", ".bak", context.cacheDir)
    // A provider that will not open, or a copy that breaks off, leaves no half-file in the cache. One
    // that answers with no stream at all is said in words: a bare NullPointerException would carry none.
    runCatching {
        val document = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException(context.getString(R.string.file_import_unopenable))
        document.use { input -> staged.outputStream().use(input::copyTo) }
    }.onFailure { staged.delete() }.getOrThrow()
    return staged
}

/** Whether the staged file begins the way a protected export does; a JSON file begins with `{`. */
private fun File.isEncryptedBackup(): Boolean = inputStream().use { input ->
    val head = ByteArray(BackupCrypto.MAGIC_LENGTH)
    input.read(head) == head.size && BackupCrypto.isEncrypted(head)
}

/**
 * Whether a backup is being imported — the process's word, not one composition's. The import is not
 * cancellable, so it outlives the screen that began it, and the screen composed after a rotation or
 * a visit to another tab has to find the doors still shut.
 */
internal val backupImportRunning = MutableStateFlow(false)

/**
 * Whether a backup is being exported, for the doors that end the process ([closeForDatabaseChange]): a
 * document cut off halfway is a backup that is not one, and nothing would say so. Counted, as two
 * exports can overlap and the first to end must not open the doors under the second.
 */
private val backupExportRunning = MutableStateFlow(false)
private val backupExportsInFlight = InFlightFlag { backupExportRunning.value = it }

private inline fun <T> whileExporting(block: () -> T): T {
    backupExportsInFlight.move(1)
    return try {
        block()
    } finally {
        backupExportsInFlight.move(-1)
    }
}

/**
 * Runs [block] as the only backup import under way, or not at all while another one is.
 *
 * Two imports do not simply queue behind Room's transaction. The first one's photographs take
 * their names after its rows have committed, outside the transaction, and the second begins by
 * sweeping every `.part` out of the folders — the first one's parked pictures — and parks the same
 * ones again, their rows not being written yet. Each then reports photographs lost that the other
 * restored, and a picture named in between gets two rows.
 *
 * Refused, not queued: the second tap on "Decrypt and import" would otherwise run on a staged file
 * the first has deleted, and report that as the import having failed. Every call of
 * [LocalBackup.import] goes through [importStaged], and every call of that through here.
 */
internal suspend fun asOnlyBackupImport(block: suspend () -> Unit) {
    if (!backupImportRunning.compareAndSet(expect = false, update = true)) return
    try {
        block()
    } finally {
        backupImportRunning.value = false
    }
}

/**
 * An export's or an import's last word, held for the page rather than written into it. Neither can be
 * called off once begun, so either outlives the page that began it: turned on its side halfway through
 * a year of photographs, the page is composed anew, and a summary written into the old one's state
 * would be shown to nobody — the doors opening again with nothing said. The page standing when it
 * lands says it, and takes it down.
 */
private val backupReport = MutableStateFlow<BackupReport?>(null)

/** One line for [backupReport]. Not a data class: the same words twice are two reports, said twice. */
private class BackupReport(val text: String, val failed: Boolean)

/**
 * Whether a password that did not open the staged file is said on its field, for another try. Only
 * while the file is still there: the page that held the file deletes it as it goes, and a wrong
 * password kept for a field nobody will see again would be the import ending in silence.
 */
internal fun keepsLockedImport(failure: Throwable?, stagedStillThere: Boolean): Boolean =
    failure is WrongBackupPasswordException && stagedStillThere

/**
 * The staged file, plain or opened with the password, run through whichever import was asked for.
 * A wrong password fails on the first chunk — before the transaction has written a row. A profile
 * restored is followed at once ([followProfile]): it lands after the resume its picker came back
 * through, and the password's door opens no picker at all.
 */
private suspend fun importStaged(
    context: Context,
    db: StepsDb,
    staged: File,
    password: CharArray?,
    profileOnly: Boolean,
): ImportOutcome = withContext(Dispatchers.IO) {
    // The file held from the start: a header that will not read throws before the decrypting stream
    // exists to close it.
    staged.inputStream().use { raw ->
        val input = if (password == null) raw else BackupCrypto.decrypting(password, raw)
        input.use {
            if (profileOnly) {
                val found = LocalBackup.importProfile(db, it)
                if (found) followProfile(context, db.dao().profileNow())
                ImportOutcome.Profile(found)
            } else {
                ImportOutcome.Merged(
                    LocalBackup.import(
                        db,
                        it,
                        progressPhotoDir(context),
                        medicinePhotoDir(context),
                        walkPhotoDir(context),
                        installId(context),
                        routinePhotoDir(context),
                        labCaptureDir(context),
                    ),
                )
            }
        }
    }
}

/**
 * Whether the repeated password has already gone its own way. A repeat that is still the start of
 * the password is one being typed, not a mistake, so it is not marked until it differs.
 */
internal fun passwordsDiverge(password: String, repeat: String): Boolean =
    repeat.isNotEmpty() && !password.startsWith(repeat)

/**
 * A password field with the text field's own error line, a way to see what was typed, and the
 * keyboard's action key going on to the next field or finishing. Showing what was typed is never
 * remembered: the choice goes with the field.
 */
@Composable
internal fun PasswordField(
    value: String,
    onValue: (String) -> Unit,
    @StringRes label: Int,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Done,
) {
    var visible by remember { mutableStateOf(false) }
    val text = rememberSyncedText(value, onValue)
    OutlinedSecureTextField(
        state = text.state,
        inputTransformation = text.handOff,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = error?.let { { Text(it, Modifier.politeLiveRegion()) } },
        trailingIcon = {
            IconButton(onClick = {
                visible = !visible
            }, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    stringResource(
                        if (visible) R.string.m3_profile_password_hide else R.string.m3_profile_password_show,
                    ),
                )
            }
        },
        // Hidden rather than the default, which follows the phone's show-passwords setting and so
        // can show each character for a moment as it is typed: this field never shows one.
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
    )
}

/**
 * The password asked for where the file was picked, in the same column — not a dialog over it.
 * While an import runs ([busy]) neither button answers: it cannot be called off once begun, and
 * Cancel would delete the file under it and take this card away with the import still writing.
 */
@Composable
private fun LockedImportCard(
    password: String,
    rejected: Boolean,
    onPassword: (String) -> Unit,
    onUnlock: () -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.profile_import_locked))
            PasswordField(
                password,
                onPassword,
                R.string.profile_password,
                error = if (rejected) stringResource(R.string.profile_import_wrong_password) else null,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(onClick = onUnlock, enabled = password.isNotBlank() && !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_import_unlock))
                }
            }
        }
    }
}
