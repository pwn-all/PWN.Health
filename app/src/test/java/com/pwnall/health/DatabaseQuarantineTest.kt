package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * What happens to a person's health record when SQLite reports the file damaged.
 *
 * Room's own answer is to delete it, and there is no second copy to fall back on. This is the step
 * that runs first, so the app still recovers into an empty database while the damaged one survives
 * on disk to be read with `sqlite3 .recover`.
 */
class DatabaseQuarantineTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theDamagedDatabaseAndItsWriteAheadLogAreKept() {
        val db = folder.newFile("steps.db").apply { writeText("rows") }
        val wal = folder.newFile("steps.db-wal").apply { writeText("writes not yet merged") }
        val shm = folder.newFile("steps.db-shm")

        quarantineDatabaseFiles(db.path)

        listOf(db, wal, shm).forEach { assertFalse(it.path, it.exists()) }
        assertEquals("rows", folder.root.resolve("steps.db.corrupt").readText())
        assertEquals("writes not yet merged", folder.root.resolve("steps.db.corrupt-wal").readText())
        assertTrue(folder.root.resolve("steps.db.corrupt-shm").exists())
    }

    /** A database without a `-wal` beside it is the ordinary case, not a reason to fail. */
    @Test
    fun aDatabaseOnItsOwnIsKeptOnItsOwn() {
        val db = folder.newFile("steps.db").apply { writeText("rows") }

        quarantineDatabaseFiles(db.path)

        assertEquals("rows", folder.root.resolve("steps.db.corrupt").readText())
        assertFalse(folder.root.resolve("steps.db.corrupt-wal").exists())
    }

    /**
     * On a disk that is failing, the fresh database is damaged in its turn a day later, and it is
     * all but empty. Renamed over the first copy it would take the record with it; it is numbered
     * beside it, and the profile's card offers both for saving or deleting.
     */
    @Test
    fun aSecondCorruptionIsKeptBesideTheFirst() {
        val db = folder.root.resolve("steps.db")
        folder.newFile("steps.db").writeText("first")
        assertTrue(quarantineDatabaseFiles(db.path))
        folder.newFile("steps.db").writeText("second")

        assertTrue(quarantineDatabaseFiles(db.path))

        assertEquals("first", folder.root.resolve("steps.db.corrupt").readText())
        assertEquals("second", folder.root.resolve("steps.db.corrupt.2").readText())
        assertEquals(SetAside.DAMAGED, setAsideKind(db))
        assertEquals(
            setOf("steps.db.corrupt", "steps.db.corrupt.2"),
            setAsideCopies(db, SetAside.DAMAGED).map { it.name }.toSet(),
        )
    }

    /**
     * Either way a file comes to be quarantined — SQLite reporting a corrupt page, or SQLCipher
     * refusing the first one as no database — the original a conversion of this start kept is the
     * only whole copy, and it goes beside the damaged one, where the fresh database's first open does
     * not sweep it.
     */
    @Test
    fun theOriginalOfAConversionGoesWithTheDamagedCopy() {
        val db = folder.newFile("steps.db").apply { writeText("ciphertext with a bad first page") }
        folder.newFile("steps.db.pre-encrypt.bak").writeText("the only plaintext")

        assertTrue(quarantineDatabaseFiles(db.path))
        DatabaseEncryption.opened(db.path)

        assertEquals("the only plaintext", folder.root.resolve("steps.db.corrupt.pre-encrypt.bak").readText())
        assertFalse(folder.root.resolve("steps.db.pre-encrypt.bak").exists())
    }

    /**
     * A file that would not move is still under its name, and the answer says so: what Room does
     * after a quarantine is delete what stands there, and the callers do not go on to that. A name
     * the file system has no room for is the one rename a test can count on to fail.
     */
    @Test
    fun aFileThatWillNotMoveIsReportedAndLeftWhereItIs() {
        val db = folder.newFile("d".repeat(250)).apply { writeText("rows") }

        assertFalse(quarantineDatabaseFiles(db.path))

        assertEquals("rows", db.readText())
    }

    /** What "Save a copy" writes: every piece under its own name, since a log is read only beside its database. */
    @Test
    fun theDamagedCopyIsSavedOutWithItsLog() {
        val db = folder.newFile("steps.db.corrupt").apply { writeText("rows") }
        val wal = folder.newFile("steps.db.corrupt-wal").apply { writeText("writes not yet merged") }
        val saved = ByteArrayOutputStream()

        zipFiles(listOf(db, wal), saved)

        val entries = ZipInputStream(saved.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.associate { it.name to zip.readBytes().decodeToString() }
        }
        assertEquals(mapOf("steps.db.corrupt" to "rows", "steps.db.corrupt-wal" to "writes not yet merged"), entries)
    }
}
