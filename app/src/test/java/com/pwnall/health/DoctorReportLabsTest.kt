package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The blood-test section of the doctor's sheet across the years: a panel from 2024 under one from
 * 2026, each result with its own fasting state and verdict, a meter's glucose kept apart from the
 * laboratory's, and figures printed on the side of the range their verdict names.
 *
 * Today is fixed so that the 24-month window starts on 2024-09-23 and a March 2024 panel lies
 * outside every period the picker offers.
 */
class DoctorReportLabsTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 23)

    private fun man(birthDate: String = "1985-05-10") =
        UserProfile(sex = "MALE", weightKg = 84.0, heightCm = 180, birthDate = birthDate)

    private fun at(day: LocalDate, hour: Int = 12, minute: Int = 0): Long =
        day.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun panel(
        day: LocalDate,
        values: Map<LabMarker, Double>,
        fasting: Boolean = true,
        ranges: Map<LabMarker, ReportedRange> = emptyMap(),
        specimen: LabSpecimen? = null,
        method: String = "",
    ) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = labsPayload(values, fasting, "", specimen, method, ranges),
    )

    private fun meter(day: LocalDate, mmol: Double) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, hour = 7),
        kind = "MEASUREMENT",
        title = "Glucose",
        calories = 0.0,
        measurementSource = "BLUETOOTH_GLS",
        measurementPayloadJson = """{"schema":1,"concentrationMmolL":$mmol}""",
    )

    private fun report(entries: List<JournalEntry>, months: Long, profile: UserProfile = man()) = doctorReport(
        sources = ReportSources(profile, entries, emptyList()),
        request = ReportRequest(from = today.minusMonths(months), to = today),
        today = today,
        zone = ZoneOffset.UTC,
    )

    private fun DoctorReport.labs(): ReportSection =
        sections.first { it.part == ReportPart.LABS && it.presence == SectionPresence.PRESENT }

    /** The marker's own rows: its headline and every detail row under it, up to the next marker. */
    private fun ReportSection.rowsOf(marker: LabMarker): List<ReportRow> {
        val start = rows.indexOfFirst { it.label == res(marker.titleRes) }
        assertTrue("no row for $marker", start >= 0)
        val end = rows.drop(start + 1).indexOfFirst { !it.detail }.let { if (it < 0) rows.size else start + 1 + it }
        return rows.subList(start, end)
    }

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    private fun ReportRow.noteParts(): List<ReportText> = (note as? ReportText.Join)?.parts ?: listOfNotNull(note)

    /** A detail row of two words, which is how the sheet says what a figure is not. */
    private fun detail(label: Int, value: Int) = ReportRow(res(label), res(value), detail = true)

    private fun figure(text: String, marker: LabMarker) =
        res(R.string.report_value_unit, text, res(marker.unitLabelRes(false)))

    private val panelA = LocalDate.of(2024, 3, 12)
    private val panelB = LocalDate.of(2026, 8, 20)

    private fun twoPanels() = listOf(
        panel(panelA, mapOf(LabMarker.HBA1C to 5.3, LabMarker.LDL to 3.2)),
        panel(panelB, mapOf(LabMarker.HBA1C to 6.0, LabMarker.LDL to 4.6)),
    )

    /**
     * One panel in 2024, one in 2026. However long the period, the 2024 result stands under the 2026
     * one with its own date and the words that say it is from before the period — and the exported
     * file carries both, because it is built from what the rows print.
     */
    @Test
    fun a2024PanelStandsUnderThe2026OneAtSixAndTwentyFourMonths() {
        listOf(6L, 24L).forEach { months ->
            val section = report(twoPanels(), months).labs()
            val hba1c = section.rowsOf(LabMarker.HBA1C)

            assertEquals("$months months", figure("6", LabMarker.HBA1C), hba1c.first().value)
            assertFalse(hba1c.first().noteParts().contains(res(R.string.report_lab_before_period)))
            val earlier = hba1c.single { it.label == ReportText.Day(panelA) }
            assertEquals(figure("5.3", LabMarker.HBA1C), earlier.value)
            assertTrue(earlier.detail)
            assertTrue("$months months", earlier.noteParts().contains(res(R.string.report_lab_before_period)))
            assertTrue(earlier.noteParts().contains(res(R.string.vitals_status_not_above)))
        }
    }

    /**
     * Three months after a spring panel the section is not "no data": the newest result is printed,
     * marked as older than the period, and the picker knows the section has something in it.
     */
    @Test
    fun aQuarterAfterTheLastPanelStillPrintsIt() {
        val spring = LocalDate.of(2026, 5, 14)
        val entries = listOf(
            panel(panelA, mapOf(LabMarker.LDL to 3.2)),
            panel(spring, mapOf(LabMarker.LDL to 4.1)),
        )

        val built = report(entries, months = 3)
        val ldl = built.labs().rowsOf(LabMarker.LDL)

        assertTrue(ReportPart.LABS in built.filled)
        assertEquals(figure("4.1", LabMarker.LDL), ldl.first().value)
        assertTrue(ldl.first().noteParts().contains(res(R.string.report_lab_before_period)))
        assertTrue(ldl.first().noteParts().contains(ReportText.Day(spring)))
        assertNotNull(ldl.firstOrNull { it.label == ReportText.Day(panelA) })
    }

    /**
     * An earlier result was read against its own band. A fasting triglyceride of 1.8 is above the
     * fasting 1.7; a fed one of 1.9 is inside the fed 2.0. Printed as "above" under "up to 1.9", the
     * earlier row would read as the sheet contradicting itself, so it says it was fasting, and its own
     * range stands under it.
     */
    @Test
    fun anEarlierResultKeepsItsOwnFastingStateVerdictAndBand() {
        val fastingDay = LocalDate.of(2024, 11, 5)
        val fedDay = LocalDate.of(2026, 5, 14)
        val entries = listOf(
            panel(fastingDay, mapOf(LabMarker.TRIGLYCERIDES to 1.8), fasting = true),
            panel(fedDay, mapOf(LabMarker.TRIGLYCERIDES to 1.9), fasting = false),
        )

        val rows = report(entries, months = 24).labs().rowsOf(LabMarker.TRIGLYCERIDES)
        val unit = res(R.string.labs_unit_mmol)
        val source = res(R.string.labs_band_source_triglycerides)

        assertEquals(
            ReportText.Join(
                listOf(ReportText.Day(fedDay), res(R.string.labs_fed), res(R.string.vitals_status_not_above)),
            ),
            rows[0].note,
        )
        assertEquals(
            ReportRow(
                res(R.string.report_reference),
                res(R.string.report_value_unit, res(R.string.labs_norm_up_to, "1.9"), unit),
                source,
                detail = true,
            ),
            rows[1],
        )
        val earlier = rows.indexOfFirst { it.label == ReportText.Day(fastingDay) }
        assertEquals(
            ReportText.Join(listOf(res(R.string.labs_fasting), res(R.string.vitals_status_above))),
            rows[earlier].note,
        )
        assertEquals(
            ReportRow(
                res(R.string.report_reference),
                res(R.string.report_value_unit, res(R.string.labs_norm_up_to, "1.6"), unit),
                source,
                detail = true,
            ),
            rows[earlier + 1],
        )
    }

    /**
     * A meter's glucose is not a laboratory result: as the marker's newest it would push the panel's
     * own glucose off the sheet and into the exported file as "laboratory". It has a row of its own,
     * counted over the period and named by who wrote it, and its mean is rounded to the unit's step:
     * printed as if typed, the mean of these four would read 6.475.
     */
    @Test
    fun aMetersGlucoseIsCountedApartFromThePanels() {
        val drawn = LocalDate.of(2026, 5, 14)
        val entries = listOf(
            panel(LocalDate.of(2024, 6, 10), mapOf(LabMarker.GLUCOSE to 5.2)),
            panel(drawn, mapOf(LabMarker.GLUCOSE to 5.6)),
            meter(today.minusDays(30), 6.1),
            meter(today.minusDays(20), 7.2),
            meter(today.minusDays(10), 5.4),
            meter(today, 7.2),
        )

        val section = report(entries, months = 6).labs()
        val glucose = section.rowsOf(LabMarker.GLUCOSE)
        val own = section.rows.single { it.label == res(R.string.report_lab_other_glucose) }

        assertEquals(figure("5.6", LabMarker.GLUCOSE), glucose.first().value)
        val earlier = glucose.single { it.label == ReportText.Day(LocalDate.of(2024, 6, 10)) }
        assertEquals(figure("5.2", LabMarker.GLUCOSE), earlier.value)
        assertEquals(ReportText.Quantity(R.plurals.count_readings, 4), own.value)
        assertTrue(own.noteParts().contains(ReportText.Quantity(R.plurals.count_days, 4)))
        assertTrue(own.noteParts().contains(res(R.string.provenance_device)))
        val spread = res(R.string.report_min_mean_max, "5.4", "6.5", "7.2")
        assertTrue(own.noteParts().contains(res(R.string.report_value_unit, spread, res(R.string.labs_unit_mmol))))
        assertEquals("6.475", LabMarker.GLUCOSE.printed(listOf(6.1, 7.2, 5.4, 7.2).average(), imperialUnits = false))
        val sources = section.provenance.map(Provenance::category)
        assertTrue(sources.containsAll(listOf(ProvenanceCategory.DEVICE, ProvenanceCategory.MANUAL)))
        assertEquals(R.string.report_caveat_band, section.caveatRes)
    }

    /**
     * A figure is printed as typed, and the range beside it at the same precision: a glucose of 6.08
     * is "6.08" under "3.9 – 6.09", not "6.1" under "3.9 – 6" and "inside the band"; a suppressed TSH of
     * 0.035 is 0.035, not 0.0.
     */
    @Test
    fun aFigureAtAnEdgePrintsOnItsVerdictsSide() {
        val entries = listOf(panel(panelB, mapOf(LabMarker.GLUCOSE to 6.08, LabMarker.TSH to 0.035)))

        val section = report(entries, months = 6).labs()
        val glucose = section.rowsOf(LabMarker.GLUCOSE)
        val tsh = section.rowsOf(LabMarker.TSH)

        assertEquals(figure("6.08", LabMarker.GLUCOSE), glucose[0].value)
        assertTrue(glucose[0].noteParts().contains(res(R.string.vitals_status_inside)))
        assertEquals(
            res(R.string.report_value_unit, res(R.string.labs_norm_range, "3.9", "6.09"), res(R.string.labs_unit_mmol)),
            glucose[1].value,
        )
        assertEquals(figure("0.035", LabMarker.TSH), tsh[0].value)
        assertTrue(tsh[0].noteParts().contains(res(R.string.vitals_status_far_below)))
        // Food does not move a TSH band, so the sheet does not say "fasting sample" beside it.
        assertFalse(tsh[0].noteParts().contains(res(R.string.labs_fasting)))
    }

    /**
     * The figures the app worked out say so, and carry no range the laboratory printed for the
     * figure they were worked out from.
     */
    @Test
    fun aDerivedFigureSaysTheAppCalculatedIt() {
        val entries = listOf(
            panel(
                panelB,
                mapOf(LabMarker.CREATININE to 98.0, LabMarker.CHOLESTEROL to 5.4, LabMarker.HDL to 1.1),
                ranges = mapOf(LabMarker.CREATININE to ReportedRange(low = 62.0, high = 106.0)),
                specimen = LabSpecimen.SERUM,
                method = "Invitro",
            ),
        )

        val section = report(entries, months = 6).labs()

        listOf(LabMarker.EGFR, LabMarker.NON_HDL).forEach { marker ->
            val rows = section.rowsOf(marker)
            assertTrue(rows.none { it.label == res(R.string.report_lab_reported_range_label) })
            assertTrue(rows.contains(detail(R.string.labs_method, R.string.report_lab_calculated)))
            assertTrue(rows.none { it.value == ReportText.Plain("Invitro") })
        }
        val creatinine = section.rowsOf(LabMarker.CREATININE)
        assertTrue(creatinine.any { it.label == res(R.string.report_lab_reported_range_label) })
    }

    /**
     * A figure with no band says why. Insulin is graded by nothing; a fifteen-year-old's alkaline
     * phosphatase is read against no adult band, while his glucose is read against the adult lines,
     * which say they are adult ones, and his creatinine says why no filtration rate was worked out.
     */
    @Test
    fun aFigureWithNoBandSaysWhy() {
        val teen = man(birthDate = "2011-05-10")
        val entries = listOf(
            panel(
                panelB,
                mapOf(
                    LabMarker.ALP to 260.0,
                    LabMarker.GLUCOSE to 6.5,
                    LabMarker.INSULIN to 9.0,
                    LabMarker.CREATININE to 60.0,
                ),
            ),
        )

        val section = report(entries, months = 6, profile = teen).labs()

        val alp = section.rowsOf(LabMarker.ALP)
        assertEquals(ReportText.Join(listOf(ReportText.Day(panelB))), alp[0].note)
        assertTrue(alp.contains(detail(R.string.labs_norm_status, R.string.labs_note_adult_band)))
        assertTrue(alp.none { it.label == res(R.string.report_reference) })

        // 6.5 fasting is over WHO's 6.1, a prediabetes line ISPAD applies to the young too. A band
        // cut to the diabetes line alone would print it "inside the band" under a range ending at 6.9.
        val glucose = section.rowsOf(LabMarker.GLUCOSE)
        assertTrue(glucose[0].noteParts().contains(res(R.string.vitals_status_above)))
        assertEquals(
            res(R.string.report_value_unit, res(R.string.labs_norm_range, "3.9", "6"), res(R.string.labs_unit_mmol)),
            glucose[1].value,
        )
        assertEquals(res(R.string.labs_band_source_youth, res(R.string.labs_band_source_glucose)), glucose[1].note)

        val insulin = section.rowsOf(LabMarker.INSULIN)
        assertTrue(insulin.contains(detail(R.string.labs_norm_status, R.string.labs_note_ungraded)))

        val creatinine = section.rowsOf(LabMarker.CREATININE)
        assertTrue(creatinine.contains(detail(R.string.labs_marker_egfr, R.string.labs_egfr_pediatric)))
    }

    /**
     * A panel whose payload says nothing about fasting is read from the meals before it — including
     * the dinner the evening before the period starts, which a history cut to the period never saw.
     */
    @Test
    fun theFirstMorningOfThePeriodSeesTheDinnerBeforeIt() {
        val from = today.minusMonths(6)
        val dinner = JournalEntry(
            day = from.minusDays(1).toString(),
            occurredAt = at(from.minusDays(1), hour = 19, minute = 30),
            kind = "EAT",
            title = "Dinner",
            calories = 700.0,
        )
        val silent = panel(from, emptyMap()).copy(
            occurredAt = at(from, hour = 7, minute = 40),
            measurementPayloadJson = """{"schema":1,"concentrationMmolL":6.4}""",
        )

        val glucose = report(listOf(dinner, silent), months = 6).labs().rowsOf(LabMarker.GLUCOSE)

        assertTrue(glucose[0].noteParts().contains(res(R.string.labs_fasting)))
        assertTrue(glucose[0].noteParts().contains(res(R.string.vitals_status_above)))
    }
}
