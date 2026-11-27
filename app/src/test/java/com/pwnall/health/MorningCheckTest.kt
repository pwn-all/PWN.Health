package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The male morning check-in: what its row holds, how a second answer lands, and where the waking is
 * taken from. The screen is Compose and not under test; everything it decides is here.
 */
class MorningCheckTest {
    /** One order, the optional keys only when set: the payload is part of a backup row's identity. */
    @Test
    fun thePayloadIsSpeltOneWay() {
        assertEquals(
            """{"schema":1,"morning":"FIRM","wokeAt":$WOKE_AT,"wake":"SLEEP"}""",
            morningCheckPayload(MorningCheck(MorningErection.FIRM, WOKE_AT, WakeSource.SLEEP)),
        )
        assertEquals(
            """{"schema":1,"morning":"DIDNT_NOTICE","wokeAt":$WOKE_AT,"wake":"TYPED",""" +
                """"answeredAt":$ANSWERED_AT,"wokeBy":"ALARM","later":true}""",
            morningCheckPayload(
                MorningCheck(
                    MorningErection.DIDNT_NOTICE,
                    WOKE_AT,
                    WakeSource.TYPED,
                    WokeBy.ALARM,
                    later = true,
                    answeredAt = ANSWERED_AT,
                ),
            ),
        )
    }

    /** Every answer, source and waker comes back as it went in. */
    @Test
    fun everyCheckRoundTrips() {
        val checks = MorningErection.entries.map { MorningCheck(it, WOKE_AT, WakeSource.SLEEP) }
            .flatMap { check -> WakeSource.entries.map { check.copy(wake = it) } }
            .flatMap { check -> (WokeBy.entries + null).map { check.copy(wokeBy = it) } }
            .flatMap { listOf(it, it.copy(later = true)) }
            .flatMap { listOf(it, it.copy(answeredAt = ANSWERED_AT)) }

        assertEquals(4 * 2 * 4 * 2 * 2, checks.toSet().size)
        checks.forEach { assertEquals(it, morningCheckOf(morningCheckPayload(it))) }
    }

    /**
     * A key this build does not know is not asked for; a waker or a source it does not know reads as
     * none and as set by hand; an answer it does not know leaves the whole morning unread.
     */
    @Test
    fun aNewerPayloadIsReadAsFarAsItCanBe() {
        val newer = """{"schema":2,"morning":"PARTLY","mood":"x","wokeAt":$WOKE_AT,"wake":"WATCH","wokeBy":"DOG"}"""
        assertEquals(MorningCheck(MorningErection.PARTLY, WOKE_AT, WakeSource.TYPED), morningCheckOf(newer))
        assertNull(morningCheckOf("""{"schema":2,"morning":"HALF","wokeAt":$WOKE_AT,"wake":"SLEEP"}"""))
        assertNull(morningCheckOf("""{"schema":1,"morning":"FIRM","wake":"SLEEP"}"""))
        assertNull(morningCheckOf(null))
    }

    /** "Didn't notice" is an unknown morning, never one with none. */
    @Test
    fun didntNoticeIsNeverNone() {
        assertEquals(listOf(2, 1, 0, null), MorningErection.entries.map(MorningErection::score))
        assertNull(MorningErection.DIDNT_NOTICE.score)
    }

    /**
     * The row: the day it woke on, stamped at the waking, no calories, and nothing in the columns the
     * Health Connect export, the sleep summary or the dose timetable pick rows by.
     */
    @Test
    fun theRowHoldsNoSleepPulseOrWeight() {
        val row = morningCheckEntry("Morning", DAY, MorningCheck(MorningErection.FIRM, WOKE_AT, WakeSource.SLEEP))

        assertEquals(KIND_MORNING_CHECK, row.kind)
        assertEquals(DAY.toString(), row.day)
        assertEquals(WOKE_AT, row.occurredAt)
        assertEquals(0.0, row.calories, 0.0)
        assertNull(row.sleepStartAt)
        assertNull(row.sleepEndAt)
        assertNull(row.measurementHeartRateBpm)
        assertNull(row.measurementWeightKg)
        assertNull(row.measurementHeightCm)
        assertNull(row.durationMinutes)
        val payload = row.measurementPayloadJson!!
        listOf("systolicMmhg", "spo2Percent", "temperatureCelsius", "glucose").forEach {
            assertFalse(it, it in payload)
        }
        // Nor does the dose timetable read a waking off it.
        assertNull(recordedWakeAt(listOf(row), DAY))
    }

    /** A second answer takes the first one's id, so the day keeps one row; a surplus one is deleted. */
    @Test
    fun aSecondAnswerReplacesTheFirst() {
        val first = check(MorningErection.FIRM).copy(id = 5)
        val water = JournalEntry(
            id = 6,
            day = DAY.toString(),
            occurredAt = WOKE_AT,
            kind = KIND_WATER,
            title = "",
            calories = 0.0,
        )
        val yesterday = check(MorningErection.NONE).copy(id = 3, day = DAY.minusDays(1).toString())

        val (written, deleted) = morningCheckReplacement(listOf(first, water, yesterday), check(MorningErection.NONE))
        assertEquals(5L, written.id)
        assertEquals(MorningErection.NONE, morningCheckOf(written.measurementPayloadJson)!!.answer)
        assertEquals(emptyList<Long>(), deleted)

        // A restored backup left two: the newest is rewritten and the other goes.
        val restored = check(MorningErection.PARTLY).copy(id = 9)
        val (again, surplus) = morningCheckReplacement(listOf(first, restored, water), check(MorningErection.FIRM))
        assertEquals(9L, again.id)
        assertEquals(listOf(5L), surplus)

        // The first answer of a day is a new row.
        assertEquals(0L, morningCheckReplacement(listOf(water), check(MorningErection.FIRM)).first.id)
    }

    /** Where a day holds two, the one written last is the one that stands. */
    @Test
    fun theNewestRowOfADayIsItsAnswer() {
        val old = check(MorningErection.FIRM).copy(id = 5)
        val newer = check(MorningErection.NONE).copy(id = 9)
        val other = check(MorningErection.PARTLY).copy(id = 2, day = DAY.minusDays(1).toString())

        val byDay = morningChecksByDay(listOf(newer, old, other))
        assertEquals(MorningErection.NONE, byDay.getValue(DAY.toString()).answer)
        assertEquals(MorningErection.PARTLY, byDay.getValue(DAY.minusDays(1).toString()).answer)
        assertEquals(2, byDay.size)
    }

    /**
     * A backup restored over a changed answer inserts the old one under a fresh, higher id; the
     * moment each was answered, not the id, says which one stands and which row the next answer
     * rewrites.
     */
    @Test
    fun aRestoredOldAnswerDoesNotOutrankTheCorrection() {
        val corrected = check(MorningErection.NONE, answeredAt = ANSWERED_AT + 60_000).copy(id = 5)
        val restored = check(MorningErection.FIRM, answeredAt = ANSWERED_AT).copy(id = 250)

        assertEquals(
            MorningErection.NONE,
            morningChecksByDay(listOf(restored, corrected)).getValue(DAY.toString()).answer,
        )
        val (written, deleted) = morningCheckReplacement(listOf(restored, corrected), check(MorningErection.PARTLY))
        assertEquals(5L, written.id)
        assertEquals(listOf(250L), deleted)
        // A row that does not say when it was answered loses to one that does.
        val unstamped = check(MorningErection.FIRM).copy(id = 300)
        assertEquals(
            MorningErection.NONE,
            morningChecksByDay(listOf(unstamped, corrected)).getValue(DAY.toString()).answer,
        )
    }

    /**
     * A morning written by a newer build is left alone: it neither hides a morning this build can read
     * nor is rewritten or deleted when the day is answered here.
     */
    @Test
    fun aMorningFromANewerBuildIsLeftAlone() {
        val newer = check(MorningErection.FIRM).let {
            it.copy(id = 9, measurementPayloadJson = it.measurementPayloadJson!!.replace("FIRM", "HALF"))
        }
        assertNull(morningCheckOf(newer.measurementPayloadJson))

        assertEquals(emptyMap<String, MorningCheck>(), morningChecksByDay(listOf(newer)))
        val (alone, untouched) = morningCheckReplacement(listOf(newer), check(MorningErection.NONE))
        assertEquals(0L, alone.id)
        assertEquals(emptyList<Long>(), untouched)

        val readable = check(MorningErection.PARTLY).copy(id = 5)
        assertEquals(
            MorningErection.PARTLY,
            morningChecksByDay(listOf(newer, readable)).getValue(DAY.toString()).answer,
        )
        val (written, deleted) = morningCheckReplacement(listOf(newer, readable), check(MorningErection.NONE))
        assertEquals(5L, written.id)
        assertEquals(emptyList<Long>(), deleted)
    }

    /** The recorded waking is the earliest end on the day; with no night recorded there is none, never a default. */
    @Test
    fun theRecordedWakingIsTheEarliestEndOrNothing() {
        val night = sleep(DAY, endHour = 7)
        val nap = sleep(DAY, endHour = 15)
        val before = sleep(DAY.minusDays(1), endHour = 6)

        assertEquals(night.sleepEndAt, recordedWakeAt(listOf(nap, night, before), DAY))
        assertNull(recordedWakeAt(listOf(before), DAY))
        assertNull(recordedWakeAt(emptyList(), DAY))
        // The dose timetable still has its default where the check-in has none.
        assertEquals(MED_DEFAULT_WAKE_MINUTE, wakeMinuteOf(listOf(before), DAY, ZONE))
        assertEquals(7 * 60, wakeMinuteOf(listOf(nap, night), DAY, ZONE))
    }

    /** A time set wins, then the morning's own, then the sleep record, then now — today only. */
    @Test
    fun theWakingIsTakenInOrder() {
        val now = DAY.atTime(9, 30).atZone(ZONE)
        val recorded = at(DAY, 6, 50)
        val stored = MorningCheck(MorningErection.FIRM, at(DAY, 7, 5), WakeSource.TYPED)

        assertEquals(MorningWake(at(DAY, 7, 0), WakeSource.TYPED), morningWake(DAY, now, 7 * 60, stored, recorded))
        assertEquals(MorningWake(at(DAY, 7, 5), WakeSource.TYPED), morningWake(DAY, now, null, stored, recorded))
        assertEquals(MorningWake(recorded, WakeSource.SLEEP), morningWake(DAY, now, null, null, recorded))
        assertEquals(
            MorningWake(now.toInstant().toEpochMilli(), WakeSource.TYPED, isNow = true),
            morningWake(DAY, now, null, null, null),
        )
    }

    /** A past morning with nothing recorded offers no time at all; one set for it is taken as set. */
    @Test
    fun aPastMorningWithNoRecordAsks() {
        val now = DAY.atTime(9, 30).atZone(ZONE)
        val yesterday = DAY.minusDays(1)

        assertNull(morningWake(yesterday, now, null, null, null))
        assertEquals(
            MorningWake(at(yesterday, 11, 0), WakeSource.TYPED),
            morningWake(yesterday, now, 11 * 60, null, null),
        )
    }

    /** A time set for today is never later than now. */
    @Test
    fun aWakingStillToComeIsNow() {
        val now = DAY.atTime(9, 30).atZone(ZONE)
        assertEquals(
            MorningWake(now.toInstant().toEpochMilli(), WakeSource.TYPED),
            morningWake(DAY, now, 11 * 60, null, null),
        )
    }

    /** An answer given after its day is marked; the same answer saved again keeps the mark it had. */
    @Test
    fun anAnswerAfterItsDayIsMarkedLater() {
        val nextDay = DAY.plusDays(1)
        val sameDay = MorningCheck(MorningErection.FIRM, WOKE_AT, WakeSource.SLEEP)

        assertFalse(answeredLater(null, MorningErection.FIRM, DAY, DAY))
        assertTrue(answeredLater(null, MorningErection.FIRM, DAY, nextDay))
        assertFalse(answeredLater(sameDay, MorningErection.FIRM, DAY, nextDay))
        assertTrue(answeredLater(sameDay, MorningErection.NONE, DAY, nextDay))
        assertTrue(answeredLater(sameDay.copy(later = true), MorningErection.FIRM, DAY, DAY))
    }

    /** The wheels for a past morning start from the middle of the recent wakings, or seven. */
    @Test
    fun theWheelsStartAtTheUsualWaking() {
        val checks = listOf(6, 7, 9).map { MorningCheck(MorningErection.FIRM, at(DAY, it, 15), WakeSource.SLEEP) }
        assertEquals(7 * 60 + 15, usualWakeMinute(checks, ZONE))
        assertEquals(7 * 60, usualWakeMinute(emptyList(), ZONE))
    }

    /** Only a man is asked; a woman's profile, and one with no sex recorded, never are. */
    @Test
    fun onlyAMaleProfileIsOffered() {
        assertTrue(morningCheckOffered("MALE"))
        assertFalse(morningCheckOffered("FEMALE"))
        assertFalse(morningCheckOffered(null))
        assertFalse(morningCheckOffered(""))
    }

    /** A morning is sexual data for the sheet's kept days, but never an act on the cycle calendar. */
    @Test
    fun aMorningIsSexualDataButNotAnAct() {
        val morning = check(MorningErection.FIRM)
        val solo =
            JournalEntry(
                day = DAY.toString(),
                occurredAt = WOKE_AT,
                kind = KIND_MASTURBATION,
                title = "",
                calories = 0.0,
            )
        val act = cycleRow(CycleLog.SEX_PROTECTED)
        val flow = cycleRow(CycleLog.FLOW_LIGHT)

        assertTrue(morning.isSexualData())
        assertFalse(morning.isSexualAct())
        listOf(solo, act).forEach {
            assertTrue(it.isSexualAct())
            assertTrue(it.isSexualData())
        }
        assertFalse(flow.isSexualData())
    }

    private fun check(answer: MorningErection, answeredAt: Long? = null) =
        morningCheckEntry("Morning", DAY, MorningCheck(answer, WOKE_AT, WakeSource.SLEEP, answeredAt = answeredAt))

    private fun cycleRow(log: CycleLog) = JournalEntry(
        day = DAY.toString(),
        occurredAt = WOKE_AT,
        kind = KIND_CYCLE_LOG,
        title = "",
        calories = 0.0,
        measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"${log.name}"}""",
    )

    private fun sleep(day: LocalDate, endHour: Int) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, endHour, 0),
        kind = "MEASUREMENT",
        title = "Sleep",
        calories = 0.0,
        sleepStartAt = at(day, endHour, 0) - 7 * 3_600_000L,
        sleepEndAt = at(day, endHour, 0),
    )

    private fun at(day: LocalDate, hour: Int, minute: Int): Long =
        ZonedDateTime.of(day.atTime(hour, minute), ZONE).toInstant().toEpochMilli()

    private companion object {
        val ZONE: ZoneId = ZoneId.systemDefault()
        val DAY: LocalDate = LocalDate.of(2026, 9, 20)
        val WOKE_AT: Long = ZonedDateTime.of(DAY.atTime(7, 12), ZONE).toInstant().toEpochMilli()
        val ANSWERED_AT: Long = ZonedDateTime.of(DAY.atTime(7, 30), ZONE).toInstant().toEpochMilli()
    }
}
