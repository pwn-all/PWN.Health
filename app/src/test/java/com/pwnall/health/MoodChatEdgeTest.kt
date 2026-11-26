package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mood thread at its edges: what else the words named beside the mood, an emoji on its own, the
 * low mood's talk offered once and asked about plainly, an Undo that holds, a mood deleted while it
 * was being updated, the pencil on what the row is built from, a question drawn again hours later,
 * and the chest a mood's words named, asked about straight after it.
 */
class MoodChatEdgeTest {
    private val hour = 60 * 60_000L

    private val talk = TopicChoice(Symptom.LOW_MOOD, TopicMention(Symptom.LOW_MOOD), R.string.chat_mood_offer_talk)

    private fun BotTurn.topics(): List<Symptom> = (reply as ChatReply.Topics).choices.map { it.topic }

    /** Words of a bad mood beside a body symptom: the body is offered first, its questions first. */
    @Test
    fun badMoodWordsBesideABodySymptomOfferTheBodyFirst() {
        val chat = MoodChat()
        chat.write("I feel sad and I have a fever")
        assertNull(chat.thread().topic)
        val turn = chat.turn()
        assertTrue(turn.notes().isEmpty())
        assertEquals(ChatLine.MATCH_SEVERAL, turn.lines().last())
        assertEquals(listOf(Symptom.FEVER, Symptom.LOW_MOOD, Symptom.MOOD), turn.topics())
        // Once the fever has had its thread, the same words bring the pair.
        val fever = MoodChat()
        fever.choose(Symptom.FEVER)
        fever.talkThrough()
        fever.write("I feel sad and I have a fever")
        assertEquals(listOf(R.string.chat_mood_both), fever.turn().notes())
        assertEquals(listOf(Symptom.MOOD, Symptom.LOW_MOOD), fever.turn().topics())
    }

    /**
     * An emoji on its own — beside words in a language the chat does not read, too — proposes the
     * mood as a chip with the line to call for an emergency beside it, and never opens the dial
     * itself.
     */
    @Test
    fun anEmojiAloneProposesTheMoodBesideTheLineToCall() {
        listOf("🙂", "Je suis fatigué 😐").forEach { words ->
            val chat = MoodChat()
            chat.write(words)
            assertNull(words, chat.thread().topic)
            assertEquals(words, SymptomStep.SYMPTOM, chat.asked())
            val turn = chat.turn()
            assertEquals(words, listOf(ChatLine.MATCH, ChatLine.SAFETY), turn.lines())
            val reply = turn.reply as ChatReply.Topics
            assertEquals(words, listOf(Symptom.MOOD), reply.choices.map { it.topic })
            assertTrue(words, reply.exit && !reply.all)
            chat.choose(reply.choices.single())
            assertEquals(words, SymptomStep.MOOD_DIAL, chat.asked())
        }
        // Words read take the mood up at once, at its dial, with no line to call for an emergency.
        val read = MoodChat()
        read.write("my mood is so so")
        assertEquals(SymptomStep.MOOD_DIAL, read.asked())
        assertFalse(ChatLine.SAFETY in read.turn().lines())
    }

    /**
     * Words of a bad mood once a low mood talk was had: the mood alone, never a second talk; once a
     * mood was noted, the talk alone.
     */
    @Test
    fun aLowMoodTalkIsNeverOfferedTwice() {
        val talked = MoodChat()
        talked.choose(Symptom.LOW_MOOD)
        talked.talkThrough()
        talked.write("I feel sad")
        val turn = talked.turn()
        assertTrue(turn.notes().isEmpty())
        assertEquals(ChatLine.MATCH, turn.lines().last())
        assertEquals(listOf(Symptom.MOOD), turn.topics())
        val noted = MoodChat()
        noted.choose(Symptom.MOOD)
        noted.dial(3)
        noted.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
        noted.write("I feel sad")
        assertEquals(ChatLine.MATCH, noted.turn().lines().last())
        assertEquals(listOf(Symptom.LOW_MOOD), noted.turn().topics())
    }

    /**
     * "You also wrote about this" stands only over what the words named: the talk offered after a mood
     * chosen from the menu is asked about plainly; after words of a bad mood it stands for the low mood
     * they named.
     */
    @Test
    fun theTalkAfterAMoodFromTheMenuIsOfferedAsAnythingElse() {
        val menu = MoodChat()
        menu.choose(Symptom.MOOD)
        menu.dial(2)
        menu.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
        val prompt = menu.turn()
        assertEquals(ChatLine.ANYTHING_ELSE, prompt.lines().last())
        val reply = prompt.reply as ChatReply.Topics
        assertEquals(listOf(talk), reply.choices)
        assertTrue(reply.end && !reply.all)
        val words = MoodChat()
        words.write("I feel sad")
        words.choose((words.turn().reply as ChatReply.Topics).choices.first { it.topic == Symptom.MOOD })
        words.dial(2)
        words.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
        assertEquals(ChatLine.ALSO_MENTIONED, words.turn().lines().last())
        assertEquals(talk, (words.turn().reply as ChatReply.Topics).choices.first())
    }

    /**
     * A low mood whose thread's words named the chest: the chest is asked about straight after it, and
     * the talk the mood's close offered comes after that. What the mood's close said stays as it was
     * said, whatever is answered in the chest's thread.
     */
    @Test
    fun theTalkALowMoodOfferedComesAfterTheChestItsWordsNamed() {
        fun lowMoodThenChest(): MoodChat = MoodChat().apply {
            choose(Symptom.MOOD)
            dial(2)
            write("my chest hurts")
            assertEquals(SymptomStep.CHEST_NOW, asked())
            tap(SymptomStep.CHEST_NOW, SymptomOption.NO)
            tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
            assertEquals(Symptom.CHEST_PAIN to ROUTE_WORDS, thread().topic to thread().from)
        }
        val chat = lowMoodThenChest()
        // A chest pain gone now is a doctor's line, and a low face's close offers the talk beside it.
        assertTrue(BotPiece.Line(ChatLine.CLOSE_GP) in chat.close(0))
        assertEquals(listOf(talk), MoodScript.followUps(chat.log.threadState(0, chat.setting())))
        chat.talkThrough()
        assertEquals(ChatMove.Prompt, chat.move())
        assertTrue(talk in chatQueue(chat.log, chat.setting()))
        // An answer about suicide in the chest's thread changes nothing the mood's close said, and ends the offer.
        val crisis = lowMoodThenChest()
        val said = crisis.close(0)
        crisis.write("I want to die")
        crisis.tap(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW)
        assertEquals(said, crisis.close(0))
        assertNull(moodAfterTalk(crisis.state()))
    }

    /** Wound up beside words of breathlessness, or after a thread about the chest: no breathing exercise is offered. */
    @Test
    fun noBreathingExerciseBesideTheChestOrBreathlessness() {
        fun offered(before: List<Symptom>, words: String?): Boolean {
            val chat = MoodChat()
            words?.let { chat.write(it) }
            before.forEach { topic ->
                chat.choose(topic)
                chat.talkThrough()
            }
            chat.choose(Symptom.MOOD)
            chat.dial(3)
            chat.tap(SymptomStep.MOOD_ENERGY, SymptomOption.ENERGY_WOUND_UP)
            chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
            return BotPiece.Offer(ChatOffer.BREATHING, chat.thread().id) in chat.close()
        }
        assertTrue(offered(listOf(Symptom.WORRY), "I am worried about work"))
        assertFalse(offered(listOf(Symptom.WORRY), "I am worried and short of breath"))
        assertFalse(offered(listOf(Symptom.WORRY, Symptom.PALPITATIONS), null))
    }

    /** Refused and left as it is: this one is not saved, and a low face is met as a low mood all the same. */
    @Test
    fun aLowFaceLeftUnsavedIsStillMetAsALowMood() {
        fun leftAt(score: Int): MoodChat {
            val store = MemoryChatStore()
            val chat = MoodChat(store)
            chat.choose(Symptom.MOOD)
            store.db.put(pageMood(CHAT_NOW - 5 * 60_000L, score = 5))
            chat.dial(score)
            chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.LEAVE_IT)
            chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
            return chat
        }
        val low = leftAt(1)
        val close = low.close()
        assertEquals(listOf(R.string.chat_mood_not_saved, R.string.chat_mood_talk_lead), close.notes())
        assertTrue(BotPiece.Line(ChatLine.CLOSE_MIND, R.string.symptom_source_low_mood) in close)
        assertEquals(listOf(talk), MoodScript.followUps(low.state()))
        // A middle face left unsaved is never thanked as noted.
        assertEquals(listOf(R.string.chat_mood_not_saved), leftAt(3).close().notes())
    }

    /**
     * Undo on the card after the dial holds: the answers after it file nothing again, nor does a face
     * given again with the pencil; Save again files the mood as it stands, and it is written through
     * from there. An update undone keeps the earlier mood as it was put back.
     */
    @Test
    fun anUndoneMoodStaysUndoneUntilSavedAgain() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(2)
        chat.undo(0)
        assertTrue(chat.moods().isEmpty())
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
        assertTrue(chat.moods().isEmpty())
        assertEquals(LinkState.UNDONE, chat.link()?.state)
        assertTrue(canSaveAgain(ScriptPlans, chat.thread(), "", chat.context()))
        chat.rewind(SymptomStep.MOOD_DIAL)
        chat.dial(4)
        assertTrue(chat.moods().isEmpty())
        chat.saveAgain(0)
        assertEquals(4, chat.moods().single().moodScore)
        assertEquals(LinkState.FILED, chat.link()?.state)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.FAMILY)
        assertEquals(listOf(SymptomOption.FAMILY), decodeMoodAbout(chat.moods().single().measurementPayloadJson))

        val (update, earlier) = withMood(ago = hour, score = 4)
        update.choose(Symptom.MOOD)
        update.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        update.dial(2)
        update.undo(0, MOOD_UPDATE_ROLE)
        assertEquals(listOf(earlier), update.moods())
        update.tick(SymptomStep.MOOD_ABOUT, SymptomOption.FAMILY)
        assertEquals(listOf(earlier), update.moods())
        assertEquals(LinkState.UNDONE, update.link(role = MOOD_UPDATE_ROLE)?.state)
        update.saveAgain(0, MOOD_UPDATE_ROLE)
        assertEquals(earlier.id, update.moods().single().id)
        assertEquals(2, update.moods().single().moodScore)
    }

    /**
     * A mood updated in a chat that was over and sealed — left for History, then come back to and
     * carried on — cannot be put back as it was: its thread offers no pencil, as its card offers no
     * Undo, since the pencil would drop the update and leave the new face for good.
     */
    @Test
    fun anUpdateThatCanNoLongerBePutBackOffersNoPencil() {
        val (chat, _) = withMood(ago = hour, score = 4)
        chat.choose(Symptom.MOOD)
        chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        chat.dial(2)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.FAMILY)
        assertTrue(chat.thread().id in pencilThreads(chat.log))
        chat.commit(chat.log.endedByPerson())
        runBlocking { chat.filing.seal() }
        chat.log = stored(chat.store, chat.key)
        chat.commit(chat.log.unended())
        assertNull(chat.link(role = MOOD_UPDATE_ROLE)?.prior)
        assertTrue(chat.thread().id !in pencilThreads(chat.log))
    }

    /** The pencil on the reason or the tension keeps the row as it stands until answered again. */
    @Test
    fun thePencilOnTheReasonOrTheTensionKeepsTheRowUntilAnsweredAgain() {
        val chat = MoodChat()
        chat.choose(Symptom.WORRY)
        chat.talkThrough()
        chat.choose(Symptom.MOOD)
        chat.dial(2)
        chat.tap(SymptomStep.MOOD_ENERGY, SymptomOption.ENERGY_CALM)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.FAMILY)
        val row = chat.moods().single()
        chat.rewind(SymptomStep.MOOD_ABOUT)
        assertEquals(SymptomStep.MOOD_ABOUT, chat.asked())
        assertEquals(listOf(row), chat.moods())
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
        val again = chat.moods().single()
        assertEquals(row.id, again.id)
        assertEquals(listOf(SymptomOption.WORK_STUDY), decodeMoodAbout(again.measurementPayloadJson))
        chat.rewind(SymptomStep.MOOD_ENERGY)
        assertEquals(listOf(again), chat.moods())
        chat.tap(SymptomStep.MOOD_ENERGY, SymptomOption.ENERGY_WOUND_UP)
        assertEquals(Energy.WOUND_UP.score, chat.moods().single().energyScore)
    }

    /**
     * "Update it", and the mood it was about deleted before the dial is taken: the face given is
     * noted as a mood of its own, its card after the dial, with no Save again on an update that could
     * only meet nothing. Gone before "Update it" was tapped, there is nothing to update at all. Deleted
     * once updated, the update is gone too, and nothing offers to save it again.
     */
    @Test
    fun aMoodDeletedWhileItIsUpdatedLeavesTheFaceNotedAsANewMood() {
        val (chat, earlier) = withMood(ago = hour, score = 4)
        chat.choose(Symptom.MOOD)
        chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        chat.store.db.journal.remove(earlier.id)
        chat.reload()
        chat.dial(2)
        assertEquals(LinkState.REFUSED, chat.link(role = MOOD_UPDATE_ROLE)?.state)
        assertEquals(listOf(""), MoodScript.savedAfter(chat.state(), SymptomStep.MOOD_DIAL))
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.HEALTH)
        val row = chat.moods().single()
        assertEquals(2 to listOf(SymptomOption.HEALTH), row.moodScore to decodeMoodAbout(row.measurementPayloadJson))
        assertEquals(LinkState.FILED, chat.link()?.state)
        assertTrue(checkNotNull(chat.link()).made)
        assertFalse(canSaveAgain(ScriptPlans, chat.thread(), MOOD_UPDATE_ROLE, chat.context()))
        assertEquals(listOf(""), MoodScript.savedAfter(chat.state(), SymptomStep.MOOD_DIAL))
        chat.rewind(SymptomStep.MOOD_ABOUT)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.FAMILY)
        assertEquals(row.id, chat.moods().single().id)

        val (before, gone) = withMood(ago = hour, score = 4)
        before.choose(Symptom.MOOD)
        before.store.db.journal.remove(gone.id)
        before.reload()
        before.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        assertNull((before.turn().reply as ChatReply.MoodDial).start)
        before.dial(3)
        assertEquals(3, before.moods().single().moodScore)
        assertEquals(LinkState.FILED, before.link()?.state)

        val (later, target) = withMood(ago = hour, score = 4)
        later.choose(Symptom.MOOD)
        later.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        later.dial(2)
        later.store.db.journal.remove(target.id)
        later.tick(SymptomStep.MOOD_ABOUT, SymptomOption.HEALTH)
        assertEquals(LinkState.GONE, later.link(role = MOOD_UPDATE_ROLE)?.state)
        assertTrue(later.moods().isEmpty())
        assertFalse(canSaveAgain(ScriptPlans, later.thread(), MOOD_UPDATE_ROLE, later.context()))

        // Gone, and another mood noted inside the four hours: the face is not saved, nothing offers to
        // save it again, and it stays so as the chat goes on.
        val (crowded, first) = withMood(ago = hour, score = 4)
        crowded.choose(Symptom.MOOD)
        crowded.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        crowded.store.db.journal.remove(first.id)
        val other = crowded.store.db.put(pageMood(CHAT_NOW - 30 * 60_000L, score = 1))
        crowded.dial(2)
        crowded.tick(SymptomStep.MOOD_ABOUT, SymptomOption.HEALTH)
        crowded.rewind(SymptomStep.MOOD_ABOUT)
        crowded.tick(SymptomStep.MOOD_ABOUT, SymptomOption.FAMILY)
        assertEquals(listOf(other), crowded.moods())
        assertEquals(LinkState.REFUSED, crowded.link()?.state)
        assertEquals(LinkState.REFUSED, crowded.link(role = MOOD_UPDATE_ROLE)?.state)
        assertFalse(canSaveAgain(ScriptPlans, crowded.thread(), "", crowded.context()))
    }

    /**
     * "Update it?" drawn again hours later names the mood it asked about, not one noted since; a mood
     * noted after the chat's moment — the widget tapped while the dial is open — never takes the dial
     * away: the door finds it when the dial is taken, and it is asked about after it.
     */
    @Test
    fun theUpdateQuestionDrawnAgainNamesTheMoodItAskedAbout() {
        val (chat, earlier) = withMood(ago = hour, score = 4)
        chat.choose(Symptom.MOOD)
        chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.LEAVE_IT)
        chat.now += 5 * hour
        chat.store.db.put(pageMood(chat.now - 10 * 60_000L, score = 1))
        chat.reload()
        val asked = chatTranscript(chat.log, chat.setting(), live = false)
            .filterIsInstance<ChatItem.Bot>()
            .flatMap { it.turn.pieces }
            .filterIsInstance<BotPiece.Ask>()
            .single { it.step == SymptomStep.MOOD_UPDATE }
        val named = listOf(PieceArg.Moment(earlier.occurredAt), PieceArg.Res(R.string.mood_good))
        assertEquals(ChatText(R.string.chat_mood_ask_update, named), asked.text)

        val open = MoodChat()
        open.choose(Symptom.MOOD)
        val widget = open.store.db.put(pageMood(CHAT_NOW + 60_000L, score = 5))
        open.reload()
        assertEquals(SymptomStep.MOOD_DIAL, open.asked())
        open.dial(2)
        assertEquals(LinkState.REFUSED, open.link()?.state)
        // The chat's moment, as the commit of the dial moved it past the widget's.
        open.now += 2 * 60_000L
        assertEquals(SymptomStep.MOOD_UPDATE, open.asked())
        assertEquals(
            ChatText(R.string.chat_mood_ask_clash, listOf(PieceArg.Moment(widget.occurredAt))),
            open.turn().ask().text,
        )
    }

    /** The check on sleep and energy reads the whole chat's words: little sleep written before the mood. */
    @Test
    fun theHighCheckReadsWordsWrittenBeforeTheMood() {
        val chat = MoodChat()
        chat.write("I feel tired, I barely slept")
        chat.choose((chat.turn().reply as ChatReply.Topics).choices.first { it.topic == Symptom.TIREDNESS })
        chat.talkThrough()
        chat.choose(Symptom.MOOD)
        chat.dial(5)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.NO_CLEAR_REASON)
        assertEquals(SymptomStep.MOOD_HIGH_ASK, chat.asked())
    }
}
