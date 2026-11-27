package com.pwnall.health

import kotlin.math.abs

/**
 * A second tap this soon after an identical one was the same tap.
 *
 * Three seconds is long enough to cover a bounced touch, a double-tap out of habit, or a widget
 * redrawing under a finger, and short enough that two deliberate glasses of water in a row still
 * both count.
 */
const val DUPLICATE_TAP_WINDOW_MS = 3_000L

/**
 * Guarding at the data layer rather than in the UI on purpose: every widget tap is a separate
 * process callback with no memory of the previous one, so a Compose-side debounce would protect the
 * app's own buttons and leave the widgets — where a fat finger is likeliest — unprotected.
 *
 * That is the one-tap loggers in Data.kt, and for a marked dose [markDosesTaken]: the medicine
 * screen, the dose widget and the reminder's Taken all write through it, and it looks for the
 * dose's mark inside the transaction that writes one.
 */
fun isDuplicateTap(
    lastAtMs: Long?,
    nowMs: Long,
    windowMs: Long = DUPLICATE_TAP_WINDOW_MS,
): Boolean {
    val last = lastAtMs ?: return false
    val since = nowMs - last
    // A clock that jumped backwards makes `since` negative; within the window that is still the same
    // tap. Bounded on both sides, though: a bare `since < windowMs` would also swallow every tap for
    // the whole duration of a large backwards jump — a timezone fix or an NTP correction of an hour
    // would leave water and coffee silently unloggable for that hour.
    return abs(since) < windowMs
}
