package com.pwnall.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A reply folding away after it was sent — the pain map, the mood's dial — takes no touch and says
 * nothing while it goes: a tap on the folding map would paint into the answer already sent.
 *
 * This composes [FoldingReply] and puts a finger on it rather than looking for `clearAndSetSemantics`
 * and the shape of the cover in its source, which a comment with the same words would satisfy and a
 * line break would fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class FoldingReplyTest {
    private val compose = createComposeRule()

    @get:Rule val rules: TestRule = ScreenShots.hosted(compose)

    @Test
    fun aReplyFoldingAwayTakesNoTouchAndSaysNothingUntilItHasGone() {
        var visible by mutableStateOf(true)
        var taps = 0
        var gone = false
        compose.setContent {
            PwnFitTheme(darkTheme = false) {
                FoldingReply(visible, animated = true, onGone = { gone = true }) {
                    Box(Modifier.size(200.dp).clickable { taps++ }) { Text("Send") }
                }
            }
        }
        compose.onNodeWithText("Send").performClick()
        assertEquals(1, taps)

        // Held a frame into the fold: still on screen, and no longer anybody's to touch or to hear.
        compose.mainClock.autoAdvance = false
        visible = false
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, taps)
        assertFalse(gone)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(gone)
    }
}
