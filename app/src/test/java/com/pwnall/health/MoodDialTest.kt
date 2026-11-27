package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mood page's dial, shared with the chat: on the page it is always given, so it lights the face
 * under it and says its name; in a chat it lights nothing and says "Not set yet" until
 * touched — a face resting on the middle is where the control starts, not an answer.
 */
class MoodDialTest {
    @Test
    fun aGivenDialLightsTheFaceUnderIt() {
        assertEquals(Mood.entries.indexOf(Mood.NEUTRAL), moodDialSelected(3f, given = true))
        assertEquals(Mood.entries.indexOf(Mood.LOW), moodDialSelected(2.4f, given = true))
        assertEquals(Mood.entries.indexOf(Mood.GOOD), moodDialSelected(3.6f, given = true))
        assertEquals(Mood.entries.indexOf(Mood.FUN), moodDialSelected(5f, given = true))
        assertEquals("Neutral", moodDialState(given = true, moodName = "Neutral", unset = "Not set yet"))
    }

    @Test
    fun aDialNobodyTouchedLightsNothingAndIsNotSetYet() {
        (1..5).forEach { assertEquals(-1, moodDialSelected(it.toFloat(), given = false)) }
        assertEquals("Not set yet", moodDialState(given = false, moodName = "Neutral", unset = "Not set yet"))
    }

    /**
     * The dial the chat draws is the reply the core folds away once it is taken: it stays in
     * its place, shrinking, while the dots stand for the next question, which itself does not fold.
     */
    @Test
    fun theChatsDialFoldsAwayOnceTakenAndTheNextQuestionDoesNot() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        val dial = chat.turn().reply
        assertTrue(dial is ChatReply.MoodDial)
        assertTrue(dial.foldsAway)
        chat.dial(3)
        assertEquals(SymptomStep.MOOD_ABOUT, chat.asked())
        assertFalse(chat.turn().reply.foldsAway)
    }
}
