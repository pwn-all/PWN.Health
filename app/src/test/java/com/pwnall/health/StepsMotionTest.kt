package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepsMotionTest {
    @Test
    fun paceExcludesStopsAndConvertsDistanceUnits() {
        val track = TrackEntity(startedAt = 1000, endedAt = 721_000, stoppedMillis = 120_000, distanceMeters = 1000.0)
        val motion = stepsMotion(listOf(track), 0, 900_000)
        assertEquals(600L, motion.seconds)
        assertEquals(600, motion.paceSeconds(false))
        assertEquals(966, motion.paceSeconds(true))
    }

    @Test
    fun otherDaysFutureUnfinishedAndVehicleRoutesCannotSupplyTodaysWalkingPace() {
        val base = TrackEntity(startedAt = 1000, endedAt = 61_000, distanceMeters = 100.0)
        val excluded = listOf(
            base.copy(startedAt = -1000),
            base.copy(endedAt = 100_001),
            base.copy(endedAt = null),
            // A ride of 30 m against 70 m on foot: past a quarter, its minutes would pass for walking.
            base.copy(vehicleDistanceMeters = 30.0),
            base.copy(distanceMeters = Double.NaN),
        )
        assertEquals(StepsMotion(0, 0.0), stepsMotion(excluded, 0, 100_000))
        assertNull(stepsMotion(excluded, 0, 100_000).paceSeconds(false))
    }

    @Test
    fun theStillStretchIsMeasuredFromTheFirstHourThatMovedRatherThanFromMidnight() {
        val hours = MutableList(24) { 0f }
        hours[7] = 120f
        assertEquals(8..9, longestStillHours(hours, 9))
        // The hour that moved is the whole of the elapsed day: nothing still has happened yet.
        assertNull(longestStillHours(hours, 7))
        assertNull(longestStillHours(MutableList(24) { 0f }, 9))
        assertNull(longestStillHours(emptyList(), 9))
        assertEquals(8..23, longestStillHours(hours, 99))
    }

    @Test
    fun theHourInProgressIsNotAStretchOnItsOwnButStillExtendsOne() {
        val hours = MutableList(24) { 0f }
        hours[13] = 120f
        // 14:00 is the hour in progress and the only still hour there is: nothing to say yet.
        assertNull(longestStillHours(hours, 14))
        // 13:00 moved, 14:00 and 15:00 did not, and the sit is still going at 15:xx.
        assertEquals(14..15, longestStillHours(hours, 15))
    }

    @Test
    fun anHourNeedsAboutAMinuteOfWalkingBeforeItCountsAsMoved() {
        val hours = MutableList(24) { 0f }
        hours[7] = 100f
        hours[9] = 99f
        assertEquals(8..10, longestStillHours(hours, 10))
    }
}
