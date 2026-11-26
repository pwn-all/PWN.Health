package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chat as a list of threads, and the pure changes the page commits to it: a topic chosen, a
 * message written, an answer that is a value or a painting, a rewind, the filer's links merged in.
 */
class ChatLogTest {
    private val snapshot = SymptomContext(sameDay = true, steps = 4_200)
    private val setting = ChatSetting(
        female = false,
        adult = true,
        day = CHAT_TODAY,
        past = false,
        now = CHAT_NOW,
        chatMoment = CHAT_NOW,
        snapshot = snapshot,
        env = ChatEnv(),
        zone = CHAT_ZONE,
        sittingFrom = 0,
        language = ChatLanguage.EN,
    )
    private val stroke = PainStroke(PainView.FRONT, 0.035f, listOf(Offset(0.64f, 0.615f)))

    /** A thread over [report], ended: the questions are over. */
    private fun ended(report: SymptomReport) = report.copy(chat = checkNotNull(report.chat).copy(ended = true))

    @Test
    fun aTopicChosenAtTheOpeningBeginsAThreadAndOnlyASymptomTakesTheSnapshot() {
        val fever = ChatLog().chose(TopicChoice(Symptom.FEVER), setting)
        val thread = fever.threads.single()
        assertEquals(0 to 1, thread.id to fever.next)
        assertEquals(Symptom.FEVER, thread.topic)
        assertEquals(listOf(ChatTurn.Asked(SymptomStep.SYMPTOM)), thread.report.chat?.turns)
        assertEquals(snapshot, thread.report.context)
        assertNull(thread.from)

        // A pain has no facts card: no snapshot rides along with it.
        val pain = ChatLog().chose(TopicChoice(Symptom.PAIN, TopicMention(Symptom.PAIN, "KNEE")), setting)
        assertNull(pain.threads.single().report.context)
        assertEquals("PAIN:KNEE", pain.threads.single().from)
    }

    /** Words written first are the thread's first turns; the topic chosen after them is that thread's. */
    @Test
    fun aTopicChosenAfterWordsIsTheirThreads() {
        val words = ChatLog().wrote("  my knee hurts  ")
        assertEquals(listOf(ChatTurn.Wrote("my knee hurts")), words.threads.single().report.chat?.turns)
        assertNull(words.threads.single().topic)

        val chosen = words.chose(TopicChoice(Symptom.PAIN, TopicMention(Symptom.PAIN, "KNEE")), setting)
        val thread = chosen.threads.single()
        assertEquals(Symptom.PAIN, thread.topic)
        assertEquals("PAIN:KNEE", thread.from)
        assertEquals(1, chosen.next)
        // A blank message is nothing.
        assertSame(chosen, chosen.wrote("   "))
    }

    /** Once a thread is over, a message or a topic begins the next; ids are never handed out twice. */
    @Test
    fun theNextThreadTakesANewIdEvenAfterOneWasTakenAway() {
        val first = ChatLog(threads = listOf(ChatThread(0, ended(newChat().pick(Symptom.FEVER)))), next = 1)
        val prompt = first.wrote("and my back")
        assertEquals(listOf(0, 1), prompt.threads.map { it.id })
        assertEquals(listOf(ChatTurn.Wrote("and my back")), prompt.threads[1].report.chat?.turns)

        // The second thread's topic taken back with no words left: it goes, and its id with it.
        val chosen = first.chose(TopicChoice(Symptom.DIZZINESS), setting)
        val removed = chosen.rewoundLog(1, 0)
        assertEquals(listOf(0), removed.threads.map { it.id })
        assertEquals(2, removed.next)
        assertEquals(listOf(0, 2), removed.chose(TopicChoice(Symptom.NAUSEA), setting).threads.map { it.id })
    }

    /** "That is all for now", and its pencil; after the end nothing is written or chosen. */
    @Test
    fun theEndAndItsUndoing() {
        val log = ChatLog(threads = listOf(ChatThread(0, ended(newChat().pick(Symptom.FEVER)))), next = 1)
        val over = log.endedByPerson()
        assertEquals(ChatEnd.ALL, over.end)
        assertSame(over, over.wrote("one more thing"))
        assertSame(over, over.chose(TopicChoice(Symptom.NAUSEA), setting))
        assertNull(over.unended().end)
        assertEquals("A note", over.noted("  A note  ").note)
        // The note is typed in the note field, which keeps 120 characters: the chat keeps no more.
        assertEquals(NOTE_MAX_CHARS, over.noted("x".repeat(NOTE_MAX_CHARS + 9)).note.length)
    }

    /**
     * The pencil on a handing-over thread takes the threads routed from it away; taking the topic
     * back keeps the words and loses the topic; a stop is carried on from.
     */
    @Test
    fun aRewindDropsTheThreadsAfterItAndKeepsTheWords() {
        val words = ChatLog().wrote("my chest and knee")
            .chose(TopicChoice(Symptom.PAIN), setting)
            .updateLast { it.valued(SymptomStep.PAIN_AREA, "KNEE:R").sent(SymptomStep.PAIN_AREA) }
        val handed = words.copy(
            threads = words.threads + ChatThread(1, newChat().pick(Symptom.CHEST_PAIN), from = "HANDOVER:0"),
            next = 2,
            end = ChatEnd.STOP,
        )

        val area = handed.rewoundLog(0, 2)
        assertEquals(listOf(0), area.threads.map { it.id })
        assertNull(area.end)
        assertEquals("KNEE:R", area.threads.single().report.values[SymptomStep.PAIN_AREA])
        assertEquals(listOf(SymptomStep.SYMPTOM), area.threads.single().report.chat?.askedSteps()?.toList())

        val topic = handed.rewoundLog(0, 1)
        val kept = topic.threads.single()
        assertNull(kept.topic)
        assertEquals("my chest and knee", kept.report.chat?.words())
        assertTrue(kept.report.values.isEmpty())
        // A position that is not an answer, or a thread that is not there, changes nothing.
        assertSame(handed, handed.rewoundLog(0, 0))
        assertSame(handed, handed.rewoundLog(9, 1))
    }

    /** The filer's links go into the threads they name, by id; nothing else of the log changes. */
    @Test
    fun withLinksMergesByThreadIdAndNothingElse() {
        val log = ChatLog(
            threads = listOf(ChatThread(4, newChat().pick(Symptom.FEVER)), ChatThread(7, newChat())),
            next = 8,
            rev = 3,
        )
        val link = FiledLink(KIND_SYMPTOM, at = 5, title = "Fever", sig = 1, wsig = 2)

        val merged = log.withLinks(FilingResult(mapOf(4 to listOf(link), 9 to listOf(link)), emptyList(), emptyList()))

        assertEquals(listOf(link), merged.threads[0].filed)
        assertTrue(merged.threads[1].filed.isEmpty())
        assertEquals(
            log.copy(
                threads = log.threads.mapIndexed { i, t ->
                    if (i ==
                        0
                    ) {
                        t.copy(filed = listOf(link))
                    } else {
                        t
                    }
                },
            ),
            merged,
        )
    }

    /** A newer stored chat is taken whole, but for what this page's same last thread still has waiting. */
    @Test
    fun adoptingKeepsOnlyTheLastThreadsWaitingAnswers() {
        val asked = newChat().pick(Symptom.FEVER).say(SymptomStep.WITH, SymptomOption.CHILLS)
        val stored =
            ChatLog(
                threads = listOf(ChatThread(0, asked, filed = listOf(FiledLink(KIND_SYMPTOM, at = 1, title = "x")))),
                next = 1,
                rev = 9,
            )
        val waiting = asked.copy(answers = asked.answers + (SymptomStep.ONSET to listOf(SymptomOption.STARTED_TODAY)))
        val mine = ChatLog(threads = listOf(ChatThread(0, waiting)), next = 1, rev = 4)

        val adopted = mine.adopting(stored)
        assertEquals(9, adopted.rev)
        assertEquals(stored.threads.single().filed, adopted.threads.single().filed)
        assertEquals(listOf(SymptomOption.STARTED_TODAY), adopted.threads.single().report.answers[SymptomStep.ONSET])
        // An answer the stored chat took stands as the stored chat has it.
        val changed = mine.copy(
            threads = listOf(
                ChatThread(
                    0,
                    waiting.copy(
                        answers =
                        waiting.answers + (SymptomStep.WITH to listOf(SymptomOption.COUGH)),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(SymptomOption.CHILLS),
            changed.adopting(stored).threads.single().report.answers[SymptomStep.WITH],
        )
        // Another last thread than the stored one keeps nothing of its own.
        assertEquals(stored, ChatLog(threads = listOf(ChatThread(5, waiting))).adopting(stored))
    }

    /** A value waits for the clock or Send as a tick does; a blank one is taken back; Send answers with it. */
    @Test
    fun valuesAndPaintingsWaitUntilTheyAreTaken() {
        val pain = newChat().pick(Symptom.PAIN)
        val valued = pain.valued(SymptomStep.PAIN_NRS, "6")
        assertEquals("6", valued.values[SymptomStep.PAIN_NRS])
        assertTrue(valued.holdsAnswer(SymptomStep.PAIN_NRS))
        assertTrue(valued.valued(SymptomStep.PAIN_NRS, " ").values.isEmpty())
        // Waiting, it is not an answer yet: the row keeps none of it.
        assertTrue(valued.forSave().values.isEmpty())
        assertEquals("6", valued.answered(SymptomStep.PAIN_NRS).forSave().values[SymptomStep.PAIN_NRS])

        // A value step with nothing held is not answered by Send or the clock.
        assertSame(pain, pain.answered(SymptomStep.PAIN_NRS))
        assertSame(pain, pain.sent(SymptomStep.PAIN_AREA))
        val painted = pain.painted(SymptomStep.PAIN_AREA, listOf(stroke))
        assertEquals(listOf(stroke), painted.strokes)
        assertSame(pain, pain.painted(SymptomStep.WITH, listOf(stroke)))
        val sent = painted.valued(SymptomStep.PAIN_AREA, "KNEE:R").sent(SymptomStep.PAIN_AREA)
        assertEquals(SymptomStep.PAIN_AREA, (sent.chat?.turns?.last() as ChatTurn.Asked).step)
        assertEquals(listOf(stroke), sent.forSave().strokes)
        assertTrue(painted.forSave().strokes.isEmpty())
    }

    /** Words typed as an answer are taken at once, trimmed and cut; blank words answer nothing. */
    @Test
    fun typedWordsAnswerAtOnce() {
        val pain = newChat().pick(Symptom.PAIN)
        val typed = pain.typed(SymptomStep.PAIN_NRS, "  7  ")
        assertEquals("7", typed.values[SymptomStep.PAIN_NRS])
        assertTrue(SymptomStep.PAIN_NRS in checkNotNull(typed.chat).askedSteps())
        assertSame(pain, pain.typed(SymptomStep.PAIN_NRS, "   "))
        assertEquals(
            SYMPTOM_TEXT_MAX,
            pain.typed(
                SymptomStep.PAIN_NRS,
                "y".repeat(SYMPTOM_TEXT_MAX + 1),
            ).values.getValue(SymptomStep.PAIN_NRS).length,
        )
    }

    /** How each step holds its answer: the pain thread's area, scale and length their own, every other its options. */
    @Test
    fun theStepsKindsComeFromTheirScripts() {
        assertEquals(StepKind(StepHolds.PAINTING, AnswerKind.TAP), SymptomStep.PAIN_AREA.kind)
        assertEquals(StepKind(StepHolds.VALUE, AnswerKind.SETTLE), SymptomStep.PAIN_NRS.kind)
        assertEquals(StepKind(StepHolds.VALUE, AnswerKind.SETTLE), SymptomStep.PAIN_LASTED.kind)
        assertEquals(StepKind(StepHolds.OPTIONS, AnswerKind.SETTLE), SymptomStep.PAIN_SIGNS.kind)
        assertEquals(StepKind(StepHolds.OPTIONS, AnswerKind.TAP), SymptomStep.PAIN_INJURY.kind)
        assertEquals(StepKind(StepHolds.OPTIONS, AnswerKind.SETTLE), SymptomStep.WITH.kind)
        assertEquals(StepKind(StepHolds.OPTIONS, AnswerKind.TAP), SymptomStep.ONSET.kind)
        val ticked = newChat().pick(
            Symptom.FEVER,
        ).copy(answers = mapOf(SymptomStep.WITH to listOf(SymptomOption.COUGH)))
        assertTrue(ticked.holdsAnswer(SymptomStep.WITH))
        assertFalse(ticked.holdsAnswer(SymptomStep.ONSET))
    }

    /**
     * The registry: every topic has its script; a record topic is offered once its thread fills it — the
     * pain thread's, the mood's and the medicine's are all filled.
     */
    @Test
    fun everyTopicHasItsScript() {
        assertEquals(
            Symptom.entries.associateWith { it.record },
            Symptom.entries.associateWith { topic ->
                when (topic.script) {
                    SymptomScript -> ChatRecord.SYMPTOM
                    PainScript -> ChatRecord.PAIN
                    MoodScript -> ChatRecord.MOOD
                    DoseScript -> ChatRecord.DOSE
                    CourseScript -> ChatRecord.COURSE
                    else -> null
                }
            },
        )
        assertTrue(Symptom.entries.filter { it.record == ChatRecord.SYMPTOM }.all { it.script.offered(setting) })
        // The pain thread's: PAIN is offered, on a day gone by too.
        assertTrue(Symptom.PAIN.script.offered(setting) && Symptom.PAIN.script.offered(setting.copy(past = true)))
        // The mood's: today only.
        assertTrue(Symptom.MOOD.script.offered(setting) && !Symptom.MOOD.script.offered(setting.copy(past = true)))
        // The medicine threads': a dose is offered on a day gone by too, a course today only.
        assertTrue(Symptom.DOSE.script.offered(setting) && Symptom.DOSE.script.offered(setting.copy(past = true)))
        assertTrue(Symptom.COURSE.script.offered(setting) && !Symptom.COURSE.script.offered(setting.copy(past = true)))
        // With the three packages filled, every record topic is offered.
        assertTrue(Symptom.entries.filter { it.record != ChatRecord.SYMPTOM }.all { it.script.offered(setting) })
        assertTrue(Symptom.entries.all { it.record == ChatRecord.SYMPTOM || !it.mind })
        // Chest pain is the one symptom topic a chat may run twice.
        assertEquals(
            listOf(Symptom.CHEST_PAIN),
            Symptom.entries.filter {
                it.record == ChatRecord.SYMPTOM &&
                    !it.asksOncePerChat
            },
        )
    }

    /** The symptom script asks today's questions in today's order: the same steps whether reached through it or not. */
    @Test
    fun theSymptomScriptAsksAsTheChatAlwaysDid() {
        val chosen = newChat().pick(Symptom.CHEST_PAIN)
        val state = chatState(chosen)
        assertEquals(listOf(SymptomStep.SYMPTOM) + SymptomScript.steps(state), chatSteps(state))
        assertEquals(
            chatOptions(Symptom.CHEST_PAIN, SymptomStep.WITH, female = false),
            SymptomScript.options(state, SymptomStep.WITH),
        )
        // Its close carries the saved card where the summary stood, and leaves the thanks to the router.
        val ended = chosen.say(SymptomStep.CHEST_NOW, SymptomOption.NO).say(SymptomStep.WITH, SymptomOption.NONE)
            .say(SymptomStep.ONSET, SymptomOption.STARTED_TODAY).say(SymptomStep.PATTERN, SymptomOption.ALL_THE_TIME)
            .rate(SymptomSeverity.MILD)
        val close = SymptomScript.close(chatState(ended).copy(thread = ThreadContext(id = 3)), emptyList())
        assertTrue(BotPiece.Saved(3) in close)
        assertFalse(close.any { it is BotPiece.Line && it.line == ChatLine.THANKS })
        // Its offers are the visit a GP's close calls for, named by the thread; another chat is no offer.
        assertEquals(listOf(BotPiece.Offer(ChatOffer.BOOK_VISIT, 3)), close.filterIsInstance<BotPiece.Offer>())
        assertTrue(close.any { it is BotPiece.Line && it.line == ChatLine.CLOSE_GP })
    }

    /** The start of a symptom topic is said once a chat for its kind: a second body topic is not told it again. */
    @Test
    fun theStartLinesComeInTheFirstThreadOfTheirKindOnly() {
        val chosen = newChat().pick(Symptom.FEVER)
        val last = chosen.chat?.turns?.last() as ChatTurn.Asked
        val first = SymptomScript.lead(chatState(chosen), last, SymptomStep.WITH)
        assertTrue(BotPiece.Line(ChatLine.START_BODY) in first)
        val second = chatState(chosen).copy(thread = ThreadContext(before = listOf(Symptom.PAIN, Symptom.NAUSEA)))
        assertFalse(BotPiece.Line(ChatLine.START_BODY) in SymptomScript.lead(second, last, SymptomStep.WITH))
        // A record topic before it is not a body topic's start.
        val afterPain = chatState(chosen).copy(thread = ThreadContext(before = listOf(Symptom.PAIN)))
        assertTrue(BotPiece.Line(ChatLine.START_BODY) in SymptomScript.lead(afterPain, last, SymptomStep.WITH))
    }
}
