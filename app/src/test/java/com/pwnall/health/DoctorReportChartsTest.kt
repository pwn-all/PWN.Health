package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * The charts drawn over the sections of the doctor's sheet, each held to the rows under it.
 *
 * A chart is read faster than the rows and trusted more, so every case here is one where a picture
 * could say something the section does not: a day nobody carried a phone drawn as a day in bed, a
 * diastolic shaded inside the systolic band, a pound chart over kilogram rows, a band the Reference
 * row never printed, an act drawn on the cycle chart, a section the person kept off the sheet
 * turning up as a line.
 */
class DoctorReportChartsTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val from: LocalDate = today.minusMonths(3)
    private val zone: ZoneId = ZoneOffset.UTC

    private fun profile(sex: String = "MALE") =
        UserProfile(sex = sex, weightKg = 80.0, heightCm = 180, birthDate = "1985-04-10")

    private fun ago(days: Long): LocalDate = today.minusDays(days)

    private fun at(day: LocalDate, minute: Int = 9 * 60): Long =
        day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun entry(
        day: LocalDate,
        kind: String,
        minute: Int = 9 * 60,
        serving: Double = 0.0,
        payload: String? = null,
    ) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, minute),
        kind = kind,
        title = kind,
        calories = 0.0,
        servingG = serving,
        measurementPayloadJson = payload,
    )

    private fun measurement(day: LocalDate, payload: String? = null, weightKg: Double? = null, pulse: Int? = null) =
        entry(day, "MEASUREMENT", payload = payload).copy(
            measurementWeightKg = weightKg,
            measurementHeartRateBpm = pulse,
        )

    /** A reading of one payload key, the way a cuff, an oximeter, a thermometer or a tape writes one. */
    private fun vital(day: LocalDate, key: String, value: Number) =
        measurement(day, """{"schema":1,"$key":$value}""")

    private fun pressure(day: LocalDate, systolic: Int, diastolic: Int) =
        measurement(day, """{"schema":1,"systolicMmhg":$systolic,"diastolicMmhg":$diastolic}""")

    private fun meal(day: LocalDate, kcal: Double, minute: Int = 9 * 60, caffeine: Double? = null) =
        entry(day, "EAT", minute).copy(calories = kcal, caffeineMg = caffeine)

    private fun mood(day: LocalDate, score: Int, energy: Int? = null, minute: Int = 9 * 60) =
        entry(day, KIND_MOOD, minute).copy(moodScore = score, energyScore = energy)

    private fun sleep(day: LocalDate, minutes: Int) =
        entry(day, "MEASUREMENT").copy(sleepStartAt = at(day) - minutes * 60_000L, sleepEndAt = at(day))

    private fun workout(day: LocalDate, minutes: Double?, minute: Int = 10 * 60) =
        entry(day, "ACTIVITY", minute).copy(durationMinutes = minutes)

    private fun logged(day: LocalDate, log: CycleLog) =
        entry(day, KIND_CYCLE_LOG, payload = """{"$CYCLE_PAYLOAD_KEY":"${log.name}"}""")

    private fun course(id: Long, start: LocalDate) = Medication(
        id = id,
        name = "Course $id",
        dose = "1 tablet",
        times = "480",
        startDay = start.toString(),
        createdAt = 0,
    )

    private fun taken(courseId: Long, day: LocalDate) =
        entry(day, KIND_MED, 480, payload = """{"$MED_COURSE_KEY":$courseId,"$MED_SLOT_KEY":"480"}""")

    private fun report(
        entries: List<JournalEntry>,
        profile: UserProfile = profile(),
        steps: List<DaySteps> = emptyList(),
        medications: List<Medication> = emptyList(),
        from: LocalDate = this.from,
        country: CountryProfile = CountryProfileRegistry.international,
        parts: Set<ReportPart>? = null,
        sexualDetail: SexualDetail = SexualDetail.SUMMARY,
    ) = doctorReport(
        sources = ReportSources(profile, entries, steps, medications),
        request = ReportRequest(from = from, to = today, country = country, sexualDetail = sexualDetail).let {
            if (parts == null) it else it.copy(parts = parts)
        },
        today = today,
        zone = zone,
    )

    private fun DoctorReport.section(part: ReportPart): ReportSection =
        sections.single { it.part == part && it.presence == SectionPresence.PRESENT }

    private fun ReportChart.points(series: Int = 0): List<Pair<LocalDate, Double>> =
        this.series[series].points.map { it.day to it.value }

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    /** The figures of the one Reference row a section prints, as the strings it prints them as. */
    private fun ReportSection.printedRange(): List<Any> {
        val reference = rows.single { it.label == res(R.string.report_reference) }.value as ReportText.Res
        return (reference.args.first() as ReportText.Res).args
    }

    private fun assertPoints(expected: List<Pair<LocalDate, Double>>, actual: List<Pair<LocalDate, Double>>) {
        assertEquals(expected.map { it.first }, actual.map { it.first })
        expected.zip(actual).forEach { (want, got) -> assertEquals(want.second, got.second, 1e-9) }
    }

    // --- weight -----------------------------------------------------------------------------------

    /**
     * Every weigh-in, two on one day included, in the order they were taken — and in pounds on a
     * sheet laid out in pounds, since the rows beside the chart are. One weigh-in is a row, not a
     * chart, and nor are two taken on the same morning.
     */
    @Test
    fun everyWeighInIsDrawnOnTheSheetsScale() {
        val entries = listOf(
            measurement(ago(40), weightKg = 90.0),
            measurement(ago(20), weightKg = 86.0),
            measurement(ago(20), weightKg = 86.5).copy(occurredAt = at(ago(20), 19 * 60)),
            measurement(today, weightKg = 88.0),
        )

        val chart = report(entries).section(ReportPart.WEIGHT).charts.single()
        assertEquals(res(R.string.unit_kg), chart.unit)
        assertEquals(ChartStyle.LINE_DOTS, chart.series.single().style)
        assertEquals(from to today, chart.from to chart.to)
        assertPoints(listOf(ago(40) to 90.0, ago(20) to 86.0, ago(20) to 86.5, today to 88.0), chart.points())

        val us = report(entries, country = UnitedStatesProfile).section(ReportPart.WEIGHT).charts.single()
        assertEquals(res(R.string.unit_lb), us.unit)
        val pounds = listOf(90.0, 86.0, 86.5, 88.0).map(::imperialWeight)
        assertPoints(listOf(ago(40), ago(20), ago(20), today).zip(pounds), us.points())

        assertTrue(report(entries.take(1)).section(ReportPart.WEIGHT).charts.isEmpty())
        assertTrue(report(entries.subList(1, 3)).section(ReportPart.WEIGHT).charts.isEmpty())
    }

    // --- vitals -----------------------------------------------------------------------------------

    /**
     * A pressure is two charts, each against the half of the band its Reference row prints: a
     * diastolic of 95 shaded inside the systolic's 91–139 would sit inside a range it is above.
     */
    @Test
    fun aPressureIsTwoChartsEachAgainstItsOwnPrintedEdge() {
        val section = report(
            listOf(pressure(ago(3), 150, 95), pressure(ago(2), 128, 84), pressure(ago(1), 118, 76)),
        ).section(ReportPart.PRESSURE)

        val (systolic, diastolic) = section.charts
        assertEquals(res(R.string.direct_bluetooth_systolic), systolic.title)
        assertEquals(res(R.string.direct_bluetooth_diastolic), diastolic.title)
        assertTrue(section.charts.all { it.width == ChartWidth.HALF && it.unit == res(R.string.vitals_unit_mmhg) })
        assertPoints(listOf(ago(3) to 150.0, ago(2) to 128.0, ago(1) to 118.0), systolic.points())
        assertPoints(listOf(ago(3) to 95.0, ago(2) to 84.0, ago(1) to 76.0), diastolic.points())
        assertEquals(ChartBand(91.0, 139.0), systolic.band)
        assertEquals(ChartBand(null, 89.0), diastolic.band)
        // "91 – 139/89", edge for edge.
        val upper = systolic.band!!
        val lower = diastolic.band!!
        val top = "${upper.high!!.pretty()}/${lower.high!!.pretty()}"
        assertEquals(listOf(upper.low!!.pretty(), top), section.printedRange())
    }

    /** A pulse is dots under the band its row prints; the variability beside it has none, and each is named. */
    @Test
    fun aPulseIsDotsUnderItsPrintedBandAndTheVariabilityBesideIt() {
        val section = report(
            listOf(
                measurement(ago(3), pulse = 62),
                measurement(ago(2), pulse = 70),
                measurement(ago(1), pulse = 58),
                vital(ago(3), "hrvRmssdMs", 41),
                vital(ago(1), "hrvRmssdMs", 45),
            ),
        ).section(ReportPart.PULSE)

        val (pulse, hrv) = section.charts
        assertEquals(res(Vital.HEART_RATE.titleRes), pulse.title)
        assertEquals(res(Vital.HRV.titleRes), hrv.title)
        assertEquals(ChartStyle.DOTS, pulse.series.single().style)
        assertPoints(listOf(ago(3) to 62.0, ago(2) to 70.0, ago(1) to 58.0), pulse.points())
        assertEquals(ChartBand(60.0, 100.0), pulse.band)
        assertEquals(listOf("60", "100"), section.printedRange())
        assertNull(hrv.band)
        assertTrue(section.charts.all { it.width == ChartWidth.HALF })
    }

    /**
     * A saturation is drawn under its one-sided band and never above 100; a temperature under the
     * core band only where its row prints that band — not at the armpit — and in Fahrenheit, band
     * and all, on a sheet laid out in Fahrenheit.
     */
    @Test
    fun oxygenAndTemperatureCarryTheBandTheirRowsPrintAndNoOther() {
        val oxygen = report(listOf(97, 95, 93).mapIndexed { i, spo2 -> vital(ago(3L - i), "spo2Percent", spo2) })
            .section(ReportPart.OXYGEN)
        val saturation = oxygen.charts.single()
        assertEquals(ChartBand(95.0, null), saturation.band)
        assertEquals(listOf("95"), oxygen.printedRange())
        assertEquals(100.0, saturation.yCeiling!!, 0.0)
        assertEquals(ChartWidth.FULL, saturation.width)

        val core = listOf(vital(ago(2), "temperatureCelsius", 36.6), vital(ago(1), "temperatureCelsius", 37.2))
        val celsius = report(core).section(ReportPart.TEMPERATURE)
        assertEquals(ChartBand(35.0, 37.9), celsius.charts.single().band)
        assertEquals(listOf("35", "37.9"), celsius.printedRange())

        val us = report(core, country = UnitedStatesProfile).section(ReportPart.TEMPERATURE)
        val fahrenheit = us.charts.single()
        assertEquals(res(R.string.vitals_unit_fahrenheit), fahrenheit.unit)
        val degrees = listOf(36.6, 37.2).map { temperatureValue(it, true) }
        assertPoints(listOf(ago(2), ago(1)).zip(degrees), fahrenheit.points())
        val band = fahrenheit.band!!
        assertEquals(listOf(band.low!!.pretty(), band.high!!.pretty()), us.printedRange())

        val armpit = listOf(37.0, 37.4).mapIndexed { i, c ->
            measurement(ago(2L - i), """{"schema":1,"temperatureCelsius":$c,"type":"ARMPIT"}""")
        }
        assertNull(report(armpit).section(ReportPart.TEMPERATURE).charts.single().band)
    }

    /**
     * The Reference row prints the band of the newest reading; the chart shades it behind every
     * reading, so it shades it only where it is the band of every one. An armpit reading before a
     * core one would sit inside the core fever band the app declines to read it by, and a pressure
     * taken at seventeen inside the adult band the app withholds below eighteen.
     */
    @Test
    fun aBandIsShadedOnlyWhereItIsTheBandOfEveryReadingDrawn() {
        val temperatures = listOf(
            measurement(ago(2), """{"schema":1,"temperatureCelsius":37.6,"type":"ARMPIT"}"""),
            vital(ago(1), "temperatureCelsius", 36.8),
        )
        val temperature = report(temperatures).section(ReportPart.TEMPERATURE)
        assertEquals(listOf("35", "37.9"), temperature.printedRange())
        assertPoints(listOf(ago(2) to 37.6, ago(1) to 36.8), temperature.charts.single().points())
        assertNull(temperature.charts.single().band)

        val eighteen = profile().copy(birthDate = today.minusYears(18).minusDays(10).toString())
        val pressure = report(listOf(pressure(ago(20), 128, 84), pressure(ago(1), 124, 80)), profile = eighteen)
            .section(ReportPart.PRESSURE)
        assertEquals(2, pressure.printedRange().size)
        assertEquals(listOf(null, null), pressure.charts.map { it.band })
    }

    /** A tape measure on a sheet in inches: the waist in inches, under its band's last inch inside. */
    @Test
    fun aWaistInInchesIsDrawnUnderItsBandInInches() {
        val section = report(
            listOf(vital(ago(20), "waistCircumferenceCm", 96), vital(ago(10), "waistCircumferenceCm", 95)),
            country = UnitedStatesProfile,
        ).section(ReportPart.GIRTH)

        val waist = section.charts.single()
        assertEquals(res(R.string.unit_in), waist.unit)
        assertEquals(ChartWidth.FULL, waist.width)
        assertPoints(listOf(ago(20) to imperialGirth(96.0), ago(10) to imperialGirth(95.0)), waist.points())
        assertEquals(ChartBand(null, imperialGirth(93.0)), waist.band)
        assertEquals(listOf(waist.band!!.high!!.pretty()), section.printedRange())
    }

    // --- activity ---------------------------------------------------------------------------------

    /**
     * A day held at 0 and a day with no count at all are both left out of the bars, as they are
     * left out of the mean printed under them; a day handed in twice is its larger count.
     */
    @Test
    fun stepsLeaveOutTheZeroAndTheMissingDay() {
        val steps = listOf(
            DaySteps(ago(1).toString(), 5_000),
            DaySteps(ago(2).toString(), 0),
            DaySteps(ago(4).toString(), 8_000),
            DaySteps(ago(4).toString(), 7_000),
        )

        val chart = report(emptyList(), steps = steps).section(ReportPart.ACTIVITY).charts.single()
        assertEquals(ChartStyle.BARS, chart.series.single().style)
        assertEquals(0.0, chart.yFloor!!, 0.0)
        assertEquals(res(R.string.report_per_day_rate, res(R.string.steps)), chart.unit)
        assertPoints(listOf(ago(4) to 8_000.0, ago(1) to 5_000.0), chart.points())
    }

    /** Over half a year it is each week's mean of the days counted — a zero day still not among them. */
    @Test
    fun aLongPeriodDrawsEachWeeksMeanOfTheDaysCounted() {
        val steps = listOf(
            DaySteps(ago(1).toString(), 4_000),
            DaySteps(ago(2).toString(), 6_000),
            DaySteps(ago(3).toString(), 0),
            DaySteps(ago(8).toString(), 10_000),
        )

        val chart = report(emptyList(), steps = steps, from = today.minusMonths(6))
            .section(ReportPart.ACTIVITY).charts.single()
        val series = chart.series.single()
        assertEquals(ChartStyle.LINE, series.style)
        assertNull(chart.yFloor)
        assertEquals(res(R.string.report_chart_week_mean), series.label)
        assertPoints(listOf(ago(7) to 10_000.0, today to 5_000.0), chart.points())
    }

    /** Workouts are the timed minutes a week; a session with no length makes no week of its own. */
    @Test
    fun workoutsAreTheirTimedMinutesAWeek() {
        val entries = listOf(
            workout(ago(1), 30.0),
            workout(ago(2), 45.0, minute = 18 * 60),
            workout(ago(9), 20.0),
            workout(ago(16), null),
        )

        val charts = report(entries).section(ReportPart.ACTIVITY).charts
        val minutes = charts.single()
        assertEquals(res(R.string.home_workouts), minutes.title)
        assertEquals(res(R.string.report_changes_per_week, res(R.string.unit_minutes)), minutes.unit)
        assertPoints(listOf(ago(7) to 20.0, today to 75.0), minutes.points())
        assertEquals(0.0, minutes.yFloor!!, 0.0)
    }

    /**
     * A week is summed only where the period holds all seven of its days. Three months back from
     * 5 September are 92 days, so the oldest week counted back from the last day holds two: summed
     * and drawn a week tall it would be a light week that was only a short one. Its days still
     * count in the rows.
     */
    @Test
    fun aSummedWeekIsDrawnOnlyWhereThePeriodHoldsItWhole() {
        assertEquals(92L, ChronoUnit.DAYS.between(from, today))
        val entries = listOf(
            entry(from, KIND_ALCOHOL, serving = 40.0),
            entry(from.plusDays(1), KIND_ALCOHOL, serving = 20.0),
            entry(from.plusDays(2), KIND_ALCOHOL, serving = 10.0),
            entry(ago(1), KIND_ALCOHOL, serving = 14.0),
            workout(from.plusDays(1), 60.0),
            workout(from.plusDays(2), 30.0),
            workout(ago(1), 45.0),
        )

        val sheet = report(entries)
        val alcohol = sheet.section(ReportPart.SOCIAL).charts.single()
        assertPoints(listOf(ago(84) to 10.0, today to 14.0), alcohol.points())
        assertEquals(84.0, habitsOver(entries).ethanolG, 1e-9)
        val minutes = sheet.section(ReportPart.ACTIVITY).charts.single()
        assertPoints(listOf(ago(84) to 30.0, today to 45.0), minutes.points())
    }

    // --- sleep ------------------------------------------------------------------------------------

    /**
     * A night is a dot at its hours, and the line through them is the mean of the nights logged in
     * the seven days up to each — a gap in the diary is not a week of short nights.
     */
    @Test
    fun sleepIsANightADotAndTheMeanOfTheWeekLogged() {
        val section = report(listOf(sleep(ago(10), 420), sleep(ago(9), 480), sleep(ago(2), 360)))
            .section(ReportPart.SLEEP)

        val chart = section.charts.single()
        assertEquals(res(R.string.report_chart_hours_night), chart.unit)
        assertNull(chart.band)
        assertEquals(ChartStyle.DOTS, chart.series[0].style)
        assertPoints(listOf(ago(10) to 7.0, ago(9) to 8.0, ago(2) to 6.0), chart.points(0))
        assertEquals(ReportText.Quantity(R.plurals.report_chart_sleep_mean, 7), chart.series[1].label)
        assertPoints(listOf(ago(10) to 7.0, ago(9) to 7.5, ago(2) to 6.0), chart.points(1))
        // The nights the rows count, and no others.
        assertEquals(ReportText.Quantity(R.plurals.count_nights, chart.points(0).size), section.rows.first().value)
    }

    // --- nutrition --------------------------------------------------------------------------------

    /**
     * Food energy on the full days only, as the average day is; water and caffeine on the days any
     * was logged, a cup and a meal's own milligrams added into one day.
     */
    @Test
    fun foodIsDrawnOnFullDaysAndDrinksOnTheDaysLogged() {
        val entries = listOf(
            meal(ago(1), 1_000.0, caffeine = 40.0),
            meal(ago(1), 800.0, minute = 19 * 60),
            meal(ago(2), 1_000.0, caffeine = 60.0),
            meal(ago(2), 1_000.0, minute = 19 * 60),
            meal(ago(3), 500.0),
            entry(ago(1), KIND_WATER, serving = 250.0),
            entry(ago(1), KIND_WATER, minute = 15 * 60, serving = 250.0),
            entry(ago(5), KIND_WATER, serving = 300.0),
            entry(ago(1), KIND_CAFFEINE, serving = 80.0),
        )

        val (food, water, caffeine) = report(entries).section(ReportPart.NUTRITION).charts
        assertEquals(ReportText.Quantity(R.plurals.report_changes_food, LAB_DIET_MIN_MEALS_PER_DAY), food.title)
        assertEquals(ChartWidth.FULL, food.width)
        assertPoints(listOf(ago(2) to 2_000.0, ago(1) to 1_800.0), food.points())
        assertEquals(res(R.string.report_per_day_rate, res(R.string.unit_ml)), water.unit)
        assertPoints(listOf(ago(5) to 300.0, ago(1) to 500.0), water.points())
        assertPoints(listOf(ago(2) to 60.0, ago(1) to 120.0), caffeine.points())
        assertTrue(listOf(water, caffeine).all { it.width == ChartWidth.HALF })
        assertTrue(listOf(food, water, caffeine).all { it.yFloor == 0.0 })
    }

    // --- mood -------------------------------------------------------------------------------------

    /**
     * Each day's own mean on the five points, the scale's ends fixed; and the second dial a week at
     * a time only once the row for it is printed — seven answered days — as the days answered wound
     * up or restless out of the days answered.
     */
    @Test
    fun moodIsEachDaysMeanAndTheSecondDialAWeekAtATime() {
        val energies = listOf(1, 4, 2, 5, 3, 3, 2, 4, 4)
        val entries = energies.mapIndexed { i, energy -> mood(ago(i + 1L), score = 3, energy = energy) } +
            mood(ago(1), score = 5, minute = 15 * 60)

        val (daily, woundUp) = report(entries).section(ReportPart.MOOD).charts
        assertEquals(1.0 to 5.0, daily.yFloor to daily.yCeiling)
        assertEquals(4.0, daily.points().last().second, 1e-9)
        assertEquals((1..9).map { ago(it.toLong()) }.reversed(), daily.points().map { it.first })
        assertEquals(res(R.string.report_mood_wound_up), woundUp.title)
        assertEquals(0.0 to 100.0, woundUp.yFloor to woundUp.yCeiling)
        // The week to today holds six answered days, two of them wound up; the one before, three and one.
        assertPoints(listOf(ago(7) to 100.0 / 3, today to 200.0 / 6), woundUp.points())
        assertEquals(
            woundUpDays(entries),
            listOf(3, 6).zip(woundUp.points()).sumOf { (days, point) -> (days * point.second / 100).roundToInt() },
        )

        val fewer = entries.filter { it.day >= ago(6).toString() }
        assertEquals(1, report(fewer).section(ReportPart.MOOD).charts.size)
    }

    // --- social and bathroom ----------------------------------------------------------------------

    /**
     * Grams of ethanol and cigarette equivalents, summed a week over the weeks something was logged,
     * so the bars add up to the totals the rows print where nothing falls in the short week the
     * period starts with — see [aSummedWeekIsDrawnOnlyWhereThePeriodHoldsItWhole].
     */
    @Test
    fun alcoholAndTobaccoAreSummedAWeek() {
        val entries = listOf(
            entry(ago(1), KIND_ALCOHOL, serving = 20.0),
            entry(ago(3), KIND_ALCOHOL, serving = 10.0),
            entry(ago(8), KIND_ALCOHOL, serving = 14.0),
            entry(ago(2), KIND_SMOKING, serving = 1.6),
            entry(ago(15), KIND_SMOKING, serving = 0.8),
        )

        val (alcohol, tobacco) = report(entries).section(ReportPart.SOCIAL).charts
        assertEquals(res(R.string.alcohol_title), alcohol.title)
        assertEquals(res(R.string.report_changes_per_week, res(R.string.unit_grams)), alcohol.unit)
        assertPoints(listOf(ago(7) to 14.0, today to 30.0), alcohol.points())
        assertEquals(res(R.string.report_changes_per_week, res(R.string.report_chart_cigarettes)), tobacco.unit)
        assertPoints(listOf(ago(14) to 1.0, today to 2.0), tobacco.points())
        assertTrue(listOf(alcohol, tobacco).all { it.yFloor == 0.0 })
        val habits = habitsOver(entries)
        assertEquals(habits.ethanolG, alcohol.points().sumOf { it.second }, 1e-9)
        assertEquals(habits.cigaretteEquivalents, tobacco.points().sumOf { it.second }, 1e-9)
    }

    /**
     * Stools on every day the bathroom log was used, and urinations on the days one was logged — a
     * day with a stool and no urination is a bladder diary not kept, never a dot at nought — so the
     * mean of each series is the rate its row prints, over the days printed beside it; a day with
     * neither logged is not on the chart.
     */
    @Test
    fun bathroomDaysCarryBothKindsAndTheirMeanIsThePrintedRate() {
        val entries = listOf(
            entry(ago(1), KIND_STOOL),
            entry(ago(1), KIND_STOOL, minute = 20 * 60),
            entry(ago(3), KIND_STOOL),
        ) + (0 until 5).map { entry(ago(1), KIND_URINATION, minute = 8 * 60 + it) } +
            (0 until 4).map { entry(ago(2), KIND_URINATION, minute = 8 * 60 + it) }

        val section = report(entries).section(ReportPart.BOWEL)
        val chart = section.charts.single()
        assertEquals(listOf(res(R.string.stool_title), res(R.string.urination_title)), chart.series.map { it.label })
        assertPoints(listOf(ago(3) to 1.0, ago(2) to 0.0, ago(1) to 2.0), chart.points(0))
        assertPoints(listOf(ago(2) to 4.0, ago(1) to 5.0), chart.points(1))
        listOf(R.string.stool_title, R.string.urination_title).forEachIndexed { i, label ->
            val note = section.rows.single { it.label == res(label) }.note as ReportText.Join
            val rate = (note.parts.first() as ReportText.Res).args.single()
            assertEquals(rate, chart.points(i).map { it.second }.average().pretty())
            assertEquals(ReportText.Quantity(R.plurals.count_days, chart.points(i).size), note.parts.last())
        }
    }

    // --- cycle and medications --------------------------------------------------------------------

    /**
     * The cycles the section counts, each at the day it began — and nothing of the acts logged
     * alongside, which are not this section's.
     */
    @Test
    fun theCycleChartIsTheCyclesCountedAndNoAct() {
        val starts = listOf(ago(90), ago(62), ago(33), ago(5)).map { entry(it, KIND_CYCLE) }
        val acts = listOf(logged(ago(50), CycleLog.SEX_PROTECTED), logged(ago(20), CycleLog.SEX_UNPROTECTED))

        val section = report(starts + acts, profile = profile("FEMALE")).section(ReportPart.CYCLE)
        val chart = section.charts.single()
        assertEquals(res(R.string.unit_days), chart.unit)
        assertPoints(listOf(ago(90) to 28.0, ago(62) to 29.0, ago(33) to 28.0), chart.points())
        assertEquals(
            ReportText.Quantity(R.plurals.count_cycles, chart.points().size),
            section.rows.single { it.label == res(R.string.report_cycles) }.value,
        )
        assertEquals(section, report(starts, profile = profile("FEMALE")).section(ReportPart.CYCLE))
    }

    /**
     * A course with its doses marked is drawn a week at a time, half a page wide, the day it started
     * marked; one never marked has no chart, as it has no percentage.
     */
    @Test
    fun adherenceIsAWeekAtATimeFromTheDayACourseStarted() {
        val started = course(7, ago(20))
        val unmarked = course(8, from.minusDays(10))
        val marks = (14L..20L).map { taken(7, ago(it)) } + (11L..13L).map { taken(7, ago(it)) }

        val section = report(marks, medications = listOf(started, unmarked)).section(ReportPart.MEDICATIONS)
        val chart = section.charts.single()
        assertEquals(ReportText.Plain("Course 7"), chart.title)
        assertEquals(ChartWidth.HALF, chart.width)
        assertEquals(0.0 to 100.0, chart.yFloor to chart.yCeiling)
        assertEquals(listOf(ChartMark(ago(20), res(R.string.report_chart_course_start))), chart.marks)
        assertPoints(listOf(ago(14) to 100.0, ago(7) to 300.0 / 7, today to 0.0), chart.points())
    }

    // --- what the person chose ---------------------------------------------------------------------

    /**
     * Acts and solo entries logged beside everything else change no chart on the sheet, whether or
     * not the sensitive section itself was sent — and that section draws none.
     */
    @Test
    fun sensitiveEntriesChangeNoChart() {
        val record = listOf(ago(60), ago(32), ago(4)).map { entry(it, KIND_CYCLE) } +
            (1L..9L).map { mood(ago(it), score = 3, energy = 2) } +
            listOf(measurement(ago(2), pulse = 64), measurement(ago(1), pulse = 66)) +
            listOf(entry(ago(1), KIND_STOOL), entry(ago(2), KIND_URINATION))
        val sensitive = listOf(logged(ago(3), CycleLog.SEX_PROTECTED), entry(ago(2), KIND_MASTURBATION)) +
            (10L..30L step 5).map { logged(ago(it), CycleLog.SEX_UNPROTECTED) }
        fun charts(report: DoctorReport) =
            report.sections.filter { it.charts.isNotEmpty() }.associate { it.part to it.charts }

        val female = profile("FEMALE")
        val without = charts(report(record, profile = female))
        assertEquals(setOf(ReportPart.CYCLE, ReportPart.MOOD, ReportPart.PULSE, ReportPart.BOWEL), without.keys)
        assertEquals(without, charts(report(record + sensitive, profile = female)))

        val sent = report(
            record + sensitive,
            profile = female,
            parts = ReportPart.entries.toSet(),
            sexualDetail = SexualDetail.DETAILED,
        )
        assertTrue(sent.section(ReportPart.SEXUAL_HEALTH).charts.isEmpty())
        assertEquals(without, charts(sent))
    }

    /** A section the person did not tick is not on the sheet, and nor is any chart of it. */
    @Test
    fun anUntickedSectionTakesItsChartsWithIt() {
        val entries = listOf(
            measurement(ago(3), weightKg = 80.0),
            measurement(ago(1), weightKg = 79.0),
            measurement(ago(3), pulse = 60),
            measurement(ago(1), pulse = 62),
        ) + (1L..4L).map { mood(ago(it), score = 4) }
        val steps = listOf(DaySteps(ago(2).toString(), 6_000), DaySteps(ago(1).toString(), 7_000))

        val onlyWeight = report(entries, steps = steps, parts = setOf(ReportPart.WEIGHT))
        assertEquals(listOf(ReportPart.WEIGHT), onlyWeight.sections.filter { it.charts.isNotEmpty() }.map { it.part })

        val noWeight = report(entries, steps = steps, parts = ReportPart.entries.toSet() - ReportPart.WEIGHT)
        assertTrue(noWeight.sections.none { it.part == ReportPart.WEIGHT })
        assertTrue(noWeight.sections.flatMap { it.charts }.none { it.unit == res(R.string.unit_kg) })
        assertEquals(
            setOf(ReportPart.PULSE, ReportPart.MOOD, ReportPart.ACTIVITY),
            noWeight.sections.filter { it.charts.isNotEmpty() }.map { it.part }.toSet(),
        )
    }
}
