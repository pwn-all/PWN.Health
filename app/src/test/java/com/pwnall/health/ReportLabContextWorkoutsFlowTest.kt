package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The two lines "Around each blood test" prints only beside the markers their research is about: the
 * workouts before an ALT or AST draw, and the days of heavy flow before a ferritin or haemoglobin one,
 * each with its study at the foot of the sheet once it lists something.
 */
class ReportLabContextWorkoutsFlowTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 24)
    private val man = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1985-05-10")
    private val woman = man.copy(sex = "FEMALE")
    private val earlier: LocalDate = LocalDate.of(2026, 3, 10)
    private val later: LocalDate = LocalDate.of(2026, 9, 1)
    private val everything = ReportPart.entries.filter { it.sensitivity != DataSensitivity.SENSITIVE }.toSet()

    private val workouts = ReportText.Quantity(R.plurals.report_lab_context_workouts_window, 14)
    private val heavyFlow = ReportText.Quantity(R.plurals.report_lab_context_heavy_flow_window, 90)
    private val workoutsNote =
        res(R.string.report_lab_context_background_workouts, res(R.string.report_lab_context_source_workouts))
    private val flowNote =
        res(R.string.report_lab_context_background_flow, res(R.string.report_lab_context_source_flow))

    private fun at(day: LocalDate, hour: Int): Long = day.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Two panels of [marker], one before the period and one inside it. */
    private fun draws(marker: LabMarker, first: Double, second: Double) = listOf(earlier to first, later to second)
        .mapIndexed { index, (day, value) ->
            JournalEntry(
                id = index + 1L,
                day = day.toString(),
                occurredAt = at(day, 9),
                kind = KIND_LABS,
                title = "Labs",
                calories = 0.0,
                measurementPayloadJson = labsPayload(mapOf(marker to value), fasting = true),
            )
        }

    private fun workout(day: LocalDate, title: String, hour: Int = 18, kind: String = "ACTIVITY") = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, hour),
        kind = kind,
        title = title,
        calories = 0.0,
        activityCode = "STRENGTH",
    )

    private fun flow(day: LocalDate, log: CycleLog) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 8),
        kind = KIND_CYCLE_LOG,
        title = "flow",
        calories = 0.0,
        measurementPayloadJson = """{"$CYCLE_PAYLOAD_KEY":"${log.name}"}""",
    )

    /** The section on a sheet of the last six months. */
    private fun context(entries: List<JournalEntry>, parts: Set<ReportPart> = everything, profile: UserProfile = man) =
        doctorReport(
            sources = ReportSources(profile, entries, emptyList()),
            request = ReportRequest(from = today.minusMonths(6), to = today, parts = parts),
            today = today,
            zone = ZoneOffset.UTC,
        ).sections.single { it.part == ReportPart.LAB_CONTEXT && it.presence == SectionPresence.PRESENT }

    /** The later draw's line; the earlier draw has one of its own. */
    private fun ReportSection.valueOf(label: ReportText): ReportText = rows.last { it.label == label }.value

    private fun ReportSection.prints(label: ReportText): Boolean = rows.any { it.label == label }

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    /**
     * The workouts 1 to 14 days before an ALT draw, by title and day, oldest first: not a plan, not one
     * on the draw day, whose time against the draw is not known, and not one 15 days before.
     */
    @Test
    fun workoutsBeforeALiverDrawAreListedByTitleAndDay() {
        val section = context(
            draws(LabMarker.ALT, 25.0, 48.0) + listOf(
                workout(later.minusDays(15), "Rowing"),
                workout(later.minusDays(14), "Swim"),
                workout(later.minusDays(3), "Leg day", hour = 18),
                workout(later.minusDays(3), "Squats", hour = 19),
                workout(later.minusDays(3), "Leg day", hour = 20),
                workout(later.minusDays(2), "Push day", kind = KIND_PLAN),
                workout(later.minusDays(1), "Run"),
                workout(later, "Cycling"),
            ),
        )
        fun on(daysBefore: Int, vararg titles: String) = res(
            R.string.report_lab_context_at,
            ReportText.Quantity(R.plurals.visit_remind_days, daysBefore),
            ReportText.Join(titles.map(ReportText::Plain), ", "),
        )

        assertEquals(
            ReportText.Join(listOf(on(14, "Swim"), on(3, "Leg day", "Squats"), on(1, "Run")), "; "),
            section.valueOf(workouts),
        )
        assertTrue(workoutsNote in section.footnotes)
    }

    /**
     * No workout in the window says "Not recorded" and cites nothing; without the activity section there
     * is no line, and a creatinine draw has none, whatever was logged before it.
     */
    @Test
    fun workoutsWaitForTheActivitySectionAndALiverEnzyme() {
        val none = context(draws(LabMarker.ALT, 25.0, 48.0))
        assertEquals(res(R.string.report_lab_context_not_recorded), none.valueOf(workouts))
        assertFalse(workoutsNote in none.footnotes)

        val legDay = workout(later.minusDays(3), "Leg day")
        assertFalse(
            context(draws(LabMarker.ALT, 25.0, 48.0) + legDay, everything - ReportPart.ACTIVITY).prints(workouts),
        )
        assertFalse(context(draws(LabMarker.CREATININE, 80.0, 95.0) + legDay).prints(workouts))
    }

    /**
     * Heavy flow before a ferritin draw is counted over the days any flow was logged, the draw day among
     * them: spotting is not a day of flow, a day logged twice is one day, and 90 or 100 days before is
     * outside the window.
     */
    @Test
    fun heavyFlowBeforeAnIronDrawIsCountedOverTheDaysLogged() {
        val section = context(
            draws(LabMarker.FERRITIN, 40.0, 22.0) + listOf(
                flow(later, CycleLog.FLOW_HEAVY),
                flow(later.minusDays(30), CycleLog.FLOW_HEAVY),
                flow(later.minusDays(30), CycleLog.FLOW_HEAVY),
                flow(later.minusDays(89), CycleLog.FLOW_HEAVY),
                flow(later.minusDays(31), CycleLog.FLOW_MEDIUM),
                flow(later.minusDays(32), CycleLog.FLOW_LIGHT),
                flow(later.minusDays(33), CycleLog.SPOTTING),
                flow(later.minusDays(90), CycleLog.FLOW_HEAVY),
                flow(later.minusDays(100), CycleLog.FLOW_HEAVY),
            ),
            profile = woman,
        )

        assertEquals(
            res(R.string.report_count_of, 3, ReportText.Quantity(R.plurals.report_lab_context_flow_days, 5)),
            section.valueOf(heavyFlow),
        )
        assertTrue(flowNote in section.footnotes)
    }

    /**
     * The flow line waits for a woman's profile, the cycle section and a ferritin or haemoglobin draw; a
     * window with spotting alone says "Not recorded" and cites nothing.
     */
    @Test
    fun heavyFlowWaitsForAWomanTheCycleAndAnIronMarker() {
        val heavy = flow(later.minusDays(3), CycleLog.FLOW_HEAVY)
        val iron = draws(LabMarker.FERRITIN, 40.0, 22.0)
        assertFalse(context(iron + heavy).prints(heavyFlow))
        assertFalse(context(iron + heavy, everything - ReportPart.CYCLE, woman).prints(heavyFlow))
        assertFalse(context(draws(LabMarker.LDL, 3.4, 3.0) + heavy, profile = woman).prints(heavyFlow))

        val spotting = context(iron + flow(later.minusDays(3), CycleLog.SPOTTING), profile = woman)
        assertEquals(res(R.string.report_lab_context_not_recorded), spotting.valueOf(heavyFlow))
        assertFalse(flowNote in spotting.footnotes)
    }
}
