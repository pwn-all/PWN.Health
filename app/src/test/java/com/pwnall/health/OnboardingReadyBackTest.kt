package com.pwnall.health

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The finish once Start is pressed. The way out plays for under two seconds and then saves, and the
 * system's Back is not taken during it, as the finish's own back arrow is gone by then: going a page
 * back in the questionnaire while the finish, still leaving, saves regardless would end onboarding
 * over the page Back had opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h913dp-xxhdpi", application = android.app.Application::class)
class OnboardingReadyBackTest {
    private val compose = createComposeRule()

    @get:Rule val rules: TestRule = ScreenShots.hosted(compose)

    @Test
    fun systemBackIsNotTakenOnceStartIsPressed() {
        var backs = 0
        var starts = 0
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PwnFitTheme(darkTheme = false) {
                // The questionnaire around the finish, whose Back goes a page down.
                BackHandler { backs++ }
                OnboardingReadyScreen(
                    ReadySummary(stepGoal = 8_000, restingKcal = 1_500, bmi = 22.0, bmiLabel = R.string.bmi_normal),
                    onBack = {},
                    onStart = { starts++ },
                )
            }
        }
        fun back() {
            compose.runOnIdle { dispatcher?.onBackPressed() }
        }

        back()
        assertEquals(1, backs)
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.common_start)).performClick()
        back()
        compose.waitForIdle()
        assertEquals(1, backs)
        assertEquals(1, starts)
    }
}
