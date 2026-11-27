package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A superset is a plan where every round is two rows, and the gap inside a round is not a rest.
 *
 * Every one of these is about parity or about where a rest is allowed to appear. Get either wrong
 * and the damage is silent: the plan still runs, the rest card still counts, and the session saves
 * two rows that describe work nobody did in that order.
 *
 * The payload's round trip is here too, with the real `org.json` on the test classpath:
 * the load a set stores is kilograms and has to come back in the unit of whoever reads it, and a
 * payload grows no key it has nothing to put under, because `journalKey` hashes it as a row's
 * identity.
 */
class SupersetPlanTest {
    private fun plan(vararg reps: String) = reps.map { PlannedSet(reps = it, load = "50") }

    private val poundsPerKilogram = 2.2046226218

    private fun done(load: String) = listOf(PlannedSet(reps = "10", load = load, completed = true, actualSeconds = 30))

    /**
     * The partner row of a finished superset is saved with `"second": true` on every set it holds,
     * and nothing restores the pairing when that row is re-opened. Read back with the flags intact,
     * it would describe a first leg that is not in the plan; "New set" would then look for one, and
     * `List.last {}` would take the process down with a NoSuchElementException.
     */
    @Test
    fun aPartnerRowReopenedWithNoPartnerIsAPlainPlan() {
        val json = done("20").map { it.copy(second = true) }.encodeToJson("bench_press")

        val unpaired = plannedSetsFor(json, loadUnitFactor = 1.0, paired = false)

        assertTrue(unpaired.isNotEmpty())
        assertFalse(unpaired.any { it.second })
    }

    @Test
    fun aPartnerStillInPlayKeepsTheSecondHalvesItWasSavedWith() {
        val json = (done("20") + done("20").map { it.copy(second = true) }).encodeToJson("bench_press")

        val paired = plannedSetsFor(json, loadUnitFactor = 1.0, paired = true)

        assertEquals(listOf(false, true), paired.map { it.second })
    }

    /** An empty payload still has to open on something to type into. */
    @Test
    fun anEmptyPayloadOpensOnOneBlankSet() {
        assertEquals(1, plannedSetsFor("", loadUnitFactor = 1.0, paired = false).size)
    }

    @Test
    fun pairingDoublesThePlanAndFlagsEverySecondRow() {
        val paired = plan("10", "8").paired()

        assertEquals(4, paired.size)
        assertEquals(listOf(false, true, false, true), paired.map { it.second })
    }

    @Test
    fun aPartnerLegStartsEmptyRatherThanInheritingTheOtherExercisesNumbers() {
        val paired = plan("10").paired()

        // Ten reps of a bench press says nothing about how many curls follow it, and a load in
        // kilograms says even less. The row is the person's to fill in.
        assertEquals("", paired[1].reps)
        assertEquals("", paired[1].load)
        assertEquals("10", paired[0].reps)
    }

    /**
     * A hold's typed seconds are where its clock starts, so they are not copied onto the partner's
     * row with everything but the reps and the load: a minute's plank paired with a wall sit would
     * give the wall sit a minute it never held. The rest is all the partner keeps, because the round
     * serves it after the second half.
     */
    @Test
    fun aPartnerLegKeepsOnlyTheRestOfTheSetItFollows() {
        val paired = listOf(PlannedSet(actualSeconds = 60, restSeconds = 90)).paired()

        assertEquals(PlannedSet(restSeconds = 90, second = true), paired[1])
        assertEquals(60, paired[0].actualSeconds)
    }

    @Test
    fun pairingIsIdempotent() {
        // The effect that pairs the plan is keyed on the partner's code and guarded, but a plan
        // that doubled twice would be the worst kind of bug: four rows where two were asked for.
        assertEquals(plan("10", "8").paired(), plan("10", "8").paired().paired())
    }

    @Test
    fun unpairingPutsThePlanBackExactlyAsItWas() {
        val singles = plan("10", "8")

        assertEquals(singles, singles.paired().unpaired())
    }

    @Test
    fun everyRoundHasTwoHalvesAfterPairing() {
        val paired = plan("10", "8", "6").paired()

        assertEquals(paired.count { it.second }, paired.count { !it.second })
    }

    @Test
    fun theGapInsideARoundIsNotARest() {
        val paired = plan("10", "8").paired()

        // Index 0 hands straight over to its partner; index 1 ends the round and rests before the
        // next one. That alternation is the whole of what a superset is.
        assertTrue(handsOver(paired, 0))
        assertFalse(handsOver(paired, 1))
        assertTrue(handsOver(paired, 2))
        assertFalse(handsOver(paired, 3))
    }

    @Test
    fun anUnpairedPlanNeverHandsOver() {
        val singles = plan("10", "8", "6")

        singles.indices.forEach { assertFalse(handsOver(singles, it)) }
    }

    @Test
    fun theLastSetOfAPairedPlanHandsOverToNothing() {
        val paired = plan("10").paired()

        assertFalse(handsOver(paired, paired.lastIndex))
    }

    @Test
    fun rpeCountsTheSameEffortFromTheOtherEnd() {
        // Nothing left in the tank is RPE 10; two reps in reserve is RPE 8. The table a lifter
        // knows, and the only arithmetic this feature does.
        assertEquals(10, 0.rpeFromRir())
        assertEquals(8, 2.rpeFromRir())
        assertEquals(6, RIR_CHOICES.last.rpeFromRir())
    }

    @Test
    fun everyChipOffersAnRpeTheScaleActuallyUses() {
        // Below RPE 6 a set is a warm-up and nobody rates it, so the chips stop there.
        RIR_CHOICES.forEach { assertTrue(it.rpeFromRir() in 6..10) }
    }

    @Test
    fun anEffortNobodyRatedStaysUnrated() {
        // The default has to survive every plan transformation: an unrated set is the common case,
        // and a zero standing in for "unanswered" would read as "went to failure".
        assertNull(PlannedSet().rir)
        plan("10").paired().forEach { assertNull(it.rir) }
    }

    /** A metric row is the row every backup ever made holds, and it may not change by a byte. */
    @Test
    fun aMetricPayloadEncodesExactlyAsItAlwaysDid() {
        val json = done("50").encodeToJson()
        assertEquals(json, done("50").encodeToJson(loadUnitFactor = 1.0))
        val root = JSONObject(json)
        assertFalse(root.has("loadInKg"))
        assertEquals(50.0, root.getJSONArray("sets").getJSONObject(0).getDouble("loadKg"), 0.0)
        assertEquals("50", decodeSetsFromJson(json).single().load)
    }

    /**
     * On an imperial profile the set box says "lb" and the person types pounds. Kept as typed under
     * `loadKg` while the row's own column is converted, that figure would read 100 lb as 100 kg on a
     * profile switched to metric, and the summary's volume would come out 2.2 times too high. The
     * payload stores kilograms, like the column, and carries no word about its unit: there is one.
     */
    @Test
    fun anImperialPayloadStoresKilograms() {
        val json = done("100").encodeToJson(loadUnitFactor = poundsPerKilogram)
        val root = JSONObject(json)
        assertFalse(root.has("loadInKg"))
        assertEquals(45.36, root.getJSONArray("sets").getJSONObject(0).getDouble("loadKg"), 0.01)
        // Back on the profile that wrote it, and on the other one.
        assertEquals("100", decodeSetsFromJson(json, poundsPerKilogram).single().load)
        assertEquals("45.4", decodeSetsFromJson(json, 1.0).single().load)
    }

    /**
     * A payload is kilograms whichever profile reads it. An unmarked one read "as it was
     * typed" would have a metric row opened on a profile since switched to pounds show its 100 kg as
     * "100 lb", and ending the session again would save 45 kg over it. A unit marker some imperial
     * rows carry is read past: such a row is kilograms already.
     */
    @Test
    fun aPayloadIsKilogramsWhicheverProfileReadsIt() {
        val metric = """{"sets":[{"reps":10,"loadKg":100,"seconds":30}]}"""
        assertEquals("100", decodeSetsFromJson(metric, 1.0).single().load)
        val inPounds = decodeSetsFromJson(metric, poundsPerKilogram)
        assertEquals("220.5", inPounds.single().load)
        // Ended again on the pounds profile, it is still the 100 kg it was — to the box's one decimal.
        val again = JSONObject(inPounds.encodeToJson(loadUnitFactor = poundsPerKilogram))
        assertEquals(100.0, again.getJSONArray("sets").getJSONObject(0).getDouble("loadKg"), 0.05)

        val marked = """{"sets":[{"reps":10,"loadKg":45.359237,"seconds":30}],"loadInKg":true}"""
        assertEquals("100", decodeSetsFromJson(marked, poundsPerKilogram).single().load)
    }

    // --- correcting a finished session -------------------------------------------------------------

    private val own = ACTIVITY_CATALOG.first { !it.counted && it.input == ActivityInput.WEIGHTED_SETS }
    private val other = ACTIVITY_CATALOG.first { !it.counted && it.input == ActivityInput.DURATION }

    private fun finished(reps: String, second: Boolean = false, code: String? = null) =
        PlannedSet(reps = reps, completed = true, actualSeconds = 30, second = second, exerciseCode = code)

    /** A set typed wrong and beyond mending is taken out on its own, and the rest served after it with it. */
    @Test
    fun aFinishedSetIsTakenOutOfThePlan() {
        val plan = listOf(finished("10").copy(actualRestSeconds = 90), finished("600"), finished("8"))

        assertEquals(listOf("10", "8"), plan.withoutSet(1, own, null)?.map { it.reps })
        assertEquals(1.0, plan.withoutSet(0, own, null)?.aggregate()?.durationMinutes ?: 0.0, 1e-9)
    }

    /** A round goes whole, from either of its rows: half a round is a pair that is not a pair. */
    @Test
    fun takingOutEitherHalfOfARoundTakesTheRound() {
        val plan = listOf(finished("10"), finished("12", second = true), finished("9"), finished("11", second = true))

        val kept = listOf("9", "11")
        assertEquals(kept, plan.withoutSet(0, own, other)?.map { it.reps })
        assertEquals(kept, plan.withoutSet(1, own, other)?.map { it.reps })
        assertEquals(listOf(false, true), plan.withoutSet(1, own, other)?.map { it.second })
    }

    /**
     * The row the form saves is its own exercise's, and the summary opens only on a session that has
     * a set of it: the last one stays, however many sets of another exercise stand beside it.
     */
    @Test
    fun theLastSetOfTheSessionsOwnExerciseStays() {
        assertNull(listOf(finished("10")).withoutSet(0, own, null))
        assertNull(listOf(finished("10"), finished("12", second = true)).withoutSet(1, own, other))

        val mixed = listOf(finished("10"), finished("5", code = other.code), finished("6", code = other.code))
        assertNull(mixed.withoutSet(0, own, null))
        assertEquals(listOf("10", "6"), mixed.withoutSet(1, own, null)?.map { it.reps })
    }

    // --- the rest a session counts ------------------------------------------------------------------

    /**
     * A phone left on the rest card overnight and ended in the morning would write the night into the
     * row's minutes and its energy. A rest counts for what the clock shows, up to a quarter of an hour.
     */
    @Test
    fun aRestCountsForNoLongerThanTheCeiling() {
        assertEquals(90, countedRest(90))
        assertEquals(MAX_COUNTED_REST_SECONDS, countedRest(MAX_COUNTED_REST_SECONDS))
        assertEquals(MAX_COUNTED_REST_SECONDS, countedRest(12 * 60 * 60))
        assertEquals(0, countedRest(-5))
    }

    /**
     * A rest is planned no longer than it is counted: wheels going to 99 minutes beside a rest card's
     * clock that stops at fifteen would leave the ring of a long rest never closing. The minutes
     * wheel ends on the ceiling's minute, and seconds turned past it come to the ceiling. Both of the
     * session's rest rows that can be edited are given it; the wheels of a set's own time are not.
     */
    @Test
    fun aRestIsPlannedNoLongerThanItCounts() {
        assertEquals(14 * 60 + 59, pickedSeconds(14, 59, MAX_COUNTED_REST_SECONDS))
        assertEquals(MAX_COUNTED_REST_SECONDS, pickedSeconds(15, 0, MAX_COUNTED_REST_SECONDS))
        assertEquals(MAX_COUNTED_REST_SECONDS, pickedSeconds(15, 40, MAX_COUNTED_REST_SECONDS))
        assertEquals(99 * 60 + 59, pickedSeconds(99, 59, MAX_PICKED_SECONDS))

        val session = File("src/main/java/com/pwnall/health/WorkoutSession.kt").readText()
        assertEquals(2, session.split("maxSeconds = MAX_COUNTED_REST_SECONDS,").size - 1)
        assertEquals(1, session.split("MAX_SESSION_REST_SECONDS").size)
    }
}
