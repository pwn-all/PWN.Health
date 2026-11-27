package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class BluetoothAccumulatorsTest {
    /**
     * The procedure ended with a context still owed. One missing context must not fail the whole
     * transfer and discard every record in it: the owed records go out contextless, in the order
     * they arrived, and the accumulator is left clean for the next procedure.
     */
    @Test
    fun recordsStillOwedAContextAreDeliveredWithoutOneWhenTheProcedureEnds() {
        val accumulator = GlucoseRecordAccumulator()
        val base = LocalDateTime.of(2026, 7, 31, 12, 0)
        val first = GlucoseMeasurement(1, base, contextFollows = true)
        val second = GlucoseMeasurement(2, base.plusMinutes(5), contextFollows = true)
        assertNull(accumulator.update(first))
        assertNull(accumulator.update(second))

        val drained = accumulator.drainPending()

        assertEquals(listOf(GlucoseRecord(first, null), GlucoseRecord(second, null)), drained)
        assertEquals(false, accumulator.hasPendingContext)
        assertEquals(emptyList<GlucoseRecord>(), accumulator.drainPending())
    }

    @Test
    fun mergesMatchingBodyCompositionParts() {
        val accumulator = BodyCompositionAccumulator()
        val timestamp = LocalDateTime.of(2026, 7, 31, 12, 0)
        assertNull(
            accumulator.update(
                BodyCompositionMeasurement(
                    bodyFatPercent = 20.1,
                    timestamp = timestamp,
                    weightKg = 80.0,
                    multiplePacket = true,
                ),
            ),
        )

        val merged = accumulator.update(
            BodyCompositionMeasurement(
                bodyFatPercent = 20.1,
                impedanceOhms = 512.3,
                multiplePacket = true,
            ),
        )!!

        assertEquals(80.0, merged.weightKg!!, 0.001)
        assertEquals(512.3, merged.impedanceOhms!!, 0.001)
        assertEquals(false, merged.multiplePacket)
    }

    @Test
    fun aNewFirstPacketReplacesAnIncompleteMeasurementFromAnotherUser() {
        val accumulator = BodyCompositionAccumulator()
        val firstTime = LocalDateTime.of(2026, 7, 31, 12, 0)
        val secondTime = firstTime.plusMinutes(1)
        assertNull(
            accumulator.update(
                BodyCompositionMeasurement(
                    20.0,
                    timestamp = firstTime,
                    userId = 1,
                    userIdPresent = true,
                    multiplePacket = true,
                ),
            ),
        )
        assertNull(
            accumulator.update(
                BodyCompositionMeasurement(
                    20.0,
                    timestamp = secondTime,
                    userId = 2,
                    userIdPresent = true,
                    multiplePacket = true,
                ),
            ),
        )
        val merged = accumulator.update(
            BodyCompositionMeasurement(20.0, impedanceOhms = 500.0, multiplePacket = true),
        )!!
        assertEquals(2, merged.userId)
        assertEquals(secondTime, merged.timestamp)
    }

    @Test
    fun waitsForMatchingGlucoseContext() {
        val accumulator = GlucoseRecordAccumulator()
        val measurement = GlucoseMeasurement(
            sequenceNumber = 42,
            baseTime = LocalDateTime.of(2026, 7, 31, 12, 0),
            contextFollows = true,
        )
        assertNull(accumulator.update(measurement))
        assertNull(accumulator.update(GlucoseMeasurementContext(sequenceNumber = 41, meal = 1)))

        val record = accumulator.update(
            GlucoseMeasurementContext(sequenceNumber = 42, meal = 2),
        )!!
        assertEquals(measurement, record.measurement)
        assertEquals(2, record.context?.meal)
    }

    /**
     * A meter that sends its whole history before any context overruns the pairing buffer. The
     * measurement pushed out of it can never be paired, but it was still a reading the person took,
     * so it has to come back contextless rather than disappear.
     */
    @Test
    fun aMeasurementPushedOutOfTheContextBufferIsStillReported() {
        val accumulator = GlucoseRecordAccumulator()
        val base = LocalDateTime.of(2026, 7, 31, 12, 0)
        val pending = (1..8).map { sequence ->
            GlucoseMeasurement(sequence, base.plusMinutes(sequence.toLong()), contextFollows = true)
        }
        pending.forEach { assertNull(accumulator.update(it)) }

        val evicted = accumulator.update(
            GlucoseMeasurement(9, base.plusMinutes(9), contextFollows = true),
        )!!

        assertEquals(pending.first(), evicted.measurement)
        assertNull(evicted.context)
        assertNull(accumulator.update(GlucoseMeasurementContext(sequenceNumber = 1, meal = 2)))
        assertEquals(
            pending[1],
            accumulator.update(GlucoseMeasurementContext(sequenceNumber = 2, meal = 2))?.measurement,
        )
    }

    @Test
    fun carriesTheProcedureTimeOffsetWhenLaterGlucoseRecordsOmitIt() {
        val accumulator = GlucoseRecordAccumulator()
        val base = LocalDateTime.of(2026, 7, 31, 12, 0)
        val first = accumulator.update(
            GlucoseMeasurement(1, base, timeOffsetMinutes = -60),
        )!!
        val second = accumulator.update(
            GlucoseMeasurement(2, base.plusMinutes(5)),
        )!!

        assertEquals(-60, first.measurement.timeOffsetMinutes)
        assertEquals(-60, second.measurement.timeOffsetMinutes)
        assertEquals(base.minusMinutes(55), second.measurement.userFacingTime)
    }
}
