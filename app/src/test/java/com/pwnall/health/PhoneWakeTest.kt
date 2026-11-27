package com.pwnall.health

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The morning check-in's waking read off the phone: the night is the gap the sleep estimate finds,
 * and the waking is the first touch or unlock after it. Then where the check-in falls back to.
 */
class PhoneWakeTest {
    /** The ordinary night: the last touch at half past eleven, the unlock at a quarter to seven. */
    @Test
    fun theWakingIsTheFirstUnlockAfterTheNight() {
        val events = listOf(touch(at(EVENING, 22, 10)), touch(at(EVENING, 23, 30)), unlock(at(MORNING, 6, 45)))

        assertEquals(at(MORNING, 6, 45), phoneWakeAt(events, window(), LATER))
    }

    /**
     * A notification lit the screen at ten past seven and nobody touched it until two minutes later.
     * The night ends at the lit screen, as the sleep estimate says; the waking is the hand after it.
     */
    @Test
    fun aScreenNoHandLitIsNotTheWaking() {
        val events = listOf(
            touch(at(EVENING, 23, 0)),
            UsageEventRecord(UsageEvents.Event.SCREEN_INTERACTIVE, at(MORNING, 7, 10)),
            unlock(at(MORNING, 7, 12)),
        )

        assertEquals(at(MORNING, 7, 12), phoneWakeAt(events, window(), LATER))
    }

    /**
     * With no hand after the gap before noon there is no phone waking: a screen nobody touched ended
     * the night, and printing it as the phone's first use would say something no hand did.
     */
    @Test
    fun withNoHandAfterTheGapThereIsNoPhoneWaking() {
        val events = listOf(
            touch(at(EVENING, 23, 0)),
            UsageEventRecord(UsageEvents.Event.ACTIVITY_RESUMED, at(MORNING, 6, 30)),
        )

        assertNull(phoneWakeAt(events, window(), LATER))
    }

    /**
     * The unlock that opens the app this morning comes a few seconds before the moment of looking,
     * and is the opening, not a waking; an unlock well before it, then the app opened later in the
     * morning, is the waking.
     */
    @Test
    fun theUnlockThatOpensTheAppIsNoPhoneWaking() {
        val now = at(MORNING, 9, 30)
        val window = nightWindow(MORNING, ZONE, now)!!
        val opening = now - 20_000
        fun events(unlockAt: Long) = listOf(
            touch(at(EVENING, 23, 30)),
            UsageEventRecord(UsageEvents.Event.SCREEN_INTERACTIVE, unlockAt - 1_000),
            unlock(unlockAt),
            touch(now - 5_000),
        )

        assertNull(phoneWakeAt(events(opening), window, now))
        assertEquals(at(MORNING, 9, 20), phoneWakeAt(events(at(MORNING, 9, 20)), window, now))
    }

    /** A restart in the night lights the lock screen by itself; it neither ends the night nor wakes. */
    @Test
    fun aRestartInTheNightIsNotAWaking() {
        val events = listOf(
            touch(at(EVENING, 23, 30)),
            UsageEventRecord(UsageEvents.Event.DEVICE_STARTUP, at(MORNING, 3, 20)),
            UsageEventRecord(UsageEvents.Event.SCREEN_INTERACTIVE, at(MORNING, 3, 21)),
            unlock(at(MORNING, 7, 0)),
        )

        assertEquals(at(MORNING, 7, 0), phoneWakeAt(events, window(), LATER))
    }

    /** A phone used all night has no gap of a night's length, and offers no waking at all. */
    @Test
    fun aNightWithoutAGapOffersNothing() {
        val events = (0..14).map { touch(at(EVENING, 20, 0) + it * HOUR) }

        assertNull(phoneWakeAt(events, window(), LATER))
    }

    /**
     * This morning, still under way: nothing since the night but opening the app now, which the
     * platform has not reported yet. It closes the night, but it is the moment of answering, not a
     * waking the phone saw: none is offered, and the check-in says "the time now" itself.
     */
    @Test
    fun openingTheAppThisMorningIsNoPhoneWaking() {
        val now = at(MORNING, 8, 5)
        val window = nightWindow(MORNING, ZONE, now)!!

        assertNull(phoneWakeAt(listOf(touch(at(EVENING, 23, 0))), window, now))
        assertEquals(
            at(MORNING, 7, 40),
            phoneWakeAt(listOf(touch(at(EVENING, 23, 0)), unlock(at(MORNING, 7, 40))), window, now),
        )
    }

    /**
     * A waking read off the phone keeps that mark in its row, so it is never read back as a sleep
     * record's end; a morning reopened offers it as the phone's again, and the calendar's editor
     * keeps it.
     */
    @Test
    fun aPhoneWakingIsStoredAsOne() {
        val check = MorningCheck(MorningErection.FIRM, at(MORNING, 6, 50), WakeSource.SLEEP, fromPhone = true)
        val payload = morningCheckPayload(check)

        assertEquals(check, morningCheckOf(payload))
        assertFalse(morningCheckOf(morningCheckPayload(check.copy(fromPhone = false)))!!.fromPhone)
        assertTrue(morningWake(MORNING, ZonedDateTime.of(MORNING.atTime(9, 0), ZONE), null, check, null)!!.fromPhone)

        val row = morningCheckEntry("Morning", MORNING, check)
        val rebuilt = rebuiltMorningCheck("Morning", row, MorningErection.NONE, MORNING, at(MORNING, 9, 0))!!
        assertTrue(morningCheckOf(rebuilt.measurementPayloadJson)!!.fromPhone)
    }

    /** Before four in the morning a night has not ended yet, and there is no window to look in. */
    @Test
    fun beforeFourThereIsNoWindow() {
        assertNull(nightWindow(MORNING, ZONE, at(MORNING, 3, 30)))
    }

    /** The order the check-in offers a waking in: set by hand, already stored, phone, record, now. */
    @Test
    fun thePhoneComesAfterWhatWasSetAndBeforeTheRecord() {
        val now = ZonedDateTime.of(MORNING.atTime(9, 0), ZONE)
        val phone = at(MORNING, 6, 50)
        val recorded = at(MORNING, 6, 30)
        val stored = MorningCheck(MorningErection.FIRM, at(MORNING, 7, 5), WakeSource.TYPED)

        assertEquals(
            MorningWake(at(MORNING, 7, 0), WakeSource.TYPED),
            morningWake(MORNING, now, 7 * 60, stored, recorded, phone),
        )
        assertEquals(
            MorningWake(stored.wokeAt, WakeSource.TYPED),
            morningWake(MORNING, now, null, stored, recorded, phone),
        )
        assertEquals(
            MorningWake(phone, WakeSource.SLEEP, fromPhone = true),
            morningWake(MORNING, now, null, null, recorded, phone),
        )
        assertEquals(MorningWake(recorded, WakeSource.SLEEP), morningWake(MORNING, now, null, null, recorded, null))
        assertTrue(morningWake(MORNING, now, null, null, null, null)!!.isNow)
    }

    /** A past morning with nothing from the phone or a record still asks: null, never a made-up time. */
    @Test
    fun aPastMorningWithNothingKnownAsks() {
        val now = ZonedDateTime.of(MORNING.plusDays(2).atTime(9, 0), ZONE)

        assertNull(morningWake(MORNING, now, null, null, null, null))
    }

    private fun window(): NightWindow = nightWindow(MORNING, ZONE, LATER)!!

    private fun touch(at: Long) = UsageEventRecord(UsageEvents.Event.USER_INTERACTION, at)

    private fun unlock(at: Long) = UsageEventRecord(UsageEvents.Event.KEYGUARD_HIDDEN, at)

    private fun at(day: LocalDate, hour: Int, minute: Int): Long =
        day.atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli()

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        val MORNING: LocalDate = LocalDate.of(2026, 9, 14)
        val EVENING: LocalDate = MORNING.minusDays(1)
        const val HOUR = 60L * 60 * 1_000

        /** Long after the morning: the whole window, to noon, has been seen. */
        val LATER: Long = MORNING.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
    }
}
