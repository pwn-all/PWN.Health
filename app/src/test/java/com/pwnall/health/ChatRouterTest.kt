package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router: the opening and its menu, the reply to words written before a topic, the chest asked
 * about first, the queue, the prompt between threads, the end, and the transcript over threads.
 */
class ChatRouterTest {
    private val setting = chatSetting()

    /** A log of one thread, [report], as the page holds it after a commit. */
    private fun logOf(report: SymptomReport) = ChatLog(threads = listOf(ChatThread(0, report)), next = 1).settle()

    /** A whole symptom thread answered calmly, ended. */
    private fun calmThread(topic: Symptom) = driveChat(topic).second

    /** [log] with [report] begun as its next thread, after a prompt. */
    private fun ChatLog.plus(report: SymptomReport) =
        copy(threads = threads + ChatThread(next, report), next = next + 1).settle()

    /**
     * The opening: "Hello", the question, and the whole menu, records first, the body, feelings, something else last.
     */
    @Test
    fun theOpeningIsHelloOpeningAndTheGroupedMenu() {
        val turn = chatTurn(ChatLog(), setting)
        assertEquals(listOf(ChatLine.OPEN, ChatLine.OPENING), turn.lines())
        val reply = turn.reply as ChatReply.Topics
        assertTrue(reply.all)
        assertFalse(reply.end)
        // The order every menu keeps: what to note down, the body, feelings, then something else.
        assertEquals(listOf(Symptom.PAIN, Symptom.MOOD, Symptom.DOSE, Symptom.COURSE), TOPIC_ORDER.take(4))
        assertEquals(Symptom.OTHER, TOPIC_ORDER.last())
        assertEquals(Symptom.entries.toSet(), TOPIC_ORDER.toSet())
        val groups = TOPIC_ORDER.map { topic -> MENU_GROUPS.first { topic in it.second }.first }
        assertEquals(
            listOf(
                R.string.symptom_chat_group_records,
                R.string.symptom_chat_group_body,
                R.string.symptom_chat_group_mind,
                null,
            ),
            groups.distinct(),
        )
        assertTrue(MENU_GROUPS.first { it.first == R.string.symptom_chat_group_mind }.second.all { it.mind })
        // Every topic offered here, in its order: every symptom topic, and each record once its thread
        // is offered — all four: the pain's thread, the mood's and the medicines' stand first, in the
        // records' group, before the body.
        assertEquals(TOPIC_ORDER.filter { it.script.offered(setting) }, reply.choices.map { it.topic })
        val menu = reply.choices.map { it.topic }
        assertTrue(menu.containsAll(TOPIC_ORDER.filter { it.record == ChatRecord.SYMPTOM }))
        assertEquals(listOf(Symptom.PAIN, Symptom.MOOD, Symptom.DOSE, Symptom.COURSE), menu.take(4))
        assertTrue(menu.indexOf(Symptom.MOOD) in 0 until menu.indexOfFirst { it.record == ChatRecord.SYMPTOM })
    }

    /**
     * A chat for an earlier day says which day, and asks its red flags and a feeling's weight about now and that day.
     */
    @Test
    fun aChatForAnEarlierDayShowsForDayAndNoMoodOrCourse() {
        val past = chatSetting(past = true)
        val turn = chatTurn(ChatLog(past = true), past)
        assertEquals(BotPiece.ForDay(past.day), turn.pieces[1])
        val topics = (turn.reply as ChatReply.Topics).choices.map { it.topic }
        assertFalse(Symptom.MOOD in topics)
        assertFalse(Symptom.COURSE in topics)
        assertFalse(chatTurn(ChatLog(), setting).pieces.any { it is BotPiece.ForDay })

        val fever = ChatLog(past = true).choseTopic(TopicChoice(Symptom.FEVER), past).settle(past)
        val ask = chatTurn(fever, past).pieces.filterIsInstance<BotPiece.Ask>().single()
        assertEquals(SymptomStep.WITH, ask.step)
        val state = fever.threadState(0, past)
        assertEquals(
            R.string.symptom_ask_with_now,
            stepScript(state, SymptomStep.WITH).question(state, SymptomStep.WITH),
        )
        assertEquals(
            R.string.symptom_ask_severity_mind_day,
            chatQuestionRes(Symptom.WORRY, SymptomStep.SEVERITY, newChat(), pastDay = true),
        )
        assertEquals(
            R.string.symptom_ask_severity_mind,
            chatQuestionRes(Symptom.WORRY, SymptomStep.SEVERITY, newChat()),
        )
        assertEquals(R.string.symptom_ask_with, chatQuestionRes(Symptom.FEVER, SymptomStep.WITH, newChat()))
    }

    /** Another chat begun today and left: "Carry on the last chat" stands first at the opening, and only there. */
    @Test
    fun carryOnLeadsWhenAChatBegunTodayIsOpen() {
        val other = setting.copy(resumeKey = "other-chat")
        assertEquals("other-chat", (chatTurn(ChatLog(), other).reply as ChatReply.Topics).resume)
        assertNull((chatTurn(ChatLog(), setting).reply as ChatReply.Topics).resume)
        // Between threads it is not offered again: the chat being had is the one to go on with.
        assertNull((chatTurn(logOf(calmThread(Symptom.FEVER)), other).reply as ChatReply.Topics).resume)
        // What carries on: a chat not ended, begun today, whatever day it is filed under.
        val row = ChatRow("k", "2026-09-28", CHAT_NOW, CHAT_NOW, "{}")
        assertTrue(continuesLive(row, ChatLog(), CHAT_TODAY, CHAT_ZONE))
        assertFalse(continuesLive(row, ChatLog(end = ChatEnd.ALL), CHAT_TODAY, CHAT_ZONE))
        assertFalse(continuesLive(row.copy(startedAt = CHAT_NOW - 86_400_000L), ChatLog(), CHAT_TODAY, CHAT_ZONE))
    }

    /**
     * A start the chat chose from a mention in the person's words keeps "Another topic" until they
     * answer, and draws no bubble for the topic it took. (The record topics that are routed so — a pain,
     * a mood, a dose — have tests of their own that route them.)
     */
    @Test
    fun aStartChosenFromWordsKeepsAnotherTopicUntilAnswered() {
        val words = newChat().write("shivery all day")
        val routed = ChatLog(threads = listOf(ChatThread(0, words.routedTo(Symptom.FEVER), from = "FEVER")), next = 1)
            .settle()
        val turn = chatTurn(routed, setting)
        assertTrue((turn.reply as ChatReply.Choose).exit)
        // No bubble for a topic the chat took, and no pencil on it.
        val people = chatTranscript(routed, setting, live = true).filterIsInstance<ChatItem.Person>()
        assertEquals(listOf("shivery all day"), people.map { it.text })
        // Answered once, the way out is gone.
        val answered = routed.answeredLast(setting) {
            it.copy(answers = it.answers + (SymptomStep.WITH to listOf(SymptomOption.NONE))).answered(SymptomStep.WITH)
        }.settle()
        assertFalse((chatTurn(answered, setting).reply as ChatReply.Choose).exit)
        // A thread the chat began itself has none: the chest is asked about whatever else was meant.
        val chest = logOf(newChat().write("my chest hurts").say(SymptomStep.CHEST_NOW, SymptomOption.NO))
        assertEquals(ROUTE_WORDS, chest.threads.single().from)
        assertFalse(chatTurn(chest, setting).reply.exitShown)
    }

    /**
     * One record named and nothing else: the chat takes that topic up at once, at its first widget — the
     * knee's chips — with its start line, "Another topic" as the way out, no bubble for the topic it
     * took and nothing recorded until the person answers; with a symptom named beside it, chips instead.
     */
    @Test
    fun oneRecordMentionRoutesItsFirstWidgetWithAnotherTopic() {
        val knee = ChatLog().wrote("my knee hurts").settle()
        val thread = knee.threads.single()
        assertEquals(Symptom.PAIN to "PAIN:KNEE", thread.topic to thread.from)
        assertTrue((thread.report.chat?.turns?.last() as ChatTurn.Asked).routed)
        val turn = chatTurn(knee, setting)
        assertEquals(listOf(ChatLine.PAIN_START), turn.lines())
        val chips = turn.reply as ChatReply.AreaChips
        assertEquals(SymptomStep.PAIN_AREA, chips.step)
        assertTrue(chips.exitShown)
        val people = chatTranscript(knee, setting, live = true).filterIsInstance<ChatItem.Person>()
        assertEquals(listOf("my knee hurts"), people.map { it.text })
        assertTrue(ScriptPlans.wants(thread, filingContext(knee, chatKey())).isEmpty())
        // "Another topic": the words stay, the thread is never routed again, the whole menu is asked.
        val left = knee.leftRoute(thread.id).settle()
        assertTrue(left.threads.single().noRoute)
        assertNull(left.threads.single().topic)
        assertEquals(ChatLine.ASK_TOPIC_AGAIN, chatTurn(left, setting).lines().last())
        assertTrue((chatTurn(left, setting).reply as ChatReply.Topics).all)
        // A symptom named beside the record: nothing is taken up, the two are offered to choose from.
        val both = ChatLog().wrote("my knee hurts and I feel sick").settle()
        assertNull(both.threads.single().topic)
        val several = chatTurn(both, setting)
        assertEquals(ChatLine.MATCH_SEVERAL, several.lines().last())
        val offered = several.reply as ChatReply.Topics
        assertEquals(listOf(Symptom.NAUSEA, Symptom.PAIN), offered.choices.map { it.topic })
        assertEquals("PAIN:KNEE", offered.choices.last().mention?.key)
        assertTrue(offered.exit)
    }

    /**
     * A follow-up claims the mention it answers: after a calm chest thread the words
     * began, "Mark it on the pain map" stands for the chest's own pain mention — one chip, never two —
     * beside what else the words named; the pain thread it starts serves that mention for good.
     */
    @Test
    fun aFollowUpClaimsItsQueuedMention() {
        val text = "my chest and my knee hurt"
        val read = chatReader.read(listOf(ChatTurn.Wrote(text)), setting).mentions.map { it.mention.key }
        assertEquals(listOf("CHEST_PAIN", "PAIN:CHEST", "PAIN:KNEE"), read)
        val chest = ChatLog().wrote(text).settle()
            .answeredLast(setting) { it.tapped(SymptomStep.CHEST_NOW, SymptomOption.NO, null) }.settle()
            .calmToTheEnd()
        assertEquals(Symptom.CHEST_PAIN to ROUTE_WORDS, chest.threads.single().let { it.topic to it.from })
        assertEquals(ChatMove.Prompt, chatMove(chest, setting))
        val queue = chatQueue(chest, setting)
        assertEquals(listOf("PAIN:CHEST", "PAIN:KNEE"), queue.map { it.mention?.key })
        assertEquals(R.string.symptom_chat_pain_map, queue.first().labelRes)
        val prompt = chatTurn(chest, setting)
        assertEquals(ChatLine.ALSO_MENTIONED, prompt.lines().last())
        val chip = (prompt.reply as ChatReply.Topics).choices.first()
        val started = chest.choseTopic(chip, setting).settle()
        assertEquals(ROUTE_FOLLOW + "PAIN:CHEST", started.threads.last().from)
        val done = started.calmToTheEnd()
        assertEquals(ChatMove.Prompt, chatMove(done, setting))
        assertTrue(chatQueue(done, setting).none { it.mention?.key == "PAIN:CHEST" })
    }

    /**
     * A close that says to get advice now pushes nothing after it: only "Show all topics"
     * and "That is all for now", though the words named more; a close for today pushes them as usual.
     */
    @Test
    fun afterANowLineOnlyAllTopicsAndThatIsAll() {
        val words = ChatLog().wrote("my left knee hurts and I feel dizzy").settle()
        val pain = (chatTurn(words, setting).reply as ChatReply.Topics).choices.first { it.topic == Symptom.PAIN }
        val now = words.choseTopic(pain, setting).settle().calmToTheEnd(nrs = 9)
        assertTrue(SymptomAlert.URGENT_NOW in firedRules(now.threads.last().report).map { it.alert })
        assertEquals(listOf(Symptom.DIZZINESS), chatQueue(now, setting).map { it.topic })
        val prompt = chatTurn(now, setting)
        assertTrue(ChatLine.CLOSE_NOW in prompt.lines())
        assertFalse(ChatLine.ALSO_MENTIONED in prompt.lines())
        assertFalse(ChatLine.ANYTHING_ELSE in prompt.lines())
        val reply = prompt.reply as ChatReply.Topics
        assertTrue(reply.choices.isEmpty())
        assertFalse(reply.all)
        assertTrue(reply.end)
        val calm = words.choseTopic(pain, setting).settle().calmToTheEnd(nrs = 3)
        val pushed = chatTurn(calm, setting)
        assertEquals(ChatLine.ALSO_MENTIONED, pushed.lines().last())
        assertEquals(listOf(Symptom.DIZZINESS), (pushed.reply as ChatReply.Topics).choices.map { it.topic })
    }

    /** "Another topic" takes the chat's choice back for good: the words stay, the menu is asked again. */
    @Test
    fun anotherTopicNeverRoutesThatThreadAgain() {
        val proposed = ChatLog().wrote("I feel sick and dizzy").settle()
        assertTrue((chatTurn(proposed, setting).reply as ChatReply.Topics).exit)
        val left = proposed.leftRoute(0).settle()
        val thread = left.threads.single()
        assertTrue(thread.noRoute)
        assertNull(thread.topic)
        assertEquals("I feel sick and dizzy", thread.report.chat?.words())
        val again = chatTurn(left, setting)
        assertEquals(ChatLine.ASK_TOPIC_AGAIN, again.lines().last())
        assertTrue((again.reply as ChatReply.Topics).all)

        val routed = ChatLog(
            threads = listOf(ChatThread(0, newChat().write("shivery").routedTo(Symptom.FEVER), from = "FEVER")),
            next = 1,
        ).settle()
        val back = routed.leftRoute(0).settle()
        assertNull(back.threads.single().topic)
        assertNull(back.threads.single().from)
        assertEquals(listOf(ChatTurn.Wrote("shivery")), back.threads.single().report.chat?.turns)
        assertEquals(ChatLine.ASK_TOPIC_AGAIN, chatTurn(back, setting).lines().last())
    }

    /**
     * Words about the chest: its "is it there now?" first, then a chest pain thread the chat starts
     * itself, with no chip and the line that says why — before anything is recorded for the chest.
     */
    @Test
    fun chestWordsRouteChestPainBeforeAnything() {
        val written = ChatLog().wrote("my chest is tight").settle()
        val first = chatTurn(written, setting)
        assertEquals(SymptomStep.CHEST_NOW, first.asked())
        assertEquals(listOf(ChatLine.WORDS_CHECK), first.lines())
        val routed = written.answeredLast(setting) { it.tapped(SymptomStep.CHEST_NOW, SymptomOption.NO, null) }.settle()
        val thread = routed.threads.single()
        assertEquals(Symptom.CHEST_PAIN, thread.topic)
        assertEquals(ROUTE_WORDS, thread.from)
        assertTrue((thread.report.chat?.turns?.last() as ChatTurn.Asked).routed)
        val next = chatTurn(routed, setting)
        assertEquals(ChatLine.CHEST_WORDS, next.lines().first())
        // Chest pain's own "is it there now?" was answered already.
        assertEquals(SymptomStep.WITH, next.asked())
        // Its "no" called for a GP's line, said in the turn after it — the chat took the topic itself in
        // between, and that turn says nothing; the line is drawn all the same, live and as stored.
        assertEquals(listOf(SymptomAlert.DOCTOR), next.alerts())
        val answered = routed.answeredLast(setting) {
            it.copy(answers = it.answers + (SymptomStep.WITH to listOf(SymptomOption.NONE))).answered(SymptomStep.WITH)
        }.settle()
        val asked = chatTranscript(answered, setting, live = true).filterIsInstance<ChatItem.Bot>()
            .single { it.turn.asked() == SymptomStep.WITH }
        assertEquals(listOf(SymptomAlert.DOCTOR), asked.turn.alerts())

        // Written inside another topic's thread: asked there, and the chest thread follows it, its answer carried in.
        val fever = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle()
            .let {
                it.copy(threads = it.threads.map { t -> t.copy(report = t.report.wrote("and my chest hurts")) })
            }.settle()
        assertEquals(SymptomStep.CHEST_NOW, (chatMove(fever, setting) as ChatMove.Ask).step)
        var log = fever.answeredLast(setting) { it.tapped(SymptomStep.CHEST_NOW, SymptomOption.NO, null) }.settle()
        while (log.threads.size == 1) {
            val step = (chatMove(log, setting) as ChatMove.Ask).step
            val state = log.threadState(0, setting)
            log =
                log.answeredLast(setting) {
                    answer(it, state, step, chatTurn(log, setting).reply, Symptom.FEVER)
                }.settle()
        }
        val chest = log.threads.last()
        assertEquals(Symptom.CHEST_PAIN to ROUTE_WORDS, chest.topic to chest.from)
        assertEquals(listOf(SymptomOption.NO), chest.report.answers[SymptomStep.CHEST_NOW])
        assertTrue(chest.report.chat?.turns.orEmpty().all { (it as ChatTurn.Asked).routed })
        // The fever's close is said with the chest's first question: there is no prompt between them.
        val turn = chatTurn(log, setting)
        assertTrue(turn.pieces.any { it is BotPiece.Saved && it.thread == 0 })
        assertEquals(SymptomStep.WITH, turn.asked())
        assertTrue(ChatLine.CHEST_WORDS in turn.lines())
    }

    /**
     * A pain the body's words put in the chest — the ribs, a breast, the breastbone, a pressure or a
     * tingling there — brings the chest's own questions first, as words of chest pain do: "is it there
     * now?", then the chest thread the chat starts itself, before anything else is taken up (never the
     * pain map framed on the chest with no question about the chest at all).
     */
    @Test
    fun aPainTheWordsPutInTheChestIsAskedAboutFirst() {
        listOf(
            "pressure on my chest",
            "pain under my left breast",
            "my sternum hurts",
            "покалывает в груди",
            "ребра болят",
        ).forEach { words ->
            assertTrue(words, chestWordsIn(words))
            val written = ChatLog().wrote(words).settle()
            assertEquals(words, SymptomStep.CHEST_NOW, chatTurn(written, setting).asked())
            val routed = written.answeredLast(setting) {
                it.tapped(SymptomStep.CHEST_NOW, SymptomOption.NO, null)
            }.settle()
            assertEquals(words, Symptom.CHEST_PAIN to ROUTE_WORDS, routed.threads.single().let { it.topic to it.from })
        }
        // The breastfeeding words keep the chest's area out; the back is no chest.
        assertFalse(chestAreaPainIn("кормлю грудью, болит спина"))
        assertFalse(chestWordsIn("my back hurts"))
        // A chest and an arm: the chest first; then its map and the arm, from the queue.
        val both = ChatLog().wrote("tingling in my left arm and chest").settle()
            .answeredLast(setting) { it.tapped(SymptomStep.CHEST_NOW, SymptomOption.NO, null) }.settle()
        assertEquals(Symptom.CHEST_PAIN, both.threads.single().topic)
        assertEquals(listOf("PAIN:CHEST", "PAIN:ARM"), chatQueue(both.calmToTheEnd(), setting).map { it.mention?.key })
        // Written inside a knee's thread: asked about there, next, and the chest's thread follows the knee's.
        val knee = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L")
        val breast = knee.copy(
            threads = knee.threads.map { it.copy(report = it.report.wrote("and my left breast hurts too")) },
        ).settle()
        assertEquals(SymptomStep.CHEST_NOW, breast.asked())
        val closed = breast.tapOn(SymptomOption.NO).calmToTheEnd()
        assertEquals(Symptom.CHEST_PAIN to ROUTE_WORDS, closed.threads.last().let { it.topic to it.from })
    }

    /**
     * A friendly message written while the chest's question stands — a greeting, "how are you?", a glass
     * of water — holds nothing of the chest back: once "is it there now?" is answered the chat starts the
     * chest's thread from the earlier words, as it does with no such message (the friendly line never
     * stands in for the route, which would leave the rest of the chest's questions a chip to tap or not).
     */
    @Test
    fun aFriendlyMessageAfterWordsOfTheChestKeepsTheChestsThread() {
        listOf("hello", "как дела?", "выпил воды").forEach { friendly ->
            val written = ChatLog().wrote("my chest hurts").settle().wrote(friendly).settle()
            assertEquals(friendly, SymptomStep.CHEST_NOW, written.asked())
            val chest = written.tapOn(SymptomOption.NO).threads.single()
            assertEquals(friendly, Symptom.CHEST_PAIN to ROUTE_WORDS, chest.topic to chest.from)
        }
        // With no chest in the thread a friendly message still takes no record up for the person.
        assertNull(ChatLog().wrote("грустно").settle().threads.single().topic)
    }

    /**
     * A start the chat took from words keeps what its reply to those words owes: with no
     * English or Russian word read — an emoji — the line to call stands before the first question, live
     * and as stored, and after "Another topic"; words about someone else first say whose journal this is,
     * and for a child that the chat is for adults (a routed start drops none of them).
     */
    @Test
    fun aStartTakenFromWordsKeepsTheLinesTheWordsOwe() {
        val head = ChatLog().wrote("🤕").settle()
        assertEquals(Symptom.PAIN to "PAIN:HEAD", head.threads.single().let { it.topic to it.from })
        val first = chatTurn(head, setting)
        assertEquals(listOf(ChatLine.PAIN_START, ChatLine.SAFETY), first.lines())
        assertTrue(
            first.pieces.indexOf(BotPiece.Line(ChatLine.SAFETY)) < first.pieces.indexOfFirst {
                it is BotPiece.Ask
            },
        )
        val told = head.give(HEAD_CHIPS.first().code)
        val asked = chatTranscript(told, setting, live = true).filterIsInstance<ChatItem.Bot>()
            .single { it.turn.asked() == SymptomStep.PAIN_AREA }
        assertEquals(first.pieces, asked.turn.pieces)
        val left = head.leftRoute(0).settle()
        assertEquals(listOf(ChatLine.ASK_TOPIC_AGAIN, ChatLine.SAFETY), chatTurn(left, setting).lines())
        // Words read: no line to call is added.
        assertEquals(listOf(ChatLine.PAIN_START), chatTurn(ChatLog().wrote("my knee hurts").settle(), setting).lines())
        // Someone else's pain, a child's: whose journal this is, that the chat is for adults, then the start.
        val son = ChatLog().wrote("у сына болит колено").settle()
        assertEquals(Symptom.PAIN, son.threads.single().topic)
        assertEquals(
            listOf(ChatLine.SOMEONE_ELSE, ChatLine.ADULTS, ChatLine.PAIN_START),
            chatTurn(son, setting).lines(),
        )
    }

    /**
     * Words of suicide: where help is and "are you safe now?" first; "no such thoughts" lets the words be read for
     * topics.
     */
    @Test
    fun crisisWordsProposeNoRecordUntilNoSuchThoughts() {
        val words = ChatLog().wrote("I want to die, I feel sick").settle()
        val first = chatTurn(words, setting)
        assertEquals(ChatLine.CRISIS_SUPPORT, first.lines().first())
        assertEquals(SymptomStep.CRISIS_NOW, first.asked())
        assertTrue(first.urgent())

        val no = words.answeredLast(setting) {
            it.tapped(SymptomStep.CRISIS_NOW, SymptomOption.NO_SUCH_THOUGHTS, null)
        }.settle()
        val match = chatTurn(no, setting)
        assertEquals(listOf(Symptom.NAUSEA), (match.reply as ChatReply.Topics).choices.map { it.topic })

        // Any other answer closes the thread, and the prompt offers to talk about it.
        val safe = words.answeredLast(setting) {
            it.tapped(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW, null)
        }.settle()
        assertTrue(safe.threads.single().ended)
        assertEquals(ChatMove.Prompt, chatMove(safe, setting))
        val prompt = chatTurn(safe, setting)
        assertEquals(ChatLine.CRISIS_TALK, prompt.lines().last())
        val talk = prompt.reply as ChatReply.Topics
        assertEquals(listOf(Symptom.LOW_MOOD), talk.choices.map { it.topic })
        assertEquals(R.string.symptom_chat_talk_about_it, talk.choices.single().labelRes)
        // The queue pushes nothing then: the nausea the words named waits behind "Show all topics".
        assertFalse(talk.all)
    }

    /**
     * The queue: the chest first, then the body in the menu's order, then feelings, whatever order the
     * words named them in; at most four; nothing a thread already serves.
     */
    @Test
    fun theQueueRanksChestBodyFeelingsPainMoodDoseCourseAndCapsAtFour() {
        val words = "I am worried and feverish, I feel sick and dizzy, my chest hurts and I keep vomiting"
        // As it stands between the fever's close and the chest thread the words bring next.
        val fever = ChatLog(threads = listOf(ChatThread(0, calmThread(Symptom.FEVER).wrote(words))), next = 1)
        assertEquals(
            listOf(Symptom.CHEST_PAIN, Symptom.VOMITING, Symptom.NAUSEA, Symptom.DIZZINESS),
            chatQueue(fever, setting).map { it.topic },
        )
        // The fever is served by its own thread; once chest pain and vomiting have threads, the rest come up.
        val more = fever.plus(calmThread(Symptom.CHEST_PAIN)).plus(calmThread(Symptom.VOMITING))
        assertEquals(
            listOf(Symptom.NAUSEA, Symptom.DIZZINESS, Symptom.WORRY),
            chatQueue(more, setting).map {
                it.topic
            },
        )
        val prompt = chatTurn(more, setting)
        assertEquals(ChatLine.ALSO_MENTIONED, prompt.lines().last())
        val reply = prompt.reply as ChatReply.Topics
        assertFalse(reply.all)
        assertTrue(reply.end)
        assertEquals(TopicMention(Symptom.NAUSEA), reply.choices.first().mention)
    }

    /**
     * After a chest thread the person's words began, closed calm: "Mark it on the pain map", which
     * starts its thread itself. (It claims a queued pain mention of the chest: the pain thread's own
     * tests hold that.)
     */
    @Test
    fun aChestThreadFromWordsOffersThePainMapAfterIt() {
        val chest = logOf(newChat().write("my chest hurts").say(SymptomStep.CHEST_NOW, SymptomOption.NO))
        var log = chest
        while (!log.threads.last().ended) {
            val step = (chatMove(log, setting) as ChatMove.Ask).step
            val state = log.threadState(0, setting)
            log =
                log.answeredLast(setting) {
                    answer(it, state, step, chatTurn(log, setting).reply, Symptom.CHEST_PAIN)
                }.settle()
        }
        val follow = SymptomScript.followUps(log.threadState(0, setting))
        assertEquals(listOf(TopicMention(Symptom.PAIN, "CHEST")), follow.map { it.mention })
        assertEquals(R.string.symptom_chat_pain_map, follow.single().labelRes)
        // Tapped, it starts a pain thread from it, with no bubble; a queued mention of that key is then served.
        val started = log.choseTopic(follow.single(), setting)
        val thread = started.threads.last()
        assertEquals(ROUTE_FOLLOW + "PAIN:CHEST", thread.from)
        assertTrue((thread.report.chat?.turns?.single() as ChatTurn.Asked).routed)
        // A chest thread chosen from the menu, or one that ended in an emergency, has no such follow-up.
        val menu = ChatLog(threads = listOf(ChatThread(0, calmThread(Symptom.CHEST_PAIN))), next = 1).settle()
        assertTrue(SymptomScript.followUps(menu.threadState(0, setting)).isEmpty())
    }

    /** After an answer about suicide: the offer to talk, with no queue pushed, until a low mood talk has run. */
    @Test
    fun afterACrisisAnswerTheTalkIsOffered() {
        val worry = driveChat(Symptom.WORRY) { step, options ->
            when (step) {
                SymptomStep.COPING -> listOf(SymptomOption.STRUGGLING)
                SymptomStep.CRISIS -> listOf(SymptomOption.CRISIS_SAFE)
                else -> calm(options)
            }
        }.second
        val log = logOf(worry)
        val prompt = chatTurn(log, setting)
        assertEquals(ChatLine.CRISIS_TALK, prompt.lines().last())
        val talk = prompt.reply as ChatReply.Topics
        assertEquals(listOf(Symptom.LOW_MOOD), talk.choices.map { it.topic })
        assertTrue(talk.end)
        // A low mood talk run after it: the offer is not made again.
        val talked = log.plus(calmThread(Symptom.LOW_MOOD))
        val later = chatTurn(talked, setting)
        assertEquals(ChatLine.CRISIS_TALK, later.lines().last())
        assertTrue((later.reply as ChatReply.Topics).choices.isEmpty())
        // A calm chat: "anything else?" and the whole menu, the end beside it.
        val calm = chatTurn(logOf(calmThread(Symptom.FEVER)), setting)
        assertEquals(ChatLine.ANYTHING_ELSE, calm.lines().last())
        val menu = calm.reply as ChatReply.Topics
        assertTrue(menu.all && menu.end)
    }

    /**
     * "That is all for now": thanks, unless an answer about suicide was anything but "no" — then what to do if it gets
     * worse.
     */
    @Test
    fun thatIsAllEndsWithThanksOrCloseCrisis() {
        val calm = logOf(calmThread(Symptom.FEVER)).endedByPerson().settle()
        assertEquals(ChatMove.Over(ChatEnd.ALL), chatMove(calm, setting))
        assertEquals(listOf(ChatLine.THANKS), chatTurn(calm, setting).lines())
        assertEquals(ChatReply.End, chatTurn(calm, setting).reply)
        val items = chatTranscript(calm, setting, live = true)
        val end = items.filterIsInstance<ChatItem.Person>().single { it.end }
        assertEquals(0, end.thread)
        assertEquals(ChatLine.THANKS, (items.last() as ChatItem.Bot).turn.lines().single())
        val crisis = driveChat(Symptom.LOW_MOOD) { step, options ->
            if (step == SymptomStep.CRISIS) listOf(SymptomOption.RATHER_NOT_SAY) else calm(options)
        }.second
        val over = logOf(crisis).endedByPerson().settle()
        assertEquals(listOf(ChatLine.CLOSE_CRISIS), chatTurn(over, setting).lines())
        // The pencil on "That is all for now" takes the end back.
        assertEquals(ChatMove.Prompt, chatMove(over.unended().settle(), setting))
    }

    /** A line to call emergency services in any thread ends the chat there: its close, no prompt, no offers. */
    @Test
    fun anEmergencyStopsTheChatInAnyThread() {
        val first = logOf(calmThread(Symptom.FEVER))
        val blood = first.choseTopic(TopicChoice(Symptom.VOMITING), setting).settle()
            .answeredLast(setting) { it.tapped(SymptomStep.BLOOD, SymptomOption.YES, null) }.settle()
        assertEquals(ChatEnd.STOP, blood.end)
        assertEquals(ChatMove.Over(ChatEnd.STOP), chatMove(blood, setting))
        val turn = chatTurn(blood, setting)
        assertEquals(SymptomUrgency.EMERGENCY, turn.alerts().single().urgency)
        assertEquals(ChatLine.CLOSE_EMERGENCY, turn.lines().first())
        assertTrue(turn.offers().isEmpty())
        assertEquals(ChatReply.End, turn.reply)
        // Nothing more begins once it has stopped.
        assertEquals(blood, blood.wrote("hello"))
        assertEquals(blood, blood.choseTopic(TopicChoice(Symptom.FEVER), setting))
    }

    /**
     * A symptom topic leaves the menu once its thread ran; chest pain stays, as a chest marked later is asked about
     * again.
     */
    @Test
    fun chestPainIsNotOncePerChatAndSymptomsAre() {
        val log = logOf(calmThread(Symptom.CHEST_PAIN)).plus(calmThread(Symptom.FEVER))
        val menu = chatMenu(log, setting).map { it.topic }
        assertFalse(Symptom.FEVER in menu)
        assertTrue(Symptom.CHEST_PAIN in menu)
        assertTrue(Symptom.NAUSEA in menu)
    }

    /** A new thread only adds to the transcript; an answer only adds to its thread: every item keeps its place. */
    @Test
    fun theTranscriptIsAppendOnlyAcrossThreads() {
        val one = logOf(calmThread(Symptom.FEVER))
        val two = one.choseTopic(TopicChoice(Symptom.NAUSEA), setting).settle()
        val three = two.answeredLast(setting) {
            it.copy(answers = it.answers + (SymptomStep.WITH to listOf(SymptomOption.NONE))).answered(SymptomStep.WITH)
        }.settle()
        val before = chatTranscript(one, setting, live = true).dropLast(1)
        val middle = chatTranscript(two, setting, live = true)
        val after = chatTranscript(three, setting, live = true)
        assertEquals(before, middle.take(before.size))
        assertEquals(middle.dropLast(1), after.take(middle.size - 1))
        // The prompt the second thread answered stands in front of its topic, with the first thread's close in it.
        val prompt = middle[before.size] as ChatItem.Bot
        assertTrue(prompt.turn.pieces.any { it is BotPiece.Saved && it.thread == 0 })
        assertEquals(ChatLine.ANYTHING_ELSE, prompt.turn.lines().last())
    }

    /** Words that matched nothing, twice in a row: the second time the chat says plainly a chip may be easier. */
    @Test
    fun noMatchTwiceSaysNoMatchAgain() {
        val once = ChatLog().wrote("zzz").settle()
        assertEquals(listOf(ChatLine.NO_MATCH, ChatLine.SAFETY), chatTurn(once, setting).lines())
        val twice = once.wrote("qqq").settle()
        assertEquals(listOf(ChatLine.NO_MATCH_AGAIN, ChatLine.SAFETY), chatTurn(twice, setting).lines())
    }

    /** With no English or Russian word read — a medicine's name, a number, an emoji — the line to call stays. */
    @Test
    fun safetyStaysWhenNoWordKeyMatched() {
        listOf("ibuprofen", "7").forEach { words ->
            val turn = chatTurn(ChatLog().wrote(words).settle(), setting)
            assertTrue(words, ChatLine.SAFETY in turn.lines())
            // The whole menu stood once, at the opening: after words it is a tap away.
            assertEquals(words, ChatReply.Topics(emptyList(), all = false), turn.reply)
        }
        // An emoji proposes its topics (😢 is the low mood's talk and the mood, which the mood
        // package offers as "Note my mood" or "Talk about it") — so its chips stand where the whole menu
        // stood; it is still no word read, and the line to call stays beside them.
        val sad = chatTurn(ChatLog().wrote("😢").settle(), setting)
        assertTrue(ChatLine.SAFETY in sad.lines())
        assertEquals(listOf(Symptom.MOOD, Symptom.LOW_MOOD), (sad.reply as ChatReply.Topics).choices.map { it.topic })
        // Words read: no line to call stands beside the chips they named.
        val read = chatTurn(ChatLog().wrote("I feel sick").settle(), setting)
        assertFalse(ChatLine.SAFETY in read.lines())
    }
}
