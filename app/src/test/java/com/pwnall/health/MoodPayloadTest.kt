package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The mood row's payload — what the mood was about, and nothing else — and what puts back a mood the
 * chat updated: the very columns its update changed, readable by the chat's own putting back.
 */
class MoodPayloadTest {
    @Test
    fun theReasonsAreWrittenByHandInTheirOrder() {
        assertEquals(
            """{"schema":1,"about":["NO_CLEAR_REASON"]}""",
            encodeMoodPayload(listOf(SymptomOption.NO_CLEAR_REASON)),
        )
        // In the options' own order whatever order they were ticked in, each once.
        assertEquals(
            """{"schema":1,"about":["PEOPLE","MOVEMENT"]}""",
            encodeMoodPayload(listOf(SymptomOption.MOVEMENT, SymptomOption.PEOPLE, SymptomOption.MOVEMENT)),
        )
        assertEquals(
            """{"schema":1,"about":["WORK_STUDY","FAMILY"]}""",
            encodeMoodPayload(setOf(SymptomOption.FAMILY, SymptomOption.WORK_STUDY)),
        )
        // Nothing picked: no payload at all.
        assertNull(encodeMoodPayload(emptyList()))
    }

    @Test
    fun theReasonsReadBackAndWhatThisBuildDoesNotKnowIsDropped() {
        val about = listOf(SymptomOption.WORK_STUDY, SymptomOption.POOR_SLEEP, SymptomOption.SOMETHING_ELSE)
        assertEquals(about.sortedBy { it.ordinal }, decodeMoodAbout(encodeMoodPayload(about)))
        assertEquals(
            listOf(SymptomOption.REST),
            decodeMoodAbout("""{"schema":2,"about":["REST","A_LATER_REASON",7,"REST"],"later":true}"""),
        )
        assertEquals(emptyList<SymptomOption>(), decodeMoodAbout("not json"))
        assertEquals(emptyList<SymptomOption>(), decodeMoodAbout("""{"schema":1}"""))
        assertEquals(emptyList<SymptomOption>(), decodeMoodAbout(null))
    }

    /** The row the chat writes is the page's own, with the moment of the dial and what it was about. */
    @Test
    fun theChatsMoodIsThePagesRowWithItsMomentAndReasons() {
        val day = LocalDate.of(2026, 10, 5)
        val page = moodEntry(::stringName, Mood.LOW, Energy.STEADY, day)
        val family = encodeMoodPayload(listOf(SymptomOption.FAMILY))
        val chat = moodEntry(::stringName, Mood.LOW, Energy.STEADY, day, at = 42L, payload = family)
        assertEquals(page.copy(occurredAt = 42L, measurementPayloadJson = """{"schema":1,"about":["FAMILY"]}"""), chat)
        assertEquals("mood_title", chat.title)
        assertEquals("mood_low, energy_steady", chat.note)
        assertEquals(2, chat.moodScore)
        assertEquals(3, chat.energyScore)
        assertNull(chat.measurementSource)
        assertNull(chat.healthConnectRecordKey)
        // No tension answered: no second word, no figure.
        assertEquals("mood_fun", moodEntry(::stringName, Mood.FUN, null, day).note)
    }

    @Test
    fun aPriorPutsTheFourColumnsBack() {
        val before = pageMood(at = 1_000L, score = 2, energy = 3).copy(
            note = "Low, Steady",
            measurementPayloadJson = encodeMoodPayload(listOf(SymptomOption.FAMILY)),
        )
        val prior = moodPrior(before)
        assertEquals(
            """{"mood":2,"energy":3,"note":"Low, Steady","payload":"{\"schema\":1,\"about\":[\"FAMILY\"]}"}""",
            prior,
        )
        val after = before.copy(moodScore = 5, energyScore = null, note = "Fun", measurementPayloadJson = null)
        // The chat has one putting back for every changed record, and it reads this prior to the same row.
        assertEquals(before, restoredRow(after, prior))
        // A mood with no tension and nothing about it: put back without them, not with the later ones.
        val plain = pageMood(at = 2_000L, score = 4)
        val loss = """{"schema":1,"about":["LOSS"]}"""
        val changed = plain.copy(moodScore = 1, energyScore = 2, measurementPayloadJson = loss)
        assertEquals("""{"mood":4,"note":"page words"}""", moodPrior(plain))
        assertEquals(plain, restoredRow(changed, moodPrior(plain)))
        // A prior this build cannot read changes nothing.
        assertEquals(changed, restoredRow(changed, "garbage"))
    }
}
