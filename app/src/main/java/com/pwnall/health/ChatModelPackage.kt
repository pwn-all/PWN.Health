package com.pwnall.health

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * The chat reader's files, under assets/chat in the main source set so the Play bundle and every
 * sideload APK carry the same ones (no asset pack, nothing downloaded):
 *
 * - reader-tokenizer.json — the pruned XLM-R tokenizer, always shipped;
 * - reader-schema.json — every label the reader outputs and where it sits in the outputs,
 *   always shipped;
 * - reader-manifest.json — the model that ships, when one does: its file, its version (stored with
 *   every reading), its size and SHA-256, its calibration, and the SHA-256 of the tokenizer and
 *   schema it was trained with; written when the model is packaged, the languages its
 *   readings route in (`routing_languages`) added at release (docs/MODELS.md);
 * - the model (reader.onnx, int8) and its calibration (reader-calibration.json) beside it.
 *
 * Without the manifest there is no model, and the chat reads with its rules alone. The two
 * always-shipped files are byte-for-byte the model's own; ChatModelAssetsTest holds them to
 * it, and to the manifest's hashes when a model ships.
 */
internal fun interface ChatModelFiles {
    /** [name] in the reader's folder, open for reading, or null when the package has no such file. */
    fun open(name: String): InputStream?

    /**
     * [name] mapped into memory where it lies, or null when this package cannot map it: the reader
     * then reads it through [open], into a direct buffer — which on Android is the Java heap.
     */
    fun map(name: String): ByteBuffer? = null
}

internal const val CHAT_MODEL_ASSETS = "chat"
internal const val CHAT_MODEL_MANIFEST = "reader-manifest.json"
internal const val CHAT_MODEL_TOKENIZER = "reader-tokenizer.json"
internal const val CHAT_MODEL_SCHEMA = "reader-schema.json"

/** The reader's files from the APK's assets. */
internal class AssetChatModelFiles(private val assets: AssetManager) : ChatModelFiles {
    override fun open(name: String): InputStream? = try {
        assets.open("$CHAT_MODEL_ASSETS/$name")
    } catch (_: FileNotFoundException) {
        null
    }

    /**
     * Straight out of the APK, as the dish classifier's model is: the reader's 66 MB stay file-backed pages
     * the system can drop, off the Java heap, with no inflate on every opening of the chat. Only a
     * file stored uncompressed can be mapped, and app/build.gradle.kts keeps reader.onnx so; a
     * compressed or a missing one is null here, and the reader falls back to [open].
     */
    override fun map(name: String): ByteBuffer? = try {
        assets.openFd("$CHAT_MODEL_ASSETS/$name").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }
    } catch (_: FileNotFoundException) {
        null
    }
}

internal fun ChatModelFiles.bytes(name: String): ByteArray? = open(name)?.use { it.readBytes() }

internal fun ChatModelFiles.text(name: String): String? = bytes(name)?.decodeToString()

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * reader-manifest.json: which model ships and what it was made with.
 *
 * [smoke] marks a smoke-test model, trained on a toy set: it
 * must never reach anyone, so only a debuggable build loads one — for the latency measurement — and
 * ChatModelAssetsTest fails while one sits in the assets.
 *
 * What a loaded model may do is decided by [smoke] and the two language lists, [routingLanguages]
 * and [journalLanguages], and by nothing else in the file: the package that ships proposes topics,
 * entities and journal forms in all 16 languages by its `routing_languages`, while [languages]
 * names none. ShippedChatModelTest holds both lists, so neither moves unnoticed.
 */
internal class ChatModelManifest(
    val model: String,
    val smoke: Boolean,
    val onnxFile: String,
    val onnxBytes: Long,
    val onnxSha256: String,
    val calibrationFile: String,
    val calibrationSha256: String,
    val tokenizerSha256: String,
    val schemaSha256: String,
    private val languagePolicy: ChatModelLanguages = ChatModelLanguages(),
) {
    /** Training exam results, independent of the app's enabled [routingLanguages]. */
    val languages: Set<String> get() = languagePolicy.examined

    /**
     * Languages enabled for topic and entity proposals in this app. A package without the list
     * defaults to its examined languages; an explicit list enables languages independently of the exam.
     * The language-confidence, abstention and calibrated proposal thresholds still apply.
     */
    val routingLanguages: Set<String> get() = languagePolicy.routing

    /**
     * Languages enabled for journal proposals, by the journal heads' own release gate rather than the
     * exam of [languages]. A package without the list offers them where it routes ([routingLanguages]).
     */
    val journalLanguages: Set<String> get() = languagePolicy.journal

    companion object {
        const val FORMAT = "chat-reader-manifest"
        const val FORMAT_VERSION = 1

        private fun languageSet(array: JSONArray): Set<String> =
            (0 until array.length()).mapNotNullTo(HashSet()) { at ->
                (array.opt(at) as? String)?.takeIf { it.isNotEmpty() }
            }

        fun parse(json: String): ChatModelManifest {
            val root = JSONObject(json)
            require(root.getString("format") == FORMAT && root.getInt("version") == FORMAT_VERSION) {
                "not a chat-reader manifest"
            }
            val onnx = root.getJSONObject("onnx")
            val calibration = root.getJSONObject("calibration")
            val languages = root.optJSONArray("languages")?.let(::languageSet).orEmpty()
            val routing = root.optJSONArray("routing_languages")?.let(::languageSet) ?: languages
            return ChatModelManifest(
                model = root.getString("model"),
                smoke = root.getBoolean("smoke"),
                onnxFile = onnx.getString("file"),
                onnxBytes = onnx.getLong("bytes"),
                onnxSha256 = onnx.getString("sha256"),
                calibrationFile = calibration.getString("file"),
                calibrationSha256 = calibration.getString("sha256"),
                tokenizerSha256 = root.getString("tokenizer_sha256"),
                schemaSha256 = root.getString("schema_sha256"),
                languagePolicy = ChatModelLanguages(
                    examined = languages,
                    routing = routing,
                    journal = root.optJSONArray("journal_languages")?.let(::languageSet) ?: routing,
                ),
            )
        }
    }
}

/**
 * The training exam result and the app's independent permissions: to offer topics and entities, and
 * to offer journal forms.
 */
internal class ChatModelLanguages(
    val examined: Set<String> = emptySet(),
    val routing: Set<String> = examined,
    val journal: Set<String> = routing,
)

internal enum class HeadKind { MULTILABEL, SOFTMAX, BINARY }

internal enum class WindowCombine { MAX, MIN, MEAN }

/**
 * One head of the reader: its labels and where they sit in its output. A message head says how
 * its windows combine; a span head ([appliesTo] not empty) which span kinds it describes.
 */
internal class ReaderHead(
    val name: String,
    val kind: HeadKind,
    val labels: List<String>,
    val output: String,
    val offset: Int,
    val combine: WindowCombine,
    val appliesTo: Set<String>,
) {
    val size: Int get() = labels.size
}

/** reader-schema.json, the labels as generated from this app's code. */
internal class ChatModelSchema(
    val version: Int,
    val messageHeads: List<ReaderHead>,
    val spanHeads: List<ReaderHead>,
    val kinds: List<String>,
    val tags: List<String>,
    val outputSizes: Map<String, Int>,
    val safetySteps: Map<String, String>,
    val journal: ChatJournalSchema? = null,
) {
    val outputNames: List<String> get() = READER_OUTPUTS + if (journal == null) emptyList() else JOURNAL_OUTPUTS
    fun head(name: String): ReaderHead =
        requireNotNull(messageHeads.firstOrNull { it.name == name }) { "the reader's schema has no head $name" }

    /**
     * Why this schema does not fit the app's code, or null when it does: the topics must be
     * [Symptom]'s in order and UNWELL, the safety flags [ReaderSafety]'s with their steps, the span
     * kinds [ReaderSpanKind]'s, and every head the reading is built from must be there and inside
     * its output. A model trained before the app's labels moved reads into the wrong boxes; it is
     * refused, and the chat keeps to its rules.
     */
    fun misfit(): String? = labelMisfit() ?: layoutMisfit()

    private fun labelMisfit(): String? {
        val topics = messageHeads.firstOrNull { it.name == "topics" }?.labels
        val safety = messageHeads.firstOrNull { it.name == "safety" }?.labels
        return when {
            version != SCHEMA_VERSION -> "schema version $version"

            topics != Symptom.entries.map { it.name } + "UNWELL" -> "topics $topics"

            safety != ReaderSafety.entries.map { it.name } -> "safety flags $safety"

            ReaderSafety.entries.any { safetySteps[it.name] != it.step.name } -> "safety steps $safetySteps"

            kinds != ReaderSpanKind.entries.map { it.name } &&
                kinds != ReaderSpanKind.entries.takeWhile {
                    it != ReaderSpanKind.FOOD
                }.map { it.name } -> "span kinds $kinds"

            tags != listOf("O") + kinds.flatMap { listOf("B-$it", "I-$it") } -> "tags $tags"

            else -> null
        }
    }

    private fun layoutMisfit(): String? {
        val names = messageHeads.map { it.name }.toSet()
        val inside = (messageHeads + spanHeads).all { head ->
            head.offset >= 0 && head.offset + head.size <= (outputSizes[head.output] ?: -1)
        }
        val lean = messageHeads.firstOrNull { it.name == "unwell_lean" }?.labels
        return when {
            !names.containsAll(REQUIRED_HEADS) -> "heads $names"
            lean != UnwellLean.entries.map { it.name } -> "unwell lean $lean"
            !inside || READER_OUTPUTS.any { it !in outputSizes } -> "outputs $outputSizes"
            spanHeads.any { !kinds.containsAll(it.appliesTo) } -> "span heads"
            journal?.fits(outputSizes) == false -> "journal outputs $outputSizes"
            else -> null
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun parse(json: String): ChatModelSchema {
            val root = JSONObject(json)
            val outputs = root.getJSONObject("outputs")
            val offsets = HashMap<String, Int>()
            val sizes = HashMap<String, Int>()
            for (output in outputs.keys()) {
                val spec = outputs.getJSONObject(output)
                sizes[output] = spec.getInt("size")
                val layout = spec.getJSONArray("layout")
                for (i in 0 until layout.length()) {
                    val entry = layout.getJSONObject(i)
                    offsets["$output/${entry.getString("head")}"] = entry.getInt("offset")
                }
            }
            val messageJson = root.getJSONArray("message_heads")
            val messageHeads = messageJson.objects().map { head ->
                val combine = WindowCombine.valueOf(head.getString("window_combine").uppercase())
                head(head, head.getString("output"), offsets, combine)
            }
            val spanHeads = root.getJSONArray("span_heads").objects().map { span ->
                head(span, SPAN_LOGITS, offsets, WindowCombine.MEAN)
            }
            val slots = root.getJSONObject("slots")
            val safety = requireNotNull(messageJson.objects().firstOrNull { it.getString("name") == "safety" }) {
                "the reader's schema has no safety head"
            }
            val steps = safety.getJSONObject("adds_step")
            return ChatModelSchema(
                version = root.getInt("schema_version"),
                messageHeads = messageHeads,
                spanHeads = spanHeads,
                kinds = slots.getJSONArray("kinds").stringList(),
                tags = slots.getJSONArray("tags").stringList(),
                outputSizes = sizes,
                safetySteps = steps.keys().asSequence().associateWith(steps::getString),
                journal = root.optJSONObject("journal")?.let { ChatJournalSchema.parse(it, outputs) },
            )
        }

        private fun head(
            json: JSONObject,
            output: String,
            offsets: Map<String, Int>,
            combine: WindowCombine,
        ): ReaderHead {
            val name = json.getString("name")
            return ReaderHead(
                name = name,
                kind = HeadKind.valueOf(json.getString("kind").uppercase()),
                labels = json.getJSONArray("labels").stringList(),
                output = output,
                offset = requireNotNull(offsets["$output/$name"]) { "head $name has no place in $output" },
                combine = combine,
                appliesTo = json.optJSONArray("applies_to")?.stringList()?.toSet().orEmpty(),
            )
        }
    }
}

/**
 * The calibration that ships beside the model (fitted on the
 * int8 file's dev predictions): a temperature per head, the safety thresholds per flag — with a
 * language of its own where that language cannot meet the recall target inside its false-alarm
 * budget — the topic thresholds for a button and for an addition, and the fixed rules.
 *
 * What the file does not name falls back to its starting values: temperature 1, a safety flag at
 * 0.25, a topic button at 0.5, and no topic ever added beside what the rules found. Every threshold
 * and rule it does name has to be a probability, 0 to 1: a NaN — Python's json writes one, org.json
 * reads it — or a 1.5 would leave a safety flag that can never rise (`p >= NaN` is false), so such a
 * file is refused and the chat keeps to its rules.
 */
internal class ChatModelCalibration(
    private val temperatures: Map<String, Double> = emptyMap(),
    private val safetyTemperatures: Map<String, Double> = emptyMap(),
    private val safetyThresholds: Map<String, Double> = emptyMap(),
    private val safetyByLanguage: Map<String, Map<String, LanguageThreshold>> = emptyMap(),
    private val topicButtons: Map<String, Double> = emptyMap(),
    private val topicAdds: Map<String, Double?> = emptyMap(),
    val rules: ReaderRules = ReaderRules(),
    val journalThresholds: Map<String, Double> = emptyMap(),
) {
    /** The temperature of [head]; the safety head's own for [language] when it has one. */
    fun temperature(head: String, language: String?): Double =
        (if (head == "safety") language?.let(safetyTemperatures::get) else null) ?: temperatures[head] ?: 1.0

    /** [flag]'s threshold: the language's own where it was given one for missing its target, else the flag's. */
    fun safetyThreshold(flag: String, language: String?): Double {
        val own = language?.let { safetyByLanguage[flag]?.get(it) }
        return if (own != null && !own.promise) own.threshold else safetyThresholds[flag] ?: SAFETY_START
    }

    fun topicButton(label: String): Double = topicButtons[label] ?: TOPIC_BUTTON_START

    /** The threshold to add [label] beside the rules' topics, or null: never. */
    fun topicAdd(label: String): Double? = topicAdds[label]

    class LanguageThreshold(val threshold: Double, val promise: Boolean)

    companion object {
        private const val SAFETY_START = 0.25
        private const val TOPIC_BUTTON_START = 0.5

        fun parse(json: String): ChatModelCalibration {
            val root = JSONObject(json)
            val thresholds = root.optJSONObject("thresholds") ?: JSONObject()
            val byLanguage = thresholds.optJSONObject("safety_by_lang") ?: JSONObject()
            val adds = thresholds.optJSONObject("topics_add") ?: JSONObject()
            val temperatures = root.optJSONObject("temperatures").doubles()
            val safetyTemperatures = root.optJSONObject("safety_temperatures_by_lang").doubles()
            require((temperatures.values + safetyTemperatures.values).all { it > 0.0 && it.isFinite() }) {
                "a temperature must be positive"
            }
            return ChatModelCalibration(
                temperatures = temperatures,
                safetyTemperatures = safetyTemperatures,
                safetyThresholds = thresholds.optJSONObject("safety").probabilities(),
                safetyByLanguage = byLanguage.keys().asSequence().associateWith { flag ->
                    val languages = byLanguage.getJSONObject(flag)
                    languages.keys().asSequence().associateWith { language ->
                        val entry = languages.getJSONObject(language)
                        val threshold = probability(entry.getDouble("threshold"), "$flag in $language")
                        LanguageThreshold(threshold, entry.getBoolean("promise"))
                    }
                },
                topicButtons = thresholds.optJSONObject("topics").probabilities(),
                topicAdds = adds.keys().asSequence().associateWith { label ->
                    if (adds.isNull(label)) null else probability(adds.getDouble(label), label)
                },
                rules = rules(root.optJSONObject("rules") ?: JSONObject()),
                journalThresholds = root.optJSONObject("journal").probabilities(),
            )
        }

        private fun rules(json: JSONObject): ReaderRules {
            val start = ReaderRules()
            val lean = json.optJSONObject("unwell_lean") ?: JSONObject()
            fun rule(from: JSONObject, name: String, fallback: Double) =
                probability(if (from.has(name)) from.getDouble(name) else fallback, name)
            return ReaderRules(
                ambiguity = rule(json, "ambiguity", start.ambiguity),
                unwellLean = rule(lean, "lean", start.unwellLean),
                unwellOtherBelow = rule(lean, "other_below", start.unwellOtherBelow),
                side = rule(json, "side", start.side),
                nrs = rule(json, "nrs", start.nrs),
                onset = rule(json, "onset", start.onset),
                oos = rule(json, "oos", start.oos),
                abstainTopicsBelow = rule(json, "abstain_topics_below", start.abstainTopicsBelow),
            )
        }

        /** [value] when it is a probability; a calibration with anything else in a threshold is no calibration. */
        private fun probability(value: Double, what: String): Double {
            require(value in 0.0..1.0) { "the threshold of $what is $value, not a probability" }
            return value
        }

        private fun JSONObject?.probabilities(): Map<String, Double> =
            doubles().onEach { probability(it.value, it.key) }
    }
}

internal fun JSONArray.stringList(): List<String> = (0 until length()).map { getString(it) }

private fun JSONObject?.doubles(): Map<String, Double> =
    this?.let { json -> json.keys().asSequence().associateWith(json::getDouble) }.orEmpty()
