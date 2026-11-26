package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The DOSE thread: a dose of one of the person's courses is its slot, asked when only when
 * its time is an hour gone; a dose on its own is named, timed and measured, and "more than it
 * says?" comes before what it was for and before anything is recorded; after "more" or "not sure"
 * the urgent line, the signs to call for help now where the medicine's page has them, and whether it
 * was by accident; an emergency files nothing.
 */
class DoseThreadFlowTest {
    private val sertraline = course("Sertraline", start = CHAT_TODAY.minusDays(4))
    private val pill = course("Microgynon", id = 2, times = "540,1260", dose = "1 tablet")

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
    fun aDoseOfACourseInTimeIsItsOneOpenSlotAndHowItIsGoingInItsFirstWeeks() {
        val chat = MedicineChat(ChatEnv(courses = listOf(sertraline))).apply { now = todayAt(9 * 60 + 10) }
        chat.start(Symptom.DOSE)
        val pick = chat.reply as ChatReply.MedicinePick
        // One dose open today: the chip carries it, so one tap answers which medicine and which dose.
        assertEquals("540", pick.choices.single().slot)
        chat.value("C${sertraline.createdAt}@540", SymptomStep.MED_WHICH)
        chat.tap(SymptomOption.FEEL_BETTER, SymptomStep.MED_FEEL)
        assertNull(chat.step)
        assertEquals(listOf(SymptomStep.MED_WHICH, SymptomStep.MED_FEEL), chat.steps)
        val row = filed(chat).single()
        assertEquals("""{"medicationId":1,"slot":"540"}""", row.measurementPayloadJson)
        assertEquals(todayAt(9 * 60 + 10), row.occurredAt)
    }

    @Test
    fun aDoseAnHourPastItsTimeAsksWhenAndStandsAtItsTime() {
        val chat = MedicineChat(ChatEnv(courses = listOf(sertraline))).start(Symptom.DOSE)
        chat.value("C${sertraline.createdAt}@540", SymptomStep.MED_WHICH)
        val chips = (chat.reply as ChatReply.Values).choices.map { it.value }
        assertEquals(listOf("NOW", "SLOT", "540"), chips)
        chat.value("SLOT", SymptomStep.MED_WHEN)
        assertEquals(SymptomStep.MED_FEEL, chat.step)
        val row = filed(chat).single()
        assertEquals(todayAt(9 * 60), row.occurredAt)
        assertEquals("Sertraline", row.title)
    }

    @Test
    fun aDoseOnItsOwnIsNamedTimedMeasuredAndWhatItWasFor() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("нурофен", SymptomStep.MED_NAME)
            .value("NOW", SymptomStep.MED_WHEN)
            .value(AMOUNT_ONE, SymptomStep.MED_AMOUNT)
            .tap(SymptomOption.FOR_PAIN, SymptomStep.MED_WHY)
        assertNull(chat.step)
        assertEquals(
            listOf(SymptomStep.MED_NAME, SymptomStep.MED_WHEN, SymptomStep.MED_AMOUNT, SymptomStep.MED_WHY),
            chat.steps,
        )
        val row = filed(chat).single()
        assertEquals("нурофен", row.title)
        assertEquals("1 tablet", row.subtitle)
        assertEquals("""{"medicationId":0,"slot":"X${row.occurredAt}","for":"PAIN"}""", row.measurementPayloadJson)
        assertTrue(isOneOffDose(row))
    }

    @Test
    fun twoTabletsAskMoreThanItSaysBeforeWhatForAndBeforeAnythingIsRecorded() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("нурофен").value("NOW").value(AMOUNT_TWO)
        assertEquals(SymptomStep.MED_MAX, chat.step)
        assertTrue(filed(chat).isEmpty())
        chat.tap(SymptomOption.NO).tap(SymptomOption.FOR_PAIN, SymptomStep.MED_WHY)
        assertEquals("2 tablets", filed(chat).single().subtitle)
    }

    @Test
    fun moreThanItSaysIsTheUrgentLineThenWhetherItWasByAccident() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("нурофен").value("NOW").value(AMOUNT_TWO).tap(SymptomOption.MORE_THAN_IT_SAYS, SymptomStep.MED_MAX)
        assertEquals(listOf(SymptomAlert.DOSE_TOO_MUCH), chat.turn.alerts())
        // Ibuprofen's page puts too much at an urgent line, with no signs to call for help now.
        chat.tap(SymptomOption.BY_ACCIDENT, SymptomStep.MED_PURPOSE).tap(SymptomOption.FOR_PAIN, SymptomStep.MED_WHY)
        assertNull(chat.step)
        assertEquals(1, filed(chat).size)
        val thread = chat.log.threads.last()
        assertFalse(DoseScript.flashes(chat.log, thread, thread.filed.single()))
        assertTrue(ChatLine.CLOSE_NOW in chat.turn.lines())
    }

    @Test
    fun notSureIsTheUrgentLineToo() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("нурофен").value("NOW").value(AMOUNT_TWO).tap(SymptomOption.NOT_SURE, SymptomStep.MED_MAX)
        assertEquals(listOf(SymptomAlert.DOSE_TOO_MUCH), chat.turn.alerts())
        assertEquals(SymptomStep.MED_PURPOSE, chat.step)
    }

    @Test
    fun aMedicineNobodyKnowsAsksEveryNowSignAndAYesFilesNothing() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type(MED_UNKNOWN, SymptomStep.MED_NAME).value("NOW").value(AMOUNT_TWO).tap(SymptomOption.MORE_THAN_IT_SAYS)
        assertEquals(SymptomStep.MED_SIGNS, chat.step)
        assertEquals(R.string.med_chat_ask_signs_any, DoseScript.question(chat.state, SymptomStep.MED_SIGNS))
        chat.tap(SymptomOption.YES)
        assertEquals(listOf(SymptomAlert.EMERGENCY), chat.turn.alerts())
        assertNull(chat.step)
        assertTrue(filed(chat).isEmpty())
        assertTrue(chat.turn.pieces.none { it is BotPiece.Saved })
    }

    @Test
    fun solpadeinInCyrillicAsksTheNowSigns() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("солпадеин").value("NOW").value(AMOUNT_TWO).tap(SymptomOption.MORE_THAN_IT_SAYS)
        assertEquals(SymptomStep.MED_SIGNS, chat.step)
    }

    @Test
    fun everyEntryOutsideTheCheckedListAsksTheNowSigns() {
        KNOWN_MEDICINES.forEach { known ->
            val signs = signsFor(listOf(namedMedicine(known.keys.first())))
            assertEquals(known.id, known.inn.isNotEmpty() && known.inn in CHECKED_OVERDOSE_111, signs == SIGNS_NONE)
        }
        assertEquals(SIGNS_TCA, signsFor(listOf(namedMedicine("amitriptyline"))))
        assertEquals(SIGNS_OPIOID, signsFor(listOf(namedMedicine("tramadol"))))
        assertEquals(SIGNS_ANY, signsFor(emptyList()))
    }

    @Test
    fun onPurposeIsTheEmergencyAndNothingIsRecorded() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("нурофен").value("NOW").value(AMOUNT_TWO).tap(SymptomOption.MORE_THAN_IT_SAYS)
            .tap(SymptomOption.ON_PURPOSE, SymptomStep.MED_PURPOSE)
        assertEquals(listOf(SymptomAlert.EMERGENCY_MIND), chat.turn.alerts())
        assertNull(chat.step)
        assertTrue(filed(chat).isEmpty())
    }

    @Test
    fun anOverdoseToldAfterACrisisAnswerStillAsksIfOnPurpose() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("нурофен")
        chat.write("I want to die")
        assertEquals(SymptomStep.CRISIS_NOW, chat.step)
        chat.tap(SymptomOption.NO_SUCH_THOUGHTS)
        chat.value("NOW", SymptomStep.MED_WHEN).value(AMOUNT_TWO).tap(SymptomOption.MORE_THAN_IT_SAYS)
        assertEquals(SymptomStep.MED_PURPOSE, chat.step)
    }

    @Test
    fun sideEffectsAskTheSeriousOnesAndThePharmacistLineComesOnlyAfterNone() {
        fun seriousOf(vararg ticks: SymptomOption): MedicineChat {
            val chat = MedicineChat(ChatEnv(courses = listOf(sertraline))).apply { now = todayAt(9 * 60 + 10) }
            chat.start(Symptom.DOSE).value("C${sertraline.createdAt}@540").tap(SymptomOption.SIDE_EFFECTS)
            val options = (chat.reply as ChatReply.Choose).options
            assertFalse(SymptomOption.SERIOUS_COLLAPSE in options)
            return chat.tick(*ticks, expected = SymptomStep.MED_SERIOUS)
        }
        assertEquals(listOf(SymptomAlert.SIDE_EFFECTS), seriousOf(SymptomOption.NONE).turn.alerts())
        val high = seriousOf(SymptomOption.SERIOUS_HIGH).turn.alerts()
        assertEquals(listOf(SymptomAlert.MED_SERIOUS_SIDE_EFFECT), high)
        val harm = seriousOf(SymptomOption.SERIOUS_HARM)
        assertEquals(SymptomStep.CRISIS, harm.step)
        assertEquals(R.string.med_chat_ask_crisis_start, DoseScript.questionText(harm.state, SymptomStep.CRISIS)?.res)
    }

    @Test
    fun aTricyclicsSeriousSideEffectsOfferItsOwnEmergencyItems() {
        val amitriptyline = namedCourse(course("Amitriptyline", times = "1260"), CHAT_TODAY)
        val report = SymptomReport(symptom = Symptom.DOSE, meta = amitriptyline.facts(SymptomStep.MED_WHICH))
        val state = ChatState(report, female = false, earlier = null)
        val reply = DoseScript.reply(state, SymptomStep.MED_SERIOUS) as ChatReply.Choose
        assertTrue(SymptomOption.SERIOUS_COLLAPSE in reply.options)
        assertEquals(R.string.med_chat_serious_heart_tca, reply.labels[SymptomOption.SERIOUS_HEART])
        val harm = report.copy(
            answers = mapOf(SymptomStep.MED_SERIOUS to listOf(SymptomOption.SERIOUS_HARM)),
            chat = SymptomChat(listOf(ChatTurn.Asked(SymptomStep.SYMPTOM), ChatTurn.Asked(SymptomStep.MED_SERIOUS))),
        )
        assertTrue(firedRules(harm).any { it.alert == SymptomAlert.EMERGENCY_MIND })
        val collapse = harm.copy(answers = mapOf(SymptomStep.MED_SERIOUS to listOf(SymptomOption.SERIOUS_COLLAPSE)))
        assertTrue(firedRules(collapse).any { it.alert == SymptomAlert.EMERGENCY })
    }

    @Test
    fun aMissedDoseSaysTwoDosesOnlyWhereThePageSaysSoAndNotesNothing() {
        fun missed(medication: Medication): MedicineChat {
            val chat = MedicineChat(ChatEnv(courses = listOf(medication))).start(Symptom.DOSE)
            chat.value("C${medication.createdAt}", SymptomStep.MED_WHICH)
            val chips = (chat.reply as ChatReply.Values).choices.map { it.value }
            assertEquals(listOf("540", "1260", "X", "MISSED"), chips)
            return chat.value("MISSED", SymptomStep.MED_SLOT)
        }
        val pill = missed(pill)
        assertNull(pill.step)
        assertTrue(R.string.med_chat_missed_leaflet in pill.turn.notes())
        assertTrue(R.string.med_chat_nothing_missed in pill.turn.notes())
        assertTrue(filed(pill).isEmpty())
        val twice = missed(sertraline.copy(times = "540,1260"))
        assertTrue(R.string.med_chat_missed in twice.turn.notes())
        assertFalse(R.string.med_chat_missed_leaflet in twice.turn.notes())
    }

    @Test
    fun theWordsQuestionSkipsTheThreadsOwnOnlyForTheMedicineItsWordsNamed() {
        val words = SymptomReport(
            symptom = Symptom.FEVER,
            answers = mapOf(SymptomStep.MED_MAX_WORDS to listOf(SymptomOption.NO)),
            chat = SymptomChat(
                listOf(
                    ChatTurn.Wrote("I took too many paracetamol"),
                    ChatTurn.Asked(SymptomStep.MED_MAX_WORDS),
                    ChatTurn.Asked(SymptomStep.SYMPTOM),
                ),
            ),
        )
        fun steps(name: String, amount: String = AMOUNT_TWO): List<SymptomStep> {
            val report = SymptomReport(
                symptom = Symptom.DOSE,
                values = mapOf(
                    SymptomStep.MED_NAME to name,
                    SymptomStep.MED_WHEN to "NOW",
                    SymptomStep.MED_AMOUNT to amount,
                ),
                chat = SymptomChat(
                    listOf(SymptomStep.SYMPTOM, SymptomStep.MED_NAME, SymptomStep.MED_WHEN, SymptomStep.MED_AMOUNT)
                        .map { ChatTurn.Asked(it) },
                ),
            )
            val thread = ThreadContext(others = listOf(OtherThread(0, Symptom.FEVER, words, emptyList())))
            return doseSteps(ChatState(report, female = false, earlier = null, thread = thread))
        }
        assertFalse(SymptomStep.MED_MAX in steps("paracetamol"))
        assertTrue(SymptomStep.MED_MAX in steps("ibuprofen"))
        // An amount the person typed is asked about whatever was asked before: it can say anything.
        assertTrue(SymptomStep.MED_MAX in steps("paracetamol", "the whole box"))
    }

    @Test
    fun aDoseOfAnEarlierDayIsOfThatDayAtItsTimeOrAtMiddayWhenNotRemembered() {
        fun dose(answer: String): JournalEntry {
            val chat = MedicineChat(ChatEnv(courses = listOf(pill)), past = true).start(Symptom.DOSE)
            chat.value("C${pill.createdAt}", SymptomStep.MED_WHICH)
            assertEquals(R.string.med_chat_ask_slot_day, DoseScript.questionText(chat.state, SymptomStep.MED_SLOT)?.res)
            chat.value("540", SymptomStep.MED_SLOT)
            assertEquals(listOf("SLOT", "540", "UNKNOWN"), (chat.reply as ChatReply.Values).choices.map { it.value })
            chat.value(answer, SymptomStep.MED_WHEN)
            assertNull(chat.step)
            return filed(chat).single()
        }
        val day = CHAT_TODAY.minusDays(3)
        // A row of a day gone by is stamped a millisecond past the moment it stands at.
        val atSlot = dose("SLOT")
        assertTrue(atSlot.occurredAt - todayAt(9 * 60, day) in 0L..1L)
        val untimed = dose("UNKNOWN")
        assertEquals(day.toString(), untimed.day)
        assertTrue(untimed.occurredAt - middayOf(day, CHAT_ZONE) in 0L..1L)
        // The mark of a course's dose says its time was not said, as one outside a course does.
        assertTrue(statesNoTime(untimed))
        assertFalse(statesNoTime(atSlot))
    }

    /** [step]'s answer taken back with the pencil, as the page rewinds the last thread. */
    private fun MedicineChat.rewound(step: SymptomStep): MedicineChat = apply {
        val thread = log.threads.last()
        val at = thread.report.chat?.turns.orEmpty().indexOfFirst { it is ChatTurn.Asked && it.step == step }
        log = log.rewoundLog(thread.id, at).settle(setting)
    }

    @Test
    fun aTimeChangedWithThePencilMovesTheSameDoseToIt() {
        val store = MemoryChatStore()
        val filing = ChatFiling(store, "k")
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("нурофен").value("NOW").value(AMOUNT_ONE).tap(SymptomOption.FOR_PAIN)
        chat.file(filing, "k")
        val first = store.db.journal.values.single()
        assertEquals(CHAT_NOW, first.occurredAt)
        chat.rewound(SymptomStep.MED_WHEN).value("480", SymptomStep.MED_WHEN).value(AMOUNT_ONE)
            .tap(SymptomOption.FOR_PAIN)
        chat.file(filing, "k")
        val moved = store.db.journal.values.single()
        assertEquals(first.id, moved.id)
        assertEquals(todayAt(8 * 60), moved.occurredAt)
    }

    @Test
    fun aSlotChangedToYesterdaysMovesTheSameMarkToThatDay() {
        val store = MemoryChatStore()
        val filing = ChatFiling(store, "k")
        val twice = course("Sertraline", times = "540,1320", start = CHAT_TODAY.minusDays(100))
        val chat = MedicineChat(ChatEnv(courses = listOf(twice))).apply { now = todayAt(30) }.start(Symptom.DOSE)
        chat.value("C${twice.createdAt}", SymptomStep.MED_WHICH).value("540", SymptomStep.MED_SLOT)
        chat.file(filing, "k")
        val first = store.db.journal.values.single()
        assertEquals(CHAT_TODAY.toString(), first.day)
        // Half past midnight, the dose was yesterday's 22:00, still inside its grace: the same mark moves there.
        chat.rewound(SymptomStep.MED_SLOT).value("Y1320", SymptomStep.MED_SLOT).value("SLOT", SymptomStep.MED_WHEN)
        chat.file(filing, "k")
        val moved = store.db.journal.values.single()
        assertEquals(first.id, moved.id)
        assertEquals(CHAT_TODAY.minusDays(1).toString(), moved.day)
        assertEquals(todayAt(22 * 60, CHAT_TODAY.minusDays(1)), moved.occurredAt)
        assertEquals("""{"medicationId":1,"slot":"1320"}""", moved.measurementPayloadJson)
    }

    /**
     * Yesterday's 22:00 picked at half past midnight is yesterday's mark whatever its clock says: a time
     * confirmed with today's date would otherwise mark tonight's dose taken, and one picked with no date
     * would stand a day before it was taken.
     */
    @Test
    fun yesterdaysSlotKeepsItsDayWhenItWasTakenAfterMidnight() {
        val twice = course("Sertraline", times = "540,1320", start = CHAT_TODAY.minusDays(100))
        fun answered(taken: String): MedicineChat {
            val chat = MedicineChat(ChatEnv(courses = listOf(twice))).apply { now = todayAt(30) }.start(Symptom.DOSE)
            chat.value("C${twice.createdAt}", SymptomStep.MED_WHICH).value("Y1320", SymptomStep.MED_SLOT)
            return chat.value(taken, SymptomStep.MED_WHEN)
        }
        val yesterday = CHAT_TODAY.minusDays(1)
        for (taken in listOf("$CHAT_TODAY@20", "20")) {
            val chat = answered(taken)
            assertNull(taken, chat.step)
            val row = filed(chat).single()
            assertEquals(taken, yesterday.toString(), row.day)
            assertEquals(taken, """{"medicationId":1,"slot":"1320"}""", row.measurementPayloadJson)
            assertEquals(taken, todayAt(20), row.occurredAt)
        }
        // Half past ten with no date is yesterday evening, half an hour after the slot.
        assertEquals(todayAt(22 * 60 + 30, yesterday), filed(answered("1350")).single().occurredAt)
        // A quarter to one has not come yet: asked again, never filed as a quarter to one the night before.
        assertEquals(SymptomStep.MED_WHEN_AGAIN, answered("45").step)
    }

    /** A course that ended yesterday is picked past midnight while its last dose is inside its grace, and marks it. */
    @Test
    fun aCourseEndedYesterdayMarksItsLastDoseInsideItsGrace() {
        val yesterday = CHAT_TODAY.minusDays(1)
        val ended = course("Amoxicillin", times = "1380", start = CHAT_TODAY.minusDays(7), end = yesterday)
        val chat = MedicineChat(ChatEnv(courses = listOf(ended))).apply { now = todayAt(30) }.start(Symptom.DOSE)
        assertEquals(listOf(ended.createdAt), (chat.reply as ChatReply.MedicinePick).choices.map { it.createdAt })
        chat.value("C${ended.createdAt}", SymptomStep.MED_WHICH)
        assertEquals(listOf("Y1380", "X", "MISSED"), (chat.reply as ChatReply.Values).choices.map { it.value })
        chat.value("Y1380", SymptomStep.MED_SLOT).value("SLOT", SymptomStep.MED_WHEN)
        assertNull(chat.step)
        val row = filed(chat).single()
        assertEquals(yesterday.toString() to todayAt(23 * 60, yesterday), row.day to row.occurredAt)
        assertEquals("""{"medicationId":1,"slot":"1380"}""", row.measurementPayloadJson)
    }

    /**
     * A record's fingerprint is stored with the chat and compared in the next run of the app, where an
     * option is another object with another hash of its own: the numbers are what every run makes of
     * these answers. One that differed would file again the dose a person had undone before the app closed.
     */
    @Test
    fun aDosesFingerprintIsTheSameInEveryRunOfTheApp() {
        val asked = listOf(
            SymptomStep.SYMPTOM,
            SymptomStep.MED_NAME,
            SymptomStep.MED_WHEN,
            SymptomStep.MED_AMOUNT,
            SymptomStep.MED_WHY,
            SymptomStep.MED_PAIN_LINK,
        )
        val report = SymptomReport(
            symptom = Symptom.DOSE,
            answers = mapOf(
                SymptomStep.MED_WHY to listOf(SymptomOption.FOR_PAIN),
                SymptomStep.MED_PAIN_LINK to listOf(SymptomOption.CHANGE_IT),
            ),
            chat = SymptomChat(asked.map { ChatTurn.Asked(it) }),
            values = mapOf(
                SymptomStep.MED_NAME to "Nurofen",
                SymptomStep.MED_WHEN to "NOW",
                SymptomStep.MED_AMOUNT to AMOUNT_ONE,
            ),
            meta = mapOf("MED_WHEN.at" to "1791201600000", "MED_WHY.pain" to "1791198000000"),
        )
        val thread = ChatThread(0, report)
        assertEquals(301_466_348, doseSig(thread, DOSE_ROLE))
        assertEquals(1_797_254_952, doseSig(thread, DOSE_PAIN_ROLE))
    }

    @Test
    fun aCourseForMoodKeepsThatAnswerWithItsDosesForTheChatsAfter() {
        val duloxetine = course("Duloxetine", times = "540", start = CHAT_TODAY.minusDays(40))
        fun dose(recent: List<String>): MedicineChat {
            val chats = recent.map { RecentChat(it, todayAt(9 * 60) - MED_DAY_MS) }
            val chat = MedicineChat(ChatEnv(courses = listOf(duloxetine), recentChats = chats))
            chat.now = todayAt(9 * 60 + 10)
            return chat.start(Symptom.DOSE).value("C${duloxetine.createdAt}@540", SymptomStep.MED_WHICH)
        }
        // No chat the chat reads said what it is for: not asked of a medicine for pain as well.
        assertNull(dose(emptyList()).step)
        // A dose of it noted since the chat that added it kept "mood or nerves": asked, and kept again.
        val noted = SymptomReport(
            symptom = Symptom.DOSE,
            meta = mapOf("MED_WHICH.c" to duloxetine.createdAt.toString(), "MED_WHICH.mood" to "1"),
        )
        val stored = encodeChatLog(ChatLog(threads = listOf(ChatThread(0, noted)), next = 1), pending = false)
        val carried = dose(listOf(stored))
        assertEquals(SymptomStep.MED_FEEL, carried.step)
        assertEquals("1", carried.report.meta["MED_WHICH.mood"])
    }

    /**
     * What a course was said to be for is read back over a medicine's first weeks: the chat that added
     * duloxetine "for mood or nerves" forty days ago still brings "how have you been feeling?",
     * as the chats the chat reads go back as far as that question is asked.
     */
    @Test
    fun aCourseAddedForMoodWeeksAgoStillAsksHowItIsGoing() {
        assertTrue(RECENT_CHAT_DAYS >= MED_START_WINDOW_DAYS)
        val duloxetine =
            course("Duloxetine", times = "540", start = CHAT_TODAY.minusDays(40), createdDaysAgo = 40)
        val added = SymptomReport(
            symptom = Symptom.COURSE,
            answers = mapOf(SymptomStep.COURSE_FOR to listOf(SymptomOption.FOR_MOOD)),
            chat = SymptomChat(listOf(ChatTurn.Asked(SymptomStep.SYMPTOM), ChatTurn.Asked(SymptomStep.COURSE_FOR))),
        )
        val link = FiledLink(LINK_COURSE, at = duloxetine.createdAt, title = "Duloxetine")
        val log = ChatLog(threads = listOf(ChatThread(0, added, filed = listOf(link))), next = 1)
        val stored = RecentChat(encodeChatLog(log, pending = false), duloxetine.createdAt)
        val chat = MedicineChat(ChatEnv(courses = listOf(duloxetine), recentChats = listOf(stored)))
        chat.now = todayAt(9 * 60 + 10)
        chat.start(Symptom.DOSE).value("C${duloxetine.createdAt}@540", SymptomStep.MED_WHICH)
        assertEquals(SymptomStep.MED_FEEL, chat.step)
    }

    /**
     * A course whose times were edited after a dose was marked: the Medicine tab
     * shows that mark on the nearest time still open, so the chat offers the one dose the tab shows
     * open, and a mark on the one it shows taken — picked by a chat that did not know of the mark — is
     * refused, never written to make the evening's dose read as taken.
     */
    @Test
    fun aCourseWhoseTimesWereEditedIsMarkedAsTheMedicineTabSettlesIt() {
        val edited = course("Metformin", times = "510,1200", start = CHAT_TODAY.minusDays(30))
        fun store(): Pair<MemoryChatStore, JournalEntry> {
            val store = MemoryChatStore()
            store.db.courses[edited.id] = edited
            val morning = JournalEntry(
                day = CHAT_TODAY.toString(),
                occurredAt = todayAt(8 * 60 + 5),
                kind = KIND_MED,
                title = "Metformin",
                calories = 0.0,
                measurementPayloadJson = """{"medicationId":${edited.id},"slot":"480"}""",
            )
            return store to store.db.put(morning)
        }
        // Marked at 08:05 under 08:00, before the times moved: it holds 08:30, so 20:00 is the one dose open.
        val (shown, morning) = store()
        val evening = MedicineChat(ChatEnv(courses = listOf(edited), dayJournal = listOf(morning)))
        evening.now = todayAt(20 * 60 + 10)
        evening.start(Symptom.DOSE)
        assertEquals("1200", (evening.reply as ChatReply.MedicinePick).choices.single().slot)
        evening.value("C${edited.createdAt}@1200", SymptomStep.MED_WHICH)
        evening.file(ChatFiling(shown, "k"), "k")
        val doses = plannedDoses(listOf(edited), shown.db.journal.values.toList(), CHAT_TODAY)
        assertEquals(morning.id, doses.first { it.slot == "510" }.takenEntryId)
        assertTrue(doses.first { it.slot == "1200" }.taken)
        // A chat that did not know of the morning's mark offered 08:30: the door refuses it.
        val (unseen, _) = store()
        val unaware = MedicineChat(ChatEnv(courses = listOf(edited))).apply { now = todayAt(8 * 60 + 40) }
        unaware.start(
            Symptom.DOSE,
        ).value("C${edited.createdAt}", SymptomStep.MED_WHICH).value("510", SymptomStep.MED_SLOT)
        unaware.file(ChatFiling(unseen, "k"), "k")
        assertEquals(1, unseen.db.journal.size)
        assertEquals(LinkState.REFUSED, unaware.log.threads.last().filed.single().state)
        // Its close names the mark the tab shows on 08:30, the day read again as the page reloads it.
        unaware.env = unaware.env.copy(dayJournal = unseen.db.journal.values.toList())
        val note = unaware.turn.pieces.filterIsInstance<BotPiece.Note>()
            .single { it.text.res == R.string.med_chat_already_marked }
        assertEquals(listOf(PieceArg.Moment(todayAt(8 * 60 + 5))), note.text.args)
    }

    /**
     * A slot the course no longer has that day — its times changed on the Medicine tab while the dose
     * was told — is refused with nothing marked, and the close says nothing new is noted: never that
     * the dose was marked already.
     */
    @Test
    fun aSlotTheCourseNoLongerHasIsNeverSaidToBeMarkedAlready() {
        val store = MemoryChatStore()
        val edited = sertraline.copy(times = "600")
        store.db.courses[edited.id] = edited
        val chat = MedicineChat(ChatEnv(courses = listOf(sertraline))).apply { now = todayAt(9 * 60 + 10) }
        chat.start(Symptom.DOSE).value("C${sertraline.createdAt}@540", SymptomStep.MED_WHICH)
            .tap(SymptomOption.FEEL_BETTER, SymptomStep.MED_FEEL)
        chat.env = ChatEnv(courses = listOf(edited))
        chat.file(ChatFiling(store, "k"), "k")
        assertTrue(store.db.journal.isEmpty())
        assertEquals(LinkState.REFUSED, chat.log.threads.last().filed.single().state)
        val notes = chat.turn.notes()
        assertTrue(R.string.med_chat_nothing_new in notes)
        assertFalse(R.string.med_chat_already_marked in notes)
    }

    @Test
    fun wordsOfADoseOrOfAPrescriptionStartTheirThreadWithTheNameProposed() {
        val dose = MedicineChat().write("выпила нурофен")
        assertEquals(SymptomStep.MED_NAME, dose.step)
        assertEquals("Нурофен", (dose.reply as ChatReply.TextAsk).suggestions.first().value)
        val prescribed = MedicineChat().write("врач назначил сертралин")
        assertEquals(SymptomStep.COURSE_NAME, prescribed.step)
        assertEquals("Сертралин", (prescribed.reply as ChatReply.TextAsk).suggestions.first().value)
        // A chat of a day gone by adds no course: the words are met with the line that says where one is added.
        val past = MedicineChat(past = true).write("врач назначил сертралин")
        assertTrue(past.step != SymptomStep.COURSE_NAME)
    }

    @Test
    fun anAntidepressantNotedOnItsOwnOffersToAddItToTheMedicines() {
        val chat = MedicineChat().start(Symptom.DOSE)
        chat.type("сертралин").value("NOW").value(AMOUNT_ONE).tap(SymptomOption.FOR_MOOD, SymptomStep.MED_WHY)
        assertNull(chat.step)
        assertTrue(R.string.med_chat_ask_add_course in chat.turn.notes())
        val offers = DoseScript.followUps(chat.state)
        assertEquals(listOf(Symptom.MOOD, Symptom.LOW_MOOD, Symptom.COURSE), offers.map { it.topic })
        assertEquals("сертралин", offers.last().mention?.detail)
        // The same medicine already in the list, under another name: it is not offered again.
        val withCourse = MedicineChat(ChatEnv(courses = listOf(sertraline))).start(Symptom.DOSE)
        withCourse.value("N").type("Золофт").value("N", SymptomStep.MED_IS_COURSE).value("NOW").value(AMOUNT_ONE)
            .tap(SymptomOption.FOR_MOOD)
        assertTrue(DoseScript.followUps(withCourse.state).none { it.topic == Symptom.COURSE })
    }

    @Test
    fun theCoursesToPickAreTheDaysInOrderWithTheOneTapChipAndAllPastEight() {
        val ibuprofen = course("Nurofen", id = 3, times = "540,1260")
        val vitamin = course("Vitamin D", id = 4, times = "480")
        val later = course("Melatonin", id = 5, createdDaysAgo = 0)
        // A course written down after the day of the chat is no dose of that day; the rest by their first dose.
        val past = MedicineChat(ChatEnv(courses = listOf(ibuprofen, later, vitamin)), past = true).start(Symptom.DOSE)
        assertEquals(listOf("Vitamin D", "Nurofen"), (past.reply as ChatReply.MedicinePick).choices.map { it.name })
        val today = MedicineChat(ChatEnv(courses = listOf(vitamin, ibuprofen, sertraline)))
        today.start(Symptom.DOSE, TopicMention(Symptom.DOSE, DETAIL_PAINKILLER))
        val pick = today.reply as ChatReply.MedicinePick
        // The class the words named first, then the rest by their next open dose.
        assertEquals(listOf("Nurofen", "Vitamin D", "Sertraline"), pick.choices.map { it.name })
        // Two doses open: no chip answers which one; one open: its chip carries it.
        assertEquals(listOf(null, "480", "540"), pick.choices.map { it.slot })
        assertFalse(pick.more)
        val nine = (1L..9L).map { course("Course $it", id = 10 + it) }
        val many = MedicineChat(ChatEnv(courses = nine)).start(Symptom.DOSE).reply as ChatReply.MedicinePick
        assertTrue(many.more)
        assertEquals(9, many.choices.size)
    }
}
