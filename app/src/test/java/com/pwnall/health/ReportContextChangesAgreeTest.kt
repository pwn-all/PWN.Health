package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * The blood-test context and the changes section stand on one sheet and read the same fifty-six days
 * before each GGT draw for the same drinks. Given a rule each of its own for a day the diary was
 * kept, the two would print different counts for one window; here the whole sheet is built and the
 * two sections are read side by side.
 */
class ReportContextChangesAgreeTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 24)
    private val man = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1985-05-10")
    private val earlier: LocalDate = LocalDate.of(2026, 5, 1)
    private val later: LocalDate = LocalDate.of(2026, 9, 1)

    private fun at(day: LocalDate, hour: Int): Long = day.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun row(day: LocalDate, kind: String, hour: Int = 12, grams: Double = 0.0) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, hour),
        kind = kind,
        title = kind,
        calories = 0.0,
        servingG = grams,
    )

    private fun panel(day: LocalDate, ggt: Double, id: Long) = JournalEntry(
        id = id,
        day = day.toString(),
        occurredAt = at(day, 8),
        kind = KIND_LABS,
        title = "Labs",
        calories = 0.0,
        measurementPayloadJson = labsPayload(mapOf(LabMarker.GGT to ggt), fasting = true),
    )

    /**
     * The GGT window before [draw], oldest day first: [kept] days written in by hand, a drink of
     * [grams] on the first [drinking] of them, then eight days a watch's workout alone reached and
     * three with only a planned workout on them — days neither section may count.
     */
    private fun before(draw: LocalDate, kept: Int, drinking: Int, grams: Double): List<JournalEntry> {
        val days = (WINDOW - 1L downTo 0L).map { draw.minusDays(it) }
        return days.take(kept).map { row(it, KIND_EAT) } +
            days.take(drinking).map { row(it, KIND_ALCOHOL, hour = 20, grams = grams) } +
            days.drop(kept).take(8).map {
                row(it, "ACTIVITY", hour = 18).copy(healthConnectRecordKey = "Health Connect:com.watch:$it")
            } +
            days.drop(kept + 8).take(3).map { row(it, KIND_PLAN, hour = 18) }
    }

    private val diary: List<JournalEntry> by lazy {
        listOf(panel(earlier, 22.0, id = 1), panel(later, 41.0, id = 2)) +
            before(earlier, kept = 36, drinking = 5, grams = 10.0) +
            before(later, kept = 40, drinking = 40, grams = 30.0)
    }

    private fun report(steps: List<DaySteps> = emptyList()) = doctorReport(
        sources = ReportSources(man, diary, steps, emptyList()),
        request = ReportRequest(from = today.minusMonths(6), to = today, parts = ReportPart.entries.toSet()),
        today = today,
        zone = ZoneOffset.UTC,
    )

    private fun DoctorReport.section(part: ReportPart): ReportSection =
        sections.first { it.part == part && it.presence == SectionPresence.PRESENT }

    private fun res(id: Int, vararg args: Any) = ReportText.Res(id, args.toList())

    private fun daysOf(count: Int) =
        res(R.string.report_count_of, count, ReportText.Quantity(R.plurals.count_days, WINDOW))

    @Test
    fun bothSectionsCountTheSameDaysKeptBeforeEachDraw() {
        val built = report()

        val context = built.section(ReportPart.LAB_CONTEXT).rows
            .filter { it.label == ReportText.Quantity(R.plurals.report_lab_context_alcohol_window, WINDOW) }
            .map { it.note }
        val window = res(
            R.string.report_changes_blood_window,
            res(R.string.alcohol_title),
            ReportText.Quantity(R.plurals.count_days, WINDOW),
        )
        val changes = built.section(ReportPart.CHANGES).rows.single { it.label == window }

        // Oldest draw first in the context, earlier window first in the changes: 36 days, then 40.
        assertEquals(listOf(36, 40).map { ReportText.Quantity(R.plurals.report_lab_context_typed_days, it) }, context)
        assertEquals(
            res(R.string.report_changes_on_days_by_hand, daysOf(36), daysOf(40)),
            (changes.note as ReportText.Join).parts.first(),
        )
    }

    /**
     * The sheet's digits are Latin whatever the phone's language ([DoctorReportSheetDigitsTest] holds
     * that for what a resource formats). A figure a section formats itself into a string before the
     * resource sees it takes the phone's default locale instead, and on an Arabic phone the steps a
     * day before a draw would come out in Arabic-Indic digits among Latin ones.
     */
    @Test
    fun theFiguresTheSectionsFormatThemselvesAreInLatinDigitsOnAnArabicPhone() {
        val steps = (0L..200L).map { DaySteps(today.minusDays(it).toString(), 8_432L) }
        val wasDefault = Locale.getDefault()
        val texts = try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val built = report(steps)
            listOf(ReportPart.CHANGES, ReportPart.LAB_CONTEXT).flatMap { part ->
                built.section(part).rows.flatMap { listOfNotNull(it.label, it.value, it.note) }
            }.flatMap(::strings)
        } finally {
            Locale.setDefault(wasDefault)
        }

        assertTrue(texts.toString(), texts.any { it.filter(Char::isDigit) == "8432" })
        assertEquals(emptyList<String>(), texts.filter { text -> text.any { it.isDigit() && it !in '0'..'9' } })
    }

    /** The text a row carries already written, rather than left for a resource to fill in. */
    private fun strings(text: Any): List<String> = when (text) {
        is String -> listOf(text)
        is ReportText.Plain -> listOf(text.text)
        is ReportText.Res -> text.args.flatMap(::strings)
        is ReportText.Join -> text.parts.flatMap(::strings)
        else -> emptyList()
    }

    private companion object {
        /** GGT's food window, [LAB_DIET_WINDOWS], which both sections read before a draw. */
        const val WINDOW = 56
    }
}
