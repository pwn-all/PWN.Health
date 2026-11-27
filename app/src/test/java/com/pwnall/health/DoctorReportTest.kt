package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The report a doctor is handed, built over a hand-made journal.
 *
 * Plain JUnit on purpose: the builder holds no Resources and no Context, and every word in the sheet
 * is a reference the renderer resolves later — which is what lets the decisions be pinned here.
 * Every case is one a person would notice on paper: a reading from before the period on the sheet,
 * a blood pressure averaged apart, a percentage the medicine chart would disagree with, a heading
 * over nothing, a child graded by an adult's BMI band.
 */
class DoctorReportTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val from: LocalDate = today.minusMonths(3)
    private val zone: ZoneId = ZoneOffset.UTC

    private fun profile(sex: String = "MALE", birthDate: String = "1985-04-10", unitSystem: String = "METRIC") =
        UserProfile(sex = sex, weightKg = 80.0, heightCm = 180, birthDate = birthDate, unitSystem = unitSystem)

    private fun at(day: LocalDate, minute: Int = 9 * 60): Long =
        day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun measurement(
        day: LocalDate,
        payload: String? = null,
        weightKg: Double? = null,
        pulse: Int? = null,
    ) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = "MEASUREMENT",
        title = "measurement",
        calories = 0.0,
        measurementWeightKg = weightKg,
        measurementHeartRateBpm = pulse,
        measurementPayloadJson = payload,
    )

    private fun pressure(day: LocalDate, systolic: Int, diastolic: Int) =
        measurement(day, """{"schema":1,"systolicMmhg":$systolic,"diastolicMmhg":$diastolic}""")

    /** A laboratory panel holding a fasting glucose: the blood-test section prints panels, not meters. */
    private fun glucose(day: LocalDate, mmol: Double) =
        measurement(day, labsPayload(mapOf(LabMarker.GLUCOSE to mmol), fasting = true)).copy(kind = KIND_LABS)

    private fun sleep(day: LocalDate, minutes: Int) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = "MEASUREMENT",
        title = "sleep",
        calories = 0.0,
        sleepStartAt = at(day) - minutes * 60_000L,
        sleepEndAt = at(day),
    )

    private fun cycleStart(day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_CYCLE,
        title = "cycle",
        calories = 0.0,
    )

    private fun course(
        id: Long = 1,
        times: String = "480,1200",
        start: LocalDate = from.minusDays(30),
    ) = Medication(
        id = id,
        name = "Metformin",
        dose = "1 tablet",
        times = times,
        startDay = start.toString(),
        createdAt = 0,
    )

    private fun taken(courseId: Long, slot: String, day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, slot.toInt()),
        kind = KIND_MED,
        title = "Metformin",
        calories = 0.0,
        measurementPayloadJson = """{"$MED_COURSE_KEY":$courseId,"$MED_SLOT_KEY":"$slot"}""",
    )

    private fun report(
        entries: List<JournalEntry>,
        profile: UserProfile = profile(),
        days: List<DaySteps> = emptyList(),
        medications: List<Medication> = emptyList(),
        from: LocalDate = this.from,
    ) = doctorReport(
        sources = ReportSources(profile, entries, days, medications),
        request = ReportRequest(from = from, to = today),
        today = today,
        zone = zone,
    )

    private fun DoctorReport.section(part: ReportPart): ReportSection? = sections.firstOrNull {
        it.part == part &&
            it.presence == SectionPresence.PRESENT
    }

    /** The sections actually carrying something: an empty one is on the sheet, saying it is empty. */
    private fun DoctorReport.printedParts(): List<ReportPart> =
        sections.filter { it.presence == SectionPresence.PRESENT }.map(ReportSection::part)

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    // --- the period -------------------------------------------------------------------------------

    /** A reading from the day before the window is not on the sheet, and neither is a day of steps. */
    @Test
    fun onlyRowsInsideThePeriodAreCounted() {
        val entries = listOf(
            measurement(from.minusDays(1), pulse = 70),
            measurement(from, pulse = 72),
            measurement(today, pulse = 68),
            measurement(today.plusDays(1), pulse = 90),
        )
        val days = listOf(
            DaySteps(from.minusDays(1).toString(), 20_000),
            DaySteps(from.toString(), 8_000),
            DaySteps(today.toString(), 6_000),
        )

        val built = report(entries, days = days)

        val readings = built.section(ReportPart.PULSE)!!.rows[1]
        assertEquals(ReportText.Quantity(R.plurals.count_readings, 2), readings.value)
        val steps = built.section(ReportPart.ACTIVITY)!!.rows.single()
        assertEquals(res(R.string.report_steps_per_day, 7_000L.sheetCount()), steps.value)
    }

    // --- a pressure stays a pair ------------------------------------------------------------------

    /**
     * The lowest and the highest are whole readings, printed with the diastolic measured alongside,
     * and the mean is both halves averaged over the same two squeezes: 118/76 and 128/84 average to
     * 123/80, not to a systolic from one reading over a diastolic from another.
     */
    @Test
    fun aBloodPressureIsPrintedAsWholePairs() {
        val entries = listOf(pressure(from.plusDays(1), 118, 76), pressure(today, 128, 84))

        val rows = report(entries).section(ReportPart.PRESSURE)!!.rows

        val unit = res(R.string.vitals_unit_mmhg)
        assertEquals(res(R.string.direct_bluetooth_blood_pressure_title), rows[0].label)
        assertEquals(res(R.string.report_value_unit, "128/84", unit), rows[0].value)
        assertEquals(res(R.string.report_min_mean_max, "118/76", "123/80", "128/84"), rows[1].note)
        assertTrue(rows[1].detail)
    }

    /** The latest reading carries its date and the verdict the vitals card would print beside it. */
    @Test
    fun theLatestReadingCarriesItsDateAndVerdict() {
        val rows = report(listOf(pressure(today, 150, 85))).section(ReportPart.PRESSURE)!!.rows

        assertEquals(
            ReportText.Join(
                listOf(
                    ReportText.Day(today),
                    res(R.string.provenance_manual),
                    res(R.string.vitals_status_above),
                ),
            ),
            rows[0].note,
        )
        assertEquals(res(R.string.report_reference), rows[2].label)
        assertEquals(res(R.string.vitals_source_blood_pressure), rows[2].note)
    }

    /** A child's pressure is shown and not graded, and the sheet names no band for it. */
    @Test
    fun aMinorGetsNoPressureBandAndNoVerdict() {
        val child = profile(birthDate = today.minusYears(12).toString())

        val built = report(listOf(pressure(today, 118, 78)), profile = child)

        val rows = built.section(ReportPart.PRESSURE)!!.rows
        assertEquals(2, rows.size)
        assertEquals(
            ReportText.Join(listOf(ReportText.Day(today), res(R.string.provenance_manual))),
            rows[0].note,
        )
        val source = res(
            R.string.report_note_band_source,
            res(R.string.direct_bluetooth_blood_pressure_title),
            res(R.string.vitals_source_blood_pressure),
        )
        assertFalse(built.notes.contains(source))
    }

    // --- adherence --------------------------------------------------------------------------------

    /**
     * Two doses a day over a week is fourteen asked for; ten marked is 71%, rounded the way the
     * medicine chart rounds it, and the row says both figures rather than the percentage alone.
     */
    @Test
    fun adherenceIsTheChartsOwnPercentage() {
        val weekAgo = today.minusDays(6)
        val marks = (0 until 5).flatMap { back ->
            val day = today.minusDays(back.toLong())
            listOf(taken(1, "480", day), taken(1, "1200", day))
        }

        val built = report(marks, medications = listOf(course()), from = weekAgo)

        val rows = built.section(ReportPart.MEDICATIONS)!!.rows
        assertEquals(ReportText.Plain("Metformin"), rows[0].label)
        assertEquals(res(R.string.report_course_dates), rows[1].label)
        assertEquals(res(R.string.med_chart_percent, 71), rows[2].value)
        assertEquals(res(R.string.report_doses_marked, 10, 14), rows[2].note)
        assertTrue(built.notes.contains(res(R.string.report_note_adherence)))
    }

    /** A course that ended before the window, or starts after it, was not running in it. */
    @Test
    fun aCourseOutsideThePeriodIsNotOnTheSheet() {
        val ended = course(id = 1, start = from.minusDays(60)).copy(endDay = from.minusDays(1).toString())
        val future = course(id = 2, start = today.plusDays(1))

        val built = report(emptyList(), medications = listOf(ended, future))

        assertNull(built.section(ReportPart.MEDICATIONS))
    }

    /** The pure sum behind the percentage: no dose asked for is no percentage, not a hundred. */
    @Test
    fun adherenceOverNothingIsNull() {
        assertNull(adherenceOf(listOf(MedDay(today, 0, 0))))
        assertEquals(Adherence(3, 4), adherenceOf(listOf(MedDay(today, 2, 1), MedDay(today.minusDays(1), 2, 2))))
        assertEquals(75, Adherence(3, 4).percent)
    }

    // --- empty sections ---------------------------------------------------------------------------

    /** A heading over "none" is left out: a journal of one pulse has a person and a vital, nothing else. */
    @Test
    fun sectionsWithNothingInThemAreOmitted() {
        val built = report(listOf(measurement(today, pulse = 64)))

        assertEquals(listOf(ReportPart.PERSON, ReportPart.PULSE), built.printedParts())
        assertFalse(built.empty)
    }

    /** No rows at all is still a report — the person — and the page can say the period is empty. */
    @Test
    fun anEmptyPeriodKeepsOnlyThePerson() {
        val built = report(emptyList())

        assertEquals(listOf(ReportPart.PERSON), built.printedParts())
        assertTrue(built.empty)
        assertTrue(built.filled.isEmpty())
        assertEquals(
            listOf(
                res(R.string.report_note_not_medical),
                res(R.string.report_note_bmi),
                res(R.string.report_note_chosen),
            ),
            built.notes,
        )
    }

    /**
     * Cycle rows on a male profile print nothing. On a female one a single start is still the date
     * of the last period — the one figure a gynaecologist writes down first — and only a second
     * start makes a cycle there is a length to count.
     */
    @Test
    fun theCycleSectionNeedsAFemaleProfile() {
        val starts = listOf(cycleStart(today.minusDays(56)), cycleStart(today.minusDays(28)), cycleStart(today))

        assertNull(report(starts).section(ReportPart.CYCLE))

        val oneStart = report(starts.take(1), profile = profile(sex = "FEMALE")).section(ReportPart.CYCLE)!!
        assertEquals(1, oneStart.rows.size)
        assertEquals(res(R.string.report_cycle_lmp), oneStart.rows.single().label)

        val rows = report(starts, profile = profile(sex = "FEMALE")).section(ReportPart.CYCLE)!!.rows
        assertEquals(res(R.string.report_cycle_lmp), rows[0].label)
        assertEquals(ReportText.Day(today), rows[0].value)
        assertEquals(ReportText.Quantity(R.plurals.count_cycles, 2), rows[1].value)
        assertEquals(ReportText.Quantity(R.plurals.count_days, 28), rows[2].value)
    }

    // --- the person -------------------------------------------------------------------------------

    /** An adult's BMI is graded by WHO's bands; a fifteen-year-old's is printed and left ungraded. */
    @Test
    fun aMinorGetsNoBmiVerdict() {
        val adult = report(emptyList()).section(ReportPart.PERSON)!!.rows.last()
        val minor = report(emptyList(), profile = profile(birthDate = today.minusYears(15).toString()))
            .section(ReportPart.PERSON)!!
            .rows
            .last()

        assertEquals(res(R.string.profile_bmi), adult.label)
        assertEquals(res(R.string.bmi_normal), adult.note)
        assertEquals(res(R.string.profile_bmi), minor.label)
        assertNull(minor.note)
        assertNull(bmiVerdictRes(24.7, today.minusYears(17).toString(), today))
        assertEquals(R.string.bmi_normal, bmiVerdictRes(24.7, today.minusYears(18).toString(), today))
    }

    /** The weight on the sheet is the period's latest weigh-in, dated, and the BMI is read off it. */
    @Test
    fun theLatestWeighInIsThePersonsWeight() {
        val entries = listOf(
            measurement(from.plusDays(2), weightKg = 82.0),
            measurement(today.minusDays(1), weightKg = 79.5),
        )

        val rows = report(entries).section(ReportPart.PERSON)!!.rows
        val weight = rows.first { it.label == res(R.string.weight) }

        assertEquals(res(R.string.report_value_unit, "79.5", res(R.string.unit_kg)), weight.value)
        assertEquals(ReportText.Day(today.minusDays(1)), weight.note)
    }

    /** With no weigh-in the profile's own figure stands, and the note says where it came from. */
    @Test
    fun withoutAWeighInTheProfileWeightIsUsed() {
        val weight = report(emptyList()).section(ReportPart.PERSON)!!.rows.first { it.label == res(R.string.weight) }

        assertEquals(res(R.string.report_value_unit, "80", res(R.string.unit_kg)), weight.value)
        assertEquals(res(R.string.report_weight_profile), weight.note)
    }

    /** Pounds and feet where the profile reads in them — the same figures the profile card prints. */
    @Test
    fun imperialProfilesReadInPoundsAndFeet() {
        val rows = report(emptyList(), profile = profile(unitSystem = "IMPERIAL")).section(ReportPart.PERSON)!!.rows

        assertEquals(ReportText.Plain(imperialHeight(180)), rows.first { it.label == res(R.string.height) }.value)
        assertEquals(
            res(R.string.report_value_unit, imperialWeight(80.0).pretty(), res(R.string.unit_lb)),
            rows.first { it.label == res(R.string.weight) }.value,
        )
    }

    /**
     * The thermometer on a sheet a doctor reads in Fahrenheit: the figure, the band under it and
     * the caption over both move together, and the grading is still the Celsius one — 38.2 °C is
     * a fever, so 100.8 °F is printed above a range ending at the 37.9 that is 100.2.
     */
    @Test
    fun imperialProfilesReadATemperatureInFahrenheit() {
        val entries = listOf(
            measurement(from.plusDays(1), """{"schema":1,"temperatureCelsius":36.6}"""),
            measurement(today, """{"schema":1,"temperatureCelsius":38.2}"""),
        )

        val rows = report(entries, profile = profile(unitSystem = "IMPERIAL")).section(ReportPart.TEMPERATURE)!!.rows

        val unit = res(R.string.vitals_unit_fahrenheit)
        assertEquals(res(R.string.report_value_unit, "100.8", unit), rows[0].value)
        assertEquals(
            ReportText.Join(
                listOf(
                    ReportText.Day(today),
                    res(R.string.provenance_manual),
                    res(R.string.vitals_status_above),
                ),
            ),
            rows[0].note,
        )
        assertEquals(res(R.string.report_min_mean_max, "97.9", "99.3", "100.8"), rows[1].note)
        assertEquals(
            res(R.string.report_value_unit, res(R.string.labs_norm_range, "95", "100.2"), unit),
            rows[2].value,
        )
    }

    /** A metric profile is left exactly where it was: the same figures, the same °C over them. */
    @Test
    fun metricProfilesKeepTheirCelsius() {
        val rows = report(
            listOf(measurement(today, """{"schema":1,"temperatureCelsius":38.2}""")),
        ).section(ReportPart.TEMPERATURE)!!.rows

        assertEquals(
            res(R.string.report_value_unit, "38.2", res(R.string.vitals_unit_celsius)),
            rows[0].value,
        )
    }

    /**
     * The scale writes kilograms of muscle and kilograms of water, and an imperial profile reads
     * both in pounds — beside a body weight this same sheet already prints in pounds.
     */
    @Test
    fun imperialProfilesReadMuscleAndWaterInPounds() {
        val entries = listOf(
            measurement(today, """{"schema":1,"muscleMassKg":32.5,"bodyWaterMassKg":41.0}"""),
        )

        val rows = report(entries, profile = profile(unitSystem = "IMPERIAL"))
            .section(ReportPart.BODY_COMPOSITION)!!
            .rows

        val pounds = res(R.string.unit_lb)
        val muscle = rows.first { it.label == res(R.string.direct_bluetooth_muscle) }
        val water = rows.first { it.label == res(R.string.direct_bluetooth_body_water) }
        assertEquals(res(R.string.report_value_unit, imperialWeight(32.5).pretty(), pounds), muscle.value)
        assertEquals(res(R.string.report_value_unit, imperialWeight(41.0).pretty(), pounds), water.value)
    }

    /** And a metric one still reads them in the kilograms the payload keys are named for. */
    @Test
    fun metricProfilesKeepMuscleAndWaterInKilograms() {
        val entries = listOf(measurement(today, """{"schema":1,"muscleMassKg":32.5}"""))

        val rows = report(entries).section(ReportPart.BODY_COMPOSITION)!!.rows

        assertEquals(
            res(R.string.report_value_unit, "32.5", res(R.string.unit_kg)),
            rows.first { it.label == res(R.string.direct_bluetooth_muscle) }.value,
        )
    }

    // --- weight trend -----------------------------------------------------------------------------

    @Test
    fun theWeightTrendRunsFirstToLatestWithTheChange() {
        val entries = listOf(
            measurement(from.plusDays(1), weightKg = 84.0),
            measurement(from.plusDays(30), weightKg = 85.0),
            measurement(today, weightKg = 82.2),
        )

        val rows = report(entries).section(ReportPart.WEIGHT)!!.rows
        val kg = res(R.string.unit_kg)

        assertEquals(res(R.string.report_value_unit, "84", kg), rows[0].value)
        assertEquals(res(R.string.report_value_unit, "82.2", kg), rows[1].value)
        assertEquals(res(R.string.report_value_unit, "82.2", kg), rows[2].value)
        assertEquals(res(R.string.report_value_unit, "85", kg), rows[3].value)
        assertEquals(res(R.string.report_value_unit, "−1.8", kg), rows[4].value)
    }

    // --- blood tests ------------------------------------------------------------------------------

    /** The newest result with its band, then three earlier ones newest first — never the fourth. */
    @Test
    fun aMarkerShowsItsLatestBandAndThreeEarlierResults() {
        val entries = (0 until 5).map { glucose(from.plusDays(10L * it), 5.0 + it * 0.5) }

        val built = report(entries)
        val rows = built.section(ReportPart.LABS)!!.rows
        val unit = res(R.string.labs_unit_mmol)

        assertEquals(res(R.string.labs_marker_glucose), rows[0].label)
        assertEquals(res(R.string.report_value_unit, "7", unit), rows[0].value)
        assertEquals(
            ReportText.Join(
                listOf(
                    ReportText.Day(from.plusDays(40)),
                    res(R.string.labs_fasting),
                    res(R.string.vitals_status_far_above),
                ),
            ),
            rows[0].note,
        )
        assertEquals(res(R.string.report_reference), rows[1].label)
        assertEquals(res(R.string.report_value_unit, "6.5", unit), rows[2].value)
        assertEquals(ReportText.Day(from.plusDays(30)), rows[2].label)
        assertEquals(res(R.string.report_value_unit, "5.5", unit), rows[4].value)
        assertEquals(5, rows.size)
        assertTrue(built.notes.contains(res(R.string.report_note_labs)))
    }

    // --- sleep ------------------------------------------------------------------------------------

    @Test
    fun sleepCountsNightsAndAveragesThem() {
        val entries = listOf(sleep(today.minusDays(2), 420), sleep(today.minusDays(1), 480))

        val built = report(entries)
        val rows = built.section(ReportPart.SLEEP)!!.rows

        assertEquals(ReportText.Quantity(R.plurals.count_nights, 2), rows[0].value)
        assertEquals(ReportText.Plain("7:30"), rows[1].value)
    }

    // --- the report's own shape -------------------------------------------------------------------

    @Test
    fun theReportCarriesItsPeriodAndOpensWithIt() {
        val built = report(emptyList())
        val first = built.section(ReportPart.PERSON)!!.rows.first()

        assertEquals(from, built.from)
        assertEquals(today, built.to)
        assertEquals(res(R.string.report_period_value, ReportText.Day(from), ReportText.Day(today)), first.value)
        assertNotNull(built.section(ReportPart.PERSON)!!.rows.first { it.label == res(R.string.report_age) })
    }
}
