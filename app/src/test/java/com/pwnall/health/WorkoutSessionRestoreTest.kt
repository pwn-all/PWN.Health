package com.pwnall.health

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A phone turned on the bench recreates the activity, and the session has to come back as it was:
 * the set done, and the rest after it still under way. The plan's own saver has its tests
 * (PlannedSetsSaverTest); this is the session around it — which set runs, whether it rests — saved
 * and restored as the activity would, on Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h913dp-xxhdpi", application = android.app.Application::class)
class WorkoutSessionRestoreTest {
    private val compose = createComposeRule()

    @get:Rule val rules: TestRule = ScreenShots.hosted(compose)

    private val bench = ACTIVITY_CATALOG.first { it.code == "BENCH_PRESS" }

    private fun text(res: Int) = RuntimeEnvironment.getApplication().getString(res)

    private fun tap(res: Int) {
        compose.onNodeWithText(text(res)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun resting(): Boolean =
        compose.onAllNodesWithText(text(R.string.workout_resting)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aSetDoneAndTheRestAfterItSurviveTheActivityBeingRecreated() {
        var finished: List<PlannedSet>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PwnFitTheme(darkTheme = false) {
                WorkoutSessionInline(
                    spec = bench,
                    loadUnitRes = R.string.unit_kg,
                    loadUnitFactor = 1.0,
                    workoutSetsJson = "",
                    partner = null,
                    onPartner = {},
                    onUnpair = {},
                    onFinished = { finished = it },
                    onDraftChanged = {},
                    allowAdditionalExercises = true,
                )
            }
        }
        tap(R.string.workout_start_workout)
        tap(R.string.workout_complete_set)
        tap(R.string.answer_yes)
        assertTrue(resting())

        restoration.emulateSavedInstanceStateRestore()

        // Resting still, and not the paused session a lost pointer would have drawn over the same plan.
        assertTrue(resting())
        tap(R.string.workout_end_workout)
        assertEquals(listOf(true), finished!!.map { it.completed })
    }
}
