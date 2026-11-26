package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dose taken outside every course ([oneOffDoseEntry]): its payload as written by hand, never a
 * mark of a course's slot — not even after the course's times are edited and the orphaned marks are
 * paired again — and the same through a restore that renumbers every course.
 */
class MedicineOneOffTest {
    private val at = 1_790_000_000_000L

    @Test
    fun theOneOffsPayloadIsWrittenByHandInItsOrder() {
        val plain = oneOffDoseEntry(" Нурофен ", "2 tablets", CHAT_TODAY, at, null)
        assertEquals("""{"medicationId":0,"slot":"X1790000000000"}""", plain.measurementPayloadJson)
        assertEquals("Нурофен", plain.title)
        assertEquals("2 tablets", plain.subtitle)
        assertEquals(KIND_MED, plain.kind)
        assertEquals(CHAT_TODAY.toString(), plain.day)
        val forPain = oneOffDoseEntry("Нурофен", "2 tablets", CHAT_TODAY, at, MedTakenFor.PAIN)
        assertEquals("""{"medicationId":0,"slot":"X1790000000000","for":"PAIN"}""", forPain.measurementPayloadJson)
        assertEquals(MedTakenFor.PAIN, medTakenFor(forPain))
        assertNull(medTakenFor(plain))
        assertEquals("a".repeat(MED_NAME_MAX), oneOffDoseEntry("a".repeat(80), "", CHAT_TODAY, at, null).title)
    }

    @Test
    fun aOneOffIsToldApartFromAMarkOfACourse() {
        val mark = medTakenEntry(
            PlannedDose(7, "Sertraline", "50 mg", MedFood.ANY, "540", 540, onWaking = false),
            CHAT_TODAY,
            at,
        )
        assertTrue(isOneOffDose(oneOffDoseEntry("Нурофен", "1 tablet", CHAT_TODAY, at, MedTakenFor.OTHER)))
        assertFalse(isOneOffDose(mark))
        assertNull(medTakenFor(mark))
    }

    @Test
    fun aOneOffNeverSettlesASlotAlsoAfterATimeEdit() {
        val course = course("Sertraline", id = 7, times = "540")
        val oneOff = oneOffDoseEntry("Sertraline", "1 tablet", CHAT_TODAY, todayAt(9 * 60), null)
        // A one-off written with the course's own id — as a hand-edited file could hold it — settles nothing either.
        val strayed = oneOff.copy(measurementPayloadJson = """{"medicationId":7,"slot":"X${todayAt(9 * 60)}"}""")
        listOf(oneOff, strayed).forEach { row ->
            assertTrue(plannedDoses(listOf(course), listOf(row), CHAT_TODAY).none { it.taken })
            // The times edited from 09:00 to 10:00: an orphaned mark is paired with the nearest slot, a one-off never.
            assertTrue(plannedDoses(listOf(course.copy(times = "600")), listOf(row), CHAT_TODAY).none { it.taken })
        }
        val marked =
            medTakenEntry(plannedDoses(listOf(course), emptyList(), CHAT_TODAY).single(), CHAT_TODAY, todayAt(9 * 60))
        assertTrue(plannedDoses(listOf(course.copy(times = "600")), listOf(marked), CHAT_TODAY).single().taken)
    }

    /** A dose on its own counts in nothing a course does: the chart, its share, what a dose holds, the alarms. */
    @Test
    fun adherenceIntakeAndTheAlarmsAreTheSameWithOneOffsAndWithout() {
        val vitamin = course("Vitamin D", id = 7, times = "540,1260").copy(contents = "VITAMIN_D=25")
        val marked = medTakenEntry(
            plannedDoses(listOf(vitamin), emptyList(), CHAT_TODAY).first(),
            CHAT_TODAY,
            todayAt(9 * 60),
        )
        val oneOffs = listOf(
            oneOffDoseEntry("Vitamin D", "2 tablets", CHAT_TODAY, todayAt(13 * 60), null),
            oneOffDoseEntry(
                "Нурофен",
                "1 tablet",
                CHAT_TODAY.minusDays(1),
                todayAt(13 * 60, CHAT_TODAY.minusDays(1)),
                null,
            ),
        )
        val without = listOf(marked)
        val with = without + oneOffs
        val now = 14 * 60
        assertEquals(
            medAdherence(listOf(vitamin), without, CHAT_TODAY, nowMinute = now, zone = CHAT_ZONE),
            medAdherence(listOf(vitamin), with, CHAT_TODAY, nowMinute = now, zone = CHAT_ZONE),
        )
        val from = CHAT_TODAY.minusDays(3)
        assertEquals(
            supplementIntake(listOf(vitamin), without, from, CHAT_TODAY, CHAT_TODAY, now, CHAT_ZONE),
            supplementIntake(listOf(vitamin), with, from, CHAT_TODAY, CHAT_TODAY, now, CHAT_ZONE),
        )
        val doses = { journal: List<JournalEntry> -> plannedDoses(listOf(vitamin), journal, CHAT_TODAY) }
        assertEquals(doses(without), doses(with))
        assertEquals(medNudge(doses(without), 21 * 60 + 10, null), medNudge(doses(with), 21 * 60 + 10, null))
    }

    @Test
    fun aOneOffsPayloadSurvivesARestoreThatRenumbersTheCourses() {
        val row = oneOffDoseEntry("Нурофен", "1 tablet", CHAT_TODAY, at, MedTakenFor.PAIN)
        assertEquals(row.measurementPayloadJson, remappedDosePayload(row.measurementPayloadJson, mapOf(1L to 9L)))
        assertEquals(journalKey(row), journalKey(row.copy(id = 42)))
    }
}
