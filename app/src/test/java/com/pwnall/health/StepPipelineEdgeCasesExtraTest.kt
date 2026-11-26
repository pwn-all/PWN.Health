package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The rest of the step pipeline: the barometer that decides a floor, the clock that decides a day,
 * and the merge that decides who owns one.
 *
 * The barometer fixtures are streams of altitudes at 5 Hz — what the sensor delivers at
 * `SENSOR_DELAY_NORMAL` — except where a boundary has to land on an exact number, which it can only
 * do with the smoothing turned off and a sample rate whose step is a binary fraction.
 */
class StepPipelineEdgeCasesExtraTest {

    private val rateHz = 5
    private val sampleIntervalNanos = 200_000_000L

    // --- floors ----------------------------------------------------------------------------

    /**
     * Truncation, pinned either side of every boundary that matters: a hair under a storey is no
     * floors, a storey exactly is one, and no ascent at all is none. (Negative ascents are not
     * pinned here: nothing produces one — a day's ascentM is a sum of strictly positive climbs —
     * and nothing consumes a floor count, so there is no promise to make about them.)
     */
    @Test
    fun `whole storeys only, including at zero`() {
        assertEquals(0, floorsClimbed(2.999))
        assertEquals(1, floorsClimbed(METRES_PER_FLOOR))
        assertEquals(2, floorsClimbed(6.9))
        assertEquals(2, floorsClimbed(8.999))
        assertEquals(0, floorsClimbed(0.0))
    }

    /**
     * The smoothing filter feeds its own previous value back in, so one NaN pressure reading — a
     * sensor hiccup, a driver returning garbage on resume — can make every later altitude NaN.
     * Every comparison then fails and the floors stop counting until the process is restarted.
     */
    @Test
    fun `a NaN altitude must not stop floors being counted for ever`() {
        val profile = List(50) { 100.0 } + Double.NaN + ramp(100.0, 103.4, 12.0)

        val ascent = feedAltitudes(FloorTracker(), profile)

        assertTrue("credited $ascent m", ascent >= METRES_PER_FLOOR)
        assertEquals(1, floorsClimbed(ascent))
    }

    /**
     * The rate guard is what keeps a slamming stairwell door and a fast lift out, and it is
     * inclusive: a storey in exactly three seconds is a metre a second, which is a person taking
     * the stairs quickly. A hair faster is the barometer being pushed, not a body climbing.
     *
     * Both runs feed the same 3 m staircase; only the clock changes.
     */
    @Test
    fun `a climb at exactly a metre a second is legs and a hair faster is not`() {
        val unsmoothed = FloorTracker.Config(smoothingSeconds = 0.0)

        val legs = feedAltitudes(FloorTracker(unsmoothed), stairs(), 125_000_000L)
        val slammedDoor = feedAltitudes(FloorTracker(unsmoothed), stairs(), 120_000_000L)

        assertEquals(3.0, legs, 1e-9)
        assertEquals(0.0, slammedDoor, 1e-9)
    }

    /**
     * Half the samples walking is the documented share, and it is compared as a floating-point
     * product of an integer count — a boundary on a quantity that can only be whole. Twelve of the
     * twenty-four samples since the anchor is exactly half; eleven is standing in something.
     */
    @Test
    fun `half the samples walking is enough to own the climb`() {
        val unsmoothed = FloorTracker.Config(smoothingSeconds = 0.0)

        val half = feedAltitudes(FloorTracker(unsmoothed), stairs(), 125_000_000L) { it in 40 until 52 }
        val justUnder = feedAltitudes(FloorTracker(unsmoothed), stairs(), 125_000_000L) { it in 40 until 51 }

        assertEquals(3.0, half, 1e-9)
        assertEquals(0.0, justUnder, 1e-9)
    }

    /**
     * The price of the weather guard, pinned so nobody removes it without seeing what it costs: an
     * elderly climber, or a flight with a long rest on the landing, has the starting height taken
     * away at sixty seconds and the storey already climbed goes with it.
     */
    @Test
    fun `a storey climbed too slowly is thrown away by the drift guard`() {
        val ascent = feedAltitudes(FloorTracker(), List(50) { 100.0 } + ramp(100.0, 103.2, 75.0))

        assertEquals(0.0, ascent, 1e-9)
    }

    /**
     * Down and then back up is the mirror of up and then down. If going down did not
     * move the anchor to the low point, walking back up the stairs you came down would credit
     * nothing at all — the rise would only be measured against the height you started the day at.
     */
    @Test
    fun `a climb straight after a descent starts from the bottom`() {
        val tracker = FloorTracker()
        val downhill = List(50) { 100.0 } + ramp(100.0, 96.0, 20.0, holdSeconds = 0.0)

        val descent = feedAltitudes(tracker, downhill)
        val ascent = feedAltitudes(tracker, ramp(96.0, 99.5, 14.0), startIndex = downhill.size)

        assertEquals(0.0, descent, 1e-9)
        assertEquals("credited $ascent m", 1, floorsClimbed(ascent))
    }

    // --- which day the steps belong to -------------------------------------------------------

    /** One millisecond of delivery lag decides a whole day's total, either side of midnight. */
    @Test
    fun `a millisecond of delivery lag across midnight belongs to yesterday`() {
        val midnight = Instant.parse("2026-07-31T00:00:00Z").toEpochMilli()

        assertEquals(
            "2026-07-30",
            SensorEventClock.day(midnight, 100_000_000_000L, 99_999_000_000L, ZoneOffset.UTC),
        )
        assertEquals(
            "2026-07-31",
            SensorEventClock.day(midnight, 100_000_000_000L, 100_000_000_000L, ZoneOffset.UTC),
        )
    }

    /**
     * The clocks jumped an hour forward between the steps being taken and the batch being
     * delivered. Going through the instant keeps the local day right; adding a fixed offset does
     * not.
     */
    @Test
    fun `a batch delivered across a spring forward keeps its own local day`() {
        val delivery = Instant.parse("2026-03-08T07:30:00Z").toEpochMilli()

        val day = SensorEventClock.day(
            deliveryEpochMillis = delivery,
            deliveryElapsedRealtimeNanos = 20_000_000_000_000L,
            eventTimestampNanos = 5_600_000_000_000L,
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals("2026-03-07", day)
    }

    /** Subtracting a negative lag moves the clock forward; subtracting Long.MIN_VALUE overflows. */
    @Test
    fun `a negative sensor timestamp cannot move the wall clock`() {
        val delivery = Instant.parse("2026-07-31T12:00:00Z").toEpochMilli()

        assertEquals(delivery, SensorEventClock.epochMillis(delivery, 100_000_000_000L, -1))
        assertEquals(delivery, SensorEventClock.epochMillis(delivery, 100_000_000_000L, Long.MIN_VALUE))
    }

    /**
     * A day later than "today" arrives by one door: a bout whose first day was stored while the
     * clock still read it, confirming after the clock moved backwards — a westward flight, a
     * stepped time correction. Hour 23 would file those steps in an evening that has not happened
     * yet, so the chart shows a spike at the end of a day barely begun; the first hour is both the
     * hour they were actually taken and the only one nobody has to explain to themselves.
     */
    @Test
    fun `a credit for a day that has not happened yet does not land in its final hour`() {
        assertEquals(0, creditedHour(day = "2026-08-13", today = "2026-08-12", currentHour = 23))
        assertEquals(0, creditedHour(day = "2026-08-13", today = "2026-08-12", currentHour = 12))
    }

    // --- who owns the day ---------------------------------------------------------------------

    /**
     * Publishing a day somebody else already owns is how the same steps get counted twice. The four
     * fields come from three independent filters and nothing states the relationship between them.
     *
     * Ownership is counting at least as much, not merely having written something: the day the
     * phone counted 12,000 against another app's 8,000 stays this app's to publish.
     */
    @Test
    fun `no day is ever both exported and covered`() {
        val watchAhead = LocalDate.parse("2026-07-27")
        val phoneAhead = LocalDate.parse("2026-07-28")
        val phoneOnly = LocalDate.parse("2026-07-29")
        val watchReportedZero = LocalDate.parse("2026-07-30")
        val corruptRow = LocalDate.parse("2026-07-31")

        val merge = mergeStepDays(
            local = mapOf(watchAhead to 3_000L, phoneAhead to 12_000L, phoneOnly to 4_000L, corruptRow to -9L),
            externalTotals = mapOf(watchAhead to 9_000L, phoneAhead to 8_000L, watchReportedZero to 0L),
        )

        assertTrue("exports ${merge.exports}", merge.exports.none { it in merge.covered })
        assertTrue("imports ${merge.imports}", merge.imports.keys.all { it in merge.covered })
        assertTrue("imports ${merge.imports}", merge.imports.values.all { it > 0L })
        assertEquals(listOf(phoneAhead, phoneOnly), merge.exports)
        assertFalse("covered ${merge.covered}", watchReportedZero in merge.covered)
        assertFalse("covered ${merge.covered}", phoneAhead in merge.covered)
    }

    /** A corrupt row must show nothing on the widget, never a negative step count. */
    @Test
    fun `a corrupt row can never become a negative total`() {
        assertEquals(0L, effectiveStepTotal(local = -5L, external = null))
        assertEquals(0L, effectiveStepTotal(local = null, external = null))
        assertEquals(0L, effectiveStepTotal(local = 0L, external = null, directBleSources = listOf(-7L)))
        assertEquals(11L, effectiveStepTotal(local = null, external = null, directBleSources = listOf(11L, -7L)))
    }

    /**
     * On a fresh install Health Connect has not yet given this device an origin, which is exactly
     * when the first sync runs. A missing identity must not make the app's own writes look like
     * somebody else's — that is the double-counting loop this function exists to break.
     */
    @Test
    fun `a phone without a Health Connect identity still refuses its own writes`() {
        val own = "com.pwnall.health"

        assertTrue(isExternalStepOrigin("com.vendor.wearable", own, null))
        assertFalse(isExternalStepOrigin(own, own, null))
        assertFalse(isExternalStepOrigin("android", own, null))
    }

    /**
     * The window is symmetric because a clock that jumped backwards must not open a second tap, and
     * it is exclusive at both ends so a correction of exactly the window length is a new tap.
     */
    @Test
    fun `the duplicate tap window is exclusive at both ends`() {
        val tapped = 1_800_000_000_000L

        assertFalse(isDuplicateTap(tapped, tapped - DUPLICATE_TAP_WINDOW_MS))
        assertTrue(isDuplicateTap(tapped, tapped - DUPLICATE_TAP_WINDOW_MS + 1))
        assertFalse(isDuplicateTap(tapped, tapped, windowMs = 0))
    }

    // --- fixtures ------------------------------------------------------------------------------

    /**
     * Feeds a profile of altitudes and returns the metres credited over all of it. [walking] is the
     * motion gate's verdict for the sample at that absolute index.
     */
    private fun feedAltitudes(
        tracker: FloorTracker,
        profile: List<Double>,
        intervalNanos: Long = sampleIntervalNanos,
        startIndex: Int = 0,
        walking: (Int) -> Boolean = { true },
    ): Double = profile
        .mapIndexed { index, altitude ->
            val at = startIndex + index
            tracker.add(at * intervalNanos, altitude, walking(at))
        }
        .sum()

    /** A ramp from [from] to [to] taking [seconds], then holding still for [holdSeconds]. */
    private fun ramp(
        from: Double,
        to: Double,
        seconds: Double,
        holdSeconds: Double = 20.0,
    ): List<Double> {
        val rising = (seconds * rateHz).toInt()
        return (0 until rising).map { from + (to - from) * it / rising } +
            List((holdSeconds * rateHz).toInt()) { to }
    }

    /**
     * Forty samples of standing followed by a climb of exactly three metres in twenty-four steps of
     * an eighth of a metre — every value a binary fraction, so the rise lands on the boundary
     * exactly rather than a rounding either side of it.
     */
    private fun stairs(): List<Double> = List(40) { 100.0 } + (1..24).map { 100.0 + 0.125 * it }
}
