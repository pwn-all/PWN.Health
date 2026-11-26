package com.pwnall.health

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
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
import java.io.OutputStream
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlin.random.Random

/**
 * A restore on a real database, for what a merge test on lists cannot see: a picture whose row
 * stayed while its file went, a day that is not one, a profile nobody has, the parts a killed import
 * left behind, and the lock an export holds while it writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class BackupRestoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var db: StepsDb
    private lateinit var pictures: File

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
        pictures = File(context.cacheDir, "backup-restore-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun close() {
        db.close()
        pictures.deleteRecursively()
        progressPhotoDir(context).deleteRecursively()
    }

    private suspend fun exported(): ByteArray = ByteArrayOutputStream().also {
        LocalBackup.export(db, it, pictures, null, null, INSTALL)
    }.toByteArray()

    private suspend fun picture(bytes: ByteArray) {
        File(pictures, "$TAKEN.jpg").writeBytes(bytes)
        db.dao().saveProgressPhoto(ProgressPhoto(day = DAY, takenAt = TAKEN, file = "$TAKEN.jpg"))
    }

    /**
     * The row stays and the file does not — a phone moved before the folder travelled with it. Taken
     * for the picture already here, the row would keep the restore from ever bringing the file back.
     */
    @Test
    fun aProgressPhotographWhoseFileWentTakesItBackUnderItsOwnRow() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 0xFF.toByte(), 0xD9.toByte())
        picture(jpeg)
        val bytes = exported()
        File(pictures, "$TAKEN.jpg").delete()

        LocalBackup.import(db, bytes.inputStream(), pictures, null, null, INSTALL)

        assertEquals(1, db.dao().allProgressPhotos().size)
        assertArrayEquals(jpeg, File(pictures, "$TAKEN.jpg").readBytes())
        assertEquals(listOf("$TAKEN.jpg"), pictures.list().orEmpty().toList())
    }

    /** A day this app never writes is a damaged file: refused whole, and nothing of it stays. */
    @Test
    fun aStepDayThatIsNotADayRefusesTheFileAndWritesNothing() = runBlocking {
        // Spelled out, so that `schema` stands first as in every file the app writes.
        val file = """{"schema":11,"days":[{"day":"$DAY","steps":4000},{"day":"yesterday","steps":9000}]}"""
            .toByteArray()

        val failure = runCatching { LocalBackup.import(db, file.inputStream(), null, null, null, INSTALL) }
            .exceptionOrNull()

        assertTrue("refused with $failure", failure is DateTimeParseException)
        assertEquals(R.string.profile_import_damaged, importFailureRes(checkNotNull(failure)))
        assertEquals(emptyList<DaySteps>(), db.dao().allLocalDays())
    }

    /** A profile replaces the one here outright, so one no person has is refused rather than taken. */
    @Test
    fun aProfileWithAWeightNobodyHasIsRefusedAndTheOneHereStays() = runBlocking {
        val mine = UserProfile(sex = "FEMALE", weightKg = 64.0, heightCm = 168, birthDate = "1990-05-01")
        db.dao().saveProfile(mine)
        val file = """{"schema":11,"profile":{"sex":"FEMALE","weightKg":0,"heightCm":168,"birthDate":"1990-05-01"}}"""

        val failure = runCatching { LocalBackup.importProfile(db, file.byteInputStream()) }.exceptionOrNull()

        assertEquals("weightKg 0.0", failure?.message)
        assertEquals(R.string.profile_import_damaged, importFailureRes(checkNotNull(failure)))
        assertEquals(mine, db.dao().profileNow())
    }

    /**
     * A part is a picture a killed import never named: no row shows it, and only the next import
     * would sweep it. A fresh start takes the hour-old ones, and leaves a young one and every picture.
     */
    @Test
    fun aFreshStartSweepsTheRestoresPartsOnceAnHourOld() {
        val now = 1_800_000_000_000L
        val dir = progressPhotoDir(context).apply { mkdirs() }
        fun file(name: String, age: Long) = File(dir, name).apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - age)
        }
        val lost = file("1.jpg$PART_SUFFIX", 2 * HOUR)
        val parked = file("2.jpg$PART_SUFFIX", HOUR / 12)
        val kept = file("3.jpg", 2 * HOUR)

        sweepRestoreParts(context, now)

        assertFalse(lost.exists())
        assertTrue(parked.exists())
        assertTrue(kept.exists())
    }

    /**
     * The transaction is Room's write lock, and the pedometer's inserts and a route's fixes wait on
     * it. The rows are read under it; the pictures' bytes are files, and are written after it.
     */
    @Test
    fun theExportWritesThePicturesOutsideItsTransaction() = runBlocking {
        val jpeg = ByteArray(PICTURE_BYTES) { it.toByte() }
        picture(jpeg)
        var locked = 0L
        val sink = object : OutputStream() {
            override fun write(b: Int) {
                if (db.inTransaction()) locked += 1
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (db.inTransaction()) locked += len
            }
        }

        LocalBackup.export(db, sink, pictures, null, null, INSTALL)

        assertTrue("$locked bytes written under the lock", locked < jpeg.size)
    }

    /**
     * A `labCaptures` member somebody else wrote, through a real restore. Only a name the camera
     * gives lands, inside the sheets' folder; a sheet already here under its name is the same sheet
     * and is left as it is; a name that reads as a way out of the folder writes nothing anywhere.
     */
    @Test
    fun aRestoreWritesOnlyTheSheetsTheCameraCouldHaveNamedAndOnlyInsideTheirFolder() = runBlocking {
        val labs = File(pictures, "lab-captures").apply { mkdirs() }
        val mine = byteArrayOf(1, 2, 3)
        File(labs, "lab-1.jpg").writeBytes(mine)
        val sheet = byteArrayOf(9, 8, 7)
        val jpeg = Base64.getEncoder().encodeToString(sheet)
        val names = listOf("x?/a", "x?/../../planted", "a#/b", ".hidden", "planted.html", "lab-1.jpg", "lab-9.jpg")
        val rows = names.joinToString(",") { """{"file":"$it","jpeg":"$jpeg"}""" }
        val file = """{"schema":11,"labCaptures":[$rows]}"""

        val summary = LocalBackup.import(db, file.byteInputStream(), null, null, null, INSTALL, labCaptures = labs)

        assertEquals(listOf("lab-captures"), pictures.list().orEmpty().toList())
        assertEquals(listOf("lab-1.jpg", "lab-9.jpg"), labs.list().orEmpty().sorted())
        assertFalse(File(context.cacheDir, "planted").exists())
        assertArrayEquals(mine, File(labs, "lab-1.jpg").readBytes())
        assertArrayEquals(sheet, File(labs, "lab-9.jpg").readBytes())
        assertEquals(0, summary.photosLost)
    }

    /**
     * A sealed file through the whole import. A wrong password, and a file cut off after the first of
     * its pictures had already been parked, are each refused in their own words, and leave neither a
     * row nor a picture behind; the same file whole, with its password, then restores everything.
     */
    @Test
    fun aSealedFileThatFailsPartWayRestoresNothingAndLeavesNoPictureBehind() = runBlocking {
        // The small picture is the earlier one, so it is parked before the large one is cut off.
        val small = ByteArray(1_000) { 1 }
        val large = Random(7).nextBytes(3 * BackupCrypto.CHUNK_BYTES)
        picture(small)
        File(pictures, "${TAKEN + 1}.jpg").writeBytes(large)
        db.dao().saveProgressPhoto(ProgressPhoto(day = DAY, takenAt = TAKEN + 1, file = "${TAKEN + 1}.jpg"))
        db.dao().insertJournalEntries(
            listOf(JournalEntry(day = DAY, occurredAt = TAKEN, kind = KIND_WATER, title = "Вода", calories = 0.0)),
        )
        val sealed = ByteArrayOutputStream().also {
            LocalBackup.export(db, BackupCrypto.encrypting(PASSWORD.toCharArray(), it), pictures, null, null, INSTALL)
        }.toByteArray()
        val target = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
        val restored = File(context.cacheDir, "backup-restore-target").apply {
            deleteRecursively()
            mkdirs()
        }
        suspend fun restore(bytes: ByteArray, password: String) = LocalBackup.import(
            target,
            BackupCrypto.decrypting(password.toCharArray(), bytes.inputStream()),
            restored,
            null,
            null,
            "another-install",
        )

        try {
            val wrong = runCatching { restore(sealed, "not the password") }.exceptionOrNull()
            val cut = runCatching { restore(sealed.copyOf(sealed.size / 2), PASSWORD) }.exceptionOrNull()

            assertTrue("refused with $wrong", wrong is WrongBackupPasswordException)
            assertTrue("refused with $cut", cut is TruncatedBackupException)
            assertEquals(emptyList<JournalEntry>(), target.dao().allJournal())
            assertEquals(emptyList<ProgressPhoto>(), target.dao().allProgressPhotos())
            assertEquals(emptyList<String>(), restored.list().orEmpty().toList())

            restore(sealed, PASSWORD)

            assertEquals(1, target.dao().allJournal().size)
            assertEquals(2, target.dao().allProgressPhotos().size)
            assertArrayEquals(large, File(restored, "${TAKEN + 1}.jpg").readBytes())
        } finally {
            target.close()
            restored.deleteRecursively()
        }
    }

    @Test
    fun encryptedProfileIsSavedOnlyAfterTheLastChunkIsAuthenticated() = runBlocking {
        val mine = UserProfile(sex = "FEMALE", weightKg = 64.0, heightCm = 168, birthDate = "1990-05-01")
        db.dao().saveProfile(mine)
        val prefix = """{"schema":11,"profile":{"sex":"FEMALE","weightKg":62,"heightCm":168},"padding":""""
        val sealed = seal(prefix + "a".repeat(BackupCrypto.CHUNK_BYTES) + "\"}")
        val truncated = sealed.copyOf(sealed.size - 20)
        val failure = runCatching {
            LocalBackup.importProfile(db, BackupCrypto.decrypting(PASSWORD.toCharArray(), truncated.inputStream()))
        }.exceptionOrNull()

        assertTrue("refused with $failure", failure is TruncatedBackupException)
        assertEquals(mine, db.dao().profileNow())
        assertTrue(LocalBackup.importProfile(db, BackupCrypto.decrypting(PASSWORD.toCharArray(), sealed.inputStream())))
        assertEquals(62.0, db.dao().profileNow()?.weightKg ?: 0.0, 0.0)
    }

    @Test
    fun aBackupEndingOnAChunkBoundaryRollsBackWithoutTheFinalTag() = runBlocking {
        val prefix = """{"schema":11,"days":[{"day":"$DAY","steps":4300}],"padding":""""
        val json = prefix + "a".repeat(BackupCrypto.CHUNK_BYTES - prefix.length - 2) + "\"}"
        val sealed = seal(json)
        val truncated = sealed.copyOf(sealed.size - 20)
        val failure = runCatching {
            val input = BackupCrypto.decrypting(PASSWORD.toCharArray(), truncated.inputStream())
            LocalBackup.import(db, input, null, null, null, INSTALL)
        }.exceptionOrNull()

        assertTrue("refused with $failure", failure is TruncatedBackupException)
        assertEquals(emptyList<DaySteps>(), db.dao().allLocalDays())
        val complete = BackupCrypto.decrypting(PASSWORD.toCharArray(), sealed.inputStream())
        LocalBackup.import(db, complete, null, null, null, INSTALL)
        assertEquals(4300L, db.dao().stepsOn(DAY))
    }

    private fun seal(json: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        BackupCrypto.encrypting(PASSWORD.toCharArray(), bytes).use { it.write(json.toByteArray()) }
    }.toByteArray()

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val INSTALL = "backup-restore-test"
        const val DAY = "2026-11-18"
        const val TAKEN = 1_795_000_000_000L
        const val HOUR = 60 * 60 * 1000L
        const val PICTURE_BYTES = 256 * 1024
    }
}
