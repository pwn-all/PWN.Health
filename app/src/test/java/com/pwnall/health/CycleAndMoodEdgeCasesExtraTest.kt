package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The rest of the boundaries: the period cap the export and the import are supposed to share, the
 * mood bands on their exact lines, and the breathing pattern on the counts nobody tests.
 */
class CycleAndMoodEdgeCasesExtraTest {

    private val ams = ZoneId.of("Europe/Amsterdam")

    private fun day(text: String) = LocalDate.parse(text)

    private fun at(text: String, hour: Int = 0) =
        day(text).atStartOfDay(ams).plusHours(hour.toLong()).toInstant()

    private fun entry(kind: String, day: String) = JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = kind,
        title = kind,
        calories = 0.0,
    )

    /**
     * A day answered on both dials, which is what the tension band reads.
     *
     * The pleasantness has to be passed in rather than defaulted: the tension band sits between the
     * poor and elevated ones, so a fixture that leaves every day neutral lands on the 50% line and
     * tests the wrong rule.
     */
    private fun energyDay(day: String, mood: Mood, vararg energies: Energy) =
        energies.mapIndexed { index, energy ->
            JournalEntry(
                day = day,
                occurredAt = index.toLong(),
                kind = KIND_MOOD,
                title = "Mood",
                calories = 0.0,
                moodScore = mood.score,
                energyScore = energy.score,
            )
        }

    private fun moodDay(day: String, vararg moods: Mood) = moods.mapIndexed { index, mood ->
        JournalEntry(
            day = day,
            occurredAt = index.toLong(),
            kind = KIND_MOOD,
            title = "Mood",
            calories = 0.0,
            moodScore = mood.score,
        )
    }

    /**
     * Thirty-one days is the longest period the record type can be built from, and the export
     * publishes one. The import has to take back the app's own longest legal period — and refuse
     * the same calendar span in October, where one day is twenty-five hours long.
     */
    @Test
    fun theImportTakesExactlyTheLongestPeriodTheExportPublishes() {
        assertEquals(
            day("2026-06-01") to day("2026-07-01"),
            importedPeriodDays(at("2026-06-01"), at("2026-07-02"), ams, ams),
        )
        val overByASecond = at("2026-07-02").plusSeconds(1)
        assertNull(importedPeriodDays(at("2026-06-01"), overByASecond, ams, ams))

        assertEquals(
            day("2026-06-05") to day("2026-07-05"),
            importedPeriodDays(at("2026-06-05"), at("2026-07-06"), ams, ams),
        )
        // The same thirty-one calendar days across the autumn clock change are 745 hours.
        assertNull(importedPeriodDays(at("2026-10-05"), at("2026-11-05"), ams, ams))
    }

    /**
     * A traveller's record, or a foreign app stamping a fixed offset: the two local days read
     * backwards although the instants do not. No period can end the day before it starts.
     */
    @Test
    fun aPeriodEndingInAZoneBehindItsStartIsRefused() {
        val auckland = ZoneId.of("Pacific/Auckland")
        val honolulu = ZoneId.of("Pacific/Honolulu")
        val start = LocalDateTime.parse("2026-02-10T00:30").atZone(auckland).toInstant()
        val end = start.plus(Duration.ofHours(2))

        assertEquals(day("2026-02-10"), start.atZone(auckland).toLocalDate())
        assertEquals(day("2026-02-09"), end.atZone(honolulu).toLocalDate())
        assertNull(importedPeriodDays(start, end, auckland, honolulu))
    }

    /**
     * Cuba jumps from 23:59:59 straight to 01:00 on 8 March, so that day has no midnight and the
     * export's exclusive end lands on 01:00 instead. The day the person recorded must come back
     * unmoved: this is the save-and-restore of a single-day period.
     */
    @Test
    fun aPeriodThroughAMissingMidnightSurvivesTheRoundTrip() {
        val havana = ZoneId.of("America/Havana")
        val oneDay = CyclePeriod(day("2026-03-07"), day("2026-03-07"))
        val rows = listOf(entry(KIND_CYCLE, "2026-03-07"), entry(KIND_CYCLE_END, "2026-03-07"))

        assertEquals(listOf(oneDay), exportablePeriods(rows, havana))

        val start = oneDay.start.atStartOfDay(havana).toInstant()
        val end = oneDay.end.plusDays(1).atStartOfDay(havana).toInstant()
        assertEquals(oneDay.start to oneDay.end, importedPeriodDays(start, end, havana, havana))
    }

    /**
     * The autumn direction of the cap has its own test because getting it wrong aborts a whole
     * sync. The spring direction is the other half: a cap tightened to compensate would silently
     * drop legal periods every March.
     */
    @Test
    fun theSpringClockChangeDoesNotCostAPeriodItsExport() {
        val berlin = ZoneId.of("Europe/Berlin")
        val march = listOf(entry(KIND_CYCLE, "2026-03-01"), entry(KIND_CYCLE_END, "2026-03-31"))
        val overlong = listOf(entry(KIND_CYCLE, "2026-03-01"), entry(KIND_CYCLE_END, "2026-04-01"))

        // 31 published days, one of them 23 hours long: 743 hours, inside the cap.
        assertEquals(listOf(CyclePeriod(day("2026-03-01"), day("2026-03-31"))), exportablePeriods(march, berlin))
        assertEquals(emptyList<CyclePeriod>(), exportablePeriods(overlong, berlin))
    }

    /**
     * A foreign period stamped mid-day, twenty-nine days and twenty-two hours long and covering
     * thirty-one local days. Whatever the import writes into the journal, the export has to be able
     * to hand back, or the app quietly stops sharing a period it holds.
     *
     * One span it cannot hand back, by construction: a mid-day period covering thirty-TWO local
     * days is inside the cap as an interval, but the journal keeps days, so the export republishes
     * it as thirty-two whole days — over the record type's limit. Refusing to re-publish a record
     * Health Connect already holds under the other app's name is the lesser loss of the two.
     */
    @Test
    fun whateverTheImportAcceptsTheExportCanStillPublish() {
        val days = importedPeriodDays(at("2026-03-01", 9), at("2026-03-31", 8), ams, ams)
        assertEquals(day("2026-03-01") to day("2026-03-31"), days)

        val rows = listOf(entry(KIND_CYCLE, "2026-03-01"), entry(KIND_CYCLE_END, "2026-03-31"))
        val exported = exportablePeriods(rows, ams)
        assertEquals("a period the journal keeps but never publishes again", 1, exported.size)
    }

    private fun payload(json: String) =
        entry(KIND_CYCLE_LOG, "2026-07-02").copy(measurementPayloadJson = json)

    /**
     * The log is dug out of the payload with a regular expression, which is the one reader here
     * that can match something it should not. Every one of these rows can arrive from a partially
     * written backup, and none of them may throw.
     */
    @Test
    fun truncatedAndNearMissPayloadsReadAsNoLog() {
        assertEquals(CycleLog.SPOTTING, payload("""{ "cycle" : "SPOTTING" }""").cycleLog())
        assertEquals(CycleLog.MUCUS_DRY, payload("""{"flow":"x","cycle":"MUCUS_DRY"}""").cycleLog())
        assertNull("a key ending in the word is not the key", payload("""{"cycles":"FLOW_HEAVY"}""").cycleLog())
        assertNull("truncated", payload("""{"cycle":"FLOW_""").cycleLog())
        assertNull("empty", payload("").cycleLog())

        assertTrue(recordedBleedingDays(listOf(payload("""{ "cycle" : "SPOTTING" }"""))).isNotEmpty())
        assertTrue(recordedBleedingDays(listOf(payload("""{"cycles":"FLOW_HEAVY"}"""))).isEmpty())
        assertTrue(recordedBleedingDays(listOf(payload("""{"cycle":"FLOW_"""))).isEmpty())
    }

    /**
     * 28 is the WHO-5 cut-off the bands are written from, and the band is `<= 28`. A week whose day
     * means are 2, 2, 2, 2 and 2.6 sits exactly on it — the one place where the rescaling lands on
     * a value binary floating point cannot hold.
     */
    @Test
    fun aWeekExactlyOnTheTwentyEightPerCentLineIsLow() {
        val week = (0..3).flatMap { moodDay("2026-07-2$it", Mood.BAD, Mood.NEUTRAL) } +
            moodDay("2026-07-24", Mood.NEUTRAL, Mood.NEUTRAL, Mood.NEUTRAL, Mood.LOW, Mood.LOW)

        val summary = moodSummary(week)

        assertEquals(2.12, summary.average, 1e-9)
        assertEquals(28.0, summary.wellbeingPercent, 1e-9)
        // Nobody answered the second dial, so nothing here can be called tense — an unasked
        // question is not an answer of "calm".
        assertEquals(0.0, summary.tenseShare, 1e-9)
        assertEquals(0, summary.tenseDays)
        assertEquals("was ${summary.wellbeingPercent}", MoodState.LOW, summary.state)
    }

    /** Three entries and two days are both minimums; a week is named as soon as both are met. */
    @Test
    fun threeEntriesOverTwoDaysAreEnoughToNameAWeek() {
        val named = moodDay("2026-07-30", Mood.BAD, Mood.BAD) + moodDay("2026-07-31", Mood.BAD)
        val summary = moodSummary(named)

        assertEquals(3, summary.entries)
        assertEquals(0.0, summary.wellbeingPercent, 1e-9)
        assertEquals(MoodState.LOW, summary.state)
        assertTrue(summary.suggestBreathing)

        val tooFew = moodDay("2026-07-30", Mood.BAD) + moodDay("2026-07-31", Mood.BAD)
        assertEquals("two entries are a moment", MoodState.NO_DATA, moodSummary(tooFew).state)
    }

    /** The elevated band is written `>= 75`, and three good days land exactly on it. */
    @Test
    fun theElevatedBandOpensExactlyAtSeventyFivePerCent() {
        val onTheLine = listOf("2026-07-20", "2026-07-21", "2026-07-22").flatMap { moodDay(it, Mood.GOOD) }

        val elevated = moodSummary(onTheLine)
        assertEquals(75.0, elevated.wellbeingPercent, 1e-9)
        assertEquals(MoodState.ELEVATED, elevated.state)
        assertFalse(elevated.suggestBreathing)

        val balanced = moodSummary(onTheLine + moodDay("2026-07-23", Mood.NEUTRAL))
        assertEquals(68.75, balanced.wellbeingPercent, 1e-9)
        assertEquals(MoodState.BALANCED, balanced.state)
    }

    /**
     * The tension rule is a mean of per-day fractions over the days the second dial was answered,
     * so it only lands on its own threshold for particular day counts. Two wound-up days out of
     * five is exactly 0.4; a third of the week is not tense, however the mean reads.
     */
    @Test
    fun twoWoundUpDaysOutOfFiveAreTenseAndTwoOutOfSixAreNot() {
        val wound = energyDay("2026-07-20", Mood.LOW, Energy.WOUND_UP) +
            energyDay("2026-07-21", Mood.LOW, Energy.RESTLESS)
        val settled = listOf("2026-07-22", "2026-07-23", "2026-07-24")
            .flatMap { energyDay(it, Mood.FUN, Energy.CALM) }

        val tense = moodSummary(wound + settled)
        assertEquals(0.4, tense.tenseShare, 1e-9)
        assertEquals(5, tense.tenseDays)
        assertEquals("wellbeing ${tense.wellbeingPercent} is well clear of the low band", MoodState.TENSE, tense.state)
        assertTrue(tense.suggestBreathing)

        val calmer = moodSummary(wound + settled + energyDay("2026-07-25", Mood.FUN, Energy.CALM))
        assertEquals(1.0 / 3.0, calmer.tenseShare, 1e-9)
        assertEquals(MoodState.ELEVATED, calmer.state)
    }

    /**
     * And a week of wound-up days with no second dial answered is not tense, however bad it reads.
     *
     * This is the whole point of keeping the two apart: were the second rung of the pleasantness
     * ladder labelled "nervous", a run of mildly bad afternoons would be enough to name a week tense.
     */
    @Test
    fun aWeekWithNoEnergyAnswersIsNeverCalledTense() {
        val week = listOf("2026-07-20", "2026-07-21", "2026-07-22", "2026-07-23", "2026-07-24")
            .flatMap { moodDay(it, Mood.LOW, Mood.LOW) }

        val summary = moodSummary(week)

        assertEquals(0, summary.tenseDays)
        assertTrue(summary.state != MoodState.TENSE)
    }

    /**
     * A row whose score never made it through a backup sits next to a real one on the same day. If
     * the day mean re-read the raw column the 9 would drag that day to 6.0 and the week would read
     * as elevated — a fabricated good week on top of a corrupt row.
     */
    @Test
    fun aCorruptScoreLeavesItsDayCountingWithoutIt() {
        val mixed = moodDay("2026-07-29", Mood.NEUTRAL).map { it.copy(moodScore = 9) } +
            moodDay("2026-07-29", Mood.NEUTRAL) +
            moodDay("2026-07-30", Mood.GOOD) +
            moodDay("2026-07-31", Mood.GOOD)

        val summary = moodSummary(mixed)

        assertEquals(3, summary.entries)
        assertEquals("day means 3, 4 and 4", 11.0 / 3.0, summary.average, 1e-9)
        assertEquals(MoodState.BALANCED, summary.state)
    }

    /**
     * A timestamp from an import or a phone whose clock is years ahead. It is no entry of this
     * window and holds nothing shut — counted, it would answer the whole window for as long as the clock
     * stayed behind it — and the addition inside the helper, which overflows on the way, is never reached.
     */
    @Test
    fun aClockFromTheFutureDoesNotLockTheScale() {
        val now = 1_800_000_000_000L

        assertEquals(0L, moodCooldownLeftMs(4_000_000_000_000L, now))
        assertEquals(0L, moodCooldownLeftMs(Long.MAX_VALUE, now))
        assertEquals(0L, moodCooldownLeftMs(null, now))
    }

    /**
     * Inhale on the count, hold for half of it, exhale half as long again — rounded, never zero.
     * The odd counts are where the integer rounding can go the wrong way, and a corrupted setting
     * must breathe like the slowest legal tempo rather than produce a negative phase.
     */
    @Test
    fun everyTempoTheSettingsOfferKeepsThePatternsProportions() {
        (BREATH_TEMPO_CHOICES + listOf(-4)).forEach { tempo ->
            val counted = tempo.coerceAtLeast(1)
            val inhale = breathPhaseSeconds(BreathPhase.INHALE, tempo)
            val holdIn = breathPhaseSeconds(BreathPhase.HOLD_IN, tempo)
            val exhale = breathPhaseSeconds(BreathPhase.EXHALE, tempo)
            val holdOut = breathPhaseSeconds(BreathPhase.HOLD_OUT, tempo)
            val shape = "tempo $tempo: $inhale/$holdIn/$exhale/$holdOut"

            assertEquals(shape, counted, inhale)
            assertEquals(shape, (counted / 2.0).roundToInt().coerceAtLeast(1), holdIn)
            assertEquals(shape, (counted * 1.5).roundToInt(), exhale)
            assertEquals(shape, holdIn, holdOut)
            assertTrue(shape, exhale > inhale && inhale >= holdIn && holdIn >= 1)
            assertEquals(shape, inhale + holdIn + exhale + holdOut, breathCycleSeconds(tempo))
        }
    }

    /**
     * On a five count the four phases are 5, 3, 8 and 3 — all different, so an off-by-one in the
     * walk across them shows here and in none of the even-tempo tests.
     */
    @Test
    fun phaseBoundariesOnAnOddTempo() {
        assertEquals(19, breathCycleSeconds(5))
        val expected = listOf(
            4 to BreathTick(BreathPhase.INHALE, 1, 0),
            5 to BreathTick(BreathPhase.HOLD_IN, 3, 0),
            7 to BreathTick(BreathPhase.HOLD_IN, 1, 0),
            8 to BreathTick(BreathPhase.EXHALE, 8, 0),
            15 to BreathTick(BreathPhase.EXHALE, 1, 0),
            16 to BreathTick(BreathPhase.HOLD_OUT, 3, 0),
            18 to BreathTick(BreathPhase.HOLD_OUT, 1, 0),
            19 to BreathTick(BreathPhase.INHALE, 5, 1),
        )

        expected.forEach { (elapsed, tick) ->
            assertEquals("at $elapsed s", tick, breathTickAt(elapsed, 5))
        }
    }

    /**
     * A duration of zero arrives from a preference that was never written. Nothing was breathed
     * because there was no session to breathe — and clamping into an inverted range would take the
     * running screen down with an IllegalArgumentException.
     */
    @Test
    fun aSessionWithNoLengthDoesNotThrow() {
        assertEquals(BreathProgress(0, 0), breathProgressAt(10_000L, 0))
        assertEquals(BreathProgress(0, 0), breathProgressAt(10_000L, -5))
        // And a session that outlived its own length still banks only the length.
        assertEquals(BreathProgress(0, 300), breathProgressAt(1_000_000L, 300))
    }

    /**
     * A clock delta no start button could produce — a restored state, or a caller passing a
     * wall-clock stamp where an uptime one belongs. The countdown has long since run out and the
     * practice banks only the length the session was set to.
     *
     * The upper end is where the promise stops: the seconds are narrowed to an Int, so a delta past
     * Int.MAX_VALUE seconds is outside the contract rather than clamped by it. No uptime clock
     * reaches sixty-eight years, and a restored pre-reboot start gives a negative delta, not a huge
     * one, so the boundary below is the last value any caller can actually arrive with.
     */
    @Test
    fun aClockReadingNoSessionCouldProduceIsStillClampedToTheSession() {
        assertEquals(BreathProgress(0, 300), breathProgressAt(1_800_000_000_000L, 300))
        assertEquals(BreathProgress(0, 300), breathProgressAt(Int.MAX_VALUE.toLong() * 1_000L, 300))
    }

    private fun breathingEntry(at: Long, cycles: Int?, minutes: Double?) = JournalEntry(
        day = "2026-07-20",
        occurredAt = at,
        kind = KIND_BREATHING,
        title = "Breathing",
        calories = 0.0,
        durationMinutes = minutes,
        breathCycles = cycles,
    )

    /** A row that carries no cycles and no duration — an import, a hand-edited backup — is still a session. */
    @Test
    fun breathingRowsWithNothingRecordedStillCountAsSessions() {
        val newest = breathingEntry(at = 300, cycles = null, minutes = null)
        val older = breathingEntry(at = 100, cycles = 5, minutes = 2.5)

        val stats = breathingStats(listOf(newest, older))

        assertEquals("a session with nothing counted still happened", 2, stats.sessions)
        assertEquals(5, stats.cycles)
        assertEquals(2.5, stats.minutes, 1e-9)
    }
}
