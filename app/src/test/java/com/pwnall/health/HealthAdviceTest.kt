package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HealthAdviceTest {
    private val today = LocalDate.parse("2026-07-31")

    private fun nights(count: Int, minutes: Int, from: LocalDate = today.minusDays(count - 1L)) =
        (0 until count).map { offset ->
            val day = from.plusDays(offset.toLong())
            val start = day.atTime(23, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            JournalEntry(
                day = day.toString(),
                occurredAt = start,
                kind = "MEASUREMENT",
                title = "Sleep",
                calories = 0.0,
                sleepStartAt = start,
                sleepEndAt = start + minutes * 60_000L,
            )
        }

    // Completed days only, newest first — the activity window deliberately excludes the running day.
    private fun steps(count: Int, perDay: Long) = (0 until count).map { offset ->
        DaySteps(today.minusDays(offset + 1L).toString(), perDay)
    }

    private fun moods(count: Int, mood: Mood) = (0 until count).map { offset ->
        val day = today.minusDays(offset.toLong())
        JournalEntry(
            day = day.toString(),
            occurredAt = offset.toLong(),
            kind = KIND_MOOD,
            title = "Mood",
            calories = 0.0,
            moodScore = mood.score,
        )
    }

    private fun cycleStarts(vararg days: String) = days.map {
        JournalEntry(day = it, occurredAt = 0, kind = KIND_CYCLE, title = "Cycle", calories = 0.0)
    }

    // --- sleep ----------------------------------------------------------------------------------

    @Test
    fun sleepSaysNothingUntilThereAreEnoughNights() {
        assertNull(sleepAdvice(nights(SLEEP_MIN_NIGHTS - 1, 300), ageYears = 30, today = today))
    }

    @Test
    fun shortNightsAreRaisedAgainstTheSevenHourGuidance() {
        val advice = sleepAdvice(nights(10, 5 * 60 + 30), ageYears = 30, today = today)!!

        assertEquals(AdviceTopic.SLEEP, advice.topic)
        assertEquals(AdviceLevel.ATTENTION, advice.level)
        assertEquals(SLEEP_WINDOW_DAYS, advice.windowDays)
        assertEquals("sleep-short", advice.id)
    }

    @Test
    fun sevenHoursExactlyIsNotShort() {
        assertNull(sleepAdvice(nights(10, 7 * 60), ageYears = 30, today = today))
    }

    /**
     * A day is a night, whatever the row count. Ten nights of seven and a half hours, five of them
     * with an hour on the sofa filed as a row of its own, are ten days averaging eight hours — not
     * fifteen "nights" averaging five hours twenty, which is what reading them row by row would make.
     */
    @Test
    fun aNapIsPartOfItsDaysSleepNotAShortNightOfItsOwn() {
        assertNull(sleepAdvice(nights(10, 7 * 60 + 30) + nights(5, 60), ageYears = 30, today = today))

        // And where the sleep really is short, the nights counted are the days, not the rows.
        val short = sleepAdvice(nights(10, 5 * 60) + nights(5, 30), ageYears = 30, today = today)!!
        assertEquals(Counted(10, R.plurals.count_nights), short.args[0])
    }

    /** Long sleep is a marker far more often than a cause, so it is a habit note, not a warning. */
    @Test
    fun longNightsAreRaisedGentlyAndOnlyAboveTheBand() {
        assertNull(sleepAdvice(nights(10, 9 * 60), ageYears = 30, today = today))

        val advice = sleepAdvice(nights(10, 10 * 60), ageYears = 30, today = today)!!

        assertEquals(AdviceLevel.HABIT, advice.level)
        assertEquals("sleep-long", advice.id)
    }

    @Test
    fun theUpperBandIsTighterAfterSixtyFive() {
        assertNull(sleepAdvice(nights(10, 8 * 60 + 30), ageYears = 40, today = today))
        assertEquals("sleep-long", sleepAdvice(nights(10, 8 * 60 + 30), ageYears = 70, today = today)!!.id)
    }

    /**
     * The app admits minors, so a night is not read against the adult seven hours alone: a
     * fifteen-year-old averaging 7 h 20 min sleeps short. AASM's 2016 paediatric consensus puts 13–18 at 8–10
     * hours and 6–12 at 9–12, and the sentence names the age group rather than calling either an
     * adult.
     */
    @Test
    fun aTeenagerIsReadAgainstTheEightToTenHourBand() {
        val short = sleepAdvice(nights(10, 7 * 60 + 20), ageYears = 15, today = today)!!
        assertEquals("sleep-short", short.id)
        assertEquals(R.string.advice_sleep_short_teen_body, short.bodyRes)
        assertEquals(R.string.advice_source_aasm_pediatric, short.sourceRes)

        assertNull(sleepAdvice(nights(10, 9 * 60 + 30), ageYears = 15, today = today))

        val long = sleepAdvice(nights(10, 10 * 60 + 30), ageYears = 15, today = today)!!
        assertEquals("sleep-long", long.id)
        assertEquals(listOf<Any>("8", "10"), long.args.drop(2))
    }

    @Test
    fun aChildIsReadAgainstNineToTwelveAndAnAdultAgainstSeven() {
        val child = sleepAdvice(nights(10, 8 * 60 + 30), ageYears = 10, today = today)!!
        assertEquals(R.string.advice_sleep_short_child_body, child.bodyRes)
        assertNull(sleepAdvice(nights(10, 8 * 60 + 30), ageYears = 30, today = today))
        assertEquals(
            R.string.advice_sleep_short_body,
            sleepAdvice(nights(10, 6 * 60 + 30), ageYears = 18, today = today)!!.bodyRes,
        )
    }

    /** Under six the consensus counts naps into its hours, and this journal records nights. */
    @Test
    fun underSixNothingIsSaidAboutSleep() {
        assertNull(sleepBandMinutes(4))
        assertNull(sleepAdvice(nights(10, 5 * 60), ageYears = 4, today = today))
        assertEquals(9 * 60..12 * 60, sleepBandMinutes(6))
        assertEquals(8 * 60..10 * 60, sleepBandMinutes(17))
        assertEquals(7 * 60..9 * 60, sleepBandMinutes(18))
        assertEquals(7 * 60..8 * 60, sleepBandMinutes(65))
    }

    /** The long-sleep sentence prints the band that applied, not a fixed 7–9 for everyone. */
    @Test
    fun theLongSleepBodyCarriesItsOwnBand() {
        val adult = sleepAdvice(nights(10, 10 * 60), ageYears = 30, today = today)!!
        assertEquals(listOf<Any>("7", "9"), adult.args.drop(2))
        val older = sleepAdvice(nights(10, 9 * 60), ageYears = 70, today = today)!!
        assertEquals(listOf<Any>("7", "8"), older.args.drop(2))
    }

    @Test
    fun nightsOutsideTheWindowDoNotCount() {
        val old = nights(10, 300, from = today.minusDays(60))

        assertNull(sleepAdvice(old, ageYears = 30, today = today))
    }

    // --- activity -------------------------------------------------------------------------------

    @Test
    fun activityNeedsAFullWeekBeforeItSaysAnything() {
        assertNull(activityAdvice(steps(6, 1_000), ageYears = 30, today = today))
    }

    @Test
    fun aQuietWeekIsAHabitNoteAndAQuietMonthIsStronger() {
        val week = activityAdvice(steps(7, 3_000), ageYears = 30, today = today)!!
        assertEquals(AdviceLevel.HABIT, week.level)
        assertEquals(ACTIVITY_WINDOW_DAYS, week.windowDays)

        val month = activityAdvice(steps(30, 3_000), ageYears = 30, today = today)!!
        assertEquals(AdviceLevel.ATTENTION, month.level)
        assertEquals(ACTIVITY_LONG_WINDOW_DAYS, month.windowDays)
    }

    @Test
    fun theReferenceIsLowerAfterSixty() {
        // 6500 a day clears the older-adult reference but not the general one.
        assertTrue(activityAdvice(steps(7, 6_500), ageYears = 30, today = today) != null)
        assertNull(activityAdvice(steps(7, 6_500), ageYears = 65, today = today))
    }

    @Test
    fun anActiveWeekGetsNoAdviceAtAll() {
        assertNull(activityAdvice(steps(30, 9_000), ageYears = 30, today = today))
    }

    // --- mood -----------------------------------------------------------------------------------

    @Test
    fun moodNeedsAFortnightOfEntriesBeforeAnythingIsSaid() {
        assertNull(moodAdvice(moods(MOOD_MIN_ENTRIES - 1, Mood.BAD), today))
    }

    @Test
    fun aHeavyFortnightIsRaisedWithoutNamingAnything() {
        val advice = moodAdvice(moods(10, Mood.BAD), today)!!

        assertEquals(AdviceTopic.MOOD, advice.topic)
        assertEquals(AdviceLevel.ATTENTION, advice.level)
        assertEquals(MOOD_WINDOW_DAYS, advice.windowDays)
        assertEquals(10, advice.args.first())
        assertEquals(MOOD_WINDOW_DAYS, (advice.args[1] as Counted).quantity)
    }

    @Test
    fun aGoodFortnightIsLeftAlone() {
        assertNull(moodAdvice(moods(10, Mood.GOOD), today))
    }

    /**
     * An all-neutral fortnight sits exactly on the WHO-5 band edge, but the sentence counts the low
     * days — and "low mood on 0 of the last 14 days" is not a statement about anybody.
     */
    @Test
    fun aFortnightWithoutOneLowDaySaysNothing() {
        assertNull(moodAdvice(moods(10, Mood.NEUTRAL), today))
    }

    // --- cycle ----------------------------------------------------------------------------------

    @Test
    fun cycleSaysNothingFromOneOrTwoIntervals() {
        assertNull(cycleAdvice(cycleStarts("2026-07-01", "2026-07-29"), ageYears = 30, today = today))
    }

    @Test
    fun consistentlyLongCyclesEarnTheOneClinicianPrompt() {
        val advice = cycleAdvice(
            cycleStarts("2026-03-01", "2026-04-12", "2026-05-24", "2026-07-05"),
            ageYears = 30,
            today = today,
        )!!

        assertEquals(AdviceLevel.CLINICIAN, advice.level)
        assertEquals("cycle-long", advice.id)
    }

    @Test
    fun consistentlyShortCyclesDoTheSame() {
        // Twenty-day cycles: under FIGO's 24 and inside the range the screen calls a cycle at all.
        // A ten-day gap is read as a double entry on both sides, so it cannot be the fixture here.
        val advice = cycleAdvice(
            cycleStarts("2026-06-01", "2026-06-21", "2026-07-11", "2026-07-31"),
            ageYears = 30,
            today = today,
        )!!

        assertEquals("cycle-short", advice.id)
    }

    /** FIGO measures regularity as shortest-to-longest spread, and only over six or more cycles. */
    @Test
    fun spreadIsOnlyNamedOnceThereAreSixIntervals() {
        val five = cycleStarts(
            "2026-03-01",
            "2026-03-25",
            "2026-04-28",
            "2026-05-22",
            "2026-06-25",
            "2026-07-19",
        )
        assertNull(cycleAdvice(five, ageYears = 30, today = today))

        val seven = cycleStarts(
            "2026-01-01",
            "2026-01-25",
            "2026-02-28",
            "2026-03-24",
            "2026-04-27",
            "2026-05-21",
            "2026-06-24",
            "2026-07-18",
        )
        val advice = cycleAdvice(seven, ageYears = 30, today = today)!!
        assertEquals("cycle-spread", advice.id)
        assertEquals(AdviceLevel.ATTENTION, advice.level)
    }

    @Test
    fun aWiderSpreadIsAllowedOutsideTheTwentySixToFortyOneBand() {
        // Intervals 26, 28, 30, 34, 27, 29, 31: an eight-day spread, which is over the limit
        // for 26-41 and inside the wider one the reference range allows under 26.
        val starts = cycleStarts(
            "2026-01-05",
            "2026-01-31",
            "2026-02-28",
            "2026-03-30",
            "2026-05-03",
            "2026-05-30",
            "2026-06-28",
            "2026-07-29",
        )

        // The same record: flagged for a 30-year-old, ordinary variation for a 22-year-old.
        assertEquals("cycle-spread", cycleAdvice(starts, ageYears = 30, today = today)?.id)
        assertNull(cycleAdvice(starts, ageYears = 22, today = today))
    }

    /**
     * FIGO's band is "more than 38 days", and the usual length of cycles of 38, 39 and 39 days is
     * over it. Truncating the figure to its whole part would silently withhold the prompt for every
     * one between 38 and 39 — the 38.7 these average to, and the 38.5 a middle of two lengths can be.
     */
    @Test
    fun aMeanJustOverThirtyEightIsStillALongCycle() {
        val advice = cycleAdvice(
            cycleStarts("2026-04-01", "2026-05-09", "2026-06-17", "2026-07-26"),
            ageYears = 30,
            today = today,
        )!!

        assertEquals("cycle-long", advice.id)
        assertEquals(39, (advice.args[1] as Counted).quantity)
    }

    @Test
    fun ninetyDaysWithoutOneIsRaisedWhateverElseIsOnRecord() {
        val advice = cycleAdvice(cycleStarts("2026-04-01"), ageYears = 30, today = today)!!

        assertEquals(AdviceLevel.CLINICIAN, advice.level)
        assertEquals("cycle-absent", advice.id)
    }

    /**
     * A 70-day gap is a missed entry to the cycle screen, which drops it from the average and the
     * prediction — so it does not count towards "long cycles, see a clinician" at the same time.
     * Both sides read one plausibility range, so the same dates cannot mean two things.
     */
    @Test
    fun aSeventyDayGapIsAMissedEntryOnBothSides() {
        val starts = cycleStarts("2026-01-01", "2026-03-12", "2026-05-21", "2026-07-30")

        assertFalse(70 in PLAUSIBLE_CYCLE_DAYS)
        assertNull(cycleAdvice(starts, ageYears = 30, today = today))
        assertEquals(0, cycleSummary(starts, today).recordedCycles)
    }

    // --- assembly -------------------------------------------------------------------------------

    @Test
    fun cycleAdviceIsOnlyProducedForProfilesThatHaveOne() {
        val male = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1996-06-15")
        val journal = cycleStarts("2026-04-01")

        assertTrue(allAdvice(emptyList(), journal, male, today, 2026).none { it.topic == AdviceTopic.CYCLE })
        assertTrue(
            allAdvice(emptyList(), journal, male.copy(sex = "FEMALE"), today, 2026)
                .any { it.topic == AdviceTopic.CYCLE },
        )
    }

    @Test
    fun theMostPressingAdviceComesFirst() {
        val profile = UserProfile(sex = "FEMALE", weightKg = 70.0, heightCm = 170, birthDate = "1996-06-15")
        val journal = cycleStarts("2026-04-01") + nights(10, 5 * 60)

        val advice = allAdvice(steps(7, 2_000), journal, profile, today, 2026)

        assertEquals(AdviceLevel.CLINICIAN, advice.first().level)
        assertTrue(advice.size >= 3)
    }

    @Test
    fun anEmptyRecordProducesNoAdvice() {
        val profile = UserProfile(sex = "FEMALE", weightKg = 70.0, heightCm = 170, birthDate = "1996-06-15")

        assertTrue(allAdvice(emptyList(), emptyList(), profile, today, 2026).isEmpty())
    }

    /**
     * A topic switched off leaves the list entirely, and it leaves it once: the home page and the
     * health section both read this function, so there is nowhere for the hidden card to survive.
     */
    @Test
    fun aHiddenTopicIsLeftOutOfTheList() {
        val profile = UserProfile(sex = "FEMALE", weightKg = 70.0, heightCm = 170, birthDate = "1996-06-15")
        val journal = cycleStarts("2026-04-01") + nights(10, 5 * 60)
        val days = steps(7, 2_000)
        val shown = allAdvice(days, journal, profile, today, 2026)
        assertTrue(shown.any { it.topic == AdviceTopic.SLEEP })

        val kept = allAdvice(days, journal, profile, today, 2026, hidden = setOf(AdviceTopic.SLEEP.name))

        assertTrue(kept.none { it.topic == AdviceTopic.SLEEP })
        // Everything the topic contributed goes, and nothing else does. Counted rather than
        // written down as one: ten five-hour nights earn both the short-average card and the
        // run-of-short-nights one, and a fixture that happens to produce a single card is not the
        // thing being asserted here.
        assertEquals(shown.size - shown.count { it.topic == AdviceTopic.SLEEP }, kept.size)
        assertTrue(shown.count { it.topic == AdviceTopic.SLEEP } >= 1)
    }

    /**
     * And it does not arrive as a notification instead. The rule is asserted on its own because a
     * unit test has no context to hand [AdviceLog.pushable], which is the only thing it adds.
     */
    @Test
    fun aHiddenTopicNeverReachesTheLockScreenEither() {
        assertTrue(AdviceLog.mayLeaveTheApp(AdviceTopic.ACTIVITY, emptySet()))
        assertFalse(AdviceLog.mayLeaveTheApp(AdviceTopic.ACTIVITY, setOf(AdviceTopic.ACTIVITY.name)))
    }

    /**
     * The list that offers the way back is the enum's own order, whatever order the stored set
     * hands its names over in — otherwise the rows rearrange themselves between two readings of
     * the same page, and the finger going for one topic lands on another.
     */
    @Test
    fun theSwitchedOffListIsOrderedTheWayTheTopicsAreDeclared() {
        val stored = setOf(AdviceTopic.BOWEL.name, AdviceTopic.SLEEP.name, AdviceTopic.MOOD.name)

        assertEquals(
            listOf(AdviceTopic.SLEEP, AdviceTopic.MOOD, AdviceTopic.BOWEL),
            switchedOffTopics(stored) { true },
        )
    }

    /**
     * A build that drops a topic leaves its name in the set for ever — there is nothing left to
     * match it against, and nothing that would ever take it out. It is passed over rather than
     * drawn as a row with no name on it and a button that switches nothing back on.
     */
    @Test
    fun aNameThisBuildNoLongerKnowsIsNotARow() {
        val stored = setOf(AdviceTopic.SLEEP.name, "MOON_PHASE")

        assertEquals(listOf(AdviceTopic.SLEEP), switchedOffTopics(stored) { true })
    }

    @Test
    fun nothingSwitchedOffIsNothingToList() {
        assertTrue(switchedOffTopics(emptySet()) { true }.isEmpty())
    }

    /**
     * The gate the advice passes through is the gate the way back passes through.
     *
     * An adult switches "Intimate health" off; a backup arrives carrying no birth date at all, the
     * import replaces the profile with the empty one, and both age gates close — while the stored
     * set, which no import touches, still holds the name. Listing it would put the subject's own
     * title on a profile the rest of the app treats as a child's, which is the content, not a route
     * to it. The cycle answers the same way for the profile that does not have one.
     */
    @Test
    fun aTopicThisProfileIsNotOfferedIsNotOnTheWayBackEither() {
        val stored = setOf(
            AdviceTopic.SLEEP.name,
            AdviceTopic.CYCLE.name,
            AdviceTopic.TOBACCO.name,
            AdviceTopic.SEXUAL.name,
        )
        val imported = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "")

        assertEquals(
            listOf(AdviceTopic.SLEEP),
            switchedOffTopics(stored) { topicOffered(it, imported, today) },
        )
    }

    /** The grown profile is offered everything it ever wrote, the way back included. */
    @Test
    fun anAdultProfileIsOfferedTheGatedTopics() {
        val adult = UserProfile(sex = "FEMALE", weightKg = 70.0, heightCm = 170, birthDate = "1996-06-15")

        assertTrue(topicOffered(AdviceTopic.TOBACCO, adult, today))
        assertTrue(topicOffered(AdviceTopic.SEXUAL, adult, today))
        assertTrue(topicOffered(AdviceTopic.CYCLE, adult, today))
        assertTrue(topicOffered(AdviceTopic.SLEEP, adult, today))
    }

    /** Seventeen: old enough for the one gate, not for the other, and neither is a cycle. */
    @Test
    fun theTwoAgeGatesAreReadSeparately() {
        val teenager = UserProfile(sex = "MALE", weightKg = 60.0, heightCm = 170, birthDate = "2009-01-01")

        assertTrue(topicOffered(AdviceTopic.SEXUAL, teenager, today))
        assertFalse(topicOffered(AdviceTopic.TOBACCO, teenager, today))
        assertFalse(topicOffered(AdviceTopic.CYCLE, teenager, today))
    }
}

class BaselineTest {
    private val today = LocalDate.parse("2026-07-31")

    @Test
    fun aThinRecordProducesNoMeansAtAll() {
        val baseline = personalBaseline(
            days = listOf(DaySteps(today.toString(), 5_000)),
            journal = emptyList(),
            today = today,
        )

        assertNull(baseline.meanStepsPerDay)
        assertNull(baseline.meanSleepMinutes)
        assertNull(baseline.usualCycleDays)
        assertEquals(0, baseline.recordedCycles)
    }

    @Test
    fun stepsAverageOverTheWindowOnly() {
        val inside = (0 until 10).map { DaySteps(today.minusDays(it.toLong()).toString(), 8_000) }
        val outside = listOf(DaySteps(today.minusDays(90).toString(), 100_000))

        assertEquals(8_000L, personalBaseline(inside + outside, emptyList(), today).meanStepsPerDay)
    }

    /**
     * The usual night is a mean over days, as the sleep card's is. Ten nights of seven and a half
     * hours and five naps of an hour are ten days of 450 or 510 minutes, not fifteen nights at 320.
     */
    @Test
    fun theSleepBaselineCountsADayOnceHoweverManyRowsItHolds() {
        fun sleep(daysAgo: Long, minutes: Int): JournalEntry {
            val day = today.minusDays(daysAgo)
            val start = day.atTime(23, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            return JournalEntry(
                day = day.toString(),
                occurredAt = start,
                kind = "MEASUREMENT",
                title = "Sleep",
                calories = 0.0,
                sleepStartAt = start,
                sleepEndAt = start + minutes * 60_000L,
            )
        }
        val journal = (0L until 10L).map { sleep(it, 450) } + (0L until 5L).map { sleep(it, 60) }

        val baseline = personalBaseline(emptyList(), journal, today)

        assertEquals(480, baseline.meanSleepMinutes)
        assertEquals(30, baseline.sleepSpreadMinutes)
    }

    @Test
    fun waterAndFatAreAveragedPerDayNotPerEntry() {
        val journal = (0 until 6).flatMap { offset ->
            val day = today.minusDays(offset.toLong()).toString()
            listOf(
                JournalEntry(
                    day = day,
                    occurredAt = 1,
                    kind = KIND_WATER,
                    title = "",
                    calories = 0.0,
                    servingG = 250.0,
                ),
                JournalEntry(
                    day = day,
                    occurredAt = 2,
                    kind = KIND_WATER,
                    title = "",
                    calories = 0.0,
                    servingG = 250.0,
                ),
                JournalEntry(day = day, occurredAt = 3, kind = "EAT", title = "", calories = 500.0, fatG = 20.0),
            )
        }

        val baseline = personalBaseline(emptyList(), journal, today)

        assertEquals(500, baseline.meanWaterMl)
        assertEquals(20.0, baseline.meanFatG!!, 0.001)
    }

    @Test
    fun theCycleBaselineIsTheirOwnMeanAndSpread() {
        val journal = listOf("2026-07-05", "2026-07-11", "2026-07-19", "2026-07-28").map {
            JournalEntry(day = it, occurredAt = 0, kind = KIND_CYCLE, title = "", calories = 0.0)
        }

        val baseline = personalBaseline(emptyList(), journal, today)

        // Intervals of 6, 8 and 9 days: below the 15-day floor, so none of them are counted.
        assertNull(baseline.usualCycleDays)
    }

    @Test
    fun deviationIsRelativeAndSafeWithoutABaseline() {
        assertEquals(0.25, deviationFromBaseline(10_000.0, 8_000.0)!!, 0.001)
        assertNull(deviationFromBaseline(10_000.0, null))
        assertNull(deviationFromBaseline(10_000.0, 0.0))
    }

    private val eater = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1996-06-15")

    private fun meals(count: Int, kcalPerDay: Double) = (0 until count).map { offset ->
        val day = today.minusDays(offset.toLong())
        JournalEntry(
            day = day.toString(),
            occurredAt = offset.toLong(),
            kind = "EAT",
            title = "Meal",
            calories = kcalPerDay,
        )
    }

    @Test
    fun theModeDecidesWhetherEatingIsWorthMentioning() {
        // ~3300 kcal a day is a surplus for this profile whatever the goal is; only the goal differs.
        val journal = meals(7, 3_300.0)

        val tracking = energyAdvice(emptyList(), journal, eater, today, currentYear = 2026)
        val losing = energyAdvice(
            emptyList(),
            journal,
            eater.copy(energyGoal = "LOSE"),
            today,
            currentYear = 2026,
        )
        val gaining = energyAdvice(
            emptyList(),
            journal,
            eater.copy(energyGoal = "GAIN"),
            today,
            currentYear = 2026,
        )

        assertNull("just tracking never coaches", tracking)
        assertEquals("energy-lose", losing?.id)
        assertNull("the surplus is already there", gaining)
    }

    /**
     * The same weeks from a fourteen-year-old. No goal is acted on under eighteen, so there is no
     * weight-loss target for the week to be over and no gain target for it to fall short of.
     */
    @Test
    fun aMinorsWeekIsNotMeasuredAgainstAGoal() {
        val teenager = eater.copy(birthDate = "2012-03-01")
        val losing = teenager.copy(energyGoal = "LOSE")
        val gaining = teenager.copy(energyGoal = "GAIN")

        assertNull(energyAdvice(emptyList(), meals(7, 3_300.0), losing, today, currentYear = 2026))
        assertNull(energyAdvice(emptyList(), meals(7, 500.0), gaining, today, currentYear = 2026))
    }

    @Test
    fun aWeekWithTooLittleLoggedFoodSaysNothing() {
        val journal = meals(3, 3_300.0)

        assertNull(
            energyAdvice(emptyList(), journal, eater.copy(energyGoal = "LOSE"), today, currentYear = 2026),
        )
    }

    /**
     * Today is still being eaten. Six full days at 1,800 kcal sit over the LOSE target of 1,636, and
     * a 200 kcal breakfast averaged in as a seventh whole day would pull the mean to 1,571 — on track
     * every morning, whatever the week had been.
     */
    @Test
    fun todaysHalfEatenDayIsLeftOutOfTheWeek() {
        fun meal(day: LocalDate, kcal: Double) =
            JournalEntry(day = day.toString(), occurredAt = 0L, kind = "EAT", title = "Meal", calories = kcal)
        val fullDays = (1..6).map { daysAgo -> meal(today.minusDays(daysAgo.toLong()), 1_800.0) }
        val breakfast = meal(today, 200.0)

        val advice = energyAdvice(emptyList(), fullDays + breakfast, eater.copy(energyGoal = "LOSE"), today, 2026)

        assertEquals("energy-lose", advice?.id)
        assertEquals(6, (advice?.args?.first() as Counted).quantity)
    }

    @Test
    fun movementIsSubtractedBeforeTheComparison() {
        // The same intake, once walked off: 25,000 steps is 18.6 km, ≈745 kcal for an 80 kg person.
        // 2,200 sits above the LOSE target of 1,636 (1,780 resting x 1.2, less 500) and 1,455 sits
        // below it. A target built on a 1.55 activity factor would pay for the same walk a second
        // time.
        val journal = meals(7, 2_200.0)
        val walked = (0 until 7).map { DaySteps(today.minusDays(it.toLong()).toString(), 25_000L) }

        val sitting = energyAdvice(emptyList(), journal, eater.copy(energyGoal = "LOSE"), today, 2026)
        val walking = energyAdvice(walked, journal, eater.copy(energyGoal = "LOSE"), today, 2026)

        assertEquals("energy-lose", sitting?.id)
        assertNull("the deficit is real once the steps are counted", walking)
    }

    /**
     * A logged walk and the steps it was made of are one outing. Crediting both would take the week's
     * intake down by the walk's whole estimate — enough to withhold the losing advice from anyone
     * who writes their walks down.
     */
    @Test
    fun aLoggedWalkIsNotCreditedOnTopOfItsOwnSteps() {
        // 10,000 steps are worth 298 kcal for this profile, the walk's own estimate 224: one outing,
        // counted once. 2,000 eaten less 298 is above the LOSE target of 1,636; less both is below.
        val journal = meals(7, 2_000.0) + (0 until 7).map { offset ->
            JournalEntry(
                day = today.minusDays(offset.toLong()).toString(),
                occurredAt = offset.toLong(),
                kind = "ACTIVITY",
                title = "Walk",
                calories = 224.0,
                activityCode = "WALK",
            )
        }
        val walked = (0 until 7).map { DaySteps(today.minusDays(it.toLong()).toString(), 10_000L) }

        val advice = energyAdvice(walked, journal, eater.copy(energyGoal = "LOSE"), today, 2026)

        assertEquals("energy-lose", advice?.id)
    }
}
