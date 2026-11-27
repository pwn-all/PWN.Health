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
 * The COURSE thread: the course is made the moment its plan is in — before food and who
 * prescribed it, which rewrite it — with today's doses said taken marked; a medicine taken when
 * needed is a daily course or a dose noted; the start-safety line for an antidepressant, a
 * tricyclic's own whatever it was for; stopping on one's own is the line to talk to the doctor first.
 */
class CourseThreadFlowTest {
    private val gatesOn =
        ReminderGateState(remindersOn = true, notificationsOn = true, exactAlarms = true, fullScreen = true)

    @After
    fun clock() {
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }

    private fun MedicineChat.planned(name: String, often: SymptomOption = SymptomOption.ONCE_A_DAY): MedicineChat =
        type(name, SymptomStep.COURSE_NAME).type("50 mg", SymptomStep.COURSE_DOSE).tap(often, SymptomStep.COURSE_OFTEN)

    @Test
    fun theCourseIsMadeOnceItsPlanIsInAndFoodAndWhoRewriteIt() {
        val store = MemoryChatStore()
        val filing = ChatFiling(store, "k")
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).start(Symptom.COURSE).planned("Sertraline")
        assertEquals(listOf(9 * 60), (chat.reply as ChatReply.TimesPick).proposals)
        chat.value("540", SymptomStep.COURSE_TIMES)
            .value(CHAT_TODAY.toString(), SymptomStep.COURSE_START)
            .value("M6", SymptomStep.COURSE_LENGTH)
        // Today's 09:00 has gone by at noon: has it been taken? Nothing is made before that answer.
        assertEquals(SymptomStep.COURSE_TODAY, chat.step)
        chat.file(filing, "k")
        assertTrue(store.db.courses.isEmpty())
        chat.tap(SymptomOption.TOOK_TODAY, SymptomStep.COURSE_TODAY)
        assertEquals(SymptomStep.COURSE_FOOD, chat.step)
        chat.file(filing, "k")
        val made = store.db.courses.values.single()
        assertEquals(
            listOf("Sertraline", "50 mg", "540", "2026-10-05", "2027-04-04"),
            listOf(made.name, made.dose, made.times, made.startDay, made.endDay),
        )
        val mark = store.db.journal.values.single()
        assertEquals("""{"medicationId":${made.id},"slot":"540"}""", mark.measurementPayloadJson)
        val lead = chat.turn
        assertTrue(lead.pieces.any { it is BotPiece.Saved })
        assertTrue(R.string.med_chat_course_today_marked in lead.notes())
        assertTrue(R.string.med_chat_start_safety in lead.notes())
        // Left at "who prescribed it", the course stays, with its food.
        chat.tap(SymptomOption.FOOD_WITH, SymptomStep.COURSE_FOOD)
        chat.file(filing, "k")
        assertEquals("WITH", store.db.courses.values.single().foodRelation)
        chat.value("GP", SymptomStep.COURSE_WHO)
        assertNull(chat.step)
        chat.file(filing, "k")
        assertEquals("Prescribed by: A GP", store.db.courses.values.single().note)
        assertEquals(1, store.db.courses.size)
        assertEquals(
            listOf(
                SymptomStep.COURSE_NAME,
                SymptomStep.COURSE_DOSE,
                SymptomStep.COURSE_OFTEN,
                SymptomStep.COURSE_TIMES,
                SymptomStep.COURSE_START,
                SymptomStep.COURSE_LENGTH,
                SymptomStep.COURSE_TODAY,
                SymptomStep.COURSE_FOOD,
                SymptomStep.COURSE_WHO,
            ),
            chat.steps,
        )
    }

    @Test
    fun aTimesPickAnswerIsTakenAndTodaysDosesGoneByAreTicked() {
        val store = MemoryChatStore()
        val chat = MedicineChat(
            ChatEnv(gates = gatesOn),
        ).start(Symptom.COURSE).planned("Vitamin D", SymptomOption.TWICE_A_DAY)
        assertEquals(listOf(8 * 60, 20 * 60), (chat.reply as ChatReply.TimesPick).proposals)
        chat.value("480,600", SymptomStep.COURSE_TIMES)
            .value(CHAT_TODAY.toString(), SymptomStep.COURSE_START)
            .value("NONE", SymptomStep.COURSE_LENGTH)
        assertEquals(SymptomStep.COURSE_TODAY_SLOTS, chat.step)
        val ticks = chat.reply as ChatReply.Values
        assertTrue(ticks.multi)
        assertEquals(listOf("480", "600", "NONE", "TOMORROW"), ticks.choices.map { it.value })
        chat.value("480", SymptomStep.COURSE_TODAY_SLOTS)
        assertEquals(SymptomStep.COURSE_FOOD, chat.step)
        // The 10:00 dose not taken is still inside its four hours: its reminder comes.
        assertTrue(R.string.med_chat_reminder_soon in chat.turn.notes())
        chat.file(ChatFiling(store, "k"), "k")
        assertEquals(
            listOf("""{"medicationId":1,"slot":"480"}"""),
            store.db.journal.values.map {
                it.measurementPayloadJson
            },
        )
        assertNull(store.db.courses.values.single().endDay)
    }

    @Test
    fun startingTomorrowInsteadMarksNothingAndMovesTheDays() {
        val store = MemoryChatStore()
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).start(Symptom.COURSE).planned("Vitamin D")
        chat.value(
            "540",
        ).value(CHAT_TODAY.toString()).value("D7").tap(SymptomOption.START_TOMORROW, SymptomStep.COURSE_TODAY)
        chat.file(ChatFiling(store, "k"), "k")
        val made = store.db.courses.values.single()
        assertEquals("2026-10-06" to "2026-10-12", made.startDay to made.endDay)
        assertTrue(store.db.journal.isEmpty())
    }

    /**
     * "From tomorrow instead" is tomorrow as it was when it was said. Food is asked after the card and
     * rewrites the same course: answered two minutes past midnight in a chat left open, it would count
     * tomorrow anew, and the course would lose its first day and end a day late.
     */
    @Test
    fun startingTomorrowKeepsItsDaysWhenFoodIsAnsweredPastMidnight() {
        val store = MemoryChatStore()
        val filing = ChatFiling(store, "k")
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).apply { now = todayAt(23 * 60 + 57) }
        chat.start(Symptom.COURSE).planned("Vitamin D").value("540").value(CHAT_TODAY.toString()).value("D7")
            .tap(SymptomOption.START_TOMORROW, SymptomStep.COURSE_TODAY)
        chat.file(filing, "k")
        val tomorrow = CHAT_TODAY.plusDays(1)
        chat.now = todayAt(2, tomorrow)
        chat.tap(SymptomOption.FOOD_WITH, SymptomStep.COURSE_FOOD)
        chat.file(filing, "k", today = tomorrow)
        val made = store.db.courses.values.single()
        assertEquals(listOf("WITH", "2026-10-06", "2026-10-12"), listOf(made.foodRelation, made.startDay, made.endDay))
    }

    /**
     * A chat begun before midnight and gone on with after it keeps the day it was begun on. The start
     * chosen then reads as its chip said it, "Today" of the new date — never "Tomorrow" of the chat's
     * day — and still does the day after.
     */
    @Test
    fun aStartChosenPastMidnightReadsAsItsChipSaidIt() {
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).apply { now = todayAt(23 * 60 + 50) }
        chat.start(Symptom.COURSE).planned("Vitamin D").value("540", SymptomStep.COURSE_TIMES)
        val tomorrow = CHAT_TODAY.plusDays(1)
        chat.now = todayAt(5, tomorrow)
        val today = (chat.reply as ChatReply.Values).choices.first()
        assertEquals(tomorrow.toString(), today.value)
        chat.value(today.value, SymptomStep.COURSE_START)
        assertEquals(R.string.med_chat_start_today, CourseScript.answerText(chat.state, SymptomStep.COURSE_START)?.res)
        chat.now = todayAt(9 * 60, tomorrow.plusDays(1))
        assertEquals(R.string.med_chat_start_today, CourseScript.answerText(chat.state, SymptomStep.COURSE_START)?.res)
    }

    /**
     * The words' own times are read as a dose's are: «в 9 вечера» is 21:00, a bare «в 9» either half of
     * the day, «в 20:30» itself alone.
     */
    @Test
    fun theTimesProposedAreTheWordsReadAsADosesTimeIs() {
        fun proposals(text: String): List<Int> {
            val chat = MedicineChat(ChatEnv(gates = gatesOn)).write(text)
            chat.value("Сертралин", SymptomStep.COURSE_NAME).type("50 mg", SymptomStep.COURSE_DOSE)
                .tap(SymptomOption.ONCE_A_DAY, SymptomStep.COURSE_OFTEN)
            return (chat.reply as ChatReply.TimesPick).proposals
        }
        assertEquals(listOf(21 * 60), proposals("врач назначил сертралин, принимать в 9 вечера"))
        assertEquals(listOf(9 * 60, 21 * 60), proposals("врач назначил сертралин, принимать в 9"))
        assertEquals(listOf(20 * 60 + 30), proposals("врач назначил сертралин, принимать в 20:30"))
    }

    @Test
    fun aMedicineTakenWhenNeededIsADailyCourseOrADoseNoted() {
        val needed = MedicineChat(
            ChatEnv(gates = gatesOn),
        ).start(Symptom.COURSE).planned("Nurofen", SymptomOption.WHEN_NEEDED)
        assertEquals(SymptomStep.COURSE_PRN, needed.step)
        assertEquals(
            listOf(SymptomOption.AS_DAILY_COURSE, SymptomOption.DOSE_NOW),
            (needed.reply as ChatReply.Choose).options,
        )
        assertEquals(R.string.med_chat_help_prn, CourseScript.help(needed.state, SymptomStep.COURSE_PRN)?.res)
        needed.tap(SymptomOption.DOSE_NOW)
        assertNull(needed.step)
        assertEquals(listOf(Symptom.DOSE), CourseScript.followUps(needed.state).map { it.topic })
        val store = MemoryChatStore()
        needed.file(ChatFiling(store, "k"), "k")
        assertTrue(store.db.courses.isEmpty())

        val weekly = MedicineChat(
            ChatEnv(gates = gatesOn),
        ).start(Symptom.COURSE).planned("Vitamin D", SymptomOption.OTHER_OFTEN)
        weekly.tap(SymptomOption.LESS_THAN_DAILY, SymptomStep.COURSE_OFTEN_OTHER)
        assertEquals(R.string.med_chat_help_less_daily, CourseScript.help(weekly.state, SymptomStep.COURSE_PRN)?.res)
        weekly.tap(SymptomOption.AS_DAILY_COURSE, SymptomStep.COURSE_PRN)
        assertEquals(listOf(9 * 60), (weekly.reply as ChatReply.TimesPick).proposals)
    }

    private fun safetyNotes(name: String, forWhat: SymptomOption?): List<Int> {
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).start(Symptom.COURSE).type(name)
        if (forWhat != null) chat.tap(forWhat, SymptomStep.COURSE_FOR)
        chat.type("25 mg").tap(SymptomOption.ONCE_A_DAY).value("1260")
            .value(CHAT_TODAY.plusDays(1).toString(), SymptomStep.COURSE_START).value("NONE")
        assertEquals(SymptomStep.COURSE_FOOD, chat.step)
        return chat.turn.notes()
    }

    @Test
    fun duloxetineAsksWhatItIsForAndStartsWithItsSafetyLineOnlyForMood() {
        assertTrue(R.string.med_chat_start_safety in safetyNotes("duloxetine", SymptomOption.FOR_MOOD))
        assertFalse(R.string.med_chat_start_safety in safetyNotes("duloxetine", SymptomOption.FOR_PAIN))
        // A medicine of one use is not asked what it is for.
        assertTrue(R.string.med_chat_start_safety in safetyNotes("sertraline", null))
        assertTrue(safetyNotes("Vitamin D", null).none { it == R.string.med_chat_start_safety })
    }

    @Test
    fun amitriptylineForPainShowsTheTcaStartLine() {
        val notes = safetyNotes("amitriptyline", SymptomOption.FOR_PAIN)
        assertTrue(R.string.med_chat_start_safety_tca in notes)
        assertFalse(R.string.med_chat_start_safety in notes)
    }

    @Test
    fun stoppingOnOnesOwnIsTheLineToTalkFirstAndWhatStoppingCanBring() {
        val sertraline = course("Sertraline")
        val chat = MedicineChat(ChatEnv(courses = listOf(sertraline), gates = gatesOn)).start(Symptom.COURSE)
        chat.type("сертралин", SymptomStep.COURSE_NAME)
        assertEquals(SymptomStep.COURSE_SAME, chat.step)
        val ask = CourseScript.questionText(chat.state, SymptomStep.COURSE_SAME)
        assertEquals(R.string.med_chat_ask_course_same, ask?.res)
        assertEquals(sertraline.createdAt, (chat.reply as ChatReply.Choose).openRef?.at)
        chat.tap(SymptomOption.COURSE_STOPPED).tap(SymptomOption.OWN_DECISION, SymptomStep.COURSE_STOP_WHO)
        assertNull(chat.step)
        assertEquals(listOf(SymptomAlert.STOP_TALK), chat.turn.alerts())
        assertTrue(R.string.med_chat_end_on_page in chat.turn.notes())
        assertTrue(R.string.med_chat_withdrawal_net in chat.turn.notes())
        val store = MemoryChatStore()
        chat.file(ChatFiling(store, "k"), "k")
        assertTrue(store.db.courses.isEmpty())
    }

    /**
     * «Бросила пить сертралин» with no course of it: nothing is added — no "let us add it", no plan —
     * who said to stop it is asked, and the close says what stopping on one's own and stopping at all
     * can bring; a medicine whose page says nothing of stopping is noted as nothing new, never as a new
     * course with reminders.
     */
    @Test
    fun aMedicineStoppedWithNoCourseOfItAddsNothingAndSaysWhatStoppingCanBring() {
        val chat = MedicineChat(ChatEnv(gates = gatesOn))
        chat.write("бросила пить сертралин")
        assertEquals(Symptom.COURSE, chat.log.threads.single().topic)
        assertEquals(SymptomStep.COURSE_NAME, chat.step)
        assertFalse(R.string.med_chat_start_course in chat.turn.notes())
        assertTrue(R.string.med_chat_now_the_medicine in chat.turn.notes())
        chat.value("сертралин", SymptomStep.COURSE_NAME)
        assertEquals(SymptomStep.COURSE_STOP_WHO, chat.step)
        chat.tap(SymptomOption.OWN_DECISION, SymptomStep.COURSE_STOP_WHO)
        assertNull(chat.step)
        assertEquals(listOf(SymptomStep.COURSE_NAME, SymptomStep.COURSE_STOP_WHO), chat.steps)
        assertEquals(listOf(SymptomAlert.STOP_TALK), chat.turn.alerts())
        assertTrue(R.string.med_chat_withdrawal_net in chat.turn.notes())
        assertFalse(R.string.med_chat_end_on_page in chat.turn.notes())
        val store = MemoryChatStore()
        chat.file(ChatFiling(store, "k"), "k")
        assertTrue(store.db.courses.isEmpty())
        assertTrue(store.db.journal.isEmpty())

        val other = MedicineChat(ChatEnv(gates = gatesOn))
        other.write("I stopped taking ibuprofen")
        other.value("ibuprofen", SymptomStep.COURSE_NAME)
        assertNull(other.step)
        assertTrue(R.string.med_chat_nothing_new in other.turn.notes())
    }

    /**
     * A medicine stopped, with a course of it that ended long ago, adds nothing "again"; a course made
     * in the thread for another name, the name then answered again as the one stopped, is taken back;
     * in a chat for an earlier day, where no course is asked about, a stop says the stop lines and a
     * prescription the line about today's chat.
     */
    @Test
    fun aMedicineStoppedNeverAddsOrKeepsACourseAndAnEarlierDaySaysTheSameLines() {
        val ended = course("Sertraline", start = CHAT_TODAY.minusDays(100), end = CHAT_TODAY.minusDays(30))
        val before = MedicineChat(ChatEnv(courses = listOf(ended), gates = gatesOn))
        before.write("бросила пить сертралин")
        before.value("сертралин", SymptomStep.COURSE_NAME)
        assertEquals(SymptomStep.COURSE_STOP_WHO, before.step)
        assertFalse(R.string.med_chat_ended_before in before.turn.notes())

        val rewound = MedicineChat(ChatEnv(gates = gatesOn))
        rewound.write("бросила пить сертралин")
        rewound.type("Vitamin D", SymptomStep.COURSE_NAME).type("1 tablet").tap(SymptomOption.ONCE_A_DAY)
            .value("1260").value(CHAT_TODAY.toString()).value("NONE")
        assertEquals(SymptomStep.COURSE_FOOD, rewound.step)
        val made = MemoryChatStore()
        rewound.file(ChatFiling(made, "k"), "k")
        assertEquals(1, made.db.courses.size)
        val thread = rewound.log.threads.single()
        val at = thread.report.chat?.turns.orEmpty()
            .indexOfFirst { it is ChatTurn.Asked && it.step == SymptomStep.COURSE_NAME }
        rewound.log = rewound.log.rewoundLog(thread.id, at).settle(rewound.setting)
        rewound.value("сертралин", SymptomStep.COURSE_NAME).tap(SymptomOption.DOCTOR_SAID, SymptomStep.COURSE_STOP_WHO)
        assertNull(rewound.step)
        rewound.file(ChatFiling(made, "k"), "k")
        assertTrue(made.db.courses.isEmpty())

        val past = MedicineChat(ChatEnv(gates = gatesOn), past = true)
        past.write("бросила пить сертралин")
        val said = past.turn
        assertEquals(listOf(R.string.med_chat_alert_stop, R.string.med_chat_withdrawal_net), said.notes())
        assertTrue(said.pieces.filterIsInstance<BotPiece.Note>().all { it.sourceRes == R.string.med_chat_source_stop })
        assertTrue(ChatLine.SAFETY in said.lines())
        val prescribed = MedicineChat(ChatEnv(gates = gatesOn), past = true)
        prescribed.write("врач назначил сертралин")
        assertEquals(listOf(R.string.med_chat_course_today_only), prescribed.turn.notes())
    }

    /**
     * A word of stopping stops the medicine it is told with and no other:
     * «бросила курить, пью сертралин» and "I quit drinking, I'm on sertraline now" add sertraline as any
     * course is added — its plan asked, its start-safety line said, the course made — «врач отменил
     * флуоксетин, теперь сертралин» stops fluoxetine alone, "my doctor stopped sertraline and prescribed
     * mirtazapine" sertraline alone; a chat for an earlier day says the stop lines for the one stopped.
     */
    @Test
    fun aWordOfStoppingStopsOnlyTheMedicineItIsToldWith() {
        fun chosen(text: String, name: String) =
            MedicineChat(ChatEnv(gates = gatesOn)).write(text).start(Symptom.COURSE, TopicMention(Symptom.COURSE, name))
        listOf(
            "бросила курить, пью сертралин" to "сертралин",
            "I quit drinking, I'm on sertraline now" to "sertraline",
            "врач отменил флуоксетин, теперь сертралин" to "сертралин",
            "My doctor stopped sertraline and prescribed mirtazapine" to "mirtazapine",
        ).forEach { (text, name) ->
            val chat = chosen(text, name)
            assertTrue(text, R.string.med_chat_start_course in chat.turn.notes())
            chat.value(name, SymptomStep.COURSE_NAME)
            assertEquals(text, SymptomStep.COURSE_DOSE, chat.step)
        }
        listOf(
            "врач отменил флуоксетин, теперь сертралин" to "флуоксетин",
            "My doctor stopped sertraline and prescribed mirtazapine" to "sertraline",
        ).forEach { (text, name) ->
            val chat = chosen(text, name)
            assertTrue(text, R.string.med_chat_now_the_medicine in chat.turn.notes())
            chat.value(name, SymptomStep.COURSE_NAME)
            assertEquals(text, SymptomStep.COURSE_STOP_WHO, chat.step)
        }

        // Sertraline taken is made a course, with the line its first weeks need and its reminders.
        val taken = chosen("бросила курить, пью сертралин", "сертралин")
        taken.value("сертралин", SymptomStep.COURSE_NAME).type("50 mg", SymptomStep.COURSE_DOSE)
            .tap(SymptomOption.ONCE_A_DAY, SymptomStep.COURSE_OFTEN).value("540", SymptomStep.COURSE_TIMES)
            .value(CHAT_TODAY.toString(), SymptomStep.COURSE_START).value("M6", SymptomStep.COURSE_LENGTH)
            .tap(SymptomOption.TOOK_TODAY, SymptomStep.COURSE_TODAY)
        assertTrue(R.string.med_chat_start_safety in taken.turn.notes())
        val store = MemoryChatStore()
        taken.file(ChatFiling(store, "k"), "k")
        assertEquals("сертралин", store.db.courses.values.single().name.lowercase())

        // A chat for an earlier day: the stop lines for fluoxetine, a prescription's line for sertraline,
        // and nothing stopped where the medicine is only named beside a habit given up.
        val past = MedicineChat(ChatEnv(gates = gatesOn), past = true)
            .write("врач отменил флуоксетин, теперь сертралин")
        assertEquals(
            listOf(R.string.med_chat_alert_stop, R.string.med_chat_withdrawal_net, R.string.med_chat_course_today_only),
            past.turn.notes(),
        )
        val quit = MedicineChat(ChatEnv(gates = gatesOn), past = true).write("I quit drinking, sertraline helps")
        assertEquals(listOf(R.string.med_chat_course_today_only), quit.turn.notes())
    }

    @Test
    fun aNameNotKnownYetEndsTheThreadWithNothingMade() {
        val chat = MedicineChat(
            ChatEnv(gates = gatesOn),
        ).start(Symptom.COURSE).type(MED_UNKNOWN, SymptomStep.COURSE_NAME)
        assertNull(chat.step)
        assertTrue(R.string.med_chat_course_needs_name in chat.turn.notes())
    }

    @Test
    fun theRemindersSwitchesAreAskedOnlyWhenOffAndTheRestOnlyOnceRemindersAreOn() {
        fun after(gates: ReminderGateState): MedicineChat = MedicineChat(ChatEnv(gates = gates)).start(Symptom.COURSE)
            .planned("Vitamin D").value("1260").value(CHAT_TODAY.toString()).value("NONE")
            .tap(SymptomOption.FOOD_ANY).value(MED_UNKNOWN, SymptomStep.COURSE_WHO)
        assertNull(after(gatesOn).step)
        val off = gatesOn.copy(remindersOn = false, notificationsOn = false)
        val left = after(off)
        assertEquals(SymptomStep.COURSE_REMIND_SWITCH, left.step)
        left.tap(SymptomOption.LEAVE_OFF)
        assertNull(left.step)
        assertTrue(R.string.med_chat_remind_left_off in left.turn.notes())
        val on = after(off).tap(SymptomOption.TURN_ON)
        assertEquals(SymptomStep.COURSE_REMIND_NOTIFY, on.step)
        assertEquals(ChatReply.ReminderGates(SymptomStep.COURSE_REMIND_NOTIFY, ReminderGate.NOTIFY), on.reply)
    }

    @Test
    fun aLengthEndsOnItsLastDay() {
        assertEquals(LocalDate.of(2027, 4, 4), endOf("M6", LocalDate.of(2026, 10, 5)))
        assertEquals(LocalDate.of(2026, 10, 11), endOf("D7", LocalDate.of(2026, 10, 5)))
        assertEquals(LocalDate.of(2028, 2, 28), endOf("M6", LocalDate.of(2027, 8, 31)))
        assertNull(endOf("NONE", CHAT_TODAY))
        assertEquals(LocalDate.of(2026, 12, 1), endOf("2026-12-01", CHAT_TODAY))
    }

    @Test
    fun aCourseMadeInTheChatIsTheRowTheFormMakes() {
        val expected = Medication(
            name = "Sertraline",
            dose = "50 mg",
            times = "540,1260",
            foodRelation = "WITH",
            startDay = "2026-10-06",
            endDay = "2027-04-05",
            note = "Prescribed by: A GP",
            createdAt = 0,
        )
        // The form, filled in as a person types it: spaces around, a time twice, the times out of order.
        val form = CourseFormState(CHAT_TODAY)
        form.name = " Sertraline "
        form.dose = "50 mg "
        form.times = listOf(1260, 540, 540)
        form.food = MedFood.WITH
        form.start = CHAT_TODAY.plusDays(1)
        form.end = LocalDate.of(2027, 4, 5)
        form.note = " Prescribed by: A GP"
        assertEquals(expected, form.toMedication().copy(createdAt = 0))
        // The chat, told the same: the same row.
        val store = MemoryChatStore()
        val chat = MedicineChat(ChatEnv(gates = gatesOn)).start(Symptom.COURSE)
        chat.type(" Sertraline ").type("50 mg ").tap(SymptomOption.TWICE_A_DAY).value("1260,540")
            .value(CHAT_TODAY.plusDays(1).toString()).value("M6").tap(SymptomOption.FOOD_WITH).value("GP")
        assertNull(chat.step)
        chat.file(ChatFiling(store, "k"), "k")
        assertEquals(expected, store.db.courses.values.single().copy(id = 0, createdAt = 0))
    }
}
