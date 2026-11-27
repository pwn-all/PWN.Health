package com.pwnall.health

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A walk's photographs through a real export and a real import: the bytes in the file, the route
 * and the name they come back under, and what is left on disk when a restore goes wrong. The rules
 * the import applies are in TrackPhotoBackupTest; this is the check that the import applies them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class WalkPhotoBackupTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var source: StepsDb
    private lateinit var target: StepsDb
    private lateinit var sourceDir: File
    private lateinit var targetDir: File

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 4, 5, 6, 0xFF.toByte(), 0xD9.toByte())
    private val walk = TrackEntity(
        startedAt = STARTED,
        endedAt = STARTED + 3_600_000L,
        distanceMeters = 4_210.5,
        points = 1_650,
        name = "Lactate party",
    )

    private fun photo(trackId: Long) = TrackPhotoEntity(
        trackId = trackId,
        takenAt = TAKEN,
        file = "$TAKEN.jpg",
        lat = 55.751244,
        lon = 37.618423,
        altitudeM = 156.5,
        accuracyM = 4.5f,
        bearingDeg = 271.5f,
        bearingAccuracyDeg = 12.5f,
        pitchDeg = -7.25f,
        rollDeg = 90f,
    )

    @Before
    fun open() {
        source = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
        val root = File(context.cacheDir, "walk-photo-backup-test").apply { deleteRecursively() }
        sourceDir = File(root, "source").apply { mkdirs() }
        targetDir = File(root, "target").apply { mkdirs() }
    }

    @After
    fun close() {
        source.close()
        target.close()
        File(context.cacheDir, "walk-photo-backup-test").deleteRecursively()
    }

    /** The walk and its photograph on the source phone, the picture's file only when [withFile]. */
    private suspend fun exported(withFile: Boolean = true): ByteArray {
        val trackId = source.dao().insertTrack(walk)
        if (withFile) File(sourceDir, "$TAKEN.jpg").writeBytes(jpeg)
        source.dao().saveTrackPhoto(photo(trackId))
        return ByteArrayOutputStream().also {
            LocalBackup.export(source, it, null, null, sourceDir, INSTALL)
        }.toByteArray()
    }

    private suspend fun import(into: StepsDb, bytes: ByteArray, dir: File?) {
        LocalBackup.import(into, bytes.inputStream(), null, null, dir, INSTALL)
    }

    /** Rewrites the file's first walk photograph, as a file somebody else wrote may have it. */
    private fun ByteArray.withPhoto(edit: (JSONObject) -> Unit): ByteArray {
        val file = JSONObject(String(this))
        edit(file.getJSONArray("trackPhotos").getJSONObject(0))
        return file.toString().toByteArray()
    }

    private fun File.names() = list().orEmpty().sorted()

    @Test
    fun theBytesRideInTheRowAndComeBackBesideTheirRoute() = runBlocking {
        val bytes = exported()
        val row = JSONObject(String(bytes)).getJSONArray("trackPhotos").getJSONObject(0)
        assertFalse("the id is the source phone's own", row.has("id"))
        assertTrue("the picture itself is in the file", row.getString("jpeg").isNotEmpty())
        // A route of the target's own first, so the restored walk is given an id it did not have.
        target.dao().insertTrack(TrackEntity(startedAt = STARTED - 86_400_000L))

        import(target, bytes, targetDir)

        val route = target.dao().allTracks().single { it.startedAt == STARTED }
        val restored = target.dao().allTrackPhotos().single()
        assertEquals(photo(route.id).copy(id = restored.id), restored)
        assertArrayEquals(jpeg, File(targetDir, restored.file).readBytes())
        assertEquals("no part is left beside it", listOf("$TAKEN.jpg"), targetDir.names())
    }

    /** A phone that already holds the walk gets the photograph it lacks under its own route. */
    @Test
    fun aRouteThisPhoneAlreadyHasTakesThePhotographItLacks() = runBlocking {
        val bytes = exported()
        target.dao().insertTrack(TrackEntity(startedAt = STARTED - 86_400_000L))
        val here = target.dao().insertTrack(walk)

        import(target, bytes, targetDir)

        assertEquals(2, target.dao().allTracks().size)
        assertEquals(here, target.dao().allTrackPhotos().single().trackId)
    }

    /** The same file imported again, or into the phone it came from, adds no second picture. */
    @Test
    fun aSecondImportAddsNothing() = runBlocking {
        val bytes = exported()
        import(target, bytes, targetDir)
        import(target, bytes, targetDir)
        import(source, bytes, sourceDir)

        assertEquals(1, target.dao().allTrackPhotos().size)
        assertEquals(listOf("$TAKEN.jpg"), targetDir.names())
        assertEquals(1, source.dao().allTrackPhotos().size)
        assertEquals(listOf("$TAKEN.jpg"), sourceDir.names())
    }

    /**
     * The row stayed and the file did not. Taken for the photograph already here, the picture would
     * never be brought back by a restore; it comes back under the row's own name, and no second row
     * is made for it.
     */
    @Test
    fun aPhotographWhoseFileWentTakesItBackUnderItsOwnRow() = runBlocking {
        val bytes = exported()
        File(sourceDir, "$TAKEN.jpg").delete()

        import(source, bytes, sourceDir)

        assertEquals(1, source.dao().allTrackPhotos().size)
        assertArrayEquals(jpeg, File(sourceDir, "$TAKEN.jpg").readBytes())
        assertEquals(listOf("$TAKEN.jpg"), sourceDir.names())
    }

    /**
     * Two photographs of one walk under one moment — a camera stamp that names only the second — are
     * two pictures. Both come back, the second under the next free name, and a second import of the
     * same file still adds nothing.
     */
    @Test
    fun twoPhotographsOfOneMomentBothComeBack() = runBlocking {
        val trackId = source.dao().insertTrack(walk)
        val other = jpeg.reversedArray()
        File(sourceDir, "$TAKEN.jpg").writeBytes(jpeg)
        File(sourceDir, "${TAKEN + 1}.jpg").writeBytes(other)
        source.dao().saveTrackPhotos(listOf(photo(trackId), photo(trackId).copy(file = "${TAKEN + 1}.jpg")))
        val bytes = ByteArrayOutputStream().also {
            LocalBackup.export(source, it, null, null, sourceDir, INSTALL)
        }.toByteArray()

        import(target, bytes, targetDir)
        import(target, bytes, targetDir)

        val restored = target.dao().allTrackPhotos()
        assertEquals(listOf("$TAKEN.jpg", "${TAKEN + 1}.jpg"), restored.map { it.file }.sorted())
        assertEquals(
            setOf(jpeg.toList(), other.toList()),
            restored.map { File(targetDir, it.file).readBytes().toList() }.toSet(),
        )
        assertEquals(listOf("$TAKEN.jpg", "${TAKEN + 1}.jpg"), targetDir.names())
    }

    /** A file already under the name — another walk's, or an orphan — is never overwritten. */
    @Test
    fun aNameAlreadyOnDiskMovesTheRestoredPictureToTheNextStamp() = runBlocking {
        val bytes = exported()
        val orphan = byteArrayOf(9, 9, 9)
        File(targetDir, "$TAKEN.jpg").writeBytes(orphan)

        import(target, bytes, targetDir)

        val restored = target.dao().allTrackPhotos().single()
        assertEquals("${TAKEN + 1}.jpg", restored.file)
        assertArrayEquals(jpeg, File(targetDir, restored.file).readBytes())
        assertArrayEquals(orphan, File(targetDir, "$TAKEN.jpg").readBytes())
    }

    /** The name in the file is never read: the picture lands under its moment, and nowhere else. */
    @Test
    fun aHostileNameInTheFileIsNotBelieved() = runBlocking {
        val bytes = exported().withPhoto { it.put("file", "../databases/steps.db") }

        import(target, bytes, targetDir)

        assertEquals("$TAKEN.jpg", target.dao().allTrackPhotos().single().file)
        assertEquals(listOf("$TAKEN.jpg"), targetDir.names())
        assertFalse(File(targetDir.parentFile, "databases").exists())
    }

    /** A row whose picture has gone is left out of the file rather than restored as nothing. */
    @Test
    fun aPhotographWhoseFileHasGoneIsLeftOutOfTheFile() = runBlocking {
        val bytes = exported(withFile = false)

        assertEquals(0, JSONObject(String(bytes)).getJSONArray("trackPhotos").length())
    }

    /** Nowhere to put the pictures: the walk comes back without them rather than naming nothing. */
    @Test
    fun anImportWithoutADirectoryBringsTheRouteBackWithoutItsPhotographs() = runBlocking {
        import(target, exported(), null)

        assertEquals(1, target.dao().allTracks().size)
        assertEquals(emptyList<TrackPhotoEntity>(), target.dao().allTrackPhotos())
    }

    /** A photograph ahead of the routes has no route to go to, and the file is refused whole. */
    @Test
    fun photographsAheadOfTheirRoutesAreRefused() = runBlocking {
        val file = JSONObject(String(exported()))
        val reordered = JSONObject()
            .put("schema", file.getInt("schema"))
            .put("trackPhotos", file.getJSONArray("trackPhotos"))
            .put("tracks", file.getJSONArray("tracks"))
            .toString()
            .toByteArray()

        val failure = runCatching { import(target, reordered, targetDir) }.exceptionOrNull()

        assertTrue("refused with $failure", failure is IllegalArgumentException)
        assertEquals("trackPhotos precede tracks", failure?.message)
        assertEquals(emptyList<TrackEntity>(), target.dao().allTracks())
        assertEquals(emptyList<String>(), targetDir.names())
    }

    /** A restore that fails after the photographs rolls the walk back and leaves no picture on disk. */
    @Test
    fun aRollbackLeavesNoPartBehind() = runBlocking {
        val broken = JSONObject(String(exported())).apply {
            put("appointments", JSONArray().put(JSONObject()))
        }.toString().toByteArray()

        val failure = runCatching { import(target, broken, targetDir) }.exceptionOrNull()

        assertTrue("an appointment without its fields is not a restore", failure != null)
        assertEquals(emptyList<TrackEntity>(), target.dao().allTracks())
        assertEquals(emptyList<TrackPhotoEntity>(), target.dao().allTrackPhotos())
        assertEquals(emptyList<String>(), targetDir.names())
    }

    /**
     * A walk deleted for good, on a real database: the cascades take its fixes and its photographs'
     * rows, the picture's file goes after them, and the tombstone that keeps a backup from bringing
     * the walk back is left. The JVM checks only the key and the filter (WalkScreenTest).
     */
    @Test
    fun aDeletedWalkTakesItsFixesAndPhotographsAndLeavesItsTombstone() = runBlocking {
        val dao = source.dao()
        val trackId = dao.insertTrack(walk)
        val fix = TrackPointEntity(
            trackId = trackId,
            timestamp = TAKEN,
            lat = 55.751244,
            lon = 37.618423,
            altitudeM = null,
            accuracyM = null,
            speedMps = null,
            provider = "gps",
            accepted = true,
        )
        dao.insertPoints(listOf(fix))
        dao.saveTrackPhoto(photo(trackId))
        // Where the app itself keeps them: the deletion is not told a folder.
        val picture = File(walkPhotoDir(context).apply { mkdirs() }, "$TAKEN.jpg").apply { writeBytes(jpeg) }

        source.deleteWalk(context, walk.copy(id = trackId))

        assertEquals(emptyList<TrackEntity>(), dao.allTracks())
        assertEquals(emptyList<TrackPointEntity>(), dao.trackPointsNow(trackId))
        assertEquals(emptyList<TrackPhotoEntity>(), dao.allTrackPhotos())
        assertFalse("the photograph's file goes with its row", picture.exists())
        assertEquals(listOf(routeTombstoneKey(STARTED)), dao.allTombstones().map(JournalTombstone::recordKey))
    }

    private companion object {
        const val INSTALL = "walk-photo-test"
        const val STARTED = 1_791_187_200_000L
        const val TAKEN = 1_791_188_000_000L
    }
}
