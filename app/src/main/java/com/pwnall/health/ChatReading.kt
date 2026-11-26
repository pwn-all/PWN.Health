package com.pwnall.health

import kotlin.math.exp
import kotlin.math.ln

/**
 * What the chat's model read in one message: how likely every topic and every
 * safety flag is, and the spans it marked in the person's own words.
 *
 * The model proposes; it never decides. Every number here is a probability at its head's
 * temperature from the calibration shipped beside the model, and the yes/no beside it is that
 * calibration's threshold for the class — a topic worth a button, a topic worth adding to what the
 * rules found, a safety step to add. What the chat shows is still the chat's own code: the rules
 * stay the floor, a safety flag can only add one of the app's fixed questions, never remove
 * one, and nothing here is a sentence anybody reads.
 *
 * A reading is made once, when the message is sent, and is immutable from then on: the chat stores
 * the decisions it took from it together with [model] and redraws old conversations from those,
 * never by reading again.
 */
internal data class ChatReading(
    /** The model that read it, as its manifest names it: stored with every decision taken from it. */
    val model: String,
    /** The message's language as the model reads it — never the script's `language` flag. */
    val lang: ReaderLabel,
    /** The twenty topics, in the app's order. */
    val topics: List<ReaderTopic>,
    /** "Unwell" without a topic, and whether it leans to the body or to the mind. */
    val unwell: ReaderUnwell,
    /** The seven safety flags, each of which can only add its fixed step. */
    val safety: List<ReaderFlag>,
    /** Who the message is about: SELF, ADULT_OTHER, CHILD or PET. */
    val about: ReaderLabel,
    /** A question to the chat ("what should I do?"). */
    val question: Double,
    /** GREETING, THANKS, HOW_ARE_YOU, UPSET or CAPABILITY, or null for none of them. */
    val smallTalk: ReaderLabel?,
    /** The kind of mood said, never a rating; null for none. */
    val mood: ReaderLabel?,
    /** Not about health or the chat at all. */
    val oos: Double,
    /** "Not sure": the chat behaves as it does without a model. Safety is read all the same. */
    val abstain: Boolean,
    /** The spans the model marked, in the message's order. */
    val spans: List<ReaderSpan>,
    /** Every message head's probabilities by head and label, the pain heads among them. */
    val heads: Map<String, Map<String, Double>>,
    /** The calibration's fixed rules, for the chat's adapter to apply to [spans]. */
    val rules: ReaderRules,
    /** The message's pieces and the windows they were read in (one unless it is long). */
    val tokens: Int,
    val windows: Int,
    /**
     * Whether topic and entity proposals are enabled for this message's confident language
     * ([ChatModelManifest.routingLanguages]). The name is kept for existing adapters; it certifies
     * no exam of the model. Safety flags apply in every language.
     */
    val examined: Boolean = false,
    /**
     * The journal proposals, which the engine keeps only in a confident language enabled for them
     * ([ChatModelManifest.journalLanguages], [enabledBy]): a gate of their own, apart from [examined].
     */
    val journal: List<ChatJournalEvent> = emptyList(),
) {
    /** The message's language when the model is sure of it, one of the app's supported languages. */
    val sureLanguage: String? get() = lang.label.takeIf { it != OTHER && lang.p >= LANGUAGE_SURE }

    /** The flags over their thresholds. */
    val raised: List<ReaderSafety> get() = safety.filter(ReaderFlag::raised).map(ReaderFlag::flag)

    /** The fixed safety steps the reading asks the chat to add on top of what its rules found. */
    val addedSteps: List<SymptomStep> get() = raised.map(ReaderSafety::step)

    fun topic(symptom: Symptom): ReaderTopic = topics.first { it.symptom == symptom }
}

internal data class ReaderLabel(val label: String, val p: Double)

/** [button]: worth offering when the rules found nothing; [add]: worth adding beside what they found. */
internal data class ReaderTopic(val symptom: Symptom, val p: Double, val button: Boolean, val add: Boolean)

internal data class ReaderUnwell(
    val p: Double,
    val button: Boolean,
    val body: Double,
    val mind: Double,
    val lean: UnwellLean?,
)

internal enum class UnwellLean { BODY, MIND }

internal data class ReaderFlag(val flag: ReaderSafety, val p: Double, val threshold: Double) {
    val raised: Boolean get() = p >= threshold
}

/**
 * The model's seven safety flags and the fixed step each can add (reader-schema.json `adds_step`). The
 * steps are the app's own questions: CRISIS_NOW with its support card, UNRESPONSIVE, BREATH_NOW,
 * CHEST_NOW, STROKE_SIGNS, BLOOD, MED_MAX_WORDS.
 */
internal enum class ReaderSafety(val step: SymptomStep) {
    CRISIS(SymptomStep.CRISIS_NOW),
    UNRESPONSIVE(SymptomStep.UNRESPONSIVE),
    BREATHING(SymptomStep.BREATH_NOW),
    CHEST(SymptomStep.CHEST_NOW),
    STROKE(SymptomStep.STROKE_SIGNS),
    BLOOD(SymptomStep.BLOOD),
    OVERDOSE(SymptomStep.MED_MAX_WORDS),
}

/** What a marked span is (reader-schema.json `slots.kinds`). */
internal enum class ReaderSpanKind {
    PLACE,
    SIDE,
    QUALIFIER,
    SYMPTOM,
    NUMBER,
    MEDICINE,
    AMOUNT,
    TIME,
    INTENSITY,
    MOOD,
    NEGATION,
    SUBJECT,
    FOOD,
    PORTION,
    EXERCISE,
    MUSCLE,
    SETS,
    REPS,
    LOAD,
    DISTANCE,
    DURATION,
    MEAL,
}

/**
 * One span the model marked. [start] and [end] are UTF-16 offsets into the raw message, so [text]
 * is exactly what the person typed there; [attributes] are the probabilities of every attribute head
 * that applies to the [kind] (the place's family, area and side, a number's class, a medicine's
 * entry and role…), by head and label.
 */
internal data class ReaderSpan(
    val kind: ReaderSpanKind,
    val firstPiece: Int,
    val endPiece: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val attributes: Map<String, Map<String, Double>>,
) {
    /** The likeliest label of the attribute [head], or null when that head does not apply here. */
    fun best(head: String): ReaderLabel? = attributes[head]?.let(::likeliest)

    /**
     * [best] when it is at least [atLeast] likely and says something: "NONE" — a side not said, a
     * number that is no pain score — is null, as is anything less sure than [atLeast].
     */
    fun said(head: String, atLeast: Double): String? = best(head)?.takeIf { it.p >= atLeast && it.label != NONE }?.label
}

/** The calibration's fixed rules. */
internal data class ReaderRules(
    val ambiguity: Double = 0.35,
    val unwellLean: Double = 0.7,
    val unwellOtherBelow: Double = 0.3,
    val side: Double = 0.8,
    val nrs: Double = 0.9,
    val onset: Double = 0.8,
    val oos: Double = 0.5,
    val abstainTopicsBelow: Double = 0.35,
)

/** Where in the conversation a message was written (`context.where`). */
internal enum class ChatReaderWhere { START, BETWEEN, QUESTION, NOTE }

/**
 * The conversation around a message, all the model is told besides the message itself: no diary,
 * no courses, no age. [step] is the question being asked, [topic] the open thread's.
 */
internal data class ChatReaderContext(
    val where: ChatReaderWhere = ChatReaderWhere.START,
    val step: SymptomStep? = null,
    val topic: Symptom? = null,
    /** Last three user messages, for the app's bounded conversational replies; never ONNX input. */
    val recentMessages: List<String> = emptyList(),
)

/** One window's five outputs, as the encoder gave them: per position rows flat in [slot] and [span]. */
internal class WindowLogits(
    val topic: FloatArray,
    val safety: FloatArray,
    val meta: FloatArray,
    val slot: FloatArray,
    val span: FloatArray,
    val positions: Int,
    val journal: Map<String, FloatArray> = emptyMap(),
) {
    fun message(output: String): FloatArray = when (output) {
        TOPIC_LOGITS -> topic
        SAFETY_LOGITS -> safety
        META_LOGITS -> meta
        else -> throw IllegalArgumentException("$output is not a message output")
    }
}

internal const val TOPIC_LOGITS = "topic_logits"
internal const val SAFETY_LOGITS = "safety_logits"
internal const val SLOT_LOGITS = "slot_logits"
internal const val META_LOGITS = "meta_logits"
internal const val SPAN_LOGITS = "span_logits"

/** The encoder's outputs in the order the exported model gives them. */
internal val READER_OUTPUTS = listOf(TOPIC_LOGITS, SAFETY_LOGITS, SLOT_LOGITS, META_LOGITS, SPAN_LOGITS)

private const val NONE = "NONE"
private const val OTHER = "OTHER"
private const val UNWELL = "UNWELL"

/** A language the model names with more confidence than all the others together. */
private const val LANGUAGE_SURE = 0.5

/**
 * The reading of [text] from its windows' outputs, step for step:
 * message heads combine across windows by their rule and go through sigmoid or
 * softmax at their temperature; every piece takes its token rows from the window where it sits
 * farthest from an edge; BIO is decoded by Viterbi; a span's attributes are the mean of its pieces'
 * rows; its offsets are the union of its pieces'.
 */
internal fun decodeReading(
    model: String,
    text: String,
    encoding: ChatEncoding,
    windows: List<ChatWindow>,
    logits: List<WindowLogits>,
    schema: ChatModelSchema,
    calibration: ChatModelCalibration,
): ChatReading {
    require(windows.size == logits.size && windows.isNotEmpty()) { "every window needs its outputs" }
    val combined = listOf(TOPIC_LOGITS, SAFETY_LOGITS, META_LOGITS).associateWith { output ->
        combineWindows(logits.map { it.message(output) }, schema.messageHeads.filter { it.output == output })
    }
    // The language first: a safety head can carry a temperature of its own per language.
    val langHead = schema.head(HEAD_LANG)
    val langP = activate(combined.getValue(langHead.output), langHead, calibration.temperature(langHead.name, null))
    val lang = likeliest(langP, langHead)
    val sureLanguage = lang.label.takeIf { it != OTHER && lang.p >= LANGUAGE_SURE }
    val heads = schema.messageHeads.associate { head ->
        val p = activate(combined.getValue(head.output), head, calibration.temperature(head.name, sureLanguage))
        head.name to head.labels.indices.associate { head.labels[it] to p[it] }
    }
    val spans = decodeSpans(text, encoding, windows, logits, schema, calibration)
    return typedReading(model, heads, spans, calibration, sureLanguage, encoding.size, windows.size)
        .copy(journal = decodeJournal(text, encoding, windows, logits, schema, calibration))
}

private const val HEAD_TOPICS = "topics"
private const val HEAD_SAFETY = "safety"
private const val HEAD_LANG = "lang"
private const val HEAD_ABOUT = "about"
private const val HEAD_QUESTION = "question"
private const val HEAD_SMALL_TALK = "small_talk"
private const val HEAD_MOOD = "mood_kind"
private const val HEAD_OOS = "oos"
private const val HEAD_UNWELL_LEAN = "unwell_lean"

/** The heads [typedReading] reads by name: a model without one of them does not fit the app. */
internal val REQUIRED_HEADS = setOf(
    HEAD_TOPICS,
    HEAD_SAFETY,
    HEAD_LANG,
    HEAD_ABOUT,
    HEAD_QUESTION,
    HEAD_SMALL_TALK,
    HEAD_MOOD,
    HEAD_OOS,
    HEAD_UNWELL_LEAN,
)

private fun typedReading(
    model: String,
    heads: Map<String, Map<String, Double>>,
    spans: List<ReaderSpan>,
    calibration: ChatModelCalibration,
    sureLanguage: String?,
    tokens: Int,
    windows: Int,
): ChatReading {
    val rules = calibration.rules
    val topicP = heads.getValue(HEAD_TOPICS)
    val topics = Symptom.entries.map { symptom ->
        val p = topicP.getValue(symptom.name)
        val add = calibration.topicAdd(symptom.name)
        ReaderTopic(symptom, p, button = p >= calibration.topicButton(symptom.name), add = add != null && p >= add)
    }
    val unwellP = topicP.getValue(UNWELL)
    val lean = heads.getValue(HEAD_UNWELL_LEAN)
    val body = lean.getValue(UnwellLean.BODY.name)
    val mind = lean.getValue(UnwellLean.MIND.name)
    val leaning = when {
        body >= rules.unwellLean && mind < rules.unwellOtherBelow -> UnwellLean.BODY
        mind >= rules.unwellLean && body < rules.unwellOtherBelow -> UnwellLean.MIND
        else -> null
    }
    val safetyP = heads.getValue(HEAD_SAFETY)
    val safety = ReaderSafety.entries.map { flag ->
        ReaderFlag(flag, safetyP.getValue(flag.name), calibration.safetyThreshold(flag.name, sureLanguage))
    }
    val oos = heads.getValue(HEAD_OOS).values.single()
    return ChatReading(
        model = model,
        lang = likeliest(heads.getValue(HEAD_LANG)),
        topics = topics,
        unwell = ReaderUnwell(unwellP, unwellP >= calibration.topicButton(UNWELL), body, mind, leaning),
        safety = safety,
        about = likeliest(heads.getValue(HEAD_ABOUT)),
        question = heads.getValue(HEAD_QUESTION).values.single(),
        smallTalk = likeliest(heads.getValue(HEAD_SMALL_TALK)).takeIf { it.label != NONE },
        mood = likeliest(heads.getValue(HEAD_MOOD)).takeIf { it.label != NONE },
        oos = oos,
        abstain = oos >= rules.oos || topicP.values.all { it < rules.abstainTopicsBelow },
        spans = spans,
        heads = heads,
        rules = rules,
        tokens = tokens,
        windows = windows,
    )
}

/** The likeliest label, the first of equals as numpy's argmax. */
private fun likeliest(p: Map<String, Double>): ReaderLabel = p.maxBy { it.value }.let { ReaderLabel(it.key, it.value) }

private fun likeliest(p: DoubleArray, head: ReaderHead): ReaderLabel {
    val at = p.indices.maxBy { p[it] }
    return ReaderLabel(head.labels[at], p[at])
}

/**
 * Message logits from window logits, by each head's rule (reader-schema.json `window_combine`): `max` —
 * any window is enough (the topics; the safety flags, an OR); `min` — every window must agree (OOS);
 * `mean` — the softmax heads average their logits.
 */
internal fun combineWindows(windows: List<FloatArray>, heads: List<ReaderHead>): DoubleArray {
    val out = DoubleArray(windows.first().size) { windows.first()[it].toDouble() }
    if (windows.size == 1) return out
    for (head in heads) {
        for (j in head.offset until head.offset + head.size) {
            out[j] = when (head.combine) {
                WindowCombine.MAX -> windows.maxOf { it[j] }.toDouble()
                WindowCombine.MIN -> windows.minOf { it[j] }.toDouble()
                WindowCombine.MEAN -> windows.sumOf { it[j].toDouble() } / windows.size
            }
        }
    }
    return out
}

/** Sigmoid for a multilabel or binary head, softmax within a softmax head, both at [temperature]. */
internal fun activate(logits: DoubleArray, head: ReaderHead, temperature: Double): DoubleArray {
    val z = DoubleArray(head.size) { logits[head.offset + it] / temperature }
    if (head.kind != HeadKind.SOFTMAX) return DoubleArray(z.size) { sigmoid(z[it]) }
    val top = z.max()
    val e = DoubleArray(z.size) { exp(z[it] - top) }
    val sum = e.sum()
    return DoubleArray(z.size) { e[it] / sum }
}

private fun sigmoid(x: Double): Double = if (x >= 0) 1.0 / (1.0 + exp(-x)) else exp(x) / (1.0 + exp(x))

/** The BIO spans, with their attributes and their offsets in the raw text. */
private fun decodeSpans(
    text: String,
    encoding: ChatEncoding,
    windows: List<ChatWindow>,
    logits: List<WindowLogits>,
    schema: ChatModelSchema,
    calibration: ChatModelCalibration,
): List<ReaderSpan> {
    val n = encoding.size
    val tags = schema.tags.size
    val attrs = schema.outputSizes.getValue(SPAN_LOGITS)
    val slotRows = FloatArray(n * tags)
    val spanRows = FloatArray(n * attrs)
    for (t in 0 until n) {
        val w = ownerWindow(windows, t)
        val at = windows[w].positionOf(t)
        logits[w].slot.copyInto(slotRows, t * tags, at * tags, (at + 1) * tags)
        logits[w].span.copyInto(spanRows, t * attrs, at * attrs, (at + 1) * attrs)
    }
    val path = viterbiBio(slotRows, n, schema.tags)
    return bioSpans(path, schema.tags).map { (kind, a, b) ->
        // A piece that is only ▁ has an empty span; a span of nothing but such pieces keeps their place.
        val real = (a until b).filter { encoding.starts[it] < encoding.ends[it] }.ifEmpty { (a until b).toList() }
        val start = real.minOf { encoding.starts[it] }
        val end = real.maxOf { encoding.ends[it] }
        val mean = DoubleArray(attrs) { j -> (a until b).sumOf { spanRows[it * attrs + j].toDouble() } / (b - a) }
        val attributes = schema.spanHeads.filter { kind in it.appliesTo }.associate { head ->
            val p = activate(mean, head, calibration.temperature(head.name, null))
            head.name to head.labels.indices.associate { head.labels[it] to p[it] }
        }
        ReaderSpan(ReaderSpanKind.valueOf(kind), a, b, start, end, text.substring(start, end), attributes)
    }
}

/**
 * The best tag sequence with I-X only after B-X or I-X, and never first:
 * Viterbi over the log-softmax of every piece's tag row; a tie goes to the lower tag index.
 */
internal fun viterbiBio(rows: FloatArray, n: Int, tags: List<String>): IntArray {
    if (n == 0) return IntArray(0)
    val k = tags.size
    val logp = DoubleArray(n * k)
    for (i in 0 until n) {
        var top = Double.NEGATIVE_INFINITY
        for (j in 0 until k) top = maxOf(top, rows[i * k + j].toDouble())
        var sum = 0.0
        for (j in 0 until k) sum += exp(rows[i * k + j] - top)
        val norm = top + ln(sum)
        for (j in 0 until k) logp[i * k + j] = rows[i * k + j] - norm
    }
    val inside = tags.map { if (it.startsWith("I-")) it.substring(2) else null }
    val kindOf = tags.map { if (it != "O") it.substring(2) else null }
    val allowed = Array(k) { prev -> BooleanArray(k) { cur -> inside[cur] == null || kindOf[prev] == inside[cur] } }
    var score = DoubleArray(k) { if (inside[it] == null) logp[it] else IMPOSSIBLE }
    val back = IntArray(n * k)
    for (i in 1 until n) {
        val next = DoubleArray(k)
        for (cur in 0 until k) {
            val prev = bestPrevious(score, allowed, cur)
            back[i * k + cur] = prev
            next[cur] = (if (allowed[prev][cur]) score[prev] else IMPOSSIBLE) + logp[i * k + cur]
        }
        score = next
    }
    val path = IntArray(n)
    path[n - 1] = score.indices.maxBy { score[it] }
    for (i in n - 1 downTo 1) path[i - 1] = back[i * k + path[i]]
    return path
}

/** The best tag before [cur] — a disallowed one scoring [IMPOSSIBLE] — the lowest index of equals. */
private fun bestPrevious(score: DoubleArray, allowed: Array<BooleanArray>, cur: Int): Int {
    var best = 0
    var bestScore = Double.NEGATIVE_INFINITY
    for (prev in score.indices) {
        val candidate = if (allowed[prev][cur]) score[prev] else IMPOSSIBLE
        if (candidate > bestScore) {
            bestScore = candidate
            best = prev
        }
    }
    return best
}

/** Viterbi's impossible: low enough to lose to anything, finite. */
private const val IMPOSSIBLE = -1e30

/** (kind, first piece, end piece) of every B-X with the I-X after it. */
private fun bioSpans(path: IntArray, tags: List<String>): List<Triple<String, Int, Int>> {
    val out = ArrayList<Triple<String, Int, Int>>()
    var i = 0
    while (i < path.size) {
        val tag = tags[path[i]]
        if (tag.startsWith("B-")) {
            val kind = tag.substring(2)
            var j = i + 1
            while (j < path.size && tags[path[j]] == "I-$kind") j++
            out += Triple(kind, i, j)
            i = j
        } else {
            i++
        }
    }
    return out
}
