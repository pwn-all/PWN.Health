package com.pwnall.health

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pain thread told through the router, as the page tells it: from the person's words to
 * the record's card; the head taken by the clock; a map opened again; the same pain; this chat's own
 * earlier pain; a rewind, a topic taken back, an emergency and midnight, each against the filer.
 */
class PainChatFlowTest {
    private val setting = chatSetting()

    private fun turn(log: ChatLog, setting: ChatSetting = this.setting) = chatTurn(log, setting)

    private fun filed(store: MemoryChatStore, log: ChatLog, key: String, now: Long = CHAT_NOW): FilingResult =
        persist(ChatFiling(store, key), log, key, now)

    /** «Болит правое колено»: the knee's chip, the knee's questions, one pain row, its card and the close. */
    @Test
    fun aKneeFromWordsToTheSavedCard() {
        var log = ChatLog().wrote("my right knee hurts, it started yesterday").settle()
        val first = turn(log)
        assertEquals(listOf(ChatLine.PAIN_START), first.lines())
        assertEquals(SymptomStep.PAIN_AREA, first.asked())
        val chips = first.reply as ChatReply.AreaChips
        assertEquals(listOf(listOf("KNEE:R")), chips.choices.map { c -> c.areas.map { it.code } })
        assertEquals(PainFrame(PainView.FRONT, "KNEE"), chips.frame)
        assertTrue(chips.exit)
        log = log.give("KNEE:R")
        assertEquals(listOf(ChatLine.ACK_1), turn(log).lines())
        assertEquals(SymptomStep.PAIN_INJURY, turn(log).asked())
        log = log.tapOn(SymptomOption.NO)
        val signs = turn(log).reply as ChatReply.Choose
        assertEquals(SymptomStep.PAIN_SIGNS, signs.step)
        assertEquals(R.string.pain_chat_sign_no_weight_knee, signs.labels[SymptomOption.NO_WEIGHT])
        assertFalse(signs.exit)
        log = log.tick(SymptomOption.NONE)
        assertEquals(listOf(ChatLine.ACK_1), turn(log).lines())
        assertEquals(SymptomStep.PAIN_TYPES, turn(log).asked())
        assertEquals(2, (turn(log).reply as ChatReply.Choose).limit)
        log = log.tick(SymptomOption.PAIN_ACHING)
        val onset = turn(log).reply as ChatReply.Choose
        assertEquals(SymptomStep.ONSET, onset.step)
        // "It started yesterday" marks the chip, and chooses nothing.
        assertEquals(setOf(SymptomOption.STARTED_YESTERDAY), onset.marked)
        assertNull(log.threads.single().report.answers[SymptomStep.ONSET])
        log = log.tapOn(SymptomOption.STARTED_YESTERDAY)
        assertEquals(ChatReply.PainScale(SymptomStep.PAIN_NRS, start = null, rest = null), turn(log).reply)
        log = log.give("6")
        assertEquals(SymptomStep.PAIN_NOTICED, turn(log).asked())
        log = log.tapOn(SymptomOption.NOTICED_AFTER_WORKOUT).tapOn(SymptomOption.STILL_HURTS)
        assertEquals(SymptomStep.PAIN_PAINKILLER, turn(log).asked())
        log = log.tapOn(SymptomOption.YES)
        assertEquals(ChatMove.Prompt, chatMove(log, setting))
        val close = turn(log)
        assertEquals(BotPiece.Saved(0), close.pieces.first())
        assertEquals(BotPiece.Line(ChatLine.CLOSE_BODY, R.string.pain_source_knee), close.pieces[1])
        assertTrue(close.offers().isEmpty())
        val dose = TopicMention(Symptom.DOSE, PAINKILLER_DETAIL)
        val took = listOf(TopicChoice(Symptom.DOSE, dose, R.string.pain_chat_follow_dose))
        // The painkiller taken brings "Note what you took", offered
        // under "anything else?", as nothing the person wrote named it.
        assertEquals(ChatLine.ANYTHING_ELSE to took, close.lines().last() to (close.reply as ChatReply.Topics).choices)
        assertEquals(took, PainScript.followUps(log.threadState(0, setting)))
        val store = MemoryChatStore()
        val key = chatKey()
        val result = filed(store, log, key)
        val row = store.painRows().single()
        assertEquals(KIND_PAIN, linkOf(result).kind)
        assertEquals(row.occurredAt, linkOf(result).at)
        assertEquals("Pain", row.title)
        val report = decodePainPayload(row.measurementPayloadJson)
        assertEquals(listOf(RegionSide.parse("KNEE:R")), report.regions)
        assertEquals(listOf(PainType.ACHING), report.types)
        assertEquals(6, report.nrs)
        assertEquals(PainOnset.YESTERDAY, report.onset)
        assertEquals(PainNoticed.AFTER_WORKOUT, report.noticed)
        assertTrue(report.painkiller)
        assertNull(row.durationMinutes)
        assertEquals(listOf(0 to linkOf(result)), result.firsts)
    }

    /**
     * "Headache": the head's chips with the canvas folded, taken by the clock three seconds after the last tick.
     */
    @Test
    fun aHeadacheIsTakenByTheClockWithTheCanvasFolded() {
        val log = ChatLog().wrote("bad headache since this morning").settle()
        val first = turn(log)
        val head = first.reply as ChatReply.BodyMap
        assertTrue(head.head)
        val question = PainScript.question(log.threadState(0, setting), SymptomStep.PAIN_AREA)
        assertEquals(R.string.pain_chat_ask_head, question)
        // The area question is a tap, so the conversation's own clock does not wait on it…
        assertNull(waitingStep(log, setting))
        // …but the head's chips with the canvas folded are taken by it, and unfolding the canvas stops that.
        val map = testMap(0)
        assertTrue(mapTakenByClock(map, head))
        map.canvas = true
        assertFalse(mapTakenByClock(map, head))
        assertFalse(mapTakenByClock(testMap(0), head.copy(head = false)))
        // The ticks wait as a value, and the clock takes them while the question is still the area's.
        val ticked = log.updateLast { it.valued(SymptomStep.PAIN_AREA, "TEMPLE:L,TEMPLE:R") }
        assertEquals(ticked.threads.single().report.asTold().values[SymptomStep.PAIN_AREA], null)
        val taken = ticked.takenLast(SymptomStep.PAIN_AREA, setting).settle()
        assertEquals("TEMPLE:L,TEMPLE:R", taken.threads.single().report.forSave().values[SymptomStep.PAIN_AREA])
        // The head asks no injury question: its list of red flags is next, the sudden worst headache first.
        val signs = turn(taken).reply as ChatReply.Choose
        assertEquals(SymptomStep.PAIN_SIGNS, signs.step)
        assertEquals(SymptomOption.SUDDEN_SEVERE, signs.options.first())
    }

    /**
     * The map opened again — after a rewind, or from a painting not yet sent — has what was struck off and added.
     */
    @Test
    fun aReopenedMapKeepsRemovalsAndAdditions() {
        val knee = dabOn("KNEE:L")
        val shin = dabOn("SHIN:L")
        var log = ChatLog().choseTopic(TopicChoice(Symptom.PAIN), setting).settle()
        val whole = ChatReply.BodyMap(SymptomStep.PAIN_AREA, PainFrame(PainView.FRONT, null), false, emptyList())
        assertEquals(whole, turn(log).reply)
        log = log.paint(listOf(knee, shin), added = listOf("HEAD_BACK:C"), removed = listOf("SHIN:L"))
        assertEquals("HEAD_BACK:C,KNEE:L", log.threads.single().report.values[SymptomStep.PAIN_AREA])
        val at = log.threads.single().report.chat?.turns.orEmpty().indexOfFirst {
            (it as? ChatTurn.Asked)?.step == SymptomStep.PAIN_AREA
        }
        val back = log.rewoundLog(0, at).settle()
        assertEquals(SymptomStep.PAIN_AREA, back.asked())
        val start = mapStart(back.threads.single(), back.threadState(0, setting), turn(back).reply)
        assertEquals(listOf(knee, shin), start.strokes)
        assertEquals(listOf("HEAD_BACK:C"), start.added)
        assertEquals(listOf("SHIN:L"), start.removed)
        assertEquals("HEAD_BACK:C,KNEE:L", chatMapCodes(start.strokes, start.added, start.removed))
        // A painting not yet sent, kept with the chat as the page stopped, is where the map opens.
        val draft = MapDraft(listOf(shin), listOf("JAW:R"), emptyList())
        val kept = back.withDraft(draft)
        val reopened = mapStart(kept.threads.single(), kept.threadState(0, setting), turn(kept).reply)
        assertEquals(MapStart(listOf(shin), listOf("JAW:R"), emptyList(), PainView.FRONT, null), reopened)
    }

    /**
     * The same pain as one of the month: its facts frozen, the page's questions left out, its words and noticed
     * carried.
     */
    @Test
    fun theSamePainFreezesItsFactsAndCarriesWhatThePageCarries() {
        val earlier = earlierKnee()
        val known = chatSetting(env = ChatEnv(painMonth = listOf(earlier)))
        var log = ChatLog().wrote("my right knee hurts again").settle(known).give("KNEE:R", known)
        val facts = log.threads.single().report.meta
        assertEquals(earlier.occurredAt.toString(), facts[PAIN_EARLIER])
        assertNull(facts[PAIN_HERE])
        log = log.tapOn(SymptomOption.NO, known).tick(SymptomOption.NONE, setting = known)
        val ask = turn(log, known)
        assertEquals(SymptomStep.PAIN_SAME, ask.asked())
        assertEquals(PainSameFound(earlier.occurredAt, here = false, areas = listOf("KNEE:R")), ask.pieces[1])
        assertEquals(BotPiece.Line(ChatLine.ACK_1), ask.pieces[0])
        // "Again" marks "the same pain", and chooses nothing.
        val sameReply = ask.reply as ChatReply.Choose
        assertEquals(setOf(SymptomOption.PAIN_SAME_YES), sameReply.marked)
        assertNull(sameReply.openRef)
        log = log.tapOn(SymptomOption.PAIN_SAME_YES, known)
        val same = log.threads.single().report.meta
        assertEquals(earlier.occurredAt.toString(), same[PAIN_SAME_FIRST])
        assertEquals("ACHING,STIFF", same[PAIN_SAME_TYPES])
        assertEquals("AFTER_WORKOUT", same[PAIN_SAME_NOTICED])
        assertEquals("6", same[PAIN_SAME_NRS])
        assertEquals("10", same[PAIN_SAME_DAYS])
        assertEquals(SymptomStep.PAIN_CHANGED, log.asked(known))
        log = log.tapOn(SymptomOption.FEELS_SAME, known)
        val carried = turn(log, known)
        assertTrue(PainCarried(listOf(PainType.ACHING, PainType.STIFF), PainNoticed.AFTER_WORKOUT) in carried.pieces)
        // No words, no start and no first-noticed asked: the ruler starts on the earlier rating, not set.
        assertEquals(ChatReply.PainScale(SymptomStep.PAIN_NRS, start = 6, rest = null), carried.reply)
        log = log.give("5", known).tapOn(SymptomOption.STILL_HURTS, known).tapOn(SymptomOption.NO, known)
        val asked = log.threads.single().report.chat?.askedSteps().orEmpty()
        assertFalse(SymptomStep.PAIN_TYPES in asked)
        assertFalse(SymptomStep.ONSET in asked)
        assertFalse(SymptomStep.PAIN_NOTICED in asked)
        val report = painRecordReport(log.threads.single().report.forSave(), CHAT_NOW)
        assertEquals(earlier.occurredAt, report.same)
        assertNull(report.onset)
        assertEquals(listOf(PainType.ACHING, PainType.STIFF), report.types)
        assertEquals(PainNoticed.AFTER_WORKOUT, report.noticed)
        assertEquals(5, report.nrs)
    }

    /**
     * A chat of today gone on with past midnight keeps the day it was begun on, while the row it files
     * stands on the new date: the same pain has gone on until then, a day more than the chat's own day says.
     */
    @Test
    fun theSamePainPastMidnightCountsItsDaysToTheNewDate() {
        val after = todayAt(5, CHAT_TODAY.plusDays(1))
        val known = chatSetting(env = ChatEnv(painMonth = listOf(earlierKnee()))).copy(now = after, chatMoment = after)
        val log = ChatLog().wrote("my right knee hurts again").settle(known).give("KNEE:R", known)
            .tapOn(SymptomOption.NO, known).tick(SymptomOption.NONE, setting = known)
            .tapOn(SymptomOption.PAIN_SAME_YES, known)
        assertEquals("11", log.threads.single().report.meta[PAIN_SAME_DAYS])
    }

    /** A right knee that hurt ten days ago: aching and stiff, a 6, noticed after a workout. */
    private fun earlierKnee(): JournalEntry = painEntry(
        "Pain",
        PainReport(
            regions = listOfNotNull(RegionSide.parse("KNEE:R")),
            types = listOf(PainType.ACHING, PainType.STIFF),
            nrs = 6,
            onset = PainOnset.DAYS_2_3,
            noticed = PainNoticed.AFTER_WORKOUT,
        ),
        CHAT_TODAY.minusDays(10),
        CHAT_TODAY,
        CHAT_NOW,
    ).copy(id = 7)

    /** The same pain, changed: its words are asked again, the earlier ones standing ticked with "Keep: …". */
    @Test
    fun aChangedSamePainAsksItsWordsAgainWithTheEarlierOnesTicked() {
        val known = chatSetting(env = ChatEnv(painMonth = listOf(earlierKnee())))
        val changed = ChatLog().wrote("my right knee hurts again").settle(known).give("KNEE:R", known)
            .tapOn(SymptomOption.NO, known).tick(SymptomOption.NONE, setting = known)
            .tapOn(SymptomOption.PAIN_SAME_YES, known).tapOn(SymptomOption.FEELS_CHANGED, known)
        val words = turn(changed, known)
        val carried = words.pieces.filterIsInstance<PainCarried>().single()
        assertEquals(PainCarried(emptyList(), PainNoticed.AFTER_WORKOUT), carried)
        val types = words.reply as ChatReply.Choose
        assertEquals(SymptomStep.PAIN_TYPES, types.step)
        assertEquals(listOf(SymptomOption.PAIN_ACHING, SymptomOption.PAIN_STIFF), types.preset)
        assertNotNull(types.keep)
    }

    /**
     * A second pain of the knee in one chat: "already noted above", with Open beside the question and nothing pointing
     * at a pencil.
     */
    @Test
    fun thisChatsEarlierPainSaysNotedAboveWithOpenAndNoPencil() {
        val store = MemoryChatStore()
        val key = chatKey()
        var log = ChatLog().wrote("my right knee hurts").settle().give("KNEE:R").calmToTheEnd()
        val result = filed(store, log, key)
        log = log.withLinks(result)
        val row = store.painRows().single()
        val known = chatSetting(env = ChatEnv(painMonth = listOf(row)))
        log = log.choseTopic(TopicChoice(Symptom.PAIN), known).settle(known)
        log = log.paint(listOf(dabOn("KNEE:R")), setting = known)
        assertEquals("1", log.threads.last().report.meta[PAIN_HERE])
        log = log.calmUntil(SymptomStep.PAIN_SAME, known)
        val ask = turn(log, known)
        val found = ask.pieces.filterIsInstance<PainSameFound>().single()
        assertEquals(PainSameFound(row.occurredAt, here = true, areas = listOf("KNEE:R")), found)
        assertEquals(ChatRecordRef(KIND_PAIN, row.occurredAt, null), (ask.reply as ChatReply.Choose).openRef)
        // "I am sorry it hurts" is said in the chat's first pain thread only.
        val areaTurns = chatTranscript(log, known, live = true).filterIsInstance<ChatItem.Bot>()
            .filter { it.turn.asked() == SymptomStep.PAIN_AREA }
        assertEquals(listOf(true, false), areaTurns.map { ChatLine.PAIN_START in it.turn.lines() })
    }

    /** An answer taken back and given again rewrites the same row: its id, its day and its moment. */
    @Test
    fun aRewindRewritesTheSameRecord() {
        val store = MemoryChatStore()
        val key = chatKey()
        var log = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L").calmToTheEnd()
        log = log.withLinks(filed(store, log, key))
        val first = store.painRows().single()
        val nrsAt = log.threads.single().report.chat?.turns.orEmpty().indexOfFirst {
            (it as? ChatTurn.Asked)?.step == SymptomStep.PAIN_NRS
        }
        log = log.rewoundLog(0, nrsAt).settle()
        assertEquals(SymptomStep.PAIN_NRS, log.asked())
        // While the answer is being changed, the row stands as it was, and nothing says "Saved".
        val waiting = filed(store, log, key, CHAT_NOW + 60_000)
        assertEquals(first, store.painRows().single())
        assertTrue(rewrittenBy(log.threads.single(), waiting.links.getValue(0)).isEmpty())
        log = log.withLinks(waiting).give("4").calmToTheEnd()
        val again = filed(store, log, key, CHAT_NOW + 120_000)
        // The page flashes "Saved" for the row written again.
        assertEquals(listOf(first.occurredAt), rewrittenBy(log.threads.single(), again.links.getValue(0)).map { it.at })
        val rewritten = store.painRows().single()
        assertEquals(first.id, rewritten.id)
        assertEquals(first.occurredAt, rewritten.occurredAt)
        assertEquals(first.day, rewritten.day)
        assertEquals(4, decodePainPayload(rewritten.measurementPayloadJson).nrs)
    }

    /**
     * The area taken back with the pencil: the entry stands as it was while the area is told
     * again — a leave meanwhile loses nothing — and is rewritten under its id and moment once the thread
     * is complete again; a red flag that stops the chat on the way leaves it standing too, as the
     * area's rewind deletes nothing.
     */
    @Test
    fun aRewoundAreaKeepsTheRecordUntilTheThreadIsCompleteAgain() {
        val store = MemoryChatStore()
        val key = chatKey()
        val knee = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L").calmToTheEnd()
        val log = knee.withLinks(filed(store, knee, key))
        val first = store.painRows().single()
        val areaAt = log.threads.single().report.chat?.turns.orEmpty().indexOfFirst {
            (it as? ChatTurn.Asked)?.step == SymptomStep.PAIN_AREA
        }
        val rewound = log.rewoundLog(0, areaAt).settle()
        assertEquals(SymptomStep.PAIN_AREA, rewound.asked())
        val waiting = rewound.withLinks(filed(store, rewound, key, CHAT_NOW + 60_000))
        assertEquals(listOf(first), store.painRows())
        assertEquals(LinkState.FILED, waiting.threads.single().filed.single().state)
        // Told again, on the other side: the same row at the same moment, on the right knee.
        val again = waiting.give("KNEE:R").calmToTheEnd()
        filed(store, again, key, CHAT_NOW + 120_000)
        val rewritten = store.painRows().single()
        assertEquals(first.id to first.occurredAt, rewritten.id to rewritten.occurredAt)
        assertEquals(listOf(RegionSide.parse("KNEE:R")), decodePainPayload(rewritten.measurementPayloadJson).regions)
        // A call to emergency services after the area is told again: the chat stops, the entry stands.
        val other = MemoryChatStore()
        val otherKey = chatKey()
        val told = knee.withLinks(filed(other, knee, otherKey))
        val kept = other.painRows().single()
        val stopped = told.rewoundLog(0, areaAt).settle().give("KNEE:L").tapOn(SymptomOption.NO)
            .tick(SymptomOption.SUDDEN_SEVERE)
        assertEquals(ChatEnd.STOP, stopped.end)
        assertNull(filed(other, stopped, otherKey, CHAT_NOW + 60_000).newer)
        assertEquals(kept, other.painRows().single())
    }

    /**
     * The close of a thread whose most urgent line is "get advice now" says not to wait;
     * one whose line is for today says today.
     */
    @Test
    fun theCloseSaysNowForAnAdviceNowLine() {
        val knee = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L").calmToTheEnd(nrs = 9)
        assertTrue(SymptomAlert.URGENT_NOW in firedRules(knee.threads.single().report).map { it.alert })
        val now = turn(knee)
        assertEquals(BotPiece.Saved(0), now.pieces.first())
        assertTrue(ChatLine.CLOSE_NOW in now.lines())
        assertFalse(ChatLine.CLOSE_TODAY in now.lines())
        val shoulder = ChatLog().choseTopic(TopicChoice(Symptom.PAIN), setting).settle()
            .paint(listOf(dabOn("SHOULDER:L"))).calmToTheEnd(nrs = 9)
        assertEquals(SymptomUrgency.SAME_DAY, chatUrgency(shoulder.threads.single().report))
        val today = turn(shoulder)
        assertTrue(ChatLine.CLOSE_TODAY in today.lines())
        assertFalse(ChatLine.CLOSE_NOW in today.lines())
    }

    /**
     * What to do now, at a thread's close, reads every answer about suicide in the chat, as the chat's
     * end does: after one in an earlier thread, a pain or a symptom for today closes with the crisis's
     * line and offers no visit, and a pain that stops the chat with the line for the mind too: no
     * thread reads its own answers only.
     */
    @Test
    fun aCloseAfterACrisisAnswerElsewhereSaysTheMindsLines() {
        val low = driveChat(Symptom.LOW_MOOD) { step, options ->
            if (step == SymptomStep.CRISIS) listOf(SymptomOption.RATHER_NOT_SAY) else calm(options)
        }.second
        val crisis = ChatLog(threads = listOf(ChatThread(0, low)), next = 1).settle()
        assertTrue(crisis.crisisInChat())
        val shoulder = crisis.choseTopic(TopicChoice(Symptom.PAIN), setting).settle()
            .paint(listOf(dabOn("SHOULDER:L"))).calmToTheEnd(nrs = 9)
        assertEquals(SymptomUrgency.SAME_DAY, chatUrgency(shoulder.threads.last().report))
        val today = closeOf(shoulder, shoulder.threads.lastIndex, setting)
        assertTrue(BotPiece.Line(ChatLine.CLOSE_CRISIS) in today)
        assertFalse(BotPiece.Line(ChatLine.CLOSE_TODAY) in today)
        assertTrue(today.none { it is BotPiece.Offer })
        val stopped = crisis.choseTopic(TopicChoice(Symptom.PAIN), setting).settle()
            .paint(listOf(dabOn("KNEE:L"))).tapOn(SymptomOption.NO).tick(SymptomOption.SUDDEN_SEVERE)
        assertEquals(ChatEnd.STOP, stopped.end)
        assertEquals(
            listOf(ChatLine.CLOSE_EMERGENCY, ChatLine.CLOSE_EMERGENCY_MIND),
            chatTurn(stopped, setting).lines(),
        )
        // A symptom's close the same: being sick and unable to keep fluids down is for today.
        var vomit = crisis.choseTopic(TopicChoice(Symptom.VOMITING), setting).settle()
        repeat(20) {
            val move = chatMove(vomit, setting) as? ChatMove.Ask ?: return@repeat
            val state = vomit.threadState(vomit.threads.lastIndex, setting)
            vomit = vomit.answeredLast(setting) {
                answer(it, state, move.step, chatTurn(vomit, setting).reply, Symptom.VOMITING) { step, options ->
                    if (step == SymptomStep.WITH) listOf(SymptomOption.CANT_KEEP_FLUIDS) else calm(options)
                }
            }.settle()
        }
        assertEquals(SymptomUrgency.SAME_DAY, chatUrgency(vomit.threads.last().report))
        val symptom = closeOf(vomit, vomit.threads.lastIndex, setting).filterIsInstance<BotPiece.Line>().map { it.line }
        assertTrue(ChatLine.CLOSE_CRISIS in symptom)
        assertFalse(ChatLine.CLOSE_TODAY in symptom)
    }

    /** The topic taken back: its record goes in the same act; with no words left the thread goes too. */
    @Test
    fun aTopicRewindTakesTheRecordBack() {
        val store = MemoryChatStore()
        val key = chatKey()
        val fever = driveLog(listOf(ThreadPlan(Symptom.FEVER))).unended()
        val chosen = fever.choseTopic(TopicChoice(Symptom.PAIN), setting).settle()
        var log = chosen.paint(listOf(dabOn("KNEE:L"))).calmToTheEnd()
        log = log.withLinks(filed(store, log, key))
        assertEquals(1, store.painRows().size)
        val pain = log.threads.last()
        assertEquals(ChatTurn.Asked(SymptomStep.SYMPTOM), pain.report.chat?.turns?.first())
        log = log.rewoundLog(pain.id, 0).settle()
        filed(store, log, key, CHAT_NOW + 1_000)
        assertTrue(store.painRows().isEmpty())
        assertEquals(listOf(Symptom.FEVER), stored(store, key).threads.map { it.topic })
        assertEquals(ChatMove.Prompt, chatMove(log, setting))
    }

    /** After a call to emergency services nothing is filed: the thread stops, the chat ends there. */
    @Test
    fun nothingIsWrittenAfterAnEmergency() {
        val store = MemoryChatStore()
        val key = chatKey()
        val log = ChatLog().wrote("I fell and my left ankle hurts").settle().give("ANKLE:L")
            .tapOn(SymptomOption.YES).tick(SymptomOption.CRACK)
        assertEquals(ChatEnd.STOP, log.end)
        val close = turn(log)
        assertEquals(listOf(SymptomAlert.EMERGENCY), close.alerts())
        assertTrue(close.pieces.none { it is BotPiece.Saved })
        assertEquals(ChatLine.CLOSE_EMERGENCY, close.lines().last())
        assertTrue(PainScript.wants(log.threads.single(), filingContext(log, key)).isEmpty())
        filed(store, log, key)
        assertTrue(store.painRows().isEmpty())
    }

    /** A live chat that crossed midnight files its pain under the day of the write. */
    @Test
    fun aRecordWrittenAfterMidnightInALiveChatLandsOnTheNewDay() {
        val store = MemoryChatStore()
        val key = chatKey()
        val log = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L").calmToTheEnd()
        val afterMidnight = CHAT_NOW + 12 * 60 * 60 * 1_000L + 5 * 60 * 1_000L
        filed(store, log, key, afterMidnight)
        val row = store.painRows().single()
        assertEquals(CHAT_TODAY.plusDays(1).toString(), row.day)
        assertEquals(afterMidnight, row.occurredAt)
        assertEquals(CHAT_TODAY.toString(), checkNotNull(store.db.chats[key]).day)
    }

    /**
     * The map writes only while its own question is asked: once it is sent, a stroke or a second Send on
     * the map still folding away changes nothing — not the areas sent, not the chest's thread the chest
     * handed over to — and the area is answered once: the folding map takes no touches to write into
     * whatever thread is last.
     */
    @Test
    fun aMapFoldingAwayAfterItsSendWritesNothing() {
        val asked = ChatLog().choseTopic(TopicChoice(Symptom.PAIN), setting).settle()
        val thread = asked.threads.last().id
        val knee = dabOn("KNEE:L")
        val hip = dabOn("HIP:L")
        // While its question is asked, the painting waits in its thread, kept as the draft.
        val drawn = checkNotNull(asked.mapPainted(thread, listOf(knee), emptyList(), emptyList(), setting))
        assertEquals("KNEE:L", drawn.threads.last().report.values[SymptomStep.PAIN_AREA])
        assertEquals(MapDraft(listOf(knee), emptyList(), emptyList()), drawn.threads.last().draft)
        // Another thread's map writes nothing into this one.
        assertNull(asked.mapPainted(thread + 1, listOf(hip), emptyList(), emptyList(), setting))
        val sent = checkNotNull(drawn.mapSent(thread, setting)).settle()
        assertNotEquals(SymptomStep.PAIN_AREA, sent.asked())
        // Folding away: a dab on the hip and Send again write nothing, and ask nothing twice.
        assertNull(sent.mapPainted(thread, listOf(knee, hip), emptyList(), emptyList(), setting))
        assertNull(sent.mapSent(thread, setting))
        val answers = sent.threads.last().report.chat?.turns.orEmpty()
            .count { it is ChatTurn.Asked && it.step == SymptomStep.PAIN_AREA }
        assertEquals(1, answers)
        // The chest sent: its own thread follows, and the pain's map writes nothing into it.
        val chest = dabOn("CHEST:L")
        val painted = checkNotNull(asked.mapPainted(thread, listOf(chest), emptyList(), emptyList(), setting))
        val handed = checkNotNull(painted.mapSent(thread, setting)).settle()
        assertEquals(Symptom.CHEST_PAIN, handed.threads.last().topic)
        assertNull(handed.mapPainted(thread, listOf(chest, hip), emptyList(), emptyList(), setting))
        assertNull(handed.mapSent(thread, setting))
    }

    /** A map state as the conversation holds it, for the clock's rule. */
    private fun testMap(thread: Int) = ChatMapState(
        thread,
        PainDrawing(emptyList(), PainView.FRONT),
        mutableStateOf(emptyList()),
        mutableStateOf(emptyList()),
        mutableStateOf(false),
        mutableStateOf(false),
        mutableStateOf(false),
        mutableStateOf(false),
        mutableStateOf(false),
    )

    /** Calm answers until [step] is the question asked. */
    private fun ChatLog.calmUntil(step: SymptomStep, setting: ChatSetting): ChatLog {
        var log = this
        repeat(20) {
            if (log.asked(setting) == step) return log
            val reply = chatTurn(log, setting).reply as ChatReply.Choose
            log = if (reply.step.multi) {
                log.tick(*calm(reply.options).toTypedArray(), setting = setting)
            } else {
                log.tapOn(calm(reply.options).single(), setting)
            }
        }
        error("$step was never asked")
    }
}
