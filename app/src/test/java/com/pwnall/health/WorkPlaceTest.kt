package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The automatic start by location, off the device: what one fix says about the workplace, the
 * usual hours read from the sessions, the window each day's checks run in, when the alarm goes
 * next, and what arriving and leaving do to the timer — on a fake clock, with fake positions.
 *
 * 2026-10-05 is a Monday.
 */
class WorkPlaceTest {
    private val zone: ZoneId = ZoneId.of("Europe/Prague")
    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val monday = LocalDate.of(2026, 10, 5)
    private val minute = 60_000L

    private fun at(day: LocalDate, hour: Int, min: Int = 0): Long =
        LocalDateTime.of(day, LocalTime.of(hour, min)).atZone(zone).toInstant().toEpochMilli()

    private fun mon(hour: Int, min: Int = 0) = at(monday, hour, min)

    // A workplace in Prague, and positions measured off it along the meridian: 0.001° of latitude
    // is about 111 m.
    private val office = WorkPlace(lat = 50.0870, lon = 14.4210, radiusM = 150, accuracyM = 12.0, savedAt = 0L)
    private fun north(metres: Double, accuracy: Double = 15.0) =
        PlaceFix(office.lat + metres / 111_195.0, office.lon, accuracy)

    private fun session(day: LocalDate, from: Pair<Int, Int>, to: Pair<Int, Int>, capped: Boolean = false) =
        workEntry(
            listOf(WorkSegment(at(day, from.first, from.second), at(day, to.first, to.second))),
            "Work",
            zone,
            capped = capped,
        )!!

    // --- one fix ---

    @Test
    fun aFixInsideTheRadiusAndSureEnoughIsInside() {
        assertEquals(PlaceReading.INSIDE, placeReading(office, north(0.0)))
        assertEquals(PlaceReading.INSIDE, placeReading(office, north(140.0, accuracy = 40.0)))
        // Accuracy up to twice the radius still counts: a phone indoors rarely does better.
        assertEquals(PlaceReading.INSIDE, placeReading(office, north(60.0, accuracy = 300.0)))
    }

    @Test
    fun aWideFixThatHappensToCentreOnTheOfficeProvesNothing() {
        assertEquals(PlaceReading.UNSURE, placeReading(office, north(20.0, accuracy = 1_600.0)))
    }

    @Test
    fun awayOnlyWhenEvenTheNearEdgeOfTheFixIsOutside() {
        assertEquals(PlaceReading.AWAY, placeReading(office, north(1_000.0, accuracy = 30.0)))
        // 200 m out, but sure only to 80 m: the near edge, at 120 m, is still inside.
        assertEquals(PlaceReading.UNSURE, placeReading(office, north(200.0, accuracy = 80.0)))
        assertEquals(PlaceReading.AWAY, placeReading(office, north(5_000.0, accuracy = 1_600.0)))
    }

    @Test
    fun noFixAndAFixWithNoAccuracyAreUnsure() {
        assertEquals(PlaceReading.UNSURE, placeReading(office, null))
        assertEquals(PlaceReading.UNSURE, placeReading(office, north(5_000.0, accuracy = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun distanceIsMetresAndFoldsAcrossTheAntimeridian() {
        assertEquals(111.2, equirectangularMetres(50.0, 14.0, 50.001, 14.0), 0.5)
        // 179.9995° E to 179.9995° W is a thousandth of a degree at the equator, not most of the globe.
        assertEquals(111.2, equirectangularMetres(0.0, 179.9995, 0.0, -179.9995), 0.5)
    }

    // --- the usual hours ---

    @Test
    fun theUsualHoursNeedThreeWorkingDaySessions() {
        val two = listOf(
            session(monday.minusDays(7), 9 to 0, 17 to 0),
            session(monday.minusDays(6), 9 to 0, 17 to 0),
        )
        assertNull(usualWorkHours(two, monday, zone, weekend))
        val three = two + session(monday.minusDays(5), 9 to 0, 17 to 0)
        assertEquals(UsualHours(9 * 60, 17 * 60), usualWorkHours(three, monday, zone, weekend))
    }

    @Test
    fun theUsualDayIsWideButOneOddSessionDoesNotStretchIt() {
        val rows = listOf(
            session(monday.minusDays(7), 8 to 30, 17 to 0),
            session(monday.minusDays(6), 9 to 0, 17 to 30),
            session(monday.minusDays(5), 9 to 0, 18 to 0),
            session(monday.minusDays(4), 9 to 15, 17 to 45),
            // A one-off start before dawn and a late finish.
            session(monday.minusDays(3), 5 to 0, 22 to 0),
        )
        // Lower quartile of five starts is the second; upper quartile of five ends the fourth.
        assertEquals(UsualHours(8 * 60 + 30, 18 * 60), usualWorkHours(rows, monday, zone, weekend))
    }

    @Test
    fun daysOffCappedSessionsTodayAndOldSessionsAreLeftOut() {
        val counted = listOf(
            session(monday.minusDays(7), 9 to 0, 17 to 0),
            session(monday.minusDays(6), 9 to 0, 17 to 0),
            session(monday.minusDays(5), 9 to 0, 17 to 0),
        )
        val noise = listOf(
            session(monday.minusDays(2), 6 to 0, 23 to 0), // a Saturday
            session(monday.minusDays(4), 1 to 0, 17 to 0, capped = true),
            session(monday, 4 to 0, 23 to 0), // today itself
            session(monday.minusDays(29), 4 to 0, 23 to 0), // past the four weeks
        )
        assertEquals(UsualHours(9 * 60, 17 * 60), usualWorkHours(counted + noise, monday, zone, weekend))
    }

    @Test
    fun aSessionPastMidnightEndsAtMidnightForTheHours() {
        val nights = (7L downTo 5L).map { back ->
            workEntry(
                listOf(WorkSegment(at(monday.minusDays(back), 22), at(monday.minusDays(back - 1), 6))),
                "Work",
                zone,
            )!!
        }
        assertEquals(UsualHours(22 * 60, 24 * 60), usualWorkHours(nights, monday, zone, weekend))
    }

    // --- the day's window ---

    @Test
    fun aDayOffHasNoChecks() {
        assertNull(workCheckWindow(monday.plusDays(5), zone, weekend, null, null, wakeReadable = false))
    }

    @Test
    fun withNeitherSignalTheDayRunsFromSevenToNine() {
        val window = workCheckWindow(monday, zone, weekend, null, null, wakeReadable = false)!!
        assertEquals(mon(7), window.open)
        assertEquals(mon(7), window.lookFrom)
        assertEquals(mon(21), window.close)
        assertEquals(at(monday.plusDays(1), 0), window.dayEnd)
    }

    /**
     * The hours are the clock's. Counted on in minutes from midnight, the window of the morning the
     * clocks go forward would open at eight, and the autumn one's at six.
     */
    @Test
    fun onTheDaysTheClocksChangeSevenIsStillSeven() {
        listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25)).forEach { day ->
            val window = workCheckWindow(day, zone, emptySet(), null, null, wakeReadable = false)!!
            assertEquals(at(day, 7), window.open)
            assertEquals(at(day, 21), window.close)
            assertEquals(at(day.plusDays(1), 0), window.dayEnd)
            assertEquals(at(day, 6), morningWake(recorded = null, phone = at(day, 4, 30), day, zone, hours = null))
        }
    }

    /** What the alarm built is handed to the service's reschedule only inside the same round and day. */
    @Test
    fun theWindowsAnAlarmBuiltServeOnlyItsOwnRound() {
        assertTrue(roundWindowsHold(mon(9), mon(9) + WORK_FIX_TIMEOUT_MS + 20_000L, zone))
        assertFalse(roundWindowsHold(mon(9), mon(9, 10), zone))
        // A fix that ran past midnight: the new day's wake was never read.
        assertFalse(roundWindowsHold(mon(23, 59), at(monday.plusDays(1), 0) + 10_000L, zone))
        // A clock set back since.
        assertFalse(roundWindowsHold(mon(9), mon(9) - 1L, zone))
    }

    @Test
    fun aWakeTheAppCanSeeButHasNotSeenIsWaitedForUntilTen() {
        val window = workCheckWindow(monday, zone, weekend, null, null, wakeReadable = true)!!
        assertEquals(mon(5), window.lookFrom)
        assertEquals(mon(10), window.open)
    }

    @Test
    fun theWakeOpensTheWindowWithoutUsualHours() {
        val window = workCheckWindow(monday, zone, weekend, null, mon(6, 12), wakeReadable = true)!!
        assertEquals(mon(6, 12), window.open)
        assertEquals(mon(6, 12), window.lookFrom)
        assertEquals(mon(21), window.close)
    }

    @Test
    fun theUsualHoursNarrowTheDayButNeverOpenItBeforeTheWake() {
        val hours = UsualHours(9 * 60, 18 * 60)
        val early = workCheckWindow(monday, zone, weekend, hours, mon(6), wakeReadable = true)!!
        assertEquals(mon(8), early.open)
        assertEquals(mon(19), early.close)
        val late = workCheckWindow(monday, zone, weekend, hours, mon(9, 30), wakeReadable = true)!!
        assertEquals(mon(9, 30), late.open)
        // Not yet seen awake: the look for the wake starts with the hours, the checks at ten at the latest.
        val unseen = workCheckWindow(monday, zone, weekend, hours, null, wakeReadable = true)!!
        assertEquals(mon(8), unseen.lookFrom)
        assertEquals(mon(10), unseen.open)
        // No way to see the wake: the hours open it, from seven at the earliest.
        val blind = workCheckWindow(monday, zone, weekend, hours, null, wakeReadable = false)!!
        assertEquals(mon(8), blind.open)
        assertEquals(mon(8), blind.lookFrom)
    }

    @Test
    fun aWakeAfterTheUsualEndLeavesNoWindowAndALateDayClosesAtMidnight() {
        assertNull(workCheckWindow(monday, zone, weekend, UsualHours(8 * 60, 16 * 60), mon(18), wakeReadable = true))
        val late = workCheckWindow(monday, zone, weekend, UsualHours(14 * 60, 23 * 60 + 30), mon(9), true)!!
        assertEquals(mon(13), late.open)
        assertEquals(at(monday.plusDays(1), 0), late.close)
    }

    @Test
    fun aSessionTheAppStartedIsFollowedPastTheCloseUntilMidnight() {
        val window = workCheckWindow(monday, zone, weekend, null, null, wakeReadable = false)!!
        assertFalse(window.covers(mon(6, 59), ownRunning = false))
        assertTrue(window.covers(mon(7), ownRunning = false))
        assertFalse(window.covers(mon(21, 10), ownRunning = false))
        assertTrue(window.covers(mon(23, 50), ownRunning = true))
    }

    // --- the next alarm ---

    private val plain: (LocalDate) -> WorkCheckWindow? =
        { day -> workCheckWindow(day, zone, weekend, null, null, wakeReadable = false) }

    @Test
    fun beforeTheWindowTheAlarmIsSetForItsStart() {
        assertEquals(mon(7), nextWorkCheckAt(mon(3), zone, plain, ownRunning = false, watching = true, lastLookAt = 0L))
    }

    @Test
    fun insideTheWindowTheRoundIsTenMinutesFromTheLastLook() {
        assertEquals(
            mon(9, 10),
            nextWorkCheckAt(mon(9), zone, plain, ownRunning = false, watching = true, lastLookAt = mon(9)),
        )
        // Opening the Work screen three minutes after a look does not add a check.
        assertEquals(
            mon(9, 10),
            nextWorkCheckAt(mon(9, 3), zone, plain, ownRunning = false, watching = true, lastLookAt = mon(9)),
        )
        // Long after the last look, now.
        assertEquals(
            mon(12),
            nextWorkCheckAt(mon(12), zone, plain, ownRunning = false, watching = true, lastLookAt = mon(9)),
        )
        // A last look ahead of the clock — it was set back since — is a look made now, not a round
        // that stands still until the clock has caught up.
        assertEquals(
            mon(9, 10),
            nextWorkCheckAt(mon(9), zone, plain, ownRunning = false, watching = true, lastLookAt = mon(9, 55)),
        )
    }

    @Test
    fun beforeTheWakeIsSeenTheLooksAreTenMinutesApart() {
        val waiting: (LocalDate) -> WorkCheckWindow? =
            { day -> workCheckWindow(day, zone, weekend, null, null, wakeReadable = true) }
        assertEquals(mon(5), nextWorkCheckAt(mon(2), zone, waiting, false, true, lastLookAt = 0L))
        assertEquals(mon(6, 10), nextWorkCheckAt(mon(6), zone, waiting, false, true, lastLookAt = mon(6)))
    }

    @Test
    fun afterTheWindowOrOnADayTakenOverTheAlarmGoesToTheNextWorkingDay() {
        assertEquals(
            at(monday.plusDays(1), 7),
            nextWorkCheckAt(mon(21, 5), zone, plain, ownRunning = false, watching = true, lastLookAt = mon(21)),
        )
        assertEquals(
            at(monday.plusDays(1), 7),
            nextWorkCheckAt(mon(10), zone, plain, ownRunning = false, watching = false, lastLookAt = mon(9, 55)),
        )
        // Friday evening: Saturday and Sunday are days off, so Monday.
        val friday = monday.plusDays(4)
        assertEquals(
            at(monday.plusDays(7), 7),
            nextWorkCheckAt(at(friday, 22), zone, plain, ownRunning = false, watching = true, lastLookAt = 0L),
        )
    }

    @Test
    fun aSessionTheAppStartedKeepsTheRoundGoingAfterTheClose() {
        assertEquals(
            mon(22, 10),
            nextWorkCheckAt(mon(22), zone, plain, ownRunning = true, watching = true, lastLookAt = mon(22)),
        )
        assertEquals(
            at(monday.plusDays(1), 7),
            nextWorkCheckAt(mon(23, 55), zone, plain, ownRunning = true, watching = true, lastLookAt = mon(23, 55)),
        )
    }

    @Test
    fun aWeekOfDaysOffSetsNoAlarm() {
        val never: (LocalDate) -> WorkCheckWindow? =
            { day -> workCheckWindow(day, zone, DayOfWeek.entries.toSet(), null, null, false) }
        assertNull(nextWorkCheckAt(mon(9), zone, never, ownRunning = false, watching = true, lastLookAt = 0L))
    }

    // --- arriving and leaving ---

    private val idle = WorkSession()

    private fun running(start: Long) = WorkSession(WorkState.RUNNING, start, emptyList(), start, start)

    @Test
    fun arrivingStartsTheTimerNowAndRemembersItsOwnSession() {
        val outcome = decideWorkCheck(WorkAutoMemory(), idle, PlaceReading.INSIDE, mon(8, 50), monday)
        assertEquals(WorkAutoAct.Start, outcome.act)
        assertEquals(WorkAutoMemory(ownStart = mon(8, 50), lastInside = mon(8, 50)), outcome.memory)
    }

    @Test
    fun awayOrUnsureWhileIdleStartsNothing() {
        listOf(PlaceReading.AWAY, PlaceReading.UNSURE).forEach { reading ->
            assertEquals(WorkAutoAct.None, decideWorkCheck(WorkAutoMemory(), idle, reading, mon(8), monday).act)
        }
    }

    @Test
    fun aDayThePersonTookOverStartsNothingButTheNextDayDoes() {
        val quiet = quietMemory(monday)
        assertEquals(WorkAutoAct.None, decideWorkCheck(quiet, idle, PlaceReading.INSIDE, mon(9), monday).act)
        val tuesday = monday.plusDays(1)
        assertEquals(
            WorkAutoAct.Start,
            decideWorkCheck(quiet, idle, PlaceReading.INSIDE, at(tuesday, 9), tuesday).act,
        )
    }

    @Test
    fun oneAwayCheckIsNotEnoughAndAnInsideOneStartsTheCountAgain() {
        val start = mon(8, 50)
        val own = WorkAutoMemory(ownStart = start, lastInside = mon(12))
        val once = decideWorkCheck(own, running(start), PlaceReading.AWAY, mon(12, 10), monday)
        assertEquals(WorkAutoAct.None, once.act)
        assertEquals(1, once.memory.awayChecks)
        val back = decideWorkCheck(once.memory, running(start), PlaceReading.INSIDE, mon(12, 20), monday)
        assertEquals(WorkAutoAct.None, back.act)
        assertEquals(WorkAutoMemory(ownStart = start, lastInside = mon(12, 20)), back.memory)
    }

    @Test
    fun awayTwiceInARowStopsAtTheLastCheckInsideAndAnUnsureOneDoesNotBreakTheRun() {
        val start = mon(8, 50)
        var memory = WorkAutoMemory(ownStart = start, lastInside = mon(17, 30))
        memory = decideWorkCheck(memory, running(start), PlaceReading.AWAY, mon(17, 40), monday).memory
        memory = decideWorkCheck(memory, running(start), PlaceReading.UNSURE, mon(17, 50), monday).memory
        val stop = decideWorkCheck(memory, running(start), PlaceReading.AWAY, mon(18), monday)
        assertEquals(WorkAutoAct.Stop(mon(17, 30)), stop.act)
        // Not a day taken over: coming back later starts a new session. The stop is remembered for its Undo.
        assertEquals(WorkAutoMemory(stoppedStart = start, stoppedAt = mon(18)), stop.memory)
        assertEquals(WorkAutoAct.Start, decideWorkCheck(stop.memory, idle, PlaceReading.INSIDE, mon(19), monday).act)
    }

    @Test
    fun aSessionThePersonStartedOrPausedIsNeverStoppedByACheck() {
        val theirs = running(mon(8))
        val outcome = decideWorkCheck(WorkAutoMemory(), theirs, PlaceReading.AWAY, mon(18), monday)
        assertEquals(WorkAutoAct.None, outcome.act)
        val paused = theirs.pause(mon(12)).session
        assertFalse(WorkAutoMemory(ownStart = mon(8)).watching(paused, monday))
    }

    @Test
    fun anOwnSessionThatEndedAnotherWayHandsTheDayToThePerson() {
        val memory = WorkAutoMemory(ownStart = mon(8, 50), lastInside = mon(9))
        // Stopped at the cap, or by a tap the check never saw: the timer is idle, the memory still says ours.
        val outcome = decideWorkCheck(memory, idle, PlaceReading.INSIDE, mon(9, 10), monday)
        assertEquals(WorkAutoAct.None, outcome.act)
        assertEquals(quietMemory(monday), outcome.memory)
    }

    /** A whole Monday, check by check: at home, on the way, at work, a walk to lunch, home again. */
    @Test
    fun aWorkingDayOnAFakeClock() {
        var memory = WorkAutoMemory()
        var timer = WorkSession()
        val acts = mutableListOf<Pair<Long, WorkAutoAct>>()
        val day = listOf(
            mon(8, 0) to north(4_000.0),
            mon(8, 10) to north(1_500.0),
            mon(8, 20) to north(30.0),
            mon(8, 30) to north(10.0, accuracy = 900.0),
            mon(12, 0) to north(40.0),
            mon(12, 10) to north(450.0, accuracy = 20.0),
            mon(12, 20) to north(20.0),
            mon(17, 0) to north(25.0),
            mon(17, 10) to north(900.0),
            mon(17, 20) to null,
            mon(17, 30) to north(3_000.0),
        )
        day.forEach { (now, fix) ->
            val outcome = decideWorkCheck(memory, timer, placeReading(office, fix), now, monday)
            memory = outcome.memory
            when (val act = outcome.act) {
                WorkAutoAct.Start -> timer = timer.start(now).session
                is WorkAutoAct.Stop -> timer = timer.stop(act.end, "Work", zone).session
                WorkAutoAct.None -> Unit
            }
            if (outcome.act != WorkAutoAct.None) acts += now to outcome.act
        }
        assertEquals(listOf(mon(8, 20) to WorkAutoAct.Start, mon(17, 30) to WorkAutoAct.Stop(mon(17, 0))), acts)
        assertEquals(WorkState.IDLE, timer.state)
    }

    @Test
    fun aNewStartForgetsTheStopBeforeIt() {
        val stopped = WorkAutoMemory(stoppedStart = mon(8, 50), stoppedAt = mon(12, 30))
        val again = decideWorkCheck(stopped, idle, PlaceReading.INSIDE, mon(13, 30), monday)
        assertEquals(WorkAutoAct.Start, again.act)
        assertEquals(WorkAutoMemory(ownStart = mon(13, 30), lastInside = mon(13, 30)), again.memory)
    }

    // --- the morning wake ---

    @Test
    fun aGlanceAtThePhoneBeforeSixIsNotTheWake() {
        // Used at 23:00, glanced at at 04:30, asleep until seven: the phone alone says 04:30.
        assertEquals(mon(6), morningWake(recorded = null, phone = mon(4, 30), monday, zone, hours = null))
        val window = workCheckWindow(monday, zone, weekend, null, mon(6), wakeReadable = true)!!
        assertFalse(window.covers(mon(5), ownRunning = false))
        assertTrue(window.covers(mon(6), ownRunning = false))
    }

    @Test
    fun aWakeAfterSixOrInTheSleepRecordIsTakenAsItIs() {
        assertEquals(mon(7, 10), morningWake(recorded = null, phone = mon(7, 10), monday, zone, hours = null))
        // The person's own record of the night is their word for it.
        assertEquals(mon(4, 30), morningWake(recorded = mon(4, 30), phone = mon(4, 35), monday, zone, hours = null))
        assertEquals(mon(6, 40), morningWake(recorded = mon(6, 40), phone = mon(7, 5), monday, zone, hours = null))
        assertNull(morningWake(recorded = null, phone = null, monday, zone, hours = null))
    }

    @Test
    fun anEarlyShiftLetsAnEarlierPhoneWakeStand() {
        // Usually at work from five: the window may open from four, and so may the phone's wake.
        val early = UsualHours(startMinute = 5 * 60, endMinute = 13 * 60)
        assertEquals(mon(4, 30), morningWake(recorded = null, phone = mon(4, 30), monday, zone, early))
        assertEquals(mon(4), morningWake(recorded = null, phone = mon(3, 15), monday, zone, early))
    }

    // --- Undo ---

    @Test
    fun undoOnAStartHoldsWhileTheSessionIsStillTheAppsOwnThatDay() {
        val start = mon(8)
        val own = WorkAutoMemory(ownStart = start, lastInside = start)
        assertTrue(undoStartAccepted(own, running(start), start, mon(8, 5), zone))
        assertTrue(undoStartAccepted(own, running(start), start, mon(23, 50), zone))
    }

    @Test
    fun undoOnAStartIsRefusedOnceThePersonTookTheSessionOver() {
        val start = mon(8)
        // Paused at 12:00 and resumed: any tap leaves the memory quiet, and the session theirs.
        val resumed = running(start).pause(mon(12)).session.resume(mon(12, 30)).session
        assertFalse(undoStartAccepted(quietMemory(monday), resumed, start, mon(17), zone))
        // Still paused.
        assertFalse(undoStartAccepted(quietMemory(monday), running(start).pause(mon(12)).session, start, mon(17), zone))
        // Another session on the clock, or the next day.
        val own = WorkAutoMemory(ownStart = start, lastInside = start)
        assertFalse(undoStartAccepted(own, running(mon(9)), start, mon(9, 5), zone))
        assertFalse(undoStartAccepted(own, running(start), start, at(monday.plusDays(1), 0, 10), zone))
        assertFalse(undoStartAccepted(WorkAutoMemory(), idle, 0L, mon(9), zone))
    }

    @Test
    fun undoOnAStopHoldsForAnHourWhileTheTimerStaysIdle() {
        val start = mon(8)
        val stopped = WorkAutoMemory(stoppedStart = start, stoppedAt = mon(17, 50))
        assertTrue(undoStopAccepted(stopped, idle, start, mon(18), zone))
        assertTrue(undoStopAccepted(stopped, idle, start, mon(18, 50), zone))
        assertFalse(undoStopAccepted(stopped, idle, start, mon(18, 51), zone))
    }

    @Test
    fun undoOnAStopIsRefusedLaterInTheWeekOrOnceAnythingElseHappened() {
        val start = mon(8)
        val stopped = WorkAutoMemory(stoppedStart = start, stoppedAt = mon(17, 50))
        // Wednesday morning, Monday's notice still in the shade.
        assertFalse(undoStopAccepted(stopped, idle, start, at(monday.plusDays(2), 8), zone))
        // A stop at 23:30 is not put back after midnight, even inside the hour.
        val late = WorkAutoMemory(stoppedStart = start, stoppedAt = mon(23, 30))
        assertFalse(undoStopAccepted(late, idle, start, at(monday.plusDays(1), 0, 10), zone))
        // A session started since, by the app or the person.
        assertFalse(undoStopAccepted(stopped, running(mon(18)), start, mon(18, 5), zone))
        // A tap of the person's since: the memory is quiet.
        assertFalse(undoStopAccepted(quietMemory(monday), idle, start, mon(18), zone))
        // A notice for a session the app did not stop last.
        assertFalse(undoStopAccepted(stopped, idle, mon(7), mon(18), zone))
        // A clock put back before the stop.
        assertFalse(undoStopAccepted(stopped, idle, start, mon(17), zone))
    }

    @Test
    fun theNoticesGoWhenTheirUndoWouldBeRefused() {
        // Started at eight: offered until midnight, sixteen hours on.
        assertEquals(16 * 60 * minute, undoNoticeTimeoutMs(mon(8), zone))
        assertEquals(WORK_UNDO_STOP_MS, undoNoticeTimeoutMs(mon(17, 50), zone, WORK_UNDO_STOP_MS))
        assertEquals(30 * minute, undoNoticeTimeoutMs(mon(23, 30), zone, WORK_UNDO_STOP_MS))
    }

    // --- saving the point ---

    @Test
    fun onlyAFixWithinAHundredMetresIsSavedAsTheWorkplace() {
        assertTrue(workplaceFixPreciseEnough(north(0.0, accuracy = 12.0)))
        assertTrue(workplaceFixPreciseEnough(north(0.0, accuracy = 100.0)))
        // The network position indoors that GPS fell back to.
        assertFalse(workplaceFixPreciseEnough(north(0.0, accuracy = 900.0)))
        assertFalse(workplaceFixPreciseEnough(north(0.0, accuracy = Double.POSITIVE_INFINITY)))
        // Why: saved 700 m off, a precise fix at the desk never reads as there with the default radius.
        val rough = office.copy(lat = north(700.0).lat)
        assertEquals(PlaceReading.AWAY, placeReading(rough, north(0.0, accuracy = 10.0)))
    }

    /** The help, the access card and the notices print these numbers as words; they must stay true. */
    @Test
    fun theNumbersThePagesPrintAreTheOnesTheRulesUse() {
        assertEquals(10 * minute, WORK_CHECK_EVERY_MS)
        assertEquals(2, WORK_AWAY_CHECKS)
        assertEquals(28L, WORK_HOURS_LOOKBACK_DAYS)
        assertEquals(3, WORK_HOURS_MIN_SESSIONS)
        assertEquals(60, WORK_HOURS_MARGIN_MINUTES)
        assertEquals(60 * minute, WORK_UNDO_STOP_MS)
        assertEquals(100.0, WORK_SAVE_MAX_ACCURACY_M, 0.0)
        val english = File("src/main/res/values/work_place.xml").readText()
        listOf(
            "every 10 minutes",
            "last 4 weeks hold at least 3 sessions",
            "two checks in a row",
            "an hour before",
            "a stop for an hour",
            "accurate to within 100 m",
        ).forEach { assertTrue(it, it in english) }
        val russian = File("src/main/res/values-ru/work_place.xml").readText()
        listOf(
            "каждые 10 минут",
            "последние 4 недели наберутся хотя бы 3 сессии",
            "Две проверки подряд",
            "за час до",
            "остановку — в течение часа",
            "с точностью до 100 м",
        ).forEach { assertTrue(it, it in russian) }
    }
}
