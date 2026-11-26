package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream

class DoctorReportArchiveTest {
    private val day = LocalDate.of(2026, 9, 10)
    private val zone = ZoneId.of("Asia/Dubai")
    private val time = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val visit = Appointment(
        specialty = "GP",
        doctor = "Dr Smith",
        place = "Clinic",
        atMs = time,
        doneAt = time,
        note = "Follow up after MRI",
        attachments = "content://mri",
    )

    private fun report(parts: Set<ReportPart> = setOf(ReportPart.VISITS, ReportPart.LABS, ReportPart.MEDICATIONS)) =
        doctorReport(
            ReportSources(
                UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1985-04-10"),
                listOf(
                    lab(day),
                    lab(day.minusDays(1)).copy(
                        measurementPayloadJson = JSONObject().put("attachments", "content://old-lab").toString(),
                    ),
                ),
                emptyList(),
                medications = listOf(
                    Medication(
                        name = "Medicine",
                        dose = "1",
                        times = "480",
                        startDay = day.toString(),
                        createdAt = time,
                        attachments = "content://prescription",
                    ),
                    Medication(
                        name = "Old",
                        dose = "1",
                        times = "480",
                        startDay = day.minusDays(4).toString(),
                        endDay = day.minusDays(1).toString(),
                        createdAt = time,
                        attachments = "content://old",
                    ),
                ),
                visits = listOf(visit, visit.copy(doneAt = null, attachments = "content://planned")),
            ),
            ReportRequest(from = day, to = day, parts = parts),
            today = day,
            zone = zone,
        )

    private fun lab(date: LocalDate) = JournalEntry(
        day = date.toString(),
        occurredAt = time,
        kind = KIND_LABS,
        title = "Ultrasound",
        calories = 0.0,
        measurementPayloadJson = JSONObject().put("attachments", "content://ultrasound").toString(),
    )

    @Test fun visitsUseLocalDatesAndCarryDoctorSpecialtyPlaceAndNotes() {
        val section = report().sections.first { it.part == ReportPart.VISITS }
        assertEquals(ReportText.Day(day), section.rows.first().label)
        assertEquals(ReportText.Plain("Clinic"), section.rows.first().note)
        val doctor = section.rows.first().value as ReportText.Join
        assertTrue(ReportText.Plain("Dr Smith") in doctor.parts)
        assertTrue(ReportText.Res(specialtyTitleRes("GP")) in doctor.parts)
        assertEquals(ReportText.Plain("Follow up after MRI"), section.rows.last().value)
        assertEquals(2, section.rows.size)
        assertTrue(reportVisits(listOf(visit), day.minusDays(1), day.minusDays(1), zone).isEmpty())
    }

    @Test fun filesFollowSelectedSectionsAndPeriod() {
        assertEquals(
            setOf("content://mri", "content://ultrasound", "content://prescription"),
            report().attachments.map { it.uri }.toSet(),
        )
        assertTrue(report(emptySet()).attachments.isEmpty())
        assertEquals(listOf("content://mri"), report(setOf(ReportPart.VISITS)).attachments.map { it.uri })
    }

    @Test fun sharedFileKeepsBothRecordReferences() {
        val references = reportAttachments(
            listOf(lab(day)),
            emptyList(),
            listOf(visit.copy(attachments = "content://ultrasound")),
            setOf(ReportPart.LABS, ReportPart.VISITS),
            zone,
        )
        assertEquals(2, references.size)
        assertEquals(1, references.distinctBy { it.uri }.size)
    }

    @Test
    fun examinationMetadataReachesTheArchivePdfWithoutChangingTheOriginalReport() {
        val described = visit.copy(
            attachments = storeMedicalAttachments(
                listOf(MedicalAttachment("content://mri", AttachmentKind.MRI, "Spine", "2026-09-09")),
            ),
        )
        val files = reportAttachments(emptyList(), emptyList(), listOf(described), setOf(ReportPart.VISITS), zone)
        assertEquals(AttachmentKind.MRI, files.single().kind)
        assertEquals("Spine", files.single().description)
        assertEquals("2026-09-09", files.single().studyDay)
        val original = report(setOf(ReportPart.VISITS)).copy(attachments = files)
        val archive = reportWithAttachmentCatalog(original, mapOf("content://mri" to "attachments/1-attachment.pdf"))
        val rows = archive.sections.first { it.part == ReportPart.VISITS }.rows
        val description = rows.last().value as ReportText.Join
        assertTrue(ReportText.Res(R.string.attachment_kind_mri) in description.parts)
        assertTrue(ReportText.Plain("Spine") in description.parts)
        assertEquals(2, original.sections.first { it.part == ReportPart.VISITS }.rows.size)
        assertEquals(3, rows.size)
    }

    @Test fun archiveNamesCannotEscapeFolderOrCollide() {
        assertEquals("attachments/1-scan.pdf", archiveAttachmentPath(0, "../../scan.pdf"))
        assertEquals("attachments/2-scan.pdf", archiveAttachmentPath(1, "C:\\scan.pdf"))
        assertEquals("attachments/1-attachment", archiveAttachmentPath(0, ".."))
        assertFalse(archiveAttachmentPath(0, "scan\n.pdf").contains('\n'))
    }

    /**
     * A long name is cut by the bytes a filesystem counts, not by letters: 160 Cyrillic ones are 320
     * bytes, past the 255 a name may take, and the archive would not unpack. The cut keeps the
     * extension and never halves a character.
     */
    @Test fun aLongNameIsCutToItsBytesAndKeepsItsExtension() {
        assertEquals("attachments/1-" + "Я".repeat(78) + ".pdf", archiveAttachmentPath(0, "Я".repeat(200) + ".pdf"))
        val emoji = "😀"
        assertEquals("attachments/1-a" + emoji.repeat(39), archiveAttachmentPath(0, "a" + emoji.repeat(50)))
        assertEquals("attachments/1-scan.pdf", archiveAttachmentPath(0, "scan.pdf"))
    }

    /** A cell a spreadsheet would run as a formula is written as text, and no cell breaks its line. */
    @Test fun theIndexHoldsNoFormulaAndNoBrokenLine() {
        assertEquals("'=HYPERLINK(\"http://example.com\")", indexCell("=HYPERLINK(\"http://example.com\")"))
        assertEquals(listOf("'+1", "'-2", "'@SUM(A1)"), listOf("+1", "-2", "@SUM(A1)").map(::indexCell))
        assertEquals("MRI head  spine", indexCell("MRI\thead\r\nspine"))
        assertEquals("2026-09-10", indexCell("2026-09-10"))
    }

    @Test fun archivePreservesOriginalBytesAndIncludesPdfAndIndex() = runBlocking {
        val original = ByteArray(100_000) { (it % 256).toByte() }
        val output = ByteArrayOutputStream()
        writeReportArchive(
            output,
            "report.pdf",
            "File\tDate\nattachments/1-MRI.dcm\t2026-09-10",
            listOf(ArchiveFile("content://mri", "attachments/1-MRI.dcm")),
            open = { ByteArrayInputStream(original) },
            pdf = { it.write("PDF".toByteArray()) },
        )
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        assertEquals(setOf("report.pdf", "attachments.tsv", "attachments/1-MRI.dcm"), entries.keys)
        assertArrayEquals(original, entries.getValue("attachments/1-MRI.dcm"))
        assertEquals("PDF", entries.getValue("report.pdf").toString(Charsets.UTF_8))
        assertTrue(entries.getValue("attachments.tsv").toString(Charsets.UTF_8).contains("2026-09-10"))
        // Marked as UTF-8, or a spreadsheet reads a Cyrillic name in the computer's own code page.
        assertArrayEquals(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            entries.getValue("attachments.tsv").copyOf(3),
        )
    }

    @Test(expected = IOException::class)
    fun missingFileFailsExport() = runBlocking {
        writeReportArchive(
            ByteArrayOutputStream(),
            "report.pdf",
            "index",
            listOf(ArchiveFile("content://missing", "attachments/1-scan.pdf")),
            open = { throw IOException("Missing original") },
            pdf = { it.write(1) },
        )
    }

    /** Whether a path reaches the ZIP only a phone shows, so the gate is pinned in the source. */
    @Test
    fun onlyAReferenceThatMayLeaveIsOpenedForTheArchive() {
        val canOpen = File("src/main/java/com/pwnall/health/DoctorReportArchive.kt").readText()
            .substringAfter("fun Context.canOpen(").substringBefore("\n\n")
        assertTrue(canOpen, "attachmentMayLeave(uri, \"\$packageName.photos\")" in canOpen)
    }
}
