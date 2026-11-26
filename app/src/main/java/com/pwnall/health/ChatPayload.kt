package com.pwnall.health

import androidx.compose.runtime.saveable.Saver
import org.json.JSONObject

// A chat's payload, schema 1, written by hand in one fixed order as every payload of this app is:
// org.json's own writer orders keys by a hash map on the JVM and by insertion on the device. Free
// text goes through JSONObject.quote; everything else is an enum name, a number or `true`; optional
// keys only when set. The reader skips whatever it does not know, so a later build's chat still
// opens here with what this one understands.
//
// CHAT   = {"schema":1,"rev":N,["past":true,]"threads":[THREAD,…],"next":N,["end":"ALL"|"STOP",]["note":"…",]
//           ["noteread":READ,]["held":HELD]}
// THREAD = {"id":N,["from":"KEY",]["noroute":true,]"report":REPORT,["filed":[LINK,…]],["draft":DRAFT],
//           ["cards":[CARD,…]]}
// REPORT = encodeSymptomPayload(report), with its values, frozen facts and painting
// LINK   = {"k":"KIND",["r":"ROLE",]"at":MS,"t":"TITLE",["start":"YYYY-MM-DD",]["slot":"S",]["course":MS,]
//           ["made":false,]["prior":"…",]"sig":N,"wsig":N,["state":"…",]["err":true]}
// DRAFT  = {"strokes":[…],"added":["CODE",…],"removed":["CODE",…]}
// CARD   = {"m":N,"t":"TYPE","s":N,"e":N,"st":"DECLINED"|"OPENED"|"ADDED",["k":"KIND","id":N,"at":MS,"ti":"…",
//          "amt":N]}: what became of a record card — its message's ordinal, its type, its words' ends — and
//          the row an Add wrote
// READ   = what the chat took from its model's reading of the note ([encodeChatRead]), and of a message in REPORT
// HELD   = {"thread":N,"message":N} | {"step":"STEP","text":"…"}: what waits for a reading ([ChatHeld])

/** The chat payload's own schema, raised only when a key changes its meaning. */
private const val CHAT_SCHEMA = 1

/** The kinds a link names: the journal's, and [LINK_COURSE]. A link of any other is a later build's, and skipped. */
private val LINK_KINDS = setOf(KIND_SYMPTOM, KIND_PAIN, KIND_MOOD, KIND_MED, LINK_COURSE)

/**
 * The chat as its row keeps it ([pending] false) or as a rotation keeps it ([pending] true). The
 * database form keeps of every thread only what was asked ([forSave]); the Bundle form keeps the
 * ticks, values and painting still waiting for the clock or for Send as well, so nothing the person
 * did is lost to a rotation or the process being killed.
 */
internal fun encodeChatLog(log: ChatLog, pending: Boolean): String {
    val fields = mutableListOf("\"schema\":$CHAT_SCHEMA", "\"rev\":${log.rev}")
    if (log.past) fields += "\"past\":true"
    fields += "\"threads\":" + log.threads.joinToString(",", "[", "]") { encodeThread(it, pending) }
    fields += "\"next\":${log.next}"
    log.end?.let { fields += "\"end\":\"${it.name}\"" }
    if (log.note.isNotEmpty()) fields += "\"note\":" + JSONObject.quote(log.note)
    log.noteRead?.takeIf { log.note.isNotEmpty() }?.let { fields += "\"noteread\":" + encodeChatRead(it) }
    log.held?.let { fields += "\"held\":" + encodeHeld(it) }
    if (log.attachments.isNotEmpty()) fields += "\"attachments\":" + JSONObject.quote(log.attachments)
    return fields.joinToString(",", "{", "}")
}

private fun encodeHeld(held: ChatHeld): String = when (held) {
    is ChatHeld.Message -> "{\"thread\":${held.place.thread},\"message\":${held.place.ordinal}}"
    is ChatHeld.Typed -> "{\"step\":\"${held.step.name}\",\"text\":" + JSONObject.quote(held.text) + "}"
}

private fun encodeThread(thread: ChatThread, pending: Boolean): String {
    val fields = mutableListOf("\"id\":${thread.id}")
    thread.from?.let { fields += "\"from\":" + JSONObject.quote(it) }
    if (thread.noRoute) fields += "\"noroute\":true"
    fields += "\"report\":" + encodeSymptomPayload(if (pending) thread.report else thread.report.forSave())
    if (thread.filed.isNotEmpty()) fields += "\"filed\":" + thread.filed.joinToString(",", "[", "]") { encodeLink(it) }
    thread.draft?.let { fields += "\"draft\":" + encodeDraft(it) }
    if (thread.cards.isNotEmpty()) {
        fields += "\"cards\":" + thread.cards.entries
            .sortedWith(compareBy({ it.key.message }, { it.key.start }, { it.key.end }, { it.key.type.name }))
            .joinToString(",", "[", "]") { (key, mark) -> encodeCard(key, mark) }
    }
    return fields.joinToString(",", "{", "}")
}

private fun encodeLink(link: FiledLink): String {
    val fields = mutableListOf("\"k\":" + JSONObject.quote(link.kind))
    if (link.role.isNotEmpty()) fields += "\"r\":" + JSONObject.quote(link.role)
    fields += "\"at\":${link.at}"
    fields += "\"t\":" + JSONObject.quote(link.title)
    link.start?.let { fields += "\"start\":" + JSONObject.quote(it) }
    link.slot?.let { fields += "\"slot\":" + JSONObject.quote(it) }
    link.course?.let { fields += "\"course\":$it" }
    if (!link.made) fields += "\"made\":false"
    link.prior?.let { fields += "\"prior\":" + JSONObject.quote(it) }
    fields += "\"sig\":${link.sig}"
    fields += "\"wsig\":${link.wsig}"
    if (link.state != LinkState.FILED) fields += "\"state\":\"${link.state.name}\""
    if (link.err) fields += "\"err\":true"
    return fields.joinToString(",", "{", "}")
}

private fun encodeDraft(draft: MapDraft): String {
    fun codes(list: List<String>) = list.joinToString(",", "[", "]") { JSONObject.quote(it) }
    return "{\"strokes\":" + (painStrokesJson(draft.strokes) ?: "[]") + ",\"added\":" + codes(draft.added) +
        ",\"removed\":" + codes(draft.removed) + "}"
}

/**
 * The chat back out of its payload, or null for a payload that is not JSON at all. A thread with no
 * id or no report, a link of a kind or a state this build does not know, and any key it does not
 * know are skipped, and nothing else with them.
 */
internal fun decodeChatLog(payload: String): ChatLog? {
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    val threads = json.optJSONArray("threads")?.objects().orEmpty().mapNotNull(::decodeThread)
    return ChatLog(
        threads = threads,
        next = (json.opt("next") as? Number)?.toInt() ?: ((threads.maxOfOrNull { it.id } ?: -1) + 1),
        end = ChatEnd.entries.firstOrNull { it.name == json.optString("end") },
        note = (json.opt("note") as? String).orEmpty(),
        past = json.optBoolean("past"),
        rev = (json.opt("rev") as? Number)?.toInt() ?: 0,
        noteRead = json.optJSONObject("noteread")?.let(::decodeChatRead),
        held = json.optJSONObject("held")?.let(::decodeHeld),
        attachments = (json.opt("attachments") as? String).orEmpty(),
    )
}

/** What waits for a reading, back out of its payload; a step this build does not know, or no words, is none. */
private fun decodeHeld(json: JSONObject): ChatHeld? {
    val thread = (json.opt("thread") as? Number)?.toInt()
    val message = (json.opt("message") as? Number)?.toInt()
    val step = SymptomStep.entries.firstOrNull { it.name == json.optString("step") }
    val text = (json.opt("text") as? String)?.takeIf { it.isNotBlank() }
    return when {
        thread != null && message != null -> ChatHeld.Message(MessagePlace(thread, message))
        step != null && text != null -> ChatHeld.Typed(step, text)
        else -> null
    }
}

private fun decodeThread(json: JSONObject): ChatThread? {
    val id = (json.opt("id") as? Number)?.toInt() ?: return null
    val report = json.optJSONObject("report")?.let(::decodeSymptomReport) ?: return null
    return ChatThread(
        id = id,
        report = report,
        from = (json.opt("from") as? String)?.takeIf { it.isNotEmpty() },
        filed = json.optJSONArray("filed")?.objects().orEmpty().mapNotNull(::decodeLink),
        draft = json.optJSONObject("draft")?.let(::decodeDraft),
        noRoute = json.optBoolean("noroute"),
        cards = json.optJSONArray("cards")?.objects().orEmpty().mapNotNull(::decodeCard).toMap(),
    )
}

private fun encodeCard(key: JournalKey, mark: CardMark): String {
    val fields = mutableListOf(
        "\"m\":${key.message}",
        "\"t\":\"${key.type.name}\"",
        "\"s\":${key.start}",
        "\"e\":${key.end}",
    )
    when (mark) {
        CardMark.Declined -> fields += "\"st\":\"DECLINED\""

        CardMark.Opened -> fields += "\"st\":\"OPENED\""

        is CardMark.Added -> fields += listOf(
            "\"st\":\"ADDED\"",
            "\"k\":" + JSONObject.quote(mark.kind),
            "\"id\":${mark.id}",
            "\"at\":${mark.at}",
            "\"ti\":" + JSONObject.quote(mark.title),
            // NaN or an infinity is no JSON number: written as it is, the device's reader would refuse the whole chat.
            "\"amt\":${mark.amount.takeIf { it.isFinite() } ?: 0.0}",
        )
    }
    return fields.joinToString(",", "{", "}")
}

/**
 * A record card's mark back out of its payload: none with a number missing, or a type, a state or an
 * added row this build does not know.
 */
private fun decodeCard(json: JSONObject): Pair<JournalKey, CardMark>? {
    val type = ChatJournalType.entries.firstOrNull { it.name == json.optString("t") }
    val numbers = CARD_NUMBERS.mapNotNull { (json.opt(it) as? Number)?.toInt() }
    val mark = when (json.optString("st")) {
        "DECLINED" -> CardMark.Declined
        "OPENED" -> CardMark.Opened
        "ADDED" -> addedMark(json)
        else -> null
    }
    val whole = type != null && mark != null && numbers.size == CARD_NUMBERS.size
    return if (whole) JournalKey(numbers[0], checkNotNull(type), numbers[1], numbers[2]) to checkNotNull(mark) else null
}

private fun addedMark(json: JSONObject): CardMark.Added? {
    val kind = (json.opt("k") as? String)?.takeIf { it.isNotEmpty() }
    val id = (json.opt("id") as? Number)?.toLong()
    val at = (json.opt("at") as? Number)?.toLong()
    val title = (json.opt("ti") as? String).orEmpty()
    val amount = (json.opt("amt") as? Number)?.toDouble() ?: 0.0
    return if (kind != null && id != null && at != null) CardMark.Added(kind, id, at, title, amount) else null
}

/** A card's message, and the ends of its words. */
private val CARD_NUMBERS = listOf("m", "s", "e")

private fun decodeLink(json: JSONObject): FiledLink? {
    val kind = (json.opt("k") as? String)?.takeIf { it in LINK_KINDS } ?: return null
    val at = (json.opt("at") as? Number)?.toLong() ?: return null
    val name = json.optString("state")
    val state = if (name == "") {
        LinkState.FILED
    } else {
        LinkState.entries.firstOrNull { it.name == name } ?: return null
    }
    return FiledLink(
        kind = kind,
        role = (json.opt("r") as? String).orEmpty(),
        at = at,
        title = (json.opt("t") as? String).orEmpty(),
        start = json.opt("start") as? String,
        slot = json.opt("slot") as? String,
        course = (json.opt("course") as? Number)?.toLong(),
        made = json.optBoolean("made", true),
        prior = json.opt("prior") as? String,
        sig = (json.opt("sig") as? Number)?.toInt() ?: 0,
        wsig = (json.opt("wsig") as? Number)?.toInt() ?: 0,
        state = state,
        err = json.optBoolean("err"),
    )
}

private fun decodeDraft(json: JSONObject): MapDraft {
    fun codes(key: String): List<String> = json.optJSONArray(key)?.let { array ->
        (0 until array.length()).mapNotNull { array.opt(it) as? String }
    }.orEmpty()
    return MapDraft(painStrokesFrom(json.optJSONArray("strokes")), codes("added"), codes("removed"))
}

/**
 * The chat across a rotation and the process being killed: the payload in its Bundle form, which
 * keeps what is still waiting on the last thread ([encodeChatLog]).
 */
internal val CHAT_LOG_SAVER: Saver<ChatLog, String> = Saver(
    save = { encodeChatLog(it, pending = true) },
    restore = ::decodeChatLog,
)
