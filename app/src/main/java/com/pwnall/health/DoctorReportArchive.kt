package com.pwnall.health

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

internal const val REPORT_ARCHIVE_MIME = "application/zip"

/** The byte order mark, which in front of UTF-8 text says only that it is UTF-8. */
private val UTF8_BOM = Char(0xFEFF)

/** A reference to an original file and the selected record that explains it. */
data class ReportAttachment(
    val uri: String,
    val part: ReportPart,
    val day: String,
    val record: ReportText,
    val kind: AttachmentKind = AttachmentKind.UNKNOWN,
    val description: String = "",
    val studyDay: String = "",
)

internal fun reportAttachments(
    entries: List<JournalEntry>,
    courses: List<Medication>,
    visits: List<Appointment>,
    parts: Set<ReportPart>,
    zone: ZoneId,
): List<ReportAttachment> = buildList {
    fun addFiles(stored: String, part: ReportPart, day: String, record: ReportText) {
        if (part in parts) {
            medicalAttachments(stored).distinctBy { it.uri }.forEach {
                add(ReportAttachment(it.uri, part, day, record, it.kind, it.description, it.studyDay))
            }
        }
    }
    visits.forEach {
        addFiles(
            it.attachments,
            ReportPart.VISITS,
            Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate().toString(),
            ReportText.Join(listOf(ReportText.Res(specialtyTitleRes(it.specialty)), ReportText.Plain(it.doctor))),
        )
    }
    entries.filter { it.kind == KIND_LABS }.forEach {
        // Named as the day list names it, in the sheet's language: "Ultrasound", not the word of the day it was saved.
        addFiles(it.labAttachments(), ReportPart.LABS, it.day, ReportText.Res(it.labTitleRes()))
    }
    courses.forEach {
        addFiles(it.attachments, ReportPart.MEDICATIONS, it.startDay, ReportText.Plain(it.name))
    }
}

internal fun doctorArchiveFileName(report: DoctorReport): String =
    doctorReportFileName(report.from, report.to).removeSuffix(".pdf") + ".zip"

/**
 * Numbered basenames prevent collisions and keep provider-supplied paths inside attachments/.
 *
 * A long name is cut to [ARCHIVE_NAME_BYTES] bytes rather than characters: a file name is at most 255
 * bytes where the archive is unpacked, and 160 Cyrillic letters are 320 of them. The cut takes whole
 * characters and falls before the extension, so the file still opens as what it is.
 */
internal fun archiveAttachmentPath(index: Int, name: String): String {
    val safe = name.substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isISOControl() || it in ":*?\"<>|") '_' else it }.joinToString("")
        .trim().takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
    val extension = safe.substringAfterLast('.', "").takeIf { it.length in 1..MAX_EXTENSION }
        ?.let { ".$it" }.orEmpty()
    val room = ARCHIVE_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size
    return "attachments/${index + 1}-${safe.removeSuffix(extension).utf8Prefix(room)}$extension"
}

/** The longest start of this string that is at most [bytes] in UTF-8, cut between whole characters. */
private fun String.utf8Prefix(bytes: Int): String {
    var used = 0
    var end = 0
    while (end < length) {
        val point = codePointAt(end)
        used += String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
        if (used > bytes) break
        end += Character.charCount(point)
    }
    return substring(0, end)
}

/** What a basename in the archive may take, the number in front of it aside. */
private const val ARCHIVE_NAME_BYTES = 160

/** The longest ending kept as an extension when a name is cut: "dicom" and "jpeg", not half a sentence. */
private const val MAX_EXTENSION = 10

/**
 * One cell of attachments.tsv: on one line, and text rather than a formula. A spreadsheet runs a
 * cell that opens with =, +, - or @ as one, and a note or a file name somebody typed is not; the
 * apostrophe in front is how a spreadsheet is told so.
 */
internal fun indexCell(value: String): String {
    val flat = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    return if (flat.take(1) in FORMULA_STARTS) "'$flat" else flat
}

private val FORMULA_STARTS = setOf("=", "+", "-", "@")

internal data class ArchiveFile(val uri: String, val path: String)

/** Streams even large scans unchanged; a file that cannot be opened fails the export rather than disappearing. */
internal suspend fun writeReportArchive(
    output: OutputStream,
    pdfName: String,
    index: String,
    files: List<ArchiveFile>,
    open: (ArchiveFile) -> InputStream,
    pdf: suspend (OutputStream) -> Unit,
) {
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry(pdfName))
        pdf(zip)
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("attachments.tsv"))
        // Marked as UTF-8 in front: a spreadsheet opens an unmarked file in the computer's own code
        // page, and a Cyrillic or Japanese name in it would come out as mojibake.
        zip.write((UTF8_BOM + index).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        files.forEach { file ->
            zip.putNextEntry(ZipEntry(file.path))
            open(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

private suspend fun writeDoctorArchive(context: Context, report: DoctorReport, output: OutputStream) {
    val files = report.attachments.distinctBy { it.uri }.mapIndexed { index, attachment ->
        ArchiveFile(attachment.uri, archiveAttachmentPath(index, context.attachmentName(attachment.uri)))
    }
    // A reference the phone can no longer open — the file deleted, its app gone, a row restored on
    // another phone — is listed as unreadable and left out of the archive, rather than failing the
    // whole export on the one attachment nobody can produce anyway.
    val readable = files.filterTo(HashSet()) { file -> context.canOpen(file.uri) }
    val paths = files.associate {
        it.uri to if (it in readable) it.path else context.getString(R.string.report_archive_unavailable, it.path)
    }
    val datePattern = report.request.country.datePatternIn(context.resources.configuration.locales[0])
    val index = buildString {
        appendLine(context.getString(R.string.report_archive_index_header))
        report.attachments.forEach {
            appendLine(
                listOf(
                    paths.getValue(it.uri),
                    it.day,
                    context.getString(it.part.titleRes),
                    it.record.resolve(context.resources, datePattern),
                    context.getString(it.kind.titleRes),
                    it.studyDay,
                    it.description,
                ).joinToString("\t", transform = ::indexCell),
            )
        }
    }
    writeReportArchive(
        output,
        doctorReportFileName(report.from, report.to),
        index,
        files.filter { it in readable },
        open = { file ->
            runCatching { context.contentResolver.openInputStream(file.uri.toUri()) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    throw IOException(context.getString(R.string.report_archive_unavailable, file.path), error)
                } ?: error(context.getString(R.string.report_archive_unavailable, file.path))
        },
        pdf = { writeDoctorReport(context, reportWithAttachmentCatalog(report, paths), it) },
    )
}

/**
 * Whether the reference still opens: a grant that outlived its file, or its app, answers no. So
 * does one that was never an attachment's to name — a path, or a file of this app's own other than
 * a lab sheet's photograph ([attachmentMayLeave]) — which a restored backup can put in a row.
 */
private fun Context.canOpen(uri: String): Boolean = attachmentMayLeave(uri, "$packageName.photos") &&
    runCatching { contentResolver.openInputStream(uri.toUri())?.use { } != null }.getOrDefault(false)

/** Names match the ZIP paths, so the PDF itself tells the doctor which original to open. */
internal fun reportWithAttachmentCatalog(report: DoctorReport, paths: Map<String, String>): DoctorReport {
    val sections = report.sections.map { section ->
        val files = report.attachments.filter { it.part == section.part }
        if (files.isEmpty()) return@map section
        section.copy(
            presence = SectionPresence.PRESENT,
            rows = section.rows + files.map {
                ReportRow(
                    label = ReportText.Res(R.string.attachments_title),
                    value = ReportText.Join(
                        listOf(ReportText.Res(it.kind.titleRes), ReportText.Plain(it.description)),
                    ),
                    note = ReportText.Join(
                        listOf(
                            ReportText.Plain(
                                it.studyDay.ifBlank {
                                    it.day
                                },
                            ),
                            ReportText.Plain(paths.getValue(it.uri)),
                        ),
                    ),
                    detail = true,
                )
            },
        )
    }
    return report.copy(
        sections = sections,
        notes = report.notes + listOfNotNull(
            ReportText.Res(R.string.attachment_catalog_note).takeIf { report.attachments.isNotEmpty() },
        ),
    )
}

internal suspend fun saveDoctorArchive(context: Context, report: DoctorReport, uri: Uri): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            // As the PDF's own save does: no stream is a document that cannot be written, in words.
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw FileNotFoundException(context.getString(R.string.file_import_unopenable))
            out.use { writeDoctorArchive(context, report, it) }
        }.onFailure {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            if (it is CancellationException) throw it
        }
    }

internal suspend fun shareDoctorArchive(context: Context, report: DoctorReport): Result<Unit> = runCatching {
    val file = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "reports").apply { mkdirs() }
        // Whatever was shared before goes as the next file is made, the way the PDF's own share
        // does it: an archive holds whole copies of the attached scans, and they would pile up here.
        folder.listFiles()?.forEach { it.delete() }
        // Under the name the saved archive has: it is what the app it is handed to shows.
        File(folder, doctorArchiveFileName(report)).also { target ->
            runCatching {
                target.outputStream().use { writeDoctorArchive(context, report, it) }
            }.onFailure { target.delete() }.getOrThrow()
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    val title = context.getString(R.string.report_share_title)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = REPORT_ARCHIVE_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title))
}.onFailure { if (it is CancellationException) throw it }
