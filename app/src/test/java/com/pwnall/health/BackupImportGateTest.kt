package com.pwnall.health

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One backup import at a time. A second one begun beside the first would sweep the first one's
 * parked photographs and park them again: both would report pictures lost that were restored, and
 * some would come back twice. The buttons go grey on [backupImportRunning], and [asOnlyBackupImport] is what holds
 * when a tap or a picker gets past them.
 */
class BackupImportGateTest {
    @Test
    fun anImportBegunBesideARunningOneIsLeftUndone() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()
        // Undispatched, so the first is inside its import by the time the second is asked for.
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            asOnlyBackupImport {
                ran += "first"
                release.await()
            }
        }
        assertTrue("the doors are shut while it runs", backupImportRunning.value)

        asOnlyBackupImport { ran += "second" }
        assertEquals(listOf("first"), ran)

        release.complete(Unit)
        first.join()
        assertFalse("and open again once it is over", backupImportRunning.value)
        asOnlyBackupImport { ran += "third" }
        assertEquals(listOf("first", "third"), ran)
    }

    /**
     * A wrong password is said on its field while the staged file waits for another try. A page
     * turned on its side under the import takes the file with it, and a "wrong password" kept for
     * its field would be said nowhere: that one is reported like any other failure.
     */
    @Test
    fun aWrongPasswordIsKeptForAnotherTryOnlyWhileTheFileIsStillThere() {
        assertTrue(keepsLockedImport(WrongBackupPasswordException(), stagedStillThere = true))
        assertFalse(keepsLockedImport(WrongBackupPasswordException(), stagedStillThere = false))
        assertFalse(keepsLockedImport(TruncatedBackupException(), stagedStillThere = true))
        assertFalse(keepsLockedImport(null, stagedStillThere = true))
    }

    /** A damaged file, a full disk: the import after a failed one is not refused for ever. */
    @Test
    fun anImportThatFailsLeavesTheDoorsOpen() = runBlocking {
        val failed = runCatching { asOnlyBackupImport { error("a damaged file") } }

        assertTrue(failed.isFailure)
        assertFalse(backupImportRunning.value)
    }
}
