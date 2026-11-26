package com.pwnall.health

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Adding lab results, four pages long: what was tested, how it comes in, the entry itself, and the
 * page that says it is saved.
 *
 * Blood and urine are figures. Their third page is the panel's form — typed in by hand, or filled
 * from a sheet read off a file or a photo — and nothing is written until its Save. It offers only the
 * chosen sample's fields: a urine sheet copied under twenty-odd blood fields would be a form asking
 * the wrong questions, and a figure typed into one of them would be saved as blood whatever the
 * sample said.
 * An ultrasound, an MRI or another document has no figures this app reads, and is kept as its file
 * and a note ([labDocumentEntry]).
 *
 * The values are typed, not measured — a lab prints them and a person copies them across, often
 * weeks after the draw, which is why the date is asked for rather than assumed. Glucose is the one
 * marker Health Connect also carries, so it is stored under Health Connect's own key and travels
 * both ways for free; the rest of the panel has no record type there and stays on the device.
 *
 * What those numbers then say is [LabResultsScreen]'s, which the last page opens: entering a sheet
 * is a visit of its own, and twenty-odd fields behind seven folds are no way into a page somebody
 * opened to read a figure.
 *
 * [storedProfile] is the profile's row as it is stored, not the one in effect that the other pages
 * count with: this page saves the allergens a panel found back into that row, and the measured
 * weight and height of the profile in effect would be written over the onboarding baseline with them.
 */
@Composable
fun LabAddScreen(
    modifier: Modifier,
    storedProfile: UserProfile,
    onBack: () -> Unit,
    onResults: () -> Unit,
    initialDocument: String? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { StepsDb.get(context) }
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.labs_saved)
    val today = rememberToday()
    // The same METRIC/IMPERIAL switch that decides kilograms or pounds sets the unit each field starts
    // in, so there is no second setting to find. It is no guide to the sheet: a British laboratory
    // prints mmol/L for people who weigh in pounds, a Spanish one mg/dL for people who weigh in
    // kilograms. A field with two units asks which one was printed (UNIT_CHOICE_MARKERS), and a sheet
    // read from a file or a photo is converted from whatever it printed.
    val imperial = storedProfile.imperial
    var status by rememberStatusLine()
    // A sheet the chat handed over is a file already being read: the flow opens on its third page,
    // with no type until the reading says which sample the sheet is.
    var step by rememberSaveable { mutableStateOf(if (initialDocument == null) LabAddStep.TYPE else LabAddStep.ENTRY) }
    // Unanswered until a row is tapped: a page resting on "blood" is an answer nobody gave.
    var type by rememberSaveable { mutableStateOf<LabAddType?>(null) }
    var method by rememberSaveable { mutableStateOf(if (initialDocument == null) null else LabAddMethod.FILE) }
    // Whether a file or a photo has been read into the form. Until one has, the third page of a file
    // or a photo is the place to choose it.
    var read by rememberSaveable { mutableStateOf(false) }
    var sampleDay by rememberSaveable { mutableStateOf(today.toString()) }
    // Off until it is switched on, as the history reads a sample that says nothing (labHistory): on,
    // a sheet copied or read in without a glance at the switch would put an ordinary result after
    // lunch under the fasting bands, and find in it a reason to see someone.
    var fasting by rememberSaveable { mutableStateOf(false) }
    var openPanel by rememberSaveable { mutableStateOf<LabPanel?>(null) }
    var attachments by rememberSaveable { mutableStateOf("") }
    // Which blood the sheet names, if it names one. Absent by default and absent when saved: a
    // specimen nobody chose is not a specimen, and the export would rather carry no code than one
    // resting on a guess — see LabSpecimen. A urine panel needs no second answer: it is urine.
    var specimen by rememberSaveable { mutableStateOf<LabSpecimen?>(null) }
    var laboratory by rememberSaveable { mutableStateOf("") }
    // A document's one file and its note: all there is to an ultrasound, an MRI or anything else.
    var document by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    // An allergy panel's rows as its file was read, held as text so a rotation keeps them; whether a
    // reading has finished; and whether the done page's offer has been answered.
    var panelRows by rememberSaveable { mutableStateOf("") }
    var panelRead by rememberSaveable { mutableStateOf(false) }
    var panelOffered by rememberSaveable { mutableStateOf(false) }
    // What the last panel's Save did — wrote figures, found them all held already, or left its file
    // with the panel that holds them: the last page says which.
    var outcome by rememberSaveable { mutableStateOf(LabSaveOutcome.ADDED) }
    val panelStatus = rememberLabImportStatus()
    val readPanel = rememberAllergyPanelReading(panelStatus) { scan ->
        panelRows = encodeAllergenRows(scan.rows)
        scan.day?.let { sampleDay = it.toString() }
        panelRead = true
    }
    val reportedLow = rememberTypedLabs()
    val reportedHigh = rememberTypedLabs()
    var pickingDate by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Kept past a rebuilt activity, with the reading it reports on, as the allergy panel's above is:
    // see rememberLabImportStatus.
    val importStatus = rememberLabImportStatus()
    // A scroll of its own for each page: each opens at its top, and after a rotation comes back
    // where it was left.
    val scroll = key(step) { rememberScrollState() }
    val typed = rememberTypedLabs()
    // The unit picked on each field that offers two, by UCUM spelling. Unpicked, a field offers the
    // one the last panel recorded for it, and the profile's where none has — see lastTypedUnits.
    val unitPicks = rememberTypedLabs()
    val lastUnits by remember(today) {
        db.dao().journalBetween(labWindowStart(today), "9999-12-31")
            .map(::lastTypedUnits)
            .flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(emptyMap())
    fun unitOf(marker: LabMarker): LabImperial? = marker.fieldUnit(unitPicks[marker] ?: lastUnits[marker], imperial)

    val sample = type?.sample
    val values = labFormValues(sample, typed, ::unitOf)
    val drawnOn = sampleDay.toLocalDateOrNull() ?: today
    val existing = typed.filterValues { it.isNotBlank() }.keys
    val fill: (LabImportDraft) -> Unit = { draft ->
        val scan = draft.scan
        scan.day?.let { sampleDay = it.toString() }
        attachments = addMedicalAttachments(attachments, draft.sources)
        val added = scan.readings.filter { !it.marker.derived && typed[it.marker].isNullOrBlank() }
        added.forEach { reading ->
            val marker = reading.marker
            unitPicks[marker] = reading.printedUnit ?: marker.ucum.ucum
            val unit = unitOf(marker)
            typed[marker] = labImportNumber(reading.value, unit)
            reportedLow[marker] = reading.range.low?.let { labImportNumber(it, unit) }.orEmpty()
            reportedHigh[marker] = reading.range.high?.let { labImportNumber(it, unit) }.orEmpty()
        }
        added.firstOrNull()?.marker?.panel?.let { panel ->
            type = panel.sample.addType()
            openPanel = panel
            scope.launch { scroll.animateScrollTo(0) }
        }
        // Read, whatever it found: the file hangs on the form now, and the import's own lines say what
        // came off it. A sheet that named no sample, handed over before one was chosen, asks for one.
        read = true
        if (type == null) step = LabAddStep.TYPE
    }
    val import = rememberLabImport(
        importStatus,
        existing,
        drawnOn,
        initialSources = listOfNotNull(initialDocument),
        onFill = fill,
    )

    fun leaveStep() {
        when (step) {
            LabAddStep.METHOD -> step = LabAddStep.TYPE

            // A reading under way stops with the page it was started from.
            LabAddStep.ENTRY -> {
                importStatus.job?.cancel()
                panelStatus.job?.cancel()
                step = if (type == null) LabAddStep.TYPE else LabAddStep.METHOD
            }

            else -> onBack()
        }
    }
    // Back walks the pages, as the arrow does. From the first page it leaves, and from the last too:
    // what that page says is saved, and the form behind it is empty.
    BackHandler(enabled = step == LabAddStep.METHOD || step == LabAddStep.ENTRY) { leaveStep() }

    fun startOver() {
        type = null
        method = null
        read = false
        sampleDay = today.toString()
        fasting = false
        specimen = null
        laboratory = ""
        attachments = ""
        document = null
        note = ""
        panelRows = ""
        panelRead = false
        panelOffered = false
        outcome = LabSaveOutcome.ADDED
        panelStatus.error = false
        openPanel = null
        importStatus.lastScan = null
        importStatus.error = false
        step = LabAddStep.TYPE
    }

    fun savePanel(chosen: LabSample) {
        // Read now, as the form stands at the tap; written once the day's panels have been looked at.
        val day = drawnOn
        val panelFasting = chosen == LabSample.BLOOD && fasting
        val sheet = attachments
        val sampled = if (chosen == LabSample.URINE) LabSpecimen.URINE else specimen
        val lab = laboratory
        // Stored in the unit the band is written in, like the value above it: a range typed
        // beside a mg/dL reading has to travel the same conversion, or the sheet's own range
        // would contradict the sheet's own number.
        val ranges = values.keys.associateWith { marker ->
            ReportedRange(
                low = reportedLow[marker]?.toDoubleOrNull()?.let { marker.storedIn(it, unitOf(marker)) },
                high = reportedHigh[marker]?.toDoubleOrNull()?.let { marker.storedIn(it, unitOf(marker)) },
            )
        }.filterValues { !it.empty }
        val units = values.keys.filter { it.otherUnit != null }.associateWith { it.typedUnitCode(unitOf(it)) }
        saving = true
        scope.launch {
            // Not cut off by a back press mid-transaction: the page's scope goes with the page. A sheet
            // already saved and read again writes only what that day's panels do not hold yet.
            outcome = withContext(NonCancellable) {
                val held = db.dao().journalOnNow(day.toString())
                val brought = labsPayload(values, panelFasting, sheet, sampled, lab, ranges, units)
                val write = labPanelWrite(values, held, ranges, units, brought)
                val merged = if (write.kept.isEmpty()) {
                    write.merged?.also { db.saveJournalEvent(it) }
                } else {
                    val entry = labsEntry(
                        resources = resources,
                        values = write.kept,
                        // Written false rather than left out on a urine panel: without the key the
                        // history guesses fasting from the meals around the day, and the export would
                        // call a urine sample a fasting one.
                        fasting = panelFasting,
                        imperial = imperial,
                        day = day,
                        attachments = sheet,
                        specimen = sampled,
                        method = lab,
                        reportedRanges = write.ranges,
                        typedUnits = write.units,
                    )
                    db.saveJournalEvent(entry)
                    // Glucose reaches Health Connect the moment it is saved, the same way an edited
                    // weight does; the rest of the panel has nowhere to go.
                    writeLocalHealthIfAvailable(context, db)
                    null
                }
                labSaveOutcome(write.kept, held, merged)
            }
            values.keys.forEach {
                typed.remove(it)
                reportedLow.remove(it)
                reportedHigh.remove(it)
            }
            // Figures typed under the other sample are the rest of the same visit, and nothing on this
            // panel said they were waiting. The form turns to them, with the fold that holds the first
            // open, and keeps the sheet's file and laboratory for that panel too. Only the last panel
            // saved goes on to the page that says so.
            val waiting = LabMarker.entries.firstOrNull { it.panel.sample != chosen && !typed[it].isNullOrEmpty() }
            if (waiting != null) {
                type = waiting.panel.sample.addType()
                openPanel = waiting.panel
                status = if (outcome == LabSaveOutcome.ADDED) savedText else resources.getString(outcome.titleRes)
                scroll.animateScrollTo(0)
            } else {
                if (typed.values.all(String::isEmpty)) openPanel = null
                step = LabAddStep.DONE
            }
            saving = false
        }
    }

    fun saveDocument(chosen: LabAddType, kind: AttachmentKind, file: String) {
        val title = resources.getString(chosen.titleRes)
        val entry = if (chosen == LabAddType.ALLERGY) {
            val rows = decodeAllergenRows(panelRows)
            // Said on the row when nothing else is: the diary and the shelf of sheets show the note.
            val counted = resources.getString(R.string.allergy_panel_count, rows.size, rows.count { it.positive })
            allergyPanelEntry(title, file, drawnOn, note.ifBlank { if (rows.isEmpty()) "" else counted }, rows)
        } else {
            labDocumentEntry(kind, title, file, drawnOn, note)
        }
        saving = true
        scope.launch {
            withContext(NonCancellable) { db.saveJournalEvent(entry) }
            saving = false
            step = LabAddStep.DONE
        }
    }

    // The panel's form, for the sample the first page chose: the draw's date and circumstances, the
    // sample's fields, the sheet's files, and Save.
    @Composable
    fun SheetForm(chosen: LabSample) {
        PwnCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { pickingDate = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.labs_drawn_on, drawnOn.formatLong()),
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.labs_change_date), color = PwnMuted)
                }
                // Fasting decides a glucose's and a triglyceride's band, and nothing on a urine
                // sheet; the tube is a blood question. A urine form asks neither.
                if (chosen == LabSample.BLOOD) {
                    PwnSwitchRow(
                        title = stringResource(R.string.labs_fasting),
                        body = stringResource(if (fasting) R.string.labs_fasting_on else R.string.labs_fasting_off),
                        checked = fasting,
                        onCheckedChange = { fasting = it },
                        titleStyle = MaterialTheme.typography.bodyLarge,
                    )
                    // The specimen and the analyser are what turn a marker name into a test:
                    // "glucose" over serum and over whole blood are two different results, and
                    // recording which is what a receiving system would need before any of these
                    // markers could honestly carry a code.
                    PwnChoiceField(
                        label = stringResource(R.string.labs_blood_specimen),
                        options = listOf(stringResource(R.string.labs_specimen_none)) +
                            BLOOD_SPECIMENS.map { stringResource(it.titleRes) },
                        selected = specimen?.let { BLOOD_SPECIMENS.indexOf(it) + 1 } ?: 0,
                        onSelected = { specimen = BLOOD_SPECIMENS.getOrNull(it - 1) },
                    )
                }
                OutlinedTextField(
                    value = laboratory,
                    onValueChange = { laboratory = it.take(LAB_METHOD_MAX) },
                    label = { Text(stringResource(R.string.labs_method)) },
                    placeholder = { Text(stringResource(R.string.labs_method_hint)) },
                    singleLine = true,
                    // Next rather than Done: a sheet is copied field after field, and the
                    // keyboard's own key carries on to the next one.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                val panels = LabPanel.entries.filter { it.sample == chosen }
                // One panel is its fields, with no fold to open first: the first page has already
                // said which sheet this is. Its name stays, as a heading, so the fields do not run
                // on from the laboratory's name.
                panels.singleOrNull()?.let { panel ->
                    Text(
                        stringResource(panel.titleRes),
                        Modifier.semantics { heading() },
                        fontWeight = FontWeight.SemiBold,
                    )
                    LabMarkerFields(
                        fields = panel.fields(),
                        typed = typed,
                        reportedLow = reportedLow,
                        reportedHigh = reportedHigh,
                        unitOf = ::unitOf,
                        onUnit = { marker, unit -> unitPicks[marker] = unit },
                    )
                } ?: panels.forEach { panel ->
                    LabPanelSection(
                        panel = panel,
                        open = openPanel == panel,
                        typed = typed,
                        reportedLow = reportedLow,
                        reportedHigh = reportedHigh,
                        unitOf = ::unitOf,
                        onUnit = { marker, unit -> unitPicks[marker] = unit },
                        onToggle = { openPanel = if (openPanel == panel) null else panel },
                    )
                }
                // A panel is usually a sheet of paper or a PDF the lab mailed: the numbers typed in
                // are a summary of it, and the sheet itself is what a clinician asks to see.
                AttachmentsField(attachments) { attachments = it }
                Button(
                    onClick = { savePanel(chosen) },
                    enabled = values.isNotEmpty() && !importStatus.busy && !saving,
                    // A floor rather than a height, so a label that wraps at a large text size
                    // makes the button taller instead of being cut off.
                    modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                ) { Text(stringResource(R.string.labs_save)) }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LabStepBar(step.ordinal, LabAddStep.entries.size, ::leaveStep)
            Column(
                Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(scroll).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                val chosen = type
                when (step) {
                    LabAddStep.TYPE -> {
                        LabStepTitle(
                            stringResource(R.string.lab_add_type_title),
                            stringResource(R.string.lab_add_choose_one),
                        )
                        LabAddType.entries.forEach { option ->
                            LabChoiceRow(option.icon, stringResource(option.titleRes), null) {
                                type = option
                                step = LabAddStep.METHOD
                            }
                        }
                    }

                    LabAddStep.METHOD -> {
                        chosen?.let { LabChosenType(it) { step = LabAddStep.TYPE } }
                        LabStepTitle(
                            stringResource(R.string.lab_add_method_title),
                            stringResource(R.string.lab_add_choose_one),
                        )
                        chosen?.methods.orEmpty().forEach { option ->
                            LabChoiceRow(option.icon, stringResource(option.titleRes), stringResource(option.bodyRes)) {
                                method = option
                                read = false
                                step = LabAddStep.ENTRY
                            }
                        }
                    }

                    LabAddStep.ENTRY -> {
                        chosen?.let { LabChosenType(it) { step = LabAddStep.TYPE } }
                        val kind = chosen?.document
                        val photo = method == LabAddMethod.PHOTO
                        when {
                            chosen != null && kind != null -> LabDocumentEntry(
                                photo = photo,
                                file = document,
                                onFile = { picked ->
                                    document = picked
                                    if (chosen == LabAddType.ALLERGY) {
                                        panelRows = ""
                                        panelRead = false
                                        readPanel(picked)
                                    }
                                },
                                day = drawnOn,
                                onPickDay = { pickingDate = true },
                                note = note,
                                onNote = { note = it },
                                saving = saving || panelStatus.busy,
                                onSave = { file -> saveDocument(chosen, kind, file) },
                            ) {
                                if (chosen == LabAddType.ALLERGY) {
                                    LabImportStatusLines(panelStatus)
                                    val rows = remember(panelRows) { decodeAllergenRows(panelRows) }
                                    if (rows.isNotEmpty()) {
                                        AllergyPanelRows(rows)
                                    } else if (panelRead && !panelStatus.busy && !panelStatus.error) {
                                        Text(stringResource(R.string.allergy_panel_empty), color = PwnMuted)
                                    }
                                }
                            }

                            sample != null && (method == LabAddMethod.MANUAL || read) -> {
                                LabStepTitle(
                                    stringResource(
                                        if (read) R.string.lab_add_check_title else R.string.lab_add_manual_title,
                                    ),
                                    if (read) null else stringResource(R.string.labs_subtitle),
                                )
                                // The import's own lines scroll with the form: only the bar above stays put.
                                LabImportStatusLines(importStatus)
                                SheetForm(sample)
                                DataNoteLink(R.string.labs_disclaimer)
                            }

                            // A file or a photo of blood or urine, before it is read: where it is chosen,
                            // and under it how the reading goes.
                            else -> {
                                LabStepTitle(
                                    stringResource(
                                        if (photo) R.string.lab_add_photo_title else R.string.lab_add_file_title,
                                    ),
                                    stringResource(
                                        if (photo) R.string.lab_add_photo_lead else R.string.lab_add_file_lead,
                                    ),
                                )
                                LabCaptureZone(
                                    icon = if (photo) LabAddMethod.PHOTO.icon else LabAddMethod.FILE.icon,
                                    title = stringResource(
                                        if (photo) R.string.progress_photo_take else R.string.file_import_pick,
                                    ),
                                    hint = stringResource(
                                        if (photo) R.string.lab_add_photo_hint else R.string.lab_add_file_hint,
                                    ),
                                    enabled = import.ready,
                                ) { if (photo) import.photo() else import.files() }
                                LabImportStatusLines(importStatus)
                            }
                        }
                    }

                    LabAddStep.DONE -> {
                        val how = method
                        if (chosen != null && how != null) {
                            LabDonePage(chosen, how, onResults, ::startOver, outcome.titleRes)
                        }
                        if (chosen == LabAddType.ALLERGY && !panelOffered) {
                            val answer = storedProfile.allergyAnswer()
                            val rows = remember(panelRows) { decodeAllergenRows(panelRows) }
                            AllergySuggestion(rows, answer) { added ->
                                panelOffered = true
                                scope.launch {
                                    val allergies = AllergyAnswer.Known(answer.allergens() + added).stored()
                                    withContext(NonCancellable) {
                                        db.dao().saveProfile(storedProfile.copy(allergies = allergies))
                                    }
                                    status = savedText
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        // The word for what was just saved is said in the centre of the screen, the way a deleted
        // record says it — here only when one sample's panel is saved and the form turns to the
        // other's, since the last save has a page of its own. Outside the scrolling Column: it must
        // neither travel with the content nor be clipped by it.
        PwnActionFlash(status)
    }

    LabImportDrafts(importStatus, existing, drawnOn, fill)

    if (pickingDate) {
        SampleDateDialog(
            initial = drawnOn,
            onDismiss = { pickingDate = false },
            onPick = {
                sampleDay = it.toString()
                pickingDate = false
            },
        )
    }
}

/**
 * One panel's fields, folded away until asked for.
 *
 * Twenty-odd fields in one column is a form nobody fills in. A person holds one sheet with one
 * panel on it, opens that panel, and types four numbers.
 */
@Composable
@Suppress("LongParameterList") // One panel: what it is, what is typed in it, and how it opens.
private fun LabPanelSection(
    panel: LabPanel,
    open: Boolean,
    typed: MutableMap<LabMarker, String>,
    reportedLow: MutableMap<LabMarker, String>,
    reportedHigh: MutableMap<LabMarker, String>,
    unitOf: (LabMarker) -> LabImperial?,
    onUnit: (LabMarker, String) -> Unit,
    onToggle: () -> Unit,
) {
    val fields = panel.fields()
    val filled = fields.count { typed[it].orEmpty().isNotEmpty() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth()
                // The header is the only way into a panel, and its tallest glyph is 24dp: 6dp above
                // and below alone make a 36dp target, twelve short of the 48dp minimum, so the
                // container grows to 48dp and the type keeps its place.
                .heightIn(min = 48.dp)
                // A disclosure control has to say that it is one. A bare clickable Row is
                // announced as static text saying nothing about open or shut: the role names the
                // control and these two carry the fold state through AccessibilityNodeInfo, and
                // none of the three needs a string of its own to translate. Same three as the
                // editor's own collapsible card.
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics {
                    if (open) {
                        collapse {
                            onToggle()
                            true
                        }
                    } else {
                        expand {
                            onToggle()
                            true
                        }
                    }
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(panel.titleRes),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (filled > 0) {
                Text(
                    stringResource(R.string.labs_panel_filled, filled),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // `clickable` merges this row into one spoken node, so a chevron with a description
            // would be read out as part of the header's label. The icon carries none, which is how
            // the rest of the app draws the same control.
            Icon(
                if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = PwnMuted,
            )
        }
        if (open) {
            LabMarkerFields(fields, typed, reportedLow, reportedHigh, unitOf, onUnit)
        }
    }
}

/** The fields a panel types into: everything on it but what the app computes itself. */
private fun LabPanel.fields(): List<LabMarker> = LabMarker.entries.filter { it.panel == this && !it.derived }

/**
 * A panel's fields, open: each marker's number, its unit where it takes two, and the sheet's own
 * range once a number is in. Folded under a heading on the blood form; the urine form, one panel
 * long, shows them straight away.
 */
@Composable
@Suppress("LongParameterList") // What is typed, in which unit, beside which printed range.
private fun LabMarkerFields(
    fields: List<LabMarker>,
    typed: MutableMap<LabMarker, String>,
    reportedLow: MutableMap<LabMarker, String>,
    reportedHigh: MutableMap<LabMarker, String>,
    unitOf: (LabMarker) -> LabImperial?,
    onUnit: (LabMarker, String) -> Unit,
) {
    fields.forEach { marker ->
        val unit = unitOf(marker)
        val figure = typed[marker]?.toDoubleOrNull()
        // A number the marker cannot accept is not saved, so the field says so and keeps it
        // rather than letting the save quietly swallow the reading.
        val refused = figure?.let { marker.storedIn(it, unit) !in marker.valid } == true
        // Where the field takes two units, the figure in the other one while it is typed: a
        // figure copied against the wrong unit shows itself here, not on the next result.
        val converted = figure?.takeIf { !refused }?.let { marker.inOtherUnit(it, unit) }
        val support = when {
            refused -> stringResource(R.string.labs_refused, acceptedText(marker, unit))

            converted != null -> stringResource(
                R.string.labs_unit_converted,
                converted.first,
                stringResource(converted.second),
            )

            else -> null
        }
        OutlinedTextField(
            value = typed[marker].orEmpty(),
            onValueChange = { typed[marker] = it.labDecimalInput() },
            label = { Text(stringResource(marker.titleRes)) },
            // A pH or a specific gravity has no unit, and a dash where one goes read as a blank.
            suffix = if (marker.unitless) null else ({ Text(stringResource(unit?.unitRes ?: marker.unitRes)) }),
            supportingText = support?.let { text -> { Text(text) } },
            isError = refused,
            singleLine = true,
            keyboardOptions = DECIMAL_NEXT,
            modifier = Modifier.fillMaxWidth(),
        )
        // Which of its two units the sheet printed, on the markers whose two overlap: the
        // profile's setting is no guide to a sheet, and a dialog would take the eye off it.
        marker.otherUnit?.let { other ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PwnFilterChip(
                    selected = unit == null,
                    onClick = { onUnit(marker, marker.typedUnitCode(null)) },
                    label = stringResource(marker.unitRes),
                )
                PwnFilterChip(
                    selected = unit != null,
                    onClick = { onUnit(marker, marker.typedUnitCode(other)) },
                    label = stringResource(other.unitRes),
                )
            }
        }
        // The laboratory's own range, and only under a marker that has a number: two empty
        // fields under every marker would be a form nobody finishes. What goes in is what the
        // sheet printed, in the field's own unit, whichever way it printed it — one edge alone
        // is how half of them come.
        if (!typed[marker].isNullOrBlank()) {
            Text(
                stringResource(R.string.labs_reported_range),
                color = PwnMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = reportedLow[marker].orEmpty(),
                    onValueChange = { reportedLow[marker] = it.labDecimalInput() },
                    label = { Text(stringResource(R.string.labs_reported_from)) },
                    singleLine = true,
                    keyboardOptions = DECIMAL_NEXT,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = reportedHigh[marker].orEmpty(),
                    onValueChange = { reportedHigh[marker] = it.labDecimalInput() },
                    label = { Text(stringResource(R.string.labs_reported_to)) },
                    singleLine = true,
                    keyboardOptions = DECIMAL_NEXT,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The figures a panel of [sample] saves: the ones typed in its own fields that those fields accept,
 * each in the unit its band is written in.
 *
 * Typed in whichever unit the field is labelled with, kept in the unit the band is written in. A
 * figure outside the marker's range is refused rather than stored, and the field keeps it: a mg/dL
 * sheet copied under the metric setting is the ordinary way this happens, and clearing the number on
 * save would lose the only copy of a reading nobody can re-draw.
 *
 * Fields of the other sample keep what was typed in them, hidden, and give none of it: a visit that
 * gave blood and urine copies one sheet, answers the question again, and finds the other where it
 * was left — while a glucose typed before the answer went to urine can never be saved as urine.
 */
internal fun labFormValues(
    sample: LabSample?,
    typed: Map<LabMarker, String>,
    unitOf: (LabMarker) -> LabImperial?,
): Map<LabMarker, Double> = LabMarker.entries
    .filter { it.panel.sample == sample }
    .mapNotNull { marker ->
        typed[marker]?.toDoubleOrNull()
            ?.let { marker.storedIn(it, unitOf(marker)) }
            ?.takeIf { it in marker.valid }
            ?.let { marker to it }
    }
    .toMap()

/**
 * What one Save writes, decided against the rows the day already holds: the figures its panels lack
 * ([unsavedLabValues]) as a new panel, with the printed ranges and the typed units of those figures
 * alone — a range beside a figure the day already holds belongs to the panel holding it. Where the day
 * holds every figure, nothing new is written, and nothing new is not nothing to keep: the file just
 * read, the laboratory's name and its printed ranges ([sheet], the payload a new panel would carry) go
 * to the panel that holds the figures ([panelWithSheet]) as [merged], null where they bring nothing.
 */
internal class LabPanelWrite(
    val kept: Map<LabMarker, Double>,
    val ranges: Map<LabMarker, ReportedRange>,
    val units: Map<LabMarker, String>,
    val merged: JournalEntry?,
)

internal fun labPanelWrite(
    values: Map<LabMarker, Double>,
    held: List<JournalEntry>,
    ranges: Map<LabMarker, ReportedRange>,
    units: Map<LabMarker, String>,
    sheet: String,
): LabPanelWrite {
    val kept = unsavedLabValues(values, held)
    return if (kept.isEmpty()) {
        LabPanelWrite(kept, emptyMap(), emptyMap(), panelWithSheet(values, held, sheet))
    } else {
        LabPanelWrite(kept, ranges.filterKeys(kept::containsKey), units.filterKeys(kept::containsKey), null)
    }
}

/**
 * The lab fields' own filter: three decimal places where [decimalInput] keeps the two a gram needs.
 *
 * A fully suppressed thyroid is printed as 0.005, which is exactly where [LabMarker.TSH]'s accepted
 * range starts; at two places it becomes 0.00 and the reading cannot be typed in at all.
 */
internal fun String.labDecimalInput(): String {
    // Char.isDigit is true for every Unicode decimal digit, but the toDoubleOrNull that saves the
    // reading accepts ASCII alone. Left as typed, ٧٢ from an Arabic or Devanagari keyboard would
    // stand in the field while the save read null and marked the reading refused with the number
    // still on screen. Folding each digit to its ASCII value keeps what was typed and makes it
    // parseable. The separator is folded with them (DECIMAL_SEPARATORS in OnboardingScreen.kt):
    // dropping the Arabic U+066B would turn 1٫5 into 15, which passes every range check this screen has.
    val normalized = map { char ->
        if (char in DECIMAL_SEPARATORS) '.' else char.digitToIntOrNull()?.let { '0' + it } ?: char
    }
        .filter { it in '0'..'9' || it == '.' }
        .joinToString("")
    val dot = normalized.indexOf('.')
    return if (dot < 0) {
        normalized.take(7)
    } else {
        normalized.substring(0, dot).take(7) + "." + normalized.substring(dot + 1).replace(".", "").take(3)
    }
}

/**
 * A blood draw can be months old by the time it is typed in, and every rule that reads it — the
 * half-year staleness window, the session two days before, the water in the three days before —
 * measures from the date on the sheet rather than from the evening it was entered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleDateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = rememberToday()
    val todayUtc = remember(today) { today.toPickerMillis() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toPickerMillis(),
        yearRange = (today.year - LAB_HISTORY_YEARS)..today.year,
        selectableDates = object : SelectableDates {
            // A sample cannot have been drawn tomorrow.
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
        },
    )
    LocalDateDialog(state, onDismiss, onPick)
}

/** A laboratory's name and analyser, long enough for both and short enough to print on the sheet. */
private const val LAB_METHOD_MAX = 60

/** A figure's keyboard, whose action key moves on to the next field rather than closing it. */
private val DECIMAL_NEXT = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)

/** Fields as one map, kept across a rotation the way every other editor in the app keeps its. */
@Composable
private fun rememberTypedLabs() = rememberSaveable(
    saver = listSaver(
        save = { state -> LabMarker.entries.map { state[it].orEmpty() } },
        restore = { saved ->
            mutableStateMapOf<LabMarker, String>().apply {
                LabMarker.entries.forEachIndexed { index, marker ->
                    saved.getOrNull(index)?.takeIf(String::isNotEmpty)?.let { put(marker, it) }
                }
            }
        },
    ),
) { mutableStateMapOf<LabMarker, String>() }

@Composable
internal fun LocalDate.formatLong(): String {
    val locale = LocalLocale.current.platformLocale
    return format(remember(locale) { localizedDatePattern(locale, "yMMMd", "d MMM yyyy") })
}

/** The same date from a stored day, for the screens that hold one as it was written down. */
@Composable
internal fun labDayText(day: String): String {
    val parsed = remember(day) { day.toLocalDateOrNull() }
    return parsed?.formatLong() ?: day
}

/**
 * The figures the field will accept, in the unit it is labelled with — the bounds the form really
 * enforces rather than the printed form of the raw range, which can itself be refused on the way in.
 */
@Composable
private fun acceptedText(marker: LabMarker, unit: LabImperial?): String =
    marker.acceptedBounds(unit).let { (lowest, highest) -> stringResource(R.string.labs_norm_range, lowest, highest) }
