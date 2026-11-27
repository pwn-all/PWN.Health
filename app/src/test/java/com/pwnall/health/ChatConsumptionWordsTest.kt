package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConsumptionWordsTest {
    private val ordinary = listOf(
        "выпил целую колу",
        "Выпила целую колу!",
        "я выпил целую банку колы",
        "выпил целую бутылку воды",
        "выпила всю банку колы",
        "выпил колу вчера в 8",
        "я сегодня выпила чашку кофе",
        "покурил сигаретку",
        "покурила сигарету",
        "выкурил одну сигарету вчера в 20:00",
        "I drank a whole bottle of water.",
        "I smoked a cigarette yesterday at 8 pm",
    )
    private val needsReading = listOf(
        "выпил целую пачку таблеток",
        "выпил целую бутылку корвалола",
        "выпил целую колу и 20 таблеток парацетамола",
        "выпил колу и хочу умереть",
        "покурил сигаретку, задыхаюсь",
        "покурил сигаретку и болит в груди",
        "покурил сигаретку чтобы навредить себе",
        "покурил неизвестную смесь",
        "выпил 20 бутылок воды",
        "выпил колу с таблетками",
        "I smoked a cigarette and cannot breathe",
        "I drank cola and want to die",
        "выпил колу🔪",
    )

    /** A drink's stated amount, up to two litres of water and half a litre of caffeine: still an everyday statement. */
    private val measured = listOf(
        "выпил 200мл воды",
        "Я выпил 200 мл воды.",
        "выпила 0,5 л воды",
        "выпил 1.5 литра воды",
        "выпил стакан воды 250 мл",
        "I drank 250 ml of water",
        "I had 330ml of cola",
        "drank 1.5 liters of water",
        "drank 16 oz of coffee",
        "выпила 0,5 л колы",
        "выпил 300 мл кофе",
    )

    /** More than an everyday drink, or a stated amount of what is no everyday drink, is read as usual. */
    private val measuredNeedsReading = listOf(
        "выпил 5 литров воды",
        "выпил 3000 мл воды",
        "I drank 3 liters of water",
        "drank 100 oz of water",
        "выпил 200 мл корвалола",
        "выпил 200 мл воды и 20 таблеток",
        // Caffeine past half a litre is not an everyday drink.
        "выпил 2 литра кофе",
        "выпил 600 мл кофе",
        "drank 64 oz of coffee",
        "I drank 1 liter of cola",
    )

    @Test
    fun aStatedAmountUpToTwoLitresIsPartOfTheStatement() {
        measured.forEach { assertTrue(it, ordinaryConsumptionIn(it)) }
        measuredNeedsReading.forEach { assertFalse(it, ordinaryConsumptionIn(it)) }
    }

    @Test
    fun onlyCompleteConsumptionStatementsAreRecognised() {
        ordinary.forEach { assertTrue(it, ordinaryConsumptionIn(it)) }
        needsReading.forEach { assertFalse(it, ordinaryConsumptionIn(it)) }
    }

    @Test
    fun beverageContainersAreNotAnOverdoseInTheRules() {
        ordinary.forEach { assertFalse(it, overdoseIn(it)) }
        // With its hour or its amount, read in the message as typed: the words alone have lost the colon
        // and the comma, and read from them «всю банку» beside «выпила» would ask whether it was more than
        // the packet says.
        listOf(
            "выпила всю банку колы в 20:00",
            "выпила всю банку колы 0,33 л",
            "I had a whole bottle of water at 8:30 pm",
        ).forEach {
            assertTrue(it, ordinaryConsumptionIn(it))
            assertFalse(it, overdoseIn(it))
        }
        listOf(needsReading[0], needsReading[1], needsReading[2]).forEach { assertTrue(it, overdoseIn(it)) }
    }

    @Test
    fun completeStatementsDiscardFalseDoseAndCrisisProposalsButRetainOtherFlags() {
        val reading = readingOf(
            flags = ReaderSafety.entries.toSet(),
            topics = mapOf(Symptom.DOSE to 0.99),
        )
        ordinary.forEach { text ->
            val read = reading.decided(CHAT_TODAY, past = false, text = text)
            assertEquals(text, ChatReadOutcome.ABSTAIN, read.why)
            assertTrue(text, read.topics.isEmpty())
            assertTrue(text, read.told.medicines.isEmpty())
            assertEquals(
                text,
                READ_SAFETY_STEPS.filter { it != SymptomStep.CRISIS_NOW && it != SymptomStep.MED_MAX_WORDS },
                read.safety,
            )
        }
    }

    @Test
    fun extraWordsKeepTheEntireUsualReading() {
        val reading = readingOf(flags = ReaderSafety.entries.toSet(), topics = mapOf(Symptom.DOSE to 0.99))
        needsReading.forEach { text ->
            assertEquals(
                text,
                reading.decided(CHAT_TODAY, past = false),
                reading.decided(CHAT_TODAY, past = false, text = text),
            )
        }
    }
}
