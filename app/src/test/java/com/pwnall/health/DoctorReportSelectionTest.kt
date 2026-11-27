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
 * Who the report is for, and what it carries.
 *
 * The sheet carries the sections the person chooses, and every case here is one somebody would
 * notice on paper: a section the person unticked printed anyway, a body credited
 * for a band the sheet never printed, a gynaecologist offered to a man, a doctor whose preselection
 * holds a section his own profile is not offered.
 */
class DoctorReportSelectionTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val from: LocalDate = today.minusMonths(3)
    private val zone: ZoneId = ZoneOffset.UTC

    private fun profile(sex: String = "FEMALE") =
        UserProfile(sex = sex, weightKg = 68.0, heightCm = 168, birthDate = "1990-04-10")

    private fun at(day: LocalDate): Long = day.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()

    private fun measurement(day: LocalDate, payload: String? = null, weightKg: Double? = null, pulse: Int? = null) =
        JournalEntry(
            day = day.toString(),
            occurredAt = at(day),
            kind = "MEASUREMENT",
            title = "measurement",
            calories = 0.0,
            measurementWeightKg = weightKg,
            measurementHeartRateBpm = pulse,
            measurementPayloadJson = payload,
        )

    private fun meal(day: LocalDate, calories: Double, protein: Double) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = "EAT",
        title = "meal",
        calories = calories,
        proteinG = protein,
    )

    private fun mood(day: LocalDate, score: Int) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_MOOD,
        title = "mood",
        calories = 0.0,
        moodScore = score,
    )

    /** A journal with something in it for every section a woman is offered. */
    private fun everything(): List<JournalEntry> = listOf(
        measurement(today, weightKg = 68.0),
        measurement(today, """{"schema":1,"muscleMassKg":32.5}"""),
        measurement(today, pulse = 64),
        measurement(today, """{"schema":1,"systolicMmhg":118,"diastolicMmhg":76}"""),
        measurement(today, """{"schema":1,"spo2Percent":97}"""),
        measurement(
            today,
            """{"schema":1,"waistCircumferenceCm":82,"hipCircumferenceCm":96,"chestCircumferenceCm":98}""",
        ),
        measurement(today, """{"schema":1,"temperatureCelsius":36.6}"""),
        measurement(today, """{"schema":1,"concentrationMmolL":5.1,"fasting":true}"""),
        // Two laboratory panels of one marker, the later inside the period, so the section that lays
        // the diary out around each blood test has a draw to stand beside another.
        labs(today.minusMonths(8), 3.4),
        labs(today.minusDays(2), 3.0),
        meal(today.minusDays(1), 700.0, 30.0),
        meal(today, 500.0, 20.0),
        mood(today.minusDays(1), 4),
        mood(today, 4),
        mood(today, 5),
        JournalEntry(
            day = today.toString(),
            occurredAt = at(today),
            kind = "MEASUREMENT",
            title = "sleep",
            calories = 0.0,
            sleepStartAt = at(today) - 480 * 60_000L,
            sleepEndAt = at(today),
        ),
        cycleStart(today.minusDays(28)),
        cycleStart(today),
        logged(today, CycleLog.FLOW_MEDIUM),
        logged(today.minusDays(1), CycleLog.SPOTTING),
        JournalEntry(
            day = today.toString(),
            occurredAt = at(today),
            kind = KIND_ALCOHOL,
            title = "beer",
            calories = 0.0,
            servingG = 16.0,
        ),
        JournalEntry(
            day = today.toString(),
            occurredAt = at(today),
            kind = KIND_STOOL,
            title = "stool",
            subtitle = StoolKind.NORMAL.name,
            calories = 0.0,
        ),
        // With a symptom on it, so the section that prints symptoms without the tally is filled
        // too: "every section the journal can fill" has to include it.
        logged(today.minusDays(3), CycleLog.SEX_PROTECTED, SexNote.PAIN),
        JournalEntry(
            day = today.toString(),
            occurredAt = at(today),
            kind = KIND_MED,
            title = "Metformin",
            calories = 0.0,
            measurementPayloadJson = """{"$MED_COURSE_KEY":1,"$MED_SLOT_KEY":"480"}""",
        ),
        // A dose of the supplement below, ticked, so the vitamin table has a marked figure to read.
        medTakenEntry(plannedDoses(listOf(supplement()), emptyList(), today).first(), today, at(today)),
    ) + painAndWork()

    /** A knee that hurt and a day of work, so the pain and work sections are filled too. */
    private fun painAndWork(): List<JournalEntry> = listOf(
        JournalEntry(
            day = today.toString(),
            occurredAt = at(today),
            kind = KIND_PAIN,
            title = "pain",
            calories = 0.0,
            measurementPayloadJson = encodePainPayload(
                PainReport(regions = listOf(RegionSide(PainRegion.KNEE, BodySide.LEFT)), nrs = 4),
            ),
        ),
        workEntry(listOf(WorkSegment(at(today.minusDays(1)), at(today.minusDays(1)) + 8 * 3_600_000L)), "Work", zone)!!,
    )

    private fun logged(day: LocalDate, log: CycleLog, note: SexNote? = null) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_CYCLE_LOG,
        title = "log",
        calories = 0.0,
        measurementPayloadJson = buildString {
            append("""{"$CYCLE_PAYLOAD_KEY":"${log.name}"""")
            note?.let { append(""","$CYCLE_NOTES_KEY":["${it.name}"]""") }
            append("}")
        },
    )

    private fun labs(day: LocalDate, ldl: Double) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = labsPayload(mapOf(LabMarker.LDL to ldl), fasting = true),
    )

    private fun cycleStart(day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_CYCLE,
        title = "cycle",
        calories = 0.0,
    )

    private fun course() = Medication(
        id = 1,
        name = "Metformin",
        dose = "1 tablet",
        times = "480",
        startDay = from.toString(),
        createdAt = 0,
    )

    /** A course whose label was written down: what the vitamin table reads, where Metformin gives it nothing. */
    private fun supplement() = Medication(
        id = 2,
        name = "Vitamin D3",
        dose = "1 capsule",
        times = "480",
        startDay = from.toString(),
        contents = "VITAMIN_D=25",
        createdAt = 0,
    )

    /**
     * One synthetic record of every kind the written-down history holds.
     *
     * Invented, like every fixture here: a diagnosis, an operation, a vaccination and the two
     * states, so that "everything offered is filled" can be a test rather than a hope.
     */
    private fun history(): List<MedicalHistoryRow> = listOf(
        MedicalHistoryRow(
            key = "h1",
            kind = HistoryKind.CONDITION.name,
            title = "Asthma",
            startDay = today.minusYears(4).toString(),
            updatedAt = at(today),
        ),
        MedicalHistoryRow(
            key = "h2",
            kind = HistoryKind.PROCEDURE.name,
            title = "Appendectomy",
            startDay = today.minusYears(9).toString(),
            updatedAt = at(today),
        ),
        MedicalHistoryRow(
            key = "h3",
            kind = HistoryKind.IMMUNISATION.name,
            title = "Tetanus",
            startDay = today.minusYears(2).toString(),
            updatedAt = at(today),
        ),
        MedicalHistoryRow(
            key = "h4",
            kind = HistoryKind.PREGNANCY.name,
            title = "NOT_PREGNANT",
            startDay = today.minusMonths(6).toString(),
            updatedAt = at(today),
        ),
        MedicalHistoryRow(
            key = "h5",
            kind = HistoryKind.CONTRACEPTION.name,
            title = "IUD_COPPER",
            startDay = today.minusYears(1).toString(),
            updatedAt = at(today),
        ),
    )

    private fun report(
        entries: List<JournalEntry>,
        profile: UserProfile = profile(),
        specialty: DoctorSpecialty? = null,
        parts: Set<ReportPart> = ReportPart.entries.toSet(),
        history: List<MedicalHistoryRow> = emptyList(),
    ) = doctorReport(
        sources = ReportSources(
            profile = profile,
            entries = entries,
            days = listOf(DaySteps(today.toString(), 9_000)),
            medications = listOf(course(), supplement()),
            visits = listOf(
                Appointment(
                    id = 1,
                    specialty = "GP",
                    doctor = "Dr Ivanova",
                    atMs = at(today.minusDays(5)),
                    doneAt = at(today.minusDays(5)),
                ),
            ),
            history = history,
        ),
        request = ReportRequest(from = from, to = today, specialty = specialty, parts = parts),
        today = today,
        zone = zone,
    )

    private fun DoctorReport.parts(): List<ReportPart> = printedParts()

    /** The sections actually carrying something: an empty one is on the sheet, saying it is empty. */
    private fun DoctorReport.printedParts(): List<ReportPart> =
        sections.filter { it.presence == SectionPresence.PRESENT }.map(ReportSection::part)

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    // --- what is offered --------------------------------------------------------------------------

    /** The cycle is offered to women only, and the picker's total is what the page prints. */
    @Test
    fun theCycleIsOfferedToWomenOnly() {
        assertTrue(ReportPart.CYCLE in reportPartsFor("FEMALE"))
        assertFalse(ReportPart.CYCLE in reportPartsFor("MALE"))
        assertFalse(ReportPart.PERSON in reportPartsFor("FEMALE"))
        // Three sections have an owner: the cycle and the pregnancy status beside it, and a
        // man's morning check-ins.
        assertTrue(ReportPart.PREGNANCY in reportPartsFor("FEMALE"))
        assertFalse(ReportPart.PREGNANCY in reportPartsFor("MALE"))
        assertTrue(ReportPart.MORNING_ERECTIONS in reportPartsFor("MALE"))
        assertFalse(ReportPart.MORNING_ERECTIONS in reportPartsFor("FEMALE"))
        assertEquals(reportPartsFor("MALE").size + 1, reportPartsFor("FEMALE").size)
    }

    /** A man is not shown a gynaecologist, and a woman is not shown a urologist. */
    @Test
    fun eachDoctorIsOfferedToTheRightPerson() {
        assertFalse(DoctorSpecialty.GYNECOLOGIST in specialtiesFor("MALE"))
        assertTrue(DoctorSpecialty.GYNECOLOGIST in specialtiesFor("FEMALE"))
        assertFalse(DoctorSpecialty.UROLOGIST in specialtiesFor("FEMALE"))
        assertTrue(DoctorSpecialty.UROLOGIST in specialtiesFor("MALE"))
        assertTrue(DoctorSpecialty.GP in specialtiesFor("MALE"))
        assertTrue(DoctorSpecialty.GP in specialtiesFor("FEMALE"))
    }

    /**
     * A doctor's preselection is a set of sections that exist, that the person is offered, and that
     * is never empty — a chosen doctor who ticks nothing would hand over a PDF of a profile.
     */
    @Test
    fun everyDoctorPreselectsSectionsThisPersonIsOffered() {
        listOf("MALE", "FEMALE").forEach { sex ->
            specialtiesFor(sex).forEach { specialty ->
                val parts = preselectedParts(specialty, sex)
                assertTrue("$specialty for $sex preselected nothing", parts.isNotEmpty())
                assertTrue("$specialty for $sex reaches past the offered list", parts.all { it in reportPartsFor(sex) })
                assertFalse("$specialty preselected the person", ReportPart.PERSON in parts)
            }
        }
    }

    /** The gynaecologist brings the cycle; nobody else does, and the urologist cannot. */
    @Test
    fun thePresetsAreAboutTheirOwnDoctor() {
        assertTrue(ReportPart.CYCLE in preselectedParts(DoctorSpecialty.GYNECOLOGIST, "FEMALE"))
        assertFalse(ReportPart.CYCLE in preselectedParts(DoctorSpecialty.CARDIOLOGIST, "FEMALE"))
        assertEquals(
            setOf(ReportPart.PRESSURE, ReportPart.PULSE),
            preselectedParts(DoctorSpecialty.CARDIOLOGIST, "MALE").intersect(
                setOf(ReportPart.PRESSURE, ReportPart.PULSE, ReportPart.CYCLE, ReportPart.MOOD),
            ),
        )
        assertTrue(ReportPart.NUTRITION in preselectedParts(DoctorSpecialty.DIETITIAN, "MALE"))
        assertTrue(ReportPart.SLEEP in preselectedParts(DoctorSpecialty.SOMNOLOGIST, "MALE"))
        // "My own choice" is every routine section, for the person to cut down or add to by hand:
        // not the sensitive ones, which no preset may tick, and not the reproductive ones, which
        // only the doctors they were written for bring.
        listOf("MALE", "FEMALE").forEach { sex ->
            val own = preselectedParts(DoctorSpecialty.CUSTOM, sex)
            assertEquals(reportPartsFor(sex).filter { it.sensitivity == DataSensitivity.ROUTINE }.toSet(), own)
            assertEquals(own, openingParts(reportPartsFor(sex)))
        }
        assertFalse(ReportPart.CONTRACEPTION in preselectedParts(DoctorSpecialty.CUSTOM, "MALE"))
        assertFalse(ReportPart.PREGNANCY in openingParts(reportPartsFor("FEMALE")))
        assertFalse(ReportPart.CYCLE in openingParts(reportPartsFor("FEMALE")))
        assertTrue(ReportPart.PREGNANCY in preselectedParts(DoctorSpecialty.GYNECOLOGIST, "FEMALE"))
    }

    // --- what the ticks do ------------------------------------------------------------------------

    /** Every section the journal can fill is filled when everything is ticked. */
    @Test
    fun aFullJournalFillsEverySectionOffered() {
        val built = report(everything(), history = history())

        assertEquals(listOf(ReportPart.PERSON) + reportPartsFor("FEMALE"), built.parts())
        assertEquals(reportPartsFor("FEMALE").filter { it in built.filled }.toSet(), built.filled)
    }

    /** One tick is one section: the rest of the record stays on the phone however full it is. */
    @Test
    fun onlyTheTickedSectionsArePrinted() {
        val built = report(everything(), parts = setOf(ReportPart.PRESSURE))

        assertEquals(listOf(ReportPart.PERSON, ReportPart.PRESSURE), built.parts())
        // What was left off is still known to have been there — the picker greys nothing out wrongly.
        assertTrue(ReportPart.LABS in built.filled)
        assertTrue(ReportPart.NUTRITION in built.filled)
        assertFalse(built.empty)
    }

    /** Nothing ticked is a profile on a page, and it is not the same thing as a quarter with nothing in it. */
    @Test
    fun nothingTickedIsNotAnEmptyQuarter() {
        val built = report(everything(), parts = emptySet())

        assertEquals(listOf(ReportPart.PERSON), built.parts())
        assertTrue(built.empty)
        assertTrue("the quarter held ${built.filled}", built.filled.isNotEmpty())
    }

    /**
     * The paragraphs at the foot follow the ticks, not the record. A sheet printed without the
     * pressure must not credit the ESH for a band it never printed, and one without the blood tests
     * must not carry the blood-test paragraph.
     */
    @Test
    fun theNotesCreditOnlyTheBandsThatWerePrinted() {
        val full = report(everything())
        val pressureSource = res(
            R.string.report_note_band_source,
            res(R.string.direct_bluetooth_blood_pressure_title),
            res(R.string.vitals_source_blood_pressure),
        )

        assertTrue(full.notes.contains(pressureSource))
        assertTrue(full.notes.contains(res(R.string.report_note_labs)))
        assertTrue(full.notes.contains(res(R.string.report_note_mood)))

        val weightOnly = report(everything(), parts = setOf(ReportPart.WEIGHT))
        assertFalse(weightOnly.notes.contains(pressureSource))
        assertFalse(weightOnly.notes.contains(res(R.string.report_note_labs)))
        assertFalse(weightOnly.notes.contains(res(R.string.report_note_mood)))
        // The not-a-device paragraph is on every sheet; the BMI one goes with the weight tick, which
        // is the one this sheet kept.
        // The last line is about the ticks, not the record: a sheet that leaves any section off says so.
        assertEquals(
            listOf(
                res(R.string.report_note_not_medical),
                res(R.string.report_note_bmi),
                res(R.string.report_note_chosen),
            ),
            weightOnly.notes,
        )
    }

    /** A cycle ticked on a man's report prints nothing: the gate is the profile, not the tick. */
    @Test
    fun aTickCannotPutTheCycleOnAMansReport() {
        val built = report(everything(), profile = profile(sex = "MALE"), parts = setOf(ReportPart.CYCLE))

        assertEquals(listOf(ReportPart.PERSON), built.parts())
        assertFalse(ReportPart.CYCLE in built.filled)
    }

    // --- who it says it is for --------------------------------------------------------------------

    /** The doctor is printed on the sheet, under the period, and only where one was chosen. */
    @Test
    fun theSheetSaysWhoItWasPutTogetherFor() {
        val forDoctor = report(everything(), specialty = DoctorSpecialty.CARDIOLOGIST)
        val row = forDoctor.sections.first().rows.first { it.label == res(R.string.report_for_doctor) }

        assertEquals(res(DoctorSpecialty.CARDIOLOGIST.titleRes), row.value)
        assertEquals(DoctorSpecialty.CARDIOLOGIST, forDoctor.specialty)

        val anonymous = report(everything())
        assertNull(anonymous.specialty)
        assertFalse(anonymous.sections.first().rows.any { it.label == res(R.string.report_for_doctor) })

        // "My own choice" is not a doctor, and the sheet does not name it as the one it is for.
        val own = report(everything(), specialty = DoctorSpecialty.CUSTOM)
        assertEquals(DoctorSpecialty.CUSTOM, own.specialty)
        assertFalse(own.sections.first().rows.any { it.label == res(R.string.report_for_doctor) })
    }

    // --- the meals and the mood ------------------------------------------------------------------

    /**
     * A week of meals averaged over the seven days that hold them, not over the quarter: a fortnight
     * nobody logged is not a fortnight of fasting, and it is the days that carry the average.
     *
     * Two meals a day rather than one: a day holding a single entry is a day nobody finished logging,
     * and it is left out of the average — see DoctorReportFoodActivityTest.
     */
    @Test
    fun foodIsAveragedOverTheDaysThatHoldIt() {
        val meals = (0L until 7L).flatMap { back ->
            listOf(meal(today.minusDays(back), 350.0, 15.0), meal(today.minusDays(back), 250.0, 10.0))
        }
        val built = report(meals)
        val rows = built.section(ReportPart.NUTRITION)!!.rows

        assertEquals(res(R.string.report_value_unit, 600, res(R.string.unit_kcal)), rows[0].value)
        assertEquals(res(R.string.report_count_of, 7, ReportText.Quantity(R.plurals.count_days, 7)), rows[0].note)
        assertEquals(res(R.string.entry_food_details, "25", "0", "0"), rows[1].value)
        assertEquals(ReportText.Plain("14"), rows[2].value)
    }

    /**
     * A mood nobody answered enough times to read is no section at all — the scale says so itself.
     * Three answers over two days are a figure: the average and the count are printed, and no word.
     */
    @Test
    fun moodNeedsThreeAnswersOverTwoDaysToPrint() {
        assertNull(report(listOf(mood(today, 4))).section(ReportPart.MOOD))

        val built = report(listOf(mood(today.minusDays(1), 4), mood(today, 4), mood(today, 4)))
        val rows = built.section(ReportPart.MOOD)!!.rows

        assertNotNull(built.section(ReportPart.MOOD))
        assertEquals(res(R.string.mood_week_value, "4", 75), rows[0].value)
        assertNull(rows[0].note)
        assertEquals(ReportText.Quantity(R.plurals.mood_entries, 3), rows[1].value)
    }

    /**
     * The word beside the average needs a week of answered days. "Low" over two days of a half-year
     * sheet is the card's one-week rule printed on a document that runs for months.
     */
    @Test
    fun theWordBesideTheMoodNeedsAWeekOfAnsweredDays() {
        fun low(days: Long) = (0L until days).map { mood(today.minusDays(it), 1) }

        val six = report(low(6)).section(ReportPart.MOOD)!!.rows[0]
        val seven = report(low(7)).section(ReportPart.MOOD)!!.rows[0]

        assertEquals(res(R.string.mood_week_value, "1", 0), six.value)
        assertNull(six.note)
        assertEquals(res(R.string.mood_state_low), seven.note)
    }

    private fun DoctorReport.section(part: ReportPart): ReportSection? = sections.firstOrNull {
        it.part == part &&
            it.presence == SectionPresence.PRESENT
    }
}
