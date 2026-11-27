package com.pwnall.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Files hung on a row, stored as content URI references with optional examination metadata.
 *
 * Nothing is copied. A prescription photographed once already exists in the gallery, and a second
 * copy inside the app is a second thing to back up, to keep in step and to leak. What is kept is a
 * reference plus the persistable read grant that makes it survive a reboot — and when the grant is
 * gone, because the file was deleted or the app that owned it was, the row keeps the reference: it
 * shows the last part of its path and a tap does nothing, and only the archive for the doctor lists
 * such a file as unavailable. The grant is taken at the pick and let go once no saved row names the
 * file: with a lab sheet that is deleted, at once ([releaseAttachmentsAfterWrite]), and otherwise at
 * the next fresh start ([sweepUnnamedAttachments]), since the cross only edits a form that may yet be
 * left unsaved.
 */
internal fun attachmentUris(stored: String): List<String> =
    medicalAttachments(stored).map { it.uri }

internal fun storeAttachments(uris: List<String>): String = uris.joinToString("\n")

/** What the file is called where it lives, or its last path segment when nothing answers. */
internal fun Context.attachmentName(uri: String): String {
    val parsed = runCatching { uri.toUri() }.getOrNull() ?: return uri
    val queried = runCatching {
        contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    return queried ?: parsed.lastPathSegment ?: uri
}

private fun String.toUri(): Uri = Uri.parse(this)

private const val CONTENT_SCHEME = "content://"

/**
 * Whether [uri] is the kind of reference a picker hands over, which is the only kind opened.
 *
 * A row is not only what a picker wrote. A backup's rows are merged in as they stand, and one that
 * somebody else wrote can name anything: a `file://` path, which the resolver opens with this app's
 * own access to its private folder and which closes the app when it is put in an intent, or a web
 * page for an app that has no business sending anybody to one.
 */
internal fun isAttachmentReference(uri: String): Boolean = uri.startsWith(CONTENT_SCHEME)

/**
 * Whether the file [uri] names may be copied out of the app, as the archive for the doctor copies
 * every attachment of the records chosen for it.
 *
 * A reference to another app's file may: reading it takes a grant that only the person's own pick
 * gave. One into this app's own provider, [ownAuthority], needs no grant at all, and the provider
 * serves the progress photographs, the medicine and walk pictures and two folders of the cache — so of those
 * only a lab sheet's photograph leaves, which is the one thing a row is given to name there.
 */
internal fun attachmentMayLeave(uri: String, ownAuthority: String): Boolean {
    if (!isAttachmentReference(uri)) return false
    return !namesOwnProvider(uri, ownAuthority) ||
        (uri.startsWith("$CONTENT_SCHEME$ownAuthority/$LAB_CAPTURE_DIR/") && labCaptureFileName(uri) != null)
}

/**
 * Whether [uri] may be handed to a viewer: another app's file, or a picture of this app's own in one
 * of the folders its screens open full-size ([OWN_PICTURE_FOLDERS]).
 *
 * The viewer is given a read grant on the reference, and this app's own provider also serves two
 * folders of the cache — the report for the doctor, the camera's shots and the files shared from the
 * app. A row restored from somebody else's file can name any of those, and were the provider to serve
 * the whole cache, a backup on its way out among them, one tap on it would hand the plain text of the
 * record to whatever opened it.
 */
internal fun attachmentOpens(uri: String, ownAuthority: String): Boolean {
    if (!isAttachmentReference(uri)) return false
    return !namesOwnProvider(uri, ownAuthority) ||
        OWN_PICTURE_FOLDERS.any { uri.startsWith("$CONTENT_SCHEME$ownAuthority/$it/") }
}

/** The `files-path` names in res/xml/photo_paths.xml; its `cache-path` ones are left out on purpose. */
private val OWN_PICTURE_FOLDERS = listOf("progress-photos", "medicine-photos", "walk-photos", LAB_CAPTURE_DIR)

/** Whether [uri] reaches this app's own provider, [ownAuthority], however it spells the way there. */
private fun namesOwnProvider(uri: String, ownAuthority: String): Boolean {
    val authority = uri.removePrefix(CONTENT_SCHEME).takeWhile { it !in "/\\?#" }
    // The resolver decodes the authority and drops a user id in front of it before it looks the
    // provider up, so `%2E` for a dot and `0@` both still reach this app's own.
    return '%' in authority || authority.substringAfterLast('@') == ownAuthority
}

/** Hands the file to whatever on the phone opens that kind, where it may be opened ([attachmentOpens]). */
internal fun Context.openAttachment(uri: String) {
    if (!attachmentOpens(uri, "$packageName.photos")) return
    val parsed = runCatching { uri.toUri() }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(parsed, contentResolver.getType(parsed) ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    // A phone with nothing that opens PDFs is a phone, not a bug: the tap does nothing rather than
    // taking the screen down with it. Nor is a grant this app no longer holds — the same scan
    // attached to two courses and released from one, a provider that never made its grant
    // persistable, a backup restored on another phone — which the system reports as a
    // SecurityException thrown by startActivity itself.
    runCatching { startActivity(intent) }.onFailure {
        if (it !is ActivityNotFoundException && it !is SecurityException) throw it
    }
}

/**
 * Every file a stored row names: a course, a visit, the payload of a lab sheet, a chat. The read
 * grant is one per file per app, not per row, so it may only be let go once none of them names the
 * file — released from one course while a second still points at the same scan, that second row
 * would open as nothing.
 */
internal fun namedAttachments(
    medications: List<Medication>,
    appointments: List<Appointment>,
    labSheets: List<JournalEntry>,
    chats: List<ChatRow> = emptyList(),
): Set<String> = buildSet {
    medications.forEach { addAll(attachmentUris(it.attachments)) }
    appointments.forEach { addAll(attachmentUris(it.attachments)) }
    labSheets.forEach { addAll(attachmentUris(it.labAttachments())) }
    chats.forEach { addAll(attachmentUris(decodeChatLog(it.payload)?.attachments.orEmpty())) }
}

private suspend fun StepsDb.namedAttachments(): Set<String> {
    val dao = dao()
    return namedAttachments(
        dao.allMedications(),
        dao.allAppointments(),
        dao.journalAround(KIND_LABS, Long.MIN_VALUE, Long.MAX_VALUE),
        dao.allChats(),
    )
}

/**
 * Lets go of each of [uris] no stored row names any more: the read grant of a file picked from
 * another app, and the file itself where it is a photograph the lab camera took into this one. Run
 * once the row that dropped them is written — never inside a transaction, which could still roll
 * back to a row naming a photograph already deleted.
 */
internal suspend fun StepsDb.releaseAttachmentsAfterWrite(uris: List<String>) {
    val app = reminderApp ?: return
    if (uris.isEmpty() || inTransaction()) return
    val named = namedAttachments()
    val shots = named.mapNotNullTo(HashSet(), ::labCaptureFileName)
    withContext(Dispatchers.IO) {
        uris.filterNot(named::contains).forEach { uri ->
            runCatching {
                app.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            labCaptureFileName(uri)?.takeUnless(shots::contains)?.let { File(labCaptureDir(app), it).delete() }
        }
    }
}

/**
 * At a fresh start, what no stored row names is let go: the grant of a file picked into a form that
 * was left unsaved, or picked over by another, or taken off a row and saved, or on a row since
 * deleted; and a lab photograph whose sheet was never saved. Nothing in this window can be holding
 * them then: a form's picks come back only with its saved state, which a fresh start does not have.
 * Only what is an hour old goes — a file picked into a form still open in another window of the app,
 * a shot the camera is still writing, or one a restore is still bringing in, is younger.
 *
 * Not while a record moved aside — damaged, or sealed by another phone's key — is still on the disk:
 * the fresh record does not name what that one does, and the files are worth more than the space.
 * Asked once the record has been read, since opening it is what moves such a file aside, and on the
 * start that does, the fresh record answers that it names nothing.
 */
internal suspend fun sweepUnnamedAttachments(context: Context) {
    val named = StepsDb.get(context).namedAttachments()
    val record = context.getDatabasePath(DATABASE_FILE).path
    if (DatabaseEncryption.lockedCopyPresent(context) || File("$record.corrupt").exists()) return
    val resolver = context.contentResolver
    val now = System.currentTimeMillis()
    // Read-only grants alone: the daily copy's folder is held to read and write, and is no attachment.
    resolver.persistedUriPermissions
        .filter {
            it.isReadPermission && !it.isWritePermission && it.persistedTime < now - IN_FLIGHT_STALE_MS &&
                it.uri.toString() !in named
        }
        .forEach {
            runCatching { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    unnamedLabCaptures(labCaptureDir(context), named, now).forEach(File::delete)
}

/** The lab photographs in [dir] that no row of [named] names and that are an hour old at [now]. */
internal fun unnamedLabCaptures(dir: File, named: Set<String>, now: Long): List<File> {
    val shots = named.mapNotNullTo(HashSet(), ::labCaptureFileName)
    return dir.listFiles().orEmpty()
        .filter { it.isFile && it.name !in shots && it.lastModified() < now - IN_FLIGHT_STALE_MS }
}

/** The picker and the file being described, shared when the add button lives outside the list. */
internal class AttachmentActions(
    val onAdd: () -> Unit,
    val editingUri: String?,
    val onEdit: (String?) -> Unit,
)

@Composable
internal fun rememberAttachmentActions(value: String, onChange: (String) -> Unit): AttachmentActions {
    val context = LocalContext.current
    var editingUri by rememberSaveable { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        val uri = picked ?: return@rememberLauncherForActivityResult
        // Without the persistable grant the reference is good until the next reboot and no longer.
        // Providers that refuse one still give a session grant, which is better than refusing the
        // attachment outright.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val added = uri.toString()
        onChange(addMedicalAttachments(value, listOf(added)))
        editingUri = added
    }
    return AttachmentActions(
        onAdd = { pick.launch(arrayOf("*/*")) },
        editingUri = editingUri,
        onEdit = { editingUri = it },
    )
}

/**
 * The attachment list and, by default, the button that adds to it. A chat shares [actions] with
 * its composer and hides this button while messages can be typed.
 *
 * [value] and [onChange] carry the stored string, so the field is as stateless as every other one
 * on the form and the page above it decides when any of it is written.
 */
@Composable
internal fun AttachmentsField(
    value: String,
    showTitle: Boolean = true,
    onImportLabs: ((String) -> Unit)? = null,
    showAddButton: Boolean = true,
    actions: AttachmentActions? = null,
    onChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val files = medicalAttachments(value)
    val editing = actions ?: rememberAttachmentActions(value, onChange)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The booking form puts this inside a step card that is already headed "Attachments"; the
        // course form has no header of its own and needs one.
        if (showTitle) {
            Text(
                stringResource(R.string.attachments_title),
                style = MaterialTheme.typography.labelLarge,
                color = PwnMuted,
            )
        }
        files.forEach { file ->
            val uri = file.uri
            val name = labFileName(uri)
            val describing = editing.editingUri == uri
            // The tap hands the file to another app, which a bare clickable never says: the role
            // and the action's name are what a screen reader announces before it happens. The
            // floor keeps the row a whole target even where its two lines are short.
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = PwnDimensions.minimumTarget)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.history_open)) {
                        context.openAttachment(uri)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = null,
                    tint = PwnMuted,
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(stringResource(file.kind.titleRes), file.studyDay, file.description)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = PwnMuted,
                    )
                }
                // No size of their own on these two: a size given to an IconButton replaces the
                // 48dp target it carries, and at 32dp the two would sit so close that a miss on the
                // pencil could land on the cross, which drops the file at once. Each names its
                // file, because with three attachments three identical buttons are one too many;
                // so does the button that reads a result out of it, below.
                IconButton(
                    onClick = { editing.onEdit(if (describing) null else uri) },
                    modifier = Modifier.semantics {
                        if (describing) {
                            collapse {
                                editing.onEdit(null)
                                true
                            }
                        } else {
                            expand {
                                editing.onEdit(uri)
                                true
                            }
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        stringResource(R.string.m3_editor_attachment_describe, name),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    // Only the form changes: one left unsaved still has the file on its row, which
                    // reads it through the grant. What no saved row names is let go at a later
                    // fresh start ([sweepUnnamedAttachments]).
                    onClick = {
                        if (describing) editing.onEdit(null)
                        onChange(storeMedicalAttachments(files.filterNot { it.uri == uri }))
                    },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.m3_editor_attachment_remove, name),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (describing) {
                AttachmentDetailsForm(
                    file = file,
                    onDismiss = { editing.onEdit(null) },
                    onSave = { updated ->
                        onChange(storeMedicalAttachments(files.map { if (it.uri == updated.uri) updated else it }))
                        editing.onEdit(null)
                    },
                )
            }
            onImportLabs?.let { import ->
                val spoken = stringResource(R.string.m3_editor_attachment_read_result, name)
                OutlinedButton(
                    onClick = { import(uri) },
                    modifier = Modifier.semantics { contentDescription = spoken },
                ) {
                    Text(stringResource(R.string.labs_add_title))
                }
            }
        }
        if (showAddButton) {
            OutlinedButton(onClick = editing.onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp))
                Text(
                    stringResource(R.string.attachments_add),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            stringResource(R.string.attachments_hint),
            style = MaterialTheme.typography.bodySmall,
            color = PwnMuted,
        )
    }
}
