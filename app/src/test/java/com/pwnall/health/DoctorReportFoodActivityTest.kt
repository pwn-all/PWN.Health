package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The lifestyle sections of the doctor's report over a diary kept the way people keep one: some
 * days in full, some days half, and many days not at all.
 *
 * Every case is a figure a doctor would read wrongly: a sodium that is half the real one because
 * blanks were added in as nought, an average day that is breakfast, water that vanished because no
 * meal was logged, a routine of six exercises counted as six workouts, a week of bathroom diary
 * spread thin over half a year.
 */
class DoctorReportFoodActivityTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val from: LocalDate = today.minusMonths(6)
    private val zone: ZoneId = ZoneOffset.UTC
    private val periodDays = (ChronoUnit.DAYS.between(from, today) + 1).toInt()

    private val profile = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1985-04-10")

    private fun at(day: LocalDate, minute: Int = 9 * 60): Long =
        day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun meal(
        day: LocalDate,
        kcal: Double = 1_000.0,
        minute: Int = 9 * 60,
        sodium: Double? = null,
        caffeine: Double? = null,
    ) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, minute),
        kind = "EAT",
        title = "meal",
        calories = kcal,
        proteinG = 10.0,
        sodiumMg = sodium,
        caffeineMg = caffeine,
    )

    /** Two meals, which is a day the diary was kept through. */
    private fun fullDay(day: LocalDate, sodium: Double? = null, secondSodium: Double? = sodium) =
        listOf(meal(day, sodium = sodium), meal(day, minute = 19 * 60, sodium = secondSodium))

    private fun row(kind: String, day: LocalDate, serving: Double = 0.0, minute: Int = 10 * 60) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, minute),
        kind = kind,
        title = kind,
        calories = 0.0,
        servingG = serving,
    )

    private fun workout(day: LocalDate, occurredAt: Long, minutes: Double?) = JournalEntry(
        day = day.toString(),
        occurredAt = occurredAt,
        kind = "ACTIVITY",
        title = "exercise",
        calories = 0.0,
        durationMinutes = minutes,
    )

    private fun weighIn(day: LocalDate, kg: Double, minute: Int = 7 * 60) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, minute),
        kind = "MEASUREMENT",
        title = "weight",
        calories = 0.0,
        measurementWeightKg = kg,
    )

    private fun report(entries: List<JournalEntry>, days: List<DaySteps> = emptyList()) = doctorReport(
        sources = ReportSources(profile, entries, days),
        request = ReportRequest(from = from, to = today),
        today = today,
        zone = zone,
    )

    private fun DoctorReport.section(part: ReportPart): ReportSection =
        sections.single { it.part == part && it.presence == SectionPresence.PRESENT }

    private fun ReportSection.row(label: ReportText): ReportRow? = rows.firstOrNull { it.label == label }

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    private fun days(count: Int, step: Long = 1L) = (0 until count).map { today.minusDays(1L + it * step) }

    private fun perDay(figure: Any, unit: Int) =
        res(R.string.report_per_day_rate, res(R.string.report_value_unit, figure, res(unit)))

    // --- food -------------------------------------------------------------------------------------

    /**
     * Sodium on every entry of seven days and on one of two entries of three more: the average is
     * the seven whole days, not the ten with a blank added in as nought. And fibre, which nobody
     * entered, is no row at all rather than "0 g".
     */
    @Test
    fun aBlankNutrientIsNeitherNoughtNorADay() {
        val whole = days(7).flatMap { fullDay(it, sodium = 1_000.0, secondSodium = 500.0) }
        val halfBlank = days(3, step = 1L).map { it.minusDays(10) }.flatMap { fullDay(it, 800.0, null) }

        val food = report(whole + halfBlank).section(ReportPart.NUTRITION)
        val sodium = food.row(res(R.string.editor_sodium))!!

        assertEquals(perDay(1_500, R.string.unit_milligrams), sodium.value)
        assertEquals(ReportText.Quantity(R.plurals.count_days, 7), sodium.note)
        assertNull("fibre was never entered", food.row(res(R.string.editor_fiber)))
    }

    /** A nutrient carried in full on three days is counted and not averaged, like the day itself. */
    @Test
    fun aNutrientOnTooFewDaysIsCountedNotAveraged() {
        val entries = days(3).flatMap { fullDay(it, sodium = 900.0) } +
            days(4).map { it.minusDays(10) }.flatMap { fullDay(it) }

        val sodium = report(entries).section(ReportPart.NUTRITION).row(res(R.string.editor_sodium))!!

        assertEquals(ReportText.Quantity(R.plurals.report_not_averaged, 7), sodium.value)
        assertEquals(ReportText.Quantity(R.plurals.count_days, 3), sodium.note)
    }

    /**
     * Seven days of two thousand kilocalories and three of a single breakfast: the average is the
     * seven days, and the three are counted under it rather than read as three days of fasting.
     */
    @Test
    fun aDayHoldingOneEntryIsLeftOutOfTheAverage() {
        val entries = days(7).flatMap { fullDay(it) } + days(3).map { meal(it.minusDays(10), 500.0) }

        val food = report(entries).section(ReportPart.NUTRITION)
        val short = food.row(ReportText.Quantity(R.plurals.report_food_short_days, LAB_DIET_MIN_MEALS_PER_DAY))!!

        assertEquals(res(R.string.report_value_unit, 2_000, res(R.string.unit_kcal)), food.rows[0].value)
        assertEquals(res(R.string.report_count_of, 7, ReportText.Quantity(R.plurals.count_days, 10)), food.rows[0].note)
        assertEquals(ReportText.Plain("3"), short.value)
        assertEquals(res(R.string.report_left_out), short.note)
        assertEquals(ReportText.Plain("17"), food.row(res(R.string.report_meals))!!.value)
    }

    /** Three full days are three days: the counts are printed, and no average or macros under them. */
    @Test
    fun tooFewFullDaysPrintTheCountsAndNoAverage() {
        val entries = days(3).flatMap { fullDay(it) } + days(6).map { meal(it.minusDays(10), 450.0) }

        val food = report(entries).section(ReportPart.NUTRITION)

        assertEquals(ReportText.Quantity(R.plurals.report_not_averaged, 7), food.rows[0].value)
        assertEquals(res(R.string.report_count_of, 3, ReportText.Quantity(R.plurals.count_days, 9)), food.rows[0].note)
        assertNull(food.row(res(R.string.editor_macros)))
        // Nothing above it was averaged, so there is nothing for it to have been left out of.
        assertNull(food.row(ReportText.Quantity(R.plurals.report_food_short_days, LAB_DIET_MIN_MEALS_PER_DAY))!!.note)
    }

    /** One snack in half a year is a section with a count in it, not an average day of 150 kcal. */
    @Test
    fun oneSnackIsNotAnAverageDay() {
        val built = report(listOf(meal(today.minusDays(3), 150.0)))
        val food = built.section(ReportPart.NUTRITION)

        assertTrue(ReportPart.NUTRITION in built.filled)
        assertEquals(ReportText.Quantity(R.plurals.report_not_averaged, 7), food.rows[0].value)
        assertEquals(res(R.string.report_count_of, 0, ReportText.Quantity(R.plurals.count_days, 1)), food.rows[0].note)
    }

    /** The first and last day food was logged, so an old burst is not read as this month's diet. */
    @Test
    fun foodCarriesTheFirstAndLastDayItWasLoggedOn() {
        val early = from.plusDays(2)
        val late = today.minusDays(40)
        val food = report(fullDay(early) + fullDay(late)).section(ReportPart.NUTRITION)

        assertEquals(
            res(R.string.report_period_value, ReportText.Day(early), ReportText.Day(late)),
            food.row(res(R.string.report_first_last_day))!!.value,
        )
    }

    // --- water and caffeine -----------------------------------------------------------------------

    /**
     * Water on ten days and no meal at all is a section with the water in it, a day's figure over the
     * days that hold a glass, standing as a row of its own rather than a detail under nothing.
     */
    @Test
    fun waterIsPrintedWithoutAnyFood() {
        val water = days(10).flatMap { listOf(row(KIND_WATER, it, 300.0), row(KIND_WATER, it, 300.0, 16 * 60)) }

        val built = report(water)
        val section = built.section(ReportPart.NUTRITION)
        val only = section.rows.single()

        assertTrue(ReportPart.NUTRITION in built.filled)
        assertEquals(res(R.string.water_title), only.label)
        assertEquals(perDay(600, R.string.unit_ml), only.value)
        assertEquals(ReportText.Quantity(R.plurals.count_days, 10), only.note)
        assertFalse(only.detail)
        assertEquals(Completeness(10, periodDays, 20), section.completeness)
    }

    /** The days with only a glass on them are days the section holds something for. */
    @Test
    fun waterDaysCountTowardsTheSectionsDays() {
        val entries = days(7).flatMap { fullDay(it) } + days(3).map { row(KIND_WATER, it.minusDays(10), 250.0) }

        assertEquals(Completeness(10, periodDays, 17), report(entries).section(ReportPart.NUTRITION).completeness)
    }

    /**
     * Two cups and a meal stating its caffeine: a day's figure over the two days that hold any. A meal
     * that states none is not a day of none, and does not make one.
     */
    @Test
    fun caffeineIsADayFigureOverTheDaysItWasLogged() {
        val first = today.minusDays(2)
        val second = today.minusDays(1)
        val entries = listOf(
            row(KIND_CAFFEINE, first, 135.0),
            row(KIND_CAFFEINE, first, 135.0, 15 * 60),
            row(KIND_CAFFEINE, second, 80.0),
            meal(second, caffeine = 80.0),
            meal(today),
        )

        val caffeine = report(entries).section(ReportPart.NUTRITION).row(res(R.string.caffeine_title))!!

        assertEquals(perDay(215, R.string.unit_milligrams), caffeine.value)
        assertEquals(ReportText.Quantity(R.plurals.count_days, 2), caffeine.note)
    }

    // --- alcohol, tobacco and the bathroom --------------------------------------------------------

    /** Three drinks over two days and four cigarettes over two others are seven entries on four days. */
    @Test
    fun alcoholAndTobaccoCountEntriesOnEveryDayEitherWasLogged() {
        val (drinking, smoking) = days(4).chunked(2)
        val entries = listOf(
            row(KIND_ALCOHOL, drinking[0], 20.0),
            row(KIND_ALCOHOL, drinking[0], 20.0, 22 * 60),
            row(KIND_ALCOHOL, drinking[1], 14.0),
        ) + smoking.flatMap { listOf(row(KIND_SMOKING, it, 0.8), row(KIND_SMOKING, it, 0.8, 12 * 60)) }

        assertEquals(Completeness(4, periodDays, 7), report(entries).section(ReportPart.SOCIAL).completeness)
    }

    /** A week of daily stools in half a year is one a day over the week, not 0.0 over the half year. */
    @Test
    fun aWeekOfStoolsIsOneADayOverTheWeek() {
        val stools = days(7).map { row(KIND_STOOL, it).copy(subtitle = StoolKind.NORMAL.name) }

        val stool = report(stools).section(ReportPart.BOWEL).row(res(R.string.stool_title))!!
        val rate = res(R.string.report_per_day_rate, "1")

        assertEquals(ReportText.Join(listOf(rate, ReportText.Quantity(R.plurals.count_days, 7))), stool.note)
    }

    /** One stool over a month of bladder diary is a rate under a tenth, never "0.0 a day". */
    @Test
    fun aRareVisitIsUnderATenthRatherThanNought() {
        val entries = days(30).map { row(KIND_URINATION, it) } + row(KIND_STOOL, today.minusDays(1), minute = 8 * 60)

        val bowel = report(entries).section(ReportPart.BOWEL)
        val days = ReportText.Quantity(R.plurals.count_days, 30)

        assertEquals(
            ReportText.Join(listOf(res(R.string.report_per_day_rate, "<0.1"), days)),
            bowel.row(res(R.string.stool_title))!!.note,
        )
        assertEquals(
            ReportText.Join(listOf(res(R.string.report_per_day_rate, "1"), days)),
            bowel.row(res(R.string.urination_title))!!.note,
        )
    }

    // --- activity ---------------------------------------------------------------------------------

    /** A day at 0 steps is a phone left at home: out of the mean and the days, counted under them. */
    @Test
    fun aDayAtZeroStepsIsLeftOutAndCounted() {
        val (a, b, c) = days(3)
        val steps = listOf(DaySteps(a.toString(), 8_000), DaySteps(b.toString(), 6_000), DaySteps(c.toString(), 0))

        val activity = report(emptyList(), steps).section(ReportPart.ACTIVITY)
        val zero = activity.row(res(R.string.report_steps_zero_days))!!

        assertEquals(res(R.string.report_steps_per_day, 7_000L.sheetCount()), activity.rows[0].value)
        assertEquals(ReportText.Quantity(R.plurals.count_days, 2), activity.rows[0].note)
        assertEquals(ReportText.Plain("1"), zero.value)
        assertEquals(res(R.string.report_left_out), zero.note)
        assertEquals(Completeness(2, periodDays, 2), activity.completeness)
    }

    /**
     * Days at 0 beside workouts and no counted day at all are still counted: a row of their own, not
     * a detail under a mean that is not there, and not "left out" of it.
     */
    @Test
    fun zeroStepDaysAreCountedWhenNoDayHoldsACount() {
        val (a, b, c) = days(3)
        val steps = listOf(DaySteps(a.toString(), 0), DaySteps(b.toString(), 0))

        val activity = report(listOf(workout(c, at(c), 30.0)), steps).section(ReportPart.ACTIVITY)
        val zero = activity.rows.first()

        assertEquals(res(R.string.report_steps_zero_days), zero.label)
        assertEquals(ReportText.Plain("2"), zero.value)
        assertNull(zero.note)
        assertFalse(zero.detail)
        assertNull(activity.row(res(R.string.steps)))
        assertEquals(ReportText.Plain("1"), activity.row(res(R.string.home_workouts))!!.value)
    }

    /** One day handed in twice is one day at its larger count: never more days than the period. */
    @Test
    fun aDayHandedInTwiceCountsOnceAtItsLargerCount() {
        val (a, b) = days(2)
        val steps = listOf(DaySteps(a.toString(), 5_000), DaySteps(a.toString(), 9_000), DaySteps(b.toString(), 7_000))

        val activity = report(emptyList(), steps).section(ReportPart.ACTIVITY)

        assertEquals(res(R.string.report_steps_per_day, 8_000L.sheetCount()), activity.rows[0].value)
        assertEquals(Completeness(2, periodDays, 2), activity.completeness)
    }

    /**
     * Every figure on the sheet is in Latin digits, whatever the phone's language counts in. Put
     * through the app's own formatter, the steps a day would come out, on a phone set to Arabic, as
     * the one Eastern Arabic number on the page.
     */
    @Test
    fun theStepsADayArePrintedInTheSheetsOwnDigits() {
        val steps = days(2).map { DaySteps(it.toString(), 8_432) }
        val wasDefault = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val printed = report(emptyList(), steps).section(ReportPart.ACTIVITY).rows[0].value as ReportText.Res
            val figure = printed.args.single() as String

            // Whatever the language groups thousands with, the digits themselves are these four.
            assertEquals("8432", figure.filter(Char::isDigit))
        } finally {
            Locale.setDefault(wasDefault)
        }
    }

    // --- who wrote the food diary -----------------------------------------------------------------

    /**
     * A food diary that came through Health Connect, or out of another app's file, was not typed
     * into this app, so the line under the section does not say "entered by hand" over all of it.
     */
    @Test
    fun anImportedFoodDiaryIsNotNamedAsTypedByHand() {
        val typed = days(7).flatMap { fullDay(it) }
        val imported = typed.map { it.copy(measurementSource = "HEALTH_CONNECT") }
        val water = row(KIND_WATER, today, serving = 250.0).copy(measurementSource = "HEALTH_CONNECT")

        assertEquals(
            listOf(Provenance(ProvenanceCategory.MANUAL)),
            report(typed).section(ReportPart.NUTRITION).provenance,
        )
        assertEquals(
            listOf(Provenance(ProvenanceCategory.HEALTH_CONNECT)),
            report(imported + water).section(ReportPart.NUTRITION).provenance,
        )
        assertEquals(
            listOf(Provenance(ProvenanceCategory.MANUAL), Provenance(ProvenanceCategory.HEALTH_CONNECT)),
            report(typed.take(2) + imported.drop(2)).section(ReportPart.NUTRITION).provenance,
        )
        // A diary out of another app's export file carries that app's own name as its source.
        val exported = typed.map { it.copy(measurementSource = HealthExportApp.CRONOMETER.label) }
        assertEquals(
            listOf(Provenance(ProvenanceCategory.IMPORTED_APP)),
            report(exported).section(ReportPart.NUTRITION).provenance,
        )
    }

    /** The same for an act another app recorded and Health Connect handed over with its protection stated. */
    @Test
    fun anImportedActIsNotNamedAsEnteredByHand() {
        fun act(day: LocalDate, source: String?) = row(KIND_CYCLE_LOG, day).copy(
            measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"${CycleLog.SEX_PROTECTED.name}"}""",
            measurementSource = source,
        )
        val acts = listOf(act(today, null), act(today.minusDays(3), "HEALTH_CONNECT"))

        val built = doctorReport(
            sources = ReportSources(profile, acts, emptyList()),
            request = ReportRequest(from = from, to = today, parts = setOf(ReportPart.SEXUAL_HEALTH)),
            today = today,
            zone = zone,
        )

        assertEquals(
            listOf(Provenance(ProvenanceCategory.MANUAL), Provenance(ProvenanceCategory.HEALTH_CONNECT)),
            built.section(ReportPart.SEXUAL_HEALTH).provenance,
        )
    }

    /**
     * A routine of six exercises started on three days is three workouts, its rows a second apart,
     * one per exercise, as a journal may hold them rather than stamped back from the finish. Their
     * minutes add up.
     */
    @Test
    fun aRoutineIsOneWorkoutNotOnePerExercise() {
        val rows = days(3, step = 2L).flatMap { day ->
            val start = at(day, 18 * 60 + 59) + 58_000L
            (0 until 6).map { workout(day, start + it * 1_000L, 10.0) }
        }

        val workouts = report(rows).section(ReportPart.ACTIVITY).row(res(R.string.home_workouts))!!

        assertEquals(ReportText.Plain("3"), workouts.value)
        assertEquals(ReportText.Quantity(R.plurals.count_minutes, 180), workouts.note)
    }

    /** An imported session stamps every exercise with its start; a run that evening is its own. */
    @Test
    fun anImportedSessionIsOneWorkoutAndAnEveningRunIsAnother() {
        val day = today.minusDays(4)
        val rows = (0 until 4).map { workout(day, at(day, 7 * 60), 12.0) } + workout(day, at(day, 19 * 60), 30.0)

        val activity = report(rows).section(ReportPart.ACTIVITY)

        assertEquals(ReportText.Plain("2"), activity.row(res(R.string.home_workouts))!!.value)
        assertEquals(Completeness(1, periodDays, 2), activity.completeness)
    }

    /**
     * A superset is saved as two rows, the second stamped at the finish and the first the length of
     * the second before it: one workout, however long the second leg ran. Two workouts typed in three
     * minutes apart after the fact are still two, though their lengths make them overlap.
     */
    @Test
    fun aSupersetIsOneWorkout() {
        val (evening, other) = days(2)
        val finish = at(evening, 18 * 60)
        val superset = listOf(workout(evening, finish - 5 * 60_000L, 12.0), workout(evening, finish, 5.0))

        val workouts = report(superset).section(ReportPart.ACTIVITY).row(res(R.string.home_workouts))!!

        assertEquals(ReportText.Plain("1"), workouts.value)
        assertEquals(ReportText.Quantity(R.plurals.count_minutes, 17), workouts.note)

        val typedLater = listOf(workout(other, at(other, 21 * 60), 30.0), workout(other, at(other, 21 * 60 + 3), 45.0))

        assertEquals(
            ReportText.Plain("2"),
            report(typedLater).section(ReportPart.ACTIVITY).row(res(R.string.home_workouts))!!.value,
        )
    }

    /** A session with no recorded length is counted as one, never added in as nought minutes. */
    @Test
    fun aWorkoutWithNoLengthIsNotNoughtMinutes() {
        val (a, b) = days(2)
        val untimed = report(listOf(workout(a, at(a), null), workout(b, at(b), null))).section(ReportPart.ACTIVITY)

        assertNull(untimed.row(res(R.string.home_workouts))!!.note)
        assertEquals(ReportText.Plain("2"), untimed.row(res(R.string.report_workouts_untimed))!!.value)

        val mixed = report(listOf(workout(a, at(a), 45.0), workout(b, at(b), null))).section(ReportPart.ACTIVITY)

        assertEquals(ReportText.Quantity(R.plurals.count_minutes, 45), mixed.row(res(R.string.home_workouts))!!.note)
        assertEquals(ReportText.Plain("1"), mixed.row(res(R.string.report_workouts_untimed))!!.value)
    }

    /** Days with a workout and no step count are days with data, alongside the step days. */
    @Test
    fun aWorkoutDayIsADayWithData() {
        val (a, b, c) = days(3)
        val steps = listOf(DaySteps(a.toString(), 6_000))

        val workouts = listOf(workout(b, at(b), 30.0), workout(c, at(c), 30.0))

        val activity = report(workouts, steps).section(ReportPart.ACTIVITY)

        assertEquals(Completeness(3, periodDays, 3), activity.completeness)
    }

    // --- weight -----------------------------------------------------------------------------------

    /** 80.00 kg to 80.04 kg prints as a change of 0 kg: no sign where the figure shows no change. */
    @Test
    fun aChangeThatRoundsToNothingCarriesNoSign() {
        val entries = listOf(weighIn(today.minusDays(20), 80.0), weighIn(today.minusDays(2), 80.04))

        val change = report(entries).section(ReportPart.WEIGHT).row(res(R.string.report_change))!!

        assertEquals(res(R.string.report_value_unit, "0", res(R.string.unit_kg)), change.value)
    }

    /**
     * A morning and an evening weigh-in carry their times, so the two do not read as one typed in
     * twice; a day with one weigh-in needs none. Times are read in the phone's own zone, which is
     * the one the report screen builds the sheet in.
     */
    @Test
    fun twoWeighInsOnOneDayCarryTheirTimes() {
        val shared = today.minusDays(3)
        val morning = weighIn(shared, 81.2, 7 * 60 + 30)
        val evening = weighIn(shared, 81.9, 21 * 60 + 10)
        val alone = weighIn(today.minusDays(10), 81.5)

        val appendix = report(listOf(alone, morning, evening)).section(ReportPart.WEIGHT).appendix

        assertEquals(
            listOf(ReportText.Plain(clock(evening)), ReportText.Plain(clock(morning)), null),
            appendix.map(ReportRow::note),
        )
    }

    /** A scale stepped on every day is not a hundred rows: the newest ninety and a count of the rest. */
    @Test
    fun aDailyScaleIsCutWithACountOfWhatWasLeftOut() {
        val daily = (1..100).map { weighIn(today.minusDays(it.toLong()), 80.0 + it / 100.0) }

        val appendix = report(daily).section(ReportPart.WEIGHT).appendix

        assertEquals(91, appendix.size)
        assertEquals(ReportText.Day(today.minusDays(1)), appendix.first().label)
        assertEquals(ReportText.Day(today.minusDays(90)), appendix[89].label)
        assertEquals(res(R.string.report_weigh_ins_earlier), appendix.last().label)
        assertEquals(ReportText.Plain("10"), appendix.last().value)
    }

    private fun clock(entry: JournalEntry): String {
        val time = Instant.ofEpochMilli(entry.occurredAt).atZone(zone).toLocalTime()
        return clockText(time.hour * 60 + time.minute)
    }
}
