package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The one writer of a chat's records, over the real doors and an in-memory database: every row of
 * the reconcile table, and the ways it could go wrong — a page's own row taken for the
 * chat's, a correction overwritten, a deletion undone, a language switch that rewrites everything.
 * The acts around the table are [ChatFilingActsTest]'s.
 */
class ChatFilingTest {
    private val pain = valuedThread(0, Symptom.PAIN, SymptomStep.PAIN_NRS to "6")

    private fun painPlans(wanted: Long? = null) = TestPlans({ thread ->
        if (thread.topic == Symptom.PAIN) mapOf("" to painWant(thread, wanted)) else emptyMap()
    })

    /** File, no link: written at the act's moment, named by kind, moment and fingerprint, and a first. */
    @Test
    fun aFirstWriteIsFiledAtItsMomentAndLinkedByFingerprint() {
        val store = MemoryChatStore()
        val key = chatKey()
        val result = persist(ChatFiling(store, key, painPlans()), logOf(pain), key)

        val row = store.db.pains().single()
        val link = linkOf(result)
        assertEquals(CHAT_NOW, row.occurredAt)
        assertEquals(CHAT_TODAY.toString(), row.day)
        assertEquals(LinkState.FILED, link.state)
        assertEquals(KIND_PAIN to CHAT_NOW, link.kind to link.at)
        assertEquals(rowSig(row), link.wsig)
        assertEquals(answersSig(pain), link.sig)
        assertEquals("Pain", link.title)
        assertEquals(listOf(0 to link), result.firsts)
        // The chat row holds the same link, and the thread its hidden tag.
        val thread = stored(store, key).threads.single()
        assertEquals(listOf(link), thread.filed)
        assertNotNull(thread.report.symptomTag)
    }

    /** File, FILED, found and unchanged: nothing is written, and nothing flashes. */
    @Test
    fun anUnchangedThreadWritesNothing() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        val first = persist(filing, logOf(pain), key)
        val writes = store.db.writes

        val second = persist(filing, logOf(pain, rev = 2), key, now = CHAT_NOW + 60_000)

        assertEquals(writes, store.db.writes)
        assertEquals(first.links, second.links)
        assertTrue(second.firsts.isEmpty())
    }

    /** File, FILED, found, answers changed: rewritten under its id, day, moment and title. */
    @Test
    fun aChangedAnswerRewritesTheRecordUnderItsIdDayMomentAndTitle() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        val first = persist(filing, logOf(pain), key)
        val before = store.db.pains().single()

        val changed = pain.revalued(SymptomStep.PAIN_NRS, "7")
        val second = persist(filing, logOf(changed, rev = 2), key, now = CHAT_NOW + 3_600_000, russian = true)

        val after = store.db.pains().single()
        assertEquals(before.id, after.id)
        assertEquals(before.occurredAt, after.occurredAt)
        assertEquals(before.day, after.day)
        // Written in the language showing at the first write; a rewrite does not translate it.
        assertEquals("Pain", after.title)
        assertEquals(7, decodePainPayload(after.measurementPayloadJson).nrs)
        val link = linkOf(second)
        assertEquals(linkOf(first).at, link.at)
        assertEquals(rowSig(after), link.wsig)
        assertEquals(answersSig(changed), link.sig)
        assertTrue(second.firsts.isEmpty())
    }

    /** A record corrected on its own page is never written over, and its pencils go off. */
    @Test
    fun aRecordEditedOnItsPageTurnsChangedAndIsNeverOverwritten() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        persist(filing, logOf(pain), key)
        val row = store.db.pains().single()
        store.db.journal[row.id] = row.copy(note = "worse after the stairs")

        val result = persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "9"), rev = 2), key)

        assertEquals(LinkState.CHANGED, linkOf(result).state)
        val kept = store.db.pains().single()
        assertEquals("worse after the stairs", kept.note)
        assertEquals(6, decodePainPayload(kept.measurementPayloadJson).nrs)
        // And it stays changed: no later act writes it either.
        persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "3"), rev = 3), key)
        assertEquals(6, decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs)
    }

    /** A record deleted elsewhere is never put back by a write, only by the person's Save again. */
    @Test
    fun deletedElsewhereIsNeverReinsertedButSaveAgainIs() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        val first = linkOf(persist(filing, logOf(pain), key))
        store.db.journal.clear()

        val gone = persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "8"), rev = 2), key)
        assertEquals(LinkState.GONE, linkOf(gone).state)
        assertTrue(store.db.pains().isEmpty())
        persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "8"), rev = 3), key)
        assertTrue(store.db.pains().isEmpty())

        val log = logOf(pain.revalued(SymptomStep.PAIN_NRS, "8"), rev = 3)
        val again = runBlocking { checkNotNull(filing.saveAgain(0, "", filingContext(log, key))) }
        val row = store.db.pains().single()
        assertEquals(first.at, row.occurredAt)
        assertEquals(LinkState.FILED, linkOf(again).state)
        assertEquals(rowSig(row), linkOf(again).wsig)
        assertEquals(1, again.firsts.size)
    }

    /** File, UNDONE: a message alone files nothing; a changed answer files it again, at its moment. */
    @Test
    fun anUndoneRecordIsFiledAgainOnlyWhenAnAnswerChanges() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        val first = linkOf(persist(filing, logOf(pain), key))
        val undone = runBlocking { checkNotNull(filing.undo(0, "", filingContext(logOf(pain), key))) }
        assertEquals(LinkState.UNDONE, linkOf(undone).state)
        assertTrue(store.db.pains().isEmpty())

        val worded = pain.copy(report = pain.report.wrote("still there"))
        persist(filing, logOf(worded, rev = 2), key)
        assertTrue(store.db.pains().isEmpty())

        val changed = worded.revalued(SymptomStep.PAIN_NRS, "4")
        val again = persist(filing, logOf(changed, rev = 3), key)
        assertEquals(LinkState.FILED, linkOf(again).state)
        assertEquals(first.at, store.db.pains().single().occurredAt)
        assertEquals(1, again.firsts.size)
    }

    /** The mood door refuses inside its cooldown: REFUSED, said once, and filed when an answer changes later. */
    @Test
    fun aRefusedRecordIsFiledAgainOnlyWhenAnAnswerChanges() {
        val store = MemoryChatStore()
        val key = chatKey()
        val earlier = store.db.put(moodRow(CHAT_NOW - 3_600_000, score = 4))
        val mood = valuedThread(0, Symptom.MOOD, SymptomStep.PAIN_NRS to "2")
        val plans = TestPlans({ thread -> mapOf("" to moodWant(thread)) })
        val filing = ChatFiling(store, key, plans)

        val refused = persist(filing, logOf(mood), key)
        assertEquals(LinkState.REFUSED, linkOf(refused).state)
        assertEquals(listOf(Refusal(0, "", earlier.occurredAt, 4)), refused.refused)
        assertEquals(1, store.db.journal.size)

        // The mood in the way deleted on its page: the same answers still file nothing, and are not
        // refused again either.
        store.db.journal.remove(earlier.id)
        val again = persist(filing, logOf(mood.copy(report = mood.report.wrote("better now")), rev = 2), key)
        assertTrue(again.refused.isEmpty())
        assertTrue(store.db.journal.isEmpty())

        val later = persist(filing, logOf(mood.revalued(SymptomStep.PAIN_NRS, "3"), rev = 3), key)
        assertEquals(LinkState.FILED, linkOf(later).state)
        assertEquals(linkOf(refused).at, store.db.journal.values.single().occurredAt)
        assertEquals(listOf(0 to linkOf(later)), later.firsts)
    }

    /** Keep: an answer is being changed, and what is filed stays exactly as it stands. */
    @Test
    fun keepLeavesTheRecordAsItStands() {
        val store = MemoryChatStore()
        val key = chatKey()
        var keep = false
        val plans = TestPlans({ thread -> mapOf("" to if (keep) RecordWant.Keep else painWant(thread)) })
        val filing = ChatFiling(store, key, plans)
        persist(filing, logOf(pain), key)
        val writes = store.db.writes

        keep = true
        val kept = persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "2"), rev = 2), key)

        assertEquals(writes, store.db.writes)
        assertEquals(6, decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs)
        assertEquals(LinkState.FILED, linkOf(kept).state)
    }

    /** Absent, FILED, made by the chat: deleted as its page deletes it, and the link goes. */
    @Test
    fun aRecordNoLongerWantedIsTakenBack() {
        val store = MemoryChatStore()
        val key = chatKey()
        var wanted = true
        val plans = TestPlans({ thread -> if (wanted) mapOf("" to painWant(thread)) else emptyMap() })
        val filing = ChatFiling(store, key, plans)
        val row = persist(filing, logOf(pain), key).let { store.db.pains().single() }

        wanted = false
        val result = persist(filing, logOf(pain, rev = 2), key)

        assertTrue(store.db.pains().isEmpty())
        assertEquals(listOf(row.id), store.db.deleted)
        assertEquals(emptyList<FiledLink>(), result.links[0])
    }

    /** Absent, anything but FILED: the link is dropped, and nothing is deleted. */
    @Test
    fun aLinkThatIsNotFiledIsDroppedAndNothingDeleted() {
        val store = MemoryChatStore()
        val key = chatKey()
        var wanted = true
        val plans = TestPlans({ thread -> if (wanted) mapOf("" to painWant(thread)) else emptyMap() })
        val filing = ChatFiling(store, key, plans)
        persist(filing, logOf(pain), key)
        val row = store.db.pains().single()
        store.db.journal[row.id] = row.copy(note = "edited")
        persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "5"), rev = 2), key)

        wanted = false
        val result = persist(filing, logOf(pain, rev = 3), key)

        assertEquals(emptyList<FiledLink>(), result.links[0])
        assertEquals("edited", store.db.pains().single().note)
        assertTrue(store.db.deleted.isEmpty())
    }

    /** A course and its marks of today: the course first, so the mark is built with its id; taken back together. */
    @Test
    fun aCourseIsFiledBeforeItsMarksAndTakenBackWithThem() {
        val store = MemoryChatStore()
        val key = chatKey()
        var wanted = true
        val course = valuedThread(0, Symptom.COURSE, SymptomStep.PAIN_NRS to "1")
        val plans = TestPlans({ _ ->
            if (!wanted) {
                emptyMap()
            } else {
                mapOf(
                    "" to RecordWant.File(RecordDraft.Course { createdAt -> sertraline(createdAt) }),
                    "dose:540" to RecordWant.File(
                        RecordDraft.Journal(JournalDoor.DOSE, wanted = null, slot = "540", course = CHAT_NOW) {
                                shell,
                                ids,
                            ->
                            doseRow(shell, checkNotNull(ids("")), "540")
                        },
                    ),
                )
            }
        })
        val filing = ChatFiling(store, key, plans)
        val result = persist(filing, logOf(course), key)

        val made = store.db.courses.values.single()
        val dose = store.db.journal.values.single()
        assertEquals(CHAT_NOW, made.createdAt)
        assertEquals(made.id to "540", medSlotOf(dose))
        val courseLink = linkOf(result)
        assertEquals(LINK_COURSE to "2026-10-05", courseLink.kind to courseLink.start)
        assertEquals(courseSig(made), courseLink.wsig)
        assertEquals("540" to CHAT_NOW, linkOf(result, role = "dose:540").let { it.slot to it.course })
        assertEquals(2, result.firsts.size)

        wanted = false
        persist(filing, logOf(course, rev = 2), key)
        assertTrue(store.db.courses.isEmpty())
        assertTrue(store.db.journal.isEmpty())
    }

    /**
     * An update of a row already there keeps what puts it back and supersedes the link that filed
     * it; taken back with its thread, the row is put back and that link is filed again.
     */
    @Test
    fun anUpdateIsPutBackWhenItsThreadGoesAndTheLinkItSupersededIsFiledAgain() {
        val store = MemoryChatStore()
        val key = chatKey()
        val first = valuedThread(0, Symptom.MOOD, SymptomStep.PAIN_NRS to "4")
        val second = valuedThread(1, Symptom.MOOD, SymptomStep.PAIN_NRS to "2")
        val plans = TestPlans({ thread ->
            if (thread.id == 0) {
                mapOf("" to moodWant(thread))
            } else {
                val at = CHAT_NOW
                mapOf(
                    "" to
                        RecordWant.File(
                            RecordDraft.Update(KIND_MOOD, at, change = {
                                it.copy(moodScore = 2, note = "Low")
                            }),
                        ),
                )
            }
        })
        val filing = ChatFiling(store, key, plans)
        persist(filing, logOf(first), key)
        val original = store.db.journal.values.single()

        val updated = persist(filing, logOf(first, second, rev = 2), key, now = CHAT_NOW + 60_000)
        val changed = store.db.journal.values.single()
        assertEquals(original.id, changed.id)
        assertEquals(2 to "Low", changed.moodScore to changed.note)
        assertEquals(LinkState.SUPERSEDED, linkOf(updated, thread = 0).state)
        val update = linkOf(updated, thread = 1)
        assertFalse(update.made)
        assertEquals(rowPrior(original), update.prior)
        assertEquals(original.occurredAt, update.at)

        // The second thread rewound away: the mood is as the first thread filed it, and so is its link.
        val back = persist(filing, logOf(first, rev = 3), key, now = CHAT_NOW + 120_000)
        val restored = store.db.journal.values.single()
        assertEquals(original, restored)
        assertEquals(LinkState.FILED, linkOf(back, thread = 0).state)
        assertEquals(rowSig(original), linkOf(back, thread = 0).wsig)
        assertNull(back.links[1])
    }

    /** An update with no row on its moment is refused, and changes nothing. */
    @Test
    fun anUpdateWithNoRowToChangeIsRefused() {
        val store = MemoryChatStore()
        val key = chatKey()
        val plans = TestPlans({ _ ->
            mapOf(
                "" to RecordWant.File(
                    RecordDraft.Update(KIND_MOOD, CHAT_NOW - 1, change = {
                        it.copy(moodScore = 1)
                    }),
                ),
            )
        })
        val result = persist(ChatFiling(store, key, plans), logOf(valuedThread(0, Symptom.MOOD)), key)

        assertEquals(LinkState.REFUSED, linkOf(result).state)
        assertEquals(listOf(Refusal(0, "", null, null)), result.refused)
        assertTrue(store.db.journal.isEmpty())
    }

    /** A moment two rows share and none or both of them the chat's is never deleted or rewritten. */
    @Test
    fun anAmbiguousMomentIsNeverDeletedOrRewritten() {
        val store = MemoryChatStore()
        val key = chatKey()
        var wanted = true
        val plans = TestPlans({ thread -> if (wanted) mapOf("" to painWant(thread)) else emptyMap() })
        val filing = ChatFiling(store, key, plans)
        persist(filing, logOf(pain), key)
        val mine = store.db.pains().single()
        // The very same row again on the same moment, as a restore of an older copy could put it.
        store.db.put(mine.copy(id = 0))

        val rewrite = persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "9"), rev = 2), key)
        assertEquals(LinkState.GONE, linkOf(rewrite).state)
        wanted = false
        persist(filing, logOf(pain, rev = 3), key)

        assertEquals(2, store.db.pains().size)
        assertTrue(store.db.pains().all { decodePainPayload(it.measurementPayloadJson).nrs == 6 })
        assertTrue(store.db.deleted.isEmpty())
    }

    /** The app's language changed between two writes: every title differs, and nothing is rewritten. */
    @Test
    fun aLanguageSwitchRewritesNothing() {
        val store = MemoryChatStore()
        val key = chatKey()
        val fever = ChatThread(0, newChat().pick(Symptom.FEVER).say(SymptomStep.WITH, SymptomOption.CHILLS))
        val filing = ChatFiling(store, key)
        persist(filing, logOf(fever), key)
        val row = store.db.journal.values.single()
        assertEquals("Fever", row.title)
        val writes = store.db.writes

        persist(filing, logOf(fever, rev = 2), key, now = CHAT_NOW + 60_000, russian = true)

        assertEquals(writes, store.db.writes)
        assertEquals(row, store.db.journal.values.single())
    }

    /** A chat's back-dated rows stand at 12:00:00.001 and .002, and the page's own at .000 is left alone. */
    @Test
    fun twoBackDatedPainsOnOneDayStandAtDotOneAndDotTwo() {
        val store = MemoryChatStore()
        val key = chatKey()
        val day = LocalDate.of(2001, 3, 10)
        val noon = middayOf(day, CHAT_ZONE)
        // The pain page's own row of that day, at its midday to the millisecond.
        val hip = PainReport(regions = listOfNotNull(RegionSide.parse("HIP:L")), nrs = 3)
        val page = store.db.put(painEntry("Pain", hip, day, CHAT_TODAY, noon).copy(occurredAt = noon))
        val second = valuedThread(1, Symptom.PAIN, SymptomStep.PAIN_NRS to "5")
        val log = logOf(pain, second, past = true)

        val result = runBlocking {
            checkNotNull(
                ChatFiling(store, key, painPlans()).persist(log, filingContext(log, key, day = day, past = true)),
            )
        }

        assertEquals(listOf(noon, noon + 1, noon + 2), store.db.pains().map { it.occurredAt }.sorted())
        assertEquals(listOf(noon + 1, noon + 2), listOf(linkOf(result, 0).at, linkOf(result, 1).at))
        assertTrue(store.db.pains().all { it.day == day.toString() })
        assertEquals(page, store.db.journal.getValue(page.id))
    }

    private fun sertraline(createdAt: Long) = Medication(
        name = "Sertraline",
        dose = "50 mg",
        times = "540",
        startDay = "2026-10-05",
        createdAt = createdAt,
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
}
