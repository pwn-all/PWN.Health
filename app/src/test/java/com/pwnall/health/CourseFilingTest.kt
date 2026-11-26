package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * A course the chat made, through the person's own changes to it: the name given again
 * with the pencil never takes back the course it made; an answer after the card never brings back a
 * course undone; given again after Undo, or saved again, the course comes back with today's marks,
 * never without them; a course begun on a day gone by is asked about today's doses as one begun today.
 */
class CourseFilingTest {
    private val gatesOn =
        ReminderGateState(remindersOn = true, notificationsOn = true, exactAlarms = true, fullScreen = true)
    private val store = MemoryChatStore()
    private val filing = ChatFiling(store, "k")

    @After
    fun clock() {
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }

    /** Sertraline at 09:00 from today, written down at noon: today's dose is asked about, and [today] answers it. */
    private fun made(today: SymptomOption = SymptomOption.TOOK_TODAY): MedicineChat {
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).start(Symptom.COURSE)
        chat.type("Sertraline", SymptomStep.COURSE_NAME).type("50 mg").tap(SymptomOption.ONCE_A_DAY).value("540")
            .value(CHAT_TODAY.toString()).value("M6").tap(today, SymptomStep.COURSE_TODAY)
        assertEquals(SymptomStep.COURSE_FOOD, chat.step)
        chat.filed()
        return chat
    }

    /** Filed as the page files it, the courses written then known to the chat, as the page reloads them. */
    private fun MedicineChat.filed(): MedicineChat = apply {
        file(filing, "k")
        env = env.copy(courses = store.db.courses.values.toList())
    }

    private fun MedicineChat.context() =
        FilingContext(FilingClock(now, CHAT_TODAY, CHAT_ZONE), CHAT_TODAY, false, "k", log, env, medicineStrings)

    private fun MedicineChat.undo() {
        val result = runBlocking { checkNotNull(filing.undo(log.threads.last().id, "", context())) }
        log = log.withLinks(result)
        env = env.copy(courses = store.db.courses.values.toList())
    }

    /** [step]'s answer taken back with the pencil, as the page rewinds a thread. */
    private fun MedicineChat.rewound(step: SymptomStep): MedicineChat = apply {
        val thread = log.threads.last()
        val at = thread.report.chat?.turns.orEmpty().indexOfFirst { it is ChatTurn.Asked && it.step == step }
        log = log.rewoundLog(thread.id, at).settle(setting)
    }

    private val courseLink: (MedicineChat) -> FiledLink = { chat ->
        chat.log.threads.last().filed.first { it.role.isEmpty() }
    }

    @Test
    fun theNameGivenAgainWithThePencilNeverTakesBackTheCourseItMade() {
        val chat = made()
        val course = store.db.courses.values.single()
        chat.rewound(SymptomStep.COURSE_NAME).type("Золофт", SymptomStep.COURSE_NAME)
        // Its own course is no course "already in your medicines".
        assertEquals(SymptomStep.COURSE_DOSE, chat.step)
        chat.filed()
        assertEquals(listOf(course.createdAt), store.db.courses.values.map { it.createdAt })
        assertEquals(1, store.db.journal.size)
    }

    @Test
    fun anAnswerAfterTheCardNeverBringsBackACourseUndone() {
        val chat = made()
        chat.undo()
        assertTrue(store.db.courses.isEmpty())
        assertTrue(store.db.journal.isEmpty())
        chat.tap(SymptomOption.FOOD_WITH, SymptomStep.COURSE_FOOD).filed()
        chat.value("GP", SymptomStep.COURSE_WHO).filed()
        assertTrue(store.db.courses.isEmpty())
        assertTrue(store.db.journal.isEmpty())
        assertEquals(LinkState.UNDONE, courseLink(chat).state)
        // Save again is the person's own way back: it brings back the dose Undo took with it too (below).
        assertTrue(canSaveAgain(ScriptPlans, chat.log.threads.last(), "", chat.context()))
    }

    @Test
    fun saveAgainBringsBackTheCourseWithTodaysMarksInOneAct() {
        val chat = made()
        val mark = store.db.journal.values.single()
        chat.undo()
        assertTrue(store.db.journal.isEmpty())
        val result = runBlocking { checkNotNull(filing.saveAgain(chat.log.threads.last().id, "", chat.context())) }
        chat.log = chat.log.withLinks(result)
        val course = store.db.courses.values.single()
        val back = store.db.journal.values.single()
        assertEquals("""{"medicationId":${course.id},"slot":"540"}""", back.measurementPayloadJson)
        assertEquals(mark.occurredAt, back.occurredAt)
        assertEquals(LinkState.FILED, courseLink(chat).state)
        val marks = chat.log.threads.last().filed.filter { it.role.startsWith(DOSE_ROLE_PREFIX) }
        assertEquals(listOf(LinkState.FILED), marks.map { it.state })
        // Its reminder sees today's dose taken, as the Medicine tab does.
        assertTrue(plannedDoses(listOf(course), store.db.journal.values.toList(), CHAT_TODAY).single().taken)
        // What is answered next keeps them both.
        chat.env = chat.env.copy(courses = store.db.courses.values.toList())
        chat.tap(SymptomOption.FOOD_WITH, SymptomStep.COURSE_FOOD).filed()
        assertEquals("WITH", store.db.courses.values.single().foodRelation)
        assertEquals(listOf(back.id), store.db.journal.values.map { it.id })
    }

    @Test
    fun aCourseUndoneWithNoMarkOfTodayIsSavedAgainWithWhatWasAnsweredSince() {
        val chat = made(SymptomOption.NOT_YET)
        chat.undo()
        chat.tap(SymptomOption.FOOD_WITH, SymptomStep.COURSE_FOOD).filed()
        assertTrue(store.db.courses.isEmpty())
        assertTrue(canSaveAgain(ScriptPlans, chat.log.threads.last(), "", chat.context()))
        val result = runBlocking { checkNotNull(filing.saveAgain(chat.log.threads.last().id, "", chat.context())) }
        chat.log = chat.log.withLinks(result)
        assertEquals("WITH", store.db.courses.values.single().foodRelation)
    }

    @Test
    fun givenAgainWithThePencilAfterUndoTheCourseComesBackWithTodaysMarks() {
        val chat = made()
        chat.undo()
        chat.rewound(SymptomStep.COURSE_TODAY).tap(SymptomOption.TOOK_TODAY, SymptomStep.COURSE_TODAY).filed()
        val course = store.db.courses.values.single()
        assertEquals(
            """{"medicationId":${course.id},"slot":"540"}""",
            store.db.journal.values.single().measurementPayloadJson,
        )
        assertEquals(LinkState.FILED, courseLink(chat).state)
        // Undone once more, it stays undone through what is answered next.
        chat.undo()
        chat.tap(SymptomOption.FOOD_ANY, SymptomStep.COURSE_FOOD).filed()
        assertTrue(store.db.courses.isEmpty())
        assertTrue(store.db.journal.isEmpty())
    }

    /**
     * The course's fingerprints are stored with the chat — its record's, its marks', and the plan frozen
     * with the thread — and compared in the next run of the app, where an option is another object with
     * another hash of its own: the numbers are what every run makes of these answers.
     */
    @Test
    fun aCoursesFingerprintsAreTheSameInEveryRunOfTheApp() {
        val asked = listOf(
            SymptomStep.SYMPTOM,
            SymptomStep.COURSE_NAME,
            SymptomStep.COURSE_DOSE,
            SymptomStep.COURSE_OFTEN,
            SymptomStep.COURSE_TIMES,
            SymptomStep.COURSE_START,
            SymptomStep.COURSE_LENGTH,
            SymptomStep.COURSE_TODAY,
            SymptomStep.COURSE_FOOD,
        )
        val report = SymptomReport(
            symptom = Symptom.COURSE,
            answers = mapOf(
                SymptomStep.COURSE_OFTEN to listOf(SymptomOption.ONCE_A_DAY),
                SymptomStep.COURSE_TODAY to listOf(SymptomOption.TOOK_TODAY),
                SymptomStep.COURSE_FOOD to listOf(SymptomOption.FOOD_WITH),
            ),
            chat = SymptomChat(asked.map { ChatTurn.Asked(it) }),
            values = mapOf(
                SymptomStep.COURSE_NAME to "Sertraline",
                SymptomStep.COURSE_DOSE to "50 mg",
                SymptomStep.COURSE_TIMES to "540",
                SymptomStep.COURSE_START to "2026-10-05",
                SymptomStep.COURSE_LENGTH to "M6",
            ),
        )
        val thread = ChatThread(0, report)
        assertEquals(-984_957_495, courseSig(thread, ""))
        assertEquals(-941_390_446, courseSig(thread, DOSE_ROLE_PREFIX + "540"))
        assertEquals(-119_502_766, planHash(report))
    }

    /**
     * The time changed with the pencil after the card, the plan given again to "taken today": the mark
     * of 09:00 is taken back before the one of 10:00 is written, so the Medicine tab never pairs the old
     * mark with the new slot and refuses the dose the card says is marked.
     */
    @Test
    fun aTimeChangedWithThePencilMovesTodaysMarkToTheNewTime() {
        val chat = made()
        chat.rewound(SymptomStep.COURSE_TIMES).value("600", SymptomStep.COURSE_TIMES).filed()
        // While the plan is being answered again, what stands is kept.
        assertEquals("540", store.db.courses.values.single().times)
        assertEquals(1, store.db.journal.size)
        chat.value(CHAT_TODAY.toString(), SymptomStep.COURSE_START).value("M6", SymptomStep.COURSE_LENGTH)
            .tap(SymptomOption.TOOK_TODAY, SymptomStep.COURSE_TODAY).filed()
        val course = store.db.courses.values.single()
        assertEquals("600", course.times)
        assertEquals(
            """{"medicationId":${course.id},"slot":"600"}""",
            store.db.journal.values.single().measurementPayloadJson,
        )
        assertTrue(plannedDoses(listOf(course), store.db.journal.values.toList(), CHAT_TODAY).single().taken)
        val marks = chat.log.threads.last().filed.filter { it.role.startsWith(DOSE_ROLE_PREFIX) }
        assertEquals(listOf(DOSE_ROLE_PREFIX + "600" to LinkState.FILED), marks.map { it.role to it.state })
    }

    /**
     * The same across threads: the 15:00 dose marked in a later thread, which the pencil on the course's
     * times takes away with it, goes before the course's new mark is written — the tab would pair it
     * with the new 10:00 otherwise, and the dose said marked would be refused.
     */
    @Test
    fun aMarkOfAThreadThePencilTookAwayGoesBeforeTheNewMarkIsWritten() {
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).apply { now = todayAt(16 * 60) }.start(Symptom.COURSE)
        chat.type("Vitamin D", SymptomStep.COURSE_NAME).type("1 tablet").tap(SymptomOption.TWICE_A_DAY)
            .value("540,900").value(CHAT_TODAY.toString()).value("M6").value("540", SymptomStep.COURSE_TODAY_SLOTS)
            .filed()
        val course = store.db.courses.values.single()
        chat.log = chat.log.updateLast { it.copy(chat = it.chat?.copy(ended = true)) }
        chat.now = todayAt(16 * 60 + 30)
        chat.start(Symptom.DOSE).value("C${course.createdAt}@900", SymptomStep.MED_WHICH)
            .value("SLOT", SymptomStep.MED_WHEN).filed()
        assertEquals(listOf("540", "900"), store.db.journal.values.mapNotNull { medSlotOf(it)?.second }.sorted())
        val first = chat.log.threads.first()
        val at = first.report.chat?.turns.orEmpty()
            .indexOfFirst { it is ChatTurn.Asked && it.step == SymptomStep.COURSE_TIMES }
        chat.log = chat.log.rewoundLog(first.id, at).settle(chat.setting)
        chat.value("600,1260", SymptomStep.COURSE_TIMES).value(CHAT_TODAY.toString()).value("M6")
            .tap(SymptomOption.TOOK_TODAY, SymptomStep.COURSE_TODAY).filed()
        val again = store.db.courses.values.single()
        assertEquals("600,1260", again.times)
        assertEquals(
            """{"medicationId":${again.id},"slot":"600"}""",
            store.db.journal.values.single().measurementPayloadJson,
        )
    }

    @Test
    fun aCourseBegunOnADayGoneByIsAskedAboutTodaysDosesGoneBy() {
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).start(Symptom.COURSE)
        chat.type("Sertraline").type("50 mg").tap(SymptomOption.ONCE_A_DAY).value("540")
            .value(CHAT_TODAY.minusDays(3).toString(), SymptomStep.COURSE_START).value("NONE")
        assertEquals(SymptomStep.COURSE_TODAY, chat.step)
        // It began days ago: "start from tomorrow instead" is no answer then.
        assertEquals(
            listOf(SymptomOption.TOOK_TODAY, SymptomOption.NOT_YET),
            (chat.reply as ChatReply.Choose).options,
        )
        chat.tap(SymptomOption.TOOK_TODAY).filed()
        assertEquals(CHAT_TODAY.minusDays(3).toString(), store.db.courses.values.single().startDay)
        assertEquals(1, store.db.journal.size)
    }

    @Test
    fun todaysDosesMarkedOrNotAreSaidAsSeveralWhenThereAreSeveral() {
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).start(Symptom.COURSE)
        chat.type("Vitamin D").type("1 tablet").tap(SymptomOption.TWICE_A_DAY).value("480,600")
            .value(CHAT_TODAY.toString()).value("NONE").value("480,600", SymptomStep.COURSE_TODAY_SLOTS)
        assertTrue(R.string.med_chat_course_today_marked_several in chat.turn.notes())
        val none = MedicineChat(ChatEnv(gates = gatesOn.copy(remindersOn = false))).start(Symptom.COURSE)
        none.type("Vitamin D").type("1 tablet").tap(SymptomOption.TWICE_A_DAY).value("480,600")
            .value(CHAT_TODAY.toString()).value("NONE").value("NONE", SymptomStep.COURSE_TODAY_SLOTS)
        assertTrue(R.string.med_chat_missed_today_several in none.turn.notes())
    }

    @Test
    fun aPsychiatristIsOfferedOnlyForAnAntidepressantForMood() {
        fun who(name: String, forWhat: SymptomOption?): List<String> {
            val chat = MedicineChat(ChatEnv(gates = gatesOn)).start(Symptom.COURSE).type(name)
            if (forWhat != null) chat.tap(forWhat, SymptomStep.COURSE_FOR)
            chat.type("25 mg").tap(SymptomOption.ONCE_A_DAY).value("1260").value(CHAT_TODAY.plusDays(1).toString())
                .value("NONE").tap(SymptomOption.FOOD_ANY)
            assertEquals(SymptomStep.COURSE_WHO, chat.step)
            return (chat.reply as ChatReply.TextAsk).suggestions.map { it.value }
        }
        assertTrue("PSYCHIATRIST" in who("sertraline", null))
        assertTrue("PSYCHIATRIST" in who("duloxetine", SymptomOption.FOR_MOOD))
        assertFalse("PSYCHIATRIST" in who("amitriptyline", SymptomOption.FOR_PAIN))
        assertFalse("PSYCHIATRIST" in who("St John's wort", null))
    }

    @Test
    fun stJohnsWortIsNeverSaidToBringWhatAPrescribedAntidepressantsPagesSay() {
        val wort = course("Hypericum", start = CHAT_TODAY.minusDays(4))
        val chat = MedicineChat(ChatEnv(courses = listOf(wort), gates = gatesOn)).start(Symptom.COURSE)
        chat.type("hypericum", SymptomStep.COURSE_NAME).tap(SymptomOption.COURSE_STOPPED, SymptomStep.COURSE_SAME)
        // Nobody is asked who stopped it, and stopping is not said to bring withdrawal.
        assertTrue(chat.step == null)
        assertFalse(R.string.med_chat_withdrawal_net in chat.turn.notes())
        val dose = MedicineChat(ChatEnv(courses = listOf(wort))).apply { now = todayAt(9 * 60 + 10) }
        dose.start(Symptom.DOSE).value("C${wort.createdAt}@540", SymptomStep.MED_WHICH)
        assertFalse(SymptomStep.MED_FEEL in dose.steps + listOfNotNull(dose.step))
    }

    @Test
    fun aCrisisAnswerInAThreadAboutATricyclicIsTheTricyclicsEmergency() {
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).start(Symptom.COURSE).type("amitriptyline")
        chat.write("I want to die")
        assertEquals(SymptomStep.CRISIS_NOW, chat.step)
        chat.tap(SymptomOption.SAFE_NOW)
        assertTrue(SymptomAlert.EMERGENCY_MIND in chat.turn.alerts())
    }

    /**
     * A course's card offers Open only where the chat's host opens a course: not in the record editor —
     * a chat opened from its row on the day list — which no tab stands over, so Open would do nothing
     * there. Every other record's card offers it; a symptom's never.
     */
    @Test
    fun aCoursesCardOffersOpenOnlyWhereItsHostOpensCourses() {
        assertTrue(cardOpens(LINK_COURSE, LinkState.FILED, opensCourses = true))
        assertFalse(cardOpens(LINK_COURSE, LinkState.FILED, opensCourses = false))
        listOf(KIND_PAIN, KIND_MED, KIND_MOOD).forEach { kind ->
            assertTrue(kind, cardOpens(kind, LinkState.FILED, opensCourses = false))
            assertTrue(kind, cardOpens(kind, LinkState.CHANGED, opensCourses = true))
            assertFalse(kind, cardOpens(kind, LinkState.GONE, opensCourses = true))
        }
        assertFalse(cardOpens(KIND_SYMPTOM, LinkState.FILED, opensCourses = true))
    }
}
