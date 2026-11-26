package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The medicine cabinet's rules: which doses a day asks for, which one this hour should mention, and
 * when a routine examination falls due.
 *
 * Every case here is one a person would notice being wrong — a dose announced twice, an after-food
 * dose announced before the meal, a screening that never comes due — and each of them is decided by
 * a pure function precisely so it can be checked here rather than on a phone at nine in the morning.
 */
class MedicineTest {
    private val today: LocalDate = LocalDate.of(2026, 8, 10)
    private val zone: ZoneId = ZoneOffset.UTC

    private fun course(
        id: Long = 1,
        name: String = "Metformin",
        schedule: MedSchedule = MedSchedule.CLOCK,
        times: String = "480,1200",
        food: MedFood = MedFood.ANY,
        start: LocalDate = today.minusDays(3),
        end: LocalDate? = null,
    ) = Medication(
        id = id,
        name = name,
        dose = "1 tablet",
        schedule = schedule.name,
        times = times,
        foodRelation = food.name,
        startDay = start.toString(),
        endDay = end?.toString(),
        createdAt = 0,
    )

    private fun minutesOn(day: LocalDate, minute: Int): Long =
        day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun takenRow(course: Long, slot: String, day: LocalDate = today, minute: Int = 0) =
        JournalEntry(
            day = day.toString(),
            occurredAt = minutesOn(day, minute),
            kind = KIND_MED,
            title = "Metformin",
            calories = 0.0,
            measurementPayloadJson = """{"$MED_COURSE_KEY":$course,"$MED_SLOT_KEY":"$slot"}""",
        )

    /** One sleep the day holds, filed the way the estimator and the editor both file it. */
    private fun sleepRow(startAt: Long, endAt: Long) = JournalEntry(
        day = today.toString(),
        occurredAt = endAt,
        kind = "MEASUREMENT",
        title = "Sleep",
        calories = 0.0,
        sleepStartAt = startAt,
        sleepEndAt = endAt,
    )

    private fun mealRow(minute: Int, day: LocalDate = today) = JournalEntry(
        day = day.toString(),
        occurredAt = minutesOn(day, minute),
        kind = "EAT",
        title = "Lunch",
        calories = 500.0,
    )

    @Test
    fun aCourseAsksForOneDosePerTimeItNames() {
        val doses = plannedDoses(listOf(course()), emptyList(), today)

        assertEquals(listOf(480, 1_200), doses.map(PlannedDose::minuteOfDay))
        assertTrue(doses.none(PlannedDose::taken))
    }

    /** The instruction on the box is "when you get up", so the night's record decides the hour. */
    @Test
    fun aWakingDoseFollowsTheNightThatWasRecorded() {
        val night = JournalEntry(
            day = today.toString(),
            occurredAt = minutesOn(today, 7 * 60 + 20),
            kind = "MEASUREMENT",
            title = "Sleep",
            calories = 0.0,
            sleepStartAt = minutesOn(today.minusDays(1), 23 * 60),
            sleepEndAt = minutesOn(today, 7 * 60 + 20),
        )
        val wake = wakeMinuteOf(listOf(night), today, zone)
        assertEquals(7 * 60 + 20, wake)

        val doses = plannedDoses(
            listOf(course(schedule = MedSchedule.WAKE)),
            listOf(night),
            today,
            wake,
        )
        assertEquals(listOf(7 * 60 + 20), doses.map(PlannedDose::minuteOfDay))
        assertTrue(doses.single().onWaking)
        // With no sleep recorded the default stands in, rather than the dose vanishing.
        assertEquals(MED_DEFAULT_WAKE_MINUTE, wakeMinuteOf(emptyList(), today, zone))
    }

    /**
     * A nap is filed under the same day as the night it follows, so reading the day's latest sleep
     * would move a dose taken on waking to the middle of the afternoon: an unmarked morning dose would
     * stop reading as late and have its grace window reopened from half past three.
     */
    @Test
    fun anAfternoonNapDoesNotMoveTheWakingDose() {
        val night = sleepRow(
            startAt = minutesOn(today.minusDays(1), 23 * 60),
            endAt = minutesOn(today, 7 * 60 + 20),
        )
        val nap = sleepRow(
            startAt = minutesOn(today, 14 * 60),
            endAt = minutesOn(today, 15 * 60 + 30),
        )

        assertEquals(7 * 60 + 20, wakeMinuteOf(listOf(night, nap), today, zone))
        // Order in the journal decides nothing: the night is the sleep that ended first.
        assertEquals(7 * 60 + 20, wakeMinuteOf(listOf(nap, night), today, zone))
        // A nap is still the only thing on record when no night was recorded at all.
        assertEquals(15 * 60 + 30, wakeMinuteOf(listOf(nap), today, zone))
        // Yesterday's night is not today's waking.
        assertEquals(
            MED_DEFAULT_WAKE_MINUTE,
            wakeMinuteOf(listOf(night.copy(day = today.minusDays(1).toString())), today, zone),
        )
    }

    @Test
    fun aCourseThatHasEndedAsksForNothing() {
        val ended = course(start = today.minusDays(10), end = today.minusDays(1))
        assertTrue(plannedDoses(listOf(ended), emptyList(), today).isEmpty())
        assertTrue(ended.activeOn(today.minusDays(1)))
        assertFalse(ended.activeOn(today))
        // A course that starts tomorrow is not today's business either.
        assertFalse(course(start = today.plusDays(1)).activeOn(today))
    }

    /**
     * The list keeps a course through its last day and files it under history the day after; an
     * open-ended course, one yet to start and one whose end cannot be read stay on the list.
     */
    @Test
    fun aCourseMovesToHistoryTheDayAfterItsLastDay() {
        assertFalse(course(end = today).endedBefore(today))
        assertTrue(course(end = today.minusDays(1)).endedBefore(today))
        assertFalse(course().endedBefore(today))
        assertFalse(course(start = today.plusDays(1)).endedBefore(today))
        assertFalse(course().copy(endDay = "soon").endedBefore(today))
    }

    /** A marked dose is a journal row, and it has to match its own slot rather than any dose. */
    @Test
    fun markingOneSlotLeavesTheOtherOpen() {
        val doses = plannedDoses(
            listOf(course()),
            listOf(takenRow(course = 1, slot = "480", minute = 500)),
            today,
        )

        assertTrue(doses.first { it.minuteOfDay == 480 }.taken)
        assertFalse(doses.first { it.minuteOfDay == 1_200 }.taken)
        // Another course's row cannot settle this one's dose.
        val foreign = plannedDoses(listOf(course()), listOf(takenRow(course = 2, slot = "480")), today)
        assertTrue(foreign.none(PlannedDose::taken))
    }

    /**
     * A schedule gets corrected — the box said eight, the doctor said half past — and the doses
     * already marked are the record of what happened. Matching on the slot key alone would stop every
     * one of them from matching the moment the time moved: a fortnight of taken doses would turn into
     * missed ones in the chart, and today's dose would reappear unmarked, inviting a second row.
     */
    @Test
    fun correctingATimeKeepsTheDosesAlreadyMarked() {
        val yesterday = today.minusDays(1)
        val journal = listOf(
            takenRow(1, "480", day = yesterday, minute = 485),
            takenRow(1, "1200", day = yesterday, minute = 1_205),
        )
        val corrected = plannedDoses(listOf(course(times = "510,1230")), journal, yesterday)

        assertEquals(listOf(510, 1_230), corrected.map(PlannedDose::minuteOfDay))
        assertTrue(corrected.all(PlannedDose::taken))
        // Paired in time order: the morning row settles the morning dose, not the evening one.
        assertEquals(
            minutesOn(yesterday, 485),
            corrected.first { it.minuteOfDay == 510 }.takenAtMs,
        )

        // A second row under a slot the course still has is a duplicate mark, not a mark orphaned
        // by an edit, so it must not settle the dose next to it.
        val duplicated = plannedDoses(
            listOf(course()),
            listOf(takenRow(1, "480", minute = 480), takenRow(1, "480", minute = 481)),
            today,
        )
        assertTrue(duplicated.first { it.minuteOfDay == 480 }.taken)
        assertFalse(duplicated.first { it.minuteOfDay == 1_200 }.taken)
    }

    @Test
    fun aMarkedDoseIsNotMentionedAgain() {
        val open = plannedDoses(listOf(course()), emptyList(), today)
        assertNotNull(medNudge(open, nowMinute = 490, lastMealMinute = null))

        val marked = plannedDoses(listOf(course()), listOf(takenRow(1, "480")), today)
        assertNull(medNudge(marked, nowMinute = 490, lastMealMinute = null))
    }

    @Test
    fun nothingIsSaidBeforeTheTimeOrLongAfterIt() {
        val doses = plannedDoses(listOf(course()), emptyList(), today)

        assertNull(medNudge(doses, nowMinute = 479, lastMealMinute = null))
        assertNotNull(medNudge(doses, nowMinute = 480, lastMealMinute = null))
        assertNotNull(medNudge(doses, nowMinute = 480 + MED_GRACE_MINUTES, lastMealMinute = null))
        // Past the grace window the morning dose is dropped; the evening one is not yet due.
        assertNull(medNudge(doses, nowMinute = 481 + MED_GRACE_MINUTES, lastMealMinute = null))
    }

    /**
     * The grace window is four hours of clock time, and midnight does not shorten it: a dose due at
     * ten to midnight is still owed at half past twelve, when the same course's next dose is a
     * whole day away.
     */
    @Test
    fun aDoseDueBeforeMidnightKeepsTheRestOfItsGraceAfterIt() {
        val course = course(times = "1430")
        val yesterday = plannedDoses(listOf(course), emptyList(), today.minusDays(1))
        val doses = plannedDoses(listOf(course), emptyList(), today)

        // 00:30: yesterday's dose is forty minutes late, today's is not due for another day.
        val nudge = medNudge(doses, nowMinute = 30, lastMealMinute = null, yesterday = yesterday)
        assertEquals(1_430, nudge?.dose?.minuteOfDay)
        assertEquals(MedReason.DUE, nudge?.reason)

        // And it does go quiet at the end of the window, which is counted from the dose's own time
        // rather than from midnight: 23:50 plus four hours is ten to four the next morning.
        val lastMinute = 1_430 + MED_GRACE_MINUTES - 1_440
        assertNotNull(
            medNudge(doses, nowMinute = lastMinute, lastMealMinute = null, yesterday = yesterday),
        )
        assertNull(
            medNudge(doses, nowMinute = lastMinute + 1, lastMealMinute = null, yesterday = yesterday),
        )
        // A dose that was marked before midnight is not brought back by the date change.
        assertNull(
            medNudge(
                doses,
                nowMinute = 30,
                lastMealMinute = null,
                yesterday = plannedDoses(
                    listOf(course),
                    listOf(takenRow(1, "1430", day = today.minusDays(1), minute = 1_435)),
                    today.minusDays(1),
                ),
            ),
        )
    }

    /** A meal logged after midnight is still the cue for the dose that was due before it. */
    @Test
    fun aMealAfterMidnightIsTheCueForYesterdaysDose() {
        val course = course(food = MedFood.AFTER, times = "1430")
        val yesterday = plannedDoses(listOf(course), emptyList(), today.minusDays(1))
        val doses = plannedDoses(listOf(course), emptyList(), today)

        // Twenty minutes late with nothing eaten: the dose waits for its meal.
        assertNull(medNudge(doses, nowMinute = 10, lastMealMinute = null, yesterday = yesterday))
        assertEquals(
            MedReason.AFTER_FOOD,
            medNudge(doses, nowMinute = 10, lastMealMinute = 5, yesterday = yesterday)?.reason,
        )
    }

    /** The earliest thing still owed comes first: that is what a person should catch up on. */
    @Test
    fun theOldestOverdueDoseIsTheOneMentioned() {
        val two = listOf(course(id = 1, times = "480"), course(id = 2, name = "Statin", times = "540"))
        val nudge = medNudge(plannedDoses(two, emptyList(), today), nowMinute = 560, lastMealMinute = null)

        assertEquals(480, nudge?.dose?.minuteOfDay)
    }

    /** A dose to be taken before food is announced on its own clock and says so. */
    @Test
    fun aBeforeFoodDoseIsAnnouncedAtItsTime() {
        val doses = plannedDoses(listOf(course(food = MedFood.BEFORE, times = "480")), emptyList(), today)
        val nudge = medNudge(doses, nowMinute = 485, lastMealMinute = null)

        assertEquals(MedReason.BEFORE_FOOD, nudge?.reason)
    }

    /**
     * The whole point of tying a dose to the meal log: an after-food dose waits for a meal, then
     * fires on it, and is not lost when no meal is ever logged.
     */
    @Test
    fun anAfterFoodDoseWaitsForTheMealAndThenAsksAnyway() {
        val course = course(food = MedFood.AFTER, times = "480")
        val doses = plannedDoses(listOf(course), emptyList(), today)

        // Due, but no meal yet and the wait has not run out.
        assertNull(medNudge(doses, nowMinute = 500, lastMealMinute = null))

        // A meal logged after the dose's time is the cue.
        assertEquals(
            MedReason.AFTER_FOOD,
            medNudge(doses, nowMinute = 500, lastMealMinute = 495)?.reason,
        )

        // A meal eaten well before the dose's time is not the meal this dose follows.
        assertNull(
            medNudge(doses, nowMinute = 500, lastMealMinute = 480 - MED_MEAL_LOOKBACK_MINUTES - 1),
        )

        // Nothing logged: after the wait it is mentioned regardless, worded differently.
        assertEquals(
            MedReason.NEEDS_FOOD,
            medNudge(doses, nowMinute = 480 + MED_MEAL_WAIT_MINUTES, lastMealMinute = null)?.reason,
        )
    }

    /**
     * "With food" is the same sitting. The after-food window is two hours because breakfast at seven
     * is the meal an eight o'clock tablet follows; a lunch two hours gone is not the meal a
     * with-food tablet goes down with, so that dose waits for a fresh one and asks in its own words
     * when none comes.
     */
    @Test
    fun aWithFoodDoseWantsTheMealItIsEatenWith() {
        val with = plannedDoses(listOf(course(food = MedFood.WITH, times = "480")), emptyList(), today)
        val after = plannedDoses(listOf(course(food = MedFood.AFTER, times = "480")), emptyList(), today)
        val staleMeal = 480 - MED_WITH_MEAL_MINUTES - 1

        assertEquals(MedReason.WITH_FOOD, medNudge(with, nowMinute = 485, lastMealMinute = 480 - 20)?.reason)
        assertEquals(MedReason.WITH_FOOD, medNudge(with, nowMinute = 485, lastMealMinute = 484)?.reason)
        assertNull(medNudge(with, nowMinute = 485, lastMealMinute = staleMeal))
        // The same stale meal is still the one an after-food dose follows.
        assertEquals(MedReason.AFTER_FOOD, medNudge(after, nowMinute = 485, lastMealMinute = staleMeal)?.reason)
        assertEquals(
            MedReason.NEEDS_WITH_FOOD,
            medNudge(with, nowMinute = 480 + MED_MEAL_WAIT_MINUTES, lastMealMinute = staleMeal)?.reason,
        )
    }

    @Test
    fun theMealBehindAnAfterFoodDoseIsFoundInTheJournal() {
        val journal = listOf(mealRow(minute = 495), mealRow(minute = 300))
        assertEquals(495, lastMealMinuteOf(journal, today, zone))
        assertNull(lastMealMinuteOf(emptyList(), today, zone))
        // Yesterday's lunch is not today's cue.
        assertNull(lastMealMinuteOf(listOf(mealRow(minute = 495, day = today.minusDays(1))), today, zone))
    }

    @Test
    fun theChartCountsWhatWasAskedForAndWhatWasMarked() {
        val journal = listOf(
            takenRow(1, "480", day = today, minute = 500),
            takenRow(1, "1200", day = today.minusDays(1), minute = 1_210),
            takenRow(1, "480", day = today.minusDays(1), minute = 500),
        )
        val days = medAdherence(listOf(course()), journal, today, days = 3)

        assertEquals(3, days.size)
        assertEquals(today, days.last().day)
        assertEquals(listOf(2, 2, 2), days.map(MedDay::planned))
        assertEquals(listOf(0, 2, 1), days.map(MedDay::taken))
        assertEquals(0.5, days.last().share, 0.001)
    }

    /** The chart must not count doses from before the course existed as missed. */
    @Test
    fun daysBeforeTheCourseStartedCountForNothing() {
        val days = medAdherence(listOf(course(start = today)), emptyList(), today, days = 3)

        assertEquals(listOf(0, 0, 2), days.map(MedDay::planned))
    }

    /**
     * A course entered with a start a week back was asking nothing before it was written down: the
     * day it was created is the first day that counts. That day counts whole, whatever the hour it
     * was typed in — the day list shows all its doses as doses to take, and the chart scores them.
     */
    @Test
    fun daysBeforeTheCourseWasWrittenDownCountForNothing() {
        val written = course(start = today.minusDays(5)).copy(createdAt = minutesOn(today.minusDays(2), 21 * 60))
        val evening = medAdherence(listOf(written), emptyList(), today, days = 4, zone = zone)
        assertEquals(listOf(0, 2, 2, 2), evening.map(MedDay::planned))

        val morning = written.copy(createdAt = minutesOn(today.minusDays(2), 10 * 60))
        val fromTen = medAdherence(listOf(morning), emptyList(), today, days = 4, zone = zone)
        assertEquals(listOf(0, 2, 2, 2), fromTen.map(MedDay::planned))
    }

    /**
     * One tablet at 8, 10, 12 and 14, written down in the afternoon, every dose marked and then one
     * mark undone. Counted from the minute the course was created, the undone dose would leave the
     * count with its mark, and the chart would still read 100%.
     */
    @Test
    fun anUndoneMarkOnTheDayTheCourseWasWrittenIsAMissedDose() {
        val fourTimes = course(times = "480,600,720,840", start = today)
            .copy(createdAt = minutesOn(today, 15 * 60))
        val marks = listOf("480", "600", "720").map { slot -> takenRow(1, slot, minute = 15 * 60 + 10) }

        val day = medAdherence(listOf(fourTimes), marks, today, days = 1, nowMinute = 16 * 60, zone = zone).single()

        assertEquals(4, day.planned)
        assertEquals(3, day.taken)
        assertEquals(0.75, day.share, 1e-9)
    }

    /**
     * A dose whose time has not come is not a dose missed: at nine in the morning the evening
     * tablet says nothing about today's score, and at eight in the evening it joins the count.
     */
    @Test
    fun todaysDosesCountOnlyOnceTheirTimeHasCome() {
        fun plannedAt(nowMinute: Int, days: Int = 1) =
            medAdherence(listOf(course()), emptyList(), today, days, nowMinute, zone).map(MedDay::planned)

        assertEquals(listOf(1), plannedAt(540))
        assertEquals(listOf(2), plannedAt(1_200))

        // Marked ahead of its time, a dose counts with its mark, so the share never runs past one.
        val early = medAdherence(
            listOf(course()),
            listOf(takenRow(1, "1200", minute = 530)),
            today,
            days = 1,
            nowMinute = 540,
            zone = zone,
        ).single()
        assertEquals(2, early.planned)
        assertEquals(1, early.taken)
        // Yesterday is over: every one of its slots has come, whatever the clock says today.
        assertEquals(listOf(2, 1), plannedAt(540, days = 2))
    }

    @Test
    fun everyExaminationAppliesToTheRightPersonAndAge() {
        val youngMan = checkupsFor("MALE", 25)
        assertTrue(Checkup.GP in youngMan)
        assertTrue(Checkup.DENTIST in youngMan)
        assertFalse(Checkup.UROLOGIST in youngMan)
        assertFalse(Checkup.GYNECOLOGIST in youngMan)

        val olderMan = checkupsFor("MALE", 45)
        assertTrue(Checkup.UROLOGIST in olderMan)
        assertTrue(Checkup.BOWEL in olderMan)
        assertTrue(Checkup.EYES in olderMan)

        val youngWoman = checkupsFor("FEMALE", 25)
        assertTrue(Checkup.GYNECOLOGIST in youngWoman)
        assertTrue(Checkup.CERVICAL in youngWoman)
        assertFalse(Checkup.MAMMOGRAPHY in youngWoman)
        assertFalse(Checkup.UROLOGIST in youngWoman)

        assertTrue(Checkup.MAMMOGRAPHY in checkupsFor("FEMALE", 41))

        // Programmes end: USPSTF stops mammography at 74, cervical screening at 65, bowel screening
        // at 75 and prostate screening at 69, and the list stops asking with them.
        val olderWoman = checkupsFor("FEMALE", 78)
        assertFalse(Checkup.MAMMOGRAPHY in olderWoman)
        assertFalse(Checkup.CERVICAL in olderWoman)
        assertFalse(Checkup.BOWEL in olderWoman)
        assertTrue(Checkup.GP in olderWoman)
        assertTrue(Checkup.GYNECOLOGIST in olderWoman)
        assertTrue(Checkup.MAMMOGRAPHY in checkupsFor("FEMALE", 74))
        val oldestMan = checkupsFor("MALE", 76)
        assertFalse(Checkup.UROLOGIST in oldestMan)
        assertFalse(Checkup.BOWEL in oldestMan)
        assertTrue(Checkup.UROLOGIST in checkupsFor("MALE", 69))
        // No examination has an interval of zero, which would make it permanently due.
        assertTrue(Checkup.entries.all { it.everyMonths > 0 })
    }

    @Test
    fun anExaminationWithNoVisitOnRecordIsDueWithoutPretendingToKnowWhen() {
        val status = checkupStatuses(listOf(Checkup.GP), emptyList(), today, zone).single()

        assertNull(status.lastDay)
        assertNull(status.dueOn)
        assertNull(status.daysLeft)
        assertTrue(status.due)
        // Due, but not behind: there is no date to be behind, and the screen must not warn in amber.
        assertFalse(status.overdue)
        assertEquals(1.0, status.elapsedShare, 0.001)
    }

    @Test
    fun aVisitWindsTheClockAndOnlyOnceItHappened() {
        val done = Appointment(
            id = 1,
            specialty = Checkup.GYNECOLOGIST.name,
            atMs = minutesOn(today.minusMonths(6), 600),
            doneAt = minutesOn(today.minusMonths(6), 600),
        )
        val planned = Appointment(
            id = 2,
            specialty = Checkup.GYNECOLOGIST.name,
            atMs = minutesOn(today.plusDays(2), 600),
        )
        val status = checkupStatuses(
            listOf(Checkup.GYNECOLOGIST),
            listOf(done, planned),
            today,
            zone,
        ).single()

        assertEquals(today.minusMonths(6), status.lastDay)
        assertEquals(today.plusMonths(6), status.dueOn)
        assertFalse(status.due)
        assertTrue("was ${status.elapsedShare}", status.elapsedShare in 0.45..0.55)
    }

    /**
     * Being marked is what makes a visit count; the day it counts from is the visit's own. The
     * screen exists to ask about appointments whose date has already passed, so a dentist visit
     * from three weeks ago is normally ticked off three weeks late — and counting from the tick
     * would put the next recall three weeks beyond where the interval actually falls.
     */
    @Test
    fun theClockWindsFromTheVisitAndNotFromTheTick() {
        val late = Appointment(
            specialty = Checkup.DENTIST.name,
            atMs = minutesOn(today.minusDays(21), 600),
            doneAt = minutesOn(today, 700),
        )
        val status = checkupStatuses(listOf(Checkup.DENTIST), listOf(late), today, zone).single()

        assertEquals(today.minusDays(21), status.lastDay)
        assertEquals(today.minusDays(21).plusMonths(6), status.dueOn)
    }

    @Test
    fun anOverdueExaminationSaysHowLateItIs() {
        val done = Appointment(
            specialty = Checkup.DENTIST.name,
            atMs = minutesOn(today.minusMonths(7), 600),
            doneAt = minutesOn(today.minusMonths(7), 600),
        )
        val status = checkupStatuses(listOf(Checkup.DENTIST), listOf(done), today, zone).single()

        assertTrue(status.due)
        assertTrue(status.overdue)
        assertTrue("was ${status.daysLeft}", (status.daysLeft ?: 0) < 0)
        assertEquals(1.0, status.elapsedShare, 0.001)
    }

    @Test
    fun anAppointmentIsAnnouncedOnceAndOnlyWithinItsWindow() {
        val now = minutesOn(today, 9 * 60)
        val soon = Appointment(id = 1, specialty = VISIT_SPECIALTY_OTHER, atMs = now + 20 * 60 * 60 * 1_000L)
        val far = Appointment(id = 2, specialty = VISIT_SPECIALTY_OTHER, atMs = now + 9 * 24 * 60 * 60 * 1_000L)

        assertEquals(1L, appointmentToRemind(listOf(soon, far), now, zone)?.id)
        // Once announced it stays quiet, and a visit already marked as attended never speaks.
        assertNull(appointmentToRemind(listOf(soon.copy(remindedAt = now)), now, zone))
        assertNull(appointmentToRemind(listOf(soon.copy(doneAt = now)), now, zone))
        // A date more than a worker's period behind is not a reminder; the screen asks about it
        // instead, and lists it as past due either way.
        assertNull(appointmentToRemind(listOf(soon.copy(atMs = now - VISIT_REMIND_LATE_MS)), now, zone))
        assertEquals(listOf(1L), pastDueAppointments(listOf(soon.copy(atMs = now - 1)), now).map { it.id })
        assertEquals(listOf(1L, 2L), upcomingAppointments(listOf(far, soon), now).map { it.id })
    }

    /**
     * The worker runs once an hour, so a visit at half past eight has its time come and go between
     * two runs; the run at nine is the first that could have spoken, and it does. What it does not
     * do is announce yesterday's late visit in the small hours as if it were today's.
     */
    @Test
    fun aVisitWhoseHourPassedSinceTheLastRunIsStillAnnounced() {
        val nine = minutesOn(today, 9 * 60)
        val halfEight = Appointment(id = 1, specialty = VISIT_SPECIALTY_OTHER, atMs = minutesOn(today, 8 * 60 + 30))

        assertEquals(1L, appointmentToRemind(listOf(halfEight), nine, zone)?.id)
        assertNull(appointmentToRemind(listOf(halfEight), nine + VISIT_REMIND_LATE_MS, zone))
        // Twenty to midnight, looked at twenty past: the day has turned, and the visit with it.
        val lateVisit = halfEight.copy(atMs = minutesOn(today.minusDays(1), 23 * 60 + 40))
        assertNull(appointmentToRemind(listOf(lateVisit), minutesOn(today, 20), zone))
    }

    /** A three-day warning is honoured, and a same-day one still lands on the day. */
    @Test
    fun theWarningWindowFollowsWhatWasAskedFor() {
        val now = minutesOn(today, 9 * 60)
        val threeDays = Appointment(
            specialty = VISIT_SPECIALTY_OTHER,
            atMs = now + 3 * 24 * 60 * 60 * 1_000L,
            remindDaysBefore = 3,
        )
        assertNotNull(appointmentToRemind(listOf(threeDays), now, zone))
        assertNull(appointmentToRemind(listOf(threeDays.copy(remindDaysBefore = 0)), now, zone))
        assertNotNull(
            appointmentToRemind(
                listOf(threeDays.copy(atMs = now + 6 * 60 * 60 * 1_000L, remindDaysBefore = 0)),
                now,
                zone,
            ),
        )
    }

    /**
     * The day the screen promises is the day the notification arrives.
     *
     * The window is counted in calendar days: a span of milliseconds — `days * 24h + 24h` — would
     * announce every choice a day early: "on the day" the evening before, "one day before" two days
     * before. The appointment page prints the date it will speak on, so the two have to agree.
     */
    @Test
    fun theReminderLandsOnTheDayItNames() {
        val morning = minutesOn(today, 9 * 60)
        val inTwoDays = Appointment(
            specialty = VISIT_SPECIALTY_OTHER,
            atMs = minutesOn(today.plusDays(2), 8 * 60),
            remindDaysBefore = 1,
        )
        // Today is two days out: the day before is tomorrow, so today says nothing.
        assertNull(appointmentToRemind(listOf(inTwoDays), morning, zone))
        assertNotNull(
            appointmentToRemind(listOf(inTwoDays), minutesOn(today.plusDays(1), 9 * 60), zone),
        )
        assertEquals(
            minutesOn(today.plusDays(1), 9 * 60),
            appointmentRemindAt(inTwoDays, zone),
        )

        // "On the day" for a visit at 08:00 cannot wait for 09:00; an hour's warning is what is left.
        val earlyTomorrow = Appointment(
            specialty = VISIT_SPECIALTY_OTHER,
            atMs = minutesOn(today.plusDays(1), 8 * 60),
            remindDaysBefore = 0,
        )
        assertNull(appointmentToRemind(listOf(earlyTomorrow), morning, zone))
        assertEquals(
            minutesOn(today.plusDays(1), 7 * 60),
            appointmentRemindAt(earlyTomorrow, zone),
        )
    }

    /**
     * The times column is typed by a form and read by a worker: a value neither of them wrote —
     * from a hand-edited backup, say — must be dropped rather than turned into a dose at hour 40.
     */
    @Test
    fun nonsenseInTheTimesColumnIsIgnoredRatherThanScheduled() {
        assertEquals(listOf(0, 60, 1_439), course(times = "0,60,1439,-5,1440,x, ").doseMinutes())
        assertEquals(emptyList<Int>(), course(times = "").doseMinutes())
        // A waking course keeps its single slot whatever the column holds.
        assertEquals(listOf(MED_SLOT_WAKE), course(schedule = MedSchedule.WAKE, times = "480").slots())
        assertEquals(emptyList<Int>(), course(schedule = MedSchedule.WAKE).doseMinutes())
        // An unknown enum name falls back rather than throwing.
        assertEquals(MedSchedule.CLOCK, course().copy(schedule = "SOMETHING").scheduleKind())
        assertEquals(MedFood.ANY, course().copy(foodRelation = "SOMETHING").foodRelation())
    }

    @Test
    fun aRowThatNamesNoCourseSettlesNothing() {
        assertNull(medSlotOf(mealRow(minute = 400)))
        assertNull(medSlotOf(takenRow(1, "480").copy(measurementPayloadJson = null)))
        assertNull(medSlotOf(takenRow(1, "480").copy(measurementPayloadJson = "not json")))
        assertEquals(1L to "480", medSlotOf(takenRow(1, "480")))
    }

    /** The row a marked dose writes has to be readable back as the dose it settled. */
    @Test
    fun theEntryWrittenForADoseRoundTrips() {
        val dose = plannedDoses(listOf(course()), emptyList(), today).first()
        val entry = medTakenEntry(dose, today, minutesOn(today, 500))

        assertEquals(KIND_MED, entry.kind)
        assertEquals("Metformin", entry.title)
        assertEquals("1 tablet", entry.subtitle)
        assertEquals(0.0, entry.calories, 0.0)
        assertEquals(1L to "480", medSlotOf(entry))
        assertTrue(plannedDoses(listOf(course()), listOf(entry), today).first().taken)
    }

    /**
     * A dose is a duty at a time the person chose, so it is not silenced by the quiet hours the
     * step nudges obey — and it does not travel through [reminderFor], which enforces them.
     */
    @Test
    fun aLateEveningDoseIsStillAnnounced() {
        val doses = plannedDoses(listOf(course(times = "1380")), emptyList(), today)
        val nudge = medNudge(doses, nowMinute = 1_385, lastMealMinute = null)

        assertEquals(MedReason.DUE, nudge?.reason)
        assertEquals(
            ReminderKind.NONE,
            reminderFor(
                now = LocalTime.of(23, 5),
                steps = 0,
                goal = 8_000,
                goalAlreadyAnnounced = false,
                minutesSinceLastStep = 600,
                loggedAnythingToday = false,
            ),
        )
    }
}
