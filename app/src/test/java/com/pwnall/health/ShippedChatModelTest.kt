package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Loads the trained v1 package, including its real calibration, through the app's reader. */
class ShippedChatModelTest {
    @Test
    fun shortEmotionalAnswerStillNarrowsWhenTheShippedReaderAbstains() {
        val load = shipped()
        val engine = (load as ChatModelLoad.Loaded).engine as OnnxChatModelEngine
        try {
            val setting = chatSetting()
            val first = "привет.мне плохо?"
            val initial = ChatLog()
            val firstContext = initial.readerContext(setting)
            val firstReading = engine.read(engine.prepare(first, firstContext))
            val firstRead = firstReading.decided(CHAT_TODAY, past = false, text = first, context = firstContext)
            val before = initial.sentRead(first, firstRead, setting)
            val initialReply = chatTurn(before, setting).reply as ChatReply.Topics
            assertEquals(ChatNarrow.entries, initialReply.narrow)
            val context = before.readerContext(setting)
            val reading = engine.read(engine.prepare("на душе", context))
            val read = reading.decided(CHAT_TODAY, past = false, text = "на душе", context = context)
            assertEquals(ChatReadOutcome.ABSTAIN, read.why)
            val after = before.sentRead("на душе", read, setting)
            val turn = chatTurn(after, setting)
            assertEquals(listOf(ChatLine.UNWELL_PICK), turn.lines())
            val reply = turn.reply as ChatReply.Topics
            assertTrue(reply.choices.any { it.topic == Symptom.MOOD })
            assertFalse(reply.choices.any { it.topic == Symptom.PAIN })
            assertTrue(reply.narrow.isEmpty())
            val messages = messagesOf(after.threads.single().report.chat).map { it.text }
            assertEquals(listOf("привет.мне плохо?", "на душе"), messages)
        } finally {
            engine.close()
        }
    }

    @Test
    fun beverageAndCigaretteMessagesDoNotBecomeCrisisOrMedicationChats() {
        val load = shipped()
        val engine = (load as ChatModelLoad.Loaded).engine as OnnxChatModelEngine
        try {
            val ordinary = listOf(
                "выпил целую колу",
                "выпил колу",
                "выпила целую банку колы",
                "выпил целую бутылку воды",
                "покурил сигаретку",
                "покурил сигарету вчера в 8",
            )
            ordinary.forEach { text ->
                val reading = engine.read(engine.prepare(text, ChatReaderContext()))
                val read = reading.decided(CHAT_TODAY, past = false, text = text)
                assertTrue("$text: $read", read.safety.isEmpty())
                assertTrue("$text: $read", read.topics.isEmpty())
                assertTrue("$text: $read", read.told.medicines.isEmpty())
                val log = ChatLog().sentRead(text, read)
                assertEquals(text, SymptomStep.SYMPTOM, log.asked())
            }
            mapOf(
                "выпил целую пачку таблеток" to SymptomStep.MED_MAX_WORDS,
                "выпил колу и не хочу жить" to SymptomStep.CRISIS_NOW,
                "покурил сигаретку и болит в груди" to SymptomStep.CHEST_NOW,
                "покурил сигаретку и задыхаюсь" to SymptomStep.BREATH_NOW,
            ).forEach { (text, step) ->
                val reading = engine.read(engine.prepare(text, ChatReaderContext()))
                val read = reading.decided(CHAT_TODAY, past = false, text = text)
                assertTrue("$text: $read", step in read.safety)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun theTrainedPackageEnablesTopicsWithoutChangingTheExamResult() {
        val load = shipped()
        assertTrue("$load", load is ChatModelLoad.Loaded)
        val engine = (load as ChatModelLoad.Loaded).engine as OnnxChatModelEngine
        try {
            assertTrue(engine.mapped)
            assertFalse(engine.manifest.smoke)
            assertEquals(emptySet<String>(), engine.manifest.languages)
            assertEquals(engine.schema.head("lang").labels.filter { it != "OTHER" }.toSet(), engine.languages)
            // The choice, spelled out: the manifest lists these to route in and names no
            // journal_languages, so journal forms are offered in the same sixteen. A list that changes,
            // or a journal gate that arrives or goes, has to change this line with it.
            val enabled = "en ar cs de es fr hi id it ja ko pl pt ru tr zh".split(" ").toSet()
            assertEquals(enabled, engine.manifest.routingLanguages)
            assertEquals(enabled, engine.manifest.journalLanguages)
            val decisions = mutableListOf<ChatRead>()
            for (text in listOf("болит правое колено, 6/10", "I took paracetamol at 8", "我今天头疼")) {
                val reading = engine.read(engine.prepare(text, ChatReaderContext()))
                assertEquals(engine.model, reading.model)
                assertTrue("$text: ${reading.lang}", reading.examined)
                decisions += reading.decided(LocalDate.of(2026, 10, 8), past = false)
                assertEquals(Symptom.entries.size, reading.topics.size)
                assertTrue(reading.topics.all { it.p.isFinite() && it.p in 0.0..1.0 })
                assertTrue(reading.safety.all { it.p.isFinite() && it.p in 0.0..1.0 })
            }
            assertEquals(listOf(Symptom.PAIN), decisions[0].topics)
            assertEquals(listOf(Symptom.DOSE), decisions[1].topics)
            assertEquals(listOf(Symptom.PAIN), decisions[2].topics)
        } finally {
            engine.close()
        }
    }

    @Test
    fun theTrainedPackageTellsPlacesScoresMedicinesAndClocks() {
        val engine = (shipped() as ChatModelLoad.Loaded).engine as OnnxChatModelEngine
        try {
            val told = listOf("болит правое колено, 6/10", "I took paracetamol at 8", "我今天头疼").map { text ->
                val reading = engine.read(engine.prepare(text, ChatReaderContext()))
                reading.decided(LocalDate.of(2026, 10, 8), past = false).told
            }
            assertEquals(listOf(ReadPlace("KNEE", "KNEE", SIDE_RIGHT)), told[0].places)
            assertEquals(6, told[0].nrs)
            assertEquals(listOf(ReadMedicine("paracetamol", listOf(ReadRole.TAKEN))), told[1].medicines)
            assertEquals(listOf(480, 1200), told[1].clocks)
            assertEquals("HEAD", told[2].places.single().family)
        } finally {
            engine.close()
        }
    }

    /** The shipped package as the app opens it: the released weights, never a smoke package's. */
    private fun shipped(): ChatModelLoad = OnnxChatModelEngine.load(
        ChatModelTestFiles.files(listOf(ChatModelTestFiles.assets)),
        threads = 1,
        allowSmoke = false,
    )
}
