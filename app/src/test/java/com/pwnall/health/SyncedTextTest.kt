package com.pwnall.health

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fields the app types into run on Compose's state-based text engine while every caller still
 * holds its text as a String ([rememberSyncedText]). What the deprecated value-based field did by
 * itself has to hold here too: a keystroke reaches the caller, a keystroke the caller refuses or
 * rewrites is not what stays on screen, a figure set from outside replaces the text, and nothing
 * typed in a burst is lost. Composed on Robolectric, so it runs in every `check`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class SyncedTextTest {
    private val compose = createComposeRule()

    @get:Rule val rules: TestRule = ScreenShots.hosted(compose)

    private val field get() = compose.onNodeWithTag(FIELD)

    /** The adapter the last composed [Field] holds, for what a test cannot reach through semantics. */
    private lateinit var synced: SyncedText

    @Composable
    private fun Field(
        value: String,
        onValueChange: (String) -> Unit,
        key: Any? = null,
        output: OutputTransformation? = null,
    ) {
        val text = rememberSyncedText(value, onValueChange, key).also { synced = it }
        BasicTextField(
            state = text.state,
            modifier = Modifier.testTag(FIELD),
            inputTransformation = text.handOff,
            outputTransformation = output,
        )
    }

    /** What the field's state holds, before anything is drawn over it. */
    private fun SemanticsNodeInteraction.holds(text: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(text)))

    /** What the field draws. */
    private fun SemanticsNodeInteraction.shows(text: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))

    @Test
    fun typingReachesTheCallerAsItHappens() {
        var value by mutableStateOf("")
        val heard = mutableListOf<String>()
        compose.setContent {
            Field(value, {
                heard += it
                value = it
            })
        }
        field.performTextInput("4")
        field.performTextInput("2")
        assertEquals(listOf("4", "42"), heard)
        field.holds("42").shows("42")
    }

    @Test
    fun aBurstOfKeystrokesBeforeTheFieldRecomposesIsAllKept() {
        var value by mutableStateOf("")
        val heard = mutableListOf<String>()
        compose.setContent {
            Field(value, {
                heard += it
                value = it
            })
        }
        val insert = checkNotNull(field.fetchSemanticsNode().config[SemanticsActions.InsertTextAtCursor].action)
        // One task on the main thread, so the second keystroke lands before any recomposition.
        compose.runOnUiThread {
            insert(AnnotatedString("1"))
            insert(AnnotatedString("2"))
            insert(AnnotatedString("3"))
        }
        compose.waitForIdle()
        assertEquals(listOf("1", "12", "123"), heard)
        assertEquals("123", value)
        field.holds("123")
    }

    @Test
    fun aKeystrokeTheCallerRefusesDoesNotStay() {
        var value by mutableStateOf("")
        compose.setContent { Field(value, { if (it.all(Char::isDigit)) value = it }) }
        field.performTextInput("1")
        field.performTextInput("x")
        assertEquals("1", value)
        field.holds("1").shows("1")
        // And the field goes on from there rather than from the refused text.
        field.performTextInput("2")
        assertEquals("12", value)
        field.holds("12")
    }

    @Test
    fun aKeystrokeTheCallerRewritesShowsAsRewritten() {
        var value by mutableStateOf("")
        compose.setContent { Field(value, { value = it.decimalInput() }) }
        field.performTextInput("1")
        field.performTextInput(",")
        field.performTextInput("5")
        assertEquals("1.5", value)
        field.holds("1.5").shows("1.5")
    }

    @Test
    fun aFigureSetFromOutsideReplacesTheText() {
        var value by mutableStateOf("")
        val heard = mutableListOf<String>()
        compose.setContent {
            Field(value, {
                heard += it
                value = it
            })
        }
        field.performTextInput("12345")
        // The cursor keeps its offset, as the value-based field kept it, rather than going to the end.
        compose.runOnIdle { value = "123456789" }
        field.holds("123456789").assert(selection(TextRange(5)))
        // And is cut down to the new length when the text gets shorter.
        compose.runOnIdle { value = "7" }
        field.holds("7").shows("7").assert(selection(TextRange(1)))
        // A write from outside is not handed back to the caller as if it had been typed.
        assertEquals(listOf("12345"), heard)
    }

    @Test
    fun aFieldThatComesToStandForAnotherItemShowsThatItemsText() {
        val texts = mutableStateMapOf("a" to "100", "b" to "250")
        var item by mutableStateOf("a")
        compose.setContent { Field(texts.getValue(item), { texts[item] = it }, key = item) }
        // Typed into a first, so a's cursor stands after the first figure.
        field.performTextInput("9")
        field.holds("9100")
        compose.runOnIdle { item = "b" }
        field.holds("250")
        // Typed into b, and at the start: a fresh field's cursor stands before the first figure, where
        // the value-based field put it too. Had a's field stayed, its cursor would have made "2150".
        field.performTextInput("1")
        assertEquals(mapOf("a" to "9100", "b" to "1250"), texts.toMap())
    }

    @Test
    fun anUndoOrRedoReachesTheCaller() {
        var value by mutableStateOf("")
        compose.setContent { Field(value, { value = it }) }
        field.performTextInput("1")
        field.performTextInput("2")
        assertEquals("12", value)
        // Undo and redo write past the input transformation, so only the field's catching up hands
        // them on; the two keystrokes are one undo step, as the engine merges adjacent typing.
        compose.runOnUiThread { synced.state.undoState.undo() }
        compose.waitForIdle()
        assertEquals("", value)
        field.holds("")
        compose.runOnUiThread { synced.state.undoState.redo() }
        compose.waitForIdle()
        assertEquals("12", value)
        field.holds("12")
    }

    @Test
    @Config(qualifiers = "+ru")
    fun aDecimalFieldDrawsTheLocalesSeparatorOverTheStoredDot() {
        var value by mutableStateOf("")
        compose.setContent { Field(value, { value = it.decimalInput() }, output = rememberDecimalSeparator()) }
        field.performTextInput("7,5")
        assertEquals("7.5", value)
        field.holds("7.5").shows("7,5")
    }

    @Test
    @Config(qualifiers = "+ru")
    fun aNumberFieldTakesTypingEndToEnd() {
        var value by mutableStateOf("")
        compose.setContent {
            PwnFitTheme(darkTheme = false) {
                NumberField(value, { value = it.decimalInput() }, "Load", "kg", decimal = true)
            }
        }
        val number = compose.onNode(hasSetTextAction())
        number.performTextInput("7,5")
        assertEquals("7.5", value)
        number.holds("7.5").shows("7,5")
        number.performTextInput("x")
        assertEquals("7.5", value)
        number.holds("7.5")
        compose.runOnIdle { value = "12" }
        number.holds("12").shows("12")
    }

    private fun selection(range: TextRange) =
        SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, range)

    private companion object {
        const val FIELD = "field"
    }
}
