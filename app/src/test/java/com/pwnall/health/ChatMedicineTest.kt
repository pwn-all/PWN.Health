package com.pwnall.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * What the medicine threads say besides their questions: the notes at a dose's close — alcohol by
 * what the guidance of the medicine says, medicines that may not mix by the names the person has
 * for them, never what they contain — once a chat and once in thirty days for the same medicines,
 * and none after a line to get advice now; a crisis answer read with a tricyclic in the person's
 * courses; a painkiller noted in the chat.
 */
class ChatMedicineTest {
    private val sertraline = course("Sertraline", start = CHAT_TODAY.minusDays(100))

    @After
    fun clock() {
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }

    private val drink =
        JournalEntry(
            day = CHAT_TODAY.toString(),
            occurredAt = todayAt(8 * 60),
            kind = KIND_ALCOHOL,
            title = "Wine",
            calories = 0.0,
        )

    /** A dose of [sertraline] marked at 09:10, how it is going answered, with [env] around it. */
    private fun markedDose(env: ChatEnv): MedicineChat {
        val chat = MedicineChat(env).apply { now = todayAt(9 * 60 + 10) }
        chat.start(Symptom.DOSE).value("C${sertraline.createdAt}@540", SymptomStep.MED_WHICH)
        assertNull(chat.step)
        return chat
    }

    @Test
    fun aDoseOnADayOfDrinkSaysWhatItsGuidanceSaysAboutAlcohol() {
        val withDrink = markedDose(ChatEnv(courses = listOf(sertraline), dayJournal = listOf(drink)))
        assertTrue(R.string.med_chat_alcohol_avoid in withDrink.turn.notes())
        assertEquals("ALCOHOL_AVOID|sertraline|Sertraline", withDrink.report.meta[THREAD_NOTES])
        val dry = markedDose(ChatEnv(courses = listOf(sertraline)))
        assertTrue(dry.turn.notes().none { it == R.string.med_chat_alcohol_avoid })
    }

    @Test
    fun aNoteIsSaidOnceInThirtyDaysForTheSameMedicines() {
        val said =
            SymptomReport(symptom = Symptom.DOSE, meta = mapOf(THREAD_NOTES to "ALCOHOL_AVOID|sertraline|Sertraline"))
        val earlier = encodeChatLog(ChatLog(threads = listOf(ChatThread(0, said)), next = 1), pending = false)
        fun daysAgo(days: Long) = RecentChat(earlier, todayAt(9 * 60) - days * MED_DAY_MS)
        val drinks = ChatEnv(courses = listOf(sertraline), dayJournal = listOf(drink))
        val again = markedDose(drinks.copy(recentChats = listOf(daysAgo(10))))
        assertTrue(again.turn.notes().none { it == R.string.med_chat_alcohol_avoid })
        // The chat reads the chats of a medicine's first weeks; one begun forty days ago said it too long ago.
        val month = markedDose(drinks.copy(recentChats = listOf(daysAgo(40))))
        assertTrue(R.string.med_chat_alcohol_avoid in month.turn.notes())
        val start = MedNote(NoteKind.START_SAFETY, "sertraline", listOf("Sertraline"))
        val state = again.state.copy(
            thread = again.state.thread.copy(
                env = again.state.thread.env.copy(
                    recentChats = listOf(
                        RecentChat(
                            encodeChatLog(
                                ChatLog(listOf(ChatThread(0, said.copy(meta = notesFact(listOf(start))))), 1),
                                false,
                            ),
                            todayAt(9 * 60) - MED_DAY_MS,
                        ),
                    ),
                ),
            ),
        )
        // A new course's start line is said with every new course.
        assertEquals(listOf(start), freshNotes(listOf(start), state))
    }

    /** The chat's own stored copy, which may still hold this thread's notes from before a rewind, hides none. */
    @Test
    fun theChatsOwnStoredCopyHidesNoNote() {
        val said =
            SymptomReport(symptom = Symptom.DOSE, meta = mapOf(THREAD_NOTES to "ALCOHOL_AVOID|sertraline|Sertraline"))
        val stored = encodeChatLog(ChatLog(threads = listOf(ChatThread(0, said)), next = 1), pending = false)
        val drinks = ChatEnv(courses = listOf(sertraline), dayJournal = listOf(drink))
        val own = markedDose(drinks.copy(recentChats = listOf(RecentChat(stored, todayAt(9 * 60), current = true))))
        assertTrue(R.string.med_chat_alcohol_avoid in own.turn.notes())
        // Another chat's copy of the same still does.
        val other = markedDose(drinks.copy(recentChats = listOf(RecentChat(stored, todayAt(9 * 60)))))
        assertTrue(other.turn.notes().none { it == R.string.med_chat_alcohol_avoid })
    }

    @Test
    fun noNoteAfterALineToGetAdviceNow() {
        val chat = MedicineChat(ChatEnv(courses = listOf(sertraline), dayJournal = listOf(drink))).start(Symptom.DOSE)
        chat.value(
            "N",
            SymptomStep.MED_WHICH,
        ).type("нурофен").value("NOW").value(AMOUNT_TWO).tap(SymptomOption.MORE_THAN_IT_SAYS)
            .tap(SymptomOption.BY_ACCIDENT).tap(SymptomOption.FOR_PAIN)
        assertNull(chat.step)
        assertTrue(ChatLine.CLOSE_NOW in chat.turn.lines())
        assertTrue(
            chat.turn.pieces.filterIsInstance<BotPiece.Note>().none {
                it.sourceRes ==
                    R.string.med_chat_source_mix
            },
        )
        assertTrue(chat.turn.notes().none { it == R.string.med_chat_alcohol_limit })
    }

    @Test
    fun aPainkillerOnItsOwnBesideAnAntidepressantMayNotMix() {
        val chat = MedicineChat(ChatEnv(courses = listOf(sertraline))).start(Symptom.DOSE)
        chat.value(
            "N",
            SymptomStep.MED_WHICH,
        ).type("Нурофен").value("NOW").value(AMOUNT_ONE).tap(SymptomOption.FOR_PAIN)
        val mix = chat.turn.pieces.filterIsInstance<BotPiece.Note>().single { it.text.res == R.string.med_chat_mix }
        assertEquals(listOf(PieceArg.Text("Нурофен"), PieceArg.Text("Sertraline")), mix.text.args)
        assertEquals(R.string.med_chat_source_mix, mix.sourceRes)
    }

    @Test
    fun mixLinesNameTheMedicinesAsThePersonHasThemNeverWhatTheyContain() {
        fun names(a: String, b: String) = mixNote(namedMedicine(a), namedMedicine(b))?.let { it.kind to it.names }
        assertEquals(NoteKind.MIX_NSAIDS to listOf("Nurofen", "Voltaren"), names("nurofen", "voltaren"))
        assertEquals(NoteKind.MIX_PARACETAMOL to listOf("Panadol", "Цитрамон"), names("panadol", "цитрамон"))
        assertEquals(NoteKind.MIX_TCA_OPIOID to listOf("Amitriptyline", "Tramadol"), names("tramadol", "amitriptyline"))
        assertEquals(NoteKind.MIX_NOT_TOGETHER to listOf("Tramadol", "Manerix"), names("tramadol", "manerix"))
        assertEquals(NoteKind.MIX_SJW to listOf("Sertraline"), names("hypericum", "sertraline"))
        assertEquals(NoteKind.MIX to listOf("Imigran", "Cipramil"), names("imigran", "cipramil"))
        assertNull(names("panadol", "nurofen"))
        assertNull(names("nurofen", "brufen"))
        assertNull(names("vitamin d", "nurofen"))
    }

    @Test
    fun crisisWordsWithAnActiveAmitriptylineCourseAreAnEmergency() {
        val amitriptyline = course("Amitriptyline", times = "1260")
        assertEquals(mapOf("tca" to "1"), medicineCrisisFacts(ChatEnv(courses = listOf(amitriptyline)), CHAT_TODAY))
        assertEquals(emptyMap<String, String>(), medicineCrisisFacts(ChatEnv(courses = listOf(sertraline)), CHAT_TODAY))
        fun alerts(courses: List<Medication>): List<SymptomAlert> {
            val chat = MedicineChat(ChatEnv(courses = courses)).write("I want to die")
            assertEquals(SymptomStep.CRISIS_NOW, chat.step)
            assertTrue(SymptomOption.SAFE_NOW in (chat.reply as ChatReply.Choose).options)
            return chat.tap(SymptomOption.SAFE_NOW).turn.alerts()
        }
        assertTrue(SymptomAlert.EMERGENCY_MIND in alerts(listOf(amitriptyline)))
        assertFalse(SymptomAlert.EMERGENCY_MIND in alerts(listOf(sertraline)))
    }

    @Test
    fun aPainkillerNotedInTheChatMarksThePainsQuestion() {
        val filed = FiledLink(KIND_MED, at = CHAT_NOW, title = "Нурофен")
        val forPain =
            SymptomReport(
                symptom = Symptom.DOSE,
                answers = mapOf(SymptomStep.MED_WHY to listOf(SymptomOption.FOR_PAIN)),
                chat = SymptomChat(listOf(ChatTurn.Asked(SymptomStep.SYMPTOM), ChatTurn.Asked(SymptomStep.MED_WHY))),
            )
        val log = ChatLog(threads = listOf(ChatThread(0, forPain, filed = listOf(filed))), next = 1)
        assertTrue(painkillerFiledInChat(log))
        assertFalse(painkillerFiledInChat(log.copy(threads = listOf(ChatThread(0, forPain)))))
        val forSleep = forPain.copy(answers = mapOf(SymptomStep.MED_WHY to listOf(SymptomOption.FOR_SLEEP)))
        assertFalse(painkillerFiledInChat(log.copy(threads = listOf(ChatThread(0, forSleep, filed = listOf(filed))))))
        assertTrue(painkillerIn("приняла нурофен"))
        assertTrue(painkillerIn("took a painkiller"))
        assertFalse(painkillerIn("took my sertraline"))
    }

    /** A good mood told with "full of energy", on [courses]: the dial at its happiest, what lifts it answered. */
    private fun highMood(courses: List<Medication>): MoodChat {
        val chat = MoodChat()
        chat.env = chat.env.copy(courses = courses)
        chat.write("I feel amazing and so full of energy")
        assertEquals(SymptomStep.MOOD_DIAL, chat.asked())
        chat.dial(5)
        chat.tick(SymptomStep.MOOD_ABOUT, SymptomOption.REST)
        return chat
    }

    /**
     * The mood thread's rule over the medicine list: with a course the built-in list
     * reads as an antidepressant, words of energy without end bring the sleep and energy check — they do
     * not on their own — and a sign ticked is the line to get advice today, with its NHS page.
     */
    @Test
    fun aHighMoodOnAnAntidepressantGetsTheUrgentLine() {
        assertEquals(MedicineClass.ANTIDEPRESSANT, medicineClassOf(sertraline.name))
        assertFalse(highMood(emptyList()).asked() == SymptomStep.MOOD_HIGH_ASK)
        val chat = highMood(listOf(sertraline))
        assertEquals(SymptomStep.MOOD_HIGH_ASK, chat.asked())
        chat.tap(SymptomStep.MOOD_HIGH_ASK, SymptomOption.ASK_ME)
        chat.tick(SymptomStep.MOOD_HIGH_SIGNS, SymptomOption.RACING_MIND)
        val alert = chat.turn().pieces.filterIsInstance<BotPiece.Alert>().single()
        assertEquals(SymptomAlert.HIGH_ON_ANTIDEPRESSANT, alert.alert)
        assertEquals(SymptomUrgency.SAME_DAY, alert.alert.urgency)
        assertEquals(R.string.symptom_source_antidepressant_mania, alert.sourceRes)
        // A course that ended before the chat's day is no course the person takes now.
        val ended = course("Sertraline", start = CHAT_TODAY.minusDays(100), end = CHAT_TODAY.minusDays(30))
        assertFalse(highMood(listOf(ended)).asked() == SymptomStep.MOOD_HIGH_ASK)
    }

    /**
     * The line names the course as the person keeps it, never its class: the list reads
     * duloxetine as an antidepressant whatever it was prescribed for, and the words say "Cymbalta 30".
     */
    @Test
    fun theHighMoodLineNamesTheCourseNeverTheClass() {
        val cymbalta = course("Cymbalta 30", start = CHAT_TODAY.minusDays(20))
        assertEquals(MedicineClass.ANTIDEPRESSANT, medicineClassOf(cymbalta.name))
        val chat = highMood(listOf(cymbalta))
        chat.tap(SymptomStep.MOOD_HIGH_ASK, SymptomOption.ASK_ME)
        assertEquals("Cymbalta 30", chat.thread().report.meta["MOOD_HIGH_ASK.ad"])
        chat.tick(SymptomStep.MOOD_HIGH_SIGNS, SymptomOption.TALKING_MORE)
        val alert = chat.turn().pieces.filterIsInstance<BotPiece.Alert>().single()
        assertEquals(
            ChatText(R.string.chat_mood_alert_high_antidepressant, listOf(PieceArg.Text("Cymbalta 30"))),
            alert.text,
        )
        for (folder in listOf("values", "values-ru")) {
            val xml = File("src/main/res/$folder/symptom_chat_mood.xml").readText()
            val line = checkNotNull(
                Regex("""name="chat_mood_alert_high_antidepressant"[^>]*>(.*?)</string>""").find(xml),
            ).groupValues[1]
            assertFalse(line, "antidepress" in line.lowercase() || "антидепресс" in line.lowercase())
        }
    }
}
