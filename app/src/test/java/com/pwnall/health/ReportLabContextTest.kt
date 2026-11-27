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
 * "Around each blood test" on the doctor's sheet: what it prints beside each draw, which section each
 * line waits for, the notes a dose and a sample call for together, and the words it never uses.
 */
class ReportLabContextTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 24)
    private val zone: ZoneId = ZoneOffset.UTC
    private val man = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1985-05-10")

    private val earlierLdl: LocalDate = LocalDate.of(2026, 3, 10)
    private val laterLdl: LocalDate = LocalDate.of(2026, 9, 1)

    private fun at(day: LocalDate, hour: Int = 9): Long = day.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun panel(day: LocalDate, values: Map<LabMarker, Double>, id: Long) = JournalEntry(
        id = id,
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = labsPayload(values, fasting = true),
    )

    private fun meal(day: LocalDate, hour: Int) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, hour),
        kind = "EAT",
        title = "meal",
        calories = 600.0,
        saturatedFatG = 10.0,
        fiberG = 8.0,
    )

    private fun drink(day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 20),
        kind = KIND_ALCOHOL,
        title = "beer",
        calories = 0.0,
        servingG = 16.0,
    )

    private fun weighIn(day: LocalDate, kg: Double) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 7),
        kind = "MEASUREMENT",
        title = "weight",
        calories = 0.0,
        measurementWeightKg = kg,
    )

    private fun course(
        id: Long,
        name: String,
        contents: String = "",
        start: LocalDate,
        end: LocalDate? = null,
    ) = Medication(
        id = id,
        name = name,
        dose = "1 tablet",
        times = "480",
        startDay = start.toString(),
        endDay = end?.toString(),
        contents = contents,
        createdAt = 0,
    )

    private fun marked(medication: Medication, day: LocalDate, id: Long): JournalEntry {
        val dose = plannedDoses(listOf(medication), emptyList(), day).first()
        return medTakenEntry(dose, day, at(day, 8)).copy(id = id)
    }

    /** Two LDL panels, six weeks of two meals a day before the later one, steps, a weigh-in and drinks. */
    private fun fullDiary(): List<JournalEntry> =
        listOf(
            panel(earlierLdl, mapOf(LabMarker.LDL to 3.4), id = 1),
            panel(laterLdl, mapOf(LabMarker.LDL to 3.0), id = 2),
            weighIn(laterLdl.minusDays(5), 81.5),
            drink(laterLdl.minusDays(3)),
        ) +
            (0L until 42L).flatMap { back ->
                listOf(meal(laterLdl.minusDays(back), 8), meal(laterLdl.minusDays(back), 19))
            }

    private fun steps(): List<DaySteps> = (0L until 20L).map { DaySteps(laterLdl.minusDays(it).toString(), 8_000) }

    private fun report(
        entries: List<JournalEntry>,
        parts: Set<ReportPart> = ReportPart.entries.filter { it.sensitivity != DataSensitivity.SENSITIVE }.toSet(),
        medications: List<Medication> = emptyList(),
        days: List<DaySteps> = emptyList(),
        months: Long = 6,
    ) = doctorReport(
        sources = ReportSources(man, entries, days, medications),
        request = ReportRequest(from = today.minusMonths(months), to = today, parts = parts),
        today = today,
        zone = zone,
    )

    private fun DoctorReport.context(): ReportSection? =
        sections.firstOrNull { it.part == ReportPart.LAB_CONTEXT && it.presence == SectionPresence.PRESENT }

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    private fun ReportSection.labels(): List<ReportText> = rows.map(ReportRow::label)

    // --- what waits for which section ------------------------------------------------------------

    @Test
    fun theSectionNeedsTheBloodTestsTicked() {
        assertNotNull(report(fullDiary()).context())
        val withoutLabs =
            ReportPart.entries.filter { it.sensitivity != DataSensitivity.SENSITIVE }.toSet() - ReportPart.LABS
        assertNull(report(fullDiary(), parts = withoutLabs).context())
    }

    /** One LDL on file is nothing to set a draw beside. */
    @Test
    fun aMarkerWithOneResultHasNoContext() {
        assertNull(report(fullDiary().drop(1)).context())
    }

    /**
     * Each line beside a draw waits for the section it reads: food for nutrition, steps for activity,
     * the weigh-in for weight, alcohol for the social history, the courses for the medicines.
     */
    @Test
    fun eachContextLineAppearsOnlyWithItsSection() {
        val iron = course(1, "Ferrous sulfate", start = laterLdl.minusDays(60))
        val all = ReportPart.entries.filter { it.sensitivity != DataSensitivity.SENSITIVE }.toSet()
        val food = ReportText.Quantity(R.plurals.report_lab_context_food_window, 42)
        val walked = ReportText.Quantity(R.plurals.report_lab_context_steps_window, 42)
        val weight = res(R.string.weight)
        val alcohol = ReportText.Quantity(R.plurals.report_lab_context_alcohol_window, 42)
        val courses = res(R.string.report_lab_context_courses)
        fun labels(parts: Set<ReportPart>) =
            report(fullDiary(), parts, listOf(iron), steps()).context()!!.labels()

        val everything = labels(all)
        listOf(food, walked, weight, alcohol, courses).forEach { assertTrue("$it", it in everything) }

        mapOf(
            ReportPart.NUTRITION to food,
            ReportPart.ACTIVITY to walked,
            ReportPart.WEIGHT to weight,
            ReportPart.SOCIAL to alcohol,
            ReportPart.MEDICATIONS to courses,
        ).forEach { (part, line) ->
            val without = labels(all - part)
            assertFalse("$line printed without $part", line in without)
            listOf(food, walked, weight, alcohol, courses).filter { it != line }.forEach { other ->
                assertTrue("$other went with $part", other in without)
            }
        }
    }

    /**
     * The draw is named with how many results it holds, oldest first — the figures themselves stand
     * once, in the blood-test section — and the diary sits under the later one.
     */
    @Test
    fun eachDrawIsNamedWithItsFigureAndTheDiaryAroundIt() {
        val section = report(fullDiary(), days = steps()).context()!!
        val days = section.rows.filter { it.label is ReportText.Day && !it.detail }

        assertEquals(listOf(ReportText.Day(earlierLdl), ReportText.Day(laterLdl)), days.map(ReportRow::label))
        assertEquals(ReportText.Quantity(R.plurals.count_readings, 1), days.last().value)

        val steps = section.rows.single {
            it.label == ReportText.Quantity(R.plurals.report_lab_context_steps_window, 42) &&
                section.rows.indexOf(it) > section.rows.indexOf(days.last())
        }
        assertEquals(res(R.string.report_steps_per_day, 8_000L.formatted()), steps.value)
        assertEquals(ReportText.Quantity(R.plurals.count_days, 20), steps.note)

        val weight = section.rows.last { it.label == res(R.string.weight) }
        assertEquals(ReportText.Day(laterLdl.minusDays(5)), weight.note)
    }

    /**
     * GGT's window reads alcohol and nothing else, and the food line does not print it: averaged over
     * the days a drink was logged on, a glass every fourth day would read as that glass a day. The alcohol
     * line is the one figure for those weeks, over every day the diary was kept, and it waits for the
     * social history; the window is never said to be logged on days that carry too little.
     */
    @Test
    fun alcoholBeforeADrawIsPrintedOnceOverTheDaysTheDiaryWasKept() {
        val journal = listOf(
            panel(earlierLdl, mapOf(LabMarker.GGT to 30.0), id = 1),
            panel(laterLdl, mapOf(LabMarker.GGT to 28.0), id = 2),
        ) +
            (0L until 56L).flatMap { back ->
                val day = laterLdl.minusDays(back)
                listOf(meal(day, 8), meal(day, 19)) + listOfNotNull(drink(day).takeIf { back % 4 == 0L })
            }
        val all = ReportPart.entries.filter { it.sensitivity != DataSensitivity.SENSITIVE }.toSet()
        val window = ReportText.Quantity(R.plurals.report_lab_context_food_window, 56)
        val alcohol = ReportText.Quantity(R.plurals.report_lab_context_alcohol_window, 56)

        val rows = report(journal, all).context()!!.rows
        assertFalse(window in rows.map(ReportRow::label))
        // Fourteen drinks of 16 g over the 56 days kept: 4 g a day, 28 g a week — not 16 g a day.
        val drinks = rows.last { it.label == alcohol }
        assertEquals(
            res(R.string.report_lab_context_per_week, res(R.string.report_value_unit, 28, res(R.string.unit_grams))),
            drinks.value,
        )
        assertEquals(ReportText.Quantity(R.plurals.report_lab_context_typed_days, 56), drinks.note)

        val without = report(journal, all - ReportPart.SOCIAL).context()!!.rows
        assertFalse(alcohol in without.map(ReportRow::label))
        assertFalse(window in without.map(ReportRow::label))
        assertFalse(without.any { it.value == res(R.string.report_lab_context_food_no_columns) })
    }

    /**
     * A sexual act or a solo one is not a day the diary was kept, whatever the sheet ticks: counted, it
     * would carry the day onto the alcohol line of a sheet that leaves it out.
     */
    @Test
    fun aSexualEntryIsNotADayWithEntriesTypedIn() {
        fun intimate(day: LocalDate, kind: String, payload: String?) = JournalEntry(
            day = day.toString(),
            occurredAt = at(day, 22),
            kind = kind,
            title = "log",
            calories = 0.0,
            measurementPayloadJson = payload,
        )
        val journal = listOf(
            panel(earlierLdl, mapOf(LabMarker.LDL to 3.4), id = 1),
            panel(laterLdl, mapOf(LabMarker.LDL to 3.0), id = 2),
            drink(laterLdl.minusDays(3)),
            intimate(
                laterLdl.minusDays(10),
                KIND_CYCLE_LOG,
                """{"$CYCLE_PAYLOAD_KEY":"${CycleLog.SEX_PROTECTED.name}"}""",
            ),
            intimate(laterLdl.minusDays(11), KIND_MASTURBATION, null),
        )
        val row = report(journal, parts = ReportPart.entries.toSet()).context()!!.rows
            .last { it.label == ReportText.Quantity(R.plurals.report_lab_context_alcohol_window, 42) }

        assertEquals(ReportText.Quantity(R.plurals.report_lab_context_typed_days, 1), row.note)
    }

    /** Nor is a morning check-in: a day holding only one would carry its existence onto the alcohol line. */
    @Test
    fun aMorningCheckIsNotADayWithEntriesTypedIn() {
        val morning = morningCheckEntry(
            "Morning",
            laterLdl.minusDays(12),
            MorningCheck(MorningErection.NONE, at(laterLdl.minusDays(12), 7), WakeSource.TYPED),
        )
        val journal = listOf(
            panel(earlierLdl, mapOf(LabMarker.LDL to 3.4), id = 1),
            panel(laterLdl, mapOf(LabMarker.LDL to 3.0), id = 2),
            drink(laterLdl.minusDays(3)),
            morning,
        )
        val row = report(journal, parts = ReportPart.entries.toSet()).context()!!.rows
            .last { it.label == ReportText.Quantity(R.plurals.report_lab_context_alcohol_window, 42) }

        assertEquals(ReportText.Quantity(R.plurals.report_lab_context_typed_days, 1), row.note)
    }

    /**
     * Nor is a day with only the work timer on it: a Start and a Stop write nothing else down, and
     * each such day would read as a day without a drink, pulling the week's grams towards nought.
     */
    @Test
    fun aWorkTimerDayIsNotADayWithEntriesTypedIn() {
        val sessions = (4L..8L).mapNotNull { back ->
            val day = laterLdl.minusDays(back)
            workEntry(listOf(WorkSegment(at(day, 9), at(day, 17))), "Work", zone)
        }
        val journal = listOf(
            panel(earlierLdl, mapOf(LabMarker.LDL to 3.4), id = 1),
            panel(laterLdl, mapOf(LabMarker.LDL to 3.0), id = 2),
            drink(laterLdl.minusDays(3)),
        ) + sessions
        val row = report(journal, parts = ReportPart.entries.toSet()).context()!!.rows
            .last { it.label == ReportText.Quantity(R.plurals.report_lab_context_alcohol_window, 42) }

        assertEquals(5, sessions.size)
        assertEquals(ReportText.Quantity(R.plurals.report_lab_context_typed_days, 1), row.note)
    }

    /** A window the diary cannot describe is refused with its counts, never averaged from three days. */
    @Test
    fun aThinFoodWindowIsRefusedWithItsCounts() {
        val thin = listOf(
            panel(earlierLdl, mapOf(LabMarker.LDL to 3.4), id = 1),
            panel(laterLdl, mapOf(LabMarker.LDL to 3.0), id = 2),
        ) +
            (0L until 3L).flatMap { back ->
                listOf(meal(laterLdl.minusDays(back), 8), meal(laterLdl.minusDays(back), 19))
            }
        val row = report(thin).context()!!.rows
            .last { it.label == ReportText.Quantity(R.plurals.report_lab_context_food_window, 42) }

        assertEquals(
            res(
                R.string.report_lab_context_not_enough,
                res(R.string.report_count_of, 3, ReportText.Quantity(R.plurals.count_days, 42)),
            ),
            row.value,
        )
    }

    @Test
    fun noWeighInNearTheDrawSaysSo() {
        val row = report(fullDiary().filterNot { it.measurementWeightKg != null }).context()!!.rows
            .last { it.label == res(R.string.weight) }

        assertEquals(res(R.string.report_lab_context_no_weigh_in), row.value)
        assertNull(row.note)
    }

    /**
     * A course nobody marked a dose of prints "not marked" — a doctor reads "0%" as a refused treatment —
     * and one that was marked prints its counts, never a percentage.
     */
    @Test
    fun aCourseNeverMarkedSaysNotMarked() {
        val statin = course(1, "Atorvastatin", start = laterLdl.minusDays(60))
        val never = report(fullDiary(), medications = listOf(statin)).context()!!.rows
            .last { it.label == ReportText.Plain("Atorvastatin") }
        assertEquals(res(R.string.report_lab_context_not_marked), never.note)

        val ticked = fullDiary() + marked(statin, laterLdl.minusDays(1), id = 50)
        val counted = report(ticked, medications = listOf(statin)).context()!!.rows
            .last { it.label == ReportText.Plain("Atorvastatin") }
        assertEquals(
            res(R.string.report_lab_context_marked, 1, ReportText.Quantity(R.plurals.report_lab_context_doses, 42)),
            counted.note,
        )
    }

    /** A course started between the two draws is named under the later one, with its dates. */
    @Test
    fun aCourseStartedBetweenTheDrawsIsNamed() {
        val statin = course(1, "Atorvastatin", start = earlierLdl.plusDays(30))
        val row = report(fullDiary(), medications = listOf(statin)).context()!!.rows
            .single { it.label == res(R.string.report_lab_context_started) }

        assertEquals(
            ReportText.Join(
                listOf(
                    res(
                        R.string.report_changes_item,
                        ReportText.Plain("Atorvastatin"),
                        res(
                            R.string.report_period_value,
                            ReportText.Day(earlierLdl.plusDays(30)),
                            res(R.string.report_course_ongoing),
                        ),
                    ),
                ),
                ", ",
            ),
            row.value,
        )
    }

    /**
     * A course that began and stopped between two LDL draws is named under the later LDL with both its
     * dates, although a TSH was drawn between them: each block looks back to its own markers' results.
     */
    @Test
    fun aCourseBetweenAMarkersOwnDrawsIsNamedUnderTheLaterOne() {
        val began = LocalDate.of(2026, 4, 1)
        val stopped = LocalDate.of(2026, 5, 15)
        val statin = course(1, "Atorvastatin", start = began, end = stopped)
        val journal = listOf(
            panel(LocalDate.of(2025, 6, 1), mapOf(LabMarker.TSH to 1.2), id = 1),
            panel(earlierLdl, mapOf(LabMarker.LDL to 3.4), id = 2),
            panel(LocalDate.of(2026, 6, 1), mapOf(LabMarker.TSH to 1.4), id = 3),
            panel(laterLdl, mapOf(LabMarker.LDL to 3.0), id = 4),
        )
        val row = report(journal, medications = listOf(statin)).context()!!.rows
            .dropWhile { it.label != ReportText.Day(laterLdl) }
            .single { it.label == res(R.string.report_lab_context_started) }

        assertEquals(
            ReportText.Join(
                listOf(
                    res(
                        R.string.report_changes_item,
                        ReportText.Plain("Atorvastatin"),
                        res(R.string.report_period_value, ReportText.Day(began), ReportText.Day(stopped)),
                    ),
                ),
                ", ",
            ),
            row.value,
        )
    }

    /**
     * A draw of LDL and TSH looks back to the older of their earlier results: a course begun after the
     * earlier LDL and before the earlier TSH stands between the two LDLs.
     */
    @Test
    fun aCourseIsLookedForBackToTheOldestEarlierResultOfTheDraw() {
        val began = LocalDate.of(2026, 4, 1)
        val statin = course(1, "Atorvastatin", start = began)
        val journal = listOf(
            panel(earlierLdl, mapOf(LabMarker.LDL to 3.4), id = 1),
            panel(LocalDate.of(2026, 6, 1), mapOf(LabMarker.TSH to 1.2), id = 2),
            panel(laterLdl, mapOf(LabMarker.LDL to 3.0, LabMarker.TSH to 1.4), id = 3),
        )
        val row = report(journal, medications = listOf(statin)).context()!!.rows
            .single { it.label == res(R.string.report_lab_context_started) }

        val dates = res(R.string.report_period_value, ReportText.Day(began), res(R.string.report_course_ongoing))
        assertEquals(
            ReportText.Join(listOf(res(R.string.report_changes_item, ReportText.Plain("Atorvastatin"), dates)), ", "),
            row.value,
        )
    }

    // --- the doses beside a draw -----------------------------------------------------------------

    /** Biotin marked the day before a TSH draw is said beside it; marked three days before, it is not. */
    @Test
    fun biotinWithinADayOfATshDrawGetsANote() {
        val draw = LocalDate.of(2026, 9, 10)
        val tsh = panel(draw, mapOf(LabMarker.TSH to 1.2), id = 1)
        fun notes(markedOn: LocalDate): List<ReportRow> {
            val biotin = course(5, "Biotin", contents = "BIOTIN=10000", start = markedOn, end = markedOn)
            val section = report(listOf(tsh, marked(biotin, markedOn, id = 9)), medications = listOf(biotin)).context()
            return section?.rows.orEmpty().filter { it.label == res(Nutrient.BIOTIN.titleRes) }
        }

        val dayBefore = notes(draw.minusDays(1)).single()
        assertEquals(
            res(
                R.string.safety_biotin,
                nutrientAmountText(Nutrient.BIOTIN, 10_000.0),
                ReportText.Join(listOf(res(LabMarker.TSH.titleRes)), ", "),
            ),
            dayBefore.value,
        )
        assertTrue(notes(draw.minusDays(3)).isEmpty())
    }

    /** A creatine course marked in the week before a creatinine draw is said beside it. */
    @Test
    fun creatineMarkedBeforeACreatinineDrawGetsANote() {
        val draw = LocalDate.of(2026, 9, 10)
        val creatine = course(6, "Creatine", contents = "CREATINE=5", start = draw.minusDays(20))
        val journal = listOf(panel(draw, mapOf(LabMarker.CREATININE to 105.0), id = 1)) +
            (1L..5L).map { marked(creatine, draw.minusDays(it), id = 20 + it) }
        val rows = report(journal, medications = listOf(creatine)).context()!!.rows

        assertTrue(rows.any { (it.value as? ReportText.Res)?.id == R.string.safety_creatine })
    }

    /** Vitamin D marked above NASEM's 100 µg upper level, read off the table and not typed here. */
    @Test
    fun vitaminDAboveTheUpperLevelIsSaid() {
        val draw = LocalDate.of(2026, 9, 10)
        val d3 = course(7, "Vitamin D3", contents = "VITAMIN_D=125", start = draw.minusDays(40))
        val journal = listOf(panel(draw, mapOf(LabMarker.VITAMIN_D to 140.0), id = 1)) +
            (0L until 30L).map { marked(d3, draw.minusDays(it), id = 100 + it) }
        val rows = report(journal, medications = listOf(d3)).context()!!.rows

        val intake = rows.single { (it.value as? ReportText.Res)?.id == R.string.safety_vitamin_d_intake }
        assertEquals(
            res(
                R.string.safety_vitamin_d_intake,
                nutrientAmountText(Nutrient.VITAMIN_D, 125.0),
                nutrientAmountText(Nutrient.VITAMIN_D, 100.0),
            ),
            intake.value,
        )
        // 140 nmol/L is over the band's own 125 edge, drawn while the doses were marked.
        assertTrue(rows.any { (it.value as? ReportText.Res)?.id == R.string.safety_vitamin_d_level })
    }

    /**
     * Iron marked above its upper level is said beside a draw of the markers it is read with, not
     * beside an LDL.
     */
    @Test
    fun anUpperLevelNoteWaitsForItsMarker() {
        val iron = course(4, "Iron", contents = "IRON=65", start = laterLdl.minusDays(40))
        val doses = (0L until 30L).map { marked(iron, laterLdl.minusDays(it), id = 100 + it) }
        fun intakeNotes(journal: List<JournalEntry>) = report(journal + doses, medications = listOf(iron))
            .context()!!.rows.filter { (it.value as? ReportText.Res)?.id == R.string.safety_iron_intake }

        assertTrue(intakeNotes(fullDiary()).isEmpty())
        val withFerritin = fullDiary() + panel(laterLdl, mapOf(LabMarker.FERRITIN to 90.0), id = 3)
        assertEquals(1, intakeNotes(withFerritin).size)
    }

    /** Without the medicines on the sheet, no note reads the doses. */
    @Test
    fun noDoseNoteWithoutTheMedicines() {
        val draw = LocalDate.of(2026, 9, 10)
        val biotin = course(5, "Biotin", contents = "BIOTIN=10000", start = draw, end = draw)
        val journal = listOf(panel(draw, mapOf(LabMarker.TSH to 1.2), id = 1), marked(biotin, draw, id = 9))
        val parts =
            ReportPart.entries.filter { it.sensitivity != DataSensitivity.SENSITIVE }.toSet() - ReportPart.MEDICATIONS

        assertNull(report(journal, parts, medications = listOf(biotin)).context())
    }

    // --- each course -----------------------------------------------------------------------------

    /**
     * A vitamin D course on the sheet: D3 from November 2025 between a 2024 and a 2026 panel. The course
     * prints its amount a day, the two results with their dates, what else differed, and the doses
     * marked — "not marked" here, since nobody ticked one.
     */
    @Test
    fun aVitaminDCourseIsSetBesideItsTwoPanels() {
        val d3 = course(3, "Vitamin D3", contents = "VITAMIN_D=50", start = LocalDate.of(2025, 11, 1))
        val journal = listOf(
            panel(LocalDate.of(2024, 3, 12), mapOf(LabMarker.VITAMIN_D to 38.0), id = 1),
            panel(LocalDate.of(2026, 6, 20), mapOf(LabMarker.VITAMIN_D to 82.0), id = 2),
        )
        val context = report(journal, medications = listOf(d3)).context()!!
        val rows = context.rows

        val header = rows.single { it.label == ReportText.Plain("Vitamin D3") && !it.detail }
        assertEquals(
            ReportText.Join(
                listOf(
                    res(
                        R.string.report_value_unit,
                        res(Nutrient.VITAMIN_D.titleRes),
                        res(R.string.report_per_day_rate, nutrientAmountText(Nutrient.VITAMIN_D, 50.0)),
                    ),
                ),
            ),
            header.value,
        )
        // The figures on the marker's own line, each qualification on a line of its own under it.
        val at = rows.indexOfFirst { it.label == res(LabMarker.VITAMIN_D.titleRes) && it.detail }
        val lines = listOf(rows[at].value) +
            rows.drop(at + 1).takeWhile { it.label == ReportText.Plain("") }.map(ReportRow::value)
        val first = lines.first() as ReportText.Res
        assertEquals(R.string.supplement_sentence, first.id)
        assertEquals(R.string.supplement_compared, (first.args.single() as ReportText.Res).id)
        assertTrue(res(R.string.supplement_flag_before_period) in lines)
        assertTrue(res(R.string.supplement_flag_before_old) in lines)
        assertEquals(
            res(R.string.report_lab_context_not_marked),
            rows.single { it.label == res(R.string.report_lab_context_doses_marked_label) }.value,
        )
        // Heaney's slope goes in once, as a footnote about the published average, never as a figure of this person's.
        assertTrue(res(R.string.report_lab_context_heaney) in context.footnotes)
        assertFalse(rows.any { it.value == res(R.string.report_lab_context_heaney) })
    }

    /** A course with no amount a day says so once, on its own line, and not again under its marker. */
    @Test
    fun aMissingAmountIsSaidOnceOnTheSheet() {
        val d3 = course(3, "Vitamin D3", contents = "VITAMIN_D=50", start = LocalDate.of(2025, 11, 1))
            .copy(times = "")
        val journal = listOf(
            panel(LocalDate.of(2024, 3, 12), mapOf(LabMarker.VITAMIN_D to 38.0), id = 1),
            panel(LocalDate.of(2026, 6, 20), mapOf(LabMarker.VITAMIN_D to 82.0), id = 2),
        )
        val said = report(journal, medications = listOf(d3)).context()!!.rows
            .flatMap { listOfNotNull(it.label, it.value, it.note) }
            .flatMap(::idsIn)
            .count { it == R.string.supplement_amount_not_computed }

        assertEquals(1, said)
    }

    /** The food before each result is printed only with the nutrition section, and refused when thin. */
    @Test
    fun theNutrientFromFoodWaitsForNutritionAndIsNeverNought() {
        val d3 = course(3, "Vitamin D3", contents = "VITAMIN_D=50", start = LocalDate.of(2025, 11, 1))
        val journal = listOf(
            panel(LocalDate.of(2024, 3, 12), mapOf(LabMarker.VITAMIN_D to 38.0), id = 1),
            panel(LocalDate.of(2026, 6, 20), mapOf(LabMarker.VITAMIN_D to 82.0), id = 2),
        )
        val label = res(R.string.supplement_food_label, res(Nutrient.VITAMIN_D.titleRes))
        val row = report(journal, medications = listOf(d3)).context()!!.rows.single { it.label == label }

        assertEquals(
            ReportText.Join(
                listOf(
                    res(
                        R.string.report_lab_context_at,
                        ReportText.Day(LocalDate.of(2024, 3, 12)),
                        res(R.string.report_lab_context_not_recorded),
                    ),
                    res(
                        R.string.report_lab_context_at,
                        ReportText.Day(LocalDate.of(2026, 6, 20)),
                        res(R.string.report_lab_context_not_recorded),
                    ),
                ),
                "; ",
            ),
            row.value,
        )

        val parts =
            ReportPart.entries.filter { it.sensitivity != DataSensitivity.SENSITIVE }.toSet() - ReportPart.NUTRITION
        assertFalse(report(journal, parts, medications = listOf(d3)).context()!!.labels().contains(label))
    }

    // --- the notes -------------------------------------------------------------------------------

    /**
     * The reference change value, log-normal with the laboratory's imprecision assumed at half the
     * within-person variation: LDL's 7.4% comes to +25.7% and −20.5%, printed whole.
     */
    @Test
    fun theReferenceChangeIsWorkedOutFromTheWithinPersonVariation() {
        assertEquals(26 to 20, referenceChange(7.4))
        assertEquals(23 to 19, referenceChange(6.76))
        assertEquals(9 to 8, referenceChange(2.67))
    }

    /** One note for the markers set side by side, and none for a marker with one result. */
    @Test
    fun theReferenceChangeNoteNamesOnlyTheMarkersSetSideBySide() {
        val journal = fullDiary() + panel(laterLdl, mapOf(LabMarker.TSH to 1.2), id = 3)
        // A footnote at the foot of the sheet, not a row squeezed into the section's columns.
        val note = report(journal).context()!!.footnotes
            .single { (it as? ReportText.Res)?.id == R.string.report_lab_context_rcv }

        assertEquals(
            res(
                R.string.report_lab_context_rcv,
                ReportText.Join(
                    listOf(res(R.string.report_lab_context_rcv_marker, 26, 20, res(LabMarker.LDL.titleRes))),
                    "; ",
                ),
            ),
            note,
        )
    }

    /**
     * A folate course's results are shown beside it, not as a before and an after, so they add no
     * marker to the note on how far two results wander: both are from before the period here, and
     * nothing else on the sheet sets two folates side by side.
     */
    @Test
    fun aPairShownBesideItsCourseAddsNoMarkerToTheReferenceChangeNote() {
        val folic = course(8, "Folic acid", contents = "FOLIC_ACID=400", start = LocalDate.of(2025, 1, 10))
        val journal = listOf(
            panel(LocalDate.of(2025, 1, 5), mapOf(LabMarker.FOLATE to 12.0), id = 1),
            panel(LocalDate.of(2025, 3, 1), mapOf(LabMarker.FOLATE to 30.0), id = 2),
        )
        val section = report(journal, medications = listOf(folic)).context()!!

        assertTrue(section.rows.any { it.label == res(LabMarker.FOLATE.titleRes) && it.detail })
        assertFalse(section.footnotes.any { (it as? ReportText.Res)?.id == R.string.report_lab_context_rcv })
    }

    // --- the words -------------------------------------------------------------------------------

    /**
     * Nothing this section or the course page can say claims a cause, grades a change, names a risk
     * or recommends anything: every string it adds, and every string a built section carries.
     */
    @Test
    fun nothingItCanSayUsesAForbiddenWord() {
        val xml = File("src/main/res/values/ui.xml").readText()
        val strings = Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        val plurals = Regex("""<plurals name="([a-z0-9_]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        val all = strings + plurals
        val added = all.filterKeys { name ->
            ADDED_PREFIXES.any { name.startsWith(it) } || name in ADDED_NAMES
        }
        assertTrue("the new strings were not found", added.size > 40)

        val names = idNames(R.string::class.java) + idNames(R.plurals::class.java)
        val emitted = scenarios().flatMap { section ->
            section.rows.flatMap { listOfNotNull(it.label, it.value, it.note) } +
                listOfNotNull(section.caveatRes?.let { res(it) })
        }.flatMap(::idsIn).mapNotNull { names[it] }.toSet()
        assertTrue("no scenario printed anything", emitted.size > 20)

        (added + all.filterKeys { it in emitted }).forEach { (name, text) ->
            FORBIDDEN.find(text)?.let { throw AssertionError("$name says “${it.value}”: $text") }
        }
    }

    /** Sections built from every case above that prints one, for the words test to read. */
    private fun scenarios(): List<ReportSection> {
        val draw = LocalDate.of(2026, 9, 10)
        val d3 = course(3, "Vitamin D3", contents = "VITAMIN_D=125", start = LocalDate.of(2025, 11, 1))
        val iron =
            course(4, "Iron", contents = "IRON=65", start = LocalDate.of(2026, 2, 1), end = LocalDate.of(2026, 5, 1))
        val folic = course(8, "Folic acid", contents = "FOLIC_ACID=1500;VITAMIN_B12=1000", start = draw.minusDays(40))
        val biotin = course(5, "Biotin", contents = "BIOTIN=10000;CREATINE=5", start = draw.minusDays(3), end = draw)
        val medications = listOf(d3, iron, folic, biotin)
        val journal = fullDiary() + listOf(
            panel(LocalDate.of(2024, 3, 12), mapOf(LabMarker.VITAMIN_D to 38.0, LabMarker.FERRITIN to 20.0), id = 11),
            panel(
                LocalDate.of(2026, 6, 20),
                mapOf(
                    LabMarker.VITAMIN_D to 140.0,
                    LabMarker.FERRITIN to 260.0,
                    LabMarker.CRP to 14.0,
                    LabMarker.TSAT to 30.0,
                ),
                id = 12,
            ),
            panel(
                draw,
                mapOf(
                    LabMarker.TSH to 1.2,
                    LabMarker.CREATININE to 105.0,
                    LabMarker.FOLATE to 30.0,
                    LabMarker.B12 to 150.0,
                ),
                id = 13,
            ),
        ) + (0L until 30L).flatMap { back ->
            listOfNotNull(
                marked(d3, draw.minusDays(back), id = 1_000 + back),
                marked(folic, draw.minusDays(back), id = 2_000 + back),
                if (back <= 3) marked(biotin, draw.minusDays(back), id = 3_000 + back) else null,
            )
        }
        return listOfNotNull(
            report(journal, medications = medications, days = steps(), months = 24).context(),
            report(fullDiary(), medications = listOf(course(9, "Statin", start = earlierLdl.plusDays(3)))).context(),
            report(fullDiary().drop(1).take(1) + fullDiary().drop(4).take(6)).context(),
        )
    }

    private fun idsIn(text: ReportText): List<Int> = when (text) {
        is ReportText.Res -> listOf(text.id) + text.args.filterIsInstance<ReportText>().flatMap(::idsIn)
        is ReportText.Quantity -> listOf(text.id)
        is ReportText.Join -> text.parts.flatMap(::idsIn)
        is ReportText.Day, is ReportText.Plain -> emptyList()
    }

    private fun idNames(type: Class<*>): Map<Int, String> =
        type.fields.associate { it.getInt(null) to it.name }

    private companion object {
        val ADDED_PREFIXES = listOf("report_lab_context_", "supplement_", "safety_")
        val ADDED_NAMES = setOf("report_caveat_lab_context", "med_course_blood_tests_body")

        /** The house list: causes, verdicts, risk and advice, and the words for what the app does not measure. */
        val FORBIDDEN = Regex(
            "\\b(because|due to|caus\\w*|led to|resulted in|improv\\w*|worsen\\w*|better|worse|helped|harmed|" +
                "effect\\w*|impact\\w*|linked to|associated with|risk\\w*|predict\\w*|\\w*likely|suggest\\w*|" +
                "indicat\\w*|significan\\w*|abnormal\\w*|deficien\\w*|stress\\w*|libido|dehydrat\\w*|fast food|" +
                "junk|unhealthy|consider\\w*|check\\w*|retest\\w*|get tested|reduc\\w*|increas\\w*|stop\\w*|" +
                "ask about)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
