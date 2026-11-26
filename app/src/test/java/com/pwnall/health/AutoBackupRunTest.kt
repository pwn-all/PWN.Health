package com.pwnall.health

import android.content.Context
import androidx.core.content.edit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a nightly run leaves where the settings row and the profile's front page read it. The worker
 * records every run this way and returns success whatever happened, so this is all of what a failed
 * night says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class AutoBackupRunTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun clear() {
        backupPrefs(context).edit(commit = true) { clear() }
    }

    /** A failed night is said under the switch, and the last copy that did get made keeps its time. */
    @Test
    fun aFailedRunIsWrittenDownAndTheLastGoodCopyKeepsItsTime() {
        AutoBackup.recordRun(context, null)
        val copied = AutoBackup.state(context).lastCopyMs

        AutoBackup.recordRun(context, "The folder is gone")

        assertNotNull(copied)
        assertEquals("The folder is gone", AutoBackup.state(context).error)
        assertEquals(copied, AutoBackup.state(context).lastCopyMs)
    }

    /** The next good night takes the complaint away. */
    @Test
    fun aRunThatWorkedClearsTheFailureBeforeIt() {
        AutoBackup.recordRun(context, "disk full")

        AutoBackup.recordRun(context, null)

        assertNull(AutoBackup.state(context).error)
        assertNotNull(AutoBackup.state(context).lastCopyMs)
    }
}
