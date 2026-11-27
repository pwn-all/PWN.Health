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

/**
 * A walk's photographs on their way into a backup file and out of it again: what the member
 * carries, what a row is believed about, and which photographs a restore takes. The streaming
 * reader around these is Android's own JsonReader, so the trip through a real import runs on a
 * phone, in WalkPhotoBackupTest — and so does what the import keeps track of while it reads: the
 * rows the phone held before it, the names it has claimed so far, a row that came without bytes.
 */
class TrackPhotoBackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tapped = 1_800_000_000_000L
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private val photo = TrackPhotoEntity(
        id = 9,
        trackId = 3,
        takenAt = tapped,
        file = "$tapped.jpg",
        lat = 55.751244,
        lon = 37.618423,
        altitudeM = 156.5,
        accuracyM = 4.2f,
        bearingDeg = 271.3f,
        bearingAccuracyDeg = 12.7f,
        pitchDeg = -7.1f,
        rollDeg = 90f,
    )

    /** The member as the export writes it, opened as the object it sits in. */
    private fun member(rows: List<TrackPhotoEntity>, dir: File?): JSONObject {
        val out = StringWriter()
        out.writeTrackPhotos(rows, dir)
        return JSONObject("{" + out.toString().removePrefix(",") + "}")
    }

    /** Every reading comes back as the phone took it; only the id is this phone's own to give. */
    @Test
    fun `a walk photograph keeps every reading through the file`() {
        val restored = JSONObject(photo.toJson("AAEC").toString()).toTrackPhoto()

        assertEquals(photo.copy(id = 0), restored)
    }

    /** A reading the phone could not give stays no reading, rather than a zero that is a place. */
    @Test
    fun `nothing the phone could not give comes back as a reading`() {
        val bare = TrackPhotoEntity(trackId = 3, takenAt = tapped, file = "$tapped.jpg")

        val row = bare.toJson("AAEC")

        listOf("lat", "lon", "altitudeM", "accuracyM", "bearingDeg", "bearingAccuracyDeg", "pitchDeg", "rollDeg")
            .forEach { assertFalse(it, row.has(it)) }
        assertEquals(bare, JSONObject(row.toString()).toTrackPhoto())
    }

    /**
     * JSONObject refuses a non-finite number outright, and the export renders inside the database's
     * own transaction: one such reading would end every backup the phone writes. It goes out as no
     * reading, and a quoted one in a file somebody else wrote comes in as none.
     */
    @Test
    fun `a reading that will not render is written and read as no reading`() {
        val damaged = photo.copy(bearingDeg = Float.POSITIVE_INFINITY, pitchDeg = Float.NaN, lat = Double.NaN)

        val row = damaged.toJson("AAEC")

        assertFalse(row.has("bearingDeg"))
        assertFalse(row.has("pitchDeg"))
        assertFalse(row.has("lat"))
        assertEquals(37.618423, row.getDouble("lon"), 0.0)
        val quoted = JSONObject().put("trackId", 3).put("takenAt", tapped).put("bearingDeg", "1e999")
        assertNull(quoted.toTrackPhoto().bearingDeg)
    }

    /**
     * The name a row came with is somebody else's text. It is rebuilt from the moment, as a progress
     * photograph's is, so `../databases/steps.db` never reaches the disk as a name.
     */
    @Test
    fun `a restored photograph is named after its moment, never after the file`() {
        val hostile = photo.toJson("AAEC").put("file", "../databases/steps.db")

        assertEquals("$tapped.jpg", hostile.toTrackPhoto().file)
    }

    /**
     * The member carries each picture's bytes beside its row, and leaves out a row whose file has
     * gone or whose name has a directory in it: neither is a picture this phone can vouch for.
     */
    @Test
    fun `the member carries the bytes and leaves out a picture that is not there`() {
        val dir = folder.newFolder("walk-photos")
        File(dir, "$tapped.jpg").writeBytes(jpeg)
        File(dir, "${tapped + 2}.jpg").writeBytes(jpeg.reversedArray())
        folder.newFile("outside.jpg").writeBytes(jpeg)
        val gone = photo.copy(id = 8, takenAt = tapped - 1, file = "${tapped - 1}.jpg")
        val second = photo.copy(id = 10, takenAt = tapped + 2, file = "${tapped + 2}.jpg")
        val climbing = photo.copy(id = 11, takenAt = tapped + 3, file = "../outside.jpg")

        val rows = member(listOf(gone, photo, second, climbing), dir).getJSONArray("trackPhotos")

        assertEquals(2, rows.length())
        val first = rows.getJSONObject(0)
        assertFalse("the id is this phone's own", first.has("id"))
        assertArrayEquals(jpeg, Base64.getDecoder().decode(first.getString("jpeg")))
        assertEquals(photo.copy(id = 0), first.toTrackPhoto())
        assertEquals(second.copy(id = 0), rows.getJSONObject(1).toTrackPhoto())
        assertArrayEquals(jpeg.reversedArray(), Base64.getDecoder().decode(rows.getJSONObject(1).getString("jpeg")))
    }

    /** Without the directory the pictures are read from, the member is there and empty. */
    @Test
    fun `an export without the directory writes an empty member`() {
        assertEquals(0, member(listOf(photo), null).getJSONArray("trackPhotos").length())
    }

    /**
     * A photograph comes back under the id its route was given here, once: a route the file did not
     * carry has nowhere to take it, and a moment of a route this phone already holds a photograph of
     * is that photograph, already here.
     */
    @Test
    fun `a photograph comes back under its route's id here, and only once`() {
        val fromFile = photo.copy(id = 0)

        assertEquals(fromFile.copy(trackId = 1), restoredTrackPhoto(fromFile, mapOf(3L to 1L), emptySet()) { false })
        assertNull("its route is not in the file", restoredTrackPhoto(fromFile, mapOf(4L to 1L), emptySet()) { false })
        assertNull("already here", restoredTrackPhoto(fromFile, mapOf(3L to 1L), setOf(1L to tapped)) { false })
    }

    /**
     * Two phones that recorded the same walk can hold photographs of one moment on two routes. The
     * second is restored beside the first under the next free name, never over it.
     */
    @Test
    fun `a name another picture holds moves the restored one to the next stamp`() {
        val fromFile = photo.copy(id = 0)
        val taken = setOf("$tapped.jpg", "${tapped + 1}.jpg")

        val restored = restoredTrackPhoto(fromFile, mapOf(3L to 1L), setOf(2L to tapped)) { it in taken }

        assertEquals(fromFile.copy(trackId = 1, file = "${tapped + 2}.jpg"), restored)
    }

    /**
     * A move to a new phone carries the walk photographs with the database that names them: the
     * device-transfer rules name the directory the app keeps them in, under its own spelling.
     */
    @Test
    fun `a move to a new phone carries the walk photographs`() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")

        assertTrue(transfer, """<include domain="file" path="$WALK_PHOTO_DIR/" />""" in transfer)
    }
}
