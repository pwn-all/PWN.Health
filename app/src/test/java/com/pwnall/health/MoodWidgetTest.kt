package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The mood widget's waiting face. The pause between two moods is four hours on the clock, and midnight
 * is not its end: looking for the last mood among today's rows alone, the widget would lose a mood
 * logged at half past ten at midnight and bring the faces back, and the database would refuse every
 * tap on them until half past two without the widget saying a word.
 */
class MoodWidgetTest {
    private val logged = 1_785_400_000_000L
    private val minute = 60_000L

    /** Logged at 22:30 and looked at 00:15: two hours and a quarter to go, whatever the date says. */
    @Test
    fun thePauseRunsOnAcrossMidnight() {
        assertEquals(135L, moodWidgetWaitMinutes(logged, logged + 105 * minute))
    }

    /** The faces are shown — null — with no mood on file, and from the moment the four hours are up. */
    @Test
    fun theFacesComeBackWhenThePauseIsOver() {
        assertNull(moodWidgetWaitMinutes(null, logged))
        assertEquals(240L, moodWidgetWaitMinutes(logged, logged))
        assertNull(moodWidgetWaitMinutes(logged, logged + MOOD_COOLDOWN_MS))
        assertNull(moodWidgetWaitMinutes(logged, logged + MOOD_COOLDOWN_MS + minute))
    }

    /** Rounded up, as the mood page counts: the last minute of the pause is a minute, not "0h 00m". */
    @Test
    fun theMinutesAreRoundedUpAsTheMoodPageRoundsThem() {
        assertEquals(1L, moodWidgetWaitMinutes(logged, logged + MOOD_COOLDOWN_MS - 1))
        assertEquals(1L, moodWidgetWaitMinutes(logged, logged + MOOD_COOLDOWN_MS - minute))
        assertEquals(2L, moodWidgetWaitMinutes(logged, logged + MOOD_COOLDOWN_MS - minute - 1))
    }

    /** The widget asks the question logMood answers by, over the last four hours and not over today. */
    @Test
    fun theWidgetLooksBackFourHoursAndNotToMidnight() {
        val widgets = File("src/main/java/com/pwnall/health/Widgets.kt").readText()
        val widget = widgets.substringAfter("class MoodWidget : GlanceAppWidget() {")
            .substringBefore("class MoodWidgetReceiver")

        assertTrue("val last = db.dao().lastMoodNear(now)" in widget)
        assertFalse("journalOnNow(" in widget)
    }

    /**
     * Nothing is written when the pause runs out, so the waiting face asks for its own redraw at that
     * moment: otherwise it would count on, and take no tap, until the launcher's next half-hourly
     * update.
     */
    @Test
    fun theWaitingFaceAsksToBeDrawnAgainWhenThePauseIsOver() {
        assertEquals(logged + MOOD_COOLDOWN_MS, moodPauseEndsAt(logged, logged + minute))
        assertNull(moodPauseEndsAt(logged, logged + MOOD_COOLDOWN_MS))
        assertNull(moodPauseEndsAt(null, logged))
        val widgets = File("src/main/java/com/pwnall/health/Widgets.kt").readText()
        assertTrue("moodPauseEndsAt(last, now)?.let { WidgetClock.redrawAt(context, KIND_MOOD, it) }" in widgets)
    }

    /**
     * The door, the page and the widget ask one question: the newest mood within a pause either side
     * of the moment. Asked without the far bound, a mood stamped a day ahead by a clock that ran fast
     * would be the newest on file and hide the real one before it: the pause that one started would
     * not be kept.
     */
    @Test
    fun theThreeProbesAskForTheNewestMoodWithinAPauseEitherSide() {
        val mood = File("src/main/java/com/pwnall/health/Mood.kt").readText()
        val data = File("src/main/java/com/pwnall/health/Data.kt").readText()
        val page = File("src/main/java/com/pwnall/health/MoodScreen.kt").readText()

        assertTrue("lastEntryBetweenMs(KIND_MOOD, atMs - MOOD_COOLDOWN_MS, atMs + MOOD_COOLDOWN_MS)" in mood)
        assertTrue("AND occurredAt > :sinceMs AND occurredAt < :untilMs" in data)
        assertTrue("dao().lastMoodNear(entry.occurredAt)" in data)
        assertTrue("dao.lastMoodNear(entry.occurredAt)" in page)
        // A stamp a whole pause ahead, were it handed over all the same, holds nothing shut.
        assertEquals(0L, moodCooldownLeftMs(logged + MOOD_COOLDOWN_MS, logged))
    }
}
