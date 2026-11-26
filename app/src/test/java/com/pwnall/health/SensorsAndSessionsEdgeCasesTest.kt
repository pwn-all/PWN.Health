package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * The edges of what a phone measures on its own: an accelerometer trace, the repetitions and jumps
 * read back out of one, and a pulse read off a chest.
 *
 * Every fixture is built on an exact sample period, so a flight, a gap or a window in these tests
 * is a whole number of samples rather than something the fixture rounded. That is what lets a
 * boundary be tested from both sides instead of from somewhere near it.
 */
class SensorsAndSessionsEdgeCasesTest {

    /** 50 Hz written as whole nanoseconds: an exact period is what keeps the boundaries exact. */
    private val periodNanos = 20_000_000L

    private val rope = REP_PROFILES.getValue("JUMP_ROPE")
    private val squats = REP_PROFILES.getValue("SQUATS")

    private val lyingStill = MotionReading(motion = Motion.STILL, posture = Posture.FLAT)

    /** The recorder is a singleton, so a test that leaves it running would record the next one. */
    @After
    fun stopRecording() {
        SensorTrace.stop()
    }

    private fun trace(values: List<Double>): List<Accel> =
        values.mapIndexed { i, v -> Accel(i * periodNanos, 0f, 0f, v.toFloat()) }

    /**
     * A jump as the accelerometer records it: standing at a g, a push-off well above it, nothing at
     * all while airborne, then a landing. The air is given in samples, so the flight measured from
     * it is exactly that many sample periods and no rounding stands between the two.
     */
    private fun jump(airborneSamples: Int): List<Accel> =
        trace(
            List(50) { 9.81 } + List(3) { 22.0 } + List(airborneSamples) { 0.4 } +
                List(3) { 30.0 } + List(50) { 9.81 },
        )

    private fun landings(count: Int, everySamples: Int): List<Accel> =
        trace(List(count) { if (it % everySamples == 0) 25.0 else 9.81 })

    /**
     * Eight samples of air at 50 Hz is 0.16 s, just past the 0.15 s floor; seven is 0.14 s, which
     * the constant's own comment calls the noise between two footfalls. Cross that line the wrong
     * way and every walk ends with a three-centimetre "jump" printed under it.
     */
    @Test
    fun `the shortest flight that counts, and the one just under it`() {
        // h = g·t²/8 — the flight-time formula the function's KDoc names, computed from the flight
        // the fixture builds rather than from anything the implementation returns.
        assertEquals(9.81 * 0.16 * 0.16 / 8.0, jumpHeightM(jump(airborneSamples = 8)), 1e-4)
        assertEquals(0.0, jumpHeightM(jump(airborneSamples = 7)), 1e-9)
    }

    /**
     * 0.88 s in the air is a 95 cm jump, near the tallest number this feature can ever print; a
     * full second of free fall is a phone thrown or dropped down a stairwell, not a person.
     */
    @Test
    fun `an implausibly long flight is not a jump`() {
        assertEquals(9.81 * 0.88 * 0.88 / 8.0, jumpHeightM(jump(airborneSamples = 44)), 1e-3)
        assertEquals(0.0, jumpHeightM(jump(airborneSamples = 46)), 1e-9)
    }

    /**
     * A flight is measured from the two timestamps that bracket it, so samples missing inside it
     * neither create a jump nor destroy one: a push-off, free fall at both ends of a 0.4 s hole and
     * a landing describe 0.42 s in the air whether or not anything was recorded in between. The
     * height is what those two stamps say it is, not what the sample count says.
     */
    @Test
    fun `a flight measured across a hole is still measured from its timestamps`() {
        val head = trace(List(25) { 9.81 } + listOf(20.0))
        val firstAir = Accel(head.last().t + periodNanos, 0f, 0f, 0.4f)
        val hole = 400_000_000L
        val tail = (listOf(0.4, 30.0) + List(25) { 9.81 })
            .mapIndexed { i, v -> Accel(firstAir.t + hole + i * periodNanos, 0f, 0f, v.toFloat()) }

        // 0.40 s between the two free-fall samples, plus the one median period of air the function
        // adds for the half-interval at each end of the run.
        assertEquals(9.81 * 0.42 * 0.42 / 8.0, jumpHeightM(head + firstAir + tail), 1e-4)
    }

    /** A phone on a table with a sensor reporting no noise at all: one long plateau, no movement. */
    @Test
    fun `a perfectly flat signal counts zero repetitions`() {
        val table = trace(List(1_000) { 9.81 })

        REP_PROFILES.forEach { (code, profile) ->
            assertEquals(code, RepCount(0, 0.0, 0.0, 0.0), RepCounter.count(table, profile))
        }
    }

    /** A phone sliding out of a pocket repeats nothing, however large the total excursion is. */
    @Test
    fun `a slow drift is not a set of repetitions`() {
        val sliding = trace(List(1_000) { 9.81 + it * (25.0 - 9.81) / 999 })

        val result = RepCounter.count(sliding, rope)

        assertEquals("was ${result.reps}", 0, result.reps)
        assertEquals(0.0, result.bestJumpM, 1e-9)
    }

    /** One peak yields no interval, so there is no rate to report — and 0/0 is not a rate. */
    @Test
    fun `one impact is one repetition with no cadence at all`() {
        val values = MutableList(200) { 9.81 }
        values[100] = 30.0
        val single = trace(values)

        val jumped = RepCounter.count(single, rope)

        assertEquals(1, jumped.reps)
        assertEquals(0.0, jumped.activeSeconds, 1e-9)
        assertEquals("cadence was ${jumped.cadenceHz}", 0.0, jumped.cadenceHz, 1e-9)
        // Half a squat is not a squat: a rotation shows both peaks of the movement or neither.
        assertEquals(0, RepCounter.count(single, squats).reps)
    }

    /**
     * A driver that stamps a whole FIFO batch with one timestamp. Every gap is then zero, so the
     * median sample period is zero, and it is the divisor of the cadence and of the contact window.
     */
    @Test
    fun `a batch delivered with one repeated timestamp`() {
        val batch = List(60) { if (it % 10 == 0) 30.0 else 9.81 }
            .map { Accel(1_000L, 0f, 0f, it.toFloat()) }

        val result = RepCounter.count(batch, rope)

        assertTrue("was ${result.reps}", result.reps <= 1)
        assertEquals(0.0, result.activeSeconds, 1e-9)
        assertEquals("cadence was ${result.cadenceHz}", 0.0, result.cadenceHz, 1e-9)
        assertEquals(0.0, result.bestJumpM, 1e-9)
        assertEquals(0.0, jumpHeightM(batch), 1e-9)
        assertEquals(0.0, batch.spanMinutes(), 1e-9)
    }

    /** A trace spanning a reboot, or two files concatenated: the clock restarts mid-recording. */
    @Test
    fun `timestamps that go backwards mid-recording`() {
        val twice = landings(count = 200, everySamples = 25) + landings(count = 200, everySamples = 25)

        val result = RepCounter.count(twice, rope)

        assertTrue("active ${result.activeSeconds}", result.activeSeconds >= 0.0)
        assertTrue("cadence ${result.cadenceHz}", result.cadenceHz >= 0.0)
        assertFalse("cadence ${result.cadenceHz}", result.cadenceHz.isNaN())
        // Sixteen landings are present in the two halves together, and no count may exceed them.
        assertTrue("reps ${result.reps}", result.reps <= 16)
    }

    /** Nothing can be said about a period, a cadence or a flight from fewer than three samples. */
    @Test
    fun `too few samples to say anything`() {
        val tiny = listOf(emptyList<Accel>(), trace(listOf(9.81)), trace(listOf(9.81, 25.0)))

        tiny.forEach { samples ->
            REP_PROFILES.forEach { (code, profile) ->
                assertEquals(code, RepCount(0, 0.0, 0.0, 0.0), RepCounter.count(samples, profile))
            }
            assertEquals(0.0, jumpHeightM(samples), 1e-9)
        }
    }

    /** A recording covers the same minutes whichever end of it the list starts from. */
    @Test
    fun `the span of a recording that runs backwards or holds one sample`() {
        val start = Accel(0, 0f, 0f, 9.81f)
        val minuteLater = Accel(60_000_000_000L, 0f, 0f, 9.81f)

        assertEquals(0.0, emptyList<Accel>().spanMinutes(), 1e-12)
        assertEquals(0.0, listOf(start).spanMinutes(), 1e-12)
        assertEquals(1.0, listOf(start, minuteLater).spanMinutes(), 1e-12)
        assertEquals(1.0, listOf(minuteLater, start).spanMinutes(), 1e-12)
    }

    /**
     * A rotation peaks twice per repetition by the enum's own definition — down and up — and an
     * impact once. Adding an exercise is one map entry, and getting that field wrong halves or
     * doubles every count for it with nothing else in the app to notice.
     */
    @Test
    fun `every rep profile describes a movement that can exist`() {
        REP_PROFILES.forEach { (code, profile) ->
            assertTrue(code, profile.minIntervalSeconds > 0.0)
            assertTrue(code, profile.minIntervalSeconds < profile.maxIntervalSeconds)
            assertTrue(code, profile.minPeakAboveRest > 0.0)
            assertTrue(code, profile.smoothingSeconds > 0.0)
            assertEquals(code, if (profile.signal == RepSignal.ROTATION) 2 else 1, profile.peaksPerRep)
        }
        assertTrue(REP_PROFILES.values.any { it.signal == RepSignal.ROTATION })
        assertTrue(REP_PROFILES.values.any { it.signal == RepSignal.IMPACT })
    }

    /**
     * The recorder and the offline analyser are two halves of one workflow, and only a round trip
     * holds them together: a column inserted into the header, or a quoted metadata value, silently
     * shifts which number is read as which axis.
     */
    @Test
    fun `the trace the recorder writes is the trace the analyser reads`() {
        SensorTrace.start(TraceSessionMetadata(extras = mapOf("note" to "one, two")))
        SensorTrace.sample(Accel(1_000, 0.5f, -1.25f, 9.8125f), lyingStill, 1_500)
        SensorTrace.sample(Accel(21_000_000, 0.25f, 0f, 9.8125f), lyingStill)
        SensorTrace.step(30_000_000, lyingStill, 10, 1, 1, StepVerdict.CREDITED)
        SensorTrace.sample(Accel(41_000_000, -0.5f, 1.5f, 9.75f), lyingStill)
        SensorTrace.stop()

        val samples = RepCounter.samplesFromTraceCsv(SensorTrace.csv())

        assertEquals(listOf(1_000L, 21_000_000L, 41_000_000L), samples.map { it.t })
        assertEquals(0.5f, samples[0].x, 1e-4f)
        assertEquals(-1.25f, samples[0].y, 1e-4f)
        assertEquals(9.8125f, samples[0].z, 1e-4f)
        assertEquals(-0.5f, samples[2].x, 1e-4f)
        assertEquals(1.5f, samples[2].y, 1e-4f)
    }

    /** A trace is evidence recovered from a phone, and half of one is the normal case. */
    @Test
    fun `a truncated or malformed trace produces no samples rather than an exception`() {
        SensorTrace.start()
        SensorTrace.sample(Accel(1_000, 0.5f, 0f, 9.81f), lyingStill)
        SensorTrace.sample(Accel(2_000, 0.3125f, 0f, 9.81f), lyingStill)
        SensorTrace.stop()
        val csv = SensorTrace.csv()
        val lines = csv.trimEnd().lines()
        val sampleRows = lines.filter { it.startsWith("S,") }

        assertEquals(emptyList<Accel>(), RepCounter.samplesFromTraceCsv(""))
        assertEquals(emptyList<Accel>(), RepCounter.samplesFromTraceCsv(sampleRows.joinToString("\n")))
        assertEquals(emptyList<Accel>(), RepCounter.samplesFromTraceCsv(csv.replace(",x,", ",xx,")))
        val damaged = listOf(
            lines.first(),
            sampleRows.first().split(',').take(4).joinToString(","),
            sampleRows.last().replace(",0.3125,", ",,"),
        )
        assertEquals(emptyList<Accel>(), RepCounter.samplesFromTraceCsv(damaged.joinToString("\n")))
        // The same file with its final newline gone is still two rows.
        assertEquals(2, RepCounter.samplesFromTraceCsv(csv.trimEnd()).size)
    }

    /**
     * A recording that saw no step event has no raw counter to report, and a written 0 would be
     * indistinguishable from a genuine just-after-boot reading — which is what the KDoc says.
     */
    @Test
    fun `a recording that saw no step event writes no stop counter at all`() {
        SensorTrace.start(
            TraceSessionMetadata(startRawCounter = 900, startCredited = 10, startRejected = 2),
        )
        SensorTrace.stopCounters(DevState(rawCounter = 0, credited = 10, rejected = 2))
        SensorTrace.stop()

        val written = metadata()

        assertEquals("900", written["counter.startRaw"])
        assertEquals("10", written["counter.startCredited"])
        assertEquals("2", written["counter.startRejected"])
        assertEquals("10", written["counter.stopCredited"])
        assertEquals("2", written["counter.stopRejected"])
        assertFalse("stopRaw was ${written["counter.stopRaw"]}", "counter.stopRaw" in written)
    }

    /**
     * A trace is evidence about one recording session. Four entry points can add to it and each
     * carries its own guard, so a missing one would let the next session's counters land in the
     * previous session's file.
     */
    @Test
    fun `a finished recording cannot be added to`() {
        SensorTrace.start()
        SensorTrace.stop()

        SensorTrace.metadata("extra.late", "x")
        SensorTrace.stopCounters(DevState(rawCounter = 5, credited = 5))
        SensorTrace.sample(Accel(1, 0f, 0f, 9.81f), lyingStill)
        SensorTrace.step(2, lyingStill, 1, 1, 1, StepVerdict.CREDITED)

        val csv = SensorTrace.csv()
        assertFalse(csv, csv.contains("extra.late"))
        assertFalse(csv, csv.contains("counter.stop"))
        assertTrue(csv, csv.lineSequence().none { it.startsWith("S,") || it.startsWith("P,") })
        assertFalse(SensorTrace.state.value.recording)
        assertEquals(0, SensorTrace.state.value.rows)
    }

    /**
     * Half a window is not a duration. A coverage that defaulted to 0 would make a window with no
     * usable samples read back as an instantaneous one when the trace is replayed.
     */
    @Test
    fun `a window with only one boundary known has no coverage`() {
        assertNull(TraceWindowMetadata(firstSampleTimestampNanos = 500).coverageNanos)
        assertNull(TraceWindowMetadata(lastSampleTimestampNanos = 500).coverageNanos)
        assertEquals(
            -1_400L,
            TraceWindowMetadata(
                firstSampleTimestampNanos = 1_900,
                lastSampleTimestampNanos = 500,
            ).coverageNanos,
        )
    }

    /**
     * A chest recording: gravity, a slow large breath, a small fast pulse, and noise with no period
     * of its own. The pulse is the smallest thing in it, which is what makes this hard.
     */
    private fun chest(bpm: Int, seconds: Double = 40.0, rate: Double = 29.7): List<Accel> {
        val noise = Random(bpm.toLong())
        return (0 until (seconds * rate).toInt()).map { i ->
            val t = i / rate
            val v = 9.81 + 0.12 * sin(2 * PI * 0.25 * t) +
                0.03 * sin(2 * PI * bpm / 60 * t) + noise.nextGaussian() * 0.015
            Accel((t * 1e9).toLong(), 0f, 0f, v.toFloat())
        }
    }

    /**
     * A limit that reports past itself is not one — the comment above the lag search says exactly
     * that. Whatever the phone believes it heard, the number written into a health journal has to
     * be one the configured band admits, or be refused.
     */
    @Test
    fun `a reported pulse never lies outside the configured band`() {
        val config = Ballistocardiogram.Config()

        listOf(150, 175, 30).forEach { bpm ->
            val result = Ballistocardiogram.estimate(chest(bpm))
            if (result is Ballistocardiogram.Result.Ok) {
                val measured = result.estimate.bpm
                assertTrue(
                    "a $bpm bpm recording read $measured, outside ${config.minBpm}..${config.maxBpm}",
                    measured in config.minBpm..config.maxBpm,
                )
            }
        }
    }

    /**
     * Three overlapping 15 s windows at a 3 s step need 21 s of recording, so 18 s of the cleanest
     * chest trace there is still has nothing for the median to lean on and cannot produce a number.
     *
     * Which [Ballistocardiogram.Failure] it refuses with is deliberately not asserted: TOO_SHORT and
     * NO_RHYTHM print the same sentence on the screen, so the distinction is not a promise to anyone.
     */
    @Test
    fun `a recording too short to fill three windows produces no reading`() {
        val result = Ballistocardiogram.estimate(chest(66, seconds = 18.0))

        assertTrue("18 s read $result", result is Ballistocardiogram.Result.Poor)
    }

    /** The metadata rows of the current trace. No value written by these tests needs quoting. */
    private fun metadata(): Map<String, String> = SensorTrace.csv()
        .lineSequence()
        .filter { it.startsWith("M,") }
        .map { it.split(',') }
        .associate { it[it.size - 2] to it.last() }
}
