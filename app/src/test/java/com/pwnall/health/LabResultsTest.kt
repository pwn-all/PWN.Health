package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LabResultsTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun ms(date: String): Long = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun reading(
        marker: LabMarker,
        day: String,
        value: Double,
        status: LabStatus?,
        entryId: Long = 0L,
    ) = LabReading(
        marker = marker,
        value = value,
        occurredAt = ms(day),
        day = day,
        fasting = true,
        ageYears = 40,
        status = status,
        entryId = entryId,
    )

    @Test
    fun onlyReadingsOutsideABandAreToldAbout() {
        val history = mapOf(
            LabMarker.GLUCOSE to listOf(
                reading(LabMarker.GLUCOSE, "2026-01-10", 5.2, LabStatus.NORMAL, entryId = 1),
                reading(LabMarker.GLUCOSE, "2026-04-10", 7.4, LabStatus.FAR_ABOVE, entryId = 2),
            ),
            LabMarker.HBA1C to listOf(
                // No band to fall either side of: neither inside nor outside, so never a notice.
                reading(LabMarker.HBA1C, "2026-05-01", 5.0, null, entryId = 3),
                reading(LabMarker.HBA1C, "2026-06-01", 6.0, LabStatus.ABOVE, entryId = 4),
            ),
        )
        val notices = labNotices(history)
        assertEquals(listOf("2026-06-01", "2026-04-10"), notices.map(LabReading::day))
        assertEquals(4L, labNoticeMark(notices))
        assertEquals(1, unreadLabNotices(notices, seenEntryId = 2))
        assertEquals(0, unreadLabNotices(notices, seenEntryId = labNoticeMark(notices)))
    }

    @Test
    fun aSheetTypedInLateIsNewsEvenThoughTheDrawIsOld() {
        // The shade has been read to the newest row there was; then a panel drawn in March is typed
        // in. Counted by the day of the draw it would arrive already read.
        val old = reading(LabMarker.GLUCOSE, "2026-03-02", 7.9, LabStatus.FAR_ABOVE, entryId = 9)
        val notices = labNotices(mapOf(LabMarker.GLUCOSE to listOf(old)))
        assertEquals(1, unreadLabNotices(notices, seenEntryId = 8))
        assertEquals(0, unreadLabNotices(notices, seenEntryId = 9))
    }

    @Test
    fun aChartCannotBeZoomedCloserThanAQuarterNorWiderThanTheRecord() {
        val bounds = labChartBounds(listOf(ms("2024-01-01"), ms("2026-01-01")))
        val open = labChartOpening(bounds)
        assertEquals(LAB_CHART_OPEN_SPAN_MS, open.spanMs)
        // The newest reading is what a chart opens on, so the window ends at the record's end.
        assertEquals(bounds.endMs, open.endMs)

        val deep = labChartZoom(bounds, open, scale = 100f, focus = 0.5f)
        assertEquals(LAB_CHART_MIN_SPAN_MS, deep.spanMs)

        val wide = labChartZoom(bounds, open, scale = 0.01f, focus = 0.5f)
        assertEquals(bounds.maxSpanMs, wide.spanMs)
        assertEquals(bounds.startMs, wide.startMs)
        assertEquals(bounds.endMs, wide.endMs)
    }

    @Test
    fun aPinchHoldsThePointUnderTheFingersStill() {
        val bounds = labChartBounds(listOf(ms("2020-01-01"), ms("2026-01-01")))
        val window = labChartWindow(bounds, spanMs = LAB_CHART_OPEN_SPAN_MS * 2, centreMs = ms("2023-01-01"))
        val held = window.startMs + window.spanMs / 4
        val zoomed = labChartZoom(bounds, window, scale = 2f, focus = 0.25f)
        assertEquals(window.spanMs / 2, zoomed.spanMs)
        assertEquals(held.toDouble(), zoomed.startMs + zoomed.spanMs * 0.25, 2.0)
    }

    @Test
    fun draggingStopsAtTheEndsOfTheRecord() {
        val bounds = labChartBounds(listOf(ms("2025-01-01"), ms("2026-01-01")))
        val window = labChartWindow(bounds, spanMs = LAB_CHART_MIN_SPAN_MS, centreMs = ms("2025-06-01"))
        val left = labChartPan(bounds, window, deltaMs = -LAB_CHART_OPEN_SPAN_MS * 10)
        assertEquals(bounds.startMs, left.startMs)
        val right = labChartPan(bounds, window, deltaMs = LAB_CHART_OPEN_SPAN_MS * 10)
        assertEquals(bounds.endMs, right.endMs)
        assertEquals(LAB_CHART_MIN_SPAN_MS, left.spanMs)
    }

    @Test
    fun aSingleReadingStillOpensOnAWindowWorthDrawing() {
        val bounds = labChartBounds(listOf(ms("2026-03-01")))
        val open = labChartOpening(bounds)
        assertEquals(LAB_CHART_MIN_SPAN_MS, open.spanMs)
        assertTrue(ms("2026-03-01") in open.startMs..open.endMs)
    }

    @Test
    fun theFiguresUpTheSideClimbInRoundSteps() {
        val ladder = labValueLadder(labValueScale(listOf(5.4, 5.4), low = null, high = 5.0))
        assertTrue("a ladder of ${ladder.size}", ladder.size in 3..6)
        val steps = ladder.zipWithNext { a, b -> b - a }
        steps.forEach { assertEquals(steps.first(), it, 1e-9) }
        // Inside the scale it was asked for, and on multiples of its own step.
        val scale = labValueScale(listOf(5.4, 5.4), low = null, high = 5.0)
        ladder.forEach { assertTrue(it >= scale.first - 1e-9 && it <= scale.second + 1e-9) }
        val step = steps.first()
        ladder.forEach { assertEquals(0.0, it / step - Math.round(it / step), 1e-6) }
    }

    @Test
    fun aFlatSeriesStillGetsAScaleAndALadder() {
        val scale = labValueScale(listOf(7.0), low = null, high = null)
        assertTrue(scale.second > scale.first)
        assertTrue(labValueLadder(scale).isNotEmpty())
    }

    /**
     * The side of a full-screen chart is printed to the decimals its step needs: a ladder over a few
     * hundredths of TSH would print 0.0, 0.0, 0.1, 0.1 through the shared one-decimal format.
     */
    @Test
    fun aLadderOfHundredthsPrintsEveryRungApart() {
        val ladder = labValueLadder(0.03 to 0.06)
        assertEquals(listOf("0.0", "0.0", "0.1", "0.1"), ladder.map { it.pretty() })
        assertEquals(listOf("0.03", "0.04", "0.05", "0.06"), labLadderText(ladder))
        assertEquals(listOf("4", "5", "6"), labLadderText(listOf(4.0, 5.0, 6.0)))
        assertEquals(listOf("7"), labLadderText(listOf(7.0)))
    }

    @Test
    fun datedMarksThinOutRatherThanRunTogether() {
        val bounds = labChartBounds(listOf(ms("2016-01-01"), ms("2026-01-01")))
        val whole = labChartWindow(bounds, bounds.maxSpanMs, centreMs = ms("2021-01-01"))
        val fitsFour = labChartTicks(whole, zone, maxLabels = 4)
        assertTrue("four labels at most over ten years, got ${fitsFour.size}", fitsFour.size <= 4)
        val fitsTwelve = labChartTicks(whole, zone, maxLabels = 12)
        assertTrue(fitsTwelve.size > fitsFour.size)
        fitsFour.forEach { assertTrue(it in whole.startMs..whole.endMs) }
        // A record as long as the form takes dates for, on a chart with room for three: ten-year
        // steps would print four.
        val archive = labChartBounds(listOf(ms("${2026 - LAB_HISTORY_YEARS}-01-01"), ms("2026-01-01")))
        val all = labChartWindow(archive, archive.maxSpanMs, centreMs = ms("2006-01-01"))
        assertTrue(labChartTicks(all, zone, maxLabels = 3).size in 1..3)
    }

    @Test
    fun datedMarksStayPutWhileTheChartIsDragged() {
        val bounds = labChartBounds(listOf(ms("2020-01-01"), ms("2026-01-01")))
        val window = labChartWindow(bounds, spanMs = LAB_CHART_OPEN_SPAN_MS, centreMs = ms("2023-01-01"))
        val before = labChartTicks(window, zone, maxLabels = 6)
        val dragged = labChartPan(bounds, window, deltaMs = 5L * 24 * 60 * 60 * 1000)
        val after = labChartTicks(dragged, zone, maxLabels = 6)
        // The marks a drag of five days keeps are the same instants, not the same offsets.
        assertTrue(after.intersect(before.toSet()).size >= before.size - 1)
    }

    @Test
    fun theChartOffersToTurnSidewaysOnlyWhereTheScreenWouldTurn() {
        // A phone turns on every version; a tablet stops turning for an app from Android 16 (API 36).
        assertTrue(labChartTurns(sdk = 35, smallestWidthDp = 800))
        assertTrue(labChartTurns(sdk = 36, smallestWidthDp = 411))
        assertTrue(labChartTurns(sdk = 37, smallestWidthDp = 599))
        assertFalse(labChartTurns(sdk = 36, smallestWidthDp = 600))
        assertFalse(labChartTurns(sdk = 37, smallestWidthDp = 840))
    }
}
