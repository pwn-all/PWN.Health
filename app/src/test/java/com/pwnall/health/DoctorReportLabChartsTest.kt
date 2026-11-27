package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The blood tests drawn year to year: one chart per marker the section prints, from the same results
 * and at the same figures as its rows, on an axis long enough that a 2024 panel and a 2026 one stand
 * two years apart; the band shaded is the one the newest row prints; and a course whose written
 * contents pair with the marker marks the day it began.
 *
 * The scenario is the one the section's own tests use: panels on 2024-03-12 and 2026-08-20, vitamin
 * D3 from 2025-11-01, a sheet made on 2026-09-24.
 */
class DoctorReportLabChartsTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 24)
    private val panelA: LocalDate = LocalDate.of(2024, 3, 12)
    private val panelB: LocalDate = LocalDate.of(2026, 8, 20)

    private fun man(unitSystem: String = "METRIC") =
        UserProfile(sex = "MALE", weightKg = 84.0, heightCm = 180, birthDate = "1985-05-10", unitSystem = unitSystem)

    private fun at(day: LocalDate, hour: Int = 9): Long = day.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun panel(day: LocalDate, values: Map<LabMarker, Double>, fasting: Boolean = true) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = labsPayload(values, fasting),
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

    private fun course(id: Long, name: String, start: LocalDate, contents: String) = Medication(
        id = id,
        name = name,
        dose = "1 capsule",
        times = "480",
        startDay = start.toString(),
        contents = contents,
        createdAt = 0,
    )

    private fun labs(
        entries: List<JournalEntry>,
        profile: UserProfile = man(),
        medications: List<Medication> = emptyList(),
        parts: Set<ReportPart> = ReportRequest(today, today).parts,
    ): ReportSection = doctorReport(
        sources = ReportSources(profile, entries, emptyList(), medications = medications),
        request = ReportRequest(from = today.minusMonths(6), to = today, parts = parts),
        today = today,
        zone = ZoneOffset.UTC,
    ).sections.first { it.part == ReportPart.LABS && it.presence == SectionPresence.PRESENT }

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    private fun ReportSection.chartOf(marker: LabMarker): ReportChart? =
        charts.firstOrNull { it.title == res(marker.titleRes) }

    private fun ReportChart.points(): List<ChartPoint> = series.single().points

    /** The marker's own rows: its headline and every detail row under it, up to the next marker. */
    private fun ReportSection.rowsOf(marker: LabMarker): List<ReportRow> {
        val start = rows.indexOfFirst { it.label == res(marker.titleRes) }
        assertTrue("no row for $marker", start >= 0)
        val end = rows.drop(start + 1).indexOfFirst { !it.detail }.let { if (it < 0) rows.size else start + 1 + it }
        return rows.subList(start, end)
    }

    /** The figures the rows print for the marker's results, oldest first: its headline and the dated rows. */
    private fun ReportSection.printedFigures(marker: LabMarker): List<String> =
        rowsOf(marker).filter { it.label == res(marker.titleRes) || it.label is ReportText.Day }
            .map { (it.value as ReportText.Res).args.first() as String }
            .asReversed()

    /** The range the newest result's Reference row prints, as the edges it names. */
    private fun ReportSection.printedBand(marker: LabMarker): ChartBand {
        val reference = rowsOf(marker).first { it.label == res(R.string.report_reference) }
        val range = (reference.value as ReportText.Res).args.first() as ReportText.Res
        val edges = range.args.map { (it as String).toDouble() }
        return when (range.id) {
            R.string.labs_norm_range -> ChartBand(edges[0], edges[1])
            R.string.labs_norm_up_to -> ChartBand(null, edges[0])
            else -> ChartBand(edges[0], null)
        }
    }

    /**
     * The case the chart exists for: a 2024 panel and a 2026 one. The axis runs from the older draw to
     * the end of the period, so they stand two years apart, and each point carries the figure its row
     * prints — "6", not "6.0", because that is what the page says.
     */
    @Test
    fun a2024And2026PanelAreDrawnTwoYearsApartWithBothFiguresOnThem() {
        val section = labs(
            listOf(
                panel(panelA, mapOf(LabMarker.HBA1C to 5.3)),
                panel(panelB, mapOf(LabMarker.HBA1C to 6.0)),
            ),
        )
        val chart = section.chartOf(LabMarker.HBA1C)!!

        assertEquals(panelA, chart.from)
        assertEquals(today, chart.to)
        assertEquals(ChartWidth.HALF, chart.width)
        assertEquals(res(LabMarker.HBA1C.unitLabelRes(false)), chart.unit)
        assertEquals(ChartStyle.LINE_DOTS, chart.series.single().style)
        assertEquals(
            listOf(ChartPoint(panelA, 5.3, "5.3"), ChartPoint(panelB, 6.0, "6")),
            chart.points(),
        )
        assertEquals(section.printedFigures(LabMarker.HBA1C), chart.points().map(ChartPoint::label))
    }

    /**
     * The shading is the newest row's band at the row's own decimals, one-sided where that band is: a
     * ceiling for LDL, a floor for HDL, both for ferritin. A triglyceride after food under a fasting
     * one is shaded with the after-food band the newest row prints, and each point carries the word
     * its row carries; a marker food does not move carries none.
     */
    @Test
    fun theShadedBandIsTheOneTheNewestRowPrints() {
        val section = labs(
            listOf(
                panel(
                    panelA,
                    mapOf(
                        LabMarker.LDL to 3.2,
                        LabMarker.HDL to 1.1,
                        LabMarker.FERRITIN to 24.0,
                        LabMarker.TRIGLYCERIDES to 1.8,
                    ),
                ),
                panel(
                    panelB,
                    mapOf(
                        LabMarker.LDL to 4.6,
                        LabMarker.HDL to 0.9,
                        LabMarker.FERRITIN to 61.0,
                        LabMarker.TRIGLYCERIDES to 1.9,
                    ),
                    fasting = false,
                ),
            ),
        )

        listOf(LabMarker.LDL, LabMarker.HDL, LabMarker.FERRITIN, LabMarker.TRIGLYCERIDES).forEach { marker ->
            assertEquals("$marker", section.printedBand(marker), section.chartOf(marker)!!.band)
        }
        assertNull(section.chartOf(LabMarker.LDL)!!.band!!.low)
        assertNull(section.chartOf(LabMarker.HDL)!!.band!!.high)
        // The after-food "up to 1.9", not the fasting "up to 1.6" the older point was graded against.
        assertEquals(1.9, section.chartOf(LabMarker.TRIGLYCERIDES)!!.band!!.high!!, 0.0)
        assertEquals(
            listOf(res(R.string.labs_fasting), res(R.string.labs_fed)),
            section.chartOf(LabMarker.TRIGLYCERIDES)!!.points().map(ChartPoint::note),
        )
        assertEquals(listOf(null, null), section.chartOf(LabMarker.LDL)!!.points().map(ChartPoint::note))
    }

    /** A marker the app has no band for is drawn without shading rather than with an invented one. */
    @Test
    fun aMarkerWithNoBandIsDrawnUnshaded() {
        val section =
            labs(listOf(panel(panelA, mapOf(LabMarker.INSULIN to 7.0)), panel(panelB, mapOf(LabMarker.INSULIN to 9.0))))

        val chart = section.chartOf(LabMarker.INSULIN)!!
        assertNull(chart.band)
        assertEquals(ChartPoint(panelB, 9.0, "9", res(R.string.labs_fasting)), chart.points().last())
    }

    /**
     * A D3 course begun between the two panels marks the 25-hydroxyvitamin D chart on the day it
     * began, named as the course is — and no other chart, however many the panel fills. A course
     * whose contents nobody wrote down marks nothing whatever its name, and one begun before the
     * oldest result is off the chart.
     */
    @Test
    fun aVitaminDCourseMarksOnlyThe25HydroxyvitaminDChart() {
        val d3Start = LocalDate.of(2025, 11, 1)
        val section = labs(
            listOf(
                panel(panelA, mapOf(LabMarker.VITAMIN_D to 38.0, LabMarker.FERRITIN to 24.0, LabMarker.LDL to 3.2)),
                panel(panelB, mapOf(LabMarker.VITAMIN_D to 82.0, LabMarker.FERRITIN to 61.0, LabMarker.LDL to 4.6)),
            ),
            medications = listOf(
                course(1, "Vitamin D3", d3Start, "VITAMIN_D=50"),
                course(2, "D3 drops", LocalDate.of(2025, 12, 1), ""),
                course(3, "Old D", LocalDate.of(2023, 10, 1), "VITAMIN_D=25"),
            ),
        )

        assertEquals(
            listOf(ChartMark(d3Start, ReportText.Plain("Vitamin D3"))),
            section.chartOf(LabMarker.VITAMIN_D)!!.marks,
        )
        assertEquals(emptyList<ChartMark>(), section.chartOf(LabMarker.FERRITIN)!!.marks)
        assertEquals(emptyList<ChartMark>(), section.chartOf(LabMarker.LDL)!!.marks)
    }

    /** Iron pairs with two markers the section prints, and marks both. */
    @Test
    fun anIronCourseMarksFerritinAndHaemoglobin() {
        val ironStart = LocalDate.of(2026, 2, 1)
        val section = labs(
            listOf(
                panel(panelA, mapOf(LabMarker.FERRITIN to 9.0, LabMarker.HEMOGLOBIN to 108.0)),
                panel(panelB, mapOf(LabMarker.FERRITIN to 38.0, LabMarker.HEMOGLOBIN to 127.0)),
            ),
            medications = listOf(course(1, "Ferrous sulfate", ironStart, "IRON=65")),
        )

        val mark = listOf(ChartMark(ironStart, ReportText.Plain("Ferrous sulfate")))
        assertEquals(mark, section.chartOf(LabMarker.FERRITIN)!!.marks)
        assertEquals(mark, section.chartOf(LabMarker.HEMOGLOBIN)!!.marks)
    }

    /** A course's name is medicine data: without the medicines ticked no chart names one. */
    @Test
    fun noCourseIsNamedWhenTheMedicinesAreNotOnTheSheet() {
        val section = labs(
            listOf(
                panel(panelA, mapOf(LabMarker.VITAMIN_D to 38.0)),
                panel(panelB, mapOf(LabMarker.VITAMIN_D to 82.0)),
            ),
            medications = listOf(course(1, "Vitamin D3", LocalDate.of(2025, 11, 1), "VITAMIN_D=50")),
            parts = ReportRequest(today, today).parts - ReportPart.MEDICATIONS,
        )

        assertTrue(section.charts.all { it.marks.isEmpty() })
    }

    /** A figure the app worked out is drawn like a typed one, at the figures its rows print. */
    @Test
    fun aDerivedMarkerGetsAChart() {
        val section = labs(
            listOf(
                panel(panelA, mapOf(LabMarker.CHOLESTEROL to 5.6, LabMarker.HDL to 1.2, LabMarker.CREATININE to 88.0)),
                panel(panelB, mapOf(LabMarker.CHOLESTEROL to 6.1, LabMarker.HDL to 1.1, LabMarker.CREATININE to 97.0)),
            ),
        )

        listOf(LabMarker.NON_HDL, LabMarker.EGFR).forEach { marker ->
            val chart = section.chartOf(marker)
            assertNotNull("$marker", chart)
            assertEquals("$marker", listOf(panelA, panelB), chart!!.points().map(ChartPoint::day))
            assertEquals("$marker", section.printedFigures(marker), chart.points().map(ChartPoint::label))
            assertEquals("$marker", section.printedBand(marker), chart.band)
        }
        assertEquals(listOf("4.4", "5"), section.chartOf(LabMarker.NON_HDL)!!.points().map(ChartPoint::label))
    }

    /**
     * A meter's fingersticks are counted on a row of their own and are not the laboratory's glucose,
     * so they are not on its chart either: the chart holds the panel's one result.
     */
    @Test
    fun meterGlucoseIsNotOnTheLaboratoryChart() {
        val september = LocalDate.of(2026, 9, 10)
        val section = labs(
            listOf(
                panel(panelA, mapOf(LabMarker.GLUCOSE to 4.9)),
                panel(panelB, mapOf(LabMarker.GLUCOSE to 5.1)),
                meter(september, 7.9),
                meter(september.plusDays(1), 8.4),
            ),
        )

        assertTrue(section.rows.any { it.label == res(R.string.report_lab_other_glucose) })
        assertEquals(listOf(panelA, panelB), section.chartOf(LabMarker.GLUCOSE)!!.points().map(ChartPoint::day))
        assertEquals(1, section.charts.count { it.title == res(LabMarker.GLUCOSE.titleRes) })
    }

    /** An imperial sheet draws in mg/dL, at the figures and the band its rows print in mg/dL. */
    @Test
    fun anImperialProfileIsChartedInItsOwnUnit() {
        val section = labs(
            listOf(
                panel(panelA, mapOf(LabMarker.LDL to 3.2)),
                panel(panelB, mapOf(LabMarker.LDL to 4.6)),
            ),
            profile = man(unitSystem = "IMPERIAL"),
        )

        val chart = section.chartOf(LabMarker.LDL)!!
        assertEquals(res(R.string.labs_unit_mgdl), chart.unit)
        assertEquals(section.printedFigures(LabMarker.LDL), chart.points().map(ChartPoint::label))
        assertEquals(listOf("124", "178"), chart.points().map(ChartPoint::label))
        assertEquals(listOf(124.0, 178.0), chart.points().map(ChartPoint::value))
        assertEquals(section.printedBand(LabMarker.LDL), chart.band)
    }

    /** The charts stand in the order the markers' rows do, one for each. */
    @Test
    fun theChartsFollowTheRows() {
        val markers = mapOf(
            LabMarker.VITAMIN_D to 82.0,
            LabMarker.GLUCOSE to 5.1,
            LabMarker.LDL to 4.6,
            LabMarker.TSH to 2.1,
        )
        val section = labs(listOf(panel(panelA, markers.mapValues { it.value * 0.9 }), panel(panelB, markers)))

        val headlines = section.rows.filter { !it.detail }.map(ReportRow::label)
        assertEquals(headlines, section.charts.map(ReportChart::title))
    }

    /** One result is a row and no chart: a single dot on an axis of its own month says nothing more. */
    @Test
    fun aSingleResultGetsNoChart() {
        val section = labs(listOf(panel(panelB, mapOf(LabMarker.FERRITIN to 320.0))))

        assertTrue(section.rows.any { it.label == res(LabMarker.FERRITIN.titleRes) })
        assertTrue(section.charts.isEmpty())
    }
}
