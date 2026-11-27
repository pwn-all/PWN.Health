package com.pwnall.health

import android.content.res.Resources
import kotlinx.coroutines.runBlocking

// A mood chat driven the way the screen drives it: every change committed through the router and
// filed by the real filer over the memory database, the chat's knowledge of the last mood loaded
// again after every act — as the page does — and every string its resource's name, so a test can say
// which words arrived.

/** Every string resource by its name: "mood_title", "energy_wound_up". */
internal val STRING_NAMES: Map<Int, String> by lazy {
    Class.forName("com.pwnall.health.R\$string").fields.associate { it.getInt(null) to it.name }
}

internal fun stringName(id: Int): String = STRING_NAMES.getValue(id)

/** Just enough of Resources for a card's line and a flash: each string its name, the arguments after it. */
@Suppress("DEPRECATION") // The one constructor a JVM test can reach; nothing reads what it is given.
internal class NamedStrings : Resources(null, null, null) {
    override fun getString(id: Int): String = stringName(id)

    override fun getString(id: Int, vararg formatArgs: Any?): String =
        stringName(id) + formatArgs.joinToString("|", "[", "]")
}

/** A mood row as some page wrote it, at [at]: a face, a tension, the page's words. */
internal fun pageMood(at: Long, score: Int, energy: Int? = null) = JournalEntry(
    day = CHAT_TODAY.toString(),
    occurredAt = at,
    kind = KIND_MOOD,
    title = "mood_title",
    calories = 0.0,
    moodScore = score,
    energyScore = energy,
    note = "page words",
)

/** A chat whose database already holds a mood [ago] milliseconds old, loaded as the page loads it. */
internal fun withMood(ago: Long, score: Int, energy: Int? = null): Pair<MoodChat, JournalEntry> {
    val store = MemoryChatStore()
    val mood = store.db.put(pageMood(CHAT_NOW - ago, score, energy))
    return MoodChat(store).apply { reload() } to mood
}

/**
 * One chat over [store], today unless [past]. [env] is what the page loads with the chat; the last
 * mood in it is read again from the database after every act, as the page does.
 */
internal class MoodChat(val store: MemoryChatStore = MemoryChatStore(), private val past: Boolean = false) {
    val key = chatKey()
    val filing = ChatFiling(store, key)
    var log = ChatLog(past = past)
    var now = CHAT_NOW
    var env = ChatEnv()
    val firsts = mutableListOf<Pair<Int, FiledLink>>()

    fun setting(): ChatSetting = chatSetting(past = past, env = env).copy(now = now, chatMoment = now)

    fun context(of: ChatLog = log) = FilingContext(
        FilingClock(now, CHAT_TODAY, CHAT_ZONE),
        setting().day,
        past,
        key,
        of,
        env,
        ::stringName,
    )

    /** [next] committed: settled by the router, filed, its links taken, the last mood read again. */
    fun commit(next: ChatLog) {
        log = settledLog(next, setting())
        val result = checkNotNull(runBlocking { filing.persist(log, context()) })
        firsts += result.firsts
        log = result.newer?.let { log.adopting(it) } ?: log.withLinks(result)
        reload()
    }

    /** The last mood as the page loads it: the newest inside the four hours, none for an earlier day. */
    fun reload() {
        val last = if (past) null else runBlocking { store.db.moodNear(now) }
        env = env.copy(lastMood = last)
    }

    fun turn(): BotTurn = chatTurn(log, setting())

    fun move(): ChatMove = chatMove(log, setting())

    fun state(): ChatState = log.threadState(log.threads.lastIndex, setting())

    fun asked(): SymptomStep? = (move() as? ChatMove.Ask)?.step

    fun write(text: String) = commit(log.wrote(text))

    fun choose(topic: Symptom) = commit(log.choseTopic(TopicChoice(topic), setting()))

    fun choose(choice: TopicChoice) = commit(log.choseTopic(choice, setting()))

    fun tap(step: SymptomStep, option: SymptomOption) =
        commit(log.answeredLast(setting()) { it.tapped(step, option, null) })

    /** Several ticks, taken by the clock. */
    fun tick(step: SymptomStep, vararg options: SymptomOption) {
        log = log.updateLast { it.copy(answers = it.answers + (step to options.toList())) }
        commit(log.takenLast(step, setting()))
    }

    /** The dial moved to [score] and taken by the clock three seconds later. */
    fun dial(score: Int) {
        log = log.updateLast { it.valued(SymptomStep.MOOD_DIAL, score.toString()) }
        commit(log.takenLast(SymptomStep.MOOD_DIAL, setting()))
    }

    fun type(step: SymptomStep, text: String) = commit(log.typedLast(step, text, setting()))

    /** A suggestion chip of a typed question tapped: the value taken at once. */
    fun suggest(step: SymptomStep, value: String) =
        commit(log.answeredLast(setting()) { it.valued(step, value).answered(step) })

    fun rate(severity: SymptomSeverity) = commit(log.answeredLast(setting()) { it.rated(severity) })

    fun undo(thread: Int, role: String = "") {
        val result = checkNotNull(runBlocking { filing.undo(thread, role, context()) })
        log = log.withLinks(result)
        reload()
    }

    /** Save again on the card of [thread]'s record [role]. */
    fun saveAgain(thread: Int, role: String = "") {
        val result = checkNotNull(runBlocking { filing.saveAgain(thread, role, context()) })
        log = log.withLinks(result)
        reload()
    }

    /** The pencil on the last thread's answer to [step]. */
    fun rewind(step: SymptomStep) {
        val thread = log.threads.last()
        val position = checkNotNull(thread.report.chat).turns.indexOfFirst { it is ChatTurn.Asked && it.step == step }
        commit(log.rewoundLog(thread.id, position))
    }

    /**
     * The last thread answered to its end: [answers] where given, else calmly ([calm]), how bad as
     * [severity]. A thread of chips only: the dial is the test's own to move.
     */
    fun talkThrough(
        answers: Map<SymptomStep, List<SymptomOption>> = emptyMap(),
        severity: SymptomSeverity = SymptomSeverity.MILD,
    ) {
        repeat(TALK_LIMIT) {
            val step = asked() ?: return
            when (val reply = turn().reply) {
                ChatReply.Severity -> rate(severity)

                is ChatReply.Choose -> {
                    val options = answers[step] ?: calm(reply.options)
                    if (step.multi) tick(step, *options.toTypedArray()) else tap(step, options.single())
                }

                else -> error("$step waits for $reply")
            }
        }
        error("the thread did not end within $TALK_LIMIT answers")
    }

    fun thread(index: Int = log.threads.lastIndex): ChatThread = log.threads[index]

    fun link(thread: Int = log.threads.last().id, role: String = ""): FiledLink? =
        log.threads.first { it.id == thread }.filed.firstOrNull { it.role == role }

    fun moods(): List<JournalEntry> = store.db.journal.values.filter { it.kind == KIND_MOOD }
}

private const val TALK_LIMIT = 30

internal fun BotTurn.notes(): List<Int> = pieces.notes()

internal fun List<BotPiece>.notes(): List<Int> = filterIsInstance<BotPiece.Note>().map { it.text.res }

internal fun BotTurn.saved(): List<BotPiece.Saved> = pieces.filterIsInstance<BotPiece.Saved>()

internal fun BotTurn.ask(): BotPiece.Ask = pieces.filterIsInstance<BotPiece.Ask>().single()

/** The close of the thread at [index]: its last answer's line and its topic's close. */
internal fun MoodChat.close(index: Int = log.threads.lastIndex): List<BotPiece> = closeOf(log, index, setting())
