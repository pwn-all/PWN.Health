package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the morning check-ins add up to. "Didn't notice" and a morning without a check-in are never
 * mornings with none; a night of seven hours is long and one of six is not short; the usual waking
 * is the median of the fourteen days before; an evening is read only when the day was kept by hand;
 * every comparison needs fifteen mornings a group.
 */
class MorningStatsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun d(month: Int, day: Int): LocalDate = LocalDate.of(2026, month, day)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun check(
        day: LocalDate,
        answer: MorningErection,
        wokeAt: Long = at(day, 7),
        answeredAt: Long? = null,
        wake: WakeSource = WakeSource.TYPED,
        fromPhone: Boolean = false,
    ) = morningCheckEntry(
        "Morning",
        day,
        MorningCheck(answer, wokeAt, wake, answeredAt = answeredAt, fromPhone = fromPhone),
    )

    private val march = d(3, 1)..d(3, 31)

    @Test
    fun didntNoticeAndUnansweredMorningsAreLeftOutOfTheShare() {
        val rows = listOf(
            check(d(3, 1), MorningErection.FIRM),
            check(d(3, 2), MorningErection.PARTLY),
            check(d(3, 3), MorningErection.NONE),
            check(d(3, 4), MorningErection.DIDNT_NOTICE),
            // Two answers for the 6th: the one given last stands.
            check(d(3, 6), MorningErection.NONE, answeredAt = 1L),
            check(d(3, 6), MorningErection.FIRM, answeredAt = 2L),
            check(d(4, 1), MorningErection.NONE),
        )
        val shares = morningShares(rows, march)
        assertEquals(MorningShares(firm = 2, partly = 1, none = 1, didntNotice = 1), shares)
        assertEquals(4, shares.answered)
        assertEquals(DayShare(3, 4), shares.firmOrPartlyShare)
        assertEquals(DayShare(2, 4), shares.firmShare)
    }

    /** 23:50 and 00:10 are twenty minutes apart and have midnight between them, not noon. */
    @Test
    fun wakingsAcrossMidnightHaveTheirMedianAtMidnight() {
        val rows = listOf(
            check(d(3, 1), MorningErection.FIRM, wokeAt = at(d(3, 1), 23, 50)),
            check(d(3, 2), MorningErection.DIDNT_NOTICE, wokeAt = at(d(3, 2), 0, 10)),
        )
        assertEquals(
            Wakings(count = 2, medianMinute = 0, spreadMinutes = 10, source = WakeSource.TYPED),
            morningWakings(rows, march, zone),
        )
        val one = morningWakings(rows.take(1), march, zone)
        assertEquals(23 * 60 + 50, one.medianMinute)
        assertNull(one.spreadMinutes)
        assertEquals(Wakings(0, null, null, null), morningWakings(emptyList(), march, zone))
    }

    /**
     * An answer tapped at 11:40 with no sleep record and no time set files the moment of answering,
     * which is no waking; and the rest are read from the source most of them used — three from the
     * sleep record, two set by hand — so the spread is never the gap between a recorded end and a
     * remembered time.
     */
    @Test
    fun theUsualWakingLeavesOutTheMomentOfAnsweringAndReadsOneSource() {
        val rows = listOf(
            check(d(3, 1), MorningErection.FIRM, wokeAt = at(d(3, 1), 7), wake = WakeSource.SLEEP),
            check(d(3, 2), MorningErection.NONE, wokeAt = at(d(3, 2), 7, 10), wake = WakeSource.SLEEP),
            check(d(3, 3), MorningErection.DIDNT_NOTICE, wokeAt = at(d(3, 3), 7, 20), wake = WakeSource.SLEEP),
            check(d(3, 4), MorningErection.FIRM, wokeAt = at(d(3, 4), 6)),
            check(d(3, 5), MorningErection.FIRM, wokeAt = at(d(3, 5), 6)),
        ) + (6..9).map { day ->
            val tapped = at(d(3, day), 11, 40)
            check(d(3, day), MorningErection.PARTLY, wokeAt = tapped, answeredAt = tapped)
        }

        assertEquals(
            Wakings(count = 3, medianMinute = 7 * 60 + 10, spreadMinutes = 8, source = WakeSource.SLEEP),
            morningWakings(rows, march, zone),
        )
        // Only the moments of answering: no waking to take a time of.
        assertEquals(Wakings(0, null, null, null), morningWakings(rows.drop(5), march, zone))
    }

    /**
     * A waking read off the phone's first use is measured, as a sleep record's end is, and read with
     * them; but the stretch says it holds one, so the sheet does not call every time a recorded end.
     */
    @Test
    fun aPhoneWakingIsMeasuredButNotCalledASleepRecord() {
        val recorded = (1..3).map { day ->
            check(d(3, day), MorningErection.FIRM, wokeAt = at(d(3, day), 7), wake = WakeSource.SLEEP)
        }
        val phone =
            check(
                d(3, 4),
                MorningErection.PARTLY,
                wokeAt = at(d(3, 4), 7, 30),
                wake = WakeSource.SLEEP,
                fromPhone = true,
            )

        assertFalse(morningWakings(recorded, march, zone).fromPhone)
        val both = morningWakings(recorded + phone, march, zone)
        assertEquals(4, both.count)
        assertEquals(WakeSource.SLEEP, both.source)
        assertTrue(both.fromPhone)
    }

    /**
     * A night of six hours and an hour's nap that afternoon, filed under the same day: the night that
     * ended that morning is six hours, not seven. A night broken by a waking at three is one night;
     * a night recorded twice is counted once.
     */
    @Test
    fun theNightIsTheSleepThatEndedFirstWithoutTheAfternoonNap() {
        val day = d(3, 10)
        val eve = day.minusDays(1)
        fun sleep(from: Long, to: Long) = JournalEntry(
            day = day.toString(),
            occurredAt = to,
            kind = "SLEEP",
            title = "Sleep",
            calories = 0.0,
            sleepStartAt = from,
            sleepEndAt = to,
        )
        assertEquals(
            mapOf(day to 360),
            nightEndingOn(listOf(sleep(at(day, 1), at(day, 7)), sleep(at(day, 14), at(day, 15)))),
        )
        val broken = listOf(sleep(at(eve, 23), at(day, 3)), sleep(at(day, 3, 30), at(day, 7)))
        assertEquals(mapOf(day to 450), nightEndingOn(broken))
        val twice = listOf(sleep(at(eve, 23), at(day, 7)), sleep(at(eve, 23, 10), at(day, 6, 50)))
        assertEquals(mapOf(day to 480), nightEndingOn(twice))
    }
}
