package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chest marked in a pain thread: nothing is recorded for it before the chest's own
 * questions, which "does it spread?" joins for every spread area marked with it; the pain is taken up
 * after them with the marks loaded; a sitting asks the chest once, a later sitting again; the arm's
 * "chest too" goes once its spread was asked, the back's, the belly's and the leg's never.
 */
class ChatChestTest {
    private val setting = chatSetting()

    private val chest = dabOn("CHEST:L")
    private val arm = dabOn("UPPER_ARM:L")

    /** A pain thread begun from the menu at the opening of [log], its area answered by [strokes]. */
    private fun painted(
        vararg strokes: PainStroke,
        log: ChatLog = ChatLog(),
        setting: ChatSetting = this.setting,
    ): ChatLog =
        log.choseTopic(TopicChoice(Symptom.PAIN), setting).settle(setting).paint(strokes.toList(), setting = setting)

    private fun filed(store: MemoryChatStore, log: ChatLog, key: String, now: Long = CHAT_NOW) =
        persist(ChatFiling(store, key), log, key, now)

    @Test
    fun aChestAreaHandsOverWithNoRecordAndResumesWithTheMarksLoaded() {
        var log = painted(chest, arm)
        assertEquals("CHEST:L,UPPER_ARM:L", log.threads[0].report.values[SymptomStep.PAIN_AREA])
        assertTrue(log.threads[0].ended)
        val handed = log.threads[1]
        assertEquals(Symptom.CHEST_PAIN to ROUTE_HANDOVER + "0", handed.topic to handed.from)
        val first = chatTurn(log, setting)
        assertEquals(listOf(ChatLine.CHEST_FIRST), first.lines())
        assertEquals(SymptomStep.CHEST_NOW, first.asked())
        // The pain thread files nothing for a chest handed over; the chest thread nothing until its own answer.
        val store = MemoryChatStore()
        val key = chatKey()
        filed(store, log, key)
        assertTrue(store.db.journal.isEmpty())
        log = log.tapOn(SymptomOption.NO)
        val spread = chatTurn(log, setting)
        assertEquals(SymptomStep.CHEST_SPREAD, spread.asked())
        assertEquals(
            ChatText(R.string.symptom_ask_chest_spread, listOf(PieceArg.Areas(listOf("UPPER_ARM:L")))),
            spread.pieces.filterIsInstance<BotPiece.Ask>().single().text,
        )
        assertEquals(listOf(SymptomOption.YES, SymptomOption.NO), (spread.reply as ChatReply.Choose).options)
        log = log.tapOn(SymptomOption.NO)
        assertEquals("UPPER_ARM:L", log.threads[1].report.meta[CHEST_SPREAD_AREAS])
        log = log.calmToTheEnd()
        // The pain taken up again, with no prompt between: the chest's close, then "now let us finish".
        val resumed = log.threads[2]
        assertEquals(Symptom.PAIN to ROUTE_RESUME + "0", resumed.topic to resumed.from)
        val again = chatTurn(log, setting)
        assertTrue(again.pieces.any { it is BotPiece.Saved && it.thread == handed.id })
        assertEquals(ChatLine.PAIN_RESUME, again.lines().last())
        val map = again.reply as ChatReply.BodyMap
        assertFalse(map.head)
        val start = mapStart(resumed, log.threadState(2, setting), map)
        assertEquals(listOf(chest, arm), start.strokes)
        assertEquals("CHEST:L,UPPER_ARM:L", chatMapCodes(start.strokes, start.added, start.removed))
        // Sent as it was: the chest was asked about, and so was the arm's spread — on to the arm's own list.
        log = log.paint(start.strokes, start.added, start.removed)
        assertEquals(SymptomStep.PAIN_INJURY, log.asked())
        log = log.tapOn(SymptomOption.NO)
        val signs = chatTurn(log, setting).reply as ChatReply.Choose
        assertFalse(SymptomOption.CHEST_TOO in signs.options)
        log = log.calmToTheEnd()
        filed(store, log, key, CHAT_NOW + 60_000)
        val pain = store.painRows().single()
        assertEquals(
            listOfNotNull(RegionSide.parse("CHEST:L"), RegionSide.parse("UPPER_ARM:L")),
            decodePainPayload(pain.measurementPayloadJson).regions,
        )
        assertEquals(1, store.db.journal.values.count { it.kind == KIND_SYMPTOM })
    }

    /** "Is it there now?" is carried into the chest's thread from this sitting only. */
    @Test
    fun chestNowIsSeededFromThisSittingOnly() {
        // Something else, with chest pain among what comes with it: "is it there now?" asked, "no".
        val other = ChatLog().choseTopic(TopicChoice(Symptom.OTHER), setting).settle()
            .tick(SymptomOption.CHEST_PAIN).tapOn(SymptomOption.NO).calmToTheEnd()
        assertEquals(listOf(SymptomOption.NO), other.threads[0].report.answers[SymptomStep.CHEST_NOW])
        assertEquals(ChatMove.Prompt, chatMove(other, setting))
        val seeded = painted(chest, log = other)
        val chestThread = seeded.threads.last()
        assertEquals(Symptom.CHEST_PAIN, chestThread.topic)
        val now = chestThread.report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Asked>()
            .first { it.step == SymptomStep.CHEST_NOW }
        assertTrue(now.routed)
        assertEquals(listOf(SymptomOption.NO), chestThread.report.answers[SymptomStep.CHEST_NOW])
        assertEquals(SymptomStep.WITH, seeded.asked())
        // In a later sitting it is asked again.
        val later = chatSetting(sittingFrom = 1)
        val asked = painted(chest, log = other, setting = later)
        assertEquals(SymptomStep.CHEST_NOW, asked.asked(later))
        assertNull(asked.threads.last().report.answers[SymptomStep.CHEST_NOW])
    }

    /**
     * A chest and an arm marked: whether it spreads is asked, and "yes" is a call to emergency services.
     */
    @Test
    fun chestSpreadIsAskedForChestAndArmAndYesIsAnEmergency() {
        val store = MemoryChatStore()
        val key = chatKey()
        val log = painted(chest, arm).tapOn(SymptomOption.NO).tapOn(SymptomOption.YES)
        assertEquals(ChatEnd.STOP, log.end)
        val close = chatTurn(log, setting)
        assertEquals(listOf(SymptomAlert.EMERGENCY), close.alerts())
        assertEquals(
            R.string.symptom_source_chest_spread,
            close.pieces.filterIsInstance<BotPiece.Alert>().single().sourceRes,
        )
        assertEquals(listOf(Symptom.PAIN, Symptom.CHEST_PAIN), log.threads.map { it.topic })
        filed(store, log, key)
        assertTrue(store.painRows().isEmpty())
        // A chest alone asks nothing of the sort.
        assertEquals(SymptomStep.WITH, painted(chest).tapOn(SymptomOption.NO).asked())
    }

    /**
     * Every spread area of the current map — by family, so every area a later map adds — is asked about.
     */
    @Test
    fun chestSpreadIsAskedForEverySpreadAreaOfTheCurrentMap() {
        val spread = PainRegion.entries.filter { region ->
            isChestSpreadArea(RegionSide(region, if (region.paired) BodySide.LEFT else BodySide.CENTRE))
        }.map { it.name }.toSet()
        val named = setOf(
            "TRAPEZIUS", "SHOULDER_BLADE", "LOWER_BACK", "FLANK", "UPPER_BELLY", "UNDER_RIBS", "LOWER_BELLY",
            "LOWER_SIDE",
            "BACK_OF_UPPER_ARM", "ELBOW_INNER", "BACK_OF_FOREARM", "FINGERS", "JAW", "NECK", "SHOULDER", "UPPER_ARM",
            "ELBOW", "FOREARM", "WRIST_HAND", "UPPER_BACK", "ABDOMEN",
        )
        assertTrue(spread.containsAll(named))
        listOf("GLUTE", "SACRUM", "TAILBONE", "CHEST", "STERNUM", "KNEE", "HIP", "HEAD_FRONT", "CALF").forEach {
            assertFalse(it, it in spread)
        }
        // Each is named by the chest's "does it spread?" when marked with the chest.
        PainRegion.entries.filter { it.name in spread }.forEach { region ->
            val area = RegionSide(region, if (region.paired) BodySide.LEFT else BodySide.CENTRE)
            val areaAnswer = mapOf(SymptomStep.PAIN_AREA to "CHEST:L,${area.code}")
            val pain = SymptomReport(symptom = Symptom.PAIN, values = areaAnswer)
            val state = ChatState(
                SymptomReport(symptom = Symptom.CHEST_PAIN, chat = SymptomChat()),
                female = false,
                earlier = null,
                thread = ThreadContext(from = ROUTE_HANDOVER + "0", resumed = pain),
            )
            assertEquals(region.name, listOf(area.code), chestSpreadCodes(state))
        }
    }

    /** A second chest marked in the same sitting is not handed over again; in the next one it is. */
    @Test
    fun noSecondHandoverInOneSittingButAgainInTheNext() {
        val once = painted(chest).calmToTheEnd().calmToTheEnd()
        assertEquals(listOf(Symptom.PAIN, Symptom.CHEST_PAIN, Symptom.PAIN), once.threads.map { it.topic })
        assertEquals(ChatMove.Prompt, chatMove(once, setting))
        val twice = painted(chest, log = once)
        assertEquals(Symptom.PAIN, twice.threads.last().topic)
        // The chest's own questions were asked in this sitting: the pain page's are next.
        assertEquals(SymptomStep.PAIN_TYPES, twice.asked())
        val nextSitting = chatSetting(sittingFrom = once.threads.size)
        val again = painted(chest, log = once, setting = nextSitting)
        assertEquals(Symptom.CHEST_PAIN, again.threads.last().topic)
        assertEquals(ROUTE_HANDOVER + again.threads[again.threads.size - 2].id, again.threads.last().from)
    }

    /** Back after a quarter of an hour, the page starts a new sitting: the chest is asked about again. */
    @Test
    fun backFromTheBackgroundAfterFifteenMinutesTheChestIsTriagedAgain() {
        assertEquals(15 * 60 * 1_000L, SITTING_GAP_MS)
        val asked = painted(chest).calmToTheEnd()
        val sameSitting = asked.threadState(asked.threads.lastIndex, setting)
        assertTrue(sameSitting.thread.chestAsked)
        val returned = setting.copy(sittingFrom = asked.threads.size)
        assertFalse(asked.threadState(asked.threads.lastIndex, returned).thread.chestAsked)
        // The chest the pain thread marks is handed over again: "is it there now?" is old news.
        val pain = asked.calmToTheEnd(returned)
        assertEquals(Symptom.CHEST_PAIN to ROUTE_HANDOVER + "2", pain.threads.last().let { it.topic to it.from })
        assertEquals(SymptomStep.CHEST_NOW, pain.asked(returned))
    }

    /**
     * After "does it spread?" answered no, the arm's and the neck's "chest too" go; while not asked, it stays.
     */
    @Test
    fun chestTooLeavesOnlyTheSpreadFamiliesAfterChestSpread() {
        val marks = setOf(checkNotNull(RegionSide.parse("UPPER_ARM:L")), checkNotNull(RegionSide.parse("NECK:C")))
        val families = painFamilies(marks)
        fun asked(covered: Boolean) = painSignLists(
            families,
            marks,
            injury = false,
            female = false,
            adult = true,
            spreadCovered = covered,
        )
        assertTrue(SymptomOption.CHEST_TOO in asked(covered = false).first)
        assertFalse(SymptomOption.CHEST_TOO in asked(covered = true).first + asked(covered = true).more)
        assertTrue(chestTooCovered(marks, setOf("UPPER_ARM:L", "NECK:C")))
        assertFalse(chestTooCovered(marks, setOf("UPPER_ARM:L")))
        assertFalse(chestTooCovered(setOf(checkNotNull(RegionSide.parse("KNEE:L"))), emptySet()))
    }

    /**
     * The back, the belly and the leg keep their "chest too" whatever was asked: their pages put chest pain with them
     * at 999.
     */
    @Test
    fun chestWithBellyBackOrCalfKeepsChestToo() {
        listOf("LOWER_BACK:C", "UPPER_BELLY:C", "CALF:L").forEach { code ->
            val marks = setOf(checkNotNull(RegionSide.parse(code)))
            val lists = painSignLists(
                painFamilies(marks),
                marks,
                injury = false,
                female = false,
                adult = true,
                spreadCovered = true,
            )
            assertTrue(code, SymptomOption.CHEST_TOO in lists.first + lists.more)
        }
    }

    /**
     * The back and a leg marked together: the leg's "chest pain, or pain in the upper back" (NHS "Pulmonary
     * embolism") stands, which holds the back's "chest pain" whole; with the arm too, the chest's own
     * widest words stand, and the back's narrower label never wins the tie.
     */
    @Test
    fun aBackAndALegMarkedAskTheLegsWiderChestLine() {
        fun label(vararg codes: String): Int? {
            val marks = codes.mapNotNull { RegionSide.parse(it) }.toSet()
            val lists = painSignLists(
                painFamilies(marks),
                marks,
                injury = false,
                female = false,
                adult = true,
                spreadCovered = false,
            )
            return lists.labels[SymptomOption.CHEST_TOO]
        }
        assertEquals(R.string.pain_chat_sign_chest_too_leg, label("LOWER_BACK:C", "CALF:L"))
        assertEquals(R.string.pain_chat_sign_chest_too_back_belly, label("LOWER_BACK:C"))
        assertEquals(SymptomOption.CHEST_TOO.labelRes, label("LOWER_BACK:C", "CALF:L", "UPPER_ARM:L"))
    }

    /**
     * Chest words, the chest's questions, then "Mark it on the pain map": a chest and an arm painted are asked whether
     * it spreads.
     */
    @Test
    fun aChestAndArmPaintedAfterChestWordsAsksChestSpread() {
        val words = ChatLog().wrote("my chest hurts").settle().calmToTheEnd()
        val follow = chatQueue(words, setting).first()
        assertEquals(TopicMention(Symptom.PAIN, "CHEST"), follow.mention)
        val pain = words.choseTopic(follow, setting).settle()
        assertEquals(ROUTE_FOLLOW + "PAIN:CHEST", pain.threads.last().from)
        val map = chatTurn(pain, setting).reply as ChatReply.BodyMap
        assertEquals(PainFrame(PainView.FRONT, FAMILY_CHEST), map.frame)
        assertTrue(ChatLine.PAIN_START !in chatTurn(pain, setting).lines())
        val marked = pain.paint(listOf(chest, arm))
        // No second triage: the pain thread asks whether it spreads, itself.
        assertEquals(Symptom.PAIN, marked.threads.last().topic)
        assertEquals(SymptomStep.CHEST_SPREAD, marked.asked())
        val store = MemoryChatStore()
        val key = chatKey()
        val stopped = marked.tapOn(SymptomOption.YES)
        assertEquals(ChatEnd.STOP, stopped.end)
        filed(store, stopped, key)
        assertTrue(store.painRows().isEmpty())
        // "No", and on to the arm's questions, its "chest too" gone.
        val calm = marked.tapOn(SymptomOption.NO).tapOn(SymptomOption.NO)
        assertFalse(SymptomOption.CHEST_TOO in (chatTurn(calm, setting).reply as ChatReply.Choose).options)
    }

    /**
     * "I marked the chest by mistake": the handing-over thread's area taken back takes the chest thread with it, and
     * its row.
     */
    @Test
    fun rewindingTheHandingOverThreadRemovesTheChestThreadAndItsRow() {
        val store = MemoryChatStore()
        val key = chatKey()
        val handed = painted(chest)
        // Before the chest thread has an answer of its own, the pain thread keeps its pencil.
        assertEquals(setOf(0, 1), pencilThreads(handed))
        var log = handed.tapOn(SymptomOption.NO)
        log = log.withLinks(filed(store, log, key))
        assertEquals(1, store.db.journal.values.count { it.kind == KIND_SYMPTOM })
        val areaAt = log.threads[0].report.chat?.turns.orEmpty().indexOfFirst {
            (it as? ChatTurn.Asked)?.step == SymptomStep.PAIN_AREA
        }
        log = log.rewoundLog(0, areaAt).settle()
        assertEquals(1, log.threads.size)
        assertEquals(SymptomStep.PAIN_AREA, log.asked())
        filed(store, log, key, CHAT_NOW + 1_000)
        assertTrue(store.db.journal.isEmpty())
    }

    /**
     * A pain filed, its area answered again with the chest in it: the chest's own questions first, and
     * then the same entry — the pain taken up again carries it, rewritten where it stands with the chest
     * once told to its end; one row, never a second beside it.
     */
    @Test
    fun aFiledPainAnsweredAgainWithTheChestIsRewrittenAfterTheChestsQuestions() {
        val store = MemoryChatStore()
        val key = chatKey()
        val knee = dabOn("KNEE:L")
        var log = painted(knee).calmToTheEnd()
        log = log.withLinks(filed(store, log, key))
        val first = store.painRows().single()
        val areaAt = log.threads[0].report.chat?.turns.orEmpty().indexOfFirst {
            (it as? ChatTurn.Asked)?.step == SymptomStep.PAIN_AREA
        }
        log = log.rewoundLog(0, areaAt).settle()
        log = log.withLinks(filed(store, log, key, CHAT_NOW + 1_000)).paint(listOf(chest, knee))
        assertEquals(Symptom.CHEST_PAIN to ROUTE_HANDOVER + "0", log.threads[1].let { it.topic to it.from })
        log = log.withLinks(filed(store, log, key, CHAT_NOW + 2_000)).calmToTheEnd()
        assertEquals(ROUTE_RESUME + "0", log.threads[2].from)
        log = log.withLinks(filed(store, log, key, CHAT_NOW + 3_000))
        // While the chest is asked about and the pain is taken up again, the entry stands as it was.
        assertEquals(listOf(first), store.painRows())
        log = log.calmToTheEnd()
        filed(store, log, key, CHAT_NOW + 4_000)
        val again = store.painRows().single()
        assertEquals(first.id to first.occurredAt, again.id to again.occurredAt)
        assertEquals(
            listOfNotNull(RegionSide.parse("CHEST:L"), RegionSide.parse("KNEE:L")),
            decodePainPayload(again.measurementPayloadJson).regions,
        )
        // Its card is the resumed thread's now; the thread that handed over keeps none.
        val kept = stored(store, key)
        assertTrue(kept.threads[0].filed.none { it.role.isEmpty() })
        assertEquals(first.occurredAt, kept.threads[2].filed.single { it.role.isEmpty() }.at)
    }

    /**
     * Whether a thread handed its chest over is what it was when its area was answered: a sitting begun
     * since — a quarter of an hour away — never turns a pain already past its chest into one that hands
     * over again, so its card, its close and its next question stand.
     */
    @Test
    fun aPainPastItsChestStaysPastItInALaterSitting() {
        val resumed = painted(chest).calmToTheEnd().calmToTheEnd()
        assertEquals(listOf(Symptom.PAIN, Symptom.CHEST_PAIN, Symptom.PAIN), resumed.threads.map { it.topic })
        assertEquals("0", resumed.threads[2].report.meta[PAIN_HANDOVER])
        val later = chatSetting(sittingFrom = resumed.threads.size)
        assertFalse(resumed.threadState(2, later).thread.chestAsked)
        assertEquals(ChatMove.Prompt, chatMove(resumed, later))
        // Its close — the card, what to do now — is said as it was in its own sitting.
        val close = chatTurn(resumed, later)
        assertTrue(close.pieces.any { it is BotPiece.Saved && it.thread == 2 })
        assertEquals(chatTurn(resumed, setting), close)
        // Nothing is handed over for it at the next commit.
        assertEquals(3, resumed.settle(later).threads.size)
        // A pain still open, past its area, keeps its next question in the later sitting.
        val open = painted(chest).calmToTheEnd().paint(listOf(chest))
        assertEquals(SymptomStep.PAIN_TYPES, open.asked())
        assertEquals(SymptomStep.PAIN_TYPES, open.asked(chatSetting(sittingFrom = open.threads.size)))
        // The thread that handed over stays the one that did.
        assertEquals("1", resumed.threads[0].report.meta[PAIN_HANDOVER])
        assertTrue(painHandsOver(resumed.threadState(0, later)))
    }

    /**
     * The page brought back — the app restarted, the process back from death, a record opened over it —
     * starts a new sitting after a quarter of an hour away, whatever brought it back; sooner, the sitting
     * goes on.
     */
    @Test
    fun aPageBroughtBackAfterAQuarterOfAnHourStartsANewSitting() {
        assertEquals(5, sittingOnReturn(from = 0, threads = 5, awaySince = CHAT_NOW, now = CHAT_NOW + SITTING_GAP_MS))
        assertEquals(
            0,
            sittingOnReturn(from = 0, threads = 5, awaySince = CHAT_NOW, now = CHAT_NOW + SITTING_GAP_MS - 1),
        )
        assertEquals(2, sittingOnReturn(from = 2, threads = 5, awaySince = null, now = CHAT_NOW))
    }

    /**
     * A heavy heart is no chest: at the opening it asks nothing of the chest, and typed where a low mood
     * asks what it is about it is the answer — the question is not asked again for it (read as chest
     * pain, «на сердце тяжело» would be a reason that could never be given).
     */
    @Test
    fun aHeavyHeartIsNoChestAndStaysAMoodsReason() {
        listOf("my heart is heavy", "на сердце тяжело").forEach { words ->
            val opening = ChatLog().wrote(words).settle()
            assertFalse(words, opening.asked() == SymptomStep.CHEST_NOW)
            assertTrue(words, opening.threads.none { it.topic == Symptom.CHEST_PAIN })
        }
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(1)
        while (chat.asked() != SymptomStep.MOOD_ABOUT) {
            val step = checkNotNull(chat.asked())
            chat.tap(step, (chat.turn().reply as ChatReply.Choose).options.first())
        }
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.SOMETHING_ELSE)
        assertEquals(SymptomStep.MOOD_ELSE, chat.asked())
        chat.type(SymptomStep.MOOD_ELSE, "на сердце тяжело")
        assertEquals("на сердце тяжело", chat.thread().report.values[SymptomStep.MOOD_ELSE])
        assertFalse(chat.asked() == SymptomStep.MOOD_ELSE || chat.asked() == SymptomStep.CHEST_NOW)
    }

    /**
     * A heart attack named at the opening runs the chest's own questions at once; written in a knee's
     * thread, "is it there now?" comes next, and the chest's thread follows the knee's (neither is
     * read as no chest words, which would bring the menu, or the knee's question again).
     */
    @Test
    fun aHeartAttackNamedRunsTheChestsQuestions() {
        val opening = ChatLog().wrote("I think I'm having a heart attack").settle()
        assertEquals(SymptomStep.CHEST_NOW, opening.asked())
        val routed = opening.tapOn(SymptomOption.NO)
        assertEquals(Symptom.CHEST_PAIN to ROUTE_WORDS, routed.threads.single().let { it.topic to it.from })
        assertTrue(ChatLine.CHEST_WORDS in chatTurn(routed, setting).lines())

        // A knee chosen from the menu: the chest's words are all the thread has written.
        val knee = painted(dabOn("KNEE:L"))
        val told = knee.wrote("сердце прихватило, тяжесть в груди").settle()
        assertEquals(SymptomStep.CHEST_NOW, told.asked())
        val after = told.tapOn(SymptomOption.NO).calmToTheEnd()
        assertEquals(listOf(Symptom.PAIN, Symptom.CHEST_PAIN), after.threads.map { it.topic })
        assertEquals(ROUTE_WORDS, after.threads.last().from)
    }

    /**
     * Words of the chest in a thread that closed in a sitting that had asked about the chest bring no chest
     * thread at a later sitting's commit that closes nothing — a card ticked, a photo kept: what is
     * due after a thread is added as it closes.
     */
    @Test
    fun aLaterSittingsCommitAddsNoChestThreadForTheWordsOfAThreadClosedBefore() {
        val chest = ChatLog().wrote("I think I'm having a heart attack").settle().tapOn(SymptomOption.NO).calmToTheEnd()
        val knee = painted(dabOn("KNEE:L"), log = chest).wrote("сердце прихватило, тяжесть в груди").settle()
            .calmToTheEnd()
        assertEquals(listOf(Symptom.CHEST_PAIN, Symptom.PAIN), knee.threads.map { it.topic })
        assertEquals(ChatMove.Prompt, chatMove(knee, setting))
        val later = chatSetting(sittingFrom = knee.threads.size)
        assertEquals(knee.threads, knee.settle(later).threads)
    }

    /**
     * The chest's last answer is filed though the pain taken up after it is added in the same commit:
     * its row gets how bad it is, though the thread just closed is not the newest any more.
     */
    @Test
    fun theChestsLastAnswerIsFiledWhenThePainIsTakenUpInTheSameCommit() {
        val store = MemoryChatStore()
        val key = chatKey()
        var log = painted(chest)
        var now = CHAT_NOW

        // Filed after every commit, as the screen files it.
        fun file(next: ChatLog): ChatLog {
            now += 1_000
            return next.withLinks(filed(store, next, key, now))
        }
        log = file(log)
        log = file(log.tapOn(SymptomOption.NO))
        val row = store.db.journal.values.single { it.kind == KIND_SYMPTOM }
        assertEquals(null, decodeSymptomPayload(row.measurementPayloadJson).severity)
        while (log.asked() != SymptomStep.SEVERITY) {
            log = file(log.calmAnswerOnce())
        }
        val severe = log.answeredLast(setting) { it.rated(SymptomSeverity.SEVERE) }.settle()
        assertEquals(Symptom.PAIN to ROUTE_RESUME + "0", severe.threads.last().let { it.topic to it.from })
        file(severe)
        val again = store.db.journal.values.single { it.kind == KIND_SYMPTOM }
        assertEquals(row.id, again.id)
        assertEquals(SymptomSeverity.SEVERE, decodeSymptomPayload(again.measurementPayloadJson).severity)
    }

    /** One calm answer to the live question of the last thread (its "no", "none", first chip). */
    private fun ChatLog.calmAnswerOnce(): ChatLog {
        val step = asked()
        val reply = chatTurn(this, setting).reply as ChatReply.Choose
        return if (step.multi) tick(*calm(reply.options).toTypedArray()) else tapOn(calm(reply.options).single())
    }

    /** A call to emergency services in the chest's thread ends the chat: no pain is taken up after it. */
    @Test
    fun anEmergencyInTheChestThreadStopsAndNothingResumes() {
        val store = MemoryChatStore()
        val key = chatKey()
        val log = painted(chest, arm).tapOn(SymptomOption.YES)
        assertEquals(ChatEnd.STOP, log.end)
        assertEquals(listOf(Symptom.PAIN, Symptom.CHEST_PAIN), log.threads.map { it.topic })
        assertEquals(listOf(SymptomAlert.EMERGENCY), chatTurn(log, setting).alerts())
        filed(store, log, key)
        assertTrue(store.painRows().isEmpty())
    }
}
