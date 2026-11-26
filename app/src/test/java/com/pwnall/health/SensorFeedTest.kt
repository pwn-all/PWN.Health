package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SensorFeedTest {
    @Before
    fun setUp() = SensorFeed.resetForTest()

    @After
    fun tearDown() = SensorFeed.resetForTest()

    @Test
    fun `event decision excludes all future accelerometer samples`() {
        feed(startIndex = 0, endIndex = 100, amplitude = 3f)
        feed(startIndex = 100, endIndex = 200, amplitude = 0.02f)

        val eventTimestamp = 3_999_999_999L
        val decision = SensorFeed.decisionAt(eventTimestamp)

        assertEquals(Motion.WALKING, decision.reading.motion)
        assertEquals(TraceReadingSource.EVENT_WINDOW, decision.window.source)
        assertTrue(decision.window.lastSampleTimestampNanos!! <= eventTimestamp)
        assertEquals(3_960_000_000L, decision.window.lastSampleTimestampNanos)
    }

    @Test
    fun `pedometer steps vouch only for route fixes near them`() {
        assertFalse(SensorFeed.stepsNear(5_000_000_000L))

        SensorFeed.onSteps(10, rawDelta = 0, creditedDelta = 0, StepVerdict.CREDITED, timestampNanos = 1_000_000_000L)
        assertFalse(SensorFeed.stepsNear(1_000_000_000L))

        SensorFeed.onSteps(13, rawDelta = 3, creditedDelta = 3, StepVerdict.CREDITED, timestampNanos = 5_000_000_000L)
        assertTrue(SensorFeed.stepsNear(1_000_000_000L))
        assertTrue(SensorFeed.stepsNear(15_000_000_000L))
        assertFalse(SensorFeed.stepsNear(15_000_000_001L))
    }

    @Test
    fun `future live window is never used for an earlier event`() {
        feed(startIndex = 250, endIndex = 350, amplitude = 3f)

        val decision = SensorFeed.decisionAt(9_000_000_000L)

        assertEquals(Motion.UNKNOWN, decision.reading.motion)
        assertEquals(TraceReadingSource.INSUFFICIENT_DATA, decision.window.source)
        assertEquals(0, decision.window.sampleCount)
        assertEquals(Motion.UNKNOWN, SensorFeed.readingAt(9_000_000_000L).motion)
    }

    @Test
    fun `causal but stale samples are insufficient`() {
        feed(startIndex = 0, endIndex = 100, amplitude = 3f)

        val decision = SensorFeed.decisionAt(9_000_000_000L)

        assertEquals(Motion.UNKNOWN, decision.reading.motion)
        assertEquals(TraceReadingSource.INSUFFICIENT_DATA, decision.window.source)
        assertEquals(3_960_000_000L, decision.window.lastSampleTimestampNanos)
    }

    @Test
    fun `a gap requires a new temporally complete run`() {
        feed(startIndex = 0, endIndex = 50, amplitude = 3f)
        feed(startIndex = 75, endIndex = 90, amplitude = 3f)

        val early = SensorFeed.decisionAt(3_599_999_999L)

        assertEquals(Motion.UNKNOWN, early.reading.motion)
        assertEquals(TraceReadingSource.INSUFFICIENT_DATA, early.window.source)
        assertEquals(15, early.window.sampleCount)

        feed(startIndex = 90, endIndex = 150, amplitude = 3f)
        val recovered = SensorFeed.decisionAt(5_999_999_999L)

        assertEquals(Motion.WALKING, recovered.reading.motion)
        assertEquals(TraceReadingSource.EVENT_WINDOW, recovered.window.source)
        assertEquals(75, recovered.window.sampleCount)
        assertTrue(recovered.window.coverageNanos!! >= 2_500_000_000L)
    }

    @Test
    fun `decision metadata describes a bounded six second window`() {
        feed(startIndex = 0, endIndex = 300, amplitude = 3f)

        val eventTimestamp = 11_999_999_999L
        val decision = SensorFeed.decisionAt(eventTimestamp)

        assertEquals(TraceReadingSource.EVENT_WINDOW, decision.window.source)
        assertTrue(
            decision.window.firstSampleTimestampNanos!! >=
                eventTimestamp - 6_000_000_000L,
        )
        assertTrue(decision.window.lastSampleTimestampNanos!! <= eventTimestamp)
        assertTrue(decision.window.coverageNanos!! <= 6_000_000_000L)
        assertTrue(decision.window.maxGapNanos!! <= 200_000_000L)
    }

    /**
     * The verdict the feed keeps is the sensor screen's and a trace's. With neither there it is not
     * worked out — rather than a window analysed every 400 ms for as long as the service lives — and a
     * step or a fix, which asks for the window of its own moment, is still answered.
     */
    @Test
    fun `the kept verdict is worked out only for the screen or a trace`() {
        // The recorder is a singleton: one another test left running would be a trace to work for.
        SensorTrace.stop()
        feed(startIndex = 0, endIndex = 100, amplitude = 3f)

        assertEquals(MotionReading(), SensorFeed.state.value.reading)
        assertEquals(Motion.WALKING, SensorFeed.readingAt(3_999_999_999L).motion)

        SensorTrace.start()
        try {
            feed(startIndex = 100, endIndex = 200, amplitude = 3f)
        } finally {
            SensorTrace.stop()
            SensorTrace.discard()
        }

        assertEquals(Motion.WALKING, SensorFeed.state.value.reading.motion)
    }

    private fun feed(startIndex: Int, endIndex: Int, amplitude: Float) {
        for (index in startIndex until endIndex) {
            val seconds = index / RATE.toFloat()
            val value = 9.81f + amplitude * sin(2f * PI.toFloat() * 2f * seconds)
            SensorFeed.add(
                sample = Accel(index * SAMPLE_INTERVAL_NANOS, 0f, value, 0f),
                screenOn = false,
            )
        }
    }

    private companion object {
        const val RATE = 25
        const val SAMPLE_INTERVAL_NANOS = 40_000_000L
    }
}
