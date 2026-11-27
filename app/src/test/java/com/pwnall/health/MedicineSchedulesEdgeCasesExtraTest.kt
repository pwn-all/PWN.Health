package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The other half of the medicine cabinet's edges: the payload a marked dose is recovered from, the
 * examination catalogue as a catalogue, and the hour an appointment is announced at.
 *
 * A screening clock is wound from a single date, so a day either side of it is a month either side
 * of the next invitation; a reminder is announced by a worker that wakes once an hour, so a
 * millisecond of slack at its boundary is an hour of silence in the real world.
 */
class MedicineSchedulesEdgeCasesExtraTest {
    private val today: LocalDate = LocalDate.of(2026, 8, 10)
    private val utc: ZoneId = ZoneOffset.UTC

    /** The wall clock, built the way a calendar reads it rather than by adding elapsed minutes. */
    private fun msAt(day: LocalDate, minute: Int, zone: ZoneId = utc): Long =
        day.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()

    private fun medRow(payload: String) = JournalEntry(
        day = today.toString(),
        occurredAt = msAt(today, 500),
        kind = KIND_MED,
        title = "Metformin",
        calories = 0.0,
        measurementPayloadJson = payload,
    )

    private fun visitAt(day: LocalDate, minute: Int, remindDaysBefore: Int = 1, id: Long = 1) = Appointment(
        id = id,
        specialty = VISIT_SPECIALTY_OTHER,
        atMs = msAt(day, minute),
        remindDaysBefore = remindDaysBefore,
    )

    private fun doneVisit(checkup: Checkup, day: LocalDate) = Appointment(
        specialty = checkup.name,
        atMs = msAt(day, 600),
        doneAt = msAt(day, 700),
    )

    private fun statusOf(checkup: Checkup, visit: Appointment, on: LocalDate, zone: ZoneId = utc) =
        checkupStatuses(listOf(checkup), listOf(visit), on, zone).single()

    /**
     * The payload is matched with two patterns rather than parsed, so the failure mode of a row this
     * build cannot read is a half-match, not an exception. A restored backup that was edited by hand
     * — or written by a build that numbered courses differently — must settle no dose at all rather
     * than the wrong one, and must never throw on the way to the day list.
     */
    @Test
    fun aPayloadTheAppDidNotWriteIsRefusedRatherThanHalfRead() {
        val spaced = """{ "$MED_COURSE_KEY" : 7 , "$MED_SLOT_KEY" : "$MED_SLOT_WAKE" }"""
        assertEquals(7L to MED_SLOT_WAKE, medSlotOf(medRow(spaced)))

        assertNull(medSlotOf(medRow("""{"$MED_COURSE_KEY":7,"$MED_SLOT_KEY":"48""")))
        assertNull(medSlotOf(medRow("""{"$MED_COURSE_KEY":99999999999999999999,"$MED_SLOT_KEY":"480"}""")))
        assertNull(medSlotOf(medRow("""{"$MED_COURSE_KEY":7,"$MED_SLOT_KEY":""}""")))
        assertNull(medSlotOf(medRow("""{"$MED_SLOT_KEY":"480"}""")))
        assertNull(medSlotOf(medRow("""{"$MED_COURSE_KEY":7}""")))

        // A row of another kind carrying the same payload is not a dose.
        val wellFormed = """{"$MED_COURSE_KEY":7,"$MED_SLOT_KEY":"480"}"""
        assertNull(medSlotOf(medRow(wellFormed).copy(kind = "EAT")))
        assertEquals(7L to "480", medSlotOf(medRow(wellFormed)))
    }

    /**
     * A new screening row is eight positional arguments. A sex string that matches neither of the
     * profile's two values, an age pair the wrong way round, or a name colliding with the catch-all
     * specialty all fail silently: the row simply never reaches anybody's screen.
     */
    @Test
    fun theExaminationCatalogueHoldsTogetherAsACatalogue() {
        Checkup.entries.forEach { checkup ->
            val label = checkup.name
            assertTrue("$label title", checkup.titleRes != 0)
            assertTrue("$label sex ${checkup.sex}", checkup.sex in listOf(null, "MALE", "FEMALE"))
            assertTrue("$label ${checkup.fromAge}..${checkup.toAge}", (checkup.toAge ?: 120) >= checkup.fromAge)
            assertFalse("$label is the catch-all specialty", label == VISIT_SPECIALTY_OTHER)

            val sex = checkup.sex ?: "FEMALE"
            assertTrue("$label at ${checkup.fromAge}", checkup in checkupsFor(sex, checkup.fromAge))
            assertTrue("$label at ${checkup.toAge ?: 120}", checkup in checkupsFor(sex, checkup.toAge ?: 120))
        }

        val titles = Checkup.entries.map(Checkup::titleRes)
        assertEquals("was $titles", Checkup.entries.size, titles.distinct().size)
    }

    /**
     * The years the cited guidance actually names: cytology from 21 to 65, bowel screening from 45
     * to 75, eyes and urology from 40, and the adult reviews from 18. A programme that starts a year
     * early is advice this app is not entitled to give.
     */
    @Test
    fun eachScreeningsFirstAndLastInvitationYear() {
        assertFalse("cervical at 20", Checkup.CERVICAL in checkupsFor("FEMALE", 20))
        assertTrue("cervical at 21", Checkup.CERVICAL in checkupsFor("FEMALE", 21))
        assertTrue("cervical at 65", Checkup.CERVICAL in checkupsFor("FEMALE", 65))
        assertFalse("cervical at 66", Checkup.CERVICAL in checkupsFor("FEMALE", 66))

        assertFalse("eyes at 39", Checkup.EYES in checkupsFor("MALE", 39))
        assertTrue("eyes at 40", Checkup.EYES in checkupsFor("MALE", 40))

        assertFalse("urologist at 39", Checkup.UROLOGIST in checkupsFor("MALE", 39))
        assertTrue("urologist at 40", Checkup.UROLOGIST in checkupsFor("MALE", 40))

        assertFalse("bowel at 44", Checkup.BOWEL in checkupsFor("MALE", 44))
        assertTrue("bowel at 45", Checkup.BOWEL in checkupsFor("MALE", 45))
        assertTrue("bowel at 75", Checkup.BOWEL in checkupsFor("MALE", 75))
        assertFalse("bowel at 76", Checkup.BOWEL in checkupsFor("MALE", 76))

        assertFalse("mammography at 39", Checkup.MAMMOGRAPHY in checkupsFor("FEMALE", 39))
        assertTrue("mammography at 40", Checkup.MAMMOGRAPHY in checkupsFor("FEMALE", 40))

        listOf(Checkup.GP, Checkup.DENTIST, Checkup.GYNECOLOGIST).forEach { adult ->
            assertFalse("${adult.name} at 17", adult in checkupsFor("FEMALE", 17))
            assertTrue("${adult.name} at 18", adult in checkupsFor("FEMALE", 18))
        }
    }

    /**
     * These are hand-written tables of resource ids. A copy-pasted row would have "take it with
     * food" and "no meal logged yet" say the same sentence, which every other test would let
     * through because both still produce a nudge.
     */
    @Test
    fun everyFoodInstructionAndEveryNudgeReasonCarriesItsOwnSentence() {
        val titles = MedFood.entries.map(MedFood::titleRes)
        val hints = MedFood.entries.map(MedFood::hintRes)
        val bodies = MedReason.entries.map(MedReason::bodyRes)

        assertEquals("was $titles", MedFood.entries.size, titles.distinct().size)
        assertEquals("was $hints", MedFood.entries.size, hints.distinct().size)
        assertEquals("was $bodies", MedReason.entries.size, bodies.distinct().size)
        assertTrue("was $titles", titles.none { it == 0 })
        assertTrue("was $hints", hints.none { it == 0 })
        assertTrue("was $bodies", bodies.none { it == 0 })
        // The instruction and the sentence under it are two different strings.
        assertTrue("$titles vs $hints", titles.none { it in hints })
    }

    /**
     * A half past eleven appointment in Tokyo is half past two in the afternoon in London's
     * yesterday. The day a visit counts as having happened on is the day it was on the person's own
     * calendar, and the next recall is a year from that day rather than from the day before it.
     */
    @Test
    fun aLateEveningVisitWindsTheClockOnTheLocalDate() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val at = Instant.parse("2026-08-10T23:30:00Z").toEpochMilli()
        val visit = Appointment(specialty = Checkup.GP.name, atMs = at, doneAt = at)

        val status = statusOf(Checkup.GP, visit, LocalDate.of(2026, 8, 20), tokyo)

        assertEquals(LocalDate.of(2026, 8, 11), status.lastDay)
        assertEquals(LocalDate.of(2027, 8, 11), status.dueOn)
    }

    /**
     * A dentist seen on the last day of August is recalled six months later, and February has no
     * thirty-first: the recall lands on the twenty-eighth rather than spilling into March. That
     * shortened interval is also the denominator the progress bar is drawn from.
     */
    @Test
    fun aSixMonthRecallFromTheThirtyFirstLandsOnTheLastDayOfTheShorterMonth() {
        val visit = doneVisit(Checkup.DENTIST, LocalDate.of(2026, 8, 31))
        val due = LocalDate.of(2027, 2, 28)

        val onTheDay = statusOf(Checkup.DENTIST, visit, due)
        assertEquals(due, onTheDay.dueOn)
        assertEquals(0L, onTheDay.daysLeft)
        assertTrue(onTheDay.due)
        assertTrue(onTheDay.overdue)
        assertEquals(1.0, onTheDay.elapsedShare, 1e-9)

        val dayBefore = statusOf(Checkup.DENTIST, visit, due.minusDays(1))
        assertEquals(1L, dayBefore.daysLeft)
        assertFalse(dayBefore.due)
        assertFalse(dayBefore.overdue)
        // 181 days from the last day of August to the last day of the following February.
        assertEquals(180.0 / 181.0, dayBefore.elapsedShare, 1e-9)
    }

    /**
     * The one date in the calendar that only exists every fourth year. A yearly recall booked on it
     * comes due on the twenty-eighth of the following February — not pushed into March, and not
     * skipped until the leap year comes round again.
     */
    @Test
    fun aYearlyRecallBookedOnLeapDayComesDueInTheNonLeapYear() {
        val visit = doneVisit(Checkup.GP, LocalDate.of(2024, 2, 29))
        val due = LocalDate.of(2025, 2, 28)

        val onTheDay = statusOf(Checkup.GP, visit, due)
        assertEquals(due, onTheDay.dueOn)
        assertEquals(0L, onTheDay.daysLeft)
        assertTrue(onTheDay.overdue)
        assertEquals(1.0, onTheDay.elapsedShare, 1e-9)

        val dayAfter = statusOf(Checkup.GP, visit, LocalDate.of(2025, 3, 1))
        assertEquals(-1L, dayAfter.daysLeft)
        // A year and a day gone is still a full bar rather than one past its end.
        assertEquals(1.0, dayAfter.elapsedShare, 1e-9)
        // 365 days: the leap day is behind the visit, not ahead of it.
        assertEquals(364.0 / 365.0, statusOf(Checkup.GP, visit, due.minusDays(1)).elapsedShare, 1e-9)
    }

    /**
     * A visit in two days asked to be announced on the day itself, and one in five days asked for a
     * week's warning. Only the second can be spoken about this morning, and the worker must say it
     * rather than falling silent for two days because a nearer visit is not ready yet.
     */
    @Test
    fun theSoonestAnnounceableVisitWinsAndNotTheSoonestVisit() {
        val now = msAt(today, 9 * 60)
        val near = visitAt(today.plusDays(2), 15 * 60, remindDaysBefore = 0, id = 1)
        val far = visitAt(today.plusDays(5), 15 * 60, remindDaysBefore = 7, id = 2)

        assertEquals(2L, appointmentToRemind(listOf(near, far), now, utc)?.id)
        // The order they come out of the database in decides nothing.
        assertEquals(2L, appointmentToRemind(listOf(far, near), now, utc)?.id)
        assertNull(appointmentToRemind(listOf(near), now, utc))
    }

    /**
     * The quiet hours end at nine, and a visit at half past nine asked to be told on the day. Waiting
     * for nine o'clock would leave half an hour's warning, and a visit one minute before ten would
     * get one minute. An hour before is what the reminder is worth.
     */
    @Test
    fun aMidMorningVisitStillGetsItsFullHourOfWarning() {
        val halfPastNine = visitAt(today, 9 * 60 + 30, remindDaysBefore = 0)
        val oneToTen = visitAt(today, 9 * 60 + 59, remindDaysBefore = 0)
        val ten = visitAt(today, 10 * 60, remindDaysBefore = 0)

        assertEquals(msAt(today, 8 * 60 + 30), appointmentRemindAt(halfPastNine, utc))
        assertEquals(msAt(today, 8 * 60 + 59), appointmentRemindAt(oneToTen, utc))
        // Ten o'clock is the first hour where the morning itself is warning enough.
        assertEquals(msAt(today, 9 * 60), appointmentRemindAt(ten, utc))
    }

    /**
     * The column is written by a form but read from a backup anybody can edit. A warning of minus
     * three days cannot be honoured as three days *after* the visit: that is a notification about an
     * appointment the person has already missed.
     */
    @Test
    fun aNegativeWarningIsNotAReminderAfterTheVisit() {
        val backwards = visitAt(today.plusDays(1), 15 * 60, remindDaysBefore = -3)
        val onTheDay = backwards.copy(remindDaysBefore = 0)

        assertEquals(msAt(today.plusDays(1), 9 * 60), appointmentRemindAt(backwards, utc))
        assertEquals(appointmentRemindAt(onTheDay, utc), appointmentRemindAt(backwards, utc))
        assertTrue(
            "was ${appointmentRemindAt(backwards, utc)} for a visit at ${backwards.atMs}",
            appointmentRemindAt(backwards, utc) < backwards.atMs,
        )
    }

    /**
     * The worker wakes on the hour, so the run that lands exactly on a reminder's moment is the one
     * that has to speak: an exclusive comparison here would push every reminder a whole period late.
     */
    @Test
    fun theReminderMomentItselfCountsAsDue() {
        val visit = visitAt(today.plusDays(1), 15 * 60, remindDaysBefore = 1)
        val remindAt = appointmentRemindAt(visit, utc)

        assertNotNull(appointmentToRemind(listOf(visit), remindAt, utc))
        assertNull(appointmentToRemind(listOf(visit), remindAt - 1, utc))
    }

    /**
     * Two screens split the same instant between them. A visit whose time is exactly now is still
     * ahead — the person is walking into it — and must appear on one list only, never on both and
     * never on neither.
     */
    @Test
    fun anAppointmentHappeningThisInstantIsUpcomingAndNotPastDue() {
        val now = msAt(today, 15 * 60)
        val onTheDot = visitAt(today, 15 * 60)

        assertEquals(listOf(1L), upcomingAppointments(listOf(onTheDot), now).map(Appointment::id))
        assertTrue(pastDueAppointments(listOf(onTheDot), now).isEmpty())
        assertNotNull(appointmentToRemind(listOf(onTheDot), now, utc))

        val amomentAgo = onTheDot.copy(atMs = now - 1)
        assertTrue(upcomingAppointments(listOf(amomentAgo), now).isEmpty())
        assertEquals(listOf(1L), pastDueAppointments(listOf(amomentAgo), now).map(Appointment::id))
        // Still inside the worker's own period, so it is still worth announcing.
        assertNotNull(appointmentToRemind(listOf(amomentAgo), now, utc))
    }
}
