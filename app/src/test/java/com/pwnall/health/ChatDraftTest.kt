package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * What a reading told, as the chat's drafts: the places with
 * their sides as the area's chips, a start and the kinds as marks, a number as where the ruler rests,
 * when a pain was noticed prefilled for one tap, a medicine's name, time and amount as the chips its
 * words would give — every one a proposal on the question the script asks anyway, none an answer.
 * Nothing is answered and nothing filed without the person's tap; a question a rule reads is never
 * prefilled, only marked.
 */
class ChatDraftTest {
    private val setting = chatSetting()

    @After
    fun clock() {
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }

    /** A message the rules read nothing in, which the reading told a pain of the right knee in. */
    private val knee = chatRead {
        copy(
            topics = listOf(Symptom.PAIN),
            told = ReadTold(
                places = listOf(ReadPlace("KNEE", "KNEE", SIDE_RIGHT)),
                onset = SymptomOption.STARTED_YESTERDAY,
                types = listOf(PainType.SHARP),
                noticed = SymptomOption.NOTICED_ON_WAKING,
                nrs = 7,
                painkiller = true,
            ),
        )
    }

    private fun started(): ChatLog {
        val log = ChatLog().wroteRead("auu", knee)
        val chip = (chatTurn(log, setting).reply as ChatReply.Topics).choices.single()
        assertEquals("PAIN:KNEE", chip.mention?.key)
        return log.choseTopic(chip, setting).settle()
    }

    /** The pain's thread opens on the knee's chip on the side told, as «болит правое колено» does; none taken yet. */
    @Test
    fun thePlaceToldIsTheAreasChip() {
        val log = started()
        assertEquals(SymptomStep.PAIN_AREA, log.asked())
        val reply = chatTurn(log, setting).reply as ChatReply.AreaChips
        assertEquals(listOf(listOf("KNEE:R")), reply.choices.map { choice -> choice.areas.map { it.code } })
        assertTrue(log.threads.last().report.values.isEmpty())
        // The rules' own words for the same knee give the same chip.
        val words = ChatLog().wrote("болит правое колено").settle()
        assertEquals(reply.choices, (chatTurn(words, setting).reply as ChatReply.AreaChips).choices)
    }

    /**
     * Down the thread: a start a rule reads is marked, never proposed; when it was noticed — a step no
     * rule reads — stands proposed, marked and never ticked (no answer the person did
     * not give is drawn as given), answered only by its "From your message: …" or another chip; the
     * ruler rests on the number told and holds no value; the painkiller is marked.
     */
    @Test
    fun theThreadProposesAndThePersonAnswers() {
        var log = started().give("KNEE:R")
        val seen = mutableMapOf<SymptomStep, ChatReply>()
        repeat(20) {
            val move = chatMove(log, setting) as? ChatMove.Ask ?: return@repeat
            val reply = chatTurn(log, setting).reply
            seen[move.step] = reply
            assertFalse("${move.step} answered before a tap", log.threads.last().report.holdsAnswer(move.step))
            log = when {
                move.step == SymptomStep.PAIN_NOTICED -> log.answeredLast(setting) {
                    it.copy(
                        answers = it.answers + (move.step to (reply as ChatReply.Choose).proposed),
                    ).answered(move.step)
                }.settle()

                reply is ChatReply.PainScale -> log.give("4")

                reply is ChatReply.PainLasted -> log.give("${reply.start}")

                reply is ChatReply.Choose && move.step.multi -> log.tick(*calm(reply.options).toTypedArray())

                reply is ChatReply.Choose -> log.tapOn(calm(reply.options).single())

                else -> return@repeat
            }
        }
        val onset = seen.getValue(SymptomStep.ONSET) as ChatReply.Choose
        assertEquals(setOf(SymptomOption.STARTED_YESTERDAY), onset.marked)
        assertTrue("a start a rule reads is never proposed", onset.preset.isEmpty() && onset.proposed.isEmpty())
        val types = seen.getValue(SymptomStep.PAIN_TYPES) as ChatReply.Choose
        assertEquals(setOf(SymptomOption.PAIN_SHARP), types.marked)
        assertTrue(types.preset.isEmpty() && types.proposed.isEmpty())
        val noticed = seen.getValue(SymptomStep.PAIN_NOTICED) as ChatReply.Choose
        assertEquals(listOf(SymptomOption.NOTICED_ON_WAKING), noticed.proposed)
        assertTrue("a proposal is never ticked", noticed.preset.isEmpty())
        assertTrue(SymptomOption.NOTICED_ON_WAKING in noticed.marked)
        assertEquals(R.string.symptom_chat_keep_told, noticed.keep?.res)
        assertEquals(7, (seen.getValue(SymptomStep.PAIN_NRS) as ChatReply.PainScale).rest)
        assertEquals(setOf(SymptomOption.YES), (seen.getValue(SymptomStep.PAIN_PAINKILLER) as ChatReply.Choose).marked)
        // What was taken is what was tapped: the kept moment, the 4 of the ruler — never the 7 told.
        val saved = log.threads.last().report.forSave()
        assertEquals(listOf(SymptomOption.NOTICED_ON_WAKING), saved.answers[SymptomStep.PAIN_NOTICED])
        assertEquals("4", saved.values[SymptomStep.PAIN_NRS])
        assertTrue(saved.answers[SymptomStep.ONSET] != listOf(SymptomOption.STARTED_YESTERDAY))
    }

    /** Any other chip answers a prefilled question as ever; the prefill is taken only by its "Keep". */
    @Test
    fun anotherChipAnswersAPrefilledQuestion() {
        var log = started().give("KNEE:R")
        repeat(20) {
            if (log.asked() == SymptomStep.PAIN_NOTICED) return@repeat
            log = log.calmToNext()
        }
        assertEquals(SymptomStep.PAIN_NOTICED, log.asked())
        val other = log.tapOn(SymptomOption.NOTICED_AFTER_LIFTING)
        assertEquals(
            listOf(SymptomOption.NOTICED_AFTER_LIFTING),
            other.threads.last().report.answers[SymptomStep.PAIN_NOTICED],
        )
    }

    /**
     * Where the words read a moment of their own, unlike the reading's, nothing is prefilled, and the
     * words' own mark stands: a reading never takes one away.
     */
    @Test
    fun aMomentTheWordsSayOtherwiseIsNotPrefilled() {
        val lifting = knee.copy(told = knee.told.copy(noticed = SymptomOption.NOTICED_AFTER_LIFTING))
        val words = ChatLog().wroteRead("болит колено, проснулась с болью", lifting)
        var log = words.give("KNEE:R")
        repeat(20) {
            if (log.asked() == SymptomStep.PAIN_NOTICED) return@repeat
            log = log.calmToNext()
        }
        val reply = chatTurn(log, setting).reply as ChatReply.Choose
        assertTrue(reply.preset.isEmpty() && reply.proposed.isEmpty())
        assertEquals(setOf(SymptomOption.NOTICED_ON_WAKING), reply.marked)
        // The words alone mark their own, and the reading that agrees with them prefills it.
        val agreeing = knee.copy(told = knee.told.copy(noticed = SymptomOption.NOTICED_ON_WAKING))
        var same = ChatLog().wroteRead("болит колено, проснулась с болью", agreeing).give("KNEE:R")
        repeat(20) {
            if (same.asked() == SymptomStep.PAIN_NOTICED) return@repeat
            same = same.calmToNext()
        }
        assertEquals(
            listOf(SymptomOption.NOTICED_ON_WAKING),
            (chatTurn(same, setting).reply as ChatReply.Choose).proposed,
        )
    }

    /**
     * A reading that tells a pain otherwise than the rules' words never takes what they gave
     * (a number only where the rules say none or agree): the ruler
     * rests on the rules' 9, not on the default between theirs and the reading's 7; the rules' "very
     * bad" keeps its red flags' marks beside a reading's "mild"; their start stands beside another
     * told. Where the words say none, the reading's own stand.
     */
    @Test
    fun aReadingThatDisagreesNeverTakesTheRulesMarks() {
        val mild = chatRead {
            copy(
                topics = listOf(Symptom.PAIN),
                told = ReadTold(
                    places = listOf(ReadPlace("KNEE", "KNEE", SIDE_LEFT)),
                    onset = SymptomOption.STARTED_TODAY,
                    intensity = listOf(ReadIntensity.MILD),
                    nrs = 7,
                ),
            )
        }
        val text = "очень сильно болит колено со вчера, 9 из 10"
        fun pain(read: ChatRead?): PainWordMention {
            val log = ChatLog().wroteRead(text, read)
            assertEquals(Symptom.PAIN, log.threads.single().topic)
            return checkNotNull(threadPainMention(log.threadState(0, setting)))
        }
        val rules = pain(null)
        assertEquals(9, rules.nrs)
        assertEquals(SymptomOption.STARTED_YESTERDAY, rules.onset)
        assertTrue(SymptomOption.SUDDEN_OR_SEVERE in rules.signs)
        val union = pain(mild)
        assertEquals(9, union.nrs)
        assertEquals(SymptomOption.STARTED_YESTERDAY, union.onset)
        assertTrue(union.signs.containsAll(rules.signs))
        // The places beside theirs: the knee on the side the words left unsaid, and the side told.
        assertTrue(union.candidates.containsAll(rules.candidates))
        // Where the words say no number and no start, the reading's own stand.
        val plain = ChatLog().wroteRead("болит колено", mild)
        val told = checkNotNull(threadPainMention(plain.threadState(0, setting)))
        assertEquals(7, told.nrs)
        assertEquals(SymptomOption.STARTED_TODAY, told.onset)
    }

    /**
     * Still there or gone, the same pain or a new one: the rules' words say it, and a reading that says
     * the other never takes their mark away — nor leaves the chip unmarked, as both said at once would.
     */
    @Test
    fun aReadingNeverTakesTheRulesStillOrSame() {
        fun pain(text: String, read: ChatRead?): PainWordMention {
            val log = ChatLog().wroteRead(text, read)
            assertEquals(Symptom.PAIN, log.threads.single().topic)
            return checkNotNull(threadPainMention(log.threadState(0, setting)))
        }
        fun told(status: SymptomOption? = null, same: SymptomOption? = null) = chatRead {
            copy(
                topics = listOf(Symptom.PAIN),
                told = ReadTold(places = listOf(ReadPlace("KNEE", "KNEE", SIDE_LEFT)), status = status, same = same),
            )
        }
        val still = "колено до сих пор болит"
        assertEquals(true, pain(still, null).still)
        assertEquals(true, pain(still, told(status = SymptomOption.GONE)).still)
        val again = "опять болит колено"
        assertEquals(true, pain(again, null).same)
        assertEquals(true, pain(again, told(same = SymptomOption.PAIN_NEW)).same)
        // Where the words say neither, the reading's own stand.
        assertEquals(false, pain("болит колено", told(status = SymptomOption.GONE)).still)
        assertEquals(false, pain("болит колено", told(same = SymptomOption.PAIN_NEW)).same)
    }

    /**
     * The places a reading told add chips and never take the rules' away: a
     * side only the reading named leaves the knee's three chips as the words give them, and another
     * area of the same family leaves them chips rather than the map.
     */
    @Test
    fun aReadingsPlacesNeverNarrowTheRulesChips() {
        val text = "болит колено"
        val rules = (chatTurn(ChatLog().wrote(text).settle(), setting).reply as ChatReply.AreaChips).choices
        assertEquals(3, rules.size)
        listOf(ReadPlace("KNEE", "KNEE", SIDE_LEFT), ReadPlace("KNEE", "BACK_OF_KNEE", null)).forEach { place ->
            val read = chatRead { copy(topics = listOf(Symptom.PAIN), told = ReadTold(places = listOf(place))) }
            val reply = chatTurn(ChatLog().wroteRead(text, read), setting).reply
            assertTrue("$place: $reply", (reply as? ChatReply.AreaChips)?.choices.orEmpty().containsAll(rules))
        }
        // With no place of the rules' own, the reading's side stands alone, as the words of one would.
        val left = ReadTold(places = listOf(ReadPlace("KNEE", "KNEE", SIDE_LEFT)))
        val read = chatRead { copy(topics = listOf(Symptom.PAIN), told = left) }
        val pain = ChatLog().wroteRead("hmm", read).choseTopic(TopicChoice(Symptom.PAIN), setting).settle()
        assertEquals(
            listOf(AreaChoice(listOf(RegionSide(PainRegion.KNEE, BodySide.LEFT)))),
            (chatTurn(pain, setting).reply as ChatReply.AreaChips).choices,
        )
    }

    /**
     * A start the words name in another message of the thread — not in the pain's own words — is marked
     * before the reading's other one, as the rules mark it without a reading; where the words name
     * none, the reading's own is marked.
     */
    @Test
    fun aStartTheWordsNameElsewhereStandsBeforeTheReadings() {
        val today = chatRead {
            copy(
                topics = listOf(Symptom.PAIN),
                told = ReadTold(
                    places = listOf(ReadPlace("KNEE", "KNEE", SIDE_RIGHT)),
                    onset = SymptomOption.STARTED_TODAY,
                ),
            )
        }
        fun onset(read: ChatRead?, remark: String): Set<SymptomOption> {
            var log = ChatLog().wroteRead("болит колено", read).wroteRead(remark, null).give("KNEE:R")
            repeat(20) {
                if (log.asked() == SymptomStep.ONSET) return@repeat
                log = log.calmToNext()
            }
            assertEquals(SymptomStep.ONSET, log.asked())
            return (chatTurn(log, setting).reply as ChatReply.Choose).marked
        }
        assertEquals(setOf(SymptomOption.STARTED_YESTERDAY), onset(null, "со вчера"))
        assertEquals(setOf(SymptomOption.STARTED_YESTERDAY), onset(today, "со вчера"))
        assertEquals(setOf(SymptomOption.STARTED_TODAY), onset(today, "ну вот"))
    }

    /** A message with all of it told and no tap after it files nothing: no topic was chosen, no question answered. */
    @Test
    fun nothingIsFiledWithoutATap() {
        val store = MemoryChatStore()
        val key = chatKey()
        val log = ChatLog().wroteRead("auu", knee)
        persist(ChatFiling(store, key), log, key)
        assertTrue(store.db.journal.isEmpty())
        assertTrue(store.db.courses.isEmpty())
        // The thread chosen and its area tapped, still no row until the thread has asked what a pain's row needs.
        val chosen = started()
        persist(ChatFiling(store, key), chosen, key)
        assertTrue(store.db.journal.isEmpty())
    }

    /**
     * A dose the reading told — the medicine taken, a time, an amount — proposes them as its words
     * would: the name above the composer, "At 08:30" among the times, the amount beside the tablets; a
     * tap each, and none answered before it.
     */
    @Test
    fun aDoseToldIsProposedAsItsWordsWouldBe() {
        val chat = MedicineChat()
        val read = chatRead {
            copy(
                topics = listOf(Symptom.DOSE),
                told = ReadTold(
                    medicines = listOf(ReadMedicine("нурофен", listOf(ReadRole.TAKEN))),
                    amounts = listOf("2 таблетки"),
                    clocks = listOf(8 * 60 + 30),
                ),
            )
        }
        chat.log = ChatLog().wroteRead("…", read, chat.setting)
        val chip = (chat.reply as ChatReply.Topics).choices.single()
        assertEquals("DOSE:нурофен", chip.mention?.key)
        chat.log = chat.log.choseTopic(chip, chat.setting).settle(chat.setting)
        assertEquals(SymptomStep.MED_NAME, chat.step)
        val name = chat.reply as ChatReply.TextAsk
        assertTrue(name.suggestions.toString(), name.suggestions.any { it.value == "Нурофен" })
        assertNull(chat.report.values[SymptomStep.MED_NAME])
        chat.type("Нурофен", SymptomStep.MED_NAME)
        val time = chat.reply as ChatReply.Values
        assertTrue(time.choices.toString(), time.choices.any { it.value == "510" && it.accent })
        assertNull(chat.report.values[SymptomStep.MED_WHEN])
        chat.value("NOW", SymptomStep.MED_WHEN)
        val amount = chat.reply as ChatReply.TextAsk
        assertTrue(amount.suggestions.toString(), amount.suggestions.any { it.value == "2 таблетки" && it.accent })
        assertNull(chat.report.values[SymptomStep.MED_AMOUNT])
    }

    /** With no reading kept, every question offers exactly what it offered before. */
    @Test
    fun withoutAReadingNothingIsProposed() {
        val log = ChatLog().wrote("auu").settle()
        assertTrue((chatTurn(log, setting).reply as ChatReply.Topics).choices.isEmpty())
        val plain = ChatLog().choseTopic(TopicChoice(Symptom.PAIN), setting).settle()
        assertTrue(chatTurn(plain, setting).reply is ChatReply.BodyMap)
    }

    /** One answer of the live thread, as [calmToTheEnd] answers it. */
    private fun ChatLog.calmToNext(): ChatLog = when (val reply = chatTurn(this, setting).reply) {
        is ChatReply.PainScale -> give("4")

        is ChatReply.PainLasted -> give("${reply.start}")

        is ChatReply.Choose -> if (asked().multi) {
            tick(
                *calm(reply.options).toTypedArray(),
            )
        } else {
            tapOn(calm(reply.options).single())
        }

        is ChatReply.AreaChips -> give(reply.choices.first().areas.joinToString(",") { it.code })

        else -> error("${asked()} waits for $reply")
    }
}
