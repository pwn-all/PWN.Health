package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp

/**
 * From the encoder's logits to a [ChatReading], with logits written by hand: how windows combine,
 * what a temperature and a threshold do, the decision rules, and how BIO tags become spans over the
 * person's own words. The schema is the app's own.
 */
class ChatReadingTest {
    private val schema = ChatModelSchema.parse(File(ChatModelTestFiles.assets, CHAT_MODEL_SCHEMA).readText())
    private val tokenizer = ChatModelTestFiles.tokenizer

    @Test
    fun theShippedSchemaFitsTheApp() {
        assertNull(schema.misfit())
    }

    /** Safety and topics: any window (max). OOS: every window (min). Softmax heads: the mean logit. */
    @Test
    fun windowsCombineByEachHeadsRule() {
        val meta = schema.messageHeads.filter { it.output == META_LOGITS }
        val about = meta.first { it.name == "about" }
        val oos = meta.first { it.name == "oos" }
        val question = meta.first { it.name == "question" }
        val a = FloatArray(schema.outputSizes.getValue(META_LOGITS))
        val b = FloatArray(a.size)
        a[about.offset] = 4f
        b[about.offset] = 0f
        a[oos.offset] = 3f
        b[oos.offset] = -1f
        a[question.offset] = -2f
        b[question.offset] = 5f
        val combined = combineWindows(listOf(a, b), meta)
        assertEquals(2.0, combined[about.offset], 1e-9)
        assertEquals(-1.0, combined[oos.offset], 1e-9)
        assertEquals(5.0, combined[question.offset], 1e-9)
        // One window is taken as it is.
        assertEquals(3.0, combineWindows(listOf(a), meta)[oos.offset], 1e-9)
    }

    /** A crisis phrase in the last window of a long message raises the flag: safety is an OR of windows. */
    @Test
    fun aFlagInAnyWindowIsRaised() {
        val text = "Work was really busy today and everyone was tired. ".repeat(60) + "I want to die"
        val reading = read(text) { windows, w, logits ->
            logits.safety[ReaderSafety.CRISIS.ordinal] = if (w == windows.lastIndex) 6f else -6f
        }
        assertTrue(reading.windows >= 2)
        assertEquals(listOf(ReaderSafety.CRISIS), reading.raised)
        assertEquals(listOf(SymptomStep.CRISIS_NOW), reading.addedSteps)
    }

    @Test
    fun temperaturesSoftenEachHeadOnItsOwn() {
        val calibration = ChatModelCalibration(temperatures = mapOf("topics" to 2.0))
        val reading = read("болит колено", calibration) { _, _, logits -> logits.topic[Symptom.PAIN.ordinal] = 3f }
        assertEquals(sigmoid(1.5), reading.topic(Symptom.PAIN).p, 1e-6)
        // Another head keeps temperature 1.
        assertEquals(0.5, reading.question, 1e-9)
    }

    /**
     * A language that cannot meet its recall target inside its false-alarm budget gets a threshold
     * of its own (promise: false); one that can keeps the flag's. The language is the
     * model's own reading of the message, and counts only when it is surer than all others together.
     */
    @Test
    fun aLanguageThatMissedItsTargetHasItsOwnSafetyThreshold() {
        val calibration = ChatModelCalibration(
            safetyThresholds = mapOf("CHEST" to 0.3),
            safetyByLanguage = mapOf(
                "CHEST" to mapOf(
                    "ja" to ChatModelCalibration.LanguageThreshold(0.1, promise = false),
                    "ru" to ChatModelCalibration.LanguageThreshold(0.5, promise = true),
                ),
            ),
        )
        fun chest(language: String, sure: Boolean) = read("胸が痛い", calibration) { _, _, logits ->
            logits.safety[ReaderSafety.CHEST.ordinal] = -1.5f // p ≈ 0.18
            logits.meta[lang(language)] = if (sure) 8f else 0.5f
        }.safety.first { it.flag == ReaderSafety.CHEST }
        assertEquals(0.1, chest("ja", sure = true).threshold, 1e-9)
        assertTrue(chest("ja", sure = true).raised)
        // Russian met its target: the flag's own threshold, not Russian's higher one.
        assertEquals(0.3, chest("ru", sure = true).threshold, 1e-9)
        assertFalse(chest("ru", sure = true).raised)
        // An unsure language counts as none.
        assertEquals(0.3, chest("ja", sure = false).threshold, 1e-9)
    }

    @Test
    fun aSafetyHeadCanHaveATemperaturePerLanguage() {
        val calibration = ChatModelCalibration(
            temperatures = mapOf("safety" to 1.0),
            safetyTemperatures = mapOf("en" to 2.0),
        )
        val reading = read("I can't breathe", calibration) { _, _, logits ->
            logits.safety[ReaderSafety.BREATHING.ordinal] = 2f
            logits.meta[lang("en")] = 8f
        }
        assertEquals("en", reading.lang.label)
        assertEquals(sigmoid(1.0), reading.safety.first { it.flag == ReaderSafety.BREATHING }.p, 1e-6)
    }

    @Test
    fun topicsOfferAButtonOrAnAdditionByTheirOwnThresholds() {
        val calibration = ChatModelCalibration(
            topicButtons = mapOf("FEVER" to 0.4, "PAIN" to 0.6),
            topicAdds = mapOf("FEVER" to 0.95, "PAIN" to null),
        )
        val reading = read("температура и болит", calibration) { _, _, logits ->
            logits.topic[Symptom.FEVER.ordinal] = 4f // p ≈ 0.982
            logits.topic[Symptom.PAIN.ordinal] = 0.2f // p ≈ 0.55
            logits.topic[Symptom.NAUSEA.ordinal] = 0.1f // p ≈ 0.52, the default button threshold 0.5
        }
        assertTrue(reading.topic(Symptom.FEVER).button && reading.topic(Symptom.FEVER).add)
        assertFalse(reading.topic(Symptom.PAIN).button)
        assertFalse(reading.topic(Symptom.PAIN).add) // never added beside the rules: no threshold
        assertTrue(reading.topic(Symptom.NAUSEA).button)
        assertFalse(reading.topic(Symptom.NAUSEA).add)
    }

    /** UNWELL leans to the body or the mind only at ≥ 0.7 with the other under 0.3; else ask. */
    @Test
    fun unwellLeansOnlyWhenOneSideIsClear() {
        val lean = schema.head("unwell_lean")
        fun leanOf(body: Float, mind: Float) = read("мне плохо") { _, _, logits ->
            logits.topic[Symptom.entries.size] = 3f
            logits.meta[lean.offset] = body
            logits.meta[lean.offset + 1] = mind
        }.unwell
        assertEquals(UnwellLean.BODY, leanOf(2f, -2f).lean)
        assertEquals(UnwellLean.MIND, leanOf(-2f, 2f).lean)
        assertNull(leanOf(2f, 0f).lean)
        assertNull(leanOf(0f, 0f).lean)
        assertTrue(leanOf(0f, 0f).button)
    }

    /** Abstain at OOS ≥ 0.5 or every topic under 0.35; safety is read all the same. */
    @Test
    fun theReadingAbstainsWhenItIsNotAboutHealth() {
        val oos = schema.head("oos")
        val offTopic = read("what's the capital of France") { _, _, logits ->
            logits.topic.fill(-4f)
            logits.meta[oos.offset] = 2f
            logits.safety[ReaderSafety.CRISIS.ordinal] = 4f
        }
        assertTrue(offTopic.abstain)
        assertEquals("abstain", ChatReadOutcome.Read(offTopic).reason)
        assertEquals(listOf(ReaderSafety.CRISIS), offTopic.raised)
        val quiet = read("hm") { _, _, logits ->
            logits.topic.fill(-1f)
            logits.meta[oos.offset] = -3f
        }
        assertTrue(quiet.abstain)
        val unwell = read("i feel bad") { _, _, logits ->
            logits.topic.fill(-3f)
            logits.topic[Symptom.entries.size] = 0f
            logits.meta[oos.offset] = -3f
        }
        assertFalse(unwell.abstain)
        assertNull(ChatReadOutcome.Read(unwell).reason)
    }

    /**
     * BIO tags become spans over the raw text: B-X opens one, I-X continues it, an I-X after
     * anything else is impossible, and a span's offsets are its pieces' in the raw message.
     */
    @Test
    fun tagsBecomeSpansOverTheTypedWords() {
        val text = "болит правое колено"
        val encoding = tokenizer.encode(text)
        assertEquals(listOf("▁бол", "ит", "▁право", "е", "▁коле", "но"), encoding.pieces)
        val tags = schema.tags
        fun tag(name: String) = tags.indexOf(name)
        val side = schema.spanHeads.first { it.name == "side" }
        val reading = read(text) { _, _, logits ->
            val rows = listOf(
                "O",
                "I-PLACE", // an I- after O is impossible: Viterbi takes the next best for it
                "B-SIDE",
                "I-SIDE",
                "B-PLACE",
                "I-PLACE",
            )
            for ((t, name) in rows.withIndex()) {
                val at = 4 + t // after [<s>] [W] [S] [T]
                logits.slot[at * tags.size + tag(name)] = 9f
                if (name == "I-PLACE" && t == 1) logits.slot[at * tags.size + tag("O")] = 8f
                logits.span[at * logits.span.size / logits.positions + side.offset + 2] = if (t == 2) 4f else 0f // R
            }
        }
        assertEquals(listOf(ReaderSpanKind.SIDE, ReaderSpanKind.PLACE), reading.spans.map { it.kind })
        val (sideSpan, place) = reading.spans
        assertEquals("правое", sideSpan.text)
        assertEquals(2 to 4, sideSpan.firstPiece to sideSpan.endPiece)
        assertEquals("колено", place.text)
        assertEquals(text.indexOf("колено"), place.start)
        // The side's attributes are the mean of its two pieces' rows: logit 2 for R.
        val r = exp(2.0) / (exp(2.0) + 3)
        assertEquals(r, sideSpan.attributes.getValue("side").getValue("R"), 1e-6)
        assertEquals("R", sideSpan.best("side")?.label)
        assertNull(sideSpan.said("side", atLeast = 0.8)) // r ≈ 0.71: not sure enough to say
        assertEquals("R", sideSpan.said("side", atLeast = 0.7))
        // Attributes only of the heads that describe the kind.
        assertFalse("medicine_entry" in place.attributes)
        assertTrue("place_area" in place.attributes)
    }

    /**
     * BIO is decoded in double, as the Python reference decodes it: B-PLACE I-PLACE beats O O here by
     * 4.8e-7, which sums in float32 lose, and the two would mark different spans in the same logits.
     */
    @Test
    fun bioIsDecodedInDoubleAsTheReferenceIs() {
        val tags = schema.tags
        val rows = FloatArray(2 * tags.size) { -10f }
        rows[tags.indexOf("O")] = 0f
        rows[tags.size + tags.indexOf("O")] = 0f
        rows[tags.indexOf("B-PLACE")] = -1.9397399425506592f
        rows[tags.size + tags.indexOf("I-PLACE")] = 1.9397404193878174f
        assertEquals(listOf("B-PLACE", "I-PLACE"), viterbiBio(rows, 2, tags).map { tags[it] })
    }

    /**
     * Every threshold and rule the calibration names is a probability. A NaN (Python's json writes
     * one) or a value past 0…1 would leave a flag that can never rise, or always does: refused.
     */
    @Test
    fun aCalibrationThresholdHasToBeAProbability() {
        val good = """{"thresholds":{"safety":{"CRISIS":0.2},
            "safety_by_lang":{"CHEST":{"ja":{"threshold":0.1,"promise":false}}},
            "topics":{"PAIN":0.5},"topics_add":{"PAIN":0.9,"FEVER":null}},
            "rules":{"side":0.8,"unwell_lean":{"lean":0.7}}}"""
        val calibration = ChatModelCalibration.parse(good)
        assertEquals(0.2, calibration.safetyThreshold("CRISIS", null), 0.0)
        assertEquals(0.1, calibration.safetyThreshold("CHEST", "ja"), 0.0)
        assertEquals(0.9, calibration.topicAdd("PAIN")!!, 0.0)
        assertNull(calibration.topicAdd("FEVER"))
        assertEquals(0.8, calibration.rules.side, 0.0)
        val spoiled = listOf(
            "\"CRISIS\":0.2" to "\"CRISIS\":NaN",
            "\"CRISIS\":0.2" to "\"CRISIS\":1.5",
            "\"threshold\":0.1" to "\"threshold\":-0.1",
            "\"PAIN\":0.5" to "\"PAIN\":Infinity",
            "\"PAIN\":0.9" to "\"PAIN\":NaN",
            "\"side\":0.8" to "\"side\":NaN",
            "\"lean\":0.7" to "\"lean\":7",
        )
        for ((from, to) in spoiled) {
            val json = good.replace(from, to)
            assertTrue(to, json != good)
            assertThrows(to, IllegalArgumentException::class.java) { ChatModelCalibration.parse(json) }
        }
    }

    /** NONE says nothing, however sure: a number that is no pain score has no score. */
    @Test
    fun aSpanSaysNothingWithNone() {
        val nrs = mapOf("nrs" to mapOf("NONE" to 0.99, "7" to 0.01))
        val span = ReaderSpan(ReaderSpanKind.NUMBER, 0, 1, 0, 2, "38", nrs)
        assertNull(span.said("nrs", atLeast = 0.9))
        assertEquals("NONE", span.best("nrs")?.label)
        assertNull(span.best("side"))
    }

    private fun lang(code: String): Int = schema.head("lang").let { it.offset + it.labels.indexOf(code) }

    private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))

    /** [text] read with every logit 0 except what [set] writes, window by window. */
    private fun read(
        text: String,
        calibration: ChatModelCalibration = ChatModelCalibration(),
        set: (List<ChatWindow>, Int, WindowLogits) -> Unit,
    ): ChatReading {
        val encoding = tokenizer.encode(text)
        val windows = tokenizer.windows(encoding, tokenizer.contextIds(ChatReaderContext()))
        val logits = windows.mapIndexed { w, window ->
            val positions = window.inputIds.size
            WindowLogits(
                topic = FloatArray(schema.outputSizes.getValue(TOPIC_LOGITS)),
                safety = FloatArray(schema.outputSizes.getValue(SAFETY_LOGITS)).apply { fill(-5f) },
                meta = FloatArray(schema.outputSizes.getValue(META_LOGITS)),
                slot = FloatArray(positions * schema.tags.size),
                span = FloatArray(positions * schema.outputSizes.getValue(SPAN_LOGITS)),
                positions = positions,
                journal = if (schema.journal == null) {
                    emptyMap()
                } else {
                    JOURNAL_OUTPUTS.associateWith { FloatArray(positions * schema.outputSizes.getValue(it)) }
                },
            ).also { set(windows, w, it) }
        }
        return decodeReading("test model", text, encoding, windows, logits, schema, calibration)
    }
}
