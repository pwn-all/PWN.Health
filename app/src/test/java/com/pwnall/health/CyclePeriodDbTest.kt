package com.pwnall.health

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The rules the cycle writes in Data.kt keep inside their transactions, run on Room over SQLite:
 * a correction that cannot be filed files nothing, a period rejoined loses its end for good, a
 * period taken back leaves the rest of its days, and every deletion names the row the ways the
 * Health Connect sync will look for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class CyclePeriodDbTest {
    private lateinit var db: StepsDb

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StepsDb::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun row(kind: String, day: String, log: CycleLog? = null, recordKey: String? = null) = JournalEntry(
        day = day,
        occurredAt = LocalDate.parse(day).toEpochDay() * 86_400_000L,
        kind = kind,
        title = "",
        calories = 0.0,
        measurementPayloadJson = log?.let { "{\"$CYCLE_PAYLOAD_KEY\":\"${it.name}\"}" },
        healthConnectRecordKey = recordKey,
    )

    private suspend fun tombstones() = db.dao().allTombstones().mapTo(HashSet()) { it.recordKey }

    /**
     * A start moved into a period already on file is turned down, and the end confirmed with it
     * goes with it: filed, the confirmation would say "period end" over a start that stayed where it was.
     */
    @Test
    fun aMovedStartThatIsTurnedDownFilesNoEnd() = runBlocking {
        db.dao().insertJournalEntries(
            listOf(
                row(KIND_CYCLE, "2026-03-01"),
                row(KIND_CYCLE_END, "2026-03-05"),
                row(KIND_CYCLE, "2026-03-20"),
            ),
        )

        val filed = db.logCyclePeriod(
            start = row(KIND_CYCLE, "2026-03-03"),
            end = row(KIND_CYCLE_END, "2026-03-25"),
            previousStartDay = "2026-03-20",
        )

        assertFalse(filed)
        val marks = db.dao().cycleMarksNow().map { it.kind to it.day }
        assertEquals(
            listOf(KIND_CYCLE to "2026-03-01", KIND_CYCLE_END to "2026-03-05", KIND_CYCLE to "2026-03-20"),
            marks,
        )
        assertEquals(emptySet<String>(), tombstones())
    }

    /** A start the day after an end rejoins that period: the end is deleted, tombstone and all, and no start added. */
    @Test
    fun aPeriodRejoinedTheNextDayLosesItsEndAndLeavesItsTombstone() = runBlocking {
        db.dao().insertJournalEntries(listOf(row(KIND_CYCLE, "2026-03-01"), row(KIND_CYCLE_END, "2026-03-05")))

        assertTrue(db.startOrResumeCycle(row(KIND_CYCLE, "2026-03-06"), LocalDate.parse("2026-03-06")))

        assertEquals(listOf(KIND_CYCLE to "2026-03-01"), db.dao().cycleMarksNow().map { it.kind to it.day })
        assertTrue(tombstones().contains("period-end:2026-03-05"))
    }

    /** "A slip — delete it" takes the marks and the flow of those days, and nothing else of them or around them. */
    @Test
    fun aPeriodTakenBackLeavesTheRestOfItsDays() = runBlocking {
        db.dao().insertJournalEntries(
            listOf(
                row(KIND_CYCLE, "2026-03-01"),
                row(KIND_CYCLE_LOG, "2026-03-02", CycleLog.FLOW_MEDIUM),
                row(KIND_CYCLE_LOG, "2026-03-02", CycleLog.SEX_UNPROTECTED),
                row(KIND_CYCLE_LOG, "2026-03-03", CycleLog.MUCUS_CREAMY),
                row(KIND_CYCLE_LOG, "2026-03-04", CycleLog.SPOTTING),
                row(KIND_CYCLE_END, "2026-03-05"),
                row(KIND_CYCLE_LOG, "2026-03-06", CycleLog.FLOW_LIGHT),
            ),
        )

        db.deleteCyclePeriod("2026-03-01", "2026-03-05")

        val left = db.dao().allJournal().map { it.day to it.cycleLog() }.toSet()
        assertEquals(
            setOf(
                "2026-03-02" to CycleLog.SEX_UNPROTECTED,
                "2026-03-03" to CycleLog.MUCUS_CREAMY,
                "2026-03-04" to CycleLog.SPOTTING,
                "2026-03-06" to CycleLog.FLOW_LIGHT,
            ),
            left,
        )
        assertTrue(tombstones().containsAll(setOf("period:2026-03-01", "period-end:2026-03-05")))
    }

    /** A deleted start is remembered by its id, by the record it came from and by its kind and day. */
    @Test
    fun aDeletedRowLeavesEveryKeyTheSyncLooksFor() = runBlocking {
        val id = db.dao().saveJournalEntry(row(KIND_CYCLE, "2026-03-01", recordKey = "hc-record-1"))

        db.deleteJournalEvent(id)

        assertEquals(emptyList<JournalEntry>(), db.dao().allJournal())
        assertEquals(setOf(journalRecordKey(id), "hc-record-1", "period:2026-03-01"), tombstones())
    }

    /**
     * A start deleted on its own — the calendar's bin, the entry editor's — takes its period's end,
     * tombstone and all; an end deleted on its own reopens its period and leaves the start.
     */
    @Test
    fun aStartDeletedAloneTakesItsEndAndAnEndLeavesItsStart() = runBlocking {
        val march = db.dao().saveJournalEntry(row(KIND_CYCLE, "2026-03-01"))
        db.dao().saveJournalEntry(row(KIND_CYCLE_END, "2026-03-05"))
        db.dao().saveJournalEntry(row(KIND_CYCLE, "2026-03-29"))
        val aprilEnd = db.dao().saveJournalEntry(row(KIND_CYCLE_END, "2026-04-02"))

        db.deleteJournalEvent(march)
        db.deleteJournalEvent(aprilEnd)

        assertEquals(listOf(KIND_CYCLE to "2026-03-29"), db.dao().cycleMarksNow().map { it.kind to it.day })
        val graves = setOf("period:2026-03-01", "period-end:2026-03-05", "period-end:2026-04-02")
        assertTrue(tombstones().containsAll(graves))
    }

    /**
     * A start is turned down on a day that already has one and on any day of a period on file, its
     * first and last included: either would leave two starts before one end, and the later one takes
     * the end. A day clear of every period is filed, once.
     */
    @Test
    fun aStartIsFiledOnlyOnADayClearOfEveryPeriodAndOnlyOnce() = runBlocking {
        db.dao().insertJournalEntries(listOf(row(KIND_CYCLE, "2026-03-01"), row(KIND_CYCLE_END, "2026-03-05")))

        listOf("2026-03-01", "2026-03-03", "2026-03-05").forEach { day ->
            assertFalse(day, db.logCycleStart(row(KIND_CYCLE, day)))
        }
        assertTrue(db.logCycleStart(row(KIND_CYCLE, "2026-03-29")))
        assertFalse("the second tap of the day", db.logCycleStart(row(KIND_CYCLE, "2026-03-29")))

        val marks = db.dao().cycleMarksNow().map { it.kind to it.day }
        val filed = listOf(KIND_CYCLE to "2026-03-01", KIND_CYCLE_END to "2026-03-05", KIND_CYCLE to "2026-03-29")
        assertEquals(filed, marks)
    }

    /**
     * Medium after Light is a correction of the day's flow: one flow row is left, the Light one is
     * remembered for the sync, and the day's other answers — another section, an act — are not touched.
     */
    @Test
    fun aSecondAnswerInOneSectionReplacesTheFirstAndNothingElse() = runBlocking {
        db.dao().insertJournalEntries(
            listOf(
                row(KIND_CYCLE_LOG, "2026-03-02", CycleLog.MUCUS_CREAMY),
                row(KIND_CYCLE_LOG, "2026-03-02", CycleLog.SEX_PROTECTED),
                row(KIND_CYCLE_LOG, "2026-03-03", CycleLog.FLOW_HEAVY),
            ),
        )
        db.logCycleLog(row(KIND_CYCLE_LOG, "2026-03-02", CycleLog.FLOW_LIGHT), CycleSection.FLOW)
        val light = db.dao().allJournal().single { it.cycleLog() == CycleLog.FLOW_LIGHT }.id

        db.logCycleLog(row(KIND_CYCLE_LOG, "2026-03-02", CycleLog.FLOW_MEDIUM), CycleSection.FLOW)

        assertEquals(
            setOf(
                "2026-03-02" to CycleLog.MUCUS_CREAMY,
                "2026-03-02" to CycleLog.SEX_PROTECTED,
                "2026-03-02" to CycleLog.FLOW_MEDIUM,
                "2026-03-03" to CycleLog.FLOW_HEAVY,
            ),
            db.dao().allJournal().map { it.day to it.cycleLog() }.toSet(),
        )
        assertTrue(tombstones().contains(journalRecordKey(light)))
    }

    /** "Not over yet" takes the end and only the end: the start and the flow between them stay hers. */
    @Test
    fun aReopenedPeriodLosesItsEndAndKeepsItsDays() = runBlocking {
        db.dao().insertJournalEntries(
            listOf(
                row(KIND_CYCLE, "2026-03-01"),
                row(KIND_CYCLE_LOG, "2026-03-05", CycleLog.FLOW_LIGHT),
                row(KIND_CYCLE_END, "2026-03-05"),
            ),
        )

        db.reopenCyclePeriod("2026-03-05")

        assertEquals(
            listOf(KIND_CYCLE to "2026-03-01", KIND_CYCLE_LOG to "2026-03-05"),
            db.dao().allJournal().sortedBy { it.day }.map { it.kind to it.day },
        )
        assertTrue(tombstones().contains("period-end:2026-03-05"))
    }

    /**
     * An act tapped twice within the bounce window today is one act; another act in that moment is
     * filed. On an earlier day every act carries the same midday instant, so none of them bounces.
     */
    @Test
    fun anActBouncesOnlyAgainstTheSameActTappedAMomentAgoToday() = runBlocking {
        val today = LocalDate.parse("2026-03-09")
        val at = today.toEpochDay() * 86_400_000L + 43_200_000L
        suspend fun filed(log: CycleLog?, ms: Long, day: String = today.toString()) = db.logIntimacy(
            row(if (log == null) KIND_MASTURBATION else KIND_CYCLE_LOG, day, log).copy(occurredAt = ms),
            log,
            today,
        )

        assertTrue(filed(CycleLog.SEX_PROTECTED, at))
        assertFalse("the same act a second later", filed(CycleLog.SEX_PROTECTED, at + 1_000L))
        assertTrue("another act", filed(CycleLog.SEX_UNPROTECTED, at + 1_000L))
        assertTrue("the solo one", filed(null, at + 1_000L))
        listOf(1, 2).forEach { assertTrue("an earlier day, $it", filed(CycleLog.SEX_PROTECTED, at, "2026-03-02")) }
        assertEquals(5, db.dao().allJournal().size)
    }
}
