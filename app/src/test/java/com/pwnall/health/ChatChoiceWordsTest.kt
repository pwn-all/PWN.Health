package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatChoiceWordsTest {
    private val setting = chatSetting()

    private fun feverQuestion(): ChatLog = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle(setting)

    @Test
    fun noAnswersTheCurrentSignsQuestionAndSurvivesReopening() {
        val question = feverQuestion()
        assertEquals(SymptomStep.WITH, (chatMove(question, setting) as ChatMove.Ask).step)
        assertEquals(R.string.symptom_ask_with, chatQuestionRes(Symptom.FEVER, SymptomStep.WITH, newChat()))

        val answered = requireNotNull(question.typedChoiceLast("  no  ", setting)).settle(setting)
        val report = answered.threads.single().report
        assertEquals(listOf(SymptomOption.NONE), report.answers[SymptomStep.WITH])
        assertEquals("no", report.values[SymptomStep.WITH])
        assertEquals(SymptomStep.ONSET, (chatMove(answered, setting) as ChatMove.Ask).step)
        assertEquals(question.messageCount(), answered.messageCount())
        assertFalse(report.chat?.turns.orEmpty().any { it is ChatTurn.Wrote })
        assertEquals(listOf(SymptomOption.NONE), report.asTold().answers[SymptomStep.WITH])
        assertEquals("no", report.asTold().values[SymptomStep.WITH])

        val restored = requireNotNull(decodeChatLog(encodeChatLog(answered, pending = false)))
        assertEquals(listOf(SymptomOption.NONE), restored.threads.single().report.answers[SymptomStep.WITH])
        assertEquals("no", restored.threads.single().report.values[SymptomStep.WITH])
        assertEquals(SymptomStep.ONSET, (chatMove(restored, setting) as ChatMove.Ask).step)

        val position = report.chat?.turns.orEmpty().indexOfFirst { it is ChatTurn.Asked && it.step == SymptomStep.WITH }
        val rewound = restored.rewoundLog(restored.threads.single().id, position).settle(setting)
        assertEquals(SymptomStep.WITH, (chatMove(rewound, setting) as ChatMove.Ask).step)
        assertNull(rewound.threads.single().report.asTold().answers[SymptomStep.WITH])
        assertNull(rewound.threads.single().report.asTold().values[SymptomStep.WITH])
        assertNotNull(rewound.typedChoiceLast("нет", setting))
    }

    @Test
    fun anExplicitNegativeReplacesPendingTicksAndButtonEditsReplaceItsWords() {
        val question = feverQuestion()
        val reply = chatTurn(question, setting).reply as ChatReply.Choose
        val sign = reply.options.first { it != SymptomOption.NONE }
        val ticked = question.updateLast { it.copy(answers = it.answers + (reply.step to listOf(sign))) }
        val answered = requireNotNull(ticked.typedChoiceLast("Нет!", setting)).settle(setting)
        assertEquals(listOf(SymptomOption.NONE), answered.threads.single().report.answers[reply.step])

        val edited = answered.threads.single().report.tapped(reply.step, sign, null)
        assertEquals(listOf(sign), edited.answers[reply.step])
        assertNull(edited.values[reply.step])

        val turns = answered.threads.single().report.chat?.turns.orEmpty()
        val position = turns.indexOfFirst { it is ChatTurn.Asked && it.step == reply.step }
        val rewound = answered.rewoundLog(answered.threads.single().id, position).settle(setting)
        val (changed, _) = rewound.tickedLast(reply.step, sign, setting)
        assertEquals(listOf(sign), changed.threads.single().report.answers[reply.step])
        assertNull(changed.threads.single().report.values[reply.step])
    }

    @Test
    fun shortRepliesOnlyChooseAnOptionOfferedByTheCurrentQuestion() {
        val binary = ChatReply.Choose(SymptomStep.BREATH_NOW, listOf(SymptomOption.YES, SymptomOption.NO))
        assertEquals(SymptomOption.NO, binary.answerIn("NO."))
        assertEquals(SymptomOption.YES, binary.answerIn("да"))
        assertEquals(SymptomOption.YES, binary.answerIn("yes!"))

        val signs = chatTurn(feverQuestion(), setting).reply as ChatReply.Choose
        assertEquals(SymptomOption.NONE, signs.answerIn("none of these"))
        assertNull(signs.answerIn("yes"))
        assertNull(ChatLog().typedChoiceLast("no", setting))
        assertNull(feverQuestion().endedByPerson().typedChoiceLast("no", setting))

        val crisis = ChatReply.Choose(
            SymptomStep.CRISIS_NOW,
            listOf(SymptomOption.NO_SUCH_THOUGHTS, SymptomOption.SAFE_NOW, SymptomOption.CRISIS_UNSAFE),
        )
        assertNull(crisis.answerIn("no"))
        assertNull(crisis.answerIn("yes"))
    }

    @Test
    fun additionalSymptomsAndUncertainWordsKeepTheUsualMessagePath() {
        val question = feverQuestion()
        val messages = listOf(
            "no, I cannot breathe", "no chest pain", "not sure", "no?", "no? maybe", "no 🤮", "нет 💊", "", "no no",
        )
        messages.forEach { text ->
            assertNull(text, question.typedChoiceLast(text, setting))
        }

        val urgent = question.wrote("no, I cannot breathe").settle(setting)
        assertEquals(SymptomStep.BREATH_NOW, (chatMove(urgent, setting) as ChatMove.Ask).step)
        assertNull(urgent.threads.single().report.answers[SymptomStep.WITH])
        assertNotNull(urgent.threads.single().report.chat?.turns?.last() as? ChatTurn.Wrote)
    }

    /**
     * Ticks on a question of several answers, as "today's doses gone by" asks them: a time joins the others,
     * "None yet" stands alone and goes when a time is ticked, and a second tap takes a tick away.
     */
    @Test
    fun anExclusiveTickStandsAloneAndASecondTapTakesATickAway() {
        val exclusive = setOf("NONE", "TOMORROW")
        assertEquals("08:00", toggledValue(emptyList(), "08:00", exclusive))
        assertEquals("08:00,14:00", toggledValue(listOf("08:00"), "14:00", exclusive))
        assertEquals("NONE", toggledValue(listOf("08:00", "14:00"), "NONE", exclusive))
        assertEquals("TOMORROW", toggledValue(listOf("NONE"), "TOMORROW", exclusive))
        assertEquals("08:00", toggledValue(listOf("NONE"), "08:00", exclusive))
        assertEquals("08:00", toggledValue(listOf("08:00", "14:00"), "14:00", exclusive))
        assertEquals("", toggledValue(listOf("NONE"), "NONE", exclusive))
    }
}
