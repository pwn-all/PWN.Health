package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The chat's questions: which are asked, in what order, when the follow-ups come, and that the chat
 * always ends — nothing is ever answered for the person, and no question is asked twice.
 */
class SymptomChatFlowTest {
    private val day = LocalDate.of(2026, 10, 5)

    /**
     * The opening is the router's: "Hello", the question, and the whole menu. The four
     * record topics — the pain's, the mood's, the dose's and the course's — come first; then every
     * symptom topic, the body first, then feelings, then something else.
     */
    @Test
    fun theOpeningAsksForATopicBodyFirstThenFeelingsThenSomethingElse() {
        val turn = openingTurn(ChatLog(), chatSetting())
        assertEquals(listOf(ChatLine.OPEN, ChatLine.OPENING), turn.lines())
        val reply = turn.reply as ChatReply.Topics
        assertTrue(reply.all)
        // The menu is every topic offered, in its order, and the four records lead it, as the
        // records' group does.
        val menu = reply.choices.map { it.topic }
        assertEquals(TOPIC_ORDER.filter { it.script.offered(chatSetting()) }, menu)
        assertEquals(listOf(Symptom.PAIN, Symptom.MOOD, Symptom.DOSE, Symptom.COURSE), menu.take(4))
        val topics = menu.drop(4)
        assertEquals(TOPIC_ORDER.filter { it in topics }, topics)
        assertEquals(Symptom.entries.filter { it.record == ChatRecord.SYMPTOM }.toSet(), topics.toSet())
        val firstMind = topics.indexOfFirst { it.mind }
        assertTrue(topics.take(firstMind).none { it.mind })
        assertTrue(topics.drop(firstMind).dropLast(1).all { it.mind })
        assertEquals(Symptom.OTHER, topics.last())
    }

    /** Every page the questions come from is written for adults: a child's profile, or none, is told so. */
    @Test
    fun aProfileThatIsNotAnAdultsIsToldTheQuestionsAreForAdults() {
        val state = chatState(newChat(), adult = false)
        assertTrue(ChatLine.ADULTS in turnBefore(state, nextChatStep(state)).lines())
        assertTrue(ChatLine.ADULTS in openingTurn(ChatLog(), chatSetting(adult = false)).lines())
        assertFalse(ChatLine.ADULTS in openingTurn(ChatLog(), chatSetting()).lines())
    }

    /**
     * The questions each topic asks on its calmest path, red flags first. Low mood asks about safety
     * as soon as it has said how long, not at the end.
     */
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

    @Test
    fun eachTopicAsksItsOwnQuestionsRedFlagsFirst() {
        golden.forEach { (topic, steps) ->
            assertEquals(topic.name, steps.split(' ').map(SymptomStep::valueOf), driveChat(topic).first)
            assertEquals(topic.name, driveChat(topic).first, driveChat(topic, female = true).first)
        }
    }

    @Test
    fun pregnancyIsAmongTheCausesForAFemaleProfileOnly() {
        assertTrue(SymptomOption.PREGNANCY in chatOptions(Symptom.VOMITING, SymptomStep.CAUSE, female = true))
        assertFalse(SymptomOption.PREGNANCY in chatOptions(Symptom.VOMITING, SymptomStep.CAUSE, female = false))
        assertFalse(SymptomOption.PREGNANCY in chatOptions(Symptom.NAUSEA, SymptomStep.CAUSE, female = false))
    }

    /**
     * On every NHS page the call-999 list comes first, so in every topic of
     * the body the question right after the topic has an answer that calls for emergency services.
     * Tiredness is the one whose page has no such list.
     */
    @Test
    fun everyBodyTopicAsksItsEmergencyQuestionFirst() {
        // The symptom topics. A record topic (pain, mood, a dose, a course) asks its own red flags
        // in its own order, and its own tests hold it to them.
        Symptom.entries.filter {
            it.record == ChatRecord.SYMPTOM && !it.mind && it != Symptom.TIREDNESS
        }.forEach { topic ->
            val chosen = newChat().pick(topic)
            val step = checkNotNull(chosen.nextStep(), { topic.name })
            val options = chatOptions(topic, step, female = false)
            assertTrue(
                "${topic.name} asks $step first",
                options.any { option ->
                    firedRules(chosen.say(step, option).asTold()).any { it.alert.urgency == SymptomUrgency.EMERGENCY }
                },
            )
        }
    }

    @Test
    fun howOftenFollowsOnlyComesAndGoesAndHowLongEachTimeOnlyBouts() {
        val dizzy = newChat().pick(Symptom.DIZZINESS)
            .say(SymptomStep.WITH, SymptomOption.NONE)
            .say(SymptomStep.ONSET, SymptomOption.STARTED_TODAY)
        assertEquals(SymptomStep.PATTERN, dizzy.nextStep())
        assertEquals(SymptomStep.DOING, dizzy.say(SymptomStep.PATTERN, SymptomOption.ALL_THE_TIME).nextStep())
        val onAndOff = dizzy.say(SymptomStep.PATTERN, SymptomOption.COMES_AND_GOES)
        assertEquals(SymptomStep.HOW_OFTEN, onAndOff.nextStep())
        assertEquals(SymptomStep.DURATION, onAndOff.say(SymptomStep.HOW_OFTEN, SymptomOption.DAILY).nextStep())
        val once = dizzy.say(SymptomStep.PATTERN, SymptomOption.JUST_ONCE)
        assertEquals(SymptomStep.DURATION, once.nextStep())
        assertEquals(
            R.string.symptom_ask_duration_once,
            chatQuestionRes(Symptom.DIZZINESS, SymptomStep.DURATION, once.asTold()),
        )
        assertEquals(
            R.string.symptom_ask_duration,
            chatQuestionRes(Symptom.DIZZINESS, SymptomStep.DURATION, onAndOff.asTold()),
        )
        // Nausea asks how often too, but never how long each bout lasts.
        val nausea = newChat().pick(Symptom.NAUSEA).say(SymptomStep.WITH, SymptomOption.NONE)
            .say(SymptomStep.ONSET, SymptomOption.STARTED_TODAY)
            .say(SymptomStep.PATTERN, SymptomOption.COMES_AND_GOES)
        assertEquals(SymptomStep.HOW_OFTEN, nausea.nextStep())
        assertEquals(SymptomStep.AFTER_MEAL, nausea.say(SymptomStep.HOW_OFTEN, SymptomOption.DAILY).nextStep())
    }

    private fun row(
        topic: Symptom,
        daysAgo: Long,
        id: Long,
        onset: SymptomOption? = SymptomOption.STARTED_TODAY,
    ): JournalEntry {
        val zone = ZoneId.systemDefault()
        val at = day.minusDays(daysAgo).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val answers = onset?.let { mapOf(SymptomStep.ONSET to listOf(it)) }.orEmpty()
        return JournalEntry(
            id = id,
            day = day.minusDays(daysAgo).toString(),
            occurredAt = at,
            kind = KIND_SYMPTOM,
            title = "",
            calories = 0.0,
            measurementPayloadJson = encodeSymptomPayload(SymptomReport(topic, answers, chat = SymptomChat())),
        )
    }

    @Test
    fun theSameOrNewIsAskedOnlyAfterASameTopicChatThisWeek() {
        val zone = ZoneId.systemDefault()
        val now = day.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val rows = listOf(row(Symptom.DIZZINESS, 8, 1), row(Symptom.FEVER, 2, 2), row(Symptom.DIZZINESS, 7, 3))
        val earlier = earlierChatFor(Symptom.DIZZINESS, rows, except = null, now = now, zone = zone)
        assertEquals(3L, rows.first { it.occurredAt == earlier?.occurredAt }.id)
        assertEquals(SymptomOption.STARTED_TODAY, earlier?.onset)
        // Eight days back is past the week; the chat being had is never its own earlier one.
        assertNull(earlierChatFor(Symptom.DIZZINESS, rows.take(2), except = null, now = now, zone = zone))
        assertNull(earlierChatFor(Symptom.DIZZINESS, rows, except = 3, now = now, zone = zone))
        assertNull(earlierChatFor(Symptom.NAUSEA, rows, except = null, now = now, zone = zone))

        val chosen = newChat().pick(Symptom.DIZZINESS, earlier)
        assertEquals(SymptomStep.SAME, chosen.nextStep(earlier))
        assertEquals(earlier?.day, chosen.turn(earlier).pieces.filterIsInstance<BotPiece.Ask>().single().day)
        assertEquals(SymptomStep.WITH, newChat().pick(Symptom.DIZZINESS).nextStep())

        val same = chosen.say(SymptomStep.SAME, SymptomOption.SAME_AS_BEFORE, earlier = earlier)
            .say(SymptomStep.WITH, SymptomOption.NONE, earlier = earlier)
        assertEquals(earlier?.occurredAt, same.chat?.sameAs)
        assertEquals(SymptomStep.PATTERN, same.nextStep(earlier))
        val new = chosen.say(SymptomStep.SAME, SymptomOption.SOMETHING_NEW, earlier = earlier)
            .say(SymptomStep.WITH, SymptomOption.NONE, earlier = earlier)
        assertNull(new.chat?.sameAs)
        assertEquals(SymptomStep.ONSET, new.nextStep(earlier))
    }

    @Test
    fun chestPainUnderAnotherTopicIsAskedAboutAtOnce() {
        val other = newChat().pick(Symptom.OTHER)
        assertEquals(SymptomStep.WITH, other.nextStep())
        assertEquals(SymptomStep.ONSET, other.say(SymptomStep.WITH, SymptomOption.NONE).nextStep())
        assertEquals(SymptomStep.CHEST_NOW, other.say(SymptomStep.WITH, SymptomOption.CHEST_PAIN).nextStep())
        val stress = driveChat(Symptom.STRESS) { step, options ->
            if (step == SymptomStep.WITH) listOf(SymptomOption.CHEST_PAIN) else calm(options)
        }
        val withAt = stress.first.indexOf(SymptomStep.WITH)
        assertEquals(SymptomStep.CHEST_NOW, stress.first[withAt + 1])
    }

    @Test
    fun theQuestionAboutSuicideComesStraightAfterTheAnswerThatCallsForIt() {
        fun worry(at: SymptomStep, answer: SymptomOption, severity: SymptomSeverity = SymptomSeverity.MILD) =
            driveChat(Symptom.WORRY, severity = severity) { step, options ->
                if (step == at) listOf(answer) else calm(options)
            }.first
        assertFalse(SymptomStep.CRISIS in worry(SymptomStep.COPING, SymptomOption.MANAGING))
        listOf(
            SymptomStep.COPING to SymptomOption.STRUGGLING,
            SymptomStep.COPING to SymptomOption.NOT_COPING,
            SymptomStep.HOW_LONG to SymptomOption.TWO_WEEKS_PLUS,
            SymptomStep.WITH to SymptomOption.NONE,
        ).forEach { (at, answer) ->
            val steps = worry(at, answer)
            if (answer == SymptomOption.NONE) {
                assertFalse(SymptomStep.CRISIS in steps)
            } else {
                assertEquals("$at $answer", at, steps[steps.indexOf(SymptomStep.CRISIS) - 1])
            }
        }
        val severe = worry(SymptomStep.COPING, SymptomOption.MANAGING, SymptomSeverity.SEVERE)
        assertEquals(SymptomStep.CRISIS, severe.last())
        // Hopelessness ticked under tiredness asks too.
        val tired = driveChat(Symptom.TIREDNESS) { step, options ->
            if (step == SymptomStep.WITH) listOf(SymptomOption.LOW_MOOD) else calm(options)
        }.first
        assertEquals(SymptomStep.CRISIS, tired[tired.indexOf(SymptomStep.WITH) + 1])
    }

    @Test
    fun wordsOfSuicideAskWhetherThePersonIsSafeBeforeAnythingElse() {
        val opening = newChat().write("I want to kill myself")
        assertEquals(SymptomStep.CRISIS_NOW, opening.nextStep())
        val turn = opening.turn()
        // Where help is comes first, before any question, and it is never held back by the dots.
        assertEquals(ChatLine.CRISIS_SUPPORT, (turn.pieces.first() as BotPiece.Line).line)
        assertEquals(SymptomStep.CRISIS_NOW, turn.asked())
        assertTrue(turn.urgent())
        assertEquals(0L, chatTypingMs(200, animationsOn = true, urgent = turn.urgent()))
        // Midway through another topic the same, and the question being asked comes back after.
        val midway = newChat().pick(Symptom.FEVER).write("не хочу жить")
        assertEquals(SymptomStep.CRISIS_NOW, midway.nextStep())
        val safe = midway.say(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW)
        assertEquals(SymptomStep.WITH, safe.nextStep())
        // The flow's own question is not asked again once the words have asked theirs.
        assertFalse(SymptomStep.CRISIS in chatSteps(chatState(safe)))
    }

    /**
     * A crisis told before any topic ends the chat, and is still a row: the filer keeps the
     * answer in the thread's "words" row.
     */
    @Test
    fun aCrisisBeforeAnyTopicEndsTheChatAndStillMakesAnEntry() {
        val unsafe = newChat().write("I took an overdose").say(SymptomStep.CRISIS_NOW, SymptomOption.CRISIS_UNSAFE)
        assertNull(unsafe.nextStep())
        assertTrue(checkNotNull(unsafe.chat).ended)
        assertNull(unsafe.symptom)
        val closing = unsafe.turn()
        assertEquals(listOf(SymptomAlert.EMERGENCY_MIND), closing.alerts())
        assertEquals(ChatReply.End, closing.reply)
        assertTrue(closing.pieces.none { it is BotPiece.Saved })
        assertTrue(closing.offers().isEmpty())
        assertEquals("OTHER", chatTitle(unsafe) { it.name })

        val log = ChatLog().wrote("I took an overdose").settle()
            .answeredLast(chatSetting()) { it.tapped(SymptomStep.CRISIS_NOW, SymptomOption.CRISIS_UNSAFE, null) }
            .settle()
        assertEquals(ChatEnd.STOP, log.end)
        val store = MemoryChatStore()
        val key = chatKey()
        val result = persist(ChatFiling(store, key), log, key)
        val row = store.db.journal.values.single()
        assertEquals(KIND_SYMPTOM, row.kind)
        assertEquals(WORDS_ROLE, linkOf(result, role = WORDS_ROLE).role)
        assertEquals(
            listOf(SymptomOption.CRISIS_UNSAFE),
            decodeSymptomPayload(row.measurementPayloadJson).answers[SymptomStep.CRISIS_NOW],
        )
    }

    @Test
    fun anEmergencyAnswerEndsTheQuestionsAtOnce() {
        listOf(
            newChat().pick(Symptom.VOMITING).say(SymptomStep.BLOOD, SymptomOption.YES),
            newChat().pick(Symptom.CHEST_PAIN).say(SymptomStep.CHEST_NOW, SymptomOption.YES),
            newChat().pick(Symptom.PALPITATIONS).say(SymptomStep.PALP_NOW, SymptomOption.YES),
            newChat().pick(Symptom.LOW_MOOD).say(SymptomStep.ABOUT, SymptomOption.LOSS)
                .say(SymptomStep.HOW_LONG, SymptomOption.FEW_DAYS)
                .say(SymptomStep.CRISIS, SymptomOption.CRISIS_HARMED),
        ).forEach { report ->
            assertNull(report.nextStep())
            assertTrue(checkNotNull(report.chat).ended)
            val closing = report.turn()
            assertEquals(SymptomUrgency.EMERGENCY, closing.alerts().single().urgency)
            assertFalse(ChatOffer.BOOK_VISIT in closing.offers())
            assertFalse(ChatLine.THANKS in closing.lines())
            // Nothing stands between the line to call and the close — no saved record's card, which
            // takes the summary's place in a thread's close.
            assertTrue(closing.pieces.none { it is BotPiece.Saved })
            assertEquals(SymptomUrgency.EMERGENCY, (closing.pieces.first() as BotPiece.Alert).alert.urgency)
            assertEquals(ChatLine.CLOSE_EMERGENCY, (closing.pieces[1] as BotPiece.Line).line)
        }
    }

    @Test
    fun aMessageMidQuestionIsKeptAndTheQuestionAskedAgain() {
        val asked = newChat().pick(Symptom.FEVER).say(SymptomStep.WITH, SymptomOption.NONE)
        assertEquals(SymptomStep.ONSET, asked.nextStep())
        val remark = asked.write("shivering since lunch").turn()
        assertEquals(listOf(ChatLine.REMARK, ChatLine.SAFETY), remark.lines())
        assertEquals(SymptomStep.ONSET, remark.asked())
        // A pending multi answer keeps its ticks under a message.
        val ticking = newChat().pick(Symptom.FEVER)
        val ticked = ticking.copy(answers = mapOf(SymptomStep.WITH to listOf(SymptomOption.CHILLS))).wrote("and sweaty")
        assertEquals(listOf(SymptomOption.CHILLS), ticked.answers[SymptomStep.WITH])
        assertEquals(SymptomStep.WITH, ticked.nextStep())
    }

    /** Words of an emergency bring their own question, at once, before or within a topic. */
    @Test
    fun wordsOfAnEmergencyBringItsQuestion() {
        val words = newChat().write("My chest really hurts and my arm is numb")
        assertEquals(SymptomStep.CHEST_NOW, words.nextStep())
        assertEquals(listOf(ChatLine.WORDS_CHECK), words.turn().lines())
        val noPain = words.say(SymptomStep.CHEST_NOW, SymptomOption.NO)
        assertEquals(SymptomStep.STROKE_SIGNS, noPain.nextStep())
        assertNull(noPain.say(SymptomStep.STROKE_SIGNS, SymptomOption.YES).nextStep())
        // Then the topics it named.
        val topics = noPain.say(SymptomStep.STROKE_SIGNS, SymptomOption.NO)
        assertEquals(SymptomStep.SYMPTOM, topics.nextStep())
        assertEquals(
            listOf(Symptom.CHEST_PAIN, Symptom.OTHER),
            (topics.turn().reply as ChatReply.Topics).choices.map { it.topic },
        )
        // Chest pain's own first question was asked already and is not asked twice.
        assertEquals(SymptomStep.WITH, topics.pick(Symptom.CHEST_PAIN).nextStep())
        // In a chat the router takes the chest up itself, with no chip: words about the chest are
        // never left to a tap. Its "is it there now?" stands answered.
        val routed = ChatLog(threads = listOf(ChatThread(0, topics)), next = 1).settle().threads.single()
        assertEquals(Symptom.CHEST_PAIN, routed.topic)
        assertEquals(ROUTE_WORDS, routed.from)
        val within = newChat().pick(Symptom.TIREDNESS).write("I can't breathe")
        assertEquals(SymptomStep.BREATH_NOW, within.nextStep())
    }

    /**
     * Words before a topic offer the topics they name: in a chat the router's chips, with "Another
     * topic" as the way out in place of "something else". The pain map is no offer:
     * words about the chest bring the chest's own questions first, and a pain anywhere else is the pain
     * topic's to take up: "my knee hurts" begins the pain thread at the knee's chips.
     */
    @Test
    fun wordsBeforeATopicOfferTheTopicsTheyNameAndTheChestItsQuestionsFirst() {
        val sick = chatTurn(ChatLog().wrote("меня тошнит и кружится голова").settle(), chatSetting())
        val chips = sick.reply as ChatReply.Topics
        assertEquals(listOf(Symptom.NAUSEA, Symptom.DIZZINESS), chips.choices.map { it.topic })
        assertTrue(chips.exit)
        assertEquals(listOf(ChatLine.MATCH), sick.lines())
        val unknown = chatTurn(ChatLog().wrote("ich will nicht mehr").settle(), chatSetting())
        // The whole menu stands once, at the opening: after words it is a tap away.
        assertEquals(ChatReply.Topics(emptyList(), all = false), unknown.reply)
        val menu = chatTurn(ChatLog(), chatSetting()).reply as ChatReply.Topics
        assertTrue(menu.all)
        // The menu holds every topic offered, the four records among them.
        assertEquals(TOPIC_ORDER.filter { it.script.offered(chatSetting()) }, menu.choices.map { it.topic })
        assertTrue(
            menu.choices.map { it.topic }.containsAll(listOf(Symptom.PAIN, Symptom.MOOD, Symptom.DOSE, Symptom.COURSE)),
        )
        // Words the chat cannot read are not met as if they had been read.
        assertEquals(listOf(ChatLine.NO_MATCH, ChatLine.SAFETY), unknown.lines())

        val chest = ChatLog().wrote("my chest hurts").settle()
        assertEquals(SymptomStep.CHEST_NOW, (chatMove(chest, chatSetting()) as ChatMove.Ask).step)
        val routed = chest.answeredLast(chatSetting()) {
            it.tapped(SymptomStep.CHEST_NOW, SymptomOption.NO, null)
        }.settle()
        assertEquals(Symptom.CHEST_PAIN, routed.threads.single().topic)
        assertEquals(ChatLine.CHEST_WORDS, chatTurn(routed, chatSetting()).lines().first())
        // The knee is the one record the words named, so
        // the chat takes the pain up at the knee's chips, both sides offered and "Another topic" beside.
        val kneeLog = ChatLog().wrote("my knee hurts").settle()
        assertEquals(Symptom.PAIN to "PAIN:KNEE", kneeLog.threads.single().let { it.topic to it.from })
        val knee = chatTurn(kneeLog, chatSetting())
        val areaChips = knee.reply as ChatReply.AreaChips
        assertEquals(SymptomStep.PAIN_AREA, areaChips.step)
        assertEquals(
            listOf("KNEE:R", "KNEE:L", "KNEE:L,KNEE:R"),
            areaChips.choices.map { choice -> choice.areas.joinToString(",") { it.code } },
        )
        assertTrue(areaChips.exit)
        assertEquals(listOf(ChatLine.PAIN_START), knee.lines())
    }

    @Test
    fun anAnswerTakenBackTakesWhatCameAfterItWithIt() {
        val told = newChat().pick(Symptom.FEVER)
            .say(SymptomStep.WITH, SymptomOption.CHILLS, SymptomOption.COUGH)
            .say(SymptomStep.ONSET, SymptomOption.STARTED_YESTERDAY)
            .rate(SymptomSeverity.MODERATE)
            .say(SymptomStep.TREND, SymptomOption.NO_CHANGE)
        val turns = checkNotNull(told.chat).turns
        val withAt = turns.indexOfFirst { (it as? ChatTurn.Asked)?.step == SymptomStep.WITH }
        val back = rewoundTo(told, withAt)
        assertEquals(turns.take(withAt), back.chat?.turns)
        assertEquals(listOf(SymptomOption.CHILLS, SymptomOption.COUGH), back.answers[SymptomStep.WITH])
        assertNull(back.answers[SymptomStep.ONSET])
        assertNull(back.severity)
        assertFalse(checkNotNull(back.chat).ended)
        assertEquals(SymptomStep.WITH, back.nextStep())
        // The topic taken back takes everything after it.
        val topic = rewoundTo(told, 0)
        assertTrue(topic.answers.isEmpty())
        assertNull(topic.asTold().symptom)
        assertEquals(SymptomStep.SYMPTOM, topic.nextStep())
        // A message has no pencil.
        val wrote = told.wrote("text")
        assertEquals(wrote, rewoundTo(wrote, checkNotNull(wrote.chat).turns.lastIndex))
    }

    /**
     * The pencil tapped while ticks wait on the clock: the ticks go with the rewind, and a clock
     * that outlived them files nothing — the call-999 question is asked again after the answer
     * changed, never skipped as if it had been answered blank.
     */
    @Test
    fun aClockThatOutlivedItsTicksFilesNothing() {
        val blood = newChat().pick(Symptom.VOMITING).say(SymptomStep.BLOOD, SymptomOption.NO)
        val ticking = blood.copy(answers = blood.answers + (SymptomStep.WITH to listOf(SymptomOption.CANT_KEEP_FLUIDS)))
        assertEquals(SymptomStep.WITH, ticking.nextStep())
        val turns = checkNotNull(ticking.chat).turns
        val bloodAt = turns.indexOfFirst { (it as? ChatTurn.Asked)?.step == SymptomStep.BLOOD }
        val back = rewoundTo(ticking, bloodAt)
        assertNull(back.answers[SymptomStep.WITH])
        // Taken late, by the clock or by the page leaving: nothing is asked, nothing changes.
        assertEquals(back, back.answered(SymptomStep.WITH))
        assertEquals(back, back.ticksTaken(SymptomStep.WITH, chatState(back)))
        val again = back.say(SymptomStep.BLOOD, SymptomOption.NO)
        assertEquals(SymptomStep.WITH, again.nextStep())
        // And while the question is waiting, its ticks are its answer.
        val taken = ticking.ticksTaken(SymptomStep.WITH, chatState(ticking))
        assertTrue(SymptomStep.WITH in checkNotNull(taken.chat).askedSteps())
    }

    /**
     * A message sent while ticks wait: the ticks and their question wait on under it, and are taken
     * by the clock as the answer — unless the message's own words asked something first.
     */
    @Test
    fun ticksWaitUnderAMessage() {
        val about = newChat().pick(Symptom.WORRY)
        val ticked = about.copy(answers = mapOf(SymptomStep.ABOUT to listOf(SymptomOption.MONEY))).write("and my boss")
        assertEquals(SymptomStep.ABOUT, ticked.nextStep())
        val more = ticked.copy(
            answers = mapOf(SymptomStep.ABOUT to listOf(SymptomOption.WORK_STUDY, SymptomOption.MONEY)),
        )
        val taken = more.ticksTaken(SymptomStep.ABOUT, chatState(more))
        assertEquals(listOf(SymptomOption.WORK_STUDY, SymptomOption.MONEY), taken.forSave().answers[SymptomStep.ABOUT])
        val words = about.copy(answers = mapOf(SymptomStep.ABOUT to listOf(SymptomOption.MONEY)))
            .write("I want to die")
        assertEquals(SymptomStep.CRISIS_NOW, words.nextStep())
        assertEquals(words, words.ticksTaken(SymptomStep.ABOUT, chatState(words)))
        assertEquals(listOf(SymptomOption.MONEY), words.answers[SymptomStep.ABOUT])
    }

    /** An answer taken back keeps every word written after it, and the question those words ask. */
    @Test
    fun anAnswerTakenBackKeepsEveryMessage() {
        val told = newChat().pick(Symptom.FEVER)
            .say(SymptomStep.WITH, SymptomOption.NONE)
            .say(SymptomStep.ONSET, SymptomOption.STARTED_YESTERDAY)
            .write("I have had a rash on my legs since Monday and I can’t go on")
            .say(SymptomStep.CRISIS_NOW, SymptomOption.RATHER_NOT_SAY)
            .write("it is itchy too")
        val onsetAt = checkNotNull(told.chat).turns.indexOfFirst { (it as? ChatTurn.Asked)?.step == SymptomStep.ONSET }
        val back = rewoundTo(told, onsetAt)
        val wrote = checkNotNull(told.chat).turns.filterIsInstance<ChatTurn.Wrote>()
        assertEquals(wrote, checkNotNull(back.chat).turns.filterIsInstance<ChatTurn.Wrote>())
        assertFalse(SymptomStep.CRISIS_NOW in checkNotNull(back.chat).askedSteps())
        // The words of suicide still ask whether the person is safe, before the answer being changed.
        assertEquals(SymptomStep.CRISIS_NOW, back.nextStep())
        val safe = back.say(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW)
        assertEquals(SymptomStep.ONSET, safe.nextStep())
        // The transcript draws each message as it was written.
        val people = chatTranscript(
            ChatLog(threads = listOf(ChatThread(0, back)), next = 1),
            chatSetting(),
            live = true,
        )
            .filterIsInstance<ChatItem.Person>()
        assertEquals(wrote.map { it.text }, people.mapNotNull { it.text })
    }

    /**
     * Words that matched a key by mistake ("this headache will kill me") are met with the question
     * whether the person is safe, and it has its "no": then nothing about suicide is filed or said,
     * and the chat ends as any other — with the visit to plan where a doctor is called for.
     */
    @Test
    fun theSafetyQuestionTheWordsAskHasItsNo() {
        val words = newChat().pick(Symptom.FEVER).write("This headache will kill me")
        assertEquals(SymptomStep.CRISIS_NOW, words.nextStep())
        val reply = words.turn().reply as ChatReply.Choose
        assertEquals(SymptomOption.NO_SUCH_THOUGHTS, reply.options.first())
        val no = words.say(SymptomStep.CRISIS_NOW, SymptomOption.NO_SUCH_THOUGHTS)
        assertFalse(crisisAnswered(no.asTold()))
        assertTrue(firedRules(no.asTold()).isEmpty())
        assertEquals(SymptomStep.WITH, no.nextStep())
        val ended = no.say(SymptomStep.WITH, SymptomOption.NONE)
            .say(SymptomStep.ONSET, SymptomOption.STARTED_YESTERDAY)
            .say(SymptomStep.TREND, SymptomOption.GETTING_WORSE)
            .rate(SymptomSeverity.MILD)
        assertNull(ended.nextStep())
        val closing = ended.turn()
        assertTrue(ChatLine.CLOSE_TODAY in closing.lines())
        assertFalse(ChatLine.CLOSE_CRISIS in closing.lines())
        assertEquals(listOf(ChatOffer.BOOK_VISIT), closing.offers())
        assertTrue(BotPiece.Saved(0) in closing.pieces)
        // The thanks is the end of the whole chat, the router's, after "That is all for now".
        assertFalse(ChatLine.THANKS in closing.lines())
        val log = ChatLog(threads = listOf(ChatThread(0, ended)), next = 1).settle().endedByPerson()
        assertEquals(listOf(ChatLine.THANKS), chatTurn(log, chatSetting()).lines())
    }

    /** Nothing is answered for the person: the next turn is worked out from the state and changes nothing in it. */
    @Test
    fun everyTopicEndsWithEveryAnswerAndAsksEachQuestionOnce() {
        Symptom.entries.forEach { topic ->
            val state = chatState(newChat().pick(topic))
            val before = state.copy()
            runBlocking { symptomResponder.next(state) }
            assertEquals(before, state)
            (0 until 12).forEach { index ->
                SymptomSeverity.entries.forEach { severity ->
                    val (steps, report) = driveChat(topic, female = index % 2 == 0, severity = severity) { _, options ->
                        listOf(options[index % options.size])
                    }
                    assertEquals("$topic $index $severity", steps.size, steps.toSet().size)
                    assertTrue(checkNotNull(report.chat).ended)
                    assertEquals(ChatReply.End, report.turn().reply)
                }
            }
        }
    }

    @Test
    fun theDotsStandLongEnoughToReadAsTypingAndNoLonger() {
        assertEquals(0L, chatTypingMs(500, animationsOn = true, urgent = true))
        assertEquals(400L, chatTypingMs(500, animationsOn = false, urgent = false))
        assertEquals(700L, chatTypingMs(5, animationsOn = true, urgent = false))
        assertEquals(1_100L, chatTypingMs(40, animationsOn = true, urgent = false))
        assertEquals(1_800L, chatTypingMs(500, animationsOn = true, urgent = false))
    }

    /** A safety line is the lead itself, never followed by "Got it"; a feeling gets thanks every other answer. */
    @Test
    fun noShortThanksAfterASafetyLine() {
        val faint = newChat().pick(Symptom.FAINTING).turn()
        assertEquals(listOf(SymptomAlert.DOCTOR), faint.alerts())
        val afterAlert = newChat().pick(Symptom.NAUSEA).say(SymptomStep.WITH, SymptomOption.NONE)
            .say(SymptomStep.ONSET, SymptomOption.STARTED_TODAY)
            .say(SymptomStep.PATTERN, SymptomOption.COMES_AND_GOES).turn()
        assertEquals(listOf(SymptomAlert.DOCTOR), afterAlert.alerts())
        assertTrue(afterAlert.lines().none { it in listOf(ChatLine.ACK_1, ChatLine.ACK_2, ChatLine.ACK_3) })
        val worry = newChat().pick(Symptom.WORRY).say(SymptomStep.ABOUT, SymptomOption.WORK_STUDY)
        val acks = listOf(
            worry.turn().lines(),
            worry.say(SymptomStep.HOW_LONG, SymptomOption.FEW_DAYS).turn().lines(),
        )
        assertEquals(1, acks.count { ChatLine.ACK_MIND in it })
        val hopeless = newChat().pick(Symptom.LOW_MOOD).say(SymptomStep.ABOUT, SymptomOption.LOSS)
            .say(SymptomStep.HOW_LONG, SymptomOption.FEW_DAYS)
            .say(SymptomStep.CRISIS, SymptomOption.NO)
            .say(SymptomStep.AFFECTS, SymptomOption.SLEEP)
            .say(SymptomStep.COPING, SymptomOption.NOT_COPING)
        assertTrue(ChatLine.HEAVY in hopeless.turn().lines())
    }
}
