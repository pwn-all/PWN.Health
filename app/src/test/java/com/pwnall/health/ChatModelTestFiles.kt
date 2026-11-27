package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Where the chat reader's files are for the JVM tests, which run with the module directory as their
 * working directory: the app's own assets (no copies), the reference files and goldens the model's
 * own tooling wrote (test resources, chat-reference), and the tiny fixture's package beside them.
 */
internal object ChatModelTestFiles {
    val assets: File = firstDirectory("src/main/assets/chat", "app/src/main/assets/chat")
    val ml: File = firstDirectory("src/test/resources/chat-reference", "app/src/test/resources/chat-reference")
    val fixture: File = firstDirectory("src/test/resources/chat-reader", "app/src/test/resources/chat-reader")

    val tokenizer: ChatModelTokenizer by lazy {
        ChatModelTokenizer.parse(referenceFile(CHAT_MODEL_TOKENIZER).readText())
    }

    fun referenceFile(name: String): File = when (name) {
        CHAT_MODEL_TOKENIZER -> File(ml, "tokenizer/reader-tokenizer.json")
        CHAT_MODEL_SCHEMA -> File(ml, "schema.json")
        else -> error("No fixture reference for $name")
    }

    /** The fixture keeps its original tokenizer and schema when the shipped model changes. */
    fun fixtureFiles(opened: MutableList<String>? = null): ChatModelFiles = files(listOf(fixture, assets), opened)

    /**
     * Files looked up in [dirs] in order, mapped where [mapped] as the APK's stored assets are;
     * every name asked for, to open or to map, goes into [opened].
     */
    fun files(dirs: List<File>, opened: MutableList<String>? = null, mapped: Boolean = true): ChatModelFiles =
        object : ChatModelFiles {
            override fun open(name: String): InputStream? = find(name)?.inputStream()

            override fun map(name: String): ByteBuffer? = if (!mapped) {
                null
            } else {
                find(name)?.let { file ->
                    FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                        channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    }
                }
            }

            private fun find(name: String): File? {
                synchronized(this@ChatModelTestFiles) { opened?.add(name) }
                return dirs.map {
                    if (it == assets && fixture in dirs && name in listOf(CHAT_MODEL_TOKENIZER, CHAT_MODEL_SCHEMA)) {
                        referenceFile(name)
                    } else {
                        File(it, name)
                    }
                }.firstOrNull(File::isFile)
            }
        }

    fun json(file: File): JSONObject = JSONObject(file.readText())

    private fun firstDirectory(vararg paths: String): File = paths.map(::File).first(File::isDirectory)
}

/**
 * A read that waits as long as a busy test machine needs. The one-second limit is the phone's
 * patience with a message; a test that wants the reading, not the deadline, must not lose it to a
 * cold JVM loading ONNX Runtime while the other tests run.
 */
internal suspend fun ChatModelReader.readPatiently(text: String, context: ChatReaderContext = ChatReaderContext()) =
    read(text, context, limitMillis = 60_000)

internal fun JSONArray.intArray(): IntArray = IntArray(length()) { getInt(it) }

internal fun JSONArray.doubleArray(): DoubleArray = DoubleArray(length()) { getDouble(it) }

/** The keys, as a set (the org.json the tests compile against, Android's, has no keySet()). */
internal fun JSONObject.keyNames(): Set<String> = keys().asSequence().toCollection(LinkedHashSet())

internal fun JSONArray.jsonObjects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/** A golden's context, as the app would hand it to the reader. */
internal fun JSONObject.readerContext(): ChatReaderContext = ChatReaderContext(
    where = ChatReaderWhere.valueOf(getString("where")),
    step = optString("step").takeIf { !isNull("step") && it.isNotEmpty() }?.let(SymptomStep::valueOf),
    topic = optString("thread_topic").takeIf { !isNull("thread_topic") && it.isNotEmpty() }?.let(Symptom::valueOf),
)
