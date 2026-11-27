package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The pain row a chat files: the very row the pain page would write for the same answers
 * — the same payload, byte for byte, the same columns — but for its moment, which for a chat of a day
 * gone by is never the pages' own midday; on the current map; without a painting when chips answered.
 */
class PainChatRecordTest {
    /** One act of the filer over [log]: the pain row it wrote. */
    private fun filedRow(log: ChatLog, past: Boolean = false, day: LocalDate = CHAT_TODAY): JournalEntry {
        val store = MemoryChatStore()
        val key = chatKey()
        runBlockingFiling(store, log, key, past, day)
        return store.painRows().single()
    }

    private fun runBlockingFiling(store: MemoryChatStore, log: ChatLog, key: String, past: Boolean, day: LocalDate) {
        kotlinx.coroutines.runBlocking {
            checkNotNull(ChatFiling(store, key).persist(log, filingContext(log, key, day = day, past = past)))
        }
    }

    /** A pain gone after an hour and a half, begun just now, painted, words and all: as the page saves it. */
    @Test
    fun theChatRowIsTheBytesThePageWouldWrite() {
        val knee = dabOn("KNEE:R")
        val log = ChatLog().choseTopic(TopicChoice(Symptom.PAIN), chatSetting()).settle()
            .paint(listOf(knee))
            .tapOn(SymptomOption.NO)
            .tick(SymptomOption.NONE)
            .tick(SymptomOption.PAIN_SHARP, SymptomOption.PAIN_ACHING)
            .tapOn(SymptomOption.STARTED_NOW)
            .give("7")
            .tapOn(SymptomOption.NOTICED_DURING_WORKOUT)
            .tapOn(SymptomOption.GONE)
            .give("90")
            .tapOn(SymptomOption.YES)
        assertEquals(ChatMove.Prompt, chatMove(log, chatSetting()))
        val row = filedRow(log)
        // What the pain page's own save writes for the same answers at that moment.
        val answers = PainReport(
            regions = listOfNotNull(RegionSide.parse("KNEE:R")),
            types = listOf(PainType.ACHING, PainType.SHARP),
            nrs = 7,
            onset = PainOnset.JUST_NOW,
            lastedMinutes = 90,
            noticed = PainNoticed.DURING_WORKOUT,
            painkiller = true,
            strokes = listOf(knee),
            map = PainMapVersion.CURRENT,
        )
        val page = painEntry(
            "Pain",
            painReportToSave(answers, row.occurredAt, earlier = null, same = null, changed = null, editing = null),
            CHAT_TODAY,
            CHAT_TODAY,
            row.occurredAt,
        )
        assertEquals(page.measurementPayloadJson, row.measurementPayloadJson)
        assertEquals(page.copy(id = row.id), row)
        assertEquals(90.0, row.durationMinutes)
        val report = decodePainPayload(row.measurementPayloadJson)
        // Gone by the report, which is where it ended: it began as long before as it lasted.
        assertEquals(row.occurredAt - 90 * 60_000L, report.onsetAt)
        assertEquals(row.occurredAt, report.endedAt)
        // The order the words were ticked in is the options' order, as the page's list gives it.
        assertEquals(listOf(PainType.ACHING, PainType.SHARP), report.types)
    }

    /** A pain told in a chat for a day gone by stands at that day's 12:00:00.001, never the pages' .000. */
    @Test
    fun aBackDatedChatPainIsNeverAtMidday() {
        val past = chatSetting(past = true)
        val log = ChatLog(past = true).choseTopic(TopicChoice(Symptom.PAIN), past).settle(past)
            .paint(listOf(dabOn("SHIN:L")), setting = past)
            .calmToTheEnd(past)
        val row = filedRow(log, past = true, day = past.day)
        assertEquals(past.day.toString(), row.day)
        assertEquals(middayOf(past.day, CHAT_ZONE) + 1, row.occurredAt)
        assertNotEquals(dayInstant(past.day.toString()), row.occurredAt)
    }

    /** A new entry is painted on the current map, and says so in its payload. */
    @Test
    fun theMapIsCurrent() {
        val log = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L").calmToTheEnd()
        val row = filedRow(log)
        assertTrue(checkNotNull(row.measurementPayloadJson).startsWith("{\"schema\":${PainMapVersion.CURRENT.schema},"))
        assertEquals(PainMapVersion.CURRENT, decodePainPayload(row.measurementPayloadJson).map)
    }

    /**
     * Every kind of pain and every first-noticed answer the chat offers is saved as the pain page's own
     * of the same name, in the page's order: the two are paired one by one, never by their places in two
     * lists, where a word added to one would have moved the rest onto the wrong word.
     */
    @Test
    fun everyKindAndFirstNoticedAnswerOfferedIsSavedAsThePagesOwn() {
        val state = ChatState(SymptomReport(symptom = Symptom.PAIN), female = false, earlier = null)
        fun saved(step: SymptomStep, option: SymptomOption): PainReport =
            painRecordReport(SymptomReport(symptom = Symptom.PAIN, answers = mapOf(step to listOf(option))), CHAT_NOW)
        val kinds = PainScript.options(state, SymptomStep.PAIN_TYPES)
        assertEquals(PainType.entries.map { "PAIN_${it.name}" }, kinds.map { it.name })
        kinds.forEach { option ->
            val type = PainType.valueOf(option.name.removePrefix("PAIN_"))
            assertEquals(option.name, listOf(type), saved(SymptomStep.PAIN_TYPES, option).types)
        }
        val moments = PainScript.options(state, SymptomStep.PAIN_NOTICED)
        assertEquals(PainNoticed.entries.map { "NOTICED_${it.name}" }, moments.map { it.name })
        moments.forEach { option ->
            val noticed = PainNoticed.valueOf(option.name.removePrefix("NOTICED_"))
            assertEquals(option.name, noticed, saved(SymptomStep.PAIN_NOTICED, option).noticed)
        }
    }

    /** Areas tapped from chips record no painting: the doctor's drawing shows only what was painted. */
    @Test
    fun chipsOnlyAnswersHaveNoStrokes() {
        val chips = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L").calmToTheEnd()
        val row = filedRow(chips)
        assertFalse("strokes" in checkNotNull(row.measurementPayloadJson))
        assertTrue(decodePainPayload(row.measurementPayloadJson).strokes.isEmpty())
        val painted = ChatLog().choseTopic(TopicChoice(Symptom.PAIN), chatSetting()).settle()
            .paint(listOf(dabOn("KNEE:L"))).calmToTheEnd()
        assertTrue("\"strokes\":" in checkNotNull(filedRow(painted).measurementPayloadJson))
    }
}
