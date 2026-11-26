package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Two things the app does on its way somewhere else. A route from a widget leaves every editor
 * behind — [editorAfterClose] — rather than shutting the record on top and landing in the chat that
 * record was opened from. And coming forward takes the hour-old shared files out of the cache —
 * [sweepStaleShares] — not only a fresh start, which can be weeks apart.
 */
class RouteLeavesEditorsTest {
    @get:Rule
    val cache = TemporaryFolder()

    private val now = 1_800_000_000_000L

    private fun shared(path: String, ageMs: Long): File = File(cache.root, path).apply {
        parentFile?.mkdirs()
        writeText("shared")
        setLastModified(now - ageMs)
    }

    @Test
    fun backFromARecordReturnsToTheChatThatOpenedIt() {
        assertEquals(7L, editorAfterClose(editing = 9L, returnTo = 7L, leaving = false))
    }

    @Test
    fun aRouteLeavesTheChatAsWell() {
        assertNull(editorAfterClose(editing = 9L, returnTo = 7L, leaving = true))
    }

    @Test
    fun anEditorOpenedFromAPageHasNothingToReturnTo() {
        assertNull(editorAfterClose(editing = 9L, returnTo = null, leaving = false))
        assertNull(editorAfterClose(editing = 9L, returnTo = 9L, leaving = false))
        assertNull(editorAfterClose(editing = null, returnTo = null, leaving = true))
    }

    @Test
    fun aShareAnHourOldIsRemovedAndOneJustMadeIsKept() {
        val oldReport = shared("reports/pwn-health-report.pdf", ageMs = IN_FLIGHT_STALE_MS + 1)
        val oldArchive = shared("reports/report-1.zip", ageMs = 3 * IN_FLIGHT_STALE_MS)
        val oldRoute = shared("$ROUTE_SHARE_DIR/12/walk.gpx", ageMs = 2 * IN_FLIGHT_STALE_MS)
        val oldRoutine = shared("$ROUTINE_SHARE_DIR/3/legs.json", ageMs = 2 * IN_FLIGHT_STALE_MS)
        val fresh = shared("reports/just-shared.pdf", ageMs = 60_000)

        sweepStaleShares(cache.root, now)

        listOf(oldReport, oldArchive, oldRoute, oldRoutine).forEach { assertFalse(it.path, it.exists()) }
        assertTrue("an app may still be reading it", fresh.exists())
    }

    @Test
    fun nothingElseInTheCacheIsTouched() {
        val medal = shared("medal-10k.png", ageMs = 30 * IN_FLIGHT_STALE_MS)
        val other = shared("images/photo.jpg", ageMs = 30 * IN_FLIGHT_STALE_MS)

        sweepStaleShares(cache.root, now)
        // A cache the system has cleared has none of the folders, and that is not a failure.
        sweepStaleShares(File(cache.root, "gone"), now)

        assertTrue(medal.exists())
        assertTrue(other.exists())
    }
}
