package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * A workplace check after its fix: what the decision does to the timer and the memory when a tap,
 * an Undo or the switch got there first, and the notice it leaves ([carryOutWorkCheck]); and the
 * session the app started that is still running the next working day. The timer and the memory
 * are fakes here; WorkPlaceStoreTest has the memory's own compare-and-write.
 *
 * 2026-10-05 is a Monday.
 */
class WorkPlaceCheckTest {
    private val zone: ZoneId = ZoneId.of("Europe/Prague")
    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = monday.plusDays(1)

    private fun at(day: LocalDate, hour: Int, min: Int = 0): Long =
        day.atTime(hour, min).atZone(zone).toInstant().toEpochMilli()

    private fun mon(hour: Int, min: Int = 0) = at(monday, hour, min)

    private val idle = WorkSession()

    private fun running(start: Long) = WorkSession(WorkState.RUNNING, start, emptyList(), start, start)

    /**
     * The memory and the timer as a check meets them: [wins] whether the memory is still what the
     * check read, [started] what the timer's Start gives back, [stopped] what its Stop does.
     */
    private class Rig(val wins: Boolean, val started: WorkSession? = null, val stopped: WorkStep? = null) {
        val written = mutableListOf<WorkAutoMemory>()
        val stops = mutableListOf<Pair<Long, Long>>()

        suspend fun carry(outcome: WorkAutoOutcome, session: WorkSession): WorkCheckNotice? = carryOutWorkCheck(
            outcome,
            session,
            saveIf = { memory -> wins.also { if (it) written += memory } },
            autoStart = { started },
            autoStop = { start, end ->
                stops += start to end
                checkNotNull(stopped)
            },
        )
    }

    private val arrival = decideWorkCheck(WorkAutoMemory(), idle, PlaceReading.INSIDE, mon(8, 50), monday)

    /** Away once already: this check is the second, and the session the app started ends at five. */
    private fun departure(start: Long): WorkAutoOutcome = decideWorkCheck(
        WorkAutoMemory(ownStart = start, lastInside = mon(17), awayChecks = 1),
        running(start),
        PlaceReading.AWAY,
        mon(17, 20),
        monday,
    )

    @Test
    fun anArrivalWhileThePersonStartedTheTimerMeanwhileStartsNothingAndClaimsNothing() = runBlocking {
        val rig = Rig(wins = true, started = null)
        assertNull(rig.carry(arrival, idle))
        // The session running is the person's: the memory does not call it the app's own.
        assertEquals(listOf(WorkAutoMemory()), rig.written)
    }

    @Test
    fun aStartOffersItsUndoOnlyWhileTheMemoryIsStillTheChecks() = runBlocking {
        val begun = running(mon(8, 50))
        val followed = Rig(wins = true, started = begun)
        assertEquals(WorkCheckNotice.Started(mon(8, 50), undo = true), followed.carry(arrival, idle))
        assertEquals(listOf(WorkAutoMemory(ownStart = mon(8, 50), lastInside = mon(8, 50))), followed.written)

        // A tap during the fix: the session started, but it is the person's now, and so is its end.
        val tapped = Rig(wins = false, started = begun)
        assertEquals(WorkCheckNotice.Started(mon(8, 50), undo = false), tapped.carry(arrival, idle))
        assertEquals(emptyList<WorkAutoMemory>(), tapped.written)
    }

    @Test
    fun aStopLostToATapLeavesTheSessionRunningAndSaysNothing() = runBlocking {
        val start = mon(8, 50)
        val outcome = departure(start)
        assertEquals(WorkAutoAct.Stop(mon(17)), outcome.act)

        val rig = Rig(wins = false, stopped = running(start).stop(mon(17), "Work", zone))
        assertNull(rig.carry(outcome, running(start)))
        assertEquals(emptyList<Pair<Long, Long>>(), rig.stops)
    }

    @Test
    fun aStopEndsTheAppsSessionAtTheLastCheckInsideAndOffersUndoOnlyForARow() = runBlocking {
        val start = mon(8, 50)
        val session = running(start)
        val outcome = departure(start)

        val rig = Rig(wins = true, stopped = session.stop(mon(17), "Work", zone))
        assertEquals(WorkCheckNotice.Stopped(start, mon(17), kept = true), rig.carry(outcome, session))
        assertEquals(listOf(start to mon(17)), rig.stops)
        assertEquals(listOf(outcome.memory), rig.written)

        // Under a minute there is no row, so nothing for an Undo to put back.
        val short = Rig(wins = true, stopped = WorkStep(idle, moved = true))
        assertEquals(WorkCheckNotice.Stopped(start, mon(17), kept = false), short.carry(outcome, session))
        // The timer had moved on by then: nothing was stopped, and no notice says it was.
        val gone = Rig(wins = true, stopped = WorkStep(session, moved = false))
        assertNull(gone.carry(outcome, session))
    }

    /**
     * A session the app started in the evening and the person stayed past midnight: the checks
     * stop at midnight and come back when Tuesday's window opens. Gone by then, two checks away end
     * the session at the last check that found them there, last night; the day is not handed to
     * the person, so arriving later starts a new session. Still there — a night shift — it runs on.
     */
    @Test
    fun anOwnSessionLeftRunningOvernightEndsAtLastNightsLastCheckInside() {
        val start = mon(20, 50)
        val session = running(start)
        val lastNight = WorkAutoMemory(ownStart = start, lastInside = mon(23, 50))

        val once = decideWorkCheck(lastNight, session, PlaceReading.AWAY, at(tuesday, 7), tuesday)
        val stop = decideWorkCheck(once.memory, session, PlaceReading.AWAY, at(tuesday, 7, 10), tuesday)
        assertEquals(WorkAutoAct.Stop(mon(23, 50)), stop.act)
        assertEquals(WorkAutoMemory(stoppedStart = start, stoppedAt = at(tuesday, 7, 10)), stop.memory)
        val after = session.stop(mon(23, 50), "Work", zone).session
        val back = decideWorkCheck(stop.memory, after, PlaceReading.INSIDE, at(tuesday, 9), tuesday)
        assertEquals(WorkAutoAct.Start, back.act)

        val stayed = decideWorkCheck(lastNight, session, PlaceReading.INSIDE, at(tuesday, 7), tuesday)
        assertEquals(WorkAutoAct.None, stayed.act)
        assertEquals(lastNight.copy(lastInside = at(tuesday, 7)), stayed.memory)
    }
}
