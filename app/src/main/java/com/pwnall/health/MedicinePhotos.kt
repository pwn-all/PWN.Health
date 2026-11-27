package com.pwnall.health

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The photograph a medicine course may carry: the box or the blister, so that a course on the list
 * is the packet in the drawer at a glance. Two long Latin names look alike in print and nothing
 * alike in a picture.
 *
 * Kept the way a progress photograph is kept — shrunk to [PROGRESS_SIDE] on the longer edge, written
 * into the app's private files directory where no gallery indexes it, its name in the course's row
 * ([Medication.photo]) and its bytes in the backup. The camera is the system's own, through
 * TakePicture, so there is no CAMERA permission.
 *
 * Nothing reaches the medicines directory until the course is saved. A fresh shot waits in the cache
 * under one fixed name ([pendingMedicinePhoto]), so a form left without saving leaves behind no
 * picture that no row names, and the next shot simply takes its place.
 */
internal fun medicinePhotoDir(context: Context): File = File(context.filesDir, MEDICINE_PHOTO_DIR)

/** The moment the course was saved with it is the name, as a progress photograph's is. */
internal fun medicinePhotoName(stamp: Long): String = "$stamp.jpg"

/** Whether [name] is one this app writes: a stamp and `.jpg`, with no directory in it anywhere. */
internal fun isMedicinePhotoName(name: String): Boolean = MEDICINE_PHOTO_NAME.matches(name)

/**
 * [name], or the first stamp after it that [taken] does not claim. Two courses never share a file:
 * deleting one would take the other's picture with it.
 */
internal fun freeMedicinePhotoName(name: String, taken: (String) -> Boolean): String {
    val stamp = name.removeSuffix(".jpg").toLongOrNull() ?: 0L
    return generateSequence(stamp) { it + 1 }.map(::medicinePhotoName).first { !taken(it) }
}

/** The file a save leaves without a course: the one it had, once it holds another or none. */
internal fun replacedMedicinePhoto(before: String, after: String): String? =
    before.takeIf { it != after && isMedicinePhotoName(it) }

/**
 * What a save leaves a course carrying, and the file it leaves without one. A waiting shot is moved
 * in by [keepWaiting] before the row names it; a shot the cache lost under the open form leaves the
 * course with [formPhoto], the picture the form was showing before it. [before] is what the stored
 * row carried, and the second value is that file once nothing carries it.
 */
internal fun savedMedicinePhoto(
    before: String,
    formPhoto: String,
    waiting: Boolean,
    keepWaiting: () -> String,
): Pair<String, String?> {
    val photo = if (waiting) runCatching(keepWaiting).getOrDefault(formPhoto) else formPhoto
    return photo to replacedMedicinePhoto(before, photo)
}

/**
 * The name a backed-up course's photograph is restored under, or null when it stays out: a name
 * this app never wrote, or a course this phone already has ([here], null for one the import adds)
 * whose own picture is in place, which a restore never replaces. A course here without one, or
 * whose file has gone ([lands] says whether it is there), gets the backup's back. A progress
 * photograph is narrower: it comes back only where its row is gone too. [taken] says which names
 * another picture already holds.
 */
internal fun restoredMedicinePhotoName(
    backup: String,
    here: String?,
    lands: (String) -> Boolean,
    taken: (String) -> Boolean,
): String? = when {
    !isMedicinePhotoName(backup) -> null
    here != null && isMedicinePhotoName(here) && lands(here) -> null
    else -> freeMedicinePhotoName(backup, taken)
}

/** Where a shot waits for the course to be saved. One name, so the next shot replaces it. */
internal fun pendingMedicinePhoto(context: Context): File = File(context.cacheDir, MEDICINE_PENDING_FILE)

/**
 * The waiting shot moved in under a name no picture holds, which is returned for the row. Blocking,
 * and it throws when there is no shot to move — the cache can be cleared under an open form. A
 * template's picture is kept the same way, from its own [pending] file into its own [into].
 */
internal fun keepPendingMedicinePhoto(
    context: Context,
    now: Long,
    into: File = medicinePhotoDir(context),
    pending: File = pendingMedicinePhoto(context),
): String {
    val dir = into.apply { mkdirs() }
    val name = freeMedicinePhotoName(medicinePhotoName(now)) { File(dir, it).exists() }
    val target = File(dir, name)
    runCatching { pending.copyTo(target) }.onFailure { target.delete() }.getOrThrow()
    pending.delete()
    return name
}

/** A course's photograph going with the course, or with the save that replaced it. Blocking. */
internal fun deleteMedicinePhoto(context: Context, name: String?) {
    if (name != null && isMedicinePhotoName(name)) File(medicinePhotoDir(context), name).delete()
}

/**
 * The name field with the photograph beside it: a camera button while there is none, the picture
 * once there is, and under it the two things a picture can have done to it.
 *
 * [shown] is the file on screen — the waiting shot or the kept one — and [version] counts the shots,
 * so a retake under the same cache name is decoded again. [onTaken] is told a shot is waiting.
 * [content] is the name field itself.
 */
@Composable
internal fun MedicineNameWithPhoto(
    name: String,
    shown: File?,
    version: Int,
    onTaken: () -> Unit,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capture = remember(context) {
        val shot = File(sharedCacheDir(context), MEDICINE_CAPTURE_FILE)
        FileProvider.getUriForFile(context, "${context.packageName}.photos", shot)
    }
    val keep: (Uri) -> Unit = { source ->
        scope.launch {
            // The page can close under the camera; a shot that was taken still lands in the cache,
            // where the form finds it if it comes back.
            val kept = withContext(NonCancellable + Dispatchers.IO) {
                val part = File(context.cacheDir, "$MEDICINE_PENDING_FILE.part")
                runCatching {
                    // Written aside and moved over the waiting shot only once whole: a retake that
                    // fails to decode leaves the picture before it standing.
                    writeShrunkJpeg(context, source, part)
                    check(part.renameTo(pendingMedicinePhoto(context))) { "cannot replace the waiting shot" }
                }.onFailure {
                    part.delete()
                    Log.w("MedicinePhotos", "cannot store the photograph", it)
                }.also { File(sharedCacheDir(context), MEDICINE_CAPTURE_FILE).delete() }.isSuccess
            }
            if (kept) onTaken()
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) keep(capture)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        picked?.let(keep)
    }
    // A tablet without a camera app throws on launch; the gallery is the same picture one step
    // later, so it stands in rather than the tap doing nothing — as on the progress page.
    val take: () -> Unit = {
        runCatching { camera.launch(capture) }.onFailure { _ ->
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    val image = rememberMedicineThumbnail(shown, version)
    val described = if (name.isBlank()) {
        stringResource(R.string.med_photo_unnamed)
    } else {
        stringResource(R.string.med_photo_of, name.trim())
    }
    Column(Modifier.fillMaxWidth()) {
        // Bottom-aligned: the outlined field keeps room above its border for the floating label,
        // and it is the border box, not the label, the picture stands level with.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.weight(1f)) { content() }
            val bitmap = image?.getOrNull()
            when {
                bitmap != null -> MedicinePhotoTile(bitmap, described, FORM_PHOTO_SIZE) {
                    shown?.let { context.openMedicinePhoto(it) }
                }

                shown != null && image == null -> PhotoPlaceholder(FORM_PHOTO_SIZE)

                else -> TakePhotoButton(take)
            }
        }
        if (image?.isSuccess == true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = take) { Text(stringResource(R.string.med_photo_retake)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.med_photo_remove)) }
            }
        }
    }
}

/**
 * The picture standing where a course's drawn glyph stands — today's doses, the course list — or
 * the glyph itself for a course without one. A picture whose file has gone falls back to the glyph
 * rather than to an empty square. [content] is that drawn glyph. Not [openable] inside a control of
 * its own — a chat's chip — where a tap is the chip's.
 */
@Composable
internal fun MedicineGlyph(
    photo: String,
    name: String,
    size: Dp,
    openable: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!isMedicinePhotoName(photo)) {
        content()
        return
    }
    val context = LocalContext.current
    val file = remember(context, photo) { File(medicinePhotoDir(context), photo) }
    val image = rememberMedicineThumbnail(file, 0)
    val bitmap = image?.getOrNull()
    when {
        bitmap != null -> MedicinePhotoTile(
            bitmap,
            stringResource(R.string.med_photo_of, name),
            size,
            onOpen = { context.openMedicinePhoto(file) }.takeIf { openable },
        )

        image == null -> PhotoPlaceholder(size)

        else -> content()
    }
}

/** Null while decoding, then the picture or why there is none. A missing file is not a crash. */
@Composable
internal fun rememberMedicineThumbnail(file: File?, version: Int): Result<ImageBitmap>? {
    val image by produceState<Result<ImageBitmap>?>(null, file, version) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching { thumbnail(checkNotNull(file) { "no photograph" }) }
        }
    }
    return image
}

/** The picture itself, named for a screen reader by the course it shows; a tap opens it full-size, where it opens. */
@Composable
private fun MedicinePhotoTile(bitmap: ImageBitmap, description: String, size: Dp, onOpen: (() -> Unit)?) {
    val openLabel = stringResource(R.string.med_photo_open)
    Image(
        bitmap,
        contentDescription = null,
        modifier = Modifier
            .then(if (onOpen != null) Modifier.minimumInteractiveComponentSize() else Modifier)
            .size(size)
            .clip(RoundedCornerShape(PwnCorners.medium))
            .then(
                onOpen?.let { Modifier.clickable(onClickLabel = openLabel, role = Role.Image, onClick = it) }
                    ?: Modifier,
            )
            .semantics { contentDescription = description },
        contentScale = ContentScale.Crop,
    )
}

/** The square a picture is about to fill, so the row does not jump when it arrives. */
@Composable
private fun PhotoPlaceholder(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(PwnCorners.medium))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Outlined in the field's own shape and line, so it reads as part of the name field it stands by. */
@Composable
private fun TakePhotoButton(onClick: () -> Unit) {
    val label = stringResource(R.string.med_photo_take)
    Surface(
        onClick = onClick,
        modifier = Modifier.size(FORM_PHOTO_SIZE).semantics {
            role = Role.Button
            contentDescription = label
        },
        shape = RoundedCornerShape(PwnCorners.medium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.PhotoCamera,
                contentDescription = null,
                tint = PwnMuted,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Full-size in whatever the phone views pictures with, as a progress photograph opens. */
private fun Context.openMedicinePhoto(file: File) {
    openAttachment(FileProvider.getUriForFile(this, "$packageName.photos", file).toString())
}

private const val MEDICINE_PHOTO_DIR = "medicines"

/** The camera's full-size shot of a packet, in the cache until it is read; [sweepLostCaptures] takes one left there. */
internal const val MEDICINE_CAPTURE_FILE = "medicine-capture.jpg"

/** The shot waiting for its course to be saved; [sweepLostCaptures] takes one no form came back for. */
internal const val MEDICINE_PENDING_FILE = "medicine-photo.jpg"
private val MEDICINE_PHOTO_NAME = Regex("""[0-9]{1,19}\.jpg""")

/** The outlined field's own height, so the picture stands level with the box it names. */
private val FORM_PHOTO_SIZE = 56.dp
