package com.pwnall.health

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What [runCatchingCancellable] catches: the body's own failures, a timeout inside it among them, and
 * not the cancellation of the coroutine it runs in, which runCatching takes for a failure of the body.
 */
class RunCatchingCancellableTest {
    @Test
    fun aFailureOfTheBodyIsCaught() = runTest {
        val failed = runCatchingCancellable { write() }
        assertTrue(failed.exceptionOrNull() is IOException)
    }

    @Test
    fun aTimeoutInsideTheBodyIsAFailureLikeAnyOther() = runTest {
        val late = runCatchingCancellable { withTimeout(TIMEOUT_MS) { delay(LONG_MS) } }
        assertTrue(late.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test
    fun theCoroutinesOwnCancellationIsThrownOn() = runTest {
        var failed = false
        val page = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { delay(LONG_MS) }.onFailure { failed = true }
        }
        page.cancelAndJoin()
        assertTrue(page.isCancelled)
        assertFalse(failed)

        // runCatching takes the page going for the write failing.
        var said = false
        val before = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { delay(LONG_MS) }.onFailure { said = true }
        }
        before.cancelAndJoin()
        assertTrue(said)
    }

    private fun write(): Unit = throw IOException("disk full")

    private companion object {
        const val TIMEOUT_MS = 10L
        const val LONG_MS = 60_000L
    }
}
