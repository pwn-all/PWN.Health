package com.pwnall.health

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

// The model's reads of words sent to the chat that are still on their way,
// for the whole process. A read outlives the page that started it — a rotation, a fold, a change of
// theme recreate the page, not the chat — and the page that shows its chat when it is done keeps it,
// once, in the order the words were sent. A page gone for good keeps what its reads come to only
// while no page of the chat stands — one brought back from its saved state takes them itself — and
// keeps them in the chat as the database holds it, never in its own copy ([ChatFiling.update]). While
// a read the turn waits for is on its way, every page of that chat shows the dots; a read past its
// second and a margin is no read, whatever the run it waits on still does.

/** Words sent to the chat for its model to read, and where the reading goes. */
internal sealed interface SentWords {
    val text: String

    /** A message, kept as it was sent, at [place]: its reading is kept with it. */
    data class Message(override val text: String, val place: MessagePlace) : SentWords

    /**
     * Words typed as the answer to [step], taken only once read: a flag of the reading may make them a
     * message instead ([typedLast]). Shown as the person's bubble while they are read.
     */
    data class Typed(override val text: String, val step: SymptomStep) : SentWords

    /** The note after the end, kept as it was taken: its reading is kept with it while it is that note. */
    data class Note(override val text: String) : SentWords
}

/**
 * [this] log once the read of [sent] is done, with [read] — or with none, a read that failed, timed
 * out or had no model to read with — as it is committed: a message gets its reading where it still
 * stands ([readMessage]) and, if the bot's turn waited for it, lets its thread be routed; words typed
 * as an answer are taken, as the answer or as a message ([typedLast]), only while they are the words
 * the chat holds for the question still asked — a rewind, or another page that took them, leaves
 * nothing to take; the note gets its reading while it is still that note ([noteReadAs]). Null when
 * nothing of the log changes.
 */
internal fun ChatLog.readDone(sent: SentWords, read: ChatRead?, setting: ChatSetting): ChatLog? = when (sent) {
    is SentWords.Message -> {
        val freed = if (held == ChatHeld.Message(sent.place)) copy(held = null) else this
        (read?.let { freed.readMessage(sent.place, sent.text, it) } ?: freed).takeIf { it != this }
    }

    is SentWords.Typed -> {
        val asked = (chatMove(this, setting) as? ChatMove.Ask)?.step
        if (held != ChatHeld.Typed(sent.step, sent.text) || asked != sent.step) {
            null
        } else {
            copy(held = null).typedLast(sent.step, sent.text, setting, read)
        }
    }

    is SentWords.Note -> read?.let { noteReadAs(sent.text, it) }
}

/**
 * [this] log with what it [held] for a reading let go as the rules read it — the reading will not come:
 * the process that read it died, or no page stands to take it — or null with nothing held: a message
 * is routed, words typed are taken as typed, while their question is still the one asked.
 */
internal fun ChatLog.released(setting: ChatSetting): ChatLog? = when (val waiting = held) {
    null -> null
    is ChatHeld.Message -> copy(held = null)
    is ChatHeld.Typed -> readDone(SentWords.Typed(waiting.text, waiting.step), null, setting) ?: copy(held = null)
}

/** Whether [read] is the one [this] log holds for: the message or the words typed it waits on. */
internal fun ChatLog.holdsFor(read: PendingRead): Boolean = when (val sent = read.sent) {
    is SentWords.Message -> held == ChatHeld.Message(sent.place)
    is SentWords.Typed -> held == ChatHeld.Typed(sent.step, sent.text)
    is SentWords.Note -> false
}

/**
 * A read on its way: what was [sent]; whether the bot's turn [holds] for it — the dots standing from
 * [since], when the words were sent — or was put up at once, as for words the rules find urgent or a
 * model read in the shadow, the reading joining the chat once done; and what it comes to.
 */
internal class PendingRead(
    val id: String,
    val sent: SentWords,
    val holds: Boolean,
    val since: Long,
    val result: Deferred<ChatRead?>,
)

/** The reads on their way, by the key of their chat, running in [scope], which no page owns. */
internal class ChatReadQueue(private val scope: CoroutineScope) {
    private val reads = MutableStateFlow<Map<String, List<PendingRead>>>(emptyMap())

    /** How many pages of each chat stand now: a page gone for good takes nothing while one does. */
    private val pages = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Every chat's reads on their way, oldest first: what a page shows the dots by and keeps. */
    val pending: StateFlow<Map<String, List<PendingRead>>> = reads.asStateFlow()

    /** A page of the chat [chat] stands. */
    fun opened(chat: String) {
        pages.update { all -> all + (chat to (all[chat] ?: 0) + 1) }
    }

    /** A page of the chat [chat] has gone, for a rotation or for good. */
    fun closed(chat: String) {
        pages.update { all ->
            val left = (all[chat] ?: 0) - 1
            if (left > 0) all + (chat to left) else all - chat
        }
    }

    /**
     * What [read] comes to: its reading, or null — nothing read — once its second and [DEADLINE_SLACK_MILLIS]
     * since [PendingRead.since] are up at [now], even when the run it waits on never ends (a stuck native
     * call, which no cancellation reaches): the turn then is the rules', and the read is let go as one
     * that failed. A read done already is what it came to, however late it is asked.
     */
    suspend fun resultOf(read: PendingRead, now: Long = System.currentTimeMillis()): ChatRead? {
        val left = read.since + ChatModelReader.READ_LIMIT_MILLIS + DEADLINE_SLACK_MILLIS - now
        return withTimeoutOrNull(left.coerceAtLeast(1)) { read.result.await() }
    }

    /**
     * [read] of the chat [chat] taken by a page gone for good, once no page of the chat stands: true for
     * the taker, who keeps it in the chat as stored; false once a page that stands took it instead.
     */
    suspend fun takeAlone(chat: String, read: PendingRead): Boolean {
        while (true) {
            combine(reads, pages) { all, open -> read !in all[chat].orEmpty() || open[chat] == null }.first { it }
            if (read !in reads.value[chat].orEmpty()) return false
            if (pages.value[chat] == null && take(chat, read)) return true
        }
    }

    /**
     * [read] of [sent] for the chat [chat], started now in the queue's own scope: a failure inside it
     * is a read that came to nothing, never a crash — a cancellation thrown out of [read] too, such as
     * a timeout of its own. The queue's own cancellation still ends the read cancelled: a cancelled job
     * ends so whatever its block returns.
     */
    fun start(chat: String, sent: SentWords, holds: Boolean, since: Long, read: suspend () -> ChatRead?): PendingRead {
        val result = scope.async { runCatching { read() }.getOrNull() }
        val pending = PendingRead(UUID.randomUUID().toString(), sent, holds, since, result)
        reads.update { all -> all + (chat to (all[chat].orEmpty() + pending)) }
        return pending
    }

    /** [read] taken off the chat [chat]'s list: true for the one caller that takes it, who keeps it. */
    fun take(chat: String, read: PendingRead): Boolean {
        var taken = false
        reads.update { all ->
            val left = all[chat].orEmpty()
            taken = read in left
            when {
                !taken -> all
                left.size == 1 -> all - chat
                else -> all + (chat to (left - read))
            }
        }
        return taken
    }

    companion object {
        /** The process's queue, whose reads outlive every page. */
        val process: ChatReadQueue by lazy { ChatReadQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

        /** How long past its second a read is waited for at most: the reader stops its own run by then. */
        const val DEADLINE_SLACK_MILLIS = 1_000L
    }
}
