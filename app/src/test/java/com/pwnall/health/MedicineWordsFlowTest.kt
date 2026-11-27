package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The words' own "more than it says?": wherever too much is told — in a message, in a name
 * typed, of the person's own course or of the thread's medicine with no name at all — it is asked
 * before anything else, in its own words, never in place of a dose's own question nor taking its
 * answer, and once more for words told after a "no"; after "more" the signs to call for help now are
 * every medicine's named, and nothing is recorded while any of it waits.
 */
class MedicineWordsFlowTest {
    private val sertraline = course("Sertraline", times = "1260", start = CHAT_TODAY.minusDays(100))

    @After
    fun clock() {
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }

    private fun filed(chat: MedicineChat): List<JournalEntry> {
        val store = MemoryChatStore()
        chat.file(ChatFiling(store, "k"), "k")
        return store.db.journal.values.toList()
    }

    @Test
    fun tooMuchTypedAsANameInAThreadWithNoMessageIsAskedBeforeAnythingIsRecorded() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("сертралин, выпила всю пачку", SymptomStep.MED_NAME)
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.step)
        assertTrue(ChatLine.WORDS_CHECK in chat.turn.lines())
        assertEquals(R.string.med_chat_ask_max_words, DoseScript.question(chat.state, SymptomStep.MED_MAX_WORDS))
        chat.tap(SymptomOption.MORE_THAN_IT_SAYS)
        assertEquals(listOf(SymptomAlert.DOSE_TOO_MUCH), chat.turn.alerts())
        // Sertraline's page has no signs to call for: whether it was by accident comes next.
        assertEquals(SymptomStep.MED_PURPOSE, chat.step)
        assertTrue(filed(chat).isEmpty())
        // The name is the medicine's, never the sentence.
        assertEquals("сертралин", threadMedicine(chat.report)?.name)
    }

    @Test
    fun tooMuchTypedAsTheNameOfAPrescriptionIsAskedToo() {
        val chat = MedicineChat().start(Symptom.COURSE)
        chat.type("амитриптилин, выпила всю пачку", SymptomStep.COURSE_NAME)
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.step)
        assertEquals(
            listOf(SymptomOption.NO, SymptomOption.MORE_THAN_IT_SAYS, SymptomOption.NOT_SURE),
            (chat.reply as ChatReply.Choose).options,
        )
        chat.tap(SymptomOption.MORE_THAN_IT_SAYS)
        assertEquals(SymptomStep.MED_SIGNS, chat.step)
        assertEquals(R.string.med_chat_ask_signs_tca, CourseScript.question(chat.state, SymptomStep.MED_SIGNS))
        chat.tap(SymptomOption.YES)
        assertEquals(listOf(SymptomAlert.EMERGENCY), chat.turn.alerts())
        assertNull(chat.step)
    }

    @Test
    fun theDoseAPrescriptionSaysIsNoDoseTaken() {
        // A message in the thread: its typed answers are read with it, the dose among them no more.
        val chat = MedicineChat().start(Symptom.COURSE).write("the doctor wants a new one")
        chat.type("Vitamin D", SymptomStep.COURSE_NAME).type("3 tablets", SymptomStep.COURSE_DOSE)
        assertEquals(SymptomStep.COURSE_OFTEN, chat.step)
    }

    @Test
    fun tooManyOfTheOwnCourseWrittenAtTheStartIsAskedFirst() {
        val propranolol = course("Propranolol", times = "540")
        val chat = MedicineChat(ChatEnv(courses = listOf(propranolol))).write("I took too many propranolol")
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.step)
        assertTrue(filed(chat).isEmpty())
    }

    /**
     * Two tablets of a tricyclic whose course says one are "more than it says?" before anything is
     * filed, and after "more" the tricyclic's signs («две таблетки» is never filed as one scheduled
     * dose with no word; NHS: "more than your prescribed dose of amitriptyline, even if you do not have
     * any symptoms"); two of a course that says two are its dose.
     */
    @Test
    fun moreTabletsThanATricyclicsCourseSaysAreAskedAboutBeforeTheDose() {
        val one = course("Amitriptyline", times = "720", dose = "1 tablet", start = CHAT_TODAY.minusDays(100))
        val chat = MedicineChat(ChatEnv(courses = listOf(one))).write("выпила две таблетки амитриптилина")
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.step)
        assertTrue(filed(chat).isEmpty())
        chat.tap(SymptomOption.MORE_THAN_IT_SAYS)
        assertEquals(SymptomStep.MED_SIGNS, chat.step)
        assertEquals(R.string.med_chat_ask_signs_tca, DoseScript.question(chat.state, SymptomStep.MED_SIGNS))

        val two = course("Amitriptyline", times = "720", dose = "2 tablets", start = CHAT_TODAY.minusDays(100))
        val asPrescribed = MedicineChat(ChatEnv(courses = listOf(two))).write("выпила две таблетки амитриптилина")
        assertFalse(asPrescribed.step == SymptomStep.MED_MAX_WORDS)
    }

    @Test
    fun aCountOfAnOwnCourseIsAskedAboutInTheThreadItWasWrittenIn() {
        val propranolol = course("Propranolol", times = "540")
        // The core reads the words with the person's courses: a count of one the list does not know is
        // asked about where it was written, first of all.
        val chat = MedicineChat(
            ChatEnv(courses = listOf(propranolol)),
        ).write("I took 5 propranolol and now I feel dizzy")
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.step)
        assertTrue(ChatLine.WORDS_CHECK in chat.turn.lines())
        chat.tap(SymptomOption.NO)
        chat.log = chat.log.endedThread()
        // Asked there already: a dose of it does not ask again.
        assertEquals(SymptomStep.MED_WHICH, chat.start(Symptom.DOSE, TopicMention(Symptom.DOSE, "propranolol")).step)
    }

    /**
     * Asked of the person's own course, the words' questions stay theirs once the course is deleted or
     * renamed: what is still to ask after "more" is asked, the row that keeps the answers is not taken
     * back, and the transcript still reads them as brought by the message.
     */
    @Test
    fun aCourseDeletedAfterItsQuestionTakesBackNothingTheWordsBrought() {
        val propranolol = course("Propranolol", times = "540")
        val store = MemoryChatStore()
        val filing = ChatFiling(store, "k")
        val chat = MedicineChat(ChatEnv(courses = listOf(propranolol))).write("I took 5 propranolol")
        chat.tap(SymptomOption.MORE_THAN_IT_SAYS, SymptomStep.MED_MAX_WORDS).file(filing, "k")
        chat.env = ChatEnv()
        if (chat.step == SymptomStep.MED_SIGNS) chat.tap(SymptomOption.NO)
        chat.tap(SymptomOption.BY_ACCIDENT, SymptomStep.MED_PURPOSE).file(filing, "k")
        val words = decodeSymptomPayload(store.db.journal.values.single().measurementPayloadJson)
        assertEquals(listOf(SymptomOption.MORE_THAN_IT_SAYS), words.answers[SymptomStep.MED_MAX_WORDS])
        assertEquals(listOf(SymptomOption.BY_ACCIDENT), words.answers[SymptomStep.MED_PURPOSE])
        val report = chat.report
        assertTrue(
            SymptomStep.MED_MAX_WORDS in wordSteps(checkNotNull(report.chat), report, emptyList()),
        )
    }

    @Test
    fun aDoseAfterAnEarlierMessageAsksAboutACountOfAnOwnCourseItNamed() {
        val propranolol = course("Propranolol", times = "540")
        fun dose(message: String): MedicineChat {
            // Written before the course was: the message's own thread could not read it as one.
            val chat = MedicineChat().write(message)
            if (chat.step == SymptomStep.MED_MAX_WORDS) chat.tap(SymptomOption.NO)
            chat.log = chat.log.endedThread()
            chat.env = ChatEnv(courses = listOf(propranolol))
            return chat.start(Symptom.DOSE, TopicMention(Symptom.DOSE, "propranolol"))
        }
        // A count of a course the list does not know, written before it was a course: a dose asks first.
        val counted = dose("I took 5 propranolol and now I feel dizzy")
        assertEquals(SymptomStep.MED_MAX_WORDS, counted.step)
        // "No" to it is no answer about another dose noted here: its own question stays.
        counted.tap(SymptomOption.NO).value(MED_OTHER, SymptomStep.MED_WHICH).type("парацетамол")
            .value("NOW").value(AMOUNT_TWO)
        assertEquals(SymptomStep.MED_MAX, counted.step)
        // Asked once in the chat, never again in a later thread.
        counted.tap(SymptomOption.NO).tap(SymptomOption.FOR_PAIN)
        counted.log = counted.log.endedThread()
        assertEquals(SymptomStep.MED_WHICH, counted.start(Symptom.DOSE).step)
        // Asked in the message's own thread already: not again.
        assertEquals(SymptomStep.MED_WHICH, dose("I took too many propranolol").step)
    }

    @Test
    fun tooManyWrittenInADoseThreadOfACourseIsAboutThatCourse() {
        val chat = MedicineChat(ChatEnv(courses = listOf(sertraline))).start(Symptom.DOSE)
        // The 21:00 dose is hours away at noon: which dose is asked, nothing is marked yet.
        chat.value("C${sertraline.createdAt}", SymptomStep.MED_WHICH)
        assertEquals(SymptomStep.MED_SLOT, chat.step)
        chat.write("выпила слишком много")
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.step)
        assertTrue(ChatLine.WORDS_CHECK in chat.turn.lines())
    }

    @Test
    fun wordsWhileADosesOwnQuestionWaitsAreAskedInTheirOwnWordsAndLeaveItsOwn() {
        val chat = MedicineChat().start(Symptom.DOSE).type("парацетамол").value("NOW").value(AMOUNT_TWO)
        assertEquals(SymptomStep.MED_MAX, chat.step)
        chat.write("and I took a whole box of tramadol")
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.step)
        assertTrue(ChatLine.WORDS_CHECK in chat.turn.lines())
        // "No" to the words is no answer about the two tablets of paracetamol.
        chat.tap(SymptomOption.NO, SymptomStep.MED_MAX_WORDS)
        assertEquals(SymptomStep.MED_MAX, chat.step)
        assertEquals(R.string.med_chat_ask_max, DoseScript.question(chat.state, SymptomStep.MED_MAX))
    }

    @Test
    fun moreOfAnotherMedicineAsksTheSignsOfEveryMedicineNamed() {
        val chat = MedicineChat().start(Symptom.DOSE).type("парацетамол")
        chat.write("and I took a whole box of tramadol")
        chat.tap(SymptomOption.MORE_THAN_IT_SAYS, SymptomStep.MED_MAX_WORDS)
        assertEquals(SymptomStep.MED_SIGNS, chat.step)
        assertEquals(R.string.med_chat_ask_signs_opioid, DoseScript.question(chat.state, SymptomStep.MED_SIGNS))
    }

    @Test
    fun aNoToADosesOwnQuestionThenWordsOfTooMuchBringTheWordsQuestion() {
        val chat = MedicineChat().start(Symptom.DOSE).type("нурофен").value("NOW").value(AMOUNT_TWO)
        chat.tap(SymptomOption.NO, SymptomStep.MED_MAX)
        chat.write("actually I took too many nurofen")
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.step)
        chat.tap(SymptomOption.MORE_THAN_IT_SAYS)
        assertEquals(listOf(SymptomAlert.DOSE_TOO_MUCH), chat.turn.alerts())
        assertEquals(SymptomStep.MED_PURPOSE, chat.step)
    }

    @Test
    fun wordsOfTooMuchAfterANoToTheWordsAreAskedOnceMore() {
        val chat = MedicineChat().write("I took too many paracetamol")
        chat.tap(SymptomOption.NO, SymptomStep.MED_MAX_WORDS)
        assertTrue(chat.step != SymptomStep.MED_MAX_AGAIN)
        chat.write("well, more than the box says")
        assertEquals(SymptomStep.MED_MAX_AGAIN, chat.step)
        chat.tap(SymptomOption.NOT_SURE)
        assertEquals(listOf(SymptomAlert.DOSE_TOO_MUCH), chat.turn.alerts())
        assertEquals(SymptomStep.MED_PURPOSE, chat.step)
    }

    @Test
    fun anOverdoseWrittenAsSuchAsksWhetherThePersonIsSafeThenMoreThanItSays() {
        val chat = MedicineChat().write("I accidentally took an overdose")
        assertEquals(SymptomStep.CRISIS_NOW, chat.step)
        chat.tap(SymptomOption.NO_SUCH_THOUGHTS)
        assertEquals(SymptomStep.MED_MAX_WORDS, chat.step)
        chat.tap(SymptomOption.MORE_THAN_IT_SAYS)
        // No medicine named: every sign is asked.
        assertEquals(R.string.med_chat_ask_signs_any, DoseScript.question(chat.state, SymptomStep.MED_SIGNS))
    }

    @Test
    fun aDoseOfACourseWaitsForTheSignsAnEarlierMoreLeftUnasked() {
        val amitriptyline = course("Amitriptyline", id = 2, times = "720", start = CHAT_TODAY.minusDays(100))
        val chat = MedicineChat(ChatEnv(courses = listOf(amitriptyline))).write("I took too many paracetamol")
        chat.tap(SymptomOption.MORE_THAN_IT_SAYS, SymptomStep.MED_MAX_WORDS)
            .tap(SymptomOption.BY_ACCIDENT, SymptomStep.MED_PURPOSE)
        chat.log = chat.log.endedThread()
        chat.start(Symptom.DOSE)
        // The one dose at noon is carried on the chip; a tricyclic's signs were never asked.
        chat.value("C${amitriptyline.createdAt}@720", SymptomStep.MED_WHICH)
        assertEquals(SymptomStep.MED_SIGNS, chat.step)
        assertEquals(R.string.med_chat_ask_signs_tca, DoseScript.question(chat.state, SymptomStep.MED_SIGNS))
        assertTrue(filed(chat).none { it.kind == KIND_MED })
        chat.tap(SymptomOption.NO)
        assertEquals(1, filed(chat).count { it.kind == KIND_MED })
        // Its card stands after the answer that completed it.
        assertTrue(chat.turn.pieces.any { it is BotPiece.Saved })
    }

    @Test
    fun noRecordFlashesAfterALineToGetAdviceNowAnywhereInTheChat() {
        val chat = MedicineChat(ChatEnv(courses = listOf(sertraline))).write("I took too many paracetamol")
        chat.tap(SymptomOption.MORE_THAN_IT_SAYS, SymptomStep.MED_MAX_WORDS)
            .tap(SymptomOption.BY_ACCIDENT, SymptomStep.MED_PURPOSE)
        chat.log = chat.log.endedThread()
        chat.start(Symptom.DOSE)
        // A dose noted in the next thread: the line to get advice now stands above it.
        val link = FiledLink(KIND_MED, at = CHAT_NOW, title = "Sertraline")
        assertFalse(DoseScript.flashes(chat.log, chat.log.threads.last(), link))
        val course = FiledLink(LINK_COURSE, at = CHAT_NOW, title = "Sertraline")
        assertFalse(CourseScript.flashes(chat.log, chat.log.threads.last(), course))
        val calm = MedicineChat().start(Symptom.DOSE)
        assertTrue(DoseScript.flashes(calm.log, calm.log.threads.last(), link))
        // The page's flash rule asks the topic too: the turn showing is no urgent one, the flash still waits.
        assertTrue(recordFlashAllowed(chat.log, chat.setting, null))
        assertFalse(recordFlashAllowed(chat.log, chat.setting, null, chat.log.threads.last(), link))
        assertTrue(recordFlashAllowed(calm.log, calm.setting, null, calm.log.threads.last(), link))
    }

    /**
     * Every flash of the record asks the topic too, not only its first write: Save again or Undo on the
     * dose's card, and the dose rewritten after a rewind, say nothing after a line to get advice now.
     */
    @Test
    fun aCardOrARewriteFlashesNothingAfterALineToGetAdviceNow() {
        val chat = MedicineChat(ChatEnv(courses = listOf(sertraline))).write("I took too many paracetamol")
        chat.tap(SymptomOption.MORE_THAN_IT_SAYS, SymptomStep.MED_MAX_WORDS)
            .tap(SymptomOption.BY_ACCIDENT, SymptomStep.MED_PURPOSE)
        chat.log = chat.log.endedThread()
        chat.start(Symptom.DOSE)
        val calm = MedicineChat().start(Symptom.DOSE)
        val link = FiledLink(KIND_MED, at = CHAT_NOW, title = "Sertraline", sig = 1)
        for (again in listOf(true, false)) {
            assertFalse(cardFlashAllowed(chat.log, chat.setting, null, chat.log.threads.last(), link, again))
            assertTrue(cardFlashAllowed(calm.log, calm.setting, null, calm.log.threads.last(), link, again))
        }
        // Saved again and refused once more: nothing is said of it.
        val refused = link.copy(state = LinkState.REFUSED)
        assertFalse(cardFlashAllowed(calm.log, calm.setting, null, calm.log.threads.last(), refused, again = true))
        fun rewrites(of: MedicineChat): List<FiledLink> {
            val last = of.log.threads.last()
            val sent = of.log.copy(threads = of.log.threads.dropLast(1) + last.copy(filed = listOf(link)))
            val result = FilingResult(mapOf(last.id to listOf(link.copy(sig = 2))), emptyList(), emptyList())
            return rewriteFlashes(of.log, of.setting, null, sent, result)
        }
        assertTrue(rewrites(chat).isEmpty())
        assertEquals(listOf(2), rewrites(calm).map { it.sig })
    }

    @Test
    fun theOneTapChipNeverCarriesADoseHoursAwayNorTodaysWhileYesterdaysWaits() {
        fun slots(env: ChatEnv, at: Long): List<String?> {
            val chat = MedicineChat(env).apply { now = at }.start(Symptom.DOSE)
            return (chat.reply as ChatReply.MedicinePick).choices.map { it.slot }
        }
        val evening = ChatEnv(courses = listOf(sertraline))
        assertEquals(listOf<String?>(null), slots(evening, todayAt(12 * 60)))
        assertEquals(listOf<String?>("1260"), slots(evening, todayAt(20 * 60 + 30)))
        // Half past midnight, today's 00:20 the one open dose: yesterday's 23:00, inside its grace,
        // is asked about first.
        val night = course("Sertraline", times = "20,1380", start = CHAT_TODAY.minusDays(100))
        fun mark(day: LocalDate) = medTakenEntry(
            plannedDoses(listOf(night), emptyList(), day).first { it.slot == "1380" },
            day,
            todayAt(0, day),
        )
        val today = mark(CHAT_TODAY)
        val waiting = ChatEnv(courses = listOf(night), dayJournal = listOf(today))
        assertEquals(listOf<String?>(null), slots(waiting, todayAt(30)))
        val both = waiting.copy(dayJournal = listOf(today, mark(CHAT_TODAY.minusDays(1))))
        assertEquals(listOf<String?>("20"), slots(both, todayAt(30)))
    }

    @Test
    fun anAmountTypedIsKeptAsTypedAndAskedAbout() {
        val chat = MedicineChat().start(Symptom.DOSE).type("нурофен").value("NOW").type("1", SymptomStep.MED_AMOUNT)
        assertEquals(SymptomStep.MED_MAX, chat.step)
        chat.tap(SymptomOption.NO).tap(SymptomOption.FOR_PAIN)
        assertEquals("1", filed(chat).single().subtitle)
    }
}

/** The last thread taken as over, as the router leaves a thread whose questions are done. */
private fun ChatLog.endedThread(): ChatLog = updateLast { it.copy(chat = it.chat?.copy(ended = true)) }
