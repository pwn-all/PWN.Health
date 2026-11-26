package com.pwnall.health

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The switch that rewrites the whole record, on a phone.
 *
 * DatabaseEncryptionTest and DatabaseKeyTest hold every decision around the file on the JVM. What
 * they cannot reach is the conversion itself and the two things it stands on, SQLCipher's native
 * library and the Keystore: the code that copies the only record there is under another key and
 * then moves the original aside. Here a database with rows in it is encrypted and decrypted
 * through [DatabaseEncryption.reconcile] and opened through the app's own [StepsDb.builder] each
 * time, and what `sqlcipher_export` is taken on trust to carry is read back: every table and
 * index, every row count, the version stamp it does not carry and the code copies by hand, and
 * `sqlite_sequence`, which decides the id of the next journal row and so what a tombstone
 * `journal:<id>` in an older backup names.
 *
 * A file of its own, never `steps.db`. The switch and the wrapped key are the app's, there being
 * one of each: what the device held is put back afterwards, and a key it already has is used as it
 * is. The last test tells the backups that this installation started over, as a quarantine does.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file get() = context.getDatabasePath(NAME)
    private var kept: Map<String, Any?> = emptyMap()

    @Before
    fun startFromAPlainSwitchAndNoFile() {
        kept = dbPrefs(context).all.toMap()
        setSwitch(EncryptionState.OFF)
        removeFiles()
    }

    @After
    fun putTheSwitchBack() {
        removeFiles()
        dbPrefs(context).edit(commit = true) {
            clear()
            kept.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                }
            }
        }
    }

    @Test
    fun turningTheSwitchOnAndOffAgainKeepsTheRecordWhole() = runBlocking {
        val plain = StepsDb.builder(context, NAME).build()
        val ids = (1..3).map { plain.saveJournalEvent(meal(it)) }
        // The newest row deleted: the sequence is now ahead of the largest id left in the table.
        plain.deleteJournalEvent(ids.last())
        plain.dao().saveChat(ChatRow("0f8c2c1e", DAY, 1L, 2L, "{}"))
        val written = shapeOf(plain)
        plain.close()
        assertEquals(FileHeader.PLAIN, fileHeader(file))

        DatabaseEncryption.request(context, wanted = true)
        val passphrase = DatabaseEncryption.reconcile(context, file)
        assertNotNull(DatabaseEncryption.lastError(context), passphrase)
        assertEquals(EncryptionState.ON, DatabaseEncryption.state(context))
        assertEquals(FileHeader.CIPHERED, fileHeader(file))
        assertTrue("the plain original is kept until the copy has opened", File(file.path + PRE_ENCRYPT).exists())
        val ciphered = StepsDb.builder(context, NAME, passphrase).build()
        assertEquals(written, shapeOf(ciphered))
        assertFalse("the plain original outlived the open", File(file.path + PRE_ENCRYPT).exists())
        // The next row takes the id after the deleted one, as it would have in the plain file.
        assertEquals(ids.last() + 1, ciphered.saveJournalEvent(meal(4)))
        val added = shapeOf(ciphered)
        ciphered.close()

        DatabaseEncryption.request(context, wanted = false)
        assertNull(DatabaseEncryption.reconcile(context, file))
        assertNull(DatabaseEncryption.lastError(context))
        assertEquals(EncryptionState.OFF, DatabaseEncryption.state(context))
        assertEquals(FileHeader.PLAIN, fileHeader(file))
        val back = StepsDb.builder(context, NAME).build()
        assertEquals(added, shapeOf(back))
        back.close()
    }

    /** The key is made once: every later start unwraps the one the first start made. */
    @Test
    fun theKeystoreHandsBackTheKeyItWrapped() {
        val made = DatabaseKey.ensurePassphrase(context)
        // x'…', with the 64 hex digits of 32 bytes between: the raw key SQLCipher takes as it is.
        assertEquals(RAW_KEY_LENGTH, made.size)
        assertTrue(DatabaseKey.exists(context))
        assertArrayEquals(made, DatabaseKey.passphraseOrNull(context))
        assertArrayEquals(made, DatabaseKey.ensurePassphrase(context))
    }

    /**
     * Asked to decrypt a file that this phone's key does not open, the conversion leaves it exactly
     * as it was and withdraws the wish: nothing is opened under the cipher, and the plain open that
     * follows is the one that sets the file aside.
     */
    @Test
    fun aFileUnderAnotherKeyIsNotConvertedAndNotTouched() = runBlocking {
        val bytes = writeUnderAnotherKey()
        DatabaseKey.ensurePassphrase(context)
        setSwitch(EncryptionState.PENDING_OFF)

        assertNull(DatabaseEncryption.reconcile(context, file))

        assertEquals(EncryptionState.OFF, DatabaseEncryption.state(context))
        assertNotNull(DatabaseEncryption.lastError(context))
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(File(file.path + ".converting").exists())
    }

    /**
     * The same file under a switch that is on: Room is handed this phone's key, SQLCipher refuses
     * the first page, and the file goes aside whole as `.corrupt` with a fresh database opened in
     * its place, where the library's own handling would have thrown at every start.
     */
    @Test
    fun aFileTheKeyDoesNotOpenIsSetAsideWholeAndAFreshOneOpened() = runBlocking {
        val bytes = writeUnderAnotherKey()
        DatabaseKey.ensurePassphrase(context)
        setSwitch(EncryptionState.ON)
        val passphrase = DatabaseEncryption.reconcile(context, file)
        assertNotNull(passphrase)

        val fresh = StepsDb.builder(context, NAME, passphrase).build()
        assertEquals(emptyList<JournalEntry>(), fresh.dao().journalOnNow(DAY))
        fresh.close()

        assertArrayEquals(bytes, File(file.path + SetAside.DAMAGED.suffix).readBytes())
        assertEquals(FileHeader.CIPHERED, fileHeader(file))
    }

    private fun meal(number: Int) = JournalEntry(
        day = DAY,
        occurredAt = 1_790_000_000_000L + number,
        kind = "EAT",
        title = "Meal $number",
        calories = 100.0 * number,
    )

    /** A database with one row in it under a key that is not this phone's; its bytes. */
    private suspend fun writeUnderAnotherKey(): ByteArray {
        val other = StepsDb.builder(context, NAME, rawKeyPassphrase(ByteArray(KEY_BYTES) { it.toByte() })).build()
        other.saveJournalEvent(meal(1))
        other.close()
        assertEquals(FileHeader.CIPHERED, fileHeader(file))
        return file.readBytes()
    }

    /**
     * Everything a conversion has to carry, read through whichever helper the file is open under:
     * the version, the tables and indexes by name, the rows of every table by count, the sequences,
     * the identity Room checks at each open, and the journal and the chat as the DAO reads them.
     */
    private suspend fun shapeOf(db: StepsDb): List<Any?> {
        val sqlite = db.openHelper.writableDatabase
        val objects = sqlite.rows(
            "SELECT `type`, `name` FROM `sqlite_master` WHERE `name` NOT LIKE 'sqlite_%' " +
                "AND `name` != 'android_metadata' ORDER BY `type`, `name`",
        ) { it.getString(0) to it.getString(1) }
        val counts = objects.filter { it.first == "table" }.map { (_, table) ->
            table to sqlite.rows("SELECT count(*) FROM `$table`") { it.getLong(0) }.single()
        }
        val sequences = sqlite.rows("SELECT `name`, `seq` FROM `sqlite_sequence` ORDER BY `name`") {
            it.getString(0) to it.getLong(1)
        }
        return listOf(
            sqlite.version,
            objects,
            counts,
            sequences,
            sqlite.rows("SELECT `identity_hash` FROM `room_master_table`") { it.getString(0) },
            db.dao().journalOnNow(DAY),
            db.dao().chat("0f8c2c1e"),
        )
    }

    /** The switch as the preferences keep it, under the name [DatabaseEncryption] reads it by. */
    private fun setSwitch(state: EncryptionState) = dbPrefs(context).edit(commit = true) {
        putString("encryption", state.name)
        remove("lastError")
        remove("returnOwnCopy")
    }

    /** The test's file under every name a conversion or a quarantine gives it, logs included. */
    private fun removeFiles() {
        file.parentFile?.listFiles { found -> found.name.startsWith(NAME) }.orEmpty().forEach { it.delete() }
    }

    private companion object {
        const val NAME = "encryption-test.db"
        const val DAY = "2026-10-03"
        const val PRE_ENCRYPT = ".pre-encrypt.bak"
        const val KEY_BYTES = 32
        const val RAW_KEY_LENGTH = 2 + 2 * KEY_BYTES + 1
    }
}
