package com.pwnall.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a screen reader is handed by the controls this app draws itself, read off the composed
 * semantics tree.
 *
 * A ruler, the sleep dial, a wheel, the map of the body and a chart's row of bars are each one
 * gesture to the eye and nothing to TalkBack until somebody writes their semantics by hand: a value
 * to speak, and actions that set it without a drag. The arithmetic behind those actions has its
 * own tests ([stepRuler], RulerMotion). What they leave out is that the control hands the action its
 * callback at all: a `semantics` block reordered under a `clearAndSetSemantics`, or `customActions`
 * dropped in a refactor, compiles, draws the same pixels and passes every test of a pure function,
 * and the control is then mute. Composed on Robolectric, so it runs in every `check` and needs no
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ControlSemanticsTest {
    private val compose = createComposeRule()

    @get:Rule val rules: TestRule = ScreenShots.hosted(compose)

    private fun text(res: Int) = RuntimeEnvironment.getApplication().getString(res)

    /** The custom action a screen reader lists as [label], run as it would run it. */
    private fun SemanticsNodeInteraction.act(label: String) {
        val actions = fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val action = actions.single { it.label == label }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.customActionLabels(): List<String> =
        fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    @Test
    fun aRulerSpeaksItsValueAndStepsWithoutADrag() {
        var value by mutableIntStateOf(70)
        compose.setContent {
            PwnFitTheme(darkTheme = false) {
                RulerPicker((40..120).toList(), value, { value = it }, label = { "$it kg" }, name = "Weight")
            }
        }
        val ruler = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        ruler.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "70 kg"))
        ruler.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Weight")))
        assertEquals(
            listOf(R.string.ruler_increase, R.string.ruler_decrease).map(::text),
            ruler.customActionLabels(),
        )

        ruler.act(text(R.string.ruler_increase))
        assertEquals(71, value)
        ruler.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "71 kg"))
        ruler.act(text(R.string.ruler_decrease))
        ruler.act(text(R.string.ruler_decrease))
        assertEquals(69, value)
        // A value asked for outright, as a slider's is: the position among the values, not the value.
        ruler.performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        assertEquals(45, value)
    }

    @Test
    fun theSleepDialMovesEitherClockByAQuarterHourAndRoundMidnight() {
        var start by mutableIntStateOf(23 * 60 + 50)
        var end by mutableIntStateOf(7 * 60)
        compose.setContent {
            PwnFitTheme(darkTheme = false) { SleepDial(start, end, onStart = { start = it }, onEnd = { end = it }) }
        }
        val summary = { text(R.string.sleep_clock_summary).format(clockText(start), clockText(end)) }
        val dial = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        dial.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(summary())))
        assertEquals(
            listOf(
                R.string.sleep_dial_start_later,
                R.string.sleep_dial_start_earlier,
                R.string.sleep_dial_end_later,
                R.string.sleep_dial_end_earlier,
            ).map(::text),
            dial.customActionLabels(),
        )

        dial.act(text(R.string.sleep_dial_start_later))
        assertEquals(5, start)
        dial.act(text(R.string.sleep_dial_start_earlier))
        assertEquals(23 * 60 + 50, start)
        dial.act(text(R.string.sleep_dial_end_later))
        dial.act(text(R.string.sleep_dial_end_later))
        assertEquals(7 * 60 + 30, end)
        dial.act(text(R.string.sleep_dial_end_earlier))
        assertEquals(7 * 60 + 15, end)
        // What is spoken follows what was set.
        dial.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(summary())))
    }

    @Test
    fun aWheelHasOneChosenRowAndATapChoosesAnother() {
        var chosen by mutableIntStateOf(4)
        compose.setContent {
            PwnFitTheme(darkTheme = false) {
                Wheel((1..9).toList(), chosen, formatter = { "$it cups" }, onSelected = { chosen = it }, tick = {})
            }
        }
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNode(isSelected()).assertTextEquals("4 cups")

        compose.onNodeWithText("5 cups").performClick()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals(5, chosen)
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNode(isSelected()).assertTextEquals("5 cups")
    }

    /**
     * The row a wheel reports is the one under the band in its middle, from the first frame on, and
     * a wheel rebuilt under a new key — the birthday's day wheel for every month passed — reports
     * the row it was rebuilt on rather than the one its predecessor stood at.
     */
    @Test
    fun aWheelReportsTheRowUnderItsBandAndARebuiltOneItsNewRow() {
        var shown by mutableIntStateOf(15)
        val reported = mutableListOf<Int>()
        compose.setContent {
            PwnFitTheme(darkTheme = false) {
                key(shown) { Wheel((1..31).toList(), shown, { "$it" }, { reported += it }, tick = {}) }
            }
        }
        compose.waitForIdle()
        assertEquals(15, reported.first())

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.waitForIdle()
        assertEquals(21, reported.last())

        shown = 30
        compose.waitForIdle()
        assertEquals(30, reported.last())
    }

    /** The map of the body is a drawing surface; the way to it without drawing is its one action. */
    @Test
    fun thePainMapSaysWhatIsMarkedAndOffersTheListOfAreas() {
        var listed = 0
        compose.setContent {
            PwnFitTheme(darkTheme = false) {
                PainCanvas(
                    drawing = remember { PainDrawing(emptyList(), PainView.FRONT) },
                    sex = "FEMALE",
                    lit = emptyMap(),
                    description = "Nothing marked",
                    onStroke = {},
                    onCleared = {},
                    onListAreas = { listed++ },
                    modifier = Modifier.size(300.dp, 400.dp),
                )
            }
        }
        val map = compose.onNodeWithContentDescription("Nothing marked")
        val offered = map.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)
        assertEquals(text(R.string.pain_add_area), offered?.label)
        map.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, listed)
    }

    /**
     * A week's bars are one target, and a tap picks the bar under the finger: the first on the left
     * where the row runs left to right, the last where it runs the other way.
     */
    @Test
    fun aTapOnARowOfBarsPicksTheBarUnderItWhicheverWayTheRowRuns() {
        var picked by mutableStateOf<Int?>(null)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                PwnFitTheme(darkTheme = false) {
                    val row = Modifier.size(200.dp, 80.dp).testTag("bars")
                    PickableBarRow(4, picked, { picked = it }, gap = 0.dp, modifier = row) {
                        repeat(4) { Box(Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
        val leftmost: () -> Unit = {
            compose.onNodeWithTag("bars").performTouchInput { click(Offset(width * 0.1f, height / 2f)) }
            compose.waitForIdle()
        }
        leftmost()
        assertEquals(0, picked)
        // A second tap on the picked bar lets go of it.
        leftmost()
        assertNull(picked)

        direction = LayoutDirection.Rtl
        compose.waitForIdle()
        leftmost()
        assertEquals(3, picked)
    }

    private companion object {
        /** Long enough for a wheel to come to rest on the row it was sent to. */
        const val SETTLE_MS = 1_500L
    }
}
