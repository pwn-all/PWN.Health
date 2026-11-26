package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * The edges of a medicine schedule: the hour a dose taken on waking actually falls at, what a clock
 * change does to the day underneath it, and the windows a food-relative dose waits inside.
 *
 * Every case here is one a person would have to reconstruct by hand — a dose the chart calls missed
 * at an hour they were still asleep, a first morning the course was not yet written down for, a
 * tablet still waiting on a lunch nobody logged.
 */
class MedicineSchedulesEdgeCasesTest {
    private val today: LocalDate = LocalDate.of(2026, 8, 10)
    private val utc: ZoneId = ZoneOffset.UTC
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    private fun course(
        id: Long = 1,
        name: String = "Metformin",
        schedule: MedSchedule = MedSchedule.CLOCK,
        times: String = "480",
        food: MedFood = MedFood.ANY,
        start: LocalDate = today.minusDays(7),
        end: LocalDate? = null,
        createdAt: Long = 0,
    ) = Medication(
        id = id,
        name = name,
        dose = "1 tablet",
        schedule = schedule.name,
        times = times,
        foodRelation = food.name,
        startDay = start.toString(),
        endDay = end?.toString(),
        createdAt = createdAt,
    )

    /** The wall clock, built the way a calendar reads it rather than by adding elapsed minutes. */
    private fun msAt(day: LocalDate, minute: Int, zone: ZoneId = utc): Long =
        day.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()

    private fun takenRow(course: Long, slot: String, day: LocalDate = today, minute: Int = 0) = JournalEntry(
        day = day.toString(),
        occurredAt = msAt(day, minute),
        kind = KIND_MED,
        title = "Metformin",
        calories = 0.0,
        measurementPayloadJson = """{"$MED_COURSE_KEY":$course,"$MED_SLOT_KEY":"$slot"}""",
    )

    private fun sleepRow(endMs: Long, day: LocalDate = today) = JournalEntry(
        day = day.toString(),
        occurredAt = endMs,
        kind = "MEASUREMENT",
        title = "Sleep",
        calories = 0.0,
        sleepStartAt = endMs - 8 * 60 * 60 * 1_000L,
        sleepEndAt = endMs,
    )

    private fun mealRow(atMs: Long, day: LocalDate = today) = JournalEntry(
        day = day.toString(),
        occurredAt = atMs,
        kind = "EAT",
        title = "Lunch",
        calories = 500.0,
    )

    private fun chartFor(
        medications: List<Medication>,
        journal: List<JournalEntry>,
        day: LocalDate,
        days: Int,
        nowMinute: Int,
        zone: ZoneId,
    ): List<MedDay> = medAdherence(medications, journal, day, days, nowMinute, zone)

    /**
     * A night shift worker slept until one in the afternoon, and their course says "when you get
     * up". At ten in the morning the dose is not yet owed: a chart that pinned every waking dose to
     * eight o'clock while the dose list on the same screen reads the night would read nought per cent
     * for the day — one screen calling a dose missed three hours before it is owed.
     */
    @Test
    fun theChartWaitsForALateRisersDose() {
        val waking = course(schedule = MedSchedule.WAKE, times = "")
        val night = listOf(sleepRow(endMs = msAt(today, 13 * 60)))

        val atTen = chartFor(listOf(waking), night, today, days = 1, nowMinute = 600, zone = utc).single()
        assertEquals("was ${atTen.planned} planned at 10:00", 0, atTen.planned)
        assertEquals("was ${atTen.share} at 10:00", 0.0, atTen.share, 1e-9)

        val afterOne = chartFor(listOf(waking), night, today, days = 1, nowMinute = 800, zone = utc).single()
        assertEquals("was ${afterOne.planned} planned at 13:20", 1, afterOne.planned)
    }

    /**
     * The mirror of the late riser: up at six, the dose was owed at six, and by twenty to seven it
     * is forty minutes late. A chart that showed the day as asking for nothing at all would hide a
     * dose that is already being forgotten.
     */
    @Test
    fun theChartCountsAnEarlyRisersDoseOnceItIsLate() {
        val waking = course(schedule = MedSchedule.WAKE, times = "")
        val night = listOf(sleepRow(endMs = msAt(today, 6 * 60)))

        val day = chartFor(listOf(waking), night, today, days = 1, nowMinute = 400, zone = utc).single()

        assertEquals("was ${day.planned} planned at 06:40", 1, day.planned)
        assertEquals("was ${day.taken} taken at 06:40", 0, day.taken)
    }

    /**
     * The clocks went forward at two in the morning and the course was written down at half past
     * eight, after its own eight o'clock dose had passed. The day the course was written down counts
     * whole, as the day list shows it, so that dose is one the day asked for — and no wall-clock
     * arithmetic is left for the missing hour to break: the rule compares days, not instants.
     */
    @Test
    fun theDayTheCourseWasWrittenDownCountsWholeWhenTheClocksGoForward() {
        val springForward = LocalDate.of(2026, 3, 29)
        val writtenDown = LocalDateTime.of(2026, 3, 29, 8, 30).atZone(berlin).toInstant().toEpochMilli()
        val morning = course(times = "480", start = springForward.minusDays(7), createdAt = writtenDown)

        val day = chartFor(
            listOf(morning),
            emptyList(),
            springForward,
            days = 1,
            nowMinute = 1_440,
            zone = berlin,
        ).single()

        assertEquals("was ${day.planned} planned", 1, day.planned)
    }

    /**
     * The same day the other way round: the clocks went back, the course was written down at half
     * past seven, and its eight o'clock dose came afterwards. That dose is one the day genuinely
     * asked for, and it stays on the chart: gone from it, it would be a missed dose that no bar could
     * show.
     */
    @Test
    fun aDoseTheCourseExistedForStaysOnTheChartWhenTheClocksGoBack() {
        val fallBack = LocalDate.of(2026, 10, 25)
        val writtenDown = LocalDateTime.of(2026, 10, 25, 7, 30).atZone(berlin).toInstant().toEpochMilli()
        val morning = course(times = "480", start = fallBack.minusDays(7), createdAt = writtenDown)

        val day = chartFor(
            listOf(morning),
            emptyList(),
            fallBack,
            days = 1,
            nowMinute = 1_440,
            zone = berlin,
        ).single()

        assertEquals("was ${day.planned} planned", 1, day.planned)
    }

    /**
     * A course with nothing in its times column asks for nothing, and nought doses out of nought is
     * nought per cent rather than a not-a-number. The share is a bar height and a printed figure;
     * NaN draws as neither.
     */
    @Test
    fun aWindowWithNothingScheduledIsZeroPerCentAndNotANaN() {
        assertEquals(0.0, MedDay(today, planned = 0, taken = 0).share, 1e-9)

        val chart = chartFor(
            listOf(course(times = "")),
            emptyList(),
            today,
            days = MED_CHART_DAYS,
            nowMinute = 1_440,
            zone = utc,
        )

        assertEquals(MED_CHART_DAYS, chart.size)
        chart.forEach { day ->
            assertEquals("planned on ${day.day}", 0, day.planned)
            assertEquals("taken on ${day.day}", 0, day.taken)
            assertEquals("share on ${day.day} was ${day.share}", 0.0, day.share, 1e-9)
        }
    }

    /**
     * Whatever the journal holds, a day cannot have taken more doses than it asked for: the marks
     * are re-attributed across edits, and a share above one would print as "112% taken".
     */
    @Test
    fun everyChartedDayKeepsTakenWithinItsPlanned() {
        val clock = course(id = 1, times = "480,1200")
        val waking = course(id = 2, name = "Bisoprolol", schedule = MedSchedule.WAKE, times = "")
        val journal = listOf(
            // Marked before its time, marked twice, and marked under a slot the course dropped.
            takenRow(1, "1200", day = today, minute = 530),
            takenRow(1, "480", day = today.minusDays(1), minute = 485),
            takenRow(1, "480", day = today.minusDays(1), minute = 490),
            takenRow(1, "615", day = today.minusDays(2), minute = 620),
            takenRow(2, MED_SLOT_WAKE, day = today.minusDays(3), minute = 460),
        )

        val chart = chartFor(listOf(clock, waking), journal, today, days = 5, nowMinute = 540, zone = utc)

        assertEquals(5, chart.size)
        chart.forEach { day ->
            assertTrue("${day.taken} taken of ${day.planned} on ${day.day}", day.taken <= day.planned)
            assertTrue("share ${day.share} on ${day.day}", day.share in 0.0..1.0)
        }
    }

    /**
     * A last day typed in ahead of the first one — a back-dated end on a course that was stopped
     * early, mistyped by a month. Such a course covers no day at all, and must never be charted as a
     * fortnight of missed doses.
     */
    @Test
    fun aCourseThatEndsBeforeItStartsAsksForNothing() {
        val inverted = course(start = LocalDate.of(2026, 8, 10), end = LocalDate.of(2026, 8, 1))
        val days = listOf(
            LocalDate.of(2026, 7, 31),
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 5),
            LocalDate.of(2026, 8, 10),
            LocalDate.of(2026, 8, 15),
        )

        days.forEach { day ->
            assertFalse("active on $day", inverted.activeOn(day))
            assertTrue("planned on $day", plannedDoses(listOf(inverted), emptyList(), day).isEmpty())
        }

        val chart = chartFor(
            listOf(inverted),
            emptyList(),
            LocalDate.of(2026, 8, 15),
            days = MED_CHART_DAYS,
            nowMinute = 1_440,
            zone = utc,
        )
        assertTrue("was ${chart.map(MedDay::planned)}", chart.all { it.planned == 0 })
    }

    /**
     * Both dates arrive as raw strings and a restored backup can hold anything. A start nobody can
     * read schedules nothing — a row that cannot be read is not a prescription — while an end nobody
     * can read leaves the course open, because the start is the part that is known. Neither may
     * throw on the way to the day list.
     */
    @Test
    fun datesNeitherFormEverWroteAreRefusedRatherThanDosingForever() {
        listOf("2026-8-10", "", "yesterday", "10.08.2026").forEach { typed ->
            val unreadable = course().copy(startDay = typed)
            assertFalse("start '$typed'", unreadable.activeOn(today))
            assertTrue("start '$typed'", plannedDoses(listOf(unreadable), emptyList(), today).isEmpty())
        }

        listOf("2026-13-40", "", "next week").forEach { typed ->
            val openEnded = course().copy(endDay = typed)
            assertTrue("end '$typed' today", openEnded.activeOn(today))
            assertTrue("end '$typed' in five years", openEnded.activeOn(today.plusYears(5)))
        }
    }

    /**
     * An after-food dose with no meal on record waits, and the wait is over at ninety minutes — not
     * at ninety-one. Two hours of silence is how a dose gets forgotten, and the far edge matters
     * just as much: four hours after its time the app stops asking, whatever the food relation.
     */
    @Test
    fun theWaitForAnUnloggedMealEndsAtNinetyMinutesExactly() {
        val doses = plannedDoses(listOf(course(food = MedFood.AFTER, times = "480")), emptyList(), today)

        assertNull(medNudge(doses, nowMinute = 480 + MED_MEAL_WAIT_MINUTES - 1, lastMealMinute = null))
        assertEquals(
            MedReason.NEEDS_FOOD,
            medNudge(doses, nowMinute = 480 + MED_MEAL_WAIT_MINUTES, lastMealMinute = null)?.reason,
        )
        assertEquals(
            MedReason.NEEDS_FOOD,
            medNudge(doses, nowMinute = 480 + MED_GRACE_MINUTES, lastMealMinute = null)?.reason,
        )
        assertNull(medNudge(doses, nowMinute = 480 + MED_GRACE_MINUTES + 1, lastMealMinute = null))
    }

    /**
     * Breakfast at six is still the meal an eight o'clock after-food tablet follows, and a meal half
     * an hour before is still the sitting a with-food tablet goes down at. A window one minute short
     * sends the person a second reminder for a dose they have already been told to take.
     */
    @Test
    fun theMealLookbackWindowsAreInclusiveAtTheirFarEdge() {
        val after = plannedDoses(listOf(course(food = MedFood.AFTER, times = "480")), emptyList(), today)
        val with = plannedDoses(listOf(course(food = MedFood.WITH, times = "480")), emptyList(), today)
        val edgeOfAfter = 480 - MED_MEAL_LOOKBACK_MINUTES
        val edgeOfWith = 480 - MED_WITH_MEAL_MINUTES

        assertEquals(
            MedReason.AFTER_FOOD,
            medNudge(after, nowMinute = 485, lastMealMinute = edgeOfAfter)?.reason,
        )
        assertNull(medNudge(after, nowMinute = 485, lastMealMinute = edgeOfAfter - 1))
        assertEquals(
            MedReason.WITH_FOOD,
            medNudge(with, nowMinute = 485, lastMealMinute = edgeOfWith)?.reason,
        )
        assertNull(medNudge(with, nowMinute = 485, lastMealMinute = edgeOfWith - 1))
    }

    /**
     * A dose due at eleven at night with nothing eaten all evening. At half past midnight the wait
     * has run out and it should ask in its own words, exactly as it would have done before midnight;
     * losing that to the date changing is losing the dose.
     */
    @Test
    fun yesterdaysFoodDoseAsksInItsOwnWordsWhenNothingWasEverEaten() {
        val nightly = course(food = MedFood.AFTER, times = "1380")
        val yesterday = plannedDoses(listOf(nightly), emptyList(), today.minusDays(1))
        val doses = plannedDoses(listOf(nightly), emptyList(), today)

        assertEquals(
            MedReason.NEEDS_FOOD,
            medNudge(
                doses = doses,
                nowMinute = 30,
                lastMealMinute = null,
                yesterday = yesterday,
                yesterdayMeal = null,
            )?.reason,
        )
        assertNull(
            medNudge(
                doses = doses,
                nowMinute = 29,
                lastMealMinute = null,
                yesterday = yesterday,
                yesterdayMeal = null,
            ),
        )
    }

    /**
     * The slot key for a dose on waking is the word rather than an hour precisely so that correcting
     * last night's sleep — a nap deleted, an end time nudged — cannot orphan a dose already marked.
     */
    @Test
    fun aDoseMarkedOnWakingStaysMarkedWhenTheNightIsCorrected() {
        val waking = course(schedule = MedSchedule.WAKE, times = "")
        val dose = plannedDoses(listOf(waking), emptyList(), today, wakeMinute = 440).single()
        assertEquals(MED_SLOT_WAKE, dose.slot)

        val entry = medTakenEntry(dose, today, msAt(today, 450))
        assertEquals(1L to MED_SLOT_WAKE, medSlotOf(entry))

        val reread = plannedDoses(listOf(waking), listOf(entry), today, wakeMinute = 800).single()
        assertTrue("was $reread", reread.taken)
        assertEquals(800, reread.minuteOfDay)
        assertEquals(msAt(today, 450), reread.takenAtMs)
    }

    /**
     * The doctor said to stop watching the clock and take it on getting up. The morning's dose was
     * already marked under its old eight o'clock key, and the edit must not resurrect it unmarked —
     * that is a second tablet from a person who is trying to do as they were told.
     */
    @Test
    fun switchingACourseToOnWakingKeepsTodaysDoseMarked() {
        val mark = takenRow(course = 1, slot = "480", day = today, minute = 485)
        val moved = course(schedule = MedSchedule.WAKE, times = "480")

        val doses = plannedDoses(listOf(moved), listOf(mark), today, wakeMinute = 420)

        assertEquals(listOf(MED_SLOT_WAKE), doses.map(PlannedDose::slot))
        assertEquals(listOf(420), doses.map(PlannedDose::minuteOfDay))
        assertTrue("was ${doses.single()}", doses.single().taken)
    }

    /** The day's list is one column of times, so the waking dose takes its place among the rest. */
    @Test
    fun aWakingDoseSortsAmongTheClockDosesByTheHourItFallsAt() {
        val clock = course(id = 1, name = "Aspirin", times = "480,1200")
        val waking = course(id = 2, name = "Bisoprolol", schedule = MedSchedule.WAKE, times = "")

        val afternoon = plannedDoses(listOf(clock, waking), emptyList(), today, wakeMinute = 780)
        assertEquals(listOf(480, 780, 1_200), afternoon.map(PlannedDose::minuteOfDay))
        assertEquals(listOf(false, true, false), afternoon.map(PlannedDose::onWaking))

        // It moves with the night rather than keeping its place in the list.
        val dawn = plannedDoses(listOf(clock, waking), emptyList(), today, wakeMinute = 300)
        assertEquals(listOf(300, 480, 1_200), dawn.map(PlannedDose::minuteOfDay))
        assertEquals(listOf("Bisoprolol", "Aspirin", "Aspirin"), dawn.map(PlannedDose::name))
    }

    /**
     * Times are typed into a form in whatever order they are remembered in, and the same one twice
     * when a course is edited in a hurry. Two identical slots would be settled by one journal row
     * and the chart would spend every day asking for a dose that does not exist.
     */
    @Test
    fun aTimesColumnTypedOutOfOrderWithRepeatsBecomesOneDosePerTime() {
        val messy = course(times = "1200,480,480,0")

        assertEquals(listOf(0, 480, 1_200), messy.doseMinutes())
        assertEquals(listOf("0", "480", "1200"), messy.slots())
        assertEquals(3, plannedDoses(listOf(messy), emptyList(), today).size)

        val doses = plannedDoses(listOf(messy), listOf(takenRow(1, "480", minute = 485)), today)
        assertEquals("was ${doses.map(PlannedDose::taken)}", 1, doses.count(PlannedDose::taken))
    }

    /**
     * The line under the course name, and the same string the doctor report prints. A minute count
     * where a clock belongs is unreadable, and a course taken on waking has no clock to print.
     */
    @Test
    fun theCourseLinePrintsItsTimesAsAClock() {
        assertEquals("00:00, 10:00, 23:59", course(times = "0,600,1439").timesText(", "))
        assertEquals("07:05, 20:00", course(times = "425,1200").timesText(", "))
        assertEquals("", course(times = "").timesText(", "))
        assertEquals("", course(schedule = MedSchedule.WAKE, times = "480").timesText(", "))
        assertEquals("00:00", clockText(0))
        assertEquals("23:59", clockText(1_439))
    }

    /**
     * The top of an hour written with a bare `"%02d:00".format(hour)` takes the digits of the phone's
     * language for the hour alone, beside a time from [clockText], which never does: in Arabic one
     * range of the day would read "٠٨:00–08:37". [hourText] writes the hour as the clock does, and
     * says the 24:00 that ends the last hour, which the clock clamps to 23:59.
     */
    @Test
    fun anHourOfAChartIsWrittenInTheClocksOwnDigits() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            // What a bare format does on this phone: the reason the helper names its locale.
            assertEquals("٠٨:00", "%02d:00".format(8))
            assertEquals("08:00", hourText(8))
            assertEquals("24:00", hourText(24))
            assertEquals("08:37", clockText(8 * 60 + 37))
            assertEquals("7:05", formatPace(425))
        } finally {
            Locale.setDefault(was)
        }
        // And no clock is put together in place: a zero-padded field in a format with no locale.
        val inPlace = Regex(""""[^"]*%02d[^"]*"\.format\(""")
        val found = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { inPlace.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), found)
    }

    /**
     * Both readers turn an instant into a minute of the day, and both are handed the zone to do it
     * in. Reading them in the machine's own zone instead would put a Tokyo morning at half past ten
     * at night — and would pass every test that runs where the default zone is the one it passes.
     */
    @Test
    fun theWakeMinuteAndTheLastMealAreReadInTheZoneTheyAreHanded() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val evening = Instant.parse("2026-08-10T22:30:00Z").toEpochMilli()
        val night = listOf(sleepRow(endMs = evening))
        val lunch = listOf(mealRow(atMs = evening))

        assertEquals(22 * 60 + 30, wakeMinuteOf(night, today, utc))
        assertEquals(7 * 60 + 30, wakeMinuteOf(night, today, tokyo))
        assertEquals(22 * 60 + 30, lastMealMinuteOf(lunch, today, utc))
        assertEquals(7 * 60 + 30, lastMealMinuteOf(lunch, today, tokyo))
    }
}
