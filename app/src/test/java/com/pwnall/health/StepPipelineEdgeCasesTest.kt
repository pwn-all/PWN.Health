package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The step pipeline where the hardware lies: a timestamp from the wrong clock base, a flush that
 * stamps two samples alike, a doze long enough to make any number of steps look plausible, and one
 * NaN in the middle of a walk.
 *
 * Every expectation here comes from what the pipeline promises — never credit steps the sensor did
 * not report, never let missing evidence delete steps it did — rather than from what the
 * arithmetic happens to produce.
 */
class StepPipelineEdgeCasesTest {

    private val boot = 1_780_000_000_000L
    private val laterBoot = boot + 3_600_000L
    private val day17 = "2026-08-17"
    private val day18 = "2026-08-18"
    private val day19 = "2026-08-19"
    private val day20 = "2026-08-20"

    @Before
    fun clearFeedBefore() = SensorFeed.resetForTest()

    @After
    fun clearFeedAfter() = SensorFeed.resetForTest()

    // --- the counter -----------------------------------------------------------------------

    /**
     * A handful of drivers stamp `TYPE_STEP_COUNTER` with CLOCK_REALTIME epoch nanos instead of
     * nanos since boot. One such event drags the trusted clock 56 years forward, and every honest
     * event after it then looks like a clock running backwards. The cost of the glitch must be the
     * steps inside it, not every step the device takes until somebody reboots it.
     */
    @Test
    fun `a timestamp from the wrong clock base does not mute the pedometer for ever`() {
        val glitch = StepAccumulator.accumulate(
            confirmedBout(timeNanos = 3_000_000_000L),
            rawCounter = 105,
            bootId = boot,
            eventTimestampNanos = 1_780_000_000_000_000_000L,
            gaitEvidence = true,
            day = day18,
        )

        var state = glitch.state
        var credited = 0L
        val verdicts = mutableListOf<StepVerdict>()
        repeat(5) { index ->
            val update = StepAccumulator.accumulate(
                state,
                rawCounter = 107 + 2L * index,
                bootId = boot,
                eventTimestampNanos = 4_000_000_000L + index * 1_000_000_000L,
                gaitEvidence = true,
                day = day18,
            )
            state = update.state
            credited += update.creditedDelta
            verdicts += update.verdict
        }

        assertFalse("verdicts were $verdicts", verdicts.all { it == StepVerdict.NON_MONOTONIC_TIME })
        assertTrue("credited $credited after the glitch", credited > 0L)
    }

    /**
     * A sensor hub can crash and restart while the application processor stays up: the counter
     * comes back at zero under the same boot id. That rebase is untrusted, so the walk it
     * interrupted cannot survive it — a bout left confirmed would credit the very next reading.
     */
    @Test
    fun `a counter that restarts without a reboot forgets the bout it was in`() {
        val interrupted = StepState(
            bootId = boot,
            lastCumulative = 45_000,
            lastEventNanos = 1_000_000_000L,
            pendingSteps = 2,
            boutStartedNanos = 500_000_000L,
            boutLastNanos = 1_000_000_000L,
            boutConfirmed = true,
            pendingDay = day18,
            pendingDaySteps = 2,
        )

        val rebased = StepAccumulator.accumulate(interrupted, 0, boot, 2_000_000_000L, true, day18)
        val next = StepAccumulator.accumulate(rebased.state, 5, boot, 3_000_000_000L, true, day18)

        assertEquals(StepVerdict.COUNTER_RESET, rebased.verdict)
        assertEquals(0L, rebased.creditedDelta)
        assertFalse(rebased.state.boutConfirmed)
        assertEquals(0L, rebased.state.pendingSteps)
        assertEquals("", rebased.state.pendingDay)
        assertEquals(0L, next.creditedDelta)
        assertEquals(StepVerdict.BOUT_PENDING, next.verdict)
    }

    /**
     * The plausibility budget is five steps a second times the gap since the previous event, so a
     * phone that dozed for three days wakes up with a ceiling above a million steps — that scaling
     * is deliberate, because a batching hub really does deliver a whole doze in one cumulative
     * jump. What must not happen is the ceiling disappearing with it: a delta past the scaled
     * budget is still refused, and the refusal must not carry into the events that follow.
     */
    @Test
    fun `a doze scales the plausibility budget but does not remove it`() {
        val threeDays = 259_200_000_000_000L
        val jump = StepAccumulator.accumulate(
            confirmedBout(timeNanos = 3_000_000_000L),
            rawCounter = 1_400_100,
            bootId = boot,
            eventTimestampNanos = 3_000_000_000L + threeDays,
            gaitEvidence = true,
            day = day20,
        )
        val next = StepAccumulator.accumulate(
            jump.state,
            rawCounter = 1_400_102,
            bootId = boot,
            eventTimestampNanos = 4_000_000_000L + threeDays,
            gaitEvidence = true,
            day = day20,
        )

        assertEquals(StepVerdict.IMPOSSIBLE_RATE, jump.verdict)
        assertEquals(0L, jump.creditedDelta)
        assertTrue("credited ${next.creditedDelta}", next.creditedDelta < 1_000L)
    }

    /**
     * A coarse flush settles when a separate bout begins. If it settles onto the arriving event's
     * day, a walk taken before midnight lands on the day after it, and both day totals are wrong.
     */
    @Test
    fun `a batch that expires after midnight is credited to the day it was walked on`() {
        val baseline = StepAccumulator.accumulate(null, 100, boot, 1_000_000_000L, true, day18).state
        val flush = StepAccumulator.accumulate(baseline, 103, boot, 11_000_000_000L, true, day18)
        val nextWalk = StepAccumulator.accumulate(flush.state, 104, boot, 45_000_000_000L, true, day19)

        assertEquals(0L, flush.creditedDelta)
        assertEquals(3L, nextWalk.creditedDelta)
        assertEquals(mapOf(day18 to 3L), nextWalk.creditedByDay)
    }

    /** Once a bout is confirmed the pending-day fields are stale, and stale must not be published. */
    @Test
    fun `a confirmed bout crossing midnight credits each event to its own day`() {
        val stale = confirmedBout(pendingDay = day17, pendingDaySteps = 99)
        val before = StepAccumulator.accumulate(stale, 102, boot, 1_500_000_000L, true, day18)
        val after = StepAccumulator.accumulate(before.state, 105, boot, 2_000_000_000L, true, day19)

        assertEquals(2L, before.creditedDelta)
        assertEquals(mapOf(day18 to 2L), before.creditedByDay)
        assertEquals(3L, after.creditedDelta)
        assertEquals(mapOf(day19 to 3L), after.creditedByDay)
    }

    /**
     * The per-day map is written to the database independently of the total, so a row from an older
     * schema or a torn write must not be able to inflate a day for ever.
     */
    @Test
    fun `a corrupt persisted day split never credits more than the bout holds`() {
        val torn = StepState(
            bootId = boot,
            lastCumulative = 100,
            lastEventNanos = 1_000_000_000L,
            pendingSteps = 2,
            boutStartedNanos = 500_000_000L,
            boutLastNanos = 1_000_000_000L,
            pendingDay = day18,
            pendingDaySteps = 999,
        )

        val update = StepAccumulator.accumulate(torn, 101, boot, 1_500_000_000L, true, day19)

        assertEquals(3L, update.creditedDelta)
        assertEquals(3L, update.creditedByDay.values.sum())
        assertTrue("split was ${update.creditedByDay}", update.creditedByDay.values.all { it > 0L })
    }

    /**
     * Every rejection branch builds its own state by hand, and the rule that ties them together is
     * written down nowhere: whatever the sensor does, the app cannot credit more steps than the
     * counter moved forward inside one boot session.
     */
    @Test
    fun `a hostile stream never credits more steps than the counter moved`() {
        val stream = hostileStream()
        var state: StepState? = null
        var credited = 0L

        stream.forEach { reading ->
            val update = StepAccumulator.accumulate(
                state,
                reading.counter,
                reading.bootId,
                reading.nanos,
                true,
                reading.day,
            )
            state = update.state
            credited += update.creditedDelta
            assertTrue("${update.verdict} credited ${update.creditedDelta}", update.creditedDelta >= 0L)
            assertTrue(
                "${update.verdict} credited ${update.creditedDelta}",
                update.creditedDelta == 0L || update.verdict == StepVerdict.CREDITED,
            )
            assertEquals(update.creditedDelta, update.creditedByDay.values.sum())
        }

        val forward = stream.zipWithNext()
            .filter { (before, after) -> before.bootId == after.bootId }
            .sumOf { (before, after) -> (after.counter - before.counter).coerceAtLeast(0L) }
        assertTrue("credited $credited of $forward moved", credited in 1L..forward)
    }

    /**
     * 300 steps a minute is the documented ceiling and one extra step is allowed for two boundary
     * events landing in the same timing bucket, so the line sits at six steps in a second. Below it
     * is ordinary running; a floor instead of a ceiling here would discard the run.
     */
    @Test
    fun `six steps in a second are plausible and seven are not`() {
        val walking = confirmedBout()
        val six = StepAccumulator.accumulate(walking, 106, boot, 2_000_000_000L, true)
        val seven = StepAccumulator.accumulate(walking, 107, boot, 2_000_000_000L, true)

        assertEquals(StepVerdict.CREDITED, six.verdict)
        assertEquals(6L, six.creditedDelta)
        assertEquals(StepVerdict.IMPOSSIBLE_RATE, seven.verdict)
        assertEquals(0L, seven.creditedDelta)
    }

    /**
     * A rejected event still advances the counter while the window anchor stays behind, so the
     * refused steps keep counting against the budget. They may do so until the window rolls; one
     * lying callback must not mute the pedometer for the rest of the walk.
     */
    @Test
    fun `a refused burst does not mute crediting past the rate window`() {
        val burst = StepAccumulator.accumulate(confirmedBout(), 120, boot, 1_100_000_000L, true)
        val soonAfter = StepAccumulator.accumulate(burst.state, 121, boot, 4_000_000_000L, true)
        val later = StepAccumulator.accumulate(soonAfter.state, 122, boot, 7_000_000_000L, true)

        assertEquals(StepVerdict.IMPOSSIBLE_RATE, burst.verdict)
        assertEquals(0L, burst.creditedDelta)
        assertEquals(StepVerdict.CREDITED, later.verdict)
        assertEquals(1L, later.creditedDelta)
    }

    /**
     * bootId is the wall clock minus the uptime, so an NTP correction moves it without a reboot.
     * A shift smaller than the session's own uptime cannot be a reboot, and a minute of jitter is
     * tolerated outright — the uptime comparison is a nanos-to-millis division, a factor of a
     * thousand away from turning every clock correction into a lost walk.
     */
    @Test
    fun `sixty seconds of clock jitter is not a reboot`() {
        val threeHours = 3 * 60 * 60 * 1_000_000_000L
        val tolerated = afterBootShift(threeHours, 60_000L)
        val correctedClock = afterBootShift(threeHours, 60_001L)
        val youngSession = afterBootShift(5_000_000_000L, 60_001L)

        assertEquals(1L, tolerated.creditedDelta)
        assertEquals(1L, correctedClock.creditedDelta)
        assertEquals(StepVerdict.REBOOT_BASELINE, youngSession.verdict)
        assertEquals(0L, youngSession.creditedDelta)
    }

    // --- the motion gate -------------------------------------------------------------------

    /**
     * One bad accelerometer value poisons the mean, the spread and the tilt at once, and every
     * comparison against a NaN is false. A window the gate cannot evaluate is missing evidence, and
     * missing evidence must leave the hardware pedometer alone rather than reporting a positive
     * finding of "not a stride" that discards the flush.
     */
    @Test
    fun `one NaN sample must not delete a flush of steps`() {
        val walk = walkWindow()
        val glitched = walk.mapIndexed { index, sample ->
            if (index == 40) sample.copy(y = Float.NaN) else sample
        }

        val reading = MotionGate.analyse(glitched, screenOn = false)

        assertEquals(Motion.UNKNOWN, reading.motion)
        assertTrue("motion was ${reading.motion}", reading.stepsAllowed)
    }

    /** A batching hub delivers two samples in one flush and stamps them alike. That is normal. */
    @Test
    fun `a flush that stamps two samples alike cannot veto the pedometer`() {
        val walk = walkWindow()
        val flushed = walk.mapIndexed { index, sample ->
            if (index == 31) sample.copy(t = walk[30].t) else sample
        }

        val reading = MotionGate.analyse(flushed, screenOn = false)

        assertEquals(Motion.UNKNOWN, reading.motion)
        assertTrue("motion was ${reading.motion}", reading.stepsAllowed)
    }

    /**
     * Flat is decided at both ends of the tilt range, and a phone face down in a bag reads 180°.
     * Getting the mirrored half wrong turns every face-down walk into a bus ride.
     */
    @Test
    fun `walking with the phone face down is still walking`() {
        val reading = MotionGate.analyse(
            sineWindow(hz = 2f, amplitude = 4f, samples = 75, intervalNanos = 40_000_000L, axis = 'z', sign = -1f),
            screenOn = false,
        )

        assertTrue("tilt was ${reading.tiltDeg}", reading.tiltDeg > 150f)
        assertEquals(Posture.FLAT, reading.posture)
        assertEquals("std was ${reading.std}", Motion.WALKING, reading.motion)
        assertTrue(reading.stepsAllowed)
    }

    /**
     * The line between a phone in a cupholder and a phone in a flat jacket pocket is 1.5 m/s² of
     * spread. A 2.0 m/s² rhythm sits below it and a 2.3 m/s² one above; a slip here either counts a
     * bus ride as a walk or throws away every walk with the phone lying flat.
     */
    @Test
    fun `the ride threshold sits between a cupholder and a flat pocket`() {
        val cupholder = MotionGate.analyse(flatRhythm(amplitude = 2.0f), screenOn = false)
        val jacketPocket = MotionGate.analyse(flatRhythm(amplitude = 2.3f), screenOn = false)

        assertEquals("std was ${cupholder.std}", Motion.VEHICLE, cupholder.motion)
        assertFalse(cupholder.stepsAllowed)
        assertEquals("std was ${jacketPocket.std}", Motion.WALKING, jacketPocket.motion)
        assertTrue(jacketPocket.stepsAllowed)
    }

    /**
     * A brief burst at running cadence is not a running interval, and the minimum is inclusive. The
     * window's span is measured between its first and last sample, which is one sample shorter than
     * the number of samples suggests — the natural place for an off-by-one.
     */
    @Test
    fun `a run has to last five seconds`() {
        val fiveSeconds = MotionGate.analyse(runningWindow(samples = 126), screenOn = false)
        val justUnder = MotionGate.analyse(runningWindow(samples = 125), screenOn = false)

        assertEquals("cadence ${fiveSeconds.cadenceHz}", Motion.RUNNING, fiveSeconds.motion)
        assertEquals("cadence ${justUnder.cadenceHz}", Motion.WALKING, justUnder.motion)
        assertTrue(fiveSeconds.stepsAllowed && justUnder.stepsAllowed)
    }

    /**
     * Two independent minimums guard the same door with a `<`, so a window that exactly meets both
     * has to be usable — otherwise the gate reports UNKNOWN for data it was given enough of.
     */
    @Test
    fun `a window that exactly meets the minimums is usable`() {
        val sixteenSamples = sineWindow(hz = 1f, amplitude = 4f, samples = 16, intervalNanos = 200_000_000L)
        val fifteenSamples = sineWindow(hz = 1f, amplitude = 4f, samples = 15, intervalNanos = 200_000_000L)
        val twoAndAHalfSeconds = sineWindow(hz = 1.5f, amplitude = 4f, samples = 26, intervalNanos = 100_000_000L)
        val justShorter = sineWindow(hz = 1.5f, amplitude = 4f, samples = 25, intervalNanos = 100_000_000L)

        assertEquals(Motion.WALKING, MotionGate.analyse(sixteenSamples, screenOn = false).motion)
        assertEquals(Motion.UNKNOWN, MotionGate.analyse(fifteenSamples, screenOn = false).motion)
        assertEquals(Motion.WALKING, MotionGate.analyse(twoAndAHalfSeconds, screenOn = false).motion)
        assertEquals(Motion.UNKNOWN, MotionGate.analyse(justShorter, screenOn = false).motion)
    }

    /**
     * The service and the sensor screen both register for the accelerometer and receive the same
     * hardware events. A sample arriving twice must be a no-op: counted twice it halves the
     * apparent sample gaps, which moves the measured cadence by a factor of two.
     */
    @Test
    fun `a second listener replaying the same samples changes nothing`() {
        val walk = walkWindow()
        walk.forEach { SensorFeed.add(it, screenOn = false) }
        val once = SensorFeed.decisionAt(walk.last().t)
        walk.forEach { SensorFeed.add(it, screenOn = false) }
        val twice = SensorFeed.decisionAt(walk.last().t)

        assertEquals(Motion.WALKING, once.reading.motion)
        assertEquals(walk.size, once.window.sampleCount)
        assertEquals(once.window.sampleCount, twice.window.sampleCount)
        assertEquals(once.window.maxGapNanos, twice.window.maxGapNanos)
        assertEquals(once.reading.motion, twice.reading.motion)
    }

    // --- fixtures --------------------------------------------------------------------------

    private data class Reading(
        val counter: Long,
        val bootId: Long,
        val nanos: Long,
        val day: String,
    )

    /** Baseline, a shared-timestamp flush, a rewind, a burst, a replay, a reset, a reboot, a walk. */
    private fun hostileStream(): List<Reading> = listOf(
        Reading(100, boot, 1_000_000_000L, day18),
        Reading(102, boot, 3_000_000_000L, day18),
        Reading(104, boot, 3_000_000_000L, day18),
        Reading(105, boot, 2_500_000_000L, day18),
        Reading(140, boot, 4_000_000_000L, day18),
        Reading(140, boot, 5_000_000_000L, day18),
        Reading(0, boot, 6_000_000_000L, day18),
        Reading(300, laterBoot, 7_000_000_000L, day19),
        Reading(302, laterBoot, 8_000_000_000L, day19),
        Reading(304, laterBoot, 9_000_000_000L, day19),
        Reading(306, laterBoot, 10_000_000_000L, day19),
    )

    private fun confirmedBout(
        raw: Long = 100,
        timeNanos: Long = 1_000_000_000L,
        pendingDay: String = "",
        pendingDaySteps: Long = 0,
    ) = StepState(
        bootId = boot,
        lastCumulative = raw,
        lastEventNanos = timeNanos,
        boutStartedNanos = timeNanos - 500_000_000L,
        boutLastNanos = timeNanos,
        boutConfirmed = true,
        pendingDay = pendingDay,
        pendingDaySteps = pendingDaySteps,
    )

    private fun afterBootShift(uptimeNanos: Long, shiftMs: Long) = StepAccumulator.accumulate(
        confirmedBout(timeNanos = uptimeNanos),
        rawCounter = 101,
        bootId = boot + shiftMs,
        eventTimestampNanos = uptimeNanos + 1_000_000_000L,
        gaitEvidence = true,
    )

    /** Gravity on one axis plus a sinusoid at [hz]; [sign] flips the device over. */
    private fun sineWindow(
        hz: Float,
        amplitude: Float,
        samples: Int,
        intervalNanos: Long,
        axis: Char = 'y',
        sign: Float = 1f,
    ): List<Accel> = (0 until samples).map { index ->
        val nanos = index * intervalNanos
        val value = sign * (9.81f + amplitude * sin(2f * PI.toFloat() * hz * (nanos / 1e9f)))
        if (axis == 'z') Accel(nanos, 0f, 0f, value) else Accel(nanos, 0f, value, 0f)
    }

    private fun walkWindow() = sineWindow(hz = 2f, amplitude = 3f, samples = 75, intervalNanos = 40_000_000L)

    private fun flatRhythm(amplitude: Float) =
        sineWindow(hz = 2f, amplitude = amplitude, samples = 75, intervalNanos = 40_000_000L, axis = 'z')

    private fun runningWindow(samples: Int) =
        sineWindow(hz = 3f, amplitude = 3f, samples = samples, intervalNanos = 40_000_000L)
}
