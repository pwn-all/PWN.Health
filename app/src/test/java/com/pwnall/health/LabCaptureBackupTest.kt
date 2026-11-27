package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringWriter
import java.util.Base64
import kotlin.random.Random

/**
 * The photographs of lab sheets on their way into a backup: which files the journal names, and what
 * a row of the member carries. The trip back runs through Android's own JsonReader, on a phone.
 */
class LabCaptureBackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tapped = 1_800_000_000_000L
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun labRow(vararg uris: String) = JournalEntry(
        day = "2026-08-10",
        occurredAt = tapped,
        kind = KIND_LABS,
        title = "Blood",
        calories = 0.0,
        measurementPayloadJson = JSONObject().put("attachments", uris.joinToString("\n")).toString(),
    )

    /** The member as the export writes it, opened as the object it sits in. */
    private fun member(names: Set<String>, dir: File?): JSONObject {
        val out = StringWriter()
        out.writeLabCaptures(names, dir)
        return JSONObject("{" + out.toString().removePrefix(",") + "}")
    }

    /** Only sheets in the app's own folder are named: a gallery picture is a reference, a water row names nothing. */
    @Test
    fun `the journal names the sheets its panels still hold`() {
        val journal = listOf(
            labRow(
                "content://com.pwnall.health.photos/lab-captures/lab-1.jpg",
                "content://media/external/images/media/77",
            ),
            labRow("content://com.pwnall.health.photos/lab-captures/lab-2.jpg"),
            JournalEntry(day = "2026-08-10", occurredAt = tapped, kind = KIND_WATER, title = "Water", calories = 0.0),
        )

        assertEquals(setOf("lab-1.jpg", "lab-2.jpg"), labCaptureNames(journal))
    }

    /** A reference outside the folder, a bare folder, or a name with a directory in it names no file. */
    @Test
    fun `a reference outside the folder names no file`() {
        assertNull(labCaptureFileName("content://media/external/images/media/77"))
        assertNull(labCaptureFileName("content://x.photos/lab-captures/../databases/steps.db"))
        assertNull(labCaptureFileName("content://x.photos/lab-captures/"))
        assertEquals("lab-9.jpg", labCaptureFileName("content://x.photos/lab-captures/lab-9.jpg"))
    }

    /**
     * A name out of a backup is held to the whole of itself. Read as a reference it would be checked
     * only up to `?` or `#`, and what followed — a directory, a way out of the folder — would be
     * written to as it stood: two rows would be enough to plant a rollback journal beside the database.
     */
    @Test
    fun `a name from a file is a sheet's only when it is nothing but the name`() {
        assertTrue(isLabCaptureName("lab-9.jpg"))
        assertFalse(isLabCaptureName("x?/a"))
        assertFalse(isLabCaptureName("x?/../../../databases/steps.db-journal"))
        assertFalse(isLabCaptureName("x#/../../../shared_prefs/planted.xml"))
        assertFalse(isLabCaptureName("a.jpg?b"))
        assertFalse(isLabCaptureName("../databases/steps.db"))
        assertFalse(isLabCaptureName(".hidden"))
        assertFalse(isLabCaptureName(""))
    }

    /**
     * A restore puts back only what the camera could have written. Any other name stays inside the
     * folder all the same, and that folder is served to viewers and copied into the doctor's archive
     * under whatever type the name's extension claims.
     */
    @Test
    fun `a restore brings back a sheet only under a name the camera gives`() {
        assertTrue(isRestorableLabCapture("lab-1804912345.jpg"))
        assertFalse(isRestorableLabCapture("planted.html"))
        assertFalse(isRestorableLabCapture("lab-1.pdf"))
        assertFalse(isRestorableLabCapture("lab-1.jpg.part"))
        assertFalse(isRestorableLabCapture("lab-x?/../a.jpg"))
    }

    /**
     * A sheet is kept no larger than an A4 page at 300 dots to the inch, whichever way the phone was
     * held; one the camera wrote smaller is not written again, and a file that is no picture — its
     * bounds read as -1 — is left as it is.
     */
    @Test
    fun `only a photograph larger than a sheet is kept at is shrunk`() {
        assertTrue(outgrowsLabSheet(12_000, 9_000))
        assertTrue(outgrowsLabSheet(3_000, LAB_SHEET_SIDE + 1))
        assertFalse(outgrowsLabSheet(LAB_SHEET_SIDE, 2_480))
        assertFalse(outgrowsLabSheet(-1, -1))
        assertEquals(LAB_SHEET_SIDE to 2_631, shrunkSize(12_000, 9_000, LAB_SHEET_SIDE))
    }

    /** The bytes ride under the name; a sheet whose file has gone is left out rather than restored as nothing. */
    @Test
    fun `each sheet travels as its bytes under its name`() {
        val dir = folder.newFolder("lab-captures")
        File(dir, "lab-1.jpg").writeBytes(jpeg)

        val rows = member(setOf("lab-1.jpg", "lab-gone.jpg"), dir).getJSONArray("labCaptures")

        assertEquals(1, rows.length())
        assertEquals("lab-1.jpg", rows.getJSONObject(0).getString("file"))
        assertArrayEquals(jpeg, Base64.getDecoder().decode(rows.getJSONObject(0).getString("jpeg")))
    }

    /**
     * A sheet is kept as the camera wrote it, tens of megabytes in some modes. Read whole and
     * rendered through one JSON object it would stand in the heap several times over, and one large
     * photograph would end every export; so its bytes go out a slice at a time, and the slices still
     * read back as the one file — a length that is no multiple of three included.
     */
    @Test
    fun `a large sheet is written in slices that read back as the file`() {
        val dir = folder.newFolder("lab-captures")
        val sheet = Random(11).nextBytes(200_000)
        File(dir, "lab-big.jpg").writeBytes(sheet)
        var longest = 0
        val out = object : StringWriter() {
            override fun write(str: String) {
                longest = maxOf(longest, str.length)
                super.write(str)
            }
        }

        out.writeLabCaptures(setOf("lab-big.jpg"), dir)

        val rows = JSONObject("{" + out.toString().removePrefix(",") + "}").getJSONArray("labCaptures")
        assertArrayEquals(sheet, Base64.getDecoder().decode(rows.getJSONObject(0).getString("jpeg")))
        assertTrue("one write of $longest characters", longest <= 64 * 1024)
    }

    /** With no folder to read from, the member is written empty, as the photographs' are. */
    @Test
    fun `no folder is an empty member`() {
        assertEquals(0, member(setOf("lab-1.jpg"), null).getJSONArray("labCaptures").length())
    }
}
