package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject

// The chat's own sorting of what it was told, kept in the entry and shown nowhere: not on a screen,
// not in a string, not to a screen reader, not in an export or the doctor's sheet. It is kept for
// uses that read it without showing it — a model's context, a count of episodes of one kind — and
// any use that would let the person see it is a decision of its own, made with them, not a reading
// of this. SymptomTagHiddenTest holds the line.
//
// The areas are where it was felt, never what it is: "chest and heartbeat", not "heart"; "temperature",
// not "infection". A group of topics is not a finding, and nothing here decides what help the chat
// offers — the rules do that (SymptomRules.kt), and NICE NG225 1.6 is plain that sorting people into
// levels of risk is no way to decide who is helped. A model given the tag as context must never say
// it back ([SafetyGuard]).

/** Where the trouble was felt or noticed: a group of topics, described, never a cause. */
enum class ProblemArea {
    STOMACH_GUT,
    CHEST_HEARTBEAT,
    BREATHING,
    BALANCE_FAINTING,
    TEMPERATURE,
    ENERGY,
    FEELINGS,
    SLEEP,

    /** A headache, muddled thinking or speech, weakness on one side: noticed in the head or a limb. */
    HEAD,
    SKIN,
    UNSORTED,

    // The records a chat files. Appended, never inserted.

    /** A pain anywhere but the chest, the head and the belly: the muscles, the joints, the bones. */
    MUSCULOSKELETAL,

    /** A dose taken or a course begun. */
    MEDICINES,

    /** A mood told in the middle of the faces, and one told on the bright side of them. */
    MOOD_NEUTRAL,
    MOOD_POSITIVE,
}

/** How long it has gone on: started in the last few days, about a week, for longer, or not told. */
enum class ProblemCourse { NEW, RECENT, LASTING, UNKNOWN }

/** How much it gets in the way, as the answers put it. */
enum class ProblemImpact { NONE, SOME, MUCH, UNKNOWN }

/**
 * One chat, sorted. [also] is the areas of what came with it, apart from [area]; [urgency] the
 * chat's own ([chatUrgency]); [flags] the ids of the rules that fired, in table order, leaving out
 * those about suicide — the answer is in the chat itself, and a label for it kept out of sight is
 * the kind of thing that turns up later where nobody expected it; [mentioned] every topic the
 * person's words named, chosen or not. [version] is the taxonomy's, for a reader of an older one.
 */
data class SymptomTag(
    val area: ProblemArea,
    val also: List<ProblemArea>,
    val urgency: SymptomUrgency,
    val course: ProblemCourse,
    val recurring: Boolean,
    val impact: ProblemImpact,
    val mind: Boolean,
    val flags: List<String>,
    val mentioned: List<Symptom>,
    val version: Int = SYMPTOM_TAG_VERSION,
)

internal const val SYMPTOM_TAG_VERSION = 1

private val TOPIC_AREA = mapOf(
    Symptom.VOMITING to ProblemArea.STOMACH_GUT,
    Symptom.NAUSEA to ProblemArea.STOMACH_GUT,
    Symptom.HEARTBURN to ProblemArea.STOMACH_GUT,
    Symptom.DIARRHOEA to ProblemArea.STOMACH_GUT,
    Symptom.PALPITATIONS to ProblemArea.CHEST_HEARTBEAT,
    Symptom.CHEST_PAIN to ProblemArea.CHEST_HEARTBEAT,
    Symptom.FAINTING to ProblemArea.BALANCE_FAINTING,
    Symptom.DIZZINESS to ProblemArea.BALANCE_FAINTING,
    Symptom.BREATHLESSNESS to ProblemArea.BREATHING,
    Symptom.FEVER to ProblemArea.TEMPERATURE,
    Symptom.TIREDNESS to ProblemArea.ENERGY,
    Symptom.WORRY to ProblemArea.FEELINGS,
    Symptom.LOW_MOOD to ProblemArea.FEELINGS,
    Symptom.STRESS to ProblemArea.FEELINGS,
    Symptom.SLEEP_WORRY to ProblemArea.SLEEP,
    Symptom.OTHER to ProblemArea.UNSORTED,
    Symptom.PAIN to ProblemArea.MUSCULOSKELETAL,
    Symptom.MOOD to ProblemArea.MOOD_NEUTRAL,
    Symptom.DOSE to ProblemArea.MEDICINES,
    Symptom.COURSE to ProblemArea.MEDICINES,
)

private val OPTION_AREA: Map<SymptomOption, ProblemArea> = buildMap {
    fun put(area: ProblemArea, vararg options: SymptomOption) = options.forEach { put(it, area) }
    put(
        ProblemArea.CHEST_HEARTBEAT,
        SymptomOption.CHEST_PAIN,
        SymptomOption.RACING_HEART,
        SymptomOption.PAIN_SPREADING,
        SymptomOption.TIGHT_HEAVY,
        SymptomOption.HEART_CONDITION,
    )
    put(
        ProblemArea.BREATHING,
        SymptomOption.BREATHLESS,
        SymptomOption.WHEEZING,
        SymptomOption.COUGH,
        SymptomOption.BLUE_LIPS,
        SymptomOption.SEVERE_BREATHING,
        SymptomOption.COUGHING_BLOOD,
        SymptomOption.SORE_THROAT,
    )
    put(
        ProblemArea.STOMACH_GUT,
        SymptomOption.VOMITING,
        SymptomOption.BLACK_STOOLS,
        SymptomOption.STOMACH,
        SymptomOption.SWALLOWING,
        SymptomOption.BLOOD,
        SymptomOption.SEVERE_TUMMY_PAIN,
        SymptomOption.CANT_KEEP_FLUIDS,
        SymptomOption.GREEN_VOMIT,
        SymptomOption.DEHYDRATION,
    )
    put(ProblemArea.BALANCE_FAINTING, SymptomOption.DIZZY_FAINT)
    put(
        ProblemArea.HEAD,
        SymptomOption.ONE_SIDE_WEAK,
        SymptomOption.SPEECH,
        SymptomOption.CONFUSED,
        SymptomOption.SUDDEN_HEADACHE,
        SymptomOption.HEADACHE,
        SymptomOption.STIFF_NECK,
    )
    put(ProblemArea.TEMPERATURE, SymptomOption.FEVER, SymptomOption.CHILLS, SymptomOption.SWEATING)
    put(ProblemArea.SKIN, SymptomOption.RASH, SymptomOption.NON_BLANCHING_RASH, SymptomOption.BLUE_SKIN)
    put(ProblemArea.SLEEP, SymptomOption.POOR_SLEEP, SymptomOption.TIRED_IN_DAY)
    put(ProblemArea.FEELINGS, SymptomOption.LOW_MOOD, SymptomOption.HOPELESS, SymptomOption.NO_ENJOYMENT)
    put(ProblemArea.ENERGY, SymptomOption.TIRED)
}

private val ONSET_COURSE = mapOf(
    SymptomOption.STARTED_NOW to ProblemCourse.NEW,
    SymptomOption.STARTED_TODAY to ProblemCourse.NEW,
    SymptomOption.STARTED_YESTERDAY to ProblemCourse.NEW,
    SymptomOption.STARTED_2_3_DAYS to ProblemCourse.NEW,
    SymptomOption.STARTED_A_WEEK to ProblemCourse.RECENT,
    SymptomOption.STARTED_A_MONTH to ProblemCourse.LASTING,
    SymptomOption.STARTED_LONGER to ProblemCourse.LASTING,
    SymptomOption.FEW_DAYS to ProblemCourse.NEW,
    SymptomOption.ABOUT_A_WEEK to ProblemCourse.RECENT,
    SymptomOption.TWO_WEEKS_PLUS to ProblemCourse.LASTING,
    SymptomOption.MONTHS to ProblemCourse.LASTING,
    SymptomOption.TODAY to ProblemCourse.NEW,
)

/** Three or more things it gets in the way of is "much"; one or two is "some". */
private const val MUCH_AFFECTED = 3

/**
 * [report] sorted, partial chats too: worked out on every write, from what has been asked so far.
 * [earlier] is the chat this one continues, whose start stands for this one's when it is "the same".
 */
fun classifySymptomChat(report: SymptomReport, earlier: EarlierChat?): SymptomTag {
    val told = report.asTold()
    val topic = told.symptom
    val area = topic?.let(TOPIC_AREA::get) ?: ProblemArea.UNSORTED
    val words = report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Wrote>().joinToString(" ") { it.text }
    return SymptomTag(
        area = area,
        also = told.answers[SymptomStep.WITH].orEmpty()
            .mapNotNull(OPTION_AREA::get)
            .distinct()
            .filter { it != area }
            .sortedBy { it.ordinal },
        urgency = chatUrgency(told),
        course = courseOf(told, earlier),
        recurring = told.has(SymptomStep.PATTERN, SymptomOption.COMES_AND_GOES) ||
            told.answers[SymptomStep.HOW_OFTEN].orEmpty().isNotEmpty() ||
            told.has(SymptomStep.SAME, SymptomOption.SAME_AS_BEFORE),
        impact = impactOf(told),
        mind = topic?.mind == true,
        flags = firedRules(told).map { it.id }.filter { it !in CRISIS_RULE_IDS },
        mentioned = mentionedTopics(words),
    )
}

/**
 * One thread of a chat, sorted by its topic's record: a symptom as [classifySymptomChat] always has; a
 * pain by its body families and the red flags ticked; a mood by its face; a dose or a course as
 * medicines; a thread with no topic as nothing yet. [facts] are what the topic's script says of it
 * ([TopicScript.tagFacts]). The tag goes into the thread and, for a symptom, into its row — never
 * into a pain, a mood, a dose or a course.
 */
internal fun classifyThread(report: SymptomReport, earlier: EarlierChat?, facts: TagFacts): SymptomTag {
    val told = report.asTold()
    val topic = told.symptom
    if (topic == null || topic.record == ChatRecord.SYMPTOM) return classifySymptomChat(report, earlier)
    val words = report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Wrote>().joinToString(" ") { it.text }
    val fired = firedRules(told).map { it.id }
    val base = SymptomTag(
        area = ProblemArea.UNSORTED,
        also = emptyList(),
        urgency = chatUrgency(told),
        course = ProblemCourse.UNKNOWN,
        recurring = false,
        impact = ProblemImpact.UNKNOWN,
        mind = topic.mind,
        flags = fired.filter { it !in CRISIS_RULE_IDS },
        mentioned = mentionedTopics(words),
    )
    return when (topic.record) {
        ChatRecord.SYMPTOM -> base

        ChatRecord.PAIN -> painTag(base, told, facts, fired)

        ChatRecord.MOOD -> moodTag(base, facts, fired)

        ChatRecord.DOSE, ChatRecord.COURSE -> base.copy(
            area = ProblemArea.MEDICINES,
            also = facts.alsoTopics.mapNotNull(TOPIC_AREA::get).areasBesides(ProblemArea.MEDICINES),
            course = if (topic.record == ChatRecord.COURSE) ProblemCourse.NEW else ProblemCourse.UNKNOWN,
        )
    }
}

/** A pain's area: the chest first, then the head and the jaw, then the belly, else the body's frame. */
private fun familyArea(family: String): ProblemArea = when (family) {
    "CHEST" -> ProblemArea.CHEST_HEARTBEAT
    "HEAD", "JAW" -> ProblemArea.HEAD
    "BELLY" -> ProblemArea.STOMACH_GUT
    else -> ProblemArea.MUSCULOSKELETAL
}

private val FAMILY_AREA_ORDER = listOf(
    ProblemArea.CHEST_HEARTBEAT,
    ProblemArea.HEAD,
    ProblemArea.STOMACH_GUT,
    ProblemArea.MUSCULOSKELETAL,
)

/** The areas the pain thread's red flags point to, besides the pain's own. */
private val SIGN_AREA: Map<SymptomOption, ProblemArea> = buildMap {
    fun put(area: ProblemArea, vararg options: SymptomOption) = options.forEach { put(it, area) }
    put(ProblemArea.CHEST_HEARTBEAT, SymptomOption.CHEST_TOO)
    put(ProblemArea.BREATHING, SymptomOption.BREATHLESS, SymptomOption.BREATHING_HARD)
    put(ProblemArea.TEMPERATURE, SymptomOption.FEVERISH, SymptomOption.HIGH_TEMP)
    put(ProblemArea.STOMACH_GUT, SymptomOption.VOMITING, SymptomOption.VOMIT_BLOOD, SymptomOption.BLACK_POO)
    put(
        ProblemArea.HEAD,
        SymptomOption.SEIZURE,
        SymptomOption.NUMB_WEAK,
        SymptomOption.SPEAK_BALANCE,
        SymptomOption.DROWSY_CONFUSED,
        SymptomOption.VISION_LOSS,
    )
    put(ProblemArea.SKIN, SymptomOption.NON_BLANCHING_RASH)
    put(ProblemArea.BALANCE_FAINTING, SymptomOption.DIZZY_FAINT, SymptomOption.PASSED_OUT)
}

private val PAIN_SIGN_STEPS = listOf(SymptomStep.PAIN_SIGNS, SymptomStep.PAIN_SIGNS_MORE, SymptomStep.PAIN_SEPSIS)

private fun painTag(base: SymptomTag, told: SymptomReport, facts: TagFacts, fired: List<String>): SymptomTag {
    val areas = facts.families.map(::familyArea).toSet()
    val area = FAMILY_AREA_ORDER.firstOrNull { it in areas } ?: ProblemArea.MUSCULOSKELETAL
    val signs = PAIN_SIGN_STEPS.flatMap { told.answers[it].orEmpty() }.mapNotNull(SIGN_AREA::get)
    val start = facts.course ?: told.answers[SymptomStep.ONSET]?.firstOrNull()
    val painRules = PAIN_RULES.mapTo(HashSet()) { it.id }
    return base.copy(
        area = area,
        also = (areas + signs).toList().areasBesides(area),
        course = start?.let(ONSET_COURSE::get) ?: ProblemCourse.UNKNOWN,
        recurring = facts.recurring || told.has(SymptomStep.PAIN_SAME, SymptomOption.PAIN_SAME_YES),
        flags = fired.filter { it in painRules },
    )
}

/** Two or lower is a low mood, three the middle, four and five the bright side; none told is unsorted. */
private const val MOOD_LOW_MAX = 2
private const val MOOD_MIDDLE = 3

private fun moodTag(base: SymptomTag, facts: TagFacts, fired: List<String>): SymptomTag {
    val score = facts.score
    val moodRules = MOOD_RULES.mapTo(HashSet()) { it.id }
    return base.copy(
        area = when {
            score == null -> ProblemArea.UNSORTED
            score <= MOOD_LOW_MAX -> ProblemArea.FEELINGS
            score == MOOD_MIDDLE -> ProblemArea.MOOD_NEUTRAL
            else -> ProblemArea.MOOD_POSITIVE
        },
        course = facts.course?.let(ONSET_COURSE::get) ?: ProblemCourse.UNKNOWN,
        mind = score != null && score <= MOOD_LOW_MAX,
        flags = fired.filter { it in moodRules },
    )
}

/** Distinct, without [area], in the order the areas are declared. */
private fun List<ProblemArea>.areasBesides(area: ProblemArea): List<ProblemArea> =
    distinct().filter { it != area }.sortedBy { it.ordinal }

private fun courseOf(told: SymptomReport, earlier: EarlierChat?): ProblemCourse {
    val start = if (told.has(SymptomStep.SAME, SymptomOption.SAME_AS_BEFORE)) {
        earlier?.onset
    } else {
        (told.answers[SymptomStep.ONSET].orEmpty() + told.answers[SymptomStep.HOW_LONG].orEmpty()).firstOrNull()
    }
    return start?.let(ONSET_COURSE::get) ?: ProblemCourse.UNKNOWN
}

/**
 * How much it gets in the way. "Mild" is not "not at all": only "nothing much" to what it affects
 * says none.
 */
private fun impactOf(told: SymptomReport): ProblemImpact {
    val affects = told.answers[SymptomStep.AFFECTS].orEmpty().filter { it != SymptomOption.NOT_REALLY }
    return when {
        told.has(SymptomStep.COPING, SymptomOption.NOT_COPING) ||
            told.severity == SymptomSeverity.SEVERE ||
            affects.size >= MUCH_AFFECTED -> ProblemImpact.MUCH

        told.has(SymptomStep.COPING, SymptomOption.STRUGGLING, SymptomOption.TRIED_NOT_HELPING) ||
            told.severity == SymptomSeverity.MODERATE ||
            affects.isNotEmpty() -> ProblemImpact.SOME

        told.has(SymptomStep.AFFECTS, SymptomOption.NOT_REALLY) -> ProblemImpact.NONE

        else -> ProblemImpact.UNKNOWN
    }
}

/** The tag as the payload keeps it, in one fixed order: `also` and `mentioned` only when not empty. */
internal fun encodeSymptomTag(tag: SymptomTag): String {
    fun names(values: List<Enum<*>>) = values.joinToString(",", "[", "]") { "\"${it.name}\"" }
    val fields = mutableListOf(
        "\"v\":${tag.version}",
        "\"area\":\"${tag.area.name}\"",
    )
    if (tag.also.isNotEmpty()) fields += "\"also\":" + names(tag.also)
    fields += "\"urgency\":\"${tag.urgency.name}\""
    fields += "\"course\":\"${tag.course.name}\""
    fields += "\"recurring\":${tag.recurring}"
    fields += "\"impact\":\"${tag.impact.name}\""
    fields += "\"mind\":${tag.mind}"
    fields += "\"flags\":" + tag.flags.joinToString(",", "[", "]") { JSONObject.quote(it) }
    if (tag.mentioned.isNotEmpty()) fields += "\"mentioned\":" + names(tag.mentioned)
    return fields.joinToString(",", "{", "}")
}

/** The tag back, or none when its area is one this build does not know. */
internal fun decodeSymptomTag(json: JSONObject): SymptomTag? {
    fun strings(key: String): List<String> {
        val array: JSONArray = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.opt(it) as? String }
    }
    val area = ProblemArea.entries.firstOrNull { it.name == json.optString("area") } ?: return null
    return SymptomTag(
        area = area,
        also = strings("also").mapNotNull { name -> ProblemArea.entries.firstOrNull { it.name == name } },
        urgency = SymptomUrgency.entries.firstOrNull { it.name == json.optString("urgency") }
            ?: SymptomUrgency.SELF_CARE,
        course = ProblemCourse.entries.firstOrNull { it.name == json.optString("course") } ?: ProblemCourse.UNKNOWN,
        recurring = json.optBoolean("recurring"),
        impact = ProblemImpact.entries.firstOrNull { it.name == json.optString("impact") } ?: ProblemImpact.UNKNOWN,
        mind = json.optBoolean("mind"),
        flags = strings("flags"),
        mentioned = strings("mentioned").mapNotNull { name -> Symptom.entries.firstOrNull { it.name == name } },
        version = json.optInt("v", SYMPTOM_TAG_VERSION),
    )
}
