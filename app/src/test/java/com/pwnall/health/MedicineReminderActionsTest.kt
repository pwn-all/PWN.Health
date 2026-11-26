package com.pwnall.health

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.room.Room
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * A dose's notification and what its buttons do, from a meal written to Room to the row and the
 * preference each button leaves.
 *
 * The device test of the reminder (MedicineReminderDeliveryTest, in androidTest) runs only when
 * somebody runs it on a device, so what "I took it" and "Later" do is held here, where every
 * `check` runs it: Room on SQLite and the platform's NotificationManager as Robolectric carries
 * them, with [MedicineAlarms.markTaken] and [MedicineAlarms.snooze] called as
 * [MedicineTakenReceiver] and [MedicineSnoozeReceiver] call them, on a database in memory. The
 * sound of the channel, the full-screen page and a broadcast through the system stay with the
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class, instrumentedPackages = ["com.pwnall.health"])
class MedicineReminderActionsTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val db = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()

    private fun text(res: Int) = context.getString(res)

    private fun titles(notice: StatusBarNotification) = notice.notification.actions.map { it.title.toString() }

    private fun doseNotices() = manager.activeNotifications.filter { it.tag?.startsWith("dose:") == true }

    private val zoneBefore: TimeZone = TimeZone.getDefault()

    /**
     * The app reads the time from [System.currentTimeMillis], which Robolectric turns into its own
     * paused clock only in the classes it instruments: the app's package is among them here
     * (`instrumentedPackages`), and this class still reads the real clock. So the clock is set to a
     * known moment in a known zone, and the tests write their dates from [NOW], never from the real
     * clock: read from it, they would follow today's date and the doses would fall outside the
     * app's day.
     */
    @Before
    fun aKnownMoment() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        SystemClock.setCurrentTimeMillis(NOW)
    }

    @Before
    fun aProfileThatWantsReminders() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db.reminderApp = context
        db.dao().saveProfile(UserProfile(sex = "MALE", weightKg = 70.0, heightCm = 175, birthDate = "1990-01-01"))
    }

    @After
    fun close() {
        db.close()
        TimeZone.setDefault(zoneBefore)
    }

    /** A course due at this minute, after food, and the meal that makes its dose due. */
    private suspend fun aDoseComesDue(): StatusBarNotification {
        val now = Instant.ofEpochMilli(NOW).atZone(ZoneId.systemDefault())
        val day = now.toLocalDate().toString()
        db.dao().saveMedication(
            Medication(
                id = 987_654,
                name = "Test medicine",
                dose = "1",
                schedule = MedSchedule.CLOCK.name,
                times = (now.hour * 60 + now.minute).toString(),
                foodRelation = MedFood.AFTER.name,
                startDay = day,
                createdAt = now.toInstant().toEpochMilli(),
            ),
        )
        val atMs = now.toInstant().toEpochMilli()
        db.saveJournalEvent(JournalEntry(day = day, occurredAt = atMs, kind = "EAT", title = "Meal", calories = 100.0))
        return doseNotices().single()
    }

    private suspend fun dosesWritten(): Int = listOf(DAY, DAY.minusDays(1)).sumOf { day ->
        db.dao().journalOnNow(day.toString()).count { it.kind == KIND_MED }
    }

    /** Marking the dose comes first, and only a ringing notification has anything to silence. */
    @Test
    fun theCardOffersTakenAndLater() = runBlocking {
        MedicineAlarmNotification.setAlarmEnabled(context, false)
        assertEquals(listOf(R.string.med_action_taken, R.string.med_action_later).map(::text), titles(aDoseComesDue()))
    }

    @Test
    fun theRingingOneOffersStopSoundAsWell() = runBlocking {
        MedicineAlarmNotification.setAlarmEnabled(context, true)
        assertEquals(
            listOf(R.string.med_action_taken, R.string.med_action_later, R.string.med_alarm_dismiss).map(::text),
            titles(aDoseComesDue()),
        )
    }

    /** "I took it": the dose is written down once and its notification goes. */
    @Test
    fun takingTheDoseWritesItOnceAndTakesTheNotificationDown() = runBlocking {
        val notice = aDoseComesDue()
        assertEquals(0, dosesWritten())

        assertTrue(MedicineAlarms.markTaken(context, notice.tag, db))

        assertEquals(emptyList<StatusBarNotification>(), doseNotices())
        assertEquals(1, dosesWritten())
        // A second tap, from a notification the shade had not yet let go of, finds no dose to mark.
        assertFalse(MedicineAlarms.markTaken(context, notice.tag, db))
        assertEquals(1, dosesWritten())
    }

    /**
     * Two taps together — the shade's button and the widget's, or one bounce — could each read the
     * dose as open before either had written, and two rows would stand for one dose. The look and the
     * write are one transaction, so whichever comes second finds the first one's mark.
     */
    @Test
    fun twoTapsTogetherWriteTheDoseOnce() = runBlocking {
        val notice = aDoseComesDue()

        listOf(
            async { MedicineAlarms.markTaken(context, notice.tag, db) },
            async { MedicineAlarms.markTaken(context, notice.tag, db) },
        ).awaitAll()

        assertEquals(1, dosesWritten())
        assertEquals(emptyList<StatusBarNotification>(), doseNotices())
    }

    /**
     * "Later": the notification goes, nothing is written to the journal, and the dose is filed as
     * put off until a quarter of an hour from now, which is what brings it back.
     */
    @Test
    fun puttingTheDoseOffTakesTheNotificationDownAndWritesWhenItComesBack() = runBlocking {
        val notice = aDoseComesDue()

        assertTrue(MedicineAlarms.snooze(context, notice.tag, db))

        assertEquals(emptyList<StatusBarNotification>(), doseNotices())
        assertEquals(0, dosesWritten())
        val until = context.getSharedPreferences("medicine_alarms", Context.MODE_PRIVATE)
            .getLong("snooze:${notice.tag}", 0L)
        assertEquals(NOW + MedicineAlarms.SNOOZE_MS, until)
    }

    /** A course whose dose came due [lateMinutes] ago on the wall clock, as [dueMedicine] counts, and its card. */
    private suspend fun aDoseLate(lateMinutes: Long): StatusBarNotification {
        val due = LocalDateTime.ofInstant(Instant.ofEpochMilli(NOW), ZoneOffset.UTC).minusMinutes(lateMinutes)
        db.dao().saveMedication(
            Medication(
                id = 987_655,
                name = "Test medicine",
                dose = "1",
                schedule = MedSchedule.CLOCK.name,
                times = (due.hour * 60 + due.minute).toString(),
                foodRelation = MedFood.ANY.name,
                startDay = due.toLocalDate().toString(),
                createdAt = NOW,
            ),
        )
        MedicineAlarms.refresh(context, db)
        return doseNotices().single()
    }

    /**
     * "Later" in a dose's last quarter of an hour cannot be kept, and is not offered. Pressed on a card
     * that still has it, the tap cannot put the dose off, so the card goes up again quiet and without
     * it rather than ringing on.
     */
    @Test
    fun laterInTheDosesLastQuarterOfAnHourLeavesItsCardQuietAndWithoutLater() = runBlocking {
        val ring = aDoseLate(230)
        assertEquals(listOf(R.string.med_action_taken, R.string.med_alarm_dismiss).map(::text), titles(ring))

        assertFalse(MedicineAlarms.snooze(context, ring.tag, db))

        val card = doseNotices().single()
        assertEquals(Reminders.MEDICINE_CHANNEL, card.notification.channelId)
        assertEquals(listOf(text(R.string.med_action_taken)), titles(card))
        assertEquals(0, dosesWritten())
    }

    /** The card opens the Medicine tab, as the dose widget does, not the home page. */
    @Test
    fun theCardOpensTheMedicineTab() = runBlocking {
        MedicineAlarmNotification.setAlarmEnabled(context, false)
        val opens = shadowOf(aDoseComesDue().notification.contentIntent).savedIntent
        assertEquals(WidgetRoute.MEDICINE, opens.getStringExtra(WidgetRoute.EXTRA))
    }

    /** "I took it" on a card whose dose was marked meanwhile, with no check in between: the card goes. */
    @Test
    fun takingADoseMarkedMeanwhileTakesItsCardDown() = runBlocking {
        val notice = aDoseComesDue()
        val day = DAY
        val dose = plannedDoses(db.dao().allMedications(), emptyList(), day).single()
        db.dao().saveJournalEntry(medTakenEntry(dose, day, NOW))
        assertEquals(1, doseNotices().size)

        assertFalse(MedicineAlarms.markTaken(context, notice.tag, db))

        assertEquals(emptyList<StatusBarNotification>(), doseNotices())
        assertEquals(1, dosesWritten())
    }

    /**
     * The alarm switched on leaves a card that is up standing. Taking every card down at either turn
     * of the switch while the record of what it said stays would keep the dose quiet in an empty shade
     * until its hour came round.
     */
    @Test
    fun switchingTheAlarmOnLeavesTheCardStanding() = runBlocking {
        MedicineAlarmNotification.setAlarmEnabled(context, false)
        val card = aDoseComesDue()

        MedicineAlarmNotification.setAlarmEnabled(context, true)

        assertEquals(listOf(card.tag), doseNotices().map { it.tag })
    }

    /** Switched off, the alarm stops a ring under way; the card follows once the ring's minute is up. */
    @Test
    fun switchingTheAlarmOffStopsARing() = runBlocking {
        MedicineAlarmNotification.setAlarmEnabled(context, true)
        assertEquals("medicine_alarm", aDoseComesDue().notification.channelId)

        MedicineAlarmNotification.setAlarmEnabled(context, false)

        assertEquals(emptyList<StatusBarNotification>(), doseNotices())
    }

    /**
     * Reminders switched off take every dose's notification down, and switched on again bring them
     * back: a record of what was said, left behind, would hold them back from an empty shade for the
     * hour.
     */
    @Test
    fun remindersSwitchedOffAndOnAgainBringTheDoseBack() = runBlocking {
        val card = aDoseComesDue()
        val profile = UserProfile(sex = "MALE", weightKg = 70.0, heightCm = 175, birthDate = "1990-01-01")

        db.dao().saveProfile(profile.copy(remindersEnabled = false))
        MedicineAlarms.refresh(context, db)
        assertEquals(emptyList<StatusBarNotification>(), doseNotices())

        db.dao().saveProfile(profile)
        MedicineAlarms.refresh(context, db)
        assertEquals(listOf(card.tag), doseNotices().map { it.tag })
    }

    /**
     * A dose put off by hand stays put off through every check made meanwhile — its record and the
     * moment it comes back both survive the sweep of old keys — and is announced again once its
     * quarter of an hour is up, with nothing written to the journal.
     */
    @Test
    fun aDosePutOffStaysPutOffThroughEveryCheckAndComesBackWhenItsQuarterOfAnHourIsUp() = runBlocking {
        val notice = aDoseComesDue()
        assertTrue(MedicineAlarms.snooze(context, notice.tag, db))
        val prefs = context.getSharedPreferences("medicine_alarms", Context.MODE_PRIVATE)

        SystemClock.setCurrentTimeMillis(NOW + MedicineAlarms.SNOOZE_MS - 1)
        MedicineAlarms.refresh(context, db)
        assertEquals(emptyList<StatusBarNotification>(), doseNotices())
        assertTrue(prefs.contains("snooze:${notice.tag}"))
        assertTrue(prefs.contains(notice.tag))

        SystemClock.setCurrentTimeMillis(NOW + MedicineAlarms.SNOOZE_MS)
        MedicineAlarms.refresh(context, db)
        assertEquals(listOf(notice.tag), doseNotices().map { it.tag })
        assertEquals(0, dosesWritten())
    }

    /** "I took it" at ten past midnight on the 23:50 dose files it under the evening it belonged to. */
    @Test
    fun takingYesterdaysLateDoseAfterMidnightFilesItUnderYesterday() = runBlocking {
        db.dao().saveMedication(
            Medication(id = 987_656, name = "Test medicine", times = "1430", startDay = DAY.toString(), createdAt = 0),
        )
        SystemClock.setCurrentTimeMillis(DAY.plusDays(1).atTime(0, 10).toInstant(ZoneOffset.UTC).toEpochMilli())
        MedicineAlarms.refresh(context, db)
        val notice = doseNotices().single()
        assertTrue(notice.tag, notice.tag.startsWith("dose:$DAY:1430:"))

        assertTrue(MedicineAlarms.markTaken(context, notice.tag, db))

        assertEquals(1, db.dao().journalOnNow(DAY.toString()).count { it.kind == KIND_MED })
        assertEquals(0, db.dao().journalOnNow(DAY.plusDays(1).toString()).count { it.kind == KIND_MED })
        assertEquals(emptyList<StatusBarNotification>(), doseNotices())
    }

    /**
     * The dose widget's Taken marks the dose of the day its face was drawn for, once however often it
     * is tapped, and nothing for a face older than yesterday, a slot since changed or a course since
     * deleted.
     */
    @Test
    fun theWidgetsTakenMarksTheFacesDoseOnceAndNothingTheFaceNoLongerNames() = runBlocking {
        val id = 987_657L
        db.dao().saveMedication(
            Medication(id = id, name = "Test medicine", times = "1320", startDay = "2026-09-01", createdAt = 0),
        )
        val today = DAY.plusDays(1)
        val face = DAY.toString()

        assertTrue(markTappedDose(db, id, "1320", face, today))
        assertFalse(markTappedDose(db, id, "1320", face, today))
        assertEquals(1, db.dao().journalOnNow(face).count { it.kind == KIND_MED })

        val twoDaysBack = DAY.minusDays(1).toString()
        assertFalse(markTappedDose(db, id, "1320", twoDaysBack, today))
        assertFalse(markTappedDose(db, id, "1200", today.toString(), today))
        db.dao().deleteMedication(id)
        assertFalse(markTappedDose(db, id, "1320", today.toString(), today))
        listOf(twoDaysBack, today.toString()).forEach { day ->
            assertEquals(day, 0, db.dao().journalOnNow(day).count { it.kind == KIND_MED })
        }
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 9, 7)

        /** 13:00 on [DAY], in the zone the tests set. */
        val NOW: Long = DAY.atTime(13, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
