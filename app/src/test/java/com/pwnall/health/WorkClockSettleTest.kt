package com.pwnall.health

import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The work timer's stopped session after its process was killed: the payload the Stop left in the
 * preferences is all there is, and opening the Work screen has to turn it into exactly one row.
 * What is decided is WorkClockStateTest's, over a list; this is [WorkClock.settle] itself over the
 * platform's SharedPreferences and Room on SQLite, which Robolectric runs on the JVM, so it is run
 * by every `check` and not by whoever remembers to attach a phone. The rows go to a database in
 * memory, which `settle` is handed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class WorkClockSettleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val db = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()

    private val start = LocalDateTime.of(2001, 3, 5, 9, 0).atZone(zone).toInstant().toEpochMilli()
    private val entry = checkNotNull(workEntry(listOf(WorkSegment(start, start + 8 * 3_600_000L)), "Work", zone))

    private fun prefs() = context.getSharedPreferences("work_clock", Context.MODE_PRIVATE)

    private suspend fun workRows() = db.dao().journalOnNow(entry.day).filter { it.kind == KIND_WORK }

    /** What the Stop's one write leaves when the process dies right after it. */
    private fun leavePending() = prefs().edit(commit = true) { putString("pending", entry.measurementPayloadJson) }

    @After
    fun close() = db.close()

    @Test
    fun aStopKilledBeforeRoomIsWrittenOnceHoweverOftenTheScreenOpens() = runBlocking {
        leavePending()
        WorkClock.settle(context, db)
        WorkClock.settle(context, db)
        assertEquals(listOf(start), workRows().map { it.occurredAt })
        assertNull(prefs().getString("pending", null))
    }

    /**
     * The same through Room: the row an Undo cut short left behind is rewritten in place by the
     * session's next Stop, which starts where it did, and the journal holds the longer day once.
     */
    @Test
    fun aRowAnUndoLeftBehindGivesWayToTheSessionsNextStop() = runBlocking {
        val longer = checkNotNull(workEntry(listOf(WorkSegment(start, start + 10 * 3_600_000L)), "Work", zone))
        db.saveJournalEvent(entry)
        prefs().edit(commit = true) { putString("pending", longer.measurementPayloadJson) }
        WorkClock.settle(context, db)
        assertEquals(listOf(longer.measurementPayloadJson), workRows().map { it.measurementPayloadJson })
        assertNull(prefs().getString("pending", null))
    }

    @Test
    fun aRowWrittenBeforeTheKillIsNotWrittenAgain() = runBlocking {
        db.saveJournalEvent(entry)
        leavePending()
        WorkClock.settle(context, db)
        assertEquals(listOf(start), workRows().map { it.occurredAt })
        assertNull(prefs().getString("pending", null))
    }
}
