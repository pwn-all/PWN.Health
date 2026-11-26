package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The barometer, as tests. The fixtures are streams of altitudes at 5 Hz — what the sensor delivers
 * at `SENSOR_DELAY_NORMAL` — because the whole question is which of those streams is a staircase and
 * which is an afternoon of weather; the last few are at the once a second some sensors deliver
 * instead, and with the gap a sleeping processor leaves.
 */
class BarometerTest {

    private val rateHz = 5
    private val second = 1_000_000_000L

    /**
     * Feeds a profile of altitudes and returns the metres the tracker credited over all of it.
     *
     * [walking] is what the motion gate would have said throughout — true for a staircase, false
     * for anything the person was standing in.
     */
    private fun climb(
        profile: List<Double>,
        tracker: FloorTracker = FloorTracker(),
        startSeconds: Double = 0.0,
        walking: Boolean = true,
    ): Double = profile
        .mapIndexed { i, altitude ->
            val t = ((startSeconds + i / rateHz.toDouble()) * 1e9).toLong()
            tracker.add(t, altitude, walking)
        }
        .sum()

    /** A ramp from [from] to [to] taking [seconds], then holding still for [holdSeconds]. */
    private fun ramp(from: Double, to: Double, seconds: Double, holdSeconds: Double = 20.0): List<Double> {
        val rising = (0 until (seconds * rateHz).toInt()).map {
            from + (to - from) * it / (seconds * rateHz)
        }
        return rising + List((holdSeconds * rateHz).toInt()) { to }
    }

    @Test
    fun `one storey of stairs is one floor`() {
        val ascent = climb(List(50) { 100.0 } + ramp(100.0, 103.2, 12.0))

        assertEquals(1, floorsClimbed(ascent))
    }

    @Test
    fun `three storeys are three floors`() {
        val ascent = climb(List(50) { 100.0 } + ramp(100.0, 109.5, 35.0))

        assertEquals(3, floorsClimbed(ascent))
    }

    @Test
    fun `an afternoon of weather is not a staircase`() {
        // 8 m of drift over four hours: what a passing front does to a barometer, and the exact
        // thing that must not accumulate into floors nobody walked.
        val hours = 4.0
        val samples = (0 until (hours * 3_600 * rateHz).toInt()).map { i ->
            100.0 + 8.0 * i / (hours * 3_600 * rateHz)
        }

        assertEquals(0.0, climb(samples), 0.001)
    }

    @Test
    fun `a fast lift is too fast to be legs`() {
        // Six storeys in eight seconds is 2.3 m/s. Even with gait alongside it, legs do not do that.
        val ascent = climb(List(50) { 100.0 } + ramp(100.0, 118.0, 8.0))

        assertEquals(0.0, ascent, 0.001)
    }

    @Test
    fun `a slow lift climbs at a human speed and is still not a climb`() {
        // 9.5 m in 35 s is 0.27 m/s — indistinguishable from stairs to a barometer. The person is
        // standing in it, which is the whole difference and the only one worth testing.
        val profile = List(50) { 100.0 } + ramp(100.0, 109.5, 35.0)

        assertEquals(3, floorsClimbed(climb(profile, walking = true)))
        assertEquals(0.0, climb(profile, walking = false), 0.001)
    }

    @Test
    fun `standing on an escalator is not climbing it`() {
        val ascent = climb(List(50) { 100.0 } + ramp(100.0, 104.0, 20.0), walking = false)

        assertEquals(0.0, ascent, 0.001)
    }

    @Test
    fun `arriving at the top standing still does not cost the flight`() {
        // Smoothing puts the credit a moment behind the last step, so the tail of every real climb
        // reads as standing. Demanding gait for all of it would lose the floor that was walked.
        val tracker = FloorTracker()
        val profile = List(50) { 100.0 } + ramp(100.0, 103.4, 12.0, holdSeconds = 6.0)
        val ascent = profile
            .mapIndexed { i, altitude ->
                // The ramp runs from sample 50; the last two and a half seconds of it read as
                // standing, which is the top of the stairs.
                tracker.add((i / rateHz.toDouble() * 1e9).toLong(), altitude, walking = i < 95)
            }
            .sum()

        assertEquals(1, floorsClimbed(ascent))
    }

    @Test
    fun `a pressure spike from a slammed door is not a floor`() {
        val quiet = List(50) { 100.0 }
        val spike = List(5) { 112.0 }

        assertEquals(0.0, climb(quiet + spike + quiet), 0.001)
    }

    @Test
    fun `going back down does not subtract the climb`() {
        val tracker = FloorTracker()
        val up = climb(List(50) { 100.0 } + ramp(100.0, 106.4, 22.0), tracker)
        val down = climb(ramp(106.4, 100.0, 22.0), tracker, startSeconds = 100.0)

        assertEquals(2, floorsClimbed(up))
        assertEquals(0.0, down, 0.001)
    }

    @Test
    fun `standing still climbs nothing`() {
        assertEquals(0.0, climb(List(600) { 100.0 }), 0.001)
    }

    /**
     * The processor sleeps through the night and the barometer with it, so the first reading of the
     * morning comes eight hours of weather later — seventeen metres of it here, read once a second.
     * Those hours make the rise slow enough to pass for stairs, and a smoothing that followed it at a
     * staircase's pace would credit 6.7 m, two floors, before the first step was taken.
     */
    @Test
    fun `the weather of a night the processor slept through is not a climb`() {
        val tracker = FloorTracker()
        val night = 8 * 3_600 * second
        val evening = (0 until 120).sumOf { tracker.add(it * second, 100.0, walking = false) }
        val morning = (0 until 120).sumOf { tracker.add(night + it * second, 117.0, walking = true) }

        assertEquals(0.0, evening + morning, 0.001)
    }

    /**
     * A step in the pressure — a door, a ventilated hall — is followed in about a second whatever the
     * sensor's rate, and so is as much too fast for legs read once a second as five times.
     */
    @Test
    fun `a step in the pressure read once a second is not a climb`() {
        val tracker = FloorTracker()
        val ascent = (0 until 180).sumOf { tracker.add(it * second, if (it < 120) 100.0 else 105.0, walking = true) }

        assertEquals(0.0, ascent, 0.001)
    }

    /** And a storey of stairs read once a second is still a floor. */
    @Test
    fun `a storey read once a second is still a floor`() {
        val tracker = FloorTracker()
        val profile = List(10) { 100.0 } + (0 until 12).map { 100.0 + 3.2 * it / 12 } + List(20) { 103.2 }
        val ascent = profile.mapIndexed { i, altitude -> tracker.add(i * second, altitude, walking = true) }.sum()

        assertEquals(1, floorsClimbed(ascent))
    }
}
