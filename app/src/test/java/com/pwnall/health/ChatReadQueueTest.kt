package com.pwnall.health

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The reads on their way: a read belongs to
 * the process, not to the page that started it, so the page a rotation brings sees it — the dots
 * standing for it — and keeps it once, with its own log, in the order the words were sent; and what
 * each kind of words sent comes to once read.
 */
class ChatReadQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ChatReadQueue(scope)
    private val setting = chatSetting()

    @After
    fun close() {
        scope.cancel()
        MedicineClock.now = System::currentTimeMillis
        MedicineClock.zone = ZoneId::systemDefault
    }

    @Test
    fun aReadOutlivesThePageAndIsKeptOnce() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val read = chatRead { copy(safety = listOf(SymptomStep.CRISIS_NOW)) }
        val log = ChatLog().wrote("hmm")
        val place = checkNotNull(log.lastMessagePlace())
        val pending = queue.start(CHAT, SentWords.Message("hmm", place), holds = true, since = 1L) {
            gate.await()
            read
        }
        // A page of the chat — the one that sent it, or the one a rotation brings — sees it on its way.
        assertEquals(listOf(pending), queue.pending.value[CHAT])
        assertTrue(queue.pending.value[OTHER].isNullOrEmpty())
        gate.complete(Unit)
        assertEquals(read, pending.result.await())
        // Kept by one page alone: the first to take it.
        assertTrue(queue.take(CHAT, pending))
        assertFalse(queue.take(CHAT, pending))
        assertNull(queue.pending.value[CHAT])
        // What it comes to on that page's log: the reading where the message stands.
        val kept = checkNotNull(log.readDone(pending.sent, read, setting))
        assertEquals(read, messagesOf(kept.threads.single().report.chat).single().read)
    }

    @Test
    fun readsAreKeptInTheOrderTheyWereSent() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val first = queue.start(CHAT, SentWords.Note("one"), holds = false, since = 1L) {
            slow.await()
            null
        }
        val second = queue.start(CHAT, SentWords.Note("two"), holds = false, since = 2L) { null }
        second.result.await()
        assertEquals(listOf(first, second), queue.pending.value[CHAT])
        slow.complete(Unit)
        assertTrue(queue.take(CHAT, first))
        assertEquals(listOf(second), queue.pending.value[CHAT])
    }

    /** A read that fails inside comes to nothing, never to a crash: the words are taken as the rules read them. */
    @Test
    fun aFailedReadComesToNothing() = runBlocking {
        val pending = queue.start(CHAT, SentWords.Note("n"), holds = false, since = 1L) { error("a read that throws") }
        assertNull(pending.result.await())
    }

    /**
     * What each kind of words sent comes to once read: a message its reading, where it stands; words
     * typed as an answer taken — as the answer, or with a flag as a message; the note its reading,
     * while it is that note — and with no reading, the message and the note as they were, the typed
     * words taken as the rules read them.
     */
    @Test
    fun whatEachKindOfWordsComesTo() {
        val flag = chatRead { copy(safety = listOf(SymptomStep.BREATH_NOW)) }
        val message = ChatLog().wrote("hmm")
        val place = checkNotNull(message.lastMessagePlace())
        assertNull(message.readDone(SentWords.Message("hmm", place), null, setting))
        val chat = MedicineChat().start(Symptom.DOSE)
        val typed = SentWords.Typed("нурофен", SymptomStep.MED_NAME)
        val held = chat.log.copy(held = ChatHeld.Typed(SymptomStep.MED_NAME, "нурофен"))
        val answer = checkNotNull(held.readDone(typed, null, chat.setting)).settle(chat.setting)
        assertEquals("нурофен", answer.threads.last().report.values[SymptomStep.MED_NAME])
        assertNull(answer.held)
        val asMessage = checkNotNull(held.readDone(typed, flag, chat.setting)).settle(chat.setting)
        assertEquals(SymptomStep.BREATH_NOW, asMessage.asked(chat.setting))
        val ended = ChatLog(end = ChatEnd.ALL).noted("tired")
        assertEquals(flag, ended.readDone(SentWords.Note("tired"), flag, setting)?.noteRead)
        assertNull(ended.readDone(SentWords.Note("tired"), null, setting))
        assertNull(ended.noted("better").readDone(SentWords.Note("tired"), flag, setting))
    }

    /**
     * Words sent with a line or a space before them are kept trimmed, and read as they are kept: the
     * words a reading finds stand where the kept message has them, so the record they propose is
     * offered rather than dropped as words no longer there.
     */
    @Test
    fun aMessageSentWithASpaceBeforeItIsReadAsItIsKept() = runBlocking {
        val sent = "\n съел яблоко"
        val reader = scriptedReader(
            ScriptedEngine { text ->
                val at = text.indexOf("съел")
                val meal = ChatJournalEvent(
                    ChatJournalType.FOOD_LOG,
                    ChatJournalSpan(at, at + "съел".length, "съел"),
                    mapOf("subject" to "SELF", "assertion" to "AFFIRMED"),
                )
                readingOf().copy(journal = listOf(meal))
            },
        )
        val written = ChatLog().wrote(sent)
        val place = checkNotNull(written.lastMessagePlace())
        val read = reader.decide(sent, ChatReaderContext(), CHAT_TODAY, past = false, routes = true)
        val kept = checkNotNull(written.readDone(SentWords.Message(sent, place), read, setting))
        val message = messagesOf(kept.threads.single().report.chat).single()
        assertEquals("съел яблоко", message.text)
        val offered = journalProposals(message, setOf(ChatJournalType.FOOD_LOG))
        assertEquals(listOf(ChatJournalType.FOOD_LOG), offered.map { it.type })
        reader.close()
    }

    /**
     * A read past its second and the margin is no read, even when the run it waits on never ends — a
     * stuck native call no cancellation reaches: the turn is then the rules'. A
     * read done already is what it came to, however late a page asks for it.
     */
    @Test
    fun aReadPastItsSecondIsNoRead() = runBlocking {
        val stuck = queue.start(CHAT, SentWords.Note("n"), holds = true, since = 1_000L) {
            CompletableDeferred<Unit>().await()
            chatRead()
        }
        val deadline = 1_000L + ChatModelReader.READ_LIMIT_MILLIS + ChatReadQueue.DEADLINE_SLACK_MILLIS
        assertNull(withTimeout(5_000) { queue.resultOf(stuck, now = deadline) })
        val started = System.currentTimeMillis()
        val soon = queue.start(CHAT, SentWords.Note("m"), holds = true, since = started) { awaitCancellation() }
        assertNull(withTimeout(10_000) { queue.resultOf(soon) })
        assertTrue(System.currentTimeMillis() - started >= ChatModelReader.READ_LIMIT_MILLIS)
        val read = chatRead()
        val done = queue.start(CHAT, SentWords.Note("o"), holds = false, since = 1L) { read }
        done.result.await()
        assertEquals(read, queue.resultOf(done, now = Long.MAX_VALUE / 2))
    }

    /**
     * A page gone for good takes no read of its chat while a page of it stands — the one a return or
     * the saved state brings back takes it, with the log it shows — and takes it once none stands; a
     * read another took is not taken twice.
     */
    @Test
    fun aPageGoneForGoodTakesNothingWhileAPageStands() = runBlocking {
        val first = queue.start(CHAT, SentWords.Note("one"), holds = false, since = 1L) { null }
        first.result.await()
        queue.opened(CHAT)
        val gone = async { queue.takeAlone(CHAT, first) }
        delay(SETTLE_MILLIS)
        assertFalse(gone.isCompleted)
        assertTrue(queue.take(CHAT, first))
        assertFalse(withTimeout(5_000) { gone.await() })
        val second = queue.start(CHAT, SentWords.Note("two"), holds = false, since = 1L) { null }
        second.result.await()
        val waiting = async { queue.takeAlone(CHAT, second) }
        delay(SETTLE_MILLIS)
        assertFalse(waiting.isCompleted)
        queue.closed(CHAT)
        assertTrue(withTimeout(5_000) { waiting.await() })
        assertFalse(queue.take(CHAT, second))
        // Another chat's page holds nothing of this one's.
        queue.opened(OTHER)
        val third = queue.start(CHAT, SentWords.Note("three"), holds = false, since = 1L) { null }
        assertTrue(withTimeout(5_000) { queue.takeAlone(CHAT, third) })
        queue.closed(OTHER)
    }

    /**
     * What the chat holds for a reading is the chat's own, in its row and its rotation's state alike:
     * words typed are taken once — with their reading, or as typed when it
     * never comes — and never again, nor after the question was taken back; a message waiting for its
     * reading keeps its thread from being routed until the reading, or its going, lets it be.
     */
    @Test
    fun whatTheChatHoldsIsTakenOnce() {
        val chat = MedicineChat().start(Symptom.DOSE)
        val at = chat.setting
        val typed = SentWords.Typed("нурофен", SymptomStep.MED_NAME)
        val held = chat.log.copy(held = ChatHeld.Typed(SymptomStep.MED_NAME, "нурофен")).settle(at)
        assertEquals(held, decodeChatLog(encodeChatLog(held, pending = false)))
        assertEquals(held, CHAT_LOG_SAVER.restore(encodeChatLog(held, pending = true)))
        val taken = checkNotNull(held.readDone(typed, chatRead(), at)).settle(at)
        assertEquals("нурофен", taken.threads.last().report.values[SymptomStep.MED_NAME])
        assertNull(taken.readDone(typed, chatRead(), at))
        assertNull(taken.released(at))
        // The reading gone with the process: the words taken as typed, once.
        val unread = checkNotNull(held.released(at)).settle(at)
        assertEquals("нурофен", unread.threads.last().report.values[SymptomStep.MED_NAME])
        assertNull(unread.held)
        // The question taken back meanwhile: nothing to take.
        val name = held.threads.last().report.chat?.turns.orEmpty()
            .indexOfFirst { it is ChatTurn.Asked && it.step == SymptomStep.SYMPTOM }
        val rewound = held.rewoundLog(held.threads.last().id, name)
        assertNull(rewound.held)
        assertNull(rewound.readDone(typed, chatRead(), at))
        // A message waiting for its reading: not routed until it comes, or goes.
        val text = "выпила нурофен"
        val written = ChatLog().wrote("hmm").wrote(text)
        val place = checkNotNull(written.lastMessagePlace())
        val waiting = written.copy(held = ChatHeld.Message(place)).settle()
        assertNull(waiting.threads.single().topic)
        assertEquals(waiting, decodeChatLog(encodeChatLog(waiting, pending = false)))
        // The reading of the message before it, committed meanwhile, routes nothing either.
        val first = SentWords.Message("hmm", MessagePlace(place.thread, 0))
        val before = checkNotNull(waiting.readDone(first, chatRead(), setting))
        assertNull(before.settle().threads.single().topic)
        assertEquals(Symptom.DOSE, checkNotNull(waiting.released(setting)).settle().threads.single().topic)
        val read = checkNotNull(waiting.readDone(SentWords.Message(text, place), chatRead(), setting)).settle()
        assertEquals(Symptom.DOSE, read.threads.single().topic)
        assertNull(read.held)
    }

    /**
     * A page gone for good keeps what comes back for it in the chat as the database holds it, never in
     * its own copy: a page brought back that moved on
     * keeps its words, and the reading joins them; the gone page's copy would have lost them, and the
     * filer refuses it as older.
     */
    @Test
    fun aPageGoneForGoodKeepsAReadingInTheChatAsStored() = runBlocking {
        val store = MemoryChatStore()
        val key = chatKey()
        val filing = ChatFiling(store, key)
        val sent = ChatLog().wrote("hmm").settle()
        val place = checkNotNull(sent.lastMessagePlace())
        persist(filing, sent, key)
        val moved = sent.wrote("again").settle()
        persist(filing, moved, key)
        val flag = chatRead { copy(safety = listOf(SymptomStep.BREATH_NOW)) }
        val message = SentWords.Message("hmm", place)
        fun change(stored: ChatLog): ChatLog? = stored.readDone(message, flag, setting)?.let { settledLog(it, setting) }
        val result = filing.update(::change) { filingContext(it, key) }
        assertNotNull(result)
        val kept = messagesOf(stored(store, key).threads.single().report.chat)
        assertEquals(listOf("hmm", "again"), kept.map { it.text })
        assertEquals(listOf(flag, null), kept.map { it.read })
        val own = checkNotNull(sent.readDone(message, flag, setting)).settle()
        assertNotNull(persist(filing, own, key).newer)
        // Nothing to change, nothing written.
        assertNull(filing.update({ null }) { filingContext(it, key) })
    }

    private companion object {
        const val CHAT = "chat-a"
        const val OTHER = "chat-b"
        const val SETTLE_MILLIS = 200L
    }
}
