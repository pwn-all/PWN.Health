package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hidden tag of each thread of a chat, by its topic's record: where it was felt, never what it
 * is; worked out at every write and kept in the chat and in a symptom row, never in another record.
 */
class ChatTagTest {
    private fun painThread(vararg answers: Pair<SymptomStep, List<SymptomOption>>): SymptomReport {
        val turns = listOf(ChatTurn.Asked(SymptomStep.SYMPTOM), ChatTurn.Asked(SymptomStep.PAIN_AREA)) +
            answers.map { ChatTurn.Asked(it.first) }
        return SymptomReport(
            symptom = Symptom.PAIN,
            answers = answers.toMap(),
            values = mapOf(SymptomStep.PAIN_AREA to "KNEE:R"),
            chat = SymptomChat(turns),
        )
    }

    /** A pain: the chest first, then the head or jaw, then the belly, else the frame; the rest are its "also". */
    @Test
    fun aPainIsSortedByItsBodyFamilies() {
        val knee = painThread()
        fun area(vararg families: String) = classifyThread(knee, null, TagFacts(families = families.toSet()))

        assertEquals(ProblemArea.MUSCULOSKELETAL, area().area)
        assertEquals(ProblemArea.MUSCULOSKELETAL, area("KNEE", "LEG").area)
        assertEquals(
            ProblemArea.CHEST_HEARTBEAT to listOf(ProblemArea.HEAD, ProblemArea.MUSCULOSKELETAL),
            area("KNEE", "JAW", "CHEST").let {
                it.area to
                    it.also
            },
        )
        assertEquals(ProblemArea.HEAD, area("HEAD", "NECK").area)
        assertEquals(ProblemArea.HEAD, area("JAW").area)
        assertEquals(
            ProblemArea.STOMACH_GUT to listOf(ProblemArea.MUSCULOSKELETAL),
            area("BELLY", "BACK").let {
                it.area to
                    it.also
            },
        )
        val tag = area("KNEE")
        assertEquals(SymptomUrgency.SELF_CARE to ProblemImpact.UNKNOWN, tag.urgency to tag.impact)
        assertFalse(tag.mind)
    }

    /** The red flags ticked add their areas, distinct and in the areas' order. */
    @Test
    fun aPainsTickedSignsAddTheirAreas() {
        val signs = painThread(
            SymptomStep.PAIN_SIGNS to listOf(SymptomOption.FEVERISH, SymptomOption.SEIZURE, SymptomOption.SWELL_BRUISE),
            SymptomStep.PAIN_SEPSIS to listOf(SymptomOption.NON_BLANCHING_RASH),
            SymptomStep.PAIN_SIGNS_MORE to listOf(SymptomOption.DIZZY_FAINT, SymptomOption.HIGH_TEMP),
        )
        val tag = classifyThread(signs, null, TagFacts(families = setOf("KNEE")))
        assertEquals(ProblemArea.MUSCULOSKELETAL, tag.area)
        assertEquals(
            listOf(ProblemArea.BALANCE_FAINTING, ProblemArea.TEMPERATURE, ProblemArea.HEAD, ProblemArea.SKIN),
            tag.also,
        )
        // A sign of the pain's own area is not an "also".
        val chest =
            classifyThread(
                painThread(SymptomStep.PAIN_SIGNS to listOf(SymptomOption.CHEST_TOO)),
                null,
                TagFacts(families = setOf("CHEST")),
            )
        assertTrue(chest.also.isEmpty())
    }

    /** Passing out, the back's and the leg's 999 chip, is sorted beside feeling faint: one area, not a new one. */
    @Test
    fun passedOutSortsAsDizzyFaint() {
        fun also(option: SymptomOption) = classifyThread(
            painThread(SymptomStep.PAIN_SIGNS_MORE to listOf(option)),
            null,
            TagFacts(families = setOf("BACK")),
        ).also
        assertEquals(listOf(ProblemArea.BALANCE_FAINTING), also(SymptomOption.PASSED_OUT))
        assertEquals(also(SymptomOption.DIZZY_FAINT), also(SymptomOption.PASSED_OUT))
    }

    /** Its course: the onset told, or for the same pain the first report's age; recurring when it is the same pain. */
    @Test
    fun aPainsCourseAndRecurrence() {
        val week = painThread(SymptomStep.ONSET to listOf(SymptomOption.STARTED_A_WEEK))
        assertEquals(ProblemCourse.RECENT, classifyThread(week, null, TagFacts()).course)
        assertFalse(classifyThread(week, null, TagFacts()).recurring)

        val same = painThread(SymptomStep.PAIN_SAME to listOf(SymptomOption.PAIN_SAME_YES))
        val tag = classifyThread(same, null, TagFacts(course = SymptomOption.STARTED_A_MONTH))
        assertEquals(ProblemCourse.LASTING, tag.course)
        assertTrue(tag.recurring)
        assertEquals(ProblemCourse.UNKNOWN, classifyThread(painThread(), null, TagFacts()).course)
    }

    /** A pain's flags are the pain rules that fired; a crisis or an emergency word's answer is none of them. */
    @Test
    fun aPainsFlagsAreItsOwnRulesOnly() {
        val worded = painThread(
            SymptomStep.CHEST_NOW to listOf(SymptomOption.YES),
            SymptomStep.CRISIS_NOW to listOf(SymptomOption.SAFE_NOW),
        )
        val tag = classifyThread(worded, null, TagFacts(families = setOf("KNEE")))
        assertTrue(firedRules(worded.asTold()).map { it.id }.containsAll(listOf("CHEST_NOW", "CRISIS_SAFE")))
        assertEquals(PAIN_RULES.map { it.id }.filter { it in tag.flags }, tag.flags)
        assertFalse("CHEST_NOW" in tag.flags)
        assertFalse("CRISIS_SAFE" in tag.flags)
        assertEquals(SymptomUrgency.EMERGENCY, tag.urgency)
    }

    /** A mood by its face: low is a feeling, the middle neutral, the bright side positive; no face, unsorted. */
    @Test
    fun aMoodIsSortedByItsFace() {
        val mood =
            SymptomReport(symptom = Symptom.MOOD, chat = SymptomChat(listOf(ChatTurn.Asked(SymptomStep.SYMPTOM))))
        fun tag(score: Int?) = classifyThread(mood, null, TagFacts(score = score))

        assertEquals(ProblemArea.FEELINGS to true, tag(1).let { it.area to it.mind })
        assertEquals(ProblemArea.FEELINGS to true, tag(2).let { it.area to it.mind })
        assertEquals(ProblemArea.MOOD_NEUTRAL to false, tag(3).let { it.area to it.mind })
        assertEquals(ProblemArea.MOOD_POSITIVE to false, tag(4).let { it.area to it.mind })
        assertEquals(ProblemArea.MOOD_POSITIVE, tag(5).area)
        assertEquals(ProblemArea.UNSORTED, tag(null).area)
        assertEquals(
            ProblemCourse.NEW,
            classifyThread(mood, null, TagFacts(score = 2, course = SymptomOption.TODAY)).course,
        )
        assertFalse(tag(5).recurring)
    }

    /** A dose or a course is about medicines: a course new, a dose's course not told; what it was for, "also". */
    @Test
    fun aDoseOrACourseIsAboutMedicines() {
        fun tag(topic: Symptom, facts: TagFacts = TagFacts()) = classifyThread(
            SymptomReport(symptom = topic, chat = SymptomChat(listOf(ChatTurn.Asked(SymptomStep.SYMPTOM)))),
            null,
            facts,
        )
        val dose = tag(Symptom.DOSE, TagFacts(alsoTopics = setOf(Symptom.PAIN, Symptom.FEVER)))
        assertEquals(ProblemArea.MEDICINES, dose.area)
        assertEquals(listOf(ProblemArea.TEMPERATURE, ProblemArea.MUSCULOSKELETAL), dose.also)
        assertEquals(ProblemCourse.UNKNOWN, dose.course)
        assertEquals(ProblemCourse.NEW, tag(Symptom.COURSE).course)
        assertEquals(ProblemArea.MEDICINES, tag(Symptom.COURSE).area)
        assertTrue(tag(Symptom.COURSE, TagFacts(alsoTopics = setOf(Symptom.DOSE))).also.isEmpty())
    }

    /** A symptom thread, and one with no topic yet, are sorted exactly as [classifySymptomChat] sorts them. */
    @Test
    fun aSymptomOrATopicLessThreadIsSortedAsBefore() {
        val fever = newChat().pick(Symptom.FEVER).say(SymptomStep.WITH, SymptomOption.COUGH)
        assertEquals(classifySymptomChat(fever, null), classifyThread(fever, null, TagFacts(families = setOf("CHEST"))))
        val words = newChat().write("I took an overdose").say(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW)
        val tag = classifyThread(words, null, TagFacts())
        assertEquals(classifySymptomChat(words, null), tag)
        assertEquals(ProblemArea.UNSORTED, tag.area)
        assertTrue(tag.flags.none { it in CRISIS_RULE_IDS })
    }

    /**
     * A record the chat files for a record topic, through that topic's own script — every one offered,
     * the pain's, the mood's, the dose's and the course's — carries no hidden tag, while the thread that
     * filed it does, and a symptom's row does.
     */
    @Test
    fun aRecordAChatFilesCarriesNoTag() {
        val setting = chatSetting()
        val records = Symptom.entries.filter { it.record != ChatRecord.SYMPTOM && it.script.offered(setting) }
        assertEquals(listOf(Symptom.PAIN, Symptom.MOOD, Symptom.DOSE, Symptom.COURSE), records)
        val log = driveLog((listOf(Symptom.FEVER) + records).map { ThreadPlan(it) }, setting)
        val store = MemoryChatStore()
        val key = chatKey()
        runBlocking { checkNotNull(ChatFiling(store, key).persist(log, filingContext(log, key))) }

        val rows = store.db.journal.values.groupBy { it.kind }
        assertNotNull(decodeSymptomPayload(rows.getValue(KIND_SYMPTOM).single().measurementPayloadJson).symptomTag)
        assertEquals(1, rows.getValue(KIND_PAIN).size)
        rows.filterKeys { it != KIND_SYMPTOM }.values.flatten().forEach { row ->
            val payload = row.measurementPayloadJson.orEmpty()
            assertFalse(row.kind, "\"tag\"" in payload)
            assertFalse(row.kind, "\"area\"" in payload)
            assertFalse(row.kind, "\"flags\"" in payload)
        }
        val stored = checkNotNull(decodeChatLog(checkNotNull(store.db.chats[key]).payload))
        assertEquals(listOf(Symptom.FEVER) + records, stored.threads.map { it.topic })
        stored.threads.forEach { assertNotNull("${it.topic}", it.report.symptomTag) }
    }

    /** Every thread of a stored chat carries its tag; the symptom row carries it too, a pain row never. */
    @Test
    fun everyThreadOfAStoredChatCarriesItsTag() {
        val store = MemoryChatStore()
        val key = chatKey()
        val fever = ChatThread(0, newChat().pick(Symptom.FEVER).say(SymptomStep.WITH, SymptomOption.CHILLS))
        val pain = valuedThread(1, Symptom.PAIN, SymptomStep.PAIN_NRS to "6")
        val words =
            ChatThread(2, newChat().write("I took an overdose").say(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW))
        val log = logOf(fever, pain, words)
        val plans = object : FilingPlans {
            override fun wants(thread: ChatThread, ctx: FilingContext) =
                if (thread.topic == Symptom.PAIN) mapOf("" to painWant(thread)) else ScriptPlans.wants(thread, ctx)

            override fun recordSig(thread: ChatThread, role: String, ctx: FilingContext) =
                ScriptPlans.recordSig(thread, role, ctx)
        }
        runBlocking { ChatFiling(store, key, plans).persist(log, filingContext(log, key)) }

        val threads = checkNotNull(decodeChatLog(checkNotNull(store.db.chats[key]).payload)).threads
        assertEquals(
            listOf(ProblemArea.TEMPERATURE, ProblemArea.MUSCULOSKELETAL, ProblemArea.UNSORTED),
            threads.map { checkNotNull(it.report.symptomTag).area },
        )
        val rows = store.db.journal.values.groupBy { it.kind }
        assertEquals(2, rows.getValue(KIND_SYMPTOM).size)
        rows.getValue(KIND_SYMPTOM).forEach {
            assertNotNull(decodeSymptomPayload(it.measurementPayloadJson).symptomTag)
        }
        val painPayload = checkNotNull(rows.getValue(KIND_PAIN).single().measurementPayloadJson)
        assertFalse("\"tag\"" in painPayload)
        assertFalse("\"area\"" in painPayload)
    }
}
