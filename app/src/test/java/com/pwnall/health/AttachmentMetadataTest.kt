package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentMetadataTest {
    private val original = MedicalAttachment(
        "content://documents/attachment.pdf",
        AttachmentKind.MRI,
        "Lumbar spine\nFollow-up",
        "2026-09-20",
    )

    @Test
    fun oldFilesRemainReadableWithoutGuessingTheirTypes() {
        val old = "content://documents/CT.pdf\ncontent://documents/scan.mp4"
        assertEquals(listOf(AttachmentKind.UNKNOWN, AttachmentKind.UNKNOWN), medicalAttachments(old).map { it.kind })
        assertEquals(old, storeMedicalAttachments(medicalAttachments(old)))
    }

    @Test
    fun descriptionsAndDatesRoundTripAlongsideTheOriginalUri() {
        val files = listOf(original, MedicalAttachment("content://documents/photo.jpg"))
        val stored = storeMedicalAttachments(files)
        assertEquals(files, medicalAttachments(stored))
        assertEquals(files.map { it.uri }, attachmentUris(stored))
        // Backup and lab payloads carry the stored column as a JSON string, not as a URI list.
        val backup = JSONObject().put("attachments", stored).toString()
        assertEquals(files, medicalAttachments(JSONObject(backup).getString("attachments")))
    }

    @Test
    fun importingMoreFilesPreservesExistingMetadataAndAvoidsDuplicates() {
        val stored = addMedicalAttachments(storeMedicalAttachments(listOf(original)), listOf(original.uri, "new.pdf"))
        assertEquals(listOf(original, MedicalAttachment("new.pdf")), medicalAttachments(stored))
    }

    @Test
    fun anUnknownKindRemainsAnAttachmentAndMalformedMetadataIsNotOpenedAsAUri() {
        val future = """{"files":[{"uri":"content://scan","kind":"FUTURE"}]}"""
        assertEquals(listOf(MedicalAttachment("content://scan")), medicalAttachments(future))
        assertTrue(attachmentUris("{invalid").isEmpty())
    }

    @Test
    fun readGrantChecksStillFindDescribedFiles() {
        val visit = Appointment(
            id = 1,
            specialty = "GP",
            atMs = 0,
            attachments = storeMedicalAttachments(listOf(original)),
        )
        assertEquals(setOf(original.uri), namedAttachments(emptyList(), listOf(visit), emptyList()))
    }
}
