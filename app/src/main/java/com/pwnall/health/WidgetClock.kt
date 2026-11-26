package com.pwnall.health

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * The redraws no write stands behind: midnight, when every "today" a widget shows starts again, and
 * the end of the pause after a mood, when the faces come back.
 *
 * A write redraws only what it changed, so without these a face that depends on the clock would
 * stand until its own next write or the launcher's half-hourly update: yesterday's water at a
 * quarter past midnight, a mood's pause counted on after it was over. So the moment itself is asked
 * for, of the alarm service — inexact and not a wake-up, which needs no permission: a face nobody
 * is looking at can wait for the screen.
 */
internal object WidgetClock {
    /** How late a redraw may come: the shortest window the platform keeps to. */
    private const val WINDOW_MS = 10 * 60 * 1_000L

    /** The moment already asked for under each action, so that asking again for the same one costs nothing. */
    private val asked = ConcurrentHashMap<String, Long>()

    /** Asks for one redraw of the widgets showing [kind], all of them for null, at [atMs]; asked again, it moves. */
    fun redrawAt(context: Context, kind: String?, atMs: Long) {
        val action = widgetClockAction(kind)
        if (asked.put(action, atMs) == atMs) return
        val app = context.applicationContext
        val pending = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, WidgetClockReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        app.getSystemService(AlarmManager::class.java)?.setWindow(AlarmManager.RTC, atMs, WINDOW_MS, pending)
    }

    /**
     * Asks for the redraw at the next midnight. Called wherever the widgets are redrawn, so the one
     * that comes at midnight asks for the next; and after a reboot, which drops every alarm.
     */
    fun armMidnight(context: Context) = redrawAt(context, null, nextMidnightMs(System.currentTimeMillis()))
}

private const val WIDGET_CLOCK_ACTION = "com.pwnall.health.WIDGET_CLOCK:"

/** The action a redraw of [kind] is asked under: one alarm a kind, since the action is what tells two apart. */
internal fun widgetClockAction(kind: String?): String = WIDGET_CLOCK_ACTION + kind.orEmpty()

/** The kind [action] asks a redraw of, null for every widget; the reverse of [widgetClockAction]. */
internal fun widgetClockKind(action: String?): String? = action?.removePrefix(WIDGET_CLOCK_ACTION)?.ifEmpty { null }

/** The first midnight after [nowMs] in [zone]: the start of the next day, whatever length this one has. */
internal fun nextMidnightMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

private val widgetClockScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Receives the moments [WidgetClock] asked for, and redraws what was asked. */
class WidgetClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = widgetClockKind(intent.action)
        val app = context.applicationContext
        // The steps notification says "today" as the widgets do. A service standing in the
        // foreground is told with the action that picks no route up, and reads the day again; one
        // that is not standing reads it when it next starts.
        if (kind == null && StepService.inForeground) {
            try {
                app.startService(Intent(app, StepService::class.java).setAction(StepService.ACTION_BOOT))
            } catch (_: IllegalStateException) {
                // Taken for a start from the background after all: the service reads the day at its
                // next start.
            }
        }
        val pending = goAsync()
        widgetClockScope.launch {
            try {
                refreshWidgets(app, kind)
            } finally {
                pending.finish()
            }
        }
    }
}
