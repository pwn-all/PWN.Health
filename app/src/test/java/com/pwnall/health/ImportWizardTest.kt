package com.pwnall.health

import androidx.health.connect.client.permission.HealthPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

/**
 * The import page decides five things away from the screen — what access it has, whether
 * "Import now" can do anything, what to call a kind it counts, whether a picked file may start, and
 * how to say why one failed — and each is a plain function.
 */
class ImportWizardTest {
    private val history = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    private val readSteps = "android.permission.health.READ_STEPS"
    private val writeSteps = "android.permission.health.WRITE_STEPS"
    private val readHeartRate = "android.permission.health.READ_HEART_RATE"
    private val requested = setOf(readSteps, writeSteps, history)

    /**
     * Health Connect answers with every grant the app holds, including ones an earlier build asked
     * for and this one does not; counting those would report more allowed than requested.
     */
    @Test
    fun onlyRequestedGrantsAreCounted() {
        val access = importAccess(setOf(readSteps, readHeartRate), requested)
        assertEquals(1, access.allowed)
        assertEquals(3, access.requested)
        assertFalse(access.complete)
    }

    @Test
    fun theHistoryGrantIsReadOnItsOwn() {
        assertEquals(HistoryGrant.MISSING, importAccess(setOf(readSteps), requested).history)
        assertEquals(HistoryGrant.ALLOWED, importAccess(setOf(history), requested).history)
    }

    /** An older provider never defines the grant, and its owner must not be sent looking for it. */
    @Test
    fun aHealthConnectWithoutTheHistoryGrantIsNotToldItIsMissing() {
        assertEquals(HistoryGrant.UNSUPPORTED, importAccess(emptySet(), requested - history).history)
    }

    @Test
    fun everythingRequestedIsComplete() {
        assertTrue(importAccess(requested + readHeartRate, requested).complete)
    }

    @Test
    fun importWaitsForAccessAndForTheRunningExchange() {
        val some = ImportAccess(allowed = 1, requested = 3, history = HistoryGrant.MISSING)
        assertFalse(importReady(null, running = false))
        assertFalse(importReady(some.copy(allowed = 0), running = false))
        assertFalse(importReady(some, running = true))
        assertTrue(importReady(some, running = false))
    }

    /** Every kind the journal stores has a name of its own; only a kind this build has never met falls through. */
    @Test
    fun everyKnownKindHasItsOwnName() {
        val kinds = listOf(
            "MEASUREMENT",
            "EAT",
            "ACTIVITY",
            KIND_WATER,
            KIND_CAFFEINE,
            KIND_CYCLE,
            KIND_CYCLE_END,
            KIND_CYCLE_LOG,
            KIND_MASTURBATION,
            KIND_MORNING_CHECK,
            KIND_BREATHING,
            KIND_PLAN,
            KIND_NOT_DONE,
            KIND_MOOD,
            KIND_MED,
            KIND_LABS,
            KIND_ALCOHOL,
            KIND_SMOKING,
            KIND_STOOL,
            KIND_URINATION,
            KIND_WORK,
            KIND_PAIN,
            KIND_SYMPTOM,
        )
        kinds.forEach { kind ->
            assertNotEquals(kind, R.string.sources_metric_other, importedKindTitle(kind))
        }
    }

    @Test
    fun measurementsAreOneLine() {
        assertEquals(R.string.import_kind_measurements, importedKindTitle("MEASUREMENT"))
    }

    @Test
    fun anUnknownKindCountsAsOtherRecords() {
        assertEquals(R.string.sources_metric_other, importedKindTitle("SOMETHING_NEW"))
    }

    /** A locked or half-downloaded archive is said in the reader's language, not in the library's English. */
    @Test
    fun aFailedFileImportIsSaidInItsOwnSentence() {
        val locked = ZipException("encrypted ZIP entry not supported")
        val halfDownloaded = EOFException("Unexpected end of ZLIB input stream")

        assertEquals(R.string.file_import_damaged, fileImportFailureRes(locked))
        assertEquals(R.string.file_import_damaged, fileImportFailureRes(halfDownloaded))
        assertEquals(R.string.file_import_unopenable, fileImportFailureRes(FileNotFoundException("content://x")))
        assertEquals(R.string.file_import_out_of_memory, fileImportFailureRes(OutOfMemoryError()))
        assertEquals(R.string.file_import_error, fileImportFailureRes(IOException("ENOSPC")))
    }

    /** The second of two picked files does not start beside the first, whose end would clear its flag. */
    @Test
    fun aSecondFileImportIsNotStartedWhileOneRuns() {
        try {
            assertTrue(claimFileImport())
            assertFalse(claimFileImport())
            fileImportRunning.value = false
            assertTrue(claimFileImport())
        } finally {
            fileImportRunning.value = false
        }
    }
}
