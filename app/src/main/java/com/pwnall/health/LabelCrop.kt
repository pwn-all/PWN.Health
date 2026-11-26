package com.pwnall.health

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The photograph, with the table on it marked out by the person who took it.
 *
 * One fixed rectangle in the middle of the viewfinder would cut most packets in half: real packets
 * have the table wrapped around a can, printed up one edge of a carton, or filling a corner of a
 * bag — anywhere but the centre. So nothing is assumed about where the table is. A finger drags a
 * box over it and that box is what the reader receives; drag nothing and the whole photograph goes,
 * which is right for the close-ups that need no help.
 *
 * The same screen serves both doors — the camera and the gallery — because a photograph picked
 * from the roll needs the marking every bit as much as a fresh one.
 *
 * Limitation: no zoom, no free rotation — only quarter turns — and no handles to nudge an edge with.
 * The box is one drag and one redraw; the reader turns the crop through its own ladder of angles
 * behind this screen.
 */
@Composable
internal fun LabelCropDialog(photo: Uri, onDismiss: () -> Unit, onCrop: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selection by remember(photo) { mutableStateOf<Rect?>(null) }
    var shown by remember(photo) { mutableStateOf(IntSize.Zero) }
    var cropping by remember(photo) { mutableStateOf(false) }
    // Saved, where the box above is not: a phone turned in the hand rebuilds the screen, the box was
    // drawn in the old screen's pixels, and the packet set upright by a tap is upright still.
    var quarterTurns by rememberSaveable(photo) { mutableIntStateOf(0) }
    val marked = stringResource(R.string.photo_crop_marked)
    val source by produceState<Photograph>(initialValue = Photograph.Loading, photo) {
        value = withContext(Dispatchers.Default) {
            runCatching { Photograph.Ready(decode(context, photo, PREVIEW_SIDE)) }
                .onFailure { Log.w("LabelCrop", "cannot show the photograph", it) }
                .getOrDefault(Photograph.Failed)
        }
    }
    // The preview is turned rather than drawn turned: the box is dragged in the coordinates of what
    // is on screen, and a rotation living only in the drawing would leave those two disagreeing.
    val preview = remember(source, quarterTurns) {
        (source as? Photograph.Ready)?.image?.turnedBy(quarterTurns)?.asImageBitmap()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val image = preview
            if (image == null) {
                // A decode that threw will not try again on its own: produceState is keyed on the
                // photograph alone, so a spinner alone here would turn for the life of the dialog
                // on that path. The failure says so instead, on the same scrim pill the hint uses —
                // white on it measures 21:1 over the black dialog — and Close, Turn and Read still
                // lie over it: Read hands the reader the whole photograph, which is where a crop
                // that cannot decode ends up anyway.
                if (source is Photograph.Failed) {
                    Surface(
                        color = Color.Black.copy(alpha = CONTROL_SCRIM_ALPHA),
                        shape = RoundedCornerShape(PwnCorners.largeIncreased),
                        modifier = Modifier.padding(horizontal = 32.dp),
                    ) {
                        // This state borrows the reader's line: the advice there — straight-on,
                        // close up, in good light — is the same advice a photograph that will not
                        // open calls for.
                        Text(
                            stringResource(R.string.photo_label_empty),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .aspectRatio(image.width.toFloat() / image.height)
                        .onSizeChanged { shown = it }
                        .pointerInput(photo) {
                            detectDragGestures(
                                onDragStart = { at -> selection = Rect(at, at) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    selection = selection?.let { box ->
                                        Rect(Offset(box.left, box.top), change.position)
                                    }
                                },
                            )
                        },
                ) {
                    Image(image, contentDescription = null, modifier = Modifier.fillMaxSize())
                    // The box is drawn nowhere else and written down nowhere else: this overlay is
                    // the only report that Read is about to cut the photograph rather than hand it
                    // over whole, so it says so instead of leaving the frame to the eyes alone.
                    Canvas(
                        Modifier.fillMaxSize().semantics {
                            if (markedBox(selection) != null) contentDescription = marked
                        },
                    ) { drawSelection(selection) }
                }
            }
            CropControls(
                cropping = cropping,
                onDismiss = onDismiss,
                onTurn = {
                    // The box was drawn over the old orientation and means nothing over the new one.
                    selection = null
                    quarterTurns = (quarterTurns + 1) % QUARTER_TURNS
                },
                onRead = {
                    if (!cropping) {
                        cropping = true
                        val drawn = selection
                        val size = shown
                        val turns = quarterTurns
                        scope.launch {
                            val chosen = withContext(Dispatchers.Default) {
                                cropped(context, photo, drawn, size, turns).also { cut ->
                                    // The cut cannot be stopped halfway, but a dialog closed while it
                                    // ran is owed no file: nobody is left to read or delete it. The
                                    // camera's own shot is not this cut's to delete — a turn of the
                                    // phone closes the dialog too, and the reopened one wants it.
                                    if (!isActive && cut != photo) cut.path?.let { File(it).delete() }
                                }
                            }
                            onCrop(chosen)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun CropControls(
    cropping: Boolean,
    onDismiss: () -> Unit,
    onTurn: () -> Unit,
    onRead: () -> Unit,
) {
    Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        // The buttons wear the hint's scrim rather than a lighter one: over the white of a table
        // reaching the top corners a white glyph measured 2.85:1 on the lighter disc, and 4.74:1
        // on this one.
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Color.Black.copy(alpha = CONTROL_SCRIM_ALPHA), CircleShape),
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.common_cancel),
                tint = Color.White,
            )
        }
        // Turning the packet upright here is worth more than it looks: the reader tries the upright
        // frame first and stops the moment a table reads, so a photograph handed over already
        // straight skips the turns it would otherwise pay for one at a time.
        IconButton(
            onClick = onTurn,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = CONTROL_SCRIM_ALPHA), CircleShape),
        ) {
            Icon(
                Icons.Outlined.Rotate90DegreesCw,
                contentDescription = stringResource(R.string.photo_crop_rotate),
                tint = Color.White,
            )
        }
        // On its own scrim pill, not bare on the photograph: what lies under it is a white
        // nutrition table for a living, and white-on-white was measured at 1.8:1 there.
        Surface(
            color = Color.Black.copy(alpha = CONTROL_SCRIM_ALPHA),
            shape = RoundedCornerShape(PwnCorners.largeIncreased),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
        ) {
            Text(
                stringResource(R.string.photo_crop_hint),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        // The name is read off the node rather than off the label alone, because the label steps
        // aside for the spinner below and a disabled button with nothing but a spinner inside it
        // is a node TalkBack can focus and cannot name.
        val read = stringResource(R.string.photo_crop_read)
        Surface(
            onClick = onRead,
            // The tap was already swallowed upstream while a crop ran; saying so on the node is
            // what makes the greyed fill true for TalkBack too, which would otherwise announce an
            // actionable button and ripple under a press that does nothing.
            enabled = !cropping,
            shape = RoundedCornerShape(percent = 50),
            color = if (cropping) PwnMuted else Color.White,
            // Floors rather than a size, with room either side of the words: a fixed box would clip
            // the label once a large font wraps it onto a second line.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .widthIn(min = READ_BUTTON_WIDTH)
                .heightIn(min = PwnDimensions.mediumButtonHeight)
                .semantics { if (cropping) contentDescription = read },
        ) {
            Box(
                Modifier.padding(horizontal = PwnDimensions.mediumButtonPadding, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                // The cut decodes at CROP_DECODE_SIDE, turns that copy when the photograph was
                // turned, and re-encodes it: long enough to need saying, and a swapped fill says
                // nothing. The label steps aside while it runs, which also settles its contrast:
                // black on the muted fill measures 3.68:1 in light theme, short of the 4.5:1 text
                // is held to, while the spinner that replaces it is a graphic and clears the 3:1
                // that applies to one.
                if (cropping) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        read,
                        color = Color.Black,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * What the decode of the photograph came back with.
 *
 * A failure is a state of its own rather than another null: [produceState] is keyed on the
 * photograph alone, so a decode that threw is never attempted again, and a loading indicator on
 * that path would have nothing left to resolve into.
 */
private sealed interface Photograph {
    data object Loading : Photograph

    data class Ready(val image: Bitmap) : Photograph

    data object Failed : Photograph
}

/** The box a drag left, or null when it was too short to have been meant as one. */
private fun markedBox(selection: Rect?): Rect? =
    selection?.let(::tidy)?.takeIf { it.width >= MIN_BOX_PX && it.height >= MIN_BOX_PX }

/** The box as drawn: everything outside it dimmed, the box itself in red. */
private fun DrawScope.drawSelection(selection: Rect?) {
    val box = markedBox(selection) ?: return
    val scrim = Color.Black.copy(alpha = 0.55f)
    drawRect(scrim, size = Size(size.width, box.top))
    drawRect(scrim, topLeft = Offset(0f, box.bottom), size = Size(size.width, size.height - box.bottom))
    drawRect(scrim, topLeft = Offset(0f, box.top), size = Size(box.left, box.height))
    drawRect(scrim, topLeft = Offset(box.right, box.top), size = Size(size.width - box.right, box.height))
    drawRect(ScanFrame, topLeft = box.topLeft, size = box.size, style = Stroke(width = FRAME_STROKE_PX))
}

/** A drag runs in any direction; the box it leaves is the same either way. */
private fun tidy(drawn: Rect): Rect = Rect(
    minOf(drawn.left, drawn.right),
    minOf(drawn.top, drawn.bottom),
    maxOf(drawn.left, drawn.right),
    maxOf(drawn.top, drawn.bottom),
)

/**
 * The drawn box as pixels of the photograph, or null when there is no box worth cutting to.
 *
 * The box was drawn over a preview a few hundred pixels tall and is applied to a decode of several
 * thousand — only the fractions survive that difference, so fractions are what cross. A drag too
 * short to have been meant is no box at all, and neither is one drawn before the screen has a size.
 */
internal fun cropBounds(drawn: Rect, shown: IntSize, photo: IntSize): IntRect? {
    val box = tidy(drawn)
    val marked = box.width >= MIN_BOX_PX && box.height >= MIN_BOX_PX
    if (!marked || !shown.measured || !photo.measured) return null
    val left = (box.left / shown.width * photo.width).toInt().coerceIn(0, photo.width - 1)
    val top = (box.top / shown.height * photo.height).toInt().coerceIn(0, photo.height - 1)
    val right = (box.right / shown.width * photo.width).toInt().coerceIn(left + 1, photo.width)
    val bottom = (box.bottom / shown.height * photo.height).toInt().coerceIn(top + 1, photo.height)
    return IntRect(left, top, right, bottom)
}

/**
 * The photograph cut down to the drawn box and written to the cache for the reader to open. A
 * photograph that will not decode, or a drag that never happened, leaves the original: the
 * reader's ladder still gets its chance at the whole frame.
 */
private fun cropped(context: Context, photo: Uri, selection: Rect?, shown: IntSize, turns: Int): Uri {
    val drawn = selection
    if (drawn == null && turns == 0) return photo
    return runCatching {
        val decoded = decode(context, photo, CROP_DECODE_SIDE)
        // Up to 64 MB at this side: the unturned copy goes the moment a turned one exists, rather
        // than standing beside it and the cut through the whole compression.
        val image = decoded.turnedBy(turns).also { if (it !== decoded) decoded.recycle() }
        if (drawn == null) return@runCatching write(context, image)
        val bounds = cropBounds(drawn, shown, IntSize(image.width, image.height))
        bounds?.let { box -> cut(context, image, box) } ?: write(context, image)
    }.onFailure { Log.w("LabelCrop", "crop failed, reading the whole photograph", it) }
        .getOrDefault(photo)
}

/** The cut itself, in the cache under one fixed name that every shot overwrites. */
private fun cut(context: Context, image: Bitmap, bounds: IntRect): Uri {
    val piece = Bitmap.createBitmap(image, bounds.left, bounds.top, bounds.width, bounds.height)
    if (piece !== image) image.recycle()
    return write(context, piece)
}

private fun write(context: Context, image: Bitmap): Uri {
    val file = File(context.cacheDir, LABEL_CROP_FILE)
    file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, CROP_JPEG_QUALITY, it) }
    image.recycle()
    return Uri.fromFile(file)
}

/** The photograph at [turns] quarter turns clockwise; nothing is copied for no turn at all. */
private fun Bitmap.turnedBy(turns: Int): Bitmap {
    val degrees = (turns % QUARTER_TURNS) * DEGREES_PER_QUARTER_TURN
    if (degrees == 0) return this
    return Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}

/** A size that has been laid out or decoded; zero means neither has happened yet. */
private val IntSize.measured: Boolean get() = width > 0 && height > 0

/** [ImageDecoder] applies the camera's EXIF rotation, so the box is drawn over what is stored. */
private fun decode(context: Context, photo: Uri, longSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, photo)) { decoder, info, _ ->
        decoder.setTargetSampleSize(sampleSizeFor(maxOf(info.size.width, info.size.height), longSide))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

/**
 * Halvings that bring a photograph under [longSide]. Powers of two because that is the reduction a
 * JPEG decoder performs while decoding, rather than on a full-size bitmap it first has to hold.
 */
private fun sampleSizeFor(photoSide: Int, longSide: Int): Int {
    var sample = 1
    while (photoSide / sample > longSide) sample *= 2
    return sample
}

/**
 * The scrim under every control laid over the photograph: white on it over a white label measures
 * 4.74:1, where the photograph itself can be anything.
 */
private const val CONTROL_SCRIM_ALPHA = 0.55f

/** The narrowest the Read button is drawn, so a short label still makes a button worth aiming at. */
private val READ_BUTTON_WIDTH = 200.dp

/** Red: over a photograph no theme colour reads better. */
private val ScanFrame = Color(0xFFE53935)
private const val FRAME_STROKE_PX = 4f

/** Four of them to a full circle, ninety degrees apiece. */
private const val QUARTER_TURNS = 4
private const val DEGREES_PER_QUARTER_TURN = 90

/** Below this a drag is a tap that slipped, not a box; the whole photograph goes instead. */
private const val MIN_BOX_PX = 24f

/**
 * How large the photograph may be decoded, on its long side, before the box is cut out of it. The
 * reader decodes what it is handed at 2048, so the pixels a 50 MP sensor adds above this bound
 * reach the reader as nothing but 200 MB of ARGB_8888 in one allocation — while at this bound even
 * a tenth of the frame still carries what a 12 MP phone reads labels with today.
 */
private const val CROP_DECODE_SIDE = 4096
private const val PREVIEW_SIDE = 1024

/** The cut's one name in the cache; [sweepLostCaptures] knows it too, for a cut nobody read. */
internal const val LABEL_CROP_FILE = "label_crop.jpg"
private const val CROP_JPEG_QUALITY = 92
