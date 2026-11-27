package com.pwnall.health

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/**
 * What onboarding's Next waits for, on the screen itself and in every `check`.
 *
 * An age page that opened on a date of its own with Next lit would let tapping straight through
 * record an adult and open everything the age gate closes. [pickedBirthDate] and [pageBefore] are
 * pure and tested, but whether a button asks them is a property of the composed screen, and the
 * screen shots ([OnboardingShotsTest]) assert nothing and run only with -Pscreens. This one walks
 * the same pages on Robolectric and holds the three things the gate rests on: no date of birth, no
 * Next; no sex chosen, no Next; and Back from the first question ends at the greeting, not before
 * it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h913dp-xxhdpi", application = android.app.Application::class)
class OnboardingNextTest {
    private val compose = createComposeRule()

    @get:Rule val rules: TestRule = ScreenShots.hosted(compose)

    private fun text(res: Int) = RuntimeEnvironment.getApplication().getString(res)

    private fun next() = compose.onNodeWithText(text(R.string.common_next))

    private fun settle() = compose.mainClock.advanceTimeBy(SETTLE_MS)

    @Test
    fun nextWaitsForAWholeDateOfBirthAndForASex() {
        compose.setContent { PwnFitTheme(darkTheme = false) { OnboardingScreen(onComplete = {}) } }
        // The greeting is left by a tap anywhere, the page after it by its own Next.
        compose.onRoot().performClick()
        settle()
        next().performClick()
        settle()

        // The age: three blank wheels. Two of them turned is still no date.
        next().assertIsNotEnabled()
        val wheels = compose.onAllNodes(hasScrollToIndexAction())
        // In the order the language writes a date in. Past the blank row a day's and a month's row
        // number is its own value.
        val rows = mapOf('d' to 15, 'M' to 6, 'y' to birthYears(LocalDate.now()).indexOf(2001))
        val order = birthWheelOrder(Locale.getDefault())
        wheels[0].performScrollToIndex(rows.getValue(order[0]))
        wheels[1].performScrollToIndex(rows.getValue(order[1]))
        settle()
        next().assertIsNotEnabled()
        wheels[2].performScrollToIndex(rows.getValue(order[2]))
        settle()
        next().assertIsEnabled().performClick()
        settle()

        // The sex: two empty cards, and Next waits for one of them.
        next().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.sex_female)).performClick()
        settle()
        next().assertIsEnabled()
    }

    @Test
    fun backFromTheFirstQuestionEndsAtTheGreeting() {
        compose.setContent { PwnFitTheme(darkTheme = false) { OnboardingScreen(onComplete = {}) } }
        compose.onRoot().performClick()
        settle()
        next().assertExists()
        compose.onNodeWithContentDescription(text(R.string.common_back)).performClick()
        settle()
        // The greeting has no Next: it is left by a tap.
        next().assertDoesNotExist()
        compose.onRoot().performClick()
        settle()
        next().assertExists()
    }

    private companion object {
        /** Long enough for a page's entrance to finish. */
        const val SETTLE_MS = 1_500L
    }
}
