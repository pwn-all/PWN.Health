package com.pwnall.health

import android.content.res.Resources
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// A symptom or a worry, told as a chat: "What is bothering you?", then one question at a time, each
// answer a tap — or several, taken three seconds after the last — with the safety lines where the
// answers call for them and a closing turn that says what to do next. Everything here is pure: what
// the chat says is worked out from what it was told, so a chat saved by this build is drawn again,
// word for word, in whatever language is showing when it is opened.
//
// The bot is not a doctor and does not look like one: it asks, thanks, and names what to do — call
// emergency services, get advice today, see a doctor, look after yourself — and where that comes
// from, behind the "?". It never says what something is or what caused it.
//
// The questions are scripted ([ScriptedSymptomResponder]); [SymptomResponder] is the seam a model
// could word them through. Whatever answers there, [SafetyGuard] keeps every red flag, the
// question about suicide and the emergency stop in the script's hands.

/**
 * One thing said in a chat, as the payload keeps it. The bot's own words are never stored: they are
 * made again from the steps.
 */
sealed interface ChatTurn {
    /**
     * [step] was asked and answered; the answer is in [SymptomReport.answers] (or its symptom or
     * severity). [alert] is the line that answer called for, as it was shown, and [rule] the id of the
     * rule behind it, for its source. [routed]: the chat answered it itself — a topic it took from the
     * person's words or a hand-over, an answer copied in from earlier in the sitting — so it draws no
     * bubble of the person's and has no pencil. [read]: for words typed as the answer where the
     * medicine's questions read typed words for too much of a medicine — a name, who prescribed it
     * ([TYPED_READ_STEPS]) — what the chat took from its model's reading of them, whose flag of too
     * much they read beside the words ([ChatRead]).
     */
    data class Asked(
        val step: SymptomStep,
        val alert: SymptomAlert? = null,
        val rule: String? = null,
        val routed: Boolean = false,
        val read: ChatRead? = null,
    ) : ChatTurn

    /**
     * The person's own words, as they wrote them, at most [SYMPTOM_TEXT_MAX] characters, and [read]:
     * what the chat took from its model's reading of them when they were sent ([ChatRead]) — none for
     * a message the model did not read, which the rules alone read.
     */
    data class Wrote(val text: String, val read: ChatRead? = null) : ChatTurn

    /** Words a model said, as it said them, in [language] (BCP 47). The scripted chat never writes one. */
    data class Said(val text: String, val language: String) : ChatTurn

    /**
     * A tap on "physically", "emotionally" or "both" under the question whether feeling unwell is more
     * physical or emotional ([ChatNarrow]): it narrows the topics the router offers next, and answers
     * nothing and files nothing.
     */
    data class Narrowed(val narrow: ChatNarrow) : ChatTurn
}

/** The chat part of an entry. [sameAs] is the moment of the earlier chat this one continues. */
data class SymptomChat(
    val turns: List<ChatTurn> = emptyList(),
    val sameAs: Long? = null,
    val ended: Boolean = false,
) {
    fun askedSteps(): Set<SymptomStep> = turns.filterIsInstance<ChatTurn.Asked>().map { it.step }.toSet()

    /** Everything the person wrote, one message after another. */
    fun words(): String = turns.filterIsInstance<ChatTurn.Wrote>().joinToString("\n") { it.text }
}

/** An earlier chat about the same topic in the last [EARLIER_CHAT_DAYS] days, offered as "the same, or new?". */
data class EarlierChat(val topic: Symptom, val day: LocalDate, val occurredAt: Long, val onset: SymptomOption?)

/**
 * Everything a responder may read: the report so far as it was told ([asTold]), whether the profile
 * is [female] (pregnancy is offered among the causes of sickness), the [earlier] chat on the same
 * topic, whether it is filed for a [pastDay], and whether the profile is an [adult] — every page the
 * questions come from is written for adults. [thread] is where this conversation stands in a chat of
 * several ([ThreadContext]).
 */
data class ChatState(
    val report: SymptomReport,
    val female: Boolean,
    val earlier: EarlierChat?,
    val pastDay: Boolean = false,
    val adult: Boolean = true,
    val thread: ThreadContext = ThreadContext(),
) {
    val chat: SymptomChat get() = report.chat ?: SymptomChat()
}

/** What the bot says next: [pieces] in order, then what it waits for. */
data class BotTurn(val pieces: List<BotPiece>, val reply: ChatReply)

sealed interface BotPiece {
    /**
     * A fixed sentence. [sourceRes] is where it comes from when the sentence is itself the advice —
     * a close set by its topic's floor ([floorSetsUrgency]) rather than by a red flag's line — shown
     * behind a "?" beside it.
     */
    data class Line(val line: ChatLine, val sourceRes: Int? = null) : BotPiece

    /**
     * The question, in its topic's wording ([chatQuestionRes]); [day] is the earlier chat's, for SAME.
     * [text] is a question that needs arguments ([TopicScript.questionText]), and [help] what the "?"
     * beside it explains — an explanation is never printed as a paragraph.
     */
    data class Ask(
        val step: SymptomStep,
        val day: LocalDate? = null,
        val text: ChatText? = null,
        val help: ChatText? = null,
    ) : BotPiece

    /**
     * A safety line an answer called for, with where it comes from. [text] is its words when they
     * need an argument ([TopicScript.alertText]) — drawn in place of the alert's own, in the same card.
     */
    data class Alert(val alert: SymptomAlert, val sourceRes: Int, val text: ChatText? = null) : BotPiece

    /** The card of what the journal held. */
    data object Facts : BotPiece

    /** Something the chat offers to do next; [thread] is the id of the thread whose close it stands in. */
    data class Offer(val offer: ChatOffer, val thread: Int = -1) : BotPiece

    /** A model's words, shown as they were said. */
    data class Said(val text: String) : BotPiece

    /**
     * The card of a record [thread] (the thread's id, never its index) filed under [role]: one line
     * of what is saved as it is now, and while the chat is live its Open and Undo.
     */
    data class Saved(val thread: Int, val role: String = "") : BotPiece

    /** "Everything you note here will be saved under 3 October." */
    data class ForDay(val day: LocalDate) : BotPiece

    /** A topic's own sentence: a bubble, or a safety card when it has a [sourceRes] or is [safety]. */
    data class Note(val text: ChatText, val sourceRes: Int? = null, val safety: Boolean = false) : BotPiece

    /**
     * A record the message at [message] of the thread [thread] (its id; the message by its ordinal)
     * could become, as a reading of it proposed: a card of the bot's answer to it, whose Add opens the
     * record's own form filled in. Nothing is written from the chat.
     */
    data class Journal(val event: ChatJournalEvent, val thread: Int, val message: Int) : BotPiece {
        internal val key: JournalKey get() = JournalKey(message, event.type, event.anchor.start, event.anchor.end)
    }
}

/**
 * What the bot's turn waits for. Every reply is a data class of values only — no lambda, no
 * resources — so two turns that say the same are equal, and [SafetyGuard] can hold a model's turn to
 * the script's. The core draws [Topics], [Choose], [Severity], [Values], [TextAsk] (the composer) and
 * [End]; the pain thread's file draws [AreaChips], [BodyMap], [PainScale] and [PainLasted]; the mood's
 * [MoodDial]; the medicine's [MedicinePick], [TimesPick] and [ReminderGates]. [exit] on a reply is
 * "Another topic", the way out of a start the chat chose from the person's words: the core draws it.
 */
sealed interface ChatReply {
    /**
     * Topic chips. [all]: the whole menu, grouped; otherwise [choices] alone, with "Show all topics"
     * to unfold the menu in place. [end] adds "That is all for now"; [resume] is the key of another
     * chat begun today and left before its end, whose "Carry on the last chat" stands first. [narrow]
     * are the chips that narrow the menu after words of feeling unwell — "physically", "emotionally",
     * "both" — before the topics.
     */
    data class Topics(
        val choices: List<TopicChoice>,
        val all: Boolean = true,
        val end: Boolean = false,
        val resume: String? = null,
        val exit: Boolean = false,
        val narrow: List<ChatNarrow> = emptyList(),
    ) : ChatReply

    /**
     * The answers to [step]; several may be ticked when [SymptomStep.multi]. [labels] overrides an
     * option's own label; [marked] are the chips the person's words point at, [chatMarked] those an
     * earlier answer of this chat points at ([TopicScript.markPairs]) — never ticked, never armed;
     * [preset], an answer given before, stands ticked and is taken only once touched, or at once by
     * [keep]'s chip; [proposed], what the person's message told, is never
     * ticked — its chips stand marked as the words' are, a proposal and no answer — and is taken only
     * by [keep]'s chip, "From your message: …"; [limit] caps the ticks; [openRef] adds "Open that entry".
     */
    data class Choose(
        val step: SymptomStep,
        val options: List<SymptomOption>,
        val labels: Map<SymptomOption, Int> = emptyMap(),
        val marked: Set<SymptomOption> = emptySet(),
        val preset: List<SymptomOption> = emptyList(),
        val keep: ChatText? = null,
        val limit: Int = 0,
        val openRef: ChatRecordRef? = null,
        val exit: Boolean = false,
        val chatMarked: Set<SymptomOption> = emptySet(),
        val proposed: List<SymptomOption> = emptyList(),
    ) : ChatReply

    data object Severity : ChatReply

    /** The chat is over, or this is a turn of the past: nothing more to answer. */
    data object End : ChatReply

    /** The areas the person's words named, one chip each; a tap is the answer ([AreaChoice]). */
    data class AreaChips(
        val step: SymptomStep,
        val choices: List<AreaChoice>,
        val frame: PainFrame?,
        val exit: Boolean = false,
    ) : ChatReply

    /** The body map, framed where the words pointed; [head] the head chips, the canvas folded. */
    data class BodyMap(
        val step: SymptomStep,
        val frame: PainFrame?,
        val head: Boolean,
        val headChips: List<RegionSide>,
        val exit: Boolean = false,
    ) : ChatReply

    /** The pain page's 1–10 ruler: [start] the earlier rating shown, [rest] where the person's own number rests it. */
    data class PainScale(val step: SymptomStep, val start: Int?, val rest: Int?) : ChatReply

    /** The pain page's "how long did it last" ruler, starting at [start] minutes. */
    data class PainLasted(val step: SymptomStep, val start: Int) : ChatReply

    /** The mood page's dial, resting on [start] (none: the middle face). */
    data class MoodDial(val step: SymptomStep, val start: Int?, val exit: Boolean = false) : ChatReply

    /** The person's own medicines to pick from, the words' proposals beside them, and "All my medicines". */
    data class MedicinePick(
        val step: SymptomStep,
        val choices: List<MedicineChoice>,
        val proposals: List<ValueChip>,
        val more: Boolean,
        val exit: Boolean = false,
    ) : ChatReply

    /**
     * Chips whose answer is a value, not an option: one is taken on the tap, or with [multi] several
     * ticked are taken by the clock ([exclusive] values stand alone). Joined by "," in the value.
     */
    data class Values(
        val step: SymptomStep,
        val choices: List<ValueChip>,
        val multi: Boolean = false,
        val exclusive: Set<String> = emptySet(),
        val exit: Boolean = false,
    ) : ChatReply

    /** The composer as the answer field: [hintRes] in it, at most [max] characters, [suggestions] above it. */
    data class TextAsk(
        val step: SymptomStep,
        val hintRes: Int,
        val max: Int,
        val suggestions: List<ValueChip>,
        val keyboard: TextAskKeyboard = TextAskKeyboard.TEXT,
        val exit: Boolean = false,
    ) : ChatReply

    /** Clock times to tick, [proposals] in minutes of the day, and "Add a time". */
    data class TimesPick(val step: SymptomStep, val proposals: List<Int>) : ChatReply

    /** One of the reminders' switches a new course's alarms need ([ReminderGate]). */
    data class ReminderGates(val step: SymptomStep, val gate: ReminderGate) : ChatReply
}

/** An area chip: the areas a tap answers with; [both] is the "both sides" chip of a paired area. */
data class AreaChoice(val areas: List<RegionSide>, val both: Boolean = false)

/** Where the body map opens: the view, and the body family it is framed on (none: the whole body). */
data class PainFrame(val view: PainView, val family: String?)

/** One of the person's courses as a chip: named by its [createdAt], never an id; a planned [slot] when one is open. */
data class MedicineChoice(
    val createdAt: Long,
    val name: String,
    val dose: String,
    val photo: String,
    val slot: String? = null,
    val slotLabel: ChatText? = null,
)

/** A chip whose answer is [value]; [picker] opens the app's own time or day picker for it. */
data class ValueChip(
    val value: String,
    val label: ChatText,
    val accent: Boolean = false,
    val picker: ValuePicker? = null,
)

enum class ValuePicker { CLOCK, DAY }

enum class TextAskKeyboard { TEXT, NUMBER }

/** The switches a course's reminders depend on, asked one at a time. */
enum class ReminderGate { SWITCH, NOTIFY, ALARM }

/**
 * A record a card or a chip opens, resolved where it is tapped: a journal row by [kind] and moment
 * [at] ([id] when it is known), a course by its `createdAt` (kind [LINK_COURSE]).
 */
data class ChatRecordRef(val kind: String, val at: Long, val id: Long?)

/**
 * What a chat can offer to do next. [BOOK_VISIT] adds a visit to the medicine tab's plans — it books
 * nothing, and says so in its words; [BREATHING] opens the breathing page. A topic to go on with is
 * the router's chip, never an offer; another chat begins once this one is over.
 */
enum class ChatOffer { BOOK_VISIT, BREATHING }

/**
 * What a tap under the question whether feeling unwell is more physical or emotional narrows the menu
 * to ([ChatTurn.Narrowed]): the body's topics, the feelings', or both (ChatUnwell.kt).
 */
enum class ChatNarrow(val labelRes: Int) {
    BODY(R.string.symptom_chat_unwell_body),
    MIND(R.string.symptom_chat_unwell_mind),
    BOTH(R.string.symptom_chat_unwell_both),
}

/**
 * The bot's fixed sentences. [safety] marks the ones drawn as a safety line rather than a bubble —
 * what to do when it may be an emergency — with [sourceRes] behind its "?".
 */
enum class ChatLine(val textRes: Int, val safety: Boolean = false, val sourceRes: Int? = null) {
    OPEN(R.string.symptom_chat_open),
    ADULTS(R.string.symptom_chat_adults),
    MATCH(R.string.symptom_chat_match),
    NO_MATCH(R.string.symptom_chat_no_match),
    START_BODY(R.string.symptom_chat_start_body),
    START_MIND(R.string.symptom_chat_start_mind),
    START_OTHER(R.string.symptom_chat_start_other),
    OWN_WORDS(R.string.symptom_chat_own_words),
    ACK_1(R.string.symptom_chat_ack_1),
    ACK_2(R.string.symptom_chat_ack_2),
    ACK_3(R.string.symptom_chat_ack_3),
    ACK_MIND(R.string.symptom_chat_ack_mind),
    HEAVY(R.string.symptom_chat_heavy),

    /** The first message during a question: the words are kept in the chat, and not answered. */
    REMARK(R.string.symptom_chat_remark_kept),
    SAFETY(R.string.symptom_chat_safety, safety = true, sourceRes = R.string.symptom_source_call_999),
    WORDS_CHECK(R.string.symptom_chat_words_check),
    CRISIS_SUPPORT(R.string.symptom_chat_crisis_support, safety = true, sourceRes = R.string.symptom_source_crisis),
    CRISIS_LEAD(R.string.symptom_chat_crisis_lead),
    FACTS_NOW(R.string.symptom_chat_facts_now),
    FACTS_DAY(R.string.symptom_chat_facts_day),
    CLOSE_EMERGENCY(R.string.symptom_chat_close_emergency),
    CLOSE_EMERGENCY_MIND(R.string.symptom_chat_close_emergency_mind),
    CLOSE_TODAY(R.string.symptom_chat_close_today),
    CLOSE_CRISIS(R.string.symptom_chat_close_crisis),
    CLOSE_GP(R.string.symptom_chat_close_gp),
    CLOSE_BODY(R.string.symptom_chat_close_body),
    CLOSE_MIND(R.string.symptom_chat_close_mind),
    THANKS(R.string.symptom_chat_thanks),
    LEFT_EARLY(R.string.symptom_chat_left_early),

    // The router's lines, between threads and around them, and the pain thread's own.
    OPENING(R.string.symptom_chat_ask_opening),
    ASK_TOPIC_AGAIN(R.string.symptom_chat_ask_topic_again),
    MATCH_SEVERAL(R.string.symptom_chat_match_several),
    ALSO_MENTIONED(R.string.symptom_chat_also_mentioned),
    ANYTHING_ELSE(R.string.symptom_chat_anything_else),
    CRISIS_TALK(R.string.symptom_chat_crisis_talk),
    CHEST_FIRST(R.string.symptom_chat_chest_first, safety = false, sourceRes = R.string.symptom_source_chest_pain),
    CHEST_WORDS(R.string.symptom_chat_chest_words, sourceRes = R.string.symptom_source_chest_pain),
    CHAT_GONE(R.string.symptom_chat_gone),
    REMARK_SHORT(R.string.symptom_chat_remark_short),
    NO_MATCH_AGAIN(R.string.symptom_chat_no_match_again),
    QUESTION(R.string.symptom_chat_question, sourceRes = R.string.symptom_source_call_999),
    WELCOME(R.string.symptom_chat_welcome),
    HOW_ARE_YOU(R.string.symptom_chat_how_are_you),
    CHAT_HELLO(R.string.symptom_chat_hello),
    CHAT_CAPABILITY(R.string.symptom_chat_capability),
    JOKE_ONE(R.string.symptom_chat_joke_one),
    JOKE_TWO(R.string.symptom_chat_joke_two),
    SAD_COMPANY(R.string.symptom_chat_sad_company),
    SAD_REASON(R.string.symptom_chat_sad_reason),
    REASON_HEARD(R.string.symptom_chat_reason_heard),
    EVERYDAY_HEARD(R.string.symptom_chat_everyday_heard),
    SORRY(R.string.symptom_chat_sorry),
    SOMEONE_ELSE(R.string.symptom_chat_someone_else, safety = true, sourceRes = R.string.symptom_source_call_999),
    PAIN_SAFETY(R.string.pain_safety, safety = true, sourceRes = R.string.pain_source_safety),
    QUEUED(R.string.symptom_chat_queued),

    /** The close after a line that says to get advice now but does not stop the chat. */
    CLOSE_NOW(R.string.symptom_chat_close_emergency),
    PAIN_START(R.string.pain_chat_start),
    PAIN_RESUME(R.string.pain_chat_chest_resume),

    // Words of feeling unwell, and nothing of what.

    /** "I am sorry you are not feeling well. Is it more physical or emotional?" */
    UNWELL_ASK(R.string.symptom_chat_unwell_ask),

    /** Over the menu one of its chips narrowed: no thanks, as a tap on it answers nothing. */
    UNWELL_PICK(R.string.symptom_chat_unwell_pick),

    /** [START_BODY] without its sympathy, after the unwell question already said it. */
    START_QUESTIONS(R.string.symptom_chat_start_questions),

    // The chat's model: the two lines that say the chat looks for a few words only,
    // in the words true of a message a model read in a language its exam was passed in ([ChatRead.examined]).

    /** [NO_MATCH] of such a message: the AI model on this phone found none of the topics, and can be wrong. */
    NO_MATCH_MODEL(R.string.symptom_chat_no_match_model),

    /** [REMARK] of such a message: the chat does not answer it, the model only chooses among its questions. */
    REMARK_MODEL(R.string.symptom_chat_remark_model),
}

/** One bubble of a transcript. */
sealed interface ChatItem {
    /** The bot's turn; [thread] is the id of the thread it belongs to, −1 for the router's own. */
    data class Bot(val turn: BotTurn, val thread: Int = -1) : ChatItem

    /**
     * The person's side: an answer to [step] (its words resolved where it is drawn) or [text];
     * [position] is its turn in [thread] (the thread's id). [end] is "That is all for now", [narrowed]
     * a narrowing chip tapped ("Physically").
     */
    data class Person(
        val position: Int,
        val step: SymptomStep?,
        val text: String?,
        val thread: Int = 0,
        val end: Boolean = false,
        val narrowed: ChatNarrow? = null,
    ) : ChatItem
}

/**
 * The responder seam: state in, the next turn out. Suspends, so a model can take its time; the
 * script answers at once.
 */
interface SymptomResponder {
    suspend fun next(state: ChatState): BotTurn
}

/** The script: the same turn for the same state, every time ([turnBefore]). */
object ScriptedSymptomResponder : SymptomResponder {
    override suspend fun next(state: ChatState): BotTurn = turnBefore(state, nextChatStep(state))
}

/**
 * What every responder the chat uses is wrapped in. With the script inside it changes nothing; with
 * a model inside it takes the model's turn only where that turn waits for exactly the script's reply
 * and opens with every safety line of the script's, and never on a question a red flag reads, the
 * question about suicide, or the closing turn — and only with words [acceptable] passes, which is
 * none: a filter for diagnoses ("you have…", «у вас…») and for the hidden tag's words belongs in the
 * model's own package, and there is none. The turn's other pieces — the question's own bubble, a line, a note,
 * an offer — are the script's, each one and in its order ([acceptsProposal]): a model adds words to
 * the script's turn, and takes nothing from it. The tag never decides which help is shown; the rules do.
 */
class SafetyGuard(
    private val inner: SymptomResponder,
    /** Whether a model's words may be shown. None may until there is a filter for diagnoses. */
    private val acceptable: (String) -> Boolean = { false },
) : SymptomResponder {
    override suspend fun next(state: ChatState): BotTurn {
        val scripted = ScriptedSymptomResponder.next(state)
        val proposed = inner.next(state)
        return if (proposed == scripted ||
            acceptsProposal(state, scripted, proposed, acceptable)
        ) {
            proposed
        } else {
            scripted
        }
    }
}

/**
 * The questions a red flag reads, or that keep a person safe: never a model's to word, skip or prefill
 * — every step a rule is shown at or reads ([AlertRule.reads]: its closure,
 * with `WORRY_MOST` reading "how often" as well as its own question), the emergencies' and the
 * crisis questions, and each topic's ([TopicScript.owedSteps]); of every topic at once.
 */
internal val OWED_STEPS: Set<SymptomStep> by lazy { Symptom.entries.flatMapTo(HashSet(), ::owedSteps) }

/**
 * [OWED_STEPS] in a thread of [topic]: the steps its own rules and the rules of every topic are shown
 * at or read, the emergencies' and the crisis questions, and its script's own.
 */
internal fun owedSteps(topic: Symptom): Set<SymptomStep> = OWED_BY_TOPIC.getValue(topic)

private val OWED_BY_TOPIC: Map<Symptom, Set<SymptomStep>> by lazy {
    Symptom.entries.associateWith { topic ->
        val rules = SYMPTOM_RULES.filter { it.topics.isEmpty() || topic in it.topics }
        rules.flatMapTo(HashSet()) { it.reads + it.shownAt } + EmergencyWords.entries.map { it.step } +
            setOf(SymptomStep.CRISIS, SymptomStep.CRISIS_NOW, SymptomStep.WITH) + topic.script.owedSteps
    }
}

/**
 * The ordinary steps the next-step decider may prefill from what a message told,
 * as a proposal taken only with a tap: a pain's start, kinds, moment and "the same as
 * before?", a dose's time and amount, a course's dose and times — of these, only the ones no rule of
 * the thread's topic reads ([prefillable]); a step a rule reads is asked as the script asks it, with
 * the words' marks at most.
 */
internal val PREFILL_STEPS: Set<SymptomStep> = setOf(
    SymptomStep.ONSET,
    SymptomStep.PAIN_TYPES,
    SymptomStep.PAIN_NOTICED,
    SymptomStep.PAIN_SAME,
    SymptomStep.MED_WHEN,
    SymptomStep.MED_AMOUNT,
    SymptomStep.COURSE_DOSE,
    SymptomStep.COURSE_TIMES,
)

/** Whether [step] of a thread of [topic] may be prefilled ([PREFILL_STEPS]): never one it owes ([owedSteps]). */
internal fun prefillable(topic: Symptom?, step: SymptomStep): Boolean =
    topic != null && step in PREFILL_STEPS && step !in owedSteps(topic)

/**
 * [SafetyGuard]'s rule, apart so it can be tested with a model that is not there. Without the model's
 * own words ([BotPiece.Said]) the turn must be the script's piece for piece: a prefix of safety lines
 * alone would let a turn through with its question gone, or with a closing line of the model's choosing.
 */
internal fun acceptsProposal(
    state: ChatState,
    scripted: BotTurn,
    proposed: BotTurn,
    acceptable: (String) -> Boolean,
): Boolean {
    val step = nextChatStep(state)
    val safety = scripted.pieces.filter { it.isSafety() }
    return step != null && step !in OWED_STEPS && proposed.reply == scripted.reply &&
        proposed.pieces.take(safety.size) == safety &&
        proposed.pieces.filter { it !is BotPiece.Said } == scripted.pieces &&
        proposed.pieces.filterIsInstance<BotPiece.Said>().all { acceptable(it.text) }
}

/** A safety line: an alert, a fixed safety sentence, or a topic's own sentence that is one. */
internal fun BotPiece.isSafety(): Boolean =
    this is BotPiece.Alert || (this is BotPiece.Line && line.safety) ||
        (this is BotPiece.Note && (safety || sourceRes != null))

/** The responder the chat uses. */
internal val symptomResponder: SymptomResponder = SafetyGuard(ScriptedSymptomResponder)

/** The longest message the chat keeps. */
internal const val SYMPTOM_TEXT_MAX = 500

/** How far back an earlier chat on the same topic is offered as "the same, or new?". */
internal const val EARLIER_CHAT_DAYS = 7L

/** The longest title an entry takes from the person's words for "something else". */
private const val OTHER_TITLE_MAX = 40

/**
 * Every topic in the menu's order: what to note down, the body, feelings, then something else
 * ([MENU_GROUPS]). The chips the words propose stand in it too.
 */
internal val TOPIC_ORDER: List<Symptom> by lazy { MENU_GROUPS.flatMap { it.second } }

private val YES_FIRST = listOf(SymptomOption.YES, SymptomOption.NO)

/** The answers a question offers wherever its topic does not say otherwise. */
private val STEP_OPTIONS: Map<SymptomStep, List<SymptomOption>> = mapOf(
    SymptomStep.ONSET to listOf(
        SymptomOption.STARTED_NOW,
        SymptomOption.STARTED_TODAY,
        SymptomOption.STARTED_YESTERDAY,
        SymptomOption.STARTED_2_3_DAYS,
        SymptomOption.STARTED_A_WEEK,
        SymptomOption.STARTED_A_MONTH,
        SymptomOption.STARTED_LONGER,
    ),
    SymptomStep.SAME to listOf(SymptomOption.SAME_AS_BEFORE, SymptomOption.SOMETHING_NEW),
    SymptomStep.PATTERN to listOf(SymptomOption.ALL_THE_TIME, SymptomOption.COMES_AND_GOES, SymptomOption.JUST_ONCE),
    SymptomStep.HOW_OFTEN to listOf(
        SymptomOption.SEVERAL_A_DAY,
        SymptomOption.DAILY,
        SymptomOption.FEW_A_WEEK,
        SymptomOption.LESS_OFTEN,
    ),
    SymptomStep.DURATION to listOf(
        SymptomOption.SECONDS,
        SymptomOption.FEW_MINUTES,
        SymptomOption.LONGER_MINUTES,
        SymptomOption.HOURS,
    ),
    SymptomStep.TREND to listOf(SymptomOption.GETTING_BETTER, SymptomOption.NO_CHANGE, SymptomOption.GETTING_WORSE),
    SymptomStep.CHEST_NOW to YES_FIRST,
    SymptomStep.CHEST_SPREAD to YES_FIRST,
    SymptomStep.PALP_NOW to YES_FIRST,
    SymptomStep.BREATH_NOW to YES_FIRST,
    SymptomStep.STROKE_SIGNS to YES_FIRST,
    SymptomStep.UNRESPONSIVE to YES_FIRST,
    SymptomStep.BLOOD to listOf(SymptomOption.NO, SymptomOption.YES),
    SymptomStep.ABOUT to listOf(
        SymptomOption.WORK_STUDY,
        SymptomOption.MONEY,
        SymptomOption.FAMILY,
        SymptomOption.HEALTH,
        SymptomOption.LONELINESS,
        SymptomOption.LOSS,
        SymptomOption.FUTURE,
        SymptomOption.NO_CLEAR_REASON,
        SymptomOption.SOMETHING_ELSE,
    ),
    SymptomStep.HOW_LONG to listOf(
        SymptomOption.FEW_DAYS,
        SymptomOption.ABOUT_A_WEEK,
        SymptomOption.TWO_WEEKS_PLUS,
        SymptomOption.MONTHS,
    ),
    SymptomStep.MIND_OFTEN to listOf(SymptomOption.NOW_AND_THEN, SymptomOption.MOST_DAYS, SymptomOption.ALL_THE_TIME),
    SymptomStep.AFFECTS to listOf(
        SymptomOption.SLEEP,
        SymptomOption.WORK_STUDY,
        SymptomOption.EATING,
        SymptomOption.DAILY_TASKS,
        SymptomOption.PEOPLE,
        SymptomOption.NOT_REALLY,
    ),
    SymptomStep.COPING to listOf(
        SymptomOption.MANAGING,
        SymptomOption.STRUGGLING,
        SymptomOption.NOT_COPING,
        SymptomOption.TRIED_NOT_HELPING,
    ),
    SymptomStep.NIGHT to listOf(
        SymptomOption.HARD_TO_FALL_ASLEEP,
        SymptomOption.WAKING_IN_NIGHT,
        SymptomOption.WAKING_EARLY,
        SymptomOption.RACING_THOUGHTS,
        SymptomOption.TIRED_IN_DAY,
    ),
    SymptomStep.CRISIS to listOf(
        SymptomOption.NO,
        SymptomOption.CRISIS_SAFE,
        SymptomOption.CRISIS_UNSAFE,
        SymptomOption.CRISIS_HARMED,
        SymptomOption.RATHER_NOT_SAY,
    ),
    SymptomStep.CRISIS_NOW to listOf(
        SymptomOption.NO_SUCH_THOUGHTS,
        SymptomOption.SAFE_NOW,
        SymptomOption.CRISIS_UNSAFE,
        SymptomOption.CRISIS_HARMED,
        SymptomOption.RATHER_NOT_SAY,
    ),
)

/** A question in a topic's chat; no [options] takes the symptom's own ([symptomAsks]), else [STEP_OPTIONS]. */
private class ChatAsk(val step: SymptomStep, val options: List<SymptomOption>? = null)

private fun tickedWith(vararg options: SymptomOption) = ChatAsk(SymptomStep.WITH, options.toList() + SymptomOption.NONE)

private fun asks(vararg steps: SymptomStep) = steps.map { ChatAsk(it) }

/**
 * Each topic's questions between "which" (and "the same as before?") and "how bad", in the order
 * the chat asks them. The body's questions open with what its NHS page tells to call 999 for — the
 * page's own first list — because a chat left after two questions has still asked those; the
 * follow-ups ([CHAT_CONDITIONS]) come only when an answer calls for them.
 */
private val CHAT_ASKS: Map<Symptom, List<ChatAsk>> = mapOf(
    Symptom.VOMITING to asks(SymptomStep.BLOOD) + tickedWith(
        SymptomOption.CANT_KEEP_FLUIDS,
        SymptomOption.GREEN_VOMIT,
        SymptomOption.SEVERE_TUMMY_PAIN,
        SymptomOption.SUDDEN_HEADACHE,
        SymptomOption.CONFUSED,
        SymptomOption.STIFF_NECK,
        SymptomOption.BLUE_SKIN,
        SymptomOption.SEVERE_BREATHING,
    ) + asks(SymptomStep.ONSET, SymptomStep.AFTER_MEAL, SymptomStep.CAUSE, SymptomStep.TIMES),
    Symptom.NAUSEA to
        listOf(tickedWith(SymptomOption.CHEST_PAIN, SymptomOption.PAIN_SPREADING, SymptomOption.BREATHLESS)) +
        asks(SymptomStep.ONSET, SymptomStep.PATTERN, SymptomStep.HOW_OFTEN, SymptomStep.AFTER_MEAL, SymptomStep.CAUSE),
    Symptom.DIARRHOEA to listOf(
        tickedWith(
            SymptomOption.BLOOD,
            SymptomOption.BLACK_STOOLS,
            SymptomOption.DEHYDRATION,
            SymptomOption.SEVERE_TUMMY_PAIN,
            SymptomOption.CONFUSED,
            SymptomOption.STIFF_NECK,
            SymptomOption.BLUE_SKIN,
            SymptomOption.SEVERE_BREATHING,
            SymptomOption.FEVER,
        ),
    ) + asks(SymptomStep.ONSET, SymptomStep.TIMES, SymptomStep.CAUSE),
    Symptom.HEARTBURN to listOf(
        tickedWith(
            SymptomOption.PAIN_SPREADING,
            SymptomOption.SWEATY_SICK,
            SymptomOption.BLACK_STOOLS,
            SymptomOption.SWALLOWING,
            SymptomOption.VOMITING,
            SymptomOption.WEIGHT_LOSS,
        ),
    ) + asks(SymptomStep.ONSET, SymptomStep.PATTERN, SymptomStep.HOW_OFTEN, SymptomStep.WHEN),
    Symptom.DIZZINESS to listOf(
        tickedWith(
            SymptomOption.CHEST_PAIN,
            SymptomOption.RACING_HEART,
            SymptomOption.ONE_SIDE_WEAK,
            SymptomOption.SPEECH,
        ),
    ) + asks(
        SymptomStep.ONSET,
        SymptomStep.PATTERN,
        SymptomStep.HOW_OFTEN,
        SymptomStep.DURATION,
        SymptomStep.DOING,
        SymptomStep.FEEL,
        SymptomStep.FAINTED,
    ),
    Symptom.FAINTING to listOf(
        tickedWith(
            SymptomOption.CHEST_PAIN,
            SymptomOption.RACING_HEART,
            SymptomOption.BREATHLESS,
            SymptomOption.DURING_EXERCISE,
        ),
    ) + asks(SymptomStep.INJURED, SymptomStep.ONSET, SymptomStep.DOING),
    Symptom.PALPITATIONS to asks(SymptomStep.PALP_NOW) + tickedWith(
        SymptomOption.CHEST_PAIN,
        SymptomOption.BREATHLESS,
        SymptomOption.DIZZY_FAINT,
        SymptomOption.HEART_CONDITION,
    ) + asks(
        SymptomStep.ONSET,
        SymptomStep.PATTERN,
        SymptomStep.HOW_OFTEN,
        SymptomStep.DURATION,
        SymptomStep.WHEN,
        SymptomStep.FEEL,
    ),
    Symptom.CHEST_PAIN to asks(SymptomStep.CHEST_NOW) +
        tickedWith(SymptomOption.PAIN_SPREADING, SymptomOption.TIGHT_HEAVY, SymptomOption.SWEATY_SICK) +
        asks(SymptomStep.ONSET, SymptomStep.PATTERN, SymptomStep.HOW_OFTEN),
    Symptom.BREATHLESSNESS to listOf(
        tickedWith(
            SymptomOption.CHEST_PAIN,
            SymptomOption.TIGHT_HEAVY,
            SymptomOption.PAIN_SPREADING,
            SymptomOption.SEVERE_BREATHING,
            SymptomOption.BLUE_LIPS,
            SymptomOption.CONFUSED,
            SymptomOption.COUGHING_BLOOD,
            SymptomOption.ONE_LEG_SWELLING,
            SymptomOption.RACING_HEART,
            SymptomOption.WHEEZING,
            SymptomOption.COUGH,
        ),
    ) + asks(SymptomStep.WHEN, SymptomStep.ONSET, SymptomStep.PATTERN),
    Symptom.FEVER to listOf(
        tickedWith(
            SymptomOption.NON_BLANCHING_RASH,
            SymptomOption.STIFF_NECK,
            SymptomOption.CONFUSED,
            SymptomOption.SUDDEN_HEADACHE,
            SymptomOption.SEVERE_BREATHING,
            SymptomOption.BLUE_SKIN,
            SymptomOption.CHILLS,
            SymptomOption.SWEATING,
            SymptomOption.SORE_THROAT,
            SymptomOption.COUGH,
        ),
    ) + asks(SymptomStep.ONSET, SymptomStep.TREND),
    Symptom.TIREDNESS to listOf(
        tickedWith(
            SymptomOption.BREATHLESS,
            SymptomOption.WEIGHT_LOSS,
            SymptomOption.LOW_MOOD,
            SymptomOption.POOR_SLEEP,
        ),
    ) + asks(SymptomStep.ONSET, SymptomStep.PATTERN, SymptomStep.AFFECTS),
    Symptom.OTHER to listOf(
        tickedWith(
            SymptomOption.SEVERE_BREATHING,
            SymptomOption.ONE_SIDE_WEAK,
            SymptomOption.SPEECH,
            SymptomOption.CONFUSED,
            SymptomOption.CHEST_PAIN,
        ),
    ) + asks(SymptomStep.CHEST_NOW, SymptomStep.ONSET, SymptomStep.PATTERN, SymptomStep.HOW_OFTEN, SymptomStep.AFFECTS),
    Symptom.WORRY to asks(
        SymptomStep.ABOUT,
        SymptomStep.HOW_LONG,
        SymptomStep.MIND_OFTEN,
        SymptomStep.AFFECTS,
        SymptomStep.COPING,
    ) + tickedWith(
        SymptomOption.RACING_HEART,
        SymptomOption.BREATHLESS,
        SymptomOption.CHEST_PAIN,
        SymptomOption.DIZZY_FAINT,
        SymptomOption.STOMACH,
        SymptomOption.TENSE,
    ) + asks(SymptomStep.CHEST_NOW, SymptomStep.BREATH_NOW, SymptomStep.PALP_NOW),
    Symptom.LOW_MOOD to asks(SymptomStep.ABOUT, SymptomStep.HOW_LONG, SymptomStep.AFFECTS, SymptomStep.COPING) +
        tickedWith(SymptomOption.POOR_SLEEP, SymptomOption.TIRED, SymptomOption.NO_ENJOYMENT, SymptomOption.HOPELESS),
    Symptom.STRESS to
        asks(SymptomStep.ABOUT, SymptomStep.HOW_LONG, SymptomStep.AFFECTS, SymptomStep.COPING) + tickedWith(
            SymptomOption.HEADACHE,
            SymptomOption.TENSE,
            SymptomOption.STOMACH,
            SymptomOption.CHEST_PAIN,
            SymptomOption.RACING_HEART,
            SymptomOption.BREATHLESS,
        ) + asks(SymptomStep.CHEST_NOW, SymptomStep.BREATH_NOW, SymptomStep.PALP_NOW),
    Symptom.SLEEP_WORRY to asks(
        SymptomStep.NIGHT,
        SymptomStep.HOW_LONG,
        SymptomStep.ABOUT,
        SymptomStep.AFFECTS,
        SymptomStep.COPING,
    ),
)

/**
 * The questions asked only when an earlier answer calls for them. A pain in the chest under any
 * other topic is never left alone: "is it there now?" follows. Breathlessness under a feeling is
 * asked about now, and a racing heart with feeling faint or breathless whether it is happening now —
 * the chat does not put them down to the worry.
 */
private val CHAT_CONDITIONS: Map<SymptomStep, (Symptom, SymptomReport) -> Boolean> = mapOf(
    SymptomStep.ONSET to { _, report -> !report.has(SymptomStep.SAME, SymptomOption.SAME_AS_BEFORE) },
    SymptomStep.HOW_OFTEN to { _, report -> report.has(SymptomStep.PATTERN, SymptomOption.COMES_AND_GOES) },
    SymptomStep.DURATION to { _, report ->
        report.has(SymptomStep.PATTERN, SymptomOption.COMES_AND_GOES, SymptomOption.JUST_ONCE)
    },
    SymptomStep.CHEST_NOW to { topic, report ->
        topic == Symptom.CHEST_PAIN || report.has(SymptomStep.WITH, SymptomOption.CHEST_PAIN)
    },
    SymptomStep.BREATH_NOW to { _, report -> report.has(SymptomStep.WITH, SymptomOption.BREATHLESS) },
    SymptomStep.PALP_NOW to { topic, report ->
        topic == Symptom.PALPITATIONS ||
            (
                report.has(SymptomStep.WITH, SymptomOption.RACING_HEART) &&
                    report.has(SymptomStep.WITH, SymptomOption.DIZZY_FAINT, SymptomOption.BREATHLESS)
                )
    },
)

/**
 * The answers [step] offers under [topic] (none chosen yet: the question's own), for a profile that
 * is [female] or not.
 */
internal fun chatOptions(topic: Symptom?, step: SymptomStep, female: Boolean): List<SymptomOption> {
    val own = topic?.let { CHAT_ASKS[it] }?.firstOrNull { it.step == step }?.options
    val page = topic?.let { symptomAsks(it, female) }?.firstOrNull { it.step == step }?.options
    val options = own ?: page ?: STEP_OPTIONS[step].orEmpty()
    return if (female) options else options - SymptomOption.PREGNANCY
}

/**
 * Whether a feeling has grown heavy enough to ask, gently and directly, about suicide: low mood once
 * it has said how long; struggling or not coping; two weeks or longer; a feeling told as severe;
 * hopelessness or low mood ticked under any topic.
 */
private fun crisisCalledFor(topic: Symptom, report: SymptomReport): Boolean =
    report.has(SymptomStep.COPING, SymptomOption.STRUGGLING, SymptomOption.NOT_COPING) ||
        report.has(SymptomStep.HOW_LONG, SymptomOption.TWO_WEEKS_PLUS, SymptomOption.MONTHS) ||
        (topic.mind && report.severity == SymptomSeverity.SEVERE) ||
        report.has(SymptomStep.WITH, SymptomOption.LOW_MOOD, SymptomOption.HOPELESS) ||
        (topic == Symptom.LOW_MOOD && report.answers[SymptomStep.HOW_LONG].orEmpty().isNotEmpty())

/** [report] with only [steps]' answers, and how bad only if it is among them. */
private fun SymptomReport.only(steps: List<SymptomStep>): SymptomReport = copy(
    answers = answers.filterKeys { it in steps },
    severity = severity.takeIf { SymptomStep.SEVERITY in steps },
)

/**
 * The questions this chat asks, in order, for its topic and the answers so far: which topic; "the
 * same as before?" when there is an [ChatState.earlier] chat; the topic's own; how bad. The question
 * about suicide goes straight after the answer that called for it, not at the end where a chat left
 * early never reaches — unless the person's words already asked whether they are safe now
 * ([crisisAskedNow]).
 */
internal fun chatSteps(state: ChatState): List<SymptomStep> {
    val report = state.report
    val topic = report.symptom ?: return listOf(SymptomStep.SYMPTOM)
    val steps = mutableListOf(SymptomStep.SYMPTOM)
    steps += topic.script.steps(state)
    val at = steps.indices.firstOrNull { crisisCalledFor(topic, report.only(steps.take(it + 1))) }
    if (at != null && !crisisAskedNow(state)) steps.add(at + 1, SymptomStep.CRISIS)
    return steps
}

/**
 * Whether "are you able to keep yourself safe right now?", asked in [state]'s thread, stands for the
 * topic's own question about suicide: asked for the person's words of suicide, as ever; asked for a
 * flag of the model's reading alone, only once answered other than "I am not having thoughts like
 * that" — after that "no" the topic's question is asked where the rules ask it, as it is with no model,
 * so a false flag answered "no" takes nothing away.
 */
private fun crisisAskedNow(state: ChatState): Boolean {
    val chat = state.chat
    if (SymptomStep.CRISIS_NOW !in chat.askedSteps()) return false
    return crisisIn(chat.words()) || !state.report.has(SymptomStep.CRISIS_NOW, SymptomOption.NO_SUCH_THOUGHTS)
}

/** A symptom topic's own questions after "which": "the same as before?", its script, how bad. */
private fun symptomTopicSteps(state: ChatState, topic: Symptom): List<SymptomStep> {
    val report = state.report
    val steps = mutableListOf<SymptomStep>()
    if (state.earlier != null) steps += SymptomStep.SAME
    steps += CHAT_ASKS[topic].orEmpty().map { it.step }.filter { step ->
        CHAT_CONDITIONS[step]?.invoke(topic, report) ?: true
    }
    // A chest marked on the map with an arm, the neck, the jaw, the back or the belly: whether the
    // pain spreads there from the chest is asked before what comes with it.
    val spread = SymptomStep.CHEST_SPREAD in state.chat.askedSteps() || chestSpreadCodes(state).isNotEmpty()
    if (topic == Symptom.CHEST_PAIN && spread) {
        steps.add(steps.indexOf(SymptomStep.WITH).coerceAtLeast(0), SymptomStep.CHEST_SPREAD)
    }
    steps += SymptomStep.SEVERITY
    return steps
}

/**
 * The questions the person's own words call for: the one about safety, then each emergency's, then
 * the medicine package's ([medicineWordSteps]), which reads them with the person's [courses] among the
 * medicines it knows ("I took 5 propranolol" names a course the built-in list does not) and keeps the
 * ones [chat] asked already. [report] is the thread they were written in.
 *
 * Each is the rules' or a reading's: a flag the model raised on one of the messages adds its fixed
 * question, in its words — after every question the rules' own words ask, in the
 * script's order among those the readings alone bring. A reading never puts its question before one
 * of the rules', so it never holds back the rules' emergency, and the stop its "yes" calls for, by a
 * turn: the rules' questions are the first of these, as they are with no model.
 */
internal fun wordSteps(chat: SymptomChat, report: SymptomReport, courses: List<Medication>): List<SymptomStep> {
    val words = chat.words()
    if (words.isBlank()) return emptyList()
    val emergencies = emergencyIn(words).toSet()
    val rules = listOfNotNull(SymptomStep.CRISIS_NOW.takeIf { crisisIn(words) }) +
        EmergencyWords.entries.filter { it in emergencies }.map { it.step } +
        medicineWordSteps(words, report.withoutReadings(), courses, chat.askedSteps())
    val read = chat.routesByReadings() || report.chat?.routesByReadings() == true
    return if (read) rules + readingsWordSteps(chat, report, courses).filter { it !in rules } else rules
}

/**
 * The questions [chat]'s words call for with the readings kept with its messages: a flag's own, in the
 * script's order — the safety one, each emergency's — and the medicine package's with the readings'
 * flags of too much among the words' ([wordsQuestions]). What of them the rules alone ask is theirs.
 */
private fun readingsWordSteps(chat: SymptomChat, report: SymptomReport, courses: List<Medication>): List<SymptomStep> {
    val read = chat.readSteps()
    return listOfNotNull(SymptomStep.CRISIS_NOW.takeIf { it in read }) +
        EmergencyWords.entries.filter { it.step in read }.map { it.step } +
        medicineWordSteps(chat.words(), report, courses, chat.askedSteps())
}

/**
 * The question put next, or null for the closing turn. Nothing more is asked once an answer called
 * for emergency services: the chat goes straight to its close. Words of suicide or of an emergency
 * put their question first, before the topic if they come before it, and whatever was being asked
 * is asked again after.
 */
internal fun nextChatStep(state: ChatState): SymptomStep? {
    val chat = state.chat
    val stopped = chat.ended || firedRules(state.report).any { it.alert.urgency == SymptomUrgency.EMERGENCY }
    val asked = chat.askedSteps()
    val words = wordSteps(chat, state.report, state.thread.env.courses)
    return if (stopped) null else (words + chatSteps(state)).firstOrNull { it !in asked }
}

/**
 * The question [step] in its topic's words; the label otherwise, which for the chat's own questions
 * is the question. In a chat for an earlier day ([pastDay]) the red flags are asked about now — safety
 * is always now — and a feeling's weight about that day.
 */
internal fun chatQuestionRes(
    topic: Symptom?,
    step: SymptomStep,
    report: SymptomReport,
    pastDay: Boolean = false,
): Int = when (step) {
    SymptomStep.ONSET -> if (topic == Symptom.FAINTING) R.string.symptom_ask_onset_event else step.labelRes

    SymptomStep.SAME -> R.string.symptom_ask_same

    SymptomStep.DURATION -> if (report.has(SymptomStep.PATTERN, SymptomOption.JUST_ONCE)) {
        R.string.symptom_ask_duration_once
    } else {
        step.labelRes
    }

    SymptomStep.WITH -> if (pastDay) R.string.symptom_ask_with_now else withQuestionRes(topic)

    SymptomStep.SEVERITY -> when {
        topic?.mind != true -> step.labelRes
        pastDay -> R.string.symptom_ask_severity_mind_day
        else -> R.string.symptom_ask_severity_mind
    }

    else -> PAGE_QUESTIONS[step] ?: step.labelRes
}

/** The steps' short labels, asked in full in a chat; and the chest's "does it spread?". */
private val PAGE_QUESTIONS = mapOf(
    SymptomStep.AFTER_MEAL to R.string.symptom_ask_after_meal,
    SymptomStep.CAUSE to R.string.symptom_ask_cause,
    SymptomStep.TIMES to R.string.symptom_ask_times,
    SymptomStep.WHEN to R.string.symptom_ask_when,
    SymptomStep.BLOOD to R.string.symptom_ask_blood,
    SymptomStep.CHEST_SPREAD to R.string.symptom_ask_chest_spread,
)

private fun withQuestionRes(topic: Symptom?): Int = when (topic) {
    Symptom.WORRY, Symptom.STRESS -> R.string.symptom_ask_with_mind
    Symptom.LOW_MOOD -> R.string.symptom_ask_with_mood
    else -> R.string.symptom_ask_with
}

/**
 * The script that words [step] in this thread: the thread's topic's, or — for a question the person's
 * words brought into any thread, the medicine's "more than the packet says?" — the script whose own
 * it is ([TopicScript.globalSteps]); a thread with no topic yet asks as a symptom's chat does.
 */
internal fun stepScript(state: ChatState, step: SymptomStep): TopicScript {
    val own = state.report.symptom?.script ?: SymptomScript
    if (step in own.steps(state)) return own
    return Symptom.entries.map { it.script }.firstOrNull { step in it.globalSteps } ?: own
}

/**
 * What the bot says before asking [step] after the turns so far, and what it then waits for; null
 * [step] is the thread's close ([closingTurn]). In order: the line the last answer called for, a lead
 * line, the journal's card once after the topic, then the question. The topic question's own turn —
 * the opening, the chips the words propose — is the router's ([chatTurn]); this is its plain form.
 */
internal fun turnBefore(state: ChatState, step: SymptomStep?): BotTurn {
    val pieces = mutableListOf<BotPiece>()
    alertDue(state.chat.turns)?.let { pieces += alertPiece(state, it) }
    if (step == null) return closingTurn(state, pieces)
    pieces += leadPieces(state, step)
    if (factsDue(state)) {
        pieces += BotPiece.Line(if (state.pastDay) ChatLine.FACTS_DAY else ChatLine.FACTS_NOW)
        pieces += BotPiece.Facts
    }
    // Where help is comes with the question about suicide a reading's flag alone brought, wherever that
    // question comes — straight after the message ([afterWords]) or after the rules' own.
    val support = BotPiece.Line(ChatLine.CRISIS_SUPPORT)
    if (step == SymptomStep.CRISIS_NOW && readCrisisAlone(state.chat) && support !in pieces) pieces += support
    if (step != SymptomStep.SYMPTOM) pieces += askPiece(state, step)
    return BotTurn(pieces, replyFor(state, step))
}

/** Whether a reading's flag, and no word of suicide the rules read, brought [chat]'s question about suicide. */
private fun readCrisisAlone(chat: SymptomChat): Boolean =
    SymptomStep.CRISIS_NOW in chat.readSteps() && !crisisIn(chat.words())

/** The question, as its script words it: the earlier chat's day for SAME, arguments, a help behind a "?". */
private fun askPiece(state: ChatState, step: SymptomStep): BotPiece.Ask {
    val script = stepScript(state, step)
    return BotPiece.Ask(
        step = step,
        day = state.earlier?.day.takeIf { step == SymptomStep.SAME },
        text = script.questionText(state, step),
        help = script.help(state, step),
    )
}

/** The journal's card comes once, right after the topic, when the snapshot has a figure for it. */
private fun factsDue(state: ChatState): Boolean {
    val last = state.chat.turns.lastOrNull() as? ChatTurn.Asked
    val topic = state.report.symptom ?: return false
    return last?.step == SymptomStep.SYMPTOM && state.report.context?.present(topic.facts)?.isNotEmpty() == true
}

/**
 * The answer whose line the next turn says: the last one, when it called for one; past a topic turn the
 * chat took itself with none of its own — the chest's, after "is it there now?" — the answer before
 * it, whose line no turn has said yet.
 */
private fun alertDue(turns: List<ChatTurn>): ChatTurn.Asked? {
    val last = turns.lastOrNull() as? ChatTurn.Asked ?: return null
    if (last.alert != null || !last.routed || last.step != SymptomStep.SYMPTOM) return last.takeIf { it.alert != null }
    return (turns.getOrNull(turns.lastIndex - 1) as? ChatTurn.Asked)?.takeIf { !it.routed && it.alert != null }
}

private fun alertPiece(state: ChatState, last: ChatTurn.Asked): BotPiece.Alert {
    val alert = checkNotNull(last.alert)
    val rule = alertRule(last.rule)
    val source = rule?.sourceOf?.invoke(state.report) ?: rule?.sourceRes ?: state.report.symptom?.sourceRes
        ?: R.string.symptom_source_call_999
    return BotPiece.Alert(alert, source, (state.report.symptom?.script ?: SymptomScript).alertText(state, alert))
}

private fun replyFor(state: ChatState, step: SymptomStep): ChatReply = when (step) {
    SymptomStep.SYMPTOM -> ChatReply.Topics(topicsOffered(state.chat).map { TopicChoice(it) })
    SymptomStep.SEVERITY -> ChatReply.Severity
    else -> stepScript(state, step).let { script -> withChatMarks(script.reply(state, step), script, state) }
}

/**
 * [reply] with the chips an earlier thread's answer points at ([TopicScript.markPairs]): for each pair
 * whose second step is the one asked, the newest earlier thread's answer to its first, where the
 * question offers it and the words have not marked it already. Marks only: never ticked, never armed.
 */
internal fun withChatMarks(reply: ChatReply, script: TopicScript, state: ChatState): ChatReply {
    if (reply !is ChatReply.Choose) return reply
    val from = script.markPairs.filter { it.second == reply.step }.map { it.first }
    val earlier = state.thread.others.asReversed().firstNotNullOfOrNull { other ->
        from.firstNotNullOfOrNull { step -> other.report.answers[step]?.takeIf { it.isNotEmpty() } }
    }.orEmpty()
    val marks = earlier.filterTo(LinkedHashSet()) { it in reply.options && it !in reply.marked }
    return if (marks.isEmpty()) reply else reply.copy(chatMarked = marks)
}

/** The topics the person's words named, with "something else" after them; every symptom topic when they named none. */
private fun topicsOffered(chat: SymptomChat): List<Symptom> {
    val found = symptomTopicsIn(chat.words()).filter { it != Symptom.OTHER }
    return if (found.isEmpty()) TOPIC_ORDER.filter { it.record == ChatRecord.SYMPTOM } else found + Symptom.OTHER
}

private fun leadPieces(state: ChatState, step: SymptomStep): List<BotPiece> {
    val turns = state.chat.turns
    return when (val last = turns.lastOrNull()) {
        null -> listOfNotNull(ChatLine.OPEN, ChatLine.OPENING, ChatLine.ADULTS.takeUnless { state.adult })
            .map { BotPiece.Line(it) }

        is ChatTurn.Wrote -> afterWords(state, last, step)

        is ChatTurn.Said, is ChatTurn.Narrowed -> emptyList()

        is ChatTurn.Asked -> (state.report.symptom?.script ?: SymptomScript).lead(state, last, step)
    }
}

/** The questions the person's words added, which their topic's own chat would not have asked. */
internal fun askedForWords(state: ChatState, step: SymptomStep): Boolean =
    step in wordSteps(state.chat, state.report, state.thread.env.courses) && step !in chatSteps(state)

/**
 * After a message. Words of suicide are met first, before anything is asked, with where help is —
 * emergency services, a crisis line that is always open, someone trusted — and only then the
 * question whether they are safe now. Words of an emergency bring its question. Words before a topic
 * bring the topics they named (the router's own reply stands in a chat). Words during a question are
 * kept, and the chat says plainly that it does not read them — the first time at length, then in
 * short, or that a new topic they named comes after this one — with the line to call for an
 * emergency, before asking again what it was asking. A question the words brought keeps that line
 * beside it when they held no English or Russian word read — «nie mogę oddychać», «dor no peito» —
 * as every reply to such a message does ([SafetyWords]).
 *
 * A reading kept with the messages only adds: where help is for a crisis
 * only it flagged comes with its own question — here when that question is the one asked now, or was
 * asked already — and the rules' question, when the words bring one, is asked first with the rules'
 * lines. Every safety piece the rules alone would have met the message with stands in the turn
 * beside the reading's ([withRulesFloor]).
 */
private fun afterWords(state: ChatState, last: ChatTurn.Wrote, step: SymptomStep): List<BotPiece> {
    val lines = wordsLines(state, last, step).map { BotPiece.Line(it) }
    // A record the words could become comes with the remark on them, before the question is asked
    // again — but not one the thread's own chat asks about, nor beside where help is.
    // Never in a chat a crisis answer has turned, nor above a question of where help is: the gate of the
    // topic turn's cards, and then some.
    if (lines.firstOrNull()?.line !in REMARKS || state.thread.crisisInChat || step in HELP_STEPS) return lines
    val cards = journalCards(last, state.thread.id, messagesOf(state.chat).lastIndex, state.thread.journal)
        .filter { it.event.type !in CHAT_OWN_JOURNAL }
    // Beside a card, without the line to call for an emergency ([SAFETY_LINE]).
    return if (cards.isEmpty()) lines else lines.take(1) + cards + (lines.drop(1) - SAFETY_LINE)
}

/** The questions of where help is — a crisis's, an emergency's, a reading's flag's: no record card above them. */
private val HELP_STEPS: Set<SymptomStep> by lazy {
    READ_SAFETY_STEPS.toSet() + EmergencyWords.entries.map { it.step } + SymptomStep.CRISIS
}

/** The lines a message written during a question is met with ([remarkLine]). */
private val REMARKS = setOf(ChatLine.REMARK, ChatLine.REMARK_MODEL, ChatLine.REMARK_SHORT, ChatLine.QUEUED)

/** What [afterWords] says, by the first case that holds. */
private fun wordsLines(state: ChatState, last: ChatTurn.Wrote, step: SymptomStep): List<ChatLine> =
    when {
        crisisIn(last.text) || readCrisisMet(state, last, step) -> listOf(ChatLine.CRISIS_SUPPORT)

        askedForWords(state, step) -> listOfNotNull(
            ChatLine.WORDS_CHECK,
            ChatLine.SAFETY.takeUnless { chatReader.read(listOf(last), state.wordsSetting()).language },
        )

        step == SymptomStep.SYMPTOM && symptomTopicsIn(state.chat.words()).any { it != Symptom.OTHER } ->
            listOf(ChatLine.MATCH)

        step == SymptomStep.SYMPTOM -> listOf(noMatchLine(last), ChatLine.SAFETY)

        else -> listOf(remarkLine(state, last), ChatLine.SAFETY)
    }

/**
 * Whether [last]'s reading flagged a crisis that is met now with where help is: its question is the one
 * asked, or was asked already in the thread, so no question of it is still to come.
 */
private fun readCrisisMet(state: ChatState, last: ChatTurn.Wrote, step: SymptomStep): Boolean =
    last.read?.raises(SymptomStep.CRISIS_NOW) == true &&
        (step == SymptomStep.CRISIS_NOW || SymptomStep.CRISIS_NOW in state.chat.askedSteps())

/**
 * What a message written during a question is met with: "you are welcome" for thanks and nothing else;
 * [ChatLine.QUEUED] when it named a topic offered here that the chat will come to after this one;
 * else the long remark the first time in the chat — in a model's words for a message a model read
 * ([longRemark]) — and the short one after. A message at the opening or between threads is never a remark.
 */
private fun remarkLine(state: ChatState, last: ChatTurn.Wrote): ChatLine {
    val setting = state.wordsSetting()
    val found = chatReader.read(listOf(last), setting)
    val topic = state.report.symptom
    val named = found.mentions.map { it.mention.topic }
        .filter { it != topic && it != Symptom.OTHER && it.script.offered(setting) }
    val served = state.thread.before.toSet()
    val earlier = state.thread.others.any { remarked(it.report) } ||
        remarked(state.report.copy(chat = state.chat.copy(turns = state.chat.turns.dropLast(1))))
    return when {
        found.smallTalk == SmallTalk.THANKS -> ChatLine.WELCOME
        named.any { it !in served } -> ChatLine.QUEUED
        earlier -> ChatLine.REMARK_SHORT
        else -> longRemark(last)
    }
}

/** Whether a message was written in [report]'s thread after its topic question was taken: a remark then. */
private fun remarked(report: SymptomReport): Boolean {
    val turns = report.chat?.turns.orEmpty()
    val topicAt = turns.indexOfFirst { it is ChatTurn.Asked && it.step == SymptomStep.SYMPTOM }
    return topicAt >= 0 && turns.drop(topicAt + 1).any { it is ChatTurn.Wrote }
}

private val BODY_ACKS = listOf(ChatLine.ACK_1, ChatLine.ACK_2, ChatLine.ACK_3)

/**
 * After an answer: the start of a topic, "that sounds really hard" after the heaviest answers, a
 * short thanks otherwise — never after a safety line, which is the lead itself, and for a feeling
 * only every other answer and only "thank you for telling me", so the chat does not seem to
 * understand more than it does. Picked by the turn's position, so a chat drawn again says the same.
 * Before the topic question (a word's question answered first) the router says what comes next.
 */
private fun afterAnswer(state: ChatState, last: ChatTurn.Asked, index: Int, step: SymptomStep): List<ChatLine> {
    val report = state.report
    val topic = report.symptom
    val lines = mutableListOf<ChatLine>()
    when {
        step == SymptomStep.SYMPTOM -> Unit
        last.step == SymptomStep.SYMPTOM && topic != null -> lines += startLines(topic)
        heavyAnswer(report, last.step) -> lines += ChatLine.HEAVY
        last.alert != null -> Unit
        topic?.mind == true -> if (index % 2 == 0) lines += ChatLine.ACK_MIND
        else -> lines += BODY_ACKS[index % BODY_ACKS.size]
    }
    if (last.step == SymptomStep.ABOUT && report.has(SymptomStep.ABOUT, SymptomOption.SOMETHING_ELSE)) {
        lines += ChatLine.OWN_WORDS
    }
    if (step == SymptomStep.CRISIS) lines += ChatLine.CRISIS_LEAD
    if (askedForWords(state, step)) lines += ChatLine.WORDS_CHECK
    return lines
}

/** "Feeling hopeless", "I am not coping", a feeling told as severe: answers met with more than thanks. */
private fun heavyAnswer(report: SymptomReport, step: SymptomStep): Boolean = when (step) {
    SymptomStep.WITH -> report.has(step, SymptomOption.HOPELESS)
    SymptomStep.COPING -> report.has(step, SymptomOption.NOT_COPING)
    SymptomStep.SEVERITY -> report.symptom?.mind == true && report.severity == SymptomSeverity.SEVERE
    else -> false
}

private fun startLines(topic: Symptom): List<ChatLine> = when {
    topic == Symptom.OTHER -> listOf(ChatLine.START_OTHER, ChatLine.OWN_WORDS)
    topic.mind -> listOf(ChatLine.START_MIND)
    else -> listOf(ChatLine.START_BODY)
}

/**
 * Whether the person answered the question about suicide with anything but "no" — to the question
 * their words brought, "I am not having thoughts like that", the answer a word matched by mistake
 * gets ("this headache will kill me").
 */
internal fun crisisAnswered(report: SymptomReport): Boolean =
    report.answers[SymptomStep.CRISIS].orEmpty().any { it != SymptomOption.NO } ||
        report.answers[SymptomStep.CRISIS_NOW].orEmpty().any { it != SymptomOption.NO_SUCH_THOUGHTS } ||
        medicineCrisisAnswered(report)

/** The topics the breathing page is offered after: never with a body sign the chat cannot put down to the feeling. */
private val BREATHING_TOPICS = setOf(Symptom.WORRY, Symptom.STRESS, Symptom.SLEEP_WORRY)

private val BODY_SIGNS = setOf(SymptomOption.BREATHLESS, SymptomOption.CHEST_PAIN, SymptomOption.RACING_HEART)

/**
 * What the close offers. Nothing after an emergency. Adding a visit to the plans where the chat
 * called for a doctor — but not after an answer about suicide anywhere in the chat, where a visit to
 * plan is the wrong thing to put in front of anyone, nor after a line to get advice now, which is not
 * to wait for one ([nowLineFired]), and never as the only thing said: the closing line names what to
 * do. The breathing page after worry, stress or sleepless nights left at self-care, unless
 * breathlessness, chest pain or a racing heart was ticked.
 */
internal fun chatOffers(state: ChatState): List<ChatOffer> {
    val report = state.report
    val urgency = chatUrgency(report)
    if (urgency == SymptomUrgency.EMERGENCY) return emptyList()
    val crisis = crisisAnswered(report) || state.thread.crisisInChat
    val bodySign = report.hasAny(SymptomStep.WITH, BODY_SIGNS)
    val visit = urgency in setOf(SymptomUrgency.SAME_DAY, SymptomUrgency.GP) && !nowLineFired(report)
    return listOfNotNull(
        ChatOffer.BOOK_VISIT.takeIf { !crisis && visit },
        ChatOffer.BREATHING.takeIf {
            !crisis && urgency == SymptomUrgency.SELF_CARE && report.symptom in BREATHING_TOPICS && !bodySign
        },
    )
}

/**
 * The thread's close: the last answer's line, then its topic's close ([TopicScript.close]) — what to
 * do now by the thread's most urgent answer, what is saved, the offers. The thanks, or a goodbye of
 * any kind, is the router's end of the whole chat, never a thread's.
 */
private fun closingTurn(state: ChatState, alert: List<BotPiece>): BotTurn =
    BotTurn((state.report.symptom?.script ?: SymptomScript).close(state, alert), ChatReply.End)

/** What to do now, by the thread's most urgent answer: the closing lines of a symptom's chat. */
internal fun closeLines(urgency: SymptomUrgency, crisis: Boolean, topic: Symptom?): List<ChatLine> = when (urgency) {
    SymptomUrgency.EMERGENCY -> if (crisis) {
        listOf(ChatLine.CLOSE_EMERGENCY, ChatLine.CLOSE_EMERGENCY_MIND)
    } else {
        listOf(ChatLine.CLOSE_EMERGENCY)
    }

    SymptomUrgency.SAME_DAY -> listOf(if (crisis) ChatLine.CLOSE_CRISIS else ChatLine.CLOSE_TODAY)

    SymptomUrgency.GP -> listOf(ChatLine.CLOSE_GP)

    SymptomUrgency.SELF_CARE -> listOf(if (topic?.mind == true) ChatLine.CLOSE_MIND else ChatLine.CLOSE_BODY)
}

/**
 * Every symptom topic's script: the one-topic chat behind [TopicScript], filing the
 * thread's KIND_SYMPTOM row, written through on every change from the moment its topic is chosen —
 * a chat left after the emergency question has still recorded it.
 */
internal object SymptomScript : TopicScript {
    override fun steps(state: ChatState): List<SymptomStep> =
        state.report.symptom?.let { symptomTopicSteps(state, it) }.orEmpty()

    override fun options(state: ChatState, step: SymptomStep): List<SymptomOption> =
        chatOptions(state.report.symptom, step, state.female)

    /**
     * The answers as chips, "when did it start?" marked where the thread's words name the time
     * ([onsetMarks]), and a low mood's "what is it about?" where a mood noted in this chat said
     * ([moodReasonMarks]).
     */
    override fun reply(state: ChatState, step: SymptomStep): ChatReply {
        val options = options(state, step)
        val marked = if (step == SymptomStep.ONSET) onsetMarks(state, options) else emptySet()
        return ChatReply.Choose(step, options, marked = marked, chatMarked = moodReasonMarks(state, step, options))
    }

    override fun question(state: ChatState, step: SymptomStep): Int =
        chatQuestionRes(state.report.symptom, step, state.report, state.pastDay)

    /** The chest's "does it spread?" names the areas marked with it. */
    override fun questionText(state: ChatState, step: SymptomStep): ChatText? =
        if (step == SymptomStep.CHEST_SPREAD) chestSpreadQuestion(state) else null

    /** "Does it spread?" keeps the areas it named, so the sitting does not ask about them again. */
    override fun freeze(state: ChatState, step: SymptomStep): Map<String, String> =
        if (step == SymptomStep.CHEST_SPREAD) chestSpreadFacts(state) else emptyMap()

    /**
     * As one topic's chat says it, with the start lines only in the chat's first thread of their
     * kind — the body's without its sympathy where the question whether feeling unwell is physical
     * said it already ([unwellAsked]). A chest pain thread the chat started itself says why instead:
     * the chest was written about, or marked on the map — its questions come first.
     */
    override fun lead(state: ChatState, last: ChatTurn.Asked?, step: SymptomStep): List<BotPiece> {
        last ?: return emptyList()
        routedLead(state)?.let { line -> return listOf(BotPiece.Line(line)) }
        val lines = afterAnswer(state, last, state.chat.turns.lastIndex, step)
        val topic = state.report.symptom
        val again = if (topic != null && last.step == SymptomStep.SYMPTOM) {
            startLines(topic).takeIf { own ->
                state.thread.before.any { it.record == ChatRecord.SYMPTOM && startLines(it) == own }
            }
        } else {
            null
        }
        val said = lines - again.orEmpty().toSet()
        val sympathised = ChatLine.START_BODY in said && unwellAsked(state)
        return said.map { line ->
            BotPiece.Line(if (sympathised && line == ChatLine.START_BODY) ChatLine.START_QUESTIONS else line)
        }
    }

    /**
     * The closing turn of a symptom's chat with the card in the summary's place, and without the
     * thanks and "something else" — the end of the whole chat is the router's.
     */
    override fun close(state: ChatState, alert: List<BotPiece>): List<BotPiece> {
        val report = state.report
        val urgency = chatUrgency(report)
        val crisis = crisisAnswered(report)
        val topic = report.symptom
        val pieces = alert.toMutableList()
        if (urgency != SymptomUrgency.EMERGENCY && !crisis && topic != null) pieces += BotPiece.Saved(state.thread.id)
        val floorSource = topic?.sourceRes?.takeIf { floorSetsUrgency(report) }
        // What to do now reads every answer about suicide in the chat, as the end and the offers do;
        // after a line to get advice now — too much of a medicine told in this thread — not to wait.
        val mind = crisis || state.thread.crisisInChat
        val lines = if (urgency == SymptomUrgency.SAME_DAY && nowLineFired(report)) {
            listOf(ChatLine.CLOSE_NOW)
        } else {
            closeLines(urgency, mind, topic)
        }
        pieces += lines.map { BotPiece.Line(it, floorSource) }
        pieces += chatOffers(state).map { BotPiece.Offer(it, state.thread.id) }
        return pieces
    }

    /** "Dizziness · Yesterday · Moderate", from the row as it stands. */
    override fun savedSummary(
        resources: Resources,
        row: JournalEntry?,
        course: Medication?,
        role: String,
        state: LinkState,
    ): String {
        val report = decodeSymptomPayload(row?.measurementPayloadJson)
        return listOfNotNull(
            report.symptom?.let { resources.getString(it.labelRes) },
            report.answers[SymptomStep.ONSET]?.firstOrNull()?.let { resources.getString(it.labelRes) },
            report.severity?.let { resources.getString(it.labelRes) },
        ).joinToString(" · ")
    }

    /**
     * The thread's symptom row from its topic's choice on, with its hidden tag. A thread the chat
     * started itself — chest pain after the chest was marked or written about — files nothing until
     * the person has answered one of its own questions: an answer carried in from earlier in the
     * sitting is already kept where it was given.
     */
    override fun wants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> {
        val turns = thread.report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Asked>()
        val topicTurn = turns.firstOrNull { it.step == SymptomStep.SYMPTOM }
        val ownAnswer = turns.any { !it.routed && it.step != SymptomStep.SYMPTOM }
        if (topicTurn == null || (topicTurn.routed && !ownAnswer)) return emptyMap()
        val saved = thread.report.forSave()
        val draft = RecordDraft.Journal(JournalDoor.PLAIN, wanted = null) { shell, _ ->
            symptomEntry(
                title = chatTitle(saved) { ctx.strings(it.labelRes) },
                report = saved,
                day = LocalDate.parse(shell.day),
                today = ctx.clock.today,
                now = shell.occurredAt,
                previous = shell,
                chatKey = ctx.chatKey,
            )
        }
        return mapOf("" to RecordWant.File(draft))
    }

    /** The answers and the hidden tag: a message naming another topic rewrites the row, which only the chat edits. */
    override fun recordSig(thread: ChatThread, role: String): Int =
        encodeSymptomPayload(thread.report.forSave().copy(context = null, chat = null)).hashCode()

    override fun answerText(state: ChatState, step: SymptomStep): ChatText? = when (step) {
        SymptomStep.SYMPTOM -> state.report.symptom?.let { ChatText(it.labelRes) }
        SymptomStep.SEVERITY -> state.report.severity?.let { ChatText(it.labelRes) }
        else -> null
    }

    override fun title(resources: Resources, thread: ChatThread): String =
        chatTitle(thread.report) { resources.getString(it.labelRes) }

    override fun serves(thread: ChatThread, mention: TopicMention): Boolean = thread.topic == mention.topic

    /**
     * After a chest pain thread the person's words about the chest began, closed calm: the pain on the
     * map. After a low mood, worry or stress talk: the mood noted ([moodAfterTalk]).
     */
    override fun followUps(state: ChatState): List<TopicChoice> {
        val calm = state.report.symptom == Symptom.CHEST_PAIN && state.thread.from == ROUTE_WORDS &&
            firedRules(state.report).none { it.alert.urgency == SymptomUrgency.EMERGENCY }
        val chest = TopicChoice(Symptom.PAIN, TopicMention(Symptom.PAIN, "CHEST"), R.string.symptom_chat_pain_map)
        return listOfNotNull(chest.takeIf { calm }, moodAfterTalk(state))
    }

    override fun offered(setting: ChatSetting): Boolean = true

    override val oncePerChat: Boolean = true

    override val flashAt: FlashAt = FlashAt.CLOSE
}

/**
 * The line a chest pain thread the chat started itself opens with, before its first question of its
 * own: [ChatLine.CHEST_WORDS] after chest words, [ChatLine.CHEST_FIRST] for a chest marked in a pain
 * thread. Null once the person has answered in it, and for any other thread.
 */
private fun routedLead(state: ChatState): ChatLine? {
    val line = when {
        state.thread.from == ROUTE_WORDS -> ChatLine.CHEST_WORDS
        state.thread.from?.startsWith(ROUTE_HANDOVER) == true -> ChatLine.CHEST_FIRST
        else -> null
    }
    val turns = state.chat.turns
    val topicAt = turns.indexOfFirst { it is ChatTurn.Asked && it.step == SymptomStep.SYMPTOM }
    val routed = (turns.getOrNull(topicAt) as? ChatTurn.Asked)?.routed == true
    val own = turns.drop(topicAt + 1).any { it !is ChatTurn.Asked || !it.routed }
    return line?.takeIf { routed && !own }
}

/** Whether a turn begins with a line to call emergency services, or with where help is: never held back by the dots. */
internal fun BotTurn.urgent(): Boolean = pieces.any {
    (it is BotPiece.Alert && it.alert.urgency == SymptomUrgency.EMERGENCY) ||
        (it is BotPiece.Line && it.line == ChatLine.CRISIS_SUPPORT)
}

private const val TYPING_STILL_MS = 400L
private const val TYPING_BASE_MS = 500L
private const val TYPING_PER_CHAR_MS = 15L
private const val TYPING_MIN_MS = 700L
private const val TYPING_MAX_MS = 1_800L

/**
 * How long the three dots stand before a turn of [chars] characters: long enough to read as a reply
 * being written, never long enough to wait on. None at all before a line to call emergency services
 * or where help is ([urgent]); with animations off, [TYPING_STILL_MS] of dots that do not move.
 */
internal fun chatTypingMs(chars: Int, animationsOn: Boolean, urgent: Boolean): Long = when {
    urgent -> 0L
    !animationsOn -> TYPING_STILL_MS
    else -> (TYPING_BASE_MS + TYPING_PER_CHAR_MS * chars).coerceIn(TYPING_MIN_MS, TYPING_MAX_MS)
}

/**
 * How long the dots stand in the live row before a turn's words come into it while a screen reader
 * explores by touch: a little more than one of the platform's 100 ms batches of accessibility events,
 * so the words land inside a live region that is already there — the change a screen reader announces.
 */
internal const val LIVE_REGION_LEAD_MS = 150L

/**
 * How long the dots stand before a turn of [chars] characters: [chatTypingMs], or under touch
 * exploration [LIVE_REGION_LEAD_MS] whatever the turn — the dots are pacing for the eye, and a line to
 * call emergency services is announced only once it arrives inside a region that stood before it.
 */
internal fun liveHoldMs(chars: Int, animationsOn: Boolean, urgent: Boolean, touchExploration: Boolean): Long =
    if (touchExploration) LIVE_REGION_LEAD_MS else chatTypingMs(chars, animationsOn, urgent)

/** [this] with [turn] added. */
private fun SymptomReport.withTurn(turn: ChatTurn): SymptomReport {
    val told = chat ?: SymptomChat()
    return copy(chat = told.copy(turns = told.turns + turn))
}

/**
 * [step] taken as answered: its turn added, carrying the most urgent line among the rules the
 * answer has just made true. A rule already shown at an earlier answer is not shown again. A
 * question of several with nothing ticked is not answered: a clock that outlived its ticks — the
 * pencil took them back — files nothing, and the question is still asked. Nor is a step whose answer
 * is a value or a painting while it holds none ([holdsAnswer]).
 */
internal fun SymptomReport.answered(step: SymptomStep): SymptomReport {
    if (!takeable(step)) return this
    val before = firedRules(asTold()).map { it.id }.toSet()
    val after = withTurn(ChatTurn.Asked(step))
    val fresh = firedRules(after.asTold()).filter { it.id !in before }
    val alert = mostUrgent(fresh.map { it.alert }) ?: return after
    val rule = fresh.first { it.alert == alert }
    val turns = checkNotNull(after.chat).turns
    return after.copy(chat = after.chat.copy(turns = turns.dropLast(1) + ChatTurn.Asked(step, alert, rule.id)))
}

/**
 * The last turn's line worked out again against [previous], the report before that answer: a fact
 * frozen with the answer ([answeredLast]) may make a rule true, and its line stands under that answer.
 */
internal fun SymptomReport.realerted(previous: SymptomReport): SymptomReport {
    val told = chat ?: return this
    val last = told.turns.lastOrNull() as? ChatTurn.Asked ?: return this
    val before = firedRules(previous.asTold()).map { it.id }.toSet()
    val fresh = firedRules(asTold()).filter { it.id !in before }
    val alert = mostUrgent(fresh.map { it.alert })
    val rule = fresh.firstOrNull { it.alert == alert }?.id
    return copy(chat = told.copy(turns = told.turns.dropLast(1) + last.copy(alert = alert, rule = rule)))
}

/**
 * Whether [step] has something to be taken as its answer: a tick when it takes several, a value when
 * it holds one. A step whose answer is a value is taken by that value alone, whether or not its
 * widget lets several be picked: its options are never where it keeps them.
 */
private fun SymptomReport.takeable(step: SymptomStep): Boolean = when (step.kind.holds) {
    StepHolds.OPTIONS -> !step.multi || !answers[step].isNullOrEmpty()
    StepHolds.VALUE, StepHolds.PAINTING -> holdsAnswer(step)
}

/**
 * The ticks on [step] taken by the clock — only while [step] is the question [state]'s chat waits
 * on, read again from this report: a clock that outlived its question, because the pencil took the
 * chat back before it or a message's words asked something first, files nothing.
 */
internal fun SymptomReport.ticksTaken(step: SymptomStep, state: ChatState): SymptomReport =
    if (nextChatStep(state.copy(report = asTold())) == step) answered(step) else this

/** [topic] chosen. */
internal fun SymptomReport.chose(topic: Symptom): SymptomReport = copy(symptom = topic).answered(SymptomStep.SYMPTOM)

/** One answer to [step] tapped; "the same as before" ties the chat to [earlier]. */
internal fun SymptomReport.tapped(step: SymptomStep, option: SymptomOption, earlier: EarlierChat?): SymptomReport {
    val told = chat ?: SymptomChat()
    val same = if (step ==
        SymptomStep.SAME
    ) {
        earlier?.occurredAt.takeIf { option == SymptomOption.SAME_AS_BEFORE }
    } else {
        told.sameAs
    }
    return copy(
        answers = answers + (step to listOf(option)),
        values = if (step.kind.holds == StepHolds.OPTIONS) values - step else values,
        chat = told.copy(sameAs = same),
    ).answered(step)
}

/** How bad, tapped. */
internal fun SymptomReport.rated(severity: SymptomSeverity): SymptomReport =
    copy(severity = severity).answered(SymptomStep.SEVERITY)

/** A message, kept as written up to [SYMPTOM_TEXT_MAX] with what its [read] decided; a blank one is nothing. */
internal fun SymptomReport.wrote(text: String, read: ChatRead? = null): SymptomReport {
    val kept = text.trim().take(SYMPTOM_TEXT_MAX)
    return if (kept.isEmpty()) this else withTurn(ChatTurn.Wrote(kept, read))
}

/**
 * A value for [step] that is not an option — a dial, a ruler, areas tapped — waiting for the clock
 * or for Send as a tick waits: nothing is answered until [answered]. A blank one takes it away.
 */
internal fun SymptomReport.valued(step: SymptomStep, value: String): SymptomReport =
    if (value.isBlank()) copy(values = values - step) else copy(values = values + (step to value))

/** The painting on the body map so far, waiting for Send; only a step that holds a painting takes one. */
internal fun SymptomReport.painted(step: SymptomStep, strokes: List<PainStroke>): SymptomReport =
    if (step.kind.holds == StepHolds.PAINTING) copy(strokes = strokes) else this

/** The map's Send, or a widget's one tap: [step] answered with what it holds. */
internal fun SymptomReport.sent(step: SymptomStep): SymptomReport = answered(step)

/**
 * Words typed as the answer to [step] — a name, a dose — kept as written up to [SYMPTOM_TEXT_MAX] and
 * taken at once; blank words answer nothing.
 */
internal fun SymptomReport.typed(step: SymptomStep, text: String): SymptomReport {
    val kept = text.trim().take(SYMPTOM_TEXT_MAX)
    return if (kept.isEmpty()) this else valued(step, kept).answered(step)
}

/** The chat marked ended once nothing is left to ask, so the entry says it was finished. */
internal fun settled(state: ChatState, report: SymptomReport): SymptomReport {
    val chat = report.chat ?: return report
    if (chat.ended || chat.askedSteps().isEmpty()) return report
    return if (nextChatStep(state) == null) report.copy(chat = chat.copy(ended = true)) else report
}

/**
 * The chat taken back to the answer at [position], to change it: that answer and every one after it
 * go — but the answer being changed stays, shown chosen, so a question of several answers can be
 * adjusted rather than started again. Taking back the topic takes back everything asked after it,
 * since another topic asks other questions. What the person wrote stays, every word of it, after
 * the answers kept: words said are said, and words of suicide or of an emergency go on asking their
 * question. A model's words go with the answers they led to.
 *
 * Values and the painting go the same way as answers — the changed step's own stay, so the map opens
 * again with the person's strokes — while the facts the changed step froze go, to be worked out
 * again when it is answered again.
 */
internal fun rewoundTo(report: SymptomReport, position: Int): SymptomReport {
    val chat = report.chat ?: return report
    val turn = chat.turns.getOrNull(position) as? ChatTurn.Asked ?: return report
    val kept = chat.turns.take(position) + chat.turns.drop(position).filterIsInstance<ChatTurn.Wrote>()
    val keptSteps = kept.filterIsInstance<ChatTurn.Asked>().map { it.step }.toSet()
    val stays = keptSteps + turn.step.takeUnless { it == SymptomStep.SYMPTOM }
    val frozen = keptSteps.mapTo(HashSet()) { it.name }
    return report.copy(
        answers = report.answers.filterKeys { it in stays },
        severity = report.severity.takeIf { SymptomStep.SEVERITY in stays },
        values = report.values.filterKeys { it in stays },
        meta = report.meta.filterKeys { it.substringBefore('.') in frozen },
        strokes = report.strokes.takeIf { stays.any { it?.kind?.holds == StepHolds.PAINTING } }.orEmpty(),
        chat = chat.copy(
            turns = kept,
            ended = false,
            sameAs = chat.sameAs.takeIf { SymptomStep.SAME in stays },
        ),
    )
}

/**
 * A chat left before its end: what the last answer called for, without the question nobody answered,
 * and [line] — that it ended early, or for a chat that was deleted that this is what was saved of it.
 */
internal fun leftEarly(turn: BotTurn, line: ChatLine = ChatLine.LEFT_EARLY): BotTurn = BotTurn(
    turn.pieces.filter { it.isSafety() || it is BotPiece.Journal } + BotPiece.Line(line),
    ChatReply.End,
)

/**
 * An entry saved without a chat (schema 1) as the chat it would have been: the topic, its answered
 * questions in [symptomSteps] order, how bad, each with the line the rules give it. Ended when how
 * bad was answered, which such an entry holds only once everything was.
 */
internal fun synthesizedChat(report: SymptomReport): SymptomChat {
    val topic = report.symptom ?: return SymptomChat()
    var told = SymptomReport(topic, context = report.context, chat = SymptomChat())
    symptomSteps(topic).forEach { step ->
        val answer = report.answers[step].orEmpty()
        told = when {
            step == SymptomStep.SYMPTOM -> told.answered(step)
            step == SymptomStep.SEVERITY -> report.severity?.let { told.rated(it) } ?: told
            answer.isNotEmpty() -> told.copy(answers = told.answers + (step to answer)).answered(step)
            else -> told
        }
    }
    return checkNotNull(told.chat).copy(ended = report.severity != null)
}

/**
 * A symptom row whose chat was deleted, as the one thread it can still be told as: its topic, then
 * each question the chat asks that the row holds an answer for, until the first it does not —
 * ended when that leaves nothing more to ask.
 */
internal fun replayedChat(report: SymptomReport): SymptomReport {
    val topic = report.symptom ?: return report.copy(chat = SymptomChat())
    val start = SymptomReport(context = report.context, chat = SymptomChat()).chose(topic)
    val told = generateSequence(start) { replayedStep(it, report) }.last()
    val ended = nextChatStep(ChatState(told.asTold(), female = false, earlier = null)) == null
    return told.copy(chat = told.chat?.copy(ended = ended))
}

/** The next question of a replay answered from [report], or null when the row holds no answer to it. */
private fun replayedStep(told: SymptomReport, report: SymptomReport): SymptomReport? {
    val step = nextChatStep(ChatState(told.asTold(), female = false, earlier = null)) ?: return null
    val answer = report.answers[step].orEmpty()
    return when {
        step == SymptomStep.SEVERITY -> report.severity?.let { told.rated(it) }
        answer.isNotEmpty() -> told.copy(answers = told.answers + (step to answer)).answered(step)
        else -> null
    }
}

/**
 * The entry's title: the topic in the language showing, or for "something else" the person's first
 * words cut at a word within [OTHER_TITLE_MAX] characters, or at the limit itself where they hold no
 * space to cut at (Chinese, Japanese, one long word), never between the two halves of an emoji — unless
 * they are words of suicide, which would then stand in the day list, the history and a visit's note.
 */
internal fun chatTitle(report: SymptomReport, name: (Symptom) -> String): String {
    val topic = report.symptom ?: Symptom.OTHER
    val first = report.chat?.turns?.firstNotNullOfOrNull { it as? ChatTurn.Wrote }
    val words = first?.text
    val crisis = words != null && (crisisIn(words) || first.read?.raises(SymptomStep.CRISIS_NOW) == true)
    if (topic != Symptom.OTHER || words == null || crisis) return name(topic)
    val flat = words.replace(Regex("$SPACE+"), " ").trim()
    val cut = flat.take(OTHER_TITLE_MAX + 1).substringBeforeLast(' ', missingDelimiterValue = "").ifEmpty {
        typedUpTo(flat, OTHER_TITLE_MAX)
    }
    return if (flat.length <= OTHER_TITLE_MAX) flat else cut.trimEnd() + "…"
}

/**
 * The newest entry about [topic] in [rows] told before [now] and filed within the last
 * [EARLIER_CHAT_DAYS] days — a week ago today counts, eight days does not — other than the row
 * [except], the one being had.
 */
internal fun earlierChatFor(
    topic: Symptom?,
    rows: List<JournalEntry>,
    except: Long?,
    now: Long,
    zone: ZoneId,
): EarlierChat? {
    if (topic == null) return null
    val since = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(EARLIER_CHAT_DAYS)
    return rows.asSequence()
        .filter { it.kind == KIND_SYMPTOM && it.id != except && it.occurredAt <= now }
        .mapNotNull { row ->
            val day = row.day.toLocalDateOrNull()
                ?: Instant.ofEpochMilli(row.occurredAt).atZone(zone).toLocalDate()
            Triple(row, day, decodeSymptomPayload(row.measurementPayloadJson)).takeIf { !day.isBefore(since) }
        }
        .filter { (_, _, report) -> report.symptom == topic }
        .maxByOrNull { (row, _, _) -> row.occurredAt }
        ?.let { (row, day, report) ->
            EarlierChat(topic, day, row.occurredAt, report.answers[SymptomStep.ONSET]?.firstOrNull())
        }
}

/** A doctor's visit offered from a chat: [specialty] a [Checkup] name, [note] one line of the chat. */
data class VisitDraft(val specialty: String, val note: String)
