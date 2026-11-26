package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * What the phone counts in a workout, where it has to be to count it, and the way a count travels from
 * Stop through the session to the journal; and the workouts it cannot count, which the picker groups.
 */
class ExerciseTrackingTest {

    /** 50 Hz as whole nanoseconds, as the sensor tests build their traces. */
    private val periodNanos = 20_000_000L

    private fun trace(values: List<Double>): List<Accel> =
        values.mapIndexed { i, v -> Accel(i * periodNanos, 0f, 0f, v.toFloat()) }

    /** Every exercise the phone counts says where the phone goes; nothing the clock alone measures does. */
    @Test
    fun everyCountedExerciseSaysWhereThePhoneGoes() {
        (REP_PROFILES.keys + ON_FOOT_CODES).forEach { assertNotNull(it, phonePlacementRes(it)) }
        OTHER_WORKOUT_CODES.forEach { assertNull(it, phonePlacementRes(it)) }
        // The glute bridge is a turn of the thigh, so the phone rides on it, not in a back pocket.
        assertEquals(R.string.track_phone_thigh, phonePlacementRes("HIP_THRUST"))
        assertEquals(R.string.track_phone_pocket, phonePlacementRes("CALF_RAISE"))
        assertEquals(R.string.track_phone_stairs, phonePlacementRes("STAIRS"))
        assertNull(phonePlacementRes("PLANK"))
    }

    /** The group is the catalogue's own timed workouts: none of them counted, none walked. */
    @Test
    fun theOtherWorkoutsAreTimedCatalogueEntriesThePhoneCannotCount() {
        val codes = ACTIVITY_CATALOG.map { it.code }.toSet()
        assertTrue(codes.containsAll(OTHER_WORKOUT_CODES))
        assertTrue(codes.containsAll(ON_FOOT_CODES))
        ACTIVITY_CATALOG.filter { it.code in OTHER_WORKOUT_CODES }.forEach {
            assertEquals(it.code, ActivityInput.DURATION, it.input)
            assertFalse(it.code, it.counted)
        }
        assertTrue((OTHER_WORKOUT_CODES intersect ON_FOOT_CODES).isEmpty())
    }

    /**
     * A counted set stops on what the phone counted — a climb's steps and floors — asks, and keeps both
     * through the saved session and into the sets the journal is written from.
     */
    @Test
    fun aTrackedSetStopsOnItsCountAndKeepsItThroughToTheJournal() {
        val climbing = FreestyleState().choosing("STAIRS").beginning(0)
        val asked = climbing.stopping(90_000, counted = false, unitFactor = 1.0, tracked = TrackedCount(156, 3))
        assertEquals(90, asked.entering)
        assertEquals("156", asked.entryReps)
        assertEquals("3", asked.entryFloors)
        assertEquals(asked, freestyleState(asked.json()))

        val set = asked.entered(unitFactor = 1.0).sets.single()
        assertEquals(FreeSet("STAIRS", 90, reps = 156, floors = 3), set)
        val figures = routineSets(freestyleOutcomes(listOf(set)).single().exercise).single()
        assertEquals(156, figures.reps)
        assertEquals(3, figures.floors)
        assertEquals(90, figures.seconds)
        // Untracked, a timed set is done on Stop.
        val danced = FreestyleState().choosing("DANCING").beginning(0).stopping(60_000, false, 1.0)
        assertEquals(listOf(FreeSet("DANCING", 60)), danced.sets)
    }

    /** A session saved with no floors field still reads back. */
    @Test
    fun aSessionSavedWithoutFloorsReadsBack() {
        val old = """{"sets":[{"code":"WALK","seconds":60,"reps":0,"rest":0}],"code":"WALK","sessionStart":0,""" +
            """"setStart":-1,"setEnd":60000,"entering":-1,"entryReps":"","entryLoad":""}"""
        val state = freestyleState(old)
        assertEquals(listOf(FreeSet("WALK", 60)), state.sets)
        assertEquals("", state.entryFloors)
    }

    /** "3 floors · 156 steps", "120 jumps": a counted set in its own words; repetitions in the usual ones. */
    @Test
    fun aCountedSetIsSaidInItsOwnWords() {
        val names = NamedResources()
        assertEquals(
            "track_floors[3] · track_steps[156]",
            routineSetText(names, RoutineSet(reps = 156, seconds = 90, floors = 3), imperial = false, code = "STAIRS"),
        )
        assertEquals(
            "track_jumps[120]",
            routineSetText(names, RoutineSet(reps = 120, seconds = 60), imperial = false, code = "JUMP_ROPE"),
        )
        assertEquals("12 workout_reps_short", routineSetText(names, RoutineSet(reps = 12), imperial = false, "SQUATS"))
        assertEquals("track_steps[0]", trackedText(names, "WALK", 0, 0))
    }

    /** Heels coming down onto the floor ten times, a calm second and a half apart, are ten calf raises. */
    @Test
    fun tenHeelDropsAreTenCalfRaises() {
        val rep = List(72) { 9.81 } + List(3) { 14.0 }
        val raises = trace(List(25) { 9.81 } + List(10) { rep }.flatten() + List(25) { 9.81 })

        assertEquals(10, RepCounter.count(raises, REP_PROFILES.getValue("CALF_RAISE")).reps)
    }

    /**
     * A glute bridge as the thigh's gyroscope reads it: a turn up, the hold at the top, a turn down and
     * the rest on the floor — two peaks a bridge, ten bridges.
     */
    @Test
    fun tenBridgesAreTenGluteBridges() {
        val turn = List(30) { 1.2 * sin(PI * it / 30) }
        val rep = turn + List(50) { 0.0 } + turn + List(40) { 0.0 }
        val bridges = trace(List(10) { rep }.flatten() + List(25) { 0.0 })

        assertEquals(10, RepCounter.count(bridges, REP_PROFILES.getValue("HIP_THRUST")).reps)
    }

    /**
     * Behind a locked screen Android stops delivering the accelerometer to an app with nothing in the
     * foreground, and the count would go on as if the set had ended there. A sample late by a frame or by
     * a busy second is still the signal; seconds of nothing from a sensor read fifty times a second
     * is the signal gone, and the set says so instead of offering the part it caught.
     */
    @Test
    fun aRepSignalSilentForSecondsIsLostAndALateSampleIsNot() {
        val last = 40_000L
        assertFalse(signalLost(last, last + 20))
        assertFalse(signalLost(last, last + 1_500))
        assertFalse(signalLost(last, last + 5_000))
        assertTrue(signalLost(last, last + 5_001))
        assertTrue(signalLost(last, last + 60_000))
    }
}
