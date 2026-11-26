package com.pwnall.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat's gestures and the screen's calls where they are wired, pinned in the source: what they do
 * only a finger on a device shows, and with the helper it calls held on the JVM, a call gone missing
 * would leave every test green. What they write is held on the JVM elsewhere (the map's own question:
 * [PainChatFlowTest.aMapFoldingAwayAfterItsSendWritesNothing]).
 * Those a test can compose without the whole screen or a window — the composer's send, the map letting
 * go of the clock — are composed and touched in [ChatGestureTest].
 */
class ChatGestureWiringTest {
    /** The text of function [name] in [file], from its name to the brace that closes it at the margin. */
    private fun function(file: String, name: String): String {
        val text = File("src/main/java/com/pwnall/health/$file").readText()
        assertTrue("fun $name( in $file", "fun $name(" in text)
        return text.substringAfter("fun $name(").substringBefore("\n}\n")
    }

    /**
     * The sent map folds as every reply that folds away does, saying nothing and under a cover the finger
     * lands on: its painter takes a touch another took first, so a touch consumed around it would still
     * be painted. That the map is such a reply is held here; what such a reply does with a touch
     * and with a screen reader is [FoldingReplyTest]'s, which composes one and touches it.
     */
    @Test
    fun theMapFoldingAwayAfterItsSendIsAFoldingReply() {
        assertTrue("FoldingReply(" in function("ChatPainUi.kt", "PainMapItem"))
    }

    /**
     * The map's painting and its Send go through the guard of its own question ([ChatLog.mapPainted],
     * [ChatLog.mapSent]), for the map the screen holds — never written into the log straight (written
     * so, a map folding away after its Send would paint into the answer it had sent, and into the
     * chest's thread).
     */
    @Test
    fun theMapsPaintingAndSendGoThroughItsOwnQuestion() {
        val screen = function("SymptomChatScreen.kt", "SymptomConversation")
        val paint = Regex(
            """onPaint = \{ step, strokes, added, removed ->\s+""" +
                """log\.mapPainted\(mapFor, strokes, added, removed, setting\)""",
        )
        assertTrue(screen, paint.containsMatchIn(screen))
        assertTrue(screen, "onSend = { _ -> log.mapSent(mapFor, setting)?.let { commit(it) } }" in screen)
    }

    /**
     * Each change brings the end into view through the reveal that notes a viewport changed under its
     * own scroll, and the viewport's change asks it first (otherwise the keyboard opening during the
     * chat's own scroll leaves the newest words under the composer).
     */
    @Test
    fun theEndIsBroughtThroughTheRevealThatNotesTheKeyboard() {
        val scrolling = function("ChatScreenParts.kt", "ChatScrolling")
        assertTrue(scrolling, "reveal.bring({ holding() }) { listState.revealTail(animated, newest()) }" in scrolling)
        assertTrue(scrolling, "!reveal.viewportChanged(listState.isScrollInProgress)" in scrolling)
    }

    /**
     * The chat's keyboard mode is said again on each focus the window gets back, and the listener goes
     * with the chat (otherwise, back from the background, the open chat is on pan again).
     */
    @Test
    fun theKeyboardModeIsSaidAgainOnTheFocusBack() {
        val keyboard = function("SymptomChatScreen.kt", "KeyboardResizesChat")
        val listener = Regex("""OnWindowFocusChangeListener \{ focused -> mode\.focused\(focused\) \}""")
        assertTrue(keyboard, listener.containsMatchIn(keyboard))
        assertTrue(keyboard, "viewTreeObserver.addOnWindowFocusChangeListener(again)" in keyboard)
        assertTrue(keyboard, "viewTreeObserver.removeOnWindowFocusChangeListener(again)" in keyboard)
        assertTrue(keyboard, "mode.stop()" in keyboard)
    }

    /**
     * The record editor's chat offers no Open on a course's card — the Medicine tab cannot show over the
     * editor — and the card asks its host before it offers one (Open would do nothing there).
     */
    @Test
    fun theEditorsChatOffersNoOpenOnACourse() {
        val editor = function("EntryEditorScreen.kt", "EntryEditorScreen")
        assertTrue(editor, Regex("""SymptomChatScreen\([^)]*opensCourses = false""").containsMatchIn(editor))
        val card = function("ChatScreenParts.kt", "CardButtons")
        assertTrue(card, "cardOpens(kind, state, inputs.opensCourses)" in card)
    }

    /**
     * A past chat lists the files it was given, read from its own log and without the pencil and the
     * cross (the chat keeps a file and the grant to read it, and without the list no page would show the
     * file once the chat is over — it could not be opened, only deleted with the whole chat).
     */
    @Test
    fun aPastChatShowsTheFilesItWasGiven() {
        val past = function("SymptomChatScreen.kt", "SymptomPastChat")
        assertTrue(past, "val files = remember(past) { medicalAttachments(past.log.attachments) }" in past)
        assertTrue(past, "item { PastChatFiles(files) }" in past)
        val files = function("ChatScreenParts.kt", "PastChatFiles")
        assertTrue(files, "context.openAttachment(file.uri)" in files)
        assertFalse(files, "IconButton(" in files)
    }

    /**
     * The history draws only the rows in view, each under its chat's key, and tells the panel's pull to
     * close where the list stands (a column of every chat ever had would draw them all, and read each
     * chat's whole text to name it, before the page's first frame).
     */
    @Test
    fun theHistoryDrawsOnlyTheRowsInView() {
        val page = function("SymptomChatHistory.kt", "SymptomChatHistoryPage")
        assertTrue(page, "LazyColumn(" in page)
        assertTrue(page, "items(shown.orEmpty(), key = { it.key })" in page)
        assertTrue(page, "RegisterPanelScroll(listState)" in page)
        assertFalse(page, "forEach" in page)
    }
}
