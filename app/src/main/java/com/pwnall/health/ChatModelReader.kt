package com.pwnall.health

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.LongBuffer
import java.nio.channels.Channels
import java.util.Collections

/**
 * The chat model's switch, set per flavour in app/build.gradle.kts as
 * `BuildConfig.CHAT_MODEL`. On in both: the chat uses the readings of a model that ships in the
 * assets with its manifest. Without a package, nothing loads and the chat reads
 * with its rules alone; a smoke-test model is never one it uses
 * ([ChatModelReaders.of]).
 */
internal enum class ChatModelMode {
    /** Nothing loads: the chat reads with its rules alone. */
    OFF,

    /** The model reads and its readings are kept, but nothing the chat shows depends on them. */
    SHADOW,

    /** The chat uses the readings. */
    ON,
    ;

    val reads: Boolean get() = this != OFF

    val routes: Boolean get() = this == ON

    companion object {
        /**
         * The build's switch. The shadow is for a debuggable build alone: set in a
         * build for everyone it reads nothing, so no message is ever read without the line that says a
         * model reads them.
         */
        val build: ChatModelMode = of(BuildConfig.CHAT_MODEL, BuildConfig.DEBUG)

        /** The mode [name] sets in a build that is [debuggable] or not; an unknown name is [OFF]. */
        fun of(name: String, debuggable: Boolean): ChatModelMode =
            entries.firstOrNull { it.name == name }?.takeIf { it != SHADOW || debuggable } ?: OFF
    }
}

/**
 * Why there is no reading, in the words a turn's payload records it with. Each of them
 * means the same thing to the chat: it reads with its rules alone.
 */
internal enum class ChatModelUnavailable(val code: String) {
    /** The switch is off. */
    DISABLED("disabled"),

    /** No model ships, or its package is missing a file. */
    NO_MODEL("no_model"),

    /** A smoke-test model, refused outside a debuggable build. */
    SMOKE("smoke"),

    /**
     * The model was made with another tokenizer, schema or output layout than this app has, or the
     * calibration beside it is not the one its manifest names.
     */
    MISMATCH("mismatch"),

    /** ONNX Runtime could not take the model — or could not start at all, its native library missing. */
    LOAD_FAILED("load_failed"),
    OUT_OF_MEMORY("out_of_memory"),

    /**
     * A read threw; the model stays off until the chat is opened again, as the process's readers
     * count an opening ([ChatModelReaders]): a rotation, or a page back within their linger, finds it
     * off still — its session went with the failure, so the linger holds nothing but that verdict.
     */
    RUN_FAILED("run_failed"),

    CLOSED("closed"),
}

/** What one read came to. [reason] is what the payload records when the chat keeps to its rules. */
internal sealed interface ChatReadOutcome {
    val reason: String?

    data class Read(val reading: ChatReading) : ChatReadOutcome {
        override val reason: String? get() = if (reading.abstain) ABSTAIN else null
    }

    data class Unavailable(val why: ChatModelUnavailable) : ChatReadOutcome {
        override val reason: String get() = why.code
    }

    /** Not in time ([ChatModelReader.READ_LIMIT_MILLIS]): this turn is the rules'. */
    data object TimedOut : ChatReadOutcome {
        override val reason: String get() = TIMEOUT
    }

    companion object {
        const val ABSTAIN = "abstain"
        const val TIMEOUT = "timeout"

        /** A message with an uncertain or disabled routing language: its flags alone count. */
        const val UNEXAMINED = "unexamined"
    }
}

internal sealed interface ChatModelState {
    /**
     * Loaded: [model] as its manifest names it, and the [languages] enabled for topic and entity
     * proposals ([ChatModelManifest.routingLanguages]).
     */
    data class Ready(val model: String, val languages: Set<String> = emptySet()) : ChatModelState

    data class Unavailable(val why: ChatModelUnavailable) : ChatModelState
}

/**
 * The chat's reader on the phone: an ONNX encoder over one message, beside the rules and never
 * instead of them.
 *
 * One per opened chat, which [ChatModelReaders] hands out so that a rotation keeps it. [prepare]
 * when the chat opens starts the load in the background, on [io] — the calibration, the schema, the
 * tokenizer (parsed once per process), an ONNX Runtime session over the model mapped out of the
 * APK, and one short run to warm it — and [close] when the chat closes releases the session. Nothing
 * loads before a model is known to ship: with no manifest in the assets the tokenizer is not even
 * opened.
 *
 * [read] reads one message, once, when it is sent; the chat stores what it decided from the
 * reading with the model's version and never reads the message again. It waits at most
 * [READ_LIMIT_MILLIS], the "typing…" dots' patience, load included: past it the run is stopped
 * inside ONNX Runtime, not merely abandoned, and the turn is the rules'. A load or run that fails —
 * an exception, no memory, ONNX Runtime's native library failing to link — leaves the model off
 * until the chat opens again, its session released at once rather than held to the end of the
 * chat, and every unavailable path says why; nothing is thrown at the chat.
 *
 * Runs on [dispatcher], 1–4 intra-op threads, one read at a time; the same process-wide
 * OrtEnvironment as the label reader, on the ONNX Runtime tools/onnxruntime-android builds: no
 * telemetry is compiled in, so there is nothing to switch off here either (OnnxRuntimeBuildTest
 * keeps it so), and only the operators of its ops.config are — a model that needs another fails to
 * load on a phone alone, as LOAD_FAILED, since the JVM tests run the full desktop runtime. So
 * [report] is told every load and run that failed, with its reason: the process's readers write it
 * to logcat ([ChatModelReaders.of]), where a bug report shows which operator or library it was; a
 * reader made without one tells nobody. [loader] is the ONNX engine over [files] unless a test
 * stands another in.
 */
internal class ChatModelReader(
    private val files: ChatModelFiles,
    private val mode: ChatModelMode = ChatModelMode.build,
    private val allowSmoke: Boolean = BuildConfig.DEBUG,
    private val threads: Int = defaultThreads(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val report: (ChatModelUnavailable, Throwable) -> Unit = { _, _ -> },
    private val loader: () -> ChatModelLoad = { OnnxChatModelEngine.load(files, threads, allowSmoke) },
) : AutoCloseable {
    private val failureLog: MutableList<Throwable> = Collections.synchronizedList(ArrayList())

    /** What the reader launches of its own — the closing — records a failure instead of crashing the app. */
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure -> failureLog += failure },
    )
    private val loading = scope.async(io, start = CoroutineStart.LAZY) { load() }
    private val runs = Mutex()

    @Volatile
    private var closed = false

    /** Set once a run fails: the model is off until the next opening. Never for a slow phone. */
    private val stops = MutableStateFlow<ChatModelUnavailable?>(null)

    /**
     * Why the model turned itself off for this opening — a run failed — or null while it has not: what
     * the chat's line about the model goes by ([rememberChatModel]), so the line goes the moment the
     * model does, a run that failed in a read included. Never for a slow phone.
     */
    val stopped: StateFlow<ChatModelUnavailable?> = stops.asStateFlow()

    /** Whether the engine has been closed. Read and written only under [runs]. */
    private var released = false

    /** What the load came to, once it has: what [stateNow] tells without waiting. */
    @Volatile
    private var settled: ChatModelLoad? = null

    /** What went wrong, for tests; [report] is told the loads and runs among it. Never sent anywhere. */
    @get:VisibleForTesting
    val failures: List<Throwable> get() = synchronized(failureLog) { failureLog.toList() }

    /** A load or a run that failed: kept for [failures], and [report] told why the model is off. */
    private fun failed(why: ChatModelUnavailable, failure: Throwable) {
        failureLog += failure
        report(why, failure)
    }

    /** Starts loading in the background, as the chat opens. */
    fun prepare() {
        if (mode.reads && !closed) loading.start()
    }

    /** Whether the model is there to read, once loading has settled. */
    suspend fun state(): ChatModelState {
        val why = when {
            !mode.reads -> ChatModelUnavailable.DISABLED

            closed -> ChatModelUnavailable.CLOSED

            else -> when (val load = awaitLoad()) {
                is ChatModelLoad.Failed -> load.why
                is ChatModelLoad.Loaded -> stops.value ?: return load.engine.ready()
            }
        }
        return ChatModelState.Unavailable(why)
    }

    /**
     * [state] as far as it is known now, without waiting — null while the model loads: what the chat
     * page starts from in its first frame, so the line about the model stands from the first frame on.
     */
    fun stateNow(): ChatModelState? {
        if (!mode.reads) return ChatModelState.Unavailable(ChatModelUnavailable.DISABLED)
        if (closed) return ChatModelState.Unavailable(ChatModelUnavailable.CLOSED)
        return when (val load = settled) {
            null -> null
            is ChatModelLoad.Failed -> ChatModelState.Unavailable(load.why)
            is ChatModelLoad.Loaded -> stops.value?.let { ChatModelState.Unavailable(it) } ?: load.engine.ready()
        }
    }

    /** [text] read in [context], or why it was not within [limitMillis]. */
    suspend fun read(
        text: String,
        context: ChatReaderContext = ChatReaderContext(),
        limitMillis: Long = READ_LIMIT_MILLIS,
    ): ChatReadOutcome {
        if (!mode.reads) return ChatReadOutcome.Unavailable(ChatModelUnavailable.DISABLED)
        if (closed) return ChatReadOutcome.Unavailable(ChatModelUnavailable.CLOSED)
        // Off the caller's thread from the first piece: tokenising and the run.
        val outcome = withContext(dispatcher) {
            withTimeoutOrNull(limitMillis) {
                when (val load = awaitLoad()) {
                    is ChatModelLoad.Failed -> ChatReadOutcome.Unavailable(load.why)
                    is ChatModelLoad.Loaded -> runs.withLock { readLocked(load.engine, text, context) }
                }
            } ?: ChatReadOutcome.TimedOut
        }
        return outcome
    }

    /**
     * The chat's side of the boundary: whatever a read throws, the turn is the rules' and the
     * model stays off for this opening — except the caller's own cancellation, which goes on.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun readLocked(engine: ChatModelEngine, text: String, context: ChatReaderContext): ChatReadOutcome {
        (if (closed) ChatModelUnavailable.CLOSED else stops.value)?.let { return ChatReadOutcome.Unavailable(it) }
        return try {
            ChatReadOutcome.Read(terminable(engine, engine.prepare(text, context)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            stopLocked(ChatModelUnavailable.RUN_FAILED, failure, engine)
        } catch (failure: LinkageError) {
            // A native method that does not link fails on its first call, which may be here.
            stopLocked(ChatModelUnavailable.RUN_FAILED, failure, engine)
        } catch (failure: OutOfMemoryError) {
            stopLocked(ChatModelUnavailable.OUT_OF_MEMORY, failure, engine)
        }
    }

    /** Off for the rest of this opening, and the session with its memory goes now. Under [runs]. */
    private fun stopLocked(why: ChatModelUnavailable, failure: Throwable, engine: ChatModelEngine): ChatReadOutcome {
        stops.value = why
        failed(why, failure)
        release(engine)
        return ChatReadOutcome.Unavailable(why)
    }

    /** Closes [engine], once. Under [runs], so never beneath a read in progress. */
    @Suppress("TooGenericExceptionCaught")
    private fun release(engine: ChatModelEngine) {
        if (released) return
        released = true
        try {
            engine.close()
        } catch (failure: Exception) {
            failureLog += failure
        }
    }

    /**
     * Runs [input] and, should the caller stop waiting, stops ONNX Runtime itself through the run's
     * terminate flag: the run ends within one kernel instead of finishing a reading nobody will look
     * at while the next message waits behind it.
     *
     * The stopper starts here, before the run, and resumes on whichever thread cancels it — the
     * deadline's timer thread — so the flag is set the moment the limit passes, even while every
     * thread of [dispatcher] is busy or the one that would run it is the one blocked in the run.
     */
    private suspend fun terminable(engine: ChatModelEngine, input: ChatModelInput): ChatReading =
        OrtSession.RunOptions().use { options ->
            try {
                coroutineScope {
                    val stopper = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            options.setTerminate(true)
                        }
                    }
                    try {
                        engine.read(input, options)
                    } finally {
                        stopper.cancel()
                    }
                }
            } catch (failure: OrtException) {
                // A run this reader stopped ends as the cancellation it is, not as a failure.
                currentCoroutineContext().ensureActive()
                throw failure
            }
        }

    private suspend fun awaitLoad(): ChatModelLoad = try {
        loading.await()
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive() // the caller's own cancellation goes on as it was
        ChatModelLoad.Failed(ChatModelUnavailable.CLOSED, cancelled)
    }

    /** The load, whatever the loader throws — or fails to link — being a failure with its reason. */
    @Suppress("TooGenericExceptionCaught")
    private fun load(): ChatModelLoad {
        val load = try {
            loader()
        } catch (failure: Exception) {
            ChatModelLoad.Failed(ChatModelUnavailable.LOAD_FAILED, failure)
        } catch (failure: LinkageError) {
            ChatModelLoad.Failed(ChatModelUnavailable.LOAD_FAILED, failure)
        } catch (failure: OutOfMemoryError) {
            ChatModelLoad.Failed(ChatModelUnavailable.OUT_OF_MEMORY, failure)
        }
        if (load is ChatModelLoad.Failed) load.cause?.let { failed(load.why, it) }
        settled = load
        return load
    }

    /** Releases the engine once a read in progress has stopped; reads after this are [ChatModelUnavailable.CLOSED]. */
    override fun close() {
        if (closed) return
        closed = true
        if (!loading.isActive && !loading.isCompleted) {
            loading.cancel()
            return
        }
        scope.launch {
            val load = loading.await()
            if (load is ChatModelLoad.Loaded) runs.withLock { release(load.engine) }
        }
    }

    companion object {
        /** How long a sent message waits for its reading before the turn goes to the rules. */
        const val READ_LIMIT_MILLIS = 1_000L
        private const val MAX_THREADS = 4

        /** Half the cores, 1 to 4: ONNX Runtime's MLAS gains little past the big cores of a phone. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, MAX_THREADS)
    }
}

/**
 * The chat's reader for the whole process (the session lives as long as the
 * chat, not as long as a screen). A rotation recreates the activity and its composition, not the
 * chat, so the reader — its session over a 66 MB model, and its load — outlives the composition that asked for it:
 * [acquire] when the chat's screen appears, [release] when it goes or the app stops under it
 * ([rememberChatModel]), and the last release closes the reader [lingerMillis] later unless the chat
 * is back by then, as the rotated screen's new composition is within a moment.
 *
 * A read in progress belongs to whoever launched it: the chat launches the read of a sent message
 * in the page's scope that a rotation does not cancel (SymptomChatScreen.kt). Cancelled all the
 * same, the run stops inside ONNX Runtime and the reader stays whole for the next message.
 */
internal class ChatModelReaders(
    private val lingerMillis: Long = LINGER_MILLIS,
    private val create: () -> ChatModelReader,
) {
    private val timers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var reader: ChatModelReader? = null
    private var holders = 0
    private var closing: Job? = null

    /** The open chat's reader, loading in the background from its first [acquire]. */
    @Synchronized
    fun acquire(): ChatModelReader {
        closing?.cancel()
        closing = null
        holders++
        return reader ?: create().also {
            reader = it
            it.prepare()
        }
    }

    /**
     * The screen holding [held] has gone, or the app has stopped under it; the reader closes once no
     * screen has taken it back for a while.
     */
    @Synchronized
    fun release(held: ChatModelReader) {
        if (held !== reader || holders == 0) return
        holders--
        if (holders == 0) {
            closing = timers.launch {
                delay(lingerMillis)
                closeUnheld(held)
            }
        }
    }

    @Synchronized
    private fun closeUnheld(held: ChatModelReader) {
        if (holders > 0 || held !== reader) return
        reader = null
        held.close()
    }

    companion object {
        /** Long enough for a rotated screen to come back, short enough not to hold 100 MB after the chat. */
        const val LINGER_MILLIS = 5_000L

        private const val LOG_TAG = "ChatModel"

        @Volatile
        private var process: ChatModelReaders? = null

        /**
         * The process's readers: the APK's assets and the build's switch.
         * Smoke weights load only in a local model-test build ([allowExperimentalChatModel]). The
         * loader checks the model's byte count, the SHA-256 of the calibration, the schema and the
         * tokenizer, and the calibration's values; the SHA-256 of the model, too large to hash on
         * every opening of the chat, ChatModelAssetsTest holds.
         *
         * A load or a run that fails goes to logcat with its reason, the one place a failure that
         * only a phone has can be read afterwards. Such a failure names a node, an index or a
         * context token, never the words of the message being read (the throw sites of
         * ChatModelTokenizer, ChatReading and ChatJournal), and logcat leaves the phone only in a
         * bug report its owner chooses to send.
         */
        fun of(context: Context): ChatModelReaders = process ?: synchronized(this) {
            process ?: AssetChatModelFiles(context.applicationContext.assets).let { files ->
                ChatModelReaders {
                    ChatModelReader(
                        files,
                        allowSmoke = allowExperimentalChatModel(),
                        report = { why, failure -> Log.w(LOG_TAG, why.code, failure) },
                    )
                }
            }.also { process = it }
        }
    }
}

/** [this] engine loaded, as the reader's state says it: its model and enabled routing languages. */
private fun ChatModelEngine.ready(): ChatModelState = ChatModelState.Ready(model, languages)

/**
 * [this] reading as [manifest] enables it in the language the model is sure of: topic and entity
 * proposals in its routing languages ([ChatReading.examined]), journal proposals in its journal
 * languages. The one gate of the journal: no event of another language, or of an unsure one, goes on.
 */
internal fun ChatReading.enabledBy(manifest: ChatModelManifest): ChatReading = copy(
    examined = sureLanguage in manifest.routingLanguages,
    journal = journal.takeIf { sureLanguage in manifest.journalLanguages }.orEmpty(),
)

/** A message ready for the encoder: its pieces and the windows they are read in. */
internal class ChatModelInput(val text: String, val encoding: ChatEncoding, val windows: List<ChatWindow>)

internal sealed interface ChatModelLoad {
    class Loaded(val engine: ChatModelEngine) : ChatModelLoad

    class Failed(val why: ChatModelUnavailable, val cause: Throwable? = null) : ChatModelLoad
}

/**
 * A loaded reader: what turns a message into the encoder's input and reads it. Blocking;
 * [ChatModelReader] is what calls it off the main thread. [OnnxChatModelEngine] is the one the
 * phone runs; the interface is where a test stands another in.
 */
internal interface ChatModelEngine : AutoCloseable {
    /** The version every reading carries ([ChatModelManifest.model]). */
    val model: String

    /** Languages enabled for topic and entity proposals ([ChatModelManifest.routingLanguages]). */
    val languages: Set<String> get() = emptySet()

    fun prepare(text: String, context: ChatReaderContext): ChatModelInput

    /** [options] belong to this run: their terminate flag stops it, with an [OrtException]. */
    fun read(input: ChatModelInput, options: OrtSession.RunOptions? = null): ChatReading
}

/**
 * Two of ONNX Runtime's trade-offs, left at its defaults
 * (ChatModelLatencyTest measures both ways). [spinning]: the intra-op threads wait for the next
 * kernel by spinning — a run is faster, and the threads burn a moment of CPU after it. [arena]: the
 * CPU arena keeps the largest run's activations until the session closes instead of returning them
 * after every run.
 */
internal data class ChatModelTuning(val spinning: Boolean = true, val arena: Boolean = true)

/** How a load makes its session: ONNX Runtime's own unless a test watches what it makes. */
internal typealias ChatModelSessions = (OrtEnvironment, ByteBuffer, OrtSession.SessionOptions) -> OrtSession

private val ORT_SESSIONS: ChatModelSessions = { environment, model, options ->
    environment.createSession(model, options)
}

/** A load stopped for a reason the package itself gives, not for an error. */
private class ChatModelRefusal(val why: ChatModelUnavailable) : Exception(why.code)

private fun refuse(why: ChatModelUnavailable): Nothing = throw ChatModelRefusal(why)

/** A package that passed every check, its model ready for ONNX Runtime ([mapped] where it lies, or read). */
private class CheckedPackage(
    val manifest: ChatModelManifest,
    val calibration: ChatModelCalibration,
    val schema: ChatModelSchema,
    val tokenizer: ChatModelTokenizer,
    val model: ByteBuffer,
    val mapped: Boolean,
)

/** The reader over an ONNX Runtime session: tokenizer, encoder and the decoding into a [ChatReading]. */
internal class OnnxChatModelEngine private constructor(
    val manifest: ChatModelManifest,
    val tokenizer: ChatModelTokenizer,
    val schema: ChatModelSchema,
    val calibration: ChatModelCalibration,
    /** Whether the model was mapped out of its package rather than read into memory. */
    val mapped: Boolean,
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : ChatModelEngine {
    override val model: String get() = manifest.model

    override val languages: Set<String> get() = manifest.routingLanguages

    override fun prepare(text: String, context: ChatReaderContext): ChatModelInput {
        val encoding = tokenizer.encode(text)
        return ChatModelInput(text, encoding, tokenizer.windows(encoding, tokenizer.contextIds(context)))
    }

    override fun read(input: ChatModelInput, options: OrtSession.RunOptions?): ChatReading {
        val logits = input.windows.map { run(it, options) }
        return decodeReading(model, input.text, input.encoding, input.windows, logits, schema, calibration)
            .enabledBy(manifest)
    }

    /** One window through the encoder, at batch 1 as on the phone, every position attended. */
    fun run(window: ChatWindow, options: OrtSession.RunOptions? = null): WindowLogits {
        val positions = window.inputIds.size
        val shape = longArrayOf(1, positions.toLong())
        return OnnxTensor.createTensor(environment, LongBuffer.wrap(window.inputIds), shape).use { ids ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(positions) { 1L }), shape).use { mask ->
                val inputs = mapOf(INPUT_IDS to ids, ATTENTION_MASK to mask)
                val result = if (options == null) {
                    session.run(inputs, schema.outputNames.toSet())
                } else {
                    session.run(inputs, schema.outputNames.toSet(), options)
                }
                result.use {
                    WindowLogits(
                        topic = it.floats(TOPIC_LOGITS),
                        safety = it.floats(SAFETY_LOGITS),
                        meta = it.floats(META_LOGITS),
                        slot = it.floats(SLOT_LOGITS),
                        span = it.floats(SPAN_LOGITS),
                        positions = positions,
                        journal = if (schema.journal ==
                            null
                        ) {
                            emptyMap()
                        } else {
                            JOURNAL_OUTPUTS.associateWith { name -> it.floats(name) }
                        },
                    )
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"

        /** What the load reads to warm the session: any short message does. */
        private const val WARM_UP = "ok"

        /**
         * reader-tokenizer.json parsed, by the SHA-256 of the file it came from: the 70 925 pieces
         * are parsed and sorted once per process rather than on every opening of the chat. One entry,
         * the last tokenizer loaded; the file is still read and hashed on every load, against the manifest.
         */
        @Volatile
        private var parsed: Pair<String, ChatModelTokenizer>? = null

        /**
         * The reader [files] hold, or why not. Every failure is a [ChatModelLoad.Failed]; nothing is thrown, not
         * even what a broken package throws that nobody foresaw, nor ONNX Runtime's native library
         * failing to link. [newSession] is ONNX Runtime's, unless a test watches what it makes.
         */
        @Suppress("TooGenericExceptionCaught")
        fun load(
            files: ChatModelFiles,
            threads: Int,
            allowSmoke: Boolean,
            tuning: ChatModelTuning = ChatModelTuning(),
            newSession: ChatModelSessions = ORT_SESSIONS,
        ): ChatModelLoad =
            try {
                ChatModelLoad.Loaded(open(checked(files, allowSmoke), threads, tuning, newSession))
            } catch (refusal: ChatModelRefusal) {
                ChatModelLoad.Failed(refusal.why, refusal)
            } catch (failure: OrtException) {
                ChatModelLoad.Failed(ChatModelUnavailable.LOAD_FAILED, failure)
            } catch (failure: IOException) {
                ChatModelLoad.Failed(ChatModelUnavailable.LOAD_FAILED, failure)
            } catch (failure: JSONException) {
                ChatModelLoad.Failed(ChatModelUnavailable.MISMATCH, failure)
            } catch (failure: IllegalArgumentException) {
                ChatModelLoad.Failed(ChatModelUnavailable.MISMATCH, failure)
            } catch (failure: RuntimeException) {
                ChatModelLoad.Failed(ChatModelUnavailable.LOAD_FAILED, failure)
            } catch (failure: LinkageError) {
                // UnsatisfiedLinkError the first time libonnxruntime.so does not load, NoClassDefFoundError after.
                ChatModelLoad.Failed(ChatModelUnavailable.LOAD_FAILED, failure)
            } catch (failure: OutOfMemoryError) {
                ChatModelLoad.Failed(ChatModelUnavailable.OUT_OF_MEMORY, failure)
            }

        /**
         * The package's files, cheapest first: the manifest, then whether the model is there at all —
         * without one the tokenizer is never opened — then the calibration, the hashes, the schema and
         * the tokenizer, and only then the model's bytes, if it cannot be mapped and has to be read.
         *
         * The calibration is held to the manifest's SHA-256 as the schema and the tokenizer are: its
         * safety thresholds were fitted on this very model's predictions, and another model's file
         * would parse as well and raise the flags elsewhere. The model itself is held to its byte
         * count alone; hashing it would cost every opening of the chat a pass over the whole file.
         */
        private fun checked(files: ChatModelFiles, allowSmoke: Boolean): CheckedPackage {
            val manifest = ChatModelManifest.parse(files.need(CHAT_MODEL_MANIFEST).decodeToString())
            if (manifest.smoke && !allowSmoke) refuse(ChatModelUnavailable.SMOKE)
            val mapped = files.map(manifest.onnxFile)
            if (mapped == null && !files.has(manifest.onnxFile)) refuse(ChatModelUnavailable.NO_MODEL)
            val calibrationBytes = files.need(manifest.calibrationFile)
            if (sha256(calibrationBytes) != manifest.calibrationSha256) refuse(ChatModelUnavailable.MISMATCH)
            val calibration = ChatModelCalibration.parse(calibrationBytes.decodeToString())
            val schemaBytes = files.need(CHAT_MODEL_SCHEMA)
            val tokenizerBytes = files.need(CHAT_MODEL_TOKENIZER)
            val tokenizerSha = sha256(tokenizerBytes)
            if (sha256(schemaBytes) != manifest.schemaSha256 || tokenizerSha != manifest.tokenizerSha256) {
                refuse(ChatModelUnavailable.MISMATCH)
            }
            val schema = ChatModelSchema.parse(schemaBytes.decodeToString())
            if (schema.misfit() != null) refuse(ChatModelUnavailable.MISMATCH)
            val tokenizer = tokenizer(tokenizerSha, tokenizerBytes)
            val model = mapped ?: files.read(manifest.onnxFile, manifest.onnxBytes)
            if (model.remaining().toLong() != manifest.onnxBytes) refuse(ChatModelUnavailable.MISMATCH)
            return CheckedPackage(manifest, calibration, schema, tokenizer, model, mapped != null)
        }

        private fun tokenizer(sha: String, bytes: ByteArray): ChatModelTokenizer =
            parsed?.takeIf { it.first == sha }?.second
                ?: ChatModelTokenizer.parse(bytes.decodeToString()).also { parsed = sha to it }

        /**
         * The session over a checked package, warmed by one short read so that the first message a
         * person sends does not pay ONNX Runtime's first run inside its second. Whatever fails once
         * the session exists — the outputs that do not fit, the warm-up — closes it before the
         * failure goes on: a session nobody holds would keep the whole model in native memory for
         * the life of the process.
         */
        private fun open(
            checked: CheckedPackage,
            threads: Int,
            tuning: ChatModelTuning,
            newSession: ChatModelSessions,
        ): OnnxChatModelEngine {
            val environment = OrtEnvironment.getEnvironment()
            val session = sessionOptions(threads, tuning).use { newSession(environment, checked.model, it) }
            var kept = false
            try {
                if (!fits(session, checked.schema)) refuse(ChatModelUnavailable.MISMATCH)
                val engine = with(checked) {
                    OnnxChatModelEngine(manifest, tokenizer, schema, calibration, mapped, environment, session)
                }
                engine.read(engine.prepare(WARM_UP, ChatReaderContext()))
                kept = true
                return engine
            } finally {
                if (!kept) session.close()
            }
        }

        private fun sessionOptions(threads: Int, tuning: ChatModelTuning) = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (!tuning.spinning) addConfigEntry("session.intra_op.allow_spinning", "0")
            if (!tuning.arena) setCPUArenaAllocator(false)
        }

        /**
         * The session takes the two inputs and gives every output the schema names at its width
         * ([ChatModelSchema.outputNames]: the reader's five, and the journal's five beside them when it has one).
         */
        private fun fits(session: OrtSession, schema: ChatModelSchema): Boolean {
            val outputs = session.outputInfo
            return session.inputNames == setOf(INPUT_IDS, ATTENTION_MASK) &&
                schema.outputNames.all { name ->
                    val shape = (outputs[name]?.info as? TensorInfo)?.shape
                    shape != null && shape.last() == schema.outputSizes[name]?.toLong()
                }
        }

        /**
         * A model its package cannot map, read whole into a direct buffer for ONNX Runtime. On
         * Android a direct buffer is a non-movable array on the Java heap, so this costs the model's
         * size in heap for the load — the reason the APK stores the model for [ChatModelFiles.map].
         */
        private fun ChatModelFiles.read(name: String, bytes: Long): ByteBuffer =
            open(name)?.use { it.readDirect(bytes) } ?: refuse(ChatModelUnavailable.NO_MODEL)

        private fun ChatModelFiles.has(name: String): Boolean = open(name)?.use { true } ?: false

        private fun InputStream.readDirect(bytes: Long): ByteBuffer {
            if (bytes <= 0 || bytes > Int.MAX_VALUE) refuse(ChatModelUnavailable.MISMATCH)
            val buffer = ByteBuffer.allocateDirect(bytes.toInt())
            val channel = Channels.newChannel(this)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) refuse(ChatModelUnavailable.MISMATCH)
            }
            if (read() >= 0) refuse(ChatModelUnavailable.MISMATCH)
            buffer.flip()
            return buffer
        }

        private fun OrtSession.Result.floats(name: String): FloatArray {
            val buffer = (get(name).get() as OnnxTensor).floatBuffer
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }

        /** A file the package cannot do without: missing, it is no package at all. */
        private fun ChatModelFiles.need(name: String): ByteArray = bytes(name) ?: refuse(ChatModelUnavailable.NO_MODEL)
    }
}
