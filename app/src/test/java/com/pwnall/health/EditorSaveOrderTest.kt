package com.pwnall.health

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order of a confirmed save — see [writeThenMirror]. The Health Connect mirror waits for the
 * lock a whole exchange holds, and the page does not wait with it: waiting would leave the row
 * already in Room, the form still up with a dead button, and the way back taken whenever the
 * exchange ended.
 */
class EditorSaveOrderTest {
    /** The exchange is still running, and the page is already let go: only the mirror waits for it. */
    @Test
    fun thePageIsReleasedWhileTheMirrorStillWaits() = runTest {
        val exchange = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val steps = mutableListOf<String>()
        val save = launch {
            writeThenMirror(
                page = this,
                write = { steps += "write" },
                release = {
                    steps += "release"
                    released.complete(Unit)
                },
                mirror = {
                    exchange.await()
                    steps += "mirror"
                },
                failed = { steps += "failed" },
            )
        }

        released.await()
        assertEquals(listOf("write", "release"), steps)

        exchange.complete(Unit)
        save.join()
        assertEquals(listOf("write", "release", "mirror"), steps)
    }

    /**
     * Back was pressed while the row was being written. The release is the way back, and taken now
     * it would close whichever page is up instead; the row is written and mirrored all the same.
     */
    @Test
    fun aPageThatHasGoneIsNotReleasedAndItsRowIsStillMirrored() = runTest {
        val writing = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val steps = mutableListOf<String>()
        val page = CoroutineScope(coroutineContext + Job())
        val save = page.launch {
            writeThenMirror(
                page = page,
                write = {
                    writing.complete(Unit)
                    written.await()
                    steps += "write"
                },
                release = { steps += "release" },
                mirror = { steps += "mirror" },
                failed = { steps += "failed" },
            )
        }

        writing.await()
        page.cancel()
        written.complete(Unit)
        save.join()

        assertEquals(listOf("write", "mirror"), steps)
    }

    /**
     * The disk was full. Nothing was written, so the page is not let go as if it had been and there
     * is nothing to mirror: the page is told, and the save ends there rather than ending the app.
     */
    @Test
    fun aWriteThatThrowsIsToldToThePageAndNeitherReleasedNorMirrored() = runTest {
        val steps = mutableListOf<String>()

        writeThenMirror(
            page = this,
            write = { throw IllegalStateException("database or disk is full") },
            release = { steps += "release" },
            mirror = { steps += "mirror" },
            failed = { steps += "failed" },
        )

        assertEquals(listOf("failed"), steps)
    }

    /**
     * The phone was turned while the row was being written, and the write then failed. The page that
     * went is told nothing; the page that came back reads how the write ended ([editorWrite]) and says
     * it was not saved, rather than closing on its `saving` flag as if the row had gone in.
     */
    @Test
    fun howAWriteEndedOutlivesThePageThatBeganIt() = runTest {
        val writing = CompletableDeferred<Unit>()
        val refused = CompletableDeferred<Unit>()
        val steps = mutableListOf<String>()
        val page = CoroutineScope(coroutineContext + Job())
        val save = page.launch {
            writeThenMirror(
                page = page,
                write = {
                    writing.complete(Unit)
                    refused.await()
                    error("database or disk is full")
                },
                release = { steps += "release" },
                mirror = { steps += "mirror" },
                failed = { steps += "failed" },
            )
        }

        writing.await()
        assertEquals(EditorWrite.RUNNING, editorWrite.value)
        page.cancel()
        refused.complete(Unit)
        save.join()

        assertEquals(EditorWrite.REFUSED, editorWrite.value)
        assertEquals(emptyList<String>(), steps)

        writeThenMirror(page = this, write = {}, release = {}, mirror = {}, failed = {})
        assertEquals(EditorWrite.WRITTEN, editorWrite.value)
    }
}
