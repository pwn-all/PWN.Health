package com.pwnall.health

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * [DatabaseEncryption.reconcile] over a ciphered file whose Keystore does not answer — which is what
 * Robolectric's is: it has no AndroidKeyStore provider, so every ask throws, as a phone's does in the
 * first seconds after boot. [StepsDb.get] keeps nothing of an open that threw, so every caller in the
 * process asks again; what is counted against the record is the start, not the ask.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class KeystoreRefusalStartTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val file: File = context.getDatabasePath(DATABASE_FILE)
    private val record = ByteArray(4_096) { (it * 31 + 7).toByte() }

    /** A ciphered record with a wrapped key beside it and the switch on, as a phone that encrypted it has. */
    @Before
    fun anEncryptedRecord() {
        aNewProcess()
        file.parentFile?.mkdirs()
        file.writeBytes(record)
        dbPrefs(context).edit(commit = true) {
            putString("encryption", EncryptionState.ON.name)
            putString("wrappedKey", packWrapped(ByteArray(12), ByteArray(48)))
        }
    }

    /** The flag a process keeps of the refusal it has counted starts out down in a process of its own. */
    private fun aNewProcess() = DatabaseEncryption::class.java.getDeclaredField("refusalCounted")
        .apply { isAccessible = true }
        .setBoolean(null, false)

    private fun refusals(): Int = dbPrefs(context).getInt("keystoreRefusals", 0)

    /**
     * The activity's onCreate, its onResume and a widget, all in one launch: each is refused and the
     * record stays where it is, counted as one refused start and not as the three that set it aside.
     */
    @Test
    fun threeCallersInOneProcessAreOneRefusedStart() {
        repeat(3) {
            assertThrows(KeystoreUnavailableException::class.java) { DatabaseEncryption.reconcile(context, file) }
        }

        assertEquals(1, refusals())
        assertArrayEquals(record, file.readBytes())
        assertFalse(DatabaseEncryption.lockedCopyPresent(context))
        assertNull(DatabaseEncryption.setAside(context))
        assertEquals(EncryptionState.ON, DatabaseEncryption.state(context))
    }

    /**
     * Starts of their own, each a minute after the refusal counted before it: the first two are
     * refused, and the third sets the record aside whole as this phone's own, for the profile card to
     * bring back once the Keystore answers.
     */
    @Test
    fun theThirdRefusedStartSetsTheRecordAsideAsThisPhonesOwn() {
        repeat(2) {
            aNewProcess()
            dbPrefs(context).edit(commit = true) { putLong("keystoreRefusedAt", 0L) }
            assertThrows(KeystoreUnavailableException::class.java) { DatabaseEncryption.reconcile(context, file) }
            assertArrayEquals(record, file.readBytes())
        }
        assertEquals(2, refusals())

        aNewProcess()
        dbPrefs(context).edit(commit = true) { putLong("keystoreRefusedAt", 0L) }
        assertNull(DatabaseEncryption.reconcile(context, file))

        assertFalse(file.exists())
        assertArrayEquals(record, File(file.path + SetAside.OWN.suffix).readBytes())
        assertEquals(SetAside.OWN, DatabaseEncryption.setAside(context))
        assertTrue(DatabaseEncryption.lockedCopyPresent(context))
        assertEquals(0, refusals())
    }
}
