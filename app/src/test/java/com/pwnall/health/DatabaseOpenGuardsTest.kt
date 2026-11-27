package com.pwnall.health

import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import androidx.core.content.edit
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyStoreException
import java.security.ProviderException

/**
 * What stands between a start that went wrong and the person's record or their screen: the open
 * helper under the quarantine, and the words under the encryption switch. Both need Android's own
 * classes, which is why they are here and not in DatabaseEncryptionTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DatabaseOpenGuardsTest {
    private val context = RuntimeEnvironment.getApplication()

    /**
     * A helper allowed to lose data on recovery deletes the file itself when an open fails, past
     * onCorruption and so past the quarantine. The wrapper never hands one that permission, whatever
     * it was asked for.
     */
    @Test
    fun theHelperUnderTheQuarantineIsNeverAllowedToLoseData() {
        var handed: SupportSQLiteOpenHelper.Configuration? = null
        val base = object : SupportSQLiteOpenHelper.Factory {
            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                handed = configuration
                return FrameworkSQLiteOpenHelperFactory().create(configuration)
            }
        }
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val asked = SupportSQLiteOpenHelper.Configuration(context, "guarded.db", callback, false, true)

        quarantineCorruptDatabase(base).create(asked).close()

        assertEquals(false, handed?.allowDataLossOnRecovery)
        assertEquals("guarded.db", handed?.name)
    }

    /**
     * The line under the switch is chosen by what failed. The exception's own message is English on
     * every phone and carries the path of a file as often as not, so it is never printed as it comes.
     */
    @Test
    fun aFailureIsWordedByWhatFailedAndNotByItsMessage() {
        assertEquals(EncryptionFailure.KEYSTORE, encryptionFailureOf(KeystoreUnavailableException(ProviderException())))
        assertEquals(EncryptionFailure.KEYSTORE, encryptionFailureOf(ProviderException("Keystore operation failed")))
        assertEquals(EncryptionFailure.KEYSTORE, encryptionFailureOf(KeyStoreException("no such alias")))
        assertEquals(EncryptionFailure.STORAGE, encryptionFailureOf(SQLiteFullException("database or disk is full")))
        assertEquals(EncryptionFailure.FILES, encryptionFailureOf(SQLiteDiskIOException("disk I/O error")))
        assertEquals(EncryptionFailure.FILES, encryptionFailureOf(IllegalStateException("integrity check of the copy")))
    }

    /** What is kept is the failure's name, and the line is its clause in the language on screen. */
    @Test
    fun theLineUnderTheSwitchIsAClauseOfTheAppsOwnAndNeverStoredText() {
        dbPrefs(context).edit(commit = true) { putString("lastError", EncryptionFailure.STORAGE.name) }
        assertEquals(context.getString(R.string.db_failure_storage), DatabaseEncryption.lastError(context))

        dbPrefs(context).edit(commit = true) {
            putString("lastError", "unable to open database file: /data/user/0/com.pwnall.health/databases/steps.db")
        }
        assertNull(DatabaseEncryption.lastError(context))
    }
}
