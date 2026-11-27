package com.pwnall.health

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [runCatching] for a body that suspends, in a coroutine that can be cancelled.
 *
 * runCatching catches everything, the coroutine's own cancellation with it: a page left, or a worker
 * stopped, while a write was under way would run the branch for a failed one — saying "not saved"
 * over a page being closed, filing an exchange with Health Connect that was only stopped as its
 * error — and go on with work the cancelled coroutine has no business doing. Here a failure of the body is caught as
 * runCatching catches it, a [kotlinx.coroutines.withTimeout] inside it running out among them; when
 * the coroutine itself has been cancelled, that cancellation is thrown on instead. Under
 * NonCancellable nothing is cancelled, and it is runCatching.
 *
 * Not for a catch whose failure branch must run on a cancellation too — a file half written taken
 * away, a lock given back — before the failure is thrown on with getOrThrow.
 */
internal suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { currentCoroutineContext().ensureActive() }
