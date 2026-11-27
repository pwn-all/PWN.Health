package com.pwnall.health

import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * The chat reader's runtime on the desktop ONNX Runtime — the same 1.30.0 the phone runs — over
 * the reader's tiny fixture: a two-layer encoder with random weights and the real inputs,
 * outputs and vocabulary. Its golden is what the Python reference read from the same file,
 * so everything between a typed message and a [ChatReading] is held to it: the ids, every window's
 * input, every logit, the window combination, the activations, the BIO decoding, the spans and their
 * offsets in the raw text.
 */
class ChatModelReaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val golden = ChatModelTestFiles.json(File(ChatModelTestFiles.fixture, "fixture-golden.json"))
    private val tolerance = golden.getDouble("tolerance")
    private val cases = golden.getJSONArray("cases").jsonObjects()
    private val sampleEvery = golden.getInt("span_logits_sample_every")

    @Test
    fun theFixtureIsTheOneTheGoldenWasReadFrom() {
        val manifest = ChatModelManifest.parse(File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText())
        val model = File(ChatModelTestFiles.fixture, manifest.onnxFile).readBytes()
        assertEquals(golden.getString("onnx_sha256"), sha256(model))
        assertEquals(golden.getString("tokenizer_sha256"), manifest.tokenizerSha256)
        assertEquals(golden.getString("schema_sha256"), manifest.schemaSha256)
        assertTrue(cases.size >= 45)
        assertTrue(cases.count { it.getJSONArray("windows").length() > 1 } >= 2)
    }

    /** Every case, window by window: the same input ids and the same logits as the reference. */
    @Test
    fun everyWindowGivesTheReferencesLogits() {
        val engine = engine()
        for (case in cases) {
            val id = case.getString("id")
            val input = engine.prepare(case.getString("text"), case.getJSONObject("context").readerContext())
            assertEquals(id, case.getJSONArray("ids").intArray().toList(), input.encoding.ids.toList())
            val windows = case.getJSONArray("windows").jsonObjects()
            assertEquals(id, windows.size, input.windows.size)
            for ((window, want) in input.windows.zip(windows)) {
                assertEquals(id, want.getJSONArray("input_ids").intArray().toList(), window.inputIds.map(Long::toInt))
                assertEquals(id, want.getInt("start"), window.start)
                assertEquals(id, want.getInt("end"), window.end)
                compareLogits(id, engine.run(window), want)
            }
        }
        engine.close()
    }

    /** Every case, read whole: the reference's probabilities, spans, offsets and attributes. */
    @Test
    fun everyReadingIsTheReferencesReading() {
        val engine = engine()
        var spans = 0
        for (case in cases) {
            val id = case.getString("id")
            val text = case.getString("text")
            val reading = engine.read(engine.prepare(text, case.getJSONObject("context").readerContext()))
            val want = case.getJSONObject("read")
            val probabilities = want.getJSONObject("probabilities")
            assertEquals(id, probabilities.keyNames(), reading.heads.keys)
            for (head in probabilities.keyNames()) {
                near(id, probabilities.getJSONObject(head), reading.heads.getValue(head))
            }
            val wantSpans = want.getJSONArray("spans").jsonObjects()
            assertEquals(id, wantSpans.map { it.getString("kind") }, reading.spans.map { it.kind.name })
            for ((span, wantSpan) in reading.spans.zip(wantSpans)) {
                val pieces = wantSpan.getJSONArray("pieces")
                assertEquals(id, pieces.getInt(0), span.firstPiece)
                assertEquals(id, pieces.getInt(1), span.endPiece)
                assertEquals(id, wantSpan.getInt("at"), span.start)
                assertEquals(id, wantSpan.getInt("end"), span.end)
                // The reference decodes a lone surrogate as U+FFFD; the reading keeps what was typed.
                assertEquals(id, wantSpan.getString("text"), span.text.replace(Regex("\\p{Cs}"), "�"))
                assertEquals(id, text.substring(span.start, span.end), span.text)
                val attrs = wantSpan.getJSONObject("attrs")
                assertEquals(id, attrs.keyNames(), span.attributes.keys)
                for (head in attrs.keyNames()) near(id, attrs.getJSONObject(head), span.attributes.getValue(head))
            }
            spans += reading.spans.size
            assertEquals(id, case.getJSONArray("windows").length(), reading.windows)
            assertEquals(id, case.getJSONArray("ids").length(), reading.tokens)
        }
        assertTrue("the fixture marks spans", spans > 100)
        engine.close()
    }

    /** The asynchronous reader the chat holds gives the same reading and names its model. */
    @Test
    fun theReaderReadsOnceItHasLoaded() = runBlocking {
        val reader = ChatModelReader(ChatModelTestFiles.fixtureFiles(), mode = ChatModelMode.ON, threads = 1)
        reader.prepare()
        val manifest = ChatModelManifest.parse(File(ChatModelTestFiles.fixture, CHAT_MODEL_MANIFEST).readText())
        assertEquals(ChatModelState.Ready(manifest.model), reader.state())
        val case = cases.first { it.getString("id") == "edge-hindi" }
        val outcome = reader.readPatiently(case.getString("text"), case.getJSONObject("context").readerContext())
        val reading = (outcome as ChatReadOutcome.Read).reading
        assertEquals(manifest.model, reading.model)
        val engine = engine()
        val direct = engine.read(engine.prepare(case.getString("text"), case.getJSONObject("context").readerContext()))
        assertEquals(direct, reading)
        engine.close()
        reader.close()
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.CLOSED), reader.read("hello"))
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.CLOSED), reader.state())
    }

    /** Past its limit a read is the rules' turn, and the reader stays whole: the next message reads as usual. */
    @Test
    fun aReadPastItsLimitIsATimeoutAndTheNextOneReads() = runBlocking {
        val reader = ChatModelReader(ChatModelTestFiles.fixtureFiles(), mode = ChatModelMode.ON, threads = 1)
        assertTrue(reader.state() is ChatModelState.Ready)
        val outcome = reader.read("my right knee hurts", limitMillis = 0)
        assertEquals(ChatReadOutcome.TimedOut, outcome)
        assertEquals("timeout", outcome.reason)
        assertTrue(reader.readPatiently("my right knee hurts") is ChatReadOutcome.Read)
        assertTrue(reader.failures.toString(), reader.failures.isEmpty())
        reader.close()
    }

    /** ONNX Runtime honours the terminate flag the reader sets when its caller stops waiting. */
    @Test
    fun aRunCanBeStoppedFromOutside() {
        val engine = engine()
        val input = engine.prepare("болит правое колено", ChatReaderContext())
        OrtSession.RunOptions().use { options ->
            options.setTerminate(true)
            try {
                engine.read(input, options)
                fail("a terminated run returned a reading")
            } catch (_: OrtException) {
                // stopped, as it should be
            }
        }
        // The session is fine afterwards.
        assertEquals(engine.read(input), engine.read(input))
        engine.close()
    }

    /**
     * The deadline stops the run inside ONNX Runtime, through the reader: a run of seconds — the
     * fixture's encoder over and over, which only the terminate flag can cut short — ends within a
     * window of the limit. Also when the reader's dispatcher has one thread, the one blocked in the
     * run: the stopper must not wait for a thread to start on or to set the flag from.
     */
    @Test
    fun theDeadlineStopsTheRunInsideOnnxRuntime() = runBlocking {
        val real = engine()
        val long = LongRun(real)
        val single = Executors.newSingleThreadExecutor { Thread(it, "chat-reader-test") }.asCoroutineDispatcher()
        for (dispatcher in listOf<CoroutineDispatcher>(single, Dispatchers.Default)) {
            val reader = ChatModelReader(NO_FILES, mode = ChatModelMode.ON, dispatcher = dispatcher, loader = {
                ChatModelLoad.Loaded(long)
            })
            assertTrue(reader.state() is ChatModelState.Ready)
            long.runs = long.seconds(FULL_RUN_SECONDS)
            long.done.set(0)
            val started = System.nanoTime()
            assertEquals(ChatReadOutcome.TimedOut, reader.read("my knee", limitMillis = LIMIT_MILLIS))
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue("$dispatcher: stopped after $millis ms", millis < LIMIT_MILLIS + STOP_MARGIN_MILLIS)
            assertTrue("$dispatcher: ${long.done.get()} of ${long.runs} windows ran", long.done.get() < long.runs / 2)
            assertTrue(reader.failures.toString(), reader.failures.isEmpty())
            long.runs = 0
            assertTrue(reader.readPatiently("my knee") is ChatReadOutcome.Read)
        }
        single.close()
        real.close()
    }

    /** A read whose caller stops waiting — the chat closing, say — stops its run the same way. */
    @Test
    fun aReadItsCallerCancelsStopsItsRunToo() = runBlocking {
        val real = engine()
        val long = LongRun(real)
        val reader = ChatModelReader(NO_FILES, mode = ChatModelMode.ON, loader = { ChatModelLoad.Loaded(long) })
        assertTrue(reader.state() is ChatModelState.Ready)
        long.runs = long.seconds(FULL_RUN_SECONDS)
        val read = launch(Dispatchers.Default) { reader.read("my knee", limitMillis = 60_000) }
        withTimeout(5_000) { while (long.done.get() == 0) delay(1) }
        val started = System.nanoTime()
        read.cancelAndJoin()
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("stopped after $millis ms", millis < STOP_MARGIN_MILLIS)
        assertTrue(reader.failures.toString(), reader.failures.isEmpty())
        long.runs = 0
        assertTrue(reader.readPatiently("my knee") is ChatReadOutcome.Read)
        real.close()
    }

    /**
     * ONNX Runtime's native library failing to load surfaces as an Error, not an Exception — an
     * UnsatisfiedLinkError the first time, NoClassDefFoundError after it, ExceptionInInitializerError
     * when its initialiser threw — and is a failed load like any other, never a crash in the chat.
     */
    @Test
    fun aRuntimeThatCannotLinkIsAFailedLoadNotACrash() = runBlocking {
        val failures = listOf(
            UnsatisfiedLinkError("dlopen failed: library \"libonnxruntime.so\" not found"),
            NoClassDefFoundError("Could not initialize class ai.onnxruntime.OrtEnvironment"),
            ExceptionInInitializerError(RuntimeException("Failed to load onnx-runtime library")),
        )
        for (failure in failures) {
            val load = OnnxChatModelEngine.load(
                ChatModelTestFiles.fixtureFiles(),
                threads = 1,
                allowSmoke = true,
                newSession = { _, _, _ -> throw failure },
            )
            assertEquals(ChatModelUnavailable.LOAD_FAILED, (load as ChatModelLoad.Failed).why)
            assertSame(failure, load.cause)
        }
        val reader = ChatModelReader(ChatModelTestFiles.fixtureFiles(), mode = ChatModelMode.ON, allowSmoke = true) {
            OnnxChatModelEngine.load(
                ChatModelTestFiles.fixtureFiles(),
                threads = 1,
                allowSmoke = true,
                newSession = { _, _, _ -> throw failures.first() },
            )
        }
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.LOAD_FAILED), reader.state())
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.LOAD_FAILED), reader.readPatiently("hi"))
        assertTrue(reader.failures.single() is UnsatisfiedLinkError)
    }

    /**
     * The load runs one short message through the session it made, so the first one a person sends
     * is not ONNX Runtime's first run; and whatever fails once the session exists closes it: here
     * the warm-up, the tokenizer's context tokens pointing past the model's vocabulary, and then
     * outputs narrower than the schema says. Nobody else holds such a session to close it later.
     */
    @Test
    fun aSessionTheLoadCannotUseIsClosedBeforeTheLoadFails() = runBlocking {
        val warmUp = packageWithTokenizer { it.replace(CONTEXT_FROM_5, "\"context_tokens\":{\"first_id\":900000,") }
        val narrow = packageCopy()
        val schema = File(narrow, CHAT_MODEL_SCHEMA)
        rewrite(schema) { json -> json.getJSONObject("outputs").getJSONObject(SPAN_LOGITS).put("size", 9_999) }
        rewrite(File(narrow, CHAT_MODEL_MANIFEST)) { it.put("schema_sha256", sha256(schema.readBytes())) }
        val packages = listOf(warmUp to ChatModelUnavailable.LOAD_FAILED, narrow to ChatModelUnavailable.MISMATCH)
        for ((dir, why) in packages) {
            val made = ArrayList<OrtSession>()
            val load = OnnxChatModelEngine.load(
                ChatModelTestFiles.files(listOf(dir)),
                threads = 1,
                allowSmoke = true,
                newSession = { environment, model, options ->
                    environment.createSession(model, options).also { made += it }
                },
            )
            assertEquals(dir.name, why, (load as ChatModelLoad.Failed).why)
            val session = made.single()
            assertThrows("the load left its session open", IllegalStateException::class.java) { session.close() }
        }
    }

    /** reader-tokenizer.json is parsed once per process, not on every opening of the chat; another file, again. */
    @Test
    fun theTokenizerIsParsedOncePerProcess() {
        val first = engine()
        val second = engine()
        assertSame(first.tokenizer, second.tokenizer)
        val wide = packageWithTokenizer { it.replace(WINDOWS_512, WINDOWS_600) }
        val other = loaded(ChatModelTestFiles.files(listOf(wide)))
        assertNotSame(first.tokenizer, other.tokenizer)
        assertEquals(600, other.tokenizer.maxPositions)
        for (engine in listOf(first, second, other)) engine.close()
    }

    /** As the APK holds it, the model is mapped where it lies; a package that cannot map it reads it, the same. */
    @Test
    fun aModelThatCannotBeMappedIsReadAndReadsTheSame() {
        val mapped = engine()
        assertTrue(mapped.mapped)
        val read = AtomicLong()
        val dirs = listOf(ChatModelTestFiles.fixture, ChatModelTestFiles.assets)
        val files = countingModel(ChatModelTestFiles.files(dirs, mapped = false), read)
        val streamed = loaded(files)
        assertFalse(streamed.mapped)
        assertEquals(File(ChatModelTestFiles.fixture, "reader-tiny.onnx").length(), read.get())
        for (case in cases.take(10)) {
            val context = case.getJSONObject("context").readerContext()
            val text = case.getString("text")
            assertEquals(mapped.read(mapped.prepare(text, context)), streamed.read(streamed.prepare(text, context)))
        }
        mapped.close()
        streamed.close()
    }

    /** A model that has to be read is read last, after every check that could still refuse its package. */
    @Test
    fun aModelThatHasToBeReadIsReadAfterEveryCheck() {
        val dir = packageCopy()
        rewrite(File(dir, CHAT_MODEL_MANIFEST)) { it.put("tokenizer_sha256", "0".repeat(64)) }
        val read = AtomicLong()
        val files = countingModel(ChatModelTestFiles.files(listOf(dir), mapped = false), read)
        val load = OnnxChatModelEngine.load(files, threads = 1, allowSmoke = true)
        assertEquals(ChatModelUnavailable.MISMATCH, (load as ChatModelLoad.Failed).why)
        assertEquals("bytes of the model read for a package refused before it", 0L, read.get())
    }

    /** A calibration whose threshold is no probability would leave a flag that never rises: no model instead. */
    @Test
    fun aCalibrationWithAThresholdThatIsNoProbabilityIsRefused() = runBlocking {
        for (threshold in listOf("NaN", "1.5", "-0.1", "Infinity")) {
            val dir = packageCopy()
            val calibration = File(dir, "reader-calibration.json")
            val spoiled = calibration.readText().replace("\"safety\": {}", "\"safety\": {\"CRISIS\": $threshold}")
            assertTrue(spoiled != calibration.readText())
            calibration.writeText(spoiled)
            nameCalibration(dir) // refused for what it says, then, not for being another file
            assertEquals(threshold, ChatModelUnavailable.MISMATCH, unavailable(dir))
        }
    }

    /**
     * A calibration that reads well but is not the file the manifest names — another run's thresholds
     * beside this model — is refused as another tokenizer is: its safety flags would rise elsewhere.
     */
    @Test
    fun aCalibrationTheManifestDoesNotNameIsRefused() {
        val dir = packageCopy()
        val calibration = File(dir, "reader-calibration.json")
        val other = calibration.readText().replace("\"safety\": {}", "\"safety\": {\"CRISIS\": 0.9}")
        assertTrue(other != calibration.readText())
        calibration.writeText(other)
        assertEquals(ChatModelUnavailable.MISMATCH, unavailable(dir))
        nameCalibration(dir)
        val named = loaded(ChatModelTestFiles.files(listOf(dir)))
        assertEquals(0.9, named.calibration.safetyThreshold("CRISIS", null), 0.0)
        named.close()
    }

    @Test
    fun theSwitchOffLoadsNothing() = runBlocking {
        val opened = ArrayList<String>()
        val reader = ChatModelReader(ChatModelTestFiles.fixtureFiles(opened), mode = ChatModelMode.OFF)
        reader.prepare()
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.DISABLED), reader.read("мне плохо"))
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.DISABLED), reader.state())
        assertEquals(emptyList<String>(), opened)
        assertEquals("disabled", reader.read("мне плохо").reason)
    }

    /** No model in the package: unavailable, and nothing else is even opened — the tokenizer least of all. */
    @Test
    fun noModelIsUnavailableAndOpensNothingElse() = runBlocking {
        val withoutManifest = ArrayList<String>()
        val noPackage = temp.newFolder()
        File(ChatModelTestFiles.assets, CHAT_MODEL_TOKENIZER).copyTo(File(noPackage, CHAT_MODEL_TOKENIZER))
        val nothing = ChatModelReader(
            ChatModelTestFiles.files(listOf(noPackage), withoutManifest),
            mode = ChatModelMode.ON,
        )
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.NO_MODEL), nothing.readPatiently("i feel bad"))
        assertEquals(listOf(CHAT_MODEL_MANIFEST), withoutManifest)

        val dir = packageCopy()
        File(dir, "reader-tiny.onnx").delete()
        val withoutModel = ArrayList<String>()
        val missing = ChatModelReader(ChatModelTestFiles.files(listOf(dir), withoutModel), mode = ChatModelMode.ON)
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.NO_MODEL), missing.state())
        assertEquals(listOf(CHAT_MODEL_MANIFEST, "reader-tiny.onnx"), withoutModel.distinct())
        assertEquals("no_model", missing.readPatiently("i feel bad").reason)
    }

    @Test
    fun aDamagedModelIsUnavailable() = runBlocking {
        val dir = packageCopy()
        val model = File(dir, "reader-tiny.onnx")
        model.writeBytes(ByteArray(model.length().toInt()) { (it * 31).toByte() })
        assertEquals(ChatModelUnavailable.LOAD_FAILED, unavailable(dir))

        val truncated = packageCopy()
        File(truncated, "reader-tiny.onnx").let { it.writeBytes(it.readBytes().copyOf(1000)) }
        assertEquals(ChatModelUnavailable.MISMATCH, unavailable(truncated))
    }

    @Test
    fun aModelMadeWithAnotherTokenizerOrSchemaIsRefused() = runBlocking {
        val tokenizer = packageCopy()
        rewrite(File(tokenizer, CHAT_MODEL_MANIFEST)) { it.put("tokenizer_sha256", "0".repeat(64)) }
        assertEquals(ChatModelUnavailable.MISMATCH, unavailable(tokenizer))

        // A schema that lost a topic the app has: the model would read into the wrong boxes.
        val schema = packageCopy()
        val schemaFile = File(schema, CHAT_MODEL_SCHEMA)
        rewrite(schemaFile) { json ->
            val topics = json.getJSONArray("message_heads").jsonObjects().first { it.getString("name") == "topics" }
            topics.getJSONArray("labels").remove(0)
        }
        rewrite(File(schema, CHAT_MODEL_MANIFEST)) { it.put("schema_sha256", sha256(schemaFile.readBytes())) }
        assertEquals(ChatModelUnavailable.MISMATCH, unavailable(schema))

        // A schema without a safety head at all is no schema for this app — and no crash either.
        val headless = packageCopy()
        val headlessFile = File(headless, CHAT_MODEL_SCHEMA)
        rewrite(headlessFile) { json ->
            val heads = json.getJSONArray("message_heads")
            heads.remove(heads.jsonObjects().indexOfFirst { it.getString("name") == "safety" })
        }
        rewrite(File(headless, CHAT_MODEL_MANIFEST)) { it.put("schema_sha256", sha256(headlessFile.readBytes())) }
        assertEquals(ChatModelUnavailable.MISMATCH, unavailable(headless))
    }

    @Test
    fun aSmokeModelLoadsOnlyInADebuggableBuild() = runBlocking {
        val refused = ChatModelReader(ChatModelTestFiles.fixtureFiles(), mode = ChatModelMode.ON, allowSmoke = false)
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.SMOKE), refused.state())
        val allowed = ChatModelReader(ChatModelTestFiles.fixtureFiles(), mode = ChatModelMode.ON, allowSmoke = true)
        assertTrue(allowed.state() is ChatModelState.Ready)
        allowed.close()
    }

    @Test
    fun aPackageWithoutItsCalibrationIsNoModel() = runBlocking {
        val dir = packageCopy()
        File(dir, "reader-calibration.json").delete()
        assertEquals(ChatModelUnavailable.NO_MODEL, unavailable(dir))
    }

    /**
     * A run that throws turns the model off for the rest of the chat: this package's tokenizer
     * windows 600 positions where the encoder has 512, so the first long message fails inside ONNX
     * Runtime. The read says so, and so does every read after it.
     */
    @Test
    fun aRunThatFailsTurnsTheModelOffUntilTheChatOpensAgain() = runBlocking {
        val dir = packageWithTokenizer { it.replace(WINDOWS_512, WINDOWS_600) }
        val reader = ChatModelReader(ChatModelTestFiles.files(listOf(dir)), mode = ChatModelMode.ON, threads = 1)
        assertTrue(reader.readPatiently("short") is ChatReadOutcome.Read)
        val long = "word ".repeat(700)
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.RUN_FAILED), reader.readPatiently(long))
        assertEquals(ChatReadOutcome.Unavailable(ChatModelUnavailable.RUN_FAILED), reader.readPatiently("short"))
        assertEquals(ChatModelState.Unavailable(ChatModelUnavailable.RUN_FAILED), reader.state())
        assertTrue(reader.failures.single() is OrtException)
        reader.close()
    }

    private fun unavailable(dir: File): ChatModelUnavailable = runBlocking {
        val reader = ChatModelReader(ChatModelTestFiles.files(listOf(dir)), mode = ChatModelMode.ON, threads = 1)
        val state = reader.state()
        reader.close()
        (state as? ChatModelState.Unavailable)?.why ?: error("$dir loaded: $state")
    }

    /** [packageCopy] with the tokenizer file changed by [change], and the manifest made to say so. */
    private fun packageWithTokenizer(change: (String) -> String): File {
        val dir = packageCopy()
        val tokenizer = File(dir, CHAT_MODEL_TOKENIZER)
        val changed = change(tokenizer.readText())
        assertTrue("the tokenizer did not change", changed != tokenizer.readText())
        tokenizer.writeText(changed)
        rewrite(File(dir, CHAT_MODEL_MANIFEST)) { it.put("tokenizer_sha256", sha256(tokenizer.readBytes())) }
        return dir
    }

    /** The fixture's complete matching package, in a folder a test may spoil. */
    private fun packageCopy(): File {
        val dir = temp.newFolder()
        for (file in ChatModelTestFiles.fixture.listFiles().orEmpty()) file.copyTo(File(dir, file.name))
        for (name in listOf(CHAT_MODEL_TOKENIZER, CHAT_MODEL_SCHEMA)) {
            ChatModelTestFiles.referenceFile(name).copyTo(File(dir, name))
        }
        return dir
    }

    /** The manifest of [dir] made to name the calibration that lies there now. */
    private fun nameCalibration(dir: File) = rewrite(File(dir, CHAT_MODEL_MANIFEST)) { manifest ->
        val calibration = sha256(File(dir, "reader-calibration.json").readBytes())
        manifest.getJSONObject("calibration").put("sha256", calibration)
    }

    private fun rewrite(file: File, change: (JSONObject) -> Unit) {
        val json = JSONObject(file.readText())
        change(json)
        file.writeText(json.toString())
    }

    private fun engine(): OnnxChatModelEngine = loaded(ChatModelTestFiles.fixtureFiles())

    private fun loaded(files: ChatModelFiles): OnnxChatModelEngine =
        when (val load = OnnxChatModelEngine.load(files, threads = 1, allowSmoke = true)) {
            is ChatModelLoad.Loaded -> load.engine as OnnxChatModelEngine
            is ChatModelLoad.Failed -> throw AssertionError("the package did not load: ${load.why}", load.cause)
        }

    /** [files] with every byte read of the fixture's model counted into [read]. */
    private fun countingModel(files: ChatModelFiles, read: AtomicLong): ChatModelFiles = object : ChatModelFiles {
        override fun open(name: String): InputStream? = files.open(name)?.let { stream ->
            if (name != "reader-tiny.onnx") return@let stream
            object : FilterInputStream(stream) {
                override fun read(): Int = super.read().also { if (it >= 0) read.incrementAndGet() }

                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) read.addAndGet(it.toLong()) }
            }
        }

        override fun map(name: String): ByteBuffer? = files.map(name)
    }

    /**
     * The fixture's engine reading [window] [runs] times before the message itself: a run of
     * seconds that nothing but ONNX Runtime's terminate flag cuts short. Closing it closes nothing.
     */
    private class LongRun(private val real: OnnxChatModelEngine) : ChatModelEngine {
        val window = real.prepare("word ".repeat(600), ChatReaderContext()).windows.first()
        val done = AtomicInteger()

        @Volatile
        var runs = 0

        override val model: String get() = real.model

        override fun prepare(text: String, context: ChatReaderContext): ChatModelInput = real.prepare(text, context)

        override fun read(input: ChatModelInput, options: OrtSession.RunOptions?): ChatReading {
            repeat(runs) {
                real.run(window, options)
                done.incrementAndGet()
            }
            return real.read(input, options)
        }

        /** How many runs of [window] take [seconds] on this machine. */
        fun seconds(seconds: Int): Int {
            repeat(3) { real.run(window) }
            val started = System.nanoTime()
            repeat(TIMING_RUNS) { real.run(window) }
            val each = (System.nanoTime() - started).toDouble() / TIMING_RUNS
            return (seconds * 1e9 / each).toInt().coerceIn(TIMING_RUNS, Int.MAX_VALUE / 2)
        }

        override fun close() = Unit
    }

    private companion object {
        val NO_FILES = ChatModelFiles { null }
        const val WINDOWS_512 = "\"windows\":{\"max_positions\":512,"
        const val WINDOWS_600 = "\"windows\":{\"max_positions\":600,"
        const val CONTEXT_FROM_5 = "\"context_tokens\":{\"first_id\":5,"
        const val LIMIT_MILLIS = 200L
        const val STOP_MARGIN_MILLIS = 1_500L
        const val FULL_RUN_SECONDS = 10
        const val TIMING_RUNS = 20
    }

    private fun compareLogits(id: String, logits: WindowLogits, want: JSONObject) {
        near("$id topic", want.getJSONArray("topic_logits").doubleArray(), logits.topic)
        near("$id safety", want.getJSONArray("safety_logits").doubleArray(), logits.safety)
        near("$id meta", want.getJSONArray("meta_logits").doubleArray(), logits.meta)
        val tags = logits.slot.size / logits.positions
        val attrs = logits.span.size / logits.positions
        if (want.has("slot_logits")) {
            val rows = want.getJSONArray("slot_logits")
            assertEquals(id, rows.length(), logits.positions)
            for (p in 0 until logits.positions) {
                val row = logits.slot.copyOfRange(p * tags, (p + 1) * tags)
                near("$id slot $p", rows.getJSONArray(p).doubleArray(), row)
            }
        } else {
            val argmax = want.getJSONArray("slot_argmax").intArray()
            val rowMax = want.getJSONArray("slot_row_max").doubleArray()
            for (p in 0 until logits.positions) {
                val row = logits.slot.copyOfRange(p * tags, (p + 1) * tags)
                assertEquals("$id slot argmax $p", argmax[p], row.indices.maxBy { row[it] })
                assertEquals("$id slot max $p", rowMax[p], row.max().toDouble(), tolerance)
            }
        }
        val samples = want.getJSONArray("span_logits_sample")
        val sums = want.getJSONArray("span_logits_row_sum").doubleArray()
        for (p in 0 until logits.positions) {
            val row = logits.span.copyOfRange(p * attrs, (p + 1) * attrs)
            val sampled = row.filterIndexed { j, _ -> j % sampleEvery == 0 }.toFloatArray()
            near("$id span $p", samples.getJSONArray(p).doubleArray(), sampled)
            assertEquals("$id span sum $p", sums[p], row.sumOf { it.toDouble() }, tolerance * 10)
        }
    }

    private fun near(what: String, want: DoubleArray, got: FloatArray) {
        assertEquals(what, want.size, got.size)
        for (j in want.indices) assertTrue("$what[$j]: ${want[j]} vs ${got[j]}", abs(want[j] - got[j]) <= tolerance)
    }

    private fun near(what: String, want: JSONObject, got: Map<String, Double>) {
        assertEquals(what, want.keyNames(), got.keys)
        for (label in want.keyNames()) {
            val difference = abs(want.getDouble(label) - got.getValue(label))
            assertTrue("$what $label: ${want.getDouble(label)} vs ${got[label]}", difference <= tolerance)
        }
    }
}
