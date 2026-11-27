package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A chest recording, as a fixture: gravity, a slow large breath, a small fast pulse, and noise.
 *
 * The ratios are the ones that make this hard and are taken from real traces — the breath moves the
 * phone several times further than the heartbeat does, so a filter that only removes the constant
 * would leave the breath dominating and read out a respiration rate.
 */
class HeartbeatTest {

    private fun chest(
        bpm: Int,
        seconds: Double = 30.0,
        rate: Double = 29.7,
        breathAmplitude: Double = 0.12,
        breathHz: Double = 0.25,
        pulseAmplitude: Double = 0.03,
        noiseAmplitude: Double = 0.015,
        secondHarmonicAmplitude: Double = 0.0,
    ): List<Accel> {
        val count = (seconds * rate).toInt()
        // Fixed-seed noise rather than a stray sinusoid: a sinusoid is a rhythm, and a fixture whose
        // "noise" has a period is a fixture that tests whether the estimator can find that period.
        val noise = java.util.Random(bpm.toLong())
        return (0 until count).map { i ->
            val t = i / rate
            val v = 9.81 +
                breathAmplitude * sin(2 * PI * breathHz * t) +
                pulseAmplitude * sin(2 * PI * bpm / 60 * t) +
                noise.nextGaussian() * noiseAmplitude +
                secondHarmonicAmplitude * sin(2 * PI * 2 * bpm / 60 * t + 1.0)
            Accel((t * 1e9).toLong(), 0f, 0f, v.toFloat())
        }
    }

    private fun bpmOf(samples: List<Accel>): Int {
        val result = Ballistocardiogram.estimate(samples)
        assertTrue("expected a reading, got $result", result is Ballistocardiogram.Result.Ok)
        return (result as Ballistocardiogram.Result.Ok).estimate.bpm
    }

    /** The rates a resting adult actually shows, breathing normally throughout. */
    @Test
    fun `reads a resting pulse through breathing`() {
        for (expected in listOf(48, 57, 72, 96)) {
            val measured = bpmOf(chest(expected))
            assertTrue(
                "expected ~$expected bpm, measured $measured",
                abs(measured - expected) <= 3,
            )
        }
    }

    /** Half the period correlates nearly as well as the whole; the reading must not double. */
    @Test
    fun `does not lock onto the second harmonic`() {
        assertTrue(bpmOf(chest(57)) < 80)
    }

    /** Holding the breath is the easy case, and it must not change the answer. */
    @Test
    fun `breath held reads the same as breathing`() {
        val held = bpmOf(chest(66, breathAmplitude = 0.0))
        val breathing = bpmOf(chest(66))
        assertTrue("held $held vs breathing $breathing", abs(held - breathing) <= 3)
    }

    /**
     * A steady rhythm repeats after two and three beats as well as after one, and with the breath
     * held nothing tilts the curve towards the single beat: 108 must not read 54, nor 144 read 48.
     */
    @Test
    fun `a fast pulse with the breath held is not read at a half or a third`() {
        for (expected in listOf(84, 96, 108, 120, 132, 144)) {
            val measured = bpmOf(chest(expected, breathAmplitude = 0.0))
            assertTrue(
                "expected ~$expected bpm, measured $measured",
                abs(measured - expected) <= 3,
            )
        }
    }

    /** The other way round: a beat whose second harmonic is twice its fundamental is still one beat. */
    @Test
    fun `a strong second harmonic does not double a resting pulse`() {
        for (expected in listOf(48, 57, 66, 72)) {
            val measured = bpmOf(chest(expected, breathAmplitude = 0.0, secondHarmonicAmplitude = 0.06))
            assertTrue(
                "expected ~$expected bpm, measured $measured",
                abs(measured - expected) <= 3,
            )
        }
    }

    @Test
    fun `a moving phone is refused rather than guessed at`() {
        val walking = chest(66).mapIndexed { i, s ->
            s.copy(z = s.z + (2.0 * sin(2 * PI * 1.8 * i / 29.7)).toFloat())
        }
        assertEquals(
            Ballistocardiogram.Result.Poor(Ballistocardiogram.Failure.MOVING),
            Ballistocardiogram.estimate(walking),
        )
    }

    @Test
    fun `too little recording is refused rather than guessed at`() {
        assertEquals(
            Ballistocardiogram.Result.Poor(Ballistocardiogram.Failure.TOO_SHORT),
            Ballistocardiogram.estimate(chest(66, seconds = 10.0)),
        )
    }

    /**
     * Someone lying down onto the phone throws |a| past 1.0 for a few seconds, and the rest of that
     * recording is the quietest kind there is. Judging movement over the whole recording would throw
     * the good part away with the bad; the windows holding the thump are the only ones to drop.
     */
    @Test
    fun `settling at the start costs its own windows and no others`() {
        val settling = chest(66, seconds = 45.0).mapIndexed { i, s ->
            val t = i / 29.7
            if (t < 4.0) s.copy(z = s.z + (3.0 * sin(2 * PI * 1.2 * t)).toFloat()) else s
        }
        val measured = bpmOf(settling)
        assertTrue("expected ~66 bpm, measured $measured", abs(measured - 66) <= 3)
    }

    @Test
    fun `a reading surviving substantial interference is marked as uncertain`() {
        val disturbed = chest(66, seconds = 60.0).mapIndexed { i, sample ->
            val t = i / 29.7
            if (t < 30.0) {
                sample.copy(z = sample.z + (2.0 * sin(2 * PI * 1.8 * t)).toFloat())
            } else {
                sample
            }
        }

        val result = Ballistocardiogram.estimate(disturbed)
        assertTrue(
            "expected the clean tail to produce a reading, got $result",
            result is Ballistocardiogram.Result.Ok,
        )
        val estimate = (result as Ballistocardiogram.Result.Ok).estimate
        assertTrue(
            "expected at least half the windows to be rejected: $estimate",
            estimate.hasExcessiveInterference,
        )
        assertTrue("expected ~66 bpm, measured ${estimate.bpm}", abs(estimate.bpm - 66) <= 3)
    }

    @Test
    fun `half rejected windows is the interference warning boundary`() {
        val halfRejected = Ballistocardiogram.Estimate(66, 0.4, 2, windows = 3, totalWindows = 6)
        val fewerRejected = Ballistocardiogram.Estimate(66, 0.4, 2, windows = 4, totalWindows = 6)

        assertTrue(halfRejected.hasExcessiveInterference)
        assertFalse(fewerRejected.hasExcessiveInterference)
    }

    /** Sensor noise on its own has no period in the band, and must not produce a number. */
    @Test
    fun `silence is refused rather than guessed at`() {
        val still = chest(66, pulseAmplitude = 0.0, breathAmplitude = 0.0)
        assertEquals(
            Ballistocardiogram.Result.Poor(Ballistocardiogram.Failure.NO_RHYTHM),
            Ballistocardiogram.estimate(still),
        )
    }

    /** [HEARTBEAT_MEASURE_SECONDS] has to leave the estimator enough windows to take a median of. */
    @Test
    fun `the measurement length the screen uses is long enough`() {
        val config = Ballistocardiogram.Config()
        val windows = 1 + ((HEARTBEAT_MEASURE_SECONDS - config.windowSeconds) / config.stepSeconds).toInt()
        assertTrue(windows >= config.minWindows)
        assertTrue(HEARTBEAT_MAX_MEASURE_SECONDS > HEARTBEAT_MEASURE_SECONDS)
    }
}
