package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sets recorded with the phone in a trouser pocket and counted by the person doing them. Synthetic
 * fixtures say what the counter was meant to do; these say what it does on a body.
 */
class RepTracesTest {

    private fun recording(name: String): List<Accel> = RepCounter.samplesFromTraceCsv(
        checkNotNull(javaClass.getResourceAsStream("/traces/$name")) { "missing $name" }
            .use { it.readBytes().decodeToString() },
    )

    private fun List<Accel>.seconds(from: Double, until: Double): List<Accel> {
        val start = first().t
        return filter { (it.t - start) / 1e9 in from..<until }
    }

    /** Start, the phone into the pocket, seven pull-ups with two longer breaths, the phone out, Stop. */
    @Test
    fun sevenPullUpsFromStartToStop() {
        val set = recording("pullups-7-pocket.csv")
        val pullUps = REP_PROFILES.getValue("PULLUPS")

        assertEquals(7, RepCounter.count(set, pullUps, handled = true).reps)
        // Counted whole it is still seven: the phone turning over on its way into the pocket and out
        // of it gives itself away. Without the turn looked at, the same set would be ten.
        assertEquals(7, RepCounter.count(set, pullUps).reps)
    }

    /**
     * Three sets of ten, knees tucked, held up on the bars, in one recording; each set is cut out with
     * a few still seconds either side, as Start and Stop would.
     */
    @Test
    fun threeSetsOfTenKneeRaisesOnTheBars() {
        val recording = recording("bar-leg-raise-3x10-pocket.csv")
        val raises = REP_PROFILES.getValue("BAR_LEG_RAISE")

        listOf(3.0 to 27.0, 38.0 to 62.0, 118.0 to 139.0).forEach { (from, until) ->
            val set = recording.seconds(from, until)
            assertEquals("$from–$until s", 10, RepCounter.count(set, raises, handled = true).reps)
        }
    }

    /**
     * A Freestyle session on the bars: knee raises, a set of pull-ups the pocket did not
     * feel (the screen stayed lit and nothing rose above the jitter), knee raises, two minutes' rest,
     * knee raises — ten each, read off the thigh turning up ten times. The screen went dark as the
     * phone went into the pocket and lit as it came out; Start is cut a second or five before the one
     * and Stop a second or five after the other, a quick hand or a slow one. Without the turn and
     * with a sample-wide boxcar (`before` below) the counter takes up to 14 of each.
     */
    @Test
    fun kneeRaisesCountTenHoweverLongThePhoneTakesToPutAway() {
        val recording = recording("freestyle-bars-session.csv")
        val raises = REP_PROFILES.getValue("BAR_LEG_RAISE")
        val before = raises.copy(smoothingSeconds = 0.06, maxTurnDegrees = null)

        // When the screen went dark and when it lit again, in seconds from the start of the recording.
        listOf(29.3 to 51.9, 133.6 to 155.1, 274.5 to 296.7).forEach { (dark, lit) ->
            listOf(1.0, 5.0).forEach { start ->
                listOf(1.0, 5.0).forEach { stop ->
                    val set = recording.seconds(dark - start, lit + stop)
                    val at = "Start ${dark - start} s, Stop ${lit + stop} s"
                    assertEquals(at, 10, RepCounter.count(set, raises, handled = true).reps)
                }
            }
            // The slow hand is what the fixture is for: without the two rules it is not ten.
            val slow = recording.seconds(dark - 5.0, lit + 5.0)
            assertTrue("$dark s", RepCounter.count(slow, before, handled = true).reps > 10)
        }
    }
}
