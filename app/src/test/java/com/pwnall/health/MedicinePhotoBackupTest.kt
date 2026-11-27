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
 * A course's photograph through a real export and a real import: the bytes in the file, the name
 * they come back under, and what is left on disk when a restore goes wrong. The rules the import
 * applies are in MedicinePhotoTest; this is the check that the import applies them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class MedicinePhotoBackupTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var source: StepsDb
    private lateinit var target: StepsDb
    private lateinit var sourceDir: File
    private lateinit var targetDir: File

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private val course = Medication(name = "Metformin", startDay = "2026-01-01", createdAt = 5, photo = "100.jpg")

    @Before
    fun open() {
        source = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
        val root = File(context.cacheDir, "medicine-photo-backup-test").apply { deleteRecursively() }
        sourceDir = File(root, "source").apply { mkdirs() }
        targetDir = File(root, "target").apply { mkdirs() }
    }

    @After
    fun close() {
        source.close()
        target.close()
        File(context.cacheDir, "medicine-photo-backup-test").deleteRecursively()
    }

    private suspend fun exported(): ByteArray {
        File(sourceDir, course.photo).writeBytes(jpeg)
        source.dao().insertMedication(course)
        return ByteArrayOutputStream().also {
            LocalBackup.export(source, it, null, sourceDir, null, INSTALL)
        }.toByteArray()
    }

    private suspend fun import(into: StepsDb, bytes: ByteArray, dir: File?) {
        LocalBackup.import(into, bytes.inputStream(), null, dir, null, INSTALL)
    }

    /** Rewrites the file's first course, as a file somebody else wrote may have it. */
    private fun ByteArray.withCourse(edit: (JSONObject) -> Unit): ByteArray {
        val file = JSONObject(String(this))
        edit(file.getJSONArray("medications").getJSONObject(0))
        return file.toString().toByteArray()
    }

    private fun File.names() = list().orEmpty().sorted()

    @Test
    fun theBytesRideInTheRowAndComeBackOnAPhoneThatHadNone() = runBlocking {
        val bytes = exported()
        val row = JSONObject(String(bytes)).getJSONArray("medications").getJSONObject(0)
        assertEquals("100.jpg", row.getString("photo"))
        assertTrue("the picture itself is in the file", row.getString("jpeg").isNotEmpty())

        import(target, bytes, targetDir)

        val restored = target.dao().allMedications().single()
        assertEquals("100.jpg", restored.photo)
        assertArrayEquals(jpeg, File(targetDir, restored.photo).readBytes())
        assertEquals("no part is left beside it", listOf("100.jpg"), targetDir.names())
    }

    /** A file already under the name — another course's, or an orphan — is never overwritten. */
    @Test
    fun aNameAlreadyOnDiskMovesTheRestoredPictureToTheNextStamp() = runBlocking {
        val bytes = exported()
        val orphan = byteArrayOf(9, 9, 9)
        File(targetDir, "100.jpg").writeBytes(orphan)

        import(target, bytes, targetDir)

        val restored = target.dao().allMedications().single()
        assertEquals("101.jpg", restored.photo)
        assertArrayEquals(jpeg, File(targetDir, "101.jpg").readBytes())
        assertArrayEquals(orphan, File(targetDir, "100.jpg").readBytes())
    }

    /** The same file imported again, or into the phone it came from, adds no second picture. */
    @Test
    fun aCourseThisPhoneHasKeepsItsOwnPicture() = runBlocking {
        val bytes = exported()
        import(source, bytes, sourceDir)
        import(source, bytes, sourceDir)

        assertEquals(listOf(course.copy(id = 1)), source.dao().allMedications())
        assertEquals(listOf("100.jpg"), sourceDir.names())
    }

    /** A picture removed by mistake comes back from yesterday's backup, as a progress photograph does. */
    @Test
    fun aCourseThisPhoneHasWithoutItsPictureGetsTheBackupsBack() = runBlocking {
        val bytes = exported()
        val here = source.dao().allMedications().single()
        source.dao().saveMedication(here.copy(photo = ""))
        File(sourceDir, "100.jpg").delete()

        import(source, bytes, sourceDir)

        val restored = source.dao().allMedications().single()
        assertEquals(here.id, restored.id)
        assertEquals("100.jpg", restored.photo)
        assertArrayEquals(jpeg, File(sourceDir, "100.jpg").readBytes())
    }

    @Test
    fun aCourseWhoseFileHasGoneGetsTheBackupsPictureBack() = runBlocking {
        val bytes = exported()
        File(sourceDir, "100.jpg").delete()

        import(source, bytes, sourceDir)

        val restored = source.dao().allMedications().single()
        assertTrue(File(sourceDir, restored.photo).isFile)
        assertArrayEquals(jpeg, File(sourceDir, restored.photo).readBytes())
        assertEquals(listOf(restored.photo), sourceDir.names())
    }

    /** A name with a path in it is no name: the bytes are not written anywhere at all. */
    @Test
    fun aHostileNameWritesNothing() = runBlocking {
        val bytes = exported().withCourse { it.put("photo", "../databases/steps.db") }

        import(target, bytes, targetDir)

        assertEquals("", target.dao().allMedications().single().photo)
        assertEquals(emptyList<String>(), targetDir.names())
        assertFalse(File(targetDir.parentFile, "databases").exists())
    }

    /** Nowhere to put the picture: the course comes back without one rather than naming nothing. */
    @Test
    fun anImportWithoutADirectoryRestoresTheCourseWithoutItsPicture() = runBlocking {
        import(target, exported(), null)

        assertEquals("", target.dao().allMedications().single().photo)
    }

    /** A restore that fails after the courses rolls them back and leaves no picture on disk. */
    @Test
    fun aRollbackLeavesNoPartBehind() = runBlocking {
        val broken = JSONObject(String(exported())).apply {
            put("appointments", JSONArray().put(JSONObject()))
        }.toString().toByteArray()

        val failure = runCatching { import(target, broken, targetDir) }.exceptionOrNull()

        assertTrue("an appointment without its fields is not a restore", failure != null)

        assertEquals(emptyList<Medication>(), target.dao().allMedications())
        assertEquals(emptyList<String>(), targetDir.names())
    }

    private companion object {
        const val INSTALL = "medicine-photo-test"
    }
}
