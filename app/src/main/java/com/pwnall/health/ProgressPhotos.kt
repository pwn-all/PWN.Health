package com.pwnall.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Progress photographs: pictures of the person, taken on the day of a weigh-in and kept with it.
 *
 * Everything else this app photographs is an input to a recogniser and is deleted once read. These
 * are the opposite — the photograph is the record — so they are the one kind the app keeps: shrunk
 * to [PROGRESS_SIDE] on the longer edge (a phone camera's 12 megapixels are for printing, not for
 * comparing two silhouettes on a screen), written into the app's private files directory where no
 * gallery indexes them, and listed in [ProgressPhoto] rows by day. The backup carries the bytes.
 *
 * The camera and the gallery are the system's own — no CAMERA permission, no media permission —
 * exactly as the meal photographs do it.
 *
 * Limitation: no side-by-side compare view, no pinch-zoom viewer. A tap opens the full-size file in
 * whatever the phone views pictures with.
 */
internal fun progressPhotoDir(context: Context): File = File(context.filesDir, PROGRESS_DIR)

/** The moment it was taken is the name: unique on this phone, and the same name on a restore. */
internal fun progressPhotoName(takenAt: Long): String = "$takenAt.jpg"

/** The photograph as a URI another app may open for the full-size view. */
internal fun progressPhotoUri(context: Context, photo: ProgressPhoto): Uri = FileProvider.getUriForFile(
    context,
    "${context.packageName}.photos",
    File(progressPhotoDir(context), photo.file),
)

/**
 * Copies [source] into the progress directory, shrunk on the way, and describes the copy.
 *
 * Decoded through [ImageDecoder] for the same reason the classifiers use it: it applies the EXIF
 * rotation a camera writes and scales during decode, so a sideways 48 MB bitmap never exists.
 * Blocking — call it off the main thread.
 */
internal fun storeProgressPhoto(context: Context, source: Uri, day: String, takenAt: Long): ProgressPhoto {
    val dir = progressPhotoDir(context).apply { mkdirs() }
    // Two photographs can claim the same millisecond, since a picked one brings its own from
    // years ago: the same picture imported twice lands on it every time. The next free one is the
    // row's as well as the file's, so the name stays derivable from the row — which is how a
    // backup, holding names for nothing, puts the bytes back beside the right day.
    val stamp = generateSequence(takenAt) { it + 1 }.first { !File(dir, progressPhotoName(it)).exists() }
    val name = progressPhotoName(stamp)
    writeShrunkJpeg(context, source, File(dir, name))
    return ProgressPhoto(day = day, takenAt = stamp, file = name)
}

/**
 * [source] decoded upright and no larger than [longest] on its longer edge, written to [target] as
 * a JPEG: the one way this app keeps a photograph, of a person, of a box of tablets or of the view
 * from a walk. [PROGRESS_SIDE] unless the caller keeps more. Blocking.
 */
internal fun writeShrunkJpeg(context: Context, source: Uri, target: File, longest: Int = PROGRESS_SIDE) {
    val image = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(context.contentResolver, source),
    ) { decoder, info, _ ->
        val (width, height) = shrunkSize(info.size.width, info.size.height, longest)
        decoder.setTargetSize(width, height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    // A write that breaks off — disk full, the process on its way out — leaves no half-picture
    // behind for a row to point at.
    runCatching {
        check(target.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, PROGRESS_JPEG_QUALITY, it) }) {
            "JPEG compression failed"
        }
    }.onFailure { target.delete() }.also { image.recycle() }.getOrThrow()
}

/**
 * When a picked picture says it was taken, in milliseconds, or null where it does not say.
 *
 * Read out of the file's own EXIF rather than asked of the gallery: the photo picker hands over a
 * URI granted for this one read and nothing else, so there is no media row to query and no media
 * permission to ask for. Blocking — call it off the main thread.
 *
 * Limitation: EXIF only. A screenshot or a picture saved out of a chat carries none and falls back
 * to the day the form is for.
 */
internal fun photoTakenAt(context: Context, source: Uri): Long? = runCatching {
    context.contentResolver.openInputStream(source)?.use { stream ->
        ExifInterface(stream).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let(::exifTakenAt)
    }
}.getOrNull()

/**
 * EXIF's own stamp — "2019:07:13 18:24:03", colons in the date and all — as a moment in time.
 *
 * A camera writes it without a zone: it is the wall clock of wherever the picture was taken, so it
 * is read against this phone's, which is the same reading the gallery shows.
 */
internal fun exifTakenAt(stamp: String, zone: ZoneId = ZoneId.systemDefault()): Long? = runCatching {
    LocalDateTime.parse(stamp, EXIF_STAMP).atZone(zone).toInstant().toEpochMilli()
}.getOrNull()

private val EXIF_STAMP = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/**
 * The size a photograph is stored at: the longer edge brought down to [PROGRESS_SIDE], the shorter
 * following in proportion, and a small picture left exactly as it is — enlarging invents nothing.
 */
internal fun shrunkSize(width: Int, height: Int, longest: Int = PROGRESS_SIDE): Pair<Int, Int> {
    val longer = maxOf(width, height)
    if (longer <= longest || longer == 0) return width to height
    val scale = longest.toDouble() / longer
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

/**
 * The door to the gallery, standing in the weight form.
 *
 * The form asks for one number; the photographs are a record of their own, growing month by month,
 * and a horizontal strip inside a scrolling form is no place to look back through them. The card
 * says how many there are and opens the page that shows them.
 */
@Composable
internal fun ProgressPhotosCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val dao = remember(context) { StepsDb.get(context).dao() }
    val photos by remember(dao) { dao.progressPhotoGallery() }.collectAsStateWithLifecycle(emptyList())
    PwnCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.PhotoLibrary, null, tint = PwnMuted, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.progress_photo_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (photos.isEmpty()) {
                        stringResource(R.string.progress_photo_hint)
                    } else {
                        pluralStringResource(R.plurals.progress_photo_count, photos.size, photos.size)
                    },
                    color = PwnMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // A drawn chevron rather than the "›" character: the card merges its text into one
            // spoken label, and a glyph typed as text would join it.
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = PwnMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * The gallery: every progress photograph there is, newest first, and the way out to another app.
 *
 * Export is a share rather than a folder write. Nothing here may touch the network, and the phone
 * already knows how to put files into Drive, Files, a chat or a cable — so the picture goes to
 * whichever of those the person picks, and the app neither chooses nor keeps a copy.
 *
 * Limitation: the share carries the JPEGs themselves, not a zip. A share sheet is a handful of
 * attachments in practice, and a target app may balk at two hundred photographs.
 */
@Composable
internal fun ProgressPhotosScreen(modifier: Modifier, day: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember(context) { StepsDb.get(context).dao() }
    val photos by remember(dao) { dao.progressPhotoGallery() }.collectAsStateWithLifecycle(emptyList())
    val remove = rememberProgressPhotoRemover()
    val past = photos.filterNot { it.day == day }
    Column(modifier.fillMaxSize()) {
        // The app's own top bar, share in its trailing slot: it keeps the title centred between
        // sides of equal width and lets a long translation at a large font take a second line,
        // where a hand-drawn header would cut it to one.
        PwnTopBar(stringResource(R.string.progress_photo_title), onBack) {
            IconButton(
                onClick = { context.shareProgressPhotos(photos) },
                enabled = photos.isNotEmpty(),
                colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
            ) {
                Icon(Icons.Outlined.IosShare, stringResource(R.string.progress_photo_export))
            }
        }
        // A lazy grid, and the page's only scroller: a year of weigh-ins is hundreds of
        // photographs, and decoding every one of them at 320 px to draw the eight on screen is
        // tens of megabytes of bitmap for a page nobody has scrolled yet.
        val gridState = rememberLazyGridState()
        RegisterPanelScroll(gridState)
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(GALLERY_TILE_MIN),
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    ProgressPhotosField(day)
                    if (past.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(R.string.progress_photo_earlier),
                            style = MaterialTheme.typography.labelLarge,
                            color = PwnMuted,
                        )
                    }
                }
            }
            items(past, key = ProgressPhoto::id) { photo ->
                ProgressThumbnail(
                    photo = photo,
                    caption = photo.day.asPhotoDay(),
                    onOpen = { context.openAttachment(progressPhotoUri(context, photo).toString()) },
                    onRemove = { remove(photo) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            }
        }
    }
}

/** How narrow a gallery tile is allowed to get before the grid drops to fewer columns. */
private val GALLERY_TILE_MIN = 104.dp

/**
 * Deleting a photograph: the question first, then the row and the file together, not cancellable
 * half way.
 *
 * The question lives here rather than at either call site, so the strip in the weight form and the
 * grid on the gallery page both get it by asking for the remover — and a photograph deleted from
 * one does not survive in the other. It is asked at all because this is the one picture the app
 * keeps: the body it was of is a month older by now, and a cross tapped by mistake in the corner of
 * a thumbnail cannot be taken again.
 */
@Composable
private fun rememberProgressPhotoRemover(): (ProgressPhoto) -> Unit {
    val context = LocalContext.current
    val dao = remember(context) { StepsDb.get(context).dao() }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ProgressPhoto?>(null) }
    pending?.let { photo ->
        DestructiveDialog(
            title = stringResource(R.string.progress_photo_delete_title),
            body = stringResource(R.string.progress_photo_delete_body),
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                scope.launch {
                    withContext(NonCancellable) {
                        // The picture first: a process that dies between the two leaves a row whose
                        // tile is empty and can be deleted again, not a photograph of a body that
                        // no list shows and no button reaches.
                        withContext(Dispatchers.IO) { File(progressPhotoDir(context), photo.file).delete() }
                        dao.deleteProgressPhoto(photo.id)
                    }
                }
            },
        )
    }
    return { pending = it }
}

/**
 * Hands every photograph to whatever the person picks — a cloud folder, a chat, a cable.
 *
 * One grant per file: the receiving app is named by the chooser and not by this app, so the read
 * permission travels with the intent rather than being granted to a package in advance.
 */
private fun Context.shareProgressPhotos(photos: List<ProgressPhoto>) {
    if (photos.isEmpty()) return
    val uris = ArrayList(photos.map { progressPhotoUri(this, it) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType("image/jpeg")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // A phone with nothing that receives pictures is a phone, not a bug.
    runCatching {
        startActivity(
            Intent.createChooser(intent, getString(R.string.progress_photo_export))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { if (it !is ActivityNotFoundException) throw it }
}

/**
 * The photographs of one day and the two ways of adding one, with everything earlier beneath them.
 *
 * Writes as it goes: a photograph is kept the moment it is taken, not when the weight is saved,
 * because the picture is true whether or not the number gets typed. Only today's: everything
 * earlier is the grid below it on the same page.
 */
@Composable
private fun ProgressPhotosField(day: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember(context) { StepsDb.get(context).dao() }
    val today by remember(day, dao) { dao.progressPhotos(day) }.collectAsStateWithLifecycle(emptyList())
    val capture = remember(context) {
        val shot = File(sharedCacheDir(context), CAPTURE_FILE)
        FileProvider.getUriForFile(context, "${context.packageName}.photos", shot)
    }
    val keep: (Uri, Boolean) -> Unit = { source, picked ->
        scope.launch {
            // The screen can close under a picker; a photograph that was taken is kept regardless.
            withContext(NonCancellable) {
                val stored = withContext(Dispatchers.IO) {
                    runCatching {
                        // A picture out of the gallery is filed under the day it was taken, which
                        // is what lands an import of last spring's photographs in last spring
                        // rather than all of them on top of today. A camera shot is filed under
                        // the day the form is for — that weigh-in may itself be back-dated — and
                        // so is a picked picture that says nothing about itself.
                        val taken = if (picked) photoTakenAt(context, source) else null
                        val on = taken?.let {
                            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                        }
                        storeProgressPhoto(context, source, on ?: day, taken ?: System.currentTimeMillis())
                    }
                        .onFailure { Log.w("ProgressPhotos", "cannot store the photograph", it) }
                        .getOrNull()
                }
                stored?.let { photo ->
                    // Uncaught here it would take the app down, and the copy just written would lie
                    // in the files directory with no row to list it: it goes instead.
                    runCatching { dao.saveProgressPhoto(photo) }.onFailure {
                        Log.w("ProgressPhotos", "cannot record the photograph", it)
                        File(progressPhotoDir(context), photo.file).delete()
                    }
                }
                File(sharedCacheDir(context), CAPTURE_FILE).delete()
            }
        }
    }
    val remove = rememberProgressPhotoRemover()
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) keep(capture, false)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        picked?.let { keep(it, true) }
    }

    Column(Modifier.fillMaxWidth()) {
        // Half a screen each, and Material's own 24dp of content padding is what these labels
        // cannot afford: on a 360dp phone a weight(1f) button is 155dp, and 48dp of padding plus
        // the 18dp glyph and its 8dp gap would leave only 81dp for the words. The 8dp the
        // medicine screen's identical pair uses raises that to 113dp. A label longer than
        // that wraps rather than being cut short — at a large font every language's is — and the
        // intrinsic height grows the neighbour with it, so the pair still ends level.
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    // A tablet without a camera app throws on launch; the gallery is the same
                    // feature one step later, so it stands in rather than the tap doing nothing.
                    runCatching { camera.launch(capture) }.onFailure { _ ->
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = PHOTO_BUTTON_HEIGHT).fillMaxHeight(),
                contentPadding = PHOTO_BUTTON_PADDING,
            ) {
                Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.progress_photo_take), textAlign = TextAlign.Center)
            }
            OutlinedButton(
                onClick = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.weight(1f).heightIn(min = PHOTO_BUTTON_HEIGHT).fillMaxHeight(),
                contentPadding = PHOTO_BUTTON_PADDING,
            ) {
                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.progress_photo_pick), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.progress_photo_hint),
            style = MaterialTheme.typography.bodySmall,
            color = PwnMuted,
        )
        // The day's own pictures under the two doors and the line about them rather than over
        // them: the pair of buttons is the one fixed thing on a page that grows a row every time
        // it is used, so it is what everything else is found under.
        if (today.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ProgressPhotoStrip(today, onRemove = remove)
        }
    }
}

/** A row of thumbnails that scrolls sideways, each opening full-size on a tap. */
@Composable
private fun ProgressPhotoStrip(
    photos: List<ProgressPhoto>,
    onRemove: (ProgressPhoto) -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        photos.forEach { photo ->
            ProgressThumbnail(
                photo = photo,
                caption = photo.day.asPhotoDay(),
                onOpen = { context.openAttachment(progressPhotoUri(context, photo).toString()) },
                onRemove = { onRemove(photo) },
                // A strip is a row of fixed squares; the grid's tiles take the width their column
                // gives them instead.
                modifier = Modifier.size(THUMBNAIL_SIZE),
            )
        }
    }
}

@Composable
private fun ProgressThumbnail(
    photo: ProgressPhoto,
    caption: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Decoded small and off the main thread; a missing file is an empty tile, not a crash.
    val image by produceState<ImageBitmap?>(initialValue = null, photo.file) {
        value = withContext(Dispatchers.IO) {
            runCatching { thumbnail(File(progressPhotoDir(context), photo.file)) }.getOrNull()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // The picture is decoration to a screen reader and the caption is a sibling outside this
        // Box, so the tap target itself would announce nothing at all — an activatable node with an
        // empty name. The date under the tile is the name it is given.
        Box(
            modifier
                .clip(RoundedCornerShape(PwnCorners.medium))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = caption },
        ) {
            image?.let {
                Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            // The disc stays the small one drawn in the corner; the button around it is the full
            // 48dp, where a size handed to the IconButton itself would cut its target down to the disc.
            // Named with the date, so a strip of them is not a row of identical "Delete" stops.
            IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
                Box(
                    Modifier.size(24.dp).background(Color.Black.copy(alpha = SCRIM_ALPHA), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.progress_photo_delete_dated, caption),
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // Drawn only: the tile above already carries the date as its name, and a second node
        // would read every photograph's date twice.
        Text(
            caption,
            Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.bodySmall,
            color = PwnMuted,
        )
    }
}

/** A kept photograph decoded small enough for a tile. Blocking, and it throws for a missing file. */
internal fun thumbnail(file: File): ImageBitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val (width, height) = shrunkSize(info.size.width, info.size.height, THUMBNAIL_DECODE_SIDE)
        decoder.setTargetSize(width, height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.asImageBitmap()

/**
 * The day under a photograph, written the way the phone writes dates: 12/31/1999 in one place,
 * 31.12.1999 in another, 1999/12/31 in a third.
 *
 * The pattern is looked up per call rather than kept. It is microseconds against decoding
 * a JPEG for the same tile, and a formatter held in a val would go on writing the old locale's
 * order after the phone's language is changed under the app.
 */
private fun String.asPhotoDay(): String = runCatching {
    LocalDate.parse(this).format(DateTimeFormatter.ofPattern(photoDayPattern(Locale.getDefault())))
}.getOrDefault(this)

/**
 * The locale's own short date with the year widened to four digits.
 *
 * Short, because a date belongs under a thumbnail without becoming the widest thing in the column —
 * and because it is what the person reads on every other screen of the phone. Four digits, because
 * these photographs exist to be compared across years, and "12/31/99" is the one figure that
 * matters left half-said.
 */
internal fun photoDayPattern(locale: Locale): String =
    DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
        .replace(Regex("y+"), "yyyy")

/**
 * The camera's files that a lost screen left in the cache, removed at a fresh start once an hour
 * old.
 *
 * A shot is written full size under one fixed name — this screen's, the meal's, the cut of a
 * packet's label, a medicine's and a workout template's — and deleted by whatever reads it. When
 * Android closes the app behind the camera and the person comes back to some other page, nothing
 * reads it: a twelve-megapixel photograph of a body would lie in the cache, in none of the app's
 * lists and with no button anywhere to delete it, until the next shot happened to overwrite it. An
 * hour is [IN_FLIGHT_STALE_MS], as it is for a walk's shot: a camera still open over another window
 * of the app is younger than that. The camera's own files lie in the folder the camera app is
 * handed ([SHARED_CACHE_DIR]); the cut of a label is the app's own and lies in the cache's root.
 *
 * So do the two pictures that wait, shrunk, for a form to be saved — a course's packet and a
 * template's picture — with the part of one a closed app was writing. A fresh start has no form open
 * to save them with, and nothing else takes them away.
 */
internal fun sweepLostCaptures(cacheDir: File, now: Long) {
    val shared = File(cacheDir, SHARED_CACHE_DIR)
    val shots = listOf(CAPTURE_FILE, MEAL_PHOTO_FILE, MEDICINE_CAPTURE_FILE, ROUTINE_CAPTURE_FILE)
    val waiting = listOf(MEDICINE_PENDING_FILE, ROUTINE_PENDING_FILE).flatMap { listOf(it, "$it.part") }
    (shots.map { File(shared, it) } + (waiting + LABEL_CROP_FILE).map { File(cacheDir, it) })
        .filter { it.isFile && it.lastModified() < now - IN_FLIGHT_STALE_MS }
        .forEach { it.delete() }
}

private const val PROGRESS_DIR = "progress"
private const val CAPTURE_FILE = "progress-capture.jpg"
internal const val PROGRESS_SIDE = 1_280
private const val PROGRESS_JPEG_QUALITY = 85
private const val THUMBNAIL_DECODE_SIDE = 320
private const val SCRIM_ALPHA = 0.45f
private val THUMBNAIL_SIZE = 96.dp

/** Half-width buttons cannot pay Material's 24dp; see the take/pick row for what it would cost. */
private val PHOTO_BUTTON_PADDING = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/**
 * A minimum and not a height. 48dp less the 8dp of padding above and below leaves 32dp of content
 * box, and a 14sp labelLarge line is 20dp at font scale 1.0 but 36dp at the 180% step — where a
 * fixed box clips the words and a floored one simply grows. The project's other text rows held to
 * 48dp — PwnDesign's picker row, LabsScreen's attachment row — are floored the same way.
 */
private val PHOTO_BUTTON_HEIGHT = 48.dp
