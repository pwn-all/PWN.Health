package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The regimes, as tests. Each one is a scenario you would otherwise have to reproduce by walking,
 * sitting on a train, or waving the phone about.
 */
class MotionGateTest {

    /**
     * A synthetic accelerometer window: gravity on [axis] plus a sinusoid at [hz].
     * axis 'z' = screen facing up (flat); axis 'y' = upright (pocket).
     */
    private fun window(
        hz: Float,
        amplitude: Float,
        axis: Char = 'z',
        seconds: Float = 3f,
        rate: Int = 25,
    ): List<Accel> = (0 until (seconds * rate).toInt()).map { i ->
        val t = i / rate.toFloat()
        val v = 9.81f + amplitude * sin(2f * PI.toFloat() * hz * t)
        val nanos = (t * 1e9f).toLong()
        if (axis == 'z') Accel(nanos, 0f, 0f, v) else Accel(nanos, 0f, v, 0f)
    }

    @Test
    fun `walking with the phone in a pocket is counted`() {
        val reading = MotionGate.analyse(window(hz = 2f, amplitude = 3f, axis = 'y'), screenOn = false)
        assertEquals(Motion.WALKING, reading.motion)
        assertEquals(Posture.POCKET, reading.posture)
        assertTrue(reading.stepsAllowed)
    }

    @Test
    fun `walking while looking at the screen is counted`() {
        val reading = MotionGate.analyse(window(hz = 1.8f, amplitude = 2.5f, axis = 'y'), screenOn = true)
        assertEquals(Motion.WALKING, reading.motion)
        assertEquals(Posture.IN_HAND, reading.posture)
        assertTrue(reading.stepsAllowed)
    }

    @Test
    fun `periodic fast gait is classified as running and counted`() {
        val reading = MotionGate.analyse(
            window(hz = 3f, amplitude = 3f, axis = 'y', seconds = 6f),
            screenOn = false,
        )
        assertEquals(Motion.RUNNING, reading.motion)
        assertTrue(reading.periodicity >= 0.35f)
        assertTrue(reading.stepsAllowed)
    }

    @Test
    fun `short fast burst is not yet a confirmed running interval`() {
        val reading = MotionGate.analyse(
            window(hz = 3f, amplitude = 3f, axis = 'y', seconds = 3f),
            screenOn = false,
        )
        assertEquals(Motion.WALKING, reading.motion)
        assertTrue(reading.stepsAllowed)
    }

    @Test
    fun `slow periodic gait is retained`() {
        val reading = MotionGate.analyse(window(hz = 0.9f, amplitude = 1.5f, axis = 'y'), screenOn = false)
        assertEquals(Motion.WALKING, reading.motion)
        assertTrue(reading.stepsAllowed)
    }

    @Test
    fun `a phone lying still is not walking`() {
        val reading = MotionGate.analyse(window(hz = 1f, amplitude = 0.05f), screenOn = false)
        assertEquals(Motion.STILL, reading.motion)
        assertFalse(reading.stepsAllowed)
    }

    @Test
    fun `low amplitude buzz on a flat surface reads as a vehicle, not steps`() {
        // Cupholder / seat / table: engine and rail vibration sits right in the gait band.
        val reading = MotionGate.analyse(window(hz = 2f, amplitude = 1.0f, axis = 'z'), screenOn = false)
        assertEquals(Posture.FLAT, reading.posture)
        assertEquals(Motion.VEHICLE, reading.motion)
        assertFalse(reading.stepsAllowed)
    }

    @Test
    fun `shaking the phone is rejected`() {
        // |a| folds the negative half of the drive, so the measured std lands well below the drive
        // amplitude: 40 m/s² of shake reads as ~14.5 m/s² of std.
        val reading = MotionGate.analyse(window(hz = 6f, amplitude = 40f, axis = 'y'), screenOn = true)
        assertEquals(Motion.SHAKING, reading.motion)
        assertFalse(reading.stepsAllowed)
    }

    @Test
    fun `a moderate shake is still not walking`() {
        // Below the SHAKING threshold it falls through to VEHICLE — a different label, same verdict.
        val reading = MotionGate.analyse(window(hz = 5f, amplitude = 25f, axis = 'y'), screenOn = true)
        assertFalse(reading.stepsAllowed)
    }

    @Test
    fun `slow sway is too slow to be a stride`() {
        // Body roll in a pocket: plenty of energy, nothing like a cadence. Not a stride, and not
        // evidence of a vehicle either — the phone is upright against a leg, not lying on a seat.
        //
        // The sway is slower than 0.5 Hz: the dominant rhythm in a pocket is not the step rate.
        // Recorded walks show that a phone in a pocket feels the stride, so 0.5 Hz is a walk at
        // 60 steps/min, not a sway. The boundary sits where a doubled rhythm still misses the
        // slowest human cadence.
        val reading = MotionGate.analyse(window(hz = 0.35f, amplitude = 3f, axis = 'y'), screenOn = false)
        assertEquals(Motion.NON_GAIT, reading.motion)
        assertFalse(reading.stepsAllowed)
    }

    /** The stride, not the step, is what a pocket feels — and half a step rate is still gait. */
    @Test
    fun `a pocket that only feels the stride is still walking`() {
        // 0.73 Hz is what a recorded walk of 200 steps measured at 1.38 steps/s.
        val reading = MotionGate.analyse(
            window(hz = 0.73f, amplitude = 3.5f, axis = 'y', seconds = 6f),
            screenOn = false,
        )
        assertEquals(Motion.WALKING, reading.motion)
        assertTrue(reading.stepsAllowed)
    }

    /**
     * A normalized autocorrelation is blind to amplitude, so a small buzz correlates with itself as
     * well as a stride does. Only the energy left after smoothing separates them.
     */
    @Test
    fun `a strong high frequency buzz is not gait however periodic it is`() {
        val reading = MotionGate.analyse(window(hz = 9f, amplitude = 5f, axis = 'y'), screenOn = false)
        assertFalse(reading.stepsAllowed)
    }

    @Test
    fun `high frequency vibration is not a stride either`() {
        val reading = MotionGate.analyse(window(hz = 8f, amplitude = 3f, axis = 'y'), screenOn = false)
        assertEquals(Motion.NON_GAIT, reading.motion)
        assertFalse(reading.stepsAllowed)
    }

    @Test
    fun `sub-gait vibration on a flat surface with the screen off is a ride`() {
        // Amplitude taken from a real bus recording, which sat at 0.4 m/s² of magnitude spread —
        // an order of magnitude below the 3.8–7.6 of recorded walks. Energy, not rhythm, is what
        // tells a ride apart: road and engine vibration is thoroughly periodic.
        val reading = MotionGate.analyse(window(hz = 2f, amplitude = 0.6f, axis = 'z'), screenOn = false)
        assertEquals(Posture.FLAT, reading.posture)
        assertEquals(Motion.VEHICLE, reading.motion)
        assertFalse(reading.stepsAllowed)
    }

    @Test
    fun `the same vibration with the screen on is not called a ride`() {
        // A ride is a positive claim. Someone holding the phone is not evidence for one.
        val reading = MotionGate.analyse(window(hz = 8f, amplitude = 3f, axis = 'z'), screenOn = true)
        assertEquals(Motion.NON_GAIT, reading.motion)
        assertFalse(reading.stepsAllowed)
    }

    /**
     * The gate is the second line of defence behind the platform pedometer, not a prerequisite for
     * it. A window too short to classify is missing evidence, and missing evidence must not delete
     * real steps — batched sensor delivery produces exactly this case on every flush.
     */
    @Test
    fun `too little data leaves the pedometer alone`() {
        val reading = MotionGate.analyse(window(hz = 2f, amplitude = 3f, seconds = 0.2f), screenOn = false)
        assertEquals(Motion.UNKNOWN, reading.motion)
        assertTrue(reading.stepsAllowed)
    }

    @Test
    fun `an empty window still leaves the pedometer alone`() {
        assertTrue(MotionGate.analyse(emptyList(), screenOn = false).stepsAllowed)
    }

    @Test
    fun `an energetic walk with the phone flat still counts`() {
        // The flat-surface rule must not veto genuine walking with the phone in a jacket pocket
        // that happens to lie horizontal.
        val reading = MotionGate.analyse(window(hz = 2f, amplitude = 4f, axis = 'z'), screenOn = false)
        assertEquals(Posture.FLAT, reading.posture)
        assertEquals(Motion.WALKING, reading.motion)
        assertTrue(reading.stepsAllowed)
    }

    @Test
    fun `irregular movements while handling the phone are rejected`() {
        val samples = (0 until 100).map { index ->
            val impulse = when (index) {
                7, 21, 22, 48, 71, 90, 91, 92 -> 7f
                13, 35, 62, 83 -> -4f
                else -> 0f
            }
            Accel(index * 40_000_000L, impulse, 9.81f, 0f)
        }
        val reading = MotionGate.analyse(samples, screenOn = true)
        assertFalse(reading.stepsAllowed)
        assertFalse(reading.stepsDoubtful)
    }

    @Test
    fun `irregular pocket jostling is not a walking sequence`() {
        // Impulses on strict 11, 17 and 29 sample cycles would not be jostling at all but three
        // metronomes, and the gate would be right to hear a rhythm in them. Real jostling has gaps
        // that never settle, so these do not.
        val hits = listOf(3, 14, 18, 37, 44, 49, 72, 81, 87)
        val impulses = listOf(7f, -5f, 9f, -4f, 6f, -8f, 5f, -6f, 8f)
        val samples = (0 until 100).map { index ->
            val impulse = hits.indexOf(index).let { if (it < 0) 0f else impulses[it] }
            Accel(index * 40_000_000L, 0f, 9.81f + impulse, 0f)
        }
        val reading = MotionGate.analyse(samples, screenOn = false)
        assertEquals(Posture.POCKET, reading.posture)
        assertFalse(reading.stepsAllowed)
        // Under a walker's energy, so not even a doubt: no walk around it makes it a step.
        assertFalse("std was ${reading.std}", reading.stepsDoubtful)
    }

    /**
     * A walker's energy with too little regularity, as a real stair climb gives it — the phone lying
     * flat in the hand, then on its way into a pocket — is doubted rather than vetoed; a ride, a fast
     * shake, a still phone are not, and walking needs no doubt.
     */
    @Test
    fun `a walker's energy without a walker's regularity is a doubt`() {
        val flatInHand =
            MotionReading(Motion.VEHICLE, Posture.FLAT, std = 2.89f, cadenceHz = 1.98f, periodicity = 0.32f)
        val intoPocket =
            MotionReading(Motion.NON_GAIT, Posture.POCKET, std = 3.22f, cadenceHz = 0.71f, periodicity = 0.39f)
        assertTrue(flatInHand.stepsDoubtful)
        assertTrue(intoPocket.stepsDoubtful)

        assertFalse(MotionReading(Motion.VEHICLE, Posture.FLAT, std = 0.7f, cadenceHz = 2f).stepsDoubtful)
        assertFalse(MotionReading(Motion.NON_GAIT, Posture.IN_HAND, std = 8f, cadenceHz = 5f).stepsDoubtful)
        assertFalse(MotionReading(Motion.STILL, std = 0.1f).stepsDoubtful)
        assertFalse(MotionReading(Motion.WALKING, Posture.POCKET, std = 4f, cadenceHz = 1f).stepsDoubtful)
    }

    @Test
    fun `many samples cannot substitute for enough elapsed coverage`() {
        val compressed = window(hz = 2f, amplitude = 3f, seconds = 0.5f, rate = 200)

        val reading = MotionGate.analyse(compressed, screenOn = false)

        assertTrue(compressed.size > GateConfig().minSamples)
        assertEquals(Motion.UNKNOWN, reading.motion)
    }

    @Test
    fun `a large hole makes an otherwise long window unknown`() {
        val discontinuous = window(hz = 2f, amplitude = 3f, axis = 'y', seconds = 4f)
            .mapIndexed { index, sample ->
                if (index < 50) sample else sample.copy(t = sample.t + 800_000_000L)
            }

        val reading = MotionGate.analyse(discontinuous, screenOn = false)

        assertEquals(Motion.UNKNOWN, reading.motion)
    }

    @Test
    fun `a rhythm needs two observed periods`() {
        val lessThanTwoPeriods = window(
            hz = 0.34f,
            amplitude = 3f,
            axis = 'y',
            seconds = 3.2f,
            rate = 5,
        )
        val config = GateConfig(
            minWindowSeconds = 2.5f,
            maxSampleGapSeconds = 0.21f,
            gaitMinStd = 0.1f,
            gaitMinHz = 0.3f,
            gaitMaxHz = 0.8f,
            periodicityMin = 0f,
            rhythmMinHz = 0.30f,
            rhythmMaxHz = 0.40f,
        )

        val reading = MotionGate.analyse(lessThanTwoPeriods, screenOn = false, config = config)

        assertEquals(0f, reading.cadenceHz)
        assertFalse(reading.motion == Motion.WALKING || reading.motion == Motion.RUNNING)
    }

    @Test
    fun `regular gait is classified across different sample rates`() {
        val slowFeed = MotionGate.analyse(
            window(hz = 1.8f, amplitude = 3f, axis = 'y', seconds = 4f, rate = 10),
            screenOn = false,
        )
        val fastFeed = MotionGate.analyse(
            window(hz = 1.8f, amplitude = 3f, axis = 'y', seconds = 4f, rate = 50),
            screenOn = false,
        )

        assertEquals(Motion.WALKING, slowFeed.motion)
        assertEquals(Motion.WALKING, fastFeed.motion)
    }

    @Test
    fun `escalator with smooth Z and no Y leg swing is rejected`() {
        // A lift or an escalator carries the body: a slow ramp with a little machine vibration on
        // top of it. What keeps it out is how little it moves — energy, not rhythm, is the test.
        val escalatorSamples = (0 until 100).map { i ->
            val t = i / 25f // 25 Hz sample rate
            val tNanos = (t * 1e9f).toLong()
            // Z-axis: smooth upward acceleration (elevator motion)
            val z = 9.81f + 0.05f * t // smooth ramp, not rhythmic
            // Y-axis: small vibration, no leg rhythm (<2 m/s² variance)
            val y = 0.3f * sin(2f * PI.toFloat() * 1.2f * t) // small vibration at 1.2 Hz
            Accel(tNanos, 0f, y, z)
        }

        val reading = MotionGate.analyse(escalatorSamples, screenOn = false)

        assertEquals(Motion.STILL, reading.motion)
        assertFalse(reading.stepsAllowed)
    }

    @Test
    fun `real stairs with strong Y axis and large Z variance are counted`() {
        // Real stairs should still be counted. They have:
        // - Strong Y-axis (leg swing): >3 m/s² variance
        // - Z-axis variance >6 m/s² (going up)
        // - Rhythmic cadence 0.7-1.0 Hz (slower than flat walking)
        val stairsSamples = (0 until 150).map { i ->
            val t = i / 25f // 25 Hz sample rate
            val tNanos = (t * 1e9f).toLong()
            // Z-axis: large variance from climbing effort (not smooth elevator)
            val z = 9.81f + 4f * sin(2f * PI.toFloat() * 0.85f * t)
            // Y-axis: strong leg rhythm from climbing (>3 m/s² variance)
            val y = 4f * sin(2f * PI.toFloat() * 0.85f * t)
            Accel(tNanos, 0f, y, z)
        }

        val reading = MotionGate.analyse(stairsSamples, screenOn = false)

        // Should be counted as walking because Y is strong and Z is rhythmic, not smooth
        assertTrue(reading.stepsAllowed)
    }
}
