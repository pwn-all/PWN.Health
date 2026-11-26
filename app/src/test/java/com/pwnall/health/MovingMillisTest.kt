package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How long a route was walked: its whole length, less every pause it had, the one still open
 * included — the figure the walk page prints under the walk's name.
 */
class MovingMillisTest {
    private val start = 1_785_400_000_000L

    @Test
    fun aRouteThatNeverPausedIsItsWholeLength() {
        val ended = TrackEntity(startedAt = start, endedAt = start + 1_800_000)

        assertEquals(1_800_000L, ended.movingMillis(start + 9_999_999))
        assertEquals(60_000L, TrackEntity(startedAt = start).movingMillis(start + 60_000))
    }

    @Test
    fun theTimeItStoodPausedIsLeftOut() {
        val walked = TrackEntity(startedAt = start, endedAt = start + 3_600_000, pausedMillis = 600_000)

        assertEquals(3_000_000L, walked.movingMillis(start + 9_999_999))
    }

    /** Paused twenty minutes in, after an earlier pause of five: the clock stands at fifteen minutes. */
    @Test
    fun aPauseStillOpenStopsTheClock() {
        val paused = TrackEntity(startedAt = start, pausedAt = start + 1_200_000, pausedMillis = 300_000)

        assertEquals(900_000L, paused.movingMillis(start + 1_200_000))
        assertEquals(900_000L, paused.movingMillis(start + 7_200_000))
    }

    /** A recording that is going again counts on from where its pauses left it. */
    @Test
    fun aRecordingThatIsGoingAgainCountsOn() {
        val going = TrackEntity(startedAt = start, pausedMillis = 300_000)

        assertEquals(1_500_000L, going.movingMillis(start + 1_800_000))
    }

    @Test
    fun aClockThatRanBackwardsIsNeverBelowZero() {
        assertEquals(0L, TrackEntity(startedAt = start, pausedAt = start + 1_000).movingMillis(start - 5_000))
    }
}
