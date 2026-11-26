package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SensorTraceTest {
    private val walking = MotionReading(
        motion = Motion.WALKING,
        posture = Posture.POCKET,
        std = 2.5f,
        cadenceHz = 1.8f,
        periodicity = 0.61f,
        tiltDeg = 88f,
    )

    @Before
    fun clear() {
        SensorTrace.start()
        SensorTrace.stop()
    }

    @After
    fun reset() {
        SensorTrace.stop()
    }

    @Test
    fun discardLetsTheRowsGoOnlyOnceRecordingHasStopped() {
        SensorTrace.start()
        SensorTrace.sample(Accel(1_000, 0.5f, 1.5f, 9.81f), walking, 1_750)
        SensorTrace.discard()
        assertEquals(1, rows().count { it["kind"] == "S" })

        SensorTrace.stop()
        SensorTrace.discard()
        assertEquals(0, SensorTrace.state.value.rows)
        assertTrue(rows().none { it["kind"] == "S" })
    }

    @Test
    fun nothingIsRecordedUntilRecordingStarts() {
        SensorTrace.sample(Accel(1, 0f, 0f, 9.81f), walking, 2)

        assertEquals(0, SensorTrace.state.value.rows)
        assertFalse(SensorTrace.state.value.recording)
        assertTrue(rows().none { it["kind"] == "S" || it["kind"] == "P" })
    }

    @Test
    fun samplesAndStepsRecordBothClocksAndExactGateDecision() {
        SensorTrace.start()
        SensorTrace.sample(
            sample = Accel(1_000, 0.5f, 1.5f, 9.81f),
            reading = walking,
            deliveryElapsedRealtimeNanos = 1_750,
        )
        SensorTrace.step(
            eventTimestampNanos = 2_000,
            reading = walking,
            rawCounter = 4_242,
            rawDelta = 15,
            credited = 0,
            verdict = StepVerdict.NO_GAIT,
            deliveryElapsedRealtimeNanos = 9_000,
            // Deliberately differs from walking.stepsAllowed. The trace must record what the
            // accumulator actually received, not re-derive the decision while exporting.
            gaitEvidence = false,
            window = TraceWindowMetadata(
                source = TraceReadingSource.EVENT_WINDOW,
                firstSampleTimestampNanos = 500,
                lastSampleTimestampNanos = 1_900,
                sampleCount = 36,
                maxGapNanos = 80,
            ),
        )
        SensorTrace.stop()

        val sample = rows().single { it["kind"] == "S" }
        val step = rows().single { it["kind"] == "P" }

        assertEquals("1000", sample["eventTimestampNanos"])
        assertEquals("1750", sample["deliveryElapsedRealtimeNanos"])
        assertEquals("0.5000", sample["x"])
        assertEquals("WALKING", sample["motion"])

        assertEquals("2000", step["eventTimestampNanos"])
        assertEquals("9000", step["deliveryElapsedRealtimeNanos"])
        assertEquals("WALKING", step["motion"])
        assertEquals("false", step["gaitEvidence"])
        assertEquals("EVENT_WINDOW", step["readingSource"])
        assertEquals("500", step["windowStartNanos"])
        assertEquals("1900", step["windowEndNanos"])
        assertEquals("36", step["windowSampleCount"])
        assertEquals("1400", step["windowCoverageNanos"])
        assertEquals("80", step["windowMaxGapNanos"])
        assertEquals("4242", step["rawCounter"])
        assertEquals("15", step["rawDelta"])
        assertEquals("0", step["credited"])
        assertEquals("NO_GAIT", step["verdict"])
        assertEquals(2, SensorTrace.state.value.rows)
    }

    @Test
    fun legacyStepCallRemainsValidAndMarksUnknownProvenance() {
        SensorTrace.start()
        SensorTrace.step(2, walking, 1, 1, 1, StepVerdict.CREDITED)
        SensorTrace.stop()

        val step = rows().single { it["kind"] == "P" }

        assertEquals("", step["deliveryElapsedRealtimeNanos"])
        assertEquals("true", step["gaitEvidence"])
        assertEquals("UNSPECIFIED", step["readingSource"])
        assertEquals("", step["windowSampleCount"])
    }

    @Test
    fun sessionMetadataCapturesBaselineConfigAndLateValues() {
        val config = GateConfig(
            minSamples = 42,
            minWindowSeconds = 3.25f,
            maxSampleGapSeconds = 0.18f,
            analysisWindowSeconds = 7f,
            periodicityMin = 0.73f,
        )
        val accumulatorState = StepState(
            bootId = 321,
            lastCumulative = 900,
            lastEventNanos = 5_000,
            pendingSteps = 2,
            boutStartedNanos = 4_000,
            boutLastNanos = 5_000,
            rateWindowStartedNanos = 4_500,
            rateWindowStartCounter = 898,
            pendingDay = "2026-07-31",
            pendingDaySteps = 2,
        )
        SensorTrace.start(
            TraceSessionMetadata(
                startedAtEpochMillis = 1_234,
                startedAtElapsedRealtimeNanos = 5_678,
                baselineRawCounter = 900,
                bootId = 321,
                day = "2026-07-31",
                accumulatorState = accumulatorState,
                gateConfig = config,
                extras = mapOf("sensor.vendor" to "Test Vendor"),
            ),
        )
        SensorTrace.metadata("counter.baselineRaw", "901")
        SensorTrace.stop()

        val metadata = rows()
            .filter { it["kind"] == "M" }
            .associate { it["metadataKey"] to it["metadataValue"] }

        assertEquals("2", metadata["schema.version"])
        assertEquals("sensor_elapsed_realtime_nanos", metadata["clock.event"])
        assertEquals("elapsed_realtime_nanos", metadata["clock.delivery"])
        assertEquals("1234", metadata["session.startedAtEpochMillis"])
        assertEquals("5678", metadata["session.startedAtElapsedRealtimeNanos"])
        assertEquals("901", metadata["counter.baselineRaw"])
        assertEquals("321", metadata["counter.bootId"])
        assertEquals("2026-07-31", metadata["counter.day"])
        assertEquals("900", metadata["state.lastCumulative"])
        assertEquals("5000", metadata["state.lastEventNanos"])
        assertEquals("2", metadata["state.pendingSteps"])
        assertEquals("4500", metadata["state.rateWindowStartedNanos"])
        assertEquals("898", metadata["state.rateWindowStartCounter"])
        assertEquals("2026-07-31", metadata["state.pendingDay"])
        assertEquals("42", metadata["gate.minSamples"])
        assertEquals("3.25", metadata["gate.minWindowSeconds"])
        assertEquals("0.18", metadata["gate.maxSampleGapSeconds"])
        assertEquals("7.0", metadata["gate.analysisWindowSeconds"])
        assertEquals("0.73", metadata["gate.periodicityMin"])
        assertEquals("Test Vendor", metadata["extra.sensor.vendor"])
        assertEquals(0, SensorTrace.state.value.rows)
    }

    @Test
    fun metadataValuesAreCsvEscaped() {
        SensorTrace.start(TraceSessionMetadata(extras = mapOf("note" to "one, \"two\"\nthree")))
        SensorTrace.stop()

        assertTrue(SensorTrace.csv().contains("extra.note,\"one, \"\"two\"\"\nthree\""))
    }

    @Test
    fun everyRowHasTheSameColumnCount() {
        SensorTrace.start()
        SensorTrace.sample(Accel(1, 0f, 0f, 9.81f), walking)
        SensorTrace.step(2, walking, 1, 1, 1, StepVerdict.NO_GAIT)
        SensorTrace.stop()

        val expected = SensorTrace.HEADER.split(",").size
        val rowColumnCounts = SensorTrace.csv()
            .lineSequence()
            .drop(1)
            .filter { it.isNotEmpty() }
            .map { parseCsvLine(it).size }
            .toSet()

        assertEquals(setOf(expected), rowColumnCounts)
    }

    /** The save path streams instead of building the whole file, so the two must not drift apart. */
    @Test
    fun theStreamedTraceIsTheSameFileAsTheJoinedOne() {
        SensorTrace.start(TraceSessionMetadata(extras = mapOf("note" to "one, \"two\"")))
        SensorTrace.sample(Accel(1, 0f, 0f, 9.81f), walking)
        SensorTrace.step(2, walking, 1, 1, 1, StepVerdict.NO_GAIT)
        SensorTrace.stop()

        val streamed = StringBuilder()
        SensorTrace.writeCsv(streamed)

        assertEquals(SensorTrace.csv(), streamed.toString())
    }

    @Test
    fun startingAgainDiscardsThePreviousRun() {
        SensorTrace.start(TraceSessionMetadata(extras = mapOf("old" to "gone")))
        SensorTrace.sample(Accel(1, 0f, 0f, 9.81f), walking)
        SensorTrace.start()

        assertEquals(0, SensorTrace.state.value.rows)
        assertTrue(SensorTrace.state.value.recording)
        assertNull(rows().singleOrNull { it["metadataKey"] == "extra.old" })
    }

    /**
     * A recording forgotten on the sensor page stops itself once it has run for as long as the service
     * keeps the processor awake for it, and the file says where it ended, rather than running on at
     * 25 rows a second for as long as the process lives.
     */
    @Test
    fun aForgottenRecordingStopsItselfAtItsLimit() {
        val limitNanos = SensorTrace.MAX_RECORDING_MS * 1_000_000L
        SensorTrace.start()
        SensorTrace.sample(Accel(5_000, 0f, 0f, 9.81f), walking)
        SensorTrace.sample(Accel(5_000 + limitNanos, 0f, 0f, 9.81f), walking)
        assertTrue(SensorTrace.state.value.recording)

        val feed = DevState(reading = walking, rawCounter = 4_210, credited = 1_830, rejected = 40)
        SensorTrace.sample(Accel(5_001 + limitNanos, 0f, 0f, 9.81f), walking, feed = feed)

        assertFalse(SensorTrace.state.value.recording)
        assertEquals(2, rows().count { it["kind"] == "S" })
        val limit = rows().single { it["metadataKey"] == "session.limitReachedAtNanos" }
        assertEquals("${5_001 + limitNanos}", limit["metadataValue"])
        // The counters a Stop by hand writes: the file of a recording that ran out has its own after too.
        val stop = rows().filter { it["metadataKey"].orEmpty().startsWith("counter.stop") }
            .associate { it["metadataKey"] to it["metadataValue"] }
        assertEquals(
            mapOf("counter.stopRaw" to "4210", "counter.stopCredited" to "1830", "counter.stopRejected" to "40"),
            stop,
        )
    }

    private fun rows(): List<Map<String, String>> {
        val parsed = SensorTrace.csv().trimEnd().lines().map(::parseCsvLine)
        val header = parsed.first()
        return parsed.drop(1).map { header.zip(it).toMap() }
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            when {
                line[index] == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    value.append('"')
                    index++
                }

                line[index] == '"' -> quoted = !quoted

                line[index] == ',' && !quoted -> {
                    values += value.toString()
                    value.clear()
                }

                else -> value.append(line[index])
            }
            index++
        }
        values += value.toString()
        return values
    }
}
