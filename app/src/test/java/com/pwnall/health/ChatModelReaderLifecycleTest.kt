package com.pwnall.health

import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The reader's own life around an engine that a test controls: when it loads, what a run that
 * stops or fails leaves behind, and that closing waits for the read in progress. The engine here
 * is a stand-in; ChatModelReaderTest runs the real one.
 */
class ChatModelReaderLifecycleTest {
    /**
     * A run the deadline overtakes ends inside ONNX Runtime with an exception, as a terminated run
     * does — and that is the timeout it is, not a failure: the next message reads.
     */
    @Test
    fun aRunStoppedAtTheLimitIsATimeoutNotAFailure() = runBlocking {
        val engine = FakeEngine { call ->
            if (call == 1) {
                Thread.sleep(SLOW_MILLIS)
                throw OrtException("Exiting due to terminate flag being set to true.")
            }
        }
        val reader = reader(engine)
        assertTrue(reader.state() is ChatModelState.Ready)
        val started = System.nanoTime()
        assertEquals(ChatReadOutcome.TimedOut, reader.read("hi", limitMillis = 50))
        assertTrue(System.nanoTime() - started >= 50_000_000L)
        assertEquals(emptyList<Throwable>(), reader.failures)
        assertTrue(reader.readPatiently("hi again") is ChatReadOutcome.Read)
        assertEquals(2, engine.reads.get())
        reader.close()
    }

    /** A failed run turns the model off for this opening, and its session goes at once, not when the chat closes. */
    @Test
    fun aRunThatThrowsByItselfTurnsTheModelOff() = runBlocking {
        val engine = FakeEngine { throw OrtException("Non-zero status code returned while running Gather node") }
        val reader = reader(engine)
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.RUN_FAILED), reader.readPatiently("hi"))
        assertTrue("the session outlived the failed run", engine.closed.isCompleted)
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.RUN_FAILED), reader.readPatiently("hi"))
        assertEquals(1, engine.reads.get())
        assertTrue(reader.failures.single() is OrtException)
        reader.close()
        assertEquals(1, engine.closings.get())
    }

    /** Out of memory, the reader lets go of the 100 MB that ran out rather than holding it to the end of the chat. */
    @Test
    fun runningOutOfMemoryTurnsTheModelOffAndFreesItsSession() = runBlocking {
        val engine = FakeEngine { throw OutOfMemoryError("Failed to allocate memory") }
        val reader = reader(engine)
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.OUT_OF_MEMORY), reader.readPatiently("hi"))
        assertTrue("the session outlived running out of memory", engine.closed.isCompleted)
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.OUT_OF_MEMORY), reader.state())
    }

    /** A native method that does not link throws an Error, not an Exception: a failed run all the same. */
    @Test
    fun aRunThatCannotLinkIsAFailureNotACrash() = runBlocking {
        val engine = FakeEngine { throw UnsatisfiedLinkError("No implementation found for OrtSession.run") }
        val reader = reader(engine)
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.RUN_FAILED), reader.readPatiently("hi"))
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.RUN_FAILED), reader.state())
        assertTrue(reader.failures.single() is UnsatisfiedLinkError)
        assertTrue(engine.closed.isCompleted)
    }

    /** Whatever the loader throws, Errors from a library that does not link among it, is a failed load. */
    @Test
    fun aLoadThatCannotLinkIsAFailedLoadNotACrash() = runBlocking {
        val reader = ChatModelReader(NO_FILES, mode = ChatModelMode.ON, loader = {
            throw UnsatisfiedLinkError("dlopen failed: library \"libonnxruntime.so\" not found")
        })
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.LOAD_FAILED), reader.state())
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.LOAD_FAILED), reader.readPatiently("hi"))
        assertTrue(reader.failures.single() is UnsatisfiedLinkError)
        val throwing = ChatModelReader(NO_FILES, mode = ChatModelMode.ON, loader = { error("a loader with a bug") })
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.LOAD_FAILED), throwing.state())
    }

    /**
     * A load or a run that fails is told to the reader's reporter once, with the reason the turn's
     * payload records — logcat on the phone, the one place a failure only a phone has can be read
     * from. The reads after it, answered without the model, tell it nothing more.
     */
    @Test
    fun aFailedLoadOrRunIsReportedOnceWithItsReason() = runBlocking {
        val reported = ArrayList<Pair<ChatModelUnavailable, Throwable>>()
        val report = { why: ChatModelUnavailable, failure: Throwable -> reported += why to failure }
        val unlinked = UnsatisfiedLinkError("dlopen failed: library \"libonnxruntime.so\" not found")
        val loading = ChatModelReader(NO_FILES, mode = ChatModelMode.ON, report = report, loader = { throw unlinked })
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.LOAD_FAILED), loading.state())
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.LOAD_FAILED), loading.readPatiently("hi"))
        val running = ChatModelReader(NO_FILES, mode = ChatModelMode.ON, report = report, loader = {
            ChatModelLoad.Loaded(FakeEngine { throw OutOfMemoryError("Failed to allocate memory") })
        })
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.OUT_OF_MEMORY), running.readPatiently("hi"))
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.OUT_OF_MEMORY), running.readPatiently("hi"))
        val reasons = listOf(ChatModelUnavailable.LOAD_FAILED, ChatModelUnavailable.OUT_OF_MEMORY)
        assertEquals(reasons, reported.map { it.first })
        assertSame(unlinked, reported.first().second)
        assertEquals(loading.failures + running.failures, reported.map { it.second })
    }

    /** The load reads the package's files, so it runs on the IO dispatcher, not on Default's few threads. */
    @Test
    fun theModelLoadsOnTheIoDispatcher() = runBlocking {
        val io = Executors.newSingleThreadExecutor { Thread(it, "chat-reader-io") }.asCoroutineDispatcher()
        var thread: String? = null
        val reader = ChatModelReader(NO_FILES, mode = ChatModelMode.ON, io = io, loader = {
            thread = Thread.currentThread().name
            ChatModelLoad.Loaded(FakeEngine {})
        })
        assertTrue(reader.state() is ChatModelState.Ready)
        assertTrue("loaded on $thread", thread.orEmpty().startsWith("chat-reader-io"))
        io.close()
    }

    /**
     * A read queued behind a slow one is cut at its own limit — the person waits no longer than that,
     * however long the run ahead takes — and the reader stays whole and on afterwards: the cut read
     * leaves its message to the rules, the next is read as before (no phone's
     * slowness switches the model off).
     */
    @Test
    fun aReadCutBehindASlowOneLeavesTheModelOn() = runBlocking {
        val running = CountDownLatch(1)
        val engine = FakeEngine { call ->
            if (call == 1) {
                running.countDown()
                Thread.sleep(RUN_MILLIS)
            }
        }
        val reader = ChatModelReader(NO_FILES, mode = ChatModelMode.ON, loader = { ChatModelLoad.Loaded(engine) })
        assertTrue(reader.state() is ChatModelState.Ready)
        val first = async(Dispatchers.Default) { reader.read("first", limitMillis = 10_000) }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertEquals(ChatReadOutcome.TimedOut, reader.read("second", limitMillis = RUN_MILLIS / 10))
        assertTrue((System.nanoTime() - started) / 1_000_000 < RUN_MILLIS)
        assertTrue(first.await() is ChatReadOutcome.Read)
        assertTrue(reader.readPatiently("third") is ChatReadOutcome.Read)
        assertNull(reader.stopped.value)
        assertTrue(reader.state() is ChatModelState.Ready)
        reader.close()
    }

    /** A rotation recreates the screen, not the chat: the new screen gets the same reader, loaded once. */
    @Test
    fun aRotationKeepsTheChatsReaderAndItsLoad() = runBlocking {
        val loads = AtomicInteger()
        val engine = FakeEngine {}
        val readers = ChatModelReaders(lingerMillis = LINGER_MILLIS) {
            ChatModelReader(NO_FILES, mode = ChatModelMode.ON, loader = {
                loads.incrementAndGet()
                ChatModelLoad.Loaded(engine)
            })
        }
        val before = readers.acquire()
        assertTrue(before.state() is ChatModelState.Ready)
        readers.release(before)
        val after = readers.acquire()
        assertSame(before, after)
        delay(2 * LINGER_MILLIS)
        assertTrue(after.state() is ChatModelState.Ready)
        assertFalse(engine.closed.isCompleted)
        assertEquals(1, loads.get())
        readers.release(after)
        withTimeout(5_000) { engine.closed.await() }
    }

    /** Leaving the chat closes its reader once nothing has taken it back; the next opening loads afresh. */
    @Test
    fun leavingTheChatClosesItsReaderAfterAMoment() = runBlocking {
        val engines = ArrayList<FakeEngine>()
        val readers = ChatModelReaders(lingerMillis = LINGER_MILLIS) {
            ChatModelReader(NO_FILES, mode = ChatModelMode.ON, loader = {
                ChatModelLoad.Loaded(FakeEngine {}.also { synchronized(engines) { engines += it } })
            })
        }
        val reader = readers.acquire()
        assertTrue(reader.state() is ChatModelState.Ready)
        readers.release(reader)
        assertFalse("closed before the linger", engines.single().closed.isCompleted)
        withTimeout(5_000) { engines.single().closed.await() }
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.CLOSED), reader.state())
        val next = readers.acquire()
        assertNotSame(reader, next)
        assertTrue(next.state() is ChatModelState.Ready)
        assertEquals(2, synchronized(engines) { engines.size })
        readers.release(next)
    }

    /**
     * The app stopped under the chat's page — Home, the screen off — gives the reader back as leaving
     * the page does: back within the linger the page has the same reader, later its session has gone
     * and the page takes a new one. One hold whatever a lifecycle repeats, so the page gone closes it.
     */
    @Test
    fun theAppStoppedGivesTheReaderBackUntilItIsOnScreenAgain() = runBlocking {
        val engines = ArrayList<FakeEngine>()
        val readers = ChatModelReaders(lingerMillis = LINGER_MILLIS) {
            ChatModelReader(NO_FILES, mode = ChatModelMode.ON, loader = {
                ChatModelLoad.Loaded(FakeEngine {}.also { synchronized(engines) { engines += it } })
            })
        }
        val held = HeldReader(readers)
        val first = held.reader
        assertTrue(first.state() is ChatModelState.Ready)
        held.giveBack()
        held.takeAgain()
        assertSame(first, held.reader)
        delay(2 * LINGER_MILLIS)
        assertFalse("closed under a page back on screen", engines.single().closed.isCompleted)
        held.giveBack()
        withTimeout(5_000) { engines.single().closed.await() }
        // The start a lifecycle replays to an observer comes on top of the real one: taken once.
        held.takeAgain()
        held.takeAgain()
        assertNotSame(first, held.reader)
        assertTrue(held.reader.state() is ChatModelState.Ready)
        held.onForgotten()
        withTimeout(5_000) { synchronized(engines) { engines.last() }.closed.await() }
        assertEquals(2, synchronized(engines) { engines.size })
    }

    /** prepare() starts the one load an opened chat makes; every read after shares it. */
    @Test
    fun theModelLoadsOncePerOpening() = runBlocking {
        val loads = AtomicInteger()
        val engine = FakeEngine {}
        val reader = ChatModelReader(NO_FILES, mode = ChatModelMode.ON, loader = {
            loads.incrementAndGet()
            ChatModelLoad.Loaded(engine)
        })
        assertEquals(0, loads.get())
        reader.prepare()
        withTimeout(5_000) { while (loads.get() == 0) delay(5) }
        repeat(3) { assertTrue(reader.readPatiently("hi") is ChatReadOutcome.Read) }
        reader.prepare()
        assertEquals(ChatModelState.Ready(FakeEngine.MODEL), reader.state())
        assertEquals(1, loads.get())
        reader.close()
    }

    @Test
    fun aReaderSwitchedOffOrClosedFirstNeverLoads() = runBlocking {
        val loads = AtomicInteger()
        val loader: () -> ChatModelLoad = {
            loads.incrementAndGet()
            ChatModelLoad.Loaded(FakeEngine {})
        }
        val off = ChatModelReader(NO_FILES, mode = ChatModelMode.OFF, loader = loader)
        off.prepare()
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.DISABLED), off.read("hi"))
        val closed = ChatModelReader(NO_FILES, mode = ChatModelMode.ON, loader = loader)
        closed.close()
        closed.prepare()
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.CLOSED), closed.read("hi"))
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.CLOSED), closed.state())
        assertEquals(0, loads.get())
    }

    /** Closing while a message is being read lets that read finish before the engine goes. */
    @Test
    fun closingWaitsForTheReadInProgress() = runBlocking {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = FakeEngine {
            running.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val reader = reader(engine)
        // Off this thread, which is about to block on the latch until the read has begun.
        val read = async(Dispatchers.Default) { reader.read("hi", limitMillis = 10_000) }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        reader.close()
        delay(100)
        assertFalse("the engine closed under a running read", engine.closed.isCompleted)
        release.countDown()
        assertTrue(read.await() is ChatReadOutcome.Read)
        withTimeout(5_000) { engine.closed.await() }
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.CLOSED), reader.readPatiently("hi"))
    }

    /** Whatever a read throws — not only ONNX Runtime's own errors — is the rules' turn, never the chat's crash. */
    @Test
    fun anythingAReadThrowsIsAFailureNotACrash() = runBlocking {
        val running = reader(FakeEngine { throw IllegalStateException("Asking for a result from a closed session") })
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.RUN_FAILED), running.readPatiently("hi"))
        val tokenizing = reader(FakeEngine(onPrepare = { throw IllegalArgumentException("no context token") }) {})
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.RUN_FAILED), tokenizing.readPatiently("hi"))
        assertTrue(tokenizing.failures.single() is IllegalArgumentException)
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.RUN_FAILED), tokenizing.state())
    }

    /** An engine that throws as it closes leaves a record, not a crash on a thread nobody watches. */
    @Test
    fun anEngineThatFailsToCloseIsRecorded() = runBlocking {
        val reader = reader(FakeEngine(onClose = { throw IllegalStateException("already closed") }) {})
        assertTrue(reader.readPatiently("hi") is ChatReadOutcome.Read)
        reader.close()
        withTimeout(5_000) { while (reader.failures.isEmpty()) delay(5) }
        assertTrue(reader.failures.single() is IllegalStateException)
    }

    private fun reader(engine: ChatModelEngine) =
        ChatModelReader(NO_FILES, mode = ChatModelMode.ON, loader = { ChatModelLoad.Loaded(engine) })

    /** An engine whose reads run [behaviour] (given the read's number, from 1) and then return a reading. */
    private class FakeEngine(
        private val onPrepare: () -> Unit = {},
        private val onClose: () -> Unit = {},
        private val behaviour: (Int) -> Unit,
    ) : ChatModelEngine {
        val reads = AtomicInteger()
        val closings = AtomicInteger()
        val closed = CompletableDeferred<Unit>()

        override val model: String = MODEL

        override fun prepare(text: String, context: ChatReaderContext): ChatModelInput {
            onPrepare()
            return ChatModelInput(text, ChatEncoding(IntArray(0), emptyList(), IntArray(0), IntArray(0)), emptyList())
        }

        override fun read(input: ChatModelInput, options: OrtSession.RunOptions?): ChatReading {
            behaviour(reads.incrementAndGet())
            return READING
        }

        override fun close() {
            closings.incrementAndGet()
            onClose()
            closed.complete(Unit)
        }

        companion object {
            const val MODEL = "fake reader"
        }
    }

    private companion object {
        const val SLOW_MILLIS = 300L

        // Real time, both: the deadline is ONNX Runtime's and the linger a real timer. Each is the
        // room a test leaves the whole JVM to stall in — a collection, a busy runner — between two
        // statements before it fails for nothing: the cut read has to come back within the slow
        // one's run, and a reader taken again has to be taken within the linger.
        const val RUN_MILLIS = 1_000L
        const val LINGER_MILLIS = 600L
        val NO_FILES = ChatModelFiles { null }
        val READING = ChatReading(
            model = FakeEngine.MODEL,
            lang = ReaderLabel("en", 1.0),
            topics = emptyList(),
            unwell = ReaderUnwell(0.0, button = false, body = 0.0, mind = 0.0, lean = null),
            safety = emptyList(),
            about = ReaderLabel("SELF", 1.0),
            question = 0.0,
            smallTalk = null,
            mood = null,
            oos = 0.0,
            abstain = true,
            spans = emptyList(),
            heads = emptyMap(),
            rules = ReaderRules(),
            tokens = 0,
            windows = 1,
        )
    }
}
