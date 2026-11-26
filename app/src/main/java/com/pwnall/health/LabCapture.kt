package com.pwnall.health

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** The folder the lab sheets' photographs live in, under the app's own files; the FileProvider path shares the name. */
internal const val LAB_CAPTURE_DIR = "lab-captures"

internal fun labCaptureDir(context: Context): File = File(context.filesDir, LAB_CAPTURE_DIR)

/** Each camera result has its own durable attachment; the next shot cannot replace it. */
internal fun newLabCapture(context: Context): Uri {
    val file = File.createTempFile("lab-", ".jpg", labCaptureDir(context).apply { mkdirs() })
    return FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
}

/**
 * The longer edge a sheet's photograph is kept at: an A4 page at 300 dots to the inch, which is what
 * a reader of print is fed and as much as a sheet's small type needs.
 *
 * A sheet kept as the camera wrote it runs to tens of megabytes in a high-resolution mode, and it is
 * the one picture a backup has to hold whole in the heap on its way back in.
 */
internal const val LAB_SHEET_SIDE = 3_508

/** Whether a photograph of this size is larger than a sheet is kept at; a smaller one is left as it is. */
internal fun outgrowsLabSheet(width: Int, height: Int): Boolean = maxOf(width, height) > LAB_SHEET_SIDE

/**
 * Brings a sheet the camera has just written down to [LAB_SHEET_SIDE], under the name it already
 * has; anything that is not a capture of ours, and a sheet already that small, is left alone, so
 * asking twice costs a look at the file's header. Written beside the file and renamed over it: a
 * write that breaks off leaves the camera's own picture standing. Blocking — call it off the main
 * thread.
 */
internal fun shrinkLabCapture(context: Context, uri: Uri) {
    if (uri.authority != "${context.packageName}.photos") return
    val file = File(labCaptureDir(context), labCaptureFileName(uri.toString()) ?: return)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (!outgrowsLabSheet(bounds.outWidth, bounds.outHeight)) return
    val part = File(file.path + PART_SUFFIX)
    runCatching {
        writeShrunkJpeg(context, uri, part, LAB_SHEET_SIDE)
        check(part.renameTo(file)) { "the shrunk sheet could not take its name" }
    }.onFailure { part.delete() }
}

/**
 * The file a lab attachment names inside [LAB_CAPTURE_DIR], or null for one kept elsewhere — a
 * gallery picture, a PDF another app owns — which is a reference and travels as one. A name with a
 * directory in it is not one this app wrote, and is not believed.
 */
internal fun labCaptureFileName(uri: String): String? {
    val name = uri.substringAfter("/$LAB_CAPTURE_DIR/", "").substringBefore('?').substringBefore('#')
    return name.takeIf { it.isNotEmpty() && it == File(it).name && !it.startsWith('.') }
}

/**
 * Whether [name], read out of a backup, is the whole of what a row's reference would name. Finding
 * some name in it is not enough: [labCaptureFileName] reads a reference and stops at `?` and `#`,
 * so `x?/../../databases/steps.db-journal` answers "x", and writing the bytes under the entire
 * string would put them outside the folder.
 */
internal fun isLabCaptureName(name: String): Boolean = labCaptureFileName("/$LAB_CAPTURE_DIR/$name") == name
