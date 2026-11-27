package com.pwnall.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * How long the wait may be before the date is read again anyway.
 *
 * The timer below sleeps on a clock that stops while the device does, and a flight or a timezone
 * change moves the date without waking anything at all. A minute is what the screens that show a
 * clock already tick at, and it bounds how stale the date can be to something nobody sees.
 */
private const val DAY_RECHECK_MS = 60_000L

/**
 * Today's date, re-read when the day actually turns over.
 *
 * Captured once with a plain `remember`, "today" stays whatever it was when the screen opened: a
 * screen left open across midnight keeps drawing yesterday's window and files the next tap under
 * yesterday. Every screen takes the date from here rather than from a copy of its own, so there is
 * one place for [millisUntilNextDay] to be wrong in.
 *
 * Re-reading an unchanged date is not a recomposition: [mutableStateOf] compares before it writes.
 */
@Composable
internal fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextDay(ZonedDateTime.now()).coerceAtMost(DAY_RECHECK_MS))
            today = LocalDate.now()
        }
    }
    return today
}

/**
 * The phone's time zone, read again as Android says it changed.
 *
 * Captured once with a plain `remember`, the zone stays the one the screen opened in: after a
 * flight a page left open would draw its times and file its records by the zone of departure until
 * it was closed.
 * [Intent.ACTION_TIMEZONE_CHANGED] reaches a receiver the page holds while it is composed, in the
 * background as well; the zone is read once more as the receiver is registered, for a change made
 * while the page was being built.
 */
@Composable
internal fun rememberZone(): ZoneId {
    val context = LocalContext.current
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                zone = ZoneId.systemDefault()
            }
        }
        // Sent by the system alone, which a receiver not exported still hears.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        zone = ZoneId.systemDefault()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return zone
}

/**
 * How long there is left of the day where the device is standing.
 *
 * Measured between two instants, not between two wall clocks, because [delay] waits out real time.
 * On the night the clocks go forward the two disagree by an hour: 00:30 to midnight reads as 23.5
 * wall-clock hours and is 22.5 real ones, and a screen open across that night would go on showing
 * yesterday for an hour after the date had changed. The night they go back is the same mistake in
 * the other direction, which merely wakes the screen early.
 *
 * [LocalDate.atStartOfDay] with a zone rather than an offset in milliseconds, because some zones
 * have days with no midnight in them — Santiago's September Sunday starts at 01:00 — and that is
 * the moment the date changes there.
 *
 * Never zero: `delay(0)` returns at once, and a tick that fires before the date has moved would
 * spin.
 */
internal fun millisUntilNextDay(now: ZonedDateTime): Long =
    Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))
        .toMillis()
        .coerceAtLeast(1L)
