package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind the three-second clock that stands in for a "Next" button: a tap is its own
 * answer, a settled answer is taken only once the person has given one, and no question is passed
 * over untouched. One rule for every page that asks one question at a time.
 */
class AutoTakeTest {
    @Test
    fun theClockIsThreeSeconds() {
        assertEquals(3_000L, AUTO_TAKE_MS)
    }

    @Test
    fun aTapAnswerNeverRunsTheClock() {
        listOf(true, false).forEach { flag ->
            assertFalse(autoTakeArmed(AnswerKind.TAP, holds = flag, touched = flag, answered = flag))
        }
    }

    /** Nobody's answer is made up: nothing held, or held but never given, runs no clock. */
    @Test
    fun aSettledAnswerRunsOnlyOnceThePersonGaveIt() {
        assertFalse(autoTakeArmed(AnswerKind.SETTLE, holds = false, touched = true, answered = true))
        assertFalse(autoTakeArmed(AnswerKind.SETTLE, holds = true, touched = false, answered = false))
        assertTrue(autoTakeArmed(AnswerKind.SETTLE, holds = true, touched = true, answered = false))
        assertTrue(autoTakeArmed(AnswerKind.SETTLE, holds = true, touched = false, answered = true))
    }

    /** Left alone, nothing is taken: no kind of question runs its clock untouched and unanswered. */
    @Test
    fun noQuestionIsPassedOverByBeingLeftAlone() {
        AnswerKind.entries.forEach { kind ->
            listOf(true, false).forEach { holds ->
                assertFalse(autoTakeArmed(kind, holds = holds, touched = false, answered = false))
            }
        }
    }

    /**
     * A "?" opened over an armed question, or any other dialog, list or sheet, takes the window's
     * focus and stops the clock; so does a finger resting on a ruler. Nothing is taken behind them.
     */
    @Test
    fun theClockStandsStillUnderADialogOrAFinger() {
        assertTrue(autoTakeRuns(armed = true, held = false, focused = true))
        assertFalse(autoTakeRuns(armed = true, held = false, focused = false))
        assertFalse(autoTakeRuns(armed = true, held = true, focused = true))
        assertFalse(autoTakeRuns(armed = false, held = false, focused = true))
    }

    /**
     * Three seconds by default; longer where the accessibility settings ask for more time; ten times
     * as long under a screen reader, whose focus moves a swipe at a time.
     */
    @Test
    fun theWindowStretchesForTheAccessibilitySettings() {
        assertEquals(AUTO_TAKE_MS, autoTakeWindow(AUTO_TAKE_MS, touchExploration = false))
        assertEquals(20_000L, autoTakeWindow(20_000L, touchExploration = false))
        assertEquals(AUTO_TAKE_MS * 10, autoTakeWindow(AUTO_TAKE_MS, touchExploration = true))
        assertEquals(60_000L, autoTakeWindow(60_000L, touchExploration = true))
    }

    /**
     * A frame adds its own time and no more than a tenth of a second: a stretch with no frames — the
     * screen off, the app away — is no time at all, so coming back takes nothing on the first frame.
     */
    @Test
    fun aStretchWithNoFramesIsNoTime() {
        assertEquals(16_000_000L, autoTakeFrameStep(16_000_000L))
        assertEquals(FRAME_STEP_CAP_NANOS, autoTakeFrameStep(5L * 60 * 1_000_000_000))
        assertEquals(0L, autoTakeFrameStep(-1L))
    }

    /** The line fills over whatever window the clock runs, and never past its end. */
    @Test
    fun theLineFillsOverItsWindow() {
        assertEquals(0f, autoTakeFraction(0), 0f)
        assertEquals(0.5f, autoTakeFraction(AUTO_TAKE_MS / 2), 0.001f)
        assertEquals(0.5f, autoTakeFraction(15_000L, 30_000L), 0.001f)
        assertEquals(1f, autoTakeFraction(AUTO_TAKE_MS * 2), 0f)
    }

    /** The pain page's words and rulers settle, and the rest — first-noticed among them — are taps. */
    @Test
    fun painQuestionsAreSortedByHowTheyAreAnswered() {
        assertEquals(
            mapOf(
                PainStep.TYPES to AnswerKind.SETTLE,
                PainStep.ONSET to AnswerKind.TAP,
                PainStep.INTENSITY to AnswerKind.SETTLE,
                PainStep.NOTICED to AnswerKind.TAP,
                PainStep.STATUS to AnswerKind.TAP,
                PainStep.LASTED to AnswerKind.SETTLE,
                PainStep.PAINKILLER to AnswerKind.TAP,
            ),
            PainStep.entries.associateWith { it.answerKind },
        )
    }
}
