package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the chat's three pages lead — back, and the move once a deletion's flash has stood — and what
 * their saved state brings back after the process was killed: the decisions [ChatPages] asks of
 * [pageAfterBack], [deletionMove] and the savers' [openedText] and [pastSourceText].
 */
class ChatPagesTest {
    /**
     * Back from a past chat the history opened goes to the history, back from the history to the page the
     * pages were opened on; any other back leaves the chat's pages, which the system's back does itself.
     */
    @Test
    fun backLeadsToThePageTheHistoryWasOpenedFrom() {
        assertEquals(SymptomPage.HISTORY, pageAfterBack(SymptomPage.PAST, fromHistory = true, startsLive = true))
        assertEquals(SymptomPage.HISTORY, pageAfterBack(SymptomPage.PAST, fromHistory = true, startsLive = false))
        assertEquals(SymptomPage.CHAT, pageAfterBack(SymptomPage.HISTORY, fromHistory = false, startsLive = true))
        assertEquals(SymptomPage.PAST, pageAfterBack(SymptomPage.HISTORY, fromHistory = true, startsLive = false))
        assertNull(pageAfterBack(SymptomPage.CHAT, fromHistory = false, startsLive = true))
        assertNull(pageAfterBack(SymptomPage.CHAT, fromHistory = true, startsLive = true))
        // The past chat a row of the day list opened is the first page: back from it leaves.
        assertNull(pageAfterBack(SymptomPage.PAST, fromHistory = false, startsLive = false))
    }

    /** On the page it was made from, a deletion gives way as that page came: to the history, out, or afresh. */
    @Test
    fun aDeletionGivesWayToWhereThePageCameFrom() {
        fun move(from: SymptomPage, wasLive: Boolean, fromHistory: Boolean, entered: Boolean) =
            deletionMove(from, from, wasLive, fromHistory, entered)

        assertEquals(DeletionMove.HISTORY, move(SymptomPage.PAST, wasLive = false, fromHistory = true, entered = true))
        assertEquals(DeletionMove.HISTORY, move(SymptomPage.PAST, wasLive = true, fromHistory = true, entered = false))
        assertEquals(DeletionMove.DONE, move(SymptomPage.PAST, wasLive = false, fromHistory = false, entered = true))
        assertEquals(DeletionMove.DONE, move(SymptomPage.CHAT, wasLive = true, fromHistory = false, entered = true))
        assertEquals(DeletionMove.AFRESH, move(SymptomPage.CHAT, wasLive = true, fromHistory = false, entered = false))
        // The history opened a past chat and came back to the chat page before the deletion of the live chat.
        assertEquals(DeletionMove.AFRESH, move(SymptomPage.CHAT, wasLive = true, fromHistory = true, entered = false))
    }

    /**
     * Gone elsewhere within the flash — to the history, say, in the 700 ms after Delete — the person stays
     * where they went: only a new chat takes the deleted live one's place, for when the chat page is back.
     */
    @Test
    fun aPageLeftWithinTheFlashStaysWhereThePersonWent() {
        val liveLeft = deletionMove(SymptomPage.HISTORY, SymptomPage.CHAT, true, false, entered = true)
        assertEquals(DeletionMove.AFRESH, liveLeft)
        val pastLeft = deletionMove(SymptomPage.HISTORY, SymptomPage.PAST, false, true, entered = false)
        assertEquals(DeletionMove.STAY, pastLeft)
        val backToChat = deletionMove(SymptomPage.CHAT, SymptomPage.PAST, false, true, entered = true)
        assertEquals(DeletionMove.STAY, backToChat)
        // The live chat deleted from its past page, then the history: the chat page starts afresh.
        assertEquals(DeletionMove.AFRESH, deletionMove(SymptomPage.HISTORY, SymptomPage.PAST, true, true, false))
    }

    /** What the page opened on comes back from its saved state as it was: the chat, and where in it. */
    @Test
    fun whatThePageOpenedOnSurvivesItsSavedState() {
        val opened = listOf(
            Opened.New,
            Opened.Live("7d1c"),
            Opened.Live("7d1c", focusAt = 1_763_200_000_000),
            Opened.Past(PastSource.Chat("7d1c")),
            Opened.Past(PastSource.Chat("7d1c", focusAt = 42)),
            Opened.Past(PastSource.Row(1234)),
        )
        opened.forEach { assertEquals(it, openedOf(openedText(it))) }
        assertNull(openedOf(openedText(null)))
    }

    /** A past chat's source comes back as it was; a saved text that is no source is none, not a wrong chat. */
    @Test
    fun aPastChatsSourceSurvivesItsSavedStateAndNothingElseIsOne() {
        val sources = listOf(PastSource.Chat("k"), PastSource.Chat("k", focusAt = 5), PastSource.Row(9))
        sources.forEach { assertEquals(it, pastSourceOf(pastSourceText(it))) }
        assertNull(pastSourceOf(pastSourceText(null)))
        listOf("", "X|k", "R|", "R|nine", "C").forEach { assertNull(it, pastSourceOf(it)) }
        assertNull(openedOf("garbage"))
        assertNull(openedOf("PR|nine"))
    }
}
