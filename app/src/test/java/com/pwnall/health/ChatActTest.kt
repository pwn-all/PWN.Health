package com.pwnall.health

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * A chat's act over Room itself: the doors in one real transaction with the chat row, rolled back
 * whole when anything in it throws, and a chat's back-dated row beside the page's own on the same
 * midday, both read as untimed. The other tests run the same doors over a port in memory; this is
 * the port the phone has, Room over SQLite, run on Robolectric so that every `check` runs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ChatActTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)

    @After
    fun close() = db.close()

    private fun painWants(thread: ChatThread): Map<String, RecordWant> = if (thread.topic != Symptom.PAIN) {
        emptyMap()
    } else {
        mapOf(
            "" to RecordWant.File(
                RecordDraft.Journal(JournalDoor.PAIN, wanted = null) { shell, _ ->
                    painEntry(
                        "Pain",
                        PainReport(regions = listOfNotNull(RegionSide.parse("KNEE:R")), nrs = 6),
                        LocalDate.parse(shell.day),
                        today,
                        shell.occurredAt,
                        previous = shell,
                    )
                },
            ),
        )
    }

    private val plans = object : FilingPlans {
        override fun wants(thread: ChatThread, ctx: FilingContext) = painWants(thread)

        override fun recordSig(thread: ChatThread, role: String, ctx: FilingContext) = answersSig(thread)
    }

    private fun thread() = ChatThread(
        0,
        SymptomReport(
            symptom = Symptom.PAIN,
            values = mapOf(SymptomStep.PAIN_NRS to "6"),
            chat = SymptomChat(listOf(ChatTurn.Asked(SymptomStep.SYMPTOM), ChatTurn.Asked(SymptomStep.PAIN_NRS))),
        ),
    )

    private fun context(log: ChatLog, key: String, day: LocalDate = today, past: Boolean = false) = FilingContext(
        FilingClock(System.currentTimeMillis(), today, zone),
        day,
        past,
        key,
        log,
        ChatEnv(),
    ) { "string $it" }

    @Test
    fun aRecordAndItsChatAreWrittenInOneAct() = runBlocking {
        val key = UUID.randomUUID().toString()
        val log = ChatLog(threads = listOf(thread()), next = 1, rev = 1)
        val filing = ChatFiling(RoomChatStore(db), key, plans)

        val result = checkNotNull(filing.persist(log, context(log, key)))

        val row = db.dao().journalOnNow(today.toString()).single { it.kind == KIND_PAIN }
        val link = checkNotNull(result.links[0]).single()
        assertEquals(link.at, row.occurredAt)
        assertEquals(rowSig(row), link.wsig)
        val chat = checkNotNull(db.dao().chat(key))
        assertEquals(listOf(link), checkNotNull(decodeChatLog(chat.payload)).threads.single().filed)
        assertEquals(listOf(row), db.dao().journalAt(KIND_PAIN, link.at))
    }

    @Test
    fun anActThatThrowsLeavesNothingBehind() = runBlocking {
        val key = UUID.randomUUID().toString()
        val failed = runCatching {
            db.chatAct { doors ->
                doors.writeRow(
                    JournalEntry(
                        day = today.toString(),
                        occurredAt = 5L,
                        kind = KIND_PAIN,
                        title = "Pain",
                        calories = 0.0,
                    ),
                )
                doors.port.saveChat(ChatRow(key, today.toString(), 1L, 1L, "{}"))
                error("a failure after both writes")
            }
        }
        assertTrue(failed.isFailure)
        assertTrue(db.dao().journalAt(KIND_PAIN, 5L).isEmpty())
        assertNull(db.dao().chat(key))
    }

    /**
     * A record card's row and the chat's mark for it are written in one act, the row at the first moment
     * of its kind still free. A page whose chat is older than the database's writes neither: the act is
     * rolled back, its row with it, and the newer chat is handed over. Written in three steps, a row could
     * stand in the journal whose card still offered Add.
     */
    @Test
    fun aCardsRowAndItsMarkAreOneActAndNoneOverANewerChat() = runBlocking {
        val key = UUID.randomUUID().toString()
        val log = ChatLog(threads = listOf(thread()), next = 1, rev = 1)
        val filing = ChatFiling(RoomChatStore(db), key, plans)
        checkNotNull(filing.persist(log, context(log, key)))
        val noon = middayOf(today, zone)
        val water = JournalEntry(
            day = today.toString(),
            occurredAt = noon,
            kind = KIND_WATER,
            title = "Water",
            calories = 0.0,
        )
        val card = JournalKey(0, ChatJournalType.WATER_LOG, 0, 6)
        suspend fun add(rev: Int) = filing.addCardRow(
            water,
            backDated = false,
            marked = { id, at ->
                log.marked(0, card, CardMark.Added(KIND_WATER, id, at, "Water", 250.0)).copy(rev = rev)
            },
            ctx = { context(it, key) },
        )

        val added = checkNotNull(add(rev = 2))

        val row = db.dao().journalAt(KIND_WATER, noon).single()
        val chat = checkNotNull(decodeChatLog(checkNotNull(db.dao().chat(key)).payload))
        assertNull(added.newer)
        assertEquals(CardMark.Added(KIND_WATER, row.id, noon, "Water", 250.0), chat.threads.single().cards[card])

        val stale = checkNotNull(add(rev = 1))

        assertEquals(2, checkNotNull(stale.newer).rev)
        assertTrue(db.dao().journalAt(KIND_WATER, noon + 1).isEmpty())
        assertEquals(listOf(row), db.dao().journalAt(KIND_WATER, noon))
        assertEquals(2, checkNotNull(decodeChatLog(checkNotNull(db.dao().chat(key)).payload)).rev)
    }

    /**
     * A back-dated pain the page wrote at 12:00:00.000 and one a chat filed at .001 on the same day:
     * each link names its own row, and the chat's act leaves the page's row as it was. Both show
     * 12:00 in the day list, as every back-dated row does.
     */
    @Test
    fun aChatsBackDatedPainStandsBesideThePagesAndEachLinkResolvesItsOwnRow() = runBlocking {
        val day = today.minusDays(3)
        val noon = middayOf(day, zone)
        val pageId = db.saveJournalEvent(
            painEntry(
                "Pain",
                PainReport(regions = listOfNotNull(RegionSide.parse("HIP:L")), nrs = 2),
                day,
                today,
                noon,
            ),
        )
        val page = checkNotNull(db.dao().journalEntry(pageId))
        val key = UUID.randomUUID().toString()
        val log = ChatLog(threads = listOf(thread()), next = 1, past = true, rev = 1)

        val result = checkNotNull(
            ChatFiling(RoomChatStore(db), key, plans).persist(log, context(log, key, day, past = true)),
        )

        val rows = db.dao().journalOnNow(day.toString()).filter { it.kind == KIND_PAIN }.sortedBy { it.occurredAt }
        assertEquals(listOf(noon, noon + 1), rows.map { it.occurredAt })
        assertEquals(page, db.dao().journalEntry(pageId))
        val link = checkNotNull(result.links[0]).single()
        assertEquals(noon + 1, link.at)
        assertEquals(rowSig(rows.last()), link.wsig)
        val found = db.chatAct { it.resolve(link, key) } as Resolved.Found
        assertEquals(rows.last().id, found.record.id)
    }
}
