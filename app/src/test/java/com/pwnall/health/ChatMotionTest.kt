package com.pwnall.health

import android.view.WindowManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live chat's motion and what a screen reader hears, in its pure parts: how
 * long the dots stand, what the live row shows before the responder's turn is in, the reply's item,
 * where the list opens, when the record's flash may stand, and how the end is kept in view as the
 * keyboard comes and goes. What only a device can show — the frames, TalkBack's speech — is checked on
 * a device.
 */
class ChatMotionTest {
    private val setting = chatSetting()

    /** Under touch exploration the dots stand only the live region's lead — before a line to call as well. */
    @Test
    fun theDotsStandTheLiveRegionsLeadUnderTouchExploration() {
        listOf(true, false).forEach { on ->
            listOf(true, false).forEach { urgent ->
                assertEquals(LIVE_REGION_LEAD_MS, liveHoldMs(400, on, urgent, touchExploration = true))
            }
        }
        // More than one of the platform's 100 ms batches of accessibility events, and short of any typing hold.
        assertTrue(LIVE_REGION_LEAD_MS in 101L until chatTypingMs(0, animationsOn = false, urgent = false))
        // Without a screen reader the typing hold, unchanged: none before a line to call, 400 still, by length.
        listOf(0, 50, 200, 2000).forEach { chars ->
            listOf(true, false).forEach { on ->
                listOf(true, false).forEach { urgent ->
                    val typing = chatTypingMs(chars, on, urgent)
                    assertEquals(typing, liveHoldMs(chars, on, urgent, touchExploration = false))
                }
            }
        }
        assertEquals(0L, liveHoldMs(100, animationsOn = true, urgent = true, touchExploration = false))
    }

    /**
     * Never dots for "not worked out yet": a change that adds no turn shows the script's turn at rest; a
     * turn not put up yet brings the dots, except a line to call, which comes at once — unless a screen
     * reader needs the dots' lead; the turn put up for this very log stands, arriving if it was new.
     */
    @Test
    fun theLiveRowShowsTheScriptAtRestAndDotsOnlyForATurnNotPutUpYet() {
        val log = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle()
        val items = chatTranscript(log.told(), setting, live = true)
        val scripted = (items.last() as ChatItem.Bot).turn
        val told = log.told()
        assertEquals(LiveShown(scripted, fresh = false, dots = false), liveShown(items, null, told, false, false))
        assertEquals(LiveShown(null, fresh = true, dots = true), liveShown(items, null, told, true, false))
        // Put up for this log: it stands, arriving if it was new; put up for another log, it is not this one's.
        val shown = ShownTurn(told, scripted, fresh = true)
        assertEquals(LiveShown(scripted, fresh = true, dots = false), liveShown(items, shown, told, true, false))
        val stale = ShownTurn(ChatLog(), scripted, fresh = true)
        assertEquals(LiveShown(scripted, fresh = false, dots = false), liveShown(items, stale, told, false, false))

        // Words of suicide: where help is comes at once, without the dots — but after their lead under TalkBack.
        val crisis = ChatLog().wrote("I want to die").settle().told()
        val crisisItems = chatTranscript(crisis, setting, live = true)
        val support = (crisisItems.last() as ChatItem.Bot).turn
        assertTrue(support.urgent())
        assertEquals(LiveShown(support, fresh = true, dots = false), liveShown(crisisItems, null, crisis, true, false))
        assertEquals(LiveShown(null, fresh = true, dots = true), liveShown(crisisItems, null, crisis, true, true))
        // Before the chat is ready there is no transcript, and nothing to show.
        assertEquals(LiveShown(null, fresh = false, dots = false), liveShown(emptyList(), null, crisis, false, false))
    }

    /**
     * Words typed as the answer while the model reads them stand under their question as the person's,
     * with the dots after them: the question stays as it was put up, never the dots
     * alone where the words would go missing; a message read shows the dots alone, its bubble already in.
     */
    @Test
    fun wordsTypedAsAnAnswerStandUnderTheirQuestionWhileRead() {
        val log = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle()
        val told = log.told()
        val items = chatTranscript(told, setting, live = true)
        val scripted = (items.last() as ChatItem.Bot).turn
        val typed = LiveShown(scripted, fresh = false, dots = true, typed = "38.5")
        assertEquals(typed, liveShown(items, null, told, false, false, reading = true, typed = "38.5"))
        val shown = ShownTurn(told, scripted, fresh = true)
        assertEquals(typed, liveShown(items, shown, told, true, false, reading = true, typed = "38.5"))
        val message = liveShown(items, shown, told, false, false, reading = true)
        assertEquals(LiveShown(null, fresh = true, dots = true), message)
    }

    /** The reply's item is named by its thread and question: asked again after a message, it keeps its key. */
    @Test
    fun aRepliesKeyIsItsThreadAndQuestionAndThePromptCountsThreads() {
        assertEquals("reply:prompt:0", replyKey(ChatLog(), setting))
        val fever = ChatLog().choseTopic(TopicChoice(Symptom.FEVER), setting).settle()
        val ask = chatMove(fever, setting) as ChatMove.Ask
        val key = "reply:${ask.thread}:${ask.step.name}"
        assertEquals(key, replyKey(fever, setting))
        // A message during the question: the same question is asked again, in the same item.
        val wrote = fever.wrote("ok").settle()
        assertEquals(ask.step, (chatMove(wrote, setting) as ChatMove.Ask).step)
        assertEquals(key, replyKey(wrote, setting))
        // Answered, the next question is an item of its own.
        val answered = fever.tick(SymptomOption.NONE)
        assertNotEquals(key, replyKey(answered, setting))
        // The prompt after the first thread, counted by it; once the chat is over, no reply at all.
        val closed = ChatLog(threads = listOf(ChatThread(0, driveChat(Symptom.FEVER).second)), next = 1).settle()
        assertEquals(ChatMove.Prompt, chatMove(closed, setting))
        assertEquals("reply:prompt:1", replyKey(closed, setting))
        assertNull(replyKey(closed.endedByPerson().settle(), setting))
    }

    /** A reply item is put in the list only when it draws something: the map is an item of its own. */
    @Test
    fun aReplyItemIsDrawnOnlyWhenItHoldsSomething() {
        assertFalse(replyDrawn(ChatReply.End))
        assertTrue(replyDrawn(ChatReply.Severity))
        val map = ChatReply.BodyMap(SymptomStep.PAIN_AREA, null, head = false, headChips = emptyList())
        assertFalse(replyDrawn(map))
        assertTrue(replyDrawn(map.copy(exit = true)))
        val typed = ChatReply.TextAsk(SymptomStep.WITH, R.string.symptom_chat_hint, 40, emptyList())
        assertFalse(replyDrawn(typed))
        assertTrue(replyDrawn(typed.copy(exit = true)))
        assertTrue(replyDrawn(typed.copy(suggestions = listOf(ValueChip("x", ChatText(R.string.symptom_chat_hint))))))
    }

    /** A chat carried on opens at its end; one opened from a row of the day list, at that row's thread. */
    @Test
    fun theLiveChatOpensAtItsEndOrAtTheThreadOfTheRowOpened() {
        val first = driveChat(Symptom.FEVER).second
        val link = FiledLink(KIND_SYMPTOM, "", CHAT_NOW, "Fever")
        val log = ChatLog(threads = listOf(ChatThread(0, first, filed = listOf(link))), next = 1).settle()
            .choseTopic(TopicChoice(Symptom.NAUSEA), setting).settle()
        val items = chatTranscript(log.told(), setting, live = true)
        assertEquals(items.size, liveFocusRow(log, items, null))
        assertEquals(items.size, liveFocusRow(log, items, CHAT_NOW + 1))
        // The stamp stands first, so the thread's first row is one further down the list.
        val firstOfThread = items.indexOfFirst {
            (it as? ChatItem.Bot)?.thread == 0 || (it as? ChatItem.Person)?.thread == 0
        }
        assertEquals(firstOfThread + 1, liveFocusRow(log, items, CHAT_NOW))
        assertTrue(liveFocusRow(log, items, CHAT_NOW) < items.size)
    }

    /**
     * The record's centre flash never stands over a line to call — the turn on screen or the next — nor
     * once any answer about suicide in the chat was other than "no", nor after an emergency.
     */
    @Test
    fun theRecordFlashNeverStandsOverALineToCallOrAfterACrisisAnswer() {
        val pain = ChatLog().choseTopic(TopicChoice(Symptom.PAIN), setting).settle().calmToTheEnd()
        assertEquals(ChatMove.Prompt, chatMove(pain, setting))
        assertTrue(recordFlashAllowed(pain.told(), setting, chatTurn(pain, setting)))
        // The turn on screen is where help is: no flash over it.
        val support = BotTurn(listOf(BotPiece.Line(ChatLine.CRISIS_SUPPORT)), ChatReply.End)
        assertFalse(recordFlashAllowed(pain.told(), setting, support))
        // The turn coming next is: words of suicide written at the prompt, not yet answered.
        val next = pain.wrote("I want to die").settle()
        assertTrue(chatTurn(next, setting).urgent())
        assertFalse(recordFlashAllowed(next.told(), setting, null))
        // Answered "no such thoughts", the chat is calm again; any other answer, never again in it.
        val no = next.answeredLast(setting) { it.tapped(SymptomStep.CRISIS_NOW, SymptomOption.NO_SUCH_THOUGHTS, null) }
            .settle()
        assertTrue(recordFlashAllowed(no.told(), setting, null))
        val safe = next.answeredLast(setting) { it.tapped(SymptomStep.CRISIS_NOW, SymptomOption.SAFE_NOW, null) }
            .settle()
        val later = safe.choseTopic(TopicChoice(Symptom.PAIN), setting).settle().calmToTheEnd()
        assertFalse(recordFlashAllowed(later.told(), setting, null))
        // An emergency stopped the chat: nothing flashes after it.
        val blood = pain.choseTopic(TopicChoice(Symptom.VOMITING), setting).settle()
            .answeredLast(setting) { it.tapped(SymptomStep.BLOOD, SymptomOption.YES, null) }.settle()
        assertEquals(ChatEnd.STOP, blood.end)
        assertFalse(recordFlashAllowed(blood.told(), setting, null))
        // A close that says to get advice now, without waiting: no "added" over it.
        val now = ChatLog().wrote("my left knee hurts").settle().give("KNEE:L").calmToTheEnd(nrs = 9)
        assertTrue(ChatLine.CLOSE_NOW in chatTurn(now, setting).lines())
        assertFalse(recordFlashAllowed(now.told(), setting, null))
        val nowAlert =
            BotTurn(listOf(BotPiece.Alert(SymptomAlert.URGENT_NOW, R.string.pain_source_knee)), ChatReply.End)
        assertFalse(recordFlashAllowed(pain.told(), setting, nowAlert))
    }

    /**
     * The keyboard coming up or going down keeps the end where the person had it: the newest row's foot
     * just above the composer, or its top when it is taller than the list; nothing when they had scrolled up.
     */
    @Test
    fun theEndStaysInViewWhenTheKeyboardComesAndGoes() {
        // The keyboard opens: the list goes from 1000 px to 600; the newest row, 200 tall, stood at 780–980.
        assertEquals(380, endKeptInView(oldHeight = 1000, newHeight = 600, endOffset = 980, newestHeight = 200))
        // Taller than the new list: its top to the top.
        assertEquals(280, endKeptInView(oldHeight = 1000, newHeight = 600, endOffset = 980, newestHeight = 700))
        // Read from its top, a row taller than the list keeps its top in view: back down to it.
        assertEquals(-300, endKeptInView(oldHeight = 1000, newHeight = 600, endOffset = 700, newestHeight = 1000))
        // The person had scrolled up: the newest row stood below the old end. The list is left alone.
        assertNull(endKeptInView(oldHeight = 1000, newHeight = 600, endOffset = 1500, newestHeight = 200))
        // The keyboard goes down: the foot already stands above the end, so nothing moves.
        assertEquals(0, endKeptInView(oldHeight = 600, newHeight = 1000, endOffset = 580, newestHeight = 200))
        // No change of height, no change.
        assertNull(endKeptInView(oldHeight = 800, newHeight = 800, endOffset = 780, newestHeight = 100))
    }

    /**
     * The keyboard opening while the chat brings its end into view is not lost: the end is brought again
     * once that scroll is done, never while a finger holds the map, and a change under the person's own
     * scroll is theirs (lost, it would leave the newest words a keyboard's height under the composer).
     */
    @Test
    fun aViewportChangedUnderTheChatsOwnScrollBringsTheEndAgain() = runTest {
        val reveal = ChatReveal()
        var brought = 0
        reveal.bring({ false }) {
            brought++
            // The keyboard opens as the first scroll runs: noted for it, not kept in view there and then.
            if (brought == 1) assertFalse(reveal.viewportChanged(scrolling = true))
        }
        assertEquals(2, brought)
        // Nothing changed under it: once.
        brought = 0
        reveal.bring({ false }) { brought++ }
        assertEquals(1, brought)
        // A finger on the map by the time it is done: not again.
        brought = 0
        reveal.bring({ brought > 0 }) {
            brought++
            reveal.viewportChanged(scrolling = true)
        }
        assertEquals(1, brought)
        // The person's own scroll: their change; with nothing scrolling, the end is kept in view at once.
        assertFalse(reveal.viewportChanged(scrolling = true))
        assertTrue(reveal.viewportChanged(scrolling = false))
    }

    /** After a change the newest row's foot comes to the end of the list, never its top past the top. */
    @Test
    fun theTailIsBroughtToItsFootNeverPastItsTop() {
        assertEquals(150, tailDelta(end = 800, top = 750, bottom = 950))
        assertEquals(0, tailDelta(end = 800, top = 300, bottom = 700))
        assertEquals(400, tailDelta(end = 800, top = 400, bottom = 1600))
        assertEquals(0, tailDelta(end = 800, top = -100, bottom = 900))
    }

    /**
     * The end comes into view no further than the bot's newest words reach the top: a reply or a map
     * taller than the room under the question leaves the question in view (never the map's top to the
     * top, and the question above it out of view).
     */
    @Test
    fun theEndComesIntoViewNeverPastTheNewestWords() {
        // A short reply under the question: all the way to the end.
        assertEquals(150, revealDelta(end = 800, anchorTop = 400, bottom = 950))
        // A map taller than the room left: the question's top to the top, the map under it.
        assertEquals(400, revealDelta(end = 800, anchorTop = 400, bottom = 1600))
        // The map not laid out yet: as far as the question's top.
        assertEquals(400, revealDelta(end = 800, anchorTop = 400, bottom = null))
        // The question already at or past the top, or the end already in view: nothing moves.
        assertEquals(0, revealDelta(end = 800, anchorTop = -30, bottom = 1600))
        assertEquals(0, revealDelta(end = 800, anchorTop = 400, bottom = 700))
    }

    /**
     * A turn whose dots were up when the page was put away — a rotation, the process dying — comes back
     * at rest, never held again; within one page the dots are held once for a turn, however often the
     * chat reloads under them (never replayed, nor restarted on every reload).
     */
    @Test
    fun aTurnCaughtMidHoldComesBackAtRest() {
        // The dots for turn 7 were up, and this page did not fix their end: it came back from a restore.
        assertTrue(caughtMidHold(holding = 7, heldHere = null, count = 7))
        // This page fixed the end of turn 7's dots: they carry on to it.
        assertFalse(caughtMidHold(holding = 7, heldHere = 7, count = 7))
        // A new turn is held as usual.
        assertFalse(caughtMidHold(holding = 7, heldHere = 7, count = 8))
        assertFalse(caughtMidHold(holding = -1, heldHere = null, count = 0))
    }

    /**
     * The window fits the keyboard while the chat stands — again once the app is brought back — and
     * every screen after it pans its field above the keyboard as before (the mode put back is never the
     * window's own 0, which the platform never writes, so the chat's resize would stay for the session).
     */
    @Test
    fun theWindowFitsTheKeyboardWhileTheChatStandsAndPansAgainAfter() {
        val window = PlatformWindow()
        val mode = ChatKeyboardMode(window.attributes, window::setSoftInputMode)
        mode.start()
        assertEquals(RESIZE, window.moves)
        // Sent away and brought back: the system chooses pan again, and the focus back says resize again.
        window.shownAgain()
        mode.focused(false)
        assertEquals(PAN, window.moves)
        mode.focused(true)
        assertEquals(RESIZE, window.moves)
        mode.stop()
        assertEquals(PAN, window.moves)
        // The window's own 0 put back as it was found would have left every later screen on the chat's resize.
        val left = PlatformWindow().apply {
            setSoftInputMode(fittingKeyboard(0))
            setSoftInputMode(0)
        }
        assertEquals(RESIZE, left.moves)
    }

    /**
     * The mode put back is the window's own where it names how the keyboard moves it, else pan; never 0,
     * and never with the forward-navigation bit of a first screen.
     */
    @Test
    fun theModePutBackIsTheWindowsOwnOrPan() {
        val hidden = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        val nothing = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        listOf(PAN, RESIZE, nothing, PAN or hidden).forEach {
            assertEquals(it, keyboardAfterChat(it))
        }
        assertEquals(PAN, keyboardAfterChat(0))
        assertEquals(PAN or hidden, keyboardAfterChat(hidden))
        assertEquals(RESIZE, keyboardAfterChat(RESIZE or FORWARD))
        assertEquals(PAN, keyboardAfterChat(FORWARD))
        // The chat's own keeps the keyboard-state bits, the keyboard resizing the window.
        assertEquals(RESIZE or hidden, fittingKeyboard(PAN or hidden))
        assertEquals(RESIZE, fittingKeyboard(0))
        assertEquals(RESIZE or hidden, fittingKeyboard(FORWARD or hidden))
    }

    /**
     * The chat as the first screen after a start or a restore, its window's mode still holding the
     * forward-navigation bit: no mode the chat says carries it — on the focus regained it would come
     * back with resize before the view root reads it, the platform's sign to show the keyboard by
     * itself over a focused field.
     */
    @Test
    fun aFirstScreensForwardBitIsNeverSaidAgain() {
        val said = mutableListOf<Int>()
        val mode = ChatKeyboardMode(FORWARD, said::add)
        mode.start()
        mode.focused(false)
        mode.focused(true)
        mode.stop()
        assertEquals(listOf(RESIZE, RESIZE, PAN), said)
        assertTrue(said.all { (it and FORWARD) == 0 })
    }

    /**
     * A window's keyboard mode as the platform keeps it (android-36.1 sources): `Window.setSoftInputMode`
     * writes no mode of 0; the view root, handed a mode that names no movement, keeps the movement it had
     * (`ViewRootImpl.setLayoutParams`); and for an activity that declares no mode it chooses again each
     * time a view of the window is shown or hidden — pan, no view of the window scrolling
     * (`ViewRootImpl.performTraversals`).
     */
    private class PlatformWindow {
        var attributes = 0
            private set

        /** How the keyboard moves the window: what the view root tells the window manager. Pan at the first layout. */
        var moves = PAN
            private set

        fun setSoftInputMode(mode: Int) {
            if (mode != 0) attributes = mode
            val named = attributes and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
            if (named != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) moves = named
        }

        /** A view of the window shown or hidden, as the app sent away and brought back is. */
        fun shownAgain() {
            moves = PAN
        }
    }

    private companion object {
        const val PAN = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN

        // The platform's name for the chat's mode, deprecated in favour of the inset an edge-to-edge
        // window gets; the mode is what stops the pan.
        @Suppress("DEPRECATION")
        const val RESIZE = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        /** The bit the platform sets on a window navigated forward to, until its first focus. */
        const val FORWARD = WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION
    }
}
