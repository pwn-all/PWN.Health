package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the work timer decides about its stored state, off the device: the queue of stopped sessions
 * still waiting for their rows, how a row written before the process was killed is known again, the
 * session an Undo puts back on the clock, and what the forgotten-stop check does at each length.
 *
 * The preferences, Room and WorkManager around these decisions run only on a phone; what is pinned
 * here is each choice they carry out.
 */
class WorkClockStateTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val title = "Work"
    private val hour = 3_600_000L

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** A day worked from nine with a lunch break, stopped at [endHour]: the row a Stop returns. */
    private fun stopped(day: Int, endHour: Int = 17): JournalEntry {
        val morning = WorkSession().start(at(day, 9)).session.pause(at(day, 12)).session
        return morning.resume(at(day, 13)).session.stop(at(day, endHour), title, zone).entry!!
    }

    private fun payload(entry: JournalEntry): String = entry.measurementPayloadJson!!

    @Test
    fun aStopJoinsTheEndOfTheQueue() {
        val first = payload(stopped(21))
        val second = payload(stopped(22))

        val one = pendingWith(null, first)
        assertEquals(listOf(first), decodePending(one))
        val two = pendingWith(one, second)
        assertEquals("$first\n$second", two)
        assertEquals(listOf(first, second), decodePending(two))

        // Nothing stored, and a queue left blank, are both an empty queue.
        assertEquals(emptyList<String>(), decodePending(null))
        assertEquals(emptyList<String>(), decodePending(" \n\n"))
        assertEquals(first, pendingWith(" \n", first))
    }

    /** The widget's Stop can land while the screen is writing the queue it read a moment before. */
    @Test
    fun aStopCommittedWhileTheQueueWasBeingWrittenKeepsItsPlace() {
        val first = payload(stopped(21))
        val second = payload(stopped(22))
        val late = payload(stopped(23))

        assertEquals(listOf(late), pendingWithout("$first\n$second\n$late", listOf(first, second)))
        assertEquals(emptyList<String>(), pendingWithout("$first\n$second", listOf(first, second)))
        assertEquals(emptyList<String>(), pendingWithout(null, listOf(first)))
    }

    /**
     * Stop, and the process is killed before Room is reached: all that is left is the payload, and
     * it has to give back the very row the Stop returned — capped mark and pauses with it.
     */
    @Test
    fun aWaitingPayloadGivesBackTheRowTheStopReturned() {
        val entry = stopped(22)
        assertEquals(entry, pendingWorkEntry(payload(entry), title, zone))
        assertEquals(1, workPauseCount(pendingWorkEntry(payload(entry), title, zone)!!))

        val capped = WorkSession().start(at(22, 6)).session.stop(at(23, 6), title, zone).entry!!
        val again = pendingWorkEntry(payload(capped), title, zone)!!
        assertEquals(capped, again)
        assertTrue(workFlag(again, WORK_CAPPED))

        // A payload that reads as under a minute, or not at all, leaves no row and is dropped.
        assertNull(pendingWorkEntry("not json", title, zone))
    }

    /**
     * Killed between writing the row and forgetting its payload, the next run finds the row by its
     * start and does not write a second one.
     */
    @Test
    fun aRowWrittenBeforeAKillIsFoundByItsStart() {
        val entry = stopped(22)
        val waiting = pendingWorkEntry(payload(entry), title, zone)!!
        assertNull(workRowAt(emptyList(), waiting.occurredAt))

        val journal = listOf(stopped(21).copy(id = 4L), entry.copy(id = 5L))
        assertEquals(5L, workRowAt(journal, waiting.occurredAt)?.id)
        // A row of another kind at the same moment is not the session, nor is work begun later.
        assertNull(workRowAt(listOf(entry.copy(kind = KIND_MOOD)), waiting.occurredAt))
        assertNull(workRowAt(listOf(entry.copy(occurredAt = entry.occurredAt + 1)), waiting.occurredAt))
    }

    /**
     * Two Stops, and the process dies before Room is reached; the screen opens and writes the queue.
     * Then it dies again, after the rows and before the payloads are forgotten, and the queue is
     * written once more. However often that happens, the journal holds each session once.
     */
    @Test
    fun aQueueWrittenTwiceLeavesEachSessionOneRow() = runBlocking {
        val first = stopped(21)
        val second = stopped(22)
        val queue = pendingWith(pendingWith(null, payload(first)), payload(second))
        val journal = mutableListOf<JournalEntry>()
        suspend fun write(payloads: List<String>) = writePendingRows(
            payloads,
            title,
            zone,
            rowsOn = { day -> journal.filter { it.day == day } },
            save = { journal += it.copy(id = journal.size + 1L) },
        )

        write(decodePending(queue))
        assertEquals(listOf(first.copy(id = 1L), second.copy(id = 2L)), journal)
        write(decodePending(queue))
        assertEquals(2, journal.size)
        // What cannot be read as a row is passed over, and the rest of the queue is still written.
        write(listOf("not json", payload(stopped(23))))
        assertEquals(listOf(21, 22, 23).map { at(it, 9) }, journal.map(JournalEntry::occurredAt))
        assertEquals(emptyList<String>(), pendingWithout(queue, decodePending(queue)))
    }

    /**
     * Undo on an automatic stop, and the process killed after the session went back on the clock
     * but before its row was deleted: the old row is still there when the session is stopped again,
     * at the same start. The new row takes its place under the old id; passed over as a row already
     * written, it would lose every minute worked since the Undo.
     */
    @Test
    fun aStopAfterAnUndoCutShortReplacesTheRowItsSessionCameBackFrom() = runBlocking {
        val old = stopped(22, endHour = 16).copy(id = 5L)
        val again = reopenedSession(workSegmentsOf(old), at(22, 16, 30))!!.stop(at(22, 18), title, zone).entry!!
        val journal = mutableListOf(old)
        writePendingRows(
            listOf(payload(again)),
            title,
            zone,
            rowsOn = { day -> journal.filter { it.day == day } },
            save = { entry -> journal.replaceAll { if (it.id == entry.id) entry else it } },
        )
        assertEquals(listOf(again.copy(id = 5L)), journal)
    }

    /** Undo on an automatic stop: the row's stretches go back on the clock, the last one open again. */
    @Test
    fun aReopenedSessionRunsOnFromItsLastStretch() {
        val entry = stopped(22, endHour = 16)
        val now = at(22, 16, 30)
        val open = reopenedSession(workSegmentsOf(entry), now)!!

        assertEquals(WorkState.RUNNING, open.state)
        assertEquals(entry.occurredAt, open.sessionStart)
        assertEquals(listOf(WorkSegment(at(22, 9), at(22, 12))), open.closed)
        assertEquals(at(22, 13), open.openSince)
        assertEquals(now, open.changedAt)
        // The half hour since the stop counts as if the stop had never been.
        assertEquals(6 * hour + hour / 2, open.netMs(now))
        // Stopped again, it is the same session at the same start: one row, not two.
        assertEquals(entry.occurredAt, open.stop(at(22, 18), title, zone).entry!!.occurredAt)

        assertNull(reopenedSession(emptyList(), now))
    }

    /** The Undo puts the session back only on an idle timer: one started or paused since is the person's. */
    @Test
    fun anUndoOnAStopLeavesASessionStartedSinceAlone() {
        val open = reopenedSession(workSegmentsOf(stopped(22, endHour = 16)), at(22, 16, 30))!!

        assertEquals(WorkStep(open, moved = true), WorkSession(changedAt = at(22, 16)).reopenedWith(open))
        val since = WorkSession().start(at(22, 16, 10)).session
        assertEquals(WorkStep(since, moved = false), since.reopenedWith(open))
        val paused = since.pause(at(22, 16, 20)).session
        assertEquals(WorkStep(paused, moved = false), paused.reopenedWith(open))
        // A row with nothing to put back leaves even an idle timer as it was.
        val idle = WorkSession(changedAt = at(22, 16))
        assertEquals(WorkStep(idle, moved = false), idle.reopenedWith(null))
    }

    @Test
    fun theForgottenStopCheckAsksAtTenHoursAndStopsAtSixteen() {
        assertEquals(OverrunStep.WAIT, overrunStep(WORK_REMIND_AFTER_MS - 1, remind = true))
        assertEquals(OverrunStep.ASK, overrunStep(WORK_REMIND_AFTER_MS, remind = true))
        assertEquals(OverrunStep.ASK, overrunStep(WORK_CAP_MS - 1, remind = true))
        // With reminders off there is nobody to ask, and the check only waits for the cap.
        assertEquals(OverrunStep.WAIT, overrunStep(WORK_REMIND_AFTER_MS, remind = false))
        // At the cap it stops whether or not anybody could be asked.
        assertEquals(OverrunStep.STOP, overrunStep(WORK_CAP_MS, remind = false))
        assertEquals(OverrunStep.STOP, overrunStep(WORK_CAP_MS + hour, remind = true))
    }
}
