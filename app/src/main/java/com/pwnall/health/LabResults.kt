package com.pwnall.health

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// The arithmetic behind the results page: which readings the bell counts, and what stretch of time
// a chart is showing after a pinch or a drag. Kept out of the screen because none of it is drawing —
// it is the part worth testing, and the part that decides whether a chart can be zoomed into a
// single dot or out into an empty field.

/** How many out-of-band results the shade shows. Older ones are history, not something to tell. */
internal const val LAB_NOTICE_LIMIT = 50

/**
 * How far back lab results go: the years the form's date picker offers, the oldest day the sheet
 * reader takes for a draw ([scanLabSheet]), and the oldest the results page and the dot beside it
 * read. One figure for all of them: a reader that took older days than the picker and the page would
 * save a sheet out of an archive under a day the page never shows, or under today's with no way to
 * pick its own. Forty years is the whole file of sheets an adult is likely to hold.
 */
const val LAB_HISTORY_YEARS = 40

/** The oldest day any of them takes, so they never disagree about what there is to tell. */
fun labWindowStart(today: LocalDate): String = LocalDate.of(today.year - LAB_HISTORY_YEARS, 1, 1).toString()

/**
 * Every reading that fell outside the band it was read against, newest first.
 *
 * A result outside a published band is the one thing about a panel worth saying out loud, and it is
 * said where the panel is read rather than as a notification the phone posts: nothing here is
 * urgent, and a bell that interrupts somebody's evening over a cholesterol figure typed in last
 * week would be the wrong kind of alarm. A reading with no band is neither inside nor outside and
 * is not counted — see [LabReading.outside].
 */
fun labNotices(history: Map<LabMarker, List<LabReading>>): List<LabReading> =
    history.values.flatten()
        .filter(LabReading::outside)
        .sortedByDescending(LabReading::occurredAt)

/**
 * How many of them were written down after the shade was last pulled down.
 *
 * By the journal row rather than by the date of the draw: a sheet from March typed in September is
 * news in September, and counting by [LabReading.occurredAt] would have let it arrive already read.
 */
fun unreadLabNotices(notices: List<LabReading>, seenEntryId: Long): Int =
    notices.count { it.entryId > seenEntryId }

/**
 * The mark to leave behind once the shade has been read: the newest row there is anything to tell
 * about — not the newest of the few the shade had room for. The list is ordered by the day of the
 * draw, so the row typed in last is not necessarily at the top of it.
 */
fun labNoticeMark(notices: List<LabReading>): Long = notices.maxOfOrNull(LabReading::entryId) ?: 0L

/**
 * The newest journal row the shade has shown.
 *
 * One number rather than a set of ids: rows are numbered in the order they are written, so
 * everything at or under this has been seen. It is also a great deal less to keep — and to leak —
 * than a list naming which of somebody's blood results they have looked at.
 */
object LabNoticeLog {
    private const val PREFS = "labs_notices"
    private const val SEEN = "seen_entry"

    fun seenUntil(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SEEN, 0L)

    /** Never backwards: a row already read stays read even if the newest notice is later deleted. */
    fun markSeen(context: Context, entryId: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (entryId > prefs.getLong(SEEN, 0L)) prefs.edit { putLong(SEEN, entryId) }
    }
}

/** A day in milliseconds, so the spans below read as the periods they are. */
private const val DAY_MS = 24L * 60L * 60L * 1000L

/**
 * The closest a chart is allowed to be zoomed in: a quarter.
 *
 * Panels are drawn months apart, so a window narrower than this is a chart of one dot with nothing
 * to compare it against — and the pinch that produced it would have thrown the rest of the record
 * off both sides of the screen.
 */
const val LAB_CHART_MIN_SPAN_MS = 92L * DAY_MS

/** What a chart opens on where the record is longer than it: a year, the length of a check-up. */
const val LAB_CHART_OPEN_SPAN_MS = 365L * DAY_MS

/** The air either side of the record, so the first and last dot are not drawn on the frame. */
private const val LAB_CHART_MARGIN_MS = 15L * DAY_MS

/** What a chart may show at all: the record itself, with a fortnight of air at each end. */
data class LabChartBounds(val startMs: Long, val endMs: Long) {
    /** Zoomed out as far as it goes, which is the whole record and no further than that. */
    val maxSpanMs: Long get() = maxOf(endMs - startMs, LAB_CHART_MIN_SPAN_MS)
}

/** The stretch of time a chart is showing. */
data class LabChartWindow(val startMs: Long, val endMs: Long) {
    val spanMs: Long get() = endMs - startMs
}

/** The bounds a series earns, including the one-reading case that has no span of its own. */
fun labChartBounds(occurredAt: List<Long>): LabChartBounds {
    val first = occurredAt.minOrNull() ?: 0L
    val last = occurredAt.maxOrNull() ?: first
    return LabChartBounds(first - LAB_CHART_MARGIN_MS, last + LAB_CHART_MARGIN_MS)
}

/**
 * A window of the asked-for width around the asked-for middle, held inside what there is to show.
 *
 * The width is clamped first and the middle after it, so a pinch that asks for more than the record
 * holds comes back centred on the record rather than pressed against one end of it.
 */
fun labChartWindow(bounds: LabChartBounds, spanMs: Long, centreMs: Long): LabChartWindow {
    val span = spanMs.coerceIn(LAB_CHART_MIN_SPAN_MS, bounds.maxSpanMs)
    val half = span / 2L
    val lowest = bounds.startMs + half
    val highest = bounds.endMs - half
    val centre = if (lowest >= highest) (bounds.startMs + bounds.endMs) / 2L else centreMs.coerceIn(lowest, highest)
    return LabChartWindow(centre - half, centre - half + span)
}

/** What a chart opens on: the most recent year of it, or the whole record where that is shorter. */
fun labChartOpening(bounds: LabChartBounds): LabChartWindow =
    labChartWindow(bounds, LAB_CHART_OPEN_SPAN_MS, bounds.endMs)

/**
 * A pinch, with the point under the fingers held still.
 *
 * [focus] is where in the window that point is, from 0 at the left edge to 1 at the right. Holding
 * it fixed is what makes a two-fingered zoom feel like the chart is being pulled apart rather than
 * being re-centred on the middle every time.
 */
fun labChartZoom(bounds: LabChartBounds, window: LabChartWindow, scale: Float, focus: Float): LabChartWindow {
    if (scale <= 0f) return window
    // In doubles throughout: a window is tens of billions of milliseconds wide, and a float carries
    // twenty-four bits of it — enough to move the held point by seconds every time it is pinched.
    val at = focus.coerceIn(0f, 1f).toDouble()
    val span = (window.spanMs / scale.toDouble()).toLong().coerceIn(LAB_CHART_MIN_SPAN_MS, bounds.maxSpanMs)
    val held = window.startMs + (window.spanMs * at).toLong()
    val start = held - (span * at).toLong()
    return labChartWindow(bounds, span, start + span / 2L)
}

/** A drag sideways, in milliseconds of the window rather than in pixels. */
fun labChartPan(bounds: LabChartBounds, window: LabChartWindow, deltaMs: Long): LabChartWindow =
    labChartWindow(bounds, window.spanMs, (window.startMs + window.endMs) / 2L + deltaMs)

/** The shorter side, in dp, from which Android counts a screen as large: a tablet, an unfolded foldable. */
private const val LARGE_SCREEN_SMALLEST_WIDTH_DP = 600

/**
 * Whether asking for landscape turns the screen at all, and so whether the chart offers to.
 *
 * From Android 16 a large screen ignores an app's orientation request, so a button there would light
 * up as pressed and leave the chart standing exactly as it was.
 */
fun labChartTurns(sdk: Int, smallestWidthDp: Int): Boolean =
    sdk < Build.VERSION_CODES.BAKLAVA || smallestWidthDp < LARGE_SCREEN_SMALLEST_WIDTH_DP

/**
 * The steps a dated axis is allowed to climb in, in months. A quarter, a half, a year, five years —
 * and twenty, for a record that runs the whole of [LAB_HISTORY_YEARS] on a narrow chart.
 */
private val LAB_TICK_MONTHS = listOf(1L, 2L, 3L, 6L, 12L, 24L, 60L, 120L, 240L)

/**
 * The dated marks under a chart: the first of a month, every so many months, inside the window.
 *
 * [maxLabels] is how many the width can print without them touching, measured from the widest of
 * them rather than guessed — which is the whole reason the step is chosen here instead of being
 * fixed. Zoomed all the way out a long record steps in years; zoomed in it steps in months, and
 * neither ever prints two dates over each other.
 */
fun labChartTicks(window: LabChartWindow, zone: ZoneId, maxLabels: Int): List<Long> {
    if (maxLabels < 1) return emptyList()
    val first = Instant.ofEpochMilli(window.startMs).atZone(zone).toLocalDate().withDayOfMonth(1)
    val last = Instant.ofEpochMilli(window.endMs).atZone(zone).toLocalDate()
    val months = java.time.temporal.ChronoUnit.MONTHS.between(first, last) + 1
    val step = LAB_TICK_MONTHS.firstOrNull { months / it <= maxLabels } ?: LAB_TICK_MONTHS.last()
    // Started from a whole number of steps since the epoch year rather than from the window's own
    // first month, so the marks stay where they are while the chart is dragged: a ladder measured
    // from the left edge would walk the labels along under the finger.
    val anchor = LocalDate.of(1970, 1, 1)
    val since = java.time.temporal.ChronoUnit.MONTHS.between(anchor, first)
    var tick = anchor.plusMonths(since / step * step)
    if (tick < first) tick = tick.plusMonths(step)
    val ticks = mutableListOf<Long>()
    while (tick <= last && ticks.size <= maxLabels) {
        ticks += tick.atStartOfDay(zone).toInstant().toEpochMilli()
        tick = tick.plusMonths(step)
    }
    return ticks.filter { it in window.startMs..window.endMs }
}
