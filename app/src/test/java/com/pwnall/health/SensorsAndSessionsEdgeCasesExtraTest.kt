package com.pwnall.health

import android.app.usage.UsageEvents
import androidx.compose.runtime.saveable.SaverScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The edges of a night estimated from phone use, and of a workout session written to the journal
 * and read back out of it.
 *
 * Both halves are about a value surviving a boundary: a night is a gap between two taps that has to
 * fall inside a range written with inclusive ends, and a session is a set of numbers that has to
 * come back out of its payload as the ones that went in.
 */
class SensorsAndSessionsEdgeCasesExtraTest {

    private val hour = 3_600_000L

    /** The windows [detectEstimatedSleep] really passes, with 04:00 as the earliest waking. */
    private fun night(interactions: List<Long>): EstimatedSleep? = estimateNightSleep(
        interactions = interactions,
        startEarliest = 0,
        startLatest = 2 * hour,
        wakeEarliest = 4 * hour,
        wakeLatest = 20 * hour,
    )

    /**
     * Four hours is the floor and sixteen the ceiling, and both are written as a Kotlin range,
     * which includes its ends. A floor turned exclusive loses the shortest real nights; a ceiling
     * widened files a weekend away from the phone as a night's sleep.
     */
    @Test
    fun `the shortest and the longest night are both still nights`() {
        assertEquals(EstimatedSleep(hour, 5 * hour), night(listOf(hour, 5 * hour)))
        assertNull(night(listOf(hour + 1, 5 * hour)))

        assertEquals(EstimatedSleep(hour, 17 * hour), night(listOf(hour, 17 * hour)))
        assertNull(night(listOf(hour - 1, 17 * hour)))
    }

    /**
     * UsageEvents reports several events in one millisecond and hands them back in platform order,
     * so the estimate has to survive both. A duplicated timestamp becoming a zero-length pair would
     * hide the real one behind it.
     */
    @Test
    fun `no interactions, one interaction, duplicates and events out of order`() {
        val clean = listOf(hour, 5 * hour)

        assertNull(night(emptyList()))
        assertNull(night(listOf(hour)))
        assertEquals(EstimatedSleep(hour, 5 * hour), night(clean))
        assertEquals(night(clean), night(listOf(hour, hour, 5 * hour, 5 * hour)))
        assertEquals(night(clean), night(clean.reversed()))
    }

    /**
     * A restart's own screen-on keeps arriving for five minutes, and one waking moment wrongly kept
     * loses a whole night — that is the bug this filter exists for. The window is inclusive at both
     * ends, and only the events a person alone can produce survive inside it.
     */
    @Test
    fun `the boot settle window's exact edge`() {
        val restart = 8 * hour
        val settle = 5 * 60 * 1_000L

        val moments = awakeMoments(
            listOf(
                UsageEventRecord(UsageEvents.Event.ACTIVITY_RESUMED, restart - 1),
                UsageEventRecord(UsageEvents.Event.DEVICE_STARTUP, restart),
                UsageEventRecord(UsageEvents.Event.KEYGUARD_HIDDEN, restart),
                UsageEventRecord(UsageEvents.Event.SCREEN_INTERACTIVE, restart + settle),
            ),
        )
        val late = awakeMoments(
            listOf(
                UsageEventRecord(UsageEvents.Event.DEVICE_STARTUP, restart),
                UsageEventRecord(UsageEvents.Event.SCREEN_INTERACTIVE, restart + settle + 1),
            ),
        )

        // The resume before the restart and the unlock at the instant of it are both a person; the
        // screen coming on at exactly five minutes is still the restart's own noise.
        assertEquals(listOf(restart - 1, restart), moments)
        assertEquals(listOf(restart + settle + 1), late)
    }

    private fun completed(reps: String = "", load: String = "", seconds: Int = 30, rest: Int = 0) =
        PlannedSet(
            reps = reps,
            load = load,
            completed = true,
            actualSeconds = seconds,
            actualRestSeconds = rest,
        )

    /**
     * 21.25 kg is a 20 kg bar and two 0.625 kg change plates, and the payload keeps that hundredth:
     * the number stored is the number that was typed. The text it comes back as is a load the way
     * every load in the app is written, to one decimal — the same rounding the form's own box has
     * already taken by then, since the session fills that box in from the live plan when it ends.
     */
    @Test
    fun `a load is stored to the hundredth and read back the way loads are shown`() {
        val plan = listOf(completed(reps = "5", load = "21.25", seconds = 40))

        val json = plan.encodeToJson()
        val written = JSONObject(json).getJSONArray("sets").getJSONObject(0)
        val reopened = decodeSetsFromJson(json).single()

        assertEquals(21.25, written.getDouble("loadKg"), 1e-9)
        assertEquals("21.3", reopened.load)
        assertEquals("5", reopened.reps)
        assertEquals(40, reopened.actualSeconds)
    }

    /**
     * A rest is the gap between two sets, and unless it is asked to keep the last one the payload
     * stores a rest only where a set follows it to be the gap before — the rule the running plan
     * and the paused session draw rests by. A session ended from the rest card does record the rest
     * it just served against its last set, and the duration it hands the form counts it. Encoded
     * without `preserveTrailingRest` the row does not, and the plan read back measures the sets and
     * the gaps between them and nothing after the last one: the summary of a session saved as five
     * minutes would say three and a half. Encoded with it, both measure the session that was ended.
     */
    @Test
    fun `a reopened session measures the sets and the gaps between them`() {
        val plan = List(2) { completed(reps = "5", load = "60", seconds = 60, rest = 90) }

        val reopened = decodeSetsFromJson(plan.encodeToJson())

        // Two sets of a minute each, each followed by ninety seconds of rest — but only the first
        // of those rests has a set after it, so only it is stored.
        assertEquals(5.0, plan.aggregate().durationMinutes, 1e-9)
        assertEquals(3.5, reopened.aggregate().durationMinutes, 1e-9)
        assertEquals(0, reopened.last().actualRestSeconds)

        // Kept, the last rest comes back with the others.
        val kept = decodeSetsFromJson(plan.encodeToJson(preserveTrailingRest = true))
        assertEquals(plan.aggregate(), kept.aggregate())
    }

    /**
     * Pull-ups: nobody types a load. An empty list averages to NaN, and the only thing between that
     * and a NaN in the journal's load column is one emptiness check.
     */
    @Test
    fun `a session of body-weight sets reports a load of zero, not NaN`() {
        val plan = listOf(completed(reps = "10", load = ""), completed(reps = "8", load = "0"))

        val aggregate = plan.aggregate()

        assertFalse("load was ${aggregate.load}", aggregate.load.isNaN())
        assertEquals(0.0, aggregate.load, 0.0)
        assertEquals(2, aggregate.sets)
        assertEquals(18, aggregate.reps)
    }

    /**
     * A session abandoned before its first completed set writes no payload, so the row falls back
     * to its own columns; and a corrupt payload out of a backup must not take the screen down with
     * it, which is what the decoder's catch-all is for.
     */
    @Test
    fun `a session with no completed set leaves nothing behind`() {
        val planned = List(3) { PlannedSet(reps = "10", load = "40") }

        assertEquals(WorkoutAggregate(0.0, 0, 0, 0.0), emptyList<PlannedSet>().aggregate())
        assertEquals(WorkoutAggregate(0.0, 0, 0, 0.0), planned.aggregate())
        assertEquals("", emptyList<PlannedSet>().encodeToJson())
        assertEquals("", planned.encodeToJson())
        listOf(null, "", "{}", """{"sets":[]}""", "not json at all").forEach {
            assertEquals("json=$it", emptyList<PlannedSet>(), decodeSetsFromJson(it))
        }
    }

    /**
     * A plank that ran for 45 seconds is a set that happened. Reps and load are written only when
     * they hold something, and the summary screen is gated on the session having any set at all.
     */
    @Test
    fun `a set that was completed with nothing typed still counts as a set`() {
        val plan = listOf(completed(seconds = 45))

        val json = plan.encodeToJson()
        val stored = JSONObject(json).getJSONArray("sets").getJSONObject(0)

        assertEquals(45, stored.getInt("seconds"))
        assertFalse(json, stored.has("reps"))
        assertFalse(json, stored.has("loadKg"))
        assertEquals(1, plan.aggregate().sets)
        assertEquals(0, plan.aggregate().reps)
        assertEquals(45, decodeSetsFromJson(json).single().actualSeconds)
    }

    /**
     * Read while drawing every row of the day list, so it must never throw. org.json hands back an
     * empty string rather than null for a missing key, which is what the blank check is for.
     */
    @Test
    fun `a partner code read out of a payload that is not one`() {
        val damaged = listOf(null, "", "   ", "not json", "{}", """{"supersetWith":""}""", """{"supersetWith":"  "}""")

        damaged.forEach { assertNull("json=$it", supersetPartnerCode(it)) }
        assertEquals("SQUATS", supersetPartnerCode(listOf(completed()).encodeToJson(supersetWith = "SQUATS")))
    }

    /**
     * Pairing takes the partner legs away before it puts them back, which is the whole of what makes
     * it idempotent: pairing twice must not leave four rows where two were asked for. So a plan whose
     * rows all carry the second flag pairs to nothing — and no call site can hand it one, because an
     * all-second plan only ever comes out of a decoded payload, where every set is completed, and the
     * pairing effect leaves a plan with any completed set in it alone.
     */
    @Test
    fun `pairing takes the partner legs away before it puts them back`() {
        val legB = listOf(PlannedSet(reps = "10", second = true), PlannedSet(reps = "8", second = true))

        assertEquals(emptyList<PlannedSet>(), legB.paired())
        assertEquals(emptyList<PlannedSet>(), emptyList<PlannedSet>().paired())
        assertEquals(emptyList<PlannedSet>(), emptyList<PlannedSet>().unpaired())
    }

    /**
     * The rating travels through a Bundle as an Int, with -1 standing in for "nobody said". Zero is
     * one comparison away from that sentinel and means the opposite: the set went to failure.
     */
    @Test
    fun `an answered zero is not an unanswered rating`() {
        val plan = listOf(PlannedSet(reps = "5", rir = 0), PlannedSet(reps = "5", rir = null))

        val saved = with(plannedSetsSaver) { SaverScope { true }.save(plan) }
        val restored = plannedSetsSaver.restore(saved!!)

        assertEquals(plan, restored)
        assertEquals(0, restored!!.first().rir)
        assertNull(restored[1].rir)
    }

    /**
     * The key is written only where somebody answered, so an unrated set carries no key at all and
     * reads back null — while a rating of zero is an answer and has to survive as one, a set taken
     * to failure rather than a set nobody spoke about. The decoder defends against the key being
     * missing, not against the number in it: like every other field in a payload, a rating reads
     * back the way it was written, and only the chips ever write one.
     */
    @Test
    fun `an effort rating read out of a payload is the one that was written`() {
        val plan = listOf(completed(reps = "5").copy(rir = 0), completed(reps = "5"))

        val sets = decodeSetsFromJson(plan.encodeToJson())

        assertEquals(0, sets[0].rir)
        assertNull(sets[1].rir)
    }
}
