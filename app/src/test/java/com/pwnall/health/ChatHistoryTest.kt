package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chat in the history: its name, when it was, the line under it, and whether a tap carries it on
 * or reads it.
 */
class ChatHistoryTest {
    private fun title(log: ChatLog): String = historyTitle(
        log,
        title = { thread -> chatTitle(thread.report) { it.name } },
        untitled = "Chat",
        more = { name, count -> "$name +$count" },
    )

    private fun thread(id: Int, report: SymptomReport, vararg filed: FiledLink) = ChatThread(
        id,
        report,
        filed = filed.toList(),
    )

    /**
     * The first thread with a topic, "+N" for the others; for "something else" the person's words, never words of
     * suicide.
     */
    @Test
    fun theTitleIsTheFirstTopicWithTheCountOfTheRest() {
        val fever = thread(0, newChat().pick(Symptom.FEVER))
        val nausea = thread(1, newChat().pick(Symptom.NAUSEA))
        val words = thread(2, newChat().write("hello"))
        assertEquals("FEVER", title(ChatLog(threads = listOf(fever))))
        assertEquals("FEVER +1", title(ChatLog(threads = listOf(fever, words, nausea))))
        assertEquals("Chat", title(ChatLog(threads = listOf(words))))
        assertEquals("Chat", title(ChatLog()))
        val tingling =
            thread(0, newChat().write("A strange tingling in both of my hands since this morning").pick(Symptom.OTHER))
        assertEquals("A strange tingling in both of my hands…", title(ChatLog(threads = listOf(tingling))))
        val crisis = thread(0, newChat().write("I want to die").pick(Symptom.OTHER))
        assertEquals("OTHER", title(ChatLog(threads = listOf(crisis))))
        assertEquals("Itchy eyes", chatTitle(newChat().write("Itchy   eyes").pick(Symptom.OTHER)) { it.name })
    }

    /**
     * Words with no space to cut at — Chinese, one long word — are cut at the fortieth character, not one past
     * it, and never between the two halves of an emoji standing across the limit.
     */
    @Test
    fun wordsWithNoSpaceAreCutAtTheLimitAndNeverInsideAnEmoji() {
        fun named(words: String) = chatTitle(newChat().write(words).pick(Symptom.OTHER)) { it.name }
        assertEquals("头".repeat(40) + "…", named("头".repeat(60)))
        assertEquals("a".repeat(39) + "…", named("a".repeat(39) + "😀" + "b".repeat(10)))
    }

    /** The time a chat began, said only when it began on the day it is filed under. */
    @Test
    fun theTimeOnlyWhenStartedThatDay() {
        val today = ChatRow("k", CHAT_TODAY.toString(), CHAT_NOW + 90 * 60_000L, CHAT_NOW, "{}")
        assertEquals(13 * 60 + 30, historyMinute(today, CHAT_ZONE))
        val backDated = today.copy(day = CHAT_TODAY.minusDays(2).toString())
        assertNull(historyMinute(backDated, CHAT_ZONE))
    }

    /** "Not finished" only while the last thread's questions were open; else what it saved and still holds. */
    @Test
    fun theLineUnderTheTitle() {
        val open = ChatLog(threads = listOf(thread(0, newChat().pick(Symptom.FEVER))))
        assertTrue(historyUnfinished(open))
        val ended = driveChat(Symptom.FEVER).second
        val saved = FiledLink(KIND_SYMPTOM, at = 1, title = "Fever")
        val undone = FiledLink(KIND_PAIN, role = "x", at = 2, title = "Pain", state = LinkState.UNDONE)
        val atPrompt = ChatLog(threads = listOf(thread(0, ended, saved, undone)))
        assertFalse(historyUnfinished(atPrompt))
        assertEquals(listOf("Fever"), historySaved(atPrompt))
        assertFalse(historyUnfinished(atPrompt.copy(end = ChatEnd.ALL)))
        // Left at the prompt is not unfinished: every thread it began is over.
        assertTrue(historySaved(ChatLog(threads = listOf(thread(0, ended)))).isEmpty())
    }

    /** A tap carries on a chat not ended and begun today, whatever day it is filed under; any other is read. */
    @Test
    fun aTapCarriesOnOnlyAChatBegunTodayAndNotEnded() {
        val row = ChatRow("k", CHAT_TODAY.minusDays(4).toString(), CHAT_NOW, CHAT_NOW, "{}")
        assertTrue(continuesLive(row, ChatLog(), CHAT_TODAY, CHAT_ZONE))
        assertFalse(continuesLive(row, ChatLog(end = ChatEnd.STOP), CHAT_TODAY, CHAT_ZONE))
        assertFalse(continuesLive(row, ChatLog(), CHAT_TODAY.plusDays(1), CHAT_ZONE))
    }
}
