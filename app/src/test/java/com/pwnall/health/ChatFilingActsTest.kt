package com.pwnall.health

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acts around the reconcile table ([ChatFilingTest]), over the real doors and an in-memory
 * database: a builder or a whole act that fails, a restore that renumbers every row, an older copy
 * of the chat, its deletion, writes that race, and the rows a symptom thread and a chat's words file.
 */
class ChatFilingActsTest {
    private val pain = valuedThread(0, Symptom.PAIN, SymptomStep.PAIN_NRS to "6")

    private fun painPlans(wanted: Long? = null) = TestPlans({ thread ->
        if (thread.topic == Symptom.PAIN) mapOf("" to painWant(thread, wanted)) else emptyMap()
    })

    /** A record whose builder throws is skipped and retried; the chat and the other records are saved. */
    @Test
    fun aThrowingWantStillSavesTheTranscript() {
        val store = MemoryChatStore()
        val key = chatKey()
        var broken = true
        val plans = TestPlans({ thread ->
            if (thread.id != 0) {
                emptyMap()
            } else {
                mapOf(
                    "" to painWant(thread),
                    "second" to RecordWant.File(
                        RecordDraft.Journal(JournalDoor.PAIN, wanted = CHAT_NOW + 500) { shell, _ ->
                            check(!broken) { "a builder that throws" }
                            painRow(shell, 2)
                        },
                    ),
                )
            }
        })
        val filing = ChatFiling(store, key, plans)
        val result = persist(filing, logOf(pain), key)

        assertEquals(1, store.db.pains().size)
        assertNull(result.links[0].orEmpty().firstOrNull { it.role == "second" })
        assertNotNull(store.db.chats[key])

        broken = false
        val retried = persist(filing, logOf(pain, valuedThread(1, Symptom.OTHER), rev = 2), key)
        assertEquals(2, store.db.pains().size)
        assertEquals(LinkState.FILED, linkOf(retried, role = "second").state)
    }

    /** A rewrite whose builder throws marks its link; the next act rebuilds the thread though it is not last. */
    @Test
    fun aRewriteThatThrowsMarksItsLinkAndIsTriedAgain() {
        val store = MemoryChatStore()
        val key = chatKey()
        var broken = false
        val plans = TestPlans({ thread ->
            if (thread.topic != Symptom.PAIN) {
                emptyMap()
            } else {
                val nrs = thread.report.values[SymptomStep.PAIN_NRS]?.toIntOrNull() ?: 1
                mapOf(
                    "" to RecordWant.File(
                        RecordDraft.Journal(JournalDoor.PAIN, wanted = null) { shell, _ ->
                            check(!broken) { "a builder that throws" }
                            painRow(shell, nrs)
                        },
                    ),
                )
            }
        })
        val filing = ChatFiling(store, key, plans)
        persist(filing, logOf(pain), key)
        broken = true
        val changed = pain.revalued(SymptomStep.PAIN_NRS, "8")
        val failed = persist(filing, logOf(changed, rev = 2), key)
        assertTrue(linkOf(failed).err)
        assertEquals(6, decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs)

        broken = false
        val later = valuedThread(1, Symptom.OTHER)
        val fixed = persist(filing, logOf(changed, later, rev = 3), key)
        assertFalse(linkOf(fixed).err)
        assertEquals(8, decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs)
    }

    /**
     * A record a failed act left marked as not built loses the mark once an act finds it standing while
     * its thread only keeps it — an answer of its being changed. Were the mark to stay on it, every act
     * after the one that failed would say "Not saved" again.
     */
    @Test
    fun aRecordKeptAsItStandsLosesTheMarkOfAFailedAct() {
        val store = MemoryChatStore()
        val key = chatKey()
        var keep = false
        val plans = TestPlans({ thread -> mapOf("" to if (keep) RecordWant.Keep else painWant(thread)) })
        val filing = ChatFiling(store, key, plans)
        persist(filing, logOf(pain), key)

        store.db.failing = "writeJournal"
        val changed = pain.revalued(SymptomStep.PAIN_NRS, "8")
        assertTrue(linkOf(persist(filing, logOf(changed, rev = 2), key, now = CHAT_NOW + 1_000)).err)

        keep = true
        val kept = linkOf(persist(filing, logOf(changed, rev = 3), key, now = CHAT_NOW + 2_000))
        assertEquals(LinkState.FILED to false, kept.state to kept.err)
        assertFalse(stored(store, key).threads.single().filed.single().err)
    }

    /** A restore renumbers every row; links are moments, and the next rewrite finds its row under its new id. */
    @Test
    fun afterARestoreLinksResolveByMomentAndFingerprint() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        persist(filing, logOf(pain), key)
        val before = store.db.pains().single()
        store.db.put(moodRow(CHAT_NOW - 99, score = 3))
        store.db.renumber()
        val renumbered = store.db.pains().single()
        assertNotEquals(before.id, renumbered.id)

        persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "3"), rev = 2), key)

        val after = store.db.pains().single()
        assertEquals(renumbered.id, after.id)
        assertEquals(3, decodePainPayload(after.measurementPayloadJson).nrs)
    }

    /** An older copy of the chat — a page restored from before the last write — writes nothing, gets the newer. */
    @Test
    fun theStaleRevGuardHandsTheStoredLogBack() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        persist(filing, logOf(pain, rev = 5), key)
        val chat = store.db.chats.getValue(key)
        val writes = store.db.writes

        val stale = persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "1"), rev = 4), key)

        assertEquals(stored(store, key), stale.newer)
        assertEquals(writes, store.db.writes)
        assertEquals(chat, store.db.chats.getValue(key))
        // Equal revs pass: a rotation sends the write that was in flight again.
        persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "1"), rev = 5), key)
        assertEquals(1, decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs)
    }

    /** Deleting a chat leaves its records, each with its own delete, and every later write writes nothing. */
    @Test
    fun deleteChatStopsEveryLaterWriteAndLeavesTheRecords() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        persist(filing, logOf(pain), key)

        runBlocking { filing.deleteChat() }

        assertNull(store.db.chats[key])
        assertEquals(1, store.db.pains().size)
        val log = logOf(pain.revalued(SymptomStep.PAIN_NRS, "2"), rev = 2)
        assertNull(runBlocking { filing.persist(log, filingContext(log, key)) })
        assertNull(runBlocking { ChatFiling(store, key, painPlans()).persist(log, filingContext(log, key)) })
        assertNull(runBlocking { filing.undo(0, "", filingContext(log, key)) })
        assertNull(store.db.chats[key])
        assertEquals(6, decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs)
    }

    /**
     * A deletion that failed leaves the chat as it was and says so by throwing. Its later writes are
     * written: were the chat left marked deleted, every one of them would write nothing without a word.
     */
    @Test
    fun aDeletionThatFailedLeavesTheChatWritten() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        persist(filing, logOf(pain), key)
        store.db.failing = "deleteChat"

        assertTrue(runCatching { runBlocking { filing.deleteChat() } }.isFailure)

        assertNotNull(store.db.chats[key])
        persist(filing, logOf(pain.revalued(SymptomStep.PAIN_NRS, "2"), rev = 2), key)
        assertEquals(2, stored(store, key).rev)
        assertEquals(2, decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs)
    }

    /** A chat whose every thread went (the first topic rewound with no words) is deleted, and its records go back. */
    @Test
    fun aChatWithNoThreadLeftIsDeletedAndItsRecordsTakenBack() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        persist(filing, logOf(pain), key)

        val result = persist(filing, ChatLog(next = 1, rev = 2), key)

        assertNull(store.db.chats[key])
        assertTrue(store.db.pains().isEmpty())
        assertTrue(result.links.isEmpty())
    }

    /** Ten writes a moment apart, from one page or two, make one chat and one record. */
    @Test
    fun tenConcurrentPersistsMakeOneChatAndOneRecord() = runBlocking {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        (1..10).map { rev ->
            async(Dispatchers.Default) {
                val log = logOf(pain.copy(report = pain.report.wrote("message $rev")), rev = rev)
                filing.persist(log, filingContext(log, key, now = CHAT_NOW + rev))
            }
        }.awaitAll()

        assertEquals(1, store.db.chats.size)
        assertEquals(1, store.db.pains().size)
        assertEquals(10, store.acts)
    }

    /** A rotation leaves the old composition's filer behind; the new one shares the chat's lock and its links. */
    @Test
    fun twoFilingsOnOneKeyMakeOneRecord() = runBlocking {
        val store = MemoryChatStore()
        val key = chatKey()
        val log = logOf(pain)
        listOf(ChatFiling(store, key, painPlans()), ChatFiling(store, key, painPlans())).map { filing ->
            async(Dispatchers.Default) { filing.persist(log, filingContext(log, key)) }
        }.awaitAll()

        assertEquals(1, store.db.pains().size)
        assertEquals(1, stored(store, key).threads.single().filed.size)
    }

    /** A process killed mid-act left nothing; the words are kept, and the next open files the record. */
    @Test
    fun aKilledActFilesNothingAndTheNextOpenFilesIt() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key, painPlans())
        store.db.failing = "saveChat"
        val worded = pain.copy(report = pain.report.wrote("it is my knee"))

        val failed = persist(filing, logOf(worded), key)

        assertTrue(store.db.pains().isEmpty())
        assertEquals(emptyList<FiledLink>(), failed.links[0])
        assertEquals(worded.report.chat?.words(), stored(store, key).threads.single().report.chat?.words())

        val opened = persist(filing, logOf(worded, rev = 2), key)
        assertEquals(1, store.db.pains().size)
        assertEquals(LinkState.FILED, linkOf(opened).state)
    }

    /** A symptom thread is written through on every change once its topic is chosen. */
    @Test
    fun aSymptomThreadIsWrittenThroughToOneRow() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key)
        val chosen = newChat().pick(Symptom.FEVER)
        persist(filing, logOf(ChatThread(0, chosen)), key)
        val first = store.db.journal.values.single()
        assertEquals(key, chatKeyOf(first))
        assertEquals(CHAT_NOW, first.occurredAt)

        val answered = chosen.say(SymptomStep.WITH, SymptomOption.CHILLS)
        persist(filing, logOf(ChatThread(0, answered), rev = 2), key, now = CHAT_NOW + 1_000)
        val second = store.db.journal.values.single()
        assertEquals(first.id to first.occurredAt, second.id to second.occurredAt)
        assertEquals(
            listOf(SymptomOption.CHILLS),
            decodeSymptomPayload(second.measurementPayloadJson).answers[SymptomStep.WITH],
        )

        // A message that names another topic changes only the hidden tag, and the row follows it.
        val worded = answered.write("and I feel dizzy")
        persist(filing, logOf(ChatThread(0, worded), rev = 3), key, now = CHAT_NOW + 2_000)
        val third = decodeSymptomPayload(store.db.journal.values.single().measurementPayloadJson)
        assertEquals(listOf(Symptom.DIZZINESS), third.symptomTag?.mentioned)
        assertNull(third.chat)
    }

    /** A thread the chat started itself files nothing until the person answers a question of its own. */
    @Test
    fun aRoutedSymptomThreadFilesNothingUntilItsOwnAnswer() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key)
        val routed = SymptomReport(
            symptom = Symptom.CHEST_PAIN,
            answers = mapOf(SymptomStep.CHEST_NOW to listOf(SymptomOption.NO)),
            chat = SymptomChat(
                listOf(
                    ChatTurn.Asked(SymptomStep.SYMPTOM, routed = true),
                    ChatTurn.Asked(SymptomStep.CHEST_NOW, routed = true),
                ),
            ),
        )
        persist(filing, logOf(ChatThread(0, routed, from = "WORDS")), key)
        assertTrue(store.db.journal.isEmpty())

        val own = routed.copy(
            answers = routed.answers + (SymptomStep.WITH to listOf(SymptomOption.NONE)),
        ).answered(SymptomStep.WITH)
        persist(filing, logOf(ChatThread(0, own, from = "WORDS"), rev = 2), key)
        val row = decodeSymptomPayload(store.db.journal.values.single().measurementPayloadJson)
        assertEquals(Symptom.CHEST_PAIN, row.symptom)
        assertEquals(listOf(SymptomOption.NO), row.answers[SymptomStep.CHEST_NOW])
    }

    /**
     * A crisis told before any topic is kept in a row of its own; an emergency answer is let
     * go from that row once a later thread was seeded with it, and kept by that thread's own row.
     */
    @Test
    fun theWordsRowIsFiledForACrisisBeforeAnyTopicAndTakenBackWhenSeeded() {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key)
        val crisis = newChat().write("I took an overdose").say(SymptomStep.CRISIS_NOW, SymptomOption.CRISIS_UNSAFE)
        val result = persist(filing, logOf(ChatThread(0, crisis)), key)
        val row = store.db.journal.values.single()
        val told = decodeSymptomPayload(row.measurementPayloadJson)
        assertEquals(KIND_SYMPTOM, row.kind)
        assertNull(told.symptom)
        assertEquals(listOf(SymptomOption.CRISIS_UNSAFE), told.answers[SymptomStep.CRISIS_NOW])
        assertEquals(key, chatKeyOf(row))
        assertEquals(WORDS_ROLE, result.links.getValue(0).single().role)

        // The chest: answered "no" in the words' thread, then carried into the chest pain thread routed after it.
        val chestKey = chatKey()
        val chestFiling = ChatFiling(store, chestKey)
        val words = newChat().write("my chest hurts").say(SymptomStep.CHEST_NOW, SymptomOption.NO)
        persist(chestFiling, logOf(ChatThread(0, words)), chestKey)
        assertEquals(2, store.db.journal.size)
        val seeded = SymptomReport(
            symptom = Symptom.CHEST_PAIN,
            answers = mapOf(SymptomStep.CHEST_NOW to listOf(SymptomOption.NO)),
            chat = SymptomChat(
                listOf(
                    ChatTurn.Asked(SymptomStep.SYMPTOM, routed = true),
                    ChatTurn.Asked(SymptomStep.CHEST_NOW, routed = true),
                ),
            ),
        )
        val afterSeed = persist(
            chestFiling,
            logOf(ChatThread(0, words), ChatThread(1, seeded, from = "WORDS"), rev = 2),
            chestKey,
        )
        assertEquals(1, store.db.journal.size)
        assertEquals(emptyList<FiledLink>(), afterSeed.links[0])
        // The crisis row of the first chat is untouched by the second.
        assertEquals(row, store.db.journal.values.single())
    }

    /** Undo puts a changed mood back as it was, and the card's link reads "Not saved". */
    @Test
    fun undoOfAnUpdatePutsTheRowBack() {
        val store = MemoryChatStore()
        val key = chatKey()
        val original = store.db.put(moodRow(CHAT_NOW - 600_000, score = 4))
        val plans = TestPlans({ _ ->
            mapOf(
                "" to
                    RecordWant.File(
                        RecordDraft.Update(KIND_MOOD, original.occurredAt, change = {
                            it.copy(moodScore = 1)
                        }),
                    ),
            )
        })
        val filing = ChatFiling(store, key, plans)
        val log = logOf(valuedThread(0, Symptom.MOOD, SymptomStep.PAIN_NRS to "1"))
        persist(filing, log, key)
        assertEquals(1, store.db.journal.getValue(original.id).moodScore)

        val undone = runBlocking { checkNotNull(filing.undo(0, "", filingContext(log, key))) }

        assertEquals(original, store.db.journal.getValue(original.id))
        assertEquals(LinkState.UNDONE, linkOf(undone).state)
        assertNull(linkOf(undone).prior)
    }

    /** A sealed chat keeps no way back: every prior goes. */
    @Test
    fun sealingDropsEveryPrior() {
        val store = MemoryChatStore()
        val key = chatKey()
        val original = store.db.put(moodRow(CHAT_NOW - 600_000, score = 4))
        val plans = TestPlans({ _ ->
            mapOf(
                "" to
                    RecordWant.File(
                        RecordDraft.Update(KIND_MOOD, original.occurredAt, change = {
                            it.copy(moodScore = 1)
                        }),
                    ),
            )
        })
        val filing = ChatFiling(store, key, plans)
        persist(filing, logOf(valuedThread(0, Symptom.MOOD)), key)
        assertNotNull(stored(store, key).threads.single().filed.single().prior)

        runBlocking { filing.seal() }

        assertNull(stored(store, key).threads.single().filed.single().prior)
        // Undo of a change with nothing left to put it back from does nothing at all: the row keeps the
        // change and the card still says it is saved.
        val log = logOf(valuedThread(0, Symptom.MOOD))
        val undone = runBlocking { checkNotNull(filing.undo(0, "", filingContext(log, key))) }
        assertEquals(LinkState.FILED, linkOf(undone).state)
        assertEquals(1, store.db.journal.getValue(original.id).moodScore)
    }
}
