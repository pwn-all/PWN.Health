package com.pwnall.health

import ai.onnxruntime.OrtSession
import java.util.concurrent.atomic.AtomicInteger

// The chat with its model, for the tests: a message sent as the page sends it — kept, read once by
// the model, its reading kept with it, the log settled — through the tiny fixture forced on (its
// random weights read anything into anything, which is the point: whatever a model reads, the rules
// stay the floor) or through an engine a test scripts.

/** The model a test names in the readings it makes by hand. */
internal const val TEST_MODEL = "chat-reader test"

/** A reading by hand: the model named, the decisions [build] gives. */
internal fun chatRead(build: ChatRead.() -> ChatRead = { this }): ChatRead = ChatRead(model = TEST_MODEL).build()

/**
 * The tiny fixture, forced on as no app ever runs it (a smoke model, which the chat's own reader
 * refuses): one thread, patience for a busy test machine. Its package names no language its exam was
 * passed in — it never sat one — so it reads here as one that passed in every language, every
 * reading of it [ChatReading.examined]: the point is what any reading does to the chat.
 */
internal fun tinyReader(): ChatModelReader {
    val files = ChatModelTestFiles.fixtureFiles()
    return ChatModelReader(files, mode = ChatModelMode.ON, allowSmoke = true, threads = 1) {
        when (val load = OnnxChatModelEngine.load(files, 1, true)) {
            is ChatModelLoad.Loaded -> ChatModelLoad.Loaded(EveryLanguage(load.engine))
            is ChatModelLoad.Failed -> load
        }
    }
}

/** [engine], its every reading of a language the exam was passed in. */
private class EveryLanguage(private val engine: ChatModelEngine) : ChatModelEngine by engine {
    override fun read(input: ChatModelInput, options: OrtSession.RunOptions?): ChatReading =
        engine.read(input, options).copy(examined = true)
}

/** [text] read by [reader] as the page reads a message it sends, where [log] stands: the decisions it keeps. */
internal suspend fun ChatLog.readBy(reader: ChatModelReader, text: String, setting: ChatSetting): ChatRead? =
    reader.decide(text, readerContext(setting), setting.day, setting.past, routes = true, limitMillis = 60_000)

/**
 * Whether the page's turn waits for [reader]'s reading of [text] sent where [this] log stands — as a
 * message, or [typed] as an answer ([readPlan]).
 */
private suspend fun ChatLog.waitsFor(
    reader: ChatModelReader,
    text: String,
    setting: ChatSetting,
    typed: Boolean = false,
): Boolean =
    readPlan(text, this, setting, reader.state() !is ChatModelState.Unavailable, routes = true, typed) == ReadPlan.HOLD

/**
 * [text] sent as the page sends it with a model: kept at once — its thread routed then only when the
 * turn does not wait for the reading, as for words the rules find urgent — then read, its reading
 * kept where the message stands, and settled again, routed with it.
 */
internal suspend fun ChatLog.sentWith(
    reader: ChatModelReader,
    text: String,
    setting: ChatSetting = chatSetting(),
): ChatLog = sentRead(text, readBy(reader, text, setting), setting, waitsFor(reader, text, setting))

/**
 * [text] sent as the page sends it, its reading having come to [read]: kept at once — held for the
 * reading, its thread not routed, when the turn [holds] for it (as it does with a model there, for words
 * the rules do not find urgent), else routed then — then the reading kept where the message stands,
 * and settled again, routed with it.
 */
internal fun ChatLog.sentRead(
    text: String,
    read: ChatRead?,
    setting: ChatSetting = chatSetting(),
    holds: Boolean = readPlan(text, this, setting, reader = true, routes = true) == ReadPlan.HOLD,
): ChatLog {
    val written = wrote(text)
    val place = written.lastMessagePlace() ?: return this
    val kept = settledLog(if (holds) written.copy(held = ChatHeld.Message(place)) else written, setting)
    return kept.readDone(SentWords.Message(text, place), read, setting)?.settle(setting) ?: kept
}

/**
 * [text] typed as the answer to [step] as the page takes it with a model: held while it is read, then
 * taken with its reading ([typedLast]) — or, for words the rules find urgent, taken at once, and read
 * after as the message they then are.
 */
internal suspend fun ChatLog.typedWith(
    reader: ChatModelReader,
    step: SymptomStep,
    text: String,
    setting: ChatSetting = chatSetting(),
): ChatLog {
    if (waitsFor(reader, text, setting, typed = true)) {
        val read = readBy(reader, text, setting)
        val held = copy(held = ChatHeld.Typed(step, text)).settle(setting)
        return checkNotNull(held.readDone(SentWords.Typed(text, step), read, setting)).settle(setting)
    }
    val next = typedLast(step, text, setting).settle(setting)
    val place = next.lastMessagePlace()?.takeIf { next.messageCount() > messageCount() }
    val read = place?.let { readBy(reader, text, setting) }
    val done = if (place == null || read == null) null else next.readDone(SentWords.Message(text, place), read, setting)
    return done?.settle(setting) ?: next
}

/** [text] written with [read] already kept with it, and settled: a message read as it was sent. */
internal fun ChatLog.wroteRead(text: String, read: ChatRead?, setting: ChatSetting = chatSetting()): ChatLog =
    wrote(text, read).settle(setting)

/**
 * An engine that reads every message as [reading] says, counting its reads: what shows that a
 * message is read once, when it is sent, and never again.
 */
internal class ScriptedEngine(private val reading: (String) -> ChatReading) : ChatModelEngine {
    val reads = AtomicInteger()

    override val model: String = TEST_MODEL

    override fun prepare(text: String, context: ChatReaderContext): ChatModelInput =
        ChatModelInput(text, ChatEncoding(IntArray(0), emptyList(), IntArray(0), IntArray(0)), emptyList())

    override fun read(input: ChatModelInput, options: OrtSession.RunOptions?): ChatReading {
        reads.incrementAndGet()
        return reading(input.text)
    }

    override fun close() = Unit
}

/** A reader over [engine], on. */
internal fun scriptedReader(engine: ChatModelEngine): ChatModelReader =
    ChatModelReader({ null }, mode = ChatModelMode.ON, loader = { ChatModelLoad.Loaded(engine) })

/**
 * A reading as the decoder makes one: [topics] at their p, a button at 0.5 and an addition where
 * [added]; [flags] raised over the start threshold, the rest well under; [spans] and [heads] as given;
 * of a language the model passed its exam on.
 */
@Suppress("LongParameterList") // A reading is many heads; the test names the few it needs.
internal fun readingOf(
    topics: Map<Symptom, Double> = emptyMap(),
    added: Set<Symptom> = emptySet(),
    flags: Set<ReaderSafety> = emptySet(),
    unwell: Double = 0.0,
    about: String = "SELF",
    mood: ReaderLabel? = null,
    oos: Double = 0.0,
    spans: List<ReaderSpan> = emptyList(),
    heads: Map<String, Map<String, Double>> = emptyMap(),
): ChatReading {
    val abstain = oos >= ReaderRules().oos || (topics.values + unwell).all { it < ReaderRules().abstainTopicsBelow }
    return ChatReading(
        model = TEST_MODEL,
        lang = ReaderLabel("en", 1.0),
        topics = Symptom.entries.map { topic ->
            val p = topics[topic] ?: 0.0
            ReaderTopic(topic, p, button = p >= 0.5, add = topic in added)
        },
        unwell = ReaderUnwell(unwell, button = unwell >= 0.5, body = 0.0, mind = 0.0, lean = null),
        safety = ReaderSafety.entries.map { ReaderFlag(it, if (it in flags) 0.9 else 0.01, 0.25) },
        about = ReaderLabel(about, 0.9),
        question = 0.0,
        smallTalk = null,
        mood = mood,
        oos = oos,
        abstain = abstain,
        spans = spans,
        heads = heads,
        rules = ReaderRules(),
        tokens = 1,
        windows = 1,
        examined = true,
    )
}

/** A span of [kind] over [text], each attribute head's labels at the probabilities given. */
internal fun spanOf(
    kind: ReaderSpanKind,
    text: String,
    vararg attributes: Pair<String, Map<String, Double>>,
): ReaderSpan =
    ReaderSpan(kind, 0, 1, 0, text.length, text, attributes.toMap())

/** One label of a softmax head, sure. */
internal fun sure(label: String): Map<String, Double> = mapOf(label to 0.95, "NONE" to 0.05)
