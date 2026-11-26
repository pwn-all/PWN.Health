package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject

// What the chat took from its model's reading of one message, kept with
// the message in the chat's payload, and how the router reads it beside its rules. A message is
// read once, when it is sent; from then on the chat redraws it from these decisions, word for word,
// whatever model a later build carries, and never reads it again. They are decisions, never
// probabilities: the payload goes into the backup and to a new phone («Хранение»).
//
// The rules stay the floor: every question, chip and mark they give is given as without a reading, and a
// reading only adds — a safety flag its one fixed question (reader-schema.json `adds_step`), the topics as
// chips where the rules found none or, sure enough, beside theirs, what was told as marks and one-tap
// proposals on the questions a reading may reach. It never answers, never files, never starts a thread
// of its own, never takes the line to call for an emergency away ([WordsFound.language] is the rules'
// alone), and a reading of [ChatRead.shadow] or with no model behind it changes nothing at all.
//
// READ     = {["model":"…",]["why":"…",]["shadow":true,]["safety":["STEP",…],]["topics":["TOPIC",…],]
//             ["add":["TOPIC",…],]["unwell":true,]["about":"ADULT_OTHER"|"CHILD"|"PET",]["told":TOLD]}
// TOLD     = {["places":[PLACE,…],]["onset":"OPTION",]["types":["ACHING",…],]["noticed":"OPTION",]
//             ["status":"OPTION",]["same":"OPTION",]["signs":["OPTION",…],]["intensity":["SEVERE",…],]
//             ["nrs":N,]["painkiller":true,]["medicines":[MEDICINE,…],]["amounts":["…",…],]["clocks":[N,…]]}
// PLACE    = {["family":"KNEE",]["area":"KNEE",]["side":"L"|"R"|"BOTH"]}
// MEDICINE = {"name":"…",["roles":["TAKEN",…]]}
// Written by hand in that order, free text through JSONObject.quote, each key only when set; read
// back skipping whatever this build does not know, as every payload of the app is.

/**
 * The decisions the chat took from its model's reading of one message.
 * [model] is the version that read it, as its manifest names it — null when none could, and [why]
 * then says why the chat kept to its rules for it ("timeout", "load_failed"…); "abstain" keeps the
 * safety flags alone. [shadow]: read by a build whose switch keeps the readings without using them.
 * [safety] are the fixed safety questions its flags added, in the script's order; [topics] the
 * topics it proposes as chips where the rules found none, the likeliest first, and [add] those of
 * them sure enough to stand beside what the rules found; [unwell] feeling unwell and nothing of
 * what, of oneself; [about] someone other than the person; [told] what it read the message to tell.
 */
data class ChatRead(
    val model: String? = null,
    val why: String? = null,
    val shadow: Boolean = false,
    val safety: List<SymptomStep> = emptyList(),
    val topics: List<Symptom> = emptyList(),
    val add: List<Symptom> = emptyList(),
    val unwell: Boolean = false,
    val about: ReadAbout? = null,
    val told: ReadTold = ReadTold(),
    val journal: List<ChatJournalEvent> = emptyList(),
) {
    /** Whether the chat routes by this reading: a model read it, and not in the shadow. */
    val routes: Boolean get() = model != null && !shadow

    /**
     * Whether the model read in an enabled language ([ChatReading.examined]): the chat can use
     * its topic and entity proposals, or it abstained. The name is the one stored decisions carry, and
     * certifies no exam of the model. The chat then identifies model-assisted reading.
     */
    val examined: Boolean get() = routes && (why == null || why == ChatReadOutcome.ABSTAIN)

    /** Whether it added the safety question [step]. */
    fun raises(step: SymptomStep): Boolean = routes && step in safety
}

/** Whom a message is about when it is not the person themselves. */
enum class ReadAbout { ADULT_OTHER, CHILD, PET }

/** How a pain was told: very bad, sudden, or mild — which marks the red flags as the rules' words do. */
enum class ReadIntensity { SEVERE, SUDDEN, MILD }

/** What a medicine named was said to be (`MedVerb`): taken, prescribed, stopped… */
enum class ReadRole { TAKEN, PRESCRIBED, RECIPE, REGULAR, MISSED, STOPPED, EXCLUDED }

/**
 * What a reading says a message told: the places of a pain with their
 * sides, when it started (already a chip, counted from the chat's day), its kinds, when it was
 * noticed, whether it is still there and the same as before, its red flags' marks, how it was told,
 * a number out of ten, a painkiller taken; the medicines named with what was said of them, amounts
 * and clock times as written. Marks and proposals only: the person's tap is every answer.
 */
data class ReadTold(
    val places: List<ReadPlace> = emptyList(),
    val onset: SymptomOption? = null,
    val types: List<PainType> = emptyList(),
    val noticed: SymptomOption? = null,
    val status: SymptomOption? = null,
    val same: SymptomOption? = null,
    val signs: List<SymptomOption> = emptyList(),
    val intensity: List<ReadIntensity> = emptyList(),
    val nrs: Int? = null,
    val painkiller: Boolean = false,
    val medicines: List<ReadMedicine> = emptyList(),
    val amounts: List<String> = emptyList(),
    val clocks: List<Int> = emptyList(),
)

/**
 * A place of a pain: the body [family] (a family of the pain thread's, "KNEE"), the [area] of the
 * current map when it was named ("BACK_OF_KNEE"), and the [side] said — "L", "R" or "BOTH"; none
 * when it was not.
 */
data class ReadPlace(val family: String? = null, val area: String? = null, val side: String? = null)

/** A medicine as it was written, and what was said of it. */
data class ReadMedicine(val name: String, val roles: List<ReadRole> = emptyList())

/**
 * The safety questions a reading may add: one per flag, the app's own (ReaderSafety, reader-schema.json
 * `adds_step`).
 */
internal val READ_SAFETY_STEPS: List<SymptomStep> = ReaderSafety.entries.map { it.step }

/** The sides a place may be told on. */
internal val READ_SIDES = setOf(SIDE_LEFT, SIDE_RIGHT, SIDE_BOTH)

internal const val SIDE_LEFT = "L"
internal const val SIDE_RIGHT = "R"
internal const val SIDE_BOTH = "BOTH"

private const val MINUTES_A_DAY = 24 * 60

// ── The codec ──

/** [read] as its payload keeps it, in the fixed order above. */
internal fun encodeChatRead(read: ChatRead): String {
    val fields = mutableListOf<String>()
    read.model?.let { fields += "\"model\":" + JSONObject.quote(it) }
    read.why?.let { fields += "\"why\":" + JSONObject.quote(it) }
    if (read.shadow) fields += "\"shadow\":true"
    if (read.safety.isNotEmpty()) fields += "\"safety\":" + names(read.safety)
    if (read.topics.isNotEmpty()) fields += "\"topics\":" + names(read.topics)
    if (read.add.isNotEmpty()) fields += "\"add\":" + names(read.add)
    if (read.unwell) fields += "\"unwell\":true"
    read.about?.let { fields += "\"about\":\"${it.name}\"" }
    encodeTold(read.told)?.let { fields += "\"told\":$it" }
    if (read.journal.isNotEmpty()) fields += "\"journal\":" + encodeJournalEvents(read.journal)
    return fields.joinToString(",", "{", "}")
}

private fun encodeTold(told: ReadTold): String? {
    val fields = mutableListOf<String>()
    if (told.places.isNotEmpty()) {
        fields += "\"places\":" + told.places.joinToString(",", "[", "]", transform = ::encodePlace)
    }
    told.onset?.let { fields += "\"onset\":\"${it.name}\"" }
    if (told.types.isNotEmpty()) fields += "\"types\":" + names(told.types)
    told.noticed?.let { fields += "\"noticed\":\"${it.name}\"" }
    told.status?.let { fields += "\"status\":\"${it.name}\"" }
    told.same?.let { fields += "\"same\":\"${it.name}\"" }
    if (told.signs.isNotEmpty()) fields += "\"signs\":" + names(told.signs)
    if (told.intensity.isNotEmpty()) fields += "\"intensity\":" + names(told.intensity)
    told.nrs?.let { fields += "\"nrs\":$it" }
    if (told.painkiller) fields += "\"painkiller\":true"
    if (told.medicines.isNotEmpty()) {
        fields += "\"medicines\":" + told.medicines.joinToString(",", "[", "]", transform = ::encodeMedicine)
    }
    if (told.amounts.isNotEmpty()) {
        fields += "\"amounts\":" + told.amounts.joinToString(",", "[", "]") { JSONObject.quote(it) }
    }
    if (told.clocks.isNotEmpty()) fields += "\"clocks\":" + told.clocks.joinToString(",", "[", "]")
    return fields.takeIf { it.isNotEmpty() }?.joinToString(",", "{", "}")
}

private fun encodePlace(place: ReadPlace): String = listOfNotNull(
    place.family?.let { "\"family\":" + JSONObject.quote(it) },
    place.area?.let { "\"area\":" + JSONObject.quote(it) },
    place.side?.let { "\"side\":" + JSONObject.quote(it) },
).joinToString(",", "{", "}")

private fun encodeMedicine(medicine: ReadMedicine): String {
    val fields = mutableListOf("\"name\":" + JSONObject.quote(medicine.name))
    if (medicine.roles.isNotEmpty()) fields += "\"roles\":" + names(medicine.roles)
    return fields.joinToString(",", "{", "}")
}

private fun names(values: List<Enum<*>>): String = values.joinToString(",", "[", "]") { "\"${it.name}\"" }

/**
 * A reading back out of its payload. A key this build does not know is skipped, and so is a value it
 * does not know — a step, a topic, an option of a later build — each by itself; a safety step that is
 * not one of the seven a flag adds is no step at all, so no payload can make a reading ask anything
 * else. A number out of ten outside 1–10 and a clock time outside the day are none.
 */
internal fun decodeChatRead(json: JSONObject): ChatRead = ChatRead(
    model = (json.opt("model") as? String)?.takeIf { it.isNotEmpty() },
    why = (json.opt("why") as? String)?.takeIf { it.isNotEmpty() },
    shadow = json.optBoolean("shadow"),
    safety = enums<SymptomStep>(json.optJSONArray("safety")).filter { it in READ_SAFETY_STEPS }.distinct(),
    topics = enums<Symptom>(json.optJSONArray("topics")).distinct(),
    add = enums<Symptom>(json.optJSONArray("add")).distinct(),
    unwell = json.optBoolean("unwell"),
    about = enumOf<ReadAbout>(json.opt("about")),
    told = json.optJSONObject("told")?.let(::decodeTold) ?: ReadTold(),
    journal = decodeJournalEvents(json.optJSONArray("journal")),
)

private fun decodeTold(json: JSONObject): ReadTold = ReadTold(
    places = json.optJSONArray("places")?.objects().orEmpty().mapNotNull(::decodePlace),
    onset = enumOf<SymptomOption>(json.opt("onset")),
    types = enums<PainType>(json.optJSONArray("types")).distinct(),
    noticed = enumOf<SymptomOption>(json.opt("noticed")),
    status = enumOf<SymptomOption>(json.opt("status")),
    same = enumOf<SymptomOption>(json.opt("same")),
    signs = enums<SymptomOption>(json.optJSONArray("signs")).distinct(),
    intensity = enums<ReadIntensity>(json.optJSONArray("intensity")).distinct(),
    nrs = (json.opt("nrs") as? Number)?.toInt()?.takeIf { it in PAIN_NRS_RANGE },
    painkiller = json.optBoolean("painkiller"),
    medicines = json.optJSONArray("medicines")?.objects().orEmpty().mapNotNull(::decodeMedicine),
    amounts = strings(json.optJSONArray("amounts")),
    clocks = numbers(json.optJSONArray("clocks")).filter { it in 0 until MINUTES_A_DAY },
)

private fun decodePlace(json: JSONObject): ReadPlace? {
    val family = (json.opt("family") as? String)?.takeIf { it.isNotEmpty() }
    val area = (json.opt("area") as? String)?.takeIf { name -> PainRegion.entries.any { it.name == name } }
    val side = (json.opt("side") as? String)?.takeIf { it in READ_SIDES }
    return if (family == null && area == null) null else ReadPlace(family, area, side)
}

private fun decodeMedicine(json: JSONObject): ReadMedicine? {
    val name = (json.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: return null
    return ReadMedicine(name, enums<ReadRole>(json.optJSONArray("roles")).distinct())
}

private inline fun <reified T : Enum<T>> enumOf(value: Any?): T? =
    (value as? String)?.let { name -> enumValues<T>().firstOrNull { it.name == name } }

private inline fun <reified T : Enum<T>> enums(array: JSONArray?): List<T> =
    (0 until (array?.length() ?: 0)).mapNotNull { enumOf<T>(array?.opt(it)) }

private fun strings(array: JSONArray?): List<String> =
    (0 until (array?.length() ?: 0)).mapNotNull { (array?.opt(it) as? String)?.takeIf(String::isNotBlank) }

private fun numbers(array: JSONArray?): List<Int> =
    (0 until (array?.length() ?: 0)).mapNotNull { (array?.opt(it) as? Number)?.toInt() }

// ── What the router reads of the stored readings ──

/**
 * The reading kept with [this] turn — a message, or words typed as an answer ([ChatTurn.Asked.read]) —
 * when it is one the chat routes by ([ChatRead.routes]).
 */
internal val ChatTurn.routingRead: ChatRead?
    get() = when (this) {
        is ChatTurn.Wrote -> read
        is ChatTurn.Asked -> read
        is ChatTurn.Said, is ChatTurn.Narrowed -> null
    }?.takeIf { it.routes }

/** The safety questions the readings of [this] chat's messages added, each once. */
internal fun SymptomChat.readSteps(): Set<SymptomStep> =
    turns.mapNotNull { it.routingRead }.flatMapTo(HashSet()) { it.safety }

/** Whether any turn of [this] chat has a reading the chat routes by. */
internal fun SymptomChat.routesByReadings(): Boolean = turns.any { it.routingRead != null }

/**
 * Whether a message [this] thread may read — its own, or an earlier thread's ([threadMessages]) — has a
 * reading the chat routes by: without one, what the readings told is nothing, and no message is read
 * again to find that out.
 */
internal fun ChatState.readsInThread(): Boolean =
    chat.routesByReadings() || thread.others.any { it.report.chat?.routesByReadings() == true }

/**
 * [this] chat as its rules alone read it: every message, and every answer typed, without the reading
 * kept with it — what a reply with the readings is held to, so a reading only adds.
 */
internal fun SymptomChat.withoutReadings(): SymptomChat =
    if (turns.all { it.keptRead() == null }) this else copy(turns = turns.map { it.withoutRead() })

/** [this] thread's state as the rules alone read it ([SymptomChat.withoutReadings]). */
internal fun ChatState.withoutReadings(): ChatState {
    val report = report.withoutReadings()
    return if (report === this.report) this else copy(report = report)
}

/** [this] report as the rules alone read it ([SymptomChat.withoutReadings]); itself when it keeps none. */
internal fun SymptomReport.withoutReadings(): SymptomReport {
    val chat = chat ?: return this
    val bare = chat.withoutReadings()
    return if (bare === chat) this else copy(chat = bare)
}

/** [this] chat as the rules alone read it: no message, typed answer or note with its reading. */
internal fun ChatLog.withoutReadings(): ChatLog = copy(
    threads = threads.map { thread ->
        val chat = thread.report.chat ?: return@map thread
        thread.copy(report = thread.report.copy(chat = chat.withoutReadings()))
    },
    noteRead = null,
)

/** The reading kept with [this] turn, whatever it is. */
private fun ChatTurn.keptRead(): ChatRead? = (this as? ChatTurn.Wrote)?.read ?: (this as? ChatTurn.Asked)?.read

private fun ChatTurn.withoutRead(): ChatTurn = when (this) {
    is ChatTurn.Wrote -> if (read == null) this else copy(read = null)
    is ChatTurn.Asked -> if (read == null) this else copy(read = null)
    is ChatTurn.Said, is ChatTurn.Narrowed -> this
}

/** Whether the message at turn [at] of [this] thread had its reading add the safety question [step]. */
internal fun SymptomReport.readRaises(at: Int, step: SymptomStep): Boolean =
    chat?.turns?.getOrNull(at)?.routingRead?.raises(step) == true

/** Whether any chat turn has a reading the chat routes by: the next-step decider works only then. */
internal fun ChatLog.routesByReadings(): Boolean = threads.any { it.report.chat?.routesByReadings() == true }

/**
 * Whether a model read anything of [this] chat — a message, an answer typed, the note — in a language
 * routing is enabled in ([ChatRead.examined]): what its "?" then says of how it reads ([chatAboutRes]).
 */
internal fun ChatLog.readExamined(): Boolean = noteRead?.examined == true ||
    threads.any { thread -> thread.report.chat?.turns.orEmpty().any { it.routingRead?.examined == true } }

/**
 * "Found none it knows" for [message], the one the chat answers: in a model's words — the AI model on
 * this phone found none of the chat's topics in it, and can be wrong — once a model read it in a
 * language routing is enabled in ([ChatRead.examined]); else the line of the chat without a model,
 * which looks for a few words only. Read from what is kept with the message, so the history says the same.
 */
internal fun noMatchLine(message: ChatTurn.Wrote?): ChatLine =
    if (message?.read?.examined == true) ChatLine.NO_MATCH_MODEL else ChatLine.NO_MATCH

/** The long remark for [message], written during a question: a model's words for one a model read ([noMatchLine]). */
internal fun longRemark(message: ChatTurn.Wrote): ChatLine =
    if (message.read?.examined == true) ChatLine.REMARK_MODEL else ChatLine.REMARK

/** What the readings of [messages] told, every message's together, each value once. */
internal fun toldIn(messages: List<ChatTurn.Wrote>): ReadTold {
    val told = messages.mapNotNull { it.read?.takeIf(ChatRead::routes)?.told }
    return ReadTold(
        places = told.flatMap { it.places }.distinct(),
        onset = told.mapNotNull { it.onset }.distinct().singleOrNull(),
        types = told.flatMap { it.types }.distinct(),
        noticed = told.mapNotNull { it.noticed }.distinct().singleOrNull(),
        status = told.mapNotNull { it.status }.distinct().singleOrNull(),
        same = told.mapNotNull { it.same }.distinct().singleOrNull(),
        signs = told.flatMap { it.signs }.distinct(),
        intensity = told.flatMap { it.intensity }.distinct(),
        nrs = told.mapNotNull { it.nrs }.distinct().singleOrNull(),
        painkiller = told.any { it.painkiller },
        medicines = told.flatMap { it.medicines }.mergedByName(),
        amounts = told.flatMap { it.amounts }.distinctBy { it.lowercase() },
        clocks = told.flatMap { it.clocks }.distinct(),
    )
}

/** The names [told] gave its medicines, as written. */
internal fun ReadTold.medicineNames(): List<String> = medicines.map { it.name }

/**
 * [this] medicines, one each by name — the first as written — with every role said of any of them:
 * "X every morning… today I missed X" is X taken regularly and missed, both.
 */
internal fun List<ReadMedicine>.mergedByName(): List<ReadMedicine> =
    groupBy { normalisedWords(it.name) }.values.map { same ->
        ReadMedicine(same.first().name, same.flatMap { it.roles }.distinct())
    }
