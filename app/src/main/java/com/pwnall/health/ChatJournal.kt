package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * An event proposal. Opening a form and saving a record are separate user actions. [titleRes] is the
 * record's name on its card and [artRes] its drawing — every type its own: "New record" is the card's
 * heading, and a type that fell back to it would name a water card "New record" twice.
 */
enum class ChatJournalType(val titleRes: Int, val artRes: Int) {
    SYMPTOM(R.string.symptom_title, R.drawable.ic_art_doctor),
    PAIN(R.string.pain_title, R.drawable.ic_art_pain),
    MOOD(R.string.add_mood, R.drawable.ic_art_mood),
    DOSE(R.string.med_title, R.drawable.ic_art_doctor),
    COURSE(R.string.med_title, R.drawable.ic_art_doctor),
    FOOD_LOG(R.string.add_meal, R.drawable.ic_art_food),
    WORKOUT_LOG(R.string.add_workout, R.drawable.ic_art_sport),
    WORKOUT_PLAN(R.string.add_workout, R.drawable.ic_art_sport),
    STOOL_LOG(R.string.stool_title, R.drawable.ic_art_toilet),
    URINATION_LOG(R.string.urination_title, R.drawable.ic_art_urination),
    WORK_LOG(R.string.work_title, R.drawable.ic_art_time),
    WORK_TIMER(R.string.work_title, R.drawable.ic_art_time),
    SLEEP_LOG(R.string.measurement_sleep_title, R.drawable.ic_art_sleep),
    WEIGHT_LOG(R.string.measurement_weight_title, R.drawable.ic_art_weight),
    HEIGHT_LOG(R.string.measurement_height_title, R.drawable.ic_art_height),
    HEART_RATE_LOG(R.string.measurement_heart_rate_title, R.drawable.ic_art_pulse),
    GIRTH_LOG(R.string.measurement_girth_title, R.drawable.ic_art_height),
    BREATHING_LOG(R.string.breathing_title, R.drawable.ic_art_breathing),
    WATER_LOG(R.string.water_title, R.drawable.ic_art_water),
    CAFFEINE_LOG(R.string.caffeine_title, R.drawable.ic_art_coffee),
    ALCOHOL_LOG(R.string.alcohol_title, R.drawable.ic_art_alcohol),
    SMOKING_LOG(R.string.smoking_title, R.drawable.ic_art_smoking),
    PERIOD_START(R.string.cycle_title, R.drawable.ic_art_cycle),
    PERIOD_END(R.string.cycle_title, R.drawable.ic_art_cycle),
    CYCLE_NOTE(R.string.cycle_title, R.drawable.ic_art_cycle),
    SEX_LOG(R.string.cycle_intimate_title, R.drawable.ic_art_sex),
    MORNING_CHECK(R.string.cycle_intimate_title, R.drawable.ic_art_sex),
    APPOINTMENT_LOG(R.string.visit_reminder_title, R.drawable.ic_art_schedule),
    LAB_RESULT_LOG(R.string.labs_title, R.drawable.ic_art_labs),
    MEDICAL_HISTORY_LOG(R.string.history_title, R.drawable.ic_art_history),
    ALLERGY_UPDATE(R.string.allergy_title, R.drawable.ic_art_history),
    ATTACHMENT(R.string.attachments_title, R.drawable.ic_art_report),
}

data class ChatJournalSpan(val start: Int, val end: Int, val text: String)
data class ChatJournalArgument(val role: String, val span: ChatJournalSpan)
data class ChatJournalEvent(
    val type: ChatJournalType,
    val anchor: ChatJournalSpan,
    val attributes: Map<String, String> = emptyMap(),
    val arguments: List<ChatJournalArgument> = emptyList(),
    /** Filled by the app when a proposal is opened, never inferred by the model. */
    val sourceText: String? = null,
    val sourceDay: String? = null,
) {
    /** Uncertain and other-person messages never offer a write into the user's own diary. */
    val canOfferForm: Boolean
        get() = attributes["subject"] == "SELF" && attributes["assertion"] == "AFFIRMED"

    fun groundedIn(text: String): Boolean = (listOf(anchor) + arguments.map { it.span }).all {
        it.start >= 0 && it.start < it.end && it.end <= text.length && text.substring(it.start, it.end) == it.text
    }
}

internal val JOURNAL_OUTPUTS = listOf(
    "journal_anchor_logits",
    "journal_attribute_logits",
    "journal_argument_logits",
    "journal_query",
    "journal_key",
)
internal val JOURNAL_ATTRIBUTES = linkedMapOf(
    "record_type" to ChatJournalType.entries.map { it.name },
    "subject" to listOf("SELF", "ADULT_OTHER", "CHILD", "PET"),
    "assertion" to listOf("AFFIRMED", "NEGATED", "UNCERTAIN"),
    "phase" to listOf("DONE", "ONGOING", "PLANNED", "UNKNOWN"),
    "operation" to listOf("REPORT", "START", "PAUSE", "RESUME", "STOP", "CORRECT", "ATTACH"),
)
internal val JOURNAL_ROLES = listOf(
    "SUBJECT", "TIME", "START_TIME", "END_TIME", "DURATION", "BREAK_DURATION", "ITEM", "VALUE",
    "UNIT", "AMOUNT", "COUNT", "PLACE", "SIDE", "SYMPTOM", "MEDICINE", "DOSE", "FOOD", "PORTION",
    "MEAL", "EXERCISE", "MUSCLE", "SETS", "REPS", "LOAD", "DISTANCE", "MARK", "STOOL_KIND", "CYCLE_MARK",
    "INTIMATE_KIND", "DRINK_KIND", "TOBACCO_KIND", "GIRTH_SITE", "LAB_MARKER", "REFERENCE_LOW", "REFERENCE_HIGH",
    "SPECIMEN", "METHOD", "SPECIALTY", "HISTORY_KIND", "ALLERGEN", "ALLERGY_STATE", "DOCUMENT_KIND", "REFERENCE",
)
private const val JOURNAL_DIM = 8

internal class ChatJournalSchema {
    companion object {
        fun parse(root: JSONObject, outputs: JSONObject): ChatJournalSchema {
            require(root.getInt("journal_contract_version") == 2)
            require(root.getInt("relation_dim") == JOURNAL_DIM)
            val attributes = root.getJSONObject("attributes")
            for ((name, labels) in JOURNAL_ATTRIBUTES) {
                require(attributes.getJSONArray(name).stringList() == labels) { "journal attribute $name" }
            }
            require(root.getJSONArray("argument_roles").stringList() == JOURNAL_ROLES)
            val layout = outputs.getJSONObject("journal_attribute_logits").getJSONArray("layout")
            require(layout.length() == JOURNAL_ATTRIBUTES.size)
            var offset = 0
            for ((i, entry) in JOURNAL_ATTRIBUTES.entries.withIndex()) {
                val item = layout.getJSONObject(i)
                require(
                    item.getString("head") == entry.key && item.getInt("offset") == offset &&
                        item.getInt("size") == entry.value.size,
                )
                offset += entry.value.size
            }
            return ChatJournalSchema()
        }
    }

    fun fits(sizes: Map<String, Int>): Boolean = JOURNAL_OUTPUTS.zip(
        listOf(
            3,
            JOURNAL_ATTRIBUTES.values.sumOf { it.size },
            JOURNAL_ROLES.size * 3,
            JOURNAL_ROLES.size * JOURNAL_DIM,
            JOURNAL_ROLES.size * JOURNAL_DIM,
        ),
    ).all { (name, width) -> sizes[name] == width }
}

private fun probability(rows: FloatArray, at: Int, width: Int): DoubleArray {
    val top = (0 until width).maxOf { rows[at + it].toDouble() }
    val p = DoubleArray(width) { exp(rows[at + it] - top) }
    val sum = p.sum()
    return DoubleArray(width) { p[it] / sum }
}

private fun journalSpans(rows: FloatArray, n: Int, minimum: Double): List<IntRange> {
    val tags = viterbiBio(rows, n, listOf("O", "B-E", "I-E"))
    val result = mutableListOf<IntRange>()
    var start = 0
    while (start < n) {
        if (tags[start] != 1) {
            start++
            continue
        }
        var end = start + 1
        while (end < n && tags[end] == 2) end++
        if ((start until end).all { probability(rows, it * 3, 3)[tags[it]] >= minimum }) result += start until end
        start = end
    }
    return result
}

internal fun decodeJournal(
    text: String,
    encoding: ChatEncoding,
    windows: List<ChatWindow>,
    logits: List<WindowLogits>,
    schema: ChatModelSchema,
    calibration: ChatModelCalibration,
): List<ChatJournalEvent> {
    if (schema.journal == null) return emptyList()
    val found = linkedMapOf<Triple<Int, Int, ChatJournalType>, Pair<Int, ChatJournalEvent>>()
    for ((wi, window) in windows.withIndex()) {
        val reader = JournalWindow(text, encoding, window, logits[wi], schema, calibration)
        for ((margin, event) in reader.events()) {
            val key = Triple(event.anchor.start, event.anchor.end, event.type)
            if (event.groundedIn(text) && margin > (found[key]?.first ?: -1)) found[key] = margin to event
        }
    }
    return found.values.map {
        it.second
    }.sortedWith(compareBy({ it.anchor.start }, { it.anchor.end }, { it.type.name }))
}

private class JournalWindow(
    private val text: String,
    private val encoding: ChatEncoding,
    private val window: ChatWindow,
    logits: WindowLogits,
    private val schema: ChatModelSchema,
    calibration: ChatModelCalibration,
) {
    private val outputs = logits.journal
    private val n = window.end - window.start
    private val spanMinimum = calibration.journalThresholds["span_threshold"] ?: 0.9
    private val attributeMinimum = calibration.journalThresholds["attribute_threshold"] ?: 0.9
    private val bindingMinimum = calibration.journalThresholds["binding_threshold"] ?: 0.9

    init {
        require(
            JOURNAL_OUTPUTS.all { name ->
                val values = outputs[name]
                values != null && values.size == logits.positions * schema.outputSizes.getValue(name) &&
                    values.all(Float::isFinite)
            },
        ) { "journal output shape or value" }
    }

    private fun span(range: IntRange): ChatJournalSpan {
        val start = range.minOf { encoding.starts[window.start + it] }
        val end = range.maxOf { encoding.ends[window.start + it] }
        return ChatJournalSpan(start, end, text.substring(start, end))
    }

    private fun mean(name: String, range: IntRange): FloatArray {
        val width = schema.outputSizes.getValue(name)
        val values = outputs.getValue(name)
        return FloatArray(width) { k ->
            (range.sumOf { p -> values[(window.prefix + p) * width + k].toDouble() } / range.count()).toFloat()
        }
    }

    private fun candidates(): List<Pair<Int, IntRange>> {
        val argument = outputs.getValue(JOURNAL_OUTPUTS[2])
        return JOURNAL_ROLES.indices.flatMap { role ->
            val rows = FloatArray(n * 3) { at ->
                argument[(window.prefix + at / 3) * JOURNAL_ROLES.size * 3 + role * 3 + at % 3]
            }
            journalSpans(rows, n, spanMinimum).map { role to it }
        }
    }

    private fun attributes(range: IntRange): Map<String, String> {
        val z = mean(JOURNAL_OUTPUTS[1], range)
        val result = linkedMapOf<String, String>()
        var offset = 0
        for ((name, labels) in JOURNAL_ATTRIBUTES) {
            val p = probability(z, offset, labels.size)
            val best = p.indices.maxBy { p[it] }
            if (p[best] >= attributeMinimum) result[name] = labels[best]
            offset += labels.size
        }
        return result
    }

    private fun bindings(range: IntRange, args: List<Pair<Int, IntRange>>): List<ChatJournalArgument> {
        val query = mean(JOURNAL_OUTPUTS[3], range)
        return args.mapNotNull { (role, argument) ->
            val key = mean(JOURNAL_OUTPUTS[4], argument)
            val score = (0 until JOURNAL_DIM).sumOf { d ->
                query[role * JOURNAL_DIM + d].toDouble() * key[role * JOURNAL_DIM + d]
            } / sqrt(JOURNAL_DIM.toDouble())
            val p = if (score >= 0) 1 / (1 + exp(-score)) else exp(score) / (1 + exp(score))
            ChatJournalArgument(JOURNAL_ROLES[role], span(argument)).takeIf { p >= bindingMinimum }
        }
    }

    fun events(): List<Pair<Int, ChatJournalEvent>> {
        val anchors = outputs.getValue(JOURNAL_OUTPUTS[0]).copyOfRange(window.prefix * 3, (window.prefix + n) * 3)
        val args = candidates()
        return journalSpans(anchors, n, spanMinimum).mapNotNull { range ->
            val attrs = attributes(range)
            val type = attrs["record_type"]?.let { name -> ChatJournalType.entries.firstOrNull { it.name == name } }
                ?: return@mapNotNull null
            minOf(range.first, n - 1 - range.last) to ChatJournalEvent(type, span(range), attrs, bindings(range, args))
        }
    }
}

internal fun encodeJournalEvents(events: List<ChatJournalEvent>): String = JSONArray().apply {
    for (event in events) {
        put(
            JSONObject().apply {
                put("type", event.type.name)
                put("anchor", journalSpanJson(event.anchor))
                event.sourceText?.let { put("sourceText", it) }
                event.sourceDay?.let { put("sourceDay", it) }
                put("attributes", JSONObject(event.attributes))
                put(
                    "arguments",
                    JSONArray().apply {
                        for (arg in event.arguments) {
                            put(
                                JSONObject().put("role", arg.role).put("span", journalSpanJson(arg.span)),
                            )
                        }
                    },
                )
            },
        )
    }
}.toString()

private fun journalSpanJson(span: ChatJournalSpan): JSONObject = JSONObject()
    .put("start", span.start).put("end", span.end).put("text", span.text)

internal fun decodeJournalEvents(array: JSONArray?): List<ChatJournalEvent> =
    (0 until (array?.length() ?: 0)).mapNotNull { index ->
        runCatching {
            val value = requireNotNull(array).getJSONObject(index)
            fun span(
                json: JSONObject,
            ): ChatJournalSpan = ChatJournalSpan(json.getInt("start"), json.getInt("end"), json.getString("text"))
            val type = ChatJournalType.valueOf(value.getString("type"))
            val attributes = value.getJSONObject("attributes")
            val kept = JOURNAL_ATTRIBUTES.keys.mapNotNull { name ->
                (attributes.opt(name) as? String)?.takeIf {
                    it in JOURNAL_ATTRIBUTES.getValue(name)
                }?.let { name to it }
            }.toMap()
            val args = value.getJSONArray("arguments")
            ChatJournalEvent(
                type,
                span(value.getJSONObject("anchor")),
                kept,
                (0 until args.length()).mapNotNull { i ->
                    args.getJSONObject(i).let { arg ->
                        arg.getString("role").takeIf {
                            it in JOURNAL_ROLES
                        }?.let { ChatJournalArgument(it, span(arg.getJSONObject("span"))) }
                    }
                },
                sourceText = value.opt("sourceText") as? String,
                sourceDay = value.opt("sourceDay") as? String,
            )
        }.getOrNull()
    }
