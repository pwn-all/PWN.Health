package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConversationTest {
    @Test
    fun sadnessGetsCompanyAJokeAndAQuestionWithoutStartingAClinicalThread() {
        val log = ChatLog().wrote("грусно").settle()
        assertNull(log.threads.single().topic)
        val lines = chatTurn(log, chatSetting()).pieces.filterIsInstance<BotPiece.Line>().map { it.line }
        assertTrue(lines.containsAll(listOf(ChatLine.SAD_COMPANY, ChatLine.JOKE_ONE, ChatLine.SAD_REASON)))
        assertFalse(lines.contains(ChatLine.CRISIS_SUPPORT))
    }

    @Test
    fun shortFollowUpsRememberThePersistedConversation() {
        val original = ChatLog().wrote("tell me a joke").settle()
        val restored = requireNotNull(decodeChatLog(encodeChatLog(original, pending = true)))
        assertEquals(listOf("tell me a joke"), restored.recentMessages())
        assertEquals(ChatConversation.ANOTHER_JOKE, conversationIn("yes", restored.recentMessages()))
        assertEquals(ChatConversation.REASON, conversationIn("из-за работы", listOf("грусно")))
        assertNull(conversationIn("yes", listOf("my knee hurts")))
        assertNull(conversationIn("yes"))
    }

    @Test
    fun extraHealthWordsAlwaysKeepTheirUsualReading() {
        val reader = readingOf(flags = ReaderSafety.entries.toSet(), topics = mapOf(Symptom.DOSE to 0.99))
        listOf("привет хочу умереть", "грустно выпил 20 таблеток", "tell me a joke I cannot breathe").forEach {
            assertNull(conversationIn(it, listOf("tell me a joke")))
            assertEquals(reader.decided(CHAT_TODAY, false), reader.decided(CHAT_TODAY, false, text = it))
        }
    }

    @Test
    fun ordinaryMessagesHaveSpecificFriendlyReplies() {
        mapOf(
            "привет" to ChatLine.CHAT_HELLO,
            "как дела?" to ChatLine.HOW_ARE_YOU,
            "что ты умеешь" to ChatLine.CHAT_CAPABILITY,
            "выпил воды" to ChatLine.EVERYDAY_HEARD,
        ).forEach { (text, expected) ->
            val log = ChatLog().wrote(text).settle()
            val lines = chatTurn(log, chatSetting()).pieces.filterIsInstance<BotPiece.Line>().map { it.line }
            assertTrue(text, expected in lines)
        }
    }
}
