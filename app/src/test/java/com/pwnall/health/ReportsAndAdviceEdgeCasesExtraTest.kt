package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The advice engines at the exact values their thresholds are written at, and at the records that
 * are not what they look like: an age nobody typed in, a week landing exactly on its own target,
 * one stale row standing in for a quarter of logging.
 */
class ReportsAndAdviceEdgeCasesExtraTest {
    private val today: LocalDate = LocalDate.of(2026, 7, 31)
    private val adult = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1996-06-15")
    private val loggedAt: Instant = Instant.parse("2026-07-31T12:00:00Z")
    private val now: Instant = loggedAt.plusSeconds(1_800)

    private fun entry(kind: String, day: LocalDate, subtitle: String = "", serving: Double = 0.0) = JournalEntry(
        day = day.toString(),
        occurredAt = 0L,
        kind = kind,
        title = kind,
        subtitle = subtitle,
        calories = 0.0,
        servingG = serving,
    )

    /** A sleep row exactly as the editor writes one: two timestamps on a measurement. */
    private fun sleepRow(day: LocalDate, minutes: Int?, atHour: Int = 23): JournalEntry {
        val start = day.atTime(atHour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return JournalEntry(
            day = day.toString(),
            occurredAt = start,
            kind = "MEASUREMENT",
            title = "Sleep",
            calories = 0.0,
            sleepStartAt = start,
            sleepEndAt = minutes?.let { start + it * 60_000L },
        )
    }

    private fun nights(count: Int, minutes: Int) =
        (0 until count).map { sleepRow(today.minusDays(it.toLong()), minutes) }

    // Completed days only: the activity window deliberately stops before the day still being walked.
    private fun steps(count: Int, perDay: Long) =
        (0 until count).map { DaySteps(today.minusDays(it + 1L).toString(), perDay) }

    private fun moodRow(day: LocalDate, mood: Mood) = JournalEntry(
        day = day.toString(),
        occurredAt = 0L,
        kind = KIND_MOOD,
        title = "Mood",
        calories = 0.0,
        moodScore = mood.score,
    )

    private fun cycleStart(day: LocalDate) = entry(KIND_CYCLE, day)

    private fun meal(day: LocalDate, kcal: Double, fibreG: Double? = null) = JournalEntry(
        day = day.toString(),
        occurredAt = 0L,
        kind = "EAT",
        title = "Meal",
        calories = kcal,
        fiberG = fibreG,
    )

    private fun waterDay(day: LocalDate, millilitres: Double, voids: Int) =
        listOf(entry(KIND_WATER, day, serving = millilitres)) + (1..voids).map { entry(KIND_URINATION, day) }

    private fun act(day: LocalDate, notes: List<SexNote>) = JournalEntry(
        day = day.toString(),
        occurredAt = 0L,
        kind = KIND_CYCLE_LOG,
        title = "sex",
        calories = 0.0,
        measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"${CycleLog.SEX_UNPROTECTED.name}",""" +
            """"$CYCLE_NOTES_KEY":[${notes.joinToString(",") { "\"${it.name}\"" }}]}""",
    )

    private fun session(code: String?, minutes: Double?, at: Instant = loggedAt) = JournalEntry(
        day = today.toString(),
        occurredAt = at.toEpochMilli(),
        kind = "ACTIVITY",
        title = "Session",
        calories = 100.0,
        activityCode = code,
        durationMinutes = minutes,
    )

    // --- the age the advice is read at ------------------------------------------------------------

    /**
     * A restored backup can leave a profile with no birth date at all, which is a state the advice
     * file names in its own KDoc. An unknown age must not be read as an old one: the same record
     * decides both which sleep band applies and which step reference is used, and at 120 the person
     * is handed the 65+ ceiling and the 60+ step figure without ever having said how old they are.
     */
    @Test
    fun anUnknownBirthDateIsNotAnAgeOfOneHundredAndTwenty() {
        val journal = nights(10, 8 * 60 + 30)
        val quietWeek = steps(7, 6_500)

        val known = allAdvice(quietWeek, journal, adult, today, 2026)
        val unknown = allAdvice(quietWeek, journal, adult.copy(birthDate = ""), today, 2026)

        assertTrue("a known age said ${known.map(Advice::id)}", known.none { it.id == "sleep-long" })
        assertTrue("a known age said ${known.map(Advice::id)}", known.any { it.topic == AdviceTopic.ACTIVITY })
        assertTrue("an unknown age said ${unknown.map(Advice::id)}", unknown.none { it.id == "sleep-long" })
        assertTrue("an unknown age said ${unknown.map(Advice::id)}", unknown.any { it.topic == AdviceTopic.ACTIVITY })
    }

    /**
     * Somebody born on the last day of December is seventeen until December, and AASM's paediatric
     * band runs to the eighteenth birthday. Read by the year alone, they cross into the adult 7–9
     * band on the 1st of January — eleven months early, and 7 h 30 min stops being short sleep.
     */
    @Test
    fun aSeventeenYearOldKeepsThePaediatricBandUntilTheirBirthday() {
        val teenager = adult.copy(birthDate = "2008-12-31")

        val advice = allAdvice(emptyList(), nights(10, 7 * 60 + 30), teenager, today, 2026)
            .firstOrNull { it.topic == AdviceTopic.SLEEP }

        assertEquals("sleep-short", advice?.id)
        assertEquals(R.string.advice_sleep_short_teen_body, advice?.bodyRes)
        assertEquals(R.string.advice_source_aasm_pediatric, advice?.sourceRes)
    }

    // --- sleep ------------------------------------------------------------------------------------

    /**
     * A row whose end precedes its start, one still being slept, and one of a whole day are none of
     * them a night. A corrupt row must neither push the count over the seven nights a mean needs nor
     * drag the mean once it is there — six short nights say nothing until a seventh real one arrives.
     */
    @Test
    fun aCorruptSleepRowIsNeitherANightNorAWeight() {
        val six = nights(6, 5 * 60)
        val corrupt = listOf(
            sleepRow(today.minusDays(6), -60),
            sleepRow(today.minusDays(7), null),
            sleepRow(today.minusDays(8), 1_440),
        )

        assertNull(sleepAdvice(six, ageYears = 30, today = today))
        assertNull(
            "three corrupt rows said ${sleepAdvice(six + corrupt, 30, today)?.id}",
            sleepAdvice(six + corrupt, ageYears = 30, today = today),
        )
        val withSeventh = six + sleepRow(today.minusDays(6), 5 * 60)
        assertEquals("sleep-short", sleepAdvice(withSeventh, ageYears = 30, today = today)?.id)
    }

    // --- steps ------------------------------------------------------------------------------------

    /**
     * Ding et al. put most of the benefit at about 7,000 steps, so reaching it is not a quiet week;
     * Paluch et al. flatten the curve at 60, and a 59-year-old is not yet an older adult.
     */
    @Test
    fun theStepReferenceSwitchesAtSixtyAndIsComparedInclusively() {
        val reached = activityAdvice(steps(7, 7_000), ageYears = 30, today = today)
        assertNull("7,000 a day said ${reached?.id}", reached)
        assertEquals("activity-week", activityAdvice(steps(7, 6_999), ageYears = 30, today = today)?.id)
        assertNull("6,000 at 60 said something", activityAdvice(steps(7, 6_000), ageYears = 60, today = today))
        assertEquals("activity-week", activityAdvice(steps(7, 6_000), ageYears = 59, today = today)?.id)
    }

    // --- mood -------------------------------------------------------------------------------------

    /**
     * The sentence names low days, and a day is a day however many times the scale was tapped on it
     * — the weighting the mood summary applies to everything else it reports.
     */
    @Test
    fun moodCountsLowDaysRatherThanTaps() {
        val sevenBad = (0 until 7).map { moodRow(today.minusDays(it.toLong()), Mood.BAD) }
        assertEquals(7, moodAdvice(sevenBad, today)?.args?.first())

        val mixed = (0 until 4).map { moodRow(today.minusDays(it.toLong()), Mood.BAD) } +
            (4 until 7).map { moodRow(today.minusDays(it.toLong()), Mood.GOOD) }
        assertEquals(4, moodAdvice(mixed, today)?.args?.first())

        val twoHardDays = (0 until 10).map { moodRow(today.minusDays((it % 2).toLong()), Mood.BAD) }
        assertEquals(2, moodAdvice(twoHardDays, today)?.args?.first())

        val beforeTheFortnight = (0 until 8).map { moodRow(today.minusDays(15L + it), Mood.BAD) }
        assertNull(moodAdvice(beforeTheFortnight, today))
    }

    /**
     * The advice and the mood card read one band. Five low days and five good ones average the
     * middle of the dial, which the card calls balanced — and the advice, on a cut-off of its own
     * that took 50 in, would still tell the same fortnight its mood had been low on five days of it.
     */
    @Test
    fun aFortnightTheCardCallsBalancedGetsNoLowMoodAdvice() {
        val evenlySplit = (0 until 10).map { back ->
            moodRow(today.minusDays(back.toLong()), if (back < 5) Mood.LOW else Mood.GOOD)
        }

        assertEquals(MoodState.BALANCED, moodSummary(evenlySplit).state)
        assertNull(moodAdvice(evenlySplit, today))
        // A shade under the middle is the band below it, on the card and here alike.
        val under = evenlySplit.drop(1) + moodRow(today, Mood.BAD)
        assertEquals(MoodState.POOR, moodSummary(under).state)
        assertEquals(5, moodAdvice(under, today)?.args?.first())
    }

    // --- cycle ------------------------------------------------------------------------------------

    /** Ninety days is the threshold itself, and absence is raised whatever the rest of the record says. */
    @Test
    fun ninetyDaysWithoutAStartIsRaisedOnTheDayItself() {
        val advice = cycleAdvice(listOf(cycleStart(today.minusDays(90))), ageYears = 30, today = today)!!

        assertEquals("cycle-absent", advice.id)
        assertEquals(AdviceLevel.CLINICIAN, advice.level)
        assertEquals(Counted(90, R.plurals.count_days), advice.args.first())
        assertEquals(90, advice.windowDays)

        assertNull(cycleAdvice(listOf(cycleStart(today.minusDays(89))), ageYears = 30, today = today))

        val ordinaryThenSilence = listOf(174L, 146L, 118L, 90L).map { cycleStart(today.minusDays(it)) }
        assertEquals("cycle-absent", cycleAdvice(ordinaryThenSilence, ageYears = 30, today = today)?.id)
    }

    /**
     * Two rows on one day are one start — a second tap is not a zero-day cycle — and a start typed
     * with the wrong year makes a gap that runs backwards, which is not ninety days of silence.
     */
    @Test
    fun aDuplicatedDayIsOneStartAndAFutureStartIsNoAbsence() {
        val duplicated = listOf(56L, 28L, 28L, 0L).map { cycleStart(today.minusDays(it)) }
        val twoIntervals = cycleAdvice(duplicated, ageYears = 30, today = today)
        assertNull("a duplicate made ${twoIntervals?.id}", twoIntervals)

        val mistyped = listOf(81L, 53L, 25L).map { cycleStart(today.minusDays(it)) } + cycleStart(today.plusDays(3))
        val ahead = cycleAdvice(mistyped, ageYears = 30, today = today)
        assertNull("a start dated ahead made ${ahead?.id}", ahead)
    }

    // --- water in, little out ---------------------------------------------------------------------

    /**
     * Three thresholds meet in one line: two and a half litres, no more than two voids, and two such
     * days. One is a hot afternoon; three voids is not far below the four to eight ICS/IUGA call
     * ordinary, and 2,499 ml is not two and a half litres.
     */
    @Test
    fun hydrationNeedsTwoMismatchedDaysAtTheThresholdItself() {
        val yesterday = today.minusDays(1)
        val two = waterDay(today, 2_500.0, 2) + waterDay(yesterday, 2_500.0, 2)

        val advice = hydrationAdvice(two, today, imperial = false)!!
        assertEquals("hydration-voids", advice.id)
        assertEquals(AdviceLevel.ATTENTION, advice.level)
        assertEquals(Counted(2, R.plurals.count_days), advice.args.first())

        assertNull(hydrationAdvice(waterDay(today, 2_500.0, 2), today, imperial = false))
        val justUnder = waterDay(today, 2_499.0, 2) + waterDay(yesterday, 2_499.0, 2)
        assertNull(hydrationAdvice(justUnder, today, imperial = false))
        val threeVoids = waterDay(today, 2_500.0, 3) + waterDay(yesterday, 2_500.0, 3)
        assertNull(hydrationAdvice(threeVoids, today, imperial = false))
    }

    // --- tobacco ----------------------------------------------------------------------------------

    /** Five of seven days is daily smoking; twelve cigarettes over four days is four days. */
    @Test
    fun tobaccoCountsDaysRatherThanCigarettes() {
        val fiveDays = (0 until 5).map { entry(KIND_SMOKING, today.minusDays(it.toLong()), serving = 0.8) }

        val advice = tobaccoAdvice(fiveDays, today)!!
        assertEquals("tobacco-daily", advice.id)
        assertEquals(Counted(5, R.plurals.count_days), advice.args.first())

        assertNull(tobaccoAdvice(fiveDays.drop(1), today))
        val twelveOnFour = (0 until 12).map { entry(KIND_SMOKING, today.minusDays((it % 4).toLong()), serving = 0.8) }
        assertNull("said ${tobaccoAdvice(twelveOnFour, today)?.id}", tobaccoAdvice(twelveOnFour, today))
        val lastWeek = (8 until 13).map { entry(KIND_SMOKING, today.minusDays(it.toLong()), serving = 0.8) }
        assertNull(tobaccoAdvice(lastWeek, today))
    }

    // --- fibre ------------------------------------------------------------------------------------

    // --- the bowel --------------------------------------------------------------------------------

    /**
     * Nine stools in a quarter is far under the thirty-nine that three a week comes to, and that is
     * the clinician line. A loose week does not delete it. [bowelAdvice] returns one statement and
     * reads the two directions in the order its own doc names them — the loose ones first — so the
     * fresher problem speaks while it is inside the week, and the quarter's line is back the moment
     * the bout rolls out of it. (No level precedence is asserted here: unlike the sexual rules,
     * which say outright that a frequency never speaks over a symptom, nothing in this engine's KDoc
     * promises the more pressing of two statements wins.)
     */
    @Test
    fun aLooseWeekIsAnsweredFirstAndTheQuartersLineSurvivesIt() {
        val quarter = (0 until 9).map { entry(KIND_STOOL, today.minusDays(90L - it * 10), StoolKind.NORMAL.name) }
        assertEquals("bowel-rare-quarter", bowelAdvice(quarter, today)?.id)

        val loose = (0 until 3).map { entry(KIND_STOOL, today.minusDays(it.toLong()), StoolKind.LOOSE.name) }
        val advice = bowelAdvice(quarter + loose, today)!!

        assertEquals("bowel-loose", advice.id)
        assertEquals(AdviceLevel.ATTENTION, advice.level)
        assertEquals("bowel-rare-quarter", bowelAdvice(quarter + loose, today.plusDays(7))?.id)
    }

    /**
     * A quarter's verdict needs a quarter's record. One row from over a year ago says nothing about
     * the three months since, and two stools logged this week are the record of somebody who started
     * logging on Tuesday — the exact reading the guard in front of the clinician line exists to stop.
     */
    @Test
    fun oneAncientRowIsNotAQuartersRecord() {
        val thisWeek = listOf(0L, 2L).map { entry(KIND_STOOL, today.minusDays(it), StoolKind.NORMAL.name) }
        assertEquals("bowel-rare-long", bowelAdvice(thisWeek, today)?.id)

        val withAncient = thisWeek + entry(KIND_STOOL, today.minusDays(400), StoolKind.NORMAL.name)
        val advice = bowelAdvice(withAncient, today)!!

        assertEquals("bowel-rare-long", advice.id)
        assertEquals(AdviceLevel.ATTENTION, advice.level)
    }

    // --- sexual health ----------------------------------------------------------------------------

    /**
     * Rider et al. compared 21 or more ejaculations a month against 4–7, and the app speaks only for
     * the low end it defined: four is inside it, five is not. Sixty solo acts is the other edge, and
     * a repeated symptom outranks either — a frequency is never allowed to speak over one.
     */
    @Test
    fun theProstateAssociationSpeaksOnlyForTheLowEndItDefined() {
        val four = (0 until 4).map { entry(KIND_MASTURBATION, today.minusDays(it.toLong())) }

        val advice = sexualAdvice(four, adult, today)!!
        assertEquals("sexual-prostate", advice.id)
        assertEquals(AdviceLevel.HABIT, advice.level)
        assertEquals(21, advice.args[2])

        val five = four + entry(KIND_MASTURBATION, today.minusDays(4))
        assertNull("five said ${sexualAdvice(five, adult, today)?.id}", sexualAdvice(five, adult, today))

        val sixty = (0 until 60).map { entry(KIND_MASTURBATION, today.minusDays((it % 30).toLong())) }
        assertEquals("sexual-solo-frequency", sexualAdvice(sixty, adult, today)?.id)
        assertNull("fifty-nine said something", sexualAdvice(sixty.drop(1), adult, today))

        val painful = sixty + listOf(0L, 3L).map { act(today.minusDays(it), listOf(SexNote.PAIN)) }
        assertEquals("sexual-pain", sexualAdvice(painful, adult, today)?.id)
    }

    // --- energy -----------------------------------------------------------------------------------

    /**
     * Mifflin–St Jeor makes this profile 1,780 kcal at rest, a sedentary 2,136 a day, and the goal
     * shifts that to 1,636 to lose or 2,436 to gain. Landing exactly on the target the goal already
     * moved is meeting it, not missing it — and the same week cannot be both too much food and too
     * little, which is what a strict comparison on each side makes it.
     */
    @Test
    fun aWeekExactlyOnTheShiftedTargetIsOnTrack() {
        val loser = adult.copy(energyGoal = "LOSE")
        val gainer = adult.copy(energyGoal = "GAIN")
        val onTarget = { profile: UserProfile ->
            val target = energyBudget(profile, 2026).targetKcal
            energyAdvice(emptyList(), (1..7).map { meal(today.minusDays(it.toLong()), target) }, profile, today, 2026)
        }

        assertNull("losing said ${onTarget(loser)?.id}", onTarget(loser))
        assertNull("gaining said ${onTarget(gainer)?.id}", onTarget(gainer))
    }

    // --- the shapes the exports and the charts are built from --------------------------------------

    /**
     * The one thing standing between a clock-skewed row and an inverted record Health Connect would
     * refuse is that an interval must end after it starts. A duration below a tenth of a second is
     * floored to a whole one rather than published as a session of no length.
     */
    @Test
    fun aNegativeDurationOrASkewedClockIsNoInterval() {
        assertNull(session("RUN", -30.0).sessionInterval(now))
        assertNull(session("BLUETOOTH_FTMS", 30.0, at = now.plusSeconds(60)).sessionInterval(now))

        val (start, end) = session("RUN", 0.004).sessionInterval(now)!!

        assertEquals(loggedAt, end)
        assertEquals(1_000L, end.toEpochMilli() - start.toEpochMilli())
    }

    /**
     * An import marker is "Health Connect:<package>:<id>", and a key that is not one names no
     * record: [markedRecordId] is the reader that answers for identity, and it refuses anything
     * without the prefix or without an id behind it. [importOrigin] is only ever asked about rows
     * the importer itself wrote, so what it makes of a string that was never a marker is nothing
     * this test pins.
     */
    @Test
    fun aRecordKeyThatNamesNoRecordClaimsNothing() {
        val keyed = { key: String? -> session("HEALTH_CONNECT", 30.0).copy(healthConnectRecordKey = key) }

        assertNull(markedRecordId("Health Connect:"))
        assertNull(markedRecordId("Health Connect:com.example.watch"))
        assertNull(markedRecordId("com.example.watch:8f2c"))
        assertNull(markedRecordId(null))

        assertEquals("", keyed(null).importOrigin())
        assertEquals("", keyed("Health Connect:").importOrigin())
        assertEquals("com.example.watch", keyed("Health Connect:com.example.watch").importOrigin())
    }

    // --- what every engine owes the card ----------------------------------------------------------

    /**
     * Five resource ids are handed to the [Advice] constructor positionally, so a copy-pasted engine
     * can pass the wrong one, or a zero, without a compile error — and the home card draws the most
     * pressing statement first, which only holds while the list comes back sorted by level.
     */
    @Test
    fun everyAdviceNamesItselfAndTheBodyBehindIt() {
        val journal = nights(8, 5 * 60) +
            (0 until 7).map { moodRow(today.minusDays(it.toLong()), Mood.BAD) } +
            cycleStart(today.minusDays(120)) +
            (0 until 3).map { entry(KIND_STOOL, today.minusDays(it.toLong()), StoolKind.LOOSE.name) } +
            (0 until 6).map { entry(KIND_SMOKING, today.minusDays(it.toLong()), serving = 0.8) }

        val advice = allAdvice(steps(7, 2_000), journal, adult.copy(sex = "FEMALE"), today, 2026)

        assertTrue("only ${advice.map(Advice::topic)}", advice.map(Advice::topic).toSet().size >= 4)
        assertTrue(advice.all { it.titleRes != 0 && it.bodyRes != 0 && it.sourceRes != 0 })
        assertTrue(advice.all { it.id.isNotBlank() && it.windowDays > 0 })
        assertEquals(advice.size, advice.map(Advice::id).toSet().size)
        val levels = advice.map { it.level.ordinal }
        assertEquals("was $levels", levels.sortedDescending(), levels)
    }
}
