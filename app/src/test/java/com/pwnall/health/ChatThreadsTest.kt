package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chat of several threads, as the page commits it: each thread asks as a chat of its topic alone
 * would, the questions words bring stay in their own thread, the pencil stands where it may, and the
 * clock and the taps take only what the person gave.
 */
class ChatThreadsTest {
    private val setting = chatSetting()

    /** The calmest path of each symptom topic, red flags first, as a chat of that topic alone asks it. */
    private val golden: Map<Symptom, String> = mapOf(
        Symptom.VOMITING to "SYMPTOM BLOOD WITH ONSET AFTER_MEAL CAUSE TIMES SEVERITY",
        Symptom.CHEST_PAIN to "SYMPTOM CHEST_NOW WITH ONSET PATTERN SEVERITY",
        Symptom.PALPITATIONS to "SYMPTOM PALP_NOW WITH ONSET PATTERN WHEN FEEL SEVERITY",
        Symptom.FEVER to "SYMPTOM WITH ONSET TREND SEVERITY",
        Symptom.BREATHLESSNESS to "SYMPTOM WITH WHEN ONSET PATTERN SEVERITY",
        Symptom.WORRY to "SYMPTOM ABOUT HOW_LONG MIND_OFTEN AFFECTS COPING WITH SEVERITY",
        Symptom.LOW_MOOD to "SYMPTOM ABOUT HOW_LONG CRISIS AFFECTS COPING WITH SEVERITY",
        Symptom.SLEEP_WORRY to "SYMPTOM NIGHT HOW_LONG ABOUT AFFECTS COPING SEVERITY",
        Symptom.OTHER to "SYMPTOM WITH ONSET PATTERN AFFECTS SEVERITY",
    )

    private fun asked(thread: ChatThread): List<SymptomStep> =
        thread.report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Asked>().map { it.step }

    /** Each symptom topic asks the same questions, first thread or second, through the router. */
    @Test
    fun everySymptomTopicAsksItsOwnSteps() {
        golden.forEach { (topic, steps) ->
            val expected = steps.split(' ').map(SymptomStep::valueOf)
            val alone = driveLog(listOf(ThreadPlan(topic)))
            assertEquals(topic.name, expected, asked(alone.threads.single()))
            assertEquals(ChatEnd.ALL, alone.end)
            val second = driveLog(listOf(ThreadPlan(Symptom.TIREDNESS), ThreadPlan(topic)))
            assertEquals(topic.name, expected, asked(second.threads.last()))
        }
    }

    /** A question words bring is asked in the thread they were written in, and only there. */
    @Test
    fun wordQuestionsAreAskedInTheThreadTheyWereWrittenIn() {
        val fever = driveLog(listOf(ThreadPlan(Symptom.FEVER))).unended()
        val tired = fever.choseTopic(TopicChoice(Symptom.TIREDNESS), setting).settle().wrote("I can't breathe").settle()
        assertEquals(SymptomStep.BREATH_NOW, (chatMove(tired, setting) as ChatMove.Ask).step)
        val answered = tired.answeredLast(setting) {
            it.tapped(SymptomStep.BREATH_NOW, SymptomOption.NO, null)
        }.settle()
        assertTrue(SymptomStep.BREATH_NOW in asked(answered.threads.last()))
        assertFalse(SymptomStep.BREATH_NOW in asked(answered.threads.first()))
        // And the tiredness question that was waiting comes back after it.
        assertEquals(SymptomStep.WITH, (chatMove(answered, setting) as ChatMove.Ask).step)
    }

    /** Words typed as an answer are taken as typed — unless they are of suicide or an emergency: a message then. */
    @Test
    fun aTypedAnswerWithCrisisWordsIsNotTaken() {
        // A thread whose question takes its answer as a value, as a typed one does.
        val log = ChatLog(threads = listOf(ChatThread(0, newChat().chose(Symptom.PAIN))), next = 1)
        val step = SymptomStep.PAIN_NRS
        val crisis = log.typedLast(step, "I want to die", setting).settle()
        val turns = crisis.threads.single().report.chat?.turns.orEmpty()
        assertEquals(ChatTurn.Wrote("I want to die"), turns.last())
        assertNull(crisis.threads.single().report.values[step])
        assertEquals(SymptomStep.CRISIS_NOW, (chatMove(crisis, setting) as ChatMove.Ask).step)
        val emergency = log.typedLast(step, "I can't breathe", setting)
        assertEquals(ChatTurn.Wrote("I can't breathe"), emergency.threads.single().report.chat?.turns?.last())
        val plain = log.typedLast(step, "  7  ", setting)
        assertEquals("7", plain.threads.single().report.values[step])
        assertTrue(step in checkNotNull(plain.threads.single().report.chat).askedSteps())
    }

    /**
     * The pencil: only on the last thread's answers, and on the pain thread that handed over to a
     * chest thread while that one has no answer of its own; never on a thread whose record was changed
     * on its own page.
     */
    @Test
    fun pencilsOnlyOnTheLastThreadAndTheHandingOverOne() {
        val two = driveLog(listOf(ThreadPlan(Symptom.FEVER), ThreadPlan(Symptom.NAUSEA))).unended()
        assertEquals(setOf(two.threads.last().id), pencilThreads(two))
        val pain = ChatThread(0, newChat().pick(Symptom.PAIN))
        val chest = ChatThread(1, newChat().routedTo(Symptom.CHEST_PAIN), from = ROUTE_HANDOVER + "0")
        val handed = ChatLog(threads = listOf(pain, chest), next = 2)
        assertEquals(setOf(0, 1), pencilThreads(handed))
        val ownAnswer = chest.copy(report = chest.report.say(SymptomStep.CHEST_NOW, SymptomOption.NO))
        assertEquals(setOf(1), pencilThreads(handed.copy(threads = listOf(pain, ownAnswer))))
        val changed = two.copy(
            threads = two.threads.dropLast(1) +
                two.threads.last().copy(
                    filed = listOf(FiledLink(KIND_SYMPTOM, at = 1, title = "x", state = LinkState.CHANGED)),
                ),
        )
        assertTrue(pencilThreads(changed).isEmpty())
    }

    /** The topic of a thread begun from the menu taken back: with no words left the thread goes, back to the prompt. */
    @Test
    fun aTopicRewindRemovesAWordlessThread() {
        val one = driveLog(listOf(ThreadPlan(Symptom.FEVER))).unended()
        val two = one.choseTopic(TopicChoice(Symptom.NAUSEA), setting).settle()
        val back = two.rewoundLog(two.threads.last().id, 0).settle()
        assertEquals(one.threads.map { it.id }, back.threads.map { it.id })
        assertEquals(ChatMove.Prompt, chatMove(back, setting))
        // A thread begun with words keeps them, and asks its topic again.
        val worded = one.wrote("my stomach is upset").settle().let { log ->
            log.choseTopic(TopicChoice(Symptom.NAUSEA), setting).settle()
        }
        val kept = worded.rewoundLog(worded.threads.last().id, 1).settle()
        assertEquals(2, kept.threads.size)
        assertNull(kept.threads.last().topic)
        assertEquals("my stomach is upset", kept.threads.last().report.chat?.words())
    }

    /** Values, the painting and frozen facts go the way answers go: kept once asked, taken back with their step. */
    @Test
    fun valuesStrokesAndMetaThroughForSaveAsToldRewoundTo() {
        val stroke = PainStroke(PainView.FRONT, 0.03f, listOf(Offset(0.6f, 0.62f)))
        val waiting = newChat().pick(Symptom.PAIN).painted(SymptomStep.PAIN_AREA, listOf(stroke))
            .valued(SymptomStep.PAIN_AREA, "KNEE:R")
        // Waiting: no answer yet, so nothing of it is told or saved.
        assertTrue(waiting.asTold().values.isEmpty())
        assertTrue(waiting.forSave().strokes.isEmpty())
        val sent = waiting.sent(SymptomStep.PAIN_AREA).copy(meta = mapOf("PAIN_AREA.earlier" to "5"))
            .valued(SymptomStep.PAIN_NRS, "6").answered(SymptomStep.PAIN_NRS)
        assertEquals("KNEE:R", sent.asTold().values[SymptomStep.PAIN_AREA])
        assertEquals(listOf(stroke), sent.forSave().strokes)
        assertEquals("5", sent.forSave().meta["PAIN_AREA.earlier"])
        // Taken back to the area: the ruler's value goes, the area's own stays shown, its facts to be frozen again.
        val areaAt = checkNotNull(sent.chat).turns.indexOfFirst {
            (it as? ChatTurn.Asked)?.step == SymptomStep.PAIN_AREA
        }
        val back = rewoundTo(sent, areaAt)
        assertNull(back.values[SymptomStep.PAIN_NRS])
        assertEquals("KNEE:R", back.values[SymptomStep.PAIN_AREA])
        assertEquals(listOf(stroke), back.strokes)
        assertTrue(back.meta.isEmpty())
        // The answer's facts are frozen as it is answered, under its step's name.
        val log = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle()
        val frozen = log.answeredLast(setting, freeze = { _, step ->
            mapOf("${step.name}.seen" to "1", "OTHER.x" to "no")
        }) {
            it.copy(answers = it.answers + (SymptomStep.WITH to listOf(SymptomOption.NONE))).answered(SymptomStep.WITH)
        }
        assertEquals(mapOf("WITH.seen" to "1"), frozen.threads.single().report.meta)
        // A message is no answer: nothing is frozen for it.
        val wrote = log.answeredLast(setting, freeze = { _, _ -> mapOf("WITH.x" to "1") }) { it.wrote("hello") }
        assertEquals(emptyMap<String, String>(), wrote.threads.single().report.meta)
    }

    /** A clock that outlived its question — the pencil took the chat back before it — files nothing. */
    @Test
    fun aClockThatOutlivedItsQuestionFilesNothing() {
        val log = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle()
        assertEquals(SymptomStep.WITH, waitingStep(log, setting))
        val ticked = log.tickedLast(SymptomStep.WITH, SymptomOption.CHILLS, setting).first
        val taken = ticked.takenLast(SymptomStep.WITH, setting).settle()
        assertEquals(SymptomStep.ONSET, (chatMove(taken, setting) as ChatMove.Ask).step)
        // ONSET is a tap: no clock waits on it, and a late clock for WITH takes nothing.
        assertNull(waitingStep(taken, setting))
        assertEquals(taken, taken.takenLast(SymptomStep.WITH, setting))
        // Ticks taken back by the pencil before their clock: nothing to take, the earlier question asked again.
        val blood = ChatLog().choseTopic(TopicChoice(Symptom.VOMITING), setting).settle()
            .answeredLast(setting) { it.tapped(SymptomStep.BLOOD, SymptomOption.NO, null) }.settle()
        val ticking = blood.tickedLast(SymptomStep.WITH, SymptomOption.CANT_KEEP_FLUIDS, setting).first
        val rewound = ticking.rewoundLog(0, 1).settle()
        assertNull(rewound.threads.single().report.answers[SymptomStep.WITH])
        assertEquals(rewound, rewound.takenLast(SymptomStep.WITH, setting))
        assertEquals(SymptomStep.BLOOD, (chatMove(rewound, setting) as ChatMove.Ask).step)
    }

    /**
     * "None of these" tapped with nothing else ticked is taken on the tap; with other ticks it replaces them, and the
     * clock waits.
     */
    @Test
    fun anExclusiveAnswerTappedAloneIsTakenOnTheTap() {
        val log = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle()
        val (none, taken) = log.tickedLast(SymptomStep.WITH, SymptomOption.NONE, setting)
        assertTrue(taken)
        assertTrue(SymptomStep.WITH in checkNotNull(none.threads.single().report.chat).askedSteps())
        val (chills, first) = log.tickedLast(SymptomStep.WITH, SymptomOption.CHILLS, setting)
        assertFalse(first)
        val (replaced, second) = chills.tickedLast(SymptomStep.WITH, SymptomOption.NONE, setting)
        assertFalse(second)
        assertEquals(listOf(SymptomOption.NONE), replaced.threads.single().report.answers[SymptomStep.WITH])
        assertFalse(SymptomStep.WITH in checkNotNull(replaced.threads.single().report.chat).askedSteps())
    }

    /**
     * A message during a question: kept, met the first time in the chat with the long remark, then in
     * short — or, when it names a topic of its own, with "let us finish this first" — and always with
     * the line to call; the question is asked again, and the topic named waits for the prompt.
     */
    @Test
    fun messagesDuringAQuestionAreKeptAndMetInShortAfterTheFirst() {
        val fever = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle()
        val once = fever.wrote("shivery since lunch").settle()
        val first = chatTurn(once, setting)
        assertEquals(listOf(ChatLine.REMARK, ChatLine.SAFETY), first.lines())
        assertEquals(SymptomStep.WITH, first.asked())
        val twice = once.wrote("and tired").settle()
        assertEquals(listOf(ChatLine.QUEUED, ChatLine.SAFETY), chatTurn(twice, setting).lines())
        val thrice = twice.wrote("it is bad").settle()
        assertEquals(listOf(ChatLine.REMARK_SHORT, ChatLine.SAFETY), chatTurn(thrice, setting).lines())
        val over = thrice.updateLast { it.copy(chat = it.chat?.copy(ended = true)) }
        assertEquals(listOf(TopicMention(Symptom.TIREDNESS)), chatQueue(over, setting).map { it.mention })

        // A later thread: the long remark was said in the first already.
        val calm = driveChat(Symptom.FEVER).second
        val turns = checkNotNull(calm.chat).turns
        val remarked = calm.copy(
            chat = calm.chat.copy(turns = turns.take(1) + ChatTurn.Wrote("shivery") + turns.drop(1)),
        )
        val later = ChatLog(threads = listOf(ChatThread(0, remarked)), next = 1).settle()
            .choseTopic(TopicChoice(Symptom.NAUSEA), setting).settle()
            .wrote("hmm").settle()
        assertEquals(listOf(ChatLine.REMARK_SHORT, ChatLine.SAFETY), chatTurn(later, setting).lines())
    }

    /** A row whose chat was deleted reads as one thread of what it holds, up to the first answer it lacks. */
    @Test
    fun aRowWhoseChatWasDeletedIsReplayedFromItsAnswers() {
        val told = driveChat(Symptom.FEVER).second.forSave()
        val row = told.copy(chat = null)
        val replayed = replayedChat(row)
        assertEquals(asked(ChatThread(0, told)), asked(ChatThread(0, replayed)))
        assertTrue(checkNotNull(replayed.chat).ended)
        val part = replayedChat(row.copy(answers = row.answers - SymptomStep.ONSET, severity = null))
        assertEquals(listOf(SymptomStep.SYMPTOM, SymptomStep.WITH), asked(ChatThread(0, part)))
        assertFalse(checkNotNull(part.chat).ended)
    }
}
