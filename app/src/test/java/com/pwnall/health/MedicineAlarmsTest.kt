package com.pwnall.health

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class MedicineAlarmsTest {
    private val day = LocalDate.of(2026, 9, 7)
    private val zone = ZoneOffset.UTC

    private fun at(minute: Int): Long = day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun course(food: MedFood = MedFood.AFTER, id: Long = 1, minute: Int = 780) = Medication(
        id = id,
        name = "Medicine",
        dose = "1",
        schedule = MedSchedule.CLOCK.name,
        times = minute.toString(),
        foodRelation = food.name,
        startDay = day.minusDays(1).toString(),
        createdAt = 0,
    )

    private fun meal(minute: Int) = JournalEntry(
        day = day.toString(),
        occurredAt = at(minute),
        kind = "EAT",
        title = "Meal",
        calories = 100.0,
    )

    @Test
    fun `saving food makes an unmarked after-food dose immediately eligible`() {
        val courses = listOf(course())
        assertTrue(dueMedicine(courses, emptyList(), at(785), zone).isEmpty())
        assertEquals(MedReason.AFTER_FOOD, dueMedicine(courses, listOf(meal(785)), at(785), zone).single().nudge.reason)
    }

    @Test
    fun `food does not announce a future dose or an already marked one`() {
        val courses = listOf(course())
        assertTrue(dueMedicine(courses, listOf(meal(779)), at(779), zone).isEmpty())
        val dose = plannedDoses(courses, emptyList(), day).single()
        val journal = listOf(meal(785), medTakenEntry(dose, day, at(784)))
        assertTrue(dueMedicine(courses, journal, at(785), zone).isEmpty())
    }

    @Test
    fun `food changes a before-food instruction into an unmarked missed reminder`() {
        val courses = listOf(course(MedFood.BEFORE))
        assertEquals(MedReason.BEFORE_FOOD, dueMedicine(courses, emptyList(), at(780), zone).single().nudge.reason)
        for (mealMinute in listOf(770, 780, 785)) {
            val nudge = dueMedicine(courses, listOf(meal(mealMinute)), at(785), zone).single().nudge
            assertEquals(MedReason.MISSED_BEFORE_FOOD, nudge.reason)
        }
        assertEquals(
            MedReason.BEFORE_FOOD,
            dueMedicine(courses, listOf(meal(600)), at(785), zone).single().nudge.reason,
        )
    }

    @Test
    fun `future and historical food records do not trigger a dose`() {
        val courses = listOf(course())
        assertTrue(dueMedicine(courses, listOf(meal(790)), at(785), zone).isEmpty())
        val yesterday = meal(785).copy(day = day.minusDays(1).toString(), occurredAt = at(785) - 86_400_000)
        assertTrue(dueMedicine(courses, listOf(yesterday), at(785), zone).isEmpty())
    }

    @Test
    fun `all eligible medicines are returned and taken or inactive ones are excluded`() {
        val courses = listOf(course(id = 1), course(id = 2), course(id = 3).copy(endDay = day.minusDays(1).toString()))
        assertEquals(
            listOf(1L, 2L),
            dueMedicine(courses, listOf(meal(785)), at(785), zone).map {
                it.nudge.dose.medicationId
            },
        )
    }

    @Test
    fun `midnight preserves dose identity and uses the new meal`() {
        val courses = listOf(course(minute = 1430))
        val nextDay = day.plusDays(1)
        val food = meal(1445).copy(day = nextDay.toString())
        val due = dueMedicine(courses, listOf(food), at(1450), zone).single()
        assertEquals(day, due.day)
        assertEquals("dose:$day:1430:AFTER_FOOD", due.key)
        assertEquals(MedReason.AFTER_FOOD, due.nudge.reason)
    }

    /**
     * A reminder never names its medicine, so three courses at 13:00 would be three cards alike word
     * for word, three alarms at once, and three "I took it" buttons that each mark nobody knows which.
     * Doses that share the day, the slot and the sentence are one announcement.
     */
    @Test
    fun `doses due together for the same reason are one announcement, and its button marks them all`() {
        val together = listOf(course(MedFood.ANY, id = 1), course(MedFood.ANY, id = 2), course(MedFood.ANY, id = 3))
        val due = dueMedicine(together, emptyList(), at(780), zone)
        assertEquals(listOf(1L, 2L, 3L), due.map { it.nudge.dose.medicationId })
        assertEquals(setOf("dose:$day:780:DUE"), due.map { it.key }.toSet())
        // Another sentence or another time is another card: its words tell it apart.
        val mixed = listOf(course(MedFood.ANY, id = 1), course(MedFood.BEFORE, id = 2), course(MedFood.ANY, 3, 775))
        assertEquals(
            listOf("dose:$day:775:DUE", "dose:$day:780:DUE", "dose:$day:780:BEFORE_FOOD"),
            dueMedicine(mixed, emptyList(), at(780), zone).map { it.key },
        )
        // "Later" on the shared card puts every one of its doses off.
        val later = mapOf("dose:$day:780:DUE" to at(795))
        assertTrue(dueMedicine(together, emptyList(), at(785), zone, later).isEmpty())
        // The check posts once a key, says there are several, and the button marks what the key names.
        val alarms = source("MedicineAlarms.kt")
        val refresh = alarms.substringAfter("suspend fun refresh(").substringBefore("fun announcementFor(")
        assertTrue(refresh, "due.groupBy { it.key }.forEach { (key, doses) ->" in refresh)
        assertTrue(refresh, "val dose = doses.first()" in refresh)
        assertTrue(refresh, "post(context, dose, loud, several = doses.size > 1," in refresh)
        val taken = alarms.substringAfter("suspend fun markTaken(").substringBefore("suspend fun snooze(")
        assertTrue(taken, "dueNow(db).filter { it.key == key }" in taken)
    }

    @Test
    fun `a dose that joins a card already up is announced, for the card spoke of fewer`() {
        // One dose rang at 13:00 and its card stands; a course added at 13:05 for the same time
        // would be marked by that card's button without the card having said a word about it.
        val one = "DUE|${at(780)}|false|1"
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("DUE", one, at(785), true, doses = 1))
        assertEquals(DoseAnnouncement.LOUD, MedicineAlarms.announcementFor("DUE", one, at(785), true, doses = 2))
        // Said for two, it is said: one of them marked in the app since is no news, nor is it back.
        val two = "DUE|${at(785)}|false|2"
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("DUE", two, at(786), true, doses = 1))
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("DUE", two, at(786), true, doses = 2))
        // A record that names no number spoke for one, and the follow-ups read the longer record too.
        val bare = "DUE|${at(780)}|false"
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("DUE", bare, at(785), false))
        assertEquals(DoseAnnouncement.QUIET, MedicineAlarms.announcementFor("DUE", bare, at(785), false, doses = 2))
        assertEquals(listOf(at(785) + 3_600_000), MedicineAlarms.followUpChecks(two))
    }

    /**
     * "On waking" with no night on record is a reminder at eight, and says so in the course's form;
     * the estimate from the phone's use is a record only once the app has been opened. A record that
     * comes in later moves the dose to the waking it names and the grace with it: the four hours
     * are counted from when the person got up, not from when the stand-in rang.
     */
    @Test
    fun `a dose on waking falls back to eight, and a night recorded later moves it and its grace`() {
        val waking = listOf(course(MedFood.ANY).copy(schedule = MedSchedule.WAKE.name, times = ""))
        assertTrue(dueMedicine(waking, emptyList(), at(MED_DEFAULT_WAKE_MINUTE - 1), zone).isEmpty())
        val fallback = dueMedicine(waking, emptyList(), at(MED_DEFAULT_WAKE_MINUTE), zone).single()
        assertEquals("dose:$day:$MED_SLOT_WAKE:DUE", fallback.key)
        assertEquals(MED_DEFAULT_WAKE_MINUTE, fallback.nudge.dose.minuteOfDay)
        // Up at 06:30, the app opened at 10:45: until then the dose stood at 08:00 and was due.
        assertEquals(1, dueMedicine(waking, emptyList(), at(645), zone).size)
        val night = JournalEntry(
            day = day.toString(),
            occurredAt = at(390),
            kind = "MEASUREMENT",
            title = "sleep",
            calories = 0.0,
            sleepStartAt = at(390) - 8 * 3_600_000L,
            sleepEndAt = at(390),
        )
        val moved = dueMedicine(waking, listOf(night), at(390 + MED_GRACE_MINUTES), zone).single()
        assertEquals(390, moved.nudge.dose.minuteOfDay)
        assertTrue(dueMedicine(waking, listOf(night), at(645), zone).isEmpty())
    }

    /**
     * The reminder's button, the alarm page's, the widget's Taken and the Medicine tab, reading "not
     * marked yet" and then writing, would let two taps mark one dose twice: one door looks and writes in one
     * transaction, and what it looks with refuses the second mark of a course's slot on a day.
     */
    @Test
    fun `a dose is marked once whoever taps second, through one door`() {
        val dose = plannedDoses(listOf(course()), emptyList(), day).single()
        val first = medTakenEntry(dose, day, at(781)).copy(id = 7)
        val second = medTakenEntry(dose, day, at(781) + 40)
        assertEquals(first, markedDoseOf(second, listOf(meal(770), first)))
        assertNull(markedDoseOf(second, listOf(meal(770))))
        // Another course at the same minute, and the same course's other slot, are other doses.
        val other = plannedDoses(listOf(course(id = 2)), emptyList(), day).single()
        assertNull(markedDoseOf(medTakenEntry(other, day, at(782)), listOf(first)))
        assertNull(markedDoseOf(medTakenEntry(dose.copy(slot = "1200"), day, at(782)), listOf(first)))
        val door = source("MedicineAlarms.kt").substringAfter("fun StepsDb.markDosesTaken(")
        assertTrue(door, "withTransaction {" in door)
        assertTrue(door, "markedDoseOf(entry, dao().journalOnNow(entry.day)) == null" in door)
        listOf("MedicineAlarms.kt", "DoseWidgets.kt", "MedicineScreen.kt").forEach { file ->
            assertTrue(file, "markDosesTaken(" in source(file))
            assertFalse(file, "saveJournalEvent(medTakenEntry(" in source(file))
        }
    }

    @Test
    fun `next alarm respects the dose clock and the ninety-minute food fallback`() {
        val courses = listOf(course())
        assertEquals(at(780), nextMedicineCheck(courses, emptyList(), at(779), zone))
        assertEquals(at(870), nextMedicineCheck(courses, emptyList(), at(850), zone))
        assertNull(nextMedicineCheck(emptyList(), emptyList(), at(779), zone))
    }

    /**
     * A last day that cannot be read leaves the course running ([Medication.activeOn]), and its doses
     * planned. A check comparing the text of that day with yesterday's would find the course over and
     * book no alarm for any of them.
     */
    @Test
    fun `a course whose last day cannot be read is checked for as it is planned`() {
        val unread = course(MedFood.ANY).copy(endDay = "")
        assertEquals(1, plannedDoses(listOf(unread), emptyList(), day).size)
        assertEquals(at(780), nextMedicineCheck(listOf(unread), emptyList(), at(779), zone))
        // One over before yesterday has nothing left to be checked for.
        val over = course(MedFood.ANY).copy(
            startDay = day.minusDays(9).toString(),
            endDay = day.minusDays(2).toString(),
        )
        assertNull(nextMedicineCheck(listOf(over), emptyList(), at(779), zone))
    }

    /**
     * "Later" is kept only where the dose is still due when its quarter of an hour is up, and due is
     * counted on the wall clock. Measured from midnight in milliseconds, the day the clocks go on would
     * take a "Later" at 05:20 for a 01:30 dose let go at 05:30, and the day they go back would refuse
     * one at 04:20 for the same dose, an hour early.
     */
    @Test
    fun `Later is kept while the dose stays due on the days the clocks change`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val cases = listOf(Triple(LocalDate.of(2026, 3, 29), 5, false), Triple(LocalDate.of(2026, 10, 25), 4, true))
        for ((changeDay, hour, kept) in cases) {
            val now = changeDay.atTime(hour, 20).atZone(berlin).toInstant().toEpochMilli()
            val courses = listOf(course(MedFood.ANY, minute = 90).copy(startDay = changeDay.toString()))
            val dose = dueMedicine(courses, emptyList(), now, berlin).single()
            val back = dueMedicine(courses, emptyList(), now + MedicineAlarms.SNOOZE_MS, berlin)
            assertEquals("$changeDay", kept, back.isNotEmpty())
            assertEquals("$changeDay", kept, MedicineAlarms.snoozable(dose, now, berlin))
        }
    }

    /**
     * The next check is the soonest thing owed: a dose put off by hand comes back at its own moment,
     * the day's end is a check of its own, a course not begun books nothing, and a course's last
     * evening is still followed after midnight and nothing after that.
     */
    @Test
    fun `the next check is the soonest of a dose put off, midnight and a course's last evening`() {
        val courses = listOf(course())
        assertEquals(at(840), nextMedicineCheck(courses, emptyList(), at(790), zone))
        val later = mapOf("dose:$day:780:AFTER_FOOD" to at(800))
        assertEquals(at(800), nextMedicineCheck(courses, emptyList(), at(790), zone, later))
        // A dose on waking has no time for tomorrow until its night is known: the check is midnight.
        val waking = listOf(course(MedFood.ANY).copy(schedule = MedSchedule.WAKE.name, times = ""))
        assertEquals(at(1440), nextMedicineCheck(waking, emptyList(), at(1000), zone))
        val notYet = listOf(course(MedFood.ANY).copy(startDay = day.plusDays(1).toString()))
        assertEquals(at(1440), nextMedicineCheck(notYet, emptyList(), at(779), zone))
        // Ended yesterday with a dose at 23:30: its 00:30 rung stands, and the night after, nothing.
        val ending = listOf(
            course(MedFood.ANY, minute = 1410).copy(
                startDay = day.minusDays(9).toString(),
                endDay = day.minusDays(1).toString(),
            ),
        )
        assertEquals(at(30), nextMedicineCheck(ending, emptyList(), at(10), zone))
        assertNull(nextMedicineCheck(ending, emptyList(), at(1450), zone))
    }

    /**
     * 02:30 does not happen on the night the clocks go on: the dose is due when the clock passes it
     * at 03:00, as [dueMedicine] counts, and its first check is booked then, not for 03:30.
     * And the day a dose belongs to is the zone's: the same moment is tomorrow's dose in Tokyo.
     */
    @Test
    fun `a dose at a time the clocks skip is checked for when they pass it, and days are the zone's`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val spring = LocalDate.of(2026, 3, 29)
        val skipped = listOf(course(MedFood.ANY, minute = 150).copy(startDay = spring.toString()))
        val jump = spring.atTime(3, 0).atZone(berlin).toInstant().toEpochMilli()
        assertTrue(dueMedicine(skipped, emptyList(), jump - 1, berlin).isEmpty())
        assertEquals(1, dueMedicine(skipped, emptyList(), jump, berlin).size)
        assertEquals(jump, nextMedicineCheck(skipped, emptyList(), jump - 60_000, berlin))

        val eight = listOf(course(MedFood.ANY, minute = 480))
        assertTrue(dueMedicine(eight, emptyList(), at(1410), zone).isEmpty())
        val tokyo = dueMedicine(eight, emptyList(), at(1410), ZoneId.of("Asia/Tokyo"))
        assertEquals(listOf("dose:${day.plusDays(1)}:480:DUE"), tokyo.map { it.key })
    }

    /**
     * Whatever cannot be held against a dose leaves it said at full volume: a new sentence for it, a
     * clock set back to before the last word, a record that does not read. Inside the ring's own
     * minute nothing more is said.
     */
    @Test
    fun `a new reason, a clock set back and a record that does not read all speak aloud`() {
        val ring = "DUE|${at(780)}|true|1"
        assertEquals(DoseAnnouncement.LOUD, MedicineAlarms.announcementFor("AFTER_FOOD", ring, at(781), true))
        assertEquals(DoseAnnouncement.QUIET, MedicineAlarms.announcementFor("AFTER_FOOD", ring, at(781), false))
        assertEquals(DoseAnnouncement.LOUD, MedicineAlarms.announcementFor("DUE", ring, at(770), true))
        for (unread in listOf("", "garbage", "DUE|later|true")) {
            assertEquals(unread, DoseAnnouncement.LOUD, MedicineAlarms.announcementFor("DUE", unread, at(781), true))
        }
        val stillRinging = at(780) + MedicineAlarmNotification.RING_MS
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("DUE", ring, stillRinging, true))
    }

    /**
     * The receiver acts on the system's broadcasts only where the manifest lets them in, and the two
     * lists live apart: an action added to one alone would leave the doses unbooked after a reboot,
     * an update or a clock change, without a word.
     */
    @Test
    fun `the manifest lets in every system broadcast the dose receiver acts on and no other`() {
        val receiver = File("src/main/AndroidManifest.xml").readText()
            .substringAfter("android:name=\".MedicineAlarmReceiver\"")
            .substringBefore("</receiver>")
        val declared = Regex("""<action android:name="([^"]+)"""").findAll(receiver).map { it.groupValues[1] }.toSet()
        val own = setOf(MedicineAlarms.ACTION_CHECK, MedicineAlarms.ACTION_RETRY)
        assertEquals(MedicineAlarmReceiver.ACTIONS - own, declared)
    }

    @Test
    fun `new food reason bypasses throttle but saving again does not ring twice`() {
        assertFalse(MedicineAlarms.shouldAnnounceDose("AFTER_FOOD", "AFTER_FOOD", at(786), at(785)))
        assertTrue(MedicineAlarms.shouldAnnounceDose("MISSED_BEFORE_FOOD", "BEFORE_FOOD", at(786), at(785)))
        assertTrue(MedicineAlarms.shouldAnnounceDose("AFTER_FOOD", "AFTER_FOOD", at(845), at(785)))
    }

    @Test
    fun `a dose put off by hand is announced again when its quarter of an hour is up`() {
        // A card at 13:00 and "Later" at 13:05: 13:20 is well inside the hour the repeat waits out.
        val until = at(785) + MedicineAlarms.SNOOZE_MS
        val card = "DUE|${at(780)}|false"
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("DUE", card, until, false))
        assertEquals(DoseAnnouncement.QUIET, MedicineAlarms.announcementFor("DUE", card, until, false, until))
        // A ring put off comes back as a ring, not as the card that stands in for one already heard.
        val ring = "DUE|${at(780)}|true"
        assertEquals(DoseAnnouncement.LOUD, MedicineAlarms.announcementFor("DUE", ring, until, true, until))
        // Said again, it is throttled like any other: a snooze that is over does not repeat it.
        val back = "DUE|$until|false"
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("DUE", back, until + 1, false, until))
    }

    @Test
    fun `what was said is followed up from when it was said and not from the dose time`() {
        val courses = listOf(course())
        // A meal logged at 13:17 rings then, and nothing on the ladder from 13:00 comes before 14:00.
        val rang = at(797) + 800
        val ring = "AFTER_FOOD|$rang|true"
        assertEquals(at(840), nextMedicineCheck(courses, emptyList(), rang, zone))
        val (card, repeat) = MedicineAlarms.followUpChecks(ring)
        assertEquals(card, nextMedicineCheck(courses, emptyList(), rang, zone, followUps = listOf(card, repeat)))
        // The card waits for the system to take the ring down, and goes up at the check made for it.
        assertTrue(card - rang > MedicineAlarmNotification.RING_MS)
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("AFTER_FOOD", ring, card - 1, true))
        assertEquals(DoseAnnouncement.QUIET, MedicineAlarms.announcementFor("AFTER_FOOD", ring, card, true))
        // The card is then the last thing said and the ring returns an hour after it: the ladder's
        // 14:00 is too soon, and none of its rungs falls on the moment itself.
        val shown = "AFTER_FOOD|$card|false"
        val again = MedicineAlarms.followUpChecks(shown).single()
        assertEquals(card + 3_600_000, again)
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("AFTER_FOOD", shown, at(840), true))
        assertEquals(again, nextMedicineCheck(courses, emptyList(), at(840), zone, followUps = listOf(again)))
        assertEquals(DoseAnnouncement.LOUD, MedicineAlarms.announcementFor("AFTER_FOOD", shown, again, true))
        assertTrue(MedicineAlarms.followUpChecks(null).isEmpty())
    }

    @Test
    fun `a check one minute after the dose time no longer decides whether the card goes up`() {
        // The 13:00 alarm has a process to start and writes the ring down 800 ms late; a warm check
        // a minute after the dose's time would find the wait 750 ms short and lose the card.
        val rang = at(780) + 800
        val ring = "AFTER_FOOD|$rang|true"
        val card = MedicineAlarms.followUpChecks(ring).first()
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("AFTER_FOOD", ring, at(781) + 50, true))
        assertEquals(card, nextMedicineCheck(listOf(course()), emptyList(), rang, zone, followUps = listOf(card)))
        assertEquals(DoseAnnouncement.QUIET, MedicineAlarms.announcementFor("AFTER_FOOD", ring, card, true))
    }

    @Test
    fun `a muted alarm channel leaves the dose its card and not silence`() {
        assertTrue(doseRings(switchOn = true, channelImportance = null))
        assertTrue(doseRings(switchOn = true, channelImportance = NotificationManager.IMPORTANCE_HIGH))
        assertFalse(doseRings(switchOn = false, channelImportance = NotificationManager.IMPORTANCE_HIGH))
        val muted = doseRings(switchOn = true, channelImportance = NotificationManager.IMPORTANCE_NONE)
        assertFalse(muted)
        // Not ringing, the dose is announced as the card and written down as one: no ring is asked
        // of a channel that refuses it, and no second card follows a ring that never was.
        assertEquals(DoseAnnouncement.QUIET, MedicineAlarms.announcementFor("DUE", null, at(780), muted))
        val card = "DUE|${at(780)}|false"
        assertEquals(DoseAnnouncement.NONE, MedicineAlarms.announcementFor("DUE", card, at(782), muted))
    }

    /** The text of [file] among the app's sources: what only a device runs is pinned where it is written. */
    private fun source(file: String): String = File("src/main/java/com/pwnall/health/$file").readText()

    /**
     * The silent card that follows a ring goes up under the ring's own tag, so an alarm page cancelling
     * by that tag when its minute runs out — late, whenever the phone slept through part of the wait —
     * would take the card with it. Only the person's own Stop, Open or Back takes the notification down.
     */
    @Test
    fun `the alarm page running out of its minute closes without taking the notification down`() {
        val expiry = source("MedicineAlarmActivity.kt")
            .substringAfter("lifecycleScope.launch {")
            .substringBefore("setContent {")
        assertTrue(expiry, "finish()" in expiry)
        assertFalse(expiry, "dismiss()" in expiry)
    }

    /**
     * A receiver's coroutine has nobody above it to hand an exception to: thrown, it ends the process,
     * and the step service with it. And the alarm's own check, failed or cut short, has booked no next
     * alarm — it asks for itself once more rather than leave the doses to the hourly worker, and the
     * check made again asks for no third.
     */
    @Test
    fun `nothing a dose's receiver throws ends the process, and a failed check is asked for once more`() {
        val alarms = source("MedicineAlarms.kt")
        val launches = (alarms + source("MedicineAlarmNotification.kt"))
            .split("goAsync()")
            .drop(1)
            .map { it.substringBefore("pending.finish()") }
        assertEquals(3, launches.size)
        launches.forEach { assertTrue(it, "runCatchingCancellable { withTimeout(" in it) }
        // Caught, and still said: the log is where a check that keeps failing can be seen to.
        launches.forEach { assertTrue(it, ".onFailure { Log.w(\"MedicineAlarms\", " in it) }
        assertTrue(launches.first(), "val first = intent.action != MedicineAlarms.ACTION_RETRY" in launches.first())
        val retry = ".onFailure { if (first) runCatching { MedicineAlarms.retrySoon(app) } }"
        assertTrue(launches.first(), retry in launches.first())
        // Its own alarm: booked over the check's, a retry would push back a dose's exact alarm.
        assertTrue("pending(context, ACTION_RETRY)" in alarms.substringAfter("fun retrySoon("))
        assertTrue("MedicineAlarms.ACTION_RETRY," in alarms.substringAfter("val ACTIONS = setOf("))
    }

    /**
     * A course added from the form asking for exact alarms and the lock-screen alarm alone would, with
     * reminders switched off or notifications never allowed, be saved to silence. The chat asks about
     * all four; the form asks the chat's own question about the switch, and Android's prompt for
     * notifications before the alarm's two.
     */
    @Test
    fun `a course added from the form asks for reminders and notifications as the chat does`() {
        val screen = source("MedicineScreen.kt")
        assertTrue("if (profile.remindersEnabled) askAlarmAccess() else askingReminders = true" in screen)
        val asked = screen.substringAfter("if (askingReminders) {").substringBefore("\n    }\n")
        assertTrue(asked, "R.string.med_chat_ask_remind_switch" in asked)
        assertTrue(asked, "turnRemindersOn()" in asked)
        val turnedOn = screen.substringAfter("fun turnRemindersOn() = ").substringBefore("\n    }\n")
        assertTrue(turnedOn, "Reminders.apply(context, true)" in turnedOn)
        val access = source("MedicineAlarmSettings.kt").substringAfter("fun rememberAlarmAccessRequest(")
        assertTrue(access, "notifications.launch(permission)" in access)
    }

    /**
     * The form asks only as a course is added. A course from before the switch went off, a prompt
     * Android no longer shows, the dose channel muted from the shade: the tab would list doses nothing
     * announces. So it says which of the two stands in the way, the app's switch first — off,
     * it calls every dose off whatever the phone allows — and only where a course is running.
     */
    @Test
    fun `the medicine tab says what keeps a running course's doses from being announced`() {
        assertNull(reminderBlock(courseRunning = true, remindersOn = true, notificationsOn = true))
        assertEquals(ReminderBlock.REMINDERS_OFF, reminderBlock(true, remindersOn = false, notificationsOn = true))
        assertEquals(ReminderBlock.REMINDERS_OFF, reminderBlock(true, remindersOn = false, notificationsOn = false))
        assertEquals(ReminderBlock.NOTIFICATIONS_OFF, reminderBlock(true, remindersOn = true, notificationsOn = false))
        assertNull(reminderBlock(courseRunning = false, remindersOn = false, notificationsOn = false))
        // What the phone allows is what the check itself is refused by: a ring needs only its own
        // channel, and without one the dose is the card, which a muted channel makes nothing of.
        val none = NotificationManager.IMPORTANCE_NONE
        assertTrue(doseShows(permitted = true, rings = false, cardImportance = null))
        assertTrue(doseShows(permitted = true, rings = true, cardImportance = none))
        assertFalse(doseShows(permitted = true, rings = false, cardImportance = none))
        assertFalse(doseShows(permitted = false, rings = true, cardImportance = NotificationManager.IMPORTANCE_HIGH))
        val screen = source("MedicineScreen.kt")
        assertTrue("notificationsOn = MedicineAlarmNotification.shows(context)" in screen)
        assertTrue("reminderBlock(running.isNotEmpty(), profile.remindersEnabled, notificationsOn)" in screen)
        assertTrue("ReminderBlock.NOTIFICATIONS_OFF -> openNotificationSettings()" in screen)
    }

    /**
     * Muted in Android's settings, the alarm's channel leaves the doses their card ([doseRings]), and
     * the app's switch does not go on saying "on": the settings say it, beside the way to the channel's page.
     */
    @Test
    fun `the alarm's switch says when its channel is muted and opens the page that gives the ring back`() {
        val settings = source("MedicineAlarmSettings.kt").substringBefore("internal enum class ReminderBlock")
        val muted = settings.substringAfter("if (alarm && !rings) {").substringBefore("if (!exact) {")
        assertTrue(muted, "R.string.med_alarm_channel_muted" in muted)
        assertTrue(muted, "request.launch(MedicineAlarmNotification.channelSettings(context))" in muted)
        assertTrue("rings = MedicineAlarmNotification.rings(context)" in settings.substringAfter("val request = "))
        val page = source("MedicineAlarmNotification.kt")
            .substringAfter("fun channelSettings(")
            .substringBefore("fun setAlarmEnabled(")
        assertTrue(page, ".putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)" in page)
    }

    @Test
    fun `food and water reminders observe quiet hours and their own records and throttle`() {
        assertFalse(routineReminderDue(LocalTime.of(8, 0), null, null, 120))
        assertFalse(routineReminderDue(LocalTime.of(22, 0), null, null, 120))
        assertFalse(routineReminderDue(LocalTime.of(10, 59), null, null, 120))
        assertTrue(routineReminderDue(LocalTime.of(11, 0), null, null, 120))
        assertFalse(routineReminderDue(LocalTime.of(13, 0), LocalTime.of(12, 0), null, 240))
        assertFalse(routineReminderDue(LocalTime.of(13, 0), null, LocalTime.of(12, 0), 120))
        assertTrue(routineReminderDue(LocalTime.of(13, 0), null, null, 240))
    }
}
