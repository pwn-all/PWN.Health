package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The OCR and photo sessions: shared while in use, kept for a moment after, closed once nobody came back. */
class LingeringModelTest {
    private class Model : AutoCloseable {
        val closed = CountDownLatch(1)

        override fun close() = closed.countDown()
    }

    /** A session whose native release fails. */
    private class Stubborn : AutoCloseable {
        val attempted = CountDownLatch(1)

        override fun close() {
            attempted.countDown()
            error("the session would not close")
        }
    }

    @Test
    fun aModelOutlivesItsLastUseByTheLingerAndNoLonger() {
        val models = LingeringModel<Model>(lingerMillis = LINGER_MILLIS)
        val opened = ArrayList<Model>()
        val open = { Model().also { opened += it } }
        models.borrow(open) { outer ->
            models.borrow(open) { assertSame(outer, it) }
            Thread.sleep(2 * LINGER_MILLIS)
            assertEquals("closed under a scan still running", 1L, outer.closed.count)
        }
        models.borrow(open) { assertSame("a scan within the linger reuses the model", opened.single(), it) }
        assertTrue(opened.single().closed.await(5, TimeUnit.SECONDS))
        models.borrow(open) {}
        assertEquals(2, opened.size)
    }

    /**
     * The close runs on a timer nobody waits on, where an exception crashes the app; and a model
     * that failed to close must not be lent to the next scan as though it were still open.
     */
    @Test
    fun aModelThatWillNotCloseIsForgottenRatherThanReused() {
        val models = LingeringModel<AutoCloseable>(lingerMillis = 1L)
        val stubborn = Stubborn()
        models.borrow({ stubborn }) {}
        // close() runs under the lock the next borrow takes, so that borrow sees what it left behind.
        assertTrue(stubborn.attempted.await(5, TimeUnit.SECONDS))
        val next = Model()
        models.borrow({ next }) { assertSame("the model that would not close was lent again", next, it) }
    }

    /**
     * A model file that would not load fails the scan that asked for it and nothing after it. Had
     * the failed open still counted its user, the next model would be held forever — never closed,
     * its native memory kept for the life of the process.
     */
    @Test
    fun aModelThatFailedToOpenLeavesNoUserBehind() {
        val models = LingeringModel<Model>(lingerMillis = 1L)
        val failed = assertThrows(IllegalStateException::class.java) {
            models.borrow({ error("the model would not load") }) {}
        }
        assertEquals("the model would not load", failed.message)
        val next = Model()
        models.borrow({ next }) { assertSame(next, it) }
        assertTrue("the model opened after a failed one was never closed", next.closed.await(5, TimeUnit.SECONDS))
    }

    private companion object {
        /**
         * Real time, because the timer under test is real. The scan "within the linger" is the next
         * statement after the one that started the timer, and a pause of the whole JVM between the
         * two — a collection, a busy runner, 4,700 tests and their ONNX sessions in one process —
         * longer than this closes the model first and fails the test for nothing.
         */
        const val LINGER_MILLIS = 600L
    }
}
