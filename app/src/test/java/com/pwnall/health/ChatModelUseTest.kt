package com.pwnall.health

import ai.onnxruntime.OrtException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat with its model: a message read once, as it is
 * sent, its decisions kept with it and the model's version beside them; redrawn — on every draw, after
 * a reload, under a later model — from what was kept and never read again. With no model, its switch
 * off, the pipeline's smoke model refused as the chat's own reader refuses it, or a failure, the chat
 * is exactly as it is without one; the tiny fixture reads only when a test forces it on.
 */
class ChatModelUseTest {
    private val setting = chatSetting()

    @Test
    fun theFixtureForcedOnReadsAndNamesItsModel() = runBlocking {
        val reader = tinyReader()
        val manifest = ChatModelManifest.parse(File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText())
        val read = checkNotNull(ChatLog().readBy(reader, "мне плохо, болит колено", setting))
        assertEquals(manifest.model, read.model)
        assertTrue(read.routes)
        // Decisions, never probabilities: no number with a fraction anywhere in what is kept.
        assertFalse(encodeChatRead(read), Regex("""\d\.\d""").containsMatchIn(encodeChatRead(read)))
        reader.close()
    }

    /** No model, the switch off, the smoke model refused: nothing is kept, and the chat is the chat it was. */
    @Test
    fun withoutAModelTheChatIsAsItWas() = runBlocking {
        val none = ChatModelReader({ null }, mode = ChatModelMode.ON, threads = 1)
        val off =
            ChatModelReader(ChatModelTestFiles.fixtureFiles(), mode = ChatModelMode.OFF, allowSmoke = true, threads = 1)
        val smoke =
            ChatModelReader(ChatModelTestFiles.fixtureFiles(), mode = ChatModelMode.ON, allowSmoke = false, threads = 1)
        listOf(none, off, smoke).forEach { reader ->
            assertNull(ChatLog().readBy(reader, "hello", setting))
            listOf("болит колено", "мне плохо", "I took 20 paracetamol", "не могу дышать", "hmm").forEach { text ->
                assertEquals(text, ChatLog().wrote(text).settle(), ChatLog().sentWith(reader, text, setting))
            }
            reader.close()
        }
    }

    /** A model that ships and fails is the rules' turn with its reason kept: nothing it routes by. */
    @Test
    fun aFailedReadKeepsWhyAndRoutesNothing() = runBlocking {
        val engine = ScriptedEngine { error("a run that fails") }
        val failing = scriptedReader(engine)
        val read = checkNotNull(ChatLog().readBy(failing, "hmm", setting))
        assertEquals(ChatRead(why = ChatModelUnavailable.RUN_FAILED.code), read)
        assertFalse(read.routes)
        assertEquals(ChatLog().wrote("hmm").settle().asked(), ChatLog().wroteRead("hmm", read).asked())
        failing.close()
        // As a run the deadline stops ends: inside ONNX Runtime, with its exception.
        val slow = scriptedReader(
            ScriptedEngine {
                Thread.sleep(SLOW_MILLIS)
                throw OrtException("Exiting due to terminate flag being set to true.")
            },
        )
        assertTrue(slow.state() is ChatModelState.Ready)
        val late = slow.decide("hmm", ChatReaderContext(), CHAT_TODAY, past = false, routes = true, limitMillis = 50)
        assertEquals(ChatRead(why = ChatReadOutcome.TIMEOUT), late)
        slow.close()
    }

    /** In the shadow a reading is kept with the message and routes nothing. */
    @Test
    fun aShadowReadingIsKeptAndUsedForNothing() = runBlocking {
        val reader =
            scriptedReader(
                ScriptedEngine {
                    readingOf(flags = setOf(ReaderSafety.BREATHING), topics = mapOf(Symptom.FEVER to 0.9))
                },
            )
        val read = checkNotNull(reader.decide("hmm", ChatReaderContext(), CHAT_TODAY, past = false, routes = false))
        assertTrue(read.shadow)
        assertEquals(listOf(SymptomStep.BREATH_NOW), read.safety)
        val shadowed = ChatLog().wroteRead("hmm", read)
        val plain = ChatLog().wrote("hmm").settle()
        assertEquals(chatTurn(plain, setting), chatTurn(shadowed, setting))
        reader.close()
    }

    /**
     * A message is read once, as it is sent: the chat is drawn again and again, kept and reloaded,
     * and nothing is read again; the reading's question stays asked as it was kept, and a later model
     * that would read otherwise changes nothing of the chat it did not read.
     */
    @Test
    fun aMessageIsReadOnceAndNeverAgain() = runBlocking {
        val engine = ScriptedEngine { text ->
            readingOf(
                flags = if ("air" in
                    text
                ) {
                    setOf(ReaderSafety.BREATHING)
                } else {
                    emptySet()
                },
                topics = mapOf(Symptom.FEVER to 0.9),
            )
        }
        val reader = scriptedReader(engine)
        var log = ChatLog().sentWith(reader, "no air", setting)
        assertEquals(1, engine.reads.get())
        assertEquals(SymptomStep.BREATH_NOW, log.asked())
        log = log.tapOn(SymptomOption.NO)
        assertEquals(SymptomStep.SYMPTOM, log.asked())
        assertEquals(listOf(Symptom.FEVER), (chatTurn(log, setting).reply as ChatReply.Topics).choices.map { it.topic })
        val transcript = chatTranscript(log, setting, live = true)
        val reloaded = checkNotNull(decodeChatLog(encodeChatLog(log, pending = false)))
        repeat(3) {
            assertEquals(transcript, chatTranscript(reloaded, setting, live = true))
            assertEquals(chatTurn(log, setting), chatTurn(reloaded, setting))
        }
        assertEquals(1, engine.reads.get())
        // The second message is read, the first never again: its reading stays as it was kept.
        val next = reloaded.sentWith(reader, "hot", setting)
        assertEquals(2, engine.reads.get())
        fun first(of: ChatLog) = (of.threads.first().report.chat?.turns?.first() as ChatTurn.Wrote).read
        assertEquals(first(log), first(next))
        reader.close()
    }

    /**
     * A model whose package names no language it passed its exam in — the fixture, which never sat
     * one — adds its safety flags and nothing else: no
     * topic, nothing told, in any language, and every reading says it is of a language not passed, even
     * one that abstains — which the chat's words about how it read go by ([ChatRead.examined]); read as
     * passed in every one, the same messages' readings propose and tell, and flag the same.
     */
    @Test
    fun aModelThatPassedNoExamAddsItsFlagsAlone() = runBlocking {
        val raw =
            ChatModelReader(ChatModelTestFiles.fixtureFiles(), mode = ChatModelMode.ON, allowSmoke = true, threads = 1)
        val examined = tinyReader()
        var proposed = 0
        for (text in listOf("болит колено", "мне плохо", "I took 20 paracetamol", "у меня температура", "hello")) {
            val read = checkNotNull(ChatLog().readBy(raw, text, setting))
            assertEquals(text, ChatReadOutcome.UNEXAMINED, read.why)
            assertFalse(text, read.examined)
            assertEquals(text, ChatRead(read.model, read.why, safety = read.safety), read)
            val passed = checkNotNull(ChatLog().readBy(examined, text, setting))
            assertTrue(text, passed.examined)
            assertTrue(text, passed.safety.containsAll(read.safety))
            proposed += passed.topics.size
        }
        assertTrue(proposed > 0)
        raw.close()
        examined.close()
    }

    /** The reader is told where the chat stands: the opening, a question with its topic, between threads, the note. */
    @Test
    fun theReaderIsToldWhereTheChatStands() {
        assertEquals(ChatReaderContext(ChatReaderWhere.START), ChatLog().readerContext(setting))
        val words = ChatLog().wrote("hmm").settle()
        assertEquals(
            ChatReaderContext(ChatReaderWhere.START, recentMessages = listOf("hmm")),
            words.readerContext(setting),
        )
        val knee = ChatLog().wrote("болит колено").settle()
        assertEquals(
            ChatReaderContext(ChatReaderWhere.QUESTION, SymptomStep.PAIN_AREA, Symptom.PAIN, listOf("болит колено")),
            knee.readerContext(setting),
        )
        val done = knee.calmToTheEnd()
        assertEquals(ChatMove.Prompt, chatMove(done, setting))
        assertEquals(ChatReaderWhere.BETWEEN, done.readerContext(setting).where)
        assertEquals(done.recentMessages(), done.readerContext(setting).recentMessages)
        val next = done.wrote("hmm").settle().readerContext(setting)
        assertEquals(ChatReaderWhere.BETWEEN, next.where)
        assertEquals("hmm", next.recentMessages.last())
        assertEquals(ChatReaderWhere.NOTE, done.endedByPerson().readerContext(setting).where)
    }

    /**
     * Words of suicide, of an emergency or of too much of a medicine never wait for a model: their
     * turn is the rules', at once — "more than it says?" among them — and the model
     * reads them all the same, after it. In the shadow nothing waits either, nothing being used; with no
     * model nothing is read.
     */
    @Test
    fun urgentWordsNeverWait() {
        listOf("не хочу жить", "I can't breathe", "он без сознания", "рвота кровью", "I took 20 paracetamol").forEach {
            assertTrue(it, urgentByRules(it))
            assertEquals(it, ReadPlan.AFTER, readPlan(it, ChatLog(), setting, reader = true, routes = true))
        }
        listOf("my knee hurts", "мне плохо", "hmm").forEach {
            assertFalse(it, urgentByRules(it))
            assertEquals(it, ReadPlan.HOLD, readPlan(it, ChatLog(), setting, reader = true, routes = true))
            assertEquals(it, ReadPlan.AFTER, readPlan(it, ChatLog(), setting, reader = true, routes = false))
            assertEquals(it, ReadPlan.NONE, readPlan(it, ChatLog(), setting, reader = false, routes = true))
        }
    }

    /**
     * Words the rules find urgent are met at once and read after: the breathing
     * question is the turn before any reading, and stays the turn once the reading, kept with the
     * message when it comes, adds what it flagged — the question about
     * suicide after the rules' own, where help is with it. The breathing turn is drawn again as it was
     * shown, with nothing of the reading in it.
     */
    @Test
    fun anUrgentMessageIsReadAfterItsTurnAndItsReadingJoins() = runBlocking {
        val engine = ScriptedEngine {
            readingOf(flags = setOf(ReaderSafety.CRISIS), topics = mapOf(Symptom.FEVER to 0.9))
        }
        val reader = scriptedReader(engine)
        val text = "не могу дышать"
        val rules = ChatLog().wrote(text).settle()
        assertEquals(SymptomStep.BREATH_NOW, rules.asked())
        val read = ChatLog().sentWith(reader, text, setting)
        assertEquals(1, engine.reads.get())
        assertTrue(messagesOf(read.threads.single().report.chat).single().read?.routes == true)
        assertEquals(chatTurn(rules, setting), chatTurn(read, setting))
        val answered = read.tapOn(SymptomOption.NO)
        val turn = chatTurn(answered, setting)
        assertEquals(SymptomStep.CRISIS_NOW, turn.asked())
        assertTrue(ChatLine.CRISIS_SUPPORT in turn.lines())
        val drawn = chatTranscript(answered, setting, live = false).filterIsInstance<ChatItem.Bot>()
            .first { item -> item.turn.pieces.any { it is BotPiece.Ask && it.step == SymptomStep.BREATH_NOW } }
        assertEquals(chatTurn(rules, setting).pieces, drawn.turn.pieces)
        reader.close()
    }

    /**
     * A message whose turn asks a question the thread's words bring never waits for a model either:
     * read over the whole thread, as the rules read it — the second half of
     * words of suicide, of too much of a medicine, and any word written while the rules' breathing
     * question waits — not over the message alone. Typed as an answer, words are read as they are typed.
     */
    @Test
    fun wordsTheThreadMakesUrgentNeverWait() {
        val halves = mapOf("больше не хочу" to "жить", "выпила 10 таблеток" to "парацетамола")
        halves.forEach { (first, second) ->
            val log = ChatLog().wrote(first).settle()
            assertFalse(second, urgentByRules(second))
            assertEquals(second, ReadPlan.AFTER, readPlan(second, log, setting, reader = true, routes = true))
        }
        val breath = ChatLog().wrote("не могу дышать").settle()
        assertEquals(ReadPlan.AFTER, readPlan("что?", breath, setting, reader = true, routes = true))
        val knee = ChatLog().wrote("болит колено").settle()
        assertEquals(ReadPlan.HOLD, readPlan("что?", knee, setting, reader = true, routes = true))
        assertEquals(ReadPlan.HOLD, readPlan("что?", breath, setting, reader = true, routes = true, typed = true))
    }

    /**
     * A message the turn waits for the reading of is not routed before it:
     * «выпила нурофен» names a dose alone, which the rules start at once; with the crisis a reading
     * flagged in the rest, its question comes first and the thread waits, as for the words of one — an
     * answer other than "no such thoughts" closes it and the chat offers to talk; "no such thoughts"
     * lets the dose start, as its words name it.
     */
    @Test
    fun aMessageWaitingForItsReadingIsNotRoutedBeforeIt() = runBlocking {
        val engine = ScriptedEngine {
            readingOf(flags = setOf(ReaderSafety.CRISIS), topics = mapOf(Symptom.DOSE to 0.9))
        }
        val reader = scriptedReader(engine)
        val text = "выпила нурофен, больше ничего не хочу"
        assertEquals(Symptom.DOSE, ChatLog().wrote(text).settle().threads.single().topic)
        val sent = ChatLog().sentWith(reader, text, setting)
        assertNull(sent.threads.single().topic)
        assertEquals(SymptomStep.CRISIS_NOW, sent.asked())
        val safe = sent.tapOn(SymptomOption.SAFE_NOW)
        assertEquals(ChatMove.Prompt, chatMove(safe, setting))
        assertTrue(safe.threads.none { it.topic == Symptom.DOSE })
        assertTrue(ChatLine.CRISIS_TALK in chatTurn(safe, setting).lines())
        assertEquals(Symptom.DOSE, sent.tapOn(SymptomOption.NO_SUCH_THOUGHTS).threads.single().topic)
        // Without a model to read it, the message is routed as it is kept, as ever.
        val none = ChatModelReader({ null }, mode = ChatModelMode.ON, threads = 1)
        assertEquals(Symptom.DOSE, ChatLog().sentWith(none, text, setting).threads.single().topic)
        reader.close()
        none.close()
    }

    /**
     * The shadow is for debuggable builds alone: a build for everyone set to it reads
     * nothing, so no message is read without the line that says so; a debuggable one keeps the readings,
     * and the line stands only while the readings are used.
     */
    @Test
    fun theShadowIsTheTeamsAlone() = runBlocking {
        assertEquals(ChatModelMode.OFF, ChatModelMode.of("SHADOW", debuggable = false))
        assertEquals(ChatModelMode.SHADOW, ChatModelMode.of("SHADOW", debuggable = true))
        assertEquals(ChatModelMode.ON, ChatModelMode.of("ON", debuggable = false))
        assertEquals(ChatModelMode.OFF, ChatModelMode.of("LOUD", debuggable = true))
        val reader = scriptedReader(ScriptedEngine { readingOf() })
        val ready = reader.state()
        assertTrue(chatModelHold(reader, ready, ChatModelMode.ON).on)
        val shadow = chatModelHold(reader, ready, ChatModelMode.SHADOW)
        assertFalse(shadow.on)
        assertNotNull(shadow.reader)
        reader.close()
    }

    /**
     * A message sent while the model loads is read all the same, the read waiting on the load within its
     * second: the page holds the reader from the start, and its line with it; a model
     * unavailable is no reader at all, and no line.
     */
    @Test
    fun aMessageSentWhileTheModelLoadsIsRead() = runBlocking {
        val reader = scriptedReader(ScriptedEngine { readingOf() })
        val loading = chatModelHold(reader, null, ChatModelMode.ON)
        assertNotNull(loading.reader)
        // The line stands while it loads too, before any message is sent.
        assertTrue(loading.on)
        val read = loading.reader?.decide("hmm", ChatReaderContext(), CHAT_TODAY, past = false, routes = true)
        assertNotNull(read?.model)
        val gone = chatModelHold(reader, ChatModelState.Unavailable(ChatModelUnavailable.RUN_FAILED), ChatModelMode.ON)
        assertNull(gone.reader)
        assertFalse(gone.on)
        reader.close()
    }

    /** The chat's own reader never takes the smoke model, while a test may (the fixture is one). */
    @Test
    fun theSmokeModelIsTheTestsAloneToForceOn() = runBlocking {
        val manifest = ChatModelManifest.parse(File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText())
        assertTrue(manifest.smoke)
        val refused =
            ChatModelReader(ChatModelTestFiles.fixtureFiles(), mode = ChatModelMode.ON, allowSmoke = false, threads = 1)
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.SMOKE), refused.state())
        refused.close()
        val forced = tinyReader()
        assertNotNull(ChatLog().readBy(forced, "hello", setting)?.model)
        forced.close()
    }

    private companion object {
        const val SLOW_MILLIS = 300L
    }
}
