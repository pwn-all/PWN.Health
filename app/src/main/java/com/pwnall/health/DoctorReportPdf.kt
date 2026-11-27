package com.pwnall.health

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import androidx.core.content.FileProvider
import androidx.core.graphics.withClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.text.BreakIterator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** What the document is to the picker that creates it and the app that receives it. */
internal const val REPORT_MIME = "application/pdf"

/** The cache folder the shared copy lives in — the `reports` entry in `photo_paths.xml`. */
private const val REPORT_CACHE_DIR = "reports"

/** What [exportSpooled] and the import staging name their temporary files, so a sweep can find them. */
private const val BACKUP_SPOOL_PREFIX = "backup"

/** The right-to-left mark: zero width, and the strongest right-to-left character there is. */
private const val RLM = "\u200F"

/** A4 in PostScript points, the unit [PdfDocument] lays a page out in. */

internal const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
internal const val MARGIN = 40f
internal const val RIGHT_EDGE = PAGE_WIDTH - MARGIN

/** What a paragraph, a banner or a footnote may run across: the page between its margins. */
private const val FULL_WIDTH = RIGHT_EDGE - MARGIN

/** The three columns of a row: the label, the figure, and the note that qualifies the figure. */
private const val LABEL_X = MARGIN
private const val VALUE_X = 205f
private const val NOTE_X = 365f
private const val GUTTER = 8f
private const val DETAIL_INDENT = 12f

private const val TITLE_SIZE = 20f
private const val SUBTITLE_SIZE = 11f
private const val HEADING_SIZE = 13f
private const val BODY_SIZE = 10f
private const val NOTE_SIZE = 9f
private const val FOOTER_SIZE = 8.5f

/** One line of body text; a row is as many of these as its widest column needs. */
private const val LINE = 14f
private const val ROW_GAP = 3f
private const val SECTION_GAP = 16f
private const val HEADING_HEIGHT = 20f

/** The room between a heading's rule and the first line under it. */
private const val HEADING_RULE_GAP = 6f

/**
 * A chart's height, its title and its dates included: a full-width one, and one of a pair. Tall
 * enough that a year of figures reads as a shape, short enough that a section's first chart and its
 * heading fit under most of a page's other sections.
 */
private const val FULL_CHART_HEIGHT = 150f
private const val HALF_CHART_HEIGHT = 130f

/** The room between two charts side by side, and under each row of charts. */
private const val CHART_GUTTER = 16f
private const val CHART_GAP = 12f
private const val HALF_CHART_WIDTH = (FULL_WIDTH - CHART_GUTTER) / 2f

/** Where the text stops and where the running foot is printed, inside the bottom margin. */
internal const val CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN - 4f
private const val FOOTER_Y = PAGE_HEIGHT - MARGIN + 14f

/**
 * The pain drawing's plates: tall enough that a dab on a wrist is a mark rather than a speck, and two
 * of them side by side with the charts above them on one page. Their width keeps the plates' own
 * proportions; the label under each takes one line.
 */
internal const val PAIN_PLATE_HEIGHT = 220f
internal const val PAIN_PLATE_WIDTH = PAIN_PLATE_HEIGHT * PLATE_ASPECT
internal const val PAIN_PLATE_GUTTER = 24f
private const val PAIN_PLATE_LABEL = LINE

/** What fits under a heading on a page of its own: more than that is not kept together. */
private const val SECTION_ROOM = CONTENT_BOTTOM - MARGIN - SECTION_GAP - HEADING_HEIGHT - HEADING_RULE_GAP

// Ink on paper. The app's light palette by value, the way the medal card is drawn: nothing here runs
// inside a composition, and a sheet handed across a desk has no dark theme.
private val INK = 0xFF0B1B2E.toInt()
private val MUTED = 0xFF69758A.toInt()
private val RULE = 0xFFE7EBF0.toInt()

// A chart's series: deep blue, orange and slate, which stay apart in colour and, on a greyscale
// copier, by how dark they print — and by the dash of their line and the shape of their dot, which
// a photocopy cannot take away.
private val SERIES_INKS = listOf(0xFF1F4E9E.toInt(), 0xFFC8661A.toInt(), 0xFF5C6B7A.toInt())
private val SERIES_DASHES = listOf(null, floatArrayOf(4f, 2f), floatArrayOf(1.2f, 1.8f))

/**
 * The pain drawing's ink: the first series' deep blue, which prints dark on a greyscale copy, laid
 * down at the summary's [PAIN_MARK_ALPHA] once per entry so a place painted again and again prints
 * darker.
 */
private val PAIN_SHEET_INK = SERIES_INKS.first()

/** The reference band: a pale green-grey that a figure printed over it still reads through. */
private val BAND_FILL = 0xFFE3EBE5.toInt()
private val BAND_EDGE = 0xFF8FA597.toInt()

/** `pwn-health-report-2026-03-05-2026-09-05.pdf`: the period is the file's identity. */
internal fun doctorReportFileName(from: LocalDate, to: LocalDate): String = "pwn-health-report-$from-$to.pdf"

/**
 * The day format the preview and the PDF share, so the two never spell one date two ways.
 *
 * The pattern comes from the country profile the report was laid out for, as that profile writes a
 * date in [locale]'s language ([datePatternIn]), and defaults to the international one. "11/09" is
 * two different days on two sides of an ocean; a sheet that may cross one writes its month in
 * letters unless a national profile says that country writes it otherwise.
 */
internal fun reportDayFormat(
    locale: Locale,
    pattern: String = InternationalProfile.datePatternIn(locale),
): DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, locale)

/**
 * The text in the reader's language: the one place a [ReportText] meets Android's resources.
 *
 * Arguments that are themselves texts are resolved first, so a template can hold a plural or a day.
 */
@Suppress("SpreadOperator") // getString takes Java varargs; there is no other way to call it.
internal fun ReportText.resolve(
    resources: Resources,
    datePattern: String = InternationalProfile.datePatternIn(resources.configuration.locales[0]),
): String = when (this) {
    is ReportText.Plain -> text

    // A resource with no arguments is read, not formatted. Handing it to the formatting overload
    // runs it through String.format even with nothing to substitute, and a string that is a bare
    // "%" — which is what a percent unit is — throws there and takes the whole document down with
    // it.
    is ReportText.Res -> if (args.isEmpty()) {
        resources.getString(id)
    } else {
        resources.getString(
            id,
            *args.map { if (it is ReportText) it.resolve(resources, datePattern) else it }.toTypedArray(),
        )
    }

    is ReportText.Quantity -> resources.getQuantityString(id, count, count)

    is ReportText.Day -> day.format(reportDayFormat(resources.configuration.locales[0], datePattern))

    is ReportText.Join -> parts.map { it.resolve(resources, datePattern) }
        .filter(String::isNotEmpty)
        .joinToString(if (separator == LIST_COMMA) resources.getString(R.string.list_comma) else separator)
}

/**
 * The report as a PDF, written to [out] and the stream left open for whoever opened it.
 *
 * Laying out the pages measures every line of text and is work for a background thread; the bytes
 * go out on the IO dispatcher, because [out] may be a document provider's stream and the provider
 * may be a cloud folder.
 */
suspend fun writeDoctorReport(context: Context, report: DoctorReport, out: OutputStream) {
    val document = withContext(Dispatchers.Default) { renderDoctorReport(context, report) }
    try {
        withContext(Dispatchers.IO) { document.writeTo(out) }
    } finally {
        document.close()
    }
}

/**
 * The report written into the document the picker created. A failure removes that document rather
 * than leaving an empty PDF where the person asked for a report — the courtesy [exportSpooled]
 * extends to a backup.
 */
internal suspend fun saveDoctorReport(context: Context, report: DoctorReport, uri: Uri): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            // A provider that answers with no stream is a document that cannot be written, and says so
            // in words: a bare NullPointerException would put up the error card with nothing after its colon.
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw FileNotFoundException(context.getString(R.string.file_import_unopenable))
            out.use { writeDoctorReport(context, report, it) }
        }.onFailure {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            if (it is CancellationException) throw it
        }
    }

/**
 * The report handed to whatever the person picks out of the chooser, through the FileProvider the
 * photographs already travel by.
 *
 * The file lives in the cache under its own folder, and whatever stood there is deleted as the next
 * PDF or the next archive ([shareDoctorArchive]) is made, because a sheet that has already left the
 * phone has no business staying on it. The last one goes once it is an hour old, the next time the
 * app comes forward ([sweepStaleShares]), and at the latest at the next fresh start, when
 * [sweepExportLeftovers] empties the folder. Nothing is uploaded — the app holds no INTERNET permission —
 * and where the file goes next is between the person and the app they chose.
 */
internal suspend fun shareDoctorReport(context: Context, report: DoctorReport): Result<Unit> = runCatching {
    val file = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, REPORT_CACHE_DIR).apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        File(folder, doctorReportFileName(report.from, report.to)).also { target ->
            target.outputStream().use { writeDoctorReport(context, report, it) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    val title = context.getString(R.string.report_share_title)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = REPORT_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        // The clip carries the URI a second time: it is what the chooser reads to preview the file.
        clipData = ClipData.newUri(context.contentResolver, title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}.onFailure { if (it is CancellationException) throw it }

/**
 * Everything an export or an import left in the cache, removed at the next start.
 *
 * Three kinds of leftover. A shared report lives in `cache/reports`, and a shared walk or workout
 * template in a folder of its own under `cache/shared/routes` or `cache/shared/routine-share`,
 * because a content URI has to point at a real file, and the share hands the receiving app a read
 * grant that outlives this process — so the file cannot be deleted at the moment it is sent, and is
 * deleted at the next start instead. A `backup…` file in the cache root is a backup copied in to be imported — and kept
 * while the import waits for its password — that Android closed the app on: the entire record, in
 * plain text unless it was sealed. An export's spool takes the same prefix, but
 * [exportSpooled] removes its name before a byte is written under it, so an interrupted export leaves
 * an empty file at most. And a file an import or a lab reading copied in to read, which its own
 * cleanup deletes unless Android closed the app first.
 *
 * Errors are swallowed on purpose: a cache the system has already cleared, or a file another
 * process still holds, is not a reason to fail a launch.
 */
internal fun sweepExportLeftovers(context: Context) {
    runCatching { File(context.cacheDir, REPORT_CACHE_DIR).listFiles()?.forEach { it.delete() } }
    runCatching { File(context.cacheDir, ROUTE_SHARE_DIR).deleteRecursively() }
    runCatching { File(context.cacheDir, ROUTINE_SHARE_DIR).deleteRecursively() }
    runCatching {
        context.cacheDir.listFiles()
            ?.filter {
                // Only an hour-old one, as below: the staged copy of an import waiting for its
                // password is still wanted, and so is the spool of an export that outlived the
                // screen that began it — the nightly worker, one left with Back — until it is open.
                it.isFile && it.name.startsWith(BACKUP_SPOOL_PREFIX) &&
                    it.lastModified() < System.currentTimeMillis() - IN_FLIGHT_STALE_MS
            }
            ?.forEach { it.delete() }
    }
    // An exported history's copy, which the import deletes when it ends. Not while one is running:
    // the import lives with the process, which outlives the screen that started it.
    runCatching {
        if (!fileImportRunning.value) {
            context.cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(IMPORT_COPY_PREFIX) }
                ?.forEach { it.delete() }
        }
    }
    // A restore's photographs parked under `.part` by a process that died before they took their
    // names: [sweepRestoreParts].
    runCatching { sweepRestoreParts(context) }
    // The shots with one fixed name — a progress photograph, a meal, the cut of a packet's label —
    // that the screen which asked for them never came back to read: [sweepLostCaptures].
    runCatching { sweepLostCaptures(context.cacheDir, System.currentTimeMillis()) }
    // A walk photograph whose shot was lost with the app's saved state — swiped away while the camera
    // was open — leaves the camera's full-size file behind, and a lab PDF being read when the app was
    // closed leaves its copy. Only an hour-old one goes: a shot or a reading still in flight in
    // another window of the app is younger than that.
    runCatching {
        val stale = System.currentTimeMillis() - IN_FLIGHT_STALE_MS
        val shared = File(context.cacheDir, SHARED_CACHE_DIR)
        val shots = shared.listFiles()?.filter { it.name.startsWith(WALK_CAPTURE_PREFIX) }
        val copies = context.cacheDir.listFiles()?.filter { it.name.startsWith(LAB_PDF_COPY_PREFIX) }
        (shots.orEmpty() + copies.orEmpty())
            .filter { it.isFile && it.lastModified() < stale }
            .forEach { it.delete() }
    }
}

/** How old a camera file or a PDF copy in the cache must be before the start-up sweep takes it for lost. */
internal const val IN_FLIGHT_STALE_MS = 60 * 60 * 1000L

/**
 * The folder of the cache the FileProvider serves under its `shared` name (res/xml/photo_paths.xml):
 * every file a camera app is asked to write, and every file handed to another app but the doctor's
 * report — a medal card, a shared walk or template. Everything else in the cache — the backup spool,
 * an import's staged copy, a restore's parts — lies outside it, where no URI the provider makes can
 * name it. Made on the way, because a camera app cannot create its file in a folder that is not
 * there.
 */
internal fun sharedCacheDir(context: Context): File = File(context.cacheDir, SHARED_CACHE_DIR).apply { mkdirs() }

internal const val SHARED_CACHE_DIR = "shared"

/**
 * Every page laid out and drawn. The layout runs first over the whole report, so that the page
 * count is known when the feet are printed — a canvas is closed with its page, and "1 of 3" cannot
 * be written on page one until page three exists.
 *
 * The layout itself touches no Paint and no Resources: it is handed a way to measure a line and a
 * way to put a [ReportText] into words, so a JVM test can lay out a whole sheet and read where
 * every line landed.
 */
private fun renderDoctorReport(context: Context, report: DoctorReport): PdfDocument {
    val resources = sheetResources(context)
    val locale = resources.configuration.locales[0]
    val datePattern = report.request.country.datePatternIn(locale)
    // Handed over unresolved: the layout puts it into words with the country's date pattern, like
    // every other day on the sheet. Resolved here it would take the international pattern, and a sheet
    // whose rows read 23.09.2026 would be signed "23 Sep 2026" in its foot.
    val generated = ReportText.Res(
        R.string.report_generated,
        listOf(resources.getString(R.string.app_name), appVersion(context).name, ReportText.Day(LocalDate.now())),
    )
    val paints = SheetType.entries.associateWith { paint(it.size, it.color, it.bold) }
    val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val layout = SheetLayout(
        text = { it.resolve(resources, datePattern) },
        measure = { line, type -> paints.getValue(type).measureText(line) },
        locale = locale,
        rtl = rtl,
        datePattern = datePattern,
    ).apply { compose(report, generated) }
    val rule = Paint().apply {
        color = RULE
        strokeWidth = 0.8f
    }
    val charts = ChartInks(paints, rtl)
    val plates = PainPlateInks(resources)
    val document = PdfDocument()
    // A page that fails to draw closes the document here, since the caller only closes one it is
    // handed: a PdfDocument keeps its pages in native memory until then. close() refuses a document
    // whose page is still open, so each page is finished whether or not it drew.
    runCatching {
        layout.pages.forEachIndexed { index, ops ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
            runCatching {
                ops.forEach { op ->
                    when (op) {
                        is TextOp -> {
                            val paint = paints.getValue(op.type)
                            paint.textAlign = if (op.rightAligned) Paint.Align.RIGHT else Paint.Align.LEFT
                            // drawText takes each line's direction from its first strong character, so an
                            // Arabic line that opens on a Latin name would run left to right. A right-to-left
                            // mark in front settles it without touching the layout the tests read.
                            page.canvas.drawText(if (rtl) RLM + op.text else op.text, op.x, op.y, paint)
                        }

                        is RuleOp -> page.canvas.drawLine(MARGIN, op.y, RIGHT_EDGE, op.y, rule)

                        is ChartOp -> charts.draw(page.canvas, op)

                        is PainDrawingOp -> plates.draw(page.canvas, op)
                    }
                }
            }.also { document.finishPage(page) }.getOrThrow()
        }
    }.onFailure {
        document.close()
    }.getOrThrow()
    return document
}

/**
 * The app's resources in the phone's language, with every number they format set in Latin digits.
 *
 * Otherwise one sheet would carry two numbering systems. In a language with digits of its own,
 * Arabic among them, a count that goes through a string resource — the page number, the days of
 * data — would take those digits, while a lab value, a BMI or a date, formatted in [Locale.US] or
 * with java.time's standard digits, stays Latin. The figures a doctor compares have to be read in
 * one system; Latin is the one the rest of the sheet already prints, and the words stay the reader's.
 */
// The language is the phone's own and only its numbering system changes, so the language split
// Play already installed for this phone is the one read: no other language is ever asked for.
@SuppressLint("AppBundleLocaleChanges")
internal fun sheetResources(context: Context): Resources {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(latinDigits(configuration.locales[0]))
    return context.createConfigurationContext(configuration).resources
}

/**
 * [locale] with its numbering system pinned to Latin digits and nothing else changed — the words,
 * the plural rules and the direction are still the language's own.
 *
 * A locale Locale.Builder refuses as ill-formed is returned as it was: a sheet in two numbering
 * systems is a lesser fault than no sheet at all.
 */
internal fun latinDigits(locale: Locale): Locale = runCatching {
    Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build()
}.getOrDefault(locale)

/**
 * The type a line is set in: a size, an ink and a weight. The layout records these rather than a
 * Paint, so it can run where there is no android.graphics.
 */
internal enum class SheetType(val size: Float, val color: Int, val bold: Boolean = false) {
    TITLE(TITLE_SIZE, INK, bold = true),
    SUBTITLE(SUBTITLE_SIZE, MUTED),
    HEADING(HEADING_SIZE, INK, bold = true),
    LABEL(BODY_SIZE, INK, bold = true),
    DETAIL_LABEL(BODY_SIZE, MUTED),
    BODY(BODY_SIZE, INK),
    NOTE(NOTE_SIZE, MUTED),
    FOOTER(FOOTER_SIZE, MUTED),
}

/** One thing to draw on a page, recorded during layout and drawn once the page exists. */
internal sealed interface SheetOp

/**
 * A line of text at its baseline. [x] is where the line is anchored on the page: its left end, or
 * its right end where [rightAligned] — which is every line of a right-to-left sheet.
 */
internal data class TextOp(
    val text: String,
    val x: Float,
    val y: Float,
    val type: SheetType,
    val rightAligned: Boolean = false,
) : SheetOp

/** A hairline across the text width. */
internal data class RuleOp(val y: Float) : SheetOp

/**
 * A chart, laid out: the [box] it takes on the page, the [plot] inside it, and every line, dot, bar
 * and label already placed — see [ChartDrafter]. [grid] and [bandEdges] are heights on the page,
 * [ticks] and [marks] are distances across it; [band] is the shaded reference range.
 */
internal data class ChartOp(
    val box: ChartBox,
    val plot: ChartBox,
    val grid: List<Float>,
    val band: ChartBox?,
    val bandEdges: List<Float>,
    val ticks: List<Float>,
    val marks: List<Float>,
    val traces: List<ChartTrace>,
    val swatches: List<ChartSwatch>,
    val texts: List<ChartText>,
) : SheetOp

/**
 * One series as drawn: the stretches of its line, its dots and its bars. [ink] is its place in the
 * chart, which picks its colour, its dash and the shape of its dot.
 */
internal data class ChartTrace(
    val ink: Int,
    val style: ChartStyle,
    val runs: List<List<ChartDot>> = emptyList(),
    val dots: List<ChartDot> = emptyList(),
    val bars: List<ChartBox> = emptyList(),
)

/** A legend's sample of a series, drawn the way the series is. */
internal data class ChartSwatch(val box: ChartBox, val ink: Int, val style: ChartStyle)

/** A label inside a chart, by its left end and its baseline: the drafter has measured it already. */
internal data class ChartText(val text: String, val x: Float, val y: Float, val type: SheetType)

/**
 * The pain drawing, laid out: each plate placed on the page with every mark on it. The renderer
 * draws the plates for [sex] — the layout holds no picture, only where it goes.
 */
internal data class PainDrawingOp(val sex: String, val plates: List<PainPlateOp>) : SheetOp

/** One plate in its [box], and each entry's marks on it, one list per entry, already on the page. */
internal data class PainPlateOp(val view: PainView, val box: ChartBox, val entries: List<List<PainMark>>)

/** One stroke on the page: its path, and its brush's radius, in points. A single point is a dab. */
internal data class PainMark(val points: List<ChartDot>, val radius: Float)

/**
 * The strokes of [entries] painted on [view], put into [box]: x across the plate's width and y down
 * its height, the brush's radius in plate heights scaled with it. An entry with nothing on this view
 * is left out, so each list is one layer of ink.
 */
internal fun painPlate(view: PainView, box: ChartBox, entries: List<List<PainStroke>>): PainPlateOp {
    val height = box.bottom - box.top
    return PainPlateOp(
        view,
        box,
        entries.map { strokes ->
            strokes.filter { it.view == view }.map { stroke ->
                PainMark(
                    stroke.points.map { ChartDot(box.left + it.x * box.width, box.top + it.y * height) },
                    stroke.radius * height,
                )
            }
        }.filter { it.isNotEmpty() },
    )
}

/**
 * The lines under a section's rows, in the order they are printed: how much of the period the
 * figures cover, who wrote them, and the caveat.
 *
 * The caveat travels whether or not the section has rows. An empty allergy section is where it
 * matters most: "None known" printed without the line saying that the answer covers the food-label
 * allergens and not medicines reads to a prescriber as no known allergies at all, and "never
 * tested" is told apart from "never asked" by nothing but its caveat.
 */
internal fun sectionNotes(section: ReportSection): List<ReportText> = listOfNotNull(
    section.completeness?.let(::completenessText),
    section.provenance.takeIf { it.isNotEmpty() }?.let { sources ->
        ReportText.Res(
            R.string.report_provenance,
            listOf(ReportText.Join(sources.map { ReportText.Res(it.category.labelRes) }, separator = LIST_COMMA)),
        )
    },
    section.caveatRes?.let { ReportText.Res(it) },
)

/** "Data for 18 of 30 days · 42 measurements", or the open-ended form where no day is owed. */
private fun completenessText(of: Completeness): ReportText = of.expectedDays?.let {
    ReportText.Res(R.string.report_completeness, listOf(of.daysWithData, it, of.measurements))
} ?: ReportText.Res(R.string.report_completeness_open, listOf(of.measurements, of.daysWithData))

/** A row set into its three columns, measured before it is placed. */
private class MeasuredRow(
    val labels: List<String>,
    val labelX: Float,
    val labelType: SheetType,
    val values: List<String>,
    val notes: List<String>,
) {
    /** The lines the tallest of the three columns runs to. */
    val lines: Int get() = maxOf(labels.size, values.size, notes.size).coerceAtLeast(1)
    val height: Float get() = lines * LINE + ROW_GAP
}

/** How much room a footnote of these lines takes, gap under it included. */
private fun footnoteHeight(lines: List<String>): Float = lines.size * (LINE - 2f) + ROW_GAP

/** How much room a paragraph of these lines takes, gap under it included. */
private fun paragraphHeight(lines: List<String>): Float = lines.size * LINE + ROW_GAP * 2

/**
 * The report flowed onto A4 pages: a title, then each section as a heading over rows of three
 * columns, then the notes as paragraphs, then the appendix, then the running foot on every page.
 *
 * Three things are kept together. A row that will not fit on the page it is on moves whole to the
 * next, and so does a row of charts or a pain drawing. A heading takes its first chart or its first
 * row along — measured, however many lines it wraps to — so no page ends on a heading; the pain
 * section's heading takes its charts and its drawing together, where they fit on one page. And a section's last row
 * takes the section's own notes along, because the caveat has to be on the same page as the figures
 * it qualifies.
 *
 * A right-to-left language gets the page mirrored: the label column on the right, every line
 * anchored at its right end, and the foot's two halves swapped. The text within a line is shaped
 * by the canvas, which already knows its direction.
 */
internal class SheetLayout(
    private val text: (ReportText) -> String,
    private val measure: (String, SheetType) -> Float,
    private val locale: Locale,
    private val rtl: Boolean,
    datePattern: String = InternationalProfile.datePatternIn(locale),
) {
    val pages = mutableListOf<MutableList<SheetOp>>(mutableListOf())
    private val drafter = ChartDrafter(text, measure, locale, datePattern, rtl)
    private var y = MARGIN
    private val ops: MutableList<SheetOp> get() = pages.last()

    fun compose(report: DoctorReport, generatedLine: ReportText) {
        val generated = text(generatedLine)
        put(string(R.string.report_title), MARGIN, y + TITLE_SIZE, SheetType.TITLE)
        y += TITLE_SIZE + 8f
        val days = listOf(ReportText.Day(report.from), ReportText.Day(report.to))
        val period = text(ReportText.Res(R.string.report_period_value, days))
        put(period, MARGIN, y + SUBTITLE_SIZE, SheetType.SUBTITLE)
        y += SUBTITLE_SIZE + 6f
        // The first thing on the page, above the person's own figures: what this document is, who
        // made it, and that nothing in it has been checked by a clinician. A reader who takes a
        // patient's own measurements for a clinical record is the failure this line exists against.
        banner(string(R.string.report_patient_generated))
        report.sections.forEach(::section)
        val notes = (report.notes.map(text) + generated).map { wrap(it, SheetType.BODY, FULL_WIDTH) }
        heading(string(R.string.report_section_notes), following = notes.first().take(2).size * LINE)
        notes.forEach(::paragraph)
        appendix(report)
        feet(generated)
    }

    /**
     * One section: its heading, its charts, the pain section's drawing, what is in it — or which kind
     * of nothing — and then the lines that qualify it, kept on the page of the last thing they
     * qualify.
     *
     * The order matters. A doctor reads a trend off a chart first, the figures second and the caveat
     * third, but the caveat has to be on the same page as the figures it qualifies, which is what
     * keeping it inside the section rather than in a footnote block achieves. An empty section keeps
     * its caveat too: see [sectionNotes]. It draws no chart: every figure a chart shows is one of the
     * section's rows, and a line drawn over "no data recorded" would contradict it.
     */
    private fun section(section: ReportSection) {
        val title = string(section.part.titleRes)
        val notes = sectionNotes(section).map { wrap(text(it), SheetType.NOTE, FULL_WIDTH) }
        val tail = notes.fold(0f) { sum, lines -> sum + footnoteHeight(lines) }
        if (section.rows.isEmpty()) {
            val empty = wrap(string(section.emptyLabelRes()), SheetType.BODY, FULL_WIDTH)
            heading(title, following = paragraphHeight(empty) + tail)
            paragraph(empty)
        } else {
            val charts = chartRows(section.charts.filter { it.drawable() })
            val rows = section.rows.map(::measured)
            val drawing = section.painDrawing
            val caption = drawing?.let { painCaption(it) }
            val first = leadHeight(charts, caption?.let(::painDrawingHeight))
            heading(title, following = first ?: (rows.first().height + if (rows.size == 1) tail else 0f))
            charts.forEach(::chartRow)
            if (drawing != null && caption != null) painDrawing(drawing, caption)
            rows.forEachIndexed { index, row -> place(row, keep = if (index == rows.lastIndex) tail else 0f) }
        }
        notes.forEach(::footnote)
    }

    /**
     * One row of charts, moved whole to the next page where it does not fit: half a chart on each
     * page is two pictures nobody can read a trend off. A pair of half-width charts sits side by
     * side, the first at the reading start; a small gap under the row sets it off from what follows.
     */
    private fun chartRow(row: List<ReportChart>) {
        val height = chartRowHeight(row)
        val width = if (row.first().width == ChartWidth.FULL) FULL_WIDTH else HALF_CHART_WIDTH
        ensure(height)
        row.forEachIndexed { index, chart ->
            val start = MARGIN + index * (width + CHART_GUTTER)
            val left = if (rtl) PAGE_WIDTH - start - width else start
            drafter.draft(chart, ChartBox(left, y, left + width, y + height))?.let { ops += it }
        }
        y += height + CHART_GAP
    }

    private fun chartRowHeight(row: List<ReportChart>): Float =
        if (row.first().width == ChartWidth.FULL) FULL_CHART_HEIGHT else HALF_CHART_HEIGHT

    /**
     * What a section's heading takes along: its first row of charts — or, with a drawing under them,
     * the charts and the drawing together where they fit on one page, so the picture of where it hurt
     * is not turned over from the charts of how often. Null with neither.
     */
    private fun leadHeight(charts: List<List<ReportChart>>, drawing: Float?): Float? {
        val first = charts.firstOrNull()?.let { chartRowHeight(it) + CHART_GAP }
        val together = drawing?.let { charts.fold(it) { sum, row -> sum + chartRowHeight(row) + CHART_GAP } }
        return together?.takeIf { it <= SECTION_ROOM } ?: first ?: drawing
    }

    /** The drawing's caption: how many entries are drawn, and that each mark is where it was painted. */
    private fun painCaption(drawing: PainSheetDrawing): List<String> = wrap(
        text(ReportText.Quantity(R.plurals.report_pain_drawing_caption, drawing.entries.size)),
        SheetType.NOTE,
        FULL_WIDTH,
    )

    private fun painDrawingHeight(caption: List<String>): Float =
        PAIN_PLATE_HEIGHT + PAIN_PLATE_LABEL + footnoteHeight(caption) + CHART_GAP

    /**
     * The front and the back side by side, the front at the reading start, each named under it, and
     * the caption under both — moved whole to the next page where it does not fit.
     */
    private fun painDrawing(drawing: PainSheetDrawing, caption: List<String>) {
        ensure(painDrawingHeight(caption))
        val plates = PainView.entries.mapIndexed { index, view ->
            val start = MARGIN + index * (PAIN_PLATE_WIDTH + PAIN_PLATE_GUTTER)
            val left = if (rtl) PAGE_WIDTH - start - PAIN_PLATE_WIDTH else start
            val label = if (view == PainView.FRONT) R.string.pain_view_front else R.string.pain_view_back
            put(string(label), start, y + PAIN_PLATE_HEIGHT + PAIN_PLATE_LABEL - 2f, SheetType.NOTE)
            painPlate(view, ChartBox(left, y, left + PAIN_PLATE_WIDTH, y + PAIN_PLATE_HEIGHT), drawing.entries)
        }
        ops += PainDrawingOp(drawing.sex, plates)
        y += PAIN_PLATE_HEIGHT + PAIN_PLATE_LABEL + ROW_GAP
        footnote(caption)
        y += CHART_GAP - ROW_GAP
    }

    /**
     * The dated rows kept off the sheet's body: the weigh-ins, every act where one was asked for.
     *
     * Last, and under its own heading, because a doctor asked for a summary and a reader who wants
     * the series should have to turn to it rather than read around it. Each series' name keeps its
     * first row, the way a heading does.
     */
    private fun appendix(report: DoctorReport) {
        val series = report.sections.filter { it.appendix.isNotEmpty() }.map { section ->
            wrap(string(section.part.titleRes), SheetType.NOTE, FULL_WIDTH) to section.appendix.map(::measured)
        }
        if (series.isEmpty()) return
        val (firstName, firstRows) = series.first()
        val first = footnoteHeight(firstName) + firstRows.first().height
        heading(string(R.string.report_appendix_title), following = first)
        series.forEach { (name, rows) ->
            ensure(footnoteHeight(name) + rows.first().height)
            footnote(name)
            rows.forEach { place(it) }
        }
    }

    /**
     * The running foot: who made the sheet and when, and which page of how many. Mirrored with the
     * rest of a right-to-left sheet, so the page number stays at the end a reader finishes on.
     */
    private fun feet(generated: String) {
        pages.forEachIndexed { index, page ->
            val number = text(ReportText.Res(R.string.report_page, listOf(index + 1, pages.size)))
            page += anchored(generated, MARGIN, FOOTER_Y, SheetType.FOOTER, atEnd = false)
            page += anchored(number, RIGHT_EDGE, FOOTER_Y, SheetType.FOOTER, atEnd = true)
        }
    }

    /** A line of its own in the quiet type, under the rows it belongs to. */
    private fun footnote(lines: List<String>) {
        lines.forEach { line ->
            ensure(LINE)
            put(line, MARGIN, y + NOTE_SIZE, SheetType.NOTE)
            y += LINE - 2f
        }
        y += ROW_GAP
    }

    /** The standing statement under the title: set apart, ruled off, impossible to skim past. */
    private fun banner(text: String) {
        y += 4f
        wrap(text, SheetType.BODY, FULL_WIDTH).forEach { line ->
            ensure(LINE)
            put(line, MARGIN, y + BODY_SIZE, SheetType.BODY)
            y += LINE
        }
        y += 4f
        ops += RuleOp(y)
        y += 4f
    }

    /** A heading, moved to the next page with [following] — the height of what must not leave it. */
    private fun heading(text: String, following: Float) {
        ensure(SECTION_GAP + HEADING_HEIGHT + HEADING_RULE_GAP + following)
        if (y > MARGIN) y += SECTION_GAP
        put(text, MARGIN, y + HEADING_SIZE, SheetType.HEADING)
        y += HEADING_HEIGHT
        ops += RuleOp(y)
        y += HEADING_RULE_GAP
    }

    private fun measured(row: ReportRow): MeasuredRow {
        val labelType = if (row.detail) SheetType.DETAIL_LABEL else SheetType.LABEL
        val labelX = LABEL_X + if (row.detail) DETAIL_INDENT else 0f
        return MeasuredRow(
            labels = wrap(text(row.label), labelType, VALUE_X - labelX - GUTTER),
            labelX = labelX,
            labelType = labelType,
            values = wrap(text(row.value), SheetType.BODY, NOTE_X - VALUE_X - GUTTER),
            notes = row.note?.let { wrap(text(it), SheetType.NOTE, RIGHT_EDGE - NOTE_X) }.orEmpty(),
        )
    }

    /**
     * A row, moved whole to the next page with [keep] — the height of what must stay under it.
     *
     * A row taller than a page cannot be moved whole, and is set a line at a time instead ([flow]).
     * Moved to a fresh page, it would run on past the foot of it: an attachment described in a few
     * thousand characters would be printed over the running foot and then off the paper, with no mark
     * on the sheet that anything was missing.
     */
    private fun place(row: MeasuredRow, keep: Float = 0f) {
        if (row.height > CONTENT_BOTTOM - MARGIN) {
            flow(row)
        } else {
            ensure(row.height + keep)
            column(row.labels, row.labelX, row.labelType)
            column(row.values, VALUE_X, SheetType.BODY)
            column(row.notes, NOTE_X, SheetType.NOTE)
            y += row.height
        }
    }

    /** [row] a line at a time across as many pages as it takes, each column's line beside the others'. */
    private fun flow(row: MeasuredRow) {
        repeat(row.lines) { line ->
            ensure(LINE)
            row.labels.getOrNull(line)?.let { put(it, row.labelX, y + BODY_SIZE, row.labelType) }
            row.values.getOrNull(line)?.let { put(it, VALUE_X, y + BODY_SIZE, SheetType.BODY) }
            row.notes.getOrNull(line)?.let { put(it, NOTE_X, y + BODY_SIZE, SheetType.NOTE) }
            y += LINE
        }
        y += ROW_GAP
    }

    private fun column(lines: List<String>, x: Float, type: SheetType) {
        lines.forEachIndexed { index, line -> put(line, x, y + BODY_SIZE + index * LINE, type) }
    }

    /** Line by line rather than whole: a paragraph may cross a page where a row may not. */
    private fun paragraph(lines: List<String>) {
        lines.forEach { line ->
            ensure(LINE)
            put(line, MARGIN, y + BODY_SIZE, SheetType.BODY)
            y += LINE
        }
        y += ROW_GAP * 2
    }

    private fun ensure(height: Float) {
        if (y + height > CONTENT_BOTTOM) {
            pages += mutableListOf<SheetOp>()
            y = MARGIN
        }
    }

    private fun string(id: Int): String = text(ReportText.Res(id))

    private fun wrap(line: String, type: SheetType, width: Float): List<String> =
        wrapText(line, width, locale) { measure(it, type) }

    /** A line that starts at [x] in reading order: at its left end, or at its right on a mirrored page. */
    private fun put(line: String, x: Float, baseline: Float, type: SheetType) {
        ops += anchored(line, x, baseline, type, atEnd = false)
    }

    /**
     * A line anchored at [x] as a left-to-right page measures it — by its start, or by its end where
     * [atEnd] — and mirrored across the page where the language reads the other way.
     */
    private fun anchored(line: String, x: Float, baseline: Float, type: SheetType, atEnd: Boolean): TextOp =
        TextOp(line, if (rtl) PAGE_WIDTH - x else x, baseline, type, rightAligned = rtl != atEnd)
}

private fun paint(size: Float, color: Int, bold: Boolean = false): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    this.color = color
    typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
}

private fun stroke(ink: Int, width: Float, dash: FloatArray? = null): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = ink
    style = Paint.Style.STROKE
    strokeWidth = width
    strokeJoin = Paint.Join.ROUND
    pathEffect = dash?.let { DashPathEffect(it, 0f) }
}

private fun fill(ink: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = ink
    style = Paint.Style.FILL
}

/** Paint's alpha runs to 255. */
private const val OPAQUE = 255

/**
 * The pain drawing's plates and ink, made once per document. Each plate is drawn drained grey at the
 * strength the app draws its idle body, then every entry's marks as a layer of their own — opaque
 * inside, so an entry's own strokes do not darken where they cross, and put down translucent, so
 * entries do where they overlap. Everything is clipped to the plate: a stroke painted on the body
 * that ran up past the neck, onto the head above it, is cut off there. A stroke painted
 * wholly on that head never reaches the sheet ([painShownStrokes]).
 */
private class PainPlateInks(private val resources: Resources) {
    private val bitmaps = HashMap<Int, Bitmap>()
    private val grey = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // Saturation nought: the grey the app's own idle plate is drained to.
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        alpha = (MUSCLE_IDLE_ALPHA * OPAQUE).roundToInt()
    }
    private val line = stroke(PAIN_SHEET_INK, 1f).apply { strokeCap = Paint.Cap.ROUND }
    private val dab = fill(PAIN_SHEET_INK)
    private val layer = (PAIN_MARK_ALPHA * OPAQUE).roundToInt()

    fun draw(canvas: Canvas, op: PainDrawingOp) {
        op.plates.forEach { plate ->
            val box = plate.box
            val res = bodyPlateRes(op.sex, plate.view == PainView.FRONT)
            val bitmap = bitmaps.getOrPut(res) { BitmapFactory.decodeResource(resources, res) }
            canvas.drawBitmap(bitmap, null, RectF(box.left, box.top, box.right, box.bottom), grey)
            canvas.withClip(box.left, box.top, box.right, box.bottom) {
                plate.entries.forEach { marks ->
                    val saved = canvas.saveLayerAlpha(box.left, box.top, box.right, box.bottom, layer)
                    marks.forEach { mark(canvas, it) }
                    canvas.restoreToCount(saved)
                }
            }
        }
    }

    private fun mark(canvas: Canvas, mark: PainMark) {
        val first = mark.points.first()
        if (mark.points.size == 1) {
            canvas.drawCircle(first.x, first.y, mark.radius, dab)
            return
        }
        val path = Path().apply {
            moveTo(first.x, first.y)
            mark.points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        line.strokeWidth = mark.radius * 2f
        canvas.drawPath(path, line)
    }
}

/** A dot's half-width, and the tick a date hangs from under the plot. */
private const val DOT_RADIUS = 1.8f
private const val TICK_LENGTH = 3f

/**
 * The pens a chart is drawn with, made once per document, and the drawing itself. Everything it
 * strokes was placed by [ChartDrafter]; nothing here measures or decides.
 */
private class ChartInks(private val type: Map<SheetType, Paint>, private val rtl: Boolean) {
    private val grid = stroke(RULE, 0.6f)
    private val axis = stroke(MUTED, 0.7f)
    private val band = fill(BAND_FILL)
    private val bandEdge = stroke(BAND_EDGE, 0.6f, floatArrayOf(3f, 2f))
    private val mark = stroke(MUTED, 0.6f, floatArrayOf(2f, 2f))
    private val paper = fill(0xFFFFFFFF.toInt())
    private val lines = SERIES_INKS.zip(SERIES_DASHES) { ink, dash -> stroke(ink, 1.3f, dash) }
    private val fills = SERIES_INKS.map(::fill)
    private val rings = SERIES_INKS.map { stroke(it, 0.9f) }

    fun draw(canvas: Canvas, op: ChartOp) {
        val plot = op.plot
        op.band?.let { canvas.drawRect(it.left, it.top, it.right, it.bottom, band) }
        op.grid.forEach { canvas.drawLine(plot.left, it, plot.right, it, grid) }
        op.bandEdges.forEach { canvas.drawLine(plot.left, it, plot.right, it, bandEdge) }
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, axis)
        op.ticks.forEach { canvas.drawLine(it, plot.bottom, it, plot.bottom + TICK_LENGTH, axis) }
        op.marks.forEach { canvas.drawLine(it, plot.top, it, plot.bottom, mark) }
        // Every figure is inside the plot: valueScale moves a fixed end out to a figure past it rather
        // than cut the point off. What the clip trims is a wide bar on the window's first or last day,
        // which would hang over the value labels; the bleed lets a dot on the frame itself, a mood of 5
        // on a scale that stops at 5, be drawn whole.
        val bleed = DOT_RADIUS
        canvas.withClip(plot.left - bleed, plot.top - bleed, plot.right + bleed, plot.bottom + bleed) {
            op.traces.forEach { trace(canvas, it) }
        }
        op.swatches.forEach { swatch(canvas, it) }
        op.texts.forEach { label ->
            val paint = type.getValue(label.type)
            paint.textAlign = Paint.Align.LEFT
            // Words take the mark a right-to-left line needs, as the sheet's own lines do. A bare
            // figure does not: in front of "-2" it would print the minus after the number.
            val words = rtl && label.text.any(Char::isLetter)
            canvas.drawText(if (words) RLM + label.text else label.text, label.x, label.y, paint)
        }
    }

    private fun trace(canvas: Canvas, trace: ChartTrace) {
        val ink = trace.ink % SERIES_INKS.size
        trace.bars.forEach { canvas.drawRect(it.left, it.top, it.right, it.bottom, fills[ink]) }
        trace.runs.forEach { run ->
            val path = Path().apply {
                moveTo(run.first().x, run.first().y)
                run.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, lines[ink])
        }
        trace.dots.forEach { dot(canvas, it.x, it.y, ink) }
    }

    /** A dot in its series' shape — a disc, a square or a ring — so a greyscale copy still tells them apart. */
    private fun dot(canvas: Canvas, x: Float, y: Float, ink: Int) {
        when (ink) {
            0 -> canvas.drawCircle(x, y, DOT_RADIUS, fills[ink])

            1 -> canvas.drawRect(x - DOT_RADIUS, y - DOT_RADIUS, x + DOT_RADIUS, y + DOT_RADIUS, fills[ink])

            else -> {
                canvas.drawCircle(x, y, DOT_RADIUS, paper)
                canvas.drawCircle(x, y, DOT_RADIUS, rings[ink])
            }
        }
    }

    private fun swatch(canvas: Canvas, swatch: ChartSwatch) {
        val box = swatch.box
        val ink = swatch.ink % SERIES_INKS.size
        val middle = (box.top + box.bottom) / 2f
        if (swatch.style == ChartStyle.BARS) {
            canvas.drawRect(box.left, box.top, box.right, box.bottom, fills[ink])
        }
        if (swatch.style == ChartStyle.LINE || swatch.style == ChartStyle.LINE_DOTS) {
            canvas.drawLine(box.left, middle, box.right, middle, lines[ink])
        }
        if (swatch.style == ChartStyle.DOTS || swatch.style == ChartStyle.LINE_DOTS) {
            dot(canvas, (box.left + box.right) / 2f, middle, ink)
        }
    }
}

/**
 * Greedy line wrap against a column's width, breaking only where [locale]'s own rules allow a
 * break.
 *
 * Those rules come from [BreakIterator] rather than from the spaces in the text. Japanese and
 * Chinese put no space between words, so a wrap that split on spaces would take a whole sentence
 * after "PWN.Health" as one word, push it to a line of its own, and then cut it by glyph wherever the
 * width ran out — a paragraph break in mid-sentence, and a line free to begin with "。" or "）". The
 * line rules break between ideographs and never before closing punctuation.
 *
 * A single piece wider than the column — a long medicine name, a compound unit — is broken by
 * character rather than allowed to run into the next column. A newline in the text ends its line.
 */
internal fun wrapText(text: String, width: Float, locale: Locale, measure: (String) -> Float): List<String> {
    val lines = mutableListOf<String>()
    var line = ""
    lineSegments(text, locale).forEach { segment ->
        val candidate = if (line.isEmpty()) segment.trimStart() else line + segment
        if (measure(candidate.trimEnd()) <= width) {
            line = candidate
        } else {
            if (line.isNotBlank()) lines += line.trimEnd()
            var rest = segment.trimStart()
            while (measure(rest.trimEnd()) > width) {
                val fit = fittingPrefix(rest, width, locale, measure)
                lines += rest.substring(0, fit)
                rest = rest.substring(fit)
            }
            line = rest
        }
        if (segment.endsWith('\n')) {
            if (line.isNotBlank()) lines += line.trimEnd()
            line = ""
        }
    }
    if (line.isNotBlank()) lines += line.trimEnd()
    return lines
}

/** [text] cut at every place [locale]'s line rules allow a break, each piece with its trailing space. */
private fun lineSegments(text: String, locale: Locale): List<String> {
    val breaks = BreakIterator.getLineInstance(locale).apply { setText(text) }
    val segments = mutableListOf<String>()
    var start = breaks.first()
    var end = breaks.next()
    while (end != BreakIterator.DONE) {
        segments += text.substring(start, end)
        start = end
        end = breaks.next()
    }
    return segments
}

/**
 * How many chars of [text] fit in [width], cut between two whole characters as a reader sees them —
 * never inside a surrogate pair or between a letter and its mark — and never fewer than one.
 */
private fun fittingPrefix(text: String, width: Float, locale: Locale, measure: (String) -> Float): Int {
    val characters = BreakIterator.getCharacterInstance(locale).apply { setText(text) }
    var fit = characters.next()
    var next = characters.next()
    while (next != BreakIterator.DONE && measure(text.substring(0, next)) <= width) {
        fit = next
        next = characters.next()
    }
    return fit
}
