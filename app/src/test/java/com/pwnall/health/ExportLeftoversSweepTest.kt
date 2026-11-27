package com.pwnall.health

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * [sweepExportLeftovers] run against a real cache folder. A shared report or route left there is a
 * copy of the record nobody is sending any more, and goes; a `backup…` file goes only once it is
 * an hour old, because a younger one is the staged copy of an import still waiting for its
 * password, or the spool of an export still being written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ExportLeftoversSweepTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun cached(path: String): File =
        File(context.cacheDir, path).apply {
            parentFile?.mkdirs()
            writeText("record")
        }

    @Test
    fun sharedCopiesGoAndOnlyAnHourOldBackupGoesWithThem() {
        val report = cached("reports/PWN-Health-report.pdf")
        val archive = cached("reports/PWN-Health-report.zip")
        val route = cached("$ROUTE_SHARE_DIR/walk/route.gpx")
        val routine = cached("$ROUTINE_SHARE_DIR/routine.json")
        val lost = cached("backup-lost.json")
        // The epoch, so the hour is long past whatever the clock says when the sweep reads it.
        assertTrue(lost.setLastModified(0L))
        val waiting = cached("backup-waiting.json")
        val unrelated = cached("other.txt")

        sweepExportLeftovers(context)

        listOf(report, archive, route, routine, lost).forEach { assertFalse("${it.name} stayed", it.exists()) }
        assertTrue("an import waiting for its password lost its copy", waiting.exists())
        assertTrue("a file the sweep has no word for was taken", unrelated.exists())
    }

    /**
     * What another window of the app is still working on stays: a walk's camera file younger than
     * an hour, and an imported history's copy while the import runs. Once that is over, they go.
     */
    @Test
    fun whatIsStillInFlightStaysUntilItIsNot() {
        // Where the camera app was handed it: the cache's folder the provider serves.
        val lostShot = cached("$SHARED_CACHE_DIR/${WALK_CAPTURE_PREFIX}1.jpg")
        assertTrue(lostShot.setLastModified(0L))
        val shot = cached("$SHARED_CACHE_DIR/${WALK_CAPTURE_PREFIX}2.jpg")
        val copy = cached("$IMPORT_COPY_PREFIX-history.json")

        fileImportRunning.value = true
        try {
            sweepExportLeftovers(context)
        } finally {
            fileImportRunning.value = false
        }

        assertFalse("a shot an hour old stayed", lostShot.exists())
        assertTrue("a shot still being taken was taken", shot.exists())
        assertTrue("an import still running lost its copy", copy.exists())

        sweepExportLeftovers(context)
        assertFalse("the copy outlived its import", copy.exists())
    }
}
