package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class SensorEventClockTest {
    @Test
    fun `batched event is attributed to its event day rather than delivery day`() {
        val delivery = Instant.parse("2026-07-31T00:00:05Z").toEpochMilli()

        assertEquals(
            "2026-07-30",
            SensorEventClock.day(
                deliveryEpochMillis = delivery,
                deliveryElapsedRealtimeNanos = 100_000_000_000L,
                eventTimestampNanos = 90_000_000_000L,
                zoneId = ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun `future malformed timestamp cannot move the wall clock into the future`() {
        val delivery = Instant.parse("2026-07-31T12:00:00Z").toEpochMilli()

        assertEquals(
            delivery,
            SensorEventClock.epochMillis(
                deliveryEpochMillis = delivery,
                deliveryElapsedRealtimeNanos = 100,
                eventTimestampNanos = 101,
            ),
        )
    }
}
