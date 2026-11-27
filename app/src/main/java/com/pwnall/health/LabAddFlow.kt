package com.pwnall.health

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The four pages of adding results, in order: what was tested, how it comes in, the entry, and done. */
internal enum class LabAddStep { TYPE, METHOD, ENTRY, DONE }

/**
 * What was tested, as the first page offers it.
 *
 * Blood and urine are figures, copied into a panel of their [sample]. An allergy panel is a file
 * whose table of allergens is read off it and kept with it ([allergyPanelEntry]). The three below it
 * have no figures this app reads: they are kept as what they are — a file of their [document] kind
 * and a few words about it.
 */
internal enum class LabAddType(
    val titleRes: Int,
    val icon: ImageVector,
    val sample: LabSample? = null,
    val document: AttachmentKind? = null,
) {
    BLOOD(R.string.labs_sample_blood, Icons.Outlined.Bloodtype, sample = LabSample.BLOOD),
    URINE(R.string.labs_specimen_urine, Icons.Outlined.WaterDrop, sample = LabSample.URINE),
    ALLERGY(R.string.lab_add_allergy, Icons.Outlined.Grass, document = AttachmentKind.LAB),
    ULTRASOUND(R.string.attachment_kind_ultrasound, Icons.Outlined.Waves, document = AttachmentKind.ULTRASOUND),
    MRI(R.string.attachment_kind_mri, Icons.Outlined.AccountCircle, document = AttachmentKind.MRI),
    OTHER(R.string.attachment_kind_other, Icons.Outlined.Description, document = AttachmentKind.OTHER),
    ;

    /** A document has nothing to type in: it comes as a file or a photo of one. */
    val methods: List<LabAddMethod>
        get() = if (document == null) LabAddMethod.entries else listOf(LabAddMethod.FILE, LabAddMethod.PHOTO)
}

/** The type a sheet read off a file turned out to be. */
internal fun LabSample.addType(): LabAddType = LabAddType.entries.first { it.sample == this }

/** How the results come in, as the second page offers it. */
internal enum class LabAddMethod(val titleRes: Int, val bodyRes: Int, val icon: ImageVector) {
    MANUAL(R.string.lab_add_manual, R.string.lab_add_manual_body, Icons.Outlined.Edit),
    FILE(R.string.lab_add_file, R.string.lab_add_file_body, Icons.Outlined.Image),
    PHOTO(R.string.lab_add_photo, R.string.lab_add_photo_body, Icons.Outlined.PhotoCamera),
}

/**
 * An ultrasound, an MRI or another document: its file, the day of the examination and the note, as a
 * lab row with no figures on it.
 *
 * A lab row rather than a kind of its own, because a lab row is already everything a document needs
 * to be: on the results page's shelf of sheets, in the doctor's archive under the lab results, and
 * holding its file's read grant. The note is the file's description as well as the row's, so the
 * shelf and the archive's list of files carry it beside the file, and the diary carries it on the row.
 */
internal fun labDocumentEntry(
    kind: AttachmentKind,
    title: String,
    file: String,
    day: LocalDate,
    note: String,
): JournalEntry {
    val said = note.trim()
    return JournalEntry(
        day = day.toString(),
        occurredAt = dayInstant(day.toString()),
        kind = KIND_LABS,
        title = title,
        calories = 0.0,
        measurementPayloadJson = labsPayload(
            values = emptyMap(),
            fasting = false,
            attachments = storeMedicalAttachments(listOf(MedicalAttachment(file, kind, said, day.toString()))),
        ),
        note = said,
    )
}

/**
 * The flow's top bar: the way back, which of the [count] pages this is ([step] from 0), and a line
 * that fills as they pass. In place of a title, because each page names itself in large type under
 * it. A workout template is added through the same bar, with an action of its own in [content] at
 * the far end.
 */
@Composable
internal fun LabStepBar(step: Int, count: Int, onBack: () -> Unit, content: (@Composable () -> Unit)? = null) {
    val filled by animateFloatAsState((step + 1f) / count, PwnMotion.spatial(), label = "lab step")
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = PwnDimensions.topBarHeight)
            // An icon button carries its own inset; bare, the line keeps the page's margin.
            .padding(start = 4.dp, end = if (content == null) 20.dp else 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.lab_add_step, step + 1, count),
                style = MaterialTheme.typography.labelLarge,
                color = PwnMuted,
            )
            LinearProgressIndicator(
                progress = { filled },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        content?.invoke()
    }
}

/** A page's name in large type, and the one line under it saying what to do there. */
@Composable
internal fun LabStepTitle(title: String, lead: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        lead?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** One answer on a page that asks one question: a tap answers it and the next page comes up. */
@Composable
internal fun LabChoiceRow(icon: ImageVector, title: String, body: String?, onClick: () -> Unit) {
    PwnCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PwnIconBubble(icon, PwnMuted, PwnIndigoSoft, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PwnMuted) }
            }
            // Drawn, not said: the card is one button already.
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = PwnMuted)
        }
    }
}

/**
 * The first page's answer, carried over the pages after it, and the way back to it. In the text ink
 * of the accent: the accent itself is a fill, and as words on this pale ground it falls under 3:1.
 */
@Composable
internal fun LabChosenType(type: LabAddType, onChange: () -> Unit) {
    val change = stringResource(R.string.common_edit)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PwnCorners.large))
            .background(PwnIndigoSoft)
            .clickable(role = Role.Button, onClickLabel = change, onClick = onChange)
            .heightIn(min = PwnDimensions.minimumTarget)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(PwnSurfaceElevated, CircleShape), contentAlignment = Alignment.Center) {
            Icon(type.icon, contentDescription = null, tint = PwnMuted, modifier = Modifier.size(20.dp))
        }
        Text(
            stringResource(type.titleRes),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(change, color = PwnBlueInk, style = MaterialTheme.typography.labelLarge)
        Icon(Icons.Outlined.Edit, contentDescription = null, tint = PwnBlueInk, modifier = Modifier.size(18.dp))
    }
}

/**
 * Where a file is chosen or a photo taken: a dashed field the size of a card, opening the phone's
 * own picker or camera. The app has no camera of its own — it holds no camera permission — so the
 * frame and the shutter are the camera app's.
 */
@Composable
internal fun LabCaptureZone(icon: ImageVector, title: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    val edge = PwnControlEdge
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PwnCorners.extraLarge))
            .drawBehind {
                val width = 1.5.dp.toPx()
                drawRoundRect(
                    color = edge,
                    topLeft = Offset(width / 2, width / 2),
                    size = Size(size.width - width, size.height - width),
                    cornerRadius = CornerRadius(PwnCorners.extraLarge.toPx()),
                    style = Stroke(
                        width,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                    ),
                )
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PwnIconBubble(icon, PwnMuted, PwnIndigoSoft, null)
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(hint, style = MaterialTheme.typography.bodySmall, color = PwnMuted, textAlign = TextAlign.Center)
    }
}

/** The camera and the file picker of a document, which is kept as it is and never read. */
@Composable
private fun rememberDocumentCapture(onFile: (String) -> Unit): LabImportLaunch {
    val context = LocalContext.current
    var capture by rememberSaveable { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Without the persistable grant the reference lasts until the next reboot.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onFile(uri.toString())
        }
    }
    val scope = rememberCoroutineScope()
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val shot = capture
        capture = null
        if (shot != null && taken) {
            // Brought to its kept size first, off the main thread: whatever reads the file after
            // the page has it — a panel's reading, a backup — reads the copy that stays.
            scope.launch {
                withContext(Dispatchers.IO) { shrinkLabCapture(context.applicationContext, shot.toUri()) }
                onFile(shot)
            }
        } else if (shot != null) {
            context.contentResolver.delete(shot.toUri(), null, null)
        }
    }
    return LabImportLaunch(
        ready = capture == null,
        photo = {
            runCatching {
                newLabCapture(context).also {
                    capture = it.toString()
                    camera.launch(it)
                }
            }.onFailure {
                capture?.let { shot -> context.contentResolver.delete(shot.toUri(), null, null) }
                capture = null
            }
        },
        files = { pick.launch(arrayOf("image/*", "application/pdf")) },
    )
}

/**
 * The third page of an ultrasound, an MRI or another document: its one file, the day it was done,
 * and a note. A second file chosen replaces the first. [content] stands under the file — what an
 * allergy panel's reading found.
 */
@Composable
internal fun LabDocumentEntry(
    photo: Boolean,
    file: String?,
    onFile: (String) -> Unit,
    day: LocalDate,
    onPickDay: () -> Unit,
    note: String,
    onNote: (String) -> Unit,
    saving: Boolean,
    onSave: (String) -> Unit,
    content: @Composable () -> Unit = {},
) {
    val capture = rememberDocumentCapture(onFile)
    LabStepTitle(
        stringResource(if (photo) R.string.lab_add_photo_title else R.string.lab_add_file_title),
        stringResource(if (photo) R.string.lab_add_photo_lead else R.string.lab_add_document_lead),
    )
    LabCaptureZone(
        icon = if (photo) LabAddMethod.PHOTO.icon else LabAddMethod.FILE.icon,
        title = file?.let { labFileName(it) }
            ?: stringResource(if (photo) R.string.progress_photo_take else R.string.file_import_pick),
        hint = stringResource(if (photo) R.string.lab_add_photo_hint else R.string.lab_add_file_hint),
        enabled = capture.ready,
    ) { if (photo) capture.photo() else capture.files() }
    content()
    OutlinedButton(onClick = onPickDay, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.lab_add_study_day, day.formatLong()), Modifier.weight(1f))
        Text(stringResource(R.string.labs_change_date), color = PwnMuted)
    }
    NoteField(note, onNote)
    Button(
        onClick = { file?.let(onSave) },
        enabled = file != null && !saving,
        modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
    ) { Text(stringResource(R.string.labs_save)) }
}

/**
 * The last page: the results are saved, and the two things worth doing next — reading them, or
 * adding the next sheet. The same rosette and salute the meal and the workout end on: one app, one
 * mark for "it is written down".
 */
@Composable
internal fun LabDonePage(
    type: LabAddType,
    method: LabAddMethod,
    onResults: () -> Unit,
    onMore: () -> Unit,
    @StringRes title: Int = R.string.lab_add_done_title,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        DoneHeading(title)
        LabChoiceRow(type.icon, stringResource(type.titleRes), stringResource(method.titleRes), onResults)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onResults,
            modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
        ) { Text(stringResource(R.string.lab_add_open_results)) }
        Spacer(Modifier.height(10.dp))
        PwnOutlinedButton(
            onClick = onMore,
            modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
        ) { Text(stringResource(R.string.lab_add_more)) }
    }
}

/**
 * The rosette springing up over the salute: what every flow that writes something down ends on — lab
 * results, a template saved, a session done from one. One app, one mark for "it is written down".
 */
@Composable
internal fun DoneBadge() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(Modifier.fillMaxWidth().height(DONE_BADGE_BOX), contentAlignment = Alignment.Center) {
        WorkoutSalute(Modifier.fillMaxWidth().height(DONE_BADGE_BOX), running = shown)
        androidx.compose.animation.AnimatedVisibility(
            visible = shown,
            enter = scaleIn(spring(dampingRatio = 0.55f)) + fadeIn(),
        ) {
            Image(themePainterResource(R.drawable.ic_art_done), null, Modifier.size(DONE_BADGE_ART))
        }
    }
}

/** How every "it is written down" page opens: the badge, its [title], and the one line under it. */
@Composable
internal fun DoneHeading(@StringRes title: Int) {
    DoneBadge()
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(title),
        Modifier.fillMaxWidth().semantics { heading() },
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.lab_add_done_body),
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(22.dp))
}

private val DONE_BADGE_BOX = 150.dp
private val DONE_BADGE_ART = 120.dp
