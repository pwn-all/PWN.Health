package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The changes over the same months: two stretches of the period side by side, marked only past the
 * app's own thresholds, the catalogue's pairs printed whichever way their figures moved, and the
 * diary before two blood results set beside them.
 *
 * Every case is one a doctor or the person would notice on paper: a pair printed only when it told
 * the expected story, a figure from a section the person kept off the sheet, a day a watch wrote
 * counted as a day the diary was kept, a word on the page that claims more than the diary can.
 *
 * The diaries the cases are built from are at the foot of the file.
 */
class ReportChangesTest {
    private fun checked(shown: Int) = res(
        R.string.report_changes_checked,
        ReportText.Quantity(R.plurals.report_changes_pair_count, ComparisonPair.entries.size),
        shown,
    )

    private fun kg(value: String) = res(R.string.report_value_unit, value, res(R.string.unit_kg))

    private fun daysOf(count: Int, of: Int) =
        res(R.string.report_count_of, count, ReportText.Quantity(R.plurals.count_days, of))

    /** A course started between the quarter's stretches and one ended then. */
    private fun courses() = listOf(
        Medication(id = 1, name = "Sertraline", startDay = "2026-07-20", createdAt = 0),
        Medication(id = 2, name = "Metformin", startDay = "2026-01-01", endDay = "2026-07-25", createdAt = 0),
    )

    /** A condition written down as starting between the quarter's stretches. */
    private fun asthma() = MedicalHistoryRow(
        key = "c",
        kind = HistoryKind.CONDITION.name,
        title = "Asthma",
        startDay = "2026-07-10",
        updatedAt = 0,
    )

    // --- the pairs --------------------------------------------------------------------------------

    /** The case the section was written for: heavier, fewer steps and more often wound up. */
    @Test
    fun heavierWithFewerStepsAndMoreOftenWoundUpPrintsTheFirstPairWithItsThirdItem() {
        val built = section(weights(80.0, 84.0) + tension(2, 12), steps(9_000, 6_000))!!

        val weight = built.row(res(R.string.report_changes_weight))!!
        val weightChange = res(R.string.report_changes_from_to, kg("80"), kg("84"))
        assertEquals(weightChange, weight.value)
        assertTrue(weight.marked())
        assertTrue(built.row(res(R.string.steps))!!.marked())
        assertTrue(built.row(res(R.string.report_changes_wound_up))!!.marked())
        assertEquals(listOf(R.string.report_changes_pair_weight_steps_wound_up), built.pairs())
        assertEquals(checked(1), built.row(res(R.string.report_changes_pairs))!!.value)
        // A mark is explained once, as the app's own threshold rather than a clinical one.
        assertNotNull(built.row(res(R.string.report_changes_changed_label)))
        assertEquals(R.string.report_changes_trailer, built.caveatRes)

        // Both changes, earlier → later, with the days behind each and the words for what the row is.
        val pair = built.rows.first { it.label == res(R.string.report_changes_pair) }
        val sentence = pair.value as ReportText.Res
        assertEquals(weightChange, sentence.args[0])
        assertEquals(3, sentence.args.size)
        assertTrue(res(R.string.report_changes_same_months) in (pair.note as ReportText.Join).parts)
        assertEquals(listOf(ComparisonPair.WEIGHT_STEPS.footnote(PROFILE.sex)), built.footnotes)
    }

    /** The direction is not chosen: heavier with more steps is the same pair, printed the same way. */
    @Test
    fun heavierWithMoreStepsIsPrintedToo() {
        val built = section(weights(80.0, 84.0), steps(6_000, 9_000))!!

        assertTrue(built.row(res(R.string.steps))!!.marked())
        assertEquals(listOf(R.string.report_changes_pair_weight_steps), built.pairs())
        assertEquals(checked(1), built.row(res(R.string.report_changes_pairs))!!.value)
    }

    /** Under the thresholds every figure still stands with both values, and nothing is paired. */
    @Test
    fun underTheThresholdsTheRowsStandWithoutAPair() {
        val built = section(weights(80.0, 81.0) + tension(2, 4), steps(9_000, 8_500))!!

        assertFalse(built.row(res(R.string.report_changes_weight))!!.marked())
        assertFalse(built.row(res(R.string.steps))!!.marked())
        assertFalse(built.row(res(R.string.report_changes_wound_up))!!.marked())
        assertEquals(emptyList<Int>(), built.pairs())
        assertEquals(checked(0), built.row(res(R.string.report_changes_pairs))!!.value)
        assertNull(built.row(res(R.string.report_changes_changed_label)))
        assertEquals(emptyList<ReportText>(), built.footnotes)
    }

    /** Weight and food energy that both changed are a pair of their own, after weight and steps. */
    @Test
    fun foodEnergyAndWeightAreThePairAfterWeightAndSteps() {
        val built = section(weights(80.0, 84.0) + meals(900.0, 1_200.0), steps(9_000, 6_000))!!

        assertEquals(
            listOf(R.string.report_changes_pair_weight_steps, R.string.report_changes_pair_food_weight),
            built.pairs(),
        )
        assertEquals(checked(2), built.row(res(R.string.report_changes_pairs))!!.value)
    }

    /** Every pair in the catalogue prints its sentence and its footnote once both of its figures move. */
    @Test
    fun everyPairInTheCatalogueIsPrintedWhenBothOfItsFiguresChange() {
        ComparisonPair.entries.forEach { pair ->
            val built = section(fixture(pair.exposure) + fixture(pair.outcome))!!

            assertTrue("${pair.key}: ${built.pairs()}", pair.sentenceRes in built.pairs())
            assertTrue(pair.key, pair.footnote(PROFILE.sex) in built.footnotes)
            val row = built.rows.first { (it.value as? ReportText.Res)?.id == pair.sentenceRes }
            assertEquals(pair.key, 2, (row.value as ReportText.Res).args.size)
        }
    }

    /** Either figure under its threshold, whichever it is, and the pair is silent. */
    @Test
    fun noPairIsPrintedWhileEitherOfItsFiguresStaysUnderItsThreshold() {
        ComparisonPair.entries.forEach { pair ->
            listOf(true to false, false to true).forEach { (exposureMoved, outcomeMoved) ->
                val built = section(fixture(pair.exposure, exposureMoved) + fixture(pair.outcome, outcomeMoved))!!

                assertFalse("${pair.key} $exposureMoved/$outcomeMoved", pair.sentenceRes in built.pairs())
                assertFalse(pair.key, pair.footnote(PROFILE.sex) in built.footnotes)
            }
        }
    }

    // --- the new figures --------------------------------------------------------------------------

    /** Sleep from fourteen nights in each stretch, marked from half an hour and 8% both. */
    @Test
    fun sleepIsComparedFromFourteenNightsAndMarkedFromHalfAnHourAndEightPerCent() {
        val label = res(R.string.sleep)
        fun marked(first: Int, last: Int, nights: Int = 31) =
            section(nights(first, last, nights), printed = setOf(ReportPart.SLEEP)).marked(label)

        assertEquals(true, marked(450, 410))
        assertEquals(false, marked(450, 425))
        // Thirty-five minutes off seven and a half hours is 7.8%: under the share.
        assertEquals(false, marked(450, 415))
        // Thirty minutes off six hours is 8.3%: both.
        assertEquals(true, marked(360, 330))
        assertNull(marked(450, 380, nights = 13))
        assertEquals(
            res(
                R.string.report_changes_from_to,
                res(R.string.report_changes_per_night, "7:30"),
                res(R.string.report_changes_per_night, "6:20"),
            ),
            section(nights(450, 380), printed = setOf(ReportPart.SLEEP))!!.row(label)!!.value,
        )
    }

    /** Caffeine over the days kept, from half of each stretch, marked from half again and 100 mg both. */
    @Test
    fun caffeineIsCountedOverTheDaysKeptAndMarkedFromHalfAgainAndAHundredMilligrams() {
        val label = res(R.string.caffeine_title)
        fun marked(first: Int, last: Int, days: Int = 31, extra: List<JournalEntry> = emptyList()) =
            section(cups(first, last, days) + extra, printed = setOf(ReportPart.NUTRITION)).marked(label)
        fun mg(value: Int) =
            res(R.string.report_per_day_rate, res(R.string.report_value_unit, value, res(R.string.unit_milligrams)))

        assertEquals(true, marked(200, 300))
        assertEquals(false, marked(100, 190))
        assertEquals(false, marked(300, 400))
        // Ten days of cups and nothing else written: not half of the stretch.
        assertNull(marked(200, 400, days = 10))
        // The same ten days in a diary kept every day: the other days are days without a cup.
        assertEquals(true, marked(200, 400, days = 10, extra = kept()))
        val built = section(cups(310, 310, days = 10) + kept(), printed = setOf(ReportPart.NUTRITION))!!
        assertEquals(res(R.string.report_changes_from_to, mg(100), mg(310)), built.row(label)!!.value)
    }

    /** A cuff's mean from six readings, marked from 5 mmHg systolic or 4 diastolic. */
    @Test
    fun bloodPressureIsComparedFromSixReadingsAndMarkedFromFiveOrFourMillimetres() {
        val label = res(R.string.report_changes_pressure)
        fun marked(first: Pair<Int, Int>, last: Pair<Int, Int>, readings: Int = 8) =
            section(pressures(first, last, readings), printed = setOf(ReportPart.PRESSURE)).marked(label)
        fun mmHg(value: String) = res(R.string.report_value_unit, value, res(R.string.vitals_unit_mmhg))

        assertEquals(true, marked(120 to 80, 125 to 80))
        assertEquals(true, marked(120 to 80, 121 to 84))
        assertEquals(false, marked(120 to 80, 124 to 83))
        assertNull(marked(120 to 80, 140 to 95, readings = 5))
        assertEquals(
            res(R.string.report_changes_from_to, mmHg("120/80"), mmHg("130/86")),
            section(pressures(120 to 80, 130 to 86), printed = setOf(ReportPart.PRESSURE))!!.row(label)!!.value,
        )
    }

    /** A pulse's mean from six readings, marked from five beats a minute. */
    @Test
    fun pulseIsComparedFromSixReadingsAndMarkedFromFiveBeats() {
        val label = res(R.string.report_changes_pulse)
        fun marked(first: Int, last: Int, readings: Int = 8) =
            section(pulses(first, last, readings), printed = setOf(ReportPart.PULSE)).marked(label)

        assertEquals(true, marked(60, 65))
        assertEquals(false, marked(60, 64))
        assertNull(marked(60, 80, readings = 5))
    }

    /** Sodium over full days that carry it on every meal, from fourteen, marked from a fifth and 500 mg. */
    @Test
    fun sodiumIsComparedFromFourteenFullDaysAndMarkedFromAFifthAndFiveHundredMilligrams() {
        val label = res(R.string.editor_sodium)
        fun marked(first: Double, last: Double, days: Int = 31) = section(
            mealsWith({ it.copy(sodiumMg = first) }, { it.copy(sodiumMg = last) }, days),
            printed = setOf(ReportPart.NUTRITION),
        ).marked(label)

        assertEquals(true, marked(1_000.0, 1_250.0))
        assertEquals(false, marked(1_000.0, 1_225.0))
        assertEquals(false, marked(1_500.0, 1_750.0))
        assertNull(marked(1_000.0, 2_000.0, days = 13))
        // A day with one meal that does not say is part of a day, and is not counted at all.
        val halfSaid = listOf(8L, 13L).flatMap { hour ->
            (FIRST_DAYS + LAST_DAYS).map { entry(it, "EAT", hour).copy(sodiumMg = 900.0.takeIf { hour == 8L }) }
        }
        assertNull(section(halfSaid, printed = setOf(ReportPart.NUTRITION))!!.row(label))
    }

    /** Sugar and the saturated share of energy from fourteen full days, both marked from a fifth. */
    @Test
    fun sugarAndSaturatedFatAreComparedFromFourteenFullDaysAndMarkedFromAFifth() {
        val sugar = res(R.string.editor_sugar)
        fun sugarMarked(first: Double, last: Double, days: Int = 31) = section(
            mealsWith({ it.copy(sugarG = first) }, { it.copy(sugarG = last) }, days),
            printed = setOf(ReportPart.NUTRITION),
        ).marked(sugar)
        assertEquals(true, sugarMarked(25.0, 30.0))
        assertEquals(false, sugarMarked(25.0, 29.5))
        assertNull(sugarMarked(25.0, 50.0, days = 13))

        val saturated = res(R.string.labs_diet_saturated)
        fun fat(first: Double, last: Double) = section(
            mealsWith(
                { it.copy(calories = 800.0, saturatedFatG = first) },
                { it.copy(calories = 800.0, saturatedFatG = last) },
            ),
            printed = setOf(ReportPart.NUTRITION),
        )
        // 8 g a meal on 800 kcal is 9% of energy; 9.6 g is 10.8%, a fifth more; 9.5 g is 10.7%.
        assertEquals(true, fat(8.0, 9.6).marked(saturated))
        assertEquals(false, fat(8.0, 9.5).marked(saturated))
        assertEquals(
            res(
                R.string.report_changes_from_to,
                res(R.string.report_changes_energy_share, "9"),
                res(R.string.report_changes_energy_share, "10.8"),
            ),
            fat(8.0, 9.6)!!.row(saturated)!!.value,
        )
    }

    /** Tobacco over the days kept, marked from half again and seven cigarette equivalents a week. */
    @Test
    fun tobaccoIsCountedOverTheDaysKeptAndMarkedFromHalfAgainAndSevenAWeek() {
        val label = res(R.string.smoking_title)
        fun marked(first: Int, last: Int, diary: List<JournalEntry> = kept()) =
            section(cigarettes(first, last) + diary, printed = setOf(ReportPart.SOCIAL)).marked(label)

        // Thirty-one more over 31 days is exactly seven more a week.
        assertEquals(true, marked(5, 36))
        assertEquals(false, marked(5, 35))
        // Seven more a week on top of nearly sixteen is under half again.
        assertEquals(false, marked(70, 101))
        // Five days with a cigarette and nothing else written: the first stretch is not described.
        assertNull(marked(5, 45, diary = LAST_DAYS.map(::glass)))
    }

    /** Nought to nought is no change, though a share of nought is nought. */
    @Test
    fun aFigureAtNoughtInBothStretchesIsNotMarked() {
        val built = section(meals(0.0, 0.0), printed = setOf(ReportPart.NUTRITION))!!

        assertFalse(built.row(ReportText.Quantity(R.plurals.report_changes_food, 2))!!.marked())
    }

    // --- what cannot be described -------------------------------------------------------------------

    /** A stretch with too few days is not compared, and the line says how few, stretch by stretch. */
    @Test
    fun aThinStretchIsNotComparedAndSaysHowThin() {
        val thinSteps = FIRST_DAYS.take(10).map { DaySteps(it.toString(), 9_000) } +
            LAST_DAYS.map { DaySteps(it.toString(), 6_000) }
        val built = section(weights(80.0, 84.0), thinSteps)!!

        assertNull(built.row(res(R.string.steps)))
        val left = built.row(res(R.string.report_changes_not_compared))!!
        val counts = res(R.string.report_changes_on_days, daysOf(10, 31), daysOf(31, 31))
        assertEquals(notCompared(res(R.string.steps), counts), left.value)
        assertEquals(res(R.string.report_changes_too_few), left.note)
        // Weight alone changed, so nothing is paired with it.
        assertEquals(emptyList<Int>(), built.pairs())
    }

    /** Nothing the sheet prints has anything in the period: there is no section. */
    @Test
    fun nothingPrintedWithDataIsNoSection() {
        assertNull(section(weights(80.0, 84.0), printed = setOf(ReportPart.PRESSURE)))
        assertNull(section(tension(2, 12), printed = setOf(ReportPart.WEIGHT)))
    }

    // --- privacy ----------------------------------------------------------------------------------

    /** Partnered activity only where the sexual section is printed and was sent with its dates. */
    @Test
    fun partneredActivityOnlyWithTheSectionSentWithItsDates() {
        val entries = acts(12, 2) + meals(900.0, 900.0)
        val label = res(R.string.report_changes_partnered)

        val detailed = section(entries)!!
        assertTrue(detailed.row(label)!!.marked())
        assertFalse(label in section(entries, detail = SexualDetail.SUMMARY)!!.texts())
        val unticked = ReportPart.entries.toSet() - ReportPart.SEXUAL_HEALTH
        assertFalse(label in section(entries, printed = unticked)!!.texts())
    }

    /**
     * The partnered count is over the days kept by hand and the days of acts, never the mornings: a
     * man who answers the morning check-in every day leaves its days by hand, its rate and its change
     * exactly as they were — the dated section does not bring the mornings.
     */
    @Test
    fun morningsLeaveThePartneredDaysByHandAsTheyWere() {
        val label = res(R.string.report_changes_partnered)
        val diary = acts(12, 12) + (FIRST_DAYS.take(20) + LAST_DAYS.take(20)).map(::glass)
        val mornings = (FIRST_DAYS + LAST_DAYS).map {
            entry(it, KIND_MORNING_CHECK, hour = 7).copy(
                measurementPayloadJson = """{"schema":1,"morning":"FIRM","wokeAt":0,"wake":"TYPED"}""",
            )
        }

        val answered = section(diary + mornings)!!
        assertEquals(section(diary)!!.row(label)!!, answered.row(label))
        assertTrue(res(R.string.report_changes_on_days_by_hand, daysOf(20, 31), daysOf(20, 31)) in answered.texts())
    }

    /** The three sexual pairs, their sentences and their footnotes, only with the section sent with its dates. */
    @Test
    fun theSexualPairsNeedTheSectionSentWithItsDates() {
        val sexual = ComparisonPair.entries.filter { ChangeDomain.PARTNERED in listOf(it.exposure, it.outcome) }
        assertEquals(
            setOf(ComparisonPair.WOUND_UP_PARTNERED, ComparisonPair.ALCOHOL_PARTNERED, ComparisonPair.SOLO_PARTNERED),
            sexual.toSet(),
        )
        val unticked = ReportPart.entries.toSet() - ReportPart.SEXUAL_HEALTH

        sexual.forEach { pair ->
            val diary = fixture(pair.exposure) + fixture(pair.outcome)
            assertTrue(pair.key, pair.sentenceRes in section(diary)!!.pairs())
            listOf(
                section(diary.entries, diary.days, detail = SexualDetail.SUMMARY),
                section(diary.entries, diary.days, printed = unticked),
            ).forEach { built ->
                assertFalse(pair.key, pair.sentenceRes in built?.pairs().orEmpty())
                assertFalse(pair.key, pair.footnote(PROFILE.sex) in built?.footnotes.orEmpty())
                assertFalse(pair.key, res(R.string.report_changes_partnered) in built?.texts().orEmpty())
                assertFalse(pair.key, res(R.string.report_changes_solo) in built?.texts().orEmpty())
            }
        }
    }

    /** Wound up and more often, with more acts: the second pair, alongside the first. */
    @Test
    fun woundUpAndPartneredActivityAreTheSecondPair() {
        val entries = weights(80.0, 84.0) + tension(2, 12) + acts(12, 2) + meals(900.0, 900.0)
        val built = section(entries, steps(9_000, 6_000))!!

        assertEquals(
            listOf(R.string.report_changes_pair_weight_steps_wound_up, R.string.report_changes_pair_wound_up_partnered),
            built.pairs(),
        )
    }

    /** Unticking mood takes the wound-up share and the mood mean with it, and out of the first pair. */
    @Test
    fun anUntickedMoodTakesTheWoundUpShareAndTheMoodWithIt() {
        fun changes(parts: Set<ReportPart>) = doctorReport(
            sources = ReportSources(PROFILE, weights(80.0, 84.0) + tension(2, 12), steps(9_000, 6_000)),
            request = ReportRequest(from = QUARTER, to = TODAY, parts = parts),
            today = TODAY,
            zone = ZONE,
        ).sections.first { it.part == ReportPart.CHANGES }
        val ticked = ReportRequest(from = QUARTER, to = TODAY).parts

        assertEquals(listOf(R.string.report_changes_pair_weight_steps_wound_up), changes(ticked).pairs())
        val without = changes(ticked - ReportPart.MOOD)
        assertEquals(listOf(R.string.report_changes_pair_weight_steps), without.pairs())
        assertFalse(res(R.string.report_changes_wound_up) in without.texts())
        assertFalse(res(R.string.report_mood_average) in without.texts())
    }

    /**
     * A day nothing but Health Connect wrote on is not a day the diary was kept: a drink count over
     * it would read every such day as a day without a drink.
     */
    @Test
    fun aHealthConnectOnlyDayIsNotADayWrittenByHand() {
        val drinks = FIRST_DAYS.take(10).map { drink(it, 20.0) } + LAST_DAYS.map { drink(it, 20.0) }
        val synced = FIRST_DAYS.drop(10).flatMap {
            listOf(
                entry(it, "MEASUREMENT", source = SOURCE_HEALTH_CONNECT).copy(measurementHeartRateBpm = 60),
                entry(it, "ACTIVITY", hour = 18).copy(healthConnectRecordKey = "com.watch:$it"),
            )
        }
        val built = section(drinks + synced, printed = setOf(ReportPart.SOCIAL))!!

        assertNull(built.row(res(R.string.alcohol_title)))
        val counts = res(R.string.report_changes_on_days_by_hand, daysOf(10, 31), daysOf(31, 31))
        val left = built.row(res(R.string.report_changes_not_compared))!!.value
        assertEquals(notCompared(res(R.string.alcohol_title), counts), left)

        // The same days typed in by hand are days the diary was kept, and the figure is compared.
        val typed = FIRST_DAYS.drop(10).map { glass(it) }
        assertNotNull(section(drinks + typed, printed = setOf(ReportPart.SOCIAL))!!.row(res(R.string.alcohol_title)))
    }

    /**
     * A day with nothing on it but a sexual act is not a day kept for the alcohol, whatever the sheet
     * ticks: a count of days beside the drinks would carry the days of sex onto the sheet.
     */
    @Test
    fun aDayWithOnlyASexualRowIsNotADayKeptForTheAlcohol() {
        val drinks = FIRST_DAYS.take(10).map { drink(it, 20.0) } + LAST_DAYS.map { drink(it, 20.0) }
        val acts = FIRST_DAYS.drop(10).map(::act)
        val counts = res(R.string.report_changes_on_days_by_hand, daysOf(10, 31), daysOf(31, 31))

        listOf(setOf(ReportPart.SOCIAL), ReportPart.entries.toSet()).forEach { printed ->
            val built = section(drinks + acts, printed = printed)!!
            assertNull(built.row(res(R.string.alcohol_title)))
            assertTrue(notCompared(res(R.string.alcohol_title), counts).parts.first() in built.texts())
        }
    }

    /** Nor is a day with nothing on it but a morning check-in: that it exists is sexual data too. */
    @Test
    fun aDayWithOnlyAMorningCheckIsNotADayKeptForTheAlcohol() {
        val drinks = FIRST_DAYS.take(10).map { drink(it, 20.0) } + LAST_DAYS.map { drink(it, 20.0) }
        val mornings = FIRST_DAYS.drop(10).map {
            entry(it, KIND_MORNING_CHECK, hour = 7).copy(
                measurementPayloadJson = """{"schema":1,"morning":"FIRM","wokeAt":0,"wake":"TYPED"}""",
            )
        }
        val counts = res(R.string.report_changes_on_days_by_hand, daysOf(10, 31), daysOf(31, 31))

        listOf(setOf(ReportPart.SOCIAL), ReportPart.entries.toSet()).forEach { printed ->
            val built = section(drinks + mornings, printed = printed)!!
            assertNull(built.row(res(R.string.alcohol_title)))
            assertTrue(notCompared(res(R.string.alcohol_title), counts).parts.first() in built.texts())
        }
    }

    // --- the stretches ----------------------------------------------------------------------------

    /** Thirds up to a year, a season line at a year, and the same days a year apart at two years. */
    @Test
    fun theStretchesFollowTheLengthOfThePeriod() {
        assertEquals(
            ChangeStretches(QUARTER..LocalDate.of(2026, 7, 5), LocalDate.of(2026, 8, 6)..TODAY, yearApart = false),
            changeStretches(QUARTER, TODAY),
        )
        assertFalse(changeStretches(TODAY.minusMonths(6), TODAY)!!.seasonsDiffer)
        assertTrue(changeStretches(TODAY.minusMonths(12), TODAY)!!.seasonsDiffer)
        val twoYears = changeStretches(TODAY.minusMonths(24), TODAY)!!
        assertEquals(
            ChangeStretches(LocalDate.of(2025, 5, 7)..LocalDate.of(2025, 9, 5), LocalDate.of(2026, 5, 7)..TODAY, true),
            twoYears,
        )
        assertFalse(twoYears.seasonsDiffer)
    }

    /** Two years compare the last 122 days with the same days a year earlier, and nothing between. */
    @Test
    fun twoYearsCompareTheSameDaysAYearApart() {
        val then = LocalDate.of(2025, 5, 7)
        val now = LocalDate.of(2026, 5, 7)
        val weighIns = listOf(0L, 30L, 60L, 90L).flatMap {
            listOf(weighIn(then.plusDays(it), 80.0), weighIn(now.plusDays(it), 84.0))
        } + weighIn(LocalDate.of(2025, 12, 1), 100.0)
        val built = section(weighIns, from = TODAY.minusMonths(24))!!

        val weight = built.row(res(R.string.report_changes_weight))!!
        assertEquals(res(R.string.report_changes_from_to, kg("80"), kg("84")), weight.value)
        assertEquals(res(R.string.report_changes_seasons_same), built.row(res(R.string.report_changes_seasons))!!.value)
        // One weigh-in at the start of the year: the stretches are cut from the first day on record.
        val year = section(weights(80.0, 84.0) + weighIn(TODAY.minusMonths(12), 80.0), from = TODAY.minusMonths(12))!!
        val seasons = year.row(res(R.string.report_changes_seasons))!!
        assertEquals(res(R.string.report_changes_seasons_differ), seasons.value)
        assertNull(section(weights(80.0, 84.0))!!.row(res(R.string.report_changes_seasons)))
    }

    /**
     * A 24-month sheet over a diary begun a year ago: the stretches are cut from the diary's first day,
     * so its first and last thirds are compared rather than an empty stretch from before it began.
     */
    @Test
    fun theStretchesStartWhereTheRecordDoes() {
        val start = TODAY.minusMonths(12)
        val weighIns = (0L..52L).map { week -> weighIn(start.plusDays(7 * week), if (week < 26) 80.0 else 86.0) }
        val built = section(weighIns, from = TODAY.minusMonths(24))!!

        val stretches = built.row(res(R.string.report_changes_stretches))!!.value as ReportText.Res
        assertEquals(ReportText.Day(start), stretches.args.first())
        assertEquals(
            res(R.string.report_changes_from_to, kg("80"), kg("86")),
            built.row(res(R.string.report_changes_weight))!!.value,
        )
        // A year's thirds are 121 days each and eight months apart: an autumn against a summer is said.
        assertEquals(
            res(R.string.report_changes_seasons_differ),
            built.row(res(R.string.report_changes_seasons))!!.value,
        )
    }

    /**
     * A laboratory sheet is copied in afterwards under the date of its draw, which can be long before
     * the diary was begun. One old result does not start the stretches in months that hold nothing
     * else: over a diary of nine months the 24-month sheet would compare a year back, and find no
     * weight.
     */
    @Test
    fun aLabSheetFromBeforeTheDiaryDoesNotStartTheStretches() {
        val start = TODAY.minusMonths(9)
        val weighIns = (0L..39L).map { week -> weighIn(start.plusDays(7 * week), if (week < 20) 80.0 else 86.0) }
        val old = panel(TODAY.minusMonths(20), mapOf(LabMarker.GGT to 22.0), id = 1)
        val built = section(weighIns + old, from = TODAY.minusMonths(24))!!

        val stretches = built.row(res(R.string.report_changes_stretches))!!.value as ReportText.Res
        assertEquals(ReportText.Day(start), stretches.args.first())
        assertEquals(
            res(R.string.report_changes_from_to, kg("80"), kg("86")),
            built.row(res(R.string.report_changes_weight))!!.value,
        )
    }

    // --- between the stretches --------------------------------------------------------------------

    /** Courses and conditions dated between the stretches, only where their own sections are printed. */
    @Test
    fun whatBeganBetweenTheStretchesFollowsItsOwnSections() {
        fun between(printed: Set<ReportPart>) =
            section(weights(80.0, 84.0), printed = printed, medications = courses(), history = listOf(asthma()))!!
                .row(res(R.string.report_changes_between))?.value
        fun named(name: String, day: String) =
            res(R.string.report_changes_item, ReportText.Plain(name), ReportText.Day(LocalDate.parse(day)))

        val expected = ReportText.Join(
            listOf(
                res(R.string.report_changes_started, ReportText.Join(listOf(named("Sertraline", "2026-07-20")), ", ")),
                res(R.string.report_changes_ended, ReportText.Join(listOf(named("Metformin", "2026-07-25")), ", ")),
                res(R.string.report_changes_conditions, ReportText.Join(listOf(named("Asthma", "2026-07-10")), ", ")),
            ),
            "; ",
        )
        assertEquals(expected, between(setOf(ReportPart.WEIGHT, ReportPart.MEDICATIONS, ReportPart.PROBLEMS)))
        assertNull(between(setOf(ReportPart.WEIGHT)))
    }

    // --- two blood results ------------------------------------------------------------------------

    /** "Alcohol in the 56 days before each result: 10 → 48 g a week; GGT 22 → 41 U/L (1 Mar → 20 Aug)". */
    @Test
    fun aBloodResultBesideAnExposureThatMovedPrintsBothPairsOfFigures() {
        val built = section(bloodDiary(LabComparison.ALCOHOL_LIVER_ENZYMES))!!
        fun grams(g: Int) =
            res(R.string.report_changes_per_week, res(R.string.report_value_unit, g, res(R.string.unit_grams)))
        fun units(value: String) = res(R.string.report_value_unit, value, res(R.string.labs_unit_ul))

        val row = built.bloodRows().single()
        val alcohol = res(R.string.alcohol_title)
        val window = res(R.string.report_changes_blood_window, alcohol, ReportText.Quantity(R.plurals.count_days, 56))
        assertEquals(window, row.label)
        val value = row.value as ReportText.Res
        assertEquals(res(R.string.report_changes_from_to, grams(10), grams(48)), value.args[0])
        assertEquals(res(LabMarker.GGT.titleRes), value.args[1])
        assertEquals(res(R.string.report_changes_from_to, units("22"), units("41")), value.args[2])
        assertEquals(listOf(ReportText.Day(DRAW_BEFORE), ReportText.Day(DRAW_AFTER)), value.args.drop(3))
        assertTrue(LabComparison.ALCOHOL_LIVER_ENZYMES.footnote in built.footnotes)
        val checkedBlood = res(
            R.string.report_changes_checked,
            ReportText.Quantity(R.plurals.report_changes_pair_count, LabComparison.entries.sumOf { it.markers.size }),
            1,
        )
        assertEquals(checkedBlood, built.row(res(R.string.report_changes_blood))!!.value)
    }

    /** Every blood comparison in the catalogue can be reached, and prints its footnote. */
    @Test
    fun everyBloodComparisonInTheCatalogueIsPrintedWhenBothMove() {
        LabComparison.entries.forEach { comparison ->
            val built = section(bloodDiary(comparison))!!

            assertEquals(comparison.key, 1, built.bloodRows().size)
            assertTrue(comparison.key, comparison.footnote in built.footnotes)
        }
    }

    /**
     * HDL, second in the alcohol's lipids after the triglycerides, is read over its own window: the 56
     * days a marker without a food window gets, not the triglycerides' 28.
     */
    @Test
    fun hdlIsReadBesideTheAlcoholOverItsOwnFiftySixDays() {
        val built = section(ChangesDiary(panels(LabMarker.HDL, 1.2, 1.5)) + beforeDraws(ChangeDomain.ALCOHOL, 56))!!

        val row = built.bloodRows().single()
        val alcohol = res(R.string.alcohol_title)
        val window = res(R.string.report_changes_blood_window, alcohol, ReportText.Quantity(R.plurals.count_days, 56))
        assertEquals(window, row.label)
        assertEquals(res(LabMarker.HDL.titleRes), (row.value as ReportText.Res).args[1])
        assertTrue(LabComparison.ALCOHOL_LIPIDS.footnote in built.footnotes)
    }

    /** A window before either draw the diary cannot describe is refused with that draw's date, and only its. */
    @Test
    fun aWindowTheDiaryCannotDescribeRefusesWithItsDate() {
        fun refusal(draw: LocalDate) = res(R.string.report_changes_blood_not_recorded, ReportText.Day(draw))
        listOf(
            Triple(false, true, listOf(DRAW_BEFORE)),
            Triple(true, false, listOf(DRAW_AFTER)),
            Triple(false, false, listOf(DRAW_BEFORE, DRAW_AFTER)),
        ).forEach { (keptBefore, keptAfter, named) ->
            val diary = bloodDiary(LabComparison.ALCOHOL_LIVER_ENZYMES, keptBefore, keptAfter)
            val built = section(diary)!!

            assertEquals(named.toString(), emptyList<ReportRow>(), built.bloodRows())
            val refused = built.rows.single { it.value == ReportText.Join(named.map(::refusal), "; ") }
            assertTrue(res(LabMarker.GGT.titleRes) in (refused.note as ReportText.Join).parts)
            assertFalse(LabComparison.ALCOHOL_LIVER_ENZYMES.footnote in built.footnotes)
        }
    }

    /**
     * Two results that both predate the period set nothing beside them — not a row and not a
     * refusal: the diary before them is months the person did not choose to share. The blood-test
     * context keeps the same rule.
     */
    @Test
    fun twoResultsBeforeThePeriodSetNoDiaryBesideThem() {
        val before = LocalDate.of(2024, 3, 12) to LocalDate.of(2025, 3, 1)
        val diary = bloodDiary(LabComparison.ALCOHOL_LIVER_ENZYMES, draws = before)

        assertNull(section(diary))
        val built = section(diary + ChangesDiary(weights(80.0, 84.0)))!!
        assertEquals(emptyList<ReportRow>(), built.bloodRows())
        assertNull(built.row(res(R.string.report_changes_blood)))
        assertFalse(built.texts().any { (it as? ReportText.Res)?.id == R.string.report_changes_blood_not_recorded })
        assertFalse(res(R.string.alcohol_title) in built.texts())
    }

    /** Draws closer together than their window share days, and are not compared: GGT's is 56 days. */
    @Test
    fun drawsWhoseWindowsOverlapAreNotCompared() {
        fun built(apart: Long) = section(
            bloodDiary(LabComparison.ALCOHOL_LIVER_ENZYMES, draws = DRAW_AFTER.minusDays(apart) to DRAW_AFTER),
        )!!

        val overlapping = built(55)
        assertEquals(emptyList<ReportRow>(), overlapping.bloodRows())
        assertFalse(
            overlapping.texts().any { (it as? ReportText.Res)?.id == R.string.report_changes_blood_not_recorded },
        )
        assertEquals(1, built(56).bloodRows().size)
    }

    /** Two results that print the same, or an exposure that stayed put, and nothing is said. */
    @Test
    fun sameResultsOrASteadyExposurePrintNoBloodRow() {
        val same = ChangesDiary(panels(LabMarker.GGT, 22.0, 22.0)) + beforeDraws(ChangeDomain.ALCOHOL, 56)
        assertEquals(emptyList<ReportRow>(), section(same)?.bloodRows().orEmpty())

        val everyFortnight = listOf(DRAW_BEFORE, DRAW_AFTER).flatMap { draw ->
            window(draw, 56).filterIndexed { i, _ -> i % 14 == 0 }.map { drink(it, 20.0) } +
                window(draw, 56).map(::glass)
        }
        val built = section(ChangesDiary(panels(LabMarker.GGT, 22.0, 41.0) + everyFortnight))!!
        assertEquals(emptyList<ReportRow>(), built.bloodRows())
        assertFalse(built.texts().any { (it as? ReportText.Res)?.id == R.string.report_changes_blood_not_recorded })
    }

    /** The blood rows wait for the blood tests and for the section the exposure is read from. */
    @Test
    fun bloodRowsNeedTheBloodTestsAndTheExposuresSection() {
        val diary = bloodDiary(LabComparison.ALCOHOL_LIVER_ENZYMES)
        val all = ReportPart.entries.toSet()

        listOf(all - ReportPart.LABS, all - ReportPart.SOCIAL).forEach { printed ->
            val built = section(diary, printed)
            assertEquals(emptyList<ReportRow>(), built?.bloodRows().orEmpty())
            assertNull(built?.row(res(R.string.report_changes_blood)))
        }
    }

    // --- the arithmetic ---------------------------------------------------------------------------

    /**
     * 12 acts against 2 over equal days: under no change the second count is binomial(14, ½), and
     * P(X ≤ 2) = 106/16384 ≈ 0.0065 — outside. 8 against 5: P(X ≤ 5) ≈ 0.29 — inside.
     */
    @Test
    fun theRateRatioTestIsTheExactBinomialOne() {
        assertTrue(poissonRatioExcludesOne(12, 31, 2, 31))
        assertTrue(poissonRatioExcludesOne(2, 31, 12, 31))
        assertFalse(poissonRatioExcludesOne(8, 31, 5, 31))
    }

    // --- the words --------------------------------------------------------------------------------

    /**
     * Every string the section can put on the page, footnotes included, read out of the English
     * resources, is free of the words the sheet may never use about a change — cause, verdict,
     * prediction, advice — and every string written for the section is one it prints.
     *
     * Matched as whole words or word stems: "Checked on every report" is the count of pairs looked at,
     * not the imperative "check" the rule forbids.
     */
    @Test
    fun everyStringTheSectionPrintsIsFreeOfTheForbiddenWords() {
        val emitted = scenarios().flatMap { section ->
            listOfNotNull(section.part.titleRes, section.part.bodyRes, section.caveatRes) +
                section.texts().mapNotNull {
                    when (it) {
                        is ReportText.Res -> it.id
                        is ReportText.Quantity -> it.id
                        else -> null
                    }
                }
        }.toSet().map(::resourceName).toSet()
        val english = englishResources()

        emitted.forEach { name ->
            val text = english[name] ?: error("$name is not in values/")
            FORBIDDEN.forEach { word -> assertFalse("$name says “$text”", word.containsMatchIn(text)) }
        }
        val written = english.keys.filter { it.startsWith("report_changes_") }.toSet()
        assertEquals("written for the section and never printed", emptySet<String>(), written - emitted)
    }

    /**
     * Sheets that between them print every row the section has, every pair and every footnote — a
     * woman's sheet among them, for the footnotes that differ by profile.
     */
    private fun scenarios(): List<ReportSection> {
        val both = FIRST_DAYS + LAST_DAYS
        val everything = weights(80.0, 84.0) + tension(2, 12) + acts(12, 2) + meals(900.0, 1_200.0) +
            both.take(50).map(::glass) + both.filterIndexed { i, _ -> i % 8 == 0 }.map { drink(it, 20.0) }
        val female = PROFILE.copy(sex = "FEMALE")
        val pairs = ComparisonPair.entries.flatMap { pair ->
            val diary = fixture(pair.exposure) + fixture(pair.outcome)
            listOf(section(diary.entries, diary.days), section(diary.entries, diary.days, profile = female))
        }
        val blood = LabComparison.entries.map { section(bloodDiary(it)) } +
            listOf(false to true, true to false, false to false).map { (keptBefore, keptAfter) ->
                section(bloodDiary(LabComparison.ALCOHOL_LIVER_ENZYMES, keptBefore, keptAfter))
            }
        return listOfNotNull(
            section(everything, steps(9_000, 6_000), medications = courses(), history = listOf(asthma())),
            section(weights(80.0, 84.0), steps(9_000, 6_000)),
            section(weights(80.0, 84.0) + tension(0, 1), FIRST_DAYS.take(10).map { DaySteps(it.toString(), 9_000) }),
            section(weights(80.0, 84.0) + weighIn(TODAY.minusMonths(12), 80.0), from = TODAY.minusMonths(12)),
            section(weights(80.0, 84.0) + weighIn(TODAY.minusMonths(24), 80.0), from = TODAY.minusMonths(24)),
            section(ChangeDomain.entries.fold(ChangesDiary()) { all, domain -> all + fixture(domain) }),
            section(cups(200, 400, days = 10)),
            section(pulses(62, 70, lastSource = SOURCE_HEALTH_CONNECT)),
        ) + pairs.filterNotNull() + blood.filterNotNull()
    }

    private fun resourceName(id: Int): String =
        listOf(R.string::class.java, R.plurals::class.java).flatMap { it.fields.toList() }
            .first { it.getInt(null) == id }.name

    /** Every string and plural in the English resources, by name; a plural as all its forms together. */
    private fun englishResources(): Map<String, String> =
        File("src/main/res/values").listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
            RESOURCE.findAll(file.readText()).map { it.groupValues[2] to it.groupValues[3] }.toList()
        }.toMap()

    private companion object {
        val RESOURCE = Regex("""<(string|plurals) name="([a-z0-9_]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)

        /** The house list of words the sheet never prints about these figures. */
        val FORBIDDEN = listOf(
            "because", "due to", "caus", "led to", "resulted in", "improv", "worsen", "better", "worse",
            "help", "harm", "effect", "impact", "linked to", "associat", "risk", "predict", "likely",
            "suggest", "indicat", "significan", "abnormal", "deficien", "depress", "stress", "libido",
            "dehydrat", "fast food", "junk", "unhealthy", "consider", "check\\b", "retest", "get tested",
            "reduc", "increas", "stop", "ask about", "anxi",
        ).map { Regex("\\b$it", RegexOption.IGNORE_CASE) }
    }
}

/**
 * How the figures were recorded, set beside the changes: readings bunched into an evening, a diary
 * kept more fully in one stretch than the other, a source that changed between them, one change
 * named in several pairs, and a food diary that thinned.
 */
class ReportChangesRecordingTest {
    /**
     * Six readings taken over one evening or two are not a stretch's mean: they must fall on three
     * days, and the line that leaves the figure out says how many days they fell on.
     */
    @Test
    fun bloodPressureAndPulseNeedTheirReadingsOnThreeDays() {
        val label = res(R.string.report_changes_pressure)
        val printed = setOf(ReportPart.PRESSURE)

        // Six readings in the first stretch, an hour apart over so many days; eight three days apart in the last.
        fun cuffs(days: Int) = (0 until 6).map { cuff(FIRST_DAYS[it % days], 120 to 80, hour = 8L + it) } +
            (0 until 8).map { cuff(LAST_DAYS[it * 3], 130 to 86) }
        fun readings(count: Int, days: Int) = res(
            R.string.report_changes_over,
            ReportText.Quantity(R.plurals.count_readings, count),
            ReportText.Quantity(R.plurals.count_days, days),
        )

        listOf(1, 2).forEach { days ->
            val built = section(cuffs(days), printed = printed)!!
            assertNull(built.row(label))
            val counts = res(R.string.report_changes_both, readings(6, days), readings(8, 8))
            assertEquals(notCompared(label, counts), built.row(res(R.string.report_changes_not_compared))!!.value)
        }
        // Two readings on each of three days.
        assertNotNull(section(cuffs(3), printed = printed)!!.row(label))
        val twoDays = (0 until 6).map { pulse(FIRST_DAYS[it % 2], 60, hour = 8L + it) } +
            (0 until 8).map { pulse(LAST_DAYS[it * 3], 70) }
        assertNull(section(twoDays, printed = setOf(ReportPart.PULSE))!!.row(res(R.string.report_changes_pulse)))
    }

    /**
     * A pulse typed in the first stretch and received through Health Connect in the last says so on
     * its row, beside the mark it does not move; one source throughout says nothing.
     */
    @Test
    fun aSourceThatChangedBetweenTheStretchesIsNotedOnTheRow() {
        val label = res(R.string.report_changes_pulse)
        val printed = setOf(ReportPart.PULSE)
        fun named(id: Int) = ReportText.Join(listOf(res(id)), ", ")
        val note = res(
            R.string.report_changes_sources,
            res(
                R.string.report_changes_from_to,
                named(R.string.provenance_manual),
                named(R.string.provenance_health_connect),
            ),
        )

        val switched = section(pulses(62, 70, lastSource = SOURCE_HEALTH_CONNECT), printed = printed)!!.row(label)!!
        assertTrue(note in (switched.note as ReportText.Join).parts)
        assertTrue(switched.marked())
        val same = section(pulses(62, 70), printed = printed)!!
        assertFalse(same.texts().any { (it as? ReportText.Res)?.id == R.string.report_changes_sources })
    }

    /**
     * 140 g in each stretch, kept every day of the first and on sixteen days of the last: by the days
     * kept that is 32 → 61 g a week, over every day of the stretch it is the same, so it is not marked.
     */
    @Test
    fun theSameAmountOverAStretchKeptMoreFullyIsComparedAndNotMarked() {
        val label = res(R.string.alcohol_title)
        val drinks = (FIRST_DAYS.take(7) + LAST_DAYS.take(7)).map { drink(it, 20.0) }
        val kept = FIRST_DAYS.map(::glass) + LAST_DAYS.take(16).map(::glass)
        val built = section(drinks + kept, printed = setOf(ReportPart.SOCIAL))!!
        fun grams(g: Int) =
            res(R.string.report_changes_per_week, res(R.string.report_value_unit, g, res(R.string.unit_grams)))

        assertEquals(res(R.string.report_changes_from_to, grams(32), grams(61)), built.row(label)!!.value)
        assertFalse(built.row(label)!!.marked())
    }

    /** Food energy a day, with the entries a counted day held printed on its own row and nowhere else. */
    @Test
    fun foodEnergyPrintsTheEntriesADayBesideItsDays() {
        val label = ReportText.Quantity(R.plurals.report_changes_food, LAB_DIET_MIN_MEALS_PER_DAY)
        val four = listOf(8L, 11L, 13L, 18L).flatMap { hour ->
            FIRST_DAYS.map { entry(it, "EAT", hour).copy(calories = 500.0) }
        }
        val two = listOf(8L, 13L).flatMap { hour -> LAST_DAYS.map { entry(it, "EAT", hour).copy(calories = 1_250.0) } }
        val built = section(four + two + weights(80.0, 84.0))!!
        fun kcal(value: Int) =
            res(R.string.report_per_day_rate, res(R.string.report_value_unit, value, res(R.string.unit_kcal)))
        val entries = res(R.string.report_changes_entries_a_day, res(R.string.report_changes_from_to, "4", "2"))

        assertEquals(res(R.string.report_changes_from_to, kcal(2_000), kcal(2_500)), built.row(label)!!.value)
        assertTrue(entries in built.row(label)!!.note!!.flattened())
        // The pair names the figure with its days, and without the entries.
        assertEquals(listOf(R.string.report_changes_pair_food_weight), built.pairs())
        assertEquals(1, built.texts().count { it == entries })
    }

    /**
     * Two pairs that both name the weight say under their count that one change can sit in more than
     * one pair, and so do two blood rows beside one change in the alcohol; one pair says nothing.
     */
    @Test
    fun pairsThatShareAFigureSaySoUnderTheirCount() {
        val shared = res(R.string.report_changes_pairs_shared)
        val two = section(weights(80.0, 84.0) + meals(900.0, 1_200.0), steps(9_000, 6_000))!!
        assertEquals(shared, two.row(res(R.string.report_changes_pairs))!!.note)
        assertNull(section(weights(80.0, 84.0), steps(6_000, 9_000))!!.row(res(R.string.report_changes_pairs))!!.note)

        val liver = listOf(
            panel(DRAW_BEFORE, mapOf(LabMarker.GGT to 22.0, LabMarker.ALT to 20.0), id = 1),
            panel(DRAW_AFTER, mapOf(LabMarker.GGT to 41.0, LabMarker.ALT to 40.0), id = 2),
        )
        val blood = section(ChangesDiary(liver) + beforeDraws(ChangeDomain.ALCOHOL, 56))!!
        assertEquals(2, blood.bloodRows().size)
        assertEquals(shared, blood.row(res(R.string.report_changes_blood))!!.note)
    }
}

/**
 * The edges of the section's own rules: a threshold met exactly, a figure moved off nought, a window
 * before a draw that holds exactly its share of days, two stretches most of a year apart, the day the
 * record begins and what began inside a stretch.
 */
class ReportChangesEdgesTest {
    private val nutrition = setOf(ReportPart.NUTRITION)

    /**
     * From a stretch at nought a fifth is nothing, so with no floor food energy, sugar and the saturated
     * share would mark any change at all — "0 g a day → 0.0 g a day · changed". Each has a floor, as
     * sodium has: a hundred kilocalories, four grams, a point of the day's energy.
     */
    @Test
    fun aFoodFigureMovedOffNoughtIsMarkedOnlyPastItsFloor() {
        val food = ReportText.Quantity(R.plurals.report_changes_food, 2)
        assertEquals(false, section(meals(0.0, 40.0), printed = nutrition).marked(food))
        assertEquals(true, section(meals(0.0, 50.0), printed = nutrition).marked(food))

        val sugar = res(R.string.editor_sugar)
        fun sugarFromNought(last: Double) =
            section(mealsWith({ it.copy(sugarG = 0.0) }, { it.copy(sugarG = last) }), printed = nutrition)
        assertEquals(false, sugarFromNought(0.02).marked(sugar))
        assertEquals(true, sugarFromNought(2.0).marked(sugar))

        val saturated = res(R.string.labs_diet_saturated)
        fun fatFromNought(last: Double) = section(
            mealsWith(
                { it.copy(calories = 800.0, saturatedFatG = 0.0) },
                { it.copy(calories = 800.0, saturatedFatG = last) },
            ),
            printed = nutrition,
        )
        // 0.4 g a meal on 800 kcal is 0.45% of the day's energy; 0.9 g is 1.01%.
        assertEquals(false, fatFromNought(0.4).marked(saturated))
        assertEquals(true, fatFromNought(0.9).marked(saturated))
    }

    /**
     * A sexual row no printed figure reads does not start the stretches, whose dates are printed: the
     * day a diary of sex was begun would stand as the day the record began on a sheet that left that
     * section off. Nor does a planned workout, put on a day ahead of it.
     */
    @Test
    fun aSexualRowNoFigureReadsDoesNotStartTheStretches() {
        val early = TODAY.minusMonths(5)
        val diary = weights(80.0, 84.0) + act(early) + entry(early.plusDays(1), KIND_PLAN)
        fun firstDay(printed: Set<ReportPart>) =
            (
                section(diary, printed = printed, from = TODAY.minusMonths(6))!!
                    .row(res(R.string.report_changes_stretches))!!.value as ReportText.Res
                ).args.first()

        assertEquals(ReportText.Day(QUARTER), firstDay(setOf(ReportPart.WEIGHT)))
        assertEquals(ReportText.Day(early), firstDay(setOf(ReportPart.WEIGHT, ReportPart.SEXUAL_HEALTH)))
    }

    /**
     * A course begun late in the first stretch or early in the last can be what moved a figure, and is
     * named beside the change as one begun between them is; one begun before both and taken through
     * both is not.
     */
    @Test
    fun whatBeganInsideAStretchIsNamedBesideTheChange() {
        val courses = listOf(
            Medication(id = 1, name = "Sertraline", startDay = "2026-08-10", createdAt = 0),
            Medication(id = 2, name = "Metformin", startDay = "2026-06-28", createdAt = 0),
            Medication(id = 3, name = "Ramipril", startDay = "2026-05-01", createdAt = 0),
        )
        fun named(name: String, day: String) =
            res(R.string.report_changes_item, ReportText.Plain(name), ReportText.Day(LocalDate.parse(day)))
        val built = section(
            weights(80.0, 84.0),
            printed = setOf(ReportPart.WEIGHT, ReportPart.MEDICATIONS),
            medications = courses,
        )!!

        val started = ReportText.Join(listOf(named("Metformin", "2026-06-28"), named("Sertraline", "2026-08-10")), ", ")
        assertEquals(
            ReportText.Join(listOf(res(R.string.report_changes_started, started)), "; "),
            built.row(res(R.string.report_changes_between))?.value,
        )
    }

    /**
     * Twenty points is the threshold and exactly twenty points is over it: 3 of 14 days answered
     * against 1 of 70 is 21.43% against 1.43%, and the interval for the difference leaves out nought.
     * Compared against 0.2 as a double the cross-product, 196, would fall 3e-14 short and leave the
     * row unmarked.
     */
    @Test
    fun aShareThatMovedByExactlyTwentyPointsIsMarked() {
        val from = TODAY.minusMonths(12)
        val answers = (0L until 14L).map { answer(from.plusDays(it), if (it < 3) 1 else 4) } +
            (0L until 70L).map { answer(TODAY.minusDays(it), if (it < 1) 1 else 4) }

        val built = section(answers, printed = setOf(ReportPart.MOOD), from = from)!!

        assertTrue(built.row(res(R.string.report_changes_wound_up))!!.marked())
    }

    /**
     * The blood-test context and this section stand on one sheet, and both ask the diary for the same
     * share of the window before a draw. A window the one describes the other must not call "not
     * recorded": sixteen full days of the triglycerides' twenty-eight would be 60% there, rounded
     * down, and short of 16.8 here.
     */
    @Test
    fun aWindowTheBloodTestContextDescribesIsComparedHere() {
        val printed = setOf(ReportPart.LABS, ChangeDomain.SUGAR.part)
        (14..28).forEach { days ->
            val meals = twoMeals(window(DRAW_BEFORE, 28)) { it.copy(sugarG = 20.0) } +
                twoMeals(window(DRAW_AFTER, 28).take(days)) { it.copy(sugarG = 30.0) }
            val diary = panels(LabMarker.TRIGLYCERIDES, 1.2, 2.0) + meals
            val later = labHistory(diary, PROFILE).getValue(LabMarker.TRIGLYCERIDES).last()

            val described = foodBeforeDraw(diary, later)!!.enough
            val built = section(diary, printed = printed)!!

            assertEquals("$days days", if (described) 1 else 0, built.bloodRows().size)
            assertEquals(
                "$days days",
                !described,
                built.texts().any { (it as? ReportText.Res)?.id == R.string.report_changes_blood_not_recorded },
            )
        }
    }

    /**
     * A 24-month sheet over a diary of sixteen months: the thirds are 324 days apart, and 121 of the
     * last one's 162 days are days of the year the first one holds too. That is neither two seasons
     * nor the same days a year apart, and the sheet claims neither; a year's thirds, a day of the
     * year in common between them, still say they fall in different seasons.
     */
    @Test
    fun stretchesMostOfAYearApartAreNotSaidToFallInDifferentSeasons() {
        val sixteenMonths = changeStretches(TODAY.minusDays(485), TODAY)!!
        assertFalse(sixteenMonths.yearApart)
        assertFalse(sixteenMonths.seasonsDiffer)
        // Half the days in common is where the line stops: 73 of 146 over 438 days, 72 of 145 over 437.
        assertFalse(changeStretches(TODAY.minusDays(437), TODAY)!!.seasonsDiffer)
        assertTrue(changeStretches(TODAY.minusDays(436), TODAY)!!.seasonsDiffer)
        assertTrue(changeStretches(TODAY.minusDays(365), TODAY)!!.seasonsDiffer)
        listOf(366L, 420L).forEach { assertTrue("$it", changeStretches(TODAY.minusDays(it), TODAY)!!.seasonsDiffer) }
        // From 486 days back the last 122 have the same days a year earlier on record: compared a year
        // apart, and so no season to allow for.
        listOf(486L, 487L).forEach { back ->
            val stretches = changeStretches(TODAY.minusDays(back), TODAY)!!
            assertTrue("$back", stretches.yearApart && !stretches.seasonsDiffer)
        }

        val weighIns = weights(80.0, 84.0) + weighIn(TODAY.minusDays(485), 80.0)
        val built = section(weighIns, from = TODAY.minusMonths(24))!!
        assertNull(built.row(res(R.string.report_changes_seasons)))
    }
}

/**
 * The figures the pain map, the work timer and the morning check-in bring: each read the way its own
 * section reads it, and each only where that section may go.
 */
class ReportChangesPatternsTest {
    private fun daysOf(count: Int, of: Int) =
        res(R.string.report_count_of, count, ReportText.Quantity(R.plurals.count_days, of))

    /**
     * The mornings are a figure only where their own section is printed, and only on a man's sheet —
     * whatever the ticks say — and they do not wait for the sexual section's dates: that section's
     * depth says nothing about them.
     */
    @Test
    fun theMorningsNeedTheirOwnSectionAndAMansSheet() {
        val pair = ComparisonPair.SLEEP_MORNING
        val diary = fixture(pair.exposure) + fixture(pair.outcome)
        val label = res(R.string.report_changes_morning)

        val his = section(diary.entries, diary.days, detail = SexualDetail.SUMMARY)!!
        assertTrue(his.row(label)!!.marked())
        assertTrue(pair.sentenceRes in his.pairs())
        val unticked = section(
            diary.entries,
            diary.days,
            printed =
            ReportPart.entries.toSet() - ReportPart.MORNING_ERECTIONS,
        )!!
        assertFalse(label in unticked.texts())
        val hers = section(diary.entries, diary.days, profile = PROFILE.copy(sex = "FEMALE"))!!
        assertFalse(label in hers.texts())
        assertFalse(pair.footnote("FEMALE") in hers.footnotes)
    }

    /**
     * Head or neck pain is a share of the days answered, "no pain today" among them, and a day with
     * nothing on it is left out rather than read as a day without pain.
     */
    @Test
    fun headOrNeckPainIsAShareOfTheDaysAnswered() {
        val label = res(R.string.report_changes_head_neck_pain)
        fun answered(count: Int, of: Int) =
            res(R.string.report_count_of, count, ReportText.Quantity(R.plurals.report_days_answered, of))

        val built = section(headaches(2, 12), printed = setOf(ReportPart.PAIN))!!
        assertEquals(res(R.string.report_changes_from_to, answered(2, 20), answered(12, 20)), built.row(label)!!.value)
        assertTrue(built.row(label)!!.marked())
        // A knee is not the head or the neck: the same days with the pain lower down are no change.
        val knees = headaches(2, 12).map { row ->
            row.copy(measurementPayloadJson = row.measurementPayloadJson!!.replace("HEAD_FRONT:C", "KNEE:L"))
        }
        assertEquals(
            res(R.string.report_changes_from_to, answered(0, 20), answered(0, 20)),
            section(knees, printed = setOf(ReportPart.PAIN))!!.row(label)!!.value,
        )
        // Thirteen days answered in a stretch are not enough to describe it.
        assertNull(section(headaches(2, 12, days = 13), printed = setOf(ReportPart.PAIN))!!.row(label))
    }

    /**
     * Net hours a week over the counted weeks, a week counted back from the stretch's end, and marked
     * from a fifth and five hours both; a day with nothing on it but the work timer is not a day kept
     * for the alcohol, so more work days do not thin the drinks out.
     */
    @Test
    fun workIsCountedByTheWeekAndItsDaysKeepNoDiary() {
        val label = res(R.string.report_changes_work)
        fun marked(
            first: Double,
            last: Double,
        ) = section(workDays(first, last), printed = setOf(ReportPart.WORK)).marked(label)
        fun hours(value: String) = res(R.string.report_changes_per_week, res(R.string.report_hours_value, value))
        fun weeks(count: Int) = ReportText.Quantity(R.plurals.report_counted_weeks, count)

        assertEquals(true, marked(5.0, 8.0))
        // 39.9 hours against 35 is under five hours more, and under a fifth.
        assertEquals(false, marked(5.0, 5.7))
        // A fifth more on 14 hours a week is under five hours.
        assertEquals(false, marked(2.0, 2.6))
        val built = section(workDays(5.0, 8.0), printed = setOf(ReportPart.WORK))!!.row(label)!!
        assertEquals(res(R.string.report_changes_from_to, hours("35"), hours("56")), built.value)
        assertTrue(res(R.string.report_changes_both, weeks(4), weeks(4)) in (built.note as ReportText.Join).parts)
        // Sessions on three days of each week: no week is counted, and nothing is compared.
        val sparse = workDays(5.0, 8.0).filter { it.localDay()!!.dayOfWeek.value <= 3 }
        assertNull(section(sparse, printed = setOf(ReportPart.WORK))!!.row(label))

        val drinks = FIRST_DAYS.take(10).map { drink(it, 20.0) } + LAST_DAYS.map { drink(it, 20.0) }
        val timed = FIRST_DAYS.drop(10).map { session(it, 8.0) }
        val counts = res(R.string.report_changes_on_days_by_hand, daysOf(10, 31), daysOf(31, 31))
        val alcohol = section(drinks + timed, printed = setOf(ReportPart.SOCIAL, ReportPart.WORK))!!
        assertNull(alcohol.row(res(R.string.alcohol_title)))
        assertTrue(notCompared(res(R.string.alcohol_title), counts).parts.first() in alcohol.texts())
    }

    /**
     * The finding about erections sits under a man's count only: on a woman's sheet the pair keeps its
     * diary study and its "mixed", and no other pair's footnote depends on the profile.
     */
    @Test
    fun theFindingAboutErectionsIsPrintedOnAMaleProfilesSheetOnly() {
        val pair = ComparisonPair.ALCOHOL_PARTNERED
        val diary = fixture(pair.exposure) + fixture(pair.outcome)
        val male = section(diary.entries, diary.days)!!
        val female = section(diary.entries, diary.days, profile = PROFILE.copy(sex = "FEMALE"))!!

        assertTrue(pair.footnote("MALE") in male.footnotes)
        assertTrue(pair.sentenceRes in female.pairs())
        assertTrue(pair.footnote("FEMALE") in female.footnotes)
        assertFalse(pair.footnote("MALE") in female.footnotes)
        listOf(pair.maleBackgroundRes!!, pair.maleSourceRes!!).forEach { id ->
            assertFalse(female.texts().any { (it as? ReportText.Res)?.id == id })
        }
        ComparisonPair.entries.filter { it.maleBackgroundRes == null }.forEach {
            assertEquals(it.key, it.footnote("MALE"), it.footnote("FEMALE"))
        }
    }
}

// --- the diaries the cases are built from -------------------------------------------------------------

private val TODAY: LocalDate = LocalDate.of(2026, 9, 5)
private val QUARTER: LocalDate = TODAY.minusMonths(3)
private val ZONE: ZoneId = ZoneOffset.UTC
private val PROFILE = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1980-01-01")

/** The quarter's two stretches: 5 June – 5 July and 6 August – 5 September, 31 days each. */
private val FIRST_DAYS = (0L until 31L).map { QUARTER.plusDays(it) }
private val LAST_DAYS = (0L until 31L).map { TODAY.minusDays(30 - it) }

private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

/** The section on a sheet from [from] to today that prints [printed]; null where it has nothing. */
private fun section(
    entries: List<JournalEntry>,
    days: List<DaySteps> = emptyList(),
    printed: Set<ReportPart> = ReportPart.entries.toSet(),
    detail: SexualDetail = SexualDetail.DETAILED,
    from: LocalDate = QUARTER,
    medications: List<Medication> = emptyList(),
    history: List<MedicalHistoryRow> = emptyList(),
    profile: UserProfile = PROFILE,
): ReportSection? = changesSection(
    sources = ReportSources(profile, entries, days, medications, history = history),
    request = ReportRequest(from = from, to = TODAY, parts = ReportPart.entries.toSet(), sexualDetail = detail),
    printed = printed,
    zone = ZONE,
)

private fun section(fixture: ChangesDiary, printed: Set<ReportPart> = ReportPart.entries.toSet()) =
    section(fixture.entries, fixture.days, printed = printed)

/** The not-compared line holding one figure and its counts. */
private fun notCompared(label: ReportText, counts: ReportText) =
    ReportText.Join(listOf(res(R.string.report_changes_item, label, counts)), "; ")

private fun ReportSection.row(label: ReportText): ReportRow? = rows.firstOrNull { it.label == label }

private fun ReportRow.marked(): Boolean =
    (note as? ReportText.Join)?.parts?.contains(res(R.string.report_changes_changed)) == true

/** Whether the figure's row is marked; null where the figure is not compared at all. */
private fun ReportSection?.marked(label: ReportText): Boolean? = this?.row(label)?.marked()

/** The sentence of each pair row, by its resource. */
private fun ReportSection.pairs(): List<Int> =
    rows.filter { it.label == res(R.string.report_changes_pair) }.map { (it.value as ReportText.Res).id }

private fun ReportSection.bloodRows(): List<ReportRow> =
    rows.filter { (it.value as? ReportText.Res)?.id == R.string.report_changes_blood_value }

/** Every text anywhere in the section's rows and footnotes, arguments included. */
private fun ReportSection.texts(): List<ReportText> =
    (rows.flatMap { listOfNotNull(it.label, it.value, it.note) } + footnotes).flatMap { it.flattened() }

private fun ReportText.flattened(): List<ReportText> = listOf(this) + when (this) {
    is ReportText.Res -> args.filterIsInstance<ReportText>().flatMap { it.flattened() }
    is ReportText.Join -> parts.flatMap { it.flattened() }
    else -> emptyList()
}

private fun entry(day: LocalDate, kind: String, hour: Long = 9, source: String? = null) = JournalEntry(
    day = day.toString(),
    occurredAt = day.atStartOfDay(ZONE).plusHours(hour).toInstant().toEpochMilli(),
    kind = kind,
    title = kind,
    calories = 0.0,
    measurementSource = source,
)

private fun weighIn(day: LocalDate, kg: Double) = entry(day, "MEASUREMENT").copy(measurementWeightKg = kg)

/** Four weigh-ins ten days apart in each stretch, at [first] kg and then [last]. */
private fun weights(first: Double, last: Double) =
    listOf(0, 10, 20, 30).flatMap { listOf(weighIn(FIRST_DAYS[it], first), weighIn(LAST_DAYS[it], last)) }

private fun steps(first: Long, last: Long) =
    FIRST_DAYS.map { DaySteps(it.toString(), first) } + LAST_DAYS.map { DaySteps(it.toString(), last) }

/** Twenty days answered in each stretch, [first] and then [last] of them wound up; mood steady at 4. */
private fun tension(first: Int, last: Int) = (0 until 20).flatMap { i ->
    listOf(answer(FIRST_DAYS[i], if (i < first) 1 else 4), answer(LAST_DAYS[i], if (i < last) 1 else 4))
}

private fun answer(day: LocalDate, energy: Int) = entry(day, KIND_MOOD).copy(moodScore = 4, energyScore = energy)

/** Twenty days of mood in each stretch, at [first] and then [last]; the second dial left unanswered. */
private fun moods(first: Int, last: Int) = (0 until 20).flatMap { i ->
    listOf(
        entry(FIRST_DAYS[i], KIND_MOOD).copy(moodScore = first),
        entry(LAST_DAYS[i], KIND_MOOD).copy(moodScore = last),
    )
}

private fun act(day: LocalDate) =
    entry(day, KIND_CYCLE_LOG).copy(measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"SEX_PROTECTED"}""")

/** [first] partnered acts on the first stretch's days, then [last]. */
private fun acts(first: Int, last: Int) = FIRST_DAYS.take(first).map(::act) + LAST_DAYS.take(last).map(::act)

/** Two meals a day on every day of both stretches, each of [first] kcal and then of [last]. */
private fun meals(first: Double, last: Double) = mealsWith({ it.copy(calories = first) }, { it.copy(calories = last) })

/** Two meals a day, on [days] days of the first stretch and every day of the last, shaped by [first] and [last]. */
private fun mealsWith(
    first: (JournalEntry) -> JournalEntry,
    last: (JournalEntry) -> JournalEntry,
    days: Int = 31,
) = listOf(8L, 13L).flatMap { hour ->
    FIRST_DAYS.take(days).map { first(entry(it, "EAT", hour)) } + LAST_DAYS.map { last(entry(it, "EAT", hour)) }
}

private fun drink(day: LocalDate, grams: Double) = entry(day, KIND_ALCOHOL, hour = 20).copy(servingG = grams)

private fun glass(day: LocalDate) = entry(day, KIND_WATER, hour = 11).copy(servingG = 1_000.0)

/** A glass of water on every day of both stretches: a diary kept by hand throughout. */
private fun kept() = (FIRST_DAYS + LAST_DAYS).map(::glass)

/** [first] drinks of 20 g over the first stretch's days, then [last]. */
private fun drinks(first: Int, last: Int) =
    (0 until first).map { drink(FIRST_DAYS[it % 31], 20.0) } + (0 until last).map { drink(LAST_DAYS[it % 31], 20.0) }

/** [first] cigarettes over the first stretch's days, then [last]. */
private fun cigarettes(first: Int, last: Int) =
    (0 until first).map { smoke(FIRST_DAYS[it % 31]) } + (0 until last).map { smoke(LAST_DAYS[it % 31]) }

private fun smoke(day: LocalDate) = entry(day, KIND_SMOKING, hour = 18).copy(servingG = CIGARETTE_EQUIVALENT_G)

/** A cup a day of [first] mg on every day of the first stretch, or of its first [days], then [last]. */
private fun cups(first: Int, last: Int, days: Int = 31) =
    FIRST_DAYS.take(days).map { cup(it, first) } + LAST_DAYS.map { cup(it, last) }

private fun cup(day: LocalDate, mg: Int) = entry(day, KIND_CAFFEINE, hour = 10).copy(servingG = mg.toDouble())

/** A night of [first] minutes before every day of the first stretch, or of its first [days], then of [last]. */
private fun nights(first: Int, last: Int, days: Int = 31) =
    FIRST_DAYS.take(days).map { night(it, first) } + LAST_DAYS.map { night(it, last) }

private fun night(day: LocalDate, minutes: Int): JournalEntry {
    val end = day.atStartOfDay(ZONE).plusHours(7).toInstant().toEpochMilli()
    return entry(day, "MEASUREMENT", hour = 7).copy(sleepStartAt = end - minutes * 60_000L, sleepEndAt = end)
}

/** [readings] cuff readings three days apart in each stretch. */
private fun pressures(first: Pair<Int, Int>, last: Pair<Int, Int>, readings: Int = 8) = (0 until readings).flatMap {
    listOf(cuff(FIRST_DAYS[it * 3], first), cuff(LAST_DAYS[it * 3], last))
}

private fun cuff(day: LocalDate, pressure: Pair<Int, Int>, hour: Long = 8) = entry(day, "MEASUREMENT", hour).copy(
    measurementPayloadJson = """{"systolicMmhg":${pressure.first},"diastolicMmhg":${pressure.second}}""",
)

/** [readings] pulse readings three days apart in each stretch, the last stretch's written by [lastSource]. */
private fun pulses(first: Int, last: Int, readings: Int = 8, lastSource: String? = null) = (0 until readings).flatMap {
    listOf(pulse(FIRST_DAYS[it * 3], first), pulse(LAST_DAYS[it * 3], last, source = lastSource))
}

private fun pulse(day: LocalDate, bpm: Int, hour: Long = 8, source: String? = null) =
    entry(day, "MEASUREMENT", hour, source).copy(measurementHeartRateBpm = bpm)

/** Rows and counted step days, added together as a sheet's diary. */
private data class ChangesDiary(val entries: List<JournalEntry> = emptyList(), val days: List<DaySteps> = emptyList()) {
    operator fun plus(other: ChangesDiary) = ChangesDiary(entries + other.entries, days + other.days)
}

/**
 * Each figure held in both stretches: past its threshold where moved, under it where not. Every
 * figure has one, so every pair in the catalogue can be reached from here.
 */
private val FIXTURES: Map<ChangeDomain, (Boolean) -> ChangesDiary> = mapOf(
    ChangeDomain.WEIGHT to { moved -> ChangesDiary(weights(80.0, if (moved) 84.0 else 81.0)) },
    ChangeDomain.PRESSURE to { moved -> ChangesDiary(pressures(120 to 80, if (moved) 130 to 86 else 122 to 81)) },
    ChangeDomain.PULSE to { moved -> ChangesDiary(pulses(62, if (moved) 70 else 64)) },
    ChangeDomain.STEPS to { moved -> ChangesDiary(days = steps(9_000, if (moved) 6_000 else 8_500)) },
    ChangeDomain.SLEEP to { moved -> ChangesDiary(nights(450, if (moved) 380 else 440)) },
    ChangeDomain.WOUND_UP to { moved -> ChangesDiary(tension(2, if (moved) 12 else 4)) },
    ChangeDomain.MOOD to { moved -> ChangesDiary(moods(4, if (moved) 2 else 4)) },
    ChangeDomain.PARTNERED to { moved -> ChangesDiary(acts(12, if (moved) 2 else 11) + kept()) },
    ChangeDomain.ALCOHOL to { moved -> ChangesDiary(drinks(3, if (moved) 12 else 4) + kept()) },
    ChangeDomain.TOBACCO to { moved -> ChangesDiary(cigarettes(5, if (moved) 45 else 6) + kept()) },
    ChangeDomain.FOOD to { moved -> ChangesDiary(meals(900.0, if (moved) 1_200.0 else 950.0)) },
    ChangeDomain.SUGAR to { moved ->
        ChangesDiary(mealsWith({ it.copy(sugarG = 20.0) }, { it.copy(sugarG = if (moved) 30.0 else 21.0) }))
    },
    ChangeDomain.SATURATED_FAT to { moved ->
        ChangesDiary(
            mealsWith(
                { it.copy(calories = 800.0, saturatedFatG = 8.0) },
                { it.copy(calories = 800.0, saturatedFatG = if (moved) 14.0 else 8.5) },
            ),
        )
    },
    ChangeDomain.SODIUM to { moved ->
        ChangesDiary(mealsWith({ it.copy(sodiumMg = 800.0) }, { it.copy(sodiumMg = if (moved) 1_500.0 else 900.0) }))
    },
    ChangeDomain.CAFFEINE to { moved -> ChangesDiary(cups(100, if (moved) 300 else 150)) },
    ChangeDomain.WATER to { _ -> ChangesDiary(kept()) },
    ChangeDomain.WORK to { moved -> ChangesDiary(workDays(5.0, if (moved) 8.0 else 5.5)) },
    ChangeDomain.HEAD_NECK_PAIN to { moved -> ChangesDiary(headaches(2, if (moved) 12 else 4)) },
    ChangeDomain.SOLO to { moved -> ChangesDiary(solo(12, if (moved) 2 else 11) + kept()) },
    ChangeDomain.MORNING to { moved -> ChangesDiary(mornings(16, if (moved) 4 else 14)) },
)

/** A work session of [hours] from nine in the morning, as the timer writes one. */
private fun session(day: LocalDate, hours: Double): JournalEntry {
    val start = day.atStartOfDay(ZONE).plusHours(9).toInstant().toEpochMilli()
    return workEntry(listOf(WorkSegment(start, start + (hours * 3_600_000L).toLong())), "Work", ZONE)!!
}

/** A session on every day of both stretches, of [first] hours and then of [last]. */
private fun workDays(first: Double, last: Double) =
    FIRST_DAYS.map { session(it, first) } + LAST_DAYS.map { session(it, last) }

/** A pain entry in the forehead that evening, or "no pain today" where not [hurt]. */
private fun painRow(day: LocalDate, hurt: Boolean) = entry(day, KIND_PAIN, hour = 20).copy(
    measurementPayloadJson = encodePainPayload(
        if (hurt) {
            PainReport(regions = listOf(RegionSide(PainRegion.HEAD_FRONT, BodySide.CENTRE)), nrs = 5)
        } else {
            PainReport(none = true)
        },
    ),
)

/** [days] days answered in each stretch, the first [first] and then [last] of them with a headache. */
private fun headaches(first: Int, last: Int, days: Int = 20) = (0 until 20).flatMap { i ->
    listOfNotNull(painRow(FIRST_DAYS[i], i < first).takeIf { i < days }, painRow(LAST_DAYS[i], i < last))
}

/** [first] solo acts on the first stretch's days, then [last]. */
private fun solo(first: Int, last: Int) =
    (FIRST_DAYS.take(first) + LAST_DAYS.take(last)).map { entry(it, KIND_MASTURBATION, hour = 22) }

/** Twenty mornings answered in each stretch, the first [first] and then [last] of them firm, the rest none. */
private fun mornings(first: Int, last: Int) = (0 until 20).flatMap { i ->
    listOf(morning(FIRST_DAYS[i], i < first), morning(LAST_DAYS[i], i < last))
}

private fun morning(day: LocalDate, firm: Boolean) = entry(day, KIND_MORNING_CHECK, hour = 7).copy(
    measurementPayloadJson = morningCheckPayload(
        MorningCheck(
            answer = if (firm) MorningErection.FIRM else MorningErection.NONE,
            wokeAt = day.atStartOfDay(ZONE).plusHours(7).toInstant().toEpochMilli(),
            wake = WakeSource.TYPED,
        ),
    ),
)

private fun fixture(domain: ChangeDomain, moved: Boolean = true): ChangesDiary = FIXTURES.getValue(domain)(moved)

/** The earlier draw is before the period; the later one inside it. */
private val DRAW_BEFORE: LocalDate = LocalDate.of(2026, 3, 1)
private val DRAW_AFTER: LocalDate = LocalDate.of(2026, 8, 20)
private val DRAWS: Pair<LocalDate, LocalDate> = DRAW_BEFORE to DRAW_AFTER

private fun panel(day: LocalDate, values: Map<LabMarker, Double>, id: Long) = entry(day, KIND_LABS, hour = 8).copy(
    id = id,
    measurementPayloadJson = labsPayload(values, fasting = true),
)

/** One marker drawn twice, on the days of [draws], at [before] and then [after]. */
private fun panels(marker: LabMarker, before: Double, after: Double, draws: Pair<LocalDate, LocalDate> = DRAWS) =
    listOf(panel(draws.first, mapOf(marker to before), id = 1), panel(draws.second, mapOf(marker to after), id = 2))

/** The [days] days up to and including a draw. */
private fun window(draw: LocalDate, days: Int) = (0L until days).map { draw.minusDays(it) }

/** Two meals on each of [days], shaped by [shape]. */
private fun twoMeals(days: List<LocalDate>, shape: (JournalEntry) -> JournalEntry) =
    days.flatMap { day -> listOf(8L, 13L).map { shape(entry(day, "EAT", it)) } }

/**
 * One exposure across the window before each of [draws], past its threshold between the two; the
 * earlier window left undescribed where not [keptBefore], the later where not [keptAfter].
 */
private fun beforeDraws(
    domain: ChangeDomain,
    days: Int,
    keptBefore: Boolean = true,
    keptAfter: Boolean = true,
    draws: Pair<LocalDate, LocalDate> = DRAWS,
): ChangesDiary {
    val before = window(draws.first, days)
    val after = window(draws.second, days)
    return when (domain) {
        ChangeDomain.ALCOHOL -> ChangesDiary(
            before.filter { keptBefore }.map(::glass) + after.filter { keptAfter }.map(::glass) +
                before.filterIndexed { i, _ -> i % 14 == 0 }.map { drink(it, 20.0) } +
                after.filterIndexed { i, _ -> i % 3 == 0 }.map { drink(it, 20.0) },
        )

        ChangeDomain.STEPS -> ChangesDiary(
            days = before.map { DaySteps(it.toString(), 9_000) } + after.map { DaySteps(it.toString(), 6_000) },
        )

        ChangeDomain.WEIGHT -> ChangesDiary(
            listOf(0L, 10L, 20L).flatMap {
                listOf(weighIn(draws.first.minusDays(it), 80.0), weighIn(draws.second.minusDays(it), 85.0))
            },
        )

        ChangeDomain.SUGAR -> ChangesDiary(
            twoMeals(before) {
                it.copy(sugarG = 20.0)
            } + twoMeals(after) { it.copy(sugarG = 30.0) },
        )

        ChangeDomain.SATURATED_FAT -> ChangesDiary(
            twoMeals(before) { it.copy(calories = 800.0, saturatedFatG = 8.0) } +
                twoMeals(after) { it.copy(calories = 800.0, saturatedFatG = 14.0) },
        )

        else -> error("no blood fixture for $domain")
    }
}

/** Two results of each marker a blood comparison opens with, far enough apart to print apart. */
private val BLOOD_VALUES: Map<LabMarker, Pair<Double, Double>> = mapOf(
    LabMarker.GGT to (22.0 to 41.0),
    LabMarker.TRIGLYCERIDES to (1.2 to 2.0),
    LabMarker.URIC_ACID to (300.0 to 420.0),
    LabMarker.LDL to (3.0 to 3.6),
)

/** Two results of the comparison's first marker, and its exposure moving across the windows before them. */
private fun bloodDiary(
    comparison: LabComparison,
    keptBefore: Boolean = true,
    keptAfter: Boolean = true,
    draws: Pair<LocalDate, LocalDate> = DRAWS,
): ChangesDiary {
    val marker = comparison.markers.first()
    val (before, after) = BLOOD_VALUES.getValue(marker)
    val days = LAB_DIET_WINDOWS[marker]?.windowDays ?: 56
    return ChangesDiary(panels(marker, before, after, draws)) +
        beforeDraws(comparison.exposure, days, keptBefore, keptAfter, draws)
}
