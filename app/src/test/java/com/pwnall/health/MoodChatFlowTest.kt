package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mood thread as it is told: how it starts, the dial that gives the face and nothing else, what
 * it is about by the face, the card after the dial, the close by how the mood ended, and the one
 * check a good mood can bring.
 */
class MoodChatFlowTest {
    private fun ChatLog.topics(setting: ChatSetting = chatSetting(past = past)) =
        (chatTurn(this, setting).reply as ChatReply.Topics).choices.map { it.topic }

    /** Words, a low mood's weight, an earlier answer: none of them ever sets the face. */
    @Test
    fun theScoreComesOnlyFromTheDial() {
        val words = MoodChat()
        words.write("I feel amazing today")
        assertEquals(SymptomStep.MOOD_DIAL, words.asked())
        val dial = words.turn().reply as ChatReply.MoodDial
        assertNull(dial.start)
        assertTrue(dial.exit)
        assertFalse(SymptomStep.MOOD_DIAL in words.thread().report.values)
        assertTrue(words.moods().isEmpty())
        words.dial(2)
        assertEquals(listOf(2), words.moods().map { it.moodScore })

        // A low mood talk told as severe, then the mood: the dial still rests on the middle face.
        val talk = MoodChat()
        talk.choose(Symptom.LOW_MOOD)
        talk.talkThrough(mapOf(SymptomStep.ABOUT to listOf(SymptomOption.MONEY)), SymptomSeverity.SEVERE)
        talk.choose(Symptom.MOOD)
        assertNull((talk.turn().reply as ChatReply.MoodDial).start)
        assertFalse(SymptomStep.MOOD_DIAL in talk.thread().report.values)
        talk.dial(4)
        assertEquals(listOf(4), talk.moods().map { it.moodScore })
    }

    @Test
    fun aGoodMoodForNoReasonIsSavedAndAskedNothingMore() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(5)
        val reason = chat.turn()
        assertEquals(listOf(R.string.chat_mood_ack_good), reason.notes())
        assertEquals(R.string.chat_mood_ask_about_good, MoodScript.question(chat.state(), SymptomStep.MOOD_ABOUT))
        // "No clear reason" tapped alone is taken on the tap.
        val (taken, now) = chat.log.tickedLast(SymptomStep.MOOD_ABOUT, SymptomOption.NO_CLEAR_REASON, chat.setting())
        assertTrue(now)
        chat.commit(taken)
        assertEquals(ChatMove.Prompt, chat.move())
        val close = chat.close()
        assertEquals(listOf(R.string.chat_mood_no_reason_good, R.string.chat_mood_close_good), close.notes())
        assertTrue(close.none { it is BotPiece.Offer || it is BotPiece.Alert || it is BotPiece.Line })
        assertTrue(MoodScript.followUps(chat.state()).isEmpty())
        val row = chat.moods().single()
        assertEquals(5, row.moodScore)
        assertEquals("""{"schema":1,"about":["NO_CLEAR_REASON"]}""", row.measurementPayloadJson)
        assertEquals(ChatLine.ANYTHING_ELSE, chat.turn().lines().last())
    }

    /** A low face: the low mood page's line and the talk, after it; no line pinned under the bar. */
    @Test
    fun aLowMoodOffersTheTalkAndNoPin() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(2)
        assertEquals(listOf(ChatLine.ACK_MIND), chat.turn().lines())
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
        val close = chat.close()
        assertEquals(BotPiece.Line(ChatLine.CLOSE_MIND, R.string.symptom_source_low_mood), close.first())
        assertEquals(listOf(R.string.chat_mood_talk_lead), close.notes())
        val talk = MoodScript.followUps(chat.state()).single()
        assertEquals(TopicChoice(Symptom.LOW_MOOD, TopicMention(Symptom.LOW_MOOD), R.string.chat_mood_offer_talk), talk)
        assertEquals(talk, chatQueue(chat.log, chat.setting()).first())
        // Nothing pins the line to call: a mood is no feeling's talk, and no question about safety was asked.
        assertFalse(Symptom.MOOD.mind)
        assertTrue(chat.log.threads.none { SymptomStep.CRISIS in checkNotNull(it.report.chat).askedSteps() })
        // Once a low mood talk ran in the chat, it is not offered again.
        chat.choose(talk)
        chat.talkThrough()
        chat.choose(Symptom.MOOD)
        // The first mood is inside its four hours: this one updates it.
        chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        chat.dial(1)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.LOSS)
        assertTrue(MoodScript.followUps(chat.state()).isEmpty())
        assertTrue(chat.close().notes().isEmpty())
    }

    /** Words of a bad mood: "Note my mood" or "Talk about it", the two as actions. */
    @Test
    fun badMoodWordsOfferNoteOrTalk() {
        listOf("плохое настроение", "I feel sad today").forEach { words ->
            val chat = MoodChat()
            chat.write(words)
            val turn = chat.turn()
            assertEquals(words, listOf(R.string.chat_mood_both), turn.notes())
            assertFalse(words, ChatLine.SAFETY in turn.lines())
            val reply = turn.reply as ChatReply.Topics
            assertEquals(words, listOf(Symptom.MOOD, Symptom.LOW_MOOD), reply.choices.map { it.topic })
            assertEquals(
                listOf(R.string.symptom_topic_mood, R.string.symptom_chat_talk_about_it),
                reply.choices.map { it.labelRes },
            )
            assertTrue(reply.exit && !reply.all)
            chat.choose(reply.choices.first())
            assertEquals(SymptomStep.MOOD_DIAL, chat.asked())
            assertEquals("MOOD", chat.thread().from)
        }
        // For an earlier day the mood is not offered: the talk alone.
        val past = MoodChat(past = true)
        past.write("плохое настроение")
        assertEquals(listOf(Symptom.LOW_MOOD), (past.turn().reply as ChatReply.Topics).choices.map { it.topic })
        assertTrue(past.turn().notes().isEmpty())
    }

    @Test
    fun theReasonIsAskedAtEveryBandWithItsOwnList() {
        val asked = (1..5).associateWith { score ->
            val chat = MoodChat()
            chat.choose(Symptom.MOOD)
            chat.dial(score)
            assertEquals(SymptomStep.MOOD_ABOUT, chat.asked())
            val reply = chat.turn().reply as ChatReply.Choose
            MoodScript.question(chat.state(), SymptomStep.MOOD_ABOUT) to reply.options
        }
        val low = R.string.symptom_ask_about to listOf(
            SymptomOption.WORK_STUDY, SymptomOption.MONEY, SymptomOption.FAMILY, SymptomOption.HEALTH,
            SymptomOption.LONELINESS, SymptomOption.LOSS, SymptomOption.FUTURE, SymptomOption.POOR_SLEEP,
            SymptomOption.NO_CLEAR_REASON, SymptomOption.SOMETHING_ELSE,
        )
        val middle = R.string.chat_mood_ask_about_neutral to listOf(
            SymptomOption.WORK_STUDY,
            SymptomOption.FAMILY,
            SymptomOption.HEALTH,
            SymptomOption.POOR_SLEEP,
            SymptomOption.PEOPLE,
            SymptomOption.NATURE_WEATHER,
            SymptomOption.NO_CLEAR_REASON,
            SymptomOption.SOMETHING_ELSE,
        )
        val good = R.string.chat_mood_ask_about_good to listOf(
            SymptomOption.PEOPLE,
            SymptomOption.REST,
            SymptomOption.MOVEMENT,
            SymptomOption.WENT_WELL,
            SymptomOption.NATURE_WEATHER,
            SymptomOption.GOOD_NEWS,
            SymptomOption.NO_CLEAR_REASON,
            SymptomOption.SOMETHING_ELSE,
        )
        assertEquals(mapOf(1 to low, 2 to low, 3 to middle, 4 to good, 5 to good), asked)
        // "Something else": the person's own words, kept in the chat and never in the row.
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(3)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.SOMETHING_ELSE)
        assertEquals(SymptomStep.MOOD_ELSE, chat.asked())
        val ask = chat.turn().reply as ChatReply.TextAsk
        assertEquals(listOf(SymptomOption.NOT_NOW.name), ask.suggestions.map { it.value })
        chat.type(SymptomStep.MOOD_ELSE, "my neighbours")
        assertEquals("my neighbours", chat.thread().report.values[SymptomStep.MOOD_ELSE])
        assertFalse(chat.moods().single().toString().contains("neighbours"))
        assertEquals(
            ChatText(R.string.chat_mood_words, listOf(PieceArg.Text("my neighbours"))),
            MoodScript.answerText(chat.state(), SymptomStep.MOOD_ELSE),
        )
    }

    /** The other thread's reason is a mark on the later one's chips, both ways — never ticked, never armed. */
    @Test
    fun anEarlierAboutIsAMarkNeverATick() {
        val chat = MoodChat()
        chat.choose(Symptom.LOW_MOOD)
        chat.talkThrough(mapOf(SymptomStep.ABOUT to listOf(SymptomOption.MONEY, SymptomOption.FAMILY)))
        chat.choose(Symptom.MOOD)
        chat.dial(2)
        val reply = chat.turn().reply as ChatReply.Choose
        assertEquals(setOf(SymptomOption.MONEY, SymptomOption.FAMILY), reply.chatMarked)
        assertTrue(reply.marked.isEmpty() && reply.preset.isEmpty())
        assertFalse(SymptomStep.MOOD_ABOUT in chat.thread().report.answers)
        val holds = chat.thread().report.holdsAnswer(SymptomStep.MOOD_ABOUT)
        assertFalse(autoTakeArmed(AnswerKind.SETTLE, holds, touched = false, answered = false))

        // A mood's reasons mark the talk's, which offers only some of them.
        val mood = MoodChat()
        mood.choose(Symptom.MOOD)
        mood.dial(2)
        mood.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY, SymptomOption.POOR_SLEEP)
        mood.choose(MoodScript.followUps(mood.state()).single())
        assertEquals(SymptomStep.ABOUT, mood.asked())
        assertEquals(setOf(SymptomOption.WORK_STUDY), (mood.turn().reply as ChatReply.Choose).chatMarked)
        assertFalse(SymptomStep.ABOUT in mood.thread().report.answers)

        // What lifts a good mood is no answer to what a low one is about.
        val good = MoodChat()
        good.choose(Symptom.MOOD)
        good.dial(5)
        good.tick(SymptomStep.MOOD_ABOUT, SymptomOption.NO_CLEAR_REASON)
        good.choose(Symptom.LOW_MOOD)
        assertTrue((good.turn().reply as ChatReply.Choose).chatMarked.isEmpty())
    }

    /** The card stands right after the dial, its line the record as it is; the close does not repeat it. */
    @Test
    fun theCardStandsRightAfterTheDial() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(3)
        val link = checkNotNull(chat.link())
        assertEquals(LinkState.FILED, link.state)
        assertTrue(link.made)
        assertEquals(listOf(0 to link), chat.firsts)
        val turn = chat.turn()
        assertEquals(listOf(BotPiece.Saved(0, "")), turn.saved())
        assertTrue(turn.pieces.indexOf(BotPiece.Saved(0, "")) < turn.pieces.indexOf(turn.ask()))
        assertEquals(listOf(""), MoodScript.savedAfter(chat.state(), SymptomStep.MOOD_DIAL))
        val names = NamedStrings()
        assertEquals("quick_log_added_named[mood_title]", MoodScript.flashText(names, link, FlashEvent.FILED))
        val row = chat.moods().single()
        val time = java.time.Instant.ofEpochMilli(row.occurredAt).atZone(java.time.ZoneId.systemDefault())
        val clock = clockText(time.hour * 60 + time.minute)
        assertEquals("mood_title · mood_neutral · $clock", MoodScript.savedSummary(names, row, null, ""))
        val tense = row.copy(energyScore = Energy.WOUND_UP.score)
        assertEquals(
            "mood_title · mood_neutral · energy_wound_up · $clock · chat_mood_updated",
            MoodScript.savedSummary(names, tense, null, MOOD_UPDATE_ROLE),
        )
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.FAMILY, SymptomOption.WORK_STUDY)
        // Joined with the list's comma of the language showing: "、" in Japanese, "،" in Arabic.
        assertEquals(
            "symptom_option_work_study" + "list_comma" + "symptom_option_family",
            MoodScript.savedDetail(names, chat.moods().single(), null, ""),
        )
        assertTrue(chat.close().none { it is BotPiece.Saved })
        assertEquals(listOf(R.string.chat_mood_close_neutral), chat.close().notes())
    }

    /** No mood for an earlier day: one honest line in place of "no match". */
    @Test
    fun noMoodInAPastDayChatButOneLine() {
        val past = MoodChat(past = true)
        past.write("I feel great")
        val turn = past.turn()
        assertEquals(listOf(R.string.chat_mood_today_only), turn.notes())
        assertTrue(ChatLine.SAFETY in turn.lines())
        assertFalse(Symptom.MOOD in (turn.reply as ChatReply.Topics).choices.map { it.topic })
        assertFalse(Symptom.MOOD in ChatLog(past = true).topics())
        assertTrue(Symptom.MOOD in ChatLog().topics())
    }

    /** After an answer about suicide other than "no", the mood is offered nowhere: menu, words or their pair. */
    @Test
    fun noMoodOfferedAfterACrisisAnswer() {
        val chat = MoodChat()
        chat.write("I want to die")
        chat.tap(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW)
        assertEquals(ChatMove.Prompt, chat.move())
        assertFalse(Symptom.MOOD in chatMenu(chat.log, chat.setting()).map { it.topic })
        chat.write("I feel sad")
        val turn = chat.turn()
        assertTrue(turn.notes().isEmpty())
        assertEquals(listOf(Symptom.LOW_MOOD), (turn.reply as ChatReply.Topics).choices.map { it.topic })
        val good = MoodChat()
        good.write("I want to die")
        good.tap(SymptomStep.CRISIS_NOW, SymptomOption.RATHER_NOT_SAY)
        good.write("I feel great")
        assertEquals(SymptomStep.SYMPTOM, good.asked())
        assertFalse(Symptom.MOOD in (good.turn().reply as ChatReply.Topics).choices.map { it.topic })
        // "No such thoughts" leaves it offered.
        val no = MoodChat()
        no.write("this headache will kill me")
        no.tap(SymptomStep.CRISIS_NOW, SymptomOption.NO_SUCH_THOUGHTS)
        assertTrue(Symptom.MOOD in chatMenu(no.log, no.setting()).map { it.topic })
    }

    /** Words of suicide inside the thread come first; after "no" it goes on, after any other answer it ends. */
    @Test
    fun crisisWordsInsideTheThreadComeFirst() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(2)
        chat.write("I want to end it all")
        assertEquals(SymptomStep.CRISIS_NOW, chat.asked())
        assertEquals(ChatLine.CRISIS_SUPPORT, chat.turn().lines().first())
        chat.tap(SymptomStep.CRISIS_NOW, SymptomOption.NO_SUCH_THOUGHTS)
        assertEquals(SymptomStep.MOOD_ABOUT, chat.asked())

        val unsafe = MoodChat()
        unsafe.choose(Symptom.MOOD)
        unsafe.dial(2)
        unsafe.write("I want to end it all")
        unsafe.tap(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW)
        assertEquals(ChatMove.Prompt, unsafe.move())
        val close = unsafe.close()
        assertEquals(listOf(SymptomAlert.URGENT_MIND), close.filterIsInstance<BotPiece.Alert>().map { it.alert })
        assertEquals(1, close.size)
        assertTrue(MoodScript.followUps(unsafe.state()).isEmpty())
        assertEquals(ChatLine.CRISIS_TALK, unsafe.turn().lines().last())
    }
}
