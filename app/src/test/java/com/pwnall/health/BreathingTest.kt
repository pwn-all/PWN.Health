package com.pwnall.health

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BreathingTest {
    @Test
    fun defaultTempoRunsTheFlowsFourTwoSixTwo() {
        assertEquals(4, breathPhaseSeconds(BreathPhase.INHALE, 4))
        assertEquals(2, breathPhaseSeconds(BreathPhase.HOLD_IN, 4))
        assertEquals(6, breathPhaseSeconds(BreathPhase.EXHALE, 4))
        assertEquals(2, breathPhaseSeconds(BreathPhase.HOLD_OUT, 4))
        assertEquals(14, breathCycleSeconds(4))
    }

    /** A stored length or count the settings do not offer opens on the default, never on a session of nothing. */
    @Test
    fun aStoredSettingTheWheelDoesNotOfferOpensOnTheDefault() {
        fun length(stored: Int) = breathSetting(stored, BREATH_DURATION_CHOICES, BREATH_DURATION_DEFAULT)

        assertEquals(10, length(10))
        assertEquals(BREATH_DURATION_DEFAULT, length(0))
        assertEquals(BREATH_DURATION_DEFAULT, length(-3))
        assertEquals(BREATH_DURATION_DEFAULT, length(20))
        assertEquals(8, breathSetting(8, BREATH_TEMPO_CHOICES, BREATH_TEMPO_DEFAULT))
        assertEquals(BREATH_TEMPO_DEFAULT, breathSetting(2, BREATH_TEMPO_CHOICES, BREATH_TEMPO_DEFAULT))
        // The defaults are offered themselves, or the fallback would be a value the wheel cannot show.
        assertTrue(BREATH_DURATION_DEFAULT in BREATH_DURATION_CHOICES)
        assertTrue(BREATH_TEMPO_DEFAULT in BREATH_TEMPO_CHOICES)
    }

    @Test
    fun eachPhaseCountsDownToItsBoundary() {
        assertEquals(BreathTick(BreathPhase.INHALE, 4, 0), breathTickAt(0))
        assertEquals(BreathTick(BreathPhase.INHALE, 1, 0), breathTickAt(3))
        assertEquals(BreathTick(BreathPhase.HOLD_IN, 2, 0), breathTickAt(4))
        assertEquals(BreathTick(BreathPhase.HOLD_IN, 1, 0), breathTickAt(5))
        assertEquals(BreathTick(BreathPhase.EXHALE, 6, 0), breathTickAt(6))
        assertEquals(BreathTick(BreathPhase.EXHALE, 1, 0), breathTickAt(11))
        assertEquals(BreathTick(BreathPhase.HOLD_OUT, 2, 0), breathTickAt(12))
        assertEquals(BreathTick(BreathPhase.HOLD_OUT, 1, 0), breathTickAt(13))
    }

    @Test
    fun aCycleCompletesOnceEveryCycleLength() {
        assertEquals(BreathTick(BreathPhase.INHALE, 4, 1), breathTickAt(14))
        assertEquals(BreathTick(BreathPhase.HOLD_OUT, 1, 1), breathTickAt(27))
        assertEquals(BreathTick(BreathPhase.INHALE, 4, 2), breathTickAt(28))
        assertEquals(6, breathTickAt(14 * 6).completedCycles)
    }

    @Test
    fun tempoStretchesTheWholePattern() {
        assertEquals(BreathTick(BreathPhase.INHALE, 6, 0), breathTickAt(0, 6))
        assertEquals(BreathTick(BreathPhase.HOLD_IN, 3, 0), breathTickAt(6, 6))
        assertEquals(BreathTick(BreathPhase.EXHALE, 9, 0), breathTickAt(9, 6))
        assertEquals(21, breathCycleSeconds(6))
        assertEquals(2, breathTickAt(42, 6).completedCycles)
    }

    @Test
    fun negativeElapsedTimeStartsTheSessionOver() {
        assertEquals(BreathTick(BreathPhase.INHALE, 4, 0), breathTickAt(-5))
    }

    @Test
    fun degenerateTempoDoesNotDivideByZero() {
        assertEquals(BreathTick(BreathPhase.HOLD_IN, 1, 0), breathTickAt(1, 0))
    }

    /**
     * The session is read off the clock, so the three preparation seconds are not practice and a
     * screen that was dark for an hour cannot bank an hour of breathing into the journal.
     */
    @Test
    fun theClockPreparesFirstAndThenStopsAtTheSessionLength() {
        val total = 5 * 60

        assertEquals(BreathProgress(3, 0), breathProgressAt(0, total))
        assertEquals(BreathProgress(3, 0), breathProgressAt(999, total))
        assertEquals(BreathProgress(1, 0), breathProgressAt(2_000, total))
        assertEquals(BreathProgress(0, 0), breathProgressAt(3_000, total))
        assertEquals(BreathProgress(0, 1), breathProgressAt(4_000, total))
        assertEquals(BreathProgress(0, total), breathProgressAt((total + 3) * 1_000L, total))
        assertEquals(BreathProgress(0, total), breathProgressAt(60 * 60 * 1_000L, total))
    }

    @Test
    fun aClockThatWentBackwardsPreparesRatherThanCountingBackwards() {
        assertEquals(BreathProgress(3, 0), breathProgressAt(-5_000, 60))
    }

    /**
     * The button under the bud can end a practice five seconds in, which is no session to save or to
     * announce as "Practice complete". Less than one whole cycle is not recorded; a session ended
     * part of the way through is recorded and is not called complete.
     */
    @Test
    fun onlyASessionThatRanItsLengthIsComplete() {
        val total = 5 * 60

        assertEquals(BreathOutcome.DISCARDED, breathOutcome(cycles = 0, seconds = 0, totalSeconds = total))
        assertEquals(BreathOutcome.DISCARDED, breathOutcome(cycles = 0, seconds = 5, totalSeconds = total))
        assertEquals(BreathOutcome.ENDED_EARLY, breathOutcome(cycles = 1, seconds = 14, totalSeconds = total))
        assertEquals(BreathOutcome.ENDED_EARLY, breathOutcome(cycles = 21, seconds = total - 1, totalSeconds = total))
        assertEquals(BreathOutcome.COMPLETE, breathOutcome(cycles = 21, seconds = total, totalSeconds = total))
    }

    /**
     * Leaving ends the practice at what was breathed: the activity stopping, or the runner leaving the
     * composition (null) when the person navigates away inside the app. A rotation is not leaving, by
     * either signal, and no other step of the lifecycle settles a session that is still running.
     */
    @Test
    fun aSessionSettlesWhenTheScreenIsLeftAndNotWhenItIsRotated() {
        assertTrue(breathSettlesOn(Lifecycle.Event.ON_STOP, changingConfigurations = false))
        assertTrue(breathSettlesOn(null, changingConfigurations = false))

        assertFalse(breathSettlesOn(Lifecycle.Event.ON_STOP, changingConfigurations = true))
        assertFalse(breathSettlesOn(null, changingConfigurations = true))
        Lifecycle.Event.entries.filter { it != Lifecycle.Event.ON_STOP }.forEach { event ->
            assertFalse("$event", breathSettlesOn(event, changingConfigurations = false))
        }
    }

    /** The shortest session at the slowest pace still holds whole cycles, so its full run is recorded. */
    @Test
    fun everySessionOnOfferHoldsAWholeCycle() {
        val shortest = BREATH_DURATION_CHOICES.min() * 60
        val cycles = breathTickAt(shortest, BREATH_TEMPO_CHOICES.max()).completedCycles

        assertEquals(BreathOutcome.COMPLETE, breathOutcome(cycles, shortest, shortest))
    }

    /**
     * The tones play on the notification stream, which a phone on vibrate or in Do Not Disturb plays
     * at nothing. With the cues switched on, the motor then marks a phase and the end.
     */
    @Test
    fun aCueThatWouldNotBeHeardIsATapOfTheMotor() {
        assertEquals(BreathCue.TONE, breathCue(soundOn = true, toneAudible = true))
        assertEquals(BreathCue.HAPTIC, breathCue(soundOn = true, toneAudible = false))
        assertEquals(BreathCue.NONE, breathCue(soundOn = false, toneAudible = true))
        assertEquals(BreathCue.NONE, breathCue(soundOn = false, toneAudible = false))
    }

    /**
     * A workout template's player marks its steps for somebody whose eyes are on a bar, and tones
     * alone would leave a set ending and a rest running out unmarked on a phone on vibrate. Every cue
     * it gives goes through the one that falls back to the motor, and none past it.
     */
    @Test
    fun aTemplatesPlayerGivesItsCuesTheSameWay() {
        val player = File("src/main/java/com/pwnall/health/RoutinePlayer.kt").readText()

        assertTrue("rememberBreathCues(sound)" in player)
        assertFalse("a tone sounded past the cue", "startTone(" in player)
    }

    @Test
    fun statsCountOnlyBreathingEntries() {
        val entries = listOf(
            session(cycles = 6, minutes = 1.9),
            session(cycles = 3, minutes = 0.95),
            JournalEntry(
                day = "2026-07-30",
                occurredAt = 3,
                kind = "ACTIVITY",
                title = "Run",
                calories = 300.0,
                durationMinutes = 30.0,
            ),
        )

        val stats = breathingStats(entries)

        assertEquals(2, stats.sessions)
        assertEquals(9, stats.cycles)
        assertEquals(2.85, stats.minutes, 0.001)
    }

    @Test
    fun noSessionsCountNothing() {
        val stats = breathingStats(emptyList())

        assertEquals(0, stats.sessions)
        assertEquals(0, stats.cycles)
        assertEquals(0.0, stats.minutes, 0.001)
    }

    private var next = 1L

    private fun session(cycles: Int, minutes: Double) = JournalEntry(
        day = "2026-07-30",
        occurredAt = next++,
        kind = KIND_BREATHING,
        title = "Breathing",
        calories = 0.0,
        durationMinutes = minutes,
        breathCycles = cycles,
    )
}
