package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The doors a chat's records go through, the real ones over an in-memory port: each
 * is its own page's path, so a record from the chat behaves as one its page wrote.
 */
class ChatDoorsTest {
    private val db = MemoryChatDb()
    private val doors = ChatDoors(db)
    private val day = CHAT_TODAY.toString()

    private fun shell(at: Long, kind: String) = JournalEntry(
        day = day,
        occurredAt = at,
        kind = kind,
        title = "",
        calories = 0.0,
    )

    private fun noPain(at: Long) = db.put(
        JournalEntry(
            day = day,
            occurredAt = at,
            kind = KIND_PAIN,
            title = "Pain",
            calories = 0.0,
            measurementPayloadJson = encodePainPayload(PainReport(none = true)),
        ),
    )

    private fun mood(at: Long, score: Int) =
        JournalEntry(day = day, occurredAt = at, kind = KIND_MOOD, title = "Mood", calories = 0.0, moodScore = score)

    private fun dose(at: Long, course: Long, slot: String) = JournalEntry(
        day = day,
        occurredAt = at,
        kind = KIND_MED,
        title = "Metformin",
        calories = 0.0,
        measurementPayloadJson = """{"medicationId":$course,"slot":"$slot"}""",
    )

    /** The pain page clears the day's "no pain today" on every save, and so does the chat's door. */
    @Test
    fun painDoorDeletesTheNoPainAnswerOnEveryWrite() = runBlocking {
        val morning = noPain(CHAT_NOW - 3_600_000)
        val first = doors.write(painRow(shell(CHAT_NOW, KIND_PAIN), nrs = 5), JournalDoor.PAIN, first = true)
        val id = (first as DoorResult.Written).id
        assertEquals(listOf(morning.id), db.deleted)
        assertEquals(listOf(id), db.pains().map { it.id })

        // A "no pain" tapped again later that day goes at the next write too: a pain row overrules it.
        val evening = noPain(CHAT_NOW + 3_600_000)
        val rewrite = painRow(shell(CHAT_NOW, KIND_PAIN).copy(id = id), nrs = 6)
        assertTrue(doors.write(rewrite, JournalDoor.PAIN, first = false) is DoorResult.Written)
        assertEquals(listOf(morning.id, evening.id), db.deleted)
        assertTrue(doors.recordsTouched)
        assertFalse(doors.medicineTouched)
    }

    /** A first mood inside four hours of another is refused, with the one in the way; its own rewrite is not. */
    @Test
    fun moodDoorRefusesInsideTheCooldown() = runBlocking {
        val earlier = db.put(mood(CHAT_NOW - 3 * 3_600_000, score = 2))

        assertEquals(
            DoorResult.Refused(earlier.occurredAt, 2),
            doors.write(mood(CHAT_NOW, score = 4), JournalDoor.MOOD, first = true),
        )
        assertEquals(1, db.journal.size)
        val later = doors.write(mood(CHAT_NOW + 3_600_000 + 1, score = 4), JournalDoor.MOOD, first = true)
        assertTrue(later is DoorResult.Written)
        // Its own row is inside its own cooldown, and a rewrite never asks.
        val id = (later as DoorResult.Written).id
        val rewrite = mood(CHAT_NOW + 3_600_000 + 1, score = 5).copy(id = id)
        assertEquals(DoorResult.Written(id), doors.write(rewrite, JournalDoor.MOOD, first = false))
        assertEquals(5, db.journal.getValue(id).moodScore)
    }

    /**
     * A mood stamped a day ahead — a clock that ran fast, a restore from such a phone — is not the
     * one the door asks about: taken for the newest on file, it would hide the mood of three hours ago
     * and let a second mood through inside that one's pause. The page's own probe has the far side.
     */
    @Test
    fun moodDoorLooksPastAStampFarAhead() = runBlocking {
        db.put(mood(CHAT_NOW + 24 * 3_600_000, score = 1))
        val earlier = db.put(mood(CHAT_NOW - 3 * 3_600_000, score = 2))

        assertEquals(
            DoorResult.Refused(earlier.occurredAt, 2),
            doors.write(mood(CHAT_NOW, score = 4), JournalDoor.MOOD, first = true),
        )
        assertEquals(earlier, db.moodNear(CHAT_NOW))
    }

    /** A dose's slot is marked once a day by any row but its own, at a first write and a rewrite alike. */
    @Test
    fun doseDoorProbesTheSlotLeavingItsOwnRowOut() = runBlocking {
        val marked = db.put(dose(CHAT_NOW - 600_000, course = 7, slot = "480"))

        assertEquals(
            DoorResult.Refused(marked.occurredAt, null),
            doors.write(dose(CHAT_NOW, 7, "480"), JournalDoor.DOSE, true),
        )
        assertEquals(1, db.journal.size)
        // Its own row, rewritten under its id, does not clash with itself.
        val own = marked.copy(subtitle = "500 mg")
        assertEquals(DoorResult.Written(marked.id), doors.write(own, JournalDoor.DOSE, first = false))
        // Another slot of the same course, and the same slot of another course, are free.
        assertTrue(doors.write(dose(CHAT_NOW, 7, "1200"), JournalDoor.DOSE, true) is DoorResult.Written)
        assertTrue(doors.write(dose(CHAT_NOW + 1, 8, "480"), JournalDoor.DOSE, true) is DoorResult.Written)
        // A rewrite moved onto a slot another row holds is refused too.
        val evening = db.journal.values.first { medSlotOf(it) == 7L to "1200" }
        val moved = evening.copy(measurementPayloadJson = """{"medicationId":7,"slot":"480"}""")
        assertEquals(DoorResult.Refused(marked.occurredAt, null), doors.write(moved, JournalDoor.DOSE, first = false))
        assertTrue(doors.medicineTouched)
    }

    /**
     * A course's dose is marked as the Medicine tab settles the day. The times
     * edited from 08:00 to 08:30 after a dose was marked at eight, the tab shows that mark on 08:30: a
     * chat's mark there is refused with its moment, a time the course no longer has is no slot at all,
     * and 20:00 stays open — marked, the tab then shows both doses taken, each by its own row.
     */
    @Test
    fun doseDoorSettlesTheSlotAsTheMedicineTabDoes() = runBlocking {
        val course = Medication(
            id = 7,
            name = "Metformin",
            dose = "500 mg",
            times = "510,1200",
            startDay = CHAT_TODAY.minusDays(30).toString(),
            createdAt = CHAT_NOW - 30 * 86_400_000L,
        )
        db.courses[course.id] = course
        val morning = db.put(dose(CHAT_NOW - 4 * 3_600_000, course = 7, slot = "480"))

        assertEquals(
            DoorResult.Refused(morning.occurredAt, null),
            doors.write(dose(CHAT_NOW, 7, "510"), JournalDoor.DOSE, true),
        )
        assertEquals(DoorResult.Refused(null, null), doors.write(dose(CHAT_NOW, 7, "600"), JournalDoor.DOSE, true))
        assertEquals(1, db.journal.size)
        val evening = doors.write(dose(CHAT_NOW + 1, 7, "1200"), JournalDoor.DOSE, true) as DoorResult.Written
        assertEquals(
            listOf(morning.id, evening.id),
            plannedDoses(listOf(course), db.journal.values.toList(), CHAT_TODAY).map { it.takenEntryId },
        )
        // The morning's own mark rewritten on its own key is no clash, though the course has that time no more.
        assertEquals(
            DoorResult.Written(morning.id),
            doors.write(morning.copy(subtitle = "500 mg"), JournalDoor.DOSE, first = false),
        )
        // The evening's mark moved onto 08:30 is refused, as a first mark there would be.
        val moved = db.journal.getValue(evening.id).copy(measurementPayloadJson = """{"medicationId":7,"slot":"510"}""")
        assertEquals(DoorResult.Refused(morning.occurredAt, null), doors.write(moved, JournalDoor.DOSE, first = false))
    }

    /** A new course is inserted under the id the database gives it, and later written under that id. */
    @Test
    fun courseDoorInsertsWithIdZeroAndRewritesUnderItsId() = runBlocking {
        val course =
            Medication(
                id = 42,
                name = "Sertraline",
                dose = "50 mg",
                times = "540",
                startDay = day,
                createdAt = CHAT_NOW,
            )

        val id = doors.insertCourse(course)
        assertEquals(1L, id)
        assertEquals(course.copy(id = 1), db.courses.getValue(1))
        doors.saveCourse(course.copy(id = id, dose = "100 mg"))
        assertEquals("100 mg", db.courses.getValue(1).dose)
        assertEquals(1, db.courses.size)

        doors.deleteCourse(db.courses.getValue(1).copy(photo = "medicine-1.jpg"))
        assertTrue(db.courses.isEmpty())
        // The photograph goes once the act has committed, never with the row.
        assertEquals(listOf("medicine-1.jpg"), doors.leftoverPhotos)
        assertTrue(doors.medicineTouched)
    }

    /** The moment wanted, then a millisecond at a time; a chat's back-dated row starts one after it. */
    @Test
    fun freeMomentStepsByOneMillisecondAndBackDatedStartsAtPlusOne() = runBlocking {
        val noon = middayOf(CHAT_TODAY, CHAT_ZONE)
        assertEquals(noon, doors.freeMoment(KIND_PAIN, noon, backDated = false))
        assertEquals(noon + 1, doors.freeMoment(KIND_PAIN, noon, backDated = true))

        db.put(painRow(shell(noon, KIND_PAIN), nrs = 3))
        db.put(painRow(shell(noon + 1, KIND_PAIN), nrs = 3))
        assertEquals(noon + 2, doors.freeMoment(KIND_PAIN, noon, backDated = false))
        assertEquals(noon + 2, doors.freeMoment(KIND_PAIN, noon, backDated = true))
        // Another kind on that moment is no obstacle.
        assertEquals(noon, doors.freeMoment(KIND_MOOD, noon, backDated = false))

        // A thousand rows on consecutive milliseconds: the act gives up rather than walk on forever.
        (2L..1_001L).forEach { db.put(painRow(shell(noon + it, KIND_PAIN), nrs = 1)) }
        assertThrows(IllegalStateException::class.java) { runBlocking { doors.freeMoment(KIND_PAIN, noon, false) } }

        db.courses[1] = Medication(id = 1, name = "x", startDay = CHAT_TODAY.toString(), createdAt = CHAT_NOW)
        assertEquals(CHAT_NOW + 1, doors.freeCourseMoment(CHAT_NOW))
    }

    /** A link finds its row by fingerprint; a lone row that is not it was changed; two alike are gone. */
    @Test
    fun aLinkResolvesByMomentAndFingerprint() = runBlocking {
        val row = db.put(painRow(shell(CHAT_NOW, KIND_PAIN), nrs = 4))
        val link = FiledLink(KIND_PAIN, at = CHAT_NOW, title = "Pain", wsig = rowSig(row))

        assertEquals(Resolved.Found(FoundRecord.Row(row)), doors.resolve(link, KEY))
        db.journal[row.id] = row.copy(note = "edited")
        assertTrue(doors.resolve(link, KEY) is Resolved.Changed)
        db.put(row.copy(id = 0))
        assertEquals(Resolved.Found(FoundRecord.Row(db.journal.values.last())), doors.resolve(link, KEY))
        db.put(row.copy(id = 0))
        assertEquals(Resolved.Gone, doors.resolve(link, KEY))
        assertEquals(Resolved.Gone, doors.resolve(link.copy(at = CHAT_NOW + 7), KEY))

        // A symptom row only the chat edits: one on its moment that names this chat is it, whatever its
        // fingerprint says now.
        val symptom = db.put(symptomRow(at = 5L, chat = KEY))
        val found = doors.resolve(FiledLink(KIND_SYMPTOM, at = 5L, title = "Fever", wsig = 99), KEY)
        assertEquals(Resolved.Found(FoundRecord.Row(symptom)), found)
    }

    /**
     * A symptom row another chat filed on the moment this chat's row was deleted from — a back-dated
     * moment, which every chat for that day steps from — is that chat's: this chat's link reads it gone,
     * so its card, its Undo and its rewrites never reach the other chat's row, though it is the lone
     * row on that moment.
     */
    @Test
    fun anotherChatsSymptomRowOnThisChatsMomentIsNeverThisChats() = runBlocking {
        db.put(symptomRow(at = 5L, chat = "another chat"))
        val link = FiledLink(KIND_SYMPTOM, at = 5L, title = "Fever", wsig = 99)

        assertEquals(Resolved.Gone, doors.resolve(link, KEY))
        assertTrue(doors.resolve(link, "another chat") is Resolved.Found)
    }

    /** A symptom row as a chat files one: schema 3, naming the chat [chat]. */
    private fun symptomRow(at: Long, chat: String) = JournalEntry(
        day = day,
        occurredAt = at,
        kind = KIND_SYMPTOM,
        title = "Fever",
        calories = 0.0,
        measurementPayloadJson = encodeSymptomPayload(SymptomReport(symptom = Symptom.FEVER), chatKey = chat),
    )

    private companion object {
        const val KEY = "this chat"
    }
}
