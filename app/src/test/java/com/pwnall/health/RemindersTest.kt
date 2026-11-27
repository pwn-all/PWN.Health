package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalTime

class RemindersTest {

    @Test
    fun `a denied notification does not consume the goal announcement`() {
        assertFalse(consumesGoalAnnouncement(ReminderKind.GOAL_REACHED, delivered = false))
        assertTrue(consumesGoalAnnouncement(ReminderKind.GOAL_REACHED, delivered = true))
        assertFalse(consumesGoalAnnouncement(ReminderKind.MOVE, delivered = true))
    }

    private fun reminder(
        hour: Int,
        steps: Long = 0,
        goal: Long = 8_000,
        announced: Boolean = false,
        idleMinutes: Long? = null,
        logged: Boolean = true,
    ) = reminderFor(
        now = LocalTime.of(hour, 0),
        steps = steps,
        goal = goal,
        goalAlreadyAnnounced = announced,
        minutesSinceLastStep = idleMinutes,
        loggedAnythingToday = logged,
    )

    @Test
    fun nothingIsSaidBeforeNineOrAfterTen() {
        assertEquals(ReminderKind.NONE, reminder(hour = 7, steps = 9_000))
        assertEquals(ReminderKind.NONE, reminder(hour = 23, steps = 9_000))
        assertEquals(ReminderKind.NONE, reminder(hour = 3, idleMinutes = 600))
    }

    @Test
    fun theGoalIsAnnouncedOnceAndOnlyOnce() {
        assertEquals(ReminderKind.GOAL_REACHED, reminder(hour = 12, steps = 8_000))
        assertEquals(ReminderKind.NONE, reminder(hour = 12, steps = 9_000, announced = true))
    }

    @Test
    fun aMissingGoalIsNeverReached() {
        assertEquals(ReminderKind.NONE, reminder(hour = 12, steps = 9_000, goal = 0))
    }

    @Test
    fun twoStillHoursEarnANudge() {
        assertEquals(ReminderKind.NONE, reminder(hour = 12, idleMinutes = 119))
        assertEquals(ReminderKind.MOVE, reminder(hour = 12, idleMinutes = 120))
    }

    @Test
    fun theEveningReminderOnlyFiresWhenNothingWasLogged() {
        assertEquals(ReminderKind.NONE, reminder(hour = 19, logged = true))
        assertEquals(ReminderKind.LOG_SOMETHING, reminder(hour = 19, logged = false))
        assertEquals(ReminderKind.NONE, reminder(hour = 18, logged = false))
    }

    /**
     * A phone spends the night on a nightstand, so the raw gap at nine in the morning is already
     * the whole night. Counting it would make the first nudge of every day a scolding for sleeping.
     */
    @Test
    fun theNightDoesNotCountTowardsTheIdleThreshold() {
        assertEquals(30L, wakingIdleMinutes(600, LocalTime.of(9, 30)))
        // The raw gap is what the rule would otherwise be handed, and it earns a nudge on its own.
        assertEquals(ReminderKind.MOVE, reminder(hour = 9, idleMinutes = 600))
        assertEquals(
            ReminderKind.NONE,
            reminder(hour = 9, idleMinutes = wakingIdleMinutes(600, LocalTime.of(9, 30))),
        )
    }

    /** Stillness after the quiet hours end still accumulates at its own pace. */
    @Test
    fun wakingStillnessStillEarnsTheNudge() {
        assertEquals(120L, wakingIdleMinutes(600, LocalTime.of(11, 0)))
        assertEquals(90L, wakingIdleMinutes(90, LocalTime.of(14, 0)))
        assertNull(wakingIdleMinutes(null, LocalTime.of(14, 0)))
    }

    /**
     * "No steps for two hours" is said of the phone's own last step, so it is said only while that
     * stamp speaks for the person: the phone is counting, and nothing else has counted more.
     */
    @Test
    fun theIdleMinutesAreThePhonesOwnWhileThePhoneIsWhatCounts() {
        val now = 1_785_400_000_000L
        val twoHoursAgo = now - 120 * 60_000L

        assertEquals(120L, idleMinutesForNudge(twoHoursAgo, now, counting = true, localSteps = 900, mergedSteps = 900))
        assertEquals(120L, idleMinutesForNudge(twoHoursAgo, now, counting = true, localSteps = 0, mergedSteps = 0))
    }

    /**
     * The stamp stands still when the phone does not count — the permission taken back, the service not
     * running — and when a band or Health Connect has the day's steps. Counted from in any of those, it
     * would send the nudge every hour of the day, about steps the app knows nothing of.
     */
    @Test
    fun aStampThatSaysNothingAboutThePersonEarnsNoNudge() {
        val now = 1_785_400_000_000L
        val twoHoursAgo = now - 120 * 60_000L
        val threeDaysAgo = now - 3 * 24 * 60 * 60_000L

        assertNull(idleMinutesForNudge(twoHoursAgo, now, counting = false, localSteps = 900, mergedSteps = 900))
        assertNull(idleMinutesForNudge(twoHoursAgo, now, counting = true, localSteps = 900, mergedSteps = 4_200))
        assertNull(idleMinutesForNudge(threeDaysAgo, now, counting = true, localSteps = 0, mergedSteps = 0))
        assertNull(idleMinutesForNudge(null, now, counting = true, localSteps = 0, mergedSteps = 0))
        assertNull(idleMinutesForNudge(0L, now, counting = true, localSteps = 0, mergedSteps = 0))
        // Just inside a day still counts; the waking-hours cap is what keeps the night out of it.
        assertEquals(1_439L, idleMinutesForNudge(now - 1_439 * 60_000L, now, counting = true, 0, 0))
        assertNull(idleMinutesForNudge(now - 1_440 * 60_000L, now, counting = true, 0, 0))
    }

    /** Two notifications in one hour is how an app gets muted. */
    @Test
    fun goodNewsWinsWhenSeveralRulesMatch() {
        assertEquals(
            ReminderKind.GOAL_REACHED,
            reminder(hour = 20, steps = 9_000, idleMinutes = 300, logged = false),
        )
    }

    private val announced = Appointment(id = 3, specialty = "GP", place = "Clinic", atMs = 1_785_400_000_000L)

    /** The row as it stands is what is marked, so a place corrected while the reminder went out is kept. */
    @Test
    fun anEditMadeWhileTheReminderWentOutIsKept() {
        val corrected = announced.copy(place = "Clinic, room 4")

        assertEquals(
            corrected.copy(remindedAt = 42L),
            visitAnnounced(listOf(corrected), announced, atMs = 42L),
        )
    }

    /** A visit deleted while its reminder went out stays deleted, and one moved keeps a reminder to come. */
    @Test
    fun aVisitDeletedOrMovedMeanwhileIsNotMarked() {
        assertNull(visitAnnounced(emptyList(), announced, atMs = 42L))
        assertNull(visitAnnounced(listOf(announced.copy(atMs = announced.atMs + 3_600_000L)), announced, 42L))
        assertNull(visitAnnounced(listOf(announced.copy(remindDaysBefore = 2)), announced, 42L))
    }

    /**
     * The other side of the same race: the editor was opened before the reminder went out and saved
     * after it. Its copy of the row has no mark, and a save that wrote none would have the visit
     * announced a second time. The mark is read from the row as it stands; a visit moved, or given
     * another notice, loses it and has a reminder of its own to come.
     */
    @Test
    fun aCorrectionSavedAfterTheReminderWentOutKeepsItsMark() {
        val corrected = announced.copy(place = "Clinic, room 4")
        val stamped = announced.copy(remindedAt = 42L)

        assertEquals(corrected.copy(remindedAt = 42L), visitSavedOver(corrected, stamped))
        assertNull(visitSavedOver(corrected.copy(atMs = announced.atMs + 3_600_000L), stamped).remindedAt)
        assertNull(visitSavedOver(corrected.copy(remindDaysBefore = 2), stamped).remindedAt)
        assertNull(visitSavedOver(corrected.copy(id = 0), null).remindedAt)
        val saving = File("src/main/java/com/pwnall/health/MedicineScreen.kt").readText()
            .substringAfter("val edited = visit.toAppointment(zone)").substringBefore("page = ")
        assertTrue(saving, "db.withTransaction {" in saving)
        assertTrue(saving, "dao.saveAppointment(visitSavedOver(edited, stored))" in saving)
    }
}
