package com.pwnall.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

// The one writer of every record a chat files. Each write reconciles what every thread wants filed
// now with the links the database holds, in one transaction with the chat itself ([chatAct]); the
// database is the truth about records, and nothing in the composition decides insert or update.
// Records are named by kind and moment, never by id, so a restore that renumbers every row leaves
// every link valid; a record changed or deleted on its own page is never written over or back.

/** What a thread wants of one of its records now. */
internal sealed interface RecordWant {
    /** Filed, as [draft] says: inserted if it is not there yet, rewritten when the thread's answers for it changed. */
    data class File(val draft: RecordDraft) : RecordWant

    /** An answer it was built from is being changed: what is filed stays exactly as it stands. */
    data object Keep : RecordWant
}

/** How a record is built. */
internal sealed interface RecordDraft {
    /**
     * A journal row through [door]. [wanted] is the moment it should stand at — null for now, or for
     * a chat of an earlier day that day's midday. [build] gets the shell (id 0, or the row's own; its
     * day and moment) and the ids of the thread's records already written this act, by role, and
     * returns the whole row. [slot] and [course] are a dose's, for its card.
     *
     * [build] returns null where there is nothing to write on purpose — the mark of a course that is
     * not written — and nothing is said of it. One that throws has failed, and the act says so
     * ([FilingResult.failed]): the two are never one silence.
     */
    class Journal(
        val door: JournalDoor,
        val wanted: Long?,
        val slot: String? = null,
        val course: Long? = null,
        val build: (shell: JournalEntry, ids: (role: String) -> Long?) -> JournalEntry?,
    ) : RecordDraft

    /** A course; [build] gets the moment it is written down, its `createdAt`. */
    class Course(val build: (createdAt: Long) -> Medication) : RecordDraft

    /**
     * A change to a row of [kind] already standing at [at] — this chat's own record, found by its
     * link's fingerprint, or another page's. [change] is applied under that row's id, day and moment;
     * [prior] keeps what puts it back, by default a snapshot of every column a page edits ([rowPrior]).
     * A link is taken back with no draft at hand, so there is one putting back for every draft
     * ([restoredRow]) and a draft gives none of its own: a prior of one's own is written under
     * [rowPrior]'s keys, and the payload, the minutes, the mood or the energy it leaves out is put back
     * as none — to be left out only where the row never has one.
     */
    class Update(
        val kind: String,
        val at: Long,
        val change: (JournalEntry) -> JournalEntry,
        val prior: (JournalEntry) -> String = ::rowPrior,
    ) : RecordDraft
}

/** The door a journal row goes through, with the kind it writes. */
internal enum class JournalDoor(val kind: String) {
    PLAIN(KIND_SYMPTOM),
    PAIN(KIND_PAIN),
    MOOD(KIND_MOOD),
    DOSE(KIND_MED),
}

/**
 * What a write knows besides the log: the clock, the chat's day and whether it was opened for an
 * earlier one, its key, the log, what was loaded with it, and the strings of the language showing —
 * titles are written in it, and are never part of a fingerprint.
 */
internal data class FilingContext(
    val clock: FilingClock,
    val chatDay: LocalDate,
    val past: Boolean,
    val chatKey: String,
    val log: ChatLog,
    val env: ChatEnv,
    val strings: (Int) -> String,
)

internal data class FilingClock(val now: Long, val today: LocalDate, val zone: ZoneId)

/**
 * What one act did: every thread's links as the database now holds them, the records filed for the
 * first time (for the flash), the writes a door refused, and — when the database held a newer copy
 * of the chat than the one handed in — that copy, for the page to adopt ([ChatLog.adopting]).
 * [failed]: the act threw and a second one kept the chat alone ([ChatFiling.persist]), or a record it
 * was to file for the first time could not be built, which leaves no link to mark: the records it was
 * to file are not filed, and the page says so.
 */
internal data class FilingResult(
    val links: Map<Int, List<FiledLink>>,
    val firsts: List<Pair<Int, FiledLink>>,
    val refused: List<Refusal>,
    val newer: ChatLog? = null,
    val failed: Boolean = false,
)

/** A first write a door refused: what stood in the way, when the door says ([DoorResult.Refused]). */
internal data class Refusal(val thread: Int, val role: String, val blockingAt: Long?, val blockingScore: Int?)

/** Where a chat's acts run: one transaction each, [chatAct] on the phone, a fake in the tests. */
internal interface ChatStore {
    suspend fun <T> act(block: suspend (ChatDoors) -> T): T
}

/**
 * One lock per chat, for the whole process: the composition a rotation left behind and the new one
 * take turns on the same chat, so two acts never both find no record yet and insert two. A chat
 * deleted is remembered, so a write that was waiting behind the deletion writes nothing — until a
 * backup written before the deletion brings its row back ([restored]): then it is written again.
 */
internal object ChatLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    // A chat deleted: false while its deletion waits for the lock, true once its row is gone.
    private val gone = ConcurrentHashMap<String, Boolean>()

    fun of(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    fun markDeleted(key: String) {
        gone[key] = false
    }

    /** The deleted chat's row is gone from the database now. */
    fun markRowGone(key: String) {
        gone[key] = true
    }

    fun deleted(key: String): Boolean = gone.containsKey(key)

    /** Whether the deleted chat's row is gone already, not only about to be. */
    fun rowGone(key: String): Boolean = gone[key] == true

    /** The deleted chat's row is back, from a backup: writes to it go ahead again. */
    fun restored(key: String) {
        gone.remove(key)
    }
}

/**
 * What each thread wants filed and how its answers are fingerprinted: the topics' scripts
 * ([ScriptPlans]), or a test's.
 */
internal interface FilingPlans {
    fun wants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant>

    fun recordSig(thread: ChatThread, role: String, ctx: FilingContext): Int
}

/**
 * The scripts' own wants, and the filer's one role of its own: the row a crisis or an emergency
 * answer is kept in when the thread it was asked in files no symptom row to hold it ([WORDS_ROLE]).
 */
internal object ScriptPlans : FilingPlans {
    override fun wants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> {
        val topic = thread.topic
        val own = topic?.script?.wants(thread, ctx).orEmpty()
        // A symptom thread's own row holds its words' answers with the rest.
        val answers = if (topic?.record == ChatRecord.SYMPTOM) {
            emptyMap()
        } else {
            wordAnswers(thread, ctx.log, ctx.env.courses)
        }
        if (answers.isEmpty()) return own
        val draft = RecordDraft.Journal(JournalDoor.PLAIN, wanted = null) { shell, _ ->
            symptomEntry(
                title = chatTitle(SymptomReport(chat = thread.report.chat)) { ctx.strings(it.labelRes) },
                report = wordsReport(thread, answers),
                day = LocalDate.parse(shell.day),
                today = ctx.clock.today,
                now = shell.occurredAt,
                previous = shell,
                chatKey = ctx.chatKey,
            )
        }
        return own + (WORDS_ROLE to RecordWant.File(draft))
    }

    override fun recordSig(thread: ChatThread, role: String, ctx: FilingContext): Int = if (role == WORDS_ROLE) {
        encodeSymptomPayload(wordsReport(thread, wordAnswers(thread, ctx.log, ctx.env.courses))).hashCode()
    } else {
        thread.topic?.script?.recordSig(thread, role) ?: answersSig(thread)
    }
}

/**
 * The answers [thread]'s own words asked for — the question about safety, an emergency's, the
 * medicine's "more than it says?", read with the person's [courses] as the thread asked them
 * ([wordSteps]) — that no later thread was seeded from: once a chest pain thread carries the chest
 * answer in, its own symptom row keeps it, and the words row lets it go.
 */
internal fun wordAnswers(
    thread: ChatThread,
    log: ChatLog,
    courses: List<Medication>,
): Map<SymptomStep, List<SymptomOption>> {
    val report = thread.report.forSave()
    val chat = report.chat ?: return emptyMap()
    val brought = wordSteps(chat, report, courses).toSet()
    val seeded = log.threads.dropWhile { it.id != thread.id }.drop(1).flatMapTo(HashSet()) { later ->
        later.report.chat?.turns.orEmpty().filterIsInstance<ChatTurn.Asked>().filter { it.routed }.map { it.step }
    }
    return report.answers.filterKeys { it in brought && it !in seeded }
}

/** The words row's report: those answers and nothing else, sorted as a chat with no topic. */
private fun wordsReport(thread: ChatThread, answers: Map<SymptomStep, List<SymptomOption>>): SymptomReport =
    SymptomReport(
        answers = answers,
        symptomTag = classifyThread(SymptomReport(answers = answers, chat = thread.report.chat), null, TagFacts()),
    )

/**
 * Files a chat's records and keeps the chat, [key], in its own table: every act one transaction,
 * behind the chat's lock ([ChatLocks]). Null from every call once the chat was deleted.
 */
internal class ChatFiling(
    private val store: ChatStore,
    private val key: String,
    private val plans: FilingPlans = ScriptPlans,
) {
    /**
     * The chat as [log] has it, every record reconciled with it. When the act fails — a database
     * error — a second act writes the chat alone with the links the database holds, so the person's
     * words are never lost; the next commit files the records.
     */
    suspend fun persist(log: ChatLog, ctx: FilingContext): FilingResult? = ChatLocks.of(key).withLock {
        if (gone()) return@withLock null
        val outcome = runCatching { store.act { doors -> FilingAct(doors, key, ctx, plans).persist(log) } }
        outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        outcome.getOrNull()
            ?: store.act { doors -> FilingAct(doors, key, ctx, plans).transcriptOnly(log) }.copy(failed = true)
    }

    /**
     * [change] made to the chat as the database holds it, and that kept, every record reconciled with
     * it ([ctx] for the chat it comes to) — in one act, behind the chat's lock: what a page gone for good
     * keeps of what comes back for it after it went, a reading of words it sent, never in its own copy
     * of the chat, which a page brought back may have moved on from. Null when there is no chat, the
     * change changes nothing, or the chat was deleted.
     */
    suspend fun update(change: (ChatLog) -> ChatLog?, ctx: (ChatLog) -> FilingContext): FilingResult? =
        ChatLocks.of(key).withLock {
            if (gone()) return@withLock null
            store.act { doors ->
                val stored = doors.port.chat(key)?.payload?.let(::decodeChatLog)
                stored?.let(change)?.let { next -> FilingAct(doors, key, ctx(next), plans).persist(next) }
            }
        }

    /** The person's Undo on a record's card: deleted, or put back as it was; the thread stays as it is. */
    suspend fun undo(thread: Int, role: String, ctx: FilingContext): FilingResult? = act(ctx) { it.undo(thread, role) }

    /** Save again on a record's card: the row a restore put back is taken as it is, else filed anew. */
    suspend fun saveAgain(thread: Int, role: String, ctx: FilingContext): FilingResult? =
        act(ctx) { it.saveAgain(thread, role) }

    /**
     * The chat opened where nothing can change it any more: every record's [FiledLink.prior] goes, so
     * an earlier state of a mood the chat updated is not kept once nothing can put it back.
     */
    suspend fun seal() {
        ChatLocks.of(key).withLock {
            if (gone()) return@withLock
            store.act { doors -> sealIn(doors) }
        }
    }

    /**
     * A record card's [row] saved from the card, at the first moment of its kind free from the one it
     * stands at ([ChatDoors.freeMoment]), and the chat [marked] for it with the row's id and moment — one
     * act, behind the chat's lock. Three steps apart, a row whose mark then failed would stand in the
     * journal with its card still offering Add, and the tap that tried again would save it twice.
     * When the database holds a newer chat than the one [marked] gives, the act is rolled back, the
     * row with it, and the newer chat handed over ([FilingResult.newer]). Null once the chat was
     * deleted.
     */
    suspend fun addCardRow(
        row: JournalEntry,
        backDated: Boolean,
        marked: (id: Long, at: Long) -> ChatLog,
        ctx: (ChatLog) -> FilingContext,
    ): FilingResult? = ChatLocks.of(key).withLock {
        if (gone()) return@withLock null
        try {
            store.act { doors ->
                val at = doors.freeMoment(row.kind, row.occurredAt, backDated)
                val log = marked(doors.writeRow(row.copy(occurredAt = at)), at)
                FilingAct(doors, key, ctx(log), plans).persist(log).also { result ->
                    if (result.newer != null) throw StaleCard(result)
                }
            }
        } catch (stale: StaleCard) {
            stale.result
        }
    }

    /** The chat goes; every record it filed stays, each with its own delete. Later writes write nothing. */
    suspend fun deleteChat() {
        ChatLocks.markDeleted(key)
        ChatLocks.of(key).withLock {
            // A deletion that failed leaves the chat as it was: marked deleted still, every later write
            // of it would have written nothing without a word.
            runCatching { store.act { it.port.deleteChat(key) } }
                .onFailure { ChatLocks.restored(key) }
                .getOrThrow()
            ChatLocks.markRowGone(key)
        }
    }

    private suspend fun act(ctx: FilingContext, block: suspend (FilingAct) -> FilingResult?): FilingResult? =
        ChatLocks.of(key).withLock {
            if (gone()) null else store.act { doors -> block(FilingAct(doors, key, ctx, plans)) }
        }

    /**
     * Whether the chat is deleted, as far as a write goes: while its deletion waits, and after it —
     * unless its row is back, a backup written before the deletion having been imported since.
     */
    private suspend fun gone(): Boolean = when {
        !ChatLocks.deleted(key) -> false

        !ChatLocks.rowGone(key) -> true

        store.act { it.port.chat(key) } == null -> true

        else -> {
            ChatLocks.restored(key)
            false
        }
    }

    private suspend fun sealIn(doors: ChatDoors) {
        val row = doors.port.chat(key) ?: return
        val stored = decodeChatLog(row.payload) ?: return
        if (stored.threads.all { thread -> thread.filed.all { it.prior == null } }) return
        val sealed = stored.copy(
            threads = stored.threads.map { thread -> thread.copy(filed = thread.filed.map { it.copy(prior = null) }) },
        )
        doors.port.saveChat(row.copy(updatedAt = row.updatedAt + 1, payload = encodeChatLog(sealed, pending = false)))
    }
}

/**
 * Whether the card of [thread]'s record [role] offers Save again: only while the thread still wants
 * that record filed. One that wants it kept as it stands — "Leave it as it is" after a refusal — shows
 * its line alone, since the same door would only refuse it again.
 */
internal fun canSaveAgain(plans: FilingPlans, thread: ChatThread, role: String, ctx: FilingContext): Boolean =
    plans.wants(thread, ctx)[role] is RecordWant.File

/** How far a record may stand from the moment wanted and still be at it: the steps of [ChatDoors.freeMoment]. */
private const val MOMENT_SLACK_MS = 999L

/** A card's act that found the chat newer in the database: thrown to roll the row back ([ChatFiling.addCardRow]). */
private class StaleCard(val result: FilingResult) : RuntimeException()

/**
 * One act of the filer: its links by thread id as the database holds them, changed as it goes, the
 * ids written so far for later roles' builders, and what it reports.
 */
private class FilingAct(
    private val doors: ChatDoors,
    private val key: String,
    private val ctx: FilingContext,
    private val plans: FilingPlans,
) {
    private val links = LinkedHashMap<Int, MutableList<FiledLink>>()
    private val firsts = mutableListOf<Pair<Int, FiledLink>>()
    private val refused = mutableListOf<Refusal>()
    private val ids = HashMap<Pair<Int, String>, Long>()
    private var actCtx = ctx

    // A record of this act that could not be built at a first write ([unbuilt]).
    private var failed = false

    suspend fun persist(log: ChatLog): FilingResult {
        val row = doors.port.chat(key)
        val stored = row?.payload?.let(::decodeChatLog)
        if (stored != null && stale(stored, log)) return newer(stored)
        val storedById = stored?.threads?.associateBy { it.id }.orEmpty()
        val resumed = resumedWithRecord(log.threads.map { it.copy(filed = storedById[it.id]?.filed ?: it.filed) })
            .map(::tagged)
        resumed.forEach { links[it.id] = it.filed.toMutableList() }
        // What a rewind took away goes before anything is written, as a thread's own unwanted roles do
        // ([reconcile]): no door is to read a record this act takes back as standing in the way. A change
        // it made, put back, files again the link it superseded, and each thread is filed with that.
        takeBackRemoved(stored?.threads.orEmpty().filter { it.id !in links })
        val live = resumed.map { it.copy(filed = links.getValue(it.id).toList()) }
        val linked = log.copy(threads = live)
        actCtx = ctx.copy(log = linked)
        // The thread the stored chat ended on is rebuilt too: the router adds the thread it starts itself
        // in the same commit as the answer that ends the one before (the pain taken up after the chest's
        // questions), and that answer is filed with it.
        val rebuilt = setOfNotNull(live.lastOrNull()?.id, stored?.threads?.lastOrNull()?.id)
        live.forEach { fileThread(it, plans.wants(it, actCtx), last = it.id in rebuilt) }
        val final = linked.copy(threads = live.map { it.copy(filed = links.getValue(it.id).toList()) })
        if (final.threads.isEmpty() && final.attachments.isEmpty()) {
            doors.port.deleteChat(key)
        } else {
            doors.port.saveChat(chatRow(row, final))
        }
        return result()
    }

    /**
     * One thread of the log handed in. Rebuilt — the reconcile table over every role — when it is
     * the last thread or the one the stored chat ended on, when a record of it failed to build at the
     * last act, or when a role it wants has no link yet (a write a killed process never committed).
     * An earlier thread's records are otherwise only checked, never rebuilt: only the row of its words
     * goes, once a later thread of the sitting carries the answers it kept ([wordAnswers]).
     */
    private suspend fun fileThread(thread: ChatThread, wanted: Map<String, RecordWant>, last: Boolean) {
        val own = links.getValue(thread.id)
        val rebuilt = last || own.any { it.err } || wanted.keys.any { role -> own.none { it.role == role } }
        if (rebuilt) {
            reconcile(thread, wanted)
            return
        }
        own.filter { it.role == WORDS_ROLE && WORDS_ROLE !in wanted }.forEach { link ->
            takeBack(link)
            own.remove(link)
        }
        check(thread.id)
    }

    /**
     * Threads a rewind took away: their records go back, the newest first, so a change a later
     * thread made to an earlier one's record is undone before that record is taken back itself.
     */
    private suspend fun takeBackRemoved(removed: List<ChatThread>) {
        removed.forEach { links[it.id] = it.filed.toMutableList() }
        removed.reversed().forEach { thread ->
            links.getValue(thread.id).toList().reversed().forEach { takeBack(it) }
        }
        removed.forEach { links.remove(it.id) }
    }

    /**
     * The chat alone, with the links the database holds: what a failed act still keeps. Not while a
     * thread a rewind took away still holds a record the failed act did not take back: the stored chat
     * then stays as it was, so the next act still knows the record is there to take back. The threads
     * the failed act was to rebuild keep their records marked as not built ([FiledLink.err]), so the
     * next act rebuilds them though a thread came after them.
     */
    suspend fun transcriptOnly(log: ChatLog): FilingResult {
        val row = doors.port.chat(key)
        val stored = row?.payload?.let(::decodeChatLog)
        if (stored != null && stale(stored, log)) return newer(stored)
        val storedById = stored?.threads?.associateBy { it.id }.orEmpty()
        val due = setOfNotNull(log.threads.lastOrNull()?.id, stored?.threads?.lastOrNull()?.id)
        val linked = log.copy(
            threads = log.threads.map { thread ->
                val filed = storedById[thread.id]?.filed ?: thread.filed
                thread.copy(filed = if (thread.id in due) filed.map(::notBuilt) else filed)
            },
        )
        val kept = log.threads.mapTo(HashSet()) { it.id }
        val orphaned = stored?.threads.orEmpty().any { old ->
            old.id !in kept && old.filed.any { it.state == LinkState.FILED }
        }
        if (linked.threads.isNotEmpty() && !orphaned) doors.port.saveChat(chatRow(row, linked))
        return FilingResult(linked.linkMap(), emptyList(), emptyList())
    }

    /** The moments this chat's links of [kind] name: never given to another record ([ChatDoors.freeMoment]). */
    private fun namedMoments(kind: String): Set<Long> =
        links.values.flatten().filter { it.kind == kind }.mapTo(HashSet()) { it.at }

    /** [link] as a record the failed act did not build: a filed one is rebuilt by the next act. */
    private fun notBuilt(link: FiledLink): FiledLink = link.copy(err = link.err || link.state == LinkState.FILED)

    /**
     * [threads] with the pain taken up again after the chest's own questions (`RESUME:<id>`) holding
     * the record its handing-over thread filed before the chest was marked — the area answered again
     * with the chest in it: one entry, rewritten under its id and moment once the pain is
     * told to its end, never a second one beside it.
     */
    private fun resumedWithRecord(threads: List<ChatThread>): List<ChatThread> {
        var out = threads
        threads.forEach { thread ->
            val source = thread.from?.takeIf { it.startsWith(ROUTE_RESUME) }?.removePrefix(ROUTE_RESUME)?.toIntOrNull()
            val own = out.first { it.id == thread.id }
            val link = out.firstOrNull { it.id == source }?.filed?.firstOrNull { it.role.isEmpty() }
            if (link == null || own.filed.any { it.role.isEmpty() }) return@forEach
            out = out.map {
                when (it.id) {
                    source -> it.copy(filed = it.filed - link)
                    thread.id -> it.copy(filed = it.filed + link)
                    else -> it
                }
            }
        }
        return out
    }

    suspend fun undo(threadId: Int, role: String): FilingResult? {
        val (row, stored) = storedChat() ?: return null
        val link = links[threadId]?.firstOrNull { it.role == role } ?: return result()
        // A change the chat made to a record already there, with nothing left to put it back from (the
        // chat was sealed): there is nothing Undo can do, and the card offers none.
        if (link.state == LinkState.FILED && (link.made || link.prior != null)) {
            replace(threadId, undone(link))
            // A course takes back its marks of today with it: they were made by the same act.
            if (link.kind == LINK_COURSE) {
                links.getValue(threadId).filter { it.role.startsWith(DOSE_ROLE_PREFIX) && it.state == LinkState.FILED }
                    .forEach { replace(threadId, undone(it)) }
            }
        }
        doors.port.saveChat(chatRow(row, stored.withLinkMap()))
        return result()
    }

    suspend fun saveAgain(threadId: Int, role: String): FilingResult? {
        val (row, stored) = storedChat() ?: return null
        filedAgain(threadId, role, stored)?.let { again ->
            replace(threadId, again)
            // A course brings back with it the marks of today its Undo took (above): the same act.
            if (again.kind == LINK_COURSE && again.state == LinkState.FILED) marksAgain(threadId, stored)
        }
        doors.port.saveChat(chatRow(row, stored.withLinkMap()))
        return result()
    }

    /**
     * The marks of today [threadId]'s course took back with it at its Undo, filed again behind the
     * course saved again — under its id, also when a restore had put it back as it was ([readopted]) —
     * so its reminder never asks for a dose already taken.
     */
    private suspend fun marksAgain(threadId: Int, stored: ChatLog) {
        val course = links.getValue(threadId).firstOrNull { it.role.isEmpty() } ?: return
        if (ids[threadId to ""] == null) {
            (doors.resolve(course, key) as? Resolved.Found)?.let { ids[threadId to ""] = it.record.id }
        }
        links.getValue(threadId).filter { it.role.startsWith(DOSE_ROLE_PREFIX) && it.state == LinkState.UNDONE }
            .forEach { mark -> filedAgain(threadId, mark.role, stored)?.let { replace(threadId, it) } }
    }

    /**
     * [role]'s record of [threadId] after Save again: the row a restore put back on its moment as the
     * chat wrote it, taken as filed; else filed anew from what the thread wants now, at its moment when
     * that is free. Null when there is nothing to save again.
     */
    private suspend fun filedAgain(threadId: Int, role: String, stored: ChatLog): FiledLink? {
        val link = links[threadId]?.firstOrNull { it.role == role }?.takeIf { it.state in AGAIN } ?: return null
        val told = ctx.log.threads.firstOrNull { it.id == threadId } ?: stored.threads.firstOrNull { it.id == threadId }
        val thread = told?.copy(filed = links.getValue(threadId).toList())?.let(::tagged)
        val want = thread?.let { plans.wants(it, actCtx)[role] } as? RecordWant.File
        val again = readopted(link) ?: if (thread != null && want != null) {
            fileNew(thread, role, want.draft, plans.recordSig(thread, role, actCtx), link)
        } else {
            null
        }
        return again
    }

    private suspend fun storedChat(): Pair<ChatRow, ChatLog>? {
        val row = doors.port.chat(key) ?: return null
        val stored = decodeChatLog(row.payload) ?: return null
        stored.threads.forEach { links[it.id] = it.filed.toMutableList() }
        actCtx = ctx.copy(log = stored)
        return row to stored
    }

    /** The thread with its hidden tag worked out again, from what it was told so far. */
    private fun tagged(thread: ChatThread): ChatThread {
        val saved = thread.report.forSave()
        val facts = thread.topic?.script?.tagFacts(thread) ?: TagFacts()
        val earlier = earlierChatOf(saved, ctx.env.recentSymptoms, ctx.clock.zone)
        return thread.copy(report = thread.report.copy(symptomTag = classifyThread(saved, earlier, facts)))
    }

    /**
     * Every role of [thread]: first each one it no longer wants, taken back, then the ones it wants, in
     * their order — a course before its marks. Nothing is written past a record the act is about to take
     * back: a mark of a course's old time, still on the day, is what the Medicine tab pairs with the new
     * time's slot ([plannedDoses]), and the dose door would refuse the new mark for it.
     */
    private suspend fun reconcile(thread: ChatThread, wants: Map<String, RecordWant>) {
        val unwanted = links.getValue(thread.id).map { it.role }.filter { it !in wants }.distinct()
        (unwanted + wants.keys).forEach { role -> reconcileRole(thread, role, wants[role]) }
    }

    /** One role: kept as it stands, filed as the table says, or — no longer wanted — taken back. */
    private suspend fun reconcileRole(thread: ChatThread, role: String, want: RecordWant?) {
        val link = links.getValue(thread.id).firstOrNull { it.role == role }
        when {
            want is RecordWant.File -> file(thread, role, want.draft, link)?.let { replace(thread.id, it, role) }

            // Left as it stands; its id is still there for a later role's builder. Standing, it is no record
            // left unbuilt either: left on it, the mark of a failed act ([notBuilt]) would stay while the thread
            // only kept its records, and every act after that one would say "Not saved".
            want == RecordWant.Keep -> {
                val filed = link?.takeIf { it.state == LinkState.FILED }
                val found = filed?.let { doors.resolve(it, key) } as? Resolved.Found
                if (filed != null && found != null) {
                    ids[thread.id to role] = found.record.id
                    if (filed.err) replace(thread.id, filed.copy(err = false))
                }
            }

            link != null -> {
                takeBack(link)
                links.getValue(thread.id).removeAll { it.role == role }
            }
        }
    }

    /** An earlier thread's records are only checked: there, changed on their page, or gone. */
    private suspend fun check(threadId: Int) {
        val own = links.getValue(threadId)
        own.toList().filter { it.state == LinkState.FILED }.forEach { link ->
            val checked = when (val found = doors.resolve(link, key)) {
                is Resolved.Found -> link.copy(wsig = sigOf(found.record))
                is Resolved.Changed -> link.copy(state = LinkState.CHANGED, prior = null)
                Resolved.Gone -> link.copy(state = LinkState.GONE, prior = null)
            }
            replace(threadId, checked)
        }
    }

    /** The reconcile table: what [thread] wants of [role], against the link the database holds. */
    private suspend fun file(thread: ChatThread, role: String, draft: RecordDraft, link: FiledLink?): FiledLink? {
        val sig = plans.recordSig(thread, role, actCtx)
        return when (link?.state) {
            null -> fileNew(thread, role, draft, sig, null)

            LinkState.FILED -> keepOrRewrite(thread, role, draft, sig, link)

            // Only the person's own change of an answer files it again: a message alone changes no answer.
            LinkState.UNDONE, LinkState.REFUSED ->
                link.takeIf { sig == link.sig } ?: fileNew(thread, role, draft, sig, link)

            LinkState.GONE, LinkState.CHANGED, LinkState.SUPERSEDED -> link
        }
    }

    private suspend fun keepOrRewrite(
        thread: ChatThread,
        role: String,
        draft: RecordDraft,
        sig: Int,
        link: FiledLink,
    ): FiledLink = when (val found = doors.resolve(link, key)) {
        Resolved.Gone -> link.copy(state = LinkState.GONE, prior = null, err = false)

        is Resolved.Changed -> link.copy(state = LinkState.CHANGED, prior = null, err = false)

        is Resolved.Found -> {
            ids[thread.id to role] = found.record.id
            if (sig == link.sig) {
                link.copy(wsig = sigOf(found.record), err = false)
            } else {
                rewrite(thread, role, draft, sig, link, found.record)
            }
        }
    }

    /** A first write, or a record filed again after Undo or a refusal, at [previous]'s moment when it is free. */
    private suspend fun fileNew(
        thread: ChatThread,
        role: String,
        draft: RecordDraft,
        sig: Int,
        previous: FiledLink?,
    ): FiledLink? = when (draft) {
        is RecordDraft.Journal -> fileJournal(thread, role, draft, sig, previous)
        is RecordDraft.Course -> fileCourse(thread, role, draft, sig, previous)
        is RecordDraft.Update -> fileUpdate(thread, role, draft, sig, previous)
    }

    private suspend fun fileJournal(
        thread: ChatThread,
        role: String,
        draft: RecordDraft.Journal,
        sig: Int,
        previous: FiledLink?,
    ): FiledLink? {
        val kind = draft.door.kind
        val reuse = previous?.at?.takeIf { previous.kind == kind && doors.port.journalAt(kind, it).isEmpty() }
        val at = reuse ?: doors.freeMoment(kind, draft.wanted ?: defaultMoment(), ctx.past, namedMoments(kind))
        val shell = JournalEntry(day = dayOf(at).toString(), occurredAt = at, kind = kind, title = "", calories = 0.0)
        val made = runCatching { draft.build(shell, idsOf(thread.id)) }
        val built = made.getOrNull()?.takeIf { it.kind == kind }?.copy(id = 0, occurredAt = at)
            ?: return if (declined(made)) previous else unbuilt(previous)
        val link = FiledLink(kind, role, at, built.title, slot = draft.slot, course = draft.course, sig = sig)
        return when (val result = doors.write(built, draft.door, first = true)) {
            is DoorResult.Written -> {
                ids[thread.id to role] = result.id
                link.copy(wsig = rowSig(built)).also { firsts += thread.id to it }
            }

            is DoorResult.Refused -> {
                refused += Refusal(thread.id, role, result.blockingAt, result.blockingScore)
                link.copy(state = LinkState.REFUSED)
            }
        }
    }

    private suspend fun fileCourse(
        thread: ChatThread,
        role: String,
        draft: RecordDraft.Course,
        sig: Int,
        previous: FiledLink?,
    ): FiledLink? {
        val reuse = previous?.at?.takeIf {
            previous.kind == LINK_COURSE && doors.port.medicationsCreatedAt(it).isEmpty()
        }
        val at = reuse ?: doors.freeCourseMoment(ctx.clock.now)
        val built = runCatching { draft.build(at) }.getOrNull()?.copy(id = 0, createdAt = at)
            ?: return unbuilt(previous)
        ids[thread.id to role] = doors.insertCourse(built)
        val link = FiledLink(LINK_COURSE, role, at, built.name, built.startDay, sig = sig, wsig = courseSig(built))
        firsts += thread.id to link
        return link
    }

    /**
     * A change to a row already there: found at the draft's moment — by the fingerprint of this chat's
     * own link to it when it is this chat's — changed under its id, day and moment, and kept with what
     * puts it back. The link that named it turns superseded. No such row: refused.
     */
    private suspend fun fileUpdate(
        thread: ChatThread,
        role: String,
        draft: RecordDraft.Update,
        sig: Int,
        previous: FiledLink?,
    ): FiledLink? {
        val rows = doors.port.journalAt(draft.kind, draft.at)
        val target = linkNaming(draft.kind, draft.at, except = thread.id to role)
        val row = if (target != null) rows.singleOrNull { rowSig(it) == target.wsig } else rows.singleOrNull()
        val prior = row?.let { runCatching { draft.prior(it) }.getOrNull() }
        val changed = row?.let { found -> runCatching { draft.change(found) }.getOrNull()?.inPlaceOf(found) }
        return when {
            row == null -> {
                refused += Refusal(thread.id, role, null, null)
                (previous ?: FiledLink(draft.kind, role, draft.at, "", made = false))
                    .copy(state = LinkState.REFUSED, sig = sig, prior = null, err = false)
            }

            prior == null || changed == null -> unbuilt(previous)

            else -> {
                doors.writeRow(changed)
                ids[thread.id to role] = row.id
                target?.let { supersede(it) }
                FiledLink(row.kind, role, row.occurredAt, row.title, made = false, prior = prior, sig = sig)
                    .copy(wsig = rowSig(changed))
                    .also { firsts += thread.id to it }
            }
        }
    }

    /**
     * A filed record whose answers changed, rewritten under its id, day, moment and title — a title
     * written in another language stays as it was written. A draft whose wanted moment moved (a dose's
     * time changed) takes a free moment there, and the link moves with it.
     */
    private suspend fun rewrite(
        thread: ChatThread,
        role: String,
        draft: RecordDraft,
        sig: Int,
        link: FiledLink,
        found: FoundRecord,
    ): FiledLink = when {
        draft is RecordDraft.Journal && found is FoundRecord.Row ->
            rewriteJournal(thread, role, draft, sig, link, found.entry)

        draft is RecordDraft.Course && found is FoundRecord.Course -> {
            val built = runCatching { draft.build(found.course.createdAt) }.getOrNull()
                ?.copy(id = found.course.id, createdAt = found.course.createdAt)
            if (built == null) {
                link.copy(err = true)
            } else {
                doors.saveCourse(built)
                link.copy(title = built.name, start = built.startDay, sig = sig, wsig = courseSig(built), err = false)
            }
        }

        draft is RecordDraft.Update && found is FoundRecord.Row -> {
            val changed = runCatching { draft.change(found.entry) }.getOrNull()?.inPlaceOf(found.entry)
            if (changed == null) {
                link.copy(err = true)
            } else {
                doors.writeRow(changed)
                link.copy(sig = sig, wsig = rowSig(changed), err = false)
            }
        }

        // A want of another shape than the record it names: nothing is written over the record.
        else -> link.copy(err = true)
    }

    private suspend fun rewriteJournal(
        thread: ChatThread,
        role: String,
        draft: RecordDraft.Journal,
        sig: Int,
        link: FiledLink,
        found: JournalEntry,
    ): FiledLink {
        val kind = draft.door.kind
        val moved = draft.wanted?.takeIf { movedFrom(link.at, it) }
        val at = moved?.let { doors.freeMoment(kind, it, ctx.past, namedMoments(kind)) } ?: found.occurredAt
        val shell = found.copy(day = if (moved != null) dayOf(at).toString() else found.day, occurredAt = at)
        val tried = runCatching { draft.build(shell, idsOf(thread.id)) }
        // Not built, the record stands as it was; a builder that had nothing to write leaves no failure on it.
        val made = tried.getOrNull()?.takeIf { it.kind == kind } ?: return link.copy(err = !declined(tried))
        // Its id, moment and title stay; its day too, unless the moment moved and the builder chose one.
        val day = if (moved != null) made.day else found.day
        val built = made.copy(id = found.id, day = day, occurredAt = at, title = found.title)
        return when (val result = doors.write(built, draft.door, first = false)) {
            is DoorResult.Written -> {
                ids[thread.id to role] = found.id
                link.copy(at = at, sig = sig, wsig = rowSig(built), err = false)
            }

            // A dose moved onto a slot another mark holds: the old mark goes, and the card says why.
            is DoorResult.Refused -> {
                doors.delete(found)
                refused += Refusal(thread.id, role, result.blockingAt, result.blockingScore)
                link.copy(state = LinkState.REFUSED, sig = sig, err = false)
            }
        }
    }

    /**
     * A record its thread no longer wants, taken back: deleted when the chat made it — a course with
     * it — or put back as it was when the chat changed it. One edited on its page, or one that cannot
     * be told from another on its moment, is left as it stands.
     */
    private suspend fun takeBack(link: FiledLink) {
        if (link.state != LinkState.FILED) return
        val found = (doors.resolve(link, key) as? Resolved.Found)?.record ?: return
        when {
            found is FoundRecord.Course -> doors.deleteCourse(found.course)
            found is FoundRecord.Row && link.made -> doors.delete(found.entry)
            found is FoundRecord.Row -> putBack(link, found.entry)
        }
    }

    /** A changed record put back from its link's [FiledLink.prior]; the link that change superseded is filed again. */
    private suspend fun putBack(link: FiledLink, row: JournalEntry) {
        val prior = link.prior ?: return
        val restored = restoredRow(row, prior).inPlaceOf(row)
        doors.writeRow(restored)
        links.forEach { (threadId, own) ->
            own.toList().filter { it.state == LinkState.SUPERSEDED && it.kind == link.kind && it.at == link.at }
                .forEach { replace(threadId, it.copy(state = LinkState.FILED, wsig = rowSig(restored))) }
        }
    }

    /** Undo of a filed record: deleted, or put back; [LinkState.UNDONE] unless it was changed or deleted meanwhile. */
    private suspend fun undone(link: FiledLink): FiledLink = when (doors.resolve(link, key)) {
        is Resolved.Found -> {
            takeBack(link)
            link.copy(state = LinkState.UNDONE, prior = null, err = false)
        }

        is Resolved.Changed -> link.copy(state = LinkState.CHANGED, prior = null)

        Resolved.Gone -> link.copy(state = LinkState.GONE, prior = null)
    }

    /** A row a restore put back on the link's moment, exactly as the chat wrote it: taken as filed again. */
    private suspend fun readopted(link: FiledLink): FiledLink? {
        val back = if (link.kind == LINK_COURSE) {
            doors.port.medicationsCreatedAt(link.at).count { courseSig(it) == link.wsig }
        } else {
            doors.port.journalAt(link.kind, link.at).count { rowSig(it) == link.wsig }
        }
        return link.copy(state = LinkState.FILED, err = false).takeIf { back == 1 && link.wsig != 0 }
    }

    /** The link of this chat, other than [except], that names the row of [kind] at [at] as filed. */
    private fun linkNaming(kind: String, at: Long, except: Pair<Int, String>): FiledLink? =
        links.entries.firstNotNullOfOrNull { (threadId, own) ->
            own.firstOrNull {
                it.kind == kind && it.at == at && it.state == LinkState.FILED && (threadId to it.role) != except
            }
        }

    private fun supersede(target: FiledLink) {
        links.forEach { (threadId, own) ->
            if (target in own) replace(threadId, target.copy(state = LinkState.SUPERSEDED, prior = null))
        }
    }

    /** [link] in place of the one with its role in [threadId]'s links (or [role]'s, when it changed). */
    private fun replace(threadId: Int, link: FiledLink, role: String = link.role) {
        val own = links.getOrPut(threadId) { mutableListOf() }
        val index = own.indexOfFirst { it.role == role }
        if (index >= 0) own[index] = link else own += link
    }

    private fun idsOf(threadId: Int): (String) -> Long? = { role -> ids[threadId to role] }

    /**
     * A record that could not be built at a first write, or at one after Undo or a refusal: [previous]
     * marked, where there is one. A first write has no link to mark and would leave no trace at all — no row,
     * no card, no "Not saved" — so the act itself says it failed ([FilingResult.failed]). The next act
     * builds it again: its role has no link, or its link is marked.
     */
    private fun unbuilt(previous: FiledLink?): FiledLink? {
        failed = true
        return previous?.copy(err = true)
    }

    private fun sigOf(record: FoundRecord): Int = when (record) {
        is FoundRecord.Row -> rowSig(record.entry)
        is FoundRecord.Course -> courseSig(record.course)
    }

    /** Now, or for a chat of an earlier day that day's midday (the millisecond after it, [ChatDoors.freeMoment]). */
    private fun defaultMoment(): Long = if (ctx.past) middayOf(ctx.chatDay, ctx.clock.zone) else ctx.clock.now

    /** The day a record at [at] is filed under: the chat's, for an earlier day; else the date of its moment. */
    private fun dayOf(at: Long): LocalDate =
        if (ctx.past) ctx.chatDay else Instant.ofEpochMilli(at).atZone(ctx.clock.zone).toLocalDate()

    /** Whether a link at [at] stands somewhere else than a draft that wants [wanted] would be filed. */
    private fun movedFrom(at: Long, wanted: Long): Boolean {
        val start = if (ctx.past) wanted + 1 else wanted
        return at < start || at > start + MOMENT_SLACK_MS
    }

    private fun chatRow(row: ChatRow?, log: ChatLog) = ChatRow(
        key = key,
        day = row?.day ?: ctx.chatDay.toString(),
        startedAt = row?.startedAt ?: ctx.clock.now,
        updatedAt = maxOf(ctx.clock.now, (row?.updatedAt ?: 0L) + 1),
        payload = encodeChatLog(log, pending = false),
    )

    private fun ChatLog.withLinkMap(): ChatLog =
        copy(threads = threads.map { it.copy(filed = links[it.id]?.toList() ?: it.filed) })

    private fun result() =
        FilingResult(links.mapValues { it.value.toList() }, firsts.toList(), refused.toList(), failed = failed)

    /** The stored chat, newer than the one handed in: nothing is written; the page takes it ([ChatLog.adopting]). */
    private fun newer(stored: ChatLog) = FilingResult(stored.linkMap(), emptyList(), emptyList(), newer = stored)
}

/**
 * Whether [log] is older than what the database holds: a lower rev, or the same rev over other
 * threads. Equal revs pass only for the same threads — a rotation sending again the write it had in
 * flight — never for two compositions that each committed their own log at that rev after a race:
 * one's threads would be paired by id with the other's, and a record taken back the person never undid.
 */
private fun stale(stored: ChatLog, log: ChatLog): Boolean =
    stored.rev > log.rev || (stored.rev == log.rev && stored.threadNames() != log.threadNames())

/** What tells two logs' threads apart: each one's id, topic and what started it, in order. */
private fun ChatLog.threadNames(): List<Triple<Int, Symptom?, String?>> =
    threads.map { Triple(it.id, it.topic, it.from) }

/** Whether a builder ran and gave no row: nothing to write on purpose ([RecordDraft.Journal.build]), no failure. */
private fun declined(made: Result<JournalEntry?>): Boolean = made.isSuccess && made.getOrNull() == null

/** The states Save again files from: undone, refused, or deleted elsewhere. */
private val AGAIN = setOf(LinkState.UNDONE, LinkState.REFUSED, LinkState.GONE)

/** A new course's mark of today is filed under `"dose:<slot>"`. */
internal const val DOSE_ROLE_PREFIX = "dose:"

private fun ChatLog.linkMap(): Map<Int, List<FiledLink>> = threads.associate { it.id to it.filed }

/** Midday of [day] in [zone]: where every page stands a row of an earlier day ([dayInstant]). */
internal fun middayOf(day: LocalDate, zone: ZoneId): Long = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

/**
 * The earlier chat a symptom thread said it is the same as, found among [rows] by the moment the
 * thread keeps ([SymptomChat.sameAs]): what the tag's course is read from.
 */
private fun earlierChatOf(report: SymptomReport, rows: List<JournalEntry>, zone: ZoneId): EarlierChat? {
    val topic = report.asTold().symptom ?: return null
    val at = report.chat?.sameAs ?: return null
    val row = rows.firstOrNull { it.kind == KIND_SYMPTOM && it.occurredAt == at } ?: return null
    val day = row.day.toLocalDateOrNull()
        ?: Instant.ofEpochMilli(row.occurredAt).atZone(zone).toLocalDate()
    val onset = decodeSymptomPayload(row.measurementPayloadJson).answers[SymptomStep.ONSET]?.firstOrNull()
    return EarlierChat(topic, day, at, onset)
}

/** [this] under [row]'s id, day, moment and kind: a change, or a putting back, of that very row. */
private fun JournalEntry.inPlaceOf(row: JournalEntry): JournalEntry =
    copy(id = row.id, day = row.day, occurredAt = row.occurredAt, kind = row.kind)

/**
 * Every column of [row] a page edits, written down to put the row back as it was: the generic
 * [RecordDraft.Update.prior]. Written by hand in one order, as every payload is.
 */
internal fun rowPrior(row: JournalEntry): String {
    val fields = mutableListOf(
        "\"title\":" + JSONObject.quote(row.title),
        "\"subtitle\":" + JSONObject.quote(row.subtitle),
        "\"note\":" + JSONObject.quote(row.note),
    )
    row.measurementPayloadJson?.let { fields += "\"payload\":" + JSONObject.quote(it) }
    row.durationMinutes?.let { fields += "\"minutes\":$it" }
    row.moodScore?.let { fields += "\"mood\":$it" }
    row.energyScore?.let { fields += "\"energy\":$it" }
    return fields.joinToString(",", "{", "}")
}

/** [row] with [rowPrior]'s columns put back; a prior this build cannot read changes nothing. */
internal fun restoredRow(row: JournalEntry, prior: String): JournalEntry {
    val json = runCatching { JSONObject(prior) }.getOrNull() ?: return row
    return row.copy(
        title = json.optString("title", row.title),
        subtitle = json.optString("subtitle", row.subtitle),
        note = json.optString("note", row.note),
        measurementPayloadJson = json.opt("payload") as? String,
        durationMinutes = (json.opt("minutes") as? Number)?.toDouble(),
        moodScore = (json.opt("mood") as? Number)?.toInt(),
        energyScore = (json.opt("energy") as? Number)?.toInt(),
    )
}
