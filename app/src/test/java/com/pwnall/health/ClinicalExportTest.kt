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
import java.util.Locale

/**
 * The export as a clinical document: what it may carry, what it must never carry by accident, and
 * what it says when it carries nothing.
 *
 * Every fixture here is synthetic and written in this file. No real record, from this project or
 * anywhere else, is in the repository.
 *
 * The cases are the ones a person would notice on paper or in a waiting room: a sheet that says
 * "normal" where it means "nobody measured", a unit converted the wrong way, a day that moved
 * because a clock did, one weighing counted twice because two apps published it, a code asserted
 * that nobody can check, and — the one that matters most — a report for a knee carrying a sexual
 * history nobody asked for.
 */
class ClinicalExportTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val from: LocalDate = today.minusMonths(3)
    private val utc: ZoneId = ZoneOffset.UTC

    private fun profile(
        sex: String = "FEMALE",
        birthDate: String = "1990-04-10",
        unitSystem: String = "METRIC",
    ) = UserProfile(sex = sex, weightKg = 68.0, heightCm = 168, birthDate = birthDate, unitSystem = unitSystem)

    private fun at(day: LocalDate, hour: Int = 9, zone: ZoneId = utc): Long =
        day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private fun measurement(
        day: LocalDate,
        payload: String? = null,
        weightKg: Double? = null,
        source: String? = null,
        at: Long = at(day),
    ) = JournalEntry(
        day = day.toString(),
        occurredAt = at,
        kind = "MEASUREMENT",
        title = "measurement",
        calories = 0.0,
        measurementWeightKg = weightKg,
        measurementPayloadJson = payload,
        measurementSource = source,
    )

    private fun act(day: LocalDate, log: CycleLog) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_CYCLE_LOG,
        title = "log",
        calories = 0.0,
        measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"${log.name}"}""",
    )

    private fun report(
        entries: List<JournalEntry> = emptyList(),
        profile: UserProfile = profile(),
        parts: Set<ReportPart> = ReportPart.entries.toSet(),
        country: CountryProfile = CountryProfileRegistry.international,
        detail: SexualDetail = SexualDetail.SUMMARY,
        days: List<DaySteps> = emptyList(),
        medications: List<Medication> = emptyList(),
        visits: List<Appointment> = emptyList(),
        zone: ZoneId = utc,
        from: LocalDate = this.from,
        history: List<MedicalHistoryRow> = emptyList(),
    ) = doctorReport(
        sources = ReportSources(profile, entries, days, medications, visits, history = history),
        request = ReportRequest(
            from = from,
            to = today,
            country = country,
            parts = parts,
            sexualDetail = detail,
        ),
        today = today,
        zone = zone,
    )

    private fun DoctorReport.section(part: ReportPart): ReportSection? = sections.firstOrNull { it.part == part }

    private fun DoctorReport.present(part: ReportPart): Boolean =
        section(part)?.presence == SectionPresence.PRESENT

    // --- an empty report ---------------------------------------------------------------------------

    /**
     * A person with nothing recorded still gets a document, and the document says which kind of
     * nothing each of its sections holds. The two a patient summary owes an answer for are there,
     * empty, saying nothing was written down — which is the opposite of saying there are none.
     */
    @Test
    fun anEmptyRecordStillProducesASummaryThatSaysWhatIsMissing() {
        val built = report()

        assertTrue(built.empty)
        assertEquals(SectionPresence.NO_DATA, built.section(ReportPart.PROBLEMS)?.presence)
        assertEquals(SectionPresence.NO_DATA, built.section(ReportPart.ALLERGIES)?.presence)
        assertTrue(built.section(ReportPart.PROBLEMS)!!.rows.isEmpty())
        // Never "none known": the app has no way to know that, and saying it would be a claim
        // about the person rather than about the document.
        assertTrue(built.sections.none { it.presence == SectionPresence.NO_KNOWN })
    }

    /** The core sections stand even on a sheet the person cut down to one tick. */
    @Test
    fun theCoreSectionsAreOnEverySheet() {
        val built = report(listOf(measurement(today, weightKg = 70.0)), parts = setOf(ReportPart.WEIGHT))

        assertNotNull(built.section(ReportPart.PROBLEMS))
        assertNotNull(built.section(ReportPart.ALLERGIES))
        assertTrue(built.present(ReportPart.WEIGHT))
    }

    // --- only weight -------------------------------------------------------------------------------

    @Test
    fun aQuarterOfWeighInsIsAWeightSectionAndNothingElseMeasured() {
        val built = report(
            listOf(
                measurement(today.minusDays(40), weightKg = 70.0),
                measurement(today, weightKg = 68.5),
            ),
        )

        assertTrue(built.present(ReportPart.WEIGHT))
        assertFalse(built.present(ReportPart.PULSE))
        assertEquals(SectionPresence.NO_DATA, built.section(ReportPart.PULSE)?.presence)
        assertEquals(2, built.section(ReportPart.WEIGHT)!!.completeness?.measurements)
    }

    // --- thirty days of vitals, summarised rather than dumped ---------------------------------------

    /**
     * Thirty daily pressures produce a section of three rows and an appendix, not thirty rows. The
     * count and the completeness are what tell a doctor the average is an average of a month.
     */
    @Test
    fun aMonthOfPressuresIsSummarisedNotDumped() {
        val entries = (0 until 30).map { back ->
            val day = today.minusDays(back.toLong())
            measurement(day, """{"schema":1,"systolicMmhg":${120 + back % 5},"diastolicMmhg":78}""")
        }

        val section = report(entries).section(ReportPart.PRESSURE)!!

        assertEquals(3, section.rows.size)
        assertEquals(30, section.completeness?.measurements)
        assertEquals(30, section.completeness?.daysWithData)
        assertEquals(93, section.completeness?.expectedDays)
    }

    // --- metric and imperial -----------------------------------------------------------------------

    /**
     * The same record, printed for two health systems: the figure changes, the record does not, and
     * the country is what decides — not the person's own app setting, once they have named one.
     */
    @Test
    fun theCountryDecidesTheScaleThePageIsPrintedOn() {
        val entries = listOf(measurement(today, weightKg = 70.0))

        val metric = report(entries).section(ReportPart.WEIGHT)!!.rows.first()
        val american = report(entries, country = UnitedStatesProfile).section(ReportPart.WEIGHT)!!.rows.first()

        assertEquals(
            ReportText.Res(R.string.report_value_unit, listOf("70", ReportText.Res(R.string.unit_kg))),
            metric.value,
        )
        assertEquals(
            ReportText.Res(
                R.string.report_value_unit,
                listOf(imperialWeight(70.0).pretty(), ReportText.Res(R.string.unit_lb)),
            ),
            american.value,
        )
    }

    /** An imperial profile asking for an international sheet keeps its own scale. */
    @Test
    fun theInternationalProfileDefersToThePersonsOwnUnits() {
        val request = ReportRequest(from = from, to = today)

        assertTrue(request.imperial(profile(unitSystem = "IMPERIAL")))
        assertFalse(request.imperial(profile()))
        assertTrue(ReportRequest(from, today, country = UnitedStatesProfile).imperial(profile()))
        assertFalse(ReportRequest(from, today, country = GermanyProfile).imperial(profile(unitSystem = "IMPERIAL")))
    }

    // --- time zones, and the day a clock changed ---------------------------------------------------

    /**
     * The zone the sheet was made in is printed on it, because the app stores a day rather than an
     * offset: a reader who knows the zone can reason about a row written abroad, and a reader who
     * does not cannot.
     */
    @Test
    fun theSheetSaysWhichZoneItWasMadeIn() {
        val built = report(zone = ZoneId.of("Europe/Berlin"))
        val row = built.sections.first().rows.first {
            it.label == ReportText.Res(R.string.report_timezone, emptyList())
        }

        assertEquals(ReportText.Plain("Europe/Berlin"), row.value)
    }

    /**
     * A day that lost an hour is still a day. The window is compared as stored ISO days, so a
     * spring-forward night neither drops a row nor duplicates one.
     */
    @Test
    fun aDaylightSavingTransitionDoesNotMoveARowOutOfTheWindow() {
        val berlin = ZoneId.of("Europe/Berlin")
        // The 2026 European spring transition: 02:00 becomes 03:00 on the last Sunday in March.
        val shortDay = LocalDate.of(2026, 3, 29)
        val entries = listOf(measurement(shortDay, weightKg = 70.0, at = at(shortDay, hour = 12, zone = berlin)))

        val built = report(entries, zone = berlin, from = LocalDate.of(2026, 3, 1))

        assertTrue(built.present(ReportPart.WEIGHT))
        assertEquals(1, built.section(ReportPart.WEIGHT)!!.completeness?.measurements)
    }

    // --- the same measurement arriving twice -------------------------------------------------------

    /**
     * A watch writes a pressure, the app it pairs with writes it again, and Health Connect hands
     * both over. One squeeze of one cuff must be one reading on the sheet, or the count and the
     * average are both wrong.
     */
    @Test
    fun oneMeasurementArrivingFromTwoAppsIsCountedOnce() {
        val instant = at(today)
        val payload = """{"schema":1,"systolicMmhg":124,"diastolicMmhg":80}"""
        val entries = listOf(
            measurement(today, payload, source = "BLUETOOTH_BPS", at = instant),
            measurement(today, payload, source = "HEALTH_CONNECT", at = instant + 400),
        )

        val section = report(entries).section(ReportPart.PRESSURE)!!

        assertEquals(1, section.completeness?.measurements)
        assertEquals(
            ReportText.Quantity(R.plurals.count_readings, 1),
            section.rows.first { it.label == ReportText.Res(R.string.report_readings, emptyList()) }.value,
        )
    }

    /** Two readings a minute apart are two readings: somebody measuring twice is a real thing. */
    @Test
    fun twoGenuineReadingsAreNotCollapsed() {
        val payload = """{"schema":1,"systolicMmhg":124,"diastolicMmhg":80}"""
        val entries = listOf(
            measurement(today, payload, at = at(today)),
            measurement(today, payload, at = at(today) + 60_000),
        )

        assertEquals(2, report(entries).section(ReportPart.PRESSURE)!!.completeness?.measurements)
    }

    // --- reference ranges --------------------------------------------------------------------------

    /**
     * A band the app applies is printed with the body that published it, and it is not a laboratory's
     * range for this sample. The distinction is on the page as a caveat under the section.
     */
    @Test
    fun anAppliedBandSaysItIsTheAppsOwn() {
        val entries = listOf(measurement(today, """{"schema":1,"systolicMmhg":150,"diastolicMmhg":95}"""))

        val section = report(entries).section(ReportPart.PRESSURE)!!

        assertEquals(R.string.report_caveat_band, section.caveatRes)
        assertNotNull(section.rows.firstOrNull { it.label == ReportText.Res(R.string.report_reference, emptyList()) })
    }

    /**
     * A marker whose band this app withholds prints its figure, its date and whether the sample was
     * fasting — and no verdict at all. Insulin drawn after food is exactly that case: the app
     * refuses to grade it rather than calling it normal.
     */
    @Test
    fun aMarkerWithNoBandGetsNoVerdict() {
        val panel = labsPayload(mapOf(LabMarker.INSULIN to 9.0), fasting = false)
        val entries = listOf(measurement(today, panel).copy(kind = KIND_LABS))

        val rows = report(entries).section(ReportPart.LABS)!!.rows
        val verdicts = setOf(
            res(R.string.vitals_status_above),
            res(R.string.vitals_status_below),
            res(R.string.vitals_status_inside),
            res(R.string.vitals_status_not_above),
            res(R.string.vitals_status_not_below),
            res(R.string.vitals_status_far_above),
            res(R.string.vitals_status_far_below),
        )

        assertTrue(rows.none { it.label == res(R.string.report_reference) })
        val note = rows.first().note as ReportText.Join
        assertTrue("a verdict was printed for a marker the app declines to grade", note.parts.none { it in verdicts })
        assertTrue(note.parts.contains(res(R.string.labs_fed)))
    }

    // --- a medicine with only part of its detail ----------------------------------------------------

    @Test
    fun aCourseWithNoDoseStillPrintsWhatIsKnown() {
        val course = Medication(
            id = 1,
            name = "Levothyroxine",
            dose = "",
            times = "480",
            startDay = from.toString(),
            createdAt = 0,
        )

        val rows = report(medications = listOf(course)).section(ReportPart.MEDICATIONS)!!.rows

        assertEquals(ReportText.Plain("Levothyroxine"), rows[0].label)
        assertEquals(res(R.string.report_course_dates), rows[1].label)
        assertEquals(
            ReportText.Join(listOf(ReportText.Day(from), res(R.string.report_course_ongoing))),
            rows[1].value,
        )
    }

    // --- an incomplete cycle ------------------------------------------------------------------------

    /**
     * One recorded start is a last period and nothing else. No length, no spread, and above all no
     * prediction dressed as a finding.
     */
    @Test
    fun oneCycleStartIsALastPeriodAndNotACycleLength() {
        val entries = listOf(
            JournalEntry(
                day = today.minusDays(10).toString(),
                occurredAt = at(today.minusDays(10)),
                kind = KIND_CYCLE,
                title = "cycle",
                calories = 0.0,
            ),
        )

        val rows = report(entries).section(ReportPart.CYCLE)!!.rows

        assertEquals(1, rows.size)
        assertEquals(res(R.string.report_cycle_lmp), rows.single().label)
    }

    // --- the sensitive section ----------------------------------------------------------------------

    /** With the tick off, nothing about it reaches the sheet — not a row, not a heading, not a count. */
    @Test
    fun aGynaecologyReportWithoutTheTickCarriesNoSexualActivity() {
        val entries = listOf(act(today, CycleLog.SEX_UNPROTECTED), act(today.minusDays(3), CycleLog.SEX_PROTECTED))

        val built = report(entries, parts = preselectedParts(DoctorSpecialty.GYNECOLOGIST, "FEMALE"))

        assertFalse(built.carriesSexualActivity)
        assertNull(built.section(ReportPart.SEXUAL_HEALTH))
        assertTrue(built.sections.none { it.part == ReportPart.SEXUAL_HEALTH })
    }

    /** With the tick on, it carries counts by protection — and says so on the sheet's own notes. */
    @Test
    fun aGynaecologyReportWithTheTickCarriesCountsByProtection() {
        val entries = listOf(
            act(today, CycleLog.SEX_UNPROTECTED),
            act(today.minusDays(3), CycleLog.SEX_PROTECTED),
            act(today.minusDays(9), CycleLog.SEX_PROTECTED),
        )

        val built = report(
            entries,
            parts = preselectedParts(DoctorSpecialty.GYNECOLOGIST, "FEMALE") + ReportPart.SEXUAL_HEALTH,
        )
        val rows = built.section(ReportPart.SEXUAL_HEALTH)!!.rows

        assertTrue(built.carriesSexualActivity)
        assertEquals(ReportText.Plain("3"), rows[0].value)
        assertEquals(res(R.string.cycle_sex_protected), rows[1].label)
        assertEquals(ReportText.Plain("2"), rows[1].value)
        assertEquals(ReportText.Plain("1"), rows[2].value)
        assertTrue(built.notes.contains(res(R.string.report_note_sensitive)))
        // A summary carries no dates; the appendix is where dates would be, and it is empty.
        assertTrue(built.section(ReportPart.SEXUAL_HEALTH)!!.appendix.isEmpty())
    }

    /** The detailed answer is a second decision, and only it puts dated acts on the sheet. */
    @Test
    fun onlyTheDetailedAnswerCarriesDates() {
        val entries = listOf(act(today, CycleLog.SEX_UNPROTECTED))

        val detailed = report(entries, parts = setOf(ReportPart.SEXUAL_HEALTH), detail = SexualDetail.DETAILED)

        assertEquals(1, detailed.section(ReportPart.SEXUAL_HEALTH)!!.appendix.size)
        assertEquals(ReportText.Day(today), detailed.section(ReportPart.SEXUAL_HEALTH)!!.appendix.single().label)
    }

    /**
     * No preset may tick it — the test that matters most in this file. A general practitioner's
     * report, and every other preset the app ships, must be incapable of carrying it by default.
     */
    @Test
    fun noPresetCanEverTickTheSensitiveSection() {
        listOf("MALE", "FEMALE").forEach { sex ->
            specialtiesFor(sex).forEach { specialty ->
                assertFalse(
                    "$specialty preselected the sensitive section for $sex",
                    ReportPart.SEXUAL_HEALTH in preselectedParts(specialty, sex),
                )
            }
        }
    }

    /**
     * A seventeen-year-old is offered the same sections as anybody else and the same gate in front
     * of the sensitive one: no preset ticks it, and nothing about age silently includes or excludes
     * it. What age changes is elsewhere — the BMI verdict the profile card withholds under 18.
     */
    @Test
    fun aSeventeenYearOldGetsTheSameGateAndNoSilentDisclosure() {
        val minor = profile(birthDate = today.minusYears(17).toString())
        val entries = listOf(act(today, CycleLog.SEX_PROTECTED), measurement(today, weightKg = 60.0))

        val byPreset = report(entries, profile = minor, parts = preselectedParts(DoctorSpecialty.GP, "FEMALE"))
        assertFalse(byPreset.carriesSexualActivity)

        val asked = report(entries, profile = minor, parts = setOf(ReportPart.SEXUAL_HEALTH))
        assertTrue(asked.carriesSexualActivity)

        // And the BMI verdict is still withheld at that age, on both sheets.
        val bmiRow = byPreset.sections.first().rows.first { it.label == res(R.string.profile_bmi) }
        assertNull(bmiRow.note)
    }

    // --- country profiles ---------------------------------------------------------------------------

    @Test
    fun theInternationalProfileIsTheFallbackForEveryCountryWithoutOne() {
        assertEquals(InternationalProfile, CountryProfileRegistry.of(null))
        assertEquals(InternationalProfile, CountryProfileRegistry.of("ZZ"))
        assertEquals(InternationalProfile, CountryProfileRegistry.of("JP"))
        assertEquals(UnitedStatesProfile, CountryProfileRegistry.of("us"))
        assertEquals(GermanyProfile, CountryProfileRegistry.of("DE"))
        assertEquals(AustraliaProfile, CountryProfileRegistry.of("AU"))
    }

    /**
     * Each shipped profile writes a date the way its own description says its health system does —
     * the month first in the United States, written out in Britain and Australia — and a new profile
     * cannot ship without its date pinned here. The scale it declares is the scale its sheet prints:
     * the height and the weight in the person block follow the one switch, so no profile can declare
     * centimetres over a sheet in feet and inches.
     */
    @Test
    fun everyShippedProfileIsInternallyConsistent() {
        val dates = mapOf(
            "XX" to "5 Sep 2026",
            "US" to "Sep 5, 2026",
            "DE" to "05.09.2026",
            "GB" to "5 September 2026",
            "AU" to "5 September 2026",
        )
        assertEquals(dates.keys, CountryProfileRegistry.all.map { it.id }.toSet())
        val entries = listOf(measurement(today, weightKg = 70.0))
        CountryProfileRegistry.all.forEach { profile ->
            val printed = LocalDate.of(2026, 9, 5).format(reportDayFormat(Locale.ENGLISH, profile.datePattern))
            assertEquals(profile.id, dates[profile.id], printed)
            val imperial = profile.imperial ?: return@forEach
            val person = report(entries, country = profile).sections.first().rows
            assertEquals(
                profile.id,
                if (imperial) ReportText.Plain(imperialHeight(168)) else res(R.string.unit_cm_value, 168),
                person.first { it.label == res(R.string.height) }.value,
            )
            val weight = if (imperial) imperialWeight(70.0) else 70.0
            val unit = res(if (imperial) R.string.unit_lb else R.string.unit_kg)
            assertEquals(
                profile.id,
                res(R.string.report_value_unit, weight.pretty(), unit),
                person.first { it.label == res(R.string.weight) }.value,
            )
        }
        // The one profile with no scale of its own, and the one country that prints the other one.
        assertNull(InternationalProfile.imperial)
        assertEquals(listOf(UnitedStatesProfile), CountryProfileRegistry.all.filter { it.imperial == true })
    }

    /** Units are UCUM, case-sensitively, and no country profile may change what a unit means. */
    @Test
    fun unitsAreSpelledTheWayUcumSpellsThem() {
        assertEquals("kg", UcumUnit.KILOGRAM.ucum)
        assertEquals("[lb_av]", UcumUnit.POUND.ucum)
        // Cel, not C: C is the coulomb, and a temperature carrying it is a charge.
        assertEquals("Cel", UcumUnit.CELSIUS.ucum)
        assertEquals("mmol/L", UcumUnit.MILLIMOLE_PER_LITRE.ucum)
    }

    /**
     * Every marker's unit is spelled in UCUM, and the filtration rate keeps its normalisation.
     *
     * "mL/min" is a different quantity from what a laboratory reports for eGFR, which is per
     * 1.73 m² of body surface. Each spelling here is one the UCUM terminology accepts, and a lab
     * record stores it.
     */
    @Test
    fun everyMarkerCarriesAUcumUnit() {
        assertEquals("mL/min/{1.73_m2}", LabMarker.EGFR.ucum.ucum)
        assertEquals("u[IU]/mL", LabMarker.INSULIN.ucum.ucum)
        assertEquals("nmol/L", LabMarker.LP_A.ucum.ucum)
        assertEquals("pmol/L", LabMarker.FT4.ucum.ucum)
        assertEquals("mg/L", LabMarker.CRP.ucum.ucum)
        assertEquals("ng/mL", LabMarker.FERRITIN.ucum.ucum)
        assertEquals("nmol/L", LabMarker.VITAMIN_D.ucum.ucum)
        assertEquals("pmol/L", LabMarker.B12.ucum.ucum)
        assertEquals("nmol/L", LabMarker.FOLATE.ucum.ucum)
        assertEquals("mmol/L", LabMarker.MAGNESIUM.ucum.ucum)
        assertEquals("%", LabMarker.TSAT.ucum.ucum)
        assertEquals("m[IU]/L", LabMarker.TSH.ucum.ucum)
        // And the units a field can be switched to, which the panel records by the same spelling.
        assertEquals("pg/mL", LabMarker.B12.otherUnit?.ucum?.ucum)
        assertEquals("ng/dL", LabMarker.FT4.otherUnit?.ucum?.ucum)
        assertEquals("mg/dL", LabMarker.CRP.otherUnit?.ucum?.ucum)
        assertEquals("g/dL", LabMarker.HEMOGLOBIN.otherUnit?.ucum?.ucum)
        // A ratio the app computes has no unit, and UCUM's unity is how that is said.
        assertEquals("1", LabMarker.HOMA_IR.ucum.ucum)
        assertTrue(LabMarker.entries.all { it.ucum.ucum.isNotBlank() })
    }

    // --- text and the absent name -------------------------------------------------------------------

    /**
     * The sheet carries no patient name at all, in any script: there is nowhere in this app to type
     * one. A medicine's name is
     * carried exactly as it was typed, whatever alphabet that was in.
     */
    @Test
    fun nonLatinTextSurvivesAndNoNameIsCarried() {
        val course = Medication(
            id = 1,
            name = "Л-тироксин 50 мкг",
            dose = "1 таблетка",
            times = "480",
            startDay = from.toString(),
            createdAt = 0,
        )

        val built = report(medications = listOf(course))
        val rows = built.section(ReportPart.MEDICATIONS)!!.rows

        assertEquals(ReportText.Plain("Л-тироксин 50 мкг"), rows[0].label)
        val person = built.sections.first()
        assertTrue(
            "the person section carried something that looks like a name",
            person.rows.none { row -> (row.value as? ReportText.Plain)?.text?.contains("Л-") == true },
        )
    }

    // --- what prose may reach the sheet -----------------------------------------------------------

    /**
     * A note typed on a journal entry never reaches the report, and this is the test that says so.
     *
     * The picker promises that what goes out is what was ticked, and every tick is described by a
     * line naming figures — courses, doses, averages. Prose is not that: a note on a workout can
     * say anything at all, including somebody else's name or a sentence about a diagnosis, and it
     * would travel under whichever ordinary tick its entry happens to belong to. Nothing in the
     * app can classify a free-text line, so none of them are printed, and this test is the guard on
     * that boundary.
     */
    @Test
    fun freeTextOnAJournalEntryNeverReachesTheSheet() {
        val secret = "told my physiotherapist about the termination"
        val entries = listOf(
            measurement(today, weightKg = 70.0).copy(note = secret),
            measurement(today, """{"schema":1,"systolicMmhg":118,"diastolicMmhg":76}""").copy(note = secret),
        )

        val built = report(entries)
        val printed: List<String> = built.sections
            .flatMap { section -> section.rows + section.appendix }
            .flatMap { row -> listOfNotNull(row.label, row.value, row.note) }
            .filterIsInstance<ReportText.Plain>()
            .map { it.text }

        assertTrue("a note reached the sheet: $printed", printed.none { secret in it })
    }

    /** A visit with no doctor's name prints the specialty as a word, not as an enum constant. */
    @Test
    fun aVisitWithNoNamePrintsTheSpecialtyAsAWord() {
        val visit = Appointment(
            id = 1,
            specialty = "DENTIST",
            atMs = at(today.minusDays(5)),
            doneAt = at(today.minusDays(5)),
        )

        val rows = report(visits = listOf(visit)).section(ReportPart.VISITS)!!.rows

        assertEquals(res(R.string.checkup_dentist), rows.single().value)
    }

    // --- the symptoms, apart from the tally -------------------------------------------------------

    private fun intimate(day: LocalDate, log: CycleLog, vararg notes: SexNote) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_CYCLE_LOG,
        title = "log",
        calories = 0.0,
        measurementPayloadJson = buildString {
            append("{\"$CYCLE_PAYLOAD_KEY\":\"${log.name}\"")
            if (notes.isNotEmpty()) {
                append(",\"$CYCLE_NOTES_KEY\":[")
                append(notes.joinToString(",") { "\"${it.name}\"" })
                append("]")
            }
            append("}")
        },
    )

    /**
     * A symptom can reach a doctor without the count of acts behind it.
     *
     * This is what the section exists for: printed in the same block as "acts: 12, unprotected: 3",
     * pain during sex could only be reported by handing over the tally. The symptoms section says
     * which symptoms, and nothing else — no number, no date, no protection.
     */
    @Test
    fun theSymptomsTravelWithoutTheTally() {
        val entries = listOf(
            intimate(today.minusDays(3), CycleLog.SEX_PROTECTED, SexNote.PAIN),
            intimate(today.minusDays(2), CycleLog.SEX_UNPROTECTED),
            intimate(today.minusDays(1), CycleLog.SEX_PROTECTED, SexNote.PAIN, SexNote.BLEEDING),
        )

        val built = report(entries, parts = setOf(ReportPart.SEXUAL_SYMPTOMS))
        val symptoms = built.section(ReportPart.SEXUAL_SYMPTOMS)!!

        assertEquals(SectionPresence.PRESENT, symptoms.presence)
        assertEquals(
            listOf(res(R.string.sex_note_pain), res(R.string.sex_note_bleeding)),
            symptoms.rows.map(ReportRow::label),
        )
        // "Reported", not "twice": a count of symptoms is a count of acts wearing a clinical word.
        assertTrue(symptoms.rows.all { it.value == res(R.string.report_symptom_reported) })
        assertTrue(symptoms.appendix.isEmpty())
        assertEquals(R.string.report_caveat_sexual_symptoms, symptoms.caveatRes)
        // And the section that does carry the tally is not on this sheet at all — not even as
        // "not included", which is the rule for everything sensitive.
        assertNull(built.section(ReportPart.SEXUAL_HEALTH))
    }

    /** The tally travels on its own, and carries none of the symptoms with it. */
    @Test
    fun theTallyTravelsWithoutTheSymptoms() {
        val entries = listOf(intimate(today.minusDays(1), CycleLog.SEX_PROTECTED, SexNote.PAIN))

        val acts = report(entries, parts = setOf(ReportPart.SEXUAL_HEALTH)).section(ReportPart.SEXUAL_HEALTH)!!

        assertEquals(res(R.string.report_sexual_acts), acts.rows.first().label)
        assertTrue(acts.rows.none { it.label == res(R.string.sex_note_pain) })
    }

    /** Neither half of it can arrive by a preset, on any specialty, for either body. */
    @Test
    fun noPresetTicksEitherSensitiveSection() {
        DoctorSpecialty.entries.forEach { specialty ->
            listOf("MALE", "FEMALE").forEach { sex ->
                val ticked = preselectedParts(specialty, sex)
                assertFalse("$specialty ticked the acts", ReportPart.SEXUAL_HEALTH in ticked)
                assertFalse("$specialty ticked the symptoms", ReportPart.SEXUAL_SYMPTOMS in ticked)
                assertFalse("$specialty ticked the mornings", ReportPart.MORNING_ERECTIONS in ticked)
            }
        }
        // Suggested is not ticked: the gynaecologist's row puts the symptoms forward, and the
        // person still has to answer the question under it.
        assertTrue(ReportPart.SEXUAL_SYMPTOMS in suggestedParts(DoctorSpecialty.GYNECOLOGIST, "FEMALE"))
    }

    // --- the written-down history ------------------------------------------------------------------

    private fun record(
        kind: HistoryKind,
        title: String,
        start: LocalDate? = null,
        end: LocalDate? = null,
        detail: String = "",
        at: Long = at(today),
    ) = MedicalHistoryRow(
        key = "$kind-$title",
        kind = kind.name,
        title = title,
        detail = detail,
        startDay = start?.toString().orEmpty(),
        endDay = end?.toString().orEmpty(),
        updatedAt = at,
    )

    /**
     * What somebody wrote down is what the problem list prints — and nothing else is added to it.
     *
     * The dates say which of the three shapes each record is in: one that has not ended is "since",
     * one that has is a span, and one nobody dated says so rather than borrowing today.
     */
    @Test
    fun theProblemListPrintsWhatWasWrittenDown() {
        val built = report(
            history = listOf(
                record(HistoryKind.CONDITION, "Asthma", start = today.minusYears(4)),
                record(
                    HistoryKind.CONDITION,
                    "Anaemia",
                    start = today.minusYears(2),
                    end = today.minusYears(1),
                ),
                record(HistoryKind.CONDITION, "Migraine"),
            ),
        )
        val section = built.section(ReportPart.PROBLEMS)!!

        assertEquals(SectionPresence.PRESENT, section.presence)
        assertEquals(
            listOf("Anaemia", "Asthma", "Migraine"),
            section.rows.map { (it.label as ReportText.Plain).text },
        )
        assertEquals(
            res(
                R.string.history_between,
                ReportText.Day(today.minusYears(2)),
                ReportText.Day(today.minusYears(1)),
            ),
            section.rows[0].value,
        )
        assertEquals(res(R.string.history_since, ReportText.Day(today.minusYears(4))), section.rows[1].value)
        assertEquals(res(R.string.history_no_date), section.rows[2].value)
        assertEquals(listOf(Provenance(ProvenanceCategory.MANUAL)), section.provenance)
        assertEquals(R.string.report_caveat_history, section.caveatRes)
    }

    /**
     * A diagnosis the person did not tick is not on the sheet, and the heading still is.
     *
     * The heading has to stay — a patient summary with no problem list reads as a person with no
     * problems — and what stands under it has to be the withheld notice rather than the rows, which
     * is the one thing that could leak a diagnosis onto a sheet its owner meant to keep it off.
     */
    @Test
    fun anUntickedProblemListSaysWithheldRatherThanPrintingIt() {
        val built = report(
            parts = setOf(ReportPart.WEIGHT),
            history = listOf(record(HistoryKind.CONDITION, "Asthma", start = today.minusYears(4))),
        )
        val section = built.section(ReportPart.PROBLEMS)!!

        assertEquals(SectionPresence.NOT_INCLUDED, section.presence)
        assertTrue(section.rows.isEmpty())
    }

    /**
     * An empty history says nothing was written down, not that nothing happened in the window.
     *
     * The two sentences are different claims, and only one of them is true of a person who has
     * never had an operation. Neither is "none", which is the claim this app never makes.
     */
    @Test
    fun anEmptyHistorySaysNothingWasWrittenDownRatherThanNothingHappenedLately() {
        val built = report()

        assertEquals(
            R.string.section_nothing_written,
            built.section(ReportPart.PROCEDURES)!!.emptyLabelRes(),
        )
        assertEquals(
            R.string.section_nothing_written,
            built.section(ReportPart.PROBLEMS)!!.emptyLabelRes(),
        )
        // A measurement keeps the sentence about the period, which is the true one for it.
        assertEquals(
            SectionPresence.NO_DATA.labelRes,
            built.section(ReportPart.PULSE)!!.emptyLabelRes(),
        )
    }

    /** The newest answer stands first and in full; the ones before it are the quiet rows behind it. */
    @Test
    fun theCurrentMethodIsTheNewestOne() {
        val built = report(
            history = listOf(
                record(HistoryKind.CONTRACEPTION, "CONDOM", start = today.minusYears(3)),
                record(HistoryKind.CONTRACEPTION, "IUD_COPPER", start = today.minusMonths(6)),
            ),
        )
        val rows = built.section(ReportPart.CONTRACEPTION)!!.rows

        assertEquals(res(R.string.contraception_iud_copper), rows[0].label)
        assertFalse(rows[0].detail)
        assertEquals(res(R.string.contraception_condom), rows[1].label)
        assertTrue(rows[1].detail)
    }

    /** A pregnancy section is offered to a woman and is not offered at all to a man. */
    @Test
    fun onlyAWomanIsAskedAboutPregnancy() {
        val written = listOf(record(HistoryKind.PREGNANCY, "PREGNANT", start = today.minusMonths(2)))

        assertTrue(report(profile = profile(), history = written).present(ReportPart.PREGNANCY))
        assertFalse(ReportPart.PREGNANCY in reportPartsFor("MALE"))
        // Even asked for by name, which no picker ever does for a man: the section is built from
        // the profile, so there is nothing for a stray tick to print.
        val his = report(profile = profile(sex = "MALE"), history = written)
        assertFalse(his.present(ReportPart.PREGNANCY))
        assertTrue(his.section(ReportPart.PREGNANCY)?.rows.orEmpty().isEmpty())
    }

    // --- the picker's own promises -----------------------------------------------------------------

    /** Every offered section belongs to a group the picker draws, and carries a line about itself. */
    @Test
    fun everyOfferedSectionIsPresentableInThePicker() {
        listOf("MALE", "FEMALE").forEach { sex ->
            reportPartsFor(sex).forEach { part ->
                assertTrue("$part is offered but not pickable", part.pickable)
                assertFalse("$part has no description", part.bodyRes == 0)
                assertFalse("$part has no title", part.titleRes == 0)
            }
        }
        // A core section is printed whatever the ticks say. Two of them are a tick as well, and
        // that is the difference the sheet turns on: unticked, they print "not included by the
        // patient" rather than their contents — which is what lets somebody keep a diagnosis off a
        // sheet without the heading vanishing and reading as "none".
        assertEquals(
            listOf(ReportPart.PERSON, ReportPart.PROBLEMS, ReportPart.ALLERGIES),
            ReportPart.entries.filter { it.core },
        )
        assertFalse(ReportPart.ALLERGIES.pickable)
        // Two sensitive sections, and deliberately two: the symptoms travel without the
        // tally, which is the whole reason they are a section of their own. Both carry every guard the
        // class does — no preset ticks either, "select all" reaches neither, and neither is ever
        // announced as withheld.
        // And a third for a man: the morning check-ins, which carry every guard as well and ask a
        // question of their own rather than riding on the dated sexual-activity one.
        assertEquals(
            listOf(ReportPart.SEXUAL_SYMPTOMS, ReportPart.SEXUAL_HEALTH, ReportPart.MORNING_ERECTIONS),
            ReportPart.entries.filter { it.sensitivity == DataSensitivity.SENSITIVE },
        )
    }

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())
}
