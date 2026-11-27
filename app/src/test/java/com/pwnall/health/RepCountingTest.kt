package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Repetitions, as tests. A landing is a short impact spike on top of gravity and a squat is a pair
 * of rotation peaks, so the fixtures below are exactly that — the shapes the two sensors record.
 */
class RepCountingTest {

    private val rope = REP_PROFILES.getValue("JUMP_ROPE")
    private val squats = REP_PROFILES.getValue("SQUATS")

    /** Gravity plus one half-sine impact per repetition: what the accelerometer sees. */
    private fun landings(
        reps: Int,
        hz: Double,
        impact: Double = 20.0,
        rate: Int = 50,
        startSeconds: Double = 0.0,
    ): List<Accel> {
        val impactSeconds = 0.1
        val count = (reps / hz * rate).toInt()
        return (0 until count).map { i ->
            val t = startSeconds + i / rate.toDouble()
            val intoRep = (i / rate.toDouble()) % (1.0 / hz)
            val v = 9.81 + if (intoRep < impactSeconds) impact * sin(PI * intoRep / impactSeconds) else 0.0
            Accel((t * 1e9).toLong(), 0f, 0f, v.toFloat())
        }
    }

    /**
     * Two rotation peaks per repetition — down and up — then a pause standing at the top. That is
     * what a gyroscope in a pocket records during a squat, and the pause is what tells the counter
     * where the quiet level is.
     */
    private fun rotations(
        reps: Int,
        secondsPerRep: Double,
        movingSeconds: Double = 2.0,
        peak: Double = 2.0,
        rate: Int = 50,
    ): List<Accel> = (0 until (reps * secondsPerRep * rate).toInt()).map { i ->
        val t = i / rate.toDouble()
        val intoRep = t % secondsPerRep
        val v = if (intoRep < movingSeconds) peak * sin(2 * PI * intoRep / movingSeconds) else 0.0
        Accel((t * 1e9).toLong(), 0f, 0f, v.toFloat())
    }

    private fun still(seconds: Double, rate: Int = 50) = (0 until (seconds * rate).toInt()).map { i ->
        Accel((i / rate.toDouble() * 1e9).toLong(), 0f, 0.02f * (i % 3), 9.81f)
    }

    @Test
    fun `counts one rope jump per landing`() {
        val result = RepCounter.count(landings(reps = 60, hz = 2.0), rope)
        assertEquals(60, result.reps)
        assertEquals(2.0, result.cadenceHz, 0.05)
    }

    @Test
    fun `a slow rope is still counted`() {
        assertEquals(30, RepCounter.count(landings(reps = 30, hz = 1.2), rope).reps)
    }

    @Test
    fun `a rest between sets does not inflate the cadence`() {
        val samples = landings(reps = 20, hz = 2.0) +
            still(30.0).map { it.copy(t = it.t + 10_000_000_000L) } +
            landings(reps = 20, hz = 2.0, startSeconds = 40.0)
        val result = RepCounter.count(samples, rope)
        assertEquals(40, result.reps)
        assertEquals(2.0, result.cadenceHz, 0.1)
        assertTrue("active time should exclude the rest", result.activeSeconds < 25.0)
    }

    /**
     * 50 Hz is only asked for: Android delivers at whatever rate the sensor runs. The smoothing is by
     * the clock and not by the sample, so the same sixty landings are sixty at every rate.
     */
    @Test
    fun `the count does not depend on how fast the samples arrive`() {
        listOf(25, 100, 200).forEach { rate ->
            assertEquals("$rate Hz", 60, RepCounter.count(landings(reps = 60, hz = 2.0, rate = rate), rope).reps)
        }
    }

    /**
     * A tapped set loses three seconds at each end to the phone going into a pocket and coming out.
     * Four seconds of jumps are nothing but that, and count nought: the question at Stop gets their reps.
     */
    @Test
    fun `a tapped set shorter than its handling counts nothing`() {
        val short = landings(reps = 8, hz = 2.0)
        assertEquals(8, RepCounter.count(short, rope).reps)
        assertEquals(0, RepCounter.count(short, rope, handled = true).reps)
    }

    /**
     * Paused in the middle of a set: the phone out of the pocket to tap Pause and back into it after
     * Continue shakes it like a jump each way. Each stretch is counted as a tapped set is, so those
     * trips are no jumps; counted whole, they would be four.
     */
    @Test
    fun `the phone handled around a pause is not counted`() {
        fun shifted(seconds: Double, samples: List<Accel>) =
            samples.map { it.copy(t = it.t + (seconds * 1e9).toLong()) }

        // In the hand for two seconds at either end, still, forty jumps, still.
        fun stretch(from: Double) = shifted(from, landings(reps = 2, hz = 1.0)) +
            shifted(from + 2, still(2.0)) +
            shifted(from + 4, landings(reps = 40, hz = 2.0)) +
            shifted(from + 24, still(2.0)) +
            shifted(from + 26, landings(reps = 2, hz = 1.0))
        val beforePause = stretch(0.0)
        val set = beforePause + stretch(100.0)

        assertEquals(80, repsAcrossPauses(set, listOf(beforePause.size), rope, handled = true))
        assertTrue(RepCounter.count(set, rope, handled = true).reps > 80)
        // A set never paused is counted whole.
        assertEquals(40, repsAcrossPauses(beforePause, emptyList(), rope, handled = true))
    }

    @Test
    fun `sitting still is not exercising`() {
        assertEquals(0, RepCounter.count(still(20.0), rope).reps)
    }

    @Test
    fun `a squat is one repetition, not two rotations`() {
        val result = RepCounter.count(rotations(reps = 15, secondsPerRep = 3.0), squats)
        assertEquals(15, result.reps)
        assertEquals(1.0 / 3.0, result.cadenceHz, 0.05)
    }

    /**
     * A jump, as the accelerometer records it: standing at a g, a push-off well above it, nothing
     * at all while airborne, then a landing. [flightSeconds] is the only thing the height is read
     * from, so it is the only thing these fixtures vary.
     */
    private fun jump(
        flightSeconds: Double,
        rate: Int = 50,
        pushOff: Double = 22.0,
        landing: Double = 30.0,
        startSeconds: Double = 0.0,
    ): List<Accel> {
        val period = 1.0 / rate
        val stance = List(rate) { 9.81 }
        val push = List(3) { pushOff }
        val air = List((flightSeconds * rate).toInt()) { 0.4 }
        val land = List(3) { landing }
        return (stance + push + air + land + stance).mapIndexed { i, v ->
            Accel(((startSeconds + i * period) * 1e9).toLong(), 0f, 0f, v.toFloat())
        }
    }

    @Test
    fun `a third of a second in the air is a 13 cm jump`() {
        // h = g·t²/8 = 9.81 · 0.34² / 8
        assertEquals(0.142, jumpHeightM(jump(flightSeconds = 0.34)), 0.02)
    }

    @Test
    fun `a longer flight is a higher jump`() {
        val low = jumpHeightM(jump(flightSeconds = 0.3))
        val high = jumpHeightM(jump(flightSeconds = 0.5))

        assertTrue("$low vs $high", high > low * 2)
    }

    @Test
    fun `the best jump of a set is the one reported`() {
        val set = jump(flightSeconds = 0.3) +
            jump(flightSeconds = 0.52, startSeconds = 10.0) +
            jump(flightSeconds = 0.28, startSeconds = 20.0)

        assertEquals(9.81 * 0.52 * 0.52 / 8, jumpHeightM(set), 0.03)
    }

    @Test
    fun `a dropped phone is not a jump`() {
        // Same free fall, same hard landing, no push-off — which is the only difference there is.
        assertEquals(0.0, jumpHeightM(jump(flightSeconds = 0.45, pushOff = 9.9)), 0.001)
    }

    @Test
    fun `feet never leaving the ground is not a jump`() {
        assertEquals(0.0, jumpHeightM(landings(reps = 20, hz = 2.0)), 0.001)
    }

    @Test
    fun `sitting still is not a jump`() {
        assertEquals(0.0, jumpHeightM(still(20.0)), 0.001)
    }

    @Test
    fun `a rotation profile reports no jump at all`() {
        // Gyroscope samples are rad/s, where "under 3" is an ordinary slow turn, not free fall.
        assertEquals(0.0, RepCounter.count(rotations(reps = 15, secondsPerRep = 3.0), squats).bestJumpM, 0.001)
    }

    @Test
    fun `every countable exercise is in the catalogue`() {
        val codes = ACTIVITY_CATALOG.map { it.code }.toSet()
        assertTrue(REP_PROFILES.keys.all { it in codes })
        assertTrue(ACTIVITY_CATALOG.first { it.code == "SQUATS" }.counted)
        assertFalse(ACTIVITY_CATALOG.first { it.code == "PLANK" }.counted)
    }

    @Test
    fun `reads samples back out of a recorded trace`() {
        val csv = SensorTrace.HEADER + "\n" +
            "M,,,,,,,,,,,,,,,,,,,,,,,,,schema.version,2\n" +
            "S,1000,1000,0.0000,0.0000,9.8100,9.8100,0.1,0.0,0.0,0.0,STILL,FLAT,false,,,,,,,,,,,,,\n" +
            "S,21000000,21000000,0.0000,0.0000,29.0000,29.0000,0.1,0.0,0.0,0.0,STILL,FLAT,false,,,,,,,,,,,,,\n"
        val samples = RepCounter.samplesFromTraceCsv(csv)
        assertEquals(2, samples.size)
        assertEquals(9.81f, samples[0].z, 0.001f)
        assertEquals(21_000_000L, samples[1].t)
    }
}
