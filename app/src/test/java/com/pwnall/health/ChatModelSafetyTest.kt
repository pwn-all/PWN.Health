package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The model's seven safety flags, ORed with the rules in every place the rules raise a safety
 * question today: the words' own questions
 * ([wordSteps]), the line of where help is after a message and before the topics, an answer typed in
 * place of one, the medicine's "more than it says?", the note after the end, the line pinned under the
 * bar. A flag only adds its own fixed question (schema.json `adds_step`), where the rules' words would
 * have put it and in their words; it never takes one away, rewords one, or takes the line to call for
 * an emergency from a message no English or Russian word of was read.
 */
class ChatModelSafetyTest {
    private val setting = chatSetting()

    @After
    fun clock() {
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }
    private val other = setting.copy(language = ChatLanguage.OTHER)

    private fun flag(vararg steps: SymptomStep) = chatRead { copy(safety = steps.toList()) }

    /** A message the rules read nothing in: no key of any lexicon, no question, no topic. */
    private val nothing = "hmm"

    @Test
    fun eachFlagAsksItsOwnQuestionFirst() {
        assertEquals(SymptomStep.SYMPTOM, ChatLog().wroteRead(nothing, null).asked())
        READ_SAFETY_STEPS.forEach { step ->
            val log = ChatLog().wroteRead(nothing, flag(step))
            assertEquals(step, log.asked())
            // In the script's own words: the turn is the one the rules' words would have brought.
            val turn = chatTurn(log, setting)
            assertEquals(step, turn.asked())
        }
        // Where help is comes before the question about suicide, as it does after the words of it.
        val crisis = chatTurn(ChatLog().wroteRead(nothing, flag(SymptomStep.CRISIS_NOW)), setting)
        assertEquals(ChatLine.CRISIS_SUPPORT, crisis.lines().first())
    }

    /** Several flags ask theirs in the script's order — the crisis first, then the emergencies, then a medicine's. */
    @Test
    fun severalFlagsAskInTheScriptsOrder() {
        val all = flag(*READ_SAFETY_STEPS.reversed().toTypedArray())
        val chat = ChatLog().wroteRead(nothing, all).threads.single().report.chat!!
        assertEquals(READ_SAFETY_STEPS, wordSteps(chat, SymptomReport(chat = chat), emptyList()))
    }

    /**
     * A flag never takes away a question the words asked: the rules' crisis, breathing and chest are
     * all asked beside a reading that raised nothing, or something else.
     */
    @Test
    fun aFlagNeverTakesAQuestionAway() {
        mapOf(
            "не хочу жить" to SymptomStep.CRISIS_NOW,
            "задыхаюсь" to SymptomStep.BREATH_NOW,
            "болит в груди" to SymptomStep.CHEST_NOW,
            "I took 20 paracetamol" to SymptomStep.MED_MAX_WORDS,
        ).forEach { (text, step) ->
            listOf(chatRead(), flag(SymptomStep.STROKE_SIGNS), chatRead { copy(why = "abstain") }).forEach { read ->
                val chat = ChatLog().wroteRead(text, read).threads.single().report.chat!!
                val asked = wordSteps(chat, SymptomReport(chat = chat), emptyList())
                assertTrue("$text $read $asked", step in asked)
            }
        }
        // The rules' own first, the reading's after them.
        val chat = ChatLog().wroteRead("задыхаюсь", flag(SymptomStep.CRISIS_NOW)).threads.single().report.chat!!
        assertEquals(
            listOf(SymptomStep.BREATH_NOW, SymptomStep.CRISIS_NOW),
            wordSteps(chat, SymptomReport(chat = chat), emptyList()),
        )
    }

    /**
     * A flag never puts its question before one the rules' words ask: the
     * rules' emergency question comes first, as it does with no model — so the stop its "yes" calls for
     * is never a turn later — and the reading's question after it, where help is coming with it. So
     * for each emergency, too much of a medicine, and a message written while the rules' question waits.
     */
    @Test
    fun aFlagNeverAsksBeforeTheRulesOwnQuestion() {
        val stroke = "лицо перекосило, рука не поднимается"
        assertEquals(SymptomStep.STROKE_SIGNS, ChatLog().wrote(stroke).settle().asked())
        val flagged = ChatLog().sentRead(stroke, flag(SymptomStep.CRISIS_NOW))
        assertEquals(SymptomStep.STROKE_SIGNS, flagged.asked())
        assertFalse(ChatLine.CRISIS_SUPPORT in chatTurn(flagged, setting).lines())
        assertEquals(ChatEnd.STOP, flagged.tapOn(SymptomOption.YES).end)
        val crisis = flagged.tapOn(SymptomOption.NO)
        assertEquals(SymptomStep.CRISIS_NOW, crisis.asked())
        assertTrue(ChatLine.CRISIS_SUPPORT in chatTurn(crisis, setting).lines())
        mapOf(
            "не могу дышать" to flag(SymptomStep.CRISIS_NOW),
            "давит в груди" to flag(SymptomStep.BREATH_NOW),
            "выпила 20 таблеток парацетамола" to flag(SymptomStep.BREATH_NOW, SymptomStep.CRISIS_NOW),
        ).forEach { (text, read) ->
            val rules = ChatLog().wrote(text).settle()
            val union = ChatLog().sentRead(text, read)
            assertEquals(text, rules.asked(), union.asked())
            val ruleSteps = wordStepsOfLast(rules)
            assertEquals(text, ruleSteps, wordStepsOfLast(union).take(ruleSteps.size))
        }
        // Written while the rules' breathing question waits: it is asked again first, the flag's after.
        val breath = ChatLog().wrote("не могу дышать").settle()
        assertEquals(SymptomStep.BREATH_NOW, breath.sentRead("что?", flag(SymptomStep.CRISIS_NOW)).asked())
    }

    private fun wordStepsOfLast(log: ChatLog): List<SymptomStep> {
        val thread = log.threads.last()
        return wordSteps(checkNotNull(thread.report.chat), thread.report.asTold(), emptyList())
    }

    /**
     * The question a flag brings in a thread with no topic yet keeps every safety piece of the router's
     * own reply to the words beside it, in its own turn — not a turn later, nor
     * never, once an answer about suicide closes the thread: someone else's line to call for a husband's
     * fever, and in a chat of an earlier day what stopping a medicine can bring. Drawn again the same.
     */
    @Test
    fun aFlagsQuestionKeepsTheRoutersSafetyPieces() {
        // Words the rules ask nothing of, so the question is the flag's alone: «почти не дышит» is one of their keys.
        val husband = "у мужа температура 40, он дышит с хрипом"
        val rules = chatTurn(ChatLog().wrote(husband).settle(), setting)
        assertTrue(ChatLine.SOMEONE_ELSE in rules.lines())
        val breath = ChatLog().sentRead(husband, flag(SymptomStep.BREATH_NOW))
        val turn = chatTurn(breath, setting)
        assertEquals(SymptomStep.BREATH_NOW, turn.asked())
        assertEquals(listOf(ChatLine.SOMEONE_ELSE, ChatLine.WORDS_CHECK), turn.lines())
        val crisis = ChatLog().sentRead(husband, flag(SymptomStep.CRISIS_NOW))
        assertTrue(ChatLine.SOMEONE_ELSE in chatTurn(crisis, setting).lines())
        val safe = crisis.tapOn(SymptomOption.SAFE_NOW)
        assertTrue(shown(safe, setting).containsAll(rules.pieces.filter { it.isSafety() }))
        val past = chatSetting(past = true)
        val stopped = "бросила сертралин"
        val notes = chatTurn(ChatLog(past = true).wroteRead(stopped, null, past), past).pieces.filter { it.isSafety() }
        assertEquals(3, notes.size)
        val asked = ChatLog(past = true).sentRead(stopped, flag(SymptomStep.CRISIS_NOW), past)
        val support = chatTurn(asked, past)
        assertEquals(SymptomStep.CRISIS_NOW, support.asked())
        assertTrue("${support.pieces}", support.pieces.containsAll(notes))
        val closed = asked.tapOn(SymptomOption.SAFE_NOW, past)
        assertTrue(shown(closed, past).containsAll(notes))
    }

    /** Every piece of every bot turn [log]'s transcript draws, as the chat shows it. */
    private fun shown(log: ChatLog, setting: ChatSetting): List<BotPiece> =
        chatTranscript(log, setting, live = true).filterIsInstance<ChatItem.Bot>().flatMap { it.turn.pieces }

    /**
     * "I am not having thoughts like that", said to the question a flag alone asked, takes away nothing
     * the rules ask later: the low mood's own question about suicide comes
     * where it comes with no model.
     */
    @Test
    fun aFlagAnsweredNoTakesNoQuestionAboutSuicideAway() {
        val low = ChatLog().choseTopic(TopicChoice(Symptom.LOW_MOOD), setting).settle()
        fun asked(log: ChatLog) = log.threads.first().report.chat?.askedSteps().orEmpty()
        val rules = low.wroteRead(nothing, null).calmToTheEnd()
        assertTrue(SymptomStep.CRISIS in asked(rules))
        val flagged = low.wroteRead(nothing, flag(SymptomStep.CRISIS_NOW))
        assertEquals(SymptomStep.CRISIS_NOW, flagged.asked())
        val no = flagged.tapOn(SymptomOption.NO_SUCH_THOUGHTS).calmToTheEnd()
        assertTrue(SymptomStep.CRISIS in asked(no))
        // Another answer stands for it, as the rules' own question asked for the words of suicide does.
        val safe = flagged.tapOn(SymptomOption.SAFE_NOW).calmToTheEnd()
        assertFalse(SymptomStep.CRISIS in asked(safe))
        val words = low.wrote("не хочу жить").settle().tapOn(SymptomOption.NO_SUCH_THOUGHTS).calmToTheEnd()
        assertFalse(SymptomStep.CRISIS in asked(words))
    }

    /**
     * A second flag of too much in a typed name, after "more than it says?" was answered "no", asks it
     * once more, as the rules' words of too much typed in the name do.
     */
    @Test
    fun aSecondFlagOfTooMuchInATypedNameAsksAgain() {
        val tooMuch = flag(SymptomStep.MED_MAX_WORDS)
        val dose = ChatLog().wrote(nothing).settle().choseTopic(TopicChoice(Symptom.DOSE), setting).settle()
        val no = dose.wroteRead(nothing, tooMuch).tapOn(SymptomOption.NO)
        assertEquals(SymptomStep.MED_NAME, no.asked())
        val words = no.typedLast(SymptomStep.MED_NAME, "20 таблеток нурофена", setting).settle()
        assertEquals(SymptomStep.MED_MAX_AGAIN, words.asked())
        val read = no.typedLast(SymptomStep.MED_NAME, "нурофен", setting, tooMuch).settle()
        assertEquals(SymptomStep.MED_MAX_AGAIN, read.asked())
        val plain = no.typedLast(SymptomStep.MED_NAME, "нурофен", setting, chatRead()).settle()
        assertTrue(plain.asked() != SymptomStep.MED_MAX_AGAIN)
    }

    /**
     * "Is it there now?" asked for a flag alone is never carried into a chest thread of the sitting:
     * its "no" may be of another pain, and the chest's own thread asks it.
     */
    @Test
    fun aChestOnlyAReadingAskedIsNotCarriedIntoTheChestsThread() {
        val first = ChatLog().wroteRead(nothing, flag(SymptomStep.CHEST_NOW))
        assertEquals(SymptomStep.CHEST_NOW, first.asked())
        val other = first.tapOn(SymptomOption.NO).choseTopic(TopicChoice(Symptom.OTHER), setting).settle()
            .calmToTheEnd()
        assertEquals(ChatMove.Prompt, chatMove(other, setting))
        val chest = other.choseTopic(TopicChoice(Symptom.PAIN), setting).settle().paint(listOf(dabOn("CHEST:L")))
        assertEquals(Symptom.CHEST_PAIN, chest.threads.last().topic)
        assertEquals(SymptomStep.CHEST_NOW, chest.asked())
    }

    /** Written during a question, a flagged message interrupts it as the rules' words do; the question comes after. */
    @Test
    fun duringAQuestionAFlagInterruptsIt() {
        val knee = ChatLog().wrote("болит колено").settle()
        assertEquals(SymptomStep.PAIN_AREA, knee.asked())
        assertEquals(SymptomStep.SYMPTOM, knee.threads.single().report.chat?.askedSteps()?.single())
        val flagged = knee.wroteRead(nothing, flag(SymptomStep.BREATH_NOW))
        assertEquals(SymptomStep.BREATH_NOW, flagged.asked())
        val crisis = knee.wroteRead(nothing, flag(SymptomStep.CRISIS_NOW))
        assertEquals(SymptomStep.CRISIS_NOW, crisis.asked())
        assertEquals(ChatLine.CRISIS_SUPPORT, chatTurn(crisis, setting).lines().first())
        val calm = flagged.tapOn(SymptomOption.NO)
        assertEquals(SymptomStep.PAIN_AREA, calm.asked())
        // Unflagged, the message is a remark, as ever.
        assertEquals(SymptomStep.PAIN_AREA, knee.wroteRead(nothing, chatRead()).asked())
    }

    /**
     * Before the topics, a flag of a crisis on the last message brings where help is with the menu,
     * as the words of one do — the person having said, to the question it asked first, that they have
     * no such thoughts.
     */
    @Test
    fun theTopicTurnBringsWhereHelpIsForAFlaggedCrisis() {
        val first = ChatLog().wroteRead(nothing, flag(SymptomStep.CRISIS_NOW))
            .tapOn(SymptomOption.NO_SUCH_THOUGHTS)
        assertEquals(SymptomStep.SYMPTOM, first.asked())
        assertFalse(ChatLine.CRISIS_SUPPORT in chatTurn(first, setting).lines())
        val again = first.wroteRead(nothing, flag(SymptomStep.CRISIS_NOW))
        assertEquals(SymptomStep.SYMPTOM, again.asked())
        assertTrue(ChatLine.CRISIS_SUPPORT in chatTurn(again, setting).lines())
        assertFalse(ChatLine.CRISIS_SUPPORT in chatTurn(first.wroteRead(nothing, chatRead()), setting).lines())
    }

    /**
     * Written during a question, a flagged message keeps every safety line the rules alone would have
     * met it with: where help is for a crisis only a reading flagged comes first, and
     * the line to call for an emergency that the remark carries stays beside it — and beside a flag's
     * own question for words of English read, where that question brings none of its own. So it is drawn
     * again in the transcript, after the question is answered.
     */
    @Test
    fun aFlaggedMessageKeepsTheRulesLineToCall() {
        val knee = ChatLog().wrote("болит колено").settle()
        assertEquals(SymptomStep.PAIN_AREA, knee.asked())
        assertEquals(listOf(ChatLine.REMARK, ChatLine.SAFETY), chatTurn(knee.wroteRead(nothing, null), setting).lines())
        val crisis = knee.wroteRead(nothing, flag(SymptomStep.CRISIS_NOW))
        val turn = chatTurn(crisis, setting)
        assertEquals(SymptomStep.CRISIS_NOW, turn.asked())
        assertEquals(listOf(ChatLine.CRISIS_SUPPORT, ChatLine.SAFETY), turn.lines())
        val words = "my knee is fine"
        assertEquals(listOf(ChatLine.REMARK, ChatLine.SAFETY), chatTurn(knee.wroteRead(words, null), setting).lines())
        val breath = knee.wroteRead(words, flag(SymptomStep.BREATH_NOW))
        assertEquals(SymptomStep.BREATH_NOW, breath.asked())
        // The rules' safety pieces first, then the flag's own question.
        assertEquals(listOf(ChatLine.SAFETY, ChatLine.WORDS_CHECK), chatTurn(breath, setting).lines())
        // Drawn again once the question is answered: the turn after the message says the same.
        val answered = crisis.tapOn(SymptomOption.NO_SUCH_THOUGHTS)
        val drawn = chatTranscript(answered, setting, live = false).filterIsInstance<ChatItem.Bot>()
            .first { item -> item.turn.pieces.any { it is BotPiece.Ask && it.step == SymptomStep.CRISIS_NOW } }
        assertEquals(listOf(ChatLine.CRISIS_SUPPORT, ChatLine.SAFETY), drawn.turn.lines())
    }

    /**
     * What a reading proposes before the topics may change which reply the words get — its chips, the
     * unwell question — and never takes a safety piece of the rules' own reply away:
     * the line to call for an emergency of "found none it knows" for words read with no topic, and for a
     * medicine stopped, told in a chat of an earlier day, the notes of what stopping can bring.
     */
    @Test
    fun aReadingsTopicsKeepTheRulesSafetyPieces() {
        val split = "голова раскалывается"
        val found = chatTurn(ChatLog().wroteRead(split, null), setting)
        assertEquals(listOf(ChatLine.NO_MATCH, ChatLine.SAFETY), found.lines())
        val pain = ChatLog().wroteRead(split, chatRead { copy(topics = listOf(Symptom.PAIN)) })
        val chips = chatTurn(pain, setting)
        assertEquals(listOf(Symptom.PAIN), (chips.reply as ChatReply.Topics).choices.map { it.topic })
        // The rules' line to call first, the reading's chips after it.
        assertEquals(listOf(ChatLine.SAFETY, ChatLine.MATCH), chips.lines())
        val past = chatSetting(past = true)
        val stopped = "бросила сертралин"
        val rules = chatTurn(ChatLog(past = true).wroteRead(stopped, null, past), past).pieces
            .filter { it.isSafety() }
        assertEquals(3, rules.size)
        val fever = chatRead { copy(topics = listOf(Symptom.FEVER), add = listOf(Symptom.FEVER)) }
        val unwell = chatRead { copy(unwell = true) }
        listOf(fever, unwell).forEach { read ->
            val turn = chatTurn(ChatLog(past = true).wroteRead(stopped, read, past), past)
            assertTrue("$read: ${turn.pieces}", turn.pieces.containsAll(rules))
        }
        assertEquals(
            listOf(Symptom.FEVER),
            (chatTurn(ChatLog(past = true).wroteRead(stopped, fever, past), past).reply as ChatReply.Topics)
                .choices.map { it.topic },
        )
    }

    /**
     * Words typed as an answer that a flag was raised on are a message, as words of an emergency are:
     * their question first, the one they were typed into asked again after. Too much of a medicine where
     * the rules read it in typed words themselves — a name, who prescribed it — goes with the answer
     * instead, as their words do; never in an amount.
     */
    @Test
    fun aFlaggedTypedAnswerIsAMessage() {
        val chat = MedicineChat().start(Symptom.DOSE)
        assertEquals(SymptomStep.MED_NAME, chat.step)
        val at = chat.setting
        val start = chat.log
        val taken = start.typedLast(SymptomStep.MED_NAME, "нурофен", at, chatRead()).settle(at)
        assertTrue(SymptomStep.MED_NAME in taken.threads.last().report.chat?.askedSteps().orEmpty())
        val breath = flag(SymptomStep.BREATH_NOW)
        val flagged = start.typedLast(SymptomStep.MED_NAME, "нурофен", at, breath).settle(at)
        assertEquals(SymptomStep.BREATH_NOW, flagged.asked(at))
        assertEquals(ChatTurn.Wrote("нурофен", breath), flagged.threads.last().report.chat?.turns?.last())
        assertEquals(SymptomStep.MED_NAME, flagged.tapOn(SymptomOption.NO, at).asked(at))
        // Too much of a medicine is read in a name as the rules read typed names, in a thread of no
        // message too: the name is taken, its reading kept with it, and "more than it says?" asked next.
        val tooMuch = start.typedLast(SymptomStep.MED_NAME, "нурофен", at, flag(SymptomStep.MED_MAX_WORDS)).settle(at)
        assertEquals("нурофен", tooMuch.threads.last().report.values[SymptomStep.MED_NAME])
        val answer = tooMuch.threads.last().report.chat?.turns?.last { it is ChatTurn.Asked } as ChatTurn.Asked
        assertEquals(flag(SymptomStep.MED_MAX_WORDS), answer.read)
        assertEquals(SymptomStep.MED_MAX_WORDS, tooMuch.asked(at))
        val words = start.typedLast(SymptomStep.MED_NAME, "20 таблеток нурофена", at).settle(at)
        assertEquals(words.asked(at), tooMuch.asked(at))
        // In the shadow, nothing.
        val shadow = chatRead { copy(shadow = true, safety = listOf(SymptomStep.BREATH_NOW)) }
        assertEquals(taken, start.typedLast(SymptomStep.MED_NAME, "нурофен", at, shadow).settle(at))
        // An amount is never read for too much: the dose's own "more than it says?" follows it.
        chat.log = taken
        chat.value("NOW", SymptomStep.MED_WHEN)
        assertEquals(SymptomStep.MED_AMOUNT, chat.step)
        val amount = chat.log.typedLast(SymptomStep.MED_AMOUNT, "20 tablets", at, flag(SymptomStep.MED_MAX_WORDS))
        assertTrue(SymptomStep.MED_AMOUNT in amount.threads.last().report.chat?.askedSteps().orEmpty())
    }

    /**
     * A name typed in a thread of words, whose reading flagged too much of a medicine, is taken as the
     * name and asks "more than it says?" next — as the rules' words of too much in a typed name do — its
     * reading kept with the answer in the payload, and the rules' own question asked as ever beside it.
     */
    @Test
    fun aFlagOfTooMuchInATypedNameAsksAfterIt() {
        val dose = ChatLog().wrote(nothing).settle().choseTopic(TopicChoice(Symptom.DOSE), setting).settle()
        assertEquals(SymptomStep.MED_NAME, dose.asked())
        val plain = dose.typedLast(SymptomStep.MED_NAME, "нурофен", setting, chatRead()).settle()
        assertTrue(SymptomStep.MED_NAME in plain.threads.last().report.chat?.askedSteps().orEmpty())
        assertTrue(plain.asked() != SymptomStep.MED_MAX_WORDS)
        val tooMuch = flag(SymptomStep.MED_MAX_WORDS)
        val flagged = dose.typedLast(SymptomStep.MED_NAME, "нурофен", setting, tooMuch).settle()
        val chat = flagged.threads.last().report.chat!!
        assertEquals(ChatTurn.Asked(SymptomStep.MED_NAME, read = tooMuch), chat.turns.last { it is ChatTurn.Asked })
        assertEquals(SymptomStep.MED_MAX_WORDS, flagged.asked())
        assertEquals(flagged, decodeChatLog(encodeChatLog(flagged, pending = false)))
        // The rules' words of too much in the name ask it with no reading at all.
        val words = dose.typedLast(SymptomStep.MED_NAME, "20 таблеток нурофена", setting).settle()
        assertEquals(SymptomStep.MED_MAX_WORDS, words.asked())
    }

    /**
     * The same words typed again and read the same are the answer the second time: a
     * flag counts only while its question is still to come in the thread, so a false one never turns
     * every answer typed into a message for ever.
     */
    @Test
    fun aFlagWhoseQuestionWasAskedNeverEatsTheAnswerAgain() {
        val chat = MedicineChat().start(Symptom.DOSE)
        val at = chat.setting
        val breath = flag(SymptomStep.BREATH_NOW)
        val first = chat.log.typedLast(SymptomStep.MED_NAME, "нурофен", at, breath).settle(at)
        assertEquals(SymptomStep.BREATH_NOW, first.asked(at))
        val asked = first.tapOn(SymptomOption.NO, at)
        assertEquals(SymptomStep.MED_NAME, asked.asked(at))
        val again = asked.typedLast(SymptomStep.MED_NAME, "нурофен", at, breath).settle(at)
        assertEquals("нурофен", again.threads.last().report.values[SymptomStep.MED_NAME])
        assertTrue(again.asked(at) != SymptomStep.MED_NAME)
        // The rules' words of an emergency are a message every time, as ever.
        val words = asked.typedLast(SymptomStep.MED_NAME, "не могу дышать", at, breath).settle(at)
        assertTrue(words.threads.last().report.values[SymptomStep.MED_NAME] == null)
    }

    /** A message a reading flagged for too much of a medicine asks "more than it says?", and once more after a "no". */
    @Test
    fun aFlagOfTooMuchAsksTheWordsOwnQuestion() {
        val tooMuch = flag(SymptomStep.MED_MAX_WORDS)
        val first = ChatLog().wroteRead(nothing, tooMuch)
        assertEquals(SymptomStep.MED_MAX_WORDS, first.asked())
        val no = first.tapOn(SymptomOption.NO)
        assertEquals(SymptomStep.SYMPTOM, no.asked())
        val again = no.wroteRead(nothing, tooMuch)
        assertEquals(SymptomStep.MED_MAX_AGAIN, again.asked())
        assertEquals(SymptomStep.SYMPTOM, no.wroteRead(nothing, chatRead()).asked())
    }

    /** The cards under a note, by their words. */
    private fun cards(note: String, read: ChatRead?): List<Int> = noteSafety(note, read).map { it.first }

    /** The note after the end: where help is under it for a flag of its reading, as for its words. */
    @Test
    fun aFlaggedNoteBringsWhereHelpIs() {
        assertTrue(cards(nothing, null).isEmpty())
        assertTrue(cards(nothing, chatRead()).isEmpty())
        assertEquals(listOf(ChatLine.CRISIS_SUPPORT.textRes), cards(nothing, flag(SymptomStep.CRISIS_NOW)))
        READ_SAFETY_STEPS.filter { it != SymptomStep.CRISIS_NOW && it != SymptomStep.MED_MAX_WORDS }.forEach { step ->
            assertEquals(step.name, listOf(ChatLine.SAFETY.textRes), cards(nothing, flag(step)))
        }
        assertEquals(listOf(R.string.med_chat_alert_too_much), cards(nothing, flag(SymptomStep.MED_MAX_WORDS)))
        assertTrue(cards(nothing, chatRead { copy(shadow = true, safety = listOf(SymptomStep.CRISIS_NOW)) }).isEmpty())
        // The rules' words still bring theirs, whatever the reading.
        assertEquals(listOf(ChatLine.CRISIS_SUPPORT.textRes), cards("не хочу жить", chatRead()))
    }

    /**
     * A flag on the note never puts its card where the rules' words put theirs: the
     * card the words call for comes first, the flag's beside it when it is another, and one card the
     * two call for alike is shown once. The note is the chat's last word — no question follows it.
     */
    @Test
    fun aFlagOnTheNoteNeverTakesTheRulesCardAway() {
        val tooMuch = R.string.med_chat_alert_too_much
        val pills = "выпила все таблетки"
        assertEquals(listOf(tooMuch), cards(pills, null))
        assertEquals(listOf(tooMuch, ChatLine.SAFETY.textRes), cards(pills, flag(SymptomStep.BREATH_NOW)))
        assertEquals(listOf(tooMuch, ChatLine.CRISIS_SUPPORT.textRes), cards(pills, flag(SymptomStep.CRISIS_NOW)))
        val air = "не могу дышать"
        assertEquals(
            listOf(ChatLine.SAFETY.textRes, ChatLine.CRISIS_SUPPORT.textRes),
            cards(air, flag(SymptomStep.CRISIS_NOW)),
        )
        assertEquals(listOf(ChatLine.SAFETY.textRes), cards(air, flag(SymptomStep.BREATH_NOW)))
        assertEquals(listOf(tooMuch), cards(pills, flag(SymptomStep.MED_MAX_WORDS)))
    }

    /**
     * The safety card stays: a message of another language a reading proposed topics for and flagged
     * nothing in keeps the line to call for an emergency beside the chips, as the rules' reading of it
     * — no English or Russian word read — would have it; the flag never touches the language.
     */
    @Test
    fun theLineToCallStaysForWordsTheRulesDidNotRead() {
        val read = chatRead { copy(topics = listOf(Symptom.NAUSEA, Symptom.TIREDNESS)) }
        val log = ChatLog().wroteRead("je me sens mal", read, other)
        val turn = chatTurn(log, other)
        assertTrue(turn.lines().toString(), ChatLine.SAFETY in turn.lines())
        assertEquals(
            listOf(Symptom.NAUSEA, Symptom.TIREDNESS),
            (turn.reply as ChatReply.Topics).choices.map {
                it.topic
            },
        )
        assertFalse(chatReader.read(messagesOf(log.threads.single().report.chat), other).language)
    }

    /** A flagged crisis keeps the first words out of the entry's title, as the words of one do. */
    @Test
    fun aFlaggedCrisisNamesNoEntryAfterItsWords() {
        val read = flag(SymptomStep.CRISIS_NOW)
        val report =
            SymptomReport(symptom = Symptom.OTHER, chat = SymptomChat(listOf(ChatTurn.Wrote("grey days again", read))))
        assertEquals("Something else", chatTitle(report) { "Something else" })
        val plain = report.copy(chat = SymptomChat(listOf(ChatTurn.Wrote("grey days again"))))
        assertEquals("grey days again", chatTitle(plain) { "Something else" })
    }

    /** A reading in the shadow, or one with no model behind it, asks nothing. */
    @Test
    fun aReadingTheChatDoesNotRouteByAsksNothing() {
        val shadow = chatRead { copy(shadow = true, safety = READ_SAFETY_STEPS) }
        assertEquals(SymptomStep.SYMPTOM, ChatLog().wroteRead(nothing, shadow).asked())
        val timeout = ChatRead(why = "timeout", safety = READ_SAFETY_STEPS)
        assertEquals(SymptomStep.SYMPTOM, ChatLog().wroteRead(nothing, timeout).asked())
    }
}
