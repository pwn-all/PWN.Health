package com.pwnall.health

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import androidx.core.graphics.createBitmap
import com.pwnall.health.LabelReader.toRaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlin.math.roundToInt

/** What a PDF's copy in the cache is named from, for [sweepExportLeftovers] to find one a closed app left. */
internal const val LAB_PDF_COPY_PREFIX = "lab-import-"

/** Bounded, offline document reading. Each PDF page remains a separate visit until reviewed. */
internal object LabDocumentReader {
    private val mutex = Mutex()

    suspend fun read(
        context: Context,
        uri: Uri,
        today: LocalDate,
        monthFirst: Boolean,
        // Asked only when a PDF will not open without one; true when the last one was refused.
        password: suspend (wrong: Boolean) -> String = { throw IOException("PDF is locked") },
        onPage: suspend (Int, Int) -> Unit = { _, _ -> },
    ): List<LabSheetScan> = readPages(context, uri, password, onPage) { scan(context, it, today, monthFirst) }

    /** An allergy panel's pages, each read as a table of allergen rows ([readAllergyPanel]). */
    suspend fun readPanel(
        context: Context,
        uri: Uri,
        today: LocalDate,
        monthFirst: Boolean,
        password: suspend (wrong: Boolean) -> String,
        onPage: suspend (Int, Int) -> Unit,
    ): List<AllergyPanelScan> = readPages(context, uri, password, onPage) { scanPanel(context, it, today, monthFirst) }

    private suspend fun <T> readPages(
        context: Context,
        uri: Uri,
        password: suspend (wrong: Boolean) -> String,
        onPage: suspend (Int, Int) -> Unit,
        scan: suspend (Raster) -> T,
    ): List<T> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pdf = context.contentResolver.openInputStream(uri)?.use {
                it.readNBytes(5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
            } ?: throw IOException("Cannot open document")
            if (pdf) {
                readPdf(context, uri, password, onPage, scan)
            } else {
                onPage(1, 1)
                val bitmap = LabelReader.decode(context, uri)
                val raster = try {
                    bitmap.toRaster()
                } finally {
                    bitmap.recycle()
                }
                listOf(scan(raster))
            }
        }
    }

    @Suppress("NestedBlockDepth") // Nested resource scopes close descriptors, pages and streams on every exit.
    private suspend fun <T> readPdf(
        context: Context,
        uri: Uri,
        password: suspend (wrong: Boolean) -> String,
        onPage: suspend (Int, Int) -> Unit,
        scan: suspend (Raster) -> T,
    ): List<T> {
        // Document providers may return a pipe; PdfRenderer needs a seekable descriptor.
        val file = File.createTempFile(LAB_PDF_COPY_PREFIX, ".pdf", context.cacheDir)
        try {
            copyPdf(context, uri, file)
            val pages: suspend (Int, (Int) -> Raster) -> List<T> = { count, raster ->
                if (count !in 1..MAX_PAGES) throw IOException("PDF page limit exceeded")
                (0 until count).map { index ->
                    currentCoroutineContext().ensureActive()
                    onPage(index + 1, count)
                    scan(raster(index))
                }
            }
            // The platform renderer refuses a locked file with a SecurityException, and that is the
            // only thing that asks for a password: a PDF that opens without one never prompts.
            var typed: String? = null
            while (true) {
                try {
                    // A fresh descriptor each try: a renderer that threw may or may not have closed its own.
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        val given = typed
                        // The backport takes a password on every phone from 13 with the PDF module at
                        // extension 13, and on every phone from 15, where the module is built in.
                        if (given != null &&
                            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PDF_PASSWORD_EXTENSION
                        ) {
                            return readLocked(descriptor, given, pages)
                        }
                        PdfRenderer(descriptor).use { renderer ->
                            return pages(renderer.pageCount) { index -> renderer.openPage(index).use(::render) }
                        }
                    }
                } catch (locked: SecurityException) {
                    // Never a question the phone cannot use the answer to: there it is a file it cannot read.
                    if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) < PDF_PASSWORD_EXTENSION) {
                        throw IOException("This phone's PDF renderer takes no password", locked)
                    }
                    typed = password(typed != null)
                }
            }
        } finally {
            file.delete()
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = PDF_PASSWORD_EXTENSION)
    private suspend fun <T> readLocked(
        descriptor: ParcelFileDescriptor,
        password: String,
        pages: suspend (Int, (Int) -> Raster) -> List<T>,
    ): List<T> =
        PdfRendererPreV(descriptor, LoadParams.Builder().setPassword(password).build()).use { renderer ->
            val display = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build()
            pages(renderer.pageCount) { index ->
                renderer.openPage(index).use { page ->
                    render(page.width, page.height) { page.render(it, null, null, display) }
                }
            }
        }

    @Suppress("NestedBlockDepth") // Nested resource scopes close descriptors, pages and streams on every exit.
    private suspend fun copyPdf(context: Context, uri: Uri, target: File) {
        val work = currentCoroutineContext()
        context.contentResolver.openInputStream(uri)?.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    work.ensureActive()
                    val size = source.read(buffer)
                    if (size < 0) break
                    total += size
                    if (total > MAX_BYTES) throw IOException("PDF size limit exceeded")
                    sink.write(buffer, 0, size)
                }
            }
        } ?: throw IOException("Cannot open PDF")
    }

    private fun render(page: PdfRenderer.Page): Raster = render(page.width, page.height) {
        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    }

    private fun render(width: Int, height: Int, draw: (Bitmap) -> Unit): Raster {
        val scale = SIDE.toDouble() / maxOf(width, height).coerceAtLeast(1)
        val bitmap = createBitmap(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        return try {
            bitmap.eraseColor(Color.WHITE)
            draw(bitmap)
            bitmap.toRaster()
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun scan(context: Context, raster: Raster, today: LocalDate, monthFirst: Boolean): LabSheetScan {
        val work = currentCoroutineContext()
        return LabelReader.withEngine(context) { ocr ->
            var best = LabSheetScan()
            for (degrees in listOf(0, 90, 270, 180)) {
                work.ensureActive()
                val turned = raster.turned(degrees)
                val regions = ocr.regions(turned, longSide = 1600.0)
                for (script in OcrScript.entries) {
                    work.ensureActive()
                    val candidate = scanLabSheet(ocr.read(turned, regions, script), today, monthFirst)
                    if (candidate.veterinary) return@withEngine candidate
                    // Keep a single interpretation, never union contradictory OCR passes.
                    if (candidate.betterThan(best)) best = candidate
                }
                if (best.settlesPage(degrees)) break
            }
            best
        }
    }

    /**
     * A panel's page read like a sheet's — each turn until one gives rows, each script — but kept as
     * one script's reading whole ([bestAllergyPanel]), with the day the page says it was drawn.
     */
    private suspend fun scanPanel(
        context: Context,
        raster: Raster,
        today: LocalDate,
        monthFirst: Boolean,
    ): AllergyPanelScan {
        val work = currentCoroutineContext()
        return LabelReader.withEngine(context) { ocr ->
            for (degrees in listOf(0, 90, 270, 180)) {
                work.ensureActive()
                val turned = raster.turned(degrees)
                val regions = ocr.regions(turned, longSide = 1600.0)
                val best = bestAllergyPanel(
                    OcrScript.entries.map { script ->
                        work.ensureActive()
                        val boxes = ocr.read(turned, regions, script)
                        AllergyPanelScan(readAllergyPanel(boxes), scanLabSheet(boxes, today, monthFirst).day)
                    },
                )
                if (best.rows.isNotEmpty()) return@withEngine best
            }
            AllergyPanelScan()
        }
    }

    private const val SIDE = 2048
    private const val MAX_PAGES = 20
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val PDF_PASSWORD_EXTENSION = 13
}
