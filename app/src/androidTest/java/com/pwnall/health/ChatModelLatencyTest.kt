package com.pwnall.health

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * How fast the chat's model is on this device and how much memory it takes: the cold start of a
 * chat opening; the time per message of 16, 64, 128, 320 and 512 positions at 1, 2 and 4 threads,
 * plus 500 characters of Chinese, the longest message the chat takes in its longest script; the
 * peak memory — VmHWM and PSS, the Java and the native heap — after the load and after the first
 * 512-position read, against a limit of +150 MB; and, at the default thread count, ONNX
 * Runtime's two trade-offs the other way round ([ChatModelTuning]: no spinning, no arena). Whatever
 * model is in the app's assets is measured; with none there the test skips.
 *
 * A release build with R8 is what the numbers stand for; this runs in the debug one, and says so
 * (`debuggable`): ART runs a
 * debuggable app's Kotlin — the tokenizer, the decoding, the first opening's parse — slower than
 * a release build's; ONNX Runtime's own work, most of every number here, is native code and the
 * same in both. Measure on real phones: an emulator on Apple silicon says nothing of them.
 *
 * A measurement, not a gate: it asserts only that the model loads, mapped out of the APK, and
 * reads. To measure a smoke-run model, put its package into the assets of a debug build and run
 * `./gradlew
 * :app:connectedSideloadDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.pwnall.health.ChatModelLatencyTest`. The
 * numbers arrive as instrumentation status lines, in logcat (System.out, "ChatModelLatency …") and in
 * chat-model-latency.json in the app's external files folder (`adb pull`). Never commit the smoke
 * model: ChatModelAssetsTest fails while it is there.
 */
@RunWith(AndroidJUnit4::class)
class ChatModelLatencyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = AssetChatModelFiles(context.assets)
    private val results = JSONObject()

    @Test
    fun measureTheModelInTheAssets() = runBlocking {
        assumeTrue("no chat model in the assets", files.open(CHAT_MODEL_MANIFEST)?.use { true } == true)
        val manifest = ChatModelManifest.parse(requireNotNull(files.text(CHAT_MODEL_MANIFEST)))
        report("model", manifest.model)
        report("model_bytes", manifest.onnxBytes)
        report("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        report("abi", Build.SUPPORTED_ABIS.first())
        report("is_64_bit", Process.is64Bit())
        report("cores", Runtime.getRuntime().availableProcessors())
        report("debuggable", (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        // VmHWM is the process's peak since it started, unless the kernel lets it be reset here.
        report("peak_reset", Memory.resetPeak())

        val tokenizer = ChatModelTokenizer.parse(requireNotNull(files.text(CHAT_MODEL_TOKENIZER)))
        coldStart(textOf(tokenizer, LONGEST - SPECIALS))
        val cores = Runtime.getRuntime().availableProcessors()
        for (threads in listOf(1, 2, 4).filter { it <= cores }) {
            measure("t$threads", threads, ChatModelTuning(), POSITIONS)
        }
        val threads = ChatModelReader.defaultThreads()
        measure("t$threads.no_spinning", threads, ChatModelTuning(spinning = false), TUNED_POSITIONS)
        measure("t$threads.no_arena", threads, ChatModelTuning(arena = false), TUNED_POSITIONS)

        val out = File(context.getExternalFilesDir(null) ?: context.filesDir, "chat-model-latency.json")
        out.writeText(results.toString(1))
        println("$TAG $results")
    }

    /** The production path: a reader as the chat makes it, from prepare() to ready, and its first long read. */
    private suspend fun coldStart(longest: String) {
        val opens = (1..COLD_OPENS).map {
            val before = Memory.now()
            val started = System.nanoTime()
            val reader = ChatModelReader(files, mode = ChatModelMode.ON, allowSmoke = true)
            reader.prepare()
            val state = reader.state()
            val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
            assertTrue("the model does not load: $state ${reader.failures}", state is ChatModelState.Ready)
            if (it == 1) {
                memory("open.loaded", before)
                val first = reader.read(longest, limitMillis = Long.MAX_VALUE)
                assertTrue(first is ChatReadOutcome.Read)
                memory("open.read_$LONGEST", before)
            }
            reader.close()
            millis
        }
        report("cold_start_ms", opens.joinToString(","))
        report("cold_start_median_ms", opens.sorted()[opens.size / 2])
    }

    /** One session at [threads] and [tuning]: its load, then every length of [positions] read again and again. */
    private fun measure(name: String, threads: Int, tuning: ChatModelTuning, positions: List<Int>) {
        val before = Memory.now()
        val started = System.nanoTime()
        val load = OnnxChatModelEngine.load(files, threads, allowSmoke = true, tuning = tuning)
        val loadMillis = (System.nanoTime() - started) / NANOS_PER_MILLI
        val engine = when (load) {
            is ChatModelLoad.Loaded -> load.engine as OnnxChatModelEngine
            is ChatModelLoad.Failed -> throw AssertionError("the model does not load: ${load.why}", load.cause)
        }
        assertTrue("the model was read, not mapped: is reader.onnx stored compressed?", engine.mapped)
        report("$name.load_ms", loadMillis)
        val messages = positions.associate { n -> "$n" to textOf(engine.tokenizer, n - SPECIALS) } +
            if (positions == POSITIONS) mapOf("zh500" to CHINESE.repeat(ZH_REPEAT).take(ZH_CHARS)) else emptyMap()
        for ((length, text) in messages) {
            val input = engine.prepare(text, ChatReaderContext())
            if (length != "zh500") assertEquals(length.toInt(), input.windows.single().inputIds.size)
            val first = timed { engine.read(input) }
            repeat(WARM_UP) { engine.read(input) }
            val times = (1..RUNS).map { timed { engine.read(engine.prepare(text, ChatReaderContext())) } }.sorted()
            val tokenize = (1..RUNS).map { timed { engine.prepare(text, ChatReaderContext()) } }.sorted()
            report("$name.$length.windows", input.windows.size)
            report("$name.$length.positions", input.windows.sumOf { it.inputIds.size })
            report("$name.$length.first_ms", first)
            report("$name.$length.p50_ms", times[times.size / 2])
            report("$name.$length.p95_ms", times[(times.size * 95 + 99) / 100 - 1])
            report("$name.$length.max_ms", times.last())
            report("$name.$length.tokenize_p50_ms", tokenize[tokenize.size / 2])
        }
        memory("$name.after_reads", before)
        engine.close()
    }

    /** A message of exactly [pieces] pieces: whole words of an ordinary sentence, topped up with "a". */
    private fun textOf(tokenizer: ChatModelTokenizer, pieces: Int): String {
        val words = FILLER.split(' ')
        var text = ""
        var i = 0
        while (true) {
            val next = if (text.isEmpty()) words[0] else "$text ${words[i % words.size]}"
            if (tokenizer.encode(next).size > pieces) break
            text = next
            i++
        }
        while (tokenizer.encode(text).size < pieces) text += " a"
        assertEquals(pieces, tokenizer.encode(text).size)
        return text
    }

    private inline fun timed(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / NANOS_PER_MILLI.toDouble()
    }

    /** The memory at [at], and how much it grew from [before]: VmHWM is the peak, the rest what is held now. */
    private fun memory(at: String, before: Memory) {
        val now = Memory.now()
        report("$at.vm_hwm_mb", now.peak)
        report("$at.vm_rss_mb", now.resident)
        report("$at.pss_mb", now.pss)
        report("$at.peak_over_start_mb", now.peak - before.resident)
        report("$at.pss_growth_mb", now.pss - before.pss)
        report("$at.java_heap_growth_mb", now.javaHeap - before.javaHeap)
        report("$at.native_heap_growth_mb", now.nativeHeap - before.nativeHeap)
    }

    private fun report(key: String, value: Any) {
        val shown = if (value is Double) Math.round(value * HUNDRED) / HUNDRED else value
        results.put(key, shown)
        println("$TAG $key=$shown") // logcat, under System.out
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, shown.toString()) })
    }

    /** The process's memory in MB, as the kernel (VmHWM, VmRSS), the PSS and ART count it; the Java heap after a GC. */
    private class Memory(
        val peak: Double,
        val resident: Double,
        val pss: Double,
        val javaHeap: Double,
        val nativeHeap: Double,
    ) {
        companion object {
            fun now(): Memory {
                val runtime = Runtime.getRuntime()
                repeat(2) { runtime.gc() }
                val status = File("/proc/self/status").readLines()
                fun kb(key: String) = status.firstOrNull { it.startsWith("$key:") }
                    ?.split(Regex("\\s+"))?.getOrNull(1)?.toDoubleOrNull() ?: Double.NaN
                return Memory(
                    peak = kb("VmHWM") / KB,
                    resident = kb("VmRSS") / KB,
                    pss = Debug.getPss() / KB,
                    javaHeap = (runtime.totalMemory() - runtime.freeMemory()) / MB,
                    nativeHeap = Debug.getNativeHeapAllocatedSize() / MB,
                )
            }

            /** Resets VmHWM to the current RSS, where the kernel and SELinux let an app write clear_refs. */
            fun resetPeak(): Boolean = try {
                File("/proc/self/clear_refs").writeText("5")
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    private companion object {
        const val TAG = "ChatModelLatency"
        const val NANOS_PER_MILLI = 1_000_000L
        const val MB = 1_048_576.0
        const val KB = 1_024.0
        const val HUNDRED = 100.0
        const val COLD_OPENS = 3
        const val WARM_UP = 3
        const val RUNS = 20

        /** [<s>] [W] [S] [T] … [</s>]: a window's positions beyond the message's pieces. */
        const val SPECIALS = 5
        const val LONGEST = 512

        /** The lengths measured; the last is one whole window, the longest a single run gets. */
        val POSITIONS = listOf(16, 64, 128, 320, LONGEST)
        val TUNED_POSITIONS = listOf(64, LONGEST)
        const val FILLER = "My right knee has hurt since Monday and it gets worse when I climb the stairs at work, " +
            "so I took two paracetamol this morning but it did not help much and now my back hurts too"
        const val CHINESE = "今天工作非常忙，老板一直在催项目的进度，同事们也都很累。"
        const val ZH_REPEAT = 30
        const val ZH_CHARS = 500
    }
}
