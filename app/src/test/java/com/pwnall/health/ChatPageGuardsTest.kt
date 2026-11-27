package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the chat's page decides before it writes, in its pure parts — an answer taken once ([awaits]),
 * a write that failed said ([filingFailed]) — and, pinned in the source as [ChatGestureWiringTest]'s
 * calls are, where the page asks them and what only a device shows: the pages' saved state kept while
 * another page stands, and a file picked before the page was ready.
 */
class ChatPageGuardsTest {
    private val setting = chatSetting()

    /** The text of function [name] of the chat's screen, from its name to the brace that closes it at the margin. */
    private fun function(name: String): String {
        val text = File("src/main/java/com/pwnall/health/SymptomChatScreen.kt").readText()
        assertTrue("fun $name(", "fun $name(" in text)
        return text.substringAfter("fun $name(").substringBefore("\n}\n")
    }

    /** A tap as the page takes it: only while the thread it was drawn for still waits for it, then settled. */
    private fun ChatLog.tap(thread: Int, step: SymptomStep, option: SymptomOption): ChatLog =
        if (awaits(thread, step)) answeredLast(setting) { it.tapped(step, option, null) }.settle(setting) else this

    private fun ChatLog.turnsOf(step: SymptomStep): Int = threads.sumOf { thread ->
        thread.report.chat?.turns.orEmpty().count { it is ChatTurn.Asked && it.step == step }
    }

    /**
     * An answer tapped twice is one answer: the second tap — "Turn on" again while the reminders are
     * turned on, a chip and its neighbour before the page has drawn the first — finds its question
     * taken and writes nothing. Without the guard it would be a second turn for the one question.
     */
    @Test
    fun anAnswerTappedTwiceIsTakenOnce() {
        val asked = ChatLog().wrote("my chest is tight").settle(setting)
        val thread = asked.threads.single().id
        val step = SymptomStep.CHEST_NOW
        assertEquals(step, (chatMove(asked, setting) as ChatMove.Ask).step)
        assertTrue(asked.awaits(thread, step))

        val once = asked.tap(thread, step, SymptomOption.NO)
        assertEquals(1, once.turnsOf(step))
        assertFalse(once.awaits(thread, step))
        assertEquals(once, once.tap(thread, step, SymptomOption.NO))

        val unguarded = once.answeredLast(setting) { it.tapped(step, SymptomOption.NO, null) }
        assertEquals(2, unguarded.turnsOf(step))
    }

    /**
     * An answer lands only in the open thread whose question its chips were drawn for: not in a thread
     * the chat started after it, not in a thread that is over, not in a chat that is.
     */
    @Test
    fun anAnswerLandsOnlyInTheOpenThreadThatAskedIt() {
        val fever = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle(setting)
        val thread = fever.threads.single()
        val step = (chatMove(fever, setting) as ChatMove.Ask).step
        assertTrue(fever.awaits(thread.id, step))

        val moved = fever.copy(threads = fever.threads + ChatThread(fever.next, newChat()), next = fever.next + 1)
        assertFalse(moved.awaits(thread.id, step))
        assertEquals(moved, moved.tap(thread.id, step, SymptomOption.NO))

        val over = thread.copy(report = thread.report.copy(chat = thread.report.chat?.copy(ended = true)))
        assertFalse(fever.copy(threads = listOf(over)).awaits(thread.id, step))
        assertFalse(fever.endedByPerson().awaits(thread.id, step))
        assertFalse(ChatLog().awaits(null, step))
    }

    /** Pinned in the source: every answer of a reply goes through the guard, and none is committed past it. */
    @Test
    fun everyAnswerOfAReplyGoesThroughTheGuard() {
        val screen = function("SymptomConversation")
        val once = "if (log.awaits(askedThread, step)) commit(log.answeredLast(setting, change = change))"
        assertTrue(screen, once in screen)
        assertFalse(screen, "log.answeredLast(setting)" in screen)
        listOf(
            "onTap = { step, option -> answerOnce(step) {",
            "onKeep = { step, options ->\n            answerOnce(step) {",
            "onSeverity = { severity -> answerOnce(SymptomStep.SEVERITY) {",
            "onTick = { step, option ->\n            if (log.awaits(askedThread, step)) {",
            "answerOnce(step) { it.valued(step, value).answered(step) }",
            "} else if (log.awaits(askedThread, step)) {",
            "onText = { step, text -> if (log.awaits(askedThread, step)) typed(step, text) else send(text) }",
        ).forEach { call -> assertTrue(call, call in screen) }
    }

    private fun filed(link: FiledLink, newer: ChatLog? = null, failed: Boolean = false): Result<FilingResult?> =
        Result.success(FilingResult(mapOf(0 to listOf(link)), emptyList(), emptyList(), newer, failed))

    /**
     * A write that failed is said: an act that threw, one the filer kept the chat alone after, or one
     * that left a record standing not built. A chat deleted meanwhile writes nothing on purpose and says
     * nothing; nor does the database's newer copy handed back, or a record Undo took back, whose card
     * says so already.
     */
    @Test
    fun aWriteThatFailedIsSaidAndADeletedChatIsNot() {
        val link = FiledLink(KIND_PAIN, at = CHAT_NOW, title = "Pain")
        assertTrue(filingFailed(Result.failure(IllegalStateException("the act threw"))))
        assertTrue(filingFailed(filed(link, failed = true)))
        assertTrue(filingFailed(filed(link.copy(err = true))))
        assertFalse(filingFailed(filed(link)))
        assertFalse(filingFailed(Result.success(null)))
        assertFalse(filingFailed(filed(link.copy(err = true), newer = ChatLog(), failed = true)))
        assertFalse(filingFailed(filed(link.copy(err = true, state = LinkState.UNDONE))))
    }

    /**
     * A record that stands while its last change could not be built says so on its card, rather than
     * go on saying "Here is what is saved" over the old answers. Every other state keeps the line it
     * had, and a record filed and built has none.
     */
    @Test
    fun aCardSaysWhenItsRecordsLastChangeWasNotSaved() {
        assertEquals(R.string.symptom_chat_record_change_not_saved, cardStateRes(LinkState.FILED, unbuilt = true))
        assertEquals(null, cardStateRes(LinkState.FILED, unbuilt = false))
        val lines = mapOf(
            LinkState.UNDONE to R.string.symptom_chat_record_not_saved,
            LinkState.REFUSED to R.string.symptom_chat_record_not_saved,
            LinkState.GONE to R.string.work_flash_deleted,
            LinkState.CHANGED to R.string.symptom_chat_record_changed,
            LinkState.SUPERSEDED to R.string.symptom_chat_record_superseded,
        )
        lines.forEach { (state, line) ->
            assertEquals(state.name, line, cardStateRes(state, unbuilt = false))
            assertEquals(state.name, line, cardStateRes(state, unbuilt = true))
        }
    }

    /**
     * Over the real filer: an act that throws keeps the chat alone and is said — a first write, which
     * leaves no record and no link to mark, as much as a rewrite — until a later act files the record.
     */
    @Test
    fun anActThatThrewIsSaidUntilALaterOneFilesItsRecord() {
        val store = MemoryChatStore()
        val key = chatKey()
        val plans = TestPlans({ thread ->
            if (thread.topic == Symptom.PAIN) mapOf("" to painWant(thread)) else emptyMap()
        })
        val filing = ChatFiling(store, key, plans)
        val knee = valuedThread(0, Symptom.PAIN, SymptomStep.PAIN_NRS to "4")
        fun nrs() = decodePainPayload(store.db.pains().single().measurementPayloadJson).nrs

        store.db.failing = "writeJournal"
        assertTrue(filingFailed(Result.success(persist(filing, logOf(knee), key))))
        assertTrue(store.db.pains().isEmpty())
        assertFalse(filingFailed(Result.success(persist(filing, logOf(knee, rev = 2), key, now = CHAT_NOW + 1_000))))
        assertEquals(4, nrs())

        store.db.failing = "writeJournal"
        val changed = logOf(knee.revalued(SymptomStep.PAIN_NRS, "7"), rev = 3)
        assertTrue(filingFailed(Result.success(persist(filing, changed, key, now = CHAT_NOW + 2_000))))
        assertEquals(4, nrs())
        assertFalse(filingFailed(Result.success(persist(filing, changed.copy(rev = 4), key, now = CHAT_NOW + 3_000))))
        assertEquals(7, nrs())
    }

    /**
     * Over the real filer: a record whose builder throws at its first write leaves no row and no link to
     * mark, and is said all the same until a later act builds it. A builder with nothing to write on
     * purpose — the mark of a course that is not written — gives no row either, and nothing is said of it.
     */
    @Test
    fun aFirstWriteNotBuiltIsSaidAndOneWithNothingToWriteIsNot() {
        val store = MemoryChatStore()
        val key = chatKey()
        var thrown = true
        var nothing = false
        val plans = TestPlans({ thread ->
            val draft = RecordDraft.Journal(JournalDoor.PAIN, wanted = null) { shell, _ ->
                check(!thrown) { "a builder that throws" }
                painRow(shell, 4).takeUnless { nothing }
            }
            if (thread.topic == Symptom.PAIN) mapOf("" to RecordWant.File(draft)) else emptyMap()
        })
        val filing = ChatFiling(store, key, plans)
        val knee = valuedThread(0, Symptom.PAIN, SymptomStep.PAIN_NRS to "4")

        val failed = persist(filing, logOf(knee), key)
        assertTrue(failed.links[0].orEmpty().isEmpty())
        assertTrue(filingFailed(Result.success(failed)))

        thrown = false
        nothing = true
        val declined = persist(filing, logOf(knee, rev = 2), key, now = CHAT_NOW + 1_000)
        assertTrue(declined.links[0].orEmpty().isEmpty())
        assertFalse(filingFailed(Result.success(declined)))

        nothing = false
        assertFalse(filingFailed(Result.success(persist(filing, logOf(knee, rev = 3), key, now = CHAT_NOW + 2_000))))
        assertEquals(1, store.db.pains().size)
    }

    /**
     * A chat opened again takes as said the first write of every record it filed, and of none a door
     * refused or Undo took back: either filed once an answer changed is a first write the centre flash
     * says (else a record the person had taken back would come back to the journal in silence).
     */
    @Test
    fun aChatOpenedAgainSaysARecordTakenBackOrRefusedWhenItIsFiled() {
        fun link(role: String, state: LinkState) = FiledLink(KIND_MED, role, CHAT_NOW, "Ibuprofen", state = state)
        val dose = ChatThread(
            0,
            newChat(),
            filed = listOf(
                link("", LinkState.FILED),
                link("pain", LinkState.UNDONE),
                link("other", LinkState.REFUSED),
            ),
        )
        val pain = ChatThread(
            1,
            newChat(),
            filed = listOf(link("", LinkState.GONE), link("a", LinkState.CHANGED), link("b", LinkState.SUPERSEDED)),
        )
        val log = ChatLog(threads = listOf(dose, pain), next = 2)
        assertEquals(setOf("0/", "1/", "1/a", "1/b"), flashedAtOpen(log))
        assertEquals(emptySet<String>(), flashedAtOpen(ChatLog()))
    }

    /** Pinned in the source: the page's writes and a card's Undo and Save again say when they failed. */
    @Test
    fun thePagesWritesSayWhenTheyFailed() {
        val screen = function("SymptomConversation")
        assertTrue(screen, "R.string.quick_log_not_saved).takeIf { filingFailed(outcome) }" in screen)
        assertTrue(screen, "R.string.quick_log_not_saved else R.string.symptom_chat_record_not_undone" in screen)
        assertTrue(screen, "pages.onFailure(resources.getString(failed).takeIf { outcome.isFailure })" in screen)
        assertTrue("PwnFailureFlash(failure) { failure = null }" in function("ChatPages"))
        // The seal, a deletion and a card's Add are said too: thrown, each would end the process. A page
        // that goes meanwhile is no failure to say (runCatchingCancellable).
        assertTrue(screen, "val outcome = runCatchingCancellable { filing.seal() }" in screen)
        assertTrue(screen, "runCatchingCancellable { filing.deleteChat() }" in screen)
        assertTrue(
            screen,
            Regex("""val outcome = runCatchingCancellable \{\s+filing\.addCardRow\(""").containsMatchIn(screen),
        )
        val past = function("SymptomPastChat")
        assertTrue(past, Regex("""runCatchingCancellable \{\s+when \(source\)""").containsMatchIn(past))
        assertTrue(past, "onFailed(notDeleted)" in past)
    }

    /**
     * A live chat carried on past midnight reads the symptoms and pains of the new day, under which its
     * records are filed; a chat of an earlier day reads them through its own day.
     */
    @Test
    fun aLiveChatPastMidnightReadsTheNewDay() {
        val afterMidnight = CHAT_TODAY.plusDays(1).atStartOfDay(CHAT_ZONE).toInstant().toEpochMilli() + 60_000L

        assertEquals(CHAT_TODAY.plusDays(1), chatEnvThrough(CHAT_TODAY, past = false, now = afterMidnight, CHAT_ZONE))
        assertEquals(CHAT_TODAY, chatEnvThrough(CHAT_TODAY, past = true, now = afterMidnight, CHAT_ZONE))
        assertEquals(CHAT_TODAY, chatEnvThrough(CHAT_TODAY, past = false, now = CHAT_NOW, CHAT_ZONE))
    }

    /**
     * The chat and the history keep their saved state while another page stands in their place — the
     * words not yet sent, a card's changes, the sitting, where the history was scrolled to — and nothing
     * of a chat deleted, or given way to another, comes back with the page; the history left by back
     * opens at its top again.
     */
    @Test
    fun theChatAndTheHistoryKeepTheirStateWhileAnotherPageStands() {
        val pages = function("ChatPages")
        assertTrue(pages, "holder.SaveableStateProvider(chatStateKey(chatGeneration))" in pages)
        assertTrue(pages, "holder.SaveableStateProvider(HISTORY_STATE)" in pages)
        val afresh = Regex("""forgetChat\(\)\s+liveKey = (other|row\.key)\s+generation\+\+""")
        assertEquals(pages, 2, afresh.findAll(pages).count())
        assertTrue(pages, Regex("""liveKey = null\s+forgetChat\(\)""").containsMatchIn(pages))
        assertTrue(pages, "holder.removeState(HISTORY_STATE)" in pages)
        // When the chat gave way to its history goes with its saved state: a process killed meanwhile
        // comes back to a new sitting after the gap, as a page read anew from its row does.
        val handedOver = Regex("""onHistory = \{\s+awayAt = System\.currentTimeMillis\(\)\s+pages\.onHistory\(\)""")
        assertTrue(handedOver.containsMatchIn(function("SymptomConversation")))
        // A past chat keeps where it was read to while the history stands in its place; one the history
        // opened and back left, or one deleted, keeps nothing.
        assertTrue(pages, "holder.SaveableStateProvider(pastStateKey(source))" in pages)
        val leftByBack = Regex("""holder\.removeState\(pastStateKey\(past\)\)\s+page = SymptomPage\.HISTORY""")
        assertTrue(pages, leftByBack.containsMatchIn(pages))
        val deleted = Regex("""holder\.removeState\(pastStateKey\(source\)\)\s+afterDelete\(""")
        assertTrue(pages, deleted.containsMatchIn(pages))
    }

    /**
     * The turn shown is worked out again when the sitting changes: otherwise, back after a quarter of an
     * hour, the page would go on showing the turn of the sitting before, the chest not asked about again.
     */
    @Test
    fun theTurnShownIsWorkedOutAgainForANewSitting() {
        val screen = function("SymptomConversation")
        val keys = "LaunchedEffect(told, ready, setting.env, setting.snapshot, setting.sittingFrom, reading)"
        assertTrue(screen, keys in screen)
    }

    /**
     * A file picked while the page was gone comes back before the chat's surroundings are read: it is
     * kept in the log, and nothing is settled or filed against an empty [ChatEnv] — the open's own
     * write takes it, in a chat of files alone as well.
     */
    @Test
    fun aFilePickedBeforeThePageIsReadyWaitsForTheOpensWrite() {
        val screen = function("SymptomConversation")
        val picked = "if (ready) commit(log.copy(attachments = next)) else log = log.copy(attachments = next)"
        assertTrue(screen, picked in screen)
        val opened = "if (ready && (log.threads.isNotEmpty() || log.attachments.isNotEmpty())) persist(log)"
        assertTrue(screen, opened in screen)
        // Nor does the page stopped or left before it is ready write the chat against one.
        assertTrue(screen, "if (ready && log.threads.lastOrNull()?.draft != null) persist(log)" in screen)
        // A reading that comes back for a page left before it was ready is settled and filed with the
        // chat's surroundings read for it: skipped, a reading the turn did not hold for would be lost.
        val read = "at.copy(env = readEnv(at.past), snapshot = at.snapshot ?: db.chatSnapshot(chatDay, profile))"
        assertTrue(screen, read in screen)
        assertTrue(screen, "{ sent -> filingContext(sent).copy(env = settle.env) }" in screen)
        assertFalse(screen, "::filingContext" in screen)
    }
}
