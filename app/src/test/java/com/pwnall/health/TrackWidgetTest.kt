package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The track widget's recording face: its title says whether the route records or stands paused, and
 * its clock counts the time walked, as the walk page does, so a pause stops it.
 */
class TrackWidgetTest {
    private val start = 1_785_400_000_000L

    @Test
    fun theTitleSaysWhetherTheRouteRecordsOrStandsPaused() {
        assertEquals(R.string.widget_track_recording, trackWidgetTitle(TrackEntity(startedAt = start)))
        assertEquals(
            R.string.widget_track_recording,
            trackWidgetTitle(TrackEntity(startedAt = start, pausedMillis = 60_000)),
        )
        assertEquals(
            R.string.widget_track_paused,
            trackWidgetTitle(TrackEntity(startedAt = start, pausedAt = start + 60_000)),
        )
    }

    /** Paused twenty minutes in, after an earlier pause of five: fifteen minutes, and an hour on as well. */
    @Test
    fun aPausedRoutesClockStandsStill() {
        val paused = TrackEntity(startedAt = start, pausedAt = start + 1_200_000, pausedMillis = 300_000)

        assertEquals("15:00", trackWidgetClock(paused, start + 1_200_000))
        assertEquals("15:00", trackWidgetClock(paused, start + 4_800_000))
    }

    /** Going again after a pause of five minutes: the clock runs on without it, into the hours. */
    @Test
    fun aRouteGoingAgainCountsOnWithoutItsPauses() {
        val going = TrackEntity(startedAt = start, pausedMillis = 300_000)

        assertEquals("25:00", trackWidgetClock(going, start + 1_800_000))
        assertEquals("1:00:05", trackWidgetClock(going, start + 3_905_000))
    }
}
