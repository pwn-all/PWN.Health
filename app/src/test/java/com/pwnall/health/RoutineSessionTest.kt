package com.pwnall.health

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** What a session asks about its sets, and what of their answers reaches the journal. */
class RoutineSessionTest {
    private val profile = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1996-06-15")
    private val day = LocalDate.of(2026, 11, 14)
    private val finish = 1_794_000_000_000L

    /**
     * Two sets on the stairs in Freestyle, the first with the three floors the barometer counted and
     * the person confirmed. The floors reach the row's sets, not only the session's last page. A set
     * with none, and a lift, keep their payload as it was.
     */
    @Test
    fun theFloorsOfAClimbReachTheJournalWithItsSets() {
        val climb = freestyleOutcomes(
            listOf(
                FreeSet("STAIRS", seconds = 120, reps = 156, floors = 3),
                FreeSet("STAIRS", seconds = 90, reps = 110),
            ),
        )
        val lift = freestyleOutcomes(listOf(FreeSet("BENCH_PRESS", seconds = 40, reps = 10, loadKg = 60.0)))

        val stairs = sessionRows(NamedResources(), profile, climb, day, finish).single()
        val press = sessionRows(NamedResources(), profile, lift, day, finish).single()

        val sets = JSONObject(checkNotNull(stairs.measurementPayloadJson)).getJSONArray("sets")
        assertEquals(3, sets.getJSONObject(0).getInt("floors"))
        assertEquals(156, sets.getJSONObject(0).getInt("reps"))
        assertFalse(sets.getJSONObject(1).has("floors"))
        assertFalse("floors" in checkNotNull(press.measurementPayloadJson))

        // The editor reads them back with the set, says them beside its steps, and saves them again:
        // a correction of the row keeps them.
        val reopened = decodeSetsFromJson(stairs.measurementPayloadJson)
        val names = NamedResources()
        assertEquals(3, reopened[0].floors)
        assertEquals(trackedText(names, "STAIRS", 156, 3), countedSetText(names, "STAIRS", reopened[0]))
        assertEquals("", countedSetText(names, "BENCH_PRESS", PlannedSet(reps = "10")))
        val saved = JSONObject(reopened.encodeToJson()).getJSONArray("sets")
        assertEquals(3, saved.getJSONObject(0).getInt("floors"))
        assertFalse(saved.getJSONObject(1).has("floors"))
    }

    /**
     * "Complete set" held on a set of reps. A phone that counted it asks whether the count is right.
     * One that lost the signal part-way through asks too, as Skip does, rather than writing the set as
     * planned as if the person had done every rep. A phone with no sensor for it never counted
     * anything and takes the set as planned.
     */
    @Test
    fun aSetWhoseCountWasLostIsAskedAboutAndOneNeverCountedIsNot() {
        assertTrue(completeAsks(followed = true, blind = false, interrupted = false))
        assertTrue(completeAsks(followed = true, blind = true, interrupted = true))
        assertFalse(completeAsks(followed = true, blind = true, interrupted = false))
        assertFalse(completeAsks(followed = false, blind = false, interrupted = false))
    }
}
