package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The floors, windows and edges behind the two figures that describe a person to themselves: what
 * is normal *for them*, and how they slept.
 *
 * Both are denominators rather than verdicts — the baseline is what every "unusual for you" line
 * divides by, and the sleep score is the one number the doctor's report carries about the night —
 * so a mean published a day early, a nap counted as a night or a bedtime that crosses a seam is
 * wrong everywhere it is read and nowhere it is written.
 */
class HabitsEdgeCasesExtraTest {
    private val today = LocalDate.of(2026, 7, 31)
    private val august = LocalDate.of(2026, 8, 31)

    private fun steps(day: LocalDate) = DaySteps(day.toString(), 8_000)

    private fun entry(
        kind: String,
        day: String,
        servingG: Double = 0.0,
        mood: Int? = null,
    ) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = kind,
        calories = 0.0,
        servingG = servingG,
        moodScore = mood,
    )

    private fun water(day: LocalDate) = entry(KIND_WATER, day.toString(), servingG = 500.0)

    private fun mood(day: LocalDate) = entry(KIND_MOOD, day.toString(), mood = 3)

    private fun cycleStart(day: LocalDate) = entry(KIND_CYCLE, day.toString())

    /** One recorded sleep interval, stamped on the wall clock of [zone] the way the importer does. */
    private fun night(
        start: LocalDate,
        atHour: Int,
        minutes: Int,
        atMinute: Int = 0,
        zone: ZoneId = ZoneOffset.UTC,
    ): JournalEntry {
        val at = start.atTime(atHour, atMinute).atZone(zone).toInstant().toEpochMilli()
        return JournalEntry(
            day = start.toString(),
            occurredAt = at,
            kind = "MEASUREMENT",
            title = "Sleep",
            calories = 0.0,
            sleepStartAt = at,
            sleepEndAt = at + minutes * 60_000L,
        )
    }

    /**
     * Below the floor a mean is noise, and this one is the denominator every "unusual for you"
     * comparison divides by: published from six days, one bad week becomes the person's normal.
     */
    @Test
    fun sixRecordedDaysAreNoStepBaselineAndSevenAre() {
        val six = (1..6).map { steps(today.minusDays(it.toLong())) }
        val thin = personalBaseline(six, emptyList(), today)

        assertNull(thin.meanStepsPerDay)
        assertEquals(6, thin.daysObserved)

        val enough = personalBaseline(six + steps(today.minusDays(7)), emptyList(), today)
        assertEquals(8_000L, enough.meanStepsPerDay)
        assertEquals(7, enough.daysObserved)
    }

    /**
     * The step window is thirty finished days, so the thirtieth day back is the oldest one inside it.
     * Only a row at that edge catches an off-by-one; a row ninety days out is excluded either way.
     */
    @Test
    fun theBaselineWindowKeepsDayThirtyAndDropsDayThirtyOne() {
        val recent = (1..6).map { steps(today.minusDays(it.toLong())) }

        val inside = personalBaseline(recent + steps(today.minusDays(30)), emptyList(), today)
        assertEquals(8_000L, inside.meanStepsPerDay)
        assertEquals(7, inside.daysObserved)

        val outside = personalBaseline(recent + steps(today.minusDays(31)), emptyList(), today)
        assertNull(outside.meanStepsPerDay)
        assertEquals(6, outside.daysObserved)
    }

    /**
     * Today's steps, water and fat are still being written, and a usual counted with them in would
     * sink every morning: seven days of 8,000 and a morning of 500 would read as 7,062 a day.
     */
    @Test
    fun todaysUnfinishedTotalsAreNotPartOfTheUsual() {
        val week = (1..7).map { today.minusDays(it.toLong()) }
        val baseline = personalBaseline(
            week.map(::steps) + DaySteps(today.toString(), 500),
            week.map(::water) + entry(KIND_WATER, today.toString(), servingG = 100.0),
            today,
        )

        assertEquals(8_000L, baseline.meanStepsPerDay)
        assertEquals(7, baseline.daysObserved)
        assertEquals(500, baseline.meanWaterMl)
    }

    /**
     * The spread is printed beside the mean as "plus or minus N minutes" and shares its floor with
     * it, so one moving without the other would publish a spread with no mean behind it. Four
     * seven-hour nights and one of ten hours average 456 minutes; their population deviation is 72,
     * where a sample deviation over n-1 would say 80.
     */
    @Test
    fun theSleepBaselineArrivesWithItsSpreadAndBothArePopulationFigures() {
        val four = (0 until 4).map { night(today.minusDays(it.toLong()), atHour = 23, minutes = 450) }
        val thin = personalBaseline(emptyList(), four, today)
        assertNull(thin.meanSleepMinutes)
        assertNull(thin.sleepSpreadMinutes)

        val varied = listOf(420, 420, 420, 420, 600).mapIndexed { index, minutes ->
            night(today.minusDays(index.toLong()), atHour = 23, minutes = minutes)
        }
        val baseline = personalBaseline(emptyList(), varied, today)
        assertEquals(456, baseline.meanSleepMinutes)
        assertEquals(72, baseline.sleepSpreadMinutes)

        val steady = (0 until 5).map { night(today.minusDays(it.toLong()), atHour = 23, minutes = 450) }
        val flat = personalBaseline(emptyList(), steady, today)
        assertEquals(450, flat.meanSleepMinutes)
        // Five identical nights have no spread at all, which is where a sqrt of a negative surfaces.
        assertEquals(0, flat.sleepSpreadMinutes)
    }

    /**
     * Water and mood are two separate floors that happen to hold the same number today; only asking
     * each of them separately would notice one of them moving on its own.
     */
    @Test
    fun waterAndMoodAppearOnlyAtTheirOwnMinimumCount() {
        val fourDays = (1..4).map { today.minusDays(it.toLong()) }
        val fiveDays = fourDays + today.minusDays(5)

        assertNull(personalBaseline(emptyList(), fourDays.map { water(it) }, today).meanWaterMl)
        assertEquals(500, personalBaseline(emptyList(), fiveDays.map { water(it) }, today).meanWaterMl)

        assertNull(personalBaseline(emptyList(), fourDays.map { mood(it) }, today).meanMoodScore)
        val moods = personalBaseline(emptyList(), fiveDays.map { mood(it) }, today)
        assertEquals(3.0, moods.meanMoodScore!!, 1e-9)
    }

    /**
     * Two starts are one cycle, and one cycle is a coincidence: the count of what was recorded is
     * published from the first interval, the average only once there is a history to average.
     */
    @Test
    fun twoRecordedCyclesAreCountedButNotYetAveraged() {
        val three = (0 until 3).map { cycleStart(today.minusDays(28L * it)) }
        val thin = personalBaseline(emptyList(), three, today)

        assertEquals(2, thin.recordedCycles)
        assertNull(thin.usualCycleDays)
        assertNull(thin.cycleSpreadDays)

        val four = three + cycleStart(today.minusDays(28L * 3))
        val baseline = personalBaseline(emptyList(), four, today)
        assertEquals(3, baseline.recordedCycles)
        assertEquals(28, baseline.usualCycleDays)
        assertEquals(0, baseline.cycleSpreadDays)
    }

    /**
     * A period nobody logged leaves a seventy-day gap that is a missing entry, not a cycle.
     * [PLAUSIBLE_CYCLE_DAYS] exists precisely so the screen and the advice do not contradict each
     * other about one date, and the baseline behind the profile card is a third reader of the same
     * four dates: it must not print "usual cycle 38 days" for a person the cycle screen calls 28.
     */
    @Test
    fun aMissedPeriodEntryDoesNotDragThePersonalCycleMean() {
        val starts = listOf("2026-03-01", "2026-03-29", "2026-04-26", "2026-05-24", "2026-08-02")
            .map { cycleStart(LocalDate.parse(it)) }

        val baseline = personalBaseline(emptyList(), starts, august)
        val screen = cycleSummary(starts, august)

        // Gaps of 28, 28, 28 and 70 days: the last is dropped, so three cycles of 28 remain.
        assertEquals(3, baseline.recordedCycles)
        assertEquals(28, baseline.usualCycleDays)
        assertEquals(0, baseline.cycleSpreadDays)
        assertEquals(screen.recordedCycles, baseline.recordedCycles)
        assertEquals(screen.averageLengthDays, baseline.usualCycleDays)
    }

    /**
     * Rounding, not truncation: the cycle screen carries an explicit comment that an average of 29.9
     * truncated to 29 moves every prediction a day earlier than the history supports, and the
     * profile card describes the same person from the same rows.
     */
    @Test
    fun thePersonalCycleMeanRoundsTheWayThePredictionDoes() {
        val starts = listOf("2026-05-01", "2026-05-29", "2026-06-28", "2026-07-29")
            .map { cycleStart(LocalDate.parse(it)) }

        val baseline = personalBaseline(emptyList(), starts, august)

        // Gaps of 28, 30 and 31 days: the middle one is 30, which is the cycle anywhere else.
        assertEquals(3, baseline.recordedCycles)
        assertEquals(30, baseline.usualCycleDays)
        assertEquals(cycleSummary(starts, august).averageLengthDays, baseline.usualCycleDays)
    }

    /**
     * The deviation is a magnitude rather than a signed ratio: half the usual steps and half again
     * as many both read as 0.5, and a caller treating the figure as signed would never find out.
     */
    @Test
    fun deviationIsAShareOfTheUsualInBothDirections() {
        assertEquals(0.5, deviationFromBaseline(4_000.0, 8_000.0)!!, 1e-9)
        assertEquals(0.5, deviationFromBaseline(12_000.0, 8_000.0)!!, 1e-9)
        // A day with nothing recorded is a whole deviation from the usual, not an eighth of one.
        assertEquals(1.0, deviationFromBaseline(0.0, 8_000.0)!!, 1e-9)
        // A negative "usual" is no usual at all; dividing by it would hand back a negative share.
        assertNull(deviationFromBaseline(10_000.0, -5.0))
    }

    /**
     * The day column is free text restored from backups written by older versions and other
     * locales, and this reader is called from the profile card and the doctor's report alike.
     */
    @Test
    fun aDayColumnThatIsNotADateNeverReachesTheBaseline() {
        val broken = listOf("", "not-a-date", "2026-02-30", "20260731", "2026-7-31")

        val baseline = personalBaseline(
            broken.map { DaySteps(it, 8_000) },
            broken.map { entry(KIND_WATER, it, servingG = 500.0) } + broken.map { entry(KIND_CYCLE, it) },
            today,
        )

        assertNull(baseline.meanStepsPerDay)
        assertEquals(0, baseline.daysObserved)
        assertNull(baseline.meanWaterMl)
        assertEquals(0, baseline.recordedCycles)
    }

    /**
     * The app records more than one sleep row a day by design — the home screen sums them — and the
     * Health Connect import stores the longest session of any day, nap-only days included. Seven
     * hours at night and an hour in the afternoon is a person who slept eight hours, not two
     * four-hour nights eight hours short of the target.
     */
    @Test
    fun anAfternoonNapIsNotHalfOfANight() {
        val day = LocalDate.of(2026, 7, 28)

        val summary = sleepSummary(
            listOf(night(day, atHour = 23, minutes = 420), night(day, atHour = 14, minutes = 60)),
            ZoneOffset.UTC,
        )

        assertEquals(1, summary.nights)
        assertTrue("was ${summary.averageMinutes}", summary.averageMinutes >= 420)
        assertTrue("was ${summary.debtMinutes}", summary.debtMinutes <= 60)
    }

    /**
     * Ten past midnight is twenty minutes after ten to, not twenty-three hours and forty before it,
     * and a person who goes to bed either side of the hour must not be told their schedule is the
     * most scattered one there is. The seam the summary rotates the day around is parked at noon
     * because nobody starts a night there — this is the end of the day it was moved away from.
     */
    @Test
    fun bedtimesEitherSideOfMidnightAreTwentyMinutesApart() {
        val summary = sleepSummary(
            listOf(
                night(LocalDate.of(2026, 7, 28), atHour = 23, minutes = 480, atMinute = 50),
                night(LocalDate.of(2026, 7, 29), atHour = 0, minutes = 480, atMinute = 10),
            ),
            ZoneOffset.UTC,
        )

        assertEquals(2, summary.nights)
        assertTrue("was ${summary.bedtimeSpreadMinutes}", summary.bedtimeSpreadMinutes <= 15)
        assertTrue("was ${summary.score}", summary.score >= 95)
    }

    /**
     * In bed at 23:00 every night and up at 05:30 and 09:30 on alternate days is a schedule the
     * bedtime spread alone calls perfectly steady.
     */
    @Test
    fun aSteadyBedtimeDoesNotHideScatteredMornings() {
        val summary = sleepSummary(
            listOf(
                night(LocalDate.of(2026, 7, 28), atHour = 23, minutes = 390),
                night(LocalDate.of(2026, 7, 29), atHour = 23, minutes = 630),
            ),
            ZoneOffset.UTC,
        )

        assertEquals(0, summary.bedtimeSpreadMinutes)
        assertEquals(120, summary.wakeSpreadMinutes)
    }

    /**
     * A waking is measured from its own night's bedtime and inherits that seam, so it has none of its
     * own at noon: up at 11:50 and at 12:10 after a 04:00 bedtime is twenty minutes apart, not a day.
     */
    @Test
    fun wakingEitherSideOfNoonIsNotADayApart() {
        val summary = sleepSummary(
            listOf(
                night(LocalDate.of(2026, 7, 28), atHour = 4, minutes = 470),
                night(LocalDate.of(2026, 7, 29), atHour = 4, minutes = 490),
            ),
            ZoneOffset.UTC,
        )

        // Twenty minutes apart is a standard deviation of ten.
        assertEquals(10, summary.wakeSpreadMinutes)
    }

    /**
     * A waking is the recorded end, not the start plus the whole minutes slept. 23:00:30 to 07:00:10
     * is 479 whole minutes, which from the start would be a waking at 06:59 and turn mornings at 07:00
     * and 07:01 into two minutes apart.
     */
    @Test
    fun theWakingIsTheRecordedEnd() {
        val withSeconds = night(LocalDate.of(2026, 7, 28), atHour = 23, minutes = 480)
            .let { it.copy(sleepStartAt = it.sleepStartAt!! + 30_000L, sleepEndAt = it.sleepEndAt!! + 10_000L) }
        val summary = sleepSummary(
            listOf(withSeconds, night(LocalDate.of(2026, 7, 29), atHour = 23, minutes = 481)),
            ZoneOffset.UTC,
        )

        // One minute apart is half a minute of spread and prints as none; two would print one.
        assertEquals(0, summary.wakeSpreadMinutes)
    }

    /**
     * A nap is filed under the same day as the night it follows and ends later than that night does,
     * so a waking read off the day's last sleep would put the morning in the afternoon, and one read
     * off the day's total would move it on by the length of the nap. Both nights end at 07:00.
     */
    @Test
    fun aNapIsNeverTheWaking() {
        val days = listOf(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 29))
        val nights = days.map { night(it.minusDays(1), atHour = 23, minutes = 480).copy(day = it.toString()) }
        val nap = night(days.last(), atHour = 14, minutes = 90)

        val summary = sleepSummary(nights + nap, ZoneOffset.UTC)

        assertEquals(2, summary.nights)
        assertEquals(0, summary.wakeSpreadMinutes)
    }

    /**
     * Eleven hours on Saturday does not give back the five-hour Friday. Debt is the one figure that
     * cannot be derived from the average: a sum of signed differences would report a week that lost
     * three hours of sleep as owing nothing.
     */
    @Test
    fun aLongNightDoesNotRepayAShortOne() {
        val summary = sleepSummary(
            listOf(
                night(LocalDate.of(2026, 7, 28), atHour = 23, minutes = 300),
                night(LocalDate.of(2026, 7, 29), atHour = 23, minutes = 660),
            ),
            ZoneOffset.UTC,
        )

        assertEquals(480, summary.averageMinutes)
        assertEquals(180, summary.debtMinutes)
    }

    /**
     * Duration against target and bedtime regularity, weighted 70/30, is the documented shape of the
     * score; the only figures pinned anywhere are 100 and "less than 100", which swapping the two
     * weights would still satisfy. Half the target on a perfectly steady schedule is 0.5 x 70 + 30,
     * which is 65 before the implementation truncates the fraction away.
     */
    @Test
    fun theScoreIsDurationAndRegularityWeightedSeventyThirty() {
        val base = LocalDate.of(2026, 7, 20)
        val nights = (0 until 5).map { night(base.plusDays(it.toLong()), atHour = 23, minutes = 240) }

        val summary = sleepSummary(nights, ZoneOffset.UTC)

        assertEquals(240, summary.averageMinutes)
        assertEquals(0, summary.bedtimeSpreadMinutes)
        assertEquals(1_200, summary.debtMinutes)
        // Weighted the other way round this would be 85, and unweighted 75.
        assertTrue("was ${summary.score}", summary.score in 64..65)
    }

    /**
     * The clocks go forward at two in the morning, so the next bedtime is 23 hours later by the
     * clock that counts milliseconds and 24 by the one on the wall. A schedule nobody changed must
     * not lose points twice a year for everybody in the timezone.
     */
    @Test
    fun theClocksGoingForwardDoNotScatterASteadyBedtime() {
        val zone = ZoneId.of("America/New_York")
        val before = night(LocalDate.of(2024, 3, 9), atHour = 23, minutes = 480, zone = zone)
        val after = night(LocalDate.of(2024, 3, 10), atHour = 23, minutes = 480, zone = zone)

        // The fixture is the spring-forward night: 23 hours between two 23:00 bedtimes.
        assertEquals(23 * 3_600_000L, after.sleepStartAt!! - before.sleepStartAt!!)

        val summary = sleepSummary(listOf(before, after), zone)

        assertEquals(0, summary.bedtimeSpreadMinutes)
        assertEquals(100, summary.score)
    }

    /**
     * A row whose end precedes its start is what a half-finished import or a hand-edited backup
     * leaves behind, and a negative span reaching the average would print negative sleep in the
     * doctor's report. Twenty-four hours is not a night either, and 23:59 is.
     */
    @Test
    fun impossibleIntervalsAreDroppedAtBothEdges() {
        val summary = sleepSummary(impossibleNights() + longestNight(), ZoneOffset.UTC)

        assertEquals(1, summary.nights)
        assertEquals(1_439, summary.averageMinutes)
    }

    /** The baseline builds its own night list, and it has to agree with the summary about all four. */
    @Test
    fun theBaselineDropsTheSameImpossibleIntervals() {
        val steady = (0 until 5).map { night(today.minusDays(it.toLong()), atHour = 23, minutes = 480) }

        val clean = personalBaseline(emptyList(), steady, today)
        val polluted = personalBaseline(emptyList(), steady + impossibleNights(), today)

        assertEquals(480, clean.meanSleepMinutes)
        assertEquals(clean.meanSleepMinutes, polluted.meanSleepMinutes)
        assertEquals(clean.sleepSpreadMinutes, polluted.sleepSpreadMinutes)
    }

    /** A whole day, no time at all, and an interval that ends before it starts. */
    private fun impossibleNights() = listOf(
        night(today.minusDays(1), atHour = 22, minutes = 1_440),
        night(today.minusDays(2), atHour = 22, minutes = 0),
        night(today.minusDays(3), atHour = 22, minutes = -60),
    )

    /** The longest interval that is still one night: one minute short of a full day. */
    private fun longestNight() = listOf(night(today, atHour = 22, minutes = 1_439))

    /**
     * Twelve hours is as far apart as two bedtimes can be on a circle, and the wrap has to hold at
     * both ends of the day: one minute before midnight is one minute from midnight, not 1439.
     */
    @Test
    fun bedtimeDriftIsTheShorterWayRoundTheClock() {
        assertEquals(720, bedtimeDriftMinutes(0, 720))
        assertEquals(720, bedtimeDriftMinutes(720, 0))
        assertEquals(1, bedtimeDriftMinutes(1_439, 0))
        assertEquals(1, bedtimeDriftMinutes(0, 1_439))
        assertEquals(0, bedtimeDriftMinutes(0, 0))
    }
}
