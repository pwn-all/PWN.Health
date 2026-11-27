package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * The doctor's sheet at its edges: periods asked for backwards or a day at a time, figures sitting
 * exactly on a band, and the sections the preview's own counter cannot see.
 *
 * Plain JUnit, because the builder holds no Resources — every word on the sheet is a reference the
 * renderer resolves later, which is what lets these decisions be pinned without a device.
 */
class ReportsAndAdviceEdgeCasesTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val quarterAgo: LocalDate = today.minusMonths(3)
    private val zone = ZoneOffset.UTC

    private fun profile(sex: String = "MALE", birthDate: String = "1985-04-10") =
        UserProfile(sex = sex, weightKg = 80.0, heightCm = 180, birthDate = birthDate)

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

    private fun weighIn(kg: Double?, occurredAt: Long, kind: String = "MEASUREMENT") = JournalEntry(
        day = today.toString(),
        occurredAt = occurredAt,
        kind = kind,
        title = "scale",
        calories = 0.0,
        measurementWeightKg = kg,
    )

    private fun workout(day: LocalDate, minutes: Double) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = "ACTIVITY",
        title = "Swim",
        calories = 300.0,
        durationMinutes = minutes,
    )

    private fun course(times: String = "480,1200") = Medication(
        id = 1,
        name = "Metformin",
        dose = "1 tablet",
        times = times,
        startDay = today.minusDays(30).toString(),
        createdAt = 0,
    )

    private fun report(
        entries: List<JournalEntry>,
        days: List<DaySteps> = emptyList(),
        profile: UserProfile = profile(),
    ) = doctorReport(
        sources = ReportSources(profile, entries, days),
        request = ReportRequest(from = quarterAgo, to = today),
        today = today,
        zone = zone,
    )

    private fun DoctorReport.section(part: ReportPart): ReportSection? = sections.firstOrNull {
        it.part == part &&
            it.presence == SectionPresence.PRESENT
    }

    private fun DoctorReport.rowLabelled(part: ReportPart, label: Int): ReportRow =
        section(part)!!.rows.first { it.label == res(label) }

    /** The sections actually carrying something: an empty one is on the sheet, saying it is empty. */
    private fun DoctorReport.printedParts(): List<ReportPart> =
        sections.filter { it.presence == SectionPresence.PRESENT }.map(ReportSection::part)

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    // --- what the preview calls an empty quarter --------------------------------------------------

    /**
     * A quarter of weigh-ins is a quarter of something. [DoctorReport.filled] exists so that a
     * period with nothing in it is discovered on the phone rather than in a waiting room, and the
     * page says "this period is empty" straight off it — never over a sheet that already has a
     * weight section on it.
     */
    @Test
    fun aQuarterOfWeighInsIsNotAnEmptyQuarter() {
        val built = report(listOf(measurement(today, weightKg = 79.5)))

        // One weigh-in on one day holds no two stretches to compare, so the changes section stays off.
        assertEquals(listOf(ReportPart.PERSON, ReportPart.WEIGHT), built.printedParts())
        assertEquals(setOf(ReportPart.WEIGHT), built.filled)
        assertFalse("sections were ${built.printedParts()}", built.empty)
    }

    /** And so is a quarter of logged workouts, which holds nothing any instrument wrote. */
    @Test
    fun aQuarterOfWorkoutsIsNotAnEmptyQuarter() {
        val built = report(listOf(workout(today, 45.0)))

        assertEquals(listOf(ReportPart.PERSON, ReportPart.ACTIVITY), built.printedParts())
        val row = built.section(ReportPart.ACTIVITY)!!.rows.single()
        assertEquals(ReportText.Plain("1"), row.value)
        assertEquals(ReportText.Quantity(R.plurals.count_minutes, 45), row.note)
        assertFalse("sections were ${built.printedParts()}", built.empty)
    }

    // --- the period itself ------------------------------------------------------------------------

    /**
     * A period whose ends were handed over the wrong way round holds no day at all. It must come
     * back as an empty report rather than as an exception on the way to a PDF — the day count behind
     * it goes negative, and a person tapping two dates in the wrong order is not a crash report.
     */
    @Test
    fun aPeriodAskedForBackwardsIsEmptyRatherThanAnError() {
        val entries = listOf(measurement(today.minusDays(10), pulse = 70), measurement(today, weightKg = 80.0))
        val days = listOf(DaySteps(today.toString(), 9_000))

        val built = doctorReport(
            sources = ReportSources(profile(), entries, days),
            request = ReportRequest(from = today, to = quarterAgo),
            today = today,
            zone = zone,
        )

        assertEquals(listOf(ReportPart.PERSON), built.printedParts())
        assertTrue("sections were ${built.printedParts()}", built.empty)
    }

    /** Both ends of the period are inside it: a one-day report holds that day's reading and steps. */
    @Test
    fun aSingleDayPeriodCountsBothOfItsEnds() {
        val entries = listOf(measurement(today, pulse = 66))
        val days = listOf(DaySteps(today.toString(), 9_000))

        val built = doctorReport(
            sources = ReportSources(profile(), entries, days),
            request = ReportRequest(from = today, to = today),
            today = today,
            zone = zone,
        )

        assertEquals(setOf(ReportPart.PULSE, ReportPart.ACTIVITY), built.filled)
        assertEquals(
            ReportText.Quantity(R.plurals.count_readings, 1),
            built.rowLabelled(ReportPart.PULSE, R.string.report_readings).value,
        )
        assertEquals(
            ReportText.Quantity(R.plurals.count_days, 1),
            built.section(ReportPart.ACTIVITY)!!.rows.single().note,
        )
    }

    // --- medicines --------------------------------------------------------------------------------

    /**
     * A sheet printed at nine in the morning must not tell a doctor that tonight's tablet was
     * missed. The medicine chart makes the same allowance, and the report carries the minute of the
     * day through to it so the two cannot disagree about the same evening.
     */
    @Test
    fun theEveningDoseIsNotYetAMissedDose() {
        val courses = listOf(course())

        // Yesterday's morning dose, marked and outside the one-day period: the tick is in use for
        // this course, so an unmarked today is a zero the sheet prints rather than a course nobody
        // ever marked, which prints no percentage at all.
        val yesterday = today.minusDays(1)
        val markedBefore = listOf(
            JournalEntry(
                day = yesterday.toString(),
                occurredAt = at(yesterday, 480),
                kind = KIND_MED,
                title = "Metformin",
                calories = 0.0,
                measurementPayloadJson = """{"$MED_COURSE_KEY":1,"$MED_SLOT_KEY":"480"}""",
            ),
        )
        val sources = ReportSources(profile(), markedBefore, emptyList(), courses)
        val request = ReportRequest(from = today, to = today)
        val atNine = doctorReport(sources, request, today, nowMinute = 9 * 60, zone = zone)
        val wholeDay = doctorReport(sources, request, today, zone = zone)

        val morning = atNine.section(ReportPart.MEDICATIONS)!!.rows[2]
        assertEquals(res(R.string.report_adherence), morning.label)
        assertEquals(res(R.string.med_chart_percent, 0), morning.value)
        assertEquals(res(R.string.report_doses_marked, 0, 1), morning.note)
        assertEquals(
            res(R.string.report_doses_marked, 0, 2),
            wholeDay.section(ReportPart.MEDICATIONS)!!.rows[2].note,
        )
    }

    // --- weight -----------------------------------------------------------------------------------

    /**
     * The plausibility gate is the one the timeline applies, and it is closed on neither end: 20 kg
     * and 400 kg are weights somebody can have, 19.9 and 400.1 are what a scale says when it is
     * reading a suitcase. Which weigh-in survives decides which number the BMI verdict is read off.
     */
    @Test
    fun theWeighInGateIsInclusiveAtBothEnds() {
        val newestFirst = listOf(
            weighIn(50.0, 6, kind = "ACTIVITY"),
            weighIn(null, 5),
            weighIn(400.1, 4),
            weighIn(400.0, 3),
            weighIn(20.0, 2),
            weighIn(19.9, 1),
        )

        val readings = weightReadings(newestFirst)

        assertEquals("was ${readings.map(WeightReading::kg)}", listOf(20.0, 400.0), readings.map(WeightReading::kg))
        assertEquals(listOf(2L, 3L), readings.map(WeightReading::occurredAt))
    }

    /**
     * One weigh-in is a first and a latest at once, and the difference between them is nothing. A
     * person who did not change weight must not be handed a sheet reading "+0 kg": the magnitude is
     * printed through abs(), so a sign chosen on the wrong side of zero is invisible in the figure.
     */
    @Test
    fun oneWeighInIsNoChangeAndCarriesNoSign() {
        val day = today.minusDays(3)
        val kg = res(R.string.unit_kg)

        val rows = report(listOf(measurement(day, weightKg = 80.0))).section(ReportPart.WEIGHT)!!.rows

        assertEquals(5, rows.size)
        val ends = rows.take(4)
        val eighty = res(R.string.report_value_unit, "80", kg)
        assertTrue("was ${ends.map(ReportRow::value)}", ends.all { it.value == eighty })
        assertTrue("was ${ends.map(ReportRow::note)}", ends.all { it.note == ReportText.Day(day) })
        assertEquals(res(R.string.report_value_unit, "0", kg), rows[4].value)
    }

    // --- bands and verdicts -----------------------------------------------------------------------

    /**
     * A band closed on one side has said nothing about the other, so its NORMAL is half an answer.
     * "Inside the range" under a ceiling-only band claims a floor nobody published, and the two
     * half-answers read plausibly enough that swapping them would never be noticed on the page.
     */
    @Test
    fun normalAgainstABandClosedOnOneSideIsNotAPass() {
        val normal: LabStatus? = LabStatus.NORMAL

        assertEquals(R.string.vitals_status_not_above, normal.reportVerdictRes(LabBand(high = 5.0)))
        assertEquals(R.string.vitals_status_not_above, normal.reportVerdictRes(LabBand(veryHigh = 5.0)))
        assertEquals(R.string.vitals_status_not_below, normal.reportVerdictRes(LabBand(low = 1.0)))
        assertEquals(R.string.vitals_status_not_below, normal.reportVerdictRes(LabBand(veryLow = 1.0)))
        assertEquals(R.string.vitals_status_inside, normal.reportVerdictRes(LabBand(low = 1.0, high = 5.0)))
        assertNull(normal.reportVerdictRes(null))
        assertNull((null as LabStatus?).reportVerdictRes(LabBand(low = 1.0, high = 5.0)))
    }

    /**
     * The printed reference is the last figures still inside the band. The high edge is a pair one
     * step under the 140/90 the ESH grades from; the low edge is the NHS's 90/60 read on the
     * systolic alone, so no diastolic is printed under it — the diastolic has no published floor.
     */
    @Test
    fun theBloodPressureReferenceIsASystolicFloorUnderAPairedCeiling() {
        val built = report(listOf(pressure(today, 128, 84)))

        val reference = built.rowLabelled(ReportPart.PRESSURE, R.string.report_reference)

        assertEquals(
            res(
                R.string.report_value_unit,
                res(R.string.labs_norm_range, "91", "139/89"),
                res(R.string.vitals_unit_mmhg),
            ),
            reference.value,
        )
        assertTrue(reference.detail)
    }

    /**
     * A mean is floored at the resolution its band was compared at, never rounded up. 139/85 and
     * 140/86 average to 139.5/85.5, and printing that as 140/86 puts a hypertensive pair on a
     * doctor's sheet where the app itself graded a normal one.
     */
    @Test
    fun theMeanPressureIsFlooredAtTheStepItWasGradedAt() {
        val onTheEdge = report(listOf(pressure(quarterAgo.plusDays(1), 139, 85), pressure(today, 140, 86)))
        val ordinary = report(listOf(pressure(quarterAgo.plusDays(1), 118, 76), pressure(today, 129, 85)))

        assertEquals(
            res(R.string.report_min_mean_max, "139/85", "139/85", "140/86"),
            onTheEdge.rowLabelled(ReportPart.PRESSURE, R.string.report_readings).note,
        )
        assertEquals(
            res(R.string.report_min_mean_max, "118/76", "123/80", "129/85"),
            ordinary.rowLabelled(ReportPart.PRESSURE, R.string.report_readings).note,
        )
    }

    /**
     * Every figure on the sheet is a figure the app does not translate, and they all read on one
     * scale. A BMI built with a bare format string would take the phone's own locale, so a
     * Russian or German phone would print "ИМТ 24,7" beside "Вес 80 кг" — two decimal separators in one
     * section, on a page whose whole purpose is being read by somebody else.
     */
    @Test
    fun theBmiFigureIsPrintedOnTheSameScaleAsTheFiguresBesideIt() {
        val wasDefault = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val built = report(emptyList())

            assertEquals(
                res(R.string.report_value_unit, "80", res(R.string.unit_kg)),
                built.rowLabelled(ReportPart.PERSON, R.string.weight).value,
            )
            assertEquals(
                ReportText.Plain("24.7"),
                built.rowLabelled(ReportPart.PERSON, R.string.profile_bmi).value,
            )
        } finally {
            Locale.setDefault(wasDefault)
        }
    }
}
