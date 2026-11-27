package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What a mood thread files: one mood row through the mood page's factory and door, inside its four
 * hours an update of the mood already there — never a second row, never a silent refusal — and Undo
 * putting an update back as it was.
 */
class MoodChatRecordTest {
    private val hour = 60 * 60_000L

    @Test
    fun insideFourHoursTheThreadAsksToUpdate() {
        val (chat, earlier) = withMood(ago = hour, score = 4, energy = Energy.RESTLESS.score)
        chat.choose(Symptom.MOOD)
        assertEquals(SymptomStep.MOOD_UPDATE, chat.asked())
        val ask = chat.turn()
        assertEquals(updateQuestion(earlier.occurredAt, R.string.mood_good), ask.ask().text)
        assertEquals(listOf(SymptomOption.UPDATE_IT, SymptomOption.LEAVE_IT), (ask.reply as ChatReply.Choose).options)
        chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        // The dial rests on the mood as it was noted, and its "?" says so.
        val dial = chat.turn()
        assertEquals(4, (dial.reply as ChatReply.MoodDial).start)
        assertEquals(ChatText(R.string.chat_mood_help_update), dial.ask().help)
        chat.dial(2)
        // That mood had a tension: it is asked again, and until then the stale one is gone.
        assertEquals(SymptomStep.MOOD_ENERGY, chat.asked())
        val row = chat.moods().single()
        assertEquals(earlier.id, row.id)
        assertEquals(earlier.occurredAt, row.occurredAt)
        assertEquals(earlier.title, row.title)
        assertEquals(2, row.moodScore)
        assertNull(row.energyScore)
        val link = checkNotNull(chat.link(role = MOOD_UPDATE_ROLE))
        assertFalse(link.made)
        assertEquals(earlier.occurredAt, link.at)
        assertNotNull(link.prior)
        assertEquals(listOf(BotPiece.Saved(0, MOOD_UPDATE_ROLE)), chat.turn().saved())
        assertEquals("work_flash_saved", MoodScript.flashText(NamedStrings(), link, FlashEvent.FILED))
        chat.tap(SymptomStep.MOOD_ENERGY, SymptomOption.ENERGY_CALM)
        assertEquals(Energy.CALM.score, chat.moods().single().energyScore)
        assertEquals("mood_low, energy_calm", chat.moods().single().note)
        // An update of a mood that had no tension asks none.
        val (plain, _) = withMood(ago = hour, score = 3)
        plain.choose(Symptom.MOOD)
        plain.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        plain.dial(5)
        assertEquals(SymptomStep.MOOD_ABOUT, plain.asked())
    }

    @Test
    fun leaveItWritesNothingAndSaysWhen() {
        val (chat, earlier) = withMood(ago = 30 * 60_000L, score = 3)
        chat.choose(Symptom.MOOD)
        chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.LEAVE_IT)
        assertEquals(ChatMove.Prompt, chat.move())
        assertEquals(listOf(earlier), chat.moods())
        assertTrue(chat.thread().filed.isEmpty())
        val close = chat.close()
        val next = earlier.occurredAt + MOOD_COOLDOWN_MS
        assertEquals(listOf(BotPiece.Note(ChatText(R.string.chat_mood_kept, listOf(PieceArg.Moment(next))))), close)
        val items = chatTranscript(chat.log, chat.setting(), live = true)
        assertTrue(items.filterIsInstance<ChatItem.Bot>().all { it.turn.saved().isEmpty() })
        // Four hours on, nothing is offered to update — even while the page still holds that mood.
        val (later, old) = withMood(ago = 5 * hour, score = 3)
        later.choose(Symptom.MOOD)
        later.env = later.env.copy(lastMood = old)
        assertEquals(SymptomStep.MOOD_DIAL, later.asked())
    }

    /** A mood that landed while the dial was open: the write is refused, and asked about — never silently. */
    @Test
    fun aRefusedWriteAsksTheClashQuestion() {
        val store = MemoryChatStore()
        val chat = MoodChat(store)
        chat.choose(Symptom.MOOD)
        assertEquals(SymptomStep.MOOD_DIAL, chat.asked())
        val widget = store.db.put(pageMood(CHAT_NOW - 5 * 60_000L, score = 5))
        chat.dial(2)
        assertEquals(LinkState.REFUSED, chat.link()?.state)
        assertEquals(listOf(widget), chat.moods())
        assertEquals(SymptomStep.MOOD_UPDATE, chat.asked())
        assertEquals(
            ChatText(R.string.chat_mood_ask_clash, listOf(PieceArg.Moment(widget.occurredAt))),
            chat.turn().ask().text,
        )
        // While it waits, the refusal stands as it is: a Save again would meet the same four hours.
        assertEquals(RecordWant.Keep, MoodScript.wants(chat.thread(), chat.context())[""])
        assertFalse(canSaveAgain(ScriptPlans, chat.thread(), "", chat.context()))
        // Its card says the core's "Not saved" until the person has said which mood stays.
        assertNull(MoodScript.stateLine(chat.thread(), checkNotNull(chat.link())))
        chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        val row = chat.moods().single()
        assertEquals(widget.id, row.id)
        assertEquals(2, row.moodScore)
        assertNull(chat.link(role = ""))
        assertEquals(false, chat.link(role = MOOD_UPDATE_ROLE)?.made)
        assertEquals(listOf(BotPiece.Saved(0, MOOD_UPDATE_ROLE)), chat.turn().saved())

        // Left as it is: nothing written, the questions about the person go on, and the close says so.
        val other = MemoryChatStore()
        val left = MoodChat(other)
        left.choose(Symptom.MOOD)
        val landed = other.db.put(pageMood(CHAT_NOW - 5 * 60_000L, score = 5))
        left.dial(2)
        left.tap(SymptomStep.MOOD_UPDATE, SymptomOption.LEAVE_IT)
        assertEquals(SymptomStep.MOOD_ABOUT, left.asked())
        assertEquals(RecordWant.Keep, MoodScript.wants(left.thread(), left.context())[""])
        assertFalse(canSaveAgain(ScriptPlans, left.thread(), "", left.context()))
        left.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
        assertEquals(listOf(landed), left.moods())
        assertEquals(LinkState.REFUSED, left.link()?.state)
        val notSaved = ChatText(R.string.chat_mood_not_saved, listOf(PieceArg.Moment(landed.occurredAt)))
        assertTrue(BotPiece.Note(notSaved) in left.close())
        // The card says it too, with the time of the mood that stays.
        assertEquals(notSaved, MoodScript.stateLine(left.thread(), checkNotNull(left.link())))
    }

    /** A second mood thread updates the first one's reading; Undo of it puts the first back. */
    @Test
    fun aSecondMoodThreadUpdatesTheFirstAndUndoPutsItBack() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(4)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.PEOPLE)
        val first = chat.moods().single()
        chat.now += 30 * 60_000L
        chat.choose(Symptom.MOOD)
        assertEquals(SymptomStep.MOOD_UPDATE, chat.asked())
        assertEquals(updateQuestion(first.occurredAt, R.string.mood_good), chat.turn().ask().text)
        chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        chat.dial(2)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
        val updated = chat.moods().single()
        assertEquals(first.id, updated.id)
        assertEquals(2, updated.moodScore)
        assertEquals(listOf(SymptomOption.WORK_STUDY), decodeMoodAbout(updated.measurementPayloadJson))
        assertEquals(LinkState.SUPERSEDED, chat.link(thread = 0)?.state)
        chat.undo(1, MOOD_UPDATE_ROLE)
        assertEquals(first, chat.moods().single())
        assertEquals(LinkState.FILED, chat.link(thread = 0)?.state)
        val undone = checkNotNull(chat.link(thread = 1, role = MOOD_UPDATE_ROLE))
        assertEquals(LinkState.UNDONE, undone.state)
        // The undone update's card names no update: the earlier mood is back as it was.
        val names = NamedStrings()
        assertFalse("chat_mood_updated" in MoodScript.savedSummary(names, first, null, MOOD_UPDATE_ROLE, undone.state))
        assertTrue(
            "chat_mood_updated" in MoodScript.savedSummary(names, first, null, MOOD_UPDATE_ROLE, LinkState.FILED),
        )
        assertEquals("chat_mood_restored", MoodScript.flashText(NamedStrings(), undone, FlashEvent.UNDONE))
        // Undo of a new reading deletes it, with the core's word.
        chat.undo(0)
        assertTrue(chat.moods().isEmpty())
        val deleted = checkNotNull(chat.link(0))
        assertEquals("work_flash_deleted", MoodScript.flashText(NamedStrings(), deleted, FlashEvent.UNDONE))
    }

    /** The pencil on the dial keeps the mood until taken again, then rewrites it; the topic takes it back. */
    @Test
    fun aRewindRewritesTheSameMoodAndTheTopicTakesItBack() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(2)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.FAMILY)
        val row = chat.moods().single()
        chat.rewind(SymptomStep.MOOD_DIAL)
        assertEquals(SymptomStep.MOOD_DIAL, chat.asked())
        assertEquals(listOf(row), chat.moods())
        assertEquals("2", chat.thread().report.values[SymptomStep.MOOD_DIAL])
        chat.dial(4)
        val again = chat.moods().single()
        assertEquals(row.id, again.id)
        assertEquals(row.occurredAt, again.occurredAt)
        assertEquals(4, again.moodScore)
        assertNull(again.measurementPayloadJson)
        chat.rewind(SymptomStep.SYMPTOM)
        assertTrue(chat.moods().isEmpty())

        // An update's topic taken back puts the mood back as it was.
        val (update, earlier) = withMood(ago = hour, score = 3)
        update.choose(Symptom.MOOD)
        update.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        update.dial(1)
        update.rewind(SymptomStep.SYMPTOM)
        assertEquals(listOf(earlier), update.moods())
    }

    /** The check on sleep and energy: needing little sleep, said in words, and a good face. */
    @Test
    fun theHighCheckNeedsSleepWordsAndAGoodScore() {
        fun asksIt(words: String?, score: Int): Boolean {
            val chat = MoodChat()
            if (words == null) chat.choose(Symptom.MOOD) else chat.write(words)
            chat.dial(score)
            chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.NO_CLEAR_REASON)
            return chat.asked() == SymptomStep.MOOD_HIGH_ASK
        }
        assertTrue(asksIt("I feel amazing, I barely slept", 5))
        assertTrue(asksIt("настроение отличное, спать не хочется", 4))
        assertFalse(asksIt("I feel amazing", 5))
        assertFalse(asksIt(null, 5))
        assertFalse(asksIt("I feel amazing, I barely slept", 3))
        // The other signs bring it only with a course read as an antidepressant, which none is here.
        assertFalse(asksIt("I feel amazing, so full of energy", 5))
        // "No, thank you": all right, and not asked again in this chat.
        val chat = MoodChat()
        chat.write("I feel amazing, I barely slept")
        chat.dial(5)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.REST)
        chat.tap(SymptomStep.MOOD_HIGH_ASK, SymptomOption.NO_THANKS)
        assertEquals(ChatMove.Prompt, chat.move())
        val close = chat.close()
        assertEquals(BotPiece.Line(ChatLine.ACK_3), close.first())
        assertEquals(listOf(R.string.chat_mood_close_good), close.notes())
        chat.write("I barely slept again")
        chat.choose(Symptom.MOOD)
        chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.UPDATE_IT)
        chat.dial(5)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.REST)
        assertEquals(ChatMove.Prompt, chat.move())
    }

    /** "In the way of everyday life" is a doctor's line at any length, even with a single sign. */
    @Test
    fun everydayLifeYesIsADoctorLineAtAnyDuration() {
        val chat = highCheck(listOf(SymptomOption.RACING_MIND), SymptomOption.TODAY)
        assertEquals(SymptomStep.MOOD_HIGH_LIFE, chat.asked())
        assertTrue(chat.turn().alerts().isEmpty())
        chat.tap(SymptomStep.MOOD_HIGH_LIFE, SymptomOption.YES)
        val close = chat.close()
        assertEquals(listOf(SymptomAlert.DOCTOR_MIND), close.filterIsInstance<BotPiece.Alert>().map { it.alert })
        assertEquals(R.string.symptom_source_bipolar, close.filterIsInstance<BotPiece.Alert>().single().sourceRes)
        assertTrue(BotPiece.Line(ChatLine.CLOSE_GP) in close)
        assertTrue(BotPiece.Offer(ChatOffer.BOOK_VISIT, 0) in close)
        assertTrue(MoodScript.followUps(chat.state()).isEmpty())
        // Not in the way: no line at all for a single sign of today.
        val calm = highCheck(listOf(SymptomOption.RACING_MIND), SymptomOption.TODAY)
        calm.tap(SymptomStep.MOOD_HIGH_LIFE, SymptomOption.NO)
        assertTrue(calm.close().none { it is BotPiece.Alert })
        assertEquals(listOf(R.string.chat_mood_close_good), calm.close().notes())
    }

    /** A week or longer is a doctor's line — "a long time" read by the app, not the NHS; a few days, worth telling. */
    @Test
    fun aLongStretchIsADoctorLineByTheAppsConvention() {
        fun alertAt(signs: List<SymptomOption>, long: SymptomOption): List<SymptomAlert> {
            val chat = highCheck(signs, long)
            return chat.turn().alerts()
        }
        val sleep = listOf(SymptomOption.LESS_SLEEP_NEED)
        assertEquals(listOf(SymptomAlert.DOCTOR_MIND), alertAt(sleep, SymptomOption.ABOUT_A_WEEK))
        assertEquals(listOf(SymptomAlert.DOCTOR_MIND), alertAt(sleep, SymptomOption.TWO_WEEKS_PLUS))
        assertEquals(listOf(SymptomAlert.HIGH_MOOD_NET), alertAt(sleep, SymptomOption.FEW_DAYS))
        assertEquals(emptyList<SymptomAlert>(), alertAt(sleep, SymptomOption.TODAY))
        val two = listOf(SymptomOption.RACING_MIND, SymptomOption.TALKING_MORE)
        assertEquals(listOf(SymptomAlert.HIGH_MOOD_NET), alertAt(two, SymptomOption.FEW_DAYS))
        val one = listOf(SymptomOption.UNUSUAL_RISKS)
        assertEquals(emptyList<SymptomAlert>(), alertAt(one, SymptomOption.ABOUT_A_WEEK))
        // A plain card's close is a plain thanks.
        val net = highCheck(sleep, SymptomOption.FEW_DAYS)
        net.tap(SymptomStep.MOOD_HIGH_LIFE, SymptomOption.NO)
        assertEquals(listOf(R.string.chat_mood_close_neutral), net.close().notes())
        // None of these: nothing more is asked.
        val none = MoodChat()
        none.write("I feel amazing, I barely slept")
        none.dial(5)
        none.tick(SymptomStep.MOOD_ABOUT, SymptomOption.REST)
        none.tap(SymptomStep.MOOD_HIGH_ASK, SymptomOption.ASK_ME)
        none.tick(SymptomStep.MOOD_HIGH_SIGNS, SymptomOption.NONE)
        assertEquals(ChatMove.Prompt, none.move())
    }

    /**
     * On a course read as an antidepressant, any one sign of a high mood is the same-day line, and it
     * names the course as the person keeps it, never what kind of medicine it is. The course
     * is the report's own fact, frozen at the check from the person's courses the medicine list reads
     * as antidepressants ([medicineClassOf]; ChatMedicineTest holds a chat to that), so the rule is held
     * here on the report alone.
     */
    @Test
    fun aHighMoodOnAnAntidepressantIsTheSameDayLineNamingTheCourse() {
        val signs = mapOf(SymptomStep.MOOD_HIGH_SIGNS to listOf(SymptomOption.RACING_MIND))
        val high = SymptomReport(
            symptom = Symptom.MOOD,
            answers = signs,
            values = mapOf(SymptomStep.MOOD_DIAL to "5"),
            meta = mapOf("MOOD_HIGH_ASK.ad" to "Zoloft 50"),
        )
        val fired = firedRules(high)
        assertEquals(listOf("MOOD_HIGH_ON_ANTIDEPRESSANT"), fired.map { it.id })
        assertEquals(SymptomUrgency.SAME_DAY, fired.single().alert.urgency)
        assertEquals(R.string.symptom_source_antidepressant_mania, fired.single().sourceRes)
        // No course, no sign, or not a good face: no line.
        assertTrue(firedRules(high.copy(meta = emptyMap())).isEmpty())
        val none = mapOf(SymptomStep.MOOD_HIGH_SIGNS to listOf(SymptomOption.NONE))
        assertTrue(firedRules(high.copy(answers = none)).isEmpty())
        assertTrue(firedRules(high.copy(values = mapOf(SymptomStep.MOOD_DIAL to "3"))).isEmpty())
        // The line carries the name as kept; no other line is reworded.
        val state = ChatState(high, female = false, earlier = null)
        assertEquals(
            ChatText(R.string.chat_mood_alert_high_antidepressant, listOf(PieceArg.Text("Zoloft 50"))),
            MoodScript.alertText(state, SymptomAlert.HIGH_ON_ANTIDEPRESSANT),
        )
        assertNull(MoodScript.alertText(state, SymptomAlert.HIGH_MOOD_NET))
        // Its words hold the name and never the class, in both languages.
        for (folder in listOf("values", "values-ru")) {
            val xml = File("src/main/res/$folder/symptom_chat_mood.xml").readText()
            val line = checkNotNull(
                Regex("""name="chat_mood_alert_high_antidepressant"[^>]*>(.*?)</string>""").find(xml),
            ).groupValues[1]
            assertTrue(line, "%1\$s" in line)
            assertFalse(line, "antidepress" in line.lowercase() || "антидепресс" in line.lowercase())
        }
    }

    /** A visit booked from a mood thread names the mood in its note, never the menu's "Note my mood". */
    @Test
    fun aVisitsNoteNamesTheMoodByItsTitle() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(1)
        val strings = NamedStrings()
        assertEquals("mood_title", strings.summaryLine(chat.thread(), chat.state().report, null, Locale.ENGLISH))
        // A symptom is still named as the menu names it.
        val fever = SymptomReport(symptom = Symptom.FEVER)
        assertEquals("symptom_fever", strings.summaryLine(ChatThread(0, fever), fever, null, Locale.ENGLISH))
    }

    /** "A mood is noted at …: …. Would you like to update it?" */
    private fun updateQuestion(at: Long, face: Int) =
        ChatText(R.string.chat_mood_ask_update, listOf(PieceArg.Moment(at), PieceArg.Res(face)))

    /** A good mood, the check taken, the signs ticked and how long answered. */
    private fun highCheck(signs: List<SymptomOption>, long: SymptomOption): MoodChat {
        val chat = MoodChat()
        chat.write("I feel amazing, I barely slept")
        chat.dial(5)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.REST)
        chat.tap(SymptomStep.MOOD_HIGH_ASK, SymptomOption.ASK_ME)
        chat.tick(SymptomStep.MOOD_HIGH_SIGNS, *signs.toTypedArray())
        chat.tap(SymptomStep.MOOD_HIGH_LONG, long)
        return chat
    }

    /** Worry in the chat: the tension is asked; wound up, the breathing exercise — unless a body sign was ticked. */
    @Test
    fun aChatThatHoldsTensionAsksItAndOffersTheBreathing() {
        val chat = MoodChat()
        chat.choose(Symptom.WORRY)
        chat.talkThrough()
        chat.choose(Symptom.MOOD)
        chat.dial(3)
        assertEquals(SymptomStep.MOOD_ENERGY, chat.asked())
        chat.tap(SymptomStep.MOOD_ENERGY, SymptomOption.ENERGY_WOUND_UP)
        assertEquals("mood_neutral, energy_wound_up", chat.moods().single().note)
        assertEquals(Energy.WOUND_UP.score, chat.moods().single().energyScore)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.WORK_STUDY)
        assertTrue(BotPiece.Offer(ChatOffer.BREATHING, 1) in chat.close())
        // Not sure: no figure. A racing heart ticked in the worry: no breathing exercise offered.
        val body = MoodChat()
        body.choose(Symptom.WORRY)
        body.talkThrough(mapOf(SymptomStep.WITH to listOf(SymptomOption.RACING_HEART)))
        body.choose(Symptom.MOOD)
        body.dial(2)
        body.tap(SymptomStep.MOOD_ENERGY, SymptomOption.ENERGY_RESTLESS)
        body.tick(SymptomStep.MOOD_ABOUT, SymptomOption.HEALTH)
        assertTrue(body.close().none { it is BotPiece.Offer })
        // No tension anywhere in the chat: not asked.
        val calm = MoodChat()
        calm.choose(Symptom.MOOD)
        calm.dial(3)
        assertEquals(SymptomStep.MOOD_ABOUT, calm.asked())
    }

    /** "Note my mood" after a talk about a low mood, a worry or stress — and it takes up a mood the words named. */
    @Test
    fun aTalkOffersTheMoodAndClaimsItsMention() {
        val chat = MoodChat()
        chat.write("I am worried and my mood is up and down")
        val match = chat.turn().reply as ChatReply.Topics
        chat.choose(match.choices.first { it.topic == Symptom.WORRY })
        chat.talkThrough()
        val queue = chatQueue(chat.log, chat.setting())
        assertEquals(1, queue.count { it.topic == Symptom.MOOD })
        assertEquals(TopicChoice(Symptom.MOOD, TopicMention(Symptom.MOOD), R.string.symptom_topic_mood), queue.first())
        chat.choose(queue.first())
        assertEquals(ROUTE_FOLLOW + "MOOD", chat.thread().from)
        assertEquals(SymptomStep.MOOD_DIAL, chat.asked())
        // The words that named it are read: the worry brings the tension, the changes "are these very big?".
        chat.dial(3)
        assertEquals(SymptomStep.MOOD_ENERGY, chat.asked())
        chat.tap(SymptomStep.MOOD_ENERGY, SymptomOption.NOT_SURE)
        assertNull(chat.moods().single().energyScore)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.HEALTH)
        assertEquals(SymptomStep.MOOD_SWINGS, chat.asked())
        chat.tap(SymptomStep.MOOD_SWINGS, SymptomOption.NOT_SURE)
        val alerts = chat.close().filterIsInstance<BotPiece.Alert>().map { it.alert }
        assertEquals(listOf(SymptomAlert.MOOD_SWINGS_NET), alerts)
        // Not once a mood was noted, nor for a symptom.
        chat.choose(Symptom.STRESS)
        chat.talkThrough()
        assertNull(moodAfterTalk(chat.state()))
        val fever = MoodChat()
        fever.choose(Symptom.FEVER)
        fever.talkThrough()
        assertNull(moodAfterTalk(fever.state()))
    }

    /** The hidden sorting of the thread follows its face, and never reaches the mood's row. */
    @Test
    fun theTagSortsTheThreadAndNeverReachesTheRow() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(2)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.LONELINESS)
        val tag = checkNotNull(stored(chat.store, chat.key).threads.last().report.symptomTag)
        assertEquals(ProblemArea.FEELINGS, tag.area)
        assertTrue(tag.mind)
        assertEquals(TagFacts(score = 2), MoodScript.tagFacts(chat.thread()))
        val row = chat.moods().single()
        assertFalse(row.measurementPayloadJson.orEmpty().contains("tag"))
        assertFalse(row.measurementPayloadJson.orEmpty().contains("area"))
        val high = highCheck(listOf(SymptomOption.LESS_SLEEP_NEED), SymptomOption.ABOUT_A_WEEK)
        val sorted = checkNotNull(stored(high.store, high.key).threads.last().report.symptomTag)
        assertEquals(ProblemArea.MOOD_POSITIVE, sorted.area)
        assertEquals(ProblemCourse.RECENT, sorted.course)
        assertEquals(listOf("MOOD_HIGH_LONG_GP"), sorted.flags)
    }

    /** A live chat crossing midnight files its mood under the moment's date; the chat keeps its own day. */
    @Test
    fun aMoodTakenAfterMidnightLandsOnTheNewDay() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.now = CHAT_TODAY.plusDays(1).atStartOfDay(CHAT_ZONE).toInstant().toEpochMilli() + 60_000L
        chat.dial(3)
        assertEquals(CHAT_TODAY.plusDays(1).toString(), chat.moods().single().day)
        assertEquals(CHAT_TODAY.toString(), checkNotNull(chat.store.db.chats[chat.key]).day)
    }
}
