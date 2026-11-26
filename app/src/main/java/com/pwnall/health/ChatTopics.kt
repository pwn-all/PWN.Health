package com.pwnall.health

import android.content.res.Resources

// The one interface every topic of the chat implements, and the registry that hands each topic its
// script. A topic asks, says and files only through this: the router, the filer and the screen never
// switch on a topic, so a package that adds one (mood, medicine) fills its own script and touches no
// core `when`.

/**
 * What one topic of the chat asks, says and files. Pure: everything comes from [ChatState] and the
 * thread. One script serves every topic of its [Symptom.record].
 */
internal interface TopicScript {
    /** The thread's questions after its topic, in order, conditions applied (the first not yet asked is next). */
    fun steps(state: ChatState): List<SymptomStep>

    fun options(state: ChatState, step: SymptomStep): List<SymptomOption>

    /** What the question waits for: [ChatReply.Choose] by default. */
    fun reply(state: ChatState, step: SymptomStep): ChatReply = ChatReply.Choose(step, options(state, step))

    /** The question: a resource, or a [ChatText] through [BotPiece.Ask.text], with an optional help. */
    fun question(state: ChatState, step: SymptomStep): Int

    fun questionText(state: ChatState, step: SymptomStep): ChatText? = null

    fun help(state: ChatState, step: SymptomStep): ChatText? = null

    /** The lines between the last answer and the next question (the start line, acks, notes). */
    fun lead(state: ChatState, last: ChatTurn.Asked?, step: SymptomStep): List<BotPiece>

    /**
     * The thread's close: its alert, saved cards, close lines, offers. Never THANKS, which is the
     * router's end of the whole chat.
     */
    fun close(state: ChatState, alert: List<BotPiece>): List<BotPiece>

    /** Roles whose saved card stands in the turn after the answer to [step] (a closing turn's are in [close]). */
    fun savedAfter(state: ChatState, step: SymptomStep): List<String> = emptyList()

    /**
     * The card's line for a record as it is now ("Pain · Knee · right · 6/10 · aching"), its link
     * standing at [state]: an update undone or refused is no update any more.
     */
    fun savedSummary(
        resources: Resources,
        row: JournalEntry?,
        course: Medication?,
        role: String,
        state: LinkState = LinkState.FILED,
    ): String

    fun savedDetail(resources: Resources, row: JournalEntry?, course: Medication?, role: String): String? = null

    /**
     * What the thread wants filed now, by role, in the order roles are filed ([ChatFiling]): a
     * course before its doses, so a dose is built with the course's id. The `"words"` role of a
     * crisis or emergency answer outside a symptom thread is the filer's own ([WORDS_ROLE]).
     */
    fun wants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant>

    /**
     * A hash of the thread's record-defining answers for [role] — never of the row it builds, which
     * holds a title in the language showing and figures read from elsewhere. A record is rewritten
     * only when this changes.
     */
    fun recordSig(thread: ChatThread, role: String): Int = answersSig(thread)

    /** Facts frozen into `report.meta` when [step] is answered (pain's earlier row, mood's update target). */
    fun freeze(state: ChatState, step: SymptomStep): Map<String, String> = emptyMap()

    /** Pairs of steps whose earlier answers in this chat mark the other's chips. */
    val markPairs: List<Pair<SymptomStep, SymptomStep>> get() = emptyList()

    /** How each of its steps is answered ([SymptomStep.kind]); options with a tap or a clock by default. */
    val stepKinds: Map<SymptomStep, StepKind> get() = emptyMap()

    /**
     * What the router puts first at the prompt once this thread has closed: each a topic chip with
     * its own label ("Mark it on the pain map"). Tapped, it starts a thread routed from it
     * (`FOLLOW:<key>`), and claims the queued mention of the same key, so one chip, never two.
     */
    fun followUps(state: ChatState): List<TopicChoice> = emptyList()

    /** The chip's words for a [mention] of this topic ("Pain: knee"); null is the topic's own name. */
    fun mentionLabel(mention: TopicMention): ChatText? = null

    /** The topic a thread hands over to before it goes on (a pain on the chest, to chest pain). */
    fun handover(state: ChatState): Symptom? = null

    /** The person's answer to [step] in words for their bubble; null: the options' own labels. */
    fun answerText(state: ChatState, step: SymptomStep): ChatText?

    /** The thread's name in the history, and a symptom row's title. */
    fun title(resources: Resources, thread: ChatThread): String

    /** Whether [thread] already answers [mention] (a pain thread that marked the knee answers "PAIN:KNEE"). */
    fun serves(thread: ChatThread, mention: TopicMention): Boolean

    /** The sorting of the thread its hidden tag is made from ([classifyThread]). */
    fun tagFacts(thread: ChatThread): TagFacts = TagFacts()

    /**
     * The router's first reply when this topic's words were found, before its generic rows: the
     * mood's pair of chips for words of a bad mood. Null leaves the words to the router's own table.
     */
    fun firstReply(mention: WordMention, words: WordsFound, setting: ChatSetting): FirstReply? = null

    /** Said in place of NO_MATCH when words named this topic and it is not offered (a past day's mood). */
    fun notOfferedLine(setting: ChatSetting): ChatText? = null

    /**
     * What is said in place of NO_MATCH for [mention], when its topic is not offered, of the [words]
     * that named it: [notOfferedLine] by default; a script may say more of what the words told (a
     * medicine stopped, in a chat for an earlier day).
     */
    fun notOfferedPieces(mention: WordMention, words: String, setting: ChatSetting): List<BotPiece> =
        listOfNotNull(notOfferedLine(setting)?.let { BotPiece.Note(it) })

    /** Steps a model may never word or skip, besides those the rules are shown at ([SafetyGuard]). */
    val owedSteps: Set<SymptomStep> get() = emptySet()

    /**
     * Steps this topic's script asks wherever the person's words bring them — the medicine's "more
     * than the packet says?" in a thread of any topic, or of none: their question, options and reply
     * are this script's ([stepScript]).
     */
    val globalSteps: Set<SymptomStep> get() = emptySet()

    /** The centre flash for [link] at [event]; null is the core's ("Pain · added", "Saved", "Deleted"). */
    fun flashText(resources: Resources, link: FiledLink, event: FlashEvent): String? = null

    /**
     * Whether [link]'s record of [thread] is said with the centre flash at all in [log] — filed for the
     * first time, rewritten after a rewind, undone or saved again on its card: not one after a line
     * that says to get advice now anywhere in the chat ([nowLineInChat]: a dose taken after "more than
     * it says?", a pain or a mood whose thread was told of too much), where "added" would read as "all
     * is well".
     */
    fun flashes(log: ChatLog, thread: ChatThread, link: FiledLink): Boolean = !nowLineInChat(log)

    /**
     * The card's state line for [link] of [thread] when it is not filed, in words the card renders
     * with their times; null is the core's ("Not saved", "Deleted"…).
     */
    fun stateLine(thread: ChatThread, link: FiledLink): ChatText? = null

    /** An alert's words with arguments ([BotPiece.Alert.text]); null is the alert's own words. */
    fun alertText(state: ChatState, alert: SymptomAlert): ChatText? = null

    /** Steps whose person bubble the topic's own UI file draws (the mood's face, a medicine's tile). */
    val bubbleSteps: Set<SymptomStep> get() = emptySet()

    fun offered(setting: ChatSetting): Boolean

    /** Whether the topic leaves the menu once a thread of it ran ([Symptom.asksOncePerChat]). */
    val oncePerChat: Boolean

    /** When a record's flash says it was saved: at the write ([FlashAt.FILE]) or at the close. */
    val flashAt: FlashAt
}

internal enum class FlashAt { FILE, CLOSE }

/** When a record's centre flash comes: filed, rewritten after a rewind, undone, saved again. */
internal enum class FlashEvent { FILED, REWRITTEN, UNDONE, SAVED_AGAIN }

/** The router's first reply to words that named a topic, before its generic rows. */
internal data class FirstReply(val lead: List<BotPiece>, val reply: ChatReply)

/**
 * What a script tells the hidden tag about a thread, in its own terms — never a tag type, which only
 * the tag's own files may name ([classifyThread] maps these). [families] are body families by name
 * ("KNEE", "CHEST"), [alsoTopics] the topics a medicine was taken or prescribed for.
 */
internal data class TagFacts(
    val score: Int? = null,
    val families: Set<String> = emptySet(),
    val alsoTopics: Set<Symptom> = emptySet(),
    val course: SymptomOption? = null,
    val recurring: Boolean = false,
)

/** What a medicine is, as far as the chat's lines need to know ([medicineClassOf]). */
internal enum class MedicineClass { PAINKILLER, ANTIDEPRESSANT, OTHER }

/** The filer's own role: the row a crisis or an emergency answer outside a symptom thread is kept in. */
internal const val WORDS_ROLE = "words"

internal val Symptom.script: TopicScript
    get() = when (record) {
        ChatRecord.SYMPTOM -> SymptomScript
        ChatRecord.PAIN -> PainScript
        ChatRecord.MOOD -> MoodScript
        ChatRecord.DOSE -> DoseScript
        ChatRecord.COURSE -> CourseScript
    }

/**
 * Whether a second thread of this topic is never offered in one chat. One script serves every
 * symptom topic, and chest pain is the one of them that may run twice: a chest marked on the map is
 * asked about again in a later sitting.
 */
internal val Symptom.asksOncePerChat: Boolean
    get() = script.oncePerChat && this != Symptom.CHEST_PAIN

/** The default [TopicScript.recordSig]: the thread's saved answers, values, facts and painting, by the codec. */
internal fun answersSig(thread: ChatThread): Int =
    encodeSymptomPayload(thread.report.forSave().copy(symptomTag = null, context = null, chat = null)).hashCode()
