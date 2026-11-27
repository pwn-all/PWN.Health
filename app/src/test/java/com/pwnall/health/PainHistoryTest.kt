package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The pain history: every pain entry and nothing else, newest first, and the short line each row
 * prints — the rating, the start counted from the entry, and whether it still hurt or how long it
 * lasted, each only when the entry has it.
 */
class PainHistoryTest {
    private val knee = RegionSide(PainRegion.KNEE, BodySide.LEFT)

    private fun row(id: Long, day: String, at: Long, kind: String = KIND_PAIN, report: PainReport = PainReport()) =
        JournalEntry(
            id = id,
            day = day,
            occurredAt = at,
            kind = kind,
            title = "Pain",
            calories = 0.0,
            measurementPayloadJson = encodePainPayload(report.copy(regions = listOf(knee))),
        )

    private fun line(report: PainReport, lastedMinutes: Int? = null) = painHistoryLine(
        report,
        lastedMinutes,
        score = { "$it/10" },
        onset = { it.name },
        sameAs = { "same as $it" },
        stillHurts = "still",
        lasted = { "lasted $it" },
    )

    /** Pain entries only, by day, then the moment on it, then the id; "no pain" answers among them. */
    @Test
    fun theHistoryIsEveryPainEntryNewestFirst() {
        val rows = listOf(
            row(1, "2026-10-01", 100),
            row(2, "2026-10-03", 50),
            row(3, "2026-10-03", 90),
            row(4, "2026-10-02", 500, kind = "EAT"),
            row(5, "2026-10-03", 90),
            row(6, "2026-10-02", 10).copy(measurementPayloadJson = encodePainPayload(PainReport(none = true))),
        )
        assertEquals(listOf(5L, 3L, 2L, 6L, 1L), painHistory(rows).map { it.entry.id })
        // Read once, with the row: a "no pain today" is known as one.
        assertEquals(true, painHistory(rows).single { it.entry.id == 6L }.report.none)
    }

    /** The rating, the start, and the status: still there, or how long it lasted. */
    @Test
    fun theLineSaysTheRatingTheStartAndTheStatus() {
        assertEquals("6/10 · YESTERDAY · still", line(PainReport(nrs = 6, onset = PainOnset.YESTERDAY)))
        assertEquals("3/10 · DAYS_2_3 · lasted 90", line(PainReport(nrs = 3, onset = PainOnset.DAYS_2_3), 90))
        // An older entry without a rating or a start says only what it has.
        assertEquals("still", line(PainReport()))
    }

    /** A follow-up has no start of its own: the line names the day the pain was first reported. */
    @Test
    fun aFollowUpNamesItsFirstReport() {
        val followUp = PainReport(nrs = 4, same = 1_234L, onset = PainOnset.JUST_NOW)
        assertEquals("4/10 · same as 1234 · still", line(followUp))
    }

    /** A "no pain today" has its name and its day, and nothing to add to them. */
    @Test
    fun noPainHasNoLine() {
        assertEquals("", line(PainReport(none = true), 30))
    }

    /** The start as the history prints it is a phrase of its own for every answer, old ones too. */
    @Test
    fun everyStartHasItsHistoryWords() {
        assertEquals(PainOnset.entries.size, PainOnset.entries.map { it.sinceRes }.toSet().size)
    }

    /** The first report is named with its year once the follow-up is in a later year. */
    @Test
    fun aFirstReportOfAnotherYearHasItsYear() {
        val zone = ZoneId.of("Europe/Moscow")
        val firstAt = LocalDate.of(2025, 10, 3).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("dMMM", painFirstReportSkeleton(firstAt, "2025-11-20", zone))
        assertEquals("dMMMy", painFirstReportSkeleton(firstAt, "2026-10-03", zone))
        // The last minutes of a year, in the phone's own zone, are still that year.
        val newYearsEve = LocalDate.of(2025, 12, 31).atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        assertEquals("dMMM", painFirstReportSkeleton(newYearsEve, "2025-12-31", zone))
        assertEquals("dMMMy", painFirstReportSkeleton(newYearsEve, "2026-01-02", zone))
    }
}
