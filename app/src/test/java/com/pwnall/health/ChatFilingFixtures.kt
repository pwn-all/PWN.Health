package com.pwnall.health

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// A database the filer's tests run the real doors over: the journal, the courses and the chats in
// memory, with Room's upsert and an act that rolls back whatever it wrote when it throws.

internal val CHAT_TODAY: LocalDate = LocalDate.of(2026, 10, 5)
internal val CHAT_ZONE: ZoneId = ZoneId.of("UTC")

/** Noon of [CHAT_TODAY] in [CHAT_ZONE]: a moment no test confuses with a back-dated one. */
internal const val CHAT_NOW = 1_791_201_600_000L

internal fun chatKey(): String = UUID.randomUUID().toString()

/**
 * The ChatDbPort a phone implements over Room, in memory; [failing] names one call to throw once.
 *
 * Where it is not Room, nothing the doors do reaches the difference, and a test that comes to rest
 * on one has to bring the fixture along first. A course is inserted with id 0 alone ([ChatDoors]
 * clears it), so the id [insertMedication] is handed is not read; a course saved is one rewritten
 * under the id it was read with. A row written over is not stamped [JournalEntry.editedAt], which
 * [rowSig] leaves out. [writes] and [deleted] count calls, and a rolled-back act keeps its count.
 */
internal class MemoryChatDb : ChatDbPort {
    val journal = LinkedHashMap<Long, JournalEntry>()
    val courses = LinkedHashMap<Long, Medication>()
    val chats = LinkedHashMap<String, ChatRow>()
    val deleted = mutableListOf<Long>()
    var writes = 0
    var failing: String? = null
    private var nextJournal = 1L
    private var nextCourse = 1L

    private fun fail(call: String) {
        if (failing == call) {
            failing = null
            error("$call failed")
        }
    }

    override suspend fun chat(key: String): ChatRow? = chats[key]

    override suspend fun saveChat(row: ChatRow) {
        fail("saveChat")
        chats[row.key] = row
    }

    override suspend fun deleteChat(key: String) {
        fail("deleteChat")
        chats.remove(key)
    }

    override suspend fun journalAt(kind: String, at: Long): List<JournalEntry> =
        journal.values.filter { it.kind == kind && it.occurredAt == at }.sortedBy { it.id }

    override suspend fun journalOnNow(day: String): List<JournalEntry> =
        journal.values.filter { it.day == day }.sortedByDescending { it.occurredAt }

    override suspend fun latestEntryBetween(kind: String, sinceMs: Long, untilMs: Long): JournalEntry? =
        journal.values.filter { it.kind == kind && it.occurredAt > sinceMs && it.occurredAt < untilMs }
            .maxWithOrNull(compareBy({ it.occurredAt }, { it.id }))

    /** Room's upsert: id 0 is numbered, an id that is there is replaced, one that is not is inserted under it. */
    override suspend fun writeJournal(row: JournalEntry): Long {
        fail("writeJournal")
        // Long enough for a second act to try its luck while this one is in the database.
        delay(1)
        writes++
        val id = if (row.id == 0L) nextJournal++ else row.id
        nextJournal = maxOf(nextJournal, id + 1)
        journal[id] = row.copy(id = id)
        return id
    }

    override suspend fun deleteJournal(id: Long) {
        journal.remove(id)
        deleted += id
    }

    override suspend fun medicationsCreatedAt(createdAt: Long): List<Medication> =
        courses.values.filter { it.createdAt == createdAt }.sortedBy { it.id }

    override suspend fun medication(id: Long): Medication? = courses[id]

    override suspend fun insertMedication(row: Medication): Long {
        val id = nextCourse++
        courses[id] = row.copy(id = id)
        return id
    }

    override suspend fun saveMedication(row: Medication) {
        courses[row.id] = row
    }

    override suspend fun deleteMedication(row: Medication) {
        courses.remove(row.id)
    }

    /** A row put in as its own page, or a restore, would: numbered by the database. */
    fun put(row: JournalEntry): JournalEntry {
        val id = nextJournal++
        return row.copy(id = id).also { journal[id] = it }
    }

    /** Every row taken out and put back under new ids, as a restore renumbers them. */
    fun renumber() {
        val rows = journal.values.toList()
        journal.clear()
        rows.forEach { put(it.copy(id = 0)) }
    }

    fun pains(): List<JournalEntry> = journal.values.filter { it.kind == KIND_PAIN }

    private data class Copy(
        val journal: Map<Long, JournalEntry>,
        val courses: Map<Long, Medication>,
        val chats: Map<String, ChatRow>,
        val next: Pair<Long, Long>,
    )

    private fun copy() = Copy(journal.toMap(), courses.toMap(), chats.toMap(), nextJournal to nextCourse)

    private fun restore(copy: Copy) {
        journal.clear()
        journal.putAll(copy.journal)
        courses.clear()
        courses.putAll(copy.courses)
        chats.clear()
        chats.putAll(copy.chats)
        nextJournal = copy.next.first
        nextCourse = copy.next.second
    }

    /** One transaction: whatever the block wrote is undone when it throws. */
    suspend fun <T> transaction(block: suspend () -> T): T {
        val before = copy()
        return runCatching { block() }.getOrElse {
            restore(before)
            throw it
        }
    }
}

/** The store over [db]: acts take turns, as a database's write transactions do. */
internal class MemoryChatStore(val db: MemoryChatDb = MemoryChatDb()) : ChatStore {
    private val lock = Mutex()
    var acts = 0
        private set

    override suspend fun <T> act(block: suspend (ChatDoors) -> T): T = lock.withLock {
        acts++
        db.transaction { block(ChatDoors(db)) }
    }
}

/** What a test's threads want, and how their answers are fingerprinted. */
internal class TestPlans(
    private val wants: (ChatThread) -> Map<String, RecordWant>,
    private val sig: (ChatThread, String) -> Int = { thread, _ -> answersSig(thread) },
) : FilingPlans {
    override fun wants(thread: ChatThread, ctx: FilingContext): Map<String, RecordWant> = wants(thread)

    override fun recordSig(thread: ChatThread, role: String, ctx: FilingContext): Int = sig(thread, role)
}

/** The strings of a language: every topic's name, and a few more, in English or in Russian. */
internal fun chatStrings(russian: Boolean = false): (Int) -> String = { res ->
    when (res) {
        R.string.symptom_fever -> if (russian) "Температура" else "Fever"
        R.string.symptom_other -> if (russian) "Другое" else "Something else"
        R.string.symptom_chest_pain -> if (russian) "Боль в груди" else "Chest pain"
        R.string.pain_title -> if (russian) "Боль" else "Pain"
        else -> "string $res"
    }
}

internal fun filingContext(
    log: ChatLog,
    key: String,
    now: Long = CHAT_NOW,
    day: LocalDate = CHAT_TODAY,
    past: Boolean = false,
    env: ChatEnv = ChatEnv(),
    russian: Boolean = false,
) = FilingContext(FilingClock(now, CHAT_TODAY, CHAT_ZONE), day, past, key, log, env, chatStrings(russian))

/** A thread of [topic] whose answers so far are [values], each step asked. */
internal fun valuedThread(id: Int, topic: Symptom, vararg values: Pair<SymptomStep, String>): ChatThread {
    val turns = listOf(ChatTurn.Asked(SymptomStep.SYMPTOM)) + values.map { ChatTurn.Asked(it.first) }
    return ChatThread(id, SymptomReport(symptom = topic, values = values.toMap(), chat = SymptomChat(turns)))
}

/** [this] with [step]'s value changed, as a rewound and answered-again thread holds it. */
internal fun ChatThread.revalued(step: SymptomStep, value: String): ChatThread =
    copy(report = report.copy(values = report.values + (step to value)))

/** A pain row as its page writes one, for the shell the filer hands in. */
internal fun painRow(shell: JournalEntry, nrs: Int, title: String = "Pain"): JournalEntry = painEntry(
    title,
    PainReport(regions = listOfNotNull(RegionSide.parse("KNEE:R")), nrs = nrs),
    LocalDate.parse(shell.day),
    CHAT_TODAY,
    shell.occurredAt,
    previous = shell,
)

/** A pain thread's want: the row of its 1–10, through the pain door. */
internal fun painWant(thread: ChatThread, wanted: Long? = null): RecordWant.File {
    val nrs = thread.report.values[SymptomStep.PAIN_NRS]?.toIntOrNull() ?: 1
    return RecordWant.File(RecordDraft.Journal(JournalDoor.PAIN, wanted) { shell, _ -> painRow(shell, nrs) })
}

internal fun logOf(vararg threads: ChatThread, rev: Int = 1, past: Boolean = false) =
    ChatLog(threads = threads.toList(), next = (threads.maxOfOrNull { it.id } ?: -1) + 1, past = past, rev = rev)

/** One act of [filing] over [log], as the page commits it. */
internal fun persist(filing: ChatFiling, log: ChatLog, key: String, now: Long = CHAT_NOW, russian: Boolean = false) =
    runBlocking { checkNotNull(filing.persist(log, filingContext(log, key, now, russian = russian))) }

/** The chat as its row holds it now. */
internal fun stored(store: MemoryChatStore, key: String): ChatLog =
    checkNotNull(decodeChatLog(checkNotNull(store.db.chats[key]).payload))

/** [thread]'s link of [role] in an act's result. */
internal fun linkOf(result: FilingResult, thread: Int = 0, role: String = ""): FiledLink =
    checkNotNull(result.links[thread]?.firstOrNull { it.role == role }) { "no link $thread/$role in $result" }

/** A mood as its page writes one. */
internal fun moodRow(at: Long, score: Int) = JournalEntry(
    day = CHAT_TODAY.toString(),
    occurredAt = at,
    kind = KIND_MOOD,
    title = "Mood",
    calories = 0.0,
    moodScore = score,
    note = "Good",
)

/** A mood thread's want: its face from the thread's value, through the mood door. */
internal fun moodWant(thread: ChatThread): RecordWant.File {
    val score = thread.report.values[SymptomStep.PAIN_NRS]?.toIntOrNull() ?: 3
    return RecordWant.File(
        RecordDraft.Journal(JournalDoor.MOOD, wanted = null) { shell, _ ->
            moodRow(shell.occurredAt, score).copy(id = shell.id, day = shell.day)
        },
    )
}
