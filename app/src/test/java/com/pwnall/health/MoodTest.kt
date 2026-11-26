package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodTest {
    /** One entry per day, so a summary reads a week rather than a burst of taps. */
    private fun mood(vararg moods: Mood) = moods.mapIndexed { index, mood ->
        entry(mood, day = "2026-07-%02d".format(20 + index), at = index.toLong())
    }

    private fun entry(mood: Mood, day: String, at: Long, energy: Energy? = null) = JournalEntry(
        id = at,
        day = day,
        occurredAt = at,
        kind = KIND_MOOD,
        title = "Mood",
        calories = 0.0,
        moodScore = mood.score,
        energyScore = energy?.score,
    )

    @Test
    fun scaleKeepsItsStoredNumbers() {
        assertEquals(listOf(1, 2, 3, 4, 5), Mood.entries.map(Mood::score))
        assertEquals(Mood.LOW, Mood.fromScore(2))
        assertEquals(listOf(1, 2, 3, 4, 5), Energy.entries.map(Energy::score))
        assertEquals(Energy.RESTLESS, Energy.fromScore(2))
        assertNull(Mood.fromScore(0))
        assertNull(Mood.fromScore(null))
    }

    @Test
    fun noEntriesIsNotAJudgement() {
        val summary = moodSummary(emptyList())

        assertEquals(MoodState.NO_DATA, summary.state)
        assertEquals(0, summary.entries)
        assertFalse(summary.suggestBreathing)
    }

    @Test
    fun entriesWithoutAScoreAreIgnored() {
        val food = JournalEntry(day = "2026-07-30", occurredAt = 1, kind = "EAT", title = "", calories = 10.0)

        assertEquals(MoodState.NO_DATA, moodSummary(listOf(food)).state)
        assertEquals(1, moodSummary(listOf(food) + mood(Mood.GOOD)).entries)
    }

    /**
     * One tap is a moment, not a trend. Naming a week "persistently low" from a single bad entry is
     * both statistically empty and the kind of wrong a person takes to heart.
     */
    @Test
    fun oneEntryNeverNamesAState() {
        val summary = moodSummary(mood(Mood.BAD))

        assertEquals(MoodState.NO_DATA, summary.state)
        assertEquals(1, summary.entries)
        assertFalse(summary.suggestBreathing)
    }

    @Test
    fun threeEntriesOnOneDayAreStillOneDay() {
        val sameDay = listOf(
            entry(Mood.BAD, day = "2026-07-30", at = 1),
            entry(Mood.BAD, day = "2026-07-30", at = 2),
            entry(Mood.BAD, day = "2026-07-30", at = 3),
        )

        assertEquals(MoodState.NO_DATA, moodSummary(sameDay).state)
    }

    /** A bad Tuesday logged five times must not outvote the ordinary days around it. */
    @Test
    fun oneChattyDayDoesNotOutvoteTheWeek() {
        val entries = List(5) { entry(Mood.BAD, day = "2026-07-28", at = it.toLong()) } +
            listOf(
                entry(Mood.GOOD, day = "2026-07-29", at = 10),
                entry(Mood.GOOD, day = "2026-07-30", at = 11),
                entry(Mood.FUN, day = "2026-07-31", at = 12),
            )

        val summary = moodSummary(entries)

        // Day means are 1, 4, 4, 5 -> 3.5 of 5. Averaging the eight entries instead would give 2.4.
        assertEquals(3.5, summary.average, 0.001)
        assertEquals(MoodState.BALANCED, summary.state)
    }

    @Test
    fun outOfRangeScoresAreIgnored() {
        val broken = mood(Mood.GOOD).map { it.copy(moodScore = 9) }

        assertEquals(MoodState.NO_DATA, moodSummary(broken).state)
    }

    @Test
    fun allBadIsLowAndSuggestsBreathing() {
        val summary = moodSummary(mood(Mood.BAD, Mood.BAD, Mood.BAD))

        assertEquals(MoodState.LOW, summary.state)
        assertEquals(1.0, summary.average, 0.001)
        assertEquals(0.0, summary.wellbeingPercent, 0.001)
        assertTrue(summary.suggestBreathing)
    }

    @Test
    fun mostlyWoundUpIsTenseEvenWhenTheMeanLooksAcceptable() {
        // Pleasantness 2,2,5,5 averages 3.5 (62%), which alone would read as balanced; the second
        // dial is what says the week was a tense one rather than an ordinary one.
        val summary = moodSummary(
            listOf(
                entry(Mood.LOW, day = "2026-07-20", at = 0, energy = Energy.WOUND_UP),
                entry(Mood.LOW, day = "2026-07-21", at = 1, energy = Energy.RESTLESS),
                entry(Mood.FUN, day = "2026-07-22", at = 2, energy = Energy.CALM),
                entry(Mood.FUN, day = "2026-07-23", at = 3, energy = Energy.CALM),
            ),
        )

        assertEquals(MoodState.TENSE, summary.state)
        assertEquals(0.5, summary.tenseShare, 0.001)
        assertEquals(4, summary.tenseDays)
        assertTrue(summary.suggestBreathing)
    }

    /**
     * The four-hour cooldown still allows three entries in one bad day, and tallying taps would let
     * those three outvote the four calm days around them — the same distortion the day means
     * prevent.
     */
    @Test
    fun oneChattyWoundUpDayDoesNotMakeTheWeekTense() {
        val entries = List(3) {
            entry(Mood.LOW, day = "2026-07-27", at = it.toLong(), energy = Energy.WOUND_UP)
        } +
            listOf(
                entry(Mood.GOOD, day = "2026-07-28", at = 10, energy = Energy.CALM),
                entry(Mood.GOOD, day = "2026-07-29", at = 11, energy = Energy.CALM),
                entry(Mood.GOOD, day = "2026-07-30", at = 12, energy = Energy.CALM),
                entry(Mood.GOOD, day = "2026-07-31", at = 13, energy = Energy.CALM),
            )

        val summary = moodSummary(entries)

        // One wound-up day out of five, not three wound-up entries out of seven.
        assertEquals(0.2, summary.tenseShare, 0.001)
        assertEquals(MoodState.BALANCED, summary.state)
        assertFalse(summary.suggestBreathing)
    }

    /**
     * "Neutral" is the middle of the dial and lands exactly on 50. A week of nothing else is not called
     * below average, in the colour of a warning, nor offered breathing for entries that "have been
     * low": the band under the middle begins under it.
     */
    @Test
    fun aNeutralWeekIsTheMiddleOfTheScaleAndNotBelowIt() {
        val summary = moodSummary(mood(Mood.NEUTRAL, Mood.NEUTRAL, Mood.NEUTRAL))

        assertEquals(50.0, summary.wellbeingPercent, 0.001)
        assertEquals(MoodState.BALANCED, summary.state)
        assertFalse(summary.suggestBreathing)
        assertEquals(R.string.breathing_body, summary.breathingHintRes())

        val under = moodSummary(mood(Mood.NEUTRAL, Mood.NEUTRAL, Mood.LOW))
        assertEquals(41.667, under.wellbeingPercent, 0.001)
        assertEquals(MoodState.POOR, under.state)
        assertTrue(under.suggestBreathing)
        assertEquals(R.string.breathing_suggestion, under.breathingHintRes())
    }

    /**
     * A tense week is offered breathing in its own words, never the low weeks' sentence — "recent
     * entries have been low" — over a mood of 3.5 out of 5.
     */
    @Test
    fun aTenseWeekIsNotToldItsEntriesWereLow() {
        val tense = moodSummary(
            listOf(
                entry(Mood.LOW, day = "2026-07-20", at = 0, energy = Energy.WOUND_UP),
                entry(Mood.LOW, day = "2026-07-21", at = 1, energy = Energy.RESTLESS),
                entry(Mood.FUN, day = "2026-07-22", at = 2, energy = Energy.CALM),
                entry(Mood.FUN, day = "2026-07-23", at = 3, energy = Energy.CALM),
            ),
        )

        assertEquals(MoodState.TENSE, tense.state)
        assertEquals(R.string.breathing_suggestion_tense, tense.breathingHintRes())
        val low = moodSummary(mood(Mood.BAD, Mood.BAD, Mood.BAD))
        assertEquals(R.string.breathing_suggestion, low.breathingHintRes())
    }

    @Test
    fun justAboveFiftyIsBalancedAndSuggestsNothing() {
        val summary = moodSummary(mood(Mood.NEUTRAL, Mood.GOOD, Mood.NEUTRAL, Mood.GOOD))

        assertEquals(62.5, summary.wellbeingPercent, 0.001)
        assertEquals(MoodState.BALANCED, summary.state)
        assertFalse(summary.suggestBreathing)
    }

    @Test
    fun goodWeekIsElevated() {
        val summary = moodSummary(mood(Mood.GOOD, Mood.FUN, Mood.FUN))

        assertEquals(91.667, summary.wellbeingPercent, 0.001)
        assertEquals(MoodState.ELEVATED, summary.state)
        assertFalse(summary.suggestBreathing)
    }

    @Test
    fun theTwentyEightBandWinsOverTheTenseShare() {
        // Day means 1, 2, 1 -> 1.33 of 5 -> 8.3%: below the lower cut-off, so LOW rather than TENSE
        // however wound up the second dial says the week was.
        val summary = moodSummary(
            listOf(
                entry(Mood.BAD, day = "2026-07-20", at = 0, energy = Energy.WOUND_UP),
                entry(Mood.LOW, day = "2026-07-21", at = 1, energy = Energy.WOUND_UP),
                entry(Mood.BAD, day = "2026-07-22", at = 2, energy = Energy.WOUND_UP),
            ),
        )

        assertEquals(MoodState.LOW, summary.state)
    }

    @Test
    fun theScaleIsClosedForFourHoursAfterAnEntry() {
        val logged = 1_800_000_000_000L

        assertEquals(0L, moodCooldownLeftMs(null, logged))
        assertEquals(MOOD_COOLDOWN_MS, moodCooldownLeftMs(logged, logged))
        assertEquals(60_000L, moodCooldownLeftMs(logged, logged + MOOD_COOLDOWN_MS - 60_000))
        assertEquals(0L, moodCooldownLeftMs(logged, logged + MOOD_COOLDOWN_MS))
        assertEquals(0L, moodCooldownLeftMs(logged, logged + MOOD_COOLDOWN_MS + 1))
    }

    @Test
    fun aClockThatWentBackwardsDoesNotLockTheScaleForever() {
        val logged = 1_800_000_000_000L

        assertEquals(MOOD_COOLDOWN_MS, moodCooldownLeftMs(logged, logged - 5_000))
    }

    /**
     * An entry stamped a day ahead — the clock was a day out when it was written — would hold the
     * scale shut for that day and four hours more, with the screen saying nothing. Only a stamp inside
     * the window counts, on either side of it: the chat files a mood under the moment its question was
     * asked, which the widget's tap can come after.
     */
    @Test
    fun anEntryStampedMoreThanAWindowAheadDoesNotShutTheScale() {
        val now = 1_800_000_000_000L
        val day = 24 * 60 * 60 * 1_000L

        assertEquals(0L, moodCooldownLeftMs(now + day, now))
        assertEquals(0L, moodCooldownLeftMs(now + MOOD_COOLDOWN_MS, now))
        assertEquals(MOOD_COOLDOWN_MS, moodCooldownLeftMs(now + MOOD_COOLDOWN_MS - 1, now))
        assertEquals(MOOD_COOLDOWN_MS, moodCooldownLeftMs(now + 60_000L, now))
    }

    /**
     * The page prints the pause in minutes, rounded up, and wakes to redraw it only when that figure
     * turns over: two minutes and five seconds read "3" for five seconds more, and the last minute
     * runs out on the moment the scale opens rather than a second after it.
     */
    @Test
    fun theCountdownWakesWhenTheMinuteItPrintsTurnsOver() {
        val minute = 60_000L

        assertEquals(3L, moodPauseMinutes(2 * minute + 5_000L))
        assertEquals(5_000L, moodCountdownWakeMs(2 * minute + 5_000L))
        assertEquals(2L, moodPauseMinutes(2 * minute))
        assertEquals(minute, moodCountdownWakeMs(2 * minute))
        assertEquals(1L, moodPauseMinutes(1L))
        assertEquals(1L, moodCountdownWakeMs(1L))
        assertEquals(minute, moodCountdownWakeMs(minute))
        // The whole pause, as a stamp just ahead of the clock holds it: a minute until the first change.
        assertEquals(240L, moodPauseMinutes(MOOD_COOLDOWN_MS))
        assertEquals(minute, moodCountdownWakeMs(MOOD_COOLDOWN_MS))
        // Nothing counting down: a look within the minute, for a stamp the clock is coming up on.
        assertEquals(minute, moodCountdownWakeMs(0L))
        // Every wake lands where the printed figure has just dropped by one.
        listOf(1L, 59_999L, minute + 1, 3 * minute + 17_000L, MOOD_COOLDOWN_MS - 1).forEach { left ->
            val wake = moodCountdownWakeMs(left)
            assertEquals("$left", moodPauseMinutes(left) - 1, moodPauseMinutes(left - wake))
            assertEquals("$left", moodPauseMinutes(left), moodPauseMinutes(left - wake + 1))
        }
    }

    @Test
    fun everyStateHasALabel() {
        MoodState.entries.forEach { state ->
            assertTrue("$state has no label", state.titleRes() != 0)
        }
    }
}
