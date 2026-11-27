package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Which days count as days the diary was kept, for the line that spreads the drinks before a blood
 * test over them. Beside [ReportLabContextTest], whose class has no room left for it.
 */
class ReportLabContextTypedDaysTest {
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

    private fun drink(day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 20),
        kind = KIND_ALCOHOL,
        title = "beer",
        calories = 0.0,
        servingG = 16.0,
    )

    private fun report(entries: List<JournalEntry>, parts: Set<ReportPart>) = doctorReport(
        sources = ReportSources(man, entries, emptyList(), emptyList()),
        request = ReportRequest(from = today.minusMonths(6), to = today, parts = parts),
        today = today,
        zone = zone,
    )

    private fun DoctorReport.context(): ReportSection? =
        sections.firstOrNull { it.part == ReportPart.LAB_CONTEXT && it.presence == SectionPresence.PRESENT }

    /**
     * A day Health Connect brought a workout to — the row carries its record key and no source — is
     * not one, nor is a day with only a planned workout or an exercise left undone on it. The changes
     * section leaves them out too; counted here, the same drinks would be spread over days nobody kept.
     */
    @Test
    fun aSyncedWorkoutAPlanOrAnExerciseLeftUndoneIsNotADayWithEntriesTypedIn() {
        fun workout(back: Long, kind: String = "ACTIVITY") = JournalEntry(
            day = laterLdl.minusDays(back).toString(),
            occurredAt = at(laterLdl.minusDays(back), 18),
            kind = kind,
            title = "Run",
            calories = 0.0,
            activityCode = "STRENGTH",
        )
        val synced = (4L..8L).map { workout(it).copy(healthConnectRecordKey = "Health Connect:com.watch:$it") }
        val journal = listOf(
            panel(earlierLdl, mapOf(LabMarker.LDL to 3.4), id = 1),
            panel(laterLdl, mapOf(LabMarker.LDL to 3.0), id = 2),
            drink(laterLdl.minusDays(3)),
            workout(9, KIND_PLAN),
            workout(10, KIND_NOT_DONE),
        ) + synced
        val row = report(journal, parts = ReportPart.entries.toSet()).context()!!.rows
            .last { it.label == ReportText.Quantity(R.plurals.report_lab_context_alcohol_window, 42) }

        assertEquals(ReportText.Quantity(R.plurals.report_lab_context_typed_days, 1), row.note)
        // The same workout typed in by hand is a day the diary was kept.
        val typed = report(journal + workout(11), parts = ReportPart.entries.toSet()).context()!!.rows
            .last { it.label == ReportText.Quantity(R.plurals.report_lab_context_alcohol_window, 42) }
        assertEquals(ReportText.Quantity(R.plurals.report_lab_context_typed_days, 2), typed.note)
    }
}
