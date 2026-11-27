package com.pwnall.health

import java.time.LocalDate
import java.time.ZoneId

// A chat as a list of threads: each thread one topic, its answers and its own turns — the one-topic
// chat's conversation in shape — and the records it filed, linked forward by moment. Everything here is
// pure; the chat is kept in its own table ([ChatRow]) by [ChatFiling], never in the journal.

/**
 * One chat. [threads] in the order they began; [next] the id the next thread takes (ids are never
 * reused, so a link is never read as another thread's); [end] how it ended, if it did; [note] the
 * note after the end, which belongs to the chat and to no record, and [noteRead] what the chat took
 * from its model's reading of that note ([ChatRead]); [past] whether it was opened for an earlier day,
 * fixed at creation; [rev] raised by every commit, so an older copy never overwrites a newer one
 * ([ChatFiling.persist]); [held] what waits for the model's reading of words just sent.
 */
internal data class ChatLog(
    val threads: List<ChatThread> = emptyList(),
    val next: Int = 0,
    val end: ChatEnd? = null,
    val note: String = "",
    val past: Boolean = false,
    val rev: Int = 0,
    val noteRead: ChatRead? = null,
    val held: ChatHeld? = null,
    val attachments: String = "",
)

/** [ALL]: the person tapped "That is all for now". [STOP]: a line to call emergency services ended it. */
internal enum class ChatEnd { ALL, STOP }

/**
 * What waits in a chat for the model's reading of words just sent, a second
 * at most, kept with the chat — its row and a rotation's state alike — so that whatever page shows it
 * next knows: a page a rotation brings, one brought back from the saved state of a page left, the
 * process back from death. [Message]: the message at [Message.place], whose thread the bot's turn
 * routes only with its reading. [Typed]: words typed as the answer to [Typed.step], taken only once
 * read — a flag of the reading may make them a message instead ([typedLast]). With the reading gone
 * — the process died — the next page lets them go as the rules read them ([released]).
 */
internal sealed interface ChatHeld {
    data class Message(val place: MessagePlace) : ChatHeld

    data class Typed(val step: SymptomStep, val text: String) : ChatHeld
}

/**
 * One topic's conversation inside a chat. [from] is what started it — a mention's key ("PAIN:KNEE"),
 * `HANDOVER:<id>`, `RESUME:<id>`, `WORDS`, `FOLLOW:<key>` — and null for a topic chosen from the
 * menu; [filed] the records it made, as the database last said; [draft] the painting not yet sent;
 * [noRoute] set once the person left a start the chat chose from their words, which it never
 * chooses for that thread again; [cards] what became of the record cards of its messages
 * ([BotPiece.Journal]): cancelled, opened in their form, or saved from the card.
 */
internal data class ChatThread(
    val id: Int,
    val report: SymptomReport,
    val from: String? = null,
    val filed: List<FiledLink> = emptyList(),
    val draft: MapDraft? = null,
    val noRoute: Boolean = false,
    val cards: Map<JournalKey, CardMark> = emptyMap(),
)

/**
 * A record card of the bot's answer to a message: the message by its [message] ordinal in its thread
 * ([MessagePlace]), the proposal by its type and the words it was read from — what the model's own
 * output never repeats within one message.
 */
internal data class JournalKey(val message: Int, val type: ChatJournalType, val start: Int, val end: Int)

/**
 * What became of a record card. [Declined]: Cancel, folded to its one line. [Opened]: Add on a record the
 * card does not save itself; its form was opened, and whether it was saved there the chat cannot know.
 * [Added]: saved from the card, as the row [id] of [kind] at [at] — titled [title], [amount] of it (a
 * drink's millilitres, a meal's grams), which the folded card shows as it was saved.
 */
internal sealed interface CardMark {
    data object Declined : CardMark

    data object Opened : CardMark

    data class Added(val kind: String, val id: Long, val at: Long, val title: String, val amount: Double) : CardMark
}

/** [this] log with the card [key] of the thread [thread] (its id) marked [mark]; as it was with no such thread. */
internal fun ChatLog.marked(thread: Int, key: JournalKey, mark: CardMark): ChatLog =
    copy(threads = threads.map { if (it.id == thread) it.copy(cards = it.cards + (key to mark)) else it })

/** What became of [card], if anything yet. */
internal fun ChatLog.markOf(card: BotPiece.Journal): CardMark? =
    threads.firstOrNull { it.id == card.thread }?.cards?.get(card.key)

/**
 * A record a thread filed, named by [kind] and moment [at] — the two things a restore keeps — and
 * told apart from another row on that moment by [wsig], the fingerprint of the row as written
 * ([rowSig], [courseSig]). [sig] is the fingerprint of the thread's answers it was built from
 * ([TopicScript.recordSig]): the record is rewritten only when that changes.
 *
 * [role] is its place in its thread: empty for the main record, [WORDS_ROLE] for the row of a crisis
 * or emergency answer, `"dose:<slot>"` for a new course's mark of today, `"pain"` for a dose's change
 * to this chat's pain. [title] is the title as written, for display only. [start] is a course's first
 * day; [slot] is what tells a dose's record from another its thread could make — for a dose thread
 * its identity ("C<the course's moment>", or "X…:<name>" for a dose outside the schedule; doseIdentity
 * in ChatDose.kt), for a new course's mark of today the slot itself ("540", "WAKE") — and [course]
 * its course's moment. [made] is false when the chat changed a record that was already there, which
 * [prior] then puts back. [err]: building it threw at the last act, and the next one tries again.
 */
data class FiledLink(
    val kind: String,
    val role: String = "",
    val at: Long,
    val title: String,
    val start: String? = null,
    val slot: String? = null,
    val course: Long? = null,
    val made: Boolean = true,
    val prior: String? = null,
    val sig: Int = 0,
    val wsig: Int = 0,
    val state: LinkState = LinkState.FILED,
    val err: Boolean = false,
)

/**
 * Where a link's record stands. [FILED]: there, as the chat wrote it. [UNDONE]: the person's Undo.
 * [GONE]: deleted elsewhere, or no longer to be told from another row on its moment. [REFUSED]: its
 * page's door said no (a mood inside its cooldown, a dose already marked). [CHANGED]: edited on its
 * own page. [SUPERSEDED]: a later thread of this chat changed it.
 */
enum class LinkState { FILED, UNDONE, GONE, REFUSED, CHANGED, SUPERSEDED }

/** The painting on the in-chat map not yet sent, and the area codes added or struck off by hand. */
internal data class MapDraft(val strokes: List<PainStroke>, val added: List<String>, val removed: List<String>)

/**
 * The kind a link names a `medications` row by. Never a journal kind: no row of the journal is ever
 * written with it.
 */
internal const val LINK_COURSE = "COURSE"

/** The state of the reminders a new course's alarms depend on, read in the screen. */
data class ReminderGateState(
    val remindersOn: Boolean,
    val notificationsOn: Boolean,
    val exactAlarms: Boolean,
    val fullScreen: Boolean,
)

/**
 * What the chat knows besides its own words; loaded on open, after every act that filed something,
 * and on ON_START. Equal by value.
 *
 * [recentSymptoms] are KIND_SYMPTOM rows of the week up to the chat's day; [painMonth] KIND_PAIN rows
 * of thirty days; [lastMood] the newest mood inside the cooldown (today's chat only); [courses] every
 * course; [dayJournal] the chat's day, the two days before it and the one after; [recentChats] the chats begun in a
 * medicine's first weeks ([MED_START_WINDOW_DAYS]), which what a course was said to be for is read
 * back over — a line said once a month reads the last thirty days of them; [gates] the reminders'
 * switches, read by the screen.
 */
data class ChatEnv(
    val recentSymptoms: List<JournalEntry> = emptyList(),
    val painMonth: List<JournalEntry> = emptyList(),
    val lastMood: JournalEntry? = null,
    val courses: List<Medication> = emptyList(),
    val dayJournal: List<JournalEntry> = emptyList(),
    val recentChats: List<RecentChat> = emptyList(),
    val gates: ReminderGateState? = null,
)

/**
 * An earlier chat as another one reads it: its [payload] ([encodeChatLog]) and when it was begun.
 * [current]: it is the stored copy of the very chat reading it, which its own threads speak for.
 */
data class RecentChat(val payload: String, val startedAt: Long, val current: Boolean = false) {
    /** The chat [payload] holds, decoded once for every reply and answer that reads it; null when unreadable. */
    internal val log: ChatLog? by lazy { decodeChatLog(payload) }
}

/**
 * Where a thread stands in its chat: what a topic's script may read beyond its own answers.
 *
 * [before] are the topics of the earlier threads, in order. [chestAsked]: a chest pain thread of this
 * sitting closed without an emergency. [chestSpreadAreas]: the area codes ("UPPER_ARM:L") this
 * sitting's "does it spread from the chest?" questions named — each answered "no", since a "yes"
 * stops the chat. [resumed] is the report a `HANDOVER:` or `RESUME:` thread starts from; [links] this
 * thread's; [others] the threads before this one, as a script may read them; [filedMoments] every
 * moment this chat filed, which an "earlier" lookup leaves out; [crisisInChat] whether any thread had
 * a crisis answer other than "no". [day] is the chat's day, what the scripts date by; [today] the real
 * date of the act, what "active now" is read against; [now] its moment (null where nothing set it), what
 * "inside the mood's four hours" is read against. [readLanguage]: the app speaks English or Russian, the
 * languages the chat reads words in, so a lone English key is a word read ([ChatLanguage]). [journal]
 * is [ChatSetting.journal].
 */
data class ThreadContext(
    val index: Int = 0,
    val id: Int = 0,
    val from: String? = null,
    val before: List<Symptom> = emptyList(),
    val chestAsked: Boolean = false,
    val chestSpreadAreas: Set<String> = emptySet(),
    val resumed: SymptomReport? = null,
    val links: List<FiledLink> = emptyList(),
    val others: List<OtherThread> = emptyList(),
    val filedMoments: Set<Long> = emptySet(),
    val crisisInChat: Boolean = false,
    val day: LocalDate? = null,
    val today: LocalDate? = null,
    val env: ChatEnv = ChatEnv(),
    val now: Long? = null,
    val readLanguage: Boolean = false,
    val journal: Set<ChatJournalType> = emptySet(),
)

/**
 * Another thread of this chat, as a script may read it: its topic, its saved answers ([forSave]) and
 * its links. Only ever a thread before the one reading it, so a thread's turns never change when a
 * later one is added.
 */
data class OtherThread(val id: Int, val topic: Symptom?, val report: SymptomReport, val links: List<FiledLink>)

/**
 * The app's language as the chat speaks it. Its words are read in English and Russian always; in
 * [OTHER] an English key alone is no sign a message was read, as it may be a word of that language too
 * ([HOMOGRAPH_KEYS], [wordsRead]).
 */
internal enum class ChatLanguage { EN, RU, OTHER }

/**
 * What the router and the page read besides the log, built once per page. [chatMoment] is now, or
 * for a chat of an earlier day the last moment of that day; threads with an index below
 * [sittingFrom] belong to an earlier sitting. [crisisInChat] is set by the router, wherever it asks a
 * script whether its topic is offered: any thread of the log had a crisis answer other than "no".
 * [resumeKey] is another chat begun today and left before its end, which the opening offers to carry on.
 * [journal] are the record types whose form the chat may offer as a card of its answer to a message
 * ([journalTypesOffered]): none where nothing set them.
 */
internal data class ChatSetting(
    val female: Boolean,
    val adult: Boolean,
    val day: LocalDate,
    val past: Boolean,
    val now: Long,
    val chatMoment: Long,
    val snapshot: SymptomContext?,
    val env: ChatEnv,
    val zone: ZoneId,
    val sittingFrom: Int,
    val language: ChatLanguage,
    val crisisInChat: Boolean = false,
    val resumeKey: String? = null,
    val journal: Set<ChatJournalType> = emptySet(),
)

/**
 * Something words or a follow-up named that a thread could take up: "PAIN:KNEE", "FEVER".
 *
 * [missed]: the words said a dose of the medicine named was not taken, «не выпила нурофен». Without
 * it the chip would say "Took: Nurofen" all the same, the mention carrying no word of it. The key carries it too
 * ("DOSE:нурофен:MISSED", read back by [mentionOfKey]), so a thread begun from the chip words the
 * person's bubble as the chip was worded.
 */
data class TopicMention(val topic: Symptom, val detail: String = "", val missed: Boolean = false) {
    val key: String
        get() = when {
            missed -> "${topic.name}:$detail$MENTION_MISSED"
            detail.isEmpty() -> topic.name
            else -> "${topic.name}:$detail"
        }
}

/** What ends the key of a mention of a dose missed; no detail ends so, a medicine's words being lower case. */
internal const val MENTION_MISSED = ":MISSED"

/** A topic chip: the topic, the mention it takes up if any, and its own label where it has one. */
data class TopicChoice(val topic: Symptom, val mention: TopicMention? = null, val labelRes: Int? = null)

/** A line the scripts build without Resources, rendered where it is drawn. */
data class ChatText(val res: Int, val args: List<PieceArg> = emptyList())

/** One argument of a [ChatText]. */
sealed interface PieceArg {
    data class Text(val s: String) : PieceArg

    data class Res(val id: Int) : PieceArg

    /** "09:00", in the 24 h or 12 h the phone shows. */
    data class Clock(val minuteOfDay: Int) : PieceArg

    /** The time of day of a moment, in the chat's zone. */
    data class Moment(val epochMs: Long) : PieceArg

    data class Day(val iso: String, val pattern: String = "dMMMM") : PieceArg

    data class Plural(val res: Int, val count: Int) : PieceArg

    data class Joined(val parts: List<PieceArg>, val separatorRes: Int) : PieceArg

    /** [parts] one after another as a list is written in the app's lines: "aching, tight". */
    data class Listed(val parts: List<PieceArg>) : PieceArg

    /**
     * Areas of the body by their codes, each named on its side as the pain page names it: "Knee · right, Calf · left".
     */
    data class Areas(val codes: List<String>) : PieceArg
}

/**
 * The prefix of a frozen fact that belongs to the thread, not to one of its steps — the notes its
 * close showed, the reminders' switches read after a course was made. [forSave] keeps such facts;
 * any rewind drops them, and the thread's close works them out again.
 */
internal const val THREAD_META_PREFIX = "THREAD."

/** Where a step's answer is kept: among the options, as a value, or as a painting with its areas. */
internal enum class StepHolds { OPTIONS, VALUE, PAINTING }

/** How a step holds its answer and how it is answered: a tap, or settled by the clock ([AutoTakeClock]). */
internal data class StepKind(val holds: StepHolds, val answer: AnswerKind)

/** The step's kind, from the script that asks it; options taken on a tap, or by the clock when several. */
internal val SymptomStep.kind: StepKind
    get() = STEP_KINDS[this] ?: StepKind(StepHolds.OPTIONS, if (multi) AnswerKind.SETTLE else AnswerKind.TAP)

private val STEP_KINDS: Map<SymptomStep, StepKind> by lazy {
    Symptom.entries.flatMap { it.script.stepKinds.entries }.associate { it.toPair() }
}

/** Whether [step] has an answer to take: a tick, or a value — a painting's answer is its areas. */
internal fun SymptomReport.holdsAnswer(step: SymptomStep): Boolean = when (step.kind.holds) {
    StepHolds.OPTIONS -> !answers[step].isNullOrEmpty()
    StepHolds.VALUE, StepHolds.PAINTING -> !values[step].isNullOrEmpty()
}

/** The topic a thread is about: none until its topic question was taken. */
internal val ChatThread.topic: Symptom? get() = report.asTold().symptom

/** Whether the thread's questions are over: answered to the end, or stopped by an emergency. */
internal val ChatThread.ended: Boolean get() = report.chat?.ended == true

/**
 * [choice] taken up. At the opening, or once the last thread is over, it begins a thread of its own
 * — with the snapshot for a symptom topic, whose card shows it — and on a last thread that has words
 * and no topic yet, that thread takes it. Nothing else answers a topic.
 */
internal fun ChatLog.chose(choice: TopicChoice, setting: ChatSetting): ChatLog {
    if (end != null) return this
    val last = threads.lastOrNull()
    val snapshot = setting.snapshot.takeIf { choice.topic.record == ChatRecord.SYMPTOM }
    return when {
        last != null && !last.ended && last.topic == null -> copy(
            threads = threads.dropLast(1) + last.copy(
                report = last.report.copy(context = last.report.context ?: snapshot).chose(choice.topic),
                from = choice.mention?.key ?: last.from,
            ),
        )

        last == null || last.ended -> copy(
            threads = threads + ChatThread(
                id = next,
                report = SymptomReport(context = snapshot, chat = SymptomChat()).chose(choice.topic),
                from = choice.mention?.key,
            ),
            next = next + 1,
        )

        else -> this
    }
}

/**
 * A message, kept as written up to [SYMPTOM_TEXT_MAX] with what its [read] decided: into the last
 * thread while it is open, and at the opening or once it is over the first turn of a new thread with
 * no topic yet. A blank message is nothing, and so is one after the end.
 */
internal fun ChatLog.wrote(text: String, read: ChatRead? = null): ChatLog {
    val kept = text.trim().take(SYMPTOM_TEXT_MAX)
    if (kept.isEmpty() || end != null) return this
    val last = threads.lastOrNull()
    return if (last != null && !last.ended) {
        updateLast { it.wrote(kept, read) }
    } else {
        copy(
            threads = threads + ChatThread(id = next, report = SymptomReport(chat = SymptomChat()).wrote(kept, read)),
            next = next + 1,
        )
    }
}

/**
 * Where a message stands, for its reading to come back to: the thread by its
 * id — never reused — and the message's [ordinal] among that thread's messages. A rewind keeps every
 * message of the thread it takes back in, in order, so the place holds whatever is taken back while
 * the message is read, and the same words sent again are another message, at another place.
 */
internal data class MessagePlace(val thread: Int, val ordinal: Int)

/** Where the newest message of [this] log stands: the last of the last thread's. */
internal fun ChatLog.lastMessagePlace(): MessagePlace? {
    val thread = threads.lastOrNull() ?: return null
    val count = messagesOf(thread.report.chat).size
    return if (count == 0) null else MessagePlace(thread.id, count - 1)
}

/** How many messages [this] log holds. */
internal fun ChatLog.messageCount(): Int = threads.sumOf { messagesOf(it.report.chat).size }

/**
 * [read] kept with the message at [place] — sent a moment ago, its reading done —
 * while it is still [text] with no reading of its own: nothing else of the log changes. Null
 * when there is no such message, as when a rewind took its thread away.
 */
internal fun ChatLog.readMessage(place: MessagePlace, text: String, read: ChatRead): ChatLog? {
    val index = threads.indexOfFirst { it.id == place.thread }
    val thread = threads.getOrNull(index)
    val turns = thread?.report?.chat?.turns.orEmpty()
    val at = turns.indices.filter { turns[it] is ChatTurn.Wrote }.getOrNull(place.ordinal)
    val message = at?.let { turns[it] as ChatTurn.Wrote }
        ?.takeIf { it.text == text.trim().take(SYMPTOM_TEXT_MAX) && it.read == null }
    if (thread == null || at == null || message == null) return null
    val chat = checkNotNull(thread.report.chat)
    val kept = turns.toMutableList().apply { this[at] = message.copy(read = read) }
    val report = thread.report.copy(chat = chat.copy(turns = kept))
    return copy(threads = threads.toMutableList().apply { this[index] = thread.copy(report = report) })
}

/** "That is all for now". */
internal fun ChatLog.endedByPerson(): ChatLog = copy(end = ChatEnd.ALL)

/** The end taken back: the pencil on "That is all for now", or a rewind after an emergency's stop. */
internal fun ChatLog.unended(): ChatLog = copy(end = null)

/**
 * The end note, trimmed and kept up to [NOTE_MAX_CHARS], the cap of the note field it is typed in; a
 * note changed goes without the reading of the note before it.
 */
internal fun ChatLog.noted(text: String): ChatLog {
    val kept = text.trim().take(NOTE_MAX_CHARS)
    return copy(note = kept, noteRead = noteRead.takeIf { kept == note })
}

/** [read] kept with the end note while it is still [text], the note the model read; else null. */
internal fun ChatLog.noteReadAs(text: String, read: ChatRead): ChatLog? =
    copy(noteRead = read).takeIf { note.isNotEmpty() && note == text.trim().take(NOTE_MAX_CHARS) }

/** [change] on the last thread's report: a tap, ticks, a value, strokes, Send, typed words. */
internal fun ChatLog.updateLast(change: (SymptomReport) -> SymptomReport): ChatLog {
    val last = threads.lastOrNull() ?: return this
    return copy(threads = threads.dropLast(1) + last.copy(report = change(last.report)))
}

/**
 * Thread [threadId] taken back to its answer at [position] ([rewoundTo]); every thread after it goes
 * with it, which is only ever the chest pain thread it handed over to and the pain thread resumed
 * after. Taking back the topic keeps the thread's words and loses its topic; with no words left the
 * thread goes too. The end goes either way. The records the dropped answers filed are the filer's
 * to take back, in the same act ([ChatFiling.persist]).
 */
internal fun ChatLog.rewoundLog(threadId: Int, position: Int): ChatLog {
    val index = threads.indexOfFirst { it.id == threadId }
    val thread = threads.getOrNull(index)
    val turn = thread?.report?.chat?.turns?.getOrNull(position) as? ChatTurn.Asked
    if (thread == null || turn == null) return this
    val rewound = rewoundTo(thread.report, position)
    val wordless = turn.step == SymptomStep.SYMPTOM && rewound.chat?.turns.isNullOrEmpty()
    val kept = threads.take(index) + listOfNotNull(thread.copy(report = rewound).takeUnless { wordless })
    // Words typed as an answer and still read go with the question taken back; a message waiting for its
    // reading keeps its thread from being routed before it, wherever the rewind leaves it.
    return copy(threads = kept, end = null, held = held.takeIf { it is ChatHeld.Message })
}

/** The links a filer's act reported, merged into this log by thread id: nothing else of it changes. */
internal fun ChatLog.withLinks(result: FilingResult): ChatLog =
    copy(threads = threads.map { thread -> result.links[thread.id]?.let { thread.copy(filed = it) } ?: thread })

/**
 * The stored chat taken in place of this one when the database holds a newer copy
 * ([FilingResult.newer]): its threads and links, keeping only what this one's last thread still has
 * waiting — ticks, values, a painting not yet taken — when that thread is the same thread.
 */
internal fun ChatLog.adopting(stored: ChatLog): ChatLog {
    val mine = threads.lastOrNull() ?: return stored
    val theirs = stored.threads.lastOrNull()?.takeIf { it.id == mine.id } ?: return stored
    val asked = theirs.report.chat?.askedSteps().orEmpty()
    val waiting = mine.report
    val report = theirs.report.copy(
        answers = theirs.report.answers + waiting.answers.filterKeys { it !in asked },
        values = theirs.report.values + waiting.values.filterKeys { it !in asked },
        strokes = if (asked.any { it.kind.holds == StepHolds.PAINTING }) theirs.report.strokes else waiting.strokes,
    )
    return stored.copy(threads = stored.threads.dropLast(1) + theirs.copy(report = report, draft = mine.draft))
}
