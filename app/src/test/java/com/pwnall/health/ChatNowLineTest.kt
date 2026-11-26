package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Too much of a medicine told in a thread of another topic — a knee's, a nausea's, a mood's — is a
 * line to get advice now there as in a dose's: the close says not to wait and offers no visit to plan,
 * the router pushes nothing after it, and no record says "added" after it; words of too much typed
 * where a mood asks what it is about bring their question; the row the words' answers are kept in
 * never flashes, titled with the person's own words; a note's words of too much show where to get
 * advice.
 */
class ChatNowLineTest {
    private val setting = chatSetting()

    private val painLink = FiledLink(KIND_PAIN, "", CHAT_NOW, "Pain")

    @After
    fun clock() {
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }

    @Test
    fun tooMuchToldInAKneesThreadClosesItOnNow() {
        val knee = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L")
        val told = knee.wrote("выпил 6 таблеток нурофена").settle()
        assertEquals(SymptomStep.MED_MAX_WORDS, told.asked())
        val more = told.tapOn(SymptomOption.MORE_THAN_IT_SAYS)
        assertEquals(SymptomStep.MED_PURPOSE, more.asked())
        val done = more.tapOn(SymptomOption.BY_ACCIDENT).calmToTheEnd()
        assertEquals(ChatMove.Prompt, chatMove(done, setting))
        val prompt = chatTurn(done, setting)
        assertTrue(prompt.pieces.any { it is BotPiece.Saved })
        assertTrue(ChatLine.CLOSE_NOW in prompt.lines())
        assertFalse(ChatLine.CLOSE_TODAY in prompt.lines())
        assertFalse(ChatOffer.BOOK_VISIT in prompt.offers())
        // The dose the words named is not pushed after it.
        assertFalse(ChatLine.ALSO_MENTIONED in prompt.lines())
        assertTrue((prompt.reply as ChatReply.Topics).choices.isEmpty())
        // No "Pain · added" over it, and none for any record after it in the chat.
        assertFalse(recordFlashAllowed(done.told(), setting, null, done.threads.single(), painLink))
        val later = done.choseTopic(TopicChoice(Symptom.FEVER), setting).settle().calmToTheEnd()
        val fever = later.threads.last()
        assertFalse(recordFlashAllowed(later.told(), setting, null, fever, painLink.copy(kind = KIND_SYMPTOM)))
        // The overdose's own line holds a flash back wherever it stands.
        val alert = BotTurn(
            listOf(BotPiece.Alert(SymptomAlert.DOSE_TOO_MUCH, R.string.med_chat_source_too_much)),
            ChatReply.End,
        )
        assertFalse(recordFlashAllowed(knee.told(), setting, alert))
        assertTrue(recordFlashAllowed(knee.told(), setting, null))
    }

    /**
     * A knee's own "get advice from 111 now" says not to wait and still offers the visit to plan its NHS
     * line asks for — "an urgent GP appointment or … NHS 111 now". Too much of a medicine
     * told in the same thread takes it away, as [tooMuchToldInAKneesThreadClosesItOnNow] holds.
     */
    @Test
    fun aKneesOwnNowLineStillOffersAVisit() {
        val knee = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L").calmToTheEnd(nrs = 9)
        val report = knee.threads.single().report
        assertTrue(nowLineFired(report))
        assertFalse(medicineNowFired(report))
        val prompt = chatTurn(knee, setting)
        assertTrue(ChatLine.CLOSE_NOW in prompt.lines())
        assertTrue(ChatOffer.BOOK_VISIT in prompt.offers())
        // A pain for today offers it too.
        val shoulder = ChatLog().choseTopic(TopicChoice(Symptom.PAIN), setting).settle()
            .paint(listOf(dabOn("SHOULDER:L"))).calmToTheEnd(nrs = 9)
        assertTrue(ChatOffer.BOOK_VISIT in chatTurn(shoulder, setting).offers())
    }

    @Test
    fun tooMuchToldInANauseasThreadClosesItOnNow() {
        val nausea = ChatLog().choseTopic(TopicChoice(Symptom.NAUSEA), setting).settle()
            .wrote("I took 10 paracetamol tablets").settle()
        assertEquals(SymptomStep.MED_MAX_WORDS, nausea.asked())
        val more = nausea.tapOn(SymptomOption.MORE_THAN_IT_SAYS)
        assertEquals(SymptomStep.MED_PURPOSE, more.asked())
        val done = more.tapOn(SymptomOption.BY_ACCIDENT).calmToTheEnd()
        val close = closeOf(done, 0, setting)
        assertTrue(BotPiece.Line(ChatLine.CLOSE_NOW) in close)
        assertTrue(close.none { it is BotPiece.Line && it.line == ChatLine.CLOSE_TODAY })
        assertTrue(close.none { it is BotPiece.Offer })
        val prompt = chatTurn(done, setting)
        assertTrue((prompt.reply as ChatReply.Topics).choices.isEmpty())
        val link = painLink.copy(kind = KIND_SYMPTOM, title = "Nausea")
        assertFalse(recordFlashAllowed(done.told(), setting, prompt, done.threads.single(), link))
    }

    /**
     * "I took all my pills" typed where a low mood asks what it is about is a message, not the answer:
     * "more than it says?" comes first, the question again after it, and the mood's close says not to
     * wait — no "today", no visit, no talk pushed after it; the row the words' answers are kept in is
     * never said "added".
     */
    @Test
    fun wordsOfTooMuchTypedWhereAMoodAsksWhatItIsAboutAreAskedAbout() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.dial(1)
        while (chat.asked() != SymptomStep.MOOD_ABOUT) {
            val step = checkNotNull(chat.asked())
            chat.tap(step, (chat.turn().reply as ChatReply.Choose).options.first())
        }
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.SOMETHING_ELSE)
        assertEquals(SymptomStep.MOOD_ELSE, chat.asked())
        chat.type(SymptomStep.MOOD_ELSE, "I took all my pills")
        assertNull(chat.thread().report.values[SymptomStep.MOOD_ELSE])
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.asked())
        assertTrue(ChatLine.WORDS_CHECK in chat.turn().lines())
        chat.tap(SymptomStep.MED_MAX_WORDS, SymptomOption.MORE_THAN_IT_SAYS)
        assertEquals(SymptomStep.MED_SIGNS, chat.asked())
        chat.tap(SymptomStep.MED_SIGNS, SymptomOption.NO)
        chat.tap(SymptomStep.MED_PURPOSE, SymptomOption.BY_ACCIDENT)
        assertEquals(SymptomStep.MOOD_ELSE, chat.asked())
        chat.suggest(SymptomStep.MOOD_ELSE, SymptomOption.NOT_NOW.name)
        assertEquals(ChatMove.Prompt, chat.move())
        val close = chat.close()
        assertTrue(BotPiece.Line(ChatLine.CLOSE_NOW) in close)
        assertTrue(close.none { it is BotPiece.Line && it.line in setOf(ChatLine.CLOSE_TODAY, ChatLine.CLOSE_MIND) })
        assertTrue(close.none { it is BotPiece.Offer })
        assertTrue((chat.turn().reply as ChatReply.Topics).choices.isEmpty())
        val words = chat.firsts.single { it.second.role == WORDS_ROLE }.second
        assertFalse(recordFlashAllowed(chat.log.told(), chat.setting(), null, chat.thread(), words))
    }

    /** "Rather not say" whether it was on purpose: the mood's close still ends on not waiting. */
    @Test
    fun aMoodToldOfTooMuchClosesOnNowAfterAnAnswerAboutHarmToo() {
        val chat = MoodChat()
        chat.choose(Symptom.MOOD)
        chat.write("I took too many pills")
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.asked())
        chat.tap(SymptomStep.MED_MAX_WORDS, SymptomOption.MORE_THAN_IT_SAYS)
        chat.tap(SymptomStep.MED_SIGNS, SymptomOption.NO)
        chat.tap(SymptomStep.MED_PURPOSE, SymptomOption.RATHER_NOT_SAY)
        assertTrue(crisisAnswered(chat.thread().report))
        assertEquals(ChatMove.Prompt, chat.move())
        val close = chat.close()
        assertTrue(BotPiece.Line(ChatLine.CLOSE_NOW) in close)
        assertTrue(close.none { it is BotPiece.Offer })
    }

    /** A mood of an hour ago left as it is, after too much was told: the close still ends on not waiting. */
    @Test
    fun aMoodLeftAsItIsAfterTooMuchClosesOnNow() {
        val (chat, _) = withMood(ago = 60 * 60_000L, score = 3)
        chat.choose(Symptom.MOOD)
        assertEquals(SymptomStep.MOOD_UPDATE, chat.asked())
        chat.write("I took too many pills")
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.asked())
        chat.tap(SymptomStep.MED_MAX_WORDS, SymptomOption.MORE_THAN_IT_SAYS)
        chat.tap(SymptomStep.MED_SIGNS, SymptomOption.NO)
        chat.tap(SymptomStep.MED_PURPOSE, SymptomOption.BY_ACCIDENT)
        assertEquals(SymptomStep.MOOD_UPDATE, chat.asked())
        chat.tap(SymptomStep.MOOD_UPDATE, SymptomOption.LEAVE_IT)
        assertEquals(ChatMove.Prompt, chat.move())
        assertTrue(BotPiece.Line(ChatLine.CLOSE_NOW) in chat.close())
        assertTrue((chat.turn().reply as ChatReply.Topics).choices.isEmpty())
    }

    /**
     * An amount and a course's dose stay the answer though they read as too much — their own "more
     * than it says?" follows — and so does a name, which the medicine's questions read as typed.
     */
    @Test
    fun anAmountADoseOrANameStaysTheAnswer() {
        val amount = MedicineChat().start(Symptom.DOSE).type("нурофен").value("NOW")
            .type("10 tablets", SymptomStep.MED_AMOUNT)
        assertEquals(SymptomStep.MED_MAX, amount.step)
        val dose = MedicineChat().start(Symptom.COURSE).type("Sertraline", SymptomStep.COURSE_NAME)
            .type("3 tablets", SymptomStep.COURSE_DOSE)
        assertEquals("3 tablets", dose.report.values[SymptomStep.COURSE_DOSE])
        assertEquals(SymptomStep.COURSE_OFTEN, dose.step)
        val name = MedicineChat().start(Symptom.DOSE)
        name.type("all my pills", SymptomStep.MED_NAME)
        assertEquals("all my pills", name.report.values[SymptomStep.MED_NAME])
    }

    /**
     * The row the filer keeps the words' answers in — "is it there now? No" in a knee's thread — is
     * no record the person asked for, titled with their own first words: it never flashes "added".
     */
    @Test
    fun theRowOfTheWordsAnswersNeverFlashes() {
        val store = MemoryChatStore()
        val key = chatKey()
        val knee = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L")
        val asked = knee.wrote("my chest hurts a bit").settle()
        assertEquals(SymptomStep.CHEST_NOW, asked.asked())
        val no = asked.tapOn(SymptomOption.NO)
        val result = persist(ChatFiling(store, key), no, key)
        val (threadId, words) = result.firsts.single { it.second.role == WORDS_ROLE }
        val thread = no.threads.single { it.id == threadId }
        assertFalse(recordFlashAllowed(no.told(), setting, null, thread, words))
        assertTrue(recordFlashAllowed(no.told(), setting, null, thread, words.copy(role = "")))
    }

    /** A note's words of too much, read as a message's, show where to get advice now. */
    @Test
    fun aNoteWithWordsOfTooMuchShowsWhereToGetAdviceNow() {
        val tooMuch = R.string.med_chat_alert_too_much to R.string.med_chat_source_too_much
        assertEquals(listOf(tooMuch), noteSafety("I took all my pills", null))
        assertEquals(listOf(tooMuch), noteSafety("выпила все таблетки", null))
        assertEquals(listOf(ChatLine.CRISIS_SUPPORT.textRes), noteSafety("I want to die", null).map { it.first })
        assertEquals(listOf(ChatLine.SAFETY.textRes), noteSafety("I can't breathe", null).map { it.first })
        assertTrue(noteSafety("slept badly, took one paracetamol", null).isEmpty())
    }
}
