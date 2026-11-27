package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DayStepsMotionTest {
    private val day = LocalDate.of(2026, 9, 11)
    private val midnight = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val stride = stepDistanceKm(1, 180) * 1000

    private fun hour(value: Int) = midnight + value * 3_600_000L
    private fun bucket(hour: Int, count: Long) = HourSteps(day.toString(), hour, count)
    private fun route(startHour: Int, minutes: Int, count: Int) = TrackEntity(
        startedAt = hour(startHour),
        endedAt = hour(startHour) + minutes * 60_000L,
        distanceMeters = count * stride,
    )

    private fun motion(
        steps: Long,
        hours: List<HourSteps> = emptyList(),
        tracks: List<TrackEntity> = emptyList(),
        journal: List<JournalEntry> = emptyList(),
        now: Long = hour(12),
    ) = dayStepsMotion(steps, hours, tracks, journal, 180, day, now, ZoneOffset.UTC)

    @Test
    fun untrackedStepsProduceAnExplicitEstimate() {
        val result = motion(3000, listOf(bucket(8, 3000)))
        assertEquals(1800L, result.seconds)
        assertEquals(3000 * stride, result.meters, 0.001)
        assertEquals(805, result.paceSeconds(false))
        assertTrue(result.estimated)
    }

    @Test
    fun recordingReplacesItsStepsAndUsesMeasuredPace() {
        val result = motion(3000, listOf(bucket(8, 3000)), listOf(route(8, 20, 3000)))
        assertEquals(1200L, result.seconds)
        assertEquals(3000 * stride, result.meters, 0.001)
        assertFalse(result.estimated)
    }

    @Test
    fun aShortRideInAWalkLeavesTheRestOfTheWalkMeasured() {
        // A 6.05 km walk with a few metres taken for a ride keeps its measured distance, not steps × height.
        val walk = route(8, 20, 4000).copy(vehicleDistanceMeters = 50.0)
        val result = motion(3000, listOf(bucket(8, 3000)), listOf(walk))
        assertEquals(4000 * stride - 50.0, result.meters, 0.001)
        assertEquals(1200L, result.seconds)
        assertFalse(result.estimated)
    }

    @Test
    fun aRecordingThatWasMostlyARideIsLeftToThePedometer() {
        val ride = route(8, 20, 4000).copy(vehicleDistanceMeters = 3000 * stride)
        val result = motion(3000, listOf(bucket(8, 3000)), listOf(ride))
        assertEquals(3000 * stride, result.meters, 0.001)
        assertTrue(result.estimated)
    }

    @Test
    fun aPauseInARecordingIsNotWalkingTime() {
        val paused = route(8, 30, 3000).copy(pausedMillis = 10 * 60_000L)
        val result = motion(3000, listOf(bucket(8, 3000)), listOf(paused))
        assertEquals(1200L, result.seconds)
        assertFalse(result.estimated)
    }

    @Test
    fun separateHoursAndExcessStepsInSameHourAddOnlyUncoveredDistance() {
        val result = motion(5000, listOf(bucket(8, 4000), bucket(9, 1000)), listOf(route(8, 20, 3000)))
        assertEquals(2400L, result.seconds)
        assertEquals(5000 * stride, result.meters, 0.001)
        assertTrue(result.estimated)
    }

    @Test
    fun duplicateAndPartiallyOverlappingTracksUseTimeUnion() {
        val first = route(8, 20, 2000)
        val second = first.copy(startedAt = first.startedAt + 600_000, endedAt = first.endedAt!! + 600_000)
        val result = motion(3000, listOf(bucket(8, 3000)), listOf(first, first, second))
        assertEquals(1800L, result.seconds)
        assertEquals(3000 * stride, result.meters, 0.001)
    }

    @Test
    fun manualWalkAndRouteDoNotAddTwice() {
        val entry = JournalEntry(
            day = day.toString(),
            occurredAt = hour(8) + 1_200_000,
            kind = "ACTIVITY",
            title = "Walk",
            calories = 0.0,
            activityCode = "WALK",
            durationMinutes = 20.0,
        )
        val result = motion(2000, listOf(bucket(8, 2000)), listOf(route(8, 20, 2000)), listOf(entry))
        assertEquals(1200L, result.seconds)
        assertEquals(2000 * stride, result.meters, 0.001)
        assertFalse(result.estimated)
        assertEquals(1200L, motion(0, journal = listOf(entry)).seconds)
        assertEquals(0L, motion(0, journal = listOf(entry.copy(activityCode = "STRENGTH"))).seconds)
    }

    @Test
    fun dailyDeviceTotalIsReconciledRatherThanAddedToPhoneAndRoute() {
        val result = motion(5000, listOf(bucket(8, 3000)), listOf(route(8, 30, 3000)))
        assertEquals(3000L, result.seconds)
        assertEquals(5000 * stride, result.meters, 0.001)
        assertTrue(result.estimated)
        assertEquals(3000L, motion(5000).seconds)
    }

    @Test
    fun timeNeverExceedsElapsedDayOrHourAndFutureBucketsAreIgnored() {
        val result = motion(1000, listOf(bucket(0, 1000), bucket(1, 6000)), now = midnight + 300_000)
        assertEquals(300L, result.seconds)
        assertEquals(1000 * stride, result.meters, 0.001)
        assertEquals(StepsMotion(0, 0.0), motion(1000, now = midnight))
    }

    @Test
    fun routeAcrossMidnightIsClippedAndStopsAreExcluded() {
        val route = route(0, 20, 2000).copy(startedAt = midnight - 1_200_000, stoppedMillis = 600_000)
        val result = motion(0, tracks = listOf(route))
        assertEquals(900L, result.seconds)
        assertEquals(1000 * stride, result.meters, 0.001)
    }

    @Test
    fun emptyOrInvalidDataDoesNotInventMovement() {
        assertEquals(StepsMotion(0, 0.0), motion(-1, listOf(bucket(8, -3))))
        assertEquals(StepsMotion(0, 0.0), motion(0, tracks = listOf(route(8, 20, 2000).copy(endedAt = null))))
    }
}
