package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The steps and the weigh-in beside a blood draw, each read by a rule the rest of the sheet keeps.
 * Beside [ReportLabContextTest], whose class has no room left for them.
 */
class ReportLabContextRowsTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 24)
    private val man = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1985-05-10")

    private val earlierLdl: LocalDate = LocalDate.of(2026, 3, 10)
    private val laterLdl: LocalDate = LocalDate.of(2026, 9, 1)

    private fun at(day: LocalDate, hour: Int): Long = day.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun panel(day: LocalDate, ldl: Double, id: Long) = JournalEntry(
        id = id,
        day = day.toString(),
        occurredAt = at(day, 9),
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = labsPayload(mapOf(LabMarker.LDL to ldl), fasting = true),
    )

    private fun weighIn(day: LocalDate, kg: Double) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 7),
        kind = "MEASUREMENT",
        title = "weight",
        calories = 0.0,
        measurementWeightKg = kg,
    )

    /** The section on a half-year sheet over two LDL draws and [entries]. */
    private fun context(entries: List<JournalEntry>, days: List<DaySteps> = emptyList()): ReportSection =
        doctorReport(
            sources = ReportSources(man, listOf(panel(earlierLdl, 3.4, 1), panel(laterLdl, 3.0, 2)) + entries, days),
            request = ReportRequest(from = today.minusMonths(6), to = today),
            today = today,
            zone = ZoneOffset.UTC,
        ).sections.first { it.part == ReportPart.LAB_CONTEXT && it.presence == SectionPresence.PRESENT }

    /**
     * Steps a day are rounded as the changes section rounds its own: 8,000 and 8,001 on alternate days
     * are 8,000.5 a day, which that section prints as 8,001, and so does this one rather than cutting
     * it down to 8,000.
     */
    @Test
    fun stepsADayAreRoundedAsTheChangesSectionRoundsThem() {
        val days = (0L until 20L).map { DaySteps(laterLdl.minusDays(it).toString(), 8_000L + it % 2) }

        val steps = context(emptyList(), days).rows
            .last { it.label == ReportText.Quantity(R.plurals.report_lab_context_steps_window, 42) }

        assertEquals(ReportText.Res(R.string.report_steps_per_day, listOf(8_001L.sheetCount())), steps.value)
    }

    /**
     * Of two weigh-ins as near the draw, the one before it, as every other line beside a draw reads the
     * days before the sample.
     */
    @Test
    fun ofTwoWeighInsAsNearTheDrawTheOneBeforeItIsPrinted() {
        val weighIns = listOf(weighIn(laterLdl.plusDays(3), 83.0), weighIn(laterLdl.minusDays(3), 81.5))

        val weight = context(weighIns).rows.last { it.label == ReportText.Res(R.string.weight, emptyList()) }

        assertEquals(ReportText.Day(laterLdl.minusDays(3)), weight.note)
    }
}
