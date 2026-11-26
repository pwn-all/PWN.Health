package com.pwnall.health

import java.time.Instant
import java.time.ZoneId

/** Converts the sensor's monotonic event time to the wall-clock day on which it happened. */
object SensorEventClock {
    fun epochMillis(
        deliveryEpochMillis: Long,
        deliveryElapsedRealtimeNanos: Long,
        eventTimestampNanos: Long,
    ): Long {
        val deliveryLagNanos = if (
            eventTimestampNanos >= 0 && eventTimestampNanos <= deliveryElapsedRealtimeNanos
        ) {
            deliveryElapsedRealtimeNanos - eventTimestampNanos
        } else {
            0L
        }
        return deliveryEpochMillis - deliveryLagNanos / 1_000_000L
    }

    fun day(
        deliveryEpochMillis: Long,
        deliveryElapsedRealtimeNanos: Long,
        eventTimestampNanos: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = Instant.ofEpochMilli(
        epochMillis(deliveryEpochMillis, deliveryElapsedRealtimeNanos, eventTimestampNanos),
    ).atZone(zoneId).toLocalDate().toString()
}
