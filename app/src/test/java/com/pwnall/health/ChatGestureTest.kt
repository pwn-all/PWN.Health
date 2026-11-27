package com.pwnall.health

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The chat's gestures composed and touched, on Robolectric in every `check`: a touch holds what the
 * words of a call in the source cannot, which a comment with the same words would satisfy and a line
 * break would fail. What still needs the whole screen or a window is pinned in [ChatGestureWiringTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ChatGestureTest {
    private val compose = createComposeRule()

    @get:Rule val rules: TestRule = ScreenShots.hosted(compose)

    private fun text(res: Int) = RuntimeEnvironment.getApplication().getString(res)

    /** Every answer a test leaves alone, and [onHeld], the clock's. */
    private fun actions(onHeld: (Boolean) -> Unit) = ReplyActions(
        onTopic = {},
        onNarrow = {},
        onResume = {},
        onEnd = {},
        onExit = {},
        onTap = { _, _ -> },
        onTick = { _, _ -> },
        onKeep = { _, _ -> },
        onSeverity = {},
        onValue = { _, _ -> },
        onPaint = { _, _, _, _ -> },
        onSend = {},
        onText = { _, _ -> },
        onHeld = onHeld,
        onOpen = {},
    )

    /**
     * The composer sends the words as the log keeps them, trimmed: a reading's spans count from the
     * first character the model read, and a record whose words are not where it found them is no card
     * ([ChatJournalEvent.groundedIn]) — untrimmed, a message pasted with a space before it would lose
     * every card.
     */
    @Test
    fun theComposerSendsTheWordsAsTheLogKeepsThem() {
        var sent: String? = null
        compose.setContent {
            PwnFitTheme(darkTheme = false) {
                ChatComposer(
                    enabled = true,
                    ask = null,
                    focus = false,
                    onFocus = {},
                    onAttach = {},
                    onLabs = null,
                ) { sent = it }
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("  my knee hurts \n")
        compose.onNodeWithContentDescription(text(R.string.symptom_chat_send)).performClick()
        assertEquals("my knee hurts", sent)
    }

    /** The field holds at most its question's characters, and never half of one: an emoji pasted across the limit. */
    @Test
    fun theFieldNeverCutsACharacterInTwo() {
        val face = "😀"
        assertEquals("ab", typedUpTo("ab$face", 3))
        assertEquals("ab$face", typedUpTo("ab$face", 4))
        assertEquals("ab$face", typedUpTo("ab${face}c", 4))
        assertEquals("abc", typedUpTo("abcd", 3))
        assertEquals("ab", typedUpTo("ab", 3))
    }

    /**
     * A map that leaves under a finger — folded away, hidden by typing — lets go of the clock, as the
     * dial does: the lift the clock waits for goes with the map, or the chat would wait for a finger
     * that is no longer there.
     */
    @Test
    fun aMapLeavingUnderAFingerLetsGoOfTheClock() {
        var shown by mutableStateOf(true)
        var held: Boolean? = null
        val reply = ChatReply.BodyMap(SymptomStep.PAIN_AREA, frame = null, head = false, headChips = emptyList())
        compose.setContent {
            PwnFitTheme(darkTheme = false) {
                val map = rememberChatMapState(0) {
                    MapStart(emptyList(), emptyList(), emptyList(), PainView.FRONT, null)
                }
                if (shown) ChatPainMap(map, reply, "FEMALE", 200.dp, actions(onHeld = { held = it }))
            }
        }
        val addArea = text(R.string.pain_add_area)
        val painter = SemanticsMatcher("the painter, whose one action opens the list") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == addArea
        }
        compose.onNode(painter).performTouchInput { down(center) }
        // Idled, not waited on: waitUntil leaves the effect that hands the finger to the clock unrun.
        compose.waitForIdle()
        assertEquals(true, held)

        shown = false
        compose.waitForIdle()
        assertEquals(false, held)
    }
}
