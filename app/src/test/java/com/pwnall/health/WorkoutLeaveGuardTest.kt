package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A workout is written down by its Save and by nothing sooner, so every road off the form that
 * holds one asks first: back — [workoutInputAtStake] — a tab of the bottom bar — [leavesWorkout] —
 * and a route from a widget or a notification — [routeLeavesWorkout]. Unasked, the bar's tap would
 * open a new trail without a word, and three finished sets would go with the add tab's remembered
 * state.
 */
class WorkoutLeaveGuardTest {
    private val untouched = ACTIVITY_CATALOG.first().code
    private val away = listOf(AppScreen.HOME, AppScreen.MEDICINE, AppScreen.CALENDAR, AppScreen.PROFILE)

    private fun atStake(
        kind: String = "ACTIVITY",
        draftChanged: Boolean = false,
        activityCode: String = untouched,
        note: String = "",
        duration: String = "",
    ) = workoutInputAtStake(kind, draftChanged, activityCode, note, duration)

    /** The form as it opens: back returns to the chooser and a tab is simply followed. */
    @Test
    fun anUntouchedFormHoldsNothingToLose() {
        assertFalse(atStake())
        assertFalse(atStake(note = "  "))
    }

    @Test
    fun aSessionAnotherExerciseANoteOrADurationIsSomethingToLose() {
        assertTrue(atStake(draftChanged = true))
        assertTrue(atStake(activityCode = ACTIVITY_CATALOG.last().code))
        assertTrue(atStake(note = "left knee"))
        assertTrue(atStake(duration = "30"))
    }

    /** The session's flag outlives its form inside the editor; on the chooser or a meal it means nothing. */
    @Test
    fun onlyTheWorkoutFormHoldsAWorkout() {
        assertFalse(atStake(kind = "", draftChanged = true))
        assertFalse(atStake(kind = "EAT", draftChanged = true, note = "left knee"))
    }

    @Test
    fun aTabAwayFromAWorkoutAsksFirst() {
        away.forEach { assertTrue(it.name, leavesWorkout(AppScreen.ADD, workoutAtStake = true, next = it)) }
    }

    /** The add tab's own stop keeps what the tab holds, and with nothing at stake no tab asks anything. */
    @Test
    fun theAddTabsOwnStopAndAnEmptyFormGoStraightThrough() {
        assertFalse(leavesWorkout(AppScreen.ADD, workoutAtStake = true, next = AppScreen.ADD))
        AppScreen.entries.forEach {
            assertFalse(it.name, leavesWorkout(AppScreen.ADD, workoutAtStake = false, next = it))
        }
    }

    /** The form goes on saying it while its page fades out under the tab that replaced it. */
    @Test
    fun aFlagLeftOverUnderAnotherTabAsksNothing() {
        assertFalse(leavesWorkout(AppScreen.CALENDAR, workoutAtStake = true, next = AppScreen.PROFILE))
        assertFalse(routeLeavesWorkout(AppScreen.CALENDAR, workoutAtStake = true, route = WidgetRoute.MOOD))
    }

    /** The meal's route stays on the add tab and still takes the workout: the tab becomes the food form. */
    @Test
    fun aRouteOffAWorkoutAsksFirst() {
        listOf(WidgetRoute.FOOD, WidgetRoute.MOOD, WidgetRoute.TRACK, WidgetRoute.MEDICINE, WidgetRoute.WORK).forEach {
            assertTrue(it, routeLeavesWorkout(AppScreen.ADD, workoutAtStake = true, route = it))
            assertFalse(it, routeLeavesWorkout(AppScreen.ADD, workoutAtStake = false, route = it))
        }
    }

    /** A drink's dialog opens over the app as it stands, and the exercise shortcut opens the workout's own form. */
    @Test
    fun aRouteThatLeavesTheWorkoutStandingIsFollowedAtOnce() {
        listOf(WidgetRoute.WATER, WidgetRoute.CAFFEINE, WidgetRoute.EXERCISE, null).forEach {
            assertFalse(it ?: "no route", routeLeavesWorkout(AppScreen.ADD, workoutAtStake = true, route = it))
        }
    }

    /**
     * A saved workout is no draft as it opens, though it is no empty plan either: what it can lose is
     * a correction. A set put right, added or under way is one while it is still in the session's
     * plan, and still one once End has handed it to the form and the summary's Done has not written
     * it — the page has no Save of its own, so back must not leave with it gone. A new entry is told
     * apart on its own terms, and End's own spelling of the same sets is no correction.
     */
    @Test
    fun aCorrectionToASavedWorkoutIsSomethingToLose() {
        val saved = listOf(PlannedSet(reps = "10", load = "60", completed = true, actualSeconds = 40))
        val corrected = saved.map { it.copy(reps = "8") }
        fun draft(plan: List<PlannedSet>, running: Boolean = false, savedAs: List<PlannedSet>? = saved) =
            sessionHoldsDraft(plan, running, paired = false, savedAs = savedAs)

        assertFalse(draft(saved))
        assertTrue(draft(corrected))
        assertTrue(draft(saved + PlannedSet()))
        assertTrue(draft(saved, running = true))
        assertFalse(draft(listOf(PlannedSet()), savedAs = null))
        assertTrue(draft(saved, savedAs = null))
        assertTrue(sessionHoldsDraft(listOf(PlannedSet()), running = false, paired = true, savedAs = null))

        val row = saved.encodeToJson()
        assertFalse(savedWorkoutCorrected(draftChanged = false, formSets = row, rowSets = row))
        assertTrue(savedWorkoutCorrected(draftChanged = true, formSets = row, rowSets = row))
        assertTrue(savedWorkoutCorrected(draftChanged = false, formSets = corrected.encodeToJson(), rowSets = row))
        assertFalse(savedWorkoutCorrected(draftChanged = false, formSets = saved.encodeToJson("PLANK"), rowSets = row))
        assertFalse(savedWorkoutCorrected(draftChanged = false, formSets = "", rowSets = null))
        // The second half of a pair opens with its flags dropped, so End alone hands it back without them.
        val secondHalf = saved.map { it.copy(second = true) }.encodeToJson()
        assertFalse(savedWorkoutCorrected(draftChanged = false, formSets = row, rowSets = secondHalf))

        val editor = File("src/main/java/com/pwnall/health/EntryEditorScreen.kt").readText()
        assertTrue("BackHandler(enabled = correctionAtStake && !showActivitySummary)" in editor)
    }

    /**
     * The form's own exercise button is a fourth road: a counted exercise draws the rep counter
     * where the session stood, and the session would go with its page. While one is at stake the
     * button offers none of them — see [mainExerciseChoices].
     */
    @Test
    fun aSessionUnderWayIsNotOfferedAnExerciseThatWouldReplaceIt() {
        val offered = mainExerciseChoices(draftChanged = true, workoutSetsJson = "")
        assertTrue(offered.none { it.counted })
        assertEquals(PICKABLE_ACTIVITIES.filterNot { it.counted }, offered)
    }

    /** Nothing to lose, nothing held back: squats are how the rep counter's form is reached at all. */
    @Test
    fun anUntouchedFormIsOfferedTheWholeCatalogue() {
        val offered = mainExerciseChoices(draftChanged = false, workoutSetsJson = "")
        assertEquals(PICKABLE_ACTIVITIES, offered)
        assertTrue(offered.any { it.code == "SQUATS" })
    }

    /** A row that holds its sets keeps the session whatever it is called, so it may be called anything. */
    @Test
    fun aRowThatHoldsItsSetsCanBeGivenAnyExercise() {
        val finished = listOf(PlannedSet(reps = "10", completed = true)).encodeToJson()
        assertEquals(PICKABLE_ACTIVITIES, mainExerciseChoices(draftChanged = true, workoutSetsJson = finished))
    }

    /**
     * The pair's second exercise is never offered as its first: chosen there, both halves would be
     * counted on the first row and the second written again beside it.
     */
    @Test
    fun theExerciseButtonDoesNotOfferThePartner() {
        val partner = PICKABLE_ACTIVITIES.first { !it.counted }

        val offered = mainExerciseChoices(draftChanged = true, workoutSetsJson = "", partner = partner)

        assertTrue(offered.none { it.code == partner.code })
        assertEquals(PICKABLE_ACTIVITIES.filterNot { it.counted || it.code == partner.code }, offered)
    }

    /** A machine's numbers replace the session, so they ask first wherever one is under way or ended. */
    @Test
    fun aMachinesNumbersAskBeforeTheyReplaceASession() {
        val ended = listOf(PlannedSet(reps = "10", completed = true)).encodeToJson()

        assertFalse(machineReplacesSession(draftChanged = false, workoutSetsJson = ""))
        assertTrue(machineReplacesSession(draftChanged = true, workoutSetsJson = ""))
        assertTrue(machineReplacesSession(draftChanged = false, workoutSetsJson = ended))
        // A console's frame from an earlier ride is no session to lose.
        assertFalse(machineReplacesSession(draftChanged = false, workoutSetsJson = """{"machineType":"ROWER"}"""))
    }
}
