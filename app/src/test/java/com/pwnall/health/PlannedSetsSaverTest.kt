package com.pwnall.health

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A running session lives in saved state, because a phone turned on the bench recreates the
 * activity, and a plan held in plain memory would come back empty with every completed set gone.
 * The saver flattens sets to Bundle-friendly fields; this is the check that it reads them back
 * whole, including the two things [encodeToJson] would drop — sets not yet done, and the rest each
 * of them is going to take.
 */
class PlannedSetsSaverTest {
    @Test
    fun aPlanSurvivesTheRoundTrip() {
        val plan = listOf(
            PlannedSet(
                reps = "8",
                load = "60",
                restSeconds = 90,
                completed = true,
                actualSeconds = 41,
                actualRestSeconds = 88,
                rir = 2,
                floors = 3,
            ),
            PlannedSet(reps = "12", load = "", restSeconds = 60, second = true),
            PlannedSet(),
        )

        val saved = with(plannedSetsSaver) { SaverScope { true }.save(plan) }
        val restored = plannedSetsSaver.restore(saved!!)

        assertEquals(plan, restored)
        assertNull(restored!![1].rir)
    }

    /** "Last time" reads a row's own sets: its reps column is their sum, so 6 and 4 never read "2 × 10". */
    @Test
    fun lastTimeSaysTheSetsAsTheyWere() {
        fun row(vararg reps: Int) = reps.map { PlannedSet(reps = "$it", completed = true) }.encodeToJson()

        assertEquals("6 + 4", sessionRepsText(row(6, 4)))
        assertEquals("2 × 5", sessionRepsText(row(5, 5)))
        assertNull(sessionRepsText(null))
        assertNull(sessionRepsText(listOf(PlannedSet(completed = true, actualSeconds = 60)).encodeToJson()))
    }
}
