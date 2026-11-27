package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The edges of the reconcile table: where a record of an
 * earlier day stands, a dose whose course a restore renumbered, a mood updated after a refusal or
 * left as it is, a row a restore put back, and two compositions that committed one rev after a
 * rotation race. The table's own rows are [ChatFilingTest]'s.
 */
class ChatFilingEdgeTest {
    private val pain = valuedThread(0, Symptom.PAIN, SymptomStep.PAIN_NRS to "6")

    private val painPlans = TestPlans({ thread ->
        if (thread.topic == Symptom.PAIN) mapOf("" to painWant(thread)) else emptyMap()
    })

    /** The first record of a chat for an earlier day stands at that day's 12:00:00.001 — never at the wall clock. */
    @Test
    fun theFirstBackDatedPainIsAtDotOneNeverNow() {
        val store = MemoryChatStore()
        val key = chatKey()
        val day = LocalDate.of(2026, 9, 28)
        val log = logOf(pain, past = true)

        val context = filingContext(log, key, day = day, past = true)
        val result = runBlocking { checkNotNull(ChatFiling(store, key, painPlans).persist(log, context)) }

        val row = store.db.pains().single()
        assertEquals(middayOf(day, CHAT_ZONE) + 1, row.occurredAt)
        assertEquals(day.toString(), row.day)
        assertEquals(row.occurredAt, linkOf(result).at)
        assertNotEquals(CHAT_NOW, row.occurredAt)
    }

    /**
     * In a chat for an earlier day a record deleted elsewhere leaves its moment free; the chat's next
     * record never takes it, so the old card stays the deleted record's and never shows the new one
     * as its own.
     */
    @Test
    fun aMomentTheChatNamedIsNeverGivenToAnotherOfItsRecords() {
        val store = MemoryChatStore()
        val key = chatKey()
        val day = LocalDate.of(2026, 9, 28)
        val filing = ChatFiling(store, key, painPlans)
        val first = valuedThread(1, Symptom.PAIN, SymptomStep.PAIN_NRS to "4")
        val one = logOf(first, past = true)
        runBlocking { filing.persist(one, filingContext(one, key, day = day, past = true)) }
        val row = store.db.pains().single()
        assertEquals(middayOf(day, CHAT_ZONE) + 1, row.occurredAt)
        store.db.journal.remove(row.id)

        val second = valuedThread(2, Symptom.PAIN, SymptomStep.PAIN_NRS to "7")
        val two = logOf(first, second, rev = 2, past = true)
        val result = runBlocking { checkNotNull(filing.persist(two, filingContext(two, key, day = day, past = true))) }
        assertEquals(middayOf(day, CHAT_ZONE) + 2, store.db.pains().single().occurredAt)
        assertEquals(LinkState.GONE, linkOf(result, 1).state)
        assertEquals(middayOf(day, CHAT_ZONE) + 2, linkOf(result, 2).at)
    }

    /** A restore gives every course a new id and rewrites each dose to it; the chat's dose is still its own. */
    @Test
    fun aScheduledDoseLinkSurvivesARestoreThatRenumbersItsCourse() {
        val store = MemoryChatStore()
        val key = chatKey()
        val courseId = store.db.insertMedicationNow(sertraline())
        val dose = valuedThread(0, Symptom.DOSE, SymptomStep.PAIN_NRS to "1")
        val plans = TestPlans({ _ ->
            mapOf(
                "" to RecordWant.File(
                    RecordDraft.Journal(JournalDoor.DOSE, wanted = null, slot = "540", course = SERTRALINE_AT) {
                            shell,
                            _,
                        ->
                        doseRow(shell, courseId, "540")
                    },
                ),
            )
        })
        val filing = ChatFiling(store, key, plans)
        val filed = linkOf(persist(filing, logOf(dose), key))

        // The restore: the course comes back under another id, the dose pointed at it, every row renumbered.
        val moved = courseId + 7
        store.db.courses.remove(courseId)?.let { store.db.courses[moved] = it.copy(id = moved) }
        store.db.journal.values.toList().forEach { row ->
            val payload = remappedDosePayload(row.measurementPayloadJson, mapOf(courseId to moved))
            store.db.journal[row.id] = row.copy(measurementPayloadJson = payload)
        }
        store.db.renumber()

        val after = persist(filing, logOf(dose, rev = 2), key, now = CHAT_NOW + 60_000)

        assertEquals(LinkState.FILED, linkOf(after).state)
        assertEquals(filed.at, linkOf(after).at)
        assertEquals(1, store.db.journal.size)
    }

    /** The mood door said no; "Update it" then changes the mood in the way, at that mood's own moment. */
    @Test
    fun anUpdateAfterARefusalFilesAtItsOwnMoment() {
        val store = MemoryChatStore()
        val key = chatKey()
        val earlier = store.db.put(moodRow(CHAT_NOW - 3_600_000, score = 4))
        val mood = valuedThread(0, Symptom.MOOD, SymptomStep.PAIN_NRS to "2")
        val plans = TestPlans({ thread ->
            if (thread.report.values[SymptomStep.PAIN_LASTED] == "UPDATE") {
                mapOf(
                    "" to RecordWant.File(
                        RecordDraft.Update(KIND_MOOD, earlier.occurredAt, change = { it.copy(moodScore = 2) }),
                    ),
                )
            } else {
                mapOf("" to moodWant(thread))
            }
        })
        val filing = ChatFiling(store, key, plans)
        assertEquals(LinkState.REFUSED, linkOf(persist(filing, logOf(mood), key)).state)

        val update = valuedThread(0, Symptom.MOOD, SymptomStep.PAIN_NRS to "2", SymptomStep.PAIN_LASTED to "UPDATE")
        val result = persist(filing, logOf(update, rev = 2), key, now = CHAT_NOW + 60_000)

        val link = linkOf(result)
        assertEquals(LinkState.FILED, link.state)
        assertEquals(earlier.occurredAt, link.at)
        assertFalse(link.made)
        assertEquals(rowPrior(earlier), link.prior)
        val row = store.db.journal.values.single()
        assertEquals(earlier.id to 2, row.id to row.moodScore)
    }

    /**
     * Refused, then "Leave it as it is": the thread wants the record kept as it stands, so the card
     * keeps its refusal and Save again files nothing — the same cooldown would only refuse it again.
     */
    @Test
    fun leaveItAfterARefusalKeepsTheRefusedCardWithoutSaveAgain() {
        val store = MemoryChatStore()
        val key = chatKey()
        store.db.put(moodRow(CHAT_NOW - 3_600_000, score = 4))
        val mood = valuedThread(0, Symptom.MOOD, SymptomStep.PAIN_NRS to "2")
        val plans = TestPlans({ thread ->
            val left = thread.report.values[SymptomStep.PAIN_LASTED] == "LEAVE"
            mapOf("" to if (left) RecordWant.Keep else moodWant(thread))
        })
        val filing = ChatFiling(store, key, plans)
        persist(filing, logOf(mood), key)

        val left = valuedThread(0, Symptom.MOOD, SymptomStep.PAIN_NRS to "2", SymptomStep.PAIN_LASTED to "LEAVE")
        val log = logOf(left, rev = 2)
        val kept = persist(filing, log, key)
        assertEquals(LinkState.REFUSED, linkOf(kept).state)
        assertFalse(canSaveAgain(plans, left, "", filingContext(log, key)))

        val again = runBlocking { checkNotNull(filing.saveAgain(0, "", filingContext(log, key))) }
        assertEquals(LinkState.REFUSED, linkOf(again).state)
        assertEquals(1, store.db.journal.size)
        // A thread that still wants it filed has its Save again.
        assertTrue(canSaveAgain(plans, mood, "", filingContext(logOf(mood), key)))
    }

    /** Undo, then a restore put the very row back on its moment: Save again takes it, writing nothing. */
    @Test
    fun saveAgainReadoptsARowARestorePutBack() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans)
        persist(filing, logOf(pain), key)
        val row = store.db.pains().single()
        runBlocking { checkNotNull(filing.undo(0, "", filingContext(logOf(pain), key))) }
        assertTrue(store.db.pains().isEmpty())
        store.db.put(row.copy(id = 0))
        val writes = store.db.writes

        val again = runBlocking { checkNotNull(filing.saveAgain(0, "", filingContext(logOf(pain), key))) }

        assertEquals(LinkState.FILED, linkOf(again).state)
        assertEquals(writes, store.db.writes)
        assertEquals(1, store.db.pains().size)
    }

    /**
     * After a rotation race two compositions commit their own logs at one rev. The second, over
     * other threads, is stale: it changes nothing, so its thread never takes back the record of the
     * thread that has the same id in the first.
     */
    @Test
    fun twoEqualRevLogsWithDifferentThreadsNeverTakeBackEachOthersRecords() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans)
        val fever = ChatThread(1, newChat().pick(Symptom.FEVER))
        val knee = valuedThread(1, Symptom.PAIN, SymptomStep.PAIN_NRS to "4")
        persist(filing, logOf(pain), key)
        persist(filing, logOf(pain, knee, rev = 2), key)
        val rows = store.db.pains().toSet()

        val other = persist(filing, logOf(pain, fever, rev = 2), key)

        assertNotNull(other.newer)
        assertEquals(listOf(0, 1), other.newer?.threads?.map { it.id })
        assertEquals(Symptom.PAIN, other.newer?.threads?.last()?.topic)
        assertEquals(rows, store.db.pains().toSet())
        // The same threads at the same rev — a rotation sending its write again — pass as before.
        assertNull(persist(filing, logOf(pain, knee, rev = 2), key).newer)
    }

    /**
     * The answer that ends a thread, committed with the thread the router starts itself after it: the
     * thread the stored chat ended on is rebuilt with the new one, so a 4/10 changed to 7/10 is filed —
     * and so it is when that act failed, by the act after it.
     */
    @Test
    fun theLastAnswerOfAThreadIsFiledWhenTheNextThreadComesInTheSameCommit() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans)
        val knee = valuedThread(1, Symptom.PAIN, SymptomStep.PAIN_NRS to "4")
        val chest = ChatThread(2, newChat().pick(Symptom.CHEST_PAIN))
        persist(filing, logOf(knee), key)
        assertEquals(4, decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs)

        persist(filing, logOf(knee.revalued(SymptomStep.PAIN_NRS, "7"), chest, rev = 2), key, now = CHAT_NOW + 1_000)
        assertEquals(7, decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs)

        // The act fails: the chat alone is kept, and the next act — the chest now the newest — still files it.
        val failing = MemoryChatStore()
        val failed = ChatFiling(failing, key, painPlans)
        persist(failed, logOf(knee), key)
        failing.db.failing = "writeJournal"
        val both = logOf(knee.revalued(SymptomStep.PAIN_NRS, "7"), chest, rev = 2)
        persist(failed, both, key, now = CHAT_NOW + 1_000)
        assertEquals(4, decodePainPayload(failing.db.pains().single().measurementPayloadJson).nrs)
        assertEquals(listOf(1, 2), stored(failing, key).threads.map { it.id })
        persist(failed, both.copy(rev = 3), key, now = CHAT_NOW + 2_000)
        assertEquals(7, decodePainPayload(failing.db.pains().single().measurementPayloadJson).nrs)
        assertTrue(stored(failing, key).threads.flatMap { it.filed }.none { it.err })
    }

    /**
     * A thread a rewind took away while the act that should have taken its record back failed: the
     * chat stays stored as it was, so the next act still knows the record is there and takes it back.
     */
    @Test
    fun aFailedActAfterARewindKeepsTheRecordItHadToTakeBack() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans)
        val fever = ChatThread(1, newChat().pick(Symptom.FEVER))
        val knee = valuedThread(2, Symptom.PAIN, SymptomStep.PAIN_NRS to "4")
        persist(filing, logOf(fever, knee), key)
        assertEquals(1, store.db.pains().size)

        store.db.failing = "saveChat"
        persist(filing, logOf(fever, rev = 2), key, now = CHAT_NOW + 1_000)

        assertEquals(1, store.db.pains().size)
        assertEquals(listOf(1, 2), stored(store, key).threads.map { it.id })
        persist(filing, logOf(fever, rev = 3), key, now = CHAT_NOW + 2_000)
        assertTrue(store.db.pains().isEmpty())
        assertEquals(listOf(1), stored(store, key).threads.map { it.id })
    }

    /**
     * A chat deleted, then brought back by a backup written before the deletion: its row is there
     * again, and so the chat is written again — while its deletion was pending nothing was, and the
     * deleted key is not refused for the rest of the process.
     */
    @Test
    fun aChatDeletedAndBroughtBackByABackupIsWrittenAgain() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans)
        persist(filing, logOf(pain), key)
        val backup = store.db.chats.getValue(key)
        runBlocking { filing.deleteChat() }
        val later = logOf(pain.revalued(SymptomStep.PAIN_NRS, "2"), rev = 2)
        assertNull(runBlocking { filing.persist(later, filingContext(later, key)) })

        store.db.chats[key] = backup

        val written = runBlocking { filing.persist(later, filingContext(later, key)) }
        assertNotNull(written)
        assertEquals(2, decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs)
        assertEquals(2, stored(store, key).rev)
    }

    private fun sertraline() = Medication(
        name = "Sertraline",
        dose = "50 mg",
        times = "540",
        startDay = "2026-10-05",
        createdAt = SERTRALINE_AT,
    )

    private fun doseRow(shell: JournalEntry, course: Long, slot: String) = JournalEntry(
        id = shell.id,
        day = shell.day,
        occurredAt = shell.occurredAt,
        kind = KIND_MED,
        title = "Sertraline",
        subtitle = "50 mg",
        calories = 0.0,
        measurementPayloadJson = """{"medicationId":$course,"slot":"$slot"}""",
    )

    private fun MemoryChatDb.insertMedicationNow(row: Medication): Long = runBlocking { insertMedication(row) }

    private companion object {
        const val SERTRALINE_AT = 1_790_000_000_000L
    }
}
