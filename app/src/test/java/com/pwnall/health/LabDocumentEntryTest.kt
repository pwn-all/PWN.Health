package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * An ultrasound, an MRI or another document is a lab row with no figures: its one file, of the kind
 * the first page named and dated by the examination, and the note — on the file, where the results
 * page's shelf and the doctor's archive read it, and on the row, where the diary does.
 */
class LabDocumentEntryTest {
    @Test
    fun aDocumentIsItsFileItsDayAndItsNote() {
        val entry = labDocumentEntry(
            AttachmentKind.ULTRASOUND,
            "Ultrasound",
            "content://scan/1",
            LocalDate.parse("2026-10-09"),
            "  Liver normal  ",
        )

        assertEquals(KIND_LABS, entry.kind)
        assertEquals("Ultrasound", entry.title)
        assertEquals("2026-10-09", entry.day)
        assertEquals(dayInstant("2026-10-09"), entry.occurredAt)
        assertEquals("Liver normal", entry.note)
        assertEquals(
            listOf(MedicalAttachment("content://scan/1", AttachmentKind.ULTRASOUND, "Liver normal", "2026-10-09")),
            medicalAttachments(entry.labAttachments()),
        )
        // No figure, and nothing that would be read as one: every marker's history stays empty.
        val profile = UserProfile(sex = "MALE", weightKg = 75.0, heightCm = 180, birthDate = "1980-01-01")
        assertTrue(labHistory(listOf(entry), profile).isEmpty())
        assertFalse(JSONObject(entry.measurementPayloadJson.orEmpty()).getBoolean(FASTING_KEY))
    }

    /** Blood and urine are typed in; a document has nothing to type, and comes as a file or a photo. */
    @Test
    fun onlyFiguresOfferManualEntry() {
        LabAddType.entries.forEach { type ->
            assertEquals(type.name, type.document == null, LabAddMethod.MANUAL in type.methods)
            // Each type is one of the two and never both: a sample's panel, or a document's file.
            assertTrue(type.name, (type.sample == null) != (type.document == null))
        }
        LabSample.entries.forEach { assertEquals(it, it.addType().sample) }
    }
}
