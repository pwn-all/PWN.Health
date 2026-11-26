package com.pwnall.health

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutContinuationTest {
    private val hold = ACTIVITY_CATALOG.first { !it.counted && it.input == ActivityInput.DURATION }
    private val weighted = ACTIVITY_CATALOG.first { !it.counted && it.input == ActivityInput.WEIGHTED_SETS }

    @Test
    fun switchingKeepsCompletedWorkAndRestAndReplacesUnusedSets() {
        val done = PlannedSet(completed = true, actualSeconds = 45, actualRestSeconds = 20)
        val next = listOf(done, PlannedSet(), PlannedSet()).continueWithExercise(weighted.code)
        assertEquals(2, next.size)
        assertEquals(done, next.first())
        assertEquals(weighted, next.last().exercise(hold, null))
        assertFalse(next.last().completed)
        assertEquals(0, next.last().actualSeconds)
    }

    /** The rest chosen for the session goes on to an exercise added to it, as it does to the next set. */
    @Test
    fun anAddedExerciseRestsAsLongAsTheSetsBeforeIt() {
        val done = PlannedSet(completed = true, restSeconds = 180)
        assertEquals(180, listOf(done, PlannedSet()).continueWithExercise(weighted.code).last().restSeconds)
        assertEquals(DEFAULT_REST_SECONDS, listOf(PlannedSet()).continueWithExercise(weighted.code).last().restSeconds)
    }

    @Test
    fun setNumbersArePerExerciseIncludingReturnToTheFirstExercise() {
        val plan = listOf(
            PlannedSet(completed = true),
            PlannedSet(completed = true, exerciseCode = weighted.code),
            PlannedSet(completed = true, exerciseCode = weighted.code),
            PlannedSet(exerciseCode = hold.code),
        )
        assertEquals(listOf(0, 0, 1, 1), plan.indices.map { plan.exerciseSetIndex(it, hold, null) })
    }

    @Test
    fun repeatedExercisesRemainInPerformedOrderWhenSaved() {
        val plan = listOf(
            PlannedSet(completed = true, actualSeconds = 45),
            PlannedSet(completed = true, actualSeconds = 30, exerciseCode = weighted.code),
            PlannedSet(completed = true, actualSeconds = 20, exerciseCode = weighted.code),
            PlannedSet(completed = true, actualSeconds = 60, exerciseCode = hold.code),
            PlannedSet(exerciseCode = weighted.code),
        )
        val groups = plan.completedExerciseGroups(hold, null)
        assertEquals(listOf(hold, weighted, hold), groups.map { it.first })
        assertEquals(listOf(1, 2, 1), groups.map { it.second.size })
        assertEquals(155.0 / 60, groups.sumOf { it.second.aggregate().durationMinutes }, 0.0001)
    }

    @Test
    fun supersetFollowedByAnotherExerciseKeepsBothLegs() {
        val plan = listOf(
            PlannedSet(completed = true, actualSeconds = 20),
            PlannedSet(completed = true, second = true, actualSeconds = 30),
            PlannedSet(completed = true, actualSeconds = 25),
            PlannedSet(completed = true, second = true, actualSeconds = 35),
            PlannedSet(completed = true, exerciseCode = hold.code, actualSeconds = 40),
        )
        val groups = plan.completedExerciseGroups(hold, weighted)
        assertEquals(listOf(hold, weighted, hold), groups.map { it.first })
        assertEquals(listOf(2, 2, 1), groups.map { it.second.size })
        assertTrue(handsOver(plan, 0))
        assertFalse(handsOver(plan, 3))
    }

    @Test
    fun restBeforeNextExerciseSurvivesSavingAnIndividualJournalRow() {
        val completed = listOf(PlannedSet(completed = true, actualSeconds = 45, actualRestSeconds = 60))
        val restored = decodeSetsFromJson(completed.encodeToJson(preserveTrailingRest = true))
        assertEquals(completed.aggregate(), restored.aggregate())
        assertEquals(60, restored.single().actualRestSeconds)
    }

    @Test
    fun mixedExercisesSurviveSavedStateAndSummaryPayload() {
        val plan = listOf(
            PlannedSet(completed = true, actualSeconds = 30, actualRestSeconds = 60),
            PlannedSet(completed = true, exerciseCode = weighted.code, reps = "20", load = "30", actualSeconds = 40),
        )
        val saved = with(plannedSetsSaver) { SaverScope { true }.save(plan) }
        assertEquals(plan, plannedSetsSaver.restore(saved!!))
        val decoded = decodeSetsFromJson(plan.encodeToJson(loadUnitFactor = 2.2046226218), 2.2046226218)
        assertEquals(plan, decoded)
        assertEquals(listOf(hold, weighted), decoded.map { it.exercise(hold, null) })
    }
}
