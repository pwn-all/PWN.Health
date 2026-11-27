package com.pwnall.health

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The medicine cabinet, in five numbered blocks: what to take today, how the fortnight went, the
 * courses behind it, the appointments in the diary, and the routine examinations that fall due with
 * age.
 *
 * Everything here is something the person typed in — a prescription this app never wrote and cannot
 * check. It keeps the schedule, marks what was taken, and says when an interval has run out. It
 * decides nothing, and the card at the bottom says so where it cannot be missed.
 *
 * The main page stays a summary: every row that has more to it than a line of text opens its own
 * page — a course, an appointment, an examination — because a screen that carries three forms at
 * once is a screen nobody can find anything on. Those pages are internal state rather than app
 * screens, the same way the profile's sections are: the back gesture returns to the summary before
 * the bottom bar ever sees it.
 *
 * A dose that has been marked is an ordinary journal row, which is why it appears in the day list,
 * the calendar and the backup without any of them being taught about medicine.
 */
@Composable
fun MedicineScreen(
    modifier: Modifier,
    profile: UserProfile,
    openVisitId: Long? = null,
    onVisitOpened: () -> Unit = {},
    /** A visit the symptom chat suggested: the form opens on it, to be dated and saved by the person. */
    bookVisit: VisitDraft? = null,
    onBookOpened: () -> Unit = {},
    /** A course a chat's card opens, by its `createdAt`: its page, as if it had been tapped in the list. */
    openCourseAt: Long? = null,
    onCourseOpened: () -> Unit = {},
    onReport: () -> Unit = {},
) {
    val context = LocalContext.current
    val db = remember { StepsDb.get(context) }
    val dao = remember { db.dao() }
    val scope = rememberCoroutineScope()
    val today = rememberToday()
    // The clock as well as the date: the late badges and the upcoming/past-due split are drawn from
    // the minute, not from the day.
    var minuteTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            minuteTick++
        }
    }
    // Read again with the minute: kept from the first frame, a time zone changed while the tab stands
    // open would leave the day's doses and visits placed in the old one, with the minute read in the new.
    val zone = remember(minuteTick) { ZoneId.systemDefault() }
    val nowMinute = remember(minuteTick) { LocalTime.now().let { it.hour * 60 + it.minute } }
    val nowMs = remember(minuteTick) { System.currentTimeMillis() }
    // Every write on this screen goes through here. Two things it settles: a tab switch cancels this
    // composition, and a save the person watched succeed still has to reach the database; and the
    // button that started it only changes once the database flow comes back, which is long enough
    // for a second tap to write a second row — the guard Data.kt's one-tap loggers already carry.
    val tappedAt = remember { mutableMapOf<String, Long>() }
    fun write(key: String, block: suspend () -> Unit) {
        val at = System.currentTimeMillis()
        if (isDuplicateTap(tappedAt[key], at)) return
        tappedAt[key] = at
        scope.launch {
            withContext(NonCancellable) {
                block()
                // Stamped again when the write lands, not only when the tap arrived. The button is
                // drawn from the database flow, so what a second tap has to be measured against is
                // the moment the screen could have answered the first — not the moment it was made.
                tappedAt[key] = System.currentTimeMillis()
            }
        }
    }

    // Marking a dose is guarded per dose rather than per screen, so unmarking one can hand its own
    // guard back — see onUndo.
    fun takeKey(dose: PlannedDose) = "take${dose.medicationId}/${dose.slot}"
    val medications by remember { dao.medications() }.collectAsStateWithLifecycle(emptyList())
    val appointments by remember { dao.appointments() }.collectAsStateWithLifecycle(emptyList())
    val history by remember { dao.medicalHistory() }.collectAsStateWithLifecycle(emptyList())
    // The chart's window is the only history this screen needs; the courses carry their own dates.
    val journal by remember(today) {
        dao.journalBetween(today.minusDays(MED_CHART_DAYS - 1L).toString(), today.toString())
    }.collectAsStateWithLifecycle(emptyList())

    // `today` is a key on each of these: the rows it reads over are the same ones either side of
    // midnight, so without it the day list would still be yesterday's plan until something changed.
    val wakeMinute = remember(journal, today, zone) { wakeMinuteOf(journal, today, zone) }
    val doses = remember(medications, journal, wakeMinute, today) {
        plannedDoses(medications, journal, today, wakeMinute)
    }
    // The minute is a key as well: today's bar counts a dose from the moment its time comes, so the
    // evening tablet joins the score at eight rather than lowering it at breakfast.
    val adherence = remember(medications, journal, today, nowMinute) {
        medAdherence(medications, journal, today, nowMinute = nowMinute, zone = zone)
    }
    val statuses = remember(appointments, profile, today, zone) {
        checkupStatuses(
            checkupsFor(profile.sex, ageOn(today.toString(), profile.birthDate)),
            appointments,
            today,
            zone,
        )
    }

    val course = rememberCourseForm(today)
    // Here rather than on the course's page: the page is gone by the time the settings screen answers.
    val askAlarmAccess = rememberAlarmAccessRequest()
    val visit = rememberVisitForm(today)
    val record = rememberHistoryForm()
    var page by rememberSaveable(stateSaver = PAGE_SAVER) { mutableStateOf(MedPage.MAIN) }
    // The blood tests beside a saved course whose label says what it holds. The result before a
    // course is often years before it, so the whole record is read — once, off the main thread, and
    // only while that course's page is open, rather than kept in the screen's fourteen-day window.
    val described = medications.firstOrNull { it.id == course.editingId }
        ?.takeIf { page == MedPage.COURSE && contentsOf(it.contents).isNotEmpty() }
    val bloodTests by produceState(emptyList<SupplementResponse>(), described, medications, profile, today, zone) {
        value = described?.let { found ->
            withContext(Dispatchers.Default) {
                val whole = dao.allJournal()
                supplementResponses(
                    found,
                    ResponseRecord(medications, labHistory(whole, profile), whole, today, zone = zone),
                    withFood = false,
                )
            }
        }.orEmpty()
    }
    var screening by rememberSaveable(stateSaver = CHECKUP_SAVER) {
        mutableStateOf<Checkup?>(null)
    }
    // The appointment being read, by id rather than by value: the list is a database flow, and a
    // copy captured when the row was tapped would go stale the moment the visit is edited.
    var visitFromHistory by rememberSaveable { mutableStateOf(false) }
    var visitFromScreening by rememberSaveable { mutableStateOf(false) }
    var historyFromScreening by rememberSaveable { mutableStateOf(false) }
    var historySpecialty by rememberSaveable { mutableStateOf<String?>(null) }
    var historyDoctor by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingId by rememberSaveable { mutableLongStateOf(0L) }
    // A course opened from its history goes back there, as a visit opened from its history does.
    var courseFromHistory by rememberSaveable { mutableStateOf(false) }
    // The list keeps the courses still running or yet to start; the ones that have ended are its
    // history, behind the clock in the list's corner.
    val (running, ended) = remember(medications, today) { medications.partition { !it.endedBefore(today) } }
    // Whether the phone lets a dose's notification show, read again on every return to the app: it
    // changes in Android's settings, which answer nobody.
    var notificationsOn by remember { mutableStateOf(MedicineAlarmNotification.shows(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = MedicineAlarmNotification.shows(context)
        onPauseOrDispose { }
    }
    val silencedBy = reminderBlock(running.isNotEmpty(), profile.remindersEnabled, notificationsOn)
    // A dose whose time has come is announced as soon as the phone allows it, not at the next check.
    val openNotificationSettings = rememberNotificationSettings {
        scope.launch { MedicineAlarms.refresh(context, db) }
    }

    // Turned on as the Profile page and the chat turn them on: the profile saved, the worker and the
    // alarms started. Then what the alarm itself needs.
    fun turnRemindersOn() = write("remindersOn") {
        dao.profileNow()?.let { dao.saveProfile(it.copy(remindersEnabled = true)) }
        Reminders.apply(context, true)
        askAlarmAccess()
    }
    // Arriving from the calendar's list of the day: the visit that was tapped, opened as if it had
    // been tapped here. Keyed on the id, so coming back with a different one opens that one.
    LaunchedEffect(openVisitId) {
        if (openVisitId != null && openVisitId > 0L) {
            visitFromHistory = false
            visitFromScreening = false
            viewingId = openVisitId
            page = MedPage.VISIT_VIEW
            // Answered once. Left standing, every later return to this tab would walk back into
            // the same visit instead of the tab's own front page.
            onVisitOpened()
        }
    }
    // Arriving from a symptom chat that called for a doctor: a new visit's form, filled with the
    // specialty and one line of the chat. Nothing is saved here — the date is the person's to set,
    // and the form saves when they say so. Answered once, as the visit above is.
    LaunchedEffect(bookVisit) {
        if (bookVisit != null) {
            visit.show(null, bookVisit.specialty, today)
            visit.note = bookVisit.note.take(MED_NOTE_MAX)
            page = MedPage.VISIT
            onBookOpened()
        }
    }
    // Arriving from a chat's card of a course it added: that course's page. Answered once, as above;
    // a course deleted meanwhile opens nothing.
    LaunchedEffect(openCourseAt) {
        if (openCourseAt != null) {
            dao.allMedications().firstOrNull { it.createdAt == openCourseAt }?.let {
                courseFromHistory = false
                course.show(it, today)
                page = MedPage.COURSE
            }
            onCourseOpened()
        }
    }
    // Which of the two free-text fields the picker page is filling, and what has been typed into it.
    var pickingPlace by rememberSaveable { mutableStateOf(false) }
    var pickQuery by rememberSaveable { mutableStateOf("") }
    // The place field's location button: the picker opens already asking for the nearest places.
    // Cleared once asked, so turning the phone does not ask a second time.
    var pickNear by rememberSaveable { mutableStateOf(false) }
    // One dialog, two ways in: the trash on a list row and the trash in a page's top bar. By id
    // rather than by value for the same reason the visit above is, with a second one behind it: a
    // row cannot be carried through a rotation, and the question has to be asked again afterwards
    // rather than answered by a dialog that vanished mid-thought. Nothing has the id zero.
    var deletingCourseId by rememberSaveable { mutableLongStateOf(0L) }
    var deletingVisitId by rememberSaveable { mutableLongStateOf(0L) }
    var showingAbout by rememberSaveable { mutableStateOf(false) }
    // A course added here while reminders are switched off in the profile: nothing will ring for
    // it, and nothing on this tab said so. The question the chat asks at the same moment.
    var askingReminders by rememberSaveable { mutableStateOf(false) }

    fun home() {
        page = MedPage.MAIN
    }

    // Where leaving a page lands. The picker and the reminder list are steps inside the form, so
    // they go back to it rather than all the way out; the form itself returns to the appointment it
    // was opened from, and everything else to the summary.

    /** Where leaving [from] lands, as a plain answer: the panel's backdrop asks it too. */
    fun backFrom(from: MedPage): MedPage = when (from) {
        MedPage.VISIT_VIEW -> when {
            visitFromHistory -> MedPage.VISIT_HISTORY
            visitFromScreening -> MedPage.SCREENING
            else -> MedPage.MAIN
        }

        MedPage.VISIT_HISTORY -> if (historyFromScreening) MedPage.SCREENING else MedPage.MAIN

        MedPage.HISTORY_FORM -> MedPage.HISTORY

        MedPage.VISIT_FIELD, MedPage.VISIT_REMIND -> MedPage.VISIT

        MedPage.COURSE -> if (courseFromHistory) MedPage.COURSE_HISTORY else MedPage.MAIN

        MedPage.VISIT -> if (visit.editingId == 0L) MedPage.MAIN else MedPage.VISIT_VIEW

        else -> MedPage.MAIN
    }

    fun back() {
        page = backFrom(page)
    }

    // Pages are this screen's own state, which the app's history knows nothing about, so they answer
    // the back gesture here.
    BackHandler(enabled = page != MedPage.MAIN) { back() }
    val nested = page != MedPage.MAIN
    // The front page's scroll, held here rather than inside it. The front page is composed in two
    // places — as the page itself, and as the backdrop behind whatever is open over it — and each
    // of the two would otherwise start a scroll of its own at the top: a course opened from halfway
    // down would stand over a backdrop showing the top of the tab, and closing it would land there too.
    val mainScroll = rememberScrollState()
    // Everything past the tab's own front page is held open over it as a panel — see [PwnPageHost].
    //
    // The front page and whatever is held open over it are the same `when`, told which of the two
    // to draw. Two call sites rather than one: the panel is handed the open page, and the panel's
    // backdrop is handed the page [back] would land on. Not always the summary — these pages are a
    // stack carried by one panel instance, so the page coming back behind a picker being pulled
    // away is the visit form the picker is a step of.
    val medicinePage: @Composable (Modifier, MedPage) -> Unit = { pageBase, shown ->
        val pageModifier = pageBase.fillMaxSize()

        when (shown) {
            MedPage.MAIN -> MedicineMainPage(
                modifier = pageModifier,
                scroll = mainScroll,
                doses = doses,
                nowMinute = nowMinute,
                nowMs = nowMs,
                zone = zone,
                adherence = adherence,
                medications = medications,
                running = running,
                hasCourseHistory = ended.isNotEmpty(),
                appointments = appointments,
                statuses = statuses,
                allergies = profile.allergyAnswer(),
                history = history,
                sex = profile.sex,
                reminderBlock = silencedBy,
                onLiftReminderBlock = { block ->
                    when (block) {
                        ReminderBlock.REMINDERS_OFF -> turnRemindersOn()
                        ReminderBlock.NOTIFICATIONS_OFF -> openNotificationSettings()
                    }
                },
                onAbout = { showingAbout = true },
                // Marked under the day the screen is showing, which the key above keeps current. Taking
                // the day from the clock instead would file the mark outside the window this screen
                // reads whenever the two disagree, so the dose would never read as taken and the person
                // would tap again — and again.
                // Through the door the widget and the reminder mark by, which writes a dose once
                // whoever taps second ([markDosesTaken]).
                onTake = { dose ->
                    write(takeKey(dose)) {
                        db.markDosesTaken(listOf(medTakenEntry(dose, today, System.currentTimeMillis())))
                    }
                },
                onUndo = { dose ->
                    // Unmarking releases the mark's own guard. Take, undo, take again is three taps a
                    // person can make in well under the window, and the third is a fresh decision
                    // rather than the bounced second tap the window is there to swallow.
                    tappedAt.remove(takeKey(dose))
                    dose.takenEntryId?.let { id -> write("undo$id") { db.deleteJournalEvent(id) } }
                },
                onAddCourse = {
                    courseFromHistory = false
                    course.show(null, today)
                    page = MedPage.COURSE
                },
                onOpenCourse = {
                    courseFromHistory = false
                    course.show(it, today)
                    page = MedPage.COURSE
                },
                onDeleteCourse = { deletingCourseId = it.id },
                onCourseHistory = { page = MedPage.COURSE_HISTORY },
                onAddVisit = {
                    visit.show(null, VISIT_SPECIALTY_UNANSWERED, today)
                    page = MedPage.VISIT
                },
                onVisitHistory = {
                    historyFromScreening = false
                    historySpecialty = null
                    historyDoctor = null
                    page = MedPage.VISIT_HISTORY
                },
                onMedicalHistory = { page = MedPage.HISTORY },
                onReport = onReport,
                onOpenVisit = {
                    visitFromHistory = false
                    visitFromScreening = false
                    viewingId = it.id
                    page = MedPage.VISIT_VIEW
                },
                onOpenCheckup = {
                    screening = it
                    page = MedPage.SCREENING
                },
            )

            MedPage.VISIT_HISTORY -> MedicalVisitHistory(
                modifier = pageModifier,
                appointments = appointments,
                specialty = historySpecialty,
                doctor = historyDoctor,
                nowMs = nowMs,
                zone = zone,
                onSpecialty = {
                    historySpecialty = it
                    historyDoctor = null
                },
                onDoctor = { historyDoctor = it },
                onBack = ::back,
                onOpen = {
                    visitFromHistory = true
                    visitFromScreening = false
                    viewingId = it.id
                    page = MedPage.VISIT_VIEW
                },
            )

            MedPage.COURSE_HISTORY -> CourseHistoryPage(
                modifier = pageModifier,
                // The latest to end first: the course just finished is the one looked for.
                courses = remember(ended) { ended.sortedByDescending { it.endDay } },
                onBack = ::back,
                onOpen = {
                    courseFromHistory = true
                    course.show(it, today)
                    page = MedPage.COURSE
                },
                onDelete = { deletingCourseId = it.id },
            )

            MedPage.COURSE -> CoursePage(
                modifier = pageModifier,
                form = course,
                bloodTests = bloodTests,
                sex = profile.sex,
                imperial = profile.imperial,
                onBack = ::back,
                // Null while adding: there is nothing to delete until the course exists.
                onDelete = medications.firstOrNull { it.id == course.editingId }
                    ?.let { found -> { deletingCourseId = found.id } },
                onSave = {
                    val before = medications.firstOrNull { it.id == course.editingId }?.photo.orEmpty()
                    val waiting = course.pendingPhoto
                    val adding = course.editingId == 0L
                    // The form as it stood at the tap. Read after the photograph's move it would be
                    // whatever the form held by then: another course, opened while the move ran,
                    // would be saved with this one's picture, and this one's edits lost.
                    val saved = course.toMedication()
                    val opening = course.openings
                    write("course${course.editingId}") {
                        // The waiting shot moves in first and the row names it after, so a row
                        // never names a file that is not there yet; the file it replaced goes last.
                        val (photo, replaced) = withContext(Dispatchers.IO) {
                            savedMedicinePhoto(before, saved.photo, waiting) {
                                keepPendingMedicinePhoto(context, System.currentTimeMillis())
                            }
                        }
                        dao.saveMedication(saved.copy(photo = photo))
                        withContext(Dispatchers.IO) { deleteMedicinePhoto(context, replaced) }
                        MedicineAlarms.refresh(context, db)
                        if (page == MedPage.COURSE && course.openings == opening) page = backFrom(MedPage.COURSE)
                        // With reminders off no dose is announced whatever the alarm may do, so
                        // that is asked first, and what the alarm needs only once they are on.
                        if (adding) {
                            if (profile.remindersEnabled) askAlarmAccess() else askingReminders = true
                        }
                        // The dose widget reads the courses too, and a course is no journal write to
                        // redraw it: it would show the old dose, or none, until its half-hourly redraw.
                        refreshWidgets(context, KIND_MED)
                    }
                },
            )

            MedPage.VISIT -> VisitPage(
                modifier = pageModifier,
                form = visit,
                profile = profile,
                today = today,
                zone = zone,
                onBack = ::back,
                onDelete = appointments.firstOrNull { it.id == visit.editingId }
                    ?.let { found -> { deletingVisitId = found.id } },
                onPickDoctor = {
                    pickingPlace = false
                    pickQuery = visit.doctor
                    page = MedPage.VISIT_FIELD
                },
                onPickPlace = {
                    pickingPlace = true
                    pickQuery = visit.place
                    pickNear = false
                    page = MedPage.VISIT_FIELD
                },
                onPickPlaceNear = {
                    pickingPlace = true
                    pickQuery = visit.place
                    pickNear = true
                    page = MedPage.VISIT_FIELD
                },
                onPickRemind = { page = MedPage.VISIT_REMIND },
                onSave = {
                    val edited = visit.toAppointment(zone)
                    write("visit${visit.editingId}") {
                        // Over the row as it stands, read and written in one transaction, as the
                        // reminder stamps it ([visitAnnounced]).
                        db.withTransaction {
                            val stored = dao.allAppointments().firstOrNull { it.id == edited.id }
                            dao.saveAppointment(visitSavedOver(edited, stored))
                        }
                        // A new appointment lands on the summary; a corrected one goes back to the page
                        // it was opened from, which is where the correction can be read.
                        page = if (visit.editingId == 0L) MedPage.MAIN else MedPage.VISIT_VIEW
                    }
                },
            )

            MedPage.VISIT_VIEW -> appointments.firstOrNull { it.id == viewingId }?.let { appointment ->
                VisitViewPage(
                    modifier = pageModifier,
                    appointment = appointment,
                    zone = zone,
                    onBack = ::back,
                    onEdit = {
                        visit.show(appointment, appointment.specialty, today)
                        page = MedPage.VISIT
                    },
                    // "I have been" is the moment there is something to write down — what was said,
                    // and whatever came home on paper. Stamping the row and walking away would throw
                    // away the only visit where a note was ever going to be written.
                    onDone = {
                        visit.show(appointment, appointment.specialty, today, recording = true)
                        page = MedPage.VISIT
                    },
                    onCancel = { deletingVisitId = appointment.id },
                )
            }

            MedPage.HISTORY -> MedicalHistoryPage(
                modifier = pageModifier,
                rows = history,
                sex = profile.sex,
                onBack = ::back,
                onAdd = { kind ->
                    record.show(kind, null)
                    page = MedPage.HISTORY_FORM
                },
                onOpen = { row ->
                    record.show(row.historyKind() ?: HistoryKind.CONDITION, row)
                    page = MedPage.HISTORY_FORM
                },
            )

            MedPage.HISTORY_FORM -> MedicalHistoryFormPage(
                modifier = pageModifier,
                form = record,
                onBack = ::back,
                onDelete = record.editingKey.takeIf(String::isNotBlank)?.let { key ->
                    {
                        write("historyDelete$key") {
                            db.deleteMedicalHistory(key)
                            page = MedPage.HISTORY
                        }
                    }
                },
                onSave = {
                    write("history${record.editingKey}") {
                        dao.saveMedicalHistoryRow(record.toRow(System.currentTimeMillis()))
                        page = MedPage.HISTORY
                    }
                },
            )

            MedPage.VISIT_FIELD -> VisitFieldPage(
                modifier = pageModifier,
                place = pickingPlace,
                specialty = visit.specialty,
                imperial = profile.imperial,
                saved = remember(appointments, pickingPlace, visit.place) {
                    typedBefore(appointments, pickingPlace, visit.place)
                },
                query = pickQuery,
                askNearOnOpen = pickNear,
                onNearAsked = { pickNear = false },
                onQuery = { pickQuery = it.take(if (pickingPlace) PLACE_MAX_CHARS else MED_NAME_MAX) },
                onBack = ::back,
                onPick = {
                    if (pickingPlace) visit.place = it else visit.doctor = it
                    page = MedPage.VISIT
                },
            )

            MedPage.VISIT_REMIND -> RemindPage(
                modifier = pageModifier,
                day = visit.day,
                selected = visit.remindDaysBefore,
                onBack = ::back,
                onPick = {
                    visit.remindDaysBefore = it
                    page = MedPage.VISIT
                },
            )

            MedPage.SCREENING -> screening?.let { checkup ->
                ScreeningPage(
                    modifier = pageModifier,
                    status = statuses.firstOrNull { it.checkup == checkup },
                    checkup = checkup,
                    visits = completedVisits(appointments, checkup.name, null, nowMs),
                    zone = zone,
                    onOpenVisit = {
                        visitFromHistory = false
                        visitFromScreening = true
                        viewingId = it.id
                        page = MedPage.VISIT_VIEW
                    },
                    onAllVisits = {
                        historyFromScreening = true
                        historySpecialty = checkup.name
                        historyDoctor = null
                        page = MedPage.VISIT_HISTORY
                    },
                    onBack = ::home,
                    // "I have been" opens the same form the booking does, in the mode that writes a
                    // visit down: a date to say when, a note for what was said, and room for the sheet
                    // that came home with it. Saving a row dated now and walking away would be right
                    // about twice a year and wrong every other time.
                    onMark = {
                        visit.show(null, checkup.name, today, recording = true)
                        page = MedPage.VISIT
                    },
                    onBook = {
                        visit.show(null, checkup.name, today)
                        page = MedPage.VISIT
                    },
                )
            }
        }
    }
    PwnPageHost(
        sheet = nested,
        onClose = ::back,
        modifier = modifier,
        backdrop = { medicinePage(Modifier, backFrom(page)) },
    ) { pageBase ->
        medicinePage(pageBase, page)
    }

    if (showingAbout) {
        AlertDialog(
            onDismissRequest = { showingAbout = false },
            icon = { Icon(Icons.Outlined.Info, null, tint = PwnMuted) },
            title = { Text(stringResource(R.string.med_disclaimer_title)) },
            text = { Text(stringResource(R.string.med_disclaimer)) },
            confirmButton = {
                TextButton(onClick = { showingAbout = false }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }

    if (askingReminders) {
        AlertDialog(
            onDismissRequest = { askingReminders = false },
            title = { Text(stringResource(R.string.reminders_title)) },
            text = { Text(stringResource(R.string.med_chat_ask_remind_switch)) },
            confirmButton = {
                TextButton(onClick = {
                    askingReminders = false
                    turnRemindersOn()
                }) { Text(stringResource(R.string.med_chat_remind_on)) }
            },
            dismissButton = {
                TextButton(onClick = { askingReminders = false }) {
                    Text(stringResource(R.string.med_chat_remind_leave))
                }
            },
        )
    }

    // A course is a typed-in schedule, so losing one to a mis-tap costs real work. The doses already
    // marked are journal rows and survive either way — which is exactly what the dialog says.
    medications.firstOrNull { it.id == deletingCourseId }?.let { medication ->
        DestructiveDialog(
            title = stringResource(R.string.med_delete_title, medication.name),
            // The photograph is the one thing a deleted course takes with it besides the schedule.
            body = stringResource(
                if (isMedicinePhotoName(medication.photo)) R.string.med_delete_body_photo else R.string.med_delete_body,
            ),
            onDismiss = { deletingCourseId = 0L },
            onConfirm = {
                deletingCourseId = 0L
                // Off the course's own page to wherever it was opened from; a row's bin leaves the
                // list it is in standing.
                if (page == MedPage.COURSE) back()
                write("deletecourse${medication.id}") {
                    dao.deleteMedication(medication.id)
                    withContext(Dispatchers.IO) { deleteMedicinePhoto(context, medication.photo) }
                    MedicineAlarms.refresh(context, db)
                    refreshWidgets(context, KIND_MED)
                }
            },
        )
    }
    appointments.firstOrNull { it.id == deletingVisitId }?.let { appointment ->
        DestructiveDialog(
            title = stringResource(R.string.visit_delete_title),
            body = stringResource(R.string.visit_delete_body),
            onDismiss = { deletingVisitId = 0L },
            onConfirm = {
                deletingVisitId = 0L
                home()
                write("deletevisit${appointment.id}") { dao.deleteAppointment(appointment.id) }
            },
        )
    }
}

/**
 * Which page of the tab is showing. Only [MAIN] is a summary; the rest are one thing each.
 *
 * The appointment has four of them because booking one is four decisions — who, when, where, and
 * when to be told — and a single scrolling form makes every one of them look equally urgent.
 * [VISIT_VIEW] is what a row opens: reading an appointment is the common act, editing it the rare
 * one, and a form that opens on arrival invites a stray tap into a change nobody meant.
 */
private enum class MedPage {
    MAIN,
    COURSE,
    COURSE_HISTORY,
    VISIT,
    VISIT_VIEW,
    VISIT_FIELD,
    VISIT_REMIND,
    SCREENING,
    VISIT_HISTORY,
    HISTORY,
    HISTORY_FORM,
}

/** An enum is not one of the types a Bundle knows, so the open page travels as its name. */
private val PAGE_SAVER: Saver<MedPage, String> =
    Saver(save = { it.name }, restore = { MedPage.valueOf(it) })

/**
 * The same for the examination being read, which most of the time is none at all. Nothing is
 * written for a null rather than an empty name: what comes back from a saved value may not be
 * null, and an absent one is restored as the default instead.
 */
private val CHECKUP_SAVER: Saver<Checkup?, String> =
    Saver(save = { it?.name }, restore = { Checkup.valueOf(it) })

@Composable
private fun MedicineMainPage(
    modifier: Modifier,
    scroll: ScrollState,
    doses: List<PlannedDose>,
    nowMinute: Int,
    nowMs: Long,
    zone: ZoneId,
    adherence: List<MedDay>,
    medications: List<Medication>,
    running: List<Medication>,
    hasCourseHistory: Boolean,
    appointments: List<Appointment>,
    statuses: List<CheckupStatus>,
    allergies: AllergyAnswer,
    history: List<MedicalHistoryRow>,
    sex: String,
    reminderBlock: ReminderBlock?,
    onLiftReminderBlock: (ReminderBlock) -> Unit,
    onAbout: () -> Unit,
    onTake: (PlannedDose) -> Unit,
    onUndo: (PlannedDose) -> Unit,
    onAddCourse: () -> Unit,
    onOpenCourse: (Medication) -> Unit,
    onDeleteCourse: (Medication) -> Unit,
    onCourseHistory: () -> Unit,
    onAddVisit: () -> Unit,
    onOpenVisit: (Appointment) -> Unit,
    onOpenCheckup: (Checkup) -> Unit,
    onVisitHistory: () -> Unit,
    onMedicalHistory: () -> Unit,
    onReport: () -> Unit,
) {
    Column(
        modifier
            .panelVerticalScroll(scroll)
            .padding(horizontal = 20.dp)
            .padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // The tab's name is its first heading, the one a screen reader's heading navigation
                // starts from before it steps through the cards' own.
                Box(Modifier.semantics(mergeDescendants = true) { heading() }) {
                    ProfileSectionHeader(stringResource(R.string.med_title), R.drawable.ic_art_doctor)
                }
                Text(
                    stringResource(R.string.med_subtitle),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onAbout, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(
                    Icons.Outlined.Info,
                    stringResource(R.string.med_disclaimer_title),
                )
            }
        }

        // No courses at all: one invitation instead of three empty cards in a row. The diary and the
        // examinations stay below — they are worth keeping even for someone who takes nothing.
        if (medications.isEmpty()) {
            NoCoursesCard(onAddCourse)
        } else {
            // Above the day's doses, which are what will not be announced.
            reminderBlock?.let { block -> ReminderBlockCard(block) { onLiftReminderBlock(block) } }
            TodayDosesCard(doses, medications.associate { it.id to it.photo }, nowMinute, onTake, onUndo)
            AdherenceCard(adherence)
            CoursesCard(running, onAddCourse, onOpenCourse, onDeleteCourse, onCourseHistory.takeIf { hasCourseHistory })
        }

        VisitsCard(appointments, nowMs, zone, onAddVisit, onOpenVisit, onVisitHistory, onReport)
        MedicalHistoryCard(history, sex, onMedicalHistory)
        CheckupsCard(statuses, onOpenCheckup)
        AllergyTestCard(allergies)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The numbered blocks of the summary, and the numbered steps of the booking form. The number is the
 * reading order, which is fixed.
 *
 * [content] is what sits at the end of the row — a counter, a question mark, or nothing. [badge]
 * is the disc the number sits on, which has to be told apart from whatever the card behind it is:
 * surfaceVariant is the same pale step as the card tier and the disc would vanish into it, so it is
 * the page's own surface.
 *
 * The disc is a floor rather than a fixed circle: the number is text and grows with the font
 * scale, and a fixed 22dp ring would clip it. Wider than it is tall, the circle becomes a pill.
 */
@Composable
private fun MedSectionHeader(
    number: Int,
    title: String,
    badge: Color = MaterialTheme.colorScheme.surface,
    content: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.sizeIn(minWidth = 22.dp, minHeight = 22.dp), shape = CircleShape, color = badge) {
            Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = PwnMuted,
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        // A heading, so a screen reader can skim the summary's cards and the form's steps by them.
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        content?.invoke()
    }
}

@Composable
private fun NoCoursesCard(onAdd: () -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The pill, not the doctor. What is missing here is a course of medicine; the doctor
            // is what the tab itself is called, and drawing it over "No course is running" would say
            // the page was empty of doctors.
            Image(
                themePainterResource(R.drawable.ic_art_pills),
                contentDescription = null,
                modifier = Modifier.size(84.dp),
            )
            Text(
                stringResource(R.string.med_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.med_empty_body),
                color = PwnMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            MedAddButton(onAdd)
        }
    }
}

/**
 * "Add a medicine", white with an outline on its tinted card, as the booking button below it is:
 * the page's buttons are to look alike, and white.
 */
@Composable
private fun MedAddButton(onAdd: () -> Unit) {
    OutlinedButton(
        onClick = onAdd,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.med_add))
    }
}

/**
 * Why no dose will be announced ([reminderBlock]), and the one button that lifts it: white with an
 * outline on its tinted card, as the page's other buttons are.
 */
@Composable
private fun ReminderBlockCard(block: ReminderBlock, onLift: () -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.NotificationsNone, null, tint = PwnMuted)
                Text(stringResource(block.bodyRes), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(
                onClick = onLift,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
            ) { Text(stringResource(block.actionRes)) }
        }
    }
}

/**
 * What today asks for: the time on the right, the name and the instruction on the left, and the
 * course's photograph before the name where it has one ([photos], by course id).
 */
@Composable
private fun TodayDosesCard(
    doses: List<PlannedDose>,
    photos: Map<Long, String>,
    nowMinute: Int,
    onTake: (PlannedDose) -> Unit,
    onUndo: (PlannedDose) -> Unit,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val left = doses.count { !it.taken }
            MedSectionHeader(1, stringResource(R.string.med_today_title)) {
                if (doses.isNotEmpty()) {
                    Text(
                        if (left == 0) {
                            stringResource(R.string.med_today_all_done)
                        } else {
                            pluralStringResource(R.plurals.med_today_left, left, left)
                        },
                        color = if (left == 0) PwnGreen else PwnIndigo,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            // Every course has ended, or every one of them starts tomorrow.
            if (doses.isEmpty()) {
                Text(stringResource(R.string.med_today_none), color = PwnMuted)
            }
            doses.forEachIndexed { index, dose ->
                DoseRow(dose, photos[dose.medicationId].orEmpty(), nowMinute, onTake, onUndo)
                if (index != doses.lastIndex) HorizontalDivider(color = PwnBorder)
            }
        }
    }
}

@Composable
private fun DoseRow(
    dose: PlannedDose,
    photo: String,
    nowMinute: Int,
    onTake: (PlannedDose) -> Unit,
    onUndo: (PlannedDose) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MedicineGlyph(photo, dose.name, 46.dp) {
            PwnGlyph(
                R.drawable.ic_art_pills,
                Icons.Outlined.Medication,
                if (dose.taken) PwnGreenSoft else PwnIndigoSoft,
                null,
                Modifier.size(46.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            // Wrapped rather than cut to one line: this is the name of what the button beside it
            // marks as taken, and two courses that share a prefix would be one ellipsis apart at a
            // large font. The row is top-aligned, so it simply grows.
            Text(
                dose.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    stringResource(R.string.med_dose_on_waking).takeIf { dose.onWaking },
                    dose.dose.takeIf { it.isNotBlank() },
                    stringResource(dose.food.titleRes).takeIf { dose.food != MedFood.ANY },
                ).joinToString(" · "),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Keyed on whether the dose is marked, so the swap plays once per tap and never otherwise;
        // the side fading out keeps the dose it was drawn from rather than borrowing the new one.
        AnimatedContent(
            targetState = dose,
            contentKey = { it.taken },
            contentAlignment = Alignment.TopEnd,
            transitionSpec = { fadeIn(PwnMotion.effects()) togetherWith fadeOut(PwnMotion.effects()) },
            label = "dose mark",
        ) { shown -> DoseAction(shown, nowMinute, onTake, onUndo) }
    }
}

/** The right of a dose row: when it was taken, is due or is late, and the one button that changes it. */
@Composable
private fun DoseAction(
    dose: PlannedDose,
    nowMinute: Int,
    onTake: (PlannedDose) -> Unit,
    onUndo: (PlannedDose) -> Unit,
) {
    val late = !dose.taken && nowMinute > dose.minuteOfDay
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val takenAt = dose.takenAtMs
        when {
            takenAt != null -> {
                val at = Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault())
                Text(
                    stringResource(
                        R.string.med_dose_taken_at,
                        clockText(at.hour * 60 + at.minute),
                    ),
                    color = PwnGreen,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            late -> Text(
                stringResource(R.string.med_dose_late),
                color = PwnWarn,
                style = MaterialTheme.typography.bodySmall,
            )

            !dose.onWaking -> Text(
                clockText(dose.minuteOfDay),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // Material 3 Expressive's pressed shape, as the wide button has it: the round button
        // squares its corners under the finger and springs back when it lifts.
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val corner by animateDpAsState(
            if (pressed) PwnCorners.medium else ButtonDefaults.MinHeight / 2,
            PwnMotion.fastSpatial(),
            label = "dose button corner",
        )
        // Said with the dose it acts on. In a screen reader's list of a card's actions, "Undo" on
        // its own does not say which of the day's doses it takes back.
        val subject = if (dose.onWaking) {
            stringResource(R.string.med_dose_on_waking)
        } else {
            clockText(dose.minuteOfDay)
        }
        val spoken = stringResource(
            if (dose.taken) R.string.med_a11y_undo_dose else R.string.med_a11y_mark_dose,
            dose.name,
            subject,
        )
        // Filled, not outlined: an outline on a white card reads as a label rather than a button,
        // in both themes.
        FilledTonalButton(
            onClick = { if (dose.taken) onUndo(dose) else onTake(dose) },
            modifier = Modifier.semantics { contentDescription = spoken },
            shape = RoundedCornerShape(corner),
            contentPadding = DOSE_BUTTON_PADDING,
            interactionSource = interaction,
        ) {
            if (dose.taken) {
                Text(stringResource(R.string.med_dose_undo))
            } else {
                Icon(Icons.Outlined.Check, null, Modifier.size(17.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.med_dose_take))
            }
        }
    }
}

private val DOSE_BUTTON_PADDING = PaddingValues(
    horizontal = 14.dp,
    vertical = 6.dp,
)

/** Room for a whole label on a button that only has half a screen to say it in. */
private val HALF_BUTTON_PADDING = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/**
 * A fortnight of doses as one row of bars: how much of each day's schedule was marked.
 *
 * Bars rather than a line, because the quantity is a share of a small whole number — three doses out
 * of four is a bar two thirds full, and a curve smoothed between such points would invent a
 * precision the record does not have. A full day is green, a partial one warm, an empty one grey:
 * the same three colours the legend names.
 */
@Composable
private fun AdherenceCard(days: List<MedDay>) {
    val planned = days.sumOf(MedDay::planned)
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val title = stringResource(R.string.med_chart_title)
            // How to read the bars sits behind the question mark beside their heading; the figures
            // and the legend stay on the card. With no chart drawn there is nothing to explain.
            MedSectionHeader(
                2,
                title,
                content = if (planned > 0) {
                    { HelpDot(title) { Text(stringResource(R.string.med_chart_legend)) } }
                } else {
                    null
                },
            )
            if (planned == 0) {
                Text(stringResource(R.string.med_chart_empty), color = PwnMuted)
                return@Column
            }
            val taken = days.sumOf(MedDay::taken)
            val percent = (taken * 100.0 / planned).roundToInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.med_chart_percent, percent),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (percent >= MED_GOOD_ADHERENCE_PERCENT) PwnGreen else PwnWarn,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    pluralStringResource(R.plurals.med_chart_of, planned, planned),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Read here rather than inside the draw lambda, which cannot ask the theme itself.
            // A bar is a shape, and a shape has to clear 3:1 against what it touches. Two things
            // here touch: the day's slot against the card, and the kept part of a day against the
            // rest of that slot. One colour cannot serve both — a track dark enough to be seen on
            // white (L <= 0.30) is closer to the deep green and deep amber drawn over it than the
            // 3:1 those need — so the slot keeps its pale fill and gains an outline, which is what
            // carries its shape. The outline is PwnMuted: 5.46:1 on the white card, 9.43:1 on the
            // night one. The fill stays surfaceVariant, where the kept part reads 5.01:1 by day
            // and 6.42:1 at night against it.
            //
            // PwnWarn rather than PwnOrange, which is 2.29:1 against that fill: PwnWarn is the same
            // orange deepened for daylight, and its night half is what the tertiary resolves to.
            val empty = MaterialTheme.colorScheme.surfaceVariant
            val edge = PwnMuted
            val full = PwnGreen
            val partial = PwnWarn
            // A row of bars publishes nothing on its own, and the two figures above it are
            // already text. What the drawing has to add is the shape of the fortnight: how many
            // days were carried through, how many were half kept, how many went by untouched.
            val spoken = stringResource(
                R.string.med_chart_description,
                days.count { it.planned > 0 && it.taken >= it.planned },
                days.count { it.taken > 0 && it.taken < it.planned },
                days.count { it.planned > 0 && it.taken == 0 },
            )
            Canvas(
                Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = spoken },
            ) {
                val slot = size.width / days.size
                val bar = (slot - 6f).coerceAtLeast(2f)
                val radius = CornerRadius(bar / 2f)
                val hairline = Stroke(width = 1.dp.toPx())
                // Oldest to today along the reading direction, the way the caption under the bars
                // runs: that Row mirrors in a right-to-left language, and a canvas does not, so the
                // slots are mirrored here too, or in Arabic "today" would stand under the oldest bar.
                val rtl = layoutDirection == LayoutDirection.Rtl
                days.forEachIndexed { index, day ->
                    val slotIndex = if (rtl) days.lastIndex - index else index
                    val left = slotIndex * slot + (slot - bar) / 2f
                    drawRoundRect(empty, Offset(left, 0f), Size(bar, size.height), radius)
                    if (day.planned > 0 && day.taken > 0) {
                        val height = (day.share * size.height).toFloat().coerceAtLeast(bar)
                        drawRoundRect(
                            color = if (day.taken >= day.planned) full else partial,
                            topLeft = Offset(left, size.height - height),
                            size = Size(bar, height),
                            cornerRadius = radius,
                        )
                    }
                    // Last, and over both: a day nobody touched is nothing but this line, and on a
                    // day half kept it is what says where the slot ends and the card begins.
                    drawRoundRect(edge, Offset(left, 0f), Size(bar, size.height), radius, style = hairline)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    pluralStringResource(
                        R.plurals.med_chart_days_ago,
                        MED_CHART_DAYS - 1,
                        MED_CHART_DAYS - 1,
                    ),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.med_today_title),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChartKey(full, stringResource(R.string.med_chart_full))
                ChartKey(partial, stringResource(R.string.med_chart_partial))
                ChartKey(empty, stringResource(R.string.med_chart_none), edge = edge)
            }
        }
    }
}

/**
 * One swatch of the chart's legend. [edge] outlines it the way the bars are outlined: the empty
 * day's pale fill does not stand out from the card on its own, and without the ring its key would
 * be a word beside an invisible dot.
 */
@Composable
private fun ChartKey(color: Color, label: String, edge: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            Modifier.size(9.dp),
            shape = CircleShape,
            color = color,
            border = edge?.let { BorderStroke(1.dp, it) },
        ) {}
        Spacer(Modifier.size(6.dp))
        Text(label, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Above this share the number reads as "holding to it" and turns green. */
private const val MED_GOOD_ADHERENCE_PERCENT = 80

/**
 * The courses still running or yet to start. Those that have ended are its history, and the clock in
 * the corner that opens it is there only when there is one ([onHistory] null otherwise): an icon
 * that opens an empty page is a promise the page cannot keep.
 */
@Composable
private fun CoursesCard(
    medications: List<Medication>,
    onAdd: () -> Unit,
    onOpen: (Medication) -> Unit,
    onDelete: (Medication) -> Unit,
    onHistory: (() -> Unit)?,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MedSectionHeader(
                3,
                stringResource(R.string.med_list_title),
                content = onHistory?.let { open ->
                    {
                        IconButton(
                            onClick = open,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                        ) {
                            Icon(Icons.Outlined.History, stringResource(R.string.med_history))
                        }
                    }
                },
            )
            if (medications.isEmpty()) Text(stringResource(R.string.med_empty_title), color = PwnMuted)
            medications.forEach { CourseRow(it, onOpen, onDelete) }
            MedAddButton(onAdd)
        }
    }
}

/** The courses that have ended, the latest first, each opening its page as a running one does. */
@Composable
private fun CourseHistoryPage(
    modifier: Modifier,
    courses: List<Medication>,
    onBack: () -> Unit,
    onOpen: (Medication) -> Unit,
    onDelete: (Medication) -> Unit,
) {
    Column(
        modifier.panelVerticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PwnTopBar(stringResource(R.string.med_history), onBack)
        PwnCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Emptied by a delete made here; the page is left standing rather than pulled away.
                if (courses.isEmpty()) Text(stringResource(R.string.med_history_empty), color = PwnMuted)
                courses.forEach { CourseRow(it, onOpen, onDelete) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** One course in a list: its photograph, name, schedule and end, with its edit and its bin. */
@Composable
private fun CourseRow(medication: Medication, onOpen: (Medication) -> Unit, onDelete: (Medication) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MedicineGlyph(medication.photo, medication.name, 40.dp) {
            PwnGlyph(
                R.drawable.ic_art_pills,
                Icons.Outlined.Medication,
                PwnIndigoSoft,
                null,
                Modifier.size(40.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            // Wrapped, as on today's list: the name is what tells two courses apart.
            Text(
                medication.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                courseSummary(medication),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            contentsSummary(medication)?.let {
                Text(
                    it,
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The column holds an ISO day, and printing it raw would put "до 2026-10-06"
            // in a list whose very next row says "15 сентября 2026, вт в 10:30". A
            // day a hand-edited backup left unparseable still shows as it stands
            // rather than taking the row down.
            val endsOn = medication.endDay
            val endsShown = endsOn?.let { day ->
                val parsed = day.toLocalDateOrNull()
                if (parsed == null) day else parsed.formatLongDay()
            }
            Text(
                endsShown?.let { stringResource(R.string.med_course_until, it) }
                    ?: stringResource(R.string.med_end_open),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Both labels name the course: read out of the row, as a screen reader's list
        // of actions reads them, a bare "Delete" does not say which course goes.
        IconButton(onClick = {
            onOpen(medication)
        }, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
            Icon(
                Icons.Outlined.Edit,
                stringResource(R.string.med_a11y_edit_course, medication.name),
            )
        }
        // Edit stays a quiet glyph; only the bin is drawn, so the destructive one of
        // the pair is the one the eye lands on without any red being spent on it.
        IconButton(onClick = { onDelete(medication) }) {
            Image(
                themePainterResource(R.drawable.ic_art_delete),
                stringResource(R.string.med_a11y_delete_course, medication.name),
                Modifier.size(26.dp),
            )
        }
    }
}

/** "1 таблетка · 08:00, 20:00 · После еды", with the empty parts left out. */
@Composable
private fun courseSummary(medication: Medication): String {
    val food = medication.foodRelation()
    return listOfNotNull(
        medication.dose.takeIf { it.isNotBlank() },
        if (medication.scheduleKind() == MedSchedule.WAKE) {
            stringResource(R.string.med_dose_on_waking)
        } else {
            medication.timesText(stringResource(R.string.list_comma)).takeIf { it.isNotBlank() }
        },
        stringResource(food.titleRes).takeIf { food != MedFood.ANY },
    ).joinToString(" · ")
}

/**
 * "Vitamin D 50 µg · Magnesium 200 mg": what one dose holds, or null for a course nobody described
 * that way — which is not a course that holds nothing.
 */
@Composable
private fun contentsSummary(medication: Medication): String? {
    val locale = LocalLocale.current.platformLocale
    return contentsOf(medication.contents).entries
        .sortedBy { it.key.ordinal }
        .map { (nutrient, amount) ->
            stringResource(
                R.string.report_value_unit,
                stringResource(nutrient.titleRes),
                stringResource(
                    R.string.report_value_unit,
                    doseAmountText(amount, locale),
                    stringResource(ownUnitRes(nutrient)),
                ),
            )
        }
        .joinToString(" · ")
        .takeIf { it.isNotEmpty() }
}

/**
 * The diary, and the one card of the summary that carries a colour of its own.
 *
 * Booking is the only thing on this screen a person does ahead of time rather than about today, and
 * the tint is what makes it findable in a column of five white cards.
 * The rows inside carry the specialty's icon on a white disc, which is what keeps them legible on
 * the tint.
 */
@Composable
private fun VisitsCard(
    appointments: List<Appointment>,
    nowMs: Long,
    zone: ZoneId,
    onAdd: () -> Unit,
    onOpen: (Appointment) -> Unit,
    onHistory: () -> Unit,
    onReport: () -> Unit,
) {
    val upcoming = remember(appointments, nowMs) { upcomingAppointments(appointments, nowMs) }
    val pastDue = remember(appointments, nowMs) { pastDueAppointments(appointments, nowMs) }
    PwnCard(
        Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.visit_title),
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onHistory, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                    Icon(Icons.Outlined.History, stringResource(R.string.visit_history))
                }
            }
            if (upcoming.isEmpty() && pastDue.isEmpty()) {
                Text(stringResource(R.string.visit_empty), color = PwnMuted)
            }
            upcoming.forEach { VisitRow(it, zone, onOpen) }
            // A date that has passed with nothing marked is the one thing this list must not hide:
            // the visit either happened, in which case it winds a screening clock, or it was missed.
            if (pastDue.isNotEmpty()) {
                // The token itself, on this card's primaryContainer too: the deepened daylight
                // amber reads 5.32:1 on that pale indigo, and the night one 6.23:1.
                Text(
                    stringResource(R.string.visit_past_title),
                    color = PwnWarn,
                    style = MaterialTheme.typography.labelLarge,
                )
                pastDue.forEach { VisitRow(it, zone, onOpen) }
            }
            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.visit_add))
            }
            // What a visit is for, under a rule: the record on paper, for whoever the person sits
            // across a desk from. Here rather than in the profile, a tab away from the doctor it is made for.
            DeviceCardDivider()
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onReport).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(themePainterResource(R.drawable.ic_art_doctor), null, Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.report_menu), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.report_menu_body),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = PwnMuted)
            }
        }
    }
}

@Composable
internal fun VisitRow(appointment: Appointment, zone: ZoneId, onOpen: (Appointment) -> Unit) {
    val at = Instant.ofEpochMilli(appointment.atMs).atZone(zone)
    Row(
        // A row that opens a page says it is a button, not only that it can be tapped.
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(appointment) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(themePainterResource(specialtyArt(appointment.specialty)), null, Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(
                specialtyLabel(appointment.specialty),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.visit_at,
                    at.toLocalDate().formatLongDay(),
                    clockText(at.hour * 60 + at.minute),
                ),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            listOfNotNull(
                appointment.doctor.takeIf { it.isNotBlank() },
                appointment.place.takeIf { it.isNotBlank() },
            )
                .takeIf { it.isNotEmpty() }
                ?.let {
                    Text(
                        it.joinToString(" · "),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = PwnMuted)
    }
}

/**
 * The examinations that apply at this age and sex: name, interval, and when the next one is due.
 *
 * One line each, and the detail — how much of the interval has gone, when the last visit was, and
 * the two things a person can do about it — lives on its own page. A summary that carried four
 * buttons per row would be a wall of buttons.
 */
@Composable
private fun CheckupsCard(statuses: List<CheckupStatus>, onOpen: (Checkup) -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.checkup_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            statuses.forEach { status ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { onOpen(status.checkup) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        themePainterResource(specialtyArt(status.checkup.name)),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(status.checkup.titleRes),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(intervalText(status.checkup), nextDueText(status))
                                .joinToString(" · "),
                            color = if (status.overdue) PwnWarn else PwnMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = PwnMuted)
                }
            }
        }
    }
}

/**
 * The one card here that is not a screening, and is careful to say so.
 *
 * It appears only for somebody who answered that they have never been tested, and it names the test
 * there is. What it deliberately does not do is put a clock on it: no body recommends testing a
 * population with no symptoms for allergy — sensitisation is not allergy, and a positive in somebody
 * with no reaction is a result that has to be explained away rather than acted on. So this carries
 * no interval, never says "due", and never turns the colour an overdue screening turns. It says
 * what the test is and whose question it answers, and leaves the asking to the person.
 *
 * A literal reading of "show the recommended test" would put a clock there; it is left off for the
 * reason that keeps every other figure in this app attributable: the app does not issue medical
 * advice nobody published.
 */
@Composable
private fun AllergyTestCard(answer: AllergyAnswer) {
    if (answer != AllergyAnswer.NotTested) return
    PwnCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(themePainterResource(R.drawable.ic_art_labs), contentDescription = null, Modifier.size(40.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val title = stringResource(R.string.allergy_test_title)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // What the guidelines say about testing without symptoms: read once, behind
                    // the question mark, not under the card on every visit to the tab.
                    HelpDot(title) { Text(stringResource(R.string.allergy_test_note)) }
                }
                Text(
                    stringResource(R.string.allergy_test_body),
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// --- the course page ----------------------------------------------------------------------------

/**
 * "On waking" is timed by the night's sleep record where there is one ([wakeMinuteOf]), and by 08:00
 * until there is. The estimate from the phone's use is one such record — worked out when the app is
 * opened, never in the background — so choosing "on waking" turns that estimate on: through the
 * Usage access screen first where it is not granted, and the choice is made only once the access
 * comes back.
 */
@Composable
private fun rememberWakeScheduleGate(onChosen: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chosen by rememberUpdatedState(onChosen)
    val choose: () -> Unit = {
        chosen()
        scope.launch { enableSleepEstimate(context) }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasUsageStatsAccess(context)) choose()
    }
    return { if (hasUsageStatsAccess(context)) choose() else request.openUsageAccess(context) }
}

/**
 * Adding a medicine, or correcting one. Its own page rather than a card in the list: there are ten
 * fields here, and a form that unfolds inside a summary pushes everything else out of reach.
 */
@Composable
private fun CoursePage(
    modifier: Modifier,
    form: CourseFormState,
    bloodTests: List<SupplementResponse>,
    sex: String,
    imperial: Boolean,
    onBack: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit,
) {
    var addingTime by rememberSaveable { mutableStateOf(false) }
    var pickingStart by rememberSaveable { mutableStateOf(false) }
    var pickingEnd by rememberSaveable { mutableStateOf(false) }
    val chooseWake = rememberWakeScheduleGate { form.schedule = MedSchedule.WAKE }
    Column(
        modifier.panelVerticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PwnTopBar(
            title = stringResource(
                if (form.editingId == 0L) R.string.med_add else R.string.med_course_edit_title,
            ),
            onBack = onBack,
            content = medDeleteAction(onDelete),
        )
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Numbered blocks, as booking a visit is: what it is, when it is
            // taken, for how long, then the note and the files. One column of ten fields would give
            // the note the weight of the name.
            StepCard(1, stringResource(R.string.med_step_medicine)) {
                val context = LocalContext.current
                MedicineNameWithPhoto(
                    name = form.name,
                    shown = when {
                        form.pendingPhoto -> pendingMedicinePhoto(context)
                        isMedicinePhotoName(form.photo) -> File(medicinePhotoDir(context), form.photo)
                        else -> null
                    },
                    version = form.photoVersion,
                    onTaken = form::photoTaken,
                    onRemove = form::removePhoto,
                ) {
                    CountedField(
                        value = form.name,
                        onValue = { form.name = it.take(MED_NAME_MAX) },
                        label = stringResource(R.string.med_name),
                        max = MED_NAME_MAX,
                    )
                }
                CountedField(
                    value = form.dose,
                    onValue = { form.dose = it.take(MED_NAME_MAX) },
                    label = stringResource(R.string.med_dose_label),
                    max = MED_NAME_MAX,
                )
                DoseContentsField(form)
            }
            // Read-only and a card of its own, so it stands between the steps rather than in one.
            CourseBloodTests(bloodTests, sex, imperial)
            StepCard(2, stringResource(R.string.med_schedule)) {
                PwnSegmentedControl(
                    options = listOf(
                        stringResource(R.string.med_schedule_clock) to null,
                        stringResource(R.string.med_schedule_wake) to null,
                    ),
                    selected = if (form.schedule == MedSchedule.CLOCK) 0 else 1,
                    onSelected = { if (it == 0) form.schedule = MedSchedule.CLOCK else chooseWake() },
                )
                if (form.schedule == MedSchedule.CLOCK) {
                    FieldLabel(stringResource(R.string.med_times))
                    if (form.times.isEmpty()) {
                        Text(
                            stringResource(R.string.med_times_empty),
                            color = PwnMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        form.times.sorted().forEach { minute ->
                            AssistChip(
                                onClick = { form.times = form.times - minute },
                                label = { Text(clockText(minute)) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Outlined.Close,
                                        stringResource(R.string.common_delete),
                                        Modifier.size(16.dp),
                                    )
                                },
                                border = chipEdge(),
                            )
                        }
                        AddTextButton(stringResource(R.string.med_add_time)) { addingTime = true }
                    }
                } else {
                    // What "on waking" is timed by, said where it is chosen. Without a record of
                    // the night it is a reminder at eight — and the estimate from the phone's use
                    // is a record only once the app has been opened that morning, so for someone
                    // who does not open it, eight is the time every day.
                    Text(
                        stringResource(R.string.med_schedule_wake_hint, clockText(MED_DEFAULT_WAKE_MINUTE)),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Four options, and "В любое время" in a quarter of the screen is two broken lines.
                // The hints move into the list with them: they are what the choice is made on.
                PwnChoiceField(
                    label = stringResource(R.string.med_food),
                    options = MedFood.entries.map { stringResource(it.titleRes) },
                    details = MedFood.entries.map { stringResource(it.hintRes) },
                    selected = MedFood.entries.indexOf(form.food),
                    onSelected = { form.food = MedFood.entries[it] },
                )
            }
            StepCard(3, stringResource(R.string.med_step_dates)) {
                FieldButton(
                    label = stringResource(R.string.med_start_label),
                    value = form.start.formatLongDay(),
                    icon = Icons.Outlined.CalendarMonth,
                    onClick = { pickingStart = true },
                )
                FieldButton(
                    label = stringResource(R.string.med_end_label),
                    value = form.end?.formatLongDay() ?: stringResource(R.string.med_end_open),
                    icon = Icons.Outlined.CalendarMonth,
                    onClick = { pickingEnd = true },
                )
                if (form.end != null) {
                    TextButton(onClick = { form.end = null }) {
                        Text(stringResource(R.string.med_end_clear))
                    }
                }
            }
            val noteTitle = stringResource(R.string.med_note_label)
            StepCard(4, noteTitle) {
                CountedField(
                    value = form.note,
                    onValue = { form.note = it.take(MED_NOTE_MAX) },
                    label = null,
                    max = MED_NOTE_MAX,
                    singleLine = false,
                    description = noteTitle,
                )
            }
            StepCard(5, stringResource(R.string.attachments_title)) {
                AttachmentsField(form.attachments, showTitle = false) { form.attachments = it }
            }
            Button(
                onClick = onSave,
                enabled = form.valid,
                modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                shape = CircleShape,
            ) { Text(stringResource(R.string.common_save)) }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (addingTime) {
        ClockTimePickerDialog(
            // The dialog's own title is "Choose %s", so the label has to be the noun the wheel
            // holds. The button that opens it says "Add a time"; pasted in here that would read
            // "Choose Add a time".
            label = stringResource(R.string.visit_time),
            initialMinuteOfDay = MED_NEW_TIME_MINUTE,
            onDismiss = { addingTime = false },
            onConfirm = {
                form.times = form.times + it
                addingTime = false
            },
        )
    }
    // The two dates are one range, so each pick carries the other with it rather than leaving a
    // course that ends before it starts — which is a course that never asks for anything.
    if (pickingStart) {
        DayPickerDialog(form.start, onDismiss = { pickingStart = false }) { picked ->
            form.start = picked
            form.end = form.end?.let { maxOf(it, picked) }
            pickingStart = false
        }
    }
    if (pickingEnd) {
        DayPickerDialog(form.end ?: form.start, onDismiss = { pickingEnd = false }) {
            form.end = maxOf(it, form.start)
            pickingEnd = false
        }
    }
}

/**
 * The blood results on file beside a saved course whose label says what it holds, read-only: each
 * marker a nutrient on the label is read against, with the result before the course and the one
 * after it, or the wait for one — the lines the doctor's report prints ([responseLines]).
 *
 * It informs and asks for nothing. A result not yet drawn is said to be missing and when one would
 * reflect a steady dose, with no reminder and no notification behind it; nor does any line say
 * whether the course did anything.
 */
@Composable
private fun CourseBloodTests(responses: List<SupplementResponse>, sex: String, imperial: Boolean) {
    if (responses.isEmpty()) return
    val resources = LocalResources.current
    val label = stringResource(R.string.labs_title)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel(label, Modifier.weight(1f, fill = false))
        // What these results are and are not — no verdict on the course — beside the label.
        HelpDot(label) { Text(stringResource(R.string.med_course_blood_tests_body)) }
    }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            responses.forEach { response ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(response.pairing.marker.titleRes), fontWeight = FontWeight.SemiBold)
                    Text(
                        response.responseLines(sex, imperial).joinToString(" ") { it.resolve(resources) },
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * What one dose holds, for the course whose label lists it: folded into a button until wanted, the
 * way a note is, because most courses are a prescription whose dose is the box's business and this
 * is the section only a supplement fills.
 *
 * Inline rather than a dialog or a page of its own: it is part of the course and scrolls with it.
 * The figures are typed at the right margin of each line, as the food editor's nutrient lines are,
 * and a line for vitamin D, A or E carries the label's other units as chips. A figure typed in one
 * of those is converted as it is typed and only the nutrient's own unit is stored, so the line says
 * underneath what will be saved — the conversion can be checked rather than believed.
 */
@Composable
private fun DoseContentsField(form: CourseFormState) {
    if (!form.contentsOpen) {
        OutlinedButton(
            onClick = { form.contentsOpen = true },
            // A floor, not a fixed height: at a large font the label wraps to a second line, and
            // a fixed height would cut that line off.
            modifier = Modifier.fillMaxWidth().heightIn(min = NOTE_BUTTON_HEIGHT),
            shape = RoundedCornerShape(NOTE_BUTTON_RADIUS),
            border = BorderStroke(1.dp, PwnBorder),
        ) {
            Icon(Icons.Outlined.Add, null, Modifier.size(20.dp), tint = PwnMuted)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.med_contents_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = PwnAccentInk,
            )
        }
        return
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(stringResource(R.string.med_contents_title))
        Text(
            stringResource(R.string.med_contents_presets_hint),
            color = PwnMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SupplementPreset.entries.forEach { preset ->
                val title = stringResource(preset.titleRes)
                AssistChip(onClick = { form.addPreset(preset, title) }, label = { Text(title) }, border = chipEdge())
            }
        }
        form.contents.forEach { line ->
            // Keyed by nutrient: a line removed above would otherwise hand its field's cursor and
            // selection to the line that slides up into its place.
            key(line.nutrient) {
                DoseContentRow(
                    line = line,
                    onLine = { changed ->
                        form.contents = form.contents.map { if (it.nutrient == line.nutrient) changed else it }
                    },
                    onRemove = { form.contents = form.contents.filterNot { it.nutrient == line.nutrient } },
                )
            }
        }
        AddTextButton(stringResource(R.string.med_contents_add_nutrient)) { adding = !adding }
        if (adding) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Nutrient.entries
                    .filterNot { nutrient -> form.contents.any { it.nutrient == nutrient } }
                    .forEach { nutrient ->
                        AssistChip(
                            onClick = {
                                form.contents = form.contents + ContentLine(nutrient)
                                adding = false
                            },
                            label = { Text(stringResource(nutrient.titleRes)) },
                            border = chipEdge(),
                        )
                    }
            }
        }
        Text(
            stringResource(R.string.med_contents_minerals_hint),
            color = PwnMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One nutrient of a dose: its name, the figure typed in place at the right margin, the unit, and
 * the cross that takes the line away. Built from the food editor's nutrient line, whose own
 * composable is private to that screen and bound to its five nutrients.
 */
@Composable
private fun DoseContentRow(line: ContentLine, onLine: (ContentLine) -> Unit, onRemove: () -> Unit) {
    val label = stringResource(line.nutrient.titleRes)
    val ownUnit = stringResource(ownUnitRes(line.nutrient))
    val labelUnits = LabelUnit.entries.filter { it.nutrient == line.nutrient }
    val locale = LocalLocale.current.platformLocale
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            val text = rememberSyncedText(line.typed, { onLine(line.copy(typed = it.decimalInput())) })
            BasicTextField(
                state = text.state,
                inputTransformation = text.handOff,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                // The food editor's lines draw the locale's separator over the stored dot, and so do these.
                outputTransformation = rememberDecimalSeparator(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .width(DOSE_VALUE_WIDTH)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = label },
                decorator = TextFieldDecorator { field ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        // A dash rather than a blank, as on the food editor's lines: an empty right
                        // margin on a line that asks for a number looks like it failed to load.
                        if (line.typed.isEmpty()) {
                            Text(
                                stringResource(R.string.editor_extra_blank),
                                style = MaterialTheme.typography.bodyLarge,
                                color = PwnMuted,
                            )
                        }
                        field()
                    }
                },
            )
            Text(
                if (line.unit == null) ownUnit else stringResource(R.string.nutrient_unit_iu),
                style = MaterialTheme.typography.bodyMedium,
                color = PwnMuted,
            )
            IconButton(onClick = onRemove, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(
                    Icons.Outlined.Close,
                    stringResource(R.string.med_contents_remove, label),
                    Modifier.size(18.dp),
                )
            }
        }
        // Which unit the figure is typed in decides what is stored, and it is one short answer out
        // of two or three — the segmented control's job, by the rule [PwnChoiceField] states: one
        // answer marked by a filled pill and said to a screen reader as the one chosen of a set,
        // where a row of chips reads as separate toggles.
        if (labelUnits.isNotEmpty()) {
            PwnSegmentedControl(
                options = listOf(ownUnit to null) + labelUnits.map { stringResource(it.labelRes) to null },
                selected = line.unit?.let { labelUnits.indexOf(it) + 1 } ?: 0,
                onSelected = { index -> onLine(line.inUnit(labelUnits.getOrNull(index - 1))) },
            )
        }
        val stored = line.amount
        if (line.unit != null && stored != null) {
            Text(
                stringResource(
                    R.string.med_contents_saved_as,
                    stringResource(R.string.report_value_unit, doseAmountText(stored, locale), ownUnit),
                ),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val hint = when {
            line.unit == LabelUnit.VITAMIN_A_IU -> R.string.med_contents_retinol_hint

            // US labels print "667 µg DFE (400 µg folic acid)": the first is the equivalents, which
            // would count the tablet 1.7 times over here.
            line.nutrient == Nutrient.FOLIC_ACID -> R.string.med_contents_folic_hint

            else -> null
        }
        hint?.let {
            Text(stringResource(it), color = PwnMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Wider than the food editor's line, whose widest figure is a four-digit sodium: IU run to five. */
private val DOSE_VALUE_WIDTH = 88.dp

/**
 * The unit a nutrient is stored in, as a label prints it. Vitamin A's micrograms are of retinol
 * activity equivalents, and a label that says "µg RAE" should be matched by a line that says so.
 */
private fun ownUnitRes(nutrient: Nutrient): Int =
    if (nutrient == Nutrient.VITAMIN_A) R.string.med_contents_unit_rae else nutrient.unit.labelRes

/** An amount as this screen prints it: the locale's digits and separator, up to three decimals. */
private fun doseAmountText(amount: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 }.format(amount)

// --- the appointment pages ----------------------------------------------------------------------

/**
 * One appointment, read rather than edited: who and where, when, and what was decided about being
 * told. Nothing on it is a field.
 *
 * A diary entry is opened to check it far more often than to change it, and the two things that do
 * change it — the correction and the cancellation — are buttons at the end, where a stray tap while
 * scrolling cannot reach them. "I have been" stands above both because it is the one action with a
 * consequence elsewhere: it is what winds the clock on the matching routine examination.
 */
@Composable
private fun VisitViewPage(
    modifier: Modifier,
    appointment: Appointment,
    zone: ZoneId,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val at = Instant.ofEpochMilli(appointment.atMs).atZone(zone)
    val unset = stringResource(R.string.visit_not_set)
    val rows = listOf(
        Triple(stringResource(R.string.visit_place), appointment.place, Icons.Outlined.Place),
        Triple(
            stringResource(R.string.visit_date_label),
            at.toLocalDate().formatLongDay(),
            Icons.Outlined.CalendarMonth,
        ),
        Triple(
            stringResource(R.string.visit_time),
            clockText(at.hour * 60 + at.minute),
            Icons.Outlined.Schedule,
        ),
        Triple(
            stringResource(R.string.visit_remind),
            remindText(appointment.remindDaysBefore),
            Icons.Outlined.NotificationsNone,
        ),
        Triple(
            stringResource(R.string.visit_note),
            appointment.note,
            Icons.AutoMirrored.Outlined.Notes,
        ),
    )
    Column(
        modifier.panelVerticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PwnTopBar(stringResource(R.string.visit_edit_title), onBack)
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PwnCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Image(
                            themePainterResource(specialtyArt(appointment.specialty)),
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                specialtyLabel(appointment.specialty),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                appointment.doctor.ifBlank { unset },
                                color = PwnMuted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    rows.forEachIndexed { index, (label, value, icon) ->
                        if (index > 0) HorizontalDivider(color = PwnBorder)
                        VisitFactRow(label, value.ifBlank { unset }, icon)
                    }
                }
            }
            if (appointment.doneAt == null) {
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                    shape = CircleShape,
                ) { Text(stringResource(R.string.visit_done)) }
            }
            // Half a screen each, and "Отменить запись" is fifteen characters. The labels wrap
            // rather than end in an ellipsis — a destructive action is never shown as a fragment —
            // and the buttons have a floor rather than a height, so a second line makes them taller
            // instead of being cut; the row's intrinsic height keeps the pair one height when only
            // one of them wraps. Both keep their outline glyphs and stay at 18dp: the words carry
            // the meaning here, and a drawn bin beside one of them would shout an action that a
            // cancelled appointment does not deserve.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = PwnDimensions.mediumButtonHeight),
                    contentPadding = HALF_BUTTON_PADDING,
                ) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.med_edit), textAlign = TextAlign.Center)
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = PwnDimensions.mediumButtonHeight),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    contentPadding = HALF_BUTTON_PADDING,
                ) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.visit_cancel), textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One fact of an appointment: what it is on the left, what it says on the right. */
@Composable
private fun VisitFactRow(label: String, value: String, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = PwnMuted, style = MaterialTheme.typography.bodyMedium)
        // In full, however many lines it takes: this page is where an appointment is read, and a
        // note cut at its second line could not be read anywhere else short of editing it.
        Text(
            value,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
        Icon(icon, null, tint = PwnMuted, modifier = Modifier.size(20.dp))
    }
}

/**
 * Booking one, or correcting one: numbered steps, one card each.
 *
 * The steps are the decisions in the order they get made — where and who, then when, then when to
 * be told, then anything to remember to bring. Numbering them is not decoration: a single
 * column of eleven controls would give the note the same weight as the date.
 *
 * The place and the doctor are buttons rather than text fields, because the answer is usually one
 * already given: both open a page that lists what has been typed before, with room to type a new
 * one. Nobody should spell out a clinic address twice. The place's own location button opens that
 * page already asking for the nearest places, [onPickPlaceNear], and is offered whether or not the
 * list of places can be read: read here only to decide that, the list would be on the heap from the
 * moment the form opened, and the Place page shows its magnifier and asks nothing when it cannot. A
 * booking asks for the place first, then the kind of doctor, then the doctor, all three required,
 * and Save says which is still missing ([visitMissing]).
 */
@Composable
private fun VisitPage(
    modifier: Modifier,
    form: VisitFormState,
    profile: UserProfile,
    today: LocalDate,
    zone: ZoneId,
    onBack: () -> Unit,
    onDelete: (() -> Unit)?,
    onPickDoctor: () -> Unit,
    onPickPlace: () -> Unit,
    onPickPlaceNear: () -> Unit,
    onPickRemind: () -> Unit,
    onSave: () -> Unit,
) {
    var pickingDay by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    // The examinations that apply first, in their own order, then every kind of doctor in the
    // order of the words the person reads, then the catch-all.
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val specialties = remember(profile, today, form.specialty, resources, locale) {
        visitChoices(
            applicable = checkupsFor(profile.sex, ageOn(today.toString(), profile.birthDate)),
            current = form.specialty,
            label = { resources.getString(specialtyTitleRes(it)) },
            order = Collator.getInstance(locale),
        )
    }
    val searchAlso = remember(specialties, resources) {
        specialties.map { visitKindOf(it)?.searchRes?.let(resources::getString).orEmpty() }
    }
    // The location button checks the permission and the switch before the Place page opens, and
    // says which one is missing rather than opening it to a grey line.
    val pickNear = rememberLocationGate(onReady = onPickPlaceNear)
    Column(
        modifier.panelVerticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PwnTopBar(
            title = stringResource(
                when {
                    // Writing one down is not booking one, and the page says which it is doing.
                    form.recording && form.editingId == 0L -> R.string.checkup_mark

                    form.editingId == 0L -> R.string.visit_add

                    else -> R.string.visit_edit_title
                },
            ),
            onBack = onBack,
            content = medDeleteAction(onDelete),
        )
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Confirming a booking rather than making one: everything already agreed is shown as
            // it stands and cannot be re-typed here. Correcting it is what Edit is for.
            val settled = form.recording && form.editingId != 0L
            // Asked for only where they can be given: a booking being confirmed shows them
            // read-only, so one saved without them can still be marked as done.
            val missing = if (settled) null else visitMissing(form.place, form.specialty, form.doctor, form.recording)
            if (settled) {
                StepCard(1, stringResource(R.string.visit_details)) {
                    SettledVisit(form, zone)
                }
            } else {
                // The place first, then who at it: a doctor is booked at a place, and the place is
                // what the suggestions for the doctor are drawn from. The kind of doctor opens
                // unanswered on a new booking; once chosen it narrows the place suggestions too.
                StepCard(1, stringResource(R.string.visit_details)) {
                    PickField(
                        label = stringResource(R.string.visit_place),
                        value = form.place,
                        onClear = { form.place = "" },
                        onClick = onPickPlace,
                        onNearMe = pickNear,
                    )
                    val chosen = specialties.indexOf(form.specialty)
                    PwnChoiceField(
                        label = stringResource(R.string.visit_kind_field),
                        options = specialties.map { specialtyLabel(it) },
                        // A word from a newer build reads as another doctor, as everywhere else.
                        selected = when {
                            chosen >= 0 -> chosen
                            form.specialty.isBlank() -> -1
                            else -> specialties.indexOf(VISIT_SPECIALTY_OTHER)
                        },
                        onSelected = { form.specialty = specialties[it] },
                        searchLabel = stringResource(R.string.visit_kind_search),
                        searchAlso = searchAlso,
                        placeholder = stringResource(R.string.visit_not_set),
                    )
                    PickField(
                        label = stringResource(R.string.visit_doctor),
                        value = form.doctor,
                        onClear = { form.doctor = "" },
                        onClick = onPickDoctor,
                    )
                }
                // The date and the time in a block of their own, under the who and where: stacked
                // at the end of the first card they read as two more names.
                StepCard(2, stringResource(R.string.visit_when)) {
                    FieldButton(
                        label = stringResource(R.string.visit_date_label),
                        value = form.day.formatLongDay(),
                        icon = Icons.Outlined.CalendarMonth,
                        onClick = { pickingDay = true },
                    )
                    FieldButton(
                        label = stringResource(R.string.visit_time),
                        value = clockText(form.minute),
                        icon = Icons.Outlined.Schedule,
                        onClick = { pickingTime = true },
                    )
                }
            }
            // A booking being confirmed shows its date read-only in the first card, so it has one
            // step fewer than a form that asks for it.
            val dated = if (settled) 1 else 2
            if (!form.recording) {
                StepCard(dated + 1, stringResource(R.string.visit_remind)) {
                    val on = form.day.minusDays(form.remindDaysBefore.toLong()).formatLongDay()
                    FieldButton(
                        label = stringResource(R.string.visit_remind_on, on),
                        value = remindText(form.remindDaysBefore),
                        icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        onClick = onPickRemind,
                    )
                }
            }
            // Booking one, the note is a reminder to oneself; writing one down afterwards, it is
            // what was said. Nothing to say about the future in the second case, or the past in
            // the first — so the step is numbered for the list it is actually in.
            val noteStep = if (form.recording) dated + 1 else dated + 2
            val noteTitle = stringResource(
                if (form.recording) R.string.visit_note_title else R.string.med_note_label,
            )
            StepCard(noteStep, noteTitle) {
                CountedField(
                    value = form.note,
                    onValue = { form.note = it.take(MED_NOTE_MAX) },
                    label = null,
                    max = MED_NOTE_MAX,
                    singleLine = false,
                    description = noteTitle,
                )
            }
            StepCard(noteStep + 1, stringResource(R.string.attachments_title)) {
                AttachmentsField(form.attachments, showTitle = false) { form.attachments = it }
            }
            // A past date on a booking is usually a slip, and worth saying so. On a visit being
            // written down it is the whole point, so the line stays out of the way.
            if (!form.recording && form.toAppointment(zone).atMs < System.currentTimeMillis()) {
                Text(
                    stringResource(R.string.visit_past),
                    color = PwnWarn,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = onSave,
                enabled = missing == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                shape = CircleShape,
            ) { Text(stringResource(R.string.common_save)) }
            // Save off says why, in one line, as the pain page's does: a grey button alone reads
            // as one that does nothing.
            missing?.let {
                Text(
                    stringResource(
                        when (it) {
                            VisitMissing.PLACE -> R.string.visit_save_needs_place
                            VisitMissing.KIND -> R.string.visit_save_needs_kind
                            VisitMissing.DOCTOR -> R.string.visit_save_needs_doctor
                        },
                    ),
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (pickingDay) {
        DayPickerDialog(form.day, onDismiss = { pickingDay = false }) {
            form.day = it
            pickingDay = false
        }
    }
    if (pickingTime) {
        ClockTimePickerDialog(
            label = stringResource(R.string.visit_time),
            initialMinuteOfDay = form.minute,
            onDismiss = { pickingTime = false },
            onConfirm = {
                form.minute = it
                pickingTime = false
            },
        )
    }
}

/**
 * One numbered step of the booking form: the header and whatever the step asks for. [help], when
 * given, stands at the end of the header — the "?" of a step that has something to explain.
 */
@Composable
internal fun StepCard(
    number: Int,
    title: String,
    help: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MedSectionHeader(number, title, content = help)
            content()
        }
    }
}

/**
 * A field whose value is chosen on another page: what it says now, a way to empty it, a chevron.
 *
 * The same object as [FieldButton] and [PwnChoiceField] — full width, one-dp outline, label above
 * it with an eight-dp gap, sixteen-dp inner padding, the [PwnCorners.medium] corner — because the
 * booking form stacks this straight on top of a [FieldButton], and the medicine form stacks that
 * one straight underneath a [PwnChoiceField]. Two radii a step apart in one column read as a slip,
 * not as a distinction. The label is spoken by the button rather than on its own, as in [FieldButton].
 *
 * The cross names the field it empties. The doctor and the place sit one above the other, and two
 * buttons both called "Delete" on one page would leave a screen reader guessing which was which.
 *
 * [onNearMe], when given, puts the location button in the chevron's corner: a second way into the
 * same page, which opens it already asking for the nearest places. It says on its own that the
 * field opens something, so the chevron makes way for it.
 */
@Composable
internal fun PickField(
    label: String,
    value: String,
    onClear: () -> Unit,
    onClick: () -> Unit,
    onNearMe: (() -> Unit)? = null,
) {
    val shown = value.ifBlank { stringResource(R.string.visit_not_set) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label, Modifier.clearAndSetSemantics {})
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().semantics {
                role = Role.Button
                contentDescription = "$label, $shown"
            },
            shape = FieldShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    shown,
                    Modifier.weight(1f),
                    color = if (value.isBlank()) PwnMuted else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (value.isNotBlank()) {
                    IconButton(
                        onClick = onClear,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            stringResource(R.string.med_contents_remove, label),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (onNearMe != null) {
                    IconButton(
                        onClick = onNearMe,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                    ) {
                        Icon(Icons.Outlined.MyLocation, stringResource(R.string.facility_near_me), Modifier.size(20.dp))
                    }
                } else {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = PwnMuted)
                }
            }
        }
    }
}

/**
 * Choosing a doctor or a place: what has been typed before, and room to type something new.
 *
 * There is no directory of doctors to search — this app has never asked the network for anything,
 * and the registers of places name no doctors — so for a doctor the list is the person's own
 * history, with the doctors already seen at the visit's place first ([saved] is the two lists from
 * [typedBefore]). That is also the list that matters: the second visit to a clinic should not cost
 * the same typing as the first. A place has a directory as well, shipped inside the app:
 * [FacilityFinder] adds the places of care from official registers under the person's own, and the
 * location button at the end of the field lists the nearest ones that see a [specialty] doctor.
 * [askNearOnOpen] is the form's own location button: the page opens already asking, once, and
 * [onNearAsked] says the asking has been done so a rotation does not ask again.
 */
@Composable
private fun VisitFieldPage(
    modifier: Modifier,
    place: Boolean,
    specialty: String,
    imperial: Boolean,
    saved: Pair<List<String>, List<String>>,
    query: String,
    askNearOnOpen: Boolean,
    onNearAsked: () -> Unit,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    val typed = query.trim()
    val (here, elsewhere) = remember(saved, typed) {
        fun List<String>.matching() = filter { typed.isEmpty() || it.contains(typed, ignoreCase = true) }
        saved.first.matching() to saved.second.matching()
    }
    val facilities = if (place) rememberFacilities() else null
    val near = rememberNearMe()
    // Waits for the list: asked while it is still being read, the answer could not be known, and
    // asked when it could not be read at all, the position would sort nothing.
    LaunchedEffect(facilities == null) {
        if (place && askNearOnOpen && facilities != null) {
            onNearAsked()
            if (facilities.isNotEmpty()) near.ask(typed)
        }
    }
    Column(
        modifier.panelVerticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PwnTopBar(
            stringResource(if (place) R.string.visit_place else R.string.visit_doctor),
            onBack,
            content = if (place) {
                { FacilityHelpDot() }
            } else {
                null
            },
        )
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The keyboard's own key finishes the page, as Done under the list does: a name typed
            // on a hardware keyboard, or with the button hidden under the soft one, is one key
            // from being used. Names are capitalised by word, an address as a sentence.
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text(stringResource(R.string.visit_pick_search)) },
                trailingIcon = {
                    if (place) {
                        NearMeIcon(near, facilities, typed)
                    } else {
                        Icon(Icons.Outlined.Search, null, tint = PwnMuted)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (place) KeyboardCapitalization.Sentences else KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                // With nothing typed the key only puts the keyboard away, its default: an action
                // handed to the field replaces that default, so it is called for here.
                keyboardActions = KeyboardActions(
                    onDone = { if (typed.isNotEmpty()) onPick(typed) else defaultKeyboardAction(ImeAction.Done) },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (place) {
                // The person's own places stay first; the register's go under them.
                FacilityFinder(facilities, near, query, specialty, imperial, onPick) {
                    SavedChoices(elsewhere, typed, place = true, title = null, onPick = onPick)
                }
            } else {
                SavedChoices(
                    here,
                    typed,
                    place = false,
                    title = stringResource(R.string.visit_doctors_here),
                    onPick = onPick,
                )
                SavedChoices(elsewhere, typed, place = false, title = null, onPick = onPick)
            }
            Button(
                onClick = { onPick(typed) },
                enabled = typed.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                shape = CircleShape,
            ) { Text(stringResource(R.string.common_done)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * What has been typed into appointments before, the answer most often wanted, with the one already
 * in the field ticked, under [title] when the list is one part of what the page offers.
 */
@Composable
private fun SavedChoices(
    matches: List<String>,
    typed: String,
    place: Boolean,
    title: String?,
    onPick: (String) -> Unit,
) {
    if (matches.isNotEmpty()) {
        PwnCard(Modifier.fillMaxWidth()) {
            // One of these is the answer, and the tick marks it; selectable says so to a
            // screen reader, which hears the tick alone as nothing at all.
            Column(Modifier.padding(horizontal = 16.dp).selectableGroup()) {
                title?.let {
                    Text(
                        it,
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = PwnMuted,
                    )
                }
                matches.forEachIndexed { index, name ->
                    if (index > 0) HorizontalDivider(color = PwnBorder)
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = name == typed, role = Role.RadioButton) { onPick(name) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PwnGlyph(
                            if (place) R.drawable.ic_art_place else R.drawable.ic_art_profile,
                            if (place) Icons.Outlined.Place else Icons.Outlined.PersonOutline,
                            PwnIndigoSoft,
                            null,
                            Modifier.size(40.dp),
                        )
                        // In full: two clinics that share a long prefix are the choice
                        // this page exists to make, and one line would cut them to the same
                        // words at a large font. The row is centred and grows.
                        Text(
                            name,
                            Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (name == typed) SelectedTick()
                    }
                }
            }
        }
    }
}

/**
 * When to be told, as three dated choices rather than three fragments of a sentence.
 *
 * Each line says which day the reminder falls on, because "3 days before" and "the 21st" are the
 * same fact and only one of them can be checked against a calendar.
 */
@Composable
private fun RemindPage(
    modifier: Modifier,
    day: LocalDate,
    selected: Int,
    onBack: () -> Unit,
    onPick: (Int) -> Unit,
) {
    Column(
        modifier.panelVerticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PwnTopBar(stringResource(R.string.visit_remind), onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            PwnCard(Modifier.fillMaxWidth()) {
                // Three answers to one question: a group of radio buttons to a screen reader, with
                // the chosen one said to be chosen rather than only ticked.
                Column(Modifier.padding(horizontal = 16.dp).selectableGroup()) {
                    VISIT_REMIND_CHOICES.forEachIndexed { index, days ->
                        if (index > 0) HorizontalDivider(color = PwnBorder)
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = days == selected, role = Role.RadioButton) { onPick(days) }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(Icons.Outlined.CalendarMonth, null, tint = PwnMuted)
                            Column(Modifier.weight(1f)) {
                                Text(remindText(days), fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(
                                        R.string.visit_remind_on,
                                        day.minusDays(days.toLong()).formatLongDay(),
                                    ),
                                    color = PwnMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (days == selected) SelectedTick()
                        }
                    }
                }
            }
        }
    }
}

/** The filled tick that marks the chosen row of a list of choices. */
@Composable
internal fun SelectedTick() {
    Surface(Modifier.size(24.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.Check,
                null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** "on the day" or "1 day before", the same phrase the reminder itself was chosen by. */
@Composable
private fun remindText(days: Int): String = if (days == 0) {
    stringResource(R.string.visit_remind_same_day)
} else {
    pluralStringResource(R.plurals.visit_remind_days, days, days)
}

/**
 * The drawn set has a picture for every specialty the app screens for, so the row keeps the thing
 * that tells a dentist from an optician and gains the rest of the app's hand. Of the other kinds of
 * doctor, those the set already draws an organ or a subject for take it; the rest share the
 * doctor's own picture rather than one drawn for somebody else.
 */
internal fun specialtyArt(specialty: String): Int = SPECIALTY_ART[specialty] ?: R.drawable.ic_art_doctor

private val SPECIALTY_ART: Map<String, Int> = mapOf(
    Checkup.GP.name to R.drawable.ic_art_doctor,
    Checkup.DENTIST.name to R.drawable.ic_art_dentist,
    Checkup.EYES.name to R.drawable.ic_art_eye,
    Checkup.GYNECOLOGIST.name to R.drawable.ic_art_gyn,
    Checkup.CERVICAL.name to R.drawable.ic_art_cytology,
    Checkup.MAMMOGRAPHY.name to R.drawable.ic_art_mammography,
    Checkup.UROLOGIST.name to R.drawable.ic_art_urology,
    Checkup.BOWEL.name to R.drawable.ic_art_colonoscopy,
    VisitKind.CARDIOLOGIST.stored to R.drawable.ic_art_pulse,
    VisitKind.PULMONOLOGIST.stored to R.drawable.ic_art_lungs,
    VisitKind.LABORATORY.stored to R.drawable.ic_art_labs,
    VisitKind.SOMNOLOGIST.stored to R.drawable.ic_art_sleep,
    VisitKind.PSYCHIATRIST.stored to R.drawable.ic_art_mood,
    VisitKind.PSYCHOLOGIST.stored to R.drawable.ic_art_mood,
    VisitKind.DIETITIAN.stored to R.drawable.ic_art_food,
    VisitKind.SPORTS.stored to R.drawable.ic_art_sport,
)

// --- the examination page -----------------------------------------------------------------------

/**
 * One routine examination: the interval its guidance names, where today stands in it, and the two
 * things that can be done about it.
 *
 * The bar is the interval, not a score: full means it has run out. It reads as a bar because "how
 * much of the year has gone" is the question, and a date on its own does not answer that at a glance.
 */
@Composable
private fun ScreeningPage(
    modifier: Modifier,
    status: CheckupStatus?,
    checkup: Checkup,
    visits: List<Appointment>,
    zone: ZoneId,
    onOpenVisit: (Appointment) -> Unit,
    onAllVisits: () -> Unit,
    onBack: () -> Unit,
    onMark: () -> Unit,
    onBook: () -> Unit,
) {
    Column(
        modifier.panelVerticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val title = stringResource(R.string.checkup_screen_title)
        // What the page holds and that none of it is a diagnosis, in the bar rather than under
        // the buttons.
        PwnTopBar(title, onBack) { HelpDot(title) { Text(stringResource(R.string.checkup_body)) } }
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PwnCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Image(
                            themePainterResource(specialtyArt(checkup.name)),
                            contentDescription = null,
                            modifier = Modifier.size(46.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(checkup.titleRes),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                intervalText(checkup),
                                color = PwnMuted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    status?.let {
                        // Amber is for a date that has passed. With no visit on record nobody is
                        // behind on anything — the line under this one says so — and a warning
                        // colour over "Due" would be the app scolding a blank page.
                        Text(
                            checkupStatusText(it),
                            color = if (it.overdue) PwnWarn else PwnMuted,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // The bar says what the line above it says: the warning amber once the
                        // interval has run out, where the tertiary would be one more blue
                        // beside the on-time bar and say nothing about being late. The track
                        // is drawn from the outline so the whole interval shows; the filled part
                        // still stands 3.27:1 off that track by day and 3.80:1 at night in amber,
                        // 3.53:1 and 4.04:1 in the primary.
                        LinearProgressIndicator(
                            progress = { it.elapsedShare.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (it.overdue) PwnWarn else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        )
                        Text(
                            it.lastDay?.let { day ->
                                stringResource(R.string.checkup_last, day.formatLongDay())
                            } ?: stringResource(R.string.checkup_never),
                            color = PwnMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        it.dueOn?.let { due ->
                            Text(
                                stringResource(R.string.checkup_next, due.formatLongDay()),
                                color = PwnMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.visit_recent),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = onAllVisits,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                ) {
                    Icon(Icons.Outlined.History, stringResource(R.string.visit_show_all))
                }
            }
            if (visits.isEmpty()) Text(stringResource(R.string.visit_history_empty), color = PwnMuted)
            visits.take(3).forEach { appointment ->
                PwnCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { VisitRow(appointment, zone, onOpenVisit) }
                }
            }
            // Both at the Medium button's height, as floors: two full-width buttons stacked with
            // nothing between them have to match, and at a large font scale the label wants more
            // room than a fixed height would leave, so the button grows instead of cutting it.
            Button(
                onClick = onMark,
                modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                shape = CircleShape,
            ) { Text(stringResource(R.string.checkup_mark)) }
            OutlinedButton(
                onClick = onBook,
                modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                shape = CircleShape,
            ) {
                Text(stringResource(R.string.checkup_book))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// --- shared pieces ------------------------------------------------------------------------------

/**
 * What an existing row puts at the right of its [PwnTopBar]: the one destructive action it
 * carries, and nothing on a row still being written. That action is the drawn bin, which wears no
 * error red: the picture is painted and takes no tint, and the confirmation behind it is where the
 * warning colour works.
 */
internal fun medDeleteAction(onDelete: (() -> Unit)?): (@Composable () -> Unit)? {
    if (onDelete == null) return null
    return {
        IconButton(onClick = onDelete) {
            Image(
                themePainterResource(R.drawable.ic_art_delete),
                stringResource(R.string.common_delete),
                Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = PwnMuted)
}

/**
 * The edge an assist chip wears here: `outline`, the edge [PwnFilterChip] and the field buttons
 * carry, rather than Material's default outlineVariant, a tonal hairline that all but vanishes on
 * the page and the cards.
 */
@Composable
private fun chipEdge(): BorderStroke =
    AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MaterialTheme.colorScheme.outline)

/**
 * "Add a time", "Add a nutrient": the plus is a drawn glyph a screen reader passes over, not a
 * typed character it reads out as "plus" in front of the words.
 */
@Composable
private fun AddTextButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

/**
 * A text field that shows how much of its room is left, the way the meal note does.
 *
 * [label] is null where the card's own header already says what the field is — a floating "Note"
 * inside a card titled "Note (optional)" is the same word twice. The header is a node of its own,
 * though, so a field without a label is handed that header as [description]: otherwise a screen
 * reader landing on it says only the counter and "edit box".
 *
 * A one-line field's keyboard key moves on to the next field, so a form can be filled in without
 * reaching for the screen between fields; a note keeps its return key for new lines.
 */
@Composable
internal fun CountedField(
    value: String,
    onValue: (String) -> Unit,
    label: String?,
    max: Int,
    singleLine: Boolean = true,
    description: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = label?.let { text -> { Text(text) } },
        suffix = {
            Text(
                stringResource(R.string.note_counter, value.length, max),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        modifier = Modifier.fillMaxWidth().then(
            if (description == null) Modifier else Modifier.semantics { contentDescription = description },
        ),
    )
}

/**
 * The shape both field-shaped buttons are drawn in: the corner [PwnChoiceField] uses, so a date
 * under a choice in the same column reads as the same kind of object.
 */
private val FieldShape = RoundedCornerShape(PwnCorners.medium)

/**
 * A field-shaped button: what a date or a time looks like until it is tapped, shaped as [PickField].
 *
 * The label above it is visible text but not a node of its own: the button says it, with the
 * value, so a screen reader or Switch Access lands on one thing that names the field it sets
 * rather than on a bare date. The Surface draws the ripple, which is what keeps the pressed state
 * inside the rounded outline.
 */
@Composable
internal fun FieldButton(label: String, value: String, icon: ImageVector, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label, Modifier.clearAndSetSemantics {})
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().semantics {
                role = Role.Button
                contentDescription = "$label, $value"
            },
            shape = FieldShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, Modifier.weight(1f))
                Icon(icon, null, tint = PwnMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Deleting something a person typed: what goes, what stays, and the red button last. The mark above
 * the question stays an outline glyph on purpose. It is the one thing here that can be tinted, so
 * it holds the same error red as the button underneath it, and that shared red is what tells the
 * two apart from the cancel beside them; a drawn bin would break the pair and, sitting a finger's
 * width above the word "Delete", say the same thing twice.
 */
@Composable
internal fun DestructiveDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.DeleteOutline,
                null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * "раз в год" for the commonest interval there is, "раз в 2 года" for the rest of the whole years,
 * and months where the interval does not divide into them.
 *
 * A year has its own string rather than a plural with the figure in it, because every language has
 * a shorter way to say "once a year" than "once every 1 year" — and a plural whose `one` form omits
 * the number is exactly what Lint's ImpliedQuantity forbids.
 */
@Composable
private fun intervalText(checkup: Checkup): String {
    val months = checkup.everyMonths
    val years = months / 12
    return when {
        months == 12 -> stringResource(R.string.checkup_every_year)
        months % 12 == 0 -> pluralStringResource(R.plurals.checkup_every_years, years, years)
        else -> pluralStringResource(R.plurals.checkup_every_months, months, months)
    }
}

/** The one line the summary has room for: when the next one is due, or that it already is. */
@Composable
private fun nextDueText(status: CheckupStatus): String = status.dueOn
    ?.takeIf { !status.due }
    ?.let { stringResource(R.string.checkup_next, it.formatLongDay()) }
    ?: checkupStatusText(status)

@Composable
private fun checkupStatusText(status: CheckupStatus): String {
    val left = status.daysLeft
    return when {
        // Nothing on record, or the day itself: "overdue by 0 days" is a quantity of nothing.
        left == null || left == 0L -> stringResource(R.string.checkup_due_now)

        left < 0 -> pluralStringResource(R.plurals.checkup_overdue, (-left).toInt(), (-left).toInt())

        else -> pluralStringResource(R.plurals.checkup_due_in, left.toInt(), left.toInt())
    }
}

@Composable
internal fun specialtyLabel(specialty: String): String = stringResource(specialtyTitleRes(specialty))

/**
 * "17 сентября 2026, чт" — the date as a person reads it, in the order the language on screen writes
 * one: "Thu, September 17, 2026" in American English, the year first in Japanese.
 */
@Composable
internal fun LocalDate.formatLongDay(): String {
    val locale = LocalLocale.current.platformLocale
    return format(remember(locale) { localizedDatePattern(locale, "yMMMMEEEd", "d MMMM yyyy, EEE") })
}

/** A day in the past or the future: a course can have started last week and end next month. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPickerDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = rememberToday()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toPickerMillis(),
        // Two years back and five ahead covers any course being planned, but the day this opens on
        // has to be inside the range whatever it is: given an initial date outside it the picker
        // shows nothing and OK never lights, and a long-term prescription started three years ago
        // is exactly the row someone comes back to correct.
        yearRange = minOf(initial.year, today.year - 2)..maxOf(initial.year, today.year + 5),
    )
    LocalDateDialog(state, onDismiss, onPick)
}

/**
 * Where the "add a time" wheel opens: nine in the morning, the commonest first dose there is — the chat's one-a-day
 * proposal too.
 */
internal const val MED_NEW_TIME_MINUTE = 9 * 60

/** Where a new appointment's time starts: ten in the morning, a surgery hour almost anywhere. */
private const val VISIT_DEFAULT_MINUTE = 10 * 60

/** The three answers to "when should I be told": on the day, the day before, three days before. */
private val VISIT_REMIND_CHOICES = listOf(0, 1, 3)

/**
 * The course form's fields, held together so the pages can open, prefill and read them back.
 *
 * A holder rather than a dozen loose `var`s: the form is opened from two places (the add button and
 * a course row), and the round trip to a [Medication] is the part worth having in one place rather
 * than spread across a save handler.
 *
 * The [Saver] is what lets a half-typed course survive the rotation that comes of turning the
 * phone to read the small print on a box — ten fields is real work to lose.
 */
internal class CourseFormState(today: LocalDate) {
    var editingId by mutableLongStateOf(0L)
    var name by mutableStateOf("")
    var dose by mutableStateOf("")
    var schedule by mutableStateOf(MedSchedule.CLOCK)
    var times by mutableStateOf(emptyList<Int>())
    var food by mutableStateOf(MedFood.ANY)
    var start by mutableStateOf(today)
    var end by mutableStateOf<LocalDate?>(null)
    var note by mutableStateOf("")
    var attachments by mutableStateOf("")
    var contents by mutableStateOf(emptyList<ContentLine>())

    /** Whatever the stored contents named that this build cannot, written back as it came. */
    private var unnamedContents = ""

    /** Whether "what one dose holds" is unfolded: from the start on a course that already says. */
    var contentsOpen by mutableStateOf(false)
    private var createdAt = 0L

    /** The kept photograph the course will go on carrying, or empty for none. */
    var photo by mutableStateOf("")

    /** A fresh shot is waiting in the cache ([pendingMedicinePhoto]) and takes [photo]'s place on save. */
    var pendingPhoto by mutableStateOf(false)

    /** Counts the shots, so one taken over another under the same cache name is drawn again. */
    var photoVersion by mutableIntStateOf(0)

    fun photoTaken() {
        pendingPhoto = true
        photoVersion += 1
    }

    fun removePhoto() {
        photo = ""
        pendingPhoto = false
    }

    // A course that ends before it starts covers no day at all: it would sit in the list and never
    // ask for a dose, on Today or in the chart, with nothing on screen to say why.
    val valid: Boolean
        get() = name.isNotBlank() &&
            (schedule == MedSchedule.WAKE || times.isNotEmpty()) &&
            end.let { it == null || it >= start }

    /**
     * How many times the form has been opened ([show]). A save that lands after the photograph's
     * move closes the page only while the form is still the opening it was tapped in: by then
     * another course, or a new one, may be open on it, and closing that one would throw its edits away.
     * Not saved across a rotation: the composition the save was tapped in is gone by then anyway.
     */
    var openings = 0
        private set

    fun show(medication: Medication?, today: LocalDate) {
        openings += 1
        editingId = medication?.id ?: 0L
        name = medication?.name.orEmpty()
        dose = medication?.dose.orEmpty()
        schedule = medication?.scheduleKind() ?: MedSchedule.CLOCK
        times = medication?.doseMinutes().orEmpty()
        food = medication?.foodRelation() ?: MedFood.ANY
        start = medication?.startDay?.let { it.toLocalDateOrNull() } ?: today
        end = medication?.endDay?.let { it.toLocalDateOrNull() }
        note = medication?.note.orEmpty()
        attachments = medication?.attachments.orEmpty()
        contents = contentLinesOf(medication?.contents.orEmpty())
        unnamedContents = unnamedContentsOf(medication?.contents.orEmpty())
        contentsOpen = contents.isNotEmpty()
        createdAt = medication?.createdAt ?: 0L
        photo = medication?.photo.orEmpty()
        // A shot left waiting by a form that was never saved belongs to nobody; this one starts
        // from what the course itself carries.
        pendingPhoto = false
    }

    /** A preset's lines added below the ones typed, and its name given to a course that has none yet. */
    fun addPreset(preset: SupplementPreset, title: String) {
        contents = contents.withPreset(preset)
        if (name.isBlank()) name = title.take(MED_NAME_MAX)
    }

    /** The row the form describes, with the picture it is showing; a save gives it the name it settles on. */
    fun toMedication() = medicationOf(
        CoursePlan(name, dose, schedule, times, food, start, end, note),
        createdAt = createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        id = editingId,
        photo = photo,
        attachments = attachments,
        contents = contentsOfLines(contents, unnamedContents),
    )

    companion object {
        /**
         * The ten fields the page shows, the row being edited and the stamp behind it, as values a
         * Bundle will take: the dates as the ISO text the row itself is stored in, the times as the
         * same comma-separated line, and an end date left open written as no text at all. The
         * contents go as the lines being typed, unit and all — see [savedContentLines] — with the
         * entries this build could not name and whether the section was unfolded. The photograph
         * goes as the kept name and whether a shot is waiting in the cache, which outlives the
         * rotation the same way the form does.
         */
        val Saver: Saver<CourseFormState, Any> = listSaver(
            save = {
                listOf<Any>(
                    it.editingId,
                    it.name,
                    it.dose,
                    it.schedule.name,
                    it.times.joinToString(","),
                    it.food.name,
                    it.start.toString(),
                    it.end?.toString().orEmpty(),
                    it.note,
                    it.attachments,
                    it.createdAt,
                    savedContentLines(it.contents),
                    it.unnamedContents,
                    it.contentsOpen,
                    it.photo,
                    it.pendingPhoto,
                )
            },
            restore = { saved ->
                CourseFormState(LocalDate.parse(saved[6] as String)).apply {
                    editingId = saved[0] as Long
                    name = saved[1] as String
                    dose = saved[2] as String
                    schedule = MedSchedule.valueOf(saved[3] as String)
                    times = (saved[4] as String).split(",").mapNotNull(String::toIntOrNull)
                    food = MedFood.valueOf(saved[5] as String)
                    end = (saved[7] as String).takeIf(String::isNotEmpty)
                        ?.let { day -> LocalDate.parse(day) }
                    note = saved[8] as String
                    attachments = saved[9] as String
                    createdAt = saved[10] as Long
                    contents = restoredContentLines(saved[11] as String)
                    unnamedContents = saved[12] as String
                    contentsOpen = saved[13] as Boolean
                    photo = saved[14] as String
                    pendingPhoto = saved[15] as Boolean
                }
            },
        )
    }
}

@Composable
private fun rememberCourseForm(today: LocalDate) =
    rememberSaveable(saver = CourseFormState.Saver) { CourseFormState(today) }

/**
 * The same for an appointment. [specialty] is what [Appointment.specialty] stores — a [VisitKind]'s
 * word, a [Checkup] name or [VISIT_SPECIALTY_OTHER] — or [VISIT_SPECIALTY_UNANSWERED] on a new
 * booking whose kind has not been chosen yet.
 */
private class VisitFormState(today: LocalDate) {
    var editingId by mutableLongStateOf(0L)
    var specialty by mutableStateOf(VISIT_SPECIALTY_UNANSWERED)
    var doctor by mutableStateOf("")
    var place by mutableStateOf("")
    var day by mutableStateOf(today)
    var minute by mutableIntStateOf(VISIT_DEFAULT_MINUTE)
    var remindDaysBefore by mutableIntStateOf(1)
    var note by mutableStateOf("")
    var attachments by mutableStateOf("")

    /**
     * True when the form was opened to write down a visit that already happened rather than to
     * book one. The same fields either way — a date, a note, whatever came home with it — but a
     * past visit needs no reminder, and its note is what the doctor said rather than what to
     * remember to ask.
     */
    var recording by mutableStateOf(false)

    /**
     * When the visit being corrected was marked done, which a correction keeps. The reminder's mark
     * is not kept here: it is read from the row at the save ([visitSavedOver]).
     */
    private var markedDoneAt: Long? = null

    fun show(appointment: Appointment?, specialty: String, today: LocalDate, recording: Boolean = false) {
        markedDoneAt = appointment?.doneAt
        editingId = appointment?.id ?: 0L
        this.recording = recording || appointment?.doneAt != null
        this.specialty = specialty
        doctor = appointment?.doctor.orEmpty()
        place = appointment?.place.orEmpty()
        // Outside the `let` the last appointment opened would still be the date a new one defaults
        // to, and a form filled in without looking at it books the visit on the old day.
        val at = appointment?.atMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        day = at?.toLocalDate() ?: today
        minute = at?.let { it.hour * 60 + it.minute } ?: VISIT_DEFAULT_MINUTE
        remindDaysBefore = appointment?.remindDaysBefore ?: 1
        note = appointment?.note.orEmpty()
        attachments = appointment?.attachments.orEmpty()
    }

    fun toAppointment(zone: ZoneId): Appointment {
        // The wall time that was picked, not midnight plus a span of minutes: on the two days a
        // year the clocks move, the two are an hour apart.
        val atMs = day.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
        return Appointment(
            id = editingId,
            specialty = specialty,
            doctor = doctor.trim(),
            place = place.trim(),
            atMs = atMs,
            note = note.trim(),
            remindDaysBefore = remindDaysBefore,
            // A visit being written down after the fact is done by definition: it is marked at
            // the moment it says it happened, not at the moment it was typed.
            doneAt = markedDoneAt ?: atMs.takeIf { recording },
            attachments = attachments,
        )
    }

    companion object {
        /**
         * The form as a row of values a Bundle will take. Zero stands for a visit never marked
         * done, which no reading of a millisecond clock has ever been.
         */
        val Saver: Saver<VisitFormState, Any> = listSaver(
            save = {
                listOf<Any>(
                    it.editingId,
                    it.specialty,
                    it.doctor,
                    it.place,
                    it.day.toString(),
                    it.minute,
                    it.remindDaysBefore,
                    it.note,
                    it.attachments,
                    it.recording,
                    it.markedDoneAt ?: 0L,
                )
            },
            restore = { saved ->
                VisitFormState(LocalDate.parse(saved[4] as String)).apply {
                    editingId = saved[0] as Long
                    specialty = saved[1] as String
                    doctor = saved[2] as String
                    place = saved[3] as String
                    minute = saved[5] as Int
                    remindDaysBefore = saved[6] as Int
                    note = saved[7] as String
                    attachments = saved[8] as String
                    recording = saved[9] as Boolean
                    markedDoneAt = (saved[10] as Long).takeIf { at -> at > 0L }
                }
            },
        )
    }
}

/**
 * [edited] as it is saved over [stored], the row as it stands at the save. A rescheduled
 * appointment has not been announced yet: keeping the old mark would swallow the reminder for the
 * new date. An edit that moves neither the date nor the notice keeps it, so correcting a place does
 * not announce the same visit a second time — and keeps it as the row holds it at the save. The
 * form's copy of the row is the one it was opened on, and a reminder that went out while it stood
 * open would be undone by the save and come a second time.
 */
internal fun visitSavedOver(edited: Appointment, stored: Appointment?): Appointment = edited.copy(
    remindedAt = stored
        ?.takeIf { it.atMs == edited.atMs && it.remindDaysBefore == edited.remindDaysBefore }
        ?.remindedAt,
)

@Composable
private fun rememberVisitForm(today: LocalDate) =
    rememberSaveable(saver = VisitFormState.Saver) { VisitFormState(today) }

/**
 * A booking being confirmed, as it was agreed: who, where and when, in plain text.
 *
 * Read-only on purpose. Saying "I have been" is not the moment to move the appointment — the visit
 * happened at the time it was booked for, and the page's Edit is where a booking that was wrong
 * gets corrected.
 */
@Composable
private fun SettledVisit(form: VisitFormState, zone: ZoneId) {
    val at = Instant.ofEpochMilli(form.toAppointment(zone).atMs).atZone(zone)
    val rows = listOfNotNull(
        stringResource(R.string.visit_specialty) to specialtyLabel(form.specialty),
        form.place.takeIf(String::isNotBlank)?.let { stringResource(R.string.visit_place) to it },
        form.doctor.takeIf(String::isNotBlank)?.let { stringResource(R.string.visit_doctor) to it },
        stringResource(R.string.visit_date_label) to at.toLocalDate().formatLongDay(),
        stringResource(R.string.visit_time) to clockText(form.minute),
    )
    // Laid out as the appointment page's facts are: the label at its own width, the value taking
    // the rest and wrapping at the end edge. Were the value measured first on one line, a long
    // clinic name would take the whole row and squeeze the label beside it out of sight.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(label, color = PwnMuted, style = MaterialTheme.typography.bodyMedium)
                Text(
                    value,
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
