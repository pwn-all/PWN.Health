package com.pwnall.health

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** Real Room commits and system notifications, with an isolated database. */
@RunWith(AndroidJUnit4::class)
class MedicineReminderDeliveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var db: StepsDb
    private var wasAlarm = true

    @Before
    fun prepare() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
        ).close()
        db = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
        db.reminderApp = context.applicationContext as Application
        db.dao().saveProfile(UserProfile(sex = "MALE", weightKg = 70.0, heightCm = 175, birthDate = "1990-01-01"))
        wasAlarm = MedicineAlarmNotification.alarmEnabled(context)
        MedicineAlarmNotification.setAlarmEnabled(context, false)
        // Switching the alarm off takes down only a ring under way; a quiet card a crashed run left
        // up would break every `single` below, so every dose's card goes here too.
        MedicineAlarms.cancel(context)
        context.getSharedPreferences("medicine_alarms", Context.MODE_PRIVATE).edit { clear() }
    }

    @After
    fun finish() {
        MedicineAlarms.cancel(context)
        MedicineAlarmNotification.setAlarmEnabled(context, wasAlarm)
        context.getSharedPreferences("medicine_alarms", Context.MODE_PRIVATE).edit { clear() }
        db.close()
    }

    @Test
    fun mealCommitImmediatelyNotifiesAndMarkingTheDoseCancelsIt() = runBlocking {
        val (course, food) = records(MedFood.AFTER)
        db.dao().saveMedication(course)
        assertFalse(MedicineAlarms.refresh(context, db))
        db.saveJournalEvent(food)
        eventually { manager.activeNotifications.any { it.tag?.startsWith("dose:") == true } }
        val notice = manager.activeNotifications.single { it.tag?.startsWith("dose:") == true }
        assertEquals(Notification.CATEGORY_REMINDER, notice.notification.category)
        assertTrue(notice.notification.extras.getString(Notification.EXTRA_TEXT).orEmpty().isNotBlank())
        val posted = notice.postTime
        db.saveJournalEvent(food.copy(id = db.dao().journalOnNow(food.day).single().id))
        assertEquals(posted, manager.activeNotifications.single { it.tag == notice.tag }.postTime)
        val day = Instant.ofEpochMilli(food.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val dose = plannedDoses(listOf(course), emptyList(), day).single()
        db.saveJournalEvent(medTakenEntry(dose, day, System.currentTimeMillis()))
        eventually { manager.activeNotifications.none { it.tag == notice.tag } }
    }

    @Test
    fun eatingChangesTheBeforeFoodMessageAndDisablingRemindersCancelsIt() = runBlocking {
        val (course, food) = records(MedFood.BEFORE)
        db.dao().saveMedication(course)
        assertTrue(MedicineAlarms.refresh(context, db))
        db.saveJournalEvent(food)
        eventually { manager.activeNotifications.any { it.tag?.startsWith("dose:") == true } }
        val notice = manager.activeNotifications.single { it.tag?.startsWith("dose:") == true }
        val at = context.getString(R.string.med_dose_at, clockText(course.doseMinutes().single()))
        assertEquals(
            context.getString(R.string.med_reminder_missed_before, at),
            notice.notification.extras.getString(Notification.EXTRA_TEXT),
        )
        db.dao().saveProfile(db.dao().profileNow()!!.copy(remindersEnabled = false))
        assertFalse(MedicineAlarms.refresh(context, db))
        eventually { manager.activeNotifications.none { it.tag == notice.tag } }
    }

    @Test
    fun alarmModeUsesAlarmSoundCategoryAndStopsOnDismiss() = runBlocking {
        val (course, food) = records(MedFood.AFTER)
        MedicineAlarmNotification.setAlarmEnabled(context, true)
        db.dao().saveMedication(course)
        db.saveJournalEvent(food)
        eventually { manager.activeNotifications.any { it.tag?.startsWith("dose:") == true } }
        val notice = manager.activeNotifications.single { it.tag?.startsWith("dose:") == true }
        assertEquals(Notification.CATEGORY_ALARM, notice.notification.category)
        assertTrue(notice.notification.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(MedicineAlarmNotification.RING_MS, notice.notification.timeoutAfter)
        val channel = manager.getNotificationChannel(notice.notification.channelId)
        assertNotNull(channel.sound)
        assertTrue(channel.shouldVibrate())
        // Three actions, with "I took it" and "Later" beside it; the one that only silences is found
        // by its label, not by `actions.single()`. What the
        // other two do is MedicineReminderActionsTest's, in the unit tests.
        assertEquals(
            listOf(R.string.med_action_taken, R.string.med_action_later, R.string.med_alarm_dismiss)
                .map { context.getString(it) },
            notice.notification.actions.map { it.title.toString() },
        )
        val silence = context.getString(R.string.med_alarm_dismiss)
        notice.notification.actions.single { it.title.toString() == silence }.actionIntent.send()
        eventually { manager.activeNotifications.none { it.tag == notice.tag } }
        assertTrue(db.dao().journalOnNow(food.day).none { it.kind == KIND_MED })
    }

    /** NotificationManager queues delivery and cancellation on a system handler. */
    private suspend fun eventually(check: () -> Boolean) {
        withTimeout(2_000) {
            while (!check()) delay(20)
        }
    }

    private fun records(food: MedFood): Pair<Medication, JournalEntry> {
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        val day = now.toLocalDate().toString()
        return Medication(
            id = 987_654,
            name = "Test medicine",
            dose = "1",
            schedule = MedSchedule.CLOCK.name,
            times = (now.hour * 60 + now.minute).toString(),
            foodRelation = food.name,
            startDay = day,
            createdAt = now.toInstant().toEpochMilli(),
        ) to
            JournalEntry(
                day = day,
                occurredAt = now.toInstant().toEpochMilli(),
                kind = "EAT",
                title = "Meal",
                calories = 100.0,
            )
    }
}
