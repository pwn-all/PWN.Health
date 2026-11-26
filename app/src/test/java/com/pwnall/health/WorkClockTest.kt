package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The work timer's rules, off the device: what a tap does from each state, what a bounced or stale
 * tap does not do, what a clock running backwards cannot break, where the cap cuts, how a night
 * shift is counted, and the exact bytes a stopped session is stored as.
 */
class WorkClockTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val title = "Work"

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun WorkSession.go(from: WorkState, to: WorkState, now: Long): WorkStep =
        transition(from, to, now, title, zone)

    private val minute = 60_000L
    private val hour = 3_600_000L

    @Test
    fun startPauseResumeStopKeepsTheWorkedStretchesAndOnlyThem() {
        var s = WorkSession()
        s = s.go(WorkState.IDLE, WorkState.RUNNING, at(22, 9)).also { assertTrue(it.moved) }.session
        s = s.go(WorkState.RUNNING, WorkState.PAUSED, at(22, 12, 30)).also { assertTrue(it.moved) }.session
        assertEquals(3 * hour + 30 * minute, s.netMs(at(22, 13, 15)))
        s = s.go(WorkState.PAUSED, WorkState.RUNNING, at(22, 13, 15)).also { assertTrue(it.moved) }.session
        assertEquals(WorkState.RUNNING, s.state)
        val stop = s.go(WorkState.RUNNING, WorkState.IDLE, at(22, 17, 45))

        assertTrue(stop.moved)
        assertEquals(WorkSession(changedAt = at(22, 17, 45)), stop.session)
        assertNotNull(stop.entry)
        val entry = stop.entry!!
        assertEquals(KIND_WORK, entry.kind)
        assertEquals("2026-09-22", entry.day)
        assertEquals(at(22, 9), entry.occurredAt)
        assertEquals(8.0 * 60, entry.durationMinutes!!, 0.0)
        assertEquals(0.0, entry.calories, 0.0)
        assertEquals(title, entry.title)
        assertEquals(
            listOf(WorkSegment(at(22, 9), at(22, 12, 30)), WorkSegment(at(22, 13, 15), at(22, 17, 45))),
            workSegmentsOf(entry),
        )
        assertEquals(1, workPauseCount(entry))
    }

    /** Nothing a work row carries is a column the Health Connect export picks rows by. */
    @Test
    fun aWorkRowFillsNoColumnTheExportReads() {
        val entry = workEntry(listOf(WorkSegment(at(22, 9), at(22, 17))), title, zone)!!
        assertNull(entry.sleepStartAt)
        assertNull(entry.sleepEndAt)
        assertNull(entry.measurementHeartRateBpm)
        assertNull(entry.measurementWeightKg)
        assertNull(entry.activityCode)
        listOf("systolicMmhg", "spo2Percent", "temperatureCelsius", "glucose").forEach {
            assertFalse(it, entry.measurementPayloadJson!!.contains(it))
        }
    }

    @Test
    fun aTapFromTheWrongStateDoesNothing() {
        val idle = WorkSession()
        val running = idle.start(at(22, 9)).session
        val paused = running.pause(at(22, 10)).session
        val later = at(22, 11)

        // The helpers themselves refuse a state they do not leave.
        assertFalse(running.start(later).moved)
        assertFalse(paused.pause(later).moved)
        assertFalse(idle.stop(later, title, zone).moved)
        assertFalse(running.resume(later).moved)
        assertFalse(idle.pause(later).moved)

        // And a face that is out of date names a state the session is no longer in.
        assertFalse(running.go(WorkState.IDLE, WorkState.RUNNING, later).moved)
        assertFalse(paused.go(WorkState.RUNNING, WorkState.PAUSED, later).moved)
        assertFalse(idle.go(WorkState.RUNNING, WorkState.IDLE, later).moved)
        // A stale Pause on a session already paused must not resume it.
        assertEquals(paused, paused.go(WorkState.RUNNING, WorkState.PAUSED, later).session)
        // Stop is the one edge whose own helper leaves any live state, so only the from-state
        // guard keeps a Stop drawn for the other live state from ending the session.
        listOf(running.go(WorkState.PAUSED, WorkState.IDLE, later), paused.go(WorkState.RUNNING, WorkState.IDLE, later))
            .forEach { stale ->
                assertFalse(stale.moved)
                assertNull(stale.entry)
            }
        assertEquals(running, running.go(WorkState.PAUSED, WorkState.IDLE, later).session)
        // Edges that do not exist move nothing.
        assertFalse(idle.go(WorkState.IDLE, WorkState.PAUSED, later).moved)
        assertFalse(running.go(WorkState.RUNNING, WorkState.RUNNING, later).moved)
    }

    @Test
    fun aSecondTapInsideTheBounceWindowIsTheSameTap() {
        val running = WorkSession().go(WorkState.IDLE, WorkState.RUNNING, at(22, 9)).session
        // The new face's Pause, hit by the same finger a moment later.
        assertFalse(running.go(WorkState.RUNNING, WorkState.PAUSED, at(22, 9) + 1_000).moved)
        assertFalse(running.go(WorkState.RUNNING, WorkState.IDLE, at(22, 9) + WORK_BOUNCE_MS - 1).moved)
        assertTrue(running.go(WorkState.RUNNING, WorkState.PAUSED, at(22, 9) + WORK_BOUNCE_MS).moved)

        // A Start straight after a Stop is a bounce too.
        val stopped = running.go(WorkState.RUNNING, WorkState.IDLE, at(22, 10)).session
        assertFalse(stopped.go(WorkState.IDLE, WorkState.RUNNING, at(22, 10) + 500).moved)
        assertTrue(stopped.go(WorkState.IDLE, WorkState.RUNNING, at(22, 10) + 2_000).moved)
    }

    /** A clock set back by an hour must not lock the buttons for that hour. */
    @Test
    fun aLargeBackwardsJumpIsNotABounce() {
        val running = WorkSession().start(at(22, 9)).session
        assertTrue(running.go(WorkState.RUNNING, WorkState.PAUSED, at(22, 8)).moved)
    }

    @Test
    fun aClockGoingBackwardsNeverMakesANegativeOrOverlappingStretch() {
        val running = WorkSession().start(at(22, 9)).session
        // Paused "before" it started: a stretch of nothing, not a negative one.
        val paused = running.pause(at(22, 8)).session
        assertEquals(listOf(WorkSegment(at(22, 9), at(22, 9))), paused.closed)
        assertEquals(0L, paused.netMs(at(22, 8)))

        val worked = WorkSession().start(at(22, 9)).session.pause(at(22, 10)).session
        // Resumed "before" the last stretch ended: it resumes where that one ended.
        val resumed = worked.resume(at(22, 9, 30)).session
        assertEquals(at(22, 10), resumed.openSince)
        // And a running stretch read at a moment before it began counts as nothing.
        assertEquals(hour, resumed.netMs(at(22, 9, 45)))
        val entry = resumed.stop(at(22, 9, 50), title, zone).entry!!
        assertEquals(60.0, entry.durationMinutes!!, 0.0)
        workSegmentsOf(entry).forEach { assertTrue(it.to >= it.from) }
    }

    @Test
    fun underAMinuteWritesNothing() {
        val running = WorkSession().start(at(22, 9)).session
        val stop = running.stop(at(22, 9) + 59_999, title, zone)
        assertTrue(stop.moved)
        assertEquals(WorkState.IDLE, stop.session.state)
        assertNull(stop.entry)
        assertNotNull(running.stop(at(22, 9) + minute, title, zone).entry)
    }

    @Test
    fun theCapCutsTheSessionAtSixteenHoursAndSaysSo() {
        val running = WorkSession().start(at(22, 8)).session.pause(at(22, 12)).session.resume(at(22, 13)).session
        val stop = running.stop(at(23, 9), title, zone)
        val entry = stop.entry!!

        assertEquals(16.0 * 60, entry.durationMinutes!!, 0.0)
        assertTrue(workFlag(entry, WORK_CAPPED))
        assertFalse(workFlag(entry, WORK_TYPED))
        assertEquals(
            listOf(WorkSegment(at(22, 8), at(22, 12)), WorkSegment(at(22, 13), at(23, 1))),
            workSegmentsOf(entry),
        )
        // A session under the cap carries no mark at all, not a false one.
        val ordinary = WorkSession().start(at(22, 8)).session.stop(at(22, 16), title, zone).entry!!
        assertEquals("{\"schema\":1,\"segments\":[[${at(22, 8)},${at(22, 16)}]]}", ordinary.measurementPayloadJson)
    }

    @Test
    fun theForgottenStopRuleActsOnlyOnARunningSessionAtTheCap() {
        val running = WorkSession().start(at(22, 8)).session
        assertFalse(running.stopIfCapped(at(22, 8) + WORK_CAP_MS - 1, title, zone).moved)
        val capped = running.stopIfCapped(at(22, 8) + WORK_CAP_MS + 5 * minute, title, zone)
        assertTrue(capped.moved)
        val entry = capped.entry!!
        assertTrue(workFlag(entry, WORK_CAPPED))
        assertEquals(16.0 * 60, entry.durationMinutes!!, 0.0)

        // Paused, the net time is not growing, so there is nothing to stop.
        val paused = running.pause(at(22, 9)).session
        assertFalse(paused.stopIfCapped(at(24, 9), title, zone).moved)
    }

    @Test
    fun theLiveTotalsNeverShowMoreThanTheCap() {
        val running = WorkSession().start(at(22, 8)).session
        assertEquals(WORK_CAP_MS, running.liveSegments(at(23, 8)).sumOf { it.length })
        assertEquals(24 * hour, running.netMs(at(23, 8)))
    }

    @Test
    fun theCheckWaitsForTheReminderThenTheCap() {
        assertEquals(WORK_REMIND_AFTER_MS, overrunCheckDelayMs(0))
        assertEquals(WORK_REMIND_AFTER_MS - 3 * hour, overrunCheckDelayMs(3 * hour))
        assertEquals(WORK_CAP_MS - WORK_REMIND_AFTER_MS, overrunCheckDelayMs(WORK_REMIND_AFTER_MS))
        assertEquals(hour, overrunCheckDelayMs(WORK_CAP_MS - hour))
        assertEquals(0L, overrunCheckDelayMs(WORK_CAP_MS + hour))
    }

    /**
     * A night shift is one row on the day it started, and two days' worth of hours: 23:00 to 02:00
     * is an hour on the first date and two on the second.
     */
    @Test
    fun aSessionAcrossMidnightBelongsToItsStartDayAndSplitsForTheTotals() {
        val entry = WorkSession().start(at(22, 23)).session.stop(at(23, 2), title, zone).entry!!
        assertEquals("2026-09-22", entry.day)

        val byDay = workMinutesByDay(listOf(entry), zone)
        assertEquals(60.0, byDay[LocalDate.of(2026, 9, 22)]!!, 0.0)
        assertEquals(120.0, byDay[LocalDate.of(2026, 9, 23)]!!, 0.0)
    }

    @Test
    fun theTotalsCountTheRunningSessionAndNothingButWork() {
        val row = workEntry(listOf(WorkSegment(at(22, 9), at(22, 12))), title, zone)!!
        val other = row.copy(kind = KIND_MOOD)
        val live = WorkSession().start(at(22, 14)).session.liveSegments(at(22, 15, 30))
        val byDay = workMinutesByDay(listOf(row, other), zone, live)
        assertEquals(270.0, byDay[LocalDate.of(2026, 9, 22)]!!, 0.0)
    }

    /** The night the clocks go back has 25 hours, and a shift across it counts every one. */
    @Test
    fun aShiftAcrossTheClockChangeCountsTheHourItGains() {
        val from = LocalDateTime.of(2026, 10, 24, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val to = LocalDateTime.of(2026, 10, 25, 6, 0).atZone(zone).toInstant().toEpochMilli()
        val byDay = workMinutesByDay(listOf(workEntry(listOf(WorkSegment(from, to)), title, zone)!!), zone)
        assertEquals(120.0, byDay[LocalDate.of(2026, 10, 24)]!!, 0.0)
        assertEquals(420.0, byDay[LocalDate.of(2026, 10, 25)]!!, 0.0)
    }

    /**
     * The payload is part of the row's backup identity, so its bytes are pinned: one fixed order,
     * the flags written only when true and after the segments.
     */
    @Test
    fun thePayloadBytesAreStable() {
        val segments = listOf(WorkSegment(1_000L, 2_000L), WorkSegment(3_000L, 4_500L))
        assertEquals("{\"schema\":1,\"segments\":[[1000,2000],[3000,4500]]}", encodeWorkPayload(segments))
        assertEquals(
            "{\"schema\":1,\"segments\":[[1000,2000],[3000,4500]],\"capped\":true,\"typed\":true}",
            encodeWorkPayload(segments, capped = true, typed = true),
        )
        assertEquals(
            "{\"schema\":1,\"segments\":[[1000,2000],[3000,4500]],\"typed\":true}",
            encodeWorkPayload(segments, typed = true),
        )
        val payload = encodeWorkPayload(segments, capped = true)
        assertEquals(segments, decodePayloadSegments(payload))
        val again = encodeWorkPayload(decodePayloadSegments(payload), capped = workPayloadFlag(payload, WORK_CAPPED))
        assertEquals(payload, again)
    }

    @Test
    fun anUnreadablePayloadStillCountsAsOneStretch() {
        val row = JournalEntry(
            day = "2026-09-22",
            occurredAt = at(22, 9),
            kind = KIND_WORK,
            title = title,
            calories = 0.0,
            durationMinutes = 90.0,
            measurementPayloadJson = "not json",
        )
        assertEquals(listOf(WorkSegment(at(22, 9), at(22, 10, 30))), workSegmentsOf(row))
        assertEquals(0, workPauseCount(row))
        assertFalse(workFlag(row, WORK_CAPPED))
        assertEquals(emptyList<WorkSegment>(), decodePayloadSegments("{\"segments\":[[5,1],[1],\"x\"]}"))
    }

    @Test
    fun theStoredSessionReadsBackAndSkipsWhatItCannotRead() {
        val segments = listOf(WorkSegment(10L, 20L), WorkSegment(30L, 45L))
        assertEquals(segments, decodeStoredSegments(encodeWorkSegments(segments)))
        assertEquals(emptyList<WorkSegment>(), decodeStoredSegments(null))
        assertEquals(emptyList<WorkSegment>(), decodeStoredSegments(""))
        assertEquals(listOf(WorkSegment(1L, 2L)), decodeStoredSegments("1:2,garbage,9:3,4:"))
    }

    @Test
    fun daysOffStartAtTheWeekendAndReadBack() {
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), decodeDaysOff(null))
        val friday = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
        assertEquals("5,6", encodeDaysOff(friday))
        assertEquals(friday, decodeDaysOff(encodeDaysOff(friday)))
        // Every day worked is a choice too, and must not snap back to the weekend.
        assertEquals(emptySet<DayOfWeek>(), decodeDaysOff(encodeDaysOff(emptySet())))
        assertEquals(setOf(DayOfWeek.MONDAY), decodeDaysOff("1,9,x"))
    }

    /** Moving a day's ends keeps the lunch break in the middle of it. */
    @Test
    fun newEndsKeepThePausesStillInsideThem() {
        val segments = listOf(
            WorkSegment(at(22, 9), at(22, 12)),
            WorkSegment(at(22, 13), at(22, 16)),
            WorkSegment(at(22, 16, 30), at(23, 1)),
        )
        assertEquals(
            listOf(
                WorkSegment(at(22, 8), at(22, 12)),
                WorkSegment(at(22, 13), at(22, 16)),
                WorkSegment(at(22, 16, 30), at(22, 18)),
            ),
            reshapeSegments(segments, at(22, 8), at(22, 18)),
        )
        // An end moved into the second pause is the end kept: that pause goes, not the time picked.
        assertEquals(
            listOf(WorkSegment(at(22, 10), at(22, 12)), WorkSegment(at(22, 13), at(22, 16, 15))),
            reshapeSegments(segments, at(22, 10), at(22, 16, 15)),
        )
        // A start moved into the lunch break starts there; the break it cut into is gone.
        assertEquals(
            listOf(WorkSegment(at(22, 12, 30), at(22, 16)), WorkSegment(at(22, 16, 30), at(22, 17))),
            reshapeSegments(segments, at(22, 12, 30), at(22, 17)),
        )
        assertEquals(emptyList<WorkSegment>(), reshapeSegments(segments, at(22, 10), at(22, 10)))
    }

    @Test
    fun aSessionSetByHandMustBeOverAndMustNotOverlapAnother() {
        val now = at(22, 16)
        val quiet = WorkSession()
        val morning = listOf(WorkSegment(at(22, 9), at(22, 12)))
        // An end still to come is refused, but not one rounded up to the dial's next mark.
        assertEquals(
            WorkSaveProblem.FUTURE,
            workSaveProblem(listOf(WorkSegment(at(22, 9), at(22, 17))), null, emptyList(), quiet, now),
        )
        assertNull(
            workSaveProblem(listOf(WorkSegment(at(22, 9), now + WORK_FUTURE_GRACE_MS)), null, emptyList(), quiet, now),
        )
        assertNull(workSaveProblem(morning, null, emptyList(), quiet, now))

        // Another row's worked time is refused; its lunch break, or a session ending as it begins, is not.
        val row = workEntry(
            listOf(WorkSegment(at(21, 9), at(21, 12)), WorkSegment(at(21, 13), at(21, 17))),
            title,
            zone,
        )!!.copy(id = 7L)
        val into = listOf(WorkSegment(at(21, 11), at(21, 14)))
        assertEquals(WorkSaveProblem.OVERLAP, workSaveProblem(into, null, listOf(row), quiet, now))
        assertNull(workSaveProblem(listOf(WorkSegment(at(21, 12), at(21, 13))), null, listOf(row), quiet, now))
        assertNull(workSaveProblem(listOf(WorkSegment(at(21, 7), at(21, 9))), null, listOf(row), quiet, now))
        // The row being edited is not in its own way, and rows of another kind are not work.
        assertNull(workSaveProblem(into, row.id, listOf(row), quiet, now))
        assertNull(workSaveProblem(into, null, listOf(row.copy(kind = KIND_MOOD)), quiet, now))

        // The session still on the clock counts, up to now and not beyond.
        val running = WorkSession().start(at(22, 14)).session
        assertEquals(
            WorkSaveProblem.OVERLAP,
            workSaveProblem(listOf(WorkSegment(at(22, 13), at(22, 15))), null, emptyList(), running, now),
        )
        assertNull(workSaveProblem(morning, null, emptyList(), running, now))
        assertNull(workSaveProblem(emptyList(), null, listOf(row), running, now))
    }

    @Test
    fun anEndBeforeTheStartIsTheNextMorning() {
        val (start, end) = workEpochRange(LocalDate.of(2026, 9, 22), 22 * 60, 6 * 60, zone)
        assertEquals(at(22, 22), start)
        assertEquals(at(23, 6), end)
        val (same, sameEnd) = workEpochRange(LocalDate.of(2026, 9, 22), 9 * 60, 9 * 60, zone)
        assertEquals(same, sameEnd)
    }

    /**
     * A Resume the morning after a forgotten Stop — the widget draws it like Start — does not join
     * two working days into one row: the paused day is written as it stood, and the tap starts anew.
     */
    @Test
    fun aResumeAfterANightAwayEndsTheOldSessionAndStartsANewOne() {
        val paused = WorkSession().start(at(21, 9)).session.pause(at(21, 18)).session
        val step = paused.go(WorkState.PAUSED, WorkState.RUNNING, at(22, 9))

        assertTrue(step.moved)
        val entry = step.entry!!
        assertEquals("2026-09-21", entry.day)
        assertEquals(9.0 * 60, entry.durationMinutes!!, 0.0)
        assertEquals(listOf(WorkSegment(at(21, 9), at(21, 18))), workSegmentsOf(entry))
        assertFalse(workFlag(entry, WORK_CAPPED))
        assertEquals(WorkSession(WorkState.RUNNING, at(22, 9), emptyList(), at(22, 9), at(22, 9)), step.session)

        // A pause shorter than a night is a pause, across midnight too: a night shift's break.
        val shift = WorkSession().start(at(21, 22)).session.pause(at(21, 23, 50)).session
        val resumed = shift.go(WorkState.PAUSED, WorkState.RUNNING, at(22, 0, 10))
        assertNull(resumed.entry)
        assertEquals(at(21, 22), resumed.session.sessionStart)
        assertEquals(at(22, 0, 10), resumed.session.openSince)

        assertFalse(paused.pausedTooLong(at(21, 18) + WORK_PAUSE_MAX_MS - 1))
        assertTrue(paused.pausedTooLong(at(21, 18) + WORK_PAUSE_MAX_MS))
        // Only a pause can outlast itself: a session left running is the cap's to end.
        assertFalse(WorkSession().start(at(21, 9)).session.pausedTooLong(at(23, 9)))
    }

    /**
     * A session's page holds one turn of the clock. A row a day long or longer is drawn as its first
     * day alone and saved as that, so its page has to know to say so.
     */
    @Test
    fun aSessionADayLongOrLongerOutgrowsItsPage() {
        val twoDays = listOf(WorkSegment(at(21, 9), at(21, 18)), WorkSegment(at(22, 9), at(22, 16)))
        assertTrue(workOutgrowsPage(twoDays))
        // What its page saves as it opens: the day it started, up to the end's time of day.
        val (start, end) = workEpochRange(LocalDate.of(2026, 9, 21), 9 * 60, 16 * 60, zone)
        assertEquals(listOf(WorkSegment(at(21, 9), at(21, 16))), reshapeSegments(twoDays, start, end))

        assertTrue(workOutgrowsPage(listOf(WorkSegment(at(21, 9), at(22, 9)))))
        assertFalse(workOutgrowsPage(listOf(WorkSegment(at(21, 22), at(22, 6)))))
        val lateEnd = listOf(WorkSegment(at(21, 9), at(21, 12)), WorkSegment(at(21, 13), at(22, 8, 59)))
        assertFalse(workOutgrowsPage(lateEnd))
        assertFalse(workOutgrowsPage(emptyList()))
    }

    /**
     * A stretch out of a damaged or hand-edited backup that never ends is not walked one midnight at
     * a time, which would hold the Work screen for as long as the row exists: it is not read, and
     * the row falls back on its own minutes.
     */
    @Test(timeout = 2_000)
    fun aStretchNobodyCouldHaveWorkedIsNotRead() {
        val endless = "{\"schema\":1,\"segments\":[[0,9223372036854775807]]}"
        assertEquals(emptyList<WorkSegment>(), decodePayloadSegments(endless))
        // Microseconds where milliseconds belong, and a start before any clock this app has seen.
        val micro = "{\"segments\":[[${at(22, 9) * 1_000},${at(22, 17) * 1_000}]]}"
        assertEquals(emptyList<WorkSegment>(), decodePayloadSegments(micro))
        assertEquals(emptyList<WorkSegment>(), decodePayloadSegments("{\"segments\":[[-5,5]]}"))
        // Two days is believed, and not a millisecond more.
        val longest = listOf(WorkSegment(at(22, 9), at(22, 9) + WORK_SEGMENT_MAX_MS))
        assertEquals(longest, decodePayloadSegments(encodeWorkPayload(longest)))
        val longer = listOf(WorkSegment(at(22, 9), at(22, 9) + WORK_SEGMENT_MAX_MS + 1))
        assertEquals(emptyList<WorkSegment>(), decodePayloadSegments(encodeWorkPayload(longer)))

        val row = JournalEntry(
            day = "2026-09-22",
            occurredAt = at(22, 9),
            kind = KIND_WORK,
            title = title,
            calories = 0.0,
            durationMinutes = 480.0,
            measurementPayloadJson = endless,
        )
        assertEquals(listOf(WorkSegment(at(22, 9), at(22, 17))), workSegmentsOf(row))
        assertEquals(mapOf(LocalDate.of(2026, 9, 22) to 480.0), workMinutesByDay(listOf(row), zone))
        // Minutes no day could hold are no stretch either: the row counts for nothing.
        val absurd = row.copy(durationMinutes = 1e15)
        assertEquals(emptyList<WorkSegment>(), workSegmentsOf(absurd))
        assertTrue(workMinutesByDay(listOf(absurd), zone).isEmpty())
    }

    @Test
    fun aTypedSessionIsMarkedAndKeepsItsOwnDay() {
        val (start, end) = workEpochRange(LocalDate.of(2026, 9, 20), 9 * 60, 17 * 60, zone)
        val entry = workEntry(listOf(WorkSegment(start, end)), title, zone, typed = true)!!
        assertEquals("2026-09-20", entry.day)
        assertEquals(start, entry.occurredAt)
        assertTrue(workFlag(entry, WORK_TYPED))
    }

    @Test
    fun theStopwatchReadsHoursMinutesAndSeconds() {
        assertEquals("0:00:00", stopwatchText(-5))
        assertEquals("0:01:05", stopwatchText(65_000))
        assertEquals("16:00:00", stopwatchText(WORK_CAP_MS))
    }
}
