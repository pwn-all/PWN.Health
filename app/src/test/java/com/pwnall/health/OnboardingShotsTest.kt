package com.pwnall.health

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.pwnall.health.ScreenShots.Themed
import com.pwnall.health.ScreenShots.film
import com.pwnall.health.ScreenShots.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Onboarding, page by page, on a Pixel 9a's panel, walked the way a person walks it: each page's
 * own button is pressed to reach the next. Runs only with -Pscreens; see [ScreenShots].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h913dp-xxhdpi", application = android.app.Application::class)
class OnboardingShotsTest {
    private val compose = createComposeRule()

    @get:Rule val rules: TestRule = ScreenShots.hosted(compose)

    private fun text(res: Int) = RuntimeEnvironment.getApplication().getString(res)

    private fun walk(dark: Boolean, prefix: String, films: Boolean) {
        val dir = ScreenShots.shotsDir()
        compose.setContent { Themed(dark = dark) { OnboardingScreen(onComplete = {}) } }
        compose.shoot(dir, "$prefix-01-hello")
        compose.onRoot().performClick()
        val pages = listOf("ahead", "age", "sex", "height", "weight", "allergy", "steps", "access")
        pages.forEachIndexed { index, name ->
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            if (name == "age") {
                // The wheels open blank and Next waits for them. Day, month and year, in the order
                // the language writes a date in: past the blank row a day's and a month's row number
                // is its own value.
                compose.shoot(dir, "$prefix-%02d-$name-empty".format(java.util.Locale.ROOT, index + 2))
                compose.onNodeWithText(text(R.string.common_next)).assertIsNotEnabled()
                val wheels = compose.onAllNodes(hasScrollToIndexAction())
                val rows = mapOf('d' to 15, 'M' to 6, 'y' to birthYears(java.time.LocalDate.now()).indexOf(2001))
                birthWheelOrder(java.util.Locale.getDefault()).forEachIndexed { at, field ->
                    wheels[at].performScrollToIndex(rows.getValue(field))
                }
                compose.mainClock.advanceTimeBy(SETTLE_MS)
            }
            if (name == "sex") {
                compose.shoot(dir, "$prefix-%02d-$name-empty".format(java.util.Locale.ROOT, index + 2))
                compose.onNodeWithText(text(R.string.sex_female)).performClick()
                compose.mainClock.advanceTimeBy(SETTLE_MS)
            }
            compose.shoot(dir, "$prefix-%02d-$name".format(java.util.Locale.ROOT, index + 2))
            val next = listOf(R.string.common_next, R.string.common_skip).map(::text)
                .firstOrNull { label -> runCatching { compose.onNodeWithText(label).assertExists() }.isSuccess }
                ?: return@forEachIndexed
            compose.onNodeWithText(next).performClick()
            // The step from the sex page on: the page slides on, the line fills a segment and the
            // answer arrives as a tag.
            if (films && name == "sex") compose.film(dir, "$prefix-film-next", count = 14, stepMs = 40)
        }
        if (films) compose.film(dir, "$prefix-film-count", count = 16, stepMs = 70)
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.shoot(dir, "$prefix-10-ready")
        compose.onNodeWithText(text(R.string.common_start)).performClick()
        if (films) compose.film(dir, "$prefix-film-exit", count = 26, stepMs = 70)
    }

    @Test fun light() = walk(dark = false, prefix = "light", films = true)

    @Test fun dark() = walk(dark = true, prefix = "dark", films = true)

    /** Russian: the longest strings the app ships, so a line that clips shows here first. */
    @Test
    @Config(qualifiers = "+ru")
    fun russian() = walk(dark = false, prefix = "ru", films = false)

    /** Arabic: right to left, so the line, the tags, the ruler and the scale all have to mirror. */
    @Test
    @Config(qualifiers = "+ar")
    fun arabic() = walk(dark = false, prefix = "ar", films = false)

    private companion object {
        /** Long enough for a page's entrance to finish before it is shot. */
        const val SETTLE_MS = 1_500L
    }
}
