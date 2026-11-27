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
 * The person block, the vitals, the mood, the cycle, the medicines and the sleep on the doctor's
 * sheet, each pinned where it could say something the record does not.
 *
 * Every case is one a reader would notice on paper: a banner denying what the page prints, a weight
 * the person unticked still dated in the header, a run's average heart rate graded as a resting
 * pulse, an armpit temperature under a core band, a supplement nobody ticked printed as "0%", the
 * phone's guess at a night printed as if a watch had measured it, and an act counted into the days
 * of a section it was not sent with.
 */
class DoctorReportPersonVitalsMoodTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val from: LocalDate = today.minusMonths(3)
    private val zone: ZoneId = ZoneOffset.UTC

    private fun profile(sex: String = "MALE", unitSystem: String = "METRIC") =
        UserProfile(sex = sex, weightKg = 80.0, heightCm = 180, birthDate = "1985-04-10", unitSystem = unitSystem)

    private fun at(day: LocalDate, minute: Int = 9 * 60): Long =
        day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun measurement(
        day: LocalDate,
        payload: String? = null,
        weightKg: Double? = null,
        pulse: Int? = null,
        source: String? = null,
    ) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = "MEASUREMENT",
        title = "measurement",
        calories = 0.0,
        measurementWeightKg = weightKg,
        measurementHeartRateBpm = pulse,
        measurementSource = source,
        measurementPayloadJson = payload,
    )

    private fun mood(day: LocalDate, score: Int, energy: Int? = null) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_MOOD,
        title = "mood",
        calories = 0.0,
        moodScore = score,
        energyScore = energy,
    )

    private fun sleep(day: LocalDate, minutes: Int, source: String? = null) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = "MEASUREMENT",
        title = "sleep",
        calories = 0.0,
        sleepStartAt = at(day) - minutes * 60_000L,
        sleepEndAt = at(day),
        measurementSource = source,
    )

    private fun cycleStart(day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_CYCLE,
        title = "cycle",
        calories = 0.0,
    )

    private fun logged(day: LocalDate, log: CycleLog) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_CYCLE_LOG,
        title = "log",
        calories = 0.0,
        measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"${log.name}"}""",
    )

    private fun course(id: Long) = Medication(
        id = id,
        name = "Course $id",
        dose = "1 tablet",
        times = "480",
        startDay = from.minusDays(30).toString(),
        createdAt = 0,
    )

    private fun taken(courseId: Long, day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 480),
        kind = KIND_MED,
        title = "Course $courseId",
        calories = 0.0,
        measurementPayloadJson = """{"$MED_COURSE_KEY":$courseId,"$MED_SLOT_KEY":"480"}""",
    )

    private fun report(
        entries: List<JournalEntry>,
        profile: UserProfile = profile(),
        parts: Set<ReportPart>? = null,
        country: CountryProfile = CountryProfileRegistry.international,
        medications: List<Medication> = emptyList(),
        history: List<MedicalHistoryRow> = emptyList(),
    ) = doctorReport(
        sources = ReportSources(profile, entries, emptyList(), medications, history = history),
        request = ReportRequest(from = from, to = today, country = country).let { request ->
            parts?.let { request.copy(parts = it) } ?: request
        },
        today = today,
        zone = zone,
    )

    private fun DoctorReport.section(part: ReportPart): ReportSection? = sections.firstOrNull {
        it.part == part &&
            it.presence == SectionPresence.PRESENT
    }

    private fun ReportSection.row(label: Int): ReportRow? = rows.firstOrNull { it.label == res(label) }

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    /** One string out of the English resources, read as text: the builder carries ids, not words. */
    private fun english(name: String): String = File("src/main/res/values/ui.xml").readText()
        .substringAfter("<string name=\"$name\"")
        .substringAfter(">")
        .substringBefore("</string>")

    // --- the banner -------------------------------------------------------------------------------

    /**
     * The first paragraph a doctor reads must not say this document carries no diagnoses, allergies,
     * vaccinations or pregnancy status because the app does not record them: the sections below it
     * print all four. A written-down condition reaches the sheet, and neither the banner nor the
     * picker's line under that section may say otherwise.
     */
    @Test
    fun theBannerDoesNotDenyTheConditionsTheSheetPrints() {
        val condition = MedicalHistoryRow(
            key = "c1",
            kind = HistoryKind.CONDITION.name,
            title = "Chronic kidney disease",
            startDay = "2025-03-01",
            updatedAt = at(today),
        )

        val built = report(emptyList(), history = listOf(condition))

        assertEquals(1, built.section(ReportPart.PROBLEMS)!!.rows.size)
        val banner = english("report_patient_generated")
        assertFalse(banner, banner.contains("carries no diagnoses"))
        assertFalse(banner, banner.contains("does not record"))
        assertTrue(banner, banner.contains("not a negative finding"))
        val picker = english("report_part_problems_body")
        assertFalse(picker, picker.contains("Not recorded"))
        // And the sexual section does not promise a "not stated" the journal cannot hold.
        assertFalse(english("report_caveat_sexual").contains("“not stated”"))
    }

    // --- the person -------------------------------------------------------------------------------

    /**
     * The weight tick's line in the picker reads "Weight trend and BMI". Unticked, the header must not
     * print a dated weigh-in and a verdict on it while the weight heading says it was not included,
     * and the notes must not credit the WHO for a BMI band the sheet then does not print.
     */
    @Test
    fun anUntickedWeightTakesTheBmiWithIt() {
        val entries = listOf(measurement(today.minusDays(1), weightKg = 92.0))

        val without = report(entries, parts = setOf(ReportPart.PRESSURE))
        val person = without.section(ReportPart.PERSON)!!

        assertNull(person.row(R.string.weight))
        assertNull(person.row(R.string.profile_bmi))
        assertNotNull(person.row(R.string.height))
        assertFalse(without.notes.contains(res(R.string.report_note_bmi)))
        assertTrue(ReportPart.WEIGHT in without.filled)

        val with = report(entries, parts = setOf(ReportPart.WEIGHT))
        assertEquals(ReportText.Day(today.minusDays(1)), with.section(ReportPart.PERSON)!!.row(R.string.weight)!!.note)
        assertNotNull(with.section(ReportPart.PERSON)!!.row(R.string.profile_bmi))
        assertTrue(with.notes.contains(res(R.string.report_note_bmi)))
    }

    // --- the vitals -------------------------------------------------------------------------------

    /**
     * A metric profile sending its sheet to the United States gets pounds in the header, and so
     * the scale's muscle mass, the tape's waist and the thermometer under it follow — not the
     * profile's kilograms, centimetres and Celsius. And the other way round for Germany.
     */
    @Test
    fun theVitalsPrintOnTheSheetsScaleNotTheProfiles() {
        val entries = listOf(
            measurement(today, """{"schema":1,"muscleMassKg":32.5}"""),
            measurement(today, """{"schema":1,"waistCircumferenceCm":96}"""),
            measurement(today, """{"schema":1,"temperatureCelsius":37.2}"""),
        )

        val us = report(entries, country = UnitedStatesProfile)
        assertEquals(
            res(R.string.report_value_unit, readingText(Vital.MUSCLE_MASS, 32.5, null, true), res(R.string.unit_lb)),
            us.section(ReportPart.BODY_COMPOSITION)!!.row(R.string.direct_bluetooth_muscle)!!.value,
        )
        assertEquals(
            res(R.string.report_value_unit, readingText(Vital.WAIST, 96.0, null, true), res(R.string.unit_in)),
            us.section(ReportPart.GIRTH)!!.row(Vital.WAIST.titleRes)!!.value,
        )
        val fahrenheit = readingText(Vital.BODY_TEMPERATURE, 37.2, null, true)
        assertEquals(
            res(R.string.report_value_unit, fahrenheit, res(R.string.vitals_unit_fahrenheit)),
            us.section(ReportPart.TEMPERATURE)!!.rows.first().value,
        )

        val germany = report(entries, profile = profile(unitSystem = "IMPERIAL"), country = GermanyProfile)
        assertEquals(
            res(R.string.report_value_unit, "32.5", res(R.string.unit_kg)),
            germany.section(ReportPart.BODY_COMPOSITION)!!.row(R.string.direct_bluetooth_muscle)!!.value,
        )
        assertEquals(
            res(R.string.report_value_unit, "37.2", res(R.string.vitals_unit_celsius)),
            germany.section(ReportPart.TEMPERATURE)!!.rows.first().value,
        )
    }

    /**
     * A run imported from a Strava export keeps the session's average heart rate in the pulse
     * column. That is a fact about the run, not a resting pulse: it is not in the vitals series,
     * so it is neither graded against the resting band nor averaged with the morning readings.
     */
    @Test
    fun aWorkoutsAverageHeartRateIsNotAPulse() {
        val run = JournalEntry(
            day = today.toString(),
            occurredAt = at(today),
            kind = "ACTIVITY",
            title = "Morning run",
            calories = 600.0,
            durationMinutes = 45.0,
            measurementHeartRateBpm = 152,
            measurementSource = "Strava",
        )

        assertNull(vitalHistory(listOf(run), profile())[Vital.HEART_RATE])

        val built = report(listOf(run, measurement(today.minusDays(1), pulse = 62)))
        val pulse = built.section(ReportPart.PULSE)!!
        assertEquals(ReportText.Quantity(R.plurals.count_readings, 1), pulse.row(R.string.report_readings)!!.value)
        assertEquals(res(R.string.report_value_unit, "62", res(R.string.unit_bpm)), pulse.rows.first().value)
    }

    /**
     * An armpit reading is not graded against the core-temperature band on the phone, so the sheet
     * does not print that band beside it, credit its source in the notes, or put the caveat about the
     * app's bands under a section that prints none. It says instead that the band is not applied at
     * this site.
     */
    @Test
    fun anArmpitTemperatureGetsNoCoreBandButSaysWhy() {
        val armpit = report(listOf(measurement(today, """{"schema":1,"temperatureCelsius":37.4,"type":"ARMPIT"}""")))
        val rows = armpit.section(ReportPart.TEMPERATURE)!!.rows

        val reference = rows.filter { it.label == res(R.string.report_reference) }
        assertEquals(listOf(res(R.string.report_temperature_site_note)), reference.map(ReportRow::value))
        val source = res(
            R.string.report_note_band_source,
            res(Vital.BODY_TEMPERATURE.titleRes),
            res(Vital.BODY_TEMPERATURE.sourceRes!!),
        )
        assertFalse(armpit.notes.contains(source))
        // Nor the caveat about the app's bands, with no band on the section for it to be about.
        assertNull(armpit.section(ReportPart.TEMPERATURE)!!.caveatRes)

        // A thermometer that named no site is graded against the band, band and source both printed.
        val unnamed = report(listOf(measurement(today, """{"schema":1,"temperatureCelsius":37.4}""")))
        val unnamedReference = unnamed.section(ReportPart.TEMPERATURE)!!.row(R.string.report_reference)!!
        assertFalse(unnamedReference.value == res(R.string.report_temperature_site_note))
        assertTrue(unnamed.notes.contains(source))
        assertEquals(R.string.report_caveat_band, unnamed.section(ReportPart.TEMPERATURE)!!.caveatRes)
    }

    /**
     * A year of weigh-ins imported from a Withings export carries "Withings" as its source. That is
     * a recorded source, and the sheet names it as an import rather than as "not recorded"; only a
     * label nothing recognises is still unknown.
     */
    @Test
    fun aFileImportIsNamedAsAnImportNotAsUnrecorded() {
        val built = report(
            listOf(
                measurement(from.plusDays(1), weightKg = 90.0, source = "Withings"),
                measurement(today, weightKg = 89.0, source = "Withings"),
                measurement(today, pulse = 64, source = "Garmin Connect"),
            ),
        )

        assertEquals(listOf(Provenance(ProvenanceCategory.IMPORTED_APP)), built.section(ReportPart.WEIGHT)!!.provenance)
        val pulse = built.section(ReportPart.PULSE)!!
        assertEquals(listOf(Provenance(ProvenanceCategory.IMPORTED_APP)), pulse.provenance)
        assertTrue((pulse.rows.first().note as ReportText.Join).parts.contains(res(R.string.provenance_imported)))

        val stranger = report(listOf(measurement(today, pulse = 64, source = "SOMETHING_NEW")))
        assertEquals(listOf(Provenance(ProvenanceCategory.UNKNOWN)), stranger.section(ReportPart.PULSE)!!.provenance)
    }

    // --- the medicines ----------------------------------------------------------------------------

    /**
     * A course written down and never once ticked is a course the tick was not used for: it prints
     * that, not "0%", which a doctor reads as a refused treatment. A course ticked before the period
     * and not in it keeps its zero, because there the tick was in use and its silence is a figure.
     */
    @Test
    fun aCourseNeverMarkedSaysSoRatherThanZero() {
        val built = report(
            entries = listOf(taken(2, from.minusDays(5))),
            medications = listOf(course(1), course(2)),
        )
        val rows = built.section(ReportPart.MEDICATIONS)!!.rows
        val adherence = rows.filter { it.label == res(R.string.report_adherence) }

        assertEquals(2, adherence.size)
        assertEquals(res(R.string.report_doses_never_marked), adherence[0].value)
        assertNull(adherence[0].note)
        assertEquals(res(R.string.med_chart_percent, 0), adherence[1].value)
        val asked = java.time.temporal.ChronoUnit.DAYS.between(from, today).toInt() + 1
        assertEquals(res(R.string.report_doses_marked, 0, asked), adherence[1].note)
    }

    // --- sleep ------------------------------------------------------------------------------------

    /**
     * Nights the app estimated from an untouched phone say so — the source line every measured
     * section carries, and a caveat that says what such a night is. A night typed in says it was
     * typed in, and a row the session gate drops lends the section no source of its own.
     */
    @Test
    fun sleepSaysWhoWroteTheNights() {
        val estimated = report(
            listOf(
                sleep(today.minusDays(2), 480, source = "PHONE_USAGE_ESTIMATE"),
                sleep(today.minusDays(1), 420, source = "PHONE_USAGE_ESTIMATE"),
                // Zero minutes: not a session, so not a source either.
                sleep(today, 0, source = "HEALTH_CONNECT"),
            ),
        ).section(ReportPart.SLEEP)!!

        assertEquals(listOf(Provenance(ProvenanceCategory.ESTIMATE)), estimated.provenance)
        assertEquals(R.string.report_caveat_sleep_estimated, estimated.caveatRes)

        val mixed = report(
            listOf(sleep(today.minusDays(2), 480), sleep(today.minusDays(1), 420, source = "PHONE_USAGE_ESTIMATE")),
        ).section(ReportPart.SLEEP)!!
        assertEquals(
            listOf(Provenance(ProvenanceCategory.MANUAL), Provenance(ProvenanceCategory.ESTIMATE)),
            mixed.provenance,
        )

        val typed = report(listOf(sleep(today.minusDays(1), 440))).section(ReportPart.SLEEP)!!
        assertEquals(listOf(Provenance(ProvenanceCategory.MANUAL)), typed.provenance)
        assertEquals(R.string.report_caveat_sleep, typed.caveatRes)
        // The shortfall names its target as the app's own.
        assertEquals(res(R.string.report_sleep_target), typed.row(R.string.report_sleep_debt)!!.note)
    }

    /**
     * The waking times sit directly under the bedtimes, a detail row like them. Both nights here end
     * at 09:00 and start at 22:30 and 02:30, four hours apart, a bedtime spread of 120 minutes, and
     * the waking times not apart at all.
     */
    @Test
    fun wakingTimesFollowTheBedtimes() {
        val rows = report(listOf(sleep(today.minusDays(2), 390), sleep(today.minusDays(1), 630)))
            .section(ReportPart.SLEEP)!!.rows
        val bedtimes = rows.indexOfFirst { it.label == res(R.string.report_sleep_spread) }
        val waking = rows[bedtimes + 1]

        assertEquals(ReportText.Quantity(R.plurals.count_minutes, 120), rows[bedtimes].value)
        assertEquals(res(R.string.report_sleep_wake_spread), waking.label)
        assertEquals(ReportText.Quantity(R.plurals.count_minutes, 0), waking.value)
        assertTrue(waking.detail)
    }

    // --- mood -------------------------------------------------------------------------------------

    /**
     * Two wound-up answers on the second dial make the card's "Mostly tense", and the sheet must not
     * print that word for a whole quarter of pleasant days. The average carries the wellbeing band
     * alone, and tension waits for a week of answers before it gets a row.
     */
    @Test
    fun twoTenseDaysDoNotNameAQuarter() {
        val entries = (0L until 10L).map { back ->
            mood(today.minusDays(back), 4, energy = Energy.WOUND_UP.score.takeIf { back < 2 })
        }

        assertEquals(MoodState.TENSE, moodSummary(entries).state)
        val section = report(entries).section(ReportPart.MOOD)!!

        assertEquals(res(R.string.mood_state_elevated), section.row(R.string.report_mood_average)!!.note)
        assertNull(section.row(R.string.report_mood_wound_up))
    }

    /**
     * Over a week of answers the tension is printed as the two figures behind it — the days that
     * came back wound up or restless, out of the days the dial was answered — with "Mostly tense"
     * where the share reaches the card's own threshold, and the band beside the average unmasked.
     */
    @Test
    fun tensionIsPrintedWithTheDaysBehindIt() {
        val tense = (0L until 10L).map { back ->
            mood(today.minusDays(back), 3, energy = if (back < 5) Energy.RESTLESS.score else Energy.CALM.score)
        }
        val section = report(tense).section(ReportPart.MOOD)!!

        // Ten days of "Neutral" are the middle of the scale, which is not below it ([wellbeingBand]).
        assertEquals(res(R.string.mood_state_balanced), section.row(R.string.report_mood_average)!!.note)
        val row = section.row(R.string.report_mood_wound_up)!!
        assertEquals(
            res(R.string.report_count_of, 5, ReportText.Quantity(R.plurals.report_days_answered, 10)),
            row.value,
        )
        assertEquals(res(R.string.mood_state_tense), row.note)

        val calmer = (0L until 10L).map { back ->
            mood(today.minusDays(back), 3, energy = if (back < 2) Energy.WOUND_UP.score else Energy.STEADY.score)
        }
        val calmerRow = report(calmer).section(ReportPart.MOOD)!!.row(R.string.report_mood_wound_up)!!
        assertEquals(
            res(R.string.report_count_of, 2, ReportText.Quantity(R.plurals.report_days_answered, 10)),
            calmerRow.value,
        )
        assertNull(calmerRow.note)
    }

    // --- the cycle --------------------------------------------------------------------------------

    /**
     * With the sexual-activity section not sent, the acts the same log keeps must leave no trace in
     * the cycle section — not a row, and not a day in its completeness line, which would otherwise grow
     * by every day an act was logged on.
     */
    @Test
    fun actsLeaveNoTraceInTheCycleSection() {
        val cycle = listOf(
            cycleStart(today.minusDays(40)),
            cycleStart(today.minusDays(12)),
            logged(today.minusDays(12), CycleLog.FLOW_MEDIUM),
            logged(today.minusDays(11), CycleLog.FLOW_LIGHT),
        )
        val acts = listOf(
            logged(today.minusDays(5), CycleLog.SEX_PROTECTED),
            logged(today.minusDays(3), CycleLog.SEX_UNPROTECTED),
        )
        val woman = profile(sex = "FEMALE")

        val without = report(cycle, profile = woman).section(ReportPart.CYCLE)!!
        val with = report(cycle + acts, profile = woman).section(ReportPart.CYCLE)!!

        assertFalse(ReportPart.SEXUAL_HEALTH in ReportRequest(from, today).parts)
        assertEquals(without, with)
        assertEquals(Completeness(daysWithData = 3, expectedDays = null, measurements = 4), with.completeness)
    }

    /**
     * Under the age gate the cycle page does not ask about desire, and an answer a restored backup
     * brought is not printed under this section either, nor counted in its days: the sheet of a
     * thirteen-year-old is the sheet she would have had without it.
     */
    @Test
    fun desireIsOffTheCycleSectionUnderTheAgeGate() {
        val cycle = listOf(cycleStart(today.minusDays(12)), logged(today.minusDays(12), CycleLog.FLOW_MEDIUM))
        val desire = listOf(logged(today.minusDays(5), CycleLog.DESIRE_HIGH))
        val woman = profile(sex = "FEMALE")
        val girl = woman.copy(birthDate = "2013-04-10")

        val ofAge = report(cycle + desire, profile = woman).section(ReportPart.CYCLE)!!
        val onOneDay = ReportText.Quantity(R.plurals.report_logged_on_days, 1)
        assertEquals(onOneDay, ofAge.row(R.string.cycle_desire_high)!!.value)
        assertEquals(
            report(cycle, profile = girl).section(ReportPart.CYCLE),
            report(cycle + desire, profile = girl).section(ReportPart.CYCLE),
        )
        assertNull(report(desire, profile = girl).section(ReportPart.CYCLE))
    }

    /** "Bleeding 26" would read as twenty-six days of bleeding; the count says it is days an entry was made on. */
    @Test
    fun observationsAreCountedInDaysLogged() {
        val entries = listOf(
            logged(today.minusDays(3), CycleLog.FLOW_LIGHT),
            logged(today.minusDays(2), CycleLog.FLOW_MEDIUM),
            logged(today.minusDays(1), CycleLog.SPOTTING),
        )

        val rows = report(entries, profile = profile(sex = "FEMALE")).section(ReportPart.CYCLE)!!.rows

        val bleeding = rows.first { it.label == res(R.string.cycle_section_flow) }
        assertEquals(ReportText.Quantity(R.plurals.report_logged_on_days, 3), bleeding.value)
        val light = rows.first { it.label == res(R.string.cycle_flow_light) }
        assertEquals(ReportText.Quantity(R.plurals.report_logged_on_days, 1), light.value)
        assertTrue(light.detail)
    }

    /**
     * The last period is the last one known, not a figure about the window: one that started before
     * the report's first day is printed with its date and marked as falling before the period,
     * rather than the section saying nothing was recorded. A start logged after the report's last
     * day is not the last period of that report.
     */
    @Test
    fun theLastPeriodIsReadFromTheWholeRecord() {
        val before = from.minusDays(20)
        val woman = profile(sex = "FEMALE")

        val section = report(listOf(cycleStart(before), cycleStart(today.plusDays(3))), profile = woman)
            .section(ReportPart.CYCLE)!!

        val lmp = section.rows.single()
        assertEquals(res(R.string.report_cycle_lmp), lmp.label)
        assertEquals(ReportText.Day(before), lmp.value)
        assertEquals(res(R.string.report_cycle_lmp_before), lmp.note)

        val inside = report(listOf(cycleStart(before), cycleStart(today.minusDays(4))), profile = woman)
            .section(ReportPart.CYCLE)!!
            .rows
            .first()
        assertEquals(ReportText.Day(today.minusDays(4)), inside.value)
        assertNull(inside.note)
    }

    /**
     * A gap between two starts that is no cycle — ten days, or seventy-five — is left out of the
     * count, the average and the spread, and the sheet says how many were: "2 cycles, 28 – 29 days"
     * over a record with a third gap in it would read as a regular one.
     */
    @Test
    fun aGapLeftOutOfTheCyclesIsCounted() {
        val woman = profile(sex = "FEMALE")
        val outside = res(R.string.report_cycle_gaps_outside, PLAUSIBLE_CYCLE_DAYS.first, PLAUSIBLE_CYCLE_DAYS.last)
        val starts = listOf(90L, 62L, 33L, 23L).map { cycleStart(today.minusDays(it)) }

        val section = report(starts, profile = woman).section(ReportPart.CYCLE)!!
        val leftOut = section.rows.single { it.label == outside }

        assertEquals(ReportText.Quantity(R.plurals.count_cycles, 2), section.row(R.string.report_cycles)!!.value)
        assertEquals(ReportText.Plain("1"), leftOut.value)
        assertEquals(res(R.string.report_left_out), leftOut.note)
        assertTrue(leftOut.detail)

        // With no cycle counted there is nothing for the gap to have been left out of: it stands alone.
        val alone = report(listOf(cycleStart(today.minusDays(80)), cycleStart(today.minusDays(5))), profile = woman)
            .section(ReportPart.CYCLE)!!
            .rows
            .single { it.label == outside }
        assertEquals(ReportText.Plain("1"), alone.value)
        assertNull(alone.note)
        assertFalse(alone.detail)

        // And a record of plausible cycles carries no such row.
        val regular = listOf(84L, 56L, 28L, 0L).map { cycleStart(today.minusDays(it)) }
        assertTrue(report(regular, profile = woman).section(ReportPart.CYCLE)!!.rows.none { it.label == outside })
    }
}
